use crate::{
    error::AppError,
    models::{
        activity::ConfidenceQuadrant,
        question::{QuestionDto, VALID_PROBLEM_LIST},
    },
    services::quiz_selector::QuizGenerateRequest,
    AppState,
};
use axum::{
    extract::{Path, State},
    Json,
};
use bson::{doc, oid::ObjectId};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnswerAttemptItem {
    #[serde(default,skip_serializing_if="Option::is_none")]
    pub exam_component_id:Option<String>,
    pub attempt_id: String,
    pub occurred_at: String,
    pub question_revision: String,
    pub question_id: String,
    pub chosen_index: usize,
    pub chosen_text: String,
    pub is_correct: bool,
    pub time_spent_seconds: i32,
    /// 0 = Nenhuma Ideia, 1 = Chute, 2 = Sei Mais ou Menos, 3 = Dominei!
    pub confidence_level: i32,
    pub session_uuid: String,
    pub quiz_mode: String,
    pub exam_block: Option<String>,
    pub contest_id: Option<String>,
    pub user_note: Option<String>,
    #[serde(default)]
    pub canonical_subject_id: Option<String>,
    #[serde(default)]
    pub canonical_topic_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SubmitBatchRequest {
    pub user_id: i64,
    pub attempts: Vec<AnswerAttemptItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessedAttemptResult {
    pub attempt_id: String,
    pub confidence_avg: Option<i32>,
    pub attempts_total: i32,
    pub correct_total: i32,
    pub next_review_at: String,
    pub canonical_subject_id: Option<String>,
    pub canonical_topic_id: Option<String>,
    pub question_id: String,
    pub is_correct: bool,
    pub confidence_quadrant: ConfidenceQuadrant,
    pub high_confidence_error_alert: bool,
    pub streak_atual: i32,
    pub streak_max: i32,
    pub points_total: f64,
    pub points_with_decay: f64,
    pub is_mastered: bool,
    pub next_review_interval_days: i32,
    pub accuracy_accumulated_pct: f64,
    pub in_error_notebook: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct SubmitBatchResponse {
    pub processed_count: usize,
    pub rejected: Vec<RejectedAttemptResult>,
    pub results: Vec<ProcessedAttemptResult>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ReportProblemRequest {
    pub user_id: i64,
    pub problem_category: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct RejectedAttemptResult {
    pub attempt_id: String,
    pub question_id: String,
    pub message: String,
    pub retryable: bool,
}

/// Endpoint `POST /api/v1/quiz/generate`: Gera fila de questões para qualquer modo
pub async fn handle_generate_quiz(
    State(state): State<Arc<AppState>>,
    Json(req): Json<QuizGenerateRequest>,
) -> Result<Json<Vec<QuestionDto>>, AppError> {
    let questions = crate::services::selection_v2::generate(&state, &req).await?;
    Ok(Json(questions))
}

/// Endpoint `POST /api/v1/quiz/submit-batch`:
/// Processa 1 ou N tentativas (ideal para sincronização Offline-First do Room via WorkManager),
/// executando toda a matemática do `update_user_question_performance` do AFTBot.py em Rust nativo.
pub async fn handle_submit_batch(
    State(state): State<Arc<AppState>>,
    Json(req): Json<SubmitBatchRequest>,
) -> Result<Json<SubmitBatchResponse>, AppError> {
    if req.attempts.len() > 120 {
        return Err(AppError::BadRequest(
            "Envie no máximo 120 tentativas por lote".into(),
        ));
    }
    let mut results = Vec::new();
    let mut rejected = Vec::new();
    for item in &req.attempts {
        match crate::services::attempts::commit(&state, req.user_id, item).await {
            Ok(ack) => results.push(ack),
            Err(error) => {
                let retryable = matches!(
                    error,
                    AppError::Database(_)
                        | AppError::Internal(_)
                        | AppError::TemporarilyUnavailable(_)
                );
                let message = if retryable {
                    "Persistência indisponível; tentativa preservada".into()
                } else {
                    error.to_string()
                };
                rejected.push(RejectedAttemptResult {
                    attempt_id: item.attempt_id.clone(),
                    question_id: item.question_id.clone(),
                    message,
                    retryable,
                });
            }
        }
    }
    Ok(Json(SubmitBatchResponse {
        processed_count: results.len(),
        results,
        rejected,
    }))
}

/// Endpoint `POST /api/v1/questions/:id/report`: Salva reporte de problema em `reportedQuestoes`
pub async fn handle_report_problem(
    State(state): State<Arc<AppState>>,
    Path(question_id): Path<String>,
    Json(req): Json<ReportProblemRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    if !VALID_PROBLEM_LIST.contains(&req.problem_category.as_str()) {
        return Err(AppError::BadRequest(format!(
            "Categoria de problema inválida: {}",
            req.problem_category
        )));
    }

    let q_oid = ObjectId::parse_str(&question_id)?;
    if state
        .db
        .collection::<bson::Document>("todasQuestoes")
        .find_one(doc! {"_id":q_oid})
        .await?
        .is_none()
    {
        return Err(AppError::NotFound(
            "Questão não encontrada; reporte preservado no aparelho".into(),
        ));
    }
    let now = bson::DateTime::now();
    let rep_coll = state.db.collection::<bson::Document>("reportedQuestoes");

    rep_coll
        .update_one(
            doc! { "questao_id": q_oid, "user_id": req.user_id },
            doc! {
                "$addToSet": { "problemas": &req.problem_category },
                "$set": { "ultima_atualizacao": now },
                "$setOnInsert": {
                    "data_report": now,
                    "fix": { "data_solved": bson::Bson::Null, "status_solved": false }
                }
            },
        )
        .upsert(true)
        .await?;

    let unique_reports = rep_coll
        .count_documents(doc! { "questao_id": q_oid })
        .await?;
    state
        .db
        .collection::<bson::Document>("todasQuestoes")
        .update_one(doc! { "_id": q_oid }, doc! { "$max": { "reports": unique_reports as i64, "reports_unique": unique_reports as i64 } })
        .await?;

    Ok(Json(serde_json::json!({
        "status": "ok",
        "question_id": question_id,
        "reported_category": req.problem_category
    })))
}
