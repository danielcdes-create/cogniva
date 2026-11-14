//! Orçamentos diários duráveis da xAI (global + por usuário) e rate-limit de autenticação.
//! Chamadas cacheadas ou falhas devolvem a reserva no mesmo dia-calendário de estudo (`America/Sao_Paulo`, `UTC-3`).
use crate::error::AppError;
use axum::http::HeaderMap;
use bson::{doc, Bson, Document};
use chrono::{DateTime, Duration, FixedOffset, Utc};
use mongodb::Database;
use std::{
    collections::{HashMap, VecDeque},
    net::{IpAddr, Ipv4Addr},
    sync::{Mutex, OnceLock},
    time::{Duration as StdDuration, Instant},
};

pub const DEFAULT_USER_DAILY_LIMIT: i64 = 120;
pub const GUEST_USER_DAILY_LIMIT: i64 = 5;
pub const DEFAULT_GLOBAL_DAILY_LIMIT: i64 = 500;
pub const MAX_TRACKED_AUTH_IP_KEYS: usize = 4096;

#[derive(Debug, Clone)]
pub struct QuotaReservation {
    pub day: String,
    pub user_id: Option<i64>,
    pub global_reserved: bool,
}

/// Dia de referência do orçamento diário no fuso `America/Sao_Paulo` (`UTC-3`).
pub fn tutor_budget_day(at: DateTime<Utc>) -> String {
    let sp_tz = FixedOffset::west_opt(3 * 3600).expect("UTC-3 válido");
    at.with_timezone(&sp_tz).date_naive().format("%Y-%m-%d").to_string()
}

pub fn effective_user_daily_limit(configured_limit: i64, is_guest: bool) -> i64 {
    if is_guest {
        configured_limit.min(GUEST_USER_DAILY_LIMIT)
    } else {
        configured_limit
    }
}

pub fn charges_tutor_budget(from_cache: bool, delivered_text: Option<&str>) -> bool {
    !from_cache && delivered_text.is_some_and(|text| !text.trim().is_empty())
}

pub fn is_trusted_proxy_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback() || v4.is_private() || v4.is_link_local(),
        IpAddr::V6(v6) => {
            if let Some(mapped) = v6.to_ipv4_mapped() {
                mapped.is_loopback() || mapped.is_private() || mapped.is_link_local()
            } else {
                v6.is_loopback() || (v6.segments()[0] & 0xfe00) == 0xfc00
            }
        }
    }
}

/// Extrai o IP real do cliente: confia em `X-Real-IP` (ou primeiro IP de `X-Forwarded-For`)
/// apenas quando a conexão direta vem de loopback ou proxy reverso privado (ex.: Nginx local/Docker).
pub fn extract_client_ip(headers: &HeaderMap, peer_ip: Option<IpAddr>) -> IpAddr {
    let trusted_peer = peer_ip.is_none_or(is_trusted_proxy_ip);
    if trusted_peer {
        if let Some(real_ip) = headers
            .get("x-real-ip")
            .and_then(|v| v.to_str().ok())
            .and_then(|raw| raw.trim().parse::<IpAddr>().ok())
        {
            return real_ip;
        }
        if let Some(forwarded_ip) = headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
            .and_then(|raw| raw.split(',').next())
            .and_then(|first| first.trim().parse::<IpAddr>().ok())
        {
            return forwarded_ip;
        }
    }
    peer_ip.unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST))
}

pub struct BoundedSlidingWindowLimiter {
    max_keys: usize,
    buckets: Mutex<HashMap<String, VecDeque<Instant>>>,
}

impl BoundedSlidingWindowLimiter {
    pub fn new(max_keys: usize) -> Self {
        Self {
            max_keys: max_keys.max(1),
            buckets: Mutex::new(HashMap::new()),
        }
    }

    pub fn check(
        &self,
        scope: &str,
        client_ip: IpAddr,
        max_requests: usize,
        window: StdDuration,
    ) -> Result<(), AppError> {
        if max_requests == 0 {
            return Err(AppError::RateLimited(
                "Muitas tentativas de autenticação em sequência. Aguarde alguns instantes.".into(),
            ));
        }
        let now = Instant::now();
        let key = format!("{scope}:{client_ip}");
        let mut map = self.buckets.lock().unwrap_or_else(|e| e.into_inner());

        if !map.contains_key(&key) && map.len() >= self.max_keys {
            map.retain(|_, q| {
                while q
                    .front()
                    .is_some_and(|&ts| now.saturating_duration_since(ts) >= window)
                {
                    q.pop_front();
                }
                !q.is_empty()
            });
            if map.len() >= self.max_keys {
                if let Some(oldest_key) = map
                    .iter()
                    .min_by_key(|(_, q)| q.back().copied().unwrap_or(now))
                    .map(|(k, _)| k.clone())
                {
                    map.remove(&oldest_key);
                }
            }
        }

        let queue = map.entry(key).or_default();
        while queue
            .front()
            .is_some_and(|&ts| now.saturating_duration_since(ts) >= window)
        {
            queue.pop_front();
        }
        if queue.len() >= max_requests {
            return Err(AppError::RateLimited(
                "Muitas tentativas de autenticação a partir deste endereço. Aguarde alguns instantes."
                    .into(),
            ));
        }
        queue.push_back(now);
        Ok(())
    }

    #[cfg(test)]
    pub fn tracked_key_count(&self) -> usize {
        self.buckets.lock().unwrap_or_else(|e| e.into_inner()).len()
    }
}

static AUTH_IP_LIMITER: OnceLock<BoundedSlidingWindowLimiter> = OnceLock::new();

pub fn check_ip_rate_limit(
    scope: &str,
    client_ip: IpAddr,
    max_requests: usize,
    window_secs: u64,
) -> Result<(), AppError> {
    AUTH_IP_LIMITER
        .get_or_init(|| BoundedSlidingWindowLimiter::new(MAX_TRACKED_AUTH_IP_KEYS))
        .check(
            scope,
            client_ip,
            max_requests,
            StdDuration::from_secs(window_secs.max(1)),
        )
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
                    "day": day,
                    "updated_at": bson::DateTime::now()
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

    #[test]
    fn guest_daily_limit_is_capped_lower_than_verified_accounts() {
        assert_eq!(effective_user_daily_limit(120, true), GUEST_USER_DAILY_LIMIT);
        assert_eq!(effective_user_daily_limit(120, false), 120);
        assert_eq!(effective_user_daily_limit(2, true), 2);
    }

    #[test]
    fn client_ip_trusts_x_real_ip_only_for_loopback_or_private_proxies() {
        let mut h = HeaderMap::new();
        h.insert("x-real-ip", "198.51.100.42".parse().unwrap());

        assert_eq!(
            extract_client_ip(&h, Some("127.0.0.1".parse().unwrap())),
            "198.51.100.42".parse::<IpAddr>().unwrap()
        );
        assert_eq!(
            extract_client_ip(&h, Some("172.18.0.3".parse().unwrap())),
            "198.51.100.42".parse::<IpAddr>().unwrap()
        );
        // Public peer cannot spoof X-Real-IP
        assert_eq!(
            extract_client_ip(&h, Some("203.0.113.77".parse().unwrap())),
            "203.0.113.77".parse::<IpAddr>().unwrap()
        );
    }

    #[test]
    fn sliding_window_limiter_enforces_quota_and_bounds_key_map_size() {
        let limiter = BoundedSlidingWindowLimiter::new(4);
        let ip: IpAddr = "198.51.100.9".parse().unwrap();
        let win = StdDuration::from_secs(60);

        assert!(limiter.check("login", ip, 2, win).is_ok());
        assert!(limiter.check("login", ip, 2, win).is_ok());
        assert!(limiter.check("login", ip, 2, win).is_err());

        for host in 1..=25u8 {
            let flood_ip = IpAddr::V4(Ipv4Addr::new(203, 0, 113, host));
            let _ = limiter.check("guest", flood_ip, 5, win);
            assert!(limiter.tracked_key_count() <= 4);
        }
    }
}
