import json
import unittest
from pathlib import Path

from cold_passthrough_controller import (
    PID_PLACEHOLDER,
    classify_injector_log,
    endpoint_launcher,
    launcher_template,
    shell_octal_command,
    sha256_bytes,
    specialize_launcher,
)


ROOT = Path(__file__).resolve().parent


class ColdPassthroughControllerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.interceptor = (ROOT / "cold_oem_interceptor.js").read_text(encoding="utf-8")
        cls.profile = json.loads((ROOT / "testdata/oem-cold-profile-v3.json").read_text(encoding="utf-8"))

    def test_pid_specialization_matches_exact_launcher_shape(self):
        template = launcher_template(self.interceptor, self.profile, 4096, "a" * 64)
        exact = specialize_launcher(template, 4242)
        self.assertIn('"expectedPid":4242', exact)
        self.assertNotIn(str(PID_PLACEHOLDER), exact)
        self.assertEqual(1, exact.count('"expectedPid":4242'))

    def test_specialization_rejects_missing_or_ambiguous_placeholder(self):
        with self.assertRaises(ValueError):
            specialize_launcher("", 42)
        with self.assertRaises(ValueError):
            specialize_launcher(f'"expectedPid":{PID_PLACEHOLDER}' * 2, 42)
        with self.assertRaises(ValueError):
            specialize_launcher(f'"expectedPid":{PID_PLACEHOLDER}', 0)

    def test_log_phase_uses_last_state(self):
        log = '\n'.join([
            '{"payload":{"status":{"phase":"armed"}}}',
            '{"payload":{"status":{"phase":"installing"}}}',
            '{"payload":{"status":{"phase":"installed-pinned"}}}',
        ])
        self.assertEqual("installed-pinned", classify_injector_log(log))
        self.assertEqual("unknown", classify_injector_log("plain output"))

    def test_hash_is_stable(self):
        self.assertEqual(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256_bytes(b"abc"),
        )

    def test_octal_command_preserves_uppercase_runtime_option(self):
        encoded = shell_octal_command("fridainjector -R qjs")
        self.assertEqual("fridainjector -R qjs", bytes(
            int(value, 8) for value in encoded.split("\\") if value
        ).decode("ascii"))
        self.assertNotIn("R", encoded)

    def test_endpoint_launcher_arms_exact_staged_bridge(self):
        source = (ROOT / "carplay_clean_map_endpoint.js").read_text(encoding="utf-8")
        launcher = endpoint_launcher(source)
        self.assertIn("/data/local/tmp/impulse-carplay/passthrough-v1/libcarplay_video_bridge.so", launcher)
        self.assertEqual(1, launcher.count("rpc.exports.arm({ bridgePath:"))

    def test_endpoint_launcher_rejects_unknown_source(self):
        with self.assertRaises(ValueError):
            endpoint_launcher("rpc.exports = {}")


if __name__ == "__main__":
    unittest.main()
