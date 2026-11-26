//! Modelo Multidimensional de Domínio Conceitual, KPIs Formais e Simulador de Reutilização IA.
//!
//! Implementa as 7 dimensões de domínio (desempenho, compreensão, transferência, retenção,
//! metacognição, fluência e consistência) e os 8 estados explícitos e configuráveis de domínio,
//! substituindo contagens fixas universais isoladas por evidência multidimensional auditável.

use serde::{Deserialize, Serialize};

pub const CONCEPT_MASTERY_POLICY_VERSION: &str = "mastery-v1.0";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConceptMasteryState {
    NotEvaluated,
    InsufficientEvidence,
    EmergingKnowledge,
    UnstableKnowledge,
    DevelopingMastery,
    DemonstratedMastery,
    ConsolidatedMastery,
    MasteryAtRisk,
}

impl ConceptMasteryState {
    pub const fn label_pt(self) -> &'static str {
        match self {
            Self::NotEvaluated => "Não avaliado",
            Self::InsufficientEvidence => "Evidência insuficiente",
            Self::EmergingKnowledge => "Conhecimento inicial",
            Self::UnstableKnowledge => "Conhecimento instável",
            Self::DevelopingMastery => "Domínio em desenvolvimento",
            Self::DemonstratedMastery => "Domínio demonstrado",
            Self::ConsolidatedMastery => "Domínio consolidado",
            Self::MasteryAtRisk => "Domínio em risco",
        }
    }

    pub const fn is_mastered_or_consolidated(self) -> bool {
        matches!(self, Self::DemonstratedMastery | Self::ConsolidatedMastery)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MasteryPolicyConfig {
    pub min_attempts_for_classification: usize,
    pub min_distinct_questions_for_classification: usize,
    pub min_distinct_for_demonstrated: usize,
    pub min_distinct_for_consolidated: usize,
    pub at_risk_retrievability_threshold: f64,
    pub high_conf_error_instability_threshold: f64,
    pub min_consistency_threshold: f64,
}

impl Default for MasteryPolicyConfig {
    fn default() -> Self {
        Self {
            min_attempts_for_classification: 3,
            min_distinct_questions_for_classification: 3,
            min_distinct_for_demonstrated: 4,
            min_distinct_for_consolidated: 5,
            at_risk_retrievability_threshold: 0.70,
            high_conf_error_instability_threshold: 0.25,
            min_consistency_threshold: 0.45,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ConceptMasteryInputs {
    pub attempts: usize,
    pub distinct_questions: usize,
    pub accuracy: f64,
    pub grounded_rate: f64,
    pub high_conf_error_rate: f64,
    pub transfer_attempts: usize,
    pub transfer_success_rate: f64,
    pub has_spaced_retention: bool,
    pub retrievability: f64,
    pub consistency: f64,
    pub avg_active_seconds: Option<f64>,
    pub reference_seconds: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ConceptMasteryDimensions {
    pub performance: f64,
    pub comprehension: f64,
    pub transfer: Option<f64>,
    pub retention: Option<f64>,
    pub metacognition: f64,
    pub fluency: Option<f64>,
    pub consistency: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ConceptMasteryEvaluation {
    pub state: ConceptMasteryState,
    pub state_label_pt: String,
    pub composite_score: f64,
    pub dimensions: ConceptMasteryDimensions,
    pub policy_version: String,
}

fn clamp01(v: f64) -> f64 {
    if v.is_finite() {
        v.clamp(0.0, 1.0)
    } else {
        0.0
    }
}

pub fn compute_fluency_score(avg_active_seconds: Option<f64>, reference_seconds: f64) -> Option<f64> {
    let secs = avg_active_seconds.filter(|s| s.is_finite() && *s > 0.0)?;
    let ref_s = if reference_seconds.is_finite() && reference_seconds > 5.0 {
        reference_seconds
    } else {
        60.0
    };
    // Respostas impulsivas (< 3s) não indicam fluência cognitiva; respostas em torno ou abaixo da referência pontuam melhor.
    if secs < 3.0 {
        return Some(0.25);
    }
    let ratio = secs / ref_s;
    let score = if ratio <= 1.0 {
        1.0 - 0.15 * (1.0 - ratio).clamp(0.0, 1.0)
    } else {
        (1.0 / (1.0 + 0.6 * (ratio - 1.0))).clamp(0.1, 1.0)
    };
    Some(clamp01(score))
}

pub fn classify_concept_mastery_with_policy(
    inputs: &ConceptMasteryInputs,
    policy: &MasteryPolicyConfig,
) -> ConceptMasteryEvaluation {
    let perf = clamp01(inputs.accuracy);
    let comp = clamp01(inputs.grounded_rate);
    let hce = clamp01(inputs.high_conf_error_rate);
    let metacog = clamp01(1.0 - hce);
    let cons = clamp01(inputs.consistency);
    let retr = clamp01(inputs.retrievability);
    let transfer_dim = (inputs.transfer_attempts > 0).then(|| clamp01(inputs.transfer_success_rate));
    let retention_dim = (inputs.attempts > 0).then_some(retr);
    let fluency_dim = if inputs.attempts > 0 {
        compute_fluency_score(inputs.avg_active_seconds, inputs.reference_seconds)
    } else {
        None
    };

    let state = if inputs.attempts == 0 || inputs.distinct_questions == 0 {
        ConceptMasteryState::NotEvaluated
    } else if inputs.attempts < policy.min_attempts_for_classification
        || inputs.distinct_questions < policy.min_distinct_questions_for_classification
    {
        ConceptMasteryState::InsufficientEvidence
    } else {
        let had_strong_mastery_profile = inputs.distinct_questions >= policy.min_distinct_for_demonstrated
            && perf >= 0.75
            && comp >= 0.70
            && hce <= 0.15
            && inputs.transfer_attempts >= 1
            && clamp01(inputs.transfer_success_rate) >= 0.70
            && cons >= 0.70;

        if had_strong_mastery_profile && retr < policy.at_risk_retrievability_threshold {
            ConceptMasteryState::MasteryAtRisk
        } else if hce >= policy.high_conf_error_instability_threshold
            || cons < policy.min_consistency_threshold
            || (inputs.transfer_attempts >= 1
                && clamp01(inputs.transfer_success_rate) < 0.50
                && perf >= 0.55)
        {
            ConceptMasteryState::UnstableKnowledge
        } else if inputs.distinct_questions >= policy.min_distinct_for_consolidated
            && perf >= 0.80
            && comp >= 0.75
            && hce <= 0.10
            && inputs.transfer_attempts >= 1
            && clamp01(inputs.transfer_success_rate) >= 0.75
            && inputs.has_spaced_retention
            && retr >= policy.at_risk_retrievability_threshold
            && cons >= 0.75
        {
            ConceptMasteryState::ConsolidatedMastery
        } else if had_strong_mastery_profile && retr >= policy.at_risk_retrievability_threshold {
            ConceptMasteryState::DemonstratedMastery
        } else if perf >= 0.70 && comp >= 0.60 && hce < policy.high_conf_error_instability_threshold && cons >= 0.60 {
            ConceptMasteryState::DevelopingMastery
        } else {
            ConceptMasteryState::EmergingKnowledge
        }
    };

    let composite_score = if inputs.attempts == 0 {
        0.0
    } else {
        let t_val = transfer_dim.unwrap_or(comp * 0.7);
        let r_val = retention_dim.unwrap_or(0.5);
        let f_val = fluency_dim.unwrap_or(0.7);
        clamp01(
            0.22 * perf
                + 0.22 * comp
                + 0.20 * t_val
                + 0.14 * r_val
                + 0.10 * metacog
                + 0.05 * f_val
                + 0.07 * cons,
        )
    };

    ConceptMasteryEvaluation {
        state,
        state_label_pt: state.label_pt().to_string(),
        composite_score,
        dimensions: ConceptMasteryDimensions {
            performance: perf,
            comprehension: comp,
            transfer: transfer_dim,
            retention: retention_dim,
            metacognition: metacog,
            fluency: fluency_dim,
            consistency: cons,
        },
        policy_version: CONCEPT_MASTERY_POLICY_VERSION.to_string(),
    }
}

pub fn classify_concept_mastery(inputs: &ConceptMasteryInputs) -> ConceptMasteryEvaluation {
    classify_concept_mastery_with_policy(inputs, &MasteryPolicyConfig::default())
}

/// Simulador determinístico de economia operacional com reutilização de questões complementares (CTV).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ReuseEconomySimulation {
    pub monthly_verifications: u64,
    pub reuse_rate_pct: f64,
    pub avg_cost_per_generation: f64,
    pub cost_without_reuse: f64,
    pub cost_with_reuse: f64,
    pub monthly_savings: f64,
    pub avoided_generations_monthly: u64,
    pub generated_questions_monthly: u64,
    pub annual_savings: f64,
    pub cost_reduction_pct: f64,
    pub disclaimer_pt: String,
}

fn round2(v: f64) -> f64 {
    (v * 100.0).round() / 100.0
}

pub fn simulate_ai_reuse_economy(
    monthly_verifications: u64,
    reuse_rate_pct: f64,
    avg_cost_per_generation: f64,
) -> ReuseEconomySimulation {
    let clamped_verifications = monthly_verifications.clamp(0, 10_000_000);
    let clamped_reuse_pct = if reuse_rate_pct.is_finite() {
        reuse_rate_pct.clamp(0.0, 100.0)
    } else {
        0.0
    };
    let clamped_cost = if avg_cost_per_generation.is_finite() {
        avg_cost_per_generation.clamp(0.0, 100.0)
    } else {
        0.0
    };

    let avoided = ((clamped_verifications as f64) * (clamped_reuse_pct / 100.0)).round() as u64;
    let avoided = avoided.min(clamped_verifications);
    let generated = clamped_verifications.saturating_sub(avoided);

    let without_reuse = (clamped_verifications as f64) * clamped_cost;
    let with_reuse = (generated as f64) * clamped_cost;
    let monthly_savings = (without_reuse - with_reuse).max(0.0);
    let annual_savings = monthly_savings * 12.0;
    let reduction_pct = if without_reuse > 0.0 {
        (monthly_savings / without_reuse) * 100.0
    } else {
        0.0
    };

    ReuseEconomySimulation {
        monthly_verifications: clamped_verifications,
        reuse_rate_pct: round2(clamped_reuse_pct),
        avg_cost_per_generation: (clamped_cost * 10000.0).round() / 10000.0,
        cost_without_reuse: round2(without_reuse),
        cost_with_reuse: round2(with_reuse),
        monthly_savings: round2(monthly_savings),
        avoided_generations_monthly: avoided,
        generated_questions_monthly: generated,
        annual_savings: round2(annual_savings),
        cost_reduction_pct: round2(reduction_pct),
        disclaimer_pt: "Estimativa operacional hipotética baseada no custo unitário de referência informado; não constitui comprovação contábil ou financeira auditada.".to_string(),
    }
}
