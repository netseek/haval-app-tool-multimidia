import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "verify_regression_lock", Path(__file__).with_name("verify_regression_lock.py")
)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class RegressionLockAlphaLiteralTest(unittest.TestCase):
    def setUp(self):
        self.alpha_pattern = next(
            pattern
            for label, pattern in guard.RISKY_ADDED_LINE_PATTERNS
            if label == "windowAlpha=0 / transparent Presentation bypass"
        )

    def test_complete_zero_literals_still_trigger_for_all_guarded_names(self):
        for name in ("alpha", "windowAlpha", "attrs.alpha"):
            for value in ("0", "0f", "0.0", "0.0f", "0.00", "0.000f"):
                with self.subTest(name=name, value=value):
                    self.assertIsNotNone(
                        self.alpha_pattern.search(f"{name} = {value};")
                    )

    def test_nonzero_literals_do_not_match_their_zero_prefix(self):
        for value in (
            "0.25f", "0.5", "0.01f", "0.0001f", "0.001", "0.10f",
            "0.1e-3f", "0.25e-3", "0x10", "0b01", "0_1", "01",
        ):
            with self.subTest(value=value):
                self.assertIsNone(self.alpha_pattern.search(f"alpha = {value})"))

    def test_zero_match_ends_at_literal_not_an_identifier_prefix(self):
        for line in ("alpha = 0fValue", "alpha = 0value", "alpha = 0.0fValue"):
            with self.subTest(line=line):
                self.assertIsNone(self.alpha_pattern.search(line))
        for line in ("alpha=0)", "attrs.alpha = 0f\n", "windowAlpha=0.0f,"):
            with self.subTest(line=line):
                self.assertIsNotNone(self.alpha_pattern.search(line))

    def test_diff_scan_reports_hidden_presentation_but_not_translucent_chip(self):
        source = "app/src/main/java/example/HudLayoutSection.kt"
        diff = (
            f"+++ b/{source}\n"
            "+val chipBackground = accent.copy(alpha = 0.25f)\n"
            "+attrs.alpha = 0.0f\n"
        )
        with patch.object(guard, "git_output", side_effect=[diff, ""]):
            findings = guard.risky_added_lines()
        self.assertEqual(len(findings), 1)
        self.assertIn("attrs.alpha = 0.0f", findings[0])
        self.assertNotIn("chipBackground", findings[0])

    def test_active_and_legacy_theme_paths_remain_guarded(self):
        for source in (
            "cluster-widgets/source/v1.0/default/src/core/main.js",
            "cluster-widgets/default/src/core/main.js",
        ):
            with self.subTest(source=source):
                diff = f"+++ b/{source}\n+send('VIDEO_FOCUS_CHANGE');\n"
                with patch.object(guard, "git_output", side_effect=[diff, ""]):
                    findings = guard.risky_added_lines()
                self.assertEqual(len(findings), 1)
                self.assertIn(source, findings[0])


if __name__ == "__main__":
    unittest.main()
