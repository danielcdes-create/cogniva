//! Lightweight read adapter for objective practice. Full JSON Schema validation runs on write.
use crate::{error::AppError, models::question::QuestionDocument};
use bson::{doc, Bson, Document};

pub fn canonical_filter(filter: Document) -> Document {
    let mut clauses = Vec::new();
    for (key, value) in filter {
        if key == "canonical_subject_id" || key == "canonical_topic_id" {
            let leaf = if key == "canonical_subject_id" {
                "subject_id"
            } else {
                "topic_id"
            };
            let mut link = doc! { "role": "PRIMARY", "coverage": "DIRECT" };
            link.insert(leaf, value);
            clauses.push(doc! {
                "classification.state": { "$in": ["CONFIRMED", "PARTIAL"] },
                "classification.links": { "$elemMatch": link }
            });
        } else if ["$and", "$or", "$nor"].contains(&key.as_str()) {
            if let Bson::Array(a) = value {
                let mapped: Vec<Bson> = a
                    .into_iter()
                    .map(|v| match v {
                        Bson::Document(d) => Bson::Document(canonical_filter(d)),
                        other => other,
                    })
                    .collect();
                clauses.push(doc! { key: mapped });
            } else {
                clauses.push(doc! { key: value });
            }
        } else {
            clauses.push(doc! { key: value });
        }
    }
    if clauses.is_empty() {
        Document::new()
    } else {
        doc! { "$and": clauses }
    }
}

pub fn decode(mut raw: Document) -> Result<QuestionDocument, AppError> {
    if raw.get_str("question_type").ok() == Some("OPEN_ENDED")
        || raw.get_str("status").ok() == Some("ARCHIVED")
    {
        return Err(AppError::Conflict(
            "Formato discursivo ou estado arquivado fora da prática objetiva atual".into(),
        ));
    }
    if let Ok(validity) = raw.get_document("validity") {
        let today = crate::services::exam_engine::study_today().to_string();
        if matches!(
            validity.get_str("status").ok(),
            Some("OBSOLETE" | "UNDER_REVIEW")
        ) || validity.get_str("scope").ok() == Some("EXPLICIT_HISTORICAL_DATE")
            || validity.get_str("review_due_on").ok().is_some_and(|due| {
                chrono::NaiveDate::parse_from_str(due, "%Y-%m-%d").is_err() || due < today.as_str()
            })
        {
            return Err(AppError::Conflict(
                "Questão fora da validade corrente".into(),
            ));
        }
    }
    if let Ok(classification) = raw.get_document("classification").cloned() {
        let version = classification
            .get("catalog_version")
            .and_then(|v| v.as_i64().or_else(|| v.as_i32().map(i64::from)));
        if !version.is_some_and(crate::services::catalog_runtime::version_known) {
            return Err(AppError::Conflict(
                "Versão de classificação desconhecida".into(),
            ));
        }
        if matches!(
            classification.get_str("state").ok(),
            Some("CONFIRMED" | "PARTIAL")
        ) {
            let links = classification
                .get_array("links")
                .map_err(|_| AppError::Conflict("Classificação sem vínculos".into()))?;
            for link in links.iter().filter_map(|l| l.as_document()) {
                let human = link.get_document("provenance").ok().is_some_and(|p| {
                    p.get_str("method").ok() == Some("MANUAL_REVIEW")
                        && p.get_document("actor")
                            .ok()
                            .is_some_and(|a| a.get_str("type").ok() == Some("HUMAN"))
                });
                if !human
                    || !crate::services::catalog_runtime::topic_at(
                        version.unwrap(),
                        link.get_str("subject_id").unwrap_or(""),
                        link.get_str("topic_id").unwrap_or(""),
                    )
                {
                    return Err(AppError::Conflict(
                        "Classificação sem vínculo revisado verificável".into(),
                    ));
                }
            }
            let primary: Vec<_> = links
                .iter()
                .filter_map(|l| l.as_document())
                .filter(|l| {
                    l.get_str("role").ok() == Some("PRIMARY")
                        && l.get_str("coverage").ok() == Some("DIRECT")
                })
                .collect();
            if primary.is_empty() && classification.get_str("state").ok() == Some("PARTIAL") {
                raw.insert("canonical_subject_id", Bson::Null);
                raw.insert("canonical_topic_id", Bson::Null);
            } else {
                if primary.len() != 1 {
                    return Err(AppError::Conflict("Classificação principal ambígua".into()));
                }
                let subject = primary[0]
                    .get_str("subject_id")
                    .map_err(|_| AppError::Conflict("Disciplina ausente".into()))?
                    .to_owned();
                let topic = primary[0]
                    .get_str("topic_id")
                    .map_err(|_| AppError::Conflict("Tópico ausente".into()))?
                    .to_owned();
                for (field, value) in [
                    ("canonical_subject_id", subject),
                    ("canonical_topic_id", topic),
                ] {
                    if let Ok(old) = raw.get_str(field) {
                        if old != value {
                            return Err(AppError::Conflict(
                                "Vínculo canônico divergente".into(),
                            ));
                        }
                    }
                    raw.insert(field, value);
                }
            }
        } else {
            raw.insert("canonical_subject_id", Bson::Null);
            raw.insert("canonical_topic_id", Bson::Null);
        }
    } else {
        raw.insert("canonical_subject_id", Bson::Null);
        raw.insert("canonical_topic_id", Bson::Null);
    }

    let mut q: QuestionDocument = bson::from_document(raw.clone())
        .map_err(|_| AppError::Conflict("Documento de questão inválido".into()))?;
    let computed_rev = q.revision()?;
    if !q.content_revision.is_empty() && q.content_revision != computed_rev {
        return Err(AppError::Conflict(
            "Revisão de conteúdo divergente do enunciado atual".into(),
        ));
    }
    q.content_revision = computed_rev;
    if let (Some(subject), Some(topic)) = (&q.canonical_subject_id, &q.canonical_topic_id) {
        if !crate::services::question_contracts::known_topic(subject, topic) {
            return Err(AppError::Conflict(
                "Conteúdo desativado no catálogo corrente; histórico preservado".into(),
            ));
        }
    }
    q.knowledge_binding_verified = crate::services::knowledge::reviewed_binding(&raw, &q);
    Ok(q)
}

#[cfg(test)]
mod tests {
    use super::*;
    use bson::oid::ObjectId;

    #[test]
    fn confirmed_projection_keeps_identity_and_rejects_unreviewed_or_tampered_docs() {
        let mut raw = doc! {
            "_id": ObjectId::new(),
            "question": "Fixture",
            "answers": ["A", "B"],
            "correct_answers": ["B"]
        };
        let before = decode(raw.clone()).unwrap();
        raw.insert(
            "classification",
            doc! {
                "catalog_version": 2_i64,
                "state": "CONFIRMED",
                "links": [{"role": "PRIMARY", "coverage": "DIRECT", "subject_id": "PORT", "topic_id": "PORT_01_001"}]
            },
        );
        assert!(decode(raw.clone()).is_err());
        raw.remove("classification");
        assert_eq!(before.revision().unwrap(), decode(raw).unwrap().revision().unwrap());

        let q: serde_json::Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        let mut raw = bson::Bson::try_from(q)
            .unwrap()
            .as_document()
            .unwrap()
            .clone();
        let projected = decode(raw.clone()).unwrap();
        assert_eq!(projected.canonical_topic_id.as_deref(), Some("MAT_01_001"));
        assert!(projected.knowledge_binding_verified);
        raw.insert("canonical_topic_id", "OTHER");
        assert!(decode(raw).is_err());
    }
}
