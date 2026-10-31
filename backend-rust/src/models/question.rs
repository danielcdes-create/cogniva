use bson::oid::ObjectId;
use serde::{Deserialize, Serialize};

/// Tipo da questão: suporta Múltipla Escolha (FCC, FGV, Cesgranrio) e Certo/Errado (Cebraspe)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QuestionType {
    MultipleChoice,
    TrueFalse,
}

impl Default for QuestionType {
    fn default() -> Self {
        Self::MultipleChoice
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PlausibilityItem {
    #[serde(rename = "Plausibilidade", default)]
    pub plausibilidade: String,
    #[serde(default)]
    pub contextual_coherence: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct IncorrectAnswerAnalysis {
    #[serde(default)]
    pub alternative: String,
    #[serde(default)]
    pub plausibility: Vec<PlausibilityItem>,
    #[serde(default)]
    pub explanation: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PlausibilityAnalysis {
    #[serde(default)]
    pub correct_answer_explanation: String,
    #[serde(default)]
    pub incorrect_answers_analysis: Vec<IncorrectAnswerAnalysis>,
    #[serde(default)]
    pub conclusion: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct LegalValidity {
    #[serde(default = "default_validity_status")]
    pub status: String, // "ACTIVE" | "OUTDATED_LAW" | "UNDER_REVIEW"
    pub reference_date: Option<String>,
    #[serde(default)]
    pub normative_refs: Vec<String>,
}

fn default_validity_status() -> String {
    "ACTIVE".to_string()
}

/// Representa um documento da coleção `todasQuestoes` no MongoDB Atlas (100% retrocompatível com AFTBot.py)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuestionDocument {
    #[serde(rename = "_id")]
    pub id: ObjectId,
    pub question: String,
    #[serde(default)]
    pub question_type: QuestionType,
    #[serde(default)]
    pub answers: Vec<String>,
    #[serde(default)]
    pub correct_answers: Vec<String>,
    #[serde(default)]
    pub correct_answer_indices: Vec<usize>,
    #[serde(default)]
    pub disciplina: String,
    /// No legado pode ser string única ou array de strings; suportamos ambos de forma flexível
    #[serde(default)]
    pub concursos: bson::Bson,
    #[serde(default)]
    pub source: Option<String>,
    #[serde(default)]
    pub exam_year: Option<i32>,
    #[serde(default)]
    pub level: Option<String>,

    // --- Vínculos com Edital Verticalizado e Tronco Comum ---
    #[serde(default)]
    pub syllabus_topic_ids: Vec<String>,
    #[serde(default)]
    pub common_trunk_id: Option<String>,
    #[serde(default)]
    pub is_borrowed_analogous: bool,
    #[serde(default)]
    pub legal_validity: Option<LegalValidity>,

    // --- Recursos Pedagógicos de IA Cacheados no Documento (Legado + App) ---
    #[serde(default)]
    pub explanation: Option<String>,
    #[serde(rename = "contextualHint", default)]
    pub contextual_hint: Vec<String>,
    #[serde(rename = "drSincero", default)]
    pub dr_sincero: Option<String>,
    #[serde(rename = "ajudaInfantil", default)]
    pub ajuda_infantil: Option<String>,
    #[serde(rename = "explicacaoInfantil", default)]
    pub explicacao_infantil: Option<String>,
    #[serde(rename = "explanationExtra", default)]
    pub explanation_extra: Option<String>,
    #[serde(default)]
    pub mnemonico: Option<String>,
    #[serde(rename = "plausibilityAnalysis", default)]
    pub plausibility_analysis: Option<PlausibilityAnalysis>,
}

/// DTO enxuto e tipado enviado para o cliente Android (Jetpack Compose / Room)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuestionDto {
    pub id: String,
    pub question: String,
    pub question_type: QuestionType,
    pub answers: Vec<String>,
    pub correct_answer_indices: Vec<usize>,
    pub disciplina: String,
    pub source: String,
    pub exam_year: Option<i32>,
    pub level: String,
    pub syllabus_topic_ids: Vec<String>,
    pub common_trunk_id: Option<String>,
    pub is_borrowed_analogous: bool,
    pub explanation: Option<String>,
    pub has_dr_sincero: bool,
    pub has_mnemonic: bool,
    pub has_plausibility: bool,
    pub hints_count: usize,
    pub user_stats: Option<UserQuestionBriefStats>,
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
    pub fn to_dto(&self, user_stats: Option<UserQuestionBriefStats>) -> QuestionDto {
        QuestionDto {
            id: self.id.to_hex(),
            question: self.question.clone(),
            question_type: self.question_type.clone(),
            answers: self.answers.clone(),
            correct_answer_indices: self.correct_answer_indices.clone(),
            disciplina: if self.disciplina.is_empty() {
                "Disciplina Geral".to_string()
            } else {
                self.disciplina.clone()
            },
            source: self.source.clone().unwrap_or_else(|| " Inédita / IA".to_string()),
            exam_year: self.exam_year,
            level: self.level.clone().unwrap_or_else(|| "Médio".to_string()),
            syllabus_topic_ids: self.syllabus_topic_ids.clone(),
            common_trunk_id: self.common_trunk_id.clone(),
            is_borrowed_analogous: self.is_borrowed_analogous,
            explanation: self.explanation.clone(),
            has_dr_sincero: self.dr_sincero.is_some(),
            has_mnemonic: self.mnemonico.is_some(),
            has_plausibility: self.plausibility_analysis.is_some(),
            hints_count: self.contextual_hint.len(),
            user_stats,
        }
    }
}

/// Categorias válidas de reporte de problemas (portadas de `VALID_PROBLEM_LIST` em AFTBot.py)
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
