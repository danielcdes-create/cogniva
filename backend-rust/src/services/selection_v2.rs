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
use std::collections::{HashMap, HashSet};

pub const INVALID_REPORTS: [&str; 9] = [
    "Questão com Erros de Conteúdo",
    "Questão Mal Formulada",
    "Enunciado Mal Formulado",
    "Alternativas Corretas em Duplicidade",
    "Alternativas Mal Formuladas",
    "Alternativas c/ Erros de Conteúdo",
    "Enunciado c/ Erros de Conteúdo",
    "Alternativas Incorretas",
    "Questão Completamente Problemática",
];

#[derive(Default)]
struct UserHistoryBuckets {
    mastered_ids: HashSet<ObjectId>,
    all_attempted_ids: HashSet<ObjectId>,
    recent_attempted_ids: HashSet<ObjectId>,
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

/// Preserva os 20% reservados (`Holdout`) fora do treino rotineiro quando o pool elegível
/// possui ao menos 5 itens e a parte de treino atende à quantidade solicitada.
fn exclude_holdout_for_practice(
    user_id: i64,
    candidates: Vec<QuestionDocument>,
    min_needed: usize,
) -> Vec<QuestionDocument> {
    let pool_len = candidates.len();
    if pool_len < 5 {
        return candidates;
    }
    let mut practice_pool = Vec::with_capacity(pool_len);
    let mut reserved_pool = Vec::new();
    for q in candidates {
        let hex = q.id.to_hex();
        if crate::services::strategy_engine::is_holdout_reserved(user_id, &hex, pool_len) {
            reserved_pool.push(q);
        } else {
            practice_pool.push(q);
        }
    }
    if practice_pool.len() < min_needed {
        for q in reserved_pool {
            practice_pool.push(q);
            if practice_pool.len() >= min_needed {
                break;
            }
        }
    }
    practice_pool
}

fn collect_server_verified_review_ids(doc: &Document, out: &mut Vec<String>) {
    if let Ok(origins) = doc.get_array("origins") {
        for o in origins.iter().filter_map(|v| v.as_document()) {
            if let Ok(p) = o.get_document("provenance") {
                if p.get_str("trust_level").ok() == Some("SERVER_VERIFIED") {
                    if let Ok(id) = p.get_str("review_event_id") {
                        out.push(id.to_string());
                    }
                }
            }
        }
    }
    if let Ok(cls) = doc.get_document("classification") {
        if let Ok(links) = cls.get_array("links") {
            for l in links.iter().filter_map(|v| v.as_document()) {
                if let Ok(p) = l.get_document("provenance") {
                    if p.get_str("trust_level").ok() == Some("SERVER_VERIFIED") {
                        if let Ok(id) = p.get_str("review_event_id") {
                            out.push(id.to_string());
                        }
                    }
                }
            }
        }
    }
    if let Ok(val) = doc.get_document("validity") {
        if let Ok(dec) = val.get_document("decision") {
            if dec.get_str("trust_level").ok() == Some("SERVER_VERIFIED") {
                if let Ok(id) = dec.get_str("review_event_id") {
                    out.push(id.to_string());
                }
            }
        }
    }
}

/// Isola reportes abertos por `user_id` e aplica a política centralizada de elegibilidade normativa (Sec. 7).
pub(crate) async fn pool(
    state: &AppState,
    user_id: i64,
    filter: Document,
    limit: usize,
) -> Result<Vec<QuestionDocument>, AppError> {
    let mut matchers = vec![
        crate::services::question_compatibility::canonical_filter(filter),
        doc! { "validity.status": "CURRENT" },
        doc! { "classification.state": "CONFIRMED" },
        doc! { "question_type": { "$in": ["MULTIPLE_CHOICE", "TRUE_FALSE"] } },
        doc! { "status": "ACTIVE" },
    ];
    let mut reports = state
        .db
        .collection::<Document>("reportedQuestoes")
        .find(doc! {
            "user_id": user_id,
            "fix.status_solved": false,
            "problemas": { "$in": INVALID_REPORTS.as_slice() }
        })
        .await?;
    let mut excluded = Vec::new();
    while let Some(report) = reports.try_next().await? {
        if let Ok(id) = report.get_object_id("questao_id") {
            excluded.push(id);
        }
    }
    if !excluded.is_empty() {
        matchers.push(doc! { "_id": { "$nin": excluded } });
    }
    let fetch_cap = limit.clamp(64, 2500) as i64;
    let raw_docs: Vec<Document> = state
        .db
        .collection::<Document>("todasQuestoes")
        .find(doc! { "$and": matchers })
        .limit(fetch_cap)
        .await?
        .try_collect()
        .await?;

    let mut candidate_rev_ids = Vec::new();
    for raw in &raw_docs {
        collect_server_verified_review_ids(raw, &mut candidate_rev_ids);
    }
    candidate_rev_ids.sort();
    candidate_rev_ids.dedup();

    let mut verified_review_ids = HashSet::new();
    if !candidate_rev_ids.is_empty() {
        let mut rev_cursor = state
            .db
            .collection::<Document>("question_review_events")
            .find(doc! { "_id": { "$in": &candidate_rev_ids } })
            .projection(doc! { "_id": 1 })
            .await?;
        while let Some(rev_doc) = rev_cursor.try_next().await? {
            if let Ok(id) = rev_doc.get_str("_id") {
                verified_review_ids.insert(id.to_string());
            }
        }
    }
    let trust_ctx = crate::services::question_eligibility::TrustedReviewContext {
        require_server_verified: false,
        verified_review_event_ids: Some(verified_review_ids),
    };
    let catalog = crate::services::question_contracts::catalog_ref();
    let eval_date = study_today();

    let mut result = Vec::new();
    for raw in raw_docs {
        let elig = crate::services::question_eligibility::evaluate_bson_question_for_practice(
            &raw,
            &catalog,
            None,
            eval_date,
            &trust_ctx,
            crate::services::question_eligibility::EligibilityMode::StandardObjective,
        );
        if !elig.eligible {
            continue;
        }
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
    let scoped_contest = if matches!(
        req.mode,
        QuizMode::Shuffle
            | QuizMode::SrsReview
            | QuizMode::ErrorNotebook
            | QuizMode::MostBadass
            | QuizMode::CommonTrunk
    ) {
        None
    } else {
        req.contest_id.as_deref()
    };
    let exam = match scoped_contest {
        Some("Geral") | None => None,
        Some(id) => Some(exams.iter().find(|e| e.id == id).ok_or_else(|| {
            AppError::NotFound("Concurso não pertence à escada desta conta".into())
        })?),
    };
    let component_exam = if let Some(id) = &req.component_id {
        if req.mode != QuizMode::Standard {
            return Err(AppError::BadRequest(
                "Componente aplica-se ao treino específico".into(),
            ));
        }
        let mut selected = exam
            .cloned()
            .ok_or_else(|| AppError::BadRequest("Componente exige concurso".into()))?;
        selected.subjects.retain(|s| s.local_subject_id == *id);
        if selected.subjects.is_empty() {
            return Err(AppError::BadRequest(
                "Componente fora deste concurso".into(),
            ));
        }
        Some(selected)
    } else {
        None
    };
    let exam = component_exam.as_ref().or(exam);
    if req.mode != QuizMode::CommonTrunk
        && exam.is_some_and(|e| {
            matches!(
                e.status,
                ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido
            )
        })
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
        let mut or_clauses = vec![doc! { "canonical_topic_id": { "$in": topics } }];
        if req.mode != QuizMode::Simulado {
            let active_comp_ids: Vec<String> = exam
                .subjects
                .iter()
                .map(|s| s.local_subject_id.clone())
                .collect();
            let mut compat_cursor = state
                .db
                .collection::<Document>("question_exam_compatibility")
                .find(doc! {
                    "owner_user_id": req.user_id,
                    "exam_id": &exam.id,
                    "exam_revision": exam.revision,
                    "component_id": { "$in": active_comp_ids },
                    "status": "CONFIRMED",
                    "relation": { "$in": ["DIRECT", "ANALOGOUS"] }
                })
                .projection(doc! { "question_id": 1 })
                .await?;
            let mut compat_oids = Vec::new();
            while let Some(c_row) = compat_cursor.try_next().await? {
                if let Ok(qid_str) = c_row.get_str("question_id") {
                    if let Ok(oid) = ObjectId::parse_str(qid_str) {
                        compat_oids.push(oid);
                    }
                }
            }
            if !compat_oids.is_empty() {
                or_clauses.push(doc! { "_id": { "$in": compat_oids } });
            }
        }
        filter.insert("$or", or_clauses);
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
                || s.canonical_subject_id == trimmed
                || s.aliases.iter().any(|a| a == trimmed || a == base)
        });
        if let Some(subject) = known {
            filter.insert("canonical_subject_id", subject.canonical_subject_id);
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
    if let Some(topic) = req.syllabus_topic_id.as_ref().filter(|s| !s.trim().is_empty()) {
        filter.insert("canonical_topic_id", topic.trim());
    }

    let mut components = HashMap::new();
    let mut analogous_ids = HashSet::new();
    let questions = match req.mode {
        QuizMode::Shuffle => {
            let candidates = exclude_holdout_for_practice(
                req.user_id,
                pool(state, req.user_id, filter, 2000).await?,
                quantity,
            );
            let recent_window = (quantity * 4).clamp(40, 150);
            let buckets = fetch_user_history_buckets(&state.db, req.user_id, recent_window).await?;
            apply_anti_repetition_tiers(
                candidates,
                &buckets,
                if exam.is_some() { 2000 } else { quantity },
            )
        }
        QuizMode::Simulado => {
            let exam = exam
                .ok_or_else(|| AppError::BadRequest("Selecione a grade do simulado".into()))?;
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
                let base_scope = doc! {
                    "canonical_subject_id": &subject.canonical_subject_id,
                    "canonical_topic_id": { "$in": &topics }
                };
                let mut scope = base_scope.clone();
                scope.insert(
                    "_id",
                    doc! { "$nin": selected.iter().map(|q: &QuestionDocument| q.id).collect::<Vec<_>>() },
                );
                let mut boards = exam
                    .exam_boards
                    .iter()
                    .filter(|b| b.as_str() != "A definir" && !b.trim().is_empty())
                    .cloned()
                    .collect::<Vec<_>>();
                let has_board_filter = !boards.is_empty();
                if has_board_filter {
                    if boards.iter().any(|b| b == "Cebraspe") {
                        boards.extend(["CESPE".into(), "CEBRASPE".into()]);
                    }
                    scope.insert("source", doc! { "$in": &boards });
                }
                let candidates = pool(state, req.user_id, scope, (count * 8).max(120)).await?;
                let mut drawn = crate::services::practice_context::filter(
                    state,
                    req.user_id,
                    exam,
                    Some(subject),
                    candidates,
                )
                .await?;
                // Prioriza os itens reservados (Holdout 20%) no simulado comparável
                let drawn_len = drawn.len();
                drawn.sort_by_key(|q| {
                    !crate::services::strategy_engine::is_holdout_reserved(
                        req.user_id,
                        &q.id.to_hex(),
                        drawn_len.max(5),
                    )
                });
                // Fallback Cross-Banca automático quando a banca-alvo possui < count itens
                if drawn.len() < count && has_board_filter {
                    let needed = count - drawn.len();
                    let mut exclude_ids: Vec<ObjectId> =
                        selected.iter().map(|q: &QuestionDocument| q.id).collect();
                    exclude_ids.extend(drawn.iter().map(|q| q.id));
                    let mut fallback_scope = base_scope;
                    fallback_scope.insert("_id", doc! { "$nin": exclude_ids });
                    if exam.wrong_answer_penalty > 0.0 {
                        fallback_scope.insert("question_type", "TRUE_FALSE");
                    }
                    let fb_candidates =
                        pool(state, req.user_id, fallback_scope, (needed * 8).max(80)).await?;
                    let (mut fb_drawn, _) = crate::services::practice_context::filter_with_mode(
                        state,
                        req.user_id,
                        exam,
                        Some(subject),
                        fb_candidates,
                        true,
                    )
                    .await?;
                    fb_drawn.truncate(needed);
                    for fq in &fb_drawn {
                        analogous_ids.insert(fq.id.to_hex());
                    }
                    drawn.append(&mut fb_drawn);
                }
                drawn.truncate(count);
                if drawn.len() != count {
                    return Err(AppError::BadRequest(format!(
                        "Questões canônicas válidas insuficientes em {}: {}/{}. Revise a grade ou a classificação do banco.",
                        subject.display_name,
                        drawn.len(),
                        count
                    )));
                }
                for q in &drawn {
                    components.insert(q.id.to_hex(), subject.local_subject_id.clone());
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
        QuizMode::AdaptiveLoop => {
            let candidates = exclude_holdout_for_practice(
                req.user_id,
                pool(state, req.user_id, filter, 2000).await?,
                quantity,
            );
            let mut act_cursor = state
                .db
                .collection::<UserActivityDocument>("userActivityLog")
                .find(doc! { "user_id": req.user_id })
                .await?;
            let mut acts_by_id = HashMap::new();
            let now = chrono::Utc::now();
            while let Some(row) = act_cursor.try_next().await? {
                acts_by_id.insert(row.question_id, row);
            }
            let mut retention_due = Vec::new();
            let mut persistent_gaps = Vec::new();
            let mut new_items = Vec::new();
            for q in candidates {
                let hex = q.id.to_hex();
                if let Some(act) = acts_by_id.get(&q.id) {
                    if act.error_notebook() || act.high_confidence_error_flag {
                        persistent_gaps.push((hex, q));
                    } else if act.review_due(now)
                        || (act.memory_stability_days > 0.0
                            && crate::services::retention_engine::retrievability(
                                (now - act.datas.ultima_sessao).num_hours().max(0) as f64 / 24.0,
                                act.memory_stability_days,
                            ) <= 0.90)
                    {
                        retention_due.push((hex, q));
                    } else if !act.dominada {
                        persistent_gaps.push((hex, q));
                    }
                } else {
                    new_items.push((hex, q));
                }
            }
            crate::services::prioritization_engine::interleave_buckets(
                &retention_due,
                &persistent_gaps,
                &new_items,
                if exam.is_some() { 2000 } else { quantity },
                |(hex, _)| hex.as_str(),
            )
            .into_iter()
            .map(|(_, q)| q)
            .collect()
        }
        QuizMode::StrategyBattery => {
            let mut candidates = pool(state, req.user_id, filter, 2000).await?;
            let pool_len = candidates.len().max(5);
            let buckets = fetch_user_history_buckets(&state.db, req.user_id, 80).await?;
            // Prioriza questões reservadas (Holdout) ainda não respondidas para treino fiel de decisão
            candidates.sort_by_key(|q| {
                let reserved = crate::services::strategy_engine::is_holdout_reserved(
                    req.user_id,
                    &q.id.to_hex(),
                    pool_len,
                );
                let attempted = buckets.all_attempted_ids.contains(&q.id);
                (!reserved, attempted)
            });
            candidates.truncate(if exam.is_some() { 2000 } else { quantity });
            candidates
        }
        QuizMode::CommonTrunk => {
            let rows = load_canonical_slices(&state.db, req.user_id).await?;
            let inputs: Vec<_> = exams.iter().map(blueprint_to_leverage).collect();
            let mastery_by_subject = mastery_by_subject_for_blueprints(&exams, &rows);
            let focuses = req
                .trunk_focus_slugs
                .iter()
                .map(|slug| TrunkFocus {
                    slug: slug.clone(),
                    core_subject_ids: core_subject_ids(slug),
                })
                .collect::<Vec<_>>();
            let card = select_common_trunk_for_focuses(
                &inputs,
                &mastery_by_subject,
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
                let candidates = pool(
                    state,
                    req.user_id,
                    doc! {
                        "canonical_subject_id": card.canonical_subject_id,
                        "canonical_topic_id": { "$in": live }
                    },
                    1000,
                )
                .await?;
                let mut allowed = HashSet::new();
                for exam in exams.iter().filter(|e| {
                    e.is_active_in_ladder
                        && !matches!(
                            e.status,
                            ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido
                        )
                }) {
                    let scoped: Vec<_> = candidates
                        .iter()
                        .filter(|q| {
                            exam.subjects.iter().any(|s| {
                                s.topics.iter().any(|t| {
                                    t.diff_status != CanonicalTopicStatus::Arquivado
                                        && q.canonical_topic_id.as_deref()
                                            == Some(t.canonical_topic_id.as_str())
                                })
                            })
                        })
                        .cloned()
                        .collect();
                    let (filtered, borrowed) =
                        crate::services::practice_context::filter_with_mode(
                            state,
                            req.user_id,
                            exam,
                            None,
                            scoped,
                            true,
                        )
                        .await?;
                    analogous_ids.extend(borrowed);
                    for q in filtered {
                        allowed.insert(q.id);
                    }
                }
                candidates
                    .into_iter()
                    .filter(|q| allowed.contains(&q.id))
                    .take(quantity)
                    .collect()
            } else {
                Vec::new()
            }
        }
        QuizMode::SrsReview | QuizMode::ErrorNotebook | QuizMode::MostBadass => {
            let mut activities = state
                .db
                .collection::<UserActivityDocument>("userActivityLog")
                .find(doc! { "user_id": req.user_id })
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
                        a.datas
                            .ultima_sessao
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
            filter.insert("_id", doc! { "$in": ids });
            let candidates = pool(state, req.user_id, filter, 2000).await?;
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
                pool(state, req.user_id, filter, 1).await?
            } else {
                return Err(AppError::BadRequest(
                    "Informe o ID da questão; buscas livres por regex não são aceitas".into(),
                ));
            }
        }
        _ => {
            let candidates = exclude_holdout_for_practice(
                req.user_id,
                pool(state, req.user_id, filter, 2000).await?,
                quantity,
            );
            let recent_window = (quantity * 4).clamp(40, 150);
            let buckets = fetch_user_history_buckets(&state.db, req.user_id, recent_window).await?;
            apply_anti_repetition_tiers(
                candidates,
                &buckets,
                if exam.is_some() { 2000 } else { quantity },
            )
        }
    };

    let questions = if req.mode != QuizMode::Simulado {
        if let Some(exam) = exam {
            let (mut q, borrowed) = crate::services::practice_context::filter_with_mode(
                state,
                req.user_id,
                exam,
                None,
                questions,
                true,
            )
            .await?;
            analogous_ids.extend(borrowed);
            q.truncate(quantity);
            q
        } else {
            questions
        }
    } else {
        questions
    };

    let mut result = enrich_questions_with_user_stats(&state.db, req.user_id, questions).await?;
    for q in &mut result {
        q.exam_component_id = req
            .component_id
            .clone()
            .or_else(|| components.get(&q.id).cloned());
        if analogous_ids.contains(&q.id) {
            q.is_borrowed_analogous = true;
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::activity::{ActivityDates, PastSessionEntry};
    use chrono::Utc;

    #[test]
    fn anti_repetition_prioritizes_ineditas_over_recent_and_mastered() {
        let q_inedita = QuestionDocument {
            id: ObjectId::new(),
            question: "Inedita".into(),
            question_type: None,
            answers: vec!["A".into(), "B".into()],
            correct_answers: vec!["A".into()],
            correct_answer_indices: vec![0],
            content_revision: String::new(),
            grading: None,
            classification: None,
            disciplina: "Direito".into(),
            source: None,
            level: None,
            exam_year: None,
            concursos: vec![],
            syllabus_topic_ids: vec![],
            canonical_subject_id: None,
            canonical_topic_id: None,
            knowledge_binding_verified: false,
            validity: None,
            explanation: None,
            explanation_extra: None,
            dr_sincero: None,
            ajuda_infantil: None,
            explicacao_infantil: None,
            mnemonico: None,
            contextual_hint: vec![],
            plausibility_markdown: None,
            plausibility_analysis: None,
            evidence_challenge: None,
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
        assert_eq!(
            selected[0].id, q_inedita.id,
            "Tier 1 (Inedita) deve vir primeiro"
        );
        assert_eq!(
            selected[1].id, q_recente.id,
            "Tier 3 (Recente nao dominada) deve vir antes de Dominada"
        );
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
            memory_stability_days: 0.0,
            memory_difficulty: 5.0,
        };

        assert!(!act.error_notebook());
        act.in_error_notebook = true;
        act.streak_atual = 0;
        assert!(act.error_notebook());
        act.dominada = true;
        assert!(!act.error_notebook());
    }

    #[test]
    fn two_wrongs_then_a_confident_correct_leave_the_error_notebook() {
        let now = Utc::now();
        let mut act = UserActivityDocument {
            id: None,
            user_id: 1,
            question_id: ObjectId::new(),
            datas: ActivityDates {
                ultima_sessao: now,
                ultima_revisao: None,
                data_primeira_sessao: now,
                data_primeiro_acerto: Some(now),
            },
            tempos: Default::default(),
            disciplina: "Direito".into(),
            past_sessions: vec![PastSessionEntry {
                attempt_id: "third".into(),
                data: now,
                status: true,
                alternativa_escolhida: "A".into(),
                tempo_gasto_segundos: 10,
                session_uuid: "s".into(),
                modo_sessao: "STANDARD".into(),
                modo_prova: None,
                concurso: None,
                canonical_subject_id: None,
                canonical_topic_id: None,
                dias_desde_ultima_sessao: 0,
                dias_desde_ultima_revisao: None,
                percentual_acertos_acumulado: 33.0,
                tempo_medio_acumulado: 10.0,
                nivel_confianca: 2,
                spaced_repetition_interval: 7,
                spaced_repetition_score: 0.0,
                streak_atual: 1,
                points_session: 0.2,
                is_revision: false,
                is_blank: false,
                confidence_explicit: true,
                selected_evidence_id: None,
                position_in_session: None,
                elapsed_session_seconds: None,
                session_interrupted: false,
            }],
            quantidade_praticas: 3,
            quantidade_acertos: 1,
            quantidade_erros: 2,
            percentual_acertos: 33.3,
            percentual_revisoes_sucesso: None,
            var_alternativa_escolhida: 0.0,
            frequencia_estudo_mes: 0.0,
            spaced_repetition_interval: 7,
            spaced_repetition_score: 0.0,
            streak_atual: 1,
            streak_max: 1,
            points: 0.2,
            points_with_decay: 0.2,
            dominada: false,
            nivel_confianca_med_rec: Some(2),
            revisao_necessaria: false,
            in_error_notebook: true,
            high_confidence_error_flag: false,
            user_note: None,
            canonical_subject_id: None,
            canonical_topic_id: None,
            memory_stability_days: 0.0,
            memory_difficulty: 5.0,
        };
        assert!(!act.error_notebook());
        act.past_sessions[0].nivel_confianca = 0;
        assert!(act.error_notebook());
    }
}
