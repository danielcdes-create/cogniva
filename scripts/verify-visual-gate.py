"""Actual second verify, then valid 1x1 negative control; restore exact golden bytes."""
import hashlib,json,os,pathlib,subprocess,struct,zlib,sys
root=pathlib.Path(__file__).resolve().parents[1];app=root/'android-app';out=root/'docs/remediation-validation'
gradle=['cmd','/c','gradlew.bat',':app:verifyPaparazziDebug','--tests','br.com.concursoprep.app.QuestionPreviewMatrixScreenshotTest','--rerun-tasks','--console=plain']
def run(name):
    with (out/name).open('w',encoding='utf-8') as log:return subprocess.run(gradle,cwd=app,stdout=log,stderr=subprocess.STDOUT).returncode
if '--negative-only' not in sys.argv:
    assert run('visual-verify-2.log')==0,'Second actual verify failed'
else:
    assert 'BUILD SUCCESSFUL' in (out/'visual-verify-2.log').read_text(encoding='utf-8')
golden=next(p for p in (app/'app/src/test/snapshots/images').iterdir() if p.name.endswith('_font_scale_2_0.png'))
original=golden.read_bytes();digest=hashlib.sha256(original).hexdigest()
try:
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    pixel=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\x00\x00\x00\xff'))+chunk(b'IEND',b'')
    golden.write_bytes(pixel)
    code=run('visual-negative-control.log')
    assert code!=0,'A changed golden incorrectly passed'
    log=(out/'visual-negative-control.log').read_text(encoding='utf-8')
    assert 'QuestionPreviewMatrixScreenshotTest' in log and 'FAILED' in log,'Failure did not reach screenshot comparison'
    xml=''.join(p.read_text(encoding='utf-8') for p in (app/'app/build/test-results/testDebugUnitTest').glob('TEST-*QuestionPreviewMatrixScreenshotTest*.xml'))
    assert 'Images differ' in xml or 'different sizes' in xml or 'image dimensions' in xml.lower(),'Failure did not prove pixel/dimension comparison'
finally:
    golden.write_bytes(original)
assert hashlib.sha256(golden.read_bytes()).hexdigest()==digest
(out/'visual-gate.json').write_text(json.dumps({'second_verify':'executed and passed','negative_control':'valid 1x1 golden rejected','golden':golden.name,'restored_sha256':digest},indent=2),encoding='utf-8')
print('Visual gate passed; exact golden restored.')
