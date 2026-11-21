import {helpTopics,searchHelp} from './help.js';
import {
  matchesSearch,
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
  sanitizeArchives,
  pushArchive,
  exportWorkspace,
  restoreWorkspace,
  validityPresentation,
  legacyQuestionDraft,
  manualProvenance,
  topicPath,
  orderedTopics,
  isQuestionDraftDirty,
  reconcileQuestionDraft,
  isExamDraftDirty,
  reconcileExamDrafts,
  buildEditorialCard
} from './domain.js';

const $ = selector => document.querySelector(selector);
const uid = () => crypto.randomUUID();
const copy = value => structuredClone(value);
const pretty = value => JSON.stringify(value,null,2);

const labels = {
  overview:'Visão geral',
  missions:'Missões',
  exams:'Concursos',
  edital:'Editais e grades',
  trunks:'Troncos de carreira',
  subjects:'Disciplinas e assuntos',
  questions:'Banco de questões',
  import:'Inserir questões',
  classification:'Classificação IA',
  reports:'Reportes',
  catalog:'Revisões do catálogo',
  drafts:'Rascunhos e pendências',
  help:'Ajuda',
  exam:'Editar template global',
  question:'Editar questão',
  entity:'Editar catálogo'
};
const lifecycle = {
  RUMOR_PREVISTO:'Previsto',
  AUTORIZADO:'Autorizado',
  BANCA_DEFINIDA:'Banca definida',
  EDITAL_PUBLICADO:'Edital publicado',
  PROVA_REALIZADA:'Prova realizada',
  ARQUIVADO:'Arquivado',
  EXCLUIDO:'Excluído'
};
const kinds = {
  MULTIPLE_CHOICE:'Múltipla escolha',
  TRUE_FALSE:'Certo / Errado',
  OPEN_ENDED:'Discursiva'
};
const statuses = {
  UNKNOWN:'Desconhecida',
  UNDER_REVIEW:'Em revisão',
  CURRENT:'Atual',
  OBSOLETE:'Obsoleta'
};
const classificationStates = {
  CONFIRMED:'Confirmada',
  PROPOSED:'Proposta',
  UNCLASSIFIED:'Sem classificação'
};
const validityBases = {
  TIMELESS:'Atemporal / conceitual estável',
  FACTUAL:'Factual / sujeita a atualização',
  LEGAL:'Normativa / legislativa',
  TECHNICAL:'Técnica / tecnológica'
};
const validityReasons = [
  ['LAW_REPEALED','Norma revogada'],
  ['LAW_AMENDED','Norma alterada'],
  ['ANSWER_CHANGED','Gabarito alterado / superado'],
  ['TECHNOLOGY_CHANGED','Tecnologia alterada'],
  ['FACT_CHANGED','Fato ou dado superado'],
  ['SOURCE_UNAVAILABLE','Fonte indisponível'],
  ['REVIEW_REQUESTED','Revisão solicitada']
];
const trunkIcons = [
  ['Fiscal','Fiscal'],
  ['Control','Controle'],
  ['Legal','Jurídica'],
  ['Police','Policial'],
  ['Admin','Administrativa'],
  ['Bank','Bancária'],
  ['Tech','Tecnologia'],
  ['Health','Saúde'],
  ['Work','Geral / Trabalho']
];
const helpForPage = {
  overview:'visao-geral',
  missions:'missoes-interativas',
  exams:'concursos',
  exam:'editais-grades',
  edital:'editais-grades',
  trunks:'troncos',
  subjects:'disciplinas-assuntos',
  questions:'banco-questoes',
  question:'banco-questoes',
  import:'inserir-individual',
  classification:'classificacao-ia',
  reports:'reportes-moderacao',
  catalog:'revisoes-catalogo',
  entity:'revisoes-catalogo',
  drafts:'rascunhos'
};

let helpFocus = '';
let authMode = 'login';
let current = 'overview', me = null, data = {}, ws = {}, busy = false, sequence = 0;
let storageBlocked = false, unreadableStorage = null, quotaExceeded = false;
let loadErrors = [];
let selectedExam = null, selectedQuestion = null, selectedEntity = null, entityGroup = 'trunks';
let questionPage = null, questionSearch = '', questionType = '', questionValidity = '', questionClassState = '';
let questionCursor = null, questionCursorStack = [];
let catalogTab = 'trunks';
let proposalsData = null, proposalStatusFilter = 'PENDING';
let reportsData = null, reportStatusFilter = 'open';
let missionsAdminData = null;
let lastRenderedRoute = null;
let openDetailsByRoute = new Map();
let examUiState = new Map();
let fieldKeyCounts = new Map();
let pendingConfirmResolve = null;
let boundDocument = null;
let bootstrapInFlight = null;

export function h(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key,value] of Object.entries(attributes || {})) {
    if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2),value);
    else if (key === 'class') node.className = value;
    else if (key === 'value') node.value = String(value ?? '');
    else if (['checked','disabled','selected','hidden','open'].includes(key)) node[key] = Boolean(value);
    else if (value !== false && value != null) node.setAttribute(key,String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}
const button = (text,action,style = '') => h('button',{type:'button',class:style,onclick:action},text);
const badge = (text,style = '') => h('span',{class:`badge ${style}`.trim()},text);
const card = (...content) => h('section',{class:'card'},...content);
const actions = (...content) => h('div',{class:'actions'},...content);
const openEndedBanner = () => h(
  'div',
  {class:'preparatory-banner',role:'note'},
  h('strong',{},'Modo preparatório discursivo: '),
  'questões discursivas (OPEN_ENDED) são validadas pelo contrato v2, calculam hash JCS e permanecem curadas no acervo, mas não entram nas filas de prática objetiva.'
);

function nextFocusKey(label, explicitKey) {
  if (explicitKey) return explicitKey;
  const base = `${current}:${label}`;
  const count = (fieldKeyCounts.get(base) || 0) + 1;
  fieldKeyCounts.set(base, count);
  return `${base}#${count}`;
}

function field(label,value,change,options = {}) {
  const id = `field-${++sequence}`;
  const focusKey = nextFocusKey(label, options.focusKey);
  const input = options.options
    ? (() => {
        const sel = h('select',{id,'data-focus-key':focusKey,disabled:options.disabled,onchange:e=>change(e.target.value)},...options.options.map(([v,t])=>h('option',{value:v,selected:String(value ?? '')===String(v)},t)));
        sel.value = String(value ?? '');
        return sel;
      })()
    : h(options.multiline ? 'textarea' : 'input',{
        id,
        'data-focus-key':focusKey,
        value:value ?? '',
        type:options.type || 'text',
        rows:options.rows || 3,
        required:options.required || false,
        disabled:options.disabled || false,
        min:options.min,
        max:options.max,
        step:options.step,
        maxlength:options.maxlength,
        placeholder:options.placeholder || '',
        class:options.code?'json-editor':'',
        oninput:e=>change(e.target.value)
      });
  return h(
    'div',
    {class:`field ${options.wide?'wide':''}`.trim()},
    h('label',{for:id},label),
    input,
    options.error ? h('p',{class:'field-error',role:'alert'},options.error) : null,
    options.help ? h('small',{},options.help) : null
  );
}
const check = (label,checked,change,disabled=false) => h('label',{},h('input',{type:'checkbox',checked,disabled,onchange:e=>change(e.target.checked)}),h('span',{},label));

function notice(text,kind='') {
  const node = $('#notice');
  if (!node) return;
  node.textContent = text;
  node.className = `notice ${kind}`.trim();
  node.hidden = !text;
}

function isQuotaError(error) {
  if (!error) return false;
  return error.name === 'QuotaExceededError'
    || error.name === 'NS_ERROR_DOM_QUOTA_REACHED'
    || error.code === 22
    || error.code === 1014
    || /quota/i.test(`${error.name || ''} ${error.message || ''}`);
}

export function persist() {
  if (!me) throw new Error('Entre na conta antes de editar.');
  if (storageBlocked) throw new Error('O arquivo local ilegível foi preservado. Exporte-o e restaure uma cópia válida antes de editar.');
  ws.archives = sanitizeArchives(ws.archives);
  try {
    localStorage.setItem(`cogniva-admin:${me.user_id}`,JSON.stringify(ws));
    quotaExceeded = false;
    return true;
  } catch (error) {
    quotaExceeded = true;
    const msg = isQuotaError(error)
      ? 'Armazenamento local cheio neste navegador (limite de espaço atingido). Suas alterações permanecem ativas nesta aba; exporte seus rascunhos ou use "Limpar histórico local" em Rascunhos e pendências.'
      : 'Não foi possível gravar o rascunho no armazenamento local deste navegador. Exporte sua cópia de segurança antes de fechar a aba.';
    notice(msg, 'warning');
    return false;
  }
}

function edit(action) {
  try {
    action();
    const saved = persist();
    if (saved) notice('Rascunho salvo neste navegador.');
    return true;
  } catch (e) {
    notice(e.message,'error');
    return false;
  }
}

export function setMobileMenuOpen(open, {restoreFocus = false} = {}) {
  const app = $('#app');
  const sidebar = $('#sidebar');
  const toggle = $('#menu-toggle');
  const backdrop = $('#sidebar-backdrop');
  const shouldOpen = Boolean(open);
  app?.classList.toggle('menu-open', shouldOpen);
  sidebar?.classList.toggle('open', shouldOpen);
  if (toggle) {
    toggle.setAttribute('aria-expanded', String(shouldOpen));
    toggle.setAttribute('aria-label', shouldOpen ? 'Fechar menu' : 'Abrir menu');
  }
  if (backdrop) backdrop.hidden = !shouldOpen;
  if (shouldOpen) {
    $('#menu-close')?.focus?.();
  } else if (restoreFocus) {
    toggle?.focus?.();
  }
}

function resetToLoginView(message = '') {
  me = null;
  ws = {};
  data = {};
  loadErrors = [];
  proposalsData = null;
  reportsData = null;
  missionsAdminData = null;
  questionPage = null;
  questionCursor = null;
  questionCursorStack = [];
  selectedExam = null;
  selectedQuestion = null;
  selectedEntity = null;
  storageBlocked = false;
  unreadableStorage = null;
  quotaExceeded = false;
  busy = false;
  lastRenderedRoute = null;
  openDetailsByRoute.clear();
  examUiState.clear();
  document.body?.classList?.remove('busy');
  setMobileMenuOpen(false);
  const progress = $('#progress');
  if (progress) progress.hidden = true;
  notice('');
  $('#view')?.replaceChildren();
  document.querySelectorAll('#app button').forEach(node => { node.disabled = false; });
  const app = $('#app');
  const login = $('#login');
  if (app) app.hidden = true;
  if (login) login.hidden = false;
  const loginMsg = $('#login-message');
  if (loginMsg) loginMsg.textContent = message;
  $('#login-email')?.focus?.();
}

function handleSessionExpired() {
  if (!storageBlocked && me) {
    try { persist(); } catch (_) {}
  }
  resetToLoginView('Sua sessão expirou ou foi encerrada. Seus rascunhos locais foram preservados; entre novamente com seu e-mail e senha.');
}

export async function performLogout() {
  try {
    if (!storageBlocked && me) persist();
  } catch (_) {
  }
  try {
    await api('/api/v1/auth/web-logout', {method:'POST', skipAuthRedirect:true});
  } catch (_) {
  } finally {
    resetToLoginView('Sessão encerrada e revogada. Seus rascunhos locais foram preservados nesta conta.');
  }
}

export async function api(path, {method='GET', body, skipAuthRedirect=false} = {}) {
  const cleanPath = String(path ?? '').replace(/^\/api\/v1\//, '').replace(/^\/+/, '');
  if (!/^(admin|catalog|exams|auth)(\/|$)/.test(cleanPath) || cleanPath.includes('..')) throw new Error('Rota inválida.');
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 45000);
  try {
    const headers = {'X-Cogniva-CSRF': '1'};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(`/api/v1/${cleanPath}`, {
      method,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal
    });
    const payload = await response.json().catch(() => ({message: `Resposta inesperada (HTTP ${response.status}).`}));
    if (!response.ok) {
      const error = new Error(payload.message || `HTTP ${response.status}`);
      error.status = response.status;
      if (response.status === 401 && !skipAuthRedirect && (me !== null || ($('#app') && !$('#app').hidden))) {
        error.handledSessionExpiry = true;
        handleSessionExpired();
      }
      throw error;
    }
    return payload;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('Tempo de resposta excedido. Os comandos permanecem salvos; confira ou reenvie a mesma operação.');
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function run(message, action) {
  if (busy) return;
  busy = true;
  document.body?.classList?.add('busy');
  const progress = $('#progress');
  if (progress) {
    progress.textContent = message;
    progress.hidden = false;
  }
  document.querySelectorAll('#app button,#view input,#view select,#view textarea').forEach(node => { node.disabled = true; });
  try {
    await action();
  } catch (error) {
    if (error.status === 401 || error.handledSessionExpiry) {
      if (me !== null || ($('#app') && !$('#app').hidden)) handleSessionExpired();
      return;
    }
    notice(error.message, 'error');
  } finally {
    busy = false;
    document.body?.classList?.remove('busy');
    if (progress) progress.hidden = true;
    document.querySelectorAll('#app button').forEach(node => { node.disabled = false; });
    if (me) render();
  }
}

function download(name,value) {downloadText(name,pretty(value),'application/json');}
function downloadText(name,text,type='text/plain') {
  const url=URL.createObjectURL(new Blob([text],{type}));
  const a=h('a',{href:url,download:name});document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
}

export async function confirm(title, text) {
  const dialog = $('#confirm-dialog');
  if (!dialog) return false;
  if (pendingConfirmResolve) {
    pendingConfirmResolve(false);
  }
  const titleNode = $('#confirm-title');
  const textNode = $('#confirm-text');
  if (titleNode) titleNode.textContent = title;
  if (textNode) textNode.textContent = text;
  return new Promise(resolve => {
    let settled = false;
    const finish = result => {
      if (settled) return;
      settled = true;
      if (pendingConfirmResolve === finish) pendingConfirmResolve = null;
      dialog.removeEventListener('close', onClose);
      dialog.removeEventListener('cancel', onCancel);
      resolve(result);
    };
    const onClose = () => finish(dialog.returnValue === 'confirm');
    const onCancel = event => {
      event?.preventDefault?.();
      dialog.returnValue = 'cancel';
      if (dialog.open) dialog.close('cancel');
      else finish(false);
    };
    pendingConfirmResolve = finish;
    dialog.addEventListener('close', onClose);
    dialog.addEventListener('cancel', onCancel);
    dialog.returnValue = 'cancel';
    if (!dialog.open) dialog.showModal();
  });
}

function heading(title,subtitle,...buttons) {
  return h('div',{class:'page-heading'},
    h('div',{},h('span',{class:'eyebrow'},'COGNIVA · ADMINISTRAÇÃO'),h('h1',{},title),h('p',{},subtitle)),
    actions(...buttons,current!=='help'?button('Ajuda desta tela',()=>{helpFocus=helpForPage[current] || 'primeiros-passos';navigate('help');}):null)
  );
}

function structureGuide(expanded=false) {
  return h('details',{class:'card structure-guide','data-panel-key':'structure-guide',...(expanded?{open:true}:{})},
    h('summary',{},'Como troncos, templates globais, conteúdo e questões se conectam'),
    h('div',{class:'structure-grid'},
      h('section',{},
        h('h3',{},'Templates globais e grades dos estudantes'),
        h('p',{class:'structure-path'},'Tronco → Template Global → Componentes por etapa'),
        h('p',{},'O painel gerencia os templates globais de concursos (exam_templates). Alunos sem customização recebem atualizações automaticamente; alunos que personalizaram sua grade recebem aviso opcional para adotar a nova revisão.'),
        actions(button('Troncos',()=>navigate('trunks')),button('Concursos',()=>navigate('exams')),button('Grades',()=>navigate('edital')))
      ),
      h('section',{},
        h('h3',{},'Catálogo canônico unificado (v2)'),
        h('p',{class:'structure-path'},'Disciplina → Assunto → Subassunto'),
        h('p',{},'Ícones de tronco, pesos padrão e tópicos vivem em um único catálogo versionado. Cada componente da grade seleciona os assuntos e subassuntos cobrados naquela etapa.'),
        button('Explorar disciplinas e assuntos',()=>navigate('subjects'))
      )
    ),
    h('section',{class:'structure-connection'},
      h('h3',{},'Curadoria de questões, classificação IA e reportes'),
      h('p',{},'Toda questão usa hash canônico único JCS SHA-256 (content_revision), validade explícita e classificação auditável. Use Classificação IA para triar questões sem tópico e Reportes para moderar sinalizações dos estudantes.'),
      actions(button('Banco de questões',()=>navigate('questions')),button('Classificação IA',()=>navigate('classification')),button('Reportes',()=>navigate('reports')))
    )
  );
}

export function navigate(page) {
  if (busy) return;
  const changedPage = current !== page;
  current = page;
  setMobileMenuOpen(false);
  notice('');
  render();
  if (changedPage) {
    window.scrollTo?.({top:0});
    $('#content')?.focus?.({preventScroll:true});
  }
}

export async function loadData() {
  const requests = [
    ['catálogo base', 'base', () => api('admin/catalog/base')],
    ['catálogo de troncos', 'templates', () => api('catalog/trunks')],
    ['templates de concursos', 'exams', () => api('admin/exam-templates')],
    ['capacidades de IA', 'capabilities', () => api('admin/questions/import/capabilities')],
    ['indicadores do acervo', 'summary', () => api('admin/summary')]
  ];
  const settled = await Promise.allSettled(requests.map(([, , fn]) => fn()));
  for (const res of settled) {
    if (res.status === 'rejected' && (res.reason?.status === 401 || res.reason?.handledSessionExpiry)) {
      throw res.reason;
    }
  }
  const failures = [];
  const nextData = {...data};
  settled.forEach((res, idx) => {
    const [label, key] = requests[idx];
    if (res.status === 'fulfilled') {
      if (key === 'exams') {
        nextData.exams = res.value.filter(e => e.status !== 'EXCLUIDO');
        nextData.examsLoadFailed = false;
        if (reconcileExamDrafts(ws, nextData.exams) && me && !storageBlocked) {
          persist();
        }
      } else {
        nextData[key] = res.value;
      }
    } else {
      failures.push(`${label} (${res.reason?.message || 'falha de comunicação'})`);
      if (key === 'exams' && !Array.isArray(nextData.exams)) {
        nextData.examsLoadFailed = true;
      }
      if (key === 'capabilities' && !nextData.capabilities) {
        nextData.capabilities = {ai_available: false};
      }
      if (key === 'templates' && !nextData.templates) {
        nextData.templates = {trunks: []};
      }
    }
  });
  data = nextData;
  loadErrors = failures;
}

export async function bootstrap() {
  if (bootstrapInFlight) return bootstrapInFlight;
  bootstrapInFlight = (async () => {
    const mePayload = await api('admin/web/me', {skipAuthRedirect: true});
    me = mePayload;
    $('#login').hidden = true;
    $('#app').hidden = false;
    $('#account-name').textContent = me.profile.display_name || me.profile.email || `Conta ${me.user_id}`;
    const saved = localStorage.getItem(`cogniva-admin:${me.user_id}`);
    try {
      ws = saved ? JSON.parse(saved) : {};
      if (!ws || typeof ws !== 'object' || Array.isArray(ws)) throw new Error();
    } catch {
      storageBlocked = true;
      unreadableStorage = saved;
      ws = {};
    }
    ws.exams ||= {};
    ws.questionEdits ||= {};
    ws.archives = sanitizeArchives(ws.archives);
    try {
      await loadData();
    } catch (error) {
      if (error.status === 401 || error.handledSessionExpiry) throw error;
      loadErrors = [error.message || 'Falha ao carregar dados iniciais.'];
    }
    render();
  })();
  try {
    return await bootstrapInFlight;
  } finally {
    bootstrapInFlight = null;
  }
}

export function openQuestionEditor(questionId) {
  run('Abrindo questão…', async () => {
    selectedQuestion = questionId;
    let serverDoc = null;
    try {
      serverDoc = await api(`admin/questions/v2/${encodeURIComponent(selectedQuestion)}`);
    } catch (error) {
      if (error.status === 401 || error.handledSessionExpiry || !ws.questionEdits[selectedQuestion]) throw error;
      current = 'question';
      notice(`Não foi possível consultar o servidor (${error.message}). Abrindo rascunho salvo localmente.`, 'warning');
      return;
    }
    const outcome = reconcileQuestionDraft(ws, selectedQuestion, serverDoc, uid);
    persist();
    current = 'question';
    if (outcome.status === 'conflict') {
      notice('Conflito de revisão detectado: a questão foi atualizada no servidor enquanto havia alterações locais não salvas.', 'warning');
    }
  });
}

function captureViewUiState(view) {
  if (!view || lastRenderedRoute !== current) return null;
  const detailsMap = openDetailsByRoute.get(current) || new Map();
  view.querySelectorAll('details').forEach(detail => {
    const key = detail.id || detail.getAttribute('data-panel-key');
    if (key) detailsMap.set(key, Boolean(detail.open));
  });
  openDetailsByRoute.set(current, detailsMap);

  const active = document.activeElement;
  if (!active || active === document.body || !view.contains?.(active)) {
    return {detailsMap, focus: null};
  }
  const focusKey = active.getAttribute?.('data-focus-key')
    || active.id
    || (active.tagName === 'BUTTON' ? `btn:${active.textContent}` : null);
  if (!focusKey) return {detailsMap, focus: null};
  return {
    detailsMap,
    focus: {
      focusKey,
      selectionStart: typeof active.selectionStart === 'number' ? active.selectionStart : null,
      selectionEnd: typeof active.selectionEnd === 'number' ? active.selectionEnd : null
    }
  };
}

function restoreViewUiState(view, snapshot) {
  const detailsMap = openDetailsByRoute.get(current);
  if (detailsMap) {
    view.querySelectorAll('details').forEach(detail => {
      const key = detail.id || detail.getAttribute('data-panel-key');
      if (key && detailsMap.has(key)) {
        detail.open = detailsMap.get(key);
      }
    });
  }
  view.querySelectorAll('details').forEach(detail => {
    const key = detail.id || detail.getAttribute('data-panel-key');
    if (!key) return;
    detail.addEventListener('toggle', () => {
      const routeMap = openDetailsByRoute.get(current) || new Map();
      routeMap.set(key, Boolean(detail.open));
      openDetailsByRoute.set(current, routeMap);
    });
  });

  if (!snapshot?.focus || busy) return;
  const {focusKey, selectionStart, selectionEnd} = snapshot.focus;
  let target = null;
  for (const el of view.querySelectorAll('[data-focus-key],input,select,textarea,button')) {
    const candidateKey = el.getAttribute?.('data-focus-key')
      || el.id
      || (el.tagName === 'BUTTON' ? `btn:${el.textContent}` : null);
    if (candidateKey === focusKey && !el.disabled) {
      target = el;
      break;
    }
  }
  if (target) {
    try { target.focus({preventScroll: true}); } catch { target.focus?.(); }
    if (typeof selectionStart === 'number' && typeof target.setSelectionRange === 'function') {
      try { target.setSelectionRange(selectionStart, selectionEnd ?? selectionStart); } catch (_) {}
    }
  }
}

function partialLoadBanner() {
  if (!loadErrors.length) return null;
  return h('section',{class:'card partial-load-banner',role:'alert'},
    badge('Carregamento parcial','warning'),
    h('p',{},`Alguns dados não puderam ser carregados do servidor: ${loadErrors.join('; ')}. Evitamos exibir listas vazias enganosas; tente recarregar a conexão.`),
    button('Tentar novamente',()=>run('Recarregando dados do painel…',async()=>{
      await loadData();
      if (!loadErrors.length) notice('Todos os dados do painel foram sincronizados.');
    }),'primary')
  );
}

export function render() {
  if (!me) return;
  fieldKeyCounts = new Map();
  $('#breadcrumb').textContent = `Administração / ${labels[current] || current}`;
  document.querySelectorAll('[data-page]').forEach(node => {
    const active = node.dataset.page === current
      || (current === 'exam' && node.dataset.page === 'exams')
      || (current === 'question' && node.dataset.page === 'questions')
      || (current === 'entity' && node.dataset.page === 'catalog');
    node.classList.toggle('active', active);
    if (active) node.setAttribute('aria-current','page'); else node.removeAttribute('aria-current');
  });
  const view = $('#view');
  const snapshot = captureViewUiState(view);
  view.replaceChildren();
  if (storageBlocked) {
    view.append(card(
      h('h2',{},'O rascunho local está ilegível e foi preservado'),
      h('p',{},'Exporte a cópia original antes de restaurar um arquivo válido da mesma conta. Nenhum envio será iniciado.'),
      button('Exportar cópia original',()=>downloadText('cogniva-rascunho-recuperacao.txt',unreadableStorage || '')),
      h('input',{type:'file',accept:'.json,application/json','aria-label':'Restaurar arquivo válido',onchange:async e=>{
        try{
          const file=e.target.files[0];if(!file)return;
          if(file.size>4*1024*1024)throw new Error('Arquivo excede 4 MiB.');
          const recovered=restoreWorkspace(me.user_id,JSON.parse(await file.text()));
          if(await confirm('Restaurar após exportar a cópia original?','O arquivo válido substituirá a cópia ilegível apenas no armazenamento local desta conta.')){
            storageBlocked=false;ws=recovered;ws.exams ||= {};ws.questionEdits ||= {};ws.archives = sanitizeArchives(ws.archives);
            persist();
            unreadableStorage=null;
            render();
          }
        }catch(error){notice(error.message,'error');}
      }})
    ));
    lastRenderedRoute = current;
    return;
  }
  const banner = partialLoadBanner();
  if (!data.base && current !== 'help' && current !== 'drafts') {
    view.append(
      heading('Conectar ao acervo','Não foi possível carregar o catálogo base no momento.'),
      banner || card(
        h('p',{},'Carregue os dados iniciais do servidor para continuar.'),
        button('Tentar novamente',()=>run('Carregando…',loadData),'primary')
      )
    );
    lastRenderedRoute = current;
    return;
  }
  const views = {
    overview:overviewView,
    missions:missionsView,
    exams:examsView,
    edital:examsView,
    exam:examView,
    trunks:trunksView,
    subjects:subjectsView,
    questions:questionsView,
    import:importView,
    question:questionView,
    classification:classificationView,
    reports:reportsView,
    catalog:catalogView,
    entity:entityView,
    drafts:draftsView,
    help:helpView
  };
  try {
    const page = views[current]();
    if (banner) {
      page.firstElementChild ? page.firstElementChild.after(banner) : page.prepend(banner);
    }
    if (['overview','exams','edital','exam','trunks','subjects','questions','import','question','classification','reports','catalog','entity'].includes(current)) {
      const anchor = banner || page.firstElementChild;
      anchor?.after(structureGuide(current === 'overview'));
    }
    view.append(page);
    restoreViewUiState(view, snapshot);
  } catch (error) {
    view.append(card(
      h('h2',{},'Rascunho preservado'),
      h('p',{},`Não foi possível abrir esta tela: ${error.message}`),
      button('Exportar dados locais',()=>download('cogniva-rascunhos.json',exportWorkspace(me.user_id,ws)))
    ));
  }
  lastRenderedRoute = current;
  if (busy) view.querySelectorAll('input,select,textarea,button').forEach(node => { node.disabled = true; });
}

function helpView() {
  const root=h('div',{},heading('Ajuda do portal','Entenda a estrutura, encontre uma tela e siga os passos para concluir seu trabalho.'));
  const count=h('p',{class:'help-count',role:'status','aria-live':'polite'});
  const articles=h('div',{class:'help-articles'});
  const draw=query=>{
    const found=searchHelp(query);
    count.textContent=query.trim()?`${found.length} de ${helpTopics.length} tópicos encontrados`:`${helpTopics.length} tópicos · escolha um tema ou use a busca`;
    articles.replaceChildren(...found.map(topic=>h('details',{class:'card help-article',id:`help-${topic.id}`,open:!!query.trim() || topic.id===helpFocus},
      h('summary',{},h('span',{class:'eyebrow'},topic.category),h('span',{class:'help-title'},topic.title)),
      h('p',{},topic.intro),h('ol',{},...topic.steps.map(step=>h('li',{},step))),
      ...topic.notes.map(note=>h('p',{class:'help-note'},note)),
      button(topic.action,()=>navigate(topic.page),'primary'))));
    if(!found.length)articles.append(card(h('h2',{},'Nenhum tópico encontrado'),h('p',{},'Tente palavras como concurso, pré-edital, questões, validade, classificação, reportes, LGPD ou sessão.')));
  };
  const search=h('input',{type:'search','data-focus-key':'help:search','aria-label':'Buscar na ajuda',placeholder:'Buscar: pré-edital, validade, classificação IA, reportes, LGPD, sessão…',oninput:e=>draw(e.target.value)});
  const toc=h('nav',{class:'help-index','aria-label':'Tópicos da ajuda'},...helpTopics.map(topic=>button(topic.title,()=>{search.value='';helpFocus=topic.id;draw('');document.getElementById(`help-${topic.id}`)?.scrollIntoView?.({behavior:'smooth',block:'start'});})));
  root.append(
    h('section',{class:'card help-search'},h('label',{},'O que você quer fazer?',search),count),
    h('details',{class:'card help-toc','data-panel-key':'help-toc'},h('summary',{},'Todos os tópicos da ajuda'),toc),
    articles
  );
  draw('');
  if(helpFocus && typeof requestAnimationFrame === 'function')requestAnimationFrame(()=>document.getElementById(`help-${helpFocus}`)?.scrollIntoView?.({block:'start'}));
  return root;
}

function overviewView() {
  const root=h('div',{},heading('Seu acervo, em perspectiva.','Gerencie templates globais, curadoria v2, classificação assistida e moderação.',button('＋ Inserir questões',()=>navigate('import'),'primary')));
  const summary=data.summary || {};
  const examCount = summary.exam_templates ?? (Array.isArray(data.exams) ? data.exams.length : '—');
  const stats=[
    ['TEMPLATES GLOBAIS',examCount,'Concursos oficiais / pré-editais'],
    ['QUESTÕES TOTAIS',summary.questions ?? '—',`${summary.questions_current ?? 0} atuais · ${summary.questions_under_review ?? 0} em revisão · ${summary.questions_obsolete ?? 0} obsoletas`],
    ['SEM CLASSIFICAÇÃO',summary.questions_unclassified ?? '—',`${summary.pending_proposals ?? 0} proposta(s) IA pendente(s)`],
    ['DISCURSIVAS',summary.questions_open_ended ?? '—','Acervo preparatório (OPEN_ENDED)'],
    ['REPORTES ABERTOS',summary.open_reports ?? '—','Aguardando moderação']
  ];
  root.append(h('div',{class:'stat-grid'},...stats.map(([label,value,note])=>h('section',{class:'card stat'},h('span',{},label),h('strong',{},value),h('small',{},note)))));
  root.append(h('section',{class:'card hero'},
    h('div',{},
      h('span',{class:'eyebrow'},'CURADORIA GLOBAL COGNIVA 2.0'),
      h('h2',{},'Templates globais e catálogo unificado para toda a base.'),
      h('p',{},'Atualize editais oficiais sem sobrescrever customizações pessoais dos alunos e mantenha o banco 100% no contrato JCS v2.'),
      actions(button('Criar template de concurso',()=>openExam(),'primary'),button('Abrir classificação IA',()=>navigate('classification')),button('Moderar reportes',()=>navigate('reports')))
    ),
    h('div',{class:'hero-art','aria-hidden':'true'},'↗')
  ));
  root.append(h('div',{class:'split'},
    card(
      h('h2',{},'Acesso rápido'),
      h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Templates globais de concursos'),h('small',{},'CPNU, RFB, BNDES, AFT e novos editais compartilhados.')),button('Organizar →',()=>navigate('exams'))),
      h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Banco de questões e validade'),h('small',{},'Filtre por validade ou classificação, edite explicação, ano e nível.')),button('Explorar →',()=>navigate('questions'))),
      h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Classificação IA'),h('small',{},'Gere e revise propostas para questões ainda sem tópico canônico.')),button('Classificar →',()=>navigate('classification'))),
      h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Reportes de estudantes'),h('small',{},'Modere sinalizações sem bloqueio global indevido.')),button('Moderar →',()=>navigate('reports'))),
      h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Revisões do catálogo'),h('small',{},'Prepare alterações versionadas no catálogo unificado.')),button('Revisar →',()=>navigate('catalog')))
    ),
    card(
      h('h2',{},'Convergência Cogniva 2.0'),
      h('p',{},'Catálogo canônico unificado, hash único JCS SHA-256 e templates globais desacoplados das grades personalizadas dos alunos.'),
      badge(`Catálogo ativo v${data.base.catalog.catalog_version}`,'good'),
      badge(`${data.base.catalog.trunks.length} troncos`),
      badge(`${data.base.catalog.subjects.length} disciplinas`),
      h('hr',{class:'divider'}),
      h('p',{},'Use Atualizar indicadores para sincronizar contagens de validade, propostas de IA e reportes abertos.'),
      button('Atualizar indicadores',()=>run('Atualizando indicadores…',async()=>{data.summary=await api('admin/summary');notice('Indicadores atualizados.');}))
    )
  ));
  return root;
}

function searchable(placeholder, rows, onFilter) {
  const input = h('input',{
    type:'search',
    'data-focus-key':`search:${current}:${placeholder}`,
    placeholder,
    'aria-label':placeholder,
    oninput:e=>{
      const query = e.target.value;
      rows.forEach(([node,text,detail])=>{
        const matched = matchesSearch(text, query);
        node.hidden = !matched;
        if (detail) {
          if (query.trim()) detail.open = matched;
          else {
            const saved = openDetailsByRoute.get(current)?.get(detail.getAttribute('data-panel-key'));
            detail.open = Boolean(saved);
          }
        }
      });
      onFilter?.(query);
    }
  });
  return h('div',{class:'toolbar'},input);
}

function examsView() {
  const root=h('div',{},heading(
    current==='edital'?'Editais e grades (Templates globais)':'Concursos (Templates globais)',
    current==='edital'
      ?'Organize disciplinas por etapa, pesos, quantidades e tópicos do template global e publique o edital oficial quando confirmado.'
      :'Gerencie os templates globais servidos aos alunos. Alunos sem customização recebem atualizações automaticamente.',
    button('＋ Criar template de concurso',()=>openExam(),'primary')
  ));
  if (!Array.isArray(data.exams)) {
    root.append(card(
      h('h2',{},'Templates globais indisponíveis no momento'),
      h('p',{},'A consulta aos templates de concursos falhou. Clique abaixo para tentar novamente.'),
      button('Tentar novamente',()=>run('Recarregando templates…',loadData),'primary')
    ));
    return root;
  }
  const tbody=h('tbody'),rows=[];
  for(const exam of data.exams){
    if(exam.status==='EXCLUIDO')continue;
    const trunkLabel=data.base.catalog.trunks.find(t=>t.trunk_id===exam.trunk_slug)?.label || exam.trunk_slug;
    const node=h('tr',{},
      h('td',{},h('strong',{},exam.short_name),h('span',{class:'secondary'},`${exam.title} · ID: ${exam._id}`)),
      h('td',{},trunkLabel),
      h('td',{},badge(lifecycle[exam.status] || exam.status,exam.status==='EDITAL_PUBLICADO'?'good':exam.status==='ARQUIVADO'?'warning':'')),
      h('td',{},`${exam.subjects.length} componente(s)`,h('span',{class:'secondary'},`Revisão ${exam.revision}`)),
      h('td',{},button('Abrir grade →',()=>openExam(exam)))
    );
    tbody.append(node);
    rows.push([node,`${exam._id} ${exam.title} ${exam.short_name} ${exam.institution || ''} ${exam.role || ''} ${trunkLabel} ${exam.trunk_slug} ${lifecycle[exam.status] || exam.status}`]);
  }
  root.append(
    searchable('Buscar template, instituição, cargo, ID, tronco ou situação…',rows),
    h('div',{class:'table-wrap'},h('table',{},h('thead',{},h('tr',{},...['Template','Tronco','Situação','Grade',''].map(t=>h('th',{},t)))),tbody))
  );
  return root;
}

export function openExam(exam=null,trunk='fiscal') {
  const model = exam || blankExam(uid(),trunk);
  selectedExam = model._id;
  if (!Object.hasOwn(ws.exams,selectedExam)) {
    ws.exams = {...ws.exams,[selectedExam]:{document:copy(model),original:copy(model),isNew:!exam,pending:null}};
    persist();
  } else if (exam) {
    const existing = ws.exams[selectedExam];
    if (!isExamDraftDirty(existing)) {
      ws.exams[selectedExam] = {document:copy(exam),original:copy(exam),isNew:false,pending:null};
      persist();
    } else if ((exam.revision ?? 0) > (existing.original?.revision ?? 0)) {
      existing.server = copy(exam);
      existing.conflict = true;
      persist();
    }
  }
  navigate('exam');
  if (ws.exams[selectedExam]?.conflict) {
    notice('Conflito de revisão detectado: este template foi atualizado no servidor enquanto havia edições locais.', 'warning');
  }
}

function setExam(mutator,rerender=false) {edit(()=>mutator(ws.exams[selectedExam].document));if(rerender)render();}

function trunkOptionsFor(currentSlug) {
  const options=data.base.catalog.trunks
    .filter(t=>t.status==='ACTIVE' || t.trunk_id===currentSlug)
    .map(t=>[t.trunk_id,t.status==='ACTIVE'?t.label:`Desativado · ${t.label}`]);
  if(currentSlug && !options.some(([id])=>id===currentSlug)){
    options.push([currentSlug,`${currentSlug} (legado)`]);
  }
  return options;
}

function getExamUi(examId, defaultSubject) {
  if (!examUiState.has(examId)) {
    examUiState.set(examId, {addSubject: defaultSubject, topicChoices: {}, customNames: {}});
  }
  const state = examUiState.get(examId);
  if (!state.addSubject && defaultSubject) state.addSubject = defaultSubject;
  return state;
}

function examView() {
  const draft=ws.exams[selectedExam],exam=draft.document,locked=!!draft.pending;
  const root=h('div',{},heading(exam.short_name || 'Novo template global',`Identificador: ${exam._id} · Alterações confirmadas propagam para alunos sem customização.`,button('← Concursos',()=>navigate('exams')),button('Exportar rascunho',()=>download('template-concurso-rascunho.json',draft))));
  if(locked)root.append(card(
    badge('Envio pendente','warning'),
    h('p',{},'O comando foi preservado. Confira ou reenvie antes de editar.'),
    actions(
      button('Conferir / reenviar',()=>run('Conferindo template…',sendExam),'primary'),
      button('Comparar com servidor',()=>run('Consultando versão atual…',async()=>{
        const exams=await api('admin/exam-templates');
        data.exams=exams.filter(e=>e.status!=='EXCLUIDO');
        draft.server=exams.find(e=>e._id===exam._id) || null;
        persist();
      }))
    )
  ));
  if(draft.server)root.append(h('section',{class:'card conflict-banner'},
    badge('Conflito / versão do servidor','warning'),
    h('h2',{},`Versão atual do servidor (revisão ${draft.server.revision ?? 'atual'})`),
    h('p',{},'O template avançou no servidor enquanto sua edição local permanece preservada. Confira as diferenças ou recarregue a versão do servidor.'),
    h('details',{'data-panel-key':`exam-server:${exam._id}`},h('summary',{},'Inspecionar JSON da versão do servidor'),h('pre',{},pretty(draft.server))),
    button('Recarregar versão do servidor',async()=>{
      if(await confirm('Usar a versão do servidor?','A edição atual será mantida em Rascunhos e pendências.')){
        edit(()=>{
          pushArchive(ws,'exam-template',draft);
          ws.exams[selectedExam]={document:copy(draft.server),original:copy(draft.server),isNew:false,pending:null};
        });
        render();
      }
    },'primary')
  ));
  const form=h('form',{onsubmit:e=>{
    e.preventDefault();
    run('Salvando template global…',async()=>{
      validateExamDraft(exam);
      draft.pending={kind:draft.isNew?'create':'update',body:copy(exam)};
      persist();
      await sendExam();
    });
  }});
  const meta=h('fieldset',{disabled:locked},
    h('legend',{},'Dados do template global'),
    h('div',{class:'form-grid'},
      field('ID do template',exam._id,v=>{
        if(!draft.isNew)return;
        const clean=v.trim();
        if(!clean)return;
        edit(()=>{
          const prevKey=selectedExam;
          exam._id=clean;
          if(prevKey!==clean && !Object.hasOwn(ws.exams,clean)){
            ws.exams[clean]=ws.exams[prevKey];
            delete ws.exams[prevKey];
            selectedExam=clean;
          }
        });
      },{required:true,disabled:!draft.isNew,maxlength:100,help:'Apenas letras, números, hífen ou sublinhado (ex.: BCB_2026).'}),
      field('Nome curto',exam.short_name,v=>setExam(e=>e.short_name=v),{required:true,maxlength:100}),
      field('Nome completo',exam.title,v=>setExam(e=>e.title=v),{required:true,maxlength:300,wide:true}),
      field('Instituição',exam.institution,v=>setExam(e=>e.institution=v)),
      field('Cargo',exam.role,v=>setExam(e=>e.role=v)),
      field('Tronco de carreira',exam.trunk_slug,v=>setExam(e=>e.trunk_slug=v),{options:trunkOptionsFor(exam.trunk_slug)}),
      field('Situação',exam.status,v=>setExam(e=>e.status=v),{options:Object.entries(lifecycle).filter(([v])=>v!=='EXCLUIDO')}),
      field('Bancas (separe por vírgula)',exam.exam_boards.join(', '),v=>setExam(e=>e.exam_boards=v.split(',').map(t=>t.trim()).filter(Boolean))),
      field('Prioridade sugerida na escada',exam.ladder_priority,v=>setExam(e=>e.ladder_priority=Number(v)),{type:'number',min:1,max:100,required:true}),
      field('Corte histórico (%)',exam.historical_cutoff_score,v=>setExam(e=>e.historical_cutoff_score=Number(v)),{type:'number',min:0,max:100,step:'0.01'}),
      field('Penalidade por erro',exam.wrong_answer_penalty,v=>setExam(e=>e.wrong_answer_penalty=Number(v)),{type:'number',min:0,max:1,step:'0.01'}),
      field('Previsão da prova',exam.target_date.type,v=>setExam(e=>{e.target_date=v==='EXACT_DATE'?{type:v,iso_date:''}:v==='ESTIMATED_WINDOW'?{type:v,label:'',estimated_sort_date:''}:{type:v};},true),{options:[['UNDEFINED','Sem previsão'],['EXACT_DATE','Data exata'],['ESTIMATED_WINDOW','Janela estimada']]}),
      exam.target_date.type==='EXACT_DATE'?field('Data da prova',exam.target_date.iso_date,v=>setExam(e=>e.target_date.iso_date=v),{type:'date',required:true}):null,
      exam.target_date.type==='ESTIMATED_WINDOW'?field('Janela de previsão',exam.target_date.label,v=>setExam(e=>e.target_date.label=v),{required:true}):null,
      exam.target_date.type==='ESTIMATED_WINDOW'?field('Data estimada para ordenação',exam.target_date.estimated_sort_date,v=>setExam(e=>e.target_date.estimated_sort_date=v),{type:'date',required:true}):null
    ),
    check('Sugerir ativo por padrão na escada inicial',exam.is_active_in_ladder,v=>setExam(e=>e.is_active_in_ladder=v))
  );
  form.append(card(meta));

  const totalDeclared = exam.subjects.reduce((acc,s)=>acc + (Number.isFinite(s.question_count)?Number(s.question_count):0), 0);
  const grade=h('fieldset',{disabled:locked},
    h('legend',{},'Grade do edital · componentes por etapa'),
    h('p',{class:'intro-note'},`Cada componente associa uma disciplina do catálogo a uma etapa deste concurso (total declarado atual: ${totalDeclared}/500 questões). A mesma disciplina pode aparecer em várias etapas com pesos e quantidades próprios.`)
  );
  const activeSubjects = data.base.catalog.subjects.filter(s=>s.status==='ACTIVE');
  const uiState = getExamUi(selectedExam, activeSubjects[0]?.subject_id || '');
  grade.append(actions(
    field('Disciplina do catálogo',uiState.addSubject,v=>{uiState.addSubject=v;},{options:activeSubjects.map(s=>[s.subject_id,s.label]),focusKey:'exam:add-subject'}),
    button('＋ Adicionar componente',()=>{const subject=activeSubjects.find(s=>s.subject_id===uiState.addSubject);if(subject)setExam(e=>e.subjects.push(newComponent(subject,uid())),true);}),
    button('＋ Disciplina personalizada',()=>setExam(e=>e.subjects.push(newComponent({subject_id:`custom-${uid()}`,label:'Nova disciplina'},uid())),true))
  ));
  exam.subjects.forEach((subject,index)=>{
    const compKey = subject.local_subject_id || String(index);
    const update=(fn,redraw=false)=>setExam(e=>fn(e.subjects[index]),redraw);
    const box=h('section',{class:'component'},
      h('div',{class:'component-head'},
        h('div',{},h('h3',{},`${index+1}. ${subject.display_name}`),badge(subject.canonical_subject_id)),
        button('Remover componente',async()=>{if(await confirm('Remover este componente?','O histórico já salvo permanece preservado.'))setExam(e=>e.subjects.splice(index,1),true);},'danger')
      )
    );
    box.append(h('div',{class:'form-grid'},
      field('Nome no edital',subject.display_name,v=>update(s=>s.display_name=v),{required:true,focusKey:`comp:${compKey}:name`}),
      field('Etapa',subject.stage,v=>update(s=>s.stage=v),{maxlength:120,focusKey:`comp:${compKey}:stage`}),
      field('Grupo de conhecimentos',subject.category,v=>update(s=>s.category=v),{options:[['CONHECIMENTOS_GERAIS','Conhecimentos gerais'],['CONHECIMENTOS_ESPECIFICOS','Conhecimentos específicos'],['DISCURSIVA_ESTUDO_DE_CASO','Discursiva / estudo de caso'],['OUTROS','Outros']],focusKey:`comp:${compKey}:cat`}),
      field('Relevância',subject.relevance_tier,v=>update(s=>s.relevance_tier=v),{options:[['CORE','Central'],['COMPLEMENTARY','Complementar'],['SPECIFIC','Específica']],focusKey:`comp:${compKey}:tier`}),
      field('Peso',subject.weight,v=>update(s=>s.weight=v===''?0:Number(v)),{type:'number',min:0.01,max:100,step:0.01,required:true,focusKey:`comp:${compKey}:weight`}),
      field('Quantidade de questões',subject.question_count ?? '',v=>update(s=>{const trimmed=String(v ?? '').trim();s.question_count=trimmed===''?null:Number.parseInt(trimmed,10);}),{type:'number',min:0,max:500,step:1,help:'Deixe vazio quando ainda não definida (máximo de 500 somando a grade).',focusKey:`comp:${compKey}:qcount`})
    ));
    const available=orderedTopics(data.base.catalog,subject.canonical_subject_id)
      .filter(t=>t.status==='ACTIVE' && !subject.topics.some(old=>old.canonical_topic_id===t.topic_id));
    if (uiState.topicChoices[compKey] && !available.some(t => t.topic_id === uiState.topicChoices[compKey])) {
      uiState.topicChoices[compKey] = '';
    }
    const appendTopic=(s,t)=>s.topics.push({local_topic_id:uid(),canonical_topic_id:t.topic_id,canonical_subject_id:s.canonical_subject_id,display_title:t.label,diff_status:'PRE_EDITAL_BASE'});
    box.append(actions(
      field('Adicionar assunto / subassunto',uiState.topicChoices[compKey] || '',v=>{uiState.topicChoices[compKey]=v;},{options:[['','Selecione…'],...available.map(t=>[t.topic_id,topicPath(data.base.catalog,t)])],focusKey:`comp:${compKey}:topic-select`}),
      button('Adicionar assunto',()=>{const topic=available.find(t=>t.topic_id===uiState.topicChoices[compKey]);if(topic){uiState.topicChoices[compKey]='';update(s=>appendTopic(s,topic),true);}}),
      button('Adicionar todos da disciplina',async()=>{if(await confirm('Adicionar todos os assuntos?','Inclui explicitamente assuntos e subassuntos ativos desta disciplina.'))update(s=>available.forEach(t=>appendTopic(s,t)),true);})
    ));
    subject.topics.forEach((topic,ti)=>box.append(h('div',{class:'topic-row'},
      h('span',{},topic.display_title,h('small',{class:'secondary'},` · ${topic.canonical_topic_id}`)),
      h('select',{'aria-label':`Situação de ${topic.display_title}`,'data-focus-key':`comp:${compKey}:topic-status:${topic.local_topic_id || ti}`,onchange:e=>update(s=>s.topics[ti].diff_status=e.target.value)},...Object.entries({PRE_EDITAL_BASE:'Pré-edital',OFICIAL_CONFIRMADO:'Oficial confirmado',OFICIAL_NOVO:'Oficial novo',ARQUIVADO:'Arquivado'}).map(([v,t])=>h('option',{value:v,selected:v===topic.diff_status},t))),
      button('Remover',()=>update(s=>s.topics.splice(ti,1),true))
    )));
    box.append(actions(
      field('Assunto personalizado',uiState.customNames[compKey] || '',v=>{uiState.customNames[compKey]=v;},{focusKey:`comp:${compKey}:custom-topic`}),
      button('Adicionar personalizado',()=>{const custom=(uiState.customNames[compKey] || '').trim();if(custom){uiState.customNames[compKey]='';update(s=>appendTopic(s,{topic_id:`custom-${uid()}`,label:custom}),true);}})
    ));
    grade.append(box);
  });
  form.append(card(grade),actions(
    h('button',{type:'submit',class:'primary',disabled:locked},'Salvar concurso e grade'),
    button('Publicar esta grade como edital',async()=>{
      if(locked)return;
      if(await confirm('Confirmar publicação do edital global?','A grade ativa será comparada ao histórico e registrada como edital oficial deste template global.')) {
        run('Publicando edital…',async()=>{
          if(draft.isNew)throw new Error('Salve o template antes de publicar o edital.');
          validateExamDraft(exam);
          draft.pending={
            kind:'publish',
            body:{
              expected_revision:exam.revision,
              published_topics:exam.subjects.flatMap(s=>s.topics.filter(t=>t.diff_status!=='ARQUIVADO').map(t=>({
                canonical_topic_id:t.canonical_topic_id,
                canonical_subject_id:t.canonical_subject_id,
                display_title:t.display_title,
                local_subject_id:s.local_subject_id
              })))
            }
          };
          persist();
          await sendExam();
        });
      }
    }),
    button('Arquivar template',async()=>{
      if(!locked && await confirm('Arquivar template global?','Ele sai das sugestões iniciais, preservando histórico e grades pessoais dos alunos.')){
        setExam(e=>{e.status='ARQUIVADO';e.is_active_in_ladder=false;});
        run('Arquivando template…',async()=>{
          validateExamDraft(exam);
          draft.pending={kind:draft.isNew?'create':'update',body:copy(exam)};
          persist();
          await sendExam();
        });
      }
    },'danger'),
    !draft.isNew?button('Excluir template',async()=>{
      if(!locked && await confirm('Excluir template global?','O template recebe tombstone EXCLUIDO e deixa de ser listado no catálogo inicial, sem apagar o histórico já praticado pelos estudantes.')){
        setExam(e=>{e.status='EXCLUIDO';e.is_active_in_ladder=false;});
        run('Excluindo template…',async()=>{
          draft.pending={kind:'update',body:copy(exam)};
          persist();
          await sendExam();
          delete ws.exams[selectedExam];
          persist();
          current='exams';
        });
      }
    },'danger'):null
  ));
  root.append(form);
  return root;
}

async function sendExam() {
  const draft=ws.exams[selectedExam],pending=draft.pending;if(!pending)return;
  if(!['create','update','publish'].includes(pending.kind))throw new Error('Comando de concurso inválido.');
  const id=draft.document._id;
  if(!/^[A-Za-z0-9_-]{1,100}$/.test(id))throw new Error('Identidade de concurso inválida (use letras, números, _ ou -).');
  const path=pending.kind==='create'
    ?'admin/exam-templates'
    :`admin/exam-templates/${encodeURIComponent(id)}${pending.kind==='publish'?'/publish-edital':''}`;
  let result;
  try {result=await api(path,{method:pending.kind==='update'?'PUT':'POST',body:pending.body});}
  catch(error){
    if(error.status>=400 && error.status<500){
      pushArchive(ws,'rejected-exam-command',pending);
      draft.pending=null;
      if(error.status===409){
        const exams=await api('admin/exam-templates').catch(()=>null);
        if(exams){
          data.exams=exams.filter(e=>e.status!=='EXCLUIDO');
          draft.server=exams.find(e=>e._id===id) || null;
          draft.conflict=Boolean(draft.server);
        }
      }
      persist();
    }
    throw error;
  }
  const expected=pending.kind==='publish'?pending.body.expected_revision:pending.body.revision;
  if(result._id!==id || (result.revision!==expected+1 && !(pending.body.status==='EXCLUIDO' && result.status==='EXCLUIDO'))){
    throw new Error('O recibo do template não corresponde ao comando. A pendência foi preservada.');
  }
  ws.exams[selectedExam]={document:copy(result),original:copy(result),isNew:false,pending:null};
  persist();
  const templates=await api('admin/exam-templates');
  data.exams=templates.filter(e=>e.status!=='EXCLUIDO');
  data.summary=await api('admin/summary');
  notice('Template global confirmado no servidor.','');
}

function trunksView() {
  const root=h('div',{},heading('Troncos de carreira','Perfis editoriais que conectam disciplinas, pesos padrão e templates globais.',button('Propor alteração no catálogo',()=>startCatalog(),'primary'))),grid=h('div',{class:'cards'}),rows=[];
  const examList = Array.isArray(data.exams) ? data.exams : [];
  for(const trunk of data.base.catalog.trunks){
    const subjects=data.base.catalog.subjects.filter(s=>s.trunk_links.some(l=>l.trunk_id===trunk.trunk_id));
    const linkedExams=examList.filter(exam=>exam.trunk_slug===trunk.trunk_id && exam.status!=='EXCLUIDO');
    const createBtn = trunk.status==='ACTIVE' ? button('Criar concurso',()=>openExam(null,trunk.trunk_id)) : null;
    const box=card(
      badge(trunk.status==='ACTIVE'?'Ativo':'Desativado',trunk.status==='ACTIVE'?'good':''),
      trunk.icon_key?badge(`Ícone: ${trunk.icon_key}`):null,
      h('h2',{},trunk.label),
      h('p',{},`${subjects.length} disciplinas relacionadas`),
      h('small',{},subjects.slice(0,4).map(s=>s.label).join(' · ')),
      h('p',{},`${linkedExams.length} template(s) global(is) neste tronco`),
      actions(...linkedExams.map(exam=>button(`${exam.short_name} → grade`,()=>openExam(exam)))),
      h('hr',{class:'divider'}),
      actions(createBtn,button('Editar proposta',()=>editEntity('trunks',trunk.trunk_id)))
    );
    grid.append(box);rows.push([box,`${trunk.label} ${trunk.trunk_id} ${trunk.aliases.join(' ')}`]);
  }
  root.append(searchable('Buscar tronco…',rows),grid);return root;
}

function subjectsView() {
  const root=h('div',{},heading('Disciplinas e assuntos','Identidades compartilhadas, assuntos e subassuntos do catálogo unificado v2.',button('Propor nova disciplina',()=>editEntity('subjects',null),'primary'))),list=card(),rows=[];
  const examList = Array.isArray(data.exams) ? data.exams : [];
  for(const subject of data.base.catalog.subjects){
    const topics=orderedTopics(data.base.catalog,subject.subject_id);
    const detail=h('details',{'data-panel-key':`subject:${subject.subject_id}`},
      h('summary',{},h('strong',{},subject.label),' ',badge(subject.subject_id),badge(`${topics.length} assuntos / subassuntos`)),
      h('p',{},subject.scope_note),
      h('p',{class:'review-meta'},`Troncos relacionados: ${subject.trunk_links.map(link=>`${data.base.catalog.trunks.find(t=>t.trunk_id===link.trunk_id)?.label || link.trunk_id} (peso ${link.default_weight ?? 1})`).join(' · ') || 'Nenhum vínculo editorial'}`),
      h('small',{},`Aliases: ${subject.aliases.join('; ') || '—'}`),
      h('div',{class:'actions'},button('Editar proposta de disciplina',()=>editEntity('subjects',subject.subject_id)),button('Adicionar assunto na proposta',()=>editEntity('topics',null,subject.subject_id)))
    );
    const uses=examList.flatMap(exam=>exam.subjects.filter(component=>component.canonical_subject_id===subject.subject_id).map(component=>[exam,component]));
    detail.append(h('p',{class:'review-meta'},'Componentes nos templates globais:'),uses.length?actions(...uses.map(([exam,component])=>button(`${exam.short_name} · ${component.stage || 'etapa não definida'}`,()=>openExam(exam)))):h('small',{},'Esta disciplina ainda não foi adicionada aos templates globais.'));
    for(const topic of topics)detail.append(h('div',{class:'catalog-row'},h('div',{},h('strong',{},topicPath(data.base.catalog,topic)),h('small',{},`${topic.topic_id}${topic.parent_topic_id?' · subassunto de '+(data.base.catalog.topics.find(t=>t.topic_id===topic.parent_topic_id)?.label || topic.parent_topic_id):''}`)),button('Propor edição',()=>editEntity('topics',topic.topic_id))));
    const row=h('section',{class:'component'},detail);
    list.append(row);
    rows.push([row,`${subject.label} ${subject.subject_id} ${subject.aliases.join(' ')} ${topics.map(t=>`${topicPath(data.base.catalog,t)} ${t.topic_id}`).join(' ')}`,detail]);
  }
  root.append(searchable('Buscar disciplina, alias ou assunto…',rows),list);return root;
}

function questionsView() {
  const root=h('div',{},heading('Banco de questões','Filtre o acervo e edite questões v2 com validade explícita e metadados preservados.',button('＋ Inserir questões',()=>navigate('import'),'primary')));
  const searchForm=h('form',{class:'toolbar',onsubmit:e=>{e.preventDefault();fetchQuestions(null,{reset:true});}},
    h('input',{type:'search','data-focus-key':'questions:search',placeholder:'Buscar no enunciado…','aria-label':'Buscar no enunciado',value:questionSearch,oninput:e=>questionSearch=e.target.value}),
    h('select',{'aria-label':'Tipo de questão','data-focus-key':'questions:type',onchange:e=>questionType=e.target.value},
      h('option',{value:''},'Todos os tipos'),
      ...Object.entries(kinds).map(([v,t])=>h('option',{value:v,selected:questionType===v},t))
    ),
    h('select',{'aria-label':'Validade da questão','data-focus-key':'questions:validity',onchange:e=>questionValidity=e.target.value},
      h('option',{value:''},'Todas as validades'),
      ...Object.entries(statuses).map(([v,t])=>h('option',{value:v,selected:questionValidity===v},t))
    ),
    h('select',{'aria-label':'Classificação da questão','data-focus-key':'questions:classification',onchange:e=>questionClassState=e.target.value},
      h('option',{value:''},'Todas as classificações'),
      ...[['CONFIRMED','Confirmada'],['UNCLASSIFIED','Sem classificação']].map(([v,t])=>h('option',{value:v,selected:questionClassState===v},t))
    ),
    h('button',{type:'submit'},'Buscar')
  );
  root.append(searchForm);
  if(!questionPage){root.append(card(h('p',{},'Carregue uma página do banco para começar.'),button('Carregar questões',()=>fetchQuestions(null,{reset:true}),'primary')));return root;}
  const tbody=h('tbody');
  const today=new Date().toLocaleDateString('en-CA',{timeZone:'America/Sao_Paulo'});
  for(const q of questionPage.items){
    const vStatus=q.validity?.status || 'UNKNOWN';
    const cState=q.classification?.state || 'UNCLASSIFIED';
    tbody.append(h('tr',{},
      h('td',{},h('p',{},q.question.length>220?q.question.slice(0,220)+'…':q.question),h('span',{class:'secondary'},`${q._id.$oid}${q.disciplina?' · '+q.disciplina:''}${q.exam_year?' · '+q.exam_year:''}`)),
      h('td',{},badge(kinds[q.question_type] || q.question_type || 'Objetiva',q.question_type==='OPEN_ENDED'?'warning':''),h('span',{class:'secondary'},q.source || 'Origem não informada')),
      h('td',{},
        badge(validityPresentation(q.validity,today),vStatus==='CURRENT'?'good':vStatus==='OBSOLETE'?'danger':'warning'),
        badge(classificationStates[cState] || cState,cState==='CONFIRMED'?'good':'warning')
      ),
      h('td',{},button('Abrir →',()=>openQuestionEditor(q._id.$oid)))
    ));
  }
  const prevBtn = button('← Página anterior', () => {
    if (!questionCursorStack.length) return;
    const prevCursor = questionCursorStack[questionCursorStack.length - 1];
    fetchQuestions(prevCursor, {popPrev: true});
  });
  prevBtn.disabled = questionCursorStack.length === 0;
  const nextBtn = button('Próxima página →', () => {
    if (!questionPage.after) return;
    fetchQuestions(questionPage.after, {pushPrev: true});
  });
  nextBtn.disabled = !questionPage.after;

  root.append(
    h('div',{class:'count'},`Página ${questionCursorStack.length + 1} · ${questionPage.items.length} questões nesta página`),
    questionPage.items.length?h('div',{class:'table-wrap'},h('table',{},h('thead',{},h('tr',{},...['Enunciado','Tipo / origem','Validade / classificação',''].map(t=>h('th',{},t)))),tbody)):h('p',{class:'empty'},'Nenhuma questão encontrada para os filtros selecionados.'),
    h('div',{class:'actions'},
      prevBtn,
      nextBtn,
      button('Recarregar início',()=>fetchQuestions(null,{reset:true}))
    )
  );
  return root;
}

function fetchQuestions(after=null, nav={}) {
  run('Consultando questões…',async()=>{
    const query=new URLSearchParams({limit:'25'});
    if(questionSearch.trim())query.set('search',questionSearch.trim());
    if(questionType)query.set('question_type',questionType);
    if(questionValidity)query.set('validity_status',questionValidity);
    if(questionClassState)query.set('classification_state',questionClassState);
    if(after)query.set('after',after);
    const result=await api(`admin/questions?${query}`);
    if (nav.reset) {
      questionCursorStack = [];
      questionCursor = null;
    } else if (nav.pushPrev) {
      questionCursorStack = [...questionCursorStack, questionCursor];
      questionCursor = after;
    } else if (nav.popPrev) {
      questionCursorStack = questionCursorStack.slice(0, -1);
      questionCursor = after;
    } else {
      questionCursor = after;
    }
    questionPage = result;
    notice('');
  });
}

function questionView() {
  const draft=ws.questionEdits[selectedQuestion],original=draft.original;
  const root=h('div',{},heading('Revisar questão',selectedQuestion,button('← Banco de questões',()=>navigate('questions')),button('Exportar contrato',()=>download('questao-v2.json',original))));
  if(draft.submitted && !draft.result)root.append(card(
    badge('Edição aguardando confirmação','warning'),
    h('p',{},'Os comandos permanecem congelados. Se houver conflito de revisão, compare a versão atual antes de continuar.'),
    actions(
      button('Comparar versão do servidor',()=>run('Conferindo a questão…',async()=>{
        draft.server=await api(`admin/questions/v2/${encodeURIComponent(selectedQuestion)}`);
        draft.conflict = (draft.server?.revision ?? 0) !== (original.revision ?? 0);
        persist();
      })),
      button('Desbloquear rascunho para edição',()=>{edit(()=>{draft.submitted=false;});render();})
    )
  ));
  if(draft.server)root.append(h('section',{class:'card conflict-banner',role:'alert'},
    badge('Conflito de revisão','warning'),
    h('h2',{},`Nova versão disponível no servidor (revisão ${draft.server.revision ?? 'atual'})`),
    h('p',{},`O servidor possui uma revisão mais recente desta questão enquanto o rascunho local contém alterações não salvas (baseadas na revisão ${original.revision ?? 0}). Recarregue a versão do servidor para atualizar sua base; seu rascunho atual ficará guardado no histórico local.`),
    h('details',{'data-panel-key':`question-server:${selectedQuestion}`},h('summary',{},'Ver contrato atual no servidor'),h('pre',{},pretty(draft.server))),
    actions(
      button('Recarregar versão do servidor',async()=>{
        if(await confirm('Recarregar versão do servidor?','Sua edição local será arquivada em Rascunhos e pendências antes de carregar a versão atual do servidor.')){
          edit(()=>{
            pushArchive(ws,'question-edit',draft);
            ws.questionEdits[selectedQuestion]={original:copy(draft.server),text:pretty(draft.server),operationId:uid(),preview:null,submitted:false,result:null,reconfirmValidity:false};
          });
          notice('Versão do servidor carregada.');
          render();
        }
      },'primary')
    )
  ));
  if(original.schema_version!==2 && !draft.converted){
    root.append(card(
      badge('Normalização de contrato v2','warning'),
      h('h2',{},original.question),
      h('p',{},'Prepare a edição no contrato v2 mantendo o ID e todos os metadados existentes.'),
      button('Preparar edição v2',()=>{
        edit(()=>{
          const q=legacyQuestionDraft(original,data.base.catalog,me.user_id,uid());
          draft.text=pretty(q);draft.converted=true;draft.operationId=uid();
          draft.classificationChoice={subjectId:original.canonical_subject_id || '',topicId:original.canonical_topic_id || '',confirmed:!original.canonical_topic_id};
        });
        render();
      },'primary'),
      h('pre',{},pretty(original))
    ));
    return root;
  }
  let parsed;
  try{
    parsed=JSON.parse(draft.text);
    if(!parsed || !Array.isArray(parsed.answers) || !Array.isArray(parsed.classification?.links) || !parsed.validity)throw new Error('Preserve alternativas, classificação e validade do contrato v2.');
  } catch(error){
    root.append(card(
      h('h2',{},'Corrigir o contrato JSON'),
      h('p',{},error.message),
      field('Documento JSON em correção',draft.text,value=>edit(()=>{if(!draft.submitted){draft.text=value;draft.preview=null;draft.operationId=uid();}}),{multiline:true,rows:20,code:true,disabled:draft.submitted}),
      button('Reabrir formulário',render)
    ));
    return root;
  }

  if(parsed.question_type==='OPEN_ENDED')root.append(openEndedBanner());

  const raw=field('Documento JSON',draft.text,v=>edit(()=>{if(draft.submitted)return;draft.text=v;delete draft.rubricText;draft.rubricError=null;draft.preview=null;draft.operationId=uid();}),{multiline:true,rows:22,code:true,disabled:draft.submitted});
  const update=(fn,redraw=false)=>{
    if(draft.submitted)return;
    edit(()=>{
      const q=JSON.parse(draft.text);
      fn(q);
      if(q.question_type!=='OPEN_ENDED')q.correct_answers=q.correct_answer_indices.map(index=>q.answers[index]);
      draft.text=pretty(q);draft.preview=null;draft.operationId=uid();
      const area=raw.querySelector('textarea');
      if(area)area.value=draft.text;
    });
    if(redraw)render();
  };

  const fields=h('fieldset',{disabled:draft.submitted},
    h('legend',{},'1. Conteúdo e gabarito'),
    badge(kinds[parsed.question_type] || parsed.question_type),
    field('Enunciado',parsed.question,v=>update(q=>q.question=v),{multiline:true,rows:4,maxlength:20000})
  );
  if(parsed.question_type!=='OPEN_ENDED'){
    parsed.answers.forEach((answer,index)=>fields.append(field(`Alternativa ${String.fromCharCode(65+index)}`,answer,v=>update(q=>q.answers[index]=v))));
    fields.append(field('Gabarito',parsed.correct_answer_indices.length?String(parsed.correct_answer_indices[0]):'',v=>update(q=>q.correct_answer_indices=v===''?[]:[Number(v)]),{options:[['','Selecione…'],...parsed.answers.map((_,i)=>[String(i),`Alternativa ${String.fromCharCode(65+i)}`])]}));
  } else {
    const rubricValue = draft.rubricText ?? pretty(parsed.grading);
    fields.append(field('Correção e rubrica (JSON)',rubricValue,v=>{
      if(draft.submitted)return;
      try {
        const validGrading = parseOpenEndedGradingJson(v);
        draft.rubricText = v;
        draft.rubricError = null;
        update(q=>{ q.grading = validGrading; });
      } catch (error) {
        draft.rubricText = v;
        draft.rubricError = error.message;
        draft.preview = null;
        draft.operationId = uid();
        persist();
        notice(error.message,'error');
      }
    },{multiline:true,rows:12,code:true,error:draft.rubricError || null}));
  }

  const originYear = parsed.exam_year ?? parsed.origins?.[0]?.exam_year ?? '';
  fields.append(
    h('hr',{class:'divider'}),
    h('h3',{},'2. Comentário da resolução e metadados pedagógicos'),
    field('Explicação / comentário da resolução',parsed.explanation || '',v=>update(q=>{
      const clean=v.trim();
      if(clean)q.explanation=v;else delete q.explanation;
    }),{multiline:true,rows:4,maxlength:20000,help:'Preservado nas edições v2. Se o enunciado mudar e este campo ficar vazio, comentários obsoletos serão limpos.'}),
    h('div',{class:'form-grid'},
      field('Nível / dificuldade',parsed.level || '',v=>update(q=>{
        const clean=v.trim();
        if(clean)q.level=clean;else delete q.level;
      }),{maxlength:300}),
      field('Ano da prova',originYear,v=>update(q=>{
        const clean=String(v ?? '').trim();
        const yearNum=clean===''?null:Number.parseInt(clean,10);
        if(yearNum && Number.isInteger(yearNum)){
          q.exam_year=yearNum;
          if(Array.isArray(q.origins) && q.origins[0])q.origins[0].exam_year=yearNum;
        } else {
          delete q.exam_year;
          if(Array.isArray(q.origins) && q.origins[0])q.origins[0].exam_year=null;
        }
      }),{type:'number',min:1900,max:2200})
    )
  );

  if (parsed.question_type !== 'OPEN_ENDED') {
    const edCard = parsed.editorial_card || {};
    const edState = draft.editorialDraft || {
      legalProvisionsText: Array.isArray(edCard.legal_provisions) ? edCard.legal_provisions.join('\n') : '',
      testedRule: edCard.tested_rule || '',
      exceptionClause: edCard.exception_clause || '',
      decisiveStemExcerpt: edCard.decisive_stem_excerpt || '',
      officialSourceExcerpt: edCard.official_source_excerpt || ''
    };
    const syncEditorial = () => {
      draft.editorialDraft = edState;
      try {
        const built = buildEditorialCard({
          ...edState,
          plausibleConceptualErrors: edCard.plausible_conceptual_errors || []
        });
        update(q => {
          if (built) q.editorial_card = built;
          else delete q.editorial_card;
        });
      } catch {
        persist();
      }
    };
    fields.append(
      h('details',{'data-panel-key':`editorial-card:${selectedQuestion}`},
        h('summary',{},'Ficha Editorial Rastreável (Dispositivos, Regra, Exceção e Trecho Decisivo)'),
        h('p',{class:'muted'},'Opcional e fora do hash semântico: enriquece o diagnóstico de 3 sinais pós-resposta sem invalidar a validade vigente.'),
        field('Dispositivos legais / normativos envolvidos (1 por linha)',edState.legalProvisionsText,v=>{edState.legalProvisionsText=v;syncEditorial();},{multiline:true,rows:2,maxlength:2000}),
        field('Regra cobrada',edState.testedRule,v=>{edState.testedRule=v;syncEditorial();},{multiline:true,rows:2,maxlength:2000}),
        field('Exceção cobrada ou aplicável (opcional)',edState.exceptionClause,v=>{edState.exceptionClause=v;syncEditorial();},{multiline:true,rows:2,maxlength:2000}),
        field('Trecho decisivo do enunciado',edState.decisiveStemExcerpt,v=>{edState.decisiveStemExcerpt=v;syncEditorial();},{multiline:true,rows:2,maxlength:1500}),
        field('Fragmento da fonte oficial (opcional)',edState.officialSourceExcerpt,v=>{edState.officialSourceExcerpt=v;syncEditorial();},{multiline:true,rows:2,maxlength:3000})
      )
    );
  }

  const primary=parsed.classification.links.find(link=>link.role==='PRIMARY');
  const choice=draft.classificationChoice || {subjectId:primary?.subject_id || '',topicId:primary?.topic_id || '',confirmed:true};
  const choose=fn=>{if(draft.submitted)return;edit(()=>{fn();choice.confirmed=false;draft.classificationChoice=choice;draft.preview=null;});render();};
  const questionTopics = orderedTopics(data.base.catalog, choice.subjectId)
    .filter(t=>t.status==='ACTIVE' || t.topic_id===choice.topicId);
  fields.append(
    h('hr',{class:'divider'}),
    h('h3',{},'3. Classificação principal'),
    h('div',{class:'form-grid'},
      field('Disciplina da questão',choice.subjectId,value=>choose(()=>{choice.subjectId=value;choice.topicId='';}),{options:[['','Sem classificação'],...data.base.catalog.subjects.filter(s=>s.status==='ACTIVE' || s.subject_id===choice.subjectId).map(s=>[s.subject_id,s.status==='ACTIVE'?s.label:`Desativada · ${s.label}`])]}),
      field('Assunto / subassunto da questão',choice.topicId,value=>choose(()=>choice.topicId=value),{options:[['','Selecione…'],...questionTopics.map(t=>[t.topic_id,t.status==='ACTIVE'?topicPath(data.base.catalog,t):`Desativado · ${topicPath(data.base.catalog,t)}`])]})
    ),
    check('Aplicar e confirmar a classificação principal selecionada',false,value=>{
      if(!value)return;
      if(choice.subjectId && !choice.topicId){notice('Selecione um assunto da disciplina.','error');return;}
      update(q=>{
        const links=q.classification.links.filter(link=>link.role!=='PRIMARY' && link.topic_id!==choice.topicId);
        if(choice.subjectId){
          links.unshift({
            link_id:uid(),
            subject_id:choice.subjectId,
            topic_id:choice.topicId,
            role:'PRIMARY',
            coverage:'DIRECT',
            provenance:manualProvenance(me.user_id,'Classificação principal revisada explicitamente na administração web.')
          });
          const subjLabel=data.base.catalog.subjects.find(s=>s.subject_id===choice.subjectId)?.label;
          if(subjLabel)q.disciplina=subjLabel;
        }
        q.classification={
          revision:q.classification.revision,
          catalog_version:data.base.catalog.catalog_version,
          state:choice.subjectId?'CONFIRMED':links.length?'PROPOSED':'UNCLASSIFIED',
          links
        };
        q.canonical_subject_id=choice.subjectId || null;
        q.canonical_topic_id=choice.topicId || null;
      });
      choice.confirmed=true;draft.classificationChoice=choice;persist();
      notice('Classificação preparada para conferência na prévia.');
    })
  );

  const val=parsed.validity;
  fields.append(
    h('hr',{class:'divider'}),
    h('h3',{},'4. Validade do conteúdo'),
    h('div',{class:'form-grid'},
      field('Estado de validade',val.status || 'UNKNOWN',v=>update(q=>{
        q.validity.status=v;
        q.validity.evaluated_content_revision=null;
        q.validity.evaluated_semantic_revision=null;
        q.validity.evaluation_mode=null;
        if(v==='CURRENT')q.validity.reason_codes=[];
        else if(v==='UNDER_REVIEW' && (!Array.isArray(q.validity.reason_codes) || !q.validity.reason_codes.length))q.validity.reason_codes=['REVIEW_REQUESTED'];
        else if(v==='OBSOLETE' && (!Array.isArray(q.validity.reason_codes) || !q.validity.reason_codes.length))q.validity.reason_codes=['FACT_CHANGED'];
      },true),{options:Object.entries(statuses)}),
      field('Base da avaliação',val.basis || 'TIMELESS',v=>update(q=>{q.validity.basis=v;}),{options:Object.entries(validityBases)}),
      val.status==='CURRENT' || val.status==='OBSOLETE'
        ? field('Data de referência (YYYY-MM-DD)',val.reference_date || new Date().toLocaleDateString('en-CA',{timeZone:'America/Sao_Paulo'}),v=>update(q=>{q.validity.reference_date=v.trim() || null;}),{type:'date'})
        : null,
      val.status==='CURRENT'
        ? field('Próxima revisão prevista (opcional)',val.review_due_on || '',v=>update(q=>{q.validity.review_due_on=v.trim() || null;}),{type:'date'})
        : null
    ),
    val.status==='OBSOLETE' || val.status==='UNDER_REVIEW'
      ? h('div',{class:'check-list'},...validityReasons.map(([code,label])=>check(label,(val.reason_codes || []).includes(code),checked=>update(q=>{
          const set=new Set(q.validity.reason_codes || []);
          if(checked)set.add(code);else set.delete(code);
          q.validity.reason_codes=[...set];
        },true))))
      : null,
    check('Reconfirmar explicitamente o estado de validade selecionado caso o enunciado/gabarito tenha sido editado',!!draft.reconfirmValidity,checked=>edit(()=>{
      draft.reconfirmValidity=checked;
      const q=JSON.parse(draft.text);
      if(checked){
        q.validity.evaluated_content_revision=null;
        q.validity.evaluated_semantic_revision=null;
        q.validity.evaluation_mode=null;
      }
      draft.text=pretty(q);
      draft.preview=null;
    }))
  );

  const validate=button('Validar edição',()=>run('Validando contrato…',async()=>{
    if(draft.classificationChoice && !draft.classificationChoice.confirmed)throw new Error('Confira e aplique a classificação selecionada antes de validar.');
    let docPayload;
    try { docPayload = JSON.parse(draft.text); }
    catch { throw new Error('JSON inválido no contrato da questão: corrija o documento JSON antes de validar.'); }
    if (docPayload.question_type === 'OPEN_ENDED') {
      const rawRubric = draft.rubricText ?? pretty(docPayload.grading);
      docPayload.grading = parseOpenEndedGradingJson(rawRubric);
      draft.rubricError = null;
    }
    if(draft.reconfirmValidity){
      docPayload.validity.evaluated_content_revision=null;
      docPayload.validity.evaluated_semantic_revision=null;
      docPayload.validity.evaluation_mode=null;
    }
    try {
      draft.preview=await api(`admin/questions/${encodeURIComponent(selectedQuestion)}/prepare-edit`,{method:'POST',body:{operation_id:draft.operationId,expected_revision:original.revision || 0,document:docPayload}});
    } catch (error) {
      if (error.status === 409) {
        draft.server = await api(`admin/questions/v2/${encodeURIComponent(selectedQuestion)}`).catch(() => null);
        draft.conflict = true;
        persist();
        throw new Error('Conflito de revisão no servidor: a questão foi alterada em outra sessão. Use "Recarregar versão do servidor" acima.');
      }
      throw error;
    }
    persist();
    notice('Prévia validada. Confira o contrato final antes de confirmar.');
  }),'primary');
  validate.disabled=draft.submitted;

  root.append(card(
    h('p',{},'A prévia calcula o hash canônico JCS SHA-256 e as revisões de classificação e validade.'),
    fields,
    h('details',{'data-panel-key':`question-json:${selectedQuestion}`},h('summary',{},'Metadados e contrato completo (JSON)'),raw,button('Atualizar formulário a partir do JSON',()=>{try{JSON.parse(draft.text);delete draft.rubricText;draft.rubricError=null;render();}catch(error){notice(`JSON inválido no contrato: ${error.message}`,'error');}})),
    validate
  ));

  if(draft.preview){
    let reviewed=false;
    const send=button(draft.submitted?'Conferir / reenviar edição':'Confirmar edição',()=>run('Gravando edição…',async()=>{
      if (draft.preview.command?.document?.question_type === 'OPEN_ENDED' && draft.rubricError) {
        throw new Error(draft.rubricError);
      }
      draft.submitted=true;persist();
      try {
        const result=await api('admin/questions/v2',{method:'POST',body:draft.preview.command});
        if(!['applied','already_applied'].includes(result.status)||result.revision!==(original.revision || 0)+1)throw new Error('Recibo de revisão divergente.');
        draft.result=result;persist();
        data.summary=await api('admin/summary');
        notice('Edição confirmada no servidor.');
      } catch(error) {
        if(error.status>=400 && error.status<500){
          draft.submitted=false;
          if(error.status===409){
            draft.server=await api(`admin/questions/v2/${encodeURIComponent(selectedQuestion)}`).catch(()=>null);
            draft.conflict=true;
          }
          persist();
        }
        throw error;
      }
    }),'primary');
    send.disabled=true;
    root.append(card(
      draft.preview.semantic_changed === false && draft.preview.content_changed
        ? badge(`Correção alterada (semântica preservada) · Validade: ${statuses[draft.preview.command.document.validity.status] || draft.preview.command.document.validity.status}`,'good')
        : draft.preview.content_changed
          ? badge(`Conteúdo alterado · Validade resultante: ${statuses[draft.preview.command.document.validity.status] || draft.preview.command.document.validity.status}`,'warning')
          : badge(`Conteúdo preservado · Validade: ${statuses[draft.preview.command.document.validity.status] || draft.preview.command.document.validity.status}`,'good'),
      h('pre',{},pretty(draft.preview.command.document)),
      check('Revisei o contrato final, a validade, a classificação e os metadados.',reviewed,value=>{reviewed=value;send.disabled=!value || !!draft.result;}),
      send
    ));
  }
  if(draft.result)root.append(card(
    badge('Edição confirmada','good'),
    h('p',{},`Revisão ${draft.result.revision}`),
    button('Carregar revisão atual',()=>run('Carregando revisão…',async()=>{
      pushArchive(ws,'question-edit',draft);
      const document=await api(`admin/questions/v2/${encodeURIComponent(selectedQuestion)}`);
      ws.questionEdits[selectedQuestion]={original:copy(document),text:pretty(document),operationId:uid(),preview:null,submitted:false,result:null,reconfirmValidity:false};
      persist();
    }))
  ));
  return root;
}

function importView() {
  ws.import ||= freshImport(uid(),uid());
  const draft=ws.import,locked=draft.submitted;
  const update=(fn,redraw=false)=>{
    if(locked)return;
    edit(()=>{fn();draft.preview=null;draft.result=null;draft.operationId=uid();draft.aiOperationId=uid();});
    const originBox=$('#origin-confirmation input');
    if(originBox)originBox.checked=!!draft.form.confirmOrigin;
    if(redraw)render();
  };
  const root=h('div',{},heading('Inserir questões','Individual ou em lote, com revisão e assistência opcional de IA.',button('Exportar rascunho',()=>download('cogniva-rascunhos.json',exportWorkspace(me.user_id,ws)))));
  root.append(h('div',{class:'tabs'},...[['single','Individual'],['bulk','Lote / IA']].map(([v,t])=>{const b=button(t,()=>update(()=>draft.mode=v,true),v===draft.mode?'active':'');b.disabled=locked || (v==='single' && !!draft.assistanceId);return b;})));
  const inputSection=card();
  const inputs=h('fieldset',{disabled:locked});
  if(draft.mode==='single'){
    const f=draft.form;
    inputs.append(
      h('legend',{},'1. Conteúdo e gabarito'),
      h('p',{class:'intro-note'},'Transcreva a questão e registre a prova de origem abaixo. Para alterar uma questão já salva no Cogniva, abra o Banco de questões.'),
      f.kind==='OPEN_ENDED'?openEndedBanner():null,
      h('div',{class:'form-grid'},
        field('Tipo',f.kind,v=>update(()=>{f.kind=v;f.correctIndex='';},true),{options:Object.entries(kinds)}),
        field('Origem',f.originKind,v=>update(()=>{f.originKind=v;f.confirmOrigin=false;},true),{options:[['LEGACY_IMPORT','Transcrição / importação'],['OFFICIAL_EXAM','Questão de prova oficial'],['AUTHOR_CREATED','Autoria própria']]}),
        field('Enunciado',f.question,v=>update(()=>f.question=v),{multiline:true,rows:4,wide:true,maxlength:20000})
      )
    );
    if(f.kind!=='OPEN_ENDED'){
      const options=f.kind==='TRUE_FALSE'?['Certo','Errado']:f.answers;
      options.forEach((answer,index)=>{if(f.kind==='MULTIPLE_CHOICE')inputs.append(field(`Alternativa ${String.fromCharCode(65+index)}`,answer,v=>update(()=>f.answers[index]=v),{maxlength:20000}));});
      inputs.append(field('Gabarito',f.correctIndex,v=>update(()=>f.correctIndex=v),{options:[['','Selecione o gabarito…'],...options.map((a,i)=>[String(i),f.kind==='TRUE_FALSE'?a:`Alternativa ${String.fromCharCode(65+i)}`])]}));
    } else {
      inputs.append(
        field('Resposta de referência',f.reference,v=>update(()=>f.reference=v),{multiline:true,rows:4}),
        field('Critérios da rubrica (array JSON)',f.criteria,v=>update(()=>f.criteria=v),{multiline:true,rows:5,code:true,help:'Ex.: [{"criterion_id":"c1","description":"Explicar corretamente o conceito","max_points":100,"essential":true}]. Os pontos devem somar exatamente 100; corte 60, com revisão humana.'})
      );
    }
    inputs.append(
      h('hr',{class:'divider'}),
      h('h2',{},'2. Prova de origem e referência'),
      h('p',{class:'intro-note'},'A prova de origem identifica de onde veio a questão. Na origem oficial, informe prova, instituição, banca, ano e fonte.'),
      h('div',{class:'form-grid'},
        ...[['Identificação da prova / cargo / caderno','sourceExamCode'],['Instituição / órgão','institution'],['Banca organizadora','organizer'],['Número da questão','questionNumber']].map(([label,key])=>field(label,f[key],v=>update(()=>{f[key]=v;f.confirmOrigin=false;}),{maxlength:300})),
        field('Ano da prova',f.examYear,v=>update(()=>{f.examYear=v;f.confirmOrigin=false;}),{type:'number',min:1900,max:2200}),
        field('Nível / dificuldade (opcional)',f.level,v=>update(()=>f.level=v),{maxlength:300}),
        field('Endereço da fonte (URL)',f.sourceUri,v=>update(()=>{f.sourceUri=v;f.confirmOrigin=false;}),{type:'url',wide:true,help:'Link do caderno, gabarito ou página de referência.'}),
        field('Título do documento / fonte',f.sourceTitle,v=>update(()=>{f.sourceTitle=v;f.confirmOrigin=false;}),{maxlength:300}),
        field('Página / localização no documento',f.sourceLocator,v=>update(()=>{f.sourceLocator=v;f.confirmOrigin=false;}),{maxlength:300}),
        field('Data em que consultei a fonte',f.sourceDate,v=>update(()=>{f.sourceDate=v;f.confirmOrigin=false;}),{type:'date'})
      ),
      h('div',{id:'origin-confirmation'},check('Consultei a fonte e conferi a origem informada.',!!f.confirmOrigin,v=>update(()=>f.confirmOrigin=v))),
      h('p',{class:'status-line'},'Sem esta conferência, a origem transcrita fica pendente. Origem conferida não confirma sozinha a validade atual do conteúdo.'),
      h('hr',{class:'divider'}),
      h('h2',{},'3. Classificação por conteúdo'),
      h('p',{class:'intro-note'},'Disciplina → Assunto → Subassunto. Escolha o conteúdo mais específico cobrado; a classificação é compartilhada entre concursos.')
    );
    const activeImportTopics = orderedTopics(data.base.catalog, f.subjectId).filter(t=>t.status==='ACTIVE');
    inputs.append(
      h('div',{class:'form-grid'},
        field('Disciplina (opcional)',f.subjectId,v=>update(()=>{f.subjectId=v;f.topicId='';f.confirmClassification=false;},true),{options:[['','Sem classificação'],...data.base.catalog.subjects.filter(s=>s.status==='ACTIVE').map(s=>[s.subject_id,s.label])]}),
        field('Assunto / subassunto',f.topicId,v=>update(()=>{f.topicId=v;f.confirmClassification=false;},true),{options:[['','Selecione…'],...activeImportTopics.map(t=>[t.topic_id,topicPath(data.base.catalog,t)])]})
      ),
      check('Conferi e confirmo este vínculo principal direto.',f.confirmClassification,v=>update(()=>f.confirmClassification=v),!f.topicId)
    );
    inputs.append(h('hr',{class:'divider'}),h('h2',{},'4. Comentário da resolução'),field('Explicação / justificativa do gabarito (opcional)',f.explanation,v=>update(()=>f.explanation=v),{multiline:true,rows:4,maxlength:20000,help:'Comentário que acompanha a resolução da questão.'}));
  } else {
    inputs.append(field('JSON / JSONL ou material para a IA',draft.source,v=>update(()=>draft.source=v),{multiline:true,rows:14,help:'Até 50 questões / 512 KiB. Gabaritos usam índices a partir de zero.'}));
    const upload=h('input',{type:'file',accept:'.json,.jsonl,.txt,application/json,text/plain','aria-label':'Importar arquivo de questões',onchange:async e=>{const file=e.target.files[0];if(!file)return;try{if(file.size>512*1024)throw new Error('Arquivo excede 512 KiB.');const text=(await file.text()).replace(/^\uFEFF/,'');update(()=>{draft.source=text;draft.assistanceId=null;},true);}catch(error){notice(error.message,'error');}}});
    inputs.append(upload,h('p',{class:'status-line'},'Aceita objetos simplificados ou documentos v2 completos com identidade nova.'));
    if(!draft.assistanceId){
      inputs.append(
        h('hr',{class:'divider'}),
        h('h3',{},'Assistência opcional · xAI'),
        h('p',{},'O botão envia o material acima ao modelo xAI configurado no servidor. A resposta será um rascunho editável; nenhuma questão será inserida automaticamente.'),
        field('Como usar a IA',draft.aiMode,v=>update(()=>draft.aiMode=v),{options:[['STRUCTURE','Estruturar questões de um texto'],['GENERATE','Gerar questões autorais a partir do pedido']]}),
        (()=>{
          const b=button('Preparar com IA',()=>run('Preparando rascunhos com IA…',async()=>{
            draft.aiOperationId ||= uid();
            persist();
            try {
              const result=await api('admin/questions/import/assist',{method:'POST',body:{operation_id:draft.aiOperationId,mode:draft.aiMode,text:draft.source}});
              draft.source=pretty(result.items);
              draft.assistanceId=result.assistance_id;
              draft.aiOperationId=uid();
              draft.preview=null;
              persist();
              notice('A IA preparou rascunhos. Confira e edite o conteúdo antes de validar.');
            } catch (error) {
              draft.aiOperationId=uid();
              persist();
              throw error;
            }
          }));
          b.disabled=!data.capabilities?.ai_available;
          return b;
        })(),
        !data.capabilities?.ai_available?h('small',{},'IA não configurada no servidor (defina XAI_API_KEY e XAI_MODEL). A inserção manual continua disponível.'):null
      );
    } else {
      inputs.append(
        badge('Rascunho assistido por IA','warning'),
        h('p',{},'Você pode editar o JSON. A proveniência de IA será preservada; classificação e validade não serão confirmadas pelo modelo.'),
        button('Nova consulta IA (desvincular assistência atual)',()=>{update(()=>{draft.assistanceId=null;draft.aiOperationId=uid();},true);})
      );
    }
  }
  inputs.append(
    h('hr',{class:'divider'}),
    button('Validar e revisar questões',()=>run('Validando o lote…',async()=>{
      const items=draft.mode==='single'?[objectiveDraft(draft.form)]:parseQuestions(draft.source);
      persist();
      draft.preview=await api('admin/questions/import/preview',{method:'POST',body:{operation_id:draft.operationId,items,assistance_id:draft.assistanceId}});
      persist();
      notice(draft.preview.ready?'Contrato validado. Confira todas as questões abaixo.':'Corrija as questões indicadas antes de enviar.',draft.preview.ready?'':'warning');
    }),'primary')
  );
  inputSection.append(inputs,h('p',{class:'status-line'},'Novas questões simplificadas iniciam com validade desconhecida; confirme classificação e validade por revisão explícita.'));
  root.append(inputSection);

  if(draft.preview){
    root.append(h('h2',{},'Revisão antes de inserir'));
    draft.preview.items.forEach((item,index)=>{
      if(!item.valid){root.append(card(badge(`Questão ${index+1}`,'danger'),h('p',{},item.message)));return;}
      const q=item.command.document;
      root.append(card(h('div',{class:'question-review'},
        badge(`Questão ${index+1}`),
        badge(kinds[q.question_type]),
        q.question_type==='OPEN_ENDED'?badge('Acervo preparatório','warning'):null,
        item.duplicate_warning?badge('Possível duplicidade','warning'):null,
        h('h3',{},q.question),
        item.duplicate_warning?h('p',{class:'review-meta'},item.duplicate_warning.message):null,
        h('ol',{type:'A'},...q.answers.map(a=>h('li',{},a))),
        h('strong',{},q.correct_answers.length?`Gabarito: ${q.correct_answers.join(', ')}`:'Discursiva · correção por rubrica'),
        q.question_type==='OPEN_ENDED'?h('pre',{},pretty(q.grading)):null,
        h('p',{class:'review-meta'},`Classificação: ${q.classification.state} · Validade: ${statuses[q.validity.status]} · Contexto: ${q.validity.scope}${q.validity.context_date?' / '+q.validity.context_date:''}`),
        h('p',{class:'review-meta'},`Vínculos: ${q.classification.links.map(l=>`${l.subject_id}/${l.topic_id} (${l.role}, ${l.coverage})`).join('; ')||'Sem classificação confirmada'}`),
        h('p',{class:'review-meta'},`Origem: ${q.origins.map(o=>`${o.kind} · ${o.verification} · ${[o.source_exam_code,o.institution,o.organizer,o.exam_year,o.question_number?'Questão '+o.question_number:null].filter(Boolean).join(' · ') || 'Sem identificação da prova'}${o.source_reference?' · Fonte: '+o.source_reference.title+' ('+o.source_reference.uri+')':''}`).join('; ')}`),
        h('details',{'data-panel-key':`import-item:${index}`},h('summary',{},'Ver contrato completo'),h('pre',{},pretty(q)))
      )));
    });
    let reviewed=false;
    const commit=button(locked?'Conferir / reenviar os mesmos comandos':`Confirmar inserção de ${draft.preview.items.length} questão(ões)`,()=>run('Inserindo questões…',async()=>{
      draft.submitted=true;persist();
      try {
        draft.result=verifyReceipt(draft.preview,await api('admin/questions/import/commit',{method:'POST',body:buildImportCommitPayload(draft.preview)}));
        persist();
        data.summary=await api('admin/summary');
        notice(draft.result.complete?'Todas as questões foram confirmadas no servidor.':'Alguns itens ainda não foram confirmados. Confira os resultados ou inicie novo cadastro preservando o recibo.',draft.result.complete?'':'warning');
      } catch(error) {
        if(error.status>=400 && error.status<500){
          draft.submitted=false;persist();
        }
        throw error;
      }
    }),'primary');
    commit.disabled=true;
    root.append(card(
      check('Revisei enunciados, alternativas, gabaritos, classificação, origem e validade.',reviewed,value=>{reviewed=value;commit.disabled=!value || !draft.preview.ready || draft.result?.complete===true;}),
      actions(
        commit,
        locked && !draft.result?button('Desbloquear rascunho para corrigir',()=>{edit(()=>{draft.submitted=false;});render();}):null
      ),
      locked && !draft.result?.complete?h('p',{class:'status-line'},'A retentativa usa os mesmos operation_id e não duplica itens já gravados.'):null
    ));
  }
  if(draft.result)root.append(card(
    h('h2',{},`${draft.result.items.filter(r=>r.saved).length} de ${draft.result.items.length} questões confirmadas`),
    ...draft.result.items.map((item,i)=>h('div',{class:'result-item'},badge(`${i+1}`,item.saved?'good':'danger'),item.saved?`Confirmada · ${item.id.$oid}`:item.message))
  ));
  const next=button('Iniciar novo cadastro',async()=>{
    if(await confirm('Iniciar novo cadastro?','O rascunho e o recibo atuais serão guardados em Rascunhos e pendências.')){
      edit(()=>{pushArchive(ws,'question-import',draft);ws.import=freshImport(uid(),uid());});
      render();
    }
  });
  next.disabled=locked && !draft.result;
  root.append(next);
  return root;
}

function fetchProposals() {
  run('Consultando propostas de classificação…',async()=>{
    const query=new URLSearchParams({limit:'50'});
    if(proposalStatusFilter)query.set('status',proposalStatusFilter);
    proposalsData=await api(`admin/classification/proposals?${query}`);
    data.summary=await api('admin/summary');
  });
}

function classificationView() {
  ws.classificationAiOpId ||= uid();
  const root=h('div',{},heading('Classificação assistida por IA','Gere sugestões para questões sem tópico canônico e confirme cada vínculo com revisão humana.',button('Atualizar fila',fetchProposals,'primary')));
  const filterBar=h('div',{class:'toolbar'},
    h('select',{'aria-label':'Filtrar propostas por status','data-focus-key':'classification:status',onchange:e=>{proposalStatusFilter=e.target.value;fetchProposals();}},
      ...[['PENDING','Pendentes de decisão'],['ACCEPTED','Aceitas'],['EDITED_ACCEPTED','Editadas e aceitas'],['DEFERRED','Adiadas'],['REJECTED','Rejeitadas'],['SUPERSEDED','Substituídas'],['','Todas']].map(([v,t])=>h('option',{value:v,selected:proposalStatusFilter===v},t))
    ),
    button('Carregar propostas',fetchProposals)
  );
  root.append(filterBar);

  if(!proposalsData){
    root.append(card(h('p',{},'Carregue a fila de classificação assistida para consultar propostas pendentes e questões sem classificação.'),button('Carregar fila de classificação',fetchProposals,'primary')));
    return root;
  }

  const candidates=proposalsData.unclassified_candidates || [];
  const genButton=button(
    `Gerar propostas com IA (${Math.min(candidates.length,10)} de ${proposalsData.unclassified_count || 0} sem classificação)`,
    ()=>run('Solicitando propostas de classificação à xAI…',async()=>{
      const batchIds=candidates.slice(0,10).map(c=>c.id);
      if(!batchIds.length)throw new Error('Não há questões sem classificação aguardando proposta.');
      const opId = ws.classificationAiOpId || uid();
      try {
        await api('admin/classification/proposals',{
          method:'POST',
          body:{
            operation_id:opId,
            question_ids:batchIds,
            limit:batchIds.length,
            max_paid_calls:batchIds.length
          }
        });
        ws.classificationAiOpId = uid();
        persist();
      } catch (error) {
        ws.classificationAiOpId = uid();
        persist();
        throw error;
      }
      proposalStatusFilter='PENDING';
      const refreshed=await api('admin/classification/proposals?status=PENDING&limit=50');
      proposalsData=refreshed;
      data.summary=await api('admin/summary');
      notice(`Lote processado (${batchIds.length} questão(ões)). Revise as propostas abaixo antes de aplicar.`);
    }),
    'primary'
  );
  genButton.disabled=!candidates.length || !data.capabilities?.ai_available;

  root.append(card(
    h('h2',{},`Questões sem classificação confirmada: ${proposalsData.unclassified_count ?? 0}`),
    h('p',{},data.capabilities?.ai_available
      ?'Gere propostas em lotes controlados. Nenhuma sugestão altera o estudo dos alunos até sua decisão explícita.'
      :'Configure XAI_API_KEY e XAI_MODEL no servidor para gerar novas propostas automáticas. A revisão das propostas já registradas continua disponível.'),
    genButton
  ));

  if(!proposalsData.items.length){
    root.append(h('p',{class:'empty'},'Nenhuma proposta encontrada para o filtro selecionado.'));
    return root;
  }

  for(const item of proposalsData.items){
    const suggestionId=typeof item._id==='string'?item._id:(item._id?.$oid || '');
    const primaryLink=(item.links || []).find(l=>l.role==='PRIMARY') || item.links?.[0] || {subject_id:'',topic_id:'',rationale:'',confidence:0};
    let editSubject=primaryLink.subject_id || '';
    let editTopic=primaryLink.topic_id || '';
    let reasonText='Vínculo conferido manualmente no painel administrativo.';

    const subjectLabel=data.base.catalog.subjects.find(s=>s.subject_id===primaryLink.subject_id)?.label || primaryLink.subject_id;
    const topicObj=data.base.catalog.topics.find(t=>t.topic_id===primaryLink.topic_id);
    const topicLabel=topicObj?topicPath(data.base.catalog,topicObj):primaryLink.topic_id;

    const decide=async(action)=>{
      if(reasonText.trim().length<10){notice('Informe um motivo de auditoria com pelo menos 10 caracteres.','error');return;}
      const body={
        operation_id:uid(),
        suggestion_id:suggestionId,
        action,
        reason:reasonText.trim(),
        expected_content_revision:item.current_content_revision || item.question_content_revision,
        expected_classification_revision:item.current_classification_revision ?? item.question_classification_revision ?? 0
      };
      if(action==='edit_accept'){
        if(!editSubject || !editTopic){notice('Selecione disciplina e assunto para editar e aceitar.','error');return;}
        body.edited_links=[{
          subject_id:editSubject,
          topic_id:editTopic,
          role:'PRIMARY',
          coverage:'DIRECT',
          rationale:reasonText.trim(),
          confidence:1
        }];
      }
      await run('Registrando decisão de classificação…',async()=>{
        await api('admin/classification/decisions',{method:'POST',body});
        proposalsData=await api(`admin/classification/proposals?status=${encodeURIComponent(proposalStatusFilter)}&limit=50`);
        data.summary=await api('admin/summary');
        notice('Decisão registrada com sucesso.');
      });
    };

    const topicSelect=h('select',{'aria-label':'Assunto ajustado','data-focus-key':`proposal:${suggestionId}:topic`,onchange:e=>{editTopic=e.target.value;}},
      h('option',{value:''},'Selecione o assunto…'),
      ...orderedTopics(data.base.catalog,editSubject).filter(t=>t.status==='ACTIVE').map(t=>h('option',{value:t.topic_id,selected:t.topic_id===editTopic},topicPath(data.base.catalog,t)))
    );
    const redrawTopics=()=>{
      topicSelect.replaceChildren(
        h('option',{value:''},'Selecione o assunto…'),
        ...orderedTopics(data.base.catalog,editSubject).filter(t=>t.status==='ACTIVE').map(t=>h('option',{value:t.topic_id,selected:t.topic_id===editTopic},topicPath(data.base.catalog,t)))
      );
    };

    const box=card(
      actions(
        badge(item.status,item.status==='PENDING'?'warning':item.status.includes('ACCEPT')?'good':''),
        item.disciplina?badge(item.disciplina):null,
        badge(`Catálogo v${item.catalog_version}`),
        primaryLink.confidence!=null?badge(`Confiança IA: ${Math.round(Number(primaryLink.confidence)*100)}%`):null
      ),
      h('p',{},item.question_snippet || `Questão ${item.question_id}`),
      h('p',{class:'review-meta'},`Sugestão principal: ${subjectLabel} → ${topicLabel}`),
      primaryLink.rationale?h('p',{class:'review-meta'},`Justificativa do modelo: ${primaryLink.rationale}`):null,
      item.status==='PENDING'?h('div',{},
        field('Motivo da decisão (auditoria, mín. 10 caracteres)',reasonText,v=>{reasonText=v;},{focusKey:`proposal:${suggestionId}:reason`}),
        h('details',{'data-panel-key':`proposal-edit:${suggestionId}`},
          h('summary',{},'Ajustar disciplina / assunto antes de aceitar'),
          h('div',{class:'form-grid'},
            field('Disciplina',editSubject,v=>{editSubject=v;editTopic='';redrawTopics();},{options:[['','Selecione…'],...data.base.catalog.subjects.filter(s=>s.status==='ACTIVE').map(s=>[s.subject_id,s.label])],focusKey:`proposal:${suggestionId}:subject`}),
            h('div',{class:'field'},h('label',{},'Assunto / subassunto'),topicSelect)
          ),
          button('Salvar vínculo ajustado (Editar e aceitar)',()=>decide('edit_accept'),'primary')
        ),
        actions(
          button('Aceitar proposta',()=>decide('accept'),'primary'),
          button('Adiar',()=>decide('defer')),
          button('Rejeitar',()=>decide('reject'),'danger'),
          button('Abrir questão no editor →',()=>openQuestionEditor(item.question_id))
        )
      ):actions(
        item.resolution?.reason?h('small',{},`Resolução: ${item.resolution.reason}`):null,
        button('Abrir questão no editor →',()=>openQuestionEditor(item.question_id))
      )
    );
    root.append(box);
  }
  return root;
}

function fetchReports() {
  run('Consultando reportes…',async()=>{
    const query=new URLSearchParams({status:reportStatusFilter,limit:'50'});
    reportsData=await api(`admin/reports?${query}`);
    data.summary=await api('admin/summary');
  });
}

function fetchAdminMissions() {
  return run('Carregando catálogo de Missões…', async () => {
    missionsAdminData = await api('admin/missions');
  });
}

function missionsView() {
  const openPortalLink = h('a', {href: '/missoes/', class: 'button-link'}, 'Abrir experiência do aluno (/missoes/) ↗');
  const root = h(
    'div',
    {},
    heading(
      'Missões interativas',
      'Narrativas audiovisuais declarativas separadas da avaliação formal de questões humanas.',
      button('Atualizar Missões', fetchAdminMissions, 'primary')
    ),
    card(
      h('h2', {}, 'Experiência do Estudante · Cogniva Missões'),
      h('p', {}, 'O módulo Missões opera de forma desacoplada do acervo oficial: decisões narrativas são estritamente formativas e nunca são inseridas automaticamente em todasQuestoes.'),
      actions(openPortalLink, button('Carregar inventário de Missões e vídeos', fetchAdminMissions))
    )
  );

  if (!missionsAdminData) {
    return root;
  }

  root.append(
    card(
      h('h2', {}, `Missões publicadas (${(missionsAdminData.missions || []).length})`),
      ...(missionsAdminData.missions || []).map(m =>
        h(
          'div',
          {class: 'catalog-row'},
          h(
            'div',
            {},
            h('strong', {}, `${m.career_header} · ${m.season_title} · ${m.full_title}`),
            h(
              'small',
              {},
              `ID: ${m.mission_id} (v${m.version}) · Disciplina: ${m.discipline} (${m.canonical_subject_id}) · ${m.scenes_count} cenas · ${m.interactions_count} decisões · ${m.duration?.display_label || ''}`
            )
          ),
          badge(m.publication_state, 'good')
        )
      )
    ),
    card(
      h('h2', {}, `Ativos audiovisuais verificados (${(missionsAdminData.media_assets || []).length})`),
      ...(missionsAdminData.media_assets || []).map(a =>
        h(
          'div',
          {class: 'catalog-row'},
          h(
            'div',
            {},
            h('strong', {}, `${a.asset_id} — ${a.relative_file_name}`),
            h(
              'small',
              {},
              `${a.resolution} (${a.aspect_ratio}) · ${a.duration_seconds}s · ${a.video_codec}/${a.audio_codec} · SHA-256: ${String(a.sha256).slice(0, 16)}…`
            )
          ),
          badge(a.available_on_server ? 'Disponível no servidor' : 'Modo transcrição', a.available_on_server ? 'good' : 'warning')
        )
      )
    )
  );
  return root;
}

function reportsView() {
  const root=h('div',{},heading('Moderação de reportes','Analise sinalizações dos estudantes por usuário sem bloqueio global do acervo.',button('Atualizar reportes',fetchReports,'primary')));
  root.append(h('div',{class:'toolbar'},
    h('select',{'aria-label':'Filtrar reportes por situação','data-focus-key':'reports:status',onchange:e=>{reportStatusFilter=e.target.value;fetchReports();}},
      ...[['open','Abertos'],['resolved','Resolvidos'],['all','Todos']].map(([v,t])=>h('option',{value:v,selected:reportStatusFilter===v},t))
    ),
    button('Carregar reportes',fetchReports)
  ));

  if(!reportsData){
    root.append(card(h('p',{},'Carregue a lista de reportes para revisar sinalizações abertas ou histórico resolvido.'),button('Carregar reportes',fetchReports,'primary')));
    return root;
  }

  if(!reportsData.items.length){
    root.append(h('p',{class:'empty'},'Nenhum reporte encontrado para este filtro.'));
    return root;
  }

  for(const rep of reportsData.items){
    let note=rep.resolution_note || 'Revisado e tratado na moderação administrativa.';
    const resolveReport=(resolveAll)=>run('Resolvendo reporte…',async()=>{
      await api(`admin/reports/${encodeURIComponent(rep.report_id)}/resolve`,{
        method:'POST',
        body:{resolution_note:note,resolve_all_for_question:resolveAll}
      });
      reportsData=await api(`admin/reports?status=${encodeURIComponent(reportStatusFilter)}&limit=50`);
      data.summary=await api('admin/summary');
      notice('Reporte marcado como resolvido.');
    });

    root.append(card(
      actions(
        badge(rep.status_solved?'Resolvido':'Aberto',rep.status_solved?'good':'warning'),
        rep.disciplina?badge(rep.disciplina):null,
        badge(`Validade: ${statuses[rep.validity_status] || rep.validity_status}`),
        ...(rep.problemas || []).map(p=>badge(p,'danger'))
      ),
      h('p',{},rep.question_snippet || `Questão ${rep.question_id}`),
      h('p',{class:'review-meta'},`Questão: ${rep.question_id} · Autor do reporte: conta ${rep.user_id}${rep.reported_at?' · Em '+rep.reported_at.slice(0,10):''}`),
      !rep.status_solved
        ? h('div',{},
            field('Nota de resolução',note,v=>{note=v;},{maxlength:500,focusKey:`report:${rep.report_id}:note`}),
            actions(
              button('Abrir questão no editor →',()=>openQuestionEditor(rep.question_id)),
              button('Resolver este reporte',()=>resolveReport(false),'primary'),
              button('Resolver todos os abertos desta questão',()=>resolveReport(true))
            )
          )
        : actions(
            rep.resolution_note?h('small',{},`Nota: ${rep.resolution_note}`):null,
            button('Abrir questão no editor →',()=>openQuestionEditor(rep.question_id))
          )
    ));
  }
  return root;
}

function ensureCatalog() {
  if(ws.catalog)return;
  const catalog=copy(data.base.catalog);
  catalog.catalog_version++;
  catalog.updated_at=new Date().toLocaleDateString('en-CA',{timeZone:'America/Sao_Paulo'});
  if(!catalog.sources.some(s=>s.source_id==='WEB_EDITOR')){
    catalog.sources.push({source_id:'WEB_EDITOR',kind:'EDITORIAL_PROPOSAL',reference:'Cogniva Admin · proposta editorial',note:'Alterações propostas pelo responsável autenticado, sujeitas à revisão e ativação versionada.'});
  }
  ws.catalog={draft_id:uid(),revision:0,base_catalog_sha256:data.base.sha256,catalog,status:'DRAFT',validation:null,pending:null};
  persist();
}
function startCatalog(){ensureCatalog();navigate('catalog');}
function catalogLocked(draft){return !!(draft.pending || draft.activationPending || draft.activationResult || draft.catalog.catalog_version<=data.base.catalog.catalog_version);}

function catalogActivationPanel(draft){
  const box=card(h('h2',{},'Ativar no catálogo global'));
  const newRevision=button('Iniciar nova revisão',async()=>{
    if(draft.activationPending || draft.pending){notice('Confira os comandos pendentes antes de iniciar outra revisão.','warning');return;}
    if(await confirm('Iniciar uma revisão da versão ativa?','A edição atual será preservada no arquivo local.')){
      edit(()=>{pushArchive(ws,'catalog',draft);delete ws.catalog;delete ws.entityEditing;ensureCatalog();});
      render();
    }
  });
  if(draft.activationResult || (draft.catalog.catalog_version<=data.base.catalog.catalog_version && !draft.activationPending)){
    box.append(badge(`Catálogo ativo v${data.base.catalog.catalog_version}`,'good'),h('p',{},'As consultas da API e a sincronização do Android já utilizam a versão ativa.'),newRevision);
    return box;
  }
  box.append(h('p',{},'A ativação entra em vigor imediatamente em memória e no banco, sem reiniciar o servidor. IDs e versões históricas são preservados; renomeações não reescrevem questões nem tentativas.'));
  let reviewed=false;
  const activate=button(draft.activationPending?'Conferir / reenviar ativação':'Ativar catálogo global',()=>run('Ativando catálogo…',async()=>{
    if(!draft.activationPending){
      if(draft.status!=='REVIEW_READY' || draft.dirty)throw new Error('Salve primeiro a revisão atual como pronta para revisão.');
      if((draft.activationReason || '').trim().length<10)throw new Error('Descreva o motivo da ativação com pelo menos 10 caracteres.');
      draft.activationPending={operation_id:uid(),draft_id:draft.draft_id,expected_draft_revision:draft.revision,expected_catalog_hash:draft.base_catalog_sha256,reason:draft.activationReason || '',reviewed:true};
      persist();
    }
    const command=draft.activationPending;
    let result;
    try{result=await api('admin/catalog/activate',{method:'POST',body:command});}
    catch(error){
      if([400,409].includes(error.status)){
        const check=await api(`admin/catalog/activations/${command.operation_id}`).catch(()=>null);
        if(check?.status==='absent'){
          pushArchive(ws,'catalog-activation-rejected',command);
          draft.activationPending=null;persist();
        }
      }
      throw error;
    }
    if(result.operation_id!==command.operation_id || result.activated_version!==draft.catalog.catalog_version || !['applied','already_applied'].includes(result.status))throw new Error('Recibo de ativação divergente. O comando foi preservado.');
    draft.activationResult=result;draft.activationPending=null;persist();await loadData();notice(`Catálogo v${result.activated_version} ativado e confirmado no servidor.`);
  }),'primary');
  activate.disabled=true;
  box.append(
    field('Motivo da ativação',draft.activationReason || '',value=>{if(!draft.activationPending)edit(()=>draft.activationReason=value);},{multiline:true,rows:2,disabled:!!draft.activationPending,help:'Descreva a revisão realizada (mínimo de 10 caracteres).'}),
    check('Revisei os nomes, assuntos, vínculos e situações e confirmo a ativação global.',reviewed,value=>{reviewed=value;activate.disabled=!value || (!draft.activationPending && (draft.status!=='REVIEW_READY' || draft.dirty===true || !!draft.pending));}),
    activate
  );
  if(draft.status!=='REVIEW_READY' || draft.dirty)box.append(h('p',{class:'status-line'},'Use “Salvar como pronta para revisão” antes de ativar.'));
  if(draft.activationPending)box.append(button('Conferir situação da ativação',()=>run('Conferindo ativação…',async()=>{draft.activationCheck=await api(`admin/catalog/activations/${draft.activationPending.operation_id}`);persist();await loadData();})),draft.activationCheck?h('pre',{},pretty(draft.activationCheck)):null);
  if(draft.activationCheck && draft.activationCheck.status!=='applied' && draft.activationCheck.current_hash!==draft.activationPending?.expected_catalog_hash){
    box.append(button('Preservar comando não aplicado e iniciar nova revisão',async()=>{
      if(await confirm('Continuar com a versão ativa?','O servidor confirmou que este comando não foi aplicado e que outra versão já está ativa. O comando original será preservado no arquivo local.')){
        edit(()=>{pushArchive(ws,'catalog-activation-not-applied',draft);delete ws.catalog;ensureCatalog();});
        render();
      }
    }));
  }
  if(!draft.activationPending)box.append(newRevision);
  return box;
}

export function editEntity(group,id,subjectId='') {
  ensureCatalog();
  if(catalogLocked(ws.catalog)){notice('Inicie uma nova revisão ou resolva a ativação pendente antes de editar.','warning');return;}
  entityGroup=group;
  const key=group==='trunks'?'trunk_id':group==='subjects'?'subject_id':'topic_id';
  const existing=ws.catalog.catalog[group].find(row=>row[key]===id);
  selectedEntity=existing?copy(existing):newCatalogEntity(group,subjectId);
  selectedEntity._existingId=id;
  ws.entityEditing={group,row:selectedEntity,aliasText:selectedEntity.aliases.join('\n')};
  persist();
  catalogTab=group;
  navigate('entity');
}

function entityView() {
  const group=entityGroup,row=selectedEntity,key=group==='trunks'?'trunk_id':group==='subjects'?'subject_id':'topic_id',catalog=ws.catalog.catalog,prior=catalog[group].find(r=>r[key]===row._existingId),baselineRow=data.base.catalog[group].find(r=>r[key]===row._existingId),baseline=Boolean(baselineRow),locked=catalogLocked(ws.catalog);
  let aliasText=ws.entityEditing?.aliasText ?? row.aliases.join('\n');
  const change=(fn,redraw=false)=>{edit(()=>{fn();ws.entityEditing={group,row,aliasText};});if(redraw)render();};
  const root=h('div',{},heading(prior?'Editar proposta':'Nova entidade no catálogo','Edite a revisão e ative pelo painel após conferir todas as alterações.',button('← Revisões do catálogo',()=>navigate('catalog'))));
  const form=h('form',{onsubmit:e=>{
    e.preventDefault();
    if(locked)return;
    const success=edit(()=>{
      let saved=copy(row);delete saved._existingId;
      saved[key]=String(saved[key] || '').trim();
      saved.label=saved.label.trim();
      saved.aliases=aliasText.split(/\n/).map(a=>a.trim()).filter(Boolean);
      const refEntity = baselineRow || prior;
      if(baselineRow && baselineRow.label!==saved.label){
        saved=renameEntity({...saved,label:baselineRow.label},saved.label);
      } else if(!baselineRow && refEntity && refEntity.label!==saved.label){
        saved.label = saved.label;
      }
      saved.source_ids=[...new Set([...saved.source_ids,'WEB_EDITOR'])];
      if(saved.revision!=null && baselineRow)saved.revision=(baselineRow.revision || 1)+1;
      const index=catalog[group].findIndex(r=>r[key]===row._existingId);
      if(index>=0){
        if(saved[key]!==row._existingId && catalog[group].some((r,idx)=>idx!==index && r[key]===saved[key])){
          throw new Error('Este ID já existe na proposta.');
        }
        catalog[group][index]=saved;
      } else {
        if(catalog[group].some(r=>r[key]===saved[key]))throw new Error('Este ID já existe na proposta.');
        catalog[group].push(saved);
      }
      ws.catalog.validation=null;ws.catalog.dirty=true;delete ws.catalog.advancedText;delete ws.entityEditing;
    });
    if(success)navigate('catalog');
  }});
  const fields=h('fieldset',{disabled:locked},
    h('legend',{},'Identidade e descrição'),
    h('div',{class:'form-grid'},
      field('ID estável',row[key],v=>change(()=>row[key]=v),{required:true,disabled:baseline,help:'Não depende do nome. IDs publicados não podem ser alterados ou reutilizados.'}),
      field('Nome',row.label,v=>change(()=>row.label=v),{required:true,maxlength:1000}),
      group==='trunks'?field('Ícone editorial',row.icon_key || 'Work',v=>change(()=>row.icon_key=v),{options:trunkIcons}):null,
      field('Situação',row.status,v=>change(()=>row.status=v),{options:[['ACTIVE','Ativo'],['DEPRECATED','Desativado, com identidade preservada']]}),
      field('Aliases (um por linha)',aliasText,v=>change(()=>aliasText=v),{multiline:true,wide:true,help:'Ao renomear entidade publicada, o nome anterior é mantido como alias sem duplicidade acentual.'})
    )
  );
  if(group==='subjects'){
    fields.append(field('Escopo da disciplina',row.scope_note,v=>change(()=>row.scope_note=v),{multiline:true,required:true}),h('h3',{},'Relações com troncos'));
    for(const trunk of catalog.trunks){
      const link=row.trunk_links.find(l=>l.trunk_id===trunk.trunk_id);
      fields.append(check(trunk.label,!!link,value=>change(()=>{
        if(value)row.trunk_links.push({trunk_id:trunk.trunk_id,role:'RELATED',default_weight:1.0,basis:'EDITORIAL_SUGGESTION',note:'Relação editorial conferida no painel.'});
        else row.trunk_links=row.trunk_links.filter(l=>l.trunk_id!==trunk.trunk_id);
      },true)));
      if(link){
        fields.append(h('div',{class:'form-grid'},
          field(`Papel em ${trunk.label}`,link.role,v=>change(()=>{link.role=v;link.default_weight=v==='FOUNDATIONAL'?1.5:1.0;link.basis='EDITORIAL_SUGGESTION';},true),{options:[['FOUNDATIONAL','Conteúdo central'],['RELATED','Conteúdo relacionado']]}),
          field(`Peso editorial em ${trunk.label}`,link.default_weight ?? (link.role==='FOUNDATIONAL'?1.5:1.0),v=>change(()=>{link.default_weight=Number(v) || 1.0;}),{type:'number',min:0.1,max:10,step:0.1}),
          field(`Justificativa em ${trunk.label}`,link.note,v=>change(()=>{link.note=v;link.basis='EDITORIAL_SUGGESTION';}),{wide:true})
        ));
      }
    }
  }
  if(group==='topics'){
    const parentTopics = orderedTopics(catalog, row.subject_id).filter(t=>t.kind==='TOPIC' && t.topic_id!==row[key]);
    fields.append(
      field('Disciplina',row.subject_id,v=>change(()=>{row.subject_id=v;row.parent_topic_id=null;row.kind='TOPIC';},true),{options:[['','Selecione…'],...catalog.subjects.map(s=>[s.subject_id,s.label])],disabled:baseline}),
      field('Assunto pai (vazio para assunto de primeiro nível)',row.parent_topic_id || '',v=>change(()=>{row.parent_topic_id=v || null;row.kind=v?'SUBTOPIC':'TOPIC';}),{options:[['','Assunto de primeiro nível'],...parentTopics.map(t=>[t.topic_id,topicPath(catalog,t)])],disabled:baseline})
    );
  }
  form.append(card(fields),actions(
    h('button',{type:'submit',class:'primary',disabled:locked},'Guardar alteração na proposta'),
    prior && !baseline ? button('Remover entidade nova do rascunho',async()=>{
      if(await confirm('Remover entidade não publicada?','Remove apenas esta entidade recém-criada na proposta local.')){
        edit(()=>{
          catalog[group]=catalog[group].filter(r=>r[key]!==row._existingId);
          ws.catalog.validation=null;ws.catalog.dirty=true;delete ws.catalog.advancedText;delete ws.entityEditing;
        });
        navigate('catalog');
      }
    },'danger') : null
  ));
  root.append(form);
  return root;
}

function catalogView() {
  const root=h('div',{},heading('Revisões do catálogo','Edite troncos, disciplinas e assuntos no catálogo unificado com identidades preservadas.',button('Consultar propostas salvas',()=>run('Carregando propostas…',async()=>{data.catalogDrafts=await api('admin/catalog/drafts');}))));
  root.append(card(badge(`Catálogo ativo v${data.base.catalog.catalog_version}`,'good'),h('p',{},'O catálogo unificado é compartilhado pelo Android e pelas questões. Salve uma revisão, confira os vínculos e ative a nova versão pelo painel.')));
  if (ws.entityEditing) {
    root.append(card(
      badge('Edição de entidade em aberto','warning'),
      h('p',{},`Há alterações ainda não guardadas em "${ws.entityEditing.row.label || 'Nova entidade'}".`),
      actions(
        button('Continuar edição da entidade',()=>{entityGroup=ws.entityEditing.group;selectedEntity=ws.entityEditing.row;navigate('entity');},'primary'),
        button('Descartar alteração em aberto',()=>{edit(()=>{delete ws.entityEditing;});render();})
      )
    ));
  }
  root.append(button('Consultar histórico de ativações',()=>run('Carregando histórico…',async()=>{data.catalogHistory=await api('admin/catalog/history');})));
  if(data.catalogHistory){
    const rows=data.catalogHistory.versions;
    root.append(card(
      h('h2',{},'Ativações confirmadas'),
      button(`Exportar catálogo base v${data.base.catalog.catalog_version}`,()=>download(`catalogo-v${data.base.catalog.catalog_version}.json`,data.base.catalog)),
      ...rows.map(row=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},`Versão ${row.version}`),h('small',{},row.reason)),button('Exportar versão',()=>run('Exportando versão…',async()=>download(`catalogo-v${row.version}.json`,await api(`admin/catalog/versions/${row.version}`)))))),
      rows.length===50?button('Próxima página →',()=>run('Carregando histórico…',async()=>{data.catalogHistory=await api(`admin/catalog/history?before=${rows.at(-1).version}`);})):null
    ));
  }
  if(!ws.catalog){root.append(button('Criar proposta a partir da versão atual',startCatalog,'primary'));}
  else {
    const draft=ws.catalog,catalog=draft.catalog;
    root.append(catalogActivationPanel(draft));
    if(draft.pending)root.append(card(badge('Salvamento aguardando confirmação','warning'),h('p',{},'Reenvie o comando original ou confira a revisão salva antes de editar.'),button('Comparar proposta do servidor',()=>run('Conferindo proposta…',async()=>{draft.server=await api(`admin/catalog/drafts/${encodeURIComponent(draft.draft_id)}`);persist();}))));
    if(draft.server)root.append(card(h('h2',{},`Servidor · revisão ${draft.server.revision}`),h('pre',{},pretty(draft.server.catalog)),button('Usar revisão do servidor e preservar minha edição',async()=>{if(await confirm('Continuar a partir da proposta salva?','Sua edição e seu comando pendente ficarão preservados no arquivo local.')){edit(()=>{const server=draft.server;pushArchive(ws,'catalog',draft);ws.catalog={draft_id:server.draft_id,revision:server.revision,base_catalog_sha256:server.base_catalog_sha256,status:server.status,catalog:copy(server.catalog),pending:null,validation:null};});render();}})));
    root.append(card(
      h('h2',{},`Proposta v${catalog.catalog_version}`),
      h('p',{},`Revisão salva: ${draft.revision} · ${draft.status}`),
      draft.pending?badge('Comando de salvamento pendente','warning'):badge('Edição local'),
      h('div',{class:'tabs'},...['trunks','subjects','topics'].map(group=>button({trunks:'Troncos',subjects:'Disciplinas',topics:'Assuntos e subassuntos'}[group],()=>{catalogTab=group;render();},catalogTab===group?'active':''))),
      button('＋ Nova entidade',()=>{if(!catalogLocked(draft))editEntity(catalogTab,null);}),
      ...catalog[catalogTab].map(row=>h('div',{class:'catalog-row'},
        h('div',{},h('strong',{},row.label),h('small',{},row.trunk_id || (row.subject_id && catalogTab==='subjects'?row.subject_id:row.topic_id))),
        actions(badge(row.status==='ACTIVE'?'Ativo':'Desativado',row.status==='ACTIVE'?'good':''),button('Editar proposta',()=>{if(!catalogLocked(draft))editEntity(catalogTab,row.trunk_id || (catalogTab==='subjects'?row.subject_id:row.topic_id));}))
      ))
    ));
    root.append(card(
      h('h2',{},'Validar e salvar versão'),
      h('p',{},'Rascunhos incompletos podem ser salvos. Para marcar como pronta para revisão, a proposta precisa passar pelo schema e pelas regras de identidade e referência.'),
      actions(
        button('Validar proposta',()=>run('Validando catálogo…',async()=>{draft.validation=await api('admin/catalog/validate',{method:'POST',body:catalog});persist();notice('Revisão validada. Salve como pronta para revisão e ative pelo painel.');})),
        button(draft.pending?'Conferir / reenviar salvamento':'Salvar rascunho no servidor',()=>saveCatalog('DRAFT'),'primary'),
        button('Salvar como pronta para revisão',()=>saveCatalog('REVIEW_READY'))
      ),
      draft.validation?h('pre',{},pretty({...draft.validation,registry:undefined})):null,
      h('hr',{class:'divider'}),
      actions(
        button('Exportar knowledge-catalog.json',()=>download('knowledge-catalog.json',catalog)),
        button('Exportar registro de IDs',()=>run('Validando registro…',async()=>{const result=await api('admin/catalog/validate',{method:'POST',body:catalog});download('knowledge-id-registry.json',result.registry);}))
      ),
      h('details',{'data-panel-key':'catalog-advanced-json'},
        h('summary',{},'Edição avançada em JSON'),
        field('Catálogo completo',draft.advancedText ?? pretty(catalog),text=>{if(!catalogLocked(draft))edit(()=>{draft.advancedText=text;});},{multiline:true,rows:15,code:true,disabled:catalogLocked(draft)}),
        button('Aplicar JSON à proposta',()=>{
          if(catalogLocked(draft))return;
          try{
            const parsed=JSON.parse(draft.advancedText || pretty(catalog));
            if(!parsed || ['trunks','subjects','topics','sources','relations'].some(key=>!Array.isArray(parsed[key])))throw new Error('Preserve as listas do catálogo no documento JSON.');
            edit(()=>{draft.catalog=parsed;draft.validation=null;draft.dirty=true;delete draft.advancedText;});
            render();
          }catch(error){notice(error.message,'error');}
        })
      )
    ));
  }
  if(data.catalogDrafts)root.append(card(h('h2',{},'Propostas salvas nesta conta'),...data.catalogDrafts.map(draft=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},draft.draft_id),h('small',{},`Revisão ${draft.revision} · ${draft.status}`)),button('Carregar',async()=>{if(ws.catalog?.pending || ws.catalog?.activationPending){notice('Resolva o salvamento pendente antes de carregar outra proposta.','warning');return;}if(await confirm('Carregar proposta salva?','A edição local atual será preservada em Rascunhos e pendências.'))run('Carregando proposta…',async()=>{const result=await api(`admin/catalog/drafts/${encodeURIComponent(draft.draft_id)}`);if(ws.catalog)pushArchive(ws,'catalog',ws.catalog);ws.catalog={draft_id:result.draft_id,revision:result.revision,base_catalog_sha256:result.base_catalog_sha256,status:result.status,catalog:result.catalog,pending:null,validation:null};persist();});})))));
  return root;
}

function saveCatalog(status) {
  run('Salvando proposta versionada…',async()=>{
    const draft=ws.catalog;
    if(draft.activationPending || (catalogLocked(draft) && !draft.pending))throw new Error('Inicie uma nova revisão antes de salvar outra versão.');
    draft.pending ||= {draft_id:draft.draft_id,expected_revision:draft.revision,base_catalog_sha256:draft.base_catalog_sha256,status,catalog:copy(draft.catalog)};
    persist();
    let result;
    try{result=await api('admin/catalog/drafts',{method:'POST',body:draft.pending});}
    catch(error){if(error.status>=400 && error.status<500){draft.pending=null;persist();}throw error;}
    const saved=result.draft;
    if(saved.draft_id!==draft.draft_id || saved.revision!==draft.pending.expected_revision+1)throw new Error('Recibo da proposta divergente.');
    draft.revision=saved.revision;draft.status=saved.status;draft.pending=null;draft.validation=result.validation;draft.dirty=false;
    persist();
    notice('Versão editorial salva. O catálogo ativo permanece na versão publicada.');
  });
}

function draftsView() {
  ws.archives = sanitizeArchives(ws.archives);
  const root=h('div',{},heading('Rascunhos e pendências','Dados locais separados por conta. Envios incertos conservam seus comandos.',button('Exportar todos',()=>download('cogniva-rascunhos.json',exportWorkspace(me.user_id,ws)),'primary')));
  if(ws.entityEditing)root.append(card(h('h2',{},'Entidade do catálogo em edição'),h('p',{},ws.entityEditing.row.label || 'Nova entidade'),button('Continuar edição',()=>{entityGroup=ws.entityEditing.group;selectedEntity=ws.entityEditing.row;navigate('entity');})));
  root.append(card(
    h('h2',{},'Continuar um trabalho'),
    ws.import?h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Inserção de questões'),h('small',{},ws.import.submitted && !ws.import.result?.complete?'Envio aguardando confirmação':'Rascunho local')),button('Abrir',()=>navigate('import'))):null,
    ...Object.entries(ws.exams).map(([id,draft])=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},draft.document.short_name || draft.document.title || 'Template sem nome'),h('small',{},draft.pending?'Comando pendente':draft.conflict?'Conflito com servidor':'Edição local')),button('Abrir',()=>{selectedExam=id;navigate('exam');}))),
    ...Object.entries(ws.questionEdits).map(([id,draft])=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},`Questão ${id}`),h('small',{},draft.submitted && !draft.result?'Envio aguardando confirmação':draft.conflict?'Conflito com servidor':'Contrato em edição')),button('Abrir',()=>openQuestionEditor(id)))),
    ws.catalog?button('Continuar proposta do catálogo',()=>navigate('catalog')):null
  ));
  const file=h('input',{type:'file',accept:'.json,application/json','aria-label':'Restaurar rascunhos desta conta',onchange:async e=>{
    try{
      const selected=e.target.files[0];if(!selected)return;
      if(selected.size>4*1024*1024)throw new Error('Arquivo excede 4 MiB.');
      const recovered=restoreWorkspace(me.user_id,JSON.parse(await selected.text()));
      if(await confirm('Restaurar este arquivo?','A cópia atual será preservada dentro do arquivo local (até 20 registros, sem duplicação recursiva). Comandos de envio do arquivo restaurado continuarão com as mesmas identidades.')){
        recovered.archives = sanitizeArchives(recovered.archives);
        pushArchive(recovered,'workspace-backup',ws);
        ws=recovered;ws.exams ||= {};ws.questionEdits ||= {};
        persist();
        render();
        notice('Rascunhos restaurados para esta conta.');
      }
    }catch(error){notice(error.message,'error');}
  }});
  const clearBtn = ws.archives.length
    ? button('Limpar histórico local', async () => {
        if (await confirm('Limpar histórico local?', 'Remove apenas as cópias arquivadas localmente neste navegador, mantendo os rascunhos em andamento.')) {
          ws.archives = [];
          persist();
          render();
          notice('Histórico local limpo.');
        }
      }, 'danger')
    : null;
  root.append(
    card(h('h2',{},'Restaurar arquivo exportado'),h('p',{},'Restaure apenas um arquivo desta conta. Nenhum envio é disparado ao abrir o arquivo.'),file),
    card(
      h('div',{class:'component-head'},
        h('h2',{},`Arquivo local · ${ws.archives.length} registros preservados (máx. 20)`),
        clearBtn
      ),
      h('p',{},'Edições substituídas ficam disponíveis para exportação sem aninhamento recursivo. Elas não são reenviadas automaticamente.'),
      ...ws.archives.map((item,index)=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},`${index+1}. ${item.type}`),h('small',{},item.saved_at)),button('Exportar',()=>download(`cogniva-arquivo-${index+1}.json`,item))))
    )
  );
  return root;
}

function setAuthMode(mode) {
  authMode=mode;
  const isReg=mode==='register';
  $('#auth-tab-login')?.classList.toggle('active',!isReg);
  $('#auth-tab-login')?.setAttribute('aria-selected',String(!isReg));
  $('#auth-tab-register')?.classList.toggle('active',isReg);
  $('#auth-tab-register')?.setAttribute('aria-selected',String(isReg));
  const nameField = $('#login-name-field');
  if (nameField) nameField.hidden = !isReg;
  $('#login-password')?.setAttribute('autocomplete',isReg?'new-password':'current-password');
  const subtitle = $('#login-subtitle');
  if (subtitle) {
    subtitle.textContent = isReg
      ? 'Cadastre a senha inicial de um e-mail administrativo previamente autorizado em ADMIN_EMAILS.'
      : 'Entre com o e-mail e a senha da sua conta administrativa Cogniva.';
  }
  const submit = $('#login-submit');
  if (submit) submit.textContent = isReg ? 'Criar conta administrativa ↗' : 'Entrar no painel ↗';
  const msg = $('#login-message');
  if (msg) msg.textContent = '';
}

export function shouldWarnBeforeUnload() {
  if (busy || quotaExceeded) return true;
  if (!me || storageBlocked) return false;
  if (ws.entityEditing) return true;
  return false;
}

export function getAppState() {
  return {current, me, data, ws, busy, storageBlocked, quotaExceeded, loadErrors, selectedExam, selectedQuestion, questionPage, questionCursor, questionCursorStack, proposalsData, reportsData};
}

export function setAppStateForTest(patch = {}) {
  if ('current' in patch) current = patch.current;
  if ('me' in patch) me = patch.me;
  if ('data' in patch) data = patch.data;
  if ('ws' in patch) ws = patch.ws;
  if ('busy' in patch) busy = patch.busy;
  if ('storageBlocked' in patch) storageBlocked = patch.storageBlocked;
  if ('quotaExceeded' in patch) quotaExceeded = patch.quotaExceeded;
  if ('loadErrors' in patch) loadErrors = patch.loadErrors;
  if ('selectedExam' in patch) selectedExam = patch.selectedExam;
  if ('selectedQuestion' in patch) selectedQuestion = patch.selectedQuestion;
  if ('questionPage' in patch) questionPage = patch.questionPage;
  if ('questionCursor' in patch) questionCursor = patch.questionCursor;
  if ('questionCursorStack' in patch) questionCursorStack = patch.questionCursorStack;
  if ('proposalsData' in patch) proposalsData = patch.proposalsData;
  if ('reportsData' in patch) reportsData = patch.reportsData;
  if (me && $('#app') && $('#login')) {
    $('#login').hidden = true;
    $('#app').hidden = false;
  }
}

export function initApp({autoBootstrap = true} = {}) {
  if (typeof document === 'undefined' || typeof window === 'undefined') return;
  if (boundDocument !== document) {
    boundDocument = document;
    $('#auth-tab-login')?.addEventListener('click',()=>setAuthMode('login'));
    $('#auth-tab-register')?.addEventListener('click',()=>setAuthMode('register'));
    document.querySelectorAll('[data-page]').forEach(node=>node.addEventListener('click',()=>{if(node.dataset.page==='help')helpFocus='';navigate(node.dataset.page);}));
    $('#menu-toggle')?.addEventListener('click',()=>setMobileMenuOpen(!$('#app')?.classList.contains('menu-open')));
    $('#menu-close')?.addEventListener('click',()=>setMobileMenuOpen(false,{restoreFocus:true}));
    $('#sidebar-backdrop')?.addEventListener('click',()=>setMobileMenuOpen(false,{restoreFocus:true}));
    const dialog = $('#confirm-dialog');
    dialog?.addEventListener('click', event => {
      if (event.target === dialog && dialog.open) {
        dialog.returnValue = 'cancel';
        dialog.close('cancel');
      }
    });
    document.addEventListener('keydown', event => {
      if (event.key !== 'Escape') return;
      const dlg = $('#confirm-dialog');
      if (dlg?.open) {
        event.preventDefault?.();
        dlg.returnValue = 'cancel';
        dlg.close('cancel');
        return;
      }
      if ($('#app')?.classList.contains('menu-open')) {
        event.preventDefault?.();
        setMobileMenuOpen(false,{restoreFocus:true});
      }
    });
    $('#logout')?.addEventListener('click', () => {
      performLogout();
    });
    $('#login-form')?.addEventListener('submit',async event=>{
      event.preventDefault();
      const emailInput=$('#login-email'),passwordInput=$('#login-password'),nameInput=$('#login-display-name'),submit=$('#login-submit');
      submit.disabled=true;
      $('#login-message').textContent=authMode==='register'?'Criando conta administrativa…':'Verificando credenciais…';
      try {
        const endpoint=authMode==='register'?'auth/register':'auth/login';
        const payload={email:emailInput.value.trim(),password:passwordInput.value,client:'web'};
        if(authMode==='register' && nameInput.value.trim())payload.display_name=nameInput.value.trim();
        await api(endpoint,{method:'POST',body:payload,skipAuthRedirect:true});
        passwordInput.value='';
        await bootstrap();
        $('#login-message').textContent='';
      } catch(error) {
        passwordInput.value='';
        $('#login-message').textContent=error.message;
        notice(error.message,'error');
      } finally {
        submit.disabled=false;
      }
    });
    window.addEventListener('online',()=>{const c=$('#connection');if(c)c.textContent='Conectado à base Cogniva';});
    window.addEventListener('offline',()=>{const c=$('#connection');if(c)c.textContent='Offline · rascunhos preservados';});
    window.addEventListener('beforeunload',event=>{if(shouldWarnBeforeUnload()){event.preventDefault();event.returnValue='';}});
  }
  if (autoBootstrap) {
    bootstrap().catch(error=>{if(error.status!==401){const msg=$('#login-message');if(msg)msg.textContent=error.message;}});
  }
}

if (typeof document !== 'undefined' && typeof window !== 'undefined' && !globalThis.__COGNIVA_SKIP_AUTO_INIT__) {
  initApp({autoBootstrap: true});
}
