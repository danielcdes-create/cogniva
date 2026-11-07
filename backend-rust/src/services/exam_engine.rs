use crate::models::exam_hierarchy::*;
use chrono::NaiveDate;
use mongodb::Database;
use std::collections::{HashMap, HashSet};

pub fn assign_exam_id(requested: Option<String>) -> String {
    match requested {
        Some(id) if !id.trim().is_empty() => id.trim().to_string(),
        _ => uuid::Uuid::new_v4().to_string(),
    }
}

pub fn upsert_exam_by_client_id(store: &mut HashMap<String, ExamBlueprint>, exam: ExamBlueprint) {
    store.insert(exam.id.clone(), exam);
}

fn parse_iso_date(raw: &str) -> Option<NaiveDate> {
    let trimmed = raw.trim();
    if let Ok(date) = NaiveDate::parse_from_str(trimmed, "%Y-%m-%d") {
        return Some(date);
    }
    if let Some(prefix) = trimmed.get(..10) {
        return NaiveDate::parse_from_str(prefix, "%Y-%m-%d").ok();
    }
    None
}

pub fn study_date(at: chrono::DateTime<chrono::Utc>) -> NaiveDate {
    at.with_timezone(&chrono::FixedOffset::west_opt(10800).unwrap())
        .date_naive()
}
pub fn study_today() -> NaiveDate {
    study_date(chrono::Utc::now())
}

pub fn days_until(iso_date: &str, today: NaiveDate) -> Option<i64> {
    parse_iso_date(iso_date).map(|date| (date - today).num_days())
}

/// Rótulo polimórfico: data exata vira contagem de dias; janela devolve o próprio rótulo.
pub fn format_deadline_label(target: &ExamDateTarget, today: NaiveDate) -> String {
    match target {
        ExamDateTarget::ExactDate { iso_date } => match days_until(iso_date, today) {
            Some(days) => format!("{} dias", days.max(0)),
            None => "Sem previsão".to_string(),
        },
        ExamDateTarget::EstimatedWindow { label, .. } => label.clone(),
        ExamDateTarget::Undefined => "Sem previsão".to_string(),
    }
}

pub fn urgency_factor(exam: &LeverageExam, today: NaiveDate) -> f64 {
    if let Some(iso) = &exam.exact_date_iso {
        if let Some(days) = days_until(iso, today) {
            if (0..=60).contains(&days) {
                return 2.0;
            }
        }
    }
    if exam.status == ExamLifecycleStatus::EditalPublicado {
        return 1.5;
    }
    1.0
}

fn normalized_weights(exam: &LeverageExam) -> HashMap<String, f64> {
    let live: Vec<&LeverageSubject> = exam
        .subjects
        .iter()
        .filter(|subject| !subject.archived_only && subject.weight > 0.0)
        .collect();
    let sum: f64 = live.iter().map(|subject| subject.weight).sum();
    let mut weights = HashMap::new();
    if sum <= 0.0 {
        return weights;
    }
    for subject in live {
        *weights.entry(subject.canonical_subject_id.clone()).or_insert(0.0) += subject.weight / sum;
    }
    weights
}

pub fn leverage_score(
    exams: &[LeverageExam],
    canonical_subject_id: &str,
    mastery: f64,
    today: NaiveDate,
) -> f64 {
    let mastery = mastery.clamp(0.0, 1.0);
    let mut weighted_urgency = 0.0;
    for exam in exams {
        let weights = normalized_weights(exam);
        if let Some(weight) = weights.get(canonical_subject_id) {
            weighted_urgency += weight * urgency_factor(exam, today);
        }
    }
    weighted_urgency * (1.0 - mastery)
}

fn contest_list(names: &[String]) -> String {
    match names {
        [] => String::new(),
        [only] => only.clone(),
        [first, second] => format!("{first} e {second}"),
        _ => {
            let (head, tail) = names.split_at(names.len() - 1);
            format!("{} e {}", head.join(", "), tail[0])
        }
    }
}

pub fn common_trunk_sentence(
    suggested_questions: u32,
    subject_name: &str,
    contests: &[String],
) -> String {
    format!(
        "Estudar {suggested_questions} questões de {subject_name} avança {} ao mesmo tempo",
        contest_list(contests)
    )
}

#[derive(Debug, Clone, PartialEq)]
pub struct CanonicalActivitySlice {
    pub canonical_subject_id: String,
    pub canonical_topic_id: String,
    pub accuracy_pct: f64,
    pub confidence_avg: Option<i32>,
    pub mastered: bool,
    pub high_confidence_error: bool,
}

/// Domínio 0..1. Questão dominada no SRS vale 1. Erro de alta confiança corta pela metade.
pub fn mastery_fraction(accuracy_pct: f64, mastered: bool, high_confidence_error: bool) -> f64 {
    let mut value = (accuracy_pct / 100.0).clamp(0.0, 1.0);
    if mastered {
        value = 1.0;
    }
    if high_confidence_error {
        value *= 0.5;
    }
    value.clamp(0.0, 1.0)
}

pub fn mastery_with_confidence(
    accuracy_pct: f64,
    mastered: bool,
    high_confidence_error: bool,
    confidence_avg: Option<i32>,
) -> f64 {
    let base = mastery_fraction(accuracy_pct, mastered, high_confidence_error);
    if mastered {
        base
    } else {
        base * confidence_avg
            .map(|v| v.clamp(0, 3) as f64 / 3.0)
            .unwrap_or(1.0)
    }
}

pub fn mastery_by_canonical_subject(rows: &[CanonicalActivitySlice]) -> HashMap<String, f64> {
    let mut sums: HashMap<String, (f64, usize)> = HashMap::new();
    for row in rows {
        if row.canonical_subject_id.is_empty() {
            continue;
        }
        let value = mastery_with_confidence(
            row.accuracy_pct,
            row.mastered,
            row.high_confidence_error,
            row.confidence_avg,
        );
        let entry = sums
            .entry(row.canonical_subject_id.clone())
            .or_insert((0.0, 0));
        entry.0 += value;
        entry.1 += 1;
    }
    sums.into_iter()
        .map(|(id, (sum, count))| (id, if count == 0 { 0.0 } else { sum / count as f64 }))
        .collect()
}

pub fn mastery_by_canonical_topic(rows: &[CanonicalActivitySlice]) -> HashMap<String, f64> {
    let mut sums: HashMap<String, (f64, usize)> = HashMap::new();
    for row in rows {
        if row.canonical_topic_id.is_empty() {
            continue;
        }
        let value = mastery_with_confidence(
            row.accuracy_pct,
            row.mastered,
            row.high_confidence_error,
            row.confidence_avg,
        );
        let entry = sums
            .entry(row.canonical_topic_id.clone())
            .or_insert((0.0, 0));
        entry.0 += value;
        entry.1 += 1;
    }
    sums.into_iter()
        .map(|(id, (sum, count))| (id, if count == 0 { 0.0 } else { sum / count as f64 }))
        .collect()
}

pub fn card_from_canonical_activity(
    exams: &[LeverageExam],
    rows: &[CanonicalActivitySlice],
    focuses: &[TrunkFocus],
    suggested_questions: u32,
    today: NaiveDate,
) -> Option<CommonTrunkCard> {
    let mastery = mastery_by_canonical_subject(rows);
    select_common_trunk_for_focuses(exams, &mastery, focuses, suggested_questions, today)
}

pub fn select_common_trunk_card(
    exams: &[LeverageExam],
    mastery_by_subject: &HashMap<String, f64>,
    trunk_focus: Option<&TrunkFocus>,
    suggested_questions: u32,
    today: NaiveDate,
) -> Option<CommonTrunkCard> {
    let owned = trunk_focus.cloned();
    let focuses: Vec<TrunkFocus> = owned.into_iter().collect();
    select_common_trunk_for_focuses(
        exams,
        mastery_by_subject,
        &focuses,
        suggested_questions,
        today,
    )
}

pub fn select_common_trunk_for_focuses(
    exams: &[LeverageExam],
    mastery_by_subject: &HashMap<String, f64>,
    focuses: &[TrunkFocus],
    suggested_questions: u32,
    today: NaiveDate,
) -> Option<CommonTrunkCard> {
    let slugs: HashSet<&str> = focuses.iter().map(|focus| focus.slug.as_str()).collect();
    let cores: HashSet<&str> = focuses
        .iter()
        .flat_map(|focus| focus.core_subject_ids.iter().map(|id| id.as_str()))
        .collect();
    let pool: Vec<&LeverageExam> = exams
        .iter()
        .filter(|exam| exam.is_active_in_ladder && !matches!(exam.status, ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido))
        .filter(|exam| slugs.is_empty() || slugs.contains(exam.trunk_slug.as_str()))
        .collect();

    let mut presence: HashMap<String, Vec<String>> = HashMap::new();
    let mut names: HashMap<String, String> = HashMap::new();
    for exam in &pool {
        let mut seen_subjects = HashSet::new();
        for subject in exam
            .subjects
            .iter()
            .filter(|subject| !subject.archived_only)
        {
            if !seen_subjects.insert(&subject.canonical_subject_id) { continue; }
            presence
                .entry(subject.canonical_subject_id.clone())
                .or_default()
                .push(exam.short_name.clone());
            names
                .entry(subject.canonical_subject_id.clone())
                .or_insert_with(|| subject.name.clone());
        }
    }

    let mut best: Option<CommonTrunkCard> = None;
    for (subject_id, contests) in &presence {
        let eligible = contests.len() >= 2 || cores.contains(subject_id.as_str());
        if !eligible {
            continue;
        }
        let mastery = mastery_by_subject.get(subject_id).copied().unwrap_or(0.0);
        let score = leverage_score(
            &pool.iter().map(|exam| (*exam).clone()).collect::<Vec<_>>(),
            subject_id,
            mastery,
            today,
        );
        let subject_name = names.get(subject_id).cloned().unwrap_or_default();
        let card = CommonTrunkCard {
            canonical_subject_id: subject_id.clone(),
            subject_name: subject_name.clone(),
            suggested_questions,
            contest_names: contests.clone(),
            sentence: common_trunk_sentence(suggested_questions, &subject_name, contests),
            score,
        };
        let replace = match &best {
            None => true,
            Some(current) => {
                card.score > current.score + 1e-12
                    || ((card.score - current.score).abs() <= 1e-12
                        && card.canonical_subject_id < current.canonical_subject_id)
            }
        };
        if replace {
            best = Some(card);
        }
    }
    best
}

/// IP do concurso de menor prioridade na escada. Tópico `ARQUIVADO` sai do numerador e do denominador.
pub fn readiness_for_blueprints(
    exams: &[ExamBlueprint],
    mastery_by_topic: &HashMap<String, f64>,
) -> f64 {
    let primary = exams
        .iter()
        .filter(|exam| exam.is_active_in_ladder && !matches!(exam.status, ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido))
        .min_by_key(|exam| exam.ladder_priority);
    let Some(exam) = primary else {
        return 0.0;
    };
    let subjects: Vec<ReadinessSubject> = exam
        .subjects
        .iter()
        .map(|subject| {
            let topics: Vec<ReadinessTopic> = subject
                .topics
                .iter()
                .map(|topic| ReadinessTopic {
                    archived: topic.diff_status == CanonicalTopicStatus::Arquivado,
                    practiced: mastery_by_topic.contains_key(&topic.canonical_topic_id),
                })
                .collect();
            let live: Vec<_> = subject
                .topics
                .iter()
                .filter(|topic| topic.diff_status != CanonicalTopicStatus::Arquivado)
                .collect();
            let real_mastery = if live.is_empty() {
                0.0
            } else {
                live.iter()
                    .map(|topic| {
                        mastery_by_topic
                            .get(&topic.canonical_topic_id)
                            .copied()
                            .unwrap_or(0.0)
                    })
                    .sum::<f64>()
                    / live.len() as f64
            };
            ReadinessSubject {
                weight: subject.weight,
                real_mastery,
                topics,
            }
        })
        .collect();
    readiness_index(&subjects)
}

pub fn readiness_index(subjects: &[ReadinessSubject]) -> f64 {
    let mut numerator = 0.0;
    let mut denominator = 0.0;
    for subject in subjects {
        let live: Vec<&ReadinessTopic> = subject
            .topics
            .iter()
            .filter(|topic| !topic.archived)
            .collect();
        if live.is_empty() || subject.weight <= 0.0 {
            continue;
        }
        let practiced = live.iter().filter(|topic| topic.practiced).count() as f64;
        let coverage = practiced / live.len() as f64;
        let mastery = subject.real_mastery.clamp(0.0, 1.0);
        numerator += subject.weight * (0.7 * mastery + 0.3 * coverage);
        denominator += subject.weight;
    }
    if denominator == 0.0 {
        0.0
    } else {
        (100.0 * numerator / denominator).clamp(0.0, 100.0)
    }
}

pub fn publish_edital_diff(
    previous: &[TopicRef],
    published: &[TopicRef],
    practiced_canonical_topic_ids: &HashSet<String>,
) -> EditalPublishResult {
    let previous_ids: HashSet<&str> = previous
        .iter()
        .map(|topic| topic.canonical_topic_id.as_str())
        .collect();
    let published_ids: HashSet<&str> = published
        .iter()
        .map(|topic| topic.canonical_topic_id.as_str())
        .collect();

    let mut topics = Vec::new();
    for topic in published {
        if previous_ids.contains(topic.canonical_topic_id.as_str()) {
            topics.push(ClassifiedTopic {
                canonical_topic_id: topic.canonical_topic_id.clone(),
                canonical_subject_id: topic.canonical_subject_id.clone(),
                display_title: topic.display_title.clone(),
                diff_status: CanonicalTopicStatus::OficialConfirmado,
                coverage_already_filled: practiced_canonical_topic_ids
                    .contains(&topic.canonical_topic_id),
            });
        } else {
            topics.push(ClassifiedTopic {
                canonical_topic_id: topic.canonical_topic_id.clone(),
                canonical_subject_id: topic.canonical_subject_id.clone(),
                display_title: topic.display_title.clone(),
                diff_status: CanonicalTopicStatus::OficialNovo,
                coverage_already_filled: practiced_canonical_topic_ids
                    .contains(&topic.canonical_topic_id),
            });
        }
    }
    for topic in previous {
        if !published_ids.contains(topic.canonical_topic_id.as_str()) {
            topics.push(ClassifiedTopic {
                canonical_topic_id: topic.canonical_topic_id.clone(),
                canonical_subject_id: topic.canonical_subject_id.clone(),
                display_title: topic.display_title.clone(),
                diff_status: CanonicalTopicStatus::Arquivado,
                coverage_already_filled: practiced_canonical_topic_ids
                    .contains(&topic.canonical_topic_id),
            });
        }
    }

    let confirmed_count = topics
        .iter()
        .filter(|topic| topic.diff_status == CanonicalTopicStatus::OficialConfirmado)
        .count() as u32;
    let new_count = topics
        .iter()
        .filter(|topic| topic.diff_status == CanonicalTopicStatus::OficialNovo)
        .count() as u32;
    let archived_count = topics
        .iter()
        .filter(|topic| topic.diff_status == CanonicalTopicStatus::Arquivado)
        .count() as u32;

    EditalPublishResult {
        topics,
        confirmed_count,
        new_count,
        archived_count,
    }
}

pub fn project_cross_exam_coverage(
    history: &[CanonicalMasteryRecord],
    links: &[ExamTopicLink],
) -> Vec<ProjectedTopicCoverage> {
    let by_topic: HashMap<&str, &CanonicalMasteryRecord> = history
        .iter()
        .map(|record| (record.canonical_topic_id.as_str(), record))
        .collect();
    links
        .iter()
        .map(|link| {
            let record = by_topic.get(link.canonical_topic_id.as_str()).copied();
            let mastery = record.map(|item| item.mastery).unwrap_or(0.0);
            ProjectedTopicCoverage {
                exam_id: link.exam_id.clone(),
                canonical_subject_id: link.canonical_subject_id.clone(),
                canonical_topic_id: link.canonical_topic_id.clone(),
                mastery,
                practiced: record.is_some(),
                hidden_from_study_queue: link.diff_status == CanonicalTopicStatus::Arquivado,
            }
        })
        .collect()
}

/// Média de domínio dos tópicos visíveis (não arquivados) da disciplina no concurso.
pub fn subject_mastery_percentage(
    projected: &[ProjectedTopicCoverage],
    exam_id: &str,
    canonical_subject_id: &str,
) -> f64 {
    let visible: Vec<&ProjectedTopicCoverage> = projected
        .iter()
        .filter(|item| {
            item.exam_id == exam_id
                && item.canonical_subject_id == canonical_subject_id
                && !item.hidden_from_study_queue
        })
        .collect();
    if visible.is_empty() {
        return 0.0;
    }
    visible.iter().map(|item| item.mastery).sum::<f64>() / visible.len() as f64
}

pub fn draw_weighted_simulado(
    quotas: &[SubjectQuota],
    questions: &[SimuladoCandidate],
) -> Vec<String> {
    let mut picked = Vec::new();
    for quota in quotas {
        let mut pool: Vec<&SimuladoCandidate> = questions
            .iter()
            .filter(|question| {
                question.canonical_subject_id == quota.canonical_subject_id
                    && !picked.contains(&question.question_id)
                    && !question.archived
                    && quota
                        .allowed_topic_ids
                        .contains(&question.canonical_topic_id)
            })
            .collect();
        use rand::seq::SliceRandom;
        pool.shuffle(&mut rand::thread_rng());
        let take = quota.question_count as usize;
        for question in pool.into_iter().take(take) {
            if !picked.contains(&question.question_id) {
                picked.push(question.question_id.clone());
            }
        }
    }
    picked
}

pub fn weighted_net_score(outcomes: &[SubjectOutcome]) -> f64 {
    let mut numerator = 0.0;
    let mut denominator = 0.0;
    for outcome in outcomes {
        if outcome.drawn == 0
            || outcome.weight <= 0.0
            || !outcome.weight.is_finite()
            || !outcome.penalty.is_finite()
        {
            continue;
        }
        let correct = outcome.correct.min(outcome.drawn);
        let wrong = outcome.wrong.min(outcome.drawn - correct);
        numerator +=
            outcome.weight * (correct as f64 - outcome.penalty.clamp(0.0, 1.0) * wrong as f64);
        denominator += outcome.weight * outcome.drawn as f64;
    }
    if denominator == 0.0 {
        0.0
    } else {
        (100.0 * numerator / denominator).clamp(0.0, 100.0)
    }
}

pub fn quotas_from_blueprint(exam: &ExamBlueprint) -> Vec<SubjectQuota> {
    exam.subjects
        .iter()
        .filter(|subject| {
            subject
                .topics
                .iter()
                .any(|topic| topic.diff_status != CanonicalTopicStatus::Arquivado)
        })
        .filter_map(|subject| {
            let count = subject.question_count.unwrap_or(0);
            if count == 0 || subject.weight <= 0.0 {
                None
            } else {
                Some(SubjectQuota {
                    canonical_subject_id: subject.canonical_subject_id.clone(),
                    weight: subject.weight,
                    question_count: count,
                    allowed_topic_ids: subject
                        .topics
                        .iter()
                        .filter(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
                        .map(|t| t.canonical_topic_id.clone())
                        .collect(),
                })
            }
        })
        .collect()
}

pub fn subject_archived_only(topics_empty: bool, all_archived: bool) -> bool {
    !topics_empty && all_archived
}

pub fn blueprint_to_leverage(exam: &ExamBlueprint) -> LeverageExam {
    LeverageExam {
        exam_id: exam.id.clone(),
        short_name: exam.short_name.clone(),
        trunk_slug: exam.trunk_slug.clone(),
        status: exam.status.clone(),
        exact_date_iso: match &exam.target_date {
            ExamDateTarget::ExactDate { iso_date } => Some(iso_date.clone()),
            _ => None,
        },
        is_active_in_ladder: exam.is_active_in_ladder,
        subjects: exam
            .subjects
            .iter()
            .map(|subject| {
                let archived_only = subject_archived_only(
                    subject.topics.is_empty(),
                    subject
                        .topics
                        .iter()
                        .all(|topic| topic.diff_status == CanonicalTopicStatus::Arquivado),
                );
                LeverageSubject {
                    canonical_subject_id: subject.canonical_subject_id.clone(),
                    name: subject.display_name.clone(),
                    weight: subject.weight,
                    archived_only,
                }
            })
            .collect(),
    }
}

pub fn system_trunks() -> Vec<CareerTrunk> {
    system_catalog().trunks
}

pub fn system_exam_blueprints() -> Vec<ExamBlueprint> {
    system_catalog().exams
}

pub fn canonical_subjects() -> Vec<CanonicalSubject> {
    system_catalog().subjects
}

pub fn system_catalog() -> TrunkCatalog {
    let mut catalog: TrunkCatalog = serde_json::from_str(include_str!("../../../shared/canonical-catalog.json"))
        .expect("validated catalog");
    let knowledge = crate::services::question_contracts::catalog_ref();
    catalog.subjects = knowledge["subjects"].as_array().unwrap().iter().filter(|s| s["status"] == "ACTIVE").map(|s| {
        let id = s["subject_id"].as_str().unwrap();
        CanonicalSubject {
            canonical_subject_id: id.into(), code: id.into(), name: s["label"].as_str().unwrap().into(),
            aliases: s["aliases"].as_array().unwrap().iter().map(|v|v.as_str().unwrap().into()).collect(),
            trunk_slugs: s["trunk_links"].as_array().unwrap().iter().map(|v|v["trunk_id"].as_str().unwrap().into()).collect(),
            topics: knowledge["topics"].as_array().unwrap().iter().filter(|t|t["subject_id"]==id && t["status"]=="ACTIVE").map(|t|CanonicalTopic {topic_id:t["topic_id"].as_str().unwrap().into(),title:t["label"].as_str().unwrap().into(),tags:vec![]}).collect(),
        }
    }).collect();
    if knowledge["catalog_version"]!=crate::services::catalog_runtime::bundled().current["catalog_version"] {
        let old=catalog.trunks.clone();
        catalog.trunks=knowledge["trunks"].as_array().unwrap().iter().filter(|t|t["status"]=="ACTIVE").map(|t|{
            let slug=t["trunk_id"].as_str().unwrap();let mut core=Vec::new();let mut complementary=Vec::new();
            for subject in &catalog.subjects {
                if subject.topics.is_empty(){continue;}
                let source=knowledge["subjects"].as_array().unwrap().iter().find(|s|s["subject_id"]==subject.canonical_subject_id).unwrap();
                if let Some(link)=source["trunk_links"].as_array().unwrap().iter().find(|l|l["trunk_id"]==slug){
                    let prior=old.iter().find(|t|t.slug==slug).and_then(|t|t.core_subjects.iter().chain(t.complementary_subjects.iter()).find(|s|s.canonical_subject_id==subject.canonical_subject_id));
                    let foundational=link["role"]=="FOUNDATIONAL";
                    let item=TrunkSubjectDefault{canonical_subject_id:subject.canonical_subject_id.clone(),name:subject.name.clone(),default_weight:prior.map(|s|s.default_weight).unwrap_or(1.0),relevance_tier:if foundational{RelevanceTier::Core}else{RelevanceTier::Complementary},topics:subject.topics.clone()};
                    if foundational{core.push(item)}else{complementary.push(item)}
                }
            }
            CareerTrunk{slug:slug.into(),name:t["label"].as_str().unwrap().into(),icon_key:old.iter().find(|t|t.slug==slug).map(|t|t.icon_key.clone()).unwrap_or_else(||"work".into()),core_subjects:core,complementary_subjects:complementary,is_system_default:true}
        }).collect();
    } else {for t in knowledge["trunks"].as_array().unwrap().iter().filter(|t|t["status"]=="ACTIVE") {
        let slug=t["trunk_id"].as_str().unwrap();
        if !catalog.trunks.iter().any(|old|old.slug==slug) {
            catalog.trunks.push(CareerTrunk {slug:slug.into(),name:t["label"].as_str().unwrap().into(),icon_key:"work".into(),core_subjects:vec![],complementary_subjects:vec![],is_system_default:true});
        }
    }}
    catalog
}

pub fn core_subject_ids(trunk_slug: &str) -> Vec<String> {
    system_trunks()
        .into_iter()
        .find(|trunk| trunk.slug == trunk_slug)
        .map(|trunk| {
            trunk
                .core_subjects
                .into_iter()
                .map(|subject| subject.canonical_subject_id)
                .collect()
        })
        .unwrap_or_default()
}

#[derive(Default)]
pub struct ExamMemory {
    pub exams: HashMap<String, ExamBlueprint>,
}

impl ExamMemory {
    pub fn seeded() -> Self {
        let mut memory = Self::default();
        for exam in system_exam_blueprints() {
            memory.exams.insert(exam.id.clone(), exam);
        }
        memory
    }

    pub fn leverage_inputs(&self) -> Vec<LeverageExam> {
        let mut exams: Vec<_> = self.exams.values().map(blueprint_to_leverage).collect();
        exams.sort_by(|left, right| left.exam_id.cmp(&right.exam_id));
        exams
    }
}

pub fn apply_publish(
    exam: &mut ExamBlueprint,
    request: &PublishEditalRequest,
) -> EditalPublishResult {
    let previous: Vec<TopicRef> = exam
        .subjects
        .iter()
        .flat_map(|subject| {
            subject.topics.iter().map(|topic| TopicRef {
                canonical_topic_id: topic.canonical_topic_id.clone(),
                canonical_subject_id: topic.canonical_subject_id.clone(),
                display_title: topic.display_title.clone(),
            })
        })
        .collect();
    let practiced: HashSet<String> = request
        .practiced_canonical_topic_ids
        .iter()
        .cloned()
        .collect();
    let result = publish_edital_diff(&previous, &request.published_topics, &practiced);

    let mut by_subject: HashMap<String, Vec<ExamTopicItem>> = HashMap::new();
    for topic in &result.topics {
        by_subject
            .entry(topic.canonical_subject_id.clone())
            .or_default()
            .push(ExamTopicItem {
                local_topic_id: topic.canonical_topic_id.clone(),
                canonical_topic_id: topic.canonical_topic_id.clone(),
                canonical_subject_id: topic.canonical_subject_id.clone(),
                display_title: topic.display_title.clone(),
                diff_status: topic.diff_status.clone(),
            });
    }
    for subject in &mut exam.subjects {
        if let Some(topics) = by_subject.remove(&subject.canonical_subject_id) {
            subject.topics = topics;
        }
    }
    for (subject_id, topics) in by_subject {
        let display_name = canonical_subjects()
            .into_iter()
            .find(|s| s.canonical_subject_id == subject_id)
            .map(|s| s.name)
            .unwrap_or_else(|| subject_id.clone());
        exam.subjects.push(ExamSubjectConfig {
            stage: String::new(),
            local_subject_id: subject_id.clone(),
            canonical_subject_id: subject_id,
            display_name,
            category: SubjectCategory::ConhecimentosEspecificos,
            weight: 1.0,
            question_count: Some(topics.len() as u32),
            relevance_tier: RelevanceTier::Specific,
            topics,
        });
    }
    exam.status = ExamLifecycleStatus::EditalPublicado;
    exam.edital_diff_summary = Some(EditalDiffSummary {
        confirmed_count: result.confirmed_count,
        new_count: result.new_count,
        archived_count: result.archived_count,
        published_at_iso: request
            .published_at_iso
            .clone()
            .unwrap_or_else(|| chrono::Utc::now().to_rfc3339()),
    });
    if exam.sync_status == "SYNCED" {
        exam.sync_status = "PENDING_UPDATE".to_string();
    }
    result
}

pub async fn load_canonical_slices(db: &Database, user_id: i64) -> Result<Vec<CanonicalActivitySlice>, crate::error::AppError> {
    crate::services::knowledge::slices(db,user_id).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn topic_ref(id: &str, subject: &str, title: &str) -> TopicRef {
        TopicRef {
            canonical_topic_id: id.to_string(),
            canonical_subject_id: subject.to_string(),
            display_title: title.to_string(),
        }
    }

    fn live_subject(id: &str, name: &str, weight: f64) -> LeverageSubject {
        LeverageSubject {
            canonical_subject_id: id.to_string(),
            name: name.to_string(),
            weight,
            archived_only: false,
        }
    }

    #[test]
    fn second_exam_inherits_canonical_mastery_and_archive_keeps_history() {
        let history = vec![
            CanonicalMasteryRecord {
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "FIN_PUB_01".to_string(),
                mastery: 0.8,
            },
            CanonicalMasteryRecord {
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "FIN_PUB_02".to_string(),
                mastery: 0.4,
            },
            CanonicalMasteryRecord {
                canonical_subject_id: "DIR_TRIB".to_string(),
                canonical_topic_id: "OLD_ONLY".to_string(),
                mastery: 0.55,
            },
        ];
        let links = vec![
            ExamTopicLink {
                exam_id: "RFB".to_string(),
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "FIN_PUB_01".to_string(),
                diff_status: CanonicalTopicStatus::OficialConfirmado,
            },
            ExamTopicLink {
                exam_id: "RFB".to_string(),
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "FIN_PUB_02".to_string(),
                diff_status: CanonicalTopicStatus::OficialConfirmado,
            },
            ExamTopicLink {
                exam_id: "SEFAZ".to_string(),
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "FIN_PUB_01".to_string(),
                diff_status: CanonicalTopicStatus::PreEditalBase,
            },
            ExamTopicLink {
                exam_id: "SEFAZ".to_string(),
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "FIN_PUB_02".to_string(),
                diff_status: CanonicalTopicStatus::PreEditalBase,
            },
            ExamTopicLink {
                exam_id: "RFB".to_string(),
                canonical_subject_id: "DIR_TRIB".to_string(),
                canonical_topic_id: "OLD_ONLY".to_string(),
                diff_status: CanonicalTopicStatus::Arquivado,
            },
            ExamTopicLink {
                exam_id: "SEFAZ".to_string(),
                canonical_subject_id: "DIR_TRIB".to_string(),
                canonical_topic_id: "OLD_ONLY".to_string(),
                diff_status: CanonicalTopicStatus::OficialNovo,
            },
        ];
        let projected = project_cross_exam_coverage(&history, &links);
        let receita = subject_mastery_percentage(&projected, "RFB", "FIN_PUB");
        let sefaz = subject_mastery_percentage(&projected, "SEFAZ", "FIN_PUB");
        assert!((receita - sefaz).abs() < 1e-12);
        assert!((sefaz - 0.6).abs() < 1e-12);

        let archived = projected
            .iter()
            .find(|item| item.exam_id == "RFB" && item.canonical_topic_id == "OLD_ONLY")
            .unwrap();
        assert!(archived.hidden_from_study_queue);
        assert!((archived.mastery - 0.55).abs() < 1e-12);
        let inherited = projected
            .iter()
            .find(|item| item.exam_id == "SEFAZ" && item.canonical_topic_id == "OLD_ONLY")
            .unwrap();
        assert!(!inherited.hidden_from_study_queue);
        assert!((inherited.mastery - 0.55).abs() < 1e-12);
        assert!(history
            .iter()
            .any(|item| item.canonical_topic_id == "OLD_ONLY"));
    }

    #[test]
    fn publish_classifies_confirmed_new_and_archived_without_clearing_coverage() {
        let previous = vec![
            topic_ref("FIN_PUB_01", "FIN_PUB", "Orçamento"),
            topic_ref("FIN_PUB_02", "FIN_PUB", "Receita"),
            topic_ref("OLD_ONLY", "DIR_TRIB", "Tópico que sai"),
        ];
        let published = vec![
            topic_ref("FIN_PUB_01", "FIN_PUB", "Orçamento"),
            topic_ref("FIN_PUB_02", "FIN_PUB", "Receita"),
            topic_ref("NEW_TOPIC", "FIN_PUB", "Tópico novo"),
        ];
        let practiced = HashSet::from([
            "FIN_PUB_01".to_string(),
            "NEW_TOPIC".to_string(),
            "OLD_ONLY".to_string(),
        ]);
        let result = publish_edital_diff(&previous, &published, &practiced);
        assert_eq!(result.confirmed_count, 2);
        assert_eq!(result.new_count, 1);
        assert_eq!(result.archived_count, 1);
        let confirmed = result
            .topics
            .iter()
            .find(|topic| topic.canonical_topic_id == "FIN_PUB_01")
            .unwrap();
        assert_eq!(
            confirmed.diff_status,
            CanonicalTopicStatus::OficialConfirmado
        );
        assert!(confirmed.coverage_already_filled);
        let new_topic = result
            .topics
            .iter()
            .find(|topic| topic.canonical_topic_id == "NEW_TOPIC")
            .unwrap();
        assert_eq!(new_topic.diff_status, CanonicalTopicStatus::OficialNovo);
        assert!(new_topic.coverage_already_filled);
        let archived = result
            .topics
            .iter()
            .find(|topic| topic.canonical_topic_id == "OLD_ONLY")
            .unwrap();
        assert_eq!(archived.diff_status, CanonicalTopicStatus::Arquivado);
        assert!(archived.coverage_already_filled);
    }

    #[test]
    fn common_trunk_card_picks_max_leverage_and_writes_the_sentence() {
        let today = NaiveDate::from_ymd_opt(2026, 10, 31).unwrap();
        let exams = vec![
            LeverageExam {
                exam_id: "CPNU".to_string(),
                short_name: "CPNU - AFT".to_string(),
                trunk_slug: "controle".to_string(),
                status: ExamLifecycleStatus::RumorPrevisto,
                exact_date_iso: Some("2026-11-30".to_string()),
                is_active_in_ladder: true,
                subjects: vec![
                    live_subject("FIN_PUB", "Finanças Públicas", 3.0),
                    live_subject("PORT", "Língua Portuguesa", 1.0),
                ],
            },
            LeverageExam {
                exam_id: "RFB".to_string(),
                short_name: "Receita Federal".to_string(),
                trunk_slug: "fiscal".to_string(),
                status: ExamLifecycleStatus::Autorizado,
                exact_date_iso: None,
                is_active_in_ladder: true,
                subjects: vec![
                    live_subject("FIN_PUB", "Finanças Públicas", 1.0),
                    live_subject("DIR_TRIB", "Direito Tributário", 1.0),
                ],
            },
        ];
        let mastery = HashMap::from([("FIN_PUB".to_string(), 0.25)]);
        let card = select_common_trunk_card(&exams, &mastery, None, 15, today).unwrap();
        assert_eq!(card.canonical_subject_id, "FIN_PUB");
        assert_eq!(
            card.sentence,
            "Estudar 15 questões de Finanças Públicas avança CPNU - AFT e Receita Federal ao mesmo tempo"
        );
        let expected = leverage_score(&exams, "FIN_PUB", 0.25, today);
        assert!((card.score - expected).abs() < 1e-9);
        assert!(card.score > leverage_score(&exams, "DIR_TRIB", 0.0, today));

        let focused = TrunkFocus {
            slug: "fiscal".to_string(),
            core_subject_ids: vec!["DIR_TRIB".to_string()],
        };
        let focused_card =
            select_common_trunk_card(&exams, &HashMap::new(), Some(&focused), 10, today).unwrap();
        assert_eq!(focused_card.canonical_subject_id, "DIR_TRIB");
        assert_eq!(
            focused_card.contest_names,
            vec!["Receita Federal".to_string()]
        );
    }

    #[test]
    fn urgency_is_two_inside_sixty_days_then_published_then_pre_edital() {
        let today = NaiveDate::from_ymd_opt(2026, 10, 31).unwrap();
        let mut exact = LeverageExam {
            exam_id: "A".to_string(),
            short_name: "A".to_string(),
            trunk_slug: "fiscal".to_string(),
            status: ExamLifecycleStatus::EditalPublicado,
            exact_date_iso: Some("2026-12-13".to_string()),
            is_active_in_ladder: true,
            subjects: Vec::new(),
        };
        assert_eq!(urgency_factor(&exact, today), 2.0);
        exact.exact_date_iso = Some("2027-06-01".to_string());
        assert_eq!(urgency_factor(&exact, today), 1.5);
        exact.status = ExamLifecycleStatus::RumorPrevisto;
        assert_eq!(urgency_factor(&exact, today), 1.0);
    }

    #[test]
    fn readiness_excludes_archived_topics_and_matches_the_weighted_formula() {
        let subjects = vec![
            ReadinessSubject {
                weight: 2.0,
                real_mastery: 0.8,
                topics: vec![
                    ReadinessTopic {
                        archived: false,
                        practiced: true,
                    },
                    ReadinessTopic {
                        archived: false,
                        practiced: true,
                    },
                ],
            },
            ReadinessSubject {
                weight: 1.0,
                real_mastery: 0.5,
                topics: vec![
                    ReadinessTopic {
                        archived: false,
                        practiced: true,
                    },
                    ReadinessTopic {
                        archived: false,
                        practiced: false,
                    },
                    ReadinessTopic {
                        archived: true,
                        practiced: true,
                    },
                ],
            },
            ReadinessSubject {
                weight: 4.0,
                real_mastery: 1.0,
                topics: vec![ReadinessTopic {
                    archived: true,
                    practiced: true,
                }],
            },
        ];
        let index = readiness_index(&subjects);
        assert!((index - 74.0).abs() < 1e-9);
    }

    #[test]
    fn weighted_simulado_skips_archived_topics_and_scores_by_weight() {
        let quotas = vec![
            SubjectQuota {
                canonical_subject_id: "FIN_PUB".to_string(),
                weight: 2.0,
                question_count: 2,
                allowed_topic_ids: vec!["FIN_PUB_01".into(), "FIN_PUB_02".into()],
            },
            SubjectQuota {
                canonical_subject_id: "DIR_TRIB".to_string(),
                weight: 1.0,
                question_count: 1,
                allowed_topic_ids: vec!["DIR_TRIB_01".into()],
            },
        ];
        let questions = vec![
            SimuladoCandidate {
                question_id: "q-archived".to_string(),
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "OLD".to_string(),
                archived: true,
            },
            SimuladoCandidate {
                question_id: "q2".to_string(),
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "FIN_PUB_02".to_string(),
                archived: false,
            },
            SimuladoCandidate {
                question_id: "q1".to_string(),
                canonical_subject_id: "FIN_PUB".to_string(),
                canonical_topic_id: "FIN_PUB_01".to_string(),
                archived: false,
            },
            SimuladoCandidate {
                question_id: "q4".to_string(),
                canonical_subject_id: "DIR_TRIB".to_string(),
                canonical_topic_id: "DIR_TRIB_01".to_string(),
                archived: false,
            },
        ];
        let drawn = draw_weighted_simulado(&quotas, &questions);
        assert_eq!(
            drawn
                .iter()
                .cloned()
                .collect::<std::collections::HashSet<_>>(),
            ["q1", "q2", "q4"].into_iter().map(str::to_string).collect()
        );
        assert!(!drawn.iter().any(|id| id == "q-archived"));
        let score = weighted_net_score(&[
            SubjectOutcome {
                local_subject_id: None,
                canonical_subject_id: String::new(),
                wrong: 0,
                penalty: 0.0,
                weight: 2.0,
                correct: 1,
                drawn: 2,
            },
            SubjectOutcome {
                local_subject_id: None,
                canonical_subject_id: String::new(),
                wrong: 0,
                penalty: 0.0,
                weight: 1.0,
                correct: 1,
                drawn: 1,
            },
        ]);
        assert!((score - 60.0).abs() < 1e-9); // 3 earned points out of 5 configured points.
    }

    #[test]
    fn deadline_label_renders_day_count_or_window_text() {
        let today = NaiveDate::from_ymd_opt(2026, 10, 31).unwrap();
        let exact = ExamDateTarget::ExactDate {
            iso_date: "2026-12-12".to_string(),
        };
        assert_eq!(format_deadline_label(&exact, today), "42 dias");
        let window = ExamDateTarget::EstimatedWindow {
            label: "1º sem/2027".to_string(),
            estimated_sort_date: "2027-06-30".to_string(),
        };
        assert_eq!(format_deadline_label(&window, today), "1º sem/2027");
    }

    #[test]
    fn client_id_round_trips_and_second_write_does_not_duplicate_the_key() {
        let mut store = HashMap::new();
        let id = assign_exam_id(Some("sefaz-sp".to_string()));
        let mut exam = system_exam_blueprints().into_iter().next().unwrap();
        exam.id = id.clone();
        exam.sync_status = "PENDING_CREATE".to_string();
        upsert_exam_by_client_id(&mut store, exam.clone());
        exam.short_name = "SEFAZ-SP".to_string();
        upsert_exam_by_client_id(&mut store, exam);
        assert_eq!(store.len(), 1);
        assert_eq!(store[&id].short_name, "SEFAZ-SP");
        let other = assign_exam_id(None);
        assert_ne!(other, id);
        assert!(!other.is_empty());
    }

    #[test]
    fn mastered_subject_does_not_beat_an_unmastered_one() {
        let today = NaiveDate::from_ymd_opt(2026, 10, 31).unwrap();
        let exams = vec![
            LeverageExam {
                exam_id: "A".to_string(),
                short_name: "CPNU - AFT".to_string(),
                trunk_slug: "controle".to_string(),
                status: ExamLifecycleStatus::Autorizado,
                exact_date_iso: None,
                is_active_in_ladder: true,
                subjects: vec![
                    live_subject("FIN_PUB", "Finanças Públicas", 5.0),
                    live_subject("PORT", "Língua Portuguesa", 1.0),
                ],
            },
            LeverageExam {
                exam_id: "B".to_string(),
                short_name: "Receita Federal".to_string(),
                trunk_slug: "fiscal".to_string(),
                status: ExamLifecycleStatus::Autorizado,
                exact_date_iso: None,
                is_active_in_ladder: true,
                subjects: vec![
                    live_subject("FIN_PUB", "Finanças Públicas", 5.0),
                    live_subject("PORT", "Língua Portuguesa", 1.0),
                ],
            },
        ];
        let rows = vec![CanonicalActivitySlice {
            canonical_subject_id: "FIN_PUB".to_string(),
            canonical_topic_id: "FIN_PUB_01".to_string(),
            accuracy_pct: 100.0,
            confidence_avg: Some(3),
            mastered: true,
            high_confidence_error: false,
        }];
        let card = card_from_canonical_activity(&exams, &rows, &[], 15, today).unwrap();
        assert_eq!(card.canonical_subject_id, "PORT");
        let mastery = mastery_by_canonical_subject(&rows);
        assert!((mastery["FIN_PUB"] - 1.0).abs() < 1e-12);
        assert!(card.score > 0.0);
    }

    #[test]
    fn readiness_for_the_primary_exam_drops_archived_topics() {
        let mut exam = system_exam_blueprints().into_iter().next().unwrap();
        exam.ladder_priority = 1;
        exam.is_active_in_ladder = true;
        exam.subjects = vec![ExamSubjectConfig {
            stage: String::new(),
            local_subject_id: "fin".to_string(),
            canonical_subject_id: "FIN_PUB".to_string(),
            display_name: "Finanças Públicas".to_string(),
            category: SubjectCategory::ConhecimentosEspecificos,
            weight: 2.0,
            question_count: Some(10),
            relevance_tier: RelevanceTier::Core,
            topics: vec![
                ExamTopicItem {
                    local_topic_id: "a".to_string(),
                    canonical_topic_id: "FIN_PUB_01".to_string(),
                    canonical_subject_id: "FIN_PUB".to_string(),
                    display_title: "Orçamento".to_string(),
                    diff_status: CanonicalTopicStatus::OficialConfirmado,
                },
                ExamTopicItem {
                    local_topic_id: "b".to_string(),
                    canonical_topic_id: "OLD".to_string(),
                    canonical_subject_id: "FIN_PUB".to_string(),
                    display_title: "Arquivado".to_string(),
                    diff_status: CanonicalTopicStatus::Arquivado,
                },
            ],
        }];
        let mastery = HashMap::from([("FIN_PUB_01".to_string(), 0.8), ("OLD".to_string(), 0.0)]);
        let score = readiness_for_blueprints(&[exam], &mastery);
        assert!((score - 86.0).abs() < 1e-9);
    }

    #[test]
    fn two_focused_trunks_keep_a_core_subject_from_either() {
        let today = NaiveDate::from_ymd_opt(2026, 10, 31).unwrap();
        let exams = vec![
            LeverageExam {
                exam_id: "RFB".to_string(),
                short_name: "Receita Federal".to_string(),
                trunk_slug: "fiscal".to_string(),
                status: ExamLifecycleStatus::Autorizado,
                exact_date_iso: None,
                is_active_in_ladder: true,
                subjects: vec![live_subject("DIR_TRIB", "Direito Tributário", 1.0)],
            },
            LeverageExam {
                exam_id: "PF".to_string(),
                short_name: "Polícia Federal".to_string(),
                trunk_slug: "policial".to_string(),
                status: ExamLifecycleStatus::RumorPrevisto,
                exact_date_iso: None,
                is_active_in_ladder: true,
                subjects: vec![live_subject("DIR_PEN", "Direito Penal", 1.0)],
            },
        ];
        let focuses = vec![
            TrunkFocus {
                slug: "fiscal".to_string(),
                core_subject_ids: vec!["DIR_TRIB".to_string()],
            },
            TrunkFocus {
                slug: "policial".to_string(),
                core_subject_ids: vec!["DIR_PEN".to_string()],
            },
        ];
        let card =
            select_common_trunk_for_focuses(&exams, &HashMap::new(), &focuses, 8, today).unwrap();
        assert!(card.canonical_subject_id == "DIR_PEN" || card.canonical_subject_id == "DIR_TRIB");
        assert_eq!(card.contest_names.len(), 1);
    }

    #[test]
    fn empty_topics_stay_live_and_deleted_or_archived_exams_leave_the_trunk_pool() {
        assert!(!subject_archived_only(true, true));
        assert!(subject_archived_only(false, true));
        assert!(!subject_archived_only(false, false));
        let live = |id: &str, subject: &str| LeverageExam {
            exam_id: id.to_string(),
            short_name: id.to_string(),
            trunk_slug: "fiscal".to_string(),
            status: ExamLifecycleStatus::RumorPrevisto,
            exact_date_iso: None,
            is_active_in_ladder: true,
            subjects: vec![LeverageSubject {
                canonical_subject_id: subject.to_string(),
                name: subject.to_string(),
                weight: 1.0,
                archived_only: false,
            }],
        };
        let mut excluded = live("gone", "ONLY_EXCLUDED");
        excluded.status = ExamLifecycleStatus::Excluido;
        let mut archived = live("old", "ONLY_ARCHIVED");
        archived.status = ExamLifecycleStatus::Arquivado;
        let today = NaiveDate::from_ymd_opt(2026, 11, 4).unwrap();
        let card = select_common_trunk_for_focuses(
            &[live("a", "FIN"), live("b", "FIN"), excluded, archived],
            &HashMap::new(),
            &[],
            8,
            today,
        )
        .unwrap();
        assert_eq!(card.canonical_subject_id, "FIN");
        assert!(card.contest_names.iter().all(|name| name == "a" || name == "b"));
    }

    #[test]
    fn system_catalog_maps_the_six_trunks_and_the_three_current_contests() {
        let catalog = system_catalog();
        let slugs: HashSet<_> = catalog
            .trunks
            .iter()
            .map(|trunk| trunk.slug.as_str())
            .collect();
        for slug in [
            "fiscal",
            "policial",
            "controle",
            "engenharia",
            "tribunais",
            "bancaria",
        ] {
            assert!(slugs.contains(slug), "{slug}");
        }
        let names: HashSet<_> = catalog
            .exams
            .iter()
            .map(|exam| exam.short_name.as_str())
            .collect();
        assert!(names.contains("CPNU - AFT"));
        assert!(names.contains("Receita Federal"));
        assert!(names.contains("BNDES"));
        let cpnu = catalog.exams.iter().find(|exam| exam.id == "CPNU").unwrap();
        assert!(cpnu
            .subjects
            .iter()
            .any(|subject| subject.canonical_subject_id == "FIN_PUB"));
        assert!(cpnu.subjects.iter().all(|subject| subject
            .topics
            .iter()
            .all(|topic| !topic.canonical_topic_id.is_empty())));
    }
}
