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
    pub marked_for_review: usize,
    pub decay_updated: usize,
    pub countdown_updated: bool,
    pub executed_at_iso: String,
}

/// Portabilidade unificada em Rust de:
/// 1. `util_manage_reviews_and_decay()` do `AFTBot.py` (verifica intervalo SRS e aplica decay diário de 1% em `points_with_decay`)
/// 2. `countdown.py` (atualiza a coleção `utils` com a contagem regressiva oficial)
pub async fn run_daily_reviews_decay_and_countdown(
    db: &Database,
) -> Result<DailyMaintenanceReport, AppError> {
    tracing::info!(
        "Iniciando rotina diária de revisão espaçada (SRS), decay de pontos e countdown..."
    );
    let now = Utc::now();
    let act_coll = db.collection::<UserActivityDocument>("userActivityLog");

    let mut cursor = act_coll.find(doc! {}).await?;
    let mut scanned_records = 0usize;
    let mut marked_for_review = 0usize;
    let mut decay_updated = 0usize;

    while let Some(item) = cursor.try_next().await? {
        scanned_records += 1;
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

        if needs_review && !item.revisao_necessaria {
            set_fields.insert("revisao_necessaria", true);
        }

        if decay_changes || mark_review {
            let result = act_coll
                .update_one(doc! { "_id": doc_id,"datas.ultima_sessao":bson::DateTime::from_chrono(item.datas.ultima_sessao),"points":item.points,"spaced_repetition_interval":item.spaced_repetition_interval }, doc! { "$set": set_fields })
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

    // Portabilidade do `countdown.py` para a coleção `utils`
    let utils_coll = db.collection::<Document>("utils");
    let now_bson = bson::DateTime::from_chrono(now);
    let countdown_pipeline = vec![
        doc! {"$match":{"dataCNU":{"$type":"date"}}},
        doc! {
            "$addFields": {
                "today": now_bson
            }
        },
        doc! {
            "$project": {
                "_id": 1,
                "dataCNU": 1,
                "today": 1,
                "countdown": {
                    "$divide": [
                        { "$subtract": ["$dataCNU", "$today"] },
                        1000 * 60 * 60 * 24
                    ]
                }
            }
        },
        doc! {
            "$merge": {
                "into": "utils",
                "on": "_id",
                "whenMatched": "merge",
                "whenNotMatched": "discard"
            }
        },
    ];

    let countdown_updated = utils_coll
        .count_documents(doc! {"dataCNU":{"$type":"date"}})
        .await?
        > 0;
    utils_coll.aggregate(countdown_pipeline).await?;

    tracing::info!(
        "Rotina diária concluída: {} registros avaliados, {} novas revisões marcadas, {} decays atualizados.",
        scanned_records,
        marked_for_review,
        decay_updated
    );

    Ok(DailyMaintenanceReport {
        scanned_records,
        marked_for_review,
        decay_updated,
        countdown_updated,
        executed_at_iso: now.to_rfc3339(),
    })
}

/// Inicia o agendador assíncrono em background dentro do próprio binário Rust (Zero overhead de cron externo)
pub fn spawn_background_daily_scheduler(db: Database) {
    tokio::spawn(async move {
        // Executa a primeira verificação 30 segundos após o boot do container e depois a cada 24h
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
