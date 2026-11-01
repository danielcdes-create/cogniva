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
use services::exam_engine::ExamMemory;
use services::scheduler::spawn_background_daily_scheduler;
use std::{net::SocketAddr, sync::Arc, sync::Mutex};
use tower_http::{
    compression::CompressionLayer,
    cors::{Any, CorsLayer},
    trace::TraceLayer,
};

pub struct AppState {
    pub db: Database,
    pub config: AppConfig,
    pub exams: Mutex<ExamMemory>,
}

impl AppState {
    pub fn new(db: Database, config: AppConfig) -> Self {
        Self {
            db,
            config,
            exams: Mutex::new(ExamMemory::seeded()),
        }
    }
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

    let state = Arc::new(AppState::new(db, config.clone()));

    let app = build_router(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], config.port));
    tracing::info!("🚀 api-rust-core escutando em http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}

pub fn build_router(state: Arc<AppState>) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    Router::new()
        .route(
            "/api/v1/health",
            get(|| async {
                Json(json!({
                    "status": "ok",
                    "service": "api-rust-core",
                    "brand": "COGNIVA",
                    "stack": "Rust (Axum + Tokio + MongoDB)"
                }))
            }),
        )
        .route("/api/v1/apk/info", get(routes::apk_ota::get_apk_info))
        .route("/api/v1/apk/download", get(routes::apk_ota::download_apk))
        .route("/apk", get(routes::apk_ota::download_apk))
        .route("/cogniva.apk", get(routes::apk_ota::download_apk))
        .route("/api/app/latest-apk", get(routes::apk_ota::get_latest_apk))
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
        .route("/api/v1/home/today", get(routes::home::get_today_dashboard))
        .route("/api/v1/contests", get(routes::contests::list_contests))
        .route(
            "/api/v1/contests/:contest_id/syllabus",
            get(routes::contests::get_contest_syllabus),
        )
        .route(
            "/api/v1/contests/:contest_id/diff",
            get(routes::contests::get_contest_edital_diff),
        )
        .route(
            "/api/v1/quiz/generate",
            post(routes::quiz::handle_generate_quiz),
        )
        .route(
            "/api/v1/quiz/submit-batch",
            post(routes::quiz::handle_submit_batch),
        )
        .route(
            "/api/v1/questions/:id/ai/:kind",
            post(routes::ai_help::handle_question_ai_assist),
        )
        .route(
            "/api/v1/questions/:id/report",
            post(routes::quiz::handle_report_problem),
        )
        .route(
            "/api/v1/analytics/progress",
            get(routes::analytics::get_user_progress_analytics),
        )
        .route("/api/v1/catalog/trunks", get(routes::exams::get_trunk_catalog))
        .route(
            "/api/v1/exams",
            get(routes::exams::list_exams).post(routes::exams::create_exam),
        )
        .route("/api/v1/exams/:exam_id", put(routes::exams::update_exam))
        .route(
            "/api/v1/exams/:exam_id/publish-edital",
            post(routes::exams::publish_edital),
        )
        .route(
            "/api/v1/exams/:exam_id/weighted-simulado",
            post(routes::exams::weighted_simulado),
        )
        .route(
            "/api/v1/questions/batch/trunk-common",
            post(routes::exams::trunk_common_batch),
        )
        .layer(CompressionLayer::new())
        .layer(TraceLayer::new_for_http())
        .layer(cors)
        .with_state(state)
}

#[cfg(test)]
mod latest_apk_tests {
    use super::*;
    use axum::body::Body;
    use axum::http::Request;
    use sha2::{Digest, Sha256};
    use tower::ServiceExt;

    fn sha256_hex(bytes: &[u8]) -> String {
        Sha256::digest(bytes)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect()
    }

    #[tokio::test]
    async fn latest_apk_size_and_sha256_come_from_the_file_and_repeat() {
        let dir = std::env::temp_dir().join(format!(
            "cogniva-latest-apk-{}",
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let apk_path = dir.join("app-debug.apk");
        let meta_path = dir.join("output-metadata.json");
        let apk_bytes = b"cogniva-fixture-apk-bytes-v1-not-a-constant-hash";
        std::fs::write(&apk_path, apk_bytes).unwrap();
        std::fs::write(
            &meta_path,
            r#"{"elements":[{"versionCode":3,"versionName":"1.2.0"}]}"#,
        )
        .unwrap();

        let config = AppConfig {
            port: 0,
            mongodb_uri: "mongodb://127.0.0.1:27017".to_string(),
            mongodb_db: "CNU".to_string(),
            openai_api_key: None,
            openai_model: "gpt-4o-mini".to_string(),
            gemini_api_key: None,
            gemini_model: "gemini-1.5-flash".to_string(),
            jwt_secret: "test".to_string(),
            apk_storage_path: apk_path.clone(),
            apk_metadata_path: meta_path,
        };
        let client = mongodb::Client::with_uri_str(&config.mongodb_uri)
            .await
            .expect("mongodb client parses without dialing");
        let state = Arc::new(AppState::new(client.database(&config.mongodb_db), config));
        let app = build_router(state);

        let mut bodies = Vec::new();
        for _ in 0..2 {
            let response = app
                .clone()
                .oneshot(
                    Request::builder()
                        .uri("/api/app/latest-apk")
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status(), axum::http::StatusCode::OK);
            let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
                .await
                .unwrap();
            bodies.push(String::from_utf8(bytes.to_vec()).unwrap());
        }

        assert_eq!(bodies[0], bodies[1], "repeat request changed the body");
        let json: serde_json::Value = serde_json::from_str(&bodies[0]).unwrap();
        assert_eq!(
            json["size_bytes"].as_u64().unwrap(),
            apk_bytes.len() as u64
        );
        assert_eq!(
            json["sha256_checksum"].as_str().unwrap(),
            sha256_hex(apk_bytes)
        );

        if let Ok(path) = std::env::var("COGNIVA_LATEST_APK_CAPTURE") {
            std::fs::write(path, format!("{}\n{}\n", bodies[0], bodies[1])).unwrap();
        }

        let _ = std::fs::remove_dir_all(&dir);
    }
}
