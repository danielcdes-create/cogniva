use crate::{
    error::AppError, models::activity::UserActivityDocument, services::srs_engine::calculate_decay,
};
use bson::{doc, Document};
use chrono::Utc;
use futures::stream::TryStreamExt;
use mongodb::Database;
use serde::Serialize;
use std::time::Duration;

#[derive(Debug, Clone, Serialize)]
pub struct DailyMaintenanceReport {
    pub scanned_records: usize,
    pub skipped_malformed: usize,
    pub marked_for_review: usize,
    pub decay_updated: usize,
    pub executed_at_iso: String,
}

/// Avalia deterministicamente se um registro de `userActivityLog` deve receber atualização
/// de decay e/ou marcação `revisao_necessaria`, ignorando omissões em branco (`quantidade_praticas <= 0`
/// ou `spaced_repetition_interval <= 0`) e limitando o intervalo SRS a `1..=3650` dias.
pub fn evaluate_daily_maintenance_item(
    item: &UserActivityDocument,
    now: chrono::DateTime<Utc>,
) -> (Option<f64>, bool) {
    if item.quantidade_praticas <= 0 || item.spaced_repetition_interval <= 0 {
        return (None, false);
    }
    let anchor = item.effective_last_answered();
    let clamped_days = item.spaced_repetition_interval.clamp(1, 3650) as i64;
    let retention_due = item.memory_stability_days > 0.0
        && crate::services::retention_engine::retrievability(
            (now - anchor).num_hours().max(0) as f64 / 24.0,
            item.memory_stability_days,
        ) <= 0.90;
    let needs_review = item.revisao_necessaria
        || retention_due
        || now >= anchor + chrono::Duration::days(clamped_days);

    let new_points_with_decay = calculate_decay(item.points, anchor, now);
    let decay_update = ((new_points_with_decay - item.points_with_decay).abs() > 1e-5)
        .then_some(new_points_with_decay);
    let mark_review = needs_review && !item.revisao_necessaria;
    (decay_update, mark_review)
}

/// Rotina diária unificada em Rust de revisão espaçada (SRS) e decay de pontos (`points_with_decay`).
/// Decodifica cada documento isoladamente para que um documento malformado nunca aborte o job.
pub async fn run_daily_reviews_decay_and_countdown(
    db: &Database,
) -> Result<DailyMaintenanceReport, AppError> {
    tracing::info!("Iniciando rotina diária de revisão espaçada (SRS) e decay de pontos...");
    let now = Utc::now();
    let act_coll = db.collection::<Document>("userActivityLog");

    let mut cursor = act_coll
        .find(doc! {})
        .projection(doc! { "pastSessions.ack": 0, "pastSessions.payload_sha256": 0 })
        .await?;
    let mut scanned_records = 0usize;
    let mut skipped_malformed = 0usize;
    let mut marked_for_review = 0usize;
    let mut decay_updated = 0usize;

    while let Some(raw_doc) = cursor.try_next().await? {
        scanned_records += 1;
        let item = match bson::from_document::<UserActivityDocument>(raw_doc) {
            Ok(parsed) => parsed,
            Err(err) => {
                skipped_malformed += 1;
                tracing::warn!(
                    "Documento userActivityLog malformado ignorado na rotina diária: {}",
                    err
                );
                continue;
            }
        };
        let Some(doc_id) = item.id else {
            continue;
        };

        let (decay_opt, mark_review) = evaluate_daily_maintenance_item(&item, now);
        let decay_changes = decay_opt.is_some();

        let mut set_fields = Document::new();
        if let Some(new_points_with_decay) = decay_opt {
            set_fields.insert("points_with_decay", new_points_with_decay);
        }
        if mark_review {
            set_fields.insert("revisao_necessaria", true);
        }

        if decay_changes || mark_review {
            let result = act_coll
                .update_one(
                    doc! {
                        "_id": doc_id,
                        "datas.ultima_sessao": bson::DateTime::from_chrono(item.datas.ultima_sessao),
                        "points": item.points,
                        "spaced_repetition_interval": item.spaced_repetition_interval
                    },
                    doc! {
                        "$set": set_fields,
                        "$inc": { "state_revision": 1_i64 }
                    },
                )
                .await?;
            if result.modified_count > 0 {
                if decay_changes {
                    decay_updated += 1;
                }
                if mark_review {
                    marked_for_review += 1;
                }
            }
        }
    }

    tracing::info!(
        "Rotina diária concluída: {} avaliados, {} ignorados, {} novas revisões marcadas, {} decays atualizados.",
        scanned_records,
        skipped_malformed,
        marked_for_review,
        decay_updated
    );

    Ok(DailyMaintenanceReport {
        scanned_records,
        skipped_malformed,
        marked_for_review,
        decay_updated,
        executed_at_iso: now.to_rfc3339(),
    })
}

/// Inicia o agendador diário em background com encerramento gracioso via canal `watch`.
pub fn spawn_background_daily_scheduler_with_shutdown(
    db: Database,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
) {
    tokio::spawn(async move {
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_secs(30)) => {}
            _ = shutdown_rx.changed() => {
                if *shutdown_rx.borrow() {
                    tracing::info!("Agendador diário encerrado antes do primeiro ciclo.");
                    return;
                }
            }
        }
        let mut interval = tokio::time::interval(Duration::from_secs(24 * 60 * 60));
        loop {
            tokio::select! {
                _ = interval.tick() => {
                    if let Err(e) = run_daily_reviews_decay_and_countdown(&db).await {
                        tracing::error!(
                            "Erro durante execução da rotina diária em background: {:?}",
                            e
                        );
                    }
                }
                _ = shutdown_rx.changed() => {
                    if *shutdown_rx.borrow() {
                        tracing::info!("Agendador diário em background encerrado graciosamente.");
                        break;
                    }
                }
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::activity::ActivityDates;
    use bson::oid::ObjectId;
    use chrono::Duration as ChronoDuration;

    fn sample_activity(
        practices: i32,
        interval: i32,
        last_session: chrono::DateTime<Utc>,
    ) -> UserActivityDocument {
        UserActivityDocument {
            id: Some(ObjectId::new()),
            user_id: 1,
            question_id: ObjectId::new(),
            datas: ActivityDates {
                ultima_sessao: last_session,
                ultima_resposta_em: Some(last_session),
                ultima_revisao: None,
                data_primeira_sessao: last_session,
                data_primeiro_acerto: None,
            },
            tempos: Default::default(),
            disciplina: "Economia".into(),
            past_sessions: vec![],
            quantidade_praticas: practices,
            quantidade_acertos: practices,
            quantidade_erros: 0,
            percentual_acertos: 100.0,
            percentual_revisoes_sucesso: None,
            var_alternativa_escolhida: 0.0,
            frequencia_estudo_mes: 1.0,
            spaced_repetition_interval: interval,
            spaced_repetition_score: 0.5,
            streak_atual: 1,
            streak_max: 1,
            points: 0.8,
            points_with_decay: 0.8,
            dominada: false,
            nivel_confianca_med_rec: Some(3),
            revisao_necessaria: false,
            in_error_notebook: false,
            high_confidence_error_flag: false,
            user_note: None,
            canonical_subject_id: None,
            canonical_topic_id: None,
            memory_stability_days: 0.0,
            memory_difficulty: 5.0,
        }
    }

    #[test]
    fn daily_maintenance_ignores_blank_omissions_and_clamps_srs_interval_to_3650_days() {
        let now = Utc::now();

        // Strategic blank omission (quantidade_praticas == 0, interval == 0) must NEVER be marked for review or decayed
        let blank = sample_activity(0, 0, now - ChronoDuration::days(30));
        assert_eq!(evaluate_daily_maintenance_item(&blank, now), (None, false));

        // Corrupt negative interval with practices > 0 is also safely ignored
        let zero_interval = sample_activity(2, 0, now - ChronoDuration::days(30));
        assert_eq!(
            evaluate_daily_maintenance_item(&zero_interval, now),
            (None, false)
        );

        // Extreme interval (e.g. 99_999 days) is clamped to 3650 days so 3651 elapsed days triggers review without overflow
        let huge_interval = sample_activity(3, 99_999, now - ChronoDuration::days(3651));
        let (decay_opt, mark_review) = evaluate_daily_maintenance_item(&huge_interval, now);
        assert!(mark_review);
        assert!(decay_opt.is_some());

        let before_clamp = sample_activity(3, 99_999, now - ChronoDuration::days(3649));
        let (_, mark_before) = evaluate_daily_maintenance_item(&before_clamp, now);
        assert!(!mark_before);
    }
}
