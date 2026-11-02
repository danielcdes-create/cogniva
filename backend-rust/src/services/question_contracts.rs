//! Authoritative v2 write validation, shared with the versioned JSON contracts.
use crate::error::AppError;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{collections::HashSet, sync::OnceLock};

pub fn catalog_ref() -> &'static Value {
    static C: OnceLock<Value> = OnceLock::new();
    C.get_or_init(|| {
        serde_json::from_str(include_str!("../../../shared/knowledge-catalog.json"))
            .expect("catalog")
    })
}
pub fn catalog() -> Value {
    catalog_ref().clone()
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
        return check(
            QUESTION
                .get_or_init(|| {
                    jsonschema::options()
                        .should_validate_formats(true)
                        .build(&schema("question"))
                        .expect("bundled schema")
                })
                .is_valid(value),
            "Documento não atende ao contrato de schema",
        );
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
pub fn known_topic(subject: &str, topic: &str) -> bool {
    catalog_ref()["topics"]
        .as_array()
        .unwrap()
        .iter()
        .any(|t| t["subject_id"] == subject && t["topic_id"] == topic && t["status"] == "ACTIVE")
}
pub fn validate(q: &Value) -> Result<(), AppError> {
    shape("question", q)?;
    check(
        q["schema_version"].as_u64() == Some(2) && q["revision"].as_u64().is_some(),
        "Revisões devem ser inteiros BSON/JSON",
    )?;
    check(
        q["classification"]["revision"].as_u64().is_some()
            && q["validity"]["revision"].as_u64().is_some(),
        "Revisões de bloco devem ser inteiras",
    )?;
    check(
        q["content_revision"].as_str() == Some(hash(q)?.as_str()),
        "Hash de conteúdo divergente",
    )?;
    check(
        !q["question"].as_str().unwrap().trim().is_empty(),
        "Enunciado vazio",
    )?;
    let answers = q["answers"].as_array().unwrap();
    let mut seen = HashSet::new();
    for a in answers {
        let text = a.as_str().unwrap().trim();
        check(
            !text.is_empty() && seen.insert(text),
            "Alternativas vazias ou repetidas",
        )?;
    }
    if q["question_type"] != "OPEN_ENDED" {
        let index = q["correct_answer_indices"][0]
            .as_u64()
            .ok_or_else(|| bad("Índice deve ser inteiro"))? as usize;
        check(
            q["correct_answers"][0] == answers[index],
            "Gabaritos textual e indexado divergem",
        )?;
    }
    for origin in q["origins"].as_array().unwrap() {
        if origin["kind"] == "OFFICIAL_EXAM" && origin["verification"] == "CONFIRMED" {
            check(
                human(&origin["provenance"]),
                "Origem oficial confirmada exige revisão humana",
            )?;
        }
    }
    let c = &q["classification"];
    check(
        c["catalog_version"] == catalog_ref()["catalog_version"],
        "Versão de catálogo desconhecida",
    )?;
    let mut primary = None;
    let mut topics = HashSet::new();
    let mut ids = HashSet::new();
    for l in c["links"].as_array().unwrap() {
        check(
            known_topic(
                l["subject_id"].as_str().unwrap(),
                l["topic_id"].as_str().unwrap(),
            ),
            "Vínculo fora do catálogo",
        )?;
        check(
            topics.insert(l["topic_id"].as_str().unwrap())
                && ids.insert(l["link_id"].as_str().unwrap()),
            "Vínculo repetido",
        )?;
        check(
            human(&l["provenance"]),
            "Classificação exige revisão humana",
        )?;
        check(
            c["revision"].as_u64().unwrap() > 0,
            "Classificação sem revisão",
        )?;
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
            v["revision"].as_u64().unwrap() > 0 && human(&v["decision"]),
            "Validade exige decisão revisada",
        )?;
        check(
            v["evaluated_content_revision"] == q["content_revision"],
            "Decisão de validade desatualizada",
        )?;
        check(
            v["basis"] == "TIMELESS" || !v["decision"]["evidence"].as_array().unwrap().is_empty(),
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
    for r in v["reason_codes"].as_array().unwrap() {
        check(
            !(r == "LAW_REPEALED" || r == "LAW_AMENDED") || v["basis"] == "LEGAL",
            "Motivo normativo incompatível",
        )?;
    }
    let g = &q["grading"];
    let max = g["max_score"].as_f64().unwrap();
    check(
        g["pass_score"].as_f64().unwrap() <= max,
        "Corte acima da nota máxima",
    )?;
    if q["question_type"] == "OPEN_ENDED" {
        check(
            g["rubric"]["revision"].as_u64().is_some(),
            "Revisão de rubrica deve ser inteira",
        )?;
        let mut criteria = HashSet::new();
        let mut total = 0.0;
        for criterion in g["rubric"]["criteria"].as_array().unwrap() {
            check(
                criteria.insert(criterion["criterion_id"].as_str().unwrap()),
                "Critério repetido",
            )?;
            total += criterion["max_points"].as_f64().unwrap();
        }
        check((total - max).abs() < 1e-9, "Rubrica não soma a nota máxima")?;
        check(
            g["expected_responses"]
                .as_array()
                .unwrap()
                .iter()
                .all(|r| !r.as_str().unwrap().trim().is_empty()),
            "Referência de resposta vazia",
        )?;
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
}
