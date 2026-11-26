use crate::{
    error::AppError, models::activity::UserActivityDocument, security::SessionUser, AppState,
};
use axum::{
    extract::{Query, State},
    Extension, Json,
};
use bson::{doc, oid::ObjectId, Document};
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Deserialize)]
pub struct LearningQuery {
    pub after: Option<String>,
}
#[derive(Serialize)]
pub struct LearningEvent {
    pub question_revision: Option<String>,
    pub attempt_id: String,
    pub occurred_at: String,
    pub is_correct: bool,
    pub is_blank: bool,
    pub confidence_level: i32,
    pub time_spent_seconds: i32,
    pub selected_evidence_id: Option<String>,
    pub quiz_mode: String,
    pub session_uuid: String,
    pub elapsed_session_seconds: Option<i32>,
    pub session_interrupted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub declared_confidence: Option<crate::services::metacognitive_engine::DeclaredConfidence>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cognitive_strategy: Option<crate::services::metacognitive_engine::CognitiveStrategy>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub active_seconds: Option<i32>,
    #[serde(skip_serializing_if = "is_zero_u16")]
    pub answer_change_count: u16,
}

fn is_zero_u16(v: &u16) -> bool {
    *v == 0
}
#[derive(Serialize)]
pub struct LearningRecord {
    pub knowledge_projection_version: i32,
    pub knowledge_evidence: Option<crate::services::knowledge::Evidence>,
    pub question_id: String,
    pub disciplina: String,
    pub canonical_subject_id: Option<String>,
    pub canonical_topic_id: Option<String>,
    pub attempts: i32,
    pub correct: i32,
    pub accuracy_pct: f64,
    pub is_mastered: bool,
    pub in_error_notebook: bool,
    pub confidence_avg: Option<i32>,
    pub high_confidence_error: bool,
    pub points: f64,
    pub next_review_at: String,
    pub review_required: bool,
    pub practice_available: bool,
    pub revision: i64,
    pub events: Vec<LearningEvent>,
}
#[derive(Serialize)]
pub struct LearningPage {
    pub records: Vec<LearningRecord>,
    pub next_cursor: Option<String>,
}
pub async fn get_state(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Query(query): Query<LearningQuery>,
) -> Result<Json<LearningPage>, AppError> {
    let mut filter = doc! { "user_id": user.user_id };
    if let Some(after) = query.after {
        filter.insert("_id", doc! { "$gt": ObjectId::parse_str(after)? });
    }
    let mut cursor = state
        .db
        .collection::<Document>("userActivityLog")
        .find(filter)
        .sort(doc! { "_id": 1 })
        .limit(200)
        .await?;
    let mut records = Vec::new();
    let mut raw_records = Vec::new();
    let mut last = None;
    while let Some(raw) = cursor.try_next().await? {
        let id = raw
            .get_object_id("_id")
            .map_err(|_| AppError::Conflict("ID legado exige revisão".into()))?;
        let activity: UserActivityDocument = bson::from_document(raw.clone()).map_err(|_| {
            AppError::Conflict(
                "Histórico legado com formato desconhecido; nenhum dado foi apagado".into(),
            )
        })?;
        let error_notebook = activity.error_notebook();
        let raw_events = raw.get_array("pastSessions").ok();
        let events = activity
            .past_sessions
            .iter()
            .enumerate()
            .map(|(index, e)| LearningEvent {
                question_revision: raw_events
                    .and_then(|all| all.get(index))
                    .and_then(|v| v.as_document())
                    .and_then(|d| d.get_str("question_revision").ok())
                    .map(str::to_string),
                attempt_id: raw_events
                    .and_then(|all| all.get(index))
                    .and_then(|v| v.as_document())
                    .and_then(|d| d.get_str("attempt_id").ok())
                    .map(str::to_string)
                    .unwrap_or_else(|| format!("legacy:{id}:{index}")),
                occurred_at: e.data.to_rfc3339(),
                is_correct: e.status,
                is_blank: e.is_blank,
                confidence_level: e.nivel_confianca,
                time_spent_seconds: e.tempo_gasto_segundos,
                selected_evidence_id: e.selected_evidence_id.clone(),
                quiz_mode: e.modo_sessao.clone(),
                session_uuid: e.session_uuid.clone(),
                elapsed_session_seconds: e.elapsed_session_seconds,
                session_interrupted: e.session_interrupted,
                declared_confidence: e.declared_confidence,
                cognitive_strategy: e.cognitive_strategy,
                active_seconds: e.active_seconds,
                answer_change_count: e.answer_change_count,
            })
            .collect();
        let non_blank_count = activity
            .past_sessions
            .iter()
            .filter(|s| !s.is_blank)
            .count() as i32;
        let clamped_interval = activity.spaced_repetition_interval.clamp(1, 3650) as i64;
        records.push(LearningRecord {
            knowledge_projection_version: 1,
            knowledge_evidence: None,
            question_id: activity.question_id.to_hex(),
            disciplina: activity.disciplina,
            canonical_subject_id: activity.canonical_subject_id,
            canonical_topic_id: activity.canonical_topic_id,
            attempts: activity.quantidade_praticas.max(non_blank_count),
            correct: activity.quantidade_acertos,
            accuracy_pct: activity.percentual_acertos,
            is_mastered: activity.dominada,
            in_error_notebook: error_notebook,
            high_confidence_error: activity.high_confidence_error_flag,
            confidence_avg: activity.nivel_confianca_med_rec,
            review_required: activity.revisao_necessaria,
            practice_available: false,
            points: activity.points,
            next_review_at: (activity.datas.ultima_sessao
                + chrono::Duration::days(clamped_interval))
            .to_rfc3339(),
            revision: raw.get_i64("state_revision").unwrap_or(0),
            events,
        });
        raw_records.push(raw);
        last = Some(id.to_hex());
    }
    let ids: Vec<_> = records
        .iter()
        .filter_map(|r| ObjectId::parse_str(&r.question_id).ok())
        .collect();
    let questions =
        crate::services::knowledge::questions(&state.db, user.user_id, ids.clone()).await?;
    let available = crate::services::selection_v2::pool(
        &state,
        user.user_id,
        doc! { "_id": { "$in": ids } },
        10000,
    )
    .await?;
    let available: std::collections::HashSet<_> =
        available.into_iter().map(|q| q.id.to_hex()).collect();
    for (record, raw) in records.iter_mut().zip(&raw_records) {
        record.practice_available = available.contains(&record.question_id);
        if let Ok(id) = ObjectId::parse_str(&record.question_id) {
            record.knowledge_evidence = questions
                .get(&id)
                .and_then(|q| crate::services::knowledge::project(raw, q));
        }
    }
    Ok(Json(LearningPage {
        next_cursor: if records.len() == 200 { last } else { None },
        records,
    }))
}

/// Endpoint `GET /api/v1/learning/analytics`:
/// Retorna os 10 KPIs formais de aprendizagem, calibração empírica de 5 níveis, Brier Score
/// e estados multidimensionais de domínio conceitual por unidade.
pub async fn get_analytics(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<crate::services::learning_analytics::StudentLearningAnalyticsResponse>, AppError> {
    let resp =
        crate::services::learning_analytics::compute_student_analytics(&state, user.user_id)
            .await?;
    Ok(Json(resp))
}
