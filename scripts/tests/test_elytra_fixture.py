"""Availability, provenance and forced-chunk lifetime are distinct fixture contracts."""

from pathlib import Path
import re
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))
import elytra  # noqa: E402
from harness import ProbeError  # noqa: E402
from probes import Results  # noqa: E402


class ElytraFixtureTest(unittest.TestCase):
    def setUp(self):
        self.probes = mock.Mock()
        self.probes.server.query.side_effect = [
            re.match(r"(-?\d+) (-?\d+)", f"{3000 + index * 5000} 3000") for index in range(8)]
        self.results = Results()
        self.forced = []

    def test_waits_for_a_frame_and_releases_rejected_city_before_selecting_another(self):
        self.probes.server.lines_since.side_effect = [[], [
            "ASRPROBE elytra-spawn x=8001 y=100 z=3001 natural=true"]]
        with mock.patch.object(elytra.time, "monotonic", side_effect=[0, 8, 8]), \
                mock.patch.object(elytra.time, "sleep") as sleep:
            selected = elytra.acquire_frame(self.probes, self.results, self.forced)
        self.assertEqual(selected, (8001, 100, 3001))
        self.assertEqual(self.forced, [(7888, 2888, 8112, 3112)])
        commands = [call.args[0] for call in self.probes.run.call_args_list]
        self.assertEqual(commands, [
            "execute in minecraft:the_end run forceload add 2888 2888 3112 3112",
            "execute in minecraft:the_end run forceload remove 2888 2888 3112 3112",
            "execute in minecraft:the_end run forceload add 7888 2888 8112 3112"])
        sleep.assert_not_called()
        self.assertTrue(self.results.passed)

    def test_genuine_unmarked_frame_is_a_provenance_failure_without_trying_more_cities(self):
        self.probes.server.lines_since.return_value = [
            "ASRPROBE elytra-spawn x=3001 y=100 z=3001 natural=false"]
        with self.assertRaisesRegex(ProbeError, "without its provenance marker"):
            elytra.acquire_frame(self.probes, self.results, self.forced)
        self.assertFalse(self.results.passed)
        self.assertEqual(self.probes.server.query.call_count, 1)
        self.assertEqual(len(self.forced), 1)  # caller's finally still owns this ticket

    def test_absent_fixture_stays_a_bounded_failure_with_city_evidence(self):
        self.probes.server.lines_since.return_value = []
        with mock.patch.object(elytra.time, "monotonic", side_effect=range(0, 128, 8)):
            with self.assertRaisesRegex(ProbeError, "fixture unavailable.*no provenance failure"):
                elytra.acquire_frame(self.probes, self.results, self.forced)
        self.assertEqual(self.probes.server.query.call_count, 8)
        self.assertEqual(self.forced, [])
        self.assertEqual(len(self.results.notes), 8)
        self.assertEqual(sum("forceload remove" in call.args[0]
                             for call in self.probes.run.call_args_list), 8)

    def test_failed_collection_cleans_both_overlapping_areas_after_the_assertion_path(self):
        selected_city = (2888, 2888, 3112, 3112)
        def acquired(probes, results, forced):
            forced.append(selected_city)
            return (3000, 100, 3000)
        def collection_unavailable(*args, **kwargs):
            self.assertFalse(any("forceload remove" in call.args[0]
                                 for call in self.probes.run.call_args_list))
            raise ProbeError("collection fixture failed")
        self.probes.near.side_effect = collection_unavailable
        with mock.patch.object(elytra, "CreditProbes", return_value=self.probes), \
                mock.patch.object(elytra, "acquire_frame", side_effect=acquired):
            elytra.run_all(mock.Mock(), mock.Mock(), "Tester", self.results)
        removes = [call.args[0] for call in self.probes.run.call_args_list if "forceload remove" in call.args[0]]
        self.assertEqual(removes, [
            "execute in minecraft:the_end run forceload remove 2968 2968 3032 3032",
            "execute in minecraft:the_end run forceload remove 2888 2888 3112 3112"])
        self.assertFalse(self.results.passed)
        self.assertEqual(self.probes.reload.call_count, 2)


if __name__ == "__main__":
    unittest.main()
