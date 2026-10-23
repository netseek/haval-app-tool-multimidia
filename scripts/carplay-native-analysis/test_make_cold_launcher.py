import importlib.util
import json
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


if __name__ == "__main__":
    unittest.main()
