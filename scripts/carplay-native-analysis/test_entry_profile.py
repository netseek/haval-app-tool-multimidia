import hashlib
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from entry_profile import generate


class EntryProfileRejectionTests(unittest.TestCase):
    def test_changed_artifact_rejected_before_binding(self):
        with TemporaryDirectory() as directory:
            artifact = Path(directory) / 'vendor__app__TsCarPlayService__lib__arm64__libAirPlay_ts.so'
            artifact.write_bytes(b'not the captured firmware')
            with self.assertRaisesRegex(ValueError, 'Unsupported input hash'):
                generate(directory)


@unittest.skipUnless(os.environ.get('CARPLAY_NATIVE_DIR'), 'Private captured binaries not supplied')
class CapturedEntryProfileTests(unittest.TestCase):
    def test_all_nine_oem_entries_and_owner_apis_match_reviewed_artifacts(self):
        profile = generate(os.environ['CARPLAY_NATIVE_DIR'])
        canonical = json.dumps(profile, sort_keys=True, separators=(',', ':')).encode()
        self.assertEqual(hashlib.sha256(canonical).hexdigest(),
                         '2cd70fbc84b06ef7b69379f83f8629e0baea55a93704cfc852162dbea7c33940')
        self.assertEqual(profile['version'], 2)
        self.assertEqual(profile['kind'], 'oem-entry-binding-v1')
        modules = {module['name']: module for module in profile['modules']}
        self.assertEqual(modules['libAirPlay_ts.so']['functions']['session']['offset'], 0x4401c)
        stack = modules['libcarplaystack.so']['functions']
        self.assertEqual({name: target['offset'] for name, target in stack.items()}, {
            'initialize': 0x21234, 'finalize': 0x212bc, 'dimensions': 0x21318,
            'start': 0x2140c, 'stop': 0x21498, 'avcc': 0x21558,
            'data': 0x217f8, 'displays': 0x158c4,
        })
        self.assertEqual(set(modules['libCoreUtils_ts.so']['functions']),
                         {'getLength', 'copyString', 'retain', 'release'})


if __name__ == '__main__':
    unittest.main()
