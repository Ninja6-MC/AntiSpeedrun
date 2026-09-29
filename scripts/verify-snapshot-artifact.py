#!/usr/bin/env python3
"""Verify build/libs holds exactly the expected plugin JAR carrying the snapshot version."""

import sys
from pathlib import Path
from zipfile import BadZipFile, ZipFile


PLUGIN = "AntiSpeedrun"


def embedded_versions(text):
    values = []
    for line in text.splitlines():
        # Top-level key only; an indented `version:` would belong to a nested mapping.
        if line.startswith("version:"):
            value = line.removeprefix("version:").strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                value = value[1:-1]
            values.append(value)
    return values


def verify(directory: Path, version: str):
    expected = f"{PLUGIN}-{version}.jar"
    # The build produces no sources, javadoc or thin jars, so every JAR here is deployable
    # and anything other than the one expected name is unexpected.
    found = sorted(path.name for path in directory.glob("*.jar"))
    if found != [expected]:
        raise ValueError(f"Expected only {expected} in {directory}; found: {found}")

    try:
        with ZipFile(directory / expected) as archive:
            if archive.namelist().count("plugin.yml") != 1:
                raise ValueError(f"Expected one plugin.yml in {expected}")
            text = archive.read("plugin.yml").decode("utf-8")
    except BadZipFile as error:
        raise ValueError(f"{expected} is not a readable JAR: {error}") from error

    values = embedded_versions(text)
    if values != [version]:
        raise ValueError(f"Expected plugin.yml version {version!r} in {expected}; found: {values}")


if __name__ == "__main__":
    if len(sys.argv) != 2 or not sys.argv[1]:
        sys.exit("usage: verify-snapshot-artifact.py <version>")
    try:
        verify(Path("build/libs"), sys.argv[1])
    except (OSError, ValueError) as error:
        sys.exit(f"::error::{error}")
    print(f"Verified {PLUGIN}-{sys.argv[1]}.jar and its embedded plugin.yml version")
