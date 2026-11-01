//! Real MongoDB tests. Never use MONGODB_URI or a production database here.
use super::*;
use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use bson::{doc, oid::ObjectId, Document};
use std::path::PathBuf;
use tower::ServiceExt;

fn config(uri: &str) -> AppConfig {
    AppConfig {
        port: 0,
        mongodb_uri: uri.into(),
        mongodb_db: "unused".into(),
        openai_api_key: None,
        openai_model: "unused".into(),
        gemini_api_key: None,
        gemini_model: "unused".into(),
        jwt_secret: "synthetic-only-secret-at-least-thirty-two-characters".into(),
        apk_storage_path: PathBuf::from("missing"),
        apk_metadata_path: PathBuf::from("missing"),
    }
}
async fn request(
    app: &Router,
    secret: &str,
    user: Option<i64>,
    method: &str,
    path: &str,
    body: serde_json::Value,
) -> (StatusCode, serde_json::Value) {
    let mut req = Request::builder()
        .method(method)
        .uri(path)
        .header("content-type", "application/json");
    if let Some(user) = user {
        req = req.header(
            "authorization",
            format!(
                "Bearer {}",
                security::issue(secret, user, "session", 600).unwrap()
            ),
        );
    }
    let response = app
        .clone()
        .oneshot(
            req.body(if body.is_null() {
                Body::empty()
            } else {
                Body::from(body.to_string())
            })
            .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null),
    )
}

#[tokio::test]
async fn protected_routes_reject_untrusted_identity_before_database_access() {
    let uri = "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=20";
    let client = Client::with_uri_str(uri).await.unwrap();
    let cfg = config(uri);
    let secret = cfg.jwt_secret.clone();
    let app = build_router(Arc::new(AppState::new(
        client.database("cogniva_test_auth_no_database"),
        cfg,
    )));
    for path in [
        "/api/v1/home/today?user%5Fid=456",
        "/api/v1/analytics/progress?user_id=%34%35%36",
        "/api/v1/home/today?user_id=123&user_id=456",
    ] {
        assert_eq!(
            request(
                &app,
                &secret,
                Some(123),
                "GET",
                path,
                serde_json::Value::Null
            )
            .await
            .0,
            StatusCode::FORBIDDEN
        );
    }
    assert_eq!(
        request(
            &app,
            &secret,
            None,
            "POST",
            "/api/v1/exams",
            serde_json::json!({})
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        request(
            &app,
            &secret,
            Some(123),
            "GET",
            "/api/v1/home/today?user_id=456",
            serde_json::Value::Null
        )
        .await
        .0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/admin/run-daily-maintenance",
            serde_json::Value::Null
        )
        .await
        .0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        request(
            &app,
            &secret,
            None,
            "POST",
            "/api/v1/auth/telegram-link",
            serde_json::json!({"user_id":123})
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
#[ignore = "Requires a dedicated loopback MongoDB with enableTestCommands=1; scripts/prepare_test_mongo.py"]
async fn durable_events_identity_ladder_and_restart_contract() {
    let uri =
        std::env::var("COGNIVA_TEST_MONGODB_URI").expect("Set the isolated test URI explicitly");
    assert!(
        uri.starts_with("mongodb://127.0.0.1:27048/")
            || uri.starts_with("mongodb://localhost:27048/"),
        "Only the dedicated loopback port is allowed"
    );
    let client = Client::with_uri_str(&uri).await.unwrap();
    let database_name = format!("cogniva_test_{}", uuid::Uuid::new_v4().simple());
    let db = client.database(&database_name);
    let cfg = config(&uri);
    let secret = cfg.jwt_secret.clone();
    let state = Arc::new(AppState::new(db.clone(), cfg.clone()));
    let app = build_router(state.clone());
    for user in [123_i64, 456_i64] {
        db.collection::<Document>("usuarios").insert_one(doc!{"user_id":user,"active_contest_ids":["CPNU"],"daily_questions_target":50,"daily_study_hours_target":2.0}).await.unwrap();
    }
    let qid = ObjectId::new();
    let question_doc = doc! {"_id":qid,"question":"Synthetic question","answers":["A","B"],"correct_answers":["B"],"disciplina":"Finanças Públicas","canonical_subject_id":"FIN_PUB","canonical_topic_id":"FIN_PUB_01","explanation":"Synthetic answer B"};
    db.collection::<Document>("todasQuestoes")
        .insert_one(question_doc.clone())
        .await
        .unwrap();
    let question: models::question::QuestionDocument = bson::from_document(question_doc).unwrap();
    let event_id = uuid::Uuid::new_v4().to_string();
    let occurred = (chrono::Utc::now() - chrono::Duration::days(2)).to_rfc3339();
    let item = serde_json::json!({"attempt_id":event_id,"occurred_at":occurred,"question_revision":question.revision().unwrap(),"question_id":qid.to_hex(),"chosen_index":0,"chosen_text":"A","is_correct":true,"time_spent_seconds":20,"confidence_level":3,"session_uuid":"synthetic-session","quiz_mode":"STANDARD","canonical_subject_id":"FIN_PUB","canonical_topic_id":"FIN_PUB_01"});
    let payload = serde_json::json!({"user_id":123,"attempts":[item.clone()]});
    client.database("admin").run_command(doc!{"configureFailPoint":"failCommand","mode":{"times":1},"data":{"failCommands":["update"],"errorCode":121}}).await.unwrap();
    let (_, failed) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/quiz/submit-batch",
        payload.clone(),
    )
    .await;
    assert_eq!(failed["processed_count"], 0);
    assert_eq!(failed["rejected"][0]["retryable"], true);
    assert_eq!(
        db.collection::<Document>("userActivityLog")
            .count_documents(doc! {})
            .await
            .unwrap(),
        0
    );
    let (_, first) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/quiz/submit-batch",
        payload.clone(),
    )
    .await;
    assert_eq!(first["processed_count"], 1);
    assert_eq!(
        first["results"][0]["is_correct"], false,
        "Server must ignore the client-supplied correctness"
    );
    for _ in 0..3 {
        let (_, again) = request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/quiz/submit-batch",
            payload.clone(),
        )
        .await;
        assert_eq!(first, again);
    }
    let activities = db.collection::<Document>("userActivityLog");
    let persisted = activities
        .find_one(doc! {"user_id":123,"question_id":qid})
        .await
        .unwrap()
        .unwrap();
    assert_eq!(persisted.get_array("pastSessions").unwrap().len(), 1);
    let date = persisted.get_array("pastSessions").unwrap()[0]
        .as_document()
        .unwrap()
        .get_datetime("data")
        .unwrap()
        .to_chrono();
    assert!(
        (date
            - chrono::DateTime::parse_from_rfc3339(&occurred)
                .unwrap()
                .with_timezone(&chrono::Utc))
        .num_milliseconds()
        .abs()
            < 2
    );
    activities.update_one(doc!{"_id":persisted.get_object_id("_id").unwrap()},doc!{"$set":{"unknown_top":"keep","datas.unknown_nested":"keep","tempos.unknown_nested":"keep"}}).await.unwrap();
    let mut a = item.clone();
    a["attempt_id"] = serde_json::json!(uuid::Uuid::new_v4().to_string());
    let mut b = item.clone();
    b["attempt_id"] = serde_json::json!(uuid::Uuid::new_v4().to_string());
    let body_a = serde_json::json!({"user_id":123,"attempts":[a]});
    let body_b = serde_json::json!({"user_id":123,"attempts":[b]});
    let (ra, rb) = tokio::join!(
        request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/quiz/submit-batch",
            body_a
        ),
        request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/quiz/submit-batch",
            body_b
        )
    );
    assert_eq!(ra.1["processed_count"], 1);
    assert_eq!(rb.1["processed_count"], 1);
    let final_record = activities
        .find_one(doc! {"user_id":123,"question_id":qid})
        .await
        .unwrap()
        .unwrap();
    assert_eq!(final_record.get_array("pastSessions").unwrap().len(), 3);
    assert_eq!(final_record.get_str("unknown_top").unwrap(), "keep");
    assert_eq!(
        final_record
            .get_document("datas")
            .unwrap()
            .get_str("unknown_nested")
            .unwrap(),
        "keep"
    );
    let mut changed = item.clone();
    changed["confidence_level"] = serde_json::json!(1);
    let (_, conflict) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/quiz/submit-batch",
        serde_json::json!({"user_id":123,"attempts":[changed]}),
    )
    .await;
    assert_eq!(conflict["processed_count"], 0);
    assert_eq!(conflict["rejected"][0]["retryable"], false);
    let mut valid = item.clone();
    valid["attempt_id"] = serde_json::json!(uuid::Uuid::new_v4().to_string());
    let mut invalid = valid.clone();
    invalid["question_id"] = serde_json::json!("invalid");
    let partial = serde_json::json!({"user_id":123,"attempts":[valid,invalid]});
    for _ in 0..2 {
        let (_, answer) = request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/quiz/submit-batch",
            partial.clone(),
        )
        .await;
        assert_eq!(answer["processed_count"], 1);
        assert_eq!(answer["rejected"].as_array().unwrap().len(), 1);
    }
    assert_eq!(
        activities
            .find_one(doc! {"user_id":123,"question_id":qid})
            .await
            .unwrap()
            .unwrap()
            .get_array("pastSessions")
            .unwrap()
            .len(),
        4
    );

    let mut exam = services::exam_engine::system_exam_blueprints().remove(0);
    exam.id = uuid::Uuid::new_v4().to_string();
    exam.title = "Private synthetic exam".into();
    let (created_status, created) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/exams",
        serde_json::to_value(exam).unwrap(),
    )
    .await;
    assert_eq!(created_status, StatusCode::OK);
    assert_eq!(created["revision"], 1);
    let restart = build_router(Arc::new(AppState::new(db.clone(), cfg)));
    let (_, restored) = request(
        &restart,
        &secret,
        Some(123),
        "GET",
        "/api/v1/exams",
        serde_json::Value::Null,
    )
    .await;
    assert!(restored
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["_id"] == created["_id"]));
    let (_, others) = request(
        &restart,
        &secret,
        Some(456),
        "GET",
        "/api/v1/exams",
        serde_json::Value::Null,
    )
    .await;
    assert!(!others
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["_id"] == created["_id"]));
    let mut edited = created.clone();
    edited["title"] = serde_json::json!("Revision two");
    let path = format!("/api/v1/exams/{}", created["_id"].as_str().unwrap());
    let (_, saved) = request(&restart, &secret, Some(123), "PUT", &path, edited.clone()).await;
    assert_eq!(saved["revision"], 2);
    assert_eq!(
        request(&restart, &secret, Some(123), "PUT", &path, edited.clone())
            .await
            .1,
        saved
    );
    edited["title"] = serde_json::json!("Stale edit");
    assert_eq!(
        request(&restart, &secret, Some(123), "PUT", &path, edited)
            .await
            .0,
        StatusCode::CONFLICT
    );
    let published_topic = serde_json::json!({"canonical_topic_id":saved["subjects"][0]["topics"][0]["canonical_topic_id"],"canonical_subject_id":saved["subjects"][0]["canonical_subject_id"],"display_title":"Reviewed publication"});
    let publication =
        serde_json::json!({"expected_revision":2,"published_topics":[published_topic]});
    let publish_path = format!("{path}/publish-edital");
    let (status, published) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        &publish_path,
        publication.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(published["revision"], 3);
    assert_eq!(
        request(&app, &secret, Some(123), "POST", &publish_path, publication)
            .await
            .1,
        published
    );
    assert!(
        published["edital_diff_summary"]["archived_count"]
            .as_u64()
            .unwrap()
            > 0
    );
    let stored = db
        .collection::<Document>("user_exams")
        .find_one(doc! {"exam_id":created["_id"].as_str().unwrap()})
        .await
        .unwrap()
        .unwrap();
    assert_eq!(stored.get_array("revision_history").unwrap().len(), 2);

    let (status, analytics) = request(
        &app,
        &secret,
        Some(123),
        "GET",
        "/api/v1/analytics/progress?user_id=123",
        serde_json::Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(analytics["activity_heatmap"].as_array().unwrap().len(), 90);
    assert_eq!(analytics["confidence_matrix"]["high_confidence_error"], 4);
    assert_eq!(analytics["global_accuracy_pct"], 0.0);
    let (status, today) = request(
        &app,
        &secret,
        Some(123),
        "GET",
        "/api/v1/home/today?user_id=123",
        serde_json::Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(today["pending_srs_reviews_count"], 1);

    let mut strict = services::exam_engine::system_exam_blueprints()
        .into_iter()
        .find(|e| e.id == "CPNU")
        .unwrap();
    strict.id = uuid::Uuid::new_v4().to_string();
    strict
        .subjects
        .retain(|s| s.canonical_subject_id == "FIN_PUB");
    assert_eq!(strict.subjects.len(), 1);
    strict.subjects[0].question_count = Some(1);
    strict.subjects[0]
        .topics
        .retain(|t| t.canonical_topic_id == "FIN_PUB_01");
    services::exam_store::save(&state, 123, strict.clone(), true)
        .await
        .unwrap();
    let generated =
        serde_json::json!({"user_id":123,"mode":"SIMULADO","quantity":99,"contest_id":strict.id});
    let (status, drawn) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/quiz/generate",
        generated,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(drawn.as_array().unwrap().len(), 1);
    assert_eq!(drawn[0]["id"], qid.to_hex());
    let (status,weighted)=request(&app,&secret,Some(123),"POST",&format!("/api/v1/exams/{}/weighted-simulado",strict.id),serde_json::json!({"questions":[{"question_id":"spoof","canonical_subject_id":"FIN_PUB","canonical_topic_id":"FIN_PUB_01","archived":false}]})).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(weighted["question_ids"][0], qid.to_hex());

    let operation = serde_json::json!({"operation_id":uuid::Uuid::new_v4().to_string(),"question_id":qid.to_hex(),"question_revision":question.revision().unwrap(),"expected_mapping_revision":0,"canonical_subject_id":"FIN_PUB","canonical_topic_id":"FIN_PUB_01","reviewed_reason":"Synthetic operator reviewed the exact question"});
    let mapping = serde_json::json!({"dry_run":true,"entries":[operation.clone()]});
    let reviewed = routes::canonical_mapping::apply(
        axum::extract::State(state.clone()),
        axum::Extension(security::SessionUser(123)),
        axum::Json(serde_json::from_value(mapping).unwrap()),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(reviewed[0].status, "reviewed_dry_run");
    assert!(!db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! {"_id":qid})
        .await
        .unwrap()
        .unwrap()
        .contains_key("canonical_mapping_history"));
    for expected in ["applied", "already_applied"] {
        let mapping = serde_json::json!({"dry_run":false,"entries":[operation.clone()]});
        let result = routes::canonical_mapping::apply(
            axum::extract::State(state.clone()),
            axum::Extension(security::SessionUser(123)),
            axum::Json(serde_json::from_value(mapping).unwrap()),
        )
        .await
        .unwrap()
        .0;
        assert_eq!(result[0].status, expected);
    }
    let (_, pre_hint) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        &format!("/api/v1/questions/{}/ai/contextual-hint", qid.to_hex()),
        serde_json::Value::Null,
    )
    .await;
    assert!(!pre_hint.to_string().contains("Synthetic answer B"));
    let installation = uuid::Uuid::new_v4().to_string();
    let native =
        serde_json::json!({"installation_id":installation,"installation_secret":"ab".repeat(32)});
    let (status, session) = request(
        &app,
        &secret,
        None,
        "POST",
        "/api/v1/auth/native-session",
        native.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(session["profile"]["user_id"].as_i64().unwrap() < 0);
    let mut wrong = native.clone();
    wrong["installation_secret"] = serde_json::json!("cd".repeat(32));
    assert_eq!(
        request(
            &app,
            &secret,
            None,
            "POST",
            "/api/v1/auth/native-session",
            wrong
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    let code = security::issue(&secret, 123, "legacy_link", 600).unwrap();
    let link = serde_json::json!({"link_code":code,"installation_id":installation,"installation_secret":"ab".repeat(32)});
    for _ in 0..2 {
        let (status, linked) = request(
            &app,
            &secret,
            None,
            "POST",
            "/api/v1/auth/telegram-link",
            link.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(linked["profile"]["user_id"], 123);
    }
    let (_, refreshed) = request(
        &app,
        &secret,
        None,
        "POST",
        "/api/v1/auth/native-session",
        native,
    )
    .await;
    assert_eq!(refreshed["profile"]["user_id"], 123);
    let stolen = serde_json::json!({"link_code":code});
    assert_eq!(
        request(
            &app,
            &secret,
            None,
            "POST",
            "/api/v1/auth/telegram-link",
            stolen
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    // Drop only the unique synthetic database created above on the dedicated loopback server.
    assert!(database_name.starts_with("cogniva_test_"));
    db.drop().await.unwrap();
}
