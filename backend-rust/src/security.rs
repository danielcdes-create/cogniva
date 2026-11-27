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
pub const MISSIONS_COOKIE_NAME: &str = "cogniva_missions_session";
pub const LEGACY_ADMIN_COOKIE_NAME: &str = "cogniva_admin_session";

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

pub fn extract_named_cookies(headers: &HeaderMap, cookie_name: &str) -> Vec<String> {
    let prefix = format!("{cookie_name}=");
    let mut found = Vec::new();
    for header_val in headers.get_all(header::COOKIE) {
        let Ok(raw) = header_val.to_str() else {
            continue;
        };
        for pair in raw.split(';') {
            let trimmed = pair.trim();
            if let Some(val) = trimmed.strip_prefix(&prefix) {
                let clean = val.trim().trim_matches('"').trim();
                if !clean.is_empty() && !found.iter().any(|existing| existing == clean) {
                    found.push(clean.to_string());
                }
            }
        }
    }
    found
}

pub fn extract_web_cookies_for_route(headers: &HeaderMap, prefer_missions: bool) -> Vec<String> {
    let web = extract_named_cookies(headers, WEB_COOKIE_NAME);
    let missions = extract_named_cookies(headers, MISSIONS_COOKIE_NAME);
    let legacy = extract_named_cookies(headers, LEGACY_ADMIN_COOKIE_NAME);
    let mut ordered = Vec::new();
    let groups = if prefer_missions {
        [missions, web, legacy]
    } else {
        [web, missions, legacy]
    };
    for grp in groups {
        for item in grp {
            if !ordered.iter().any(|existing| existing == &item) {
                ordered.push(item);
            }
        }
    }
    ordered
}

pub fn extract_web_cookies(headers: &HeaderMap) -> Vec<String> {
    extract_web_cookies_for_route(headers, false)
}

pub fn has_missions_cookie(headers: &HeaderMap) -> bool {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|raw| raw.split(';'))
        .map(str::trim)
        .any(|pair| pair.starts_with("cogniva_missions_session="))
}

pub fn has_legacy_admin_cookie(headers: &HeaderMap) -> bool {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|raw| raw.split(';'))
        .map(str::trim)
        .any(|pair| pair.starts_with("cogniva_admin_session="))
}

pub fn extract_bearer_token(headers: &HeaderMap) -> Option<String> {
    let raw = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())?
        .trim_start();
    if raw.len() > 7 && raw[..7].eq_ignore_ascii_case("bearer ") {
        let tok = raw[7..].trim();
        if !tok.is_empty() {
            return Some(tok.to_string());
        }
    }
    None
}

pub fn check_sec_fetch_site(headers: &HeaderMap) -> Result<(), AppError> {
    if let Some(raw) = headers.get("sec-fetch-site") {
        let site = raw
            .to_str()
            .map_err(|_| AppError::Forbidden("Cabeçalho Sec-Fetch-Site inválido".into()))?
            .trim()
            .to_ascii_lowercase();
        if !matches!(site.as_str(), "same-origin" | "none") {
            return Err(AppError::Forbidden(
                "Requisição cross-site bloqueada na sessão web".into(),
            ));
        }
    }
    Ok(())
}

type NormalizedOrigin = (String, String, Option<u16>);

fn parse_http_origin(raw: &str) -> Option<NormalizedOrigin> {
    let parsed = reqwest::Url::parse(raw.trim()).ok()?;
    if !matches!(parsed.scheme(), "http" | "https")
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return None;
    }
    let host = parsed.host_str()?.to_ascii_lowercase();
    Some((
        parsed.scheme().to_ascii_lowercase(),
        host,
        parsed.port_or_known_default(),
    ))
}

fn is_loopback_authority(host_header: &str) -> bool {
    reqwest::Url::parse(&format!("http://{}", host_header.trim()))
        .ok()
        .is_some_and(|u| matches!(u.host_str(), Some("127.0.0.1" | "localhost" | "[::1]")))
}

pub fn same_origin(headers: &HeaderMap, public_origin: &str) -> Result<(), AppError> {
    let candidate_raw = headers
        .get(header::ORIGIN)
        .or_else(|| headers.get(header::REFERER))
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"))
        .ok_or_else(|| AppError::Forbidden("Origem ausente na mutação da sessão web".into()))?;

    let candidate_origin = parse_http_origin(candidate_raw)
        .ok_or_else(|| AppError::Forbidden("Origem inválida na sessão web".into()))?;

    if let Some(expected_public) = parse_http_origin(public_origin) {
        if candidate_origin == expected_public {
            // Se estiver atrás de proxy TLS (`X-Forwarded-Proto`), exige que o esquema também coincida com o proxy.
            if let Some(xfp) = headers
                .get("x-forwarded-proto")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.split(',').next())
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                let proto = xfp.to_ascii_lowercase();
                if !matches!(proto.as_str(), "http" | "https") {
                    return Err(AppError::Forbidden(
                        "Protocolo encaminhado inválido na sessão web".into(),
                    ));
                }
                if candidate_origin.0 != proto {
                    return Err(AppError::Forbidden(
                        "Esquema da origem diverge do protocolo da sessão web".into(),
                    ));
                }
            }
            return Ok(());
        }
    }

    let public_is_loopback = parse_http_origin(public_origin)
        .is_none_or(|(_, h, _)| matches!(h.as_str(), "127.0.0.1" | "localhost" | "[::1]"));

    if let Some(host_raw) = headers.get(header::HOST).and_then(|v| v.to_str().ok()) {
        let host = host_raw.trim();
        let allow_host_fallback = is_loopback_authority(host) || public_is_loopback;
        if allow_host_fallback
            && !host.is_empty()
            && !host.contains('/')
            && !host.contains('\\')
            && !host.contains('@')
            && !host.contains(char::is_whitespace)
        {
            let expected_scheme = if let Some(xfp) = headers
                .get("x-forwarded-proto")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.split(',').next())
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                let lower = xfp.to_ascii_lowercase();
                match lower.as_str() {
                    "https" => "https",
                    "http" => "http",
                    _ => {
                        return Err(AppError::Forbidden(
                            "Protocolo encaminhado inválido na sessão web".into(),
                        ));
                    }
                }
            } else if is_loopback_authority(host) {
                "http"
            } else if public_origin
                .trim()
                .to_ascii_lowercase()
                .starts_with("https://")
            {
                "https"
            } else {
                "http"
            };

            if let Some(expected_host_origin) =
                parse_http_origin(&format!("{expected_scheme}://{host}"))
            {
                if candidate_origin == expected_host_origin {
                    return Ok(());
                }
            }
        }
    }

    Err(AppError::Forbidden("Origem inválida na sessão web".into()))
}

pub fn verify_csrf_same_origin(
    headers: &HeaderMap,
    method: &Method,
    public_origin: &str,
) -> Result<(), AppError> {
    check_sec_fetch_site(headers)?;

    if matches!(method, &Method::GET | &Method::HEAD | &Method::OPTIONS) {
        if headers.contains_key(header::ORIGIN) {
            same_origin(headers, public_origin)?;
        }
        return Ok(());
    }
    if headers
        .get("x-cogniva-csrf")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        != Some("1")
    {
        return Err(AppError::Forbidden(
            "Cabeçalho CSRF ausente na mutação da sessão web".into(),
        ));
    }
    same_origin(headers, public_origin)
}

async fn resolve_db_session(
    state: &AppState,
    claims: &TokenClaims,
    allow_deleting_account: bool,
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

    if user.deleting_account && !allow_deleting_account {
        return Err(AppError::Unauthorized(
            "Exclusão definitiva da conta em andamento".into(),
        ));
    }

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

pub fn apply_cookie_auth_response_headers(headers: &mut HeaderMap) {
    let keep_private_cache = headers
        .get(header::CACHE_CONTROL)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|cc| cc.to_ascii_lowercase().contains("private"));
    if !keep_private_cache {
        headers.insert(
            header::CACHE_CONTROL,
            axum::http::HeaderValue::from_static("private, no-store"),
        );
    }

    let mut parts: Vec<String> = Vec::new();
    let mut has_wildcard = false;
    let mut has_cookie = false;
    for val in headers.get_all(header::VARY) {
        if let Ok(raw) = val.to_str() {
            for item in raw.split(',') {
                let trimmed = item.trim();
                if trimmed.is_empty() {
                    continue;
                }
                if trimmed == "*" {
                    has_wildcard = true;
                }
                if trimmed.eq_ignore_ascii_case("cookie") {
                    has_cookie = true;
                }
                if !parts
                    .iter()
                    .any(|existing| existing.eq_ignore_ascii_case(trimmed))
                {
                    parts.push(trimmed.to_string());
                }
            }
        }
    }
    if !has_wildcard {
        if !has_cookie {
            parts.push("Cookie".to_string());
        }
        if let Ok(joined) = axum::http::HeaderValue::from_str(&parts.join(", ")) {
            headers.insert(header::VARY, joined);
        }
    }
}

pub async fn authenticate(
    State(state): State<std::sync::Arc<AppState>>,
    mut request: Request,
    next: Next,
) -> Result<Response, AppError> {
    let path = request.uri().path();
    let allow_deleting_account = (request.method() == Method::DELETE
        && path == "/api/v1/auth/account")
        || path.ends_with("/logout")
        || path.ends_with("/web-logout");
    let prefer_missions = !path.starts_with("/api/v1/admin");

    let mut used_cookie_auth = false;
    let user = if let Some(bearer) = extract_bearer_token(request.headers()) {
        let claims = verify_session_token(&state.config.jwt_secret, &bearer, "mobile")?;
        resolve_db_session(&state, &claims, allow_deleting_account).await?
    } else if request.headers().contains_key(header::AUTHORIZATION) {
        return Err(AppError::Unauthorized(
            "Cabeçalho Authorization inválido".into(),
        ));
    } else {
        let cookies = extract_web_cookies_for_route(request.headers(), prefer_missions);
        if cookies.is_empty() {
            return Err(AppError::Unauthorized("Sessão obrigatória".into()));
        }
        verify_csrf_same_origin(
            request.headers(),
            request.method(),
            &state.config.public_origin,
        )?;
        let mut resolved = None;
        let mut fallback_non_admin = None;
        for token in cookies {
            if let Ok(claims) = verify_session_token(&state.config.jwt_secret, &token, "web") {
                if let Ok(session_user) =
                    resolve_db_session(&state, &claims, allow_deleting_account).await
                {
                    if path.starts_with("/api/v1/admin") && !session_user.is_admin {
                        if fallback_non_admin.is_none() {
                            fallback_non_admin = Some(session_user);
                        }
                        continue;
                    }
                    resolved = Some(session_user);
                    break;
                }
            }
        }
        used_cookie_auth = true;
        resolved
            .or(fallback_non_admin)
            .ok_or_else(|| AppError::Unauthorized("Sessão web inválida ou expirada".into()))?
    };

    let catalog = state.catalog.refresh(&state.db).await?;
    request.extensions_mut().insert(user);
    let mut response = crate::services::catalog_runtime::scope(catalog, next.run(request)).await;
    if used_cookie_auth {
        apply_cookie_auth_response_headers(response.headers_mut());
    }
    Ok(response)
}

pub async fn require_admin(
    Extension(user): Extension<SessionUser>,
    request: Request,
    next: Next,
) -> Result<Response, AppError> {
    if !user.is_admin {
        return Err(AppError::forbidden_code(
            "REVIEW_PERMISSION_DENIED",
            None::<String>,
            "Acesso restrito a administradores",
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
            "cogniva_web_session=; other=1; cogniva_admin_session=legacy_tok"
                .parse()
                .unwrap(),
        );
        headers.append(
            header::COOKIE,
            "cogniva_web_session=stale; cogniva_web_session=fresh"
                .parse()
                .unwrap(),
        );
        assert_eq!(
            extract_web_cookies(&headers),
            vec!["stale", "fresh", "legacy_tok"]
        );

        let mut missions_headers = HeaderMap::new();
        missions_headers.append(
            header::COOKIE,
            "cogniva_web_session=admin_tok; cogniva_missions_session=student_tok"
                .parse()
                .unwrap(),
        );
        assert_eq!(
            extract_web_cookies_for_route(&missions_headers, true),
            vec!["student_tok", "admin_tok"]
        );
        assert_eq!(
            extract_web_cookies_for_route(&missions_headers, false),
            vec!["admin_tok", "student_tok"]
        );
        assert!(has_missions_cookie(&missions_headers));
    }

    #[test]
    fn csrf_same_origin_validates_scheme_host_referer_and_sec_fetch_site() {
        let mut h = HeaderMap::new();
        h.insert(header::HOST, "cogniva.example.org".parse().unwrap());
        h.insert("x-forwarded-proto", "https".parse().unwrap());
        h.insert("x-cogniva-csrf", "1".parse().unwrap());
        h.insert(
            header::ORIGIN,
            "http://cogniva.example.org".parse().unwrap(),
        );

        // Scheme downgrade http:// against X-Forwarded-Proto: https MUST fail
        assert!(verify_csrf_same_origin(&h, &Method::POST, "https://cogniva.example.org").is_err());

        // Matching https:// origin succeeds
        h.insert(
            header::ORIGIN,
            "https://cogniva.example.org".parse().unwrap(),
        );
        assert!(verify_csrf_same_origin(&h, &Method::POST, "https://cogniva.example.org").is_ok());

        // Referer fallback when Origin is absent
        h.remove(header::ORIGIN);
        h.insert(
            header::REFERER,
            "https://cogniva.example.org/admin/".parse().unwrap(),
        );
        assert!(
            verify_csrf_same_origin(&h, &Method::DELETE, "https://cogniva.example.org").is_ok()
        );

        // Sec-Fetch-Site: cross-site rejected even on safe GET or valid CSRF POST
        h.insert("sec-fetch-site", "cross-site".parse().unwrap());
        assert!(verify_csrf_same_origin(&h, &Method::GET, "https://cogniva.example.org").is_err());
        assert!(verify_csrf_same_origin(&h, &Method::POST, "https://cogniva.example.org").is_err());

        // Sec-Fetch-Site: same-origin allowed
        h.insert("sec-fetch-site", "same-origin".parse().unwrap());
        assert!(verify_csrf_same_origin(&h, &Method::GET, "https://cogniva.example.org").is_ok());
        assert!(verify_csrf_same_origin(&h, &Method::POST, "https://cogniva.example.org").is_ok());

        // Host-header spoofing against a configured non-loopback public_origin MUST fail
        h.insert(header::HOST, "evil.example.com".parse().unwrap());
        h.insert(header::ORIGIN, "https://evil.example.com".parse().unwrap());
        assert!(verify_csrf_same_origin(&h, &Method::POST, "https://cogniva.example.org").is_err());

        // Case-insensitive Bearer extraction
        let mut bh = HeaderMap::new();
        bh.insert(header::AUTHORIZATION, "bearer tok-abc".parse().unwrap());
        assert_eq!(extract_bearer_token(&bh).as_deref(), Some("tok-abc"));
        bh.insert(header::AUTHORIZATION, "BeArEr   tok-xyz  ".parse().unwrap());
        assert_eq!(extract_bearer_token(&bh).as_deref(), Some("tok-xyz"));
        bh.insert(header::AUTHORIZATION, "Basic dXNlcjpwYXNz".parse().unwrap());
        assert_eq!(extract_bearer_token(&bh), None);
    }

    #[test]
    fn cookie_auth_response_headers_consolidate_multiple_vary_headers_and_set_private_cache_control(
    ) {
        let mut headers = HeaderMap::new();
        headers.append(header::VARY, "Origin".parse().unwrap());
        headers.append(header::VARY, "Accept-Encoding".parse().unwrap());

        apply_cookie_auth_response_headers(&mut headers);

        let vary_vals: Vec<&str> = headers
            .get_all(header::VARY)
            .iter()
            .filter_map(|v| v.to_str().ok())
            .collect();
        assert_eq!(vary_vals, vec!["Origin, Accept-Encoding, Cookie"]);
        assert_eq!(
            headers
                .get(header::CACHE_CONTROL)
                .and_then(|v| v.to_str().ok()),
            Some("private, no-store")
        );

        // Idempotent when called a second time and preserves pre-existing `private` Cache-Control
        headers.insert(
            header::CACHE_CONTROL,
            "private, max-age=3600, no-transform".parse().unwrap(),
        );
        apply_cookie_auth_response_headers(&mut headers);
        assert_eq!(
            headers.get(header::VARY).and_then(|v| v.to_str().ok()),
            Some("Origin, Accept-Encoding, Cookie")
        );
        assert_eq!(
            headers
                .get(header::CACHE_CONTROL)
                .and_then(|v| v.to_str().ok()),
            Some("private, max-age=3600, no-transform")
        );

        // Wildcard Vary: * is preserved untouched
        let mut wild = HeaderMap::new();
        wild.insert(header::VARY, "*".parse().unwrap());
        apply_cookie_auth_response_headers(&mut wild);
        assert_eq!(
            wild.get(header::VARY).and_then(|v| v.to_str().ok()),
            Some("*")
        );
    }
}
