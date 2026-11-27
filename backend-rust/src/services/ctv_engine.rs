//! Motor de Verificação Adaptativa de Domínio Conceitual (`Conceptual Transfer Verification — CTV`).
//!
//! Opera estritamente sob a diretriz **Database First, AI on Demand**:
//! 1. Avalia a política configurável de acionamento (9 critérios + amostragem periódica + supressão em `SIMULADO` + cooldown).
//! 2. Consulta prioritariamente `cogniva.todasQuestoes` (questões humanas curadas e questões CTV já aprovadas) no mesmo conceito/tópico.
//! 3. Cruza o histórico individual de exposição (`userActivityLog` + `conceptual_transfer_attempts`), distinguindo transferência inédita de retenção.
//! 4. Aciona a xAI sob demanda **somente** quando não existe questão elegível inédita no banco, com trava de concorrência por `(unidade, propósito)`.
//! 5. Valida estrutural, semântica e pedagogicamente o candidato gerado (schema V2.4, coerência de gabarito, filtro antipistas, independência lexical/antiparáfrase e deduplicação JCS/quase-duplicata).
//! 6. Persiste permanentemente a questão aprovada em `cogniva.todasQuestoes` (sem dados pessoais do aluno), tornando-a reutilizável para toda a base.

pub use crate::services::{ctv_alignment::*, ctv_reconciliation::*};
use crate::{
    error::AppError,
    models::question::QuestionDto,
    security::SessionUser,
    services::{
        ai_tutor,
        learning_analytics::DEFAULT_REFERENCE_COST_PER_GENERATION_USD,
        metacognitive_engine::{
            classify_preliminary_matrix, CognitiveStrategy, CtvTriggerPriority, DeclaredConfidence,
            MetacognitiveMatrixInputs, PreliminaryDiagnosis,
        },
        question_compatibility, question_contracts,
        question_eligibility::{self, EligibilityMode, TrustedReviewContext},
        question_storage, rate_limit,
    },
    AppState,
};
use bson::{doc, oid::ObjectId, Document};
use chrono::Utc;
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, OnceLock},
    time::Instant,
};
use tokio::sync::Mutex;

pub const CTV_POLICY_VERSION: &str = "ctv-policy-v2.0";
pub const CTV_PROMPT_ID: &str = "ctv-generator";
pub const CTV_PROMPT_VERSION: &str = "ctv-generator/2";
pub const MAX_SOURCE_SIMILARITY_FOR_TRANSFER: f64 = 0.45;
pub const MAX_BANK_SIMILARITY_FOR_DEDUP: f64 = 0.65;
pub const CTV_BANK_PAGE_SIZE: i64 = 40;
pub const DEFAULT_MAX_SEARCH_PAGES: usize = 60;

static GENERATION_LOCKS: OnceLock<Mutex<HashMap<String, Arc<Mutex<()>>>>> = OnceLock::new();

async fn generation_lock_for(key: &str) -> Arc<Mutex<()>> {
    let table = GENERATION_LOCKS.get_or_init(|| Mutex::new(HashMap::new()));
    let mut guard = table.lock().await;
    if guard.len() > 2048 {
        guard.retain(|_, arc| Arc::strong_count(arc) > 1);
    }
    guard
        .entry(key.to_string())
        .or_insert_with(|| Arc::new(Mutex::new(())))
        .clone()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvPolicyConfig {
    pub enabled: bool,
    pub max_per_session: u16,
    pub cooldown_attempts: u16,
    pub periodic_mastery_sample_pct: u8,
    pub baseline_sample_pct: u8,
}

impl Default for CtvPolicyConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            max_per_session: 3,
            cooldown_attempts: 2,
            periodic_mastery_sample_pct: 15,
            baseline_sample_pct: 10,
        }
    }
}

impl CtvPolicyConfig {
    pub fn from_env() -> Self {
        let mut cfg = Self::default();
        if let Ok(v) = std::env::var("CTV_ENABLED") {
            cfg.enabled = !matches!(
                v.trim().to_ascii_lowercase().as_str(),
                "0" | "false" | "no" | "off"
            );
        }
        if let Ok(v) = std::env::var("CTV_MAX_PER_SESSION") {
            if let Ok(n) = v.trim().parse::<u16>() {
                cfg.max_per_session = n.min(20);
            }
        }
        if let Ok(v) = std::env::var("CTV_COOLDOWN_ATTEMPTS") {
            if let Ok(n) = v.trim().parse::<u16>() {
                cfg.cooldown_attempts = n.min(20);
            }
        }
        cfg
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvEvaluateRequest {
    pub parent_attempt_id: String,
    pub source_question_id: String,
    pub session_uuid: String,
    pub quiz_mode: String,
    pub is_correct: bool,
    #[serde(default)]
    pub is_blank: bool,
    #[serde(default)]
    pub declared_confidence: Option<DeclaredConfidence>,
    #[serde(default)]
    pub cognitive_strategy: Option<CognitiveStrategy>,
    #[serde(default)]
    pub confidence_level: i32,
    #[serde(default)]
    pub confidence_explicit: bool,
    #[serde(default)]
    pub session_ctv_count: u16,
    #[serde(default)]
    pub attempts_since_last_ctv: u16,
    #[serde(default)]
    pub force_post_instruction: bool,
    #[serde(default)]
    pub rationale_contradicted: bool,
    #[serde(skip)]
    pub max_search_pages: Option<usize>,
    #[serde(skip)]
    pub simulate_search_error_after_page: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvEvaluateResponse {
    pub triggered: bool,
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub offer_id: Option<String>,
    pub trigger_reason: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trigger_cohort: Option<String>,
    pub diagnostic_purpose: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub verification_mode: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub framing_reason_code: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub framing_reason_pt: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_concept_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_tested_rule: Option<String>,
    pub delivery_source: Option<String>,
    pub verification_timing: String,
    pub was_previously_exposed: bool,
    pub complementary_question: Option<QuestionDto>,
    pub student_framing_pt: Option<String>,
    pub policy_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvSubmitRequest {
    pub ctv_attempt_id: String,
    pub parent_attempt_id: String,
    pub source_question_id: String,
    pub complementary_question_id: String,
    pub session_uuid: String,
    pub verification_timing: String,
    pub chosen_index: usize,
    #[serde(default)]
    pub chosen_text: String,
    #[serde(default)]
    pub declared_confidence: Option<DeclaredConfidence>,
    #[serde(default)]
    pub cognitive_strategy: Option<CognitiveStrategy>,
    #[serde(default)]
    pub active_seconds: i32,
    #[serde(default)]
    pub was_previously_exposed: bool,
    #[serde(default)]
    pub offer_id: Option<String>,
    #[serde(default)]
    pub occurred_at: Option<String>,
    #[serde(default)]
    pub displayed_at: Option<String>,
    #[serde(default)]
    pub consulted_resources: Vec<String>,
    #[serde(default)]
    pub focus_loss_count: u16,
    #[serde(default)]
    pub had_decisive_help: bool,
    #[serde(default)]
    pub trigger_reason: Option<String>,
    #[serde(default)]
    pub delivery_source: Option<String>,
    #[serde(default)]
    pub verification_mode: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvSubmitResponse {
    pub ctv_attempt_id: String,
    #[serde(default)]
    pub offer_id: String,
    pub parent_attempt_id: String,
    pub complementary_question_id: String,
    pub is_correct: bool,
    pub correct_index: usize,
    pub explanation: Option<String>,
    pub verification_timing: String,
    #[serde(default)]
    pub was_previously_exposed: bool,
    pub counts_as_independent_transfer: bool,
    #[serde(default)]
    pub verification_mode: String,
    #[serde(default)]
    pub classification_code: String,
    #[serde(default)]
    pub framing_reason_code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_concept_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_tested_rule: Option<String>,
    pub consolidated_diagnostic_title_pt: String,
    pub consolidated_diagnostic_message_pt: String,
}

#[derive(Debug, Deserialize)]
pub(crate) struct GeneratedCtvPayload {
    question: String,
    answers: Vec<String>,
    correct_index: usize,
    explanation: String,
    #[serde(default)]
    tested_rule: String,
    #[serde(default)]
    decisive_stem_excerpt: String,
    #[serde(default)]
    estimated_difficulty: String,
    #[serde(default)]
    normative_certainty: String,
    #[serde(default)]
    concept_adherence: String,
}

fn deterministic_sample_bucket(seed: &str) -> u8 {
    let digest = Sha256::digest(seed.as_bytes());
    digest[0] % 100
}

fn normalize_tokens_and_trigrams(raw: &str) -> HashSet<String> {
    let cleaned: String = raw
        .to_lowercase()
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect();
    let mut set = HashSet::new();
    let words: Vec<&str> = cleaned
        .split_whitespace()
        .filter(|w| w.chars().count() >= 3)
        .collect();
    for w in &words {
        set.insert(format!("w:{w}"));
    }
    let joined = words.join(" ");
    let chars: Vec<char> = joined.chars().collect();
    for win in chars.windows(4) {
        let gram: String = win.iter().collect();
        set.insert(format!("g:{gram}"));
    }
    set
}

/// Similaridade lexical e de n-gramas (Jaccard) para bloquear paráfrases rasas e quase-duplicatas.
pub fn jaccard_trigram_similarity(a: &str, b: &str) -> f64 {
    let set_a = normalize_tokens_and_trigrams(a);
    let set_b = normalize_tokens_and_trigrams(b);
    if set_a.is_empty() && set_b.is_empty() {
        return 1.0;
    }
    if set_a.is_empty() || set_b.is_empty() {
        return 0.0;
    }
    let inter = set_a.intersection(&set_b).count() as f64;
    let union = set_a.union(&set_b).count() as f64;
    inter / union
}

pub fn decide_ctv_trigger(
    policy: &CtvPolicyConfig,
    req: &CtvEvaluateRequest,
    consistent_history: bool,
    recurrent_high_conf_errors: bool,
    intra_concept_inconsistent: bool,
    insufficient_unit_evidence: bool,
    has_prior_independent_transfer: bool,
) -> (
    bool,
    &'static str,
    Option<&'static str>,
    Option<&'static str>,
) {
    if !policy.enabled || req.is_blank {
        return (false, "not_triggered", None, None);
    }
    if req.quiz_mode.trim().eq_ignore_ascii_case("SIMULADO") && !req.force_post_instruction {
        return (
            false,
            "deferred_post_simulado",
            Some("SIMULADO_DEFERRED"),
            Some("CONCEPTUAL_TRANSFER"),
        );
    }
    if req.session_ctv_count >= policy.max_per_session {
        return (
            false,
            "cooldown_suppressed",
            Some("SESSION_CAP_REACHED"),
            None,
        );
    }

    let decl_conf = req.declared_confidence.or_else(|| {
        DeclaredConfidence::from_legacy_band(
            req.confidence_level,
            req.confidence_explicit || matches!(req.confidence_level, 1 | 3),
        )
    });
    let (diag, _, prio) = classify_preliminary_matrix(&MetacognitiveMatrixInputs {
        is_blank: req.is_blank,
        is_correct: req.is_correct,
        declared_confidence: decl_conf,
        cognitive_strategy: req.cognitive_strategy,
        consistent_history,
        rationale_contradicted: req.rationale_contradicted,
    });

    if req.session_ctv_count > 0
        && req.attempts_since_last_ctv < policy.cooldown_attempts
        && prio != CtvTriggerPriority::High
    {
        return (false, "cooldown_suppressed", Some("ATTEMPT_COOLDOWN"), None);
    }

    match diag {
        PreliminaryDiagnosis::PossibleCasualHit => {
            return (
                true,
                "triggered",
                Some("GUESS_HIT"),
                Some("GUESS_CONFIRMATION"),
            );
        }
        PreliminaryDiagnosis::InsecureKnowledge => {
            return (
                true,
                "triggered",
                Some("LOW_CONFIDENCE_HIT"),
                Some("LOW_CONFIDENCE_CHECK"),
            );
        }
        PreliminaryDiagnosis::PossibleConceptualMisconception => {
            if recurrent_high_conf_errors || req.session_ctv_count == 0 {
                return (
                    true,
                    "triggered",
                    Some("HIGH_CONFIDENCE_MISCONCEPTION"),
                    Some("MISCONCEPTION_PROBE"),
                );
            }
        }
        PreliminaryDiagnosis::UnconfirmedMastery => {
            if req
                .cognitive_strategy
                .is_some_and(CognitiveStrategy::is_surface_or_fragile)
            {
                return (
                    true,
                    "triggered",
                    Some("SURFACE_RECOGNITION_HIT"),
                    Some("SURFACE_RECOGNITION_CHECK"),
                );
            }
            if !has_prior_independent_transfer || insufficient_unit_evidence {
                return (
                    true,
                    "triggered",
                    Some("TRANSFER_VERIFICATION_NEEDED"),
                    Some("CONCEPTUAL_TRANSFER"),
                );
            }
        }
        PreliminaryDiagnosis::PossibleConceptualMastery => {
            if intra_concept_inconsistent {
                return (
                    true,
                    "triggered",
                    Some("INTRA_CONCEPT_INCONSISTENCY"),
                    Some("CONCEPTUAL_TRANSFER"),
                );
            }
            if !has_prior_independent_transfer {
                return (
                    true,
                    "triggered",
                    Some("TRANSFER_VERIFICATION_NEEDED"),
                    Some("CONCEPTUAL_TRANSFER"),
                );
            }
            let bucket = deterministic_sample_bucket(&format!(
                "periodic:{}:{}",
                req.parent_attempt_id, req.source_question_id
            ));
            if bucket < policy.periodic_mastery_sample_pct {
                return (
                    true,
                    "triggered",
                    Some("PERIODIC_MASTERY_SAMPLING"),
                    Some("PERIODIC_AUDIT"),
                );
            }
        }
        PreliminaryDiagnosis::FunctionalReasoning => {
            if !has_prior_independent_transfer {
                let bucket = deterministic_sample_bucket(&format!(
                    "functional:{}:{}",
                    req.parent_attempt_id, req.source_question_id
                ));
                if bucket < 35 {
                    return (
                        true,
                        "triggered",
                        Some("TRANSFER_VERIFICATION_NEEDED"),
                        Some("CONCEPTUAL_TRANSFER"),
                    );
                }
            }
        }
        _ => {}
    }

    // Amostragem ocasional de linha de base sem sinais de fragilidade (evita restringir CTV apenas a itens problemáticos)
    if req.is_correct {
        let bucket = deterministic_sample_bucket(&format!(
            "baseline:{}:{}",
            req.parent_attempt_id, req.source_question_id
        ));
        if bucket < policy.baseline_sample_pct {
            return (
                true,
                "triggered",
                Some("BASELINE_SAMPLING"),
                Some("CONCEPTUAL_TRANSFER"),
            );
        }
    }

    (false, "not_triggered", None, None)
}

#[derive(Debug, Clone)]
pub(crate) struct CtvAuditEvent<'a> {
    pub request_id: &'a str,
    pub outcome: &'a str,
    pub subject_id: &'a str,
    pub topic_id: &'a str,
    pub concept_id: Option<&'a str>,
    pub diagnostic_purpose: &'a str,
    pub question_id: Option<&'a str>,
    pub model_name: Option<&'a str>,
    pub latency_ms: Option<u64>,
    pub provider_calls: i32,
    pub persisted_item: bool,
    pub duplicate_barred: bool,
    pub delivered_to_student: bool,
    pub delivery_channel: &'a str,
    pub estimated_cost_usd: f64,
}

async fn log_ctv_audit_idempotent(state: &AppState, ev: CtvAuditEvent<'_>) {
    // Auditoria operacional impessoal: chave determinística derivada da oferta sem persistir user_id pessoal.
    let audit_id = format!("ctv-req:{}", ev.request_id);
    let _ = state
        .db
        .collection::<Document>("ai_generation_audit")
        .update_one(
            doc! { "_id": &audit_id },
            doc! {
                "$setOnInsert": {
                    "_id": &audit_id,
                    "request_id": ev.request_id,
                    "outcome": ev.outcome,
                    "subject_id": ev.subject_id,
                    "topic_id": ev.topic_id,
                    "concept_id": ev.concept_id,
                    "diagnostic_purpose": ev.diagnostic_purpose,
                    "question_id": ev.question_id,
                    "model": ev.model_name,
                    "prompt_id": CTV_PROMPT_ID,
                    "prompt_version": CTV_PROMPT_VERSION,
                    "latency_ms": ev.latency_ms.map(|v| v as i64),
                    "provider_calls": ev.provider_calls,
                    "persisted_item": ev.persisted_item,
                    "duplicate_barred": ev.duplicate_barred,
                    "delivered_to_student": ev.delivered_to_student,
                    "delivery_channel": ev.delivery_channel,
                    "estimated_cost_usd": ev.estimated_cost_usd,
                    "cost_source": "ESTIMATED_REFERENCE",
                    "recorded_at": bson::DateTime::now(),
                }
            },
        )
        .upsert(true)
        .await;
}

fn validate_generated_candidate(
    cand: &GeneratedCtvPayload,
    source_stem: &str,
) -> Result<(), &'static str> {
    let stem = cand.question.trim();
    if stem.chars().count() < 30 || stem.chars().count() > 4_000 {
        return Err("STEM_LENGTH_OUT_OF_RANGE");
    }
    if cand.answers.len() != 5 {
        return Err("REQUIRES_FIVE_OPTIONS");
    }
    if cand.correct_index >= 5 {
        return Err("INVALID_CORRECT_INDEX");
    }
    let exp = cand.explanation.trim();
    if exp.chars().count() < 40 || exp.chars().count() > 12_000 {
        return Err("EXPLANATION_TOO_SHORT");
    }

    let mut seen_opts = HashSet::new();
    let mut lengths = Vec::with_capacity(5);
    for opt in &cand.answers {
        let clean = opt.trim();
        if clean.chars().count() < 2 || clean.chars().count() > 1_500 {
            return Err("OPTION_LENGTH_INVALID");
        }
        if clean.contains('✅') || clean.contains('❌') || clean.contains('✔') {
            return Err("OPTION_CONTAINS_SPOILER_MARKER");
        }
        if !seen_opts.insert(clean.to_lowercase()) {
            return Err("DUPLICATE_OPTIONS");
        }
        lengths.push(clean.chars().count() as f64);
    }

    if stem.contains('✅') || stem.contains('❌') || stem.contains('✔') {
        return Err("STEM_CONTAINS_SPOILER_MARKER");
    }
    let stem_lower = stem.to_lowercase();
    for forbidden_meta in [
        "questão anterior",
        "item anterior",
        "enunciado anterior",
        "como vimos na questão",
    ] {
        if stem_lower.contains(forbidden_meta) {
            return Err("META_REFERENCE_TO_PRIOR_QUESTION");
        }
    }

    // Anti-cue: verifica assimetria grosseira de tamanho da alternativa correta vs. distratores
    let correct_len = lengths[cand.correct_index];
    let distractor_mean = (lengths.iter().sum::<f64>() - correct_len) / 4.0;
    if distractor_mean >= 12.0 && correct_len > 65.0 && correct_len > distractor_mean * 2.8 {
        return Err("ANSWER_LENGTH_CUE_DETECTED");
    }

    // Anti-paráfrase: exige cenário distinto do enunciado de origem
    if jaccard_trigram_similarity(source_stem, stem) >= MAX_SOURCE_SIMILARITY_FOR_TRANSFER {
        return Err("PARAPHRASE_OF_SOURCE_QUESTION");
    }

    Ok(())
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn build_v2_ctv_question_contract(
    oid: ObjectId,
    cand: &GeneratedCtvPayload,
    source_question_id: &str,
    subject_id: &str,
    subject_label: &str,
    topic_id: &str,
    concept_ids: &[String],
    diagnostic_purpose: &str,
    model_name: &str,
    has_normative_basis: bool,
) -> Result<(Value, bool), AppError> {
    let now_iso = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    let answers_trimmed: Vec<String> = cand.answers.iter().map(|a| a.trim().to_string()).collect();
    let correct_text = answers_trimmed[cand.correct_index].clone();
    let difficulty = match cand.estimated_difficulty.trim().to_uppercase().as_str() {
        "EASY" | "FACIL" | "FÁCIL" => "EASY",
        "HARD" | "DIFICIL" | "DIFÍCIL" => "HARD",
        _ => "MEDIUM",
    };
    let auto_approved = !cand
        .normative_certainty
        .trim()
        .eq_ignore_ascii_case("UNCERTAIN")
        && !matches!(
            cand.concept_adherence.trim().to_ascii_uppercase().as_str(),
            "UNCERTAIN" | "DUBIOUS" | "PARTIAL" | "DIVERGENT"
        );
    let validation_status = if auto_approved {
        "AUTO_APPROVED"
    } else {
        "PENDING_HUMAN_REVIEW"
    };
    let quality_status = if auto_approved {
        "OPERATIONAL"
    } else {
        "MONITORING"
    };
    let catalog_ver = question_contracts::catalog_ref()["catalog_version"]
        .as_i64()
        .unwrap_or(2);

    let tested_rule = if cand.tested_rule.trim().chars().count() >= 10 {
        cand.tested_rule
            .trim()
            .chars()
            .take(600)
            .collect::<String>()
    } else {
        cand.explanation
            .trim()
            .chars()
            .take(300)
            .collect::<String>()
    };
    let decisive_excerpt = if cand.decisive_stem_excerpt.trim().chars().count() >= 10 {
        cand.decisive_stem_excerpt
            .trim()
            .chars()
            .take(300)
            .collect::<String>()
    } else {
        cand.question.trim().chars().take(180).collect::<String>()
    };

    let mut doc_json = json!({
        "_id": { "$oid": oid.to_hex() },
        "schema_version": 2,
        "revision": 1,
        "status": "ACTIVE",
        "question": cand.question.trim(),
        "question_type": "MULTIPLE_CHOICE",
        "answers": answers_trimmed,
        "correct_answer_indices": [cand.correct_index],
        "correct_answers": [correct_text],
        "origins": [
            {
                "origin_id": uuid::Uuid::new_v4().to_string(),
                "kind": "AI_GENERATED",
                "verification": "PENDING",
                "source_exam_code": null,
                "institution": null,
                "organizer": "Cogniva CTV",
                "exam_year": null,
                "question_number": null,
                "source_reference": null,
                "provenance": {
                    "actor": { "type": "AI", "id": "xai" },
                    "method": "AI_SUGGESTION",
                    "rationale": "Questão complementar gerada sob demanda pelo Motor CTV após verificação de ausência de item inédito elegível no acervo.",
                    "recorded_at": now_iso,
                    "evidence": [],
                    "model": model_name,
                    "prompt_id": CTV_PROMPT_ID,
                    "prompt_version": CTV_PROMPT_VERSION,
                    "source_suggestion_id": null
                }
            }
        ],
        "classification": {
            "revision": 0,
            "catalog_version": catalog_ver,
            "state": "UNCLASSIFIED",
            "links": []
        },
        "validity": {
            "revision": 0,
            "status": "UNDER_REVIEW",
            "basis": if has_normative_basis { "LEGAL" } else { "TIMELESS" },
            "reference_date": null,
            "review_due_on": null,
            "reason_codes": ["REVIEW_REQUESTED"],
            "evaluated_content_revision": null,
            "evaluated_semantic_revision": null,
            "evaluation_mode": null,
            "decision": null,
            "scope": "CURRENT_KNOWLEDGE",
            "context_date": null
        },
        "grading": {
            "strategy": "EXACT_OPTION",
            "max_score": 100,
            "pass_score": 60,
            "expected_responses": [],
            "rubric": null,
            "engine": null,
            "confirmation_policy": "AUTOMATIC_EXACT",
            "policy_version": "objective-exact/1"
        },
        "editorial_card": {
            "legal_provisions": [],
            "tested_rule": tested_rule,
            "exception_clause": null,
            "decisive_stem_excerpt": decisive_excerpt,
            "official_source_excerpt": null,
            "plausible_conceptual_errors": []
        },
        "explanation": cand.explanation.trim(),
        "contextualHint": [],
        "disciplina": subject_label,
        "source": "Cogniva CTV",
        "level": difficulty,
    });

    question_contracts::populate_v2_1_hashes(&mut doc_json)?;
    let content_rev = doc_json["content_revision"]
        .as_str()
        .unwrap_or_default()
        .to_string();

    doc_json["diagnostic_meta"] = json!({
        "source_question_id": source_question_id,
        "subject_id": subject_id,
        "topic_id": topic_id,
        "concept_ids": concept_ids,
        "diagnostic_purpose": diagnostic_purpose,
        "generation_origin": "AI_ON_DEMAND",
        "model_identifier": model_name,
        "prompt_id": CTV_PROMPT_ID,
        "prompt_version": CTV_PROMPT_VERSION,
        "generated_at": now_iso,
        "validation_status": validation_status,
        "estimated_difficulty": difficulty,
        "content_hash": content_rev,
        "usage_count": 0,
        "quality_status": quality_status,
        "quality_metrics": {
            "attempts_count": 0,
            "correct_count": 0,
            "avg_active_seconds": 0.0,
            "discrimination_index": null,
            "open_reports_count": 0
        }
    });

    question_contracts::validate(&doc_json)?;
    Ok((doc_json, auto_approved))
}

#[derive(Debug, Clone)]
pub(crate) struct SelectedBankCandidate {
    dto: QuestionDto,
    was_seen: bool,
    mode: VerificationMode,
    framing_reason_code: &'static str,
    framing_reason_pt: String,
    content_revision: String,
    semantic_revision: String,
}

#[derive(Debug, Default)]
struct BankSearchOutcome {
    best_transfer: Option<SelectedBankCandidate>,
    best_topic_practice: Option<SelectedBankCandidate>,
}

#[derive(Debug)]
enum BankSearchError {
    Inconclusive,
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn evaluate_bank_page_docs(
    page_docs: Vec<Document>,
    source_oid: ObjectId,
    source_profile: &QuestionAlignmentProfile,
    exposure_snap: &UserExposureSnapshot,
    reported_by_user: &HashSet<ObjectId>,
    allow_seen_for_retention: bool,
    best_transfer_scored: &mut Option<(i32, SelectedBankCandidate)>,
    best_practice_scored: &mut Option<(i32, SelectedBankCandidate)>,
) {
    let catalog = question_contracts::catalog_ref();
    let eval_date = Utc::now().date_naive();
    let trusted_ctx = TrustedReviewContext::default();
    for doc_raw in page_docs {
        let Ok(oid) = doc_raw.get_object_id("_id") else {
            continue;
        };
        if oid == source_oid || reported_by_user.contains(&oid) {
            continue;
        }
        let elig = question_eligibility::evaluate_bson_question_for_practice(
            &doc_raw,
            &catalog,
            None,
            eval_date,
            &trusted_ctx,
            EligibilityMode::ComplementaryVerification,
        );
        if !elig.eligible {
            continue;
        }
        let Ok(q_doc) = question_compatibility::decode_for_ctv(doc_raw.clone()) else {
            continue;
        };
        let cand_profile = resolve_question_alignment_profile(&doc_raw, &q_doc);
        let hex = oid.to_hex();
        let was_seen = exposure_snap.has_seen(
            &hex,
            &cand_profile.content_revision,
            &cand_profile.semantic_revision,
        );
        if was_seen && !allow_seen_for_retention {
            continue;
        }

        let CandidateAlignmentDecision::Accepted {
            mode,
            framing_reason_code,
            framing_reason_pt,
            application_bonus,
        } = evaluate_candidate_alignment(source_profile, &cand_profile, allow_seen_for_retention)
        else {
            continue;
        };

        let mut score = if !was_seen { 100 } else { 20 };
        let is_human_standard = question_eligibility::evaluate_bson_question_for_practice(
            &doc_raw,
            &catalog,
            None,
            eval_date,
            &trusted_ctx,
            EligibilityMode::StandardObjective,
        )
        .eligible;
        if is_human_standard {
            score += 40;
        }
        score += application_bonus;

        let cand_entry = SelectedBankCandidate {
            dto: q_doc.to_dto(None),
            was_seen,
            mode,
            framing_reason_code,
            framing_reason_pt,
            content_revision: cand_profile.content_revision,
            semantic_revision: cand_profile.semantic_revision,
        };

        match mode {
            VerificationMode::ConceptualTransfer | VerificationMode::IntentionalReview => {
                if best_transfer_scored
                    .as_ref()
                    .is_none_or(|(best_s, _)| score > *best_s)
                {
                    *best_transfer_scored = Some((score, cand_entry));
                }
            }
            VerificationMode::TopicPractice => {
                if best_practice_scored
                    .as_ref()
                    .is_none_or(|(best_s, _)| score > *best_s)
                {
                    *best_practice_scored = Some((score, cand_entry));
                }
            }
        }
    }
}

pub(crate) fn revalidate_post_ai_duplicate_candidate(
    ex: &Document,
    source_oid: ObjectId,
    source_profile: &QuestionAlignmentProfile,
    exposure_snap: &UserExposureSnapshot,
    not_reported: bool,
    allow_seen: bool,
) -> Option<SelectedBankCandidate> {
    let cat = question_contracts::catalog_ref();
    let eval_date = Utc::now().date_naive();
    let trusted_ctx = TrustedReviewContext::default();
    let elig = question_eligibility::evaluate_bson_question_for_practice(
        ex,
        &cat,
        None,
        eval_date,
        &trusted_ctx,
        EligibilityMode::ComplementaryVerification,
    );
    if !elig.eligible || !not_reported {
        return None;
    }
    let q_doc = question_compatibility::decode_for_ctv(ex.clone()).ok()?;
    if q_doc.id == source_oid {
        return None;
    }
    let dup_profile = resolve_question_alignment_profile(ex, &q_doc);
    let was_seen = exposure_snap.has_seen(
        &q_doc.id.to_hex(),
        &dup_profile.content_revision,
        &dup_profile.semantic_revision,
    );
    if was_seen && !allow_seen {
        return None;
    }
    if let CandidateAlignmentDecision::Accepted {
        mode: VerificationMode::ConceptualTransfer,
        framing_reason_code,
        framing_reason_pt,
        ..
    } = evaluate_candidate_alignment(source_profile, &dup_profile, allow_seen)
    {
        Some(SelectedBankCandidate {
            dto: q_doc.to_dto(None),
            was_seen,
            mode: VerificationMode::ConceptualTransfer,
            framing_reason_code,
            framing_reason_pt,
            content_revision: dup_profile.content_revision,
            semantic_revision: dup_profile.semantic_revision,
        })
    } else {
        None
    }
}

#[allow(clippy::too_many_arguments)]
async fn find_eligible_bank_candidate_paginated(
    state: &AppState,
    user_id: i64,
    offer_id: &str,
    source_oid: ObjectId,
    source_profile: &QuestionAlignmentProfile,
    topic_id: &str,
    allow_seen_for_retention: bool,
    max_pages_override: Option<usize>,
    simulate_error_after_page: Option<usize>,
) -> Result<BankSearchOutcome, BankSearchError> {
    let max_pages = max_pages_override
        .unwrap_or(DEFAULT_MAX_SEARCH_PAGES)
        .clamp(1, DEFAULT_MAX_SEARCH_PAGES);

    let exposure_snap = load_user_exposure_snapshot(&state.db, user_id, Some(offer_id), None, None)
        .await
        .map_err(|_| BankSearchError::Inconclusive)?;

    let reported_by_user: HashSet<ObjectId> = state
        .db
        .collection::<Document>("reportedQuestoes")
        .find(doc! {
            "user_id": user_id,
            "fix.status_solved": { "$ne": true }
        })
        .projection(doc! { "questao_id": 1 })
        .await
        .map_err(|_| BankSearchError::Inconclusive)?
        .try_collect::<Vec<_>>()
        .await
        .map_err(|_| BankSearchError::Inconclusive)?
        .into_iter()
        .filter_map(|d| d.get_object_id("questao_id").ok())
        .collect();

    let mut cursor_oid: Option<ObjectId> = None;
    let mut best_transfer_scored: Option<(i32, SelectedBankCandidate)> = None;
    let mut best_practice_scored: Option<(i32, SelectedBankCandidate)> = None;
    let mut pages_scanned = 0usize;

    loop {
        if simulate_error_after_page.is_some_and(|fail_after| pages_scanned >= fail_after) {
            return Err(BankSearchError::Inconclusive);
        }
        if pages_scanned >= max_pages {
            if best_transfer_scored.is_some() {
                break;
            }
            return Err(BankSearchError::Inconclusive);
        }

        let mut id_clause = doc! { "$ne": source_oid };
        if let Some(last_id) = cursor_oid {
            id_clause.insert("$gt", last_id);
        }

        let page_docs: Vec<Document> = state
            .db
            .collection::<Document>("todasQuestoes")
            .find(doc! {
                "_id": id_clause,
                "status": "ACTIVE",
                "question_type": { "$in": ["MULTIPLE_CHOICE", "TRUE_FALSE"] },
                "$or": [
                    { "canonical_topic_id": topic_id },
                    { "classification.links.topic_id": topic_id },
                    { "diagnostic_meta.topic_id": topic_id }
                ]
            })
            .sort(doc! { "_id": 1 })
            .limit(CTV_BANK_PAGE_SIZE)
            .await
            .map_err(|_| BankSearchError::Inconclusive)?
            .try_collect()
            .await
            .map_err(|_| BankSearchError::Inconclusive)?;

        if page_docs.is_empty() {
            break;
        }
        pages_scanned += 1;
        let fetched_len = page_docs.len();
        cursor_oid = page_docs.last().and_then(|d| d.get_object_id("_id").ok());

        evaluate_bank_page_docs(
            page_docs,
            source_oid,
            source_profile,
            &exposure_snap,
            &reported_by_user,
            allow_seen_for_retention,
            &mut best_transfer_scored,
            &mut best_practice_scored,
        );

        if best_transfer_scored
            .as_ref()
            .is_some_and(|(s, _)| *s >= 150)
        {
            break;
        }
        if (fetched_len as i64) < CTV_BANK_PAGE_SIZE {
            break;
        }
    }

    Ok(BankSearchOutcome {
        best_transfer: best_transfer_scored.map(|(_, c)| c),
        best_topic_practice: best_practice_scored.map(|(_, c)| c),
    })
}

fn extract_json_object(raw: &str) -> Option<&str> {
    let start = raw.find('{')?;
    let end = raw.rfind('}')?;
    (end >= start).then_some(&raw[start..=end])
}

fn build_non_triggered_response(
    status: &str,
    trigger_reason: Option<&str>,
    diagnostic_purpose: Option<&str>,
    verification_timing: &str,
    source_profile: Option<&QuestionAlignmentProfile>,
) -> CtvEvaluateResponse {
    CtvEvaluateResponse {
        triggered: false,
        status: status.to_string(),
        offer_id: None,
        trigger_reason: trigger_reason.map(str::to_string),
        trigger_cohort: trigger_reason.map(|r| trigger_cohort_for_reason(Some(r)).to_string()),
        diagnostic_purpose: diagnostic_purpose.map(str::to_string),
        verification_mode: None,
        framing_reason_code: None,
        framing_reason_pt: None,
        target_concept_id: source_profile.and_then(|p| p.target_concept_id.clone()),
        target_tested_rule: source_profile.and_then(|p| p.tested_rule.clone()),
        delivery_source: None,
        verification_timing: verification_timing.to_string(),
        was_previously_exposed: false,
        complementary_question: None,
        student_framing_pt: None,
        policy_version: CTV_POLICY_VERSION.to_string(),
    }
}

#[allow(clippy::too_many_arguments)]
async fn persist_supplied_offer_and_build_response(
    state: &AppState,
    user_id: i64,
    offer_id: &str,
    parent_attempt_id: &str,
    source_profile: &QuestionAlignmentProfile,
    cand: SelectedBankCandidate,
    trigger_reason: Option<&str>,
    purpose: &str,
    delivery_source: &str,
    verification_timing: &str,
) -> Result<CtvEvaluateResponse, AppError> {
    let effective_purpose = if cand.mode == VerificationMode::TopicPractice {
        "TOPIC_PRACTICE"
    } else {
        purpose
    };
    let student_framing_pt = match cand.mode {
        VerificationMode::ConceptualTransfer => {
            "Vamos verificar rapidamente se você consegue aplicar esse mesmo conceito e regra em outra situação antes de abrir o comentário."
                .to_string()
        }
        VerificationMode::TopicPractice => {
            "Prática complementar do tópico (regra ou conceito distinto da questão original)."
                .to_string()
        }
        VerificationMode::IntentionalReview => {
            "Revisão intencional de retenção com questão já trabalhada neste assunto."
                .to_string()
        }
    };
    let cohort = trigger_cohort_for_reason(trigger_reason);
    let resp = CtvEvaluateResponse {
        triggered: true,
        status: "triggered".to_string(),
        offer_id: Some(offer_id.to_string()),
        trigger_reason: trigger_reason.map(str::to_string),
        trigger_cohort: Some(cohort.to_string()),
        diagnostic_purpose: Some(effective_purpose.to_string()),
        verification_mode: Some(cand.mode.code().to_string()),
        framing_reason_code: Some(cand.framing_reason_code.to_string()),
        framing_reason_pt: Some(cand.framing_reason_pt.clone()),
        target_concept_id: source_profile.target_concept_id.clone(),
        target_tested_rule: source_profile.tested_rule.clone(),
        delivery_source: Some(delivery_source.to_string()),
        verification_timing: verification_timing.to_string(),
        was_previously_exposed: cand.was_seen,
        complementary_question: Some(cand.dto.clone()),
        student_framing_pt: Some(student_framing_pt),
        policy_version: CTV_POLICY_VERSION.to_string(),
    };

    // Importante (A04): `evaluate_and_supply` grava `state = "SUPPLIED"` SEM `first_displayed_at`.
    // Apenas a exibição real na tela (`DISPLAYED`) ou resposta/dispensa marca exposição efetiva!
    let doc_key = format!("{user_id}:{offer_id}");
    let resp_bson = bson::to_document(&resp).unwrap_or_default();
    let _ = state
        .db
        .collection::<Document>("ctv_offers")
        .update_one(
            doc! { "_id": &doc_key },
            doc! {
                "$setOnInsert": {
                    "_id": &doc_key,
                    "user_id": user_id,
                    "offer_id": offer_id,
                    "parent_attempt_id": parent_attempt_id,
                    "source_question_id": &source_profile.question_id,
                    "complementary_question_id": &cand.dto.id,
                    "content_revision": &cand.content_revision,
                    "semantic_revision": &cand.semantic_revision,
                    "target_concept_id": &source_profile.target_concept_id,
                    "target_tested_rule": &source_profile.tested_rule,
                    "verification_mode": cand.mode.code(),
                    "framing_reason_code": cand.framing_reason_code,
                    "framing_reason_pt": &cand.framing_reason_pt,
                    "trigger_reason": trigger_reason,
                    "trigger_cohort": cohort,
                    "diagnostic_purpose": effective_purpose,
                    "delivery_source": delivery_source,
                    "verification_timing": verification_timing,
                    "state": "SUPPLIED",
                    "supplied_at": bson::DateTime::now(),
                    "cached_response": resp_bson,
                }
            },
        )
        .upsert(true)
        .await;

    Ok(resp)
}

pub async fn evaluate_and_supply(
    state: &AppState,
    user: &SessionUser,
    mut req: CtvEvaluateRequest,
) -> Result<CtvEvaluateResponse, AppError> {
    uuid::Uuid::parse_str(&req.parent_attempt_id)
        .map_err(|_| AppError::BadRequest("Identificador da tentativa original inválido".into()))?;
    if req.session_uuid.len() > 100 {
        return Err(AppError::BadRequest("Sessão CTV fora dos limites".into()));
    }
    let source_oid = ObjectId::parse_str(&req.source_question_id)?;
    let offer_id = deterministic_offer_id(user.user_id, &req.parent_attempt_id);
    let offer_doc_key = format!("{}:{offer_id}", user.user_id);

    // Idempotência de retentativas para a mesma tentativa original (A11): preserva a mesma oferta e contagens!
    if let Some(existing_offer) = state
        .db
        .collection::<Document>("ctv_offers")
        .find_one(doc! { "_id": &offer_doc_key })
        .await?
    {
        if let Ok(cached_doc) = existing_offer.get_document("cached_response") {
            if let Ok(cached_resp) = bson::from_document::<CtvEvaluateResponse>(cached_doc.clone())
            {
                return Ok(cached_resp);
            }
        }
    }

    rate_limit::check_auth_rate_limit(&state.db, "ctv_eval", &user.user_id.to_string(), 30, 60)
        .await?;

    let session_uuid_trimmed = req.session_uuid.trim().to_string();
    if !session_uuid_trimmed.is_empty() {
        let server_session_count = state
            .db
            .collection::<Document>("ctv_offers")
            .count_documents(doc! {
                "user_id": user.user_id,
                "session_uuid": &session_uuid_trimmed
            })
            .await
            .unwrap_or(0);
        let clamped_server = server_session_count.min(u16::MAX as u64) as u16;
        req.session_ctv_count = req.session_ctv_count.max(clamped_server);
    }

    let resp = evaluate_and_supply_inner(state, user, req, source_oid, &offer_id).await?;
    if resp.triggered && !session_uuid_trimmed.is_empty() {
        let _ = state
            .db
            .collection::<Document>("ctv_offers")
            .update_one(
                doc! { "_id": &offer_doc_key },
                doc! { "$set": { "session_uuid": &session_uuid_trimmed } },
            )
            .await;
    }
    Ok(resp)
}

async fn evaluate_and_supply_inner(
    state: &AppState,
    user: &SessionUser,
    req: CtvEvaluateRequest,
    source_oid: ObjectId,
    offer_id: &str,
) -> Result<CtvEvaluateResponse, AppError> {
    let offer_id = offer_id.to_string();
    let policy = CtvPolicyConfig::from_env();

    let source_raw = state
        .db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! { "_id": source_oid })
        .await?
        .ok_or_else(|| AppError::NotFound("Questão original não encontrada".into()))?;
    let source_q = question_compatibility::decode(source_raw.clone())?;
    let source_profile = resolve_question_alignment_profile(&source_raw, &source_q);

    let (subject_id, topic_id) = match (
        source_profile.subject_id.clone(),
        source_profile.topic_id.clone(),
    ) {
        (Some(s), Some(t)) if question_contracts::known_topic(&s, &t) => (s, t),
        _ => {
            return Ok(build_non_triggered_response(
                "not_triggered",
                None,
                None,
                "PRE_FEEDBACK",
                Some(&source_profile),
            ));
        }
    };

    let mut target_concepts_for_gen = Vec::new();
    if let Some(tc) = &source_profile.target_concept_id {
        target_concepts_for_gen.push(tc.clone());
    }
    let primary_concept = source_profile.target_concept_id.as_deref();

    // Consulta evidências históricas resumidas do estudante no tópico para informar os gatilhos CTV
    let topic_acts: Vec<Document> = state
        .db
        .collection::<Document>("userActivityLog")
        .find(doc! { "user_id": user.user_id, "canonical_topic_id": &topic_id })
        .projection(doc! {
            "quantidade_praticas": 1,
            "percentual_acertos": 1,
            "high_confidence_error_flag": 1
        })
        .limit(30)
        .await?
        .try_collect()
        .await?;

    let distinct_practiced = topic_acts.len();
    let hce_count = topic_acts
        .iter()
        .filter(|d| d.get_bool("high_confidence_error_flag").unwrap_or(false))
        .count();
    let acc_values: Vec<f64> = topic_acts
        .iter()
        .filter_map(|d| d.get_f64("percentual_acertos").ok())
        .collect();
    let mean_acc = if acc_values.is_empty() {
        0.0
    } else {
        acc_values.iter().sum::<f64>() / (acc_values.len() as f64)
    };
    let consistent_history = distinct_practiced >= 3 && mean_acc >= 75.0 && hce_count == 0;
    let intra_concept_inconsistent =
        acc_values.iter().any(|&a| a >= 80.0) && acc_values.iter().any(|&a| a <= 35.0);

    let mut prior_ctv_filter = doc! {
        "user_id": user.user_id,
        "counts_as_independent_transfer": true,
        "is_correct": true
    };
    if let Some(tc) = primary_concept {
        prior_ctv_filter.insert("target_concept_id", tc);
    } else {
        prior_ctv_filter.insert("canonical_topic_id", &topic_id);
    }
    let prior_ctv_ok = state
        .db
        .collection::<Document>("conceptual_transfer_attempts")
        .count_documents(prior_ctv_filter)
        .await?
        > 0;

    let (should_trigger, status_code, trigger_reason, purpose_opt) = decide_ctv_trigger(
        &policy,
        &req,
        consistent_history,
        hce_count >= 2,
        intra_concept_inconsistent,
        distinct_practiced < 3,
        prior_ctv_ok,
    );

    let verification_timing = if req.quiz_mode.trim().eq_ignore_ascii_case("SIMULADO") {
        "POST_SIMULADO"
    } else if req.force_post_instruction {
        "POST_INSTRUCTION"
    } else {
        "PRE_FEEDBACK"
    };

    if !should_trigger {
        return Ok(build_non_triggered_response(
            status_code,
            trigger_reason,
            purpose_opt,
            verification_timing,
            Some(&source_profile),
        ));
    }

    let purpose = purpose_opt.unwrap_or("CONCEPTUAL_TRANSFER");
    let allow_seen = purpose == "RETENTION_CHECK";

    // ETAPA 1: DATABASE FIRST — busca paginada com cursor estável sobre cogniva.todasQuestoes
    let initial_search = match find_eligible_bank_candidate_paginated(
        state,
        user.user_id,
        &offer_id,
        source_oid,
        &source_profile,
        &topic_id,
        allow_seen,
        req.max_search_pages,
        req.simulate_search_error_after_page,
    )
    .await
    {
        Ok(res) => res,
        Err(BankSearchError::Inconclusive) => {
            log_ctv_audit_idempotent(
                state,
                CtvAuditEvent {
                    request_id: &offer_id,
                    outcome: "SEARCH_INCONCLUSIVE",
                    subject_id: &subject_id,
                    topic_id: &topic_id,
                    concept_id: primary_concept,
                    diagnostic_purpose: purpose,
                    question_id: None,
                    model_name: None,
                    latency_ms: Some(0),
                    provider_calls: 0,
                    persisted_item: false,
                    duplicate_barred: false,
                    delivered_to_student: false,
                    delivery_channel: "NONE",
                    estimated_cost_usd: 0.0,
                },
            )
            .await;
            return Ok(build_non_triggered_response(
                "search_inconclusive",
                trigger_reason,
                Some(purpose),
                verification_timing,
                Some(&source_profile),
            ));
        }
    };

    if let Some(transfer_cand) = initial_search.best_transfer {
        log_ctv_audit_idempotent(
            state,
            CtvAuditEvent {
                request_id: &offer_id,
                outcome: "REUSED_FROM_DB",
                subject_id: &subject_id,
                topic_id: &topic_id,
                concept_id: primary_concept,
                diagnostic_purpose: purpose,
                question_id: Some(&transfer_cand.dto.id),
                model_name: None,
                latency_ms: Some(0),
                provider_calls: 0,
                persisted_item: false,
                duplicate_barred: false,
                delivered_to_student: true,
                delivery_channel: "ONLINE_DB_REUSE",
                estimated_cost_usd: 0.0,
            },
        )
        .await;
        return persist_supplied_offer_and_build_response(
            state,
            user.user_id,
            &offer_id,
            &req.parent_attempt_id,
            &source_profile,
            transfer_cand,
            trigger_reason,
            purpose,
            "REUSED_FROM_DB",
            verification_timing,
        )
        .await;
    }

    let fallback_topic_practice = initial_search.best_topic_practice;

    // Se o alvo da questão original for ambíguo, não aciona IA para inventar conceito:
    // oferece prática do tópico quando disponível ou degrada graciosamente.
    if source_profile.target_concept_ambiguous {
        if let Some(tp_cand) = fallback_topic_practice {
            log_ctv_audit_idempotent(
                state,
                CtvAuditEvent {
                    request_id: &offer_id,
                    outcome: "REUSED_FROM_DB",
                    subject_id: &subject_id,
                    topic_id: &topic_id,
                    concept_id: None,
                    diagnostic_purpose: "TOPIC_PRACTICE",
                    question_id: Some(&tp_cand.dto.id),
                    model_name: None,
                    latency_ms: Some(0),
                    provider_calls: 0,
                    persisted_item: false,
                    duplicate_barred: false,
                    delivered_to_student: true,
                    delivery_channel: "ONLINE_DB_REUSE",
                    estimated_cost_usd: 0.0,
                },
            )
            .await;
            return persist_supplied_offer_and_build_response(
                state,
                user.user_id,
                &offer_id,
                &req.parent_attempt_id,
                &source_profile,
                tp_cand,
                trigger_reason,
                "TOPIC_PRACTICE",
                "REUSED_FROM_DB",
                verification_timing,
            )
            .await;
        }
        return Ok(build_non_triggered_response(
            "unavailable_graceful",
            trigger_reason,
            Some(purpose),
            verification_timing,
            Some(&source_profile),
        ));
    }

    // ETAPA 2: AI ON DEMAND — protegida por trava assíncrona por (unidade, propósito)
    let unit_lock_key = format!(
        "{}:{}:{}",
        subject_id,
        primary_concept.unwrap_or(topic_id.as_str()),
        purpose
    );
    let unit_mutex = generation_lock_for(&unit_lock_key).await;
    let Ok(_unit_guard) =
        tokio::time::timeout(std::time::Duration::from_secs(5), unit_mutex.lock()).await
    else {
        if let Some(tp_cand) = fallback_topic_practice {
            return persist_supplied_offer_and_build_response(
                state,
                user.user_id,
                &offer_id,
                &req.parent_attempt_id,
                &source_profile,
                tp_cand,
                trigger_reason,
                "TOPIC_PRACTICE",
                "REUSED_FROM_DB",
                verification_timing,
            )
            .await;
        }
        return Ok(build_non_triggered_response(
            "unavailable_graceful",
            trigger_reason,
            Some(purpose),
            verification_timing,
            Some(&source_profile),
        ));
    };

    // Reconsulta paginada após adquirir a trava: aproveita questão recém-persistida por pedido concorrente (A11)!
    if let Ok(locked_search) = find_eligible_bank_candidate_paginated(
        state,
        user.user_id,
        &offer_id,
        source_oid,
        &source_profile,
        &topic_id,
        allow_seen,
        req.max_search_pages,
        None,
    )
    .await
    {
        if let Some(reused_cand) = locked_search.best_transfer {
            log_ctv_audit_idempotent(
                state,
                CtvAuditEvent {
                    request_id: &offer_id,
                    outcome: "REUSED_CONCURRENT_GENERATION",
                    subject_id: &subject_id,
                    topic_id: &topic_id,
                    concept_id: primary_concept,
                    diagnostic_purpose: purpose,
                    question_id: Some(&reused_cand.dto.id),
                    model_name: None,
                    latency_ms: Some(0),
                    provider_calls: 0,
                    persisted_item: false,
                    duplicate_barred: false,
                    delivered_to_student: true,
                    delivery_channel: "ONLINE_DB_REUSE",
                    estimated_cost_usd: 0.0,
                },
            )
            .await;
            return persist_supplied_offer_and_build_response(
                state,
                user.user_id,
                &offer_id,
                &req.parent_attempt_id,
                &source_profile,
                reused_cand,
                trigger_reason,
                purpose,
                "REUSED_FROM_DB",
                verification_timing,
            )
            .await;
        }
    }

    // Degradação graciosa (ou prática do tópico como convexidade em A01) quando IA não está ativa
    let model_opt = state
        .config
        .require_xai_model()
        .ok()
        .filter(|_| state.config.xai_api_key.is_some());
    let Some(model_name) = model_opt else {
        if let Some(tp_cand) = fallback_topic_practice {
            log_ctv_audit_idempotent(
                state,
                CtvAuditEvent {
                    request_id: &offer_id,
                    outcome: "REUSED_FROM_DB",
                    subject_id: &subject_id,
                    topic_id: &topic_id,
                    concept_id: primary_concept,
                    diagnostic_purpose: "TOPIC_PRACTICE",
                    question_id: Some(&tp_cand.dto.id),
                    model_name: None,
                    latency_ms: Some(0),
                    provider_calls: 0,
                    persisted_item: false,
                    duplicate_barred: false,
                    delivered_to_student: true,
                    delivery_channel: "ONLINE_DB_REUSE",
                    estimated_cost_usd: 0.0,
                },
            )
            .await;
            return persist_supplied_offer_and_build_response(
                state,
                user.user_id,
                &offer_id,
                &req.parent_attempt_id,
                &source_profile,
                tp_cand,
                trigger_reason,
                "TOPIC_PRACTICE",
                "REUSED_FROM_DB",
                verification_timing,
            )
            .await;
        }
        log_ctv_audit_idempotent(
            state,
            CtvAuditEvent {
                request_id: &offer_id,
                outcome: "SKIPPED_AI_UNAVAILABLE",
                subject_id: &subject_id,
                topic_id: &topic_id,
                concept_id: primary_concept,
                diagnostic_purpose: purpose,
                question_id: None,
                model_name: state.config.xai_model.as_deref(),
                latency_ms: None,
                provider_calls: 0,
                persisted_item: false,
                duplicate_barred: false,
                delivered_to_student: false,
                delivery_channel: "NONE",
                estimated_cost_usd: 0.0,
            },
        )
        .await;
        return Ok(build_non_triggered_response(
            "unavailable_graceful",
            trigger_reason,
            Some(purpose),
            verification_timing,
            Some(&source_profile),
        ));
    };

    let user_limit =
        rate_limit::effective_user_daily_limit(state.config.xai_daily_user_limit, user.is_guest);
    let reservation = match rate_limit::reserve_xai_quota(
        &state.db,
        Some(user.user_id),
        user_limit,
        state.config.xai_daily_global_limit,
    )
    .await
    {
        Ok(res) => res,
        Err(_) => {
            if let Some(tp_cand) = fallback_topic_practice {
                return persist_supplied_offer_and_build_response(
                    state,
                    user.user_id,
                    &offer_id,
                    &req.parent_attempt_id,
                    &source_profile,
                    tp_cand,
                    trigger_reason,
                    "TOPIC_PRACTICE",
                    "REUSED_FROM_DB",
                    verification_timing,
                )
                .await;
            }
            return Ok(build_non_triggered_response(
                "unavailable_graceful",
                trigger_reason,
                Some(purpose),
                verification_timing,
                Some(&source_profile),
            ));
        }
    };
    let quota_guard = rate_limit::QuotaGuard::new(state.db.clone(), reservation);

    let cat = question_contracts::catalog_ref();
    let subject_label = cat["subjects"]
        .as_array()
        .and_then(|arr| {
            arr.iter()
                .find(|s| s["subject_id"].as_str() == Some(subject_id.as_str()))
                .and_then(|s| s["label"].as_str())
        })
        .unwrap_or(source_q.disciplina.as_str())
        .to_string();
    let topic_label = cat["topics"]
        .as_array()
        .and_then(|arr| {
            arr.iter()
                .find(|t| t["topic_id"].as_str() == Some(topic_id.as_str()))
                .and_then(|t| t["label"].as_str())
        })
        .unwrap_or(topic_id.as_str())
        .to_string();
    let verified_sources = ai_tutor::verified_sources_for_question(&source_q);
    let has_normative = !verified_sources.is_empty();

    // Encaminha ao gerador o conceito-alvo, a regra decisiva e as referências verificadas (Seção 5.2)
    let untrusted_source = ai_tutor::wrap_untrusted_xml(
        "untrusted_raw_source",
        &json!({
            "disciplina": subject_label,
            "assunto": topic_label,
            "conceito_alvo": primary_concept,
            "regra_alvo_decisiva": source_profile.tested_rule,
            "proposito_diagnostico": purpose,
            "enunciado_origem_nao_parafrasear": source_q.question,
            "fontes_verificadas": verified_sources,
        })
        .to_string(),
    );

    let system_prompt = "Você é um elaborador sênior de bancas de concursos públicos brasileiros, especialista em psicometria e transferência conceitual. Elabore UMA questão inédita de múltipla escolha (exatamente 5 alternativas distintas) que exija aplicar a MESMA regra decisiva e conceito-alvo em um contexto/situação concreta completamente diferente, sem parafrasear o enunciado de origem, sem mencionar questões anteriores e sem pistas involuntárias. Responda EXCLUSIVAMENTE com um objeto JSON válido.";
    let user_prompt = format!(
        "{untrusted_source}\n\nRetorne APENAS um objeto JSON com as chaves exatas:\n{{\n  \"question\": \"enunciado inédito contextualizado exigindo aplicar a regra_alvo_decisiva\",\n  \"answers\": [\"opção 1\", \"opção 2\", \"opção 3\", \"opção 4\", \"opção 5\"],\n  \"correct_index\": 0,\n  \"explanation\": \"fundamentação técnica clara explicando por que o gabarito procede e por que os distratores falham, sem letras A-E\",\n  \"tested_rule\": \"regra nuclear cobrada\",\n  \"decisive_stem_excerpt\": \"trecho do próprio novo enunciado que delimita o caso\",\n  \"estimated_difficulty\": \"MEDIUM\",\n  \"normative_certainty\": \"HIGH\",\n  \"concept_adherence\": \"CONFIRMED\"\n}}\nSe depender de detalhe legal específico ausente de `fontes_verificadas` ou houver dúvida normativa/conceitual, defina `\"normative_certainty\": \"UNCERTAIN\"` ou `\"concept_adherence\": \"UNCERTAIN\"`."
    );

    let started = Instant::now();
    let raw_ai_text =
        match ai_tutor::call_xai_text(&state.config, system_prompt, &user_prompt).await {
            Ok(txt) => txt,
            Err(_) => {
                quota_guard.release_now().await;
                log_ctv_audit_idempotent(
                    state,
                    CtvAuditEvent {
                        request_id: &offer_id,
                        outcome: "PROVIDER_FAILURE_GRACEFUL",
                        subject_id: &subject_id,
                        topic_id: &topic_id,
                        concept_id: primary_concept,
                        diagnostic_purpose: purpose,
                        question_id: None,
                        model_name: Some(model_name),
                        latency_ms: Some(started.elapsed().as_millis() as u64),
                        provider_calls: 0,
                        persisted_item: false,
                        duplicate_barred: false,
                        delivered_to_student: false,
                        delivery_channel: "NONE",
                        estimated_cost_usd: 0.0,
                    },
                )
                .await;
                return Ok(build_non_triggered_response(
                    "unavailable_graceful",
                    trigger_reason,
                    Some(purpose),
                    verification_timing,
                    Some(&source_profile),
                ));
            }
        };
    let latency_ms = started.elapsed().as_millis() as u64;

    let parsed_cand: GeneratedCtvPayload = match extract_json_object(&raw_ai_text)
        .and_then(|slice| serde_json::from_str(slice).ok())
    {
        Some(c) => c,
        None => {
            quota_guard.commit();
            log_ctv_audit_idempotent(
                state,
                CtvAuditEvent {
                    request_id: &offer_id,
                    outcome: "VALIDATION_REJECTED",
                    subject_id: &subject_id,
                    topic_id: &topic_id,
                    concept_id: primary_concept,
                    diagnostic_purpose: purpose,
                    question_id: None,
                    model_name: Some(model_name),
                    latency_ms: Some(latency_ms),
                    provider_calls: 1,
                    persisted_item: false,
                    duplicate_barred: false,
                    delivered_to_student: false,
                    delivery_channel: "NONE",
                    estimated_cost_usd: DEFAULT_REFERENCE_COST_PER_GENERATION_USD,
                },
            )
            .await;
            return Ok(build_non_triggered_response(
                "unavailable_graceful",
                trigger_reason,
                Some(purpose),
                verification_timing,
                Some(&source_profile),
            ));
        }
    };

    if validate_generated_candidate(&parsed_cand, &source_q.question).is_err() {
        quota_guard.commit();
        log_ctv_audit_idempotent(
            state,
            CtvAuditEvent {
                request_id: &offer_id,
                outcome: "VALIDATION_REJECTED",
                subject_id: &subject_id,
                topic_id: &topic_id,
                concept_id: primary_concept,
                diagnostic_purpose: purpose,
                question_id: None,
                model_name: Some(model_name),
                latency_ms: Some(latency_ms),
                provider_calls: 1,
                persisted_item: false,
                duplicate_barred: false,
                delivered_to_student: false,
                delivery_channel: "NONE",
                estimated_cost_usd: DEFAULT_REFERENCE_COST_PER_GENERATION_USD,
            },
        )
        .await;
        return Ok(build_non_triggered_response(
            "unavailable_graceful",
            trigger_reason,
            Some(purpose),
            verification_timing,
            Some(&source_profile),
        ));
    }

    let new_oid = ObjectId::new();
    let (v2_json, auto_approved) = match build_v2_ctv_question_contract(
        new_oid,
        &parsed_cand,
        &req.source_question_id,
        &subject_id,
        &subject_label,
        &topic_id,
        &target_concepts_for_gen,
        purpose,
        model_name,
        has_normative,
    ) {
        Ok(pair) => pair,
        Err(_) => {
            quota_guard.commit();
            log_ctv_audit_idempotent(
                state,
                CtvAuditEvent {
                    request_id: &offer_id,
                    outcome: "VALIDATION_REJECTED",
                    subject_id: &subject_id,
                    topic_id: &topic_id,
                    concept_id: primary_concept,
                    diagnostic_purpose: purpose,
                    question_id: None,
                    model_name: Some(model_name),
                    latency_ms: Some(latency_ms),
                    provider_calls: 1,
                    persisted_item: false,
                    duplicate_barred: false,
                    delivered_to_student: false,
                    delivery_channel: "NONE",
                    estimated_cost_usd: DEFAULT_REFERENCE_COST_PER_GENERATION_USD,
                },
            )
            .await;
            return Ok(build_non_triggered_response(
                "unavailable_graceful",
                trigger_reason,
                Some(purpose),
                verification_timing,
                Some(&source_profile),
            ));
        }
    };

    let c_rev = v2_json["content_revision"].as_str().unwrap_or_default();
    let s_rev = v2_json["semantic_revision"].as_str().unwrap_or_default();

    // ETAPA 4 (Seção 5.4 / A12): Deduplicação exata indexada + quase-duplicata nas 200 mais recentes do tópico!
    let mut hash_or = vec![doc! { "content_revision": c_rev }];
    if !s_rev.is_empty() {
        hash_or.push(doc! { "semantic_revision": s_rev });
    }
    let exact_matches: Vec<Document> = state
        .db
        .collection::<Document>("todasQuestoes")
        .find(doc! { "$or": hash_or })
        .limit(10)
        .await?
        .try_collect()
        .await?;
    let recent_topic_docs: Vec<Document> = state
        .db
        .collection::<Document>("todasQuestoes")
        .find(doc! {
            "$or": [
                { "canonical_topic_id": &topic_id },
                { "classification.links.topic_id": &topic_id },
                { "diagnostic_meta.topic_id": &topic_id }
            ]
        })
        .sort(doc! { "_id": -1 })
        .limit(200)
        .await?
        .try_collect()
        .await?;
    let mut seen_dedup_oids = HashSet::new();
    let mut existing_same_topic = Vec::with_capacity(exact_matches.len() + recent_topic_docs.len());
    for doc_item in exact_matches.into_iter().chain(recent_topic_docs) {
        if let Ok(oid) = doc_item.get_object_id("_id") {
            if seen_dedup_oids.insert(oid) {
                existing_same_topic.push(doc_item);
            }
        }
    }

    for ex in &existing_same_topic {
        let exact_dup = ex.get_str("content_revision").ok() == Some(c_rev)
            || (!s_rev.is_empty() && ex.get_str("semantic_revision").ok() == Some(s_rev));
        let near_dup = ex.get_str("question").ok().is_some_and(|qtxt| {
            jaccard_trigram_similarity(qtxt, &parsed_cand.question) >= MAX_BANK_SIMILARITY_FOR_DEDUP
        });
        if exact_dup || near_dup {
            quota_guard.commit();
            let dup_oid_opt = ex.get_object_id("_id").ok();
            let dup_id_hex = dup_oid_opt.map(|id| id.to_hex());

            // Reaplica validade, qualidade, reportes do usuário, alinhamento conceitual e exposição (A12)!
            let not_reported = if let Some(d_oid) = dup_oid_opt {
                state
                    .db
                    .collection::<Document>("reportedQuestoes")
                    .count_documents(doc! {
                        "user_id": user.user_id,
                        "questao_id": d_oid,
                        "fix.status_solved": { "$ne": true }
                    })
                    .await
                    .unwrap_or(0)
                    == 0
            } else {
                false
            };
            let exposure_snap =
                load_user_exposure_snapshot(&state.db, user.user_id, Some(&offer_id), None, None)
                    .await?;

            let reusable_cand = revalidate_post_ai_duplicate_candidate(
                ex,
                source_oid,
                &source_profile,
                &exposure_snap,
                not_reported,
                allow_seen,
            );

            // Registra 1 chamada real, 1 duplicata barrada, 0 chamadas evitadas e o desfecho real da entrega!
            log_ctv_audit_idempotent(
                state,
                CtvAuditEvent {
                    request_id: &offer_id,
                    outcome: "DUPLICATE_PREVENTED",
                    subject_id: &subject_id,
                    topic_id: &topic_id,
                    concept_id: primary_concept,
                    diagnostic_purpose: purpose,
                    question_id: dup_id_hex.as_deref(),
                    model_name: Some(model_name),
                    latency_ms: Some(latency_ms),
                    provider_calls: 1,
                    persisted_item: false,
                    duplicate_barred: true,
                    delivered_to_student: reusable_cand.is_some(),
                    delivery_channel: if reusable_cand.is_some() {
                        "ONLINE_DEDUP_REUSE"
                    } else {
                        "NONE"
                    },
                    estimated_cost_usd: DEFAULT_REFERENCE_COST_PER_GENERATION_USD,
                },
            )
            .await;

            if let Some(cand) = reusable_cand {
                return persist_supplied_offer_and_build_response(
                    state,
                    user.user_id,
                    &offer_id,
                    &req.parent_attempt_id,
                    &source_profile,
                    cand,
                    trigger_reason,
                    purpose,
                    "REUSED_FROM_DB",
                    verification_timing,
                )
                .await;
            }

            return Ok(build_non_triggered_response(
                "unavailable_graceful",
                trigger_reason,
                Some(purpose),
                verification_timing,
                Some(&source_profile),
            ));
        }
    }

    // ETAPA 3 (Seção 5.3 / A11): Persistência permanente em cogniva.todasQuestoes ANTES da entrega ao aluno!
    let bson_question = question_contracts::contract_to_bson_question(&v2_json)?;
    question_storage::collection(&state.db, "todasQuestoes")
        .insert_one(bson_question.clone())
        .await?;
    quota_guard.commit();

    let saved_id_hex = new_oid.to_hex();
    let _ = question_storage::collection(&state.db, "question_revision_events")
        .insert_one(doc! {
            "_id": format!("ctv-gen:{saved_id_hex}:1"),
            "question_id": &saved_id_hex,
            "revision": 1_i64,
            "content_revision": c_rev,
            "semantic_revision": s_rev,
            "action": "CTV_AI_GENERATED_PERMANENT",
            "recorded_at": Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
        })
        .await;

    if !auto_approved {
        log_ctv_audit_idempotent(
            state,
            CtvAuditEvent {
                request_id: &offer_id,
                outcome: "QUEUED_HUMAN_REVIEW",
                subject_id: &subject_id,
                topic_id: &topic_id,
                concept_id: primary_concept,
                diagnostic_purpose: purpose,
                question_id: Some(&saved_id_hex),
                model_name: Some(model_name),
                latency_ms: Some(latency_ms),
                provider_calls: 1,
                persisted_item: true,
                duplicate_barred: false,
                delivered_to_student: false,
                delivery_channel: "NONE",
                estimated_cost_usd: DEFAULT_REFERENCE_COST_PER_GENERATION_USD,
            },
        )
        .await;
        return Ok(build_non_triggered_response(
            "queued_for_human_review",
            trigger_reason,
            Some(purpose),
            verification_timing,
            Some(&source_profile),
        ));
    }

    let decoded = question_compatibility::decode_for_ctv(bson_question)?;
    let generated_cand = SelectedBankCandidate {
        dto: decoded.to_dto(None),
        was_seen: false,
        mode: VerificationMode::ConceptualTransfer,
        framing_reason_code: "AI_GENERATED_SAME_CONCEPT_AND_RULE",
        framing_reason_pt:
            "Questão complementar inédita gerada sob demanda e persistida no acervo permanente para verificar o mesmo conceito e regra."
                .to_string(),
        content_revision: c_rev.to_string(),
        semantic_revision: s_rev.to_string(),
    };

    log_ctv_audit_idempotent(
        state,
        CtvAuditEvent {
            request_id: &offer_id,
            outcome: "GENERATED_AND_APPROVED",
            subject_id: &subject_id,
            topic_id: &topic_id,
            concept_id: primary_concept,
            diagnostic_purpose: purpose,
            question_id: Some(&saved_id_hex),
            model_name: Some(model_name),
            latency_ms: Some(latency_ms),
            provider_calls: 1,
            persisted_item: true,
            duplicate_barred: false,
            delivered_to_student: true,
            delivery_channel: "ONLINE_AI_GENERATED",
            estimated_cost_usd: DEFAULT_REFERENCE_COST_PER_GENERATION_USD,
        },
    )
    .await;

    persist_supplied_offer_and_build_response(
        state,
        user.user_id,
        &offer_id,
        &req.parent_attempt_id,
        &source_profile,
        generated_cand,
        trigger_reason,
        purpose,
        "AI_GENERATED_PERMANENT",
        verification_timing,
    )
    .await
}

pub async fn submit_ctv_attempt(
    state: &AppState,
    user_id: i64,
    req: CtvSubmitRequest,
) -> Result<CtvSubmitResponse, AppError> {
    uuid::Uuid::parse_str(&req.ctv_attempt_id)
        .map_err(|_| AppError::BadRequest("Identificador da tentativa CTV inválido".into()))?;
    uuid::Uuid::parse_str(&req.parent_attempt_id)
        .map_err(|_| AppError::BadRequest("Identificador da tentativa original inválido".into()))?;
    let source_oid = ObjectId::parse_str(&req.source_question_id)?;
    let comp_oid = ObjectId::parse_str(&req.complementary_question_id)?;
    if source_oid == comp_oid {
        return Err(AppError::BadRequest(
            "A questão complementar deve ser distinta da questão original".into(),
        ));
    }
    if !(0..=86_400).contains(&req.active_seconds) {
        return Err(AppError::BadRequest("Tempo ativo fora dos limites".into()));
    }
    let timing = match req.verification_timing.trim().to_uppercase().as_str() {
        "POST_INSTRUCTION" => "POST_INSTRUCTION",
        "POST_SIMULADO" => "POST_SIMULADO",
        "UNKNOWN_CONDITION" => "UNKNOWN_CONDITION",
        _ => "PRE_FEEDBACK",
    };

    let ctv_coll = state
        .db
        .collection::<Document>("conceptual_transfer_attempts");
    let receipt_key = format!("{user_id}:{}", req.ctv_attempt_id);
    if let Some(existing) = ctv_coll.find_one(doc! { "_id": &receipt_key }).await? {
        if let Ok(resp_doc) = existing.get_document("response_ack") {
            if let Ok(ack) = bson::from_document::<CtvSubmitResponse>(resp_doc.clone()) {
                return Ok(ack);
            }
        }
    }

    let source_raw_opt = state
        .db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! { "_id": source_oid })
        .await?;
    let source_profile_opt = source_raw_opt.as_ref().and_then(|raw| {
        question_compatibility::decode(raw.clone())
            .ok()
            .map(|q| resolve_question_alignment_profile(raw, &q))
    });

    let comp_raw = state
        .db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! { "_id": comp_oid })
        .await?
        .ok_or_else(|| AppError::NotFound("Questão complementar não encontrada".into()))?;
    let comp_q = question_compatibility::decode_for_ctv(comp_raw.clone())?;
    let comp_profile = resolve_question_alignment_profile(&comp_raw, &comp_q);
    let indices = comp_q.answer_indices()?;
    let correct_index = *indices.first().unwrap_or(&0);
    if req.chosen_index >= comp_q.answers.len() {
        return Err(AppError::BadRequest(
            "Alternativa escolhida fora dos limites da questão complementar".into(),
        ));
    }
    let is_correct = indices.contains(&req.chosen_index);

    let effective_offer_id = req
        .offer_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| deterministic_offer_id(user_id, &req.parent_attempt_id));

    let offers_coll = state.db.collection::<Document>("ctv_offers");
    let offer_doc_key = format!("{user_id}:{effective_offer_id}");
    let existing_offer = offers_coll.find_one(doc! { "_id": &offer_doc_key }).await?;

    let occurred_dt = parse_client_timestamp_or_now(req.occurred_at.as_deref())?;
    let displayed_dt =
        if let Some(d_str) = req.displayed_at.as_deref().filter(|s| !s.trim().is_empty()) {
            parse_client_timestamp_or_now(Some(d_str))?.min(occurred_dt)
        } else if let Some(off_disp) = existing_offer
            .as_ref()
            .and_then(|d| d.get_datetime("first_displayed_at").ok())
            .map(|d| d.to_chrono())
        {
            off_disp.min(occurred_dt)
        } else {
            occurred_dt
        };

    let (verification_mode, framing_reason_code, framing_reason_pt) =
        if let Some(src_prof) = &source_profile_opt {
            let allow_seen = existing_offer
                .as_ref()
                .and_then(|d| d.get_str("diagnostic_purpose").ok())
                == Some("RETENTION_CHECK")
                || req.verification_mode.as_deref() == Some("INTENTIONAL_REVIEW");
            match evaluate_candidate_alignment(src_prof, &comp_profile, allow_seen) {
                CandidateAlignmentDecision::Accepted {
                    mode,
                    framing_reason_code,
                    framing_reason_pt,
                    ..
                } => (mode, framing_reason_code.to_string(), framing_reason_pt),
                CandidateAlignmentDecision::Rejected { reason_code } => (
                    VerificationMode::IntentionalReview,
                    reason_code.to_string(),
                    "Item sem independência textual suficiente em relação à questão original."
                        .to_string(),
                ),
            }
        } else {
            let fallback_mode = req
                .verification_mode
                .as_deref()
                .or_else(|| {
                    existing_offer
                        .as_ref()
                        .and_then(|d| d.get_str("verification_mode").ok())
                })
                .map(VerificationMode::from_code)
                .unwrap_or(VerificationMode::ConceptualTransfer);
            (
                fallback_mode,
                "FALLBACK_PROFILE".to_string(),
                "Enquadramento recuperado da oferta.".to_string(),
            )
        };

    let had_decisive_help = req.had_decisive_help
        || req
            .consulted_resources
            .iter()
            .any(|r| is_rule_revealing_help(r));

    let prior_snap = load_user_exposure_snapshot(
        &state.db,
        user_id,
        Some(&effective_offer_id),
        Some(&req.ctv_attempt_id),
        Some((displayed_dt, &effective_offer_id)),
    )
    .await?;

    let was_previously_exposed = req.was_previously_exposed
        || prior_snap.has_seen(
            &req.complementary_question_id,
            &comp_profile.content_revision,
            &comp_profile.semantic_revision,
        );

    let exposure_history_known = timing != "UNKNOWN_CONDITION";
    let verdict = evaluate_ctv_independence(&CtvIndependenceInput {
        verification_mode,
        action: "ANSWERED".to_string(),
        verification_timing: timing.to_string(),
        was_previously_exposed,
        exposure_history_known,
        had_decisive_help,
        is_correct,
    });
    let counts_as_independent_transfer = verdict.counts_as_independent_attempt;

    let (title_pt, msg_pt) = match verdict.classification_code {
        "INDEPENDENT_CORRECT" => (
            "Transferência conceitual demonstrada".to_string(),
            "Você aplicou o mesmo conceito e regra em outro contexto antes de ver o gabarito. Evidência favorável de compreensão transferível.".to_string(),
        ),
        "INDEPENDENT_ERROR" => (
            "Transferência ainda em consolidação".to_string(),
            "Na aplicação independente em novo contexto houve divergência. Confira abaixo a regra decisiva cobrada nas duas situações.".to_string(),
        ),
        "TOPIC_PRACTICE_RECORDED" => (
            "Prática complementar do tópico registrada".to_string(),
            "Esta atividade exercitou outro recorte do tópico. O conceito da questão original permanece com transferência não mensurada.".to_string(),
        ),
        "POST_INSTRUCTION_FIXATION" => {
            if is_correct {
                (
                    "Fixação pós-instrução registrada".to_string(),
                    "Acerto após explicação ou ajuda decisiva: registrado como fixação imediata, sem contar isoladamente como transferência independente.".to_string(),
                )
            } else {
                (
                    "Ponto de atenção pós-instrução".to_string(),
                    "Mesmo após auxílio ou explicação, houve dúvida na aplicação; o conceito será retomado de forma espaçada.".to_string(),
                )
            }
        }
        "PRIOR_EXPOSURE_REVIEW" => (
            "Revisão de item já conhecido".to_string(),
            "Este conteúdo já havia sido visto anteriormente; o resultado foi registrado como revisão sem inflar tentativas independentes.".to_string(),
        ),
        _ => (
            "Verificação complementar concluída".to_string(),
            verdict.summary_pt.to_string(),
        ),
    };

    let subj_id = source_profile_opt
        .as_ref()
        .and_then(|p| p.subject_id.clone())
        .or_else(|| comp_profile.subject_id.clone());
    let topic_id = source_profile_opt
        .as_ref()
        .and_then(|p| p.topic_id.clone())
        .or_else(|| comp_profile.topic_id.clone());
    let target_concept_id = source_profile_opt
        .as_ref()
        .and_then(|p| p.target_concept_id.clone());
    let target_tested_rule = source_profile_opt
        .as_ref()
        .and_then(|p| p.tested_rule.clone());
    // Só vincula `concept_id` ao alvo quando a complementar efetivamente verificou esse conceito;
    // em prática do tópico (ex.: convexidade quando a origem pedia duration), registra apenas o conceito do próprio item ou nulo!
    let credited_concept_id = if verification_mode == VerificationMode::ConceptualTransfer {
        target_concept_id
            .clone()
            .or_else(|| comp_profile.target_concept_id.clone())
    } else {
        comp_profile.target_concept_id.clone()
    };

    let trigger_reason = req.trigger_reason.clone().or_else(|| {
        existing_offer
            .as_ref()
            .and_then(|d| d.get_str("trigger_reason").ok())
            .map(str::to_string)
    });
    let trigger_cohort = trigger_cohort_for_reason(trigger_reason.as_deref());

    let response = CtvSubmitResponse {
        ctv_attempt_id: req.ctv_attempt_id.clone(),
        offer_id: effective_offer_id.clone(),
        parent_attempt_id: req.parent_attempt_id.clone(),
        complementary_question_id: req.complementary_question_id.clone(),
        is_correct,
        correct_index,
        explanation: comp_q.explanation.clone(),
        verification_timing: timing.to_string(),
        was_previously_exposed,
        counts_as_independent_transfer,
        verification_mode: verification_mode.code().to_string(),
        classification_code: verdict.classification_code.to_string(),
        framing_reason_code: framing_reason_code.clone(),
        target_concept_id: target_concept_id.clone(),
        target_tested_rule: target_tested_rule.clone(),
        consolidated_diagnostic_title_pt: title_pt,
        consolidated_diagnostic_message_pt: msg_pt,
    };

    let record_doc = doc! {
        "_id": &receipt_key,
        "ctv_attempt_id": &req.ctv_attempt_id,
        "offer_id": &effective_offer_id,
        "user_id": user_id,
        "parent_attempt_id": &req.parent_attempt_id,
        "source_question_id": &req.source_question_id,
        "complementary_question_id": &req.complementary_question_id,
        "content_revision": &comp_profile.content_revision,
        "semantic_revision": &comp_profile.semantic_revision,
        "canonical_subject_id": subj_id,
        "canonical_topic_id": topic_id,
        "target_concept_id": target_concept_id,
        "target_tested_rule": target_tested_rule,
        "concept_id": credited_concept_id,
        "item_concept_id": comp_profile.target_concept_id,
        "verification_mode": verification_mode.code(),
        "framing_reason_code": &framing_reason_code,
        "framing_reason_pt": &framing_reason_pt,
        "trigger_reason": &trigger_reason,
        "trigger_cohort": trigger_cohort,
        "session_uuid": &req.session_uuid,
        "verification_timing": timing,
        "had_decisive_help": had_decisive_help,
        "consulted_resources": &req.consulted_resources,
        "focus_loss_count": i32::from(req.focus_loss_count),
        "client_was_previously_exposed": req.was_previously_exposed,
        "was_previously_exposed": was_previously_exposed,
        "counts_as_independent_transfer": counts_as_independent_transfer,
        "counts_as_independent_correct": verdict.counts_as_independent_correct,
        "classification_code": verdict.classification_code,
        "chosen_index": req.chosen_index as i32,
        "chosen_text": &req.chosen_text,
        "is_correct": is_correct,
        "declared_confidence": req.declared_confidence.map(DeclaredConfidence::code),
        "cognitive_strategy": req.cognitive_strategy.map(CognitiveStrategy::code),
        "active_seconds": req.active_seconds,
        "displayed_at": bson::DateTime::from_chrono(displayed_dt),
        "occurred_at": bson::DateTime::from_chrono(occurred_dt),
        "response_ack": bson::to_document(&response).unwrap_or_default(),
    };
    let insert_res = ctv_coll
        .update_one(
            doc! { "_id": &receipt_key },
            doc! { "$setOnInsert": record_doc },
        )
        .upsert(true)
        .await?;

    // Garante que a oferta fique registrada como exibida e respondida
    let _ = offers_coll
        .update_one(
            doc! { "_id": &offer_doc_key },
            doc! {
                "$set": {
                    "state": "ANSWERED",
                    "answered_ctv_attempt_id": &req.ctv_attempt_id,
                    "content_revision": &comp_profile.content_revision,
                    "semantic_revision": &comp_profile.semantic_revision,
                    "updated_at": bson::DateTime::now(),
                },
                "$min": {
                    "first_displayed_at": bson::DateTime::from_chrono(displayed_dt),
                },
                "$setOnInsert": {
                    "_id": &offer_doc_key,
                    "user_id": user_id,
                    "offer_id": &effective_offer_id,
                    "parent_attempt_id": &req.parent_attempt_id,
                    "source_question_id": &req.source_question_id,
                    "complementary_question_id": &req.complementary_question_id,
                    "verification_mode": verification_mode.code(),
                    "framing_reason_code": &framing_reason_code,
                    "trigger_reason": &trigger_reason,
                    "trigger_cohort": trigger_cohort,
                    "delivery_source": req.delivery_source.as_deref().unwrap_or("REUSED_FROM_DB"),
                    "supplied_at": bson::DateTime::from_chrono(displayed_dt),
                }
            },
        )
        .upsert(true)
        .await;

    // Reconcilia caso outro dispositivo tenha registrado uma tentativa posterior que dependia deste item!
    let _ = reconcile_user_ctv_history(
        &state.db,
        user_id,
        &req.complementary_question_id,
        &comp_profile.content_revision,
        &comp_profile.semantic_revision,
    )
    .await?;

    if insert_res.upserted_id.is_some() {
        // Atualiza métricas operacionais de qualidade e reutilização em diagnostic_meta de forma atômica ($inc)!
        if let Ok(dmeta) = comp_raw.get_document("diagnostic_meta") {
            let qm = dmeta
                .get_document("quality_metrics")
                .cloned()
                .unwrap_or_default();
            let prev_att = qm
                .get_i64("attempts_count")
                .or_else(|_| qm.get_i32("attempts_count").map(i64::from))
                .unwrap_or(0);
            let prev_corr = qm
                .get_i64("correct_count")
                .or_else(|_| qm.get_i32("correct_count").map(i64::from))
                .unwrap_or(0);
            let prev_avg_s = qm.get_f64("avg_active_seconds").unwrap_or(0.0);

            let next_att = prev_att.saturating_add(1);
            let next_corr = prev_corr.saturating_add(i64::from(is_correct));
            let next_avg_s = if next_att > 0 {
                ((prev_avg_s * (prev_att as f64)) + (req.active_seconds.max(1) as f64))
                    / (next_att as f64)
            } else {
                req.active_seconds.max(1) as f64
            };

            let mut set_doc = doc! {
                "diagnostic_meta.quality_metrics.avg_active_seconds": (next_avg_s * 100.0).round() / 100.0,
            };

            if dmeta.get_str("generation_origin").ok() == Some("AI_ON_DEMAND") && next_att >= 10 {
                let acc_rate = (next_corr as f64) / (next_att as f64);
                if acc_rate < 0.10 {
                    set_doc.insert("diagnostic_meta.quality_status", "SUSPENDED");
                    set_doc.insert("diagnostic_meta.validation_status", "PENDING_HUMAN_REVIEW");
                }
            }

            let _ = question_storage::collection(&state.db, "todasQuestoes")
                .update_one(
                    doc! { "_id": comp_oid },
                    doc! {
                        "$inc": {
                            "diagnostic_meta.usage_count": 1_i64,
                            "diagnostic_meta.quality_metrics.attempts_count": 1_i64,
                            "diagnostic_meta.quality_metrics.correct_count": i64::from(is_correct),
                        },
                        "$set": set_doc
                    },
                )
                .await;
        }
    }

    // Recarrega response_ack pós-reconciliação caso a própria tentativa tenha sido ajustada
    if let Some(reloaded) = ctv_coll.find_one(doc! { "_id": &receipt_key }).await? {
        if let Ok(resp_doc) = reloaded.get_document("response_ack") {
            if let Ok(ack) = bson::from_document::<CtvSubmitResponse>(resp_doc.clone()) {
                return Ok(ack);
            }
        }
    }

    Ok(response)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::NaiveDate;

    #[test]
    fn anti_paraphrase_similarity_blocks_reworded_clones_and_allows_distinct_contexts() {
        let orig = "Em relação aos poderes administrativos, o ato pelo qual a Administração aplica sanção disciplinar a servidor após regular processo administrativo decorre imediatamente do poder:";
        let paraphrase = "Quanto aos poderes da Administração Pública, o ato mediante o qual a Administração aplica penalidade disciplinar ao servidor após processo administrativo regular decorre do poder:";
        let distinct_transfer = "Um fiscal de vigilância sanitária interditou cautelarmente restaurante privado que comercializava alimentos deteriorados sem alvará válido. Essa medida restritiva sobre atividade particular manifesta o poder:";

        assert!(jaccard_trigram_similarity(orig, paraphrase) >= MAX_SOURCE_SIMILARITY_FOR_TRANSFER);
        assert!(
            jaccard_trigram_similarity(orig, distinct_transfer)
                < MAX_SOURCE_SIMILARITY_FOR_TRANSFER
        );
    }

    #[test]
    fn generated_ctv_question_satisfies_v2_4_contract_and_remains_isolated_from_standard_simulado()
    {
        let cat = question_contracts::catalog();
        let active_subject = cat["subjects"]
            .as_array()
            .unwrap()
            .iter()
            .find(|s| s["status"] == "ACTIVE")
            .unwrap();
        let subj_id = active_subject["subject_id"].as_str().unwrap();
        let subj_label = active_subject["label"].as_str().unwrap();
        let active_topic = cat["topics"]
            .as_array()
            .unwrap()
            .iter()
            .find(|t| t["subject_id"] == subj_id && t["status"] == "ACTIVE")
            .unwrap();
        let top_id = active_topic["topic_id"].as_str().unwrap();

        let cand = GeneratedCtvPayload {
            question: "Durante auditoria em contrato de tecnologia de autarquia federal, verificou-se a aplicação de multa contratual prevista em cláusula regulamentar após contraditório. Esse ato expressa qual prerrogativa administrativa?".to_string(),
            answers: vec![
                "Poder disciplinar restrito exclusivamente aos servidores estatutários efetivos.".to_string(),
                "Poder hierárquico exercido entre pessoas jurídicas distintas sem subordinação.".to_string(),
                "Poder sancionador decorrente de vínculo jurídico específico submetido ao regime administrativo.".to_string(),
                "Poder regulamentar originário para inovar primariamente na ordem jurídica.".to_string(),
                "Poder discricionário imune a qualquer controle judicial de legalidade.".to_string(),
            ],
            correct_index: 2,
            explanation: "A aplicação de sanção a particular contratado que mantém vínculo jurídico específico com a Administração fundamenta-se no regime sancionador/disciplinar vinculado ao contrato, distinto do poder de polícia geral.".to_string(),
            tested_rule: "Distinção entre sanção baseada em vínculo específico e polícia administrativa geral.".to_string(),
            decisive_stem_excerpt: "aplicação de multa contratual prevista em cláusula regulamentar após contraditório".to_string(),
            estimated_difficulty: "MEDIUM".to_string(),
            normative_certainty: "HIGH".to_string(),
            concept_adherence: "CONFIRMED".to_string(),
        };

        validate_generated_candidate(
            &cand,
            "No âmbito municipal, a cassação de alvará de funcionamento de estabelecimento comercial por infração sanitária decorre do poder de polícia.",
        )
        .expect("distinct transfer candidate must pass anti-cue and anti-paraphrase checks");

        let (doc_v2, auto_approved) = build_v2_ctv_question_contract(
            ObjectId::new(),
            &cand,
            "000000000000000000000001",
            subj_id,
            subj_label,
            top_id,
            &[],
            "CONCEPTUAL_TRANSFER",
            "grok-4",
            false,
        )
        .expect("must build valid V2.4 question contract");
        assert!(auto_approved);

        let eval_date = NaiveDate::from_ymd_opt(2026, 11, 25).unwrap();
        // 1. Eligible for CTV (ComplementaryVerification)
        let ctv_elig = question_eligibility::evaluate_question_eligibility(
            &doc_v2,
            &cat,
            None,
            eval_date,
            &TrustedReviewContext::default(),
            EligibilityMode::ComplementaryVerification,
        );
        assert!(ctv_elig.eligible, "{ctv_elig:?}");

        // 2. Strictly excluded from StandardObjective (SIMULADO / official exam queues) until human confirmation
        let std_elig = question_eligibility::evaluate_question_eligibility(
            &doc_v2,
            &cat,
            None,
            eval_date,
            &TrustedReviewContext::default(),
            EligibilityMode::StandardObjective,
        );
        assert!(!std_elig.eligible);
        assert!(std_elig
            .reason_codes
            .contains(&"CLASSIFICATION_NOT_CONFIRMED"));
    }

    #[test]
    fn simulado_mode_defers_inline_ctv_and_guess_hit_triggers_in_practice() {
        let policy = CtvPolicyConfig::default();
        let base_req = CtvEvaluateRequest {
            parent_attempt_id: uuid::Uuid::new_v4().to_string(),
            source_question_id: "000000000000000000000001".to_string(),
            session_uuid: "sess-1".to_string(),
            quiz_mode: "SIMULADO".to_string(),
            is_correct: true,
            is_blank: false,
            declared_confidence: Some(DeclaredConfidence::Guess),
            cognitive_strategy: Some(CognitiveStrategy::RandomGuess),
            confidence_level: 1,
            confidence_explicit: true,
            session_ctv_count: 0,
            attempts_since_last_ctv: 0,
            force_post_instruction: false,
            rationale_contradicted: false,
            max_search_pages: None,
            simulate_search_error_after_page: None,
        };
        let (trig_sim, status_sim, _, _) =
            decide_ctv_trigger(&policy, &base_req, false, false, false, true, false);
        assert!(!trig_sim);
        assert_eq!(status_sim, "deferred_post_simulado");

        let mut practice_req = base_req;
        practice_req.quiz_mode = "STANDARD".to_string();
        let (trig_prac, status_prac, reason, purpose) =
            decide_ctv_trigger(&policy, &practice_req, false, false, false, true, false);
        assert!(trig_prac);
        assert_eq!(status_prac, "triggered");
        assert_eq!(reason, Some("GUESS_HIT"));
        assert_eq!(purpose, Some("GUESS_CONFIRMATION"));
    }

    fn make_duration_ctv_bson_doc(
        oid: ObjectId,
        stem: &str,
        concept_id: &str,
        normative_certainty: &str,
        concept_adherence: &str,
        suspended: bool,
    ) -> Document {
        let cand = GeneratedCtvPayload {
            question: stem.to_string(),
            answers: vec![
                "O segundo título sofre maior variação percentual aproximada de preço diante do choque paralelo.".to_string(),
                "O primeiro título sofre maior variação percentual porque possui menor prazo médio.".to_string(),
                "Ambos os títulos sofrem exatamente a mesma variação percentual de preço.".to_string(),
                "A variação percentual depende apenas do valor de face nominal e ignora a duration.".to_string(),
                "Nenhum título prefixado sofre oscilação de marcação a mercado antes do vencimento.".to_string(),
            ],
            correct_index: 0,
            explanation: "Pela aproximação linear da duration modificada, a variação percentual de preço é proporcional a -D_mod * delta_y.".to_string(),
            tested_rule: "Sensibilidade percentual aproximada do preço de um título prefixado a choques paralelos de juros via duration modificada.".to_string(),
            decisive_stem_excerpt: "submetidos ao mesmo pequeno choque paralelo na taxa de juros".to_string(),
            estimated_difficulty: "MEDIUM".to_string(),
            normative_certainty: normative_certainty.to_string(),
            concept_adherence: concept_adherence.to_string(),
        };
        let (mut v2_json, _) = build_v2_ctv_question_contract(
            oid,
            &cand,
            "000000000000000000000099",
            "MFIN",
            "Matemática Financeira",
            "MFIN_01_004",
            &[concept_id.to_string()],
            "CONCEPTUAL_TRANSFER",
            "grok-4",
            false,
        )
        .unwrap();
        if suspended {
            v2_json["diagnostic_meta"]["quality_status"] = json!("SUSPENDED");
            v2_json["diagnostic_meta"]["validation_status"] = json!("PENDING_HUMAN_REVIEW");
        }
        question_contracts::contract_to_bson_question(&v2_json).unwrap()
    }

    #[test]
    fn a02_dubious_ai_candidate_routes_to_pending_human_review_and_is_blocked_from_student_delivery(
    ) {
        let cat = question_contracts::catalog();
        let eval_date = NaiveDate::from_ymd_opt(2026, 11, 25).unwrap();
        let dubious_doc = make_duration_ctv_bson_doc(
            ObjectId::new(),
            "Considere dois títulos prefixados com durations modificadas de 2,1 e 5,4 submetidos ao mesmo pequeno choque paralelo na taxa de juros de 0,40%. Qual apresenta maior variação percentual aproximada?",
            "CON_MFIN_DURATION_MODIFICADA",
            "HIGH",
            "DUBIOUS",
            false,
        );
        let dmeta = dubious_doc.get_document("diagnostic_meta").unwrap();
        assert_eq!(
            dmeta.get_str("validation_status").unwrap(),
            "PENDING_HUMAN_REVIEW"
        );
        assert_eq!(dmeta.get_str("quality_status").unwrap(), "MONITORING");

        let elig = question_eligibility::evaluate_bson_question_for_practice(
            &dubious_doc,
            &cat,
            None,
            eval_date,
            &TrustedReviewContext::default(),
            EligibilityMode::ComplementaryVerification,
        );
        assert!(
            !elig.eligible,
            "Dubious AI CTV item must be routed to PENDING_HUMAN_REVIEW and never served to students"
        );
    }

    #[test]
    fn a08_adaptive_cohorts_separate_difficulty_1_of_2_with_dismissal_excluded_from_periodic_3_of_4(
    ) {
        use crate::services::ctv_admin_metrics::build_adaptive_cohort_dto;
        let diff = build_adaptive_cohort_dto(
            "DIFFICULTY_DETECTED",
            "Disparo adaptativo",
            "Escopo dificuldade",
            3, // 2 respondidas + 1 dispensada
            1, // 1 dispensada -> fora do denominador!
            2,
            2,
            1,
        );
        assert_eq!(diff.independent_eligible_responses, 2);
        assert_eq!(diff.independent_correct_count, 1);
        assert_eq!(diff.independent_success_rate_pct, Some(50.0));
        assert!(diff.summary_pt.contains("1/2"));
        assert!(diff.summary_pt.contains("1 oferta(s) dispensada(s)"));

        let per = build_adaptive_cohort_dto(
            "PERIODIC_SAMPLING",
            "Amostragem periódica",
            "Escopo amostragem",
            4,
            0,
            4,
            4,
            3,
        );
        assert_eq!(per.independent_eligible_responses, 4);
        assert_eq!(per.independent_correct_count, 3);
        assert_eq!(per.independent_success_rate_pct, Some(75.0));
        assert!(per.summary_pt.contains("3/4"));
    }

    #[test]
    fn a10_cursor_pagination_scans_past_80_seen_items_to_find_81st_unseen_with_zero_ai_calls_and_flags_inconclusive(
    ) {
        let source_oid = ObjectId::parse_str("000000000000000000000001").unwrap();
        let source_profile = QuestionAlignmentProfile {
            question_id: source_oid.to_hex(),
            subject_id: Some("MFIN".to_string()),
            topic_id: Some("MFIN_01_004".to_string()),
            target_concept_id: Some("CON_MFIN_DURATION_MODIFICADA".to_string()),
            all_concept_ids: vec!["CON_MFIN_DURATION_MODIFICADA".to_string()],
            tested_rule: Some("Sensibilidade percentual aproximada de preço via duration modificada.".to_string()),
            question_type: "MULTIPLE_CHOICE".to_string(),
            stem: "Qual é o significado econômico da duration modificada na precificação de títulos de renda fixa?".to_string(),
            content_revision: "src-rev".to_string(),
            ..Default::default()
        };

        let mut all_docs = Vec::with_capacity(81);
        let mut exposure_snap = UserExposureSnapshot::default();
        for idx in 1..=81usize {
            let oid = ObjectId::parse_str(format!("{:024x}", idx + 100)).unwrap();
            let stem = format!(
                "Em carteira institucional #{idx}, dois títulos prefixados com durations modificadas de 2,{idx} e 5,{idx} sofrem o mesmo pequeno choque paralelo na taxa de juros. Qual apresenta maior variação percentual aproximada?"
            );
            let doc_bson = make_duration_ctv_bson_doc(
                oid,
                &stem,
                "CON_MFIN_DURATION_MODIFICADA",
                "HIGH",
                "CONFIRMED",
                false,
            );
            if idx <= 80 {
                exposure_snap.seen_question_ids.insert(oid.to_hex());
            }
            all_docs.push(doc_bson);
        }
        let target_81_hex = all_docs[80].get_object_id("_id").unwrap().to_hex();

        // Simula o cursor estável `_id > cursor_oid` em páginas de 40 (`CTV_BANK_PAGE_SIZE = 40`):
        // 81 itens exigem 3 páginas (40 + 40 + 1).
        let run_cursor_scan = |max_pages: usize,
                               fail_after_page: Option<usize>|
         -> Result<Option<String>, BankSearchError> {
            let mut cursor_oid: Option<ObjectId> = None;
            let mut pages_scanned = 0usize;
            let mut best_transfer: Option<(i32, SelectedBankCandidate)> = None;
            let mut best_practice: Option<(i32, SelectedBankCandidate)> = None;
            let empty_reports = HashSet::new();
            loop {
                if fail_after_page.is_some_and(|f| pages_scanned >= f) || pages_scanned >= max_pages
                {
                    return Err(BankSearchError::Inconclusive);
                }
                let page: Vec<Document> = all_docs
                    .iter()
                    .filter(|d| {
                        let id = d.get_object_id("_id").unwrap();
                        id != source_oid && cursor_oid.is_none_or(|c| id > c)
                    })
                    .take(CTV_BANK_PAGE_SIZE as usize)
                    .cloned()
                    .collect();
                if page.is_empty() {
                    break;
                }
                pages_scanned += 1;
                let fetched_len = page.len();
                cursor_oid = page.last().and_then(|d| d.get_object_id("_id").ok());
                evaluate_bank_page_docs(
                    page,
                    source_oid,
                    &source_profile,
                    &exposure_snap,
                    &empty_reports,
                    false,
                    &mut best_transfer,
                    &mut best_practice,
                );
                if (fetched_len as i64) < CTV_BANK_PAGE_SIZE {
                    break;
                }
            }
            assert_eq!(pages_scanned, 3);
            Ok(best_transfer.map(|(_, c)| c.dto.id))
        };

        let ai_calls = std::sync::atomic::AtomicUsize::new(0);
        let found = run_cursor_scan(DEFAULT_MAX_SEARCH_PAGES, None).expect("3 pages must succeed");
        assert_eq!(found.as_deref(), Some(target_81_hex.as_str()));
        assert_eq!(ai_calls.load(std::sync::atomic::Ordering::SeqCst), 0);

        // Falha de leitura na página 2 ou teto de 2 páginas -> SEARCH_INCONCLUSIVE (nunca "0 elegíveis"!)
        assert!(matches!(
            run_cursor_scan(DEFAULT_MAX_SEARCH_PAGES, Some(1)),
            Err(BankSearchError::Inconclusive)
        ));
        assert!(matches!(
            run_cursor_scan(2, None),
            Err(BankSearchError::Inconclusive)
        ));
    }

    #[tokio::test]
    async fn a11_generation_lock_and_bank_recheck_ensure_single_ai_call_and_shared_permanent_item()
    {
        let source_oid = ObjectId::parse_str("000000000000000000000001").unwrap();
        let source_profile = Arc::new(QuestionAlignmentProfile {
            question_id: source_oid.to_hex(),
            subject_id: Some("MFIN".to_string()),
            topic_id: Some("MFIN_01_004".to_string()),
            target_concept_id: Some("CON_MFIN_DURATION_MODIFICADA".to_string()),
            all_concept_ids: vec!["CON_MFIN_DURATION_MODIFICADA".to_string()],
            tested_rule: Some(
                "Sensibilidade percentual aproximada de preço via duration modificada.".to_string(),
            ),
            question_type: "MULTIPLE_CHOICE".to_string(),
            stem: "Qual é o significado econômico da duration modificada de um título?".to_string(),
            content_revision: "src-rev-a11".to_string(),
            ..Default::default()
        });
        let bank: Arc<Mutex<Vec<Document>>> = Arc::new(Mutex::new(Vec::new()));
        let ai_calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let lock_key = format!(
            "MFIN:CON_MFIN_DURATION_MODIFICADA:CONCEPTUAL_TRANSFER:{}",
            uuid::Uuid::new_v4()
        );

        let spawn_worker = |user_seen: UserExposureSnapshot| {
            let bank = bank.clone();
            let ai_calls = ai_calls.clone();
            let sp = source_profile.clone();
            let lk = lock_key.clone();
            tokio::spawn(async move {
                let mutex = generation_lock_for(&lk).await;
                let _guard = mutex.lock().await;
                // Reconsulta sob trava antes de chamar o provedor (Seção 5.2 / A11)
                let existing_docs = bank.lock().await.clone();
                let mut best_t = None;
                let mut best_p = None;
                evaluate_bank_page_docs(
                    existing_docs,
                    source_oid,
                    &sp,
                    &user_seen,
                    &HashSet::new(),
                    false,
                    &mut best_t,
                    &mut best_p,
                );
                if let Some((_, reused)) = best_t {
                    return (reused.dto.id, "REUSED_FROM_DB");
                }
                // Banco vazio: gera, persiste em todasQuestoes ANTES de entregar ao aluno
                ai_calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                tokio::time::sleep(std::time::Duration::from_millis(20)).await;
                let generated_oid = ObjectId::new();
                let doc_bson = make_duration_ctv_bson_doc(
                    generated_oid,
                    "Considere dois títulos prefixados com durations modificadas de 3,0 e 6,2 submetidos ao mesmo pequeno choque paralelo na taxa de juros de 0,25%. Qual apresenta maior variação percentual aproximada?",
                    "CON_MFIN_DURATION_MODIFICADA",
                    "HIGH",
                    "CONFIRMED",
                    false,
                );
                bank.lock().await.push(doc_bson);
                (generated_oid.to_hex(), "AI_GENERATED_PERMANENT")
            })
        };

        let (res_a, res_b) = tokio::join!(
            spawn_worker(UserExposureSnapshot::default()),
            spawn_worker(UserExposureSnapshot::default())
        );
        let (id_a, src_a) = res_a.unwrap();
        let (id_b, src_b) = res_b.unwrap();
        assert_eq!(
            id_a, id_b,
            "Both concurrent students must receive the exact same permanent _id"
        );
        assert_eq!(
            ai_calls.load(std::sync::atomic::Ordering::SeqCst),
            1,
            "Only 1 real AI call may occur"
        );
        assert!(
            (src_a == "AI_GENERATED_PERMANENT" && src_b == "REUSED_FROM_DB")
                || (src_b == "AI_GENERATED_PERMANENT" && src_a == "REUSED_FROM_DB")
        );
        assert_eq!(bank.lock().await.len(), 1);
    }

    #[test]
    fn a12_post_ai_duplicate_revalidates_eligibility_concept_and_exposure_and_bars_suspended_or_seen(
    ) {
        let source_oid = ObjectId::parse_str("000000000000000000000001").unwrap();
        let source_profile = QuestionAlignmentProfile {
            question_id: source_oid.to_hex(),
            subject_id: Some("MFIN".to_string()),
            topic_id: Some("MFIN_01_004".to_string()),
            target_concept_id: Some("CON_MFIN_DURATION_MODIFICADA".to_string()),
            all_concept_ids: vec!["CON_MFIN_DURATION_MODIFICADA".to_string()],
            tested_rule: Some(
                "Sensibilidade percentual aproximada de preço via duration modificada.".to_string(),
            ),
            question_type: "MULTIPLE_CHOICE".to_string(),
            stem: "Qual é o significado econômico da duration modificada de um título?".to_string(),
            content_revision: "src-rev-a12".to_string(),
            ..Default::default()
        };

        let valid_oid = ObjectId::new();
        let valid_dup = make_duration_ctv_bson_doc(
            valid_oid,
            "Considere dois títulos prefixados com durations modificadas de 1,8 e 4,9 submetidos ao mesmo pequeno choque paralelo na taxa de juros. Qual apresenta maior variação percentual aproximada?",
            "CON_MFIN_DURATION_MODIFICADA",
            "HIGH",
            "CONFIRMED",
            false,
        );
        // 1. Item elegível, alinhado e inédito -> reaproveitado (porém a chamada já ocorreu: 1 real call, 1 duplicate barred, 0 avoided)
        let ok_reused = revalidate_post_ai_duplicate_candidate(
            &valid_dup,
            source_oid,
            &source_profile,
            &UserExposureSnapshot::default(),
            true,
            false,
        );
        assert_eq!(
            ok_reused.as_ref().map(|c| c.dto.id.as_str()),
            Some(valid_oid.to_hex().as_str())
        );

        // 2. Mesmo item já visto pelo aluno -> bloqueado como verificação inédita!
        let mut seen_snap = UserExposureSnapshot::default();
        seen_snap.seen_question_ids.insert(valid_oid.to_hex());
        assert!(revalidate_post_ai_duplicate_candidate(
            &valid_dup,
            source_oid,
            &source_profile,
            &seen_snap,
            true,
            false,
        )
        .is_none());

        // 3. Item suspenso (`quality_status: SUSPENDED`) -> bloqueado!
        let suspended_dup = make_duration_ctv_bson_doc(
            ObjectId::new(),
            "Considere dois títulos prefixados com durations modificadas de 1,8 e 4,9 submetidos ao mesmo pequeno choque paralelo na taxa de juros. Qual apresenta maior variação percentual aproximada?",
            "CON_MFIN_DURATION_MODIFICADA",
            "HIGH",
            "CONFIRMED",
            true,
        );
        assert!(revalidate_post_ai_duplicate_candidate(
            &suspended_dup,
            source_oid,
            &source_profile,
            &UserExposureSnapshot::default(),
            true,
            false,
        )
        .is_none());

        // 4. Item de conceito divergente (`CON_MFIN_CONVEXIDADE`) -> não pode ser entregue como verificação conceitual do alvo!
        let divergent_dup = make_duration_ctv_bson_doc(
            ObjectId::new(),
            "Considere duas carteiras com diferentes medidas de convexidade submetidas a grande variação de juros. Qual efeito de segunda ordem a convexidade captura?",
            "CON_MFIN_CONVEXIDADE",
            "HIGH",
            "CONFIRMED",
            false,
        );
        assert!(revalidate_post_ai_duplicate_candidate(
            &divergent_dup,
            source_oid,
            &source_profile,
            &UserExposureSnapshot::default(),
            true,
            false,
        )
        .is_none());
    }

    #[test]
    fn a14_legacy_records_without_timing_or_strategy_preserve_unknown_condition_and_unknown_grounding(
    ) {
        let legacy_indep = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: VerificationMode::ConceptualTransfer,
            action: "ANSWERED".to_string(),
            verification_timing: "".to_string(),
            was_previously_exposed: false,
            exposure_history_known: false,
            had_decisive_help: false,
            is_correct: true,
        });
        assert_eq!(legacy_indep.classification_code, "UNKNOWN_CONDITION");
        assert_eq!(legacy_indep.independent_attempts_delta, 0);
        assert_eq!(legacy_indep.independent_correct_delta, 0);

        let legacy_grounding =
            crate::services::metacognitive_engine::classify_hit_grounding(true, None, None, None);
        assert_eq!(legacy_grounding.code(), "UNKNOWN_GROUNDING");
        assert!(!legacy_grounding.credits_comprehension());
        assert!(!legacy_grounding.is_evaluable());
    }
}
