use crate::{
    error::AppError,
    models::exam_hierarchy::*,
    services::exam_engine::{canonical_subjects, system_exam_blueprints},
    AppState,
};
use bson::{doc, Document};
use futures::TryStreamExt;
use mongodb::options::{CollectionOptions, WriteConcern};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};

fn collection(state: &AppState) -> mongodb::Collection<Document> {
    state.db.collection_with_options(
        "user_exams",
        CollectionOptions::builder()
            .write_concern(WriteConcern::majority())
            .build(),
    )
}
fn decode(mut raw: Document) -> Result<ExamBlueprint, AppError> {
    let id = raw
        .get_str("exam_id")
        .map_err(|_| {
            AppError::Conflict("Concurso legado sem identidade/proprietário verificável".into())
        })?
        .to_string();
    raw.insert("_id", id);
    bson::from_document(raw)
        .map_err(|_| AppError::Conflict("Formato do concurso exige revisão".into()))
}
pub async fn list(state: &AppState, user_id: i64) -> Result<Vec<ExamBlueprint>, AppError> {
    let profile = crate::routes::auth::profile(state, user_id).await?;
    let mut exams: HashMap<_, _> = system_exam_blueprints()
        .into_iter()
        .map(|mut e| {
            e.is_active_in_ladder = profile.active_contest_ids.contains(&e.id)
                && !matches!(e.status, ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido);
            (e.id.clone(), e)
        })
        .collect();
    let mut cursor = collection(state)
        .find(doc! {"owner_user_id":user_id.to_string()})
        .await?;
    while let Some(raw) = cursor.try_next().await? {
        let mut exam = decode(raw)?;
        exam.is_active_in_ladder = profile.active_contest_ids.contains(&exam.id)
            && !matches!(exam.status, ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido);
        exams.insert(exam.id.clone(), exam);
    }
    let mut result: Vec<_> = exams.into_values().collect();
    result.sort_by(|a, b| {
        a.ladder_priority
            .cmp(&b.ladder_priority)
            .then(a.id.cmp(&b.id))
    });
    Ok(result)
}
pub async fn get(state: &AppState, user_id: i64, id: &str) -> Result<ExamBlueprint, AppError> {
    list(state, user_id)
        .await?
        .into_iter()
        .find(|e| e.id == id)
        .ok_or_else(|| AppError::NotFound("Concurso não encontrado nesta conta".into()))
}
pub fn validate(exam: &ExamBlueprint) -> Result<(), AppError> {
    let invalid = |reason: &str| AppError::BadRequest(reason.into());
    if exam.revision < 0 || exam.revision == i64::MAX {
        return Err(invalid("Revisão inválida"));
    }
    if exam.id.is_empty()
        || exam.id.len() > 100
        || !exam
            .id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return Err(invalid("Identificador de concurso inválido"));
    }
    if exam.title.trim().is_empty()
        || exam.title.len() > 300
        || exam.short_name.trim().is_empty()
        || !(1..=100).contains(&exam.ladder_priority)
        || !exam.historical_cutoff_score.is_finite()
        || !(0.0..=100.0).contains(&exam.historical_cutoff_score)
        || !exam.wrong_answer_penalty.is_finite()
        || !(0.0..=1.0).contains(&exam.wrong_answer_penalty)
        || exam.subjects.len() > 100
        || !crate::services::exam_engine::system_trunks()
            .iter()
            .any(|t| t.slug == exam.trunk_slug)
    {
        return Err(invalid(
            "Revise nome, tronco, prioridade, corte e disciplinas",
        ));
    }
    let date_valid = |date: &str| {
        date.len() == 10 && chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d").is_ok()
    };
    match &exam.target_date {
        ExamDateTarget::ExactDate { iso_date } if !date_valid(iso_date) => {
            return Err(invalid("Data exata inválida"))
        }
        ExamDateTarget::EstimatedWindow {
            label,
            estimated_sort_date,
        } if label.trim().is_empty() || label.len() > 120 || !date_valid(estimated_sort_date) => {
            return Err(invalid("Janela estimada inválida"))
        }
        _ => {}
    }
    if exam
        .subjects
        .iter()
        .map(|s| u64::from(s.question_count.unwrap_or(0)))
        .sum::<u64>()
        > 500
    {
        return Err(invalid("A grade aceita no máximo 500 questões"));
    }
    let catalog = canonical_subjects();
    let mut subject_ids = HashSet::new();
    let mut local_subject_ids = HashSet::new();
    let mut local_topic_ids = HashSet::new();
    let mut topic_ids = HashSet::new();
    for subject in &exam.subjects {
        let known = catalog.iter().find(|s| s.canonical_subject_id == subject.canonical_subject_id);
        let custom = subject.canonical_subject_id.strip_prefix("custom-").is_some_and(|id| uuid::Uuid::parse_str(id).is_ok());
        if known.is_none() && !custom { return Err(invalid("Disciplina fora do catálogo ou sem identidade personalizada válida")); }
        if subject.display_name.trim().is_empty() || subject.display_name.len()>300 || subject.stage.len()>120 {
            return Err(invalid("Nome ou etapa da disciplina inválido"));
        }
        if !subject_ids.insert(&subject.canonical_subject_id)
            || subject.local_subject_id.trim().is_empty() || !local_subject_ids.insert(&subject.local_subject_id)
            || !subject.weight.is_finite()
            || !(0.01..=100.0).contains(&subject.weight)
            || subject.question_count.is_some_and(|n| n > 500)
            || subject.topics.len() > 1000
        {
            return Err(invalid(
                "Disciplina repetida, peso/quantidade inválido ou grade vazia",
            ));
        }
        for topic in &subject.topics {
            if topic.local_topic_id.trim().is_empty() || !local_topic_ids.insert(&topic.local_topic_id)
                || topic.canonical_subject_id != subject.canonical_subject_id
                || !topic_ids.insert(&topic.canonical_topic_id)
                || topic.display_title.trim().is_empty() || topic.display_title.len()>1000
                || !(known.is_some_and(|k| k.topics.iter().any(|t| t.topic_id == topic.canonical_topic_id))
                    || topic.canonical_topic_id.strip_prefix("custom-").is_some_and(|id| uuid::Uuid::parse_str(id).is_ok()))
            {
                return Err(invalid("Tópico inválido, repetido ou fora da disciplina"));
            }
        }
    }
    Ok(())
}
pub async fn save(
    state: &AppState,
    user_id: i64,
    exam: ExamBlueprint,
    create: bool,
) -> Result<ExamBlueprint, AppError> {
    save_command(state, user_id, exam, create, None).await
}
pub fn command_hash(user_id: i64, exam: &ExamBlueprint) -> String {
    let mut command = exam.clone();
    command.owner_user_id = Some(user_id.to_string());
    command.sync_status.clear();
    format!(
        "{:x}",
        Sha256::digest(serde_json::to_vec(&command).expect("serializable blueprint"))
    )
}
pub async fn replay(
    state: &AppState,
    user_id: i64,
    id: &str,
    hash: &str,
) -> Result<Option<ExamBlueprint>, AppError> {
    let raw=collection(state).find_one(doc!{"_id":format!("{user_id}:{id}"),"owner_user_id":user_id.to_string(),"last_command_hash":hash}).await?;
    raw.map(decode).transpose()
}
pub async fn save_command(
    state: &AppState,
    user_id: i64,
    mut exam: ExamBlueprint,
    create: bool,
    original_hash: Option<String>,
) -> Result<ExamBlueprint, AppError> {
    validate(&exam)?;
    if create && system_exam_blueprints().iter().any(|t| t.id == exam.id) {
        return Err(AppError::Conflict(
            "Use a edição da sua cópia do template".into(),
        ));
    }
    exam.owner_user_id = Some(user_id.to_string());
    let key = format!("{user_id}:{}", exam.id);
    let hash = original_hash.unwrap_or_else(|| command_hash(user_id, &exam));
    let coll = collection(state);
    let existing = coll
        .find_one(doc! {"_id":&key,"owner_user_id":user_id.to_string()})
        .await?;
    if let Some(raw) = &existing {
        if raw.get_str("last_command_hash").ok() == Some(&hash) {
            return decode(raw.clone());
        }
        let current = decode(raw.clone())?;
        if create || exam.revision != current.revision {
            return Err(AppError::Conflict(
                "O concurso mudou em outro aparelho; sincronize e revise antes de salvar".into(),
            ));
        }
        // Removed grade items stay available in revision_history, never reappear in the current grade.
    } else if !create && exam.revision != 0 {
        return Err(AppError::Conflict(
            "A revisão do concurso não está disponível".into(),
        ));
    }
    let expected = exam.revision;
    exam.revision = expected + 1;
    exam.sync_status = "SYNCED".into();
    let mut raw = bson::to_document(&exam)
        .map_err(|_| AppError::Internal("Concurso não serializável".into()))?;
    raw.insert("_id", &key);
    raw.insert("exam_id", &exam.id);
    raw.insert("last_command_hash", hash);
    if existing.is_none() {
        if let Err(e) = coll.insert_one(raw).await {
            if e.to_string().contains("E11000") {
                return Err(AppError::Conflict(
                    "Gravação concorrente; sincronize para conferir o resultado".into(),
                ));
            }
            return Err(e.into());
        }
    } else {
        raw.remove("_id");
        let previous = bson::to_document(&decode(existing.clone().unwrap())?)
            .map_err(|_| AppError::Internal("Revisão não serializável".into()))?;
        let result = coll
            .update_one(
                doc! {"_id":&key,"owner_user_id":user_id.to_string(),"revision":expected},
                doc! {"$set":raw,"$push":{"revision_history":previous}},
            )
            .await?;
        if result.matched_count != 1 {
            return Err(AppError::Conflict(
                "O concurso mudou durante a gravação".into(),
            ));
        }
    }
    Ok(exam)
}

#[cfg(test)]
mod validation_tests {
    use super::*;
    #[test]
    fn invalid_revision_quantity_weight_and_utf8_date_fail_without_panicking() {
        let template = system_exam_blueprints().remove(0);
        assert!(validate(&template).is_ok());
        for revision in [-1, i64::MAX] {
            let mut e = template.clone();
            e.revision = revision;
            assert!(validate(&e).is_err());
        }
        let mut e = template.clone();
        for subject in &mut e.subjects {
            subject.question_count = Some(u32::MAX);
        }
        assert!(validate(&e).is_err());
        let mut e = template.clone();
        e.subjects[0].weight = 0.0;
        assert!(validate(&e).is_err());
        let mut e = template;
        e.target_date = ExamDateTarget::ExactDate {
            iso_date: "2026-01-1ç".into(),
        };
        assert!(validate(&e).is_err());
        assert_eq!(
            crate::services::exam_engine::days_until("2026-01-1ç", chrono::Utc::now().date_naive()),
            None
        );
    }
}
