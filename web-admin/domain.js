export function normalizeIdentityKey(value) {
  return String(value ?? '')
    .normalize('NFKD')
    .replace(/\p{M}/gu, '')
    .trim()
    .replace(/\s+/g, ' ')
    .toLocaleLowerCase('pt-BR');
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
    try { criteria = JSON.parse(form.criteria); }
    catch { throw new Error('Informe um array JSON válido nos critérios da rubrica.'); }
    if (!Array.isArray(criteria) || criteria.length === 0) throw new Error('A rubrica discursiva exige ao menos um critério.');
    let totalPoints = 0;
    for (const c of criteria) {
      if (!c || typeof c !== 'object' || typeof c.criterion_id !== 'string' || !c.criterion_id.trim() || typeof c.description !== 'string' || !c.description.trim() || typeof c.max_points !== 'number' || c.max_points <= 0) {
        throw new Error('Cada critério da rubrica exige criterion_id, description e max_points positivo.');
      }
      totalPoints += c.max_points;
    }
    if (Math.abs(totalPoints - 100) > 1e-6) {
      throw new Error(`Os pontos dos critérios da rubrica devem somar 100 (atual: ${totalPoints}).`);
    }
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

export function freshImport(id, aiId) {
  return {mode:'single',operationId:id,aiOperationId:aiId,source:'',assistanceId:null,aiMode:'STRUCTURE',preview:null,result:null,submitted:false,form:{kind:'MULTIPLE_CHOICE',question:'',answers:['','','','',''],correctIndex:'',reference:'',criteria:'',originKind:'LEGACY_IMPORT',sourceExamCode:'',institution:'',organizer:'',examYear:'',questionNumber:'',sourceUri:'',sourceTitle:'',sourceLocator:'',sourceDate:'',confirmOrigin:false,explanation:'',level:'',subjectId:'',topicId:'',confirmClassification:false}};
}

export function exportWorkspace(owner, workspace) { return {format:'cogniva-admin-workspace/1',owner_user_id:String(owner),workspace}; }

export function restoreWorkspace(owner, envelope) {
  if (envelope?.format !== 'cogniva-admin-workspace/1' || String(envelope.owner_user_id) !== String(owner) || !envelope.workspace || typeof envelope.workspace !== 'object' || Array.isArray(envelope.workspace)) throw new Error('Rascunho inválido ou pertencente a outra conta.');
  return structuredClone(envelope.workspace);
}

export function validityPresentation(validity,today) {
  if (!validity?.status) return 'Validade não revisada';
  if (validity.status==='CURRENT' && validity.review_due_on && validity.review_due_on<today) return 'Revisão vencida';
  const label={UNKNOWN:'Desconhecida',UNDER_REVIEW:'Em revisão',CURRENT:'Atual',OBSOLETE:'Obsoleta'}[validity.status] || validity.status;
  return validity.scope==='EXPLICIT_HISTORICAL_DATE' ? `${label} no contexto histórico de ${validity.context_date || 'data não informada'}` : label;
}

export function manualProvenance(actor,rationale){return {actor:{type:'HUMAN',id:String(actor)},method:'MANUAL_REVIEW',rationale,recorded_at:new Date().toISOString(),evidence:[],model:null,prompt_id:null,prompt_version:null,source_suggestion_id:null};}

export function legacyQuestionDraft(raw,catalog,actor,originId){
  const q=structuredClone(raw);
  const kind=raw.question_type || 'MULTIPLE_CHOICE';
  const answers=Array.isArray(raw.answers)?structuredClone(raw.answers):kind==='TRUE_FALSE'?['Certo','Errado']:['','','','',''];
  let indices=[];
  const explicit=Array.isArray(raw.correct_answer_indices)?raw.correct_answer_indices:[];
  const texts=Array.isArray(raw.correct_answers)?raw.correct_answers:[];
  if(explicit.length===1 && Number.isInteger(explicit[0]) && explicit[0]>=0 && explicit[0]<answers.length && (texts.length===0 || texts.length===1 && texts[0]===answers[explicit[0]]))indices=[explicit[0]];
  else if(explicit.length===0 && texts.length===1){const matches=answers.flatMap((answer,index)=>answer===texts[0]?[index]:[]);if(matches.length===1)indices=matches;}
  const catalogVersion = catalog?.catalog_version ?? 2;
  Object.assign(q,{schema_version:2,revision:1,content_hash_version:'question-v2-jcs-sha256',content_revision:'0'.repeat(64),status:raw.status==='ARCHIVED'?'ARCHIVED':'ACTIVE',question:typeof raw.question==='string'?raw.question:'',question_type:kind,answers:kind==='OPEN_ENDED'?[]:answers,correct_answer_indices:kind==='OPEN_ENDED'?[]:indices,correct_answers:kind==='OPEN_ENDED'?[]:indices.map(index=>answers[index]),
    origins:[{origin_id:originId,kind:'LEGACY_IMPORT',verification:'PENDING',source_exam_code:null,institution:null,organizer:null,exam_year:null,question_number:null,source_reference:null,provenance:manualProvenance(actor,'Conversão explícita de registro para o contrato v2, sujeita à conferência humana.')}],
    classification:{revision:0,catalog_version:catalogVersion,state:'UNCLASSIFIED',links:[]},canonical_subject_id:null,canonical_topic_id:null,
    validity:{revision:0,status:'UNKNOWN',basis:'FACTUAL',reference_date:null,review_due_on:null,reason_codes:[],evaluated_content_revision:null,decision:null,scope:'CURRENT_KNOWLEDGE',context_date:null},
    grading:kind==='OPEN_ENDED'?(raw.grading || null):{strategy:'EXACT_OPTION',max_score:100,pass_score:60,expected_responses:[],rubric:null,engine:null,confirmation_policy:'AUTOMATIC_EXACT',policy_version:'objective-exact/1'}});
  delete q.schema_outbox;
  return q;
}
