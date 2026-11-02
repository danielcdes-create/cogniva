use crate::{
    error::AppError,
    security::SessionUser,
    services::{question_contracts, question_storage},
    AppState,
};
use axum::{
    extract::{Path, State},
    Extension, Json,
};
use bson::{doc, oid::ObjectId, Bson};
use serde_json::Value;
use std::sync::Arc;

pub async fn save(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(r): Json<question_storage::SaveRequest>,
) -> Result<Json<Value>, AppError> {
    question_storage::save(&state.db, user.0, r).await.map(Json)
}
pub async fn get(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, AppError> {
    let raw = question_storage::collection(&state.db, "todasQuestoes")
        .find_one(doc! {"_id":ObjectId::parse_str(id)?})
        .await?
        .ok_or_else(|| AppError::NotFound("Questão ausente".into()))?;
    Ok(Json(Bson::Document(raw).into_relaxed_extjson()))
}
pub async fn catalog() -> Json<Value> {
    Json(question_contracts::catalog())
}
