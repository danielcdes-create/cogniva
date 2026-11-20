use crate::{
    error::AppError,
    models::{activity::UserActivityDocument, exam_hierarchy::*, question::QuestionDto},
    security::SessionUser,
    services::{
        calibration_engine::{calibrated_probability, CalibrationCellDto},
        diagnostic_engine::{
            classify_diagnostic, evaluate_transfer_status, DiagnosticInputs, DiagnosticStatus,
            TransferInputs, TransferStatus, MIN_QUESTIONS_FOR_MASTERY,
        },
        exam_engine::{days_until, study_today},
        exam_store,
        fatigue_engine::{evaluate_from_activities, FatigueReport},
        knowledge,
        prioritization_engine::{
            format_entry_60s_header, priority_utility, PrioritizationInputs,
        },
        quiz_selector::{QuizGenerateRequest, QuizMode},
        retention_engine::retrievability,
        selection_v2,
        strategy_engine::{
            build_exam_moment_scenarios, evaluate_strategy, ExamMomentScenarioDto, StrategyDecision,
        },
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
    pub min_questions_for_mastery: usize,
    pub evidence_in_progress_units: usize,
    pub active_error_units: usize,
    pub transfer_verified_units: usize,
    pub transfer_available_units: usize,
    pub transfer_unmeasured_units: usize,
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct ThreeSignalSummaryDto {
    pub grounded_hits: u32,
    pub illusory_competence_hits: u32,
    pub hesitant_hits: u32,
    pub overconfidence_errors: u32,
    pub conceptual_errors: u32,
    pub retrieval_failures: u32,
    pub strategic_omissions: u32,
    pub omission_net_points_saved: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct StrategyBandGuidanceDto {
    pub confidence_band: i32,
    pub calibrated_mean: f64,
    pub decision: StrategyDecision,
}

#[derive(Debug, Clone, Serialize)]
pub struct NextActionResponse {
    pub schedule_header: String,
    pub entry_question: Option<QuestionDto>,
    pub entry_reason: String,
    pub priority_topic_id: Option<String>,
    pub exam_gap_summary: ExamGapSummaryDto,
    pub three_signal_summary: ThreeSignalSummaryDto,
    pub calibration_cells: Vec<CalibrationCellDto>,
    pub strategy_guidance: Vec<StrategyBandGuidanceDto>,
    pub exam_scenarios: Vec<ExamMomentScenarioDto>,
    pub fatigue_report: FatigueReport,
}

pub async fn get_next_action(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Query(query): Query<NextActionQuery>,
) -> Result<Json<NextActionResponse>, AppError> {
    let now_utc = chrono::Utc::now();
    let schedule_header = format_entry_60s_header(now_utc);

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

    let target_boards: HashSet<String> = active_exam
        .map(|e| {
            e.exam_boards
                .iter()
                .filter(|b| b.as_str() != "A definir" && !b.trim().is_empty())
                .map(|b| {
                    let low = b.trim().to_lowercase();
                    if low == "cespe" {
                        "cebraspe".to_string()
                    } else {
                        low
                    }
                })
                .collect()
        })
        .unwrap_or_default();

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

    // Disponibilidade de questões humanas cross-banca por tópico ativo para medir TransferStatus
    let mut cross_board_supply_by_topic: HashMap<String, usize> = HashMap::new();
    if let Some(exam) = active_exam {
        let active_topics: Vec<String> = exam
            .subjects
            .iter()
            .flat_map(|s| &s.topics)
            .filter(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
            .map(|t| t.canonical_topic_id.clone())
            .collect();
        if !active_topics.is_empty() {
            let mut supply_cursor = state
                .db
                .collection::<Document>("todasQuestoes")
                .find(doc! {
                    "status": "ACTIVE",
                    "validity.status": "CURRENT",
                    "classification.state": "CONFIRMED",
                    "question_type": { "$in": ["MULTIPLE_CHOICE", "TRUE_FALSE"] },
                    "canonical_topic_id": { "$in": &active_topics }
                })
                .projection(doc! { "canonical_topic_id": 1, "source": 1 })
                .limit(2500)
                .await?;
            let mut boards_per_topic: HashMap<String, HashSet<String>> = HashMap::new();
            while let Some(doc_row) = supply_cursor.try_next().await? {
                let Ok(tid) = doc_row.get_str("canonical_topic_id") else {
                    continue;
                };
                let src = doc_row
                    .get_str("source")
                    .unwrap_or("")
                    .trim()
                    .to_lowercase();
                let norm_src = if src == "cespe" {
                    "cebraspe".to_string()
                } else {
                    src
                };
                if norm_src.is_empty() || norm_src == "origem não informada" {
                    continue;
                }
                if !target_boards.is_empty() {
                    if !target_boards.contains(&norm_src) {
                        *cross_board_supply_by_topic
                            .entry(tid.to_string())
                            .or_insert(0) += 1;
                    }
                } else {
                    boards_per_topic
                        .entry(tid.to_string())
                        .or_default()
                        .insert(norm_src);
                }
            }
            if target_boards.is_empty() {
                for (tid, bset) in boards_per_topic {
                    if bset.len() >= 2 {
                        cross_board_supply_by_topic.insert(tid, bset.len() - 1);
                    }
                }
            }
        }
    }

    // Consolida diagnóstico de 3 sinais e transferência por unidade (`concept_id ?? topic_id`)
    struct UnitAgg {
        topic_id: String,
        distinct_qids: HashSet<String>,
        distinct_boards: HashSet<String>,
        non_blank_attempts: usize,
        correct_attempts: usize,
        transfer_attempts: usize,
        transfer_correct_grounded: usize,
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
                topic_id: obs.canonical_topic_id.clone(),
                distinct_qids: HashSet::new(),
                distinct_boards: HashSet::new(),
                non_blank_attempts: 0,
                correct_attempts: 0,
                transfer_attempts: 0,
                transfer_correct_grounded: 0,
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
            let is_cross_board_obs = if !obs.board_key.is_empty() {
                let was_other = if !target_boards.is_empty() {
                    !target_boards.contains(&obs.board_key)
                } else {
                    !entry.distinct_boards.is_empty() && !entry.distinct_boards.contains(&obs.board_key)
                };
                entry.distinct_boards.insert(obs.board_key);
                was_other
            } else {
                false
            };
            if is_cross_board_obs {
                entry.transfer_attempts += 1;
                if obs.last_is_correct
                    && obs.last_confidence >= 2
                    && obs.evidence_challenge_passed != Some(false)
                {
                    entry.transfer_correct_grounded += 1;
                }
            }
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

    let now_ms = now_utc.timestamp_millis();
    let mut diag_by_unit: HashMap<String, (DiagnosticStatus, TransferStatus, f64, usize, f64)> =
        HashMap::new();
    for (uid, agg) in &unit_map {
        let avail_cross = cross_board_supply_by_topic
            .get(&agg.topic_id)
            .copied()
            .unwrap_or(0);
        let t_status = evaluate_transfer_status(&TransferInputs {
            distinct_questions: agg.distinct_qids.len(),
            available_cross_board_questions: avail_cross,
            transfer_attempts: agg.transfer_attempts,
            transfer_correct_grounded: agg.transfer_correct_grounded,
        });
        let d_status = classify_diagnostic(&DiagnosticInputs {
            distinct_questions: agg.distinct_qids.len(),
            non_blank_attempts: agg.non_blank_attempts,
            correct_attempts: agg.correct_attempts,
            last_is_correct: agg.last_is_correct,
            last_confidence: agg.last_confidence,
            had_prior_verified_correct: agg.had_prior_verified_correct,
            has_spaced_retention: agg.has_spaced_retention,
            evidence_challenge_passed: agg.evidence_challenge_passed,
            has_independent_transfer: t_status == TransferStatus::TransferVerified,
        });
        let elapsed_days = ((now_ms - agg.last_answered_ms).max(0) as f64) / 86_400_000.0;
        let r = retrievability(elapsed_days, agg.max_stability);
        let avg_s = if agg.obs_count > 0 {
            agg.avg_seconds_sum / (agg.obs_count as f64)
        } else {
            60.0
        };
        diag_by_unit.insert(uid.clone(), (d_status, t_status, r, agg.distinct_qids.len(), avg_s));
    }

    let mut verified_units = 0usize;
    let mut consolidating_units = 0usize;
    let mut insufficient_units = 0usize;
    let mut evidence_in_progress_units = 0usize;
    let mut active_error_units = 0usize;
    let mut transfer_verified_units = 0usize;
    let mut transfer_available_units = 0usize;
    let mut transfer_unmeasured_units = 0usize;
    let mut best_entry_topic: Option<(f64, String, String)> = None;

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
                let default_t = if cross_board_supply_by_topic
                    .get(&top.canonical_topic_id)
                    .copied()
                    .unwrap_or(0)
                    > 0
                {
                    TransferStatus::TransferAvailable
                } else {
                    TransferStatus::TransferUnmeasured
                };
                let (st, tst, r, dq, avg_s) = diag_by_unit
                    .get(&top.canonical_topic_id)
                    .copied()
                    .unwrap_or((DiagnosticStatus::InsufficientEvidence, default_t, 0.0, 0, 60.0));
                match st {
                    DiagnosticStatus::VerifiedCompetence => verified_units += 1,
                    DiagnosticStatus::EvidenceInProgress
                    | DiagnosticStatus::ConsolidatedNoTransfer => {
                        consolidating_units += 1;
                        evidence_in_progress_units += 1;
                    }
                    DiagnosticStatus::OverconfidenceError
                    | DiagnosticStatus::ConceptualError
                    | DiagnosticStatus::RetrievalFailure
                    | DiagnosticStatus::IllusoryCompetence => {
                        consolidating_units += 1;
                        active_error_units += 1;
                    }
                    DiagnosticStatus::InsufficientEvidence if dq == 0 => insufficient_units += 1,
                    DiagnosticStatus::InsufficientEvidence => consolidating_units += 1,
                }
                match tst {
                    TransferStatus::TransferVerified => transfer_verified_units += 1,
                    TransferStatus::TransferAvailable | TransferStatus::TransferProvisional => {
                        transfer_available_units += 1;
                    }
                    TransferStatus::TransferUnmeasured => transfer_unmeasured_units += 1,
                    TransferStatus::TransferFailed => {}
                }
                let base_u = priority_utility(&PrioritizationInputs {
                    edital_weight: norm_w,
                    incidence_weight: 1.0,
                    retrievability: r,
                    verified_competence: st == DiagnosticStatus::VerifiedCompetence,
                    distinct_questions: dq,
                    avg_seconds: avg_s,
                    unmet_prerequisites_count: 0,
                    dependent_count: 0,
                });
                // Protocolo 60s prioriza assunto familiar (`dq >= 1`) com retenção pendente (`r < 0.90`)
                let entry_boost = if dq >= 1 && r < 0.90 {
                    1.65
                } else if dq >= 1 {
                    1.20
                } else {
                    1.0
                };
                let u = base_u * entry_boost;
                let reason = match st {
                    DiagnosticStatus::IllusoryCompetence => format!(
                        "Acerto com premissa divergente em {} — conferir fundamento",
                        top.display_title
                    ),
                    DiagnosticStatus::OverconfidenceError => format!(
                        "Indício de excesso de confiança em {}",
                        top.display_title
                    ),
                    DiagnosticStatus::ConceptualError => format!(
                        "Indício de erro conceitual em {}",
                        top.display_title
                    ),
                    DiagnosticStatus::RetrievalFailure => format!(
                        "Assunto familiar com retenção pendente: {}",
                        top.display_title
                    ),
                    DiagnosticStatus::EvidenceInProgress => format!(
                        "Evidência em construção ({dq}/{MIN_QUESTIONS_FOR_MASTERY} questões): {}",
                        top.display_title
                    ),
                    DiagnosticStatus::ConsolidatedNoTransfer => format!(
                        "Testar transferência independente em {}",
                        top.display_title
                    ),
                    DiagnosticStatus::VerifiedCompetence => format!(
                        "Manutenção leve em {}",
                        top.display_title
                    ),
                    DiagnosticStatus::InsufficientEvidence if dq > 0 => format!(
                        "Assunto familiar com retenção pendente: {}",
                        top.display_title
                    ),
                    DiagnosticStatus::InsufficientEvidence => format!(
                        "Assunto do edital para explorar sem pressa: {}",
                        top.display_title
                    ),
                };
                if best_entry_topic
                    .as_ref()
                    .is_none_or(|(best_u, _, _)| u > *best_u)
                {
                    best_entry_topic = Some((u, top.canonical_topic_id.clone(), reason));
                }
            }
        }
    }

    let priority_topic_id = best_entry_topic.as_ref().map(|(_, tid, _)| tid.clone());
    let mut entry_reason = best_entry_topic
        .map(|(_, _, r)| r)
        .unwrap_or_else(|| "Assunto familiar com retenção pendente.".into());

    let mut entry_questions = if let Some(tid) = &priority_topic_id {
        selection_v2::generate(
            &state,
            &QuizGenerateRequest {
                component_id: None,
                trunk_focus_slugs: Vec::new(),
                user_id: user.user_id,
                mode: QuizMode::Entry60s,
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
                mode: QuizMode::Entry60s,
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
            entry_reason = format!("Questão curta de {}.", first.disciplina);
        }
    }

    // Consolida Matriz dos 3 Sinais + Células Beta-Binomial
    let penalty = active_exam.map(|e| e.wrong_answer_penalty).unwrap_or(0.0);
    let cutoff = active_exam
        .map(|e| e.historical_cutoff_score)
        .unwrap_or(0.0);

    let mut three_signal = ThreeSignalSummaryDto::default();
    let mut cell_counts: HashMap<(&'static str, i32), (u32, u32)> = HashMap::new();

    for act in &typed_activities {
        let qdoc_opt = q_docs.get(&act.question_id);
        let qt = qdoc_opt
            .and_then(|d| d.get_str("question_type").ok())
            .unwrap_or("MULTIPLE_CHOICE");
        let qt_norm = if qt.eq_ignore_ascii_case("TRUE_FALSE") {
            "TRUE_FALSE"
        } else {
            "MULTIPLE_CHOICE"
        };
        let decoded_ch = qdoc_opt
            .and_then(|d| crate::services::question_compatibility::decode(d.clone()).ok())
            .and_then(|q| q.effective_evidence_challenge());

        let mut had_prior_good = false;
        for s in &act.past_sessions {
            if s.is_blank {
                three_signal.strategic_omissions += 1;
                if penalty > 0.0 {
                    // Sob penalidade (ex.: Cebraspe -1), omitir evita perda esperada em itens incertos
                    three_signal.omission_net_points_saved += 0.25 * penalty;
                }
                continue;
            }
            let band = s.nivel_confianca.clamp(1, 3);
            let entry = cell_counts.entry((qt_norm, band)).or_insert((0, 0));
            if s.status {
                entry.0 += 1;
            }
            entry.1 += 1;

            let ev_passed = match (s.selected_evidence_id.as_deref(), decoded_ch.as_ref()) {
                (Some(sel), Some(ch)) if !sel.trim().is_empty() => {
                    Some(sel.trim() == ch.correct_evidence_id)
                }
                _ => None,
            };

            if s.status {
                if ev_passed == Some(false) {
                    three_signal.illusory_competence_hits += 1;
                } else if s.nivel_confianca <= 1 {
                    three_signal.hesitant_hits += 1;
                } else {
                    three_signal.grounded_hits += 1;
                    had_prior_good = true;
                }
            } else if s.nivel_confianca >= 3 || (s.nivel_confianca >= 2 && ev_passed == Some(false))
            {
                three_signal.overconfidence_errors += 1;
            } else if had_prior_good {
                three_signal.retrieval_failures += 1;
            } else {
                three_signal.conceptual_errors += 1;
            }
        }
    }
    three_signal.omission_net_points_saved =
        (three_signal.omission_net_points_saved * 100.0).round() / 100.0;

    let mut calibration_cells = Vec::new();
    for qt in ["TRUE_FALSE", "MULTIPLE_CHOICE"] {
        for band in [1, 2, 3] {
            let (corr, att) = cell_counts.get(&(qt, band)).copied().unwrap_or((0, 0));
            calibration_cells.push(calibrated_probability(qt, band, corr, att));
        }
    }

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

    let fatigue_report = evaluate_from_activities(&typed_activities);
    let medium_cell = calibration_cells
        .iter()
        .find(|c| c.question_type == primary_qt && c.confidence_band == 2)
        .unwrap();
    let exam_scenarios = build_exam_moment_scenarios(
        medium_cell.calibrated_mean,
        medium_cell.credible_sd,
        penalty,
        cutoff,
        fatigue_report.estimated_lambda,
    );

    let total_units = verified_units + consolidating_units + insufficient_units;
    let days_until_exam = active_exam.and_then(|e| match &e.target_date {
        ExamDateTarget::ExactDate { iso_date } => days_until(iso_date, study_today()),
        _ => None,
    });

    Ok(Json(NextActionResponse {
        schedule_header,
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
            min_questions_for_mastery: MIN_QUESTIONS_FOR_MASTERY,
            evidence_in_progress_units,
            active_error_units,
            transfer_verified_units,
            transfer_available_units,
            transfer_unmeasured_units,
        },
        three_signal_summary: three_signal,
        calibration_cells,
        strategy_guidance,
        exam_scenarios,
        fatigue_report,
    }))
}
