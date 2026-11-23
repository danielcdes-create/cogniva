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

fn finite_or(val: f64, fallback: f64) -> f64 {
    if val.is_finite() {
        val
    } else {
        fallback
    }
}

pub fn logit(p: f64) -> f64 {
    let c = finite_or(p, 0.50).clamp(0.02, 0.98);
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
    let d = finite_or(item_difficulty, 0.50).clamp(0.0, 1.0);
    let t = finite_or(elapsed_minutes, 0.0).clamp(0.0, 300.0);
    let lam = finite_or(lambda_i, 0.0).clamp(-2.5, 2.5);
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

    let d_early = finite_or(early_avg_difficulty, 0.50).clamp(0.0, 1.0);
    let d_late = finite_or(late_avg_difficulty, 0.50).clamp(0.0, 1.0);
    let u_early = (finite_or(early_mean_minutes, 30.0) / 300.0).clamp(0.0, 0.85);
    let u_late = (finite_or(late_mean_minutes, 210.0) / 300.0).clamp(u_early + 0.15, 1.0);

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
        let item_diff = (finite_or(act.memory_difficulty, 5.0) / 10.0).clamp(0.10, 0.90);
        for s in &act.past_sessions {
            if s.is_blank || !s.modo_sessao.eq_ignore_ascii_case("SIMULADO") {
                continue;
            }
            let Some(elapsed) = s.elapsed_session_seconds else {
                continue;
            };
            let entry = sessions
                .entry(s.session_uuid.clone())
                .or_insert(SessionObs {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn high_early_minutes_and_nan_inputs_never_panic_or_produce_nan() {
        let rep = evaluate_fatigue_with_difficulty(
            4,
            50,
            65,
            42,
            65,
            f64::NAN,
            f64::INFINITY,
            290.0,
            295.0,
        );
        assert!(rep.estimated_lambda.unwrap().is_finite());
        assert!(rep.shrunk_accuracy_delta.unwrap().is_finite());
        let (p0, pt) = performance_at_minute(f64::NAN, f64::NAN, f64::NAN, f64::NAN);
        assert!(p0.is_finite() && pt.is_finite());
    }

    #[test]
    fn evaluate_from_activities_filters_interrupted_blank_and_non_simulado_sessions() {
        use crate::models::activity::{ActivityDates, PastSessionEntry};
        use bson::oid::ObjectId;
        use chrono::Utc;

        let now = Utc::now();
        let make_entry =
            |sess: &str, mode: &str, elapsed: i32, ok: bool, is_blank: bool, interrupted: bool| {
                PastSessionEntry {
                    attempt_id: uuid::Uuid::new_v4().to_string(),
                    data: now,
                    status: ok,
                    alternativa_escolhida: "A".into(),
                    tempo_gasto_segundos: 45,
                    session_uuid: sess.into(),
                    modo_sessao: mode.into(),
                    modo_prova: None,
                    concurso: None,
                    canonical_subject_id: None,
                    canonical_topic_id: None,
                    dias_desde_ultima_sessao: 0,
                    dias_desde_ultima_revisao: None,
                    percentual_acertos_acumulado: 50.0,
                    tempo_medio_acumulado: 45.0,
                    nivel_confianca: 2,
                    spaced_repetition_interval: 1,
                    spaced_repetition_score: 0.0,
                    streak_atual: 0,
                    points_session: 0.0,
                    is_revision: false,
                    is_blank,
                    confidence_explicit: true,
                    selected_evidence_id: None,
                    position_in_session: None,
                    elapsed_session_seconds: Some(elapsed),
                    session_interrupted: interrupted,
                }
            };

        let mut sessions = Vec::new();
        // 3 qualifying uninterrupted SIMULADO sessions (>= 5400s, 45 answered items each -> 135 >= 120):
        // High early accuracy (22/23) vs low late accuracy (4/22) -> detects fatigue!
        for s_idx in 1..=3 {
            let sid = format!("sim-ok-{s_idx}");
            for i in 0..23 {
                sessions.push(make_entry(
                    &sid,
                    "SIMULADO",
                    600 + i * 60,
                    i < 22,
                    false,
                    false,
                ));
            }
            for i in 0..22 {
                sessions.push(make_entry(
                    &sid,
                    "SIMULADO",
                    5400 + i * 120,
                    i < 4,
                    false,
                    false,
                ));
            }
            // Strategic blanks inside simulado must be ignored without corrupting counts
            sessions.push(make_entry(&sid, "SIMULADO", 7200, false, true, false));
        }
        // Interrupted simulado and STANDARD sessions must be ignored completely
        for i in 0..50 {
            sessions.push(make_entry(
                "sim-interrupted",
                "SIMULADO",
                6000,
                true,
                false,
                i == 49,
            ));
            sessions.push(make_entry(
                "std-ignored",
                "STANDARD",
                6000,
                true,
                false,
                false,
            ));
        }

        let act = UserActivityDocument {
            id: Some(ObjectId::new()),
            user_id: 1,
            question_id: ObjectId::new(),
            datas: ActivityDates {
                ultima_sessao: now,
                ultima_revisao: None,
                data_primeira_sessao: now,
                data_primeiro_acerto: None,
            },
            tempos: Default::default(),
            disciplina: "Direito".into(),
            past_sessions: sessions,
            quantidade_praticas: 135,
            quantidade_acertos: 78,
            quantidade_erros: 57,
            percentual_acertos: 57.7,
            percentual_revisoes_sucesso: None,
            var_alternativa_escolhida: 0.0,
            frequencia_estudo_mes: 1.0,
            spaced_repetition_interval: 1,
            spaced_repetition_score: 0.0,
            streak_atual: 0,
            streak_max: 0,
            points: 0.2,
            points_with_decay: 0.2,
            dominada: false,
            nivel_confianca_med_rec: Some(2),
            revisao_necessaria: false,
            in_error_notebook: false,
            high_confidence_error_flag: false,
            user_note: None,
            canonical_subject_id: None,
            canonical_topic_id: None,
            memory_stability_days: 2.0,
            memory_difficulty: 5.0,
        };

        let report = evaluate_from_activities(&[act]);
        assert_eq!(report.qualifying_sessions, 3);
        assert_eq!(report.qualifying_attempts, 135);
        assert_eq!(report.status, FatigueStatus::FatigueDetected);
        assert_eq!(report.curve_300m.len(), 6);
    }
}
