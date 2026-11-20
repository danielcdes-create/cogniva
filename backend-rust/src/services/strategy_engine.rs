use crate::services::fatigue_engine::performance_at_minute;
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

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UncertaintyDecisionInputs {
    pub p_base: f64,
    pub uncertainty_sd: f64,
    pub penalty: f64,
    pub item_difficulty: f64,
    pub lambda_i: f64,
    pub elapsed_minutes: f64,
    pub current_net_score: f64,
    pub remaining_items: usize,
    pub target_score: f64,
    pub minimum_score: f64,
    pub baseline_future_p: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UncertaintyDecisionResult {
    pub p0: f64,
    pub p_at_time: f64,
    pub expected_value: f64,
    pub risk_neutral_threshold: f64,
    pub prob_goal_if_answer: f64,
    pub prob_goal_if_omit: f64,
    pub posture: StrategyPosture,
    pub recommended_action: StrategyAction,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ExamMomentScenarioDto {
    pub scenario_id: String,
    pub label: String,
    pub elapsed_minutes: i32,
    pub current_net_score: f64,
    pub remaining_items: usize,
    pub target_score: f64,
    pub minimum_score: f64,
    pub calibrated_longitudinal: bool,
    pub decision_at_medium_confidence: UncertaintyDecisionResult,
}

fn round9(val: f64) -> f64 {
    if val >= 0.0 {
        (val * 1_000_000_000.0 + 0.5).floor() / 1_000_000_000.0
    } else {
        (val * 1_000_000_000.0 - 0.5).ceil() / 1_000_000_000.0
    }
}

/// Normalização de complementaridade em item estritamente binário (`Certo`/`Errado`):
/// se uma alternativa tem probabilidade estimada de 48%, a outra possui 52%.
pub fn normalize_binary_choice_probability(p_alternative: f64) -> f64 {
    let p = p_alternative.clamp(0.0, 1.0);
    round9(p.max(1.0 - p))
}

/// CDF da distribuição normal padrão via aproximação racional Abramowitz & Stegun 7.1.26,
/// idêntica bit a bit entre Python, Rust e Kotlin.
pub fn normal_cdf(z: f64) -> f64 {
    if z <= -8.0 {
        return 0.0;
    }
    if z >= 8.0 {
        return 1.0;
    }
    let sign = if z < 0.0 { -1.0 } else { 1.0 };
    let x = z.abs() / std::f64::consts::SQRT_2;
    let t = 1.0 / (1.0 + 0.327_591_1 * x);
    let a1 = 0.254_829_592;
    let a2 = -0.284_496_736;
    let a3 = 1.421_413_741;
    let a4 = -1.453_152_027;
    let a5 = 1.061_405_429;
    let poly = (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t;
    let erf_approx = sign * (1.0 - poly * (-x * x).exp());
    0.5 * (1.0 + erf_approx)
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

/// Simulador de Decisão sob Incerteza: maximiza a probabilidade de alcançar o objetivo de pontuação
/// (`target_score`) e sobreviver à nota mínima (`minimum_score`) considerando o estado atual da prova,
/// o modelo logístico de 300 minutos `p(t)` e a incerteza epistêmica `uncertainty_sd`.
pub fn simulate_decision_under_uncertainty(
    input: &UncertaintyDecisionInputs,
) -> UncertaintyDecisionResult {
    let pen = if input.penalty.is_finite() {
        input.penalty.max(0.0)
    } else {
        0.0
    };
    let (p0, pt) = performance_at_minute(
        input.p_base,
        input.item_difficulty,
        input.lambda_i,
        input.elapsed_minutes,
    );
    let ev = round9((1.0 + pen) * pt - pen);
    let p_star = if pen > 0.0 {
        round9(pen / (1.0 + pen))
    } else {
        0.0
    };
    let rem = input.remaining_items.max(1);
    let goal = input.target_score.max(input.minimum_score);
    let sd_u = input.uncertainty_sd.clamp(0.0, 0.35);

    let pf = input.baseline_future_p.clamp(0.10, 0.95);
    let qf = if pen > 0.0 { 0.85 } else { 1.0 };
    let mu_item = qf * ((1.0 + pen) * pf - pen);
    let e_x2 = qf * (pf * 1.0 + (1.0 - pf) * pen * pen);
    let var_item = (e_x2 - mu_item * mu_item).max(0.04);

    let continuation_prob = |start_score: f64, n_left: usize| -> f64 {
        if n_left == 0 {
            if start_score >= goal - 1e-9 {
                1.0
            } else {
                0.0
            }
        } else {
            let nl = n_left as f64;
            let mean_final = start_score + nl * mu_item;
            let epistemic_var = (nl * (1.0 + pen) * sd_u * 0.35).powi(2);
            let sd_final = (nl * var_item + epistemic_var).sqrt();
            normal_cdf((mean_final - goal) / sd_final)
        }
    };

    let prob_omit = continuation_prob(input.current_net_score, rem - 1);
    let p_minus = (pt - sd_u).clamp(0.01, 0.99);
    let p_plus = (pt + sd_u).clamp(0.01, 0.99);
    let prob_hit = continuation_prob(input.current_net_score + 1.0, rem - 1);
    let prob_miss = continuation_prob(input.current_net_score - pen, rem - 1);
    let prob_ans = 0.5 * (p_minus * prob_hit + (1.0 - p_minus) * prob_miss)
        + 0.5 * (p_plus * prob_hit + (1.0 - p_plus) * prob_miss);

    let (recommended_action, posture) = if pen <= 0.0 {
        (StrategyAction::Answer, StrategyPosture::MaximizeEv)
    } else if prob_omit > prob_ans + 0.002 {
        let pos = if ev > 0.0 {
            StrategyPosture::ProtectMargin
        } else {
            StrategyPosture::MaximizeEv
        };
        (StrategyAction::Omit, pos)
    } else if prob_ans > prob_omit + 0.002 {
        let pos = if ev < 0.0 {
            StrategyPosture::CalculatedRisk
        } else {
            StrategyPosture::MaximizeEv
        };
        (StrategyAction::Answer, pos)
    } else if ev > 0.0 {
        (StrategyAction::Answer, StrategyPosture::MaximizeEv)
    } else {
        (StrategyAction::Omit, StrategyPosture::MaximizeEv)
    };

    UncertaintyDecisionResult {
        p0,
        p_at_time: pt,
        expected_value: ev,
        risk_neutral_threshold: p_star,
        prob_goal_if_answer: round9(prob_ans),
        prob_goal_if_omit: round9(prob_omit),
        posture,
        recommended_action,
    }
}

/// Constrói cenários de diferentes momentos de uma prova de 300 minutos para sessões
/// de decisão de 8–12 minutos, distinguindo explicitamente calibração real de simulação matemática.
pub fn build_exam_moment_scenarios(
    medium_p: f64,
    medium_sd: f64,
    penalty: f64,
    cutoff_pct: f64,
    lambda_i: Option<f64>,
) -> Vec<ExamMomentScenarioDto> {
    let total_items = 120.0;
    let target = if cutoff_pct > 0.0 {
        (total_items * cutoff_pct / 100.0).clamp(30.0, 108.0)
    } else if penalty > 0.0 {
        66.0
    } else {
        84.0
    };
    let min_floor = (target * 0.75).round();
    let lam = lambda_i.unwrap_or(0.0);
    let calibrated = lambda_i.is_some();

    [
        ("OPENING_35M", "Início de prova · 35 min (ritmo base)", 35, (target * 0.18).round(), 98usize),
        ("MIDPOINT_165M", "Meio de prova · 165 min (maximização de VE)", 165, (target * 0.58).round(), 52usize),
        ("PROTECT_275M", "Reta final · 275 min (protegendo margem acima do corte)", 275, target + 1.5, 4usize),
        ("RECOVERY_285M", "Reta final · 285 min (1 ponto abaixo da meta)", 285, target - 1.0, 3usize),
    ]
    .into_iter()
    .map(|(sid, label, mins, cur_score, rem)| ExamMomentScenarioDto {
        scenario_id: sid.to_string(),
        label: label.to_string(),
        elapsed_minutes: mins,
        current_net_score: cur_score,
        remaining_items: rem,
        target_score: target,
        minimum_score: min_floor,
        calibrated_longitudinal: calibrated,
        decision_at_medium_confidence: simulate_decision_under_uncertainty(&UncertaintyDecisionInputs {
            p_base: medium_p,
            uncertainty_sd: medium_sd,
            penalty,
            item_difficulty: 0.50,
            lambda_i: lam,
            elapsed_minutes: mins as f64,
            current_net_score: cur_score,
            remaining_items: rem,
            target_score: target,
            minimum_score: min_floor,
            baseline_future_p: 0.72,
        }),
    })
    .collect()
}

/// Avalia a decisão rápida por faixa de confiança para orientação executiva.
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
    let (eff, posture) =
        if cutoff_pct > 0.0 && remaining_fraction > 0.0 && remaining_fraction <= 0.35 {
            let margin = current_projected_pct - cutoff_pct;
            if margin >= 6.0 {
                (
                    round9((p_star + 0.05).min(0.95)),
                    StrategyPosture::ProtectMargin,
                )
            } else if margin <= -6.0 {
                (
                    round9((p_star - 0.04).max(0.25)),
                    StrategyPosture::CalculatedRisk,
                )
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
