from pathlib import Path
import argparse
import contextlib
import importlib.util
import io
import re
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release_description", ROOT / "scripts" / "release-description.py")
rd = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rd)

REPOSITORY = "Ninja6-MC/AntiSpeedrun"
FILES = {"v1.0.0-beta.2": "Beta two page\n", "v1.0.0": "Stable page\n", "v1.1.0-beta.1": "Newer beta page\n",
         "v1.1.0": "Newer stable page\n"}


class World:
    """What the commands read: the page owner over time, the public body and the file at each tag."""

    def __init__(self, tag, owners, body):
        self.tag = tag
        # confirm reads the owner twice per pass; the last entry repeats once the list runs out.
        self.owners = list(owners)
        self.body = body
        self.environment_checks = 0
        temporary = tempfile.TemporaryDirectory()
        self.cleanup = temporary.cleanup
        self.output = Path(temporary.name, "output")
        self.summary = Path(temporary.name, "summary")

    def owner(self, project):
        return self.owners.pop(0) if len(self.owners) > 1 else self.owners[0]

    def run(self, action):
        def environment(repository):
            self.environment_checks += 1

        args = argparse.Namespace(repository=REPOSITORY, tag=self.tag, project="antispeedrun", output=str(self.output))
        with mock.patch.object(rd, "public_owner", self.owner), \
                mock.patch.object(rd, "public_body", lambda project: self.body), \
                mock.patch.object(rd, "source_body", lambda tag: FILES[tag].strip()), \
                mock.patch.object(rd, "check_environment", environment), \
                mock.patch.dict("os.environ", {"GITHUB_STEP_SUMMARY": str(self.summary)}), \
                contextlib.redirect_stdout(io.StringIO()):
            return getattr(rd, action)(args)

    def paste_needed(self):
        return self.output.read_text() if self.output.exists() else ""

    def summarised(self):
        return self.summary.read_text() if self.summary.exists() else ""


class NormalizeTest(unittest.TestCase):
    def test_ignores_only_line_endings_and_trailing_whitespace(self):
        self.assertTrue(rd.same("a\nb\n", "a  \r\nb\t\r\n\r\n"))
        self.assertTrue(rd.same("a\nb", "a\rb\n"))
        self.assertFalse(rd.same("a\nb", "a\n b"))
        self.assertFalse(rd.same("a\nb", "a\n\nb"))
        self.assertFalse(rd.same("A\nb", "a\nb"))


class Worlds(unittest.TestCase):
    def setUp(self):
        self.worlds = []

    def tearDown(self):
        for item in self.worlds:
            item.cleanup()

    def make(self, tag, owners, body):
        item = World(tag, owners, body)
        self.worlds.append(item)
        return item


class CheckTest(Worlds):
    def test_a_matching_body_needs_no_paste_or_approval(self):
        item = self.make("v1.0.0", ["v1.0.0"], "Stable page")
        self.assertFalse(item.run("check"))
        self.assertEqual(item.paste_needed(), "paste_needed=false\n")
        self.assertEqual(item.environment_checks, 0)
        self.assertIn("already matches", item.summarised())

    def test_a_whitespace_only_difference_needs_no_paste(self):
        item = self.make("v1.0.0", ["v1.0.0"], "Stable page   \r\n\r\n")
        self.assertFalse(item.run("check"))
        self.assertEqual(item.paste_needed(), "paste_needed=false\n")

    def test_a_differing_body_asks_for_the_owners_file_after_checking_the_gate(self):
        item = self.make("v1.0.0", ["v1.0.0"], "Old page")
        self.assertTrue(item.run("check"))
        self.assertEqual(item.paste_needed(), "paste_needed=true\n")
        self.assertEqual(item.environment_checks, 1)
        summary = item.summarised()
        self.assertIn(f"https://github.com/{REPOSITORY}/blob/v1.0.0/docs/modrinth-description.md", summary)
        self.assertIn("https://modrinth.com/plugin/antispeedrun/settings/description", summary)
        self.assertIn("newer version owns the page", summary)

    def test_a_tag_that_does_not_own_the_page_is_skipped(self):
        cases = {
            "a pre-release while a stable version is public": ("v1.1.0-beta.1", "v1.0.0"),
            "a stable hotfix below a newer stable version": ("v1.0.0", "v1.1.0"),
            "no public version yet": ("v1.0.0-alpha.1", None),
        }
        for label, (tag, owner) in cases.items():
            with self.subTest(label):
                item = self.make(tag, [owner], "Anything")
                self.assertFalse(item.run("check"))
                self.assertEqual(item.paste_needed(), "paste_needed=false\n")
                self.assertEqual(item.environment_checks, 0)
                self.assertIn(f"skipped for {tag}", item.summarised())


class ConfirmTest(Worlds):
    def test_a_matching_paste_confirms(self):
        item = self.make("v1.0.0", ["v1.0.0"], "Stable page\n")
        item.run("confirm")
        self.assertIn("matches docs/modrinth-description.md at its owner v1.0.0", item.summarised())

    def test_a_differing_body_fails_and_names_the_file_to_paste(self):
        item = self.make("v1.0.0", ["v1.0.0"], "Old page")
        with self.assertRaisesRegex(rd.DescriptionError, "blob/v1.0.0/docs/modrinth-description.md"):
            item.run("confirm")
        self.assertIn("```diff", item.summarised())

    def test_a_tag_superseded_while_waiting_is_checked_against_the_new_owner(self):
        superseded = self.make("v1.0.0", ["v1.1.0"], "Newer stable page")
        superseded.run("confirm")
        self.assertIn("v1.0.0 was superseded while waiting: the Modrinth page now belongs to v1.1.0",
                      superseded.summarised())
        pasted_old = self.make("v1.0.0", ["v1.1.0"], "Stable page")
        with self.assertRaisesRegex(rd.DescriptionError, "blob/v1.1.0/"):
            pasted_old.run("confirm")

    def test_an_owner_change_between_reads_rereads_the_body(self):
        item = self.make("v1.0.0", ["v1.0.0", "v1.1.0", "v1.1.0", "v1.1.0"], "Newer stable page")
        item.run("confirm")
        self.assertIn("now belongs to v1.1.0", item.summarised())

    def test_an_owner_that_keeps_changing_stops(self):
        item = self.make("v1.0.0", ["v1.0.0", "v1.1.0"] * 3 + ["v1.0.0"], "Stable page")
        with self.assertRaisesRegex(rd.DescriptionError, "kept changing"):
            item.run("confirm")

    def test_no_owner_at_confirmation_stops(self):
        item = self.make("v1.0.0", [None], "Stable page")
        with self.assertRaisesRegex(rd.DescriptionError, "no public version"):
            item.run("confirm")


class OwnerTest(unittest.TestCase):
    def versions(self, response):
        with mock.patch.object(rd, "modrinth", lambda path, what, missing_ok=False: response):
            return rd.public_owner("antispeedrun")

    def test_listed_public_versions_decide_the_owner(self):
        listed = [{"version_number": "1.0.0", "status": "listed"},
                  {"version_number": "1.1.0-beta.1", "status": "listed"},
                  {"version_number": "1.2.0", "status": "unlisted"},
                  {"version_number": "not-a-version", "status": "listed"}]
        self.assertEqual(self.versions(listed), "v1.0.0")
        self.assertEqual(self.versions(listed[1:]), "v1.1.0-beta.1")

    def test_a_project_not_public_yet_has_no_owner(self):
        self.assertIsNone(self.versions(None))
        self.assertIsNone(self.versions([]))

    def test_an_unreadable_listing_stops(self):
        with self.assertRaisesRegex(rd.DescriptionError, "Cannot list"):
            self.versions({"error": "x"})


def environment(**changes):
    value = {"protection_rules": [{"type": "required_reviewers", "reviewers": [{"type": "User", "reviewer": {"id": 1}}]}],
             "can_admins_bypass": False,
             "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True}}
    value.update(changes)
    return value


class EnvironmentTest(unittest.TestCase):
    def check(self, settings, policies=({"type": "tag", "name": "v*"},)):
        def read(path):
            return {"branch_policies": list(policies)} if "deployment-branch-policies" in path else settings
        rd.check_environment(REPOSITORY, read)

    def test_a_gate_set_up_as_n6_rel_07_requires_is_accepted(self):
        self.check(environment())

    def test_a_gate_that_would_not_hold_refuses_the_paste(self):
        cases = {
            "no required reviewer": (environment(protection_rules=[]), None),
            "bypass": (environment(can_admins_bypass=True), None),
            "v\\* tags with custom": (environment(deployment_branch_policy={"protected_branches": True}), None),
            "only the v\\* tag policy": (environment(), [{"type": "branch", "name": "main"}]),
            "only the v\\* tag policy ": (environment(), []),
            "invalid": ([], None),
        }
        for message, (settings, policies) in cases.items():
            with self.subTest(message):
                with self.assertRaisesRegex(rd.DescriptionError, message.strip()):
                    if policies is None:
                        self.check(settings)
                    else:
                        self.check(settings, policies)


def jobs():
    text = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
    body = text[text.index("\njobs:\n") + len("\njobs:\n"):]
    parts = re.split(r"^  ([\w-]+):\n", body, flags=re.M)
    return dict(zip(parts[1::2], parts[2::2]))


class WorkflowTest(unittest.TestCase):
    def test_check_and_confirm_hold_no_secret_and_join_no_shared_group(self):
        found = jobs()
        check, confirm = found["check-modrinth-description"], found["confirm-modrinth-description"]
        for job in (check, confirm):
            self.assertNotIn("secrets.", job)
            self.assertNotIn("concurrency:", job)
            self.assertNotIn("contents: write", job)
            self.assertIn("fetch-depth: 0", job)
        self.assertNotIn("environment:", check)
        self.assertIn("needs: [plan, publish-modrinth]", check)
        self.assertIn("release-description.py check", check)
        self.assertIn("environment: modrinth-description\n", confirm)
        self.assertIn("needs.check-modrinth-description.outputs.paste_needed == 'true'", confirm)
        self.assertIn("release-description.py confirm", confirm)

    def test_ci_checks_the_committed_description(self):
        ci = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        self.assertIn("python3 scripts/modrinth-description.py --check", ci)


if __name__ == "__main__":
    unittest.main()
