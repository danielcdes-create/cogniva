"""Read-only release acceptance against the public endpoint and trusted VPS."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import urllib.error
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('revision')
p.add_argument('--version-name', default='1.4.0')
p.add_argument('--version-code', type=int, default=7)
p.add_argument('--evidence-dir', type=pathlib.Path)
p.add_argument('--baseline', type=pathlib.Path)
a = p.parse_args()
assert len(a.revision) == 40 and all(c in '0123456789abcdef' for c in a.revision)
root = pathlib.Path(__file__).resolve().parents[1]
base = 'https://198.199.65.103'

def get(path):
    with urllib.request.urlopen(base + path, timeout=60) as response:
        return json.load(response)

health, ready = get('/api/v1/health'), get('/api/v1/ready')
assert health['revision'] == ready['revision'] == a.revision
assert health['contract_version'] == ready['contract_version'] == 2
assert health['version'] == a.version_name
info = get('/api/app/latest-apk')
assert info == get('/api/app/latest-apk'), 'Repeated metadata differs'
assert info['version_code'] == a.version_code and info['version_name'] == a.version_name
try:
    get('/api/v1/exams')
    raise AssertionError('Anonymous protected route accepted')
except urllib.error.HTTPError as error:
    assert error.code == 401, error.code

directory = pathlib.Path(os.environ['LOCALAPPDATA']) / 'Cogniva' / 'releases' / a.revision
directory.mkdir(parents=True, exist_ok=True)
apk = directory / f'cogniva-{a.version_name}-{a.version_code}.apk'
partial = apk.with_suffix('.part')
with urllib.request.urlopen(base + info['download_path'], timeout=120) as response, partial.open('wb') as output:
    while chunk := response.read(1024 * 1024):
        output.write(chunk)
assert partial.stat().st_size == info['size_bytes']
assert hashlib.sha256(partial.read_bytes()).hexdigest() == info['sha256_checksum']
partial.replace(apk)

remote = r'''
import hashlib,json,pathlib,subprocess
def run(*args):return subprocess.check_output(args,text=True)
core=json.loads(run('docker','inspect','api-rust-core'))[0]
env=dict(row.split('=',1) for row in core['Config']['Env'] if '=' in row)
containers=json.loads(run('docker','inspect',*run('docker','ps','-q').split()))
print(json.dumps({
 'manifest':json.loads(pathlib.Path('/opt/apps/cogniva-current-release.json').read_text()),
 'compose_sha256':hashlib.sha256(pathlib.Path('/opt/apps/docker-compose.yml').read_bytes()).hexdigest(),
 'containers':[{'name':c['Name'],'id':c['Id'],'image_id':c['Image']} for c in containers],
 'core':{'user':core['Config']['User'],'read_only':core['HostConfig']['ReadonlyRootfs'],
         'memory_bytes':core['HostConfig']['Memory'],'nano_cpus':core['HostConfig']['NanoCpus'],
         'cap_drop':core['HostConfig']['CapDrop'],'health':core['State'].get('Health',{}).get('Status'),
         'gemini_model':env.get('GEMINI_MODEL')},
 'gate':pathlib.Path('/etc/nginx/cogniva-gate.conf').read_text().strip(),
 'stats':[json.loads(row) for row in run('docker','stats','--no-stream','--format','{{json .}}').splitlines()],
 'renewal_timer':run('systemctl','show','cogniva-renew-tls.timer','-p','ActiveState','-p','LastTriggerUSec'),
 'renewal_service':run('systemctl','show','cogniva-renew-tls.service','-p','Result','-p','ExecMainStatus')
}))
'''
server = json.loads(subprocess.check_output([
    'ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
    '-o', 'ConnectTimeout=5', 'root@198.199.65.103', 'python3', '-'
], input=remote, text=True))
baseline = a.baseline or root / 'docs/remediation-validation/server-before.json'
before = json.loads(baseline.read_text(encoding='utf-8-sig'))
before = before.get('server', before)
assert server['compose_sha256'] == before['compose_sha256']
after_ids = {row['name']: row['id'] for row in server['containers']}
for row in before['containers']:
    if row['name'] != '/api-rust-core':
        assert after_ids[row['name']] == row['id'], row['name']
manifest = server['manifest']
assert manifest['revision'] == a.revision
assert manifest['apk_sha256'] == info['sha256_checksum']
assert manifest['apk_size_bytes'] == info['size_bytes']
assert manifest['runtime_size_bytes'] < 50_000_000
assert server['core']['read_only'] and server['core']['user'] != 'root'
assert server['core']['gemini_model'] == 'gemini-2.5-flash-lite'
assert server['core']['health'] == 'healthy'
assert server['gate'] == 'set $cogniva_maintenance 0;'
result = {
    'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'health': health, 'ready': ready, 'metadata_twice_equal': True,
    'anonymous_protected_status': 401, 'apk': info, 'local_apk': str(apk),
    'compose_and_other_containers_preserved': True, 'server': server
}
evidence = a.evidence_dir or root / 'docs/remediation-validation'
evidence.mkdir(parents=True, exist_ok=True)
destination = evidence / 'production-release.json'
destination.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
