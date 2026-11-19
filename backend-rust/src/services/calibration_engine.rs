use serde::{Deserialize, Serialize};

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

fn round9(val: f64) -> f64 {
    (val * 1_000_000_000.0).round() / 1_000_000_000.0
}

/// Estimação Beta-Binomial por formato (`TRUE_FALSE` vs `MULTIPLE_CHOICE`) e faixa de confiança.
/// Priors de peso `N_0 = 6.0` estabilizam as primeiras respostas e convergem rapidamente
/// para a precisão empírica real do candidato.
pub fn calibrated_probability(
    question_type: &str,
    confidence_band: i32,
    correct: u32,
    attempts: u32,
) -> CalibrationCellDto {
    let is_tf = question_type.eq_ignore_ascii_case("TRUE_FALSE");
    let mu0 = if confidence_band <= 1 {
        if is_tf { 0.50 } else { 0.20 }
    } else if confidence_band == 2 {
        if is_tf { 0.65 } else { 0.45 }
    } else {
        0.85
    };
    let n0 = 6.0;
    let alpha0 = mu0 * n0;
    let beta0 = (1.0 - mu0) * n0;
    let safe_correct = correct.min(attempts);
    let a = alpha0 + (safe_correct as f64);
    let b = beta0 + ((attempts - safe_correct) as f64);
    let total = a + b;
    let mean = round9(a / total);
    let sd = round9(((a * b) / ((total * total) * (total + 1.0))).sqrt());

    CalibrationCellDto {
        question_type: if is_tf {
            "TRUE_FALSE".to_string()
        } else {
            "MULTIPLE_CHOICE".to_string()
        },
        confidence_band: confidence_band.clamp(1, 3),
        correct: safe_correct,
        attempts,
        calibrated_mean: mean,
        credible_sd: sd,
        sufficient_sample: attempts >= 10,
    }
}
