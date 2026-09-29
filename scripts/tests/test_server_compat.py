from pathlib import Path
import importlib.util
import unittest


def load(name):
    path = Path(__file__).resolve().parents[1] / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fetch_server = load("fetch-server")
boot_smoke = load("boot-smoke")

CLEAN_BOOT = """\
[06:00:01 INFO]: [AntiSpeedrun] Enabling AntiSpeedrun v1.0.0-SNAPSHOT-g1234567
[06:00:01 INFO]: [AntiSpeedrun] Item gates compiled: 85 materials across 5 tiers.
[06:00:01 INFO]: [AntiSpeedrun] AntiSpeedrun enabled successfully.
[06:00:09 INFO]: Done (8.123s)! For help, type "help"
"""
CLEAN_STOP = """\
[06:00:10 INFO]: Stopping the server
[06:00:10 INFO]: [AntiSpeedrun] Disabling AntiSpeedrun v1.0.0-SNAPSHOT-g1234567
[06:00:10 INFO]: [AntiSpeedrun] AntiSpeedrun disabled.
"""


class ReleaseVersionsTest(unittest.TestCase):
    def test_orders_numerically_and_skips_prereleases(self):
        document = {"versions": {
            "26.3": ["26.3", "26.3-rc-3"],
            "26.2": ["26.2"],
            "1.21": ["1.21.11", "1.21.11-pre5", "1.21.9", "1.21.10", "1.21"],
        }}
        self.assertEqual(fetch_server.release_versions(document),
                         ["26.3", "26.2", "1.21.11", "1.21.10", "1.21.9", "1.21"])

    def test_empty_document(self):
        self.assertEqual(fetch_server.release_versions({}), [])


class SelectBuildTest(unittest.TestCase):
    def test_prefers_newest_stable_over_newer_experimental(self):
        builds = [{"id": 9, "channel": "BETA"}, {"id": 7, "channel": "STABLE"},
                  {"id": 5, "channel": "STABLE"}]
        self.assertEqual(fetch_server.select_build(builds)["id"], 7)

    def test_falls_back_to_newest_of_any_channel(self):
        builds = [{"id": 3, "channel": "ALPHA"}, {"id": 6, "channel": "ALPHA"}]
        self.assertEqual(fetch_server.select_build(builds)["id"], 6)

    def test_no_builds(self):
        self.assertIsNone(fetch_server.select_build([]))


class CheckLogTest(unittest.TestCase):
    def test_clean_boot_passes(self):
        self.assertEqual(boot_smoke.check_log(CLEAN_BOOT, CLEAN_STOP), [])

    def test_each_required_line_is_required(self):
        for needle in ("Enabling AntiSpeedrun", "Item gates compiled", "enabled successfully", "Done ("):
            with self.subTest(needle=needle):
                log = "".join(line + "\n" for line in CLEAN_BOOT.splitlines() if needle not in line)
                self.assertEqual(len(boot_smoke.check_log(log, CLEAN_STOP)), 1)

    def test_required_lines_after_stop_do_not_count(self):
        self.assertNotEqual(boot_smoke.check_log("", CLEAN_BOOT + CLEAN_STOP), [])

    def test_zero_compiled_gates_fails(self):
        log = CLEAN_BOOT.replace("85 materials across 5 tiers", "0 materials across 0 tiers")
        self.assertEqual(len(boot_smoke.check_log(log, CLEAN_STOP)), 1)

    def test_disabling_while_running_fails_but_not_at_shutdown(self):
        log = CLEAN_BOOT + "[06:00:05 INFO]: [AntiSpeedrun] Disabling AntiSpeedrun v1\n"
        self.assertEqual(len(boot_smoke.check_log(log, CLEAN_STOP)), 1)

    def test_forbidden_output_fails_anywhere(self):
        for bad in (
            "[06:00:02 ERROR]: [AntiSpeedrun] item-progression has an unresolvable tier collision",
            "java.lang.UnsupportedOperationException",
            "[06:00:02 ERROR]: Could not load 'plugins/AntiSpeedrun.jar' in folder 'plugins'",
            "[06:00:02 ERROR]: Error occurred while enabling AntiSpeedrun v1 (Is it up to date?)",
            "[06:00:02 ERROR]: [AntiSpeedrun] something failed",
            "\tat com.ninja6.antispeedrun.AntiSpeedrunPlugin.onEnable(AntiSpeedrunPlugin.java:1)",
        ):
            with self.subTest(bad=bad):
                self.assertNotEqual(boot_smoke.check_log(CLEAN_BOOT, CLEAN_STOP + bad + "\n"), [])

    def test_another_plugins_error_is_not_ours(self):
        log = CLEAN_BOOT + "[06:00:02 ERROR]: [oshi.driver.windows.registry] Unable to locate counters\n"
        self.assertEqual(boot_smoke.check_log(log, CLEAN_STOP), [])


if __name__ == "__main__":
    unittest.main()
