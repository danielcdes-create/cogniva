from pathlib import Path
import hashlib
import json
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo

out = Path(__file__).resolve().parent
root = out.parents[2]
data = json.loads((out / 'evidence.json').read_text(encoding='utf-8'))
mismatches = []
for row in data['source_fingerprints']:
    p = root / row['path']
    if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest() != row['sha256']:
        mismatches.append(row['path'])
diff = subprocess.run(['git','diff','--name-only','--','backend-rust','android-app','.github','README.md','docker-compose.yml','deploy'],cwd=root,capture_output=True,text=True,check=True)
apk = root / 'android-app/app/build/outputs/apk/debug/app-debug.apk'
result = {
    'captured_at':datetime.now(ZoneInfo('America/Sao_Paulo')).isoformat(),
    'checked_source_files':len(data['source_fingerprints']),
    'source_fingerprint_mismatches':mismatches,
    'tracked_application_source_diff':diff.stdout.strip(),
    'local_apk_sha256_after_jdk17_build':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'local_apk_bytes':apk.stat().st_size,
    'notes':'Source files unchanged; audit artifacts only. Initial working tree note renames retained. No publication or remote data writes.'
}
(out / 'final-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
if mismatches or diff.stdout.strip():
    raise SystemExit('Source verification requires review')
