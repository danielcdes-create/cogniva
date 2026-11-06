//! Read-only knowledge projection v1. Never re-tags or duplicates learning events.
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
    ids: Vec<ObjectId>,
) -> Result<HashMap<ObjectId, Document>, AppError> {
    let mut result = HashMap::new();
    let mut reports=db.collection::<Document>("reportedQuestoes").find(doc!{"fix.status_solved":false,"questao_id":{"$in":&ids},"problemas":{"$in":super::selection_v2::INVALID_REPORTS.as_slice()}}).await?;
    let mut excluded = HashSet::new();
    while let Some(row) = reports.try_next().await? {
        if let Ok(id) = row.get_object_id("questao_id") {
            excluded.insert(id);
        }
    }
    let mut cursor = db
        .collection::<Document>("todasQuestoes")
        .find(doc! {"_id":{"$in":ids}})
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
        .find(doc! {"user_id":user})
        .await?
        .try_collect()
        .await?;
    let questions = questions(
        db,
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn legacy_coverage_requires_the_operator_receipt_for_exact_content() {
        let value: serde_json::Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        let mut q = bson::Bson::try_from(value)
            .unwrap()
            .as_document()
            .unwrap()
            .clone();
        q.remove("schema_version");
        q.remove("classification");
        q.insert("canonical_subject_id", "MAT");
        q.insert("canonical_topic_id", "MAT_01_001");
        let typed = question_compatibility::decode(q.clone()).unwrap();
        let revision = typed.revision().unwrap();
        let row = doc! {"pastSessions":[{"attempt_id":"a","question_revision":&revision,"status":true,"nivel_confianca":3}]};
        assert!(project(&row, &q).is_none());
        let entry = crate::routes::canonical_mapping::MappingEntry {
            operation_id: uuid::Uuid::new_v4().to_string(),
            question_id: typed.id.to_hex(),
            question_revision: revision,
            expected_mapping_revision: 0,
            canonical_subject_id: "MAT".into(),
            canonical_topic_id: "MAT_01_001".into(),
            reviewed_reason: "Manual review for this exact content.".into(),
        };
        use sha2::{Digest, Sha256};
        let hash = format!("{:x}", Sha256::digest(serde_json::to_vec(&entry).unwrap()));
        q.insert("canonical_mapping_revision", 1_i64);
        q.insert("canonical_mapping_history",vec![doc!{"operation_id":entry.operation_id,"payload_sha256":hash,"reviewed_by":123_i64,"reviewed_reason":entry.reviewed_reason}]);
        assert_eq!(project(&row, &q).unwrap().attempts, 1);
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
        let event = doc! {"attempt_id":"a","question_revision":&revision,"status":true,"nivel_confianca":3,"data":bson::DateTime::now()};
        let row = doc! {"quantidade_praticas":3,"dominada":true,"pastSessions":[event.clone(),event,{"attempt_id":"old","question_revision":"old-body","status":true},{"attempt_id":"unknown","status":true}]};
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
    fn legacy_practice_count_above_events_len_allows_mastery_when_current_revision_is_mastered() {
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
        let ev1 = doc! {"attempt_id":"a1","question_revision":&revision,"status":true,"nivel_confianca":3,"data":bson::DateTime::from_millis(1000)};
        let ev2 = doc! {"attempt_id":"a2","question_revision":&revision,"status":true,"nivel_confianca":3,"data":bson::DateTime::from_millis(2000)};
        let ev3 = doc! {"attempt_id":"a3","question_revision":&revision,"status":true,"nivel_confianca":3,"data":bson::DateTime::from_millis(3000)};
        let row = doc! {
            "quantidade_praticas": 6,
            "dominada": true,
            "pastSessions": [
                {"attempt_id": "legacy-unversioned", "status": false},
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

/// Called only after the read adapter validates structured classification.
pub fn reviewed_binding(
    question: &Document,
    q: &crate::models::question::QuestionDocument,
) -> bool {
    (|| -> Option<()> {
        let subject = q.canonical_subject_id.as_ref()?;
        let topic = q.canonical_topic_id.as_ref()?;
        if !super::question_contracts::known_topic(subject, topic) {
            return None;
        }
        let revision = q.revision().ok()?;
        if !question.contains_key("classification") && !question.contains_key("canonical_mapping_revision")
            && super::legacy_classification::binding(question).is_some() {return Some(());}
        if !question.contains_key("classification") {
            // Singular legacy IDs remain readable, but coverage requires the
            // operator's original review receipt for this exact content/mapping.
            let mapping_revision = question
                .get_i64("canonical_mapping_revision")
                .ok()
                .or_else(|| {
                    question
                        .get_i32("canonical_mapping_revision")
                        .ok()
                        .map(i64::from)
                })?;
            if mapping_revision < 1 {
                return None;
            }
            let review = question
                .get_array("canonical_mapping_history")
                .ok()?
                .last()?
                .as_document()?;
            let reviewer = review
                .get_i64("reviewed_by")
                .ok()
                .or_else(|| review.get_i32("reviewed_by").ok().map(i64::from))?;
            if reviewer < 1 {
                return None;
            }
            let entry = crate::routes::canonical_mapping::MappingEntry {
                operation_id: review.get_str("operation_id").ok()?.into(),
                question_id: q.id.to_hex(),
                question_revision: revision.clone(),
                expected_mapping_revision: mapping_revision - 1,
                canonical_subject_id: subject.clone(),
                canonical_topic_id: topic.clone(),
                reviewed_reason: review.get_str("reviewed_reason").ok()?.into(),
            };
            use sha2::{Digest, Sha256};
            let digest = format!("{:x}", Sha256::digest(serde_json::to_vec(&entry).ok()?));
            if review.get_str("payload_sha256").ok() != Some(digest.as_str()) {
                return None;
            }
        }
        Some(())
    })()
    .is_some()
}
