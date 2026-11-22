//! One Mongo document is the commit boundary: event, projection revision and ACK are atomic.
//! No transaction spans a batch. Retry of an accepted prefix returns its stored receipts.
use crate::{
    error::AppError,
    models::{activity::*, question::QuestionDocument},
    routes::quiz::{AnswerAttemptItem, ProcessedAttemptResult},
    services::srs_engine::*,
    AppState,
};
use bson::{doc, oid::ObjectId, Document};
use chrono::{DateTime, Duration, Utc};
use mongodb::options::{CollectionOptions, WriteConcern};
use sha2::{Digest, Sha256};

pub fn next_error_notebook(
    previous: bool,
    last_correct: bool,
    mastered: bool,
    streak: i32,
    confidence: i32,
) -> bool {
    if !last_correct {
        true
    } else if mastered || streak >= 2 || confidence >= 2 {
        false
    } else {
        previous
    }
}

fn payload_hash(item: &AnswerAttemptItem) -> String {
    let mut core=item.clone();core.exam_component_id=None;
    let mut payload = serde_json::to_value(core).expect("serializable attempt");
    // This hint is never trusted as a result, nor part of the event's authoritative meaning.
    payload.as_object_mut().unwrap().remove("is_correct");
    payload.as_object_mut().unwrap().remove("exam_component_id");
    format!(
        "{:x}",
        Sha256::digest(serde_json::to_vec(&payload).unwrap())
    )
}

fn receipt(
    raw: &Document,
    item: &AnswerAttemptItem,
    hash: &str,
) -> Result<Option<ProcessedAttemptResult>, AppError> {
    if let Ok(sessions) = raw.get_array("pastSessions") {
        for event in sessions.iter().filter_map(|v| v.as_document()) {
            if event.get_str("attempt_id").ok() == Some(item.attempt_id.as_str()) {
                if event.get_str("payload_sha256").ok() != Some(hash) {
                    return Err(AppError::Conflict(
                        "O identificador da tentativa já existe com outro conteúdo".into(),
                    ));
                }
                return bson::from_document(
                    event
                        .get_document("ack")
                        .map_err(|_| {
                            AppError::Conflict(
                                "Recibo incompleto; preservada a tentativa para revisão".into(),
                            )
                        })?
                        .clone(),
                )
                .map(Some)
                .map_err(|_| AppError::Conflict("Formato do recibo exige revisão".into()));
            }
        }
    }
    Ok(None)
}

fn validate(
    item: &AnswerAttemptItem,
    question: &QuestionDocument,
) -> Result<DateTime<Utc>, AppError> {
    uuid::Uuid::parse_str(&item.attempt_id)
        .map_err(|_| AppError::BadRequest("Identificador da tentativa inválido".into()))?;
    if item.session_uuid.is_empty()
        || item.session_uuid.len() > 100
        || !(0..=3).contains(&item.confidence_level)
        || !(1..=86400).contains(&item.time_spent_seconds)
        || item.user_note.as_ref().is_some_and(|s| s.len() > 20000)
    {
        return Err(AppError::BadRequest("Tentativa fora dos limites".into()));
    }
    if item.question_revision.is_empty() || item.question_revision != question.revision()? {
        return Err(AppError::Conflict("A questão mudou ou a tentativa antiga não possui versão. Revise o item preservado antes de sincronizar.".into()));
    }
    if !item.is_blank && question.answers.get(item.chosen_index) != Some(&item.chosen_text) {
        return Err(AppError::Conflict(
            "Alternativa não corresponde à versão da questão".into(),
        ));
    }
    if item.selected_evidence_id.as_ref().is_some_and(|s| s.len() > 100) {
        return Err(AppError::BadRequest("Identificador de evidência fora dos limites".into()));
    }
    // Submitted taxonomy is historical client context, never authority. A
    // review may change it while the exact same content is queued offline.
    question.answer_indices()?;
    let raw_ts = item.effective_answered_at();
    let occurred = DateTime::parse_from_rfc3339(raw_ts)
        .map_err(|_| AppError::BadRequest("Data da tentativa deve incluir fuso".into()))?
        .with_timezone(&Utc);
    if occurred > Utc::now() + Duration::minutes(5) || occurred.timestamp() < 946684800 {
        return Err(AppError::BadRequest(
            "Relógio da tentativa fora da faixa; ajuste o aparelho e revise o registro preservado"
                .into(),
        ));
    }
    Ok(occurred)
}

fn empty(user_id: i64, question: &QuestionDocument, at: DateTime<Utc>) -> UserActivityDocument {
    UserActivityDocument {
        id: None,
        user_id,
        question_id: question.id,
        datas: ActivityDates {
            ultima_sessao: at,
            ultima_revisao: None,
            data_primeira_sessao: at,
            data_primeiro_acerto: None,
        },
        tempos: ActivityTimes::default(),
        disciplina: question.disciplina.clone(),
        past_sessions: vec![],
        quantidade_praticas: 0,
        quantidade_acertos: 0,
        quantidade_erros: 0,
        percentual_acertos: 0.0,
        percentual_revisoes_sucesso: None,
        var_alternativa_escolhida: 0.0,
        frequencia_estudo_mes: 0.0,
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
        canonical_subject_id: question.canonical_subject_id.clone(),
        canonical_topic_id: question.canonical_topic_id.clone(),
        memory_stability_days: 0.0,
        memory_difficulty: 5.0,
    }
}

fn project(
    mut activity: UserActivityDocument,
    question: &QuestionDocument,
    item: &AnswerAttemptItem,
    at: DateTime<Utc>,
) -> Result<(Document, Document, ProcessedAttemptResult), AppError> {
    let correct = !item.is_blank && question.answer_indices()?.contains(&item.chosen_index);
    let prior_had_history =
        !activity.past_sessions.is_empty() || activity.quantidade_praticas > 0;
    let prior_ultima_sessao = prior_had_history.then_some(activity.datas.ultima_sessao);

    let mut event = PastSessionEntry {
        attempt_id: item.attempt_id.clone(),
        data: at,
        status: correct,
        alternativa_escolhida: if item.is_blank {
            "EM_BRANCO".to_string()
        } else {
            item.chosen_text.clone()
        },
        tempo_gasto_segundos: item.time_spent_seconds,
        session_uuid: item.session_uuid.clone(),
        modo_sessao: item.quiz_mode.clone(),
        modo_prova: item.exam_block.clone(),
        concurso: item.contest_id.clone(),
        canonical_subject_id: question.canonical_subject_id.clone(),
        canonical_topic_id: question.canonical_topic_id.clone(),
        dias_desde_ultima_sessao: 0,
        dias_desde_ultima_revisao: None,
        percentual_acertos_acumulado: 0.0,
        tempo_medio_acumulado: 0.0,
        nivel_confianca: item.confidence_level,
        spaced_repetition_interval: 0,
        spaced_repetition_score: 0.0,
        streak_atual: 0,
        points_session: 0.0,
        is_revision: false,
        is_blank: item.is_blank,
        confidence_explicit: item.confidence_explicit,
        selected_evidence_id: item.selected_evidence_id.clone(),
        position_in_session: item.position_in_session,
        elapsed_session_seconds: item.elapsed_session_seconds,
        session_interrupted: item.session_interrupted,
    };
    activity.past_sessions.push(event.clone());
    // Delivery order cannot change the meaning of occurrence order: keep pastSessions chronological.
    activity
        .past_sessions
        .sort_by_key(|s| (s.data, s.attempt_id.clone(), s.session_uuid.clone()));

    if !item.is_blank {
        activity.quantidade_praticas = activity
            .quantidade_praticas
            .saturating_add(1)
            .max(activity.past_sessions.iter().filter(|s| !s.is_blank).count() as i32);
        if correct {
            activity.quantidade_acertos += 1;
        } else {
            activity.quantidade_erros += 1;
        }
        activity.percentual_acertos =
            100.0 * activity.quantidade_acertos as f64 / activity.quantidade_praticas.max(1) as f64;
    }
    activity.tempos.tempo_total_estudo += item.time_spent_seconds as f64;
    let answered_time_sum: f64 = activity
        .past_sessions
        .iter()
        .filter(|s| !s.is_blank)
        .map(|s| s.tempo_gasto_segundos as f64)
        .sum();
    let answered_count = activity
        .past_sessions
        .iter()
        .filter(|s| !s.is_blank)
        .count();
    if answered_count > 0 {
        let denom = activity.quantidade_praticas.max(answered_count as i32) as f64;
        let num = if activity.quantidade_praticas as usize > answered_count {
            activity.tempos.tempo_total_estudo
        } else {
            answered_time_sum
        };
        activity.tempos.tempo_medio_resposta = num / denom;
    }

    // Replay chronologically across past_sessions so an out-of-order offline attempt
    // never overwrites newer SRS state or corrupts ultima_sessao / ultima_resposta_em backwards.
    let mut running_statuses = Vec::with_capacity(activity.past_sessions.len());
    let mut running_correct = 0i32;
    let mut running_non_blank = 0i32;
    let mut running_answered_time = 0.0f64;
    let mut prev_session_date: Option<DateTime<Utc>> = None;
    let mut prev_interval: i32 = 0;
    let mut latest_revision: Option<DateTime<Utc>> = activity.datas.ultima_revisao;
    let mut first_correct: Option<DateTime<Utc>> = activity.datas.data_primeiro_acerto;
    let mut replay_notebook = if activity.past_sessions.len() <= 1 && prior_had_history {
        activity.in_error_notebook
    } else {
        false
    };
    let mut final_streak = calculate_streak_and_points(&[]);
    let mut final_score = 0.0;
    let mut final_interval = 1;
    let mut running_stability = 0.0f64;
    let mut running_difficulty = 5.0f64;

    let total_sessions_len = activity.past_sessions.len();
    for idx in 0..total_sessions_len {
        let non_blank_prefix: Vec<_> = activity.past_sessions[..=idx]
            .iter()
            .filter(|e| !e.is_blank)
            .cloned()
            .collect();
        let conf_avg_step = calculate_recent_confidence_avg(&non_blank_prefix, 3);
        let s = &mut activity.past_sessions[idx];
        let step_dias_ultima = prev_session_date
            .map(|p| (s.data - p).num_days().max(0))
            .unwrap_or(0);
        if s.is_blank {
            s.dias_desde_ultima_sessao = step_dias_ultima;
            s.percentual_acertos_acumulado = if running_non_blank > 0 {
                100.0 * (running_correct as f64) / (running_non_blank as f64)
            } else {
                0.0
            };
            s.tempo_medio_acumulado = if running_non_blank > 0 {
                running_answered_time / (running_non_blank as f64)
            } else {
                0.0
            };
            s.spaced_repetition_interval = final_interval;
            s.spaced_repetition_score = final_score;
            s.streak_atual = final_streak.streak_atual;
            s.points_session = 0.0;
            if s.attempt_id == item.attempt_id {
                event = s.clone();
            }
            continue;
        }
        running_non_blank += 1;
        running_answered_time += s.tempo_gasto_segundos as f64;
        running_statuses.push(s.status);
        if s.status {
            running_correct += 1;
            first_correct = Some(first_correct.map_or(s.data, |old| old.min(s.data)));
        }
        let step_streak = calculate_streak_and_points(&running_statuses);
        let step_count = running_non_blank as f64;
        let step_avg_time = running_answered_time / step_count;
        let step_acc = 100.0 * (running_correct as f64) / step_count;
        let avg_for_srs = if idx + 1 == total_sessions_len {
            activity.tempos.tempo_medio_resposta
        } else {
            step_avg_time
        };
        let (step_score, step_interval) = calculate_spaced_repetition_interval(
            step_streak.streak_atual,
            avg_for_srs,
            s.tempo_gasto_segundos,
            s.nivel_confianca,
            s.status,
            true,
        );
        let ret = crate::services::retention_engine::update_retention(
            running_stability,
            running_difficulty,
            s.status,
            s.nivel_confianca,
            step_dias_ultima as f64,
            s.tempo_gasto_segundos,
            avg_for_srs,
        );
        running_stability = ret.next_stability;
        running_difficulty = ret.next_difficulty;
        let step_is_revision = prev_session_date
            .is_some_and(|p| s.data >= p + Duration::days(prev_interval.max(1) as i64));
        let step_dias_revisao = latest_revision
            .filter(|&r| r <= s.data)
            .map(|r| (s.data - r).num_days().max(0));
        if step_is_revision {
            latest_revision = Some(latest_revision.map_or(s.data, |old| old.max(s.data)));
        }
        let step_dominada =
            is_mastered(step_streak.points_total) && conf_avg_step.unwrap_or(0) >= 2;
        replay_notebook = next_error_notebook(
            replay_notebook,
            s.status,
            step_dominada,
            step_streak.streak_atual,
            s.nivel_confianca,
        );

        s.dias_desde_ultima_sessao = step_dias_ultima;
        s.dias_desde_ultima_revisao = step_dias_revisao;
        s.percentual_acertos_acumulado = step_acc;
        s.tempo_medio_acumulado = step_avg_time;
        s.spaced_repetition_interval = step_interval;
        s.spaced_repetition_score = step_score;
        s.streak_atual = step_streak.streak_atual;
        s.points_session = step_streak.last_session_points;
        s.is_revision = step_is_revision;

        if s.attempt_id == item.attempt_id {
            event = s.clone();
        }

        prev_session_date = Some(s.data);
        prev_interval = step_interval;
        final_streak = step_streak;
        final_score = step_score;
        final_interval = step_interval;
    }

    let first_session_data = activity.past_sessions.first().unwrap().data;
    let last = activity.past_sessions.last().unwrap().clone();
    let is_latest_attempt = last.attempt_id == item.attempt_id;
    let monotonic_ultima = prior_ultima_sessao.map_or(last.data, |prev| prev.max(last.data));

    activity.datas.ultima_sessao = monotonic_ultima;
    activity.datas.data_primeira_sessao = if prior_had_history {
        activity.datas.data_primeira_sessao.min(first_session_data)
    } else {
        first_session_data
    };
    activity.datas.data_primeiro_acerto = first_correct;
    activity.datas.ultima_revisao = latest_revision;

    let answered_sessions: Vec<_> = activity
        .past_sessions
        .iter()
        .filter(|s| !s.is_blank)
        .cloned()
        .collect();
    let last_answered = answered_sessions.last();

    activity.streak_atual = final_streak.streak_atual;
    activity.streak_max = final_streak.streak_max;
    activity.points = final_streak.points_total;
    activity.points_with_decay = calculate_decay(activity.points, monotonic_ultima, Utc::now());
    activity.nivel_confianca_med_rec = calculate_recent_confidence_avg(&answered_sessions, 3);
    activity.dominada =
        is_mastered(activity.points) && activity.nivel_confianca_med_rec.unwrap_or(0) >= 2;
    activity.spaced_repetition_score = final_score;
    activity.spaced_repetition_interval = if running_non_blank > 0 {
        final_interval.clamp(1, 3650)
    } else {
        0
    };
    activity.memory_stability_days = running_stability;
    activity.memory_difficulty = running_difficulty;
    activity.revisao_necessaria = running_non_blank > 0
        && Utc::now()
            >= monotonic_ultima + Duration::days(activity.spaced_repetition_interval.max(1) as i64);
    activity.high_confidence_error_flag =
        last_answered.is_some_and(|la| !la.status && la.nivel_confianca >= 2);
    activity.in_error_notebook = replay_notebook;
    activity.var_alternativa_escolhida = calculate_alternative_variation(&answered_sessions);
    activity.frequencia_estudo_mes =
        calculate_monthly_study_frequency(&activity.past_sessions, Utc::now());
    activity.canonical_subject_id = question
        .canonical_subject_id
        .clone()
        .or(activity.canonical_subject_id);
    activity.canonical_topic_id = question
        .canonical_topic_id
        .clone()
        .or(activity.canonical_topic_id);
    if let Some(note) = &item.user_note {
        if is_latest_attempt || activity.user_note.is_none() {
            activity.user_note = Some(note.clone());
        }
    }

    let ack = ProcessedAttemptResult {
        confidence_avg: activity.nivel_confianca_med_rec,
        attempts_total: activity.quantidade_praticas,
        correct_total: activity.quantidade_acertos,
        attempt_id: item.attempt_id.clone(),
        question_id: item.question_id.clone(),
        is_correct: correct,
        confidence_quadrant: classify_confidence_quadrant(correct, item.confidence_level),
        high_confidence_error_alert: !item.is_blank && !correct && item.confidence_level >= 2,
        streak_atual: activity.streak_atual,
        streak_max: activity.streak_max,
        points_total: activity.points,
        points_with_decay: activity.points_with_decay,
        is_mastered: activity.dominada,
        next_review_interval_days: final_interval,
        accuracy_accumulated_pct: activity.percentual_acertos,
        in_error_notebook: activity.in_error_notebook,
        next_review_at: (monotonic_ultima + Duration::days(final_interval as i64)).to_rfc3339(),
        canonical_subject_id: activity.canonical_subject_id.clone(),
        canonical_topic_id: activity.canonical_topic_id.clone(),
    };
    let mut fields =
        bson::to_document(&activity).map_err(|_| AppError::Internal("Projeção inválida".into()))?;
    for key in ["_id", "pastSessions", "user_id", "question_id"] {
        fields.remove(key);
    }
    // Preserve unknown legacy nested fields too, not only unknown top-level fields.
    for key in ["datas", "tempos"] {
        if let Some(bson::Bson::Document(nested)) = fields.remove(key) {
            for (name, value) in nested {
                fields.insert(format!("{key}.{name}"), value);
            }
        }
    }
    fields.insert(
        "datas.ultima_resposta_em",
        bson::DateTime::from_chrono(monotonic_ultima),
    );
    fields.insert("projection_version", 2);
    let mut event =
        bson::to_document(&event).map_err(|_| AppError::Internal("Evento inválido".into()))?;
    event.insert("attempt_id", &item.attempt_id);
    event.insert("question_revision", &item.question_revision);
    event.insert("submitted_canonical_subject_id",item.canonical_subject_id.clone());
    event.insert("submitted_canonical_topic_id",item.canonical_topic_id.clone());
    event.insert("exam_component_id",item.exam_component_id.clone());
    event.insert("payload_sha256", payload_hash(item));
    event.insert("received_at", bson::DateTime::now());
    event.insert("ack", bson::to_document(&ack).unwrap());
    Ok((fields, event, ack))
}

pub async fn commit(
    state: &AppState,
    user_id: i64,
    item: &AnswerAttemptItem,
) -> Result<ProcessedAttemptResult, AppError> {
    let qid = ObjectId::parse_str(&item.question_id)?;
    let hash = payload_hash(item);
    let receipt_id = format!("{user_id}:{}", item.attempt_id);
    let receipts = state.db.collection_with_options::<Document>(
        "attempt_receipts",
        CollectionOptions::builder()
            .write_concern(
                WriteConcern::builder()
                    .w(mongodb::options::Acknowledgment::Majority)
                    .journal(true)
                    .w_timeout(std::time::Duration::from_secs(10))
                    .build(),
            )
            .build(),
    );
    if let Some(stored_receipt) = receipts.find_one(doc! { "_id": &receipt_id }).await? {
        if stored_receipt.get_str("payload_sha256").ok() != Some(hash.as_str()) {
            return Err(AppError::Conflict(
                "O identificador da tentativa já existe com outro conteúdo".into(),
            ));
        }
        let ack_doc = stored_receipt
            .get_document("ack")
            .map_err(|_| AppError::Conflict("Recibo incompleto; preservada a tentativa".into()))?
            .clone();
        return bson::from_document(ack_doc)
            .map_err(|_| AppError::Conflict("Formato do recibo exige revisão".into()));
    }

    let activities = state.db.collection_with_options::<Document>(
        "userActivityLog",
        CollectionOptions::builder()
            .write_concern(
                WriteConcern::builder()
                    .w(mongodb::options::Acknowledgment::Majority)
                    .journal(true)
                    .w_timeout(std::time::Duration::from_secs(10))
                    .build(),
            )
            .build(),
    );
    for _ in 0..12 {
        let existing = activities
            .find_one(doc! { "user_id": user_id, "question_id": qid })
            .await?;
        if let Some(raw) = &existing {
            if let Some(ack) = receipt(raw, item, &hash)? {
                let _ = receipts
                    .update_one(
                        doc! { "_id": &receipt_id },
                        doc! {
                            "$setOnInsert": {
                                "user_id": user_id,
                                "attempt_id": &item.attempt_id,
                                "question_id": qid,
                                "payload_sha256": &hash,
                                "ack": bson::to_document(&ack).unwrap(),
                                "recorded_at": bson::DateTime::now()
                            }
                        },
                    )
                    .upsert(true)
                    .await;
                return Ok(ack);
            }
        }
        let question = state
            .db
            .collection::<Document>("todasQuestoes")
            .find_one(doc! { "_id": qid })
            .await?
            .ok_or_else(|| {
                AppError::NotFound(
                    "Questão não encontrada; tentativa preservada para revisão".into(),
                )
            })?;
        let question = crate::services::question_compatibility::decode(question)?;
        let at = validate(item, &question)?;
        let activity = match &existing {
            Some(raw) => bson::from_document(raw.clone()).map_err(|_| {
                AppError::Conflict(
                    "Formato legado exige revisão; nenhuma informação foi substituída".into(),
                )
            })?,
            None => empty(user_id, &question, at),
        };
        let (fields, event, ack) = project(activity, &question, item, at)?;
        let digest =
            Sha256::digest(format!("cogniva-activity:{user_id}:{}", item.question_id).as_bytes());
        let id = existing
            .as_ref()
            .and_then(|d| d.get_object_id("_id").ok())
            .unwrap_or_else(|| ObjectId::from_bytes(digest[..12].try_into().unwrap()));
        let revision = existing.as_ref().and_then(|d| {
            d.get_i64("state_revision")
                .ok()
                .or_else(|| d.get_i32("state_revision").ok().map(i64::from))
        });
        let mut filter = doc! { "_id": id, "pastSessions.attempt_id": { "$ne": &item.attempt_id } };
        if let Some(revision) = revision {
            filter.insert("state_revision", revision);
        } else {
            filter.insert("state_revision", doc! { "$exists": false });
        }
        let write = activities
            .update_one(
                filter,
                doc! {
                    "$set": fields,
                    "$push": {
                        "pastSessions": {
                            "$each": [event],
                            "$sort": { "data": 1, "attempt_id": 1 },
                            "$slice": -50
                        }
                    },
                    "$inc": { "state_revision": 1_i64 },
                    "$setOnInsert": { "user_id": user_id, "question_id": qid }
                },
            )
            .upsert(existing.is_none())
            .await;
        match write {
            Ok(result) if result.matched_count == 1 || result.upserted_id.is_some() => {
                let _ = receipts
                    .update_one(
                        doc! { "_id": &receipt_id },
                        doc! {
                            "$setOnInsert": {
                                "user_id": user_id,
                                "attempt_id": &item.attempt_id,
                                "question_id": qid,
                                "payload_sha256": &hash,
                                "ack": bson::to_document(&ack).unwrap(),
                                "recorded_at": bson::DateTime::now()
                            }
                        },
                    )
                    .upsert(true)
                    .await;
                return Ok(ack);
            }
            Ok(_) => continue,
            Err(e) if e.to_string().contains("E11000") => continue,
            Err(e) => return Err(e.into()),
        }
    }
    Err(AppError::TemporarilyUnavailable(
        "Outra gravação está em andamento; a tentativa continua pendente".into(),
    ))
}

pub async fn attach_evidence(
    state: &AppState,
    user_id: i64,
    question_id: &str,
    attempt_id: &str,
    evidence_id: &str,
) -> Result<bool, AppError> {
    let qid = ObjectId::parse_str(question_id)?;
    uuid::Uuid::parse_str(attempt_id)
        .map_err(|_| AppError::BadRequest("Identificador da tentativa inválido".into()))?;
    let trimmed = evidence_id.trim();
    if trimmed.is_empty() || trimmed.len() > 100 {
        return Err(AppError::BadRequest(
            "Identificador de evidência fora dos limites".into(),
        ));
    }
    if let Some(raw_q) = state
        .db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! { "_id": qid })
        .await?
    {
        if let Ok(q_doc) = bson::from_document::<QuestionDocument>(raw_q) {
            if let Some(challenge) = q_doc.effective_evidence_challenge() {
                if !challenge
                    .options
                    .iter()
                    .any(|opt| opt.evidence_id == trimmed)
                {
                    return Err(AppError::BadRequest(
                        "Identificador de evidência não pertence às opções desta questão".into(),
                    ));
                }
            }
        }
    }
    let activities = state.db.collection_with_options::<Document>(
        "userActivityLog",
        CollectionOptions::builder()
            .write_concern(
                WriteConcern::builder()
                    .w(mongodb::options::Acknowledgment::Majority)
                    .journal(true)
                    .w_timeout(std::time::Duration::from_secs(10))
                    .build(),
            )
            .build(),
    );
    let result = activities
        .update_one(
            doc! {
                "user_id": user_id,
                "question_id": qid,
                "pastSessions.attempt_id": attempt_id
            },
            doc! {
                "$set": { "pastSessions.$.selected_evidence_id": trimmed },
                "$inc": { "state_revision": 1_i64 }
            },
        )
        .await?;
    Ok(result.matched_count > 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fixture_legacy_answer_is_authoritative_and_event_is_append_only() {
        let q:QuestionDocument=bson::from_document(doc!{"_id":ObjectId::new(),"question":"Q","answers":["A","B"],"correct_answers":["B"],"disciplina":"D"}).unwrap();
        let item = AnswerAttemptItem {
            exam_component_id:None,
            attempt_id: uuid::Uuid::new_v4().to_string(),
            occurred_at: Utc::now().to_rfc3339(),
            client_answered_at: None,
            question_revision: q.revision().unwrap(),
            question_id: q.id.to_hex(),
            chosen_index: 0,
            chosen_text: "A".into(),
            is_correct: true,
            time_spent_seconds: 10,
            confidence_level: 3,
            session_uuid: "s".into(),
            quiz_mode: "STANDARD".into(),
            exam_block: None,
            contest_id: None,
            user_note: None,
            canonical_subject_id: None,
            canonical_topic_id: None,
            is_blank: false,
            confidence_explicit: true,
            selected_evidence_id: None,
            position_in_session: None,
            elapsed_session_seconds: None,
            session_interrupted: false,
        };
        let (fields, event, ack) =
            project(empty(123, &q, Utc::now()), &q, &item, Utc::now()).unwrap();
        let mut contextual=item.clone();contextual.exam_component_id=Some("second-stage".into());
        assert_eq!(payload_hash(&item),payload_hash(&contextual));
        assert!(!ack.is_correct);
        assert!(!fields.contains_key("pastSessions"));
        assert!(!fields.contains_key("datas"));
        let raw = doc! {"pastSessions":[event]};
        assert_eq!(
            receipt(&raw, &item, &payload_hash(&item))
                .unwrap()
                .unwrap()
                .attempt_id,
            item.attempt_id
        );
        let mut changed = item.clone();
        changed.confidence_level = 1;
        assert!(receipt(&raw, &changed, &payload_hash(&changed)).is_err());
    }

    #[test]
    fn full_history_graduates_or_keeps_the_notebook() {
        assert!(!next_error_notebook(true, true, true, 1, 0));
        assert!(!next_error_notebook(true, true, false, 2, 0));
        assert!(!next_error_notebook(true, true, false, 1, 2));
        assert!(next_error_notebook(true, true, false, 1, 0));
        assert!(next_error_notebook(false, false, false, 0, 3));
    }

    #[test]
    fn attempts_total_reconciles_legacy_practice_count_and_session_len() {
        let q: QuestionDocument = bson::from_document(doc! {
            "_id": ObjectId::new(),
            "question": "Q",
            "answers": ["A", "B"],
            "correct_answers": ["B"],
            "disciplina": "D"
        })
        .unwrap();
        let now = Utc::now();
        let mut act = empty(123, &q, now);
        act.quantidade_praticas = 5;
        let item = AnswerAttemptItem {
            exam_component_id: None,
            attempt_id: uuid::Uuid::new_v4().to_string(),
            occurred_at: now.to_rfc3339(),
            client_answered_at: None,
            question_revision: q.revision().unwrap(),
            question_id: q.id.to_hex(),
            chosen_index: 1,
            chosen_text: "B".into(),
            is_correct: true,
            time_spent_seconds: 10,
            confidence_level: 3,
            session_uuid: "s".into(),
            quiz_mode: "STANDARD".into(),
            exam_block: None,
            contest_id: None,
            user_note: None,
            canonical_subject_id: None,
            canonical_topic_id: None,
            is_blank: false,
            confidence_explicit: true,
            selected_evidence_id: None,
            position_in_session: None,
            elapsed_session_seconds: None,
            session_interrupted: false,
        };
        let (_, _, ack) = project(act, &q, &item, now).unwrap();
        assert_eq!(ack.attempts_total, 6);
    }

    #[test]
    fn out_of_order_offline_sync_replays_chronologically_without_regressing_srs_or_timestamps() {
        let q: QuestionDocument = bson::from_document(doc! {
            "_id": ObjectId::new(),
            "question": "Questão Offline",
            "answers": ["Errada", "Certa"],
            "correct_answers": ["Certa"],
            "disciplina": "Direito Constitucional"
        })
        .unwrap();
        let rev = q.revision().unwrap();
        // Use millisecond-precision timestamps so BSON DateTime round-trip is exact.
        let t1 = DateTime::parse_from_rfc3339("2026-11-10T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let t2 = DateTime::parse_from_rfc3339("2026-11-12T15:30:00Z")
            .unwrap()
            .with_timezone(&Utc);

        let older_offline_wrong = AnswerAttemptItem {
            exam_component_id: None,
            attempt_id: "11111111-1111-4111-8111-111111111111".into(),
            occurred_at: String::new(),
            client_answered_at: Some(t1.to_rfc3339()),
            question_revision: rev.clone(),
            question_id: q.id.to_hex(),
            chosen_index: 0,
            chosen_text: "Errada".into(),
            is_correct: false,
            time_spent_seconds: 30,
            confidence_level: 3,
            session_uuid: "sess-offline".into(),
            quiz_mode: "STANDARD".into(),
            exam_block: None,
            contest_id: None,
            user_note: Some("Nota antiga offline".into()),
            canonical_subject_id: None,
            canonical_topic_id: None,
            is_blank: false,
            confidence_explicit: true,
            selected_evidence_id: None,
            position_in_session: None,
            elapsed_session_seconds: None,
            session_interrupted: false,
        };
        let newer_online_right = AnswerAttemptItem {
            exam_component_id: None,
            attempt_id: "22222222-2222-4222-8222-222222222222".into(),
            occurred_at: t2.to_rfc3339(),
            client_answered_at: None,
            question_revision: rev,
            question_id: q.id.to_hex(),
            chosen_index: 1,
            chosen_text: "Certa".into(),
            is_correct: true,
            time_spent_seconds: 20,
            confidence_level: 1,
            session_uuid: "sess-online".into(),
            quiz_mode: "STANDARD".into(),
            exam_block: None,
            contest_id: None,
            user_note: Some("Nota recente online".into()),
            canonical_subject_id: None,
            canonical_topic_id: None,
            is_blank: false,
            confidence_explicit: true,
            selected_evidence_id: None,
            position_in_session: None,
            elapsed_session_seconds: None,
            session_interrupted: false,
        };

        let apply_step = |existing: Option<Document>, item: &AnswerAttemptItem| -> Document {
            let at = validate(item, &q).unwrap();
            let act: UserActivityDocument = match &existing {
                Some(raw) => bson::from_document(raw.clone()).unwrap(),
                None => empty(42, &q, at),
            };
            let (fields, event, _) = project(act, &q, item, at).unwrap();
            let mut doc_state = existing.unwrap_or_else(|| doc! {
                "_id": ObjectId::new(),
                "user_id": 42_i64,
                "question_id": q.id,
                "datas": {},
                "tempos": {},
                "pastSessions": []
            });
            for (k, v) in fields {
                if let Some((parent, child)) = k.split_once('.') {
                    doc_state
                        .entry(parent.to_string())
                        .or_insert_with(|| bson::Bson::Document(Document::new()))
                        .as_document_mut()
                        .unwrap()
                        .insert(child, v);
                } else {
                    doc_state.insert(k, v);
                }
            }
            let sessions = doc_state.get_array_mut("pastSessions").unwrap();
            sessions.push(bson::Bson::Document(event));
            sessions.sort_by_key(|b| {
                let d = b.as_document().unwrap();
                (
                    d.get_datetime("data").unwrap().timestamp_millis(),
                    d.get_str("attempt_id").unwrap().to_string(),
                )
            });
            doc_state
        };

        // Path A: Chronological arrival (t1 -> t2)
        let in_order = apply_step(
            Some(apply_step(None, &older_offline_wrong)),
            &newer_online_right,
        );
        // Path B: Out-of-order offline sync arrival (t2 -> t1)
        let out_of_order = apply_step(
            Some(apply_step(None, &newer_online_right)),
            &older_offline_wrong,
        );

        let parsed_in: UserActivityDocument = bson::from_document(in_order.clone()).unwrap();
        let parsed_out: UserActivityDocument = bson::from_document(out_of_order.clone()).unwrap();

        assert_eq!(parsed_out.datas.ultima_sessao, t2);
        assert_eq!(parsed_out.datas.data_primeira_sessao, t1);
        assert_eq!(
            out_of_order
                .get_document("datas")
                .unwrap()
                .get_datetime("ultima_resposta_em")
                .unwrap()
                .to_chrono(),
            t2
        );
        // Chronological order in pastSessions preserved [t1, t2]
        assert_eq!(parsed_out.past_sessions.len(), 2);
        assert_eq!(parsed_out.past_sessions[0].data, t1);
        assert_eq!(parsed_out.past_sessions[1].data, t2);

        // Final SRS & notebook state matches chronological replay [false, true], never regressing to t1!
        assert_eq!(parsed_out.streak_atual, parsed_in.streak_atual);
        assert_eq!(parsed_out.streak_atual, 1);
        assert_eq!(parsed_out.points, parsed_in.points);
        assert_eq!(
            parsed_out.spaced_repetition_interval,
            parsed_in.spaced_repetition_interval
        );
        assert_eq!(parsed_out.in_error_notebook, parsed_in.in_error_notebook);
        // Notice: t1 wrong put question in notebook; t2 right with confidence=1 and streak=1 keeps it in notebook!
        assert!(parsed_out.in_error_notebook);
        assert!(!parsed_out.high_confidence_error_flag);
        // Newer user_note at t2 is NOT overwritten by older offline note at t1
        assert_eq!(parsed_out.user_note.as_deref(), Some("Nota recente online"));
    }

    #[test]
    fn blank_omissions_do_not_pollute_confidence_avg_response_time_or_trigger_review_due() {
        let q: QuestionDocument = bson::from_document(doc! {
            "_id": ObjectId::new(),
            "question": "Julgue o item sobre política monetária.",
            "answers": ["Certo", "Errado"],
            "correct_answers": ["Certo"],
            "disciplina": "Economia"
        })
        .unwrap();
        let now = Utc::now();
        let blank_only = AnswerAttemptItem {
            exam_component_id: None,
            attempt_id: uuid::Uuid::new_v4().to_string(),
            occurred_at: (now - Duration::days(10)).to_rfc3339(),
            client_answered_at: None,
            question_revision: q.revision().unwrap(),
            question_id: q.id.to_hex(),
            chosen_index: 0,
            chosen_text: String::new(),
            is_correct: false,
            time_spent_seconds: 8,
            confidence_level: 0,
            session_uuid: "sim-1".into(),
            quiz_mode: "SIMULADO".into(),
            exam_block: None,
            contest_id: None,
            user_note: None,
            canonical_subject_id: None,
            canonical_topic_id: None,
            is_blank: true,
            confidence_explicit: false,
            selected_evidence_id: None,
            position_in_session: None,
            elapsed_session_seconds: Some(120),
            session_interrupted: false,
        };
        let (fields_b, event_b, ack_b) =
            project(empty(99, &q, now - Duration::days(10)), &q, &blank_only, now - Duration::days(10))
                .unwrap();
        assert!(!ack_b.high_confidence_error_alert);
        assert_eq!(ack_b.attempts_total, 0);
        assert_eq!(ack_b.confidence_avg, None);
        assert!(!fields_b.get_bool("revisao_necessaria").unwrap());

        let mut raw_doc = doc! {
            "_id": ObjectId::new(),
            "user_id": 99_i64,
            "question_id": q.id,
            "datas": {
                "ultima_sessao": bson::DateTime::from_chrono(now - Duration::days(10)),
                "data_primeira_sessao": bson::DateTime::from_chrono(now - Duration::days(10)),
            },
            "tempos": { "tempo_total_estudo": 8.0, "tempo_medio_resposta": 0.0 },
            "disciplina": "Economia",
            "pastSessions": [event_b],
            "quantidade_praticas": 0,
        };
        let act_b: UserActivityDocument = bson::from_document(raw_doc.clone()).unwrap();
        assert!(!act_b.review_due(now));

        // Now answer with confidence 3 and 30s -> average confidence is 3 (not diluted by blank's 0)
        // and average response time is 30.0s (not diluted by blank's 8s)
        let answered = AnswerAttemptItem {
            attempt_id: uuid::Uuid::new_v4().to_string(),
            occurred_at: now.to_rfc3339(),
            chosen_text: "Certo".into(),
            is_correct: true,
            time_spent_seconds: 30,
            confidence_level: 3,
            is_blank: false,
            confidence_explicit: true,
            ..blank_only
        };
        let (fields_a, _, ack_a) = project(act_b, &q, &answered, now).unwrap();
        assert_eq!(ack_a.confidence_avg, Some(3));
        assert!((fields_a.get_f64("tempos.tempo_medio_resposta").unwrap() - 30.0).abs() < 1e-6);
        raw_doc.insert("dominada", false);
    }
}
