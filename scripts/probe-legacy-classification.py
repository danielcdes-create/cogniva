"""Operator acceptance: read-only by default; --trigger-one authorizes one paid demand.

No attempts, tutor calls, tokens, question contents or user history enter evidence.
"""
import argparse
import json
import pathlib
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('user_id',type=int)
mode=p.add_mutually_exclusive_group()
mode.add_argument('--trigger-one',action='store_true')
mode.add_argument('--check-reuse')
p.add_argument('--output',required=True,type=pathlib.Path)
a=p.parse_args()
assert a.user_id>0
if a.check_reuse:assert len(a.check_reuse)==24 and all(c in '0123456789abcdef' for c in a.check_reuse)
remote=r'''
import base64,datetime,hashlib,hmac,json,subprocess,sys,time,urllib.request,uuid
owner=int(sys.argv[1]);action=sys.argv[2];qid=sys.argv[3]
core=json.loads(subprocess.check_output(['docker','inspect','api-rust-core']))[0]
env=dict(row.split('=',1) for row in core['Config']['Env'] if '=' in row)
def enc(v):return base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).rstrip(b'=')
unsigned=enc({'alg':'HS256','typ':'JWT'})+b'.'+enc({'sub':str(owner),'user_id':owner,'purpose':'session','exp':int(time.time())+600,'nonce':str(uuid.uuid4())})
token=(unsigned+b'.'+base64.urlsafe_b64encode(hmac.new(env['JWT_SECRET'].encode(),unsigned,hashlib.sha256).digest()).rstrip(b'=')).decode()
def request(path,payload=None):
    req=urllib.request.Request('http://127.0.0.1:8001'+path,data=None if payload is None else json.dumps(payload).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)
def status():
    raw=subprocess.check_output(['docker','exec','api-rust-core','/usr/local/bin/app','--legacy-classification-status'],text=True)
    return json.loads(raw.splitlines()[-1])
def cached(identifier):return request('/api/v1/questions/cache-state',{'question_ids':[identifier]})['questions'][0]['question']
before=status();report={'captured_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'action':action,'before':before,'attempts_submitted':0}
if action=='trigger-one':
    assert before['enabled'] and before['queued']==0,'Inspect existing demand before triggering a controlled request'
    assert before['daily_reserved']<before['daily_limit'],'Daily budget exhausted; do not reset it'
    rows=request('/api/v1/quiz/generate',{'user_id':owner,'mode':'SHUFFLE','quantity':1,'trunk_focus_slugs':[]})
    assert len(rows)==1
    qid=rows[0]['id'];report['question_id']=qid;report['question_revision']=rows[0]['question_revision']
    deadline=time.monotonic()+50
    while time.monotonic()<deadline:
        after=status()
        if after['queued']==0 and after['attempted']>before['attempted'] and after['jobs'].get('RESERVED',0)==0:break
        time.sleep(2)
    q=cached(qid)
    report.update(after=status(),binding_verified=bool(q and q['knowledge_binding_verified']),canonical_subject_id=q.get('canonical_subject_id') if q else None,canonical_topic_id=q.get('canonical_topic_id') if q else None)
elif action=='check-reuse':
    q=cached(qid);assert q and q['knowledge_binding_verified'] and q['canonical_topic_id']
    rows=request('/api/v1/quiz/generate',{'user_id':owner,'mode':'SHUFFLE','quantity':1,'syllabus_topic_id':q['canonical_topic_id'],'trunk_focus_slugs':[]})
    assert rows and all(row['knowledge_binding_verified'] for row in rows)
    time.sleep(6)
    after=status();assert after['daily_reserved']==before['daily_reserved'] and after['attempted']==before['attempted'] and after['queued']==before['queued'],'Concurrent demand or unexpected new reservation; inspect evidence'
    report.update(question_id=qid,after=after,reused_without_new_reservation=True)
else:report['after']=status()
print(json.dumps(report,ensure_ascii=False))
'''
result=subprocess.run(['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=5','root@198.199.65.103','python3','-',str(a.user_id),'trigger-one' if a.trigger_one else 'check-reuse' if a.check_reuse else 'status',a.check_reuse or 'none'],input=remote,text=True,encoding='utf-8',capture_output=True,check=True)
report=json.loads(result.stdout)
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
