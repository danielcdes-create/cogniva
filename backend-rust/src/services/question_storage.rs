use crate::{
    error::AppError,
    security::SessionUser,
    services::question_contracts as contracts,
};
use bson::{doc, oid::ObjectId, Bson, Document};
use futures::TryStreamExt;
use mongodb::{
    options::{Acknowledgment, CollectionOptions, IndexOptions, WriteConcern},
    ClientSession, Database, IndexModel,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct ReviewerContext {
    pub user_id: i64,
    pub principal_id: String,
    pub can_confirm_normative: bool,
}

impl ReviewerContext {
    pub fn from_session(user: SessionUser) -> Self {
        Self {
            user_id: user.user_id,
            principal_id: user.user_id.to_string(),
            can_confirm_normative: user.user_id > 0 && user.is_admin && !user.is_guest,
        }
    }

    pub fn admin(user_id: i64) -> Self {
        Self {
            user_id,
            principal_id: user_id.to_string(),
            can_confirm_normative: user_id > 0,
        }
    }

    pub fn pending_only(user_id: i64) -> Self {
        Self {
            user_id,
            principal_id: user_id.to_string(),
            can_confirm_normative: false,
        }
    }
}

pub fn deterministic_review_event_uuid(seed: &str) -> String {
    let bytes = Sha256::digest(seed.as_bytes());
    let mut raw = [0u8; 16];
    raw.copy_from_slice(&bytes[..16]);
    raw[6] = (raw[6] & 0x0f) | 0x40;
    raw[8] = (raw[8] & 0x3f) | 0x80;
    uuid::Uuid::from_bytes(raw).to_string()
}

pub async fn topology_supports_transactions(db: &Database) -> bool {
    match db.run_command(doc! { "hello": 1 }).await {
        Ok(reply) => {
            reply.get_str("setName").is_ok()
                || reply.get_str("msg").ok() == Some("isdbgrid")
        }
        Err(_) => false,
    }
}

pub fn is_duplicate_key_error(error: &mongodb::error::Error) -> bool {
    let msg = error.to_string();
    msg.contains("E11000") || msg.contains("code: 11000") || msg.contains("code 11000")
}

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

pub fn mongo_schema_from_bundle(bundle: &Value, kind: &str) -> Document {
    let s = json!({
        "$schema": bundle["$schema"],
        "$ref": format!("#/$defs/{kind}"),
        "$defs": bundle["$defs"]
    });
    let mut expanded = expand(&s, &s["$defs"]);
    if kind == "question" {
        expanded["properties"]["_id"] = json!({"bsonType":"objectId"});
        expanded["properties"]["schema_version"] = json!({"bsonType":["int","long"],"enum":[2]});
    }
    bson_doc(expanded).expect("converted schema")
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

pub fn validator_source_sha256(validator: &Document) -> String {
    format!(
        "{:x}",
        Sha256::digest(serde_json::to_vec(validator).expect("validator JSON"))
    )
}

pub const CURRENT_VALIDATOR_REVISION: i32 = 6;
pub const VALIDATOR_MIGRATION_V2_0_1: &str = "question-v2-validator-2.0.1";
pub const VALIDATOR_MIGRATION_V2_0_2: &str = "question-v2-validator-2.0.2";
pub const VALIDATOR_MIGRATION_V2_1_0: &str = "question-v2-validator-2.1.0";
pub const VALIDATOR_MIGRATION_V2_2_0: &str = "question-v2-validator-2.2.0";
pub const VALIDATOR_MIGRATION_V2_3_0: &str = "question-v2-validator-2.3.0";

pub const QUESTION_VALIDATOR_V2_0_2_SHA256: &str =
    "eadf843691163ff7f6d08ce266484038f4f4499f7d5157b99c3e31d4073aa6c9";
pub const QUESTION_VALIDATOR_V2_1_0_SHA256: &str =
    "83925ee70854b73c09f6536e8f7a1a080378cb998c5fcaa787819a1c3178577c";
pub const QUESTION_VALIDATOR_V2_2_0_SHA256: &str =
    "3531adbb29802f6191f657a2483e10e9f39a010b011f6082cd1b40ee4500fd16";
pub const QUESTION_VALIDATOR_V2_3_0_SHA256: &str =
    "3b2059e8acbe7da32481e2c961bfc7740cf122b6647da7bf506426b8509b1c80";
pub const SUGGESTION_VALIDATOR_V2_1_0_SHA256: &str =
    "f1204f7bfba57ceebf2603cccfef008f5a65b4e8e26fb799d62e03b5332b4389";
pub const SUGGESTION_VALIDATOR_V2_2_0_SHA256: &str =
    "93f772c7262d4a8ed4c5d38f0f212e5681cf74aa33fe9f52d1f788004058a79b";
pub const COMPATIBILITY_VALIDATOR_V2_1_0_SHA256: &str =
    "e651c77541542f315a69342838312e013381f6b5edd632033c2f158f5847287d";
pub const EVALUATION_VALIDATOR_V2_1_0_SHA256: &str =
    "4f6dbb641b2d63113e13a5a273988aa97d562c8d3794ec3a6fd8d94e35bfd7f7";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ValidatorMigrationStep {
    pub migration_id: &'static str,
    pub kind: &'static str,
    pub from_source_sha256: &'static str,
    pub to_source_sha256: &'static str,
    pub to_revision: i32,
}

pub const VALIDATOR_MIGRATIONS: &[ValidatorMigrationStep] = &[
    // Historical steps preserved for auditability
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_0_2,
        kind: "question",
        from_source_sha256: "891ab1a222dce8d53dc7fc4cf4335d0e91e45444d2643640f3659a306bc17a9d",
        to_source_sha256: QUESTION_VALIDATOR_V2_0_2_SHA256,
        to_revision: 3,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_0_2,
        kind: "question",
        from_source_sha256: "61ef0c9ef5deb4b3868cf22ed668079ce73f87bbef483dfbbc746d12a2140013",
        to_source_sha256: QUESTION_VALIDATOR_V2_0_2_SHA256,
        to_revision: 3,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "question",
        from_source_sha256: "891ab1a222dce8d53dc7fc4cf4335d0e91e45444d2643640f3659a306bc17a9d",
        to_source_sha256: QUESTION_VALIDATOR_V2_1_0_SHA256,
        to_revision: 4,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "question",
        from_source_sha256: "61ef0c9ef5deb4b3868cf22ed668079ce73f87bbef483dfbbc746d12a2140013",
        to_source_sha256: QUESTION_VALIDATOR_V2_1_0_SHA256,
        to_revision: 4,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "question",
        from_source_sha256: QUESTION_VALIDATOR_V2_0_2_SHA256,
        to_source_sha256: QUESTION_VALIDATOR_V2_1_0_SHA256,
        to_revision: 4,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "suggestion",
        from_source_sha256: "fa915882387ab8c1c69b74b501dc3fa4b3ead8afe392b934c554414d56e5c23e",
        to_source_sha256: SUGGESTION_VALIDATOR_V2_1_0_SHA256,
        to_revision: 4,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "suggestion",
        from_source_sha256: "5da194157dc8bd0154fc25eba3dcd3618c99b4d7f16e27ede39ba87e18a66091",
        to_source_sha256: SUGGESTION_VALIDATOR_V2_1_0_SHA256,
        to_revision: 4,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_2_0,
        kind: "question",
        from_source_sha256: "891ab1a222dce8d53dc7fc4cf4335d0e91e45444d2643640f3659a306bc17a9d",
        to_source_sha256: QUESTION_VALIDATOR_V2_2_0_SHA256,
        to_revision: 5,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_2_0,
        kind: "question",
        from_source_sha256: "61ef0c9ef5deb4b3868cf22ed668079ce73f87bbef483dfbbc746d12a2140013",
        to_source_sha256: QUESTION_VALIDATOR_V2_2_0_SHA256,
        to_revision: 5,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_2_0,
        kind: "question",
        from_source_sha256: QUESTION_VALIDATOR_V2_0_2_SHA256,
        to_source_sha256: QUESTION_VALIDATOR_V2_2_0_SHA256,
        to_revision: 5,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_2_0,
        kind: "question",
        from_source_sha256: QUESTION_VALIDATOR_V2_1_0_SHA256,
        to_source_sha256: QUESTION_VALIDATOR_V2_2_0_SHA256,
        to_revision: 5,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_3_0,
        kind: "question",
        from_source_sha256: "891ab1a222dce8d53dc7fc4cf4335d0e91e45444d2643640f3659a306bc17a9d",
        to_source_sha256: QUESTION_VALIDATOR_V2_3_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_3_0,
        kind: "question",
        from_source_sha256: "61ef0c9ef5deb4b3868cf22ed668079ce73f87bbef483dfbbc746d12a2140013",
        to_source_sha256: QUESTION_VALIDATOR_V2_3_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_3_0,
        kind: "question",
        from_source_sha256: QUESTION_VALIDATOR_V2_0_2_SHA256,
        to_source_sha256: QUESTION_VALIDATOR_V2_3_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_3_0,
        kind: "question",
        from_source_sha256: QUESTION_VALIDATOR_V2_1_0_SHA256,
        to_source_sha256: QUESTION_VALIDATOR_V2_3_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_3_0,
        kind: "question",
        from_source_sha256: QUESTION_VALIDATOR_V2_2_0_SHA256,
        to_source_sha256: QUESTION_VALIDATOR_V2_3_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_2_0,
        kind: "suggestion",
        from_source_sha256: "fa915882387ab8c1c69b74b501dc3fa4b3ead8afe392b934c554414d56e5c23e",
        to_source_sha256: SUGGESTION_VALIDATOR_V2_2_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_2_0,
        kind: "suggestion",
        from_source_sha256: "5da194157dc8bd0154fc25eba3dcd3618c99b4d7f16e27ede39ba87e18a66091",
        to_source_sha256: SUGGESTION_VALIDATOR_V2_2_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_2_0,
        kind: "suggestion",
        from_source_sha256: SUGGESTION_VALIDATOR_V2_1_0_SHA256,
        to_source_sha256: SUGGESTION_VALIDATOR_V2_2_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "compatibility",
        from_source_sha256: "14f6963f17029f351688e9656c4cd84b5320853b6cd3ebfe7476aa4cd4710ef4",
        to_source_sha256: COMPATIBILITY_VALIDATOR_V2_1_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "compatibility",
        from_source_sha256: "68de8f1929279932b9b9a51b2ff432d14c2435a486ce7bd7933caf98da0c2943",
        to_source_sha256: COMPATIBILITY_VALIDATOR_V2_1_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "evaluation",
        from_source_sha256: "328928b756aeef73c4a200dc75d9784ee3739239f93c4b094173b0b13eca5212",
        to_source_sha256: EVALUATION_VALIDATOR_V2_1_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
    ValidatorMigrationStep {
        migration_id: VALIDATOR_MIGRATION_V2_1_0,
        kind: "evaluation",
        from_source_sha256: "94095af3ffb78706949ae12aa8b1d4320a8c57e777a99b3d4b026455cf8f5ab6",
        to_source_sha256: EVALUATION_VALIDATOR_V2_1_0_SHA256,
        to_revision: CURRENT_VALIDATOR_REVISION,
    },
];

fn is_legacy_true_false_pair(arr: &[Bson]) -> bool {
    if arr.len() != 2 {
        return false;
    }
    let (Some(a), Some(b)) = (arr[0].as_str(), arr[1].as_str()) else {
        return false;
    };
    let (na, nb) = (a.trim().to_lowercase(), b.trim().to_lowercase());
    let tf_true = ["certo", "verdadeiro", "c", "v"];
    let tf_false = ["errado", "falso", "e", "f"];
    (tf_true.contains(&na.as_str()) && tf_false.contains(&nb.as_str()))
        || (tf_false.contains(&na.as_str()) && tf_true.contains(&nb.as_str()))
}

pub fn is_sub5_multiple_choice_doc(doc: &Document) -> bool {
    match doc
        .get_str("question_type")
        .ok()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some("MULTIPLE_CHOICE") => doc.get_array("answers").is_ok_and(|arr| arr.len() < 5),
        Some("TRUE_FALSE" | "OPEN_ENDED") => false,
        Some(_) => false,
        None => doc.get_array("answers").is_ok_and(|arr| {
            !arr.is_empty() && arr.len() < 5 && !is_legacy_true_false_pair(arr)
        }),
    }
}

pub async fn purge_sub5_multiple_choice_questions(db: &Database) -> Result<u64, AppError> {
    let coll = collection(db, "todasQuestoes");
    let mut cursor = coll
        .find(doc! {
            "question_type": { "$nin": ["TRUE_FALSE", "OPEN_ENDED"] },
            "answers.4": { "$exists": false }
        })
        .projection(doc! { "_id": 1, "question_type": 1, "answers": 1 })
        .await?;
    let mut raw_ids: Vec<Bson> = Vec::new();
    let mut oids: Vec<ObjectId> = Vec::new();
    let mut hex_ids: Vec<String> = Vec::new();
    while let Some(doc) = cursor.try_next().await? {
        if is_sub5_multiple_choice_doc(&doc) {
            if let Some(id_bson) = doc.get("_id").cloned() {
                if let Some(oid) = id_bson.as_object_id() {
                    oids.push(oid);
                    hex_ids.push(oid.to_hex());
                } else if let Some(s) = id_bson.as_str() {
                    hex_ids.push(s.to_owned());
                }
                raw_ids.push(id_bson);
            }
        }
    }
    if raw_ids.is_empty() {
        return Ok(0);
    }
    let deleted = coll
        .delete_many(doc! { "_id": { "$in": &raw_ids } })
        .await?
        .deleted_count;
    if !hex_ids.is_empty() {
        let _ = collection(db, "question_classification_suggestions")
            .delete_many(doc! { "question_id": { "$in": &hex_ids } })
            .await?;
        let _ = collection(db, "question_exam_compatibility")
            .delete_many(doc! { "question_id": { "$in": &hex_ids } })
            .await?;
    }
    if !oids.is_empty() || !hex_ids.is_empty() {
        let _ = collection(db, "reportedQuestoes")
            .delete_many(doc! {
                "$or": [
                    { "questao_id": { "$in": &oids } },
                    { "question_id": { "$in": &hex_ids } }
                ]
            })
            .await?;
    }
    tracing::info!(
        purged_count = deleted,
        "Questões MULTIPLE_CHOICE com menos de 5 alternativas removidas de todasQuestoes."
    );
    Ok(deleted)
}

pub fn find_validator_migration(
    kind: &str,
    from_sha256: &str,
    to_sha256: &str,
) -> Option<&'static ValidatorMigrationStep> {
    VALIDATOR_MIGRATIONS.iter().find(|step| {
        step.kind == kind
            && step.from_source_sha256 == from_sha256
            && step.to_source_sha256 == to_sha256
    })
}

fn upgrade_merged_validator(
    expected_previous: &Document,
    next_validator: &Document,
) -> Result<Document, AppError> {
    if expected_previous.contains_key("$jsonSchema") && expected_previous.len() == 1 {
        return Ok(next_validator.clone());
    }
    if let Ok(and_clauses) = expected_previous.get_array("$and") {
        if and_clauses.len() == 2
            && and_clauses[1]
                .as_document()
                .is_some_and(|d| d.contains_key("$jsonSchema") && d.len() == 1)
        {
            return Ok(doc! { "$and": [and_clauses[0].clone(), next_validator.clone()] });
        }
    }
    Err(err(
        "Estrutura de validador anterior inesperada; revisão necessária",
    ))
}

pub async fn prepare(db: &Database) -> Result<Value, AppError> {
    let specs = [
        ("todasQuestoes", "question"),
        ("question_classification_suggestions", "suggestion"),
        ("question_exam_compatibility", "compatibility"),
        ("answer_evaluations", "evaluation"),
    ];
    let mut validators_migrated = 0_usize;
    let mut purged_sub5_multiple_choice = 0_u64;
    let receipts = collection(db, "cogniva_schema_versions");
    for (name, kind) in specs {
        let schema = mongo_schema(kind);
        let validator = doc! { "$jsonSchema": schema };
        let source_hash = validator_source_sha256(&validator);
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

        if let Some(receipt_doc) = &receipt {
            let old_hash = receipt_doc.get_str("source_sha256").unwrap_or_default();
            let expected = receipt_doc
                .get_document("validator")
                .map_err(|_| err("Recibo de schema sem validador válido"))?;
            if old_hash != source_hash {
                let Some(step) = find_validator_migration(kind, old_hash, &source_hash) else {
                    return Err(err("Mudança de schema exige migração versionada explícita"));
                };
                let target_merged = upgrade_merged_validator(expected, &validator)?;
                if descriptions.is_empty() || (&previous != expected && previous != target_merged) {
                    return Err(err("Validador mudou fora da migração; revisão necessária"));
                }
                if kind == "question" {
                    purged_sub5_multiple_choice += purge_sub5_multiple_choice_questions(db).await?;
                }
                if db
                    .collection::<Document>(name)
                    .count_documents(doc! { "$nor": [target_merged.clone()] })
                    .await?
                    > 0
                {
                    return Err(err(
                        "Documentos existentes incompatíveis; migração não altera nem descarta dados",
                    ));
                }
                if previous != target_merged {
                    db.run_command(doc! {
                        "collMod": name,
                        "validator": target_merged.clone(),
                        "validationLevel": "strict",
                        "validationAction": "error"
                    })
                    .await?;
                }
                receipts
                    .update_one(
                        doc! { "_id": &receipt_key, "source_sha256": old_hash },
                        doc! {
                            "$set": {
                                "validator": target_merged,
                                "source_sha256": &source_hash,
                                "validator_revision": step.to_revision,
                                "migration_id": step.migration_id,
                                "previous_source_sha256": old_hash,
                                "migrated_at": bson::DateTime::now()
                            }
                        },
                    )
                    .await?;
                tracing::info!(
                    collection = name,
                    kind = kind,
                    migration_id = step.migration_id,
                    from_sha256 = old_hash,
                    to_sha256 = %source_hash,
                    "Validador MongoDB migrado com sucesso sem reescrita de documentos."
                );
                validators_migrated += 1;
                continue;
            }

            if expected != &previous {
                return Err(err("Validador mudou fora da migração; revisão necessária"));
            }
            if !descriptions.is_empty()
                && db
                    .collection::<Document>(name)
                    .count_documents(doc! { "$nor": [expected.clone()] })
                    .await?
                    > 0
            {
                return Err(err(
                    "Documentos existentes incompatíveis; migração não altera nem descarta dados",
                ));
            }
            continue;
        }

        let merged = if previous.is_empty() || previous == validator {
            validator.clone()
        } else {
            doc! { "$and": [previous, validator] }
        };
        if !descriptions.is_empty() && kind == "question" {
            purged_sub5_multiple_choice += purge_sub5_multiple_choice_questions(db).await?;
        }
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
        } else {
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
                        "validator_revision": CURRENT_VALIDATOR_REVISION,
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
    create_index_tolerant(
        db,
        "knowledge_catalog_versions",
        IndexModel::builder()
            .keys(doc! { "catalog_id": 1, "version": 1 })
            .options(
                IndexOptions::builder()
                    .name("qv2_catalog_version".to_string())
                    .unique(true)
                    .build(),
            )
            .build(),
        "qv2_catalog_version",
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
        "validator_revision": CURRENT_VALIDATOR_REVISION,
        "validators_migrated": validators_migrated,
        "legacy_sub5_multiple_choice_purged": purged_sub5_multiple_choice,
        "question_documents_rewritten": 0,
        "indexes": created_indexes.len(),
        "catalog_version": catalog_version
    }))
}

pub fn is_benign_index_conflict_message(msg: &str) -> bool {
    let lower = msg.to_ascii_lowercase();
    lower.contains("indexoptionsconflict")
        || lower.contains("indexkeyspecsconflict")
        || lower.contains("indexalreadyexists")
        || lower.contains("code 85")
        || lower.contains("code: 85")
        || lower.contains("code 86")
        || lower.contains("code: 86")
        || lower.contains("code 68")
        || lower.contains("code: 68")
        || lower.contains("already exists with a different name")
        || lower.contains("already exists with different options")
        || lower.contains("equivalent index already exists")
}

async fn create_index_tolerant(
    db: &Database,
    coll_name: &str,
    model: IndexModel,
    label: &str,
) -> Result<bool, AppError> {
    match collection(db, coll_name).create_index(model).await {
        Ok(_) => Ok(true),
        Err(err) if is_benign_index_conflict_message(&err.to_string()) => {
            tracing::warn!(
                "Índice '{}' em '{}' já existe com nome/opções anteriores ({}); mantendo índice existente.",
                label,
                coll_name,
                err
            );
            Ok(false)
        }
        Err(err) => Err(err.into()),
    }
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
            doc! { "content_revision": 1 },
            "qv2_content_revision",
        ),
        (
            "todasQuestoes",
            doc! { "semantic_revision": 1 },
            "qv2_semantic_revision",
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
            "question_review_events",
            doc! { "resource_id": 1, "resource_revision": 1 },
            "qv2_review_events_resource",
        ),
        (
            "question_review_events",
            doc! { "principal_id": 1, "reviewed_at": -1 },
            "qv2_review_events_principal",
        ),
        (
            "question_import_commits",
            doc! { "actor_user_id": 1, "created_at": -1 },
            "qv2_import_commits_actor",
        ),
        (
            "userActivityLog",
            doc! { "user_id": 1, "revisao_necessaria": 1 },
            "idx_activity_user_srs",
        ),
        (
            "userActivityLog",
            doc! { "user_id": 1, "datas.ultima_sessao": -1 },
            "idx_activity_user_last_session",
        ),
        (
            "question_commands",
            doc! { "kind": 1, "status": 1 },
            "idx_question_commands_kind_status",
        ),
        (
            "todasQuestoes",
            doc! { "schema_version": 1, "audit_outbox.0": 1 },
            "idx_questions_schema_outbox",
        ),
        (
            "attempt_receipts",
            doc! { "recorded_at": 1 },
            "idx_attempt_receipts_recorded_at",
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
            "tutor_budgets",
            doc! { "day": 1 },
            "idx_tutor_budgets_day",
        ),
        (
            "catalog_editor_drafts",
            doc! { "owner_user_id": 1, "saved_at": -1 },
            "idx_catalog_drafts_owner_saved",
        ),
        (
            "question_import_ai_requests",
            doc! { "actor_user_id": 1 },
            "idx_import_ai_actor",
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
        create_index_tolerant(
            db,
            name,
            IndexModel::builder()
                .keys(keys)
                .options(IndexOptions::builder().name(label.to_string()).build())
                .build(),
            label,
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
        create_index_tolerant(
            db,
            name,
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
            label,
        )
        .await?;
        created.push(label.to_string());
    }

    create_index_tolerant(
        db,
        "usuarios",
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
        "idx_usuarios_email_normalized",
    )
    .await?;
    created.push("idx_usuarios_email_normalized".to_string());

    for (name, field, ttl, label) in [
        ("sessions", "expires_at", Duration::ZERO, "idx_sessions_expires_ttl"),
        (
            "auth_rate_limits",
            "expires_at",
            Duration::ZERO,
            "idx_auth_rate_limits_ttl",
        ),
        (
            "tutor_budgets",
            "updated_at",
            Duration::from_secs(30 * 24 * 60 * 60),
            "idx_tutor_budgets_updated_ttl",
        ),
    ] {
        create_index_tolerant(
            db,
            name,
            IndexModel::builder()
                .keys(doc! { field: 1 })
                .options(
                    IndexOptions::builder()
                        .name(label.to_string())
                        .expire_after(ttl)
                        .build(),
                )
                .build(),
            label,
        )
        .await?;
        created.push(label.to_string());
    }

    create_index_tolerant(
        db,
        "mission_progress",
        IndexModel::builder()
            .keys(doc! { "user_id": 1, "mission_id": 1 })
            .options(
                IndexOptions::builder()
                    .name("idx_mission_progress_user_mission".to_string())
                    .unique(true)
                    .build(),
            )
            .build(),
        "idx_mission_progress_user_mission",
    )
    .await?;
    created.push("idx_mission_progress_user_mission".to_string());

    let _ = collection(db, "mission_definitions")
        .drop_index("idx_mission_definitions_id")
        .await;
    create_index_tolerant(
        db,
        "mission_definitions",
        IndexModel::builder()
            .keys(doc! { "mission.mission_id": 1 })
            .options(
                IndexOptions::builder()
                    .name("idx_mission_definitions_nested_id".to_string())
                    .unique(true)
                    .partial_filter_expression(doc! {
                        "mission.mission_id": { "$type": "string" }
                    })
                    .build(),
            )
            .build(),
        "idx_mission_definitions_nested_id",
    )
    .await?;
    created.push("idx_mission_definitions_nested_id".to_string());

    Ok(created)
}

pub async fn persist_review_event_idempotent(
    db: &Database,
    session: Option<&mut ClientSession>,
    ev: &Document,
) -> Result<(), AppError> {
    let ev_id = ev
        .get_str("review_event_id")
        .or_else(|_| ev.get_str("_id"))
        .map_err(|_| err("Evento de revisão sem review_event_id"))?;
    let mut stored = ev.clone();
    stored.insert("_id", ev_id);
    let coll = collection(db, "question_review_events");
    if let Some(s) = session {
        coll.update_one(doc! { "_id": ev_id }, doc! { "$setOnInsert": stored })
            .upsert(true)
            .session(s)
            .await?;
    } else {
        coll.update_one(doc! { "_id": ev_id }, doc! { "$setOnInsert": stored })
            .upsert(true)
            .await?;
    }
    Ok(())
}

pub async fn flush_audit_with_session(
    db: &Database,
    mut session: Option<&mut ClientSession>,
    id: ObjectId,
) -> Result<(), AppError> {
    let coll = collection(db, "todasQuestoes");
    let raw = if let Some(s) = session.as_deref_mut() {
        coll.find_one(doc! { "_id": id }).session(s).await?
    } else {
        coll.find_one(doc! { "_id": id }).await?
    };
    if let Some(raw) = raw {
        if let Ok(event) = raw.get_document("schema_outbox") {
            let event_id = event
                .get_str("_id")
                .map_err(|_| err("Outbox sem identidade"))?;
            if let Ok(rev_events) = event.get_array("review_events") {
                for item in rev_events {
                    if let Some(rev_doc) = item.as_document() {
                        persist_review_event_idempotent(db, session.as_deref_mut(), rev_doc).await?;
                    }
                }
            }
            let mut content = event.clone();
            content.remove("_id");
            content.remove("review_events");
            let audit_coll = collection(db, "question_revision_events");
            if let Some(s) = session {
                audit_coll
                    .update_one(doc! { "_id": event_id }, doc! { "$setOnInsert": content })
                    .upsert(true)
                    .session(&mut *s)
                    .await?;
                coll.update_one(
                    doc! { "_id": id, "schema_outbox._id": event_id },
                    doc! { "$unset": { "schema_outbox": "" } },
                )
                .session(&mut *s)
                .await?;
            } else {
                audit_coll
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
    }
    Ok(())
}

pub async fn flush_audit(db: &Database, id: ObjectId) -> Result<(), AppError> {
    flush_audit_with_session(db, None, id).await
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

#[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
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

fn strip_provenance_server_stamps(v: &Value) -> Value {
    match v {
        Value::Object(o) => {
            let mut m = serde_json::Map::new();
            let is_prov = o.contains_key("actor") && o.contains_key("method");
            for (k, val) in o {
                if is_prov && matches!(k.as_str(), "recorded_at" | "review_event_id" | "trust_level") {
                    continue;
                }
                if k == "actor" && is_prov {
                    if let Some(ao) = val.as_object() {
                        let mut am = ao.clone();
                        if am.get("type").and_then(Value::as_str) == Some("HUMAN") {
                            am.remove("id");
                        }
                        m.insert(k.clone(), Value::Object(am));
                        continue;
                    }
                }
                m.insert(k.clone(), strip_provenance_server_stamps(val));
            }
            Value::Object(m)
        }
        Value::Array(arr) => Value::Array(arr.iter().map(strip_provenance_server_stamps).collect()),
        _ => v.clone(),
    }
}

fn collect_existing_review_event_ids(old_ext: &Value) -> std::collections::HashSet<String> {
    let mut ids = std::collections::HashSet::new();
    if let Some(origins) = old_ext["origins"].as_array() {
        for o in origins {
            if let Some(id) = o["provenance"]["review_event_id"].as_str() {
                ids.insert(id.to_string());
            }
        }
    }
    if let Some(links) = old_ext["classification"]["links"].as_array() {
        for l in links {
            if let Some(id) = l["provenance"]["review_event_id"].as_str() {
                ids.insert(id.to_string());
            }
        }
    }
    if let Some(id) = old_ext["validity"]["decision"]["review_event_id"].as_str() {
        ids.insert(id.to_string());
    }
    ids
}

fn ensure_no_spoofed_provenance(
    old_ext: Option<&Value>,
    next_doc: &Value,
) -> Result<(), AppError> {
    if let Some(old) = old_ext {
        let allowed = collect_existing_review_event_ids(old);
        if let Some(origins) = next_doc["origins"].as_array() {
            for (idx, o) in origins.iter().enumerate() {
                if let Some(rev_id) = o["provenance"]["review_event_id"].as_str() {
                    if !allowed.contains(rev_id) {
                        return Err(AppError::bad_request_code(
                            "REVIEW_ACTOR_UNTRUSTED",
                            Some(format!("origins.{idx}.provenance.review_event_id")),
                            "Cliente não pode autoatribuir review_event_id não verificado",
                        ));
                    }
                } else if o["provenance"]["trust_level"] == "SERVER_VERIFIED" {
                    return Err(AppError::bad_request_code(
                        "REVIEW_ACTOR_UNTRUSTED",
                        Some(format!("origins.{idx}.provenance.trust_level")),
                        "Cliente não pode autoatribuir trust_level SERVER_VERIFIED",
                    ));
                }
            }
        }
        if let Some(links) = next_doc["classification"]["links"].as_array() {
            for (idx, l) in links.iter().enumerate() {
                if let Some(rev_id) = l["provenance"]["review_event_id"].as_str() {
                    if !allowed.contains(rev_id) {
                        return Err(AppError::bad_request_code(
                            "REVIEW_ACTOR_UNTRUSTED",
                            Some(format!("classification.links.{idx}.provenance.review_event_id")),
                            "Cliente não pode autoatribuir review_event_id não verificado",
                        ));
                    }
                } else if l["provenance"]["trust_level"] == "SERVER_VERIFIED" {
                    return Err(AppError::bad_request_code(
                        "REVIEW_ACTOR_UNTRUSTED",
                        Some(format!("classification.links.{idx}.provenance.trust_level")),
                        "Cliente não pode autoatribuir trust_level SERVER_VERIFIED",
                    ));
                }
            }
        }
        if !next_doc["validity"]["decision"].is_null() {
            if let Some(rev_id) = next_doc["validity"]["decision"]["review_event_id"].as_str() {
                if !allowed.contains(rev_id) {
                    return Err(AppError::bad_request_code(
                        "REVIEW_ACTOR_UNTRUSTED",
                        Some("validity.decision.review_event_id"),
                        "Cliente não pode autoatribuir review_event_id não verificado",
                    ));
                }
            } else if next_doc["validity"]["decision"]["trust_level"] == "SERVER_VERIFIED" {
                return Err(AppError::bad_request_code(
                    "REVIEW_ACTOR_UNTRUSTED",
                    Some("validity.decision.trust_level"),
                    "Cliente não pode autoatribuir trust_level SERVER_VERIFIED",
                ));
            }
        }
    } else {
        if let Some(origins) = next_doc["origins"].as_array() {
            for (idx, o) in origins.iter().enumerate() {
                let prefix = format!("origins.{idx}.provenance");
                contracts::reject_client_server_provenance(&o["provenance"], &prefix)?;
            }
        }
        if let Some(links) = next_doc["classification"]["links"].as_array() {
            for (idx, l) in links.iter().enumerate() {
                let prefix = format!("classification.links.{idx}.provenance");
                contracts::reject_client_server_provenance(&l["provenance"], &prefix)?;
            }
        }
        if !next_doc["validity"]["decision"].is_null() {
            contracts::reject_client_server_provenance(
                &next_doc["validity"]["decision"],
                "validity.decision",
            )?;
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
pub fn build_review_event_doc(
    seed: &str,
    reviewer: &ReviewerContext,
    action: &str,
    resource_id: &str,
    resource_revision: i64,
    content_revision: &str,
    semantic_revision: &str,
    reviewed_at: &str,
) -> Result<(String, Document), AppError> {
    let review_event_id = deterministic_review_event_uuid(seed);
    let ev_json = json!({
        "_id": review_event_id,
        "schema_version": 1,
        "review_event_id": review_event_id,
        "principal_id": reviewer.principal_id,
        "actor_user_id": reviewer.user_id,
        "action": action,
        "resource_id": resource_id,
        "resource_revision": resource_revision,
        "content_revision": content_revision,
        "semantic_revision": semantic_revision,
        "reviewed_at": reviewed_at,
        "decision": "APPROVED"
    });
    contracts::shape("review_event", &ev_json)?;
    let doc = bson_doc(ev_json)?;
    Ok((review_event_id, doc))
}

#[allow(clippy::too_many_arguments)]
fn authorize_and_stamp_human_decisions(
    old_ext: Option<&Value>,
    doc_val: &mut Value,
    reviewer: &ReviewerContext,
    operation_id: &str,
    qid_hex: &str,
    next_rev: i64,
    content_rev: &str,
    semantic_rev: &str,
) -> Result<Vec<Document>, AppError> {
    if reviewer.user_id <= 0 {
        return Err(AppError::forbidden_code(
            "REVIEW_ACTOR_UNTRUSTED",
            None::<String>,
            "Revisão exige sessão autenticada válida no servidor",
        ));
    }

    let now_iso = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let mut review_events = Vec::new();

    let origins_changed = old_ext.is_none_or(|old| {
        strip_provenance_server_stamps(&old["origins"])
            != strip_provenance_server_stamps(&doc_val["origins"])
    });
    let class_changed = old_ext.is_none_or(|old| {
        strip_provenance_server_stamps(&old["classification"])
            != strip_provenance_server_stamps(&doc_val["classification"])
    });
    let validity_changed = old_ext.is_none_or(|old| {
        strip_provenance_server_stamps(&old["validity"])
            != strip_provenance_server_stamps(&doc_val["validity"])
    });

    if origins_changed {
        if let Some(origins) = doc_val["origins"].as_array_mut() {
            for (idx, origin) in origins.iter_mut().enumerate() {
                let requires_normative =
                    origin["kind"] == "OFFICIAL_EXAM" && origin["verification"] == "CONFIRMED";
                let has_human = contracts::human_review(&origin["provenance"]);
                if requires_normative || has_human {
                    if requires_normative && !reviewer.can_confirm_normative {
                        return Err(AppError::forbidden_code(
                            "REVIEW_PERMISSION_DENIED",
                            Some(format!("origins.{idx}.verification")),
                            "Permissão insuficiente para confirmar origem documental oficial",
                        ));
                    }
                    origin["provenance"]["actor"]["id"] = json!(reviewer.principal_id);
                    origin["provenance"]["recorded_at"] = json!(now_iso);
                    if reviewer.can_confirm_normative {
                        let (rev_id, ev_doc) = build_review_event_doc(
                            &format!("{}:{operation_id}:origin:{idx}", reviewer.user_id),
                            reviewer,
                            "ORIGIN_CONFIRM",
                            qid_hex,
                            next_rev,
                            content_rev,
                            semantic_rev,
                            &now_iso,
                        )?;
                        origin["provenance"]["review_event_id"] = json!(rev_id);
                        origin["provenance"]["trust_level"] = json!("SERVER_VERIFIED");
                        review_events.push(ev_doc);
                    }
                }
            }
        }
    }

    if class_changed {
        let is_confirmed = doc_val["classification"]["state"] == "CONFIRMED";
        let has_links = doc_val["classification"]["links"]
            .as_array()
            .is_some_and(|a| !a.is_empty());
        if is_confirmed || has_links {
            if !reviewer.can_confirm_normative {
                return Err(AppError::forbidden_code(
                    "REVIEW_PERMISSION_DENIED",
                    Some("classification.state"),
                    "Permissão insuficiente para confirmar vínculos de classificação",
                ));
            }
            let (rev_id, ev_doc) = build_review_event_doc(
                &format!("{}:{operation_id}:classification", reviewer.user_id),
                reviewer,
                "CLASSIFICATION_CONFIRM",
                qid_hex,
                next_rev,
                content_rev,
                semantic_rev,
                &now_iso,
            )?;
            if let Some(links) = doc_val["classification"]["links"].as_array_mut() {
                for link in links {
                    if contracts::human_review(&link["provenance"]) {
                        link["provenance"]["actor"]["id"] = json!(reviewer.principal_id);
                        link["provenance"]["recorded_at"] = json!(now_iso);
                        link["provenance"]["review_event_id"] = json!(rev_id);
                        link["provenance"]["trust_level"] = json!("SERVER_VERIFIED");
                    }
                }
            }
            review_events.push(ev_doc);
        }
    }

    if validity_changed {
        let vstatus = doc_val["validity"]["status"].as_str().unwrap_or("UNKNOWN");
        if matches!(vstatus, "CURRENT" | "OBSOLETE") {
            if !reviewer.can_confirm_normative {
                return Err(AppError::forbidden_code(
                    "REVIEW_PERMISSION_DENIED",
                    Some("validity.status"),
                    "Permissão insuficiente para confirmar validade normativa da questão",
                ));
            }
            let action = if vstatus == "CURRENT" {
                "VALIDITY_CONFIRM_CURRENT"
            } else {
                "VALIDITY_CONFIRM_OBSOLETE"
            };
            let (rev_id, ev_doc) = build_review_event_doc(
                &format!("{}:{operation_id}:validity:{vstatus}", reviewer.user_id),
                reviewer,
                action,
                qid_hex,
                next_rev,
                content_rev,
                semantic_rev,
                &now_iso,
            )?;
            doc_val["validity"]["decision"]["actor"]["id"] = json!(reviewer.principal_id);
            doc_val["validity"]["decision"]["recorded_at"] = json!(now_iso);
            doc_val["validity"]["decision"]["review_event_id"] = json!(rev_id);
            doc_val["validity"]["decision"]["trust_level"] = json!("SERVER_VERIFIED");
            doc_val["validity"]["evaluated_semantic_revision"] = json!(semantic_rev);
            doc_val["validity"]["evaluation_mode"] = json!("SEMANTIC");
            review_events.push(ev_doc);
        }
    }

    Ok(review_events)
}

pub async fn save(db: &Database, user: i64, r: SaveRequest) -> Result<Value, AppError> {
    save_with_reviewer(db, &ReviewerContext::admin(user), r).await
}

pub async fn save_with_reviewer(
    db: &Database,
    reviewer: &ReviewerContext,
    r: SaveRequest,
) -> Result<Value, AppError> {
    save_internal(db, None, reviewer, r, false).await
}

/// Insertion never upgrades or overwrites an existing question.
pub async fn create(db: &Database, user: i64, r: SaveRequest) -> Result<Value, AppError> {
    create_with_reviewer(db, &ReviewerContext::admin(user), r).await
}

pub async fn create_with_reviewer(
    db: &Database,
    reviewer: &ReviewerContext,
    r: SaveRequest,
) -> Result<Value, AppError> {
    save_internal(db, None, reviewer, r, true).await
}

pub async fn create_in_session(
    db: &Database,
    session: Option<&mut ClientSession>,
    reviewer: &ReviewerContext,
    r: SaveRequest,
) -> Result<Value, AppError> {
    save_internal(db, session, reviewer, r, true).await
}

async fn save_internal(
    db: &Database,
    mut external_session: Option<&mut ClientSession>,
    reviewer: &ReviewerContext,
    mut r: SaveRequest,
    create_only: bool,
) -> Result<Value, AppError> {
    let user = reviewer.user_id;
    if uuid::Uuid::parse_str(&r.operation_id).is_err()
        || r.expected_revision < 0
        || r.expected_revision >= i64::MAX - 1
    {
        return Err(err("Operação/revisão inválida"));
    }
    if r.document.get("schema_outbox").is_some() {
        return Err(err("Outbox é preenchida apenas pelo servidor"));
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

    if r.document["revision"].as_i64() != Some(r.expected_revision + 1) {
        return Err(AppError::bad_request_code(
            "SCHEMA_VALIDATION_FAILED",
            Some("revision"),
            "A revisão deve avançar exatamente uma unidade",
        ));
    }
    let qid_hex = contracts::validate_contract_oid(&r.document["_id"], "_id")?;
    let id = ObjectId::parse_str(&qid_hex)?;

    // Canonical command digest computed over client-submitted payload BEFORE server timestamps
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

    if !r.dry_run && external_session.is_none() {
        flush_audit(db, id).await?;
    }

    let existing_receipt = if let Some(s) = external_session.as_deref_mut() {
        collection(db, "question_revision_events")
            .find_one(doc! { "_id": &event_id })
            .session(s)
            .await?
    } else {
        collection(db, "question_revision_events")
            .find_one(doc! { "_id": &event_id })
            .await?
    };
    if let Some(receipt) = existing_receipt {
        if receipt.get_str("payload_sha256").ok() != Some(hash.as_str()) {
            return Err(AppError::conflict_code(
                "IDEMPOTENCY_KEY_CONFLICT",
                Some("operation_id"),
                "Operação repetida com outro conteúdo",
            ));
        }
        return Ok(json!({
            "status": "already_applied",
            "revision": receipt.get_i64("revision").unwrap_or(r.expected_revision + 1),
            "id": id.to_hex()
        }));
    }

    let raw = if let Some(s) = external_session.as_deref_mut() {
        coll.find_one(doc! { "_id": id }).session(s).await?
    } else {
        coll.find_one(doc! { "_id": id }).await?
    };

    if create_only && (raw.is_some() || r.expected_revision != 0) {
        return Err(AppError::conflict_code(
            "QUESTION_ALREADY_EXISTS",
            Some("_id"),
            "Inserção exige uma questão nova; o registro existente foi preservado",
        ));
    }
    if raw.as_ref().map(|d| number(d, "revision")).unwrap_or(0) != r.expected_revision
        || raw.is_none() && r.expected_revision != 0
    {
        return Err(err("Questão mudou; refaça a revisão"));
    }

    let old_ext = raw
        .as_ref()
        .map(|d| Bson::Document(d.clone()).into_relaxed_extjson());
    ensure_no_spoofed_provenance(old_ext.as_ref(), &r.document)?;

    // V2.0 compatibility on update: if a V2.0 caller updated content_revision (matching hash(doc))
    // while carrying over untouched V2.1 fields from GET, refresh those V2.1 fields before validate.
    // Any stale/mismatched content_revision or forged semantic_revision/grading_revision still fails T07!
    if let Some(prev) = old_ext.as_ref() {
        if let Ok(new_crev) = contracts::hash(&r.document) {
            if r.document["content_revision"].as_str() == Some(new_crev.as_str()) {
                let carried_old_sem = r.document.get("semantic_revision").is_some_and(|v| {
                    !v.is_null() && v == &prev["semantic_revision"]
                });
                let carried_old_grad = r.document.get("grading_revision").is_some_and(|v| {
                    !v.is_null() && v == &prev["grading_revision"]
                });
                if carried_old_sem {
                    if let Ok(new_srev) = contracts::semantic_hash(&r.document) {
                        if r.document["validity"]["evaluated_content_revision"].as_str()
                            == Some(new_crev.as_str())
                            && r.document["validity"].get("evaluated_semantic_revision")
                                == prev["validity"].get("evaluated_semantic_revision")
                        {
                            r.document["validity"]["evaluated_semantic_revision"] =
                                json!(&new_srev);
                        }
                        r.document["semantic_revision"] = json!(new_srev);
                        r.document["semantic_hash_version"] =
                            json!(contracts::SEMANTIC_HASH_VERSION);
                    }
                }
                if carried_old_grad {
                    if let Ok(new_grev) = contracts::grading_hash(&r.document) {
                        r.document["grading_revision"] = json!(new_grev);
                        r.document["grading_hash_version"] =
                            json!(contracts::GRADING_HASH_VERSION);
                    }
                }
            }
        }
    }

    // Validate full contract (enforcing T07 hash integrity, T19 ObjectId, T20 strict ints, schema)
    contracts::validate(&r.document)?;

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

    let unchanged_classification = old_ext.as_ref().is_some_and(|prev| {
        strip_provenance_server_stamps(&prev["classification"])
            == strip_provenance_server_stamps(&r.document["classification"])
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

    // Populate V2.1 semantic and grading signatures deterministically
    contracts::populate_v2_1_hashes(&mut r.document)?;
    let computed_content = r.document["content_revision"]
        .as_str()
        .unwrap()
        .to_string();
    let computed_semantic = r.document["semantic_revision"]
        .as_str()
        .unwrap()
        .to_string();

    if matches!(
        r.document["validity"]["status"].as_str(),
        Some("CURRENT" | "OBSOLETE")
    ) && r.document["validity"]
        .get("evaluated_semantic_revision")
        .is_none_or(Value::is_null)
    {
        r.document["validity"]["evaluated_semantic_revision"] = json!(&computed_semantic);
        r.document["validity"]["evaluation_mode"] = json!("SEMANTIC");
    }

    // Enforce server-authenticated reviewer permissions and generate auditable ReviewEvents
    let review_events = authorize_and_stamp_human_decisions(
        old_ext.as_ref(),
        &mut r.document,
        reviewer,
        &r.operation_id,
        &qid_hex,
        r.expected_revision + 1,
        &computed_content,
        &computed_semantic,
    )?;

    // Re-validate final stamped document against full V2.1 contract before touching MongoDB
    contracts::validate(&r.document)?;

    if r.dry_run {
        return Ok(json!({
            "status": "validated_dry_run",
            "revision": r.expected_revision + 1,
            "semantic_revision": r.document["semantic_revision"],
            "grading_revision": r.document["grading_revision"]
        }));
    }

    let schema_ready = if let Some(s) = external_session.as_deref_mut() {
        collection(db, "cogniva_schema_versions")
            .find_one(doc! { "_id": "question-v2:ready", "ready": true })
            .session(s)
            .await?
    } else {
        collection(db, "cogniva_schema_versions")
            .find_one(doc! { "_id": "question-v2:ready", "ready": true })
            .await?
    };
    if schema_ready.is_none() {
        return Err(AppError::TemporarilyUnavailable(
            "Prepare os validadores e índices antes da escrita v2".into(),
        ));
    }

    // If caller didn't open a session and MongoDB topology supports transactions, start one!
    let mut owned_session = if external_session.is_none() && topology_supports_transactions(db).await {
        if let Ok(mut s) = db.client().start_session().await {
            if s.start_transaction().await.is_ok() {
                Some(s)
            } else {
                None
            }
        } else {
            None
        }
    } else {
        None
    };

    let active_session: Option<&mut ClientSession> = match external_session {
        Some(s) => Some(s),
        None => owned_session.as_mut(),
    };

    let result = execute_write_steps(
        db,
        active_session,
        &coll,
        id,
        user,
        &event_id,
        &hash,
        raw.as_ref(),
        old_ext.as_ref(),
        &r,
        &computed_semantic,
        review_events,
    )
    .await;

    if let Some(mut s) = owned_session {
        match &result {
            Ok(_) => {
                s.commit_transaction().await?;
            }
            Err(_) => {
                let _ = s.abort_transaction().await;
            }
        }
    }

    result
}

#[allow(clippy::too_many_arguments)]
async fn execute_write_steps(
    db: &Database,
    mut session: Option<&mut ClientSession>,
    coll: &mongodb::Collection<Document>,
    id: ObjectId,
    user: i64,
    event_id: &str,
    hash: &str,
    raw: Option<&Document>,
    old_ext: Option<&Value>,
    r: &SaveRequest,
    computed_semantic: &str,
    review_events: Vec<Document>,
) -> Result<Value, AppError> {
    let commands = collection(db, "question_commands");
    let existing_cmd = if let Some(s) = session.as_deref_mut() {
        commands.find_one(doc! { "_id": event_id }).session(s).await?
    } else {
        commands.find_one(doc! { "_id": event_id }).await?
    };
    if let Some(command) = existing_cmd {
        if command.get_str("payload_sha256").ok() != Some(hash) {
            return Err(AppError::conflict_code(
                "IDEMPOTENCY_KEY_CONFLICT",
                Some("operation_id"),
                "Operação já reservada com outro conteúdo",
            ));
        }
    } else {
        let cmd_doc = doc! {
            "_id": event_id,
            "payload_sha256": hash,
            "question_id": id.to_hex(),
            "actor_user_id": user
        };
        let insert_res = if let Some(s) = session.as_deref_mut() {
            commands.insert_one(cmd_doc).session(s).await
        } else {
            commands.insert_one(cmd_doc).await
        };
        if let Err(e) = insert_res {
            if is_duplicate_key_error(&e) {
                return Err(AppError::conflict_code(
                    "IDEMPOTENCY_KEY_CONFLICT",
                    Some("operation_id"),
                    "Conflito concorrente na reserva de operação",
                ));
            }
            return Err(e.into());
        }
    }

    let primary = r.document["classification"]["links"]
        .as_array()
        .unwrap()
        .iter()
        .find(|l| l["role"] == "PRIMARY");
    if primary.is_none()
        && raw.is_some_and(|d| d.get_str("canonical_topic_id").is_ok())
    {
        return Err(err("Preserve o vínculo canônico confirmado na migração"));
    }
    let mirrors = primary.map(|p| (p["subject_id"].clone(), p["topic_id"].clone()));

    let previous = raw.cloned().unwrap_or_default();
    let old_semantic_rev = raw
        .and_then(|d| d.get_str("semantic_revision").ok().map(str::to_string))
        .or_else(|| old_ext.and_then(|prev| contracts::semantic_hash(prev).ok()));
    let semantic_changed =
        raw.is_some() && old_semantic_rev.as_deref() != Some(computed_semantic);

    let incoming_explanation = r
        .document
        .get("explanation")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let old_explanation = raw
        .and_then(|d| d.get_str("explanation").ok())
        .map(str::trim);
    let explicit_updated_explanation =
        incoming_explanation.filter(|exp| !semantic_changed || Some(*exp) != old_explanation);

    let mut update = if raw.is_none() {
        contracts::contract_to_bson_question(&r.document)?
    } else {
        Document::new()
    };
    for key in [
        "schema_version",
        "revision",
        "content_revision",
        "content_hash_version",
        "semantic_revision",
        "semantic_hash_version",
        "grading_revision",
        "grading_hash_version",
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
        } else if semantic_changed || r.document.get("explanation") == Some(&Value::Null) {
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
    } else if raw.is_none_or(|d| !d.contains_key("source")) {
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

    let review_event_ids: Vec<String> = review_events
        .iter()
        .filter_map(|d| d.get_str("review_event_id").ok().map(str::to_string))
        .collect();
    let review_events_bson: Vec<Bson> = review_events
        .into_iter()
        .map(Bson::Document)
        .collect();

    let event = doc! {
        "_id": event_id,
        "question_id": id.to_hex(),
        "revision": r.expected_revision + 1,
        "payload_sha256": hash,
        "actor_user_id": user,
        "review_event_ids": review_event_ids.clone(),
        "review_events": review_events_bson,
        "recorded_at": bson::DateTime::now(),
        "previous": previous
    };
    update.insert("schema_outbox", event);

    if let Some(old) = raw {
        let mut filter = doc! { "_id": id, "schema_outbox": { "$exists": false } };
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
        if semantic_changed {
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
        let result = if let Some(s) = session.as_deref_mut() {
            coll.update_one(filter, command).session(s).await?
        } else {
            coll.update_one(filter, command).await?
        };
        if result.matched_count != 1 {
            return Err(err(
                "Alteração concorrente; operação não sobrescreveu a questão",
            ));
        }
    } else {
        update.insert("_id", id);
        let ins_res = if let Some(s) = session.as_deref_mut() {
            coll.insert_one(update).session(s).await
        } else {
            coll.insert_one(update).await
        };
        if let Err(e) = ins_res {
            if is_duplicate_key_error(&e) {
                return Err(AppError::conflict_code(
                    "QUESTION_ALREADY_EXISTS",
                    Some("_id"),
                    "Questão já existente; inserção concorrente bloqueada",
                ));
            }
            return Err(e.into());
        }
    }

    flush_audit_with_session(db, session, id).await?;
    Ok(json!({
        "status": "applied",
        "revision": r.expected_revision + 1,
        "id": id.to_hex(),
        "semantic_revision": computed_semantic,
        "grading_revision": r.document["grading_revision"],
        "review_event_ids": review_event_ids
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
            let n = next[field]["revision"]
                .as_i64()
                .ok_or_else(|| err("Revisão de classificação/validade não inteira"))?;
            if prior.is_null() {
                if n > 1 {
                    return Err(err("Bloco novo deve iniciar com revisão zero ou um"));
                }
            } else {
                let p = prior["revision"].as_i64().unwrap_or(0);
                let changed = strip_provenance_server_stamps(prior)
                    != strip_provenance_server_stamps(&next[field]);
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

#[cfg(test)]
pub fn v2_2_0_schema_bundle() -> Value {
    let mut bundle: Value = serde_json::from_str(include_str!(
        "../../../shared/schemas/question-contracts.schema.json"
    ))
    .expect("contracts");

    bundle["$defs"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "conceptual_error_item" && k != "editorial_card");
    bundle["$defs"]["question"]["properties"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "editorial_card");

    bundle
}

#[cfg(test)]
pub fn v2_1_0_schema_bundle() -> Value {
    let mut bundle = v2_2_0_schema_bundle();

    bundle["$defs"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "evidence_option" && k != "evidence_challenge");
    for link_def in ["classified_link", "proposed_link"] {
        bundle["$defs"][link_def]["properties"]
            .as_object_mut()
            .unwrap()
            .retain(|k, _| k != "concept_id");
    }
    bundle["$defs"]["question"]["properties"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "evidence_challenge");

    bundle
}

#[cfg(test)]
pub fn v2_0_2_schema_bundle() -> Value {
    let mut bundle = v2_1_0_schema_bundle();

    if let Some(one_of) = bundle["oneOf"].as_array_mut() {
        one_of.retain(|entry| entry["$ref"] != "#/$defs/review_event");
    }
    bundle["$defs"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "review_event");

    let prov_props = bundle["$defs"]["provenance"]["properties"]
        .as_object_mut()
        .unwrap();
    prov_props.retain(|k, _| k != "review_event_id" && k != "trust_level");

    let val_props = bundle["$defs"]["validity"]["properties"]
        .as_object_mut()
        .unwrap();
    val_props.retain(|k, _| k != "evaluated_semantic_revision" && k != "evaluation_mode");

    let val_all_of = bundle["$defs"]["validity"]["allOf"].as_array_mut().unwrap();
    if let Some(unknown_props) = val_all_of[0]["then"]["properties"].as_object_mut() {
        unknown_props.retain(|k, _| k != "evaluated_semantic_revision" && k != "evaluation_mode");
    }
    val_all_of.retain(|rule| rule["if"]["properties"]["status"]["const"] != "UNDER_REVIEW");

    let q_props = bundle["$defs"]["question"]["properties"]
        .as_object_mut()
        .unwrap();
    q_props["schema_version"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "type");
    q_props.retain(|k, _| {
        !matches!(
            k.as_str(),
            "semantic_revision"
                | "semantic_hash_version"
                | "grading_revision"
                | "grading_hash_version"
        )
    });

    let s_props = bundle["$defs"]["suggestion"]["properties"]
        .as_object_mut()
        .unwrap();
    s_props["schema_version"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "type");
    s_props.retain(|k, _| k != "question_semantic_revision");
    s_props["resolution"]["anyOf"][0]["properties"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "review_event_id");

    let c_props = bundle["$defs"]["compatibility"]["properties"]
        .as_object_mut()
        .unwrap();
    c_props["schema_version"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "type");
    c_props.retain(|k, _| k != "question_semantic_revision");

    let e_props = bundle["$defs"]["evaluation"]["properties"]
        .as_object_mut()
        .unwrap();
    e_props["schema_version"]
        .as_object_mut()
        .unwrap()
        .retain(|k, _| k != "type");
    e_props.retain(|k, _| k != "question_semantic_revision" && k != "grading_revision");

    bundle
}

#[cfg(test)]
pub fn v2_0_1_schema_bundle() -> Value {
    let mut bundle = v2_0_2_schema_bundle();
    bundle["$defs"]["question"]["allOf"][0]["then"]["properties"]["answers"]["minItems"] = json!(4);
    bundle
}

#[cfg(test)]
pub fn v2_0_0_schema_bundle() -> Value {
    let mut bundle = v2_0_1_schema_bundle();
    let uri = bundle["$defs"]["evidence"]["properties"]["uri"]
        .as_object_mut()
        .expect("evidence.uri");
    uri.remove("maxLength");
    uri.remove("pattern");

    let exam_year_int = bundle["$defs"]["question"]["properties"]["exam_year"]["anyOf"][0]
        .as_object_mut()
        .expect("question.exam_year.anyOf[0]");
    exam_year_int.remove("minimum");
    exam_year_int.remove("maximum");

    let concursos_str = bundle["$defs"]["question"]["properties"]["concursos"]["anyOf"][0]
        .as_object_mut()
        .expect("question.concursos.anyOf[0]");
    concursos_str.remove("maxLength");

    let concursos_arr = bundle["$defs"]["question"]["properties"]["concursos"]["anyOf"][1]
        .as_object_mut()
        .expect("question.concursos.anyOf[1]");
    concursos_arr.remove("maxItems");
    concursos_arr["items"]
        .as_object_mut()
        .expect("question.concursos.anyOf[1].items")
        .remove("maxLength");

    bundle
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn benign_index_conflict_classifier_distinguishes_legacy_index_drift_from_fatal_errors() {
        assert!(is_benign_index_conflict_message(
            "Command failed (IndexOptionsConflict, code 85): Index with name: expires_at_1 already exists with different options"
        ));
        assert!(is_benign_index_conflict_message(
            "Command failed (IndexKeySpecsConflict, code: 86): Index already exists with a different name: idx_legacy_user"
        ));
        assert!(is_benign_index_conflict_message(
            "CommandError { code: 68, code_name: \"IndexAlreadyExists\" }"
        ));
        assert!(!is_benign_index_conflict_message(
            "Command failed (Unauthorized, code 13): not authorized on cogniva"
        ));
        assert!(!is_benign_index_conflict_message(
            "Server selection timeout: No available servers"
        ));
    }

    #[test]
    fn validator_migration_table_matches_v2_0_0_v2_0_1_v2_0_2_v2_2_0_and_current_v2_3_0_hashes() {
        let old_bundle = v2_0_0_schema_bundle();
        let v2_0_1_bundle = v2_0_1_schema_bundle();
        let v2_0_2_bundle = v2_0_2_schema_bundle();
        let v2_1_0_bundle = v2_1_0_schema_bundle();
        let v2_2_0_bundle = v2_2_0_schema_bundle();
        for kind in ["question", "suggestion", "compatibility", "evaluation"] {
            let old_doc = mongo_schema_from_bundle(&old_bundle, kind);
            let old_hash = validator_source_sha256(&doc! { "$jsonSchema": old_doc });
            let v2_0_1_doc = mongo_schema_from_bundle(&v2_0_1_bundle, kind);
            let v2_0_1_hash = validator_source_sha256(&doc! { "$jsonSchema": v2_0_1_doc });
            let v2_0_2_doc = mongo_schema_from_bundle(&v2_0_2_bundle, kind);
            let v2_0_2_hash = validator_source_sha256(&doc! { "$jsonSchema": v2_0_2_doc });
            let v2_1_0_doc = mongo_schema_from_bundle(&v2_1_0_bundle, kind);
            let v2_1_0_hash = validator_source_sha256(&doc! { "$jsonSchema": v2_1_0_doc });
            let v2_2_0_doc = mongo_schema_from_bundle(&v2_2_0_bundle, kind);
            let v2_2_0_hash = validator_source_sha256(&doc! { "$jsonSchema": v2_2_0_doc });
            let current_doc = mongo_schema(kind);
            assert!(
                current_doc.contains_key("properties"),
                "missing properties in {kind}"
            );
            let current_hash = validator_source_sha256(&doc! { "$jsonSchema": current_doc });
            let expected_mig = match kind {
                "question" => VALIDATOR_MIGRATION_V2_3_0,
                "suggestion" => VALIDATOR_MIGRATION_V2_2_0,
                _ => VALIDATOR_MIGRATION_V2_1_0,
            };
            if kind == "question" {
                assert_eq!(v2_0_2_hash, QUESTION_VALIDATOR_V2_0_2_SHA256);
                assert_eq!(v2_1_0_hash, QUESTION_VALIDATOR_V2_1_0_SHA256);
                assert_eq!(v2_2_0_hash, QUESTION_VALIDATOR_V2_2_0_SHA256);
                assert_eq!(current_hash, QUESTION_VALIDATOR_V2_3_0_SHA256);
                let step_202 = find_validator_migration(kind, &v2_0_2_hash, &current_hash)
                    .unwrap_or_else(|| {
                        panic!("missing v2.0.2 -> current migration for {kind}: {v2_0_2_hash} -> {current_hash}")
                    });
                assert_eq!(step_202.migration_id, expected_mig);
                assert_eq!(step_202.to_revision, CURRENT_VALIDATOR_REVISION);

                let step_220 = find_validator_migration(kind, &v2_2_0_hash, &current_hash)
                    .unwrap_or_else(|| {
                        panic!("missing v2.2.0 -> v2.3.0 migration for {kind}: {v2_2_0_hash} -> {current_hash}")
                    });
                assert_eq!(step_220.migration_id, VALIDATOR_MIGRATION_V2_3_0);
                assert_eq!(step_220.to_revision, CURRENT_VALIDATOR_REVISION);
            }
            if matches!(kind, "question" | "suggestion") {
                let step_210 = find_validator_migration(kind, &v2_1_0_hash, &current_hash)
                    .unwrap_or_else(|| {
                        panic!("missing v2.1.0 -> current migration for {kind}: {v2_1_0_hash} -> {current_hash}")
                    });
                assert_eq!(step_210.migration_id, expected_mig);
                assert_eq!(step_210.to_revision, CURRENT_VALIDATOR_REVISION);
            }
            let step_old = find_validator_migration(kind, &old_hash, &current_hash)
                .unwrap_or_else(|| {
                    panic!("missing v2.0.0 -> current migration for {kind}: {old_hash} -> {current_hash}")
                });
            assert_eq!(step_old.migration_id, expected_mig);
            assert_eq!(step_old.to_revision, CURRENT_VALIDATOR_REVISION);

            let step_201 = find_validator_migration(kind, &v2_0_1_hash, &current_hash)
                .unwrap_or_else(|| {
                    panic!("missing v2.0.1 -> current migration for {kind}: {v2_0_1_hash} -> {current_hash}")
                });
            assert_eq!(step_201.migration_id, expected_mig);
            assert_eq!(step_201.to_revision, CURRENT_VALIDATOR_REVISION);

            assert!(
                find_validator_migration(
                    kind,
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    &current_hash
                )
                .is_none(),
                "unversioned predecessor hash must never match {kind}"
            );
        }
    }

    #[test]
    fn reviewer_authorization_blocks_untrusted_or_unauthorized_normative_decisions_t01_t02_t03() {
        let mut mc: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        contracts::populate_v2_1_hashes(&mut mc).unwrap();
        let c_rev = mc["content_revision"].as_str().unwrap().to_string();
        let s_rev = mc["semantic_revision"].as_str().unwrap().to_string();

        // T01: Client spoofing review_event_id or SERVER_VERIFIED rejected immediately
        let mut spoofed = mc.clone();
        spoofed["validity"]["decision"]["review_event_id"] =
            json!("11111111-1111-4111-8111-111111111111");
        let spoof_err = ensure_no_spoofed_provenance(None, &spoofed).unwrap_err();
        assert_eq!(spoof_err.error_code(), "REVIEW_ACTOR_UNTRUSTED");

        // T01: Unauthenticated context (user_id <= 0) rejected
        let unauth_err = authorize_and_stamp_human_decisions(
            None,
            &mut mc.clone(),
            &ReviewerContext::pending_only(0),
            "00000000-0000-4000-8000-000000000001",
            "000000000000000000000001",
            1,
            &c_rev,
            &s_rev,
        )
        .unwrap_err();
        assert_eq!(unauth_err.error_code(), "REVIEW_ACTOR_UNTRUSTED");

        // T02: Authenticated user WITHOUT normative permission attempting CURRENT validity rejected
        let forbidden_err = authorize_and_stamp_human_decisions(
            None,
            &mut mc.clone(),
            &ReviewerContext::pending_only(42),
            "00000000-0000-4000-8000-000000000001",
            "000000000000000000000001",
            1,
            &c_rev,
            &s_rev,
        )
        .unwrap_err();
        assert_eq!(forbidden_err.error_code(), "REVIEW_PERMISSION_DENIED");

        // Pending unclassified/unknown question CAN be created by pending_only operator
        let mut pending: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-unclassified.json"
        ))
        .unwrap();
        contracts::populate_v2_1_hashes(&mut pending).unwrap();
        let pending_crev = pending["content_revision"].as_str().unwrap().to_string();
        let pending_srev = pending["semantic_revision"].as_str().unwrap().to_string();
        let pending_events = authorize_and_stamp_human_decisions(
            None,
            &mut pending,
            &ReviewerContext::pending_only(42),
            "00000000-0000-4000-8000-000000000002",
            "000000000000000000000007",
            1,
            &pending_crev,
            &pending_srev,
        )
        .unwrap();
        assert!(pending_events.is_empty());

        // T03: Authorized admin reviewer produces persistent ReviewEvents and SERVER_VERIFIED provenance
        let mut approved = mc.clone();
        let events = authorize_and_stamp_human_decisions(
            None,
            &mut approved,
            &ReviewerContext::admin(99),
            "00000000-0000-4000-8000-000000000003",
            "000000000000000000000001",
            1,
            &c_rev,
            &s_rev,
        )
        .unwrap();
        assert_eq!(events.len(), 3);
        assert_eq!(approved["validity"]["decision"]["actor"]["id"], "99");
        assert_eq!(
            approved["validity"]["decision"]["trust_level"],
            "SERVER_VERIFIED"
        );
        assert_eq!(approved["validity"]["evaluation_mode"], "SEMANTIC");
        assert_eq!(approved["validity"]["evaluated_semantic_revision"], s_rev);
        contracts::validate(&approved).unwrap();
    }

    #[test]
    fn sub5_multiple_choice_classifier_excludes_true_false_open_ended_and_5_option_mc() {
        assert!(is_sub5_multiple_choice_doc(&doc! {
            "question_type": "MULTIPLE_CHOICE",
            "answers": ["A", "B", "C", "D"]
        }));
        assert!(is_sub5_multiple_choice_doc(&doc! {
            "answers": ["A", "B", "C", "D"]
        }));
        assert!(is_sub5_multiple_choice_doc(&doc! {
            "answers": ["Sim", "Talvez"]
        }));
        assert!(!is_sub5_multiple_choice_doc(&doc! {
            "question_type": "MULTIPLE_CHOICE",
            "answers": ["A", "B", "C", "D", "E"]
        }));
        assert!(!is_sub5_multiple_choice_doc(&doc! {
            "question_type": "TRUE_FALSE",
            "answers": ["Certo", "Errado"]
        }));
        assert!(!is_sub5_multiple_choice_doc(&doc! {
            "answers": ["Certo", "Errado"]
        }));
        assert!(!is_sub5_multiple_choice_doc(&doc! {
            "question_type": "OPEN_ENDED",
            "answers": []
        }));
    }
}
