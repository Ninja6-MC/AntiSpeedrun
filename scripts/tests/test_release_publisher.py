from pathlib import Path
import argparse
import contextlib
import importlib.util
import io
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release_publisher", ROOT / "scripts" / "release-publisher.py")
rp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rp)

REPO = "Ninja6-MC/AntiSpeedrun"
SHA = "0123456789abcdef0123456789abcdef01234567"
NO = {"modrinth": False, "hangar": False}


def quiet(function, *args, **kwargs):
    with contextlib.redirect_stdout(io.StringIO()):
        return function(*args, **kwargs)


class SelectTest(unittest.TestCase):
    def test_all_means_every_destination_the_tag_declares(self):
        cases = {
            "v0.6.0": {"github": True, "modrinth": False, "hangar": False},
            "v1.0.0-rc.1": {"github": True, "modrinth": False, "hangar": False},
            "v1.0.0-alpha.1": {"github": True, "modrinth": True, "hangar": True},
            "v1.0.0": {"github": True, "modrinth": True, "hangar": True},
        }
        for tag, expected in cases.items():
            with self.subTest(tag):
                self.assertEqual(rp.select(tag, "all", NO), expected)

    def test_a_list_selects_only_its_destinations(self):
        self.assertEqual(rp.select("v1.0.0", " hangar ,github", NO),
                         {"github": True, "modrinth": False, "hangar": True})
        self.assertEqual(rp.select("v1.0.0", "modrinth", {"modrinth": True, "hangar": False}),
                         {"github": False, "modrinth": True, "hangar": False})

    def test_refused_selections(self):
        cases = {
            ("v0.6.0", "github,modrinth"): "never to modrinth",
            ("v1.0.0-rc.1", "hangar"): "never to hangar",
            ("v1.0.0", "github,curseforge"): "Unknown destination curseforge",
            ("v1.0.0", "github,github"): "listed twice",
            ("v1.0.0", ""): "comma-separated",
            ("v1.0.0", "github,"): "comma-separated",
        }
        for (tag, value), message in cases.items():
            with self.subTest(tag=tag, value=value):
                with self.assertRaisesRegex(rp.PublisherError, message):
                    rp.select(tag, value, NO)

    def test_an_attestation_for_a_destination_not_selected_is_refused(self):
        with self.assertRaisesRegex(rp.PublisherError, "hangar_absence_confirmed.*not a destination"):
            rp.select("v1.0.0", "github,modrinth", {"modrinth": True, "hangar": True})
        with self.assertRaisesRegex(rp.PublisherError, "modrinth_absence_confirmed"):
            rp.select("v0.6.0", "all", {"modrinth": True, "hangar": False})


def candidate_run(**changes):
    run = {"id": 77, "repository": {"full_name": REPO}, "head_repository": {"full_name": REPO},
           "path": ".github/workflows/release-candidate.yml", "event": "push", "head_branch": "v1.0.0",
           "head_sha": SHA, "status": "completed", "conclusion": "success"}
    run.update(changes)
    return run


class ProvenanceTest(unittest.TestCase):
    def test_the_successful_candidate_run_for_this_tag_is_accepted(self):
        rp.check_candidate_run(candidate_run(), REPO, "v1.0.0", SHA)

    def test_any_other_run_is_refused(self):
        cases = {
            "workflow": dict(path=".github/workflows/ci.yml"),
            "tag": dict(head_branch="v1.0.1"),
            "event": dict(event="pull_request"),
            "commit": dict(head_sha="f" * 40),
            "status": dict(status="in_progress", conclusion=None),
            "conclusion": dict(conclusion="failure"),
            "repository": dict(repository={"full_name": "someone/AntiSpeedrun"}),
            "head repository": dict(head_repository={"full_name": "someone/AntiSpeedrun"}),
        }
        for field, change in cases.items():
            with self.subTest(field):
                with self.assertRaisesRegex(rp.PublisherError, f"has {field} "):
                    rp.check_candidate_run(candidate_run(**change), REPO, "v1.0.0", SHA)

    def test_the_newest_candidate_attempt_is_pinned_with_its_own_evidence(self):
        artifacts = [{"name": n, "expired": False} for n in (
            "release-candidate-77-1", "release-evidence-77-1", "release-candidate-77-2", "release-evidence-77-2",
            "release-boot-paper-1.21.4-77-2", "release-candidate-770-9")]
        self.assertEqual(rp.pin_attempt(artifacts, 77), 2)

    def test_missing_or_expired_artifacts_stop_the_publisher_without_a_rebuild(self):
        with self.assertRaisesRegex(rp.PublisherError, "retained no release candidate"):
            rp.pin_attempt([{"name": "release-evidence-77-1", "expired": False}], 77)
        with self.assertRaisesRegex(rp.PublisherError, "no single release-evidence-77-2"):
            rp.pin_attempt([{"name": "release-candidate-77-2", "expired": False},
                            {"name": "release-evidence-77-1", "expired": False}], 77)
        for expired in ("candidate", "evidence"):
            with self.subTest(expired):
                artifacts = [{"name": f"release-{kind}-77-1", "expired": kind == expired}
                             for kind in ("candidate", "evidence")]
                with self.assertRaisesRegex(rp.PublisherError, "expired. Nothing is rebuilt"):
                    rp.pin_attempt(artifacts, 77)


class AttestationGuardTest(unittest.TestCase):
    CREATED = "2027-02-01T10:00:00Z"

    def test_a_true_attestation_is_refused_on_a_later_attempt(self):
        rp.check_attempt("1", True)
        rp.check_attempt("2", False)
        with self.assertRaisesRegex(rp.PublisherError, "only on attempt 1"):
            rp.check_attempt("2", True)

    def test_overlapping_publisher_runs_are_refused(self):
        earlier_running = {"id": 5, "head_branch": "v1.0.0", "status": "in_progress",
                           "created_at": "2027-02-01T09:00:00Z", "updated_at": "2027-02-01T09:30:00Z"}
        finished_after = {"id": 6, "head_branch": "v1.0.0", "status": "completed",
                          "created_at": "2027-02-01T09:00:00Z", "updated_at": "2027-02-01T10:05:00Z"}
        for run, message in ((earlier_running, "is in_progress"), (finished_after, "finished after")):
            with self.subTest(run["id"]):
                with self.assertRaisesRegex(rp.PublisherError, message):
                    rp.check_overlap([run], 9, self.CREATED, "v1.0.0")

    def test_runs_that_cannot_have_written_since_are_ignored(self):
        runs = [
            {"id": 9, "head_branch": "v1.0.0", "status": "in_progress", "created_at": self.CREATED},
            {"id": 4, "head_branch": "v1.0.0", "status": "completed",
             "created_at": "2027-01-30T09:00:00Z", "updated_at": "2027-01-30T09:20:00Z"},
            {"id": 10, "head_branch": "v1.0.0", "status": "queued", "created_at": "2027-02-01T10:01:00Z"},
            {"id": 11, "head_branch": "v1.0.1", "status": "in_progress", "created_at": "2027-02-01T09:00:00Z"},
        ]
        rp.check_overlap(runs, 9, self.CREATED, "v1.0.0")

    def test_the_upload_check_reads_this_run_and_the_tags_publisher_runs(self):
        calls = []

        def fake(path, paginate=False, jq=None):
            calls.append(path)
            if path.endswith("/runs/9"):
                return {"created_at": self.CREATED}
            return [{"id": 5, "head_branch": "v1.0.0", "status": "waiting", "created_at": "2027-02-01T09:00:00Z"}]

        original, rp.gh_json = rp.gh_json, fake
        try:
            with self.assertRaisesRegex(rp.PublisherError, "is waiting"):
                rp.refuse_reused_attestation(REPO, "v1.0.0", "9", "1")
            with self.assertRaisesRegex(rp.PublisherError, "only on attempt 1"):
                rp.refuse_reused_attestation(REPO, "v1.0.0", "9", "3")
        finally:
            rp.gh_json = original
        self.assertEqual(calls, [f"repos/{REPO}/actions/runs/9",
                                 f"repos/{REPO}/actions/workflows/release.yml/runs?branch=v1.0.0&per_page=100"])

    def test_no_attestation_skips_every_lookup(self):
        original, rp.gh_json = rp.gh_json, None
        try:
            quiet(rp.guard, argparse.Namespace(attested="false", repository=REPO, tag="v1.0.0", run_id="9",
                                               attempt="4"))
        finally:
            rp.gh_json = original


def approval(state, login="maintainer", kind="User", environment="release"):
    return {"state": state, "user": {"login": login, "type": kind}, "environments": [{"name": environment}]}


class ApprovalTest(unittest.TestCase):
    def test_a_person_approving_release_is_required(self):
        self.assertEqual(rp.approvers([approval("approved")]), ["maintainer"])
        cases = {
            "rejected": [approval("approved"), approval("rejected", login="other")],
            "No one approved": [],
            "No one approved ": [approval("approved", login="ninja6-agent[bot]", kind="Bot")],
            "No one approved  ": [approval("approved", environment="modrinth-description")],
        }
        for message, approvals in cases.items():
            with self.subTest(message):
                with self.assertRaisesRegex(rp.PublisherError, message.strip()):
                    rp.approvers(approvals)


class FakeGitHubModule:
    def __init__(self, error=None):
        self.error = error
        self.calls = []

    def GitHubApi(self, repository):
        return repository

    def preflight(self, api, manifest, directory, sha):
        self.calls.append(api)
        if self.error:
            raise ValueError(self.error)


class FakeDistributorsModule:
    def __init__(self, errors=None):
        self.errors = errors or {}
        self.calls = []

    def run(self, args):
        self.calls.append((args.command, args.destination))
        if args.destination in self.errors:
            raise ValueError(self.errors[args.destination])


class PreflightTest(unittest.TestCase):
    def args(self, **selected):
        values = dict(github="false", modrinth="false", hangar="false", directory="candidate",
                      evidence="evidence.json", tag="v1.0.0", sha=SHA, run_id="77", attempt="1", repository=REPO)
        values.update(selected)
        return argparse.Namespace(**values)

    def run_preflight(self, args, github, distributors):
        original, rp.candidate.check_evidence = rp.candidate.check_evidence, lambda args: {}
        try:
            return quiet(rp.preflight, args, github, distributors)
        finally:
            rp.candidate.check_evidence = original

    def test_every_selected_destination_is_checked_and_nothing_else(self):
        github, distributors = FakeGitHubModule(), FakeDistributorsModule()
        self.run_preflight(self.args(github="true", hangar="true"), github, distributors)
        self.assertEqual(github.calls, [REPO])
        self.assertEqual(distributors.calls, [("preflight", "hangar")])

    def test_one_failing_destination_stops_all_after_checking_each(self):
        github, distributors = FakeGitHubModule(), FakeDistributorsModule({"modrinth": "Missing MODRINTH_TOKEN"})
        with self.assertRaisesRegex(rp.PublisherError, "Preflight failed for modrinth; nothing was published"):
            self.run_preflight(self.args(github="true", modrinth="true", hangar="true"), github, distributors)
        self.assertEqual(distributors.calls, [("preflight", "modrinth"), ("preflight", "hangar")])
        self.assertEqual(github.calls, [REPO])


def jobs(workflow):
    """Each job's text in a workflow, keyed by job ID."""
    text = (ROOT / ".github" / "workflows" / workflow).read_text(encoding="utf-8")
    body = text[text.index("\njobs:\n") + len("\njobs:\n"):]
    parts = re.split(r"^  ([\w-]+):\n", body, flags=re.M)
    return text, dict(zip(parts[1::2], parts[2::2]))


class PublisherWorkflowTest(unittest.TestCase):
    GUARD = "if: github.ref == format('refs/tags/{0}', inputs.tag) && startsWith(inputs.tag, 'v')"

    def setUp(self):
        self.text, self.jobs = jobs("release.yml")

    def test_started_by_hand_only_with_a_nonce_in_the_run_name(self):
        on = self.text[self.text.index("\non:\n"):self.text.index("\nconcurrency:\n")]
        self.assertEqual(re.findall(r"^  (\w+):", on, re.M), ["workflow_dispatch"])
        for name in ("tag", "candidate_run_id", "destinations", "modrinth_absence_confirmed",
                     "hangar_absence_confirmed", "nonce"):
            self.assertIn(f"\n      {name}:\n", on)
        self.assertIn("run-name: Publish ${{ inputs.tag }} ${{ inputs.nonce }}", self.text)
        self.assertIn("group: release-publish-${{ github.ref }}\n", self.text)

    def test_every_job_runs_only_on_the_tag_it_was_given(self):
        self.assertEqual(sorted(self.jobs), ["plan", "publish-github", "publish-hangar", "publish-modrinth"])
        for name, job in self.jobs.items():
            with self.subTest(name):
                self.assertIn(self.GUARD, job)

    def test_plan_has_no_environment_and_no_secret(self):
        plan = self.jobs["plan"]
        self.assertNotIn("environment:", plan)
        self.assertNotIn("secrets.", plan)
        self.assertNotIn("contents: write", plan)
        for step in ("release-publisher.py select", "release-publisher.py provenance",
                     "release-candidate.py check-evidence", "release-publisher.py guard",
                     "release-distributors.py public modrinth", "release-distributors.py public hangar"):
            self.assertIn(step, plan)

    def test_each_destination_publishes_in_its_own_gated_job_after_a_full_preflight(self):
        for name in ("github", "modrinth", "hangar"):
            with self.subTest(name):
                job = self.jobs[f"publish-{name}"]
                self.assertIn("needs: plan\n", job)
                self.assertIn("environment: release\n", job)
                self.assertIn(f"needs.plan.outputs.{name} == 'true'", job)
                approval = job.index("release-publisher.py approval")
                preflight = job.index("release-publisher.py preflight --github")
                self.assertLess(approval, preflight)
                writes = {"github": "release-github.py --directory",
                          "modrinth": "release-distributors.py publish modrinth",
                          "hangar": "release-distributors.py publish hangar"}
                self.assertLess(preflight, job.index(writes[name]))
                for other in ("github", "modrinth", "hangar"):
                    self.assertIn(f"SELECT_{other.upper()}: ${{{{ needs.plan.outputs.{other} }}}}", job)
        self.assertIn("contents: write", self.jobs["publish-github"])
        for name in ("modrinth", "hangar"):
            self.assertNotIn("contents: write", self.jobs[f"publish-{name}"])

    def test_distributor_uploads_carry_this_runs_attempt_and_dispatch_attestation(self):
        for name in ("modrinth", "hangar"):
            with self.subTest(name):
                job = self.jobs[f"publish-{name}"]
                self.assertIn(f"ATTESTED: ${{{{ inputs.{name}_absence_confirmed }}}}", job)
                self.assertIn('--publisher-run-id "$GITHUB_RUN_ID" --publisher-attempt "$GITHUB_RUN_ATTEMPT"', job)

    def test_attestations_come_only_from_the_dispatch(self):
        for line in self.text.splitlines():
            if "absence_confirmed" in line and "${{" in line:
                for reference in re.findall(r"\$\{\{([^}]*)\}\}", line):
                    if "absence_confirmed" in reference:
                        self.assertNotRegex(reference, r"\b(vars|secrets|env)\.")
                        self.assertRegex(reference, r"\binputs\.")


class CandidateWorkflowTest(unittest.TestCase):
    def test_the_candidate_workflow_publishes_nothing_and_keeps_its_artifacts_90_days(self):
        text, found = jobs("release-candidate.yml")
        self.assertEqual(sorted(found), ["candidate", "evidence", "verify"])
        for forbidden in ("environment:", "secrets.", "contents: write"):
            self.assertNotIn(forbidden, text)
        # The paths filter names the publishing scripts; no job runs them.
        steps = "".join(found.values())
        for script in ("release-github.py", "release-distributors.py", "release-publisher.py"):
            self.assertNotIn(script, steps)
        days = re.findall(r"retention-days: (\d+)", text)
        self.assertTrue(days)
        self.assertEqual(set(days), {"90"})


if __name__ == "__main__":
    unittest.main()
