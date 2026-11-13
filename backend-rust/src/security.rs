use crate::{error::AppError, models::user::UserDocument, AppState};
use axum::{
    extract::{Request, State},
    http::{header, HeaderMap, Method},
    middleware::Next,
    response::Response,
    Extension,
};
use bson::{doc, Document};
use chrono::Utc;
use jsonwebtoken::{decode, encode, Algorithm, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

pub const MOBILE_SESSION_TTL_SECS: i64 = 30 * 24 * 60 * 60;
pub const WEB_SESSION_TTL_SECS: i64 = 12 * 60 * 60;
pub const WEB_COOKIE_NAME: &str = "cogniva_web_session";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SessionUser {
    pub user_id: i64,
    pub is_admin: bool,
    pub is_guest: bool,
}

impl SessionUser {
    pub fn id(self) -> i64 {
        self.user_id
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct TokenClaims {
    pub v: u8,
    pub uid: i64,
    pub sid: String,
    pub purpose: String,
    pub iat: i64,
    pub exp: i64,
}

pub fn sign_session_token(
    secret: &str,
    user_id: i64,
    session_id: &str,
    purpose: &str,
    ttl_seconds: i64,
) -> Result<String, AppError> {
    if secret.len() < 32 {
        return Err(AppError::Internal(
            "Segredo de sessão não configurado".into(),
        ));
    }
    if user_id <= 0
        || session_id.trim().is_empty()
        || !matches!(purpose, "mobile" | "web")
        || ttl_seconds <= 0
    {
        return Err(AppError::BadRequest(
            "Parâmetros inválidos para emissão de sessão".into(),
        ));
    }
    let now = Utc::now().timestamp();
    let claims = TokenClaims {
        v: 2,
        uid: user_id,
        sid: session_id.to_string(),
        purpose: purpose.to_string(),
        iat: now,
        exp: now + ttl_seconds,
    };
    encode(
        &Header::new(Algorithm::HS256),
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .map_err(|_| AppError::Internal("Falha ao assinar sessão".into()))
}

pub fn verify_session_token(
    secret: &str,
    token: &str,
    expected_purpose: &str,
) -> Result<TokenClaims, AppError> {
    if secret.len() < 32 {
        return Err(AppError::Unauthorized("Sessão indisponível".into()));
    }
    let mut validation = Validation::new(Algorithm::HS256);
    validation.validate_exp = false;
    validation.required_spec_claims.clear();
    let data = decode::<TokenClaims>(
        token.trim(),
        &DecodingKey::from_secret(secret.as_bytes()),
        &validation,
    )
    .map_err(|_| AppError::Unauthorized("Token de sessão inválido".into()))?;
    let claims = data.claims;
    let now = Utc::now().timestamp();
    if claims.v != 2
        || claims.uid <= 0
        || claims.sid.is_empty()
        || claims.purpose != expected_purpose
        || claims.exp <= now
        || claims.iat > now + 300
    {
        return Err(AppError::Unauthorized("Sessão expirada ou inválida".into()));
    }
    Ok(claims)
}

pub fn extract_web_cookies(headers: &HeaderMap) -> Vec<String> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|raw| raw.split(';'))
        .map(str::trim)
        .filter_map(|pair| pair.strip_prefix("cogniva_web_session="))
        .map(|val| val.trim().to_string())
        .filter(|val| !val.is_empty())
        .collect()
}

pub fn extract_bearer_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

pub fn verify_csrf_same_origin(
    headers: &HeaderMap,
    method: &Method,
    public_origin: &str,
) -> Result<(), AppError> {
    if matches!(method, &Method::GET | &Method::HEAD | &Method::OPTIONS) {
        return Ok(());
    }
    let origin = headers
        .get(header::ORIGIN)
        .and_then(|v| v.to_str().ok())
        .map(|v| v.trim().trim_end_matches('/'))
        .ok_or_else(|| AppError::Forbidden("Origem ausente na mutação da sessão web".into()))?;
    let expected = public_origin.trim().trim_end_matches('/');
    if origin == expected {
        return Ok(());
    }
    if let Some(host) = headers.get(header::HOST).and_then(|v| v.to_str().ok()) {
        let host = host.trim();
        let proto = headers
            .get("x-forwarded-proto")
            .and_then(|v| v.to_str().ok())
            .map(str::trim)
            .filter(|s| *s == "https" || *s == "http")
            .unwrap_or("http");
        if origin == format!("{proto}://{host}") || origin == format!("http://{host}") {
            return Ok(());
        }
    }
    Err(AppError::Forbidden(
        "Origem inválida na sessão web".into(),
    ))
}

async fn resolve_db_session(
    state: &AppState,
    claims: &TokenClaims,
) -> Result<SessionUser, AppError> {
    let now = bson::DateTime::now();
    let session = state
        .db
        .collection::<Document>("sessions")
        .find_one(doc! {
            "_id": &claims.sid,
            "user_id": claims.uid,
            "purpose": &claims.purpose,
            "revoked": false,
            "expires_at": { "$gt": now }
        })
        .await?
        .ok_or_else(|| AppError::Unauthorized("Sessão revogada ou expirada".into()))?;
    let _ = session;

    let user = state
        .db
        .collection::<UserDocument>("usuarios")
        .find_one(doc! { "user_id": claims.uid })
        .await?
        .ok_or_else(|| AppError::Unauthorized("Conta da sessão não encontrada".into()))?;

    let is_admin = user.is_admin()
        || user
            .email_normalized
            .as_deref()
            .is_some_and(|e| state.config.is_bootstrap_admin_email(e));

    Ok(SessionUser {
        user_id: user.user_id,
        is_admin,
        is_guest: user.is_guest,
    })
}

pub async fn authenticate(
    State(state): State< std::sync::Arc<AppState> >,
    mut request: Request,
    next: Next,
) -> Result<Response, AppError> {
    let user = if let Some(bearer) = extract_bearer_token(request.headers()) {
        let claims = verify_session_token(&state.config.jwt_secret, &bearer, "mobile")?;
        resolve_db_session(&state, &claims).await?
    } else {
        let cookies = extract_web_cookies(request.headers());
        if cookies.is_empty() {
            return Err(AppError::Unauthorized("Sessão obrigatória".into()));
        }
        verify_csrf_same_origin(
            request.headers(),
            request.method(),
            &state.config.public_origin,
        )?;
        let mut resolved = None;
        for token in cookies {
            if let Ok(claims) = verify_session_token(&state.config.jwt_secret, &token, "web") {
                if let Ok(session_user) = resolve_db_session(&state, &claims).await {
                    resolved = Some(session_user);
                    break;
                }
            }
        }
        resolved.ok_or_else(|| AppError::Unauthorized("Sessão web inválida ou expirada".into()))?
    };

    let catalog = state.catalog.refresh(&state.db).await?;
    request.extensions_mut().insert(user);
    Ok(crate::services::catalog_runtime::scope(catalog, next.run(request)).await)
}

pub async fn require_admin(
    Extension(user): Extension<SessionUser>,
    request: Request,
    next: Next,
) -> Result<Response, AppError> {
    if !user.is_admin {
        return Err(AppError::Forbidden(
            "Acesso restrito a administradores".into(),
        ));
    }
    Ok(next.run(request).await)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "0123456789abcdef0123456789abcdef";

    #[test]
    fn signs_verifies_and_separates_mobile_and_web_purposes() {
        let mobile = sign_session_token(SECRET, 42, "sid-1", "mobile", 3600).unwrap();
        let claims = verify_session_token(SECRET, &mobile, "mobile").unwrap();
        assert_eq!(claims.uid, 42);
        assert_eq!(claims.sid, "sid-1");
        assert!(verify_session_token(SECRET, &mobile, "web").is_err());

        let mut headers = HeaderMap::new();
        headers.append(
            header::COOKIE,
            "cogniva_web_session=stale; other=1; cogniva_web_session=fresh"
                .parse()
                .unwrap(),
        );
        assert_eq!(extract_web_cookies(&headers), vec!["stale", "fresh"]);
    }
}
