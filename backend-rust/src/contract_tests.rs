use crate::services::{exam_engine::*, exam_store, question_contracts, srs_engine::*};
use serde_json::Value;

#[test]
fn both_runtimes_share_utf8_mastery_weighted_score_srs_and_calendar_fixtures() {
    let f: Value =
        serde_json::from_str(include_str!("../../shared/domain-contracts.json")).unwrap();
    assert_eq!(f["contract_version"].as_i64(), Some(5));
    assert_eq!(
        f["min_questions_for_mastery"].as_u64(),
        Some(crate::services::diagnostic_engine::MIN_QUESTIONS_FOR_MASTERY as u64)
    );

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

    // Contract v5 parity suites across Entry 60s, 3-Signal Diagnostics (<15 scarcity), Transfer, Retention, Prioritization, Calibration, 300m Model, Decision Under Uncertainty & Difficulty-Controlled Fatigue
    for v in f["entry_60s"].as_array().unwrap() {
        let instant = chrono::DateTime::parse_from_rfc3339(v["instant"].as_str().unwrap()).unwrap();
        assert_eq!(
            crate::services::prioritization_engine::format_entry_60s_header(
                instant.with_timezone(&chrono::Utc)
            ),
            v["expected_header"].as_str().unwrap()
        );
    }
    for v in f["holdout"].as_array().unwrap() {
        let actual = crate::services::strategy_engine::is_holdout_reserved(
            v["user_id"].as_i64().unwrap(),
            v["question_id"].as_str().unwrap(),
            v["pool_size"].as_u64().unwrap() as usize,
        );
        assert_eq!(actual, v["reserved"].as_bool().unwrap());
    }
    for v in f["diagnostics"].as_array().unwrap() {
        let actual = crate::services::diagnostic_engine::classify_diagnostic(
            &crate::services::diagnostic_engine::DiagnosticInputs {
                distinct_questions: v["distinct_questions"].as_u64().unwrap() as usize,
                non_blank_attempts: v["non_blank_attempts"].as_u64().unwrap() as usize,
                correct_attempts: v["correct_attempts"].as_u64().unwrap() as usize,
                last_is_correct: v["last_is_correct"].as_bool().unwrap(),
                last_confidence: v["last_confidence"].as_i64().unwrap() as i32,
                had_prior_verified_correct: v["had_prior_verified_correct"].as_bool().unwrap(),
                has_spaced_retention: v["has_spaced_retention"].as_bool().unwrap(),
                evidence_challenge_passed: v["evidence_challenge_passed"].as_bool(),
                has_independent_transfer: v["has_independent_transfer"].as_bool().unwrap(),
            },
        );
        assert_eq!(actual.as_str(), v["expected"].as_str().unwrap());
    }
    for v in f["transfer"].as_array().unwrap() {
        let actual = crate::services::diagnostic_engine::evaluate_transfer_status(
            &crate::services::diagnostic_engine::TransferInputs {
                distinct_questions: v["distinct_questions"].as_u64().unwrap() as usize,
                available_cross_board_questions: v["available_cross_board_questions"]
                    .as_u64()
                    .unwrap() as usize,
                transfer_attempts: v["transfer_attempts"].as_u64().unwrap() as usize,
                transfer_correct_grounded: v["transfer_correct_grounded"].as_u64().unwrap()
                    as usize,
            },
        );
        assert_eq!(actual.as_str(), v["expected"].as_str().unwrap());
    }
    for v in f["retention"].as_array().unwrap() {
        let ps = v["prior_stability"].as_f64().unwrap();
        let pd = v["prior_difficulty"].as_f64().unwrap();
        let el = v["elapsed_days"].as_f64().unwrap();
        let r = crate::services::retention_engine::retrievability(el, ps);
        assert!((r - v["retrievability_at_elapsed"].as_f64().unwrap()).abs() < 1e-8);
        let upd = crate::services::retention_engine::update_retention(
            ps,
            pd,
            v["is_correct"].as_bool().unwrap(),
            v["confidence"].as_i64().unwrap() as i32,
            el,
            v["response_seconds"].as_i64().unwrap() as i32,
            v["expected_seconds"].as_f64().unwrap(),
        );
        assert!((upd.next_stability - v["next_stability"].as_f64().unwrap()).abs() < 1e-6);
        assert!((upd.next_difficulty - v["next_difficulty"].as_f64().unwrap()).abs() < 1e-6);
        assert_eq!(
            upd.next_interval_days as i64,
            v["next_interval_days"].as_i64().unwrap()
        );
    }
    for v in f["prioritization"].as_array().unwrap() {
        let u = crate::services::prioritization_engine::priority_utility(
            &crate::services::prioritization_engine::PrioritizationInputs {
                edital_weight: v["edital_weight"].as_f64().unwrap(),
                incidence_weight: v["incidence_weight"].as_f64().unwrap(),
                retrievability: v["retrievability"].as_f64().unwrap(),
                verified_competence: v["verified_competence"].as_bool().unwrap(),
                distinct_questions: v["distinct_questions"].as_u64().unwrap() as usize,
                avg_seconds: v["avg_seconds"].as_f64().unwrap(),
                unmet_prerequisites_count: v["unmet_prerequisites_count"].as_u64().unwrap()
                    as usize,
                dependent_count: v["dependent_count"].as_u64().unwrap() as usize,
            },
        );
        assert!((u - v["expected_utility"].as_f64().unwrap()).abs() < 1e-8);
    }
    for v in f["calibration"].as_array().unwrap() {
        let cell = crate::services::calibration_engine::calibrated_probability(
            v["question_type"].as_str().unwrap(),
            v["confidence_band"].as_i64().unwrap() as i32,
            v["correct"].as_u64().unwrap() as u32,
            v["attempts"].as_u64().unwrap() as u32,
        );
        assert!((cell.calibrated_mean - v["expected_mean"].as_f64().unwrap()).abs() < 1e-8);
        assert!((cell.credible_sd - v["expected_sd"].as_f64().unwrap()).abs() < 1e-8);
    }
    assert!(
        (crate::services::strategy_engine::normalize_binary_choice_probability(0.48) - 0.52).abs()
            < 1e-9
    );
    for v in f["performance_300m"].as_array().unwrap() {
        let (p0, pt) = crate::services::fatigue_engine::performance_at_minute(
            v["p_base"].as_f64().unwrap(),
            v["item_difficulty"].as_f64().unwrap(),
            v["lambda_i"].as_f64().unwrap(),
            v["elapsed_minutes"].as_f64().unwrap(),
        );
        assert!((p0 - v["expected_p0"].as_f64().unwrap()).abs() < 1e-8);
        assert!((pt - v["expected_pt"].as_f64().unwrap()).abs() < 1e-8);
    }
    for v in f["decision_uncertainty"].as_array().unwrap() {
        let res = crate::services::strategy_engine::simulate_decision_under_uncertainty(
            &crate::services::strategy_engine::UncertaintyDecisionInputs {
                p_base: v["p_base"].as_f64().unwrap(),
                uncertainty_sd: v["uncertainty_sd"].as_f64().unwrap(),
                penalty: v["penalty"].as_f64().unwrap(),
                item_difficulty: v["item_difficulty"].as_f64().unwrap(),
                lambda_i: v["lambda_i"].as_f64().unwrap(),
                elapsed_minutes: v["elapsed_minutes"].as_f64().unwrap(),
                current_net_score: v["current_net_score"].as_f64().unwrap(),
                remaining_items: v["remaining_items"].as_u64().unwrap() as usize,
                target_score: v["target_score"].as_f64().unwrap(),
                minimum_score: v["minimum_score"].as_f64().unwrap(),
                baseline_future_p: v["baseline_future_p"].as_f64().unwrap(),
            },
        );
        assert!((res.p0 - v["expected_p0"].as_f64().unwrap()).abs() < 1e-8);
        assert!((res.p_at_time - v["expected_pt"].as_f64().unwrap()).abs() < 1e-8);
        assert!((res.expected_value - v["expected_ev"].as_f64().unwrap()).abs() < 1e-8);
        assert!(
            (res.risk_neutral_threshold - v["expected_p_star"].as_f64().unwrap()).abs() < 1e-8
        );
        assert!(
            (res.prob_goal_if_answer - v["expected_prob_goal_if_answer"].as_f64().unwrap()).abs()
                < 1e-8
        );
        assert!(
            (res.prob_goal_if_omit - v["expected_prob_goal_if_omit"].as_f64().unwrap()).abs()
                < 1e-8
        );
        let posture_str = serde_json::to_value(res.posture).unwrap();
        let action_str = serde_json::to_value(res.recommended_action).unwrap();
        assert_eq!(
            posture_str.as_str().unwrap(),
            v["expected_posture"].as_str().unwrap()
        );
        assert_eq!(
            action_str.as_str().unwrap(),
            v["expected_action"].as_str().unwrap()
        );
    }
    for v in f["strategy"].as_array().unwrap() {
        let dec = crate::services::strategy_engine::evaluate_strategy(
            v["p_calibrated"].as_f64().unwrap(),
            v["penalty"].as_f64().unwrap(),
            v["cutoff_pct"].as_f64().unwrap(),
            v["current_projected_pct"].as_f64().unwrap(),
            v["remaining_fraction"].as_f64().unwrap(),
        );
        assert!((dec.expected_value - v["expected_ev"].as_f64().unwrap()).abs() < 1e-8);
        assert!((dec.risk_neutral_threshold - v["expected_p_star"].as_f64().unwrap()).abs() < 1e-8);
        assert!(
            (dec.effective_threshold - v["expected_effective_threshold"].as_f64().unwrap()).abs()
                < 1e-8
        );
        let posture_str = serde_json::to_value(dec.posture).unwrap();
        let action_str = serde_json::to_value(dec.recommended_action).unwrap();
        assert_eq!(posture_str.as_str().unwrap(), v["expected_posture"].as_str().unwrap());
        assert_eq!(action_str.as_str().unwrap(), v["expected_action"].as_str().unwrap());
    }
    for v in f["fatigue"].as_array().unwrap() {
        let rep = crate::services::fatigue_engine::evaluate_fatigue_with_difficulty(
            v["qualifying_sessions"].as_u64().unwrap() as u32,
            v["early_correct"].as_u64().unwrap() as u32,
            v["early_total"].as_u64().unwrap() as u32,
            v["late_correct"].as_u64().unwrap() as u32,
            v["late_total"].as_u64().unwrap() as u32,
            v["early_avg_difficulty"].as_f64().unwrap(),
            v["late_avg_difficulty"].as_f64().unwrap(),
            v["early_mean_minutes"].as_f64().unwrap(),
            v["late_mean_minutes"].as_f64().unwrap(),
        );
        let status_str = serde_json::to_value(rep.status).unwrap();
        assert_eq!(status_str.as_str().unwrap(), v["expected_status"].as_str().unwrap());
        match (rep.estimated_lambda, v["expected_lambda"].as_f64()) {
            (None, None) => {}
            (Some(a), Some(b)) => assert!((a - b).abs() < 1e-8),
            other => panic!("fatigue lambda mismatch: {other:?}"),
        }
        match (rep.shrunk_accuracy_delta, v["expected_shrunk_delta"].as_f64()) {
            (None, None) => {}
            (Some(a), Some(b)) => assert!((a - b).abs() < 1e-8),
            other => panic!("fatigue delta mismatch: {other:?}"),
        }
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
