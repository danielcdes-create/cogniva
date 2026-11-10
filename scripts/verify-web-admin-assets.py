"""Compare public web-admin bytes with an immutable Git revision (read-only)."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('revision')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert len(args.revision) == 40 and all(c in '0123456789abcdef' for c in args.revision)
root = Path(__file__).resolve().parents[1]
files = {'/admin/': 'index.html', '/admin/assets/app.js': 'app.js',
         '/admin/assets/help.js': 'help.js', '/admin/assets/domain.js': 'domain.js', '/admin/assets/style.css': 'style.css'}
checked = {}
for url, name in files.items():
    expected = subprocess.check_output(['git', 'show', f'{args.revision}:web-admin/{name}'], cwd=root)
    with urllib.request.urlopen('https://198.199.65.103' + url, timeout=45) as response:
        received = response.read()
        assert response.status == 200
        assert 'no-store' in response.headers.get('Cache-Control', '')
        if url == '/admin/':
            assert "frame-ancestors 'none'" in response.headers.get('Content-Security-Policy', '')
    assert received == expected, f'{name}: served bytes differ from release revision'
    checked[name] = {'bytes': len(received), 'sha256': hashlib.sha256(received).hexdigest(),
                     'matches_release': True}
result = {'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'revision': args.revision, 'files': checked, 'access': 'public read-only',
          'page_csp_verified': True, 'cache_disabled': True}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
