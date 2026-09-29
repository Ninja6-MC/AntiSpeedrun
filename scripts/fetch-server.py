#!/usr/bin/env python3
"""Download a Paper or Folia server jar from PaperMC's Fill v3 API and verify its checksum.

Two modes:

  fetch-server.py --project paper --version 1.21.4 --build 232 --sha256 <hex> --out server.jar
      A pinned build. The pinned SHA-256 must match both what the API reports for that build
      and the bytes downloaded, so an upstream artifact that changes under the pin fails
      rather than being booted.

  fetch-server.py --project folia --version latest --out server.jar
      The newest release version the project publishes (release candidates and pre-releases
      skipped), and its newest build, preferring the STABLE channel when one exists. Folia
      often has no STABLE build for its newest version, so the fallback is common there.

When GITHUB_OUTPUT is set, the resolved minecraft version, build, channel, minimum Java
version and SHA-256 are appended to it.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request

API = "https://fill.papermc.io/v3/projects"
# PaperMC's CDN rejects default library user agents with HTTP 403, so every request,
# including the jar download, carries a descriptive one.
USER_AGENT = "AntiSpeedrun-CI/1.0 (+https://github.com/Ninja6-MC/AntiSpeedrun)"
RELEASE_VERSION = re.compile(r"^[0-9]+(\.[0-9]+)*$")


def fail(message):
    print(f"::error::{message}")
    raise SystemExit(1)


def open_url(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    return urllib.request.urlopen(request, timeout=60)


def get_json(url):
    with open_url(url) as response:
        return json.load(response)


def version_key(version):
    return tuple(int(part) for part in version.split("."))


def release_versions(project_document):
    """Every release version in a /projects/<name> document, newest first."""
    versions = [
        version
        for family in project_document.get("versions", {}).values()
        for version in family
        if RELEASE_VERSION.match(version)
    ]
    return sorted(set(versions), key=version_key, reverse=True)


def select_build(builds):
    """The newest STABLE build, or the newest build of any channel when none is STABLE."""
    if not builds:
        return None
    stable = [build for build in builds if build.get("channel") == "STABLE"]
    return max(stable or builds, key=lambda build: build["id"])


def server_download(build):
    try:
        return build["downloads"]["server:default"]
    except KeyError:
        fail(f"Build {build.get('id')} has no server:default download.")


def minimum_java(project, version):
    document = get_json(f"{API}/{project}/versions/{version}")
    try:
        return int(document["version"]["java"]["version"]["minimum"])
    except (KeyError, TypeError, ValueError):
        fail(f"{project} {version} does not state a minimum Java version.")


def resolve_latest(project):
    for version in release_versions(get_json(f"{API}/{project}")):
        build = select_build(get_json(f"{API}/{project}/versions/{version}/builds"))
        if build is not None:
            return version, build
        print(f"{project} {version} has no builds yet; trying the next version.")
    fail(f"No {project} release version has a published build.")


def resolve_pinned(project, version, build_id, pinned_sha256):
    build = get_json(f"{API}/{project}/versions/{version}/builds/{build_id}")
    published = server_download(build)["checksums"]["sha256"]
    if published.lower() != pinned_sha256.lower():
        fail(f"{project} {version} build {build_id} is published with SHA-256 {published}, "
             f"not the pinned {pinned_sha256}. Upstream changed a pinned artifact.")
    return build


def download(url, destination, expected_sha256):
    digest = hashlib.sha256()
    with open_url(url) as response, open(destination, "wb") as jar:
        for chunk in iter(lambda: response.read(1 << 20), b""):
            digest.update(chunk)
            jar.write(chunk)
    actual = digest.hexdigest()
    if actual != expected_sha256.lower():
        os.remove(destination)
        fail(f"Checksum mismatch for {destination}: expected {expected_sha256}, got {actual}.")
    return actual


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True, choices=("paper", "folia"))
    parser.add_argument("--version", required=True, help="a Minecraft version, or 'latest'")
    parser.add_argument("--build", type=int, help="pinned build number")
    parser.add_argument("--sha256", help="pinned SHA-256 of the server jar")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    if args.version == "latest":
        if args.build is not None or args.sha256:
            parser.error("--build and --sha256 pin a specific version; they do not apply to 'latest'")
        version, build = resolve_latest(args.project)
    else:
        if args.build is None or not args.sha256:
            parser.error("a specific version needs both --build and --sha256")
        version = args.version
        build = resolve_pinned(args.project, version, args.build, args.sha256)

    artifact = server_download(build)
    java = minimum_java(args.project, version)
    print(f"Using {args.project} {version} build {build['id']} ({build.get('channel')}), "
          f"Java {java} or newer.")
    sha256 = download(artifact["url"], args.out, artifact["checksums"]["sha256"])
    print(f"Checksum verified: {sha256}")

    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as handle:
            handle.write(f"minecraft={version}\nbuild={build['id']}\n"
                         f"channel={build.get('channel')}\njava={java}\nsha256={sha256}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
