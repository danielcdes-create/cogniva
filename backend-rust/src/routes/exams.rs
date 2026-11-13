use crate::services::{
    quiz_selector::{QuizGenerateRequest, QuizMode},
    selection_v2,
};
use crate::{
    error::AppError,
    models::exam_hierarchy::{
        CanonicalTopicStatus, ExamBlueprint, ExamLifecycleStatus, PublishEditalRequest,
        SimuladoCandidate, SubjectOutcome, TopicRef, TrunkCatalog, TrunkFocus,
    },
    services::exam_engine::{
        apply_publish, blueprint_to_leverage, card_from_canonical_activity, core_subject_ids,
        load_canonical_slices, system_catalog, weighted_net_score,
    },
    AppState,
};
use crate::{security::SessionUser, services::exam_store};
use axum::{
    extract::{Path, Query, State},
    Extension, Json,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{collections::HashMap, sync::Arc};

#[derive(Debug, Deserialize)]
pub struct WeightedSimuladoRequest {
    #[serde(default)]
    pub questions: Vec<SimuladoCandidate>,
    #[serde(default)]
    pub outcomes: Vec<SubjectOutcome>,
}

#[derive(Debug, Serialize)]
pub struct WeightedSimuladoResponse {
    pub question_ids: Vec<String>,
    pub net_score: Option<f64>,
    pub ignored_archived: bool,
}

#[derive(Debug, Deserialize)]
pub struct TrunkCommonRequest {
    #[serde(default)]
    pub mastery_by_subject: HashMap<String, f64>,
    #[serde(default)]
    pub trunk_focus_slug: Option<String>,
    #[serde(default = "default_suggested")]
    pub suggested_questions: u32,
    #[serde(default)]
    pub cached_question_ids_by_subject: HashMap<String, Vec<String>>,
}

fn default_suggested() -> u32 {
    15
}

#[derive(Debug, Serialize)]
pub struct TrunkCommonResponse {
    pub canonical_subject_id: String,
    pub subject_name: String,
    pub contest_names: Vec<String>,
    pub sentence: String,
    pub question_ids: Vec<String>,
}

pub async fn get_trunk_catalog() -> Json<TrunkCatalog> {
    Json(system_catalog())
}

pub async fn list_exams(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<Vec<ExamBlueprint>>, AppError> {
    exam_store::list(&state, user.user_id).await.map(Json)
}

pub async fn create_exam(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(payload): Json<ExamBlueprint>,
) -> Result<Json<ExamBlueprint>, AppError> {
    exam_store::save(&state, user.user_id, payload, true)
        .await
        .map(Json)
}

pub async fn update_exam(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
    Json(mut payload): Json<ExamBlueprint>,
) -> Result<Json<ExamBlueprint>, AppError> {
    if payload.id != id {
        return Err(AppError::BadRequest(
            "ID diferente do concurso editado".into(),
        ));
    }
    let command_hash = exam_store::command_hash(user.user_id, &payload);
    let mut previous = match exam_store::get(&state, user.user_id, &id).await {
        Ok(exam) => exam,
        Err(AppError::NotFound(_)) if payload.revision == 0 => {
            return exam_store::save_command(
                &state,
                user.user_id,
                payload,
                false,
                Some(command_hash),
            )
            .await
            .map(Json)
        }
        Err(error) => return Err(error),
    };
    if payload.status == ExamLifecycleStatus::Excluido {
        if previous.status == ExamLifecycleStatus::Excluido {
            return Ok(Json(previous));
        }
        let mut tombstone = previous;
        tombstone.status = ExamLifecycleStatus::Excluido;
        tombstone.is_active_in_ladder = false;
        return exam_store::save_command(
            &state,
            user.user_id,
            tombstone,
            false,
            Some(command_hash),
        )
        .await
        .map(Json);
    }
    if payload.status == ExamLifecycleStatus::EditalPublicado
        && previous.revision == payload.revision
    {
        let request = PublishEditalRequest {
            expected_revision: payload.revision,
            published_topics: payload
                .subjects
                .iter()
                .flat_map(|s| {
                    s.topics
                        .iter()
                        .filter(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
                        .map(move |t| TopicRef {
                            canonical_topic_id: t.canonical_topic_id.clone(),
                            canonical_subject_id: t.canonical_subject_id.clone(),
                            display_title: t.display_title.clone(),
                            local_subject_id: Some(s.local_subject_id.clone()),
                        })
                })
                .collect(),
            practiced_canonical_topic_ids: load_canonical_slices(&state.db, user.user_id)
                .await?
                .into_iter()
                .map(|s| s.canonical_topic_id)
                .collect(),
            published_at_iso: Some(Utc::now().to_rfc3339()),
        };
        let captured_summary = previous.edital_diff_summary.clone();
        let diff = apply_publish(&mut previous, &request);
        for subject in &mut payload.subjects {
            for topic in &mut subject.topics {
                if let Some(classified) = diff.topics.iter().find(|t| {
                    t.canonical_topic_id == topic.canonical_topic_id
                        && (t.local_subject_id.as_deref()
                            == Some(subject.local_subject_id.as_str())
                            || t.local_subject_id.is_none())
                }) {
                    topic.diff_status = classified.diff_status.clone();
                }
            }
        }
        payload.keep_summary_captured_before_publish(captured_summary);
    }
    payload.id = id;
    exam_store::save_command(&state, user.user_id, payload, false, Some(command_hash))
        .await
        .map(Json)
}

pub async fn publish_edital(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
    Json(request): Json<PublishEditalRequest>,
) -> Result<Json<ExamBlueprint>, AppError> {
    use sha2::{Digest, Sha256};
    let command_hash = format!(
        "publish:{:x}",
        Sha256::digest(
            serde_json::to_vec(&request)
                .map_err(|_| AppError::BadRequest("Publicação inválida".into()))?
        )
    );
    if let Some(confirmed) =
        exam_store::replay(&state, user.user_id, &id, &command_hash).await?
    {
        return Ok(Json(confirmed));
    }
    let mut exam = exam_store::get(&state, user.user_id, &id).await?;
    // If the grade has the same canonical discipline across multiple stages, require
    // local_subject_id on published_topics so stage scoping stays unambiguous.
    let mut disciplines = std::collections::HashSet::new();
    let has_multi_stage_subject = exam
        .subjects
        .iter()
        .any(|s| !disciplines.insert(&s.canonical_subject_id));
    if has_multi_stage_subject
        && request
            .published_topics
            .iter()
            .any(|t| t.local_subject_id.is_none())
    {
        return Err(AppError::BadRequest(
            "Grade com disciplina em várias etapas: informe local_subject_id nos tópicos publicados ou salve pela atualização completa".into(),
        ));
    }
    if exam.revision != request.expected_revision {
        return Err(AppError::Conflict(
            "O edital mudou; sincronize a revisão antes de publicar".into(),
        ));
    }
    let mut request = request;
    request.practiced_canonical_topic_ids = load_canonical_slices(&state.db, user.user_id)
        .await?
        .into_iter()
        .map(|s| s.canonical_topic_id)
        .collect();
    request.published_at_iso = Some(Utc::now().to_rfc3339());
    apply_publish(&mut exam, &request);
    exam_store::save_command(&state, user.user_id, exam, false, Some(command_hash))
        .await
        .map(Json)
}

pub async fn adopt_template(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(exam_id): Path<String>,
) -> Result<Json<ExamBlueprint>, AppError> {
    exam_store::adopt_template(&state, user.user_id, &exam_id)
        .await
        .map(Json)
}

pub async fn get_compatibility(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(exam_id): Path<String>,
    Query(query): Query<HashMap<String, String>>,
) -> Result<Json<Value>, AppError> {
    crate::services::classification::current_compatibility(
        &state,
        user.user_id,
        &exam_id,
        query.get("question_id").map(String::as_str).unwrap_or(""),
    )
    .await
    .map(Json)
}

pub async fn decide_compatibility(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(exam_id): Path<String>,
    Json(mut body): Json<Value>,
) -> Result<Json<Value>, AppError> {
    if let Some(obj) = body.as_object_mut() {
        match obj.get("exam_id").and_then(Value::as_str) {
            Some(existing) if existing != exam_id => {
                return Err(AppError::BadRequest(
                    "Concurso divergente na decisão de compatibilidade".into(),
                ));
            }
            None => {
                obj.insert("exam_id".to_string(), Value::String(exam_id));
            }
            _ => {}
        }
    }
    crate::services::classification::decide_compatibility(&state, user.user_id, &body)
        .await
        .map(Json)
}

pub async fn weighted_simulado(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(exam_id): Path<String>,
    Json(request): Json<WeightedSimuladoRequest>,
) -> Result<Json<WeightedSimuladoResponse>, AppError> {
    let exam = exam_store::get(&state, user.user_id, &exam_id).await?;
    let question_ids = selection_v2::generate(
        &state,
        &generate_request(
            user.user_id,
            QuizMode::Simulado,
            120,
            Some(exam_id),
            Vec::new(),
        ),
    )
    .await?
    .into_iter()
    .map(|q| q.id)
    .collect();
    let net_score = if request.outcomes.is_empty() {
        None
    } else {
        let mut seen = std::collections::HashSet::new();
        let mut normalized = Vec::new();
        for outcome in request.outcomes {
            let candidates: Vec<_> = exam
                .subjects
                .iter()
                .filter(|s| {
                    s.canonical_subject_id == outcome.canonical_subject_id
                        && outcome
                            .local_subject_id
                            .as_ref()
                            .is_none_or(|id| id == &s.local_subject_id)
                })
                .collect();
            if candidates.len() != 1 {
                return Err(AppError::BadRequest(
                    "Resultado ambíguo: informe local_subject_id do componente".into(),
                ));
            }
            let subject = candidates[0];
            let count = subject.question_count.unwrap_or(0);
            if !seen.insert(subject.local_subject_id.clone())
                || outcome.drawn != count
                || outcome.correct.saturating_add(outcome.wrong) > count
            {
                return Err(AppError::BadRequest(
                    "Resultado repetido ou quantidade incompatível com a grade".into(),
                ));
            }
            normalized.push(SubjectOutcome {
                weight: subject.weight,
                penalty: exam.wrong_answer_penalty,
                ..outcome
            });
        }
        let expected = exam
            .subjects
            .iter()
            .filter(|s| {
                s.question_count.unwrap_or(0) > 0
                    && s.topics
                        .iter()
                        .any(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
            })
            .count();
        if normalized.len() != expected {
            return Err(AppError::BadRequest(
                "Informe o resultado de toda a grade ativa".into(),
            ));
        }
        Some(weighted_net_score(&normalized))
    };
    Ok(Json(WeightedSimuladoResponse {
        question_ids,
        net_score,
        ignored_archived: true,
    }))
}

pub async fn trunk_common_batch(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(request): Json<TrunkCommonRequest>,
) -> Result<Json<TrunkCommonResponse>, AppError> {
    let inputs = exam_store::list(&state, user.user_id)
        .await?
        .iter()
        .map(blueprint_to_leverage)
        .collect::<Vec<_>>();
    let focus = request.trunk_focus_slug.as_ref().map(|slug| TrunkFocus {
        slug: slug.clone(),
        core_subject_ids: core_subject_ids(slug),
    });
    let today = crate::services::exam_engine::study_today();
    let focuses = focus.into_iter().collect::<Vec<_>>();
    let rows = load_canonical_slices(&state.db, user.user_id).await?;
    let quantity = request.suggested_questions.clamp(1, 120);
    let card = card_from_canonical_activity(&inputs, &rows, &focuses, quantity, today).ok_or_else(
        || AppError::NotFound("nenhuma disciplina elegível para o tronco comum".to_string()),
    )?;
    let question_ids = selection_v2::generate(
        &state,
        &generate_request(
            user.user_id,
            QuizMode::CommonTrunk,
            quantity as usize,
            None,
            request.trunk_focus_slug.into_iter().collect(),
        ),
    )
    .await?
    .into_iter()
    .map(|q| q.id)
    .collect();
    Ok(Json(TrunkCommonResponse {
        canonical_subject_id: card.canonical_subject_id,
        subject_name: card.subject_name,
        contest_names: card.contest_names,
        sentence: card.sentence,
        question_ids,
    }))
}

fn generate_request(
    user_id: i64,
    mode: QuizMode,
    quantity: usize,
    contest_id: Option<String>,
    trunk_focus_slugs: Vec<String>,
) -> QuizGenerateRequest {
    QuizGenerateRequest {
        component_id: None,
        user_id,
        mode,
        quantity,
        contest_id,
        trunk_focus_slugs,
        block_name: None,
        disciplina: None,
        source: None,
        syllabus_topic_id: None,
        single_question_id: None,
        keyword_query: None,
    }
}
