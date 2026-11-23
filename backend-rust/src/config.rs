use crate::error::AppError;
use std::collections::HashSet;
use std::env;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;

#[derive(Clone)]
pub struct AppConfig {
    pub bind_ip: IpAddr,
    pub port: u16,
    pub mongodb_uri: String,
    pub mongodb_db: String,
    pub jwt_secret: String,
    pub public_origin: String,
    pub admin_emails: HashSet<String>,
    pub xai_api_key: Option<String>,
    pub xai_base_url: String,
    pub xai_model: Option<String>,
    pub xai_daily_global_limit: i64,
    pub xai_daily_user_limit: i64,
    pub apk_storage_path: PathBuf,
    pub apk_metadata_path: PathBuf,
    pub missions_media_dir: PathBuf,
}

impl std::fmt::Debug for AppConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AppConfig")
            .field("bind_ip", &self.bind_ip)
            .field("port", &self.port)
            .field("mongodb_uri", &"<redacted>")
            .field("mongodb_db", &self.mongodb_db)
            .field("jwt_secret", &"<redacted>")
            .field("public_origin", &self.public_origin)
            .field("admin_emails_count", &self.admin_emails.len())
            .field(
                "xai_api_key",
                &self.xai_api_key.as_ref().map(|_| "<redacted>"),
            )
            .field("xai_base_url", &self.xai_base_url)
            .field("xai_model", &self.xai_model)
            .field("xai_daily_global_limit", &self.xai_daily_global_limit)
            .field("xai_daily_user_limit", &self.xai_daily_user_limit)
            .field("apk_storage_path", &self.apk_storage_path)
            .field("apk_metadata_path", &self.apk_metadata_path)
            .field("missions_media_dir", &self.missions_media_dir)
            .finish()
    }
}

pub fn resolve_default_missions_media_dir() -> PathBuf {
    for candidate in [
        PathBuf::from("missoes/videos"),
        PathBuf::from("../missoes/videos"),
        PathBuf::from("/app/missoes/videos"),
    ] {
        if candidate.is_dir() {
            return candidate;
        }
    }
    PathBuf::from("/app/missoes/videos")
}

impl AppConfig {
    pub fn from_env() -> Self {
        let _ = dotenvy::dotenv();

        let (bind_ip, parsed_port) = env::var("BIND_ADDRESS")
            .ok()
            .and_then(|raw| {
                let trimmed = raw.trim();
                trimmed
                    .parse::<SocketAddr>()
                    .map(|addr| (addr.ip(), Some(addr.port())))
                    .or_else(|_| trimmed.parse::<IpAddr>().map(|ip| (ip, None)))
                    .ok()
            })
            .unwrap_or((IpAddr::V4(Ipv4Addr::UNSPECIFIED), None));

        let port = env::var("PORT")
            .ok()
            .and_then(|p| p.parse::<u16>().ok())
            .or(parsed_port)
            .unwrap_or(8000);

        let mongodb_uri =
            env::var("MONGODB_URI").unwrap_or_else(|_| "mongodb://127.0.0.1:27017".to_string());

        let mongodb_db = env::var("MONGODB_DB_NAME")
            .or_else(|_| env::var("DATABASE_NAME"))
            .or_else(|_| env::var("MONGODB_DB"))
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty() && !v.eq_ignore_ascii_case("cnu"))
            .unwrap_or_else(|| "cogniva".to_string());

        let jwt_secret = env::var("JWT_SECRET").unwrap_or_default();

        let public_origin = env::var("COGNIVA_PUBLIC_ORIGIN")
            .ok()
            .map(|v| v.trim().trim_end_matches('/').to_string())
            .filter(|v| !v.is_empty())
            .unwrap_or_else(|| format!("http://127.0.0.1:{port}"));

        let admin_emails = parse_admin_emails(&env::var("ADMIN_EMAILS").unwrap_or_default());

        let xai_api_key = env::var("XAI_API_KEY")
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty());

        let xai_base_url = env::var("XAI_BASE_URL")
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty())
            .unwrap_or_else(|| "https://api.x.ai/v1".to_string());

        let xai_model = env::var("XAI_MODEL")
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty());

        let xai_daily_global_limit = env::var("XAI_DAILY_GLOBAL_LIMIT")
            .ok()
            .and_then(|v| v.parse::<i64>().ok())
            .filter(|&v| v > 0)
            .unwrap_or(500);

        let xai_daily_user_limit = env::var("XAI_DAILY_USER_LIMIT")
            .ok()
            .and_then(|v| v.parse::<i64>().ok())
            .filter(|&v| v > 0)
            .unwrap_or(120);

        let apk_storage_path = PathBuf::from(
            env::var("APK_STORAGE_PATH")
                .unwrap_or_else(|_| "/opt/cogniva/releases/latest.apk".to_string()),
        );
        let apk_metadata_path = PathBuf::from(
            env::var("APK_METADATA_PATH")
                .unwrap_or_else(|_| "/opt/cogniva/releases/apk-info.json".to_string()),
        );
        let missions_media_dir = env::var("MISSIONS_MEDIA_DIR")
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(resolve_default_missions_media_dir);

        Self {
            bind_ip,
            port,
            mongodb_uri,
            mongodb_db,
            jwt_secret,
            public_origin,
            admin_emails,
            xai_api_key,
            xai_base_url,
            xai_model,
            xai_daily_global_limit,
            xai_daily_user_limit,
            apk_storage_path,
            apk_metadata_path,
            missions_media_dir,
        }
    }

    pub fn validate_server(&self) -> Result<(), AppError> {
        if self.mongodb_db.eq_ignore_ascii_case("cnu") {
            return Err(AppError::Internal(
                "O banco legado CNU é estritamente somente-leitura na 2.0.0; use MONGODB_DB_NAME=cogniva"
                    .into(),
            ));
        }
        if self.jwt_secret.len() < 32 {
            return Err(AppError::Internal(
                "JWT_SECRET ausente ou menor que 32 bytes".into(),
            ));
        }
        if self.xai_api_key.is_some() && self.xai_model.is_none() {
            return Err(AppError::Internal(
                "XAI_MODEL deve ser definido explicitamente no ambiente quando XAI_API_KEY estiver configurada"
                    .into(),
            ));
        }
        let parsed_origin = reqwest::Url::parse(self.public_origin.trim()).map_err(|_| {
            AppError::Internal("COGNIVA_PUBLIC_ORIGIN deve ser uma URL HTTP/HTTPS válida".into())
        })?;
        if !matches!(parsed_origin.scheme(), "http" | "https")
            || parsed_origin.host_str().is_none()
            || !parsed_origin.username().is_empty()
            || parsed_origin.password().is_some()
        {
            return Err(AppError::Internal(
                "COGNIVA_PUBLIC_ORIGIN deve usar esquema http:// ou https:// sem credenciais"
                    .into(),
            ));
        }
        Ok(())
    }

    pub fn bind_addr(&self) -> SocketAddr {
        SocketAddr::new(self.bind_ip, self.port)
    }

    pub fn require_xai_model(&self) -> Result<&str, AppError> {
        if self.xai_api_key.is_none() {
            return Err(AppError::ExternalAiError("xAI não configurada".into()));
        }
        self.xai_model.as_deref().ok_or_else(|| {
            AppError::ExternalAiError("XAI_MODEL não configurado no ambiente do servidor".into())
        })
    }

    pub fn is_bootstrap_admin_email(&self, normalized_email: &str) -> bool {
        !normalized_email.is_empty() && self.admin_emails.contains(normalized_email)
    }
}

pub fn parse_admin_emails(raw: &str) -> HashSet<String> {
    raw.split(',')
        .map(|part| part.trim().to_ascii_lowercase())
        .filter(|part| !part.is_empty() && part.contains('@'))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_admin_emails_and_enforces_explicit_xai_model() {
        let emails = parse_admin_emails(" Admin@Cogniva.local , , outro@exemplo.org ");
        assert!(emails.contains("admin@cogniva.local"));
        assert!(emails.contains("outro@exemplo.org"));
        assert_eq!(emails.len(), 2);

        let mut cfg = AppConfig {
            bind_ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
            port: 8000,
            mongodb_uri: "mongodb://127.0.0.1:27017".into(),
            mongodb_db: "cogniva".into(),
            jwt_secret: "0123456789abcdef0123456789abcdef".into(),
            public_origin: "http://127.0.0.1:8000".into(),
            admin_emails: emails,
            xai_api_key: Some("synthetic-key".into()),
            xai_base_url: "https://api.x.ai/v1".into(),
            xai_model: None,
            xai_daily_global_limit: 500,
            xai_daily_user_limit: 120,
            apk_storage_path: PathBuf::from("/tmp/latest.apk"),
            apk_metadata_path: PathBuf::from("/tmp/apk-info.json"),
            missions_media_dir: PathBuf::from("/tmp/missions-videos"),
        };
        assert!(cfg.validate_server().is_err());
        cfg.xai_model = Some("grok-4-fast-non-reasoning".into());
        assert!(cfg.validate_server().is_ok());
        assert_eq!(
            cfg.require_xai_model().unwrap(),
            "grok-4-fast-non-reasoning"
        );
    }
}
