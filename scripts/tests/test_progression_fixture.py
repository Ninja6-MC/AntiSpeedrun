from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))

import harness  # noqa: E402
import run  # noqa: E402


class ProgressionFixtureTest(unittest.TestCase):
    def test_enables_optional_progression_in_shipped_core(self):
        config = (Path(__file__).resolve().parents[2] / "src/main/resources/config.yml").read_text(
            encoding="utf-8")
        result = run.enable_progression_fixture(config)
        expected = config.replace("item-progression:\n  enabled: false",
                                  "item-progression:\n  enabled: true").replace(
                                      "trim-progression:\n  enabled: false",
                                      "trim-progression:\n  enabled: true")
        self.assertEqual(result, expected)
        self.assertEqual(run.enable_progression_fixture(result), result)

    def test_missing_section_fails_before_running_probes(self):
        with self.assertRaises(harness.ProbeError):
            run.enable_progression_fixture("item-progression:\n  enabled: false\n")
