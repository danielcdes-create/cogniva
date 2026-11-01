use crate::{
    error::AppError,
    models::user::UserProfileDocument,
    security,
    services::scheduler::{run_daily_reviews_decay_and_countdown, DailyMaintenanceReport},
    AppState,
};
use axum::{extract::State, Extension, Json};
use bson::{doc, Document};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct TelegramLinkRequest {
    pub user_id: Option<i64>,
    pub username: Option<String>,
    pub display_name: Option<String>,
    pub link_code: Option<String>,
    pub installation_id: Option<String>,
    pub installation_secret: Option<String>,
}
#[derive(Debug, Deserialize)]
pub struct NativeSessionRequest {
    pub installation_id: String,
    pub installation_secret: String,
    pub display_name: Option<String>,
}
#[derive(Debug, Serialize)]
pub struct AuthSessionResponse {
    pub token: String,
    pub profile: UserProfileDocument,
    pub historical_questions_count: u64,
    pub is_existing_telegram_user: bool,
}
#[derive(Debug, Deserialize)]
pub struct OnboardingUpdateRequest {
    pub user_id: i64,
    pub active_contest_ids: Vec<String>,
    pub daily_questions_target: i32,
    pub daily_study_hours_target: f64,
    pub display_name: Option<String>,
}
pub fn native_user_id(installation: uuid::Uuid, secret: &str) -> i64 {
    let digest = Sha256::digest(format!("cogniva-native:{installation}:{secret}").as_bytes());
    -((u64::from_be_bytes(digest[..8].try_into().unwrap()) >> 1).max(1) as i64)
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
pub async fn handle_native_session(
    State(state): State<Arc<AppState>>,
    Json(req): Json<NativeSessionRequest>,
) -> Result<Json<AuthSessionResponse>, AppError> {
    let installation = uuid::Uuid::parse_str(&req.installation_id)
        .map_err(|_| AppError::BadRequest("Instalação inválida".into()))?;
    if req.installation_secret.len() != 64
        || !req
            .installation_secret
            .bytes()
            .all(|c| c.is_ascii_hexdigit())
    {
        return Err(AppError::BadRequest(
            "Credencial da instalação inválida".into(),
        ));
    }
    let hash = hash_secret(&req.installation_secret);
    let accounts = state.db.collection::<Document>("native_accounts");
    accounts.update_one(doc!{"_id": installation.to_string()}, doc!{"$setOnInsert": {
        "user_id": native_user_id(installation,&req.installation_secret), "secret_hash": &hash, "created_at": bson::DateTime::now()
    }}).upsert(true).await?;
    let account = accounts
        .find_one(doc! {"_id": installation.to_string()})
        .await?
        .ok_or_else(|| AppError::Internal("Cadastro não confirmado".into()))?;
    if !equal_hash(account.get_str("secret_hash").unwrap_or(""), &hash) {
        return Err(AppError::Unauthorized(
            "Esta instalação já possui outra credencial".into(),
        ));
    }
    let user_id = account
        .get_i64("linked_user_id")
        .or_else(|_| account.get_i64("user_id"))
        .map_err(|_| AppError::Internal("Cadastro inválido".into()))?;
    ensure_profile(&state, user_id, req.display_name.as_deref()).await?;
    session(&state, user_id, false).await.map(Json)
}
/// ID/username não são provas. O operador emite --issue-link-code USER_ID no binário instalado.
pub async fn handle_telegram_link_or_login(
    State(state): State<Arc<AppState>>,
    Json(req): Json<TelegramLinkRequest>,
) -> Result<Json<AuthSessionResponse>, AppError> {
    let token = req.link_code.as_deref().ok_or_else(|| AppError::Unauthorized("Informe o código de vínculo fornecido pelo responsável pela conta. ID e @username não comprovam identidade.".into()))?;
    let claims = security::verify(&state.config.jwt_secret, token.trim(), "legacy_link")?;
    if claims.user_id == 0 || req.user_id.is_some_and(|id| id != claims.user_id) {
        return Err(AppError::Forbidden(
            "O código pertence a outra conta".into(),
        ));
    }
    let installation = match (&req.installation_id, &req.installation_secret) {
        (Some(id), Some(secret)) => {
            let account = state
                .db
                .collection::<Document>("native_accounts")
                .find_one(doc! {"_id":id})
                .await?
                .ok_or_else(|| {
                    AppError::Unauthorized("Registre a instalação antes de vincular".into())
                })?;
            if !equal_hash(
                account.get_str("secret_hash").unwrap_or(""),
                &hash_secret(secret),
            ) {
                return Err(AppError::Unauthorized(
                    "Credencial da instalação inválida".into(),
                ));
            }
            Some(id.clone())
        }
        (None, None) => None,
        _ => {
            return Err(AppError::BadRequest(
                "Credencial da instalação incompleta".into(),
            ))
        }
    };
    let used = state.db.collection::<Document>("used_link_codes");
    match used.insert_one(doc!{"_id":&claims.nonce,"user_id":claims.user_id,"installation_id":installation.clone(),"used_at":bson::DateTime::now()}).await {
        Ok(_)=>{},
        Err(error) if error.to_string().contains("E11000")=>{
            let receipt=used.find_one(doc!{"_id":&claims.nonce}).await?;
            if installation.as_deref().is_none()||receipt.as_ref().and_then(|r|r.get_str("installation_id").ok())!=installation.as_deref(){
                return Err(AppError::Unauthorized("Código de vínculo já utilizado em outra sessão".into()));
            }
            // A lost response may retry only from the same proven installation.
        },
        Err(error)=>return Err(error.into()),
    }
    if let Some(id) = installation {
        state
            .db
            .collection::<Document>("native_accounts")
            .update_one(
                doc! {"_id":id},
                doc! {"$set":{"linked_user_id":claims.user_id}},
            )
            .await?;
    }
    ensure_profile(&state, claims.user_id, req.display_name.as_deref()).await?;
    session(&state, claims.user_id, true).await.map(Json)
}
async fn ensure_profile(
    state: &AppState,
    user_id: i64,
    display_name: Option<&str>,
) -> Result<(), AppError> {
    state.db.collection::<Document>("usuarios").update_one(doc!{"user_id":user_id}, doc!{
        "$setOnInsert": {"user_id":user_id,"display_name":display_name.unwrap_or("Concurseiro"),
            "daily_questions_target":50,"daily_study_hours_target":2.0,"active_contest_ids":["CPNU"],
            "current_daily_streak":0,"streak_freezes_available":0,"xp_total":0_i64,"cadastro":bson::DateTime::now()},
        "$set":{"ultimo_acesso":bson::DateTime::now()}
    }).upsert(true).await?;
    Ok(())
}
pub async fn profile(state: &AppState, user_id: i64) -> Result<UserProfileDocument, AppError> {
    let raw = state
        .db
        .collection::<Document>("usuarios")
        .find_one(doc! {"user_id":user_id})
        .await?
        .ok_or_else(|| AppError::NotFound("Perfil não encontrado".into()))?;
    bson::from_document(raw).map_err(|_| {
        AppError::Conflict(
            "Perfil legado exige revisão de formato; os dados foram preservados".into(),
        )
    })
}
pub async fn get_profile(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<security::SessionUser>,
) -> Result<Json<UserProfileDocument>, AppError> {
    profile(&state, user.0).await.map(Json)
}
async fn session(
    state: &AppState,
    user_id: i64,
    linked: bool,
) -> Result<AuthSessionResponse, AppError> {
    Ok(AuthSessionResponse {
        token: security::issue(&state.config.jwt_secret, user_id, "session", 30 * 86400)?,
        profile: profile(state, user_id).await?,
        historical_questions_count: state
            .db
            .collection::<Document>("userActivityLog")
            .count_documents(doc! {"user_id":user_id})
            .await?,
        is_existing_telegram_user: linked,
    })
}
pub async fn handle_save_onboarding(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<security::SessionUser>,
    Json(req): Json<OnboardingUpdateRequest>,
) -> Result<Json<UserProfileDocument>, AppError> {
    if !(1..=1000).contains(&req.daily_questions_target)
        || !req.daily_study_hours_target.is_finite()
        || !(0.0..=24.0).contains(&req.daily_study_hours_target)
        || req.active_contest_ids.len() > 100
    {
        return Err(AppError::BadRequest("Metas fora dos limites".into()));
    }
    let mut fields = doc! {"active_contest_ids": &req.active_contest_ids,
    "daily_questions_target":req.daily_questions_target,"daily_study_hours_target":req.daily_study_hours_target,
    "ultimo_acesso":bson::DateTime::now()};
    if let Some(name) = req.display_name {
        fields.insert("display_name", name.chars().take(120).collect::<String>());
    }
    state
        .db
        .collection::<Document>("usuarios")
        .update_one(doc! {"user_id":user.0}, doc! {"$set":fields})
        .await?;
    profile(&state, user.0).await.map(Json)
}
pub async fn handle_trigger_daily_maintenance(
    State(state): State<Arc<AppState>>,
) -> Result<Json<DailyMaintenanceReport>, AppError> {
    run_daily_reviews_decay_and_countdown(&state.db)
        .await
        .map(Json)
}
