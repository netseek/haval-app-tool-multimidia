import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("patch_cold_start_gate.py")
SPEC = importlib.util.spec_from_file_location("patch_cold_start_gate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ColdStartGatePatchTest(unittest.TestCase):
    def create_fixture(self, root: Path, service=None) -> Path:
        service_path = root / MODULE.SERVICE_RELATIVE_PATH
        service_path.parent.mkdir(parents=True)
        if service is None:
            service = (
                ".method public onCreate()V\n"
                "    .locals 1\n\n"
                + MODULE.SERVICE_ANCHOR
                + "\n    return-void\n.end method\n"
            )
        service_path.write_text(service, encoding="utf-8")
        return service_path

    def test_patches_once_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            service_path = self.create_fixture(root)

            self.assertTrue(MODULE.patch_decoded_apk(root))
            self.assertIn(MODULE.SENTINEL, service_path.read_text(encoding="utf-8"))
            gate_path = root / MODULE.GATE_RELATIVE_PATH
            self.assertEqual(MODULE.GATE_SMALI, gate_path.read_text(encoding="utf-8"))
            self.assertFalse(MODULE.patch_decoded_apk(root))

    def test_rejects_unknown_service_body(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.create_fixture(root, ".method public onCreate()V\n.end method\n")

            with self.assertRaisesRegex(RuntimeError, "exact CarPlayService.onCreate anchor"):
                MODULE.patch_decoded_apk(root)

    def test_rejects_changed_helper_after_patch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.create_fixture(root)
            MODULE.patch_decoded_apk(root)
            (root / MODULE.GATE_RELATIVE_PATH).write_text("changed", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "helper changed"):
                MODULE.patch_decoded_apk(root)

    def test_accepts_reassembled_source_without_comments(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            service_path = self.create_fixture(root)
            MODULE.patch_decoded_apk(root)
            service_path.write_text(
                "\n".join(
                    line for line in service_path.read_text(encoding="utf-8").splitlines()
                    if not line.lstrip().startswith("#")
                ),
                encoding="utf-8",
            )
            gate_path = root / MODULE.GATE_RELATIVE_PATH
            reassembled_gate = "\n".join(
                line for line in gate_path.read_text(encoding="utf-8").splitlines()
                if not line.lstrip().startswith("#")
            )
            for original, rebuilt in {
                ":enabled": ":cond_0",
                ":poll": ":goto_0",
                ":check_cancel": ":cond_1",
                ":check_timeout": ":cond_2",
                ":sleep": ":cond_3",
            }.items():
                reassembled_gate = reassembled_gate.replace(original, rebuilt)
            gate_path.write_text(
                reassembled_gate,
                encoding="utf-8",
            )

            self.assertFalse(MODULE.patch_decoded_apk(root))


if __name__ == "__main__":
    unittest.main()
