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
pub const INVALID_REPORTS: [&str;6]=["Questão com Erros de Conteúdo","Alternativas Corretas em Duplicidade","Alternativas Mal Formuladas","Enunciado c/ Erros de Conteúdo","Alternativas Incorretas","Questão Completamente Problemática"];

#[derive(Default)]
struct UserHistoryBuckets {
    mastered_ids: std::collections::HashSet<ObjectId>,
    all_attempted_ids: std::collections::HashSet<ObjectId>,
    recent_attempted_ids: std::collections::HashSet<ObjectId>,
}

async fn fetch_user_history_buckets(
    db: &mongodb::Database,
    user_id: i64,
    recent_window: usize,
) -> Result<UserHistoryBuckets, AppError> {
    let mut buckets = UserHistoryBuckets::default();
    let mut cursor = db
        .collection::<Document>("userActivityLog")
        .find(doc! { "user_id": user_id })
        .sort(doc! { "datas.ultima_sessao": -1 })
        .await?;

    let mut idx = 0usize;
    while let Some(doc) = cursor.try_next().await? {
        if let Ok(qid) = doc.get_object_id("question_id") {
            buckets.all_attempted_ids.insert(qid);
            if idx < recent_window {
                buckets.recent_attempted_ids.insert(qid);
            }
            if doc.get_bool("dominada").unwrap_or(false) {
                buckets.mastered_ids.insert(qid);
            }
            idx += 1;
        }
    }
    Ok(buckets)
}

fn apply_anti_repetition_tiers(
    candidates: Vec<QuestionDocument>,
    buckets: &UserHistoryBuckets,
    quantity: usize,
) -> Vec<QuestionDocument> {
    let mut rng = rand::thread_rng();

    let mut tier1 = Vec::new();
    let mut tier2 = Vec::new();
    let mut tier3 = Vec::new();
    let mut tier4 = Vec::new();

    for q in candidates {
        if buckets.mastered_ids.contains(&q.id) {
            tier4.push(q);
        } else if !buckets.all_attempted_ids.contains(&q.id) {
            tier1.push(q);
        } else if !buckets.recent_attempted_ids.contains(&q.id) {
            tier2.push(q);
        } else {
            tier3.push(q);
        }
    }

    tier1.shuffle(&mut rng);
    tier2.shuffle(&mut rng);
    tier3.shuffle(&mut rng);
    tier4.shuffle(&mut rng);

    let mut selected = Vec::with_capacity(quantity);
    for tier in [tier1, tier2, tier3, tier4] {
        for q in tier {
            selected.push(q);
            if selected.len() >= quantity {
                return selected;
            }
        }
    }
    selected
}

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
    let mut reports=state.db.collection::<Document>("reportedQuestoes").find(doc!{"fix.status_solved":false,"problemas":{"$in":INVALID_REPORTS.as_slice()}}).await?;
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
    let component_exam=if let Some(id)=&req.component_id {
        if req.mode!=QuizMode::Standard{return Err(AppError::BadRequest("Componente aplica-se ao treino específico".into()));}
        let mut selected=exam.cloned().ok_or_else(||AppError::BadRequest("Componente exige concurso".into()))?;
        selected.subjects.retain(|s|s.local_subject_id==*id);
        if selected.subjects.is_empty(){return Err(AppError::BadRequest("Componente fora deste concurso".into()));}
        Some(selected)
    }else{None};
    let exam=component_exam.as_ref().or(exam);
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
        if req.component_id.is_some(){filter.insert("canonical_topic_id",doc!{"$in":exam.subjects.iter().flat_map(|s|&s.topics).filter(|t|t.diff_status!=CanonicalTopicStatus::Arquivado).map(|t|t.canonical_topic_id.clone()).collect::<Vec<_>>()});}
    }
    if let Some(disc) = req.disciplina.as_ref().filter(|d| !d.is_empty()) {
        let trimmed = disc.trim();
        let base = trimmed
            .split('(')
            .next()
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .unwrap_or(trimmed);
        let known = canonical_subjects().into_iter().find(|s| {
            s.name == trimmed
                || s.name == base
                || s.aliases.iter().any(|a| a == trimmed || a == base)
        });
        if let Some(subject) = known {
            let mut labels = subject.aliases;
            labels.push(subject.name);
            if base != trimmed {
                labels.push(trimmed.to_string());
                labels.push(base.to_string());
            }
            labels.sort();
            labels.dedup();
            filter.insert(
                "$and",
                vec![doc! {
                    "$or": [
                        { "canonical_subject_id": subject.canonical_subject_id },
                        { "canonical_topic_id": bson::Bson::Null, "disciplina": { "$in": labels } }
                    ]
                }],
            );
        } else if base != trimmed {
            filter.insert("disciplina", doc! { "$in": [trimmed, base] });
        } else {
            filter.insert("disciplina", trimmed);
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
    let mut components = HashMap::new();
    // Demand only, bounded globally; no provider wait on the request path.
    if exam.is_some() || matches!(req.mode,QuizMode::CommonTrunk) {
        let labels:Vec<String>=exam.map(|e|e.subjects.iter().map(|s|s.display_name.clone()).collect()).unwrap_or_else(||exams.iter().filter(|e|e.is_active_in_ladder && !matches!(e.status,ExamLifecycleStatus::Arquivado|ExamLifecycleStatus::Excluido)).flat_map(|e|e.subjects.iter().map(|s|s.display_name.clone())).collect());
        if !labels.is_empty() {if let Err(error)=super::legacy_classification::seed(state,&labels).await {tracing::warn!("Fila legada indisponível: {}",error);}}
    }
    let questions = match req.mode {
        QuizMode::Shuffle => {
            let mut candidates = pool(state, filter, 10000).await?;
            if state.config.legacy_auto_classification_enabled {
                if let Err(error) = super::legacy_classification::prioritize(&state.db, &mut candidates).await {
                    tracing::warn!("Prioridade legada indisponível: {}", error);
                }
            }
            let recent_window = (quantity * 4).clamp(40, 150);
            let buckets = fetch_user_history_buckets(&state.db, req.user_id, recent_window).await?;
            apply_anti_repetition_tiers(candidates, &buckets, if exam.is_some() { 10000 } else { quantity })
        }
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
                scope.insert("_id", doc! {"$nin": selected.iter().map(|q: &QuestionDocument| q.id).collect::<Vec<_>>()});
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
                let candidates=pool(state,scope,10000).await?;
                let mut drawn=crate::services::practice_context::filter(state,req.user_id,exam,Some(subject),candidates).await?;
                drawn.truncate(count);
                if drawn.len() != count {
                    return Err(AppError::BadRequest(format!("Questões canônicas válidas insuficientes em {}: {}/{}. Revise a grade ou o mapeamento do banco.",subject.display_name,drawn.len(),count)));
                }
                for q in &drawn { components.insert(q.id.to_hex(), subject.local_subject_id.clone()); }
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
                let candidates=pool(state,doc!{"canonical_subject_id":card.canonical_subject_id,"canonical_topic_id":{"$in":live}},10000).await?;
                let mut allowed=std::collections::HashSet::new();
                for exam in exams.iter().filter(|e|e.is_active_in_ladder && !matches!(e.status,ExamLifecycleStatus::Arquivado|ExamLifecycleStatus::Excluido)) {
                    let scoped:Vec<_>=candidates.iter().filter(|q|exam.subjects.iter().any(|s|s.topics.iter().any(|t|t.diff_status!=CanonicalTopicStatus::Arquivado && q.canonical_topic_id.as_deref()==Some(t.canonical_topic_id.as_str())))).cloned().collect();
                    for q in crate::services::practice_context::filter(state,req.user_id,exam,None,scoped).await?{allowed.insert(q.id);}
                }
                candidates.into_iter().filter(|q|allowed.contains(&q.id)).take(quantity).collect()
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
            match req.mode {
                QuizMode::ErrorNotebook => {
                    eligible.sort_by(|a, b| {
                        b.high_confidence_error_flag
                            .cmp(&a.high_confidence_error_flag)
                            .then_with(|| a.datas.ultima_sessao.cmp(&b.datas.ultima_sessao))
                            .then_with(|| a.percentual_acertos.total_cmp(&b.percentual_acertos))
                    });
                }
                QuizMode::SrsReview => {
                    eligible.sort_by(|a, b| {
                        a.datas.ultima_sessao
                            .cmp(&b.datas.ultima_sessao)
                            .then_with(|| a.points_with_decay.total_cmp(&b.points_with_decay))
                            .then_with(|| a.percentual_acertos.total_cmp(&b.percentual_acertos))
                    });
                }
                _ => {
                    eligible.sort_by(|a, b| {
                        a.percentual_acertos
                            .total_cmp(&b.percentual_acertos)
                            .then_with(|| a.datas.ultima_sessao.cmp(&b.datas.ultima_sessao))
                            .then_with(|| b.quantidade_erros.cmp(&a.quantidade_erros))
                    });
                }
            }
            let ids: Vec<_> = eligible.iter().map(|a| a.question_id).collect();
            filter.insert("_id", doc! {"$in":ids});
            let candidates = pool(state, filter, 10000).await?;
            let mut by_id: HashMap<_, _> = candidates.into_iter().map(|q| (q.id, q)).collect();
            let mut available: Vec<_> = eligible
                .into_iter()
                .filter(|a| by_id.contains_key(&a.question_id))
                .collect();

            let window_size = (quantity * 2).min(available.len());
            if window_size > 1 {
                let mut top_slice = available[..window_size].to_vec();
                top_slice.shuffle(&mut rand::thread_rng());
                available[..window_size].clone_from_slice(&top_slice);
            }

            available
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
        _ => {
            let candidates = pool(state, filter, 10000).await?;
            let recent_window = (quantity * 4).clamp(40, 150);
            let buckets = fetch_user_history_buckets(&state.db, req.user_id, recent_window).await?;
            apply_anti_repetition_tiers(candidates, &buckets, if exam.is_some() { 10000 } else { quantity })
        },
    };
    let questions=if req.mode!=QuizMode::Simulado {
        if let Some(exam)=exam {let mut q=crate::services::practice_context::filter(state,req.user_id,exam,None,questions).await?;q.truncate(quantity);q}else{questions}
    }else{questions};
    let mut result = enrich_questions_with_user_stats(&state.db, req.user_id, questions).await?;
    if req.mode==QuizMode::Shuffle {
        if let Err(error)=super::legacy_classification::enqueue(state,&result.iter().filter_map(|q|ObjectId::parse_str(&q.id).ok()).collect::<Vec<_>>()).await {tracing::warn!("Fila legada indisponível: {}",error);}
    }
    for q in &mut result { q.exam_component_id = req.component_id.clone().or_else(||components.get(&q.id).cloned()); }
    Ok(result)
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::activity::ActivityDates;
    use chrono::Utc;

    #[test]
    fn anti_repetition_prioritizes_ineditas_over_recent_and_mastered() {
        let q_inedita = QuestionDocument {
            id: ObjectId::new(),
            knowledge_binding_verified: false,
            validity: None,
            question: "Inedita".into(),
            question_type: Default::default(),
            answers: vec!["A".into(), "B".into()],
            correct_answers: vec!["A".into()],
            correct_answer_indices: vec![0],
            disciplina: "Direito".into(),
            concursos: bson::Bson::Null,
            source: None,
            exam_year: None,
            level: None,
            syllabus_topic_ids: vec![],
            common_trunk_id: None,
            canonical_subject_id: None,
            canonical_topic_id: None,
            is_borrowed_analogous: false,
            legal_validity: None,
            explanation: None,
            contextual_hint: vec![],
            dr_sincero: None,
            ajuda_infantil: None,
            explicacao_infantil: None,
            explanation_extra: None,
            mnemonico: None,
            plausibility_analysis: None,
            plausibility_markdown: None,
        };

        let mut q_recente = q_inedita.clone();
        q_recente.id = ObjectId::new();
        q_recente.question = "Recente".into();

        let mut q_dominada = q_inedita.clone();
        q_dominada.id = ObjectId::new();
        q_dominada.question = "Dominada".into();

        let mut buckets = UserHistoryBuckets::default();
        buckets.all_attempted_ids.insert(q_recente.id);
        buckets.recent_attempted_ids.insert(q_recente.id);
        buckets.all_attempted_ids.insert(q_dominada.id);
        buckets.mastered_ids.insert(q_dominada.id);

        let candidates = vec![q_dominada.clone(), q_recente.clone(), q_inedita.clone()];
        let selected = apply_anti_repetition_tiers(candidates, &buckets, 2);

        assert_eq!(selected.len(), 2);
        assert_eq!(selected[0].id, q_inedita.id, "Tier 1 (Inedita) deve vir primeiro");
        assert_eq!(selected[1].id, q_recente.id, "Tier 3 (Recente nao dominada) deve vir antes de Dominada");
    }

    #[test]
    fn error_notebook_graduates_on_streak_or_mastery() {
        let now = Utc::now();
        let mut act = UserActivityDocument {
            id: None,
            user_id: 1,
            question_id: ObjectId::new(),
            datas: ActivityDates {
                ultima_sessao: now,
                ultima_revisao: None,
                data_primeira_sessao: now,
                data_primeiro_acerto: None,
            },
            tempos: Default::default(),
            disciplina: "Direito".into(),
            past_sessions: vec![],
            quantidade_praticas: 3,
            quantidade_acertos: 2,
            quantidade_erros: 1,
            percentual_acertos: 66.6,
            percentual_revisoes_sucesso: None,
            var_alternativa_escolhida: 0.0,
            frequencia_estudo_mes: 0.0,
            spaced_repetition_interval: 1,
            spaced_repetition_score: 0.0,
            streak_atual: 2,
            streak_max: 2,
            points: 50.0,
            points_with_decay: 50.0,
            dominada: false,
            nivel_confianca_med_rec: Some(2),
            revisao_necessaria: false,
            in_error_notebook: false,
            high_confidence_error_flag: false,
            user_note: None,
            canonical_subject_id: None,
            canonical_topic_id: None,
        };

        // Com in_error_notebook = false e streak_atual = 2, nao deve estar no caderno de erros
        assert!(!act.error_notebook());

        // Se errar de novo (in_error_notebook = true, streak_atual = 0)
        act.in_error_notebook = true;
        act.streak_atual = 0;
        assert!(act.error_notebook());

        // Se dominada for true, sempre sai do caderno
        act.dominada = true;
        assert!(!act.error_notebook());
    }
}
