import {
  buildDurationComparisonSummary,
  buildPresentValueCurvePoints,
  canAccessScene,
  formatBrl,
  formatClockSeconds,
  formatSignedPct,
  presentValueZeroCoupon,
  priceChangePct,
  shouldAutoAdvanceScene,
} from './domain.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
const GUEST_STORAGE_KEY = 'cogniva_missions_guest_v1';

let state = {
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
};

let boundDoc = null;
let sceneEnteredAtMs = Date.now();
let preloadedMediaUrls = new Set();
let preloadedVideoElements = [];

export function getMissionsState() {
  return state;
}

export function setMissionsStateForTest(patch) {
  state = {...state, ...patch};
}

export function h(tag, attrs = {}, ...children) {
  const doc = boundDoc || document;
  const el = doc.createElement(tag);
  for (const [key, val] of Object.entries(attrs || {})) {
    if (key.startsWith('on') && typeof val === 'function') {
      el.addEventListener(key.slice(2).toLowerCase(), val);
    } else if (key === 'class') {
      el.className = val;
    } else if (key === 'value') {
      el.value = String(val ?? '');
    } else if (['checked', 'disabled', 'selected', 'hidden', 'muted', 'controls', 'default'].includes(key)) {
      el[key] = Boolean(val);
      if (val) el.setAttribute(key, '');
    } else if (val !== false && val != null) {
      el.setAttribute(key, String(val));
    }
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child.nodeType ? child : doc.createTextNode(String(child)));
  }
  return el;
}

export function svg(tag, attrs = {}, ...children) {
  const doc = boundDoc || document;
  const el = doc.createElementNS
    ? doc.createElementNS(SVG_NS, tag)
    : doc.createElement(tag);
  for (const [key, val] of Object.entries(attrs || {})) {
    if (val !== false && val != null) {
      el.setAttribute(key, String(val));
    }
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child.nodeType ? child : doc.createTextNode(String(child)));
  }
  return el;
}

function clearNode(node) {
  while (node && node.firstChild) {
    node.removeChild(node.firstChild);
  }
}

function setNotice(message = '') {
  state.notice = message;
  const banner = (boundDoc || document).getElementById('missions-notice');
  if (!banner) return;
  banner.textContent = message;
  banner.hidden = !message;
}

function randomHex(bytesLen) {
  const arr = new Uint8Array(bytesLen);
  crypto.getRandomValues(arr);
  return Array.from(arr, b => b.toString(16).padStart(2, '0')).join('');
}

function getOrCreateGuestCredentials() {
  try {
    const raw = window.localStorage.getItem(GUEST_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed?.installation_id && parsed?.installation_secret?.length === 64) {
        return parsed;
      }
    }
    const fresh = {
      installation_id: crypto.randomUUID(),
      installation_secret: randomHex(32),
    };
    window.localStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(fresh));
    return fresh;
  } catch {
    return null;
  }
}

export async function api(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const headers = {'Accept': 'application/json', ...(options.headers || {})};
  if (method !== 'GET' && method !== 'HEAD') {
    headers['X-Cogniva-CSRF'] = '1';
  }
  let body = options.body;
  if (body !== undefined && typeof body !== 'string') {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const resp = await fetch(path, {
    method,
    headers,
    body,
    credentials: 'same-origin',
  });
  let payload = null;
  const text = await resp.text();
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = {message: text};
    }
  }
  if (!resp.ok) {
    if (resp.status === 401 && !path.startsWith('/api/v1/auth/')) {
      stopActiveVideo();
      state.profile = null;
      state.catalog = null;
      state.activeDetail = null;
      state.activeSceneId = null;
      state.showConclusion = false;
      state.transcriptOpen = false;
      state.userIntervenedInScene = false;
      state.videoErrored = false;
      state.simulatedRatePct = 14;
      state.practiceState = null;
      state.busy = false;
      setNotice('');
      clearNode((boundDoc || document).getElementById('missions-main'));
      syncShellVisibility();
    }
    const msg = payload?.message || payload?.detail || `Erro HTTP ${resp.status}`;
    const err = new Error(msg);
    err.status = resp.status;
    err.code = payload?.code || null;
    throw err;
  }
  return payload;
}

function syncShellVisibility() {
  const doc = boundDoc || document;
  const authScreen = doc.getElementById('auth-screen');
  const shell = doc.getElementById('missions-shell');
  const userBadge = doc.getElementById('user-badge');
  const adminLink = doc.getElementById('nav-admin-link');
  if (!authScreen || !shell) return;

  const loggedIn = Boolean(state.profile);
  authScreen.hidden = loggedIn;
  shell.hidden = !loggedIn;

  if (loggedIn && userBadge) {
    const label = state.profile.display_name || state.profile.email || 'Estudante';
    userBadge.textContent = state.profile.is_guest ? `${label} · Convidado` : label;
  }
  if (adminLink) {
    adminLink.hidden = !state.profile?.is_admin;
  }
}

function stopActiveVideo() {
  const doc = boundDoc || document;
  const videoEl = doc.getElementById('active-scene-video');
  if (videoEl) {
    try { videoEl.pause?.(); } catch {}
    try { videoEl.removeAttribute?.('src'); } catch {}
    videoEl.src = '';
    try { videoEl.load?.(); } catch {}
  }
  for (const warm of preloadedVideoElements) {
    try { warm.pause?.(); } catch {}
    try { warm.removeAttribute?.('src'); } catch {}
    warm.src = '';
    try { warm.load?.(); } catch {}
    try { warm.remove?.(); } catch {}
  }
  preloadedVideoElements = [];
  preloadedMediaUrls.clear();
}

function triggerModerateNextPreload(url) {
  if (!url || preloadedMediaUrls.has(url)) return;
  const doc = boundDoc || document;
  if (!doc?.head) return;
  preloadedMediaUrls.add(url);
  // Lightweight metadata warm-up via hidden <video preload="metadata"> without downloading entire file
  const warm = doc.createElement('video');
  warm.preload = 'metadata';
  warm.muted = true;
  warm.src = url;
  warm.setAttribute('aria-hidden', 'true');
  warm.hidden = true;
  warm.load?.();
  preloadedVideoElements.push(warm);
}

function sendTelemetrySilently(missionId, eventType, sceneId = null, detail = null) {
  if (!missionId) return;
  api(`/api/v1/missions/${encodeURIComponent(missionId)}/telemetry`, {
    method: 'POST',
    body: {event_type: eventType, scene_id: sceneId, detail},
  }).catch(() => {});
}

export async function loadCatalog() {
  const catalog = await api('/api/v1/missions');
  state.catalog = catalog;
  return catalog;
}

export async function openMission(missionId, {resume = true, allowWhenBusy = false} = {}) {
  if (state.busy && !allowWhenBusy) return;
  stopActiveVideo();
  setNotice('');
  state.busy = true;
  try {
    const detail = await api(
      `/api/v1/missions/${encodeURIComponent(missionId)}${resume ? '?resume=true' : ''}`
    );
    state.activeDetail = detail;
    const progress = detail.narrative_progress;
    const scenes = detail.mission.scenes || [];
    state.activeSceneId = progress?.current_scene_id || scenes[0]?.scene_id || null;
    state.showConclusion = progress?.status === 'COMPLETED' && !resume;
    state.transcriptOpen = false;
    state.userIntervenedInScene = false;
    state.videoErrored = false;
    state.practiceState = null;
    sceneEnteredAtMs = Date.now();

    if (typeof window !== 'undefined' && window.history?.replaceState) {
      window.history.replaceState(null, '', `/missoes/#missao/${encodeURIComponent(missionId)}`);
    }

    // Record mission start on first visit so status transitions to IN_PROGRESS immediately
    if (progress?.status === 'NOT_STARTED' && state.activeSceneId) {
      const started = await api(`/api/v1/missions/${encodeURIComponent(missionId)}/progress`, {
        method: 'POST',
        body: {current_scene_id: state.activeSceneId},
      });
      state.activeDetail.narrative_progress = started.narrative_progress;
      state.activeDetail.interaction_performance = started.interaction_performance;
    }

    state.busy = false;
    renderMissionsView();
  } catch (err) {
    if (err.status !== 401) {
      setNotice(err.message || 'Não foi possível carregar a missão.');
    }
  } finally {
    if (!allowWhenBusy) {
      state.busy = false;
    }
  }
}

export async function exitMissionToCatalog() {
  if (state.busy) return;
  const doc = boundDoc || document;
  const videoEl = doc.getElementById('active-scene-video');
  const currentPosMs = videoEl && Number.isFinite(videoEl.currentTime)
    ? Math.round(videoEl.currentTime * 1000)
    : null;
  stopActiveVideo();

  if (state.activeDetail && state.activeSceneId) {
    try {
      await api(
        `/api/v1/missions/${encodeURIComponent(state.activeDetail.mission.mission_id)}/progress`,
        {
          method: 'POST',
          body: {
            current_scene_id: state.activeSceneId,
            playback_position_ms: currentPosMs,
          },
        }
      );
    } catch {
      // Preserve local navigation even on transient network blip
    }
  }
  state.activeDetail = null;
  state.activeSceneId = null;
  state.showConclusion = false;
  state.practiceState = null;
  if (typeof window !== 'undefined' && window.history?.replaceState) {
    window.history.replaceState(null, '', '/missoes/');
  }
  await loadCatalog().catch(() => {});
  renderMissionsView();
}

async function advanceFromScene(scene, {viaTranscript = false} = {}) {
  if (!state.activeDetail || !scene || state.busy) return;
  const missionId = state.activeDetail.mission.mission_id;
  state.busy = true;
  stopActiveVideo();
  setNotice('');
  try {
    const isLastScene = !scene.next_scene_id;
    const body = {
      completed_scene_id: scene.scene_id,
      current_scene_id: scene.next_scene_id || scene.scene_id,
      used_transcript_fallback: viaTranscript,
    };
    if (isLastScene) {
      body.mark_mission_completed = true;
    }
    const updated = await api(`/api/v1/missions/${encodeURIComponent(missionId)}/progress`, {
      method: 'POST',
      body,
    });
    if (!state.activeDetail) return;
    state.activeDetail.narrative_progress = updated.narrative_progress;
    state.activeDetail.interaction_performance = updated.interaction_performance;
    scene.is_completed = true;

    if (!isLastScene) {
      state.activeSceneId = scene.next_scene_id;
      state.transcriptOpen = false;
      state.userIntervenedInScene = false;
      state.videoErrored = false;
      sceneEnteredAtMs = Date.now();
    } else {
      state.showConclusion = true;
    }
    state.busy = false;
    renderMissionsView();
  } catch (err) {
    if (err.status !== 401) {
      setNotice(err.message || 'Não foi possível avançar para a próxima cena.');
    }
  } finally {
    state.busy = false;
  }
}

async function selectDecisionOption(scene, interaction, optionId) {
  if (!state.activeDetail || state.busy) return;
  const missionId = state.activeDetail.mission.mission_id;
  state.busy = true;
  setNotice('');
  try {
    const elapsed = Math.max(0, Date.now() - sceneEnteredAtMs);
    const res = await api(`/api/v1/missions/${encodeURIComponent(missionId)}/decisions`, {
      method: 'POST',
      body: {
        scene_id: scene.scene_id,
        interaction_id: interaction.interaction_id,
        selected_option_id: optionId,
        time_spent_ms: elapsed,
        used_transcript_fallback: state.videoErrored,
      },
    });
    if (!state.activeDetail) return;
    interaction.answered = res.decision;
    scene.is_completed = true;
    state.activeDetail.narrative_progress = res.narrative_progress;
    state.activeDetail.interaction_performance = res.interaction_performance;
    state.activeDetail.mission.worked_concepts = res.worked_concepts;
    state.busy = false;
    renderMissionsView();
  } catch (err) {
    if (err.status !== 401) {
      setNotice(err.message || 'Falha ao registrar sua decisão.');
    }
  } finally {
    state.busy = false;
  }
}

async function repeatActiveMission() {
  if (!state.activeDetail || state.busy) return;
  const missionId = state.activeDetail.mission.mission_id;
  state.busy = true;
  stopActiveVideo();
  setNotice('');
  try {
    await api(`/api/v1/missions/${encodeURIComponent(missionId)}/repeat`, {method: 'POST'});
    await openMission(missionId, {resume: false, allowWhenBusy: true});
  } catch (err) {
    if (err.status !== 401) {
      setNotice(err.message || 'Não foi possível reiniciar a missão.');
    }
  } finally {
    state.busy = false;
  }
}

async function openRelatedHumanQuestions() {
  if (!state.activeDetail || state.busy) return;
  const missionId = state.activeDetail.mission.mission_id;
  state.busy = true;
  setNotice('');
  try {
    const res = await api(
      `/api/v1/missions/${encodeURIComponent(missionId)}/practice-questions`,
      {
        method: 'POST',
        body: {quantity: 5},
      }
    );
    if (!state.activeDetail) return;
    state.practiceState = {
      availableCount: res.available_count || 0,
      questions: res.questions || [],
      emptyMessage: res.empty_state_message || null,
      index: 0,
      selectedIndex: null,
      confidence: 2,
      declaredConfidence: null,
      confidenceExplicit: false,
      submittedResult: null,
      questionStartedAtMs: Date.now(),
    };
    state.busy = false;
    renderMissionsView();
  } catch (err) {
    if (err.status !== 401) {
      setNotice(err.message || 'Não foi possível consultar as questões do acervo.');
    }
  } finally {
    state.busy = false;
  }
}

async function submitRealPracticeAnswer() {
  if (state.busy) return;
  const ps = state.practiceState;
  if (!ps || ps.selectedIndex == null || !ps.questions[ps.index]) return;
  const q = ps.questions[ps.index];
  const chosenIdx = ps.selectedIndex;
  const correctIndices = q.correct_answer_indices || [];
  const isCorrect = correctIndices.includes(chosenIdx);
  const spentSec = Math.max(1, Math.round((Date.now() - ps.questionStartedAtMs) / 1000));

  state.busy = true;
  setNotice('');
  const confirmBtn = (boundDoc || document).getElementById('confirm-practice-btn');
  if (confirmBtn) confirmBtn.disabled = true;
  try {
    const batchResp = await api('/api/v1/quiz/submit-batch', {
      method: 'POST',
      body: {
        attempts: [
          {
            attempt_id: crypto.randomUUID(),
            occurred_at: new Date().toISOString(),
            client_answered_at: new Date().toISOString(),
            question_revision: q.question_revision || q.content_revision || '',
            question_id: q.id,
            chosen_index: chosenIdx,
            chosen_text: q.answers?.[chosenIdx] || '',
            is_correct: isCorrect,
            time_spent_seconds: spentSec,
            confidence_level: ps.confidence,
            confidence_explicit: Boolean(ps.confidenceExplicit),
            declared_confidence: ps.confidenceExplicit ? ps.declaredConfidence : null,
            session_uuid: crypto.randomUUID(),
            quiz_mode: 'STANDARD',
            canonical_subject_id: q.canonical_subject_id || 'MFIN',
            canonical_topic_id: q.canonical_topic_id || null,
          },
        ],
      },
    });
    if (
      (Array.isArray(batchResp?.rejected) && batchResp.rejected.length > 0) ||
      (typeof batchResp?.processed_count === 'number' && batchResp.processed_count < 1)
    ) {
      throw new Error(
        batchResp?.rejected?.[0]?.message ||
          'A resposta formal foi rejeitada pelo servidor. Tente novamente.'
      );
    }
    ps.submittedResult = {
      isCorrect,
      ack: batchResp?.results?.[0] || null,
    };
    // Refresh independent demonstrated mastery from server
    if (state.activeDetail) {
      const refreshed = await api(
        `/api/v1/missions/${encodeURIComponent(state.activeDetail.mission.mission_id)}`
      );
      if (state.activeDetail) {
        state.activeDetail.demonstrated_mastery = refreshed.demonstrated_mastery;
      }
    }
    state.busy = false;
    renderMissionsView();
  } catch (err) {
    const btn = (boundDoc || document).getElementById('confirm-practice-btn') || confirmBtn;
    if (btn) btn.disabled = false;
    if (err.status !== 401) {
      setNotice(err.message || 'Falha ao registrar tentativa no acervo.');
    }
  } finally {
    state.busy = false;
  }
}

function renderPresentValueVisual(visual) {
  const params = visual.parameters || {};
  const faceValue = Number(params.face_value_brl ?? 1000);
  const years = Number(params.maturity_years ?? 3);
  const initialRate = Number(params.initial_rate_pct ?? 10);
  const minRate = Number(params.min_rate_pct ?? 4);
  const maxRate = Number(params.max_rate_pct ?? 20);
  const clampSimRate = val => Math.min(maxRate, Math.max(minRate, Number(val)));
  const simRate = clampSimRate(state.simulatedRatePct || params.shocked_rate_pct || 14);

  const points = buildPresentValueCurvePoints(params);
  const width = 620;
  const height = 220;
  const padL = 62;
  const padR = 24;
  const padT = 22;
  const padB = 36;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const prices = points.map(p => p.priceBrl);
  const minP = Math.floor(Math.min(...prices) * 0.96);
  const maxP = Math.ceil(Math.max(...prices) * 1.02);

  const rateSpan = maxRate - minRate;
  const xOf = rate => (rateSpan > 0 ? padL + ((rate - minRate) / rateSpan) * plotW : padL + plotW / 2);
  const yOf = price => padT + (1 - (price - minP) / Math.max(1, maxP - minP)) * plotH;

  const pathD = points
    .map((p, idx) => `${idx === 0 ? 'M' : 'L'}${xOf(p.ratePct).toFixed(1)},${yOf(p.priceBrl).toFixed(1)}`)
    .join(' ');

  const initialPrice = presentValueZeroCoupon(faceValue, initialRate, years);
  const simPrice = presentValueZeroCoupon(faceValue, simRate, years);
  const deltaPct = priceChangePct(faceValue, initialRate, simRate, years);

  const simCircle = svg('circle', {
    id: 'sim-rate-point',
    cx: xOf(simRate).toFixed(1),
    cy: yOf(simPrice).toFixed(1),
    r: 6.5,
    fill: '#fbbf24',
  });
  const simText = svg(
    'text',
    {
      id: 'sim-rate-callout',
      x: Math.min(width - 175, Math.max(padL + 6, xOf(simRate) - 20)).toFixed(1),
      y: Math.min(height - padB - 8, yOf(simPrice) + 18).toFixed(1),
      fill: '#fef3c7',
      'font-size': 12,
    },
    `Taxa ${simRate.toFixed(1)}%: ${formatBrl(simPrice)}`
  );

  const chart = svg(
    'svg',
    {
      viewBox: `0 0 ${width} ${height}`,
      class: 'chart-svg',
      role: 'img',
      'aria-label': `Gráfico nativo da curva de valor presente entre ${minRate}% e ${maxRate}% ao ano`,
    },
    svg('line', {x1: padL, y1: height - padB, x2: width - padR, y2: height - padB, stroke: '#284166', 'stroke-width': 1.5}),
    svg('line', {x1: padL, y1: padT, x2: padL, y2: height - padB, stroke: '#284166', 'stroke-width': 1.5}),
    svg('path', {d: pathD, fill: 'none', stroke: '#38bdf8', 'stroke-width': 3}),
    svg('circle', {cx: xOf(initialRate).toFixed(1), cy: yOf(initialPrice).toFixed(1), r: 6, fill: '#4ade80'}),
    svg(
      'text',
      {x: (xOf(initialRate) + 8).toFixed(1), y: (yOf(initialPrice) - 8).toFixed(1), fill: '#dcfce7', 'font-size': 12},
      `Base ${initialRate}%: ${formatBrl(initialPrice)}`
    ),
    simCircle,
    simText,
    svg('text', {x: padL, y: height - 10, fill: '#cbd5e1', 'font-size': 11}, `${minRate}% a.a.`),
    svg('text', {x: width - padR - 58, y: height - 10, fill: '#cbd5e1', 'font-size': 11}, `${maxRate}% a.a.`)
  );

  const rateLabel = h(
    'label',
    {for: 'sim-rate-range'},
    `Simular taxa exigida pelo mercado: ${simRate.toFixed(1).replace('.', ',')}% a.a.`
  );
  const priceStrong = h(
    'strong',
    {},
    `Preço hoje: ${formatBrl(simPrice)} (${formatSignedPct(deltaPct)} vs. ${initialRate}% a.a.)`
  );

  return h(
    'section',
    {class: 'visual-card', 'aria-label': visual.title},
    h('span', {class: 'eyebrow'}, `GRÁFICO NATIVO DO APLICATIVO · ${visual.formula_label}`),
    h('h3', {}, visual.title),
    h('p', {}, visual.subtitle),
    h(
      'div',
      {class: 'rate-simulator-row'},
      rateLabel,
      h('input', {
        id: 'sim-rate-range',
        type: 'range',
        min: minRate,
        max: maxRate,
        step: '0.5',
        value: simRate,
        oninput: e => {
          const nextRate = clampSimRate(e.target.value);
          state.simulatedRatePct = nextRate;
          const nextPrice = presentValueZeroCoupon(faceValue, nextRate, years);
          const nextDelta = priceChangePct(faceValue, initialRate, nextRate, years);
          rateLabel.textContent = `Simular taxa exigida pelo mercado: ${nextRate.toFixed(1).replace('.', ',')}% a.a.`;
          priceStrong.textContent = `Preço hoje: ${formatBrl(nextPrice)} (${formatSignedPct(nextDelta)} vs. ${initialRate}% a.a.)`;
          simCircle.setAttribute('cx', xOf(nextRate).toFixed(1));
          simCircle.setAttribute('cy', yOf(nextPrice).toFixed(1));
          simText.setAttribute('x', Math.min(width - 175, Math.max(padL + 6, xOf(nextRate) - 20)).toFixed(1));
          simText.setAttribute('y', Math.min(height - padB - 8, yOf(nextPrice) + 18).toFixed(1));
          simText.textContent = `Taxa ${nextRate.toFixed(1)}%: ${formatBrl(nextPrice)}`;
        },
      }),
      priceStrong
    ),
    chart,
    h(
      'ul',
      {class: 'highlights-list'},
      ...(visual.highlights || []).map(item => h('li', {}, item))
    )
  );
}

function renderDurationComparisonVisual(visual) {
  const cmp = buildDurationComparisonSummary(visual.parameters || {});
  const maxAbsDrop = Math.max(1, Math.abs(cmp.shortBond.changePct), Math.abs(cmp.longBond.changePct));
  const shortBarW = Math.round((Math.abs(cmp.shortBond.changePct) / maxAbsDrop) * 320);
  const longBarW = Math.round((Math.abs(cmp.longBond.changePct) / maxAbsDrop) * 320);

  const shortUnit = cmp.shortBond.years === 1 ? 'ano' : 'anos';
  const longUnit = cmp.longBond.years === 1 ? 'ano' : 'anos';

  const chart = svg(
    'svg',
    {
      viewBox: '0 0 620 170',
      class: 'chart-svg',
      role: 'img',
      'aria-label': `Comparativo nativo da queda percentual entre título zero cupom de ${cmp.shortBond.years} ${shortUnit} e de ${cmp.longBond.years} ${longUnit}`,
    },
    svg('text', {x: 20, y: 48, fill: '#f8fafc', 'font-size': 13, 'font-weight': 'bold'}, `Zero Cupom ${cmp.shortBond.years} ${shortUnit} (D = ${cmp.shortBond.macaulayDuration})`),
    svg('rect', {x: 210, y: 30, width: Math.max(12, shortBarW), height: 26, rx: 6, fill: '#38bdf8'}),
    svg(
      'text',
      {x: 220 + Math.max(12, shortBarW), y: 48, fill: '#cbd5e1', 'font-size': 13},
      `${formatSignedPct(cmp.shortBond.changePct)} (${formatBrl(cmp.shortBond.initialPrice)} → ${formatBrl(cmp.shortBond.shockedPrice)})`
    ),
    svg('text', {x: 20, y: 112, fill: '#f8fafc', 'font-size': 13, 'font-weight': 'bold'}, `Zero Cupom ${cmp.longBond.years} ${longUnit} (D = ${cmp.longBond.macaulayDuration})`),
    svg('rect', {x: 210, y: 94, width: Math.max(12, longBarW), height: 26, rx: 6, fill: '#fbbf24'}),
    svg(
      'text',
      {x: 220 + Math.max(12, longBarW), y: 112, fill: '#fef3c7', 'font-size': 13, 'font-weight': 'bold'},
      `${formatSignedPct(cmp.longBond.changePct)} (${formatBrl(cmp.longBond.initialPrice)} → ${formatBrl(cmp.longBond.shockedPrice)})`
    ),
    svg(
      'text',
      {x: 20, y: 152, fill: '#cbd5e1', 'font-size': 11.5},
      `Mesmo choque paralelo: taxa de ${cmp.initialRate}% a.a. para ${cmp.shockedRate}% a.a. sobre VF = ${formatBrl(cmp.faceValue)}`
    )
  );

  return h(
    'section',
    {class: 'visual-card', 'aria-label': visual.title},
    h('span', {class: 'eyebrow'}, `COMPARATIVO NATIVO · ${visual.formula_label}`),
    h('h3', {}, visual.title),
    h('p', {}, visual.subtitle),
    chart,
    h(
      'ul',
      {class: 'highlights-list'},
      ...(visual.highlights || []).map(item => h('li', {}, item))
    )
  );
}

function renderCatalogView() {
  const careers = state.catalog?.careers || [];
  if (careers.length === 0) {
    return h(
      'section',
      {class: 'season-block'},
      h('h1', {}, 'Catálogo de Missões'),
      h('p', {}, 'Nenhuma missão publicada no momento.')
    );
  }

  return h(
    'div',
    {},
    ...careers.map(career =>
      h(
        'section',
        {class: 'career-section', 'aria-labelledby': `career-${career.career_id}`},
        h(
          'div',
          {class: 'career-header'},
          h('span', {class: 'eyebrow'}, `${career.institution} · ${career.role}`),
          h('h1', {id: `career-${career.career_id}`}, career.display_header),
          h(
            'p',
            {class: 'career-subtitle'},
            'Episódios interativos curtos com decisões situacionais e vínculo direto ao edital.'
          )
        ),
        ...(career.disciplines || []).flatMap(disc =>
          (disc.seasons || []).map(season =>
            h(
              'div',
              {class: 'season-block'},
              h(
                'div',
                {class: 'season-header'},
                h('span', {class: 'eyebrow'}, `DISCIPLINA: ${disc.display_name} · TEMA: ${season.theme}`),
                h('h2', {}, season.title),
                h('p', {}, season.summary)
              ),
              ...(season.missions || []).map(m => {
                const prog = m.narrative_progress || {status: 'NOT_STARTED', status_label_pt: 'Não iniciada', progress_pct: 0, current_scene_order: 1};
                const perf = m.interaction_performance || {answered_decisions_count: 0, total_decisions_count: 0};
                const statusClass = `status-pill status-${String(prog.status || 'NOT_STARTED').toLowerCase()}`;
                const ctaLabel =
                  prog.status === 'IN_PROGRESS'
                    ? `Continuar missão (Cena ${prog.current_scene_order} de ${m.scenes_count})`
                    : prog.status === 'COMPLETED'
                      ? 'Revisitar missão'
                      : 'Iniciar missão';

                return h(
                  'article',
                  {class: 'mission-card', 'data-mission-id': m.mission_id},
                  h(
                    'div',
                    {class: 'mission-card-top'},
                    h('h3', {}, m.full_title),
                    h('span', {class: statusClass}, prog.status_label_pt)
                  ),
                  h('p', {}, m.description),
                  h(
                    'div',
                    {class: 'mission-meta-row'},
                    h('span', {}, `Disciplina: ${m.discipline_label}`),
                    h('span', {}, `Tema: ${m.theme_label}`),
                    h('span', {}, `Duração real: ${m.duration?.display_label || '~3 min'}`),
                    h('span', {}, `${m.scenes_count} cenas`)
                  ),
                  h(
                    'div',
                    {class: 'concept-chips', 'aria-label': 'Conceitos trabalhados'},
                    ...(m.worked_concepts || []).map(c =>
                      h('span', {class: 'concept-chip'}, c.label)
                    )
                  ),
                  h(
                    'div',
                    {class: 'mission-metrics-strip'},
                    h('span', {}, `Progresso narrativo: ${prog.progress_pct}%`),
                    h(
                      'span',
                      {},
                      `Decisões respondidas: ${perf.answered_decisions_count}/${perf.total_decisions_count} (formativas)`
                    )
                  ),
                  h(
                    'div',
                    {class: 'mission-card-actions'},
                    h(
                      'button',
                      {
                        type: 'button',
                        class: 'btn-primary',
                        onclick: () => openMission(m.mission_id, {resume: prog.status === 'IN_PROGRESS'}),
                      },
                      ctaLabel
                    ),
                    prog.status === 'COMPLETED' &&
                      h(
                        'button',
                        {
                          type: 'button',
                          class: 'btn-secondary',
                          onclick: () => openMission(m.mission_id, {resume: false}),
                        },
                        'Ver resumo e questões reais'
                      )
                  )
                );
              })
            )
          )
        )
      )
    )
  );
}

function renderPracticePanel() {
  const ps = state.practiceState;
  if (!ps) return null;

  if (!ps.questions || ps.questions.length === 0) {
    return h(
      'div',
      {class: 'practice-panel', role: 'region', 'aria-label': 'Questões reais do acervo'},
      h('h3', {}, 'Questões Reais do Acervo Cogniva'),
      h(
        'p',
        {},
        ps.emptyMessage ||
          'Nenhuma questão humana validada está disponível neste ambiente para os subtópicos vinculados.'
      )
    );
  }

  const q = ps.questions[ps.index];
  const submitted = ps.submittedResult;
  const correctSet = new Set(q.correct_answer_indices || []);

  return h(
    'div',
    {class: 'practice-panel', role: 'region', 'aria-label': 'Prática em questões reais do acervo'},
    h(
      'span',
      {class: 'eyebrow'},
      `AVALIAÇÃO FORMAL INDEPENDENTE · QUESTÃO ${ps.index + 1} DE ${ps.questions.length} (${ps.availableCount} NO ACERVO)`
    ),
    h('h3', {}, `${q.disciplina || 'Finanças'} · ${q.source || 'Acervo Cogniva'}`),
    h('p', {}, q.question),
    h(
      'div',
      {class: 'decision-options'},
      ...(q.answers || []).map((ansText, idx) => {
        let cls = 'option-button';
        if (submitted) {
          if (correctSet.has(idx)) cls += ' selected-correct';
          else if (ps.selectedIndex === idx) cls += ' selected-review';
        } else if (ps.selectedIndex === idx) {
          cls += ' selected-correct';
        }
        return h(
          'button',
          {
            type: 'button',
            class: cls,
            'aria-pressed': String(ps.selectedIndex === idx),
            disabled: Boolean(submitted),
            onclick: () => {
              ps.selectedIndex = idx;
              renderMissionsView();
            },
          },
          h('span', {class: 'option-letter'}, String.fromCharCode(65 + idx)),
          h('span', {}, ansText)
        );
      })
    ),
    !submitted &&
      h(
        'div',
        {class: 'confidence-row', role: 'group', 'aria-label': 'Nível de confiança'},
        ...[
          ['GUESS', 1, 'Chute puro'],
          ['LOW', 1, '1 · Baixa confiança'],
          ['MEDIUM', 2, '2 · Conhecimento parcial'],
          ['HIGH', 3, '3 · Certeza fundamentada'],
          ['VERY_HIGH', 3, 'Certeza plena'],
        ].map(([decl, lvl, text]) => {
          const isSelected = Boolean(ps.confidenceExplicit && ps.declaredConfidence === decl);
          return h(
            'button',
            {
              type: 'button',
              class: `confidence-btn ${isSelected ? 'active' : ''}`,
              'aria-pressed': String(isSelected),
              onclick: () => {
                ps.confidence = lvl;
                ps.declaredConfidence = decl;
                ps.confidenceExplicit = true;
                renderMissionsView();
              },
            },
            text
          );
        })
      ),
    !submitted &&
      h(
        'button',
        {
          id: 'confirm-practice-btn',
          type: 'button',
          class: 'btn-primary',
          disabled: ps.selectedIndex == null || state.busy,
          onclick: submitRealPracticeAnswer,
        },
        'Confirmar resposta formal'
      ),
    submitted &&
      h(
        'div',
        {class: `feedback-panel ${submitted.isCorrect ? 'good' : 'review'}`},
        h('strong', {}, submitted.isCorrect ? 'Resposta correta no acervo oficial!' : 'Resposta incorreta — revise o fundamento oficial:'),
        q.explanation && h('p', {}, q.explanation),
        ps.index + 1 < ps.questions.length &&
          h(
            'button',
            {
              type: 'button',
              class: 'btn-primary',
              onclick: () => {
                ps.index += 1;
                ps.selectedIndex = null;
                ps.confidence = 2;
                ps.declaredConfidence = null;
                ps.confidenceExplicit = false;
                ps.submittedResult = null;
                ps.questionStartedAtMs = Date.now();
                renderMissionsView();
              },
            },
            'Próxima questão real →'
          )
      )
  );
}

function renderConclusionView(detail) {
  const mission = detail.mission;
  const prog = detail.narrative_progress;
  const perf = detail.interaction_performance;
  const mastery = detail.demonstrated_mastery;
  const conclusion = mission.conclusion;

  return h(
    'section',
    {class: 'conclusion-card', 'aria-labelledby': 'conclusion-title'},
    h(
      'div',
      {class: 'theater-topbar'},
      h('span', {class: 'eyebrow'}, `${detail.career.display_header} · ${mission.display_code} CONCLUÍDA`),
      h(
        'button',
        {type: 'button', class: 'btn-ghost', onclick: exitMissionToCatalog},
        '← Catálogo de Missões'
      )
    ),
    h('h2', {id: 'conclusion-title'}, conclusion.headline),
    h('p', {}, conclusion.narrative_closure),
    h('p', {class: 'problem-callout'}, conclusion.epistemological_note),

    h(
      'div',
      {class: 'epistemic-grid'},
      h(
        'div',
        {class: 'epistemic-box'},
        h('h3', {}, '1. Progresso narrativo'),
        h('div', {class: 'epistemic-value'}, `${prog.progress_pct}%`),
        h('small', {}, `${prog.completed_scenes_count} de ${prog.total_scenes_count} cenas percorridas`)
      ),
      h(
        'div',
        {class: 'epistemic-box'},
        h('h3', {}, '2. Desempenho nas interações'),
        h(
          'div',
          {class: 'epistemic-value'},
          `${perf.first_attempt_correct_count}/${perf.total_decisions_count} na 1ª escolha`
        ),
        h('small', {}, 'Demonstração narrativa formativa — não compõe indicador formal de domínio')
      ),
      h(
        'div',
        {class: 'epistemic-box'},
        h('h3', {}, '3. Domínio demonstrado (acervo)'),
        h(
          'div',
          {class: 'epistemic-value'},
          `${mastery.distinct_human_questions_practiced}/${mastery.min_questions_for_mastery}`
        ),
        h('small', {}, mastery.mastery_label_pt)
      )
    ),

    h('h3', {}, 'Conceitos trabalhados e vínculos curriculares'),
    h(
      'div',
      {class: 'concepts-grid'},
      ...(mission.worked_concepts || []).map(c =>
        h(
          'div',
          {class: `concept-card ${c.recommended_revisit ? 'revisit' : ''}`},
          h('strong', {}, c.label),
          c.recommended_revisit &&
            h('span', {class: 'status-pill status-in_progress'}, 'Revisitação sugerida'),
          h('p', {}, c.summary),
          h(
            'small',
            {},
            `Currículo Cogniva: ${c.canonical_subject_id} · ${(c.active_topic_ids || []).join(', ')}`
          ),
          c.recommended_revisit &&
            c.related_scene_ids?.[0] &&
            h(
              'div',
              {class: 'concept-revisit-actions'},
              h(
                'button',
                {
                  type: 'button',
                  class: 'btn-secondary',
                  onclick: () => {
                    state.showConclusion = false;
                    state.activeSceneId = c.related_scene_ids[0];
                    renderMissionsView();
                  },
                },
                'Revisitar cena deste conceito'
              )
            )
        )
      )
    ),

    h('p', {}, conclusion.next_step_guidance),
    h(
      'div',
      {class: 'mission-card-actions'},
      mastery.practice_available
        ? h(
            'button',
            {type: 'button', class: 'btn-primary', onclick: openRelatedHumanQuestions},
            `Praticar questões reais relacionadas (${mastery.available_human_questions_count} disponíveis)`
          )
        : h(
            'button',
            {type: 'button', class: 'btn-secondary', onclick: openRelatedHumanQuestions},
            'Verificar questões reais no acervo'
          ),
      h(
        'button',
        {type: 'button', class: 'btn-secondary', onclick: repeatActiveMission},
        'Repetir missão'
      ),
      h(
        'button',
        {type: 'button', class: 'btn-ghost', onclick: exitMissionToCatalog},
        'Retornar ao catálogo'
      )
    ),
    renderPracticePanel()
  );
}

function renderPlayerView(detail) {
  if (state.showConclusion) {
    return renderConclusionView(detail);
  }

  const mission = detail.mission;
  const scenes = mission.scenes || [];
  const prog = detail.narrative_progress;
  const scene = scenes.find(s => s.scene_id === state.activeSceneId) || scenes[0];
  if (!scene) {
    return renderCatalogView();
  }

  const savedStartMs = prog?.scene_positions_ms?.[scene.scene_id] || 0;
  const videoUnavailable = !scene.media?.available_on_server || state.videoErrored;

  const scrubber = h('input', {
    type: 'range',
    class: 'scrubber-input',
    min: 0,
    max: Math.max(1, Math.round(scene.media.duration_seconds * 10)),
    value: Math.round((savedStartMs / 1000) * 10),
    'aria-label': 'Posição do vídeo',
  });
  const timeReadout = h(
    'span',
    {class: 'time-readout'},
    `${formatClockSeconds(savedStartMs / 1000)} / ${formatClockSeconds(scene.media.duration_seconds)}`
  );
  const playBtn = h('button', {type: 'button', class: 'btn-primary'}, '▶ Reproduzir');
  const muteBtn = h('button', {type: 'button', class: 'btn-ghost', 'aria-label': 'Alternar áudio'}, '🔊 Áudio');
  const volumeSlider = h('input', {
    type: 'range',
    class: 'volume-input',
    min: 0,
    max: 100,
    value: 100,
    'aria-label': 'Volume do áudio',
  });

  let videoEl = null;
  if (!videoUnavailable) {
    const trackEl = h('track', {
      kind: 'captions',
      srclang: 'pt-BR',
      label: 'Português (Brasil)',
      src: `${scene.media.captions_url}.vtt`,
      default: state.captionsEnabled,
    });
    videoEl = h(
      'video',
      {
        id: 'active-scene-video',
        class: 'mission-video',
        preload: 'auto',
        playsinline: '',
        src: scene.media.stream_url,
      },
      trackEl
    );

    videoEl.addEventListener('loadedmetadata', () => {
      if (savedStartMs > 0 && savedStartMs < scene.media.duration_seconds * 950) {
        try {
          videoEl.currentTime = savedStartMs / 1000;
        } catch {
          // Ignore seek restriction before buffer ready
        }
      }
      if (videoEl.textTracks && videoEl.textTracks[0]) {
        videoEl.textTracks[0].mode = state.captionsEnabled ? 'showing' : 'hidden';
      }
      if (scene.preload_next_media_url) {
        triggerModerateNextPreload(scene.preload_next_media_url);
      }
      // Attempt smooth autoplay; browsers blocking unmuted autoplay leave playBtn ready
      const playPromise = videoEl.play?.();
      if (playPromise && typeof playPromise.catch === 'function') {
        playPromise.catch(() => {});
      }
    });

    videoEl.addEventListener('play', () => {
      playBtn.textContent = '⏸ Pausar';
      sendTelemetrySilently(mission.mission_id, 'VIDEO_STARTED', scene.scene_id);
    });

    videoEl.addEventListener('pause', () => {
      playBtn.textContent = '▶ Reproduzir';
      if (!videoEl.ended) {
        state.userIntervenedInScene = true;
      }
    });

    videoEl.addEventListener('timeupdate', () => {
      const cur = Number(videoEl.currentTime) || 0;
      const dur = Number(videoEl.duration) || scene.media.duration_seconds;
      scrubber.max = String(Math.max(1, Math.round(dur * 10)));
      scrubber.value = String(Math.round(cur * 10));
      timeReadout.textContent = `${formatClockSeconds(cur)} / ${formatClockSeconds(dur)}`;
    });

    videoEl.addEventListener('ended', () => {
      playBtn.textContent = '↺ Rever cena';
      sendTelemetrySilently(mission.mission_id, 'VIDEO_COMPLETED', scene.scene_id);
      if (
        shouldAutoAdvanceScene(scene, {
          autoAdvanceEnabled: state.autoAdvanceEnabled,
          userIntervened: state.userIntervenedInScene,
          videoErrored: state.videoErrored,
        })
      ) {
        advanceFromScene(scene);
      }
    });

    videoEl.addEventListener('error', () => {
      state.videoErrored = true;
      state.transcriptOpen = true;
      sendTelemetrySilently(mission.mission_id, 'VIDEO_ERROR_FALLBACK', scene.scene_id);
      renderMissionsView();
    });

    playBtn.addEventListener('click', () => {
      if (!videoEl) return;
      if (videoEl.paused || videoEl.ended) {
        if (videoEl.ended) videoEl.currentTime = 0;
        const p = videoEl.play?.();
        if (p && typeof p.catch === 'function') p.catch(() => {});
      } else {
        state.userIntervenedInScene = true;
        videoEl.pause?.();
      }
    });

    scrubber.addEventListener('input', e => {
      state.userIntervenedInScene = true;
      if (videoEl) {
        videoEl.currentTime = Number(e.target.value) / 10;
      }
    });

    muteBtn.addEventListener('click', () => {
      if (!videoEl) return;
      videoEl.muted = !videoEl.muted;
      muteBtn.textContent = videoEl.muted ? '🔇 Mudo' : '🔊 Áudio';
    });

    volumeSlider.addEventListener('input', e => {
      if (!videoEl) return;
      const vol = Number(e.target.value) / 100;
      videoEl.volume = vol;
      videoEl.muted = vol === 0;
      muteBtn.textContent = videoEl.muted ? '🔇 Mudo' : '🔊 Áudio';
    });
  }

  const interaction = scene.interaction;
  const answered = interaction?.answered || null;

  const transcriptDrawer = h(
    'section',
    {
      class: 'transcript-drawer',
      'aria-label': 'Transcrição em português brasileiro',
      hidden: !state.transcriptOpen,
    },
    h('h3', {}, `Transcrição · ${scene.title}`),
    h('p', {}, scene.transcript_pt_br)
  );
  const captionsBtn = !videoUnavailable
    ? h(
        'button',
        {
          type: 'button',
          class: 'btn-ghost',
          onclick: () => {
            state.captionsEnabled = !state.captionsEnabled;
            if (videoEl?.textTracks?.[0]) {
              videoEl.textTracks[0].mode = state.captionsEnabled ? 'showing' : 'hidden';
            }
            captionsBtn.textContent = state.captionsEnabled ? 'CC Legendas: Ativas' : 'CC Legendas: Ocultas';
            sendTelemetrySilently(mission.mission_id, 'CAPTIONS_TOGGLED', scene.scene_id);
          },
        },
        state.captionsEnabled ? 'CC Legendas: Ativas' : 'CC Legendas: Ocultas'
      )
    : null;
  const transcriptBtn = h(
    'button',
    {
      type: 'button',
      class: 'btn-ghost',
      'aria-expanded': String(state.transcriptOpen),
      onclick: () => {
        state.transcriptOpen = !state.transcriptOpen;
        state.userIntervenedInScene = true;
        transcriptDrawer.hidden = !state.transcriptOpen;
        if (state.transcriptOpen) {
          transcriptDrawer.removeAttribute?.('hidden');
          sendTelemetrySilently(mission.mission_id, 'TRANSCRIPT_OPENED', scene.scene_id);
        } else {
          transcriptDrawer.setAttribute('hidden', '');
        }
        transcriptBtn.setAttribute('aria-expanded', String(state.transcriptOpen));
        transcriptBtn.textContent = state.transcriptOpen ? 'Ocultar transcrição' : 'Transcrição PT-BR';
      },
    },
    state.transcriptOpen ? 'Ocultar transcrição' : 'Transcrição PT-BR'
  );
  const autoAdvBtn = scene.auto_advance_on_video_end
    ? h(
        'button',
        {
          type: 'button',
          class: 'btn-ghost',
          onclick: () => {
            state.autoAdvanceEnabled = !state.autoAdvanceEnabled;
            autoAdvBtn.textContent = state.autoAdvanceEnabled
              ? 'Avanço automático: Ligado'
              : 'Avanço automático: Pausado';
          },
        },
        state.autoAdvanceEnabled ? 'Avanço automático: Ligado' : 'Avanço automático: Pausado'
      )
    : null;

  return h(
    'div',
    {class: 'theater-wrap'},
    h(
      'div',
      {class: 'theater-topbar'},
      h(
        'button',
        {type: 'button', class: 'btn-ghost', onclick: exitMissionToCatalog},
        '← Sair e salvar progresso'
      ),
      h(
        'div',
        {class: 'step-strip', role: 'navigation', 'aria-label': 'Etapas da missão'},
        h('span', {class: 'step-label'}, `Etapa ${scene.order} de ${scenes.length} · ${scene.title}`),
        ...scenes.map(s => {
          const accessible = canAccessScene(scenes, s.scene_id, prog);
          const cls = `step-dot ${s.scene_id === scene.scene_id ? 'current' : ''} ${s.is_completed ? 'done' : ''}`.trim();
          return h(
            'button',
            {
              type: 'button',
              class: cls,
              disabled: !accessible,
              title: s.title,
              'aria-label': `Ir para Cena ${s.order}: ${s.title}`,
              onclick: async () => {
                if (state.busy) return;
                state.busy = true;
                stopActiveVideo();
                state.activeSceneId = s.scene_id;
                state.transcriptOpen = false;
                state.userIntervenedInScene = false;
                state.videoErrored = false;
                sceneEnteredAtMs = Date.now();
                sendTelemetrySilently(mission.mission_id, 'SCENE_JUMPED', s.scene_id);
                try {
                  const updated = await api(`/api/v1/missions/${encodeURIComponent(mission.mission_id)}/progress`, {
                    method: 'POST',
                    body: {current_scene_id: s.scene_id},
                  });
                  if (state.activeDetail && updated?.narrative_progress) {
                    state.activeDetail.narrative_progress = updated.narrative_progress;
                    if (updated.interaction_performance) {
                      state.activeDetail.interaction_performance = updated.interaction_performance;
                    }
                  }
                } catch {
                  // Preserve local scene jump even on transient network blip
                } finally {
                  state.busy = false;
                }
                if (state.profile && state.activeDetail) {
                  renderMissionsView();
                }
              },
            },
            String(s.order)
          );
        })
      )
    ),

    h(
      'section',
      {class: 'video-stage-card', 'aria-label': `Player audiovisual: ${scene.title}`},
      h(
        'div',
        {class: 'video-aspect-box'},
        videoUnavailable
          ? h(
              'div',
              {class: 'video-fallback-panel', role: 'alert'},
              h('h3', {}, 'Modo Transcrição Ativo · Vídeo indisponível no momento'),
              h(
                'p',
                {},
                'Não foi possível reproduzir o arquivo de vídeo agora, mas toda a narrativa e as interações desta cena estão preservadas abaixo para você continuar sem bloqueio.'
              ),
              h('p', {class: 'problem-callout'}, scene.transcript_pt_br),
              h(
                'div',
                {class: 'mission-card-actions'},
                h(
                  'button',
                  {
                    type: 'button',
                    class: 'btn-secondary',
                    onclick: () => {
                      state.videoErrored = false;
                      renderMissionsView();
                    },
                  },
                  'Tentar carregar vídeo novamente'
                ),
                !scene.requires_decision_to_advance &&
                  h(
                    'button',
                    {
                      type: 'button',
                      class: 'btn-primary',
                      onclick: () => advanceFromScene(scene, {viaTranscript: true}),
                    },
                    scene.next_scene_id ? 'Prosseguir com transcrição →' : 'Concluir missão →'
                  )
              )
            )
          : videoEl
      ),
      h(
        'div',
        {class: 'player-controls'},
        !videoUnavailable && playBtn,
        !videoUnavailable && scrubber,
        !videoUnavailable && timeReadout,
        !videoUnavailable && muteBtn,
        !videoUnavailable && volumeSlider,
        captionsBtn,
        transcriptBtn,
        autoAdvBtn
      )
    ),

    transcriptDrawer,

    scene.native_visual?.visual_type === 'BOND_PRESENT_VALUE_CURVE' &&
      renderPresentValueVisual(scene.native_visual),

    interaction
      ? h(
          'section',
          {class: 'decision-card', 'aria-labelledby': `inter-${interaction.interaction_id}`},
          h('span', {class: 'eyebrow'}, 'PONTO DE DECISÃO NARRATIVA · ATIVIDADE DIDÁTICA'),
          h('h2', {id: `inter-${interaction.interaction_id}`}, interaction.prompt),
          interaction.context_lead && h('p', {}, interaction.context_lead),
          h('p', {class: 'ceteris-note'}, interaction.ceteris_paribus_note),
          h(
            'div',
            {class: 'decision-options', role: 'group', 'aria-label': 'Alternativas de decisão'},
            ...(interaction.options || []).map(opt => {
              let cls = 'option-button';
              if (answered) {
                if (opt.option_id === answered.expected_option_id) {
                  cls += ' selected-correct';
                } else if (opt.option_id === answered.selected_option_id && !answered.is_correct) {
                  cls += ' selected-review';
                }
              }
              return h(
                'button',
                {
                  type: 'button',
                  class: cls,
                  'aria-pressed': String(Boolean(answered && opt.option_id === answered.selected_option_id)),
                  disabled: state.busy,
                  onclick: () => selectDecisionOption(scene, interaction, opt.option_id),
                },
                h('span', {class: 'option-letter'}, opt.option_id),
                h('span', {}, opt.label)
              );
            })
          ),
          answered &&
            h(
              'div',
              {class: `feedback-panel ${answered.is_correct ? 'good' : 'review'}`},
              h(
                'strong',
                {},
                answered.is_correct
                  ? `Resposta esperada (${answered.expected_option_id}) confirmada!`
                  : `Você selecionou ${answered.selected_option_id} · Resposta conceitual esperada: ${answered.expected_option_id}`
              ),
              h('p', {}, answered.option_feedback),
              h('p', {}, answered.conceptual_explanation),
              h(
                'div',
                {class: 'mission-card-actions'},
                scene.next_scene_id
                  ? h(
                      'button',
                      {
                        type: 'button',
                        class: 'btn-primary',
                        onclick: () => advanceFromScene(scene),
                      },
                      'Continuar para a próxima cena →'
                    )
                  : h(
                      'button',
                      {
                        type: 'button',
                        class: 'btn-primary',
                        onclick: () => advanceFromScene(scene),
                      },
                      'Ver conclusão da missão →'
                    )
              )
            ),
          answered &&
            scene.native_visual?.visual_type === 'ZERO_COUPON_DURATION_COMPARISON' &&
            renderDurationComparisonVisual(scene.native_visual)
        )
      : h(
          'section',
          {class: 'scene-context-card'},
          h('span', {class: 'eyebrow'}, `${scene.character?.name || 'Protagonista'} · ${scene.character?.role_title || ''}`),
          h('h2', {}, scene.title),
          h('p', {}, scene.narrative_context),
          scene.problem_statement && h('div', {class: 'problem-callout'}, scene.problem_statement),
          h(
            'div',
            {class: 'mission-card-actions'},
            h(
              'button',
              {
                type: 'button',
                class: 'btn-primary',
                onclick: () => advanceFromScene(scene, {viaTranscript: videoUnavailable}),
              },
              scene.next_scene_id ? 'Avançar para a próxima cena →' : 'Concluir missão →'
            )
          )
        )
  );
}

export function renderMissionsView() {
  const doc = boundDoc || document;
  syncShellVisibility();
  const main = doc.getElementById('missions-main');
  if (!main || !state.profile) return;
  clearNode(main);
  if (state.activeDetail) {
    main.append(renderPlayerView(state.activeDetail));
  } else {
    main.append(renderCatalogView());
  }
}

function bindAuthControls(doc) {
  const tabLogin = doc.getElementById('tab-login');
  const tabRegister = doc.getElementById('tab-register');
  const nameWrap = doc.getElementById('auth-name-wrap');
  const nameInput = doc.getElementById('auth-name');
  const emailInput = doc.getElementById('auth-email');
  const passInput = doc.getElementById('auth-password');
  const submitBtn = doc.getElementById('auth-submit');
  const guestBtn = doc.getElementById('guest-btn');
  const statusEl = doc.getElementById('auth-status');
  const form = doc.getElementById('auth-form');
  const logoutBtn = doc.getElementById('logout-btn');
  const navMissionsBtn = doc.getElementById('nav-missions-btn');
  const navHomeLink = doc.getElementById('nav-home-link');

  const syncAuthMode = mode => {
    state.authMode = mode;
    const isReg = mode === 'register';
    if (tabLogin) {
      tabLogin.classList.toggle('active', !isReg);
      tabLogin.setAttribute('aria-selected', String(!isReg));
    }
    if (tabRegister) {
      tabRegister.classList.toggle('active', isReg);
      tabRegister.setAttribute('aria-selected', String(isReg));
    }
    if (nameWrap) nameWrap.hidden = !isReg;
    if (passInput) passInput.setAttribute('autocomplete', isReg ? 'new-password' : 'current-password');
    if (submitBtn) submitBtn.textContent = isReg ? 'Criar conta e abrir Missões' : 'Entrar nas Missões';
    if (statusEl) statusEl.textContent = '';
  };

  tabLogin?.addEventListener('click', () => syncAuthMode('login'));
  tabRegister?.addEventListener('click', () => syncAuthMode('register'));

  form?.addEventListener('submit', async e => {
    e.preventDefault();
    if (statusEl) statusEl.textContent = 'Autenticando…';
    if (submitBtn) submitBtn.disabled = true;
    try {
      const endpoint = state.authMode === 'register' ? '/api/v1/auth/register' : '/api/v1/auth/login';
      const body = {
        email: emailInput?.value || '',
        password: passInput?.value || '',
        client: 'student_web',
      };
      if (state.authMode === 'register' && nameInput?.value?.trim()) {
        body.display_name = nameInput.value.trim();
      }
      const res = await api(endpoint, {method: 'POST', body});
      state.profile = res.profile;
      if (passInput) passInput.value = '';
      if (statusEl) statusEl.textContent = '';
      await afterAuthenticatedBootstrap();
    } catch (err) {
      if (statusEl) statusEl.textContent = err.message || 'Falha na autenticação.';
    } finally {
      if (submitBtn) submitBtn.disabled = false;
    }
  });

  guestBtn?.addEventListener('click', async () => {
    if (statusEl) statusEl.textContent = 'Preparando sessão convidada…';
    guestBtn.disabled = true;
    try {
      const creds = getOrCreateGuestCredentials();
      const body = {client: 'student_web', ...(creds || {})};
      const res = await api('/api/v1/auth/guest', {method: 'POST', body});
      state.profile = res.profile;
      if (statusEl) statusEl.textContent = '';
      await afterAuthenticatedBootstrap();
    } catch (err) {
      if (statusEl) statusEl.textContent = err.message || 'Não foi possível iniciar sessão convidada.';
    } finally {
      guestBtn.disabled = false;
    }
  });

  logoutBtn?.addEventListener('click', async () => {
    if (state.busy) return;
    state.busy = true;
    logoutBtn.disabled = true;
    stopActiveVideo();
    try {
      await api('/api/v1/auth/logout', {method: 'POST'});
    } catch {
      // Clear client state even if server unreachable
    } finally {
      state.busy = false;
      logoutBtn.disabled = false;
    }
    state.profile = null;
    state.catalog = null;
    state.activeDetail = null;
    state.activeSceneId = null;
    state.showConclusion = false;
    state.transcriptOpen = false;
    state.userIntervenedInScene = false;
    state.videoErrored = false;
    state.simulatedRatePct = 14;
    state.practiceState = null;
    setNotice('');
    if (typeof window !== 'undefined' && window.history?.replaceState) {
      window.history.replaceState(null, '', '/missoes/');
    }
    clearNode(doc.getElementById('missions-main'));
    syncShellVisibility();
  });

  const goCatalog = e => {
    e?.preventDefault?.();
    if (state.activeDetail) {
      exitMissionToCatalog();
    } else {
      renderMissionsView();
    }
  };
  navMissionsBtn?.addEventListener('click', goCatalog);
  navHomeLink?.addEventListener('click', goCatalog);
}

async function afterAuthenticatedBootstrap() {
  syncShellVisibility();
  await loadCatalog();
  const hash = typeof window !== 'undefined' ? window.location.hash || '' : '';
  const match = hash.match(/^#missao\/([a-z0-9_-]+)$/i);
  if (match) {
    await openMission(decodeURIComponent(match[1]), {resume: true});
  } else {
    renderMissionsView();
  }
}

export async function initMissionsApp(doc = document) {
  boundDoc = doc;
  bindAuthControls(doc);
  try {
    const profile = await api('/api/v1/auth/me');
    state.profile = profile;
    await afterAuthenticatedBootstrap();
  } catch {
    state.profile = null;
    syncShellVisibility();
  }
}

if (typeof window !== 'undefined' && typeof document !== 'undefined' && !window.__COGNIVA_TEST__) {
  initMissionsApp(document);
}
