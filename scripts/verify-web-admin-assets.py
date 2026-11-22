"""Compare public web-admin and web-missions bytes with an immutable Git revision (read-only)."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('revision')
parser.add_argument('--origin', default=os.environ.get('COGNIVA_PUBLIC_ORIGIN', 'https://198.199.65.103'))
parser.add_argument('--output', type=Path)
args = parser.parse_args()

assert len(args.revision) == 40 and all(c in '0123456789abcdef' for c in args.revision)
root = Path(__file__).resolve().parents[1]
origin = args.origin.rstrip('/')
files = {
    '/admin/': ('web-admin', 'index.html'),
    '/admin/assets/app.js': ('web-admin', 'app.js'),
    '/admin/assets/help.js': ('web-admin', 'help.js'),
    '/admin/assets/domain.js': ('web-admin', 'domain.js'),
    '/admin/assets/style.css': ('web-admin', 'style.css'),
    '/missoes/': ('web-missions', 'index.html'),
    '/missoes/assets/app.js': ('web-missions', 'app.js'),
    '/missoes/assets/domain.js': ('web-missions', 'domain.js'),
    '/missoes/assets/style.css': ('web-missions', 'style.css'),
}
checked = {}
for url, (folder, name) in files.items():
    git_rel = f'{folder}/{name}'
    expected = subprocess.check_output(['git', 'show', f'{args.revision}:{git_rel}'], cwd=root)
    with urllib.request.urlopen(origin + url, timeout=45) as response:
        received = response.read()
        assert response.status == 200
        assert 'no-store' in response.headers.get('Cache-Control', '')
        if url in ('/admin/', '/missoes/'):
            assert "frame-ancestors 'none'" in response.headers.get('Content-Security-Policy', '')
    assert received == expected, f'{git_rel}: served bytes differ from release revision'
    key = name if folder == 'web-admin' else git_rel
    checked[key] = {
        'bytes': len(received),
        'sha256': hashlib.sha256(received).hexdigest(),
        'matches_release': True,
    }

result = {
    'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'revision': args.revision,
    'origin': origin,
    'files': checked,
    'access': 'public read-only',
    'page_csp_verified': True,
    'cache_disabled': True,
}
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
