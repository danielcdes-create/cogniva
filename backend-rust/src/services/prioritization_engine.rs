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
