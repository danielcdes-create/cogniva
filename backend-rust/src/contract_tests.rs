use crate::services::{exam_engine::*, exam_store, question_contracts, srs_engine::*};
use serde_json::Value;

#[test]
fn both_runtimes_share_utf8_mastery_weighted_score_srs_and_calendar_fixtures() {
    let f: Value =
        serde_json::from_str(include_str!("../../shared/domain-contracts.json")).unwrap();
    assert_eq!(f["contract_version"].as_i64(), Some(3));

    let catalog = question_contracts::catalog();
    assert_eq!(catalog["schema_version"].as_i64(), Some(2));
    assert_eq!(catalog["catalog_version"].as_i64(), Some(2));
    assert_eq!(catalog["status"].as_str(), Some("ACTIVE"));

    let templates = system_exam_blueprints();
    assert!(templates.len() >= 4);
    for tpl in &templates {
        exam_store::validate(tpl).expect("bundled exam template must satisfy exam_store::validate");
    }

    let q = &f["question"];
    let question: crate::models::question::QuestionDocument = bson::from_document(bson::doc! {
        "_id": bson::oid::ObjectId::new(),
        "question": q["question"].as_str().unwrap(),
        "answers": q["answers"].as_array().unwrap().iter().map(|a| a.as_str().unwrap()).collect::<Vec<_>>(),
        "correct_answers": [q["answers"][q["correct_index"].as_u64().unwrap() as usize].as_str().unwrap()],
        "disciplina": "Fixture"
    })
    .unwrap();
    assert_eq!(
        question.revision().unwrap(),
        q["revision"].as_str().unwrap()
    );
    for v in f["mastery"].as_array().unwrap() {
        let actual = mastery_with_confidence(
            v["accuracy"].as_f64().unwrap(),
            v["mastered"].as_bool().unwrap(),
            v["high_confidence_error"].as_bool().unwrap(),
            v["confidence"].as_i64().map(|n| n as i32),
        );
        assert!((actual - v["expected"].as_f64().unwrap()).abs() < 1e-9);
    }
    for v in f["scores"].as_array().unwrap() {
        let outcomes: Vec<crate::models::exam_hierarchy::SubjectOutcome> =
            serde_json::from_value(v["outcomes"].clone()).unwrap();
        assert!((weighted_net_score(&outcomes) - v["expected"].as_f64().unwrap()).abs() < 1e-9);
    }
    for v in f["srs"].as_array().unwrap() {
        let statuses = v["statuses"]
            .as_array()
            .unwrap()
            .iter()
            .map(|b| b.as_bool().unwrap())
            .collect::<Vec<_>>();
        let points = calculate_streak_and_points(&statuses);
        assert!((points.points_total - v["points"].as_f64().unwrap()).abs() < 1e-9);
        let confidence = v["confidence"].as_i64().unwrap() as i32;
        let (_, interval) = calculate_spaced_repetition_interval(
            points.streak_atual,
            42.0,
            42,
            confidence,
            *statuses.last().unwrap(),
            true,
        );
        assert_eq!(interval as i64, v["interval"].as_i64().unwrap());
        assert_eq!(
            is_mastered(points.points_total) && confidence >= 2,
            v["mastered"].as_bool().unwrap()
        );
    }
    for v in f["study_days"].as_array().unwrap() {
        let instant = chrono::DateTime::parse_from_rfc3339(v["instant"].as_str().unwrap()).unwrap();
        assert_eq!(
            study_date(instant.with_timezone(&chrono::Utc)).to_string(),
            v["day"].as_str().unwrap()
        );
    }
}

#[tokio::test]
async fn structured_error_response_carries_stable_code_field_and_correlation_id() {
    use crate::error::AppError;
    use axum::{body::to_bytes, http::StatusCode, response::IntoResponse};

    let cases = [
        (
            AppError::bad_request_code("INVALID_INTEGER", Some("revision"), "Inteiro inválido"),
            StatusCode::BAD_REQUEST,
            "INVALID_INTEGER",
            Some("revision"),
        ),
        (
            AppError::forbidden_code(
                "REVIEW_PERMISSION_DENIED",
                Some("validity.status"),
                "Sem permissão normativa",
            ),
            StatusCode::FORBIDDEN,
            "REVIEW_PERMISSION_DENIED",
            Some("validity.status"),
        ),
        (
            AppError::conflict_code(
                "QUESTION_ALREADY_EXISTS",
                Some("_id"),
                "Questão já existente",
            ),
            StatusCode::CONFLICT,
            "QUESTION_ALREADY_EXISTS",
            Some("_id"),
        ),
    ];

    for (err, expected_status, expected_code, expected_field) in cases {
        let resp = err.into_response();
        assert_eq!(resp.status(), expected_status);
        let bytes = to_bytes(resp.into_body(), 64 * 1024).await.unwrap();
        let body: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(body["error"], true);
        assert_eq!(body["code"], expected_code);
        assert_eq!(body["field"].as_str(), expected_field);
        assert!(body["message"].as_str().is_some_and(|s| !s.is_empty()));
        assert!(body["correlation_id"]
            .as_str()
            .is_some_and(|id| uuid::Uuid::parse_str(id).is_ok()));
    }
}

#[test]
fn operational_grading_change_preserves_semantic_validity_while_substantive_change_invalidates_t04_t05_t24() {
    use crate::services::question_eligibility::{
        evaluate_question_eligibility, EligibilityMode, TrustedReviewContext,
    };
    use serde_json::json;

    let mut open: Value = serde_json::from_str(include_str!(
        "../../shared/examples/question-contracts/question-open-manual.json"
    ))
    .unwrap();
    question_contracts::populate_v2_1_hashes(&mut open).unwrap();
    let orig_content = open["content_revision"].as_str().unwrap().to_string();
    let orig_semantic = open["semantic_revision"].as_str().unwrap().to_string();
    let orig_grading = open["grading_revision"].as_str().unwrap().to_string();

    // Confirm CURRENT validity bound to semantic_revision
    open["validity"] = json!({
        "revision": 1,
        "status": "CURRENT",
        "basis": "FACTUAL",
        "reference_date": "2026-11-01",
        "review_due_on": "2027-11-01",
        "reason_codes": [],
        "evaluated_content_revision": orig_content,
        "evaluated_semantic_revision": orig_semantic,
        "evaluation_mode": "SEMANTIC",
        "decision": {
            "actor": { "type": "HUMAN", "id": "123" },
            "method": "MANUAL_REVIEW",
            "rationale": "Conteúdo discursivo revisado e vigente.",
            "recorded_at": "2026-11-01T12:00:00Z",
            "evidence": [{
                "uri": "https://www.planalto.gov.br/ccivil_03/constituicao/constituicao.htm",
                "title": "Constituição da República Federativa do Brasil de 1988",
                "locator": "Art. 165",
                "retrieved_at": "2026-11-01T12:00:00Z",
                "sha256": null
            }],
            "model": null,
            "prompt_id": null,
            "prompt_version": null,
            "source_suggestion_id": null,
            "review_event_id": "22222222-2222-4222-8222-222222222222",
            "trust_level": "SERVER_VERIFIED"
        },
        "scope": "CURRENT_KNOWLEDGE",
        "context_date": null
    });
    question_contracts::validate(&open).unwrap();

    // T04 / T24: Numeric-only score change updates content_revision & grading_revision, NOT semantic_revision
    let mut numeric_only = open.clone();
    numeric_only["grading"]["max_score"] = json!(10);
    numeric_only["grading"]["pass_score"] = json!(6);
    let crit_len = numeric_only["grading"]["rubric"]["criteria"]
        .as_array()
        .unwrap()
        .len();
    let orig_max = open["grading"]["max_score"].as_f64().unwrap();
    for i in 0..crit_len {
        let old_pts = open["grading"]["rubric"]["criteria"][i]["max_points"]
            .as_f64()
            .unwrap();
        numeric_only["grading"]["rubric"]["criteria"][i]["max_points"] =
            json!((old_pts / orig_max) * 10.0);
    }
    question_contracts::populate_v2_1_hashes(&mut numeric_only).unwrap();
    assert_ne!(numeric_only["content_revision"], orig_content);
    assert_ne!(numeric_only["grading_revision"], orig_grading);
    assert_eq!(numeric_only["semantic_revision"], orig_semantic);
    question_contracts::validate(&numeric_only).unwrap();

    let eval_date = chrono::NaiveDate::from_ymd_opt(2026, 11, 15).unwrap();
    let trusted = TrustedReviewContext {
        require_server_verified: false,
        verified_review_event_ids: Some(std::collections::HashSet::from([
            "22222222-2222-4222-8222-222222222222".to_string(),
        ])),
    };
    let elig_numeric = evaluate_question_eligibility(
        &numeric_only,
        &question_contracts::catalog_ref(),
        None,
        eval_date,
        &trusted,
        EligibilityMode::DiscursiveExplicit,
    );
    assert!(
        elig_numeric.eligible,
        "T24: numeric-only grading change must remain eligible: {:?}",
        elig_numeric.reason_codes
    );

    // T05: Modifying expected_responses changes semantic_revision and invalidates CURRENT validity
    let mut altered_expected = numeric_only.clone();
    altered_expected["grading"]["expected_responses"] =
        json!(["Gabarito discursivo substancialmente diferente."]);
    question_contracts::populate_v2_1_hashes(&mut altered_expected).unwrap();
    assert_ne!(altered_expected["semantic_revision"], orig_semantic);
    assert_eq!(
        question_contracts::validate(&altered_expected)
            .unwrap_err()
            .error_code(),
        "SEMANTIC_REVISION_MISMATCH"
    );
    let elig_stale = evaluate_question_eligibility(
        &altered_expected,
        &question_contracts::catalog_ref(),
        None,
        eval_date,
        &trusted,
        EligibilityMode::DiscursiveExplicit,
    );
    assert!(!elig_stale.eligible);
    assert!(elig_stale.reason_codes.contains(&"SEMANTIC_REVISION_MISMATCH"));
    assert!(elig_stale.reason_codes.contains(&"QUESTION_NOT_ELIGIBLE"));
}
