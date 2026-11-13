use crate::{error::AppError, services::question_contracts as contracts};
use bson::{doc, oid::ObjectId, Bson, Document};
use futures::TryStreamExt;
use mongodb::{
    options::{Acknowledgment, CollectionOptions, IndexOptions, WriteConcern},
    Database, IndexModel,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::time::Duration;

pub fn collection(db: &Database, name: &str) -> mongodb::Collection<Document> {
    db.collection_with_options(
        name,
        CollectionOptions::builder()
            .write_concern(
                WriteConcern::builder()
                    .w(Acknowledgment::Majority)
                    .journal(true)
                    .build(),
            )
            .build(),
    )
}

fn err(s: &str) -> AppError {
    AppError::Conflict(s.into())
}

fn bson_doc(v: Value) -> Result<Document, AppError> {
    Bson::try_from(v)
        .map_err(|_| err("Documento BSON inválido"))?
        .as_document()
        .cloned()
        .ok_or_else(|| err("Documento esperado"))
}

fn expand(v: &Value, defs: &Value) -> Value {
    if let Some(path) = v.get("$ref").and_then(Value::as_str) {
        return expand(
            &defs[path.strip_prefix("#/$defs/").expect("local reference")],
            defs,
        );
    }
    match v {
        Value::Array(a) => Value::Array(a.iter().map(|x| expand(x, defs)).collect()),
        Value::Object(o) => {
            let mut m = serde_json::Map::new();
            for (k, x) in o {
                if ["$schema", "$id", "$defs", "format", "title", "if", "then"]
                    .contains(&k.as_str())
                {
                    continue;
                }
                if k == "properties" || k == "patternProperties" {
                    m.insert(
                        k.clone(),
                        Value::Object(
                            x.as_object()
                                .unwrap()
                                .iter()
                                .map(|(field, sub)| (field.clone(), expand(sub, defs)))
                                .collect(),
                        ),
                    );
                    continue;
                }
                if k == "const" {
                    m.insert("enum".into(), json!([x]));
                    continue;
                }
                if k == "type" {
                    m.insert(
                        "bsonType".into(),
                        match x.as_str() {
                            Some("boolean") => json!("bool"),
                            Some("integer") => json!(["int", "long"]),
                            Some("number") => json!(["int", "long", "double"]),
                            _ => x.clone(),
                        },
                    );
                    continue;
                }
                if k == "exclusiveMinimum" && x.is_number() {
                    m.insert("minimum".into(), x.clone());
                    m.insert(k.clone(), json!(true));
                    continue;
                }
                m.insert(k.clone(), expand(x, defs));
            }
            if let (Some(condition), Some(then)) = (o.get("if"), o.get("then")) {
                let rule = json!({"anyOf":[{"not":expand(condition,defs)},expand(then,defs)]});
                m.entry("allOf")
                    .or_insert(json!([]))
                    .as_array_mut()
                    .unwrap()
                    .push(rule);
            }
            Value::Object(m)
        }
        _ => v.clone(),
    }
}

pub fn mongo_schema(kind: &str) -> Document {
    let s = contracts::schema(kind);
    let mut expanded = expand(&s, &s["$defs"]);
    if kind == "question" {
        expanded["properties"]["_id"] = json!({"bsonType":"objectId"});
        expanded["properties"]["schema_version"] = json!({"bsonType":["int","long"],"enum":[2]});
    }
    bson_doc(expanded).expect("converted schema")
}

pub async fn prepare(db: &Database) -> Result<Value, AppError> {
    let specs = [
        ("todasQuestoes", "question"),
        ("question_classification_suggestions", "suggestion"),
        ("question_exam_compatibility", "compatibility"),
        ("answer_evaluations", "evaluation"),
    ];
    let receipts = collection(db, "cogniva_schema_versions");
    for (name, kind) in specs {
        let schema = mongo_schema(kind);
        let validator = doc! { "$jsonSchema": schema };
        let source_hash = format!(
            "{:x}",
            Sha256::digest(serde_json::to_vec(&validator).expect("validator JSON"))
        );
        let receipt_key = format!("question-v2:{name}");
        let receipt = receipts.find_one(doc! { "_id": &receipt_key }).await?;
        let descriptions: Vec<_> = db
            .list_collections()
            .filter(doc! { "name": name })
            .await?
            .try_collect()
            .await?;
        let previous = descriptions
            .first()
            .and_then(|d| d.options.validator.clone())
            .unwrap_or_default();
        if receipt
            .as_ref()
            .is_some_and(|r| r.get_str("source_sha256").ok() != Some(source_hash.as_str()))
        {
            return Err(err("Mudança de schema exige migração versionada explícita"));
        }
        let expected = receipt
            .as_ref()
            .and_then(|r| r.get_document("validator").ok());
        if let Some(expected) = expected {
            if expected != &previous {
                return Err(err("Validador mudou fora da migração; revisão necessária"));
            }
        }
        let merged = if let Some(expected) = expected {
            expected.clone()
        } else if previous.is_empty() || previous == validator {
            validator.clone()
        } else {
            doc! { "$and": [previous, validator] }
        };
        if !descriptions.is_empty()
            && db
                .collection::<Document>(name)
                .count_documents(doc! { "$nor": [merged.clone()] })
                .await?
                > 0
        {
            return Err(err(
                "Documentos existentes incompatíveis; migração não altera nem descarta dados",
            ));
        }
        if descriptions.is_empty() {
            db.create_collection(name).validator(merged.clone()).await?;
        } else if expected.is_none() {
            db.run_command(doc! {
                "collMod": name,
                "validator": merged.clone(),
                "validationLevel": "strict",
                "validationAction": "error"
            })
            .await?;
        }
        receipts
            .update_one(
                doc! { "_id": receipt_key },
                doc! {
                    "$setOnInsert": {
                        "validator": merged,
                        "source_sha256": source_hash,
                        "installed_at": bson::DateTime::now()
                    }
                },
            )
            .upsert(true)
            .await?;
    }

    let created_indexes = ensure_indexes(db).await?;
    let payload = contracts::catalog();
    let catalogs = collection(db, "knowledge_catalog_versions");
    catalogs
        .create_index(
            IndexModel::builder()
                .keys(doc! { "catalog_id": 1, "version": 1 })
                .options(
                    IndexOptions::builder()
                        .name("qv2_catalog_version".to_string())
                        .unique(true)
                        .build(),
                )
                .build(),
        )
        .await?;
    let catalog_id = payload["catalog_id"].as_str().expect("catalog id");
    let catalog_version = payload["catalog_version"]
        .as_i64()
        .expect("catalog version");
    let key = format!("{catalog_id}:{catalog_version}");
    if let Some(old) = catalogs.find_one(doc! { "_id": &key }).await? {
        let old: Value = serde_json::to_value(old.get("payload").unwrap_or(&Bson::Null))
            .map_err(|_| err("Catálogo armazenado inválido"))?;
        if old != payload {
            return Err(err("Versão de catálogo imutável divergente"));
        }
    } else {
        catalogs
            .insert_one(doc! {
                "_id": &key,
                "catalog_id": catalog_id.to_owned(),
                "version": catalog_version,
                "payload": bson_doc(payload.clone())?
            })
            .await?;
    }
    collection(db, "knowledge_catalog_runtime")
        .update_one(
            doc! { "_id": "active" },
            doc! {
                "$setOnInsert": {
                    "active_version": catalog_version,
                    "activated_at": chrono::Utc::now().to_rfc3339()
                }
            },
        )
        .upsert(true)
        .await?;

    super::exam_store::seed_templates(db).await?;

    receipts
        .update_one(
            doc! { "_id": "question-v2:ready" },
            doc! { "$set": { "ready": true, "version": 2 } },
        )
        .upsert(true)
        .await?;

    Ok(json!({
        "ready": true,
        "schema_version": 2,
        "question_documents_rewritten": 0,
        "indexes": created_indexes.len(),
        "catalog_version": catalog_version
    }))
}

pub async fn ensure_indexes(db: &Database) -> Result<Vec<String>, AppError> {
    let mut created = Vec::new();
    for (name, keys, label) in [
        (
            "todasQuestoes",
            doc! { "canonical_topic_id": 1 },
            "qv2_legacy_topic",
        ),
        (
            "todasQuestoes",
            doc! {
                "classification.links.subject_id": 1,
                "classification.links.topic_id": 1,
                "validity.status": 1
            },
            "qv2_classification_validity",
        ),
        (
            "todasQuestoes",
            doc! { "validity.status": 1, "validity.review_due_on": 1, "question_type": 1 },
            "qv2_validity_type",
        ),
        (
            "todasQuestoes",
            doc! { "status": 1, "disciplina": 1 },
            "qv2_disciplina_status",
        ),
        (
            "question_classification_suggestions",
            doc! { "question_id": 1, "status": 1, "question_content_revision": 1 },
            "qv2_pending_classification",
        ),
        (
            "question_exam_compatibility",
            doc! { "owner_user_id": 1, "exam_id": 1, "component_id": 1, "question_id": 1 },
            "qv2_owner_exam_question",
        ),
        (
            "answer_evaluations",
            doc! { "user_id": 1, "attempt_id": 1, "created_at": 1 },
            "qv2_owner_attempt",
        ),
        (
            "question_revision_events",
            doc! { "question_id": 1, "revision": 1 },
            "qv2_question_audit",
        ),
        (
            "userActivityLog",
            doc! { "user_id": 1, "revisao_necessaria": 1 },
            "idx_activity_user_srs",
        ),
        (
            "exam_revisions",
            doc! { "scope": 1, "exam_id": 1, "revision": -1 },
            "idx_exam_revisions_lookup",
        ),
        (
            "sessions",
            doc! { "user_id": 1 },
            "idx_sessions_user_id",
        ),
        (
            "reportedQuestoes",
            doc! { "user_id": 1, "fix.status_solved": 1, "questao_id": 1 },
            "idx_reported_user_status",
        ),
        (
            "reportedQuestoes",
            doc! { "fix.status_solved": 1, "data_report": -1 },
            "idx_reported_status_date",
        ),
    ] {
        collection(db, name)
            .create_index(
                IndexModel::builder()
                    .keys(keys)
                    .options(IndexOptions::builder().name(label.to_string()).build())
                    .build(),
            )
            .await?;
        created.push(label.to_string());
    }

    for (name, keys, label) in [
        (
            "userActivityLog",
            doc! { "user_id": 1, "question_id": 1 },
            "idx_activity_user_question",
        ),
        (
            "attempt_receipts",
            doc! { "user_id": 1, "attempt_id": 1 },
            "idx_receipt_user_attempt",
        ),
        (
            "user_exams",
            doc! { "owner_user_id": 1, "exam_id": 1 },
            "idx_user_exams_owner_exam",
        ),
        (
            "exam_templates",
            doc! { "exam_id": 1 },
            "idx_exam_templates_id",
        ),
        (
            "usuarios",
            doc! { "user_id": 1 },
            "idx_usuarios_user_id",
        ),
        (
            "usuarios",
            doc! { "installation_id": 1 },
            "idx_usuarios_installation_id",
        ),
    ] {
        collection(db, name)
            .create_index(
                IndexModel::builder()
                    .keys(keys)
                    .options(
                        IndexOptions::builder()
                            .name(label.to_string())
                            .unique(true)
                            .sparse(true)
                            .build(),
                    )
                    .build(),
            )
            .await?;
        created.push(label.to_string());
    }

    collection(db, "usuarios")
        .create_index(
            IndexModel::builder()
                .keys(doc! { "email_normalized": 1 })
                .options(
                    IndexOptions::builder()
                        .name("idx_usuarios_email_normalized".to_string())
                        .unique(true)
                        .partial_filter_expression(doc! {
                            "email_normalized": { "$type": "string" }
                        })
                        .build(),
                )
                .build(),
        )
        .await?;
    created.push("idx_usuarios_email_normalized".to_string());

    for (name, label) in [
        ("sessions", "idx_sessions_expires_ttl"),
        ("auth_rate_limits", "idx_auth_rate_limits_ttl"),
    ] {
        collection(db, name)
            .create_index(
                IndexModel::builder()
                    .keys(doc! { "expires_at": 1 })
                    .options(
                        IndexOptions::builder()
                            .name(label.to_string())
                            .expire_after(Duration::ZERO)
                            .build(),
                    )
                    .build(),
            )
            .await?;
        created.push(label.to_string());
    }

    Ok(created)
}

pub async fn flush_audit(db: &Database, id: ObjectId) -> Result<(), AppError> {
    let coll = collection(db, "todasQuestoes");
    if let Some(raw) = coll.find_one(doc! { "_id": id }).await? {
        if let Ok(event) = raw.get_document("schema_outbox") {
            let event_id = event
                .get_str("_id")
                .map_err(|_| err("Outbox sem identidade"))?;
            let mut content = event.clone();
            content.remove("_id");
            collection(db, "question_revision_events")
                .update_one(doc! { "_id": event_id }, doc! { "$setOnInsert": content })
                .upsert(true)
                .await?;
            coll.update_one(
                doc! { "_id": id, "schema_outbox._id": event_id },
                doc! { "$unset": { "schema_outbox": "" } },
            )
            .await?;
        }
    }
    Ok(())
}

pub async fn recover_audit(db: &Database) -> Result<(), AppError> {
    let mut rows = collection(db, "todasQuestoes")
        .find(doc! { "schema_outbox": { "$exists": true } })
        .await?;
    while let Some(row) = rows.try_next().await? {
        flush_audit(
            db,
            row.get_object_id("_id")
                .map_err(|_| err("Identidade de outbox inválida"))?,
        )
        .await?;
    }
    Ok(())
}

#[derive(serde::Deserialize)]
pub struct SaveRequest {
    pub operation_id: String,
    pub expected_revision: i64,
    #[serde(default = "yes")]
    pub dry_run: bool,
    pub document: Value,
}
fn yes() -> bool {
    true
}
fn number(d: &Document, k: &str) -> i64 {
    d.get_i64(k)
        .or_else(|_| d.get_i32(k).map(i64::from))
        .unwrap_or(0)
}
fn stamp(v: &mut Value, user: i64) {
    match v {
        Value::Object(o) => {
            if o.get("actor").is_some_and(|a| a["type"] == "HUMAN") && o.contains_key("method") {
                o.get_mut("actor").unwrap()["id"] = json!(user.to_string());
                o.insert(
                    "recorded_at".into(),
                    json!(chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)),
                );
            }
            for x in o.values_mut() {
                stamp(x, user);
            }
        }
        Value::Array(a) => {
            for x in a {
                stamp(x, user)
            }
        }
        _ => {}
    }
}

pub async fn save(db: &Database, user: i64, r: SaveRequest) -> Result<Value, AppError> {
    save_internal(db, user, r, false).await
}

/// Insertion never upgrades or overwrites an existing question.
pub async fn create(db: &Database, user: i64, r: SaveRequest) -> Result<Value, AppError> {
    save_internal(db, user, r, true).await
}

async fn save_internal(
    db: &Database,
    user: i64,
    mut r: SaveRequest,
    create_only: bool,
) -> Result<Value, AppError> {
    if uuid::Uuid::parse_str(&r.operation_id).is_err()
        || r.expected_revision < 0
        || r.expected_revision >= i64::MAX - 1
    {
        return Err(err("Operação/revisão inválida"));
    }
    if r.document
        .get("explanation")
        .and_then(Value::as_str)
        .is_some_and(|s| s.trim().is_empty())
    {
        r.document["explanation"] = Value::Null;
    }
    if r.document
        .get("level")
        .and_then(Value::as_str)
        .is_some_and(|s| s.trim().is_empty())
    {
        r.document["level"] = Value::Null;
    }
    contracts::validate(&r.document)?;
    if r.document["revision"].as_i64() != Some(r.expected_revision + 1) {
        return Err(err("A revisão deve avançar exatamente uma unidade"));
    }
    let id = ObjectId::parse_str(r.document["_id"]["$oid"].as_str().unwrap())?;
    let hash = format!(
        "{:x}",
        Sha256::digest(
            serde_json_canonicalizer::to_vec(
                &json!({"user":user,"expected":r.expected_revision,"document":r.document})
            )
            .map_err(|_| err("Comando inválido"))?
        )
    );
    let event_id = format!("{user}:{}", r.operation_id);
    let coll = collection(db, "todasQuestoes");
    if !r.dry_run {
        flush_audit(db, id).await?;
    }
    if let Some(receipt) = collection(db, "question_revision_events")
        .find_one(doc! { "_id": &event_id })
        .await?
    {
        if receipt.get_str("payload_sha256").ok() != Some(hash.as_str()) {
            return Err(err("Operação repetida com outro conteúdo"));
        }
        return Ok(json!({
            "status": "already_applied",
            "revision": receipt.get_i64("revision").unwrap_or(r.expected_revision + 1)
        }));
    }
    let raw = coll.find_one(doc! { "_id": id }).await?;
    if create_only && (raw.is_some() || r.expected_revision != 0) {
        return Err(err(
            "Inserção exige uma questão nova; o registro existente foi preservado",
        ));
    }
    if raw.as_ref().map(|d| number(d, "revision")).unwrap_or(0) != r.expected_revision
        || raw.is_none() && r.expected_revision != 0
    {
        return Err(err("Questão mudou; refaça a revisão"));
    }
    validate_transition(raw.as_ref(), &r.document)?;
    for link in r.document["classification"]["links"].as_array().unwrap() {
        if !contracts::known_topic(
            link["subject_id"].as_str().unwrap(),
            link["topic_id"].as_str().unwrap(),
        ) {
            let retained = raw
                .as_ref()
                .and_then(|d| d.get_document("classification").ok())
                .and_then(|d| d.get_array("links").ok())
                .is_some_and(|links| {
                    links.iter().filter_map(|v| v.as_document()).any(|old| {
                        old.get_str("subject_id").ok() == link["subject_id"].as_str()
                            && old.get_str("topic_id").ok() == link["topic_id"].as_str()
                            && old.get_str("role").ok() == link["role"].as_str()
                            && old.get_str("coverage").ok() == link["coverage"].as_str()
                    })
                });
            if !retained {
                return Err(err(
                    "Novos vínculos de questões exigem assunto ativo no catálogo atual",
                ));
            }
        }
    }
    let unchanged_classification = raw.as_ref().is_some_and(|d| {
        Bson::Document(d.clone()).into_relaxed_extjson()["classification"]
            == r.document["classification"]
    });
    if r.document["classification"]["links"]
        .as_array()
        .unwrap()
        .iter()
        .any(|l| !l["provenance"]["source_suggestion_id"].is_null())
        && !unchanged_classification
    {
        return Err(err(
            "Aceitação de sugestões exige o fluxo de revisão da etapa de classificação",
        ));
    }
    if r.dry_run {
        return Ok(json!({"status":"validated_dry_run","revision":r.expected_revision+1}));
    }
    if collection(db, "cogniva_schema_versions")
        .find_one(doc! { "_id": "question-v2:ready", "ready": true })
        .await?
        .is_none()
    {
        return Err(AppError::TemporarilyUnavailable(
            "Prepare os validadores e índices antes da escrita v2".into(),
        ));
    }
    let commands = collection(db, "question_commands");
    if let Some(command) = commands.find_one(doc! { "_id": &event_id }).await? {
        if command.get_str("payload_sha256").ok() != Some(hash.as_str()) {
            return Err(err("Operação já reservada com outro conteúdo"));
        }
    } else {
        commands
            .insert_one(doc! {
                "_id": &event_id,
                "payload_sha256": &hash,
                "question_id": id.to_hex(),
                "actor_user_id": user
            })
            .await?;
    }
    if let Some(old) = &raw {
        let previous = bson::Bson::Document(old.clone()).into_relaxed_extjson();
        for field in ["origins", "classification", "validity"] {
            if previous[field] != r.document[field] {
                stamp(&mut r.document[field], user);
            }
        }
    } else {
        stamp(&mut r.document, user);
    }
    let primary = r.document["classification"]["links"]
        .as_array()
        .unwrap()
        .iter()
        .find(|l| l["role"] == "PRIMARY");
    if primary.is_none()
        && raw
            .as_ref()
            .is_some_and(|d| d.get_str("canonical_topic_id").is_ok())
    {
        return Err(err("Preserve o vínculo canônico confirmado na migração"));
    }
    let mirrors = primary.map(|p| (p["subject_id"].clone(), p["topic_id"].clone()));
    if r.document.get("schema_outbox").is_some() {
        return Err(err("Outbox é preenchida apenas pelo servidor"));
    }

    let previous = raw.clone().unwrap_or_default();
    let content_changed = raw.as_ref().is_some_and(|d| {
        d.get_str("content_revision").ok() != r.document["content_revision"].as_str()
    });

    let incoming_explanation = r
        .document
        .get("explanation")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let old_explanation = raw
        .as_ref()
        .and_then(|d| d.get_str("explanation").ok())
        .map(str::trim);
    let explicit_updated_explanation =
        incoming_explanation.filter(|exp| !content_changed || Some(*exp) != old_explanation);

    let mut update = if raw.is_none() {
        bson_doc(r.document.clone())?
    } else {
        Document::new()
    };
    for key in [
        "schema_version",
        "revision",
        "content_revision",
        "content_hash_version",
        "status",
        "question",
        "question_type",
        "answers",
        "correct_answer_indices",
        "correct_answers",
        "origins",
        "classification",
        "validity",
        "grading",
    ] {
        update.insert(
            key,
            Bson::try_from(r.document[key].clone()).map_err(|_| err("Campo BSON inválido"))?,
        );
    }

    // Persist optional metadata fields on V2 edits (ADR-08)
    for opt_key in ["level", "exam_year", "disciplina", "concursos"] {
        if let Some(val) = r.document.get(opt_key) {
            update.insert(
                opt_key,
                Bson::try_from(val.clone()).map_err(|_| err("Metadado BSON inválido"))?,
            );
        }
    }

    let mut unset_fields = Document::new();
    if raw.is_some() {
        if let Some(exp) = explicit_updated_explanation {
            update.insert("explanation", exp);
        } else if content_changed || r.document.get("explanation") == Some(&Value::Null) {
            unset_fields.insert("explanation", "");
        }
    }

    if let Some(origin) = r.document["origins"]
        .as_array()
        .unwrap()
        .iter()
        .find(|o| o["kind"] == "OFFICIAL_EXAM" && o["verification"] == "CONFIRMED")
    {
        update.insert("source", origin["organizer"].as_str().unwrap());
    } else if raw.as_ref().is_none_or(|d| !d.contains_key("source")) {
        let first = &r.document["origins"][0];
        let label = match first["kind"].as_str() {
            Some("AUTHOR_CREATED") => "Autoral",
            Some("AI_GENERATED") => "Gerada por IA",
            _ => "Origem não informada",
        };
        update.insert("source", label);
    }
    if let Some((s, t)) = mirrors {
        update.insert("canonical_subject_id", Bson::try_from(s).unwrap());
        update.insert("canonical_topic_id", Bson::try_from(t).unwrap());
    } else {
        update.insert("canonical_subject_id", Bson::Null);
        update.insert("canonical_topic_id", Bson::Null);
    }

    let event = doc! {
        "_id": &event_id,
        "question_id": id.to_hex(),
        "revision": r.expected_revision + 1,
        "payload_sha256": &hash,
        "actor_user_id": user,
        "recorded_at": bson::DateTime::now(),
        "previous": previous
    };
    update.insert("schema_outbox", event);

    if raw.is_none() {
        update.insert("_id", id);
        coll.insert_one(update).await?;
    } else {
        let mut filter = doc! { "_id": id, "schema_outbox": { "$exists": false } };
        let old = raw.as_ref().unwrap();
        for key in [
            "revision",
            "schema_version",
            "content_revision",
            "question_type",
            "grading",
            "question",
            "answers",
            "correct_answers",
            "correct_answer_indices",
            "classification",
            "validity",
        ] {
            filter.insert(
                key,
                old.get(key)
                    .cloned()
                    .unwrap_or(Bson::Document(doc! { "$exists": false })),
            );
        }
        if content_changed {
            for key in [
                "explanation",
                "contextualHint",
                "drSincero",
                "ajudaInfantil",
                "explicacaoInfantil",
                "explanationExtra",
                "mnemonico",
                "plausibilityMarkdown",
                "plausibilityAnalysis",
                "ai_resource_meta",
            ] {
                filter.insert(
                    key,
                    old.get(key)
                        .cloned()
                        .unwrap_or(Bson::Document(doc! { "$exists": false })),
                );
            }
            for stale_ai in [
                "contextualHint",
                "drSincero",
                "ajudaInfantil",
                "explicacaoInfantil",
                "explanationExtra",
                "mnemonico",
                "plausibilityMarkdown",
                "plausibilityAnalysis",
                "ai_resource_meta",
            ] {
                unset_fields.insert(stale_ai, "");
            }
        }
        let mut command = doc! { "$set": update };
        if !unset_fields.is_empty() {
            command.insert("$unset", unset_fields);
        }
        let result = coll.update_one(filter, command).await?;
        if result.matched_count != 1 {
            return Err(err(
                "Alteração concorrente; operação não sobrescreveu a questão",
            ));
        }
    }
    flush_audit(db, id).await?;
    Ok(json!({
        "status": "applied",
        "revision": r.expected_revision + 1,
        "id": id.to_hex()
    }))
}

fn validate_transition(old: Option<&Document>, next: &Value) -> Result<(), AppError> {
    if let Some(old) = old {
        if old.contains_key("revision")
            && old.get_i64("revision").is_err()
            && old.get_i32("revision").is_err()
        {
            return Err(err("Revisão legada ambígua"));
        }
        let previous = Bson::Document(old.clone()).into_relaxed_extjson();
        for field in ["classification", "validity"] {
            let prior = &previous[field];
            let n = next[field]["revision"].as_i64().unwrap();
            if prior.is_null() {
                if n > 1 {
                    return Err(err("Bloco novo deve iniciar com revisão zero ou um"));
                }
            } else {
                let p = prior["revision"].as_i64().unwrap_or(0);
                let changed = prior != &next[field];
                if changed && n != p + 1 || !changed && n != p {
                    return Err(err("Revisão de classificação/validade divergente"));
                }
            }
        }
        if old.get_str("canonical_topic_id").is_ok()
            && !next["classification"]["links"]
                .as_array()
                .unwrap()
                .iter()
                .any(|l| l["role"] == "PRIMARY")
        {
            return Err(err("Preserve o vínculo canônico confirmado na migração"));
        }
    }
    Ok(())
}
