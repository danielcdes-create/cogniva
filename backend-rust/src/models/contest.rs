use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Máquina de estados do Concurso conforme Nota Técnica Quiz Kotlin (§6.1)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ContestStatus {
    Previsto,
    SolicitadoAutorizado,
    EditalPublicado,
    EmAndamento,
    Encerrado,
}

/// Selo de confiança do Edital Estimado (§7.2)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConfidenceBadge {
    High,   // 🟢 Recorrente nos últimos 3 editais
    Medium, // 🟡 Em 2 dos últimos 3 ou similares
    Low,    // ⚪ Tendência / tema em alta
}

/// Status do tópico na transição Previsto -> Edital Publicado (§8)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TopicDiffStatus {
    Confirmed,
    NewInEdital,
    RemovedArchived,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContestDates {
    /// Data oficial da prova em ISO-8601 (somente quando EditalPublicado ou EmAndamento)
    pub exam_date_official: Option<String>,
    /// Janela estimada honesta (ex: "2º sem/2026" ou "1º sem/2027") sem countdown falso
    pub estimated_window: Option<String>,
    pub edital_published_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoringRules {
    pub question_format: String, // "MULTIPLE_CHOICE_5" | "CEBRASPE_TRUE_FALSE"
    /// Fator de penalidade (ex: 1.0 para Cebraspe onde 1 errada anula 1 certa; 0.0 para sem penalidade)
    pub wrong_answer_penalty: f64,
    pub historical_cutoff_pct: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContestSourceRef {
    pub title: String,
    pub url: Option<String>,
    pub source_type: String, // "LAST_EDITAL" | "DOU_AUTHORIZATION" | "ANALOGOUS_CONTEST"
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EditalDiffSummary {
    pub previous_label: String,
    pub published_label: String,
    pub confirmed_topics_count: usize,
    pub new_topics_count: usize,
    pub removed_topics_count: usize,
    pub confirmed_board: String,
}

/// Documento da coleção `concursos`
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContestDocument {
    #[serde(rename = "_id")]
    pub id: String,
    pub code: String, // Ex: "CPNU", "BNDES", "RFB", "PF_AGENTE"
    pub name: String,
    pub role_title: String,
    pub career_area: String,
    pub uf: String,
    pub status: ContestStatus,
    pub exam_board: String,
    pub is_board_confirmed: bool,
    pub dates: ContestDates,
    pub scoring_rules: ScoringRules,
    /// Pesos de cada disciplina no Simulado (soma = 1.0)
    pub simulation_proportions: HashMap<String, f64>,
    pub blocks: HashMap<String, Vec<String>>,
    pub sources: Vec<ContestSourceRef>,
    pub latest_diff_summary: Option<EditalDiffSummary>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EstimationMetadata {
    pub is_estimated: bool,
    pub confidence_badge: ConfidenceBadge,
    pub rationale: String,
    pub reference_exams: Vec<String>,
}

/// Documento da coleção `editais_topicos` (Edital Verticalizado Oficial e Estimado)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyllabusTopicDocument {
    #[serde(rename = "_id")]
    pub id: String,
    pub contest_id: String,
    pub block_name: String,
    pub discipline: String,
    pub title: String,
    pub order: i32,
    /// Identificador do Tronco Comum para aproveitar estudo entre múltiplos concursos (§13)
    pub common_trunk_id: Option<String>,
    pub estimation: EstimationMetadata,
    pub diff_status: TopicDiffStatus,
}
