import json
from pathlib import Path
import tempfile
import unittest

from build_endpoint_agent import build
from cold_passthrough_controller import (
    REMOTE_BRIDGE, REMOTE_ENDPOINT_LAUNCHER, ENDPOINT_STATUS_MANUAL, ENDPOINT_STATUS_PROCESS,
    ColdPassthroughController, Inputs,
    endpoint_agent_entry, read_endpoint_bundle, sha256_bytes,
)

ROOT = Path(__file__).resolve().parent


class EndpointBundleTest(unittest.TestCase):
    def bundle(self, directory, bridge_path=REMOTE_BRIDGE, status_mode=ENDPOINT_STATUS_MANUAL):
        path = Path(directory) / "agent.js"
        entry = b'import Java from "frida-java-bridge";\n// fixture only; do not execute\n'
        payload = "📦\n".encode() + str(len(entry)).encode() + b" /agent.js\n" + "✄\n".encode() + entry
        path.write_bytes(payload)
        package = json.loads((ROOT / "agent/package.json").read_text())
        source = (ROOT / "carplay_clean_map_endpoint.js").read_text()
        manifest = {
            "kind": "impulse-clean-map-frida-agent-v1",
            "bundleSha256": sha256_bytes(payload),
            "entrySha256": sha256_bytes(endpoint_agent_entry(
                source, bridge_path=bridge_path, status_mode=status_mode).encode()),
            "packageLockSha256": sha256_bytes((ROOT / "agent/package-lock.json").read_bytes()),
            "packageJsonSha256": sha256_bytes((ROOT / "agent/package.json").read_bytes()),
            "moduleMode": "esm-node16",
            "javaBridgeVersion": package["dependencies"]["frida-java-bridge"],
            "compilerVersion": package["devDependencies"]["frida-compile"],
            "bridgePath": bridge_path,
            "statusMode": status_mode,
        }
        sidecar = path.with_suffix(".js.manifest.json")
        sidecar.write_text(json.dumps(manifest))
        return path, payload, sidecar, manifest

    def test_bundle_is_staged_byte_for_byte_without_plaintext_wrappers(self):
        class Remote:
            def __init__(self): self.uploads = {}
            def run(self, *args, **kwargs): return ""
            def upload(self, payload, path, mode): self.uploads[path] = payload

        with tempfile.TemporaryDirectory() as temporary:
            path, payload, _, _ = self.bundle(temporary)
            native = Path(temporary) / "bridge.so"
            native.write_bytes(b"fixture native data".ljust(4096, b"\0"))
            inputs = Inputs(native, ROOT / "cold_oem_interceptor.js",
                            ROOT / "testdata/oem-cold-profile-v3.json", path, endpoint_is_bundle=True)
            remote = Remote()
            controller = ColdPassthroughController(remote, inputs)
            controller.oem_uid = 1000
            receipt = controller.stage()
            self.assertEqual(payload, remote.uploads[REMOTE_ENDPOINT_LAUNCHER])
            self.assertEqual(sha256_bytes(payload), receipt["endpointLauncherSha256"])

    def test_changed_bundle_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            path, payload, _, _ = self.bundle(temporary)
            path.write_bytes(payload + b"modified")
            with self.assertRaisesRegex(ValueError, "bundleSha256"):
                read_endpoint_bundle(path)

    def test_stale_source_lock_or_library_binding_is_refused(self):
        for key in ("entrySha256", "packageLockSha256", "packageJsonSha256", "moduleMode", "javaBridgeVersion", "compilerVersion", "bridgePath", "statusMode", "kind"):
            with self.subTest(field=key), tempfile.TemporaryDirectory() as temporary:
                path, _, sidecar, manifest = self.bundle(temporary)
                manifest[key] = "changed"
                sidecar.write_text(json.dumps(manifest))
                with self.assertRaisesRegex(ValueError, key):
                    read_endpoint_bundle(path)

    def test_preexisting_manual_manifest_without_status_mode_still_verifies(self):
        with tempfile.TemporaryDirectory() as temporary:
            path, payload, sidecar, manifest = self.bundle(temporary)
            del manifest["statusMode"]
            sidecar.write_text(json.dumps(manifest))
            self.assertEqual(payload.decode(), read_endpoint_bundle(path))

    def test_process_bundle_pins_custom_native_path_and_runtime_status_mode(self):
        bridge_path = "/data/local/tmp/impulse-carplay/versions/74133-native-source/libcarplay_video_bridge.so"
        with tempfile.TemporaryDirectory() as temporary:
            path, payload, sidecar, manifest = self.bundle(temporary, bridge_path, ENDPOINT_STATUS_PROCESS)
            self.assertEqual(payload.decode(), read_endpoint_bundle(
                path, bridge_path=bridge_path, status_mode=ENDPOINT_STATUS_PROCESS))
            with self.assertRaisesRegex(ValueError, "entrySha256"):
                read_endpoint_bundle(path)
            del manifest["statusMode"]
            sidecar.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "statusMode"):
                read_endpoint_bundle(path, bridge_path=bridge_path, status_mode=ENDPOINT_STATUS_PROCESS)

    def test_endpoint_rejects_unknown_status_mode_and_nonprivate_bridge_path(self):
        source = (ROOT / "carplay_clean_map_endpoint.js").read_text()
        for options in ({"status_mode": "unknown"}, {"bridge_path": "/tmp/foreign.so"},
                        {"bridge_path": "/data/local/tmp/impulse-carplay/../libcarplay_video_bridge.so"}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                endpoint_agent_entry(source, **options)

    def test_commonjs_archive_is_refused_even_with_matching_bundle_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            path, _, sidecar, manifest = self.bundle(temporary)
            entry = b'Object.defineProperty(exports, "__esModule", {value:true});\nconst Java = require("frida-java-bridge");\n'
            payload = "📦\n".encode() + str(len(entry)).encode() + b" /agent.js\n" + "✄\n".encode() + entry
            path.write_bytes(payload)
            manifest["bundleSha256"] = sha256_bytes(payload)
            sidecar.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "ES module"):
                read_endpoint_bundle(path)

    @unittest.skipUnless((ROOT / "agent/node_modules/.bin/frida-compile").exists(), "npm ci required for real bundle integration")
    def test_real_java_bundle_is_reproducible_and_has_a_valid_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary) / "first.js"
            second = Path(temporary) / "second.js"
            receipt = build(first)
            build(second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertTrue(first.read_bytes().startswith("📦\n".encode()))
            self.assertIn("/node_modules/frida-java-bridge/index.js", read_endpoint_bundle(first))
            self.assertFalse(receipt["androidRuntimeValidated"])
            with self.assertRaisesRegex(ValueError, "never overwritten"):
                build(first)


if __name__ == "__main__":
    unittest.main()
