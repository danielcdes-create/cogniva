//! Calibração Beta-Binomial e avaliação temporal prequencial (walk-forward) do Brier Score,
//! separando estritamente formatos (`TRUE_FALSE` vs `MULTIPLE_CHOICE`) e mantendo os 5 níveis
//! ordinais de confiança declarada sem vazamento de dados futuros (Seção 3.3 e 3.4 — A07).

use crate::services::metacognitive_engine::DeclaredConfidence;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const CALIBRATION_POLICY_VERSION: &str = "calibration-walkforward-v2.0";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CalibrationCellDto {
    pub question_type: String,
    pub confidence_band: i32,
    pub correct: u32,
    pub attempts: u32,
    pub calibrated_mean: f64,
    pub credible_sd: f64,
    pub sufficient_sample: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WalkforwardCalibrationInput {
    pub attempt_id: String,
    pub occurred_at_ms: i64,
    pub question_type: String,
    pub declared_confidence: DeclaredConfidence,
    pub is_correct: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WalkforwardStepTrace {
    pub attempt_id: String,
    pub occurred_at_ms: i64,
    pub question_type: String,
    pub declared_confidence: DeclaredConfidence,
    pub predicted_probability: f64,
    pub is_correct: bool,
    pub squared_error: f64,
    pub used_cold_start_prior: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FormatConfidenceCalibrationCellDto {
    pub question_type: String,
    pub declared_confidence: DeclaredConfidence,
    pub label_pt: &'static str,
    pub attempts: usize,
    pub correct: usize,
    pub observed_accuracy_pct: Option<f64>,
    pub model_predicted_mean: f64,
    pub credible_sd: f64,
    pub sufficiency_status: &'static str,
    pub coverage_sentence_pt: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WalkforwardCalibrationReport {
    pub policy_version: &'static str,
    pub overall_brier_score: Option<f64>,
    pub overall_brier_raw: Option<f64>,
    pub true_false_brier_score: Option<f64>,
    pub multiple_choice_brier_score: Option<f64>,
    pub eligible_attempts_count: usize,
    pub cold_start_predictions_count: usize,
    pub sufficiency_status: &'static str,
    pub legacy_band_mapping: HashMap<&'static str, &'static str>,
    pub cells_by_format: Vec<FormatConfidenceCalibrationCellDto>,
    pub traces: Vec<WalkforwardStepTrace>,
}

fn round9(val: f64) -> f64 {
    (val * 1_000_000_000.0).round() / 1_000_000_000.0
}

fn round4(val: f64) -> f64 {
    (val * 10_000.0).round() / 10_000.0
}

pub fn normalize_calibration_format(raw: &str) -> &'static str {
    if raw.trim().eq_ignore_ascii_case("TRUE_FALSE") {
        "TRUE_FALSE"
    } else {
        "MULTIPLE_CHOICE"
    }
}

/// Mapeamento explícito entre bandas legadas (`confidence_level` 1..=3 com `confidence_explicit == true`)
/// e as 5 declarações ordinais vigentes.
pub fn legacy_band_mapping_table() -> HashMap<&'static str, &'static str> {
    HashMap::from([
        ("0_explicit", "GUESS"),
        ("1_explicit", "GUESS"),
        ("2_explicit", "MEDIUM"),
        ("3_explicit", "HIGH"),
        ("unconfirmed_default", "UNDECLARED_EXCLUDED"),
    ])
}

/// Política inicial (cold-start, `N_0 = 6.0`) por formato e nível de confiança declarada.
/// Aproveita os parâmetros das bandas legadas (banda 1 -> GUESS, banda 2 -> MEDIUM, banda 3 -> HIGH)
/// e diferencia `LOW` e `ABSOLUTE`, mantendo `TRUE_FALSE` e `MULTIPLE_CHOICE` separados.
pub fn level_prior_mean(question_type: &str, confidence: DeclaredConfidence) -> f64 {
    let is_tf = normalize_calibration_format(question_type) == "TRUE_FALSE";
    match confidence {
        DeclaredConfidence::Guess => {
            if is_tf {
                0.50
            } else {
                0.20
            }
        }
        DeclaredConfidence::Low => {
            if is_tf {
                0.56
            } else {
                0.32
            }
        }
        DeclaredConfidence::Medium => {
            if is_tf {
                0.65
            } else {
                0.45
            }
        }
        DeclaredConfidence::High => 0.85,
        DeclaredConfidence::VeryHigh => 0.92,
    }
}

pub fn calibrated_level_posterior(
    question_type: &str,
    confidence: DeclaredConfidence,
    correct: u32,
    attempts: u32,
) -> (f64, f64) {
    let mu0 = level_prior_mean(question_type, confidence);
    let n0 = 6.0;
    let alpha0 = mu0 * n0;
    let beta0 = (1.0 - mu0) * n0;
    let safe_correct = correct.min(attempts);
    let a = alpha0 + (safe_correct as f64);
    let b = beta0 + ((attempts - safe_correct) as f64);
    let total = a + b;
    let mean = round9(a / total);
    let sd = round9(((a * b) / ((total * total) * (total + 1.0))).sqrt());
    (mean, sd)
}

/// Estimação Beta-Binomial por formato (`TRUE_FALSE` vs `MULTIPLE_CHOICE`) e faixa legada de confiança (1..=3).
pub fn calibrated_probability(
    question_type: &str,
    confidence_band: i32,
    correct: u32,
    attempts: u32,
) -> CalibrationCellDto {
    let fmt = normalize_calibration_format(question_type);
    let mapped_conf = match confidence_band.clamp(1, 3) {
        1 => DeclaredConfidence::Guess,
        2 => DeclaredConfidence::Medium,
        _ => DeclaredConfidence::High,
    };
    let (mean, sd) = calibrated_level_posterior(fmt, mapped_conf, correct, attempts);

    CalibrationCellDto {
        question_type: fmt.to_string(),
        confidence_band: confidence_band.clamp(1, 3),
        correct: correct.min(attempts),
        attempts,
        calibrated_mean: mean,
        credible_sd: sd,
        sufficient_sample: attempts >= 10,
    }
}

/// Cálculo puro e verificável do Brier Score: média de `(p - y)^2`, onde `p` é a probabilidade
/// prevista ANTES do resultado e `y` vale `1` para acerto e `0` para erro.
/// Exemplo de referência (Seção 3.4 / A07): `[(0.70, true), (0.40, false)]` -> `0.125`.
pub fn compute_brier_score(predictions_and_outcomes: &[(f64, bool)]) -> Option<f64> {
    if predictions_and_outcomes.is_empty() {
        return None;
    }
    let sum_sq: f64 = predictions_and_outcomes
        .iter()
        .map(|(p, ok)| {
            let p_clamped = p.clamp(0.0, 1.0);
            let y = if *ok { 1.0 } else { 0.0 };
            (p_clamped - y).powi(2)
        })
        .sum();
    Some(round9(sum_sq / (predictions_and_outcomes.len() as f64)))
}

pub fn sufficiency_status_for_sample(n: usize, min_sufficient: usize) -> &'static str {
    if n == 0 {
        "EMPTY"
    } else if n < min_sufficient {
        "INITIAL_EVIDENCE"
    } else {
        "SUFFICIENT"
    }
}

pub fn format_confidence_coverage_sentence(
    confidence: DeclaredConfidence,
    question_type: Option<&str>,
    correct: usize,
    attempts: usize,
) -> String {
    let conf_phrase = match confidence {
        DeclaredConfidence::Guess => "chute assumido",
        DeclaredConfidence::Low => "confiança baixa",
        DeclaredConfidence::Medium => "confiança média",
        DeclaredConfidence::High => "confiança alta",
        DeclaredConfidence::VeryHigh => "certeza elevada",
    };
    let fmt_phrase = match question_type {
        Some("TRUE_FALSE") => " de Certo/Errado",
        Some("MULTIPLE_CHOICE") => " de múltipla escolha",
        _ => "",
    };
    if attempts == 0 {
        return format!(
            "Ainda não há questões{fmt_phrase} respondidas em que você declarou {conf_phrase}."
        );
    }
    let suffix = if attempts < 5 {
        " (evidência inicial)"
    } else {
        ""
    };
    format!(
        "Você acertou {correct} de {attempts} questões{fmt_phrase} em que declarou {conf_phrase}{suffix}."
    )
}

/// Avalia a calibração temporal estrita (walk-forward):
/// ordena por `(occurred_at_ms, attempt_id)` e, para cada tentativa elegível, calcula `p_t` usando
/// SOMENTE o histórico anterior daquele estudante no formato e faixa pertinentes.
pub fn evaluate_walkforward_calibration(
    events: &[WalkforwardCalibrationInput],
) -> WalkforwardCalibrationReport {
    let mut sorted = events.to_vec();
    sorted.sort_by(|a, b| {
        (a.occurred_at_ms, a.attempt_id.as_str()).cmp(&(b.occurred_at_ms, b.attempt_id.as_str()))
    });

    let mut running_cells: HashMap<(&'static str, DeclaredConfidence), (u32, u32)> = HashMap::new();
    let mut all_pairs: Vec<(f64, bool)> = Vec::with_capacity(sorted.len());
    let mut tf_pairs: Vec<(f64, bool)> = Vec::new();
    let mut mc_pairs: Vec<(f64, bool)> = Vec::new();
    let mut traces: Vec<WalkforwardStepTrace> = Vec::with_capacity(sorted.len());
    let mut cold_start_count = 0usize;

    for ev in sorted {
        let fmt = normalize_calibration_format(&ev.question_type);
        let (prior_corr, prior_att) = running_cells
            .get(&(fmt, ev.declared_confidence))
            .copied()
            .unwrap_or((0, 0));
        let is_cold = prior_att == 0;
        if is_cold {
            cold_start_count += 1;
        }
        let (p_pred, _) =
            calibrated_level_posterior(fmt, ev.declared_confidence, prior_corr, prior_att);
        let y = if ev.is_correct { 1.0 } else { 0.0 };
        let sq_err = round9((p_pred - y).powi(2));

        all_pairs.push((p_pred, ev.is_correct));
        if fmt == "TRUE_FALSE" {
            tf_pairs.push((p_pred, ev.is_correct));
        } else {
            mc_pairs.push((p_pred, ev.is_correct));
        }

        traces.push(WalkforwardStepTrace {
            attempt_id: ev.attempt_id,
            occurred_at_ms: ev.occurred_at_ms,
            question_type: fmt.to_string(),
            declared_confidence: ev.declared_confidence,
            predicted_probability: p_pred,
            is_correct: ev.is_correct,
            squared_error: sq_err,
            used_cold_start_prior: is_cold,
        });

        // Atualiza o histórico SOMENTE APÓS registrar a previsão da tentativa atual!
        let next_entry = running_cells
            .entry((fmt, ev.declared_confidence))
            .or_insert((0, 0));
        if ev.is_correct {
            next_entry.0 += 1;
        }
        next_entry.1 += 1;
    }

    let mut cells_by_format = Vec::with_capacity(10);
    for fmt in ["TRUE_FALSE", "MULTIPLE_CHOICE"] {
        for dc in DeclaredConfidence::ALL {
            let (corr, att) = running_cells.get(&(fmt, dc)).copied().unwrap_or((0, 0));
            let (post_mean, sd) = calibrated_level_posterior(fmt, dc, corr, att);
            let obs_pct = if att > 0 {
                Some(((100.0 * (corr as f64) / (att as f64)) * 100.0).round() / 100.0)
            } else {
                None
            };
            cells_by_format.push(FormatConfidenceCalibrationCellDto {
                question_type: fmt.to_string(),
                declared_confidence: dc,
                label_pt: dc.label_pt(),
                attempts: att as usize,
                correct: corr as usize,
                observed_accuracy_pct: obs_pct,
                model_predicted_mean: round4(post_mean),
                credible_sd: round4(sd),
                sufficiency_status: sufficiency_status_for_sample(att as usize, 5),
                coverage_sentence_pt: format_confidence_coverage_sentence(
                    dc,
                    Some(fmt),
                    corr as usize,
                    att as usize,
                ),
            });
        }
    }

    let raw_brier = compute_brier_score(&all_pairs);
    let overall_brier = raw_brier.map(round4);
    let tf_brier = compute_brier_score(&tf_pairs).map(round4);
    let mc_brier = compute_brier_score(&mc_pairs).map(round4);

    WalkforwardCalibrationReport {
        policy_version: CALIBRATION_POLICY_VERSION,
        overall_brier_score: overall_brier,
        overall_brier_raw: raw_brier,
        true_false_brier_score: tf_brier,
        multiple_choice_brier_score: mc_brier,
        eligible_attempts_count: all_pairs.len(),
        cold_start_predictions_count: cold_start_count,
        sufficiency_status: sufficiency_status_for_sample(all_pairs.len(), 5),
        legacy_band_mapping: legacy_band_mapping_table(),
        cells_by_format,
        traces,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a07_verifiable_brier_reference_example_equals_0_125() {
        let brier = compute_brier_score(&[(0.70, true), (0.40, false)]).unwrap();
        assert!((brier - 0.125).abs() < 1e-12);
        assert!(compute_brier_score(&[]).is_none());
    }

    #[test]
    fn a07_walkforward_calibration_keeps_formats_separate_and_future_outcomes_never_leak_into_past() {
        let base_events = vec![
            WalkforwardCalibrationInput {
                attempt_id: "att-1".into(),
                occurred_at_ms: 1_000,
                question_type: "MULTIPLE_CHOICE".into(),
                declared_confidence: DeclaredConfidence::High,
                is_correct: true,
            },
            WalkforwardCalibrationInput {
                attempt_id: "att-2".into(),
                occurred_at_ms: 2_000,
                question_type: "TRUE_FALSE".into(),
                declared_confidence: DeclaredConfidence::Guess,
                is_correct: false,
            },
            WalkforwardCalibrationInput {
                attempt_id: "att-3".into(),
                occurred_at_ms: 3_000,
                question_type: "MULTIPLE_CHOICE".into(),
                declared_confidence: DeclaredConfidence::High,
                is_correct: true,
            },
            WalkforwardCalibrationInput {
                attempt_id: "att-4".into(),
                occurred_at_ms: 4_000,
                question_type: "MULTIPLE_CHOICE".into(),
                declared_confidence: DeclaredConfidence::High,
                is_correct: true,
            },
        ];

        let mut future_flipped = base_events.clone();
        // Flip ONLY future outcome att-4 (and insert a future TRUE_FALSE item)
        future_flipped[3].is_correct = false;
        future_flipped.push(WalkforwardCalibrationInput {
            attempt_id: "att-5".into(),
            occurred_at_ms: 5_000,
            question_type: "TRUE_FALSE".into(),
            declared_confidence: DeclaredConfidence::High,
            is_correct: false,
        });

        let rep_a = evaluate_walkforward_calibration(&base_events);
        let rep_b = evaluate_walkforward_calibration(&future_flipped);

        // Predictions for att-1, att-2, att-3, and even att-4 (before att-4's outcome!) MUST be identical!
        for i in 0..4 {
            assert_eq!(
                rep_a.traces[i].predicted_probability,
                rep_b.traces[i].predicted_probability,
                "Future outcome leaked into prediction at step {i}"
            );
        }
        // Format separation: TRUE_FALSE GUESS cold-start prior is 0.50, not MULTIPLE_CHOICE 0.20!
        assert!((rep_a.traces[1].predicted_probability - 0.50).abs() < 1e-9);
        assert!(rep_a.traces[0].used_cold_start_prior);
        assert!(rep_a.traces[1].used_cold_start_prior);
        assert!(!rep_a.traces[2].used_cold_start_prior);
    }
}
