#!/usr/bin/env python3
"""Confirm that the public Modrinth project body is the committed description (N6-REL-06).

  release-description.py check   --repository owner/name --tag vX.Y.Z --project <slug> --output <file>
  release-description.py confirm --repository owner/name --tag vX.Y.Z --project <slug>

check    if this tag owns the Modrinth project page, compare the public body with
         docs/modrinth-description.md at the tag; on a difference, ask for a paste
confirm  after the maintainer's paste and approval: check the owner again, then compare the
         body with the file at the tag that owns the page now

The page belongs to the highest stable version public on Modrinth, or the highest
pre-release while there is none (N6-REL-05, shared_state_owner in release-candidate.py). A
project with no public version, such as one still awaiting Modrinth's review, has no owner,
so nothing is asked. The comparison ignores line endings and trailing whitespace only.
Neither command writes anything or holds a secret: MODRINTH_TOKEN cannot write the body,
so the maintainer's paste into the Modrinth description editor is the write.
"""

import argparse
import difflib
import importlib.util
import json
import os
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


distributors = _load("release_distributors", "release-distributors.py")
candidate = distributors.candidate

DESCRIPTION = "docs/modrinth-description.md"
ENVIRONMENT = "modrinth-description"


class DescriptionError(ValueError):
    pass


def normalize(text):
    """Line endings and trailing whitespace are the only differences ignored."""
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    return "\n".join(line.rstrip() for line in lines).rstrip("\n")


def same(expected, actual):
    return normalize(expected) == normalize(actual)


def difference(expected, actual):
    lines = difflib.unified_diff(normalize(expected).splitlines(), normalize(actual).splitlines(),
                                 fromfile="file at owner tag", tofile="Modrinth body", n=1, lineterm="")
    return "\n".join(line[:200] for line in list(lines)[:16])


def git(*command):
    return subprocess.run(("git", *command), check=True, text=True, capture_output=True).stdout


def source_body(tag):
    """The description at the tag, without its generated comment: what is pasted into Modrinth."""
    if not candidate.TAG.fullmatch(tag):
        raise DescriptionError(f"{tag} is not a release tag")
    if git("rev-parse", f"refs/tags/{tag}^{{commit}}").strip() != distributors.remote_tag_commit(tag):
        raise DescriptionError(f"Release tag {tag} differs between this checkout and origin")
    try:
        text = git("show", f"refs/tags/{tag}:{DESCRIPTION}")
    except subprocess.CalledProcessError:
        raise DescriptionError(f"{tag} has no {DESCRIPTION}") from None
    if not text.startswith("<!--") or "-->" not in text:
        raise DescriptionError(f"{DESCRIPTION} at {tag} lacks its generated comment")
    return text.split("-->", 1)[1].strip()


def modrinth(path, what, missing_ok=False):
    """Signed out: only what any consumer sees."""
    status, body = distributors.Http().request("GET", distributors.MODRINTH + path)
    if missing_ok and status == 404:
        return None
    return distributors.parse(status, body, what)


def project_path(project):
    return f"/project/{urllib.parse.quote(project, safe='')}"


def public_body(project):
    data = modrinth(project_path(project), "Modrinth project (signed out)")
    if not isinstance(data, dict) or not isinstance(data.get("body"), str):
        raise DescriptionError("The public Modrinth project body is unavailable")
    return data["body"]


def public_owner(project):
    """The tag of the version that owns the project page, judged by the listed public versions."""
    versions = modrinth(project_path(project) + "/version", "Modrinth versions (signed out)", missing_ok=True)
    if versions is None:
        return None
    if not isinstance(versions, list):
        raise DescriptionError("Cannot list the public Modrinth versions")
    listed = [f"v{item['version_number']}" for item in versions
              if isinstance(item, dict) and item.get("status") == "listed"
              and isinstance(item.get("version_number"), str)]
    return candidate.shared_state_owner(listed)


def file_link(repository, tag):
    return f"https://github.com/{repository}/blob/{tag}/{DESCRIPTION}"


def editor_link(project):
    return f"https://modrinth.com/plugin/{urllib.parse.quote(project, safe='')}/settings/description"


def summary(text):
    print(text, flush=True)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8", newline="\n") as stream:
            stream.write(text + "\n\n")


def gh_json(path):
    process = subprocess.run(("gh", "api", path), capture_output=True, text=True)
    if process.returncode:
        raise DescriptionError(f"The job token cannot read {path}; no paste is requested")
    return json.loads(process.stdout)


def check_environment(repository, read=gh_json):
    """Refuse to ask for a paste the confirm job's gate would not hold (N6-REL-07)."""
    base = f"repos/{repository}/environments/{ENVIRONMENT}"
    try:
        environment = read(base)
        reviewers = [item for rule in environment.get("protection_rules") or []
                     if rule.get("type") == "required_reviewers" for item in rule.get("reviewers") or []]
        if not any(item.get("type") in ("User", "Team") and (item.get("reviewer") or {}).get("id")
                   for item in reviewers):
            raise DescriptionError(f"{ENVIRONMENT} has no required reviewer; configure it before a paste is requested")
        if environment.get("can_admins_bypass") is not False:
            raise DescriptionError(f"{ENVIRONMENT} must not let administrators bypass its protection rules")
        policy = environment.get("deployment_branch_policy") or {}
        if policy.get("custom_branch_policies") is not True or policy.get("protected_branches") is not False:
            raise DescriptionError(f"{ENVIRONMENT} must restrict deployments to v* tags with custom policies")
        policies = read(f"{base}/deployment-branch-policies?per_page=100").get("branch_policies")
        if not isinstance(policies, list) or not policies or any(
                item.get("type") != "tag" or item.get("name") != "v*" for item in policies):
            raise DescriptionError(f"{ENVIRONMENT} must allow only the v* tag policy")
    except (AttributeError, TypeError):
        raise DescriptionError(f"The {ENVIRONMENT} protection response is invalid; no paste requested") from None


def write_output(path, value):
    with open(path, "a", encoding="utf-8", newline="\n") as stream:
        stream.write(f"paste_needed={str(value).lower()}\n")


def check(args):
    owner = public_owner(args.project)
    if owner != args.tag:
        summary(f"Modrinth description skipped for {args.tag}: the project page belongs to "
                f"{owner or 'no public version yet'}.")
        write_output(args.output, False)
        return False
    needed = not same(source_body(owner), public_body(args.project))
    if needed:
        check_environment(args.repository)
    write_output(args.output, needed)
    if not needed:
        summary(f"The Modrinth description already matches {DESCRIPTION} at {owner}; no paste or approval needed.")
        return False
    summary(f"## Modrinth description paste required\n\n"
            f"Paste [{DESCRIPTION} at {owner}]({file_link(args.repository, owner)}), without its leading "
            f"generated comment, into the [Modrinth description editor]({editor_link(args.project)}), then "
            f"approve the `{ENVIRONMENT}` environment. If a newer version owns the page by then, the "
            "confirmation checks against its file instead and names it.")
    return True


def confirm(args):
    # The paste is the write. Ownership is read before and after the body, so the file the
    # body is compared with is the one that owned the page when the body was read.
    for _ in range(3):
        owner = public_owner(args.project)
        if owner is None:
            raise DescriptionError("Modrinth has no public version that owns the project page")
        actual = public_body(args.project)
        if public_owner(args.project) == owner:
            break
    else:
        raise DescriptionError("The Modrinth page owner kept changing; re-run this job")
    expected = source_body(owner)
    if owner != args.tag:
        summary(f"{args.tag} was superseded while waiting: the Modrinth page now belongs to {owner}, "
                "so the body is checked against its file.")
    if not same(expected, actual):
        summary(f"The Modrinth body differs from [{DESCRIPTION} at {owner}]({file_link(args.repository, owner)}). "
                "Paste that file without its leading generated comment and re-run this job.\n\n"
                f"```diff\n{difference(expected, actual)}\n```")
        raise DescriptionError(f"The Modrinth body does not match {DESCRIPTION} at {owner}; paste "
                               f"{file_link(args.repository, owner)} and re-run this job")
    summary(f"The Modrinth description matches {DESCRIPTION} at its owner {owner}.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("action", choices=("check", "confirm"))
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--output")
    args = parser.parse_args(argv)
    if not candidate.TAG.fullmatch(args.tag) or (args.action == "check" and not args.output):
        parser.error("a release tag and, for check, --output are required")
    (check if args.action == "check" else confirm)(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError,
            urllib.error.URLError) as error:
        sys.exit(f"::error::Modrinth description stopped: {error}")
