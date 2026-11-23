import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseOpenEndedGradingJson,
  parseQuestions,
  verifyReceipt,
  buildImportCommitPayload,
  blankExam,
  newComponent,
  validateExamDraft,
  objectiveDraft,
  renameEntity,
  newCatalogEntity,
  freshImport,
  exportWorkspace,
  restoreWorkspace,
  validityPresentation,
  legacyQuestionDraft,
  topicPath,
  orderedTopics,
  buildEditorialCard,
  isQuestionDraftDirty,
  reconcileQuestionDraft,
  isExamDraftDirty,
  reconcileExamDrafts
} from '../domain.js';

test('existing question metadata survives workspace restore and preview payload',()=>{
  const draft=freshImport('op','ai');
  Object.assign(draft.form,{sourceExamCode:' Prova Analista / caderno 1 ',institution:'Órgão',organizer:'Banca',examYear:'2025',questionNumber:'042',sourceUri:'https://example.org/prova.pdf',sourceTitle:'Caderno oficial',sourceLocator:'p. 10',sourceDate:'2026-11-07',confirmOrigin:true,explanation:'Justificativa',level:'Intermediária',originKind:'OFFICIAL_EXAM',conceptId:'DEAD_CONCEPT_ID'});
  const restored=restoreWorkspace('1',JSON.parse(JSON.stringify(exportWorkspace('1',{import:draft}))));
  const q=objectiveDraft(restored.import.form);
  assert.equal(q.source_exam_code,'Prova Analista / caderno 1');assert.equal(q.exam_year,2025);assert.equal(q.question_number,'042');assert.equal(q.explanation,'Justificativa');assert.equal(q.confirm_origin,true);
  assert.equal('concept_id' in q, false, 'Dead conceptId branch must never emit concept_id');
  assert.deepEqual(q.source_reference,{uri:'https://example.org/prova.pdf',title:'Caderno oficial',locator:'p. 10',retrieved_at:'2026-11-07T00:00:00.000Z',sha256:null});
  assert.equal(q.confirm_classification,false);
  for(const changes of [{examYear:'20e2'},{examYear:'1899'},{sourceDate:'2026-02-30'},{sourceTitle:''},{sourceUri:'javascript:alert(1)'}]) assert.throws(()=>objectiveDraft({...draft.form,...changes}));
  const old={kind:'TRUE_FALSE',question:'Q',correctIndex:'0',originKind:'LEGACY_IMPORT'};
  assert.equal(objectiveDraft(old).source_reference,null);assert.equal(objectiveDraft(old).exam_year,null);
});

test('topic paths distinguish parents and children even with repeated child labels',()=>{
  const catalog={topics:[{topic_id:'B1',subject_id:'D',label:'Conceito',parent_topic_id:'B'},{topic_id:'A',subject_id:'D',label:'Assunto A'},{topic_id:'B',subject_id:'D',label:'Assunto B'},{topic_id:'A1',subject_id:'D',label:'Conceito',parent_topic_id:'A'},{topic_id:'X',subject_id:'OTHER',label:'Outro'}]};
  assert.equal(topicPath(catalog,catalog.topics[0]),'Assunto B → Conceito');
  assert.deepEqual(orderedTopics(catalog,'D').map(t=>t.topic_id),['A','A1','B','B1']);
});

test('JSON and JSONL preserve two distinct occurrences, validate stringified OPEN_ENDED grading, and report physical line numbers',()=>{
  assert.equal(parseQuestions('{"question":"a"}\n\n{"question":"a"}').length,2);
  assert.equal(parseQuestions('[{"question":"a"}]').length,1);
  assert.throws(()=>parseQuestions('{}\n\ninvalid'),/linha 3/);
  for(const input of ['','[]','[null]','null','42','[{}',JSON.stringify(Array(51).fill({})),'x'.repeat(524289)])assert.throws(()=>parseQuestions(input));

  const validOpenGrading = JSON.stringify({
    strategy: 'RUBRIC',
    max_score: 100,
    pass_score: 60,
    expected_responses: ['Resposta esperada'],
    rubric: {revision: 1, criteria: [{criterion_id: 'c1', description: 'Critério', max_points: 100, essential: true}]},
    engine: null,
    confirmation_policy: 'HUMAN_REVIEW',
    policy_version: 'open-rubric-human-review/1'
  });
  const parsedItems = parseQuestions(JSON.stringify([{question_type: 'OPEN_ENDED', question: 'Disserte.', grading: validOpenGrading}]));
  assert.equal(typeof parsedItems[0].grading, 'object');
  assert.throws(
    () => parseQuestions(JSON.stringify([{question_type: 'OPEN_ENDED', question: 'Disserte.', grading: '{"strategy":"RUBRIC"}'}])),
    /Questão 1: JSON inválido na rubrica/
  );
});

test('form never invents a correct answer and true false options are canonical',()=>{
  const {form}=freshImport('op','ai');
  assert.deepEqual(objectiveDraft(form).correct_answer_indices,[]);
  form.kind='TRUE_FALSE';form.correctIndex='1';
  assert.deepEqual(objectiveDraft(form).answers,['Certo','Errado']);
  assert.deepEqual(objectiveDraft(form).correct_answer_indices,[1]);
});

test('open question and parseOpenEndedGradingJson enforce strict schema: boolean essential, unknown keys, expected_responses, revision >= 1, and HUMAN_REVIEW',()=>{
  const {form}=freshImport('op','ai');form.kind='OPEN_ENDED';
  assert.throws(()=>objectiveDraft(form));
  form.reference='Resposta de referência esperada';
  form.criteria='';
  assert.throws(()=>objectiveDraft(form), /JSON inválido na rubrica/);
  form.criteria='[{"criterion_id":"c1","description":"explanation","max_points":60,"essential":true}]';
  assert.throws(()=>objectiveDraft(form),/somar 100/);

  // Missing essential or non-boolean essential
  form.criteria='[{"criterion_id":"c1","description":"explanation","max_points":100}]';
  assert.throws(()=>objectiveDraft(form),/essential/);
  form.criteria='[{"criterion_id":"c1","description":"explanation","max_points":100,"essential":"sim"}]';
  assert.throws(()=>objectiveDraft(form),/essential/);

  // Unknown key in criterion
  form.criteria='[{"criterion_id":"c1","description":"explanation","max_points":100,"essential":true,"extra_field":1}]';
  assert.throws(()=>objectiveDraft(form),/chave desconhecida no critério \(extra_field\)/);

  form.criteria='[{"criterion_id":"c1","description":"explanation","max_points":60,"essential":true},{"criterion_id":"c2","description":"detail","max_points":40,"essential":false}]';
  const q=objectiveDraft(form);
  assert.deepEqual(q.answers,[]);
  assert.deepEqual(q.correct_answer_indices,[]);
  assert.equal(q.grading.confirmation_policy,'HUMAN_REVIEW');
  assert.equal(q.grading.max_score,100);
  assert.equal(q.grading.rubric.revision,1);

  const baseGrading = structuredClone(q.grading);
  assert.deepEqual(parseOpenEndedGradingJson(JSON.stringify(baseGrading)), baseGrading);

  assert.throws(() => parseOpenEndedGradingJson(''), /informe o objeto JSON/);
  assert.throws(() => parseOpenEndedGradingJson('[]'), /objeto JSON de correção/);
  assert.throws(() => parseOpenEndedGradingJson(JSON.stringify({...baseGrading, strategy: 'EXACT_OPTION'})), /strategy discursiva/);
  assert.throws(() => parseOpenEndedGradingJson(JSON.stringify({...baseGrading, confirmation_policy: 'AUTOMATIC_EXACT'})), /HUMAN_REVIEW/);
  assert.throws(() => parseOpenEndedGradingJson(JSON.stringify({...baseGrading, expected_responses: []})), /expected_responses/);
  assert.throws(() => parseOpenEndedGradingJson(JSON.stringify({...baseGrading, expected_responses: ['   ']})), /expected_responses/);
  assert.throws(() => parseOpenEndedGradingJson(JSON.stringify({...baseGrading, rubric: {revision: 0, criteria: baseGrading.rubric.criteria}})), /rubric\.revision/);
  assert.throws(() => parseOpenEndedGradingJson(JSON.stringify({...baseGrading, rubric: {revision: 1.5, criteria: baseGrading.rubric.criteria}})), /rubric\.revision/);
  assert.throws(() => parseOpenEndedGradingJson(JSON.stringify({...baseGrading, max_score: 0})), /max_score/);
  assert.throws(
    () => parseOpenEndedGradingJson(JSON.stringify({
      ...baseGrading,
      rubric: {revision: 1, criteria: [{criterion_id: 'c1', description: 'ok', max_points: 100, essential: true, injected: true}]}
    })),
    /chave desconhecida no critério/
  );
});

test('exam template validation enforces 500-question total cap and allows null counts',()=>{
  assert.throws(()=>validateExamDraft(null));
  const exam=blankExam('CPNU');
  const compA=newComponent({subject_id:'PORT',label:'Português'},'a');
  const compB=newComponent({subject_id:'DIR_ADM',label:'Administrativo'},'b');
  compA.question_count=300;
  compB.question_count=null;
  exam.subjects.push(compA,compB);
  assert.equal(validateExamDraft(exam),exam);
  compB.question_count=-1;
  assert.throws(()=>validateExamDraft(exam),/Quantidade inválida/);
  compB.question_count=201;
  assert.throws(()=>validateExamDraft(exam),/excede o limite de 500/);
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

test('rename preserves distinct old label and avoids NFKD accent or case alias collisions',()=>{
  const original={subject_id:'MAT',label:'Matemática',aliases:['Mat']};
  const result=renameEntity(original,'Matemática revisada');
  assert.deepEqual(result.aliases,['Mat','Matemática']);
  assert.equal(result.subject_id,'MAT');
  assert.equal(original.label,'Matemática');

  const accentOnly=renameEntity({subject_id:'MAT',label:'Matematica',aliases:['MATEMATICA','Álgebra']},'Matemática');
  assert.deepEqual(accentOnly.aliases,['Álgebra']);
  assert.equal(accentOnly.label,'Matemática');

  assert.equal(newCatalogEntity('trunks').icon_key,'Work');
  assert.equal(newCatalogEntity('subjects').revision,1);
  assert.equal(newCatalogEntity('topics','MAT').subject_id,'MAT');
});

test('workspace restore preserves frozen commands and rejects another account',()=>{
  const workspace={import:{submitted:true,preview:{items:[{command:{operation_id:'fixed'}}]}},exams:{},questionEdits:{},archives:[]};
  const exported=JSON.parse(JSON.stringify(exportWorkspace('9007199254740993',workspace)));
  assert.deepEqual(restoreWorkspace('9007199254740993',exported),workspace);
  assert.throws(()=>restoreWorkspace('9007199254740992',exported));
});

test('validity presents expired reviews and historical scope explicitly',()=>{
  assert.equal(validityPresentation(null,'2026-11-07'),'Validade não revisada');
  assert.equal(validityPresentation({status:'CURRENT',review_due_on:'2026-11-06'},'2026-11-07'),'Revisão vencida');
  assert.match(validityPresentation({status:'CURRENT',scope:'EXPLICIT_HISTORICAL_DATE',context_date:'2020-01-01'},'2026-11-07'),/contexto histórico de 2020-01-01/);
  assert.equal(validityPresentation({status:'UNKNOWN'},'2026-11-07'),'Desconhecida');
});

test('legacy conversion preserves identity and extra fields and requires new classification review',()=>{
  const raw={_id:{$oid:'012345678901234567890123'},question:'Legacy',answers:['A','B'],correct_answers:['B'],canonical_subject_id:'OLD',explanation:'cached',drSincero:'   ',disciplina:'',contextualHint:null,exam_year:'',extra:{keep:true}};
  const q=legacyQuestionDraft(raw,{catalog_version:2},'9007199254740993','origin');
  assert.deepEqual(q._id,raw._id);assert.equal(q.explanation,'cached');assert.equal('drSincero' in q,false);assert.equal('disciplina' in q,false);assert.deepEqual(q.extra,{keep:true});
  assert.deepEqual(q.correct_answer_indices,[1]);assert.equal(q.classification.catalog_version,2);assert.equal(q.classification.state,'UNCLASSIFIED');assert.equal(q.canonical_subject_id,null);
  assert.equal(q.origins[0].provenance.actor.id,'9007199254740993');assert.equal(q.origins[0].verification,'PENDING');assert.equal(q.validity.status,'UNKNOWN');assert.equal(raw.schema_version,undefined);
});

test('legacy conversion leaves missing, ambiguous, or conflicting answer keys for human repair',()=>{
  for(const raw of [{answers:['A','B']},{answers:['A','A'],correct_answers:['A']},{answers:['A','B'],correct_answers:['B'],correct_answer_indices:[0]},{answers:['A','B'],correct_answer_indices:[-1]},{answers:['A','B'],correct_answer_indices:[0,1]}]){
    const q=legacyQuestionDraft(raw,{catalog_version:2},'123','origin');assert.deepEqual(q.correct_answer_indices,[]);assert.deepEqual(q.correct_answers,[]);
  }
  const normalized=legacyQuestionDraft({answers:['A','B'],correct_answer_indices:[1]},{catalog_version:2},'123','origin');
  assert.deepEqual(normalized.correct_answer_indices,[1]);
  assert.equal(normalized.validity.evaluated_semantic_revision,null);
  assert.equal(normalized.validity.evaluation_mode,null);
});

test('import commit payload forwards idempotency_key, payload_digest, and duplicate acknowledgement',()=>{
  assert.throws(()=>buildImportCommitPayload(null));
  const cmd={operation_id:'op-1',expected_revision:0,dry_run:false,document:{_id:{$oid:'65f000000000000000000001'}}};
  const preview={
    ready:true,
    idempotency_key:'batch-uuid-1',
    payload_digest:'a'.repeat(64),
    items:[{valid:true,command:cmd}],
    duplicate_warnings:[{index:0,code:'POTENTIAL_DUPLICATE',kind:'EXACT_CONTENT',message:'Duplicidade detectada'}]
  };
  const body=buildImportCommitPayload(preview);
  assert.deepEqual(body,{
    reviewed:true,
    idempotency_key:'batch-uuid-1',
    payload_digest:'a'.repeat(64),
    acknowledge_duplicates:true,
    commands:[cmd]
  });
  const cleanBody=buildImportCommitPayload({ready:true,idempotency_key:'batch-uuid-2',payload_digest:'b'.repeat(64),items:[{valid:true,command:cmd}],duplicate_warnings:[]});
  assert.equal(cleanBody.acknowledge_duplicates,undefined);
});

test('editorial card builder enforces 500-char legal_provisions cap and isQuestionDraftDirty protects unsaved editorial drafts',()=>{
  assert.equal(buildEditorialCard({}), null);
  assert.throws(()=>buildEditorialCard({testedRule:'Regra sem trecho decisivo'}), /trecho decisivo/);
  assert.throws(()=>buildEditorialCard({decisiveStemExcerpt:'Trecho sem regra'}), /regra cobrada/);
  assert.throws(
    ()=>buildEditorialCard({
      legalProvisionsText: 'x'.repeat(501),
      testedRule: 'Regra válida',
      decisiveStemExcerpt: 'Trecho válido'
    }),
    /500 caracteres/
  );
  assert.throws(
    ()=>buildEditorialCard({
      testedRule: 'Regra',
      decisiveStemExcerpt: 'Trecho',
      plausibleConceptualErrors: [{error_id:'E1',label:'A',description:'B'},{error_id:'E1',label:'C',description:'D'}]
    }),
    /identificador de erro repetido/
  );
  assert.throws(
    ()=>buildEditorialCard({
      testedRule: 'Regra',
      decisiveStemExcerpt: 'Trecho',
      plausibleConceptualErrors: [{error_id:'E1',label:'',description:'B'}]
    }),
    /exige identificador, rótulo e descrição/
  );

  const card = buildEditorialCard({
    legalProvisionsText: `${'A'.repeat(500)}\nLei 9.784/99, art. 53`,
    testedRule: 'Autotutela exige anulação de ato ilegal.',
    exceptionClause: 'Salvo decadência quinquenal de boa-fé.',
    decisiveStemExcerpt: 'eivados de vício de legalidade',
    officialSourceExcerpt: 'Art. 53. A Administração deve anular...',
    plausibleConceptualErrors: [{error_id:'E1',label:'Revogação',description:'Confundir mérito com legalidade'}]
  });
  assert.equal(card.legal_provisions.length, 2);
  assert.equal(card.legal_provisions[0].length, 500);
  assert.equal(card.tested_rule, 'Autotutela exige anulação de ato ilegal.');
  assert.equal(card.plausible_conceptual_errors.length, 1);

  // Dirty draft protection when editorialError or editorialDraft is set even before draft.text changes
  const serverDoc = {_id:{$oid:'65f000000000000000000001'},revision:1,question:'Original'};
  const ws = {questionEdits: {}, archives: []};
  reconcileQuestionDraft(ws, '65f000000000000000000001', serverDoc, () => 'op-1');
  const draft = ws.questionEdits['65f000000000000000000001'];
  assert.equal(isQuestionDraftDirty(draft), false);

  draft.editorialDraft = {testedRule: 'Rascunho parcial', decisiveStemExcerpt: ''};
  draft.editorialError = 'Ficha editorial: informe o trecho decisivo do enunciado.';
  assert.equal(isQuestionDraftDirty(draft), true);

  const sameRev = reconcileQuestionDraft(ws, '65f000000000000000000001', serverDoc, () => 'op-2');
  assert.equal(sameRev.status, 'kept_dirty');
  assert.equal(sameRev.draft.editorialDraft.testedRule, 'Rascunho parcial');

  const nextRev = reconcileQuestionDraft(ws, '65f000000000000000000001', {...serverDoc, revision: 2}, () => 'op-3');
  assert.equal(nextRev.status, 'conflict');
  assert.equal(nextRev.draft.conflict, true);
});

test('reconcileExamDrafts refreshes clean drafts, marks server conflicts on dirty drafts, and cleans invalid entries',()=>{
  const base = {...blankExam('BCB_2026'), revision: 1, title: 'Banco Central v1'};
  const ws = {
    exams: {
      BCB_2026: {document: structuredClone(base), original: structuredClone(base), isNew: false, pending: null},
      RFB_2026: {document: {...blankExam('RFB_2026'), revision: 1, title: 'Edição local suja'}, original: {...blankExam('RFB_2026'), revision: 1, title: 'Original'}, isNew: false, pending: null},
      BROKEN: {document: null}
    }
  };
  assert.equal(isExamDraftDirty(ws.exams.BCB_2026), false);
  assert.equal(isExamDraftDirty(ws.exams.RFB_2026), true);

  const changed = reconcileExamDrafts(ws, [
    {...base, revision: 2, title: 'Banco Central v2'},
    {...blankExam('RFB_2026'), revision: 3, title: 'Receita Servidor v3'},
    {...blankExam('BROKEN'), revision: 1}
  ]);
  assert.equal(changed, true);
  assert.equal(ws.exams.BCB_2026.document.revision, 2);
  assert.equal(ws.exams.RFB_2026.conflict, true);
  assert.equal(ws.exams.RFB_2026.server.revision, 3);
  assert.equal('BROKEN' in ws.exams, false);
});
