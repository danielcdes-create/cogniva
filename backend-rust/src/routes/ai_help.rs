use crate::{
    error::AppError,
    services::ai_tutor::{
        get_or_generate_ai_assist_with_context, AiAssistKind, AiAssistResponse,
    },
    AppState,
};
use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    Extension, Json,
};
use serde::Deserialize;
use std::sync::Arc;

#[derive(Debug, Default, Deserialize)]
pub struct AiHelpQuery {
    pub hint_index: Option<usize>,
    pub user_note: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub struct AiHelpBody {
    pub hint_index: Option<usize>,
    pub user_note: Option<String>,
    pub student_answer: Option<String>,
}

/// Endpoint `POST /api/v1/questions/:id/ai/:kind`
/// Exemplos de `:kind`: `explanation`, `contextual-hint`, `dr-sincero`, `child-friendly-pre`,
/// `child-friendly-post`, `extra-concepts`, `mnemonic`, `plausibility`
pub async fn handle_question_ai_assist(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<crate::security::SessionUser>,
    Path((question_id, kind)): Path<(String, AiAssistKind)>,
    Query(query): Query<AiHelpQuery>,
    body: Bytes,
) -> Result<Json<AiAssistResponse>, AppError> {
    let parsed_body = if body.iter().all(|b| b.is_ascii_whitespace()) {
        AiHelpBody::default()
    } else {
        serde_json::from_slice::<AiHelpBody>(&body)
            .map_err(|_| AppError::BadRequest("Corpo JSON inválido na solicitação de IA.".into()))?
    };

    let hint_index = parsed_body.hint_index.or(query.hint_index);
    let user_note = parsed_body.user_note.or(query.user_note);

    let res = get_or_generate_ai_assist_with_context(
        &state.db,
        &state.config,
        &question_id,
        kind,
        hint_index,
        Some(user.user_id),
        user.is_guest,
        user_note.as_deref(),
        parsed_body.student_answer.as_deref(),
    )
    .await?;

    Ok(Json(res))
}
