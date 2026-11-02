use crate::{
    error::AppError,
    models::{
        activity::UserActivityDocument,
        exam_hierarchy::*,
        question::{QuestionDocument, QuestionDto},
    },
    services::{
        exam_engine::*,
        exam_store,
        quiz_selector::{enrich_questions_with_user_stats, QuizGenerateRequest, QuizMode},
    },
    AppState,
};
use bson::{doc, oid::ObjectId, Document};
use futures::TryStreamExt;
use rand::seq::SliceRandom;
use std::collections::HashMap;

pub(crate) async fn pool(
    state: &AppState,
    filter: Document,
    limit: usize,
) -> Result<Vec<QuestionDocument>, AppError> {
    let mut matchers = vec![
        crate::services::question_compatibility::canonical_filter(filter),
        doc! {"validity.status":{"$nin":["OBSOLETE","UNDER_REVIEW"]}},
        doc! {"question_type":{"$ne":"OPEN_ENDED"}},
        doc! {"$or":[{"schema_version":2},{"legal_validity.status":{"$in":[bson::Bson::Null,bson::Bson::String("ACTIVE".into()),bson::Bson::String("VIGENTE".into())]}}]},
    ];
    let mut reports=state.db.collection::<Document>("reportedQuestoes").find(doc!{"fix.status_solved":false,"problemas":{"$in":["Questão com Erros de Conteúdo","Alternativas Corretas em Duplicidade","Alternativas Mal Formuladas","Enunciado c/ Erros de Conteúdo","Alternativas Incorretas","Questão Completamente Problemática"]}}).await?;
    let mut excluded = Vec::new();
    while let Some(report) = reports.try_next().await? {
        if let Ok(id) = report.get_object_id("questao_id") {
            excluded.push(id);
        }
    }
    if !excluded.is_empty() {
        matchers.push(doc! {"_id":{"$nin":excluded}});
    }
    let mut cursor = state
        .db
        .collection::<Document>("todasQuestoes")
        .find(doc! {"$and":matchers})
        .limit(10000)
        .await?;
    let mut result = Vec::new();
    while let Some(raw) = cursor.try_next().await? {
        match crate::services::question_compatibility::decode(raw) {
            Ok(q) if q.answer_indices().is_ok() => result.push(q),
            _ => tracing::warn!(
                "Questão com formato/gabarito inválido excluída da seleção; conteúdo preservado"
            ),
        }
    }
    result.shuffle(&mut rand::thread_rng());
    result.truncate(limit);
    Ok(result)
}

pub async fn generate(
    state: &AppState,
    req: &QuizGenerateRequest,
) -> Result<Vec<QuestionDto>, AppError> {
    let quantity = req.quantity.clamp(1, 120);
    let exams = exam_store::list(state, req.user_id).await?;
    // General shuffle and personal history queues are independent of the
    // planner's selected tab, including requests from older Android clients.
    let scoped_contest = if matches!(req.mode, QuizMode::Shuffle | QuizMode::SrsReview | QuizMode::ErrorNotebook | QuizMode::MostBadass | QuizMode::CommonTrunk) { None } else { req.contest_id.as_deref() };
    let exam = match scoped_contest {
        Some("Geral") | None => None,
        Some(id) => Some(exams.iter().find(|e| e.id == id).ok_or_else(|| {
            AppError::NotFound("Concurso não pertence à escada desta conta".into())
        })?),
    };
    if req.mode != QuizMode::CommonTrunk
        && exam.is_some_and(|e| matches!(e.status, ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido))
    {
        return Err(AppError::BadRequest(
            "Concurso arquivado; revise sua seleção de estudo".into(),
        ));
    }
    let mut filter = Document::new();
    if let Some(exam) = exam {
        let topics: Vec<_> = exam
            .subjects
            .iter()
            .flat_map(|s| &s.topics)
            .filter(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
            .map(|t| t.canonical_topic_id.clone())
            .collect();
        let mut subjects: Vec<_> = exam
            .subjects
            .iter()
            .filter(|s| {
                s.topics
                    .iter()
                    .any(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
            })
            .map(|s| s.display_name.clone())
            .collect();
        let catalog = canonical_subjects();
        for subject in exam.subjects.iter().filter(|s| {
            s.topics
                .iter()
                .any(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
        }) {
            if let Some(known) = catalog
                .iter()
                .find(|s| s.canonical_subject_id == subject.canonical_subject_id)
            {
                subjects.extend(known.aliases.clone());
            }
        }
        // An exact legacy subject may be studied, but is never assigned a canonical leaf by inference.
        filter.insert(
            "$or",
            vec![
                doc! {"canonical_topic_id":{"$in":topics}},
                doc! {"canonical_topic_id":bson::Bson::Null,"disciplina":{"$in":subjects}},
            ],
        );
    }
    if let Some(disc) = req.disciplina.as_ref().filter(|d| !d.is_empty()) {
        let known = canonical_subjects()
            .into_iter()
            .find(|s| s.name == *disc || s.aliases.contains(disc));
        if let Some(subject) = known {
            let mut labels = subject.aliases;
            labels.push(subject.name);
            filter.insert("disciplina", doc! {"$in":labels});
        } else {
            filter.insert("disciplina", disc);
        }
    }
    if let Some(source) = req
        .source
        .as_ref()
        .filter(|s| !s.is_empty() && s.as_str() != "Todas")
    {
        filter.insert("source", source);
    }
    if let Some(topic) = req.syllabus_topic_id.as_ref() {
        filter.insert("canonical_topic_id", topic);
    }
    let questions = match req.mode {
        QuizMode::Simulado => {
            let exam =
                exam.ok_or_else(|| AppError::BadRequest("Selecione a grade do simulado".into()))?;
            let mut selected = Vec::new();
            for subject in &exam.subjects {
                let topics: Vec<_> = subject
                    .topics
                    .iter()
                    .filter(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
                    .map(|t| t.canonical_topic_id.clone())
                    .collect();
                if topics.is_empty() || subject.weight <= 0.0 {
                    continue;
                }
                let count = subject.question_count.unwrap_or(0) as usize;
                if count == 0 {
                    continue;
                }
                let mut scope = doc! {"canonical_subject_id":&subject.canonical_subject_id,"canonical_topic_id":{"$in":topics}};
                let mut boards = exam
                    .exam_boards
                    .iter()
                    .filter(|b| b.as_str() != "A definir" && !b.trim().is_empty())
                    .cloned()
                    .collect::<Vec<_>>();
                if !boards.is_empty() {
                    if boards.iter().any(|b| b == "Cebraspe") {
                        boards.extend(["CESPE".into(), "CEBRASPE".into()]);
                    }
                    scope.insert("source", doc! {"$in":boards});
                }
                let mut drawn = pool(state, scope, count).await?;
                if drawn.len() != count {
                    return Err(AppError::BadRequest(format!("Questões canônicas válidas insuficientes em {}: {}/{}. Revise a grade ou o mapeamento do banco.",subject.display_name,drawn.len(),count)));
                }
                selected.append(&mut drawn);
            }
            if selected.is_empty() {
                return Err(AppError::BadRequest(
                    "A grade não possui quantidades ativas".into(),
                ));
            }
            selected.shuffle(&mut rand::thread_rng());
            selected
        }
        QuizMode::CommonTrunk => {
            let rows = load_canonical_slices(&state.db, req.user_id).await?;
            let inputs: Vec<_> = exams.iter().map(blueprint_to_leverage).collect();
            let focuses = req
                .trunk_focus_slugs
                .iter()
                .map(|slug| TrunkFocus {
                    slug: slug.clone(),
                    core_subject_ids: core_subject_ids(slug),
                })
                .collect::<Vec<_>>();
            let card = card_from_canonical_activity(
                &inputs,
                &rows,
                &focuses,
                quantity as u32,
                study_today(),
            );
            if let Some(card) = card {
                let live: Vec<_> = exams
                    .iter()
                    .filter(|e| e.is_active_in_ladder)
                    .flat_map(|e| &e.subjects)
                    .flat_map(|s| &s.topics)
                    .filter(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
                    .map(|t| t.canonical_topic_id.clone())
                    .collect();
                pool(state,doc!{"canonical_subject_id":card.canonical_subject_id,"canonical_topic_id":{"$in":live}},quantity).await?
            } else {
                Vec::new()
            }
        }
        QuizMode::SrsReview | QuizMode::ErrorNotebook | QuizMode::MostBadass => {
            let mut activities = state
                .db
                .collection::<UserActivityDocument>("userActivityLog")
                .find(doc! {"user_id":req.user_id})
                .await?;
            let mut eligible = Vec::new();
            while let Some(row) = activities.try_next().await? {
                let allowed = match req.mode {
                    QuizMode::SrsReview => row.review_due(chrono::Utc::now()),
                    QuizMode::ErrorNotebook => row.error_notebook(),
                    _ => !row.dominada && row.quantidade_praticas > 0,
                };
                if allowed {
                    eligible.push(row);
                }
            }
            eligible.sort_by(|a, b| a.percentual_acertos.total_cmp(&b.percentual_acertos));
            let ids: Vec<_> = eligible.iter().map(|a| a.question_id).collect();
            filter.insert("_id", doc! {"$in":ids});
            let candidates = pool(state, filter, 10000).await?;
            let mut by_id: HashMap<_, _> = candidates.into_iter().map(|q| (q.id, q)).collect();
            eligible
                .iter()
                .filter_map(|a| by_id.remove(&a.question_id))
                .take(quantity)
                .collect()
        }
        QuizMode::Single => {
            if let Some(id) = &req.single_question_id {
                filter.insert("_id", ObjectId::parse_str(id)?);
                pool(state, filter, 1).await?
            } else {
                return Err(AppError::BadRequest(
                    "Informe o ID da questão; buscas livres por regex não são aceitas".into(),
                ));
            }
        }
        _ => pool(state, filter, quantity).await?,
    };
    enrich_questions_with_user_stats(&state.db, req.user_id, questions).await
}
