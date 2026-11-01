use crate::{
    error::AppError,
    models::contest::{ContestDocument, SyllabusTopicDocument, TopicDiffStatus},
    services::seed::ensure_seed_data,
    AppState,
};
use axum::{
    extract::{Path, Query, State},
    Json,
};
use bson::{doc, Document};
use futures::stream::TryStreamExt;
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, sync::Arc};

#[derive(Debug, Deserialize)]
pub struct SyllabusQuery {
    pub user_id: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct SyllabusTopicNodeDto {
    pub topic: SyllabusTopicDocument,
    pub user_mastery_pct: f64,
    pub questions_practiced: i32,
    pub status_label: String, // "NAO_INICIADO" | "QUESTOES" | "REVISAO" | "DOMINADO"
}

#[derive(Debug, Serialize)]
pub struct DisciplineSyllabusGroupDto {
    pub block_name: String,
    pub discipline: String,
    pub discipline_mastery_pct: f64,
    pub topics: Vec<SyllabusTopicNodeDto>,
}

#[derive(Debug, Serialize)]
pub struct ContestDiffScreenResponse {
    pub contest_id: String,
    pub contest_name: String,
    pub confirmed_board: String,
    pub confirmed_topics: Vec<SyllabusTopicDocument>,
    pub new_topics: Vec<SyllabusTopicDocument>,
    pub removed_topics: Vec<SyllabusTopicDocument>,
}

/// Lista todos os concursos (com seed automático inicial se o banco estiver vazio)
pub async fn list_contests(
    State(state): State<Arc<AppState>>,
) -> Result<Json<Vec<ContestDocument>>, AppError> {
    let _ = ensure_seed_data(&state.db).await;

    let mut cursor = state
        .db
        .collection::<ContestDocument>("concursos")
        .find(doc! {})
        .await?;

    let mut contests = Vec::new();
    while let Some(c) = cursor.try_next().await? {
        contests.push(c);
    }
    Ok(Json(contests))
}

/// Retorna a árvore do Edital Verticalizado (oficial ou estimado com selos de confiança)
/// cruzada com o domínio real do usuário em `userActivityLog` (§7 da Nota Técnica Quiz Kotlin)
pub async fn get_contest_syllabus(
    State(state): State<Arc<AppState>>,
    Path(contest_id): Path<String>,
    Query(query): Query<SyllabusQuery>,
) -> Result<Json<Vec<DisciplineSyllabusGroupDto>>, AppError> {
    let _ = ensure_seed_data(&state.db).await;

    let mut topic_cursor = state
        .db
        .collection::<SyllabusTopicDocument>("editais_topicos")
        .find(doc! { "contest_id": &contest_id })
        .await?;

    let mut topics = Vec::new();
    while let Some(t) = topic_cursor.try_next().await? {
        topics.push(t);
    }

    // Calcula domínio por disciplina do usuário se `user_id` foi informado
    let mut disc_mastery: HashMap<String, (f64, i32)> = HashMap::new();
    if let Some(uid) = query.user_id {
        let pipeline = vec![
            doc! { "$match": { "user_id": uid } },
            doc! {
                "$group": {
                    "_id": "$disciplina",
                    "avg_acc": { "$avg": "$percentual_acertos" },
                    "total_practiced": { "$sum": "$quantidade_praticas" }
                }
            },
        ];
        let mut cursor = state
            .db
            .collection::<Document>("userActivityLog")
            .aggregate(pipeline)
            .await?;
        while let Some(d) = cursor.try_next().await? {
            if let Ok(disc_name) = d.get_str("_id") {
                let avg_acc = d.get_f64("avg_acc").unwrap_or(0.0);
                let practiced = d.get_i32("total_practiced").unwrap_or(0);
                disc_mastery.insert(disc_name.to_string(), (avg_acc, practiced));
            }
        }
    }

    let mut groups_map: HashMap<(String, String), Vec<SyllabusTopicNodeDto>> = HashMap::new();
    for topic in topics {
        let (acc, practiced) = disc_mastery
            .get(&topic.discipline)
            .copied()
            .unwrap_or((0.0, 0));

        let status_label = if practiced == 0 {
            "NAO_INICIADO"
        } else if acc >= 80.0 {
            "DOMINADO"
        } else if acc < 60.0 {
            "REVISAO"
        } else {
            "QUESTOES"
        };

        groups_map
            .entry((topic.block_name.clone(), topic.discipline.clone()))
            .or_default()
            .push(SyllabusTopicNodeDto {
                topic,
                user_mastery_pct: (acc * 100.0).round() / 100.0,
                questions_practiced: practiced,
                status_label: status_label.to_string(),
            });
    }

    let mut response = Vec::new();
    for ((block_name, discipline), mut nodes) in groups_map {
        nodes.sort_by_key(|n| n.topic.order);
        let avg_mastery = if !nodes.is_empty() {
            nodes.iter().map(|n| n.user_mastery_pct).sum::<f64>() / (nodes.len() as f64)
        } else {
            0.0
        };
        response.push(DisciplineSyllabusGroupDto {
            block_name,
            discipline,
            discipline_mastery_pct: (avg_mastery * 100.0).round() / 100.0,
            topics: nodes,
        });
    }

    response.sort_by(|a, b| a.discipline.cmp(&b.discipline));
    Ok(Json(response))
}

/// Retorna os dados da Tela de Diff ("Previsto -> Edital Publicado", §8 da Nota Técnica Quiz Kotlin)
pub async fn get_contest_edital_diff(
    State(state): State<Arc<AppState>>,
    Path(contest_id): Path<String>,
) -> Result<Json<ContestDiffScreenResponse>, AppError> {
    let contest = state
        .db
        .collection::<ContestDocument>("concursos")
        .find_one(doc! { "_id": &contest_id })
        .await?
        .ok_or_else(|| AppError::NotFound(format!("Concurso {} não encontrado", contest_id)))?;

    let mut cursor = state
        .db
        .collection::<SyllabusTopicDocument>("editais_topicos")
        .find(doc! { "contest_id": &contest_id })
        .await?;

    let mut confirmed_topics = Vec::new();
    let mut new_topics = Vec::new();
    let mut removed_topics = Vec::new();

    while let Some(t) = cursor.try_next().await? {
        match t.diff_status {
            TopicDiffStatus::Confirmed => confirmed_topics.push(t),
            TopicDiffStatus::NewInEdital => new_topics.push(t),
            TopicDiffStatus::RemovedArchived => removed_topics.push(t),
        }
    }

    Ok(Json(ContestDiffScreenResponse {
        contest_id: contest.id,
        contest_name: contest.name,
        confirmed_board: contest.exam_board,
        confirmed_topics,
        new_topics,
        removed_topics,
    }))
}
