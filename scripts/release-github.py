#!/usr/bin/env python3
"""Promote a verified AntiSpeedrun release candidate to GitHub Releases (N6-REL-03).

  release-github.py --directory candidate --evidence evidence.json --tag vX.Y.Z \
      --sha <source commit> --run-id <id> --attempt <candidate attempt> --repository owner/name

Runs only in the publisher job behind the protected `release` environment, with GH_TOKEN set
to that job's token. It builds nothing and changes no file: it uploads the candidate's bytes as
they are. Every run is a resumable promotion of one candidate:

  1. the candidate, its manifest, digests and passing boot evidence are re-verified, and the
     tag on GitHub must still point at the candidate's source commit;
  2. with no release for the tag, a draft is created carrying the changelog notes;
  3. on a draft (this run's or an interrupted earlier one), assets GitHub never finished
     receiving are removed, assets already uploaded must match the manifest byte for byte,
     and the missing ones are uploaded;
  4. the draft is published with the candidate's pre-release and Latest flags;
  5. what an anonymous consumer downloads is hashed against the manifest.

A published release that differs from the candidate in any way stops the run for the
maintainer to reconcile. Nothing public is ever overwritten or deleted.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
import importlib.util
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("release_candidate", Path(__file__).with_name("release-candidate.py"))
candidate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(candidate)

USER_AGENT = "AntiSpeedrun-release"


class PromotionError(ValueError):
    pass


class GitHubApi:
    """The handful of REST calls promotion makes, through the authenticated gh CLI."""

    def __init__(self, repository):
        self.repository = repository

    def _gh(self, *args, stdin=None):
        process = subprocess.run(("gh", "api", *args), input=stdin, capture_output=True)
        if process.returncode:
            raise PromotionError(f"gh api {args[0]} failed: {process.stderr.decode(errors='replace').strip()}")
        return process.stdout

    def _json(self, *args, stdin=None):
        return json.loads(self._gh(*args, stdin=stdin) or b"null")

    def tag_commit(self, tag):
        """The commit a tag resolves to on GitHub now, peeling annotated tags."""
        target = self._json(f"repos/{self.repository}/git/ref/tags/{tag}")
        if not isinstance(target, dict) or target.get("ref") != f"refs/tags/{tag}":
            raise PromotionError(f"Tag {tag} does not resolve to exactly one ref on GitHub")
        obj = target["object"]
        for _ in range(8):
            if obj["type"] == "commit":
                return obj["sha"]
            if obj["type"] != "tag":
                raise PromotionError(f"Tag {tag} points at a {obj['type']}, not a commit")
            obj = self._json(f"repos/{self.repository}/git/tags/{obj['sha']}")["object"]
        raise PromotionError(f"Tag {tag} nests too deeply to peel")

    def releases(self, tag):
        """Every release carrying this tag name, drafts included."""
        output = self._gh("--paginate", f"repos/{self.repository}/releases?per_page=100",
                          "--jq", f'.[] | select(.tag_name == "{tag}")')
        return [json.loads(line) for line in output.decode().splitlines() if line.strip()]

    def release(self, release_id):
        return self._json(f"repos/{self.repository}/releases/{release_id}")

    def latest_id(self):
        process = subprocess.run(("gh", "api", f"repos/{self.repository}/releases/latest", "--jq", ".id"),
                                 capture_output=True, text=True)
        if process.returncode:
            if "HTTP 404" in process.stderr:
                return None
            raise PromotionError(f"Cannot read the Latest release: {process.stderr.strip()}")
        return int(process.stdout.strip())

    def create_draft(self, tag, title, body, prerelease):
        payload = {"tag_name": tag, "name": title, "body": body, "draft": True,
                   "prerelease": prerelease, "make_latest": "false"}
        return self._json("-X", "POST", f"repos/{self.repository}/releases", "--input", "-",
                          stdin=json.dumps(payload).encode())

    def delete_asset(self, asset_id):
        self._gh("-X", "DELETE", f"repos/{self.repository}/releases/assets/{asset_id}")

    def upload_asset(self, release_id, path):
        url = f"https://uploads.github.com/repos/{self.repository}/releases/{release_id}/assets?name={path.name}"
        return self._json("-X", "POST", url, "-H", "Content-Type: application/octet-stream",
                          "--input", str(path))

    def download_asset(self, asset_id):
        return self._gh("-H", "Accept: application/octet-stream",
                        f"repos/{self.repository}/releases/assets/{asset_id}")

    def publish(self, release_id, prerelease, make_latest):
        payload = {"draft": False, "prerelease": prerelease, "make_latest": "true" if make_latest else "false"}
        return self._json("-X", "PATCH", f"repos/{self.repository}/releases/{release_id}", "--input", "-",
                          stdin=json.dumps(payload).encode())

    def consumer_download(self, url):
        """What a signed-out user gets from the public download link."""
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.read()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def log(message):
    print(message, flush=True)


def check_source(api, manifest, sha):
    if sha != manifest["source_sha"]:
        raise PromotionError(f"This run's commit {sha} is not the candidate's source {manifest['source_sha']}")
    current = api.tag_commit(manifest["tag"])
    if current != manifest["source_sha"]:
        raise PromotionError(f"Tag {manifest['tag']} now points at {current}, not the candidate's "
                             f"source {manifest['source_sha']}; a moved tag is never released")


def check_metadata(release, manifest, title, notes):
    problems = []
    if release.get("tag_name") != manifest["tag"]:
        problems.append("tag")
    if release.get("name") != title:
        problems.append("title")
    if (release.get("body") or "").replace("\r\n", "\n").rstrip("\n") != notes.rstrip("\n"):
        problems.append("notes")
    if release.get("prerelease") is not manifest["prerelease"]:
        problems.append("pre-release flag")
    if problems:
        raise PromotionError(f"Release {release.get('id')} for {manifest['tag']} differs from the candidate in: "
                             f"{', '.join(problems)}. Reconcile it by hand; it is not overwritten.")


def check_assets(api, release, manifest, anonymous):
    """Every asset present, uploaded and byte-identical to the manifest; nothing extra."""
    expected = candidate.published_assets(manifest["version"])
    assets = release.get("assets") or []
    names = sorted(asset.get("name") for asset in assets)
    if names != sorted(expected):
        raise PromotionError(f"Release assets {names} differ from the candidate's {sorted(expected)}")
    for asset in assets:
        if asset.get("state") != "uploaded":
            raise PromotionError(f"Release asset {asset['name']} is {asset.get('state')}, not uploaded")
        if anonymous:
            data = api.consumer_download(asset["browser_download_url"])
        else:
            data = api.download_asset(asset["id"])
        if sha256(data) != manifest["files"][asset["name"]]:
            raise PromotionError(f"Release asset {asset['name']} does not match the candidate's SHA-256")
        log(f"  {asset['name']}: sha256 {manifest['files'][asset['name']]} matches")


def complete_draft(api, release, manifest, directory):
    """Bring a draft's assets to exactly the candidate's, resuming an interrupted upload."""
    expected = candidate.published_assets(manifest["version"])
    present = set()
    for asset in release.get("assets") or []:
        name = asset.get("name")
        if name not in expected:
            raise PromotionError(f"Draft {release['id']} carries unexpected asset {name}; reconcile it by hand")
        if asset.get("state") != "uploaded":
            # An upload GitHub never finished. The draft is not public, and these are not
            # published bytes, so it is removed and uploaded again from the candidate.
            log(f"Removing incomplete draft asset {name} ({asset.get('state')})")
            api.delete_asset(asset["id"])
            continue
        if sha256(api.download_asset(asset["id"])) != manifest["files"][name]:
            raise PromotionError(f"Draft asset {name} differs from the candidate; reconcile it by hand")
        present.add(name)
    for name in expected:
        if name not in present:
            log(f"Uploading {name}")
            api.upload_asset(release["id"], Path(directory) / name)


def check_latest(api, release, manifest):
    latest = api.latest_id()
    if manifest["make_latest"] and latest != release["id"]:
        raise PromotionError(f"Stable release {manifest['tag']} is published but is not GitHub's Latest "
                             f"(Latest is {latest}); reconcile it by hand")
    if not manifest["make_latest"] and latest == release["id"]:
        raise PromotionError(f"Pre-release {manifest['tag']} is marked Latest; reconcile it by hand")


def promote(api, manifest, directory, sha):
    tag = manifest["tag"]
    title = f"{candidate.PLUGIN} {tag}"
    notes = (Path(directory) / "release-notes.md").read_text(encoding="utf-8")
    check_source(api, manifest, sha)

    matches = api.releases(tag)
    if len(matches) > 1:
        raise PromotionError(f"{len(matches)} releases carry tag {tag}; reconcile them by hand")
    if matches:
        release = matches[0]
        log(f"Found {'draft' if release.get('draft') else 'published'} release {release['id']} for {tag}")
    else:
        release = api.create_draft(tag, title, notes, manifest["prerelease"])
        log(f"Created draft release {release['id']} for {tag}")
    check_metadata(release, manifest, title, notes)

    if release.get("draft"):
        complete_draft(api, release, manifest, directory)
        release = api.release(release["id"])
        check_metadata(release, manifest, title, notes)
        log("Draft assets, re-read from GitHub:")
        check_assets(api, release, manifest, anonymous=False)
        # The approval may have waited days; the tag is checked again right before going public.
        check_source(api, manifest, sha)
        release = api.publish(release["id"], manifest["prerelease"], manifest["make_latest"])
        log(f"Published release {release['id']} ({'pre-release' if manifest['prerelease'] else 'stable'})")

    release = api.release(release["id"])
    if release.get("draft"):
        raise PromotionError(f"Release {release['id']} is still a draft after publishing")
    check_metadata(release, manifest, title, notes)
    log("Public assets, downloaded anonymously:")
    check_assets(api, release, manifest, anonymous=True)
    check_latest(api, release, manifest)
    log(f"{tag} is published and matches candidate {manifest['candidate_id']}: {release.get('html_url')}")
    return release


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for option in ("--directory", "--evidence", "--tag", "--sha", "--run-id", "--attempt", "--repository"):
        parser.add_argument(option, required=True)
    args = parser.parse_args(argv)
    manifest = candidate.check_evidence(args)
    promote(GitHubApi(args.repository), manifest, args.directory, args.sha)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, urllib.error.URLError) as error:
        sys.exit(f"::error::{error}")
