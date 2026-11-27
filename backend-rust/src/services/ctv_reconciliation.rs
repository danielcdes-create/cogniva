//! Ciclo de vida de ofertas CTV (`SUPPLIED` -> `DISPLAYED` -> `DISMISSED` | `ANSWERED`),
//! rastreamento de exposição real por revisão de conteúdo/semântica e reconciliação
//! cronológica multi-dispositivo (Seção 2: A03, A04, A05, A14).

use crate::{
    error::AppError,
    services::{
        ctv_alignment::{evaluate_ctv_independence, CtvIndependenceInput, VerificationMode},
        question_compatibility,
    },
    AppState,
};
use bson::{doc, oid::ObjectId, Document};
use chrono::{DateTime, Duration, Utc};
use futures::TryStreamExt;
use mongodb::Database;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashSet;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvLifecycleEventRequest {
    #[serde(default)]
    pub event_id: Option<String>,
    pub offer_id: String,
    pub parent_attempt_id: String,
    pub source_question_id: String,
    pub complementary_question_id: String,
    #[serde(default)]
    pub question_revision: Option<String>,
    /// `"DISPLAYED"` ou `"DISMISSED"` ("Ver gabarito agora")
    pub event_kind: String,
    #[serde(default)]
    pub occurred_at: Option<String>,
    #[serde(default)]
    pub trigger_reason: Option<String>,
    #[serde(default)]
    pub verification_mode: Option<String>,
    #[serde(default)]
    pub delivery_source: Option<String>,
    #[serde(default)]
    pub target_concept_id: Option<String>,
    #[serde(default)]
    pub target_tested_rule: Option<String>,
    #[serde(default)]
    pub framing_reason_code: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvLifecycleEventResponse {
    pub offer_id: String,
    pub state: String,
    pub first_displayed_at: Option<String>,
    pub dismissed_at: Option<String>,
    pub reconciled_attempts_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvReconciledOfferDto {
    pub offer_id: String,
    pub parent_attempt_id: String,
    pub source_question_id: String,
    pub complementary_question_id: String,
    pub content_revision: String,
    pub semantic_revision: String,
    pub state: String,
    pub verification_mode: String,
    pub trigger_reason: Option<String>,
    pub trigger_cohort: String,
    pub first_displayed_at: Option<String>,
    pub dismissed_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvReconciledAttemptDto {
    pub ctv_attempt_id: String,
    pub offer_id: String,
    pub parent_attempt_id: String,
    pub source_question_id: String,
    pub complementary_question_id: String,
    pub content_revision: String,
    pub verification_mode: String,
    pub verification_timing: String,
    pub is_correct: bool,
    pub was_previously_exposed: bool,
    pub counts_as_independent_transfer: bool,
    pub classification_code: String,
    pub trigger_reason: Option<String>,
    pub trigger_cohort: String,
    pub target_concept_id: Option<String>,
    pub target_tested_rule: Option<String>,
    pub occurred_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtvUserStateResponse {
    pub offers: Vec<CtvReconciledOfferDto>,
    pub attempts: Vec<CtvReconciledAttemptDto>,
}

#[derive(Debug, Clone, Default)]
pub struct UserExposureSnapshot {
    pub seen_question_ids: HashSet<String>,
    pub seen_content_revisions: HashSet<String>,
    pub seen_semantic_revisions: HashSet<String>,
}

impl UserExposureSnapshot {
    pub fn has_seen(&self, question_id: &str, content_rev: &str, semantic_rev: &str) -> bool {
        self.seen_question_ids.contains(question_id)
            || (!content_rev.is_empty() && self.seen_content_revisions.contains(content_rev))
            || (!semantic_rev.is_empty() && self.seen_semantic_revisions.contains(semantic_rev))
    }
}

pub fn deterministic_offer_id(user_id: i64, parent_attempt_id: &str) -> String {
    let bytes =
        Sha256::digest(format!("ctv-offer:{user_id}:{}", parent_attempt_id.trim()).as_bytes());
    let mut raw = [0u8; 16];
    raw.copy_from_slice(&bytes[..16]);
    raw[6] = (raw[6] & 0x0f) | 0x40;
    raw[8] = (raw[8] & 0x3f) | 0x80;
    uuid::Uuid::from_bytes(raw).to_string()
}

pub fn parse_client_timestamp_or_now(raw: Option<&str>) -> Result<DateTime<Utc>, AppError> {
    let Some(s) = raw.map(str::trim).filter(|s| !s.is_empty()) else {
        return Ok(Utc::now());
    };
    let dt = DateTime::parse_from_rfc3339(s)
        .map_err(|_| AppError::BadRequest("Carimbo temporal CTV deve estar em RFC 3339".into()))?
        .with_timezone(&Utc);
    if dt > Utc::now() + Duration::minutes(5) || dt.timestamp() < 946_684_800 {
        return Err(AppError::BadRequest(
            "Relógio do evento CTV fora da faixa permitida".into(),
        ));
    }
    Ok(dt)
}

#[derive(Default)]
struct RawUserExposureHistory {
    regular_docs: Vec<Document>,
    q_revs_by_oid: std::collections::HashMap<ObjectId, (String, String)>,
    offer_docs: Vec<Document>,
    ctv_docs: Vec<Document>,
}

impl RawUserExposureHistory {
    async fn load(db: &Database, user_id: i64) -> Result<Self, AppError> {
        let regular_docs: Vec<Document> = db
            .collection::<Document>("userActivityLog")
            .find(doc! { "user_id": user_id })
            .projection(doc! {
                "question_id": 1,
                "datas.data_primeira_sessao": 1,
                "pastSessions.data": 1,
                "pastSessions.question_revision": 1
            })
            .await?
            .try_collect()
            .await?;

        let all_oids: Vec<ObjectId> = regular_docs
            .iter()
            .filter_map(|d| d.get_object_id("question_id").ok())
            .collect();
        let mut q_revs_by_oid = std::collections::HashMap::new();
        if !all_oids.is_empty() {
            let q_revs: Vec<Document> = db
                .collection::<Document>("todasQuestoes")
                .find(doc! { "_id": { "$in": &all_oids } })
                .projection(doc! { "content_revision": 1, "semantic_revision": 1 })
                .await?
                .try_collect()
                .await?;
            for qr in q_revs {
                if let Ok(oid) = qr.get_object_id("_id") {
                    let cr = qr.get_str("content_revision").unwrap_or("").to_string();
                    let sr = qr.get_str("semantic_revision").unwrap_or("").to_string();
                    q_revs_by_oid.insert(oid, (cr, sr));
                }
            }
        }

        let offer_docs: Vec<Document> = db
            .collection::<Document>("ctv_offers")
            .find(doc! {
                "user_id": user_id,
                "first_displayed_at": { "$ne": bson::Bson::Null, "$exists": true }
            })
            .projection(doc! { "cached_response": 0 })
            .await?
            .try_collect()
            .await?;

        let ctv_docs: Vec<Document> = db
            .collection::<Document>("conceptual_transfer_attempts")
            .find(doc! { "user_id": user_id })
            .projection(doc! { "response_ack": 0 })
            .await?
            .try_collect()
            .await?;

        Ok(Self {
            regular_docs,
            q_revs_by_oid,
            offer_docs,
            ctv_docs,
        })
    }

    fn build_snapshot(
        &self,
        exclude_offer_id: Option<&str>,
        exclude_ctv_attempt_id: Option<&str>,
        cutoff: Option<(DateTime<Utc>, &str)>,
    ) -> UserExposureSnapshot {
        let mut snap = UserExposureSnapshot::default();
        for doc in &self.regular_docs {
            let Some(qid) = doc.get_object_id("question_id").ok() else {
                continue;
            };
            let mut earliest_session: Option<DateTime<Utc>> = doc
                .get_document("datas")
                .ok()
                .and_then(|d| d.get_datetime("data_primeira_sessao").ok())
                .map(|dt| dt.to_chrono());

            if let Ok(sessions) = doc.get_array("pastSessions") {
                for s in sessions.iter().filter_map(|v| v.as_document()) {
                    if let Ok(sdt) = s.get_datetime("data") {
                        let cdt = sdt.to_chrono();
                        let is_before = match cutoff {
                            Some((cut_ts, _)) => cdt <= cut_ts,
                            None => true,
                        };
                        if is_before {
                            if let Ok(qrev) = s.get_str("question_revision") {
                                if !qrev.is_empty() {
                                    snap.seen_content_revisions.insert(qrev.to_string());
                                }
                            }
                        }
                        earliest_session = Some(earliest_session.map_or(cdt, |prev| prev.min(cdt)));
                    }
                }
            }

            let qualifies = match (cutoff, earliest_session) {
                (Some((cut_ts, _)), Some(es)) => es <= cut_ts,
                (Some(_), None) => false,
                (None, _) => true,
            };
            if qualifies {
                snap.seen_question_ids.insert(qid.to_hex());
                if let Some((cr, sr)) = self.q_revs_by_oid.get(&qid) {
                    if !cr.is_empty() {
                        snap.seen_content_revisions.insert(cr.clone());
                    }
                    if !sr.is_empty() {
                        snap.seen_semantic_revisions.insert(sr.clone());
                    }
                }
            }
        }

        for off in &self.offer_docs {
            let off_id = off.get_str("offer_id").unwrap_or("");
            if exclude_offer_id.is_some_and(|ex| ex == off_id) {
                continue;
            }
            let Some(disp_dt) = off
                .get_datetime("first_displayed_at")
                .ok()
                .map(|d| d.to_chrono())
            else {
                continue;
            };
            if let Some((cut_ts, cut_tie)) = cutoff {
                if (disp_dt, off_id) >= (cut_ts, cut_tie) {
                    continue;
                }
            }
            if let Ok(cq) = off.get_str("complementary_question_id") {
                if !cq.is_empty() {
                    snap.seen_question_ids.insert(cq.to_string());
                }
            }
            if let Ok(cr) = off.get_str("content_revision") {
                if !cr.is_empty() {
                    snap.seen_content_revisions.insert(cr.to_string());
                }
            }
            if let Ok(sr) = off.get_str("semantic_revision") {
                if !sr.is_empty() {
                    snap.seen_semantic_revisions.insert(sr.to_string());
                }
            }
        }

        for att in &self.ctv_docs {
            let att_id = att.get_str("ctv_attempt_id").unwrap_or("");
            let att_off_id = att.get_str("offer_id").unwrap_or("");
            if exclude_ctv_attempt_id.is_some_and(|ex| ex == att_id) {
                continue;
            }
            if !att_off_id.is_empty() && exclude_offer_id.is_some_and(|ex| ex == att_off_id) {
                continue;
            }
            let eff_dt = att
                .get_datetime("displayed_at")
                .or_else(|_| att.get_datetime("occurred_at"))
                .ok()
                .map(|d| d.to_chrono());
            if let Some((cut_ts, cut_tie)) = cutoff {
                let Some(dt) = eff_dt else {
                    continue;
                };
                let tie_id = if !att_off_id.is_empty() {
                    att_off_id
                } else {
                    att_id
                };
                if (dt, tie_id) >= (cut_ts, cut_tie) {
                    continue;
                }
            }
            if let Ok(cq) = att.get_str("complementary_question_id") {
                if !cq.is_empty() {
                    snap.seen_question_ids.insert(cq.to_string());
                }
            }
            if let Ok(cr) = att.get_str("content_revision") {
                if !cr.is_empty() {
                    snap.seen_content_revisions.insert(cr.to_string());
                }
            }
            if let Ok(sr) = att.get_str("semantic_revision") {
                if !sr.is_empty() {
                    snap.seen_semantic_revisions.insert(sr.to_string());
                }
            }
        }
        snap
    }
}

/// Carrega todas as exposições reais do estudante anteriores a `cutoff` (excluindo a própria `current_offer_id`
/// e `current_ctv_attempt_id`), considerando tanto `question_id` quanto `content_revision` e `semantic_revision`.
/// Ofertas apenas pré-carregadas (`first_displayed_at == null` e sem resposta/dispensa) NÃO marcam o item como visto!
pub async fn load_user_exposure_snapshot(
    db: &Database,
    user_id: i64,
    exclude_offer_id: Option<&str>,
    exclude_ctv_attempt_id: Option<&str>,
    cutoff: Option<(DateTime<Utc>, &str)>,
) -> Result<UserExposureSnapshot, AppError> {
    let raw = RawUserExposureHistory::load(db, user_id).await?;
    Ok(raw.build_snapshot(exclude_offer_id, exclude_ctv_attempt_id, cutoff))
}

/// Reconcilia cronologicamente todas as tentativas CTV do usuário que tocam a questão ou revisão informada,
/// garantindo que a chegada tardia de uma exposição/dispensa/tentativa anterior de outro dispositivo
/// atualize o histórico uma única vez sem duplicar nem distorcer contadores (A05).
pub async fn reconcile_user_ctv_history(
    db: &Database,
    user_id: i64,
    question_id: &str,
    content_rev: &str,
    semantic_rev: &str,
) -> Result<usize, AppError> {
    let mut or_clauses = vec![doc! { "complementary_question_id": question_id }];
    if !content_rev.is_empty() {
        or_clauses.push(doc! { "content_revision": content_rev });
    }
    if !semantic_rev.is_empty() {
        or_clauses.push(doc! { "semantic_revision": semantic_rev });
    }

    let ctv_coll = db.collection::<Document>("conceptual_transfer_attempts");
    let mut matching_attempts: Vec<Document> = ctv_coll
        .find(doc! {
            "user_id": user_id,
            "$or": or_clauses
        })
        .await?
        .try_collect()
        .await?;

    if matching_attempts.is_empty() {
        return Ok(0);
    }

    matching_attempts.sort_by(|a, b| {
        let ta = a
            .get_datetime("displayed_at")
            .or_else(|_| a.get_datetime("occurred_at"))
            .map(|d| d.timestamp_millis())
            .unwrap_or(i64::MAX);
        let tb = b
            .get_datetime("displayed_at")
            .or_else(|_| b.get_datetime("occurred_at"))
            .map(|d| d.timestamp_millis())
            .unwrap_or(i64::MAX);
        let ida = a.get_str("ctv_attempt_id").unwrap_or("");
        let idb = b.get_str("ctv_attempt_id").unwrap_or("");
        (ta, ida).cmp(&(tb, idb))
    });

    let raw_history = RawUserExposureHistory::load(db, user_id).await?;
    let mut reconciled_updates = 0usize;
    for att in matching_attempts {
        let Ok(doc_id) = att.get_str("_id") else {
            continue;
        };
        let att_id = att.get_str("ctv_attempt_id").unwrap_or("");
        let off_id_opt = att.get_str("offer_id").ok().filter(|s| !s.is_empty());
        let tie_key = off_id_opt.unwrap_or(att_id);

        // Registros legados sem momento ou sem campo explícito de exposição conservam condição desconhecida (A05/A14)
        let has_known_timing = att.contains_key("verification_timing")
            && att.get_str("verification_timing").ok() != Some("UNKNOWN_CONDITION");
        let eff_dt_opt = att
            .get_datetime("displayed_at")
            .or_else(|_| att.get_datetime("occurred_at"))
            .ok()
            .map(|d| d.to_chrono());
        let exposure_history_known =
            has_known_timing && eff_dt_opt.is_some() && att.contains_key("was_previously_exposed");

        let cq_id = att.get_str("complementary_question_id").unwrap_or("");
        let cq_crev = att.get_str("content_revision").unwrap_or(content_rev);
        let cq_srev = att.get_str("semantic_revision").unwrap_or(semantic_rev);

        let discovered_prior_exposure = if let Some(eff_dt) = eff_dt_opt {
            let snap =
                raw_history.build_snapshot(off_id_opt, Some(att_id), Some((eff_dt, tie_key)));
            snap.has_seen(cq_id, cq_crev, cq_srev)
        } else {
            false
        };

        let client_reported_initial = att
            .get_bool("client_was_previously_exposed")
            .unwrap_or_else(|_| att.get_bool("was_previously_exposed").unwrap_or(false));
        let next_was_exposed = client_reported_initial || discovered_prior_exposure;

        let vmode = VerificationMode::from_code(
            att.get_str("verification_mode")
                .unwrap_or("CONCEPTUAL_TRANSFER"),
        );
        let timing = att
            .get_str("verification_timing")
            .unwrap_or("UNKNOWN_CONDITION");
        let had_help = att.get_bool("had_decisive_help").unwrap_or(false);
        let is_correct = att.get_bool("is_correct").unwrap_or(false);

        let verdict = evaluate_ctv_independence(&CtvIndependenceInput {
            verification_mode: vmode,
            action: "ANSWERED".to_string(),
            verification_timing: timing.to_string(),
            was_previously_exposed: next_was_exposed,
            exposure_history_known,
            had_decisive_help: had_help,
            is_correct,
        });

        let prev_exposed = att.get_bool("was_previously_exposed").unwrap_or(false);
        let prev_indep = att
            .get_bool("counts_as_independent_transfer")
            .unwrap_or(false);
        let prev_code = att.get_str("classification_code").unwrap_or("");

        if prev_exposed != next_was_exposed
            || prev_indep != verdict.counts_as_independent_attempt
            || prev_code != verdict.classification_code
        {
            let mut set_doc = doc! {
                "was_previously_exposed": next_was_exposed,
                "counts_as_independent_transfer": verdict.counts_as_independent_attempt,
                "counts_as_independent_correct": verdict.counts_as_independent_correct,
                "classification_code": verdict.classification_code,
                "reconciled_at": bson::DateTime::now(),
            };
            if let Ok(mut ack_doc) = att.get_document("response_ack").cloned() {
                ack_doc.insert(
                    "counts_as_independent_transfer",
                    verdict.counts_as_independent_attempt,
                );
                ack_doc.insert("was_previously_exposed", next_was_exposed);
                ack_doc.insert("classification_code", verdict.classification_code);
                if next_was_exposed && !verdict.counts_as_independent_attempt {
                    ack_doc.insert(
                        "consolidated_diagnostic_title_pt",
                        "Verificação reconciliada como revisão",
                    );
                    ack_doc.insert(
                        "consolidated_diagnostic_message_pt",
                        "Uma exposição anterior a este item foi sincronizada; o resultado bruto foi preservado sem contar como transferência inédita.",
                    );
                }
                set_doc.insert("response_ack", ack_doc);
            }
            let res = ctv_coll
                .update_one(doc! { "_id": doc_id }, doc! { "$set": set_doc })
                .await?;
            if res.modified_count > 0 {
                reconciled_updates += 1;
            }
        }
    }

    Ok(reconciled_updates)
}

pub async fn record_ctv_event(
    state: &AppState,
    user_id: i64,
    req: CtvLifecycleEventRequest,
) -> Result<CtvLifecycleEventResponse, AppError> {
    let offer_id = req.offer_id.trim();
    if offer_id.is_empty() || offer_id.len() > 120 {
        return Err(AppError::BadRequest(
            "Identificador da oferta CTV inválido".into(),
        ));
    }
    uuid::Uuid::parse_str(&req.parent_attempt_id)
        .map_err(|_| AppError::BadRequest("Identificador da tentativa original inválido".into()))?;
    let _source_oid = ObjectId::parse_str(&req.source_question_id)?;
    let comp_oid = ObjectId::parse_str(&req.complementary_question_id)?;

    let kind = match req.event_kind.trim().to_ascii_uppercase().as_str() {
        "DISPLAYED" => "DISPLAYED",
        "DISMISSED" => "DISMISSED",
        _ => {
            return Err(AppError::BadRequest(
                "Tipo de evento CTV inválido; use DISPLAYED ou DISMISSED".into(),
            ))
        }
    };

    let event_dt = parse_client_timestamp_or_now(req.occurred_at.as_deref())?;
    let event_bson = bson::DateTime::from_chrono(event_dt);

    let comp_raw_opt = state
        .db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! { "_id": comp_oid })
        .await?;
    let (content_rev, semantic_rev) = if let Some(raw) = &comp_raw_opt {
        let cr = raw
            .get_str("content_revision")
            .ok()
            .map(str::to_string)
            .or_else(|| {
                question_compatibility::decode(raw.clone())
                    .ok()
                    .and_then(|q| q.revision().ok())
            })
            .or_else(|| req.question_revision.clone())
            .unwrap_or_default();
        let sr = raw
            .get_str("semantic_revision")
            .ok()
            .map(str::to_string)
            .unwrap_or_default();
        (cr, sr)
    } else {
        (
            req.question_revision.clone().unwrap_or_default(),
            String::new(),
        )
    };

    let offers_coll = state.db.collection::<Document>("ctv_offers");
    let doc_key = format!("{user_id}:{offer_id}");
    let existing = offers_coll.find_one(doc! { "_id": &doc_key }).await?;

    let prev_disp = existing
        .as_ref()
        .and_then(|d| d.get_datetime("first_displayed_at").ok())
        .map(|d| d.to_chrono());
    let prev_dism = existing
        .as_ref()
        .and_then(|d| d.get_datetime("dismissed_at").ok())
        .map(|d| d.to_chrono());
    let prev_state = existing
        .as_ref()
        .and_then(|d| d.get_str("state").ok())
        .unwrap_or("SUPPLIED");

    let effective_disp = prev_disp.map_or(event_dt, |p| p.min(event_dt));
    let effective_dism = if kind == "DISMISSED" {
        Some(prev_dism.map_or(event_dt, |p| p.min(event_dt)))
    } else {
        prev_dism
    };
    let next_state = if prev_state == "ANSWERED" {
        "ANSWERED"
    } else if effective_dism.is_some() {
        "DISMISSED"
    } else {
        "DISPLAYED"
    };

    let trigger_cohort = crate::services::ctv_alignment::trigger_cohort_for_reason(
        req.trigger_reason.as_deref().or_else(|| {
            existing
                .as_ref()
                .and_then(|d| d.get_str("trigger_reason").ok())
        }),
    );

    let mut set_doc = doc! {
        "state": next_state,
        "updated_at": bson::DateTime::now(),
    };
    let mut min_doc = doc! {
        "first_displayed_at": bson::DateTime::from_chrono(effective_disp),
    };
    if let Some(dism) = effective_dism {
        min_doc.insert("dismissed_at", bson::DateTime::from_chrono(dism));
    }
    if !content_rev.is_empty() {
        set_doc.insert("content_revision", &content_rev);
    }
    if !semantic_rev.is_empty() {
        set_doc.insert("semantic_revision", &semantic_rev);
    }

    let on_insert = doc! {
        "_id": &doc_key,
        "user_id": user_id,
        "offer_id": offer_id,
        "parent_attempt_id": &req.parent_attempt_id,
        "source_question_id": &req.source_question_id,
        "complementary_question_id": &req.complementary_question_id,
        "verification_mode": req.verification_mode.as_deref().unwrap_or("CONCEPTUAL_TRANSFER"),
        "framing_reason_code": req.framing_reason_code.as_deref().unwrap_or("SAME_CONCEPT_AND_RULE_NEW_CONTEXT"),
        "target_concept_id": req.target_concept_id.as_deref(),
        "target_tested_rule": req.target_tested_rule.as_deref(),
        "trigger_reason": req.trigger_reason.as_deref(),
        "trigger_cohort": trigger_cohort,
        "delivery_source": req.delivery_source.as_deref().unwrap_or("OFFLINE_LOCAL_BANK"),
        "supplied_at": event_bson,
    };

    offers_coll
        .update_one(
            doc! { "_id": &doc_key },
            doc! { "$set": set_doc, "$min": min_doc, "$setOnInsert": on_insert },
        )
        .upsert(true)
        .await?;

    // Se a entrega ocorreu inicialmente por cache offline e há recibo válido da tentativa pai (ou oferta prévia), registra auditoria idempotente
    if req.delivery_source.as_deref() == Some("OFFLINE_LOCAL_BANK") && comp_raw_opt.is_some() {
        let parent_receipt_exists = existing.is_some()
            || state
                .db
                .collection::<Document>("attempt_receipts")
                .find_one(doc! { "_id": format!("{user_id}:{}", req.parent_attempt_id) })
                .await
                .ok()
                .flatten()
                .is_some();
        if parent_receipt_exists {
            let audit_id = format!("ctv-offline-offer:{user_id}:{offer_id}");
            // Hash anonimizado para idempotência sem gravar user_id pessoal em ai_generation_audit
            let anon_req_id = format!("{:x}", Sha256::digest(audit_id.as_bytes()));
            let _ = state
                .db
                .collection::<Document>("ai_generation_audit")
                .update_one(
                    doc! { "_id": &anon_req_id },
                    doc! {
                        "$setOnInsert": {
                            "_id": &anon_req_id,
                            "request_id": &anon_req_id,
                            "outcome": "OFFLINE_CACHE_REUSE_RECONCILED",
                            "delivery_channel": "OFFLINE_CACHE_RECONCILED",
                            "question_id": &req.complementary_question_id,
                            "provider_calls": 0_i32,
                            "persisted_item": false,
                            "duplicate_barred": false,
                            "delivered_to_student": true,
                            "estimated_cost_usd": 0.0_f64,
                            "cost_source": "ESTIMATED_REFERENCE",
                            "recorded_at": event_bson,
                        }
                    },
                )
                .upsert(true)
                .await;
        }
    }

    let reconciled = reconcile_user_ctv_history(
        &state.db,
        user_id,
        &req.complementary_question_id,
        &content_rev,
        &semantic_rev,
    )
    .await?;

    Ok(CtvLifecycleEventResponse {
        offer_id: offer_id.to_string(),
        state: next_state.to_string(),
        first_displayed_at: Some(effective_disp.to_rfc3339()),
        dismissed_at: effective_dism.map(|d| d.to_rfc3339()),
        reconciled_attempts_count: reconciled,
    })
}

pub async fn get_user_ctv_state(
    state: &AppState,
    user_id: i64,
) -> Result<CtvUserStateResponse, AppError> {
    let offer_docs: Vec<Document> = state
        .db
        .collection::<Document>("ctv_offers")
        .find(doc! {
            "user_id": user_id,
            "first_displayed_at": { "$ne": bson::Bson::Null, "$exists": true }
        })
        .sort(doc! { "first_displayed_at": -1 })
        .limit(500)
        .await?
        .try_collect()
        .await?;

    let offers = offer_docs
        .into_iter()
        .map(|d| {
            let tr = d.get_str("trigger_reason").ok().map(str::to_string);
            let cohort = d
                .get_str("trigger_cohort")
                .ok()
                .map(str::to_string)
                .unwrap_or_else(|| {
                    crate::services::ctv_alignment::trigger_cohort_for_reason(tr.as_deref())
                        .to_string()
                });
            CtvReconciledOfferDto {
                offer_id: d.get_str("offer_id").unwrap_or("").to_string(),
                parent_attempt_id: d.get_str("parent_attempt_id").unwrap_or("").to_string(),
                source_question_id: d.get_str("source_question_id").unwrap_or("").to_string(),
                complementary_question_id: d
                    .get_str("complementary_question_id")
                    .unwrap_or("")
                    .to_string(),
                content_revision: d.get_str("content_revision").unwrap_or("").to_string(),
                semantic_revision: d.get_str("semantic_revision").unwrap_or("").to_string(),
                state: d.get_str("state").unwrap_or("DISPLAYED").to_string(),
                verification_mode: d
                    .get_str("verification_mode")
                    .unwrap_or("CONCEPTUAL_TRANSFER")
                    .to_string(),
                trigger_reason: tr,
                trigger_cohort: cohort,
                first_displayed_at: d
                    .get_datetime("first_displayed_at")
                    .ok()
                    .map(|dt| dt.to_chrono().to_rfc3339()),
                dismissed_at: d
                    .get_datetime("dismissed_at")
                    .ok()
                    .map(|dt| dt.to_chrono().to_rfc3339()),
            }
        })
        .collect();

    let att_docs: Vec<Document> = state
        .db
        .collection::<Document>("conceptual_transfer_attempts")
        .find(doc! { "user_id": user_id })
        .sort(doc! { "occurred_at": -1 })
        .limit(500)
        .await?
        .try_collect()
        .await?;

    let attempts = att_docs
        .into_iter()
        .map(|d| {
            let tr = d.get_str("trigger_reason").ok().map(str::to_string);
            let cohort = d
                .get_str("trigger_cohort")
                .ok()
                .map(str::to_string)
                .unwrap_or_else(|| {
                    crate::services::ctv_alignment::trigger_cohort_for_reason(tr.as_deref())
                        .to_string()
                });
            let ctv_attempt_id = d.get_str("ctv_attempt_id").unwrap_or("").to_string();
            let parent_id = d.get_str("parent_attempt_id").unwrap_or("").to_string();
            let offer_id = d
                .get_str("offer_id")
                .ok()
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .unwrap_or_else(|| deterministic_offer_id(user_id, &parent_id));
            CtvReconciledAttemptDto {
                ctv_attempt_id,
                offer_id,
                parent_attempt_id: parent_id,
                source_question_id: d.get_str("source_question_id").unwrap_or("").to_string(),
                complementary_question_id: d
                    .get_str("complementary_question_id")
                    .unwrap_or("")
                    .to_string(),
                content_revision: d.get_str("content_revision").unwrap_or("").to_string(),
                verification_mode: d
                    .get_str("verification_mode")
                    .unwrap_or("CONCEPTUAL_TRANSFER")
                    .to_string(),
                verification_timing: d
                    .get_str("verification_timing")
                    .unwrap_or("UNKNOWN_CONDITION")
                    .to_string(),
                is_correct: d.get_bool("is_correct").unwrap_or(false),
                was_previously_exposed: d.get_bool("was_previously_exposed").unwrap_or(false),
                counts_as_independent_transfer: d
                    .get_bool("counts_as_independent_transfer")
                    .unwrap_or(false),
                classification_code: d
                    .get_str("classification_code")
                    .unwrap_or("UNKNOWN_CONDITION")
                    .to_string(),
                trigger_reason: tr,
                trigger_cohort: cohort,
                target_concept_id: d.get_str("target_concept_id").ok().map(str::to_string),
                target_tested_rule: d.get_str("target_tested_rule").ok().map(str::to_string),
                occurred_at: d
                    .get_datetime("occurred_at")
                    .ok()
                    .map(|dt| dt.to_chrono().to_rfc3339())
                    .unwrap_or_default(),
            }
        })
        .collect();

    Ok(CtvUserStateResponse { offers, attempts })
}
