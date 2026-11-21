/**
 * Pure domain calculations and state helpers for Cogniva Missões.
 * Zero DOM dependencies — shared by browser runtime and Node automated tests.
 */

export function presentValueZeroCoupon(faceValueBrl, annualRatePct, maturityYears) {
  const vf = Number(faceValueBrl) || 1000;
  const r = Number(annualRatePct) / 100;
  const n = Number(maturityYears);
  if (!Number.isFinite(r) || r <= -1 || !Number.isFinite(n) || n < 0) {
    return vf;
  }
  return vf / Math.pow(1 + r, n);
}

export function priceChangePct(faceValueBrl, initialRatePct, shockedRatePct, maturityYears) {
  const p0 = presentValueZeroCoupon(faceValueBrl, initialRatePct, maturityYears);
  const p1 = presentValueZeroCoupon(faceValueBrl, shockedRatePct, maturityYears);
  if (p0 <= 0) return 0;
  return ((p1 - p0) / p0) * 100;
}

export function modifiedDurationZeroCoupon(maturityYears, annualRatePct) {
  const d = Number(maturityYears) || 0;
  const r = Number(annualRatePct) / 100;
  if (r <= -1) return d;
  return d / (1 + r);
}

export function buildPresentValueCurvePoints(params = {}) {
  const faceValue = Number(params.face_value_brl ?? 1000);
  const years = Number(params.maturity_years ?? 3);
  const minRate = Number(params.min_rate_pct ?? 4);
  const maxRate = Number(params.max_rate_pct ?? 20);
  const points = [];
  const steps = 16;
  for (let i = 0; i <= steps; i++) {
    const ratePct = minRate + ((maxRate - minRate) * i) / steps;
    const price = presentValueZeroCoupon(faceValue, ratePct, years);
    points.push({
      ratePct: Math.round(ratePct * 100) / 100,
      priceBrl: Math.round(price * 100) / 100,
    });
  }
  return points;
}

export function buildDurationComparisonSummary(params = {}) {
  const faceValue = Number(params.face_value_brl ?? 1000);
  const shortYears = Number(params.short_maturity_years ?? 1);
  const longYears = Number(params.long_maturity_years ?? 5);
  const initialRate = Number(params.initial_rate_pct ?? 10);
  const shockedRate = Number(params.shocked_rate_pct ?? 12);

  const shortInitial = presentValueZeroCoupon(faceValue, initialRate, shortYears);
  const shortShocked = presentValueZeroCoupon(faceValue, shockedRate, shortYears);
  const shortDropPct = priceChangePct(faceValue, initialRate, shockedRate, shortYears);

  const longInitial = presentValueZeroCoupon(faceValue, initialRate, longYears);
  const longShocked = presentValueZeroCoupon(faceValue, shockedRate, longYears);
  const longDropPct = priceChangePct(faceValue, initialRate, shockedRate, longYears);

  return {
    faceValue,
    initialRate,
    shockedRate,
    shortBond: {
      years: shortYears,
      macaulayDuration: shortYears,
      modifiedDuration: modifiedDurationZeroCoupon(shortYears, initialRate),
      initialPrice: shortInitial,
      shockedPrice: shortShocked,
      changePct: shortDropPct,
    },
    longBond: {
      years: longYears,
      macaulayDuration: longYears,
      modifiedDuration: modifiedDurationZeroCoupon(longYears, initialRate),
      initialPrice: longInitial,
      shockedPrice: longShocked,
      changePct: longDropPct,
    },
  };
}

export function formatBrl(value) {
  const num = Number(value) || 0;
  return 'R$ ' + num.toFixed(2).replace('.', ',').replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}

export function formatSignedPct(value) {
  const num = Number(value) || 0;
  const sign = num > 0 ? '+' : num < 0 ? '−' : '';
  return `${sign}${Math.abs(num).toFixed(2).replace('.', ',')}%`;
}

export function formatClockSeconds(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  const mins = Math.floor(total / 60);
  const secs = total % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

export function canAccessScene(scenes, targetSceneId, narrativeProgress) {
  if (!Array.isArray(scenes) || scenes.length === 0) return false;
  if (narrativeProgress?.status === 'COMPLETED') return true;
  const completed = new Set(narrativeProgress?.completed_scene_ids || []);
  const currentId = narrativeProgress?.current_scene_id || scenes[0].scene_id;
  if (targetSceneId === currentId || completed.has(targetSceneId)) {
    return true;
  }
  const targetIdx = scenes.findIndex(s => s.scene_id === targetSceneId);
  if (targetIdx <= 0) return targetIdx === 0;
  // Can advance to immediate next scene if all preceding scenes were completed
  for (let i = 0; i < targetIdx; i++) {
    if (!completed.has(scenes[i].scene_id)) return false;
  }
  return true;
}

export function shouldAutoAdvanceScene(scene, {autoAdvanceEnabled, userIntervened, videoErrored}) {
  if (!scene || !scene.auto_advance_on_video_end) return false;
  if (scene.requires_decision_to_advance) return false;
  if (!autoAdvanceEnabled || userIntervened || videoErrored) return false;
  return Boolean(scene.next_scene_id);
}
