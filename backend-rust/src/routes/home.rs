use crate::{
    error::AppError,
    models::contest::{ContestDocument, ContestStatus},
    AppState,
};
use axum::{
    extract::{Query, State},
    Json,
};
use bson::{doc, Document};
use chrono::{Datelike, TimeZone, Utc};
use futures::stream::TryStreamExt;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct UserQuery {
    pub user_id: i64,
}

#[derive(Debug, Serialize)]
pub struct UpcomingExamCard {
    pub contest_id: String,
    pub name: String,
    pub status: ContestStatus,
    pub days_remaining: Option<i64>,
    pub estimated_window: Option<String>,
    pub exam_board: String,
}

#[derive(Debug, Serialize)]
pub struct CommonTrunkSuggestion {
    pub discipline: String,
    pub shared_contests: Vec<String>,
    pub badge_text: String,
}

/// Payload da Tela "Hoje" (Pulso diário do app — Nota Técnica Quiz Kotlin §5)
#[derive(Debug, Serialize)]
pub struct TodayDashboardResponse {
    pub user_id: i64,
    pub date_iso: String,
    pub daily_goal_questions: i32,
    pub questions_answered_today: i32,
    pub accuracy_today_pct: f64,
    pub pending_srs_reviews_count: u64,
    pub error_notebook_count: u64,
    pub high_confidence_errors_count: u64,
    pub upcoming_exams: Vec<UpcomingExamCard>,
    pub common_trunk_suggestion: CommonTrunkSuggestion,
}

pub async fn get_today_dashboard(
    State(state): State<Arc<AppState>>,
    Query(query): Query<UserQuery>,
) -> Result<Json<TodayDashboardResponse>, AppError> {
    let db = &state.db;
    let now = Utc::now();
    let start_of_day = Utc
        .with_ymd_and_hms(now.year(), now.month(), now.day(), 0, 0, 0)
        .single()
        .unwrap_or(now);

    let act_coll = db.collection::<Document>("userActivityLog");

    // 1. Revisões espaçadas pendentes (sempre topo da Home!)
    let pending_srs_reviews_count = act_coll
        .count_documents(doc! {
            "user_id": query.user_id,
            "revisao_necessaria": true
        })
        .await?;

    // 2. Caderno de erros e Erros de Alta Confiança
    let error_notebook_count = act_coll
        .count_documents(doc! {
            "user_id": query.user_id,
            "$or": [
                { "in_error_notebook": true },
                { "quantidade_erros": { "$gt": 0 }, "dominada": false }
            ]
        })
        .await?;

    let high_confidence_errors_count = act_coll
        .count_documents(doc! {
            "user_id": query.user_id,
            "high_confidence_error_flag": true
        })
        .await?;

    // 3. Questões respondidas hoje
    let today_pipeline = vec![
        doc! { "$match": { "user_id": query.user_id } },
        doc! { "$unwind": "$pastSessions" },
        doc! {
            "$match": {
                "pastSessions.data": { "$gte": bson::DateTime::from_chrono(start_of_day) }
            }
        },
        doc! {
            "$group": {
                "_id": null,
                "total_today": { "$sum": 1 },
                "correct_today": {
                    "$sum": { "$cond": [{ "$eq": ["$pastSessions.status", true] }, 1, 0] }
                }
            }
        },
    ];

    let mut cursor = act_coll.aggregate(today_pipeline).await?;
    let mut questions_answered_today = 0;
    let mut accuracy_today_pct = 0.0;
    if let Some(res_doc) = cursor.try_next().await? {
        questions_answered_today = res_doc.get_i32("total_today").unwrap_or(0);
        let correct_today = res_doc.get_i32("correct_today").unwrap_or(0);
        if questions_answered_today > 0 {
            accuracy_today_pct =
                ((correct_today as f64 / questions_answered_today as f64) * 10000.0).round() / 100.0;
        }
    }

    // 4. Concursos ativos e countdown real (sem urgência falsa para previstos!)
    let mut contests_cursor = db
        .collection::<ContestDocument>("concursos")
        .find(doc! {})
        .await?;
    let mut upcoming_exams = Vec::new();
    while let Some(c) = contests_cursor.try_next().await? {
        let days_remaining = c
            .dates
            .exam_date_official
            .as_deref()
            .and_then(|iso| chrono::DateTime::parse_from_rfc3339(iso).ok())
            .map(|dt| (dt.with_timezone(&Utc).date_naive() - now.date_naive()).num_days().max(0));

        upcoming_exams.push(UpcomingExamCard {
            contest_id: c.id,
            name: c.name,
            status: c.status,
            days_remaining,
            estimated_window: c.dates.estimated_window,
            exam_board: c.exam_board,
        });
    }

    let common_trunk_suggestion = CommonTrunkSuggestion {
        discipline: "Finanças Públicas".to_string(),
        shared_contests: vec!["CPNU".to_string(), "RFB".to_string()],
        badge_text: "Estudar 15 questões de Finanças Públicas avança CPNU e Receita Federal ao mesmo tempo".to_string(),
    };

    Ok(Json(TodayDashboardResponse {
        user_id: query.user_id,
        date_iso: now.to_rfc3339(),
        daily_goal_questions: 50,
        questions_answered_today,
        accuracy_today_pct,
        pending_srs_reviews_count,
        error_notebook_count,
        high_confidence_errors_count,
        upcoming_exams,
        common_trunk_suggestion,
    }))
}
