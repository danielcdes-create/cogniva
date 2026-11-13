pub mod config;
#[cfg(test)]
mod catalog_activation_tests;
#[cfg(test)]
mod contract_tests;
pub mod error;
pub mod models;
pub mod routes;
pub mod security;
pub mod services;

use axum::{
    extract::DefaultBodyLimit,
    http::{header, HeaderValue, Method},
    routing::{get, post, put},
    Json, Router,
};
use config::AppConfig;
use mongodb::{Client, Database};
use serde_json::json;
use services::scheduler::spawn_background_daily_scheduler;
use std::future::IntoFuture;
use std::{net::SocketAddr, sync::Arc};
use tower_http::{
    compression::CompressionLayer,
    cors::{AllowOrigin, CorsLayer},
    trace::TraceLayer,
};

pub struct AppState {
    pub db: Database,
    pub config: AppConfig,
    pub catalog: services::catalog_runtime::Runtime,
}

impl AppState {
    pub fn new(db: Database, config: AppConfig) -> Self {
        Self {
            db,
            config,
            catalog: services::catalog_runtime::Runtime::default(),
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,cogniva_api=debug".into()),
        )
        .init();

    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("--export-catalog") {
        println!(
            "{}",
            serde_json::to_string_pretty(&services::exam_engine::system_catalog())?
        );
        return Ok(());
    }

    let config = AppConfig::from_env();
    if args.get(1).map(String::as_str) == Some("--health-check") {
        reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(5))
            .build()?
            .get(format!("http://127.0.0.1:{}/api/v1/ready", config.port))
            .send()
            .await?
            .error_for_status()?;
        return Ok(());
    }
    if config.jwt_secret.len() < 32 {
        return Err("JWT_SECRET deve ter pelo menos 32 caracteres aleatórios".into());
    }

    tracing::info!(
        "Conectando ao MongoDB (database: {})...",
        config.mongodb_db
    );

    let client = Client::with_uri_str(&config.mongodb_uri).await?;
    let db = client.database(&config.mongodb_db);

    if args.get(1).map(String::as_str) == Some("--grant-admin") {
        let email = args
            .get(2)
            .ok_or("Informe o e-mail da conta cadastrada: cogniva-api --grant-admin <email>")?;
        let updated = routes::auth::grant_admin_by_email(&db, email).await?;
        println!("{}", serde_json::to_string_pretty(&updated)?);
        return Ok(());
    }

    if matches!(
        args.get(1).map(String::as_str),
        Some("--prepare-storage" | "--prepare-question-storage")
    ) {
        let result = services::question_storage::prepare(&db).await?;
        let runtime = services::catalog_runtime::Runtime::default();
        let catalog = runtime.refresh(&db).await?;
        services::catalog_runtime::scope(catalog, async {
            services::question_storage::recover_audit(&db).await?;
            services::classification::recover(&db).await?;
            Ok::<_, error::AppError>(())
        })
        .await?;
        println!("{}", result);
        return Ok(());
    }

    config.validate_server()?;

    let state = Arc::new(AppState::new(db.clone(), config.clone()));
    let catalog = state.catalog.refresh(&db).await?;
    services::catalog_runtime::scope(catalog, async {
        services::question_storage::prepare(&db).await?;
        services::question_storage::recover_audit(&db).await?;
        services::classification::recover(&db).await?;
        Ok::<_, error::AppError>(())
    })
    .await?;

    spawn_background_daily_scheduler(db.clone());

    let app = build_router(state);

    let addr = match std::env::var("BIND_ADDRESS") {
        Ok(raw) if !raw.trim().is_empty() => raw
            .trim()
            .parse::<SocketAddr>()
            .or_else(|_| raw.trim().parse::<std::net::IpAddr>().map(|ip| SocketAddr::new(ip, config.port)))?,
        _ => SocketAddr::new(config.bind_ip, config.port),
    };
    tracing::info!("🚀 cogniva-api v{} escutando em http://{}", env!("CARGO_PKG_VERSION"), addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}

pub fn build_router(state: Arc<AppState>) -> Router {
    let allowed_origin = state.config.public_origin.trim().to_string();
    let cors = CorsLayer::new()
        .allow_origin(AllowOrigin::predicate(move |origin: &HeaderValue, _| {
            let Ok(text) = origin.to_str() else {
                return false;
            };
            (!allowed_origin.is_empty() && text.eq_ignore_ascii_case(&allowed_origin))
                || text.starts_with("http://127.0.0.1:")
                || text.starts_with("http://localhost:")
        }))
        .allow_methods([Method::GET, Method::POST, Method::PUT, Method::OPTIONS])
        .allow_headers([
            header::AUTHORIZATION,
            header::CONTENT_TYPE,
            header::ACCEPT,
        ]);

    let admin_routes = Router::new()
        .route(
            "/api/v1/admin/catalog/activate",
            post(routes::admin_data::catalog_activate),
        )
        .route(
            "/api/v1/admin/catalog/activations/:operation",
            get(routes::admin_data::catalog_activation_status),
        )
        .route(
            "/api/v1/admin/catalog/history",
            get(routes::admin_data::catalog_history),
        )
        .route(
            "/api/v1/admin/catalog/versions/:version",
            get(routes::admin_data::catalog_version),
        )
        .route(
            "/api/v1/admin/catalog/base",
            get(routes::admin_data::catalog_base),
        )
        .route(
            "/api/v1/admin/catalog/validate",
            post(routes::admin_data::catalog_validate),
        )
        .route(
            "/api/v1/admin/catalog/drafts",
            get(routes::admin_data::catalog_list).post(routes::admin_data::catalog_save),
        )
        .route(
            "/api/v1/admin/catalog/drafts/:id",
            get(routes::admin_data::catalog_get),
        )
        .route("/api/v1/admin/summary", get(routes::admin_data::summary))
        .route(
            "/api/v1/admin/questions",
            get(routes::admin_data::questions),
        )
        .route(
            "/api/v1/admin/questions/:id/prepare-edit",
            post(routes::admin_data::prepare_question),
        )
        .route(
            "/api/v1/admin/exam-templates",
            get(routes::admin_data::list_exam_templates)
                .post(routes::admin_data::create_exam_template),
        )
        .route(
            "/api/v1/admin/exam-templates/:id",
            put(routes::admin_data::update_exam_template),
        )
        .route(
            "/api/v1/admin/exam-templates/:id/publish-edital",
            post(routes::admin_data::publish_template_edital),
        )
        .route(
            "/api/v1/admin/reports",
            get(routes::admin_data::list_reports),
        )
        .route(
            "/api/v1/admin/reports/:id/resolve",
            post(routes::admin_data::resolve_report),
        )
        .route("/api/v1/admin/web/me", get(routes::web_admin::me))
        .route("/api/v1/admin/web/logout", post(routes::web_admin::logout))
        .route(
            "/api/v1/admin/questions/import/capabilities",
            get(routes::questions_v2::import_capabilities),
        )
        .route(
            "/api/v1/admin/questions/import/preview",
            post(routes::questions_v2::import_preview),
        )
        .route(
            "/api/v1/admin/questions/import/commit",
            post(routes::questions_v2::import_commit),
        )
        .route(
            "/api/v1/admin/questions/import/assist",
            post(routes::questions_v2::import_assist),
        )
        .route(
            "/api/v1/admin/questions/v2",
            post(routes::questions_v2::save),
        )
        .route(
            "/api/v1/admin/questions/v2/:id",
            get(routes::questions_v2::get),
        )
        .route(
            "/api/v1/admin/classification/proposals",
            get(routes::questions_v2::list_proposals)
                .post(routes::questions_v2::generate_proposals),
        )
        .route(
            "/api/v1/admin/classification/proposals/:id",
            get(routes::questions_v2::review_proposals),
        )
        .route(
            "/api/v1/admin/classification/decisions",
            post(routes::questions_v2::decide_proposal),
        )
        .route(
            "/api/v1/admin/run-daily-maintenance",
            post(routes::auth::handle_trigger_daily_maintenance),
        )
        .route_layer(axum::middleware::from_fn(security::require_admin));

    let authenticated_routes = Router::new()
        .merge(admin_routes)
        .route("/api/v1/auth/me", get(routes::auth::get_profile))
        .route("/api/v1/auth/profile", get(routes::auth::get_profile))
        .route(
            "/api/v1/auth/onboarding",
            put(routes::auth::handle_save_onboarding),
        )
        .route(
            "/api/v1/catalog/knowledge",
            get(routes::questions_v2::catalog),
        )
        .route(
            "/api/v1/catalog/trunks",
            get(routes::exams::get_trunk_catalog),
        )
        .route(
            "/api/v1/questions/cache-state",
            post(routes::questions_v2::cache_state),
        )
        .route(
            "/api/v1/questions/batch/trunk-common",
            post(routes::exams::trunk_common_batch),
        )
        .route("/api/v1/learning/state", get(routes::learning::get_state))
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
            "/api/v1/exams",
            get(routes::exams::list_exams).post(routes::exams::create_exam),
        )
        .route("/api/v1/exams/:exam_id", put(routes::exams::update_exam))
        .route(
            "/api/v1/exams/:exam_id/publish-edital",
            post(routes::exams::publish_edital),
        )
        .route(
            "/api/v1/exams/:exam_id/adopt-template",
            post(routes::exams::adopt_template),
        )
        .route(
            "/api/v1/exams/:exam_id/weighted-simulado",
            post(routes::exams::weighted_simulado),
        )
        .route(
            "/api/v1/exams/:exam_id/compatibility",
            get(routes::exams::get_compatibility).post(routes::exams::decide_compatibility),
        )
        .route_layer(axum::middleware::from_fn_with_state(
            state.clone(),
            security::authenticate,
        ));

    let public_routes = Router::new()
        .route("/admin", get(routes::web_admin::index))
        .route("/admin/", get(routes::web_admin::index))
        .route("/admin/assets/:name", get(routes::web_admin::asset))
        .route(
            "/api/v1/health",
            get(|| async {
                Json(json!({
                    "status": "ok",
                    "service": "cogniva-api",
                    "version": env!("CARGO_PKG_VERSION"),
                    "revision": option_env!("BUILD_REVISION").unwrap_or("local"),
                    "contract_version": 3
                }))
            }),
        )
        .route("/api/v1/ready", get(readiness))
        .route(
            "/api/v1/auth/guest",
            post(routes::auth::handle_guest_session),
        )
        .route(
            "/api/v1/auth/native-session",
            post(routes::auth::handle_guest_session),
        )
        .route(
            "/api/v1/auth/register",
            post(routes::auth::handle_register),
        )
        .route("/api/v1/auth/login", post(routes::auth::handle_login))
        .route("/api/v1/auth/logout", post(routes::auth::handle_logout))
        .route("/api/v1/auth/web-logout", post(routes::web_admin::logout))
        .route("/api/v1/apk/info", get(routes::apk_ota::get_apk_info));

    authenticated_routes
        .merge(public_routes)
        .layer(DefaultBodyLimit::max(2 * 1024 * 1024))
        .layer(CompressionLayer::new())
        .merge(
            Router::new().route(
                "/api/v1/apk/download",
                get(routes::apk_ota::download_apk),
            ),
        )
        .layer(TraceLayer::new_for_http())
        .layer(cors)
        .with_state(state)
}

async fn readiness(
    axum::extract::State(state): axum::extract::State<Arc<AppState>>,
) -> Result<Json<serde_json::Value>, error::AppError> {
    tokio::time::timeout(
        std::time::Duration::from_secs(3),
        state.db.run_command(bson::doc! { "ping": 1 }).into_future(),
    )
    .await
    .map_err(|_| error::AppError::Internal("Banco não respondeu a tempo".into()))??;
    Ok(Json(json!({
        "status": "ready",
        "service": "cogniva-api",
        "contract_version": 3,
        "revision": option_env!("BUILD_REVISION").unwrap_or("local")
    })))
}

#[cfg(test)]
mod latest_apk_tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{header, Request};
    use sha2::{Digest, Sha256};
    use tower::ServiceExt;

    fn sha256_hex(bytes: &[u8]) -> String {
        Sha256::digest(bytes)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect()
    }

    #[tokio::test]
    async fn apk_info_size_and_sha256_come_from_the_file_and_repeat() {
        let dir = std::env::temp_dir().join(format!("cogniva-apk-info-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let apk_path = dir.join("cogniva.apk");
        let meta_path = dir.join("output-metadata.json");
        let apk_bytes = b"cogniva-fixture-apk-bytes-v2-not-a-constant-hash";
        std::fs::write(&apk_path, apk_bytes).unwrap();
        std::fs::write(
            &meta_path,
            r#"{"elements":[{"versionCode":25,"versionName":"2.0.0"}]}"#,
        )
        .unwrap();

        let config = AppConfig {
            bind_ip: std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST),
            port: 0,
            mongodb_uri: "mongodb://127.0.0.1:27017".to_string(),
            mongodb_db: "cogniva".to_string(),
            public_origin: "https://cogniva.local".to_string(),
            admin_emails: std::collections::HashSet::new(),
            xai_api_key: None,
            xai_base_url: "https://api.x.ai/v1".into(),
            xai_model: Some("grok-4".into()),
            xai_daily_user_limit: 120,
            xai_daily_global_limit: 500,
            jwt_secret: "test-secret-with-at-least-32-characters".to_string(),
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
                        .uri("/api/v1/apk/info")
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
        assert_eq!(json["version_code"].as_i64(), Some(25));
        assert_eq!(json["version_name"].as_str(), Some("2.0.0"));
        assert_eq!(json["size_bytes"].as_u64().unwrap(), apk_bytes.len() as u64);
        assert_eq!(
            json["sha256_checksum"].as_str().unwrap(),
            sha256_hex(apk_bytes)
        );

        let download = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/apk/download")
                    .header(header::ACCEPT_ENCODING, "gzip")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(download.status(), axum::http::StatusCode::OK);
        assert!(
            download.headers().get(header::CONTENT_ENCODING).is_none(),
            "APK download must not be re-compressed with gzip"
        );
        assert_eq!(
            download
                .headers()
                .get(header::CONTENT_LENGTH)
                .and_then(|v| v.to_str().ok()),
            Some(apk_bytes.len().to_string().as_str())
        );
        assert_eq!(
            download
                .headers()
                .get("x-accel-buffering")
                .and_then(|v| v.to_str().ok()),
            Some("no")
        );
        let downloaded = axum::body::to_bytes(download.into_body(), usize::MAX)
            .await
            .unwrap();
        assert_eq!(downloaded.as_ref(), apk_bytes);

        if let Ok(path) = std::env::var("COGNIVA_LATEST_APK_CAPTURE") {
            std::fs::write(path, format!("{}\n{}\n", bodies[0], bodies[1])).unwrap();
        }

        let _ = std::fs::remove_dir_all(&dir);
    }
}

#[cfg(test)]
mod integration_tests;
