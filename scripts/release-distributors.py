#!/usr/bin/env python3
"""Publish a verified AntiSpeedrun release candidate to Modrinth and Hangar (N6-REL-03).

  release-distributors.py plan --tag vX.Y.Z
  release-distributors.py preflight modrinth|hangar COMMON
  release-distributors.py publish modrinth|hangar COMMON

COMMON is --directory, --evidence, --tag, --sha (this run's commit), --run-id, --attempt (the
candidate job's attempt) and --repository, as for release-github.py.

Runs only in the publisher job behind the protected `release` environment. Each destination
reads its own token and project from the environment and nothing else:

  modrinth  MODRINTH_TOKEN (secret), MODRINTH_PROJECT (project slug or ID)
  hangar    HANGAR_API_TOKEN (secret), HANGAR_PROJECT (project slug)

`plan` says whether a tag goes to the distributors at all. A development build (major
version 0, with or without a suffix) and a release candidate never do; `preflight` and
`publish` refuse them outright. Everything published is taken from the candidate manifest:
version, channel, loaders, platforms and Minecraft versions.

`preflight` writes nothing. It checks the configuration, the project and the token's upload
permission, and that the destination either lacks this version or already holds exactly the
candidate. `publish` re-verifies the candidate and its evidence, uploads only when the version
is absent, then checks what a signed-out consumer downloads against the manifest. A version
that differs from the candidate in any way stops the run; nothing published is overwritten.
"""

import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("release_candidate", Path(__file__).with_name("release-candidate.py"))
candidate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(candidate)

USER_AGENT = "Ninja6-MC/AntiSpeedrun-release (https://github.com/Ninja6-MC/AntiSpeedrun)"
MODRINTH = "https://api.modrinth.com/v2"
HANGAR = "https://hangar.papermc.io/api/v1"
# Modrinth's project member permission bit for uploading versions.
MODRINTH_UPLOAD_VERSION = 1 << 0
HANGAR_FOLIA_TAG = "SUPPORTS_FOLIA"
CONFIG = {
    "modrinth": ("MODRINTH_TOKEN", "MODRINTH_PROJECT"),
    "hangar": ("HANGAR_API_TOKEN", "HANGAR_PROJECT"),
}


class DistributorError(ValueError):
    pass


def log(message):
    print(message, flush=True)


def quote(value):
    return urllib.parse.quote(str(value), safe="")


def multipart(parts):
    """Encode (name, filename or None, content type, bytes) parts as multipart/form-data."""
    boundary = uuid.uuid4().hex
    body = bytearray()
    for name, filename, content_type, data in parts:
        disposition = f'form-data; name="{name}"'
        if filename:
            disposition += f'; filename="{filename}"'
        body += (f"--{boundary}\r\nContent-Disposition: {disposition}\r\n"
                 f"Content-Type: {content_type}\r\n\r\n").encode()
        body += data + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return f"multipart/form-data; boundary={boundary}", bytes(body)


class Http:
    """One HTTPS request. Returns (status, body) for every HTTP status; raises on transport errors."""

    def request(self, method, url, headers=None, body=None, timeout=60):
        request = urllib.request.Request(url, data=body, method=method,
                                         headers={"User-Agent": USER_AGENT, **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()


def parse(status, body, what):
    if not 200 <= status < 300:
        raise DistributorError(f"{what} returned HTTP {status}: {body[:300].decode(errors='replace')}")
    try:
        return json.loads(body)
    except json.JSONDecodeError as error:
        raise DistributorError(f"{what} returned unreadable JSON: {error}") from error


def accepted(status, body, what):
    """An upload's response is not relied on: the version is looked up again afterwards."""
    if not 200 <= status < 300:
        raise DistributorError(f"{what} returned HTTP {status}: {body[:300].decode(errors='replace')}")


def optional(status, body, what):
    """The parsed body, or None on 404."""
    return None if status == 404 else parse(status, body, what)


class ModrinthApi:
    def __init__(self, token, http=None):
        self.token = token
        self.http = http or Http()

    def _get(self, path, what, auth=True, allow_missing=False):
        headers = {"Authorization": self.token} if auth else {}
        status, body = self.http.request("GET", MODRINTH + path, headers)
        return optional(status, body, what) if allow_missing else parse(status, body, what)

    def public_project(self, project):
        return self._get(f"/project/{quote(project)}", f"Modrinth project {project} (signed out)",
                         auth=False, allow_missing=True)

    def user(self):
        return self._get("/user", "Modrinth token owner")

    def members(self, project_id):
        return self._get(f"/project/{quote(project_id)}/members", "Modrinth project members")

    def versions(self, project_id):
        return self._get(f"/project/{quote(project_id)}/version", "Modrinth project versions")

    def version_by_file(self, sha512):
        return self._get(f"/version_file/{sha512}?algorithm=sha512", "Modrinth file lookup", allow_missing=True)

    def public_version(self, version_id):
        return self._get(f"/version/{quote(version_id)}", "Modrinth version (signed out)",
                         auth=False, allow_missing=True)

    def create_version(self, data, jar):
        content_type, body = multipart([
            ("data", None, "application/json", json.dumps(data).encode()),
            ("jar", jar.name, "application/java-archive", jar.read_bytes()),
        ])
        status, response = self.http.request("POST", MODRINTH + "/version",
                                             {"Authorization": self.token, "Content-Type": content_type},
                                             body, timeout=300)
        accepted(status, response, "Modrinth version upload")

    def download(self, url):
        status, body = self.http.request("GET", url, timeout=300)
        if status != 200:
            raise DistributorError(f"Signed-out download of {url} returned HTTP {status}")
        return body


class HangarApi:
    def __init__(self, key, http=None):
        self.key = key
        self.http = http or Http()
        self._session = None

    def _auth(self):
        if self._session is None:
            status, body = self.http.request("POST", f"{HANGAR}/authenticate?apiKey={quote(self.key)}", body=b"")
            if status != 200:
                raise DistributorError(f"Hangar rejected HANGAR_API_TOKEN (HTTP {status})")
            token = json.loads(body).get("token")
            if not token:
                raise DistributorError("Hangar authentication returned no session token")
            self._session = {"Authorization": f"HangarAuth {token}"}
        return self._session

    def _get(self, path, what, auth=True, allow_missing=False):
        status, body = self.http.request("GET", HANGAR + path, self._auth() if auth else {})
        return optional(status, body, what) if allow_missing else parse(status, body, what)

    def public_project(self, slug):
        return self._get(f"/projects/{quote(slug)}", f"Hangar project {slug} (signed out)",
                         auth=False, allow_missing=True)

    def version(self, slug, name, auth=True):
        return self._get(f"/projects/{quote(slug)}/versions/{quote(name)}",
                         f"Hangar version {name}{'' if auth else ' (signed out)'}", auth=auth, allow_missing=True)

    def can_create_versions(self, project_id):
        result = self._get(f"/permissions/hasAll?permissions=create_version&project={quote(project_id)}",
                           "Hangar permission check")
        return isinstance(result, dict) and result.get("result") is True

    def upload(self, slug, data, jar):
        content_type, body = multipart([
            ("versionUpload", None, "application/json", json.dumps(data).encode()),
            ("files", jar.name, "application/java-archive", jar.read_bytes()),
        ])
        status, response = self.http.request("POST", f"{HANGAR}/projects/{quote(slug)}/upload",
                                             {**self._auth(), "Content-Type": content_type}, body, timeout=300)
        accepted(status, response, "Hangar version upload")

    def download(self, url):
        status, body = self.http.request("GET", url, timeout=300)
        if status != 200:
            raise DistributorError(f"Signed-out download of {url} returned HTTP {status}")
        return body


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def destination(manifest, name):
    """The candidate's own record of what goes to this distributor."""
    found = [d for d in manifest.get("destinations", []) if d.get("name") == name]
    if len(found) != 1:
        raise DistributorError(f"Candidate {manifest.get('candidate_id')} does not name {name} as a destination")
    return found[0]


def notes(directory):
    return (Path(directory) / "release-notes.md").read_text(encoding="utf-8")


def same_text(published, expected):
    return (published or "").replace("\r\n", "\n").rstrip("\n") == expected.rstrip("\n")


def stop(name, version, problems):
    if problems:
        raise DistributorError(f"{name} version {version} differs from the candidate in: {', '.join(problems)}. "
                               "Reconcile it by hand; it is not overwritten.")


# --- Modrinth ---------------------------------------------------------------------------

def modrinth_project(api, project):
    """The project's ID. It must be public: an unapproved project hides its versions."""
    details = api.public_project(project)
    if not isinstance(details, dict) or not details.get("id"):
        raise DistributorError(f"Modrinth project {project} is not visible signed out; it must be approved "
                               "and public before a release can be published to it")
    if project not in (details.get("id"), details.get("slug")):
        raise DistributorError(f"Modrinth resolved {project} to {details.get('slug')}; set MODRINTH_PROJECT "
                               "to the project's slug or ID")
    return details["id"]


def modrinth_upload_access(api, project_id):
    user = api.user()
    for member in api.members(project_id) or []:
        if not isinstance(member, dict):
            continue
        permissions = member.get("permissions")
        if (isinstance(member.get("user"), dict) and member["user"].get("id") == user.get("id")
                and member.get("accepted") is True and isinstance(permissions, int)
                and not isinstance(permissions, bool) and permissions & MODRINTH_UPLOAD_VERSION):
            return
    raise DistributorError(f"The MODRINTH_TOKEN owner cannot upload versions to Modrinth project {project_id}")


def modrinth_payload(manifest, dest, project_id, directory):
    return {
        "name": f"{candidate.PLUGIN} {manifest['version']}",
        "version_number": manifest["version"],
        "changelog": notes(directory),
        "dependencies": [],
        "game_versions": dest["game_versions"],
        "version_type": dest["version_type"],
        "loaders": dest["loaders"],
        "featured": False,
        "status": "listed",
        "project_id": project_id,
        "file_parts": ["jar"],
        "primary_file": "jar",
    }


def modrinth_state(api, manifest, dest, project_id, directory):
    """'absent', or 'complete' once the published version is proven to be the candidate."""
    version = manifest["version"]
    jar = Path(directory) / candidate.jar_name(version)
    sha512 = hashlib.sha512(jar.read_bytes()).hexdigest()
    found = [v for v in api.versions(project_id) if isinstance(v, dict) and v.get("version_number") == version]
    # Modrinth refuses a file it already hosts, anywhere. The lookup also finds a version the
    # project listing omits, so neither a stale listing nor a lost response leads to a second upload.
    by_file = api.version_by_file(sha512)
    if not found:
        if by_file is not None:
            raise DistributorError(f"The candidate JAR is already on Modrinth as version {by_file.get('id')} "
                                   f"({by_file.get('version_number')}), which the project does not list as "
                                   f"{version}. Reconcile it by hand.")
        return "absent"
    if len(found) > 1:
        raise DistributorError(f"{len(found)} Modrinth versions are numbered {version}; reconcile them by hand")
    published = found[0]
    expected = modrinth_payload(manifest, dest, project_id, directory)
    problems = [key for key in ("name", "version_type", "project_id") if published.get(key) != expected[key]]
    if published.get("status") != "listed":
        problems.append(f"status {published.get('status')}")
    for key in ("loaders", "game_versions"):
        if sorted(published.get(key) or []) != sorted(expected[key]):
            problems.append(key)
    if not same_text(published.get("changelog"), expected["changelog"]):
        problems.append("changelog")
    files = published.get("files") or []
    if (len(files) != 1 or files[0].get("filename") != jar.name
            or (files[0].get("hashes") or {}).get("sha512") != sha512):
        problems.append("files")
    if not isinstance(by_file, dict) or by_file.get("id") != published.get("id"):
        problems.append("file identity")
    stop("Modrinth", version, problems)

    public = api.public_version(published["id"])
    if not isinstance(public, dict) or public.get("id") != published["id"] or public.get("files") != files:
        raise DistributorError(f"Modrinth version {version} is not visible signed out with the same files")
    data = api.download(files[0]["url"])
    if sha256(data) != manifest["files"][jar.name]:
        raise DistributorError(f"What Modrinth serves for {version} does not match the candidate's SHA-256")
    log(f"  modrinth {version}: {jar.name} sha256 {manifest['files'][jar.name]} matches, signed out")
    return "complete"


def modrinth_preflight(api, manifest, project, directory):
    dest = destination(manifest, "modrinth")
    project_id = modrinth_project(api, project)
    modrinth_upload_access(api, project_id)
    return modrinth_state(api, manifest, dest, project_id, directory)


def modrinth_publish(api, manifest, project, directory):
    dest = destination(manifest, "modrinth")
    project_id = modrinth_project(api, project)
    state = modrinth_state(api, manifest, dest, project_id, directory)
    if state == "absent":
        version = manifest["version"]
        log(f"Uploading {version} to Modrinth project {project} as {dest['version_type']}")
        api.create_version(modrinth_payload(manifest, dest, project_id, directory),
                           Path(directory) / candidate.jar_name(version))
        state = modrinth_state(api, manifest, dest, project_id, directory)
        if state != "complete":
            raise DistributorError(f"Modrinth does not list {version} after the upload; re-run the job to verify it")
    return state


# --- Hangar -----------------------------------------------------------------------------

def hangar_project(api, slug, dest):
    """The project's numeric ID. It must be public and, for Folia, carry the Folia tag."""
    details = api.public_project(slug)
    namespace = details.get("namespace") if isinstance(details, dict) else None
    project_id = details.get("id") if isinstance(details, dict) else None
    if (not isinstance(namespace, dict) or namespace.get("slug") != slug or details.get("visibility") != "public"
            or not isinstance(project_id, int) or isinstance(project_id, bool)):
        raise DistributorError(f"Hangar project {slug} is missing or not public; set HANGAR_PROJECT to the "
                               "project's slug and make the project public before releasing to it")
    tags = (details.get("settings") or {}).get("tags") or []
    if dest["supports_folia"] and HANGAR_FOLIA_TAG not in tags:
        raise DistributorError(f"Hangar project {slug} does not carry the Supports Folia tag, but every "
                               "release is tested on Folia; enable it in the project settings")
    return project_id


def hangar_payload(manifest, dest, directory):
    return {
        "version": manifest["version"],
        "pluginDependencies": {},
        "platformDependencies": dest["platforms"],
        "description": notes(directory),
        "files": [{"platforms": sorted(dest["platforms"])}],
        "channel": dest["channel"],
    }


def hangar_state(api, manifest, dest, slug, directory):
    """'absent', or 'complete' once the published version is proven to be the candidate."""
    version = manifest["version"]
    jar = candidate.jar_name(version)
    digest = manifest["files"][jar]
    # Authenticated, so a version only its members can see still counts as present.
    published = api.version(slug, version)
    if published is None:
        return "absent"
    expected = hangar_payload(manifest, dest, directory)
    problems = []
    if published.get("name") != version:
        problems.append("name")
    if (published.get("channel") or {}).get("name") != dest["channel"]:
        problems.append("channel")
    if published.get("visibility") != "public":
        problems.append(f"visibility {published.get('visibility')}")
    if not same_text(published.get("description"), expected["description"]):
        problems.append("description")
    platforms = published.get("platformDependencies") or {}
    if {k: sorted(v or []) for k, v in platforms.items()} != {k: sorted(v) for k, v in dest["platforms"].items()}:
        problems.append("platform versions")
    downloads = published.get("downloads") or {}
    if sorted(downloads) != sorted(dest["platforms"]):
        problems.append("platforms")
    for platform, download in sorted(downloads.items()):
        download = download or {}
        info = download.get("fileInfo") or {}
        if info.get("name") != jar or str(info.get("sha256Hash", "")).lower() != digest or not download.get("downloadUrl"):
            problems.append(f"{platform} file")
    stop("Hangar", version, problems)

    if api.version(slug, version, auth=False) is None:
        raise DistributorError(f"Hangar version {version} is not visible signed out")
    for platform, download in sorted(downloads.items()):
        if sha256(api.download(download["downloadUrl"])) != digest:
            raise DistributorError(f"What Hangar serves for {version} on {platform} does not match the candidate's SHA-256")
        log(f"  hangar {version} {platform}: {jar} sha256 {digest} matches, signed out")
    return "complete"


def hangar_preflight(api, manifest, slug, directory):
    dest = destination(manifest, "hangar")
    project_id = hangar_project(api, slug, dest)
    if not api.can_create_versions(project_id):
        raise DistributorError(f"The HANGAR_API_TOKEN cannot create versions in Hangar project {slug}")
    return hangar_state(api, manifest, dest, slug, directory)


def hangar_publish(api, manifest, slug, directory):
    dest = destination(manifest, "hangar")
    hangar_project(api, slug, dest)
    state = hangar_state(api, manifest, dest, slug, directory)
    if state == "absent":
        version = manifest["version"]
        log(f"Uploading {version} to Hangar project {slug} on the {dest['channel']} channel")
        api.upload(slug, hangar_payload(manifest, dest, directory), Path(directory) / candidate.jar_name(version))
        state = hangar_state(api, manifest, dest, slug, directory)
        if state != "complete":
            raise DistributorError(f"Hangar does not show {version} after the upload; re-run the job to verify it")
    return state


# --- Commands ---------------------------------------------------------------------------

def refuse_ineligible(tag):
    """Raise unless the tag may reach a distributor. Development builds never may."""
    info = candidate.release(tag)
    if not candidate.distribution(tag):
        kind = "a development build" if info["version"].startswith("0.") else f"a {info['channel']} build"
        raise DistributorError(f"{tag} is {kind}; it is published to GitHub only, never to Modrinth or Hangar")
    return info


def remote_tag_commit(tag, remote="origin"):
    """The commit the tag names on the remote now, peeling an annotated tag."""
    output = subprocess.run(("git", "ls-remote", remote, f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"),
                            capture_output=True, text=True, check=True).stdout.splitlines()
    direct = [line.split()[0] for line in output if line.endswith(f"\trefs/tags/{tag}")]
    peeled = [line.split()[0] for line in output if line.endswith(f"\trefs/tags/{tag}^{{}}")]
    if len(direct) != 1 or len(peeled) > 1:
        raise DistributorError(f"Tag {tag} is missing or ambiguous on {remote}")
    return peeled[0] if peeled else direct[0]


def configuration(name, environ=None):
    environ = os.environ if environ is None else environ
    names = CONFIG[name]
    missing = [key for key in names if not environ.get(key, "").strip()]
    if missing:
        raise DistributorError(f"Missing {', '.join(missing)} for {name}. Tokens are secrets of the release "
                               "environment and projects are repository variables; see RELEASE_PROCESS.md section 3.")
    return [environ[key].strip() for key in names]


def verified(args, tag_commit=remote_tag_commit):
    """The candidate manifest, re-verified with its evidence against this run and the remote tag."""
    refuse_ineligible(args.tag)
    manifest = candidate.check_evidence(args)
    if args.sha != manifest["source_sha"]:
        raise DistributorError(f"This run's commit {args.sha} is not the candidate's source {manifest['source_sha']}")
    current = tag_commit(manifest["tag"])
    if current != manifest["source_sha"]:
        raise DistributorError(f"Tag {manifest['tag']} now points at {current}, not the candidate's source "
                               f"{manifest['source_sha']}; a moved tag is never released")
    return manifest


def plan(args):
    distribute = bool(candidate.distribution(args.tag))
    if distribute:
        log(f"{args.tag} goes to GitHub, Modrinth and Hangar")
    else:
        try:
            refuse_ineligible(args.tag)
        except DistributorError as error:
            log(f"::notice::{error}")
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8", newline="\n") as stream:
            stream.write(f"distribute={'true' if distribute else 'false'}\n")
    return distribute


ACTIONS = {
    ("preflight", "modrinth"): (ModrinthApi, modrinth_preflight),
    ("preflight", "hangar"): (HangarApi, hangar_preflight),
    ("publish", "modrinth"): (ModrinthApi, modrinth_publish),
    ("publish", "hangar"): (HangarApi, hangar_publish),
}


def run(args, environ=None, tag_commit=remote_tag_commit, apis=None):
    refuse_ineligible(args.tag)
    token, project = configuration(args.destination, environ)
    manifest = verified(args, tag_commit)
    api_class, action = ACTIONS[(args.command, args.destination)]
    api = (apis or {}).get(args.destination) or api_class(token)
    state = action(api, manifest, project, args.directory)
    log(f"{args.destination} {args.command}: {manifest['version']} is {state}")
    return state


def parser():
    root = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("plan").add_argument("--tag", required=True)
    for name in ("preflight", "publish"):
        command = commands.add_parser(name)
        command.add_argument("destination", choices=sorted(CONFIG))
        for option in ("--directory", "--evidence", "--tag", "--sha", "--run-id", "--attempt", "--repository"):
            command.add_argument(option, required=True)
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    if args.command == "plan":
        plan(args)
    else:
        run(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError,
            urllib.error.URLError) as error:
        sys.exit(f"::error::{error}")
