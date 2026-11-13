"""Verify that all Markdown files in the repository have valid relative links and no workstation paths."""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
LINK_RE = re.compile(r'!?\[[^\]]*\]\(([^)]+)\)')
FORBIDDEN_WORKSTATION_RE = re.compile(r'(?:[A-Za-z]:\\Users\\|[A-Za-z]:/Users/|file:///[A-Za-z]:/)', re.IGNORECASE)


def iter_markdown_files() -> list[pathlib.Path]:
    ignored_dirs = {'.git', 'target', 'build', '.gradle', 'node_modules', '.venv', '.pytest_cache'}
    results: list[pathlib.Path] = []
    for path in ROOT.rglob('*.md'):
        if any(part in ignored_dirs for part in path.parts):
            continue
        results.append(path)
    return sorted(results)


def strip_fenced_code(text: str) -> str:
    return re.sub(r'```[\s\S]*?```', '', text)


def check_all() -> list[str]:
    errors: list[str] = []
    for md in iter_markdown_files():
        rel_md = md.relative_to(ROOT)
        raw = md.read_text(encoding='utf-8')
        if FORBIDDEN_WORKSTATION_RE.search(raw):
            errors.append(f'{rel_md}: contains hardcoded local workstation path')
        cleaned = strip_fenced_code(raw)
        for match in LINK_RE.finditer(cleaned):
            target = match.group(1).strip()
            if not target or target.startswith(('#', 'http://', 'https://', 'mailto:')):
                continue
            path_part = target.split('#', 1)[0].split('?', 1)[0].strip()
            if not path_part:
                continue
            resolved = (md.parent / path_part).resolve()
            try:
                resolved.relative_to(ROOT)
            except ValueError:
                errors.append(f'{rel_md}: link escapes repository root -> {target}')
                continue
            if not resolved.exists():
                errors.append(f'{rel_md}: broken relative link -> {target}')
    return errors


def main() -> int:
    errors = check_all()
    if errors:
        for err in errors:
            print(err, file=sys.stderr)
        return 1
    print(f'Markdown links verified across {len(iter_markdown_files())} files.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
