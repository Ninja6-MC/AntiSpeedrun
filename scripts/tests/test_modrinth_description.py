from pathlib import Path
import contextlib
import importlib.util
import io
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("modrinth_description", ROOT / "scripts" / "modrinth-description.py")
md = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(md)

README = """# AntiSpeedrun

<p align="center">
  <img src="docs/assets/icon.svg" width="128" alt="icon">
</p>

Part of the suite.

---

## Status

See the [guide](docs/administration.md) and ![shot](docs/shot.png) — fine.

```yaml
key: value
```

[ref]: docs/configuration.md
"""


def render(readme):
    with mock.patch("io.open", mock.mock_open(read_data=readme)):
        return md.render()


class RenderTest(unittest.TestCase):
    def test_strips_github_chrome_and_makes_links_absolute(self):
        text = render(README)
        self.assertTrue(text.startswith(md.HEADER))
        self.assertNotIn("# AntiSpeedrun", text)
        self.assertNotIn("<p", text)
        self.assertIn("](https://github.com/Ninja6-MC/AntiSpeedrun/blob/main/docs/administration.md)", text)
        self.assertIn("(https://raw.githubusercontent.com/Ninja6-MC/AntiSpeedrun/main/docs/shot.png)", text)
        self.assertIn("[ref]: https://github.com/Ninja6-MC/AntiSpeedrun/blob/main/docs/configuration.md", text)
        self.assertIn(" - fine.", text)
        self.assertIn("```yaml\nkey: value\n```", text)

    def test_content_rule_violations_fail(self):
        cases = {
            "comment in a fence": README.replace("key: value", "# a comment"),
            "malformed heading": README.replace("## Status", "#Status"),
            "relative html": README + '\n<div><img src="docs/x.png"></div>\n',
            "no sections": "# AntiSpeedrun\n\nJust text.\n",
        }
        for label, readme in cases.items():
            with self.subTest(label):
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    render(readme)

    def test_an_unclosed_html_block_is_refused(self):
        with self.assertRaises(md.ReadmeError):
            render(README.replace("</p>", ""))


class CommittedFileTest(unittest.TestCase):
    def test_the_committed_description_is_what_the_readme_generates(self):
        committed = (ROOT / "docs" / "modrinth-description.md").read_text(encoding="utf-8")
        self.assertEqual(committed, md.render())


if __name__ == "__main__":
    unittest.main()
