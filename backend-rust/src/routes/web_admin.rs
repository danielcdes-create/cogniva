use crate::{error::AppError, routes::auth_session, security::SessionUser, AppState};
use axum::{
    extract::{Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Extension, Json,
};
use serde_json::{json, Value};
use std::sync::Arc;

const CSP: &str = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

pub async fn asset(Path(name): Path<String>) -> Response {
    let (mime, body) = match name.as_str() {
        "app.js" => (
            "text/javascript; charset=utf-8",
            include_str!("../../../web-admin/app.js"),
        ),
        "help.js" => (
            "text/javascript; charset=utf-8",
            include_str!("../../../web-admin/help.js"),
        ),
        "domain.js" => (
            "text/javascript; charset=utf-8",
            include_str!("../../../web-admin/domain.js"),
        ),
        "style.css" => (
            "text/css; charset=utf-8",
            include_str!("../../../web-admin/style.css"),
        ),
        _ => return StatusCode::NOT_FOUND.into_response(),
    };
    document(mime, body)
}

fn document(mime: &str, body: &'static str) -> Response {
    (
        [
            (header::CONTENT_TYPE, mime),
            (header::CACHE_CONTROL, "no-store"),
            (header::CONTENT_SECURITY_POLICY, CSP),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
            (header::REFERRER_POLICY, "no-referrer"),
        ],
        body,
    )
        .into_response()
}

pub async fn index() -> Response {
    document(
        "text/html; charset=utf-8",
        include_str!("../../../web-admin/index.html"),
    )
}

pub async fn me(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<Value>, AppError> {
    let profile = auth_session::profile(&state, user.user_id).await?;
    Ok(Json(json!({
        "user_id": user.user_id.to_string(),
        "profile": profile,
        "admin": user.is_admin,
        "catalog_activation": "ADMIN_REVIEW"
    })))
}

pub async fn logout(
    state: State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    auth_session::handle_logout(state, headers).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cookies_are_http_only_and_secure_outside_loopback() {
        let mut h = HeaderMap::new();
        h.insert(header::HOST, "example.test".parse().unwrap());
        let remote =
            auth_session::build_web_cookie(&h, "http://example.test", "synthetic", 20).unwrap();
        let remote = remote.to_str().unwrap();
        assert!(remote.contains("HttpOnly"));
        assert!(remote.contains("SameSite=Strict"));
        assert!(remote.contains("; Secure"));

        h.insert(header::HOST, "127.0.0.1:8876".parse().unwrap());
        assert!(!auth_session::build_web_cookie(
            &h,
            "http://127.0.0.1:8876",
            "synthetic",
            20
        )
        .unwrap()
        .to_str()
        .unwrap()
        .contains("; Secure"));
    }
}
