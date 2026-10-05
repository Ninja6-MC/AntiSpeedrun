from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))

import anti_cheese  # noqa: E402
import harness  # noqa: E402

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
        self.issue = next(iter(anti_cheese.KNOWN_ISSUES))

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
        line = "Wither has the following entity data: \x1b[38;5;3m300.0\x1b[38;5;9mf\x1b[0m"
        cleaned = harness.ANSI.sub("", line)
        self.assertEqual(anti_cheese.HEALTH.search(cleaned).group(1), "300.0")


if __name__ == "__main__":
    unittest.main()
