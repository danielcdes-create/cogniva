//! User-owned, revision-bound decisions. Analogous questions never fill direct quotas.
use crate::{
    error::AppError,
    models::{
        exam_hierarchy::*,
        question::{QuestionDocument, QuestionDto},
    },
    AppState,
};
use bson::{doc, Document};
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Decision {
    pub question_id: String,
    pub question_revision: String,
    pub exam_id: String,
    pub exam_revision: i64,
    pub component_id: String,
    pub relation: String,
    pub revision: i64,
    pub catalog_version: i64,
}
fn number(row: &Document, key: &str) -> i64 {
    row.get_i64(key)
        .ok()
        .or_else(|| row.get_i32(key).ok().map(i64::from))
        .unwrap_or(0)
}
pub async fn load(
    state: &AppState,
    user: i64,
    questions: &[QuestionDto],
) -> Result<Vec<Decision>, AppError> {
    let ids: Vec<_> = questions.iter().map(|q| q.id.clone()).collect();
    let rows: Vec<Document> = state
        .db
        .collection::<Document>("question_exam_compatibility")
        .find(doc! {"owner_user_id":user,"question_id":{"$in":ids},"status":"CONFIRMED"})
        .await?
        .try_collect()
        .await?;
    if rows.is_empty() {
        return Ok(vec![]);
    }
    let raw = super::knowledge::questions(
        &state.db,
        questions
            .iter()
            .filter_map(|q| bson::oid::ObjectId::parse_str(&q.id).ok())
            .collect(),
    )
    .await?;
    let mut result = Vec::new();
    for row in rows {
        let id = row.get_str("question_id").unwrap_or("");
        let Some(q) = questions.iter().find(|q| q.id == id) else {
            continue;
        };
        let Some(raw) = bson::oid::ObjectId::parse_str(id)
            .ok()
            .and_then(|id| raw.get(&id))
        else {
            continue;
        };
        let Ok(hash) = super::classification::content_revision_of(raw) else {
            continue;
        };
        if row.get_str("question_content_revision").ok() != Some(hash.as_str())
            || !super::catalog_runtime::version_known(number(&row, "catalog_version"))
        {
            continue;
        }
        result.push(Decision {
            question_id: id.into(),
            question_revision: q.question_revision.clone(),
            exam_id: row.get_str("exam_id").unwrap_or("").into(),
            exam_revision: number(&row, "exam_revision"),
            component_id: row.get_str("component_id").unwrap_or("").into(),
            relation: row.get_str("relation").unwrap_or("EXCLUDED").into(),
            revision: number(&row, "revision"),
            catalog_version: number(&row, "catalog_version"),
        });
    }
    Ok(result)
}
pub fn allowed(
    rows: &[Decision],
    exam: &ExamBlueprint,
    component: &ExamSubjectConfig,
    qid: &str,
    revision: &str,
) -> bool {
    rows.iter()
        .filter(|r| {
            r.exam_id == exam.id
                && r.exam_revision == exam.revision
                && r.component_id == component.local_subject_id
                && r.question_id == qid
                && r.question_revision == revision
        })
        .max_by_key(|r| {
            (
                r.revision,
                if r.relation == "EXCLUDED" {
                    2
                } else if r.relation == "ANALOGOUS" {
                    1
                } else {
                    0
                },
            )
        })
        .is_none_or(|r| r.relation == "DIRECT")
}
pub async fn filter(
    state: &AppState,
    user: i64,
    exam: &ExamBlueprint,
    component: Option<&ExamSubjectConfig>,
    questions: Vec<QuestionDocument>,
) -> Result<Vec<QuestionDocument>, AppError> {
    let dtos: Vec<_> = questions.iter().map(|q| q.to_dto(None)).collect();
    let rows = load(state, user, &dtos).await?;
    Ok(questions
        .into_iter()
        .filter(|q| {
            let rev = q.revision().unwrap_or_default();
            if let Some(c) = component {
                return allowed(&rows, exam, c, &q.id.to_hex(), &rev);
            }
            exam.subjects
                .iter()
                .filter(|c| {
                    c.topics.iter().any(|t| {
                        t.diff_status != CanonicalTopicStatus::Arquivado
                            && q.canonical_topic_id.as_deref()
                                == Some(t.canonical_topic_id.as_str())
                    })
                })
                .any(|c| allowed(&rows, exam, c, &q.id.to_hex(), &rev))
                || (q.canonical_topic_id.is_none()
                    && exam
                        .subjects
                        .iter()
                        .any(|c| allowed(&rows, exam, c, &q.id.to_hex(), &rev)))
        })
        .collect())
}
