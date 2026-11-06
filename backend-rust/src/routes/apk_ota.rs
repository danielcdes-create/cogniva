use crate::AppState;
use axum::{
    body::{Body, Bytes},
    extract::State,
    http::{header, StatusCode},
    response::Response,
    Json,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    path::{Path, PathBuf},
    sync::{Arc, OnceLock},
    time::SystemTime,
};
use tokio::{io::AsyncReadExt, sync::Mutex};

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

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ApkMeta {
    pub path: PathBuf,
    pub size_bytes: u64,
    pub modified: Option<SystemTime>,
    pub sha256: String,
}

static APK_META_CACHE: OnceLock<Mutex<Option<ApkMeta>>> = OnceLock::new();

type ApkError = (StatusCode, String);

fn apk_missing() -> ApkError {
    (StatusCode::NOT_FOUND, "APK indisponível".into())
}

pub async fn read_apk_bytes(configured: &Path) -> Result<(PathBuf, Vec<u8>), ApkError> {
    let apk = tokio::fs::canonicalize(configured)
        .await
        .map_err(|_| apk_missing())?;
    let bytes = tokio::fs::read(&apk).await.map_err(|_| apk_missing())?;
    Ok((apk, bytes))
}

pub async fn read_apk_meta(configured: &Path) -> Result<ApkMeta, ApkError> {
    let apk = tokio::fs::canonicalize(configured)
        .await
        .map_err(|_| apk_missing())?;
    let fs_meta = tokio::fs::metadata(&apk).await.map_err(|_| apk_missing())?;
    let size_bytes = fs_meta.len();
    let modified = fs_meta.modified().ok();

    let cache_mutex = APK_META_CACHE.get_or_init(|| Mutex::new(None));
    {
        let guard = cache_mutex.lock().await;
        if let Some(cached) = guard.as_ref() {
            if cached.path == apk && cached.size_bytes == size_bytes && cached.modified == modified
            {
                return Ok(cached.clone());
            }
        }
    }

    let mut file = tokio::fs::File::open(&apk)
        .await
        .map_err(|_| apk_missing())?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf).await.map_err(|_| apk_missing())?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    let sha256: String = hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();

    let meta = ApkMeta {
        path: apk,
        size_bytes,
        modified,
        sha256,
    };
    *cache_mutex.lock().await = Some(meta.clone());
    Ok(meta)
}

async fn read_release(state: &AppState) -> Result<(ApkMeta, i64, String), ApkError> {
    // Resolve current once. Metadata and streamed digest come from the same immutable
    // release directory even if a promotion swaps the symlink concurrently.
    let meta = read_apk_meta(&state.config.apk_storage_path).await?;
    let metadata =
        if state.config.apk_storage_path.parent() == state.config.apk_metadata_path.parent() {
            match (
                meta.path.parent(),
                state.config.apk_metadata_path.file_name(),
            ) {
                (Some(parent), Some(file_name)) => parent.join(file_name),
                _ => state.config.apk_metadata_path.clone(),
            }
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
    if meta.size_bytes == 0 {
        return Err(invalid());
    }
    Ok((meta, code, name))
}

pub async fn get_apk_info(State(state): State<Arc<AppState>>) -> Result<Json<Value>, ApkError> {
    let (meta, code, name) = read_release(&state).await?;
    Ok(Json(json!({
        "version_code": code,
        "version_name": name,
        "size_bytes": meta.size_bytes,
        "sha256_checksum": meta.sha256,
        "download_path": "/api/v1/apk/download"
    })))
}

/// Public route, independent of MongoDB; metadata is measured from the APK bytes.
pub async fn get_latest_apk(state: State<Arc<AppState>>) -> Result<Json<Value>, ApkError> {
    get_apk_info(state).await
}

pub async fn download_apk(
    State(state): State<Arc<AppState>>,
) -> Result<Response, (StatusCode, String)> {
    let meta = read_apk_meta(&state.config.apk_storage_path).await?;
    let file = tokio::fs::File::open(&meta.path)
        .await
        .map_err(|_| apk_missing())?;

    let stream = futures::stream::unfold(file, |mut file| async move {
        let mut buf = vec![0u8; 64 * 1024];
        match file.read(&mut buf).await {
            Ok(0) => None,
            Ok(n) => {
                buf.truncate(n);
                Some((Ok::<Bytes, std::io::Error>(Bytes::from(buf)), file))
            }
            Err(e) => Some((Err(e), file)),
        }
    });

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
        .header(header::CONTENT_LENGTH, meta.size_bytes)
        .body(Body::from_stream(stream))
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
        let first_meta = read_apk_meta(&file).await.unwrap();
        let second_meta = read_apk_meta(&file).await.unwrap();
        assert_eq!(first_meta, second_meta);
        assert_eq!(first_meta.sha256, sha256_hex(b"same-bytes"));
        assert_eq!(first_meta.size_bytes, 10);

        let (first_path, first) = read_apk_bytes(&file).await.unwrap();
        let (second_path, second) = read_apk_bytes(&file).await.unwrap();
        assert_eq!(first, second);
        assert_eq!(first_path, second_path);
        assert_eq!(first, b"same-bytes");
        let missing = read_apk_meta(&dir.join("absent.apk")).await.unwrap_err();
        assert_eq!(missing.0, StatusCode::NOT_FOUND);
        assert_eq!(missing.1, "APK indisponível");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
