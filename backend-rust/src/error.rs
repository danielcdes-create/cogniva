use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum AppError {
    #[error("{0}")]
    TemporarilyUnavailable(String),
    #[error("{0}")]
    RateLimited(String),
    #[error("{0}")]
    Unauthorized(String),
    #[error("{0}")]
    Forbidden(String),
    #[error("{0}")]
    Conflict(String),
    #[error("Erro no Banco de Dados: {0}")]
    Database(#[from] mongodb::error::Error),

    #[error("ID de objeto inválido: {0}")]
    InvalidObjectId(#[from] bson::oid::Error),

    #[error("Recurso não encontrado: {0}")]
    NotFound(String),

    #[error("Requisição inválida: {0}")]
    BadRequest(String),

    #[error("Erro ao consultar provedor de IA: {0}")]
    ExternalAiError(String),

    #[error("Erro interno: {0}")]
    Internal(String),
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AppError::TemporarilyUnavailable(_) => {
                (StatusCode::SERVICE_UNAVAILABLE, self.to_string())
            }
            AppError::RateLimited(_) => (StatusCode::TOO_MANY_REQUESTS, self.to_string()),
            AppError::Unauthorized(_) => (StatusCode::UNAUTHORIZED, self.to_string()),
            AppError::Forbidden(_) => (StatusCode::FORBIDDEN, self.to_string()),
            AppError::Conflict(_) => (StatusCode::CONFLICT, self.to_string()),
            AppError::Database(e) => {
                tracing::error!("MongoDB error: {:?}", e);
                (
                    StatusCode::SERVICE_UNAVAILABLE,
                    "Persistência temporariamente indisponível".to_string(),
                )
            }
            AppError::InvalidObjectId(_) => (StatusCode::BAD_REQUEST, self.to_string()),
            AppError::NotFound(_) => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::BadRequest(_) => (StatusCode::BAD_REQUEST, self.to_string()),
            AppError::ExternalAiError(_) => (StatusCode::BAD_GATEWAY, self.to_string()),
            AppError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, self.to_string()),
        };

        let body = Json(json!({
            "error": true,
            "message": message,
        }));

        (status, body).into_response()
    }
}
