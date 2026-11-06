use crate::{
    error::AppError,
    routes::home::UserQuery,
    services::exam_engine::{
        load_canonical_slices, mastery_by_canonical_topic, readiness_for_blueprints,
    },
    AppState,
};
use axum::{
    extract::{Query, State},
    Extension, Json,
};
use bson::{doc, Document};
use chrono::TimeZone;
use futures::stream::TryStreamExt;
use serde::Serialize;
use std::sync::Arc;

fn number(d: &Document, key: &str) -> f64 {
    match d.get(key) {
        Some(bson::Bson::Int32(v)) => *v as f64,
        Some(bson::Bson::Int64(v)) => *v as f64,
        Some(bson::Bson::Double(v)) if v.is_finite() => *v,
        _ => 0.0,
    }
}

#[derive(Debug, Serialize)]
pub struct DisciplineMasteryBar {
    pub discipline: String,
    pub accuracy_pct: f64,
    pub total_attempts: i32,
    pub mastered_questions: i32,
    pub points_accumulated: f64,
}

#[derive(Debug, Serialize)]
pub struct ConfidenceMatrixSummary {
    pub high_confidence_correct: i32,
    pub low_confidence_correct: i32,
    pub low_confidence_error: i32,
    /// Ponto cego crítico (Erro com alta confiança)
    pub high_confidence_error: i32,
}

#[derive(Debug, Serialize)]
pub struct DailyHeatmapCell {
    pub date_ymd: String,
    pub questions_count: i32,
    pub accuracy_pct: f64,
}

/// Resposta da Tela "Progresso" (§12 da Nota Técnica Quiz Kotlin & §10 da Nota Técnica Concurso App)
#[derive(Debug, Serialize)]
pub struct ProgressAnalyticsResponse {
    pub user_id: i64,
    pub global_accuracy_pct: f64,
    pub total_questions_practiced: i32,
    pub total_mastered_questions: i32,
    pub readiness_index_score: f64,
    pub historical_cutoff_reference: f64,
    /// Ordenadas da disciplina mais crítica (menor acerto) para a mais forte
    pub disciplines_sorted_by_weakness: Vec<DisciplineMasteryBar>,
    pub confidence_matrix: ConfidenceMatrixSummary,
    pub activity_heatmap: Vec<DailyHeatmapCell>,
}

pub async fn get_user_progress_analytics(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<crate::security::SessionUser>,
    Query(_query): Query<UserQuery>,
) -> Result<Json<ProgressAnalyticsResponse>, AppError> {
    let query = UserQuery { user_id: user.0 };
    let act_coll = state.db.collection::<Document>("userActivityLog");

    // 1. Agregação por Disciplina (ordenada por pior desempenho para foco tático)
    let effective_attempts = doc! {
        "$max": [
            "$quantidade_praticas",
            { "$size": { "$ifNull": ["$pastSessions", []] } }
        ]
    };
    let disc_pipeline = vec![
        doc! { "$match": { "user_id": query.user_id } },
        doc! {
            "$group": {
                "_id": "$disciplina",
                "correct_count": { "$sum": "$quantidade_acertos" },
                "total_attempts": { "$sum": effective_attempts.clone() },
                "questions_practiced": {
                    "$sum": {
                        "$cond": [{ "$gt": [effective_attempts, 0] }, 1, 0]
                    }
                },
                "mastered_count": {
                    "$sum": { "$cond": [{ "$eq": ["$dominada", true] }, 1, 0] }
                },
                "points_sum": { "$sum": "$points" }
            }
        },
        doc! { "$sort": { "avg_acc": 1 } },
    ];

    let mut disc_cursor = act_coll.aggregate(disc_pipeline).await?;
    let mut disciplines = Vec::new();
    let mut total_questions_practiced = 0;
    let mut total_attempts_all = 0;
    let mut total_mastered_questions = 0;
    let mut weighted_acc_sum = 0.0;

    while let Some(d) = disc_cursor.try_next().await? {
        let discipline = d.get_str("_id").unwrap_or("Geral").to_string();
        let correct = d
            .get_i32("correct_count")
            .map(i64::from)
            .or_else(|_| d.get_i64("correct_count"))
            .unwrap_or(0);
        let attempts_for_rate = d
            .get_i32("total_attempts")
            .map(i64::from)
            .or_else(|_| d.get_i64("total_attempts"))
            .unwrap_or(0);
        let accuracy_pct = if attempts_for_rate > 0 {
            100.0 * correct as f64 / attempts_for_rate as f64
        } else {
            0.0
        };
        let attempts = number(&d, "total_attempts").clamp(0.0, i32::MAX as f64) as i32;
        let practiced = number(&d, "questions_practiced").clamp(0.0, i32::MAX as f64) as i32;
        let mastered = number(&d, "mastered_count").clamp(0.0, i32::MAX as f64) as i32;
        let points = (number(&d, "points_sum") * 100.0).round() / 100.0;

        total_questions_practiced += practiced;
        total_attempts_all += attempts;
        total_mastered_questions += mastered;
        weighted_acc_sum += accuracy_pct * (attempts as f64);

        disciplines.push(DisciplineMasteryBar {
            discipline,
            accuracy_pct,
            total_attempts: attempts,
            mastered_questions: mastered,
            points_accumulated: points,
        });
    }

    disciplines.sort_by(|a, b| {
        a.accuracy_pct
            .total_cmp(&b.accuracy_pct)
            .then(a.discipline.cmp(&b.discipline))
    });
    let global_accuracy_pct = if total_attempts_all > 0 {
        ((weighted_acc_sum / (total_attempts_all as f64)) * 100.0).round() / 100.0
    } else {
        0.0
    };

    // 2. Matriz Confiança × Acerto + Heatmap diário a partir de `pastSessions`
    let zone = chrono::FixedOffset::west_opt(3 * 3600).unwrap();
    let today = chrono::Utc::now().with_timezone(&zone).date_naive();
    let since = today - chrono::Duration::days(89);
    let start = zone
        .from_local_datetime(&since.and_hms_opt(0, 0, 0).unwrap())
        .single()
        .unwrap()
        .with_timezone(&chrono::Utc);
    let end = zone
        .from_local_datetime(
            &(today + chrono::Duration::days(1))
                .and_hms_opt(0, 0, 0)
                .unwrap(),
        )
        .single()
        .unwrap()
        .with_timezone(&chrono::Utc);
    let sessions_pipeline = vec![
        doc! { "$match": { "user_id": query.user_id } },
        doc! { "$unwind": "$pastSessions" },
        doc! { "$match":{"pastSessions.data":{"$gte":bson::DateTime::from_chrono(start),"$lt":bson::DateTime::from_chrono(end)}} },
        doc! {
            "$group": {
                "_id": {
                    "$dateToString": { "format": "%Y-%m-%d", "date": "$pastSessions.data", "timezone":"-03:00" }
                },
                "count": { "$sum": 1 },
                "correct": {
                    "$sum": { "$cond": [{ "$eq": ["$pastSessions.status", true] }, 1, 0] }
                },
                "hc_correct": {
                    "$sum": {
                        "$cond": [
                            { "$and": [
                                { "$eq": ["$pastSessions.status", true] },
                                { "$gte": ["$pastSessions.nivel_confianca", 2] }
                            ]},
                            1, 0
                        ]
                    }
                },
                "lc_correct": {
                    "$sum": {
                        "$cond": [
                            { "$and": [
                                { "$eq": ["$pastSessions.status", true] },
                                { "$lt": ["$pastSessions.nivel_confianca", 2] }
                            ]},
                            1, 0
                        ]
                    }
                },
                "lc_error": {
                    "$sum": {
                        "$cond": [
                            { "$and": [
                                { "$eq": ["$pastSessions.status", false] },
                                { "$lt": ["$pastSessions.nivel_confianca", 2] }
                            ]},
                            1, 0
                        ]
                    }
                },
                "hc_error": {
                    "$sum": {
                        "$cond": [
                            { "$and": [
                                { "$eq": ["$pastSessions.status", false] },
                                { "$gte": ["$pastSessions.nivel_confianca", 2] }
                            ]},
                            1, 0
                        ]
                    }
                }
            }
        },
        doc! { "$sort": { "_id": -1 } },
        doc! { "$limit": 90 },
    ];

    let mut sess_cursor = act_coll.aggregate(sessions_pipeline).await?;
    let mut heatmap = Vec::new();
    let mut matrix = ConfidenceMatrixSummary {
        high_confidence_correct: 0,
        low_confidence_correct: 0,
        low_confidence_error: 0,
        high_confidence_error: 0,
    };

    while let Some(d) = sess_cursor.try_next().await? {
        let date_ymd = d.get_str("_id").unwrap_or("").to_string();
        let count = d.get_i32("count").unwrap_or(0);
        let correct = d.get_i32("correct").unwrap_or(0);
        let acc = if count > 0 {
            ((correct as f64 / count as f64) * 10000.0).round() / 100.0
        } else {
            0.0
        };

        matrix.high_confidence_correct += d.get_i32("hc_correct").unwrap_or(0);
        matrix.low_confidence_correct += d.get_i32("lc_correct").unwrap_or(0);
        matrix.low_confidence_error += d.get_i32("lc_error").unwrap_or(0);
        matrix.high_confidence_error += d.get_i32("hc_error").unwrap_or(0);

        heatmap.push(DailyHeatmapCell {
            date_ymd,
            questions_count: count,
            accuracy_pct: acc,
        });
    }
    let mut by_day: std::collections::HashMap<_, _> = heatmap
        .into_iter()
        .map(|cell| (cell.date_ymd.clone(), cell))
        .collect();
    let heatmap = (0..90)
        .map(|offset| {
            let date = (since + chrono::Duration::days(offset)).to_string();
            by_day.remove(&date).unwrap_or(DailyHeatmapCell {
                date_ymd: date,
                questions_count: 0,
                accuracy_pct: 0.0,
            })
        })
        .collect();

    let activity = load_canonical_slices(&state.db, query.user_id).await?;
    let topic_mastery = mastery_by_canonical_topic(&activity);
    let blueprints = crate::services::exam_store::list(&state, query.user_id).await?;
    let readiness_index_score = readiness_for_blueprints(&blueprints, &topic_mastery);

    Ok(Json(ProgressAnalyticsResponse {
        user_id: query.user_id,
        global_accuracy_pct,
        total_questions_practiced,
        total_mastered_questions,
        readiness_index_score,
        historical_cutoff_reference: blueprints
            .iter()
            .filter(|e| e.is_active_in_ladder)
            .min_by_key(|e| e.ladder_priority)
            .map(|e| e.historical_cutoff_score)
            .unwrap_or(0.0),
        disciplines_sorted_by_weakness: disciplines,
        confidence_matrix: matrix,
        activity_heatmap: heatmap,
    }))
}
