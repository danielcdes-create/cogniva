//! Correspondência conceitual, verificação da regra decisiva e semântica unificada
//! de tentativas complementares CTV (compartilhada entre Rust e Android/Kotlin).

use crate::{
    models::question::QuestionDocument,
    services::{catalog_runtime, ctv_engine::jaccard_trigram_similarity, question_contracts},
};
use bson::Document;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashSet;

pub const MAX_COMBINED_STEM_ANSWER_SIMILARITY: f64 = 0.50;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum VerificationMode {
    ConceptualTransfer,
    TopicPractice,
    IntentionalReview,
}

impl VerificationMode {
    pub const fn code(self) -> &'static str {
        match self {
            Self::ConceptualTransfer => "CONCEPTUAL_TRANSFER",
            Self::TopicPractice => "TOPIC_PRACTICE",
            Self::IntentionalReview => "INTENTIONAL_REVIEW",
        }
    }

    pub fn from_code(raw: &str) -> Self {
        match raw.trim().to_ascii_uppercase().as_str() {
            "TOPIC_PRACTICE" => Self::TopicPractice,
            "INTENTIONAL_REVIEW" | "RETENTION_CHECK" => Self::IntentionalReview,
            _ => Self::ConceptualTransfer,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct QuestionAlignmentProfile {
    pub question_id: String,
    pub subject_id: Option<String>,
    pub topic_id: Option<String>,
    pub target_concept_id: Option<String>,
    pub all_concept_ids: Vec<String>,
    pub target_concept_ambiguous: bool,
    pub tested_rule: Option<String>,
    pub decisive_excerpt: Option<String>,
    pub legal_provisions: Vec<String>,
    pub question_type: String,
    pub stem: String,
    pub correct_answer_text: Option<String>,
    pub content_revision: String,
    pub semantic_revision: String,
    pub anchored_source_question_id: Option<String>,
    pub is_ai_unconfirmed_binding: bool,
    pub is_editorial_pending_or_dubious: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum CandidateAlignmentDecision {
    Accepted {
        mode: VerificationMode,
        framing_reason_code: &'static str,
        framing_reason_pt: String,
        application_bonus: i32,
    },
    Rejected {
        reason_code: &'static str,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvIndependenceInput {
    pub verification_mode: VerificationMode,
    /// `"ANSWERED"` | `"DISMISSED"` | `"PRELOADED_ONLY"`
    pub action: String,
    /// `"PRE_FEEDBACK"` | `"POST_INSTRUCTION"` | `"POST_SIMULADO"` | `"UNKNOWN_CONDITION"`
    pub verification_timing: String,
    pub was_previously_exposed: bool,
    pub exposure_history_known: bool,
    pub had_decisive_help: bool,
    pub is_correct: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CtvIndependenceVerdict {
    pub counts_as_independent_attempt: bool,
    pub counts_as_independent_correct: bool,
    pub independent_attempts_delta: u32,
    pub independent_correct_delta: u32,
    pub classification_code: &'static str,
    pub summary_pt: &'static str,
}

pub fn trigger_cohort_for_reason(reason: Option<&str>) -> &'static str {
    match reason.map(str::trim) {
        Some("PERIODIC_MASTERY_SAMPLING" | "BASELINE_SAMPLING" | "PERIODIC_AUDIT") => {
            "PERIODIC_SAMPLING"
        }
        _ => "DIFFICULTY_DETECTED",
    }
}

/// Recursos genéricos (glossário, calculadora, consulta contextual sem gabarito) ou perda de foco
/// da janela, isoladamente, NÃO demonstram que a regra decisiva/gabarito foi revelada.
pub fn is_rule_revealing_help(resource: &str) -> bool {
    let norm = resource.trim().to_ascii_lowercase();
    matches!(
        norm.as_str(),
        "explanation"
            | "gabarito"
            | "answer_key"
            | "answer-key"
            | "editorial_card"
            | "editorial-card"
            | "dr_sincero"
            | "dr-sincero"
            | "plausibility"
            | "ai_explanation"
            | "ai-explanation"
            | "rule_hint"
            | "rule-hint"
            | "explicacao_infantil"
            | "decisive_help"
    )
}

/// Regra de domínio unificada (Seção 2): separa qualidade da verificação, condição e resultado.
/// O acerto (`is_correct`) entra EXCLUSIVAMENTE no numerador de sucesso.
pub fn evaluate_ctv_independence(input: &CtvIndependenceInput) -> CtvIndependenceVerdict {
    let action_norm = input.action.trim().to_ascii_uppercase();
    if action_norm == "PRELOADED_ONLY" {
        return CtvIndependenceVerdict {
            counts_as_independent_attempt: false,
            counts_as_independent_correct: false,
            independent_attempts_delta: 0,
            independent_correct_delta: 0,
            classification_code: "PRELOADED_NOT_DISPLAYED",
            summary_pt: "Transferência de dados sem exibição do enunciado.",
        };
    }
    if action_norm == "DISMISSED" {
        return CtvIndependenceVerdict {
            counts_as_independent_attempt: false,
            counts_as_independent_correct: false,
            independent_attempts_delta: 0,
            independent_correct_delta: 0,
            classification_code: "EXPOSED_AND_DISMISSED",
            summary_pt: "Questão complementar exibida e dispensada pelo estudante.",
        };
    }

    let timing_norm = input.verification_timing.trim().to_ascii_uppercase();
    if !input.exposure_history_known || timing_norm == "UNKNOWN_CONDITION" || timing_norm.is_empty() {
        return CtvIndependenceVerdict {
            counts_as_independent_attempt: false,
            counts_as_independent_correct: false,
            independent_attempts_delta: 0,
            independent_correct_delta: 0,
            classification_code: "UNKNOWN_CONDITION",
            summary_pt: "Registro anterior sem informação suficiente de momento ou exposição.",
        };
    }

    if input.verification_mode == VerificationMode::TopicPractice {
        return CtvIndependenceVerdict {
            counts_as_independent_attempt: false,
            counts_as_independent_correct: false,
            independent_attempts_delta: 0,
            independent_correct_delta: 0,
            classification_code: "TOPIC_PRACTICE_RECORDED",
            summary_pt: "Prática complementar do tópico registrada sem medir transferência do conceito original.",
        };
    }

    if input.verification_mode == VerificationMode::IntentionalReview || input.was_previously_exposed {
        return CtvIndependenceVerdict {
            counts_as_independent_attempt: false,
            counts_as_independent_correct: false,
            independent_attempts_delta: 0,
            independent_correct_delta: 0,
            classification_code: "PRIOR_EXPOSURE_REVIEW",
            summary_pt: "Item já visto anteriormente pelo estudante; contabilizado como revisão.",
        };
    }

    if timing_norm == "POST_INSTRUCTION" || input.had_decisive_help {
        return CtvIndependenceVerdict {
            counts_as_independent_attempt: false,
            counts_as_independent_correct: false,
            independent_attempts_delta: 0,
            independent_correct_delta: 0,
            classification_code: "POST_INSTRUCTION_FIXATION",
            summary_pt: "Resposta realizada após explicação ou ajuda que revelou a regra decisiva.",
        };
    }

    if timing_norm != "PRE_FEEDBACK" {
        return CtvIndependenceVerdict {
            counts_as_independent_attempt: false,
            counts_as_independent_correct: false,
            independent_attempts_delta: 0,
            independent_correct_delta: 0,
            classification_code: "DEFERRED_VERIFICATION",
            summary_pt: "Verificação realizada fora da janela pré-feedback imediata.",
        };
    }

    // Inédita, alinhada ao conceito/regra, pré-feedback e sem ajuda reveladora:
    // +1 tentativa independente independentemente de acertar ou errar!
    if input.is_correct {
        CtvIndependenceVerdict {
            counts_as_independent_attempt: true,
            counts_as_independent_correct: true,
            independent_attempts_delta: 1,
            independent_correct_delta: 1,
            classification_code: "INDEPENDENT_CORRECT",
            summary_pt: "Aplicação independente correta antes do feedback.",
        }
    } else {
        CtvIndependenceVerdict {
            counts_as_independent_attempt: true,
            counts_as_independent_correct: false,
            independent_attempts_delta: 1,
            independent_correct_delta: 0,
            classification_code: "INDEPENDENT_ERROR",
            summary_pt: "Falha de aplicação independente observada antes do feedback.",
        }
    }
}

fn normalize_compact_text(raw: &str) -> String {
    raw.to_lowercase()
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn domain_content_tokens(raw: &str) -> HashSet<String> {
    const STOPWORDS: &[&str] = &[
        "para", "como", "sobre", "entre", "qual", "quais", "quando", "onde", "pelo", "pela",
        "pelos", "pelas", "uma", "uns", "umas", "dos", "das", "nos", "nas", "com", "sem",
        "mais", "menos", "maior", "menor", "assinale", "opcao", "opção", "correta", "correto",
        "errado", "errada", "certo", "acerca", "relacao", "relação", "segundo", "conforme",
        "pode", "deve", "sendo", "sera", "será", "item", "questao", "questão", "alternativa",
        "aplicacao", "aplicação", "criterio", "critério", "cobrado", "oficial", "gabarito",
        "significado", "definicao", "definição", "conceito", "apresenta", "informa", "pergunta",
    ];
    raw.to_lowercase()
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .filter(|w| w.chars().count() >= 4 && !STOPWORDS.contains(w))
        .map(str::to_string)
        .collect()
}

fn is_definition_prompt(stem: &str) -> bool {
    let lower = stem.to_lowercase();
    lower.contains("significado ")
        || lower.contains("definição ")
        || lower.contains("definicao ")
        || lower.contains("o que mede")
        || lower.contains("o que é ")
        || lower.contains("o que expressa")
        || lower.contains("o que caracteriza")
        || lower.contains("conceito de ")
        || lower.contains("define-se como")
}

fn is_application_scenario(stem: &str) -> bool {
    let lower = stem.to_lowercase();
    let has_numbers = stem.chars().any(|c| c.is_ascii_digit());
    has_numbers
        || lower.contains("dois títulos")
        || lower.contains("duas carteiras")
        || lower.contains("choque")
        || lower.contains("caso ")
        || lower.contains("situação")
        || lower.contains("durante ")
        || lower.contains("suponha")
        || lower.contains("considere que um")
        || lower.contains("considere dois")
        || lower.contains("servidor ")
        || lower.contains("contribuinte ")
        || lower.contains("auditoria")
}

pub fn detect_paraphrase_or_preserved_cues(
    source: &QuestionAlignmentProfile,
    candidate: &QuestionAlignmentProfile,
) -> bool {
    if jaccard_trigram_similarity(&source.stem, &candidate.stem)
        >= crate::services::ctv_engine::MAX_SOURCE_SIMILARITY_FOR_TRANSFER
    {
        return true;
    }

    if source.question_type == "MULTIPLE_CHOICE" && candidate.question_type == "MULTIPLE_CHOICE" {
        if let (Some(sa), Some(ca)) = (
            source.correct_answer_text.as_deref(),
            candidate.correct_answer_text.as_deref(),
        ) {
            let norm_sa = normalize_compact_text(sa);
            let norm_ca = normalize_compact_text(ca);
            if norm_sa.chars().count() >= 14 && norm_sa == norm_ca {
                let stem_sim = jaccard_trigram_similarity(&source.stem, &candidate.stem);
                if stem_sim >= 0.20 {
                    return true;
                }
            }
            let combined_src = format!("{} {}", source.stem, sa);
            let combined_cand = format!("{} {}", candidate.stem, ca);
            if jaccard_trigram_similarity(&combined_src, &combined_cand)
                >= MAX_COMBINED_STEM_ANSWER_SIMILARITY
            {
                return true;
            }
        }
    }

    false
}

fn rule_overlap_ratio(source: &QuestionAlignmentProfile, candidate: &QuestionAlignmentProfile) -> f64 {
    if !source.legal_provisions.is_empty() && !candidate.legal_provisions.is_empty() {
        let shared_prov = source.legal_provisions.iter().any(|sp| {
            candidate
                .legal_provisions
                .iter()
                .any(|cp| normalize_compact_text(sp) == normalize_compact_text(cp))
        });
        if shared_prov {
            return 0.60;
        }
    }

    let src_text = format!(
        "{} {}",
        source.tested_rule.as_deref().unwrap_or(""),
        source.stem
    );
    let cand_text = format!(
        "{} {}",
        candidate.tested_rule.as_deref().unwrap_or(""),
        candidate.stem
    );
    let src_tokens = domain_content_tokens(&src_text);
    let cand_tokens = domain_content_tokens(&cand_text);
    if src_tokens.is_empty() || cand_tokens.is_empty() {
        return 0.0;
    }
    let inter = src_tokens.intersection(&cand_tokens).count() as f64;
    let min_len = src_tokens.len().min(cand_tokens.len()).max(1) as f64;
    inter / min_len
}

pub fn evaluate_candidate_alignment(
    source: &QuestionAlignmentProfile,
    candidate: &QuestionAlignmentProfile,
    allow_seen_review: bool,
) -> CandidateAlignmentDecision {
    if !source.question_id.is_empty() && source.question_id == candidate.question_id {
        return CandidateAlignmentDecision::Rejected {
            reason_code: "SAME_QUESTION_ID",
        };
    }

    let exact_content_dup = !source.content_revision.is_empty()
        && source.content_revision == candidate.content_revision;
    let exact_semantic_dup = !source.semantic_revision.is_empty()
        && source.semantic_revision == candidate.semantic_revision;

    if exact_content_dup || exact_semantic_dup {
        if allow_seen_review {
            return CandidateAlignmentDecision::Accepted {
                mode: VerificationMode::IntentionalReview,
                framing_reason_code: "INTENTIONAL_RETENTION_REVIEW",
                framing_reason_pt:
                    "Revisão intencional de retenção com material já conhecido pelo estudante."
                        .to_string(),
                application_bonus: 0,
            };
        }
        return CandidateAlignmentDecision::Rejected {
            reason_code: "DUPLICATE_CONTENT_OR_SEMANTIC_REVISION",
        };
    }

    if detect_paraphrase_or_preserved_cues(source, candidate) {
        if allow_seen_review {
            return CandidateAlignmentDecision::Accepted {
                mode: VerificationMode::IntentionalReview,
                framing_reason_code: "INTENTIONAL_RETENTION_REVIEW",
                framing_reason_pt:
                    "Revisão intencional aproveitando formulação próxima do item conhecido."
                        .to_string(),
                application_bonus: 0,
            };
        }
        return CandidateAlignmentDecision::Rejected {
            reason_code: "PARAPHRASE_OR_PRESERVED_ANSWER_CUE",
        };
    }

    if candidate.is_editorial_pending_or_dubious {
        return CandidateAlignmentDecision::Rejected {
            reason_code: "CANDIDATE_EDITORIAL_PENDING_OR_DUBIOUS",
        };
    }

    let app_bonus = if is_definition_prompt(&source.stem) && is_application_scenario(&candidate.stem)
    {
        35
    } else if is_application_scenario(&candidate.stem) {
        15
    } else {
        0
    };

    if source.target_concept_ambiguous {
        return CandidateAlignmentDecision::Accepted {
            mode: VerificationMode::TopicPractice,
            framing_reason_code: "TARGET_CONCEPT_AMBIGUOUS_TOPIC_PRACTICE",
            framing_reason_pt:
                "A questão original abrange múltiplos conceitos sem alvo único inequívoco; item enquadrado como prática do tópico."
                    .to_string(),
            application_bonus: 0,
        };
    }

    if let Some(src_cid) = source.target_concept_id.as_deref() {
        if candidate.target_concept_ambiguous {
            return CandidateAlignmentDecision::Accepted {
                mode: VerificationMode::TopicPractice,
                framing_reason_code: "CANDIDATE_CONCEPT_AMBIGUOUS_TOPIC_PRACTICE",
                framing_reason_pt:
                    "A questão candidata envolve mais de um conceito; enquadrada como prática do tópico."
                        .to_string(),
                application_bonus: 0,
            };
        }

        let cand_matches_concept = candidate.target_concept_id.as_deref() == Some(src_cid)
            || candidate.all_concept_ids.iter().any(|c| c == src_cid);

        if cand_matches_concept {
            // Se o candidato for de IA sem confirmação humana, só aceita para transferência se
            // estiver ancorado ao conceito já confirmado na questão original e com regra coerente.
            let overlap = rule_overlap_ratio(source, candidate);
            if candidate.is_ai_unconfirmed_binding
                && candidate.anchored_source_question_id.as_deref()
                    != Some(source.question_id.as_str())
                && overlap < 0.12
            {
                return CandidateAlignmentDecision::Accepted {
                    mode: VerificationMode::TopicPractice,
                    framing_reason_code: "AI_UNCONFIRMED_CONCEPT_RULE_WEAK",
                    framing_reason_pt:
                        "Vínculo conceitual declarado apenas por IA sem sustentação suficiente da mesma regra decisiva; tratado como prática do tópico."
                            .to_string(),
                    application_bonus: 0,
                };
            }

            return CandidateAlignmentDecision::Accepted {
                mode: VerificationMode::ConceptualTransfer,
                framing_reason_code: "SAME_CONCEPT_AND_RULE_NEW_CONTEXT",
                framing_reason_pt:
                    "Mesmo conceito-alvo e regra decisiva sustentados em situação distinta."
                        .to_string(),
                application_bonus: app_bonus,
            };
        }

        return CandidateAlignmentDecision::Accepted {
            mode: VerificationMode::TopicPractice,
            framing_reason_code: if candidate.target_concept_id.is_some() {
                "TOPIC_ONLY_CONCEPT_DIVERGENT"
            } else {
                "TOPIC_ONLY_UNCONFIRMED_CONCEPT"
            },
            framing_reason_pt:
                "O item pertence ao mesmo tópico, mas avalia conceito ou regra distinta da questão original; oferecido como prática do tópico."
                    .to_string(),
            application_bonus: 0,
        };
    }

    // Questão original sem `concept_id` explícito:
    // se o candidato tem conceito diferente explícito e não compartilha a regra da questão original, é apenas prática do tópico.
    let overlap = rule_overlap_ratio(source, candidate);
    let is_anchored_to_source = !source.question_id.is_empty()
        && candidate.anchored_source_question_id.as_deref() == Some(source.question_id.as_str());

    if candidate.is_ai_unconfirmed_binding && !is_anchored_to_source && overlap < 0.25 {
        return CandidateAlignmentDecision::Accepted {
            mode: VerificationMode::TopicPractice,
            framing_reason_code: "AI_SELF_DECLARED_WITHOUT_CONFIRMED_TARGET",
            framing_reason_pt:
                "Declaração de IA sem conceito confirmado na questão original; mantida no nível de prática do tópico."
                    .to_string(),
            application_bonus: 0,
        };
    }

    if is_anchored_to_source || overlap >= 0.22 {
        return CandidateAlignmentDecision::Accepted {
            mode: VerificationMode::ConceptualTransfer,
            framing_reason_code: "SHARED_DECISIVE_RULE_NEW_CONTEXT",
            framing_reason_pt:
                "Mesma regra decisiva identificada em contexto de aplicação distinto.".to_string(),
            application_bonus: app_bonus,
        };
    }

    CandidateAlignmentDecision::Accepted {
        mode: VerificationMode::TopicPractice,
        framing_reason_code: "TOPIC_ONLY_RULE_DIVERGENT",
        framing_reason_pt:
            "Apenas o tópico coincide e a regra decisiva diverge ou não é determinável; classificado como prática do tópico."
                .to_string(),
        application_bonus: 0,
    }
}

fn infer_single_catalog_concept_for_topic(
    topic_id: &str,
    stem: &str,
    tested_rule: Option<&str>,
) -> Option<String> {
    let cat = question_contracts::catalog_ref();
    let concepts = catalog_runtime::effective_concepts(&cat);
    let haystack = normalize_compact_text(&format!("{} {}", stem, tested_rule.unwrap_or("")));
    if haystack.is_empty() {
        return None;
    }

    let mut matched = Vec::new();
    for c in concepts {
        if c["status"].as_str() != Some("ACTIVE") || c["topic_id"].as_str() != Some(topic_id) {
            continue;
        }
        let Some(cid) = c["concept_id"].as_str() else {
            continue;
        };
        let mut phrases = Vec::new();
        if let Some(lbl) = c["label"].as_str() {
            phrases.push(lbl);
        }
        if let Some(aliases) = c["aliases"].as_array() {
            for a in aliases.iter().filter_map(Value::as_str) {
                phrases.push(a);
            }
        }
        let hits = phrases.iter().any(|p| {
            let norm_p = normalize_compact_text(p);
            norm_p.chars().count() >= 6 && haystack.contains(&norm_p)
        });
        if hits && !matched.iter().any(|x: &String| x == cid) {
            matched.push(cid.to_string());
        }
    }

    if matched.len() == 1 {
        matched.into_iter().next()
    } else {
        None
    }
}

pub fn resolve_question_alignment_profile(
    raw: &Document,
    q_doc: &QuestionDocument,
) -> QuestionAlignmentProfile {
    let dmeta = raw.get_document("diagnostic_meta").ok();
    let subject_id = q_doc.canonical_subject_id.clone().or_else(|| {
        dmeta
            .and_then(|d| d.get_str("subject_id").ok())
            .map(str::to_string)
    });
    let topic_id = q_doc.canonical_topic_id.clone().or_else(|| {
        dmeta
            .and_then(|d| d.get_str("topic_id").ok())
            .map(str::to_string)
    });

    let card = q_doc.effective_editorial_card();
    let tested_rule = card.as_ref().map(|c| c.tested_rule.clone());
    let decisive_excerpt = card.as_ref().map(|c| c.decisive_stem_excerpt.clone());
    let legal_provisions = card
        .as_ref()
        .map(|c| c.legal_provisions.clone())
        .unwrap_or_default();

    let mut primary_direct_concepts = Vec::new();
    let mut primary_concepts = Vec::new();
    let mut all_confirmed = Vec::new();
    let mut classification_confirmed = false;

    if let Some(cls) = &q_doc.classification {
        if cls.get("state").and_then(Value::as_str) == Some("CONFIRMED") {
            classification_confirmed = true;
            if let Some(links) = cls.get("links").and_then(Value::as_array) {
                for l in links {
                    if let Some(cid) = l
                        .get("concept_id")
                        .and_then(Value::as_str)
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                    {
                        if !all_confirmed.iter().any(|x| x == cid) {
                            all_confirmed.push(cid.to_string());
                        }
                        if l.get("role").and_then(Value::as_str) == Some("PRIMARY") {
                            if !primary_concepts.iter().any(|x| x == cid) {
                                primary_concepts.push(cid.to_string());
                            }
                            if l.get("coverage").and_then(Value::as_str) == Some("DIRECT")
                                && !primary_direct_concepts.iter().any(|x| x == cid)
                            {
                                primary_direct_concepts.push(cid.to_string());
                            }
                        }
                    }
                }
            }
        }
    }

    let dmeta_concepts: Vec<String> = dmeta
        .and_then(|d| d.get_array("concept_ids").ok())
        .map(|arr| {
            arr.iter()
                .filter_map(|b| b.as_str().map(str::trim).filter(|s| !s.is_empty()))
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default();

    let validation_status = dmeta
        .and_then(|d| d.get_str("validation_status").ok())
        .unwrap_or("");
    let quality_status = dmeta
        .and_then(|d| d.get_str("quality_status").ok())
        .unwrap_or("");
    let is_editorial_pending_or_dubious = validation_status == "PENDING_HUMAN_REVIEW"
        || validation_status == "REJECTED"
        || quality_status == "SUSPENDED";

    let has_ai_origin = raw
        .get_array("origins")
        .ok()
        .is_some_and(|arr| {
            arr.iter()
                .filter_map(|b| b.as_document())
                .any(|o| o.get_str("kind").ok() == Some("AI_GENERATED"))
        })
        || dmeta.and_then(|d| d.get_str("generation_origin").ok()) == Some("AI_ON_DEMAND");

    let is_ai_unconfirmed_binding = has_ai_origin
        && !classification_confirmed
        && validation_status != "HUMAN_CONFIRMED";

    let candidate_pool = if !primary_direct_concepts.is_empty() {
        primary_direct_concepts
    } else if !primary_concepts.is_empty() {
        primary_concepts
    } else if !all_confirmed.is_empty() {
        all_confirmed.clone()
    } else if !is_editorial_pending_or_dubious && !dmeta_concepts.is_empty() {
        dmeta_concepts.clone()
    } else {
        Vec::new()
    };

    let (target_concept_id, target_concept_ambiguous) = match candidate_pool.len() {
        0 => {
            let inferred = topic_id.as_deref().and_then(|tid| {
                infer_single_catalog_concept_for_topic(tid, &q_doc.question, tested_rule.as_deref())
            });
            (inferred, false)
        }
        1 => (Some(candidate_pool[0].clone()), false),
        _ => {
            // Múltiplos conceitos primários: tenta desambiguar pela regra decisiva / enunciado
            let disambiguated = topic_id
                .as_deref()
                .and_then(|tid| {
                    infer_single_catalog_concept_for_topic(
                        tid,
                        &q_doc.question,
                        tested_rule.as_deref(),
                    )
                })
                .filter(|cid| candidate_pool.iter().any(|c| c == cid));
            if let Some(cid) = disambiguated {
                (Some(cid), false)
            } else {
                (None, true)
            }
        }
    };

    let mut combined_concepts = all_confirmed;
    for c in dmeta_concepts {
        if !combined_concepts.iter().any(|x| x == &c) {
            combined_concepts.push(c);
        }
    }
    if let Some(tc) = &target_concept_id {
        if !combined_concepts.iter().any(|x| x == tc) {
            combined_concepts.push(tc.clone());
        }
    }

    let correct_answer_text = q_doc
        .answer_indices()
        .ok()
        .and_then(|idx| idx.first().copied())
        .and_then(|i| q_doc.answers.get(i).cloned());

    let content_revision = raw
        .get_str("content_revision")
        .ok()
        .map(str::to_string)
        .or_else(|| q_doc.revision().ok())
        .unwrap_or_default();
    let semantic_revision = raw
        .get_str("semantic_revision")
        .ok()
        .map(str::to_string)
        .unwrap_or_default();
    let anchored_source_question_id = dmeta
        .and_then(|d| d.get_str("source_question_id").ok())
        .map(str::to_string);

    QuestionAlignmentProfile {
        question_id: q_doc.id.to_hex(),
        subject_id,
        topic_id,
        target_concept_id,
        all_concept_ids: combined_concepts,
        target_concept_ambiguous,
        tested_rule,
        decisive_excerpt,
        legal_provisions,
        question_type: q_doc.effective_question_type().to_string(),
        stem: q_doc.question.clone(),
        correct_answer_text,
        content_revision,
        semantic_revision,
        anchored_source_question_id,
        is_ai_unconfirmed_binding,
        is_editorial_pending_or_dubious,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a01_duration_vs_convexity_prefers_duration_and_classifies_convexity_as_topic_practice() {
        let src_duration = QuestionAlignmentProfile {
            question_id: "q_orig_duration".into(),
            subject_id: Some("MFIN".into()),
            topic_id: Some("MFIN_01_004".into()),
            target_concept_id: Some("CON_MFIN_DURATION_MODIFICADA".into()),
            all_concept_ids: vec!["CON_MFIN_DURATION_MODIFICADA".into()],
            tested_rule: Some(
                "A duration modificada mede a sensibilidade percentual aproximada do preço de um título a variações na taxa de juros."
                    .into(),
            ),
            question_type: "MULTIPLE_CHOICE".into(),
            stem: "Em finanças de renda fixa no Banco Central, qual é o significado econômico da duration modificada de um título prefixado?".into(),
            correct_answer_text: Some(
                "Mede a elasticidade ou sensibilidade percentual aproximada do preço diante de pequena mudança na taxa de retorno.".into(),
            ),
            content_revision: "rev_orig".into(),
            ..Default::default()
        };

        let cand_duration_app = QuestionAlignmentProfile {
            question_id: "q_comp_duration".into(),
            subject_id: Some("MFIN".into()),
            topic_id: Some("MFIN_01_004".into()),
            target_concept_id: Some("CON_MFIN_DURATION_MODIFICADA".into()),
            all_concept_ids: vec!["CON_MFIN_DURATION_MODIFICADA".into()],
            tested_rule: Some("Para um mesmo pequeno choque de juros, o título com maior duration modificada sofre maior variação percentual aproximada de preço.".into()),
            question_type: "MULTIPLE_CHOICE".into(),
            stem: "Dois títulos públicos A e B possuem durations modificadas de 2,4 e 5,8 anos, respectivamente. Sob um mesmo pequeno choque paralelo de +0,25 ponto percentual na curva de juros, qual título apresenta maior variação percentual aproximada no preço?".into(),
            correct_answer_text: Some("O título B, cuja variação percentual aproximada é cerca de 1,45%.".into()),
            content_revision: "rev_comp_dur".into(),
            ..Default::default()
        };

        let cand_convexity = QuestionAlignmentProfile {
            question_id: "q_comp_convexity".into(),
            subject_id: Some("MFIN".into()),
            topic_id: Some("MFIN_01_004".into()),
            target_concept_id: Some("CON_MFIN_CONVEXIDADE".into()),
            all_concept_ids: vec!["CON_MFIN_CONVEXIDADE".into()],
            tested_rule: Some("A convexidade mede a curvatura da relação preço-taxa em títulos de renda fixa.".into()),
            question_type: "MULTIPLE_CHOICE".into(),
            stem: "Na análise de carteiras de renda fixa, o que define a propriedade de convexidade de um título?".into(),
            correct_answer_text: Some("A taxa de variação da duration em função de oscilações na taxa de juros.".into()),
            content_revision: "rev_comp_conv".into(),
            ..Default::default()
        };

        let dec_dur = evaluate_candidate_alignment(&src_duration, &cand_duration_app, false);
        assert_eq!(
            dec_dur,
            CandidateAlignmentDecision::Accepted {
                mode: VerificationMode::ConceptualTransfer,
                framing_reason_code: "SAME_CONCEPT_AND_RULE_NEW_CONTEXT",
                framing_reason_pt:
                    "Mesmo conceito-alvo e regra decisiva sustentados em situação distinta.".into(),
                application_bonus: 35,
            }
        );

        let dec_conv = evaluate_candidate_alignment(&src_duration, &cand_convexity, false);
        match dec_conv {
            CandidateAlignmentDecision::Accepted {
                mode,
                framing_reason_code,
                ..
            } => {
                assert_eq!(mode, VerificationMode::TopicPractice);
                assert_eq!(framing_reason_code, "TOPIC_ONLY_CONCEPT_DIVERGENT");
            }
            other => panic!("Expected TopicPractice, got {other:?}"),
        }

        // Efeito no diagnóstico: prática do tópico NUNCA gera tentativa independente no conceito original
        let indep_conv = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: VerificationMode::TopicPractice,
            action: "ANSWERED".into(),
            verification_timing: "PRE_FEEDBACK".into(),
            was_previously_exposed: false,
            exposure_history_known: true,
            had_decisive_help: false,
            is_correct: true,
        });
        assert_eq!(indep_conv.independent_attempts_delta, 0);
        assert_eq!(indep_conv.independent_correct_delta, 0);
        assert_eq!(indep_conv.classification_code, "TOPIC_PRACTICE_RECORDED");
    }

    #[test]
    fn a02_paraphrase_and_cue_preserving_clones_are_rejected_even_with_same_concept() {
        let src = QuestionAlignmentProfile {
            question_id: "q1".into(),
            target_concept_id: Some("CON_MFIN_DURATION_MODIFICADA".into()),
            all_concept_ids: vec!["CON_MFIN_DURATION_MODIFICADA".into()],
            question_type: "MULTIPLE_CHOICE".into(),
            stem: "Qual é o significado econômico da duration modificada na análise de risco de títulos públicos prefixados?".into(),
            correct_answer_text: Some("Sensibilidade percentual aproximada do preço a pequenas variações de juros.".into()),
            content_revision: "rev1".into(),
            ..Default::default()
        };

        let paraphrase = QuestionAlignmentProfile {
            question_id: "q2".into(),
            target_concept_id: Some("CON_MFIN_DURATION_MODIFICADA".into()),
            all_concept_ids: vec!["CON_MFIN_DURATION_MODIFICADA".into()],
            question_type: "MULTIPLE_CHOICE".into(),
            stem: "Na avaliação de risco de títulos prefixados, qual o significado da duration modificada?".into(),
            correct_answer_text: Some("Sensibilidade percentual aproximada do preço a pequenas variações de juros.".into()),
            content_revision: "rev2".into(),
            ..Default::default()
        };

        assert_eq!(
            evaluate_candidate_alignment(&src, &paraphrase, false),
            CandidateAlignmentDecision::Rejected {
                reason_code: "PARAPHRASE_OR_PRESERVED_ANSWER_CUE"
            }
        );
    }

    #[test]
    fn a03_a04_a05_independence_truth_table_separates_condition_and_outcome() {
        // 1. Inédita, alinhada, pré-feedback, correta -> +1 tentativa, +1 acerto
        let ok = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: VerificationMode::ConceptualTransfer,
            action: "ANSWERED".into(),
            verification_timing: "PRE_FEEDBACK".into(),
            was_previously_exposed: false,
            exposure_history_known: true,
            had_decisive_help: false,
            is_correct: true,
        });
        assert_eq!((ok.independent_attempts_delta, ok.independent_correct_delta), (1, 1));

        // 2. Mesmas condições, resposta ERRADA -> +1 tentativa, 0 acertos!
        let err = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: VerificationMode::ConceptualTransfer,
            action: "ANSWERED".into(),
            verification_timing: "PRE_FEEDBACK".into(),
            was_previously_exposed: false,
            exposure_history_known: true,
            had_decisive_help: false,
            is_correct: false,
        });
        assert_eq!((err.independent_attempts_delta, err.independent_correct_delta), (1, 0));
        assert_eq!(err.classification_code, "INDEPENDENT_ERROR");

        // 3. Ver gabarito agora (dispensa) -> 0 tentativas, 0 acertos
        let dism = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: VerificationMode::ConceptualTransfer,
            action: "DISMISSED".into(),
            verification_timing: "PRE_FEEDBACK".into(),
            was_previously_exposed: false,
            exposure_history_known: true,
            had_decisive_help: false,
            is_correct: false,
        });
        assert_eq!((dism.independent_attempts_delta, dism.independent_correct_delta), (0, 0));
        assert_eq!(dism.classification_code, "EXPOSED_AND_DISMISSED");

        // 4. Apenas pré-carregada -> 0 tentativas, 0 acertos
        let pre = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: VerificationMode::ConceptualTransfer,
            action: "PRELOADED_ONLY".into(),
            verification_timing: "PRE_FEEDBACK".into(),
            was_previously_exposed: false,
            exposure_history_known: true,
            had_decisive_help: false,
            is_correct: false,
        });
        assert_eq!((pre.independent_attempts_delta, pre.independent_correct_delta), (0, 0));
        assert_eq!(pre.classification_code, "PRELOADED_NOT_DISPLAYED");

        // 5. Resposta após explicação decisiva -> 0 tentativas, 0 acertos (fixação pós-instrução)
        let post = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: VerificationMode::ConceptualTransfer,
            action: "ANSWERED".into(),
            verification_timing: "PRE_FEEDBACK".into(),
            was_previously_exposed: false,
            exposure_history_known: true,
            had_decisive_help: true,
            is_correct: true,
        });
        assert_eq!((post.independent_attempts_delta, post.independent_correct_delta), (0, 0));
        assert_eq!(post.classification_code, "POST_INSTRUCTION_FIXATION");

        // 6. Histórico legado incapaz de estabelecer ineditismo -> UNKNOWN_CONDITION
        let unk = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: VerificationMode::ConceptualTransfer,
            action: "ANSWERED".into(),
            verification_timing: "PRE_FEEDBACK".into(),
            was_previously_exposed: false,
            exposure_history_known: false,
            had_decisive_help: false,
            is_correct: true,
        });
        assert_eq!((unk.independent_attempts_delta, unk.independent_correct_delta), (0, 0));
        assert_eq!(unk.classification_code, "UNKNOWN_CONDITION");
    }
}
