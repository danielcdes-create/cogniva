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

/// Rotina diária unificada em Rust de revisão espaçada (SRS) e decay de pontos (`points_with_decay`).
/// Decodifica cada documento isoladamente para que um documento malformado nunca aborte o job.
pub async fn run_daily_reviews_decay_and_countdown(
    db: &Database,
) -> Result<DailyMaintenanceReport, AppError> {
    tracing::info!("Iniciando rotina diária de revisão espaçada (SRS) e decay de pontos...");
    let now = Utc::now();
    let act_coll = db.collection::<Document>("userActivityLog");

    let mut cursor = act_coll.find(doc! {}).await?;
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

        let needs_review = item.revisao_necessaria
            || now
                >= item.datas.ultima_sessao
                    + chrono::Duration::days(item.spaced_repetition_interval.max(1) as i64);

        let new_points_with_decay = calculate_decay(item.points, item.datas.ultima_sessao, now);
        let decay_changes = (new_points_with_decay - item.points_with_decay).abs() > 1e-5;
        let mark_review = needs_review && !item.revisao_necessaria;

        let mut set_fields = Document::new();
        if decay_changes {
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

/// Inicia o agendador assíncrono em background dentro do binário Rust.
pub fn spawn_background_daily_scheduler(db: Database) {
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_secs(30)).await;
        let mut interval = tokio::time::interval(Duration::from_secs(24 * 60 * 60));
        loop {
            interval.tick().await;
            if let Err(e) = run_daily_reviews_decay_and_countdown(&db).await {
                tracing::error!(
                    "Erro durante execução da rotina diária em background: {:?}",
                    e
                );
            }
        }
    });
}
