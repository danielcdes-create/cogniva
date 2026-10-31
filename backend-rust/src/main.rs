pub mod config;
pub mod error;
pub mod models;
pub mod routes;
pub mod services;

use axum::{
    routing::{get, post, put},
    Json, Router,
};
use config::AppConfig;
use mongodb::{Client, Database};
use serde_json::json;
use services::scheduler::spawn_background_daily_scheduler;
use std::{net::SocketAddr, sync::Arc};
use tower_http::{
    compression::CompressionLayer,
    cors::{Any, CorsLayer},
    trace::TraceLayer,
};

pub struct AppState {
    pub db: Database,
    pub config: AppConfig,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,api_rust_core=debug".into()),
        )
        .init();

    let config = AppConfig::from_env();
    tracing::info!(
        "Conectando ao MongoDB Atlas (database: {})...",
        config.mongodb_db
    );

    let client = Client::with_uri_str(&config.mongodb_uri).await?;
    let db = client.database(&config.mongodb_db);

    // Inicia agendador diário em background (substitui schedule do AFTBot.py + countdown.py)
    spawn_background_daily_scheduler(db.clone());

    let state = Arc::new(AppState {
        db,
        config: config.clone(),
    });

    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    let app = Router::new()
        .route(
            "/api/v1/health",
            get(|| async {
                Json(json!({
                    "status": "ok",
                    "service": "api-rust-core",
                    "stack": "Rust (Axum + Tokio + MongoDB)"
                }))
            }),
        )
        // Autenticação, Vínculo com Conta Telegram Legada e Onboarding em 4 Passos
        .route(
            "/api/v1/auth/telegram-link",
            post(routes::auth::handle_telegram_link_or_login),
        )
        .route(
            "/api/v1/auth/onboarding",
            put(routes::auth::handle_save_onboarding),
        )
        .route(
            "/api/v1/admin/run-daily-maintenance",
            post(routes::auth::handle_trigger_daily_maintenance),
        )
        // Aba 1: Hoje (Pulso diário, revisões SRS, meta e countdown)
        .route("/api/v1/home/today", get(routes::home::get_today_dashboard))
        // Aba 2: Concursos, Edital Verticalizado (Oficial e Estimado) e Tela de Diff
        .route("/api/v1/contests", get(routes::contests::list_contests))
        .route(
            "/api/v1/contests/:contest_id/syllabus",
            get(routes::contests::get_contest_syllabus),
        )
        .route(
            "/api/v1/contests/:contest_id/diff",
            get(routes::contests::get_contest_edital_diff),
        )
        // Aba 3: Praticar (Geração de baterias, Simulado, Badass, SRS, Caderno de Erros, Tronco Comum e Sync Offline)
        .route(
            "/api/v1/quiz/generate",
            post(routes::quiz::handle_generate_quiz),
        )
        .route(
            "/api/v1/quiz/submit-batch",
            post(routes::quiz::handle_submit_batch),
        )
        // Assistentes Pedagógicos de IA (com cache persistente em todasQuestoes) e Reporte
        .route(
            "/api/v1/questions/:id/ai/:kind",
            post(routes::ai_help::handle_question_ai_assist),
        )
        .route(
            "/api/v1/questions/:id/report",
            post(routes::quiz::handle_report_problem),
        )
        // Aba 4: Progresso (Heatmap, Domínio por Disciplina, Matriz de Confiança e Índice de Prontidão)
        .route(
            "/api/v1/analytics/progress",
            get(routes::analytics::get_user_progress_analytics),
        )
        .layer(CompressionLayer::new())
        .layer(TraceLayer::new_for_http())
        .layer(cors)
        .with_state(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], config.port));
    tracing::info!("🚀 api-rust-core escutando em http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
