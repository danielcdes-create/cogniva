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

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CacheRequest { pub question_ids:Vec<String> }
pub async fn cache_state(State(state):State<Arc<AppState>>,Extension(user):Extension<SessionUser>,Json(body):Json<CacheRequest>)->Result<Json<Value>,AppError>{
    if body.question_ids.len()>200 || body.question_ids.iter().collect::<std::collections::HashSet<_>>().len()!=body.question_ids.len(){return Err(AppError::BadRequest("Cache aceita até 200 IDs distintos por página".into()));}
    let ids=body.question_ids.iter().map(|id|ObjectId::parse_str(id)).collect::<Result<Vec<_>,_>>()?;
    if ids.iter().zip(&body.question_ids).any(|(id,text)|id.to_hex()!=*text){return Err(AppError::BadRequest("Use IDs canônicos em minúsculas".into()));}
    let available=crate::services::selection_v2::pool(&state,doc!{"_id":{"$in":ids}},10000).await?;
    let dtos=crate::services::quiz_selector::enrich_questions_with_user_stats(&state.db,user.0,available).await?;
    let decisions=crate::services::practice_context::load(&state,user.0,&dtos).await?;
    let mut by_id:std::collections::HashMap<_,_>=dtos.into_iter().map(|q|(q.id.clone(),q)).collect();
    Ok(Json(serde_json::json!({"projection_version":1,"decisions":decisions,"questions":body.question_ids.iter().map(|id|serde_json::json!({"question_id":id,"question":by_id.remove(id)})).collect::<Vec<_>>()})))
}

pub async fn generate_proposals(
    State(state): State<Arc<AppState>>,
    Json(body): Json<Value>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::generate(&state.db, &state.config, &body).await.map(Json)
}

pub async fn review_proposals(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::review_view(&state.db, &id).await.map(Json)
}

pub async fn decide_proposal(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<Value>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::decide(&state.db, user.0, &body).await.map(Json)
}

pub async fn decide_compatibility(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<Value>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::decide_compatibility(&state, user.0, &body).await.map(Json)
}

pub async fn list_compatibility(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    axum::extract::Query(query): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::current_compatibility(
        &state,
        user.0,
        query.get("exam_id").map(String::as_str).unwrap_or(""),
        query.get("question_id").map(String::as_str).unwrap_or(""),
    )
    .await
    .map(Json)
}
