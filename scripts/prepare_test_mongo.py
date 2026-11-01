"""Download a hash-verified portable MongoDB for isolated loopback tests; no service installation."""
import hashlib,json,tempfile,urllib.request,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
cache=Path(tempfile.gettempdir())/'cogniva-test-mongo-8.0.34'
cache.mkdir(exist_ok=True)
archive=cache/'runtime.zip'
expected='6b021a0864494e4decf278b36477a29eb36072432773e4e62c2f42c302630db0'
if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:
    urllib.request.urlretrieve('https://fastdl.mongodb.org/windows/mongodb-windows-x86_64-8.0.34.zip',archive)
if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:raise RuntimeError('MongoDB archive hash mismatch')
with zipfile.ZipFile(archive) as z:
    for member in z.infolist():
        target=(cache/member.filename).resolve()
        if not target.is_relative_to(cache.resolve()):raise RuntimeError('Invalid archive path')
    z.extractall(cache)
exe=next(cache.rglob('mongod.exe'))
db=cache/'synthetic-db'
db.mkdir(exist_ok=True)
out=root/'docs/remediation-validation';out.mkdir(exist_ok=True)
result={'exe':str(exe),'dbpath':str(db),'port':27048,'sha256':expected,'source':'https://downloads.mongodb.org/full.json'}
(out/'mongo-runtime.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result))
