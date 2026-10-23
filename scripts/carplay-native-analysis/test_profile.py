import hashlib
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from probe_profile import generate


class ProfileRejectionTests(unittest.TestCase):
    def test_changed_binary_rejected_before_elf_parsing(self):
        with TemporaryDirectory() as directory:
            artifact = Path(directory) / 'vendor__app__TsCarPlayService__lib__arm64__libAirPlay_ts.so'
            artifact.write_bytes(b'unsupported binary')
            with self.assertRaisesRegex(ValueError, 'Unsupported input hash'):
                generate(directory)


@unittest.skipUnless(os.environ.get('CARPLAY_NATIVE_DIR'), 'Private captured binaries not supplied')
class CapturedProfileTests(unittest.TestCase):
    def test_profile_matches_observer_reviewed_digest(self):
        profile = generate(os.environ['CARPLAY_NATIVE_DIR'])
        canonical = json.dumps(profile, sort_keys=True, separators=(',', ':')).encode()
        self.assertEqual(hashlib.sha256(canonical).hexdigest(),
                         '4e032c87870602a0810530e583c677d201d17cc4b2a15c82477af94a5a8037b6')
        self.assertEqual(profile['architecture'], 'arm64')
        self.assertEqual(len(profile['modules']), 3)
        self.assertEqual(sum(len(module['functions']) for module in profile['modules']), 7)


if __name__ == '__main__':
    unittest.main()
