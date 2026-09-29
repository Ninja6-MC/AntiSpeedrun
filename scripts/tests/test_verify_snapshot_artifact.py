from pathlib import Path
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / "verify-snapshot-artifact.py"
SPEC = importlib.util.spec_from_file_location("verify_snapshot_artifact", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
verify = MODULE.verify


class VerifySnapshotArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.directory = Path(self.temporary_directory.name)
        self.version = "0.1.0-beta.1-3-gabc1234"
        self.jar = self.directory / f"AntiSpeedrun-{self.version}.jar"

    def write_jar(self, path, plugin_yml):
        with ZipFile(path, "w") as archive:
            if plugin_yml is not None:
                archive.writestr("plugin.yml", plugin_yml)

    def plugin_yml(self, version, quote="'"):
        return f"name: AntiSpeedrun\nversion: {quote}{version}{quote}\nmain: x.Y\n"

    def test_accepts_the_single_matching_jar(self):
        self.write_jar(self.jar, self.plugin_yml(self.version))
        verify(self.directory, self.version)

    def test_accepts_unquoted_and_double_quoted_version(self):
        for quote in ("", '"'):
            with self.subTest(quote=quote):
                self.write_jar(self.jar, self.plugin_yml(self.version, quote))
                verify(self.directory, self.version)

    def test_rejects_missing_jar(self):
        with self.assertRaisesRegex(ValueError, r"found: \[\]"):
            verify(self.directory, self.version)

    def test_rejects_unexpected_extra_jar(self):
        self.write_jar(self.jar, self.plugin_yml(self.version))
        self.write_jar(self.directory / "AntiSpeedrun-1.0.0-SNAPSHOT.jar", self.plugin_yml(self.version))
        with self.assertRaises(ValueError):
            verify(self.directory, self.version)

    def test_rejects_jar_named_for_another_version(self):
        self.write_jar(self.directory / "AntiSpeedrun-1.0.0-SNAPSHOT.jar", self.plugin_yml(self.version))
        with self.assertRaises(ValueError):
            verify(self.directory, self.version)

    def test_rejects_mismatched_embedded_version(self):
        self.write_jar(self.jar, self.plugin_yml("1.0.0-SNAPSHOT"))
        with self.assertRaisesRegex(ValueError, "plugin.yml version"):
            verify(self.directory, self.version)

    def test_rejects_duplicate_version_keys(self):
        self.write_jar(self.jar, self.plugin_yml(self.version) + f"version: '{self.version}'\n")
        with self.assertRaises(ValueError):
            verify(self.directory, self.version)

    def test_rejects_jar_without_plugin_yml(self):
        self.write_jar(self.jar, None)
        with self.assertRaisesRegex(ValueError, "one plugin.yml"):
            verify(self.directory, self.version)

    def test_rejects_unreadable_jar(self):
        self.jar.write_bytes(b"not a zip")
        with self.assertRaisesRegex(ValueError, "not a readable JAR"):
            verify(self.directory, self.version)

    def test_command_line_exits_non_zero_on_mismatch(self):
        libs = self.directory / "build" / "libs"
        libs.mkdir(parents=True)
        self.write_jar(libs / f"AntiSpeedrun-{self.version}.jar", self.plugin_yml("0.0.0"))
        result = subprocess.run((sys.executable, str(SCRIPT), self.version), cwd=self.directory,
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("plugin.yml version", result.stderr)

    def test_command_line_requires_a_version(self):
        result = subprocess.run((sys.executable, str(SCRIPT)), cwd=self.directory,
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("usage", result.stderr)


if __name__ == "__main__":
    unittest.main()
