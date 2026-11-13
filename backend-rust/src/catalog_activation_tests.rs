use crate::integration_tests::{config, request, seed_user_and_session};
use crate::*;
use axum::http::StatusCode;
use bson::{doc, oid::ObjectId, Bson, Document};
use futures::TryStreamExt;
use serde_json::{json, Value};

fn next(mut c: Value, label: &str) -> Value {
    c["catalog_version"] = json!(c["catalog_version"].as_i64().unwrap() + 1);
    let old = c["trunks"][0]["label"].clone();
    if !c["trunks"][0]["aliases"].as_array().unwrap().contains(&old) {
        c["trunks"][0]["aliases"].as_array_mut().unwrap().push(old);
    }
    c["trunks"][0]["label"] = json!(label);
    c
}

#[tokio::test]
#[ignore = "Requires dedicated loopback Mongo 27048"]
async fn global_catalog_activation_preserves_history_and_is_restartable() {
    let uri = std::env::var("COGNIVA_TEST_MONGODB_URI").unwrap();
    assert!(uri.starts_with("mongodb://127.0.0.1:27048/"));
    let client = Client::with_uri_str(&uri).await.unwrap();
    let name = format!("cogniva_test_{}", uuid::Uuid::new_v4().simple());
    let db = client.database(&name);
    services::question_storage::prepare(&db).await.unwrap();
    seed_user_and_session(&db, 123, true).await;

    let cfg = config(&uri);
    let secret = cfg.jwt_secret.clone();
    let state = Arc::new(AppState::new(db.clone(), cfg));
    let app = build_router(state.clone());

    let mut fixture: Value = serde_json::from_str(include_str!(
        "../../shared/examples/question-contracts/question-multiple-choice.json"
    ))
    .unwrap();
    let qid = ObjectId::new();
    fixture["_id"] = json!({ "$oid": qid.to_hex() });
    fixture["explanation"] = json!("Preserved synthetic cached explanation");
    fixture["unknown_extra"] = json!({ "keep": true });
    services::question_storage::create(
        &db,
        123,
        services::question_storage::SaveRequest {
            operation_id: uuid::Uuid::new_v4().to_string(),
            expected_revision: 0,
            dry_run: false,
            document: fixture.clone(),
        },
    )
    .await
    .unwrap();

    let mut decision: Value = serde_json::from_str(include_str!(
        "../../shared/examples/question-contracts/compatibility-direct.json"
    ))
    .unwrap();
    decision["owner_user_id"] = json!(123);
    decision["question_id"] = json!(qid.to_hex());
    decision["relation"] = json!("EXCLUDED");
    decision["matched_topic_ids"] = json!([]);
    db.collection::<Document>("question_exam_compatibility")
        .insert_one(Bson::try_from(decision).unwrap().as_document().unwrap().clone())
        .await
        .unwrap();

    let qraw = db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! { "_id": qid })
        .await
        .unwrap()
        .unwrap();
    let q = services::question_compatibility::decode(qraw.clone()).unwrap();
    let attempt = json!({
        "attempt_id": uuid::Uuid::new_v4().to_string(),
        "occurred_at": chrono::Utc::now().to_rfc3339(),
        "question_revision": q.revision().unwrap(),
        "question_id": qid.to_hex(),
        "chosen_index": 1,
        "chosen_text": "4",
        "is_correct": true,
        "time_spent_seconds": 10,
        "confidence_level": 3,
        "session_uuid": "catalog-activation-synthetic",
        "quiz_mode": "STANDARD",
        "canonical_subject_id": "MAT",
        "canonical_topic_id": "MAT_01_001"
    });
    assert_eq!(
        request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/quiz/submit-batch",
            json!({ "user_id": 123, "attempts": [attempt] })
        )
        .await
        .0,
        StatusCode::OK
    );

    let qraw = db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! { "_id": qid })
        .await
        .unwrap()
        .unwrap();
    let history_before: Vec<Document> = db
        .collection("userActivityLog")
        .find(doc! {})
        .await
        .unwrap()
        .try_collect()
        .await
        .unwrap();

    let (_, base) = request(
        &app,
        &secret,
        Some(123),
        "GET",
        "/api/v1/admin/catalog/base",
        json!(null),
    )
    .await;
    assert_eq!(base["catalog"]["catalog_version"], 2);
    let mut candidate = next(base["catalog"].clone(), "Fiscal ativado no painel");
    let subjects = candidate["subjects"].as_array_mut().unwrap();
    let math = subjects
        .iter_mut()
        .find(|s| s["subject_id"] == "MAT")
        .unwrap();
    let old_label = math["label"].clone();
    math["aliases"].as_array_mut().unwrap().push(old_label);
    math["label"] = json!("Matemática do painel");
    math["revision"] = json!(2);
    math["trunk_links"] = json!([{
        "trunk_id": "fiscal",
        "role": "FOUNDATIONAL",
        "default_weight": 1.5,
        "basis": "EDITORIAL_SUGGESTION",
        "note": "Vínculo revisado na fixture de ativação."
    }]);
    candidate["trunks"].as_array_mut().unwrap().push(json!({
        "trunk_id": "web_teste",
        "label": "Carreira de teste web",
        "icon_key": "Work",
        "aliases": [],
        "status": "ACTIVE",
        "source_ids": ["EDITORIAL_V1"]
    }));
    candidate["subjects"].as_array_mut().unwrap().push(json!({
        "subject_id": "WEB_TEST",
        "label": "Disciplina do painel",
        "aliases": [],
        "scope_note": "Escopo sintético para provar inclusão e ativação.",
        "status": "ACTIVE",
        "revision": 1,
        "source_ids": ["EDITORIAL_V1"],
        "trunk_links": [{
            "trunk_id": "web_teste",
            "role": "FOUNDATIONAL",
            "default_weight": 1.5,
            "basis": "EDITORIAL_SUGGESTION",
            "note": "Composição sintética revisada."
        }]
    }));
    for (id, parent, kind, label) in [
        ("WEB_TEST_01", None, "TOPIC", "Assunto do painel"),
        (
            "WEB_TEST_01_001",
            Some("WEB_TEST_01"),
            "SUBTOPIC",
            "Subassunto do painel",
        ),
    ] {
        candidate["topics"].as_array_mut().unwrap().push(json!({
            "topic_id": id,
            "subject_id": "WEB_TEST",
            "parent_topic_id": parent,
            "kind": kind,
            "label": label,
            "aliases": [],
            "status": "ACTIVE",
            "revision": 1,
            "source_ids": ["EDITORIAL_V1"]
        }));
    }

    let draft_id = uuid::Uuid::new_v4().to_string();
    let save = json!({
        "draft_id": draft_id,
        "expected_revision": 0,
        "base_catalog_sha256": base["sha256"],
        "status": "REVIEW_READY",
        "catalog": candidate
    });
    let (status, saved) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/admin/catalog/drafts",
        save.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{saved}");

    let activate = json!({
        "operation_id": uuid::Uuid::new_v4().to_string(),
        "draft_id": draft_id,
        "expected_draft_revision": 1,
        "expected_catalog_hash": base["sha256"],
        "reason": "Revisão explícita de cadastro e vínculos no teste sintético.",
        "reviewed": true
    });
    let mut unreviewed = activate.clone();
    unreviewed["reviewed"] = json!(false);
    assert_eq!(
        request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/admin/catalog/activate",
            unreviewed
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );

    let (status, receipt) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/admin/catalog/activate",
        activate.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{receipt}");
    assert_eq!(receipt["activated_version"], 3);
    assert_eq!(
        request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/admin/catalog/activate",
            activate.clone()
        )
        .await
        .1["status"],
        "already_applied"
    );
    assert_eq!(
        request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/admin/catalog/drafts",
            save
        )
        .await
        .1["draft"]["revision"],
        1
    );

    let (_, current) = request(
        &app,
        &secret,
        Some(123),
        "GET",
        "/api/v1/catalog/knowledge",
        json!(null),
    )
    .await;
    assert_eq!(current["catalog_version"], 3);
    assert!(current["subjects"]
        .as_array()
        .unwrap()
        .iter()
        .any(|s| s["subject_id"] == "WEB_TEST"));

    let (_, trunks) = request(
        &app,
        &secret,
        Some(123),
        "GET",
        "/api/v1/catalog/trunks",
        json!(null),
    )
    .await;
    let fiscal = trunks["trunks"]
        .as_array()
        .unwrap()
        .iter()
        .find(|t| t["slug"] == "fiscal")
        .unwrap();
    assert_eq!(fiscal["name"], "Fiscal ativado no painel");
    assert!(fiscal["core_subjects"]
        .as_array()
        .unwrap()
        .iter()
        .any(|s| s["canonical_subject_id"] == "MAT"));

    let (_, cache) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/questions/cache-state",
        json!({ "question_ids": [qid.to_hex()] }),
    )
    .await;
    assert!(cache["questions"][0]["question"].is_object(), "{cache}");
    assert_eq!(cache["decisions"][0]["catalog_version"], 2);
    assert_eq!(cache["decisions"][0]["relation"], "EXCLUDED");

    let (_, learning) = request(
        &app,
        &secret,
        Some(123),
        "GET",
        "/api/v1/learning/state",
        json!(null),
    )
    .await;
    assert_eq!(learning["records"][0]["knowledge_evidence"]["attempts"], 1);

    let restarted = Arc::new(AppState::new(db.clone(), config(&uri)));
    let reopened = build_router(restarted);
    let (_, base2) = request(
        &reopened,
        &secret,
        Some(123),
        "GET",
        "/api/v1/admin/catalog/base",
        json!(null),
    )
    .await;
    assert_eq!(base2["catalog"]["catalog_version"], 3);

    // Recover a process that reserved its immutable event but never wrote the head.
    let recovery_name = format!("cogniva_test_recovery_{}", uuid::Uuid::new_v4().simple());
    let recovery_db = client.database(&recovery_name);
    services::question_storage::prepare(&recovery_db)
        .await
        .unwrap();
    let event = db
        .collection::<Document>("knowledge_catalog_activations")
        .find_one(doc! { "_id": format!("123:{}", activate["operation_id"].as_str().unwrap()) })
        .await
        .unwrap()
        .unwrap();
    recovery_db
        .collection::<Document>("knowledge_catalog_activations")
        .insert_one(event)
        .await
        .unwrap();
    let recovery_state = AppState::new(recovery_db.clone(), config(&uri));
    let recovery_request: services::catalog_runtime::ActivateRequest =
        serde_json::from_value(activate.clone()).unwrap();
    assert_eq!(
        services::catalog_runtime::activate(&recovery_state, 123, recovery_request)
            .await
            .unwrap()["activated_version"],
        3
    );
    assert_eq!(
        recovery_db
            .collection::<Document>("knowledge_catalog_activations")
            .count_documents(doc! {})
            .await
            .unwrap(),
        1
    );

    let mut candidate4 = next(base2["catalog"].clone(), "Fiscal quarta versão");
    let math = candidate4["subjects"]
        .as_array_mut()
        .unwrap()
        .iter_mut()
        .find(|s| s["subject_id"] == "MAT")
        .unwrap();
    math["status"] = json!("DEPRECATED");
    math["revision"] = json!(3);
    let fourth_id = uuid::Uuid::new_v4().to_string();
    let (status, body) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/admin/catalog/drafts",
        json!({
            "draft_id": fourth_id,
            "expected_revision": 0,
            "base_catalog_sha256": base2["sha256"],
            "status": "REVIEW_READY",
            "catalog": candidate4
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");

    let fourth = json!({
        "operation_id": uuid::Uuid::new_v4().to_string(),
        "draft_id": fourth_id,
        "expected_draft_revision": 1,
        "expected_catalog_hash": base2["sha256"],
        "reason": "Desativação explícita mantendo todo o histórico anterior.",
        "reviewed": true
    });
    let (status, body) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/admin/catalog/activate",
        fourth,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");

    let (_, cache) = request(
        &app,
        &secret,
        Some(123),
        "POST",
        "/api/v1/questions/cache-state",
        json!({ "question_ids": [qid.to_hex()] }),
    )
    .await;
    assert!(cache["questions"][0]["question"].is_null());
    assert_eq!(
        db.collection::<Document>("todasQuestoes")
            .find_one(doc! { "_id": qid })
            .await
            .unwrap()
            .unwrap(),
        qraw
    );
    assert_eq!(
        db.collection::<Document>("userActivityLog")
            .find(doc! {})
            .await
            .unwrap()
            .try_collect::<Vec<_>>()
            .await
            .unwrap(),
        history_before
    );
    let (_, v2) = request(
        &app,
        &secret,
        Some(123),
        "GET",
        "/api/v1/admin/catalog/versions/2",
        json!(null),
    )
    .await;
    assert_eq!(v2["catalog"], base["catalog"]);
    let (_, v3) = request(
        &app,
        &secret,
        Some(123),
        "GET",
        "/api/v1/admin/catalog/versions/3",
        json!(null),
    )
    .await;
    assert_eq!(v3["catalog"], current);
    assert_eq!(
        request(
            &app,
            &secret,
            Some(123),
            "POST",
            "/api/v1/admin/catalog/activate",
            activate
        )
        .await
        .1["activated_version"],
        3
    );
    assert_eq!(
        db.collection::<Document>("knowledge_catalog_versions")
            .count_documents(doc! {})
            .await
            .unwrap(),
        3
    );
    assert_eq!(
        services::question_contracts::catalog_ref()["catalog_version"],
        2,
        "No request context leaks between test databases"
    );
    assert!(name.starts_with("cogniva_test_"));
    assert!(recovery_name.starts_with("cogniva_test_recovery_"));
    db.drop().await.unwrap();
    recovery_db.drop().await.unwrap();
}

#[tokio::test]
#[ignore = "Requires dedicated loopback Mongo 27048"]
async fn competing_catalog_activations_have_one_committed_winner() {
    use services::{catalog_editor, catalog_runtime};
    let uri = std::env::var("COGNIVA_TEST_MONGODB_URI").unwrap();
    assert!(uri.starts_with("mongodb://127.0.0.1:27048/"));
    let client = Client::with_uri_str(&uri).await.unwrap();
    let name = format!("cogniva_test_race_{}", uuid::Uuid::new_v4().simple());
    let db = client.database(&name);
    services::question_storage::prepare(&db).await.unwrap();
    let state = AppState::new(db.clone(), config(&uri));
    let base = catalog_runtime::bundled();
    let mut commands = Vec::new();
    for label in ["Fiscal concorrente A", "Fiscal concorrente B"] {
        let draft_id = uuid::Uuid::new_v4().to_string();
        catalog_editor::save(
            &state,
            123,
            catalog_editor::DraftRequest {
                draft_id: draft_id.clone(),
                expected_revision: 0,
                base_catalog_sha256: base.hash.clone(),
                status: "REVIEW_READY".into(),
                catalog: next((*base.current).clone(), label),
            },
        )
        .await
        .unwrap();
        commands.push(catalog_runtime::ActivateRequest {
            operation_id: uuid::Uuid::new_v4().to_string(),
            draft_id,
            expected_draft_revision: 1,
            expected_catalog_hash: base.hash.clone(),
            reason: "Ativação concorrente sob revisão explícita.".into(),
            reviewed: true,
        });
    }
    let (a, b) = tokio::join!(
        catalog_runtime::activate(&state, 123, commands[0].clone()),
        catalog_runtime::activate(&state, 123, commands[1].clone())
    );
    assert_eq!(usize::from(a.is_ok()) + usize::from(b.is_ok()), 1);
    let current = state.catalog.refresh(&db).await.unwrap();
    assert_eq!(current.version, 3);
    assert_eq!(current.committed.len(), 1);
    let loser = if a.is_ok() { 1 } else { 0 };
    assert!(catalog_runtime::activate(&state, 123, commands[loser].clone())
        .await
        .is_err());
    let history = catalog_runtime::history(&state, None).await.unwrap();
    assert_eq!(history["versions"].as_array().unwrap().len(), 1);
    assert_eq!(
        db.collection::<Document>("catalog_editor_versions")
            .count_documents(doc! {})
            .await
            .unwrap(),
        2
    );
    assert!(name.starts_with("cogniva_test_race_"));
    db.drop().await.unwrap();
}
