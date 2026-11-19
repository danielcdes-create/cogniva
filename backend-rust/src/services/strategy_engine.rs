use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyPosture {
    MaximizeEv,
    ProtectMargin,
    CalculatedRisk,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyAction {
    Answer,
    Omit,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StrategyDecision {
    pub expected_value: f64,
    pub risk_neutral_threshold: f64,
    pub effective_threshold: f64,
    pub posture: StrategyPosture,
    pub recommended_action: StrategyAction,
}

fn round9(val: f64) -> f64 {
    (val * 1_000_000_000.0).round() / 1_000_000_000.0
}

/// Particionamento determinístico de 20% (`Holdout`) para verificação de aprendizagem
/// e simulados comparáveis, ativado quando o pool elegível possui ao menos 5 questões.
pub fn is_holdout_reserved(user_id: i64, question_id: &str, pool_size: usize) -> bool {
    if pool_size < 5 {
        return false;
    }
    let digest = Sha256::digest(format!("cogniva-holdout-v1:{user_id}:{question_id}").as_bytes());
    let head = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]);
    (head % 5) == 0
}

/// Avalia a decisão ótima entre responder ou omitir com base na probabilidade calibrada `p_calibrated`,
/// na penalidade de erro `penalty` (`lambda`) do edital e na posição relativa à nota de corte.
/// Para Cebraspe (+1 / -1 / 0), `lambda = 1.0` -> `VE = 2p - 1` e `p* = 0.50`.
pub fn evaluate_strategy(
    p_calibrated: f64,
    penalty: f64,
    cutoff_pct: f64,
    current_projected_pct: f64,
    remaining_fraction: f64,
) -> StrategyDecision {
    let p = p_calibrated.clamp(0.0, 1.0);
    let lam = if penalty.is_finite() {
        penalty.max(0.0)
    } else {
        0.0
    };
    let ev = round9((1.0 + lam) * p - lam);

    if lam <= 0.0 {
        return StrategyDecision {
            expected_value: ev,
            risk_neutral_threshold: 0.0,
            effective_threshold: 0.0,
            posture: StrategyPosture::MaximizeEv,
            recommended_action: StrategyAction::Answer,
        };
    }

    let p_star = round9(lam / (1.0 + lam));
    let (eff, posture) = if cutoff_pct > 0.0 && remaining_fraction > 0.0 && remaining_fraction <= 0.35 {
        let margin = current_projected_pct - cutoff_pct;
        if margin >= 6.0 {
            (round9((p_star + 0.05).min(0.95)), StrategyPosture::ProtectMargin)
        } else if margin <= -6.0 {
            (round9((p_star - 0.04).max(0.25)), StrategyPosture::CalculatedRisk)
        } else {
            (p_star, StrategyPosture::MaximizeEv)
        }
    } else {
        (p_star, StrategyPosture::MaximizeEv)
    };

    let recommended_action = if p > eff + 1e-9 {
        StrategyAction::Answer
    } else {
        StrategyAction::Omit
    };

    StrategyDecision {
        expected_value: ev,
        risk_neutral_threshold: p_star,
        effective_threshold: eff,
        posture,
        recommended_action,
    }
}
