from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "snapshot-version.py"


class SnapshotVersionTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo = Path(self.temporary_directory.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Snapshot Test")
        self.git("config", "user.email", "snapshot@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "tag.gpgsign", "false")
        self.commit_number = 0
        self.commit()

    def git(self, *args):
        return subprocess.check_output(("git", *args), cwd=self.repo, text=True).strip()

    def commit(self):
        self.commit_number += 1
        self.git("commit", "-q", "--allow-empty", "-m", f"test-{self.commit_number}")

    def head(self):
        return self.git("rev-parse", "--short", "HEAD")

    def version(self):
        return subprocess.check_output((sys.executable, str(SCRIPT)), cwd=self.repo, text=True).strip()

    def test_untagged_history_uses_hash_fallback(self):
        self.commit()
        self.assertEqual(self.version(), f"0.0.0-SNAPSHOT-g{self.head()}")

    def test_exact_stable_tag_still_includes_distance_and_commit(self):
        self.git("tag", "v1.0.0")
        self.assertEqual(self.version(), f"1.0.0-0-g{self.head()}")

    def test_exact_annotated_prerelease_tag(self):
        self.git("tag", "-a", "v0.1.0-beta.1", "-m", "release")
        self.assertEqual(self.version(), f"0.1.0-beta.1-0-g{self.head()}")

    def test_commits_after_valid_tag(self):
        self.git("tag", "v0.1.0-rc.1")
        self.commit()
        self.commit()
        self.assertEqual(self.version(), f"0.1.0-rc.1-2-g{self.head()}")

    def test_nearer_invalid_tags_are_ignored(self):
        self.git("tag", "v0.1.0-alpha.1")
        self.commit()
        for tag in ("v9.9.9-preview.1", "v2.0.0-rc.1-extra", "v3.0", "1.2.3", "v1.2.3.4", "V4.0.0"):
            self.git("tag", tag)
        self.commit()
        self.assertEqual(self.version(), f"0.1.0-alpha.1-2-g{self.head()}")

    def test_only_invalid_tags_use_hash_fallback(self):
        self.git("tag", "v1.2.3-preview.1")
        self.git("tag", "v1.2.3.4")
        self.assertEqual(self.version(), f"0.0.0-SNAPSHOT-g{self.head()}")

    def test_valid_unreachable_tag_is_ignored(self):
        build_commit = self.git("rev-parse", "HEAD")
        self.git("checkout", "-q", "--orphan", "other")
        self.commit()
        self.git("tag", "v9.9.9")
        self.git("checkout", "-q", "--detach", build_commit)
        self.assertEqual(self.version(), f"0.0.0-SNAPSHOT-g{self.head()}")

    def test_valid_tag_on_unmerged_branch_is_ignored(self):
        self.git("tag", "v0.1.0")
        self.git("checkout", "-q", "-b", "side")
        self.commit()
        self.git("tag", "v0.2.0")
        self.git("checkout", "-q", "main")
        self.commit()
        self.assertEqual(self.version(), f"0.1.0-1-g{self.head()}")

    def test_checked_out_merge_commit_uses_its_own_hash(self):
        # On a pull_request event actions/checkout builds a synthetic merge of the PR head
        # into the base branch. The version must name that commit, not the PR head.
        self.git("tag", "v0.1.0")
        self.git("checkout", "-q", "-b", "feature")
        self.commit()
        pr_head = self.head()
        self.git("checkout", "-q", "main")
        self.commit()
        self.git("merge", "-q", "--no-ff", "--no-edit", "feature")
        self.git("checkout", "-q", "--detach", "HEAD")
        merge = self.head()
        self.assertNotEqual(merge, pr_head)
        # The main commit, the feature commit and the merge itself are all past v0.1.0.
        self.assertEqual(self.version(), f"0.1.0-3-g{merge}")

    def test_tag_reachable_only_through_merged_pr_head(self):
        # A tag on the PR side of the merge is an ancestor of the merge commit and counts.
        self.git("checkout", "-q", "-b", "feature")
        self.commit()
        self.git("tag", "v0.2.0-alpha.1")
        self.git("checkout", "-q", "main")
        self.commit()
        self.git("merge", "-q", "--no-ff", "--no-edit", "feature")
        self.assertEqual(self.version(), f"0.2.0-alpha.1-2-g{self.head()}")


if __name__ == "__main__":
    unittest.main()
