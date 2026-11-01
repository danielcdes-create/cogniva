use crate::{
    error::AppError,
    models::{
        activity::UserActivityDocument,
        contest::ContestDocument,
        question::{QuestionDocument, QuestionDto, UserQuestionBriefStats},
    },
};
use bson::{doc, oid::ObjectId, Document};
use futures::stream::TryStreamExt;
use mongodb::Database;
use rand::seq::SliceRandom;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

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

/// Busca os IDs de questões dominadas e reportadas problemáticas para exclusão
async fn fetch_excluded_question_ids(
    db: &Database,
    user_id: i64,
    exclude_mastered: bool,
) -> Result<Vec<ObjectId>, AppError> {
    let mut excluded = HashSet::new();

    if exclude_mastered {
        let mut cursor = db
            .collection::<Document>("userActivityLog")
            .find(doc! { "user_id": user_id, "dominada": true })
            .await?;
        while let Some(doc) = cursor.try_next().await? {
            if let Ok(qid) = doc.get_object_id("question_id") {
                excluded.insert(qid);
            }
        }
    }

    let problemas_excluir = vec![
        "Alternativas Corretas em Duplicidade",
        "Alternativas Incorretas",
        "Questão Completamente Problemática",
        "Alternativas Mal Formuladas",
    ];
    let mut rep_cursor = db
        .collection::<Document>("reportedQuestoes")
        .find(doc! {
            "fix.status_solved": false,
            "problemas": { "$in": problemas_excluir }
        })
        .await?;
    while let Some(doc) = rep_cursor.try_next().await? {
        if let Ok(qid) = doc.get_object_id("questao_id") {
            excluded.insert(qid);
        }
    }

    Ok(excluded.into_iter().collect())
}

/// Enriquece uma lista de `QuestionDocument` com o histórico resumido do usuário (`UserQuestionBriefStats`)
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
                in_error_notebook: act.in_error_notebook || act.quantidade_erros > act.quantidade_acertos,
            },
        );
    }

    Ok(questions
        .into_iter()
        .map(|q| {
            let st = stats_map.remove(&q.id);
            q.to_dto(st)
        })
        .collect())
}

/// Motor principal de geração de sessão de questões (substitui `fetch_quiz_questions`, `fetch_simulation_questions`, `fetch_badass_questions`, etc.)
pub async fn generate_quiz_questions(
    db: &Database,
    req: &QuizGenerateRequest,
) -> Result<Vec<QuestionDto>, AppError> {
    let quantity = req.quantity.clamp(1, 120);

    let docs: Vec<QuestionDocument> = match req.mode {
        QuizMode::Single => fetch_single_mode(db, req).await?,
        QuizMode::MostBadass => fetch_badass_mode(db, req, quantity).await?,
        QuizMode::Simulado => fetch_simulado_mode(db, req, quantity).await?,
        QuizMode::SrsReview => fetch_srs_review_mode(db, req.user_id, quantity).await?,
        QuizMode::ErrorNotebook => fetch_error_notebook_mode(db, req, quantity).await?,
        QuizMode::CommonTrunk => fetch_common_trunk_mode(db, req, quantity).await?,
        QuizMode::Standard | QuizMode::Shuffle => fetch_standard_or_shuffle_mode(db, req, quantity).await?,
    };

    enrich_questions_with_user_stats(db, req.user_id, docs).await
}

async fn fetch_single_mode(
    db: &Database,
    req: &QuizGenerateRequest,
) -> Result<Vec<QuestionDocument>, AppError> {
    let coll = db.collection::<QuestionDocument>("todasQuestoes");
    if let Some(id_str) = &req.single_question_id {
        let oid = ObjectId::parse_str(id_str.trim())?;
        if let Some(q) = coll.find_one(doc! { "_id": oid }).await? {
            return Ok(vec![q]);
        }
        return Ok(Vec::new());
    }

    if let Some(kw) = &req.keyword_query {
        let regex_filter = doc! {
            "question": { "$regex": kw.trim(), "$options": "i" }
        };
        let mut cursor = coll.find(regex_filter).limit(5).await?;
        let mut results = Vec::new();
        while let Some(q) = cursor.try_next().await? {
            results.push(q);
        }
        return Ok(results);
    }

    Ok(Vec::new())
}

async fn get_contest_disciplines(db: &Database, contest_id: Option<&str>) -> Vec<String> {
    let Some(concurso) = contest_id else {
        return Vec::new();
    };
    if concurso == "Geral" || concurso.is_empty() {
        return Vec::new();
    }

    let contest_doc = db
        .collection::<ContestDocument>("concursos")
        .find_one(doc! { "$or": [{ "_id": concurso }, { "code": concurso }] })
        .await
        .ok()
        .flatten();

    if let Some(c) = contest_doc {
        c.simulation_proportions.into_keys().collect()
    } else if concurso == "CPNU" {
        vec![
            "Segurança e Saúde do Trabalhador".to_string(),
            "Direito do Trabalho".to_string(),
            "Sociologia e Psicologia Aplicadas ao Trabalho".to_string(),
            "Políticas Públicas".to_string(),
            "Gestão Governamental e Governança Pública".to_string(),
            "Desafios do Estado de Direito".to_string(),
            "Ética e Integridade".to_string(),
            "Administração Pública Federal".to_string(),
            "Diversidade e Inclusão na Sociedade".to_string(),
            "Finanças Públicas".to_string(),
        ]
    } else {
        Vec::new()
    }
}

async fn fetch_standard_or_shuffle_mode(
    db: &Database,
    req: &QuizGenerateRequest,
    quantity: usize,
) -> Result<Vec<QuestionDocument>, AppError> {
    let excluded = fetch_excluded_question_ids(db, req.user_id, false).await?;
    let mut match_doc = Document::new();

    if !excluded.is_empty() {
        match_doc.insert("_id", doc! { "$nin": excluded });
    }

    let contest_disciplines = get_contest_disciplines(db, req.contest_id.as_deref()).await;

    if let Some(disc) = &req.disciplina {
        if !disc.is_empty() {
            match_doc.insert("disciplina", disc);
        }
    } else if let Some(concurso) = &req.contest_id {
        if concurso != "Geral" && !concurso.is_empty() {
            if !contest_disciplines.is_empty() {
                match_doc.insert(
                    "$or",
                    vec![
                        doc! { "concursos": concurso },
                        doc! { "concursos": { "$in": [concurso] } },
                        doc! { "disciplina": { "$in": &contest_disciplines } },
                    ],
                );
            } else {
                match_doc.insert(
                    "$or",
                    vec![
                        doc! { "concursos": concurso },
                        doc! { "concursos": { "$in": [concurso] } },
                    ],
                );
            }
        }
    }
    if let Some(topic_id) = &req.syllabus_topic_id {
        if !topic_id.is_empty() {
            match_doc.insert("syllabus_topic_ids", topic_id);
        }
    }
    if let Some(src) = &req.source {
        if !src.is_empty() && src != "Todas" {
            if src == "Outras" {
                match_doc.insert(
                    "source",
                    doc! {
                        "$nin": [
                            "Resumo", "Legislação", "Jurisprudência", "Cesgranrio",
                            "CESPE", "ESAF", "Q Concursos", "Estratégia",
                            "Gran Concursos", "ChatGPT", "Gemini"
                        ]
                    },
                );
            } else {
                match_doc.insert("source", src);
            }
        }
    }

    let pipeline = vec![
        doc! { "$match": match_doc },
        doc! { "$sample": { "size": quantity as i64 } },
    ];

    let mut cursor = db
        .collection::<Document>("todasQuestoes")
        .aggregate(pipeline)
        .await?;

    let mut results = Vec::new();
    while let Some(raw_doc) = cursor.try_next().await? {
        if let Ok(q) = bson::from_document::<QuestionDocument>(raw_doc) {
            results.push(q);
        }
    }
    Ok(results)
}

/// Portabilidade de `fetch_badass_questions` do AFTBot.py:
/// Seleciona questões não dominadas ordenadas pelo índice composto de dificuldade do usuário
/// (40% taxa de erro + 30% número de práticas + 30% tempo médio gasto)
async fn fetch_badass_mode(
    db: &Database,
    req: &QuizGenerateRequest,
    quantity: usize,
) -> Result<Vec<QuestionDocument>, AppError> {
    let mut match_stage = doc! {
        "user_id": req.user_id,
        "dominada": false
    };
    if let Some(disc) = &req.disciplina {
        if !disc.is_empty() {
            match_stage.insert("disciplina", disc);
        }
    }

    let mut pipeline = vec![
        doc! { "$match": match_stage },
        doc! {
            "$addFields": {
                "indice_acertos": {
                    "$divide": [{ "$subtract": [100.0, "$percentual_acertos"] }, 100.0]
                },
                "indice_praticas": {
                    "$subtract": [
                        1.0,
                        { "$divide": [1.0, { "$max": ["$quantidade_praticas", 1] }] }
                    ]
                },
                "indice_tempo": {
                    "$min": [
                        { "$divide": [{ "$ifNull": ["$tempos.tempo_medio_resposta", 60.0] }, 120.0] },
                        1.0
                    ]
                }
            }
        },
        doc! {
            "$addFields": {
                "indice_dificuldade": {
                    "$add": [
                        { "$multiply": ["$indice_acertos", 0.4] },
                        { "$multiply": ["$indice_praticas", 0.3] },
                        { "$multiply": ["$indice_tempo", 0.3] }
                    ]
                }
            }
        },
        doc! { "$sort": { "indice_dificuldade": -1 } },
        doc! {
            "$lookup": {
                "from": "todasQuestoes",
                "localField": "question_id",
                "foreignField": "_id",
                "as": "questao_completa"
            }
        },
        doc! { "$unwind": "$questao_completa" },
    ];

    let contest_disciplines = get_contest_disciplines(db, req.contest_id.as_deref()).await;

    if let Some(disc) = &req.disciplina {
        if !disc.is_empty() {
            pipeline.push(doc! {
                "$match": { "questao_completa.disciplina": disc }
            });
        }
    } else if let Some(concurso) = &req.contest_id {
        if concurso != "Geral" && !concurso.is_empty() {
            if !contest_disciplines.is_empty() {
                pipeline.push(doc! {
                    "$match": {
                        "$or": [
                            { "questao_completa.concursos": concurso },
                            { "questao_completa.concursos": { "$in": [concurso] } },
                            { "questao_completa.disciplina": { "$in": &contest_disciplines } },
                        ]
                    }
                });
            } else {
                pipeline.push(doc! {
                    "$match": {
                        "$or": [
                            { "questao_completa.concursos": concurso },
                            { "questao_completa.concursos": { "$in": [concurso] } },
                        ]
                    }
                });
            }
        }
    }

    pipeline.push(doc! { "$replaceRoot": { "newRoot": "$questao_completa" } });
    pipeline.push(doc! { "$limit": quantity as i64 });

    let mut cursor = db
        .collection::<Document>("userActivityLog")
        .aggregate(pipeline)
        .await?;

    let mut results = Vec::new();
    while let Some(raw_doc) = cursor.try_next().await? {
        if let Ok(q) = bson::from_document::<QuestionDocument>(raw_doc) {
            results.push(q);
        }
    }

    // Fallback inteligente se o usuário ainda tiver poucas questões respondidas no histórico
    if results.len() < quantity {
        let needed = quantity - results.len();
        let fallback = fetch_standard_or_shuffle_mode(db, req, needed).await?;
        results.extend(fallback);
    }

    Ok(results)
}

/// Portabilidade de `fetch_simulation_questions` do AFTBot.py:
/// Sorteia questões respeitando as proporções oficiais das disciplinas no concurso
async fn fetch_simulado_mode(
    db: &Database,
    req: &QuizGenerateRequest,
    quantity: usize,
) -> Result<Vec<QuestionDocument>, AppError> {
    let contest_code = req.contest_id.as_deref().unwrap_or("CPNU");
    let excluded = fetch_excluded_question_ids(db, req.user_id, true).await?;

    // Busca proporções no documento do concurso ou usa fallback padrão CPNU
    let contest_doc = db
        .collection::<ContestDocument>("concursos")
        .find_one(doc! { "$or": [{ "_id": contest_code }, { "code": contest_code }] })
        .await?;

    let proportions: HashMap<String, f64> = if let Some(c) = contest_doc {
        c.simulation_proportions
    } else {
        HashMap::from([
            ("Segurança e Saúde do Trabalhador".to_string(), 0.225),
            ("Direito do Trabalho".to_string(), 0.225),
            ("Sociologia e Psicologia Aplicadas ao Trabalho".to_string(), 0.15),
            ("Políticas Públicas".to_string(), 0.105),
            ("Gestão Governamental e Governança Pública".to_string(), 0.075),
            ("Desafios do Estado de Direito".to_string(), 0.05),
            ("Ética e Integridade".to_string(), 0.05),
            ("Administração Pública Federal".to_string(), 0.04),
            ("Diversidade e Inclusão na Sociedade".to_string(), 0.04),
            ("Finanças Públicas".to_string(), 0.04),
        ])
    };

    let mut questoes_simulado = Vec::new();
    for (disciplina, weight) in &proportions {
        let count_for_disc = ((quantity as f64) * weight).round().max(1.0) as i64;
        let mut match_doc = doc! {
            "disciplina": disciplina,
        };
        if !excluded.is_empty() {
            match_doc.insert("_id", doc! { "$nin": &excluded });
        }

        let pipeline = vec![
            doc! { "$match": match_doc },
            doc! { "$sample": { "size": count_for_disc } },
        ];

        let mut cursor = db
            .collection::<Document>("todasQuestoes")
            .aggregate(pipeline)
            .await?;

        while let Some(raw_doc) = cursor.try_next().await? {
            if let Ok(q) = bson::from_document::<QuestionDocument>(raw_doc) {
                questoes_simulado.push(q);
            }
        }
    }

    let mut rng = rand::thread_rng();
    questoes_simulado.shuffle(&mut rng);
    questoes_simulado.truncate(quantity);

    Ok(questoes_simulado)
}

/// Busca questões pendentes de Revisão Espaçada (SRS) — prioridade nº 1 na aba "Hoje"
async fn fetch_srs_review_mode(
    db: &Database,
    user_id: i64,
    quantity: usize,
) -> Result<Vec<QuestionDocument>, AppError> {
    let pipeline = vec![
        doc! {
            "$match": {
                "user_id": user_id,
                "revisao_necessaria": true
            }
        },
        doc! { "$sort": { "points_with_decay": 1 } },
        doc! { "$limit": quantity as i64 },
        doc! {
            "$lookup": {
                "from": "todasQuestoes",
                "localField": "question_id",
                "foreignField": "_id",
                "as": "questao"
            }
        },
        doc! { "$unwind": "$questao" },
        doc! { "$replaceRoot": { "newRoot": "$questao" } },
    ];

    let mut cursor = db
        .collection::<Document>("userActivityLog")
        .aggregate(pipeline)
        .await?;

    let mut results = Vec::new();
    while let Some(raw_doc) = cursor.try_next().await? {
        if let Ok(q) = bson::from_document::<QuestionDocument>(raw_doc) {
            results.push(q);
        }
    }
    Ok(results)
}

/// Busca questões do Caderno de Erros do candidato (priorizando Erros com Alta Confiança!)
async fn fetch_error_notebook_mode(
    db: &Database,
    req: &QuizGenerateRequest,
    quantity: usize,
) -> Result<Vec<QuestionDocument>, AppError> {
    let mut match_doc = doc! {
        "user_id": req.user_id,
        "$or": [
            { "in_error_notebook": true },
            { "quantidade_erros": { "$gt": 0 }, "dominada": false }
        ]
    };
    if let Some(disc) = &req.disciplina {
        if !disc.is_empty() {
            match_doc.insert("disciplina", disc);
        }
    }

    let pipeline = vec![
        doc! { "$match": match_doc },
        doc! { "$sort": { "high_confidence_error_flag": -1, "quantidade_erros": -1 } },
        doc! { "$limit": quantity as i64 },
        doc! {
            "$lookup": {
                "from": "todasQuestoes",
                "localField": "question_id",
                "foreignField": "_id",
                "as": "questao"
            }
        },
        doc! { "$unwind": "$questao" },
        doc! { "$replaceRoot": { "newRoot": "$questao" } },
    ];

    let mut cursor = db
        .collection::<Document>("userActivityLog")
        .aggregate(pipeline)
        .await?;

    let mut results = Vec::new();
    while let Some(raw_doc) = cursor.try_next().await? {
        if let Ok(q) = bson::from_document::<QuestionDocument>(raw_doc) {
            results.push(q);
        }
    }
    Ok(results)
}

/// Mecânica de Tronco Comum (§13 da Nota Técnica Quiz Kotlin):
/// Seleciona questões de disciplinas compartilhadas que avançam múltiplos concursos ao mesmo tempo
async fn fetch_common_trunk_mode(
    db: &Database,
    req: &QuizGenerateRequest,
    quantity: usize,
) -> Result<Vec<QuestionDocument>, AppError> {
    let shared_disciplines = vec![
        "Direito Constitucional",
        "Direito Administrativo",
        "Língua Portuguesa",
        "Políticas Públicas",
        "Finanças Públicas",
        "Administração Pública Federal",
        "Ética e Integridade",
    ];

    let match_doc = if let Some(disc) = &req.disciplina {
        doc! { "disciplina": disc }
    } else {
        doc! { "disciplina": { "$in": shared_disciplines } }
    };

    let pipeline = vec![
        doc! { "$match": match_doc },
        doc! { "$sample": { "size": quantity as i64 } },
    ];

    let mut cursor = db
        .collection::<Document>("todasQuestoes")
        .aggregate(pipeline)
        .await?;

    let mut results = Vec::new();
    while let Some(raw_doc) = cursor.try_next().await? {
        if let Ok(q) = bson::from_document::<QuestionDocument>(raw_doc) {
            results.push(q);
        }
    }
    Ok(results)
}
