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

function splitSelectorOutsideBrackets(input, delim) {
  const out = [];
  let cur = '';
  let depth = 0;
  let quote = null;
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

function matchesSelector(el, selectorGroup) {
  return splitSelectorOutsideBrackets(selectorGroup, ',').some(raw => {
    const chain = splitSelectorOutsideBrackets(raw, ' ');
    let idx = chain.length - 1;
    if (idx < 0 || !matchesSimple(el, chain[idx])) return false;
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
    this._value = '';
    this.checked = false;
    this.disabled = false;
    this.selected = false;
    this.hidden = false;
    this.muted = false;
    this.preload = '';
    this.volume = 1;
    this.currentTime = 0;
    this.duration = 10;
    this.paused = true;
    this.ended = false;
    this.loadCount = 0;
    this.pauseCount = 0;
    this.textTracks = [{mode: 'showing'}];
  }
  get id() { return this._attrs.get('id') || ''; }
  set id(v) { if (v) this._attrs.set('id', String(v)); else this._attrs.delete('id'); }
  get className() { return this._attrs.get('class') || ''; }
  set className(v) { if (v) this._attrs.set('class', String(v)); else this._attrs.delete('class'); }
  get value() { return this._value; }
  set value(v) { this._value = String(v ?? ''); }
  get src() { return this._attrs.get('src') || ''; }
  set src(v) {
    if (v === '' || v == null) this._attrs.set('src', '');
    else this._attrs.set('src', String(v));
  }
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
  removeAttribute(name) {
    const key = String(name).toLowerCase();
    this._attrs.delete(key);
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
    const invokeOn = node => {
      ev.currentTarget = node;
      for (const fn of [...(node._listeners.get(ev.type) || [])]) {
        fn.call(node, ev);
      }
    };
    invokeOn(this);
    if (ev.bubbles) {
      let cur = this.parentElement;
      while (cur) {
        invokeOn(cur);
        cur = cur.parentElement;
      }
      if (this.ownerDocument) invokeOn(this.ownerDocument);
    }
    return !ev.defaultPrevented;
  }
  click() {
    if (this.disabled) return;
    const allowed = this.dispatchEvent({type: 'click', bubbles: true});
    if (allowed && this.tagName === 'BUTTON' && (this.getAttribute('type') || 'submit') === 'submit') {
      let form = this.parentElement;
      while (form && form.tagName !== 'FORM') form = form.parentElement;
      if (form) {
        form.dispatchEvent({type: 'submit', bubbles: true});
      }
    }
  }
  play() {
    this.paused = false;
    this.ended = false;
    this.dispatchEvent({type: 'play'});
    return Promise.resolve();
  }
  pause() {
    this.paused = true;
    this.pauseCount += 1;
    this.dispatchEvent({type: 'pause'});
  }
  load() {
    this.loadCount += 1;
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
    this.createdElements = [];
    this.head = new FakeElement('head', this);
    this.body = new FakeElement('body', this);
    this.append(this.head, this.body);
  }
  createElement(tag) {
    const el = new FakeElement(tag, this);
    this.createdElements.push(el);
    return el;
  }
  createElementNS(_ns, tag) {
    const el = new FakeElement(tag, this);
    this.createdElements.push(el);
    return el;
  }
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
  const tabLogin = doc.createElement('button'); tabLogin.id = 'tab-login'; tabLogin.setAttribute('type', 'button');
  const tabRegister = doc.createElement('button'); tabRegister.id = 'tab-register'; tabRegister.setAttribute('type', 'button');
  const form = doc.createElement('form'); form.id = 'auth-form';
  const nameWrap = doc.createElement('div'); nameWrap.id = 'auth-name-wrap'; nameWrap.hidden = true;
  const nameInput = doc.createElement('input'); nameInput.id = 'auth-name';
  nameWrap.append(nameInput);
  const emailInput = doc.createElement('input'); emailInput.id = 'auth-email';
  const passInput = doc.createElement('input'); passInput.id = 'auth-password';
  const submitBtn = doc.createElement('button'); submitBtn.id = 'auth-submit'; submitBtn.setAttribute('type', 'submit');
  form.append(nameWrap, emailInput, passInput, submitBtn);
  const guestBtn = doc.createElement('button'); guestBtn.id = 'guest-btn'; guestBtn.setAttribute('type', 'button');
  const authStatus = doc.createElement('p'); authStatus.id = 'auth-status';
  auth.append(tabLogin, tabRegister, form, guestBtn, authStatus);

  const shell = doc.createElement('div'); shell.id = 'missions-shell'; shell.hidden = true;
  const navHome = doc.createElement('a'); navHome.id = 'nav-home-link';
  const navMissions = doc.createElement('button'); navMissions.id = 'nav-missions-btn'; navMissions.setAttribute('type', 'button');
  const navAdmin = doc.createElement('a'); navAdmin.id = 'nav-admin-link'; navAdmin.hidden = true;
  const userBadge = doc.createElement('span'); userBadge.id = 'user-badge';
  const logoutBtn = doc.createElement('button'); logoutBtn.id = 'logout-btn'; logoutBtn.setAttribute('type', 'button');
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
  const mDef = structuredClone(season.missions[0]);
  if (options.overrideVisualParams) {
    for (const [sceneId, params] of Object.entries(options.overrideVisualParams)) {
      const targetScene = mDef.scenes.find(s => s.scene_id === sceneId);
      if (targetScene?.native_visual) {
        targetScene.native_visual.parameters = {
          ...targetScene.native_visual.parameters,
          ...params,
        };
      }
    }
  }
  const assetsById = Object.fromEntries(rawCatalog.media_assets.map(a => [a.asset_id, a]));
  const charsById = Object.fromEntries(rawCatalog.characters.map(c => [c.character_id, c]));

  const completedScenes = new Set(options.initialCompletedScenes || []);
  let currentSceneId = options.initialSceneId || mDef.scenes[0].scene_id;
  let status = options.initialStatus || 'NOT_STARTED';
  let repeatCount = 0;
  const scenePositionsMs = {...(options.initialScenePositionsMs || {})};
  const decisions = new Map();
  const humanQuestions = options.humanQuestions || [];
  const practicedQuestionIds = new Set();

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
      scene_positions_ms: {...scenePositionsMs},
      repeat_count: repeatCount,
    };
  };

  const computePerf = () => {
    const totalDec = mDef.scenes.filter(s => s.interaction).length;
    const vals = [...decisions.values()];
    const firstRight = vals.filter(d => d.first_attempt_correct).length;
    const curRight = vals.filter(d => d.is_correct).length;
    const review = [...new Set(vals.flatMap(d => (d.is_correct ? [] : d.revisit_concept_keys || [])))];
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

  const computeMastery = () => {
    const practicedDistinct = practicedQuestionIds.size;
    return {
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
    };
  };

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
        version: mDef.version,
        display_code: mDef.display_code,
        title: mDef.title,
        full_title: mDef.full_title,
        summary: mDef.summary,
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

    if (typeof options.beforeRequest === 'function') {
      const intercepted = await options.beforeRequest(url, method, body, respond);
      if (intercepted) return intercepted;
    }

    if (url === '/api/v1/auth/me') {
      if (options.authenticatedProfile) {
        return respond(200, options.authenticatedProfile);
      }
      return respond(401, {code: 'UNAUTHORIZED', message: 'Não autenticado'});
    }
    if (url === '/api/v1/auth/guest' && method === 'POST') {
      const profile = {user_id: 9001, display_name: 'Visitante', is_guest: true, is_admin: false, role: 'student'};
      return respond(200, {
        authenticated: true,
        user_id: '9001',
        profile,
        expires_in_seconds: 43200,
      });
    }
    if (url === '/api/v1/auth/register' && method === 'POST') {
      if (options.failAuthMessage) {
        return respond(409, {message: options.failAuthMessage});
      }
      const profile = {
        user_id: 9010,
        email: body.email,
        display_name: body.display_name || 'Estudante Web',
        is_guest: false,
        is_admin: Boolean(options.loginAsAdmin),
        role: options.loginAsAdmin ? 'admin' : 'student',
      };
      return respond(200, {
        authenticated: true,
        user_id: String(profile.user_id),
        profile,
        expires_in_seconds: 43200,
      });
    }
    if (url === '/api/v1/auth/login' && method === 'POST') {
      if (options.failAuthMessage) {
        return respond(401, {message: options.failAuthMessage});
      }
      const profile = {
        user_id: 9011,
        email: body.email,
        display_name: options.loginDisplayName || 'Ana Autenticada',
        is_guest: false,
        is_admin: Boolean(options.loginAsAdmin),
        role: options.loginAsAdmin ? 'admin' : 'student',
      };
      return respond(200, {
        authenticated: true,
        user_id: String(profile.user_id),
        profile,
        expires_in_seconds: 43200,
      });
    }
    if (url === '/api/v1/auth/logout' && method === 'POST') {
      return respond(200, {authenticated: false, revoked: true});
    }
    if (url === '/api/v1/missions' && method === 'GET') {
      const det = buildDetail();
      return respond(200, {
        catalog_version: rawCatalog.catalog_version,
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
                        version: det.mission.version,
                        display_code: det.mission.display_code,
                        title: det.mission.title,
                        full_title: det.mission.full_title,
                        summary: det.mission.summary,
                        description: det.mission.description,
                        contest_name: mDef.curriculum.contest_name,
                        contest_template_id: mDef.curriculum.contest_template_id,
                        role_name: mDef.curriculum.role_name,
                        discipline_label: det.mission.discipline_label,
                        theme_label: det.mission.theme_label,
                        canonical_subject_id: mDef.curriculum.canonical_subject_id,
                        worked_concepts: mDef.curriculum.worked_concepts.map(c => ({
                          concept_key: c.concept_key,
                          label: c.label,
                        })),
                        duration: det.mission.duration,
                        scenes_count: det.mission.scenes.length,
                        available_videos_count: options.videoAvailable === false ? 0 : det.mission.scenes.length,
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
      if (body.current_scene_id) {
        currentSceneId = body.current_scene_id;
        if (typeof body.playback_position_ms === 'number') {
          scenePositionsMs[currentSceneId] = body.playback_position_ms;
        }
      }
      if (body.mark_mission_completed) {
        for (const s of mDef.scenes) completedScenes.add(s.scene_id);
        status = 'COMPLETED';
      }
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
        first_selected_option_id: prev ? prev.first_selected_option_id : opt.option_id,
        expected_option_id: inter.expected_option_id,
        is_correct: isCorrect,
        first_attempt_correct: prev ? prev.first_attempt_correct : isCorrect,
        attempt_count: (prev?.attempt_count || 0) + 1,
        option_feedback: opt.feedback,
        conceptual_explanation: inter.conceptual_explanation,
        revisit_concept_keys: inter.revisit_concept_keys,
        answered_at: '2026-11-23T10:00:00Z',
      };
      decisions.set(inter.interaction_id, record);
      completedScenes.add(scene.scene_id);
      currentSceneId = scene.scene_id;
      if (!scene.next_scene_id) {
        const allRequiredAnswered = mDef.scenes
          .filter(s => s.requires_decision_to_advance)
          .every(s => s.interaction && decisions.has(s.interaction.interaction_id));
        if (allRequiredAnswered) {
          for (const s of mDef.scenes) completedScenes.add(s.scene_id);
          status = 'COMPLETED';
        }
      } else if (status === 'NOT_STARTED') {
        status = 'IN_PROGRESS';
      }
      return respond(200, {
        decision: record,
        narrative_progress: computeProgress(),
        interaction_performance: computePerf(),
        worked_concepts: computeConcepts(),
        mission_completed: status === 'COMPLETED',
      });
    }
    if (url === `/api/v1/missions/${mDef.mission_id}/practice-questions` && method === 'POST') {
      return respond(200, {
        mission_id: mDef.mission_id,
        canonical_subject_id: mDef.curriculum.canonical_subject_id,
        discipline_label: mDef.curriculum.discipline_label,
        resolved_topic_ids: ['MFIN_07_001', 'MFIN_07_003'],
        available_count: humanQuestions.length,
        questions: humanQuestions,
        empty_state_message: humanQuestions.length === 0
          ? 'Nenhuma questão humana validada disponível no acervo atual para os assuntos desta missão.'
          : null,
      });
    }
    if (url === '/api/v1/quiz/submit-batch' && method === 'POST') {
      const results = [];
      const rejected = [];
      for (const att of body.attempts || []) {
        const matchedQ = humanQuestions.find(q => q.id === att.question_id);
        if (!matchedQ || !att.question_revision || att.question_revision !== matchedQ.question_revision) {
          rejected.push({
            attempt_id: att.attempt_id,
            question_id: att.question_id,
            message: 'Revisão da questão ausente ou divergente no acervo oficial.',
            retryable: false,
          });
          continue;
        }
        practicedQuestionIds.add(att.question_id);
        results.push({
          attempt_id: att.attempt_id,
          confidence_avg: att.confidence_level,
          attempts_total: 1,
          correct_total: att.is_correct ? 1 : 0,
          next_review_at: '2026-11-30T00:00:00Z',
          canonical_subject_id: att.canonical_subject_id || 'MFIN',
          canonical_topic_id: att.canonical_topic_id || null,
          question_id: att.question_id,
          is_correct: Boolean(att.is_correct),
          confidence_quadrant: att.is_correct ? 'HIGH_CONFIDENCE_HIT' : 'HIGH_CONFIDENCE_ERROR',
          high_confidence_error_alert: !att.is_correct && att.confidence_level >= 3,
          streak_atual: att.is_correct ? 1 : 0,
          streak_max: 1,
          points_total: att.is_correct ? 10 : 0,
          points_with_decay: att.is_correct ? 10 : 0,
          is_mastered: false,
          next_review_interval_days: 7,
          accuracy_accumulated_pct: att.is_correct ? 100 : 0,
          in_error_notebook: !att.is_correct,
        });
      }
      return respond(200, {
        processed_count: results.length,
        rejected,
        results,
      });
    }
    if (url.endsWith('/telemetry') && method === 'POST') {
      return respond(200, {recorded: true});
    }
    if (url.endsWith('/repeat') && method === 'POST') {
      repeatCount += 1;
      status = 'IN_PROGRESS';
      completedScenes.clear();
      decisions.clear();
      currentSceneId = mDef.scenes[0].scene_id;
      return respond(200, {
        narrative_progress: computeProgress(),
        interaction_performance: computePerf(),
      });
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
    location: {hash: backendOptions.initialHash || ''},
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

const flush = (ms = 15) => new Promise(r => setTimeout(r, ms));

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
  const borderContrast = contrastRatio(vars['--line'], vars['--surface-elevated']);
  assert.ok(borderContrast >= 3.0, `--line on --surface-elevated contrast ${borderContrast.toFixed(2)}:1 must be >= 3.0:1 (WCAG 1.4.11)`);
  assert.match(css, /:focus-visible/);
  assert.match(css, /cursor:\s*not-allowed/);
  assert.match(css, /aspect-ratio:\s*16\s*\/\s*9/);
});

// --- 3. Financial Domain Calculations & Scene Progression Rules ---

test('domain.js calculates exact Present Value, Macaulay/Modified Duration, non-linear rate shock drops, and boundary guards', () => {
  const pv10 = presentValueZeroCoupon(1000, 10, 3);
  const pv14 = presentValueZeroCoupon(1000, 14, 3);
  assert.ok(Math.abs(pv10 - 751.3148) < 0.01);
  assert.ok(Math.abs(pv14 - 674.9715) < 0.01);
  assert.ok(pv14 < pv10, 'Rising market interest rates must reduce fixed-rate bond price');

  // Boundary guards for invalid discount rate / negative maturity
  assert.equal(presentValueZeroCoupon(1000, -100, 3), 1000);
  assert.equal(presentValueZeroCoupon(1000, 10, -2), 1000);
  assert.equal(modifiedDurationZeroCoupon(5, -100), 5);
  assert.equal(formatSignedPct(0), '0,00%');
  assert.equal(formatSignedPct(2.5), '+2,50%');

  const curve = buildPresentValueCurvePoints({face_value_brl: 1000, maturity_years: 3, min_rate_pct: 4, max_rate_pct: 20});
  assert.equal(curve.length, 17);
  for (let i = 1; i < curve.length; i++) {
    assert.ok(curve[i].priceBrl < curve[i - 1].priceBrl, 'PV curve must be strictly decreasing with rate');
  }

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

  const resumeProg = {
    status: 'IN_PROGRESS',
    current_scene_id: scenes[3].scene_id,
    completed_scene_ids: [scenes[0].scene_id, scenes[1].scene_id, scenes[2].scene_id],
  };
  assert.equal(canAccessScene([], s1.scene_id, resumeProg), false);
  assert.equal(canAccessScene(scenes, scenes[0].scene_id, resumeProg), true);
  assert.equal(canAccessScene(scenes, scenes[3].scene_id, resumeProg), true);
  assert.equal(canAccessScene(scenes, scenes[4].scene_id, resumeProg), false);
  assert.equal(canAccessScene(scenes, scenes[4].scene_id, {status: 'COMPLETED'}), true);
});

// --- 4. End-to-End Pilot Flow: Scenes 1-5, Native SVG Visuals, Decisions & Epistemological Separation ---

test('full pilot flow walks Scenes 1-5, renders native SVG PV & Duration charts, persists formative decisions, and sends single final /progress', async () => {
  const {doc, calls} = setupMissionsTestEnv({humanQuestions: []});
  await initMissionsApp(doc);

  assert.equal(doc.getElementById('auth-screen').hidden, false);
  assert.equal(doc.getElementById('missions-shell').hidden, true);

  doc.getElementById('guest-btn').click();
  await flush();

  assert.equal(doc.getElementById('auth-screen').hidden, true);
  assert.equal(doc.getElementById('missions-shell').hidden, false);
  assert.equal(doc.getElementById('nav-admin-link').hidden, true);
  const guestCall = calls.find(c => c.url === '/api/v1/auth/guest');
  assert.equal(guestCall.opts.headers['X-Cogniva-CSRF'], '1');
  assert.equal(JSON.parse(guestCall.opts.body).client, 'student_web');

  const catalogResp = getMissionsState().catalog;
  assert.equal(catalogResp.catalog_version, rawCatalog.catalog_version);
  assert.equal(catalogResp.careers[0].disciplines[0].seasons[0].missions[0].available_videos_count, 5);

  const main = doc.getElementById('missions-main');
  assert.match(main.textContent, /Banco Central — Economia e Finanças/);
  assert.match(main.textContent, /Temporada 1: Mercado Financeiro/);
  assert.match(main.textContent, /Missão 01: O Choque dos Juros/);

  // Open Mission 01 -> Cena 1 (O Alerta)
  findButton(main, 'Iniciar missão').click();
  await flush();
  assert.match(main.textContent, /Etapa 1 de 5 · Cena 1 — O Alerta/);
  const video1 = doc.getElementById('active-scene-video');
  assert.ok(video1);
  assert.equal(video1.getAttribute('src'), '/api/v1/missions/media/asset-bcb-m01-v01-alerta');

  // Cena 1 auto-advances smoothly to Cena 2 on video ended
  video1.dispatchEvent({type: 'ended'});
  await flush();
  assert.match(main.textContent, /Etapa 2 de 5 · Cena 2 — O Problema/);

  // Advance from Cena 2 to Cena 3 (A Primeira Decisão)
  findButton(main, 'Avançar para a próxima cena').click();
  await flush();
  assert.match(main.textContent, /Etapa 3 de 5 · Cena 3 — A Primeira Decisão/);

  // Student picks wrong option A first -> non-punitive feedback + real contract fields
  findButton(main, 'O preço aumenta.').click();
  await flush();
  assert.match(main.textContent, /Você selecionou A · Resposta conceitual esperada: B/);
  const dec3 = getMissionsState().activeDetail.mission.scenes[2].interaction.answered;
  assert.equal(dec3.first_selected_option_id, 'A');
  assert.equal(dec3.attempt_count, 1);
  assert.ok(Array.isArray(dec3.revisit_concept_keys) && dec3.revisit_concept_keys.length > 0);

  // Advance to Cena 4 (A Explicação) -> Native App-Rendered SVG Bond PV Curve + Rate Slider
  findButton(main, 'Continuar para a próxima cena').click();
  await flush();
  assert.match(main.textContent, /Etapa 4 de 5 · Cena 4 — A Explicação/);
  assert.equal(main.querySelectorAll('.visual-card svg').length, 1);

  // Advance to Cena 5 (O Desafio de Duration)
  findButton(main, 'Avançar para a próxima cena').click();
  await flush();
  assert.match(main.textContent, /Etapa 5 de 5 · Cena 5 — O Desafio de Duration/);

  // Select correct option B (5-year bond) -> renders native SVG Duration Comparison chart
  findButton(main, 'Título com vencimento em cinco anos.').click();
  await flush();
  assert.match(main.textContent, /Resposta esperada \(B\) confirmada!/);
  assert.match(main.textContent, /Zero Cupom 1 ano \(D = 1\)/);
  assert.match(main.textContent, /Zero Cupom 5 anos \(D = 5\)/);

  // Open Mission Conclusion -> verify single POST /progress on last scene (with mark_mission_completed: true)
  const progressCallsBefore = calls.filter(c => c.url.endsWith('/progress')).length;
  findButton(main, 'Ver conclusão da missão').click();
  await flush();
  const progressCallsAfter = calls.filter(c => c.url.endsWith('/progress'));
  assert.equal(progressCallsAfter.length - progressCallsBefore, 1, 'Last scene conclusion must emit a single POST /progress');
  const finalProgressBody = JSON.parse(progressCallsAfter.at(-1).opts.body);
  assert.equal(finalProgressBody.completed_scene_id, 'cena-05-desafio-duration');
  assert.equal(finalProgressBody.mark_mission_completed, true);

  assert.match(main.textContent, /1\. Progresso narrativo/);
  assert.match(main.textContent, /100%/);
  assert.match(main.textContent, /1\/2 na 1ª escolha/);
  assert.match(main.textContent, /0\/15/);
  assert.match(main.textContent, /Revisitação sugerida/);

  // Honest empty state when 0 human-validated questions exist
  findButton(main, 'Verificar questões reais no acervo').click();
  await flush();
  assert.match(main.textContent, /Nenhuma questão humana validada disponível no acervo atual/);
});

// --- 5. Formal Human Question Submission: Contract Payload, Rejections, 502/503 Retry & Anti-Double-Click ---

test('real human questions send QuestionDto.question_revision to /api/v1/quiz/submit-batch, surface rejected/503 errors without marking answered, and block concurrent double-submit', async () => {
  const realHumanQuestion1 = {
    id: '65f000000000000000000101',
    question_revision: 'b'.repeat(64),
    question: 'Questão oficial CESPE/CEBRASPE sobre marcação a mercado de LTN prefixada.',
    question_type: 'TRUE_FALSE',
    answers: ['Certo', 'Errado'],
    correct_answer_indices: [0],
    disciplina: 'Finanças',
    source: 'CESPE · BCB',
    level: 'Difícil',
    exam_year: 2024,
    explanation: 'A elevação da taxa de desconto reduz o PU da LTN.',
    canonical_subject_id: 'MFIN',
    canonical_topic_id: 'MFIN_07_001',
  };
  const realHumanQuestion2 = {
    id: '65f000000000000000000102',
    question_revision: 'c'.repeat(64),
    question: 'Segunda questão humana validada sobre duration de Macaulay.',
    question_type: 'TRUE_FALSE',
    answers: ['Certo', 'Errado'],
    correct_answer_indices: [1],
    disciplina: 'Finanças',
    source: 'FGV · STN',
    level: 'Média',
    exam_year: 2025,
    explanation: 'Zero cupom possui duration igual ao prazo.',
    canonical_subject_id: 'MFIN',
    canonical_topic_id: 'MFIN_07_003',
  };
  assert.equal('content_revision' in realHumanQuestion1, false, 'QuestionDto must not rely on legacy content_revision');

  let submitMode = 'http_503';
  let releasePendingSubmit = null;

  const {doc, calls} = setupMissionsTestEnv({
    authenticatedProfile: {user_id: 77, display_name: 'Ana', is_guest: false, is_admin: false},
    humanQuestions: [realHumanQuestion1, realHumanQuestion2],
    initialStatus: 'COMPLETED',
    initialCompletedScenes: [
      'cena-01-o-alerta',
      'cena-02-o-problema',
      'cena-03-primeira-decisao',
      'cena-04-explicacao',
      'cena-05-desafio-duration',
    ],
    beforeRequest: async (url, method, _body, respond) => {
      if (url === '/api/v1/quiz/submit-batch' && method === 'POST') {
        if (submitMode === 'http_503') {
          return respond(503, {message: 'Serviço de persistência temporariamente indisponível (503).'});
        }
        if (submitMode === 'batch_rejected') {
          return respond(200, {
            processed_count: 0,
            rejected: [
              {
                attempt_id: 'rej-1',
                question_id: realHumanQuestion1.id,
                message: 'Persistência indisponível; tentativa preservada para reenvio.',
                retryable: true,
              },
            ],
            results: [],
          });
        }
        if (submitMode === 'slow_ok') {
          await new Promise(resolve => { releasePendingSubmit = resolve; });
        }
      }
      return null;
    },
  });

  await initMissionsApp(doc);
  const main = doc.getElementById('missions-main');
  const notice = doc.getElementById('missions-notice');

  // Open conclusion straight from catalog "Ver resumo e questões reais" (single render, no double open)
  findButton(main, 'Ver resumo e questões reais').click();
  await flush();
  assert.equal(getMissionsState().showConclusion, true);

  findButton(main, 'Praticar questões reais relacionadas (2 disponíveis)').click();
  await flush();

  assert.match(main.textContent, /AVALIAÇÃO FORMAL INDEPENDENTE · QUESTÃO 1 DE 2/);
  findButton(main, '3 · Certeza fundamentada').click();
  findButton(main, 'Certo').click();

  // 5a. HTTP 503 failure: surfaces pt-BR error, keeps question unanswered, and re-enables confirm button
  findButton(main, 'Confirmar resposta formal').click();
  await flush();
  assert.equal(getMissionsState().practiceState.submittedResult, null);
  assert.equal(notice.hidden, false);
  assert.match(notice.textContent, /temporariamente indisponível \(503\)/);
  assert.equal(doc.getElementById('confirm-practice-btn').disabled, false);

  // 5b. HTTP 200 with SubmitBatchResponse.rejected (processed_count === 0): surfaces rejected[0].message, keeps unanswered, re-enables button
  submitMode = 'batch_rejected';
  doc.getElementById('confirm-practice-btn').click();
  await flush();
  assert.equal(getMissionsState().practiceState.submittedResult, null);
  assert.equal(notice.hidden, false);
  assert.match(notice.textContent, /Persistência indisponível; tentativa preservada/);
  assert.equal(doc.getElementById('confirm-practice-btn').disabled, false);
  assert.doesNotMatch(main.textContent, /Resposta correta no acervo oficial!/);

  // 5c. Anti-double-click lock + full payload inspection on real acceptance
  submitMode = 'slow_ok';
  const submitCountBefore = calls.filter(c => c.url === '/api/v1/quiz/submit-batch').length;
  const confirmBtn = doc.getElementById('confirm-practice-btn');
  confirmBtn.click();
  confirmBtn.click(); // second synchronous click while busy must be ignored
  assert.equal(confirmBtn.disabled, true);
  assert.equal(getMissionsState().busy, true);
  assert.equal(calls.filter(c => c.url === '/api/v1/quiz/submit-batch').length - submitCountBefore, 1);

  releasePendingSubmit();
  await flush();

  const submitCall = calls.filter(c => c.url === '/api/v1/quiz/submit-batch').at(-1);
  const sentPayload = JSON.parse(submitCall.opts.body);
  assert.equal(sentPayload.attempts.length, 1);
  assert.equal(sentPayload.attempts[0].question_id, realHumanQuestion1.id);
  assert.equal(sentPayload.attempts[0].question_revision, realHumanQuestion1.question_revision);
  assert.equal(sentPayload.attempts[0].chosen_index, 0);
  assert.equal(sentPayload.attempts[0].chosen_text, 'Certo');
  assert.equal(sentPayload.attempts[0].is_correct, true);
  assert.equal(sentPayload.attempts[0].confidence_level, 3);
  assert.equal(sentPayload.attempts[0].confidence_explicit, true);
  assert.equal(sentPayload.attempts[0].declared_confidence, 'HIGH');
  assert.equal(sentPayload.attempts[0].quiz_mode, 'STANDARD');
  assert.equal(sentPayload.attempts[0].canonical_subject_id, 'MFIN');
  assert.equal(sentPayload.attempts[0].canonical_topic_id, 'MFIN_07_001');

  assert.equal(notice.hidden, true);
  assert.match(main.textContent, /Resposta correta no acervo oficial!/);
  assert.match(main.textContent, /1\/15/);

  // Advance to 2nd human question without clicking confidence -> must NOT fabricate confidence from Q1!
  submitMode = 'normal';
  findButton(main, 'Próxima questão real').click();
  assert.match(main.textContent, /QUESTÃO 2 DE 2/);
  const confBtnsQ2 = main.querySelectorAll('.confidence-btn');
  assert.equal(confBtnsQ2.length, 5);
  assert.ok(confBtnsQ2.every(b => b.getAttribute('aria-pressed') === 'false'));
  const certoBtnQ2 = findButton(main, 'Certo');
  assert.equal(certoBtnQ2.getAttribute('aria-pressed'), 'false');
  certoBtnQ2.click();
  assert.equal(findButton(main, 'Certo').getAttribute('aria-pressed'), 'true');
  findButton(main, 'Confirmar resposta formal').click();
  await flush();
  const submitCallQ2 = calls.filter(c => c.url === '/api/v1/quiz/submit-batch').at(-1);
  const sentPayloadQ2 = JSON.parse(submitCallQ2.opts.body);
  assert.equal(sentPayloadQ2.attempts[0].confidence_explicit, false);
  assert.equal(sentPayloadQ2.attempts[0].declared_confidence, null);
  assert.match(main.textContent, /Resposta incorreta — revise o fundamento oficial/);
  assert.match(main.textContent, /2\/15/);
});

// --- 6. Scene 1 Real Auto-Advance Toggle, In-Place Player Controls & Preloaded Video Cleanup ---

test('Scene 1 autoAdvBtn toggles in-place without destroying <video>, blocks auto-advance when paused, and player controls operate live', async () => {
  const {doc} = setupMissionsTestEnv({
    authenticatedProfile: {user_id: 88, display_name: 'Bruno', is_guest: false, is_admin: false},
    initialStatus: 'IN_PROGRESS',
    initialSceneId: 'cena-01-o-alerta',
    initialScenePositionsMs: {'cena-01-o-alerta': 2500},
  });
  await initMissionsApp(doc);
  await openMission('bcb-ef-t1-m01-choque-dos-juros', {resume: true});

  const main = doc.getElementById('missions-main');
  const video1 = doc.getElementById('active-scene-video');
  assert.ok(video1);

  // Trigger loadedmetadata -> restores saved position (2.5s) and creates hidden <video preload="metadata"> for Scene 2
  video1.dispatchEvent({type: 'loadedmetadata'});
  assert.equal(video1.currentTime, 2.5);
  const preloadedWarmVideos = doc.createdElements.filter(
    el => el.tagName === 'VIDEO' && el.preload === 'metadata'
  );
  assert.equal(preloadedWarmVideos.length, 1);
  assert.equal(preloadedWarmVideos[0].src, '/api/v1/missions/media/asset-bcb-m01-v02-problema');

  // Toggle real autoAdvBtn on Scene 1 (where auto_advance_on_video_end === true)
  const autoAdvBtn = findButton(main, 'Avanço automático: Ligado');
  assert.ok(autoAdvBtn, 'Scene 1 must render the auto-advance toggle button');
  autoAdvBtn.click();
  assert.equal(autoAdvBtn.textContent, 'Avanço automático: Pausado');
  assert.equal(getMissionsState().autoAdvanceEnabled, false);
  assert.strictEqual(doc.getElementById('active-scene-video'), video1, 'Toggling autoAdvBtn must never recreate <video>');

  // Video ended while auto-advance is paused must stay on Scene 1
  video1.ended = true;
  video1.dispatchEvent({type: 'ended'});
  await flush();
  assert.equal(getMissionsState().activeSceneId, 'cena-01-o-alerta');
  assert.match(main.textContent, /↺ Rever cena/);

  // Re-enable autoAdvBtn and exercise play/pause, scrubber, mute, volume, captions, and transcript in-place
  autoAdvBtn.click();
  assert.equal(autoAdvBtn.textContent, 'Avanço automático: Ligado');
  findButton(main, '↺ Rever cena').click(); // replays from 0
  assert.equal(video1.currentTime, 0);
  assert.equal(video1.paused, false);
  findButton(main, '⏸ Pausar').click();
  assert.equal(video1.paused, true);

  const scrubber = main.querySelector('.scrubber-input');
  scrubber.value = '40';
  scrubber.dispatchEvent({type: 'input', target: scrubber});
  assert.equal(video1.currentTime, 4);
  video1.dispatchEvent({type: 'timeupdate'});
  assert.match(main.textContent, /00:04 \/ 00:10/);

  const muteBtn = findButton(main, '🔊 Áudio');
  muteBtn.click();
  assert.equal(video1.muted, true);
  assert.equal(muteBtn.textContent, '🔇 Mudo');

  const volSlider = main.querySelector('.volume-input');
  volSlider.value = '65';
  volSlider.dispatchEvent({type: 'input', target: volSlider});
  assert.equal(video1.volume, 0.65);
  assert.equal(video1.muted, false);

  findButton(main, 'CC Legendas').click();
  assert.equal(video1.textTracks[0].mode, 'hidden');
  const transBtn = findButton(main, 'Transcrição PT-BR');
  transBtn.click();
  assert.equal(transBtn.textContent, 'Ocultar transcrição');
  transBtn.click();
  assert.equal(transBtn.textContent, 'Transcrição PT-BR');
  assert.strictEqual(doc.getElementById('active-scene-video'), video1);

  // Exiting pauses and clears BOTH active video AND preloaded metadata video
  await exitMissionToCatalog();
  assert.equal(video1.paused, true);
  assert.equal(video1.src, '');
  assert.equal(preloadedWarmVideos[0].src, '');
});

// --- 7. SVG Geometric Coordinates, Zero-Division Guard & Dynamic Macaulay Duration Labels ---

test('SVG Present Value chart updates cx/cy/x/y monotonically, guards maxRate === minRate, and Duration chart renders dynamic macaulayDuration labels', async () => {
  const {doc} = setupMissionsTestEnv({
    authenticatedProfile: {user_id: 91, display_name: ' Carla ', is_guest: false, is_admin: false},
    initialStatus: 'COMPLETED',
    initialCompletedScenes: [
      'cena-01-o-alerta',
      'cena-02-o-problema',
      'cena-03-primeira-decisao',
      'cena-04-explicacao',
      'cena-05-desafio-duration',
    ],
    overrideVisualParams: {
      'cena-05-desafio-duration': {
        short_maturity_years: 2,
        long_maturity_years: 8,
        initial_rate_pct: 10,
        shocked_rate_pct: 13,
      },
    },
  });
  await initMissionsApp(doc);
  await openMission('bcb-ef-t1-m01-choque-dos-juros', {resume: true});
  setMissionsStateForTest({showConclusion: false, activeSceneId: 'cena-04-explicacao'});
  renderMissionsView();

  const main = doc.getElementById('missions-main');
  const simCircle = doc.getElementById('sim-rate-point');
  const simCallout = doc.getElementById('sim-rate-callout');
  const slider = doc.getElementById('sim-rate-range');
  assert.ok(simCircle && simCallout && slider);

  slider.value = '6';
  slider.dispatchEvent({type: 'input', target: slider});
  const cx6 = Number(simCircle.getAttribute('cx'));
  const cy6 = Number(simCircle.getAttribute('cy'));
  const x6 = Number(simCallout.getAttribute('x'));
  const y6 = Number(simCallout.getAttribute('y'));

  slider.value = '14';
  slider.dispatchEvent({type: 'input', target: slider});
  const cx14 = Number(simCircle.getAttribute('cx'));
  const cy14 = Number(simCircle.getAttribute('cy'));

  slider.value = '18';
  slider.dispatchEvent({type: 'input', target: slider});
  const cx18 = Number(simCircle.getAttribute('cx'));
  const cy18 = Number(simCircle.getAttribute('cy'));
  const x18 = Number(simCallout.getAttribute('x'));
  const y18 = Number(simCallout.getAttribute('y'));

  for (const num of [cx6, cy6, x6, y6, cx14, cy14, cx18, cy18, x18, y18]) {
    assert.ok(Number.isFinite(num), `SVG coordinate ${num} must be finite`);
  }
  assert.ok(cx6 < cx14 && cx14 < cx18, 'Higher interest rate must move point right along X axis');
  assert.ok(cy6 < cy14 && cy14 < cy18, 'Higher interest rate lowers PV price, increasing SVG Y coordinate');

  // Degenerate min_rate_pct === max_rate_pct must never emit NaN/Infinity in SVG
  getMissionsState().activeDetail.mission.scenes[3].native_visual.parameters = {
    face_value_brl: 1000,
    maturity_years: 3,
    initial_rate_pct: 10,
    shocked_rate_pct: 10,
    min_rate_pct: 10,
    max_rate_pct: 10,
  };
  renderMissionsView();
  const degSvgHtml = main.querySelector('.visual-card svg').serializeHtml();
  assert.doesNotMatch(degSvgHtml, /NaN|Infinity/);

  // Jump to Scene 5 and answer decision -> verify dynamic (D = 2) and (D = 8) labels from macaulayDuration
  setMissionsStateForTest({activeSceneId: 'cena-05-desafio-duration'});
  renderMissionsView();
  findButton(main, 'Título com vencimento em cinco anos.').click();
  await flush();

  const durationTexts = main.querySelectorAll('.visual-card svg text').map(t => t.textContent);
  assert.ok(durationTexts.some(t => t.includes('Zero Cupom 2 anos (D = 2)')), 'Must render dynamic short Macaulay duration D = 2');
  assert.ok(durationTexts.some(t => t.includes('Zero Cupom 8 anos (D = 8)')), 'Must render dynamic long Macaulay duration D = 8');
});

// --- 8. Auth Flows (Login / Register / Logout / 401), #nav-admin-link & Media Stop Guarantees ---

test('web auth supports register/login (student_web), shows #nav-admin-link only for admins, and stops video on advance, step-dot, logout, and 401', async () => {
  let force401 = false;
  let adminFlag = false;
  let failAuth = null;

  const {doc, calls} = setupMissionsTestEnv({
    initialStatus: 'IN_PROGRESS',
    initialCompletedScenes: ['cena-01-o-alerta', 'cena-02-o-problema'],
    initialSceneId: 'cena-02-o-problema',
    beforeRequest: async (url, method, body, respond) => {
      if ((url === '/api/v1/auth/login' || url === '/api/v1/auth/register') && failAuth) {
        return respond(401, {message: failAuth});
      }
      if (url === '/api/v1/auth/register' && method === 'POST') {
        return respond(200, {
          authenticated: true,
          user_id: '501',
          profile: {
            user_id: 501,
            email: body.email,
            display_name: body.display_name,
            is_guest: false,
            is_admin: adminFlag,
          },
          expires_in_seconds: 43200,
        });
      }
      if (url === '/api/v1/auth/login' && method === 'POST') {
        return respond(200, {
          authenticated: true,
          user_id: '502',
          profile: {
            user_id: 502,
            email: body.email,
            display_name: 'Coordenação',
            is_guest: false,
            is_admin: adminFlag,
          },
          expires_in_seconds: 43200,
        });
      }
      if (force401 && !url.startsWith('/api/v1/auth/')) {
        return respond(401, {code: 'UNAUTHORIZED', message: 'Sessão expirada'});
      }
      return null;
    },
  });

  await initMissionsApp(doc);

  // Switch to Register tab -> submit real form click -> verifies student_web + display_name
  doc.getElementById('tab-register').click();
  assert.equal(doc.getElementById('auth-name-wrap').hidden, false);
  assert.equal(doc.getElementById('auth-password').getAttribute('autocomplete'), 'new-password');

  doc.getElementById('auth-name').value = '  Marina Costa  ';
  doc.getElementById('auth-email').value = 'marina@exemplo.com';
  doc.getElementById('auth-password').value = 'SenhaForte123!';
  doc.getElementById('auth-submit').click();
  await flush();

  const regCall = calls.find(c => c.url === '/api/v1/auth/register');
  assert.ok(regCall);
  assert.deepEqual(JSON.parse(regCall.opts.body), {
    email: 'marina@exemplo.com',
    password: 'SenhaForte123!',
    client: 'student_web',
    display_name: 'Marina Costa',
  });
  assert.equal(doc.getElementById('auth-password').value, '');
  assert.equal(doc.getElementById('nav-admin-link').hidden, true, 'Non-admin must never see #nav-admin-link');

  // Open mission -> verify video stops on step-dot jump AND narrative_progress updates from POST /progress
  await openMission('bcb-ef-t1-m01-choque-dos-juros', {resume: true});
  const main = doc.getElementById('missions-main');
  const videoScene2 = doc.getElementById('active-scene-video');
  await videoScene2.play();
  videoScene2.dispatchEvent({type: 'loadedmetadata'});

  const dotScene1 = main.querySelectorAll('.step-dot')[0];
  dotScene1.click();
  await flush();
  assert.equal(videoScene2.paused, true);
  assert.equal(videoScene2.src, '');
  assert.equal(getMissionsState().activeDetail.narrative_progress.current_scene_id, 'cena-01-o-alerta');

  // Advance from Scene 1 stops Scene 1 video before rendering Scene 2
  const videoScene1 = doc.getElementById('active-scene-video');
  await videoScene1.play();
  findButton(main, 'Avançar para a próxima cena').click();
  await flush();
  assert.equal(videoScene1.paused, true);
  assert.equal(videoScene1.src, '');

  // Logout stops active video and resets dirty state (simulatedRatePct, practiceState, showConclusion, notice)
  const videoBeforeLogout = doc.getElementById('active-scene-video');
  await videoBeforeLogout.play();
  setMissionsStateForTest({simulatedRatePct: 19, practiceState: {questions: []}, showConclusion: true});
  doc.getElementById('logout-btn').click();
  await flush();

  assert.equal(videoBeforeLogout.paused, true);
  assert.equal(videoBeforeLogout.src, '');
  assert.equal(getMissionsState().profile, null);
  assert.equal(getMissionsState().simulatedRatePct, 14);
  assert.equal(getMissionsState().practiceState, null);
  assert.equal(getMissionsState().showConclusion, false);
  assert.equal(getMissionsState().notice, '');
  assert.equal(doc.getElementById('auth-screen').hidden, false);

  // Failed login shows pt-BR error in #auth-status, then admin login reveals #nav-admin-link
  doc.getElementById('tab-login').click();
  failAuth = 'E-mail ou senha incorretos';
  doc.getElementById('auth-email').value = 'admin@cogniva.local';
  doc.getElementById('auth-password').value = 'errada123';
  doc.getElementById('auth-submit').click();
  await flush();
  assert.match(doc.getElementById('auth-status').textContent, /E-mail ou senha incorretos/);

  failAuth = null;
  adminFlag = true;
  doc.getElementById('auth-password').value = 'CorretaAdmin123!';
  doc.getElementById('auth-submit').click();
  await flush();
  assert.equal(doc.getElementById('nav-admin-link').hidden, false, 'Admin user must see #nav-admin-link');

  // Mid-session 401 stops video and resets transient state immediately
  await openMission('bcb-ef-t1-m01-choque-dos-juros', {resume: true});
  const videoBefore401 = doc.getElementById('active-scene-video');
  await videoBefore401.play();
  setMissionsStateForTest({simulatedRatePct: 17.5, practiceState: {questions: []}});
  force401 = true;
  await assert.rejects(() => loadCatalog(), {status: 401});
  assert.equal(videoBefore401.paused, true);
  assert.equal(videoBefore401.src, '');
  assert.equal(getMissionsState().profile, null);
  assert.equal(getMissionsState().simulatedRatePct, 14);
  assert.equal(getMissionsState().practiceState, null);
  assert.equal(getMissionsState().notice, '');
  assert.equal(doc.getElementById('auth-screen').hidden, false);
  assert.equal(doc.getElementById('missions-shell').hidden, true);
});

// --- 9. Deep-Link #missao/..., Decision-Gated Transcript Fallback, Concept Revisit & Repeat Mission ---

test('deep-link #missao/... opens player directly, decision scenes enforce choice in Transcript Fallback Mode, and Repeat resets mission', async () => {
  const {doc, calls} = setupMissionsTestEnv({
    authenticatedProfile: {user_id: 95, display_name: 'Diego', is_guest: false, is_admin: false},
    initialHash: '#missao/bcb-ef-t1-m01-choque-dos-juros',
    initialStatus: 'IN_PROGRESS',
    initialCompletedScenes: ['cena-01-o-alerta', 'cena-02-o-problema'],
    initialSceneId: 'cena-03-primeira-decisao',
  });

  await initMissionsApp(doc);
  const main = doc.getElementById('missions-main');

  // Booted straight into Scene 3 via #missao/bcb-ef-t1-m01-choque-dos-juros
  assert.equal(getMissionsState().activeSceneId, 'cena-03-primeira-decisao');
  assert.match(main.textContent, /Cena 3 — A Primeira Decisão/);

  // Video error on decision-gated Scene 3 -> activates Transcript Fallback WITHOUT bypass button
  doc.getElementById('active-scene-video').dispatchEvent({type: 'error'});
  assert.match(main.textContent, /Modo Transcrição Ativo/);
  assert.equal(
    findButton(main, 'Prosseguir com transcrição'),
    null,
    'Decision-required scene must never allow bypassing the decision via transcript button'
  );

  // Retry button restores video player, then re-trigger error and answer decision with used_transcript_fallback: true
  findButton(main, 'Tentar carregar vídeo novamente').click();
  assert.equal(getMissionsState().videoErrored, false);
  assert.ok(doc.getElementById('active-scene-video'));

  doc.getElementById('active-scene-video').dispatchEvent({type: 'error'});
  findButton(main, 'O preço aumenta.').click();
  await flush();

  const decisionCall = calls.find(c => c.url.endsWith('/decisions'));
  assert.equal(JSON.parse(decisionCall.opts.body).used_transcript_fallback, true);
  assert.ok(findButton(main, 'Continuar para a próxima cena'), 'Answering decision unlocks scene progression');

  // Walk to Scene 4, trigger video error on non-decision scene -> "Prosseguir com transcrição →" advances to Scene 5
  findButton(main, 'Continuar para a próxima cena').click();
  await flush();
  doc.getElementById('active-scene-video').dispatchEvent({type: 'error'});
  const proceedTranscriptBtn = findButton(main, 'Prosseguir com transcrição');
  assert.ok(proceedTranscriptBtn, 'Non-decision scene must allow advancing via transcript fallback');
  proceedTranscriptBtn.click();
  await flush();
  assert.equal(getMissionsState().activeSceneId, 'cena-05-desafio-duration');

  findButton(main, 'Título com vencimento em cinco anos.').click();
  await flush();
  findButton(main, 'Ver conclusão da missão').click();
  await flush();

  assert.equal(getMissionsState().showConclusion, true);
  const revisitBtn = findButton(main, 'Revisitar cena deste conceito');
  assert.ok(revisitBtn);
  revisitBtn.click();
  assert.equal(getMissionsState().showConclusion, false);

  setMissionsStateForTest({showConclusion: true});
  renderMissionsView();
  findButton(main, 'Repetir missão').click();
  await flush();

  assert.ok(calls.some(c => c.url.endsWith('/repeat') && c.opts.method === 'POST'));
  assert.equal(getMissionsState().showConclusion, false);
  assert.equal(getMissionsState().activeSceneId, 'cena-01-o-alerta');
  assert.equal(getMissionsState().activeDetail.narrative_progress.repeat_count, 1);

  // Topbar nav-missions-btn exits back to catalog cleanly
  doc.getElementById('nav-missions-btn').click();
  await flush();
  assert.equal(getMissionsState().activeDetail, null);
});
