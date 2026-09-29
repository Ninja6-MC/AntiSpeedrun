#!/usr/bin/env python3
"""Derive the CI snapshot version from the checked-out commit (N6-CI-09)."""

import re
import subprocess


# The release tag grammar in RELEASE_PROCESS.md: vX.Y.Z, optionally -alpha.N, -beta.N or -rc.N.
# A tag must match in full; a similarly named tag is not a version source.
RELEASE_TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.[0-9]+)?\Z")


def git(*args):
    return subprocess.check_output(("git", *args), text=True).strip()


def snapshot_version():
    # --merged HEAD keeps only tags reachable from the commit being built, which on a pull
    # request is the synthetic merge commit actions/checkout checked out.
    tags = [
        tag for tag in git("tag", "--merged", "HEAD", "--list", "v*").splitlines()
        if RELEASE_TAG.fullmatch(tag)
    ]
    if tags:
        # --long keeps the distance and hash even when HEAD is exactly on the tag.
        described = git("describe", "--tags", "--long", *(f"--match={tag}" for tag in tags))
        return described.removeprefix("v")
    return f"0.0.0-SNAPSHOT-g{git('rev-parse', '--short', 'HEAD')}"


if __name__ == "__main__":
    print(snapshot_version())
