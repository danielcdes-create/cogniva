"""Move a staged bundle into its immutable source-revision directory, protected by flock."""
from __future__ import annotations

import argparse
try:
    import fcntl
except ImportError:  # pragma: no cover - Windows unit test runner compatibility
    fcntl = None  # type: ignore[assignment]
import hashlib
import json
import os
import pathlib
import re


def stage_bundle(
    source_dir: pathlib.Path | None = None,
    *,
    app_root: pathlib.Path | None = None,
) -> pathlib.Path:
    root = (app_root or pathlib.Path(os.environ.get('COGNIVA_APP_ROOT', '/opt/apps'))).resolve()
    lock_path = root / '.cogniva-deploy.lock'
    source = (source_dir or pathlib.Path(__file__).resolve().parent).resolve()

    assert source.parent == root / 'cogniva-incoming', f'Unexpected staging parent: {source.parent}'
    revision = json.loads((source / 'release.json').read_text(encoding='utf-8'))['revision']
    assert re.fullmatch(r'[0-9a-f]{40}', revision), 'Invalid revision SHA'

    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open('w', encoding='utf-8') as lock_file:
        if fcntl is not None:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        target = root / 'cogniva-releases' / revision
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            for path in source.iterdir():
                assert path.is_file()
                existing = target / path.name
                assert (
                    existing.is_file()
                    and hashlib.sha256(path.read_bytes()).digest()
                    == hashlib.sha256(existing.read_bytes()).digest()
                ), f'Immutable bundle already exists with different bytes: {path.name}'
            for path in source.iterdir():
                path.unlink()
            source.rmdir()
        else:
            os.rename(source, target)
        print('IMMUTABLE_BUNDLE_STAGED', revision)
        return target


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', nargs='?', type=pathlib.Path, default=None)
    args = parser.parse_args(argv)
    stage_bundle(args.source)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
