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
static GENUINE_EXCEPTION_RE: OnceLock<Regex> = OnceLock::new();
static STEM_DELIMITER_RE: OnceLock<Regex> = OnceLock::new();
static LEADING_VERDICT_RE: OnceLock<Regex> = OnceLock::new();
static TRAILING_REFERENCES_RE: OnceLock<Regex> = OnceLock::new();

fn truncate_chars(text: &str, max_chars: usize) -> String {
    let trimmed = text.trim();
    if trimmed.chars().count() <= max_chars {
        return trimmed.to_string();
    }
    let limit = max_chars.saturating_sub(1);
    let prefix: String = trimmed.chars().take(limit).collect();
    let min_word_cut = (limit * 60) / 100;
    let clean_prefix = match prefix
        .char_indices()
        .rev()
        .find(|(_, ch)| ch.is_whitespace())
    {
        Some((byte_idx, _)) if prefix[..byte_idx].chars().count() >= min_word_cut => {
            &prefix[..byte_idx]
        }
        _ => prefix.as_str(),
    };
    let trimmed_end = clean_prefix
        .trim_end()
        .trim_end_matches([',', ';', ':', '-', '–', '—']);
    format!("{}…", trimmed_end.trim_end())
}

pub(crate) fn strip_verdict_and_references(raw: &str) -> String {
    let verdict_re = LEADING_VERDICT_RE.get_or_init(|| {
        Regex::new(
            r"(?i)^\s*(?:[✅❌✔]\s*:?\s*)?(?:(?:gabarito(?:\s+oficial)?|item|assertiva|afirmativa|resposta)\s*:\s*)?(?:(?:certo|errado|corret[oa]|incorret[oa]|verdadeir[oa]|fals[oa])\s*[.!—–:-]+\s*|(?:a\s+)?(?:alternativa|op[çc][ãa]o)\s+correta\s+[ée]\s+(?:a\s+)?(?:primeira|segunda|terceira|quarta|quinta|letra\s+[a-e]|[a-e]|[1-5][ªº]?)\s*[.!—–:-]+\s*)",
        )
        .unwrap()
    });
    let refs_re = TRAILING_REFERENCES_RE
        .get_or_init(|| Regex::new(r"(?is)\s*\bRefer[êe]ncias?\s*:.*$").unwrap());

    let without_icons = raw.replace("✅:", "").replace("❌:", "");
    let stripped_verdict = verdict_re.replace(without_icons.trim(), "");
    let without_refs = refs_re.replace(stripped_verdict.trim(), "");
    let candidate = without_refs.trim();
    if candidate.chars().count() >= 16 {
        candidate.to_string()
    } else if stripped_verdict.trim().chars().count() >= 16 {
        stripped_verdict.trim().to_string()
    } else {
        without_icons.trim().to_string()
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

/// Extrai apenas cláusulas genuínas de exceção/ressalva (`salvo`, `exceto`, `ressalvado`, `sem prejuízo`),
/// ignorando advérbios de escopo/finalidade como `apenas` ou `independentemente` para jamais rotular
/// o próprio enunciado ou uma assertiva falsa como "Ressalva / Exceção".
fn extract_genuine_exception_sentence(text: &str) -> Option<String> {
    let re = GENUINE_EXCEPTION_RE.get_or_init(|| {
        Regex::new(
            r"(?i)\b(salvo|exceto|excetuad[oa]s?|ressalvad[oa]s?|à\s+exce[çc][ãa]o|sem\s+preju[íi]zo)\b",
        )
        .unwrap()
    });
    let cleaned = strip_verdict_and_references(text);
    for sentence in cleaned.split(['.', ';', '\n']) {
        let s = sentence.trim();
        if s.chars().count() >= 18 && re.is_match(s) {
            return Some(truncate_chars(s, 240));
        }
    }
    None
}

fn pick_decisive_stem_fragment(stem: &str) -> String {
    let delim_re = STEM_DELIMITER_RE.get_or_init(|| {
        Regex::new(
            r"(?i)\b(salvo|exceto|ressalvad[oa]s?|vedad[oa]s?|independentemente|apenas|exclusivamente|somente|necessariamente|isoladamente|sempre|nunca|jamais|sem\s+preju[íi]zo|não\s+[ée]|implica|exige|prescinde)\b",
        )
        .unwrap()
    });
    let sentences: Vec<&str> = stem
        .split(['.', ';', '?', '\n'])
        .map(str::trim)
        .filter(|s| s.chars().count() >= 16)
        .collect();

    let chosen = sentences
        .iter()
        .copied()
        .find(|s| delim_re.is_match(s))
        .or_else(|| sentences.last().copied())
        .unwrap_or_else(|| stem.trim());

    if chosen.chars().count() <= 135 {
        return chosen.to_string();
    }

    if let Some(m) = delim_re.find(chosen) {
        let char_before = chosen[..m.start()].chars().count();
        if char_before > 32 {
            let target_skip = char_before.saturating_sub(20);
            let byte_start = chosen
                .char_indices()
                .nth(target_skip)
                .map(|(idx, _)| {
                    chosen[idx..]
                        .char_indices()
                        .find(|(_, ch)| ch.is_whitespace())
                        .map(|(ws, ch)| idx + ws + ch.len_utf8())
                        .unwrap_or(idx)
                })
                .unwrap_or(0);
            let windowed = chosen[byte_start..].trim_start_matches(|c: char| {
                c.is_whitespace() || matches!(c, ',' | ';' | ':' | '-' | '–' | '—')
            });
            if windowed.chars().count() >= 24 {
                return format!("…{}", truncate_chars(windowed, 130));
            }
        }
    }

    truncate_chars(chosen, 135)
}

pub(crate) fn deterministic_swap_evidence_order(question_id: &str) -> bool {
    question_id.bytes().fold(0u8, |acc, b| acc.wrapping_add(b)) & 1 == 1
}

/// Normaliza IDs legados (`ev_decisive`, `ev_err_1`, `ev_err_2`) emitidos por caches offline anteriores
/// quando a questão utiliza o desafio de evidência sintetizado padrão.
pub(crate) fn normalize_synthesized_evidence_id(raw_id: &str, has_curated_challenge: bool) -> &str {
    let trimmed = raw_id.trim();
    if has_curated_challenge {
        return trimmed;
    }
    if trimmed.eq_ignore_ascii_case("ev_decisive") {
        "EV_DECISIVE"
    } else if trimmed.eq_ignore_ascii_case("ev_premissa_divergente")
        || trimmed.eq_ignore_ascii_case("ev_err_1")
        || trimmed.eq_ignore_ascii_case("ev_err_2")
    {
        "EV_PREMISSA_DIVERGENTE"
    } else {
        trimmed
    }
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
                    && ns["source_id"]
                        .as_str()
                        .is_some_and(|id| wanted.contains(id))
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

        let official_source_excerpt = sources.first().map(|(_, title, locator, excerpt)| {
            if let Some(loc) = locator.as_deref().filter(|l| !l.is_empty()) {
                truncate_chars(&format!("{title} [{loc}]: {excerpt}"), 420)
            } else {
                truncate_chars(&format!("{title}: {excerpt}"), 420)
            }
        });

        let tested_rule = if let Some((_, _, _, excerpt)) = sources
            .first()
            .filter(|(_, _, _, ex)| !ex.trim().is_empty())
        {
            truncate_chars(excerpt, 320)
        } else if let Some(exp) = self.explanation.as_deref().filter(|s| !s.trim().is_empty()) {
            let clean = strip_verdict_and_references(exp);
            let first_para = clean
                .split('\n')
                .map(str::trim)
                .find(|line| line.chars().count() >= 20)
                .unwrap_or(clean.trim());
            truncate_chars(first_para, 320)
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

        let is_true_statement = self.effective_question_type() == "TRUE_FALSE"
            && self
                .answer_indices()
                .ok()
                .and_then(|idx| self.answers.get(*idx.first()?))
                .is_some_and(|ans| ans.trim().eq_ignore_ascii_case("Certo"));

        // Extrai exceção/ressalva APENAS de fontes fidedignas (catálogo normativo ou fundamentação oficial;
        // ou do enunciado exclusivamente se for item Certo sem fundamentação cadastrada).
        let exception_clause = sources
            .iter()
            .find_map(|(_, _, _, excerpt)| extract_genuine_exception_sentence(excerpt))
            .or_else(|| {
                self.explanation
                    .as_deref()
                    .filter(|s| !s.trim().is_empty())
                    .and_then(extract_genuine_exception_sentence)
            })
            .or_else(|| {
                if self
                    .explanation
                    .as_deref()
                    .is_none_or(|s| s.trim().is_empty())
                    && is_true_statement
                {
                    extract_genuine_exception_sentence(&self.question)
                } else {
                    None
                }
            });

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
                    description:
                        "Desconsiderar a ressalva ou exceção expressa aplicável ao caso concreto."
                            .to_string(),
                });
            } else if self.effective_question_type() == "TRUE_FALSE" {
                plausible_errors.push(ConceptualErrorItemDto {
                    error_id: "ERR_ESCOPO_ASSERTIVA".to_string(),
                    label: "Generalização ou restrição indevida da assertiva".to_string(),
                    description: "Aceitar extrapolação além do alcance do dispositivo ou conceito cobrado pela banca.".to_string(),
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

    /// Retorna o seletor opcional pós-resposta apontando exclusivamente para fragmentos concisos do
    /// enunciado e da fonte oficial (sem repetir a fundamentação textual e sem formular nova questão).
    pub fn effective_evidence_challenge(&self) -> Option<EvidenceChallengeDto> {
        let card = self.effective_editorial_card();
        self.build_evidence_challenge_from_card(card.as_ref())
    }

    fn build_evidence_challenge_from_card(
        &self,
        card_opt: Option<&EditorialCardDto>,
    ) -> Option<EvidenceChallengeDto> {
        if let Some(curated) = &self.evidence_challenge {
            return Some(curated.clone());
        }
        if self.is_open_ended() {
            return None;
        }
        let card = card_opt?;
        let sources = self.linked_catalog_sources();
        let primary_source_id = sources.first().map(|(sid, _, _, _)| sid.clone());

        let decisive_text = if let Some(first_prov) = card.legal_provisions.first() {
            truncate_chars(
                &format!(
                    "Critério no trecho \"{}\" confrontado com {}",
                    card.decisive_stem_excerpt, first_prov
                ),
                195,
            )
        } else if let Some((_, title, _, _)) = sources.first().filter(|(_, t, _, _)| !t.is_empty())
        {
            truncate_chars(
                &format!(
                    "Critério no trecho \"{}\" à luz de {}",
                    card.decisive_stem_excerpt, title
                ),
                195,
            )
        } else {
            truncate_chars(
                &format!(
                    "Alcance exato do trecho \"{}\" diante do conceito central cobrado",
                    card.decisive_stem_excerpt
                ),
                195,
            )
        };

        let alt_misconception = if self.effective_question_type() == "MULTIPLE_CHOICE" {
            if let Some(inc) = self
                .plausibility_analysis
                .as_ref()
                .and_then(|pa| pa.incorrect_answers_analysis.first())
                .filter(|inc| !inc.alternative.trim().is_empty())
            {
                truncate_chars(
                    &format!(
                        "Associação com a hipótese alternativa \"{}\"",
                        inc.alternative.trim()
                    ),
                    195,
                )
            } else {
                "Associação geral com termos familiares das alternativas, sem isolar o requisito específico do comando."
                    .to_string()
            }
        } else if card.exception_clause.is_some() {
            "Aplicação direta da regra geral do tema, sem ponderar a ressalva ou condição específica do item."
                .to_string()
        } else {
            "Impressão geral pelos termos técnicos do tema, sem isolar a condição específica afirmada na assertiva."
                .to_string()
        };

        let decisive_opt = EvidenceOptionDto {
            evidence_id: "EV_DECISIVE".to_string(),
            text: decisive_text,
            source_id: primary_source_id,
        };
        let divergent_opt = EvidenceOptionDto {
            evidence_id: "EV_PREMISSA_DIVERGENTE".to_string(),
            text: alt_misconception,
            source_id: None,
        };
        let options = if deterministic_swap_evidence_order(&self.id.to_hex()) {
            vec![divergent_opt, decisive_opt]
        } else {
            vec![decisive_opt, divergent_opt]
        };

        Some(EvidenceChallengeDto {
            prompt: "Opcional — qual ponto guiou sua decisão nesta questão?".to_string(),
            options,
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
        let editorial_card = self.effective_editorial_card();
        let evidence_challenge = self.build_evidence_challenge_from_card(editorial_card.as_ref());

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
            has_dr_sincero: self
                .dr_sincero
                .as_ref()
                .is_some_and(|s| !s.trim().is_empty()),
            has_mnemonic: self
                .mnemonico
                .as_ref()
                .is_some_and(|s| !s.trim().is_empty()),
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
            evidence_challenge,
            editorial_card,
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
            if self
                .correct_answer_indices
                .iter()
                .any(|&i| i >= self.answers.len())
            {
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

    #[test]
    fn synthesized_post_answer_avoids_false_stem_exceptions_verdict_prefixes_and_explanation_echo()
    {
        // Caso real reportado: "apenas para evitar reconhecer o prejuízo" no enunciado NÃO é exceção legal,
        // "Certo." e "Referência: ..." não devem poluir `tested_rule`, e `evidence_challenge` não deve repetir `tested_rule`.
        let q_disp: QuestionDocument = bson::from_document(doc! {
            "_id": ObjectId::new(),
            "question_type": "TRUE_FALSE",
            "question": "Vender rapidamente investimentos que se valorizaram e manter investimentos desvalorizados apenas para evitar reconhecer o prejuízo é um padrão compatível com o efeito disposição.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [0],
            "disciplina": "Finanças",
            "explanation": "Certo. O efeito disposição envolve maior propensão a realizar ganhos que perdas. Sua identificação empírica exige considerar as oportunidades de venda e explicações alternativas, como tributos, custos e rebalanceamento. Referência: Odean, Are Investors Reluctant to Realize Their Losses?, 1998."
        })
        .unwrap();

        let card = q_disp.effective_editorial_card().unwrap();
        assert!(
            card.exception_clause.is_none(),
            "Advérbio de finalidade 'apenas para evitar' no enunciado não pode virar Ressalva / Exceção"
        );
        assert!(
            !card.tested_rule.starts_with("Certo"),
            "tested_rule não deve iniciar com prefixo redundante de veredito: {}",
            card.tested_rule
        );
        assert!(
            !card.tested_rule.contains("Referência:"),
            "tested_rule não deve incluir rodapé bibliográfico: {}",
            card.tested_rule
        );

        let challenge = q_disp.effective_evidence_challenge().unwrap();
        assert_eq!(challenge.correct_evidence_id, "EV_DECISIVE");
        assert_eq!(challenge.options.len(), 2);
        for opt in &challenge.options {
            assert!(
                !opt.text
                    .contains("O efeito disposição envolve maior propensão"),
                "Desafio de 3 Sinais não deve repetir a explicação exibida acima: {}",
                opt.text
            );
            assert!(
                !opt.text.contains("Premissa divergente:"),
                "Opção alternativa não deve exibir rótulo meta-autoincriminador: {}",
                opt.text
            );
        }

        // Assertiva ERRADA contendo "salvo" no enunciado falso jamais deve extrair o enunciado falso como exceção verdadeira.
        let q_false_salvo: QuestionDocument = bson::from_document(doc! {
            "_id": ObjectId::new(),
            "question_type": "TRUE_FALSE",
            "question": "As dimensões ambiental, social e de governança são independentes, salvo no setor bancário estatal.",
            "answers": ["Certo", "Errado"],
            "correct_answer_indices": [1],
            "disciplina": "Finanças",
            "explanation": "Errado. Um mesmo evento pode atravessar múltiplas dimensões de risco e categorias financeiras."
        })
        .unwrap();
        let false_card = q_false_salvo.effective_editorial_card().unwrap();
        assert!(
            false_card.exception_clause.is_none(),
            "Enunciado de item Errado jamais pode ser extraído como Ressalva / Exceção"
        );

        // Normalização retrocompatível de IDs legados de evidência
        assert_eq!(
            normalize_synthesized_evidence_id("ev_decisive", false),
            "EV_DECISIVE"
        );
        assert_eq!(
            normalize_synthesized_evidence_id("ev_err_1", false),
            "EV_PREMISSA_DIVERGENTE"
        );
        assert_eq!(
            normalize_synthesized_evidence_id("ev_decisive", true),
            "ev_decisive"
        );
    }

    #[test]
    fn pick_decisive_stem_fragment_handles_multibyte_utf8_whitespace_without_panic() {
        // U+00A0 (NBSP, 2 bytes) e U+202F (NNBSP, 3 bytes) são comuns em PDFs de editais e provas brasileiras
        let nbsp = "\u{00A0}";
        let nnbsp = "\u{202F}";
        let stem = format!(
            "Considere{nbsp}a{nbsp}administração{nnbsp}pública{nbsp}federal{nbsp}direta{nnbsp}e{nbsp}autárquica{nbsp}no{nnbsp}exercício{nbsp}de{nbsp}poder{nnbsp}regulamentar,{nbsp}salvo{nnbsp}quando{nbsp}houver{nbsp}reserva{nbsp}legal{nbsp}estrita{nnbsp}prevista{nbsp}na{nbsp}Constituição{nbsp}Federal."
        );
        let q: QuestionDocument = bson::from_document(doc! {
            "_id": ObjectId::new(),
            "question_type": "MULTIPLE_CHOICE",
            "question": stem,
            "answers": ["Opção 1", "Opção 2", "Opção 3", "Opção 4", "Opção 5"],
            "correct_answer_indices": [0],
            "disciplina": "Direito Administrativo"
        })
        .unwrap();
        let dto = q.to_dto(None);
        let excerpt = &dto.editorial_card.expect("card").decisive_stem_excerpt;
        assert!(excerpt.contains("salvo"));
    }
}
