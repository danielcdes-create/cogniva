use crate::{error::AppError, AppState};
use axum::{
    body::{to_bytes, Body},
    extract::{Request, State},
    middleware::Next,
    response::Response,
};
use jsonwebtoken::{decode, encode, Algorithm, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    pub user_id: i64,
    pub exp: usize,
    pub purpose: String,
    #[serde(default)]
    pub nonce: String,
}

#[derive(Debug, Clone, Copy)]
pub struct SessionUser(pub i64);

pub fn is_admin(user_id: i64) -> bool {
    std::env::var("ADMIN_USER_IDS").unwrap_or_default().split(',')
        .any(|id| id.trim().parse::<i64>().ok() == Some(user_id))
}

pub fn same_origin(headers: &axum::http::HeaderMap) -> bool {
    let Some(host) = headers.get("host").and_then(|v|v.to_str().ok()) else { return false };
    let Some(origin) = headers.get("origin").and_then(|v|v.to_str().ok()) else { return false };
    reqwest::Url::parse(origin).ok().is_some_and(|url| {
        matches!(url.scheme(), "http" | "https") && url.username().is_empty() && url.password().is_none()
            && url.path()=="/" && url.query().is_none() && url.fragment().is_none()
            && reqwest::Url::parse(&format!("{}://{host}",url.scheme())).ok().is_some_and(|expected| expected.path()=="/" && expected.username().is_empty() && expected.origin()==url.origin())
    })
}

pub fn issue(secret: &str, user_id: i64, purpose: &str, seconds: i64) -> Result<String, AppError> {
    let claims = Claims {
        sub: user_id.to_string(),
        user_id,
        exp: (chrono::Utc::now().timestamp() + seconds) as usize,
        purpose: purpose.into(),
        nonce: uuid::Uuid::new_v4().to_string(),
    };
    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .map_err(|_| AppError::Internal("Não foi possível emitir a sessão".into()))
}

pub fn verify(secret: &str, token: &str, purpose: &str) -> Result<Claims, AppError> {
    let mut validation = Validation::new(Algorithm::HS256);
    validation.leeway = 0;
    let claims = decode::<Claims>(
        token,
        &DecodingKey::from_secret(secret.as_bytes()),
        &validation,
    )
    .map_err(|_| AppError::Unauthorized("Sessão inválida ou expirada".into()))?
    .claims;
    if claims.purpose != purpose || claims.user_id == 0 || claims.sub != claims.user_id.to_string()
    {
        return Err(AppError::Unauthorized(
            "Credencial não serve para esta operação".into(),
        ));
    }
    Ok(claims)
}

pub async fn authenticate(
    State(state): State<Arc<AppState>>,
    request: Request,
    next: Next,
) -> Result<Response, AppError> {
    let bearer = request
        .headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    let web_cookie = request.headers().get("cookie").and_then(|v|v.to_str().ok()).and_then(|cookies|cookies.split(';').find_map(|entry|entry.trim().strip_prefix("cogniva_web_session=")));
    let is_web = bearer.is_none() && web_cookie.is_some();
    let claims = if let Some(token)=bearer {
        verify(&state.config.jwt_secret, token, "session")?
    } else if let Some(token)=web_cookie {
        let claims=verify(&state.config.jwt_secret, token, "web_session")?;
        if !matches!(*request.method(),axum::http::Method::GET | axum::http::Method::HEAD | axum::http::Method::OPTIONS)
            && (!same_origin(request.headers()) || request.headers().get("x-cogniva-csrf").and_then(|v|v.to_str().ok())!=Some("1")) {
            return Err(AppError::Forbidden("Origem da ação web não autorizada".into()));
        }
        if crate::services::question_storage::collection(&state.db,"web_sessions").find_one(bson::doc!{"_id":&claims.nonce,"user_id":claims.user_id,"revoked":false}).await?.is_none() {
            return Err(AppError::Unauthorized("Sessão web encerrada".into()));
        }
        claims
    } else { return Err(AppError::Unauthorized("Entre na sua conta para sincronizar".into())); };
    if request.uri().path().starts_with("/api/v1/admin/") {
        if !is_admin(claims.user_id) {
            return Err(AppError::Forbidden("Operação administrativa".into()));
        }
    }
    if let Some(query) = request.uri().query() {
        let url = reqwest::Url::parse(&format!("http://localhost/?{query}"))
            .map_err(|_| AppError::BadRequest("Consulta inválida".into()))?;
        for (key, value) in url.query_pairs() {
            if key == "user_id" {
                if value.parse::<i64>().ok() != Some(claims.user_id) {
                    return Err(AppError::Forbidden("Identidade diferente da sessão".into()));
                }
            }
        }
    }
    let (mut parts, body) = request.into_parts();
    let bytes = to_bytes(body, 2 * 1024 * 1024)
        .await
        .map_err(|_| AppError::BadRequest("Requisição excede o limite".into()))?;
    if !bytes.is_empty() {
        if let Ok(json) = serde_json::from_slice::<serde_json::Value>(&bytes) {
            if let Some(id) = json.get("user_id") {
                if id.as_i64() != Some(claims.user_id) {
                    return Err(AppError::Forbidden("Identidade diferente da sessão".into()));
                }
            }
        }
    }
    parts.extensions.insert(SessionUser(claims.user_id));
    let catalog=state.catalog.refresh(&state.db).await?;
    let mut response = crate::services::catalog_runtime::scope(catalog,next.run(Request::from_parts(parts, Body::from(bytes)))).await;
    if is_web {
        response.headers_mut().insert(axum::http::header::CACHE_CONTROL,axum::http::HeaderValue::from_static("no-store"));
        response.headers_mut().append(axum::http::header::VARY,axum::http::HeaderValue::from_static("Cookie"));
    }
    Ok(response)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn browser_origin_and_token_purposes_are_separate() {
        let mut headers=axum::http::HeaderMap::new();
        headers.insert("host","127.0.0.1:8876".parse().unwrap());
        assert!(!same_origin(&headers));
        headers.insert("origin","http://127.0.0.1:8876".parse().unwrap());assert!(same_origin(&headers));
        headers.insert("origin","http://127.0.0.1:8877".parse().unwrap());assert!(!same_origin(&headers));
        headers.insert("origin","https://example.test".parse().unwrap());assert!(!same_origin(&headers));
        headers.insert("origin","null".parse().unwrap());assert!(!same_origin(&headers));
        let code=issue("synthetic-secret",123,"web_login",600).unwrap();
        assert!(verify("synthetic-secret",&code,"web_session").is_err());
        assert!(verify("synthetic-secret",&code,"session").is_err());
    }
    #[test]
    fn a_pairing_credential_cannot_be_used_as_session_or_with_another_key() {
        let token = issue("synthetic-secret", 123, "legacy_link", 600).unwrap();
        assert!(verify("synthetic-secret", &token, "legacy_link").is_ok());
        assert!(verify("synthetic-secret", &token, "session").is_err());
        assert!(verify("other-secret", &token, "legacy_link").is_err());
    }
}
