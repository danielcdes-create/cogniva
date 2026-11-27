use chrono::{Datelike, FixedOffset, Timelike, Utc, Weekday};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PrioritizationInputs {
    pub edital_weight: f64,
    pub incidence_weight: f64,
    pub retrievability: f64,
    pub verified_competence: bool,
    pub distinct_questions: usize,
    pub avg_seconds: f64,
    pub unmet_prerequisites_count: usize,
    pub dependent_count: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AdaptiveSlotKind {
    RetentionReview,
    PersistentGap,
    NewHighImpact,
}

/// Formata o cabeçalho temporal calmo do Protocolo de Entrada de 60 Segundos (`TERÇA · 22H`)
/// no fuso oficial de estudo `America/Sao_Paulo` (`UTC-3`).
pub fn format_entry_60s_header(at: chrono::DateTime<Utc>) -> String {
    let local = at.with_timezone(&FixedOffset::west_opt(10_800).unwrap());
    let day = match local.weekday() {
        Weekday::Mon => "SEGUNDA",
        Weekday::Tue => "TERÇA",
        Weekday::Wed => "QUARTA",
        Weekday::Thu => "QUINTA",
        Weekday::Fri => "SEXTA",
        Weekday::Sat => "SÁBADO",
        Weekday::Sun => "DOMINGO",
    };
    format!("{day} · {:02}H", local.hour())
}

/// Calcula a utilidade esperada por minuto de uma unidade de conhecimento,
/// descontando incerteza de amostra pequena (`< 15` questões humanas distintas) e
/// aplicando impulso suave (não-punitivo) de pré-requisitos pendentes.
pub fn priority_utility(input: &PrioritizationInputs) -> f64 {
    let r = if input.retrievability.is_finite() {
        input.retrievability.clamp(0.0, 1.0)
    } else {
        0.0
    };
    let ed_w = if input.edital_weight.is_finite() {
        input.edital_weight.max(0.0)
    } else {
        0.0
    };
    let inc_w = if input.incidence_weight.is_finite() {
        input.incidence_weight.max(0.0)
    } else {
        0.0
    };
    let avg_s = if input.avg_seconds.is_finite() {
        input.avg_seconds
    } else {
        60.0
    };
    let ev_factor = (input.distinct_questions.min(15) as f64) / 15.0;
    let m_eff = if input.verified_competence {
        0.85 * r + 0.15
    } else {
        0.65 * r * ev_factor
    };
    let gap = (1.0 - m_eff).clamp(0.05, 1.0);
    let tau_min = avg_s.clamp(20.0, 300.0) / 60.0;
    let prereq_mult = if input.unmet_prerequisites_count == 0 {
        1.0
    } else {
        0.55_f64.powi(input.unmet_prerequisites_count.min(3) as i32)
    };
    let downstream_boost = 1.0 + 0.15 * (input.dependent_count.min(4) as f64);
    let raw = (ed_w * inc_w * gap / tau_min) * prereq_mult * downstream_boost;
    if raw >= 0.0 {
        (raw * 1_000_000_000.0 + 0.5).floor() / 1_000_000_000.0
    } else {
        (raw * 1_000_000_000.0 - 0.5).ceil() / 1_000_000_000.0
    }
}

/// Extensão adaptativa metacognitiva para priorização longitudinal (Seção 4 / A09):
/// incorpora erros recorrentes com alta confiança / fundamento incompatível, avanço após
/// raciocínio funcional + transferência independente, espaçamento pós-explicação, evidência
/// escassa/conflitante e penalização de repetição consecutiva, preservando exatamente a
/// recomendação de referência (`priority_utility`) quando não há sinais metacognitivos novos.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MetacognitivePrioritySignals {
    #[serde(default)]
    pub high_confidence_error_rate: f64,
    #[serde(default)]
    pub high_confidence_error_count: usize,
    #[serde(default)]
    pub incompatible_rationale_count: usize,
    #[serde(default)]
    pub fragile_correct_rate: f64,
    #[serde(default)]
    pub failed_transfer: bool,
    #[serde(default)]
    pub functional_elimination_transfer_verified: bool,
    #[serde(default)]
    pub post_explanation_fixation_pending_spacing: bool,
    #[serde(default)]
    pub post_explanation_spaced_due: bool,
    #[serde(default)]
    pub scarce_or_conflicting_evidence: bool,
    #[serde(default)]
    pub recent_consecutive_exposures: usize,
}

impl MetacognitivePrioritySignals {
    pub fn has_active_signals(&self) -> bool {
        self.high_confidence_error_rate > 0.0
            || self.high_confidence_error_count > 0
            || self.incompatible_rationale_count > 0
            || self.fragile_correct_rate > 0.0
            || self.failed_transfer
            || self.functional_elimination_transfer_verified
            || self.post_explanation_fixation_pending_spacing
            || self.post_explanation_spaced_due
            || self.scarce_or_conflicting_evidence
            || self.recent_consecutive_exposures >= 3
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UnitRecommendationDecision {
    pub utility: f64,
    pub decision_rule_code: &'static str,
    pub reason_pt: String,
    pub recommended_mode: &'static str,
}

pub fn priority_utility_metacognitive(
    base: &PrioritizationInputs,
    signals: &MetacognitivePrioritySignals,
) -> f64 {
    let base_u = priority_utility(base);
    if !signals.has_active_signals() {
        return base_u;
    }

    // Penaliza apenas a descalibração metacognitiva / fundamento incompatível recorrente,
    // sem somar duas vezes o erro bruto já considerado em `retrievability` e `verified_competence`.
    let recurrent_misconception_bonus =
        if signals.high_confidence_error_count >= 2 && signals.incompatible_rationale_count >= 2 {
            0.25
        } else if signals.high_confidence_error_count >= 2 {
            0.12
        } else {
            0.0
        };
    let safe_hce_rate = if signals.high_confidence_error_rate.is_finite() {
        signals.high_confidence_error_rate.clamp(0.0, 1.0)
    } else {
        0.0
    };
    let safe_fragile_rate = if signals.fragile_correct_rate.is_finite() {
        signals.fragile_correct_rate.clamp(0.0, 1.0)
    } else {
        0.0
    };
    let hce_boost = 1.0 + 0.45 * safe_hce_rate + recurrent_misconception_bonus;
    let fragile_boost = 1.0 + 0.25 * safe_fragile_rate;
    let transfer_boost = if signals.failed_transfer { 1.30 } else { 1.0 };
    let conflict_boost = if signals.scarce_or_conflicting_evidence {
        1.18
    } else {
        1.0
    };
    let spaced_post_exp_boost = if signals.post_explanation_spaced_due {
        1.22
    } else {
        1.0
    };
    // Acerto por eliminação/dedução seguido de transferência independente correta permite avançar para outro conceito
    let functional_advance_mult = if signals.functional_elimination_transfer_verified {
        0.42
    } else {
        1.0
    };
    // Fixação imediata pós-explicação posterga repetição imediata para outra sessão espaçada
    let post_exp_defer_mult = if signals.post_explanation_fixation_pending_spacing {
        0.48
    } else {
        1.0
    };
    let anti_trapping = if signals.recent_consecutive_exposures >= 3 {
        0.55_f64.powi((signals.recent_consecutive_exposures - 2).min(3) as i32)
    } else {
        1.0
    };

    let adjusted = base_u
        * hce_boost
        * fragile_boost
        * transfer_boost
        * conflict_boost
        * spaced_post_exp_boost
        * functional_advance_mult
        * post_exp_defer_mult
        * anti_trapping;
    (adjusted * 1_000_000_000.0).round() / 1_000_000_000.0
}

pub fn build_metacognitive_decision_reason(
    signals: &MetacognitivePrioritySignals,
    unit_title: &str,
    fallback_reason_pt: &str,
    advanced_after_functional_transfer: bool,
) -> (&'static str, String, &'static str) {
    if signals.high_confidence_error_count >= 2
        || (signals.high_confidence_error_count >= 1 && signals.incompatible_rationale_count >= 1)
    {
        let n = signals
            .high_confidence_error_count
            .max(signals.incompatible_rationale_count)
            .max(2);
        let msg = if signals.incompatible_rationale_count >= 2 {
            format!(
                "Revisar {unit_title}. Em {n} questões houve erro com confiança alta e fundamento incompatível."
            )
        } else {
            format!(
                "Revisar {unit_title}. Em {n} questões houve erro recorrente com confiança alta."
            )
        };
        return ("RECURRENT_HIGH_CONF_MISCONCEPTION", msg, "ADAPTIVE_LOOP");
    }

    if signals.post_explanation_spaced_due || signals.post_explanation_fixation_pending_spacing {
        return (
            "POST_EXPLANATION_SPACED_FOLLOWUP",
            format!("Você acertou após a explicação. Vamos retomar {unit_title} em outra sessão."),
            "TRANSFER_PROBE",
        );
    }

    if signals.failed_transfer
        || signals.scarce_or_conflicting_evidence
        || signals.fragile_correct_rate >= 0.40
    {
        return (
            "SCARCE_OR_CONFLICTING_EVIDENCE",
            format!("Falta observar como você aplica esta regra em outro exemplo: {unit_title}."),
            "TRANSFER_PROBE",
        );
    }

    if advanced_after_functional_transfer {
        return (
            "ADVANCE_AFTER_FUNCTIONAL_TRANSFER",
            format!(
                "Você aplicou a regra em outro contexto. Sua próxima atividade avança para {unit_title}."
            ),
            "ADAPTIVE_LOOP",
        );
    }

    (
        "BASELINE_REFERENCE",
        fallback_reason_pt.to_string(),
        "ENTRY_60S",
    )
}

/// Pontua candidatas para o Protocolo de Entrada de 60 Segundos (`8–40 s`):
/// prioriza questão humana curta (`<= 380` caracteres), preferencialmente em formato
/// binário direto (`Certo · Errado · Branco`) e de assunto familiar com retenção pendente.
pub fn entry_60s_question_score(
    stem_char_len: usize,
    is_true_false: bool,
    is_familiar_topic: bool,
    has_pending_retention: bool,
    recently_answered: bool,
) -> f64 {
    let mut score = 0.0;
    if is_familiar_topic && has_pending_retention {
        score += 50.0;
    } else if is_familiar_topic {
        score += 25.0;
    } else if has_pending_retention {
        score += 20.0;
    }
    if is_true_false {
        score += 18.0;
    }
    if stem_char_len <= 260 {
        score += 22.0;
    } else if stem_char_len <= 420 {
        score += 12.0;
    } else if stem_char_len > 750 {
        score -= 20.0;
    }
    if recently_answered {
        score -= 35.0;
    }
    score
}

/// Intercala até `quantity` itens (~40% retenção próxima do esquecimento, ~40% lacunas
/// diagnósticas, ~20% novos de alto impacto), preenchendo sobras sem duplicar IDs.
pub fn interleave_buckets<T: Clone, F: Fn(&T) -> &str>(
    retention_due: &[T],
    persistent_gaps: &[T],
    new_high_impact: &[T],
    quantity: usize,
    id_of: F,
) -> Vec<T> {
    if quantity == 0 {
        return Vec::new();
    }
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::with_capacity(quantity);

    let pattern = [
        AdaptiveSlotKind::RetentionReview,
        AdaptiveSlotKind::PersistentGap,
        AdaptiveSlotKind::RetentionReview,
        AdaptiveSlotKind::PersistentGap,
        AdaptiveSlotKind::NewHighImpact,
    ];
    let mut idx_ret = 0usize;
    let mut idx_gap = 0usize;
    let mut idx_new = 0usize;
    let mut step = 0usize;

    while out.len() < quantity
        && (idx_ret < retention_due.len()
            || idx_gap < persistent_gaps.len()
            || idx_new < new_high_impact.len())
    {
        let slot = pattern[step % pattern.len()];
        step += 1;
        let picked = match slot {
            AdaptiveSlotKind::RetentionReview => {
                next_unseen(retention_due, &mut idx_ret, &seen, &id_of)
            }
            AdaptiveSlotKind::PersistentGap => {
                next_unseen(persistent_gaps, &mut idx_gap, &seen, &id_of)
            }
            AdaptiveSlotKind::NewHighImpact => {
                next_unseen(new_high_impact, &mut idx_new, &seen, &id_of)
            }
        }
        .or_else(|| next_unseen(retention_due, &mut idx_ret, &seen, &id_of))
        .or_else(|| next_unseen(persistent_gaps, &mut idx_gap, &seen, &id_of))
        .or_else(|| next_unseen(new_high_impact, &mut idx_new, &seen, &id_of));

        if let Some(item) = picked {
            seen.insert(id_of(&item).to_string());
            out.push(item);
        } else {
            break;
        }
    }
    out
}

fn next_unseen<T: Clone, F: Fn(&T) -> &str>(
    slice: &[T],
    cursor: &mut usize,
    seen: &std::collections::HashSet<String>,
    id_of: &F,
) -> Option<T> {
    while *cursor < slice.len() {
        let candidate = &slice[*cursor];
        *cursor += 1;
        if !seen.contains(id_of(candidate)) {
            return Some(candidate.clone());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn interleave_buckets_follows_40_40_20_cadence_deduplicates_and_backfills_shortfalls() {
        let ret = vec!["R1", "R2", "SHARED", "R3"];
        let gap = vec!["G1", "SHARED", "G2"];
        let new_hi = vec!["N1", "N2"];

        let mixed = interleave_buckets(&ret, &gap, &new_hi, 7, |s| s);
        assert_eq!(mixed, vec!["R1", "G1", "R2", "SHARED", "N1", "R3", "G2"]);

        // Backfills from remaining buckets when retention and gap are empty
        let only_new = interleave_buckets::<&str, _>(&[], &[], &["N1", "N1", "N2", "N3"], 5, |s| s);
        assert_eq!(only_new, vec!["N1", "N2", "N3"]);
        assert!(interleave_buckets::<&str, _>(&ret, &gap, &new_hi, 0, |s| s).is_empty());
    }

    #[test]
    fn a09_metacognitive_priority_breaks_ties_on_recurrent_hce_advances_after_functional_transfer_and_preserves_baseline(
    ) {
        let tied_base = PrioritizationInputs {
            edital_weight: 0.25,
            incidence_weight: 1.0,
            retrievability: 0.72,
            verified_competence: false,
            distinct_questions: 4,
            avg_seconds: 48.0,
            unmet_prerequisites_count: 0,
            dependent_count: 0,
        };

        // 1. Sem sinais novos -> preserva exatamente a referência!
        let empty_signals = MetacognitivePrioritySignals::default();
        assert_eq!(
            priority_utility_metacognitive(&tied_base, &empty_signals),
            priority_utility(&tied_base)
        );

        // 2. Empate exato em peso/retenção/questões: unidade com erros recorrentes de alta confiança vence!
        let hce_signals = MetacognitivePrioritySignals {
            high_confidence_error_rate: 0.50,
            high_confidence_error_count: 2,
            incompatible_rationale_count: 2,
            ..Default::default()
        };
        let u_ref = priority_utility_metacognitive(&tied_base, &empty_signals);
        let u_hce = priority_utility_metacognitive(&tied_base, &hce_signals);
        assert!(u_hce > u_ref);
        let (rule_code, reason_pt, _) = build_metacognitive_decision_reason(
            &hce_signals,
            "sensibilidade a juros",
            "Assunto familiar",
            false,
        );
        assert_eq!(rule_code, "RECURRENT_HIGH_CONF_MISCONCEPTION");
        assert!(reason_pt.contains("Revisar sensibilidade a juros"));

        // 3. Acerto por eliminação seguido de transferência independente reduz urgência e avança para outro conceito!
        let functional_verified = MetacognitivePrioritySignals {
            functional_elimination_transfer_verified: true,
            ..Default::default()
        };
        let u_func = priority_utility_metacognitive(&tied_base, &functional_verified);
        assert!(u_func < u_ref);
        let (adv_code, adv_reason, _) = build_metacognitive_decision_reason(
            &empty_signals,
            "Convexidade em renda fixa",
            "Assunto do edital",
            true,
        );
        assert_eq!(adv_code, "ADVANCE_AFTER_FUNCTIONAL_TRANSFER");
        assert!(adv_reason.contains("Você aplicou a regra em outro contexto"));
    }
}
