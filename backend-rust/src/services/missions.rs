//! Módulo Missões — Aprendizagem experiencial por narrativas audiovisuais interativas.
//!
//! Mantém separação estrita entre:
//! 1. **Progresso narrativo** (`narrative_progress`): cenas percorridas e estado da sessão.
//! 2. **Desempenho nas interações didáticas** (`interaction_performance`): decisões formativas da história
//!    (nunca inseridas no banco oficial `todasQuestoes` e nunca contadas como domínio formal).
//! 3. **Domínio demonstrado** (`demonstrated_mastery`): evidências provenientes exclusivamente de questões
//!    humanas reais e validadas em `todasQuestoes` + `userActivityLog` (`MIN_QUESTIONS_FOR_MASTERY = 15`).

use crate::{
    error::AppError,
    models::{activity::UserActivityDocument, question::QuestionDto},
    services::{
        catalog_runtime, question_storage, quiz_selector::enrich_questions_with_user_stats,
        selection_v2,
    },
    AppState,
};
use bson::{doc, oid::ObjectId, Document};
use chrono::Utc;
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    path::{Path, PathBuf},
    sync::OnceLock,
};

pub const MIN_HUMAN_QUESTIONS_FOR_MASTERY: usize = 15;
pub const MAX_TELEMETRY_EVENTS_PER_MISSION: usize = 80;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MissionsCatalogSpec {
    #[serde(rename = "$schema")]
    pub schema_ref: String,
    pub schema_version: i32,
    pub catalog_id: String,
    pub catalog_version: i64,
    pub updated_at: String,
    pub language: String,
    pub media_assets: Vec<MediaAssetSpec>,
    pub characters: Vec<CharacterSpec>,
    pub careers: Vec<CareerSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaptionCueSpec {
    pub cue_id: String,
    pub start_ms: u64,
    pub end_ms: u64,
    #[serde(default)]
    pub speaker: Option<String>,
    pub text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MediaAssetSpec {
    pub asset_id: String,
    pub kind: String,
    pub relative_file_name: String,
    pub mime_type: String,
    pub size_bytes: u64,
    pub sha256: String,
    pub duration_seconds: f64,
    pub width: u32,
    pub height: u32,
    pub aspect_ratio: String,
    pub video_codec: String,
    pub audio_codec: String,
    #[serde(default)]
    pub audio_sample_rate_hz: Option<u32>,
    #[serde(default)]
    pub audio_channels: Option<u16>,
    pub has_embedded_voice: bool,
    pub language: String,
    #[serde(default)]
    pub scene_mapping_rationale: Option<String>,
    pub captions: Vec<CaptionCueSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CharacterSpec {
    pub character_id: String,
    pub name: String,
    pub role_title: String,
    pub institution_context: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CareerSpec {
    pub career_id: String,
    pub trunk_id: String,
    #[serde(default)]
    pub contest_template_id: Option<String>,
    pub display_header: String,
    pub institution: String,
    pub role: String,
    pub status: String,
    pub disciplines: Vec<DisciplineSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DisciplineSpec {
    pub discipline_id: String,
    pub canonical_subject_id: String,
    #[serde(default)]
    pub exam_component_id: Option<String>,
    pub display_name: String,
    pub seasons: Vec<SeasonSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SeasonSpec {
    pub season_id: String,
    pub season_number: i32,
    pub title: String,
    pub theme: String,
    pub summary: String,
    pub missions: Vec<MissionSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkedConceptSpec {
    pub concept_key: String,
    pub label: String,
    pub summary: String,
    pub canonical_subject_id: String,
    pub canonical_topic_ids: Vec<String>,
    #[serde(default)]
    pub fallback_subject_ids: Vec<String>,
    pub related_scene_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LearningEvidencePolicySpec {
    pub video_completion_grants_mastery: bool,
    pub narrative_decisions_grant_mastery: bool,
    pub requires_independent_human_questions: bool,
    pub min_human_questions_for_mastery: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CurriculumBindingSpec {
    pub contest_name: String,
    #[serde(default)]
    pub contest_template_id: Option<String>,
    pub role_name: String,
    #[serde(default)]
    pub exam_component_id: Option<String>,
    pub discipline_label: String,
    pub theme_label: String,
    pub canonical_subject_id: String,
    #[serde(default)]
    pub related_subject_ids: Vec<String>,
    pub primary_topic_ids: Vec<String>,
    #[serde(default)]
    pub fallback_topic_ids: Vec<String>,
    #[serde(default)]
    pub curated_question_ids: Vec<String>,
    pub worked_concepts: Vec<WorkedConceptSpec>,
    pub learning_evidence_policy: LearningEvidencePolicySpec,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DecisionOptionSpec {
    pub option_id: String,
    pub label: String,
    pub feedback: String,
    #[serde(default)]
    pub branch_scene_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DidacticInteractionSpec {
    pub interaction_id: String,
    pub kind: String,
    pub is_editorial_question: bool,
    pub contributes_to_formal_mastery: bool,
    #[serde(default)]
    pub context_lead: Option<String>,
    pub prompt: String,
    pub ceteris_paribus_note: String,
    pub options: Vec<DecisionOptionSpec>,
    pub expected_option_id: String,
    pub conceptual_explanation: String,
    pub revisit_concept_keys: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NativeVisualSpec {
    pub visual_id: String,
    pub visual_type: String,
    pub title: String,
    pub subtitle: String,
    pub formula_label: String,
    pub highlights: Vec<String>,
    pub parameters: BTreeMap<String, Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SceneSpec {
    pub scene_id: String,
    pub order: usize,
    pub title: String,
    pub pedagogical_goal: String,
    pub asset_id: String,
    pub character_id: String,
    pub narrative_context: String,
    #[serde(default)]
    pub problem_statement: Option<String>,
    pub transcript_pt_br: String,
    pub estimated_interaction_seconds: u32,
    pub auto_advance_on_video_end: bool,
    pub requires_decision_to_advance: bool,
    #[serde(default)]
    pub next_scene_id: Option<String>,
    #[serde(default)]
    pub interaction: Option<DidacticInteractionSpec>,
    #[serde(default)]
    pub native_visual: Option<NativeVisualSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MissionConclusionSpec {
    pub headline: String,
    pub narrative_closure: String,
    pub epistemological_note: String,
    pub next_step_guidance: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MissionSpec {
    pub mission_id: String,
    pub mission_number: i32,
    pub version: i32,
    pub publication_state: String,
    pub display_code: String,
    pub title: String,
    pub full_title: String,
    pub summary: String,
    pub description: String,
    pub curriculum: CurriculumBindingSpec,
    pub scenes: Vec<SceneSpec>,
    pub conclusion: MissionConclusionSpec,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecordedDecisionDoc {
    pub interaction_id: String,
    pub scene_id: String,
    pub first_selected_option_id: String,
    pub latest_selected_option_id: String,
    pub first_attempt_correct: bool,
    pub latest_correct: bool,
    pub attempt_count: i32,
    pub answered_at: String,
    pub time_spent_ms: i64,
    #[serde(default)]
    pub used_transcript_fallback: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MissionTelemetryEventDoc {
    pub event_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scene_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    pub occurred_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MissionProgressDoc {
    pub user_id: i64,
    pub mission_id: String,
    pub mission_version: i32,
    /// "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED"
    pub status: String,
    pub current_scene_id: String,
    #[serde(default)]
    pub completed_scene_ids: Vec<String>,
    #[serde(default)]
    pub scene_positions_ms: BTreeMap<String, i64>,
    #[serde(default)]
    pub decisions: BTreeMap<String, RecordedDecisionDoc>,
    #[serde(default)]
    pub started_at: String,
    #[serde(default)]
    pub updated_at: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub completed_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_resumed_at: Option<String>,
    #[serde(default)]
    pub resume_count: i32,
    #[serde(default)]
    pub run_count: i32,
    #[serde(default)]
    pub completed_runs_count: i32,
    #[serde(default)]
    pub revision: i64,
    #[serde(default)]
    pub telemetry_events: Vec<MissionTelemetryEventDoc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CalculatedDurationDto {
    pub video_seconds: f64,
    pub interaction_seconds: u32,
    pub total_seconds: f64,
    pub estimated_minutes: u32,
    pub display_label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NarrativeProgressSummaryDto {
    pub status: String,
    pub status_label_pt: String,
    pub current_scene_id: String,
    pub current_scene_order: usize,
    pub completed_scene_ids: Vec<String>,
    pub completed_scenes_count: usize,
    pub total_scenes_count: usize,
    pub progress_pct: f64,
    pub scene_positions_ms: BTreeMap<String, i64>,
    pub started_at: Option<String>,
    pub updated_at: Option<String>,
    pub completed_at: Option<String>,
    pub last_resumed_at: Option<String>,
    pub resume_count: i32,
    pub run_count: i32,
    pub completed_runs_count: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InteractionPerformanceSummaryDto {
    pub answered_decisions_count: usize,
    pub total_decisions_count: usize,
    pub first_attempt_correct_count: usize,
    pub latest_correct_count: usize,
    pub first_attempt_accuracy_pct: Option<f64>,
    pub is_diagnostic_only: bool,
    pub concepts_for_review: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DemonstratedMasterySummaryDto {
    /// "NO_QUESTIONS_AVAILABLE" | "UNMEASURED" | "EVIDENCE_IN_PROGRESS" | "VERIFIED_INDEPENDENT"
    pub mastery_state: String,
    pub mastery_label_pt: String,
    pub available_human_questions_count: usize,
    pub distinct_human_questions_practiced: usize,
    pub mastered_human_questions_count: usize,
    pub min_questions_for_mastery: usize,
    pub resolved_topic_ids: Vec<String>,
    pub practice_available: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StudentOptionDto {
    pub option_id: String,
    pub label: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub feedback: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StudentInteractionDto {
    pub interaction_id: String,
    pub kind: String,
    pub is_editorial_question: bool,
    pub contributes_to_formal_mastery: bool,
    pub context_lead: Option<String>,
    pub prompt: String,
    pub ceteris_paribus_note: String,
    pub options: Vec<StudentOptionDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub answered: Option<StudentDecisionFeedbackDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StudentDecisionFeedbackDto {
    pub interaction_id: String,
    pub scene_id: String,
    pub selected_option_id: String,
    pub first_selected_option_id: String,
    pub expected_option_id: String,
    pub is_correct: bool,
    pub first_attempt_correct: bool,
    pub attempt_count: i32,
    pub option_feedback: String,
    pub conceptual_explanation: String,
    pub revisit_concept_keys: Vec<String>,
    pub answered_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SceneMediaDto {
    pub asset_id: String,
    pub stream_url: String,
    pub captions_url: String,
    pub mime_type: String,
    pub duration_seconds: f64,
    pub width: u32,
    pub height: u32,
    pub aspect_ratio: String,
    pub has_embedded_voice: bool,
    pub available_on_server: bool,
    pub captions: Vec<CaptionCueSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StudentSceneDto {
    pub scene_id: String,
    pub order: usize,
    pub title: String,
    pub pedagogical_goal: String,
    pub character: CharacterSpec,
    pub narrative_context: String,
    pub problem_statement: Option<String>,
    pub transcript_pt_br: String,
    pub estimated_interaction_seconds: u32,
    pub auto_advance_on_video_end: bool,
    pub requires_decision_to_advance: bool,
    pub next_scene_id: Option<String>,
    pub preload_next_media_url: Option<String>,
    pub is_completed: bool,
    pub media: SceneMediaDto,
    pub interaction: Option<StudentInteractionDto>,
    pub native_visual: Option<NativeVisualSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkedConceptViewDto {
    pub concept_key: String,
    pub label: String,
    pub summary: String,
    pub canonical_subject_id: String,
    pub active_topic_ids: Vec<String>,
    pub related_scene_ids: Vec<String>,
    pub recommended_revisit: bool,
}

pub fn bundled_catalog() -> &'static MissionsCatalogSpec {
    static CATALOG: OnceLock<MissionsCatalogSpec> = OnceLock::new();
    CATALOG.get_or_init(|| {
        let schema_val: Value =
            serde_json::from_str(include_str!("../../../shared/schemas/missions.schema.json"))
                .expect("valid missions.schema.json");
        let raw_val: Value =
            serde_json::from_str(include_str!("../../../shared/missions-catalog.json"))
                .expect("valid missions-catalog.json");
        validate_catalog_value(&schema_val, &raw_val)
            .expect("bundled missions-catalog.json passes schema validation");
        serde_json::from_value(raw_val).expect("MissionsCatalogSpec deserializes")
    })
}

pub fn validate_catalog_value(schema_val: &Value, raw_val: &Value) -> Result<(), AppError> {
    let validator = jsonschema::validator_for(schema_val)
        .map_err(|e| AppError::Internal(format!("Schema de missões inválido: {e}")))?;
    if let Err(err) = validator.validate(raw_val) {
        return Err(AppError::BadRequest(format!(
            "Catálogo de missões inválido em {}: {}",
            err.instance_path, err
        )));
    }
    Ok(())
}

#[cfg(test)]
pub fn find_media_asset(asset_id: &str) -> Option<&'static MediaAssetSpec> {
    bundled_catalog()
        .media_assets
        .iter()
        .find(|a| a.asset_id == asset_id)
}

pub fn resolve_media_path(media_dir: &Path, asset: &MediaAssetSpec) -> Option<PathBuf> {
    if asset.relative_file_name.contains('/')
        || asset.relative_file_name.contains('\\')
        || asset.relative_file_name.contains("..")
        || asset.relative_file_name.contains(':')
    {
        return None;
    }
    let mut comps = Path::new(&asset.relative_file_name).components();
    if !matches!(comps.next(), Some(std::path::Component::Normal(_))) || comps.next().is_some() {
        return None;
    }
    let candidate = media_dir.join(&asset.relative_file_name);
    if candidate.is_file() {
        Some(candidate)
    } else {
        None
    }
}

fn sanitize_vtt_single_line(raw: &str) -> String {
    raw.replace(['\r', '\n'], " ")
        .replace("-->", "->")
        .trim()
        .to_string()
}

pub fn format_webvtt_timestamp(ms: u64) -> String {
    let hours = ms / 3_600_000;
    let rem = ms % 3_600_000;
    let minutes = rem / 60_000;
    let rem = rem % 60_000;
    let seconds = rem / 1_000;
    let millis = rem % 1_000;
    format!("{hours:02}:{minutes:02}:{seconds:02}.{millis:03}")
}

pub fn render_webvtt(asset: &MediaAssetSpec) -> String {
    let mut out = String::from("WEBVTT\nKind: captions\nLanguage: pt-BR\n\n");
    for cue in &asset.captions {
        out.push_str(&sanitize_vtt_single_line(&cue.cue_id));
        out.push('\n');
        out.push_str(&format_webvtt_timestamp(cue.start_ms));
        out.push_str(" --> ");
        out.push_str(&format_webvtt_timestamp(cue.end_ms.max(cue.start_ms)));
        out.push('\n');
        let clean_text = sanitize_vtt_single_line(&cue.text);
        if let Some(speaker) = cue.speaker.as_deref().filter(|s| !s.trim().is_empty()) {
            let clean_speaker = sanitize_vtt_single_line(speaker).replace(['<', '>'], "");
            out.push_str(&format!("<v {clean_speaker}>{clean_text}\n\n"));
        } else {
            out.push_str(&clean_text);
            out.push_str("\n\n");
        }
    }
    out
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UnsatisfiableRange;

/// Parses a single HTTP `Range: bytes=start-end` header against `total_len`.
/// Returns `Ok(Some((start, end_inclusive)))` for valid partial range,
/// `Ok(None)` when no Range header is present, and `Err(UnsatisfiableRange)` for unsatisfiable ranges (`416`).
pub fn parse_http_byte_range(
    range_header: Option<&str>,
    total_len: u64,
) -> Result<Option<(u64, u64)>, UnsatisfiableRange> {
    let Some(raw) = range_header.map(str::trim).filter(|s| !s.is_empty()) else {
        return Ok(None);
    };
    if total_len == 0 {
        return Err(UnsatisfiableRange);
    }
    let Some(spec) = raw.strip_prefix("bytes=") else {
        return Err(UnsatisfiableRange);
    };
    if spec.contains(',') {
        return Err(UnsatisfiableRange);
    }
    let Some((start_str, end_str)) = spec.split_once('-') else {
        return Err(UnsatisfiableRange);
    };
    let start_str = start_str.trim();
    let end_str = end_str.trim();

    if start_str.is_empty() {
        let suffix_len: u64 = end_str.parse().map_err(|_| UnsatisfiableRange)?;
        if suffix_len == 0 {
            return Err(UnsatisfiableRange);
        }
        let start = total_len.saturating_sub(suffix_len);
        return Ok(Some((start, total_len - 1)));
    }

    let start: u64 = start_str.parse().map_err(|_| UnsatisfiableRange)?;
    if start >= total_len {
        return Err(UnsatisfiableRange);
    }
    let end = if end_str.is_empty() {
        total_len - 1
    } else {
        let parsed_end: u64 = end_str.parse().map_err(|_| UnsatisfiableRange)?;
        if parsed_end < start {
            return Err(UnsatisfiableRange);
        }
        parsed_end.min(total_len - 1)
    };
    Ok(Some((start, end)))
}

pub fn calculate_mission_duration(
    mission: &MissionSpec,
    assets: &HashMap<String, MediaAssetSpec>,
) -> CalculatedDurationDto {
    let mut video_seconds = 0.0_f64;
    let mut interaction_seconds = 0_u32;
    let mut decisions_count = 0_usize;

    for scene in &mission.scenes {
        if let Some(asset) = assets.get(&scene.asset_id) {
            video_seconds += asset.duration_seconds;
        }
        interaction_seconds += scene.estimated_interaction_seconds;
        if scene.interaction.is_some() {
            decisions_count += 1;
        }
    }

    let total_seconds = (video_seconds + f64::from(interaction_seconds)).max(1.0);
    let estimated_minutes = ((total_seconds / 60.0).ceil() as u32).max(1);
    let rounded_video_secs = video_seconds.round() as u32;
    let display_label = format!(
        "~{estimated_minutes} min ({rounded_video_secs}s de vídeo + {decisions_count} {})",
        if decisions_count == 1 {
            "decisão"
        } else {
            "decisões"
        }
    );

    CalculatedDurationDto {
        video_seconds: (video_seconds * 1000.0).round() / 1000.0,
        interaction_seconds,
        total_seconds: (total_seconds * 100.0).round() / 100.0,
        estimated_minutes,
        display_label,
    }
}

pub fn status_label_pt(status: &str) -> &'static str {
    match status {
        "COMPLETED" => "Concluída",
        "IN_PROGRESS" => "Em andamento",
        _ => "Não iniciada",
    }
}

pub fn summarize_narrative_progress(
    mission: &MissionSpec,
    doc: Option<&MissionProgressDoc>,
) -> NarrativeProgressSummaryDto {
    let total_scenes_count = mission.scenes.len();
    let first_scene_id = mission
        .scenes
        .first()
        .map(|s| s.scene_id.clone())
        .unwrap_or_default();

    let Some(p) = doc else {
        return NarrativeProgressSummaryDto {
            status: "NOT_STARTED".to_string(),
            status_label_pt: status_label_pt("NOT_STARTED").to_string(),
            current_scene_id: first_scene_id,
            current_scene_order: 1,
            completed_scene_ids: Vec::new(),
            completed_scenes_count: 0,
            total_scenes_count,
            progress_pct: 0.0,
            scene_positions_ms: BTreeMap::new(),
            started_at: None,
            updated_at: None,
            completed_at: None,
            last_resumed_at: None,
            resume_count: 0,
            run_count: 0,
            completed_runs_count: 0,
        };
    };

    let valid_scene_ids: HashSet<&str> =
        mission.scenes.iter().map(|s| s.scene_id.as_str()).collect();
    let completed_scene_ids: Vec<String> = p
        .completed_scene_ids
        .iter()
        .filter(|sid| valid_scene_ids.contains(sid.as_str()))
        .cloned()
        .collect();
    let completed_scenes_count = completed_scene_ids.len().min(total_scenes_count);
    let current_scene_order = mission
        .scenes
        .iter()
        .find(|s| s.scene_id == p.current_scene_id)
        .map(|s| s.order)
        .unwrap_or(1);
    let current_scene_id = mission
        .scenes
        .iter()
        .find(|s| s.order == current_scene_order)
        .map(|s| s.scene_id.clone())
        .unwrap_or(first_scene_id);

    let progress_pct = if p.status == "COMPLETED" {
        100.0
    } else if total_scenes_count > 0 {
        ((completed_scenes_count as f64 / total_scenes_count as f64) * 1000.0).round() / 10.0
    } else {
        0.0
    };

    NarrativeProgressSummaryDto {
        status: p.status.clone(),
        status_label_pt: status_label_pt(&p.status).to_string(),
        current_scene_id,
        current_scene_order,
        completed_scene_ids,
        completed_scenes_count,
        total_scenes_count,
        progress_pct,
        scene_positions_ms: p.scene_positions_ms.clone(),
        started_at: Some(p.started_at.clone()).filter(|s| !s.is_empty()),
        updated_at: Some(p.updated_at.clone()).filter(|s| !s.is_empty()),
        completed_at: p.completed_at.clone(),
        last_resumed_at: p.last_resumed_at.clone(),
        resume_count: p.resume_count,
        run_count: p.run_count,
        completed_runs_count: p.completed_runs_count,
    }
}

pub fn summarize_interaction_performance(
    mission: &MissionSpec,
    doc: Option<&MissionProgressDoc>,
) -> InteractionPerformanceSummaryDto {
    let interactions: Vec<&DidacticInteractionSpec> = mission
        .scenes
        .iter()
        .filter_map(|s| s.interaction.as_ref())
        .collect();
    let total_decisions_count = interactions.len();

    let Some(p) = doc else {
        return InteractionPerformanceSummaryDto {
            answered_decisions_count: 0,
            total_decisions_count,
            first_attempt_correct_count: 0,
            latest_correct_count: 0,
            first_attempt_accuracy_pct: None,
            is_diagnostic_only: true,
            concepts_for_review: Vec::new(),
        };
    };

    let mut answered = 0_usize;
    let mut first_correct = 0_usize;
    let mut latest_correct = 0_usize;
    let mut review_keys = Vec::new();
    let mut seen_review = HashSet::new();

    for inter in interactions {
        if let Some(rec) = p.decisions.get(&inter.interaction_id) {
            answered += 1;
            if rec.first_attempt_correct {
                first_correct += 1;
            } else {
                for key in &inter.revisit_concept_keys {
                    if seen_review.insert(key.clone()) {
                        review_keys.push(key.clone());
                    }
                }
            }
            if rec.latest_correct {
                latest_correct += 1;
            }
        }
    }

    let first_attempt_accuracy_pct = if answered > 0 {
        Some(((first_correct as f64 / answered as f64) * 1000.0).round() / 10.0)
    } else {
        None
    };

    InteractionPerformanceSummaryDto {
        answered_decisions_count: answered,
        total_decisions_count,
        first_attempt_correct_count: first_correct,
        latest_correct_count: latest_correct,
        first_attempt_accuracy_pct,
        is_diagnostic_only: true,
        concepts_for_review: review_keys,
    }
}

pub fn append_bounded_telemetry(
    events: &mut Vec<MissionTelemetryEventDoc>,
    event_type: &str,
    scene_id: Option<&str>,
    detail: Option<&str>,
    now_iso: &str,
) {
    events.push(MissionTelemetryEventDoc {
        event_type: event_type.to_string(),
        scene_id: scene_id.map(str::to_string),
        detail: detail.map(|d| d.chars().take(180).collect()),
        occurred_at: now_iso.to_string(),
    });
    if events.len() > MAX_TELEMETRY_EVENTS_PER_MISSION {
        let excess = events.len() - MAX_TELEMETRY_EVENTS_PER_MISSION;
        events.drain(0..excess);
    }
}

/// Resolves which canonical topic IDs configured in the mission are currently active in the
/// runtime catalog snapshot (`v5` in BCB production or `v2` in synthetic baseline environments).
pub fn resolve_active_mission_topics(curriculum: &CurriculumBindingSpec) -> Vec<String> {
    let snap = catalog_runtime::snapshot();
    let active_refs = snap.references.get(&snap.version);
    let mut subjects_to_check = vec![curriculum.canonical_subject_id.clone()];
    for s in &curriculum.related_subject_ids {
        if !subjects_to_check.contains(s) {
            subjects_to_check.push(s.clone());
        }
    }

    let is_registered_topic = |topic_id: &str| -> bool {
        let Some(refs) = active_refs else {
            return false;
        };
        subjects_to_check
            .iter()
            .any(|subj| refs.active.contains(&(subj.clone(), topic_id.to_string())))
    };

    let mut resolved = Vec::new();
    for tid in &curriculum.primary_topic_ids {
        if is_registered_topic(tid) && !resolved.contains(tid) {
            resolved.push(tid.clone());
        }
    }
    if resolved.is_empty() {
        for tid in &curriculum.fallback_topic_ids {
            if is_registered_topic(tid) && !resolved.contains(tid) {
                resolved.push(tid.clone());
            }
        }
    }
    resolved
}

pub fn has_non_blank_practice(act: &UserActivityDocument) -> bool {
    act.quantidade_praticas > 0 || act.past_sessions.iter().any(|s| !s.is_blank)
}

pub fn summarize_demonstrated_mastery_from_activities(
    available_count: usize,
    resolved_topic_ids: Vec<String>,
    activities: &[UserActivityDocument],
) -> DemonstratedMasterySummaryDto {
    if available_count == 0 {
        return DemonstratedMasterySummaryDto {
            mastery_state: "NO_QUESTIONS_AVAILABLE".to_string(),
            mastery_label_pt: "Sem questões validadas disponíveis neste ambiente".to_string(),
            available_human_questions_count: 0,
            distinct_human_questions_practiced: 0,
            mastered_human_questions_count: 0,
            min_questions_for_mastery: MIN_HUMAN_QUESTIONS_FOR_MASTERY,
            resolved_topic_ids,
            practice_available: false,
        };
    }

    let mut distinct_practiced = 0_usize;
    let mut mastered_count = 0_usize;
    for act in activities {
        if has_non_blank_practice(act) {
            distinct_practiced += 1;
            if act.dominada {
                mastered_count += 1;
            }
        }
    }

    let (mastery_state, mastery_label_pt) = if distinct_practiced == 0 {
        (
            "UNMEASURED".to_string(),
            format!(
                "Não medido em questões independentes (0/{MIN_HUMAN_QUESTIONS_FOR_MASTERY} questões humanas)"
            ),
        )
    } else if distinct_practiced < MIN_HUMAN_QUESTIONS_FOR_MASTERY
        || mastered_count < MIN_HUMAN_QUESTIONS_FOR_MASTERY
    {
        (
            "EVIDENCE_IN_PROGRESS".to_string(),
            format!(
                "Evidência em construção ({distinct_practiced}/{MIN_HUMAN_QUESTIONS_FOR_MASTERY} questões humanas praticadas)"
            ),
        )
    } else {
        (
            "VERIFIED_INDEPENDENT".to_string(),
            format!(
                "Domínio independente verificado ({mastered_count} questões humanas dominadas)"
            ),
        )
    };

    DemonstratedMasterySummaryDto {
        mastery_state,
        mastery_label_pt,
        available_human_questions_count: available_count,
        distinct_human_questions_practiced: distinct_practiced,
        mastered_human_questions_count: mastered_count,
        min_questions_for_mastery: MIN_HUMAN_QUESTIONS_FOR_MASTERY,
        resolved_topic_ids,
        practice_available: true,
    }
}

pub async fn compute_demonstrated_mastery(
    state: &AppState,
    user_id: i64,
    curriculum: &CurriculumBindingSpec,
) -> Result<DemonstratedMasterySummaryDto, AppError> {
    let resolved_topic_ids = resolve_active_mission_topics(curriculum);

    let mut candidate_questions = if !resolved_topic_ids.is_empty() {
        selection_v2::pool(
            state,
            user_id,
            doc! { "canonical_topic_id": { "$in": &resolved_topic_ids } },
            300,
        )
        .await?
    } else {
        Vec::new()
    };

    if candidate_questions.is_empty() {
        let mut fallback_subjects = vec![curriculum.canonical_subject_id.clone()];
        for rel in &curriculum.related_subject_ids {
            if !fallback_subjects.contains(rel) {
                fallback_subjects.push(rel.clone());
            }
        }
        candidate_questions = selection_v2::pool(
            state,
            user_id,
            doc! { "canonical_subject_id": { "$in": fallback_subjects } },
            300,
        )
        .await?;
    }

    let available_count = candidate_questions.len();
    if available_count == 0 {
        return Ok(summarize_demonstrated_mastery_from_activities(
            0,
            resolved_topic_ids,
            &[],
        ));
    }

    let eligible_ids: Vec<ObjectId> = candidate_questions.iter().map(|q| q.id).collect();
    let activities: Vec<UserActivityDocument> = state
        .db
        .collection::<UserActivityDocument>("userActivityLog")
        .find(doc! {
            "user_id": user_id,
            "question_id": { "$in": eligible_ids },
            "quantidade_praticas": { "$gt": 0 }
        })
        .await?
        .try_collect()
        .await?;

    Ok(summarize_demonstrated_mastery_from_activities(
        available_count,
        resolved_topic_ids,
        &activities,
    ))
}

pub async fn fetch_related_human_questions(
    state: &AppState,
    user_id: i64,
    curriculum: &CurriculumBindingSpec,
    limit: usize,
) -> Result<(Vec<String>, usize, Vec<QuestionDto>), AppError> {
    let capped_limit = limit.clamp(1, 20);
    let resolved_topic_ids = resolve_active_mission_topics(curriculum);

    let mut pool_docs = if !resolved_topic_ids.is_empty() {
        selection_v2::pool(
            state,
            user_id,
            doc! { "canonical_topic_id": { "$in": &resolved_topic_ids } },
            200,
        )
        .await?
    } else {
        Vec::new()
    };

    if pool_docs.is_empty() {
        let mut fallback_subjects = vec![curriculum.canonical_subject_id.clone()];
        for rel in &curriculum.related_subject_ids {
            if !fallback_subjects.contains(rel) {
                fallback_subjects.push(rel.clone());
            }
        }
        pool_docs = selection_v2::pool(
            state,
            user_id,
            doc! { "canonical_subject_id": { "$in": fallback_subjects } },
            200,
        )
        .await?;
    }

    let total_available = pool_docs.len();
    if total_available == 0 {
        return Ok((resolved_topic_ids, 0, Vec::new()));
    }

    // Prioritize unanswered human questions first so post-mission practice measures genuine transfer
    let candidate_ids: Vec<ObjectId> = pool_docs.iter().map(|q| q.id).collect();
    let mut act_cursor = state
        .db
        .collection::<Document>("userActivityLog")
        .find(doc! {
            "user_id": user_id,
            "question_id": { "$in": candidate_ids },
            "quantidade_praticas": { "$gt": 0 }
        })
        .projection(doc! { "question_id": 1, "dominada": 1 })
        .await?;

    let mut attempted = HashSet::new();
    let mut mastered = HashSet::new();
    while let Some(d) = act_cursor.try_next().await? {
        if let Ok(qid) = d.get_object_id("question_id") {
            attempted.insert(qid);
            if d.get_bool("dominada").unwrap_or(false) {
                mastered.insert(qid);
            }
        }
    }

    pool_docs.sort_by_key(|q| {
        if !attempted.contains(&q.id) {
            0_u8
        } else if !mastered.contains(&q.id) {
            1_u8
        } else {
            2_u8
        }
    });
    pool_docs.truncate(capped_limit);

    let dtos = enrich_questions_with_user_stats(&state.db, user_id, pool_docs).await?;
    Ok((resolved_topic_ids, total_available, dtos))
}

pub struct ResolvedCatalog {
    pub careers: Vec<CareerSpec>,
    pub assets: HashMap<String, MediaAssetSpec>,
    pub characters: HashMap<String, CharacterSpec>,
}

impl ResolvedCatalog {
    pub fn find_mission(
        &self,
        mission_id: &str,
    ) -> Option<(&CareerSpec, &DisciplineSpec, &SeasonSpec, &MissionSpec)> {
        for career in &self.careers {
            if career.status == "ARCHIVED" {
                continue;
            }
            for disc in &career.disciplines {
                for season in &disc.seasons {
                    for mission in &season.missions {
                        if mission.mission_id == mission_id
                            && mission.publication_state == "PUBLISHED"
                        {
                            return Some((career, disc, season, mission));
                        }
                    }
                }
            }
        }
        None
    }
}

pub fn apply_mission_definition_override(
    careers: &mut [CareerSpec],
    assets: &mut HashMap<String, MediaAssetSpec>,
    characters: &mut HashMap<String, CharacterSpec>,
    doc: &Document,
) {
    if let Ok(extra_assets) = doc.get_array("media_assets") {
        for a in extra_assets {
            if let Ok(parsed) = bson::from_bson::<MediaAssetSpec>(a.clone()) {
                assets.insert(parsed.asset_id.clone(), parsed);
            }
        }
    }
    if let Ok(extra_chars) = doc.get_array("characters") {
        for c in extra_chars {
            if let Ok(parsed) = bson::from_bson::<CharacterSpec>(c.clone()) {
                characters.insert(parsed.character_id.clone(), parsed);
            }
        }
    }
    if let (Ok(career_id), Ok(discipline_id), Ok(season_id), Ok(mission_bson)) = (
        doc.get_str("career_id"),
        doc.get_str("discipline_id"),
        doc.get_str("season_id"),
        doc.get("mission").ok_or(()),
    ) {
        if let Ok(mission) = bson::from_bson::<MissionSpec>(mission_bson.clone()) {
            for career in careers.iter_mut() {
                if career.career_id == career_id {
                    for disc in &mut career.disciplines {
                        if disc.discipline_id == discipline_id {
                            for season in &mut disc.seasons {
                                if season.season_id == season_id {
                                    if let Some(existing) = season
                                        .missions
                                        .iter_mut()
                                        .find(|m| m.mission_id == mission.mission_id)
                                    {
                                        if mission.version >= existing.version {
                                            *existing = mission.clone();
                                        }
                                    } else {
                                        season.missions.push(mission.clone());
                                        season.missions.sort_by_key(|m| m.mission_number);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

pub async fn load_effective_catalog(state: &AppState) -> Result<ResolvedCatalog, AppError> {
    let base = bundled_catalog();
    let mut careers = base.careers.clone();
    let mut assets: HashMap<String, MediaAssetSpec> = base
        .media_assets
        .iter()
        .cloned()
        .map(|a| (a.asset_id.clone(), a))
        .collect();
    let mut characters: HashMap<String, CharacterSpec> = base
        .characters
        .iter()
        .cloned()
        .map(|c| (c.character_id.clone(), c))
        .collect();

    // Allow declarative extension and asset/mission overrides from MongoDB `mission_definitions`
    if let Ok(mut cursor) = state
        .db
        .collection::<Document>("mission_definitions")
        .find(doc! {
            "$or": [
                { "publication_state": "PUBLISHED" },
                { "mission.publication_state": "PUBLISHED" }
            ]
        })
        .limit(100)
        .await
    {
        while let Ok(Some(doc)) = cursor.try_next().await {
            apply_mission_definition_override(&mut careers, &mut assets, &mut characters, &doc);
        }
    }

    Ok(ResolvedCatalog {
        careers,
        assets,
        characters,
    })
}

pub async fn load_user_progress_map(
    state: &AppState,
    user_id: i64,
) -> Result<HashMap<String, MissionProgressDoc>, AppError> {
    let mut cursor = state
        .db
        .collection::<MissionProgressDoc>("mission_progress")
        .find(doc! { "user_id": user_id })
        .await?;
    let mut map = HashMap::new();
    while let Some(doc) = cursor.try_next().await? {
        map.insert(doc.mission_id.clone(), doc);
    }
    Ok(map)
}

pub async fn load_single_progress(
    state: &AppState,
    user_id: i64,
    mission_id: &str,
) -> Result<Option<MissionProgressDoc>, AppError> {
    state
        .db
        .collection::<MissionProgressDoc>("mission_progress")
        .find_one(doc! { "user_id": user_id, "mission_id": mission_id })
        .await
        .map_err(Into::into)
}

pub async fn mutate_progress_cas<F>(
    state: &AppState,
    user_id: i64,
    mission: &MissionSpec,
    mutator: F,
) -> Result<MissionProgressDoc, AppError>
where
    F: Fn(&mut MissionProgressDoc) -> Result<(), AppError>,
{
    let coll = question_storage::collection(&state.db, "mission_progress");
    for _ in 0..10 {
        let existing = load_single_progress(state, user_id, &mission.mission_id).await?;
        let (mut draft, is_insert) = match existing {
            Some(d) => (d, false),
            None => (new_initial_progress(user_id, mission), true),
        };
        let expected_rev = draft.revision;
        mutator(&mut draft)?;
        draft.revision = expected_rev.saturating_add(1);
        let encoded = bson::to_document(&draft).map_err(|e| {
            AppError::Internal(format!("Falha ao serializar progresso da missão: {e}"))
        })?;

        if is_insert {
            match coll.insert_one(encoded).await {
                Ok(_) => return Ok(draft),
                Err(e) if e.to_string().contains("E11000") || e.to_string().contains("11000") => {
                    continue;
                }
                Err(e) => return Err(e.into()),
            }
        } else {
            let filter = if expected_rev == 0 {
                doc! {
                    "user_id": user_id,
                    "mission_id": &mission.mission_id,
                    "$or": [
                        { "revision": 0_i64 },
                        { "revision": { "$exists": false } }
                    ]
                }
            } else {
                doc! {
                    "user_id": user_id,
                    "mission_id": &mission.mission_id,
                    "revision": expected_rev,
                }
            };
            let res = coll.update_one(filter, doc! { "$set": encoded }).await?;
            if res.matched_count == 1 {
                return Ok(draft);
            }
        }
    }
    Err(AppError::Conflict(
        "Conflito concorrente ao atualizar progresso da missão; tente novamente.".into(),
    ))
}

pub fn build_student_scenes(
    mission: &MissionSpec,
    assets: &HashMap<String, MediaAssetSpec>,
    characters: &HashMap<String, CharacterSpec>,
    media_dir: &Path,
    progress: Option<&MissionProgressDoc>,
) -> Vec<StudentSceneDto> {
    let completed_set: HashSet<&str> = progress
        .map(|p| p.completed_scene_ids.iter().map(String::as_str).collect())
        .unwrap_or_default();

    let scene_by_id: HashMap<&str, &SceneSpec> = mission
        .scenes
        .iter()
        .map(|s| (s.scene_id.as_str(), s))
        .collect();

    mission
        .scenes
        .iter()
        .filter_map(|scene| {
            let asset = assets.get(&scene.asset_id)?;
            let character = characters.get(&scene.character_id)?.clone();
            let available_on_server = resolve_media_path(media_dir, asset).is_some();

            let preload_next_media_url = scene
                .next_scene_id
                .as_deref()
                .and_then(|nid| scene_by_id.get(nid))
                .map(|next_scene| format!("/api/v1/missions/media/{}", next_scene.asset_id));

            let interaction = scene.interaction.as_ref().map(|inter| {
                let recorded = progress.and_then(|p| p.decisions.get(&inter.interaction_id));
                let answered = recorded.map(|rec| {
                    let opt_feedback = inter
                        .options
                        .iter()
                        .find(|o| o.option_id == rec.latest_selected_option_id)
                        .map(|o| o.feedback.clone())
                        .unwrap_or_default();
                    StudentDecisionFeedbackDto {
                        interaction_id: inter.interaction_id.clone(),
                        scene_id: scene.scene_id.clone(),
                        selected_option_id: rec.latest_selected_option_id.clone(),
                        first_selected_option_id: rec.first_selected_option_id.clone(),
                        expected_option_id: inter.expected_option_id.clone(),
                        is_correct: rec.latest_correct,
                        first_attempt_correct: rec.first_attempt_correct,
                        attempt_count: rec.attempt_count,
                        option_feedback: opt_feedback,
                        conceptual_explanation: inter.conceptual_explanation.clone(),
                        revisit_concept_keys: inter.revisit_concept_keys.clone(),
                        answered_at: rec.answered_at.clone(),
                    }
                });

                let options = inter
                    .options
                    .iter()
                    .map(|o| StudentOptionDto {
                        option_id: o.option_id.clone(),
                        label: o.label.clone(),
                        // Reveal per-option rationale only after the student has submitted a decision
                        feedback: if answered.is_some() {
                            Some(o.feedback.clone())
                        } else {
                            None
                        },
                    })
                    .collect();

                StudentInteractionDto {
                    interaction_id: inter.interaction_id.clone(),
                    kind: inter.kind.clone(),
                    is_editorial_question: false,
                    contributes_to_formal_mastery: false,
                    context_lead: inter.context_lead.clone(),
                    prompt: inter.prompt.clone(),
                    ceteris_paribus_note: inter.ceteris_paribus_note.clone(),
                    options,
                    answered,
                }
            });

            Some(StudentSceneDto {
                scene_id: scene.scene_id.clone(),
                order: scene.order,
                title: scene.title.clone(),
                pedagogical_goal: scene.pedagogical_goal.clone(),
                character,
                narrative_context: scene.narrative_context.clone(),
                problem_statement: scene.problem_statement.clone(),
                transcript_pt_br: scene.transcript_pt_br.clone(),
                estimated_interaction_seconds: scene.estimated_interaction_seconds,
                auto_advance_on_video_end: scene.auto_advance_on_video_end,
                requires_decision_to_advance: scene.requires_decision_to_advance,
                next_scene_id: scene.next_scene_id.clone(),
                preload_next_media_url,
                is_completed: completed_set.contains(scene.scene_id.as_str())
                    || progress.is_some_and(|p| p.status == "COMPLETED"),
                media: SceneMediaDto {
                    asset_id: asset.asset_id.clone(),
                    stream_url: format!("/api/v1/missions/media/{}", asset.asset_id),
                    captions_url: format!("/api/v1/missions/captions/{}", asset.asset_id),
                    mime_type: asset.mime_type.clone(),
                    duration_seconds: asset.duration_seconds,
                    width: asset.width,
                    height: asset.height,
                    aspect_ratio: asset.aspect_ratio.clone(),
                    has_embedded_voice: asset.has_embedded_voice,
                    available_on_server,
                    captions: asset.captions.clone(),
                },
                interaction,
                native_visual: scene.native_visual.clone(),
            })
        })
        .collect()
}

pub fn build_worked_concepts_view(
    curriculum: &CurriculumBindingSpec,
    concepts_for_review: &[String],
) -> Vec<WorkedConceptViewDto> {
    let review_set: HashSet<&str> = concepts_for_review.iter().map(String::as_str).collect();
    let snap = catalog_runtime::snapshot();
    let active_refs = snap.references.get(&snap.version);

    curriculum
        .worked_concepts
        .iter()
        .map(|c| {
            let mut active_topics = Vec::new();
            if let Some(refs) = active_refs {
                let mut subjects = vec![c.canonical_subject_id.clone()];
                for fs in &c.fallback_subject_ids {
                    if !subjects.contains(fs) {
                        subjects.push(fs.clone());
                    }
                }
                for tid in &c.canonical_topic_ids {
                    if subjects
                        .iter()
                        .any(|s| refs.active.contains(&(s.clone(), tid.clone())))
                    {
                        active_topics.push(tid.clone());
                    }
                }
            }
            if active_topics.is_empty() {
                active_topics = c.canonical_topic_ids.clone();
            }
            WorkedConceptViewDto {
                concept_key: c.concept_key.clone(),
                label: c.label.clone(),
                summary: c.summary.clone(),
                canonical_subject_id: c.canonical_subject_id.clone(),
                active_topic_ids: active_topics,
                related_scene_ids: c.related_scene_ids.clone(),
                recommended_revisit: review_set.contains(c.concept_key.as_str()),
            }
        })
        .collect()
}

pub fn new_initial_progress(user_id: i64, mission: &MissionSpec) -> MissionProgressDoc {
    let now_iso = Utc::now().to_rfc3339();
    let first_scene = mission
        .scenes
        .first()
        .map(|s| s.scene_id.clone())
        .unwrap_or_default();
    MissionProgressDoc {
        user_id,
        mission_id: mission.mission_id.clone(),
        mission_version: mission.version,
        status: "IN_PROGRESS".to_string(),
        current_scene_id: first_scene.clone(),
        completed_scene_ids: Vec::new(),
        scene_positions_ms: BTreeMap::new(),
        decisions: BTreeMap::new(),
        started_at: now_iso.clone(),
        updated_at: now_iso.clone(),
        completed_at: None,
        last_resumed_at: None,
        resume_count: 0,
        run_count: 1,
        completed_runs_count: 0,
        revision: 0,
        telemetry_events: vec![MissionTelemetryEventDoc {
            event_type: "MISSION_STARTED".to_string(),
            scene_id: Some(first_scene),
            detail: None,
            occurred_at: now_iso,
        }],
    }
}

pub fn admin_overview_payload(catalog: &ResolvedCatalog, media_dir: &Path) -> Value {
    let base = bundled_catalog();
    let mut sorted_assets: Vec<&MediaAssetSpec> = catalog.assets.values().collect();
    sorted_assets.sort_by(|a, b| a.asset_id.cmp(&b.asset_id));

    let assets_report: Vec<Value> = sorted_assets
        .into_iter()
        .map(|a| {
            let resolved = resolve_media_path(media_dir, a);
            json!({
                "asset_id": a.asset_id,
                "relative_file_name": a.relative_file_name,
                "duration_seconds": a.duration_seconds,
                "resolution": format!("{}x{}", a.width, a.height),
                "aspect_ratio": a.aspect_ratio,
                "video_codec": a.video_codec,
                "audio_codec": a.audio_codec,
                "has_embedded_voice": a.has_embedded_voice,
                "size_bytes": a.size_bytes,
                "sha256": a.sha256,
                "available_on_server": resolved.is_some(),
            })
        })
        .collect();

    let mut missions_report = Vec::new();
    for career in &catalog.careers {
        for disc in &career.disciplines {
            for season in &disc.seasons {
                for m in &season.missions {
                    let dur = calculate_mission_duration(m, &catalog.assets);
                    missions_report.push(json!({
                        "mission_id": m.mission_id,
                        "version": m.version,
                        "publication_state": m.publication_state,
                        "career_header": career.display_header,
                        "season_title": season.title,
                        "full_title": m.full_title,
                        "discipline": disc.display_name,
                        "canonical_subject_id": disc.canonical_subject_id,
                        "scenes_count": m.scenes.len(),
                        "interactions_count": m.scenes.iter().filter(|s| s.interaction.is_some()).count(),
                        "duration": dur,
                        "worked_concepts": m.curriculum.worked_concepts.iter().map(|c| &c.label).collect::<Vec<_>>(),
                    }));
                }
            }
        }
    }

    json!({
        "catalog_id": base.catalog_id,
        "catalog_version": base.catalog_version,
        "student_portal_path": "/missoes/",
        "missions": missions_report,
        "media_assets": assets_report,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundled_catalog_validates_and_calculates_real_duration_from_five_scenes() {
        let cat = bundled_catalog();
        assert_eq!(cat.catalog_id, "cogniva-missions");
        assert_eq!(cat.media_assets.len(), 5);
        let career = &cat.careers[0];
        assert_eq!(career.display_header, "Banco Central — Economia e Finanças");
        let season = &career.disciplines[0].seasons[0];
        assert_eq!(season.title, "Temporada 1: Mercado Financeiro");
        let mission = &season.missions[0];
        assert_eq!(mission.full_title, "Missão 01: O Choque dos Juros");
        assert_eq!(mission.scenes.len(), 5);

        let assets: HashMap<String, MediaAssetSpec> = cat
            .media_assets
            .iter()
            .cloned()
            .map(|a| (a.asset_id.clone(), a))
            .collect();
        let dur = calculate_mission_duration(mission, &assets);
        assert!((dur.video_seconds - 48.210).abs() < 0.01);
        assert_eq!(dur.interaction_seconds, 145);
        assert!(dur.display_label.contains("48s de vídeo + 2 decisões"));
    }

    #[test]
    fn http_byte_range_parser_handles_partial_open_suffix_and_invalid_ranges() {
        assert_eq!(parse_http_byte_range(None, 1000), Ok(None));
        assert_eq!(
            parse_http_byte_range(Some("bytes=0-499"), 1000),
            Ok(Some((0, 499)))
        );
        assert_eq!(
            parse_http_byte_range(Some("bytes=500-"), 1000),
            Ok(Some((500, 999)))
        );
        assert_eq!(
            parse_http_byte_range(Some("bytes=500-9999"), 1000),
            Ok(Some((500, 999)))
        );
        assert_eq!(
            parse_http_byte_range(Some("bytes=-200"), 1000),
            Ok(Some((800, 999)))
        );
        assert_eq!(
            parse_http_byte_range(Some("bytes=1000-1001"), 1000),
            Err(UnsatisfiableRange)
        );
        assert_eq!(
            parse_http_byte_range(Some("bytes=500-100"), 1000),
            Err(UnsatisfiableRange)
        );
        assert_eq!(
            parse_http_byte_range(Some("items=0-10"), 1000),
            Err(UnsatisfiableRange)
        );
    }

    #[test]
    fn webvtt_renderer_produces_valid_pt_br_captions_and_hides_unanswered_keys() {
        let asset = find_media_asset("asset-bcb-m01-v03-decisao").expect("asset 3");
        let vtt = render_webvtt(asset);
        assert!(vtt.starts_with("WEBVTT\nKind: captions\nLanguage: pt-BR"));
        assert!(vtt.contains("00:00:00.800 --> 00:00:05.200"));
        assert!(vtt.contains("Se os juros de mercado subirem"));

        let cat = bundled_catalog();
        let mission = &cat.careers[0].disciplines[0].seasons[0].missions[0];
        let assets: HashMap<String, MediaAssetSpec> = cat
            .media_assets
            .iter()
            .cloned()
            .map(|a| (a.asset_id.clone(), a))
            .collect();
        let chars: HashMap<String, CharacterSpec> = cat
            .characters
            .iter()
            .cloned()
            .map(|c| (c.character_id.clone(), c))
            .collect();

        let unanswered = build_student_scenes(mission, &assets, &chars, Path::new("missing"), None);
        let scene3 = &unanswered[2];
        let inter = scene3.interaction.as_ref().unwrap();
        assert!(inter.answered.is_none());
        assert!(inter.options.iter().all(|o| o.feedback.is_none()));
    }

    #[test]
    fn narrative_progress_and_interaction_performance_stay_strictly_separated() {
        let cat = bundled_catalog();
        let mission = &cat.careers[0].disciplines[0].seasons[0].missions[0];
        let mut p = new_initial_progress(77, mission);
        p.completed_scene_ids = vec![
            "cena-01-o-alerta".into(),
            "cena-02-o-problema".into(),
            "cena-03-primeira-decisao".into(),
        ];
        p.current_scene_id = "cena-04-explicacao".into();
        p.decisions.insert(
            "decisao-01-juros-preco-prefixado".into(),
            RecordedDecisionDoc {
                interaction_id: "decisao-01-juros-preco-prefixado".into(),
                scene_id: "cena-03-primeira-decisao".into(),
                first_selected_option_id: "A".into(),
                latest_selected_option_id: "B".into(),
                first_attempt_correct: false,
                latest_correct: true,
                attempt_count: 2,
                answered_at: "2026-11-20T15:00:00Z".into(),
                time_spent_ms: 8200,
                used_transcript_fallback: false,
            },
        );

        let nar = summarize_narrative_progress(mission, Some(&p));
        assert_eq!(nar.status, "IN_PROGRESS");
        assert_eq!(nar.completed_scenes_count, 3);
        assert_eq!(nar.progress_pct, 60.0);

        let perf = summarize_interaction_performance(mission, Some(&p));
        assert!(perf.is_diagnostic_only);
        assert_eq!(perf.answered_decisions_count, 1);
        assert_eq!(perf.first_attempt_correct_count, 0);
        assert_eq!(perf.latest_correct_count, 1);
        assert_eq!(perf.first_attempt_accuracy_pct, Some(0.0));
        assert!(perf
            .concepts_for_review
            .contains(&"relacao_taxas_precos".to_string()));
    }

    #[test]
    fn demonstrated_mastery_ignores_blank_omissions_and_catalog_filters_archived_or_draft_items() {
        use crate::models::activity::{ActivityDates, PastSessionEntry};

        let now = Utc::now();
        let blank_act = UserActivityDocument {
            id: None,
            user_id: 55,
            question_id: ObjectId::new(),
            datas: ActivityDates {
                ultima_sessao: now,
                ultima_revisao: None,
                data_primeira_sessao: now,
                data_primeiro_acerto: None,
            },
            tempos: Default::default(),
            disciplina: "Finanças".into(),
            past_sessions: vec![PastSessionEntry {
                attempt_id: "blank-1".into(),
                data: now,
                status: false,
                alternativa_escolhida: String::new(),
                tempo_gasto_segundos: 5,
                session_uuid: "sim-1".into(),
                modo_sessao: "SIMULADO".into(),
                modo_prova: None,
                concurso: None,
                canonical_subject_id: Some("MFIN".into()),
                canonical_topic_id: Some("MFIN_01_004".into()),
                dias_desde_ultima_sessao: 0,
                dias_desde_ultima_revisao: None,
                percentual_acertos_acumulado: 0.0,
                tempo_medio_acumulado: 0.0,
                nivel_confianca: 0,
                spaced_repetition_interval: 0,
                spaced_repetition_score: 0.0,
                streak_atual: 0,
                points_session: 0.0,
                is_revision: false,
                is_blank: true,
                confidence_explicit: false,
                selected_evidence_id: None,
                position_in_session: None,
                elapsed_session_seconds: Some(40),
                session_interrupted: false,
            }],
            quantidade_praticas: 0,
            quantidade_acertos: 0,
            quantidade_erros: 0,
            percentual_acertos: 0.0,
            percentual_revisoes_sucesso: None,
            var_alternativa_escolhida: 0.0,
            frequencia_estudo_mes: 0.0,
            spaced_repetition_interval: 0,
            spaced_repetition_score: 0.0,
            streak_atual: 0,
            streak_max: 0,
            points: 0.0,
            points_with_decay: 0.0,
            dominada: false,
            nivel_confianca_med_rec: None,
            revisao_necessaria: false,
            in_error_notebook: false,
            high_confidence_error_flag: false,
            user_note: None,
            canonical_subject_id: Some("MFIN".into()),
            canonical_topic_id: Some("MFIN_01_004".into()),
            memory_stability_days: 0.0,
            memory_difficulty: 5.0,
        };

        assert!(!has_non_blank_practice(&blank_act));
        let summary_blank = summarize_demonstrated_mastery_from_activities(
            3,
            vec!["MFIN_01_004".into()],
            std::slice::from_ref(&blank_act),
        );
        assert_eq!(summary_blank.distinct_human_questions_practiced, 0);
        assert_eq!(summary_blank.mastery_state, "UNMEASURED");

        let mut practiced_act = blank_act.clone();
        practiced_act.quantidade_praticas = 1;
        practiced_act.quantidade_acertos = 1;
        practiced_act.past_sessions[0].is_blank = false;
        practiced_act.past_sessions[0].status = true;
        assert!(has_non_blank_practice(&practiced_act));
        let summary_practiced = summarize_demonstrated_mastery_from_activities(
            3,
            vec!["MFIN_01_004".into()],
            &[blank_act, practiced_act],
        );
        assert_eq!(summary_practiced.distinct_human_questions_practiced, 1);
        assert_eq!(summary_practiced.mastery_state, "EVIDENCE_IN_PROGRESS");

        // ResolvedCatalog hides ARCHIVED careers and DRAFT missions, and apply_mission_definition_override updates existing assets
        let base = bundled_catalog();
        let mut careers = base.careers.clone();
        let mut assets: HashMap<String, MediaAssetSpec> = base
            .media_assets
            .iter()
            .cloned()
            .map(|a| (a.asset_id.clone(), a))
            .collect();
        let mut characters: HashMap<String, CharacterSpec> = base
            .characters
            .iter()
            .cloned()
            .map(|c| (c.character_id.clone(), c))
            .collect();

        let mut overridden_asset = assets.get("asset-bcb-m01-v01-alerta").unwrap().clone();
        overridden_asset.sha256 = "f".repeat(64);
        overridden_asset.duration_seconds = 12.5;
        let override_doc = doc! {
            "media_assets": [bson::to_bson(&overridden_asset).unwrap()],
        };
        apply_mission_definition_override(
            &mut careers,
            &mut assets,
            &mut characters,
            &override_doc,
        );
        assert_eq!(
            assets.get("asset-bcb-m01-v01-alerta").unwrap().sha256,
            "f".repeat(64)
        );

        let mut draft_mission = careers[0].disciplines[0].seasons[0].missions[0].clone();
        draft_mission.mission_id = "bcb-draft-hidden".into();
        draft_mission.publication_state = "DRAFT".into();
        careers[0].disciplines[0].seasons[0]
            .missions
            .push(draft_mission);

        let mut archived_career = careers[0].clone();
        archived_career.career_id = "archived-career".into();
        archived_career.status = "ARCHIVED".into();
        archived_career.disciplines[0].seasons[0].missions[0].mission_id =
            "archived-mission".into();
        careers.push(archived_career);

        let resolved = ResolvedCatalog {
            careers,
            assets,
            characters,
        };
        assert!(resolved
            .find_mission("bcb-ef-t1-m01-choque-dos-juros")
            .is_some());
        assert!(resolved.find_mission("bcb-draft-hidden").is_none());
        assert!(resolved.find_mission("archived-mission").is_none());
    }
}
