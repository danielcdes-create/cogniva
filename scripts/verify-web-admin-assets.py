"""Compare public web-admin and web-missions bytes with an immutable Git revision (read-only)."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

FILES: dict[str, tuple[str, str]] = {
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


def verify_web_admin_assets(
    revision: str,
    *,
    origin: str = 'https://198.199.65.103',
    output: Path | None = None,
    root: Path = ROOT,
) -> dict:
    assert len(revision) == 40 and all(c in '0123456789abcdef' for c in revision)
    eff_origin = origin.rstrip('/')
    checked: dict[str, dict] = {}
    for url, (folder, name) in FILES.items():
        git_rel = f'{folder}/{name}'
        expected = subprocess.check_output(['git', 'show', f'{revision}:{git_rel}'], cwd=root)
        with urllib.request.urlopen(eff_origin + url, timeout=45) as response:
            received = response.read()
            headers = {k.lower(): v for k, v in response.headers.items()}
            assert response.status == 200
            assert 'no-store' in headers.get('cache-control', '')
            assert headers.get('x-content-type-options') == 'nosniff', (
                f'{url}: missing X-Content-Type-Options: nosniff'
            )
            if url in ('/admin/', '/missoes/'):
                csp = headers.get('content-security-policy', '')
                assert "frame-ancestors 'none'" in csp, f'{url}: missing frame-ancestors in CSP'
                assert "default-src 'none'; script-src 'self'; style-src 'self'" in csp, (
                    f'{url}: missing baseline script/style CSP directives'
                )
                if url == '/missoes/':
                    assert "media-src 'self'" in csp, "/missoes/: missing media-src 'self' in CSP"
        assert received == expected, f'{git_rel}: served bytes differ from release revision'
        key = name if folder == 'web-admin' else git_rel
        checked[key] = {
            'bytes': len(received),
            'sha256': hashlib.sha256(received).hexdigest(),
            'matches_release': True,
        }

    result = {
        'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'revision': revision,
        'origin': eff_origin,
        'files': checked,
        'access': 'public read-only',
        'page_csp_verified': True,
        'cache_disabled': True,
    }
    if output:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('revision')
    parser.add_argument('--origin', default=os.environ.get('COGNIVA_PUBLIC_ORIGIN', 'https://198.199.65.103'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    result = verify_web_admin_assets(args.revision, origin=args.origin, output=args.output)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
