from pathlib import Path
import argparse
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest

from test_release_candidate import write_jar


SCRIPT = Path(__file__).resolve().parents[1] / "release-distributors.py"
SPEC = importlib.util.spec_from_file_location("release_distributors", SCRIPT)
rd = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rd)
rc = rd.candidate

SHA = "0123456789abcdef0123456789abcdef01234567"
NOTES = "### Added\n- Things.\n"
VERSIONS = ["1.21.4", "1.21.11", "26.2"]
ENV = {"MODRINTH_TOKEN": "m-token", "MODRINTH_PROJECT": "antispeedrun",
       "HANGAR_API_TOKEN": "h-token", "HANGAR_PROJECT": "AntiSpeedrun"}


def quiet(function, *args, **kwargs):
    with contextlib.redirect_stdout(io.StringIO()):
        return function(*args, **kwargs)


class FakeModrinth:
    """An in-memory Modrinth API that records every write."""

    def __init__(self):
        self.project = {"id": "AbCd1234", "slug": "antispeedrun"}
        self.public = True
        self.user_id = "user1"
        self.members_list = [{"user": {"id": "user1"}, "accepted": True, "permissions": 0b1111}]
        self.versions_by_id = {}
        self.blobs = {}
        self.uploads = []
        self.lose_response = False
        self.hide_from_listing = False
        self.serve = None

    def public_project(self, project):
        return copy.deepcopy(self.project) if self.public and project in self.project.values() else None

    def user(self):
        return {"id": self.user_id}

    def members(self, project_id):
        return copy.deepcopy(self.members_list)

    def versions(self, project_id):
        if self.hide_from_listing:
            return []
        return [copy.deepcopy(v) for v in self.versions_by_id.values() if v["project_id"] == project_id]

    def version_by_file(self, sha512):
        for version in self.versions_by_id.values():
            if any(f["hashes"]["sha512"] == sha512 for f in version["files"]):
                return copy.deepcopy(version)
        return None

    def public_version(self, version_id):
        version = self.versions_by_id.get(version_id)
        return copy.deepcopy(version) if version and version["status"] == "listed" else None

    def add(self, data, jar_bytes, filename):
        url = f"https://cdn.modrinth.invalid/{len(self.versions_by_id)}/{filename}"
        version = {key: data[key] for key in ("name", "version_number", "changelog", "game_versions",
                                              "version_type", "loaders", "status", "project_id")}
        version["id"] = f"v{len(self.versions_by_id)}"
        version["files"] = [{"filename": filename, "url": url,
                             "hashes": {"sha512": hashlib.sha512(jar_bytes).hexdigest()}}]
        self.versions_by_id[version["id"]] = version
        self.blobs[url] = jar_bytes
        return version

    def create_version(self, data, jar):
        self.uploads.append((copy.deepcopy(data), jar.read_bytes()))
        version = self.add(data, jar.read_bytes(), jar.name)
        if self.lose_response:
            raise rd.DistributorError("connection reset")
        return copy.deepcopy(version)

    def download(self, url):
        return self.serve if self.serve is not None else self.blobs[url]


class FakeHangar:
    """An in-memory Hangar API that records every write."""

    def __init__(self):
        self.project = {"id": 42, "namespace": {"owner": "Ninja6-MC", "slug": "AntiSpeedrun"},
                        "visibility": "public", "settings": {"tags": ["SUPPORTS_FOLIA"]}}
        self.allowed = True
        self.versions_by_name = {}
        self.blobs = {}
        self.uploads = []
        self.serve = None

    def public_project(self, slug):
        return copy.deepcopy(self.project) if slug == self.project["namespace"]["slug"] else None

    def version(self, slug, name, auth=True):
        version = self.versions_by_name.get(name)
        if version is None or (not auth and version["visibility"] != "public"):
            return None
        return copy.deepcopy(version)

    def can_create_versions(self, project_id):
        return self.allowed

    def add(self, data, jar_bytes, filename):
        url = f"https://hangarcdn.invalid/{data['version']}/{filename}"
        self.blobs[url] = jar_bytes
        file_info = {"name": filename, "sizeBytes": len(jar_bytes),
                     "sha256Hash": hashlib.sha256(jar_bytes).hexdigest()}
        version = {"name": data["version"], "visibility": "public", "description": data["description"],
                   "channel": {"name": data["channel"]},
                   "platformDependencies": copy.deepcopy(data["platformDependencies"]),
                   "downloads": {platform: {"fileInfo": dict(file_info), "downloadUrl": url}
                                 for platform in data["files"][0]["platforms"]}}
        self.versions_by_name[data["version"]] = version
        return version

    def upload(self, slug, data, jar):
        self.uploads.append((copy.deepcopy(data), jar.read_bytes()))
        self.add(data, jar.read_bytes(), jar.name)
        return {"url": "https://hangar.invalid"}

    def download(self, url):
        return self.serve if self.serve is not None else self.blobs[url]


class Candidate(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def candidate(self, tag="v1.0.0-alpha.1"):
        version = tag[1:]
        libs = self.root / "libs"
        libs.mkdir()
        write_jar(libs / f"AntiSpeedrun-{version}.jar", version)
        changelog = self.root / "CHANGELOG.md"
        changelog.write_text(f"## [Unreleased]\n\n## [{version}]\n\n{NOTES}", encoding="utf-8")
        self.directory = self.root / "candidate"
        common = dict(directory=str(self.directory), tag=tag, sha=SHA, run_id="7", attempt="1",
                      repository="Ninja6-MC/AntiSpeedrun")
        rc.create(argparse.Namespace(**common, jar_directory=str(libs), changelog=str(changelog)))
        receipts = self.root / "receipts"
        receipts.mkdir()
        for leg in rc.SERVER_LEGS:
            rc.receipt(argparse.Namespace(**common, leg=leg, output=str(receipts / f"{leg}.json")))
        evidence = self.root / "evidence.json"
        rc.evidence(argparse.Namespace(**common, receipts=str(receipts), output=str(evidence)))
        self.args = dict(common, evidence=str(evidence))
        self.manifest = rc.check_evidence(argparse.Namespace(**self.args))
        self.jar = self.directory / f"AntiSpeedrun-{version}.jar"
        return self.manifest

    def run_command(self, command, destination, environ=ENV, tag_commit=lambda tag: SHA, apis=None, **overrides):
        args = argparse.Namespace(**dict(self.args, **overrides), command=command, destination=destination)
        return quiet(rd.run, args, environ=environ, tag_commit=tag_commit, apis=apis)


class PlanTest(unittest.TestCase):
    def test_only_alpha_beta_and_stable_from_major_one_are_distributed(self):
        cases = {
            "v0.1.0": False, "v0.2.0": False, "v0.2.0-alpha.1": False, "v0.9.9-beta.3": False,
            "v0.3.0-rc.1": False, "v1.0.0-rc.1": False,
            "v1.0.0-alpha.1": True, "v1.0.0-beta.2": True, "v1.0.0": True, "v2.1.3": True,
        }
        for tag, expected in cases.items():
            with self.subTest(tag=tag):
                self.assertIs(quiet(rd.plan, argparse.Namespace(tag=tag)), expected)

    def test_development_builds_are_refused_by_name(self):
        for tag in ("v0.1.0", "v0.2.0-beta.1"):
            with self.subTest(tag=tag):
                with self.assertRaisesRegex(rd.DistributorError, "development build.*never to Modrinth or Hangar"):
                    rd.refuse_ineligible(tag)
        with self.assertRaisesRegex(rd.DistributorError, "rc build"):
            rd.refuse_ineligible("v1.0.0-rc.1")


class CommandTest(Candidate):
    def test_development_tag_is_refused_before_configuration_or_candidate(self):
        self.args = dict(directory="missing", evidence="missing", tag="v0.2.0", sha=SHA, run_id="7",
                         attempt="1", repository="Ninja6-MC/AntiSpeedrun")
        for command in ("preflight", "publish"):
            for destination in ("modrinth", "hangar"):
                with self.subTest(command=command, destination=destination):
                    with self.assertRaisesRegex(rd.DistributorError, "development build"):
                        self.run_command(command, destination, environ={})

    def test_missing_configuration_fails_closed(self):
        self.candidate()
        for destination, keys in rd.CONFIG.items():
            for key in keys:
                with self.subTest(destination=destination, key=key):
                    environ = dict(ENV, **{key: " "})
                    with self.assertRaisesRegex(rd.DistributorError, f"Missing {key}"):
                        self.run_command("publish", destination, environ=environ,
                                         apis={"modrinth": FakeModrinth(), "hangar": FakeHangar()})

    def test_moved_tag_or_other_commit_is_never_published(self):
        self.candidate()
        modrinth = FakeModrinth()
        with self.assertRaisesRegex(rd.DistributorError, "moved tag"):
            self.run_command("publish", "modrinth", tag_commit=lambda tag: "f" * 40, apis={"modrinth": modrinth})
        with self.assertRaises(ValueError):
            self.run_command("publish", "modrinth", sha="e" * 40, apis={"modrinth": modrinth})
        self.assertEqual(modrinth.uploads, [])

    def test_tampered_candidate_is_never_published(self):
        self.candidate()
        self.jar.write_bytes(self.jar.read_bytes() + b"\0")
        hangar = FakeHangar()
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.run_command("publish", "hangar", apis={"hangar": hangar})
        self.assertEqual(hangar.uploads, [])

    def test_both_destinations_publish_through_run(self):
        self.candidate("v1.0.0")
        modrinth, hangar = FakeModrinth(), FakeHangar()
        apis = {"modrinth": modrinth, "hangar": hangar}
        self.assertEqual(self.run_command("preflight", "modrinth", apis=apis), "absent")
        self.assertEqual(self.run_command("preflight", "hangar", apis=apis), "absent")
        self.assertEqual(self.run_command("publish", "modrinth", apis=apis), "complete")
        self.assertEqual(self.run_command("publish", "hangar", apis=apis), "complete")
        self.assertEqual(self.run_command("preflight", "modrinth", apis=apis), "complete")
        self.assertEqual(self.run_command("preflight", "hangar", apis=apis), "complete")
        self.assertEqual((len(modrinth.uploads), len(hangar.uploads)), (1, 1))


class ModrinthTest(Candidate):
    def publish(self, api):
        return quiet(rd.modrinth_publish, api, self.manifest, "antispeedrun", self.directory)

    def test_fresh_upload_carries_the_candidate_bytes_and_tag_metadata(self):
        for tag, version_type in (("v1.0.0-alpha.1", "alpha"), ("v1.0.0-beta.1", "beta"), ("v1.0.0", "release")):
            with self.subTest(tag=tag):
                self.setUp()
                self.candidate(tag)
                api = FakeModrinth()
                self.assertEqual(self.publish(api), "complete")
                [(data, sent)] = api.uploads
                self.assertEqual(sent, self.jar.read_bytes())
                self.assertEqual(data["version_type"], version_type)
                self.assertEqual(data["version_number"], tag[1:])
                self.assertEqual(data["loaders"], ["folia", "paper"])
                self.assertEqual(data["game_versions"], VERSIONS)
                self.assertEqual(data["changelog"], NOTES)
                self.assertEqual(data["project_id"], "AbCd1234")
                self.assertFalse(data["featured"])

    def test_retry_after_success_verifies_and_uploads_nothing(self):
        self.candidate()
        api = FakeModrinth()
        self.publish(api)
        self.assertEqual(self.publish(api), "complete")
        self.assertEqual(len(api.uploads), 1)

    def test_lost_upload_response_is_resumed_without_a_second_upload(self):
        self.candidate()
        api = FakeModrinth()
        api.lose_response = True
        with self.assertRaises(rd.DistributorError):
            self.publish(api)
        api.lose_response = False
        self.assertEqual(self.publish(api), "complete")
        self.assertEqual(len(api.uploads), 1)

    def test_candidate_bytes_hosted_but_unlisted_stop_the_run(self):
        self.candidate()
        api = FakeModrinth()
        self.publish(api)
        api.hide_from_listing = True
        with self.assertRaisesRegex(rd.DistributorError, "already on Modrinth"):
            self.publish(api)
        self.assertEqual(len(api.uploads), 1)

    def test_a_different_published_version_is_never_overwritten(self):
        cases = {
            "changelog": lambda v: v.update(changelog="Other notes"),
            "version_type": lambda v: v.update(version_type="release"),
            "game_versions": lambda v: v.update(game_versions=["1.21.4"]),
            "loaders": lambda v: v.update(loaders=["paper"]),
            "status": lambda v: v.update(status="draft"),
            "files": lambda v: v["files"][0]["hashes"].update(sha512="0" * 128),
        }
        for label, change in cases.items():
            with self.subTest(label):
                self.setUp()
                self.candidate()
                api = FakeModrinth()
                self.publish(api)
                change(next(iter(api.versions_by_id.values())))
                with self.assertRaisesRegex(rd.DistributorError, "not overwritten|identity|Modrinth"):
                    self.publish(api)
                self.assertEqual(len(api.uploads), 1)

    def test_a_foreign_version_with_the_same_number_is_never_overwritten(self):
        self.candidate()
        api = FakeModrinth()
        api.add(dict(name="AntiSpeedrun 1.0.0-alpha.1", version_number="1.0.0-alpha.1", changelog=NOTES,
                     game_versions=VERSIONS, version_type="alpha", loaders=["folia", "paper"], status="listed",
                     project_id="AbCd1234"), b"other bytes", self.jar.name)
        with self.assertRaisesRegex(rd.DistributorError, "files"):
            self.publish(api)
        self.assertEqual(api.uploads, [])

    def test_served_bytes_must_match_the_candidate(self):
        self.candidate()
        api = FakeModrinth()
        api.serve = b"tampered"
        with self.assertRaisesRegex(rd.DistributorError, "SHA-256"):
            self.publish(api)

    def test_preflight_requires_a_public_project_and_upload_permission(self):
        self.candidate()
        api = FakeModrinth()
        api.public = False
        with self.assertRaisesRegex(rd.DistributorError, "not visible signed out"):
            quiet(rd.modrinth_preflight, api, self.manifest, "antispeedrun", self.directory)
        for members in ([], [{"user": {"id": "user1"}, "accepted": False, "permissions": 1}],
                        [{"user": {"id": "user1"}, "accepted": True, "permissions": 0b10}],
                        [{"user": {"id": "someone"}, "accepted": True, "permissions": 1}]):
            with self.subTest(members=members):
                api = FakeModrinth()
                api.members_list = members
                with self.assertRaisesRegex(rd.DistributorError, "cannot upload"):
                    quiet(rd.modrinth_preflight, api, self.manifest, "antispeedrun", self.directory)
        self.assertEqual(quiet(rd.modrinth_preflight, FakeModrinth(), self.manifest, "AbCd1234", self.directory),
                         "absent")


class HangarTest(Candidate):
    def publish(self, api):
        return quiet(rd.hangar_publish, api, self.manifest, "AntiSpeedrun", self.directory)

    def test_fresh_upload_carries_the_candidate_bytes_and_tag_metadata(self):
        for tag, channel in (("v1.0.0-alpha.1", "Alpha"), ("v1.0.0-beta.1", "Beta"), ("v1.0.0", "Release")):
            with self.subTest(tag=tag):
                self.setUp()
                self.candidate(tag)
                api = FakeHangar()
                self.assertEqual(self.publish(api), "complete")
                [(data, sent)] = api.uploads
                self.assertEqual(sent, self.jar.read_bytes())
                self.assertEqual(data["channel"], channel)
                self.assertEqual(data["version"], tag[1:])
                self.assertEqual(data["platformDependencies"], {"PAPER": VERSIONS})
                self.assertEqual(data["files"], [{"platforms": ["PAPER"]}])
                self.assertEqual(data["description"], NOTES)

    def test_retry_after_success_verifies_and_uploads_nothing(self):
        self.candidate()
        api = FakeHangar()
        self.publish(api)
        self.assertEqual(self.publish(api), "complete")
        self.assertEqual(len(api.uploads), 1)

    def test_a_different_published_version_is_never_overwritten(self):
        cases = {
            "channel": lambda v: v.update(channel={"name": "Release"}),
            "description": lambda v: v.update(description="Other"),
            "platform versions": lambda v: v.update(platformDependencies={"PAPER": ["1.21.4"]}),
            "PAPER file": lambda v: v["downloads"]["PAPER"]["fileInfo"].update(sha256Hash="0" * 64),
            "visibility": lambda v: v.update(visibility="needsApproval"),
        }
        for label, change in cases.items():
            with self.subTest(label):
                self.setUp()
                self.candidate()
                api = FakeHangar()
                self.publish(api)
                change(api.versions_by_name["1.0.0-alpha.1"])
                with self.assertRaisesRegex(rd.DistributorError, label):
                    self.publish(api)
                self.assertEqual(len(api.uploads), 1)

    def test_served_bytes_must_match_the_candidate(self):
        self.candidate()
        api = FakeHangar()
        api.serve = b"tampered"
        with self.assertRaisesRegex(rd.DistributorError, "SHA-256"):
            self.publish(api)

    def test_preflight_requires_public_folia_project_and_permission(self):
        self.candidate()
        cases = {
            "not public": lambda api: api.project.update(visibility="new"),
            "Supports Folia": lambda api: api.project["settings"].update(tags=[]),
            "cannot create versions": lambda api: setattr(api, "allowed", False),
        }
        for message, change in cases.items():
            with self.subTest(message):
                api = FakeHangar()
                change(api)
                with self.assertRaisesRegex(rd.DistributorError, message):
                    quiet(rd.hangar_preflight, api, self.manifest, "AntiSpeedrun", self.directory)
                self.assertEqual(api.uploads, [])
        with self.assertRaisesRegex(rd.DistributorError, "missing or not public"):
            quiet(rd.hangar_preflight, FakeHangar(), self.manifest, "Other", self.directory)


class FakeHttp:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def request(self, method, url, headers=None, body=None, timeout=60):
        self.requests.append((method, url, headers or {}, body))
        return self.responses.pop(0)


class WireTest(Candidate):
    def test_modrinth_upload_sends_the_jar_bytes_unchanged(self):
        self.candidate()
        http = FakeHttp([(200, b'{"id": "v1"}')])
        rd.ModrinthApi("m-token", http).create_version({"version_number": "1.0.0-alpha.1"}, self.jar)
        method, url, headers, body = http.requests[0]
        self.assertEqual((method, url, headers["Authorization"]), ("POST", rd.MODRINTH + "/version", "m-token"))
        self.assertIn(b'name="data"', body)
        self.assertIn(b'name="jar"; filename="AntiSpeedrun-1.0.0-alpha.1.jar"', body)
        self.assertIn(b"\r\n\r\n" + self.jar.read_bytes() + b"\r\n", body)

    def test_hangar_authenticates_then_uploads_the_jar_bytes_unchanged(self):
        self.candidate()
        http = FakeHttp([(200, b'{"token": "jwt"}'), (200, b'{"url": "x"}')])
        rd.HangarApi("h-token", http).upload("AntiSpeedrun", {"version": "1.0.0-alpha.1"}, self.jar)
        (auth_method, auth_url, _, _), (method, url, headers, body) = http.requests
        self.assertEqual(auth_method, "POST")
        self.assertTrue(auth_url.startswith(rd.HANGAR + "/authenticate?apiKey=h-token"))
        self.assertEqual((method, url), ("POST", rd.HANGAR + "/projects/AntiSpeedrun/upload"))
        self.assertEqual(headers["Authorization"], "HangarAuth jwt")
        self.assertIn(b'name="versionUpload"', body)
        self.assertIn(b"\r\n\r\n" + self.jar.read_bytes() + b"\r\n", body)

    def test_not_found_is_absence_and_other_errors_stop(self):
        http = FakeHttp([(404, b""), (500, b"boom")])
        api = rd.ModrinthApi("m-token", http)
        self.assertIsNone(api.version_by_file("0" * 128))
        with self.assertRaisesRegex(rd.DistributorError, "HTTP 500"):
            api.version_by_file("0" * 128)
        self.assertEqual(json.loads(json.dumps(http.requests[0][2])), {"Authorization": "m-token"})

    def test_failed_hangar_authentication_stops(self):
        with self.assertRaisesRegex(rd.DistributorError, "rejected HANGAR_API_TOKEN"):
            rd.HangarApi("bad", FakeHttp([(401, b"")])).version("AntiSpeedrun", "1.0.0")


if __name__ == "__main__":
    unittest.main()
