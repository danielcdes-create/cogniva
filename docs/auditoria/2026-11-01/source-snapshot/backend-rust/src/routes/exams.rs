use crate::{
    error::AppError,
    models::exam_hierarchy::{
        ExamBlueprint, ExamLifecycleStatus, PublishEditalRequest, SimuladoCandidate, SubjectOutcome,
        TrunkCatalog, TrunkFocus,
    },
    services::exam_engine::{
        apply_publish, assign_exam_id, draw_weighted_simulado, quotas_from_blueprint,
        select_common_trunk_card, system_catalog, weighted_net_score,
    },
    AppState,
};
use axum::{
    extract::{Path, State},
    Json,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{collections::HashMap, sync::Arc, time::Duration};

#[derive(Debug, Deserialize)]
pub struct WeightedSimuladoRequest {
    pub questions: Vec<SimuladoCandidate>,
    #[serde(default)]
    pub outcomes: Vec<SubjectOutcome>,
}

#[derive(Debug, Serialize)]
pub struct WeightedSimuladoResponse {
    pub question_ids: Vec<String>,
    pub net_score: Option<f64>,
    pub ignored_archived: bool,
}

#[derive(Debug, Deserialize)]
pub struct TrunkCommonRequest {
    #[serde(default)]
    pub mastery_by_subject: HashMap<String, f64>,
    #[serde(default)]
    pub trunk_focus_slug: Option<String>,
    #[serde(default = "default_suggested")]
    pub suggested_questions: u32,
    #[serde(default)]
    pub cached_question_ids_by_subject: HashMap<String, Vec<String>>,
}

fn default_suggested() -> u32 {
    15
}

#[derive(Debug, Serialize)]
pub struct TrunkCommonResponse {
    pub canonical_subject_id: String,
    pub subject_name: String,
    pub contest_names: Vec<String>,
    pub sentence: String,
    pub question_ids: Vec<String>,
}

pub async fn get_trunk_catalog() -> Json<TrunkCatalog> {
    Json(system_catalog())
}

pub async fn list_exams(
    State(state): State<Arc<AppState>>,
) -> Result<Json<Vec<ExamBlueprint>>, AppError> {
    let exams = {
        let guard = state.exams.lock().expect("exam store");
        let mut exams: Vec<_> = guard.exams.values().cloned().collect();
        exams.sort_by(|left, right| {
            left.ladder_priority
                .cmp(&right.ladder_priority)
                .then(left.short_name.cmp(&right.short_name))
        });
        exams
    };
    Ok(Json(exams))
}

pub async fn create_exam(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<serde_json::Value>,
) -> Result<Json<ExamBlueprint>, AppError> {
    let mut exam: ExamBlueprint = serde_json::from_value(payload)
        .map_err(|error| AppError::BadRequest(error.to_string()))?;
    if exam.id.trim().is_empty() || exam.id == "PENDING" {
        exam.id = assign_exam_id(None);
    } else {
        exam.id = assign_exam_id(Some(exam.id));
    }
    if exam.sync_status.trim().is_empty() {
        exam.sync_status = "PENDING_CREATE".to_string();
    }
    if exam.owner_user_id.is_none() {
        exam.owner_user_id = Some("local".to_string());
    }
    {
        let mut guard = state.exams.lock().expect("exam store");
        guard.exams.insert(exam.id.clone(), exam.clone());
    }
    try_persist_exam(&state, &exam).await;
    Ok(Json(exam))
}

pub async fn update_exam(
    State(state): State<Arc<AppState>>,
    Path(exam_id): Path<String>,
    Json(payload): Json<serde_json::Value>,
) -> Result<Json<ExamBlueprint>, AppError> {
    let mut exam: ExamBlueprint = serde_json::from_value(payload)
        .map_err(|error| AppError::BadRequest(error.to_string()))?;
    exam.id = exam_id;
    if exam.sync_status == "SYNCED" || exam.sync_status.is_empty() {
        exam.sync_status = "PENDING_UPDATE".to_string();
    }
    {
        let mut guard = state.exams.lock().expect("exam store");
        if !guard.exams.contains_key(&exam.id) {
            return Err(AppError::NotFound(format!("concurso {}", exam.id)));
        }
        guard.exams.insert(exam.id.clone(), exam.clone());
    }
    try_persist_exam(&state, &exam).await;
    Ok(Json(exam))
}

pub async fn publish_edital(
    State(state): State<Arc<AppState>>,
    Path(exam_id): Path<String>,
    Json(request): Json<PublishEditalRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let (exam, result) = {
        let mut guard = state.exams.lock().expect("exam store");
        let exam = guard
            .exams
            .get_mut(&exam_id)
            .ok_or_else(|| AppError::NotFound(format!("concurso {exam_id}")))?;
        let result = apply_publish(exam, &request);
        (exam.clone(), result)
    };
    try_persist_exam(&state, &exam).await;
    Ok(Json(json!({
        "exam_id": exam.id,
        "status": ExamLifecycleStatus::EditalPublicado,
        "confirmed_count": result.confirmed_count,
        "new_count": result.new_count,
        "archived_count": result.archived_count,
        "topics": result.topics,
        "edital_diff_summary": exam.edital_diff_summary,
    })))
}

pub async fn weighted_simulado(
    State(state): State<Arc<AppState>>,
    Path(exam_id): Path<String>,
    Json(request): Json<WeightedSimuladoRequest>,
) -> Result<Json<WeightedSimuladoResponse>, AppError> {
    let exam = {
        let guard = state.exams.lock().expect("exam store");
        guard
            .exams
            .get(&exam_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("concurso {exam_id}")))?
    };
    let quotas = quotas_from_blueprint(&exam);
    let question_ids = draw_weighted_simulado(&quotas, &request.questions);
    let net_score = if request.outcomes.is_empty() {
        None
    } else {
        Some(weighted_net_score(&request.outcomes))
    };
    Ok(Json(WeightedSimuladoResponse {
        question_ids,
        net_score,
        ignored_archived: true,
    }))
}

pub async fn trunk_common_batch(
    State(state): State<Arc<AppState>>,
    Json(request): Json<TrunkCommonRequest>,
) -> Result<Json<TrunkCommonResponse>, AppError> {
    let inputs = {
        let guard = state.exams.lock().expect("exam store");
        guard.leverage_inputs()
    };
    let focus = request.trunk_focus_slug.as_ref().map(|slug| TrunkFocus {
        slug: slug.clone(),
        core_subject_ids: crate::services::exam_engine::core_subject_ids(slug),
    });
    let today = Utc::now().date_naive();
    let card = select_common_trunk_card(
        &inputs,
        &request.mastery_by_subject,
        focus.as_ref(),
        request.suggested_questions,
        today,
    )
    .ok_or_else(|| {
        AppError::NotFound("nenhuma disciplina elegível para o tronco comum".to_string())
    })?;
    let question_ids = request
        .cached_question_ids_by_subject
        .get(&card.canonical_subject_id)
        .cloned()
        .unwrap_or_default();
    Ok(Json(TrunkCommonResponse {
        canonical_subject_id: card.canonical_subject_id,
        subject_name: card.subject_name,
        contest_names: card.contest_names,
        sentence: card.sentence,
        question_ids,
    }))
}

async fn try_persist_exam(state: &AppState, exam: &ExamBlueprint) {
    let db = state.db.clone();
    let exam = exam.clone();
    let persist = async move {
        let document = match bson::to_document(&exam) {
            Ok(document) => document,
            Err(_) => return,
        };
        let collection = db.collection::<bson::Document>("user_exams");
        let _ = collection
            .replace_one(bson::doc! { "_id": &exam.id }, document)
            .upsert(true)
            .await;
        let _ = db
            .collection::<bson::Document>("userActivityLog")
            .create_index(
                mongodb::IndexModel::builder()
                    .keys(bson::doc! { "canonical_subject_id": 1, "canonical_topic_id": 1 })
                    .build(),
            )
            .await;
    };
    let _ = tokio::time::timeout(Duration::from_millis(300), persist).await;
}

#[cfg(test)]
mod router_tests {
    use super::*;
    use crate::config::AppConfig;
    use crate::AppState;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use std::path::PathBuf;
    use tower::ServiceExt;

    fn test_config() -> AppConfig {
        AppConfig {
            port: 0,
            mongodb_uri: "mongodb://127.0.0.1:27017".to_string(),
            mongodb_db: "CNU".to_string(),
            openai_api_key: None,
            openai_model: "gpt-4o-mini".to_string(),
            gemini_api_key: None,
            gemini_model: "gemini-1.5-flash".to_string(),
            jwt_secret: "test".to_string(),
            apk_storage_path: PathBuf::from("app-debug.apk"),
            apk_metadata_path: PathBuf::from("output-metadata.json"),
        }
    }

    async fn body_json(response: axum::response::Response) -> serde_json::Value {
        let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    #[tokio::test]
    async fn catalog_create_and_publish_round_trip_through_build_router() {
        let client = mongodb::Client::with_uri_str("mongodb://127.0.0.1:27017")
            .await
            .expect("mongodb client parses without dialing");
        let state = Arc::new(AppState::new(client.database("CNU"), test_config()));
        let app = crate::build_router(state);

        let catalog = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/catalog/trunks")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(catalog.status(), StatusCode::OK);
        let catalog_json = body_json(catalog).await;
        let slugs: Vec<&str> = catalog_json["trunks"]
            .as_array()
            .unwrap()
            .iter()
            .map(|trunk| trunk["slug"].as_str().unwrap())
            .collect();
        for slug in ["fiscal", "policial", "controle", "engenharia", "tribunais", "bancaria"] {
            assert!(slugs.contains(&slug), "{slug}");
        }

        let created = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/exams")
                    .header("content-type", "application/json")
                    .body(Body::from(
                        r#"{
                          "id": "sefaz-sp",
                          "title": "SEFAZ-SP",
                          "short_name": "SEFAZ-SP",
                          "institution": "SEFAZ-SP",
                          "role": "Auditor Fiscal",
                          "trunk_slug": "fiscal",
                          "status": "RUMOR_PREVISTO",
                          "exam_boards": ["FCC"],
                          "target_date": {"type": "ESTIMATED_WINDOW", "label": "1º sem/2027", "estimated_sort_date": "2027-06-30"},
                          "historical_cutoff_score": 75.0,
                          "is_active_in_ladder": true,
                          "ladder_priority": 2,
                          "subjects": [{
                            "local_subject_id": "fin",
                            "canonical_subject_id": "FIN_PUB",
                            "display_name": "Finanças Públicas",
                            "category": "CONHECIMENTOS_ESPECIFICOS",
                            "weight": 2.0,
                            "question_count": 15,
                            "relevance_tier": "CORE",
                            "topics": [
                              {"local_topic_id": "t1", "canonical_topic_id": "FIN_PUB_01", "canonical_subject_id": "FIN_PUB", "display_title": "Orçamento", "diff_status": "PRE_EDITAL_BASE"},
                              {"local_topic_id": "t2", "canonical_topic_id": "FIN_PUB_02", "canonical_subject_id": "FIN_PUB", "display_title": "Receita", "diff_status": "PRE_EDITAL_BASE"},
                              {"local_topic_id": "t3", "canonical_topic_id": "OLD_ONLY", "canonical_subject_id": "DIR_TRIB", "display_title": "Sai do edital", "diff_status": "PRE_EDITAL_BASE"}
                            ]
                          }]
                        }"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(created.status(), StatusCode::OK);
        let created_json = body_json(created).await;
        assert_eq!(created_json["_id"].as_str().unwrap(), "sefaz-sp");

        let published = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/exams/sefaz-sp/publish-edital")
                    .header("content-type", "application/json")
                    .body(Body::from(
                        r#"{
                          "published_topics": [
                            {"canonical_topic_id": "FIN_PUB_01", "canonical_subject_id": "FIN_PUB", "display_title": "Orçamento"},
                            {"canonical_topic_id": "FIN_PUB_02", "canonical_subject_id": "FIN_PUB", "display_title": "Receita"},
                            {"canonical_topic_id": "NEW_TOPIC", "canonical_subject_id": "FIN_PUB", "display_title": "Tópico novo"}
                          ],
                          "practiced_canonical_topic_ids": ["NEW_TOPIC", "FIN_PUB_01"]
                        }"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(published.status(), StatusCode::OK);
        let published_json = body_json(published).await;
        assert_eq!(published_json["confirmed_count"].as_u64().unwrap(), 2);
        assert_eq!(published_json["new_count"].as_u64().unwrap(), 1);
        assert_eq!(published_json["archived_count"].as_u64().unwrap(), 1);
    }
}
