import hashlib
import json
import os
import unittest

from cold_profile import generate


@unittest.skipUnless(os.environ.get('CARPLAY_NATIVE_DIR'), 'Private captured binaries not supplied')
class CapturedColdProfileTests(unittest.TestCase):
    def test_full_executable_segments_and_function_bodies_are_pinned(self):
        profile = generate(os.environ['CARPLAY_NATIVE_DIR'])
        canonical = json.dumps(profile, sort_keys=True, separators=(',', ':')).encode()
        self.assertEqual(hashlib.sha256(canonical).hexdigest(),
                         '31b878ba35c1a7fec07d7e854264837ef3702962a1e059fa5d020afa0d7c4d0f')
        self.assertEqual(profile['version'], 3)
        self.assertEqual(profile['kind'], 'oem-cold-interception-v1')
        self.assertEqual(sum(len(module['functions']) for module in profile['modules']), 13)
        for module in profile['modules']:
            self.assertTrue(module['executable'])
            self.assertGreater(module['fileSize'], module['executable'][0]['size'])
            for target in module['functions'].values():
                self.assertGreaterEqual(target['size'], 16)
                self.assertEqual(len(target['sha256']), 64)


if __name__ == '__main__':
    unittest.main()
