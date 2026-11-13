use crate::{
    error::AppError,
    models::exam_hierarchy::{
        CanonicalTopicStatus, ExamBlueprint, ExamLifecycleStatus, PublishEditalRequest, TopicRef,
    },
    security::SessionUser,
    services::{exam_engine::apply_publish, exam_store, question_contracts, question_storage},
    AppState,
};
use axum::{
    extract::{Path, Query, State},
    Extension, Json,
};
use bson::{doc, oid::ObjectId, Bson, Document};
use chrono::Utc;
use futures::TryStreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use std::{collections::HashMap, sync::Arc};

const VALID_REASON_CODES: &[&str] = &[
    "LAW_REPEALED",
    "LAW_AMENDED",
    "ANSWER_CHANGED",
    "TECHNOLOGY_CHANGED",
    "FACT_CHANGED",
    "SOURCE_UNAVAILABLE",
    "REVIEW_REQUESTED",
];

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct QuestionQuery {
    pub search: Option<String>,
    pub after: Option<String>,
    pub question_type: Option<String>,
    pub validity_status: Option<String>,
    pub classification_state: Option<String>,
    pub limit: Option<i64>,
}

pub async fn questions(
    State(state): State<Arc<AppState>>,
    Query(q): Query<QuestionQuery>,
) -> Result<Json<Value>, AppError> {
    let limit = q.limit.unwrap_or(25);
    if !(1..=50).contains(&limit) || q.search.as_ref().is_some_and(|s| s.len() > 200) {
        return Err(AppError::BadRequest(
            "Busca de até 200 bytes e páginas de 1 a 50 itens".into(),
        ));
    }
    let mut filter = doc! {};
    if let Some(text) = q.search.filter(|s| !s.trim().is_empty()) {
        filter.insert(
            "question",
            doc! { "$regex": regex::escape(text.trim()), "$options": "i" },
        );
    }
    if let Some(kind) = q.question_type.filter(|s| !s.is_empty()) {
        if !["MULTIPLE_CHOICE", "TRUE_FALSE", "OPEN_ENDED"].contains(&kind.as_str()) {
            return Err(AppError::BadRequest("Tipo de questão inválido".into()));
        }
        filter.insert("question_type", kind);
    }
    if let Some(vstatus) = q.validity_status.filter(|s| !s.is_empty()) {
        if !["CURRENT", "UNDER_REVIEW", "OBSOLETE", "UNKNOWN"].contains(&vstatus.as_str()) {
            return Err(AppError::BadRequest("Estado de validade inválido".into()));
        }
        filter.insert("validity.status", vstatus);
    }
    if let Some(cstate) = q.classification_state.filter(|s| !s.is_empty()) {
        if !["CONFIRMED", "PROPOSED", "UNCLASSIFIED"].contains(&cstate.as_str()) {
            return Err(AppError::BadRequest("Estado de classificação inválido".into()));
        }
        filter.insert("classification.state", cstate);
    }
    if let Some(id) = q.after {
        filter.insert("_id", doc! { "$lt": ObjectId::parse_str(id)? });
    }
    let mut rows: Vec<Document> = question_storage::collection(&state.db, "todasQuestoes")
        .find(filter)
        .sort(doc! { "_id": -1 })
        .limit(limit + 1)
        .projection(doc! {
            "question": 1,
            "question_type": 1,
            "schema_version": 1,
            "revision": 1,
            "status": 1,
            "source": 1,
            "disciplina": 1,
            "explanation": 1,
            "level": 1,
            "exam_year": 1,
            "classification.state": 1,
            "validity.status": 1,
            "validity.review_due_on": 1,
            "validity.scope": 1,
            "validity.context_date": 1
        })
        .await?
        .try_collect()
        .await?;
    let more = rows.len() > limit as usize;
    rows.truncate(limit as usize);
    let after = if more {
        rows.last()
            .and_then(|r| r.get_object_id("_id").ok())
            .map(|id| id.to_hex())
    } else {
        None
    };
    Ok(Json(json!({
        "items": rows
            .into_iter()
            .map(|r| Bson::Document(r).into_relaxed_extjson())
            .collect::<Vec<_>>(),
        "after": after
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EditPreview {
    pub operation_id: String,
    pub expected_revision: i64,
    pub document: Value,
}

fn human_provenance(user_id: i64, rationale: &str) -> Value {
    json!({
        "actor": { "type": "HUMAN", "id": user_id.to_string() },
        "method": "MANUAL_REVIEW",
        "rationale": rationale,
        "recorded_at": Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
        "evidence": [],
        "model": null,
        "prompt_id": null,
        "prompt_version": null,
        "source_suggestion_id": null
    })
}

pub async fn prepare_question(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
    Json(mut body): Json<EditPreview>,
) -> Result<Json<Value>, AppError> {
    if !body.document.is_object()
        || !body.document["classification"].is_object()
        || !body.document["validity"].is_object()
    {
        return Err(AppError::BadRequest(
            "Informe o documento v2 com classificação e validade".into(),
        ));
    }
    uuid::Uuid::parse_str(&body.operation_id)
        .map_err(|_| AppError::BadRequest("Operação inválida".into()))?;
    let oid = ObjectId::parse_str(&id)?;
    let raw = question_storage::collection(&state.db, "todasQuestoes")
        .find_one(doc! { "_id": oid })
        .await?
        .ok_or_else(|| AppError::NotFound("Questão ausente".into()))?;
    let old = Bson::Document(raw).into_relaxed_extjson();
    let legacy = old["schema_version"] != 2;
    if old["revision"].as_i64().unwrap_or(0) != body.expected_revision
        || body.document["_id"]["$oid"] != id
    {
        return Err(AppError::Conflict(
            "Revise a versão atual antes de salvar o contrato v2".into(),
        ));
    }
    if body.document.get("schema_outbox").is_some() {
        return Err(AppError::Conflict(
            "Auditoria pendente; recarregue a questão".into(),
        ));
    }
    body.document["revision"] = json!(body
        .expected_revision
        .checked_add(1)
        .ok_or_else(|| AppError::BadRequest("Revisão inválida".into()))?);
    let new_content_revision = question_contracts::hash(&body.document)?;
    body.document["content_revision"] = json!(&new_content_revision);

    let changed = body.document["content_revision"] != old["content_revision"];
    let incoming_status = body.document["validity"]["status"]
        .as_str()
        .unwrap_or("UNDER_REVIEW")
        .to_string();
    let explicit_validity_decision =
        body.document["validity"]["status"] != old["validity"]["status"];

    // Content edits without an explicit new validity decision transition to UNDER_REVIEW.
    if changed && !legacy && !explicit_validity_decision {
        body.document["validity"] = json!({
            "revision": old["validity"]["revision"].as_i64().unwrap_or(0) + 1,
            "status": "UNDER_REVIEW",
            "basis": old["validity"]["basis"],
            "reference_date": null,
            "review_due_on": null,
            "reason_codes": ["REVIEW_REQUESTED"],
            "evaluated_content_revision": null,
            "decision": null,
            "scope": old["validity"]["scope"],
            "context_date": old["validity"]["context_date"]
        });
    } else {
        let today = Utc::now().format("%Y-%m-%d").to_string();
        match incoming_status.as_str() {
            "CURRENT" => {
                if !body.document["validity"]["reference_date"].is_string() {
                    body.document["validity"]["reference_date"] = json!(today);
                }
                body.document["validity"]["reason_codes"] = json!([]);
                if body.document["validity"]["evaluated_content_revision"].as_str()
                    != Some(new_content_revision.as_str())
                    || !body.document["validity"]["decision"].is_object()
                {
                    body.document["validity"]["evaluated_content_revision"] =
                        json!(&new_content_revision);
                    body.document["validity"]["decision"] = human_provenance(
                        user.user_id,
                        "Validade atual confirmada manualmente pelo administrador.",
                    );
                }
            }
            "OBSOLETE" => {
                if !body.document["validity"]["reference_date"].is_string() {
                    body.document["validity"]["reference_date"] = json!(today);
                }
                let valid_codes: Vec<String> = body.document["validity"]["reason_codes"]
                    .as_array()
                    .map(|arr| {
                        arr.iter()
                            .filter_map(|v| v.as_str())
                            .filter(|code| VALID_REASON_CODES.contains(code))
                            .map(str::to_string)
                            .collect()
                    })
                    .unwrap_or_default();
                body.document["validity"]["reason_codes"] = if valid_codes.is_empty() {
                    json!(["FACT_CHANGED"])
                } else {
                    json!(valid_codes)
                };
                if body.document["validity"]["evaluated_content_revision"].as_str()
                    != Some(new_content_revision.as_str())
                    || !body.document["validity"]["decision"].is_object()
                {
                    body.document["validity"]["evaluated_content_revision"] =
                        json!(&new_content_revision);
                    body.document["validity"]["decision"] = human_provenance(
                        user.user_id,
                        "Questão marcada como obsoleta na revisão administrativa.",
                    );
                }
            }
            "UNDER_REVIEW" => {
                body.document["validity"]["evaluated_content_revision"] = Value::Null;
                body.document["validity"]["decision"] = Value::Null;
                body.document["validity"]["reference_date"] = Value::Null;
                let has_codes = body.document["validity"]["reason_codes"]
                    .as_array()
                    .is_some_and(|a| !a.is_empty());
                if !has_codes {
                    body.document["validity"]["reason_codes"] = json!(["REVIEW_REQUESTED"]);
                }
            }
            "UNKNOWN" => {
                body.document["validity"]["evaluated_content_revision"] = Value::Null;
                body.document["validity"]["decision"] = Value::Null;
                body.document["validity"]["reference_date"] = Value::Null;
                body.document["validity"]["review_due_on"] = Value::Null;
            }
            _ => {}
        }
    }

    let class_prov = human_provenance(
        user.user_id,
        "Classificação conferida na revisão explícita da edição da questão.",
    );
    if let Some(links) = body.document["classification"]["links"].as_array_mut() {
        for link in links {
            if changed || !link["provenance"].is_object() {
                link["provenance"] = class_prov.clone();
            }
            if !link["link_id"].is_string() {
                link["link_id"] = json!(uuid::Uuid::new_v4().to_string());
            }
        }
    }

    for block in ["classification", "validity"] {
        let mut a = old[block].clone();
        let mut b = body.document[block].clone();
        if let Some(o) = a.as_object_mut() {
            o.remove("revision");
        }
        if let Some(o) = b.as_object_mut() {
            o.remove("revision");
        }
        body.document[block]["revision"] =
            json!(old[block]["revision"].as_i64().unwrap_or(0) + i64::from(a != b));
    }
    question_contracts::validate(&body.document)?;
    let request = question_storage::SaveRequest {
        operation_id: body.operation_id.clone(),
        expected_revision: body.expected_revision,
        dry_run: true,
        document: body.document.clone(),
    };
    question_storage::save(&state.db, user.user_id, request).await?;
    Ok(Json(json!({
        "content_changed": changed,
        "command": {
            "operation_id": body.operation_id,
            "expected_revision": body.expected_revision,
            "dry_run": false,
            "document": body.document
        }
    })))
}

pub async fn summary(
    State(state): State<Arc<AppState>>,
    Extension(_user): Extension<SessionUser>,
) -> Result<Json<Value>, AppError> {
    let questions = question_storage::collection(&state.db, "todasQuestoes");
    let reports = state.db.collection::<Document>("reportedQuestoes");
    let suggestions = question_storage::collection(&state.db, "question_classification_suggestions");
    let (
        count,
        current_count,
        under_review_count,
        obsolete_count,
        unclassified_count,
        open_ended_count,
        open_reports,
        pending_proposals,
    ) = tokio::try_join!(
        questions.count_documents(doc! {}),
        questions.count_documents(doc! { "validity.status": "CURRENT" }),
        questions.count_documents(doc! { "validity.status": "UNDER_REVIEW" }),
        questions.count_documents(doc! { "validity.status": "OBSOLETE" }),
        questions.count_documents(doc! { "classification.state": "UNCLASSIFIED" }),
        questions.count_documents(doc! { "question_type": "OPEN_ENDED" }),
        reports.count_documents(doc! { "fix.status_solved": { "$ne": true } }),
        suggestions.count_documents(doc! { "status": "PENDING" }),
    )?;
    let templates = exam_store::list_templates(&state).await?;
    Ok(Json(json!({
        "questions": count,
        "questions_v2": count,
        "questions_current": current_count,
        "questions_under_review": under_review_count,
        "questions_obsolete": obsolete_count,
        "questions_unclassified": unclassified_count,
        "questions_open_ended": open_ended_count,
        "open_reports": open_reports,
        "pending_proposals": pending_proposals,
        "exams": templates.len(),
        "exam_templates": templates.len(),
        "catalog_version": question_contracts::catalog_ref()["catalog_version"]
    })))
}

pub async fn list_exam_templates(
    State(state): State<Arc<AppState>>,
) -> Result<Json<Vec<ExamBlueprint>>, AppError> {
    exam_store::list_templates(&state).await.map(Json)
}

pub async fn create_exam_template(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(payload): Json<ExamBlueprint>,
) -> Result<Json<ExamBlueprint>, AppError> {
    exam_store::save_template(&state, user.user_id, payload, true)
        .await
        .map(Json)
}

pub async fn update_exam_template(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
    Json(mut payload): Json<ExamBlueprint>,
) -> Result<Json<ExamBlueprint>, AppError> {
    if payload.id != id {
        return Err(AppError::BadRequest(
            "ID diferente do template global editado".into(),
        ));
    }
    if let Ok(mut previous) = exam_store::get_template(&state, &id).await {
        if payload.status == ExamLifecycleStatus::Excluido {
            if previous.status == ExamLifecycleStatus::Excluido {
                return Ok(Json(previous));
            }
            let mut tombstone = previous;
            tombstone.status = ExamLifecycleStatus::Excluido;
            tombstone.is_active_in_ladder = false;
            return exam_store::save_template(&state, user.user_id, tombstone, false)
                .await
                .map(Json);
        }
        if payload.status == ExamLifecycleStatus::EditalPublicado
            && previous.revision == payload.revision
        {
            let request = PublishEditalRequest {
                expected_revision: payload.revision,
                published_topics: payload
                    .subjects
                    .iter()
                    .flat_map(|s| {
                        s.topics
                            .iter()
                            .filter(|t| t.diff_status != CanonicalTopicStatus::Arquivado)
                            .map(move |t| TopicRef {
                                canonical_topic_id: t.canonical_topic_id.clone(),
                                canonical_subject_id: t.canonical_subject_id.clone(),
                                display_title: t.display_title.clone(),
                                local_subject_id: Some(s.local_subject_id.clone()),
                            })
                    })
                    .collect(),
                practiced_canonical_topic_ids: Vec::new(),
                published_at_iso: Some(Utc::now().to_rfc3339()),
            };
            let captured_summary = previous.edital_diff_summary.clone();
            let diff = apply_publish(&mut previous, &request);
            for subject in &mut payload.subjects {
                for topic in &mut subject.topics {
                    if let Some(classified) = diff.topics.iter().find(|t| {
                        t.canonical_topic_id == topic.canonical_topic_id
                            && (t.local_subject_id.as_deref()
                                == Some(subject.local_subject_id.as_str())
                                || t.local_subject_id.is_none())
                    }) {
                        topic.diff_status = classified.diff_status.clone();
                    }
                }
            }
            payload.keep_summary_captured_before_publish(captured_summary);
        }
    }
    exam_store::save_template(&state, user.user_id, payload, false)
        .await
        .map(Json)
}

pub async fn publish_template_edital(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
    Json(mut request): Json<PublishEditalRequest>,
) -> Result<Json<ExamBlueprint>, AppError> {
    let mut template = exam_store::get_template(&state, &id).await?;
    if template.revision != request.expected_revision {
        return Err(AppError::Conflict(
            "O template global mudou; recarregue antes de publicar o edital".into(),
        ));
    }
    request.practiced_canonical_topic_ids = Vec::new();
    request.published_at_iso = Some(Utc::now().to_rfc3339());
    apply_publish(&mut template, &request);
    exam_store::save_template(&state, user.user_id, template, false)
        .await
        .map(Json)
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReportsQuery {
    pub status: Option<String>,
    pub limit: Option<i64>,
}

pub async fn list_reports(
    State(state): State<Arc<AppState>>,
    Query(query): Query<ReportsQuery>,
) -> Result<Json<Value>, AppError> {
    let limit = query.limit.unwrap_or(50).clamp(1, 100);
    let filter = match query.status.as_deref().unwrap_or("open") {
        "open" => doc! { "fix.status_solved": { "$ne": true } },
        "resolved" => doc! { "fix.status_solved": true },
        "all" => doc! {},
        _ => return Err(AppError::BadRequest("Filtro de status de reporte inválido".into())),
    };
    let rows: Vec<Document> = state
        .db
        .collection::<Document>("reportedQuestoes")
        .find(filter)
        .sort(doc! { "ultima_atualizacao": -1, "_id": -1 })
        .limit(limit)
        .await?
        .try_collect()
        .await?;

    let mut q_oids = Vec::new();
    for row in &rows {
        if let Ok(oid) = row.get_object_id("questao_id") {
            if !q_oids.contains(&oid) {
                q_oids.push(oid);
            }
        }
    }

    let mut questions_by_id: HashMap<ObjectId, Document> = HashMap::new();
    if !q_oids.is_empty() {
        let mut cursor = question_storage::collection(&state.db, "todasQuestoes")
            .find(doc! { "_id": { "$in": &q_oids } })
            .projection(doc! { "question": 1, "disciplina": 1, "validity.status": 1 })
            .await?;
        while let Some(qdoc) = cursor.try_next().await? {
            if let Ok(oid) = qdoc.get_object_id("_id") {
                questions_by_id.insert(oid, qdoc);
            }
        }
    }

    let mut items = Vec::with_capacity(rows.len());
    for row in rows {
        let report_id = row
            .get_object_id("_id")
            .map(|id| id.to_hex())
            .unwrap_or_default();
        let q_oid = row.get_object_id("questao_id").ok();
        let question_id = q_oid.map(|id| id.to_hex()).unwrap_or_default();
        let user_id = row
            .get_i64("user_id")
            .or_else(|_| row.get_i32("user_id").map(i64::from))
            .unwrap_or(0);
        let problemas: Vec<String> = row
            .get_array("problemas")
            .map(|arr| {
                arr.iter()
                    .filter_map(|v| v.as_str().map(str::to_string))
                    .collect()
            })
            .unwrap_or_default();
        let solved = row
            .get_document("fix")
            .ok()
            .and_then(|f| f.get_bool("status_solved").ok())
            .unwrap_or(false);
        let resolution_note = row
            .get_document("fix")
            .ok()
            .and_then(|f| f.get_str("resolution_note").ok())
            .map(str::to_string);
        let reported_at = row
            .get_datetime("data_report")
            .ok()
            .map(|d| d.to_chrono().to_rfc3339());
        let updated_at = row
            .get_datetime("ultima_atualizacao")
            .ok()
            .map(|d| d.to_chrono().to_rfc3339());
        let q_meta = q_oid.and_then(|oid| questions_by_id.get(&oid));
        let snippet = q_meta
            .and_then(|q| q.get_str("question").ok())
            .map(|s| s.chars().take(240).collect::<String>())
            .unwrap_or_default();
        let disciplina = q_meta
            .and_then(|q| q.get_str("disciplina").ok())
            .unwrap_or("")
            .to_string();
        let validity_status = q_meta
            .and_then(|q| q.get_document("validity").ok())
            .and_then(|v| v.get_str("status").ok())
            .unwrap_or("UNKNOWN")
            .to_string();
        items.push(json!({
            "report_id": report_id,
            "question_id": question_id,
            "user_id": user_id.to_string(),
            "problemas": problemas,
            "status_solved": solved,
            "resolution_note": resolution_note,
            "reported_at": reported_at,
            "updated_at": updated_at,
            "question_snippet": snippet,
            "disciplina": disciplina,
            "validity_status": validity_status,
        }));
    }
    Ok(Json(json!({ "items": items })))
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ResolveReportRequest {
    pub resolution_note: Option<String>,
    pub resolve_all_for_question: Option<bool>,
}

pub async fn resolve_report(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(report_id): Path<String>,
    Json(body): Json<ResolveReportRequest>,
) -> Result<Json<Value>, AppError> {
    let oid = ObjectId::parse_str(&report_id)?;
    let coll = state.db.collection::<Document>("reportedQuestoes");
    let existing = coll
        .find_one(doc! { "_id": oid })
        .await?
        .ok_or_else(|| AppError::NotFound("Reporte não encontrado".into()))?;

    let now = bson::DateTime::now();
    let note = body
        .resolution_note
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("Resolvido na moderação administrativa.")
        .chars()
        .take(500)
        .collect::<String>();

    let filter = if body.resolve_all_for_question.unwrap_or(false) {
        if let Ok(q_oid) = existing.get_object_id("questao_id") {
            doc! { "questao_id": q_oid, "fix.status_solved": { "$ne": true } }
        } else {
            doc! { "_id": oid }
        }
    } else {
        doc! { "_id": oid }
    };

    let res = coll
        .update_many(
            filter,
            doc! {
                "$set": {
                    "ultima_atualizacao": now,
                    "fix.status_solved": true,
                    "fix.data_solved": now,
                    "fix.resolved_by": user.user_id,
                    "fix.resolution_note": &note,
                }
            },
        )
        .await?;

    Ok(Json(json!({
        "status": "resolved",
        "report_id": report_id,
        "resolved_count": res.modified_count,
    })))
}

pub async fn catalog_base() -> Json<Value> {
    Json(json!({
        "catalog": question_contracts::catalog(),
        "sha256": crate::services::catalog_editor::hash(&question_contracts::catalog_ref()),
        "activation": "ADMIN_REVIEW"
    }))
}

pub async fn catalog_validate(Json(body): Json<Value>) -> Result<Json<Value>, AppError> {
    crate::services::catalog_editor::validate(&body).map(Json)
}

pub async fn catalog_save(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<crate::services::catalog_editor::DraftRequest>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_editor::save(&state, user.user_id, body)
        .await
        .map(Json)
}

pub async fn catalog_list(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_editor::list(&state, user.user_id)
        .await
        .map(Json)
}

pub async fn catalog_get(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_editor::get(&state, user.user_id, &id)
        .await
        .map(Json)
}

pub async fn catalog_activate(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<crate::services::catalog_runtime::ActivateRequest>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_runtime::activate(&state, user.user_id, body)
        .await
        .map(Json)
}

pub async fn catalog_activation_status(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(op): Path<String>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_runtime::activation_status(&state, user.user_id, &op)
        .await
        .map(Json)
}

pub async fn catalog_history(
    State(state): State<Arc<AppState>>,
    Query(query): Query<HashMap<String, String>>,
) -> Result<Json<Value>, AppError> {
    let before = query
        .get("before")
        .map(|v| {
            v.parse::<i64>()
                .map_err(|_| AppError::BadRequest("Versão inválida".into()))
        })
        .transpose()?;
    crate::services::catalog_runtime::history(&state, before)
        .await
        .map(Json)
}

pub async fn catalog_version(
    State(state): State<Arc<AppState>>,
    Path(version): Path<i64>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_runtime::version(&state, version)
        .await
        .map(Json)
}
