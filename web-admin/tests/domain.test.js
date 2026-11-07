import test from 'node:test';
import assert from 'node:assert/strict';
import {parseQuestions,verifyReceipt,blankExam,newComponent,objectiveDraft,renameEntity,freshImport,exportWorkspace,restoreWorkspace,validityPresentation,legacyQuestionDraft} from '../domain.js';

test('JSON and JSONL preserve two distinct occurrences and report physical line numbers',()=>{
  assert.equal(parseQuestions('{"question":"a"}\n\n{"question":"a"}').length,2);
  assert.equal(parseQuestions('[{"question":"a"}]').length,1);
  assert.throws(()=>parseQuestions('{}\n\ninvalid'),/linha 3/);
  for(const input of ['','[]','[null]','null','42','[{}',JSON.stringify(Array(51).fill({})),'x'.repeat(524289)])assert.throws(()=>parseQuestions(input));
});
test('form never invents a correct answer and true false options are canonical',()=>{
  const {form}=freshImport('op','ai');
  assert.deepEqual(objectiveDraft(form).correct_answer_indices,[]);
  form.kind='TRUE_FALSE';form.correctIndex='1';
  assert.deepEqual(objectiveDraft(form).answers,['Certo','Errado']);
  assert.deepEqual(objectiveDraft(form).correct_answer_indices,[1]);
});
test('open question requires an explicit rubric and no fake option',()=>{
  const {form}=freshImport('op','ai');form.kind='OPEN_ENDED';
  assert.throws(()=>objectiveDraft(form));
  form.criteria='[{"criterion_id":"c1","description":"explanation","max_points":100,"essential":true}]';form.reference='reference';
  const q=objectiveDraft(form);assert.deepEqual(q.answers,[]);assert.deepEqual(q.correct_answer_indices,[]);assert.equal(q.grading.confirmation_policy,'HUMAN_REVIEW');
});
test('receipts must match each operation, question, revision, and total',()=>{
  const preview={items:[{command:{operation_id:'op',document:{_id:{$oid:'id'}}}}]};
  const response={complete:true,items:[{index:0,operation_id:'op',id:{$oid:'id'},saved:true,receipt:{status:'already_applied',revision:1}}]};
  assert.equal(verifyReceipt(preview,response),response);
  for(const mutate of [r=>r.items[0].operation_id='different',r=>r.items[0].id.$oid='other',r=>r.items[0].receipt.revision=2,r=>r.items=[],r=>r.complete=false]){const r=structuredClone(response);mutate(r);assert.throws(()=>verifyReceipt(preview,r));}
});
test('same discipline in two exam stages has independent identities and lists',()=>{
  const exam=blankExam('exam');const subject={subject_id:'MAT',label:'Matemática'};
  exam.subjects.push(newComponent(subject,'stage-a'),newComponent(subject,'stage-b'));
  exam.subjects[0].topics.push({canonical_topic_id:'topic'});exam.subjects[1].weight=3;
  assert.equal(exam.subjects[1].topics.length,0);assert.equal(exam.subjects[0].weight,1);assert.notEqual(exam.subjects[0].local_subject_id,exam.subjects[1].local_subject_id);
});
test('rename preserves the old label and existing aliases without changing identity',()=>{
  const original={subject_id:'MAT',label:'Matemática',aliases:['Mat']};const result=renameEntity(original,'Matemática revisada');
  assert.deepEqual(result.aliases,['Mat','Matemática']);assert.equal(result.subject_id,'MAT');assert.equal(original.label,'Matemática');
});
test('workspace restore preserves frozen commands and rejects another account',()=>{
  const workspace={import:{submitted:true,preview:{items:[{command:{operation_id:'fixed'}}]}},exams:{},questionEdits:{},archives:[]};
  const exported=JSON.parse(JSON.stringify(exportWorkspace('9007199254740993',workspace)));
  assert.deepEqual(restoreWorkspace('9007199254740993',exported),workspace);
  assert.throws(()=>restoreWorkspace('9007199254740992',exported));
});
test('validity presents expired reviews and historical scope explicitly',()=>{
  assert.equal(validityPresentation({status:'CURRENT',review_due_on:'2026-11-06'},'2026-11-07'),'Revisão vencida');
  assert.match(validityPresentation({status:'CURRENT',scope:'EXPLICIT_HISTORICAL_DATE',context_date:'2020-01-01'},'2026-11-07'),/contexto histórico de 2020-01-01/);
  assert.equal(validityPresentation({status:'UNKNOWN'},'2026-11-07'),'Desconhecida');
});

test('legacy conversion preserves identity and extra fields and requires new classification review',()=>{
  const raw={_id:{$oid:'012345678901234567890123'},question:'Legacy',answers:['A','B'],correct_answers:['B'],canonical_subject_id:'OLD',explanation:'cached',extra:{keep:true}};
  const q=legacyQuestionDraft(raw,{catalog_version:3},'9007199254740993','origin');
  assert.deepEqual(q._id,raw._id);assert.equal(q.explanation,'cached');assert.deepEqual(q.extra,{keep:true});
  assert.deepEqual(q.correct_answer_indices,[1]);assert.equal(q.classification.catalog_version,3);assert.equal(q.classification.state,'UNCLASSIFIED');assert.equal(q.canonical_subject_id,null);
  assert.equal(q.origins[0].provenance.actor.id,'9007199254740993');assert.equal(q.origins[0].verification,'PENDING');assert.equal(q.validity.status,'UNKNOWN');assert.equal(raw.schema_version,undefined);
});

test('legacy conversion leaves missing, ambiguous, or conflicting answer keys for human repair',()=>{
  for(const raw of [{answers:['A','B']},{answers:['A','A'],correct_answers:['A']},{answers:['A','B'],correct_answers:['B'],correct_answer_indices:[0]},{answers:['A','B'],correct_answer_indices:[-1]},{answers:['A','B'],correct_answer_indices:[0,1]}]){
    const q=legacyQuestionDraft(raw,{catalog_version:2},'123','origin');assert.deepEqual(q.correct_answer_indices,[]);assert.deepEqual(q.correct_answers,[]);
  }
  assert.deepEqual(legacyQuestionDraft({answers:['A','B'],correct_answer_indices:[1]},{catalog_version:2},'123','origin').correct_answer_indices,[1]);
});
