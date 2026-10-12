from pathlib import Path
import argparse
import contextlib
import copy
import hashlib
import importlib.util
import io
import subprocess
import tempfile
import unittest
from unittest import mock

from test_release_candidate import write_jar


SCRIPT = Path(__file__).resolve().parents[1] / "release-github.py"
SPEC = importlib.util.spec_from_file_location("release_github", SCRIPT)
gh = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gh)
rc = gh.candidate

SHA = "0123456789abcdef0123456789abcdef01234567"
NOTES = "### Added\n- Things.\n"


class FakeGitHub:
    """An in-memory GitHub Releases API that records every write."""

    def __init__(self, tag_sha=SHA):
        self.tag_sha = tag_sha
        self.releases_by_id = {}
        self.blobs = {}
        self.writes = []
        self.latest = None
        self.next_id = 100
        self.fail_upload = None

    def _id(self):
        self.next_id += 1
        return self.next_id

    def add_release(self, tag, draft, prerelease, assets=(), name=None, body=NOTES):
        release = {"id": self._id(), "tag_name": tag, "name": name or f"AntiSpeedrun {tag}", "body": body,
                   "draft": draft, "prerelease": prerelease, "assets": [], "html_url": "https://example.invalid"}
        for asset_name, data, state in assets:
            self._add_asset(release, asset_name, data, state)
        self.releases_by_id[release["id"]] = release
        return release

    def _add_asset(self, release, name, data, state="uploaded"):
        asset = {"id": self._id(), "name": name, "state": state,
                 "browser_download_url": f"https://download.invalid/{release['id']}/{name}"}
        self.blobs[asset["id"]] = data
        self.blobs[asset["browser_download_url"]] = data
        release["assets"].append(asset)

    def tag_commit(self, tag):
        return self.tag_sha

    def releases(self, tag):
        return [copy.deepcopy(r) for r in self.releases_by_id.values() if r["tag_name"] == tag]

    def release(self, release_id):
        return copy.deepcopy(self.releases_by_id[release_id])

    def latest_id(self):
        return self.latest

    def published_releases(self):
        return [{"id": r["id"], "tag_name": r["tag_name"], "prerelease": r["prerelease"]}
                for r in self.releases_by_id.values() if not r["draft"]]

    def create_draft(self, tag, title, body, prerelease, target):
        self.writes.append(("create", tag))
        self.draft_target = target
        release = self.add_release(tag, True, prerelease, name=title, body=body)
        return copy.deepcopy(release)

    def delete_asset(self, asset_id):
        self.writes.append(("delete", asset_id))
        for release in self.releases_by_id.values():
            release["assets"] = [a for a in release["assets"] if a["id"] != asset_id]

    def upload_asset(self, release_id, path):
        self.writes.append(("upload", path.name))
        if self.fail_upload == path.name:
            self._add_asset(self.releases_by_id[release_id], path.name, b"", "starter")
            raise gh.PromotionError("upload interrupted")
        self._add_asset(self.releases_by_id[release_id], path.name, path.read_bytes())

    def download_asset(self, asset_id):
        return self.blobs[asset_id]

    def publish(self, release_id, prerelease, make_latest):
        self.writes.append(("publish", release_id))
        release = self.releases_by_id[release_id]
        release.update(draft=False, prerelease=prerelease)
        if make_latest:
            self.latest = release_id
        return copy.deepcopy(release)

    def consumer_download(self, url):
        return self.blobs[url]


class PromoteTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def candidate(self, tag):
        version = tag[1:]
        libs = self.root / "libs"
        libs.mkdir()
        write_jar(libs / f"AntiSpeedrun-{version}.jar", version)
        changelog = self.root / "CHANGELOG.md"
        changelog.write_text(f"## [Unreleased]\n\n## [{version}]\n\n{NOTES}", encoding="utf-8")
        directory = self.root / "candidate"
        rc.create(argparse.Namespace(directory=str(directory), tag=tag, sha=SHA, run_id="7", attempt="1",
                                     repository="Ninja6-MC/AntiSpeedrun", jar_directory=str(libs),
                                     changelog=str(changelog)))
        self.directory = directory
        self.manifest = rc.verify(argparse.Namespace(directory=str(directory), tag=tag, sha=SHA, run_id="7",
                                                     attempt="1", repository="Ninja6-MC/AntiSpeedrun"))
        return self.manifest

    def assets(self, *names, state="uploaded", corrupt=()):
        result = []
        for name in names:
            data = (self.directory / name).read_bytes()
            result.append((name, data + b"x" if name in corrupt else data, state))
        return result

    def promote(self, api):
        with contextlib.redirect_stdout(io.StringIO()):
            return gh.promote(api, self.manifest, self.directory, SHA)

    def names(self):
        jar = f"AntiSpeedrun-{self.manifest['version']}.jar"
        return jar, f"{jar}.sha256"

    def test_fresh_stable_release_is_drafted_uploaded_and_published_as_latest(self):
        self.candidate("v1.0.0")
        api = FakeGitHub()
        release = self.promote(api)
        self.assertEqual([w[0] for w in api.writes], ["create", "upload", "upload", "publish"])
        self.assertFalse(release["draft"])
        self.assertFalse(release["prerelease"])
        self.assertEqual(api.latest, release["id"])

    def test_draft_targets_the_candidate_commit(self):
        self.candidate("v1.0.0")
        api = FakeGitHub()
        self.promote(api)
        self.assertEqual(api.draft_target, SHA)

    def test_stable_release_older_than_a_published_stable_is_not_made_latest(self):
        self.candidate("v1.0.0")
        api = FakeGitHub()
        newer = api.add_release("v1.2.0", False, False)
        api.add_release("v2.0.0-beta.1", False, True)
        api.latest = newer["id"]
        release = self.promote(api)
        self.assertFalse(release["draft"])
        self.assertEqual(api.latest, newer["id"])

    def test_stable_release_newer_than_every_published_stable_is_made_latest(self):
        self.candidate("v1.2.0")
        api = FakeGitHub()
        older = api.add_release("v1.1.9", False, False)
        api.add_release("v1.10.0", True, False)       # a draft does not count
        api.add_release("v1.3.0-rc.1", False, True)   # nor does a pre-release
        api.add_release("v0.9.0", False, True)
        api.latest = older["id"]
        release = self.promote(api)
        self.assertEqual(api.latest, release["id"])

    def test_semver_order_is_numeric(self):
        self.assertGreater(gh.stable_key("v1.10.0"), gh.stable_key("v1.9.0"))
        self.assertIsNone(gh.stable_key("v1.0.0-rc.1"))
        self.assertIsNone(gh.stable_key("v0.3.0"))
        self.assertIsNone(gh.stable_key("nightly"))

    def test_older_stable_release_marked_latest_stops(self):
        self.candidate("v1.0.0")
        api = FakeGitHub()
        api.add_release("v1.2.0", False, False)
        release = api.add_release("v1.0.0", False, False, self.assets(*self.names()))
        api.latest = release["id"]
        with self.assertRaisesRegex(gh.PromotionError, "should not be"):
            self.promote(api)
        self.assertEqual(api.writes, [])

    def test_development_build_is_a_prerelease_and_never_latest(self):
        self.candidate("v0.1.0")
        api = FakeGitHub()
        api.latest = 1
        release = self.promote(api)
        self.assertTrue(release["prerelease"])
        self.assertEqual(api.latest, 1)

    def test_interrupted_upload_resumes_on_retry(self):
        self.candidate("v0.1.0")
        api = FakeGitHub()
        jar, checksum = self.names()
        api.fail_upload = checksum
        with self.assertRaises(gh.PromotionError):
            self.promote(api)
        api.fail_upload = None
        api.writes.clear()
        release = self.promote(api)
        self.assertEqual([w[0] for w in api.writes], ["delete", "upload", "publish"])
        self.assertEqual(sorted(a["name"] for a in release["assets"]), sorted([jar, checksum]))

    def test_published_matching_release_is_left_alone(self):
        self.candidate("v1.0.0")
        api = FakeGitHub()
        release = api.add_release("v1.0.0", False, False, self.assets(*self.names()))
        api.latest = release["id"]
        self.promote(api)
        self.assertEqual(api.writes, [])

    def test_published_release_with_different_bytes_stops(self):
        self.candidate("v1.0.0")
        api = FakeGitHub()
        jar, checksum = self.names()
        release = api.add_release("v1.0.0", False, False, self.assets(jar, checksum, corrupt=(jar,)))
        api.latest = release["id"]
        with self.assertRaisesRegex(gh.PromotionError, "SHA-256"):
            self.promote(api)
        self.assertEqual(api.writes, [])

    def test_published_release_missing_an_asset_stops_without_writing(self):
        self.candidate("v0.1.0")
        api = FakeGitHub()
        api.add_release("v0.1.0", False, True, self.assets(self.names()[0]))
        with self.assertRaisesRegex(gh.PromotionError, "differ"):
            self.promote(api)
        self.assertEqual(api.writes, [])

    def test_draft_with_different_bytes_stops(self):
        self.candidate("v0.1.0")
        api = FakeGitHub()
        jar, _ = self.names()
        api.add_release("v0.1.0", True, True, self.assets(jar, corrupt=(jar,)))
        with self.assertRaisesRegex(gh.PromotionError, "differs from the candidate"):
            self.promote(api)
        self.assertEqual(api.writes, [])

    def test_existing_release_with_other_metadata_stops(self):
        for change in (dict(prerelease=False), dict(body="other notes"), dict(name="Something else")):
            with self.subTest(change=change):
                self.root = Path(tempfile.mkdtemp(dir=self.root))
                self.candidate("v0.1.0")
                api = FakeGitHub()
                values = dict(draft=True, prerelease=True)
                values.update({k: v for k, v in change.items() if k == "prerelease"})
                api.add_release("v0.1.0", values["draft"], values["prerelease"],
                                name=change.get("name"), body=change.get("body", NOTES))
                with self.assertRaises(gh.PromotionError):
                    self.promote(api)
                self.assertEqual(api.writes, [])

    def test_moved_tag_is_never_released(self):
        self.candidate("v1.0.0")
        api = FakeGitHub(tag_sha="f" * 40)
        with self.assertRaisesRegex(gh.PromotionError, "moved tag"):
            self.promote(api)
        self.assertEqual(api.writes, [])

    def test_run_from_another_commit_is_refused(self):
        self.candidate("v1.0.0")
        api = FakeGitHub()
        with self.assertRaises(gh.PromotionError):
            with contextlib.redirect_stdout(io.StringIO()):
                gh.promote(api, self.manifest, self.directory, "e" * 40)
        self.assertEqual(api.writes, [])

    def test_two_releases_for_one_tag_stop(self):
        self.candidate("v0.1.0")
        api = FakeGitHub()
        api.add_release("v0.1.0", True, True)
        api.add_release("v0.1.0", True, True)
        with self.assertRaisesRegex(gh.PromotionError, "2 releases"):
            self.promote(api)

    def test_stable_release_that_is_not_latest_stops(self):
        self.candidate("v1.0.0")
        api = FakeGitHub()
        api.add_release("v1.0.0", False, False, self.assets(*self.names()))
        api.latest = 1
        with self.assertRaisesRegex(gh.PromotionError, "not GitHub's Latest"):
            self.promote(api)

    def test_unexpected_draft_asset_stops(self):
        self.candidate("v0.1.0")
        api = FakeGitHub()
        release = api.add_release("v0.1.0", True, True)
        api._add_asset(api.releases_by_id[release["id"]], "other.zip", b"zip")
        with self.assertRaisesRegex(gh.PromotionError, "unexpected asset"):
            self.promote(api)

    def test_consumer_bytes_are_what_is_checked_after_publishing(self):
        self.candidate("v0.1.0")
        api = FakeGitHub()
        original = api.publish

        def publish_then_cdn_serves_other_bytes(release_id, prerelease, make_latest):
            result = original(release_id, prerelease, make_latest)
            for asset in api.releases_by_id[release_id]["assets"]:
                api.blobs[asset["browser_download_url"]] = b"tampered"
            return result

        api.publish = publish_then_cdn_serves_other_bytes
        with self.assertRaisesRegex(gh.PromotionError, "SHA-256"):
            self.promote(api)


class GitHubApiTest(unittest.TestCase):
    """The gh api calls themselves, with gh faked at the subprocess boundary."""

    REPO = "Ninja6-MC/AntiSpeedrun"

    @staticmethod
    def response(status, body=b"", headers=()):
        reason = {200: "OK", 204: "No Content", 403: "Forbidden", 404: "Not Found"}.get(status, "")
        head = "".join(f"{name}: {value}\r\n" for name, value in
                       (("Content-Type", "application/json; charset=utf-8"), *headers))
        return f"HTTP/2.0 {status} {reason}\n{head}\r\n".encode() + body

    def run_gh(self, stdout, returncode=0, stderr=b""):
        """Patch gh for one block; the returned list collects each command it was given."""
        calls = []

        def fake_run(command, input=None, capture_output=False):
            calls.append(command)
            return subprocess.CompletedProcess(command, returncode, stdout, stderr)

        patcher = mock.patch.object(gh.subprocess, "run", fake_run)
        patcher.start()
        self.addCleanup(patcher.stop)
        return calls

    def test_forbidden_create_names_the_call_status_body_and_wanted_permission(self):
        body = (b'{"message":"Resource not accessible by integration",'
                b'"documentation_url":"https://docs.github.com/rest/releases/releases#create-a-release","status":"403"}')
        self.run_gh(self.response(403, body, (("X-Accepted-GitHub-Permissions", "contents=write"),)),
                    returncode=1, stderr=b"gh: Resource not accessible by integration (HTTP 403)\n")
        with self.assertRaises(gh.PromotionError) as raised:
            gh.GitHubApi(self.REPO).create_draft("v0.3.1", "AntiSpeedrun v0.3.1", NOTES, False, SHA)
        message = str(raised.exception)
        self.assertIn(f"POST repos/{self.REPO}/releases failed with HTTP 403", message)
        self.assertIn('"message":"Resource not accessible by integration"', message)
        self.assertIn("X-Accepted-GitHub-Permissions: contents=write", message)
        self.assertIn("gh: Resource not accessible by integration (HTTP 403)", message)
        self.assertNotIn("\n", message)

    def test_failure_without_the_permission_header_omits_it(self):
        self.run_gh(self.response(500, b'{"message":"Server Error"}'), returncode=1)
        with self.assertRaises(gh.PromotionError) as raised:
            gh.GitHubApi(self.REPO).delete_asset(7)
        message = str(raised.exception)
        self.assertEqual(message, f'DELETE repos/{self.REPO}/releases/assets/7 failed with HTTP 500: '
                                  '{"message":"Server Error"}')

    def test_long_failure_body_is_truncated(self):
        self.run_gh(self.response(502, b"x" * (gh.BODY_LIMIT + 500)), returncode=1)
        with self.assertRaises(gh.PromotionError) as raised:
            gh.GitHubApi(self.REPO).release(9)
        message = str(raised.exception)
        self.assertIn("x" * gh.BODY_LIMIT + f"... ({gh.BODY_LIMIT + 500} characters)", message)
        self.assertNotIn("x" * (gh.BODY_LIMIT + 1), message)

    def test_failure_before_any_response_reports_gh(self):
        self.run_gh(b"", returncode=4, stderr=b"To get started with GitHub CLI, please run:  gh auth login\n")
        with self.assertRaisesRegex(gh.PromotionError,
                                    f"^GET repos/{self.REPO}/releases/3 failed: To get started with GitHub CLI"):
            gh.GitHubApi(self.REPO).release(3)

    def test_success_returns_the_body_without_headers(self):
        calls = self.run_gh(self.response(200, b'{"id": 5, "draft": true}'))
        self.assertEqual(gh.GitHubApi(self.REPO).release(5), {"id": 5, "draft": True})
        self.assertEqual(calls, [("gh", "api", "--include", "-X", "GET", f"repos/{self.REPO}/releases/5")])

    def test_binary_download_keeps_every_byte(self):
        data = b"PK\x03\x04\r\n\r\nHTTP/2.0 200 OK\n\x00\xff"
        self.run_gh(self.response(200, data))
        self.assertEqual(gh.GitHubApi(self.REPO).download_asset(11), data)

    def test_no_content_is_empty(self):
        self.run_gh(self.response(204))
        self.assertEqual(gh.GitHubApi(self.REPO).delete_asset(7), None)

    def test_paginated_filter_output_joins_every_page(self):
        pages = (self.response(200, b'{"id":1,"tag_name":"v0.3.1","prerelease":false}\n')
                 + b"\n" + self.response(200, b'{"id":2,"tag_name":"v0.2.0","prerelease":true}\n'))
        calls = self.run_gh(pages)
        self.assertEqual(gh.GitHubApi(self.REPO).published_releases(),
                         [{"id": 1, "tag_name": "v0.3.1", "prerelease": False},
                          {"id": 2, "tag_name": "v0.2.0", "prerelease": True}])
        self.assertIn("--paginate", calls[0])

    def test_failed_later_page_reports_that_page(self):
        pages = (self.response(200, b'{"id":1,"tag_name":"v0.3.1"}\n') + b"\n"
                 + self.response(403, b'{"message":"rate limited"}'))
        self.run_gh(pages, returncode=1)
        with self.assertRaisesRegex(gh.PromotionError, 'HTTP 403: {"message":"rate limited"}$'):
            gh.GitHubApi(self.REPO).releases("v0.3.1")

    def test_latest_id_reads_the_filtered_id(self):
        self.run_gh(self.response(200, b"403212406\n"))
        self.assertEqual(gh.GitHubApi(self.REPO).latest_id(), 403212406)

    def test_no_latest_release_is_none(self):
        self.run_gh(self.response(404, b'{"message":"Not Found"}'), returncode=1,
                    stderr=b"gh: Not Found (HTTP 404)\n")
        self.assertIsNone(gh.GitHubApi(self.REPO).latest_id())

    def test_other_latest_failure_stops(self):
        self.run_gh(self.response(403, b'{"message":"Forbidden"}'), returncode=1)
        with self.assertRaisesRegex(gh.PromotionError, "releases/latest failed with HTTP 403"):
            gh.GitHubApi(self.REPO).latest_id()

    def test_a_404_elsewhere_still_stops(self):
        self.run_gh(self.response(404, b'{"message":"Not Found"}'), returncode=1)
        with self.assertRaisesRegex(gh.PromotionError, "HTTP 404"):
            gh.GitHubApi(self.REPO).tag_commit("v0.3.1")


class DigestTest(unittest.TestCase):
    def test_sha256(self):
        self.assertEqual(gh.sha256(b"abc"), hashlib.sha256(b"abc").hexdigest())


if __name__ == "__main__":
    unittest.main()
