use crate::{
    error::AppError,
    models::{
        activity::UserActivityDocument,
        question::{QuestionDocument, QuestionDto, UserQuestionBriefStats},
    },
};
use bson::{doc, oid::ObjectId};
use futures::stream::TryStreamExt;
use mongodb::Database;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QuizMode {
    Standard,
    Shuffle,
    Simulado,
    MostBadass,
    SrsReview,
    ErrorNotebook,
    CommonTrunk,
    Single,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuizGenerateRequest {
    pub component_id: Option<String>,
    #[serde(default)]
    pub trunk_focus_slugs: Vec<String>,
    #[serde(default)]
    pub user_id: i64,
    pub mode: QuizMode,
    pub quantity: usize,
    pub contest_id: Option<String>,
    pub block_name: Option<String>,
    pub disciplina: Option<String>,
    pub source: Option<String>,
    pub syllabus_topic_id: Option<String>,
    pub single_question_id: Option<String>,
    pub keyword_query: Option<String>,
}

/// Enriquece uma lista de `QuestionDocument` com o histórico resumido do aluno (`UserQuestionBriefStats`),
/// utilizando a regra canônica única `act.error_notebook()`.
pub async fn enrich_questions_with_user_stats(
    db: &Database,
    user_id: i64,
    questions: Vec<QuestionDocument>,
) -> Result<Vec<QuestionDto>, AppError> {
    if questions.is_empty() {
        return Ok(Vec::new());
    }

    let qids: Vec<ObjectId> = questions.iter().map(|q| q.id).collect();
    let mut cursor = db
        .collection::<UserActivityDocument>("userActivityLog")
        .find(doc! {
            "user_id": user_id,
            "question_id": { "$in": qids }
        })
        .await?;

    let mut stats_map: HashMap<ObjectId, UserQuestionBriefStats> = HashMap::new();
    while let Some(act) = cursor.try_next().await? {
        stats_map.insert(
            act.question_id,
            UserQuestionBriefStats {
                attempts: act.quantidade_praticas,
                accuracy_pct: (act.percentual_acertos * 100.0).round() / 100.0,
                last_session_iso: Some(act.datas.ultima_sessao.to_rfc3339()),
                is_mastered: act.dominada,
                in_error_notebook: act.error_notebook(),
            },
        );
    }

    Ok(questions
        .into_iter()
        .filter(|q| q.answer_indices().is_ok())
        .map(|q| {
            let st = stats_map.remove(&q.id);
            q.to_dto(st)
        })
        .collect())
}
