import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {
  MAX_ARCHIVES,
  blankExam,
  exportWorkspace,
  freshImport,
  matchesSearch,
  newComponent,
  normalizeSearchText,
  objectiveDraft,
  parseOpenEndedGradingJson,
  pushArchive,
  reconcileQuestionDraft,
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
  setAppStateForTest
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
  const tabLogin = doc.createElement('button'); tabLogin.id = 'auth-tab-login';
  const tabReg = doc.createElement('button'); tabReg.id = 'auth-tab-register';
  const subtitle = doc.createElement('p'); subtitle.id = 'login-subtitle';
  const form = doc.createElement('form'); form.id = 'login-form';
  const nameField = doc.createElement('div'); nameField.id = 'login-name-field'; nameField.hidden = true;
  const nameInput = doc.createElement('input'); nameInput.id = 'login-display-name'; nameField.append(nameInput);
  const emailInput = doc.createElement('input'); emailInput.id = 'login-email';
  const passInput = doc.createElement('input'); passInput.id = 'login-password';
  const submit = doc.createElement('button'); submit.id = 'login-submit';
  form.append(nameField, emailInput, passInput, submit);
  const loginMsg = doc.createElement('p'); loginMsg.id = 'login-message';
  login.append(tabLogin, tabReg, subtitle, form, loginMsg);

  const app = doc.createElement('div'); app.id = 'app'; app.hidden = true; app.className = 'app-shell';
  const sidebar = doc.createElement('aside'); sidebar.id = 'sidebar'; sidebar.className = 'sidebar';
  const menuClose = doc.createElement('button'); menuClose.id = 'menu-close'; menuClose.setAttribute('type', 'button');
  const nav = doc.createElement('nav');
  for (const page of ['overview','exams','edital','trunks','subjects','questions','import','classification','reports','catalog','drafts','help']) {
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
    catalog_version: 2,
    trunks: [
      {trunk_id: 'fiscal', label: 'Área Fiscal', icon_key: 'Fiscal', aliases: ['Fisco'], status: 'ACTIVE', source_ids: ['BASE']},
      {trunk_id: 'controle', label: 'Controle e Gestão', icon_key: 'Control', aliases: ['TCU'], status: 'ACTIVE', source_ids: ['BASE']}
    ],
    subjects: [
      {subject_id: 'AFO', label: 'Orçamento Público', aliases: ['AFO'], scope_note: 'Orçamento e finanças', status: 'ACTIVE', revision: 1, source_ids: ['BASE'], trunk_links: [{trunk_id: 'fiscal', role: 'FOUNDATIONAL', default_weight: 1.5}]},
      {subject_id: 'DIR_TRIB', label: 'Direito Tributário', aliases: ['CTN'], scope_note: 'Tributos', status: 'ACTIVE', revision: 1, source_ids: ['BASE'], trunk_links: [{trunk_id: 'fiscal', role: 'FOUNDATIONAL', default_weight: 2.0}]}
    ],
    topics: [
      {topic_id: 'TRIB_CRED', subject_id: 'DIR_TRIB', parent_topic_id: null, kind: 'TOPIC', label: 'Crédito Tributário', aliases: [], status: 'ACTIVE', revision: 1, source_ids: ['BASE']},
      {topic_id: 'TRIB_LANC', subject_id: 'DIR_TRIB', parent_topic_id: 'TRIB_CRED', kind: 'SUBTOPIC', label: 'Lançamento', aliases: [], status: 'ACTIVE', revision: 1, source_ids: ['BASE']},
      {topic_id: 'TRIB_BASE', subject_id: 'DIR_TRIB', parent_topic_id: null, kind: 'TOPIC', label: 'Assunto Inicial', aliases: [], status: 'ACTIVE', revision: 1, source_ids: ['BASE']},
      {topic_id: 'AFO_LOA', subject_id: 'AFO', parent_topic_id: null, kind: 'TOPIC', label: 'Lei Orçamentária Anual', aliases: ['LOA'], status: 'ACTIVE', revision: 1, source_ids: ['BASE']}
    ],
    sources: [{source_id: 'BASE', kind: 'EDITORIAL_BASELINE', reference: 'Base', note: 'Base'}],
    relations: []
  };
}

function sampleBaseData(exams = []) {
  return {
    base: {sha256: 'abc123', catalog: sampleCatalog()},
    templates: {trunks: [{slug: 'fiscal', name: 'Área Fiscal'}]},
    exams,
    capabilities: {ai_available: true},
    summary: {
      exam_templates: exams.length,
      questions: 120,
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

function sampleV2Question(id = '65f000000000000000000001', revision = 1, kind = 'MULTIPLE_CHOICE') {
  return {
    _id: {$oid: id},
    schema_version: 2,
    revision,
    content_hash_version: 'question-v2-jcs-sha256',
    content_revision: 'a'.repeat(64),
    status: 'ACTIVE',
    question: `Enunciado original rev ${revision}`,
    question_type: kind,
    answers: kind === 'OPEN_ENDED' ? [] : ['Opção A', 'Opção B'],
    correct_answer_indices: kind === 'OPEN_ENDED' ? [] : [0],
    correct_answers: kind === 'OPEN_ENDED' ? [] : ['Opção A'],
    origins: [{origin_id: 'o1', kind: 'OFFICIAL_EXAM', verification: 'CONFIRMED', exam_year: 2025, provenance: {actor: {type: 'HUMAN', id: '1'}, method: 'MANUAL_REVIEW', rationale: 'ok', recorded_at: '2026-01-01T00:00:00Z', evidence: []}}],
    classification: {revision: 1, catalog_version: 2, state: 'CONFIRMED', links: [{link_id: 'l1', subject_id: 'DIR_TRIB', topic_id: 'TRIB_CRED', role: 'PRIMARY', coverage: 'DIRECT', provenance: {actor: {type: 'HUMAN', id: '1'}, method: 'MANUAL_REVIEW', rationale: 'ok', recorded_at: '2026-01-01T00:00:00Z', evidence: []}}]},
    validity: {revision: 1, status: 'CURRENT', basis: 'LEGAL', reference_date: '2026-01-01', review_due_on: null, reason_codes: [], evaluated_content_revision: 'a'.repeat(64), decision: null, scope: 'CURRENT_KNOWLEDGE', context_date: null},
    grading: kind === 'OPEN_ENDED'
      ? {strategy: 'RUBRIC', max_score: 100, pass_score: 60, expected_responses: ['Ref'], rubric: {revision: 1, criteria: [{criterion_id: 'c1', description: 'Critério 1', max_points: 100, essential: true}]}, engine: null, confirmation_policy: 'HUMAN_REVIEW', policy_version: 'open-rubric-human-review/1'}
      : {strategy: 'EXACT_OPTION', max_score: 100, pass_score: 60, expected_responses: [], rubric: null, engine: null, confirmation_policy: 'AUTOMATIC_EXACT', policy_version: 'objective-exact/1'}
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
    }
  };
  globalThis.document = doc;
  globalThis.window = win;
  globalThis.Node = FakeNode;
  globalThis.localStorage = storage;
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
    reportsData: null
  });
  initApp({autoBootstrap: false});
  return {doc, storage, calls};
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

  // Render untrusted strings inside live views (questionsView + reportsView)
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

  // Verify index.html & JS files obey strict CSP (script-src 'self'; style-src 'self') and contain no innerHTML / mojibake
  const indexHtml = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  const scriptTags = [...indexHtml.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];
  assert.equal(scriptTags.length, 1, 'index.html must have exactly one external module script tag');
  assert.match(scriptTags[0][1], /src="\/admin\/assets\/app\.js"/);
  assert.equal(scriptTags[0][2].trim(), '', 'index.html must have zero inline script code');
  assert.doesNotMatch(indexHtml, /\son[a-z]+\s*=/i, 'index.html must have zero inline on*= event handlers');
  assert.doesNotMatch(indexHtml, /\sstyle\s*=/i, 'index.html must have zero inline style= attributes');

  for (const file of ['../app.js', '../domain.js', '../help.js', '../index.html', '../style.css']) {
    const content = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(content, /\b(innerHTML|outerHTML|insertAdjacentHTML|eval)\b/);
    assert.doesNotMatch(content, /Ã£|Ã§|Ã©|Ãª|Ã³|â†|\uFFFD/, `${file} must not contain UTF-8 mojibake`);
  }
});

// --- 2. WCAG 2.1 AA Contrast (>= 4.5:1) & Minimum Font Size (>= 11px) ---

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
  const lighter = Math.max(l1, l2);
  const darker = Math.min(l1, l2);
  return (lighter + 0.05) / (darker + 0.05);
}

test('style.css enforces WCAG 2.1 AA (>= 4.5:1) across palette tokens/pills, font-size >= 11px, :focus-visible, and not-allowed disabled cursor', () => {
  const css = readFileSync(new URL('../style.css', import.meta.url), 'utf8');

  const rootMatch = css.match(/:root\s*\{([^}]+)\}/);
  assert.ok(rootMatch, ':root block must exist');
  const vars = Object.fromEntries(
    [...rootMatch[1].matchAll(/(--[a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,6})/g)].map(m => [m[1], m[2]])
  );
  const rootFontPx = Number(rootMatch[1].match(/font-size\s*:\s*(\d+(?:\.\d+)?)px/)?.[1] || 14);
  assert.ok(rootFontPx >= 14, 'root font size must be at least 14px');

  const pairs = [
    ['--ink on --bg', vars['--ink'], vars['--bg']],
    ['--ink on --surface', vars['--ink'], vars['--surface']],
    ['--muted on --bg', vars['--muted'], vars['--bg']],
    ['--muted on --surface', vars['--muted'], vars['--surface']],
    ['--muted on --teal-soft', vars['--muted'], vars['--teal-soft']],
    ['--brand on --surface', vars['--brand'], vars['--surface']],
    ['--brand on --bg', vars['--brand'], vars['--bg']],
    ['--brand on --teal-soft (.tabs button.active)', vars['--brand'], vars['--teal-soft']],
    ['--brand on #e4efee (.progress)', vars['--brand'], '#e4efee'],
    ['--brand on #edf6f3 (.structure-guide summary)', vars['--brand'], '#edf6f3'],
    ['#ffffff on --brand (button.primary)', '#ffffff', vars['--brand']],
    ['.badge default (#364752 on #eef2f5)', '#364752', '#eef2f5'],
    ['.badge.good (--good on --good-soft)', vars['--good'], vars['--good-soft']],
    ['.badge.warning (--amber on --amber-soft)', vars['--amber'], vars['--amber-soft']],
    ['.badge.danger (--red on --red-soft)', vars['--red'], vars['--red-soft']],
    ['--red on --surface (button.danger / #login-message)', vars['--red'], vars['--surface']],
    ['.notice.error (#7a231c on #fff0ee)', '#7a231c', '#fff0ee'],
    ['.notice.warning / .preparatory-banner (#5f3f0c on #fcf5e7)', '#5f3f0c', '#fcf5e7'],
    ['.conflict-banner (#53380b on #fcf5e7)', '#53380b', '#fcf5e7'],
    ['table th (#3d4e58 on #fafbfc)', '#3d4e58', '#fafbfc'],
    ['sidebar nav (#cde0e5 on --navy)', '#cde0e5', vars['--navy']],
    ['sidebar nav-label (#b0ced7 on --navy)', '#b0ced7', vars['--navy']],
    ['sidebar active (#ffffff on #244a52)', '#ffffff', '#244a52'],
    ['login-story eyebrow (#a6dccb on --navy)', '#a6dccb', vars['--navy']],
    ['login-story text (#c2d9df on --navy)', '#c2d9df', vars['--navy']]
  ];

  for (const [label, fg, bg] of pairs) {
    assert.ok(fg && bg, `missing color token for ${label}`);
    const ratio = contrastRatio(fg, bg);
    assert.ok(ratio >= 4.5, `${label} contrast ${ratio.toFixed(2)}:1 is below WCAG 2.1 AA 4.5:1`);
  }

  // Verify every font-size rule in style.css computes to >= 11px
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

  // Simulate repeated workspace export + backup + restore cycles: serialized size stays bounded O(1)
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

  // QuotaExceededError must warn in pt-BR and never crash UI
  const {doc, storage} = setupEnv();
  setAppStateForTest({
    me: {user_id: '42', profile: {display_name: 'Admin'}},
    data: sampleBaseData(),
    ws: currentWs
  });
  storage.failWithQuota = true;
  const saved = persist();
  assert.equal(saved, false);
  assert.equal(doc.getElementById('notice').hidden, false);
  assert.match(doc.getElementById('notice').textContent, /Armazenamento local cheio/i);

  // "Limpar histórico local" button in draftsView clears archives
  storage.failWithQuota = false;
  navigate('drafts');
  const clearBtn = findButton(doc.getElementById('view'), 'Limpar histórico local');
  assert.ok(clearBtn, 'draftsView must render "Limpar histórico local" when archives exist');
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

  // First open: caches rev 1 clean
  openQuestionEditor(qRev1._id.$oid);
  await flush();
  await flush();
  assert.equal(getAppState().ws.questionEdits[qRev1._id.$oid].original.revision, 1);

  // Server advances to rev 2 while local draft has NO unsaved edits (!dirty) -> silently refreshes to rev 2
  currentServerQuestion = qRev2;
  openQuestionEditor(qRev1._id.$oid);
  await flush();
  await flush();
  assert.equal(getAppState().ws.questionEdits[qRev1._id.$oid].original.revision, 2);
  assert.equal(Boolean(getAppState().ws.questionEdits[qRev1._id.$oid].conflict), false);
  assert.match(getAppState().ws.questionEdits[qRev1._id.$oid].text, /atualizado no servidor rev 2/);

  // User makes local dirty edit on rev 2, then server advances to rev 3 -> preserves dirty edit and surfaces conflict banner + reload button
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
  assert.match(dirtyDraft.text, /Minha edição local não salva/, 'must not silently overwrite dirty user edits');

  const conflictBanner = doc.getElementById('view').querySelector('.conflict-banner');
  assert.ok(conflictBanner, 'conflict banner must be visible');
  assert.match(conflictBanner.textContent, /revisão 3/i);

  const reloadBtn = findButton(conflictBanner, 'Recarregar versão do servidor');
  assert.ok(reloadBtn, 'must provide button to reload server version');
  reloadBtn.click();
  await flush();
  doc.getElementById('confirm-dialog').close('confirm');
  await flush();

  assert.equal(getAppState().ws.questionEdits[qRev1._id.$oid].original.revision, 3);
  assert.equal(getAppState().ws.archives.length, 1, 'dirty draft archived before reloading server version');
});

// --- 5. Session, CSRF, Logout Resilience, 401 Immediate Login Transition & Partial Load Retry ---

test('api attaches X-Cogniva-CSRF and same-origin credentials, logout recovers from 502/503, 401 transitions immediately to login, and partial loadData shows retry banner', async () => {
  let failLogout = true;
  let failSummaryAndExams = true;
  let return401OnSummary = false;

  const {doc, storage, calls} = setupEnv(async (url) => {
    if (url === '/api/v1/auth/web-logout') {
      if (failLogout) return {ok: false, status: 503, json: async () => ({message: 'Bad Gateway'})};
      return {ok: true, status: 200, json: async () => ({ok: true})};
    }
    if (url === '/api/v1/admin/web/me') {
      return {ok: true, status: 200, json: async () => ({user_id: '99', profile: {display_name: 'Coordenação'}})};
    }
    if (url === '/api/v1/admin/catalog/base') {
      return {ok: true, status: 200, json: async () => ({sha256: 'h1', catalog: sampleCatalog()})};
    }
    if (url === '/api/v1/catalog/trunks') {
      return {ok: true, status: 200, json: async () => ({trunks: []})};
    }
    if (url === '/api/v1/admin/questions/import/capabilities') {
      return {ok: true, status: 200, json: async () => ({ai_available: true})};
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
    return {ok: true, status: 200, json: async () => ({})};
  });

  // Bootstrap with partial failure on exam-templates (502) and summary (503)
  await bootstrap();
  assert.equal(doc.getElementById('app').hidden, false);
  assert.equal(doc.getElementById('login').hidden, true);

  // Verify CSRF header and credentials: 'same-origin' on every call
  assert.ok(calls.length >= 5);
  for (const call of calls) {
    assert.equal(call.options.credentials, 'same-origin');
    assert.equal(call.options.headers['X-Cogniva-CSRF'], '1');
  }

  // Partial load banner with "Tentar novamente" is visible
  const retryBanner = doc.getElementById('view').querySelector('.partial-load-banner');
  assert.ok(retryBanner, 'partial load retry banner must be rendered');
  const retryBtn = findButton(retryBanner, 'Tentar novamente');
  assert.ok(retryBtn, 'retry banner must have "Tentar novamente" button');

  // Recover on retry
  failSummaryAndExams = false;
  retryBtn.click();
  await flush();
  await flush();
  assert.equal(getAppState().loadErrors.length, 0);
  assert.equal(doc.getElementById('view').querySelector('.partial-load-banner'), null);

  // Logout resilience when server returns 503 AND localStorage throws QuotaExceededError
  storage.failWithQuota = true;
  await performLogout();
  assert.equal(getAppState().me, null);
  assert.equal(doc.getElementById('app').hidden, true);
  assert.equal(doc.getElementById('login').hidden, false);
  assert.match(doc.getElementById('login-message').textContent, /Sessão encerrada/i);
  storage.failWithQuota = false;

  // Re-bootstrap, then trigger HTTP 401 on an API call -> immediate transition to #login with pt-BR session-expired notice
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
  // 6a. OPEN_ENDED rubric JSON validation in domain.js
  const form = freshImport('op1', 'ai1').form;
  form.kind = 'OPEN_ENDED';
  form.question = 'Disserte sobre o orçamento.';
  form.reference = 'Resposta oficial.';
  form.criteria = '{malformed json';
  assert.throws(() => objectiveDraft(form), /JSON inválido na rubrica/);
  assert.throws(() => parseOpenEndedGradingJson('{bad'), /JSON inválido na rubrica/);

  // 6b. NFD accent-insensitive search
  assert.equal(normalizeSearchText('Orçamento Público e Tributário'), 'orcamento publico e tributario');
  assert.equal(matchesSearch('Orçamento Público — Receita Federal', 'orcamento publico'), true);
  assert.equal(matchesSearch('Direito Tributário', 'tributario'), true);

  // 6c. Question editor OPEN_ENDED rubric guard + AI operation_id rotation + questionsView pagination
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

  // In questionView, typing invalid JSON into "Correção e rubrica (JSON)" shows pt-BR error and blocks "Validar edição"
  navigate('question');
  const rubricField = findFieldControl(doc.getElementById('view'), 'Correção e rubrica (JSON)');
  assert.ok(rubricField);
  rubricField.value = '{rubrica quebrada';
  rubricField.dispatchEvent({type: 'input', bubbles: true});
  assert.match(doc.getElementById('notice').textContent, /JSON inválido na rubrica/);

  findButton(doc.getElementById('view'), 'Validar edição').click();
  await flush();
  await flush();
  assert.match(doc.getElementById('notice').textContent, /JSON inválido na rubrica/);

  // In examsView, searching "orcamento" matches "Orçamento Público Federal"
  navigate('exams');
  const searchInput = doc.getElementById('view').querySelector('input[type="search"]');
  searchInput.value = 'orcamento';
  searchInput.dispatchEvent({type: 'input', bubbles: true});
  const rows = doc.getElementById('view').querySelectorAll('tbody tr');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].hidden, false);

  // In importView, failed AI call rotates aiOperationId so retry uses a new ID and succeeds
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

  // In questionsView, clean UTF-8 pagination "← Página anterior" and "Próxima página →"
  navigate('questions');
  findButton(doc.getElementById('view'), 'Carregar questões').click();
  await flush();
  await flush();
  let prevBtn = findButton(doc.getElementById('view'), '← Página anterior');
  let nextBtn = findButton(doc.getElementById('view'), 'Próxima página →');
  assert.ok(prevBtn && nextBtn);
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

  // Mobile drawer toggle, close button, backdrop click, and Escape key
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

  // Dialog guards re-entrant showModal() when already open, and closes on backdrop click and Escape
  const dlg = doc.getElementById('confirm-dialog');
  const p1 = confirm('Primeira confirmação', 'Texto 1');
  assert.equal(dlg.open, true);
  const p2 = confirm('Segunda confirmação', 'Texto 2'); // must NOT throw InvalidStateError
  assert.equal(await p1, false);
  assert.equal(dlg.open, true);
  dlg.dispatchEvent({type: 'click', target: dlg, bubbles: true}); // backdrop click
  assert.equal(await p2, false);
  assert.equal(dlg.open, false);

  const p3 = confirm('Terceira confirmação', 'Texto 3');
  doc.dispatchEvent({type: 'keydown', key: 'Escape', bubbles: true});
  assert.equal(await p3, false);
  assert.equal(dlg.open, false);

  // Preserve open <details> and active input focus/selection across render()
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

// --- 8. Help Center LGPD Art. 18 & AI Governance Sync ---

test('help center includes LGPD Art. 18 privacy and AI governance guidance and matches docs/GUIA-ADMINISTRACAO.md', () => {
  const found = searchHelp('LGPD Art. 18 DELETE todasQuestoes prompt injection cota');
  assert.equal(found.length, 1);
  assert.equal(found[0].id, 'privacidade-lgpd-ia');
  const fullText = [found[0].title, found[0].intro, ...found[0].steps, ...found[0].notes].join(' ');
  for (const term of ['DELETE /api/v1/auth/account', 'todasQuestoes', 'question_revision_events', 'knowledge_catalog', 'exam_templates']) {
    assert.ok(fullText.includes(term), `help topic must mention ${term}`);
  }
  const guideMd = readFileSync(new URL('../../docs/GUIA-ADMINISTRACAO.md', import.meta.url), 'utf8');
  assert.ok(guideMd.includes('## Privacidade (LGPD Art. 18) e Governança de IA'));
  assert.equal(helpTopics.length >= 17, true);
});
