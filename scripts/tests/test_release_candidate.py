from pathlib import Path
import argparse
import importlib.util
import json
import re
import tempfile
import unittest
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release-candidate.py"
SPEC = importlib.util.spec_from_file_location("release_candidate", SCRIPT)
rc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rc)

SHA = "0123456789abcdef0123456789abcdef01234567"
PLUGIN_YML = """\
name: AntiSpeedrun
version: '{version}'
main: com.ninja6.antispeedrun.AntiSpeedrunPlugin
api-version: '1.21'
folia-supported: true
commands:
  progress:
    name: nested
    version: nested
"""
CHANGELOG = """\
# Changelog

## [Unreleased]

### Added
- Something unreleased.

## [1.0.0] - 2027-01-01

### Added
- The first stable release.

## [0.9.0]

[1.0.0]: https://example.invalid/1.0.0
"""


def write_jar(path, version, plugin_yml=None, extra=(), omit=()):
    with ZipFile(path, "w") as archive:
        entries = {
            "plugin.yml": plugin_yml if plugin_yml is not None else PLUGIN_YML.format(version=version),
            "config.yml": "enabled: true\n",
            "com/ninja6/antispeedrun/AntiSpeedrunPlugin.class": "class",
        }
        for name, data in entries.items():
            if name not in omit:
                archive.writestr(name, data)
        for name in extra:
            archive.writestr(name, "x")


def args(directory, tag="v1.0.0", **overrides):
    values = dict(directory=str(directory), tag=tag, sha=SHA, run_id="42", attempt="1",
                  repository="Ninja6-MC/AntiSpeedrun")
    values.update(overrides)
    return argparse.Namespace(**values)


class ReleaseTagTest(unittest.TestCase):
    def test_channels(self):
        cases = {
            "v1.0.0": ("stable", False, True),
            "v2.3.4": ("stable", False, True),
            "v1.0.0-alpha.1": ("alpha", True, False),
            "v1.0.0-beta.2": ("beta", True, False),
            "v1.0.0-rc.1": ("rc", True, False),
            "v0.1.0": ("development", True, False),
            "v0.2.0-beta.1": ("development", True, False),
        }
        for tag, (channel, prerelease, latest) in cases.items():
            with self.subTest(tag=tag):
                info = rc.release(tag)
                self.assertEqual(info["version"], tag[1:])
                self.assertEqual((info["channel"], info["prerelease"], info["make_latest"]),
                                 (channel, prerelease, latest))

    def test_rejects_other_tags(self):
        for tag in ("1.0.0", "v1.0", "v1.0.0-alpha", "v1.0.0-gamma.1", "v01.0.0", "v1.0.0-beta.01",
                    "v1.0.0-SNAPSHOT", "v1.0.0+build.1", "v1.0.0\n", "", None):
            with self.subTest(tag=tag):
                with self.assertRaises(rc.CandidateError):
                    rc.release(tag)


class DistributionTest(unittest.TestCase):
    def test_channel_mapping(self):
        for tag, (version_type, channel) in {"v1.0.0-alpha.1": ("alpha", "Alpha"), "v1.0.0-beta.3": ("beta", "Beta"),
                                             "v1.0.0": ("release", "Release"), "v2.4.1": ("release", "Release"),
                                             "v2.0.0-alpha.2": ("alpha", "Alpha")}.items():
            with self.subTest(tag=tag):
                modrinth, hangar = rc.distribution(tag)
                self.assertEqual((modrinth["name"], modrinth["version_type"]), ("modrinth", version_type))
                self.assertEqual((hangar["name"], hangar["channel"]), ("hangar", channel))

    def test_development_builds_and_release_candidates_never_reach_a_distributor(self):
        for tag in ("v0.1.0", "v0.2.0", "v0.2.0-alpha.1", "v0.4.0-beta.2", "v0.9.0-rc.1", "v1.0.0-rc.1"):
            with self.subTest(tag=tag):
                self.assertEqual(rc.distribution(tag), [])
                self.assertEqual([d["name"] for d in rc.destinations("Ninja6-MC/AntiSpeedrun", tag)], ["github"])

    def test_declared_platforms_and_versions_come_from_the_tested_servers(self):
        modrinth, hangar = rc.distribution("v1.0.0")
        self.assertEqual(modrinth["loaders"], ["folia", "paper"])
        self.assertEqual(modrinth["game_versions"], ["1.21.4", "1.21.11", "26.2"])
        self.assertEqual(hangar["platforms"], {"PAPER": ["1.21.4", "1.21.11", "26.2"]})
        self.assertTrue(hangar["supports_folia"])


class ReleaseNotesTest(unittest.TestCase):
    def test_stable_uses_its_own_section_and_stops_at_the_next(self):
        notes = rc.release_notes(CHANGELOG, rc.release("v1.0.0"))
        self.assertEqual(notes, "### Added\n- The first stable release.\n")

    def test_stable_without_a_section_fails(self):
        with self.assertRaises(rc.CandidateError):
            rc.release_notes(CHANGELOG, rc.release("v1.1.0"))

    def test_stable_with_an_empty_section_fails(self):
        with self.assertRaises(rc.CandidateError):
            rc.release_notes(CHANGELOG, dict(rc.release("v1.0.0"), version="0.9.0"))

    def test_prerelease_falls_back_to_base_then_unreleased(self):
        self.assertIn("first stable", rc.release_notes(CHANGELOG, rc.release("v1.0.0-rc.1")))
        self.assertIn("unreleased", rc.release_notes(CHANGELOG, rc.release("v0.1.0")))
        self.assertIn("No changelog section", rc.release_notes("# Changelog\n", rc.release("v0.1.0")))


class CandidateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.libs = self.root / "libs"
        self.libs.mkdir()
        self.changelog = self.root / "CHANGELOG.md"
        self.changelog.write_text(CHANGELOG, encoding="utf-8")
        self.directory = self.root / "candidate"

    def create(self, tag="v1.0.0", **overrides):
        version = tag[1:]
        if not list(self.libs.glob("*.jar")):
            write_jar(self.libs / f"AntiSpeedrun-{version}.jar", version, **overrides)
        return rc.create(argparse.Namespace(**vars(args(self.directory, tag)), jar_directory=str(self.libs),
                                            changelog=str(self.changelog)))

    def test_create_then_verify(self):
        manifest = self.create()
        self.assertEqual(manifest["candidate_id"], f"42-1-{SHA[:12]}")
        self.assertEqual(manifest["channel"], "stable")
        self.assertEqual(manifest["destinations"],
                         [{"name": "github", "repository": "Ninja6-MC/AntiSpeedrun", "release": "v1.0.0"}]
                         + rc.distribution("v1.0.0"))
        self.assertEqual([d["name"] for d in manifest["destinations"]], ["github", "modrinth", "hangar"])
        self.assertEqual(sorted(manifest["files"]),
                         ["AntiSpeedrun-1.0.0.jar", "AntiSpeedrun-1.0.0.jar.sha256", "release-notes.md"])
        self.assertEqual(rc.verify(args(self.directory)), manifest)

    def test_create_refuses_extra_or_missing_jars(self):
        write_jar(self.libs / "AntiSpeedrun-1.0.0.jar", "1.0.0")
        write_jar(self.libs / "AntiSpeedrun-1.0.0-sources.jar", "1.0.0")
        with self.assertRaises(rc.CandidateError):
            self.create()

    def test_create_refuses_wrong_embedded_version(self):
        write_jar(self.libs / "AntiSpeedrun-1.0.0.jar", "1.0.0", plugin_yml=PLUGIN_YML.format(version="0.1.0-SNAPSHOT"))
        with self.assertRaisesRegex(rc.CandidateError, "version"):
            self.create()

    def test_inspection_failures(self):
        cases = {
            "no config": dict(omit=("config.yml",)),
            "no main class": dict(omit=("com/ninja6/antispeedrun/AntiSpeedrunPlugin.class",)),
            "not folia": dict(plugin_yml=PLUGIN_YML.format(version="1.0.0").replace("folia-supported: true", "")),
            "wrong main": dict(plugin_yml=PLUGIN_YML.format(version="1.0.0").replace("AntiSpeedrunPlugin", "Other")),
            "wrong name": dict(plugin_yml=PLUGIN_YML.format(version="1.0.0").replace("name: AntiSpeedrun", "name: X")),
            "two versions": dict(plugin_yml=PLUGIN_YML.format(version="1.0.0") + "version: '1.0.0'\n"),
        }
        write_jar(self.root / "good.jar", "1.0.0")
        rc.inspect_jar(self.root / "good.jar", "1.0.0")
        for label, overrides in cases.items():
            with self.subTest(label):
                path = self.root / f"{label}.jar"
                write_jar(path, "1.0.0", **overrides)
                with self.assertRaises(rc.CandidateError):
                    rc.inspect_jar(path, "1.0.0")

    def test_verify_rejects_tampering(self):
        self.create()
        jar = self.directory / "AntiSpeedrun-1.0.0.jar"
        original = jar.read_bytes()
        jar.write_bytes(original + b"\0")
        with self.assertRaisesRegex(rc.CandidateError, "SHA-256"):
            rc.verify(args(self.directory))
        jar.write_bytes(original)
        (self.directory / "extra.txt").write_text("x")
        with self.assertRaisesRegex(rc.CandidateError, "files differ"):
            rc.verify(args(self.directory))

    def test_verify_rejects_a_different_identity(self):
        self.create()
        for override in (dict(tag="v1.0.1"), dict(tag="v1.0.0-rc.1"), dict(sha="f" * 40),
                         dict(run_id="43"), dict(attempt="2"), dict(repository="someone/else")):
            with self.subTest(override=override):
                with self.assertRaises(rc.CandidateError):
                    rc.verify(args(self.directory, **override))

    def test_evidence_needs_every_leg_passing_for_this_candidate(self):
        self.create()
        receipts = self.root / "receipts"
        receipts.mkdir()
        for leg in rc.SERVER_LEGS[:-1]:
            rc.receipt(argparse.Namespace(**vars(args(self.directory)), leg=leg, output=str(receipts / f"{leg}.json")))
        evidence = self.root / "evidence.json"
        run = argparse.Namespace(**vars(args(self.directory)), receipts=str(receipts), output=str(evidence))
        with self.assertRaisesRegex(rc.CandidateError, "do not cover"):
            rc.evidence(run)
        leg = rc.SERVER_LEGS[-1]
        rc.receipt(argparse.Namespace(**vars(args(self.directory)), leg=leg, output=str(receipts / f"{leg}.json")))
        rc.evidence(run)
        check = argparse.Namespace(**vars(args(self.directory)), evidence=str(evidence))
        self.assertEqual(rc.check_evidence(check)["tag"], "v1.0.0")

        record = json.loads(evidence.read_text())
        record["files"]["AntiSpeedrun-1.0.0.jar"] = "0" * 64
        evidence.write_text(json.dumps(record))
        with self.assertRaisesRegex(rc.CandidateError, "files"):
            rc.check_evidence(check)

    def test_receipt_from_another_candidate_is_rejected(self):
        self.create()
        receipts = self.root / "receipts"
        receipts.mkdir()
        for leg in rc.SERVER_LEGS:
            rc.receipt(argparse.Namespace(**vars(args(self.directory)), leg=leg, output=str(receipts / f"{leg}.json")))
        stale = json.loads((receipts / f"{rc.SERVER_LEGS[0]}.json").read_text())
        stale["candidate_id"] = "41-1-" + SHA[:12]
        (receipts / f"{rc.SERVER_LEGS[0]}.json").write_text(json.dumps(stale))
        with self.assertRaisesRegex(rc.CandidateError, "candidate_id"):
            rc.evidence(argparse.Namespace(**vars(args(self.directory)), receipts=str(receipts),
                                           output=str(self.root / "e.json")))

    def test_unknown_leg_is_rejected(self):
        self.create()
        with self.assertRaises(rc.CandidateError):
            rc.receipt(argparse.Namespace(**vars(args(self.directory)), leg="spigot-1.21.4",
                                          output=str(self.root / "r.json")))


MATRIX_LINE = re.compile(r"^\s*- \{ platform: (\w+), minecraft: '([^']+)', build: (\d+), java: '(\d+)', sha256: (\w+) \}$",
                         re.M)


def matrix(workflow, job_header):
    text = (ROOT / ".github" / "workflows" / workflow).read_text(encoding="utf-8")
    start = text.index(job_header)
    following = re.search(r"^  [\w-]+:$", text[start + len(job_header):], re.M)
    section = text[start:start + len(job_header) + (following.start() if following else len(text))]
    return MATRIX_LINE.findall(section)


class ServerMatrixTest(unittest.TestCase):
    def test_release_boots_exactly_the_ci_servers(self):
        ci = matrix("ci.yml", "\n  server-smoke:\n")
        release = matrix("release.yml", "\n  verify:\n")
        self.assertEqual(len(ci), len(rc.SERVER_LEGS))
        self.assertEqual(release, ci)
        self.assertEqual(sorted(f"{platform}-{minecraft}" for platform, minecraft, *_ in release),
                         sorted(rc.SERVER_LEGS))

    def test_distributor_listings_declare_exactly_the_booted_servers(self):
        ci = matrix("ci.yml", "\n  server-smoke:\n")
        platforms = sorted({platform for platform, *_ in ci})
        versions = sorted({minecraft for _, minecraft, *_ in ci})
        modrinth, hangar = rc.distribution("v1.0.0")
        self.assertEqual(sorted(modrinth["loaders"]), [rc.MODRINTH_LOADERS[p] for p in platforms])
        self.assertEqual(sorted(modrinth["game_versions"]), versions)
        for platform in platforms:
            if platform in rc.HANGAR_PLATFORMS:
                self.assertEqual(sorted(hangar["platforms"][rc.HANGAR_PLATFORMS[platform]]), versions)
        self.assertEqual(hangar["supports_folia"], "folia" in platforms)


if __name__ == "__main__":
    unittest.main()
