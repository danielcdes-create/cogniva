use crate::error::AppError;
use bson::oid::ObjectId;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserQuestionBriefStats {
    pub attempts: i32,
    pub accuracy_pct: f64,
    pub last_session_iso: Option<String>,
    pub is_mastered: bool,
    pub in_error_notebook: bool,
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
            disciplina: if self.disciplina.trim().is_empty() {
                "Disciplina Geral".to_string()
            } else {
                self.disciplina.clone()
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
            "answers": ["Não", "Ação", "Sem", "Outro"],
            "correct_answers": ["Ação"],
            "disciplina": "Teste"
        })
        .unwrap();
        assert_eq!(
            q.revision().unwrap(),
            "19163f3dda43893f9e0702f684358c43a5b06db4b0451f1f7315adebfa388583"
        );

        let open_raw: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-open-manual.json"
        ))
        .unwrap();
        let open_doc: QuestionDocument =
            bson::from_document(bson::Bson::try_from(open_raw.clone()).unwrap().as_document().unwrap().clone())
                .unwrap();
        let rev = open_doc.revision().unwrap();
        assert_eq!(rev.len(), 64);
        assert_eq!(rev, open_raw["content_revision"].as_str().unwrap());
        let dto = open_doc.to_dto(None);
        assert_eq!(dto.question_revision, rev);
        assert_eq!(dto.question_type, "OPEN_ENDED");
    }
}
