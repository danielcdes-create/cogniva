"""One authorized real generation, persisted-resource reuse, sanitized evidence."""
import argparse, base64, hashlib, json, pathlib, socket, subprocess, time
import socks
from bson import BSON
from pymongo import MongoClient

p=argparse.ArgumentParser();p.add_argument('user_id',type=int);a=p.parse_args();assert a.user_id>0
root=pathlib.Path(__file__).resolve().parents[1]
ssh=['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=5','root@198.199.65.103']
core=json.loads(subprocess.check_output(ssh+['docker','inspect','api-rust-core']))[0]
env=dict(row.split('=',1) for row in core['Config']['Env'] if '=' in row)
assert env.get('XAI_MODEL')=='grok-4.7' and env.get('XAI_API_KEY')
tunnel=subprocess.Popen(ssh[:-1]+['-N','-D','127.0.0.1:27049',ssh[-1]],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
try:
    for _ in range(30):
        try:
            with socket.create_connection(('127.0.0.1',27049),timeout=.2):break
        except OSError:time.sleep(.1)
    socks.setdefaultproxy(socks.SOCKS5,'127.0.0.1',27049);socket.socket=socks.socksocket
    client=MongoClient(env['MONGODB_URI'],serverSelectionTimeoutMS=20000,appname='CognivaAiCacheValidation');db=client[env.get('MONGODB_DB','CNU')]
    blocked={r.get('questao_id') for r in db.reportedQuestoes.find({'fix.status_solved':False,'problemas':{'$in':['Questão com Erros de Conteúdo','Alternativas Corretas em Duplicidade','Alternativas Mal Formuladas','Enunciado c/ Erros de Conteúdo','Alternativas Incorretas','Questão Completamente Problemática']}})}
    target=None
    for q in db.todasQuestoes.find({'$or':[{'mnemonico':{'$exists':False}},{'mnemonico':None},{'mnemonico':''}]}):
        if q['_id'] in blocked or (q.get('legal_validity') or {}).get('status','ACTIVE') not in ['ACTIVE','VIGENTE']:continue
        answers=q.get('answers',[]);correct=q.get('correct_answers',[])
        if len(correct)==1 and sum(isinstance(x,str) and x.strip()==correct[0].strip() for x in answers)==1:
            index=next(i for i,x in enumerate(answers) if x.strip()==correct[0].strip())
            if q.get('correct_answer_indices') and q['correct_answer_indices']!=[index]:continue
            target=q;break
    assert target is not None,'No eligible missing mnemonic; do not erase cached content to force a test'
    before=BSON.encode(target)
    remote=r'''
import base64,hashlib,hmac,json,subprocess,sys,time,urllib.request,uuid
owner=int(sys.argv[1]);qid=sys.argv[2]
c=json.loads(subprocess.check_output(['docker','inspect','api-rust-core']))[0];e=dict(x.split('=',1) for x in c['Config']['Env'] if '=' in x)
def enc(v):return base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).rstrip(b'=')
unsigned=enc({'alg':'HS256','typ':'JWT'})+b'.'+enc({'sub':str(owner),'user_id':owner,'purpose':'session','exp':int(time.time())+300,'nonce':str(uuid.uuid4())})
token=(unsigned+b'.'+base64.urlsafe_b64encode(hmac.new(e['JWT_SECRET'].encode(),unsigned,hashlib.sha256).digest()).rstrip(b'=')).decode()
results=[]
for _ in range(2):
    request=urllib.request.Request('http://127.0.0.1:8001/api/v1/questions/'+qid+'/ai/mnemonic',data=b'{}',headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=60) as r:body=json.load(r)
    text=body['content_markdown'];assert text.strip()
    results.append({'from_cache':body['from_cache'],'content_sha256':hashlib.sha256(text.encode()).hexdigest(),'characters':len(text)})
assert results[0]['from_cache'] is False and results[1]['from_cache'] is True
assert results[0]['content_sha256']==results[1]['content_sha256']
print(json.dumps(results))
'''
    import shlex
    command='python3 -c '+shlex.quote(remote)+' '+str(a.user_id)+' '+str(target['_id'])
    results=json.loads(subprocess.check_output(ssh+[command],text=True))
    after=db.todasQuestoes.find_one({'_id':target['_id']})
    assert hashlib.sha256(after['mnemonico'].encode()).hexdigest()==results[0]['content_sha256']
    original=dict(target);current=dict(after);original.pop('mnemonico',None);current.pop('mnemonico',None)
    assert original==current,'An unrelated question field changed'
    report={'model':env['XAI_MODEL'],'resource':'mnemonic','question_id':str(target['_id']),'requests':results,'mongo_matches_response':True,'unrelated_fields_preserved':True,'question_before_bson_sha256':hashlib.sha256(before).hexdigest(),'question_after_bson_sha256':hashlib.sha256(BSON.encode(after)).hexdigest(),'intentional_change':'mnemonico only; one real generation authorized by the user'}
    out=root/'docs/remediation-validation/1.4.3';out.mkdir(parents=True,exist_ok=True);(out/'xai-live-cache.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report,indent=2));client.close()
finally:
    tunnel.terminate()
    try:tunnel.wait(timeout=5)
    except subprocess.TimeoutExpired:tunnel.kill()
