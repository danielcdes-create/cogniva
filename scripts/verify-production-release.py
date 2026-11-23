"""Cross-platform read-only release acceptance against the public endpoint and VPS."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import pathlib
import socket
import ssl
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('revision')
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--origin', default=os.environ.get('COGNIVA_PUBLIC_ORIGIN', 'https://198.199.65.103'))
    parser.add_argument('--ssh-target', default=os.environ.get('COGNIVA_SSH_TARGET', 'root@198.199.65.103'))
    parser.add_argument('--service', default=os.environ.get('COGNIVA_COMPOSE_SERVICE', 'api-rust-core'))
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--xai-model')
    return parser


def get_json_with_headers(base: str, path: str) -> tuple[dict, dict[str, str]]:
    with urllib.request.urlopen(base.rstrip('/') + path, timeout=60) as response:
        headers = {k.lower(): v for k, v in response.headers.items()}
        return json.load(response), headers


def get_json(base: str, path: str) -> dict:
    payload, _ = get_json_with_headers(base, path)
    return payload


def check_tls_remaining_hours(origin_url: str) -> float | None:
    parsed = urllib.parse.urlsplit(origin_url)
    if parsed.scheme != 'https' or not parsed.hostname:
        return None
    port = parsed.port or 443
    ctx = ssl.create_default_context()
    with socket.create_connection((parsed.hostname, port), timeout=10) as raw_sock:
        with ctx.wrap_socket(raw_sock, server_hostname=parsed.hostname) as tls_sock:
            cert = tls_sock.getpeercert()
    not_after = cert.get('notAfter') if cert else None
    assert not_after, 'Missing TLS certificate notAfter'
    expires_at = datetime.datetime.strptime(not_after, '%b %d %H:%M:%S %Y %Z').replace(
        tzinfo=datetime.timezone.utc
    )
    remaining_hours = (expires_at - datetime.datetime.now(datetime.timezone.utc)).total_seconds() / 3600.0
    assert remaining_hours >= 48.0, f'TLS certificate expires too soon: {remaining_hours:.1f}h'
    return round(remaining_hours, 2)


def verify_production_release(
    revision: str,
    *,
    version_name: str,
    version_code: int,
    origin: str = 'https://198.199.65.103',
    ssh_target: str = 'root@198.199.65.103',
    service: str = 'api-rust-core',
    output: pathlib.Path | None = None,
    xai_model: str | None = None,
) -> dict:
    assert len(revision) == 40 and all(c in '0123456789abcdef' for c in revision)
    base = origin.rstrip('/')

    health, health_headers = get_json_with_headers(base, '/api/v1/health')
    ready = get_json(base, '/api/v1/ready')
    assert health['revision'] == ready['revision'] == revision
    assert health['contract_version'] == ready['contract_version'] == 5
    assert health['version'] == version_name
    assert 'max-age=' in health_headers.get('strict-transport-security', ''), 'Missing HSTS header'
    assert health_headers.get('x-content-type-options') == 'nosniff', 'Missing X-Content-Type-Options'
    assert health_headers.get('x-frame-options') == 'DENY', 'Missing X-Frame-Options'

    tls_remaining_hours = check_tls_remaining_hours(base)

    info = get_json(base, '/api/v1/apk/info')
    assert info == get_json(base, '/api/v1/apk/info'), 'Repeated APK metadata differs'
    assert info['version_code'] == version_code and info['version_name'] == version_name

    try:
        get_json(base, '/api/v1/exams')
        raise AssertionError('Anonymous protected route accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 401, error.code

    for portal_path in ('/admin/', '/missoes/'):
        with urllib.request.urlopen(base + portal_path, timeout=30) as portal_resp:
            portal_headers = {k.lower(): v for k, v in portal_resp.headers.items()}
            assert portal_resp.status == 200, f'{portal_path} returned {portal_resp.status}'
            assert "frame-ancestors 'none'" in portal_headers.get('content-security-policy', '')
            assert 'no-store' in portal_headers.get('cache-control', '')

    with tempfile.TemporaryDirectory(prefix='cogniva-release-verify-') as tmp:
        apk = pathlib.Path(tmp) / 'cogniva.apk'
        with urllib.request.urlopen(base + info['download_path'], timeout=120) as response, apk.open('wb') as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
        assert apk.stat().st_size == info['size_bytes']
        assert hashlib.sha256(apk.read_bytes()).hexdigest() == info['sha256_checksum']

    remote = f"""
import hashlib, json, pathlib, subprocess
def run(*args): return subprocess.check_output(args, text=True)
core = json.loads(run('docker', 'inspect', {service!r}))[0]
env = dict(row.split('=', 1) for row in core['Config']['Env'] if '=' in row)
print(json.dumps({{
  'manifest': json.loads(pathlib.Path('/opt/apps/cogniva-current-release.json').read_text()),
  'compose_sha256': hashlib.sha256(pathlib.Path('/opt/apps/docker-compose.yml').read_bytes()).hexdigest(),
  'core': {{
    'user': core['Config']['User'],
    'read_only': core['HostConfig']['ReadonlyRootfs'],
    'memory_bytes': core['HostConfig']['Memory'],
    'nano_cpus': core['HostConfig']['NanoCpus'],
    'cap_drop': core['HostConfig']['CapDrop'],
    'health': core['State'].get('Health', {{}}).get('Status'),
    'xai_model': env.get('XAI_MODEL'),
    'xai_base_url': env.get('XAI_BASE_URL'),
    'xai_key_configured': bool(env.get('XAI_API_KEY')),
  }},
  'gate': pathlib.Path('/etc/nginx/cogniva-gate.conf').read_text().strip(),
  'renewal_timer': run('systemctl', 'show', 'cogniva-renew-tls.timer', '-p', 'ActiveState', '-p', 'LastTriggerUSec'),
  'renewal_service': run('systemctl', 'show', 'cogniva-renew-tls.service', '-p', 'Result', '-p', 'ExecMainStatus'),
}}))
"""

    server = json.loads(
        subprocess.check_output(
            [
                'ssh',
                '-o',
                'BatchMode=yes',
                '-o',
                'StrictHostKeyChecking=yes',
                '-o',
                'ConnectTimeout=10',
                ssh_target,
                'python3',
                '-',
            ],
            input=remote,
            text=True,
        )
    )

    manifest = server['manifest']
    assert manifest['revision'] == revision
    assert manifest.get('contract_version') == 5
    assert manifest['apk_sha256'] == info['sha256_checksum']
    assert manifest['apk_size_bytes'] == info['size_bytes']
    assert manifest['runtime_size_bytes'] < 50_000_000
    assert server['core']['read_only'] and server['core']['user'] not in ('', 'root', '0')
    assert server['core']['health'] == 'healthy'
    assert server['gate'] == 'set $cogniva_maintenance 0;'
    assert 'ActiveState=active' in server['renewal_timer'], 'TLS renewal timer is not active'
    assert 'Result=success' in server['renewal_service'], 'Last TLS renewal service run failed'
    if xai_model:
        assert server['core']['xai_model'] == xai_model and server['core']['xai_key_configured']

    result = {
        'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'health': health,
        'ready': ready,
        'metadata_twice_equal': True,
        'anonymous_protected_status': 401,
        'apk': info,
        'tls_remaining_hours': tls_remaining_hours,
        'tls_renewal_verified': True,
        'server': server,
    }

    if output:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    result = verify_production_release(
        args.revision,
        version_name=args.version_name,
        version_code=args.version_code,
        origin=args.origin,
        ssh_target=args.ssh_target,
        service=args.service,
        output=args.output,
        xai_model=args.xai_model,
    )
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
