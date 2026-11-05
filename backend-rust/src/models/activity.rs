use bson::oid::ObjectId;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// Quadrante da Matriz Confiança × Acerto (Nota Técnica Concurso App §8.2)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConfidenceQuadrant {
    HighConfidenceCorrect, // Domínio consolidado
    LowConfidenceCorrect,  // Acerto por intuição/chute — requer fixação
    LowConfidenceError,    // Lacuna reconhecida pelo candidato
    HighConfidenceError,   // ALERTA CRÍTICO: Ponto cego / conceito equivocado enraizado
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActivityDates {
    #[serde(with = "bson::serde_helpers::chrono_datetime_as_bson_datetime")]
    pub ultima_sessao: DateTime<Utc>,
    #[serde(
        default,
        with = "bson::serde_helpers::chrono_datetime_as_bson_datetime_optional"
    )]
    pub ultima_revisao: Option<DateTime<Utc>>,
    #[serde(with = "bson::serde_helpers::chrono_datetime_as_bson_datetime")]
    pub data_primeira_sessao: DateTime<Utc>,
    #[serde(
        default,
        with = "bson::serde_helpers::chrono_datetime_as_bson_datetime_optional"
    )]
    pub data_primeiro_acerto: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ActivityTimes {
    #[serde(default)]
    pub tempo_total_estudo: f64,
    #[serde(default)]
    pub tempo_medio_acerto: f64,
    #[serde(default)]
    pub tempo_medio_erro: f64,
    #[serde(default)]
    pub tempo_medio_resposta: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PastSessionEntry {
    #[serde(default)]
    pub attempt_id: String,
    #[serde(with = "bson::serde_helpers::chrono_datetime_as_bson_datetime")]
    pub data: DateTime<Utc>,
    pub status: bool,
    pub alternativa_escolhida: String,
    pub tempo_gasto_segundos: i32,
    pub session_uuid: String,
    pub modo_sessao: String,
    pub modo_prova: Option<String>,
    pub concurso: Option<String>,
    #[serde(default)]
    pub canonical_subject_id: Option<String>,
    #[serde(default)]
    pub canonical_topic_id: Option<String>,
    pub dias_desde_ultima_sessao: i64,
    pub dias_desde_ultima_revisao: Option<i64>,
    pub percentual_acertos_acumulado: f64,
    pub tempo_medio_acumulado: f64,
    /// 0 = Nenhuma Ideia, 1 = Chute, 2 = Sei Mais ou Menos, 3 = Dominei!
    pub nivel_confianca: i32,
    pub spaced_repetition_interval: i32,
    pub spaced_repetition_score: f64,
    pub streak_atual: i32,
    pub points_session: f64,
    #[serde(default)]
    pub is_revision: bool,
}

/// Documento da coleção `userActivityLog` (100% compatível com o banco atual + extensões do App)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserActivityDocument {
    #[serde(rename = "_id", skip_serializing_if = "Option::is_none")]
    pub id: Option<ObjectId>,
    pub user_id: i64,
    pub question_id: ObjectId,
    pub datas: ActivityDates,
    pub tempos: ActivityTimes,
    pub disciplina: String,
    #[serde(rename = "pastSessions", default)]
    pub past_sessions: Vec<PastSessionEntry>,
    #[serde(default)]
    pub quantidade_praticas: i32,
    #[serde(default)]
    pub quantidade_acertos: i32,
    #[serde(default)]
    pub quantidade_erros: i32,
    #[serde(default)]
    pub percentual_acertos: f64,
    #[serde(default)]
    pub percentual_revisoes_sucesso: Option<f64>,
    #[serde(default)]
    pub var_alternativa_escolhida: f64,
    #[serde(default)]
    pub frequencia_estudo_mes: f64,
    #[serde(default)]
    pub spaced_repetition_interval: i32,
    #[serde(default)]
    pub spaced_repetition_score: f64,
    #[serde(default)]
    pub streak_atual: i32,
    #[serde(default)]
    pub streak_max: i32,
    #[serde(default)]
    pub points: f64,
    #[serde(default)]
    pub points_with_decay: f64,
    #[serde(default)]
    pub dominada: bool,
    #[serde(default)]
    pub nivel_confianca_med_rec: Option<i32>,
    #[serde(default)]
    pub revisao_necessaria: bool,

    // --- Campos Novos para Caderno de Erros e Diagnóstico ---
    #[serde(default)]
    pub in_error_notebook: bool,
    #[serde(default)]
    pub high_confidence_error_flag: bool,
    #[serde(default)]
    pub user_note: Option<String>,
    /// Taxonomia canônica: o SRS e a matriz não ficam presos a um concurso.
    #[serde(default)]
    pub canonical_subject_id: Option<String>,
    #[serde(default)]
    pub canonical_topic_id: Option<String>,
}

impl UserActivityDocument {
    pub fn review_due(&self, now: DateTime<Utc>) -> bool {
        self.revisao_necessaria || now >= self.datas.ultima_sessao + chrono::Duration::days(self.spaced_repetition_interval.max(1) as i64)
    }
    pub fn error_notebook(&self) -> bool {
        if self.dominada {
            return false;
        }
        if let Some(last) = self.past_sessions.last() {
            if last.status && (self.streak_atual >= 2 || last.nivel_confianca >= 2) {
                return false;
            }
        }
        self.in_error_notebook
            || (self.quantidade_erros > self.quantidade_acertos && self.streak_atual < 2)
    }
}
