//! Compatibility views of the same durable ExamBlueprint. Legacy collections are never rewritten.
use crate::{
    error::AppError,
    models::{contest::*, exam_hierarchy::*},
    security::SessionUser,
    services::{exam_engine::*, exam_store},
    AppState,
};
use axum::{
    extract::{Path, Query, State},
    Extension, Json,
};
use bson::doc;
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, sync::Arc};

#[derive(Deserialize)]
pub struct SyllabusQuery {
    pub user_id: Option<i64>,
}
#[derive(Serialize)]
pub struct SyllabusTopicNodeDto {
    pub topic: SyllabusTopicDocument,
    pub user_mastery_pct: f64,
    pub questions_practiced: i32,
    pub status_label: String,
}
#[derive(Serialize)]
pub struct DisciplineSyllabusGroupDto {
    pub component_id: String,
    pub block_name: String,
    pub discipline: String,
    pub discipline_mastery_pct: f64,
    pub topics: Vec<SyllabusTopicNodeDto>,
}
#[derive(Serialize)]
pub struct ContestDiffScreenResponse {
    pub contest_id: String,
    pub contest_name: String,
    pub confirmed_board: String,
    pub confirmed_topics: Vec<SyllabusTopicDocument>,
    pub new_topics: Vec<SyllabusTopicDocument>,
    pub removed_topics: Vec<SyllabusTopicDocument>,
}

pub fn status(value: &ExamLifecycleStatus) -> ContestStatus {
    match value {
        ExamLifecycleStatus::EditalPublicado => ContestStatus::EditalPublicado,
        ExamLifecycleStatus::Autorizado | ExamLifecycleStatus::BancaDefinida => {
            ContestStatus::SolicitadoAutorizado
        }
        ExamLifecycleStatus::ProvaRealizada => ContestStatus::EmAndamento,
        ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido => ContestStatus::Encerrado,
        _ => ContestStatus::Previsto,
    }
}
pub fn topic(
    exam: &ExamBlueprint,
    subject: &ExamSubjectConfig,
    value: &ExamTopicItem,
    index: usize,
) -> SyllabusTopicDocument {
    SyllabusTopicDocument{id:value.local_topic_id.clone(),contest_id:exam.id.clone(),block_name:subject_group(subject),discipline:subject.display_name.clone(),title:value.display_title.clone(),order:index as i32,common_trunk_id:Some(value.canonical_topic_id.clone()),
        estimation:EstimationMetadata{is_estimated:value.diff_status==CanonicalTopicStatus::PreEditalBase,confidence_badge:ConfidenceBadge::Low,rationale:"Modelo configurável ou publicação informada pelo usuário. Confira a fonte do edital.".into(),reference_exams:vec![]},
        diff_status:match value.diff_status{CanonicalTopicStatus::OficialNovo=>TopicDiffStatus::NewInEdital,CanonicalTopicStatus::Arquivado=>TopicDiffStatus::RemovedArchived,_=>TopicDiffStatus::Confirmed}}
}
pub async fn list_contests(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<Vec<ContestDocument>>, AppError> {
    let exams = exam_store::list(&state, user.0).await?;
    Ok(Json(
        exams
            .iter()
            .filter(|exam| exam.status != ExamLifecycleStatus::Excluido)
            .map(|exam| ContestDocument {
                id: exam.id.clone(),
                code: exam.id.clone(),
                name: exam.title.clone(),
                role_title: exam.role.clone(),
                career_area: exam.trunk_slug.clone(),
                uf: String::new(),
                status: status(&exam.status),
                exam_board: exam.exam_boards.join(" / "),
                is_board_confirmed: exam.status == ExamLifecycleStatus::EditalPublicado,
                dates: ContestDates {
                    exam_date_official: match &exam.target_date {
                        ExamDateTarget::ExactDate { iso_date } => {
                            Some(format!("{iso_date}T12:00:00-03:00"))
                        }
                        _ => None,
                    },
                    estimated_window: match &exam.target_date {
                        ExamDateTarget::EstimatedWindow { label, .. } => Some(label.clone()),
                        _ => None,
                    },
                    edital_published_at: exam
                        .edital_diff_summary
                        .as_ref()
                        .map(|d| d.published_at_iso.clone()),
                },
                scoring_rules: ScoringRules {
                    question_format: "CONFIGURED".into(),
                    wrong_answer_penalty: exam.wrong_answer_penalty,
                    historical_cutoff_pct: exam.historical_cutoff_score,
                },
                simulation_proportions: HashMap::new(),
                blocks: HashMap::new(),
                sources: vec![],
                latest_diff_summary: None,
            })
            .collect(),
    ))
}
pub async fn get_contest_syllabus(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
    Query(_query): Query<SyllabusQuery>,
) -> Result<Json<Vec<DisciplineSyllabusGroupDto>>, AppError> {
    let exam = exam_store::get(&state, user.0, &id).await?;
    let evidence=crate::services::knowledge::load(&state.db,user.0).await?;
    let mut counts:HashMap<String,i32>=HashMap::new();
    for e in &evidence { *counts.entry(e.canonical_topic_id.clone()).or_default()+=e.attempts; }
    let mastery=mastery_by_canonical_topic(&evidence.into_iter().map(crate::services::knowledge::slice).collect::<Vec<_>>());
    Ok(Json(
        exam.subjects
            .iter()
            .filter_map(|subject| {
                let nodes: Vec<_> = subject
                    .topics
                    .iter()
                    .enumerate()
                    .filter(|(_, t)| t.diff_status != CanonicalTopicStatus::Arquivado)
                    .map(|(i, t)| {
                        let value =
                            mastery.get(&t.canonical_topic_id).copied().unwrap_or(0.0) * 100.0;
                        let practiced = counts.get(&t.canonical_topic_id).copied().unwrap_or(0);
                        SyllabusTopicNodeDto {
                            topic: topic(&exam, subject, t, i),
                            user_mastery_pct: value,
                            questions_practiced: practiced,
                            status_label: if practiced == 0 {
                                "NAO_INICIADO"
                            } else if value >= 100.0 {
                                "DOMINADO"
                            } else {
                                "QUESTOES"
                            }
                            .into(),
                        }
                    })
                    .collect();
                if nodes.is_empty() {
                    return None;
                }
                Some(DisciplineSyllabusGroupDto {
                    component_id: subject.local_subject_id.clone(),
                    block_name: subject_group(subject),
                    discipline: subject.display_name.clone(),
                    discipline_mastery_pct: nodes.iter().map(|n| n.user_mastery_pct).sum::<f64>()
                        / nodes.len() as f64,
                    topics: nodes,
                })
            })
            .collect(),
    ))
}
pub async fn get_contest_edital_diff(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
) -> Result<Json<ContestDiffScreenResponse>, AppError> {
    let exam = exam_store::get(&state, user.0, &id).await?;
    let mut confirmed_topics = vec![];
    let mut new_topics = vec![];
    let mut removed_topics = vec![];
    for subject in &exam.subjects {
        for (i, value) in subject.topics.iter().enumerate() {
            let t = topic(&exam, subject, value, i);
            match value.diff_status {
                CanonicalTopicStatus::OficialConfirmado => confirmed_topics.push(t),
                CanonicalTopicStatus::OficialNovo => new_topics.push(t),
                CanonicalTopicStatus::Arquivado => removed_topics.push(t),
                _ => {}
            }
        }
    }
    Ok(Json(ContestDiffScreenResponse {
        contest_id: exam.id,
        contest_name: exam.title,
        confirmed_board: exam.exam_boards.join(" / "),
        confirmed_topics,
        new_topics,
        removed_topics,
    }))
}

fn subject_group(subject: &ExamSubjectConfig) -> String {
    use crate::models::exam_hierarchy::SubjectCategory;
    let category=match subject.category { SubjectCategory::ConhecimentosGerais=>"Conhecimentos gerais", SubjectCategory::ConhecimentosEspecificos=>"Conhecimentos específicos", SubjectCategory::DiscursivaEstudoDeCaso=>"Discursiva / estudo de caso", SubjectCategory::Outros=>"Outros" };
    if subject.stage.trim().is_empty() { category.into() } else { format!("{} · {}",subject.stage,category) }
}
