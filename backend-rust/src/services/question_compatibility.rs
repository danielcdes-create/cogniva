//! Additive read adapter. It never writes inferred classifications to Mongo.
use crate::{error::AppError, models::question::QuestionDocument};
use bson::Document;

pub fn canonical_filter(filter: Document) -> Document {
    use bson::{doc, Bson};
    let mut clauses = Vec::new();
    for (key, value) in filter {
        if key == "canonical_subject_id" || key == "canonical_topic_id" {
            let leaf = if key == "canonical_subject_id" {
                "subject_id"
            } else {
                "topic_id"
            };
            let mut link = doc! {"role":"PRIMARY","coverage":"DIRECT"};
            link.insert(leaf, value.clone());
            let legacy = if value == Bson::Null {
                doc! {"$and":[{key.clone():value.clone()},{"schema_version":{"$ne":2}}]}
            } else {
                doc! {key.clone():value.clone()}
            };
            clauses.push(doc!{"$or":[legacy,{"schema_version":2,"classification.state":{"$in":["CONFIRMED","PARTIAL"]},"classification.links":{"$elemMatch":link}}]});
        } else if ["$and", "$or", "$nor"].contains(&key.as_str()) {
            if let Bson::Array(a) = value {
                let mapped: Vec<Bson> = a
                    .into_iter()
                    .map(|v| match v {
                        Bson::Document(d) => Bson::Document(canonical_filter(d)),
                        other => other,
                    })
                    .collect();
                clauses.push(doc! {key:mapped});
            } else {
                clauses.push(doc! {key:value});
            }
        } else {
            clauses.push(doc! {key:value});
        }
    }
    if clauses.is_empty() {
        Document::new()
    } else {
        doc! {"$and":clauses}
    }
}

pub fn decode(mut raw: Document) -> Result<QuestionDocument, AppError> {
    let modern = raw.get_i32("schema_version").ok() == Some(2)
        || raw.get_i64("schema_version").ok() == Some(2);
    if modern {
        crate::services::question_contracts::validate(
            &bson::Bson::Document(raw.clone()).into_relaxed_extjson(),
        )?;
    }
    let modern_unknown = raw
        .get_document("validity")
        .ok()
        .and_then(|v| v.get_str("status").ok())
        == Some("UNKNOWN");
    if (!modern || modern_unknown)
        && raw
            .get_document("legal_validity")
            .ok()
            .and_then(|v| v.get_str("status").ok())
            .is_some_and(|s| s != "ACTIVE" && s != "VIGENTE")
    {
        return Err(AppError::Conflict("Validade legada exige revisão".into()));
    }
    if raw.get_str("question_type").ok() == Some("OPEN_ENDED")
        || raw.get_str("status").ok() == Some("ARCHIVED")
    {
        return Err(AppError::Conflict(
            "Formato ou estado fora da prática atual".into(),
        ));
    }
    if let Ok(validity) = raw.get_document("validity") {
        let today = crate::services::exam_engine::study_today().to_string();
        if matches!(
            validity.get_str("status").ok(),
            Some("OBSOLETE" | "UNDER_REVIEW")
        ) || validity.get_str("scope").ok() == Some("EXPLICIT_HISTORICAL_DATE")
            || validity
                .get_str("review_due_on")
                .ok()
                .is_some_and(|due| due < today.as_str())
        {
            return Err(AppError::Conflict(
                "Questão fora da validade corrente".into(),
            ));
        }
    }
    if let Some(classification) = modern
        .then(|| raw.get_document("classification").ok())
        .flatten()
    {
        if matches!(
            classification.get_str("state").ok(),
            Some("CONFIRMED" | "PARTIAL")
        ) {
            let links = classification
                .get_array("links")
                .map_err(|_| AppError::Conflict("Classificação sem vínculos".into()))?;
            let primary: Vec<_> = links
                .iter()
                .filter_map(|l| l.as_document())
                .filter(|l| {
                    l.get_str("role").ok() == Some("PRIMARY")
                        && l.get_str("coverage").ok() == Some("DIRECT")
                })
                .collect();
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
                            "Vínculo canônico legado divergente".into(),
                        ));
                    }
                }
                raw.insert(field, value);
            }
        }
    }
    bson::from_document(raw).map_err(|_| AppError::Conflict("Documento de questão inválido".into()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use bson::{doc, oid::ObjectId};
    #[test]
    fn legacy_and_confirmed_projection_keep_identity_and_answer_key() {
        let mut raw = doc! {"_id":ObjectId::new(),"question":"Fixture","answers":["A","B"],"correct_answers":["B"]};
        let before = decode(raw.clone()).unwrap();
        raw.insert("classification",doc!{"state":"CONFIRMED","links":[{"role":"PRIMARY","coverage":"DIRECT","subject_id":"PORT","topic_id":"PORT_01"}]});
        let unchanged = decode(raw.clone()).unwrap();
        assert_eq!(before.id, unchanged.id);
        assert_eq!(before.revision().unwrap(), unchanged.revision().unwrap());
        assert!(unchanged.canonical_topic_id.is_none());
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
        raw.insert("canonical_topic_id", "OTHER");
        assert!(decode(raw).is_err());
    }
}
