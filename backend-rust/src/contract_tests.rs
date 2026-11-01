use crate::services::{exam_engine::*, srs_engine::*};
use serde_json::Value;

#[test]
fn both_runtimes_share_utf8_mastery_weighted_score_srs_and_calendar_fixtures() {
    let f: Value =
        serde_json::from_str(include_str!("../../shared/domain-contracts.json")).unwrap();
    let q = &f["question"];
    let question:crate::models::question::QuestionDocument=bson::from_document(bson::doc!{
        "_id":bson::oid::ObjectId::new(),"question":q["question"].as_str().unwrap(),
        "answers":q["answers"].as_array().unwrap().iter().map(|a|a.as_str().unwrap()).collect::<Vec<_>>(),
        "correct_answers":[q["answers"][q["correct_index"].as_u64().unwrap() as usize].as_str().unwrap()],"disciplina":"Fixture"
    }).unwrap();
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
            instant
                .with_timezone(&chrono::FixedOffset::west_opt(10800).unwrap())
                .date_naive()
                .to_string(),
            v["day"].as_str().unwrap()
        );
    }
}
