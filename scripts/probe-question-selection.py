"""Read-only authenticated question/learning probes; never submit attempts.

The trusted VPS signs an ephemeral diagnostic session in memory. Credentials,
tokens, question contents and learning events never enter the report.
"""
import argparse
import json
import pathlib
import subprocess

p = argparse.ArgumentParser()
p.add_argument('user_id', type=int)
p.add_argument('--output', type=pathlib.Path, required=True)
p.add_argument('--assert-fixed', action='store_true')
p.add_argument('--expected-version')
p.add_argument('--assert-catalog', action='store_true')
a = p.parse_args()
assert a.user_id > 0
remote = r'''
import base64,collections,datetime,hashlib,hmac,json,subprocess,sys,time,urllib.request,urllib.error,uuid
owner=int(sys.argv[1])
core=json.loads(subprocess.check_output(['docker','inspect','api-rust-core']))[0]
env=dict(row.split('=',1) for row in core['Config']['Env'] if '=' in row)
def enc(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=')
unsigned=enc({'alg':'HS256','typ':'JWT'})+b'.'+enc({'sub':str(owner),'user_id':owner,'purpose':'session','exp':int(time.time())+600,'nonce':str(uuid.uuid4())})
token=(unsigned+b'.'+base64.urlsafe_b64encode(hmac.new(env['JWT_SECRET'].encode(),unsigned,hashlib.sha256).digest()).rstrip(b'=')).decode()
def request(path,payload=None):
    req=urllib.request.Request('http://127.0.0.1:8001'+path,data=None if payload is None else json.dumps(payload).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=30) as response:return response.status,json.load(response)
    except urllib.error.HTTPError as error:
        raw=error.read().decode()
        try:return error.code,json.loads(raw)
        except ValueError:return error.code,{'message':raw[:600]}
_,health=request('/api/v1/health')
knowledge_status,knowledge=request('/api/v1/catalog/knowledge')
legacy_status,legacy=request('/api/v1/catalog/trunks')
catalog={'knowledge_status':knowledge_status,'legacy_status':legacy_status}
if knowledge_status==200:
    catalog.update(catalog_id=knowledge['catalog_id'],catalog_version=knowledge['catalog_version'],subjects=len(knowledge['subjects']),topics=len(knowledge['topics']),trunks=len(knowledge['trunks']))
if legacy_status==200:
    catalog.update(projected_subjects=len(legacy['subjects']),projected_trunks=len(legacy['trunks']),preserved_templates=len(legacy['exams']))
status,exams=request('/api/v1/exams');assert status==200
archived=next((e for e in exams if e['status']=='ARQUIVADO'),None)
active=next((e for e in exams if e['status']!='ARQUIVADO' and e['is_active_in_ladder']),None)
cases=[]
def probe(label,mode,exam=None,discipline=None):
    payload={'user_id':owner,'mode':mode,'quantity':10,'trunk_focus_slugs':[]}
    if exam is not None:payload['contest_id']=exam['_id']
    if discipline is not None:payload['disciplina']=discipline
    status,body=request('/api/v1/quiz/generate',payload)
    result={'case':label,'mode':mode,'scope':'general' if exam is None else exam['status'],'status':status}
    if isinstance(body,list):
        result['count']=len(body)
        result['subjects']=dict(collections.Counter(q['disciplina'] for q in body))
        result['valid_dtos']=all(q['question_revision'] and len(q['correct_answer_indices'])==1 and 0<=q['correct_answer_indices'][0]<len(q['answers']) for q in body)
        assert result['valid_dtos']
    else:result['message']=body.get('message')
    cases.append(result)
for mode in ['STANDARD','SHUFFLE','ERROR_NOTEBOOK','SRS_REVIEW','MOST_BADASS','COMMON_TRUNK']:
    probe(mode.lower()+'_general',mode)
    if archived is not None:probe(mode.lower()+'_archived_context',mode,archived)
if active is not None:
    probe('simulado_active_grade','SIMULADO',active)
    for subject in active['subjects']:probe('discipline:'+subject['display_name'],'STANDARD',active,subject['display_name'])
records=[];cursor=None
while True:
    status,page=request('/api/v1/learning/state'+('?after='+cursor if cursor else ''));assert status==200
    records.extend(page['records']);cursor=page['next_cursor']
    if cursor is None:break
now=datetime.datetime.now(datetime.timezone.utc)
availability_known=all('practice_available' in r for r in records)
available=[r for r in records if r.get('practice_available') is True]
learning={'records_preserved':len(records),'availability_known':availability_known,
 'available_records':len(available) if availability_known else None,
 'actionable_srs':sum(r.get('review_required',False) or datetime.datetime.fromisoformat(r['next_review_at'])<=now for r in available) if availability_known else None,
 'actionable_errors':sum(r['in_error_notebook'] for r in available) if availability_known else None}
print(json.dumps({'captured_at':now.isoformat(),'access':'read-only; no attempts submitted','health':health,'catalog':catalog,'cases':cases,'learning':learning},ensure_ascii=False))
'''
result = subprocess.run([
    'ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
    '-o', 'ConnectTimeout=5', 'root@198.199.65.103', 'python3', '-', str(a.user_id)
], input=remote, text=True, encoding='utf-8', capture_output=True, check=True)
report = json.loads(result.stdout)
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
if a.assert_fixed:
    cases = {case['case']: case for case in report['cases']}
    assert report['health']['version'] == (a.expected_version or '1.4.2')
    for mode in ['shuffle', 'error_notebook', 'srs_review', 'most_badass']:
        general = cases[mode + '_general']
        assert general['status'] == 200 and general['count'] > 0
        legacy = cases.get(mode + '_archived_context', general)
        assert legacy['status'] == 200 and legacy['count'] == general['count']
    assert report['learning']['availability_known']
    assert min(report['learning']['actionable_srs'], 10) == cases['srs_review_general']['count']
    assert min(report['learning']['actionable_errors'], 10) == cases['error_notebook_general']['count']
if a.assert_catalog:
    catalog=report['catalog']
    assert catalog['knowledge_status']==catalog['legacy_status']==200
    assert catalog['subjects']==catalog['projected_subjects']==35
    assert catalog['trunks']==catalog['projected_trunks']==7
    assert catalog['topics']==223 and catalog['preserved_templates']==3
