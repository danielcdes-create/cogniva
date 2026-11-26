export const MAX_ARCHIVES = 20;
export const RUBRIC_JSON_ERROR = 'JSON inválido na rubrica: informe um array JSON válido nos critérios da rubrica.';

export function normalizeIdentityKey(value) {
  return String(value ?? '')
    .normalize('NFKD')
    .replace(/\p{M}/gu, '')
    .trim()
    .replace(/\s+/g, ' ')
    .toLocaleLowerCase('pt-BR');
}

export function normalizeSearchText(value) {
  return String(value ?? '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}

export function matchesSearch(text, query) {
  const terms = normalizeSearchText(query).trim().split(/\s+/).filter(Boolean);
  if (!terms.length) return true;
  const haystack = normalizeSearchText(text);
  return terms.every(term => haystack.includes(term));
}

const ALLOWED_CRITERION_KEYS = new Set(['criterion_id', 'description', 'max_points', 'essential']);

function validateRubricCriteria(criteria, expectedMaxScore) {
  if (!Array.isArray(criteria) || criteria.length === 0 || criteria.length > 30) {
    throw new Error('JSON inválido na rubrica: rubric.criteria deve conter de 1 a 30 critérios.');
  }
  let totalPoints = 0;
  for (const c of criteria) {
    if (!c || typeof c !== 'object' || Array.isArray(c)) {
      throw new Error('JSON inválido na rubrica: cada critério deve ser um objeto JSON.');
    }
    const unknownKey = Object.keys(c).find(k => !ALLOWED_CRITERION_KEYS.has(k));
    if (unknownKey) {
      throw new Error(`JSON inválido na rubrica: chave desconhecida no critério (${unknownKey}).`);
    }
    if (
      typeof c.criterion_id !== 'string' ||
      !c.criterion_id.trim() ||
      c.criterion_id.length > 300 ||
      typeof c.description !== 'string' ||
      !c.description.trim() ||
      c.description.length > 20000 ||
      typeof c.max_points !== 'number' ||
      !Number.isFinite(c.max_points) ||
      c.max_points <= 0 ||
      c.max_points > 100
    ) {
      throw new Error('JSON inválido na rubrica: cada critério exige criterion_id, description e max_points positivo.');
    }
    if (typeof c.essential !== 'boolean') {
      throw new Error('JSON inválido na rubrica: cada critério exige o booleano essential (true ou false).');
    }
    totalPoints += c.max_points;
  }
  if (Math.abs(totalPoints - expectedMaxScore) > 1e-6) {
    throw new Error(`JSON inválido na rubrica: os pontos dos critérios devem somar ${expectedMaxScore} (atual: ${totalPoints}).`);
  }
}

export function parseOpenEndedGradingJson(rawText) {
  if (typeof rawText !== 'string' || !rawText.trim()) {
    throw new Error('JSON inválido na rubrica: informe o objeto JSON de correção e rubrica.');
  }
  let parsed;
  try {
    parsed = JSON.parse(rawText);
  } catch {
    throw new Error('JSON inválido na rubrica: verifique chaves, aspas e vírgulas.');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('JSON inválido na rubrica: o campo deve conter um objeto JSON de correção.');
  }
  if (!['RUBRIC', 'SEMANTIC', 'AI'].includes(parsed.strategy)) {
    throw new Error('JSON inválido na rubrica: strategy discursiva deve ser RUBRIC, SEMANTIC ou AI.');
  }
  if (parsed.confirmation_policy !== 'HUMAN_REVIEW') {
    throw new Error('JSON inválido na rubrica: confirmation_policy deve ser HUMAN_REVIEW.');
  }
  if (
    !Array.isArray(parsed.expected_responses) ||
    parsed.expected_responses.length < 1 ||
    parsed.expected_responses.length > 20 ||
    parsed.expected_responses.some(r => typeof r !== 'string' || !r.trim() || r.length > 20000)
  ) {
    throw new Error('JSON inválido na rubrica: informe ao menos uma resposta esperada em expected_responses.');
  }
  if (
    !parsed.rubric ||
    typeof parsed.rubric !== 'object' ||
    Array.isArray(parsed.rubric) ||
    !Number.isInteger(parsed.rubric.revision) ||
    parsed.rubric.revision < 1
  ) {
    throw new Error('JSON inválido na rubrica: rubric.revision deve ser um inteiro maior ou igual a 1.');
  }
  const maxScore =
    typeof parsed.max_score === 'number' && Number.isFinite(parsed.max_score) && parsed.max_score > 0 && parsed.max_score <= 100
      ? parsed.max_score
      : null;
  if (maxScore === null) {
    throw new Error('JSON inválido na rubrica: max_score deve ser um número positivo até 100.');
  }
  validateRubricCriteria(parsed.rubric.criteria, maxScore);
  return parsed;
}

export function parseQuestions(source) {
  if (new TextEncoder().encode(source).length > 512 * 1024) throw new Error('O lote deve ter até 512 KiB.');
  if (!source.trim()) throw new Error('Informe as questões em JSON ou JSONL.');
  let value;
  try { value = JSON.parse(source); }
  catch (original) {
    if (source.trimStart().startsWith('[')) throw new Error(`Array JSON inválido: ${original.message}`);
    value = source.split(/\r?\n/).flatMap((line, index) => {
      if (!line.trim()) return [];
      try { return [JSON.parse(line)]; } catch { throw new Error(`JSON inválido na linha ${index + 1}.`); }
    });
  }
  const items = Array.isArray(value) ? value : [value];
  if (items.length < 1 || items.length > 50 || items.some(v => !v || typeof v !== 'object' || Array.isArray(v))) throw new Error('Informe de 1 a 50 objetos de questão.');
  for (const [idx, item] of items.entries()) {
    if (item.question_type === 'OPEN_ENDED' && typeof item.grading === 'string') {
      try {
        item.grading = parseOpenEndedGradingJson(item.grading);
      } catch (err) {
        throw new Error(`Questão ${idx + 1}: ${err.message}`);
      }
    }
  }
  return items;
}

export function verifyReceipt(preview, result) {
  if (!Array.isArray(result.items) || result.items.length !== preview.items.length) throw new Error('Recibo incompleto. Os comandos foram preservados.');
  result.items.forEach((item, index) => {
    const command = preview.items[index].command;
    if (item.index !== index || item.operation_id !== command.operation_id || item.id?.$oid !== command.document._id.$oid) throw new Error('O recibo não corresponde às questões enviadas. Confira usando os mesmos comandos.');
    if (item.saved && (!['applied','already_applied'].includes(item.receipt?.status) || item.receipt.revision !== 1)) throw new Error('Confirmação de gravação inválida.');
  });
  if (result.complete !== result.items.every(item => item.saved === true)) throw new Error('Totais do recibo divergentes.');
  return result;
}

export function buildImportCommitPayload(preview, acknowledgeDuplicates = false) {
  if (!preview || !Array.isArray(preview.items)) throw new Error('Prévia de importação ausente.');
  const body = {
    reviewed: true,
    commands: preview.items.map(item => item.command)
  };
  if (typeof preview.idempotency_key === 'string' && preview.idempotency_key) {
    body.idempotency_key = preview.idempotency_key;
  }
  if (typeof preview.payload_digest === 'string' && preview.payload_digest) {
    body.payload_digest = preview.payload_digest;
  }
  if (acknowledgeDuplicates || (Array.isArray(preview.duplicate_warnings) && preview.duplicate_warnings.length > 0)) {
    body.acknowledge_duplicates = true;
  }
  return body;
}

export function blankExam(id, trunk = 'fiscal') {
  return {_id:id,revision:0,title:'',short_name:'',institution:'',role:'',trunk_slug:trunk,status:'RUMOR_PREVISTO',exam_boards:[],target_date:{type:'UNDEFINED'},historical_cutoff_score:0,wrong_answer_penalty:0,is_active_in_ladder:true,ladder_priority:1,subjects:[],sync_status:'SYNCED'};
}

export function newComponent(subject, id) {
  return {stage:'',local_subject_id:id,canonical_subject_id:subject.subject_id,display_name:subject.label,category:'CONHECIMENTOS_GERAIS',weight:1,question_count:null,relevance_tier:'CORE',topics:[]};
}

export function validateExamDraft(exam) {
  if (!exam || typeof exam !== 'object') throw new Error('Template de concurso inválido.');
  const totalQuestions = (exam.subjects || []).reduce((acc, component) => {
    if (component.question_count == null || component.question_count === '') return acc;
    const n = Number(component.question_count);
    if (!Number.isInteger(n) || n < 0 || n > 500) throw new Error(`Quantidade inválida em ${component.display_name || 'componente'} (use inteiro de 0 a 500).`);
    return acc + n;
  }, 0);
  if (totalQuestions > 500) throw new Error(`A soma das questões da grade (${totalQuestions}) excede o limite de 500 questões.`);
  return exam;
}

export function objectiveDraft(form) {
  const open = form.kind === 'OPEN_ENDED';
  const answers = open ? [] : form.kind === 'TRUE_FALSE' ? ['Certo','Errado'] : form.answers;
  let grading = null;
  if (open) {
    if (!form.reference || !form.reference.trim()) throw new Error('Informe a resposta de referência da questão discursiva.');
    let criteria;
    if (typeof form.criteria !== 'string' || !form.criteria.trim()) throw new Error(RUBRIC_JSON_ERROR);
    try { criteria = JSON.parse(form.criteria); }
    catch { throw new Error(RUBRIC_JSON_ERROR); }
    validateRubricCriteria(criteria, 100);
    grading = {strategy:'RUBRIC',max_score:100,pass_score:60,expected_responses:[form.reference.trim()],rubric:{revision:1,criteria},engine:null,confirmation_policy:'HUMAN_REVIEW',policy_version:'open-rubric-human-review/1'};
  }
  const text = value => value?.trim() || null;
  const year = text(form.examYear);
  if (year && (!/^\d{4}$/.test(year) || Number(year)<1900 || Number(year)>2200)) throw new Error('Informe um ano entre 1900 e 2200.');
  let source = null;
  if (text(form.sourceUri) || text(form.sourceTitle) || text(form.sourceLocator) || text(form.sourceDate)) {
    if (!text(form.sourceUri) || !text(form.sourceTitle) || !/^\d{4}-\d{2}-\d{2}$/.test(form.sourceDate || '')) throw new Error('Complete endereço, título e data de consulta da fonte.');
    if (!/^https?:\/\//i.test(form.sourceUri.trim())) throw new Error('Use um endereço de fonte HTTP ou HTTPS.');
    const retrieved = new Date(`${form.sourceDate}T00:00:00Z`);
    if (!Number.isFinite(retrieved.getTime()) || retrieved.toISOString().slice(0,10)!==form.sourceDate) throw new Error('Data de consulta inválida.');
    source = {uri:form.sourceUri.trim(),title:form.sourceTitle.trim(),locator:text(form.sourceLocator),retrieved_at:retrieved.toISOString(),sha256:null};
  }
  return {question_type:form.kind,question:form.question,answers,correct_answer_indices:open || form.correctIndex === '' ? [] : [Number(form.correctIndex)],grading,origin_kind:form.originKind,subject_id:form.subjectId,topic_id:form.topicId,confirm_classification:form.confirmClassification,
    source_exam_code:text(form.sourceExamCode),institution:text(form.institution),organizer:text(form.organizer),exam_year:year?Number(year):null,question_number:text(form.questionNumber),source_reference:source,confirm_origin:!!form.confirmOrigin,explanation:text(form.explanation),level:text(form.level)};
}

export function topicPath(catalog, topic) {
  const parent = catalog.topics.find(row=>row.topic_id===topic.parent_topic_id);
  return parent ? `${parent.label} → ${topic.label}` : topic.label;
}

export function orderedTopics(catalog, subjectId) {
  const topics=catalog.topics.filter(t=>t.subject_id===subjectId);
  return topics.toSorted((a,b)=>topicPath(catalog,a).localeCompare(topicPath(catalog,b),'pt-BR'));
}

export function renameEntity(row, label) {
  const result = structuredClone(row);
  const nextLabel = label.trim();
  const nextKey = normalizeIdentityKey(nextLabel);
  const oldKey = normalizeIdentityKey(row.label);
  if (row.label !== nextLabel) {
    const cleaned = (row.aliases || []).filter(name => normalizeIdentityKey(name) !== nextKey && normalizeIdentityKey(name) !== oldKey);
    if (oldKey && oldKey !== nextKey) {
      cleaned.push(row.label);
    }
    const seen = new Set([nextKey]);
    result.aliases = cleaned.filter(alias => {
      const k = normalizeIdentityKey(alias);
      if (!k || seen.has(k)) return false;
      seen.add(k);
      return true;
    });
    result.label = nextLabel;
  }
  return result;
}

export function newCatalogEntity(group, subjectId = '') {
  if (group === 'trunks') {
    return {trunk_id:'',label:'',icon_key:'Work',aliases:[],source_ids:['WEB_EDITOR'],status:'ACTIVE'};
  }
  if (group === 'subjects') {
    return {subject_id:'',label:'',aliases:[],scope_note:'',status:'ACTIVE',revision:1,source_ids:['WEB_EDITOR'],trunk_links:[]};
  }
  return {topic_id:'',subject_id:subjectId,parent_topic_id:null,kind:'TOPIC',label:'',aliases:[],status:'ACTIVE',revision:1,source_ids:['WEB_EDITOR']};
}

export function buildEditorialCard(input = {}) {
  const provisions = String(input.legalProvisionsText ?? '')
    .split(/\r?\n|;/)
    .map(s => s.trim())
    .filter(Boolean)
    .slice(0, 15);
  const testedRule = String(input.testedRule ?? '').trim();
  const exceptionClause = String(input.exceptionClause ?? '').trim() || null;
  const decisiveStemExcerpt = String(input.decisiveStemExcerpt ?? '').trim();
  const officialSourceExcerpt = String(input.officialSourceExcerpt ?? '').trim() || null;
  const existingErrors = Array.isArray(input.plausibleConceptualErrors) ? input.plausibleConceptualErrors : [];

  const hasAny = provisions.length > 0 || testedRule || exceptionClause || decisiveStemExcerpt || officialSourceExcerpt || existingErrors.length > 0;
  if (!hasAny) return null;
  if (provisions.some(p => p.length > 500)) {
    throw new Error('Ficha editorial: cada dispositivo legal deve ter até 500 caracteres.');
  }
  if (!testedRule) throw new Error('Ficha editorial: informe a regra cobrada.');
  if (testedRule.length > 2000) throw new Error('Ficha editorial: a regra cobrada deve ter até 2000 caracteres.');
  if (exceptionClause && exceptionClause.length > 2000) {
    throw new Error('Ficha editorial: a exceção deve ter até 2000 caracteres.');
  }
  if (!decisiveStemExcerpt) throw new Error('Ficha editorial: informe o trecho decisivo do enunciado.');
  if (decisiveStemExcerpt.length > 1500) {
    throw new Error('Ficha editorial: o trecho decisivo deve ter até 1500 caracteres.');
  }
  if (officialSourceExcerpt && officialSourceExcerpt.length > 3000) {
    throw new Error('Ficha editorial: o fragmento da fonte oficial deve ter até 3000 caracteres.');
  }
  if (existingErrors.length > 8) {
    throw new Error('Ficha editorial: informe até 8 erros conceituais plausíveis.');
  }

  const seenIds = new Set();
  const plausible = existingErrors.map((err, idx) => {
    const eid = String(err?.error_id || `ERR_${idx + 1}`).trim();
    const label = String(err?.label ?? '').trim();
    const desc = String(err?.description ?? '').trim();
    if (!eid || eid.length > 100 || !label || label.length > 300 || !desc || desc.length > 2000) {
      throw new Error('Ficha editorial: cada erro conceitual exige identificador, rótulo e descrição válidos.');
    }
    if (seenIds.has(eid)) throw new Error(`Ficha editorial: identificador de erro repetido (${eid}).`);
    seenIds.add(eid);
    return { error_id: eid, label, description: desc };
  });

  return {
    legal_provisions: provisions,
    tested_rule: testedRule,
    exception_clause: exceptionClause,
    decisive_stem_excerpt: decisiveStemExcerpt,
    official_source_excerpt: officialSourceExcerpt,
    plausible_conceptual_errors: plausible
  };
}

export function freshImport(id, aiId) {
  return {mode:'single',operationId:id,aiOperationId:aiId,source:'',assistanceId:null,aiMode:'STRUCTURE',preview:null,result:null,submitted:false,form:{kind:'MULTIPLE_CHOICE',question:'',answers:['','','','',''],correctIndex:'',reference:'',criteria:'',originKind:'LEGACY_IMPORT',sourceExamCode:'',institution:'',organizer:'',examYear:'',questionNumber:'',sourceUri:'',sourceTitle:'',sourceLocator:'',sourceDate:'',confirmOrigin:false,explanation:'',level:'',subjectId:'',topicId:'',confirmClassification:false}};
}

export function sanitizeArchiveValue(value) {
  const cloned = structuredClone(value);
  if (cloned && typeof cloned === 'object' && !Array.isArray(cloned)) {
    if ('archives' in cloned) cloned.archives = [];
    if (cloned.value && typeof cloned.value === 'object' && !Array.isArray(cloned.value) && 'archives' in cloned.value) {
      cloned.value.archives = [];
    }
  }
  return cloned;
}

export function sanitizeArchives(archives) {
  if (!Array.isArray(archives)) return [];
  return archives
    .filter(item => item && typeof item === 'object' && !Array.isArray(item))
    .map(item => ({
      ...structuredClone(item),
      value: sanitizeArchiveValue(item.value)
    }))
    .slice(-MAX_ARCHIVES);
}

export function pushArchive(ws, type, value, savedAt = new Date().toISOString()) {
  if (!ws || typeof ws !== 'object') return [];
  ws.archives = sanitizeArchives(ws.archives);
  ws.archives.push({type, saved_at: savedAt, value: sanitizeArchiveValue(value)});
  if (ws.archives.length > MAX_ARCHIVES) {
    ws.archives.splice(0, ws.archives.length - MAX_ARCHIVES);
  }
  return ws.archives;
}

export function exportWorkspace(owner, workspace) {
  return {format:'cogniva-admin-workspace/1',owner_user_id:String(owner),workspace};
}

export function restoreWorkspace(owner, envelope) {
  if (envelope?.format !== 'cogniva-admin-workspace/1' || String(envelope.owner_user_id) !== String(owner) || !envelope.workspace || typeof envelope.workspace !== 'object' || Array.isArray(envelope.workspace)) {
    throw new Error('Rascunho inválido ou pertencente a outra conta.');
  }
  const recovered = structuredClone(envelope.workspace);
  if ('archives' in recovered) {
    recovered.archives = sanitizeArchives(recovered.archives);
  }
  return recovered;
}

export function validityPresentation(validity,today) {
  if (!validity?.status) return 'Validade não revisada';
  if (validity.status==='CURRENT' && validity.review_due_on && validity.review_due_on<today) return 'Revisão vencida';
  const label={UNKNOWN:'Desconhecida',UNDER_REVIEW:'Em revisão',CURRENT:'Atual',OBSOLETE:'Obsoleta'}[validity.status] || validity.status;
  return validity.scope==='EXPLICIT_HISTORICAL_DATE' ? `${label} no contexto histórico de ${validity.context_date || 'data não informada'}` : label;
}

export function manualProvenance(actor,rationale){
  return {actor:{type:'HUMAN',id:String(actor)},method:'MANUAL_REVIEW',rationale,recorded_at:new Date().toISOString(),evidence:[],model:null,prompt_id:null,prompt_version:null,source_suggestion_id:null};
}

export function legacyQuestionDraft(raw,catalog,actor,originId){
  const q=structuredClone(raw);
  const kind=raw.question_type || 'MULTIPLE_CHOICE';
  const answers=Array.isArray(raw.answers)?structuredClone(raw.answers):kind==='TRUE_FALSE'?['Certo','Errado']:['','','','',''];
  let indices=[];
  const explicit=Array.isArray(raw.correct_answer_indices)?raw.correct_answer_indices:[];
  const texts=Array.isArray(raw.correct_answers)?raw.correct_answers:[];
  const sameAnswer=(a,b)=>typeof a==='string' && typeof b==='string' && a.trim()===b.trim();
  if(explicit.length===1 && Number.isInteger(explicit[0]) && explicit[0]>=0 && explicit[0]<answers.length && (texts.length===0 || texts.length===1 && sameAnswer(texts[0],answers[explicit[0]])))indices=[explicit[0]];
  else if(explicit.length===0 && texts.length===1){const matches=answers.flatMap((answer,index)=>sameAnswer(answer,texts[0])?[index]:[]);if(matches.length===1)indices=matches;}
  for (const key of ['explanation','drSincero','ajudaInfantil','explicacaoInfantil','explanationExtra','mnemonico','plausibilityMarkdown','source','level']) {
    if (typeof q[key] === 'string' && !q[key].trim()) delete q[key];
  }
  if (q.disciplina === null || (typeof q.disciplina === 'string' && !q.disciplina.trim())) delete q.disciplina;
  if (q.contextualHint === null) delete q.contextualHint;
  if (q.exam_year === '') delete q.exam_year;
  const catalogVersion = catalog?.catalog_version ?? 2;
  Object.assign(q,{schema_version:2,revision:1,content_hash_version:'question-v2-jcs-sha256',content_revision:'0'.repeat(64),status:raw.status==='ARCHIVED'?'ARCHIVED':'ACTIVE',question:typeof raw.question==='string'?raw.question:'',question_type:kind,answers:kind==='OPEN_ENDED'?[]:answers,correct_answer_indices:kind==='OPEN_ENDED'?[]:indices,correct_answers:kind==='OPEN_ENDED'?[]:indices.map(index=>answers[index]),
    origins:[{origin_id:originId,kind:'LEGACY_IMPORT',verification:'PENDING',source_exam_code:null,institution:null,organizer:null,exam_year:null,question_number:null,source_reference:null,provenance:manualProvenance(actor,'Conversão explícita de registro para o contrato v2, sujeita à conferência humana.')}],
    classification:{revision:0,catalog_version:catalogVersion,state:'UNCLASSIFIED',links:[]},canonical_subject_id:null,canonical_topic_id:null,
    validity:{revision:0,status:'UNKNOWN',basis:'FACTUAL',reference_date:null,review_due_on:null,reason_codes:[],evaluated_content_revision:null,evaluated_semantic_revision:null,evaluation_mode:null,decision:null,scope:'CURRENT_KNOWLEDGE',context_date:null},
    grading:kind==='OPEN_ENDED'?(raw.grading || null):{strategy:'EXACT_OPTION',max_score:100,pass_score:60,expected_responses:[],rubric:null,engine:null,confirmation_policy:'AUTOMATIC_EXACT',policy_version:'objective-exact/1'}});
  delete q.schema_outbox;
  return q;
}

export function isQuestionDraftDirty(draft) {
  if (!draft) return false;
  if (draft.result) return false;
  if (draft.submitted || draft.converted || draft.rubricError || Boolean(draft.editorialError || draft.editorialDraft) || draft.reconfirmValidity) return true;
  if (draft.classificationChoice && draft.classificationChoice.confirmed === false) return true;
  if (!draft.original) return false;
  try {
    return JSON.stringify(JSON.parse(draft.text)) !== JSON.stringify(draft.original);
  } catch {
    return true;
  }
}

export function reconcileQuestionDraft(ws, questionId, serverDoc, makeOperationId) {
  ws.questionEdits ||= {};
  const existing = ws.questionEdits[questionId];
  const makeFresh = () => ({
    original: structuredClone(serverDoc),
    text: JSON.stringify(serverDoc, null, 2),
    operationId: makeOperationId(),
    preview: null,
    submitted: false,
    result: null,
    reconfirmValidity: false
  });
  if (!existing) {
    ws.questionEdits[questionId] = makeFresh();
    return {status: 'created', draft: ws.questionEdits[questionId]};
  }
  if (existing.result) {
    pushArchive(ws, 'question-edit', existing);
    ws.questionEdits[questionId] = makeFresh();
    return {status: 'refreshed_completed', draft: ws.questionEdits[questionId]};
  }
  if (!isQuestionDraftDirty(existing)) {
    ws.questionEdits[questionId] = makeFresh();
    return {status: 'refreshed_clean', draft: ws.questionEdits[questionId]};
  }
  const serverRev = serverDoc?.revision ?? 0;
  const baseRev = existing.original?.revision ?? 0;
  if (serverRev > baseRev || (serverRev !== baseRev) || (serverRev === baseRev && JSON.stringify(serverDoc) !== JSON.stringify(existing.original))) {
    existing.server = structuredClone(serverDoc);
    existing.conflict = true;
    return {status: 'conflict', draft: existing};
  }
  delete existing.server;
  existing.conflict = false;
  return {status: 'kept_dirty', draft: existing};
}

export function isExamDraftDirty(draft) {
  if (!draft) return false;
  if (draft.isNew || draft.pending) return true;
  if (!draft.original) return false;
  return JSON.stringify(draft.document) !== JSON.stringify(draft.original);
}

export function reconcileExamDrafts(ws, serverExams) {
  if (!ws || typeof ws !== 'object' || !Array.isArray(serverExams)) return false;
  ws.exams ||= {};
  let changed = false;
  for (const serverExam of serverExams) {
    const id = serverExam._id;
    if (!Object.hasOwn(ws.exams, id)) continue;
    const draft = ws.exams[id];
    if (!draft || typeof draft !== 'object' || !draft.document) {
      delete ws.exams[id];
      changed = true;
      continue;
    }
    if (!isExamDraftDirty(draft)) {
      if (JSON.stringify(draft.document) !== JSON.stringify(serverExam) || draft.server || draft.conflict) {
        ws.exams[id] = {document: structuredClone(serverExam), original: structuredClone(serverExam), isNew: false, pending: null};
        changed = true;
      }
    } else if ((serverExam.revision ?? 0) > (draft.original?.revision ?? 0)) {
      if (JSON.stringify(draft.server) !== JSON.stringify(serverExam) || !draft.conflict) {
        draft.server = structuredClone(serverExam);
        draft.conflict = true;
        changed = true;
      }
    } else if (draft.server && (serverExam.revision ?? 0) <= (draft.original?.revision ?? 0)) {
      delete draft.server;
      draft.conflict = false;
      changed = true;
    }
  }
  return changed;
}

export function simulateAiReuseEconomy(monthlyVerifications, reuseRatePct, avgCostPerGeneration) {
  const safeVerif = Math.max(0, Math.min(10000000, Math.round(Number.isFinite(Number(monthlyVerifications)) ? Number(monthlyVerifications) : 0)));
  const safeReuse = Math.max(0, Math.min(100, Number.isFinite(Number(reuseRatePct)) ? Number(reuseRatePct) : 0));
  const safeCost = Math.max(0, Math.min(100, Number.isFinite(Number(avgCostPerGeneration)) ? Number(avgCostPerGeneration) : 0));

  const avoided = Math.min(safeVerif, Math.round(safeVerif * (safeReuse / 100)));
  const generated = Math.max(0, safeVerif - avoided);

  const withoutReuse = safeVerif * safeCost;
  const withReuse = generated * safeCost;
  const monthlySavings = Math.max(0, withoutReuse - withReuse);
  const annualSavings = monthlySavings * 12;
  const reductionPct = withoutReuse > 0 ? (monthlySavings / withoutReuse) * 100 : 0;

  const round2 = v => Math.round(v * 100) / 100;
  return {
    monthly_verifications: safeVerif,
    reuse_rate_pct: round2(safeReuse),
    avg_cost_per_generation: Math.round(safeCost * 10000) / 10000,
    cost_without_reuse: round2(withoutReuse),
    cost_with_reuse: round2(withReuse),
    monthly_savings: round2(monthlySavings),
    avoided_generations_monthly: avoided,
    generated_questions_monthly: generated,
    annual_savings: round2(annualSavings),
    cost_reduction_pct: round2(reductionPct),
    disclaimer_pt: 'Estimativa operacional hipotética baseada no custo unitário de referência informado; não constitui comprovação contábil ou financeira auditada.'
  };
}
