use crate::{
    error::AppError,
    models::contest::ContestStatus,
    services::exam_engine::{load_canonical_slices, select_common_trunk_for_focuses},
    AppState,
};
use axum::{
    extract::{Query, State},
    Extension, Json,
};
use bson::{doc, Document};
use chrono::{TimeZone, Utc};
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
    Extension(user): Extension<crate::security::SessionUser>,
    Query(_query): Query<UserQuery>,
) -> Result<Json<TodayDashboardResponse>, AppError> {
    let query = UserQuery { user_id: user.0 };
    let db = &state.db;
    let now = Utc::now();
    let study_zone = chrono::FixedOffset::west_opt(3 * 3600).unwrap();
    let local_day = now.with_timezone(&study_zone).date_naive();
    let start_of_day = study_zone
        .from_local_datetime(&local_day.and_hms_opt(0, 0, 0).unwrap())
        .single()
        .unwrap()
        .with_timezone(&Utc);

    let act_coll = db.collection::<Document>("userActivityLog");

    // 1. Revisões espaçadas pendentes (sempre topo da Home!)
    let pending_srs_reviews_count = act_coll
        .count_documents(doc! {
            "user_id": query.user_id,
            "$or":[{"revisao_necessaria":true},{"$expr":{"$lte":[{"$add":["$datas.ultima_sessao",{"$multiply":[{"$max":[1,{"$ifNull":["$spaced_repetition_interval",1]}]},86400000]}]},bson::DateTime::from_chrono(now)]}}]
        })
        .await?;

    // 2. Caderno de erros e Erros de Alta Confiança
    let error_notebook_count = act_coll
        .count_documents(doc! {
            "user_id": query.user_id,
            "dominada": false,
            "$or": [
                { "in_error_notebook": true },
                {
                    "in_error_notebook": { "$exists": false },
                    "$expr": { "$gt": ["$quantidade_erros", "$quantidade_acertos"] }
                }
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
                ((correct_today as f64 / questions_answered_today as f64) * 10000.0).round()
                    / 100.0;
        }
    }

    // 4. Concursos ativos e countdown real (sem urgência falsa para previstos!)
    let blueprints = crate::services::exam_store::list(&state, query.user_id).await?;
    let upcoming_exams = blueprints
        .iter()
        .filter(|e| e.is_active_in_ladder)
        .map(|exam| {
            let (days_remaining, estimated_window) = match &exam.target_date {
                crate::models::exam_hierarchy::ExamDateTarget::ExactDate { iso_date } => (
                    crate::services::exam_engine::days_until(iso_date, local_day),
                    None,
                ),
                crate::models::exam_hierarchy::ExamDateTarget::EstimatedWindow {
                    label, ..
                } => (None, Some(label.clone())),
                _ => (None, None),
            };
            UpcomingExamCard {
                contest_id: exam.id.clone(),
                name: exam.title.clone(),
                status: crate::routes::contest_projection::status(&exam.status),
                days_remaining,
                estimated_window,
                exam_board: exam.exam_boards.join(" / "),
            }
        })
        .collect();

    let leverage_inputs = blueprints
        .iter()
        .map(crate::services::exam_engine::blueprint_to_leverage)
        .collect::<Vec<_>>();
    let activity = load_canonical_slices(db, query.user_id).await?;
    let mut subject_mastery = crate::services::exam_engine::mastery_by_canonical_subject(&activity);
    if let Some(active_exam) = blueprints
        .iter()
        .filter(|e| {
            e.is_active_in_ladder
                && !matches!(
                    e.status,
                    crate::models::exam_hierarchy::ExamLifecycleStatus::Arquivado
                        | crate::models::exam_hierarchy::ExamLifecycleStatus::Excluido
                )
        })
        .min_by_key(|e| e.ladder_priority)
    {
        let by_topic = crate::services::exam_engine::mastery_by_canonical_topic(&activity);
        let mut topics_by_subject: std::collections::HashMap<String, Vec<String>> =
            std::collections::HashMap::new();
        for subject in &active_exam.subjects {
            let entry = topics_by_subject
                .entry(subject.canonical_subject_id.clone())
                .or_default();
            for topic in &subject.topics {
                if topic.diff_status
                    != crate::models::exam_hierarchy::CanonicalTopicStatus::Arquivado
                    && !topic.canonical_topic_id.is_empty()
                    && !entry.contains(&topic.canonical_topic_id)
                {
                    entry.push(topic.canonical_topic_id.clone());
                }
            }
        }
        for (subject_id, active_topics) in topics_by_subject {
            if !active_topics.is_empty() {
                let sum: f64 = active_topics
                    .iter()
                    .map(|tid| by_topic.get(tid).copied().unwrap_or(0.0))
                    .sum();
                subject_mastery.insert(subject_id, sum / active_topics.len() as f64);
            }
        }
    }
    let common_trunk_suggestion =
        select_common_trunk_for_focuses(&leverage_inputs, &subject_mastery, &[], 15, local_day)
            .map(|card| CommonTrunkSuggestion {
                discipline: card.subject_name,
                shared_contests: card.contest_names,
                badge_text: card.sentence,
            })
            .unwrap_or(CommonTrunkSuggestion {
                discipline: String::new(),
                shared_contests: Vec::new(),
                badge_text: String::new(),
            });

    // 5. Meta diária configurada pelo usuário na coleção `usuarios` (default 50)
    let daily_goal_questions = db
        .collection::<Document>("usuarios")
        .find_one(doc! { "user_id": query.user_id })
        .await
        .ok()
        .flatten()
        .and_then(|u| u.get_i32("daily_questions_target").ok())
        .unwrap_or(50);

    Ok(Json(TodayDashboardResponse {
        user_id: query.user_id,
        date_iso: local_day.to_string(),
        daily_goal_questions,
        questions_answered_today,
        accuracy_today_pct,
        pending_srs_reviews_count,
        error_notebook_count,
        high_confidence_errors_count,
        upcoming_exams,
        common_trunk_suggestion,
    }))
}
