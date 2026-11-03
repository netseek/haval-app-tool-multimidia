import importlib.util
import json
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("make_cold_launcher.py")
SPEC = importlib.util.spec_from_file_location("make_cold_launcher", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ColdLauncherTest(unittest.TestCase):
    def reviewed_profile(self):
        fixture = Path(__file__).with_name("testdata") / "oem-cold-profile-v3.json"
        return json.loads(fixture.read_text(encoding="utf-8"))

    def interceptor(self):
        return Path(__file__).with_name("cold_oem_interceptor.js").read_text(encoding="utf-8")

    def test_generates_fixed_auto_arm_without_secrets(self):
        launcher = MODULE.build_launcher(
            self.interceptor(),
            self.reviewed_profile(),
            1234,
            "/data/local/tmp/impulse-carplay/run/libcarplay_video_bridge.so",
            4096,
            "a" * 64,
            "e5f7a68d-7b0f-4305-984b-974f677a150b",
        )
        self.assertIn("impulse-carplay-cold-state", launcher)
        self.assertIn('"expectedPid":1234', launcher)
        self.assertIn("impulseInstaller.arm", launcher)
        self.assertNotIn("release.", launcher)

    def test_optional_eligibility_is_bound_only_to_dedicated_safety_input(self):
        arguments = (self.interceptor(), self.reviewed_profile(), 1234,
                     "/data/local/tmp/impulse-carplay/run/libcarplay_video_bridge.so",
                     4096, "a" * 64, "main")
        normal = MODULE.build_launcher(*arguments)
        options = json.loads(re.search(r"const impulseOptions = (\{[^\n]+\});", normal).group(1))
        self.assertNotIn("eligibilityPath", options)
        safety = "/data/local/tmp/impulse-carplay-input/safety.json"
        persistent = MODULE.build_launcher(*arguments, eligibility_path=safety)
        options = json.loads(re.search(r"const impulseOptions = (\{[^\n]+\});", persistent).group(1))
        self.assertEqual(safety, options["eligibilityPath"])
        with self.assertRaisesRegex(ValueError, "eligibility path"):
            MODULE.build_launcher(*arguments, eligibility_path="/tmp/safety.json")

    def test_rejects_unreviewed_profile_and_paths(self):
        profile = self.reviewed_profile()
        profile["modules"][0]["fileSize"] += 1
        with self.assertRaisesRegex(ValueError, "reviewed v3"):
            MODULE.build_launcher(
                self.interceptor(), profile, 1, "/data/local/tmp/impulse-carplay/run/libcarplay_video_bridge.so",
                4096, "a" * 64, "main"
            )
        with self.assertRaisesRegex(ValueError, "staging root"):
            MODULE.build_launcher(
                self.interceptor(), self.reviewed_profile(), 1, "/data/local/tmp/libcarplay_video_bridge.so",
                4096, "a" * 64, "main"
            )

    def test_writes_private_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "launcher.js"
            launcher = MODULE.build_launcher(
                self.interceptor(), self.reviewed_profile(), 7,
                "/data/local/tmp/impulse-carplay/run/libcarplay_video_bridge.so",
                4096, "b" * 64, "main"
            )
            descriptor = __import__("os").open(output, __import__("os").O_WRONLY | __import__("os").O_CREAT, 0o600)
            with __import__("os").fdopen(descriptor, "w", encoding="utf-8") as stream:
                stream.write(launcher)
            self.assertEqual(0o600, output.stat().st_mode & 0o777)

    @unittest.skipUnless(shutil.which("node"), "Node required for launcher execution")
    def test_status_is_flushed_before_message_and_records_arm_failures(self):
        harness = r"""
const assert = require('node:assert/strict');
const vm = require('node:vm');
const launchers = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
for (let index = 0; index < launchers.length; index++) {
    const snapshots = [];
    let poll = null;
    let flushed = false;
    let closed = false;
    const context = {
        rpc: {exports: {}},
        File: class {
            constructor(path, mode) {
                assert.equal(path, '/data/local/tmp/impulse-carplay/run/cold-state.json');
                assert.equal(mode, 'w');
                flushed = false;
                closed = false;
            }
            write(text) { snapshots.push(JSON.parse(text)); }
            flush() { flushed = true; }
            close() { closed = true; }
        },
        send() { assert.ok(flushed && closed, 'status must reach file before buffered send'); },
        setInterval(callback) { poll = callback; return 1; },
        clearInterval() { poll = null; }
    };
    vm.runInNewContext(launchers[index], context);
    if (index === 0) {
        assert.equal(snapshots[0].phase, 'armed');
        assert.ok(poll);
        poll();
        assert.equal(snapshots[1].phase, 'installed-pinned');
        assert.equal(poll, null);
    } else {
        assert.equal(snapshots[0].phase, index === 1 ? 'failed' : 'failed-pinned');
        assert.match(snapshots[0].failure, /test failure/);
        assert.equal(poll, null);
    }
}
"""
        scenarios = [
            "arm() { return {phase:'armed'}; }, status() { return {phase:'installed-pinned'}; }",
            "arm() { throw Error('test failure'); }, status() { return {phase:'idle', pinned:false}; }",
            "arm() { throw Error('test failure'); }, status() { return {phase:'failed-pinned', pinned:true}; }",
        ]
        launchers = [MODULE.build_launcher(
            "function createColdOemInterceptor(api) {}\nrpc.exports = {" + scenario + "};",
            self.reviewed_profile(), 7,
            "/data/local/tmp/impulse-carplay/run/libcarplay_video_bridge.so",
            4096, "a" * 64, "main",
            status_path="/data/local/tmp/impulse-carplay/run/cold-state.json",
        ) for scenario in scenarios]
        subprocess.run(["node", "-e", harness], input=json.dumps(launchers), text=True, check=True, capture_output=True)

    def test_rejects_status_file_outside_private_root(self):
        with self.assertRaisesRegex(ValueError, "status path"):
            MODULE.build_launcher(
                self.interceptor(), self.reviewed_profile(), 7,
                "/data/local/tmp/impulse-carplay/run/libcarplay_video_bridge.so",
                4096, "a" * 64, "main", status_path="/data/local/tmp/cold-state.json",
            )


if __name__ == "__main__":
    unittest.main()
