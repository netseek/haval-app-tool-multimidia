import hashlib
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from audit import analyze, branch_target


class BranchTests(unittest.TestCase):
    def test_direct_call(self):
        self.assertEqual(branch_target(SimpleNamespace(mnemonic='bl', op_str='#0x1234')), 0x1234)

    def test_direct_branch(self):
        self.assertEqual(branch_target(SimpleNamespace(mnemonic='b', op_str='#0x5678')), 0x5678)

    def test_indirect_call_is_not_resolved(self):
        self.assertIsNone(branch_target(SimpleNamespace(mnemonic='blr', op_str='x8')))

    def test_conditional_branch_is_not_a_call(self):
        self.assertIsNone(branch_target(SimpleNamespace(mnemonic='b.eq', op_str='#0x1234')))

    def test_hash_rejected_before_parsing(self):
        with TemporaryDirectory() as directory:
            artifact = Path(directory) / 'invalid.so'
            artifact.write_bytes(b'not executable')
            with self.assertRaisesRegex(ValueError, 'hash differs'):
                analyze(artifact, '0' * 64, ['anything'])


@unittest.skipUnless(os.environ.get('CARPLAY_NATIVE_DIR'), 'Private captured binaries not supplied')
class CapturedStackTests(unittest.TestCase):
    def setUp(self):
        self.path = Path(os.environ['CARPLAY_NATIVE_DIR']) / 'vendor__app__TsCarPlayService__lib__arm64__libcarplaystack.so'
        self.digest = '8d4c1fe001d3e8bf1299438a96ca07e12470bc60d75d0d52fe89fe5d18f85931'
        self.function = '_ZL18_getScreenDisplaysPi'

    def test_exact_display_announcement_body(self):
        result = analyze(self.path, self.digest, [self.function])
        function = result['functions'][0]
        calls = {call['symbol'] for call in function['direct_transfers']}
        self.assertIn('AirPlayInfoArrayAddMainScreenDisplay', calls)
        self.assertIn('AirPlayInfoArrayAddMainScreenDisplayWithViewAreas', calls)
        self.assertFalse(any(name and 'AddAltScreen' in name for name in calls))
        self.assertEqual(function['indirect_transfers'], [])
        self.assertTrue(result['static_only'])

    def test_missing_symbol_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Missing functions'):
            analyze(self.path, self.digest, ['missing_function'])

    def test_video_callback_table_is_lossless(self):
        result = analyze(self.path, self.digest, [self.function])
        callbacks = result['video_callback_table']
        self.assertEqual([slot['offset'] for slot in callbacks], list(range(0, 56, 8)))
        self.assertIn('Initialize', callbacks[0]['symbol'])
        self.assertIn('Finalize', callbacks[1]['symbol'])
        self.assertIn('SetWidthHeight', callbacks[2]['symbol'])
        self.assertIn('Start', callbacks[3]['symbol'])
        self.assertIn('Stop', callbacks[4]['symbol'])
        self.assertIn('SetAVCC', callbacks[5]['symbol'])
        self.assertIn('ProcessData', callbacks[6]['symbol'])

    def test_input_unchanged(self):
        before = hashlib.sha256(self.path.read_bytes()).hexdigest()
        analyze(self.path, self.digest, [self.function])
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).hexdigest(), before)


@unittest.skipUnless(os.environ.get('CARPLAY_NATIVE_DIR'), 'Private captured binaries not supplied')
class CapturedLoaderTests(unittest.TestCase):
    def test_coreutils_explicit_proxy_loader(self):
        path = Path(os.environ['CARPLAY_NATIVE_DIR']) / 'vendor__app__TsCarPlayService__lib__arm64__libCoreUtils_ts.so'
        result = analyze(path, '8466bd2e24e3f8eb0217d33686d03b54806993074c3c33d3a1e5608fdd90fed0', ['ScreenStreamCreate'])
        function = result['functions'][0]
        calls = {call['symbol'].split('@')[0] for call in function['direct_transfers'] if call['symbol']}
        self.assertTrue({'dlopen', 'dlsym'}.issubset(calls))
        literals = {entry['value'] for entry in function['adjacent_literal_references']}
        self.assertIn('libcarplay_proxy_ts.so', literals)
        expected = {'ScreenStreamInitialize', 'ScreenStreamFinalize',
                    'ScreenStreamSetDelegateContext', 'ScreenStreamSetWidthHeight',
                    'ScreenStreamSetViewArea', 'ScreenStreamSetAVCC',
                    'ScreenStreamStart', 'ScreenStreamStop', 'ScreenStreamProcessData'}
        self.assertTrue(expected.issubset(literals))


if __name__ == '__main__':
    unittest.main()
