"""Offline ABI/profile and source lifecycle guards; never execute Android code."""
from pathlib import Path
import hashlib
import re
import struct
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
NAV = ROOT / 'app/src/main/java/br/com/redesurftank/havalshisuku/navigation'
APK = ROOT / 'app/src/main/assets/aa_patches/AndroidAutoService.apk'


def method_instructions(dex, class_name, method_name):
    def u32(at):
        return struct.unpack_from('<I', dex, at)[0]

    def leb(at):
        value = 0
        for shift in range(0, 35, 7):
            byte = dex[at]
            at += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, at
        raise ValueError('Invalid ULEB128')

    def string(index):
        at = u32(u32(0x3c) + 4 * index)
        _, at = leb(at)
        return dex[at:dex.index(0, at)].decode('utf8')

    def typ(index):
        return string(u32(u32(0x44) + index * 4))

    for offset in range(u32(0x64), u32(0x64) + u32(0x60) * 32, 32):
        if typ(u32(offset)) != class_name:
            continue
        cursor = u32(offset + 24)
        counts = []
        for _ in range(4):
            count, cursor = leb(cursor)
            counts.append(count)
        for _ in range(sum(counts[:2])):
            _, cursor = leb(cursor)
            _, cursor = leb(cursor)
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, cursor = leb(cursor)
                index += delta
                _, cursor = leb(cursor)
                code, cursor = leb(cursor)
                _, _, name = struct.unpack_from('<HHI', dex, u32(0x5c) + 8 * index)
                if string(name) == method_name:
                    units = u32(code + 12)
                    return dex[code + 16:code + 16 + 2 * units]
    raise AssertionError('Expected method is absent')


class CarPlayAndroidAutoObserverGuardTest(unittest.TestCase):
    def test_reviewed_apk_and_no_op_start_are_exactly_pinned(self):
        profile = (NAV / 'CarPlayAndroidAutoObservationPolicy.kt').read_text()
        expected = re.search(r'APK_SHA256 = "([a-f0-9]{64})"', profile).group(1)
        self.assertEqual(expected, hashlib.sha256(APK.read_bytes()).hexdigest())
        self.assertEqual(2_472_405, APK.stat().st_size)
        with zipfile.ZipFile(APK) as archive:
            instructions = method_instructions(archive.read('classes.dex'),
                'Lcom/ts/androidauto/projectionservice/AndroidAutoService;', 'onServiceStart')
        # DEX nop; return-void. A replacement profile needs a new independent review.
        self.assertEqual(bytes.fromhex('00000e00'), instructions)

    def test_reader_can_only_query_the_existing_profiled_link_command(self):
        source = (NAV / 'CarPlayAndroidAutoObserver.kt').read_text()
        self.assertNotIn('BIND_AUTO_CREATE', source)
        self.assertEqual(1, source.count('.bindService('))
        self.assertIn('candidate, 0)', source)
        self.assertIn('if (approval != null) requestBinding(s, approval)', source)
        self.assertIn('!unchanged(approval)', source)
        self.assertEqual(['GET_LINK_STATUS'], re.findall(r'\.transact\((\w+),', source))
        self.assertIn('GET_LINK_STATUS = 0x15', source)
        self.assertIn('binder.interfaceDescriptor != DESCRIPTOR', source)
        self.assertLess(source.index('if (!admitted()) return null'), source.index('binder.transact(GET_LINK_STATUS'))
        self.assertIn('reply.dataAvail() != Int.SIZE_BYTES', source)
        self.assertIn('reply.dataAvail() == 0', source)
        self.assertNotRegex(source, r'\.(?:startService|startActivity|sendBroadcast)\(')
        self.assertNotIn('ensureAndroidAutoLinkCommandBound', source)
        self.assertNotIn('AndroidAutoDcmRecovery', source)

    def test_blocking_reads_and_late_callbacks_cannot_own_the_next_capture(self):
        source = (NAV / 'CarPlayAndroidAutoObserver.kt').read_text()
        host = (NAV / 'CarPlayExperimentalHud.kt').read_text()
        self.assertIn('newSingleThreadScheduledExecutor', source)
        self.assertIn('SystemClock.elapsedRealtime() < s.deadlineMs', source)
        self.assertIn('session === s', source)
        self.assertIn('inRead.compareAndSet(read, 0)', source)
        self.assertIn('bound === slot', source)
        self.assertIn('READ_TIMEOUT', source)
        self.assertLess(source.index('session = null'), source.index('release(previous?.context)'))
        for callback in ('onServiceDisconnected', 'onBindingDied', 'onNullBinding'):
            self.assertIn('override fun ' + callback, source)
        self.assertIn('captureGeneration != next.generation', host)
        self.assertIn('SystemClock.elapsedRealtime() >= captureDeadlineMs', host)
        self.assertIn('observer.stop()', host)
        self.assertIn('policy.nextRevalidationMs(now, captureDeadlineMs', host)
        self.assertIn('else if (session === s) main.post { if (session === s) stop() }', source)
        self.assertIn('!it.endsWith("_age")', host)
        self.assertIn('now - lastDiagnosticAtMs >= 5_000L', host)


if __name__ == '__main__':
    unittest.main()
