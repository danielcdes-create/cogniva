use crate::{
    error::AppError,
    models::user::{UserDocument, UserProfileDto},
    security::{
        self, SessionUser, MOBILE_SESSION_TTL_SECS, WEB_COOKIE_NAME, WEB_SESSION_TTL_SECS,
    },
    services::{
        rate_limit,
        scheduler::{run_daily_reviews_decay_and_countdown, DailyMaintenanceReport},
    },
    AppState,
};
use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{
    body::Bytes,
    extract::{ConnectInfo, State},
    http::{header, HeaderMap, HeaderValue, Method},
    response::{IntoResponse, Response},
    Extension, Json,
};
use bson::{doc, Document};
use mongodb::Database;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::{net::SocketAddr, sync::Arc, sync::OnceLock};

const MAX_SAFE_JS_INT: u64 = 9_007_199_254_740_991;
const MAX_PASSWORD_BYTES: usize = 1024;
const MAX_CONCURRENT_ARGON2: usize = 3;

static DUMMY_PASSWORD_HASH: OnceLock<String> = OnceLock::new();
static ARGON2_SEMAPHORE: OnceLock<Arc<tokio::sync::Semaphore>> = OnceLock::new();

fn argon2_semaphore() -> Arc<tokio::sync::Semaphore> {
    ARGON2_SEMAPHORE
        .get_or_init(|| Arc::new(tokio::sync::Semaphore::new(MAX_CONCURRENT_ARGON2)))
        .clone()
}

fn dummy_password_hash() -> &'static str {
    DUMMY_PASSWORD_HASH.get_or_init(|| {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(b"cogniva-dummy-constant-time-password", &salt)
            .expect("dummy argon2 hash")
            .to_string()
    })
}

#[derive(Debug, Default, Deserialize)]
pub struct GuestSessionRequest {
    pub installation_id: Option<String>,
    pub installation_secret: Option<String>,
    pub display_name: Option<String>,
    pub client: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub email: String,
    pub password: String,
    pub display_name: Option<String>,
    pub client: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub email: String,
    pub password: String,
    pub client: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct DeleteAccountRequest {
    #[serde(default)]
    pub confirm: String,
}

#[derive(Debug, Serialize)]
pub struct AuthSessionResponse {
    pub token: String,
    pub expires_in_days: i64,
    pub profile: UserProfileDto,
    pub historical_questions_count: u64,
}

#[derive(Debug, Deserialize)]
pub struct OnboardingUpdateRequest {
    #[serde(default)]
    pub user_id: Option<i64>,
    pub active_contest_ids: Vec<String>,
    pub daily_questions_target: i32,
    pub daily_study_hours_target: f64,
    pub display_name: Option<String>,
}

pub fn guest_user_id(installation: uuid::Uuid, secret: &str) -> i64 {
    let digest = Sha256::digest(format!("cogniva-guest-v2:{installation}:{secret}").as_bytes());
    let raw = u64::from_be_bytes(digest[..8].try_into().unwrap()) & MAX_SAFE_JS_INT;
    raw.max(1) as i64
}

pub fn random_safe_user_id() -> i64 {
    let raw = rand::random::<u64>() & MAX_SAFE_JS_INT;
    raw.max(1) as i64
}

fn hash_secret(secret: &str) -> String {
    format!("{:x}", Sha256::digest(secret.as_bytes()))
}

fn equal_hash(a: &str, b: &str) -> bool {
    a.len() == b.len()
        && a.bytes()
            .zip(b.bytes())
            .fold(0u8, |different, (x, y)| different | (x ^ y))
            == 0
}

pub fn normalize_and_validate_email(raw: &str) -> Result<(String, String), AppError> {
    let trimmed = raw.trim();
    if trimmed.len() < 5 || trimmed.len() > 254 || trimmed.contains(char::is_whitespace) {
        return Err(AppError::BadRequest("Informe um e-mail válido".into()));
    }
    let Some((local, domain)) = trimmed.split_once('@') else {
        return Err(AppError::BadRequest("Informe um e-mail válido".into()));
    };
    if local.is_empty()
        || domain.len() < 3
        || !domain.contains('.')
        || domain.starts_with('.')
        || domain.ends_with('.')
        || domain.contains('@')
    {
        return Err(AppError::BadRequest("Informe um e-mail válido".into()));
    }
    Ok((trimmed.to_string(), trimmed.to_lowercase()))
}

pub fn validate_password_strength(password: &str) -> Result<(), AppError> {
    if password.len() > MAX_PASSWORD_BYTES {
        return Err(AppError::BadRequest(
            "A senha deve ter entre 8 e 128 caracteres".into(),
        ));
    }
    let chars = password.chars().count();
    if !(8..=128).contains(&chars) {
        return Err(AppError::BadRequest(
            "A senha deve ter entre 8 e 128 caracteres".into(),
        ));
    }
    Ok(())
}

async fn acquire_argon2_permit() -> Result<tokio::sync::OwnedSemaphorePermit, AppError> {
    tokio::time::timeout(
        std::time::Duration::from_secs(5),
        argon2_semaphore().acquire_owned(),
    )
    .await
    .map_err(|_| {
        AppError::TemporarilyUnavailable(
            "Servidor ocupado processando autenticações. Tente novamente em instantes.".into(),
        )
    })?
    .map_err(|_| AppError::Internal("Semáforo de criptografia indisponível".into()))
}

pub async fn hash_password(password: String) -> Result<String, AppError> {
    if password.len() > MAX_PASSWORD_BYTES {
        return Err(AppError::BadRequest(
            "A senha deve ter entre 8 e 128 caracteres".into(),
        ));
    }
    let _permit = acquire_argon2_permit().await?;
    tokio::task::spawn_blocking(move || {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|h| h.to_string())
            .map_err(|_| AppError::Internal("Falha ao proteger credencial".into()))
    })
    .await
    .map_err(|_| AppError::Internal("Falha na tarefa de criptografia".into()))?
}

pub async fn verify_password(password: String, password_hash: String) -> Result<bool, AppError> {
    if password.len() > MAX_PASSWORD_BYTES {
        return Ok(false);
    }
    let _permit = acquire_argon2_permit().await?;
    tokio::task::spawn_blocking(move || {
        let Ok(parsed) = PasswordHash::new(&password_hash) else {
            return Ok(false);
        };
        Ok(Argon2::default()
            .verify_password(password.as_bytes(), &parsed)
            .is_ok())
    })
    .await
    .map_err(|_| AppError::Internal("Falha na verificação de credencial".into()))?
}

pub fn build_named_web_cookie(
    headers: &HeaderMap,
    public_origin: &str,
    cookie_name: &str,
    token: &str,
    max_age_secs: i64,
) -> Result<HeaderValue, AppError> {
    let host = headers
        .get(header::HOST)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let local_host = reqwest::Url::parse(&format!("http://{host}"))
        .ok()
        .is_some_and(|u| matches!(u.host_str(), Some("127.0.0.1" | "localhost" | "[::1]")));
    let proto_https = headers
        .get("x-forwarded-proto")
        .and_then(|v| v.to_str().ok())
        .and_then(|p| p.split(',').next())
        .is_some_and(|p| p.trim().eq_ignore_ascii_case("https"));
    let origin_https = public_origin.trim().starts_with("https://");
    let secure = proto_https || origin_https || !local_host;
    HeaderValue::from_str(&format!(
        "{cookie_name}={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age={max_age_secs}{}",
        if secure { "; Secure" } else { "" }
    ))
    .map_err(|_| AppError::Internal("Cookie de sessão indisponível".into()))
}

pub fn build_web_cookie(
    headers: &HeaderMap,
    public_origin: &str,
    token: &str,
    max_age_secs: i64,
) -> Result<HeaderValue, AppError> {
    build_named_web_cookie(headers, public_origin, WEB_COOKIE_NAME, token, max_age_secs)
}

pub async fn issue_db_session(
    state: &AppState,
    user_id: i64,
    purpose: &str,
) -> Result<(String, i64), AppError> {
    let ttl = if purpose == "web" {
        WEB_SESSION_TTL_SECS
    } else {
        MOBILE_SESSION_TTL_SECS
    };
    let sid = uuid::Uuid::new_v4().to_string();
    let now = chrono::Utc::now();
    let expires_at = bson::DateTime::from_chrono(now + chrono::Duration::seconds(ttl));
    state
        .db
        .collection::<Document>("sessions")
        .insert_one(doc! {
            "_id": &sid,
            "user_id": user_id,
            "purpose": purpose,
            "revoked": false,
            "created_at": bson::DateTime::from_chrono(now),
            "expires_at": expires_at,
        })
        .await?;
    let token = security::sign_session_token(&state.config.jwt_secret, user_id, &sid, purpose, ttl)?;
    Ok((token, ttl))
}

pub async fn load_user(db: &Database, user_id: i64) -> Result<UserDocument, AppError> {
    db.collection::<UserDocument>("usuarios")
        .find_one(doc! { "user_id": user_id })
        .await?
        .ok_or_else(|| AppError::NotFound("Perfil não encontrado".into()))
}

pub async fn profile(state: &AppState, user_id: i64) -> Result<UserProfileDto, AppError> {
    let mut user = load_user(&state.db, user_id).await?;
    if !user.is_admin()
        && user
            .email_normalized
            .as_deref()
            .is_some_and(|e| state.config.is_bootstrap_admin_email(e))
    {
        user.role = "admin".to_string();
    }
    Ok(user.to_profile_dto())
}

async fn revoke_incoming_web_cookies(state: &AppState, headers: &HeaderMap) {
    for prev_token in security::extract_web_cookies(headers) {
        if let Ok(claims) =
            security::verify_session_token(&state.config.jwt_secret, &prev_token, "web")
        {
            let _ = state
                .db
                .collection::<Document>("sessions")
                .update_one(
                    doc! { "_id": claims.sid, "user_id": claims.uid },
                    doc! { "$set": { "revoked": true } },
                )
                .await;
        }
    }
}

async fn build_auth_response(
    state: &AppState,
    user: &UserDocument,
    purpose: &str,
    headers: &HeaderMap,
) -> Result<Response, AppError> {
    if purpose == "web" {
        revoke_incoming_web_cookies(state, headers).await;
    }
    let (token, ttl) = issue_db_session(state, user.user_id, purpose).await?;
    let historical_questions_count = state
        .db
        .collection::<Document>("userActivityLog")
        .count_documents(doc! { "user_id": user.user_id })
        .await?;
    let mut dto = user.to_profile_dto();
    if user
        .email_normalized
        .as_deref()
        .is_some_and(|e| state.config.is_bootstrap_admin_email(e))
    {
        dto.role = "admin".to_string();
        dto.is_admin = true;
    }

    if purpose == "web" {
        let mut resp = Json(json!({
            "authenticated": true,
            "user_id": dto.user_id.to_string(),
            "profile": dto,
            "expires_in_seconds": ttl,
        }))
        .into_response();
        resp.headers_mut().insert(
            header::SET_COOKIE,
            build_web_cookie(headers, &state.config.public_origin, &token, ttl)?,
        );
        resp.headers_mut()
            .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
        Ok(resp)
    } else {
        let mut resp = Json(AuthSessionResponse {
            token,
            expires_in_days: ttl / 86_400,
            profile: dto,
            historical_questions_count,
        })
        .into_response();
        resp.headers_mut()
            .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
        Ok(resp)
    }
}

/// `POST /api/v1/auth/guest` (e alias `/api/v1/auth/native-session`): cria ou retoma conta convidada.
pub async fn handle_guest_session(
    State(state): State<Arc<AppState>>,
    connect_info: Option<ConnectInfo<SocketAddr>>,
    headers: HeaderMap,
    Json(req): Json<GuestSessionRequest>,
) -> Result<Response, AppError> {
    let client_ip = rate_limit::extract_client_ip(&headers, connect_info.map(|c| c.0.ip()));
    rate_limit::check_ip_rate_limit("auth_guest_ip", client_ip, 20, 60)?;

    let is_student_web = req
        .client
        .as_deref()
        .is_some_and(|c| c.eq_ignore_ascii_case("student_web"));
    if is_student_web {
        security::verify_csrf_same_origin(&headers, &Method::POST, &state.config.public_origin)?;
    }

    let users = state.db.collection::<UserDocument>("usuarios");
    let now = bson::DateTime::now();

    let user = match (&req.installation_id, &req.installation_secret) {
        (Some(inst_raw), Some(secret)) => {
            let inst = uuid::Uuid::parse_str(inst_raw)
                .map_err(|_| AppError::BadRequest("Instalação inválida".into()))?;
            if secret.len() != 64 || !secret.bytes().all(|c| c.is_ascii_hexdigit()) {
                return Err(AppError::BadRequest(
                    "Credencial da instalação inválida".into(),
                ));
            }
            rate_limit::check_auth_rate_limit(&state.db, "guest", &inst.to_string(), 30, 60)
                .await?;
            let secret_hash = hash_secret(secret);
            if let Some(existing) = users
                .find_one(doc! { "installation_id": inst.to_string() })
                .await?
            {
                let stored_hash = existing.installation_secret_sha256.as_deref().unwrap_or("");
                if !equal_hash(stored_hash, &secret_hash) {
                    return Err(AppError::Unauthorized(
                        "Esta instalação já possui outra credencial".into(),
                    ));
                }
                if !existing.is_guest {
                    return Err(AppError::Unauthorized(
                        "Esta conta está protegida por e-mail e senha. Faça login para continuar."
                            .into(),
                    ));
                }
                state
                    .db
                    .collection::<Document>("usuarios")
                    .update_one(
                        doc! { "user_id": existing.user_id },
                        doc! { "$set": { "updated_at": now } },
                    )
                    .await?;
                existing
            } else {
                let mut uid = guest_user_id(inst, secret);
                while users.find_one(doc! { "user_id": uid }).await?.is_some() {
                    uid = random_safe_user_id();
                }
                let doc = UserDocument {
                    id: None,
                    user_id: uid,
                    email: None,
                    email_normalized: None,
                    password_hash: None,
                    display_name: Some(
                        req.display_name
                            .as_deref()
                            .map(str::trim)
                            .filter(|s| !s.is_empty())
                            .unwrap_or("Estudante")
                            .chars()
                            .take(120)
                            .collect(),
                    ),
                    role: "student".to_string(),
                    is_guest: true,
                    installation_id: Some(inst.to_string()),
                    installation_secret_sha256: Some(secret_hash.clone()),
                    active_contest_ids: vec!["CPNU".to_string()],
                    daily_questions_target: 30,
                    daily_study_hours_target: 3.0,
                    onboarding_completed: false,
                    created_at: Some(now),
                    updated_at: Some(now),
                };
                match users.insert_one(&doc).await {
                    Ok(_) => doc,
                    Err(e) if e.to_string().contains("E11000") => {
                        let raced = users
                            .find_one(doc! { "installation_id": inst.to_string() })
                            .await?
                            .ok_or_else(|| {
                                AppError::Conflict("Conflito ao iniciar sessão convidada".into())
                            })?;
                        let stored = raced.installation_secret_sha256.as_deref().unwrap_or("");
                        if !equal_hash(stored, &secret_hash) || !raced.is_guest {
                            return Err(AppError::Unauthorized(
                                "Esta instalação já possui outra credencial".into(),
                            ));
                        }
                        raced
                    }
                    Err(e) => return Err(e.into()),
                }
            }
        }
        (None, None) => {
            rate_limit::check_auth_rate_limit(
                &state.db,
                "guest_anon",
                &client_ip.to_string(),
                20,
                60,
            )
            .await?;
            let mut uid = random_safe_user_id();
            while users.find_one(doc! { "user_id": uid }).await?.is_some() {
                uid = random_safe_user_id();
            }
            let doc = UserDocument {
                id: None,
                user_id: uid,
                email: None,
                email_normalized: None,
                password_hash: None,
                display_name: Some(
                    req.display_name
                        .as_deref()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .unwrap_or("Estudante")
                        .chars()
                        .take(120)
                        .collect(),
                ),
                role: "student".to_string(),
                is_guest: true,
                installation_id: None,
                installation_secret_sha256: None,
                active_contest_ids: vec!["CPNU".to_string()],
                daily_questions_target: 30,
                daily_study_hours_target: 3.0,
                onboarding_completed: false,
                created_at: Some(now),
                updated_at: Some(now),
            };
            users.insert_one(&doc).await?;
            doc
        }
        _ => {
            return Err(AppError::BadRequest(
                "Credencial da instalação incompleta".into(),
            ))
        }
    };

    let purpose = if is_student_web { "web" } else { "mobile" };
    build_auth_response(&state, &user, purpose, &headers).await
}

/// `POST /api/v1/auth/register`: cadastra e-mail + senha Argon2id ou promove conta convidada in-place.
pub async fn handle_register(
    State(state): State<Arc<AppState>>,
    connect_info: Option<ConnectInfo<SocketAddr>>,
    headers: HeaderMap,
    Json(req): Json<RegisterRequest>,
) -> Result<Response, AppError> {
    let client_ip = rate_limit::extract_client_ip(&headers, connect_info.map(|c| c.0.ip()));
    rate_limit::check_ip_rate_limit("auth_register_ip", client_ip, 10, 60)?;

    let is_admin_web = req
        .client
        .as_deref()
        .is_some_and(|c| c.eq_ignore_ascii_case("web"));
    let is_student_web = req
        .client
        .as_deref()
        .is_some_and(|c| c.eq_ignore_ascii_case("student_web"));
    let is_web = is_admin_web || is_student_web;
    if is_web {
        security::verify_csrf_same_origin(&headers, &Method::POST, &state.config.public_origin)?;
    }

    let (email_clean, email_norm) = normalize_and_validate_email(&req.email)?;
    validate_password_strength(&req.password)?;
    rate_limit::check_auth_rate_limit(&state.db, "register", &email_norm, 10, 300).await?;

    let is_bootstrap_admin = state.config.is_bootstrap_admin_email(&email_norm);
    if is_admin_web && !is_bootstrap_admin {
        return Err(AppError::Forbidden(
            "Apenas e-mails autorizados em ADMIN_EMAILS podem inaugurar acesso administrativo pelo navegador."
                .into(),
        ));
    }

    let users_raw = state.db.collection::<Document>("usuarios");
    if users_raw
        .find_one(doc! { "email_normalized": &email_norm })
        .await?
        .is_some()
    {
        return Err(AppError::Conflict(
            "Este e-mail já está cadastrado. Entre com sua senha.".into(),
        ));
    }

    let password_hash = hash_password(req.password).await?;
    let now = bson::DateTime::now();
    let role = if is_bootstrap_admin {
        "admin".to_string()
    } else {
        "student".to_string()
    };

    // Verifica se há sessão Bearer ou Cookie convidada ativa para upgrade in-place (preservando todo o progresso!)
    let mut upgraded_user: Option<UserDocument> = None;
    let mut active_guest_claims: Option<security::TokenClaims> = None;
    if let Some(bearer) = security::extract_bearer_token(&headers) {
        if let Ok(claims) =
            security::verify_session_token(&state.config.jwt_secret, &bearer, "mobile")
        {
            let active_session = state
                .db
                .collection::<Document>("sessions")
                .find_one(doc! {
                    "_id": &claims.sid,
                    "user_id": claims.uid,
                    "purpose": "mobile",
                    "revoked": false,
                    "expires_at": { "$gt": now }
                })
                .await?;
            if active_session.is_some() {
                active_guest_claims = Some(claims);
            }
        }
    } else if is_student_web {
        for cookie_tok in security::extract_web_cookies(&headers) {
            if let Ok(claims) =
                security::verify_session_token(&state.config.jwt_secret, &cookie_tok, "web")
            {
                let active_session = state
                    .db
                    .collection::<Document>("sessions")
                    .find_one(doc! {
                        "_id": &claims.sid,
                        "user_id": claims.uid,
                        "purpose": "web",
                        "revoked": false,
                        "expires_at": { "$gt": now }
                    })
                    .await?;
                if active_session.is_some() {
                    active_guest_claims = Some(claims);
                    break;
                }
            }
        }
    }

    if let Some(claims) = active_guest_claims {
        {
            {
                if let Ok(existing) = load_user(&state.db, claims.uid).await {
                    if existing.is_guest && existing.email_normalized.is_none() {
                        let mut set_doc = doc! {
                            "email": &email_clean,
                            "email_normalized": &email_norm,
                            "password_hash": &password_hash,
                            "is_guest": false,
                            "role": &role,
                            "updated_at": now,
                        };
                        if let Some(name) = req
                            .display_name
                            .as_deref()
                            .map(str::trim)
                            .filter(|s| !s.is_empty())
                        {
                            set_doc.insert(
                                "display_name",
                                name.chars().take(120).collect::<String>(),
                            );
                        }
                        let unset_guest_inst = doc! {
                            "installation_id": "",
                            "installation_secret_sha256": "",
                        };
                        let res = users_raw
                            .update_one(
                                doc! {
                                    "user_id": existing.user_id,
                                    "is_guest": true,
                                    "email_normalized": { "$exists": false }
                                },
                                doc! { "$set": set_doc, "$unset": unset_guest_inst.clone() },
                            )
                            .await;
                        match res {
                            Ok(r) if r.matched_count == 1 => {
                                let _ = state
                                    .db
                                    .collection::<Document>("sessions")
                                    .update_many(
                                        doc! { "user_id": existing.user_id },
                                        doc! { "$set": { "revoked": true } },
                                    )
                                    .await;
                                upgraded_user = Some(load_user(&state.db, existing.user_id).await?);
                            }
                            Ok(_) => {
                                // Caso email_normalized estivesse gravado como null explícito
                                let mut retry_set = doc! {
                                    "email": &email_clean,
                                    "email_normalized": &email_norm,
                                    "password_hash": &password_hash,
                                    "is_guest": false,
                                    "role": &role,
                                    "updated_at": now,
                                };
                                if let Some(name) = req
                                    .display_name
                                    .as_deref()
                                    .map(str::trim)
                                    .filter(|s| !s.is_empty())
                                {
                                    retry_set.insert(
                                        "display_name",
                                        name.chars().take(120).collect::<String>(),
                                    );
                                }
                                let r2 = users_raw
                                    .update_one(
                                        doc! {
                                            "user_id": existing.user_id,
                                            "is_guest": true,
                                            "email_normalized": bson::Bson::Null
                                        },
                                        doc! { "$set": retry_set, "$unset": unset_guest_inst },
                                    )
                                    .await
                                    .map_err(|e| {
                                        if e.to_string().contains("E11000") {
                                            AppError::Conflict(
                                                "Este e-mail já está cadastrado.".into(),
                                            )
                                        } else {
                                            e.into()
                                        }
                                    })?;
                                if r2.matched_count == 1 {
                                    let _ = state
                                        .db
                                        .collection::<Document>("sessions")
                                        .update_many(
                                            doc! { "user_id": existing.user_id },
                                            doc! { "$set": { "revoked": true } },
                                        )
                                        .await;
                                    upgraded_user =
                                        Some(load_user(&state.db, existing.user_id).await?);
                                }
                            }
                            Err(e) if e.to_string().contains("E11000") => {
                                return Err(AppError::Conflict(
                                    "Este e-mail já está cadastrado.".into(),
                                ));
                            }
                            Err(e) => return Err(e.into()),
                        }
                    }
                }
            }
        }
    }

    let user = if let Some(u) = upgraded_user {
        u
    } else {
        let users = state.db.collection::<UserDocument>("usuarios");
        let mut uid = random_safe_user_id();
        while users.find_one(doc! { "user_id": uid }).await?.is_some() {
            uid = random_safe_user_id();
        }
        let default_name = email_clean.split('@').next().unwrap_or("Estudante");
        let display = req
            .display_name
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or(default_name)
            .chars()
            .take(120)
            .collect::<String>();
        let new_user = UserDocument {
            id: None,
            user_id: uid,
            email: Some(email_clean),
            email_normalized: Some(email_norm),
            password_hash: Some(password_hash),
            display_name: Some(display),
            role,
            is_guest: false,
            installation_id: None,
            installation_secret_sha256: None,
            active_contest_ids: vec!["CPNU".to_string()],
            daily_questions_target: 30,
            daily_study_hours_target: 3.0,
            onboarding_completed: false,
            created_at: Some(now),
            updated_at: Some(now),
        };
        users.insert_one(&new_user).await.map_err(|e| {
            if e.to_string().contains("E11000") {
                AppError::Conflict("Este e-mail já está cadastrado.".into())
            } else {
                e.into()
            }
        })?;
        new_user
    };

    let purpose = if is_web { "web" } else { "mobile" };
    build_auth_response(&state, &user, purpose, &headers).await
}

/// `POST /api/v1/auth/login`: autentica e-mail + senha Argon2id (mobile Bearer ou web cookie).
pub async fn handle_login(
    State(state): State<Arc<AppState>>,
    connect_info: Option<ConnectInfo<SocketAddr>>,
    headers: HeaderMap,
    Json(req): Json<LoginRequest>,
) -> Result<Response, AppError> {
    let client_ip = rate_limit::extract_client_ip(&headers, connect_info.map(|c| c.0.ip()));
    rate_limit::check_ip_rate_limit("auth_login_ip", client_ip, 15, 60)?;
    login_inner(state, headers, req).await
}

/// `POST /api/v1/auth/web-session`: autentica sessão administrativa web com cookie HttpOnly + CSRF.
pub async fn handle_web_session(
    State(state): State<Arc<AppState>>,
    connect_info: Option<ConnectInfo<SocketAddr>>,
    headers: HeaderMap,
    Json(mut req): Json<LoginRequest>,
) -> Result<Response, AppError> {
    let client_ip = rate_limit::extract_client_ip(&headers, connect_info.map(|c| c.0.ip()));
    rate_limit::check_ip_rate_limit("auth_web_session_ip", client_ip, 10, 60)?;
    req.client = Some("web".to_string());
    login_inner(state, headers, req).await
}

async fn login_inner(
    state: Arc<AppState>,
    headers: HeaderMap,
    req: LoginRequest,
) -> Result<Response, AppError> {
    let is_admin_web = req
        .client
        .as_deref()
        .is_some_and(|c| c.eq_ignore_ascii_case("web"));
    let is_student_web = req
        .client
        .as_deref()
        .is_some_and(|c| c.eq_ignore_ascii_case("student_web"));
    let is_web = is_admin_web || is_student_web;
    if is_web {
        security::verify_csrf_same_origin(&headers, &Method::POST, &state.config.public_origin)?;
    }

    let (_, email_norm) = normalize_and_validate_email(&req.email)?;
    if req.password.len() > MAX_PASSWORD_BYTES {
        return Err(AppError::Unauthorized("E-mail ou senha incorretos".into()));
    }
    rate_limit::check_auth_rate_limit(&state.db, "login", &email_norm, 10, 300).await?;

    let found = state
        .db
        .collection::<UserDocument>("usuarios")
        .find_one(doc! { "email_normalized": &email_norm })
        .await?;

    let candidate_hash = found
        .as_ref()
        .and_then(|u| u.password_hash.clone())
        .unwrap_or_else(|| dummy_password_hash().to_string());

    let ok = verify_password(req.password, candidate_hash).await?;
    let Some(mut user) = found.filter(|u| ok && u.password_hash.is_some()) else {
        return Err(AppError::Unauthorized("E-mail ou senha incorretos".into()));
    };

    if !user.is_admin() && state.config.is_bootstrap_admin_email(&email_norm) {
        user.role = "admin".to_string();
        let _ = state
            .db
            .collection::<Document>("usuarios")
            .update_one(
                doc! { "user_id": user.user_id },
                doc! { "$set": { "role": "admin", "updated_at": bson::DateTime::now() } },
            )
            .await;
    }

    if is_admin_web && !user.is_admin() {
        return Err(AppError::Forbidden(
            "A conta autenticada não possui permissão administrativa".into(),
        ));
    }

    let _ = state
        .db
        .collection::<Document>("usuarios")
        .update_one(
            doc! { "user_id": user.user_id },
            doc! { "$set": { "updated_at": bson::DateTime::now() } },
        )
        .await;

    let purpose = if is_web { "web" } else { "mobile" };
    build_auth_response(&state, &user, purpose, &headers).await
}

/// `POST /api/v1/auth/logout`: revoga sessão Bearer e/ou cookie web na coleção `sessions`.
pub async fn handle_logout(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    let cookies = security::extract_web_cookies(&headers);
    let had_web_cookies = !cookies.is_empty();
    if had_web_cookies {
        security::verify_csrf_same_origin(&headers, &Method::POST, &state.config.public_origin)?;
        for token in cookies {
            if let Ok(claims) = security::verify_session_token(&state.config.jwt_secret, &token, "web") {
                let _ = state
                    .db
                    .collection::<Document>("sessions")
                    .update_one(
                        doc! { "_id": claims.sid, "user_id": claims.uid },
                        doc! { "$set": { "revoked": true } },
                    )
                    .await;
            }
        }
    }

    if let Some(bearer) = security::extract_bearer_token(&headers) {
        if let Ok(claims) =
            security::verify_session_token(&state.config.jwt_secret, &bearer, "mobile")
        {
            let _ = state
                .db
                .collection::<Document>("sessions")
                .update_one(
                    doc! { "_id": claims.sid, "user_id": claims.uid },
                    doc! { "$set": { "revoked": true } },
                )
                .await;
        }
    }

    let mut resp = Json(json!({ "authenticated": false, "revoked": true })).into_response();
    if had_web_cookies {
        resp.headers_mut().append(
            header::SET_COOKIE,
            build_web_cookie(&headers, &state.config.public_origin, "", 0)?,
        );
        if security::has_legacy_admin_cookie(&headers) {
            resp.headers_mut().append(
                header::SET_COOKIE,
                build_named_web_cookie(
                    &headers,
                    &state.config.public_origin,
                    security::LEGACY_ADMIN_COOKIE_NAME,
                    "",
                    0,
                )?,
            );
        }
    }
    resp.headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(resp)
}

#[derive(Debug, Clone)]
pub struct LgpdDeletionPlan {
    pub delete_filters: Vec<(&'static str, Document)>,
    pub scoped_cleanup_filters: Vec<(&'static str, Document)>,
    pub report_anonymize_filter: Document,
    pub report_anonymize_update: Document,
}

pub fn validate_delete_confirmation(body: &[u8]) -> Result<(), AppError> {
    let parsed: DeleteAccountRequest = serde_json::from_slice(body).map_err(|_| {
        AppError::BadRequest(
            "Confirme a exclusão definitiva informando {\"confirm\": \"EXCLUIR\"}.".into(),
        )
    })?;
    if parsed.confirm != "EXCLUIR" {
        return Err(AppError::BadRequest(
            "Confirme a exclusão definitiva informando {\"confirm\": \"EXCLUIR\"}.".into(),
        ));
    }
    Ok(())
}

pub fn lgpd_deletion_plan(uid: i64) -> LgpdDeletionPlan {
    let uid_str = uid.to_string();
    let uid_escaped = regex::escape(&uid_str);
    let uid_prefix = format!("^{uid_escaped}:");
    let compat_cmd_prefix = format!("^(?:compatibility|compat-sequence):{uid_escaped}:");

    let delete_filters = vec![
        ("usuarios", doc! { "user_id": uid }),
        ("sessions", doc! { "user_id": uid }),
        ("web_sessions", doc! { "user_id": uid }),
        (
            "native_accounts",
            doc! { "$or": [{ "user_id": uid }, { "linked_user_id": uid }] },
        ),
        ("userActivityLog", doc! { "user_id": uid }),
        (
            "attempt_receipts",
            doc! { "$or": [{ "user_id": uid }, { "_id": { "$regex": &uid_prefix } }] },
        ),
        (
            "user_exams",
            doc! { "$or": [{ "owner_user_id": &uid_str }, { "owner_user_id": uid }, { "user_id": uid }] },
        ),
        (
            "exam_revisions",
            doc! { "scope": "user", "$or": [{ "owner_user_id": &uid_str }, { "owner_user_id": uid }] },
        ),
        (
            "question_exam_compatibility",
            doc! { "$or": [{ "owner_user_id": uid }, { "owner_user_id": &uid_str }] },
        ),
        (
            "answer_evaluations",
            doc! { "$or": [{ "user_id": uid }, { "owner_user_id": uid }] },
        ),
        (
            "tutor_budgets",
            doc! {
                "_id": { "$ne": "global" },
                "$or": [{ "_id": uid }, { "user_id": uid }, { "_id": { "$regex": &uid_prefix } }]
            },
        ),
        (
            "catalog_editor_drafts",
            doc! { "owner_user_id": uid, "status": { "$ne": "ACTIVATED" } },
        ),
        (
            "catalog_editor_versions",
            doc! { "owner_user_id": uid, "status": { "$ne": "ACTIVATED" } },
        ),
        (
            "question_import_ai_requests",
            doc! { "$or": [{ "actor_user_id": uid }, { "user_id": uid }, { "_id": { "$regex": &uid_prefix } }] },
        ),
        (
            "question_import_commits",
            doc! { "$or": [{ "actor_user_id": uid }, { "_id": { "$regex": &uid_prefix } }] },
        ),
        ("mission_progress", doc! { "user_id": uid }),
    ];

    let scoped_cleanup_filters = vec![
        (
            "question_commands",
            doc! { "_id": { "$regex": &compat_cmd_prefix } },
        ),
        (
            "question_review_events",
            doc! {
                "actor.id": &uid_str,
                "event_type": {
                    "$in": [
                        "COMPATIBILITY_CONFIRM",
                        "COMPATIBILITY_EXCLUDE",
                        "EVALUATION_CONFIRM"
                    ]
                }
            },
        ),
    ];

    let report_anonymize_filter = doc! {
        "$or": [
            { "user_id": uid },
            { "user_id": &uid_str },
            { "userId": uid },
            { "userId": &uid_str }
        ]
    };
    let report_anonymize_update = doc! {
        "$set": {
            "user_id": bson::Bson::Null,
            "anonymized_lgpd": true
        },
        "$unset": {
            "userId": "",
            "userEmail": "",
            "reporterEmail": ""
        }
    };

    LgpdDeletionPlan {
        delete_filters,
        scoped_cleanup_filters,
        report_anonymize_filter,
        report_anonymize_update,
    }
}

pub fn build_account_deleted_response(
    headers: &HeaderMap,
    public_origin: &str,
    uid: i64,
    deleted_documents: u64,
    anonymized_reports: u64,
) -> Result<Response, AppError> {
    let used_web_cookie = security::extract_bearer_token(headers).is_none()
        && !security::extract_web_cookies(headers).is_empty();

    let mut resp = Json(json!({
        "deleted": true,
        "user_id": uid.to_string(),
        "deleted_documents": deleted_documents,
        "anonymized_reports": anonymized_reports,
    }))
    .into_response();

    if used_web_cookie {
        resp.headers_mut().append(
            header::SET_COOKIE,
            build_web_cookie(headers, public_origin, "", 0)?,
        );
        if security::has_legacy_admin_cookie(headers) {
            resp.headers_mut().append(
                header::SET_COOKIE,
                build_named_web_cookie(
                    headers,
                    public_origin,
                    security::LEGACY_ADMIN_COOKIE_NAME,
                    "",
                    0,
                )?,
            );
        }
    }
    resp.headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(resp)
}

/// `DELETE /api/v1/auth/account`: exclusão física dos dados pessoais do titular (LGPD Art. 18, VI),
/// preservando 100% do acervo compartilhado e anonimizando reportes de qualidade.
pub async fn handle_delete_account(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Response, AppError> {
    validate_delete_confirmation(&body)?;
    let plan = lgpd_deletion_plan(user.user_id);

    let mut deleted_documents = 0u64;
    // 1. Anonimiza reportes de qualidade antes de remover a sessão/conta para permitir repetição idempotente em falhas de rede.
    let report_res = state
        .db
        .collection::<Document>("reportedQuestoes")
        .update_many(plan.report_anonymize_filter, plan.report_anonymize_update)
        .await?;

    // 2. Remove comandos/eventos pessoais do titular sem tocar em registros editoriais compartilhados.
    for (coll_name, filter) in &plan.scoped_cleanup_filters {
        let res = state
            .db
            .collection::<Document>(coll_name)
            .delete_many(filter.clone())
            .await?;
        deleted_documents += res.deleted_count;
    }

    // 3. Remove dados pessoais e histórico primeiro, revogando sessões e a conta (`usuarios`) estritamente por último.
    for (coll_name, filter) in plan.delete_filters.iter().rev() {
        let res = state
            .db
            .collection::<Document>(coll_name)
            .delete_many(filter.clone())
            .await?;
        deleted_documents += res.deleted_count;
    }

    build_account_deleted_response(
        &headers,
        &state.config.public_origin,
        user.user_id,
        deleted_documents,
        report_res.modified_count,
    )
}

pub async fn get_profile(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<UserProfileDto>, AppError> {
    profile(&state, user.user_id).await.map(Json)
}

pub async fn handle_save_onboarding(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(req): Json<OnboardingUpdateRequest>,
) -> Result<Json<UserProfileDto>, AppError> {
    if let Some(explicit_uid) = req.user_id {
        if explicit_uid != 0 && explicit_uid != user.user_id {
            return Err(AppError::Forbidden(
                "Não é permitido alterar o perfil de outra conta".into(),
            ));
        }
    }
    if !(1..=1000).contains(&req.daily_questions_target)
        || !req.daily_study_hours_target.is_finite()
        || !(0.0..=24.0).contains(&req.daily_study_hours_target)
        || req.active_contest_ids.len() > 100
    {
        return Err(AppError::BadRequest("Metas fora dos limites".into()));
    }
    let mut dedup_contests = Vec::new();
    for cid in &req.active_contest_ids {
        let trimmed = cid.trim();
        if !trimmed.is_empty() && !dedup_contests.iter().any(|existing| existing == trimmed) {
            dedup_contests.push(trimmed.to_string());
        }
    }
    let mut fields = doc! {
        "active_contest_ids": &dedup_contests,
        "daily_questions_target": req.daily_questions_target,
        "daily_study_hours_target": req.daily_study_hours_target,
        "onboarding_completed": true,
        "updated_at": bson::DateTime::now()
    };
    if let Some(name) = req
        .display_name
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        fields.insert("display_name", name.chars().take(120).collect::<String>());
    }
    state
        .db
        .collection::<Document>("usuarios")
        .update_one(doc! { "user_id": user.user_id }, doc! { "$set": fields })
        .await?;

    // Sincroniza `is_active_in_ladder` nas grades customizadas do estudante (ADR-06)
    let owner_str = user.user_id.to_string();
    let user_exams = state.db.collection::<Document>("user_exams");
    user_exams
        .update_many(
            doc! {
                "owner_user_id": &owner_str,
                "exam_id": { "$in": &dedup_contests },
                "status": { "$nin": ["ARQUIVADO", "EXCLUIDO"] }
            },
            doc! { "$set": { "is_active_in_ladder": true } },
        )
        .await?;
    user_exams
        .update_many(
            doc! {
                "owner_user_id": &owner_str,
                "exam_id": { "$nin": &dedup_contests }
            },
            doc! { "$set": { "is_active_in_ladder": false } },
        )
        .await?;

    profile(&state, user.user_id).await.map(Json)
}

pub async fn grant_admin_by_email(db: &Database, email: &str) -> Result<UserProfileDto, AppError> {
    let (_, email_norm) = normalize_and_validate_email(email)?;
    let res = db
        .collection::<Document>("usuarios")
        .update_one(
            doc! { "email_normalized": &email_norm },
            doc! { "$set": { "role": "admin", "updated_at": bson::DateTime::now() } },
        )
        .await?;
    if res.matched_count == 0 {
        return Err(AppError::NotFound(format!(
            "Nenhuma conta cadastrada com o e-mail '{}'",
            email_norm
        )));
    }
    let updated = db
        .collection::<UserDocument>("usuarios")
        .find_one(doc! { "email_normalized": &email_norm })
        .await?
        .ok_or_else(|| AppError::NotFound("Conta não encontrada após atualização".into()))?;
    Ok(updated.to_profile_dto())
}

pub async fn mint_ephemeral_admin_token(
    db: &Database,
    jwt_secret: &str,
    email_filter: Option<&str>,
) -> Result<serde_json::Value, AppError> {
    let filter = if let Some(email_raw) = email_filter.filter(|s| !s.trim().is_empty()) {
        let (_, email_norm) = normalize_and_validate_email(email_raw)?;
        doc! { "email_normalized": email_norm, "role": "admin" }
    } else {
        doc! { "role": "admin", "is_guest": { "$ne": true } }
    };
    let admin = db
        .collection::<UserDocument>("usuarios")
        .find_one(filter)
        .await?
        .ok_or_else(|| {
            AppError::NotFound(
                "Nenhuma conta administrativa ativa encontrada em usuarios".into(),
            )
        })?;
    let ttl = 900_i64;
    let sid = uuid::Uuid::new_v4().to_string();
    let now = chrono::Utc::now();
    let expires_at = bson::DateTime::from_chrono(now + chrono::Duration::seconds(ttl));
    db.collection::<Document>("sessions")
        .insert_one(doc! {
            "_id": &sid,
            "user_id": admin.user_id,
            "purpose": "mobile",
            "revoked": false,
            "ephemeral_cli": true,
            "created_at": bson::DateTime::from_chrono(now),
            "expires_at": expires_at,
        })
        .await?;
    let token = security::sign_session_token(jwt_secret, admin.user_id, &sid, "mobile", ttl)?;
    Ok(json!({
        "user_id": admin.user_id,
        "sid": sid,
        "token": token,
        "expires_in_seconds": ttl,
    }))
}

pub async fn handle_trigger_daily_maintenance(
    State(state): State<Arc<AppState>>,
) -> Result<Json<DailyMaintenanceReport>, AppError> {
    run_daily_reviews_decay_and_countdown(&state.db)
        .await
        .map(Json)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn argon2id_roundtrip_and_email_normalization_are_strict() {
        let (raw, norm) = normalize_and_validate_email("  Admin@Cogniva.App ").unwrap();
        assert_eq!(raw, "Admin@Cogniva.App");
        assert_eq!(norm, "admin@cogniva.app");
        assert!(normalize_and_validate_email("invalido").is_err());
        assert!(validate_password_strength("curta").is_err());
        assert!(validate_password_strength(&"A".repeat(1025)).is_err());
        validate_password_strength("SenhaForte123!").unwrap();

        let hashed = hash_password("SenhaForte123!".into()).await.unwrap();
        assert!(hashed.starts_with("$argon2id$"));
        assert!(verify_password("SenhaForte123!".into(), hashed.clone())
            .await
            .unwrap());
        assert!(!verify_password("OutraSenha123!".into(), hashed)
            .await
            .unwrap());
    }

    #[test]
    fn guest_and_random_user_ids_are_positive_53_bit_safe_integers() {
        let u = guest_user_id(uuid::Uuid::new_v4(), &"a".repeat(64));
        assert!(u >= 1 && (u as u64) <= MAX_SAFE_JS_INT);
        let r = random_safe_user_id();
        assert!(r >= 1 && (r as u64) <= MAX_SAFE_JS_INT);
    }

    #[test]
    fn lgpd_delete_account_guard_cookie_clearing_and_shared_corpus_preservation() {
        assert!(validate_delete_confirmation(b"").is_err());
        assert!(validate_delete_confirmation(br#"{}"#).is_err());
        assert!(validate_delete_confirmation(br#"{"confirm":"excluir"}"#).is_err());
        assert!(validate_delete_confirmation(br#"{"confirm":"OUTRO"}"#).is_err());
        validate_delete_confirmation(br#"{"confirm":"EXCLUIR"}"#).unwrap();

        let plan = lgpd_deletion_plan(987654);
        let targeted: std::collections::HashSet<&str> =
            plan.delete_filters.iter().map(|(c, _)| *c).collect();
        for required in [
            "usuarios",
            "sessions",
            "userActivityLog",
            "attempt_receipts",
            "user_exams",
            "exam_revisions",
            "question_exam_compatibility",
            "answer_evaluations",
            "tutor_budgets",
            "catalog_editor_drafts",
            "question_import_ai_requests",
            "mission_progress",
        ] {
            assert!(targeted.contains(required), "missing {required}");
        }
        for shared in [
            "todasQuestoes",
            "question_revision_events",
            "question_commands",
            "question_classification_suggestions",
            "knowledge_catalog_versions",
            "knowledge_catalog_runtime",
            "knowledge_catalog_head",
            "knowledge_catalog_activations",
            "exam_templates",
            "cogniva_schema_versions",
            "ai_explanations",
            "reportedQuestoes",
            "mission_definitions",
        ] {
            assert!(!targeted.contains(shared), "must preserve {shared}");
        }

        // exam_revisions must restrict deletion to user scope, never templates
        let (_, rev_filter) = plan
            .delete_filters
            .iter()
            .find(|(c, _)| *c == "exam_revisions")
            .unwrap();
        assert_eq!(rev_filter.get_str("scope").unwrap(), "user");

        // tutor_budgets must never delete the global bucket
        let (_, budget_filter) = plan
            .delete_filters
            .iter()
            .find(|(c, _)| *c == "tutor_budgets")
            .unwrap();
        assert_eq!(
            budget_filter.get_document("_id").unwrap().get_str("$ne").unwrap(),
            "global"
        );

        // reportedQuestoes anonymization must not overlap $set and $unset keys
        let set_keys: std::collections::HashSet<&str> = plan
            .report_anonymize_update
            .get_document("$set")
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        let unset_keys: std::collections::HashSet<&str> = plan
            .report_anonymize_update
            .get_document("$unset")
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        assert!(set_keys.is_disjoint(&unset_keys));
        assert!(unset_keys.contains("userId"));
        assert!(unset_keys.contains("userEmail"));
        assert!(unset_keys.contains("reporterEmail"));

        // Cookie auth clears session cookie; Bearer auth does not emit Set-Cookie
        let mut web_headers = HeaderMap::new();
        web_headers.insert(header::HOST, "cogniva.example.org".parse().unwrap());
        web_headers.insert(
            header::COOKIE,
            "cogniva_web_session=tok123; cogniva_admin_session=legacy"
                .parse()
                .unwrap(),
        );
        let web_resp =
            build_account_deleted_response(&web_headers, "https://cogniva.example.org", 42, 7, 1)
                .unwrap();
        let cookies: Vec<_> = web_resp
            .headers()
            .get_all(header::SET_COOKIE)
            .iter()
            .map(|v| v.to_str().unwrap().to_string())
            .collect();
        assert_eq!(cookies.len(), 2);
        assert!(cookies.iter().any(|c| c.starts_with("cogniva_web_session=; ") && c.contains("Max-Age=0")));

        let mut bearer_headers = HeaderMap::new();
        bearer_headers.insert(header::AUTHORIZATION, "Bearer tok-mobile".parse().unwrap());
        let mobile_resp = build_account_deleted_response(
            &bearer_headers,
            "https://cogniva.example.org",
            42,
            7,
            0,
        )
        .unwrap();
        assert!(mobile_resp.headers().get(header::SET_COOKIE).is_none());
    }
}
