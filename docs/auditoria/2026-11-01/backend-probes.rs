// Reproducoes adversariais: anexadas SOMENTE a uma copia temporaria do backend.
// Os asserts confirmam o comportamento defeituoso observado, nao a regra desejada.
#[cfg(test)]
mod audit_probes {
    use super::*;
    use axum::{body::Body, http::{Request, StatusCode}};
    use tower::ServiceExt;
    use crate::models::{question::QuestionDocument, exam_hierarchy::*};
    use crate::services::{exam_engine::*, srs_engine::*};
    use std::path::PathBuf;

    async fn state() -> Arc<AppState> {
        // Porta 1 e selecao limitada: nenhum acesso ao Atlas ou ao banco local padrao.
        let uri = "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=30&connectTimeoutMS=30";
        let client = mongodb::Client::with_uri_str(uri).await.unwrap();
        Arc::new(AppState::new(client.database("cogniva_audit_synthetic"), AppConfig {
            port: 0, mongodb_uri: uri.into(), mongodb_db: "cogniva_audit_synthetic".into(),
            openai_api_key: None, openai_model: "unused".into(),
            gemini_api_key: None, gemini_model: "unused".into(), jwt_secret: "audit".into(),
            apk_storage_path: PathBuf::from("missing"), apk_metadata_path: PathBuf::from("missing")
        }))
    }

    async fn create(app: Router, exam: &ExamBlueprint) -> axum::response::Response {
        app.oneshot(Request::builder().method("POST").uri("/api/v1/exams")
            .header("content-type", "application/json")
            .body(Body::from(serde_json::to_vec(exam).unwrap())).unwrap()).await.unwrap()
    }

    #[tokio::test]
    async fn audit_unauthenticated_request_overwrites_system_exam() {
        let s = state().await;
        let mut exam = system_exam_blueprints().remove(0);
        exam.title = "AUDIT OVERWRITE".into();
        let response = create(build_router(s.clone()), &exam).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(s.exams.lock().unwrap().exams["CPNU"].title, "AUDIT OVERWRITE");
        println!("AUDIT: POST sem token sobrescreveu CPNU e retornou 200");
    }

    #[tokio::test]
    async fn audit_success_with_database_unavailable_and_loss_after_restart() {
        let s = state().await;
        let mut exam = system_exam_blueprints().remove(0);
        exam.id = "audit-custom".into();
        let response = create(build_router(s.clone()), &exam).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert!(s.exams.lock().unwrap().exams.contains_key("audit-custom"));
        let restarted = AppState::new(s.db.clone(), s.config.clone());
        assert!(!restarted.exams.lock().unwrap().exams.contains_key("audit-custom"));
        println!("AUDIT: Mongo indisponivel -> HTTP 200 -> novo AppState perde concurso");
    }

    #[tokio::test]
    async fn audit_invalid_domain_values_are_accepted() {
        let s = state().await;
        let mut exam = system_exam_blueprints().remove(0);
        exam.id = "audit-invalid".into();
        exam.title = "".into();
        exam.trunk_slug = "does-not-exist".into();
        exam.historical_cutoff_score = 900.0;
        exam.subjects[0].weight = -10.0;
        exam.target_date = ExamDateTarget::ExactDate { iso_date: "invalid".into() };
        assert_eq!(create(build_router(s), &exam).await.status(), StatusCode::OK);
        println!("AUDIT: titulo vazio, tronco inexistente, peso negativo, corte 900, data invalida -> 200");
    }

    #[test]
    fn audit_legacy_text_answer_does_not_become_correct_index() {
        let q: QuestionDocument = bson::from_document(bson::doc! {
            "_id": bson::oid::ObjectId::new(), "question": "synthetic",
            "answers": ["wrong", "right"], "correct_answers": ["right"]
        }).unwrap();
        assert!(q.to_dto(None).correct_answer_indices.is_empty());
        println!("AUDIT: gabarito legado na opcao B vira indices vazios; Android usa A por padrao");
    }

    #[test]
    fn audit_typed_activity_roundtrip_drops_unknown_legacy_fields() {
        let mut raw = bson::doc! {
            "user_id": 123_i64, "question_id": bson::oid::ObjectId::new(),
            "datas": {"ultima_sessao": bson::DateTime::now(), "data_primeira_sessao": bson::DateTime::now()},
            "tempos": {}, "disciplina": "synthetic", "legacy_extra": "preserve-me"
        };
        raw.insert("pastSessions", bson::Bson::Array(vec![]));
        let parsed: crate::models::activity::UserActivityDocument = bson::from_document(raw).unwrap();
        assert!(!bson::to_document(&parsed).unwrap().contains_key("legacy_extra"));
        println!("AUDIT: roundtrip tipado usado em replace_one descarta campo legado desconhecido");
    }

    #[test]
    fn audit_srs_is_discontinuous_and_ignores_error_in_default_mode() {
        let (_, before) = calculate_spaced_repetition_interval(4, 1000.0, 1, 3, true, false);
        // Score 0.92 e intervalo 11: subir a confianca pode reduzir o intervalo.
        let (_, high) = calculate_spaced_repetition_interval(5, 1000.0, 1, 3, true, false);
        let correct = calculate_spaced_repetition_interval(5, 60.0, 60, 3, true, false);
        let wrong = calculate_spaced_repetition_interval(5, 60.0, 60, 3, false, false);
        assert_eq!(correct, wrong);
        let values: Vec<i32> = (0..=5).flat_map(|streak| (0..=3).flat_map(move |confidence|
            (1..=120).map(move |time| calculate_spaced_repetition_interval(streak, 60.0, time, confidence, true, false).1)
        )).collect();
        assert_eq!(*values.iter().min().unwrap(), 10);
        assert_eq!(*values.iter().max().unwrap(), 20);
        // A descontinuidade exata e mais clara em scores proximos de .90.
        let (_, below) = calculate_spaced_repetition_interval(5, 1000.0, 1501, 3, true, false);
        let (_, at) = calculate_spaced_repetition_interval(5, 1000.0, 1500, 3, true, false);
        assert!(below > at);
        println!("AUDIT: SRS score abaixo .90 -> {below}d; score .90 -> {at}d; faixa enumerada 10..20; acerto=erro; outros {before}/{high}");
    }

    #[test]
    fn audit_simulado_accepts_foreign_topic_and_score_over_100() {
        let quotas = vec![SubjectQuota { canonical_subject_id: "S".into(), weight: 1.0, question_count: 1 }];
        let questions = vec![SimuladoCandidate { question_id: "foreign".into(), canonical_subject_id: "S".into(), canonical_topic_id: "OUTSIDE-EXAM".into(), archived: false }];
        assert_eq!(draw_weighted_simulado(&quotas, &questions), vec!["foreign"]);
        assert_eq!(weighted_net_score(&[SubjectOutcome { weight: 1.0, correct: 20, drawn: 10 }]), 200.0);
        println!("AUDIT: topico fora do edital selecionado; correct > drawn resulta em nota 200");
    }

    #[test]
    fn audit_unicode_date_panics() {
        let result = std::panic::catch_unwind(|| days_until("😀😀😀", chrono::NaiveDate::from_ymd_opt(2026,11,1).unwrap()));
        assert!(result.is_err());
        println!("AUDIT: data UTF-8 invalida causa panic ao fatiar byte 10");
    }

    #[test]
    fn audit_practiced_zero_mastery_is_treated_as_unpracticed() {
        let mut exam = system_exam_blueprints().remove(0);
        exam.subjects.truncate(1);
        exam.subjects[0].topics.truncate(1);
        let topic_id = exam.subjects[0].topics[0].canonical_topic_id.clone();
        let score = readiness_for_blueprints(&[exam], &std::collections::HashMap::from([(topic_id,0.0)]));
        assert_eq!(score, 0.0); // Pela formula documentada: cobertura praticada 1 -> 30 pontos.
        println!("AUDIT: topico praticado com dominio 0 perde tambem os 30 pontos de cobertura");
    }

    // Servidor de protocolo MongoDB minimo, inteiramente sintetico, para testar o handler real.
    async fn wire_state(reject_writes: bool) -> (Arc<AppState>, Arc<std::sync::Mutex<Option<bson::Document>>>, tokio::task::JoinHandle<()>) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let stored = Arc::new(std::sync::Mutex::new(None::<bson::Document>));
        let server_store = stored.clone();
        let handle = tokio::spawn(async move {
            loop {
                let Ok((mut socket, _)) = listener.accept().await else { break; };
                let storage = server_store.clone();
                tokio::spawn(async move {
                    loop {
                        let mut header = [0u8;16];
                        if socket.read_exact(&mut header).await.is_err() { break; }
                        let length = i32::from_le_bytes(header[0..4].try_into().unwrap()) as usize;
                        let request_id = i32::from_le_bytes(header[4..8].try_into().unwrap());
                        let opcode = i32::from_le_bytes(header[12..16].try_into().unwrap());
                        let mut body = vec![0; length - 16];
                        if socket.read_exact(&mut body).await.is_err() { break; }
                        let offset = if opcode == 2013 { 5 } else {
                            let null_at = body[4..].iter().position(|byte| *byte == 0).unwrap() + 4;
                            null_at + 1 + 8
                        };
                        let command = bson::Document::from_reader(&mut std::io::Cursor::new(&body[offset..])).unwrap();
                        let reply = if command.contains_key("hello") || command.contains_key("isMaster") || command.contains_key("ismaster") {
                            bson::doc! {"ok":1.0,"ismaster":true,"isWritablePrimary":true,"minWireVersion":0,"maxWireVersion":17,
                                "maxBsonObjectSize":16777216,"maxMessageSizeBytes":48000000,"maxWriteBatchSize":100000}
                        } else if let Ok(collection) = command.get_str("find") {
                            let rows: Vec<bson::Bson> = if collection == "todasQuestoes" {
                                vec![bson::Bson::Document(bson::doc! {
                                    "_id": bson::oid::ObjectId::parse_str("000000000000000000000001").unwrap(),
                                    "question":"synthetic", "answers":["wrong","right"], "correct_answer_indices":[1_i32],
                                    "disciplina":"Audit", "level":"Médio"
                                })]
                            } else {
                                storage.lock().unwrap().clone().into_iter().map(bson::Bson::Document).collect()
                            };
                            bson::doc! {"ok":1.0,"cursor":{"id":0_i64,"ns":format!("cogniva_audit_synthetic.{collection}"),"firstBatch":rows}}
                        } else if command.contains_key("update") {
                            if reject_writes { bson::doc! {"ok":0.0,"code":121,"errmsg":"synthetic rejected write"} }
                            else {
                                let replacement = command.get_array("updates").unwrap()[0].as_document().unwrap().get_document("u").unwrap().clone();
                                *storage.lock().unwrap() = Some(replacement);
                                bson::doc! {"ok":1.0,"n":1_i32,"nModified":1_i32}
                            }
                        } else { bson::doc! {"ok":1.0} };
                        let doc_bytes = bson::to_vec(&reply).unwrap();
                        let mut payload = if opcode == 2013 { vec![0u8;5] } else {
                            let mut prefix = vec![0u8;20]; prefix[16..20].copy_from_slice(&1_i32.to_le_bytes()); prefix
                        };
                        payload.extend(doc_bytes);
                        let mut out = Vec::new();
                        out.extend(((16 + payload.len()) as i32).to_le_bytes());
                        out.extend(1_i32.to_le_bytes()); out.extend(request_id.to_le_bytes());
                        out.extend((if opcode == 2013 {2013_i32} else {1_i32}).to_le_bytes()); out.extend(payload);
                        if socket.write_all(&out).await.is_err() { break; }
                    }
                });
            }
        });
        let uri = format!("mongodb://127.0.0.1:{port}/?serverSelectionTimeoutMS=1000&directConnection=true");
        let client = mongodb::Client::with_uri_str(&uri).await.unwrap();
        let base = state().await;
        let mut cfg = base.config.clone(); cfg.mongodb_uri = uri;
        (Arc::new(AppState::new(client.database("cogniva_audit_synthetic"), cfg)), stored, handle)
    }

    fn submit_body(invalid_tail: bool) -> String {
        let item = serde_json::json!({"question_id":"000000000000000000000001","chosen_index":0,"chosen_text":"wrong",
            "is_correct":true,"time_spent_seconds":10,"confidence_level":3,"session_uuid":"same-event","quiz_mode":"STANDARD"});
        let mut attempts = vec![item.clone()];
        if invalid_tail { let mut invalid=item; invalid["question_id"]=serde_json::json!("invalid"); attempts.push(invalid); }
        serde_json::json!({"user_id":123,"attempts":attempts}).to_string()
    }

    async fn submit(app: Router, body: String) -> axum::response::Response {
        app.oneshot(Request::builder().method("POST").uri("/api/v1/quiz/submit-batch")
            .header("content-type","application/json").body(Body::from(body)).unwrap()).await.unwrap()
    }

    #[tokio::test]
    async fn audit_rejected_mongo_write_is_acknowledged_as_processed() {
        let (s, stored, handle)=wire_state(true).await;
        let response=submit(build_router(s), submit_body(false)).await;
        assert_eq!(response.status(),StatusCode::OK);
        let bytes=axum::body::to_bytes(response.into_body(),usize::MAX).await.unwrap();
        let value:serde_json::Value=serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["processed_count"],1);
        assert!(stored.lock().unwrap().is_none()); handle.abort();
        println!("AUDIT: Mongo rejeitou update; handler devolveu 200 processed_count=1 sem persistir");
    }

    #[tokio::test]
    async fn audit_replay_duplicates_history_and_trusts_incorrect_client_answer() {
        let (s, stored, handle)=wire_state(false).await;
        let app=build_router(s);
        for _ in 0..2 { assert_eq!(submit(app.clone(),submit_body(false)).await.status(),StatusCode::OK); }
        let doc=stored.lock().unwrap().clone().unwrap();
        assert_eq!(doc.get_array("pastSessions").unwrap().len(),2);
        assert_eq!(doc.get_i32("quantidade_acertos").unwrap(),2);
        handle.abort();
        println!("AUDIT: mesmo evento duas vezes -> 2 sessoes; alternativa errada + is_correct=true -> 2 acertos");
    }

    #[tokio::test]
    async fn audit_partial_batch_error_keeps_first_write_and_replay_duplicates_it() {
        let (s, stored, handle)=wire_state(false).await;
        let app=build_router(s);
        assert_eq!(submit(app.clone(),submit_body(true)).await.status(),StatusCode::BAD_REQUEST);
        assert_eq!(stored.lock().unwrap().as_ref().unwrap().get_array("pastSessions").unwrap().len(),1);
        assert_eq!(submit(app,submit_body(true)).await.status(),StatusCode::BAD_REQUEST);
        assert_eq!(stored.lock().unwrap().as_ref().unwrap().get_array("pastSessions").unwrap().len(),2);
        handle.abort();
        println!("AUDIT: lote [valido,invalido] -> 400 com primeira gravacao; retry duplica primeira tentativa");
    }
}
