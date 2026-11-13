use crate::{
    error::AppError,
    models::exam_hierarchy::*,
    services::exam_engine::system_exam_blueprints,
    AppState,
};
use bson::{doc, Document};
use futures::TryStreamExt;
use mongodb::{
    options::{CollectionOptions, WriteConcern},
    Database,
};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
#[cfg(test)]
use crate::services::exam_engine::canonical_subjects;

fn user_collection(state: &AppState) -> mongodb::Collection<Document> {
    state.db.collection_with_options(
        "user_exams",
        CollectionOptions::builder()
            .write_concern(WriteConcern::majority())
            .build(),
    )
}

fn templates_collection(db: &Database) -> mongodb::Collection<Document> {
    db.collection_with_options(
        "exam_templates",
        CollectionOptions::builder()
            .write_concern(WriteConcern::majority())
            .build(),
    )
}

fn revisions_collection(db: &Database) -> mongodb::Collection<Document> {
    db.collection_with_options(
        "exam_revisions",
        CollectionOptions::builder()
            .write_concern(WriteConcern::majority())
            .build(),
    )
}

fn decode(mut raw: Document) -> Result<ExamBlueprint, AppError> {
    let id = raw
        .get_str("exam_id")
        .or_else(|_| raw.get_str("_id"))
        .map_err(|_| {
            AppError::Conflict("Concurso sem identificador verificável".into())
        })?
        .to_string();
    raw.insert("_id", id);
    bson::from_document(raw)
        .map_err(|_| AppError::Conflict("Formato do concurso exige revisão".into()))
}

pub async fn seed_templates(db: &Database) -> Result<(), AppError> {
    let coll = templates_collection(db);
    for mut template in system_exam_blueprints() {
        if template.revision < 1 {
            template.revision = 1;
        }
        template.owner_user_id = None;
        template.sync_status = "SYNCED".into();
        let mut raw = bson::to_document(&template)
            .map_err(|_| AppError::Internal("Template global não serializável".into()))?;
        raw.insert("_id", &template.id);
        raw.insert("exam_id", &template.id);
        coll.update_one(
            doc! { "_id": &template.id },
            doc! { "$setOnInsert": raw },
        )
        .upsert(true)
        .await?;
    }
    Ok(())
}

pub async fn list_templates(state: &AppState) -> Result<Vec<ExamBlueprint>, AppError> {
    let coll = templates_collection(&state.db);
    let mut by_id: HashMap<String, ExamBlueprint> = system_exam_blueprints()
        .into_iter()
        .map(|mut e| {
            if e.revision < 1 {
                e.revision = 1;
            }
            (e.id.clone(), e)
        })
        .collect();

    let mut cursor = coll.find(doc! {}).await?;
    while let Some(raw) = cursor.try_next().await? {
        let exam = decode(raw)?;
        by_id.insert(exam.id.clone(), exam);
    }
    let mut result: Vec<_> = by_id
        .into_values()
        .filter(|e| e.status != ExamLifecycleStatus::Excluido)
        .collect();
    result.sort_by(|a, b| a.ladder_priority.cmp(&b.ladder_priority).then(a.id.cmp(&b.id)));
    Ok(result)
}

pub async fn get_template(state: &AppState, id: &str) -> Result<ExamBlueprint, AppError> {
    if let Some(raw) = templates_collection(&state.db)
        .find_one(doc! { "_id": id })
        .await?
    {
        return decode(raw);
    }
    system_exam_blueprints()
        .into_iter()
        .find(|e| e.id == id)
        .ok_or_else(|| AppError::NotFound("Template global não encontrado".into()))
}

pub async fn save_template(
    state: &AppState,
    actor_user_id: i64,
    mut template: ExamBlueprint,
    create: bool,
) -> Result<ExamBlueprint, AppError> {
    validate(&template)?;
    template.owner_user_id = None;
    let coll = templates_collection(&state.db);
    let existing = coll.find_one(doc! { "_id": &template.id }).await?;
    let prior = match &existing {
        Some(raw) => Some(decode(raw.clone())?),
        None => system_exam_blueprints()
            .into_iter()
            .find(|e| e.id == template.id),
    };
    if create && prior.is_some() {
        return Err(AppError::Conflict(
            "Já existe um template global com este identificador".into(),
        ));
    }
    if let Some(current) = &prior {
        let current_rev = current.revision.max(1);
        if !create && template.revision != current_rev && !(existing.is_none() && template.revision == 0) {
            return Err(AppError::Conflict(
                "O template global mudou em outra aba; recarregue antes de salvar".into(),
            ));
        }
        validate_active_links(&template, Some(current))?;
        let expected = template.revision;
        template.revision = current_rev + 1;
        template.sync_status = "SYNCED".into();
        template.upstream_update_available = false;
        template.is_custom_copy = false;
        template.base_template_revision = None;

        let mut raw = bson::to_document(&template)
            .map_err(|_| AppError::Internal("Template não serializável".into()))?;
        raw.insert("_id", &template.id);
        raw.insert("exam_id", &template.id);

        if existing.is_none() {
            coll.insert_one(raw).await?;
        } else {
            raw.remove("_id");
            let res = coll
                .update_one(
                    doc! { "_id": &template.id, "revision": expected },
                    doc! { "$set": raw },
                )
                .await?;
            if res.matched_count != 1 {
                return Err(AppError::Conflict(
                    "Template global alterado concorrentemente".into(),
                ));
            }
        }
        let snapshot = bson::to_document(current)
            .map_err(|_| AppError::Internal("Revisão de template inválida".into()))?;
        let _ = revisions_collection(&state.db)
            .insert_one(doc! {
                "scope": "template",
                "exam_id": &template.id,
                "revision": current_rev,
                "actor_user_id": actor_user_id,
                "recorded_at": bson::DateTime::now(),
                "snapshot": snapshot
            })
            .await;
    } else {
        validate_active_links(&template, None)?;
        template.revision = 1;
        template.sync_status = "SYNCED".into();
        template.upstream_update_available = false;
        template.is_custom_copy = false;
        template.base_template_revision = None;
        let mut raw = bson::to_document(&template)
            .map_err(|_| AppError::Internal("Template não serializável".into()))?;
        raw.insert("_id", &template.id);
        raw.insert("exam_id", &template.id);
        coll.insert_one(raw).await?;
    }
    Ok(template)
}

pub async fn list(state: &AppState, user_id: i64) -> Result<Vec<ExamBlueprint>, AppError> {
    let profile = crate::routes::auth::profile(state, user_id).await?;
    let templates = list_templates(state).await?;
    let template_revs: HashMap<String, i64> = templates
        .iter()
        .map(|t| (t.id.clone(), t.revision.max(1)))
        .collect();

    let mut exams: HashMap<String, ExamBlueprint> = templates
        .into_iter()
        .map(|mut e| {
            e.is_active_in_ladder = profile.active_contest_ids.contains(&e.id)
                && !matches!(
                    e.status,
                    ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido
                );
            e.base_template_revision = Some(e.revision.max(1));
            e.upstream_update_available = false;
            e.is_custom_copy = false;
            (e.id.clone(), e)
        })
        .collect();

    let mut cursor = user_collection(state)
        .find(doc! { "owner_user_id": user_id.to_string() })
        .await?;
    while let Some(raw) = cursor.try_next().await? {
        let mut exam = decode(raw)?;
        if exam.status == ExamLifecycleStatus::Excluido {
            exams.remove(&exam.id);
            continue;
        }
        exam.is_active_in_ladder = profile.active_contest_ids.contains(&exam.id)
            && !matches!(
                exam.status,
                ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido
            );
        exam.is_custom_copy = true;
        if let Some(&global_rev) = template_revs.get(&exam.id) {
            let base_rev = exam.base_template_revision.unwrap_or(1);
            exam.upstream_update_available = base_rev < global_rev;
        } else {
            exam.upstream_update_available = false;
        }
        exams.insert(exam.id.clone(), exam);
    }

    let mut result: Vec<_> = exams
        .into_values()
        .filter(|e| e.status != ExamLifecycleStatus::Excluido)
        .collect();
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
        || !crate::services::question_contracts::catalog_ref()["trunks"]
            .as_array()
            .unwrap()
            .iter()
            .any(|t| t["trunk_id"] == exam.trunk_slug)
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
    let mut local_subject_ids = HashSet::new();
    let mut local_topic_ids = HashSet::new();
    for subject in &exam.subjects {
        let mut topic_ids = HashSet::new();
        let custom = subject
            .canonical_subject_id
            .strip_prefix("custom-")
            .is_some_and(|id| uuid::Uuid::parse_str(id).is_ok());
        if !crate::services::catalog_runtime::registered(&subject.canonical_subject_id, None)
            && !custom
        {
            return Err(invalid(
                "Disciplina fora do catálogo ou sem identidade personalizada válida",
            ));
        }
        if subject.display_name.trim().is_empty()
            || subject.display_name.len() > 300
            || subject.stage.len() > 120
        {
            return Err(invalid("Nome ou etapa da disciplina inválido"));
        }
        if subject.local_subject_id.trim().is_empty()
            || !local_subject_ids.insert(&subject.local_subject_id)
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
            if topic.local_topic_id.trim().is_empty()
                || !local_topic_ids.insert(&topic.local_topic_id)
                || topic.canonical_subject_id != subject.canonical_subject_id
                || !topic_ids.insert(&topic.canonical_topic_id)
                || topic.display_title.trim().is_empty()
                || topic.display_title.len() > 1000
                || !(crate::services::catalog_runtime::registered(
                    &subject.canonical_subject_id,
                    Some(&topic.canonical_topic_id),
                ) || topic
                    .canonical_topic_id
                    .strip_prefix("custom-")
                    .is_some_and(|id| uuid::Uuid::parse_str(id).is_ok()))
            {
                return Err(invalid("Tópico inválido, repetido ou fora da disciplina"));
            }
        }
    }
    Ok(())
}

fn validate_active_links(
    exam: &ExamBlueprint,
    previous: Option<&ExamBlueprint>,
) -> Result<(), AppError> {
    let knowledge = crate::services::question_contracts::catalog_ref();
    let active_trunk = knowledge["trunks"]
        .as_array()
        .unwrap()
        .iter()
        .any(|t| t["trunk_id"] == exam.trunk_slug && t["status"] == "ACTIVE");
    if !active_trunk && !previous.is_some_and(|e| e.trunk_slug == exam.trunk_slug) {
        return Err(AppError::BadRequest(
            "Novos vínculos exigem um tronco ativo".into(),
        ));
    }
    for subject in &exam.subjects {
        let prior = previous.and_then(|e| {
            e.subjects.iter().find(|s| {
                s.local_subject_id == subject.local_subject_id
                    && s.canonical_subject_id == subject.canonical_subject_id
            })
        });
        let active = knowledge["subjects"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| s["subject_id"] == subject.canonical_subject_id && s["status"] == "ACTIVE");
        if !active && !subject.canonical_subject_id.starts_with("custom-") && prior.is_none() {
            return Err(AppError::BadRequest(
                "Disciplina desativada só pode permanecer nos componentes existentes".into(),
            ));
        }
        for topic in &subject.topics {
            if !topic.canonical_topic_id.starts_with("custom-")
                && !crate::services::question_contracts::known_topic(
                    &subject.canonical_subject_id,
                    &topic.canonical_topic_id,
                )
                && !prior.is_some_and(|s| {
                    s.topics.iter().any(|t| {
                        t.local_topic_id == topic.local_topic_id
                            && t.canonical_topic_id == topic.canonical_topic_id
                    })
                })
            {
                return Err(AppError::BadRequest(
                    "Assunto desativado só pode permanecer nos vínculos existentes".into(),
                ));
            }
        }
    }
    Ok(())
}

async fn sync_user_ladder_membership(
    db: &Database,
    user_id: i64,
    exam_id: &str,
    active_in_ladder: bool,
    status: &ExamLifecycleStatus,
) -> Result<(), AppError> {
    let should_be_active = active_in_ladder
        && !matches!(
            status,
            ExamLifecycleStatus::Arquivado | ExamLifecycleStatus::Excluido
        );
    let update = if should_be_active {
        doc! { "$addToSet": { "active_contest_ids": exam_id } }
    } else {
        doc! { "$pull": { "active_contest_ids": exam_id } }
    };
    db.collection::<Document>("usuarios")
        .update_one(doc! { "user_id": user_id }, update)
        .await?;
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
    command.upstream_update_available = false;
    command.is_custom_copy = false;
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
    let raw = user_collection(state)
        .find_one(doc! {
            "_id": format!("{user_id}:{id}"),
            "owner_user_id": user_id.to_string(),
            "last_command_hash": hash
        })
        .await?;
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
    let global_template = get_template(state, &exam.id).await.ok();
    if create && global_template.is_some() {
        return Err(AppError::Conflict(
            "Use a edição da sua cópia do template".into(),
        ));
    }
    exam.assign_session_owner(user_id);
    let key = format!("{user_id}:{}", exam.id);
    let hash = original_hash.unwrap_or_else(|| command_hash(user_id, &exam));
    let coll = user_collection(state);
    let existing = coll
        .find_one(doc! { "_id": &key, "owner_user_id": user_id.to_string() })
        .await?;

    if let Some(raw) = &existing {
        if raw.get_str("last_command_hash").ok() == Some(&hash) {
            let decoded = decode(raw.clone())?;
            sync_user_ladder_membership(
                &state.db,
                user_id,
                &decoded.id,
                decoded.is_active_in_ladder,
                &decoded.status,
            )
            .await?;
            return Ok(decoded);
        }
        let current = decode(raw.clone())?;
        if create || exam.revision != current.revision {
            return Err(AppError::Conflict(
                "O concurso mudou em outro aparelho; sincronize e revise antes de salvar".into(),
            ));
        }
    } else if !create {
        // First customization of a global template! Accept revision == 0 or revision == global_template.revision.
        let allowed_initial = exam.revision == 0
            || global_template
                .as_ref()
                .is_some_and(|t| exam.revision == t.revision);
        if !allowed_initial {
            return Err(AppError::Conflict(
                "A revisão do concurso não está disponível".into(),
            ));
        }
    }

    let previous = match &existing {
        Some(raw) => Some(decode(raw.clone())?),
        None => global_template.clone(),
    };
    validate_active_links(&exam, previous.as_ref())?;

    let expected = exam.revision;
    exam.revision = if existing.is_none() {
        1
    } else {
        expected + 1
    };
    if exam.base_template_revision.is_none() {
        exam.base_template_revision = match &existing {
            Some(raw) => decode(raw.clone())?.base_template_revision,
            None => global_template.as_ref().map(|t| t.revision.max(1)),
        };
    }
    exam.is_custom_copy = true;
    exam.upstream_update_available = global_template
        .as_ref()
        .zip(exam.base_template_revision)
        .is_some_and(|(t, base)| base < t.revision.max(1));
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
        let prior_doc = decode(existing.clone().unwrap())?;
        let result = coll
            .update_one(
                doc! { "_id": &key, "owner_user_id": user_id.to_string(), "revision": expected },
                doc! { "$set": raw },
            )
            .await?;
        if result.matched_count != 1 {
            return Err(AppError::Conflict(
                "O concurso mudou durante a gravação".into(),
            ));
        }
        if let Ok(snapshot) = bson::to_document(&prior_doc) {
            let _ = revisions_collection(&state.db)
                .insert_one(doc! {
                    "scope": "user",
                    "owner_user_id": user_id.to_string(),
                    "exam_id": &exam.id,
                    "revision": expected,
                    "recorded_at": bson::DateTime::now(),
                    "snapshot": snapshot
                })
                .await;
        }
    }

    sync_user_ladder_membership(
        &state.db,
        user_id,
        &exam.id,
        exam.is_active_in_ladder,
        &exam.status,
    )
    .await?;

    Ok(exam)
}

pub async fn adopt_template(
    state: &AppState,
    user_id: i64,
    exam_id: &str,
) -> Result<ExamBlueprint, AppError> {
    let global = get_template(state, exam_id).await?;
    let key = format!("{user_id}:{exam_id}");
    let coll = user_collection(state);
    let Some(existing) = coll
        .find_one(doc! { "_id": &key, "owner_user_id": user_id.to_string() })
        .await?
    else {
        return get(state, user_id, exam_id).await;
    };
    let current = decode(existing)?;
    let mut adopted = global.clone();
    adopted.assign_session_owner(user_id);
    adopted.revision = current.revision + 1;
    adopted.is_active_in_ladder = current.is_active_in_ladder;
    adopted.ladder_priority = current.ladder_priority;
    adopted.base_template_revision = Some(global.revision.max(1));
    adopted.upstream_update_available = false;
    adopted.is_custom_copy = true;
    adopted.sync_status = "SYNCED".into();

    let mut raw = bson::to_document(&adopted)
        .map_err(|_| AppError::Internal("Concurso não serializável".into()))?;
    raw.remove("_id");
    raw.insert("exam_id", exam_id);
    raw.insert("last_command_hash", command_hash(user_id, &adopted));

    let res = coll
        .update_one(
            doc! { "_id": &key, "owner_user_id": user_id.to_string(), "revision": current.revision },
            doc! { "$set": raw },
        )
        .await?;
    if res.matched_count != 1 {
        return Err(AppError::Conflict(
            "O concurso mudou durante a adoção da nova revisão do template".into(),
        ));
    }
    if let Ok(snapshot) = bson::to_document(&current) {
        let _ = revisions_collection(&state.db)
            .insert_one(doc! {
                "scope": "user",
                "owner_user_id": user_id.to_string(),
                "exam_id": exam_id,
                "revision": current.revision,
                "recorded_at": bson::DateTime::now(),
                "snapshot": snapshot
            })
            .await;
    }
    Ok(adopted)
}

#[cfg(test)]
mod validation_tests {
    use super::*;
    #[test]
    fn shared_discipline_in_distinct_components_and_pending_custom_are_valid() {
        let mut exam = system_exam_blueprints().remove(0);
        let mut second = exam.subjects[0].clone();
        second.local_subject_id = "second-stage".into();
        second.stage = "2ª etapa".into();
        for t in &mut second.topics {
            t.local_topic_id = format!("second-{}", t.local_topic_id);
        }
        exam.subjects.push(second);
        assert!(validate(&exam).is_ok());
        let last = exam.subjects.len() - 1;
        exam.subjects[last].local_subject_id = exam.subjects[0].local_subject_id.clone();
        assert!(validate(&exam).is_err());
        exam.subjects[last].local_subject_id = "second-stage".into();
        let catalog = canonical_subjects();
        let full = catalog.iter().find(|s| s.topics.len() > 2).unwrap();
        let topic = &full.topics[2];
        exam.subjects[last].canonical_subject_id = full.canonical_subject_id.clone();
        exam.subjects[last].topics = vec![ExamTopicItem {
            local_topic_id: "new-leaf".into(),
            canonical_topic_id: topic.topic_id.clone(),
            canonical_subject_id: full.canonical_subject_id.clone(),
            display_title: topic.title.clone(),
            diff_status: CanonicalTopicStatus::PreEditalBase,
        }];
        assert!(validate(&exam).is_ok());
        exam.subjects[last].topics[0].canonical_subject_id = "INVALID".into();
        assert!(validate(&exam).is_err());
        let custom = format!("custom-{}", uuid::Uuid::new_v4());
        exam.subjects[last].canonical_subject_id = custom.clone();
        exam.subjects[last].topics[0].canonical_subject_id = custom;
        exam.subjects[last].topics[0].canonical_topic_id =
            format!("custom-{}", uuid::Uuid::new_v4());
        exam.trunk_slug = "fiscalizacao_trabalho".into();
        assert!(validate(&exam).is_ok());
    }
    #[test]
    fn every_system_exam_and_its_deletion_tombstone_pass_validation() {
        for exam in system_exam_blueprints() {
            assert!(validate(&exam).is_ok(), "template {} inválido", exam.id);
            let mut tombstone = exam.clone();
            tombstone.status = ExamLifecycleStatus::Excluido;
            tombstone.is_active_in_ladder = false;
            assert!(validate(&tombstone).is_ok(), "exclusão de {} inválida", exam.id);
        }
    }
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
            crate::services::exam_engine::days_until(
                "2026-01-1ç",
                chrono::Utc::now().date_naive()
            ),
            None
        );
    }
}
