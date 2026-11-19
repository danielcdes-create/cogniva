use crate::{
    error::AppError,
    models::{activity::UserActivityDocument, exam_hierarchy::*, question::QuestionDto},
    security::SessionUser,
    services::{
        calibration_engine::{calibrated_probability, CalibrationCellDto},
        diagnostic_engine::{classify_diagnostic, DiagnosticInputs, DiagnosticStatus},
        exam_engine::{days_until, study_today},
        exam_store,
        fatigue_engine::{evaluate_from_activities, FatigueReport},
        knowledge,
        prioritization_engine::{priority_utility, PrioritizationInputs},
        quiz_selector::{QuizGenerateRequest, QuizMode},
        retention_engine::retrievability,
        selection_v2,
        strategy_engine::{evaluate_strategy, StrategyDecision},
    },
    AppState,
};
use axum::{
    extract::{Query, State},
    Extension, Json,
};
use bson::{doc, Document};
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
};

#[derive(Debug, Deserialize)]
pub struct NextActionQuery {
    pub contest_id: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ExamGapSummaryDto {
    pub exam_id: Option<String>,
    pub exam_name: Option<String>,
    pub days_until_exam: Option<i64>,
    pub verified_units: usize,
    pub consolidating_units: usize,
    pub insufficient_evidence_units: usize,
    pub total_units: usize,
    pub wrong_answer_penalty: f64,
    pub historical_cutoff_score: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct StrategyBandGuidanceDto {
    pub confidence_band: i32,
    pub calibrated_mean: f64,
    pub decision: StrategyDecision,
}

#[derive(Debug, Clone, Serialize)]
pub struct NextActionResponse {
    pub entry_question: Option<QuestionDto>,
    pub entry_reason: String,
    pub priority_topic_id: Option<String>,
    pub exam_gap_summary: ExamGapSummaryDto,
    pub calibration_cells: Vec<CalibrationCellDto>,
    pub strategy_guidance: Vec<StrategyBandGuidanceDto>,
    pub fatigue_report: FatigueReport,
}

pub async fn get_next_action(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Query(query): Query<NextActionQuery>,
) -> Result<Json<NextActionResponse>, AppError> {
    let exams = exam_store::list(&state, user.user_id).await?;
    let active_exam = query
        .contest_id
        .as_deref()
        .and_then(|cid| exams.iter().find(|e| e.id == cid))
        .or_else(|| {
            exams
                .iter()
                .filter(|e| {
                    e.is_active_in_ladder
                        && !matches!(
                            e.status,
                            ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido
                        )
                })
                .min_by_key(|e| e.ladder_priority)
        });

    let raw_rows: Vec<Document> = state
        .db
        .collection::<Document>("userActivityLog")
        .find(doc! { "user_id": user.user_id })
        .await?
        .try_collect()
        .await?;

    let mut typed_activities = Vec::with_capacity(raw_rows.len());
    for raw in &raw_rows {
        if let Ok(parsed) = bson::from_document::<UserActivityDocument>(raw.clone()) {
            typed_activities.push(parsed);
        }
    }

    let qids: Vec<_> = raw_rows
        .iter()
        .filter_map(|r| r.get_object_id("question_id").ok())
        .collect();
    let q_docs = knowledge::questions(&state.db, user.user_id, qids).await?;

    // Consolida diagnóstico por unidade (`concept_id ?? topic_id`)
    struct UnitAgg {
        distinct_qids: HashSet<String>,
        non_blank_attempts: usize,
        correct_attempts: usize,
        last_is_correct: bool,
        last_confidence: i32,
        had_prior_verified_correct: bool,
        has_spaced_retention: bool,
        evidence_challenge_passed: Option<bool>,
        last_answered_ms: i64,
        avg_seconds_sum: f64,
        obs_count: usize,
        max_stability: f64,
    }

    let stability_by_qid: HashMap<String, f64> = typed_activities
        .iter()
        .map(|a| (a.question_id.to_hex(), a.memory_stability_days))
        .collect();

    let mut unit_map: HashMap<String, UnitAgg> = HashMap::new();
    for raw in &raw_rows {
        let Some(qid) = raw.get_object_id("question_id").ok() else {
            continue;
        };
        let Some(qdoc) = q_docs.get(&qid) else {
            continue;
        };
        let stab = stability_by_qid
            .get(&qid.to_hex())
            .copied()
            .unwrap_or(0.0);
        for obs in knowledge::project_units(raw, qdoc) {
            let entry = unit_map.entry(obs.unit_id.clone()).or_insert_with(|| UnitAgg {
                distinct_qids: HashSet::new(),
                non_blank_attempts: 0,
                correct_attempts: 0,
                last_is_correct: obs.last_is_correct,
                last_confidence: obs.last_confidence,
                had_prior_verified_correct: false,
                has_spaced_retention: false,
                evidence_challenge_passed: None,
                last_answered_ms: obs.last_answered_ms,
                avg_seconds_sum: 0.0,
                obs_count: 0,
                max_stability: 0.0,
            });
            entry.distinct_qids.insert(obs.question_id);
            entry.non_blank_attempts += obs.non_blank_attempts;
            entry.correct_attempts += obs.correct_attempts;
            entry.had_prior_verified_correct |= obs.had_prior_verified_correct;
            entry.has_spaced_retention |= obs.has_spaced_retention;
            entry.avg_seconds_sum += obs.avg_seconds;
            entry.obs_count += 1;
            entry.max_stability = entry.max_stability.max(stab);
            if obs.last_answered_ms >= entry.last_answered_ms {
                entry.last_answered_ms = obs.last_answered_ms;
                entry.last_is_correct = obs.last_is_correct;
                entry.last_confidence = obs.last_confidence;
                entry.evidence_challenge_passed = obs.evidence_challenge_passed;
            }
        }
    }

    let now_ms = chrono::Utc::now().timestamp_millis();
    let mut diag_by_unit: HashMap<String, (DiagnosticStatus, f64, usize, f64)> = HashMap::new();
    for (uid, agg) in &unit_map {
        let status = classify_diagnostic(&DiagnosticInputs {
            distinct_questions: agg.distinct_qids.len(),
            non_blank_attempts: agg.non_blank_attempts,
            correct_attempts: agg.correct_attempts,
            last_is_correct: agg.last_is_correct,
            last_confidence: agg.last_confidence,
            had_prior_verified_correct: agg.had_prior_verified_correct,
            has_spaced_retention: agg.has_spaced_retention,
            evidence_challenge_passed: agg.evidence_challenge_passed,
        });
        let elapsed_days = ((now_ms - agg.last_answered_ms).max(0) as f64) / 86_400_000.0;
        let r = retrievability(elapsed_days, agg.max_stability);
        let avg_s = if agg.obs_count > 0 {
            agg.avg_seconds_sum / (agg.obs_count as f64)
        } else {
            60.0
        };
        diag_by_unit.insert(uid.clone(), (status, r, agg.distinct_qids.len(), avg_s));
    }

    let mut verified_units = 0usize;
    let mut consolidating_units = 0usize;
    let mut insufficient_units = 0usize;
    let mut best_topic: Option<(f64, String, String)> = None;

    if let Some(exam) = active_exam {
        let total_weight: f64 = exam
            .subjects
            .iter()
            .filter(|s| s.weight > 0.0)
            .map(|s| s.weight)
            .sum::<f64>()
            .max(1.0);
        for subj in &exam.subjects {
            let norm_w = (subj.weight / total_weight).max(0.05);
            for top in subj
                .topics
                .iter()
                .filter(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
            {
                let (st, r, dq, avg_s) = diag_by_unit
                    .get(&top.canonical_topic_id)
                    .copied()
                    .unwrap_or((DiagnosticStatus::InsufficientEvidence, 0.0, 0, 60.0));
                match st {
                    DiagnosticStatus::VerifiedCompetence => verified_units += 1,
                    DiagnosticStatus::InsufficientEvidence if dq == 0 => insufficient_units += 1,
                    _ => consolidating_units += 1,
                }
                let u = priority_utility(&PrioritizationInputs {
                    edital_weight: norm_w,
                    incidence_weight: 1.0,
                    retrievability: r,
                    verified_competence: st == DiagnosticStatus::VerifiedCompetence,
                    distinct_questions: dq,
                    avg_seconds: avg_s,
                    unmet_prerequisites_count: 0,
                    dependent_count: 0,
                });
                let reason = match st {
                    DiagnosticStatus::OverconfidenceError => format!(
                        "Excesso de confiança recente em {} — revisar fundamento",
                        top.display_title
                    ),
                    DiagnosticStatus::ConceptualError => format!(
                        "Lacuna conceitual prioritária em {}",
                        top.display_title
                    ),
                    DiagnosticStatus::RetrievalFailure => format!(
                        "Retenção pendente em {} (recuperar após intervalo)",
                        top.display_title
                    ),
                    DiagnosticStatus::VerifiedCompetence => format!(
                        "Manutenção espaçada em {}",
                        top.display_title
                    ),
                    DiagnosticStatus::InsufficientEvidence => format!(
                        "Tópico relevante do edital com evidência pendente: {}",
                        top.display_title
                    ),
                };
                if best_topic.as_ref().is_none_or(|(best_u, _, _)| u > *best_u) {
                    best_topic = Some((u, top.canonical_topic_id.clone(), reason));
                }
            }
        }
    }

    let priority_topic_id = best_topic.as_ref().map(|(_, tid, _)| tid.clone());
    let mut entry_reason = best_topic
        .map(|(_, _, r)| r)
        .unwrap_or_else(|| "Prioridade sugerida: conceito relevante com retenção pendente.".into());

    let mut entry_questions = if let Some(tid) = &priority_topic_id {
        selection_v2::generate(
            &state,
            &QuizGenerateRequest {
                component_id: None,
                trunk_focus_slugs: Vec::new(),
                user_id: user.user_id,
                mode: QuizMode::AdaptiveLoop,
                quantity: 1,
                contest_id: active_exam.map(|e| e.id.clone()),
                block_name: None,
                disciplina: None,
                source: None,
                syllabus_topic_id: Some(tid.clone()),
                single_question_id: None,
                keyword_query: None,
            },
        )
        .await
        .unwrap_or_default()
    } else {
        Vec::new()
    };

    if entry_questions.is_empty() {
        entry_questions = selection_v2::generate(
            &state,
            &QuizGenerateRequest {
                component_id: None,
                trunk_focus_slugs: Vec::new(),
                user_id: user.user_id,
                mode: QuizMode::AdaptiveLoop,
                quantity: 1,
                contest_id: None,
                block_name: None,
                disciplina: None,
                source: None,
                syllabus_topic_id: None,
                single_question_id: None,
                keyword_query: None,
            },
        )
        .await
        .unwrap_or_default();
        if let Some(first) = entry_questions.first() {
            entry_reason = format!(
                "Próxima ação de alto impacto em {} — você pode encerrar após responder.",
                first.disciplina
            );
        }
    }

    // Células Beta-Binomial por modalidade e faixa de confiança
    let mut cell_counts: HashMap<(&'static str, i32), (u32, u32)> = HashMap::new();
    for act in &typed_activities {
        let qt = q_docs
            .get(&act.question_id)
            .and_then(|d| d.get_str("question_type").ok())
            .unwrap_or("MULTIPLE_CHOICE");
        let qt_norm = if qt.eq_ignore_ascii_case("TRUE_FALSE") {
            "TRUE_FALSE"
        } else {
            "MULTIPLE_CHOICE"
        };
        for s in &act.past_sessions {
            if s.is_blank {
                continue;
            }
            let band = s.nivel_confianca.clamp(1, 3);
            let entry = cell_counts.entry((qt_norm, band)).or_insert((0, 0));
            if s.status {
                entry.0 += 1;
            }
            entry.1 += 1;
        }
    }

    let mut calibration_cells = Vec::new();
    for qt in ["TRUE_FALSE", "MULTIPLE_CHOICE"] {
        for band in [1, 2, 3] {
            let (corr, att) = cell_counts.get(&(qt, band)).copied().unwrap_or((0, 0));
            calibration_cells.push(calibrated_probability(qt, band, corr, att));
        }
    }

    let penalty = active_exam.map(|e| e.wrong_answer_penalty).unwrap_or(0.0);
    let cutoff = active_exam
        .map(|e| e.historical_cutoff_score)
        .unwrap_or(0.0);
    let primary_qt = if penalty > 0.0 {
        "TRUE_FALSE"
    } else {
        "MULTIPLE_CHOICE"
    };
    let strategy_guidance: Vec<StrategyBandGuidanceDto> = [1, 2, 3]
        .into_iter()
        .map(|band| {
            let cell = calibration_cells
                .iter()
                .find(|c| c.question_type == primary_qt && c.confidence_band == band)
                .unwrap();
            StrategyBandGuidanceDto {
                confidence_band: band,
                calibrated_mean: cell.calibrated_mean,
                decision: evaluate_strategy(cell.calibrated_mean, penalty, cutoff, 0.0, 1.0),
            }
        })
        .collect();

    let total_units = verified_units + consolidating_units + insufficient_units;
    let days_until_exam = active_exam.and_then(|e| match &e.target_date {
        ExamDateTarget::ExactDate { iso_date } => days_until(iso_date, study_today()),
        _ => None,
    });

    Ok(Json(NextActionResponse {
        entry_question: entry_questions.into_iter().next(),
        entry_reason,
        priority_topic_id,
        exam_gap_summary: ExamGapSummaryDto {
            exam_id: active_exam.map(|e| e.id.clone()),
            exam_name: active_exam.map(|e| e.short_name.clone()),
            days_until_exam,
            verified_units,
            consolidating_units,
            insufficient_evidence_units: insufficient_units,
            total_units,
            wrong_answer_penalty: penalty,
            historical_cutoff_score: cutoff,
        },
        calibration_cells,
        strategy_guidance,
        fatigue_report: evaluate_from_activities(&typed_activities),
    }))
}
