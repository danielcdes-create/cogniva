//! Authoritative v2 write validation, shared with the versioned JSON contracts.
use crate::error::AppError;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{collections::HashSet, sync::{Arc,OnceLock}};

pub fn catalog_ref() -> Arc<Value> {super::catalog_runtime::snapshot().current.clone()}
pub fn catalog() -> Value {
    (*catalog_ref()).clone()
}
fn bad(message: &str) -> AppError {
    AppError::BadRequest(message.into())
}
fn check(ok: bool, message: &str) -> Result<(), AppError> {
    if ok {
        Ok(())
    } else {
        Err(bad(message))
    }
}
pub fn schema(kind: &str) -> Value {
    let bundle: Value = serde_json::from_str(include_str!(
        "../../../shared/schemas/question-contracts.schema.json"
    ))
    .expect("contracts");
    json!({"$schema":bundle["$schema"],"$ref":format!("#/$defs/{kind}"),"$defs":bundle["$defs"]})
}
pub fn shape(kind: &str, value: &Value) -> Result<(), AppError> {
    static QUESTION: OnceLock<jsonschema::Validator> = OnceLock::new();
    if kind == "question" {
        let validator = QUESTION
                .get_or_init(|| {
                    jsonschema::options()
                        .should_validate_formats(true)
                        .build(&schema("question"))
                        .expect("bundled schema")
                });
        let issues: Vec<String> = validator.iter_errors(value).take(3).map(|error| {
            format!("{}: {}", error.instance_path, error.to_string().chars().take(180).collect::<String>())
        }).collect();
        return check(issues.is_empty(), &format!("Contrato da questão: {}", issues.join("; ")));
    }
    let validator = jsonschema::options()
        .should_validate_formats(true)
        .build(&schema(kind))
        .map_err(|_| AppError::Internal("Schema indisponível".into()))?;
    check(
        validator.is_valid(value),
        "Documento não atende ao contrato de schema",
    )
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
    let bytes =
        serde_json_canonicalizer::to_vec(&content).map_err(|_| bad("Conteúdo não canonizável"))?;
    Ok(format!("{:x}", Sha256::digest(bytes)))
}
fn human(p: &Value) -> bool {
    p["actor"]["type"] == "HUMAN" && p["method"] == "MANUAL_REVIEW"
}
pub const MIN_EXAM_YEAR: i32 = 1900;
pub const MAX_EXAM_YEAR: i32 = 2200;
pub const MAX_CONCURSOS_ITEMS: usize = 50;

pub fn checked_bson_i32(value: &bson::Bson, field: &str) -> Result<i32, AppError> {
    match value {
        bson::Bson::Int32(n) => Ok(*n),
        bson::Bson::Int64(n) => i32::try_from(*n)
            .map_err(|_| bad(&format!("Inteiro fora do intervalo de 32 bits em {field}"))),
        _ => Err(bad(&format!("Esperado inteiro BSON em {field}"))),
    }
}

pub fn validate_http_uri(uri: &str) -> Result<(), AppError> {
    let trimmed = uri.trim();
    if trimmed.is_empty()
        || trimmed.len() > 2048
        || trimmed.chars().any(char::is_control)
    {
        return Err(bad("URI de referência inválida ou acima de 2048 caracteres"));
    }
    if let Some(rest) = trimmed.strip_prefix("urn:cogniva:") {
        if !rest.is_empty()
            && rest
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b':' | b'-'))
        {
            return Ok(());
        }
        return Err(bad("URN de referência interna Cogniva malformada"));
    }
    let has_http_scheme = trimmed
        .get(..7)
        .is_some_and(|p| p.eq_ignore_ascii_case("http://"))
        || trimmed
            .get(..8)
            .is_some_and(|p| p.eq_ignore_ascii_case("https://"));
    if !has_http_scheme {
        return Err(bad(
            "URI deve utilizar esquema http://, https:// ou urn:cogniva:",
        ));
    }
    let parsed =
        reqwest::Url::parse(trimmed).map_err(|_| bad("URI de referência malformada"))?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.host_str().is_none_or(str::is_empty)
    {
        return Err(bad("URI deve conter host válido com esquema http:// ou https://"));
    }
    Ok(())
}

pub fn validate_exam_year_value(value: &Value, field: &str) -> Result<(), AppError> {
    if value.is_null() {
        return Ok(());
    }
    let raw = value
        .as_i64()
        .ok_or_else(|| bad(&format!("{field} deve ser inteiro")))?;
    let year = i32::try_from(raw)
        .map_err(|_| bad(&format!("{field} fora do intervalo permitido")))?;
    check(
        (MIN_EXAM_YEAR..=MAX_EXAM_YEAR).contains(&year),
        &format!("{field} deve estar entre {MIN_EXAM_YEAR} e {MAX_EXAM_YEAR}"),
    )
}

fn validate_evidence_uris(evidence: &Value) -> Result<(), AppError> {
    if let Some(items) = evidence.as_array() {
        for item in items {
            let uri = item["uri"]
                .as_str()
                .ok_or_else(|| bad("Evidência documental exige URI textual"))?;
            validate_http_uri(uri)?;
        }
    }
    Ok(())
}

pub fn known_topic(subject: &str, topic: &str) -> bool {
    let snapshot = super::catalog_runtime::snapshot();
    snapshot.references[&snapshot.version]
        .active
        .contains(&(subject.into(), topic.into()))
}

pub fn validate(q: &Value) -> Result<(), AppError> {
    // Explicit security & domain checks BEFORE jsonschema so case-insensitive schemes,
    // malicious URIs (`javascript:`, `data:`, `file:`), `exam_year`, `concursos`, and
    // float-integer pitfalls always return deterministic `pt-BR` validation messages.
    if let Some(year_val) = q.get("exam_year") {
        validate_exam_year_value(year_val, "exam_year")?;
    }
    if let Some(concursos_val) = q.get("concursos") {
        if !concursos_val.is_null() {
            match concursos_val {
                Value::String(s) => {
                    check(s.chars().count() <= 300, "Campo concursos excede 300 caracteres")?;
                }
                Value::Array(arr) => {
                    check(
                        arr.len() <= MAX_CONCURSOS_ITEMS,
                        "Lista concursos aceita no máximo 50 itens",
                    )?;
                    for entry in arr {
                        let s = entry
                            .as_str()
                            .ok_or_else(|| bad("Itens de concursos devem ser texto"))?;
                        check(s.chars().count() <= 300, "Item de concursos excede 300 caracteres")?;
                    }
                }
                _ => return Err(bad("Campo concursos inválido")),
            }
        }
    }
    if let Some(origins) = q["origins"].as_array() {
        for origin in origins {
            if let Some(origin_year) = origin.get("exam_year") {
                validate_exam_year_value(origin_year, "origins.exam_year")?;
            }
            if !origin["source_reference"].is_null() {
                if let Some(uri) = origin["source_reference"]["uri"].as_str() {
                    if !uri.trim().is_empty() {
                        validate_http_uri(uri)?;
                    }
                }
            }
            validate_evidence_uris(&origin["provenance"]["evidence"])?;
        }
    }
    if let Some(links) = q["classification"]["links"].as_array() {
        for link in links {
            validate_evidence_uris(&link["provenance"]["evidence"])?;
        }
    }
    if !q["validity"]["decision"].is_null() {
        validate_evidence_uris(&q["validity"]["decision"]["evidence"])?;
    }

    shape("question", q)?;
    check(
        q["schema_version"].as_u64() == Some(2) && q["revision"].as_u64().is_some(),
        "Revisões devem ser inteiros BSON/JSON",
    )?;
    let class_rev = q["classification"]["revision"]
        .as_u64()
        .ok_or_else(|| bad("Revisões de bloco devem ser inteiras"))?;
    let val_rev = q["validity"]["revision"]
        .as_u64()
        .ok_or_else(|| bad("Revisões de bloco devem ser inteiras"))?;
    check(
        q["content_revision"].as_str() == Some(hash(q)?.as_str()),
        "Hash de conteúdo divergente",
    )?;
    let question_text = q["question"]
        .as_str()
        .ok_or_else(|| bad("Enunciado inválido"))?;
    check(!question_text.trim().is_empty(), "Enunciado vazio")?;
    let answers = q["answers"]
        .as_array()
        .ok_or_else(|| bad("Alternativas devem ser uma lista"))?;
    let mut seen = HashSet::new();
    for a in answers {
        let text = a
            .as_str()
            .ok_or_else(|| bad("Alternativa deve ser texto"))?
            .trim();
        check(
            !text.is_empty() && seen.insert(text),
            "Alternativas vazias ou repetidas",
        )?;
    }
    if q["question_type"] != "OPEN_ENDED" {
        let index = usize::try_from(
            q["correct_answer_indices"][0]
                .as_u64()
                .ok_or_else(|| bad("Índice deve ser inteiro"))?,
        )
        .map_err(|_| bad("Índice fora dos limites"))?;
        check(
            index < answers.len() && q["correct_answers"][0] == answers[index],
            "Gabaritos textual e indexado divergem",
        )?;
    }
    let origins = q["origins"]
        .as_array()
        .ok_or_else(|| bad("Origens devem ser uma lista"))?;
    for origin in origins {
        if origin["kind"] == "OFFICIAL_EXAM" && origin["verification"] == "CONFIRMED" {
            check(
                human(&origin["provenance"]),
                "Origem oficial confirmada exige revisão humana",
            )?;
        }
    }
    let c = &q["classification"];
    let catalog_ver = c["catalog_version"]
        .as_i64()
        .filter(|&v| super::catalog_runtime::version_known(v))
        .ok_or_else(|| bad("Versão de catálogo desconhecida"))?;
    let mut primary = None;
    let mut topics = HashSet::new();
    let mut ids = HashSet::new();
    let links = c["links"]
        .as_array()
        .ok_or_else(|| bad("Vínculos de classificação inválidos"))?;
    for l in links {
        let subj = l["subject_id"]
            .as_str()
            .ok_or_else(|| bad("Disciplina inválida no vínculo"))?;
        let top = l["topic_id"]
            .as_str()
            .ok_or_else(|| bad("Tópico inválido no vínculo"))?;
        let link_id = l["link_id"]
            .as_str()
            .ok_or_else(|| bad("Identificador de vínculo inválido"))?;
        check(
            super::catalog_runtime::topic_at(catalog_ver, subj, top),
            "Vínculo fora do catálogo",
        )?;
        check(
            topics.insert(top) && ids.insert(link_id),
            "Vínculo repetido",
        )?;
        check(
            human(&l["provenance"]),
            "Classificação exige revisão humana",
        )?;
        check(class_rev > 0, "Classificação sem revisão")?;
        if l["role"] == "PRIMARY" {
            check(
                primary.is_none() && l["coverage"] == "DIRECT",
                "Vínculo principal ambíguo",
            )?;
            primary = Some(l);
        }
    }
    check(
        c["state"] != "CONFIRMED" || primary.is_some(),
        "Classificação confirmada sem principal",
    )?;
    for (old, field) in [
        ("canonical_subject_id", "subject_id"),
        ("canonical_topic_id", "topic_id"),
    ] {
        if !q[old].is_null() {
            check(
                primary.is_some_and(|p| p[field] == q[old]),
                "Espelho canônico divergente",
            )?;
        }
    }
    let v = &q["validity"];
    check(
        (v["scope"] == "CURRENT_KNOWLEDGE") == v["context_date"].is_null(),
        "Contexto histórico exige data",
    )?;
    if v["status"] == "CURRENT" || v["status"] == "OBSOLETE" {
        check(
            val_rev > 0 && human(&v["decision"]),
            "Validade exige decisão revisada",
        )?;
        check(
            v["evaluated_content_revision"] == q["content_revision"],
            "Decisão de validade desatualizada",
        )?;
        let evidence = v["decision"]["evidence"]
            .as_array()
            .ok_or_else(|| bad("Evidências de validade inválidas"))?;
        check(
            v["basis"] == "TIMELESS" || !evidence.is_empty(),
            "Validade temporal sem evidência",
        )?;
    }
    check(
        !(v["status"] == "CURRENT" && v["basis"] == "LEGAL" && v["review_due_on"].is_null()),
        "Validade normativa exige prazo",
    )?;
    if let (Some(due), Some(review)) = (v["review_due_on"].as_str(), v["reference_date"].as_str()) {
        check(due >= review, "Prazo anterior à revisão")?;
    }
    let reasons = v["reason_codes"]
        .as_array()
        .ok_or_else(|| bad("Códigos de razão inválidos"))?;
    for r in reasons {
        check(
            !(r == "LAW_REPEALED" || r == "LAW_AMENDED") || v["basis"] == "LEGAL",
            "Motivo normativo incompatível",
        )?;
    }
    let g = &q["grading"];
    let max = g["max_score"]
        .as_f64()
        .ok_or_else(|| bad("Nota máxima inválida"))?;
    let pass = g["pass_score"]
        .as_f64()
        .ok_or_else(|| bad("Nota de corte inválida"))?;
    check(pass <= max, "Corte acima da nota máxima")?;
    if q["question_type"] == "OPEN_ENDED" {
        check(
            g["rubric"]["revision"].as_u64().is_some(),
            "Revisão de rubrica deve ser inteira",
        )?;
        let mut criteria = HashSet::new();
        let mut total = 0.0;
        let criteria_arr = g["rubric"]["criteria"]
            .as_array()
            .ok_or_else(|| bad("Critérios da rubrica inválidos"))?;
        for criterion in criteria_arr {
            let cid = criterion["criterion_id"]
                .as_str()
                .ok_or_else(|| bad("Identificador de critério inválido"))?;
            check(criteria.insert(cid), "Critério repetido")?;
            total += criterion["max_points"]
                .as_f64()
                .ok_or_else(|| bad("Pontuação de critério inválida"))?;
        }
        check((total - max).abs() < 1e-9, "Rubrica não soma a nota máxima")?;
        let expected_resps = g["expected_responses"]
            .as_array()
            .ok_or_else(|| bad("Respostas de referência inválidas"))?;
        for r in expected_resps {
            let txt = r
                .as_str()
                .ok_or_else(|| bad("Resposta de referência inválida"))?;
            check(!txt.trim().is_empty(), "Referência de resposta vazia")?;
        }
        let e = &g["engine"];
        if g["strategy"] == "RUBRIC" {
            check(e.is_null(), "Correção manual com motor automático")?;
        }
        if g["strategy"] == "AI" {
            check(
                e["provider"] == "xai"
                    && ["model", "prompt_id", "prompt_version"]
                        .iter()
                        .all(|k| e[k].as_str().is_some_and(|s| !s.is_empty())),
                "Correção IA sem modelo/prompt",
            )?;
            check(
                [
                    "semantic_model_id",
                    "semantic_model_revision",
                    "calibration_id",
                ]
                .iter()
                .all(|k| e[k].is_null()),
                "Motores de correção misturados",
            )?;
        }
        if g["strategy"] == "SEMANTIC" {
            check(
                [
                    "semantic_model_id",
                    "semantic_model_revision",
                    "calibration_id",
                ]
                .iter()
                .all(|k| e[k].as_str().is_some_and(|s| !s.is_empty())),
                "Motor semântico sem versão/calibração",
            )?;
            check(
                ["provider", "model", "prompt_id", "prompt_version"]
                    .iter()
                    .all(|k| e[k].is_null()),
                "Motores de correção misturados",
            )?;
        }
    }
    Ok(())
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
    fn rejects_dangerous_uri_schemes_out_of_range_years_oversized_concursos_and_bson_i64_truncation() {
        validate_http_uri("https://www.planalto.gov.br/ccivil_03/constituicao/constituicao.htm")
            .unwrap();
        validate_http_uri("HTTP://example.org/edital.pdf").unwrap();
        validate_http_uri("HTTPS://example.org/prova.pdf").unwrap();
        validate_http_uri("urn:cogniva:admin-review:672f8b2c4e1a2b3c4d5e6f70").unwrap();
        for bad_uri in [
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
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

        // Internal Cogniva admin-review URN passes full JSON Schema + domain validation
        let mut with_urn_ev = base_q.clone();
        with_urn_ev["origins"][0]["provenance"]["evidence"] = json!([{
            "uri": "urn:cogniva:admin-review:672f8b2c4e1a2b3c4d5e6f70",
            "title": "Revisão administrativa explícita no painel Cogniva",
            "locator": null,
            "retrieved_at": "2026-11-01T00:00:00Z",
            "sha256": null
        }]);
        validate(&with_urn_ev).unwrap();

        // source_reference.uri dangerous scheme rejected
        let mut with_bad_source = base_q.clone();
        with_bad_source["origins"][0]["source_reference"] = json!({
            "uri": "javascript:alert(1)",
            "title": "Fonte maliciosa",
            "locator": null,
            "retrieved_at": "2026-11-01T00:00:00Z",
            "sha256": null
        });
        assert!(validate(&with_bad_source).is_err());

        // evidence[*].uri dangerous scheme rejected
        let mut with_bad_ev = base_q.clone();
        with_bad_ev["origins"][0]["provenance"]["evidence"] = json!([{
            "uri": "data:text/plain;base64,SGVsbG8=",
            "title": "Evidência inválida",
            "locator": null,
            "retrieved_at": "2026-11-01T00:00:00Z",
            "sha256": null
        }]);
        assert!(validate(&with_bad_ev).is_err());

        // exam_year bounds 1900..=2200
        for invalid_year in [json!(1899), json!(2201), json!(i64::MAX)] {
            let mut bad_year = base_q.clone();
            bad_year["exam_year"] = invalid_year;
            assert!(validate(&bad_year).is_err());
        }
        let mut valid_year = base_q.clone();
        valid_year["exam_year"] = json!(2025);
        validate(&valid_year).unwrap();

        // concursos cap <= 50
        let mut ok_concursos = base_q.clone();
        ok_concursos["concursos"] = json!((0..50).map(|i| format!("C{i}")).collect::<Vec<_>>());
        validate(&ok_concursos).unwrap();

        let mut too_many_concursos = base_q.clone();
        too_many_concursos["concursos"] =
            json!((0..51).map(|i| format!("C{i}")).collect::<Vec<_>>());
        assert!(validate(&too_many_concursos).is_err());

        // float integer revision returns Err instead of panicking
        let mut float_rev = base_q;
        float_rev["revision"] = json!(1.0);
        assert!(validate(&float_rev).is_err());

        // Checked BSON integer parsing rejects 64-bit overflow instead of truncating
        assert_eq!(checked_bson_i32(&bson::Bson::Int64(2026), "exam_year").unwrap(), 2026);
        assert!(checked_bson_i32(&bson::Bson::Int64(i64::from(i32::MAX) + 1), "exam_year").is_err());
    }
}
