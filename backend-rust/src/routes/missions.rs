use crate::{
    error::AppError,
    routes::apk_ota::if_none_match_hit,
    security::SessionUser,
    services::missions::{
        self, append_bounded_telemetry, build_student_scenes, build_worked_concepts_view,
        calculate_mission_duration, compute_demonstrated_mastery, fetch_related_human_questions,
        load_effective_catalog, load_single_progress, load_user_progress_map, new_initial_progress,
        parse_http_byte_range, render_webvtt, resolve_media_path, save_progress_doc,
        summarize_interaction_performance, summarize_narrative_progress, RecordedDecisionDoc,
        StudentDecisionFeedbackDto,
    },
    AppState,
};
use axum::{
    body::{Body, Bytes},
    extract::{Path, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Extension, Json,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{io::SeekFrom, sync::Arc};
use tokio::io::{AsyncReadExt, AsyncSeekExt};

const ALLOWED_TELEMETRY_EVENTS: &[&str] = &[
    "VIDEO_STARTED",
    "VIDEO_PAUSED",
    "VIDEO_RESUMED",
    "VIDEO_COMPLETED",
    "VIDEO_ERROR_FALLBACK",
    "TRANSCRIPT_OPENED",
    "CAPTIONS_TOGGLED",
    "SCENE_JUMPED",
];

#[derive(Debug, Default, Deserialize)]
pub struct MissionDetailQuery {
    #[serde(default)]
    pub resume: bool,
}

#[derive(Debug, Default, Deserialize)]
pub struct UpdateMissionProgressRequest {
    pub current_scene_id: Option<String>,
    pub completed_scene_id: Option<String>,
    pub playback_position_ms: Option<i64>,
    pub mark_mission_completed: Option<bool>,
    pub used_transcript_fallback: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct SubmitMissionDecisionRequest {
    pub scene_id: String,
    pub interaction_id: String,
    pub selected_option_id: String,
    #[serde(default)]
    pub time_spent_ms: i64,
    #[serde(default)]
    pub used_transcript_fallback: bool,
}

#[derive(Debug, Deserialize)]
pub struct MissionTelemetryRequest {
    pub event_type: String,
    pub scene_id: Option<String>,
    pub detail: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub struct MissionPracticeQuestionsRequest {
    pub quantity: Option<usize>,
}

#[derive(Debug, Serialize)]
pub struct SubmitMissionDecisionResponse {
    pub decision: StudentDecisionFeedbackDto,
    pub narrative_progress: missions::NarrativeProgressSummaryDto,
    pub interaction_performance: missions::InteractionPerformanceSummaryDto,
    pub worked_concepts: Vec<missions::WorkedConceptViewDto>,
    pub mission_completed: bool,
}

/// `GET /api/v1/missions`: Catálogo hierárquico de Missões enriquecido com progresso individual do aluno.
pub async fn list_missions(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<Value>, AppError> {
    let catalog = load_effective_catalog(&state).await?;
    let progress_map = load_user_progress_map(&state, user.user_id).await?;

    let mut careers_json = Vec::new();
    for career in &catalog.careers {
        if career.status == "ARCHIVED" {
            continue;
        }
        let mut disciplines_json = Vec::new();
        for disc in &career.disciplines {
            let mut seasons_json = Vec::new();
            for season in &disc.seasons {
                let mut missions_json = Vec::new();
                for mission in &season.missions {
                    if mission.publication_state != "PUBLISHED" {
                        continue;
                    }
                    let progress_doc = progress_map.get(&mission.mission_id);
                    let duration = calculate_mission_duration(mission, &catalog.assets);
                    let narrative_progress = summarize_narrative_progress(mission, progress_doc);
                    let interaction_performance =
                        summarize_interaction_performance(mission, progress_doc);
                    let available_videos = mission
                        .scenes
                        .iter()
                        .filter(|s| {
                            catalog
                                .assets
                                .get(&s.asset_id)
                                .and_then(|a| {
                                    resolve_media_path(&state.config.missions_media_dir, a)
                                })
                                .is_some()
                        })
                        .count();

                    missions_json.push(json!({
                        "mission_id": mission.mission_id,
                        "mission_number": mission.mission_number,
                        "version": mission.version,
                        "display_code": mission.display_code,
                        "title": mission.title,
                        "full_title": mission.full_title,
                        "summary": mission.summary,
                        "description": mission.description,
                        "contest_name": mission.curriculum.contest_name,
                        "contest_template_id": mission.curriculum.contest_template_id,
                        "role_name": mission.curriculum.role_name,
                        "discipline_label": mission.curriculum.discipline_label,
                        "theme_label": mission.curriculum.theme_label,
                        "canonical_subject_id": mission.curriculum.canonical_subject_id,
                        "worked_concepts": mission.curriculum.worked_concepts.iter().map(|c| json!({
                            "concept_key": c.concept_key,
                            "label": c.label,
                        })).collect::<Vec<_>>(),
                        "duration": duration,
                        "scenes_count": mission.scenes.len(),
                        "available_videos_count": available_videos,
                        "narrative_progress": narrative_progress,
                        "interaction_performance": interaction_performance,
                    }));
                }
                if !missions_json.is_empty() {
                    seasons_json.push(json!({
                        "season_id": season.season_id,
                        "season_number": season.season_number,
                        "title": season.title,
                        "theme": season.theme,
                        "summary": season.summary,
                        "missions": missions_json,
                    }));
                }
            }
            if !seasons_json.is_empty() {
                disciplines_json.push(json!({
                    "discipline_id": disc.discipline_id,
                    "canonical_subject_id": disc.canonical_subject_id,
                    "exam_component_id": disc.exam_component_id,
                    "display_name": disc.display_name,
                    "seasons": seasons_json,
                }));
            }
        }
        if !disciplines_json.is_empty() {
            careers_json.push(json!({
                "career_id": career.career_id,
                "trunk_id": career.trunk_id,
                "contest_template_id": career.contest_template_id,
                "display_header": career.display_header,
                "institution": career.institution,
                "role": career.role,
                "disciplines": disciplines_json,
            }));
        }
    }

    Ok(Json(json!({
        "catalog_version": missions::bundled_catalog().catalog_version,
        "careers": careers_json,
    })))
}

/// `GET /api/v1/missions/:mission_id`: Detalhe completo da missão, cenas, progresso e evidência independente.
pub async fn get_mission_detail(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(mission_id): Path<String>,
    Query(query): Query<MissionDetailQuery>,
) -> Result<Json<Value>, AppError> {
    let catalog = load_effective_catalog(&state).await?;
    let Some((career, disc, season, mission)) = catalog.find_mission(mission_id.trim()) else {
        return Err(AppError::NotFound("Missão não encontrada".into()));
    };

    let mut progress_doc = load_single_progress(&state, user.user_id, &mission.mission_id).await?;
    if query.resume {
        if let Some(ref mut doc) = progress_doc {
            if doc.status == "IN_PROGRESS" {
                let now_iso = Utc::now().to_rfc3339();
                doc.resume_count = doc.resume_count.saturating_add(1);
                doc.last_resumed_at = Some(now_iso.clone());
                doc.updated_at = now_iso.clone();
                let current_sid = doc.current_scene_id.clone();
                append_bounded_telemetry(
                    &mut doc.telemetry_events,
                    "MISSION_RESUMED",
                    Some(&current_sid),
                    None,
                    &now_iso,
                );
                save_progress_doc(&state, doc).await?;
            }
        }
    }

    let duration = calculate_mission_duration(mission, &catalog.assets);
    let narrative_progress = summarize_narrative_progress(mission, progress_doc.as_ref());
    let interaction_performance =
        summarize_interaction_performance(mission, progress_doc.as_ref());
    let worked_concepts = build_worked_concepts_view(
        &mission.curriculum,
        &interaction_performance.concepts_for_review,
    );
    let scenes = build_student_scenes(
        mission,
        &catalog.assets,
        &catalog.characters,
        &state.config.missions_media_dir,
        progress_doc.as_ref(),
    );
    let demonstrated_mastery =
        compute_demonstrated_mastery(&state, user.user_id, &mission.curriculum).await?;

    Ok(Json(json!({
        "career": {
            "career_id": career.career_id,
            "display_header": career.display_header,
            "institution": career.institution,
            "role": career.role,
            "trunk_id": career.trunk_id,
            "contest_template_id": career.contest_template_id,
        },
        "discipline": {
            "discipline_id": disc.discipline_id,
            "display_name": disc.display_name,
            "canonical_subject_id": disc.canonical_subject_id,
            "exam_component_id": disc.exam_component_id,
        },
        "season": {
            "season_id": season.season_id,
            "season_number": season.season_number,
            "title": season.title,
            "theme": season.theme,
        },
        "mission": {
            "mission_id": mission.mission_id,
            "mission_number": mission.mission_number,
            "version": mission.version,
            "display_code": mission.display_code,
            "title": mission.title,
            "full_title": mission.full_title,
            "summary": mission.summary,
            "description": mission.description,
            "discipline_label": mission.curriculum.discipline_label,
            "theme_label": mission.curriculum.theme_label,
            "duration": duration,
            "curriculum": {
                "contest_name": mission.curriculum.contest_name,
                "contest_template_id": mission.curriculum.contest_template_id,
                "role_name": mission.curriculum.role_name,
                "exam_component_id": mission.curriculum.exam_component_id,
                "discipline_label": mission.curriculum.discipline_label,
                "theme_label": mission.curriculum.theme_label,
                "canonical_subject_id": mission.curriculum.canonical_subject_id,
                "primary_topic_ids": mission.curriculum.primary_topic_ids,
                "learning_evidence_policy": mission.curriculum.learning_evidence_policy,
            },
            "worked_concepts": worked_concepts,
            "scenes": scenes,
            "conclusion": mission.conclusion,
        },
        "narrative_progress": narrative_progress,
        "interaction_performance": interaction_performance,
        "demonstrated_mastery": demonstrated_mastery,
    })))
}

/// `POST /api/v1/missions/:mission_id/progress`: Atualiza cena atual, cenas concluídas e retomada.
pub async fn update_mission_progress(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(mission_id): Path<String>,
    Json(req): Json<UpdateMissionProgressRequest>,
) -> Result<Json<Value>, AppError> {
    let catalog = load_effective_catalog(&state).await?;
    let Some((_, _, _, mission)) = catalog.find_mission(mission_id.trim()) else {
        return Err(AppError::NotFound("Missão não encontrada".into()));
    };

    let now_iso = Utc::now().to_rfc3339();
    let mut doc = match load_single_progress(&state, user.user_id, &mission.mission_id).await? {
        Some(existing) => existing,
        None => new_initial_progress(user.user_id, mission),
    };
    doc.updated_at = now_iso.clone();

    if let Some(ref completed_sid) = req.completed_scene_id {
        let Some(scene_spec) = mission.scenes.iter().find(|s| &s.scene_id == completed_sid) else {
            return Err(AppError::BadRequest("Cena informada não pertence à missão".into()));
        };
        if scene_spec.requires_decision_to_advance {
            let answered = scene_spec
                .interaction
                .as_ref()
                .is_some_and(|inter| doc.decisions.contains_key(&inter.interaction_id));
            if !answered {
                return Err(AppError::conflict_code(
                    "DECISION_REQUIRED_BEFORE_ADVANCE",
                    Some("completed_scene_id"),
                    "Responda à decisão desta cena antes de concluí-la.",
                ));
            }
        }
        if !doc.completed_scene_ids.contains(completed_sid) {
            doc.completed_scene_ids.push(completed_sid.clone());
        }
        let evt = if req.used_transcript_fallback == Some(true) {
            "SCENE_COMPLETED_VIA_TRANSCRIPT"
        } else {
            "SCENE_COMPLETED"
        };
        append_bounded_telemetry(
            &mut doc.telemetry_events,
            evt,
            Some(completed_sid),
            None,
            &now_iso,
        );
        if req.current_scene_id.is_none() {
            if let Some(ref next_sid) = scene_spec.next_scene_id {
                doc.current_scene_id = next_sid.clone();
            }
        }
    }

    if let Some(ref target_sid) = req.current_scene_id {
        let Some(target_spec) = mission.scenes.iter().find(|s| &s.scene_id == target_sid) else {
            return Err(AppError::BadRequest("Cena atual inválida para esta missão".into()));
        };
        if doc.status != "COMPLETED" {
            for prior in mission.scenes.iter().filter(|s| s.order < target_spec.order) {
                if prior.requires_decision_to_advance {
                    let answered = prior
                        .interaction
                        .as_ref()
                        .is_some_and(|inter| doc.decisions.contains_key(&inter.interaction_id));
                    if !answered {
                        return Err(AppError::conflict_code(
                            "DECISION_REQUIRED_BEFORE_ADVANCE",
                            Some("current_scene_id"),
                            "Responda às decisões obrigatórias anteriores antes de avançar.",
                        ));
                    }
                }
            }
        }
        doc.current_scene_id = target_sid.clone();
        if let Some(pos) = req.playback_position_ms {
            doc.scene_positions_ms
                .insert(target_sid.clone(), pos.clamp(0, 3_600_000));
        }
        append_bounded_telemetry(
            &mut doc.telemetry_events,
            "SCENE_VIEWED",
            Some(target_sid),
            None,
            &now_iso,
        );
    } else if let Some(pos) = req.playback_position_ms {
        let cur = doc.current_scene_id.clone();
        doc.scene_positions_ms
            .insert(cur, pos.clamp(0, 3_600_000));
    }

    if req.mark_mission_completed == Some(true) {
        for scene in &mission.scenes {
            if scene.requires_decision_to_advance {
                let answered = scene
                    .interaction
                    .as_ref()
                    .is_some_and(|i| doc.decisions.contains_key(&i.interaction_id));
                if !answered {
                    return Err(AppError::conflict_code(
                        "DECISION_REQUIRED_BEFORE_COMPLETION",
                        Some("mark_mission_completed"),
                        "Responda às decisões obrigatórias da missão antes de concluí-la.",
                    ));
                }
            }
        }
        for scene in &mission.scenes {
            if !doc.completed_scene_ids.contains(&scene.scene_id) {
                doc.completed_scene_ids.push(scene.scene_id.clone());
            }
        }
        if doc.status != "COMPLETED" {
            doc.status = "COMPLETED".to_string();
            doc.completed_at = Some(now_iso.clone());
            doc.completed_runs_count = doc.completed_runs_count.saturating_add(1);
            append_bounded_telemetry(
                &mut doc.telemetry_events,
                "MISSION_COMPLETED",
                Some(&doc.current_scene_id.clone()),
                None,
                &now_iso,
            );
        }
    } else if doc.status == "NOT_STARTED" {
        doc.status = "IN_PROGRESS".to_string();
    }

    save_progress_doc(&state, &doc).await?;

    let narrative_progress = summarize_narrative_progress(mission, Some(&doc));
    let interaction_performance = summarize_interaction_performance(mission, Some(&doc));
    Ok(Json(json!({
        "narrative_progress": narrative_progress,
        "interaction_performance": interaction_performance,
    })))
}

/// `POST /api/v1/missions/:mission_id/decisions`: Avalia e persiste no servidor uma decisão narrativa.
pub async fn submit_mission_decision(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(mission_id): Path<String>,
    Json(req): Json<SubmitMissionDecisionRequest>,
) -> Result<Json<SubmitMissionDecisionResponse>, AppError> {
    let catalog = load_effective_catalog(&state).await?;
    let Some((_, _, _, mission)) = catalog.find_mission(mission_id.trim()) else {
        return Err(AppError::NotFound("Missão não encontrada".into()));
    };

    let Some(scene) = mission
        .scenes
        .iter()
        .find(|s| s.scene_id == req.scene_id.trim())
    else {
        return Err(AppError::BadRequest("Cena não encontrada nesta missão".into()));
    };
    let Some(interaction) = scene
        .interaction
        .as_ref()
        .filter(|i| i.interaction_id == req.interaction_id.trim())
    else {
        return Err(AppError::BadRequest(
            "Interação não encontrada para a cena informada".into(),
        ));
    };

    let selected_clean = req.selected_option_id.trim().to_uppercase();
    let Some(chosen_option) = interaction
        .options
        .iter()
        .find(|o| o.option_id == selected_clean)
    else {
        return Err(AppError::BadRequest(
            "Alternativa selecionada não pertence a esta decisão".into(),
        ));
    };

    let is_correct = chosen_option.option_id == interaction.expected_option_id;
    let now_iso = Utc::now().to_rfc3339();

    let mut doc = match load_single_progress(&state, user.user_id, &mission.mission_id).await? {
        Some(existing) => existing,
        None => new_initial_progress(user.user_id, mission),
    };
    doc.updated_at = now_iso.clone();
    doc.current_scene_id = scene.scene_id.clone();

    let recorded = match doc.decisions.get(&interaction.interaction_id).cloned() {
        Some(prev) => RecordedDecisionDoc {
            interaction_id: interaction.interaction_id.clone(),
            scene_id: scene.scene_id.clone(),
            first_selected_option_id: prev.first_selected_option_id,
            latest_selected_option_id: chosen_option.option_id.clone(),
            first_attempt_correct: prev.first_attempt_correct,
            latest_correct: is_correct,
            attempt_count: prev.attempt_count.saturating_add(1),
            answered_at: now_iso.clone(),
            time_spent_ms: req.time_spent_ms.clamp(0, 3_600_000),
            used_transcript_fallback: req.used_transcript_fallback,
        },
        None => RecordedDecisionDoc {
            interaction_id: interaction.interaction_id.clone(),
            scene_id: scene.scene_id.clone(),
            first_selected_option_id: chosen_option.option_id.clone(),
            latest_selected_option_id: chosen_option.option_id.clone(),
            first_attempt_correct: is_correct,
            latest_correct: is_correct,
            attempt_count: 1,
            answered_at: now_iso.clone(),
            time_spent_ms: req.time_spent_ms.clamp(0, 3_600_000),
            used_transcript_fallback: req.used_transcript_fallback,
        },
    };
    doc.decisions
        .insert(interaction.interaction_id.clone(), recorded.clone());

    if !doc.completed_scene_ids.contains(&scene.scene_id) {
        doc.completed_scene_ids.push(scene.scene_id.clone());
    }

    append_bounded_telemetry(
        &mut doc.telemetry_events,
        "DECISION_SUBMITTED",
        Some(&scene.scene_id),
        Some(&format!(
            "{}:{} ({})",
            interaction.interaction_id,
            chosen_option.option_id,
            if is_correct { "correta" } else { "revisar" }
        )),
        &now_iso,
    );

    // If this is the terminal scene and all required decisions are answered, mark mission completed!
    if scene.next_scene_id.is_none() {
        let all_required_answered = mission
            .scenes
            .iter()
            .filter(|s| s.requires_decision_to_advance)
            .all(|s| {
                s.interaction
                    .as_ref()
                    .is_some_and(|i| doc.decisions.contains_key(&i.interaction_id))
            });
        if all_required_answered {
            for s in &mission.scenes {
                if !doc.completed_scene_ids.contains(&s.scene_id) {
                    doc.completed_scene_ids.push(s.scene_id.clone());
                }
            }
            if doc.status != "COMPLETED" {
                doc.status = "COMPLETED".to_string();
                doc.completed_at = Some(now_iso.clone());
                doc.completed_runs_count = doc.completed_runs_count.saturating_add(1);
                append_bounded_telemetry(
                    &mut doc.telemetry_events,
                    "MISSION_COMPLETED",
                    Some(&scene.scene_id),
                    None,
                    &now_iso,
                );
            }
        }
    }

    save_progress_doc(&state, &doc).await?;

    let narrative_progress = summarize_narrative_progress(mission, Some(&doc));
    let interaction_performance = summarize_interaction_performance(mission, Some(&doc));
    let worked_concepts = build_worked_concepts_view(
        &mission.curriculum,
        &interaction_performance.concepts_for_review,
    );
    let mission_completed = doc.status == "COMPLETED";

    Ok(Json(SubmitMissionDecisionResponse {
        decision: StudentDecisionFeedbackDto {
            interaction_id: interaction.interaction_id.clone(),
            scene_id: scene.scene_id.clone(),
            selected_option_id: recorded.latest_selected_option_id,
            first_selected_option_id: recorded.first_selected_option_id,
            expected_option_id: interaction.expected_option_id.clone(),
            is_correct: recorded.latest_correct,
            first_attempt_correct: recorded.first_attempt_correct,
            attempt_count: recorded.attempt_count,
            option_feedback: chosen_option.feedback.clone(),
            conceptual_explanation: interaction.conceptual_explanation.clone(),
            revisit_concept_keys: interaction.revisit_concept_keys.clone(),
            answered_at: recorded.answered_at,
        },
        narrative_progress,
        interaction_performance,
        worked_concepts,
        mission_completed,
    }))
}

/// `POST /api/v1/missions/:mission_id/repeat`: Reinicia a execução ativa da missão preservando o contador histórico.
pub async fn repeat_mission(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(mission_id): Path<String>,
) -> Result<Json<Value>, AppError> {
    let catalog = load_effective_catalog(&state).await?;
    let Some((_, _, _, mission)) = catalog.find_mission(mission_id.trim()) else {
        return Err(AppError::NotFound("Missão não encontrada".into()));
    };

    let now_iso = Utc::now().to_rfc3339();
    let first_scene = mission
        .scenes
        .first()
        .map(|s| s.scene_id.clone())
        .unwrap_or_default();

    let mut doc = match load_single_progress(&state, user.user_id, &mission.mission_id).await? {
        Some(mut existing) => {
            existing.status = "IN_PROGRESS".to_string();
            existing.current_scene_id = first_scene.clone();
            existing.completed_scene_ids.clear();
            existing.scene_positions_ms.clear();
            existing.decisions.clear();
            existing.updated_at = now_iso.clone();
            existing.run_count = existing.run_count.saturating_add(1);
            append_bounded_telemetry(
                &mut existing.telemetry_events,
                "MISSION_REPEATED",
                Some(&first_scene),
                None,
                &now_iso,
            );
            existing
        }
        None => new_initial_progress(user.user_id, mission),
    };

    if doc.run_count < 1 {
        doc.run_count = 1;
    }
    save_progress_doc(&state, &doc).await?;

    let narrative_progress = summarize_narrative_progress(mission, Some(&doc));
    let interaction_performance = summarize_interaction_performance(mission, Some(&doc));
    Ok(Json(json!({
        "narrative_progress": narrative_progress,
        "interaction_performance": interaction_performance,
    })))
}

/// `POST /api/v1/missions/:mission_id/telemetry`: Registra evento proporcional de usabilidade/reprodução.
pub async fn record_mission_telemetry(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(mission_id): Path<String>,
    Json(req): Json<MissionTelemetryRequest>,
) -> Result<Json<Value>, AppError> {
    let event_clean = req.event_type.trim().to_uppercase();
    if !ALLOWED_TELEMETRY_EVENTS.contains(&event_clean.as_str()) {
        return Err(AppError::BadRequest(
            "Tipo de evento de telemetria inválido".into(),
        ));
    }

    let catalog = load_effective_catalog(&state).await?;
    let Some((_, _, _, mission)) = catalog.find_mission(mission_id.trim()) else {
        return Err(AppError::NotFound("Missão não encontrada".into()));
    };

    if let Some(ref sid) = req.scene_id {
        if !mission.scenes.iter().any(|s| &s.scene_id == sid) {
            return Err(AppError::BadRequest("Cena inválida no evento".into()));
        }
    }

    let now_iso = Utc::now().to_rfc3339();
    let mut doc = match load_single_progress(&state, user.user_id, &mission.mission_id).await? {
        Some(existing) => existing,
        None => new_initial_progress(user.user_id, mission),
    };
    doc.updated_at = now_iso.clone();
    append_bounded_telemetry(
        &mut doc.telemetry_events,
        &event_clean,
        req.scene_id.as_deref(),
        req.detail.as_deref(),
        &now_iso,
    );
    save_progress_doc(&state, &doc).await?;

    Ok(Json(json!({ "recorded": true })))
}

/// `POST /api/v1/missions/:mission_id/practice-questions`:
/// Busca exclusivamente questões humanas reais (`CONFIRMED` + `CURRENT`) vinculadas aos assuntos da missão.
pub async fn get_mission_practice_questions(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(mission_id): Path<String>,
    body: Option<Json<MissionPracticeQuestionsRequest>>,
) -> Result<Json<Value>, AppError> {
    let catalog = load_effective_catalog(&state).await?;
    let Some((_, _, _, mission)) = catalog.find_mission(mission_id.trim()) else {
        return Err(AppError::NotFound("Missão não encontrada".into()));
    };

    let quantity = body.and_then(|b| b.0.quantity).unwrap_or(5).clamp(1, 15);
    let (resolved_topic_ids, available_count, questions) =
        fetch_related_human_questions(&state, user.user_id, &mission.curriculum, quantity).await?;

    if let Some(mut doc) = load_single_progress(&state, user.user_id, &mission.mission_id).await? {
        let now_iso = Utc::now().to_rfc3339();
        append_bounded_telemetry(
            &mut doc.telemetry_events,
            "PRACTICE_QUESTIONS_OPENED",
            None,
            Some(&format!("available={available_count}")),
            &now_iso,
        );
        doc.updated_at = now_iso;
        let _ = save_progress_doc(&state, &doc).await;
    }

    let empty_state_message = if questions.is_empty() {
        Some(
            "Nenhuma questão humana validada disponível no acervo atual para os assuntos desta missão."
                .to_string(),
        )
    } else {
        None
    };

    Ok(Json(json!({
        "mission_id": mission.mission_id,
        "canonical_subject_id": mission.curriculum.canonical_subject_id,
        "discipline_label": mission.curriculum.discipline_label,
        "resolved_topic_ids": resolved_topic_ids,
        "available_count": available_count,
        "questions": questions,
        "empty_state_message": empty_state_message,
    })))
}

/// `GET /api/v1/admin/missions`: Visão administrativa do catálogo de Missões e integridade dos ativos locais.
pub async fn admin_missions_overview(
    State(state): State<Arc<AppState>>,
) -> Result<Json<Value>, AppError> {
    Ok(Json(missions::admin_overview_payload(
        &state.config.missions_media_dir,
    )))
}

async fn resolve_media_asset_fast(
    state: &AppState,
    clean_id: &str,
) -> Result<Option<missions::MediaAssetSpec>, AppError> {
    if let Some(static_asset) = missions::find_media_asset(clean_id) {
        return Ok(Some(static_asset.clone()));
    }
    let catalog = load_effective_catalog(state).await?;
    Ok(catalog.assets.get(clean_id).cloned())
}

/// `GET /api/v1/missions/captions/:asset_id`: Legendas sincronizadas WebVTT em português brasileiro.
pub async fn get_mission_captions(
    State(state): State<Arc<AppState>>,
    Path(raw_asset_id): Path<String>,
) -> Result<Response, AppError> {
    let clean_id = raw_asset_id
        .trim()
        .strip_suffix(".vtt")
        .unwrap_or_else(|| raw_asset_id.trim());
    let Some(asset) = resolve_media_asset_fast(&state, clean_id).await? else {
        return Err(AppError::NotFound("Legenda não encontrada".into()));
    };
    let vtt = render_webvtt(&asset);
    Ok((
        [
            (header::CONTENT_TYPE, "text/vtt; charset=utf-8"),
            (header::CACHE_CONTROL, "no-store"),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
        ],
        vtt,
    )
        .into_response())
}

/// `GET /api/v1/missions/media/:asset_id`:
/// Streaming binário direto em chunks de 64 KB com suporte integral a `HTTP Range` (`206 Partial Content`),
/// `ETag` / `304 Not Modified` e `X-Accel-Buffering: no`.
pub async fn stream_mission_media(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(raw_asset_id): Path<String>,
) -> Result<Response, AppError> {
    let clean_id = raw_asset_id
        .trim()
        .strip_suffix(".mp4")
        .unwrap_or_else(|| raw_asset_id.trim());
    let Some(asset) = resolve_media_asset_fast(&state, clean_id).await? else {
        return Err(AppError::NotFound("Ativo audiovisual não registrado".into()));
    };

    let Some(file_path) = resolve_media_path(&state.config.missions_media_dir, &asset) else {
        return Err(AppError::structured(
            StatusCode::NOT_FOUND,
            "MEDIA_ASSET_UNAVAILABLE",
            Some("asset_id"),
            "Vídeo indisponível neste servidor no momento. Você pode continuar normalmente utilizando a transcrição da cena.",
        ));
    };

    let meta = tokio::fs::metadata(&file_path).await.map_err(|_| {
        AppError::structured(
            StatusCode::NOT_FOUND,
            "MEDIA_ASSET_UNAVAILABLE",
            Some("asset_id"),
            "Vídeo indisponível neste servidor no momento. Você pode continuar normalmente utilizando a transcrição da cena.",
        )
    })?;
    let total_len = meta.len();
    if total_len == 0 {
        return Err(AppError::structured(
            StatusCode::NOT_FOUND,
            "MEDIA_ASSET_UNAVAILABLE",
            Some("asset_id"),
            "Arquivo de vídeo vazio ou indisponível.",
        ));
    }

    let etag = format!("\"{}-{total_len}\"", asset.sha256);
    let range_raw = headers.get(header::RANGE).and_then(|v| v.to_str().ok());

    if range_raw.is_none() && if_none_match_hit(&headers, &etag) {
        return Response::builder()
            .status(StatusCode::NOT_MODIFIED)
            .header(header::ETAG, &etag)
            .header(header::ACCEPT_RANGES, "bytes")
            .header(header::CACHE_CONTROL, "private, max-age=3600, no-transform")
            .body(Body::empty())
            .map_err(|e| AppError::Internal(e.to_string()));
    }

    let parsed_range = match parse_http_byte_range(range_raw, total_len) {
        Ok(r) => r,
        Err(missions::UnsatisfiableRange) => {
            return Response::builder()
                .status(StatusCode::RANGE_NOT_SATISFIABLE)
                .header(header::CONTENT_RANGE, format!("bytes */{total_len}"))
                .header(header::ACCEPT_RANGES, "bytes")
                .body(Body::empty())
                .map_err(|e| AppError::Internal(e.to_string()));
        }
    };

    let mut file = tokio::fs::File::open(&file_path).await.map_err(|_| {
        AppError::structured(
            StatusCode::NOT_FOUND,
            "MEDIA_ASSET_UNAVAILABLE",
            Some("asset_id"),
            "Não foi possível abrir o arquivo de vídeo.",
        )
    })?;

    let (status, start, end) = match parsed_range {
        Some((s, e)) => {
            file.seek(SeekFrom::Start(s))
                .await
                .map_err(|e| AppError::Internal(format!("Falha ao posicionar stream: {e}")))?;
            (StatusCode::PARTIAL_CONTENT, s, e)
        }
        None => (StatusCode::OK, 0, total_len - 1),
    };

    let chunk_total = end - start + 1;
    let limited = file.take(chunk_total);
    let stream = futures::stream::unfold(limited, |mut reader| async move {
        let mut buf = vec![0u8; 64 * 1024];
        match reader.read(&mut buf).await {
            Ok(0) => None,
            Ok(n) => {
                buf.truncate(n);
                Some((Ok::<Bytes, std::io::Error>(Bytes::from(buf)), reader))
            }
            Err(e) => Some((Err(e), reader)),
        }
    });

    let mut builder = Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, asset.mime_type.as_str())
        .header(header::CONTENT_LENGTH, chunk_total)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::ETAG, &etag)
        .header(header::CACHE_CONTROL, "private, max-age=3600, no-transform")
        .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff")
        .header("X-Accel-Buffering", "no");

    if status == StatusCode::PARTIAL_CONTENT {
        builder = builder.header(
            header::CONTENT_RANGE,
            format!("bytes {start}-{end}/{total_len}"),
        );
    }

    builder
        .body(Body::from_stream(stream))
        .map_err(|e| AppError::Internal(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::AppConfig;
    use mongodb::Client;
    use std::collections::HashSet;
    use std::net::{IpAddr, Ipv4Addr};
    use std::path::PathBuf;

    async fn state_with_media_dir(dir: PathBuf) -> Arc<AppState> {
        let client = Client::with_uri_str("mongodb://127.0.0.1:27099")
            .await
            .unwrap();
        let cfg = AppConfig {
            bind_ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
            port: 0,
            mongodb_uri: "mongodb://127.0.0.1:27099".into(),
            mongodb_db: "synthetic".into(),
            public_origin: "https://cogniva.local".into(),
            admin_emails: HashSet::new(),
            xai_api_key: None,
            xai_base_url: "https://api.x.ai/v1".into(),
            xai_model: Some("grok-4".into()),
            xai_daily_user_limit: 120,
            xai_daily_global_limit: 500,
            jwt_secret: "synthetic-only-secret-at-least-thirty-two-characters".into(),
            apk_storage_path: PathBuf::from("missing"),
            apk_metadata_path: PathBuf::from("missing"),
            missions_media_dir: dir,
        };
        Arc::new(AppState::new(client.database("synthetic"), cfg))
    }

    #[tokio::test]
    async fn byte_range_streaming_supports_206_304_416_404_and_real_mp4_boxes() {
        let tmp_dir = std::env::temp_dir().join(format!(
            "cogniva_stream_unit_{}",
            uuid::Uuid::new_v4().simple()
        ));
        std::fs::create_dir_all(&tmp_dir).unwrap();
        let payload: Vec<u8> = (0..12_000_usize).map(|i| (i % 253) as u8).collect();
        std::fs::write(tmp_dir.join("Vídeo 1 — O alerta.mp4"), &payload).unwrap();

        let state = state_with_media_dir(tmp_dir.clone()).await;

        // 1. Partial Range 206
        let mut headers = HeaderMap::new();
        headers.insert(header::RANGE, "bytes=200-1199".parse().unwrap());
        let resp = stream_mission_media(
            State(state.clone()),
            headers,
            Path("asset-bcb-m01-v01-alerta".into()),
        )
        .await
        .unwrap();
        assert_eq!(resp.status(), StatusCode::PARTIAL_CONTENT);
        assert_eq!(resp.headers().get(header::ACCEPT_RANGES).unwrap(), "bytes");
        assert_eq!(
            resp.headers().get(header::CONTENT_RANGE).unwrap(),
            "bytes 200-1199/12000"
        );
        let etag = resp
            .headers()
            .get(header::ETAG)
            .unwrap()
            .to_str()
            .unwrap()
            .to_string();
        let bytes = axum::body::to_bytes(resp.into_body(), usize::MAX)
            .await
            .unwrap();
        assert_eq!(&bytes[..], &payload[200..1200]);

        // 2. ETag conditional 304
        let mut cond_headers = HeaderMap::new();
        cond_headers.insert(header::IF_NONE_MATCH, etag.parse().unwrap());
        let resp_304 = stream_mission_media(
            State(state.clone()),
            cond_headers,
            Path("asset-bcb-m01-v01-alerta".into()),
        )
        .await
        .unwrap();
        assert_eq!(resp_304.status(), StatusCode::NOT_MODIFIED);

        // 3. Invalid range 416
        let mut bad_headers = HeaderMap::new();
        bad_headers.insert(header::RANGE, "bytes=50000-60000".parse().unwrap());
        let resp_416 = stream_mission_media(
            State(state.clone()),
            bad_headers,
            Path("asset-bcb-m01-v01-alerta".into()),
        )
        .await
        .unwrap();
        assert_eq!(resp_416.status(), StatusCode::RANGE_NOT_SATISFIABLE);

        // 4. Missing asset file returns structured 404 MEDIA_ASSET_UNAVAILABLE
        let missing_err = stream_mission_media(
            State(state.clone()),
            HeaderMap::new(),
            Path("asset-bcb-m01-v02-problema".into()),
        )
        .await
        .unwrap_err();
        let missing_resp = missing_err.into_response();
        assert_eq!(missing_resp.status(), StatusCode::NOT_FOUND);

        // 5. Captions WebVTT endpoint serves all 5 assets cleanly
        for asset_id in [
            "asset-bcb-m01-v01-alerta.vtt",
            "asset-bcb-m01-v02-problema.vtt",
            "asset-bcb-m01-v03-decisao.vtt",
            "asset-bcb-m01-v04-explicacao.vtt",
            "asset-bcb-m01-v05-duration.vtt",
        ] {
            let vtt_resp = get_mission_captions(State(state.clone()), Path(asset_id.into()))
                .await
                .unwrap();
            assert_eq!(vtt_resp.status(), StatusCode::OK);
            let text = String::from_utf8(
                axum::body::to_bytes(vtt_resp.into_body(), usize::MAX)
                    .await
                    .unwrap()
                    .to_vec(),
            )
            .unwrap();
            assert!(text.starts_with("WEBVTT"));
        }

        // 6. When local `missoes/videos` exists in workspace, verify first 32 bytes of real MP4 (`ftyp`)
        let default_dir = crate::config::resolve_default_missions_media_dir();
        if default_dir.join("Vídeo 1 — O alerta.mp4").is_file() {
            let real_state = state_with_media_dir(default_dir).await;
            let mut h = HeaderMap::new();
            h.insert(header::RANGE, "bytes=0-31".parse().unwrap());
            let real_resp = stream_mission_media(
                State(real_state),
                h,
                Path("asset-bcb-m01-v01-alerta".into()),
            )
            .await
            .unwrap();
            assert_eq!(real_resp.status(), StatusCode::PARTIAL_CONTENT);
            let head = axum::body::to_bytes(real_resp.into_body(), 64)
                .await
                .unwrap();
            assert_eq!(&head[4..8], b"ftyp");
        }

        let _ = std::fs::remove_dir_all(&tmp_dir);
    }
}
