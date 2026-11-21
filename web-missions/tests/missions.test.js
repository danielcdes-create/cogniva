import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {
  buildDurationComparisonSummary,
  buildPresentValueCurvePoints,
  canAccessScene,
  formatBrl,
  formatClockSeconds,
  formatSignedPct,
  modifiedDurationZeroCoupon,
  presentValueZeroCoupon,
  priceChangePct,
  shouldAutoAdvanceScene,
} from '../domain.js';
import {
  api,
  exitMissionToCatalog,
  getMissionsState,
  h,
  initMissionsApp,
  loadCatalog,
  openMission,
  renderMissionsView,
  setMissionsStateForTest,
  svg,
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
  get firstChild() {
    return this.childNodes?.[0] || null;
  }
  removeChild(child) {
    if (!this.childNodes) return child;
    const idx = this.childNodes.indexOf(child);
    if (idx >= 0) {
      this.childNodes.splice(idx, 1);
      child.parentNode = null;
    }
    return child;
  }
  remove() {
    if (this.parentNode) {
      this.parentNode.removeChild(this);
    }
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

class FakeElement extends FakeNode {
  constructor(tagName, ownerDocument = null) {
    super(1);
    this.tagName = String(tagName).toUpperCase();
    this.ownerDocument = ownerDocument;
    this.childNodes = [];
    this._attrs = new Map();
    this._listeners = new Map();
    this.classList = new FakeClassList(this);
    this._value = '';
    this.checked = false;
    this.disabled = false;
    this.selected = false;
    this.hidden = false;
    this.muted = false;
    this.volume = 1;
    this.currentTime = 0;
    this.duration = 10;
    this.paused = true;
    this.ended = false;
    this.textTracks = [{mode: 'showing'}];
  }
  get id() { return this._attrs.get('id') || ''; }
  set id(v) { if (v) this._attrs.set('id', String(v)); else this._attrs.delete('id'); }
  get className() { return this._attrs.get('class') || ''; }
  set className(v) { if (v) this._attrs.set('class', String(v)); else this._attrs.delete('class'); }
  get value() { return this._value; }
  set value(v) { this._value = String(v ?? ''); }
  get textContent() { return this.childNodes.map(c => c.textContent).join(''); }
  set textContent(v) {
    this.childNodes = [];
    if (v !== '' && v != null) this.append(new FakeText(String(v), this.ownerDocument));
  }
  setAttribute(name, val) {
    const key = String(name).toLowerCase();
    this._attrs.set(key, String(val));
    if (key === 'hidden') this.hidden = true;
    if (key === 'disabled') this.disabled = true;
    if (key === 'value') this._value = String(val);
  }
  getAttribute(name) {
    const key = String(name).toLowerCase();
    return this._attrs.has(key) ? this._attrs.get(key) : null;
  }
  hasAttribute(name) { return this._attrs.has(String(name).toLowerCase()); }
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
  addEventListener(type, fn) {
    const list = this._listeners.get(type) || [];
    list.push(fn);
    this._listeners.set(type, list);
  }
  dispatchEvent(event) {
    const ev = {
      type: event.type,
      target: event.target || this,
      currentTarget: this,
      bubbles: Boolean(event.bubbles),
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
    };
    for (const fn of [...(this._listeners.get(ev.type) || [])]) {
      fn.call(this, ev);
    }
    return !ev.defaultPrevented;
  }
  click() {
    if (this.disabled) return;
    this.dispatchEvent({type: 'click', bubbles: true});
  }
  play() {
    this.paused = false;
    this.ended = false;
    this.dispatchEvent({type: 'play'});
    return Promise.resolve();
  }
  pause() {
    this.paused = true;
    this.dispatchEvent({type: 'pause'});
  }
  querySelectorAll(selector) {
    const out = [];
    const groups = selector.split(',').map(s => s.trim()).filter(Boolean);
    const visit = node => {
      for (const child of node.childNodes || []) {
        if (child.nodeType === 1) {
          if (groups.some(g => matchesSimple(child, g))) out.push(child);
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
    this.head = new FakeElement('head', this);
    this.body = new FakeElement('body', this);
    this.append(this.head, this.body);
  }
  createElement(tag) { return new FakeElement(tag, this); }
  createElementNS(_ns, tag) { return new FakeElement(tag, this); }
  createTextNode(text) { return new FakeText(text, this); }
  getElementById(id) { return this.querySelector(`#${id}`); }
}

class FakeStorage {
  constructor() { this.map = new Map(); }
  getItem(k) { return this.map.has(k) ? this.map.get(k) : null; }
  setItem(k, v) { this.map.set(String(k), String(v)); }
  removeItem(k) { this.map.delete(String(k)); }
  clear() { this.map.clear(); }
}

function buildMissionsShell(doc) {
  const auth = doc.createElement('section'); auth.id = 'auth-screen';
  const tabLogin = doc.createElement('button'); tabLogin.id = 'tab-login';
  const tabRegister = doc.createElement('button'); tabRegister.id = 'tab-register';
  const form = doc.createElement('form'); form.id = 'auth-form';
  const nameWrap = doc.createElement('div'); nameWrap.id = 'auth-name-wrap'; nameWrap.hidden = true;
  const nameInput = doc.createElement('input'); nameInput.id = 'auth-name';
  nameWrap.append(nameInput);
  const emailInput = doc.createElement('input'); emailInput.id = 'auth-email';
  const passInput = doc.createElement('input'); passInput.id = 'auth-password';
  const submitBtn = doc.createElement('button'); submitBtn.id = 'auth-submit';
  form.append(nameWrap, emailInput, passInput, submitBtn);
  const guestBtn = doc.createElement('button'); guestBtn.id = 'guest-btn';
  const authStatus = doc.createElement('p'); authStatus.id = 'auth-status';
  auth.append(tabLogin, tabRegister, form, guestBtn, authStatus);

  const shell = doc.createElement('div'); shell.id = 'missions-shell'; shell.hidden = true;
  const navHome = doc.createElement('a'); navHome.id = 'nav-home-link';
  const navMissions = doc.createElement('button'); navMissions.id = 'nav-missions-btn';
  const navAdmin = doc.createElement('a'); navAdmin.id = 'nav-admin-link'; navAdmin.hidden = true;
  const userBadge = doc.createElement('span'); userBadge.id = 'user-badge';
  const logoutBtn = doc.createElement('button'); logoutBtn.id = 'logout-btn';
  const notice = doc.createElement('div'); notice.id = 'missions-notice'; notice.hidden = true;
  const main = doc.createElement('main'); main.id = 'missions-main';
  shell.append(navHome, navMissions, navAdmin, userBadge, logoutBtn, notice, main);

  doc.body.append(auth, shell);
}

const rawCatalog = JSON.parse(
  readFileSync(new URL('../../shared/missions-catalog.json', import.meta.url), 'utf8')
);

function buildMockMissionBackend(options = {}) {
  const career = rawCatalog.careers[0];
  const disc = career.disciplines[0];
  const season = disc.seasons[0];
  const mDef = season.missions[0];
  const assetsById = Object.fromEntries(rawCatalog.media_assets.map(a => [a.asset_id, a]));
  const charsById = Object.fromEntries(rawCatalog.characters.map(c => [c.character_id, c]));

  const completedScenes = new Set(options.initialCompletedScenes || []);
  let currentSceneId = options.initialSceneId || mDef.scenes[0].scene_id;
  let status = options.initialStatus || 'NOT_STARTED';
  let repeatCount = 0;
  const decisions = new Map();
  let humanQuestions = options.humanQuestions || [];
  let practicedDistinct = 0;

  const computeProgress = () => {
    const pct = status === 'COMPLETED'
      ? 100
      : Math.round((completedScenes.size / mDef.scenes.length) * 100);
    const curOrder = (mDef.scenes.find(s => s.scene_id === currentSceneId)?.order) || 1;
    return {
      status,
      status_label_pt: status === 'COMPLETED' ? 'Concluída' : status === 'IN_PROGRESS' ? 'Em andamento' : 'Não iniciada',
      current_scene_id: currentSceneId,
      current_scene_order: curOrder,
      completed_scene_ids: [...completedScenes],
      completed_scenes_count: completedScenes.size,
      total_scenes_count: mDef.scenes.length,
      progress_pct: pct,
      scene_positions_ms: {},
      repeat_count: repeatCount,
    };
  };

  const computePerf = () => {
    const totalDec = mDef.scenes.filter(s => s.interaction).length;
    const vals = [...decisions.values()];
    const firstRight = vals.filter(d => d.first_attempt_correct).length;
    const curRight = vals.filter(d => d.is_correct).length;
    const review = [...new Set(vals.flatMap(d => d.concepts_for_review || []))];
    return {
      answered_decisions_count: vals.length,
      total_decisions_count: totalDec,
      first_attempt_correct_count: firstRight,
      latest_correct_count: curRight,
      concepts_for_review: review,
      is_diagnostic_only: true,
    };
  };

  const computeConcepts = () => {
    const reviewSet = new Set(computePerf().concepts_for_review);
    return mDef.curriculum.worked_concepts.map(c => ({
      concept_key: c.concept_key,
      label: c.label,
      summary: c.summary,
      canonical_subject_id: c.canonical_subject_id,
      active_topic_ids: c.canonical_topic_ids,
      related_scene_ids: c.related_scene_ids,
      recommended_revisit: reviewSet.has(c.concept_key),
    }));
  };

  const computeMastery = () => ({
    mastery_state: humanQuestions.length === 0 ? 'NO_QUESTIONS_AVAILABLE' : practicedDistinct === 0 ? 'UNMEASURED' : 'EVIDENCE_IN_PROGRESS',
    mastery_label_pt: humanQuestions.length === 0
      ? 'Sem questões humanas validadas disponíveis neste ambiente para aferir domínio formal.'
      : `Evidência em construção (${practicedDistinct}/15 questões humanas distintas nos subtópicos).`,
    available_human_questions_count: humanQuestions.length,
    distinct_human_questions_practiced: practicedDistinct,
    mastered_human_questions_count: 0,
    min_questions_for_mastery: 15,
    resolved_topic_ids: ['MFIN_07_001', 'MFIN_07_003'],
    practice_available: humanQuestions.length > 0,
  });

  const buildDetail = () => {
    const totalDur = rawCatalog.media_assets.reduce((acc, a) => acc + a.duration_seconds, 0);
    return {
      career: {
        career_id: career.career_id,
        display_header: career.display_header,
        institution: career.institution,
        role: career.role,
        contest_template_id: career.contest_template_id,
        trunk_id: career.trunk_id,
      },
      discipline: {
        discipline_id: disc.discipline_id,
        display_name: disc.display_name,
        canonical_subject_id: disc.canonical_subject_id,
        exam_component_id: disc.exam_component_id,
      },
      season: {
        season_id: season.season_id,
        season_number: season.season_number,
        title: season.title,
        theme: season.theme,
        summary: season.summary,
      },
      mission: {
        mission_id: mDef.mission_id,
        mission_number: mDef.mission_number,
        display_code: mDef.display_code,
        title: mDef.title,
        full_title: mDef.full_title,
        discipline_label: mDef.curriculum.discipline_label,
        theme_label: mDef.curriculum.theme_label,
        description: mDef.description,
        duration: {
          video_seconds: totalDur,
          interaction_seconds: 145,
          total_seconds: totalDur + 145,
          estimated_minutes: 3,
          display_label: '~3 min (48s de vídeo + decisões)',
        },
        worked_concepts: computeConcepts(),
        scenes: mDef.scenes.map((s, idx) => {
          const asset = assetsById[s.asset_id];
          const nextAssetId = mDef.scenes[idx + 1]?.asset_id;
          return {
            scene_id: s.scene_id,
            order: s.order,
            title: s.title,
            pedagogical_goal: s.pedagogical_goal,
            character: charsById[s.character_id],
            narrative_context: s.narrative_context,
            problem_statement: s.problem_statement || null,
            transcript_pt_br: s.transcript_pt_br,
            auto_advance_on_video_end: s.auto_advance_on_video_end,
            requires_decision_to_advance: s.requires_decision_to_advance,
            next_scene_id: s.next_scene_id || null,
            preload_next_media_url: nextAssetId ? `/api/v1/missions/media/${nextAssetId}` : null,
            is_completed: completedScenes.has(s.scene_id),
            media: {
              asset_id: asset.asset_id,
              stream_url: `/api/v1/missions/media/${asset.asset_id}`,
              captions_url: `/api/v1/missions/captions/${asset.asset_id}`,
              mime_type: asset.mime_type,
              duration_seconds: asset.duration_seconds,
              width: asset.width,
              height: asset.height,
              aspect_ratio: asset.aspect_ratio,
              available_on_server: options.videoAvailable !== false,
              captions: asset.captions,
            },
            native_visual: s.native_visual || null,
            interaction: s.interaction
              ? {
                  interaction_id: s.interaction.interaction_id,
                  kind: s.interaction.kind,
                  is_editorial_question: false,
                  contributes_to_formal_mastery: false,
                  prompt: s.interaction.prompt,
                  context_lead: s.interaction.context_lead,
                  ceteris_paribus_note: s.interaction.ceteris_paribus_note,
                  options: s.interaction.options.map(o => ({option_id: o.option_id, label: o.label})),
                  answered: decisions.get(s.interaction.interaction_id) || null,
                }
              : null,
          };
        }),
        conclusion: mDef.conclusion,
      },
      narrative_progress: computeProgress(),
      interaction_performance: computePerf(),
      demonstrated_mastery: computeMastery(),
    };
  };

  return async (url, reqOpts = {}) => {
    const method = (reqOpts.method || 'GET').toUpperCase();
    const body = reqOpts.body ? JSON.parse(reqOpts.body) : {};
    const respond = (statusHttp, jsonObj) => ({
      ok: statusHttp >= 200 && statusHttp < 300,
      status: statusHttp,
      text: async () => JSON.stringify(jsonObj),
    });

    if (url === '/api/v1/auth/me') {
      if (options.authenticatedProfile) {
        return respond(200, options.authenticatedProfile);
      }
      return respond(401, {code: 'UNAUTHORIZED', message: 'Não autenticado'});
    }
    if (url === '/api/v1/auth/guest' && method === 'POST') {
      return respond(200, {
        profile: {user_id: 9001, display_name: 'Visitante', is_guest: true, is_admin: false},
      });
    }
    if (url === '/api/v1/missions' && method === 'GET') {
      const det = buildDetail();
      return respond(200, {
        schema_version: 1,
        catalog_revision: 1,
        careers: [
          {
            ...det.career,
            disciplines: [
              {
                ...det.discipline,
                seasons: [
                  {
                    ...det.season,
                    missions: [
                      {
                        mission_id: det.mission.mission_id,
                        mission_number: det.mission.mission_number,
                        display_code: det.mission.display_code,
                        title: det.mission.title,
                        full_title: det.mission.full_title,
                        discipline_label: det.mission.discipline_label,
                        theme_label: det.mission.theme_label,
                        description: det.mission.description,
                        scenes_count: det.mission.scenes.length,
                        decisions_count: 2,
                        duration: det.mission.duration,
                        worked_concepts: det.mission.worked_concepts,
                        narrative_progress: det.narrative_progress,
                        interaction_performance: det.interaction_performance,
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      });
    }
    if (url.startsWith(`/api/v1/missions/${mDef.mission_id}`) && method === 'GET') {
      return respond(200, buildDetail());
    }
    if (url === `/api/v1/missions/${mDef.mission_id}/progress` && method === 'POST') {
      if (status === 'NOT_STARTED') status = 'IN_PROGRESS';
      if (body.completed_scene_id) completedScenes.add(body.completed_scene_id);
      if (body.current_scene_id) currentSceneId = body.current_scene_id;
      if (body.mark_mission_completed) status = 'COMPLETED';
      return respond(200, {
        narrative_progress: computeProgress(),
        interaction_performance: computePerf(),
      });
    }
    if (url === `/api/v1/missions/${mDef.mission_id}/decisions` && method === 'POST') {
      const scene = mDef.scenes.find(s => s.scene_id === body.scene_id);
      const inter = scene.interaction;
      const opt = inter.options.find(o => o.option_id === body.selected_option_id);
      const isCorrect = inter.expected_option_id === opt.option_id;
      const prev = decisions.get(inter.interaction_id);
      const record = {
        interaction_id: inter.interaction_id,
        scene_id: scene.scene_id,
        selected_option_id: opt.option_id,
        expected_option_id: inter.expected_option_id,
        is_correct: isCorrect,
        first_attempt_option_id: prev ? prev.first_attempt_option_id : opt.option_id,
        first_attempt_correct: prev ? prev.first_attempt_correct : isCorrect,
        attempts_count: (prev?.attempts_count || 0) + 1,
        option_feedback: opt.feedback,
        conceptual_explanation: inter.conceptual_explanation,
        concepts_for_review: isCorrect ? [] : inter.revisit_concept_keys,
      };
      decisions.set(inter.interaction_id, record);
      completedScenes.add(scene.scene_id);
      if (status === 'NOT_STARTED') status = 'IN_PROGRESS';
      return respond(200, {
        decision: record,
        narrative_progress: computeProgress(),
        interaction_performance: computePerf(),
        worked_concepts: computeConcepts(),
      });
    }
    if (url === `/api/v1/missions/${mDef.mission_id}/practice-questions` && method === 'POST') {
      return respond(200, {
        epistemic_source: 'HUMAN_VALIDATED_QUESTION_BANK_ONLY',
        canonical_subject_id: 'MFIN',
        active_topic_ids: ['MFIN_07_001', 'MFIN_07_003'],
        available_count: humanQuestions.length,
        questions: humanQuestions,
        empty_state_message: humanQuestions.length === 0
          ? 'Ainda não há questões humanas validadas disponíveis neste ambiente para os subtópicos vinculados.'
          : null,
      });
    }
    if (url === '/api/v1/quiz/submit-batch' && method === 'POST') {
      practicedDistinct += 1;
      return respond(200, {results: [{accepted: true}]});
    }
    if (url.endsWith('/telemetry') && method === 'POST') {
      return respond(200, {ok: true});
    }
    if (url.endsWith('/repeat') && method === 'POST') {
      repeatCount += 1;
      status = 'IN_PROGRESS';
      completedScenes.clear();
      currentSceneId = mDef.scenes[0].scene_id;
      return respond(200, {narrative_progress: computeProgress()});
    }
    return respond(404, {message: `Rota não simulada: ${method} ${url}`});
  };
}

function setupMissionsTestEnv(backendOptions = {}) {
  const doc = new FakeDocument();
  buildMissionsShell(doc);
  const storage = new FakeStorage();
  const calls = [];
  const handler = buildMockMissionBackend(backendOptions);

  globalThis.document = doc;
  globalThis.localStorage = storage;
  globalThis.window = {
    __COGNIVA_TEST__: true,
    localStorage: storage,
    location: {hash: ''},
    history: {replaceState(_s, _t, url) { this.lastUrl = url; }},
  };
  globalThis.fetch = async (url, opts = {}) => {
    calls.push({url, opts});
    return handler(url, opts);
  };

  setMissionsStateForTest({
    authMode: 'login',
    profile: null,
    catalog: null,
    activeDetail: null,
    activeSceneId: null,
    showConclusion: false,
    transcriptOpen: false,
    captionsEnabled: true,
    autoAdvanceEnabled: true,
    userIntervenedInScene: false,
    videoErrored: false,
    simulatedRatePct: 14,
    practiceState: null,
    busy: false,
    notice: '',
  });

  return {doc, storage, calls};
}

function findButton(root, textSubstring) {
  return root.querySelectorAll('button').find(b => b.textContent.includes(textSubstring)) || null;
}

// --- 1. Strict CSP, XSS Escaping, No Windows Path Leaks & UTF-8 Integrity ---

test('DOM/SVG builders escape XSS payloads and web-missions contains zero innerHTML, inline style/script, or host path leaks', () => {
  setupMissionsTestEnv();
  const payload = `<script>window.__pwn=1</script><img src=x onerror="alert(1)">`;
  const node = h('section', {'data-test': payload}, h('h2', {}, payload), svg('svg', {'aria-label': payload}));

  assert.equal(node.querySelectorAll('script').length, 0);
  assert.equal(node.querySelectorAll('img').length, 0);
  assert.match(node.serializeHtml(), /&lt;script&gt;window\.__pwn=1&lt;\/script&gt;/);

  const indexHtml = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  const scripts = [...indexHtml.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];
  assert.equal(scripts.length, 1);
  assert.match(scripts[0][1], /src="\/missoes\/assets\/app\.js"/);
  assert.equal(scripts[0][2].trim(), '');
  assert.doesNotMatch(indexHtml, /\son[a-z]+\s*=/i);
  assert.doesNotMatch(indexHtml, /\sstyle\s*=/i);

  for (const rel of ['../index.html', '../style.css', '../domain.js', '../app.js']) {
    const content = readFileSync(new URL(rel, import.meta.url), 'utf8');
    assert.doesNotMatch(content, /\b(innerHTML|outerHTML|insertAdjacentHTML|eval)\b/);
    assert.doesNotMatch(content, /C:\\Users\\|missoes\\videos/i, `${rel} must never leak host filesystem paths`);
    assert.doesNotMatch(content, /Ã£|Ã§|Ã©|Ãª|Ã³|\uFFFD/, `${rel} must not contain UTF-8 mojibake`);
  }
  const appJs = readFileSync(new URL('../app.js', import.meta.url), 'utf8');
  assert.doesNotMatch(appJs, /\{style\s*:/, 'app.js must not emit inline style attributes under style-src self CSP');
});

// --- 2. WCAG 2.1 AA Contrast (>= 4.5:1) & Accessibility Rules ---

function parseHexColor(hex) {
  const clean = hex.trim().replace(/^#/, '');
  return [
    parseInt(clean.slice(0, 2), 16),
    parseInt(clean.slice(2, 4), 16),
    parseInt(clean.slice(4, 6), 16),
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

test('style.css enforces WCAG 2.1 AA (>= 4.5:1) across dark theater tokens, status pills, focus-visible, and disabled states', () => {
  const css = readFileSync(new URL('../style.css', import.meta.url), 'utf8');
  const rootMatch = css.match(/:root\s*\{([^}]+)\}/);
  assert.ok(rootMatch);
  const vars = Object.fromEntries(
    [...rootMatch[1].matchAll(/(--[a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{6})/g)].map(m => [m[1], m[2]])
  );

  const pairs = [
    ['--ink on --bg', vars['--ink'], vars['--bg']],
    ['--ink on --surface', vars['--ink'], vars['--surface']],
    ['--ink on --surface-elevated', vars['--ink'], vars['--surface-elevated']],
    ['--ink on --surface-card', vars['--ink'], vars['--surface-card']],
    ['--muted on --bg', vars['--muted'], vars['--bg']],
    ['--muted on --surface', vars['--muted'], vars['--surface']],
    ['--muted on --surface-elevated', vars['--muted'], vars['--surface-elevated']],
    ['--muted on --surface-card', vars['--muted'], vars['--surface-card']],
    ['--brand on --surface', vars['--brand'], vars['--surface']],
    ['--brand on --surface-elevated', vars['--brand'], vars['--surface-elevated']],
    ['--brand-ink on --brand (.btn-primary)', vars['--brand-ink'], vars['--brand']],
    ['--good-ink on --good-bg (.status-completed / .feedback-panel.good)', vars['--good-ink'], vars['--good-bg']],
    ['--warn-ink on --warn-bg (.status-in_progress / .feedback-panel.review)', vars['--warn-ink'], vars['--warn-bg']],
    ['--error-ink on --error-bg', vars['--error-ink'], vars['--error-bg']],
    ['--warn on --surface (.auth-status)', vars['--warn'], vars['--surface']],
  ];

  for (const [label, fg, bg] of pairs) {
    const ratio = contrastRatio(fg, bg);
    assert.ok(ratio >= 4.5, `${label} contrast ${ratio.toFixed(2)}:1 must be >= 4.5:1`);
  }
  assert.match(css, /:focus-visible/);
  assert.match(css, /cursor:\s*not-allowed/);
  assert.match(css, /aspect-ratio:\s*16\s*\/\s*9/);
});

// --- 3. Financial Domain Calculations & Scene Progression Rules ---

test('domain.js calculates exact Present Value, Macaulay/Modified Duration, and non-linear rate shock drops', () => {
  // 3-year zero-coupon VF = 1000: at 10% -> 751.31; at 14% -> 674.97
  const pv10 = presentValueZeroCoupon(1000, 10, 3);
  const pv14 = presentValueZeroCoupon(1000, 14, 3);
  assert.ok(Math.abs(pv10 - 751.3148) < 0.01);
  assert.ok(Math.abs(pv14 - 674.9715) < 0.01);
  assert.ok(pv14 < pv10, 'Rising market interest rates must reduce fixed-rate bond price');

  const curve = buildPresentValueCurvePoints({face_value_brl: 1000, maturity_years: 3, min_rate_pct: 4, max_rate_pct: 20});
  assert.equal(curve.length, 17);
  for (let i = 1; i < curve.length; i++) {
    assert.ok(curve[i].priceBrl < curve[i - 1].priceBrl, 'PV curve must be strictly decreasing with rate');
  }

  // Scene 5 Duration comparison: 1y vs 5y zero-coupon under parallel shock 10% -> 12%
  const cmp = buildDurationComparisonSummary({
    face_value_brl: 1000,
    short_maturity_years: 1,
    long_maturity_years: 5,
    initial_rate_pct: 10,
    shocked_rate_pct: 12,
  });
  assert.equal(cmp.shortBond.macaulayDuration, 1);
  assert.equal(cmp.longBond.macaulayDuration, 5);
  assert.ok(Math.abs(modifiedDurationZeroCoupon(5, 10) - 4.5454) < 0.01);
  assert.equal(formatSignedPct(cmp.shortBond.changePct), '−1,79%');
  assert.equal(formatSignedPct(cmp.longBond.changePct), '−8,62%');
  assert.ok(Math.abs(cmp.longBond.changePct) > Math.abs(cmp.shortBond.changePct) * 4);
  assert.equal(formatBrl(1000), 'R$ 1.000,00');
  assert.equal(formatClockSeconds(48.2), '00:48');
});

test('shouldAutoAdvanceScene and canAccessScene respect student intervention, decision gates, and resume jumps', () => {
  const scenes = rawCatalog.careers[0].disciplines[0].seasons[0].missions[0].scenes;
  const s1 = scenes[0]; // auto_advance_on_video_end: true
  const s3 = scenes[2]; // requires_decision_to_advance: true

  assert.equal(shouldAutoAdvanceScene(s1, {autoAdvanceEnabled: true, userIntervened: false, videoErrored: false}), true);
  assert.equal(shouldAutoAdvanceScene(s1, {autoAdvanceEnabled: true, userIntervened: true, videoErrored: false}), false);
  assert.equal(shouldAutoAdvanceScene(s1, {autoAdvanceEnabled: false, userIntervened: false, videoErrored: false}), false);
  assert.equal(shouldAutoAdvanceScene(s1, {autoAdvanceEnabled: true, userIntervened: false, videoErrored: true}), false);
  assert.equal(shouldAutoAdvanceScene(s3, {autoAdvanceEnabled: true, userIntervened: false, videoErrored: false}), false);

  // Resume at Scene 4 with Scenes 1..3 completed: Scenes 1..4 accessible without re-watching, Scene 5 locked until Scene 4 completes
  const resumeProg = {
    status: 'IN_PROGRESS',
    current_scene_id: scenes[3].scene_id,
    completed_scene_ids: [scenes[0].scene_id, scenes[1].scene_id, scenes[2].scene_id],
  };
  assert.equal(canAccessScene(scenes, scenes[0].scene_id, resumeProg), true);
  assert.equal(canAccessScene(scenes, scenes[3].scene_id, resumeProg), true);
  assert.equal(canAccessScene(scenes, scenes[4].scene_id, resumeProg), false);
});

// --- 4. End-to-End Pilot Flow: Scenes 1-5, Native SVG Visuals, Decisions & Epistemological Separation ---

test('full pilot flow walks Scenes 1-5, renders native SVG PV & Duration charts, persists formative decisions, and preserves formal mastery separation', async () => {
  const {doc, calls} = setupMissionsTestEnv({humanQuestions: []});
  await initMissionsApp(doc);

  // Starts on Auth screen when /api/v1/auth/me returns 401
  assert.equal(doc.getElementById('auth-screen').hidden, false);
  assert.equal(doc.getElementById('missions-shell').hidden, true);

  // Student starts guest session (attaches X-Cogniva-CSRF: 1 + client: student_web)
  doc.getElementById('guest-btn').click();
  await new Promise(r => setTimeout(r, 20));

  assert.equal(doc.getElementById('auth-screen').hidden, true);
  assert.equal(doc.getElementById('missions-shell').hidden, false);
  const guestCall = calls.find(c => c.url === '/api/v1/auth/guest');
  assert.equal(guestCall.opts.headers['X-Cogniva-CSRF'], '1');
  assert.equal(JSON.parse(guestCall.opts.body).client, 'student_web');

  // Catalog renders Career -> Discipline/Theme -> Season -> Mission 01
  const main = doc.getElementById('missions-main');
  assert.match(main.textContent, /Banco Central — Economia e Finanças/);
  assert.match(main.textContent, /Temporada 1: Mercado Financeiro/);
  assert.match(main.textContent, /Missão 01: O Choque dos Juros/);
  assert.match(main.textContent, /Não iniciada/);

  // Open Mission 01 -> Cena 1 (O Alerta)
  findButton(main, 'Iniciar missão').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Etapa 1 de 5 · Cena 1 — O Alerta/);
  const video1 = doc.getElementById('active-scene-video');
  assert.ok(video1);
  assert.equal(video1.getAttribute('src'), '/api/v1/missions/media/asset-bcb-m01-v01-alerta');

  // Cena 1 auto-advances smoothly to Cena 2 on video ended
  video1.dispatchEvent({type: 'ended'});
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Etapa 2 de 5 · Cena 2 — O Problema/);
  assert.match(main.textContent, /Títulos prefixados prometem pagamento nominal fixo no vencimento/);

  // Advance from Cena 2 to Cena 3 (A Primeira Decisão)
  findButton(main, 'Avançar para a próxima cena').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Etapa 3 de 5 · Cena 3 — A Primeira Decisão/);
  assert.match(main.textContent, /Se os juros de mercado subirem, o que acontece com o preço de um título prefixado\?/);

  // Student picks wrong option A first -> non-punitive feedback + highlights B as expected
  findButton(main, 'O preço aumenta.').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Você selecionou A · Resposta conceitual esperada: B/);
  assert.match(main.textContent, /valor presente dos seus fluxos fixos descontados pela taxa exigida/);

  // Advance to Cena 4 (A Explicação) -> Native App-Rendered SVG Bond PV Curve + Rate Slider
  findButton(main, 'Continuar para a próxima cena').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Etapa 4 de 5 · Cena 4 — A Explicação/);
  const svgCharts4 = main.querySelectorAll('svg');
  assert.equal(svgCharts4.length, 1, 'Scene 4 must render native SVG Present Value chart');
  assert.match(main.textContent, /GRÁFICO NATIVO DO APLICATIVO/);
  assert.match(main.textContent, /fluxos de caixa fixos e ausência de outras alterações relevantes/);

  // Adjust interactive rate slider to 18% -> price updates dynamically
  const slider = doc.getElementById('sim-rate-range');
  assert.ok(slider);
  slider.value = '18';
  slider.dispatchEvent({type: 'input', target: slider});
  assert.equal(getMissionsState().simulatedRatePct, 18);
  assert.match(main.textContent, /18,0% a\.a\./);

  // Advance to Cena 5 (O Desafio de Duration)
  findButton(main, 'Avançar para a próxima cena').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Etapa 5 de 5 · Cena 5 — O Desafio de Duration/);
  assert.match(main.textContent, /Qual título sofre maior queda percentual de preço quando os juros sobem\?/);

  // Select correct option B (5-year bond) -> renders native SVG Duration Comparison chart
  findButton(main, 'Título com vencimento em cinco anos.').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Resposta esperada \(B\) confirmada!/);
  const svgCharts5 = main.querySelectorAll('svg');
  assert.equal(svgCharts5.length, 1, 'Scene 5 must render native SVG Duration comparison chart after answer');
  assert.match(main.textContent, /−8,62%/);
  assert.match(main.textContent, /−1,79%/);

  // Open Mission Conclusion -> verifies 3-tier epistemological separation & revisit recommendation
  findButton(main, 'Ver conclusão da missão').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /1\. Progresso narrativo/);
  assert.match(main.textContent, /100%/);
  assert.match(main.textContent, /2\. Desempenho nas interações/);
  assert.match(main.textContent, /1\/2 na 1ª escolha/);
  assert.match(main.textContent, /3\. Domínio demonstrado \(acervo\)/);
  assert.match(main.textContent, /0\/15/);
  assert.match(main.textContent, /Revisitação sugerida/);

  // Honest empty state when 0 human-validated questions exist in test environment
  findButton(main, 'Verificar questões reais no acervo').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Ainda não há questões humanas validadas disponíveis neste ambiente/);
});

// --- 5. Video Failure Transcript Fallback & Formal Human Question Submission ---

test('video playback failure activates Transcript Fallback Mode without blocking progression, and real human questions submit to /api/v1/quiz/submit-batch', async () => {
  const realHumanQuestion = {
    id: '65f000000000000000000101',
    question: 'Questão oficial CESPE/CEBRASPE sobre marcação a mercado de LTN prefixada.',
    question_type: 'TRUE_FALSE',
    answers: ['Certo', 'Errado'],
    correct_answer_indices: [0],
    disciplina: 'Finanças',
    source: 'CESPE · BCB',
    explanation: 'A elevação da taxa de desconto reduz o PU da LTN.',
    content_revision: 'b'.repeat(64),
    canonical_subject_id: 'MFIN',
    canonical_topic_id: 'MFIN_07_001',
  };

  const {doc, calls} = setupMissionsTestEnv({
    authenticatedProfile: {user_id: 77, display_name: 'Ana', is_guest: false, is_admin: false},
    humanQuestions: [realHumanQuestion],
    initialStatus: 'COMPLETED',
    initialCompletedScenes: [
      'cena-01-o-alerta',
      'cena-02-o-problema',
      'cena-03-primeira-decisao',
      'cena-04-explicacao',
      'cena-05-desafio-duration',
    ],
  });

  await initMissionsApp(doc);

  const main = doc.getElementById('missions-main');
  assert.match(main.textContent, /Concluída/);

  // Re-open Mission 01 at Scene 1 and trigger video error -> Transcript Fallback Mode activates
  await openMission('bcb-ef-t1-m01-choque-dos-juros', {resume: false});
  setMissionsStateForTest({showConclusion: false, activeSceneId: 'cena-01-o-alerta'});
  renderMissionsView();

  const videoEl = doc.getElementById('active-scene-video');
  videoEl.dispatchEvent({type: 'error'});
  assert.match(main.textContent, /Modo Transcrição Ativo · Vídeo indisponível no momento/);
  assert.match(main.textContent, /Uma alta inesperada dos juros acaba de atingir o mercado de títulos públicos/);

  // Student can advance via transcript without blocking
  findButton(main, 'Prosseguir com transcrição').click();
  await new Promise(r => setTimeout(r, 20));
  assert.match(main.textContent, /Etapa 2 de 5 · Cena 2 — O Problema/);

  // Exit to catalog saves progress, then open conclusion & practice real human question
  await exitMissionToCatalog();
  assert.equal(getMissionsState().activeDetail, null);

  await openMission('bcb-ef-t1-m01-choque-dos-juros', {resume: false});
  setMissionsStateForTest({showConclusion: true});
  renderMissionsView();

  const practiceBtn = findButton(main, 'Praticar questões reais relacionadas (1 disponíveis)');
  assert.ok(practiceBtn);
  practiceBtn.click();
  await new Promise(r => setTimeout(r, 20));

  assert.match(main.textContent, /AVALIAÇÃO FORMAL INDEPENDENTE · QUESTÃO 1 DE 1/);
  findButton(main, 'Certo').click();
  findButton(main, 'Confirmar resposta formal').click();
  await new Promise(r => setTimeout(r, 20));

  const submitCall = calls.find(c => c.url === '/api/v1/quiz/submit-batch');
  assert.ok(submitCall, 'Real human question practice must submit through official /api/v1/quiz/submit-batch');
  assert.match(main.textContent, /Resposta correta no acervo oficial!/);
  assert.match(main.textContent, /1\/15/);
});
