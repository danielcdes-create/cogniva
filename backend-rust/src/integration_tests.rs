//! Real MongoDB tests. Never use MONGODB_URI or a production database here.
use super::*;
use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use bson::{doc, oid::ObjectId, Document};
use std::path::PathBuf;
use tower::ServiceExt;
use futures::TryStreamExt;

#[tokio::test]
#[ignore = "Requires dedicated loopback Mongo 27048"]
async fn xai_generates_missing_resource_once_and_persists_for_reuse() {
    use std::sync::atomic::{AtomicUsize, Ordering};
    let uri=std::env::var("COGNIVA_TEST_MONGODB_URI").unwrap();assert!(uri.starts_with("mongodb://127.0.0.1:27048/"));
    let client=Client::with_uri_str(&uri).await.unwrap();let name=format!("cogniva_test_{}",uuid::Uuid::new_v4().simple());let db=client.database(&name);
    let hits=Arc::new(AtomicUsize::new(0));let count=hits.clone();
    let mock=Router::new().route("/responses",axum::routing::post(move |axum::Json(payload):axum::Json<serde_json::Value>|{let count=count.clone();async move{
        count.fetch_add(1,Ordering::SeqCst);assert_eq!(payload["model"],"synthetic-grok");assert_eq!(payload["store"],false);
        assert!(payload["input"][1]["content"].as_str().unwrap().contains("mnemônico"));
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        axum::Json(serde_json::json!({"status":"completed","output":[{"type":"reasoning","content":[]},{"type":"message","content":[{"type":"output_text","text":"Memória sintética validada."}]}]}))
    }}));
    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let addr=listener.local_addr().unwrap();let server=tokio::spawn(async move{axum::serve(listener,mock).await.unwrap()});
    let mut cfg=config(&uri);cfg.xai_api_key=Some("synthetic-only-key".into());cfg.xai_base_url=format!("http://{addr}");cfg.xai_model="synthetic-grok".into();
    let id=ObjectId::new();db.collection::<Document>("todasQuestoes").insert_one(doc!{"_id":id,"question":"Synthetic memory question","answers":["A","B"],"correct_answers":["B"],"explanation":"Existing explanation preserved","unknown_legacy_field":"preserve"}).await.unwrap();
    use services::ai_tutor::{get_or_generate_ai_assist,AiAssistKind};let key=id.to_hex();
    let (a,b)=tokio::join!(get_or_generate_ai_assist(&db,&cfg,&key,AiAssistKind::Mnemonic,None),get_or_generate_ai_assist(&db,&cfg,&key,AiAssistKind::Mnemonic,None));
    let a=a.unwrap();let b=b.unwrap();assert_ne!(a.from_cache,b.from_cache);assert_eq!(a.content_markdown,b.content_markdown);assert_eq!(hits.load(Ordering::SeqCst),1);
    let reused=get_or_generate_ai_assist(&db,&cfg,&key,AiAssistKind::Mnemonic,None).await.unwrap();assert!(reused.from_cache);assert_eq!(hits.load(Ordering::SeqCst),1);
    let row=db.collection::<Document>("todasQuestoes").find_one(doc!{"_id":id}).await.unwrap().unwrap();assert_eq!(row.get_str("mnemonico").unwrap(),"Memória sintética validada.");assert_eq!(row.get_str("unknown_legacy_field").unwrap(),"preserve");
    let cached=get_or_generate_ai_assist(&db,&cfg,&key,AiAssistKind::Explanation,None).await.unwrap();assert!(cached.from_cache);assert_eq!(hits.load(Ordering::SeqCst),1);
    server.abort();assert!(name.starts_with("cogniva_test_"));db.drop().await.unwrap();
}

#[tokio::test]
#[ignore = "Requires dedicated loopback Mongo 27048"]
async fn editable_exam_components_and_deletion_survive_sync_without_touching_history() {
    use models::exam_hierarchy::*;
    let uri=std::env::var("COGNIVA_TEST_MONGODB_URI").unwrap();assert!(uri.starts_with("mongodb://127.0.0.1:27048/"));
    let client=Client::with_uri_str(&uri).await.unwrap();let name=format!("cogniva_test_{}",uuid::Uuid::new_v4().simple());let db=client.database(&name);
    let cfg=config(&uri);let secret=cfg.jwt_secret.clone();let state=Arc::new(AppState::new(db.clone(),cfg));let app=build_router(state.clone());
    for owner in [123_i64,456_i64]{db.collection::<Document>("usuarios").insert_one(doc!{"user_id":owner,"active_contest_ids":[]}).await.unwrap();}
    db.collection::<Document>("userActivityLog").insert_one(doc!{"user_id":123_i64,"marker":"history unchanged"}).await.unwrap();
    let mut exam=services::exam_engine::system_exam_blueprints().remove(0);exam.id=uuid::Uuid::new_v4().to_string();
    let custom=format!("custom-{}",uuid::Uuid::new_v4());
    exam.subjects.push(ExamSubjectConfig{stage:"2ª etapa · Prova técnica".into(),local_subject_id:custom.clone(),canonical_subject_id:custom.clone(),display_name:"Engenharia de Túneis".into(),category:SubjectCategory::ConhecimentosGerais,weight:2.75,question_count:Some(7),relevance_tier:RelevanceTier::Specific,topics:vec![ExamTopicItem{local_topic_id:"local-topic".into(),canonical_topic_id:format!("custom-{}",uuid::Uuid::new_v4()),canonical_subject_id:custom.clone(),display_title:"Escavação".into(),diff_status:CanonicalTopicStatus::PreEditalBase}]});
    let (status,created)=request(&app,&secret,Some(123),"POST","/api/v1/exams",serde_json::to_value(&exam).unwrap()).await;assert_eq!(status,StatusCode::OK,"{created}");
    let path=format!("/api/v1/exams/{}",exam.id);let mut edit:ExamBlueprint=serde_json::from_value(created).unwrap();
    edit.subjects.retain(|s| s.canonical_subject_id==custom);edit.subjects[0].topics.clear();edit.subjects[0].display_name="Túneis revisados".into();
    let command=serde_json::to_value(&edit).unwrap();let (status,updated)=request(&app,&secret,Some(123),"PUT",&path,command.clone()).await;assert_eq!(status,StatusCode::OK,"{updated}");
    assert_eq!(updated["subjects"].as_array().unwrap().len(),1);assert_eq!(updated["subjects"][0]["stage"],"2ª etapa · Prova técnica");assert!(updated["subjects"][0]["topics"].as_array().unwrap().is_empty());
    assert_eq!(request(&app,&secret,Some(123),"PUT",&path,command).await.1,updated);
    let mut removed:ExamBlueprint=serde_json::from_value(updated).unwrap();removed.status=ExamLifecycleStatus::Excluido;removed.is_active_in_ladder=false;removed.subjects.clear();
    let (status,deleted)=request(&app,&secret,Some(123),"PUT",&path,serde_json::to_value(removed).unwrap()).await;assert_eq!(status,StatusCode::OK,"{deleted}");
    let listed=services::exam_store::list(&state,123).await.unwrap();let tombstone=listed.iter().find(|e| e.id==exam.id).unwrap();assert_eq!(tombstone.status,ExamLifecycleStatus::Excluido);assert!(!tombstone.is_active_in_ladder);assert!(tombstone.subjects.is_empty());
    assert!(!services::exam_store::list(&state,456).await.unwrap().iter().any(|e| e.id==exam.id));
    let raw=db.collection::<Document>("user_exams").find_one(doc!{"exam_id":&exam.id}).await.unwrap().unwrap();assert_eq!(raw.get_array("revision_history").unwrap().len(),2);
    assert_eq!(db.collection::<Document>("userActivityLog").find_one(doc!{"user_id":123_i64}).await.unwrap().unwrap().get_str("marker").unwrap(),"history unchanged");
    assert!(name.starts_with("cogniva_test_"));db.drop().await.unwrap();
}

fn config(uri: &str) -> AppConfig {
    AppConfig {
        port: 0,
        mongodb_uri: uri.into(),
        mongodb_db: "unused".into(),
        openai_api_key: None,
        openai_model: "unused".into(),
        gemini_api_key: None,
        gemini_model: "unused".into(),
        xai_api_key: None,
        xai_base_url: "https://api.x.ai/v1".into(),
        xai_model: "grok-4.7".into(),
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
#[ignore = "Requires the dedicated loopback MongoDB on 27048"]
async fn legacy_question_bank_remains_practicable_with_an_archived_selected_exam() {
    let uri=std::env::var("COGNIVA_TEST_MONGODB_URI").unwrap();
    assert!(uri.starts_with("mongodb://127.0.0.1:27048/")||uri.starts_with("mongodb://localhost:27048/"));
    let client=Client::with_uri_str(&uri).await.unwrap();
    let name=format!("cogniva_test_{}",uuid::Uuid::new_v4().simple());let db=client.database(&name);
    let cfg=config(&uri);let secret=cfg.jwt_secret.clone();let state=Arc::new(AppState::new(db.clone(),cfg));let app=build_router(state.clone());
    for owner in [123_i64,456_i64]{db.collection::<Document>("usuarios").insert_one(doc!{"user_id":owner,"active_contest_ids":[]}).await.unwrap();}
    let mut archived=services::exam_engine::system_exam_blueprints().into_iter().find(|e|e.id=="CPNU").unwrap();
    archived.status=models::exam_hierarchy::ExamLifecycleStatus::Arquivado;archived.is_active_in_ladder=false;
    services::exam_store::save(&state,123,archived,false).await.unwrap();
    let ids=[ObjectId::new(),ObjectId::new(),ObjectId::new()];
    for (index,id) in ids.iter().enumerate(){
        let mut question=doc!{"_id":id,"question":"Questão legada sintética ç","answers":["A","B"],"disciplina":"Direito do Trabalho"};
        if index!=1{question.insert("correct_answers",vec!["B"]);}
        db.collection::<Document>("todasQuestoes").insert_one(question).await.unwrap();
        let now=bson::DateTime::now();
        db.collection::<Document>("userActivityLog").insert_one(doc!{"user_id":if index==2 {456_i64}else{123_i64},"question_id":id,"disciplina":"Direito do Trabalho","datas":{"ultima_sessao":now,"data_primeira_sessao":now},"tempos":{},"quantidade_praticas":1,"quantidade_erros":1,"spaced_repetition_interval":30,"revisao_necessaria":true,"pastSessions":[]}).await.unwrap();
    }
    let before_questions:Vec<Document>=db.collection("todasQuestoes").find(doc!{}).sort(doc!{"_id":1}).await.unwrap().try_collect().await.unwrap();
    let before_history:Vec<Document>=db.collection("userActivityLog").find(doc!{}).sort(doc!{"_id":1}).await.unwrap().try_collect().await.unwrap();
    for mode in ["SHUFFLE","SRS_REVIEW","ERROR_NOTEBOOK","MOST_BADASS"] {
        let (status,body)=request(&app,&secret,Some(123),"POST","/api/v1/quiz/generate",serde_json::json!({"user_id":123,"mode":mode,"quantity":10,"contest_id":"CPNU"})).await;
        assert_eq!(status,StatusCode::OK,"{mode}: {body}");let questions=body.as_array().unwrap();
        assert_eq!(questions.len(),if mode=="SHUFFLE"{2}else{1});
        if mode!="SHUFFLE"{assert_eq!(questions[0]["id"],ids[0].to_hex());}
        for q in questions{assert!(!q["question_revision"].as_str().unwrap().is_empty());assert!(q["canonical_topic_id"].is_null());assert_eq!(q["correct_answer_indices"],serde_json::json!([1]));}
    }
    let (status,page)=request(&app,&secret,Some(123),"GET","/api/v1/learning/state",serde_json::Value::Null).await;
    assert_eq!(status,StatusCode::OK,"{page}");let rows=page["records"].as_array().unwrap();assert_eq!(rows.len(),2);
    assert!(rows.iter().all(|r|r["review_required"]==true&&r["in_error_notebook"]==true));
    assert_eq!(rows.iter().filter(|r|r["practice_available"]==true).count(),1);
    for mode in ["STANDARD","SIMULADO"]{
        let (status,body)=request(&app,&secret,Some(123),"POST","/api/v1/quiz/generate",serde_json::json!({"user_id":123,"mode":mode,"quantity":10,"contest_id":"CPNU"})).await;
        assert_eq!(status,StatusCode::BAD_REQUEST);assert!(body["message"].as_str().unwrap().contains("arquivado"));
    }
    let after_questions:Vec<Document>=db.collection("todasQuestoes").find(doc!{}).sort(doc!{"_id":1}).await.unwrap().try_collect().await.unwrap();
    let after_history:Vec<Document>=db.collection("userActivityLog").find(doc!{}).sort(doc!{"_id":1}).await.unwrap().try_collect().await.unwrap();
    assert_eq!(before_questions,after_questions);assert_eq!(before_history,after_history);
    assert!(name.starts_with("cogniva_test_"));db.drop().await.unwrap();
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
    // Weighted accuracy must use counts, not the mean of per-question percentages.
    db.collection::<Document>("usuarios").insert_one(doc!{"user_id":789_i64,"active_contest_ids":["CPNU"],"daily_questions_target":50,"daily_study_hours_target":2.0}).await.unwrap();
    for (attempts, correct) in [(100, 99), (1, 0)] {
        activities.insert_one(doc!{"user_id":789_i64,"question_id":ObjectId::new(),"disciplina":"Same discipline","quantidade_praticas":attempts,"quantidade_acertos":correct,"percentual_acertos":if attempts==100{99.0}else{0.0},"dominada":false,"points":0.0,"pastSessions":[]}).await.unwrap();
    }
    let (status, weighted_accuracy) = request(
        &app,
        &secret,
        Some(789),
        "GET",
        "/api/v1/analytics/progress?user_id=789",
        serde_json::Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(weighted_accuracy["global_accuracy_pct"], 98.02);
    assert!(
        (weighted_accuracy["disciplines_sorted_by_weakness"][0]["accuracy_pct"]
            .as_f64()
            .unwrap()
            - 9900.0 / 101.0)
            .abs()
            < 1e-9
    );
    activities
        .delete_many(doc! {"user_id":789_i64})
        .await
        .unwrap(); // Synthetic fixture only.

    // Schedule by elapsed interval, count actual mutations, and surface write errors.
    activities.update_one(doc!{"user_id":123_i64,"question_id":qid},doc!{"$set":{"datas.ultima_sessao":bson::DateTime::from_chrono(chrono::Utc::now()-chrono::Duration::hours(23)),"spaced_repetition_interval":1,"revisao_necessaria":false,"points":0.0,"points_with_decay":0.0}}).await.unwrap();
    let before_due = services::scheduler::run_daily_reviews_decay_and_countdown(&db)
        .await
        .unwrap();
    assert_eq!(before_due.marked_for_review, 0);
    assert_eq!(before_due.decay_updated, 0);
    assert!(!before_due.countdown_updated);
    activities.update_one(doc!{"user_id":123_i64,"question_id":qid},doc!{"$set":{"datas.ultima_sessao":bson::DateTime::from_chrono(chrono::Utc::now()-chrono::Duration::days(1)-chrono::Duration::seconds(1))}}).await.unwrap();
    let due = services::scheduler::run_daily_reviews_decay_and_countdown(&db)
        .await
        .unwrap();
    assert_eq!(due.marked_for_review, 1);
    assert_eq!(due.decay_updated, 0);
    assert_eq!(
        services::scheduler::run_daily_reviews_decay_and_countdown(&db)
            .await
            .unwrap()
            .marked_for_review,
        0
    );
    activities
        .update_one(
            doc! {"user_id":123_i64,"question_id":qid},
            doc! {"$set":{"revisao_necessaria":false}},
        )
        .await
        .unwrap();
    client.database("admin").run_command(doc!{"configureFailPoint":"failCommand","mode":{"times":1},"data":{"failCommands":["update"],"errorCode":121}}).await.unwrap();
    assert!(
        services::scheduler::run_daily_reviews_decay_and_countdown(&db)
            .await
            .is_err()
    );
    assert_eq!(
        activities
            .find_one(doc! {"user_id":123_i64,"question_id":qid})
            .await
            .unwrap()
            .unwrap()
            .get_array("pastSessions")
            .unwrap()
            .len(),
        4
    );
    // Drop only the unique synthetic database created above on the dedicated loopback server.
    assert!(database_name.starts_with("cogniva_test_"));
    db.drop().await.unwrap();
}
