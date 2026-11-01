//! A durable per-account daily tutor budget. Reservation precedes any paid call.
use crate::error::AppError;
use bson::{doc, Document};
use mongodb::Database;

pub async fn reserve_tutor(db: &Database, user_id: i64) -> Result<(), AppError> {
    let day = chrono::Utc::now().format("%Y-%m-%d").to_string();
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
