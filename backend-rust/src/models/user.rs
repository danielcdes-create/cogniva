use bson::oid::ObjectId;
use serde::{Deserialize, Serialize};

fn default_role() -> String {
    "student".to_string()
}

fn default_daily_questions() -> i32 {
    30
}

fn default_daily_hours() -> f64 {
    3.0
}

/// Documento nativo da coleção `usuarios` no Cogniva 2.0 (100% independente de Telegram).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserDocument {
    #[serde(rename = "_id", skip_serializing_if = "Option::is_none")]
    pub id: Option<ObjectId>,
    pub user_id: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub email_normalized: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub password_hash: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    #[serde(default = "default_role")]
    pub role: String,
    #[serde(default)]
    pub is_guest: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub installation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub installation_secret_sha256: Option<String>,
    #[serde(default)]
    pub active_contest_ids: Vec<String>,
    #[serde(default = "default_daily_questions")]
    pub daily_questions_target: i32,
    #[serde(default = "default_daily_hours")]
    pub daily_study_hours_target: f64,
    #[serde(default)]
    pub onboarding_completed: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub created_at: Option<bson::DateTime>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<bson::DateTime>,
}

impl UserDocument {
    pub fn is_admin(&self) -> bool {
        self.role.eq_ignore_ascii_case("admin")
    }

    pub fn to_profile_dto(&self) -> UserProfileDto {
        UserProfileDto {
            user_id: self.user_id,
            email: self.email.clone(),
            display_name: self.display_name.clone(),
            role: if self.is_admin() {
                "admin".to_string()
            } else {
                "student".to_string()
            },
            is_guest: self.is_guest,
            is_admin: self.is_admin(),
            active_contest_ids: self.active_contest_ids.clone(),
            daily_questions_target: self.daily_questions_target,
            daily_study_hours_target: self.daily_study_hours_target,
            onboarding_completed: self.onboarding_completed,
        }
    }
}

/// DTO retornado pelas rotas `/api/v1/auth/me`, `/api/v1/auth/profile` e `/api/v1/auth/onboarding`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct UserProfileDto {
    pub user_id: i64,
    pub email: Option<String>,
    pub display_name: Option<String>,
    pub role: String,
    pub is_guest: bool,
    pub is_admin: bool,
    pub active_contest_ids: Vec<String>,
    pub daily_questions_target: i32,
    pub daily_study_hours_target: f64,
    pub onboarding_completed: bool,
}
