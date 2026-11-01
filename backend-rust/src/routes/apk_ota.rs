use crate::AppState;
use axum::{
    extract::State,
    http::{header, StatusCode},
    response::Response,
    Json,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::sync::Arc;

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

type ApkError = (StatusCode, String);

async fn read_release(state: &AppState) -> Result<(Vec<u8>, i64, String), ApkError> {
    // Resolve current once. Bytes and metadata come from the same immutable
    // release directory even if a promotion swaps the symlink concurrently.
    let apk = tokio::fs::canonicalize(&state.config.apk_storage_path)
        .await
        .map_err(|_| (StatusCode::NOT_FOUND, "APK indisponível".into()))?;
    let metadata =
        if state.config.apk_storage_path.parent() == state.config.apk_metadata_path.parent() {
            apk.parent()
                .unwrap()
                .join(state.config.apk_metadata_path.file_name().unwrap())
        } else {
            state.config.apk_metadata_path.clone()
        };
    let bytes = tokio::fs::read(apk)
        .await
        .map_err(|_| (StatusCode::NOT_FOUND, "APK indisponível".into()))?;
    let invalid = || {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            "Metadados de versão indisponíveis".into(),
        )
    };
    let raw = tokio::fs::read(metadata).await.map_err(|_| invalid())?;
    let value: Value = serde_json::from_slice(&raw).map_err(|_| invalid())?;
    let item = value
        .get("elements")
        .and_then(Value::as_array)
        .and_then(|a| a.first())
        .ok_or_else(invalid)?;
    let code = item
        .get("versionCode")
        .and_then(Value::as_i64)
        .filter(|v| *v > 0)
        .ok_or_else(invalid)?;
    let name = item
        .get("versionName")
        .and_then(Value::as_str)
        .filter(|v| !v.is_empty())
        .ok_or_else(invalid)?
        .to_string();
    if bytes.is_empty() {
        return Err(invalid());
    }
    Ok((bytes, code, name))
}

pub async fn get_apk_info(State(state): State<Arc<AppState>>) -> Result<Json<Value>, ApkError> {
    let (bytes, code, name) = read_release(&state).await?;
    Ok(Json(apk_info_value(code, &name, &bytes)))
}

/// Public route, independent of MongoDB; metadata is measured from the APK bytes.
pub async fn get_latest_apk(state: State<Arc<AppState>>) -> Result<Json<Value>, ApkError> {
    get_apk_info(state).await
}

pub async fn download_apk(
    State(state): State<Arc<AppState>>,
) -> Result<Response, (StatusCode, String)> {
    let bytes = tokio::fs::read(&state.config.apk_storage_path)
        .await
        .map_err(|e| (StatusCode::NOT_FOUND, format!("APK not found: {}", e)))?;

    Response::builder()
        .status(StatusCode::OK)
        .header(
            header::CONTENT_TYPE,
            "application/vnd.android.package-archive",
        )
        .header(
            header::CONTENT_DISPOSITION,
            "attachment; filename=\"cogniva.apk\"",
        )
        .header(header::CONTENT_LENGTH, bytes.len())
        .body(axum::body::Body::from(bytes))
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}
