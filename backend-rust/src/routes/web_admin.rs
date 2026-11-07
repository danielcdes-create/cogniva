use crate::{error::AppError, security, services::question_storage, AppState};
use axum::{
    extract::State,
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    Extension, Json,
};
use bson::doc;
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;

const CSP: &str="default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
pub async fn asset(axum::extract::Path(name): axum::extract::Path<String>) -> Response {
    let (mime, body) = match name.as_str() {
        "app.js" => (
            "text/javascript; charset=utf-8",
            include_str!("../../../web-admin/app.js"),
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

fn cookie(headers: &HeaderMap, token: &str, seconds: i64) -> Result<HeaderValue, AppError> {
    let host = headers
        .get(header::HOST)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let local = reqwest::Url::parse(&format!("http://{host}"))
        .ok()
        .is_some_and(|u| matches!(u.host_str(), Some("127.0.0.1" | "localhost" | "[::1]")));
    HeaderValue::from_str(&format!(
        "cogniva_web_session={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age={seconds}{}",
        if local { "" } else { "; Secure" }
    ))
    .map_err(|_| AppError::Internal("Cookie indisponível".into()))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Login {
    pub code: String,
}
pub async fn login(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<Login>,
) -> Result<Response, AppError> {
    if !security::same_origin(&headers) {
        return Err(AppError::Forbidden(
            "Abra o painel no endereço do Cogniva".into(),
        ));
    }
    let claims = security::verify(&state.config.jwt_secret, body.code.trim(), "web_login")?;
    if !security::is_admin(claims.user_id) {
        return Err(AppError::Forbidden(
            "A conta não tem acesso administrativo".into(),
        ));
    }
    // The operator proves identity before issuing a short-lived single-use web code.
    crate::routes::auth::profile(&state, claims.user_id).await?;
    question_storage::collection(&state.db, "used_web_login_codes")
        .insert_one(
            doc! {"_id":claims.nonce,"user_id":claims.user_id,"used_at":bson::DateTime::now()},
        )
        .await
        .map_err(|e| {
            if e.to_string().contains("E11000") {
                AppError::Unauthorized("Código já utilizado; solicite outro".into())
            } else {
                e.into()
            }
        })?;
    let token = security::issue(
        &state.config.jwt_secret,
        claims.user_id,
        "web_session",
        8 * 3600,
    )?;
    let session = security::verify(&state.config.jwt_secret, &token, "web_session")?;
    question_storage::collection(&state.db,"web_sessions").insert_one(doc!{"_id":session.nonce,"user_id":claims.user_id,"revoked":false,"created_at":bson::DateTime::now(),"expires_at":bson::DateTime::from_millis(session.exp as i64*1000)}).await?;
    let mut response = Json(
        json!({"authenticated":true,"user_id":claims.user_id.to_string(),"expires_at":session.exp}),
    )
    .into_response();
    response
        .headers_mut()
        .insert(header::SET_COOKIE, cookie(&headers, &token, 8 * 3600)?);
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(response)
}
pub async fn me(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<security::SessionUser>,
) -> Result<Json<Value>, AppError> {
    Ok(Json(
        json!({"user_id":user.0.to_string(),"profile":crate::routes::auth::profile(&state,user.0).await?,"admin":true,"catalog_activation":"ADMIN_REVIEW"}),
    ))
}
pub async fn logout(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    if !security::same_origin(&headers)
        || headers.get("x-cogniva-csrf").and_then(|v| v.to_str().ok()) != Some("1")
    {
        return Err(AppError::Forbidden(
            "Origem da ação web não autorizada".into(),
        ));
    }
    if let Some(token) = headers
        .get("cookie")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| {
            s.split(';')
                .find_map(|e| e.trim().strip_prefix("cogniva_web_session="))
        })
    {
        if let Ok(claims) = security::verify(&state.config.jwt_secret, token, "web_session") {
            question_storage::collection(&state.db, "web_sessions")
                .update_one(doc! {"_id":claims.nonce}, doc! {"$set":{"revoked":true}})
                .await?;
        }
    }
    let mut response = Json(json!({"authenticated":false})).into_response();
    response
        .headers_mut()
        .insert(header::SET_COOKIE, cookie(&headers, "", 0)?);
    Ok(response)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cookies_are_http_only_and_secure_outside_loopback() {
        let mut h = HeaderMap::new();
        h.insert(header::HOST, "example.test".parse().unwrap());
        let remote = cookie(&h, "synthetic", 20).unwrap();
        let remote = remote.to_str().unwrap();
        assert!(remote.contains("HttpOnly"));
        assert!(remote.contains("SameSite=Strict"));
        assert!(remote.contains("; Secure"));
        h.insert(header::HOST, "127.0.0.1:8876".parse().unwrap());
        assert!(!cookie(&h, "synthetic", 20)
            .unwrap()
            .to_str()
            .unwrap()
            .contains("; Secure"));
    }
}
