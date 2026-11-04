"""Run the real shared shell probe against OEM-shaped task fixtures, without Android."""

from pathlib import Path
import os
import re
import shlex
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / "app/src/main/java/br/com/redesurftank/havalshisuku/experimental/CarPlayProjectionVisibilityProbe.kt"
MANAGER = ROOT / "app/src/main/java/br/com/redesurftank/havalshisuku/managers/CarPlayBridgeBootstrapManager.kt"

# Same OEM task/header fields as the owner's passive 141 capture, with only
# configuration details shortened. This task is retained but explicitly hidden.
OEM_HIDDEN_AA = """Stack id=5 bounds=[0,0][1920,660] displayId=0 userId=0
 configuration={1.0 land car night winConfig={mBounds=Rect(0, 0 - 1792, 720)} s.6}
  taskId=14357: com.ts.androidauto.app/com.ts.androidauto.app.display.AapActivity bounds=[0,0][1920,660] userId=0 visible=false topActivity=ComponentInfo{com.ts.androidauto.app/com.ts.androidauto.app.display.AapActivity}
"""


class ProjectionVisibilityProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = PROBE.read_text()
        capture = re.search(r'fun capture\(\): String = """(.*?)"""', source, re.S)
        clear = re.search(r'fun requireClear\(\): String = capture\(\) \+ """(.*?)"""', source, re.S)
        if not capture or not clear:
            raise AssertionError("Shared shell visibility probe is missing")
        cls.capture = textwrap.dedent(capture.group(1)).replace("§", "$")
        cls.clear = textwrap.dedent(clear.group(1)).replace("§", "$")

    def run_probe(self, stacks, *, require_clear=False, am_status=0, broken_awk=False, glob_trap=False):
        with tempfile.TemporaryDirectory(prefix="impulse-projection-visibility-") as directory:
            fixture = Path(directory)
            data = fixture / "stacks.txt"
            data.write_text(stacks)
            am = fixture / "am"
            am.write_text("#!/bin/sh\n[ \"$*\" = 'stack list' ] || exit 2\ncat " +
                          shlex.quote(str(data)) + "\nexit " + str(am_status) + "\n")
            am.chmod(0o700)
            if broken_awk:
                awk = fixture / "awk"
                awk.write_text("#!/bin/sh\nexit 138\n")
                awk.chmod(0o700)
            if glob_trap:
                (fixture / "visible=false").write_text("A filename must not turn an ambiguous flag into false")
            script = self.capture + (self.clear if require_clear else "")
            script += '\nprintf "%s\\n" "$cpb_projection_visuals"\n'
            return subprocess.run(["sh", "-c", script], text=True, capture_output=True,
                                  timeout=5, cwd=fixture,
                                  env={**os.environ, "PATH": str(fixture) + ":" + os.environ["PATH"]})

    def values(self, stacks):
        result = self.run_probe(stacks)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertLess(len(result.stdout), 64)
        return dict(line.split("=", 1) for line in result.stdout.splitlines())

    @staticmethod
    def task(package, *, visible="false", display=0, task_id=12):
        flag = "" if visible is None else " visible=" + visible
        return (f"Stack id={task_id} bounds=[0,0][1920,720] displayId={display} userId=0\n"
                f"  taskId={task_id}: {package}/.Activity bounds=[0,0][1920,720] userId=0" +
                flag + f" topActivity=ComponentInfo{{{package}/.Activity}}\n")

    def test_retained_oem_hidden_task_is_not_an_active_android_auto_screen(self):
        self.assertEqual(self.values(OEM_HIDDEN_AA), {"cp_visual": "0", "aa_visual": "0"})
        self.assertEqual(self.run_probe(OEM_HIDDEN_AA, require_clear=True).returncode, 0)

    def test_visible_projection_blocks_on_both_primary_and_cluster_displays(self):
        for package, field in [("com.ts.carplay.app", "cp_visual"),
                               ("com.ts.androidauto.app", "aa_visual"),
                               ("com.google.android.projection.gearhead", "aa_visual")]:
            for display in (0, 3):
                with self.subTest(package=package, display=display):
                    data = self.task(package, visible="true", display=display)
                    self.assertEqual(self.values(data)[field], "1")
                    self.assertNotEqual(self.run_probe(data, require_clear=True).returncode, 0)

    def test_hidden_projection_is_negative_visual_evidence_on_either_display(self):
        for display in (0, 3):
            data = self.task("com.ts.carplay.app", display=display) + self.task("com.ts.androidauto.app", display=display)
            self.assertEqual(self.values(data), {"cp_visual": "0", "aa_visual": "0"})

    def test_missing_duplicate_or_malformed_visibility_never_proves_inactivity(self):
        for flag in (None, "unknown", "falseX", "false visible=false", "false visible=true"):
            with self.subTest(flag=flag):
                data = self.task("com.ts.carplay.app", visible=flag)
                self.assertEqual(self.values(data)["cp_visual"], "unknown")
                self.assertNotEqual(self.run_probe(data, require_clear=True).returncode, 0)

    def test_mixed_tasks_keep_the_visible_or_unknown_projection_blocking(self):
        hidden = self.task("com.ts.carplay.app")
        self.assertEqual(self.values(hidden + self.task("com.ts.androidauto.app", visible="true")),
                         {"cp_visual": "0", "aa_visual": "1"})
        self.assertEqual(self.values(hidden + self.task("com.ts.carplay.app", visible=None))["cp_visual"], "unknown")

    def test_unrelated_oem_metadata_and_task_format_do_not_create_a_projection(self):
        data = ("Stack id=0 bounds=[0,0][1920,720] displayId=0 userId=0 extraOEM=field\n"
                " custom OEM metadata without a configuration block\n"
                "  taskId=1: com.example.launcher/.Home extraOEM=format\n")
        self.assertEqual(self.values(data), {"cp_visual": "0", "aa_visual": "0"})

    def test_missing_framing_truncated_projection_and_am_errors_stay_unknown(self):
        broken = ["", "Error: activity service unavailable\n", OEM_HIDDEN_AA.splitlines()[-1],
                  OEM_HIDDEN_AA.replace("displayId=0", "displayId=unknown"),
                  OEM_HIDDEN_AA.replace("displayId=0", "displayId=0 displayId=3"),
                  OEM_HIDDEN_AA.replace("bounds=[0,0][1920,660] userId", "bounds=[bad][bad] userId"),
                  OEM_HIDDEN_AA.replace("com.ts.androidauto.app/com.ts.androidauto.app.display.AapActivity}", "invalid}"),
                  OEM_HIDDEN_AA.rsplit("}", 1)[0],
                  OEM_HIDDEN_AA + "Error: stack dump failed\n"]
        for data in broken:
            with self.subTest(data=data[:70]):
                self.assertIn("unknown", self.values(data).values())
                self.assertNotEqual(self.run_probe(data, require_clear=True).returncode, 0)
        self.assertNotEqual(self.run_probe(OEM_HIDDEN_AA, am_status=1).returncode, 0)

    def test_excessive_output_stays_unknown_and_does_not_grow_the_probe_response(self):
        for data in (OEM_HIDDEN_AA + "metadata\n" * 4096, OEM_HIDDEN_AA + "x" * 262144):
            self.assertEqual(self.values(data), {"cp_visual": "unknown", "aa_visual": "unknown"})

    def test_observation_and_final_root_guard_share_the_same_probe(self):
        source = MANAGER.read_text()
        self.assertEqual(source.count("CarPlayProjectionVisibilityProbe.capture()"), 1)
        self.assertEqual(source.count("CarPlayProjectionVisibilityProbe.requireClear()"), 1)
        self.assertNotIn("grep -c 'com.ts.carplay.app'", source)

    def test_projection_evidence_does_not_depend_on_the_crashing_oem_awk(self):
        for visible, expected in [("false", "0"), ("true", "1"), (None, "unknown")]:
            with self.subTest(visible=visible):
                result = self.run_probe(self.task("com.ts.carplay.app", visible=visible), broken_awk=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("cp_visual=" + expected + "\n", result.stdout)

    def test_filename_globbing_cannot_make_a_malformed_flag_look_hidden(self):
        data = self.task("com.ts.carplay.app", visible="false*")
        result = self.run_probe(data, glob_trap=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("cp_visual=unknown\n", result.stdout)


if __name__ == "__main__":
    unittest.main()
