"""Audit the real input read-only; exercise output only with a synthetic ELF fixture."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import patch_bootstrap_injector as injector


RAW = Path(__file__).resolve().parents[2] / 'app/src/main/res/raw/fridainject'


class BootstrapInjectorProfileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = json.loads(injector.PROFILE.read_text())
        cls.original = RAW.read_bytes()

    def test_real_input_audit_is_pinned_and_does_not_emit_an_artifact(self):
        metadata = injector.audit(self.original, self.profile)
        self.assertEqual(injector.ORIGINAL_SHA, metadata['injectorOriginalSha256'])
        self.assertEqual(injector.PATCHED_SHA, metadata['injectorSha256'])
        self.assertEqual(len(self.original), metadata['injectorSize'])
        self.assertEqual(injector.ORIGINAL_SHA, hashlib.sha256(RAW.read_bytes()).hexdigest())

    def test_unknown_profile_or_any_instruction_change_is_rejected(self):
        variants = {
            'schema': 2, 'id': 'different', 'policyMode': 'enforcing',
            'upstreamVersion': '17.5.3', 'originalSha256': '0' * 64,
            'patchedSha256': '1' * 64, 'originalSize': len(self.original) - 1,
            'patchFileOffset': self.profile['patchFileOffset'] + 4,
            'patchAddress': '0x44af9c', 'tailTargetAddress': '0x2dd23e0',
            'originalInstructionHex': '111da694',  # BL would not permit this RET.
            'replacementInstructionHex': '1f2003d5',  # NOP is a different transform.
            'callerHex': '00' * 24,
            'callerFileOffset': self.profile['callerFileOffset'] - 4,
        }
        for key, value in variants.items():
            profile = dict(self.profile, **{key: value})
            with self.subTest(field=key), self.assertRaises(ValueError):
                injector.audit(self.original, profile)
        changed = bytearray(self.original)
        changed[-1] ^= 1
        with self.assertRaisesRegex(ValueError, 'immutable artifact'):
            injector.audit(changed, self.profile)

    def synthetic_fixture(self):
        """Minimal data layout, not an executable Frida/Android artifact."""
        profile = copy.deepcopy(self.profile)
        offset = profile['patchFileOffset']
        data = bytearray(offset + 4)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<HH', data, 16, 3, 183)
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0x1000, 0x1000,
                         len(data), len(data), 0x1000)
        data[profile['callerFileOffset']:offset + 4] = bytes.fromhex(profile['callerHex'])
        profile['originalSize'] = len(data)
        profile['originalSha256'] = hashlib.sha256(data).hexdigest()
        expected = bytearray(data)
        expected[offset:offset + 4] = bytes.fromhex(profile['replacementInstructionHex'])
        profile['patchedSha256'] = hashlib.sha256(expected).hexdigest()
        return bytes(data), bytes(expected), profile

    def test_synthetic_output_changes_only_the_reviewed_tail_instruction(self):
        original, expected, profile = self.synthetic_fixture()
        with tempfile.TemporaryDirectory(prefix='synthetic-injector-profile-') as temporary:
            directory = Path(temporary)
            source, output, profile_path = (directory / item for item in ('input', 'output', 'profile.json'))
            source.write_bytes(original)
            profile_path.write_text(json.dumps(profile))
            with patch.object(injector, 'PROFILE', profile_path), \
                    patch.object(injector, 'ORIGINAL_SHA', profile['originalSha256']), \
                    patch.object(injector, 'PATCHED_SHA', profile['patchedSha256']):
                metadata = injector.build(source, output)
                self.assertEqual(expected, output.read_bytes())
                self.assertEqual(original, source.read_bytes())
                self.assertEqual(hashlib.sha256(expected).hexdigest(), metadata['injectorSha256'])
                differences = [index for index, pair in enumerate(zip(original, expected)) if pair[0] != pair[1]]
                self.assertEqual(list(range(profile['patchFileOffset'], profile['patchFileOffset'] + 4)), differences)
                with self.assertRaisesRegex(ValueError, 'new generated injector destination'):
                    injector.build(source, output)
                self.assertEqual(expected, output.read_bytes())

    def test_synthetic_non_executable_or_wrong_address_mapping_is_rejected(self):
        original, _, profile = self.synthetic_fixture()
        for label, header_offset, value, fmt in (
                ('non_executable', 68, 4, '<I'),
                ('wrong_address', 80, 0x2000, '<Q'),
                ('bad_header_size', 54, 1, '<H'),
                ('too_many_headers', 56, 129, '<H')):
            data = bytearray(original)
            struct.pack_into(fmt, data, header_offset, value)
            modified = dict(profile, originalSha256=hashlib.sha256(data).hexdigest())
            with self.subTest(case=label), \
                    patch.object(injector, 'ORIGINAL_SHA', modified['originalSha256']), \
                    patch.object(injector, 'PATCHED_SHA', modified['patchedSha256']), \
                    self.assertRaises(ValueError):
                injector.audit(data, modified)


if __name__ == '__main__':
    unittest.main()
