use crate::models::activity::UserActivityDocument;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum FatigueStatus {
    InsufficientEvidence,
    FatigueDetected,
    StableEndurance,
    WarmupGainDetected,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FatigueCurvePointDto {
    pub minute: i32,
    pub predicted_accuracy: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FatigueReport {
    pub status: FatigueStatus,
    pub qualifying_sessions: u32,
    pub required_sessions: u32,
    pub qualifying_attempts: u32,
    pub required_attempts: u32,
    pub shrunk_accuracy_delta: Option<f64>,
    #[serde(default)]
    pub estimated_lambda: Option<f64>,
    #[serde(default)]
    pub difficulty_controlled: bool,
    #[serde(default)]
    pub curve_300m: Vec<FatigueCurvePointDto>,
}

fn round9(val: f64) -> f64 {
    if val >= 0.0 {
        (val * 1_000_000_000.0 + 0.5).floor() / 1_000_000_000.0
    } else {
        (val * 1_000_000_000.0 - 0.5).ceil() / 1_000_000_000.0
    }
}

pub fn sigmoid(z: f64) -> f64 {
    if z >= 0.0 {
        let ez = (-z).exp();
        1.0 / (1.0 + ez)
    } else {
        let ez = z.exp();
        ez / (1.0 + ez)
    }
}

pub fn logit(p: f64) -> f64 {
    let c = p.clamp(0.02, 0.98);
    (c / (1.0 - c)).ln()
}

/// Modelo individual de desempenho ao longo de 300 minutos:
/// `p(t) = sigma(logit(p0) - lambda_i * (t / 300))`
/// onde `p0` é ajustada à dificuldade do item `item_difficulty` em `[0, 1]`
/// e `lambda_i` pode ser positivo (queda), negativo (aquecimento) ou nulo.
pub fn performance_at_minute(
    p_base: f64,
    item_difficulty: f64,
    lambda_i: f64,
    elapsed_minutes: f64,
) -> (f64, f64) {
    let d = item_difficulty.clamp(0.0, 1.0);
    let t = elapsed_minutes.clamp(0.0, 300.0);
    let lam = if lambda_i.is_finite() {
        lambda_i.clamp(-2.5, 2.5)
    } else {
        0.0
    };
    let z0 = logit(p_base) - 0.80 * (d - 0.50);
    let p0 = sigmoid(z0);
    let pt = sigmoid(z0 - lam * (t / 300.0));
    (round9(p0), round9(pt))
}

pub fn build_curve_300m(p_base: f64, lambda_i: f64) -> Vec<FatigueCurvePointDto> {
    [0, 60, 120, 180, 240, 300]
        .into_iter()
        .map(|minute| {
            let (_, pt) = performance_at_minute(p_base, 0.50, lambda_i, minute as f64);
            FatigueCurvePointDto {
                minute,
                predicted_accuracy: pt,
            }
        })
        .collect()
}

/// Avalia o parâmetro individual `lambda_i` de tempo de prova ao longo de 300 minutos
/// com controle explícito de dificuldade dos itens e guarda epistemológica estrita:
/// exige `>= 3` simulados prolongados sem interrupção e `>= 120` itens cronometrados.
#[allow(clippy::too_many_arguments)]
pub fn evaluate_fatigue_with_difficulty(
    qualifying_sessions: u32,
    early_correct: u32,
    early_total: u32,
    late_correct: u32,
    late_total: u32,
    early_avg_difficulty: f64,
    late_avg_difficulty: f64,
    early_mean_minutes: f64,
    late_mean_minutes: f64,
) -> FatigueReport {
    let total = early_total + late_total;
    if qualifying_sessions < 3 || total < 120 || early_total < 30 || late_total < 30 {
        return FatigueReport {
            status: FatigueStatus::InsufficientEvidence,
            qualifying_sessions,
            required_sessions: 3,
            qualifying_attempts: total,
            required_attempts: 120,
            shrunk_accuracy_delta: None,
            estimated_lambda: None,
            difficulty_controlled: true,
            curve_300m: Vec::new(),
        };
    }

    let d_early = early_avg_difficulty.clamp(0.0, 1.0);
    let d_late = late_avg_difficulty.clamp(0.0, 1.0);
    let u_early = (early_mean_minutes / 300.0).clamp(0.0, 1.0);
    let u_late = (late_mean_minutes / 300.0).clamp(u_early + 0.15, 1.0);

    let p_early = ((early_correct.min(early_total) as f64) + 0.5) / ((early_total as f64) + 1.0);
    let p_late = ((late_correct.min(late_total) as f64) + 0.5) / ((late_total as f64) + 1.0);

    let z_early_adj = logit(p_early) + 0.80 * (d_early - 0.50);
    let z_late_adj = logit(p_late) + 0.80 * (d_late - 0.50);

    let du = u_late - u_early;
    let raw_lambda = (z_early_adj - z_late_adj) / du;

    let w_early = (early_total as f64) * p_early * (1.0 - p_early);
    let w_late = (late_total as f64) * p_late * (1.0 - p_late);
    let var_diff = (1.0 / w_early) + (1.0 / w_late);
    let se_raw_lambda = var_diff.sqrt() / du;
    let info_lambda = 1.0 / (se_raw_lambda * se_raw_lambda);
    let prior_prec = 4.0;
    let shrunk_lambda = ((info_lambda / (info_lambda + prior_prec)) * raw_lambda).clamp(-2.5, 2.5);

    let p_early_neutral = sigmoid(z_early_adj);
    let p_late_neutral = sigmoid(z_late_adj);
    let kappa = (late_total as f64) / ((late_total as f64) + 40.0);
    let shrunk_delta = round9(kappa * (p_late_neutral - p_early_neutral));
    let rounded_lambda = round9(shrunk_lambda);

    let status = if shrunk_lambda > 0.25 && (raw_lambda - 1.645 * se_raw_lambda) > 0.0 {
        FatigueStatus::FatigueDetected
    } else if shrunk_lambda < -0.25 && (raw_lambda + 1.645 * se_raw_lambda) < 0.0 {
        FatigueStatus::WarmupGainDetected
    } else {
        FatigueStatus::StableEndurance
    };

    FatigueReport {
        status,
        qualifying_sessions,
        required_sessions: 3,
        qualifying_attempts: total,
        required_attempts: 120,
        shrunk_accuracy_delta: Some(shrunk_delta),
        estimated_lambda: Some(rounded_lambda),
        difficulty_controlled: true,
        curve_300m: build_curve_300m(p_early_neutral, rounded_lambda),
    }
}

pub fn evaluate_fatigue(
    qualifying_sessions: u32,
    early_correct: u32,
    early_total: u32,
    late_correct: u32,
    late_total: u32,
) -> FatigueReport {
    evaluate_fatigue_with_difficulty(
        qualifying_sessions,
        early_correct,
        early_total,
        late_correct,
        late_total,
        0.50,
        0.50,
        30.0,
        210.0,
    )
}

/// Consolida sessões `SIMULADO` não interrompidas com duração contínua >= 90 min (5400s)
/// ou >= 40 respostas cronometradas, controlando a dificuldade individual `memory_difficulty` dos itens.
pub fn evaluate_from_activities(activities: &[UserActivityDocument]) -> FatigueReport {
    struct SessionObs {
        max_elapsed: i32,
        interrupted: bool,
        events: Vec<(i32, bool, f64)>,
    }

    let mut sessions: HashMap<String, SessionObs> = HashMap::new();
    for act in activities {
        let item_diff = (act.memory_difficulty / 10.0).clamp(0.10, 0.90);
        for s in &act.past_sessions {
            if s.is_blank || !s.modo_sessao.eq_ignore_ascii_case("SIMULADO") {
                continue;
            }
            let Some(elapsed) = s.elapsed_session_seconds else {
                continue;
            };
            let entry = sessions.entry(s.session_uuid.clone()).or_insert(SessionObs {
                max_elapsed: 0,
                interrupted: false,
                events: Vec::new(),
            });
            entry.max_elapsed = entry.max_elapsed.max(elapsed);
            entry.interrupted |= s.session_interrupted;
            entry.events.push((elapsed, s.status, item_diff));
        }
    }

    let mut qualifying_sessions = 0u32;
    let mut early_correct = 0u32;
    let mut early_total = 0u32;
    let mut late_correct = 0u32;
    let mut late_total = 0u32;
    let mut early_diff_sum = 0.0f64;
    let mut late_diff_sum = 0.0f64;
    let mut early_min_sum = 0.0f64;
    let mut late_min_sum = 0.0f64;

    for obs in sessions.values() {
        if obs.interrupted || (obs.max_elapsed < 5400 && obs.events.len() < 40) {
            continue;
        }
        qualifying_sessions += 1;
        for &(elapsed, ok, diff) in &obs.events {
            let mins = ((elapsed.max(0) as f64) / 60.0).min(300.0);
            if elapsed <= 2700 {
                early_total += 1;
                early_diff_sum += diff;
                early_min_sum += mins;
                if ok {
                    early_correct += 1;
                }
            } else {
                late_total += 1;
                late_diff_sum += diff;
                late_min_sum += mins;
                if ok {
                    late_correct += 1;
                }
            }
        }
    }

    let early_avg_d = if early_total > 0 {
        early_diff_sum / (early_total as f64)
    } else {
        0.50
    };
    let late_avg_d = if late_total > 0 {
        late_diff_sum / (late_total as f64)
    } else {
        0.50
    };
    let early_avg_m = if early_total > 0 {
        early_min_sum / (early_total as f64)
    } else {
        30.0
    };
    let late_avg_m = if late_total > 0 {
        late_min_sum / (late_total as f64)
    } else {
        180.0
    };

    evaluate_fatigue_with_difficulty(
        qualifying_sessions,
        early_correct,
        early_total,
        late_correct,
        late_total,
        early_avg_d,
        late_avg_d,
        early_avg_m,
        late_avg_m,
    )
}
