//! Finite, demand-driven exception for the frozen legacy cohort. Never a human review.
use super::{classification, question_storage::collection};
use crate::{
    config::{AppConfig, XAI_MODEL},
    error::AppError,
    models::question::QuestionDocument,
    AppState,
};
use bson::{doc, oid::ObjectId, Bson, Document};
use mongodb::{options::ReturnDocument, Database};
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    sync::{Arc, OnceLock},
    time::Duration,
};

pub const POLICY: &str = "legacy-618-auto/1";
pub const COHORT: &str = "legacy-618/2026-11-02";
pub const DAILY_LIMIT: i64 = 20;
pub const QUEUE_LIMIT: i64 = 8;
const CONTROL: &str = "legacy-auto:control/1";
const GUARD: &str = "legacy-cohort-guard/1";
fn conflict(text: &str) -> AppError {
    AppError::Conflict(text.into())
}
fn recorded_model(value: &Value) -> bool {
    matches!(value.as_str(), Some("grok-4.3" | XAI_MODEL))
}
fn cohort() -> &'static Value {
    static C: OnceLock<Value> = OnceLock::new();
    C.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../../shared/legacy-classification-cohort.json"
        ))
        .expect("frozen cohort")
    })
}
fn member_revision(id: &str) -> Option<String> {
    #[cfg(test)]
    if tests::IDS.contains(&id) {
        let q: QuestionDocument = bson::from_document(tests::fixture(id)).ok()?;
        return q.revision().ok();
    }
    cohort()["members"]
        .as_array()?
        .iter()
        .find(|m| m["question_id"] == id && m["blocked"] == false)?["question_revision"]
        .as_str()
        .map(str::to_owned)
}
fn eligible(raw: &Document) -> bool {
    if raw.contains_key("classification")
        || raw.contains_key("legacy_auto_classification")
        || raw
            .get("canonical_subject_id")
            .is_some_and(|v| v != &Bson::Null)
        || raw
            .get("canonical_topic_id")
            .is_some_and(|v| v != &Bson::Null)
        || matches!(raw.get_i32("schema_version"), Ok(2))
    {
        return false;
    }
    let Ok(q) = super::question_compatibility::decode(raw.clone()) else {
        return false;
    };
    member_revision(&q.id.to_hex())
        .is_some_and(|revision| q.revision().ok().as_deref() == Some(revision.as_str()))
        && q.question.len() + q.answers.iter().map(String::len).sum::<usize>() <= 16_000
        && classification::content_revision_of(raw).is_ok()
}
fn enabled(config: &AppConfig) -> bool {
    config.legacy_auto_classification_enabled
        && super::catalog_runtime::snapshot().version==1
        && config.xai_api_key.is_some()
        && config.xai_model == XAI_MODEL
}
async fn control(db: &Database) -> Result<Document, AppError> {
    let c = collection(db, "question_commands");
    if let Err(error)=c.update_one(doc!{"_id":CONTROL},doc!{"$setOnInsert":{"queue":[],"attempted":[],"day":"","daily_reserved":0_i64,"lease_until":bson::DateTime::from_millis(0)}}).upsert(true).await {
        if !error.to_string().contains("E11000"){return Err(error.into())}
    }
    Ok(c.find_one(doc! {"_id":CONTROL})
        .await?
        .expect("control upsert"))
}
/// Versioned, recoverable tightening of Mongo's legacy escape hatch. The v2
/// schema stays unchanged; only these frozen IDs may retain a legacy shape.
pub async fn prepare_guard(db: &Database) -> Result<(), AppError> {
    let receipts = collection(db, "cogniva_schema_versions");
    if receipts.find_one(doc! {"_id":GUARD}).await?.is_some() {
        return recover_guard(db).await;
    }
    let base = receipts
        .find_one(doc! {"_id":"question-v2:todasQuestoes"})
        .await?
        .ok_or_else(|| conflict("Instale o schema v2 primeiro"))?;
    let before = base
        .get_document("validator")
        .map_err(|_| conflict("Recibo de schema ausente"))?
        .clone();
    let ids: Vec<_> = cohort()["members"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| ObjectId::parse_str(m["question_id"].as_str().unwrap()).expect("cohort ID"))
        .collect();
    let after = doc! {"$and":[before.clone(),{"$or":[{"schema_version":2},{"_id":{"$in":ids}}]}]};
    if collection(db, "todasQuestoes")
        .count_documents(doc! {"$nor":[after.clone()]})
        .await?
        > 0
    {
        return Err(conflict(
            "Documentos fora do inventário legado; revisão necessária",
        ));
    }
    receipts.update_one(doc!{"_id":GUARD},doc!{"$setOnInsert":{"state":"PREPARED","before":before,"after":after,"cohort_id":COHORT,"prepared_at":bson::DateTime::now()}}).upsert(true).await?;
    recover_guard(db).await
}
pub async fn recover_guard(db: &Database) -> Result<(), AppError> {
    use futures::TryStreamExt;
    let receipts = collection(db, "cogniva_schema_versions");
    let Some(m) = receipts.find_one(doc! {"_id":GUARD}).await? else {
        return Ok(());
    };
    let before = m
        .get_document("before")
        .map_err(|_| conflict("Migração sem origem"))?;
    let after = m
        .get_document("after")
        .map_err(|_| conflict("Migração sem destino"))?;
    if m.get_str("cohort_id").ok() != Some(COHORT) {
        return Err(conflict("Inventário da migração divergente"));
    }
    let descriptions: Vec<_> = db
        .list_collections()
        .filter(doc! {"name":"todasQuestoes"})
        .await?
        .try_collect()
        .await?;
    let actual = descriptions
        .first()
        .and_then(|d| d.options.validator.as_ref())
        .ok_or_else(|| conflict("Validador ausente"))?;
    if actual != after {
        if actual != before || m.get_str("state").ok() != Some("PREPARED") {
            return Err(conflict("Validador alterado fora da migração"));
        }
        if collection(db, "todasQuestoes")
            .count_documents(doc! {"$nor":[after.clone()]})
            .await?
            > 0
        {
            return Err(conflict("Inventário incompatível com a migração"));
        }
        db.run_command(doc!{"collMod":"todasQuestoes","validator":after.clone(),"validationLevel":"strict","validationAction":"error"}).await?;
    }
    let base = receipts
        .find_one(doc! {"_id":"question-v2:todasQuestoes"})
        .await?
        .ok_or_else(|| conflict("Recibo base ausente"))?;
    let current = base
        .get_document("validator")
        .map_err(|_| conflict("Recibo base inválido"))?;
    if current != before && current != after {
        return Err(conflict("Recibo base divergente"));
    }
    receipts
        .update_one(
            doc! {"_id":"question-v2:todasQuestoes","validator":{"$eq":current.clone()}},
            doc! {"$set":{"validator":after.clone(),"legacy_cohort_guard":GUARD}},
        )
        .await?;
    receipts
        .update_one(doc! {"_id":GUARD}, doc! {"$set":{"state":"APPLIED"}})
        .await?;
    Ok(())
}
fn attempted(row: &Document) -> HashSet<String> {
    row.get_array("attempted")
        .into_iter()
        .flatten()
        .filter_map(|v| v.as_str().map(str::to_owned))
        .collect()
}
pub async fn prioritize(db: &Database, questions: &mut [QuestionDocument]) -> Result<(), AppError> {
    let done = attempted(&control(db).await?);
    questions.sort_by_key(|q| {
        !(member_revision(&q.id.to_hex()).is_some()
            && !done.contains(&q.id.to_hex())
            && q.canonical_topic_id.is_none())
    });
    Ok(())
}
pub async fn enqueue(state: &AppState, ids: &[ObjectId]) -> Result<(), AppError> {
    if !enabled(&state.config) {
        return Ok(());
    }
    control(&state.db).await?;
    let commands = collection(&state.db, "question_commands");
    for id in ids.iter().take(120) {
        if member_revision(&id.to_hex()).is_none() {
            continue;
        }
        let Some(raw) = collection(&state.db, "todasQuestoes")
            .find_one(doc! {"_id":id})
            .await?
        else {
            continue;
        };
        if !eligible(&raw) {
            continue;
        }
        let text = id.to_hex();
        commands.update_one(doc!{"_id":CONTROL,"attempted":{"$ne":&text},"queue":{"$ne":&text},"$expr":{"$lt":[{"$size":"$queue"},QUEUE_LIMIT]}},doc!{"$push":{"queue":text}}).await?;
    }
    Ok(())
}
/// Seed only two playable cohort questions per scoped request. Label hints order
/// candidates; they never establish a canonical classification or eligibility.
pub async fn seed(state: &AppState, labels: &[String]) -> Result<(), AppError> {
    if !enabled(&state.config) {
        return Ok(());
    }
    let c = control(&state.db).await?;
    if c.get_array("queue")
        .map(|q| q.len() >= QUEUE_LIMIT as usize)
        .unwrap_or(true)
    {
        return Ok(());
    }
    let done = attempted(&c);
    #[allow(unused_mut)]
    let mut ids: Vec<_> = cohort()["members"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|m| m["blocked"] == false)
        .filter_map(|m| m["question_id"].as_str())
        .filter(|id| !done.contains(*id))
        .filter_map(|id| ObjectId::parse_str(id).ok())
        .collect();
    #[cfg(test)]
    ids.extend(
        tests::IDS
            .iter()
            .filter(|id| !done.contains(**id))
            .map(|id| ObjectId::parse_str(id).unwrap()),
    );
    let mut candidates=super::selection_v2::pool(state,doc!{"_id":{"$in":ids},"canonical_topic_id":Bson::Null,"classification":{"$exists":false},"legacy_auto_classification":{"$exists":false}},10000).await?;
    candidates.sort_by_key(|q| {
        !labels
            .iter()
            .any(|label| label.eq_ignore_ascii_case(&q.disciplina))
    });
    enqueue(
        state,
        &candidates
            .into_iter()
            .take(2)
            .map(|q| q.id)
            .collect::<Vec<_>>(),
    )
    .await
}
fn approved_links(suggestion: &Value) -> Option<Value> {
    let links = suggestion["proposed_links"].as_array()?;
    if links.is_empty()
        || links.len() > 3
        || links
            .iter()
            .any(|l| l["confidence"].as_f64().unwrap_or(0.0) < 0.90)
    {
        return None;
    }
    let normalized = json!({"catalog_version":suggestion["catalog_version"],"links":links});
    classification::parse_model_output_for(&normalized.to_string(),suggestion["catalog_version"].as_i64()?).ok()?;
    if links
        .iter()
        .filter(|l| l["role"] == "PRIMARY" && l["coverage"] == "DIRECT")
        .count()
        != 1
    {
        return None;
    }
    Some(json!(links))
}
/// A separate extension, not classification.CONFIRMED and never HUMAN provenance.
/// Changing content, mirrors, policy, catalog or cohort invalidates this projection.
pub fn binding(raw: &Document) -> Option<(String, String)> {
    if raw.contains_key("classification") {
        return None;
    }
    let id = raw.get_object_id("_id").ok()?.to_hex();
    let revision = member_revision(&id)?;
    let a: Value = Bson::Document(raw.get_document("legacy_auto_classification").ok()?.clone())
        .into_relaxed_extjson();
    static SCHEMA: OnceLock<jsonschema::Validator> = OnceLock::new();
    if !SCHEMA
        .get_or_init(|| {
            jsonschema::options()
                .should_validate_formats(true)
                .build(
                    &serde_json::from_str::<Value>(include_str!(
                        "../../../shared/schemas/legacy-auto-classification.schema.json"
                    ))
                    .expect("auto schema"),
                )
                .expect("auto validator")
        })
        .is_valid(&a)
    {
        return None;
    }
    if a["policy_version"] != POLICY
        || a["cohort_id"] != COHORT
        || a["status"] != "AUTO_APPLIED"
        || a["schema_version"] != 1
        || a["operation_id"] != format!("legacy-auto:{id}")
        || a["provenance"]["actor"]["type"] != "AI"
        || a["provenance"]["method"] != "AI_AUTOMATIC_LEGACY"
        || !recorded_model(&a["provenance"]["actor"]["id"])
        || a["provenance"]["model"] != a["provenance"]["actor"]["id"]
        || a["provenance"]["prompt_id"] != classification::PROMPT_ID
        || a["provenance"]["prompt_version"] != classification::PROMPT_VERSION
        || a["provenance"]["prompt_sha256"] != classification::prompt_sha256()
        || !a["catalog_version"].as_i64().is_some_and(super::catalog_runtime::version_known)
        || a["content_revision"].as_str()
            != Some(classification::content_revision_of(raw).ok()?.as_str())
    {
        return None;
    }
    let q: QuestionDocument = bson::from_document(raw.clone()).ok()?;
    if q.revision().ok()?.as_str() != revision {
        return None;
    }
    let links = approved_links(
        &json!({"catalog_version":a["catalog_version"],"proposed_links":a["links"]}),
    )?;
    let primary = links.as_array()?.iter().find(|l| l["role"] == "PRIMARY")?;
    let subject = primary["subject_id"].as_str()?;
    let topic = primary["topic_id"].as_str()?;
    if !super::question_contracts::known_topic(subject,topic){return None;}
    if raw.get_str("canonical_subject_id").ok() != Some(subject)
        || raw.get_str("canonical_topic_id").ok() != Some(topic)
    {
        return None;
    }
    Some((subject.into(), topic.into()))
}
async fn process(db: &Database, config: &AppConfig, id: &str) -> Result<(), AppError> {
    let commands = collection(db, "question_commands");
    let job = format!("legacy-auto:{id}");
    // _id uniqueness is permanent: a timeout/crash cannot authorize a second call.
    if commands.find_one(doc! {"_id":&job}).await?.is_some() {
        return Ok(());
    }
    let oid = ObjectId::parse_str(id)?;
    let Some(raw) = collection(db, "todasQuestoes")
        .find_one(doc! {"_id":oid})
        .await?
    else {
        return Ok(());
    };
    if !eligible(&raw) {
        return Ok(());
    }
    let original = raw.clone();
    let content = classification::content_revision_of(&raw)?;
    if let Err(error)=commands.insert_one(doc!{"_id":&job,"status":"RESERVED","question_id":id,"original":&original,"content_revision":&content,"policy_version":POLICY,"reserved_at":bson::DateTime::now()}).await {
        if error.to_string().contains("E11000"){return Ok(())}return Err(error.into())
    }
    // Any earlier human-review proposal, including failed/reserved provider work,
    // is reused or left for review. generate_one shares its durable provider fence.
    let prior = collection(db, "question_classification_suggestions")
        .find_one(doc! {"question_id":id})
        .await?;
    let generated = if let Some(proposal) = prior {
        json!({"suggestion":Bson::Document(proposal).into_relaxed_extjson()})
    } else {
        classification::generate_one(db, config, id, None, &job).await?
    };
    let suggestion = &generated["suggestion"];
    let valid = suggestion["status"] == "PENDING"
        && suggestion["question_content_revision"] == content
        && suggestion["expected_classification_revision"] == 0;
    let links = if valid {
        approved_links(suggestion)
    } else {
        None
    };
    let Some(links) = links else {
        commands
            .update_one(
                doc! {"_id":&job},
                doc! {"$set":{"status":"PENDING_REVIEW","finished_at":bson::DateTime::now()}},
            )
            .await?;
        return Ok(());
    };
    let primary = links
        .as_array()
        .unwrap()
        .iter()
        .find(|l| l["role"] == "PRIMARY")
        .unwrap();
    let a = json!({"schema_version":1,"policy_version":POLICY,"cohort_id":COHORT,"status":"AUTO_APPLIED","operation_id":job,
        "content_revision":content,"catalog_version":suggestion["catalog_version"],"links":links,
        "provenance":{"actor":{"type":"AI","id":XAI_MODEL},"method":"AI_AUTOMATIC_LEGACY","model":XAI_MODEL,"prompt_id":classification::PROMPT_ID,"prompt_version":classification::PROMPT_VERSION,"prompt_sha256":classification::prompt_sha256(),"source_suggestion_id":suggestion["_id"],"recorded_at":chrono::Utc::now().to_rfc3339(),"rationale":"Aplicação automática autorizada exclusivamente para o acervo legado; não constitui revisão humana."}});
    let extension =
        Bson::try_from(a).map_err(|_| AppError::Internal("Auditoria inválida".into()))?;
    // Exact original snapshot CAS also protects concurrent editors and tutor writes.
    let changed=collection(db,"todasQuestoes").update_one(doc!{"_id":oid,"$expr":{"$eq":["$$ROOT",{"$literal":original}]}},doc!{"$set":{"legacy_auto_classification":extension,"canonical_subject_id":primary["subject_id"].as_str().unwrap(),"canonical_topic_id":primary["topic_id"].as_str().unwrap()}}).await?;
    commands.update_one(doc!{"_id":&job},doc!{"$set":{"status":if changed.modified_count==1{"AUTO_APPLIED"}else{"STALE"},"finished_at":bson::DateTime::now()}}).await?;
    Ok(())
}
pub async fn recover_jobs(db: &Database) -> Result<(), AppError> {
    use futures::TryStreamExt;
    let commands = collection(db, "question_commands");
    let mut rows = commands
        .find(doc! {"_id":{"$regex":"^legacy-auto:[0-9a-f]{24}$"},"status":"RESERVED"})
        .await?;
    while let Some(job) = rows.try_next().await? {
        let id = job
            .get_str("question_id")
            .map_err(|_| conflict("Auditoria legada sem identidade"))?;
        let raw = collection(db, "todasQuestoes")
            .find_one(doc! {"_id":ObjectId::parse_str(id)?})
            .await?;
        let applied = raw.as_ref().is_some_and(|q| {
            binding(q).is_some()
                && q.get_document("legacy_auto_classification")
                    .ok()
                    .and_then(|a| a.get_str("operation_id").ok())
                    == job.get_str("_id").ok()
        });
        let expired = job.get_datetime("reserved_at").is_ok_and(|at| {
            at.timestamp_millis() + 120_000 < bson::DateTime::now().timestamp_millis()
        });
        if applied || expired {
            commands.update_one(doc!{"_id":job.get_str("_id").unwrap(),"status":"RESERVED"},doc!{"$set":{"status":if applied{"AUTO_APPLIED"}else{"UNKNOWN_NO_RETRY"},"recovered_at":bson::DateTime::now()}}).await?;
        }
    }
    Ok(())
}
pub async fn tick(state: &AppState) -> Result<(), AppError> {
    if !enabled(&state.config) {
        return Ok(());
    }
    let commands = collection(&state.db, "question_commands");
    // Idle/exhausted queues do not continuously write leases or daily counters.
    let Some(pending) = commands.find_one(doc! {"_id":CONTROL}).await? else {
        return Ok(());
    };
    let today = super::exam_engine::study_today().to_string();
    if pending
        .get_array("queue")
        .map(|q| q.is_empty())
        .unwrap_or(true)
        || (pending.get_str("day").ok() == Some(today.as_str())
            && pending.get_i64("daily_reserved").unwrap_or(DAILY_LIMIT) >= DAILY_LIMIT)
    {
        return Ok(());
    }
    recover_jobs(&state.db).await?;
    let now = bson::DateTime::now();
    let owner = uuid::Uuid::new_v4().to_string();
    let locked=commands.find_one_and_update(doc!{"_id":CONTROL,"lease_until":{"$lt":now}},doc!{"$set":{"lease_until":bson::DateTime::from_millis(now.timestamp_millis()+120_000),"owner":&owner}}).return_document(ReturnDocument::After).await?;
    let Some(mut row) = locked else { return Ok(()) };
    let result=async {
        let day=super::exam_engine::study_today().to_string();
        if row.get_str("day").ok()!=Some(day.as_str()){
            commands.update_one(doc!{"_id":CONTROL,"owner":&owner},doc!{"$set":{"day":&day,"daily_reserved":0_i64}}).await?;
            row.insert("daily_reserved",0_i64);
        }
        let Some(id)=row.get_array("queue").ok().and_then(|q|q.first()).and_then(Bson::as_str) else{return Ok::<(),AppError>(())};
        let reserved=commands.update_one(doc!{"_id":CONTROL,"owner":&owner,"day":&day,"daily_reserved":{"$lt":DAILY_LIMIT},"attempted":{"$ne":id}},doc!{"$inc":{"daily_reserved":1_i64},"$addToSet":{"attempted":id},"$pull":{"queue":id}}).await?;
        if reserved.modified_count==1 {
            if let Err(error)=process(&state.db,&state.config,id).await {
                commands.update_one(doc!{"_id":format!("legacy-auto:{id}")},doc!{"$set":{"status":"FAILED_NO_RETRY","failure":error.to_string()}}).await?;
                tracing::warn!("Classificação legada falhou; sem repetição automática: {}",id);
            }
        }
        Ok(())
    }.await;
    commands
        .update_one(
            doc! {"_id":CONTROL,"owner":owner},
            doc! {"$set":{"lease_until":bson::DateTime::from_millis(0)}},
        )
        .await?;
    result
}
pub fn spawn(state: Arc<AppState>) {
    if !enabled(&state.config) {
        return;
    }
    tokio::spawn(async move {
        loop {
            // No inventory sweep: startup consumes only an existing demand queue.
            tokio::time::sleep(Duration::from_secs(5)).await;
            let result=async{let snapshot=state.catalog.refresh(&state.db).await?;super::catalog_runtime::scope(snapshot,tick(&state)).await}.await;
            if let Err(error) = result {
                tracing::warn!("Fila de classificação legada indisponível: {}", error);
            }
        }
    });
}
pub async fn status(db: &Database, config: &AppConfig) -> Result<Value, AppError> {
    let row = collection(db, "question_commands")
        .find_one(doc! {"_id":CONTROL})
        .await?
        .unwrap_or_default();
    let mut states = serde_json::Map::new();
    for state in [
        "RESERVED",
        "AUTO_APPLIED",
        "PENDING_REVIEW",
        "STALE",
        "FAILED_NO_RETRY",
        "UNKNOWN_NO_RETRY",
    ] {
        states.insert(
            state.into(),
            json!(
                collection(db, "question_commands")
                    .count_documents(
                        doc! {"_id":{"$regex":"^legacy-auto:[0-9a-f]{24}$"},"status":state}
                    )
                    .await?
            ),
        );
    }
    let day = super::exam_engine::study_today().to_string();
    Ok(
        json!({"enabled":enabled(config),"policy_version":POLICY,"cohort_id":COHORT,"cohort_total":618,"cohort_eligible":612,"blocked":6,
        "day":day,"daily_limit":DAILY_LIMIT,"daily_reserved":if row.get_str("day").ok()==Some(day.as_str()){row.get_i64("daily_reserved").unwrap_or(0)}else{0},
        "queue_limit":QUEUE_LIMIT,"queued":row.get_array("queue").map(|q|q.len()).unwrap_or(0),"attempted":attempted(&row).len(),"jobs":states,
        "mongo_guard_installed":collection(db,"cogniva_schema_versions").find_one(doc!{"_id":GUARD,"state":"APPLIED"}).await?.is_some()}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    pub const IDS: [&str; 10] = [
        "00000000000000000000aa01",
        "00000000000000000000aa02",
        "00000000000000000000aa03",
        "00000000000000000000aa04",
        "00000000000000000000aa05",
        "00000000000000000000aa06",
        "00000000000000000000aa07",
        "00000000000000000000aa08",
        "00000000000000000000aa09",
        "00000000000000000000aa10",
    ];
    pub fn fixture(id: &str) -> Document {
        doc! {"_id":ObjectId::parse_str(id).unwrap(),"question":format!("Synthetic objective fixture {id}"),"answers":["A","B","C","D","E"],"correct_answers":["B"],"disciplina":"Matemática","unknown_original":{"keep":true}}
    }
    fn suggestion(confidence: f64) -> Value {
        json!({"catalog_version":1,"links":[{"subject_id":"MAT","topic_id":"MAT_01_001","role":"PRIMARY","coverage":"DIRECT","rationale":"Synthetic mathematical topic.","confidence":confidence}]})
    }
    #[test]
    fn frozen_membership_and_quality_gate_are_not_open_ended() {
        let c = cohort()["members"].as_array().unwrap();
        assert_eq!(c.len(), 618);
        assert_eq!(c.iter().filter(|m| m["blocked"] == true).count(), 6);
        let unique: HashSet<_> = c
            .iter()
            .map(|m| m["question_id"].as_str().unwrap())
            .collect();
        assert_eq!(unique.len(), 618);
        for m in c {
            assert_eq!(
                member_revision(m["question_id"].as_str().unwrap()).is_some(),
                m["blocked"] == false
            );
        }
        assert!(member_revision(&ObjectId::new().to_hex()).is_none());
        assert!(approved_links(
            &json!({"catalog_version":1,"proposed_links":suggestion(0.89)["links"]})
        )
        .is_none());
        assert!(approved_links(
            &json!({"catalog_version":1,"proposed_links":suggestion(0.95)["links"]})
        )
        .is_some());
        let mut bad = suggestion(0.99);
        bad["links"][0]["topic_id"] = json!("NOT_A_TOPIC");
        assert!(
            approved_links(&json!({"catalog_version":1,"proposed_links":bad["links"]})).is_none()
        );
        let mut q = fixture(IDS[0]);
        q.insert("question", "Changed fixture content");
        assert!(!eligible(&q));
        q = fixture(IDS[0]);
        q.insert("classification", doc! {"state":"UNCLASSIFIED"});
        assert!(!eligible(&q));
        q = fixture(IDS[0]);
        q.insert("correct_answers", vec!["ambiguous"]);
        assert!(!eligible(&q));
    }
    #[tokio::test]
    #[ignore = "Requires dedicated loopback Mongo 27048"]
    async fn bounded_demand_queue_cost_fence_and_auto_projection_survive_restarts() {
        let uri = std::env::var("COGNIVA_TEST_MONGODB_URI").unwrap();
        assert!(uri.starts_with("mongodb://127.0.0.1:27048/"));
        let client = mongodb::Client::with_uri_str(&uri).await.unwrap();
        let db = client.database(&format!("cogniva_test_{}", uuid::Uuid::new_v4().simple()));
        let hits = Arc::new(AtomicUsize::new(0));
        let response_mode = Arc::new(AtomicUsize::new(0));
        let h = hits.clone();
        let mode = response_mode.clone();
        let router=axum::Router::new().route("/responses",axum::routing::post(move|axum::Json(payload):axum::Json<Value>|{
            let h=h.clone();let mode=mode.clone();async move{
                h.fetch_add(1,Ordering::SeqCst);tokio::time::sleep(Duration::from_millis(100)).await;assert_eq!(payload["max_output_tokens"],2048);assert_eq!(payload["store"],false);
                match mode.load(Ordering::SeqCst){
                    2=>(axum::http::StatusCode::BAD_GATEWAY,axum::Json(json!({"status":"failed"}))),
                    _=>{let text=suggestion(if mode.load(Ordering::SeqCst)==1{0.50}else{0.95}).to_string();
                        (axum::http::StatusCode::OK,axum::Json(json!({"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":text}]}]})))}
                }
            }
        }));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        let mut cfg = AppConfig::from_env();
        cfg.legacy_auto_classification_enabled = true;
        cfg.xai_api_key = Some("synthetic-only".into());
        cfg.xai_model = XAI_MODEL.into();
        cfg.xai_base_url = format!("http://{addr}");
        let state = AppState::new(db.clone(), cfg.clone());
        super::super::question_storage::prepare(&db).await.unwrap();
        for id in IDS {
            collection(&db, "todasQuestoes")
                .insert_one(fixture(id))
                .await
                .unwrap();
        }
        let outsider = ObjectId::new();
        collection(&db,"todasQuestoes").insert_one(doc!{"_id":outsider,"question":"Not in cohort","answers":["A","B"],"correct_answers":["A"]}).await.unwrap();
        let all: Vec<_> = IDS
            .iter()
            .map(|id| ObjectId::parse_str(id).unwrap())
            .chain([outsider])
            .collect();
        let (a, b) = tokio::join!(enqueue(&state, &all), enqueue(&state, &all));
        a.unwrap();
        b.unwrap();
        let c = control(&db).await.unwrap();
        assert_eq!(c.get_array("queue").unwrap().len(), 8);
        assert!(!c
            .get_array("queue")
            .unwrap()
            .contains(&Bson::String(outsider.to_hex())));
        let (a, b) = tokio::join!(tick(&state), tick(&state));
        a.unwrap();
        b.unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 1);
        let after = collection(&db, "todasQuestoes")
            .find_one(doc! {"_id":ObjectId::parse_str(IDS[0]).unwrap()})
            .await
            .unwrap()
            .unwrap();
        assert!(binding(&after).is_some());
        assert!(
            super::super::question_compatibility::decode(after.clone())
                .unwrap()
                .knowledge_binding_verified
        );
        let mut original = after.clone();
        for key in [
            "legacy_auto_classification",
            "canonical_subject_id",
            "canonical_topic_id",
        ] {
            original.remove(key);
        }
        assert_eq!(original, fixture(IDS[0]));
        assert!(!after.contains_key("classification"));
        collection(&db, "question_commands")
            .update_one(
                doc! {"_id":format!("legacy-auto:{}",IDS[0])},
                doc! {"$set":{"status":"RESERVED"}},
            )
            .await
            .unwrap();
        recover_jobs(&db).await.unwrap();
        assert_eq!(
            collection(&db, "question_commands")
                .find_one(doc! {"_id":format!("legacy-auto:{}",IDS[0])})
                .await
                .unwrap()
                .unwrap()
                .get_str("status")
                .unwrap(),
            "AUTO_APPLIED"
        );
        assert_eq!(
            after
                .get_document("legacy_auto_classification")
                .unwrap()
                .get_document("provenance")
                .unwrap()
                .get_str("method")
                .unwrap(),
            "AI_AUTOMATIC_LEGACY"
        );
        let mut stale = after.clone();
        stale.insert("question", "Changed body");
        assert!(binding(&stale).is_none());
        assert!(super::super::question_compatibility::decode(stale).is_err());
        let q = super::super::question_compatibility::decode(after.clone()).unwrap();
        let activity = doc! {"quantidade_praticas":2,"dominada":true,"pastSessions":[{"attempt_id":"legacy-unknown","status":true},{"attempt_id":"verified","status":true,"question_revision":q.revision().unwrap()}]};
        let evidence = super::super::knowledge::project(&activity, &after).unwrap();
        assert_eq!(evidence.attempts, 1);
        assert!(!evidence.mastered);
        // Budget survives a fresh AppState, and requests cannot expand it.
        let restarted = AppState::new(db.clone(), cfg.clone());
        collection(&db, "question_commands")
            .update_one(
                doc! {"_id":CONTROL},
                doc! {"$set":{"daily_reserved":DAILY_LIMIT}},
            )
            .await
            .unwrap();
        tick(&restarted).await.unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 1);
        collection(&db, "question_commands")
            .update_one(
                doc! {"_id":CONTROL},
                doc! {"$set":{"daily_reserved":DAILY_LIMIT-1}},
            )
            .await
            .unwrap();
        response_mode.store(1, Ordering::SeqCst);
        tick(&restarted).await.unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 2);
        assert_eq!(
            collection(&db, "question_commands")
                .find_one(doc! {"_id":format!("legacy-auto:{}",IDS[1])})
                .await
                .unwrap()
                .unwrap()
                .get_str("status")
                .unwrap(),
            "PENDING_REVIEW"
        );
        tick(&restarted).await.unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 2);
        // Explicit next-day rollover, provider failure, replay: never a retry.
        collection(&db, "question_commands")
            .update_one(doc! {"_id":CONTROL}, doc! {"$set":{"day":"old-day"}})
            .await
            .unwrap();
        response_mode.store(2, Ordering::SeqCst);
        tick(&restarted).await.unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 3);
        assert_eq!(
            collection(&db, "question_commands")
                .find_one(doc! {"_id":format!("legacy-auto:{}",IDS[2])})
                .await
                .unwrap()
                .unwrap()
                .get_str("status")
                .unwrap(),
            "FAILED_NO_RETRY"
        );
        collection(&db, "question_commands")
            .update_one(doc! {"_id":CONTROL}, doc! {"$set":{"queue":[]}})
            .await
            .unwrap();
        enqueue(&restarted, &all[..3]).await.unwrap();
        tick(&restarted).await.unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 3);
        seed(&restarted, &["Matemática".into()]).await.unwrap();
        assert_eq!(
            control(&db)
                .await
                .unwrap()
                .get_array("queue")
                .unwrap()
                .len(),
            2
        );
        let mut prioritized: Vec<QuestionDocument> = vec![
            bson::from_document(fixture(IDS[0])).unwrap(),
            bson::from_document(fixture(IDS[9])).unwrap(),
        ];
        prioritize(&db, &mut prioritized).await.unwrap();
        assert_eq!(prioritized[0].id.to_hex(), IDS[9]);
        cfg.legacy_auto_classification_enabled = false;
        let disabled = AppState::new(db.clone(), cfg);
        enqueue(&disabled, &all[8..]).await.unwrap();
        tick(&disabled).await.unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 3);
        server.abort();
        db.drop().await.unwrap();
    }
    #[tokio::test]
    #[ignore = "Requires dedicated loopback Mongo 27048 with enableTestCommands=1"]
    async fn mongo_legacy_guard_is_versioned_recoverable_and_rejects_new_legacy_documents() {
        let uri = std::env::var("COGNIVA_TEST_MONGODB_URI").unwrap();
        assert!(uri.starts_with("mongodb://127.0.0.1:27048/"));
        let client = mongodb::Client::with_uri_str(&uri).await.unwrap();
        let db = client.database(&format!("cogniva_test_{}", uuid::Uuid::new_v4().simple()));
        super::super::question_storage::prepare(&db).await.unwrap();
        let id = cohort()["members"][0]["question_id"].as_str().unwrap();
        let mut old = fixture(IDS[0]);
        old.insert("_id", ObjectId::parse_str(id).unwrap());
        collection(&db, "todasQuestoes")
            .insert_one(old.clone())
            .await
            .unwrap();
        let unknown = fixture(IDS[1]);
        collection(&db, "todasQuestoes")
            .insert_one(unknown.clone())
            .await
            .unwrap();
        assert!(prepare_guard(&db).await.is_err());
        assert!(collection(&db, "cogniva_schema_versions")
            .find_one(doc! {"_id":GUARD})
            .await
            .unwrap()
            .is_none());
        collection(&db, "todasQuestoes")
            .delete_one(doc! {"_id":unknown.get_object_id("_id").unwrap()})
            .await
            .unwrap();
        client.database("admin").run_command(doc!{"configureFailPoint":"failCommand","mode":{"times":1},"data":{"failCommands":["collMod"],"errorCode":2}}).await.unwrap();
        assert!(prepare_guard(&db).await.is_err());
        assert_eq!(
            collection(&db, "cogniva_schema_versions")
                .find_one(doc! {"_id":GUARD})
                .await
                .unwrap()
                .unwrap()
                .get_str("state")
                .unwrap(),
            "PREPARED"
        );
        // A fresh preparation repairs the interrupted migration before checking receipts.
        super::super::question_storage::prepare(&db).await.unwrap();
        prepare_guard(&db).await.unwrap();
        assert_eq!(
            collection(&db, "todasQuestoes")
                .find_one(doc! {"_id":ObjectId::parse_str(id).unwrap()})
                .await
                .unwrap()
                .unwrap(),
            old
        );
        assert!(collection(&db, "todasQuestoes")
            .insert_one(unknown.clone())
            .await
            .is_err());
        let mut modern = unknown;
        modern.insert("schema_version", 2_i32);
        assert!(collection(&db, "todasQuestoes")
            .insert_one(modern)
            .await
            .is_err());
        let value: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        let mut valid = Bson::try_from(value)
            .unwrap()
            .as_document()
            .unwrap()
            .clone();
        valid.insert("_id", ObjectId::new());
        collection(&db, "todasQuestoes")
            .insert_one(valid)
            .await
            .unwrap();
        prepare_guard(&db).await.unwrap();
        db.drop().await.unwrap();
    }
}
