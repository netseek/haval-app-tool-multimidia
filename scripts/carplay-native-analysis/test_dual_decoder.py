import os
import re
from pathlib import Path
import selectors
import shutil
import subprocess
from tempfile import TemporaryDirectory
import time
import unittest


@unittest.skipUnless(shutil.which('ffmpeg'), 'FFmpeg is not installed')
class DualDecoderTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('CARPLAY_PACKET_CONVERTER'), 'Native host converter not supplied')
    def test_native_conversion_preserves_decoded_video(self):
        with TemporaryDirectory() as directory:
            original = Path(directory) / 'original.h264'
            prefixed = Path(directory) / 'prefixed.bin'
            converted = Path(directory) / 'converted.h264'
            subprocess.run([
                'ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi',
                '-i', 'testsrc2=s=160x96:r=10:d=1', '-an', '-c:v', 'libx264',
                '-threads', '1', '-preset', 'ultrafast', '-tune', 'zerolatency',
                '-pix_fmt', 'yuv420p', '-f', 'h264', str(original),
            ], check=True, timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            segments = re.split(rb'\x00\x00\x00?\x01', original.read_bytes())
            self.assertEqual(segments[0], b'')
            nals = segments[1:]
            self.assertTrue(all(nals))
            self.assertGreater(len(nals), 2)
            prefixed.write_bytes(b''.join(len(nal).to_bytes(4, 'big') + nal for nal in nals))
            before = prefixed.read_bytes()
            with converted.open('wb') as output:
                subprocess.run([os.environ['CARPLAY_PACKET_CONVERTER'], str(prefixed)],
                               check=True, timeout=10, stdout=output, stderr=subprocess.PIPE)
            self.assertEqual(prefixed.read_bytes(), before)
            decoded = []
            for source in [original, converted]:
                result = subprocess.run([
                    'ffmpeg', '-nostdin', '-v', 'error', '-threads', '1', '-i', str(source),
                    '-an', '-threads', '1', '-f', 'framemd5', 'pipe:1',
                ], check=True, timeout=15, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                frames = [line for line in result.stdout.splitlines() if line and not line.startswith(b'#')]
                self.assertEqual(len(frames), 10)
                decoded.append(frames)
            self.assertEqual(decoded[0], decoded[1])

    def test_secondary_restart_preserves_principal_decoder(self):
        processes = []
        selector = selectors.DefaultSelector()
        buffers = {}
        frames = {}
        headers = {}

        def start(name, source):
            process = subprocess.Popen([
                'ffmpeg', '-nostdin', '-v', 'error', '-threads', '1',
                '-re', '-stream_loop', '-1', '-i', str(source),
                '-an', '-threads', '1', '-f', 'framemd5', 'pipe:1',
            ], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            processes.append(process)
            buffers[name] = b''
            frames[name] = []
            headers[name] = []
            selector.register(process.stdout, selectors.EVENT_READ, (name, process))
            return process

        def collect(targets):
            deadline = time.monotonic() + 12
            while any(len(frames[name]) < target for name, target in targets.items()):
                self.assertLess(time.monotonic(), deadline, 'Decoder frame deadline exceeded')
                for event, unused_mask in selector.select(timeout=0.2):
                    name, process = event.data
                    chunk = os.read(event.fd, 4096)
                    self.assertTrue(chunk, 'Unexpected decoder EOF: ' + name)
                    buffers[name] += chunk
                    self.assertLess(len(buffers[name]), 16384)
                    while b'\n' in buffers[name]:
                        line, buffers[name] = buffers[name].split(b'\n', 1)
                        if line.startswith(b'#'):
                            headers[name].append(line)
                        elif line.strip():
                            fields = line.split(b',')
                            self.assertEqual(len(fields), 6)
                            frames[name].append(fields[-1].strip())
                            self.assertLess(len(frames[name]), 200)
                    self.assertIsNone(process.poll(), 'Decoder unexpectedly stopped: ' + name)

        def stop(process):
            selector.unregister(process.stdout)
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
            process.stdout.close()

        try:
            with TemporaryDirectory() as directory:
                sources = []
                for name, color, size in [('main', 'red', '320x180'),
                                          ('secondary', 'blue', '160x96'),
                                          ('replacement', 'green', '192x112')]:
                    source = Path(directory) / (name + '.h264')
                    subprocess.run([
                        'ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi',
                        '-i', f'color=c={color}:s={size}:r=10:d=1', '-an',
                        '-c:v', 'libx264', '-threads', '1', '-preset', 'ultrafast',
                        '-tune', 'zerolatency', '-pix_fmt', 'yuv420p',
                        '-f', 'h264', str(source),
                    ], check=True, timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                    sources.append(source)
                principal = start('main', sources[0])
                secondary = start('secondary', sources[1])
                collect({'main': 3, 'secondary': 3})
                self.assertNotEqual(frames['main'][0], frames['secondary'][0])
                self.assertTrue(any(b'320x180' in line for line in headers['main']))
                self.assertTrue(any(b'160x96' in line for line in headers['secondary']))
                principal_pid = principal.pid
                before_stop = len(frames['main'])
                stop(secondary)
                collect({'main': before_stop + 3})
                replacement = start('replacement', sources[2])
                before_restart = len(frames['main'])
                collect({'main': before_restart + 3, 'replacement': 3})
                self.assertEqual(principal.pid, principal_pid)
                self.assertIsNone(principal.poll())
                self.assertIsNone(replacement.poll())
                self.assertTrue(any(b'192x112' in line for line in headers['replacement']))
                self.assertNotEqual(frames['main'][0], frames['replacement'][0])
                self.assertEqual(len(set(frames['main'])), 1)
        finally:
            for process in processes:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=3)
                if process.stdout and not process.stdout.closed:
                    process.stdout.close()
            selector.close()


if __name__ == '__main__':
    unittest.main()
