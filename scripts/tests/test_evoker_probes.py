from pathlib import Path
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))
import evokers


class RaidTriggerObservationTest(unittest.TestCase):
    def test_observes_the_pre_registration_id_reported_by_a_real_raid(self):
        pattern = evokers.raid_trigger_pattern("AsrProbe")
        for ident in (-1, 0, 2):
            line = f"[13:40:10 INFO]: [AntiSpeedrunProbe] ASRPROBE raid-trigger player=AsrProbe id={ident}"
            observed = re.search(pattern, line)
            self.assertIsNotNone(observed)
            self.assertEqual(str(ident), observed.group("id"))

    def test_another_players_raid_does_not_satisfy_the_fixture(self):
        self.assertIsNone(re.search(evokers.raid_trigger_pattern("AsrProbe"),
                                    "ASRPROBE raid-trigger player=AsrHelper id=-1"))


if __name__ == "__main__":
    unittest.main()
