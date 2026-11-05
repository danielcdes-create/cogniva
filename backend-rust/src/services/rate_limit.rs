//! A durable per-account daily tutor budget. A failed or cached generation does not spend it.
use crate::error::AppError;
use bson::{doc, Document};
use chrono::{DateTime, Utc};
use mongodb::Database;

pub fn tutor_budget_day(at: DateTime<Utc>) -> String {
    crate::services::exam_engine::study_date(at)
        .format("%Y-%m-%d")
        .to_string()
}

pub fn charges_tutor_budget(from_cache: bool, delivered_text: Option<&str>) -> bool {
    !from_cache && delivered_text.is_some_and(|text| !text.trim().is_empty())
}

pub async fn reserve_tutor(db: &Database, user_id: i64) -> Result<(), AppError> {
    reserve_tutor_at(db, user_id, Utc::now()).await
}

pub async fn reserve_tutor_at(db: &Database, user_id: i64, at: DateTime<Utc>) -> Result<(), AppError> {
    let day = tutor_budget_day(at);
    let result = db
        .collection::<Document>("tutor_budgets")
        .update_one(
            doc! {"_id":user_id,"$or":[{"day":{"$ne":&day}},{"count":{"$lt":120}}]},
            vec![doc! {"$set":{
                "count":{"$cond":[{"$eq":["$day",&day]},{"$add":["$count",1]},1]},
                "day":&day
            }}],
        )
        .upsert(true)
        .await;
    match result {
        Ok(_) => Ok(()),
        Err(e) if e.to_string().contains("E11000") => Err(AppError::RateLimited(
            "Limite diário do tutor atingido. O estudo e o histórico continuam disponíveis.".into(),
        )),
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
