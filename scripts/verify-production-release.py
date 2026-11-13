"""Cross-platform read-only release acceptance against the public endpoint and VPS."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import urllib.error
import urllib.request

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('revision')
p.add_argument('--version-name', required=True)
p.add_argument('--version-code', type=int, required=True)
p.add_argument('--origin', default=os.environ.get('COGNIVA_PUBLIC_ORIGIN', 'https://198.199.65.103'))
p.add_argument('--ssh-target', default=os.environ.get('COGNIVA_SSH_TARGET', 'root@198.199.65.103'))
p.add_argument('--service', default=os.environ.get('COGNIVA_COMPOSE_SERVICE', 'api-rust-core'))
p.add_argument('--output', type=pathlib.Path)
p.add_argument('--xai-model')
a = p.parse_args()

assert len(a.revision) == 40 and all(c in '0123456789abcdef' for c in a.revision)
base = a.origin.rstrip('/')


def get_json(path: str) -> dict:
    with urllib.request.urlopen(base + path, timeout=60) as response:
        return json.load(response)


health = get_json('/api/v1/health')
ready = get_json('/api/v1/ready')
assert health['revision'] == ready['revision'] == a.revision
assert health['contract_version'] == ready['contract_version'] == 2
assert health['version'] == a.version_name

info = get_json('/api/v1/apk/info')
assert info == get_json('/api/v1/apk/info'), 'Repeated APK metadata differs'
assert info['version_code'] == a.version_code and info['version_name'] == a.version_name

try:
    get_json('/api/v1/exams')
    raise AssertionError('Anonymous protected route accepted')
except urllib.error.HTTPError as error:
    assert error.code == 401, error.code

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
core = json.loads(run('docker', 'inspect', {a.service!r}))[0]
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
            a.ssh_target,
            'python3',
            '-',
        ],
        input=remote,
        text=True,
    )
)

manifest = server['manifest']
assert manifest['revision'] == a.revision
assert manifest['apk_sha256'] == info['sha256_checksum']
assert manifest['apk_size_bytes'] == info['size_bytes']
assert manifest['runtime_size_bytes'] < 50_000_000
assert server['core']['read_only'] and server['core']['user'] not in ('', 'root', '0')
assert server['core']['health'] == 'healthy'
assert server['gate'] == 'set $cogniva_maintenance 0;'
assert 'ActiveState=active' in server['renewal_timer'], 'TLS renewal timer is not active'
assert 'Result=success' in server['renewal_service'], 'Last TLS renewal service run failed'
if a.xai_model:
    assert server['core']['xai_model'] == a.xai_model and server['core']['xai_key_configured']

result = {
    'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'health': health,
    'ready': ready,
    'metadata_twice_equal': True,
    'anonymous_protected_status': 401,
    'apk': info,
    'tls_renewal_verified': True,
    'server': server,
}

if a.output:
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
