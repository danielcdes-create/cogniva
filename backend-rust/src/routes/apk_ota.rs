use crate::AppState;
use axum::{
    extract::State,
    http::{header, StatusCode},
    response::Response,
    Json,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{path::Path, sync::Arc};

pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn apk_info_value(version_code: i64, version_name: &str, apk_bytes: &[u8]) -> Value {
    json!({
        "version_code": version_code,
        "version_name": version_name,
        "size_bytes": apk_bytes.len() as u64,
        "sha256_checksum": sha256_hex(apk_bytes),
        "download_path": "/api/v1/apk/download"
    })
}

async fn read_apk_version(metadata_path: &Path) -> (i64, String) {
    let mut version_code = 1_i64;
    let mut version_name = "1.0.0".to_string();
    if let Ok(meta_str) = tokio::fs::read_to_string(metadata_path).await {
        if let Ok(meta_json) = serde_json::from_str::<Value>(&meta_str) {
            if let Some(first_el) = meta_json
                .get("elements")
                .and_then(|e| e.as_array())
                .and_then(|a| a.first())
            {
                if let Some(vc) = first_el.get("versionCode").and_then(|v| v.as_i64()) {
                    version_code = vc;
                }
                if let Some(vn) = first_el.get("versionName").and_then(|v| v.as_str()) {
                    version_name = vn.to_string();
                }
            }
        }
    }
    (version_code, version_name)
}

pub async fn get_apk_info(State(state): State<Arc<AppState>>) -> Json<Value> {
    let apk_bytes = tokio::fs::read(&state.config.apk_storage_path)
        .await
        .unwrap_or_default();
    let (version_code, version_name) = read_apk_version(&state.config.apk_metadata_path).await;
    Json(apk_info_value(version_code, &version_name, &apk_bytes))
}

pub async fn download_apk(
    State(state): State<Arc<AppState>>,
) -> Result<Response, (StatusCode, String)> {
    let bytes = tokio::fs::read(&state.config.apk_storage_path)
        .await
        .map_err(|e| (StatusCode::NOT_FOUND, format!("APK not found: {}", e)))?;

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "application/vnd.android.package-archive")
        .header(
            header::CONTENT_DISPOSITION,
            "attachment; filename=\"cogniva.apk\"",
        )
        .header(header::CONTENT_LENGTH, bytes.len())
        .body(axum::body::Body::from(bytes))
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}
