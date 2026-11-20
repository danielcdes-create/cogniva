use crate::error::AppError;
use bson::oid::ObjectId;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::sync::OnceLock;

pub const VALID_PROBLEM_LIST: &[&str] = &[
    "Questão com Erros de Conteúdo",
    "Questão com Formatação Ruim",
    "Questão Mal Formulada",
    "Questão com Erros de Gramática ou Ortografia",
    "Enunciado Mal Formulado",
    "Enunciado c/ Erros de Conteúdo",
    "Enunciado c/ Formatação Ruim",
    "Enunciado c/ Erros de Gramática ou Ortografia",
    "Alternativas Mal Formuladas",
    "Alternativas Corretas em Duplicidade",
    "Alternativas Muito Fáceis",
    "Alternativas c/ Erros de Conteúdo",
    "Alternativas c/ Formatação Ruim",
    "Alternativas c/ Erros de Gramática ou Ortografia",
    "Explicação c/ Formatação Ruim",
    "Explicação Incompleta",
    "Explicação Divergente c/ Alternativa Correta",
    "Explicação c/ Erros de Conteúdo",
    "Explicação c/ Erros de Gramática ou Ortografia",
    "Outro Problema com a Questão",
];

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlausibilityLevelItem {
    #[serde(default)]
    pub plausibilidade: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IncorrectAnswerAnalysis {
    #[serde(default)]
    pub alternative: String,
    #[serde(default)]
    pub explanation: String,
    #[serde(default)]
    pub plausibility: Vec<PlausibilityLevelItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlausibilityAnalysis {
    #[serde(default)]
    pub correct_answer_explanation: String,
    #[serde(default)]
    pub incorrect_answers_analysis: Vec<IncorrectAnswerAnalysis>,
    #[serde(default)]
    pub conclusion: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvidenceOptionDto {
    pub evidence_id: String,
    pub text: String,
    #[serde(default)]
    pub source_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EvidenceChallengeDto {
    pub prompt: String,
    pub options: Vec<EvidenceOptionDto>,
    pub correct_evidence_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConceptualErrorItemDto {
    pub error_id: String,
    pub label: String,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EditorialCardDto {
    #[serde(default)]
    pub legal_provisions: Vec<String>,
    pub tested_rule: String,
    #[serde(default)]
    pub exception_clause: Option<String>,
    pub decisive_stem_excerpt: String,
    #[serde(default)]
    pub official_source_excerpt: Option<String>,
    #[serde(default)]
    pub plausible_conceptual_errors: Vec<ConceptualErrorItemDto>,
}

/// Documento da coleção `todasQuestoes` (Cogniva V2 — revisão única JCS SHA-256).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuestionDocument {
    #[serde(rename = "_id")]
    pub id: ObjectId,
    pub question: String,
    #[serde(default)]
    pub question_type: Option<String>,
    #[serde(default)]
    pub answers: Vec<String>,
    #[serde(default)]
    pub correct_answers: Vec<String>,
    #[serde(default)]
    pub correct_answer_indices: Vec<usize>,
    #[serde(default)]
    pub content_revision: String,
    #[serde(default)]
    pub grading: Option<Value>,
    #[serde(default)]
    pub classification: Option<Value>,
    #[serde(default)]
    pub disciplina: String,
    #[serde(default)]
    pub source: Option<String>,
    #[serde(default)]
    pub level: Option<String>,
    #[serde(default)]
    pub exam_year: Option<i32>,

    #[serde(default)]
    pub concursos: Vec<String>,
    #[serde(default)]
    pub syllabus_topic_ids: Vec<String>,
    #[serde(default)]
    pub canonical_subject_id: Option<String>,
    #[serde(default)]
    pub canonical_topic_id: Option<String>,
    #[serde(default)]
    pub knowledge_binding_verified: bool,
    #[serde(default)]
    pub validity: Option<Value>,

    // Campos de Tutoria IA cacheados
    #[serde(default)]
    pub explanation: Option<String>,
    #[serde(rename = "explanationExtra", default)]
    pub explanation_extra: Option<String>,
    #[serde(rename = "drSincero", default)]
    pub dr_sincero: Option<String>,
    #[serde(rename = "ajudaInfantil", default)]
    pub ajuda_infantil: Option<String>,
    #[serde(rename = "explicacaoInfantil", default)]
    pub explicacao_infantil: Option<String>,
    #[serde(default)]
    pub mnemonico: Option<String>,
    #[serde(rename = "contextualHint", default)]
    pub contextual_hint: Vec<String>,
    #[serde(rename = "plausibilityMarkdown", default)]
    pub plausibility_markdown: Option<String>,
    #[serde(rename = "plausibilityAnalysis", default)]
    pub plausibility_analysis: Option<PlausibilityAnalysis>,
    #[serde(default)]
    pub evidence_challenge: Option<EvidenceChallengeDto>,
    #[serde(default)]
    pub editorial_card: Option<EditorialCardDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuestionDto {
    pub id: String,
    pub question_revision: String,
    pub question_type: String,
    pub question: String,
    pub answers: Vec<String>,
    pub correct_answer_indices: Vec<usize>,
    pub disciplina: String,
    pub source: String,
    pub level: String,
    pub exam_year: Option<i32>,
    #[serde(default)]
    pub concursos: Vec<String>,
    #[serde(default)]
    pub syllabus_topic_ids: Vec<String>,
    #[serde(default)]
    pub common_trunk_id: Option<String>,
    pub canonical_subject_id: Option<String>,
    pub canonical_topic_id: Option<String>,
    #[serde(default)]
    pub is_borrowed_analogous: bool,
    pub explanation: Option<String>,
    pub has_dr_sincero: bool,
    pub has_mnemonic: bool,
    pub has_plausibility: bool,
    pub hints_count: usize,
    pub has_cached_explanation: bool,
    pub cached_hints_count: usize,
    pub knowledge_projection_version: i32,
    pub practice_review_due_on: Option<String>,
    pub knowledge_binding_verified: bool,
    pub user_stats: Option<UserQuestionBriefStats>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub exam_component_id: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub concept_ids: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub evidence_challenge: Option<EvidenceChallengeDto>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub editorial_card: Option<EditorialCardDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserQuestionBriefStats {
    pub attempts: i32,
    pub accuracy_pct: f64,
    pub last_session_iso: Option<String>,
    pub is_mastered: bool,
    pub in_error_notebook: bool,
}

static LEGAL_PROVISION_RE: OnceLock<Regex> = OnceLock::new();
static EXCEPTION_MARKER_RE: OnceLock<Regex> = OnceLock::new();

fn truncate_chars(text: &str, max_chars: usize) -> String {
    let trimmed = text.trim();
    if trimmed.chars().count() <= max_chars {
        trimmed.to_string()
    } else {
        let prefix: String = trimmed.chars().take(max_chars.saturating_sub(1)).collect();
        format!("{}…", prefix.trim_end())
    }
}

fn extract_legal_provisions(text: &str) -> Vec<String> {
    let re = LEGAL_PROVISION_RE.get_or_init(|| {
        Regex::new(
            r"(?i)\b(?:art(?:igo)?\.?\s*\d+[º°]?(?:\s*,\s*(?:§\s*\d+[º°]?|parágrafo\s+único|inciso\s+[IVXLCDM]+|[a-z]\)))?|lei(?:\s+complementar|\s+n[º°]?)?\s*[\d\.]+(?:/\d{2,4})?|cf(?:/88)?|súmula(?:\s+vinculante)?\s*(?:n[º°]?\s*)?\d+|decreto(?:\s+n[º°]?)?\s*[\d\.]+(?:/\d{2,4})?|resolu[çc][ãa]o\s+(?:cmn|bcb|cvm)?\s*n?[º°]?\s*[\d\.]+)",
        )
        .unwrap()
    });
    let mut found = Vec::new();
    for m in re.find_iter(text) {
        let item = m.as_str().trim();
        if item.len() >= 3
            && !found
                .iter()
                .any(|existing: &String| existing.eq_ignore_ascii_case(item))
        {
            found.push(truncate_chars(item, 120));
            if found.len() >= 5 {
                break;
            }
        }
    }
    found
}

fn extract_exception_sentence(text: &str) -> Option<String> {
    let re = EXCEPTION_MARKER_RE.get_or_init(|| {
        Regex::new(r"(?i)\b(salvo|exceto|ressalvad[oa]s?|vedad[oa]s?|independentemente|apenas|exclusivamente|somente|sem\s+preju[íi]zo)\b")
            .unwrap()
    });
    for sentence in text.split(['.', ';', '\n']) {
        let s = sentence.trim();
        if s.len() >= 18 && re.is_match(s) {
            return Some(truncate_chars(s, 260));
        }
    }
    None
}

fn pick_decisive_stem_fragment(stem: &str) -> String {
    let clauses: Vec<&str> = stem
        .split(['.', ';', '?', '\n'])
        .map(str::trim)
        .filter(|s| s.chars().count() >= 16)
        .collect();
    if let Some(exc) = extract_exception_sentence(stem) {
        return exc;
    }
    if let Some(last) = clauses.last() {
        return truncate_chars(last, 240);
    }
    truncate_chars(stem, 240)
}

impl QuestionDocument {
    pub fn effective_question_type(&self) -> &str {
        if let Some(qt) = self.question_type.as_deref() {
            if !qt.trim().is_empty() {
                return qt;
            }
        }
        if self.answers.is_empty() {
            "OPEN_ENDED"
        } else if self.answers == ["Certo", "Errado"] {
            "TRUE_FALSE"
        } else {
            "MULTIPLE_CHOICE"
        }
    }

    pub fn is_open_ended(&self) -> bool {
        self.effective_question_type() == "OPEN_ENDED"
    }

    pub fn confirmed_concept_ids(&self) -> Vec<String> {
        let mut out = Vec::new();
        if let Some(cls) = &self.classification {
            if cls.get("state").and_then(Value::as_str) == Some("CONFIRMED") {
                if let Some(links) = cls.get("links").and_then(Value::as_array) {
                    for l in links {
                        if let Some(cid) = l.get("concept_id").and_then(Value::as_str) {
                            let trimmed = cid.trim();
                            if !trimmed.is_empty() && !out.iter().any(|x| x == trimmed) {
                                out.push(trimmed.to_string());
                            }
                        }
                    }
                }
            }
        }
        out
    }

    fn linked_catalog_sources(&self) -> Vec<(String, String, Option<String>, String)> {
        let cat = crate::services::question_contracts::catalog_ref();
        let concepts = crate::services::catalog_runtime::effective_concepts(&cat);
        let norm_sources = crate::services::catalog_runtime::effective_normative_sources(&cat);
        let q_concepts = self.confirmed_concept_ids();
        let mut wanted = std::collections::HashSet::new();
        for c in &concepts {
            let cid = c["concept_id"].as_str().unwrap_or("");
            let c_top = c["topic_id"].as_str();
            if q_concepts.iter().any(|qc| qc == cid)
                || (q_concepts.is_empty() && c_top == self.canonical_topic_id.as_deref())
            {
                if let Some(arr) = c["source_ids"].as_array() {
                    for sid in arr.iter().filter_map(Value::as_str) {
                        wanted.insert(sid.to_string());
                    }
                }
            }
        }
        norm_sources
            .into_iter()
            .filter(|ns| {
                ns["status"].as_str() == Some("ACTIVE")
                    && ns["source_id"].as_str().is_some_and(|id| wanted.contains(id))
            })
            .filter_map(|ns| {
                let sid = ns["source_id"].as_str()?.to_string();
                let title = ns["title"].as_str().unwrap_or("").to_string();
                let locator = ns["locator"].as_str().map(str::to_string);
                let excerpt = ns["excerpt"].as_str().unwrap_or("").to_string();
                if title.is_empty() && excerpt.is_empty() {
                    None
                } else {
                    Some((sid, title, locator, excerpt))
                }
            })
            .collect()
    }

    /// Retorna a ficha editorial curada ou sintetiza deterministicamente a partir do enunciado,
    /// das fontes normativas verificadas do catálogo e da fundamentação existente (zero alucinação).
    pub fn effective_editorial_card(&self) -> Option<EditorialCardDto> {
        if let Some(curated) = &self.editorial_card {
            return Some(curated.clone());
        }
        if self.is_open_ended() || self.question.trim().is_empty() {
            return None;
        }
        let combined_text = match self.explanation.as_deref() {
            Some(exp) if !exp.trim().is_empty() => format!("{}\n{}", self.question, exp),
            _ => self.question.clone(),
        };
        let mut provisions = extract_legal_provisions(&combined_text);
        let sources = self.linked_catalog_sources();
        for (_, title, locator, _) in &sources {
            let label = match locator.as_deref() {
                Some(loc) if !loc.is_empty() => format!("{title} ({loc})"),
                _ => title.clone(),
            };
            if !label.is_empty()
                && !provisions
                    .iter()
                    .any(|p| p.eq_ignore_ascii_case(label.as_str()))
            {
                provisions.push(truncate_chars(&label, 140));
            }
        }

        let official_source_excerpt = sources
            .first()
            .map(|(_, title, locator, excerpt)| {
                if let Some(loc) = locator.as_deref().filter(|l| !l.is_empty()) {
                    truncate_chars(&format!("{title} [{loc}]: {excerpt}"), 420)
                } else {
                    truncate_chars(&format!("{title}: {excerpt}"), 420)
                }
            });

        let tested_rule = if let Some(exp) = self.explanation.as_deref().filter(|s| !s.trim().is_empty()) {
            let clean = exp.replace("✅:", "").replace("❌:", "");
            let first_para = clean
                .split('\n')
                .map(str::trim)
                .find(|line| line.chars().count() >= 20)
                .unwrap_or(clean.trim());
            truncate_chars(first_para, 360)
        } else if let Some((_, _, _, excerpt)) = sources.first() {
            truncate_chars(excerpt, 360)
        } else if let Ok(indices) = self.answer_indices() {
            let gabarito = self
                .answers
                .get(indices[0])
                .map(String::as_str)
                .unwrap_or("Gabarito oficial");
            truncate_chars(
                &format!(
                    "Aplicação direta do critério cobrado em {} (gabarito oficial: {}).",
                    if self.disciplina.trim().is_empty() {
                        "disciplina do edital"
                    } else {
                        self.disciplina.trim()
                    },
                    gabarito
                ),
                320,
            )
        } else {
            return None;
        };

        let exception_clause = extract_exception_sentence(&combined_text);
        let decisive_stem_excerpt = pick_decisive_stem_fragment(&self.question);

        let mut plausible_errors = Vec::new();
        if let Some(pa) = &self.plausibility_analysis {
            for (idx, inc) in pa.incorrect_answers_analysis.iter().take(3).enumerate() {
                if !inc.explanation.trim().is_empty() {
                    plausible_errors.push(ConceptualErrorItemDto {
                        error_id: format!("ERR_DISTRATOR_{}", idx + 1),
                        label: if inc.alternative.trim().is_empty() {
                            format!("Distrator plausível #{}", idx + 1)
                        } else {
                            truncate_chars(&inc.alternative, 90)
                        },
                        description: truncate_chars(&inc.explanation, 260),
                    });
                }
            }
        }
        if plausible_errors.is_empty() {
            if exception_clause.is_some() {
                plausible_errors.push(ConceptualErrorItemDto {
                    error_id: "ERR_REGRA_EXCECAO".to_string(),
                    label: "Inversão entre regra geral e cláusula restritiva/exceção".to_string(),
                    description: "Desconsiderar o delimitador restritivo (salvo/exceto/apenas/vedado) presente no enunciado ou na norma.".to_string(),
                });
            } else if self.effective_question_type() == "TRUE_FALSE" {
                plausible_errors.push(ConceptualErrorItemDto {
                    error_id: "ERR_ESCOPO_ASSERTIVA".to_string(),
                    label: "Generalização ou restrição indevida da assertiva".to_string(),
                    description: "Aceitar extrapolação além do alcance literal do dispositivo ou conceito cobrado pela banca.".to_string(),
                });
            }
        }

        Some(EditorialCardDto {
            legal_provisions: provisions,
            tested_rule,
            exception_clause,
            decisive_stem_excerpt,
            official_source_excerpt,
            plausible_conceptual_errors: plausible_errors,
        })
    }

    /// Retorna o seletor opcional pós-resposta apontando exclusivamente para fragmentos do
    /// enunciado e da fonte oficial (sem formular outra questão jurídica).
    pub fn effective_evidence_challenge(&self) -> Option<EvidenceChallengeDto> {
        if let Some(curated) = &self.evidence_challenge {
            return Some(curated.clone());
        }
        if self.is_open_ended() {
            return None;
        }
        let card = self.effective_editorial_card()?;
        let sources = self.linked_catalog_sources();
        let primary_source_id = sources.first().map(|(sid, _, _, _)| sid.clone());

        let decisive_text = if let Some(src_exc) = &card.official_source_excerpt {
            truncate_chars(
                &format!(
                    "Trecho decisivo: \"{}\" · Fonte: {}",
                    card.decisive_stem_excerpt, src_exc
                ),
                320,
            )
        } else {
            truncate_chars(
                &format!(
                    "Trecho decisivo do enunciado e fundamento: \"{}\" — {}",
                    card.decisive_stem_excerpt, card.tested_rule
                ),
                320,
            )
        };

        let alt_misconception = if let Some(first_err) = card.plausible_conceptual_errors.first() {
            truncate_chars(
                &format!("Premissa divergente: {} — {}", first_err.label, first_err.description),
                280,
            )
        } else {
            "Leitura superficial de termos acessórios do enunciado, sem conferir o núcleo normativo/conceitual.".to_string()
        };

        Some(EvidenceChallengeDto {
            prompt: "Opcional — aponte qual fragmento determinou sua resposta (sem nova questão):".to_string(),
            options: vec![
                EvidenceOptionDto {
                    evidence_id: "EV_DECISIVE".to_string(),
                    text: decisive_text,
                    source_id: primary_source_id,
                },
                EvidenceOptionDto {
                    evidence_id: "EV_PREMISSA_DIVERGENTE".to_string(),
                    text: alt_misconception,
                    source_id: None,
                },
            ],
            correct_evidence_id: "EV_DECISIVE".to_string(),
        })
    }

    pub fn to_dto(&self, user_stats: Option<UserQuestionBriefStats>) -> QuestionDto {
        let correct_answer_indices = if self.is_open_ended() {
            Vec::new()
        } else {
            self.answer_indices().unwrap_or_default()
        };

        let has_cached_explanation = self
            .explanation
            .as_ref()
            .is_some_and(|s| !s.trim().is_empty());
        let hints_count = self
            .contextual_hint
            .iter()
            .filter(|s| !s.trim().is_empty())
            .count();

        QuestionDto {
            id: self.id.to_hex(),
            question_revision: self.revision().unwrap_or_default(),
            question_type: self.effective_question_type().to_string(),
            question: self.question.clone(),
            answers: self.answers.clone(),
            correct_answer_indices,
            disciplina: {
                let raw = self.disciplina.trim();
                let is_generic = raw.is_empty()
                    || raw.eq_ignore_ascii_case("Disciplina Geral")
                    || raw.eq_ignore_ascii_case("Geral");
                if is_generic {
                    self.canonical_subject_id
                        .as_deref()
                        .and_then(|sid| {
                            crate::services::catalog_runtime::snapshot().current["subjects"]
                                .as_array()
                                .and_then(|arr| {
                                    arr.iter()
                                        .find(|s| s["subject_id"].as_str() == Some(sid))
                                        .and_then(|s| s["label"].as_str())
                                        .map(str::to_string)
                                })
                        })
                        .unwrap_or_else(|| {
                            if raw.is_empty() {
                                "Disciplina Geral".to_string()
                            } else {
                                self.disciplina.clone()
                            }
                        })
                } else {
                    self.disciplina.clone()
                }
            },
            source: self
                .source
                .clone()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or_else(|| "Origem não informada".to_string()),
            level: self
                .level
                .clone()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or_else(|| "Médio".to_string()),
            exam_year: self.exam_year,
            concursos: self.concursos.clone(),
            syllabus_topic_ids: self.syllabus_topic_ids.clone(),
            common_trunk_id: None,
            canonical_subject_id: self.canonical_subject_id.clone(),
            canonical_topic_id: self.canonical_topic_id.clone(),
            is_borrowed_analogous: false,
            explanation: self.explanation.clone(),
            has_dr_sincero: self.dr_sincero.as_ref().is_some_and(|s| !s.trim().is_empty()),
            has_mnemonic: self.mnemonico.as_ref().is_some_and(|s| !s.trim().is_empty()),
            has_plausibility: self
                .plausibility_markdown
                .as_ref()
                .is_some_and(|s| !s.trim().is_empty())
                || self.plausibility_analysis.is_some(),
            hints_count,
            has_cached_explanation,
            cached_hints_count: hints_count,
            knowledge_projection_version: 1,
            practice_review_due_on: self
                .validity
                .as_ref()
                .and_then(|v| v["review_due_on"].as_str())
                .map(str::to_string),
            knowledge_binding_verified: self.knowledge_binding_verified,
            user_stats,
            exam_component_id: None,
            concept_ids: self.confirmed_concept_ids(),
            evidence_challenge: self.effective_evidence_challenge(),
            editorial_card: self.effective_editorial_card(),
        }
    }

    pub fn answer_indices(&self) -> Result<Vec<usize>, AppError> {
        if self.is_open_ended() {
            return Err(AppError::Conflict(
                "Questão discursiva faz parte do acervo preparatório e não entra em sessão objetiva"
                    .into(),
            ));
        }
        let mut from_text = Vec::new();
        for text in &self.correct_answers {
            let matches: Vec<usize> = self
                .answers
                .iter()
                .enumerate()
                .filter(|(_, a)| a.trim() == text.trim())
                .map(|(i, _)| i)
                .collect();
            if matches.len() != 1 {
                return Err(AppError::Conflict(
                    "Questão sem correspondência inequívoca da resposta correta".into(),
                ));
            }
            from_text.push(matches[0]);
        }
        let indices = if self.correct_answer_indices.is_empty() {
            from_text
        } else {
            if self.correct_answer_indices.iter().any(|&i| i >= self.answers.len()) {
                return Err(AppError::Conflict(
                    "Índice de resposta correta fora das alternativas".into(),
                ));
            }
            if !from_text.is_empty() && self.correct_answer_indices != from_text {
                return Err(AppError::Conflict(
                    "Gabarito por índice diverge do texto da resposta correta".into(),
                ));
            }
            self.correct_answer_indices.clone()
        };
        if indices.len() != 1 {
            return Err(AppError::Conflict(
                "Questão objetiva exige exatamente uma alternativa correta".into(),
            ));
        }
        Ok(indices)
    }

    /// Hash canônico único (JCS RFC 8785 + SHA-256) compartilhado entre Rust, Python, Web Admin e Android.
    pub fn revision(&self) -> Result<String, AppError> {
        let qtype = self.effective_question_type();
        let indices: Vec<usize> = if qtype == "OPEN_ENDED" {
            Vec::new()
        } else {
            self.answer_indices()?
        };
        let grading = match &self.grading {
            Some(g) if !g.is_null() => g.clone(),
            _ if qtype == "OPEN_ENDED" => {
                return Err(AppError::Conflict(
                    "Questão discursiva sem rubrica estruturada para cálculo de revisão".into(),
                ));
            }
            _ => json!({
                "strategy": "EXACT_OPTION",
                "max_score": 100,
                "pass_score": 60,
                "expected_responses": [],
                "rubric": null,
                "engine": null,
                "confirmation_policy": "AUTOMATIC_EXACT",
                "policy_version": "objective-exact/1"
            }),
        };
        let payload = json!({
            "question_type": qtype,
            "question": self.question,
            "answers": self.answers,
            "correct_answer_indices": indices,
            "grading": grading,
        });
        crate::services::question_contracts::hash(&payload)
    }
}

#[cfg(test)]
mod revision_tests {
    use super::*;
    use bson::doc;

    #[test]
    fn objective_and_open_ended_questions_compute_jcs_sha256_revision() {
        let q: QuestionDocument = bson::from_document(doc! {
            "_id": ObjectId::new(),
            "question": "Qual opção contém ação? 🧠",
            "answers": ["Não", "Ação", "Sem", "Outro", "Nenhum"],
            "correct_answers": ["Ação"],
            "disciplina": "Teste"
        })
        .unwrap();
        assert_eq!(
            q.revision().unwrap(),
            "c337eb8ca0129047d0be82f6c08b53d651b4f21d1304e5dd6a6f9bb9b0c6b510"
        );
        let dto = q.to_dto(None);
        assert!(dto.editorial_card.is_some());
        assert!(dto.evidence_challenge.is_some());

        let open_raw: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-open-manual.json"
        ))
        .unwrap();
        let open_doc: QuestionDocument = bson::from_document(
            bson::Bson::try_from(open_raw.clone())
                .unwrap()
                .as_document()
                .unwrap()
                .clone(),
        )
        .unwrap();
        let rev = open_doc.revision().unwrap();
        assert_eq!(rev.len(), 64);
        assert_eq!(rev, open_raw["content_revision"].as_str().unwrap());
        let dto = open_doc.to_dto(None);
        assert_eq!(dto.question_revision, rev);
        assert_eq!(dto.question_type, "OPEN_ENDED");
    }
}
