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
    if question.answers.get(item.chosen_index) != Some(&item.chosen_text) {
        return Err(AppError::Conflict(
            "Alternativa não corresponde à versão da questão".into(),
        ));
    }
    // Submitted taxonomy is historical client context, never authority. A
    // review may change it while the exact same content is queued offline.
    question.answer_indices()?;
    let occurred = DateTime::parse_from_rfc3339(&item.occurred_at)
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
    }
}

fn project(
    mut activity: UserActivityDocument,
    question: &QuestionDocument,
    item: &AnswerAttemptItem,
    at: DateTime<Utc>,
) -> Result<(Document, Document, ProcessedAttemptResult), AppError> {
    let correct = question.answer_indices()?.contains(&item.chosen_index);
    let mut event = PastSessionEntry {
        attempt_id: item.attempt_id.clone(),
        data: at,
        status: correct,
        alternativa_escolhida: item.chosen_text.clone(),
        tempo_gasto_segundos: item.time_spent_seconds,
        session_uuid: item.session_uuid.clone(),
        modo_sessao: item.quiz_mode.clone(),
        modo_prova: item.exam_block.clone(),
        concurso: item.contest_id.clone(),
        canonical_subject_id: question.canonical_subject_id.clone(),
        canonical_topic_id: question.canonical_topic_id.clone(),
        dias_desde_ultima_sessao: (at - activity.datas.ultima_sessao).num_days().max(0),
        dias_desde_ultima_revisao: None,
        percentual_acertos_acumulado: 0.0,
        tempo_medio_acumulado: 0.0,
        nivel_confianca: item.confidence_level,
        spaced_repetition_interval: 0,
        spaced_repetition_score: 0.0,
        streak_atual: 0,
        points_session: 0.0,
        is_revision: activity.revisao_necessaria,
    };
    activity.past_sessions.push(event.clone());
    // Delivery order cannot change the meaning of occurrence order.
    activity
        .past_sessions
        .sort_by_key(|s| (s.data, s.attempt_id.clone(), s.session_uuid.clone()));
    let statuses: Vec<bool> = activity.past_sessions.iter().map(|s| s.status).collect();
    let streak = calculate_streak_and_points(&statuses);
    activity.quantidade_praticas =
        activity.quantidade_praticas.saturating_add(1).max(activity.past_sessions.len() as i32);
    if correct {
        activity.quantidade_acertos += 1;
    } else {
        activity.quantidade_erros += 1;
    }
    activity.percentual_acertos =
        100.0 * activity.quantidade_acertos as f64 / activity.quantidade_praticas.max(1) as f64;
    activity.tempos.tempo_total_estudo += item.time_spent_seconds as f64;
    activity.tempos.tempo_medio_resposta =
        activity.tempos.tempo_total_estudo / activity.quantidade_praticas.max(1) as f64;
    let last = activity.past_sessions.last().unwrap();
    let (score, interval) = calculate_spaced_repetition_interval(
        streak.streak_atual,
        activity.tempos.tempo_medio_resposta,
        last.tempo_gasto_segundos,
        last.nivel_confianca,
        last.status,
        true,
    );
    activity.datas.ultima_sessao = last.data;
    activity.datas.data_primeira_sessao = activity.datas.data_primeira_sessao.min(at);
    if correct {
        activity.datas.data_primeiro_acerto = Some(
            activity
                .datas
                .data_primeiro_acerto
                .map_or(at, |old| old.min(at)),
        );
    }
    if event.is_revision {
        activity.datas.ultima_revisao =
            Some(activity.datas.ultima_revisao.map_or(at, |old| old.max(at)));
    }
    activity.streak_atual = streak.streak_atual;
    activity.streak_max = streak.streak_max;
    activity.points = streak.points_total;
    activity.points_with_decay = calculate_decay(activity.points, last.data, Utc::now());
    activity.dominada = is_mastered(activity.points)
        && calculate_recent_confidence_avg(&activity.past_sessions, 3).unwrap_or(0) >= 2;
    activity.spaced_repetition_score = score;
    activity.spaced_repetition_interval = interval;
    activity.revisao_necessaria = Utc::now() >= last.data + Duration::days(interval as i64);
    activity.high_confidence_error_flag = if !last.status {
        last.nivel_confianca >= 2
    } else {
        false
    };
    activity.in_error_notebook = next_error_notebook(
        activity.in_error_notebook,
        last.status,
        activity.dominada,
        activity.streak_atual,
        last.nivel_confianca,
    );
    activity.var_alternativa_escolhida = calculate_alternative_variation(&activity.past_sessions);
    activity.frequencia_estudo_mes =
        calculate_monthly_study_frequency(&activity.past_sessions, Utc::now());
    activity.nivel_confianca_med_rec = calculate_recent_confidence_avg(&activity.past_sessions, 3);
    activity.canonical_subject_id = question
        .canonical_subject_id
        .clone()
        .or(activity.canonical_subject_id);
    activity.canonical_topic_id = question
        .canonical_topic_id
        .clone()
        .or(activity.canonical_topic_id);
    if let Some(note) = &item.user_note {
        activity.user_note = Some(note.clone());
    }
    event.percentual_acertos_acumulado = activity.percentual_acertos;
    event.tempo_medio_acumulado = activity.tempos.tempo_medio_resposta;
    event.spaced_repetition_interval = interval;
    event.spaced_repetition_score = score;
    event.streak_atual = streak.streak_atual;
    event.points_session = streak.last_session_points;
    let ack = ProcessedAttemptResult {
        confidence_avg: activity.nivel_confianca_med_rec,
        attempts_total: activity.quantidade_praticas,
        correct_total: activity.quantidade_acertos,
        attempt_id: item.attempt_id.clone(),
        question_id: item.question_id.clone(),
        is_correct: correct,
        confidence_quadrant: classify_confidence_quadrant(correct, item.confidence_level),
        high_confidence_error_alert: !correct && item.confidence_level >= 2,
        streak_atual: activity.streak_atual,
        streak_max: activity.streak_max,
        points_total: activity.points,
        points_with_decay: activity.points_with_decay,
        is_mastered: activity.dominada,
        next_review_interval_days: interval,
        accuracy_accumulated_pct: activity.percentual_acertos,
        in_error_notebook: activity.in_error_notebook,
        next_review_at: (activity.datas.ultima_sessao + Duration::days(interval as i64))
            .to_rfc3339(),
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
            .find_one(doc! {"user_id":user_id,"question_id":qid})
            .await?;
        if let Some(raw) = &existing {
            if let Some(ack) = receipt(raw, item, &hash)? {
                return Ok(ack);
            }
        }
        let question = state
            .db
            .collection::<Document>("todasQuestoes")
            .find_one(doc! {"_id":qid})
            .await?
            .ok_or_else(|| {
                AppError::NotFound(
                    "Questão não encontrada; tentativa preservada para revisão".into(),
                )
            })?;
        let question=crate::services::question_compatibility::decode(question)?;
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
        let mut filter = doc! {"_id":id,"pastSessions.attempt_id":{"$ne":&item.attempt_id}};
        if let Some(revision) = revision {
            filter.insert("state_revision", revision);
        } else {
            filter.insert("state_revision", doc! {"$exists":false});
        }
        let write = activities
            .update_one(
                filter,
                doc! {"$set":fields,"$push":{"pastSessions":event},"$inc":{"state_revision":1_i64},
                "$setOnInsert":{"user_id":user_id,"question_id":qid}},
            )
            .upsert(existing.is_none())
            .await;
        match write {
            Ok(result) if result.matched_count == 1 || result.upserted_id.is_some() => {
                return Ok(ack)
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
        };
        let (_, _, ack) = project(act, &q, &item, now).unwrap();
        assert_eq!(ack.attempts_total, 6);
    }
}
