"""Read-only inventory/backup through the existing trusted VPS SSH connection.
Credentials stay in process memory. Private BSON backups live outside the repo.
"""
import argparse,datetime,hashlib,json,os,pathlib,socket,subprocess,time,zipfile
import socks
from bson import BSON, json_util
from pymongo import MongoClient

p=argparse.ArgumentParser();p.add_argument('--backup',action='store_true');p.add_argument('--label',default='before');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
ssh=['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=5','root@198.199.65.103']
core=json.loads(subprocess.check_output(ssh+['docker','inspect','api-rust-core']))[0]
env=dict(item.split('=',1) for item in core['Config']['Env'] if '=' in item)
assert env.get('MONGODB_DB','CNU')=='CNU','Unexpected production database'
tunnel=subprocess.Popen(ssh[:-1]+['-N','-D','127.0.0.1:27049',ssh[-1]],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
try:
    for _ in range(30):
        try:
            with socket.create_connection(('127.0.0.1',27049),timeout=.2):break
        except OSError:time.sleep(.1)
    # This dedicated utility process routes every Mongo TCP socket through SSH.
    # No application runtime or machine-wide proxy settings are changed.
    socks.setdefaultproxy(socks.SOCKS5,'127.0.0.1',27049)
    socket.socket=socks.socksocket
    client=MongoClient(env['MONGODB_URI'],serverSelectionTimeoutMS=20000,connectTimeoutMS=10000,appname='CognivaReadOnlyAudit')
    db=client['CNU'];db.command('ping')
    now=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup_dir=pathlib.Path(os.environ['LOCALAPPDATA'])/'Cogniva'/'production-backups'/now
    if a.backup:
        backup_dir.mkdir(parents=True)
        subprocess.run(['icacls',str(backup_dir),'/inheritance:r','/grant:r',os.environ['USERNAME']+':(OI)(CI)F'],check=True,stdout=subprocess.DEVNULL)
    result={'captured_at':now,'database':'CNU','access':'read-only','collections':{},'protected_history':{},'canonical_questions':0,
        'jwt_matches_public_example':env.get('JWT_SECRET') in ['gere_uma_string_aleatoria_forte_para_producao','cogniva-super-secret-change-in-production','super-secret-jwt-key-change-me']}
    descriptions={item['name']:item for item in db.list_collections()}
    if a.backup:(backup_dir/'collection-options.json').write_text(json_util.dumps(descriptions,indent=2),encoding='utf-8')
    for name in sorted(descriptions):
        if descriptions[name].get('type')=='view':
            result['collections'][name]='view definition preserved in private backup'
            continue
        collection=db[name];digest=hashlib.sha256();count=0;events=0
        archive=zipfile.ZipFile(backup_dir/(name+'.zip'),'w',compression=zipfile.ZIP_DEFLATED) if a.backup else None
        try:
            output=archive.open(name+'.bson','w') if archive else None
            for document in collection.find({}).sort('_id',1).batch_size(100):
                count+=1
                if output:output.write(BSON.encode(document))
                if name=='userActivityLog':
                    protected={key:document.get(key) for key in ['_id','user_id','question_id','pastSessions','user_note']}
                    digest.update(BSON.encode(protected));events+=len(document.get('pastSessions',[]))
                if name=='todasQuestoes' and document.get('canonical_topic_id'):result['canonical_questions']+=1
            if output:output.close()
            if archive:archive.writestr('indexes.json',json.dumps(list(collection.list_indexes()),default=str,indent=2))
        finally:
            if archive:archive.close()
        result['collections'][name]=count
        if name=='userActivityLog':result['protected_history']={'documents':count,'events':events,'sha256':digest.hexdigest()}
    client.close()
    if a.backup:
        result['private_backup_directory']=str(backup_dir)
        (backup_dir/'inventory.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    out=root/'docs/remediation-validation'/('production-'+a.label+'.json')
    out.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))
finally:
    tunnel.terminate()
    try:tunnel.wait(timeout=5)
    except subprocess.TimeoutExpired:tunnel.kill()
