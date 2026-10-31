use crate::{
    error::AppError, models::user::UserProfileDocument,
    services::scheduler::{run_daily_reviews_decay_and_countdown, DailyMaintenanceReport},
    AppState,
};
use axum::{extract::State, Json};
use bson::{doc, Document};
use chrono::{Duration, Utc};
use jsonwebtoken::{encode, EncodingKey, Header};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Serialize, Deserialize)]
pub struct JwtClaims {
    pub sub: String,
    pub user_id: i64,
    pub exp: usize,
}

#[derive(Debug, Deserialize)]
pub struct TelegramLinkRequest {
    pub user_id: Option<i64>,
    pub username: Option<String>,
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

/// Endpoint `POST /api/v1/auth/telegram-link`
/// Vincula a conta legada do Telegram (coleção `usuarios` e `userActivityLog` no banco `CNU`)
/// ou cria um novo perfil nativo e emite o JWT de sessão.
pub async fn handle_telegram_link_or_login(
    State(state): State<Arc<AppState>>,
    Json(req): Json<TelegramLinkRequest>,
) -> Result<Json<AuthSessionResponse>, AppError> {
    let db = &state.db;
    let users_coll = db.collection::<Document>("usuarios");
    let act_coll = db.collection::<Document>("userActivityLog");

    // 1. Tenta localizar pelo `user_id` ou pelo `username` na coleção `usuarios`
    let mut resolved_user_id = req.user_id.unwrap_or(0);
    let mut existing_user_doc: Option<Document> = None;

    if resolved_user_id > 0 {
        existing_user_doc = users_coll
            .find_one(doc! { "user_id": resolved_user_id })
            .await?;
    } else if let Some(uname) = &req.username {
        let clean_uname = uname.trim_start_matches('@').trim();
        if !clean_uname.is_empty() {
            existing_user_doc = users_coll
                .find_one(doc! { "username": clean_uname })
                .await?;
            if let Some(ref d) = existing_user_doc {
                resolved_user_id = d
                    .get_i64("user_id")
                    .or_else(|_| d.get_i32("user_id").map(|v| v as i64))
                    .unwrap_or(1001);
            }
        }
    }

    if resolved_user_id <= 0 {
        resolved_user_id = 1001;
    }

    let historical_questions_count = act_coll
        .count_documents(doc! { "user_id": resolved_user_id })
        .await?;

    let is_existing = existing_user_doc.is_some() || historical_questions_count > 0;
    let now_bson = bson::DateTime::now();

    let profile = if let Some(doc) = existing_user_doc {
        let _ = users_coll
            .update_one(
                doc! { "user_id": resolved_user_id },
                doc! { "$set": { "ultimo_acesso": now_bson } },
            )
            .await;

        let active_contests = doc
            .get_array("active_contest_ids")
            .map(|arr| {
                arr.iter()
                    .filter_map(|v| v.as_str().map(|s| s.to_string()))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_else(|_| vec!["CPNU".to_string(), "RFB".to_string()]);

        UserProfileDocument {
            user_id: resolved_user_id,
            username: doc.get_str("username").ok().map(|s| s.to_string()),
            display_name: doc
                .get_str("display_name")
                .ok()
                .map(|s| s.to_string())
                .or_else(|| req.display_name.clone()),
            daily_questions_target: doc.get_i32("daily_questions_target").unwrap_or(50),
            daily_study_hours_target: doc.get_f64("daily_study_hours_target").unwrap_or(2.0),
            active_contest_ids: active_contests,
            current_daily_streak: doc.get_i32("current_daily_streak").unwrap_or(1),
            streak_freezes_available: doc.get_i32("streak_freezes_available").unwrap_or(1),
            xp_total: doc.get_i64("xp_total").unwrap_or((historical_questions_count * 10) as i64),
        }
    } else {
        let default_contests = vec!["CPNU".to_string(), "RFB".to_string()];
        let _ = users_coll
            .insert_one(doc! {
                "user_id": resolved_user_id,
                "username": req.username.clone().unwrap_or_else(|| format!("user_{}", resolved_user_id)),
                "display_name": req.display_name.clone().unwrap_or_else(|| "Concurseiro".to_string()),
                "cadastro": now_bson,
                "ultimo_acesso": now_bson,
                "daily_questions_target": 50,
                "daily_study_hours_target": 2.0,
                "active_contest_ids": &default_contests,
                "current_daily_streak": 1,
                "streak_freezes_available": 1,
                "xp_total": (historical_questions_count * 10) as i64
            })
            .await;

        UserProfileDocument {
            user_id: resolved_user_id,
            username: req.username,
            display_name: req.display_name,
            daily_questions_target: 50,
            daily_study_hours_target: 2.0,
            active_contest_ids: default_contests,
            current_daily_streak: 1,
            streak_freezes_available: 1,
            xp_total: (historical_questions_count * 10) as i64,
        }
    };

    let exp = (Utc::now() + Duration::days(30)).timestamp() as usize;
    let claims = JwtClaims {
        sub: resolved_user_id.to_string(),
        user_id: resolved_user_id,
        exp,
    };

    let token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(state.config.jwt_secret.as_bytes()),
    )
    .map_err(|e| AppError::Internal(format!("Erro ao gerar JWT: {}", e)))?;

    Ok(Json(AuthSessionResponse {
        token,
        profile,
        historical_questions_count,
        is_existing_telegram_user: is_existing,
    }))
}

/// Endpoint `PUT /api/v1/auth/onboarding`
/// Persiste as escolhas dos 4 passos do Onboarding (Concursos em escada, Meta de Questões e Horas/dia)
pub async fn handle_save_onboarding(
    State(state): State<Arc<AppState>>,
    Json(req): Json<OnboardingUpdateRequest>,
) -> Result<Json<UserProfileDocument>, AppError> {
    let users_coll = state.db.collection::<Document>("usuarios");
    let now_bson = bson::DateTime::now();

    users_coll
        .update_one(
            doc! { "user_id": req.user_id },
            doc! {
                "$set": {
                    "active_contest_ids": &req.active_contest_ids,
                    "daily_questions_target": req.daily_questions_target,
                    "daily_study_hours_target": req.daily_study_hours_target,
                    "display_name": req.display_name.clone().unwrap_or_else(|| "Candidato".to_string()),
                    "ultimo_acesso": now_bson
                }
            },
        )
        .upsert(true)
        .await?;

    Ok(Json(UserProfileDocument {
        user_id: req.user_id,
        username: None,
        display_name: req.display_name,
        daily_questions_target: req.daily_questions_target,
        daily_study_hours_target: req.daily_study_hours_target,
        active_contest_ids: req.active_contest_ids,
        current_daily_streak: 1,
        streak_freezes_available: 1,
        xp_total: 0,
    }))
}

/// Endpoint `POST /api/v1/admin/run-daily-maintenance`
/// Dispara sob demanda a rotina diária de revisão espaçada (SRS), decay de pontos e countdown
pub async fn handle_trigger_daily_maintenance(
    State(state): State<Arc<AppState>>,
) -> Result<Json<DailyMaintenanceReport>, AppError> {
    let report = run_daily_reviews_decay_and_countdown(&state.db).await?;
    Ok(Json(report))
}
