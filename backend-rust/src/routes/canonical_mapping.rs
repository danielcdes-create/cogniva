//! Explicit operator-reviewed mappings. No label inference or historical array rewrite.
use crate::{
    error::AppError, models::question::QuestionDocument, security::SessionUser,
    AppState,
};
use axum::{extract::State, Extension, Json};
use bson::{doc, oid::ObjectId, Document};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::HashSet, sync::Arc};

fn dry_run_default() -> bool {
    true
}
#[derive(Deserialize)]
pub struct MappingRequest {
    #[serde(default = "dry_run_default")]
    pub dry_run: bool,
    pub entries: Vec<MappingEntry>,
}
#[derive(Deserialize, Serialize)]
pub struct MappingEntry {
    pub operation_id: String,
    pub question_id: String,
    pub question_revision: String,
    pub expected_mapping_revision: i64,
    pub canonical_subject_id: String,
    pub canonical_topic_id: String,
    pub reviewed_reason: String,
}
#[derive(Serialize)]
pub struct MappingResult {
    pub operation_id: String,
    pub question_id: String,
    pub status: String,
    pub mapping_revision: i64,
}

pub async fn apply(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(request): Json<MappingRequest>,
) -> Result<Json<Vec<MappingResult>>, AppError> {
    if request.entries.is_empty() || request.entries.len() > 100 {
        return Err(AppError::BadRequest(
            "Revise de 1 a 100 vínculos explícitos por lote".into(),
        ));
    }

    let coll = state.db.collection::<Document>("todasQuestoes");
    let mut plans = Vec::new();
    let mut seen = HashSet::new();
    for entry in request.entries {
        if uuid::Uuid::parse_str(&entry.operation_id).is_err()
            || !seen.insert(entry.question_id.clone())
            || !(10..=1000).contains(&entry.reviewed_reason.trim().len())
        {
            return Err(AppError::BadRequest(
                "Operação, justificativa ou identidade repetida inválida".into(),
            ));
        }
        if !crate::services::question_contracts::known_topic(&entry.canonical_subject_id,&entry.canonical_topic_id) {
            return Err(AppError::BadRequest("Vínculo fora do catálogo".into()));
        }
        let oid = ObjectId::parse_str(&entry.question_id)?;
        let raw = coll
            .find_one(doc! {"_id":oid})
            .await?
            .ok_or_else(|| AppError::NotFound("Questão ausente".into()))?;
        if raw.get_i32("schema_version").ok()==Some(2) || raw.get_i64("schema_version").ok()==Some(2) || raw.contains_key("classification") {return Err(AppError::Conflict("Questões v2 devem ser editadas pelo endpoint versionado".into()));}
        let hash = format!(
            "{:x}",
            Sha256::digest(
                serde_json::to_vec(&entry)
                    .map_err(|_| AppError::BadRequest("Plano inválido".into()))?
            )
        );
        let receipt = raw
            .get_array("canonical_mapping_history")
            .ok()
            .and_then(|items| {
                items
                    .iter()
                    .filter_map(|v| v.as_document())
                    .find(|r| r.get_str("operation_id").ok() == Some(&entry.operation_id))
            });
        if let Some(receipt) = receipt {
            if receipt.get_str("payload_sha256").ok() != Some(&hash) {
                return Err(AppError::Conflict(
                    "Operação de vínculo repetida com conteúdo diferente".into(),
                ));
            }
            plans.push((entry, raw, None, hash));
            continue;
        }
        let q: QuestionDocument = bson::from_document(raw.clone())
            .map_err(|_| AppError::Conflict("Formato da questão exige revisão".into()))?;
        let revision = raw.get_i64("canonical_mapping_revision").unwrap_or(0);
        if q.revision()? != entry.question_revision || revision != entry.expected_mapping_revision {
            return Err(AppError::Conflict(
                "A questão ou seu vínculo mudou; refaça a revisão".into(),
            ));
        }
        plans.push((entry, raw, Some(revision), hash));
    }
    let mut results = Vec::new();
    for (entry, raw, revision, hash) in plans {
        let current =
            revision.unwrap_or_else(|| raw.get_i64("canonical_mapping_revision").unwrap_or(0));
        let status = if revision.is_none() {
            "already_applied"
        } else if request.dry_run {
            "reviewed_dry_run"
        } else {
            let mut filter = doc! {"_id":ObjectId::parse_str(&entry.question_id)?,"question":raw.get("question").cloned().unwrap_or(bson::Bson::Null),"answers":raw.get("answers").cloned().unwrap_or(bson::Bson::Null)};
            if raw.contains_key("canonical_mapping_revision") {
                filter.insert("canonical_mapping_revision", current);
            } else {
                filter.insert("canonical_mapping_revision", doc! {"$exists":false});
            }
            for field in ["correct_answers", "correct_answer_indices"] {
                filter.insert(field, raw.get(field).cloned().unwrap_or(bson::Bson::Null));
            }
            let result=coll.update_one(filter,doc!{"$set":{"canonical_subject_id":&entry.canonical_subject_id,"canonical_topic_id":&entry.canonical_topic_id,"canonical_mapping_revision":current+1},"$push":{"canonical_mapping_history":{"operation_id":&entry.operation_id,"payload_sha256":hash,"reviewed_by":user.0,"reviewed_reason":&entry.reviewed_reason,"applied_at":bson::DateTime::now(),"previous_subject":raw.get("canonical_subject_id").cloned().unwrap_or(bson::Bson::Null),"previous_topic":raw.get("canonical_topic_id").cloned().unwrap_or(bson::Bson::Null)}}}).await?;
            if result.matched_count == 1 {
                "applied"
            } else {
                "conflict_preserved"
            }
        };
        results.push(MappingResult {
            operation_id: entry.operation_id,
            question_id: entry.question_id,
            status: status.into(),
            mapping_revision: if status == "applied" {
                current + 1
            } else {
                current
            },
        });
    }
    Ok(Json(results))
}
