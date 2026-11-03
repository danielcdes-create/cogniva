"""Read-only verification of the finite automatic legacy exception, not human review."""
import hashlib
import json
import struct
from functools import lru_cache
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from question_contracts import content_revision

ROOT = Path(__file__).resolve().parents[1]

@lru_cache
def cohort():
    source = json.loads((ROOT / 'shared/legacy-classification-cohort.json').read_text(encoding='utf-8'))
    return {m['question_id']: m for m in source['members']}

def identity(q):
    value = q['_id']
    return value['$oid'] if isinstance(value, dict) else str(value)

def content(q):
    answers = q['answers']
    indices = q.get('correct_answer_indices') or [next(i for i,a in enumerate(answers) if a.strip()==t.strip()) for t in q['correct_answers']]
    return {'question_type':q.get('question_type','TRUE_FALSE' if answers==['Certo','Errado'] else 'MULTIPLE_CHOICE'),
            'question':q['question'],'answers':answers,'correct_answer_indices':indices,
            'grading':q.get('grading',{'strategy':'EXACT_OPTION','max_score':100,'pass_score':60,'expected_responses':[],'rubric':None,'engine':None,'confirmation_policy':'AUTOMATIC_EXACT','policy_version':'objective-exact/1'})}

def objective_revision(q):
    view=content(q);digest=hashlib.sha256()
    def feed(s):
        raw=s.encode('utf-8');digest.update(struct.pack('>Q',len(raw)));digest.update(raw)
    feed(view['question']);digest.update(struct.pack('>Q',len(view['answers'])))
    for a in view['answers']:feed(a)
    digest.update(struct.pack('>Q',len(view['correct_answer_indices'])))
    for i in view['correct_answer_indices']:digest.update(struct.pack('>Q',i))
    return digest.hexdigest()

@lru_cache
def validator():
    schema=json.loads((ROOT/'shared/schemas/legacy-auto-classification.schema.json').read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema,format_checker=FormatChecker())

def validate_auto(q):
    identifier=identity(q);member=cohort().get(identifier)
    if member is None or member['blocked'] or objective_revision(q)!=member['question_revision']:
        raise ValueError('Not the frozen eligible legacy content')
    if q.get('schema_version')==2 or 'classification' in q:
        raise ValueError('Automatic exception is only for unclassified legacy documents')
    a=q['legacy_auto_classification']
    if not validator().is_valid(a):raise ValueError('Invalid automatic receipt schema')
    if a['operation_id']!='legacy-auto:'+identifier or a['content_revision']!=content_revision(content(q)):
        raise ValueError('Automatic receipt/content mismatch')
    catalog=json.loads((ROOT/'shared/knowledge-catalog.json').read_text(encoding='utf-8'))
    topics={t['topic_id']:t for t in catalog['topics'] if t['status']=='ACTIVE'}
    primary=[];seen=set()
    for link in a['links']:
        topic=topics.get(link['topic_id'])
        if topic is None or topic['subject_id']!=link['subject_id'] or link['topic_id'] in seen:
            raise ValueError('Invalid automatic topic')
        seen.add(link['topic_id'])
        if link['role']=='PRIMARY':
            if link['coverage']!='DIRECT':raise ValueError('Primary is not direct')
            primary.append(link)
    if len(primary)!=1 or q.get('canonical_subject_id')!=primary[0]['subject_id'] or q.get('canonical_topic_id')!=primary[0]['topic_id']:
        raise ValueError('Invalid automatic primary projection')
    return True

def compare(before, after):
    old={identity(q):q for q in before};new={identity(q):q for q in after}
    if len(old)!=len(before) or len(new)!=len(after) or old.keys()!=new.keys():raise ValueError('Question identities changed')
    changes=[]
    for identifier,q in old.items():
        updated=new[identifier]
        delta={k for k in q.keys()|updated.keys() if (k in q)!=(k in updated) or q.get(k)!=updated.get(k)}
        if not delta:continue
        if 'legacy_auto_classification' in q or 'classification' in q or q.get('canonical_topic_id') or q.get('canonical_subject_id'):
            raise ValueError('Previously classified question changed')
        if delta-{'legacy_auto_classification','canonical_subject_id','canonical_topic_id'}:raise ValueError('Protected question field changed')
        validate_auto(updated);changes.append({'question_id':identifier,'fields':sorted(delta)})
    return {'authorized_changes':changes,'changed_questions':len(changes),'original_fields_preserved':True}
