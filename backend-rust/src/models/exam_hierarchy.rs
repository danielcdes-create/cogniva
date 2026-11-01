use serde::{Deserialize, Serialize};

/// Tronco de carreira. A cor de destaque fica no catálogo; a UI usa os tokens do tema.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CareerTrunk {
    pub slug: String,
    pub name: String,
    pub icon_key: String,
    pub core_subjects: Vec<TrunkSubjectDefault>,
    pub complementary_subjects: Vec<TrunkSubjectDefault>,
    pub is_system_default: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TrunkSubjectDefault {
    pub canonical_subject_id: String,
    pub name: String,
    pub default_weight: f64,
    pub relevance_tier: RelevanceTier,
    pub topics: Vec<CanonicalTopic>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RelevanceTier {
    Core,
    Complementary,
    Specific,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CanonicalSubject {
    pub canonical_subject_id: String,
    pub code: String,
    pub name: String,
    pub aliases: Vec<String>,
    pub trunk_slugs: Vec<String>,
    pub topics: Vec<CanonicalTopic>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CanonicalTopic {
    pub topic_id: String,
    pub title: String,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExamLifecycleStatus {
    RumorPrevisto,
    Autorizado,
    BancaDefinida,
    EditalPublicado,
    ProvaRealizada,
    Arquivado,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExamDateTarget {
    ExactDate {
        iso_date: String,
    },
    EstimatedWindow {
        label: String,
        estimated_sort_date: String,
    },
    Undefined,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SubjectCategory {
    ConhecimentosGerais,
    ConhecimentosEspecificos,
    DiscursivaEstudoDeCaso,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CanonicalTopicStatus {
    PreEditalBase,
    OficialConfirmado,
    OficialNovo,
    Arquivado,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExamTopicItem {
    pub local_topic_id: String,
    pub canonical_topic_id: String,
    pub canonical_subject_id: String,
    pub display_title: String,
    pub diff_status: CanonicalTopicStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExamSubjectConfig {
    pub local_subject_id: String,
    pub canonical_subject_id: String,
    pub display_name: String,
    pub category: SubjectCategory,
    pub weight: f64,
    pub question_count: Option<u32>,
    pub relevance_tier: RelevanceTier,
    pub topics: Vec<ExamTopicItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct EditalDiffSummary {
    pub confirmed_count: u32,
    pub new_count: u32,
    pub archived_count: u32,
    pub published_at_iso: String,
}

/// Concurso oficial ou criado pelo usuário. `id` é o identificador do cliente (UUID ou código estável).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExamBlueprint {
    #[serde(rename = "_id", alias = "id")]
    pub id: String,
    #[serde(default)]
    pub revision: i64,
    #[serde(default)]
    pub wrong_answer_penalty: f64,
    pub owner_user_id: Option<String>,
    pub title: String,
    pub short_name: String,
    pub institution: String,
    pub role: String,
    pub trunk_slug: String,
    pub status: ExamLifecycleStatus,
    pub exam_boards: Vec<String>,
    pub target_date: ExamDateTarget,
    pub historical_cutoff_score: f64,
    pub is_active_in_ladder: bool,
    pub ladder_priority: i32,
    pub subjects: Vec<ExamSubjectConfig>,
    pub edital_diff_summary: Option<EditalDiffSummary>,
    #[serde(default)]
    pub sync_status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TrunkCatalog {
    pub trunks: Vec<CareerTrunk>,
    pub subjects: Vec<CanonicalSubject>,
    pub exams: Vec<ExamBlueprint>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TopicRef {
    pub canonical_topic_id: String,
    pub canonical_subject_id: String,
    pub display_title: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ClassifiedTopic {
    pub canonical_topic_id: String,
    pub canonical_subject_id: String,
    pub display_title: String,
    pub diff_status: CanonicalTopicStatus,
    /// Preenchida quando o tópico novo já foi estudado em outro concurso.
    pub coverage_already_filled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct EditalPublishResult {
    pub topics: Vec<ClassifiedTopic>,
    pub confirmed_count: u32,
    pub new_count: u32,
    pub archived_count: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PublishEditalRequest {
    #[serde(default)]
    pub expected_revision: i64,
    pub published_topics: Vec<TopicRef>,
    #[serde(default)]
    pub practiced_canonical_topic_ids: Vec<String>,
    pub published_at_iso: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct LeverageSubject {
    pub canonical_subject_id: String,
    pub name: String,
    pub weight: f64,
    pub archived_only: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct LeverageExam {
    pub exam_id: String,
    pub short_name: String,
    pub trunk_slug: String,
    pub status: ExamLifecycleStatus,
    pub exact_date_iso: Option<String>,
    pub is_active_in_ladder: bool,
    pub subjects: Vec<LeverageSubject>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TrunkFocus {
    pub slug: String,
    pub core_subject_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CommonTrunkCard {
    pub canonical_subject_id: String,
    pub subject_name: String,
    pub suggested_questions: u32,
    pub contest_names: Vec<String>,
    pub sentence: String,
    pub score: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReadinessTopic {
    pub archived: bool,
    pub practiced: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReadinessSubject {
    pub weight: f64,
    pub real_mastery: f64,
    pub topics: Vec<ReadinessTopic>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SimuladoCandidate {
    pub question_id: String,
    pub canonical_subject_id: String,
    pub canonical_topic_id: String,
    pub archived: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SubjectQuota {
    pub canonical_subject_id: String,
    pub weight: f64,
    pub question_count: u32,
    pub allowed_topic_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SubjectOutcome {
    #[serde(default)]
    pub canonical_subject_id: String,
    #[serde(default)]
    pub wrong: u32,
    #[serde(default)]
    pub penalty: f64,
    pub weight: f64,
    pub correct: u32,
    pub drawn: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CanonicalMasteryRecord {
    pub canonical_subject_id: String,
    pub canonical_topic_id: String,
    pub mastery: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ExamTopicLink {
    pub exam_id: String,
    pub canonical_subject_id: String,
    pub canonical_topic_id: String,
    pub diff_status: CanonicalTopicStatus,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ProjectedTopicCoverage {
    pub exam_id: String,
    pub canonical_subject_id: String,
    pub canonical_topic_id: String,
    pub mastery: f64,
    pub practiced: bool,
    pub hidden_from_study_queue: bool,
}
