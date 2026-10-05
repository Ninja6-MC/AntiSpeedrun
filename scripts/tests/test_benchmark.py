from pathlib import Path
import importlib.util
import unittest


def load():
    path = Path(__file__).resolve().parents[1] / "benchmark" / "max_dragons.py"
    spec = importlib.util.spec_from_file_location("max_dragons", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


max_dragons = load()

# Folia 26.2 build 7 console output, colour codes removed.
HEALTH_REPORT = """\
[15:24:08 INFO]: Server Health Report
 - Online Players: 2
 - Total regions: 2
 - Utilisation: 36.0% / 100.0%
 - Load rate: 0.13, Gen rate: 41.67
 - Lowest Region TPS: 17.11
 - Median Region TPS: 17.44
 - Highest Region TPS: 17.78
Highest 3 utilisation regions
 - Region around block [w:'world',7,80,-9]:
    23.3% util at 15.97 MSPT at 17.11 TPS
    Chunks: 251 Players: 1 Entities: 135
 - Region around block [w:'world_the_end',-25,80,23]:
    10.8% util at 11.92 MSPT at 17.78 TPS
    Chunks: 376 Players: 1 Entities: 82
"""


class HealthReportTest(unittest.TestCase):

    def test_every_region_is_read(self):
        regions = [match.groupdict() for match in max_dragons.REGION.finditer(HEALTH_REPORT)]
        self.assertEqual([r["world"] for r in regions], ["world", "world_the_end"])
        end = regions[1]
        self.assertEqual((end["util"], end["mspt"], end["tps"]), ("10.8", "11.92", "17.78"))
        self.assertEqual((end["chunks"], end["players"], end["entities"]), ("376", "1", "82"))
        self.assertEqual(max_dragons.LOWEST_TPS.search(HEALTH_REPORT).group("tps"), "17.11")

    def test_the_window_line_gives_party_and_dragons(self):
        line = ("[15:37:49 INFO]: [AntiSpeedrun] Reinforcement window closed in world_the_end for the "
                "first fight: 2 on the main island, 3 dragon(s).")
        match = max_dragons.WINDOW_CLOSED.search(line)
        self.assertEqual((match.group("party"), match.group("dragons")), ("2", "3"))


class ConfigureTest(unittest.TestCase):

    def test_overrides_the_benchmark_keys_and_keeps_comments(self):
        shipped = max_dragons.read(max_dragons.SHIPPED_CONFIG)
        text = max_dragons.configure(shipped, {
            "boss-scaling.enabled": "true",
            "boss-scaling.multi-dragon.multiplier": "1.0",
            "boss-scaling.multi-dragon.max-dragons": 3,
        })
        self.assertIn("\n  enabled: true  ", text)
        self.assertIn("\n    multiplier: 1.0  ", text)
        self.assertIn("\n    max-dragons: 3", text)
        # Only the three lines change.
        changed = [pair for pair in zip(shipped.split("\n"), text.split("\n")) if pair[0] != pair[1]]
        self.assertEqual(len(changed), 3)
        self.assertTrue(changed[0][1].endswith("# Development build: enable after Epic 6 is complete."))

    def test_an_unknown_key_is_refused(self):
        shipped = max_dragons.read(max_dragons.SHIPPED_CONFIG)
        with self.assertRaises(max_dragons.RunError):
            max_dragons.configure(shipped, {"boss-scaling.multi-dragon.no-such-key": 1})


if __name__ == "__main__":
    unittest.main()
