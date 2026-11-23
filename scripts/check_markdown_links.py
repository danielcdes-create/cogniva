"""Verify that all Markdown files in the repository have valid relative links and no workstation paths."""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]
LINK_RE = re.compile(r'!?\[[^\]]*\]\(((?:<[^>\r\n]*>|"[^"]*"|\'[^\']*\'|\([^()]*\)|[^()])+)\)')
FORBIDDEN_WORKSTATION_RE = re.compile(
    r'(?:[A-Za-z]:\\Users\\|[A-Za-z]:/Users/|file:///[A-Za-z]:/|(?<![A-Za-z0-9_.-])/(?:Users|home)/[A-Za-z0-9._-]+/)',
    re.IGNORECASE,
)
FENCED_CODE_RE = re.compile(r'(`{3,}|~{3,})[\s\S]*?\1')
INLINE_CODE_RE = re.compile(r'(?<!`)(`+)(?!`)[\s\S]*?(?<!`)\1(?!`)')


def iter_markdown_files(root: pathlib.Path = ROOT) -> list[pathlib.Path]:
    ignored_dirs = {
        '.git',
        'target',
        'build',
        '.gradle',
        'node_modules',
        '.venv',
        '.pytest_cache',
        'questoes',
        'release-bundle',
    }
    results: list[pathlib.Path] = []
    for path in root.rglob('*.md'):
        rel_parts = path.relative_to(root).parts
        if any(part in ignored_dirs for part in rel_parts):
            continue
        results.append(path)
    return sorted(results)


def strip_fenced_code(text: str) -> str:
    without_fences = FENCED_CODE_RE.sub('', text)
    return INLINE_CODE_RE.sub('', without_fences)


def extract_link_destination(raw_target: str) -> str:
    target = raw_target.strip()
    if target.startswith('<'):
        end_angle = target.find('>')
        if end_angle != -1:
            return target[1:end_angle].strip()
    title_match = re.match(r'^(.+?)\s+(?:"[^"]*"|\'[^\']*\')$', target)
    if title_match:
        return title_match.group(1).strip()
    return target


def check_all(root: pathlib.Path = ROOT) -> list[str]:
    resolved_root = root.resolve()
    errors: list[str] = []
    for md in iter_markdown_files(resolved_root):
        rel_md = md.relative_to(resolved_root)
        raw = md.read_text(encoding='utf-8')
        if FORBIDDEN_WORKSTATION_RE.search(raw):
            errors.append(f'{rel_md}: contains hardcoded local workstation path')
        cleaned = strip_fenced_code(raw)
        for match in LINK_RE.finditer(cleaned):
            raw_target = match.group(1).strip()
            dest = extract_link_destination(raw_target)
            if not dest or dest.startswith('#') or dest.lower().startswith(('http://', 'https://', 'mailto:')):
                continue
            path_part = dest.split('#', 1)[0].split('?', 1)[0].strip()
            if not path_part:
                continue
            decoded_path = urllib.parse.unquote(path_part)
            resolved = (md.parent / decoded_path).resolve()
            try:
                resolved.relative_to(resolved_root)
            except ValueError:
                errors.append(f'{rel_md}: link escapes repository root -> {raw_target}')
                continue
            if not resolved.exists():
                errors.append(f'{rel_md}: broken relative link -> {raw_target}')
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=pathlib.Path, default=ROOT)
    args = parser.parse_args(argv)
    errors = check_all(args.root)
    if errors:
        for err in errors:
            print(err, file=sys.stderr)
        return 1
    print(f'Markdown links verified across {len(iter_markdown_files(args.root))} files.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
