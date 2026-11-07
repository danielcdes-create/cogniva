//! Versioned editorial drafts. Runtime catalogs and historical references stay immutable.
use crate::{
    error::AppError,
    services::{question_contracts, question_storage},
    AppState,
};
use bson::{doc, Bson, Document};
use futures::TryStreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    sync::OnceLock,
};
use unicode_normalization::{char::is_combining_mark, UnicodeNormalization};

fn bad(s: impl Into<String>) -> AppError {
    AppError::BadRequest(s.into())
}
pub fn hash(value: &Value) -> String {
    format!(
        "{:x}",
        Sha256::digest(serde_json_canonicalizer::to_vec(value).expect("JSON catalog"))
    )
}
fn normalized(s: &str) -> String {
    s.nfkd()
        .filter(|c| !is_combining_mark(*c))
        .flat_map(char::to_lowercase)
        .flat_map(|c| {
            if c == 'ß' {
                vec!['s', 's']
            } else {
                vec![if c == 'ς' { 'σ' } else { c }]
            }
        })
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}
fn index<'a>(
    catalog: &'a Value,
    group: &str,
    key: &str,
) -> Result<HashMap<&'a str, &'a Value>, AppError> {
    let mut map = HashMap::new();
    for row in catalog[group]
        .as_array()
        .ok_or_else(|| bad(format!("Lista {group} ausente")))?
    {
        let id = row[key]
            .as_str()
            .ok_or_else(|| bad(format!("Identidade {key} ausente")))?;
        if map.insert(id, row).is_some() {
            return Err(bad(format!("Identidade repetida: {id}")));
        }
    }
    Ok(map)
}
pub fn validate(catalog: &Value) -> Result<Value, AppError> {
    let snapshot=super::catalog_runtime::snapshot();
    validate_against(catalog,&snapshot.current,&snapshot.registry)
}
pub fn validate_against(catalog:&Value,baseline:&Value,registry_base:&Value)->Result<Value,AppError>{
    static SCHEMA: OnceLock<jsonschema::Validator> = OnceLock::new();
    let validator = SCHEMA.get_or_init(|| {
        jsonschema::options()
            .should_validate_formats(true)
            .build(
                &serde_json::from_str::<Value>(include_str!(
                    "../../../shared/knowledge-catalog.schema.json"
                ))
                .unwrap(),
            )
            .unwrap()
    });
    if let Some(error) = validator.iter_errors(catalog).next() {
        return Err(bad(format!(
            "{}: {}",
            error.instance_path,
            error.to_string().chars().take(200).collect::<String>()
        )));
    }
    if catalog["catalog_version"].as_u64()
        != baseline["catalog_version"]
            .as_u64()
            .and_then(|v| v.checked_add(1))
        || catalog["compatibility"] != baseline["compatibility"]
    {
        return Err(bad(
            "A proposta deve avançar uma versão e preservar o contrato de compatibilidade",
        ));
    }
    let sources = index(catalog, "sources", "source_id")?;
    let trunks = index(catalog, "trunks", "trunk_id")?;
    if !trunks.values().any(|t|t["status"]=="ACTIVE"){return Err(bad("Mantenha ao menos um tronco ativo para novos cadastros"));}
    let subjects = index(catalog, "subjects", "subject_id")?;
    let topics = index(catalog, "topics", "topic_id")?;
    let mut all = HashSet::new();
    let mut aliases = HashMap::new();
    for (group, key, rows) in [
        ("trunks", "trunk_id", &trunks),
        ("subjects", "subject_id", &subjects),
        ("topics", "topic_id", &topics),
    ] {
        let old = index(baseline, group, key)?;
        for id in old.keys() {
            if !rows.contains_key(id) {
                return Err(bad(format!("ID protegido removido: {id}. Use DEPRECATED")));
            }
        }
        for (&id, row) in rows {
            if !all.insert(id) {
                return Err(bad(format!("ID reutilizado entre entidades: {id}")));
            }
            if row["source_ids"]
                .as_array()
                .unwrap()
                .iter()
                .any(|s| !sources.contains_key(s.as_str().unwrap()))
            {
                return Err(bad(format!("Fonte desconhecida em {id}")));
            }
            let scope = if group == "topics" {
                format!("{group}:{}:{}", row["subject_id"], row["parent_topic_id"])
            } else {
                group.into()
            };
            let mut names = vec![row["label"].as_str().unwrap()];
            names.extend(
                row["aliases"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|v| v.as_str().unwrap()),
            );
            for name in names {
                let norm = normalized(name);
                if name != name.trim()
                    || norm.is_empty()
                    || aliases.insert((scope.clone(), norm), id).is_some()
                {
                    return Err(bad(format!(
                        "Nome/alias vazio, repetido ou ambíguo: {id}/{name}"
                    )));
                }
            }
            if let Some(prior) = old.get(id) {
                if group == "topics"
                    && (row["subject_id"] != prior["subject_id"]
                        || row["parent_topic_id"] != prior["parent_topic_id"]
                        || row["kind"] != prior["kind"])
                {
                    return Err(bad(format!("Identidade ou filiação alterada: {id}")));
                }
                let retained = |name: &Value| {
                    row["label"] == *name || row["aliases"].as_array().unwrap().contains(name)
                };
                if !retained(&prior["label"])
                    || prior["aliases"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|name| !retained(name))
                {
                    return Err(bad(format!("Nome anterior/alias removido: {id}")));
                }
                if row.get("revision").is_some() {
                    let mut a: Value = (**prior).clone();
                    let mut b: Value = (**row).clone();
                    a.as_object_mut().unwrap().remove("revision");
                    b.as_object_mut().unwrap().remove("revision");
                    if row["revision"].as_u64().unwrap()
                        < prior["revision"].as_u64().unwrap() + u64::from(a != b)
                    {
                        return Err(bad(format!("Revisão não avançou: {id}")));
                    }
                }
            }
        }
    }
    for (&id, subject) in &subjects {
        let mut seen = HashSet::new();
        for link in subject["trunk_links"].as_array().unwrap() {
            let trunk = link["trunk_id"].as_str().unwrap();
            if !trunks.contains_key(trunk) || !seen.insert(trunk) {
                return Err(bad(format!("Tronco inexistente/repetido: {id}/{trunk}")));
            }
        }
        if !topics
            .values()
            .any(|t| t["subject_id"] == id && t["kind"] == "TOPIC")
        {
            return Err(bad(format!("Disciplina sem assunto: {id}")));
        }
    }
    for (&id, topic) in &topics {
        if !subjects.contains_key(topic["subject_id"].as_str().unwrap()) {
            return Err(bad(format!("Disciplina inexistente em {id}")));
        }
        if topic["parent_topic_id"].is_null() {
            if topic["kind"] != "TOPIC" || !topics.values().any(|t| t["parent_topic_id"] == id) {
                return Err(bad(format!("Assunto deve ter subassunto: {id}")));
            }
        } else {
            let parent = topics
                .get(topic["parent_topic_id"].as_str().unwrap())
                .ok_or_else(|| bad(format!("Pai inexistente: {id}")))?;
            if topic["kind"] != "SUBTOPIC"
                || parent["kind"] != "TOPIC"
                || parent["subject_id"] != topic["subject_id"]
                || topic["parent_topic_id"] == id
            {
                return Err(bad(format!("Hierarquia incompatível: {id}")));
            }
        }
    }
    for old in baseline["sources"].as_array().unwrap() {
        if sources
            .get(old["source_id"].as_str().unwrap())
            .is_none_or(|v| *v != old)
        {
            return Err(bad("Fontes anteriores devem permanecer preservadas"));
        }
    }
    let mut relations = HashSet::new();
    for r in catalog["relations"].as_array().unwrap() {
        let a = r["from_topic_id"].as_str().unwrap();
        let b = r["to_topic_id"].as_str().unwrap();
        if a == b
            || !topics.contains_key(a)
            || !topics.contains_key(b)
            || r["source_ids"]
                .as_array()
                .unwrap()
                .iter()
                .any(|s| !sources.contains_key(s.as_str().unwrap()))
        {
            return Err(bad("Relação entre assuntos inválida"));
        }
        let mut pair = [a, b];
        pair.sort();
        if !relations.insert((pair, r["relation"].as_str().unwrap())) {
            return Err(bad("Relação simétrica repetida"));
        }
    }
    let mut registry: Value =registry_base.clone();
    let mut entries = Vec::new();
    for (entity, rows) in [
        ("TRUNK", &trunks),
        ("SUBJECT", &subjects),
        ("TOPIC", &topics),
    ] {
        for (&id, row) in rows {
            let pin = registry["entries"]
                .as_array()
                .unwrap()
                .iter()
                .find(|e| e["id"] == id);
            entries.push(pin.cloned().unwrap_or_else(||json!({"id":id,"entity":entity,"subject_id":row.get("subject_id").unwrap_or(&Value::Null),"parent_topic_id":row.get("parent_topic_id").unwrap_or(&Value::Null),"initial_label":row["label"]})));
        }
    }
    entries.sort_by(|a, b| a["id"].as_str().cmp(&b["id"].as_str()));
    registry["entries"] = json!(entries);
    registry["registry_version"] = json!(registry["registry_version"].as_u64().unwrap() + 1);
    Ok(
        json!({"valid":true,"catalog_sha256":hash(catalog),"registry":registry,"activation":"ADMIN_REVIEW","counts":{"trunks":trunks.len(),"subjects":subjects.len(),"topics":topics.len()}}),
    )
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DraftRequest {
    pub draft_id: String,
    pub expected_revision: i64,
    pub base_catalog_sha256: String,
    pub status: String,
    pub catalog: Value,
}
pub async fn save(state: &AppState, user: i64, r: DraftRequest) -> Result<Value, AppError> {
    uuid::Uuid::parse_str(&r.draft_id).map_err(|_| bad("Identidade do rascunho inválida"))?;
    if r.expected_revision < 0
        || r.expected_revision > 1000000
        || !["DRAFT", "REVIEW_READY"].contains(&r.status.as_str())
        || serde_json::to_vec(&r.catalog).unwrap_or_default().len() > 768 * 1024
    {
        return Err(bad("Rascunho fora dos limites"));
    }
    let revision = r.expected_revision + 1;
    let key = format!("{user}:{}", r.draft_id);
    let version_key = format!("{key}:{revision}");
    let payload =
        json!({"catalog":r.catalog,"status":r.status,"base_catalog_sha256":r.base_catalog_sha256});
    let payload_hash = hash(&payload);
    let heads = question_storage::collection(&state.db, "catalog_editor_drafts");
    let versions = question_storage::collection(&state.db, "catalog_editor_versions");
    if let Some(old) = heads.find_one(doc! {"_id":&key}).await? {
        if old.get_i64("revision").ok() == Some(revision)
            && old.get_str("payload_sha256").ok() == Some(&payload_hash)
        {
            return Ok(
                json!({"draft":Bson::Document(old).into_relaxed_extjson(),"validation":null}),
            );
        }
        if old.get_i64("revision").ok() != Some(r.expected_revision) {
            return Err(AppError::Conflict(
                "Rascunho alterado em outra janela; exporte sua cópia e confira a revisão atual"
                    .into(),
            ));
        }
    } else if r.expected_revision != 0 {
        return Err(AppError::Conflict("Revisão do rascunho ausente".into()));
    }
    if r.base_catalog_sha256 != hash(&question_contracts::catalog_ref()) {
        return Err(AppError::Conflict("O catálogo de referência mudou; revise a proposta preservada".into()));
    }
    let validation=if r.status=="REVIEW_READY"{validate(&r.catalog)?}else{json!(null)};
    let catalog =
        Bson::try_from(payload["catalog"].clone()).map_err(|_| bad("Catálogo não armazenável"))?;
    let version = doc! {"_id":&version_key,"draft_id":&r.draft_id,"owner_user_id":user,"revision":revision,"payload_sha256":&payload_hash,"base_catalog_sha256":&r.base_catalog_sha256,"status":&r.status,"catalog":catalog,"saved_at":bson::DateTime::now()};
    if let Some(old) = versions.find_one(doc! {"_id":&version_key}).await? {
        if old.get_str("payload_sha256").ok() != Some(&payload_hash) {
            return Err(AppError::Conflict(
                "Outra proposta já reservou esta revisão".into(),
            ));
        }
    } else {
        versions.insert_one(version.clone()).await?;
    }
    let mut head = version;
    head.insert("_id", &key);
    if r.expected_revision == 0 {
        heads.insert_one(head.clone()).await?;
    } else {
        let result = heads
            .replace_one(
                doc! {"_id":&key,"revision":r.expected_revision},
                head.clone(),
            )
            .await?;
        if result.matched_count != 1 {
            return Err(AppError::Conflict(
                "Alteração concorrente; sua versão foi preservada para conferência".into(),
            ));
        }
    }
    Ok(json!({"draft":Bson::Document(head).into_relaxed_extjson(),"validation":validation}))
}
pub async fn list(state: &AppState, user: i64) -> Result<Value, AppError> {
    let rows: Vec<Document> = question_storage::collection(&state.db, "catalog_editor_drafts")
        .find(doc! {"owner_user_id":user})
        .sort(doc! {"saved_at":-1})
        .limit(50)
        .projection(doc! {"catalog":0})
        .await?
        .try_collect()
        .await?;
    Ok(json!(rows
        .into_iter()
        .map(|d| Bson::Document(d).into_relaxed_extjson())
        .collect::<Vec<_>>()))
}
pub async fn get(state: &AppState, user: i64, id: &str) -> Result<Value, AppError> {
    let row = question_storage::collection(&state.db, "catalog_editor_drafts")
        .find_one(doc! {"_id":format!("{user}:{id}"),"owner_user_id":user})
        .await?
        .ok_or_else(|| AppError::NotFound("Rascunho ausente nesta conta".into()))?;
    Ok(Bson::Document(row).into_relaxed_extjson())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn next() -> Value {
        let mut c = question_contracts::catalog();
        c["catalog_version"] = json!(2);
        c
    }
    #[test]
    fn next_version_exports_registry_without_touching_baseline() {
        let c = next();
        assert!(validate(&c).is_ok());
        assert_eq!(question_contracts::catalog_ref()["catalog_version"], 1);
    }
    #[test]
    fn rejects_deletion_reparenting_and_lost_names() {
        let mut c = next();
        c["subjects"].as_array_mut().unwrap().remove(0);
        assert!(validate(&c).is_err());
        c = next();
        c["topics"][0]["subject_id"] = json!("MAT");
        assert!(validate(&c).is_err());
        c = next();
        c["trunks"][0]["label"] = json!("Renamed");
        assert!(validate(&c).is_err());
    }
    #[test]
    fn rename_with_alias_and_unicode_identity_rules() {
        let mut c = next();
        let old = c["trunks"][0]["label"].clone();
        c["trunks"][0]["aliases"].as_array_mut().unwrap().push(old);
        c["trunks"][0]["label"] = json!("Fiscal revisado");
        assert!(validate(&c).is_ok());
        assert_eq!(normalized(" Administração "), normalized("ADMINISTRACAO"));
        c["trunks"][1]["aliases"] = json!(["FISCAL REVISADO"]);
        assert!(validate(&c).is_err());
    }
}
