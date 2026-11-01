use std::env;
use std::path::PathBuf;

#[derive(Clone, Debug)]
pub struct AppConfig {
    pub port: u16,
    pub mongodb_uri: String,
    pub mongodb_db: String,
    pub openai_api_key: Option<String>,
    pub openai_model: String,
    pub gemini_api_key: Option<String>,
    pub gemini_model: String,
    pub jwt_secret: String,
    pub apk_storage_path: PathBuf,
    pub apk_metadata_path: PathBuf,
}

impl AppConfig {
    pub fn from_env() -> Self {
        let _ = dotenvy::dotenv();

        let port = env::var("PORT")
            .ok()
            .and_then(|p| p.parse::<u16>().ok())
            .unwrap_or(8000);

        let mongodb_uri = env::var("MONGODB_URI")
            .unwrap_or_else(|_| "mongodb://localhost:27017".to_string());

        let mongodb_db = env::var("MONGODB_DB").unwrap_or_else(|_| "CNU".to_string());

        let openai_api_key = env::var("OPENAI_API_KEY").ok().filter(|s| !s.is_empty());
        let openai_model = env::var("OPENAI_MODEL")
            .unwrap_or_else(|_| "gpt-4o-mini-2024-07-18".to_string());

        let gemini_api_key = env::var("GEMINI_API_KEY").ok().filter(|s| !s.is_empty());
        let gemini_model = env::var("GEMINI_MODEL")
            .unwrap_or_else(|_| "gemini-1.5-flash-latest".to_string());

        let jwt_secret = env::var("JWT_SECRET")
            .unwrap_or_else(|_| "dev_concurso_secret_key_change_in_prod".to_string());

        let apk_storage_path = env::var("APK_STORAGE_PATH")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/app/apk/app-debug.apk"));

        let apk_metadata_path = env::var("APK_METADATA_PATH")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/app/apk/output-metadata.json"));

        Self {
            port,
            mongodb_uri,
            mongodb_db,
            openai_api_key,
            openai_model,
            gemini_api_key,
            gemini_model,
            jwt_secret,
            apk_storage_path,
            apk_metadata_path,
        }
    }
}
