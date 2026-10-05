#!/usr/bin/env python3
"""Seal, verify and attest an AntiSpeedrun release candidate (N6-REL-03).

  release-candidate.py tag      --tag vX.Y.Z
  release-candidate.py create   --directory candidate --jar-directory build/libs COMMON
  release-candidate.py verify   --directory candidate COMMON
  release-candidate.py receipt  --directory candidate COMMON --leg paper-1.21.4 --output r.json
  release-candidate.py evidence --directory candidate COMMON --receipts dir --output e.json
  release-candidate.py check-evidence --directory candidate COMMON --evidence e.json

COMMON is --tag, --sha (source commit), --run-id, --attempt (the candidate job's attempt) and
--repository. Every command re-derives the version and channel from the tag rather than
trusting the manifest, and fails with a non-zero exit on the first mismatch.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
from pathlib import Path
from zipfile import BadZipFile, ZipFile

PLUGIN = "AntiSpeedrun"
MAIN = "com.ninja6.antispeedrun.AntiSpeedrunPlugin"
# Resources the plugin loads from its own JAR at runtime; a JAR without them boots broken.
RESOURCES = ("plugin.yml", "config.yml")
SCHEMA = 1

# The release tag grammar in RELEASE_PROCESS.md, with SemVer's rule against leading zeros.
NUMBER = r"(?:0|[1-9][0-9]*)"
TAG = re.compile(rf"v({NUMBER})\.({NUMBER})\.({NUMBER})(?:-(alpha|beta|rc)\.({NUMBER}))?")

# Every server the candidate JAR must boot on before it can be published: the same pinned
# builds as the Server Boot matrix in ci.yml and the Candidate Server Boot matrix in
# release.yml. scripts/tests/test_release_candidate.py fails if the three drift apart.
SERVER_LEGS = (
    "paper-1.21.4",
    "folia-1.21.4",
    "paper-1.21.11",
    "folia-1.21.11",
    "paper-26.2",
    "folia-26.2",
)


class CandidateError(ValueError):
    pass


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def release(tag):
    """Version, channel and GitHub flags for a tag, per RELEASE_PROCESS.md section 2."""
    match = TAG.fullmatch(tag or "")
    if not match:
        raise CandidateError(f"{tag!r} is not a release tag (vX.Y.Z or vX.Y.Z-alpha|beta|rc.N)")
    major, suffix = int(match.group(1)), match.group(4)
    if major == 0:
        # Every 0.y.z build is a development build, suffixed or not: never stable, never Latest.
        channel = "development"
    else:
        channel = suffix or "stable"
    return {
        "tag": tag,
        "version": tag[1:],
        "channel": channel,
        "prerelease": channel != "stable",
        "make_latest": channel == "stable",
    }


def jar_name(version):
    return f"{PLUGIN}-{version}.jar"


def release_files(version):
    jar = jar_name(version)
    return [jar, f"{jar}.sha256", "release-notes.md"]


def published_assets(version):
    """The files a GitHub release carries. release-notes.md is its body, not an asset."""
    return release_files(version)[:2]


# Release channel -> (Modrinth version type, Hangar channel), per RELEASE_PROCESS.md section 2.
# Nothing else reaches a distributor: not a development build (major version 0, whatever its
# suffix, whose channel is "development") and not a release candidate.
DISTRIBUTOR_CHANNELS = {
    "alpha": ("alpha", "Alpha"),
    "beta": ("beta", "Beta"),
    "stable": ("release", "Release"),
}

# Modrinth loader and Hangar platform for each server platform in SERVER_LEGS. Folia is no
# Hangar platform: a Folia plugin is a Paper plugin whose Hangar project carries the
# "Supports Folia" tag, which scripts/release-distributors.py checks before publishing.
MODRINTH_LOADERS = {"paper": "paper", "folia": "folia"}
HANGAR_PLATFORMS = {"paper": "PAPER"}


def _version_key(version):
    return tuple(int(part) for part in version.split("."))


def tested_platforms():
    """Server platforms the release boots on, from SERVER_LEGS alone."""
    return sorted({leg.split("-", 1)[0] for leg in SERVER_LEGS})


def tested_minecraft_versions():
    """Minecraft versions the release boots on, from SERVER_LEGS alone, oldest first."""
    return sorted({leg.split("-", 1)[1] for leg in SERVER_LEGS}, key=_version_key)


def distribution(tag):
    """Modrinth and Hangar destinations for a tag, or [] when it must never reach them.

    Every declared platform and Minecraft version is derived from SERVER_LEGS, the servers
    the candidate itself boots on, so a listing cannot claim more than was tested.
    """
    info = release(tag)
    if info["version"].split(".", 1)[0] == "0" or info["channel"] not in DISTRIBUTOR_CHANNELS:
        return []
    version_type, channel = DISTRIBUTOR_CHANNELS[info["channel"]]
    platforms = tested_platforms()
    versions = tested_minecraft_versions()
    unknown = [p for p in platforms if p not in MODRINTH_LOADERS]
    if unknown:
        raise CandidateError(f"No Modrinth loader is defined for tested platform(s) {unknown}")
    if not any(p in HANGAR_PLATFORMS for p in platforms):
        raise CandidateError(f"No tested platform in {platforms} is a Hangar platform")
    return [
        {"name": "modrinth", "version_type": version_type,
         "loaders": sorted(MODRINTH_LOADERS[p] for p in platforms), "game_versions": versions},
        {"name": "hangar", "channel": channel,
         "platforms": {HANGAR_PLATFORMS[p]: versions for p in platforms if p in HANGAR_PLATFORMS},
         "supports_folia": "folia" in platforms},
    ]


def destinations(repository, tag):
    return [{"name": "github", "repository": repository, "release": tag}] + distribution(tag)


def changelog_section(text, heading):
    """Body of the `## [heading]` section, or None when there is no such section."""
    lines = text.splitlines()
    wanted = f"## [{heading}]"
    for index, line in enumerate(lines):
        if line == wanted or line.startswith(wanted + " "):
            body = []
            for following in lines[index + 1:]:
                if following.startswith("## ") or re.match(r"\[[^\]]+\]: ", following):
                    break
                body.append(following)
            return "\n".join(body).strip("\n")
    return None


def release_notes(text, info):
    """The changelog section a release publishes. Only a stable release insists on its own."""
    version = info["version"]
    exact = changelog_section(text, version)
    if exact and exact.strip():
        return exact + "\n"
    if not info["prerelease"]:
        raise CandidateError(f"CHANGELOG.md has no section for {version}; a stable release needs one")
    base = version.split("-", 1)[0]
    for heading in (base, "Unreleased"):
        section = changelog_section(text, heading)
        if section and section.strip():
            return section + "\n"
    return "No changelog section was written for this pre-release.\n"


def top_level(text, key):
    values = []
    for line in text.splitlines():
        # Top-level key only; an indented key would belong to a nested mapping.
        if line.startswith(f"{key}:"):
            value = line[len(key) + 1:].split(" #", 1)[0].strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                value = value[1:-1]
            values.append(value)
    return values


def inspect_jar(path, version):
    """Check the packaged descriptor and contents, not what the source tree says they should be."""
    path = Path(path)
    try:
        with ZipFile(path) as archive:
            names = archive.namelist()
            for resource in RESOURCES:
                if names.count(resource) != 1:
                    raise CandidateError(f"Expected exactly one {resource} in {path.name}")
            main_class = MAIN.replace(".", "/") + ".class"
            if names.count(main_class) != 1:
                raise CandidateError(f"{path.name} does not contain {main_class}")
            if archive.testzip() is not None:
                raise CandidateError(f"{path.name} has a corrupt entry")
            descriptor = archive.read("plugin.yml").decode("utf-8")
    except BadZipFile as error:
        raise CandidateError(f"{path.name} is not a readable JAR: {error}") from error
    expected = {"name": PLUGIN, "version": version, "main": MAIN, "folia-supported": "true"}
    for key, value in expected.items():
        found = top_level(descriptor, key)
        if found != [value]:
            raise CandidateError(f"plugin.yml {key} in {path.name} is {found}, expected [{value!r}]")


def exact_files(directory, expected):
    actual = sorted(p.name for p in Path(directory).iterdir())
    if actual != sorted(expected):
        raise CandidateError(f"Candidate files differ: expected {sorted(expected)}, found {actual}")


def expected_manifest(args):
    info = release(args.tag)
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha or ""):
        raise CandidateError(f"{args.sha!r} is not a full commit SHA")
    if not re.fullmatch(r"[1-9][0-9]*", str(args.run_id)) or not re.fullmatch(r"[1-9][0-9]*", str(args.attempt)):
        raise CandidateError("Run ID and attempt must be positive integers")
    return {
        "schema": SCHEMA,
        "plugin": PLUGIN,
        "candidate_id": f"{args.run_id}-{args.attempt}-{args.sha[:12]}",
        "artifact": f"release-candidate-{args.run_id}-{args.attempt}",
        "repository": args.repository,
        "source_sha": args.sha,
        "tag": info["tag"],
        "version": info["version"],
        "channel": info["channel"],
        "prerelease": info["prerelease"],
        "make_latest": info["make_latest"],
        "run_id": str(args.run_id),
        "attempt": str(args.attempt),
        "destinations": destinations(args.repository, info["tag"]),
    }


def create(args):
    manifest = expected_manifest(args)
    version = manifest["version"]
    jar = jar_name(version)
    built = Path(args.jar_directory)
    # The build produces no sources, javadoc or thin jars, so anything but the one JAR is a surprise.
    found = sorted(p.name for p in built.glob("*.jar"))
    if found != [jar]:
        raise CandidateError(f"Expected only {jar} in {built}; found {found}")
    notes = release_notes(Path(args.changelog).read_text(encoding="utf-8"), release(args.tag))
    directory = Path(args.directory)
    directory.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(built / jar, directory / jar)
    (directory / f"{jar}.sha256").write_text(f"{digest(directory / jar)}  {jar}\n", encoding="utf-8", newline="\n")
    (directory / "release-notes.md").write_text(notes, encoding="utf-8", newline="\n")
    inspect_jar(directory / jar, version)
    names = release_files(version)
    exact_files(directory, names)
    manifest["files"] = {name: digest(directory / name) for name in names}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n",
                                             encoding="utf-8", newline="\n")
    return manifest


def verify(args):
    directory = Path(args.directory)
    try:
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CandidateError(f"Candidate manifest is missing or unreadable: {error}") from error
    if not isinstance(manifest, dict):
        raise CandidateError("Candidate manifest is not an object")
    for key, value in expected_manifest(args).items():
        if manifest.get(key) != value:
            raise CandidateError(f"Candidate {key} is {manifest.get(key)!r}, expected {value!r}")
    names = release_files(manifest["version"])
    files = manifest.get("files")
    if not isinstance(files, dict) or sorted(files) != sorted(names):
        raise CandidateError(f"Manifest file inventory differs from {sorted(names)}")
    exact_files(directory, names + ["manifest.json"])
    for name in names:
        if digest(directory / name) != files[name]:
            raise CandidateError(f"SHA-256 of {name} differs from the manifest")
    jar = jar_name(manifest["version"])
    if (directory / f"{jar}.sha256").read_text(encoding="utf-8") != f"{files[jar]}  {jar}\n":
        raise CandidateError(f"{jar}.sha256 does not identify the candidate JAR")
    inspect_jar(directory / jar, manifest["version"])
    return manifest


def binding(args, manifest):
    """What every receipt and the evidence record must agree on for this exact candidate."""
    return {
        "candidate_id": manifest["candidate_id"],
        "manifest_sha256": digest(Path(args.directory) / "manifest.json"),
        "files": manifest["files"],
        "source_sha": manifest["source_sha"],
        "tag": manifest["tag"],
        "result": "passed",
    }


def receipt(args):
    manifest = verify(args)
    if args.leg not in SERVER_LEGS:
        raise CandidateError(f"{args.leg!r} is not a release server leg")
    record = dict(binding(args, manifest), leg=args.leg)
    Path(args.output).write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")


def evidence(args):
    manifest = verify(args)
    expected = binding(args, manifest)
    legs = []
    for path in sorted(Path(args.receipts).glob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise CandidateError(f"Receipt {path.name} is unreadable: {error}") from error
        if not isinstance(record, dict):
            raise CandidateError(f"Receipt {path.name} is not an object")
        for key, value in expected.items():
            if record.get(key) != value:
                raise CandidateError(f"Receipt {path.name} has mismatched {key}")
        legs.append(record.get("leg"))
    if sorted(map(str, legs)) != sorted(SERVER_LEGS) or len(legs) != len(SERVER_LEGS):
        raise CandidateError(f"Server boot receipts {legs} do not cover {sorted(SERVER_LEGS)} exactly once")
    record = dict(expected, legs=sorted(legs),
                  verified_by={"run_id": os.environ.get("GITHUB_RUN_ID", ""),
                               "attempt": os.environ.get("GITHUB_RUN_ATTEMPT", "")})
    Path(args.output).write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")


def check_evidence(args):
    manifest = verify(args)
    try:
        record = json.loads(Path(args.evidence).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CandidateError(f"Test evidence is missing or unreadable: {error}") from error
    if not isinstance(record, dict):
        raise CandidateError("Test evidence is not an object")
    expected = dict(binding(args, manifest), legs=sorted(SERVER_LEGS))
    for key, value in expected.items():
        if record.get(key) != value:
            raise CandidateError(f"Test evidence has mismatched {key}")
    return manifest


def tag(args):
    info = release(args.tag)
    lines = [f"{key}={json.dumps(value) if isinstance(value, bool) else value}" for key, value in info.items()]
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8", newline="\n") as stream:
            stream.write("\n".join(lines) + "\n")
    print("\n".join(lines))


def parser():
    root = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("tag").add_argument("--tag", required=True)
    for name in ("create", "verify", "receipt", "evidence", "check-evidence"):
        command = commands.add_parser(name)
        for option in ("--directory", "--tag", "--sha", "--run-id", "--attempt", "--repository"):
            command.add_argument(option, required=True)
        if name == "create":
            command.add_argument("--jar-directory", required=True)
            command.add_argument("--changelog", default="CHANGELOG.md")
        if name == "receipt":
            command.add_argument("--leg", required=True)
        if name in ("receipt", "evidence"):
            command.add_argument("--output", required=True)
        if name == "evidence":
            command.add_argument("--receipts", required=True)
        if name == "check-evidence":
            command.add_argument("--evidence", required=True)
    return root


COMMANDS = {"tag": tag, "create": create, "verify": verify, "receipt": receipt,
            "evidence": evidence, "check-evidence": check_evidence}


def main(argv=None):
    args = parser().parse_args(argv)
    COMMANDS[args.command](args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, CandidateError, KeyError, json.JSONDecodeError) as error:
        sys.exit(f"::error::{error}")
