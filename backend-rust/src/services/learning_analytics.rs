//! Indicadores Analíticos Formais de Aprendizagem (10 KPIs) e de Eficiência Operacional de IA (8 KPIs).
//!
//! Cada indicador possui definição formal explícita, numerador, denominador, tamanho amostral (`n`),
//! critérios de elegibilidade, tratamento de dados ausentes, granularidade, período de referência e
//! limitação interpretativa, respeitando honestidade epistêmica em amostras pequenas.

use crate::{
    error::AppError,
    models::{activity::UserActivityDocument, exam_hierarchy::ExamLifecycleStatus},
    services::{
        calibration_engine::{
            calibrated_level_posterior, evaluate_walkforward_calibration,
            format_confidence_coverage_sentence, normalize_calibration_format,
            sufficiency_status_for_sample, FormatConfidenceCalibrationCellDto,
            WalkforwardCalibrationInput, CALIBRATION_POLICY_VERSION,
        },
        concept_mastery_engine::{
            classify_concept_mastery, ConceptMasteryEvaluation, ConceptMasteryInputs,
            ConceptMasteryState, ReuseEconomySimulation,
        },
        ctv_admin_metrics::{
            build_adaptive_cohort_dto, compute_admin_ctv_metrics_with_window, AdminCtvMetricsQuery,
            CtvAdaptiveSelectionReportDto, OperationalCostBreakdownDto,
        },
        ctv_alignment::trigger_cohort_for_reason,
        exam_store,
        metacognitive_engine::{
            classify_hit_grounding, classify_preliminary_matrix, CognitiveStrategy,
            DeclaredConfidence, HitGroundingCategory, MetacognitiveMatrixInputs,
            PreliminaryDiagnosis,
        },
        question_compatibility,
        retention_engine::retrievability,
    },
    AppState,
};
use bson::{doc, oid::ObjectId, Document};
use chrono::Utc;
use futures::TryStreamExt;
use serde::Serialize;
use serde_json::Value;
use std::collections::{HashMap, HashSet};

pub const DEFAULT_REFERENCE_COST_PER_GENERATION_USD: f64 = 0.03;

#[derive(Debug, Clone, Serialize)]
pub struct FormalKpiDto {
    pub kpi_id: &'static str,
    pub label_pt: &'static str,
    pub value: Option<f64>,
    pub unit: &'static str,
    pub numerator: f64,
    pub denominator: f64,
    pub sample_size_n: usize,
    pub formal_definition: &'static str,
    pub eligibility_criteria: &'static str,
    pub missing_data_rule: &'static str,
    pub granularity: &'static str,
    pub reference_period: &'static str,
    pub interpretive_limitation: &'static str,
}

#[derive(Debug, Clone, Serialize)]
pub struct ConfidenceLevelCalibrationDto {
    pub declared_confidence: DeclaredConfidence,
    pub label_pt: &'static str,
    pub attempts: usize,
    pub correct: usize,
    pub observed_accuracy_pct: Option<f64>,
    pub calibrated_posterior_mean: f64,
    pub credible_sd: f64,
    pub sufficiency_status: &'static str,
    pub coverage_sentence_pt: String,
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct GroundingEvidenceSummaryDto {
    pub raw_correct_hits: usize,
    pub evaluable_grounding_hits: usize,
    pub unknown_grounding_hits: usize,
    #[serde(default)]
    pub retrospective_legacy_hits: usize,
    pub valid_conceptual_hits: usize,
    pub valid_functional_strategy_hits: usize,
    pub divergent_rationale_hits: usize,
    pub fragile_or_guess_hits: usize,
    pub coverage_sentence_pt: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct UnitConceptMasteryDto {
    pub unit_id: String,
    pub canonical_subject_id: String,
    pub canonical_topic_id: String,
    pub concept_id: Option<String>,
    pub attempts: usize,
    pub distinct_questions: usize,
    pub transfer_attempts: usize,
    pub evaluation: ConceptMasteryEvaluation,
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct ConceptMasterySummaryDto {
    pub not_evaluated: usize,
    pub insufficient_evidence: usize,
    pub emerging_knowledge: usize,
    pub unstable_knowledge: usize,
    pub developing_mastery: usize,
    pub demonstrated_mastery: usize,
    pub consolidated_mastery: usize,
    pub mastery_at_risk: usize,
}

#[derive(Debug, Clone, Serialize)]
pub struct StudentLearningAnalyticsResponse {
    pub generated_at: String,
    pub calibration_policy_version: &'static str,
    pub learning_kpis: Vec<FormalKpiDto>,
    pub empirical_brier_score: Option<f64>,
    pub true_false_brier_score: Option<f64>,
    pub multiple_choice_brier_score: Option<f64>,
    pub cold_start_predictions_count: usize,
    pub confidence_calibration: Vec<ConfidenceLevelCalibrationDto>,
    pub confidence_calibration_by_format: Vec<FormatConfidenceCalibrationCellDto>,
    pub grounding_summary: GroundingEvidenceSummaryDto,
    pub ctv_adaptive_selection: CtvAdaptiveSelectionReportDto,
    pub concept_mastery_summary: ConceptMasterySummaryDto,
    pub unit_mastery: Vec<UnitConceptMasteryDto>,
}

#[derive(Debug, Clone, Serialize)]
pub struct AdminAiCtvMetricsResponse {
    pub generated_at: String,
    pub reference_period_label_pt: String,
    pub reference_cost_per_generation_usd: f64,
    pub operational_kpis: Vec<FormalKpiDto>,
    pub operational_breakdown: OperationalCostBreakdownDto,
    pub adaptive_selection: CtvAdaptiveSelectionReportDto,
    pub ctv_bank_summary: Value,
    pub recent_ctv_questions: Vec<Value>,
    pub default_simulation: ReuseEconomySimulation,
}

fn round2_opt(v: Option<f64>) -> Option<f64> {
    v.filter(|x| x.is_finite()).map(|x| (x * 100.0).round() / 100.0)
}

fn ratio_pct(num: f64, den: f64) -> Option<f64> {
    if den > 0.0 {
        round2_opt(Some(100.0 * num / den))
    } else {
        None
    }
}

pub async fn compute_student_analytics(
    state: &AppState,
    user_id: i64,
) -> Result<StudentLearningAnalyticsResponse, AppError> {
    let now = Utc::now();
    let now_ms = now.timestamp_millis();

    let raw_activities: Vec<Document> = state
        .db
        .collection::<Document>("userActivityLog")
        .find(doc! { "user_id": user_id })
        .projection(doc! { "pastSessions.ack": 0, "pastSessions.payload_sha256": 0 })
        .await?
        .try_collect()
        .await?;

    let ctv_attempts: Vec<Document> = state
        .db
        .collection::<Document>("conceptual_transfer_attempts")
        .find(doc! { "user_id": user_id })
        .await?
        .try_collect()
        .await?;

    let exams = exam_store::list(state, user_id).await.unwrap_or_default();
    let active_exam = exams
        .iter()
        .filter(|e| {
            e.is_active_in_ladder
                && !matches!(
                    e.status,
                    ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido
                )
        })
        .min_by_key(|e| e.ladder_priority);

    let mut target_topics: HashSet<(String, String)> = HashSet::new();
    if let Some(ex) = active_exam {
        for subj in &ex.subjects {
            for top in &subj.topics {
                target_topics.insert((
                    top.canonical_subject_id.clone(),
                    top.canonical_topic_id.clone(),
                ));
            }
        }
    }

    let qids: Vec<ObjectId> = raw_activities
        .iter()
        .filter_map(|d| d.get_object_id("question_id").ok())
        .collect();
    let q_map: HashMap<ObjectId, Document> = if qids.is_empty() {
        HashMap::new()
    } else {
        state
            .db
            .collection::<Document>("todasQuestoes")
            .find(doc! { "_id": { "$in": &qids } })
            .await?
            .try_collect::<Vec<_>>()
            .await?
            .into_iter()
            .filter_map(|d| Some((d.get_object_id("_id").ok()?, d)))
            .collect()
    };

    #[derive(Default)]
    struct UnitStats {
        subject_id: String,
        topic_id: String,
        concept_id: Option<String>,
        distinct_questions: HashSet<String>,
        attempts: usize,
        correct: usize,
        grounded_correct: usize,
        high_conf_errors: usize,
        transfer_attempts: usize,
        transfer_correct: usize,
        earliest_correct_ms: Option<i64>,
        latest_correct_ms: Option<i64>,
        last_answered_ms: i64,
        stability_sum: f64,
        stability_n: usize,
        active_secs_sum: f64,
        question_outcomes: Vec<bool>,
    }

    let mut unit_map: HashMap<String, UnitStats> = HashMap::new();
    let mut total_non_blank = 0usize;
    let mut total_correct = 0usize;
    let mut conf_recorded_attempts = 0usize;
    let mut high_conf_errors = 0usize;
    let mut fragile_corrects = 0usize;
    let mut guess_attempts = 0usize;
    let mut spaced_review_attempts = 0usize;
    let mut spaced_review_correct = 0usize;
    let mut active_secs_all = Vec::new();

    let ctv_offers: Vec<Document> = state
        .db
        .collection::<Document>("ctv_offers")
        .find(doc! {
            "user_id": user_id,
            "first_displayed_at": { "$ne": bson::Bson::Null, "$exists": true }
        })
        .await?
        .try_collect()
        .await?;

    let mut level_counts: HashMap<DeclaredConfidence, (usize, usize)> = HashMap::new();
    let mut level_tf_counts: HashMap<DeclaredConfidence, usize> = HashMap::new();
    for dc in DeclaredConfidence::ALL {
        level_counts.insert(dc, (0, 0));
        level_tf_counts.insert(dc, 0);
    }
    let mut walkforward_inputs: Vec<WalkforwardCalibrationInput> = Vec::new();
    let mut grounding_summary = GroundingEvidenceSummaryDto::default();

    for raw in &raw_activities {
        let Ok(act) = bson::from_document::<UserActivityDocument>(raw.clone()) else {
            continue;
        };
        let q_doc_opt = q_map.get(&act.question_id);
        let decoded_q = q_doc_opt
            .and_then(|d| question_compatibility::decode(d.clone()).ok());
        let q_type = decoded_q
            .as_ref()
            .map(|q| q.effective_question_type().to_string())
            .or_else(|| {
                q_doc_opt
                    .and_then(|d| d.get_str("question_type").ok())
                    .map(str::to_string)
            })
            .unwrap_or_else(|| "MULTIPLE_CHOICE".to_string());
        let is_open_ended = q_type.eq_ignore_ascii_case("OPEN_ENDED");
        let has_curated_ch = decoded_q
            .as_ref()
            .is_some_and(|q| q.evidence_challenge.is_some());
        let decoded_ch = decoded_q
            .as_ref()
            .and_then(|q| q.effective_evidence_challenge());

        let subj_id = act
            .canonical_subject_id
            .clone()
            .or_else(|| {
                q_doc_opt
                    .and_then(|d| d.get_str("canonical_subject_id").ok())
                    .map(str::to_string)
            })
            .unwrap_or_default();
        let topic_id = act
            .canonical_topic_id
            .clone()
            .or_else(|| {
                q_doc_opt
                    .and_then(|d| d.get_str("canonical_topic_id").ok())
                    .map(str::to_string)
            })
            .unwrap_or_default();
        let concept_id = q_doc_opt.and_then(|d| {
            let cls = d.get_document("classification").ok()?;
            if cls.get_str("state").ok() != Some("CONFIRMED") {
                return None;
            }
            let links = cls.get_array("links").ok()?;
            let prim: Vec<String> = links
                .iter()
                .filter_map(|v| v.as_document())
                .filter(|l| l.get_str("role").ok() == Some("PRIMARY"))
                .filter_map(|l| l.get_str("concept_id").ok().map(str::trim).filter(|s| !s.is_empty()))
                .map(str::to_string)
                .collect();
            if prim.len() == 1 {
                Some(prim[0].clone())
            } else {
                None
            }
        });
        let unit_id = concept_id
            .clone()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| topic_id.clone());

        let mut prev_ms_for_question: Option<i64> = None;
        for (sess_idx, s) in act.past_sessions.iter().enumerate() {
            if s.is_blank {
                continue;
            }
            let ts_ms = s.data.timestamp_millis();
            total_non_blank += 1;
            if s.status {
                total_correct += 1;
                grounding_summary.raw_correct_hits += 1;
            }

            let ev_compatible: Option<bool> =
                match (s.selected_evidence_id.as_deref(), decoded_ch.as_ref()) {
                    (Some(sel), Some(ch)) if !sel.trim().is_empty() => {
                        let norm_sel = crate::models::question::normalize_synthesized_evidence_id(
                            sel,
                            has_curated_ch,
                        );
                        Some(norm_sel == ch.correct_evidence_id)
                    }
                    _ => None,
                };
            let rationale_contradicted = ev_compatible == Some(false);

            let decl_conf = s.declared_confidence.or_else(|| {
                DeclaredConfidence::from_legacy_band(s.nivel_confianca, s.confidence_explicit)
            });
            if let Some(dc) = decl_conf {
                conf_recorded_attempts += 1;
                let entry = level_counts.entry(dc).or_insert((0, 0));
                entry.0 += 1;
                if s.status {
                    entry.1 += 1;
                }
                if normalize_calibration_format(&q_type) == "TRUE_FALSE" {
                    *level_tf_counts.entry(dc).or_insert(0) += 1;
                }
                if !is_open_ended {
                    let tie_id = if s.attempt_id.trim().is_empty() {
                        format!("{}:{sess_idx:04}", act.question_id.to_hex())
                    } else {
                        s.attempt_id.clone()
                    };
                    walkforward_inputs.push(WalkforwardCalibrationInput {
                        attempt_id: tie_id,
                        occurred_at_ms: ts_ms,
                        question_type: q_type.clone(),
                        declared_confidence: dc,
                        is_correct: s.status,
                    });
                }
                if !s.status && dc.is_high() {
                    high_conf_errors += 1;
                }
            } else if !s.status && s.nivel_confianca >= 3 {
                conf_recorded_attempts += 1;
                high_conf_errors += 1;
            }

            if decl_conf == Some(DeclaredConfidence::Guess)
                || s.cognitive_strategy == Some(CognitiveStrategy::RandomGuess)
            {
                guess_attempts += 1;
            }

            let (diag, _, _) = classify_preliminary_matrix(&MetacognitiveMatrixInputs {
                is_blank: false,
                is_correct: s.status,
                declared_confidence: decl_conf,
                cognitive_strategy: s.cognitive_strategy,
                consistent_history: act.quantidade_praticas >= 2 && act.percentual_acertos >= 75.0,
                rationale_contradicted,
            });

            let hit_grounding = classify_hit_grounding(
                s.status,
                decl_conf,
                s.cognitive_strategy,
                ev_compatible,
            );
            if s.status {
                match hit_grounding {
                    HitGroundingCategory::ValidConceptualGrounding => {
                        grounding_summary.evaluable_grounding_hits += 1;
                        grounding_summary.valid_conceptual_hits += 1;
                    }
                    HitGroundingCategory::ValidFunctionalStrategy => {
                        grounding_summary.evaluable_grounding_hits += 1;
                        grounding_summary.valid_functional_strategy_hits += 1;
                    }
                    HitGroundingCategory::DivergentRationale => {
                        grounding_summary.evaluable_grounding_hits += 1;
                        grounding_summary.divergent_rationale_hits += 1;
                    }
                    HitGroundingCategory::FragileOrGuess => {
                        grounding_summary.evaluable_grounding_hits += 1;
                        grounding_summary.fragile_or_guess_hits += 1;
                    }
                    HitGroundingCategory::RetrospectiveLegacyEvidence => {
                        grounding_summary.retrospective_legacy_hits += 1;
                        grounding_summary.unknown_grounding_hits += 1;
                    }
                    HitGroundingCategory::UnknownGrounding => {
                        grounding_summary.unknown_grounding_hits += 1;
                    }
                    HitGroundingCategory::NotApplicableError => {}
                }
            }

            if s.status
                && (matches!(
                    hit_grounding,
                    HitGroundingCategory::FragileOrGuess | HitGroundingCategory::DivergentRationale
                ) || matches!(
                    diag,
                    PreliminaryDiagnosis::PossibleCasualHit
                        | PreliminaryDiagnosis::InsecureKnowledge
                        | PreliminaryDiagnosis::PossibleMasteryIllusion
                ))
            {
                fragile_corrects += 1;
            }

            if let Some(prev_ms) = prev_ms_for_question {
                if ts_ms - prev_ms >= 86_400_000 || s.is_revision {
                    spaced_review_attempts += 1;
                    if s.status {
                        spaced_review_correct += 1;
                    }
                }
            }
            prev_ms_for_question = Some(ts_ms);

            let eff_secs = s.active_seconds.unwrap_or(s.tempo_gasto_segundos).max(1) as f64;
            active_secs_all.push(eff_secs);

            if !unit_id.is_empty() {
                let u = unit_map.entry(unit_id.clone()).or_default();
                if u.topic_id.is_empty() {
                    u.subject_id = subj_id.clone();
                    u.topic_id = topic_id.clone();
                    u.concept_id = concept_id.clone();
                }
                u.distinct_questions.insert(act.question_id.to_hex());
                u.attempts += 1;
                if s.status {
                    u.correct += 1;
                    // Crédito positivo de compreensão apenas com fundamento verificável (nunca por omissão!)
                    if hit_grounding.credits_comprehension() {
                        u.grounded_correct += 1;
                    }
                    u.earliest_correct_ms =
                        Some(u.earliest_correct_ms.map_or(ts_ms, |c| c.min(ts_ms)));
                    u.latest_correct_ms = Some(u.latest_correct_ms.map_or(ts_ms, |c| c.max(ts_ms)));
                } else if decl_conf.is_some_and(DeclaredConfidence::is_high) || s.nivel_confianca >= 3 {
                    u.high_conf_errors += 1;
                }
                u.last_answered_ms = u.last_answered_ms.max(ts_ms);
                u.active_secs_sum += eff_secs;
                u.question_outcomes.push(s.status);
            }
        }

        if !unit_id.is_empty() && act.memory_stability_days > 0.0 {
            if let Some(u) = unit_map.get_mut(&unit_id) {
                u.stability_sum += act.memory_stability_days;
                u.stability_n += 1;
            }
        }
    }

    grounding_summary.coverage_sentence_pt = if grounding_summary.raw_correct_hits == 0 {
        "Ainda não há acertos registrados para avaliação de fundamento.".to_string()
    } else {
        format!(
            "Dos {} acertos brutos, {} têm fundamento avaliável ({} sem estratégia ou justificativa informada).",
            grounding_summary.raw_correct_hits,
            grounding_summary.evaluable_grounding_hits,
            grounding_summary.unknown_grounding_hits
        )
    };

    let mut independent_transfer_attempts = 0usize;
    let mut independent_transfer_correct = 0usize;
    let mut diff_resp = 0usize;
    let mut diff_indep_elig = 0usize;
    let mut diff_indep_ok = 0usize;
    let mut per_resp = 0usize;
    let mut per_indep_elig = 0usize;
    let mut per_indep_ok = 0usize;
    let mut unknown_condition_ctv = 0usize;

    for ctv in &ctv_attempts {
        let has_known_timing = ctv.contains_key("verification_timing")
            && ctv.get_str("verification_timing").ok() != Some("UNKNOWN_CONDITION");
        let code = ctv.get_str("classification_code").unwrap_or("");
        if !has_known_timing || code == "UNKNOWN_CONDITION" {
            unknown_condition_ctv += 1;
        }
        let vmode = ctv
            .get_str("verification_mode")
            .unwrap_or("CONCEPTUAL_TRANSFER");
        let is_independent_unseen = if ctv.contains_key("counts_as_independent_transfer") {
            ctv.get_bool("counts_as_independent_transfer")
                .unwrap_or(false)
                && vmode == "CONCEPTUAL_TRANSFER"
        } else {
            has_known_timing
                && ctv.get_str("verification_timing").ok() == Some("PRE_FEEDBACK")
                && !ctv.get_bool("was_previously_exposed").unwrap_or(false)
                && vmode == "CONCEPTUAL_TRANSFER"
        };
        let is_ok = ctv.get_bool("is_correct").unwrap_or(false);

        let cohort = ctv
            .get_str("trigger_cohort")
            .ok()
            .unwrap_or_else(|| trigger_cohort_for_reason(ctv.get_str("trigger_reason").ok()));
        if cohort == "PERIODIC_SAMPLING" {
            per_resp += 1;
            if is_independent_unseen {
                per_indep_elig += 1;
                if is_ok {
                    per_indep_ok += 1;
                }
            }
        } else {
            diff_resp += 1;
            if is_independent_unseen {
                diff_indep_elig += 1;
                if is_ok {
                    diff_indep_ok += 1;
                }
            }
        }

        if is_independent_unseen {
            independent_transfer_attempts += 1;
            if is_ok {
                independent_transfer_correct += 1;
            }
        }
        // Em prática do tópico (ex.: convexidade em A01), jamais concede transferência nem domínio ao conceito-alvo original!
        if vmode == "CONCEPTUAL_TRANSFER" && is_independent_unseen {
            let unit_id = ctv
                .get_str("target_concept_id")
                .ok()
                .filter(|s| !s.is_empty())
                .or_else(|| ctv.get_str("concept_id").ok().filter(|s| !s.is_empty()))
                .or_else(|| ctv.get_str("canonical_topic_id").ok())
                .unwrap_or("");
            if !unit_id.is_empty() {
                let u = unit_map.entry(unit_id.to_string()).or_default();
                if u.topic_id.is_empty() {
                    u.subject_id = ctv
                        .get_str("canonical_subject_id")
                        .unwrap_or("")
                        .to_string();
                    u.topic_id = ctv.get_str("canonical_topic_id").unwrap_or("").to_string();
                    u.concept_id = ctv
                        .get_str("target_concept_id")
                        .or_else(|_| ctv.get_str("concept_id"))
                        .ok()
                        .filter(|s| !s.is_empty())
                        .map(str::to_string);
                }
                if let Ok(cq) = ctv.get_str("complementary_question_id") {
                    u.distinct_questions.insert(cq.to_string());
                }
                u.transfer_attempts += 1;
                if is_ok {
                    u.transfer_correct += 1;
                }
            }
        }
    }

    let mut diff_offers = 0usize;
    let mut diff_dismissals = 0usize;
    let mut per_offers = 0usize;
    let mut per_dismissals = 0usize;
    for off in &ctv_offers {
        let cohort = off
            .get_str("trigger_cohort")
            .ok()
            .unwrap_or_else(|| trigger_cohort_for_reason(off.get_str("trigger_reason").ok()));
        let is_dism = off.get_str("state").ok() == Some("DISMISSED");
        if cohort == "PERIODIC_SAMPLING" {
            per_offers += 1;
            if is_dism {
                per_dismissals += 1;
            }
        } else {
            diff_offers += 1;
            if is_dism {
                diff_dismissals += 1;
            }
        }
    }
    diff_offers = diff_offers.max(diff_resp + diff_dismissals);
    per_offers = per_offers.max(per_resp + per_dismissals);

    let ctv_adaptive_selection = CtvAdaptiveSelectionReportDto {
        difficulty_triggered: build_adaptive_cohort_dto(
            "DIFFICULTY_DETECTED",
            "Disparadas por dificuldade detectada",
            "Tentativas com chute, baixa confiança, erro de alta confiança, reconhecimento superficial ou inconsistência",
            diff_offers,
            diff_dismissals,
            diff_resp,
            diff_indep_elig,
            diff_indep_ok,
        ),
        periodic_sampling: build_adaptive_cohort_dto(
            "PERIODIC_SAMPLING",
            "Sorteadas para acompanhamento periódico",
            "Amostra periódica restrita a unidades elegíveis com histórico consistente ou acertos sem fragilidade",
            per_offers,
            per_dismissals,
            per_resp,
            per_indep_elig,
            per_indep_ok,
        ),
        unknown_condition_count: unknown_condition_ctv,
    };

    // Add unpracticed units from active exam so NOT_EVALUATED is tracked accurately
    for (subj_id, top_id) in &target_topics {
        if !unit_map.values().any(|u| &u.topic_id == top_id) {
            unit_map.insert(
                top_id.clone(),
                UnitStats {
                    subject_id: subj_id.clone(),
                    topic_id: top_id.clone(),
                    concept_id: None,
                    ..Default::default()
                },
            );
        }
    }

    let mut summary_counts = ConceptMasterySummaryDto::default();
    let mut unit_mastery = Vec::with_capacity(unit_map.len());
    let mut evaluated_units_count = 0usize;
    let mut mastered_units_count = 0usize;
    let mut sufficient_evidence_units = 0usize;

    for (unit_id, u) in unit_map {
        let dq = u.distinct_questions.len();
        let acc = if u.attempts > 0 {
            (u.correct as f64) / (u.attempts as f64)
        } else {
            0.0
        };
        let grounded_rate = if u.attempts > 0 {
            (u.grounded_correct as f64) / (u.attempts as f64)
        } else {
            0.0
        };
        let hce_rate = if u.attempts > 0 {
            (u.high_conf_errors as f64) / (u.attempts as f64)
        } else {
            0.0
        };
        let t_rate = if u.transfer_attempts > 0 {
            (u.transfer_correct as f64) / (u.transfer_attempts as f64)
        } else {
            0.0
        };
        let has_spaced = matches!(
            (u.earliest_correct_ms, u.latest_correct_ms),
            (Some(a), Some(b)) if b - a >= 86_400_000
        );
        let r = if u.attempts == 0 {
            0.0
        } else {
            let elapsed_days = ((now_ms - u.last_answered_ms).max(0) as f64) / 86_400_000.0;
            let stab = if u.stability_n > 0 {
                u.stability_sum / (u.stability_n as f64)
            } else if acc >= 0.7 {
                3.0
            } else {
                1.0
            };
            retrievability(elapsed_days, stab)
        };
        // Consistência intraconceitual: penaliza oscilações frequentes acerto/erro
        let consistency = if u.question_outcomes.len() <= 1 {
            acc
        } else {
            let transitions = u
                .question_outcomes
                .windows(2)
                .filter(|w| w[0] != w[1])
                .count();
            let stability_seq =
                1.0 - (transitions as f64) / ((u.question_outcomes.len() - 1) as f64);
            (0.6 * acc + 0.4 * stability_seq).clamp(0.0, 1.0)
        };
        let avg_secs = (u.attempts > 0).then(|| u.active_secs_sum / (u.attempts as f64));

        let eval = classify_concept_mastery(&ConceptMasteryInputs {
            attempts: u.attempts,
            distinct_questions: dq,
            accuracy: acc,
            grounded_rate,
            high_conf_error_rate: hce_rate,
            transfer_attempts: u.transfer_attempts,
            transfer_success_rate: t_rate,
            has_spaced_retention: has_spaced,
            retrievability: r,
            consistency,
            avg_active_seconds: avg_secs,
            reference_seconds: 50.0,
        });

        match eval.state {
            ConceptMasteryState::NotEvaluated => summary_counts.not_evaluated += 1,
            ConceptMasteryState::InsufficientEvidence => {
                evaluated_units_count += 1;
                summary_counts.insufficient_evidence += 1;
            }
            ConceptMasteryState::EmergingKnowledge => {
                evaluated_units_count += 1;
                sufficient_evidence_units += 1;
                summary_counts.emerging_knowledge += 1;
            }
            ConceptMasteryState::UnstableKnowledge => {
                evaluated_units_count += 1;
                sufficient_evidence_units += 1;
                summary_counts.unstable_knowledge += 1;
            }
            ConceptMasteryState::DevelopingMastery => {
                evaluated_units_count += 1;
                sufficient_evidence_units += 1;
                summary_counts.developing_mastery += 1;
            }
            ConceptMasteryState::DemonstratedMastery => {
                evaluated_units_count += 1;
                sufficient_evidence_units += 1;
                mastered_units_count += 1;
                summary_counts.demonstrated_mastery += 1;
            }
            ConceptMasteryState::ConsolidatedMastery => {
                evaluated_units_count += 1;
                sufficient_evidence_units += 1;
                mastered_units_count += 1;
                summary_counts.consolidated_mastery += 1;
            }
            ConceptMasteryState::MasteryAtRisk => {
                evaluated_units_count += 1;
                sufficient_evidence_units += 1;
                summary_counts.mastery_at_risk += 1;
            }
        }

        unit_mastery.push(UnitConceptMasteryDto {
            unit_id,
            canonical_subject_id: u.subject_id,
            canonical_topic_id: u.topic_id,
            concept_id: u.concept_id,
            attempts: u.attempts,
            distinct_questions: dq,
            transfer_attempts: u.transfer_attempts,
            evaluation: eval,
        });
    }
    unit_mastery.sort_by(|a, b| a.unit_id.cmp(&b.unit_id));

    let wf_report = evaluate_walkforward_calibration(&walkforward_inputs);
    let empirical_brier_score = wf_report.overall_brier_score;

    let mut confidence_calibration = Vec::with_capacity(5);
    for dc in DeclaredConfidence::ALL {
        let (att, corr) = level_counts.get(&dc).copied().unwrap_or((0, 0));
        let tf_n = level_tf_counts.get(&dc).copied().unwrap_or(0);
        // Usa o formato efetivamente predominante na faixa (ou ponderação exata entre formatos), nunca fixando MULTIPLE_CHOICE para Certo/Errado!
        let (tf_mean, tf_sd) = calibrated_level_posterior("TRUE_FALSE", dc, corr as u32, att as u32);
        let (mc_mean, mc_sd) =
            calibrated_level_posterior("MULTIPLE_CHOICE", dc, corr as u32, att as u32);
        let (post_mean, post_sd) = if att == 0 {
            (mc_mean, mc_sd)
        } else {
            let w_tf = (tf_n as f64) / (att as f64);
            let w_mc = 1.0 - w_tf;
            (w_tf * tf_mean + w_mc * mc_mean, w_tf * tf_sd + w_mc * mc_sd)
        };
        confidence_calibration.push(ConfidenceLevelCalibrationDto {
            declared_confidence: dc,
            label_pt: dc.label_pt(),
            attempts: att,
            correct: corr,
            observed_accuracy_pct: ratio_pct(corr as f64, att as f64),
            calibrated_posterior_mean: (post_mean * 10000.0).round() / 10000.0,
            credible_sd: (post_sd * 10000.0).round() / 10000.0,
            sufficiency_status: sufficiency_status_for_sample(att, 5),
            coverage_sentence_pt: format_confidence_coverage_sentence(dc, None, corr, att),
        });
    }

    let avg_active_s = if !active_secs_all.is_empty() {
        Some(active_secs_all.iter().sum::<f64>() / (active_secs_all.len() as f64))
    } else {
        None
    };
    let fluency_pct =
        crate::services::concept_mastery_engine::compute_fluency_score(avg_active_s, 50.0)
            .map(|f| (f * 10000.0).round() / 100.0);

    let total_tracked_units = unit_mastery.len();
    let learning_kpis = vec![
        FormalKpiDto {
            kpi_id: "ACCURACY_RATE",
            label_pt: "Taxa de acerto",
            value: ratio_pct(total_correct as f64, total_non_blank as f64),
            unit: "%",
            numerator: total_correct as f64,
            denominator: total_non_blank as f64,
            sample_size_n: total_non_blank,
            formal_definition: "Proporção de tentativas respondidas (não em branco) com gabarito oficial correto.",
            eligibility_criteria: "Tentativas originais sincronizadas com alternativa efetivamente marcada.",
            missing_data_rule: "Omissões em branco são excluídas do denominador; retorna nulo quando n = 0.",
            granularity: "Global / Disciplina / Conceito",
            reference_period: "Histórico acumulado da conta",
            interpretive_limitation: "Acerto bruto isolado não distingue conhecimento consolidado de acerto casual ou sem fundamento observável.",
        },
        FormalKpiDto {
            kpi_id: "CONCEPTUAL_MASTERY_RATE",
            label_pt: "Domínio conceitual",
            value: ratio_pct(mastered_units_count as f64, evaluated_units_count as f64),
            unit: "%",
            numerator: mastered_units_count as f64,
            denominator: evaluated_units_count as f64,
            sample_size_n: evaluated_units_count,
            formal_definition: "Fração das unidades avaliadas que atingiram Domínio Demonstrado ou Domínio Consolidado em múltiplas dimensões.",
            eligibility_criteria: "Unidades canônicas com ao menos uma tentativa registrada.",
            missing_data_rule: "Unidades não avaliadas permanecem fora do denominador; retorna nulo quando nenhuma unidade foi praticada.",
            granularity: "Conceito / Subassunto",
            reference_period: "Estado longitudinal vigente",
            interpretive_limitation: "Exige evidência positiva combinada de desempenho, compreensão verificável e transferência independente.",
        },
        FormalKpiDto {
            kpi_id: "HIGH_CONFIDENCE_ERROR_RATE",
            label_pt: "Erro com alta confiança",
            value: ratio_pct(high_conf_errors as f64, conf_recorded_attempts as f64),
            unit: "%",
            numerator: high_conf_errors as f64,
            denominator: conf_recorded_attempts as f64,
            sample_size_n: conf_recorded_attempts,
            formal_definition: "Proporção de tentativas com confiança declarada registrada em que o aluno declarou confiança alta/muito alta e errou.",
            eligibility_criteria: "Tentativas respondidas com sinal de confiança explícito ou banda alta registrada.",
            missing_data_rule: "Tentativas sem registro de confiança não entram no denominador.",
            granularity: "Tentativa / Conceito",
            reference_period: "Histórico acumulado da conta",
            interpretive_limitation: "Sensível a pequenas amostras iniciais; requer recorrência para caracterizar equívoco conceitual estável.",
        },
        FormalKpiDto {
            kpi_id: "FRAGILE_CORRECT_RATE",
            label_pt: "Acerto frágil",
            value: ratio_pct(fragile_corrects as f64, total_correct as f64),
            unit: "%",
            numerator: fragile_corrects as f64,
            denominator: total_correct as f64,
            sample_size_n: total_correct,
            formal_definition: "Proporção dos acertos totais acompanhados de chute declarado, baixa confiança, reconhecimento superficial ou divergência de fundamento.",
            eligibility_criteria: "Tentativas originais corretas.",
            missing_data_rule: "Acertos sem estratégia nem evidência permanecem separados como fundamento desconhecido.",
            granularity: "Tentativa / Conceito",
            reference_period: "Histórico acumulado da conta",
            interpretive_limitation: "Depende da cobertura de declarações de estratégia e justificativa pós-resposta.",
        },
        FormalKpiDto {
            kpi_id: "GUESSING_RATE",
            label_pt: "Taxa de chute",
            value: ratio_pct(guess_attempts as f64, total_non_blank as f64),
            unit: "%",
            numerator: guess_attempts as f64,
            denominator: total_non_blank as f64,
            sample_size_n: total_non_blank,
            formal_definition: "Proporção de tentativas respondidas em que o aluno declarou confiança 'Chutei' ou estratégia 'Escolhi aleatoriamente'.",
            eligibility_criteria: "Tentativas originais não em branco.",
            missing_data_rule: "Ausência de declaração não é presumida como chute.",
            granularity: "Tentativa / Sessão",
            reference_period: "Histórico acumulado da conta",
            interpretive_limitation: "Em provas sem penalidade, o chute estratégico ao final da prova pode ser racional.",
        },
        FormalKpiDto {
            kpi_id: "TRANSFER_SUCCESS_RATE",
            label_pt: "Taxa de transferência",
            value: ratio_pct(
                independent_transfer_correct as f64,
                independent_transfer_attempts as f64,
            ),
            unit: "%",
            numerator: independent_transfer_correct as f64,
            denominator: independent_transfer_attempts as f64,
            sample_size_n: independent_transfer_attempts,
            formal_definition: "Proporção de verificações conceituais independentes (alinhadas ao conceito/regra, inéditas e pré-feedback) respondidas corretamente.",
            eligibility_criteria: "Tentativas complementares CTV com counts_as_independent_transfer == true.",
            missing_data_rule: "Dispensas, pré-carregamentos, práticas de tópico, revisões e fixações pós-instrução ficam fora do denominador.",
            granularity: "Conceito / Subassunto",
            reference_period: "Histórico acumulado de verificações CTV",
            interpretive_limitation: "Retorna nulo até que pelo menos uma tentativa independente elegível seja respondida.",
        },
        FormalKpiDto {
            kpi_id: "RETENTION_RATE",
            label_pt: "Taxa de retenção",
            value: ratio_pct(spaced_review_correct as f64, spaced_review_attempts as f64),
            unit: "%",
            numerator: spaced_review_correct as f64,
            denominator: spaced_review_attempts as f64,
            sample_size_n: spaced_review_attempts,
            formal_definition: "Proporção de revisões espaçadas (>= 24h após exposição anterior ou intervalo SRS vencido) concluídas com acerto.",
            eligibility_criteria: "Tentativas não em branco realizadas em janela de revisão espaçada.",
            missing_data_rule: "Primeiras exposições e repetições no mesmo dia são excluídas.",
            granularity: "Questão / Conceito",
            reference_period: "Histórico longitudinal de revisões",
            interpretive_limitation: "Repetir exatamente a mesma questão mede também memória episódica do item; combinar com CTV.",
        },
        FormalKpiDto {
            kpi_id: "METACOGNITIVE_CALIBRATION",
            label_pt: "Erro quadrático de calibração (Brier)",
            value: empirical_brier_score,
            unit: "Brier",
            numerator: empirical_brier_score.unwrap_or(0.0) * (wf_report.eligible_attempts_count as f64),
            denominator: wf_report.eligible_attempts_count as f64,
            sample_size_n: wf_report.eligible_attempts_count,
            formal_definition: "Média walk-forward de (p - y)^2 em que p é a probabilidade prevista apenas com o histórico anterior do aluno no mesmo formato e faixa declarada.",
            eligibility_criteria: "Tentativas objetivas respondidas com confiança declarada explícita.",
            missing_data_rule: "Retorna nulo quando n = 0; não deve ser interpretado como percentual de domínio (quanto menor, mais calibrada a previsão).",
            granularity: "Global e separado por formato (Certo/Errado vs. Múltipla Escolha)",
            reference_period: "Sequência temporal ordenada por momento da tentativa",
            interpretive_limitation: "As primeiras tentativas de cada célula usam a política inicial Beta-Binomial (cold-start identificado).",
        },
        FormalKpiDto {
            kpi_id: "COGNITIVE_FLUENCY",
            label_pt: "Fluência cognitiva",
            value: fluency_pct,
            unit: "pts",
            numerator: fluency_pct.unwrap_or(0.0),
            denominator: if fluency_pct.is_some() { 100.0 } else { 0.0 },
            sample_size_n: active_secs_all.len(),
            formal_definition: "Índice normalizado (0 a 100) de eficiência do tempo ativo de resolução em relação ao tempo de referência (50s), descontando respostas impulsivas (< 3s).",
            eligibility_criteria: "Tentativas respondidas com tempo registrado.",
            missing_data_rule: "Usa tempo total da questão apenas quando tempo ativo em primeiro plano não está disponível em registros legados.",
            granularity: "Global / Conceito",
            reference_period: "Histórico acumulado da conta",
            interpretive_limitation: "Questões com textos longos exigem mais tempo de leitura sem indicar baixa fluência conceitual.",
        },
        FormalKpiDto {
            kpi_id: "EVIDENCE_COVERAGE",
            label_pt: "Cobertura de evidência",
            value: ratio_pct(sufficient_evidence_units as f64, total_tracked_units as f64),
            unit: "%",
            numerator: sufficient_evidence_units as f64,
            denominator: total_tracked_units as f64,
            sample_size_n: total_tracked_units,
            formal_definition: "Fração das unidades da grade ativa (e tópicos praticados) que já acumularam evidência mínima classificável (>= 3 questões distintas).",
            eligibility_criteria: "Unidades da grade ativa do estudante somadas às unidades já praticadas.",
            missing_data_rule: "Retorna nulo quando não há grade ativa nem unidades praticadas.",
            granularity: "Grade do Concurso",
            reference_period: "Estado atual da preparação",
            interpretive_limitation: "Cobertura de evidência indica amplitude diagnóstica, não aprovação ou domínio do edital.",
        },
    ];

    Ok(StudentLearningAnalyticsResponse {
        generated_at: now.to_rfc3339(),
        calibration_policy_version: CALIBRATION_POLICY_VERSION,
        learning_kpis,
        empirical_brier_score,
        true_false_brier_score: wf_report.true_false_brier_score,
        multiple_choice_brier_score: wf_report.multiple_choice_brier_score,
        cold_start_predictions_count: wf_report.cold_start_predictions_count,
        confidence_calibration,
        confidence_calibration_by_format: wf_report.cells_by_format,
        grounding_summary,
        ctv_adaptive_selection,
        concept_mastery_summary: summary_counts,
        unit_mastery,
    })
}

pub async fn compute_admin_ctv_metrics(
    state: &AppState,
    ref_cost_override: Option<f64>,
) -> Result<AdminAiCtvMetricsResponse, AppError> {
    compute_admin_ctv_metrics_with_window(
        state,
        AdminCtvMetricsQuery {
            reference_cost_usd: ref_cost_override,
            from: None,
            to: None,
        },
    )
    .await
}
