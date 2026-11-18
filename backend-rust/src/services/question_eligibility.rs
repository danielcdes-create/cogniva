//! Centralized normative practice eligibility evaluator (`NT-COGNIVA-QUESTIONS-002` Sec. 7).
//!
//! All practice selection, exam simulation, cache-state, and topic availability consumers
//! delegate here so storage in `cogniva.todasQuestoes` never implies student practice eligibility.
use crate::services::question_contracts as contracts;
use chrono::NaiveDate;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashSet;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EligibilityMode {
    StandardObjective,
    HistoricalOnly,
    DiscursiveExplicit,
}

#[derive(Clone, Debug, Default)]
pub struct TrustedReviewContext {
    pub require_server_verified: bool,
    pub verified_review_event_ids: Option<HashSet<String>>,
}

#[derive(Clone, Debug, Default)]
pub struct ExamEligibilityContext {
    pub owner_user_id: Option<i64>,
    pub exam_id: Option<String>,
    pub exam_revision: Option<i64>,
    pub component_id: Option<String>,
    pub allowed_topic_ids: HashSet<String>,
    pub require_explicit_compatibility: bool,
    pub compatibility_records: Vec<Value>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EligibilityDecision {
    pub eligible: bool,
    pub reason_codes: Vec<&'static str>,
    pub coverage_mode: Option<&'static str>,
    pub is_borrowed_analogous: bool,
    pub primary_subject_id: Option<String>,
    pub primary_topic_id: Option<String>,
}

pub fn trusted_human_review(provenance: &Value, ctx: &TrustedReviewContext) -> bool {
    if !contracts::human_review(provenance) {
        return false;
    }
    let trust_level = provenance.get("trust_level").and_then(Value::as_str);
    if trust_level == Some("UNVERIFIED_LEGACY") {
        return false;
    }
    let review_event_id = provenance.get("review_event_id").and_then(Value::as_str);
    if trust_level == Some("SERVER_VERIFIED") {
        let Some(rev_id) = review_event_id else {
            return false;
        };
        if !contracts::is_uuid_string(rev_id) {
            return false;
        }
        if let Some(verified_set) = &ctx.verified_review_event_ids {
            if !verified_set.contains(rev_id) {
                return false;
            }
        }
        return true;
    }
    if ctx.require_server_verified {
        return false;
    }
    if let (Some(verified_set), Some(rev_id)) = (&ctx.verified_review_event_ids, review_event_id) {
        return verified_set.contains(rev_id);
    }
    true
}

pub fn compatibility_matches_question(
    rec: &Value,
    question: &Value,
    computed_semantic: &str,
) -> bool {
    if rec.get("status").and_then(Value::as_str) != Some("CONFIRMED") {
        return false;
    }
    let Some(qid) = question["_id"]["$oid"].as_str() else {
        return false;
    };
    if rec.get("question_id").and_then(Value::as_str) != Some(qid) {
        return false;
    }
    if let Some(rec_sem) = rec.get("question_semantic_revision").and_then(Value::as_str) {
        return rec_sem == computed_semantic;
    }
    rec.get("question_content_revision").and_then(Value::as_str)
        == question.get("content_revision").and_then(Value::as_str)
}

fn push_unique(reasons: &mut Vec<&'static str>, code: &'static str) {
    if !reasons.contains(&code) {
        reasons.push(code);
    }
}

pub fn evaluate_question_eligibility(
    question: &Value,
    catalog: &Value,
    exam_context: Option<&ExamEligibilityContext>,
    evaluation_date: NaiveDate,
    trusted_review_context: &TrustedReviewContext,
    mode: EligibilityMode,
) -> EligibilityDecision {
    if !question.is_object() {
        return EligibilityDecision {
            eligible: false,
            reason_codes: vec!["SCHEMA_VALIDATION_FAILED", "QUESTION_NOT_ELIGIBLE"],
            coverage_mode: None,
            is_borrowed_analogous: false,
            primary_subject_id: None,
            primary_topic_id: None,
        };
    }

    let mut reasons = Vec::new();

    // 1. status == ACTIVE
    if question.get("status").and_then(Value::as_str) != Some("ACTIVE") {
        push_unique(&mut reasons, "QUESTION_ARCHIVED");
    }

    // 2. question_type objective in standard practice
    let qtype = question.get("question_type").and_then(Value::as_str);
    if mode != EligibilityMode::DiscursiveExplicit && qtype == Some("OPEN_ENDED") {
        push_unique(&mut reasons, "OPEN_ENDED_EXCLUDED_FROM_OBJECTIVE_PRACTICE");
    } else if !matches!(qtype, Some("MULTIPLE_CHOICE" | "TRUE_FALSE" | "OPEN_ENDED")) {
        push_unique(&mut reasons, "SCHEMA_VALIDATION_FAILED");
    }

    // 3. Structural, answer-key, grading, and hash invariants
    if let Err(err) = contracts::validate_with_catalog(question, Some(catalog), true) {
        push_unique(&mut reasons, err.code);
    }

    // 4 & 5. Confirmed classification with active PRIMARY DIRECT link & trusted human review
    let links = question["classification"]["links"]
        .as_array()
        .cloned()
        .unwrap_or_default();
    let primary_link = links
        .iter()
        .find(|l| l["role"] == "PRIMARY" && l["coverage"] == "DIRECT");

    let active_subjects: HashSet<&str> = catalog["subjects"]
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter(|s| s["status"] == "ACTIVE")
                .filter_map(|s| s["subject_id"].as_str())
                .collect()
        })
        .unwrap_or_default();
    let active_topics: HashSet<(&str, &str)> = catalog["topics"]
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter(|t| t["status"] == "ACTIVE")
                .filter_map(|t| Some((t["subject_id"].as_str()?, t["topic_id"].as_str()?)))
                .collect()
        })
        .unwrap_or_default();

    let primary_subject_id = primary_link
        .and_then(|l| l["subject_id"].as_str())
        .map(str::to_string);
    let primary_topic_id = primary_link
        .and_then(|l| l["topic_id"].as_str())
        .map(str::to_string);

    let classification_confirmed = question["classification"]["state"] == "CONFIRMED"
        && primary_link.is_some()
        && primary_subject_id
            .as_deref()
            .is_some_and(|s| active_subjects.contains(s))
        && primary_subject_id
            .as_deref()
            .zip(primary_topic_id.as_deref())
            .is_some_and(|pair| active_topics.contains(&pair));

    if !classification_confirmed {
        push_unique(&mut reasons, "CLASSIFICATION_NOT_CONFIRMED");
    } else if let Some(plink) = primary_link {
        if !trusted_human_review(&plink["provenance"], trusted_review_context) {
            push_unique(&mut reasons, "HUMAN_REVIEW_REQUIRED");
        }
    }

    if let Some(origins) = question["origins"].as_array() {
        for origin in origins {
            if origin["kind"] == "OFFICIAL_EXAM"
                && origin["verification"] == "CONFIRMED"
                && !trusted_human_review(&origin["provenance"], trusted_review_context)
            {
                push_unique(&mut reasons, "HUMAN_REVIEW_REQUIRED");
            }
        }
    }

    // 6, 7 & 8. Confirmed validity, semantic/content binding, trusted review, and review_due_on
    let vstatus = question["validity"]["status"].as_str();
    let historical = question["validity"]["scope"] == "EXPLICIT_HISTORICAL_DATE";
    let expected_historical = mode == EligibilityMode::HistoricalOnly;

    if vstatus != Some("CURRENT") || historical != expected_historical {
        push_unique(&mut reasons, "VALIDITY_NOT_CONFIRMED");
    } else {
        if !trusted_human_review(&question["validity"]["decision"], trusted_review_context) {
            push_unique(&mut reasons, "HUMAN_REVIEW_REQUIRED");
        }
        if let Some(due_raw) = question["validity"]["review_due_on"].as_str() {
            match NaiveDate::parse_from_str(due_raw, "%Y-%m-%d") {
                Ok(due_date) if due_date < evaluation_date => {
                    push_unique(&mut reasons, "VALIDITY_REVIEW_EXPIRED");
                }
                Ok(_) => {}
                Err(_) => push_unique(&mut reasons, "INVALID_VALIDITY_DEADLINE"),
            }
        }
    }

    // 9. Exam compatibility context
    let mut coverage_mode = None;
    let mut is_borrowed_analogous = false;

    if let Some(ectx) = exam_context {
        let computed_sem = contracts::semantic_hash(question).ok();
        let mut relations = HashSet::new();

        if let Some(sem_rev) = computed_sem.as_deref() {
            for rec in &ectx.compatibility_records {
                if let Some(owner) = ectx.owner_user_id {
                    if rec.get("owner_user_id").and_then(Value::as_i64) != Some(owner) {
                        continue;
                    }
                }
                if let Some(eid) = ectx.exam_id.as_deref() {
                    if rec.get("exam_id").and_then(Value::as_str) != Some(eid) {
                        continue;
                    }
                }
                if let Some(erev) = ectx.exam_revision {
                    if rec.get("exam_revision").and_then(Value::as_i64) != Some(erev) {
                        continue;
                    }
                }
                if let Some(cid) = ectx.component_id.as_deref() {
                    if rec.get("component_id").and_then(Value::as_str) != Some(cid) {
                        continue;
                    }
                }
                if compatibility_matches_question(rec, question, sem_rev)
                    && trusted_human_review(&rec["provenance"], trusted_review_context)
                {
                    if let Some(rel) = rec.get("relation").and_then(Value::as_str) {
                        relations.insert(rel);
                    }
                }
            }
        }

        let in_allowed_topic = primary_topic_id
            .as_deref()
            .is_some_and(|tid| ectx.allowed_topic_ids.contains(tid));

        if relations.contains("EXCLUDED") {
            push_unique(&mut reasons, "EXAM_COMPATIBILITY_EXCLUDED");
        } else if relations.contains("DIRECT") && in_allowed_topic {
            coverage_mode = Some("DIRECT");
            is_borrowed_analogous = false;
        } else if relations.contains("ANALOGOUS") {
            coverage_mode = Some("ANALOGOUS");
            is_borrowed_analogous = true;
        } else if !ectx.require_explicit_compatibility && in_allowed_topic {
            coverage_mode = Some("DIRECT");
            is_borrowed_analogous = false;
        } else {
            push_unique(&mut reasons, "EXAM_COMPATIBILITY_MISSING");
        }
    }

    if !reasons.is_empty() {
        push_unique(&mut reasons, "QUESTION_NOT_ELIGIBLE");
        return EligibilityDecision {
            eligible: false,
            reason_codes: reasons,
            coverage_mode: None,
            is_borrowed_analogous: false,
            primary_subject_id,
            primary_topic_id,
        };
    }

    EligibilityDecision {
        eligible: true,
        reason_codes: Vec::new(),
        coverage_mode: Some(coverage_mode.unwrap_or("DIRECT")),
        is_borrowed_analogous,
        primary_subject_id,
        primary_topic_id,
    }
}

pub fn evaluate_bson_question_for_practice(
    doc: &bson::Document,
    catalog: &Value,
    exam_context: Option<&ExamEligibilityContext>,
    evaluation_date: NaiveDate,
    trusted_review_context: &TrustedReviewContext,
    mode: EligibilityMode,
) -> EligibilityDecision {
    let Ok(projected) = contracts::bson_to_contract_question(doc) else {
        return EligibilityDecision {
            eligible: false,
            reason_codes: vec!["SCHEMA_VALIDATION_FAILED", "QUESTION_NOT_ELIGIBLE"],
            coverage_mode: None,
            is_borrowed_analogous: false,
            primary_subject_id: None,
            primary_topic_id: None,
        };
    };
    evaluate_question_eligibility(
        &projected,
        catalog,
        exam_context,
        evaluation_date,
        trusted_review_context,
        mode,
    )
}

pub fn utc_today() -> NaiveDate {
    chrono::Utc::now().date_naive()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_exam_ctx(raw: &Value) -> Option<ExamEligibilityContext> {
        if raw.is_null() {
            return None;
        }
        let allowed_topic_ids = raw["allowed_topic_ids"]
            .as_array()
            .map(|arr| {
                arr.iter()
                    .filter_map(Value::as_str)
                    .map(str::to_string)
                    .collect::<HashSet<_>>()
            })
            .unwrap_or_default();
        Some(ExamEligibilityContext {
            owner_user_id: raw.get("owner_user_id").and_then(Value::as_i64),
            exam_id: raw
                .get("exam_id")
                .and_then(Value::as_str)
                .map(str::to_string),
            exam_revision: raw.get("exam_revision").and_then(Value::as_i64),
            component_id: raw
                .get("component_id")
                .and_then(Value::as_str)
                .map(str::to_string),
            allowed_topic_ids,
            require_explicit_compatibility: raw
                .get("require_explicit_compatibility")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            compatibility_records: raw["compatibility_records"]
                .as_array()
                .cloned()
                .unwrap_or_default(),
        })
    }

    #[test]
    fn shared_eligibility_parity_vectors_t13_through_t18_t21_t24() {
        let bundle: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/parity-vectors-v2-1.json"
        ))
        .unwrap();
        let cat = contracts::catalog();
        let vectors = bundle["eligibility_vectors"].as_array().unwrap();
        assert_eq!(vectors.len(), 12);

        for vec in vectors {
            let id = vec["id"].as_str().unwrap();
            let eval_date =
                NaiveDate::parse_from_str(vec["evaluation_date"].as_str().unwrap(), "%Y-%m-%d")
                    .unwrap();
            let ectx = parse_exam_ctx(&vec["exam_context"]);
            let trusted_ctx = TrustedReviewContext {
                require_server_verified: vec["trusted_review_context"]["require_server_verified"]
                    .as_bool()
                    .unwrap_or(false),
                verified_review_event_ids: None,
            };
            let res = evaluate_question_eligibility(
                &vec["question"],
                &cat,
                ectx.as_ref(),
                eval_date,
                &trusted_ctx,
                EligibilityMode::StandardObjective,
            );
            let exp = &vec["expected"];
            assert_eq!(
                res.eligible,
                exp["eligible"].as_bool().unwrap(),
                "{id}: {res:?}"
            );
            let exp_reasons: Vec<&str> = exp["reason_codes"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap())
                .collect();
            assert_eq!(res.reason_codes, exp_reasons, "{id}: {res:?}");
            assert_eq!(res.coverage_mode, exp["coverage_mode"].as_str(), "{id}");
            assert_eq!(
                res.is_borrowed_analogous,
                exp["is_borrowed_analogous"].as_bool().unwrap(),
                "{id}"
            );
        }
    }

    #[test]
    fn numeric_score_edit_preserves_normative_eligibility_t04_t24() {
        let mut mc: Value = serde_json::from_str(include_str!(
            "../../../shared/examples/question-contracts/question-multiple-choice.json"
        ))
        .unwrap();
        contracts::populate_v2_1_hashes(&mut mc).unwrap();
        let orig_content = mc["content_revision"].as_str().unwrap().to_string();
        let orig_semantic = mc["semantic_revision"].as_str().unwrap().to_string();
        mc["validity"]["evaluated_content_revision"] = Value::String(orig_content.clone());
        mc["validity"]["evaluated_semantic_revision"] = Value::String(orig_semantic.clone());
        mc["validity"]["evaluation_mode"] = Value::String("SEMANTIC".into());

        // Adjust numeric grading only (100/60 -> 10/6)
        mc["revision"] = serde_json::json!(2);
        mc["grading"]["max_score"] = serde_json::json!(10);
        mc["grading"]["pass_score"] = serde_json::json!(6);
        contracts::populate_v2_1_hashes(&mut mc).unwrap();
        assert_ne!(mc["content_revision"].as_str().unwrap(), orig_content);
        assert_eq!(mc["semantic_revision"].as_str().unwrap(), orig_semantic);

        let cat = contracts::catalog();
        let decision = evaluate_question_eligibility(
            &mc,
            &cat,
            None,
            NaiveDate::from_ymd_opt(2026, 11, 15).unwrap(),
            &TrustedReviewContext::default(),
            EligibilityMode::StandardObjective,
        );
        assert!(decision.eligible, "{decision:?}");
    }
}
