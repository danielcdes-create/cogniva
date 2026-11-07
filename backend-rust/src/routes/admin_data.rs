use crate::{
    error::AppError,
    security::SessionUser,
    services::{question_contracts, question_storage},
    AppState,
};
use axum::{
    extract::{Path, Query, State},
    Extension, Json,
};
use bson::{doc, oid::ObjectId, Bson, Document};
use futures::TryStreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct QuestionQuery {
    pub search: Option<String>,
    pub after: Option<String>,
    pub question_type: Option<String>,
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
            doc! {"$regex":regex::escape(text.trim()),"$options":"i"},
        );
    }
    if let Some(kind) = q.question_type.filter(|s| !s.is_empty()) {
        if !["MULTIPLE_CHOICE", "TRUE_FALSE", "OPEN_ENDED"].contains(&kind.as_str()) {
            return Err(AppError::BadRequest("Tipo de questão inválido".into()));
        }
        filter.insert("question_type", kind);
    }
    if let Some(id) = q.after {
        filter.insert("_id", doc! {"$lt":ObjectId::parse_str(id)?});
    }
    let mut rows:Vec<Document>=question_storage::collection(&state.db,"todasQuestoes").find(filter).sort(doc!{"_id":-1}).limit(limit+1).projection(doc!{"question":1,"question_type":1,"schema_version":1,"revision":1,"status":1,"source":1,"classification.state":1,"validity.status":1,"validity.review_due_on":1,"validity.scope":1,"validity.context_date":1}).await?.try_collect().await?;
    let more = rows.len() > limit as usize;
    rows.truncate(limit as usize);
    let after = if more {
        rows.last()
            .and_then(|r| r.get_object_id("_id").ok())
            .map(|id| id.to_hex())
    } else {
        None
    };
    Ok(Json(
        json!({"items":rows.into_iter().map(|r|Bson::Document(r).into_relaxed_extjson()).collect::<Vec<_>>(),"after":after}),
    ))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EditPreview {
    pub operation_id: String,
    pub expected_revision: i64,
    pub document: Value,
}
pub async fn prepare_question(
    State(state): State<Arc<AppState>>,
    Extension(_user): Extension<SessionUser>,
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
        .find_one(doc! {"_id":oid})
        .await?
        .ok_or_else(|| AppError::NotFound("Questão ausente".into()))?;
    let old = Bson::Document(raw).into_relaxed_extjson();
    let legacy=old["schema_version"]!=2;
    if old["revision"].as_i64().unwrap_or(0) != body.expected_revision
        || body.document["_id"]["$oid"] != id
    {
        return Err(AppError::Conflict(
            "Revise a versão atual antes de salvar o contrato v2"
                .into(),
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
    body.document["content_revision"] = json!(question_contracts::hash(&body.document)?);
    // Content edits must not silently preserve a definitive validity decision.
    let changed = body.document["content_revision"] != old["content_revision"];
    if changed && !legacy {
        body.document["validity"] = json!({"revision":old["validity"]["revision"].as_i64().unwrap_or(0)+1,"status":"UNDER_REVIEW","basis":old["validity"]["basis"],"reference_date":null,"review_due_on":null,"reason_codes":["REVIEW_REQUESTED"],"evaluated_content_revision":null,"decision":null,"scope":old["validity"]["scope"],"context_date":old["validity"]["context_date"]});
    }
    if changed {
        let provenance=json!({"actor":{"type":"HUMAN","id":_user.0.to_string()},"method":"MANUAL_REVIEW","rationale":"Classificação conferida na revisão explícita da edição da questão.","recorded_at":chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs,true),"evidence":[],"model":null,"prompt_id":null,"prompt_version":null,"source_suggestion_id":null});
        if let Some(links)=body.document["classification"]["links"].as_array_mut(){for link in links {link["provenance"]=provenance.clone();}}
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
    question_storage::save(&state.db, _user.0, request).await?;
    Ok(Json(
        json!({"content_changed":changed,"command":{"operation_id":body.operation_id,"expected_revision":body.expected_revision,"dry_run":false,"document":body.document}}),
    ))
}

pub async fn summary(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<Value>, AppError> {
    let questions = question_storage::collection(&state.db, "todasQuestoes");
    let (count, v2) = tokio::try_join!(
        questions.count_documents(doc! {}),
        questions.count_documents(doc! {"schema_version":2})
    )?;
    let exams = crate::services::exam_store::list(&state, user.0).await?;
    Ok(Json(
        json!({"questions":count,"questions_v2":v2,"exams":exams.len(),"catalog_version":question_contracts::catalog_ref()["catalog_version"]}),
    ))
}

pub async fn catalog_base() -> Json<Value> {
    Json(
        json!({"catalog":question_contracts::catalog(),"sha256":crate::services::catalog_editor::hash(&question_contracts::catalog_ref()),"activation":"ADMIN_REVIEW"}),
    )
}
pub async fn catalog_validate(Json(body): Json<Value>) -> Result<Json<Value>, AppError> {
    crate::services::catalog_editor::validate(&body).map(Json)
}
pub async fn catalog_save(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Json(body): Json<crate::services::catalog_editor::DraftRequest>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_editor::save(&state, user.0, body)
        .await
        .map(Json)
}
pub async fn catalog_list(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_editor::list(&state, user.0)
        .await
        .map(Json)
}
pub async fn catalog_get(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<SessionUser>,
    Path(id): Path<String>,
) -> Result<Json<Value>, AppError> {
    crate::services::catalog_editor::get(&state, user.0, &id)
        .await
        .map(Json)
}
pub async fn catalog_activate(State(state):State<Arc<AppState>>,Extension(user):Extension<SessionUser>,Json(body):Json<crate::services::catalog_runtime::ActivateRequest>)->Result<Json<Value>,AppError>{crate::services::catalog_runtime::activate(&state,user.0,body).await.map(Json)}
pub async fn catalog_activation_status(State(state):State<Arc<AppState>>,Extension(user):Extension<SessionUser>,Path(op):Path<String>)->Result<Json<Value>,AppError>{crate::services::catalog_runtime::activation_status(&state,user.0,&op).await.map(Json)}
pub async fn catalog_history(State(state):State<Arc<AppState>>,Query(query):Query<std::collections::HashMap<String,String>>)->Result<Json<Value>,AppError>{
    let before=query.get("before").map(|v|v.parse::<i64>().map_err(|_|AppError::BadRequest("Versão inválida".into()))).transpose()?;
    crate::services::catalog_runtime::history(&state,before).await.map(Json)
}
pub async fn catalog_version(State(state):State<Arc<AppState>>,Path(version):Path<i64>)->Result<Json<Value>,AppError>{crate::services::catalog_runtime::version(&state,version).await.map(Json)}
