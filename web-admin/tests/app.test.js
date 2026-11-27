import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {
  MAX_ARCHIVES,
  blankExam,
  exportWorkspace,
  freshImport,
  isQuestionDraftDirty,
  matchesSearch,
  newComponent,
  normalizeSearchText,
  objectiveDraft,
  parseOpenEndedGradingJson,
  pushArchive,
  restoreWorkspace
} from '../domain.js';
import {helpTopics, searchHelp} from '../help.js';
import {
  api,
  bootstrap,
  confirm,
  getAppState,
  h,
  initApp,
  loadData,
  navigate,
  openExam,
  openQuestionEditor,
  performLogout,
  persist,
  render,
  setAppStateForTest,
  shouldWarnBeforeUnload
} from '../app.js';

// --- Lightweight zero-dependency DOM + Storage shim for Node test runner ---

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

class FakeNode {
  constructor(nodeType) {
    this.nodeType = nodeType;
    this.parentNode = null;
    this.ownerDocument = null;
  }
  get parentElement() {
    return this.parentNode?.nodeType === 1 ? this.parentNode : null;
  }
  remove() {
    if (!this.parentNode) return;
    const idx = this.parentNode.childNodes.indexOf(this);
    if (idx >= 0) this.parentNode.childNodes.splice(idx, 1);
    this.parentNode = null;
  }
  after(...nodes) {
    if (!this.parentNode) return;
    const parent = this.parentNode;
    const idx = parent.childNodes.indexOf(this);
    if (idx < 0) return;
    const prepared = nodes.flat(Infinity).filter(n => n != null && n !== false).map(n => (
      n instanceof FakeNode ? n : new FakeText(String(n), this.ownerDocument)
    ));
    for (const node of prepared) {
      node.remove();
      node.parentNode = parent;
      node.ownerDocument = this.ownerDocument;
    }
    parent.childNodes.splice(idx + 1, 0, ...prepared);
  }
}

class FakeText extends FakeNode {
  constructor(text, ownerDocument = null) {
    super(3);
    this.data = String(text);
    this.ownerDocument = ownerDocument;
  }
  get textContent() { return this.data; }
  set textContent(v) { this.data = String(v ?? ''); }
  serializeHtml() { return escapeHtml(this.data); }
}

class FakeClassList {
  constructor(el) { this.el = el; }
  _tokens() {
    return new Set((this.el._attrs.get('class') || '').split(/\s+/).filter(Boolean));
  }
  _save(set) {
    const str = [...set].join(' ');
    if (str) this.el._attrs.set('class', str);
    else this.el._attrs.delete('class');
  }
  add(...names) {
    const s = this._tokens();
    names.filter(Boolean).forEach(n => s.add(n));
    this._save(s);
  }
  remove(...names) {
    const s = this._tokens();
    names.forEach(n => s.delete(n));
    this._save(s);
  }
  contains(name) { return this._tokens().has(name); }
  toggle(name, force) {
    const s = this._tokens();
    const next = force !== undefined ? Boolean(force) : !s.has(name);
    if (next) s.add(name);
    else s.delete(name);
    this._save(s);
    return next;
  }
}

function splitSelectorOutsideBrackets(input, delim) {
  const out = [];
  let cur = '', depth = 0, quote = null;
  for (const ch of input) {
    if (quote) {
      cur += ch;
      if (ch === quote) quote = null;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
      cur += ch;
    } else if (ch === '[') {
      depth++;
      cur += ch;
    } else if (ch === ']') {
      depth = Math.max(0, depth - 1);
      cur += ch;
    } else if (depth === 0 && (delim === ' ' ? /\s/.test(ch) : ch === delim)) {
      if (cur.trim()) out.push(cur.trim());
      cur = '';
    } else {
      cur += ch;
    }
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

function matchesSimple(el, simple) {
  if (!el || el.nodeType !== 1) return false;
  let rest = simple.trim();
  if (!rest) return false;
  const attrs = [];
  rest = rest.replace(/\[([a-zA-Z0-9_-]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\]]*)))?\]/g, (_, k, d, s, b) => {
    attrs.push([k, d ?? s ?? b ?? null]);
    return '';
  });
  let id = null;
  rest = rest.replace(/#([a-zA-Z0-9_-]+)/g, (_, m) => { id = m; return ''; });
  const classes = [];
  rest = rest.replace(/\.([a-zA-Z0-9_-]+)/g, (_, m) => { classes.push(m); return ''; });
  const tag = rest.trim();
  if (tag && tag !== '*' && el.tagName !== tag.toUpperCase()) return false;
  if (id && el.id !== id) return false;
  for (const cls of classes) if (!el.classList.contains(cls)) return false;
  for (const [k, v] of attrs) {
    if (!el.hasAttribute(k)) return false;
    if (v !== null && el.getAttribute(k) !== v) return false;
  }
  return true;
}

function matchesSelector(el, group) {
  return splitSelectorOutsideBrackets(group, ',').some(raw => {
    const chain = splitSelectorOutsideBrackets(raw, ' ');
    let idx = chain.length - 1;
    if (!matchesSimple(el, chain[idx])) return false;
    idx--;
    let cur = el.parentElement;
    while (idx >= 0) {
      while (cur && !matchesSimple(cur, chain[idx])) cur = cur.parentElement;
      if (!cur) return false;
      idx--;
      cur = cur.parentElement;
    }
    return true;
  });
}

class FakeElement extends FakeNode {
  constructor(tagName, ownerDocument = null) {
    super(1);
    this.tagName = String(tagName).toUpperCase();
    this.ownerDocument = ownerDocument;
    this.childNodes = [];
    this._attrs = new Map();
    this._listeners = new Map();
    this.classList = new FakeClassList(this);
    this.dataset = new Proxy({}, {
      get: (_, prop) => {
        const attr = 'data-' + String(prop).replace(/[A-Z]/g, m => '-' + m.toLowerCase());
        return this._attrs.get(attr);
      },
      set: (_, prop, val) => {
        const attr = 'data-' + String(prop).replace(/[A-Z]/g, m => '-' + m.toLowerCase());
        this.setAttribute(attr, val);
        return true;
      }
    });
    this._value = '';
    this.checked = false;
    this.disabled = false;
    this.selected = false;
    this.hidden = false;
    this.open = false;
    this.returnValue = '';
    this.selectionStart = null;
    this.selectionEnd = null;
    this.files = [];
  }
  get id() { return this._attrs.get('id') || ''; }
  set id(v) { if (v) this._attrs.set('id', String(v)); else this._attrs.delete('id'); }
  get className() { return this._attrs.get('class') || ''; }
  set className(v) { if (v) this._attrs.set('class', String(v)); else this._attrs.delete('class'); }
  get value() { return this._value; }
  set value(v) { this._value = String(v ?? ''); }
  get firstElementChild() { return this.childNodes.find(n => n.nodeType === 1) || null; }
  get children() { return this.childNodes.filter(n => n.nodeType === 1); }
  get textContent() { return this.childNodes.map(c => c.textContent).join(''); }
  set textContent(v) {
    this.childNodes = [];
    if (v !== '' && v != null) this.append(new FakeText(String(v), this.ownerDocument));
  }
  setAttribute(name, val) {
    const key = String(name).toLowerCase();
    this._attrs.set(key, String(val));
    if (key === 'open') this.open = true;
    if (key === 'hidden') this.hidden = true;
    if (key === 'disabled') this.disabled = true;
    if (key === 'value') this._value = String(val);
  }
  getAttribute(name) {
    const key = String(name).toLowerCase();
    return this._attrs.has(key) ? this._attrs.get(key) : null;
  }
  hasAttribute(name) { return this._attrs.has(String(name).toLowerCase()); }
  removeAttribute(name) {
    const key = String(name).toLowerCase();
    this._attrs.delete(key);
    if (key === 'open') this.open = false;
    if (key === 'hidden') this.hidden = false;
    if (key === 'disabled') this.disabled = false;
  }
  append(...nodes) {
    for (const raw of nodes.flat(Infinity)) {
      if (raw == null || raw === false) continue;
      const node = raw instanceof FakeNode ? raw : new FakeText(String(raw), this.ownerDocument);
      node.remove();
      node.parentNode = this;
      node.ownerDocument = this.ownerDocument;
      this.childNodes.push(node);
    }
  }
  prepend(...nodes) {
    const prepared = [];
    for (const raw of nodes.flat(Infinity)) {
      if (raw == null || raw === false) continue;
      const node = raw instanceof FakeNode ? raw : new FakeText(String(raw), this.ownerDocument);
      node.remove();
      node.parentNode = this;
      node.ownerDocument = this.ownerDocument;
      prepared.push(node);
    }
    this.childNodes.unshift(...prepared);
  }
  replaceChildren(...nodes) {
    for (const c of this.childNodes) c.parentNode = null;
    this.childNodes = [];
    this.append(...nodes);
  }
  contains(other) {
    let cur = other;
    while (cur) {
      if (cur === this) return true;
      cur = cur.parentNode;
    }
    return false;
  }
  addEventListener(type, fn, opts) {
    const key = String(type);
    const list = this._listeners.get(key) || [];
    list.push({fn, once: Boolean(opts?.once)});
    this._listeners.set(key, list);
  }
  removeEventListener(type, fn) {
    const key = String(type);
    const list = this._listeners.get(key);
    if (!list) return;
    this._listeners.set(key, list.filter(entry => entry.fn !== fn));
  }
  dispatchEvent(event) {
    const ev = {
      type: event.type,
      target: event.target || this,
      currentTarget: this,
      bubbles: Boolean(event.bubbles),
      key: event.key,
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; }
    };
    const invokeOn = node => {
      ev.currentTarget = node;
      const entries = [...(node._listeners.get(ev.type) || [])];
      for (const entry of entries) {
        if (entry.once) node.removeEventListener(ev.type, entry.fn);
        entry.fn.call(node, ev);
      }
    };
    invokeOn(this);
    if (ev.bubbles) {
      let p = this.parentElement;
      while (p) {
        invokeOn(p);
        p = p.parentElement;
      }
      if (this.ownerDocument) invokeOn(this.ownerDocument);
    }
    return !ev.defaultPrevented;
  }
  click() {
    if (this.disabled) return;
    if (this.tagName === 'SUMMARY' && this.parentElement?.tagName === 'DETAILS') {
      this.parentElement.open = !this.parentElement.open;
      this.parentElement.dispatchEvent({type: 'toggle', bubbles: false});
      return;
    }
    const allowed = this.dispatchEvent({type: 'click', bubbles: true});
    if (allowed && this.tagName === 'BUTTON' && (this.getAttribute('type') || 'submit') === 'submit') {
      let p = this.parentElement;
      while (p && p.tagName !== 'FORM') p = p.parentElement;
      if (p) {
        if (p.getAttribute('method') === 'dialog') {
          let dlg = p.parentElement;
          while (dlg && dlg.tagName !== 'DIALOG') dlg = dlg.parentElement;
          if (dlg) dlg.close(this.getAttribute('value') || '');
        } else {
          p.dispatchEvent({type: 'submit', bubbles: true});
        }
      }
    }
  }
  focus() {
    if (this.disabled) return;
    if (this.ownerDocument) this.ownerDocument.activeElement = this;
  }
  setSelectionRange(start, end) {
    this.selectionStart = start;
    this.selectionEnd = end;
  }
  showModal() {
    if (this.open) {
      const err = new Error('InvalidStateError: dialog is already open');
      err.name = 'InvalidStateError';
      throw err;
    }
    this.open = true;
  }
  close(retVal) {
    if (retVal !== undefined) this.returnValue = String(retVal);
    this.open = false;
    this.dispatchEvent({type: 'close', bubbles: false});
  }
  querySelectorAll(selector) {
    const out = [];
    const visit = node => {
      for (const child of node.childNodes || []) {
        if (child.nodeType === 1) {
          if (matchesSelector(child, selector)) out.push(child);
          visit(child);
        }
      }
    };
    visit(this);
    return out;
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  serializeHtml() {
    const tag = this.tagName.toLowerCase();
    const attrs = [...this._attrs.entries()].map(([k, v]) => ` ${k}="${escapeHtml(v)}"`).join('');
    const inner = this.childNodes.map(c => c.serializeHtml()).join('');
    return `<${tag}${attrs}>${inner}</${tag}>`;
  }
}

class FakeDocument extends FakeElement {
  constructor() {
    super('#document');
    this.body = new FakeElement('body', this);
    this.append(this.body);
    this.activeElement = this.body;
  }
  createElement(tag) { return new FakeElement(tag, this); }
  createTextNode(text) { return new FakeText(text, this); }
  getElementById(id) { return this.querySelector(`#${id}`); }
}

class FakeStorage {
  constructor() {
    this.map = new Map();
    this.failWithQuota = false;
  }
  getItem(k) { return this.map.has(k) ? this.map.get(k) : null; }
  setItem(k, v) {
    if (this.failWithQuota) {
      const err = new Error('The quota has been exceeded.');
      err.name = 'QuotaExceededError';
      err.code = 22;
      throw err;
    }
    this.map.set(String(k), String(v));
  }
  removeItem(k) { this.map.delete(String(k)); }
  clear() { this.map.clear(); }
}

function buildPortalShell(doc) {
  const login = doc.createElement('section');
  login.id = 'login';
  const tabLogin = doc.createElement('button'); tabLogin.id = 'auth-tab-login'; tabLogin.setAttribute('type', 'button');
  const tabReg = doc.createElement('button'); tabReg.id = 'auth-tab-register'; tabReg.setAttribute('type', 'button');
  const subtitle = doc.createElement('p'); subtitle.id = 'login-subtitle';
  const form = doc.createElement('form'); form.id = 'login-form';
  const nameField = doc.createElement('div'); nameField.id = 'login-name-field'; nameField.hidden = true;
  const nameInput = doc.createElement('input'); nameInput.id = 'login-display-name'; nameField.append(nameInput);
  const emailInput = doc.createElement('input'); emailInput.id = 'login-email';
  const passInput = doc.createElement('input'); passInput.id = 'login-password';
  const submit = doc.createElement('button'); submit.id = 'login-submit'; submit.setAttribute('type', 'submit');
  form.append(nameField, emailInput, passInput, submit);
  const loginMsg = doc.createElement('p'); loginMsg.id = 'login-message';
  login.append(tabLogin, tabReg, subtitle, form, loginMsg);

  const app = doc.createElement('div'); app.id = 'app'; app.hidden = true; app.className = 'app-shell';
  const sidebar = doc.createElement('aside'); sidebar.id = 'sidebar'; sidebar.className = 'sidebar';
  const menuClose = doc.createElement('button'); menuClose.id = 'menu-close'; menuClose.setAttribute('type', 'button');
  const nav = doc.createElement('nav');
  for (const page of ['overview','missions','exams','edital','trunks','subjects','questions','import','classification','reports','catalog','drafts','help']) {
    const b = doc.createElement('button');
    b.setAttribute('type', 'button');
    b.setAttribute('data-page', page);
    b.textContent = page;
    nav.append(b);
  }
  const connection = doc.createElement('span'); connection.id = 'connection';
  sidebar.append(menuClose, nav, connection);

  const backdrop = doc.createElement('div');
  backdrop.id = 'sidebar-backdrop';
  backdrop.className = 'sidebar-backdrop';
  backdrop.hidden = true;

  const mainShell = doc.createElement('div');
  const menuToggle = doc.createElement('button'); menuToggle.id = 'menu-toggle'; menuToggle.setAttribute('type', 'button'); menuToggle.setAttribute('aria-expanded', 'false');
  const breadcrumb = doc.createElement('span'); breadcrumb.id = 'breadcrumb';
  const accountName = doc.createElement('span'); accountName.id = 'account-name';
  const logout = doc.createElement('button'); logout.id = 'logout'; logout.setAttribute('type', 'button');
  const content = doc.createElement('main'); content.id = 'content';
  const notice = doc.createElement('div'); notice.id = 'notice'; notice.hidden = true;
  const progress = doc.createElement('div'); progress.id = 'progress'; progress.hidden = true;
  const view = doc.createElement('div'); view.id = 'view';
  content.append(notice, progress, view);
  mainShell.append(menuToggle, breadcrumb, accountName, logout, content);
  app.append(sidebar, backdrop, mainShell);

  const dialog = doc.createElement('dialog'); dialog.id = 'confirm-dialog';
  const dlgForm = doc.createElement('form'); dlgForm.setAttribute('method', 'dialog'); dlgForm.className = 'dialog-panel';
  const dlgTitle = doc.createElement('h2'); dlgTitle.id = 'confirm-title';
  const dlgText = doc.createElement('p'); dlgText.id = 'confirm-text';
  const cancelBtn = doc.createElement('button'); cancelBtn.setAttribute('value', 'cancel'); cancelBtn.textContent = 'Cancelar';
  const okBtn = doc.createElement('button'); okBtn.setAttribute('value', 'confirm'); okBtn.textContent = 'Confirmar';
  dlgForm.append(dlgTitle, dlgText, cancelBtn, okBtn);
  dialog.append(dlgForm);

  doc.body.append(login, app, dialog);
}

function sampleCatalog() {
  return {
    $schema: 'knowledge-catalog.schema.json',
    schema_version: 2,
    catalog_id: 'cogniva-knowledge',
    catalog_version: 2,
    updated_at: '2026-01-01',
    status: 'ACTIVE',
    review_status: 'APPROVED',
    language: 'pt-BR',
    trunks: [
      {trunk_id: 'fiscal', label: 'Área Fiscal', icon_key: 'Fiscal', aliases: ['Fisco'], status: 'ACTIVE', source_ids: ['BASE']},
      {trunk_id: 'controle', label: 'Controle e Gestão', icon_key: 'Control', aliases: ['TCU'], status: 'ACTIVE', source_ids: ['BASE']}
    ],
    subjects: [
      {subject_id: 'AFO', label: 'Orçamento Público', aliases: ['AFO'], scope_note: 'Orçamento e finanças', status: 'ACTIVE', revision: 1, source_ids: ['BASE'], trunk_links: [{trunk_id: 'fiscal', role: 'FOUNDATIONAL', default_weight: 1.5, basis: 'LEGACY_TEMPLATE', note: 'Base canônica'}]},
      {subject_id: 'DIR_TRIB', label: 'Direito Tributário', aliases: ['CTN'], scope_note: 'Tributos', status: 'ACTIVE', revision: 1, source_ids: ['BASE'], trunk_links: [{trunk_id: 'fiscal', role: 'FOUNDATIONAL', default_weight: 2.0, basis: 'LEGACY_TEMPLATE', note: 'Base canônica'}]}
    ],
    topics: [
      {topic_id: 'TRIB_CRED', subject_id: 'DIR_TRIB', parent_topic_id: null, kind: 'TOPIC', label: 'Crédito Tributário', aliases: [], status: 'ACTIVE', revision: 1, source_ids: ['BASE']},
      {topic_id: 'TRIB_LANC', subject_id: 'DIR_TRIB', parent_topic_id: 'TRIB_CRED', kind: 'SUBTOPIC', label: 'Lançamento', aliases: [], status: 'ACTIVE', revision: 1, source_ids: ['BASE']},
      {topic_id: 'TRIB_BASE', subject_id: 'DIR_TRIB', parent_topic_id: null, kind: 'TOPIC', label: 'Assunto Inicial', aliases: [], status: 'ACTIVE', revision: 1, source_ids: ['BASE']},
      {topic_id: 'TRIB_BASE_SUB', subject_id: 'DIR_TRIB', parent_topic_id: 'TRIB_BASE', kind: 'SUBTOPIC', label: 'Normas Gerais', aliases: [], status: 'ACTIVE', revision: 1, source_ids: ['BASE']},
      {topic_id: 'AFO_LOA', subject_id: 'AFO', parent_topic_id: null, kind: 'TOPIC', label: 'Lei Orçamentária Anual', aliases: ['LOA'], status: 'ACTIVE', revision: 1, source_ids: ['BASE']},
      {topic_id: 'AFO_LOA_SUB', subject_id: 'AFO', parent_topic_id: 'AFO_LOA', kind: 'SUBTOPIC', label: 'Princípios Orçamentários', aliases: [], status: 'ACTIVE', revision: 1, source_ids: ['BASE']}
    ],
    sources: [{source_id: 'BASE', kind: 'LEGACY_INTERNAL', reference: 'Base Cogniva', note: 'Catálogo canônico base'}],
    relations: [],
    compatibility: {
      legacy_subject_ids: ['AFO', 'DIR_TRIB'],
      legacy_topic_ids: ['TRIB_CRED', 'TRIB_BASE', 'AFO_LOA']
    }
  };
}

function sampleBaseData(exams = []) {
  return {
    base: {sha256: 'd'.repeat(64), activation: 'ADMIN_REVIEW', catalog: sampleCatalog()},
    exams,
    capabilities: {max_items: 50, ai_available: true},
    summary: {
      exams: exams.length,
      exam_templates: exams.length,
      catalog_version: 2,
      questions: 120,
      questions_v2: 120,
      questions_current: 100,
      questions_under_review: 15,
      questions_obsolete: 5,
      questions_unclassified: 8,
      pending_proposals: 3,
      questions_open_ended: 4,
      open_reports: 2
    }
  };
}

function sampleProvenance(rationale = 'Revisão humana verificada no contrato v2.1.') {
  return {
    actor: {type: 'HUMAN', id: '1'},
    method: 'MANUAL_REVIEW',
    rationale,
    recorded_at: '2026-01-01T00:00:00Z',
    evidence: [{
      uri: 'https://www.planalto.gov.br/ccivil_03/leis/l5172compilado.htm',
      title: 'Código Tributário Nacional',
      locator: 'Art. 142',
      retrieved_at: '2026-01-01T00:00:00Z',
      sha256: null
    }],
    model: null,
    prompt_id: null,
    prompt_version: null,
    source_suggestion_id: null,
    review_event_id: '22222222-2222-4222-8222-222222222222',
    trust_level: 'SERVER_VERIFIED'
  };
}

function sampleV2Question(id = '65f000000000000000000001', revision = 1, kind = 'MULTIPLE_CHOICE') {
  return {
    _id: {$oid: id},
    schema_version: 2,
    revision,
    content_hash_version: 'question-v2-jcs-sha256',
    content_revision: 'a'.repeat(64),
    semantic_hash_version: 'question-v2-semantic-jcs-sha256-v1',
    semantic_revision: 'b'.repeat(64),
    grading_hash_version: 'question-v2-grading-jcs-sha256-v1',
    grading_revision: 'c'.repeat(64),
    status: 'ACTIVE',
    question: `Enunciado original rev ${revision}`,
    question_type: kind,
    answers: kind === 'OPEN_ENDED' ? [] : kind === 'TRUE_FALSE' ? ['Certo', 'Errado'] : ['Opção A', 'Opção B', 'Opção C', 'Opção D', 'Opção E'],
    correct_answer_indices: kind === 'OPEN_ENDED' ? [] : [0],
    correct_answers: kind === 'OPEN_ENDED' ? [] : kind === 'TRUE_FALSE' ? ['Certo'] : ['Opção A'],
    origins: [{
      origin_id: '33333333-3333-4333-8333-333333333333',
      kind: 'OFFICIAL_EXAM',
      verification: 'CONFIRMED',
      source_exam_code: 'RFB-2025',
      institution: 'Receita Federal',
      organizer: 'CEBRASPE',
      exam_year: 2025,
      question_number: '14',
      source_reference: {
        uri: 'https://exemplo.gov.br/provas/rfb-2025.pdf',
        title: 'Caderno Oficial RFB 2025',
        locator: 'p. 8',
        retrieved_at: '2026-01-01T00:00:00Z',
        sha256: null
      },
      provenance: sampleProvenance('Origem oficial conferida com o caderno da prova.')
    }],
    classification: {
      revision: 1,
      catalog_version: 2,
      state: 'CONFIRMED',
      links: [{
        link_id: '44444444-4444-4444-8444-444444444444',
        subject_id: 'DIR_TRIB',
        topic_id: 'TRIB_LANC',
        role: 'PRIMARY',
        coverage: 'DIRECT',
        provenance: sampleProvenance('Classificação principal direta revisada manualmente.')
      }]
    },
    canonical_subject_id: 'DIR_TRIB',
    canonical_topic_id: 'TRIB_LANC',
    validity: {
      revision: 1,
      status: 'CURRENT',
      basis: 'LEGAL',
      reference_date: '2026-01-01',
      review_due_on: '2027-01-01',
      reason_codes: [],
      evaluated_content_revision: 'a'.repeat(64),
      evaluated_semantic_revision: 'b'.repeat(64),
      evaluation_mode: 'SEMANTIC',
      decision: sampleProvenance('Validade jurídica vigente conferida pelo administrador.'),
      scope: 'CURRENT_KNOWLEDGE',
      context_date: null
    },
    grading: kind === 'OPEN_ENDED'
      ? {
          strategy: 'RUBRIC',
          max_score: 100,
          pass_score: 60,
          expected_responses: ['Resposta padrão oficial'],
          rubric: {revision: 1, criteria: [{criterion_id: 'c1', description: 'Critério 1', max_points: 100, essential: true}]},
          engine: null,
          confirmation_policy: 'HUMAN_REVIEW',
          policy_version: 'open-rubric-human-review/1'
        }
      : {
          strategy: 'EXACT_OPTION',
          max_score: 100,
          pass_score: 60,
          expected_responses: [],
          rubric: null,
          engine: null,
          confirmation_policy: 'AUTOMATIC_EXACT',
          policy_version: 'objective-exact/1'
        }
  };
}

function setupEnv(fetchHandler = async () => ({ok: true, status: 200, json: async () => ({})})) {
  const doc = new FakeDocument();
  buildPortalShell(doc);
  const storage = new FakeStorage();
  const winListeners = new Map();
  const win = {
    scrollTo() {},
    addEventListener(type, fn) {
      const list = winListeners.get(type) || [];
      list.push(fn);
      winListeners.set(type, list);
    },
    dispatchEvent(type, ev = {}) {
      for (const fn of winListeners.get(type) || []) fn(ev);
    }
  };
  globalThis.document = doc;
  globalThis.window = win;
  globalThis.Node = FakeNode;
  globalThis.localStorage = storage;
  globalThis.URL.createObjectURL ||= () => 'blob:cogniva-test';
  globalThis.URL.revokeObjectURL ||= () => {};
  const calls = [];
  globalThis.fetch = async (url, options = {}) => {
    calls.push({url, options});
    return fetchHandler(url, options);
  };
  setAppStateForTest({
    current: 'overview',
    me: null,
    data: {},
    ws: {exams: {}, questionEdits: {}, archives: []},
    busy: false,
    storageBlocked: false,
    quotaExceeded: false,
    loadErrors: [],
    selectedExam: null,
    selectedQuestion: null,
    questionPage: null,
    questionCursor: null,
    questionCursorStack: [],
    proposalsData: null,
    proposalStatusFilter: 'PENDING',
    reportsData: null,
    reportStatusFilter: 'open',
    missionsAdminData: null
  });
  initApp({autoBootstrap: false});
  return {doc, win, storage, calls};
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

function findButton(root, labelSubstring) {
  return root.querySelectorAll('button').find(b => b.textContent.includes(labelSubstring)) || null;
}

function findFieldControl(root, labelText) {
  for (const lbl of root.querySelectorAll('label')) {
    if (lbl.textContent === labelText) {
      const id = lbl.getAttribute('for');
      if (id) return root.querySelector(`#${id}`);
    }
  }
  return null;
}

// --- 1. HTML/XSS Escaping & CSP Asset Compliance ---

test('DOM builder escapes untrusted HTML/XSS strings and codebase contains zero innerHTML or inline script/handler violations', () => {
  const {doc} = setupEnv();
  const payload = `<script>window.__xss=1</script><img src=x onerror="alert(1)">`;
  const cardNode = h('div', {class: 'card', 'data-note': payload}, h('h3', {}, payload), payload);

  assert.equal(cardNode.querySelectorAll('script').length, 0);
  assert.equal(cardNode.querySelectorAll('img').length, 0);
  const html = cardNode.serializeHtml();
  assert.match(html, /&lt;script&gt;window\.__xss=1&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script>/i);

  setAppStateForTest({
    me: {user_id: '1', profile: {display_name: payload}},
    data: sampleBaseData(),
    ws: {exams: {}, questionEdits: {}, archives: []},
    questionPage: {
      items: [{
        _id: {$oid: '65f000000000000000000099'},
        question: payload,
        question_type: 'MULTIPLE_CHOICE',
        source: payload,
        validity: {status: 'CURRENT'},
        classification: {state: 'CONFIRMED'}
      }],
      after: null
    }
  });
  navigate('questions');
  const view = doc.getElementById('view');
  assert.equal(view.querySelectorAll('script').length, 0);
  assert.equal(view.querySelectorAll('img').length, 0);
  assert.ok(view.textContent.includes(payload));

  const indexHtml = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  const scriptTags = [...indexHtml.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];
  assert.equal(scriptTags.length, 1, 'index.html must have exactly one external module script tag');
  assert.match(scriptTags[0][1], /src="\/admin\/assets\/app\.js"/);
  assert.equal(scriptTags[0][2].trim(), '');
  assert.doesNotMatch(indexHtml, /\son[a-z]+\s*=/i);
  assert.doesNotMatch(indexHtml, /\sstyle\s*=/i);

  for (const file of ['../app.js', '../domain.js', '../help.js', '../index.html', '../style.css']) {
    const content = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(content, /\b(innerHTML|outerHTML|insertAdjacentHTML|eval)\b/);
    assert.doesNotMatch(content, /Ã£|Ã§|Ã©|Ãª|Ã³|â†|\uFFFD/, `${file} must not contain UTF-8 mojibake`);
  }
});

// --- 2. WCAG 2.1 AA Contrast Extracted Directly from style.css (including .button-link) ---

function parseHexColor(hex) {
  const clean = hex.trim().replace(/^#/, '');
  if (clean.length === 3) {
    return clean.split('').map(c => parseInt(c + c, 16));
  }
  return [
    parseInt(clean.slice(0, 2), 16),
    parseInt(clean.slice(2, 4), 16),
    parseInt(clean.slice(4, 6), 16)
  ];
}

function relativeLuminance([r, g, b]) {
  const chan = v => {
    const s = v / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b);
}

function contrastRatio(fgHex, bgHex) {
  const l1 = relativeLuminance(parseHexColor(fgHex));
  const l2 = relativeLuminance(parseHexColor(bgHex));
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

function parseStyleSheetRules(cssText) {
  const cleaned = cssText.replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = new Map();
  let i = 0;

  const parseBlockList = text => {
    let pos = 0;
    while (pos < text.length) {
      while (pos < text.length && /\s/.test(text[pos])) pos++;
      if (pos >= text.length) break;
      const openIdx = text.indexOf('{', pos);
      if (openIdx < 0) break;
      const header = text.slice(pos, openIdx).trim();
      let depth = 1;
      let cursor = openIdx + 1;
      while (cursor < text.length && depth > 0) {
        if (text[cursor] === '{') depth++;
        else if (text[cursor] === '}') depth--;
        cursor++;
      }
      const body = text.slice(openIdx + 1, cursor - 1);
      if (header.startsWith('@media')) {
        parseBlockList(body);
      } else {
        const decls = {};
        for (const stmt of body.split(';')) {
          const colon = stmt.indexOf(':');
          if (colon > 0) {
            const prop = stmt.slice(0, colon).trim().toLowerCase();
            const val = stmt.slice(colon + 1).trim();
            decls[prop] = val;
          }
        }
        for (const rawSel of header.split(',')) {
          const normSel = rawSel.trim().replace(/\s+/g, ' ');
          if (!normSel) continue;
          rules.set(normSel, {...(rules.get(normSel) || {}), ...decls});
        }
      }
      pos = cursor;
    }
  };

  void i;
  parseBlockList(cleaned);
  return rules;
}

function resolveCssColor(rawValue, vars) {
  if (!rawValue) return null;
  let resolved = rawValue.replace(/!important/gi, '').trim();
  const varMatch = resolved.match(/^var\(\s*(--[a-z0-9-]+)\s*\)$/i);
  if (varMatch) resolved = vars[varMatch[1]] || '';
  const hexMatch = resolved.match(/^(#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6})$/);
  return hexMatch ? hexMatch[1] : null;
}

test('style.css enforces WCAG 2.1 AA (>= 4.5:1) across real extracted CSS rules including .button-link, font-size >= 11px, :focus-visible, and disabled cursor', () => {
  const css = readFileSync(new URL('../style.css', import.meta.url), 'utf8');
  const rules = parseStyleSheetRules(css);
  const rootDecls = rules.get(':root');
  assert.ok(rootDecls, ':root rule must exist in style.css');

  const vars = {};
  for (const [k, v] of Object.entries(rootDecls)) {
    if (k.startsWith('--')) vars[k] = v;
  }

  const rootFontPx = Number(rootDecls['font-size']?.match(/^(\d+(?:\.\d+)?)px$/)?.[1] || 0);
  assert.ok(rootFontPx >= 14, 'root font size must be at least 14px');

  const propColor = (selector, prop) => {
    const decl = rules.get(selector);
    assert.ok(decl, `Selector "${selector}" must exist in web-admin/style.css`);
    const raw = prop === 'background' ? (decl.background || decl['background-color']) : decl[prop];
    const hex = resolveCssColor(raw, vars);
    assert.ok(hex, `Selector "${selector}" property "${prop}" (${raw}) must resolve to a valid hex color`);
    return hex;
  };

  // Every foreground & background pair is extracted dynamically from style.css rules (zero hardcoded hexes)
  const extractedRulePairs = [
    [':root body text on :root bg', propColor(':root', 'color'), propColor(':root', 'background')],
    ['.card text (:root) on .card bg', propColor(':root', 'color'), propColor('.card', 'background')],
    ['p muted text on :root bg', propColor('p', 'color'), propColor(':root', 'background')],
    ['p muted text on .card bg', propColor('p', 'color'), propColor('.card', 'background')],
    ['.hero p on .hero bg', propColor('.hero p', 'color'), propColor('.hero', 'background')],
    ['button default', propColor('button', 'color'), propColor('button', 'background')],
    ['.button-link default', propColor('.button-link', 'color'), propColor('.button-link', 'background')],
    ['.button-link:hover', propColor('.button-link', 'color'), propColor('.button-link:hover', 'background')],
    ['button.primary', propColor('button.primary', 'color'), propColor('button.primary', 'background')],
    ['button.primary:hover', propColor('button.primary', 'color'), propColor('button.primary:hover', 'background')],
    ['button.danger on button bg', propColor('button.danger', 'color'), propColor('button', 'background')],
    ['.text-button on .topbar bg', propColor('.text-button', 'color'), propColor('.topbar', 'background')],
    ['a link on .card bg', propColor('a', 'color'), propColor('.card', 'background')],
    ['label on .card bg', propColor('label', 'color'), propColor('.card', 'background')],
    ['.field-error on .card bg', propColor('.field-error', 'color'), propColor('.card', 'background')],
    ['.eyebrow on .card bg', propColor('.eyebrow', 'color'), propColor('.card', 'background')],
    ['.skip-link', propColor('.skip-link', 'color'), propColor('.skip-link', 'background')],
    ['.tabs button.active', propColor('.tabs button.active', 'color'), propColor('.tabs button.active', 'background')],
    ['.badge default', propColor('.badge', 'color'), propColor('.badge', 'background')],
    ['.badge.good', propColor('.badge.good', 'color'), propColor('.badge.good', 'background')],
    ['.badge.warning', propColor('.badge.warning', 'color'), propColor('.badge.warning', 'background')],
    ['.badge.danger', propColor('.badge.danger', 'color'), propColor('.badge.danger', 'background')],
    ['th table header', propColor('th', 'color'), propColor('th', 'background')],
    ['.notice default', propColor('.notice', 'color'), propColor('.notice', 'background')],
    ['.notice.error', propColor('.notice.error', 'color'), propColor('.notice.error', 'background')],
    ['.notice.warning', propColor('.notice.warning', 'color'), propColor('.notice.warning', 'background')],
    ['.preparatory-banner', propColor('.preparatory-banner', 'color'), propColor('.preparatory-banner', 'background')],
    ['.card.conflict-banner p', propColor('.card.conflict-banner p', 'color'), propColor('.card.conflict-banner', 'background')],
    ['.card.partial-load-banner p', propColor('.card.partial-load-banner p', 'color'), propColor('.card.partial-load-banner', 'background')],
    ['.progress', propColor('.progress', 'color'), propColor('.progress', 'background')],
    ['.structure-guide > summary', propColor('.structure-guide > summary', 'color'), propColor('.structure-guide', 'background')],
    ['.help-note', propColor('.help-note', 'color'), propColor('.help-note', 'background')],
    ['.sidebar text on .sidebar bg', propColor('.sidebar', 'color'), propColor('.sidebar', 'background')],
    ['.nav-label on .sidebar bg', propColor('.nav-label', 'color'), propColor('.sidebar', 'background')],
    ['.sidebar nav button on .sidebar bg', propColor('.sidebar nav button', 'color'), propColor('.sidebar', 'background')],
    ['.sidebar nav button.active', propColor('.sidebar nav button.active', 'color'), propColor('.sidebar nav button.active', 'background')],
    ['.sidebar nav button:hover', propColor('.sidebar nav button:hover', 'color'), propColor('.sidebar nav button:hover', 'background')],
    ['.sidebar-close mobile button', propColor('.sidebar-close', 'color'), propColor('.sidebar-close', 'background')],
    ['.login-story .eyebrow on .login-story bg', propColor('.login-story .eyebrow', 'color'), propColor('.login-story', 'background')],
    ['.login-story p on .login-story bg', propColor('.login-story p', 'color'), propColor('.login-story', 'background')],
    ['.login-map span on .login-story bg', propColor('.login-map span', 'color'), propColor('.login-story', 'background')],
    ['.login-card #login-message on :root bg', propColor('.login-card #login-message', 'color'), propColor(':root', 'background')]
  ];

  for (const [label, fg, bg] of extractedRulePairs) {
    const ratio = contrastRatio(fg, bg);
    assert.ok(ratio >= 4.5, `${label} (${fg} on ${bg}) contrast ${ratio.toFixed(2)}:1 is below WCAG 2.1 AA 4.5:1`);
  }

  const fontSizes = [...css.matchAll(/font-size\s*:\s*([0-9.]+)(px|rem)/g)];
  assert.ok(fontSizes.length > 20);
  for (const [, rawNum, unit] of fontSizes) {
    const px = unit === 'rem' ? Number(rawNum) * rootFontPx : Number(rawNum);
    assert.ok(px >= 11 - 1e-6, `font-size ${rawNum}${unit} (${px.toFixed(2)}px) must be >= 11px`);
  }

  assert.match(css, /:focus-visible\s*\{[^}]*outline\s*:\s*2px\s+solid/i);
  assert.match(css, /button:disabled\s*\{[^}]*cursor\s*:\s*not-allowed/i);
  assert.match(css, /@media\s*\(\s*max-width\s*:\s*960px\s*\)/);
  assert.match(css, /\.sidebar-backdrop/);
});

// --- 3. Archive Non-Nesting, 20-Item Cap, QuotaExceededError & Local History Cleanup ---

test('archives never self-nest across export/restore cycles, cap at 20 items, handle QuotaExceededError gracefully, and support clearing local history', async () => {
  const ws = {exams: {}, questionEdits: {}, archives: []};
  for (let i = 0; i < 28; i++) {
    pushArchive(ws, `snapshot-${i}`, {step: i, archives: [{nested: true}]});
  }
  assert.equal(ws.archives.length, MAX_ARCHIVES);
  assert.equal(MAX_ARCHIVES, 20);
  assert.equal(ws.archives[0].type, 'snapshot-8');
  assert.equal(ws.archives[19].type, 'snapshot-27');
  assert.deepEqual(ws.archives[19].value.archives, []);

  let currentWs = ws;
  for (let cycle = 0; cycle < 8; cycle++) {
    const envelope = exportWorkspace('42', currentWs);
    const recovered = restoreWorkspace('42', JSON.parse(JSON.stringify(envelope)));
    pushArchive(recovered, 'workspace-backup', currentWs);
    currentWs = recovered;
  }
  assert.equal(currentWs.archives.length, 20);
  for (const entry of currentWs.archives) {
    if (entry.value && typeof entry.value === 'object' && 'archives' in entry.value) {
      assert.deepEqual(entry.value.archives, [], 'nested archives inside backup entry must always be empty');
    }
  }

  const {doc, storage} = setupEnv();
  setAppStateForTest({
    me: {user_id: '42', profile: {display_name: 'Admin'}},
    data: sampleBaseData(),
    ws: currentWs
  });
  storage.failWithQuota = true;
  const saved = persist();
  assert.equal(saved, false);
  assert.equal(shouldWarnBeforeUnload(), true);
  assert.equal(doc.getElementById('notice').hidden, false);
  assert.match(doc.getElementById('notice').textContent, /Armazenamento local cheio/i);

  storage.failWithQuota = false;
  navigate('drafts');
  const clearBtn = findButton(doc.getElementById('view'), 'Limpar histórico local');
  assert.ok(clearBtn);
  const clickPromise = clearBtn.click();
  await flush();
  const dlg = doc.getElementById('confirm-dialog');
  assert.equal(dlg.open, true);
  dlg.close('confirm');
  await clickPromise;
  await flush();
  assert.equal(getAppState().ws.archives.length, 0);
});

// --- 4. Question Draft Conflict Safety (Clean Silent Refresh vs Dirty Conflict Notice) ---

test('question draft reconciliation silently refreshes clean drafts and surfaces pt-BR conflict banner on dirty drafts when server revision advances', async () => {
  const qRev1 = sampleV2Question('65f000000000000000000001', 1);
  const qRev2 = {...sampleV2Question('65f000000000000000000001', 2), question: 'Enunciado atualizado no servidor rev 2'};

  let currentServerQuestion = qRev1;
  const {doc} = setupEnv(async url => {
    if (url.includes('/api/v1/admin/questions/v2/')) {
      return {ok: true, status: 200, json: async () => structuredClone(currentServerQuestion)};
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  setAppStateForTest({
    me: {user_id: '7', profile: {display_name: 'Admin'}},
    data: sampleBaseData(),
    ws: {exams: {}, questionEdits: {}, archives: []}
  });

  openQuestionEditor(qRev1._id.$oid);
  await flush();
  await flush();
  assert.equal(getAppState().ws.questionEdits[qRev1._id.$oid].original.revision, 1);

  currentServerQuestion = qRev2;
  openQuestionEditor(qRev1._id.$oid);
  await flush();
  await flush();
  assert.equal(getAppState().ws.questionEdits[qRev1._id.$oid].original.revision, 2);
  assert.equal(Boolean(getAppState().ws.questionEdits[qRev1._id.$oid].conflict), false);
  assert.match(getAppState().ws.questionEdits[qRev1._id.$oid].text, /atualizado no servidor rev 2/);

  const enunciadoField = findFieldControl(doc.getElementById('view'), 'Enunciado');
  enunciadoField.value = 'Minha edição local não salva';
  enunciadoField.dispatchEvent({type: 'input', bubbles: true});

  const qRev3 = {...sampleV2Question('65f000000000000000000001', 3), question: 'Versão servidor rev 3'};
  currentServerQuestion = qRev3;
  openQuestionEditor(qRev1._id.$oid);
  await flush();
  await flush();

  const dirtyDraft = getAppState().ws.questionEdits[qRev1._id.$oid];
  assert.equal(dirtyDraft.conflict, true);
  assert.match(dirtyDraft.text, /Minha edição local não salva/);

  const conflictBanner = doc.getElementById('view').querySelector('.conflict-banner');
  assert.ok(conflictBanner);
  assert.match(conflictBanner.textContent, /revisão 3/i);

  const reloadBtn = findButton(conflictBanner, 'Recarregar versão do servidor');
  reloadBtn.click();
  await flush();
  doc.getElementById('confirm-dialog').close('confirm');
  await flush();

  assert.equal(getAppState().ws.questionEdits[qRev1._id.$oid].original.revision, 3);
  assert.equal(getAppState().ws.archives.length, 1);
});

// --- 5. Session, CSRF, Logout Resilience, 401 Immediate Login Transition & Partial Load Retry (No Dead /catalog/trunks) ---

test('api attaches X-Cogniva-CSRF and same-origin credentials, never calls dead /catalog/trunks, recovers logout from 503, and transitions to login on 401', async () => {
  let failLogout = true;
  let failSummaryAndExams = true;
  let return401OnSummary = false;

  const {doc, storage, calls} = setupEnv(async (url) => {
    if (url === '/api/v1/auth/web-logout') {
      if (failLogout) return {ok: false, status: 503, json: async () => ({message: 'Bad Gateway'})};
      return {ok: true, status: 200, json: async () => ({authenticated: false, revoked: true})};
    }
    if (url === '/api/v1/admin/web/me') {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          user_id: '99',
          profile: {
            user_id: 99,
            email: 'coord@cogniva.local',
            display_name: 'Coordenação',
            role: 'admin',
            is_admin: true,
            is_guest: false
          },
          admin: true,
          catalog_activation: 'ADMIN_REVIEW'
        })
      };
    }
    if (url === '/api/v1/admin/catalog/base') {
      return {ok: true, status: 200, json: async () => ({sha256: 'd'.repeat(64), activation: 'ADMIN_REVIEW', catalog: sampleCatalog()})};
    }
    if (url === '/api/v1/admin/questions/import/capabilities') {
      return {ok: true, status: 200, json: async () => ({max_items: 50, ai_available: true})};
    }
    if (url === '/api/v1/admin/exam-templates') {
      if (failSummaryAndExams) return {ok: false, status: 502, json: async () => ({message: 'Upstream error'})};
      return {ok: true, status: 200, json: async () => ([{...blankExam('CPNU', 'fiscal'), title: 'Concurso Nacional', short_name: 'CPNU'}])};
    }
    if (url === '/api/v1/admin/summary') {
      if (return401OnSummary) return {ok: false, status: 401, json: async () => ({message: 'Sessão inválida'})};
      if (failSummaryAndExams) return {ok: false, status: 503, json: async () => ({message: 'Indisponível'})};
      return {ok: true, status: 200, json: async () => ({exam_templates: 1, questions: 50})};
    }
    return {ok: false, status: 404, json: async () => ({message: `Rota inesperada: ${url}`})};
  });

  await bootstrap();
  assert.equal(doc.getElementById('app').hidden, false);
  assert.equal(doc.getElementById('login').hidden, true);

  assert.equal(calls.some(c => c.url.includes('/catalog/trunks')), false, 'loadData() must not invoke dead GET /api/v1/catalog/trunks');
  assert.ok(calls.length >= 4);
  for (const call of calls) {
    assert.equal(call.options.credentials, 'same-origin');
    assert.equal(call.options.headers['X-Cogniva-CSRF'], '1');
  }

  const retryBanner = doc.getElementById('view').querySelector('.partial-load-banner');
  assert.ok(retryBanner);
  const retryBtn = findButton(retryBanner, 'Tentar novamente');

  failSummaryAndExams = false;
  retryBtn.click();
  await flush();
  await flush();
  assert.equal(getAppState().loadErrors.length, 0);
  assert.equal(doc.getElementById('view').querySelector('.partial-load-banner'), null);

  storage.failWithQuota = true;
  await performLogout();
  assert.equal(getAppState().me, null);
  assert.equal(doc.getElementById('app').hidden, true);
  assert.equal(doc.getElementById('login').hidden, false);
  assert.match(doc.getElementById('login-message').textContent, /Sessão encerrada/i);
  storage.failWithQuota = false;

  failLogout = false;
  await bootstrap();
  assert.equal(doc.getElementById('app').hidden, false);
  return401OnSummary = true;
  await assert.rejects(() => api('admin/summary'), {status: 401});
  assert.equal(getAppState().me, null);
  assert.equal(doc.getElementById('app').hidden, true);
  assert.equal(doc.getElementById('login').hidden, false);
  assert.match(doc.getElementById('login-message').textContent, /Sua sessão expirou/i);
});

// --- 6. Editor Hardening: OPEN_ENDED Rubric Validation, AI OperationId Rotation, NFD Search & Pagination ---

test('OPEN_ENDED rubric JSON validates in pt-BR, AI failures rotate aiOperationId, NFD search finds accented records, and pagination supports back/next', async () => {
  const form = freshImport('op1', 'ai1').form;
  form.kind = 'OPEN_ENDED';
  form.question = 'Disserte sobre o orçamento.';
  form.reference = 'Resposta oficial.';
  form.criteria = '{malformed json';
  assert.throws(() => objectiveDraft(form), /JSON inválido na rubrica/);
  assert.throws(() => parseOpenEndedGradingJson('{bad'), /JSON inválido na rubrica/);

  assert.equal(normalizeSearchText('Orçamento Público e Tributário'), 'orcamento publico e tributario');
  assert.equal(matchesSearch('Orçamento Público — Receita Federal', 'orcamento publico'), true);
  assert.equal(matchesSearch('Direito Tributário', 'tributario'), true);

  let aiAttempt = 0;
  const seenAiOperationIds = [];
  const {doc} = setupEnv(async (url, options) => {
    if (url === '/api/v1/admin/questions/import/assist') {
      const body = JSON.parse(options.body);
      seenAiOperationIds.push(body.operation_id);
      aiAttempt++;
      if (aiAttempt === 1) {
        return {ok: false, status: 502, json: async () => ({message: 'Falha temporária no provedor xAI'})};
      }
      return {ok: true, status: 200, json: async () => ({assistance_id: 'ast-1', items: [{question: 'Gerada'}]})};
    }
    if (url.startsWith('/api/v1/admin/questions?')) {
      const u = new URL('http://localhost' + url);
      const after = u.searchParams.get('after');
      if (!after) {
        return {ok: true, status: 200, json: async () => ({items: [sampleV2Question('65f000000000000000000010', 1)], after: '65f000000000000000000010'})};
      }
      return {ok: true, status: 200, json: async () => ({items: [sampleV2Question('65f000000000000000000009', 1)], after: null})};
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  const openQ = sampleV2Question('65f000000000000000000055', 1, 'OPEN_ENDED');
  const imp = freshImport('op-imp', 'ai-fixed-initial');
  imp.mode = 'bulk';
  imp.source = 'Material bruto de prova';

  setAppStateForTest({
    me: {user_id: '5', profile: {display_name: 'Admin'}},
    data: sampleBaseData([{...blankExam('AFO_1', 'fiscal'), title: 'Orçamento Público Federal', short_name: 'SOF'}]),
    ws: {
      exams: {},
      questionEdits: {[openQ._id.$oid]: {original: structuredClone(openQ), text: JSON.stringify(openQ, null, 2), operationId: 'op-edit', preview: null, submitted: false, result: null}},
      import: imp,
      archives: []
    },
    selectedQuestion: openQ._id.$oid
  });

  navigate('question');
  const rubricField = findFieldControl(doc.getElementById('view'), 'Correção e rubrica (JSON)');
  assert.ok(rubricField);
  rubricField.value = JSON.stringify({
    ...openQ.grading,
    rubric: {revision: 1, criteria: [{criterion_id: 'c1', description: 'Sem booleano essential', max_points: 100}]}
  });
  rubricField.dispatchEvent({type: 'input', bubbles: true});
  assert.match(doc.getElementById('notice').textContent, /essential/);

  findButton(doc.getElementById('view'), 'Validar edição').click();
  await flush();
  await flush();
  assert.match(doc.getElementById('notice').textContent, /essential/);

  navigate('exams');
  const searchInput = doc.getElementById('view').querySelector('input[type="search"]');
  searchInput.value = 'orcamento';
  searchInput.dispatchEvent({type: 'input', bubbles: true});
  const rows = doc.getElementById('view').querySelectorAll('tbody tr');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].hidden, false);

  navigate('import');
  findButton(doc.getElementById('view'), 'Preparar com IA').click();
  await flush();
  await flush();
  assert.notEqual(getAppState().ws.import.aiOperationId, 'ai-fixed-initial');

  findButton(doc.getElementById('view'), 'Preparar com IA').click();
  await flush();
  await flush();
  assert.equal(seenAiOperationIds.length, 2);
  assert.notEqual(seenAiOperationIds[0], seenAiOperationIds[1]);
  assert.equal(getAppState().ws.import.assistanceId, 'ast-1');

  navigate('questions');
  findButton(doc.getElementById('view'), 'Carregar questões').click();
  await flush();
  await flush();
  let prevBtn = findButton(doc.getElementById('view'), '← Página anterior');
  let nextBtn = findButton(doc.getElementById('view'), 'Próxima página →');
  assert.equal(prevBtn.disabled, true);
  assert.equal(nextBtn.disabled, false);

  nextBtn.click();
  await flush();
  await flush();
  prevBtn = findButton(doc.getElementById('view'), '← Página anterior');
  nextBtn = findButton(doc.getElementById('view'), 'Próxima página →');
  assert.equal(prevBtn.disabled, false);
  assert.equal(nextBtn.disabled, true);

  prevBtn.click();
  await flush();
  await flush();
  assert.equal(getAppState().questionCursorStack.length, 0);
});

// --- 7. Mobile Drawer, Accessible <dialog>, <details> & Focus/Selection Preservation ---

test('mobile drawer closes on button/backdrop/Escape, dialog guards showModal and closes on backdrop/Escape, and render preserves <details> and input focus', async () => {
  const indexHtml = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  assert.match(indexHtml, /<dialog[^>]*id="confirm-dialog"[^>]*aria-labelledby="confirm-title"/);
  assert.match(indexHtml, /id="menu-close"/);
  assert.match(indexHtml, /class="sidebar-backdrop"/);

  const exam = {
    ...blankExam('RFB_2026', 'fiscal'),
    title: 'Receita Federal',
    short_name: 'RFB',
    subjects: [newComponent({subject_id: 'DIR_TRIB', label: 'Direito Tributário'}, 'comp-1')]
  };
  const {doc} = setupEnv();
  setAppStateForTest({
    me: {user_id: '11', profile: {display_name: 'Admin'}},
    data: sampleBaseData([exam]),
    ws: {exams: {}, questionEdits: {}, archives: []}
  });

  const toggle = doc.getElementById('menu-toggle');
  const app = doc.getElementById('app');
  const backdrop = doc.getElementById('sidebar-backdrop');

  toggle.click();
  assert.equal(app.classList.contains('menu-open'), true);
  assert.equal(toggle.getAttribute('aria-expanded'), 'true');
  assert.equal(backdrop.hidden, false);

  doc.getElementById('menu-close').click();
  assert.equal(app.classList.contains('menu-open'), false);
  assert.equal(backdrop.hidden, true);

  toggle.click();
  backdrop.click();
  assert.equal(app.classList.contains('menu-open'), false);

  toggle.click();
  doc.dispatchEvent({type: 'keydown', key: 'Escape', bubbles: true});
  assert.equal(app.classList.contains('menu-open'), false);

  const dlg = doc.getElementById('confirm-dialog');
  const p1 = confirm('Primeira confirmação', 'Texto 1');
  assert.equal(dlg.open, true);
  const p2 = confirm('Segunda confirmação', 'Texto 2');
  assert.equal(await p1, false);
  assert.equal(dlg.open, true);
  dlg.dispatchEvent({type: 'click', target: dlg, bubbles: true});
  assert.equal(await p2, false);
  assert.equal(dlg.open, false);

  const p3 = confirm('Terceira confirmação', 'Texto 3');
  doc.dispatchEvent({type: 'keydown', key: 'Escape', bubbles: true});
  assert.equal(await p3, false);
  assert.equal(dlg.open, false);

  openExam(exam);
  let view = doc.getElementById('view');
  const guide = view.querySelector('details.structure-guide');
  guide.open = true;
  guide.dispatchEvent({type: 'toggle', bubbles: false});

  const customInput = findFieldControl(view, 'Assunto personalizado');
  customInput.value = 'Subtópico Especial';
  customInput.dispatchEvent({type: 'input', bubbles: true});
  customInput.focus();
  customInput.setSelectionRange(3, 9);

  render();
  view = doc.getElementById('view');
  assert.equal(view.querySelector('details.structure-guide').open, true);
  const restoredInput = findFieldControl(view, 'Assunto personalizado');
  assert.equal(restoredInput.value, 'Subtópico Especial');
  assert.equal(doc.activeElement, restoredInput);
  assert.equal(restoredInput.selectionStart, 3);
  assert.equal(restoredInput.selectionEnd, 9);
});

// --- 8. Help Center LGPD Art. 18 & Exact Byte-for-Byte Parity with docs/GUIA-ADMINISTRACAO.md ---

test('help center includes LGPD Art. 18 privacy and AI governance guidance and matches docs/GUIA-ADMINISTRACAO.md byte-for-byte', () => {
  const found = searchHelp('LGPD Art. 18 DELETE todasQuestoes prompt injection cota');
  assert.equal(found.length, 1);
  assert.equal(found[0].id, 'privacidade-lgpd-ia');
  const fullText = [found[0].title, found[0].intro, ...found[0].steps, ...found[0].notes].join(' ');
  for (const term of ['DELETE /api/v1/auth/account', 'todasQuestoes', 'question_revision_events', 'knowledge_catalog', 'exam_templates']) {
    assert.ok(fullText.includes(term), `help topic must mention ${term}`);
  }

  const expectedGuide =
    '# Ajuda do portal de administração\n\n' +
    'Conteúdo compartilhado com a seção Ajuda do portal, disponível no menu lateral e em Ajuda desta tela. Fonte: `web-admin/help.js`. Para atualizar este arquivo, execute `node scripts/export-admin-help.mjs --write`.\n\n' +
    helpTopics.map(topic => (
      `## ${topic.title}\n\n${topic.intro}\n\n` +
      `${topic.steps.map((step, index) => `${index + 1}. ${step}`).join('\n')}\n\n` +
      `${topic.notes.join('\n\n')}\n\nNo portal: **${topic.action}**.\n`
    )).join('\n');

  const guideMd = readFileSync(new URL('../../docs/GUIA-ADMINISTRACAO.md', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
  assert.equal(guideMd, expectedGuide, 'docs/GUIA-ADMINISTRACAO.md must match helpTopics byte-for-byte (normalized EOL)');
  assert.equal(helpTopics.length >= 17, true);
});

// --- 9. V2.1 Contract Hardening: Batch Idempotency, Duplicate Warnings & Semantic vs Grading Preview ---

test('import commit forwards V2.1 idempotency_key + payload_digest + duplicate acknowledgement, and questionView distinguishes grading-only from semantic changes', async () => {
  let capturedCommitBody = null;
  const cmdDoc = sampleV2Question('65f000000000000000000088', 1);
  const cmd = {operation_id: 'op-v21-1', expected_revision: 0, dry_run: false, document: cmdDoc};

  const {doc} = setupEnv(async (url, options) => {
    if (url === '/api/v1/admin/questions/import/commit') {
      capturedCommitBody = JSON.parse(options.body);
      return {
        ok: true,
        status: 200,
        json: async () => ({
          complete: true,
          idempotent_replay: true,
          items: [{index: 0, operation_id: 'op-v21-1', id: {$oid: cmdDoc._id.$oid}, saved: true, receipt: {status: 'already_applied', revision: 1}}]
        })
      };
    }
    if (url === '/api/v1/admin/summary') {
      return {ok: true, status: 200, json: async () => ({questions: 121})};
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  const imp = freshImport('op-batch', 'ai-batch');
  imp.preview = {
    ready: true,
    idempotency_key: '11111111-1111-4111-8111-111111111111',
    payload_digest: 'c'.repeat(64),
    duplicate_warnings: [{index: 0, code: 'POTENTIAL_DUPLICATE', kind: 'EXACT_CONTENT', message: 'Conteúdo canônico idêntico detectado no acervo.'}],
    items: [{
      index: 0,
      valid: true,
      command: cmd,
      duplicate_warning: {index: 0, code: 'POTENTIAL_DUPLICATE', kind: 'EXACT_CONTENT', message: 'Conteúdo canônico idêntico detectado no acervo.'}
    }]
  };

  const editQ = sampleV2Question('65f000000000000000000077', 1, 'OPEN_ENDED');
  setAppStateForTest({
    me: {user_id: '12', profile: {display_name: 'Admin'}},
    data: sampleBaseData(),
    ws: {
      exams: {},
      import: imp,
      questionEdits: {
        [editQ._id.$oid]: {
          original: structuredClone(editQ),
          text: JSON.stringify(editQ, null, 2),
          operationId: 'op-edit-77',
          preview: {
            content_changed: true,
            semantic_changed: false,
            command: {operation_id: 'op-edit-77', expected_revision: 1, dry_run: false, document: editQ}
          },
          submitted: false,
          result: null
        }
      },
      archives: []
    },
    selectedQuestion: editQ._id.$oid
  });

  navigate('import');
  let view = doc.getElementById('view');
  assert.match(view.textContent, /Possível duplicidade/);
  assert.match(view.textContent, /Conteúdo canônico idêntico detectado no acervo/);

  const reviewCheck = view.querySelectorAll('input[type="checkbox"]').at(-1);
  reviewCheck.checked = true;
  reviewCheck.dispatchEvent({type: 'change', bubbles: true});

  findButton(view, 'Confirmar inserção').click();
  await flush();
  await flush();
  assert.ok(capturedCommitBody);
  assert.equal(capturedCommitBody.idempotency_key, '11111111-1111-4111-8111-111111111111');
  assert.equal(capturedCommitBody.payload_digest, 'c'.repeat(64));
  assert.equal(capturedCommitBody.acknowledge_duplicates, true);
  assert.equal(capturedCommitBody.reviewed, true);

  navigate('question');
  view = doc.getElementById('view');
  assert.match(view.textContent, /Correção alterada \(semântica preservada\)/);
});

// --- 10. [P0] AI Classification Queue: proposed_links, ALL/STALE Filters, Batch OpId Rotation & Decisions ---

test('classificationView reads proposed_links, queries status=ALL and STALE, rotates classificationAiOpId, validates audit reason >= 10 chars, and executes accept/edit_accept/defer/reject', async () => {
  const proposalQueries = [];
  const batchOpIds = [];
  const decisionsSent = [];
  let failBatchOnce = true;

  const makeSuggestion = (id, status = 'PENDING') => ({
    _id: id,
    schema_version: 1,
    question_id: '65f000000000000000000042',
    question_content_revision: 'a'.repeat(64),
    question_semantic_revision: 'b'.repeat(64),
    expected_classification_revision: 1,
    current_content_revision: 'a'.repeat(64),
    current_classification_revision: 1,
    catalog_version: 2,
    status,
    question_snippet: 'Enunciado sobre lançamento por homologação do crédito tributário.',
    disciplina: 'Direito Tributário',
    proposed_links: [{
      subject_id: 'DIR_TRIB',
      topic_id: 'TRIB_LANC',
      role: 'PRIMARY',
      coverage: 'DIRECT',
      rationale: 'O enunciado cobra diretamente as modalidades de lançamento no CTN.',
      confidence: 0.92
    }],
    provenance: {
      actor: {type: 'AI', id: 'grok-4'},
      method: 'AI_SUGGESTION',
      rationale: 'Proposta automática pendente de revisão humana.',
      recorded_at: '2026-11-23T09:00:00Z',
      evidence: [],
      model: 'grok-4',
      prompt_id: 'classify-question',
      prompt_version: '3',
      source_suggestion_id: null,
      review_event_id: null,
      trust_level: null
    },
    resolution: status === 'REJECTED'
      ? {decided_at: '2026-11-23T09:30:00Z', actor: {type: 'HUMAN', id: '99'}, reason: 'Fora do escopo.', resulting_classification_revision: null}
      : null
  });

  const {doc} = setupEnv(async (url, options) => {
    if (url.startsWith('/api/v1/admin/classification/proposals?')) {
      const parsedUrl = new URL('http://localhost' + url);
      proposalQueries.push(parsedUrl.searchParams.get('status'));
      return {
        ok: true,
        status: 200,
        json: async () => ({
          items: [makeSuggestion('55555555-5555-4555-8555-555555555555', 'PENDING')],
          catalog_version: 2,
          unclassified_count: 1,
          unclassified_candidates: [{id: '65f000000000000000000042', disciplina: 'Direito Tributário', snippet: 'Questão sem tópico'}]
        })
      };
    }
    if (url === '/api/v1/admin/classification/proposals' && options.method === 'POST') {
      const body = JSON.parse(options.body);
      batchOpIds.push(body.operation_id);
      if (failBatchOnce) {
        failBatchOnce = false;
        return {ok: false, status: 502, json: async () => ({message: 'xAI indisponível temporariamente'})};
      }
      return {ok: true, status: 200, json: async () => ({status: 'completed', items: []})};
    }
    if (url === '/api/v1/admin/classification/decisions' && options.method === 'POST') {
      const body = JSON.parse(options.body);
      decisionsSent.push(body);
      return {ok: true, status: 200, json: async () => ({status: 'applied', suggestion_id: body.suggestion_id})};
    }
    if (url === '/api/v1/admin/summary') {
      return {ok: true, status: 200, json: async () => sampleBaseData().summary};
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  setAppStateForTest({
    me: {user_id: '99', profile: {display_name: 'Coordenação'}},
    data: sampleBaseData(),
    ws: {exams: {}, questionEdits: {}, archives: [], classificationAiOpId: '00000000-0000-4000-8000-000000000001'}
  });

  navigate('classification');
  let view = doc.getElementById('view');

  // Verify filter options strictly match schema (ALL, PENDING, ACCEPTED, REJECTED, STALE, SUPERSEDED)
  const statusSelect = view.querySelector('select');
  const statusValues = statusSelect.querySelectorAll('option').map(o => o.value);
  assert.deepEqual(statusValues, ['PENDING', 'ACCEPTED', 'REJECTED', 'STALE', 'SUPERSEDED', 'ALL']);
  assert.equal(statusValues.includes('EDITED_ACCEPTED'), false);
  assert.equal(statusValues.includes('DEFERRED'), false);

  // Load initial PENDING queue -> renders proposed_links (Direito Tributário -> Crédito Tributário → Lançamento, 92%)
  findButton(view, 'Carregar fila de classificação').click();
  await flush();
  await flush();
  view = doc.getElementById('view');
  assert.equal(proposalQueries.at(-1), 'PENDING');
  assert.match(view.textContent, /Sugestão principal: Direito Tributário → Crédito Tributário → Lançamento/);
  assert.match(view.textContent, /Confiança IA: 92%/);
  assert.match(view.textContent, /modalidades de lançamento no CTN/);

  // Filter "Todas" -> sends status=ALL; Filter "STALE" -> sends status=STALE
  let currentSelect = view.querySelector('select');
  currentSelect.value = 'ALL';
  currentSelect.dispatchEvent({type: 'change', bubbles: true});
  await flush();
  await flush();
  assert.equal(proposalQueries.at(-1), 'ALL');

  view = doc.getElementById('view');
  currentSelect = view.querySelector('select');
  currentSelect.value = 'STALE';
  currentSelect.dispatchEvent({type: 'change', bubbles: true});
  await flush();
  await flush();
  assert.equal(proposalQueries.at(-1), 'STALE');

  // Batch generation rotates classificationAiOpId on failure (502) and on retry success (200)
  view = doc.getElementById('view');
  findButton(view, 'Gerar propostas com IA').click();
  await flush();
  await flush();
  assert.equal(batchOpIds.length, 1);
  assert.equal(batchOpIds[0], '00000000-0000-4000-8000-000000000001');
  assert.notEqual(getAppState().ws.classificationAiOpId, '00000000-0000-4000-8000-000000000001');

  view = doc.getElementById('view');
  findButton(view, 'Gerar propostas com IA').click();
  await flush();
  await flush();
  assert.equal(batchOpIds.length, 2);
  assert.notEqual(batchOpIds[0], batchOpIds[1]);

  // Audit reason < 10 chars blocks submission with pt-BR error
  view = doc.getElementById('view');
  let reasonInput = findFieldControl(view, 'Motivo da decisão (auditoria, mín. 10 caracteres)');
  reasonInput.value = 'curto';
  reasonInput.dispatchEvent({type: 'input', bubbles: true});
  findButton(view, 'Aceitar proposta').click();
  await flush();
  assert.equal(decisionsSent.length, 0);
  assert.match(doc.getElementById('notice').textContent, /pelo menos 10 caracteres/);

  // 1) accept
  reasonInput.value = 'Proposta conferida e aceita integralmente.';
  reasonInput.dispatchEvent({type: 'input', bubbles: true});
  findButton(view, 'Aceitar proposta').click();
  await flush();
  await flush();
  assert.equal(decisionsSent.at(-1).action, 'accept');
  assert.equal(decisionsSent.at(-1).edited_links, undefined);

  // 2) edit_accept (switching discipline clears topic -> guard requires topic -> choosing AFO_LOA_SUB sends edited_links)
  view = doc.getElementById('view');
  const subjSelect = findFieldControl(view, 'Disciplina');
  subjSelect.value = 'AFO';
  subjSelect.dispatchEvent({type: 'change', bubbles: true});
  findButton(view, 'Salvar vínculo ajustado (Editar e aceitar)').click();
  await flush();
  assert.match(doc.getElementById('notice').textContent, /Selecione disciplina e assunto/);

  const topicSelect = view.querySelector('[data-focus-key="proposal:55555555-5555-4555-8555-555555555555:topic"]');
  topicSelect.value = 'AFO_LOA_SUB';
  topicSelect.dispatchEvent({type: 'change', bubbles: true});
  findButton(view, 'Salvar vínculo ajustado (Editar e aceitar)').click();
  await flush();
  await flush();
  assert.equal(decisionsSent.at(-1).action, 'edit_accept');
  assert.equal(decisionsSent.at(-1).edited_links[0].subject_id, 'AFO');
  assert.equal(decisionsSent.at(-1).edited_links[0].topic_id, 'AFO_LOA_SUB');

  // 3) defer & 4) reject
  view = doc.getElementById('view');
  findButton(view, 'Adiar').click();
  await flush();
  await flush();
  assert.equal(decisionsSent.at(-1).action, 'defer');

  view = doc.getElementById('view');
  findButton(view, 'Rejeitar').click();
  await flush();
  await flush();
  assert.equal(decisionsSent.at(-1).action, 'reject');
});

// --- 11. [P0/P1] questionView: editorial_card Error/Preview/Dirty/Sync, Validity & PARTIAL Classification ---

test('questionView invalidates preview and disables Confirmar edição on editorial_card error, preserves dirty editorial draft on conflict, syncs reference_date/LEGAL/UNKNOWN, and sets PARTIAL classification', async () => {
  const qId = '65f000000000000000000061';
  const baseQ = sampleV2Question(qId, 1, 'MULTIPLE_CHOICE');
  baseQ.classification.links.push({
    link_id: '66666666-6666-4666-8666-666666666666',
    subject_id: 'AFO',
    topic_id: 'AFO_LOA_SUB',
    role: 'SECONDARY',
    coverage: 'CONTEXTUAL',
    provenance: sampleProvenance('Vínculo secundário contextual preservado.')
  });

  let serverQuestion = structuredClone(baseQ);
  let lastPreparedPayload = null;
  let lastPreparedBody = null;

  const {doc, storage} = setupEnv(async (url, options) => {
    if (url.includes(`/admin/questions/v2/${qId}`)) {
      return {ok: true, status: 200, json: async () => structuredClone(serverQuestion)};
    }
    if (url.includes(`/admin/questions/${qId}/prepare-edit`) && options.method === 'POST') {
      const body = JSON.parse(options.body);
      lastPreparedBody = body;
      lastPreparedPayload = body.document;
      return {
        ok: true,
        status: 200,
        json: async () => ({
          content_changed: false,
          semantic_changed: false,
          command: {operation_id: body.operation_id, expected_revision: body.expected_revision, dry_run: false, document: body.document}
        })
      };
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  setAppStateForTest({
    me: {user_id: '99', profile: {display_name: 'Admin'}},
    data: sampleBaseData(),
    ws: {exams: {}, questionEdits: {}, archives: []}
  });

  openQuestionEditor(qId);
  await flush();
  await flush();
  let view = doc.getElementById('view');

  // Generate preview and check review checkbox -> "Confirmar edição" becomes enabled
  findButton(view, 'Validar edição').click();
  await flush();
  await flush();
  view = doc.getElementById('view');
  const reviewCheckbox = view.querySelectorAll('input[type="checkbox"]').at(-1);
  reviewCheckbox.checked = true;
  reviewCheckbox.dispatchEvent({type: 'change', bubbles: true});
  const confirmBtn = findButton(view, 'Confirmar edição');
  assert.ok(confirmBtn);
  assert.equal(confirmBtn.disabled, false);

  // Introduce incomplete editorial_card (testedRule without decisiveStemExcerpt):
  // 1) inline .field-error appears, 2) draft.preview becomes null, 3) Confirmar edição immediately disables!
  const ruleField = findFieldControl(view, 'Regra cobrada');
  ruleField.value = 'O lançamento tributário compete privativamente à autoridade administrativa.';
  ruleField.dispatchEvent({type: 'input', bubbles: true});

  const draft = getAppState().ws.questionEdits[qId];
  assert.match(draft.editorialError, /trecho decisivo/);
  assert.equal(draft.preview, null, 'Invalid editorial_card must invalidate draft.preview');
  assert.equal(confirmBtn.disabled, true, 'Invalid editorial_card must immediately disable Confirmar edição');
  assert.equal(isQuestionDraftDirty(draft), true, 'Draft with editorialError must be recognized as dirty');
  const inlineErrors = view.querySelectorAll('.field-error').filter(n => !n.hidden);
  assert.equal(inlineErrors.length, 1);

  // Server advances to rev 2 while draft only has unsaved editorialError -> must preserve draft and surface conflict!
  serverQuestion = {...structuredClone(baseQ), revision: 2};
  openQuestionEditor(qId);
  await flush();
  await flush();
  assert.equal(getAppState().ws.questionEdits[qId].conflict, true);
  assert.equal(getAppState().ws.questionEdits[qId].editorialDraft.testedRule, 'O lançamento tributário compete privativamente à autoridade administrativa.');

  // Clicking "Atualizar formulário a partir do JSON" clears editorialDraft & editorialError
  view = doc.getElementById('view');
  findButton(view, 'Atualizar formulário a partir do JSON').click();
  assert.equal(getAppState().ws.questionEdits[qId].editorialError, null);
  assert.equal(getAppState().ws.questionEdits[qId].editorialDraft, undefined);

  // Fill complete editorial_card -> writes editorial_card into draft.text
  view = doc.getElementById('view');
  findFieldControl(view, 'Dispositivos legais / normativos envolvidos (1 por linha)').value = 'CTN, art. 142';
  findFieldControl(view, 'Dispositivos legais / normativos envolvidos (1 por linha)').dispatchEvent({type: 'input', bubbles: true});
  findFieldControl(view, 'Regra cobrada').value = 'Competência privativa da autoridade fiscal.';
  findFieldControl(view, 'Regra cobrada').dispatchEvent({type: 'input', bubbles: true});
  findFieldControl(view, 'Trecho decisivo do enunciado').value = 'compete privativamente constituir o crédito';
  findFieldControl(view, 'Trecho decisivo do enunciado').dispatchEvent({type: 'input', bubbles: true});
  assert.equal(JSON.parse(getAppState().ws.questionEdits[qId].text).editorial_card.tested_rule, 'Competência privativa da autoridade fiscal.');

  // Validity: immediate reference_date sync, LEGAL vs FACTUAL label redraw, UNKNOWN cleanup, and reconfirmValidity
  const refDateField = findFieldControl(view, 'Data de referência (YYYY-MM-DD)');
  assert.ok(refDateField);
  assert.ok(findFieldControl(view, 'Próxima revisão obrigatória (YYYY-MM-DD)'), 'LEGAL basis must require mandatory review date label');
  refDateField.value = '2026-08-20';
  refDateField.dispatchEvent({type: 'change', bubbles: true});
  assert.equal(JSON.parse(getAppState().ws.questionEdits[qId].text).validity.reference_date, '2026-08-20');
  assert.match(storage.getItem('cogniva-admin:99'), /2026-08-20/);

  const basisSelect = findFieldControl(view, 'Base da avaliação');
  basisSelect.value = 'FACTUAL';
  basisSelect.dispatchEvent({type: 'change', bubbles: true});
  view = doc.getElementById('view');
  assert.ok(findFieldControl(view, 'Próxima revisão prevista (opcional)'));

  const statusSelect = findFieldControl(view, 'Estado de validade');
  statusSelect.value = 'UNKNOWN';
  statusSelect.dispatchEvent({type: 'change', bubbles: true});
  const afterUnknown = JSON.parse(getAppState().ws.questionEdits[qId].text).validity;
  assert.equal(afterUnknown.status, 'UNKNOWN');
  assert.equal(afterUnknown.reference_date, null);
  assert.equal(afterUnknown.review_due_on, null);
  assert.equal(afterUnknown.decision, null);
  assert.deepEqual(afterUnknown.reason_codes, []);

  view = doc.getElementById('view');
  findFieldControl(view, 'Estado de validade').value = 'CURRENT';
  findFieldControl(view, 'Estado de validade').dispatchEvent({type: 'change', bubbles: true});
  assert.match(JSON.parse(getAppState().ws.questionEdits[qId].text).validity.reference_date, /^\d{4}-\d{2}-\d{2}$/);

  // Toggle reconfirmValidity checkbox -> clears evaluated_content_revision when checked and restores original when unchecked
  view = doc.getElementById('view');
  const reconfirmBox = view.querySelectorAll('input[type="checkbox"]').find(
    cb => cb.parentElement?.textContent.includes('Reconfirmar explicitamente o estado de validade')
  );
  reconfirmBox.checked = true;
  reconfirmBox.dispatchEvent({type: 'change', bubbles: true});
  assert.equal(getAppState().ws.questionEdits[qId].reconfirmValidity, true);
  assert.equal(JSON.parse(getAppState().ws.questionEdits[qId].text).validity.evaluated_content_revision, null);

  reconfirmBox.checked = false;
  reconfirmBox.dispatchEvent({type: 'change', bubbles: true});
  assert.equal(getAppState().ws.questionEdits[qId].reconfirmValidity, false);
  assert.equal(
    JSON.parse(getAppState().ws.questionEdits[qId].text).validity.evaluated_content_revision,
    baseQ.validity.evaluated_content_revision
  );

  reconfirmBox.checked = true;
  reconfirmBox.dispatchEvent({type: 'change', bubbles: true});

  // Removing primary classification while keeping secondary link sets state === 'PARTIAL' (never 'PROPOSED')
  const discSelect = findFieldControl(view, 'Disciplina da questão');
  discSelect.value = '';
  discSelect.dispatchEvent({type: 'change', bubbles: true});
  view = doc.getElementById('view');
  const applyClassBox = view.querySelectorAll('input[type="checkbox"]').find(
    cb => cb.parentElement?.textContent.includes('Aplicar e confirmar a classificação principal')
  );
  applyClassBox.checked = true;
  applyClassBox.dispatchEvent({type: 'change', bubbles: true});

  const updatedDoc = JSON.parse(getAppState().ws.questionEdits[qId].text);
  assert.equal(updatedDoc.classification.state, 'PARTIAL');
  assert.equal(updatedDoc.classification.links.length, 1);
  assert.equal(updatedDoc.classification.links[0].role, 'SECONDARY');

  // Validate sends updated payload + explicit reconfirm_validity cleanly
  getAppState().ws.questionEdits[qId].original.revision = 2;
  findButton(view, 'Validar edição').click();
  await flush();
  await flush();
  assert.equal(lastPreparedPayload.classification.state, 'PARTIAL');
  assert.equal(lastPreparedBody.reconfirm_validity, true);
});

// --- 12. [P1] reportsView Moderation: open / resolved / all Filters & Individual vs Batch Resolve ---

test('reportsView filters open/resolved/all and resolves individual vs all open reports for a question', async () => {
  const reportStatusesQueried = [];
  const resolvedPayloads = [];

  const {doc} = setupEnv(async (url, options) => {
    if (url.startsWith('/api/v1/admin/reports?')) {
      const u = new URL('http://localhost' + url);
      const st = u.searchParams.get('status');
      reportStatusesQueried.push(st);
      return {
        ok: true,
        status: 200,
        json: async () => ({
          items: st === 'resolved'
            ? [{
                report_id: '65f000000000000000000902',
                question_id: '65f000000000000000000001',
                user_id: '301',
                problemas: ['Explicação Incompleta'],
                status_solved: true,
                resolution_note: 'Complementado gabarito comentado.',
                reported_at: '2026-11-20T12:00:00Z',
                question_snippet: 'Questão já tratada.',
                disciplina: 'Direito Tributário',
                validity_status: 'CURRENT'
              }]
            : [{
                report_id: '65f000000000000000000901',
                question_id: '65f000000000000000000001',
                user_id: '300',
                problemas: ['Questão com Erros de Conteúdo'],
                status_solved: false,
                resolution_note: null,
                reported_at: '2026-11-22T15:00:00Z',
                question_snippet: 'Questão sinalizada pelo aluno.',
                disciplina: 'Direito Tributário',
                validity_status: 'UNDER_REVIEW'
              }]
        })
      };
    }
    if (url.includes('/api/v1/admin/reports/') && url.endsWith('/resolve') && options.method === 'POST') {
      resolvedPayloads.push({url, body: JSON.parse(options.body)});
      return {ok: true, status: 200, json: async () => ({status: 'resolved', resolved_count: 1})};
    }
    if (url === '/api/v1/admin/summary') {
      return {ok: true, status: 200, json: async () => sampleBaseData().summary};
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  setAppStateForTest({
    me: {user_id: '99', profile: {display_name: 'Admin'}},
    data: sampleBaseData(),
    ws: {exams: {}, questionEdits: {}, archives: []}
  });

  navigate('reports');
  let view = doc.getElementById('view');
  findButton(view, 'Carregar reportes').click();
  await flush();
  await flush();
  assert.equal(reportStatusesQueried.at(-1), 'open');

  view = doc.getElementById('view');
  const noteInput = findFieldControl(view, 'Nota de resolução');
  noteInput.value = 'Conferido com a Lei Complementar vigente.';
  noteInput.dispatchEvent({type: 'input', bubbles: true});

  // Resolve single report
  findButton(view, 'Resolver este reporte').click();
  await flush();
  await flush();
  assert.deepEqual(resolvedPayloads.at(-1).body, {
    resolution_note: 'Conferido com a Lei Complementar vigente.',
    resolve_all_for_question: false
  });

  // Resolve all open reports for the question
  view = doc.getElementById('view');
  findButton(view, 'Resolver todos os abertos desta questão').click();
  await flush();
  await flush();
  assert.equal(resolvedPayloads.at(-1).body.resolve_all_for_question, true);

  // Switch filter to resolved and all
  view = doc.getElementById('view');
  let filterSelect = view.querySelector('select');
  filterSelect.value = 'resolved';
  filterSelect.dispatchEvent({type: 'change', bubbles: true});
  await flush();
  await flush();
  assert.equal(reportStatusesQueried.at(-1), 'resolved');
  assert.match(doc.getElementById('view').textContent, /Nota: Complementado gabarito comentado/);

  view = doc.getElementById('view');
  filterSelect = view.querySelector('select');
  filterSelect.value = 'all';
  filterSelect.dispatchEvent({type: 'change', bubbles: true});
  await flush();
  await flush();
  assert.equal(reportStatusesQueried.at(-1), 'all');
});

// --- 13. [P1] catalogView & entityView: v+1 Proposal, Rename Alias, REVIEW_READY Gate, Activation & 409 Recovery ---

test('catalogView creates v+1 draft, renames entity preserving alias, blocks activation before REVIEW_READY, and recovers 409 via /activations/:op', async () => {
  let activeCatalogVersion = 2;
  let failActivate409 = true;
  const activationChecks = [];

  const {doc} = setupEnv(async (url, options) => {
    if (url === '/api/v1/admin/catalog/validate' && options.method === 'POST') {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          valid: true,
          catalog_sha256: 'e'.repeat(64),
          activation: 'ADMIN_REVIEW',
          counts: {trunks: 2, subjects: 2, topics: 7},
          registry: {registry_version: 2, entries: []}
        })
      };
    }
    if (url === '/api/v1/admin/catalog/drafts' && options.method === 'POST') {
      const body = JSON.parse(options.body);
      return {
        ok: true,
        status: 200,
        json: async () => ({
          draft: {
            draft_id: body.draft_id,
            revision: body.expected_revision + 1,
            status: body.status,
            base_catalog_sha256: body.base_catalog_sha256,
            catalog: body.catalog
          },
          validation: {valid: true}
        })
      };
    }
    if (url === '/api/v1/admin/catalog/activate' && options.method === 'POST') {
      const body = JSON.parse(options.body);
      if (failActivate409) {
        failActivate409 = false;
        return {ok: false, status: 409, json: async () => ({message: 'Conflito temporário de ativação'})};
      }
      activeCatalogVersion = 3;
      return {
        ok: true,
        status: 200,
        json: async () => ({
          status: 'applied',
          operation_id: body.operation_id,
          activated_version: 3
        })
      };
    }
    if (url.startsWith('/api/v1/admin/catalog/activations/')) {
      activationChecks.push(url);
      return {ok: true, status: 200, json: async () => ({status: 'absent', current_version: 2, current_hash: 'd'.repeat(64)})};
    }
    if (url === '/api/v1/admin/catalog/base') {
      const cat = sampleCatalog();
      cat.catalog_version = activeCatalogVersion;
      return {ok: true, status: 200, json: async () => ({sha256: activeCatalogVersion === 3 ? 'f'.repeat(64) : 'd'.repeat(64), catalog: cat})};
    }
    if (url === '/api/v1/admin/exam-templates') {
      return {ok: true, status: 200, json: async () => ([])};
    }
    if (url === '/api/v1/admin/questions/import/capabilities') {
      return {ok: true, status: 200, json: async () => ({ai_available: true})};
    }
    if (url === '/api/v1/admin/summary') {
      return {ok: true, status: 200, json: async () => ({...sampleBaseData().summary, catalog_version: activeCatalogVersion})};
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  setAppStateForTest({
    me: {user_id: '99', profile: {display_name: 'Admin'}},
    data: sampleBaseData(),
    ws: {exams: {}, questionEdits: {}, archives: []}
  });

  // Start catalog proposal from trunksView -> creates v3 (v+1)
  navigate('trunks');
  findButton(doc.getElementById('view'), 'Propor alteração no catálogo').click();
  assert.equal(getAppState().ws.catalog.catalog.catalog_version, 3);
  assert.ok(getAppState().ws.catalog.catalog.sources.some(s => s.source_id === 'WEB_EDITOR'));

  // Rename published subject "Orçamento Público" -> preserves old name in aliases & bumps revision to 2
  navigate('subjects');
  findButton(doc.getElementById('view'), 'Editar proposta de disciplina').click();
  let view = doc.getElementById('view');
  const nameInput = findFieldControl(view, 'Nome');
  nameInput.value = 'Orçamento e Finanças Públicas';
  nameInput.dispatchEvent({type: 'input', bubbles: true});
  findButton(view, 'Guardar alteração na proposta').click();

  const editedAfo = getAppState().ws.catalog.catalog.subjects.find(s => s.subject_id === 'AFO');
  assert.equal(editedAfo.label, 'Orçamento e Finanças Públicas');
  assert.ok(editedAfo.aliases.includes('Orçamento Público'));
  assert.equal(editedAfo.revision, 2);

  // Add a new subtopic and verify duplicate ID protection
  navigate('subjects');
  findButton(doc.getElementById('view'), 'Adicionar assunto na proposta').click();
  view = doc.getElementById('view');
  findFieldControl(view, 'ID estável').value = 'AFO_LOA'; // collides with existing ID
  findFieldControl(view, 'ID estável').dispatchEvent({type: 'input', bubbles: true});
  findFieldControl(view, 'Nome').value = 'Subassunto Colidente';
  findFieldControl(view, 'Nome').dispatchEvent({type: 'input', bubbles: true});
  findButton(view, 'Guardar alteração na proposta').click();
  assert.match(doc.getElementById('notice').textContent, /Este ID já existe na proposta/);

  findFieldControl(view, 'ID estável').value = 'AFO_LDO_SUB';
  findFieldControl(view, 'ID estável').dispatchEvent({type: 'input', bubbles: true});
  findFieldControl(view, 'Assunto pai (vazio para assunto de primeiro nível)').value = 'AFO_LOA';
  findFieldControl(view, 'Assunto pai (vazio para assunto de primeiro nível)').dispatchEvent({type: 'change', bubbles: true});
  findFieldControl(view, 'Nome').value = 'Metas e Prioridades da LDO';
  findFieldControl(view, 'Nome').dispatchEvent({type: 'input', bubbles: true});
  findButton(view, 'Guardar alteração na proposta').click();
  assert.equal(getAppState().current, 'catalog');

  // Before REVIEW_READY, checking activation box keeps "Ativar catálogo global" disabled
  view = doc.getElementById('view');
  let actCheck = view.querySelector('input[type="checkbox"]');
  actCheck.checked = true;
  actCheck.dispatchEvent({type: 'change', bubbles: true});
  assert.equal(findButton(view, 'Ativar catálogo global').disabled, true);

  // Validate and save as REVIEW_READY
  findButton(view, 'Validar proposta').click();
  await flush();
  await flush();
  view = doc.getElementById('view');
  findButton(view, 'Salvar como pronta para revisão').click();
  await flush();
  await flush();
  assert.equal(getAppState().ws.catalog.status, 'REVIEW_READY');
  assert.equal(getAppState().ws.catalog.dirty, false);

  // First activation hits 409 -> queries /activations/:op -> status 'absent' archives command & clears activationPending
  view = doc.getElementById('view');
  findFieldControl(view, 'Motivo da ativação').value = 'Inclusão de subassunto de LDO e atualização de AFO.';
  findFieldControl(view, 'Motivo da ativação').dispatchEvent({type: 'input', bubbles: true});
  actCheck = view.querySelector('input[type="checkbox"]');
  actCheck.checked = true;
  actCheck.dispatchEvent({type: 'change', bubbles: true});
  const actBtn = findButton(view, 'Ativar catálogo global');
  assert.equal(actBtn.disabled, false);
  actBtn.click();
  await flush();
  await flush();

  assert.equal(activationChecks.length, 1);
  assert.equal(getAppState().ws.catalog.activationPending, null);
  assert.equal(getAppState().ws.archives.at(-1).type, 'catalog-activation-rejected');

  // Retry activation succeeds -> activates v3 and reloads data.base.catalog v3
  view = doc.getElementById('view');
  actCheck = view.querySelector('input[type="checkbox"]');
  actCheck.checked = true;
  actCheck.dispatchEvent({type: 'change', bubbles: true});
  findButton(view, 'Ativar catálogo global').click();
  await flush();
  await flush();
  await flush();

  assert.equal(getAppState().ws.catalog.activationResult.activated_version, 3);
  assert.equal(getAppState().data.base.catalog.catalog_version, 3);
});

// --- 14. [P1] examView: _id Collision Guard, Edital Publication & 409 Conflict Reconciliation ---

test('examView blocks _id collision with existing templates, publishes edital, and reconciles 409 server conflict', async () => {
  const existingRfb = {...blankExam('RFB_2026', 'fiscal'), revision: 2, short_name: 'RFB', title: 'Receita Federal'};
  let currentTemplates = [existingRfb];
  let publishedPayload = null;
  let force409OnPut = false;

  const {doc} = setupEnv(async (url, options) => {
    if (url === '/api/v1/admin/exam-templates' && (!options.method || options.method === 'GET')) {
      return {ok: true, status: 200, json: async () => structuredClone(currentTemplates)};
    }
    if (url === '/api/v1/admin/exam-templates' && options.method === 'POST') {
      const created = {...JSON.parse(options.body), revision: 1};
      currentTemplates = [...currentTemplates, created];
      return {ok: true, status: 200, json: async () => structuredClone(created)};
    }
    if (url === '/api/v1/admin/exam-templates/BCB_2026/publish-edital' && options.method === 'POST') {
      publishedPayload = JSON.parse(options.body);
      const updated = {
        ...currentTemplates.find(e => e._id === 'BCB_2026'),
        status: 'EDITAL_PUBLICADO',
        revision: 2
      };
      currentTemplates = currentTemplates.map(e => (e._id === 'BCB_2026' ? updated : e));
      return {ok: true, status: 200, json: async () => structuredClone(updated)};
    }
    if (url === '/api/v1/admin/exam-templates/BCB_2026' && options.method === 'PUT') {
      if (force409OnPut) {
        currentTemplates = currentTemplates.map(e => (e._id === 'BCB_2026' ? {...e, revision: 5, title: 'BCB Edital Retificado v5'} : e));
        return {ok: false, status: 409, json: async () => ({message: 'Template alterado em outra sessão'})};
      }
      const saved = {...JSON.parse(options.body), revision: 3};
      return {ok: true, status: 200, json: async () => saved};
    }
    if (url === '/api/v1/admin/summary') {
      return {ok: true, status: 200, json: async () => sampleBaseData(currentTemplates).summary};
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  setAppStateForTest({
    me: {user_id: '99', profile: {display_name: 'Admin'}},
    data: sampleBaseData(currentTemplates),
    ws: {
      exams: {RFB_2026: {document: structuredClone(existingRfb), original: structuredClone(existingRfb), isNew: false, pending: null}},
      questionEdits: {},
      archives: []
    }
  });

  // Create new exam template and attempt to collide with existing RFB_2026
  openExam(null, 'fiscal');
  const initialGeneratedId = getAppState().selectedExam;
  let view = doc.getElementById('view');
  const idInput = findFieldControl(view, 'ID do template');
  idInput.value = 'RFB_2026';
  idInput.dispatchEvent({type: 'input', bubbles: true});

  assert.match(doc.getElementById('notice').textContent, /Já existe um template com o ID "RFB_2026"/);
  assert.equal(getAppState().selectedExam, initialGeneratedId);
  assert.equal(getAppState().ws.exams[initialGeneratedId].document._id, initialGeneratedId);
  assert.equal(getAppState().ws.exams.RFB_2026.document.short_name, 'RFB');

  // Rekey cleanly to BCB_2026
  idInput.value = 'BCB_2026';
  idInput.dispatchEvent({type: 'input', bubbles: true});
  assert.equal(getAppState().selectedExam, 'BCB_2026');
  assert.equal('BCB_2026' in getAppState().ws.exams, true);
  assert.equal(initialGeneratedId in getAppState().ws.exams, false);

  findFieldControl(view, 'Nome curto').value = 'BCB';
  findFieldControl(view, 'Nome curto').dispatchEvent({type: 'input', bubbles: true});
  findFieldControl(view, 'Nome completo').value = 'Banco Central do Brasil';
  findFieldControl(view, 'Nome completo').dispatchEvent({type: 'input', bubbles: true});

  // Add component + catalog topic
  findButton(view, '＋ Adicionar componente').click();
  view = doc.getElementById('view');
  const topicSel = findFieldControl(view, 'Adicionar assunto / subassunto');
  topicSel.value = 'AFO_LOA_SUB';
  topicSel.dispatchEvent({type: 'change', bubbles: true});
  findButton(view, 'Adicionar assunto').click();

  // Save new template (POST -> revision 1)
  view = doc.getElementById('view');
  findButton(view, 'Salvar concurso e grade').click();
  await flush();
  await flush();
  assert.equal(getAppState().ws.exams.BCB_2026.document.revision, 1);
  assert.equal(getAppState().ws.exams.BCB_2026.isNew, false);

  // Publish edital (POST /publish-edital -> revision 2)
  view = doc.getElementById('view');
  findButton(view, 'Publicar esta grade como edital').click();
  await flush();
  doc.getElementById('confirm-dialog').close('confirm');
  await flush();
  await flush();
  assert.ok(publishedPayload);
  assert.equal(publishedPayload.expected_revision, 1);
  assert.equal(publishedPayload.published_topics.length, 1);
  assert.equal(publishedPayload.published_topics[0].canonical_topic_id, 'AFO_LOA_SUB');
  assert.equal(getAppState().ws.exams.BCB_2026.document.revision, 2);

  // Trigger 409 conflict on PUT -> archives rejected command, renders conflict banner, reloads server rev 5
  force409OnPut = true;
  view = doc.getElementById('view');
  findFieldControl(view, 'Nome completo').value = 'Edição concorrente local';
  findFieldControl(view, 'Nome completo').dispatchEvent({type: 'input', bubbles: true});
  findButton(view, 'Salvar concurso e grade').click();
  await flush();
  await flush();

  assert.equal(getAppState().ws.exams.BCB_2026.conflict, true);
  assert.equal(getAppState().ws.exams.BCB_2026.server.revision, 5);
  assert.equal(getAppState().ws.archives.at(-1).type, 'rejected-exam-command');

  view = doc.getElementById('view');
  const conflictBanner = view.querySelector('.conflict-banner');
  assert.ok(conflictBanner);
  findButton(conflictBanner, 'Recarregar versão do servidor').click();
  await flush();
  doc.getElementById('confirm-dialog').close('confirm');
  await flush();
  assert.equal(getAppState().ws.exams.BCB_2026.document.revision, 5);
});

// --- 15. [P1/P2] #login-form (Register + Login), missionsView, Legacy Question Conversion & Help View ---

test('#login-form handles register/login with password clearing, sidebar opens missionsView inventory, and questionView converts legacy v1 records', async () => {
  const authCalls = [];

  const {doc, win} = setupEnv(async (url, options) => {
    if (url === '/api/v1/auth/register' && options.method === 'POST') {
      authCalls.push({url, body: JSON.parse(options.body)});
      return {ok: false, status: 403, json: async () => ({message: 'Apenas e-mails autorizados em ADMIN_EMAILS'})};
    }
    if (url === '/api/v1/auth/login' && options.method === 'POST') {
      authCalls.push({url, body: JSON.parse(options.body)});
      return {ok: true, status: 200, json: async () => ({authenticated: true, user_id: '99'})};
    }
    if (url === '/api/v1/admin/web/me') {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          user_id: '99',
          profile: {user_id: 99, email: 'admin@cogniva.local', display_name: 'Admin', role: 'admin', is_admin: true, is_guest: false},
          admin: true,
          catalog_activation: 'ADMIN_REVIEW'
        })
      };
    }
    if (url === '/api/v1/admin/catalog/base') {
      return {ok: true, status: 200, json: async () => ({sha256: 'd'.repeat(64), activation: 'ADMIN_REVIEW', catalog: sampleCatalog()})};
    }
    if (url === '/api/v1/admin/exam-templates') {
      return {ok: true, status: 200, json: async () => ([])};
    }
    if (url === '/api/v1/admin/questions/import/capabilities') {
      return {ok: true, status: 200, json: async () => ({ai_available: true})};
    }
    if (url === '/api/v1/admin/summary') {
      return {ok: true, status: 200, json: async () => sampleBaseData().summary};
    }
    if (url === '/api/v1/admin/missions') {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          catalog_version: 1,
          missions: [{
            mission_id: 'bcb-ef-t1-m01-choque-dos-juros',
            version: 1,
            career_header: 'Banco Central — Economia e Finanças',
            season_title: 'Temporada 1: Mercado Financeiro',
            full_title: 'Missão 01: O Choque dos Juros',
            discipline: 'Finanças',
            canonical_subject_id: 'MFIN',
            scenes_count: 5,
            interactions_count: 2,
            publication_state: 'PUBLISHED',
            duration: {display_label: '~3 min'}
          }],
          media_assets: [
            {
              asset_id: 'asset-bcb-m01-v01-alerta',
              relative_file_name: 'v01-alerta.mp4',
              resolution: '1280x720',
              aspect_ratio: '16:9',
              duration_seconds: 9.6,
              video_codec: 'h264',
              audio_codec: 'aac',
              sha256: '1'.repeat(64),
              available_on_server: true
            },
            {
              asset_id: 'asset-bcb-m01-v02-problema',
              relative_file_name: 'v02-problema.mp4',
              resolution: '1280x720',
              aspect_ratio: '16:9',
              duration_seconds: 9.6,
              video_codec: 'h264',
              audio_codec: 'aac',
              sha256: '2'.repeat(64),
              available_on_server: false
            }
          ]
        })
      };
    }
    if (url === '/api/v1/admin/questions/v2/65f000000000000000000099') {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          _id: {$oid: '65f000000000000000000099'},
          question: 'Questão legada v1 para normalização',
          answers: ['Certo', 'Errado'],
          correct_answers: ['Certo'],
          canonical_topic_id: 'TRIB_LANC'
        })
      };
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  // First-access register tab -> 403 clears password & shows message
  doc.getElementById('auth-tab-register').click();
  assert.equal(doc.getElementById('login-name-field').hidden, false);
  doc.getElementById('login-display-name').value = 'Coordenação';
  doc.getElementById('login-email').value = 'nao-autorizado@cogniva.local';
  doc.getElementById('login-password').value = 'SenhaForte123!';
  doc.getElementById('login-submit').click();
  await flush();
  await flush();
  assert.equal(authCalls[0].url, '/api/v1/auth/register');
  assert.equal(authCalls[0].body.client, 'web');
  assert.equal(authCalls[0].body.display_name, 'Coordenação');
  assert.equal(doc.getElementById('login-password').value, '');
  assert.match(doc.getElementById('login-message').textContent, /ADMIN_EMAILS/);

  // Switch back to login tab -> authenticates and bootstraps portal
  doc.getElementById('auth-tab-login').click();
  doc.getElementById('login-email').value = 'admin@cogniva.local';
  doc.getElementById('login-password').value = 'SenhaAdmin123!';
  doc.getElementById('login-submit').click();
  await flush();
  await flush();
  await flush();
  assert.equal(authCalls[1].url, '/api/v1/auth/login');
  assert.equal(doc.getElementById('app').hidden, false);

  // Online / offline connection badge listeners
  win.dispatchEvent('offline');
  assert.match(doc.getElementById('connection').textContent, /Offline/);
  win.dispatchEvent('online');
  assert.match(doc.getElementById('connection').textContent, /Conectado/);

  // Sidebar [data-page="missions"] opens missionsView and loads inventory + video badges
  const missionsNavBtn = doc.querySelector('[data-page="missions"]');
  assert.ok(missionsNavBtn, 'Sidebar must render [data-page="missions"]');
  missionsNavBtn.click();
  let view = doc.getElementById('view');
  const studentPortalLink = view.querySelector('a.button-link');
  assert.ok(studentPortalLink);
  assert.equal(studentPortalLink.getAttribute('href'), '/missoes/');

  findButton(view, 'Atualizar Missões').click();
  await flush();
  await flush();
  view = doc.getElementById('view');
  assert.match(view.textContent, /Missões publicadas \(1\)/);
  assert.match(view.textContent, /Missão 01: O Choque dos Juros/);
  assert.match(view.textContent, /Disponível no servidor/);
  assert.match(view.textContent, /Modo transcrição/);

  // Help button from missionsView opens helpView focused on 'missoes-interativas'
  findButton(view, 'Ajuda desta tela').click();
  view = doc.getElementById('view');
  assert.equal(doc.getElementById('help-missoes-interativas').open, true);
  const helpSearch = view.querySelector('input[type="search"]');
  helpSearch.value = 'inexistente-xyz-999';
  helpSearch.dispatchEvent({type: 'input', bubbles: true});
  assert.match(view.textContent, /Nenhum tópico encontrado/);

  // Legacy v1 question conversion in questionView
  openQuestionEditor('65f000000000000000000099');
  await flush();
  await flush();
  view = doc.getElementById('view');
  assert.match(view.textContent, /Normalização de contrato v2/);
  findButton(view, 'Preparar edição v2').click();
  view = doc.getElementById('view');
  const converted = JSON.parse(getAppState().ws.questionEdits['65f000000000000000000099'].text);
  assert.equal(converted.schema_version, 2);
  assert.equal(converted.classification.state, 'UNCLASSIFIED');
});

// --- 16. Question Commit Lifecycle, Raw JSON Editorial Reset, Single Import, Exam Tombstone & Activation Hash Recovery ---

test('questionView commits v2 edits and clears editorialError on raw JSON edit, importView validates single mode, examView deletes tombstone, and catalogView recovers stale activation hash', async () => {
  const qId = '65f000000000000000000070';
  let serverQ = sampleV2Question(qId, 1, 'TRUE_FALSE');
  let singlePreviewBody = null;
  let tombstoneBody = null;

  const {doc, storage} = setupEnv(async (url, options) => {
    if (url === '/api/v1/admin/web/me') {
      return {ok: true, status: 200, json: async () => ({user_id: '42', profile: {display_name: 'Admin'}, admin: true, catalog_activation: 'ADMIN_REVIEW'})};
    }
    if (url === '/api/v1/admin/catalog/base') {
      return {ok: true, status: 200, json: async () => ({sha256: 'new-server-hash-999', activation: 'ADMIN_REVIEW', catalog: {...sampleCatalog(), catalog_version: 4}})};
    }
    if (url === '/api/v1/admin/exam-templates' && (!options.method || options.method === 'GET')) {
      return {ok: true, status: 200, json: async () => ([])};
    }
    if (url === '/api/v1/admin/exam-templates/OLD_EXAM' && options.method === 'PUT') {
      tombstoneBody = JSON.parse(options.body);
      return {ok: true, status: 200, json: async () => ({...tombstoneBody, status: 'EXCLUIDO', revision: 2})};
    }
    if (url === '/api/v1/admin/questions/import/capabilities') {
      return {ok: true, status: 200, json: async () => ({ai_available: false})};
    }
    if (url === '/api/v1/admin/summary') {
      return {ok: true, status: 200, json: async () => sampleBaseData().summary};
    }
    if (url.includes(`/admin/questions/v2/${qId}`)) {
      return {ok: true, status: 200, json: async () => structuredClone(serverQ)};
    }
    if (url === '/api/v1/admin/questions/v2' && options.method === 'POST') {
      const cmd = JSON.parse(options.body);
      serverQ = {...cmd.document, revision: 2};
      return {ok: true, status: 200, json: async () => ({status: 'applied', revision: 2})};
    }
    if (url === '/api/v1/admin/questions/import/preview' && options.method === 'POST') {
      singlePreviewBody = JSON.parse(options.body);
      return {
        ok: true,
        status: 200,
        json: async () => ({
          ready: true,
          items: [{index: 0, valid: true, command: {operation_id: 'op-single', expected_revision: 0, dry_run: false, document: sampleV2Question('65f000000000000000000071', 1, 'TRUE_FALSE')}}]
        })
      };
    }
    if (url === '/api/v1/admin/catalog/activations/op-stale-hash') {
      return {ok: true, status: 200, json: async () => ({status: 'pending_not_found', current_version: 4, current_hash: 'new-server-hash-999'})};
    }
    if (url === '/api/v1/admin/catalog/history') {
      return {ok: true, status: 200, json: async () => ({versions: [{version: 4, reason: 'Revisão anterior'}]})};
    }
    return {ok: true, status: 200, json: async () => ({})};
  });

  // Corrupt localStorage safeguards workspace with storageBlocked recovery screen
  storage.setItem('cogniva-admin:42', '{corrupted-json');
  await bootstrap();
  let view = doc.getElementById('view');
  assert.equal(getAppState().storageBlocked, true);
  assert.match(view.textContent, /O rascunho local está ilegível e foi preservado/);

  const validRecoveryEnvelope = JSON.stringify(exportWorkspace('42', {exams: {}, questionEdits: {}, archives: []}));
  const fileInput = view.querySelector('input[type="file"]');
  fileInput.files = [{size: validRecoveryEnvelope.length, text: async () => validRecoveryEnvelope}];
  const restorePromise = fileInput.dispatchEvent({type: 'change', target: fileInput});
  void restorePromise;
  await flush();
  await flush();
  doc.getElementById('confirm-dialog').close('confirm');
  await flush();
  assert.equal(getAppState().storageBlocked, false);

  // Raw JSON editing clears editorialDraft & editorialError, and questionView commits POST /admin/questions/v2
  openQuestionEditor(qId);
  await flush();
  await flush();
  view = doc.getElementById('view');
  findFieldControl(view, 'Regra cobrada').value = 'Regra sem trecho';
  findFieldControl(view, 'Regra cobrada').dispatchEvent({type: 'input', bubbles: true});
  assert.ok(getAppState().ws.questionEdits[qId].editorialError);

  const rawJsonField = findFieldControl(view, 'Documento JSON');
  rawJsonField.value = JSON.stringify(serverQ, null, 2);
  rawJsonField.dispatchEvent({type: 'input', bubbles: true});
  assert.equal(getAppState().ws.questionEdits[qId].editorialError, null);
  assert.equal(getAppState().ws.questionEdits[qId].editorialDraft, undefined);

  getAppState().ws.questionEdits[qId].preview = {
    content_changed: true,
    semantic_changed: true,
    command: {operation_id: 'op-commit-70', expected_revision: 1, dry_run: false, document: serverQ}
  };
  render();
  view = doc.getElementById('view');
  const revCheck = view.querySelectorAll('input[type="checkbox"]').at(-1);
  revCheck.checked = true;
  revCheck.dispatchEvent({type: 'change', bubbles: true});
  findButton(view, 'Confirmar edição').click();
  await flush();
  await flush();
  assert.equal(getAppState().ws.questionEdits[qId].result.revision, 2);

  view = doc.getElementById('view');
  findButton(view, 'Carregar revisão atual').click();
  await flush();
  await flush();
  assert.equal(getAppState().ws.questionEdits[qId].original.revision, 2);

  // Single-mode importView validates and previews TRUE_FALSE item, then resets via "Iniciar novo cadastro"
  navigate('import');
  view = doc.getElementById('view');
  findFieldControl(view, 'Tipo').value = 'TRUE_FALSE';
  findFieldControl(view, 'Tipo').dispatchEvent({type: 'change', bubbles: true});
  view = doc.getElementById('view');
  findFieldControl(view, 'Enunciado').value = 'O lançamento tributário é ato vinculado.';
  findFieldControl(view, 'Enunciado').dispatchEvent({type: 'input', bubbles: true});
  findFieldControl(view, 'Gabarito').value = '0';
  findFieldControl(view, 'Gabarito').dispatchEvent({type: 'change', bubbles: true});
  findButton(view, 'Validar e revisar questões').click();
  await flush();
  await flush();
  assert.equal(singlePreviewBody.items.length, 1);
  assert.equal(singlePreviewBody.items[0].question_type, 'TRUE_FALSE');

  view = doc.getElementById('view');
  findButton(view, 'Iniciar novo cadastro').click();
  await flush();
  doc.getElementById('confirm-dialog').close('confirm');
  await flush();
  assert.equal(getAppState().ws.import.preview, null);

  // examView EXCLUIDO tombstone removes exam from local workspace
  const oldExam = {...blankExam('OLD_EXAM', 'fiscal'), revision: 1, short_name: 'OLD', title: 'Concurso Antigo'};
  openExam(oldExam);
  view = doc.getElementById('view');
  findButton(view, 'Excluir template').click();
  await flush();
  doc.getElementById('confirm-dialog').close('confirm');
  await flush();
  await flush();
  assert.equal(tombstoneBody.status, 'EXCLUIDO');
  assert.equal('OLD_EXAM' in getAppState().ws.exams, false);
  assert.equal(getAppState().current, 'exams');

  // catalogView recovers when activationPending points to superseded base hash
  getAppState().ws.catalog = {
    draft_id: '77777777-7777-4777-8777-777777777777',
    revision: 1,
    base_catalog_sha256: 'old-hash-000',
    status: 'REVIEW_READY',
    catalog: {...sampleCatalog(), catalog_version: 5},
    activationPending: {
      operation_id: 'op-stale-hash',
      draft_id: '77777777-7777-4777-8777-777777777777',
      expected_draft_revision: 1,
      expected_catalog_hash: 'old-hash-000',
      reason: 'Revisão concorrente.',
      reviewed: true
    }
  };
  navigate('catalog');
  view = doc.getElementById('view');
  findButton(view, 'Consultar histórico de ativações').click();
  await flush();
  await flush();
  view = doc.getElementById('view');
  findButton(view, 'Conferir situação da ativação').click();
  await flush();
  await flush();
  view = doc.getElementById('view');
  const preserveBtn = findButton(view, 'Preservar comando não aplicado e iniciar nova revisão');
  assert.ok(preserveBtn);
  preserveBtn.click();
  await flush();
  doc.getElementById('confirm-dialog').close('confirm');
  await flush();
  assert.equal(getAppState().ws.archives.at(-1).type, 'catalog-activation-not-applied');
  assert.equal(getAppState().ws.catalog.catalog.catalog_version, 5);
  assert.equal(getAppState().ws.catalog.activationPending, undefined);

  // ctvView renders interactive Reuse Economy Simulator and updates outputs on slider input
  navigate('ctv');
  view = doc.getElementById('view');
  const verifSlider = doc.getElementById('ctv-slider-verifications');
  const reuseSlider = doc.getElementById('ctv-slider-reuse');
  const costSlider = doc.getElementById('ctv-slider-cost');
  assert.ok(verifSlider && reuseSlider && costSlider);
  verifSlider.value = '20000';
  verifSlider.dispatchEvent({type: 'input', target: verifSlider});
  reuseSlider.value = '80';
  reuseSlider.dispatchEvent({type: 'input', target: reuseSlider});
  costSlider.value = '0.05';
  costSlider.dispatchEvent({type: 'input', target: costSlider});
  assert.match(doc.getElementById('ctv-out-without').textContent, /1\.000,00/);
  assert.match(doc.getElementById('ctv-out-with').textContent, /200,00/);
  assert.match(doc.getElementById('ctv-out-monthly-savings').textContent, /800,00/);
  assert.match(doc.getElementById('ctv-out-avoided').textContent, /16\.000/);

  // A13 & A08: ctvView separates hypothetical simulator from real operational breakdown ($0.09 / 2 = $0.045) and handles 0 completions ("sem conclusões")
  setAppStateForTest({
    ctvMetricsData: {
      reference_period_label_pt: '2026-11-01T00:00:00Z até 2026-11-26T00:00:00Z',
      reference_cost_per_generation_usd: 0.03,
      operational_breakdown: {
        period_start: '2026-11-01T00:00:00Z',
        period_end: '2026-11-26T00:00:00Z',
        full_period_aggregated: true,
        total_requests_evaluated: 4,
        search_inconclusive_count: 0,
        requests_with_student_delivery: 3,
        reuse_without_call_deliveries: 1,
        reuse_without_call_rate_pct: 33.33,
        offline_cache_deliveries_reconciled: 1,
        real_provider_calls: 3,
        duplicates_barred_after_call: 1,
        avoided_generation_requests: 1,
        persisted_ai_items_count: 1,
        completed_verifications_count: 2,
        total_attributable_cost_usd: 0.09,
        cost_per_completed_verification_raw_usd: 0.045,
        cost_per_completed_verification_rounded_usd: 0.045,
        cost_per_completed_verification_display_pt: 'US$ (estimado) 0.0450',
        cost_is_estimated: true,
        cost_source_label_pt: 'Estimativa configurada por chamada (US$ 0.0300)'
      },
      adaptive_selection: {
        difficulty_triggered: {
          cohort_id: 'DIFFICULTY_DETECTED',
          label_pt: 'Disparo adaptativo por dificuldade ou fragilidade',
          offers_count: 3,
          dismissals_count: 1,
          responses_count: 2,
          independent_eligible_responses: 2,
          independent_correct_count: 1,
          independent_success_rate_pct: 50.0,
          summary_pt: '1/2 verificações independentes corretas (50.0%) · 1 oferta(s) dispensada(s)'
        },
        periodic_sampling: {
          cohort_id: 'PERIODIC_SAMPLING',
          label_pt: 'Amostragem periódica de acompanhamento',
          offers_count: 4,
          dismissals_count: 0,
          responses_count: 4,
          independent_eligible_responses: 4,
          independent_correct_count: 3,
          independent_success_rate_pct: 75.0,
          summary_pt: '3/4 verificações independentes corretas (75.0%)'
        },
        unknown_condition_count: 1
      },
      ctv_bank_summary: {ai_generated_total: 2, auto_approved: 1, pending_human_review: 1, suspended: 0},
      operational_kpis: [],
      recent_ctv_questions: []
    }
  });
  render();
  view = doc.getElementById('view');
  assert.equal(doc.getElementById('ctv-cost-per-completed').textContent, 'US$ (estimado) 0.0450');
  assert.match(view.textContent, /exato: US\$ 0\.045/);
  assert.match(view.textContent, /1\/2 verificações independentes corretas/);
  assert.match(view.textContent, /3\/4 verificações independentes corretas/);
  assert.match(view.textContent, /1 duplicata\(s\) barrada\(s\) pós-chamada/);
  assert.match(view.textContent, /Cache offline à parte: 1/);

  // Zero completed verifications -> displays "sem conclusões"
  setAppStateForTest({
    ctvMetricsData: {
      ...getAppState().ctvMetricsData,
      operational_breakdown: {
        ...getAppState().ctvMetricsData.operational_breakdown,
        completed_verifications_count: 0,
        cost_per_completed_verification_raw_usd: null,
        cost_per_completed_verification_rounded_usd: null,
        cost_per_completed_verification_display_pt: 'sem conclusões'
      }
    }
  });
  render();
  assert.equal(doc.getElementById('ctv-cost-per-completed').textContent, 'sem conclusões');
});

