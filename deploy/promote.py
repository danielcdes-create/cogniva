"""Promote a verified immutable bundle on the VPS, with a tested recovery path.

No builds, database writes or replacement of the shared Compose configuration.
Usage: python3 promote.py /opt/apps/cogniva-releases/<git-sha>
"""
import hashlib,json,os,pathlib,re,subprocess,sys,time,urllib.request,urllib.error

ROOT=pathlib.Path('/opt/apps')
COMPOSE=ROOT/'docker-compose.yml'
OVERRIDE=ROOT/'cogniva-runtime.override.json'
SERVICE='api-rust-core'

def run(*args):
    return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT)
def inspect():
    return json.loads(run('docker','inspect',SERVICE))[0]
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def atomic_json(path,value):
    temp=path.with_name(path.name+'.tmp');temp.write_text(json.dumps(value,indent=2));os.replace(temp,path)
def symlink(target,path):
    temp=path.with_name(path.name+'.next');temp.unlink(missing_ok=True);temp.symlink_to(target);os.replace(temp,path)
def compose_up():
    # Deliberately named service only: FIN, Asbuildo and Spamshield are untouched.
    print(run('docker','compose','-p','apps','-f',str(COMPOSE),'-f',str(OVERRIDE),'up','-d','--no-deps','--force-recreate',SERVICE))
def gate(paused):
    path=pathlib.Path('/etc/nginx/cogniva-gate.conf')
    temp=path.with_name(path.name+'.tmp')
    temp.write_text('set $cogniva_maintenance '+('1' if paused else '0')+';\n',encoding='utf-8')
    os.replace(temp,path)
    run('nginx','-t');run('systemctl','reload','nginx')
    for _ in range(20):
        try:urllib.request.urlopen('https://198.199.65.103/api/v1/exams',timeout=3)
        except urllib.error.HTTPError as error:
            if error.code==(503 if paused else 401):return
        time.sleep(.25)
    raise RuntimeError('Nginx gate did not reach its expected state')
def ready(revision):
    for _ in range(45):
        try:
            value=json.load(urllib.request.urlopen('http://127.0.0.1:8001/api/v1/ready',timeout=5))
            if value.get('status')=='ready' and value.get('revision')==revision:return
        except Exception:pass
        time.sleep(2)
    raise RuntimeError('Candidate did not become ready with the expected revision')

release=pathlib.Path(sys.argv[1]).resolve()
assert release.parent==ROOT/'cogniva-releases' and re.fullmatch('[0-9a-f]{40}',release.name),'Invalid release directory'
manifest=json.loads((release/'release.json').read_text())
assert manifest['revision']==release.name
image=manifest['image']
assert re.fullmatch(r'ghcr\.io/danielcdes-create/api-rust-core@sha256:[0-9a-f]{64}',image),'Immutable image required'
apk=release/'cogniva.apk';metadata=release/'output-metadata.json'
assert sha(apk)==manifest['apk_sha256'] and apk.stat().st_size==manifest['apk_size_bytes']
element=json.loads(metadata.read_text())['elements'][0]
assert element['versionCode']==manifest['version_code'] and element['versionName']==manifest['version_name']
before=inspect()
old_health=json.load(urllib.request.urlopen('http://127.0.0.1:8001/api/v1/health',timeout=5))
protocol_migration=old_health.get('contract_version',1)!=manifest['contract_version']
assert before['Config']['Labels'].get('com.docker.compose.project')=='apps'
assert before['Config']['Labels'].get('com.docker.compose.service')==SERVICE
others={item['Name']:item['Id'] for item in json.loads(run('docker','inspect',*run('docker','ps','-q').split())) if item['Name']!='/'+SERVICE}
compose_hash=sha(COMPOSE)
current=ROOT/'apk'/'current';old_target=os.readlink(current) if current.is_symlink() else None
old_override=OVERRIDE.read_bytes() if OVERRIDE.exists() else None
old_image=before['Image']
old_details=json.loads(run('docker','image','inspect',old_image))[0]
old_runtime_ref=next(iter(old_details.get('RepoDigests',[])),old_image)
previous={'image_id':old_image,'image':before['Config']['Image'],'current_target':old_target,'compose_sha256':compose_hash,'promoted_revision':release.name}
atomic_json(release/'previous.json',previous)
os.chmod(release/'previous.json',0o600)
print(run('docker','pull',image))
size=json.loads(run('docker','image','inspect',image))[0]['Size']
assert size<50_000_000,f'Runtime exceeds 50 MB: {size}'
override={'services':{SERVICE:{'image':image,'environment':{'APK_STORAGE_PATH':'/app/apk/current/cogniva.apk','APK_METADATA_PATH':'/app/apk/current/output-metadata.json'},'read_only':True,'tmpfs':['/tmp:size=16m'],'security_opt':['no-new-privileges:true'],'cap_drop':['ALL'],'mem_limit':'160m','cpus':0.75,'pids_limit':128,'logging':{'driver':'json-file','options':{'max-size':'10m','max-file':'3'}}}}}
controlled_failure=False
try:
    gate(True)
    atomic_json(OVERRIDE,override)
    # The container already binds /opt/apps/apk. The single symlink controls
    # APK and metadata together; immutable release bytes are copied once.
    apk_release=ROOT/'apk'/'releases'/release.name
    apk_release.mkdir(parents=True,exist_ok=True)
    for file in [apk,metadata]:
        target=apk_release/file.name
        if target.exists():assert sha(target)==sha(file),'An immutable release differs'
        else:target.write_bytes(file.read_bytes());target.chmod(0o644)
    symlink('releases/'+release.name,current)
    compose_up();ready(release.name)
    info=json.load(urllib.request.urlopen('http://127.0.0.1:8001/api/app/latest-apk',timeout=10))
    assert info['sha256_checksum']==manifest['apk_sha256'] and info['version_code']==manifest['version_code']
    response=urllib.request.urlopen('https://198.199.65.103/api/v1/health',timeout=10)
    assert json.load(response)['revision']==release.name
    assert sha(COMPOSE)==compose_hash,'Shared Compose changed'
    for name,container_id in others.items():assert json.loads(run('docker','inspect',name))[0]['Id']==container_id,'Another application was replaced'
    if '--exercise-rollback' in sys.argv:
        controlled_failure=True
        raise RuntimeError('CONTROLLED_SMOKE_FAILURE_FOR_ROLLBACK')
    print(run("docker","exec",SERVICE,"/usr/local/bin/app","--prepare-question-storage"))
    gate(False)  # The first moment client writes can reach the verified candidate.
    atomic_json(ROOT/'cogniva-current-release.json',{**manifest,'runtime_size_bytes':size,'previous_image_id':old_image,'previous_image':before['Config']['Image']})
    print(json.dumps({'promoted':release.name,'runtime_size_bytes':size,'other_containers_preserved':True}))
except BaseException:
    restored_previous=False
    try:
        gate(True)
        if old_target is not None:symlink(old_target,current)
        else:current.unlink(missing_ok=True)
        if old_override is not None:OVERRIDE.write_bytes(old_override)
        else:atomic_json(OVERRIDE,{'services':{SERVICE:{'image':old_runtime_ref}}})
        compose_up()
        # Old baseline has health but not readiness. Test actual HTTP recovery.
        for retry in range(30):
            try:
                with urllib.request.urlopen('http://127.0.0.1:8001/api/v1/health',timeout=5) as response:
                    assert response.status==200
                restored_previous=True
                print('ROLLBACK_HEALTHY');break
            except Exception:
                if retry==29:raise RuntimeError('Rollback requires operator attention')
                time.sleep(2)
    finally:
        if restored_previous or not protocol_migration:gate(False)
    if controlled_failure:
        assert inspect()['Image']==old_image,'Rollback did not restore the previous image'
        assert (os.readlink(current) if current.is_symlink() else None)==old_target
        assert sha(COMPOSE)==compose_hash
        for name,container_id in others.items():assert json.loads(run('docker','inspect',name))[0]['Id']==container_id
        print('CONTROLLED_ROLLBACK_VERIFIED')
    else:raise
