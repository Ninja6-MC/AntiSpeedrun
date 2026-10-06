from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))

import anti_cheese  # noqa: E402
import gates  # noqa: E402
import harness  # noqa: E402
import credits  # noqa: E402
import probes  # noqa: E402
import trims  # noqa: E402

CONFIG = """\
anti-cheese:
  enabled: true
  block-bed-anchor-boss-damage: false
  cap-single-hit-boss-damage: false
  max-single-hit-boss-damage: 12.0
  block-exit-portal-crystal-place: false
  block-gateway-pre-dragon: false      # Off until the playtest.
"""

HIT = ("[10:51:11 INFO]: [AntiSpeedrunProbe] ASRPROBE damage victim=WITHER uuid=u part=none "
       "type=minecraft:player_attack bad-respawn-point=false causing=PLAYER direct=PLAYER cause=ENTITY_ATTACK "
       "base=135.0000 final={final} cancelled={cancelled} health=300.0000")


class SetRulesTest(unittest.TestCase):
    def test_turns_on_only_the_named_rule(self):
        text = anti_cheese.set_rules(CONFIG, ("cap-single-hit-boss-damage",))
        self.assertIn("  cap-single-hit-boss-damage: true\n", text)
        self.assertIn("  block-bed-anchor-boss-damage: false\n", text)
        self.assertIn("  block-exit-portal-crystal-place: false\n", text)

    def test_turns_a_rule_back_off(self):
        on = anti_cheese.set_rules(CONFIG, ("block-bed-anchor-boss-damage",))
        self.assertEqual(anti_cheese.set_rules(on, ()), CONFIG)

    def test_leaves_other_keys_and_comments_alone(self):
        text = anti_cheese.set_rules(CONFIG, anti_cheese.RULES)
        self.assertIn("  block-gateway-pre-dragon: false      # Off until the playtest.\n", text)
        self.assertIn("  max-single-hit-boss-damage: 12.0\n", text)

    def test_a_missing_rule_is_a_harness_failure(self):
        with self.assertRaises(harness.ProbeError):
            anti_cheese.set_rules(CONFIG.replace("  cap-single-hit-boss-damage: false\n", ""), ())

    def test_reads_the_configured_cap(self):
        self.assertEqual(anti_cheese.configured_cap(CONFIG), 12.0)


class ParseDamageTest(unittest.TestCase):
    def test_parses_numbers_and_flags(self):
        (event,) = anti_cheese.parse_damage([HIT.format(final="131.6480", cancelled="false")])
        self.assertEqual(event["victim"], "WITHER")
        self.assertEqual(event["type"], "minecraft:player_attack")
        self.assertEqual(event["final"], 131.648)
        self.assertFalse(event["cancelled"])
        self.assertFalse(event["bad"])

    def test_ignores_other_lines(self):
        self.assertEqual(anti_cheese.parse_damage(["[10:51:11 INFO]: Summoned new Wither"]), [])

    def test_landed_drops_cancelled_and_zero_hits(self):
        events = anti_cheese.parse_damage([HIT.format(final="12.0000", cancelled="false"),
                                           HIT.format(final="40.0000", cancelled="true"),
                                           HIT.format(final="0.0000", cancelled="false")])
        self.assertEqual([event["final"] for event in anti_cheese.landed(events)], [12.0])


class KnownIssueTest(unittest.TestCase):
    def setUp(self):
        self.results = anti_cheese.Results()
        self.issue = 1
        patcher = mock.patch.dict(anti_cheese.KNOWN_ISSUES, {self.issue: "a stand-in open bug"})
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_a_known_failure_is_recorded_but_does_not_fail_the_run(self):
        self.results.check("rule", "check", False, "detail", known_issue=self.issue)
        (entry,) = self.results.checks
        self.assertEqual(entry["status"], "known-failure")
        self.assertEqual(entry["known_issue"], self.issue)
        self.assertTrue(self.results.passed)

    def test_a_known_issue_that_passes_fails_the_run(self):
        self.results.check("rule", "check", True, "detail", known_issue=self.issue)
        (entry,) = self.results.checks
        self.assertEqual(entry["status"], "unexpected-pass")
        self.assertFalse(self.results.passed)

    def test_an_unlisted_issue_is_a_harness_error(self):
        with self.assertRaises(harness.ProbeError):
            self.results.check("rule", "check", False, "detail", known_issue=999999)


class ConsoleColourTest(unittest.TestCase):
    def test_strips_ansi_colours_from_command_feedback(self):
        line = "[20:29:22 INFO]: \x1b[38;5;7m[\x1b[38;5;3mAntiSpeedrun\x1b[38;5;7m]\x1b[0m \x1b[38;5;10mConfiguration reloaded."
        self.assertIn("[AntiSpeedrun] Configuration reloaded.", harness.ANSI.sub("", line))


class PlatformTest(unittest.TestCase):
    def test_reads_folia_from_the_build_line(self):
        log = "[17:12:52 INFO]: This server is running Folia version 26.2-7-ver/26.2.x@14b7fee"
        self.assertEqual(harness.platform(log), "folia")

    def test_anything_else_is_paper(self):
        log = "[20:22:32 INFO]: This server is running Paper version 26.2-129-ver/26.2@9240f58"
        self.assertEqual(harness.platform(log), "paper")
        self.assertEqual(harness.platform(""), "paper")


GATES_CONFIG = """\
dimension-gates:
  nether:
    enabled: true
    require-advancements:
      - "minecraft:story/smelt_iron"
  the_end:
    enabled: true

item-progression:
  enabled: true
  drop-recall-enabled: true
  gated-items:
    iron-tier:
      enabled: true
"""


class GateConfigTest(unittest.TestCase):
    def test_switches_only_the_nether_gate(self):
        text = gates.set_nether_gate(GATES_CONFIG, False)
        self.assertIn("  nether:\n    enabled: false\n", text)
        self.assertIn("  the_end:\n    enabled: true\n", text)
        self.assertEqual(gates.set_nether_gate(text, True), GATES_CONFIG)

    def test_sets_a_direct_child_of_a_section_only(self):
        text = gates.set_key(GATES_CONFIG, "item-progression", "enabled", "false")
        self.assertIn("item-progression:\n  enabled: false\n", text)
        self.assertIn("    iron-tier:\n      enabled: true\n", text)
        self.assertIn("  nether:\n    enabled: true\n", text)

    def test_never_reaches_past_its_section(self):
        config = ("item-progression:\n  drop-recall-enabled: true\n  enabled: true\n"
                  "boss:\n  enabled: true\n")
        text = gates.set_key(config, "item-progression", "enabled", "false")
        self.assertIn("item-progression:\n  drop-recall-enabled: true\n  enabled: false\n", text)
        self.assertIn("boss:\n  enabled: true\n", text)
        with self.assertRaises(harness.ProbeError):
            gates.set_key("item-progression:\n  drop-recall-enabled: true\nboss:\n  enabled: true\n",
                          "item-progression", "enabled", "false")

    def test_a_missing_key_is_a_harness_failure(self):
        with self.assertRaises(harness.ProbeError):
            gates.set_key(GATES_CONFIG, "item-progression", "gate-dispensers", "false")


# Seeds two real servers reported to /seed, and the bounds /asrprobe structure then reported for
# the Ancient City of trims.CITY_CELL: Paper 1.21.4 build 232 and Folia 26.2 build 7.
CITIES = (
    (5457511341097885765, (1911, 1813, 2156, 2054)),
    (-5487481698590049538, (1956, 2021, 2207, 2265)),
)


class CityPlacementTest(unittest.TestCase):
    def test_the_computed_start_chunk_lies_inside_the_city_the_server_generated(self):
        for seed, (min_x, min_z, max_x, max_z) in CITIES:
            chunk_x, chunk_z = trims.city_chunk(seed, *trims.CITY_CELL)
            x, z = chunk_x * 16 + 8, chunk_z * 16 + 8
            self.assertTrue(min_x <= x <= max_x and min_z <= z <= max_z, (seed, x, z))

    def test_the_start_chunk_stays_inside_its_cell_short_of_the_separation(self):
        for seed in (0, 1, -1, 2 ** 63 - 1, -(2 ** 63)):
            chunk_x, chunk_z = trims.city_chunk(seed, 5, 5)
            for chunk in (chunk_x, chunk_z):
                self.assertGreaterEqual(chunk, 5 * trims.CITY_SPACING)
                self.assertLess(chunk, 6 * trims.CITY_SPACING - trims.CITY_SEPARATION)

    def test_java_random_matches_java_util_random(self):
        # new java.util.Random(42).nextInt(16), nextInt(16), nextInt(10).
        random = trims._LegacyRandom(42)
        self.assertEqual([random.next_int(16), random.next_int(16), random.next_int(10)], [11, 0, 8])


class StructureLineTest(unittest.TestCase):
    def test_parses_a_structure_and_an_enter_line(self):
        found = trims.FOUND.search("ASRPROBE structure query=s4 structures=1 pieces=83 "
                                   "bounds=1911,-64,1813..2156,-10,2054 lowest=-52")
        self.assertEqual((found["structures"], found["lowest"], found["result"]), ("1", "-52", None))
        entered = trims.FOUND.search("ASRPROBE enter query=s8 structures=1 pieces=83 "
                                     "bounds=1911,-64,1813..2156,-10,2054 lowest=-52 "
                                     "piece=2012,-52,1932..2052,-22,1949 to=2032,-37,1940 result=true")
        self.assertEqual((entered["piece"], entered["to"], entered["result"]),
                         ("2012,-52,1932..2052,-22,1949", "2032,-37,1940", "true"))


class ModuleTest(unittest.TestCase):
    def test_probe_names_are_unique_across_modules(self):
        names = list(gates.PROBES) + list(credits.PROBES) + list(trims.PROBES) + list(anti_cheese.PROBES)
        self.assertEqual(len(names), len(set(names)))

    def test_the_results_class_is_shared(self):
        self.assertIs(anti_cheese.Results, probes.Results)
        self.assertIs(anti_cheese.KNOWN_ISSUES, probes.KNOWN_ISSUES)


if __name__ == "__main__":
    unittest.main()
