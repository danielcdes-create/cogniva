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

    #[error("{message}")]
    Structured {
        status: StatusCode,
        code: &'static str,
        field: Option<String>,
        message: String,
    },
}

impl AppError {
    pub fn structured(
        status: StatusCode,
        code: &'static str,
        field: Option<impl Into<String>>,
        message: impl Into<String>,
    ) -> Self {
        Self::Structured {
            status,
            code,
            field: field.map(Into::into),
            message: message.into(),
        }
    }

    pub fn bad_request_code(
        code: &'static str,
        field: Option<impl Into<String>>,
        message: impl Into<String>,
    ) -> Self {
        Self::structured(StatusCode::BAD_REQUEST, code, field, message)
    }

    pub fn forbidden_code(
        code: &'static str,
        field: Option<impl Into<String>>,
        message: impl Into<String>,
    ) -> Self {
        Self::structured(StatusCode::FORBIDDEN, code, field, message)
    }

    pub fn conflict_code(
        code: &'static str,
        field: Option<impl Into<String>>,
        message: impl Into<String>,
    ) -> Self {
        Self::structured(StatusCode::CONFLICT, code, field, message)
    }

    pub fn error_code(&self) -> &'static str {
        match self {
            AppError::TemporarilyUnavailable(_) => "TEMPORARILY_UNAVAILABLE",
            AppError::RateLimited(_) => "RATE_LIMITED",
            AppError::Unauthorized(_) => "AUTHENTICATION_REQUIRED",
            AppError::Forbidden(_) => "FORBIDDEN",
            AppError::Conflict(_) => "CONFLICT",
            AppError::Database(_) => "DATABASE_UNAVAILABLE",
            AppError::InvalidObjectId(_) => "INVALID_OBJECT_ID",
            AppError::NotFound(_) => "NOT_FOUND",
            AppError::BadRequest(_) => "BAD_REQUEST",
            AppError::ExternalAiError(_) => "EXTERNAL_AI_ERROR",
            AppError::Internal(_) => "INTERNAL_ERROR",
            AppError::Structured { code, .. } => code,
        }
    }

    pub fn error_field(&self) -> Option<&str> {
        match self {
            AppError::InvalidObjectId(_) => Some("_id"),
            AppError::Structured { field, .. } => field.as_deref(),
            _ => None,
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let code = self.error_code();
        let field = self.error_field().map(str::to_string);
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
            AppError::Structured {
                status, message, ..
            } => (*status, message.clone()),
        };

        let body = Json(json!({
            "error": true,
            "code": code,
            "field": field,
            "message": message,
            "correlation_id": uuid::Uuid::new_v4().to_string(),
        }));

        (status, body).into_response()
    }
}
