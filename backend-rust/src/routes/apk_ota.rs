use crate::AppState;
use axum::{
    body::{Body, Bytes},
    extract::State,
    http::{header, HeaderMap, StatusCode},
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

pub fn format_apk_etag(sha256_hex: &str) -> String {
    format!("\"{sha256_hex}\"")
}

pub fn if_none_match_hit(headers: &HeaderMap, etag_quoted: &str) -> bool {
    let expected_unquoted = etag_quoted.trim_matches('"');
    for val in headers.get_all(header::IF_NONE_MATCH) {
        let Ok(raw) = val.to_str() else {
            continue;
        };
        for candidate in raw.split(',') {
            let trimmed = candidate.trim();
            if trimmed == "*" {
                return true;
            }
            let normalized = trimmed
                .strip_prefix("W/")
                .or_else(|| trimmed.strip_prefix("w/"))
                .unwrap_or(trimmed)
                .trim()
                .trim_matches('"');
            if !normalized.is_empty() && normalized.eq_ignore_ascii_case(expected_unquoted) {
                return true;
            }
        }
    }
    false
}

pub async fn read_apk_meta(configured: &Path) -> Result<ApkMeta, ApkError> {
    let apk = tokio::fs::canonicalize(configured)
        .await
        .map_err(|_| apk_missing())?;
    let fs_meta = tokio::fs::metadata(&apk).await.map_err(|_| apk_missing())?;
    let size_bytes = fs_meta.len();
    let modified = fs_meta.modified().ok();

    let cache_mutex = APK_META_CACHE.get_or_init(|| Mutex::new(None));
    let mut guard = cache_mutex.lock().await;
    if let Some(cached) = guard.as_ref() {
        if cached.path == apk && cached.size_bytes == size_bytes && cached.modified == modified {
            return Ok(cached.clone());
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
    *guard = Some(meta.clone());
    Ok(meta)
}

async fn read_release(state: &AppState) -> Result<(ApkMeta, i64, String), ApkError> {
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

pub async fn download_apk(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    let mut meta = read_apk_meta(&state.config.apk_storage_path).await?;
    if meta.size_bytes == 0 {
        return Err(apk_missing());
    }
    let mut etag = format_apk_etag(&meta.sha256);
    if if_none_match_hit(&headers, &etag) {
        return Response::builder()
            .status(StatusCode::NOT_MODIFIED)
            .header(header::ETAG, &etag)
            .header(header::CACHE_CONTROL, "no-cache, no-transform")
            .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff")
            .body(Body::empty())
            .map_err(|_| (StatusCode::INTERNAL_SERVER_ERROR, "Erro interno".into()));
    }

    let mut file = tokio::fs::File::open(&meta.path)
        .await
        .map_err(|_| apk_missing())?;
    let opened_len = file.metadata().await.map_err(|_| apk_missing())?.len();
    if opened_len != meta.size_bytes {
        meta = read_apk_meta(&state.config.apk_storage_path).await?;
        etag = format_apk_etag(&meta.sha256);
        file = tokio::fs::File::open(&meta.path)
            .await
            .map_err(|_| apk_missing())?;
    }
    let limited = file.take(meta.size_bytes);

    let stream = futures::stream::unfold(limited, |mut file| async move {
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
        .header(header::ETAG, &etag)
        .header(header::CACHE_CONTROL, "no-cache, no-transform")
        .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff")
        .header("X-Accel-Buffering", "no")
        .body(Body::from_stream(stream))
        .map_err(|_| (StatusCode::INTERNAL_SERVER_ERROR, "Erro interno".into()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::AppConfig;
    use mongodb::Client;
    use std::collections::HashSet;
    use std::net::{IpAddr, Ipv4Addr};

    async fn test_state_for_apk(apk_path: PathBuf, meta_path: PathBuf) -> Arc<AppState> {
        let client = Client::with_uri_str(
            "mongodb://127.0.0.1:27099/?serverSelectionTimeoutMS=20&connectTimeoutMS=20",
        )
        .await
        .unwrap();
        let cfg = AppConfig {
            bind_ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
            port: 0,
            mongodb_uri: "mongodb://127.0.0.1:27099".into(),
            mongodb_db: "synthetic".into(),
            public_origin: "https://cogniva.local".into(),
            admin_emails: HashSet::new(),
            xai_api_key: None,
            xai_base_url: "https://api.x.ai/v1".into(),
            xai_model: Some("grok-4".into()),
            xai_daily_user_limit: 120,
            xai_daily_global_limit: 500,
            jwt_secret: "synthetic-only-secret-at-least-thirty-two-characters".into(),
            apk_storage_path: apk_path,
            apk_metadata_path: meta_path,
            missions_media_dir: PathBuf::from("missing"),
        };
        Arc::new(AppState::new(client.database("synthetic"), cfg))
    }

    #[tokio::test]
    async fn info_and_download_read_the_same_file_and_hide_os_errors() {
        let dir = std::env::temp_dir().join(format!(
            "cogniva-apk-test-{}",
            uuid::Uuid::new_v4().simple()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("cogniva.apk");
        let metadata_file = dir.join("output-metadata.json");
        std::fs::write(&file, b"same-bytes").unwrap();
        std::fs::write(
            &metadata_file,
            serde_json::to_vec(&json!({
                "elements": [{ "versionCode": 241, "versionName": "2.4.1" }]
            }))
            .unwrap(),
        )
        .unwrap();

        let first_meta = read_apk_meta(&file).await.unwrap();
        let second_meta = read_apk_meta(&file).await.unwrap();
        assert_eq!(first_meta, second_meta);
        assert_eq!(first_meta.sha256, sha256_hex(b"same-bytes"));
        assert_eq!(first_meta.size_bytes, 10);

        let state = test_state_for_apk(file.clone(), metadata_file.clone()).await;
        let Json(info) = get_apk_info(State(state.clone())).await.unwrap();
        assert_eq!(info["version_code"], 241);
        assert_eq!(info["version_name"], "2.4.1");
        assert_eq!(info["size_bytes"], 10);
        assert_eq!(info["sha256_checksum"], sha256_hex(b"same-bytes"));

        let resp = download_apk(State(state.clone()), HeaderMap::new())
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let dl_bytes = axum::body::to_bytes(resp.into_body(), usize::MAX)
            .await
            .unwrap();
        assert_eq!(&dl_bytes[..], b"same-bytes");

        // Cache invalidation when APK payload grows/updates on disk
        std::fs::write(&file, b"updated-apk-payload").unwrap();
        let refreshed_meta = read_apk_meta(&file).await.unwrap();
        assert_eq!(refreshed_meta.size_bytes, 19);
        assert_eq!(refreshed_meta.sha256, sha256_hex(b"updated-apk-payload"));

        let missing = read_apk_meta(&dir.join("absent.apk")).await.unwrap_err();
        assert_eq!(missing.0, StatusCode::NOT_FOUND);
        assert_eq!(missing.1, "APK indisponível");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn if_none_match_handles_strong_weak_wildcard_and_multi_valued_etags() {
        let etag = format_apk_etag("abcdef0123456789");
        assert_eq!(etag, "\"abcdef0123456789\"");

        let mut exact = HeaderMap::new();
        exact.insert(header::IF_NONE_MATCH, etag.parse().unwrap());
        assert!(if_none_match_hit(&exact, &etag));

        let mut weak_list = HeaderMap::new();
        weak_list.insert(
            header::IF_NONE_MATCH,
            "\"other\", W/\"ABCDEF0123456789\"".parse().unwrap(),
        );
        assert!(if_none_match_hit(&weak_list, &etag));

        let mut wildcard = HeaderMap::new();
        wildcard.insert(header::IF_NONE_MATCH, "*".parse().unwrap());
        assert!(if_none_match_hit(&wildcard, &etag));

        let mut miss = HeaderMap::new();
        miss.insert(header::IF_NONE_MATCH, "\"different\"".parse().unwrap());
        assert!(!if_none_match_hit(&miss, &etag));
    }
}
