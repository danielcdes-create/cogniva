"""Prepare the documented portable Temurin 21 using the official GitHub release and checksum."""
import hashlib,json,subprocess,tempfile,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
cache=Path(tempfile.gettempdir())/'cogniva-temurin21'
cache.mkdir(exist_ok=True)
gh='gh'
metadata=json.loads(subprocess.check_output([gh,'api','repos/adoptium/temurin21-binaries/releases/latest'],text=True,encoding='utf-8'))
package=next(a for a in metadata['assets'] if a['name'].startswith('OpenJDK21U-jdk_x64_windows_hotspot_') and a['name'].endswith('.zip'))
subprocess.run([gh,'release','download',metadata['tag_name'],'--repo','adoptium/temurin21-binaries','--pattern',package['name'],'--pattern',package['name']+'.sha256.txt','--dir',str(cache),'--skip-existing'],check=True)
archive=cache/package['name']
checksum=(cache/(package['name']+'.sha256.txt')).read_text(encoding='utf-8').split()[0].lower()
if hashlib.sha256(archive.read_bytes()).hexdigest()!=checksum:raise RuntimeError('JDK archive checksum mismatch; cached archive preserved for investigation')
with zipfile.ZipFile(archive) as z:
    for member in z.infolist():
        if not (cache/member.filename).resolve().is_relative_to(cache.resolve()):raise RuntimeError('Invalid archive path')
    z.extractall(cache)
java=next(cache.glob('*/bin/java.exe'))
result={'java_home':str(java.parents[1]),'version':metadata['tag_name'],'sha256':checksum,'source':metadata['html_url']}
out=root/'docs/remediation-validation';out.mkdir(exist_ok=True)
(out/'jdk-runtime.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result))
