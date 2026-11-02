"""Capture current shared-server identities before a release (read-only SSH)."""
import argparse
import datetime
import json
import pathlib
import subprocess

p = argparse.ArgumentParser()
p.add_argument("destination", type=pathlib.Path)
a = p.parse_args()
remote = r'''
import hashlib,json,pathlib,subprocess
ids=subprocess.check_output(['docker','ps','-q'],text=True).split()
rows=json.loads(subprocess.check_output(['docker','inspect',*ids]))
print(json.dumps({'compose_sha256':hashlib.sha256(pathlib.Path('/opt/apps/docker-compose.yml').read_bytes()).hexdigest(),'containers':[{'name':c['Name'],'id':c['Id'],'image_id':c['Image']} for c in rows]}))
'''
server = json.loads(subprocess.check_output([
    "ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
    "-o", "ConnectTimeout=5", "root@198.199.65.103", "python3", "-"
], input=remote, text=True))
result = {"captured_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "access": "read-only", "server": server}
a.destination.parent.mkdir(parents=True, exist_ok=True)
if a.destination.exists():
    raise SystemExit("Refusing to replace an existing baseline")
a.destination.write_text(json.dumps(result, indent=2), encoding="utf-8")
print(f"Captured {len(server['containers'])} container identities: {a.destination}")
