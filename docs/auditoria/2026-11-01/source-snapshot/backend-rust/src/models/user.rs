use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserProfileDocument {
    pub user_id: i64,
    pub username: Option<String>,
    pub display_name: Option<String>,
    #[serde(default = "default_daily_target")]
    pub daily_questions_target: i32,
    #[serde(default = "default_daily_hours")]
    pub daily_study_hours_target: f64,
    /// Múltiplos concursos simultâneos (escada de concursos)
    #[serde(default)]
    pub active_contest_ids: Vec<String>,
    #[serde(default)]
    pub current_daily_streak: i32,
    #[serde(default = "default_streak_freezes")]
    pub streak_freezes_available: i32,
    #[serde(default)]
    pub xp_total: i64,
}

fn default_daily_target() -> i32 {
    50
}

fn default_daily_hours() -> f64 {
    2.0
}

fn default_streak_freezes() -> i32 {
    1
}
