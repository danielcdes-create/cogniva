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
    pub question_semantic_revision: Option<String>,
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
        user,
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
        let Ok(content_hash) = super::classification::content_revision_of(raw) else {
            continue;
        };
        let Ok(semantic_hash) = super::classification::semantic_revision_of(raw) else {
            continue;
        };
        let revision_matches = if let Ok(row_sem) = row.get_str("question_semantic_revision") {
            row_sem == semantic_hash
        } else {
            row.get_str("question_content_revision").ok() == Some(content_hash.as_str())
        };
        if !revision_matches
            || !super::catalog_runtime::version_known(number(&row, "catalog_version"))
        {
            continue;
        }
        result.push(Decision {
            question_id: id.into(),
            question_revision: q.question_revision.clone(),
            question_semantic_revision: row
                .get_str("question_semantic_revision")
                .ok()
                .map(Into::into),
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
pub fn effective_relation<'a>(
    rows: &'a [Decision],
    exam: &ExamBlueprint,
    component: &ExamSubjectConfig,
    qid: &str,
    revision: &str,
) -> Option<&'a str> {
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
        .map(|r| r.relation.as_str())
}
pub fn allowed(
    rows: &[Decision],
    exam: &ExamBlueprint,
    component: &ExamSubjectConfig,
    qid: &str,
    revision: &str,
) -> bool {
    effective_relation(rows, exam, component, qid, revision).is_none_or(|rel| rel == "DIRECT")
}
pub fn component_decision_status(
    rows: &[Decision],
    exam: &ExamBlueprint,
    component: &ExamSubjectConfig,
    q: &QuestionDocument,
    revision: &str,
    allow_analogous: bool,
) -> (bool, bool) {
    let qid = q.id.to_hex();
    let in_component_topics = component.topics.iter().any(|t| {
        t.diff_status != CanonicalTopicStatus::Arquivado
            && q.canonical_topic_id.as_deref() == Some(t.canonical_topic_id.as_str())
    });
    match effective_relation(rows, exam, component, &qid, revision) {
        Some("EXCLUDED") => (false, false),
        Some("DIRECT") => (true, false),
        Some("ANALOGOUS") => {
            if allow_analogous {
                (true, true)
            } else {
                (false, false)
            }
        }
        Some(_) => (false, false),
        None => (in_component_topics, false),
    }
}
pub async fn filter_with_mode(
    state: &AppState,
    user: i64,
    exam: &ExamBlueprint,
    component: Option<&ExamSubjectConfig>,
    questions: Vec<QuestionDocument>,
    allow_analogous: bool,
) -> Result<(Vec<QuestionDocument>, std::collections::HashSet<String>), AppError> {
    let dtos: Vec<_> = questions.iter().map(|q| q.to_dto(None)).collect();
    let rows = load(state, user, &dtos).await?;
    let mut analogous_ids = std::collections::HashSet::new();
    let mut selected = Vec::new();
    for q in questions {
        let rev = q.revision().unwrap_or_default();
        if let Some(c) = component {
            let (ok, is_analogous) =
                component_decision_status(&rows, exam, c, &q, &rev, allow_analogous);
            if ok {
                if is_analogous {
                    analogous_ids.insert(q.id.to_hex());
                }
                selected.push(q);
            }
            continue;
        }
        let mut any_direct = false;
        let mut any_analogous = false;
        for c in &exam.subjects {
            let (ok, is_analogous) =
                component_decision_status(&rows, exam, c, &q, &rev, allow_analogous);
            if ok {
                if is_analogous {
                    any_analogous = true;
                } else {
                    any_direct = true;
                }
            }
        }
        if any_direct || any_analogous {
            if !any_direct && any_analogous {
                analogous_ids.insert(q.id.to_hex());
            }
            selected.push(q);
        }
    }
    Ok((selected, analogous_ids))
}
pub async fn filter(
    state: &AppState,
    user: i64,
    exam: &ExamBlueprint,
    component: Option<&ExamSubjectConfig>,
    questions: Vec<QuestionDocument>,
) -> Result<Vec<QuestionDocument>, AppError> {
    Ok(
        filter_with_mode(state, user, exam, component, questions, false)
            .await?
            .0,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use bson::oid::ObjectId;

    fn sample_exam_and_question() -> (ExamBlueprint, QuestionDocument) {
        let mut exam = crate::services::exam_engine::system_exam_blueprints().remove(0);
        exam.id = "exam-test".into();
        exam.revision = 2;
        let mut comp = exam.subjects[0].clone();
        comp.local_subject_id = "comp-mat".into();
        comp.canonical_subject_id = "MAT".into();
        comp.topics = vec![ExamTopicItem {
            local_topic_id: "t1".into(),
            canonical_topic_id: "MAT_01_001".into(),
            canonical_subject_id: "MAT".into(),
            display_title: "Conjuntos".into(),
            diff_status: CanonicalTopicStatus::OficialConfirmado,
        }];
        exam.subjects = vec![comp];

        let q = QuestionDocument {
            id: ObjectId::new(),
            question: "2 + 2?".into(),
            question_type: Some("MULTIPLE_CHOICE".into()),
            answers: vec!["1".into(), "2".into(), "3".into(), "4".into(), "5".into()],
            correct_answers: vec!["4".into()],
            correct_answer_indices: vec![3],
            content_revision: String::new(),
            grading: None,
            classification: None,
            disciplina: "Matemática".into(),
            source: None,
            level: None,
            exam_year: None,
            concursos: vec![],
            syllabus_topic_ids: vec![],
            canonical_subject_id: Some("MAT".into()),
            canonical_topic_id: Some("MAT_01_001".into()),
            knowledge_binding_verified: true,
            validity: None,
            explanation: None,
            explanation_extra: None,
            dr_sincero: None,
            ajuda_infantil: None,
            explicacao_infantil: None,
            mnemonico: None,
            contextual_hint: vec![],
            plausibility_markdown: None,
            plausibility_analysis: None,
            evidence_challenge: None,
            editorial_card: None,
        };
        (exam, q)
    }

    #[test]
    fn compatibility_matrix_enforces_excluded_block_and_analogous_borrowing_rules_t15_t16() {
        let (exam, mut q) = sample_exam_and_question();
        let comp = &exam.subjects[0];
        let rev = q.revision().unwrap();
        let qid = q.id.to_hex();

        // Implicit active topic match without compatibility row -> DIRECT (not borrowed)
        assert_eq!(
            component_decision_status(&[], &exam, comp, &q, &rev, false),
            (true, false)
        );

        // EXCLUDED blocks even when canonical_topic_id matches active component topic (T16)
        let excluded_row = vec![Decision {
            question_id: qid.clone(),
            question_revision: rev.clone(),
            question_semantic_revision: None,
            exam_id: exam.id.clone(),
            exam_revision: exam.revision,
            component_id: comp.local_subject_id.clone(),
            relation: "EXCLUDED".into(),
            revision: 1,
            catalog_version: 2,
        }];
        assert_eq!(
            component_decision_status(&excluded_row, &exam, comp, &q, &rev, false),
            (false, false)
        );
        assert_eq!(
            component_decision_status(&excluded_row, &exam, comp, &q, &rev, true),
            (false, false)
        );

        // ANALOGOUS on external topic: blocked in Simulado (allow_analogous=false), allowed with borrowed flag in Standard (allow_analogous=true)
        q.canonical_topic_id = Some("MAT_02_001".into());
        let rev2 = q.revision().unwrap();
        let analogous_row = vec![Decision {
            question_id: qid,
            question_revision: rev2.clone(),
            question_semantic_revision: Some("a".repeat(64)),
            exam_id: exam.id.clone(),
            exam_revision: exam.revision,
            component_id: comp.local_subject_id.clone(),
            relation: "ANALOGOUS".into(),
            revision: 2,
            catalog_version: 2,
        }];
        assert_eq!(
            component_decision_status(&analogous_row, &exam, comp, &q, &rev2, false),
            (false, false)
        );
        assert_eq!(
            component_decision_status(&analogous_row, &exam, comp, &q, &rev2, true),
            (true, true)
        );
    }
}
