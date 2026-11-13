//! Orçamentos diários duráveis da xAI (global + por usuário) e rate-limit de autenticação.
//! Chamadas cacheadas ou falhas devolvem a reserva no mesmo dia-calendário de estudo (`America/Sao_Paulo`).
use crate::error::AppError;
use bson::{doc, Bson, Document};
use chrono::{DateTime, Duration, Utc};
use mongodb::Database;

pub const DEFAULT_USER_DAILY_LIMIT: i64 = 120;
pub const DEFAULT_GLOBAL_DAILY_LIMIT: i64 = 500;

#[derive(Debug, Clone)]
pub struct QuotaReservation {
    pub day: String,
    pub user_id: Option<i64>,
    pub global_reserved: bool,
}

pub fn tutor_budget_day(at: DateTime<Utc>) -> String {
    crate::services::exam_engine::study_date(at)
        .format("%Y-%m-%d")
        .to_string()
}

pub fn charges_tutor_budget(from_cache: bool, delivered_text: Option<&str>) -> bool {
    !from_cache && delivered_text.is_some_and(|text| !text.trim().is_empty())
}

async fn reserve_bucket(
    db: &Database,
    bucket_id: Bson,
    day: &str,
    limit: i64,
    exceeded_msg: &str,
) -> Result<(), AppError> {
    if limit <= 0 {
        return Err(AppError::RateLimited(exceeded_msg.to_string()));
    }
    let limit_i64 = limit;
    let result = db
        .collection::<Document>("tutor_budgets")
        .update_one(
            doc! {
                "_id": bucket_id,
                "$or": [
                    { "day": { "$ne": day } },
                    { "count": { "$lt": limit_i64 } }
                ]
            },
            vec![doc! {
                "$set": {
                    "count": {
                        "$cond": [
                            { "$eq": ["$day", day] },
                            { "$add": ["$count", 1] },
                            1
                        ]
                    },
                    "day": day
                }
            }],
        )
        .upsert(true)
        .await;
    match result {
        Ok(_) => Ok(()),
        Err(e) if e.to_string().contains("E11000") || e.to_string().contains("11000") => {
            Err(AppError::RateLimited(exceeded_msg.to_string()))
        }
        Err(e) => Err(e.into()),
    }
}

async fn release_bucket(db: &Database, bucket_id: Bson, day: &str) {
    let _ = db
        .collection::<Document>("tutor_budgets")
        .update_one(
            doc! { "_id": bucket_id, "day": day, "count": { "$gt": 0 } },
            doc! { "$inc": { "count": -1 } },
        )
        .await;
}

pub async fn reserve_xai_quota(
    db: &Database,
    user_id: Option<i64>,
    user_limit: i64,
    global_limit: i64,
) -> Result<QuotaReservation, AppError> {
    reserve_xai_quota_at(db, user_id, user_limit, global_limit, Utc::now()).await
}

pub async fn reserve_xai_quota_at(
    db: &Database,
    user_id: Option<i64>,
    user_limit: i64,
    global_limit: i64,
    at: DateTime<Utc>,
) -> Result<QuotaReservation, AppError> {
    let day = tutor_budget_day(at);
    reserve_bucket(
        db,
        Bson::String("global".to_string()),
        &day,
        global_limit,
        "Limite diário global de IA atingido. O estudo e o histórico continuam disponíveis.",
    )
    .await?;

    if let Some(uid) = user_id {
        if let Err(err) = reserve_bucket(
            db,
            Bson::Int64(uid),
            &day,
            user_limit,
            "Limite diário do tutor atingido. O estudo e o histórico continuam disponíveis.",
        )
        .await
        {
            release_bucket(db, Bson::String("global".to_string()), &day).await;
            return Err(err);
        }
    }

    Ok(QuotaReservation {
        day,
        user_id,
        global_reserved: true,
    })
}

pub async fn release_xai_quota(db: &Database, reservation: &QuotaReservation) {
    if let Some(uid) = reservation.user_id {
        release_bucket(db, Bson::Int64(uid), &reservation.day).await;
    }
    if reservation.global_reserved {
        release_bucket(db, Bson::String("global".to_string()), &reservation.day).await;
    }
}

pub async fn reserve_tutor(db: &Database, user_id: i64) -> Result<QuotaReservation, AppError> {
    reserve_xai_quota_at(
        db,
        Some(user_id),
        DEFAULT_USER_DAILY_LIMIT,
        DEFAULT_GLOBAL_DAILY_LIMIT,
        Utc::now(),
    )
    .await
}

pub async fn release_tutor(db: &Database, user_id: i64) {
    let day = tutor_budget_day(Utc::now());
    release_bucket(db, Bson::Int64(user_id), &day).await;
    release_bucket(db, Bson::String("global".to_string()), &day).await;
}

pub async fn check_auth_rate_limit(
    db: &Database,
    scope: &str,
    subject_key: &str,
    max_attempts: i64,
    window_secs: i64,
) -> Result<(), AppError> {
    let now = Utc::now();
    let window_bucket = now.timestamp().div_euclid(window_secs.max(1));
    let id = format!("{scope}:{subject_key}:{window_bucket}");
    let expires_at = bson::DateTime::from_chrono(now + Duration::seconds(window_secs.max(60) * 2));
    let coll = db.collection::<Document>("auth_rate_limits");
    let res = coll
        .update_one(
            doc! { "_id": &id, "count": { "$lt": max_attempts } },
            doc! {
                "$inc": { "count": 1_i64 },
                "$setOnInsert": { "expires_at": expires_at }
            },
        )
        .upsert(true)
        .await;
    match res {
        Ok(_) => Ok(()),
        Err(e) if e.to_string().contains("E11000") || e.to_string().contains("11000") => {
            Err(AppError::RateLimited(
                "Muitas tentativas de autenticação em sequência. Aguarde alguns instantes.".into(),
            ))
        }
        Err(e) => Err(e.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn budget_day_changes_at_midnight_in_the_study_zone() {
        let before = "2026-11-04T02:59:59Z".parse::<DateTime<Utc>>().unwrap();
        let after = "2026-11-04T03:00:00Z".parse::<DateTime<Utc>>().unwrap();
        assert_eq!(tutor_budget_day(before), "2026-11-03");
        assert_eq!(tutor_budget_day(after), "2026-11-04");
        assert_eq!(
            tutor_budget_day(before),
            crate::services::exam_engine::study_date(before)
                .format("%Y-%m-%d")
                .to_string()
        );
    }

    #[test]
    fn cache_hit_and_failed_generation_do_not_charge() {
        assert!(!charges_tutor_budget(true, Some("texto em cache")));
        assert!(!charges_tutor_budget(false, None));
        assert!(!charges_tutor_budget(false, Some("   ")));
        assert!(charges_tutor_budget(false, Some("explicação entregue")));
    }
}
