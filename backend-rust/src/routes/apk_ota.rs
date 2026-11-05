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

fn apk_missing() -> ApkError {
    (StatusCode::NOT_FOUND, "APK indisponível".into())
}

pub async fn read_apk_bytes(configured: &std::path::Path) -> Result<(std::path::PathBuf, Vec<u8>), ApkError> {
    let apk = tokio::fs::canonicalize(configured).await.map_err(|_| apk_missing())?;
    let bytes = tokio::fs::read(&apk).await.map_err(|_| apk_missing())?;
    Ok((apk, bytes))
}

async fn read_release(state: &AppState) -> Result<(Vec<u8>, i64, String), ApkError> {
    // Resolve current once. Bytes and metadata come from the same immutable
    // release directory even if a promotion swaps the symlink concurrently.
    let (apk, bytes) = read_apk_bytes(&state.config.apk_storage_path).await?;
    let metadata =
        if state.config.apk_storage_path.parent() == state.config.apk_metadata_path.parent() {
            apk.parent()
                .unwrap()
                .join(state.config.apk_metadata_path.file_name().unwrap())
        } else {
            state.config.apk_metadata_path.clone()
        };
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
    let (_path, bytes) = read_apk_bytes(&state.config.apk_storage_path).await?;

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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn info_and_download_read_the_same_file_and_hide_os_errors() {
        let dir = std::env::temp_dir().join(format!("cogniva-apk-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("cogniva.apk");
        std::fs::write(&file, b"same-bytes").unwrap();
        let (first_path, first) = read_apk_bytes(&file).await.unwrap();
        let (second_path, second) = read_apk_bytes(&file).await.unwrap();
        assert_eq!(first, second);
        assert_eq!(first_path, second_path);
        assert_eq!(first, b"same-bytes");
        let missing = read_apk_bytes(&dir.join("absent.apk")).await.unwrap_err();
        assert_eq!(missing.0, StatusCode::NOT_FOUND);
        assert_eq!(missing.1, "APK indisponível");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
