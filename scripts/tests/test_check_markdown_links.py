from __future__ import annotations

import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import check_markdown_links as cml  # noqa: E402


class CheckMarkdownLinksTest(unittest.TestCase):
    def test_repository_markdown_files_pass_link_and_workstation_checks(self):
        self.assertEqual(cml.check_all(cml.ROOT), [])

    def test_supports_optional_titles_angle_brackets_percent_encoding_and_ignores_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            docs = root / "docs"
            docs.mkdir()
            (docs / "guia.md").write_text("# Guia\n", encoding="utf-8")
            (docs / "meu arquivo (1).md").write_text("# Com espaços\n", encoding="utf-8")

            readme = root / "README.md"
            readme.write_text(
                "# Índice\n\n"
                '- [Com título duplo](docs/guia.md "Guia (Oficial)")\n'
                "- [Com título simples](docs/guia.md 'Guia Simples')\n"
                "- [Com angle brackets](<docs/meu arquivo (1).md>)\n"
                "- [Com URL encoding](docs/meu%20arquivo%20%281%29.md#secao)\n"
                "- [Âncora local](#indice)\n"
                "- [API GitHub](https://api.github.com/users/danielcdes-create/packages)\n"
                "- Inline code ignorado: `[falso](inexistente.md)` e `` `[outro](nao-existe.md)` ``\n\n"
                "```markdown\n"
                "[bloco crase](inexistente-crase.md)\n"
                "```\n\n"
                "~~~markdown\n"
                "[bloco til](inexistente-til.md)\n"
                "~~~\n",
                encoding="utf-8",
            )

            self.assertEqual(cml.check_all(root), [])
            with mock.patch("sys.stdout", io.StringIO()):
                self.assertEqual(cml.main(["--root", str(root)]), 0)

    def test_rejects_broken_links_root_escapes_and_windows_mac_linux_workstation_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            repo = base / "repo"
            repo.mkdir()
            (base / "secret.md").write_text("# Outside\n", encoding="utf-8")

            (repo / "broken.md").write_text(
                "[Quebrado](ausente.md)\n"
                "[Escape direto](../secret.md)\n"
                "[Escape encoded](%2e%2e/secret.md)\n",
                encoding="utf-8",
            )
            errors = cml.check_all(repo)
            self.assertEqual(len(errors), 3)
            self.assertTrue(any("broken relative link" in e for e in errors))
            self.assertEqual(sum("escapes repository root" in e for e in errors), 2)

            for idx, bad_snippet in enumerate(
                (
                    r"Veja C:\Users\alice\Cogniva\README.md",
                    "Veja C:/Users/alice/Cogniva/README.md",
                    "Veja file:///C:/Cogniva/README.md",
                    "Veja /Users/alice/Cogniva/README.md",
                    "Veja /home/ubuntu/Cogniva/README.md",
                )
            ):
                sub = base / f"ws-{idx}"
                sub.mkdir()
                (sub / "doc.md").write_text(bad_snippet + "\n", encoding="utf-8")
                ws_errors = cml.check_all(sub)
                self.assertEqual(len(ws_errors), 1, msg=bad_snippet)
                self.assertIn("hardcoded local workstation path", ws_errors[0])

            with mock.patch("sys.stderr", io.StringIO()):
                self.assertEqual(cml.main(["--root", str(repo)]), 1)


if __name__ == "__main__":
    unittest.main()
