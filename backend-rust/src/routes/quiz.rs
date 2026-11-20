use crate::{
    error::AppError,
    models::{
        activity::ConfidenceQuadrant,
        question::{QuestionDto, VALID_PROBLEM_LIST},
    },
    security::SessionUser,
    services::quiz_selector::QuizGenerateRequest,
    AppState,
};
use axum::{
    extract::{Path, State},
    Extension, Json,
};
use bson::{doc, oid::ObjectId};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

fn is_false(v: &bool) -> bool {
    !*v
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnswerAttemptItem {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub exam_component_id: Option<String>,
    pub attempt_id: String,
    #[serde(default)]
    pub occurred_at: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_answered_at: Option<String>,
    pub question_revision: String,
    pub question_id: String,
    #[serde(default)]
    pub chosen_index: usize,
    #[serde(default)]
    pub chosen_text: String,
    #[serde(default)]
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
    #[serde(default, skip_serializing_if = "is_false")]
    pub is_blank: bool,
    #[serde(default, skip_serializing_if = "is_false")]
    pub confidence_explicit: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub selected_evidence_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub position_in_session: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub elapsed_session_seconds: Option<i32>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub session_interrupted: bool,
}

impl AnswerAttemptItem {
    pub fn effective_answered_at(&self) -> &str {
        self.client_answered_at
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| self.occurred_at.trim())
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct SubmitBatchRequest {
    #[serde(default)]
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
    #[serde(default)]
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

/// Endpoint `POST /api/v1/quiz/generate`: Gera fila de questões vinculada à sessão autenticada
pub async fn handle_generate_quiz(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(mut req): Json<QuizGenerateRequest>,
) -> Result<Json<Vec<QuestionDto>>, AppError> {
    if req.user_id != 0 && req.user_id != user.user_id {
        return Err(AppError::Forbidden(
            "Não é permitido consultar filas de outra conta".into(),
        ));
    }
    req.user_id = user.user_id;
    let questions = crate::services::selection_v2::generate(&state, &req).await?;
    Ok(Json(questions))
}

/// Endpoint `POST /api/v1/quiz/submit-batch`:
/// Processa 1 ou N tentativas vinculadas à sessão autenticada (com recibo durável de idempotência).
pub async fn handle_submit_batch(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(req): Json<SubmitBatchRequest>,
) -> Result<Json<SubmitBatchResponse>, AppError> {
    if req.user_id != 0 && req.user_id != user.user_id {
        return Err(AppError::Forbidden(
            "Não é permitido registrar tentativas em outra conta".into(),
        ));
    }
    if req.attempts.len() > 120 {
        return Err(AppError::BadRequest(
            "Envie no máximo 120 tentativas por lote".into(),
        ));
    }
    let mut results = Vec::new();
    let mut rejected = Vec::new();
    for item in &req.attempts {
        match crate::services::attempts::commit(&state, user.user_id, item).await {
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

#[derive(Debug, Clone, Deserialize)]
pub struct AttachEvidenceRequest {
    pub question_id: String,
    pub attempt_id: String,
    pub selected_evidence_id: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct AttachEvidenceResponse {
    pub status: &'static str,
    pub attempt_id: String,
    pub selected_evidence_id: String,
}

/// Endpoint `POST /api/v1/quiz/attempt-evidence`:
/// Vincula de forma idempotente a evidência opcional pós-resposta a uma tentativa já sincronizada.
pub async fn handle_attach_attempt_evidence(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(req): Json<AttachEvidenceRequest>,
) -> Result<Json<AttachEvidenceResponse>, AppError> {
    let applied = crate::services::attempts::attach_evidence(
        &state,
        user.user_id,
        &req.question_id,
        &req.attempt_id,
        &req.selected_evidence_id,
    )
    .await?;
    Ok(Json(AttachEvidenceResponse {
        status: if applied {
            "applied"
        } else {
            "pending_parent_attempt"
        },
        attempt_id: req.attempt_id,
        selected_evidence_id: req.selected_evidence_id.trim().to_string(),
    }))
}

/// Endpoint `POST /api/v1/questions/:id/report`: Salva reporte isolado por usuário em `reportedQuestoes`
pub async fn handle_report_problem(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(question_id): Path<String>,
    Json(req): Json<ReportProblemRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    if req.user_id != 0 && req.user_id != user.user_id {
        return Err(AppError::Forbidden(
            "Não é permitido reportar questões em nome de outra conta".into(),
        ));
    }
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
        .find_one(doc! { "_id": q_oid })
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
            doc! { "questao_id": q_oid, "user_id": user.user_id },
            doc! {
                "$addToSet": { "problemas": &req.problem_category },
                "$set": {
                    "ultima_atualizacao": now,
                    "fix.status_solved": false,
                    "fix.data_solved": bson::Bson::Null,
                },
                "$setOnInsert": {
                    "data_report": now,
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
        .update_one(
            doc! { "_id": q_oid },
            doc! { "$max": { "reports": unique_reports as i64, "reports_unique": unique_reports as i64 } },
        )
        .await?;

    Ok(Json(serde_json::json!({
        "status": "ok",
        "question_id": question_id,
        "reported_category": req.problem_category
    })))
}
