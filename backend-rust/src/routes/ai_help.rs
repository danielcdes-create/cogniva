use crate::{
    error::AppError,
    services::ai_tutor::{get_or_generate_ai_assist_budget as get_or_generate_ai_assist, AiAssistKind, AiAssistResponse},
    AppState,
};
use axum::{
    extract::{Path, Query, State},
    Extension, Json,
};
use serde::Deserialize;
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct AiHelpQuery {
    pub hint_index: Option<usize>,
}

/// Endpoint `POST /api/v1/questions/:id/ai/:kind`
/// Exemplos de `:kind`: `explanation`, `contextual-hint`, `dr-sincero`, `child-friendly-pre`,
/// `child-friendly-post`, `extra-concepts`, `mnemonic`, `plausibility`
pub async fn handle_question_ai_assist(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<crate::security::SessionUser>,
    Path((question_id, kind)): Path<(String, AiAssistKind)>,
    Query(query): Query<AiHelpQuery>,
) -> Result<Json<AiAssistResponse>, AppError> {
    let res = get_or_generate_ai_assist(
        &state.db,
        &state.config,
        &question_id,
        kind,
        query.hint_index,
        Some(user.0),
    )
    .await?;

    Ok(Json(res))
}
