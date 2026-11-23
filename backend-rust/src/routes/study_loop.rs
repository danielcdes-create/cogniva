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
        prioritization_engine::{format_entry_60s_header, priority_utility, PrioritizationInputs},
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

pub type UnitDiagnosticTuple = (DiagnosticStatus, TransferStatus, f64, usize, f64);

#[derive(Default)]
struct UnitDiagAccumulator {
    topic_id: String,
    seen_questions: HashSet<String>,
    non_blank_attempts: usize,
    correct_attempts: usize,
    any_question_spaced_retention: bool,
    any_question_prior_verified_correct: bool,
    earliest_verified_correct_ms: Option<i64>,
    latest_verified_correct_ms: Option<i64>,
    latest_obs_key: Option<(i64, String)>,
    last_is_correct: bool,
    last_confidence: i32,
    evidence_challenge_passed: Option<bool>,
    retrievability_sum: f64,
    avg_seconds_sum: f64,
    primary_board_when_untargeted: Option<String>,
    transfer_attempts: usize,
    transfer_correct_grounded: usize,
}

/// Agrega observações de questões por unidade canônica (`concept_id ?? topic_id`),
/// ordenando cronologicamente antes de avaliar retenção espaçada no nível da unidade (`>= 24h`
/// entre o primeiro e o último acerto verificado dentre questões distintas) e transferência
/// entre bancas (invariante à ordem física do cursor MongoDB).
pub fn aggregate_unit_diagnostics(
    observations: Vec<(knowledge::UnitQuestionObservation, f64)>,
    target_boards: &HashSet<String>,
    cross_board_supply_by_topic: &HashMap<String, usize>,
    now_ms: i64,
) -> HashMap<String, UnitDiagnosticTuple> {
    let mut sorted = observations;
    sorted.sort_by(|(a, _), (b, _)| {
        a.first_answered_ms
            .cmp(&b.first_answered_ms)
            .then_with(|| a.last_answered_ms.cmp(&b.last_answered_ms))
            .then_with(|| a.board_key.cmp(&b.board_key))
            .then_with(|| a.question_id.cmp(&b.question_id))
            .then_with(|| a.unit_id.cmp(&b.unit_id))
    });

    let mut by_unit: HashMap<String, UnitDiagAccumulator> = HashMap::new();
    for (obs, stability_days) in sorted {
        let acc = by_unit.entry(obs.unit_id.clone()).or_default();
        if acc.topic_id.is_empty() {
            acc.topic_id = obs.canonical_topic_id.clone();
        }
        if !acc.seen_questions.insert(obs.question_id.clone()) {
            continue;
        }

        acc.non_blank_attempts += obs.non_blank_attempts;
        acc.correct_attempts += obs.correct_attempts;
        acc.any_question_spaced_retention |= obs.has_spaced_retention;
        acc.any_question_prior_verified_correct |= obs.had_prior_verified_correct;

        if let Some(first_vc) = obs.first_verified_correct_ms {
            acc.earliest_verified_correct_ms = Some(
                acc.earliest_verified_correct_ms
                    .map_or(first_vc, |cur| cur.min(first_vc)),
            );
        }
        if let Some(last_vc) = obs.last_verified_correct_ms {
            acc.latest_verified_correct_ms = Some(
                acc.latest_verified_correct_ms
                    .map_or(last_vc, |cur| cur.max(last_vc)),
            );
        }

        let obs_key = (obs.last_answered_ms, obs.question_id.clone());
        if acc
            .latest_obs_key
            .as_ref()
            .is_none_or(|prev| &obs_key >= prev)
        {
            acc.latest_obs_key = Some(obs_key);
            acc.last_is_correct = obs.last_is_correct;
            acc.last_confidence = obs.last_confidence;
            acc.evidence_challenge_passed = obs.evidence_challenge_passed;
        }

        let elapsed_days = ((now_ms - obs.last_answered_ms).max(0) as f64) / 86_400_000.0;
        let eff_stability = if stability_days > 0.0 {
            stability_days
        } else if obs.last_is_correct {
            2.0
        } else {
            0.5
        };
        acc.retrievability_sum += retrievability(elapsed_days, eff_stability);
        acc.avg_seconds_sum += obs.avg_seconds;

        if !obs.board_key.is_empty() {
            let is_cross_board = if !target_boards.is_empty() {
                !target_boards.contains(&obs.board_key)
            } else {
                match &acc.primary_board_when_untargeted {
                    None => {
                        acc.primary_board_when_untargeted = Some(obs.board_key.clone());
                        false
                    }
                    Some(primary) => primary != &obs.board_key,
                }
            };
            if is_cross_board {
                acc.transfer_attempts += 1;
                if obs.last_is_correct
                    && obs.last_confidence >= 2
                    && obs.evidence_challenge_passed != Some(false)
                {
                    acc.transfer_correct_grounded += 1;
                }
            }
        }
    }

    let mut out = HashMap::with_capacity(by_unit.len());
    for (unit_id, acc) in by_unit {
        let distinct_questions = acc.seen_questions.len();
        if distinct_questions == 0 {
            continue;
        }
        let available_cross = cross_board_supply_by_topic
            .get(&acc.topic_id)
            .copied()
            .unwrap_or(0);
        let transfer_status = evaluate_transfer_status(&TransferInputs {
            distinct_questions,
            available_cross_board_questions: available_cross,
            transfer_attempts: acc.transfer_attempts,
            transfer_correct_grounded: acc.transfer_correct_grounded,
        });

        let unit_spaced_retention = acc.any_question_spaced_retention
            || matches!(
                (acc.earliest_verified_correct_ms, acc.latest_verified_correct_ms),
                (Some(first_vc), Some(last_vc)) if last_vc - first_vc >= 86_400_000
            );
        let latest_ts = acc.latest_obs_key.as_ref().map(|(t, _)| *t).unwrap_or(0);
        let had_prior_verified_correct = acc.any_question_prior_verified_correct
            || acc
                .earliest_verified_correct_ms
                .is_some_and(|first_vc| first_vc < latest_ts);

        let diag_status = classify_diagnostic(&DiagnosticInputs {
            distinct_questions,
            non_blank_attempts: acc.non_blank_attempts,
            correct_attempts: acc.correct_attempts,
            last_is_correct: acc.last_is_correct,
            last_confidence: acc.last_confidence,
            had_prior_verified_correct,
            has_spaced_retention: unit_spaced_retention,
            evidence_challenge_passed: acc.evidence_challenge_passed,
            has_independent_transfer: transfer_status == TransferStatus::TransferVerified,
        });

        let mean_r = acc.retrievability_sum / (distinct_questions as f64);
        let mean_secs = acc.avg_seconds_sum / (distinct_questions as f64);
        out.insert(
            unit_id,
            (
                diag_status,
                transfer_status,
                mean_r,
                distinct_questions,
                mean_secs,
            ),
        );
    }
    out
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
        .and_then(|cid| {
            exams.iter().find(|e| {
                e.id == cid
                    && !matches!(
                        e.status,
                        ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido
                    )
            })
        })
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
                .map(|b| knowledge::normalize_board_key(b))
                .filter(|b| !b.is_empty())
                .collect()
        })
        .unwrap_or_default();

    let raw_rows: Vec<Document> = state
        .db
        .collection::<Document>("userActivityLog")
        .find(doc! { "user_id": user.user_id })
        .projection(doc! { "pastSessions.ack": 0, "pastSessions.payload_sha256": 0 })
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
                let norm_src =
                    knowledge::normalize_board_key(doc_row.get_str("source").unwrap_or(""));
                if norm_src.is_empty() {
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

    let stability_by_qid: HashMap<String, f64> = typed_activities
        .iter()
        .map(|a| (a.question_id.to_hex(), a.memory_stability_days))
        .collect();

    let mut collected_observations = Vec::new();
    for raw in &raw_rows {
        let Some(qid) = raw.get_object_id("question_id").ok() else {
            continue;
        };
        let Some(qdoc) = q_docs.get(&qid) else {
            continue;
        };
        let stab = stability_by_qid.get(&qid.to_hex()).copied().unwrap_or(0.0);
        for obs in knowledge::project_units(raw, qdoc) {
            collected_observations.push((obs, stab));
        }
    }

    let now_ms = now_utc.timestamp_millis();
    let diag_by_unit = aggregate_unit_diagnostics(
        collected_observations,
        &target_boards,
        &cross_board_supply_by_topic,
        now_ms,
    );

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
                    .unwrap_or((
                        DiagnosticStatus::InsufficientEvidence,
                        default_t,
                        0.0,
                        0,
                        60.0,
                    ));
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
                    DiagnosticStatus::OverconfidenceError => {
                        format!("Indício de excesso de confiança em {}", top.display_title)
                    }
                    DiagnosticStatus::ConceptualError => {
                        format!("Indício de erro conceitual em {}", top.display_title)
                    }
                    DiagnosticStatus::RetrievalFailure => format!(
                        "Assunto familiar com retenção pendente: {}",
                        top.display_title
                    ),
                    DiagnosticStatus::EvidenceInProgress => format!(
                        "Evidência em construção ({dq}/{MIN_QUESTIONS_FOR_MASTERY} questões): {}",
                        top.display_title
                    ),
                    DiagnosticStatus::ConsolidatedNoTransfer => {
                        format!("Testar transferência independente em {}", top.display_title)
                    }
                    DiagnosticStatus::VerifiedCompetence => {
                        format!("Manutenção leve em {}", top.display_title)
                    }
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
        let decoded_q = qdoc_opt
            .and_then(|d| crate::services::question_compatibility::decode(d.clone()).ok());
        let has_curated_ch = decoded_q
            .as_ref()
            .is_some_and(|q| q.evidence_challenge.is_some());
        let decoded_ch = decoded_q
            .as_ref()
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
                    let norm_sel = crate::models::question::normalize_synthesized_evidence_id(
                        sel,
                        has_curated_ch,
                    );
                    Some(norm_sel == ch.correct_evidence_id)
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
            } else if ev_passed == Some(false) || !had_prior_good {
                three_signal.conceptual_errors += 1;
            } else {
                three_signal.retrieval_failures += 1;
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::knowledge::UnitQuestionObservation;

    fn make_obs(
        idx: usize,
        board: &str,
        ts_ms: i64,
        is_correct: bool,
        confidence: i32,
    ) -> (UnitQuestionObservation, f64) {
        (
            UnitQuestionObservation {
                unit_id: "DIR_ADM_01".into(),
                canonical_subject_id: "DIR_ADM".into(),
                canonical_topic_id: "DIR_ADM_01".into(),
                concept_id: None,
                question_id: format!("{idx:024x}"),
                board_key: board.into(),
                non_blank_attempts: 1,
                correct_attempts: usize::from(is_correct),
                last_is_correct: is_correct,
                last_confidence: confidence,
                had_prior_verified_correct: false,
                has_spaced_retention: false,
                evidence_challenge_passed: Some(true),
                first_answered_ms: ts_ms,
                last_answered_ms: ts_ms,
                first_verified_correct_ms: is_correct.then_some(ts_ms),
                last_verified_correct_ms: is_correct.then_some(ts_ms),
                avg_seconds: 35.0,
            },
            4.0,
        )
    }

    #[test]
    fn fifteen_distinct_questions_across_days_reach_verified_competence_with_order_invariance() {
        let day1_ms = 1_760_000_000_000_i64;
        let day3_ms = day1_ms + 2 * 86_400_000;
        let now_ms = day3_ms + 3_600_000;

        // 10 distinct CEBRASPE questions on Day 1 + 5 distinct FGV questions on Day 3 (>= 24h apart, 0 repeated question_ids!)
        let mut forward = Vec::new();
        for i in 1..=10 {
            forward.push(make_obs(
                i,
                "cebraspe",
                day1_ms + (i as i64) * 1_000,
                true,
                3,
            ));
        }
        for i in 11..=15 {
            forward.push(make_obs(i, "fgv", day3_ms + (i as i64) * 1_000, true, 3));
        }

        let mut reversed = forward.clone();
        reversed.reverse();

        let empty_targets = HashSet::new();
        let mut supply = HashMap::new();
        supply.insert("DIR_ADM_01".to_string(), 5);

        let diag_fwd = aggregate_unit_diagnostics(forward, &empty_targets, &supply, now_ms);
        let diag_rev = aggregate_unit_diagnostics(reversed, &empty_targets, &supply, now_ms);

        let (st_f, tr_f, r_f, dq_f, _) = diag_fwd["DIR_ADM_01"];
        let (st_r, tr_r, r_r, dq_r, _) = diag_rev["DIR_ADM_01"];

        assert_eq!(dq_f, 15);
        assert_eq!(tr_f, TransferStatus::TransferVerified);
        assert_eq!(st_f, DiagnosticStatus::VerifiedCompetence);
        assert_eq!(st_f, st_r);
        assert_eq!(tr_f, tr_r);
        assert_eq!(dq_f, dq_r);
        assert!((r_f - r_r).abs() < 1e-12);

        // Same-day burst (< 24h) of 15 distinct questions must NOT prematurely claim VerifiedCompetence
        let same_day: Vec<_> = (1..=15)
            .map(|i| {
                make_obs(
                    i,
                    if i <= 10 { "cebraspe" } else { "fgv" },
                    day1_ms + (i as i64) * 1_000,
                    true,
                    3,
                )
            })
            .collect();
        let diag_same_day =
            aggregate_unit_diagnostics(same_day, &empty_targets, &supply, day1_ms + 60_000);
        assert_eq!(
            diag_same_day["DIR_ADM_01"].0,
            DiagnosticStatus::InsufficientEvidence
        );
    }
}
