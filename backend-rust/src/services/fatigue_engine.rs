use crate::models::activity::UserActivityDocument;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum FatigueStatus {
    InsufficientEvidence,
    FatigueDetected,
    StableEndurance,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FatigueReport {
    pub status: FatigueStatus,
    pub qualifying_sessions: u32,
    pub required_sessions: u32,
    pub qualifying_attempts: u32,
    pub required_attempts: u32,
    pub shrunk_accuracy_delta: Option<f64>,
}

fn round9(val: f64) -> f64 {
    (val * 1_000_000_000.0).round() / 1_000_000_000.0
}

/// Avalia efeito individual de fadiga de prova com guarda epistemológica estrita:
/// exige no mínimo 3 simulados prolongados sem interrupção e >= 120 itens cronometrados
/// (>= 30 na janela inicial 0-45m e >= 30 na janela tardia >45m).
pub fn evaluate_fatigue(
    qualifying_sessions: u32,
    early_correct: u32,
    early_total: u32,
    late_correct: u32,
    late_total: u32,
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
        };
    }

    let p_early = (early_correct.min(early_total) as f64) / (early_total as f64);
    let p_late = (late_correct.min(late_total) as f64) / (late_total as f64);
    let delta = p_late - p_early;
    let se = ((p_early * (1.0 - p_early)) / (early_total as f64)
        + (p_late * (1.0 - p_late)) / (late_total as f64))
        .sqrt();
    let kappa = (late_total as f64) / ((late_total as f64) + 40.0);
    let shrunk = round9(kappa * delta);

    let status = if delta < -0.05 && (delta + 1.645 * se) < 0.0 {
        FatigueStatus::FatigueDetected
    } else {
        FatigueStatus::StableEndurance
    };

    FatigueReport {
        status,
        qualifying_sessions,
        required_sessions: 3,
        qualifying_attempts: total,
        required_attempts: 120,
        shrunk_accuracy_delta: Some(shrunk),
    }
}

/// Consolida sessões `SIMULADO` não interrompidas com duração contínua >= 90 min (5400s)
/// ou >= 40 respostas cronometradas a partir do histórico auditável `userActivityLog`.
pub fn evaluate_from_activities(activities: &[UserActivityDocument]) -> FatigueReport {
    struct SessionObs {
        max_elapsed: i32,
        interrupted: bool,
        events: Vec<(i32, bool)>,
    }

    let mut sessions: HashMap<String, SessionObs> = HashMap::new();
    for act in activities {
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
            entry.events.push((elapsed, s.status));
        }
    }

    let mut qualifying_sessions = 0u32;
    let mut early_correct = 0u32;
    let mut early_total = 0u32;
    let mut late_correct = 0u32;
    let mut late_total = 0u32;

    for obs in sessions.values() {
        if obs.interrupted || (obs.max_elapsed < 5400 && obs.events.len() < 40) {
            continue;
        }
        qualifying_sessions += 1;
        for &(elapsed, ok) in &obs.events {
            if elapsed <= 2700 {
                early_total += 1;
                if ok {
                    early_correct += 1;
                }
            } else {
                late_total += 1;
                if ok {
                    late_correct += 1;
                }
            }
        }
    }

    evaluate_fatigue(
        qualifying_sessions,
        early_correct,
        early_total,
        late_correct,
        late_total,
    )
}
