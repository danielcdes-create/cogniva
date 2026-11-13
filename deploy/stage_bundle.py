"""Move a staged bundle into its immutable source-revision directory, protected by flock."""
import fcntl
import hashlib
import json
import os
import pathlib
import re

ROOT = pathlib.Path(os.environ.get('COGNIVA_APP_ROOT', '/opt/apps')).resolve()
LOCK_PATH = ROOT / '.cogniva-deploy.lock'

source = pathlib.Path(__file__).resolve().parent
assert source.parent == ROOT / 'cogniva-incoming', f'Unexpected staging parent: {source.parent}'
revision = json.loads((source / 'release.json').read_text(encoding='utf-8'))['revision']
assert re.fullmatch(r'[0-9a-f]{40}', revision), 'Invalid revision SHA'

LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
with LOCK_PATH.open('w', encoding='utf-8') as lock_file:
    fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
    target = ROOT / 'cogniva-releases' / revision
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
