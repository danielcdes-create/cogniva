use crate::{
    error::AppError,
    security::SessionUser,
    services::{question_contracts, question_storage},
    AppState,
};
use axum::{
    extract::{Path, Query, State},
    Extension, Json,
};
use bson::{doc, oid::ObjectId, Bson};
use serde::Deserialize;
use serde_json::Value;
use std::sync::Arc;

pub async fn import_capabilities(State(state): State<Arc<AppState>>) -> Json<Value> {
    let ai_available = state.config.xai_api_key.is_some()
        && state
            .config
            .xai_model
            .as_deref()
            .is_some_and(|m| !m.trim().is_empty());
    Json(serde_json::json!({
        "max_items": 50,
        "ai_available": ai_available
    }))
}

pub async fn import_preview(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<crate::services::question_import::PreviewRequest>,
) -> Result<Json<Value>, AppError> {
    crate::services::question_import::preview(&state, user.user_id, body)
        .await
        .map(Json)
}

pub async fn import_commit(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<crate::services::question_import::CommitRequest>,
) -> Result<Json<Value>, AppError> {
    crate::services::question_import::commit(&state, user.user_id, body)
        .await
        .map(Json)
}

pub async fn import_assist(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<crate::services::question_import::AssistRequest>,
) -> Result<Json<Value>, AppError> {
    crate::services::question_import::assist(&state, user.user_id, body)
        .await
        .map(Json)
}

pub async fn save(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(r): Json<question_storage::SaveRequest>,
) -> Result<Json<Value>, AppError> {
    question_storage::save(&state.db, user.user_id, r)
        .await
        .map(Json)
}

pub async fn get(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, AppError> {
    let raw = question_storage::collection(&state.db, "todasQuestoes")
        .find_one(doc! { "_id": ObjectId::parse_str(id)? })
        .await?
        .ok_or_else(|| AppError::NotFound("Questão ausente".into()))?;
    Ok(Json(Bson::Document(raw).into_relaxed_extjson()))
}

pub async fn catalog() -> Json<Value> {
    Json(question_contracts::catalog())
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CacheRequest {
    pub question_ids: Vec<String>,
}

pub async fn cache_state(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<CacheRequest>,
) -> Result<Json<Value>, AppError> {
    if body.question_ids.len() > 200
        || body
            .question_ids
            .iter()
            .collect::<std::collections::HashSet<_>>()
            .len()
            != body.question_ids.len()
    {
        return Err(AppError::BadRequest(
            "Cache aceita até 200 IDs distintos por página".into(),
        ));
    }
    let ids = body
        .question_ids
        .iter()
        .map(|id| ObjectId::parse_str(id))
        .collect::<Result<Vec<_>, _>>()?;
    if ids
        .iter()
        .zip(&body.question_ids)
        .any(|(id, text)| id.to_hex() != *text)
    {
        return Err(AppError::BadRequest(
            "Use IDs canônicos em minúsculas".into(),
        ));
    }
    let available = crate::services::selection_v2::pool(
        &state,
        user.user_id,
        doc! { "_id": { "$in": ids } },
        10000,
    )
    .await?;
    let dtos = crate::services::quiz_selector::enrich_questions_with_user_stats(
        &state.db,
        user.user_id,
        available,
    )
    .await?;
    let decisions = crate::services::practice_context::load(&state, user.user_id, &dtos).await?;
    let mut by_id: std::collections::HashMap<_, _> =
        dtos.into_iter().map(|q| (q.id.clone(), q)).collect();
    Ok(Json(serde_json::json!({
        "projection_version": 1,
        "decisions": decisions,
        "questions": body
            .question_ids
            .iter()
            .map(|id| serde_json::json!({
                "question_id": id,
                "question": by_id.remove(id)
            }))
            .collect::<Vec<_>>()
    })))
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProposalsQuery {
    pub status: Option<String>,
    pub limit: Option<i64>,
}

pub async fn list_proposals(
    State(state): State<Arc<AppState>>,
    Query(query): Query<ProposalsQuery>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::list_proposals(
        &state.db,
        query.status.as_deref(),
        query.limit.unwrap_or(50),
    )
    .await
    .map(Json)
}

pub async fn generate_proposals(
    State(state): State<Arc<AppState>>,
    Json(body): Json<Value>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::generate(&state.db, &state.config, &body)
        .await
        .map(Json)
}

pub async fn review_proposals(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::review_view(&state.db, &id)
        .await
        .map(Json)
}

pub async fn decide_proposal(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<Value>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::decide(&state.db, user.user_id, &body)
        .await
        .map(Json)
}
