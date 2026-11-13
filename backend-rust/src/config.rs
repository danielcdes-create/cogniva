use crate::error::AppError;
use std::collections::HashSet;
use std::env;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;

#[derive(Clone, Debug)]
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
}

impl AppConfig {
    pub fn from_env() -> Self {
        let (bind_ip, parsed_port) = env::var("BIND_ADDRESS")
            .ok()
            .and_then(|raw| raw.parse::<SocketAddr>().ok())
            .map(|addr| (addr.ip(), Some(addr.port())))
            .unwrap_or((IpAddr::V4(Ipv4Addr::UNSPECIFIED), None));

        let port = env::var("PORT")
            .ok()
            .and_then(|p| p.parse::<u16>().ok())
            .or(parsed_port)
            .unwrap_or(8000);

        let mongodb_uri =
            env::var("MONGODB_URI").unwrap_or_else(|_| "mongodb://127.0.0.1:27017".to_string());

        let mongodb_db = env::var("DATABASE_NAME")
            .or_else(|_| env::var("MONGODB_DB"))
            .unwrap_or_else(|_| "cogniva".to_string());

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
        }
    }

    pub fn validate_server(&self) -> Result<(), AppError> {
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
        Ok(())
    }

    pub fn require_xai_model(&self) -> Result<&str, AppError> {
        if self.xai_api_key.is_none() {
            return Err(AppError::ExternalAiError("xAI não configurada".into()));
        }
        self.xai_model.as_deref().ok_or_else(|| {
            AppError::ExternalAiError(
                "XAI_MODEL não configurado no ambiente do servidor".into(),
            )
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
        };
        assert!(cfg.validate_server().is_err());
        cfg.xai_model = Some("grok-4-fast-non-reasoning".into());
        assert!(cfg.validate_server().is_ok());
        assert_eq!(cfg.require_xai_model().unwrap(), "grok-4-fast-non-reasoning");
    }
}
