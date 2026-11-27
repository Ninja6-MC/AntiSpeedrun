#!/usr/bin/env python3
"""Guards for the dispatched AntiSpeedrun release publisher (N6-REL-05).

  release-publisher.py select     --tag vX.Y.Z --destinations all|LIST --modrinth-attested B --hangar-attested B
  release-publisher.py provenance --repository owner/name --tag vX.Y.Z --sha <commit> --run-id <candidate run>
  release-publisher.py guard      --repository owner/name --tag vX.Y.Z --run-id <publisher run> --attempt <n> --attested B
  release-publisher.py approval   --repository owner/name --run-id <publisher run>
  release-publisher.py preflight  --own github|modrinth|hangar --github B --modrinth B --hangar B COMMON

B is true or false. COMMON is release-github.py's arguments: --directory, --evidence, --tag,
--sha, --run-id and --attempt (the candidate run and the attempt that built it) and
--repository.

`select` turns the dispatch's destination list into one flag per destination. `all` means
every destination the tag declares; GitHub always, Modrinth and Hangar for distributor tags.
`provenance` accepts only a successful tag-push run of this repository's candidate
workflow, built from the tag's commit, and pins the attempt whose artifacts are used. An
expired candidate stops the publisher: nothing is ever rebuilt. `guard` refuses a true
absence attestation on a later attempt of the publisher run, or while another publisher
run for the same tag overlaps this one; the distributor publish step runs the same checks
again immediately before its upload. `approval` proceeds only on a person's recorded
approval of the `release` environment for this run. `preflight` runs every credential check
that writes nothing, for every selected destination, in every publish job. Only the job's own
destination (`--own`) is also checked for its current version: the jobs start together, so
another job may already have written to its destination, and its result must not stop this one.
GitHub calls go through the gh CLI with GH_TOKEN.
"""

import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
from pathlib import Path


def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


candidate = _load("release_candidate", "release-candidate.py")

DESTINATIONS = ("github", "modrinth", "hangar")
# Destinations whose authenticated lookup cannot prove absence (RELEASE_PROCESS.md section 3).
ATTESTED = ("modrinth", "hangar")
CANDIDATE_WORKFLOW = ".github/workflows/release-candidate.yml"
PUBLISHER_WORKFLOW = "release.yml"
ENVIRONMENT = "release"


class PublisherError(ValueError):
    pass


def log(message):
    print(message, flush=True)


def flag(value):
    return value == "true"


def write_outputs(values):
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8", newline="\n") as stream:
            for key, value in values.items():
                stream.write(f"{key}={value}\n")


def summary(text):
    log(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8", newline="\n") as stream:
            stream.write(text + "\n\n")


def gh_json(path, paginate=False, jq=None):
    command = ["gh", "api", path]
    if paginate:
        command.append("--paginate")
    if jq:
        command += ["--jq", jq]
    process = subprocess.run(command, capture_output=True, text=True)
    if process.returncode:
        raise PublisherError(f"GitHub API {path} failed: {' '.join(process.stderr.split())}")
    if jq:
        return [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
    return json.loads(process.stdout)


# --- Destinations ----------------------------------------------------------------------

def declared(tag):
    """The destinations a tag's candidate names, in publication order."""
    return [d["name"] for d in candidate.destinations("", tag)]


def select(tag, value, attestations):
    """One flag per destination for this dispatch, from `all` or a comma-separated list."""
    allowed = declared(tag)
    if value.strip() == "all":
        names = list(allowed)
    else:
        names = [name.strip() for name in value.split(",")]
        if not value.strip() or any(not name for name in names):
            raise PublisherError("destinations must be `all` or a comma-separated list such as github,modrinth")
        unknown = sorted(set(names) - set(DESTINATIONS))
        if unknown:
            raise PublisherError(f"Unknown destination {', '.join(unknown)}; expected any of {', '.join(DESTINATIONS)}")
        if len(set(names)) != len(names):
            raise PublisherError("A destination is listed twice")
        refused = [name for name in names if name not in allowed]
        if refused:
            raise PublisherError(f"{tag} is published to {', '.join(allowed)} only, never to {', '.join(refused)} "
                                 "(RELEASE_PROCESS.md section 2)")
    for name, attested in sorted(attestations.items()):
        if attested and name not in names:
            raise PublisherError(f"{name}_absence_confirmed is set, but {name} is not a destination of this dispatch")
    return {name: name in names for name in DESTINATIONS}


# --- Candidate run provenance -----------------------------------------------------------

def check_candidate_run(run, repository, tag, sha):
    """The run must be this repository's candidate workflow, run by pushing this tag at its commit."""
    checks = (
        ("repository", (run.get("repository") or {}).get("full_name"), repository),
        ("head repository", (run.get("head_repository") or {}).get("full_name"), repository),
        ("workflow", run.get("path"), CANDIDATE_WORKFLOW),
        ("event", run.get("event"), "push"),
        ("tag", run.get("head_branch"), tag),
        ("commit", run.get("head_sha"), sha),
        ("status", run.get("status"), "completed"),
        ("conclusion", run.get("conclusion"), "success"),
    )
    for field, actual, expected in checks:
        if actual != expected:
            raise PublisherError(f"Candidate run {run.get('id')} has {field} {actual!r}, expected {expected!r}; "
                                 "start the publisher with the ID of the successful Release Candidate run for this tag")


def pin_attempt(artifacts, run_id):
    """The newest candidate attempt, whose evidence must come with it and neither expired."""
    pattern = re.compile(rf"release-candidate-{re.escape(str(run_id))}-([1-9][0-9]*)")
    attempts = sorted(int(match.group(1)) for artifact in artifacts
                      if (match := pattern.fullmatch(str(artifact.get("name", "")))))
    if not attempts:
        raise PublisherError(f"Candidate run {run_id} retained no release candidate artifact")
    attempt = attempts[-1]
    for kind in ("candidate", "evidence"):
        name = f"release-{kind}-{run_id}-{attempt}"
        matches = [artifact for artifact in artifacts if artifact.get("name") == name]
        if len(matches) != 1:
            raise PublisherError(f"Candidate run {run_id} has no single {name} artifact")
        if matches[0].get("expired") is not False:
            raise PublisherError(
                f"{name} has expired. Nothing is rebuilt for this tag: record each destination still missing "
                "as not published in RELEASE_PROCESS.md section 3; the next release reaches it")
    return attempt


def provenance(args):
    if not re.fullmatch(r"[1-9][0-9]*", args.run_id):
        raise PublisherError("candidate_run_id must be a workflow run ID")
    candidate.release(args.tag)
    run = gh_json(f"repos/{args.repository}/actions/runs/{args.run_id}")
    check_candidate_run(run, args.repository, args.tag, args.sha)
    artifacts = gh_json(f"repos/{args.repository}/actions/runs/{args.run_id}/artifacts?per_page=100",
                        paginate=True, jq=".artifacts[]")
    attempt = pin_attempt(artifacts, args.run_id)
    log(f"Candidate run {args.run_id} attempt {attempt} built {args.tag} at {args.sha}")
    write_outputs({"attempt": attempt})
    return attempt


# --- Attestation guards -----------------------------------------------------------------

def check_attempt(attempt, attested):
    if attested and str(attempt) != "1":
        raise PublisherError(
            f"An absence attestation is valid only on attempt 1 of a publisher run, not attempt {attempt}: a "
            "re-run cannot re-inspect the owner inventory. Inspect it again and start a new dispatch")


def check_overlap(runs, current_id, created_at, tag):
    """Another publisher run for the tag may have written a version the attestation did not see."""
    for run in runs:
        if str(run.get("id")) == str(current_id) or run.get("head_branch") != tag:
            continue
        if run.get("status") != "completed":
            # The shared concurrency group holds a later dispatch until this run ends, so it
            # has written nothing yet; it refuses its own attestation when it starts.
            if (run.get("created_at") or "") > created_at:
                continue
            raise PublisherError(f"Publisher run {run.get('id')} for {tag} is {run.get('status')}; an absence "
                                 "attestation cannot be used beside it")
        if (run.get("updated_at") or "") > created_at:
            raise PublisherError(f"Publisher run {run.get('id')} for {tag} finished after this run was created and "
                                 "may have published a version the attestation did not see. Inspect the owner "
                                 "inventory again and start a new dispatch")


def publisher_runs(repository, tag):
    query = urllib.parse.urlencode({"branch": tag, "per_page": 100})
    return gh_json(f"repos/{repository}/actions/workflows/{PUBLISHER_WORKFLOW}/runs?{query}",
                   paginate=True, jq=".workflow_runs[]")


def refuse_reused_attestation(repository, tag, run_id, attempt):
    """Both N6-REL-05 checks for a true attestation, as one call for the upload step."""
    check_attempt(attempt, True)
    created_at = gh_json(f"repos/{repository}/actions/runs/{run_id}")["created_at"]
    check_overlap(publisher_runs(repository, tag), run_id, created_at, tag)


def guard(args):
    if not flag(args.attested):
        log("No absence attestation in this dispatch")
        return
    refuse_reused_attestation(args.repository, args.tag, args.run_id, args.attempt)
    log(f"Attempt {args.attempt} of publisher run {args.run_id}, and no other publisher run for {args.tag} "
        "overlaps it")


# --- Approval ---------------------------------------------------------------------------

def approvers(approvals, environment=ENVIRONMENT):
    """The people who approved `environment` for the run; any rejection stops it.

    `environment:` alone fails open: GitHub creates a missing environment without protection,
    and one that lost its reviewer lets the job start unreviewed.
    """
    relevant = [a for a in approvals if isinstance(a, dict)
                and any(isinstance(e, dict) and e.get("name") == environment for e in a.get("environments") or [])]
    if any(a.get("state") == "rejected" for a in relevant):
        raise PublisherError(f"The {environment} environment was rejected for this run")
    people = sorted({(a.get("user") or {}).get("login") for a in relevant
                     if a.get("state") == "approved" and (a.get("user") or {}).get("type") == "User"} - {None})
    if not people:
        raise PublisherError(f"No one approved the {environment} environment for this run. It is missing or has no "
                             "required reviewer, so GitHub let the job start unreviewed. Configure it as "
                             "RELEASE_PROCESS.md section 3 describes, then re-run this job.")
    return people


def approval(args):
    people = approvers(gh_json(f"repos/{args.repository}/actions/runs/{args.run_id}/approvals"))
    log(f"{ENVIRONMENT} approved for run {args.run_id} by {', '.join(people)}")


# --- Cross-destination preflight --------------------------------------------------------

def preflight(args, github=None, distributors=None):
    """Every write-free credential check for every selected destination, whichever job runs
    it, so a prerequisite that could be checked stops all destinations before the first public
    write. The version lookup is made for the job's own destination only."""
    if not flag(getattr(args, args.own)):
        raise PublisherError(f"{args.own} is not a destination of this dispatch")
    github = github or _load("release_github", "release-github.py")
    distributors = distributors or _load("release_distributors", "release-distributors.py")
    common = {key: getattr(args, key) for key in
              ("directory", "evidence", "tag", "sha", "run_id", "attempt", "repository")}
    failures = []
    for name in DESTINATIONS:
        if not flag(getattr(args, name)):
            continue
        try:
            if name == "github":
                manifest = candidate.check_evidence(argparse.Namespace(**common))
                github.preflight(github.GitHubApi(args.repository), manifest, args.directory, args.sha)
            else:
                command = "preflight" if name == args.own else "credentials"
                distributors.run(argparse.Namespace(**common, command=command, destination=name))
        except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError,
                urllib.error.URLError) as error:
            log(f"::error::{name} preflight: {error}")
            failures.append(name)
    if failures:
        raise PublisherError(f"Preflight failed for {', '.join(failures)}; nothing was published. Fix the cause, or "
                             "dispatch again without those destinations")
    log("Preflight passed for every selected destination")


# --- Commands ---------------------------------------------------------------------------

def select_command(args):
    candidate.release(args.tag)
    chosen = select(args.tag, args.destinations,
                    {"modrinth": flag(args.modrinth_attested), "hangar": flag(args.hangar_attested)})
    log("Destinations: " + ", ".join(name for name in DESTINATIONS if chosen[name]))
    write_outputs({name: str(chosen[name]).lower() for name in DESTINATIONS})
    return chosen


def parser():
    root = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = root.add_subparsers(dest="command", required=True)
    boolean = {"choices": ("true", "false"), "required": True}

    command = commands.add_parser("select")
    command.add_argument("--tag", required=True)
    command.add_argument("--destinations", required=True)
    command.add_argument("--modrinth-attested", **boolean)
    command.add_argument("--hangar-attested", **boolean)
    command.set_defaults(handler=select_command)

    command = commands.add_parser("provenance")
    for option in ("--repository", "--tag", "--sha", "--run-id"):
        command.add_argument(option, required=True)
    command.set_defaults(handler=provenance)

    command = commands.add_parser("guard")
    for option in ("--repository", "--tag", "--run-id", "--attempt"):
        command.add_argument(option, required=True)
    command.add_argument("--attested", **boolean)
    command.set_defaults(handler=guard)

    command = commands.add_parser("approval")
    for option in ("--repository", "--run-id"):
        command.add_argument(option, required=True)
    command.set_defaults(handler=approval)

    command = commands.add_parser("preflight")
    command.add_argument("--own", choices=DESTINATIONS, required=True)
    for name in DESTINATIONS:
        command.add_argument(f"--{name}", **boolean)
    for option in ("--directory", "--evidence", "--tag", "--sha", "--run-id", "--attempt", "--repository"):
        command.add_argument(option, required=True)
    command.set_defaults(handler=preflight)
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    args.handler(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError,
            urllib.error.URLError) as error:
        sys.exit(f"::error::{error}")
