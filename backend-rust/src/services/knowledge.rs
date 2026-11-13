//! Read-only knowledge projection v2. Never re-tags or duplicates learning events.
use crate::{error::AppError, services::question_compatibility};
use bson::{doc, oid::ObjectId, Document};
use futures::TryStreamExt;
use mongodb::Database;
use serde::Serialize;
use std::collections::{HashMap, HashSet};

#[derive(Clone, Debug, Serialize)]
pub struct Evidence {
    pub canonical_subject_id: String,
    pub canonical_topic_id: String,
    pub question_revision: String,
    pub attempts: i32,
    pub correct: i32,
    pub accuracy_pct: f64,
    pub confidence_avg: Option<i32>,
    pub recent_confidences: Vec<i32>,
    pub high_confidence_error: bool,
    pub mastered: bool,
    pub review_due_on: Option<String>,
}

pub fn project(activity: &Document, question: &Document) -> Option<Evidence> {
    let q = question_compatibility::decode(question.clone()).ok()?;
    let subject = q.canonical_subject_id.as_ref()?;
    let topic = q.canonical_topic_id.as_ref()?;
    if !crate::services::question_contracts::known_topic(subject, topic) {
        return None;
    }
    let revision = q.revision().ok()?;
    if !q.knowledge_binding_verified {
        return None;
    }
    let mut seen = HashSet::new();
    let mut events: Vec<_> = activity
        .get_array("pastSessions")
        .ok()?
        .iter()
        .filter_map(|v| v.as_document())
        .filter(|e| {
            e.get_str("question_revision").ok() == Some(revision.as_str())
                && e.get_bool("status").is_ok()
                && e.get_str("attempt_id")
                    .ok()
                    .is_some_and(|id| !id.is_empty() && seen.insert(id.to_owned()))
        })
        .collect();
    events.sort_by_key(|e| {
        (
            e.get_datetime("data")
                .map(|t| t.timestamp_millis())
                .unwrap_or(0),
            e.get_str("attempt_id").unwrap_or(""),
        )
    });
    if events.is_empty() {
        return None;
    }
    let correct = events
        .iter()
        .filter(|e| e.get_bool("status").ok() == Some(true))
        .count() as i32;
    let recent: Vec<_> = events
        .iter()
        .rev()
        .take(3)
        .map(|e| e.get_i32("nivel_confianca").unwrap_or(0).clamp(0, 3))
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    let confidence =
        Some((recent.iter().sum::<i32>() as f64 / recent.len() as f64 + 0.5).floor() as i32);
    let recorded_practices = activity.get_i32("quantidade_praticas").unwrap_or(0) as usize;
    let last = events.last()?;
    Some(Evidence {
        canonical_subject_id: subject.clone(),
        canonical_topic_id: topic.clone(),
        question_revision: revision,
        attempts: events.len() as i32,
        correct,
        accuracy_pct: 100.0 * correct as f64 / events.len() as f64,
        confidence_avg: confidence,
        recent_confidences: recent,
        high_confidence_error: last.get_bool("status").ok() == Some(false)
            && last.get_i32("nivel_confianca").unwrap_or(0) >= 2,
        mastered: activity.get_bool("dominada").unwrap_or(false)
            && recorded_practices >= events.len()
            && super::srs_engine::is_mastered(
                super::srs_engine::calculate_streak_and_points(
                    &events
                        .iter()
                        .map(|e| e.get_bool("status").unwrap_or(false))
                        .collect::<Vec<_>>(),
                )
                .points_total,
            )
            && confidence.unwrap_or(0) >= 2,
        review_due_on: q
            .validity
            .as_ref()
            .and_then(|v| v["review_due_on"].as_str())
            .map(str::to_string),
    })
}

pub async fn questions(
    db: &Database,
    user: i64,
    ids: Vec<ObjectId>,
) -> Result<HashMap<ObjectId, Document>, AppError> {
    if ids.is_empty() {
        return Ok(HashMap::new());
    }
    let mut result = HashMap::new();
    let mut reports = db
        .collection::<Document>("reportedQuestoes")
        .find(doc! {
            "user_id": user,
            "fix.status_solved": false,
            "questao_id": { "$in": &ids },
            "problemas": { "$in": super::selection_v2::INVALID_REPORTS.as_slice() }
        })
        .await?;
    let mut excluded = HashSet::new();
    while let Some(row) = reports.try_next().await? {
        if let Ok(id) = row.get_object_id("questao_id") {
            excluded.insert(id);
        }
    }
    let mut cursor = db
        .collection::<Document>("todasQuestoes")
        .find(doc! { "_id": { "$in": ids } })
        .await?;
    while let Some(q) = cursor.try_next().await? {
        if let Ok(id) = q.get_object_id("_id") {
            if !excluded.contains(&id) {
                result.insert(id, q);
            }
        }
    }
    Ok(result)
}

pub async fn load(db: &Database, user: i64) -> Result<Vec<Evidence>, AppError> {
    let rows: Vec<Document> = db
        .collection::<Document>("userActivityLog")
        .find(doc! { "user_id": user })
        .await?
        .try_collect()
        .await?;
    let questions = questions(
        db,
        user,
        rows.iter()
            .filter_map(|r| r.get_object_id("question_id").ok())
            .collect(),
    )
    .await?;
    let mut seen = HashSet::new();
    Ok(rows
        .iter()
        .filter_map(|r| {
            let id = r.get_object_id("question_id").ok()?;
            if !seen.insert(id) {
                return None;
            }
            let e = project(r, questions.get(&id)?)?;
            Some(e)
        })
        .collect())
}

pub fn slice(e: Evidence) -> super::exam_engine::CanonicalActivitySlice {
    super::exam_engine::CanonicalActivitySlice {
        canonical_subject_id: e.canonical_subject_id,
        canonical_topic_id: e.canonical_topic_id,
        accuracy_pct: e.accuracy_pct,
        confidence_avg: e.confidence_avg,
        mastered: e.mastered,
        high_confidence_error: e.high_confidence_error,
    }
}

pub async fn slices(
    db: &Database,
    user: i64,
) -> Result<Vec<super::exam_engine::CanonicalActivitySlice>, AppError> {
    Ok(load(db, user).await?.into_iter().map(slice).collect())
}

/// Verified V2 human classification binding check.
pub fn reviewed_binding(
    question: &Document,
    q: &crate::models::question::QuestionDocument,
) -> bool {
    let Some(subject) = q.canonical_subject_id.as_ref() else {
        return false;
    };
    let Some(topic) = q.canonical_topic_id.as_ref() else {
        return false;
    };
    if !super::question_contracts::known_topic(subject, topic) || q.revision().is_err() {
        return false;
    }
    let Ok(classification) = question.get_document("classification") else {
        return false;
    };
    matches!(
        classification.get_str("state").ok(),
        Some("CONFIRMED" | "PARTIAL")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unreviewed_or_unclassified_questions_do_not_project_topic_coverage() {
        let value: serde_json::Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        let mut q = bson::Bson::try_from(value)
            .unwrap()
            .as_document()
            .unwrap()
            .clone();
        q.remove("classification");
        let typed = question_compatibility::decode(q.clone()).unwrap();
        let revision = typed.revision().unwrap();
        let row = doc! { "pastSessions": [{ "attempt_id": "a", "question_revision": &revision, "status": true, "nivel_confianca": 3 }] };
        assert!(project(&row, &q).is_none());
    }

    #[test]
    fn only_same_content_unique_events_support_the_primary_topic() {
        let value: serde_json::Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        let mut q = bson::Bson::try_from(value)
            .unwrap()
            .as_document()
            .unwrap()
            .clone();
        let revision = question_compatibility::decode(q.clone())
            .unwrap()
            .revision()
            .unwrap();
        let event = doc! { "attempt_id": "a", "question_revision": &revision, "status": true, "nivel_confianca": 3, "data": bson::DateTime::now() };
        let row = doc! { "quantidade_praticas": 3, "dominada": true, "pastSessions": [event.clone(), event, { "attempt_id": "old", "question_revision": "old-body", "status": true }, { "attempt_id": "unknown", "status": true }] };
        let before = row.clone();
        let evidence = project(&row, &q).unwrap();
        assert_eq!(evidence.attempts, 1);
        assert_eq!(evidence.correct, 1);
        assert_eq!(evidence.canonical_topic_id, "MAT_01_001");
        assert!(!evidence.mastered);
        assert_eq!(row, before);
        q.insert("question", "Edited content");
        assert!(project(&row, &q).is_none());
    }

    #[test]
    fn practice_count_above_events_len_allows_mastery_when_current_revision_is_mastered() {
        let value: serde_json::Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        let mut q = bson::Bson::try_from(value)
            .unwrap()
            .as_document()
            .unwrap()
            .clone();
        let revision = question_compatibility::decode(q.clone())
            .unwrap()
            .revision()
            .unwrap();
        let ev1 = doc! { "attempt_id": "a1", "question_revision": &revision, "status": true, "nivel_confianca": 3, "data": bson::DateTime::from_millis(1000) };
        let ev2 = doc! { "attempt_id": "a2", "question_revision": &revision, "status": true, "nivel_confianca": 3, "data": bson::DateTime::from_millis(2000) };
        let ev3 = doc! { "attempt_id": "a3", "question_revision": &revision, "status": true, "nivel_confianca": 3, "data": bson::DateTime::from_millis(3000) };
        let row = doc! {
            "quantidade_praticas": 6,
            "dominada": true,
            "pastSessions": [
                { "attempt_id": "legacy-unversioned", "status": false },
                ev1,
                ev2,
                ev3
            ]
        };
        let evidence = project(&row, &q).unwrap();
        assert_eq!(evidence.attempts, 3);
        assert_eq!(evidence.correct, 3);
        assert!(evidence.mastered);
        q.insert("question", "Edited content");
        assert!(project(&row, &q).is_none());
    }
}
