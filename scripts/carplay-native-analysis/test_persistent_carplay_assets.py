import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import sys
from unittest.mock import patch

import build_persistent_carplay_assets as assets
import patch_bootstrap_injector as injector
from cold_passthrough_controller import ENDPOINT_STATUS_PROCESS, endpoint_launcher, read_endpoint_bundle

ROOT = Path(__file__).resolve().parent
MINI_NATIVE = Path('/root/projects/haval-impulse/artifacts/carplay-namespace-2f6e6961-debug/native/libcarplay_video_bridge.so')
HAS_COMPILER = (ROOT / 'agent/node_modules/.bin/frida-compile').exists()
MINI_ASSET_TESTS = sys.platform == 'darwin' and os.environ.get('IMPULSE_MINI_ASSET_TESTS') == '1'
BOOT = '12345678-abcd-4abc-8def-123456789abc'


def proc_stat(pid=42, start='123', state='S'):
    return f'{pid} (com.ts.carplay (worker)) ' + ' '.join([state] + ['0'] * 18 + [start] + ['0'] * 3)


class PersistentCarPlayAssetsTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('node'), 'Node required for emitted runtime resolver test')
    def test_generated_endpoint_uses_full_boot_pid_and_kernel_start_time(self):
        source = (ROOT / 'carplay_clean_map_endpoint.js').read_text()
        resolver = endpoint_launcher(source, status_mode=ENDPOINT_STATUS_PROCESS).split('function impulsePublishEndpoint', 1)[0]
        cases = [
            {'boot': BOOT.upper() + '\n', 'stat': proc_stat(), 'pid': 42},
            {'boot': BOOT, 'stat': proc_stat(start='999'), 'pid': 42},
            {'boot': 'invalid', 'stat': proc_stat(), 'pid': 42},
            {'boot': BOOT, 'stat': proc_stat(pid=99), 'pid': 42},
            {'boot': BOOT, 'stat': proc_stat(start='0'), 'pid': 42},
            {'boot': BOOT, 'stat': proc_stat(state='Z'), 'pid': 42},
        ]
        harness = r"""
const fs = require('node:fs'), vm = require('node:vm');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const results = input.cases.map(item => {
    let closed = 0;
    class File {
        constructor(path, mode) {
            if (mode !== 'r') throw new Error('Unexpected write in resolver');
            this.path = path;
        }
        readText() {
            if (this.path === '/proc/sys/kernel/random/boot_id') return item.boot;
            if (this.path === '/proc/self/stat') return item.stat;
            throw new Error('Unexpected identity source');
        }
        close() { closed++; }
    }
    const context = { File, Process: { id: item.pid } };
    try {
        vm.runInNewContext(input.source + ';this.result=impulseEndpointStatusPath;', context);
        return { path: context.result, closed };
    } catch (error) { return { error: String(error), closed }; }
});
process.stdout.write(JSON.stringify(results));
"""
        result = subprocess.run(['node', '-e', harness], input=json.dumps({'source': resolver, 'cases': cases}),
                                text=True, check=True, capture_output=True)
        results = json.loads(result.stdout)
        self.assertEqual(f'{assets.RUNTIME_ROOT}/{BOOT}-42-123/endpoint-state.log', results[0]['path'])
        self.assertEqual(f'{assets.RUNTIME_ROOT}/{BOOT}-42-999/endpoint-state.log', results[1]['path'])
        self.assertEqual(2, results[0]['closed'])
        self.assertTrue(all('error' in result for result in results[2:]))

    def test_revision_digest_covers_program_profile_and_generator_changes(self):
        essential = {'cold_oem_interceptor.js', 'carplay_clean_map_endpoint.js', 'make_cold_launcher.py',
                     'impulse_residency_peer.js',
                     'patch_bootstrap_injector.py', 'testdata/frida-injector-17.5.2-permissive.json',
                     'cold_passthrough_controller.py', 'testdata/oem-cold-profile-v3.json',
                     'testdata/android9-linker-profile.json', 'agent/package.json', 'agent/package-lock.json'}
        self.assertTrue(essential.issubset(assets.SOURCE_FILES))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in assets.SOURCE_FILES:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
            project = root / 'project'
            for name in assets.BOOTSTRAP_SOURCE_FILES:
                path = project / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
            with patch.object(assets, 'ROOT', root), patch.object(assets, 'PROJECT_ROOT', project):
                first = assets.source_sha256('a' * 64)
                self.assertEqual(first, assets.source_sha256('a' * 64))
                self.assertNotEqual(first, assets.source_sha256('b' * 64))
                for name in essential:
                    path = root / name
                    original = path.read_bytes()
                    path.write_bytes(original + b'changed')
                    self.assertNotEqual(first, assets.source_sha256('a' * 64), name)
                    path.write_bytes(original)
                for name in assets.BOOTSTRAP_SOURCE_FILES:
                    path = project / name
                    original = path.read_bytes()
                    path.write_bytes(original + b'controller repair')
                    self.assertNotEqual(first, assets.source_sha256('a' * 64), name)
                    path.write_bytes(original)

    def test_invalid_native_or_version_never_starts_a_compiler(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(assets, 'build_endpoint') as compiler:
            root = Path(temporary)
            wrong = root / 'wrong.so'
            wrong.write_bytes(b'not an Android shared library')
            with self.assertRaisesRegex(ValueError, 'prebuilt ELF64'):
                assets.build(wrong, root / 'assets')
            for version in [0, -1, True, 2_147_483_648]:
                with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'version code'):
                    assets.build(wrong, root / 'assets', version)
            compiler.assert_not_called()
            self.assertFalse((root / 'assets').exists())

    def test_existing_generated_assets_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            existing = output / 'carplay_bridge'
            existing.mkdir()
            sentinel = existing / 'keep'
            sentinel.write_text('original')
            with self.assertRaisesRegex(ValueError, 'never overwritten'):
                assets.build(output / 'missing.so', output)
            self.assertEqual('original', sentinel.read_text())

    def test_profiled_injector_is_a_separate_hashed_asset(self):
        self.assertEqual(1, assets.ASSET_FILES.count('cpbridge-inject'))
        self.assertEqual({'libcarplay_video_bridge.so', 'cold-launcher-template.js',
                          'endpoint.js', 'endpoint.js.manifest.json', 'cpbridge-inject'},
                         set(assets.ASSET_FILES))
        original = assets.INJECTOR.read_bytes()
        metadata = injector.audit(original, json.loads(injector.PROFILE.read_text()))
        self.assertEqual(assets.file_sha256(assets.INJECTOR), metadata['injectorOriginalSha256'])
        self.assertNotEqual(metadata['injectorOriginalSha256'], metadata['injectorSha256'])
        self.assertEqual(injector.PATCHED_SHA, metadata['injectorSha256'])
        self.assertEqual(injector.PROFILE_ID, metadata['patchProfile'])
        self.assertEqual(injector.POLICY_MODE, metadata['policyMode'])

    @unittest.skipUnless(MINI_ASSET_TESTS and MINI_NATIVE.exists() and HAS_COMPILER,
                         'Real Android asset generation is opt-in on the authorized Mini only')
    def test_real_assets_are_reproducible_and_bind_native_agent_and_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = assets.build(MINI_NATIVE, root / 'first')
            second = assets.build(MINI_NATIVE, root / 'second')
            self.assertEqual(first, second)
            directory = root / 'first/carplay_bridge'
            self.assertEqual({'manifest.json', *assets.ASSET_FILES}, {path.name for path in directory.iterdir()})
            self.assertEqual(MINI_NATIVE.read_bytes(), (directory / 'libcarplay_video_bridge.so').read_bytes())
            self.assertEqual(assets.file_sha256(assets.INJECTOR), first['injectorOriginalSha256'])
            self.assertEqual(injector.PATCHED_SHA, first['injectorSha256'])
            self.assertEqual(assets.file_sha256(directory / 'cpbridge-inject'), first['injectorSha256'])
            self.assertEqual(injector.PROFILE_ID, first['patchProfile'])
            self.assertEqual(injector.POLICY_MODE, first['policyMode'])
            self.assertEqual(assets.INJECTOR.stat().st_size, first['injectorSize'])
            self.assertEqual(1, first['schema'])
            self.assertEqual(74136, first['versionCode'])
            self.assertEqual(f"74136-{assets.file_sha256(MINI_NATIVE)[:12]}-{first['sourceSha256'][:12]}", first['revision'])
            launcher = (directory / 'cold-launcher-template.js').read_text()
            self.assertEqual(1, launcher.count('"expectedPid":1234567890'))
            self.assertEqual(1, launcher.count('BOOT_PROCESS_PLACEHOLDER'))
            self.assertIn(first['remoteDirectory'] + '/libcarplay_video_bridge.so', launcher)
            self.assertIn('/runtime/BOOT_PROCESS_PLACEHOLDER/cold-state.json', launcher)
            self.assertEqual(assets.ELIGIBILITY_PATH, first['eligibilityPath'])
            options = json.loads(re.search(r"const impulseOptions = (\{[^\n]+\});", launcher).group(1))
            self.assertEqual(assets.ELIGIBILITY_PATH, options['eligibilityPath'])
            read_endpoint_bundle(directory / 'endpoint.js',
                                 bridge_path=first['remoteDirectory'] + '/libcarplay_video_bridge.so',
                                 status_mode=ENDPOINT_STATUS_PROCESS)
            for record in first['files']:
                self.assertEqual(assets.file_sha256(directory / record['name']), record['sha256'])
                self.assertEqual((directory / record['name']).stat().st_size, record['size'])
                self.assertEqual((directory / record['name']).read_bytes(),
                                 (root / 'second/carplay_bridge' / record['name']).read_bytes())

    @unittest.skipUnless(MINI_ASSET_TESTS and MINI_NATIVE.exists(),
                         'Real Android asset generation is opt-in on the authorized Mini only')
    def test_failed_endpoint_build_never_publishes_partial_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with patch.object(assets, 'build_endpoint', side_effect=RuntimeError('bundle failed')):
                with self.assertRaisesRegex(RuntimeError, 'bundle failed'):
                    assets.build(MINI_NATIVE, output)
            self.assertFalse((output / 'carplay_bridge').exists())
            self.assertEqual([], list(output.iterdir()))


if __name__ == '__main__':
    unittest.main()
