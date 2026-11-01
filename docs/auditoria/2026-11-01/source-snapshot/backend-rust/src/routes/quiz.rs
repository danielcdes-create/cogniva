use crate::{
    error::AppError,
    models::{
        activity::{
            ActivityDates, ActivityTimes, ConfidenceQuadrant, PastSessionEntry,
            UserActivityDocument,
        },
        question::{QuestionDocument, QuestionDto, VALID_PROBLEM_LIST},
    },
    services::{
        difficulty::analyze_question_difficulty,
        quiz_selector::{generate_quiz_questions, QuizGenerateRequest},
        srs_engine::{
            calculate_alternative_variation, calculate_decay, calculate_monthly_study_frequency,
            calculate_recent_confidence_avg, calculate_spaced_repetition_interval,
            calculate_streak_and_points, classify_confidence_quadrant, is_mastered,
        },
    },
    AppState,
};
use axum::{
    extract::{Path, State},
    Json,
};
use bson::{doc, oid::ObjectId};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Clone, Deserialize)]
pub struct AnswerAttemptItem {
    pub question_id: String,
    pub chosen_index: usize,
    pub chosen_text: String,
    pub is_correct: bool,
    pub time_spent_seconds: i32,
    /// 0 = Nenhuma Ideia, 1 = Chute, 2 = Sei Mais ou Menos, 3 = Dominei!
    pub confidence_level: i32,
    pub session_uuid: String,
    pub quiz_mode: String,
    pub exam_block: Option<String>,
    pub contest_id: Option<String>,
    pub user_note: Option<String>,
    #[serde(default)]
    pub canonical_subject_id: Option<String>,
    #[serde(default)]
    pub canonical_topic_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SubmitBatchRequest {
    pub user_id: i64,
    pub use_simplified_srs: Option<bool>,
    pub attempts: Vec<AnswerAttemptItem>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ProcessedAttemptResult {
    pub question_id: String,
    pub is_correct: bool,
    pub confidence_quadrant: ConfidenceQuadrant,
    pub high_confidence_error_alert: bool,
    pub streak_atual: i32,
    pub streak_max: i32,
    pub points_total: f64,
    pub points_with_decay: f64,
    pub is_mastered: bool,
    pub next_review_interval_days: i32,
    pub accuracy_accumulated_pct: f64,
    pub in_error_notebook: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct SubmitBatchResponse {
    pub processed_count: usize,
    pub results: Vec<ProcessedAttemptResult>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ReportProblemRequest {
    pub user_id: i64,
    pub problem_category: String,
}

/// Endpoint `POST /api/v1/quiz/generate`: Gera fila de questões para qualquer modo
pub async fn handle_generate_quiz(
    State(state): State<Arc<AppState>>,
    Json(req): Json<QuizGenerateRequest>,
) -> Result<Json<Vec<QuestionDto>>, AppError> {
    let questions = generate_quiz_questions(&state.db, &req).await?;
    Ok(Json(questions))
}

/// Endpoint `POST /api/v1/quiz/submit-batch`:
/// Processa 1 ou N tentativas (ideal para sincronização Offline-First do Room via WorkManager),
/// executando toda a matemática do `update_user_question_performance` do AFTBot.py em Rust nativo.
pub async fn handle_submit_batch(
    State(state): State<Arc<AppState>>,
    Json(req): Json<SubmitBatchRequest>,
) -> Result<Json<SubmitBatchResponse>, AppError> {
    let db = &state.db;
    let act_coll = db.collection::<UserActivityDocument>("userActivityLog");
    let q_coll = db.collection::<QuestionDocument>("todasQuestoes");
    let use_simplified = req.use_simplified_srs.unwrap_or(false);

    let mut results = Vec::new();

    for item in &req.attempts {
        let q_oid = ObjectId::parse_str(&item.question_id)?;
        let now = Utc::now();

        let questao_doc = q_coll.find_one(doc! { "_id": q_oid }).await?;
        let disciplina = questao_doc
            .as_ref()
            .map(|q| q.disciplina.clone())
            .unwrap_or_else(|| "Geral".to_string());

        // Se a questão ainda não tinha nível de dificuldade classificado, classifica automaticamente
        if let Some(q) = &questao_doc {
            if q.level.is_none() {
                let metrics = analyze_question_difficulty(q);
                let _ = q_coll
                    .update_one(
                        doc! { "_id": q_oid },
                        doc! { "$set": { "level": &metrics.level_label } },
                    )
                    .await;
            }
        }

        let existing = act_coll
            .find_one(doc! { "user_id": req.user_id, "question_id": q_oid })
            .await?;

        let mut doc_state = existing.unwrap_or_else(|| UserActivityDocument {
            id: None,
            user_id: req.user_id,
            question_id: q_oid,
            datas: ActivityDates {
                ultima_sessao: now,
                ultima_revisao: None,
                data_primeira_sessao: now,
                data_primeiro_acerto: if item.is_correct { Some(now) } else { None },
            },
            tempos: ActivityTimes::default(),
            disciplina: disciplina.clone(),
            past_sessions: Vec::new(),
            quantidade_praticas: 0,
            quantidade_acertos: 0,
            quantidade_erros: 0,
            percentual_acertos: 0.0,
            percentual_revisoes_sucesso: None,
            var_alternativa_escolhida: 0.0,
            frequencia_estudo_mes: 1.0,
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
            canonical_subject_id: None,
            canonical_topic_id: None,
        });

        let dias_desde_ultima = (now.date_naive() - doc_state.datas.ultima_sessao.date_naive())
            .num_days()
            .max(0);

        let mut statuses: Vec<bool> = doc_state.past_sessions.iter().map(|s| s.status).collect();
        statuses.push(item.is_correct);

        let streak_res = calculate_streak_and_points(&statuses);
        let total_sessions = statuses.len();
        let total_correct = statuses.iter().filter(|&&b| b).count();
        let acc_pct = ((total_correct as f64 / total_sessions as f64) * 10000.0).round() / 100.0;

        let total_time = doc_state.tempos.tempo_total_estudo + (item.time_spent_seconds as f64);
        let avg_time = total_time / (total_sessions as f64);

        let (srs_score, srs_interval) = calculate_spaced_repetition_interval(
            streak_res.streak_max,
            avg_time,
            item.time_spent_seconds,
            item.confidence_level,
            item.is_correct,
            use_simplified,
        );

        let is_rev = doc_state.revisao_necessaria;
        let new_session = PastSessionEntry {
            data: now,
            status: item.is_correct,
            alternativa_escolhida: item.chosen_text.clone(),
            tempo_gasto_segundos: item.time_spent_seconds,
            session_uuid: item.session_uuid.clone(),
            modo_sessao: item.quiz_mode.clone(),
            modo_prova: item.exam_block.clone(),
            concurso: item.contest_id.clone(),
            canonical_subject_id: item.canonical_subject_id.clone(),
            canonical_topic_id: item.canonical_topic_id.clone(),
            dias_desde_ultima_sessao: dias_desde_ultima,
            dias_desde_ultima_revisao: None,
            percentual_acertos_acumulado: acc_pct,
            tempo_medio_acumulado: avg_time,
            nivel_confianca: item.confidence_level,
            spaced_repetition_interval: srs_interval,
            spaced_repetition_score: srs_score,
            streak_atual: streak_res.streak_atual,
            points_session: streak_res.last_session_points,
            is_revision: is_rev,
        };

        doc_state.past_sessions.push(new_session);
        doc_state.quantidade_praticas += 1;
        if item.is_correct {
            doc_state.quantidade_acertos += 1;
            if doc_state.datas.data_primeiro_acerto.is_none() {
                doc_state.datas.data_primeiro_acerto = Some(now);
            }
        } else {
            doc_state.quantidade_erros += 1;
        }

        doc_state.datas.ultima_sessao = now;
        if is_rev {
            doc_state.datas.ultima_revisao = Some(now);
        }
        doc_state.tempos.tempo_total_estudo = total_time;
        doc_state.tempos.tempo_medio_resposta = avg_time;
        doc_state.percentual_acertos = acc_pct;
        doc_state.streak_atual = streak_res.streak_atual;
        doc_state.streak_max = streak_res.streak_max;
        doc_state.points = streak_res.points_total;
        doc_state.points_with_decay =
            calculate_decay(streak_res.points_total, doc_state.datas.ultima_sessao, now);
        doc_state.dominada = is_mastered(doc_state.points);
        doc_state.spaced_repetition_score = srs_score;
        doc_state.spaced_repetition_interval = srs_interval;
        doc_state.revisao_necessaria = !item.is_correct;
        doc_state.var_alternativa_escolhida =
            calculate_alternative_variation(&doc_state.past_sessions);
        doc_state.frequencia_estudo_mes =
            calculate_monthly_study_frequency(&doc_state.past_sessions, now);
        doc_state.nivel_confianca_med_rec =
            calculate_recent_confidence_avg(&doc_state.past_sessions, 3);

        let quadrant = classify_confidence_quadrant(item.is_correct, item.confidence_level);
        let high_conf_error = quadrant == ConfidenceQuadrant::HighConfidenceError;

        // Caderno de Erros automático (§11.2): toda questão errada entra; sai ao dominar
        doc_state.in_error_notebook = if !item.is_correct {
            true
        } else if doc_state.dominada {
            false
        } else {
            doc_state.in_error_notebook
        };
        doc_state.high_confidence_error_flag = high_conf_error;
        if let Some(note) = &item.user_note {
            doc_state.user_note = Some(note.clone());
        }
        doc_state.canonical_subject_id = item
            .canonical_subject_id
            .clone()
            .or_else(|| {
                questao_doc
                    .as_ref()
                    .and_then(|question| question.canonical_subject_id.clone())
            })
            .or(doc_state.canonical_subject_id.clone());
        doc_state.canonical_topic_id = item
            .canonical_topic_id
            .clone()
            .or_else(|| {
                questao_doc
                    .as_ref()
                    .and_then(|question| question.canonical_topic_id.clone())
            })
            .or(doc_state.canonical_topic_id.clone());

        // Upsert no MongoDB
        let _ = act_coll
            .replace_one(
                doc! { "user_id": req.user_id, "question_id": q_oid },
                doc_state.clone(),
            )
            .upsert(true)
            .await;

        results.push(ProcessedAttemptResult {
            question_id: item.question_id.clone(),
            is_correct: item.is_correct,
            confidence_quadrant: quadrant,
            high_confidence_error_alert: high_conf_error,
            streak_atual: doc_state.streak_atual,
            streak_max: doc_state.streak_max,
            points_total: doc_state.points,
            points_with_decay: doc_state.points_with_decay,
            is_mastered: doc_state.dominada,
            next_review_interval_days: srs_interval,
            accuracy_accumulated_pct: acc_pct,
            in_error_notebook: doc_state.in_error_notebook,
        });
    }

    Ok(Json(SubmitBatchResponse {
        processed_count: results.len(),
        results,
    }))
}

/// Endpoint `POST /api/v1/questions/:id/report`: Salva reporte de problema em `reportedQuestoes`
pub async fn handle_report_problem(
    State(state): State<Arc<AppState>>,
    Path(question_id): Path<String>,
    Json(req): Json<ReportProblemRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    if !VALID_PROBLEM_LIST.contains(&req.problem_category.as_str()) {
        return Err(AppError::BadRequest(format!(
            "Categoria de problema inválida: {}",
            req.problem_category
        )));
    }

    let q_oid = ObjectId::parse_str(&question_id)?;
    let now = bson::DateTime::now();
    let rep_coll = state.db.collection::<bson::Document>("reportedQuestoes");

    rep_coll
        .update_one(
            doc! { "questao_id": q_oid, "user_id": req.user_id },
            doc! {
                "$addToSet": { "problemas": &req.problem_category },
                "$set": { "ultima_atualizacao": now },
                "$setOnInsert": {
                    "data_report": now,
                    "fix": { "data_solved": bson::Bson::Null, "status_solved": false }
                }
            },
        )
        .upsert(true)
        .await?;

    // Incrementa contador `reports` na coleção `todasQuestoes` (retrocompatibilidade com AFTBot.py)
    let _ = state
        .db
        .collection::<bson::Document>("todasQuestoes")
        .update_one(doc! { "_id": q_oid }, doc! { "$inc": { "reports": 1 } })
        .await;

    Ok(Json(serde_json::json!({
        "status": "ok",
        "question_id": question_id,
        "reported_category": req.problem_category
    })))
}
