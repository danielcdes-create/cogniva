//! Authoritative v2/v2.1 write validation, JCS signatures, ObjectId/integer boundaries, and schema parity.
use crate::error::AppError;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashSet,
    sync::{Arc, OnceLock},
};

pub const CONTENT_HASH_VERSION: &str = "question-v2-jcs-sha256";
pub const SEMANTIC_HASH_VERSION: &str = "question-v2-semantic-jcs-sha256-v1";
pub const GRADING_HASH_VERSION: &str = "question-v2-grading-jcs-sha256-v1";

pub const MIN_EXAM_YEAR: i32 = 1900;
pub const MAX_EXAM_YEAR: i32 = 2200;
pub const MAX_CONCURSOS_ITEMS: usize = 50;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContractError {
    pub code: &'static str,
    pub field: Option<String>,
    pub message: String,
}

impl ContractError {
    pub fn new(
        code: &'static str,
        field: Option<impl Into<String>>,
        message: impl Into<String>,
    ) -> Self {
        Self {
            code,
            field: field.map(Into::into),
            message: message.into(),
        }
    }
}

impl From<ContractError> for AppError {
    fn from(err: ContractError) -> Self {
        AppError::bad_request_code(err.code, err.field, err.message)
    }
}

pub fn catalog_ref() -> Arc<Value> {
    super::catalog_runtime::snapshot().current.clone()
}

pub fn catalog() -> Value {
    (*catalog_ref()).clone()
}

fn bad_code(
    code: &'static str,
    field: Option<&str>,
    message: impl Into<String>,
) -> ContractError {
    ContractError::new(code, field, message)
}

fn ensure(
    ok: bool,
    code: &'static str,
    field: Option<&str>,
    message: impl Into<String>,
) -> Result<(), ContractError> {
    if ok {
        Ok(())
    } else {
        Err(bad_code(code, field, message))
    }
}

pub fn is_strict_json_int(value: &Value) -> bool {
    match value {
        Value::Number(n) => !n.is_f64() && (n.is_i64() || n.is_u64()),
        _ => false,
    }
}

pub fn is_valid_oid_hex(raw: &str) -> bool {
    raw.len() == 24
        && raw
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

pub fn is_uuid_string(raw: &str) -> bool {
    uuid::Uuid::try_parse(raw).is_ok() && raw.len() == 36
}

pub fn validate_api_question_id(
    raw: &str,
    field: &str,
) -> Result<bson::oid::ObjectId, ContractError> {
    if !is_valid_oid_hex(raw) {
        return Err(bad_code(
            "INVALID_OBJECT_ID",
            Some(field),
            format!("Identificador hexadecimal de 24 caracteres minúsculos esperado em {field}"),
        ));
    }
    bson::oid::ObjectId::parse_str(raw).map_err(|_| {
        bad_code(
            "INVALID_OBJECT_ID",
            Some(field),
            format!("ObjectId inválido em {field}"),
        )
    })
}

pub fn validate_contract_oid(value: &Value, field: &str) -> Result<String, ContractError> {
    let Some(obj) = value.as_object() else {
        return Err(bad_code(
            "INVALID_OBJECT_ID",
            Some(field),
            format!("Esperado Extended JSON {{\"$oid\": \"...\"}} em {field}"),
        ));
    };
    if obj.len() != 1 {
        return Err(bad_code(
            "INVALID_OBJECT_ID",
            Some(field),
            format!("Extended JSON ObjectId inválido em {field}"),
        ));
    }
    let Some(hex) = obj.get("$oid").and_then(Value::as_str) else {
        return Err(bad_code(
            "INVALID_OBJECT_ID",
            Some(field),
            format!("Extended JSON ObjectId deve conter string $oid válida em {field}"),
        ));
    };
    if !is_valid_oid_hex(hex) {
        return Err(bad_code(
            "INVALID_OBJECT_ID",
            Some(field),
            format!("ObjectId hexadecimal inválido em {field}"),
        ));
    }
    Ok(hex.to_string())
}

pub fn schema(kind: &str) -> Value {
    let bundle: Value = serde_json::from_str(include_str!(
        "../../../shared/schemas/question-contracts.schema.json"
    ))
    .expect("contracts");
    json!({"$schema":bundle["$schema"],"$ref":format!("#/$defs/{kind}"),"$defs":bundle["$defs"]})
}

fn json_pointer_to_dot_path(pointer: &str, fallback: &str) -> String {
    let trimmed = pointer.trim_start_matches('/');
    if trimmed.is_empty() {
        fallback.to_string()
    } else {
        trimmed.replace('/', ".")
    }
}

fn check_strict_question_json_integers(q: &Value) -> Result<(), ContractError> {
    let Some(obj) = q.as_object() else {
        return Err(bad_code(
            "SCHEMA_VALIDATION_FAILED",
            Some("question"),
            "Documento da questão deve ser objeto JSON",
        ));
    };
    if let Some(id_val) = obj.get("_id") {
        validate_contract_oid(id_val, "_id")?;
    }
    for top in ["schema_version", "revision"] {
        if let Some(v) = obj.get(top) {
            ensure(
                is_strict_json_int(v),
                "INVALID_INTEGER",
                Some(top),
                format!("Campo {top} exige inteiro JSON estrito"),
            )?;
        }
    }
    if let Some(year_val) = obj.get("exam_year") {
        if !year_val.is_null() {
            ensure(
                is_strict_json_int(year_val),
                "INVALID_INTEGER",
                Some("exam_year"),
                "exam_year deve ser inteiro estrito",
            )?;
        }
    }
    if let Some(arr) = obj.get("correct_answer_indices").and_then(Value::as_array) {
        for (idx, item) in arr.iter().enumerate() {
            if !is_strict_json_int(item) {
                let path = format!("correct_answer_indices.{idx}");
                return Err(bad_code(
                    "INVALID_INTEGER",
                    Some(&path),
                    format!("Índice em {path} deve ser inteiro estrito"),
                ));
            }
        }
    }
    if let Some(origins) = obj.get("origins").and_then(Value::as_array) {
        for (idx, origin) in origins.iter().enumerate() {
            if let Some(origin_year) = origin.get("exam_year") {
                if !origin_year.is_null() && !is_strict_json_int(origin_year) {
                    let path = format!("origins.{idx}.exam_year");
                    return Err(bad_code(
                        "INVALID_INTEGER",
                        Some(&path),
                        format!("Campo {path} deve ser inteiro estrito"),
                    ));
                }
            }
        }
    }
    if let Some(cls) = obj.get("classification").and_then(Value::as_object) {
        for sub in ["revision", "catalog_version"] {
            if let Some(v) = cls.get(sub) {
                if !is_strict_json_int(v) {
                    let path = format!("classification.{sub}");
                    return Err(bad_code(
                        "INVALID_INTEGER",
                        Some(&path),
                        format!("Campo {path} deve ser inteiro estrito"),
                    ));
                }
            }
        }
    }
    if let Some(val) = obj.get("validity").and_then(Value::as_object) {
        if let Some(v) = val.get("revision") {
            ensure(
                is_strict_json_int(v),
                "INVALID_INTEGER",
                Some("validity.revision"),
                "Campo validity.revision deve ser inteiro estrito",
            )?;
        }
    }
    if let Some(rubric) = obj
        .get("grading")
        .and_then(|g| g.get("rubric"))
        .and_then(Value::as_object)
    {
        if let Some(v) = rubric.get("revision") {
            ensure(
                is_strict_json_int(v),
                "INVALID_INTEGER",
                Some("grading.rubric.revision"),
                "Campo grading.rubric.revision deve ser inteiro estrito",
            )?;
        }
    }
    Ok(())
}

fn check_strict_auxiliary_json_integers(kind: &str, value: &Value) -> Result<(), ContractError> {
    let Some(obj) = value.as_object() else {
        return Err(bad_code(
            "SCHEMA_VALIDATION_FAILED",
            Some(kind),
            "Documento deve ser objeto JSON",
        ));
    };
    if matches!(kind, "suggestion" | "compatibility" | "evaluation") {
        if let Some(qid) = obj.get("question_id") {
            let Some(qid_str) = qid.as_str() else {
                return Err(bad_code(
                    "INVALID_OBJECT_ID",
                    Some("question_id"),
                    "question_id deve ser hexadecimal de 24 caracteres",
                ));
            };
            validate_api_question_id(qid_str, "question_id")?;
        }
    }
    for int_key in [
        "schema_version",
        "revision",
        "catalog_version",
        "exam_revision",
        "owner_user_id",
        "user_id",
        "resource_revision",
    ] {
        if let Some(v) = obj.get(int_key) {
            if !v.is_null() && !is_strict_json_int(v) {
                return Err(bad_code(
                    "INVALID_INTEGER",
                    Some(int_key),
                    format!("Campo {int_key} exige inteiro JSON estrito"),
                ));
            }
        }
    }
    Ok(())
}

pub fn shape_detailed(kind: &str, value: &Value) -> Result<(), ContractError> {
    if kind == "question" {
        check_strict_question_json_integers(value)?;
        static QUESTION: OnceLock<jsonschema::Validator> = OnceLock::new();
        let validator = QUESTION.get_or_init(|| {
            jsonschema::options()
                .should_validate_formats(true)
                .build(&schema("question"))
                .expect("bundled schema")
        });
        let mut errors = validator.iter_errors(value);
        if let Some(first) = errors.next() {
            let field = json_pointer_to_dot_path(&first.instance_path.to_string(), "question");
            let code = if field == "_id" || field.starts_with("_id.") {
                "INVALID_OBJECT_ID"
            } else {
                "SCHEMA_VALIDATION_FAILED"
            };
            let normalized_field = if code == "INVALID_OBJECT_ID" {
                "_id".to_string()
            } else {
                field
            };
            let msg = format!(
                "Contrato da questão ({}): {}",
                normalized_field,
                first.to_string().chars().take(180).collect::<String>()
            );
            return Err(ContractError::new(code, Some(normalized_field), msg));
        }
        return Ok(());
    }

    check_strict_auxiliary_json_integers(kind, value)?;
    let validator = jsonschema::options()
        .should_validate_formats(true)
        .build(&schema(kind))
        .map_err(|_| bad_code("INTERNAL_ERROR", None, "Schema indisponível"))?;
    let mut errors = validator.iter_errors(value);
    if let Some(first) = errors.next() {
        let field = json_pointer_to_dot_path(&first.instance_path.to_string(), kind);
        return Err(ContractError::new(
            "SCHEMA_VALIDATION_FAILED",
            Some(field),
            "Documento não atende ao contrato de schema",
        ));
    }
    Ok(())
}

pub fn shape(kind: &str, value: &Value) -> Result<(), AppError> {
    shape_detailed(kind, value).map_err(Into::into)
}

pub fn hash(q: &Value) -> Result<String, AppError> {
    let mut content = serde_json::Map::new();
    for key in [
        "question_type",
        "question",
        "answers",
        "correct_answer_indices",
        "grading",
    ] {
        content.insert(key.into(), q[key].clone());
    }
    let bytes = serde_json_canonicalizer::to_vec(&content)
        .map_err(|_| AppError::BadRequest("Conteúdo não canonizável".into()))?;
    Ok(format!("{:x}", Sha256::digest(bytes)))
}

pub fn semantic_payload(q: &Value) -> Value {
    let is_open = q["question_type"] == "OPEN_ENDED";
    let expected_responses = if is_open {
        q["grading"]["expected_responses"]
            .as_array()
            .cloned()
            .unwrap_or_default()
    } else {
        Vec::new()
    };
    let substantive_criteria = if is_open {
        q["grading"]["rubric"]["criteria"]
            .as_array()
            .map(|arr| {
                arr.iter()
                    .map(|c| {
                        json!({
                            "criterion_id": c["criterion_id"],
                            "description": c["description"],
                            "essential": c["essential"],
                        })
                    })
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default()
    } else {
        Vec::new()
    };
    json!({
        "question_type": q["question_type"],
        "question": q["question"],
        "answers": q["answers"],
        "correct_answer_indices": q["correct_answer_indices"],
        "expected_responses": expected_responses,
        "substantive_criteria": substantive_criteria,
    })
}

pub fn semantic_hash(q: &Value) -> Result<String, AppError> {
    let payload = semantic_payload(q);
    let bytes = serde_json_canonicalizer::to_vec(&payload)
        .map_err(|_| AppError::BadRequest("Payload semântico não canonizável".into()))?;
    Ok(format!("{:x}", Sha256::digest(bytes)))
}

pub fn grading_hash(q: &Value) -> Result<String, AppError> {
    let bytes = serde_json_canonicalizer::to_vec(&q["grading"])
        .map_err(|_| AppError::BadRequest("Política de correção não canonizável".into()))?;
    Ok(format!("{:x}", Sha256::digest(bytes)))
}

pub fn populate_v2_1_hashes(q: &mut Value) -> Result<(), AppError> {
    let c_rev = hash(q)?;
    let s_rev = semantic_hash(q)?;
    let g_rev = grading_hash(q)?;
    q["content_revision"] = Value::String(c_rev);
    q["content_hash_version"] = Value::String(CONTENT_HASH_VERSION.to_string());
    q["semantic_revision"] = Value::String(s_rev);
    q["semantic_hash_version"] = Value::String(SEMANTIC_HASH_VERSION.to_string());
    q["grading_revision"] = Value::String(g_rev);
    q["grading_hash_version"] = Value::String(GRADING_HASH_VERSION.to_string());
    Ok(())
}

pub fn human_review(p: &Value) -> bool {
    p["actor"]["type"] == "HUMAN" && p["method"] == "MANUAL_REVIEW"
}

pub fn checked_bson_i32(value: &bson::Bson, field: &str) -> Result<i32, AppError> {
    match value {
        bson::Bson::Int32(n) => Ok(*n),
        bson::Bson::Int64(n) => i32::try_from(*n).map_err(|_| {
            AppError::bad_request_code(
                "INVALID_INTEGER",
                Some(field),
                format!("Inteiro fora do intervalo de 32 bits em {field}"),
            )
        }),
        _ => Err(AppError::bad_request_code(
            "INVALID_INTEGER",
            Some(field),
            format!("Esperado inteiro BSON em {field}"),
        )),
    }
}

pub fn validate_http_uri(uri: &str) -> Result<(), AppError> {
    validate_http_uri_detailed(uri, "uri").map_err(Into::into)
}

fn validate_http_uri_detailed(uri: &str, field: &str) -> Result<(), ContractError> {
    let trimmed = uri.trim();
    if trimmed.is_empty() || trimmed.len() > 2048 || trimmed.chars().any(char::is_control) {
        return Err(bad_code(
            "UNSAFE_EVIDENCE_URI",
            Some(field),
            "URI de referência inválida ou acima de 2048 caracteres",
        ));
    }
    if let Some(rest) = trimmed.strip_prefix("urn:cogniva:") {
        if !rest.is_empty()
            && rest
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b':' | b'-'))
        {
            return Ok(());
        }
        return Err(bad_code(
            "UNSAFE_EVIDENCE_URI",
            Some(field),
            "URN de referência interna Cogniva malformada",
        ));
    }
    if let Some(rest) = trimmed.strip_prefix("file:///") {
        if rest.len() > 4
            && !rest.contains("..")
            && rest
                .get(rest.len() - 4..)
                .is_some_and(|ext| ext.eq_ignore_ascii_case(".pdf"))
            && rest.bytes().all(|b| {
                b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b':' | b'/' | b'-')
            })
        {
            return Ok(());
        }
        return Err(bad_code(
            "UNSAFE_EVIDENCE_URI",
            Some(field),
            "URI file:/// permitida apenas para caderno PDF arquivístico sem travessia de diretório",
        ));
    }
    let has_http_scheme = trimmed
        .get(..7)
        .is_some_and(|p| p.eq_ignore_ascii_case("http://"))
        || trimmed
            .get(..8)
            .is_some_and(|p| p.eq_ignore_ascii_case("https://"));
    if !has_http_scheme {
        return Err(bad_code(
            "UNSAFE_EVIDENCE_URI",
            Some(field),
            "URI deve utilizar esquema http://, https:// ou urn:cogniva:",
        ));
    }
    let parsed = reqwest::Url::parse(trimmed).map_err(|_| {
        bad_code(
            "UNSAFE_EVIDENCE_URI",
            Some(field),
            "URI de referência malformada",
        )
    })?;
    if !matches!(parsed.scheme(), "http" | "https") || parsed.host_str().is_none_or(str::is_empty) {
        return Err(bad_code(
            "UNSAFE_EVIDENCE_URI",
            Some(field),
            "URI deve conter host válido com esquema http:// ou https://",
        ));
    }
    Ok(())
}

pub fn validate_exam_year_value(value: &Value, field: &str) -> Result<(), AppError> {
    validate_exam_year_detailed(value, field).map_err(Into::into)
}

fn validate_exam_year_detailed(value: &Value, field: &str) -> Result<(), ContractError> {
    if value.is_null() {
        return Ok(());
    }
    if !is_strict_json_int(value) {
        return Err(bad_code(
            "INVALID_INTEGER",
            Some(field),
            format!("{field} deve ser inteiro"),
        ));
    }
    let raw = value.as_i64().ok_or_else(|| {
        bad_code(
            "SCHEMA_VALIDATION_FAILED",
            Some(field),
            format!("{field} fora do intervalo permitido"),
        )
    })?;
    let year = i32::try_from(raw).map_err(|_| {
        bad_code(
            "SCHEMA_VALIDATION_FAILED",
            Some(field),
            format!("{field} fora do intervalo permitido"),
        )
    })?;
    ensure(
        (MIN_EXAM_YEAR..=MAX_EXAM_YEAR).contains(&year),
        "SCHEMA_VALIDATION_FAILED",
        Some(field),
        format!("{field} deve estar entre {MIN_EXAM_YEAR} e {MAX_EXAM_YEAR}"),
    )
}

fn validate_evidence_uris(evidence: &Value, field_prefix: &str) -> Result<(), ContractError> {
    if let Some(items) = evidence.as_array() {
        let ev_field = format!("{field_prefix}.evidence");
        for item in items {
            let uri = item["uri"].as_str().ok_or_else(|| {
                bad_code(
                    "SCHEMA_VALIDATION_FAILED",
                    Some(&ev_field),
                    "Evidência documental exige URI textual",
                )
            })?;
            validate_http_uri_detailed(uri, &ev_field)?;
        }
    }
    Ok(())
}

fn validate_provenance_integrity(p: &Value, field_prefix: &str) -> Result<(), ContractError> {
    if p.is_null() {
        return Ok(());
    }
    validate_evidence_uris(&p["evidence"], field_prefix)?;
    if p["trust_level"] == "SERVER_VERIFIED" {
        let rev_id_field = format!("{field_prefix}.review_event_id");
        let valid_uuid = p["review_event_id"]
            .as_str()
            .is_some_and(is_uuid_string);
        ensure(
            valid_uuid,
            "REVIEW_ACTOR_UNTRUSTED",
            Some(&rev_id_field),
            "Proveniência SERVER_VERIFIED exige review_event_id válido emitido pelo servidor",
        )?;
    }
    Ok(())
}

pub fn reject_client_server_provenance(
    p: &Value,
    field_prefix: &str,
) -> Result<(), ContractError> {
    let Some(obj) = p.as_object() else {
        return Ok(());
    };
    if obj.get("review_event_id").is_some_and(|v| !v.is_null()) {
        let f = format!("{field_prefix}.review_event_id");
        return Err(bad_code(
            "REVIEW_ACTOR_UNTRUSTED",
            Some(&f),
            "Cliente não pode autoatribuir review_event_id",
        ));
    }
    if obj
        .get("trust_level")
        .and_then(Value::as_str)
        == Some("SERVER_VERIFIED")
    {
        let f = format!("{field_prefix}.trust_level");
        return Err(bad_code(
            "REVIEW_ACTOR_UNTRUSTED",
            Some(&f),
            "Cliente não pode autoatribuir trust_level SERVER_VERIFIED",
        ));
    }
    Ok(())
}

pub fn known_topic(subject: &str, topic: &str) -> bool {
    let snapshot = super::catalog_runtime::snapshot();
    snapshot.references[&snapshot.version]
        .active
        .contains(&(subject.into(), topic.into()))
}

fn topic_valid_in_catalog(
    catalog_override: Option<&Value>,
    catalog_ver: i64,
    subject: &str,
    topic: &str,
) -> Result<(), (&'static str, &'static str)> {
    if let Some(cat) = catalog_override {
        let subj_status = cat["subjects"]
            .as_array()
            .and_then(|arr| arr.iter().find(|s| s["subject_id"].as_str() == Some(subject)))
            .and_then(|s| s["status"].as_str());
        let topic_obj = cat["topics"]
            .as_array()
            .and_then(|arr| arr.iter().find(|t| t["topic_id"].as_str() == Some(topic)));
        let Some(t) = topic_obj else {
            return Err(("INVALID_TAXONOMY_LINK", "Vínculo fora do catálogo"));
        };
        if t["subject_id"].as_str() != Some(subject) || subj_status.is_none() {
            return Err(("INVALID_TAXONOMY_LINK", "Vínculo fora do catálogo"));
        }
        if t["status"].as_str() != Some("ACTIVE") || subj_status != Some("ACTIVE") {
            return Err(("INACTIVE_TAXONOMY_LINK", "Vínculo inativo no catálogo"));
        }
        return Ok(());
    }
    if super::catalog_runtime::topic_at(catalog_ver, subject, topic) {
        Ok(())
    } else {
        Err(("INVALID_TAXONOMY_LINK", "Vínculo fora do catálogo"))
    }
}

pub fn validate_with_catalog(
    q: &Value,
    catalog_override: Option<&Value>,
    allow_server_provenance: bool,
) -> Result<(), ContractError> {
    if !allow_server_provenance {
        if let Some(origins) = q["origins"].as_array() {
            for (idx, origin) in origins.iter().enumerate() {
                let prefix = format!("origins.{idx}.provenance");
                reject_client_server_provenance(&origin["provenance"], &prefix)?;
            }
        }
        if let Some(links) = q["classification"]["links"].as_array() {
            for (idx, link) in links.iter().enumerate() {
                let prefix = format!("classification.links.{idx}.provenance");
                reject_client_server_provenance(&link["provenance"], &prefix)?;
            }
        }
        if !q["validity"]["decision"].is_null() {
            reject_client_server_provenance(&q["validity"]["decision"], "validity.decision")?;
        }
    }

    check_strict_question_json_integers(q)?;

    if let Some(year_val) = q.get("exam_year") {
        validate_exam_year_detailed(year_val, "exam_year")?;
    }
    if let Some(concursos_val) = q.get("concursos") {
        if !concursos_val.is_null() {
            match concursos_val {
                Value::String(s) => {
                    ensure(
                        s.chars().count() <= 300,
                        "SCHEMA_VALIDATION_FAILED",
                        Some("concursos"),
                        "Campo concursos excede 300 caracteres",
                    )?;
                }
                Value::Array(arr) => {
                    ensure(
                        arr.len() <= MAX_CONCURSOS_ITEMS,
                        "SCHEMA_VALIDATION_FAILED",
                        Some("concursos"),
                        "Lista concursos aceita no máximo 50 itens",
                    )?;
                    for entry in arr {
                        let s = entry.as_str().ok_or_else(|| {
                            bad_code(
                                "SCHEMA_VALIDATION_FAILED",
                                Some("concursos"),
                                "Itens de concursos devem ser texto",
                            )
                        })?;
                        ensure(
                            s.chars().count() <= 300,
                            "SCHEMA_VALIDATION_FAILED",
                            Some("concursos"),
                            "Item de concursos excede 300 caracteres",
                        )?;
                    }
                }
                _ => {
                    return Err(bad_code(
                        "SCHEMA_VALIDATION_FAILED",
                        Some("concursos"),
                        "Campo concursos inválido",
                    ))
                }
            }
        }
    }
    if let Some(origins) = q["origins"].as_array() {
        for (idx, origin) in origins.iter().enumerate() {
            if let Some(origin_year) = origin.get("exam_year") {
                let path = format!("origins.{idx}.exam_year");
                validate_exam_year_detailed(origin_year, &path)?;
            }
            if !origin["source_reference"].is_null() {
                if let Some(uri) = origin["source_reference"]["uri"].as_str() {
                    if !uri.trim().is_empty() {
                        let path = format!("origins.{idx}.source_reference");
                        validate_http_uri_detailed(uri, &path)?;
                    }
                }
            }
            let prov_path = format!("origins.{idx}.provenance");
            validate_provenance_integrity(&origin["provenance"], &prov_path)?;
        }
    }
    if let Some(links) = q["classification"]["links"].as_array() {
        for (idx, link) in links.iter().enumerate() {
            let prov_path = format!("classification.links.{idx}.provenance");
            validate_provenance_integrity(&link["provenance"], &prov_path)?;
        }
    }
    if !q["validity"]["decision"].is_null() {
        validate_provenance_integrity(&q["validity"]["decision"], "validity.decision")?;
    }

    shape_detailed("question", q)?;

    let computed_content = hash(q).map_err(|_| {
        bad_code(
            "CONTENT_REVISION_MISMATCH",
            Some("content_revision"),
            "Conteúdo não canonizável",
        )
    })?;
    ensure(
        q["content_revision"].as_str() == Some(computed_content.as_str()),
        "CONTENT_REVISION_MISMATCH",
        Some("content_revision"),
        "Hash de conteúdo divergente",
    )?;

    let computed_semantic = semantic_hash(q).map_err(|_| {
        bad_code(
            "SEMANTIC_REVISION_MISMATCH",
            Some("semantic_revision"),
            "Payload semântico não canonizável",
        )
    })?;
    let has_sem_rev = q.get("semantic_revision").is_some_and(|v| !v.is_null());
    let has_sem_ver = q.get("semantic_hash_version").is_some_and(|v| !v.is_null());
    if has_sem_rev || has_sem_ver {
        ensure(
            q["semantic_hash_version"].as_str() == Some(SEMANTIC_HASH_VERSION)
                && q["semantic_revision"].as_str() == Some(computed_semantic.as_str()),
            "SEMANTIC_REVISION_MISMATCH",
            Some("semantic_revision"),
            "Assinatura semântica divergente",
        )?;
    }

    let computed_grading = grading_hash(q).map_err(|_| {
        bad_code(
            "GRADING_REVISION_MISMATCH",
            Some("grading_revision"),
            "Política de correção não canonizável",
        )
    })?;
    let has_grad_rev = q.get("grading_revision").is_some_and(|v| !v.is_null());
    let has_grad_ver = q.get("grading_hash_version").is_some_and(|v| !v.is_null());
    if has_grad_rev || has_grad_ver {
        ensure(
            q["grading_hash_version"].as_str() == Some(GRADING_HASH_VERSION)
                && q["grading_revision"].as_str() == Some(computed_grading.as_str()),
            "GRADING_REVISION_MISMATCH",
            Some("grading_revision"),
            "Assinatura de correção divergente",
        )?;
    }

    let origins = q["origins"].as_array().ok_or_else(|| {
        bad_code(
            "SCHEMA_VALIDATION_FAILED",
            Some("origins"),
            "Origens devem ser uma lista",
        )
    })?;
    for (idx, origin) in origins.iter().enumerate() {
        if origin["kind"] == "OFFICIAL_EXAM" && origin["verification"] == "CONFIRMED" {
            let prov_path = format!("origins.{idx}.provenance");
            ensure(
                human_review(&origin["provenance"]),
                "HUMAN_REVIEW_REQUIRED",
                Some(&prov_path),
                "Origem oficial confirmada exige revisão humana",
            )?;
        }
    }

    let question_text = q["question"].as_str().ok_or_else(|| {
        bad_code(
            "SCHEMA_VALIDATION_FAILED",
            Some("question"),
            "Enunciado inválido",
        )
    })?;
    ensure(
        !question_text.trim().is_empty(),
        "EMPTY_QUESTION",
        Some("question"),
        "Enunciado vazio",
    )?;
    let answers = q["answers"].as_array().ok_or_else(|| {
        bad_code(
            "INVALID_ANSWERS",
            Some("answers"),
            "Alternativas devem ser uma lista",
        )
    })?;
    let mut seen = HashSet::new();
    for a in answers {
        let text = a
            .as_str()
            .ok_or_else(|| {
                bad_code(
                    "INVALID_ANSWERS",
                    Some("answers"),
                    "Alternativa deve ser texto",
                )
            })?
            .trim();
        ensure(
            !text.is_empty() && seen.insert(text),
            "INVALID_ANSWERS",
            Some("answers"),
            "Alternativas vazias ou repetidas",
        )?;
    }
    if q["question_type"] == "MULTIPLE_CHOICE" {
        ensure(
            answers.len() == 5,
            "INVALID_ANSWERS",
            Some("answers"),
            "Questões de múltipla escolha exigem exatamente 5 alternativas",
        )?;
    }
    if q["question_type"] != "OPEN_ENDED" {
        let index = usize::try_from(q["correct_answer_indices"][0].as_u64().ok_or_else(|| {
            bad_code(
                "INVALID_INTEGER",
                Some("correct_answer_indices.0"),
                "Índice deve ser inteiro",
            )
        })?)
        .map_err(|_| {
            bad_code(
                "INVALID_ANSWER_KEY",
                Some("correct_answer_indices"),
                "Índice fora dos limites",
            )
        })?;
        ensure(
            index < answers.len(),
            "INVALID_ANSWER_KEY",
            Some("correct_answer_indices"),
            "Índice de gabarito fora dos limites",
        )?;
        ensure(
            q["correct_answers"][0] == answers[index],
            "INVALID_ANSWER_KEY",
            Some("correct_answers"),
            "Gabaritos textual e indexado divergem",
        )?;
    }

    let c = &q["classification"];
    let class_rev = c["revision"].as_u64().ok_or_else(|| {
        bad_code(
            "INVALID_INTEGER",
            Some("classification.revision"),
            "Revisões de bloco devem ser inteiras",
        )
    })?;
    let catalog_ver = c["catalog_version"].as_i64().ok_or_else(|| {
        bad_code(
            "INVALID_INTEGER",
            Some("classification.catalog_version"),
            "Versão de catálogo deve ser inteira",
        )
    })?;
    let ver_ok = if let Some(cat) = catalog_override {
        cat["catalog_version"]
            .as_i64()
            .is_some_and(|max_v| (1..=max_v).contains(&catalog_ver))
    } else {
        super::catalog_runtime::version_known(catalog_ver)
    };
    ensure(
        ver_ok,
        "UNKNOWN_CATALOG_VERSION",
        Some("classification.catalog_version"),
        "Versão de catálogo desconhecida",
    )?;

    let mut primary = None;
    let mut topics = HashSet::new();
    let mut ids = HashSet::new();
    let links = c["links"].as_array().ok_or_else(|| {
        bad_code(
            "SCHEMA_VALIDATION_FAILED",
            Some("classification.links"),
            "Vínculos de classificação inválidos",
        )
    })?;
    for (idx, l) in links.iter().enumerate() {
        let link_field = format!("classification.links.{idx}");
        let subj = l["subject_id"].as_str().ok_or_else(|| {
            bad_code(
                "SCHEMA_VALIDATION_FAILED",
                Some(&link_field),
                "Disciplina inválida no vínculo",
            )
        })?;
        let top = l["topic_id"].as_str().ok_or_else(|| {
            bad_code(
                "SCHEMA_VALIDATION_FAILED",
                Some(&link_field),
                "Tópico inválido no vínculo",
            )
        })?;
        let link_id = l["link_id"].as_str().ok_or_else(|| {
            bad_code(
                "SCHEMA_VALIDATION_FAILED",
                Some(&link_field),
                "Identificador de vínculo inválido",
            )
        })?;
        if let Err((code, msg)) = topic_valid_in_catalog(catalog_override, catalog_ver, subj, top) {
            return Err(bad_code(code, Some(&link_field), msg));
        }
        let concept_opt = match l.get("concept_id") {
            None | Some(Value::Null) => None,
            Some(Value::String(cid)) => {
                let active_cat = catalog_override.cloned().unwrap_or_else(catalog);
                let concepts = super::catalog_runtime::effective_concepts(&active_cat);
                if !concepts.is_empty() {
                    let Some(found) = concepts
                        .iter()
                        .find(|c| c["concept_id"].as_str() == Some(cid.as_str()))
                    else {
                        let f = format!("{link_field}.concept_id");
                        return Err(bad_code(
                            "INVALID_TAXONOMY_LINK",
                            Some(&f),
                            "Conceito inexistente no catálogo",
                        ));
                    };
                    if found["subject_id"].as_str() != Some(subj)
                        || found["topic_id"].as_str() != Some(top)
                    {
                        let f = format!("{link_field}.concept_id");
                        return Err(bad_code(
                            "INVALID_TAXONOMY_LINK",
                            Some(&f),
                            "Conceito não pertence à disciplina/subassunto do vínculo",
                        ));
                    }
                    if found["status"].as_str() != Some("ACTIVE") {
                        let f = format!("{link_field}.concept_id");
                        return Err(bad_code(
                            "INACTIVE_TAXONOMY_LINK",
                            Some(&f),
                            "Conceito desativado no catálogo",
                        ));
                    }
                }
                Some(cid.as_str())
            }
            Some(_) => {
                let f = format!("{link_field}.concept_id");
                return Err(bad_code(
                    "SCHEMA_VALIDATION_FAILED",
                    Some(&f),
                    "Identificador de conceito inválido",
                ));
            }
        };
        if !topics.insert((top, concept_opt)) {
            let f = format!("{link_field}.topic_id");
            return Err(bad_code("DUPLICATE_TAXONOMY_LINK", Some(&f), "Tópico/conceito repetido"));
        }
        if l["role"] == "PRIMARY" {
            if l["coverage"] != "DIRECT" {
                let f = format!("{link_field}.coverage");
                return Err(bad_code(
                    "INVALID_PRIMARY_LINK",
                    Some(&f),
                    "Vínculo principal exige cobertura DIRECT",
                ));
            }
            ensure(
                primary.is_none(),
                "MULTIPLE_PRIMARY_LINKS",
                Some("classification.links"),
                "Vínculo principal ambíguo",
            )?;
            primary = Some(l);
        }
        let prov_field = format!("{link_field}.provenance");
        ensure(
            human_review(&l["provenance"]),
            "HUMAN_REVIEW_REQUIRED",
            Some(&prov_field),
            "Classificação exige revisão humana",
        )?;
        if !ids.insert(link_id) {
            let f = format!("{link_field}.link_id");
            return Err(bad_code(
                "DUPLICATE_TAXONOMY_LINK",
                Some(&f),
                "Identificador de vínculo repetido",
            ));
        }
    }
    if !links.is_empty() {
        ensure(
            class_rev > 0,
            "SCHEMA_VALIDATION_FAILED",
            Some("classification.revision"),
            "Classificação sem revisão",
        )?;
    }
    ensure(
        c["state"] != "CONFIRMED" || primary.is_some(),
        "CLASSIFICATION_NOT_CONFIRMED",
        Some("classification.links"),
        "Classificação confirmada sem principal",
    )?;
    for (old, field) in [
        ("canonical_subject_id", "subject_id"),
        ("canonical_topic_id", "topic_id"),
    ] {
        if !q[old].is_null() {
            ensure(
                primary.is_some_and(|p| p[field] == q[old]),
                "CANONICAL_MIRROR_MISMATCH",
                Some(old),
                "Espelho canônico divergente",
            )?;
        }
    }
    if let Some(ev_ch) = q.get("evidence_challenge").filter(|v| !v.is_null()) {
        if let Some(opts) = ev_ch.get("options").and_then(Value::as_array) {
            let mut seen_ev = HashSet::new();
            for opt in opts {
                if let Some(eid) = opt.get("evidence_id").and_then(Value::as_str) {
                    ensure(
                        seen_ev.insert(eid),
                        "SCHEMA_VALIDATION_FAILED",
                        Some("evidence_challenge.options"),
                        "Identificador de evidência repetido no desafio",
                    )?;
                }
            }
            let correct_eid = ev_ch
                .get("correct_evidence_id")
                .and_then(Value::as_str)
                .unwrap_or("");
            ensure(
                seen_ev.contains(correct_eid),
                "SCHEMA_VALIDATION_FAILED",
                Some("evidence_challenge.correct_evidence_id"),
                "Evidência correta ausente entre as opções do desafio",
            )?;
        }
    }
    if let Some(ed_card) = q.get("editorial_card").filter(|v| !v.is_null()) {
        ensure(
            ed_card
                .get("tested_rule")
                .and_then(Value::as_str)
                .is_some_and(|s| !s.trim().is_empty()),
            "SCHEMA_VALIDATION_FAILED",
            Some("editorial_card.tested_rule"),
            "Regra cobrada na ficha editorial não pode ser vazia",
        )?;
        ensure(
            ed_card
                .get("decisive_stem_excerpt")
                .and_then(Value::as_str)
                .is_some_and(|s| !s.trim().is_empty()),
            "SCHEMA_VALIDATION_FAILED",
            Some("editorial_card.decisive_stem_excerpt"),
            "Trecho decisivo do enunciado na ficha editorial não pode ser vazio",
        )?;
        if let Some(errs) = ed_card
            .get("plausible_conceptual_errors")
            .and_then(Value::as_array)
        {
            let mut seen_err = HashSet::new();
            for item in errs {
                if let Some(eid) = item.get("error_id").and_then(Value::as_str) {
                    ensure(
                        !eid.trim().is_empty() && seen_err.insert(eid),
                        "SCHEMA_VALIDATION_FAILED",
                        Some("editorial_card.plausible_conceptual_errors"),
                        "Identificador de erro conceitual repetido ou vazio na ficha editorial",
                    )?;
                }
            }
        }
    }

    let v = &q["validity"];
    let val_rev = v["revision"].as_u64().ok_or_else(|| {
        bad_code(
            "INVALID_INTEGER",
            Some("validity.revision"),
            "Revisões de bloco devem ser inteiras",
        )
    })?;
    ensure(
        (v["scope"] == "CURRENT_KNOWLEDGE") == v["context_date"].is_null(),
        "INVALID_VALIDITY_SCOPE",
        Some("validity.context_date"),
        "Contexto histórico exige data",
    )?;
    let eval_mode = v.get("evaluation_mode").and_then(Value::as_str);
    let eval_sem = v.get("evaluated_semantic_revision").and_then(Value::as_str);
    if v["status"] == "CURRENT" || v["status"] == "OBSOLETE" {
        ensure(
            val_rev > 0 && human_review(&v["decision"]),
            "HUMAN_REVIEW_REQUIRED",
            Some("validity.decision"),
            "Validade exige decisão revisada",
        )?;
        if eval_mode == Some("SEMANTIC") || eval_sem.is_some() {
            ensure(
                matches!(eval_mode, None | Some("SEMANTIC"))
                    && eval_sem == Some(computed_semantic.as_str()),
                "SEMANTIC_REVISION_MISMATCH",
                Some("validity.evaluated_semantic_revision"),
                "Decisão de validade semântica desatualizada",
            )?;
        } else {
            ensure(
                v["evaluated_content_revision"] == q["content_revision"],
                "CONTENT_REVISION_MISMATCH",
                Some("validity.evaluated_content_revision"),
                "Decisão de validade desatualizada",
            )?;
        }
        let evidence = v["decision"]["evidence"].as_array().ok_or_else(|| {
            bad_code(
                "VALIDITY_EVIDENCE_REQUIRED",
                Some("validity.decision.evidence"),
                "Evidências de validade inválidas",
            )
        })?;
        ensure(
            v["basis"] == "TIMELESS" || !evidence.is_empty(),
            "VALIDITY_EVIDENCE_REQUIRED",
            Some("validity.decision.evidence"),
            "Validade temporal sem evidência",
        )?;
    }
    ensure(
        !(v["status"] == "CURRENT" && v["basis"] == "LEGAL" && v["review_due_on"].is_null()),
        "VALIDITY_DEADLINE_REQUIRED",
        Some("validity.review_due_on"),
        "Validade normativa exige prazo",
    )?;
    if let (Some(due), Some(review)) = (v["review_due_on"].as_str(), v["reference_date"].as_str()) {
        ensure(
            due >= review,
            "INVALID_VALIDITY_DEADLINE",
            Some("validity.review_due_on"),
            "Prazo anterior à revisão",
        )?;
    }
    let reasons = v["reason_codes"].as_array().ok_or_else(|| {
        bad_code(
            "INVALID_VALIDITY_REASON",
            Some("validity.reason_codes"),
            "Códigos de razão inválidos",
        )
    })?;
    for r in reasons {
        ensure(
            !(r == "LAW_REPEALED" || r == "LAW_AMENDED") || v["basis"] == "LEGAL",
            "INVALID_VALIDITY_REASON",
            Some("validity.reason_codes"),
            "Motivo normativo incompatível",
        )?;
    }

    let g = &q["grading"];
    let max = g["max_score"].as_f64().ok_or_else(|| {
        bad_code(
            "INVALID_GRADING",
            Some("grading.max_score"),
            "Nota máxima inválida",
        )
    })?;
    let pass = g["pass_score"].as_f64().ok_or_else(|| {
        bad_code(
            "INVALID_GRADING",
            Some("grading.pass_score"),
            "Nota de corte inválida",
        )
    })?;
    ensure(
        pass <= max,
        "INVALID_GRADING",
        Some("grading.pass_score"),
        "Corte acima da nota máxima",
    )?;
    if q["question_type"] == "OPEN_ENDED" {
        ensure(
            is_strict_json_int(&g["rubric"]["revision"]),
            "INVALID_INTEGER",
            Some("grading.rubric.revision"),
            "Revisão de rubrica deve ser inteira",
        )?;
        let mut criteria = HashSet::new();
        let mut total = 0.0;
        let criteria_arr = g["rubric"]["criteria"].as_array().ok_or_else(|| {
            bad_code(
                "INVALID_GRADING",
                Some("grading.rubric.criteria"),
                "Critérios da rubrica inválidos",
            )
        })?;
        for criterion in criteria_arr {
            let cid = criterion["criterion_id"].as_str().ok_or_else(|| {
                bad_code(
                    "INVALID_GRADING",
                    Some("grading.rubric.criteria"),
                    "Identificador de critério inválido",
                )
            })?;
            ensure(
                criteria.insert(cid),
                "INVALID_GRADING",
                Some("grading.rubric.criteria"),
                "Critério repetido",
            )?;
            total += criterion["max_points"].as_f64().ok_or_else(|| {
                bad_code(
                    "INVALID_GRADING",
                    Some("grading.rubric.criteria"),
                    "Pontuação de critério inválida",
                )
            })?;
        }
        ensure(
            (total - max).abs() < 1e-9,
            "INVALID_GRADING",
            Some("grading.rubric.criteria"),
            "Rubrica não soma a nota máxima",
        )?;
        let expected_resps = g["expected_responses"].as_array().ok_or_else(|| {
            bad_code(
                "INVALID_GRADING",
                Some("grading.expected_responses"),
                "Respostas de referência inválidas",
            )
        })?;
        for r in expected_resps {
            let txt = r.as_str().ok_or_else(|| {
                bad_code(
                    "INVALID_GRADING",
                    Some("grading.expected_responses"),
                    "Resposta de referência inválida",
                )
            })?;
            ensure(
                !txt.trim().is_empty(),
                "INVALID_GRADING",
                Some("grading.expected_responses"),
                "Referência de resposta vazia",
            )?;
        }
        let e = &g["engine"];
        if g["strategy"] == "RUBRIC" {
            ensure(
                e.is_null(),
                "INVALID_GRADING",
                Some("grading.engine"),
                "Correção manual com motor automático",
            )?;
        }
        if g["strategy"] == "AI" {
            ensure(
                e["provider"] == "xai"
                    && ["model", "prompt_id", "prompt_version"]
                        .iter()
                        .all(|k| e[k].as_str().is_some_and(|s| !s.is_empty())),
                "INVALID_GRADING",
                Some("grading.engine"),
                "Correção IA sem modelo/prompt",
            )?;
            ensure(
                [
                    "semantic_model_id",
                    "semantic_model_revision",
                    "calibration_id",
                ]
                .iter()
                .all(|k| e[k].is_null()),
                "INVALID_GRADING",
                Some("grading.engine"),
                "Motores de correção misturados",
            )?;
        }
        if g["strategy"] == "SEMANTIC" {
            ensure(
                [
                    "semantic_model_id",
                    "semantic_model_revision",
                    "calibration_id",
                ]
                .iter()
                .all(|k| e[k].as_str().is_some_and(|s| !s.is_empty())),
                "INVALID_GRADING",
                Some("grading.engine"),
                "Motor semântico sem versão/calibração",
            )?;
            ensure(
                ["provider", "model", "prompt_id", "prompt_version"]
                    .iter()
                    .all(|k| e[k].is_null()),
                "INVALID_GRADING",
                Some("grading.engine"),
                "Motores de correção misturados",
            )?;
        }
    }
    Ok(())
}

pub fn validate_detailed(q: &Value, allow_server_provenance: bool) -> Result<(), ContractError> {
    validate_with_catalog(q, None, allow_server_provenance)
}

pub fn validate(q: &Value) -> Result<(), AppError> {
    validate_detailed(q, true).map_err(Into::into)
}

pub fn validate_untrusted_client_question(q: &Value) -> Result<(), AppError> {
    validate_detailed(q, false).map_err(Into::into)
}

fn is_strict_bson_int(b: &bson::Bson) -> bool {
    matches!(b, bson::Bson::Int32(_) | bson::Bson::Int64(_))
}

pub fn check_bson_question_integers_and_oid(
    doc: &bson::Document,
) -> Result<bson::oid::ObjectId, ContractError> {
    let Some(bson::Bson::ObjectId(oid)) = doc.get("_id") else {
        return Err(bad_code(
            "INVALID_OBJECT_ID",
            Some("_id"),
            "Campo _id no MongoDB deve ser ObjectId nativo BSON",
        ));
    };
    for top in ["schema_version", "revision"] {
        if let Some(v) = doc.get(top) {
            if !is_strict_bson_int(v) {
                return Err(bad_code(
                    "INVALID_INTEGER",
                    Some(top),
                    format!("Campo BSON {top} deve ser Int32/Int64, nunca Double/Bool/String"),
                ));
            }
        }
    }
    if let Some(v) = doc.get("exam_year") {
        if !matches!(v, bson::Bson::Null) && !is_strict_bson_int(v) {
            return Err(bad_code(
                "INVALID_INTEGER",
                Some("exam_year"),
                "Campo BSON exam_year deve ser Int32/Int64",
            ));
        }
    }
    if let Some(bson::Bson::Array(arr)) = doc.get("correct_answer_indices") {
        for (idx, item) in arr.iter().enumerate() {
            if !is_strict_bson_int(item) {
                let path = format!("correct_answer_indices.{idx}");
                return Err(bad_code(
                    "INVALID_INTEGER",
                    Some(&path),
                    format!("Índice BSON em {path} deve ser Int32/Int64"),
                ));
            }
        }
    }
    if let Some(bson::Bson::Array(origins)) = doc.get("origins") {
        for (idx, item) in origins.iter().enumerate() {
            if let bson::Bson::Document(origin_doc) = item {
                if let Some(oy) = origin_doc.get("exam_year") {
                    if !matches!(oy, bson::Bson::Null) && !is_strict_bson_int(oy) {
                        let path = format!("origins.{idx}.exam_year");
                        return Err(bad_code(
                            "INVALID_INTEGER",
                            Some(&path),
                            format!("Campo BSON {path} deve ser Int32/Int64"),
                        ));
                    }
                }
            }
        }
    }
    if let Some(bson::Bson::Document(cls)) = doc.get("classification") {
        for sub in ["revision", "catalog_version"] {
            if let Some(v) = cls.get(sub) {
                if !is_strict_bson_int(v) {
                    let path = format!("classification.{sub}");
                    return Err(bad_code(
                        "INVALID_INTEGER",
                        Some(&path),
                        format!("Campo BSON {path} deve ser Int32/Int64"),
                    ));
                }
            }
        }
    }
    if let Some(bson::Bson::Document(val)) = doc.get("validity") {
        if let Some(v) = val.get("revision") {
            if !is_strict_bson_int(v) {
                return Err(bad_code(
                    "INVALID_INTEGER",
                    Some("validity.revision"),
                    "Campo BSON validity.revision deve ser Int32/Int64",
                ));
            }
        }
    }
    if let Some(bson::Bson::Document(grad)) = doc.get("grading") {
        if let Some(bson::Bson::Document(rubric)) = grad.get("rubric") {
            if let Some(v) = rubric.get("revision") {
                if !matches!(v, bson::Bson::Null) && !is_strict_bson_int(v) {
                    return Err(bad_code(
                        "INVALID_INTEGER",
                        Some("grading.rubric.revision"),
                        "Campo BSON grading.rubric.revision deve ser Int32/Int64",
                    ));
                }
            }
        }
    }
    Ok(*oid)
}

pub fn contract_to_bson_question(q: &Value) -> Result<bson::Document, AppError> {
    let oid_hex = validate_contract_oid(&q["_id"], "_id")?;
    let oid = bson::oid::ObjectId::parse_str(&oid_hex).map_err(|_| {
        AppError::bad_request_code("INVALID_OBJECT_ID", Some("_id"), "ObjectId inválido")
    })?;
    let mut doc = bson::to_document(q).map_err(|_| {
        AppError::bad_request_code(
            "SCHEMA_VALIDATION_FAILED",
            Some("question"),
            "Questão não conversível para BSON",
        )
    })?;
    doc.insert("_id", oid);
    check_bson_question_integers_and_oid(&doc)?;
    Ok(doc)
}

pub fn bson_to_contract_question(doc: &bson::Document) -> Result<Value, AppError> {
    let oid = check_bson_question_integers_and_oid(doc)?;
    let mut cleaned = doc.clone();
    cleaned.remove("_id");
    cleaned.remove("schema_outbox");
    let mut value = bson::Bson::Document(cleaned).into_relaxed_extjson();
    if let Some(obj) = value.as_object_mut() {
        obj.insert("_id".into(), json!({ "$oid": oid.to_hex() }));
    }
    Ok(value)
}

pub fn validate_bson_question(doc: &bson::Document) -> Result<Value, AppError> {
    let projected = bson_to_contract_question(doc)?;
    validate(&projected)?;
    Ok(projected)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn python_fixtures_match_rust_jcs_and_validation() {
        for file in [
            "question-multiple-choice.json",
            "question-true-false.json",
            "question-open-manual.json",
            "question-open-ai.json",
            "question-open-semantic.json",
            "question-obsolete.json",
            "question-unclassified.json",
        ] {
            let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../shared/examples/question-contracts")
                .join(file);
            let mut q: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
            validate(&q).unwrap();
            q["question"] = json!("Alterado");
            assert!(validate(&q).is_err());
        }
    }

    #[test]
    fn shared_parity_vectors_v2_1_match_python_byte_for_byte() {
        let bundle: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/parity-vectors-v2-1.json"
        ))
        .unwrap();
        let cat = catalog();
        let vectors = bundle["validation_vectors"].as_array().unwrap();
        assert!(vectors.len() >= 20);

        for vec in vectors {
            let id = vec["id"].as_str().unwrap();
            let doc = &vec["document"];
            let untrusted = vec["untrusted_input"].as_bool().unwrap_or(false);
            let res = validate_with_catalog(doc, Some(&cat), !untrusted);

            if vec["valid"].as_bool().unwrap() {
                assert!(res.is_ok(), "vector {id} failed: {:?}", res.err());
                let exp = &vec["expected_hashes"];
                assert_eq!(
                    hash(doc).unwrap(),
                    exp["content_revision"].as_str().unwrap(),
                    "content_revision mismatch in {id}"
                );
                assert_eq!(
                    semantic_hash(doc).unwrap(),
                    exp["semantic_revision"].as_str().unwrap(),
                    "semantic_revision mismatch in {id}"
                );
                assert_eq!(
                    grading_hash(doc).unwrap(),
                    exp["grading_revision"].as_str().unwrap(),
                    "grading_revision mismatch in {id}"
                );
            } else {
                let err = res.expect_err(&format!("vector {id} unexpectedly passed"));
                assert_eq!(
                    err.code,
                    vec["expected_code"].as_str().unwrap(),
                    "code mismatch in {id}: {err:?}"
                );
                assert_eq!(
                    err.field.as_deref(),
                    vec["expected_field"].as_str(),
                    "field mismatch in {id}: {err:?}"
                );
            }
        }
    }

    #[test]
    fn strict_objectid_and_bson_double_integer_boundaries_t19_t20() {
        let mut base_q: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        populate_v2_1_hashes(&mut base_q).unwrap();

        // T19: API -> Contract Extended JSON -> Native BSON ObjectId -> Contract -> API roundtrip
        let oid = validate_api_question_id("000000000000000000000001", "question_id").unwrap();
        assert_eq!(oid.to_hex(), "000000000000000000000001");
        assert_eq!(
            validate_api_question_id("00000000000000000000000A", "question_id")
                .unwrap_err()
                .code,
            "INVALID_OBJECT_ID"
        );

        let bson_doc = contract_to_bson_question(&base_q).unwrap();
        assert!(matches!(bson_doc.get("_id"), Some(bson::Bson::ObjectId(_))));
        let roundtrip = validate_bson_question(&bson_doc).unwrap();
        assert_eq!(roundtrip["_id"]["$oid"], "000000000000000000000001");

        // Reject Extended JSON stored literally inside MongoDB BSON _id
        let mut extended_in_bson = bson_doc.clone();
        extended_in_bson.insert("_id", bson::doc! { "$oid": "000000000000000000000001" });
        assert_eq!(
            validate_bson_question(&extended_in_bson)
                .unwrap_err()
                .error_code(),
            "INVALID_OBJECT_ID"
        );

        // T20: Reject BSON Double(1.0) on integer fields even when mathematically integral
        for (field, mutator) in [
            (
                "revision",
                Box::new(|d: &mut bson::Document| {
                    d.insert("revision", bson::Bson::Double(1.0));
                }) as Box<dyn FnOnce(&mut bson::Document)>,
            ),
            (
                "schema_version",
                Box::new(|d: &mut bson::Document| {
                    d.insert("schema_version", bson::Bson::Double(2.0));
                }),
            ),
            (
                "correct_answer_indices.0",
                Box::new(|d: &mut bson::Document| {
                    d.insert(
                        "correct_answer_indices",
                        bson::Bson::Array(vec![bson::Bson::Double(1.0)]),
                    );
                }),
            ),
            (
                "classification.revision",
                Box::new(|d: &mut bson::Document| {
                    d.get_document_mut("classification")
                        .unwrap()
                        .insert("revision", bson::Bson::Double(1.0));
                }),
            ),
            (
                "validity.revision",
                Box::new(|d: &mut bson::Document| {
                    d.get_document_mut("validity")
                        .unwrap()
                        .insert("revision", bson::Bson::Double(1.0));
                }),
            ),
        ] {
            let mut bad_bson = bson_doc.clone();
            mutator(&mut bad_bson);
            let err = validate_bson_question(&bad_bson).unwrap_err();
            assert_eq!(err.error_code(), "INVALID_INTEGER", "{field}");
            assert_eq!(err.error_field(), Some(field), "{field}");
        }

        // Also reject JSON lexical 1.0, "1", true on integer fields
        for raw_json in [
            r#"{"revision": 1.0}"#,
            r#"{"revision": "1"}"#,
            r#"{"revision": true}"#,
        ] {
            let patch: Value = serde_json::from_str(raw_json).unwrap();
            let mut candidate = base_q.clone();
            candidate["revision"] = patch["revision"].clone();
            let err = validate_detailed(&candidate, true).unwrap_err();
            assert_eq!(err.code, "INVALID_INTEGER");
            assert_eq!(err.field.as_deref(), Some("revision"));
        }
    }

    #[test]
    fn rejects_dangerous_uri_schemes_out_of_range_years_oversized_concursos_and_bson_i64_truncation()
    {
        validate_http_uri("https://www.planalto.gov.br/ccivil_03/constituicao/constituicao.htm")
            .unwrap();
        validate_http_uri("HTTP://example.org/edital.pdf").unwrap();
        validate_http_uri("HTTPS://example.org/prova.pdf").unwrap();
        validate_http_uri("urn:cogniva:admin-review:672f8b2c4e1a2b3c4d5e6f70").unwrap();
        validate_http_uri("file:///C:/fixtures/959_BCB_CB1_01.pdf").unwrap();
        for bad_uri in [
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "file:///C:/Users/../secret.pdf",
            "ftp://example.org/file.pdf",
            "urn:evil:payload",
            "urn:cogniva:",
            "https://",
        ] {
            assert!(validate_http_uri(bad_uri).is_err(), "{bad_uri}");
        }

        let base_q: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();

        let mut with_urn_ev = base_q.clone();
        with_urn_ev["origins"][0]["provenance"]["evidence"] = json!([{
            "uri": "urn:cogniva:admin-review:672f8b2c4e1a2b3c4d5e6f70",
            "title": "Revisão administrativa explícita no painel Cogniva",
            "locator": null,
            "retrieved_at": "2026-11-01T00:00:00Z",
            "sha256": null
        }]);
        validate(&with_urn_ev).unwrap();

        let mut with_bad_source = base_q.clone();
        with_bad_source["origins"][0]["source_reference"] = json!({
            "uri": "javascript:alert(1)",
            "title": "Fonte maliciosa",
            "locator": null,
            "retrieved_at": "2026-11-01T00:00:00Z",
            "sha256": null
        });
        assert!(validate(&with_bad_source).is_err());

        let mut with_bad_ev = base_q.clone();
        with_bad_ev["origins"][0]["provenance"]["evidence"] = json!([{
            "uri": "data:text/plain;base64,SGVsbG8=",
            "title": "Evidência inválida",
            "locator": null,
            "retrieved_at": "2026-11-01T00:00:00Z",
            "sha256": null
        }]);
        assert!(validate(&with_bad_ev).is_err());

        for invalid_year in [json!(1899), json!(2201), json!(i64::MAX)] {
            let mut bad_year = base_q.clone();
            bad_year["exam_year"] = invalid_year;
            assert!(validate(&bad_year).is_err());
        }
        let mut valid_year = base_q.clone();
        valid_year["exam_year"] = json!(2025);
        validate(&valid_year).unwrap();

        let mut ok_concursos = base_q.clone();
        ok_concursos["concursos"] = json!((0..50).map(|i| format!("C{i}")).collect::<Vec<_>>());
        validate(&ok_concursos).unwrap();

        let mut too_many_concursos = base_q.clone();
        too_many_concursos["concursos"] =
            json!((0..51).map(|i| format!("C{i}")).collect::<Vec<_>>());
        assert!(validate(&too_many_concursos).is_err());

        let mut float_rev = base_q.clone();
        float_rev["revision"] = json!(1.0);
        assert!(validate(&float_rev).is_err());

        let mut sub5_mc = base_q;
        sub5_mc["answers"] = json!(["3", "4", "5", "6"]);
        let new_hash = hash(&sub5_mc).unwrap();
        sub5_mc["content_revision"] = json!(new_hash);
        sub5_mc["validity"]["evaluated_content_revision"] = json!(new_hash);
        assert!(
            validate(&sub5_mc).is_err(),
            "4-option MULTIPLE_CHOICE must be rejected"
        );

        assert_eq!(
            checked_bson_i32(&bson::Bson::Int64(2026), "exam_year").unwrap(),
            2026
        );
        assert!(
            checked_bson_i32(&bson::Bson::Int64(i64::from(i32::MAX) + 1), "exam_year").is_err()
        );
    }
}
