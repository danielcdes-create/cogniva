//! Contabilidade operacional e financeira auditável do Motor CTV (Seção 5 — A12, A13).
//!
//! Distingue solicitação, chamada real ao provedor, item persistido, entrega ao estudante
//! (online vs. cache offline reconciliado) e verificação efetivamente concluída (respondida),
//! agregando o período completo sem cortes silenciosos de volume.

use crate::{
    error::AppError,
    services::{
        concept_mastery_engine::{simulate_ai_reuse_economy, ReuseEconomySimulation},
        ctv_alignment::trigger_cohort_for_reason,
        learning_analytics::{
            AdminAiCtvMetricsResponse, FormalKpiDto, DEFAULT_REFERENCE_COST_PER_GENERATION_USD,
        },
        question_contracts, question_storage,
    },
    AppState,
};
use bson::{doc, Document};
use chrono::{DateTime, Utc};
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};
use serde_json::json;

#[derive(Debug, Clone, Default, Deserialize)]
pub struct AdminCtvMetricsQuery {
    pub reference_cost_usd: Option<f64>,
    pub from: Option<String>,
    pub to: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct OperationalCostBreakdownDto {
    pub period_start: Option<String>,
    pub period_end: Option<String>,
    pub full_period_aggregated: bool,
    pub total_requests_evaluated: usize,
    pub search_inconclusive_count: usize,
    pub requests_with_student_delivery: usize,
    pub reuse_without_call_deliveries: usize,
    pub reuse_without_call_rate_pct: Option<f64>,
    pub offline_cache_deliveries_reconciled: usize,
    pub real_provider_calls: usize,
    pub duplicates_barred_after_call: usize,
    pub avoided_generation_requests: usize,
    pub persisted_ai_items_count: usize,
    pub completed_verifications_count: usize,
    pub total_attributable_cost_usd: f64,
    pub cost_per_completed_verification_raw_usd: Option<f64>,
    pub cost_per_completed_verification_rounded_usd: Option<f64>,
    pub cost_per_completed_verification_display_pt: String,
    pub cost_is_estimated: bool,
    pub cost_source_label_pt: &'static str,
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct CtvAdaptiveCohortDto {
    pub cohort_id: &'static str,
    pub label_pt: &'static str,
    pub eligibility_scope_pt: &'static str,
    pub offers_count: usize,
    pub dismissals_count: usize,
    pub responses_count: usize,
    pub independent_eligible_responses: usize,
    pub independent_correct_count: usize,
    pub independent_success_rate_pct: Option<f64>,
    pub summary_pt: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct CtvAdaptiveSelectionReportDto {
    pub difficulty_triggered: CtvAdaptiveCohortDto,
    pub periodic_sampling: CtvAdaptiveCohortDto,
    pub unknown_condition_count: usize,
}

fn round2_opt(v: Option<f64>) -> Option<f64> {
    v.filter(|x| x.is_finite())
        .map(|x| (x * 100.0).round() / 100.0)
}

fn round4_opt(v: Option<f64>) -> Option<f64> {
    v.filter(|x| x.is_finite())
        .map(|x| (x * 10_000.0).round() / 10_000.0)
}

fn ratio_pct(num: f64, den: f64) -> Option<f64> {
    if den > 0.0 {
        round2_opt(Some(100.0 * num / den))
    } else {
        None
    }
}

#[allow(clippy::too_many_arguments)]
pub fn build_adaptive_cohort_dto(
    cohort_id: &'static str,
    label_pt: &'static str,
    eligibility_scope_pt: &'static str,
    offers: usize,
    dismissals: usize,
    responses: usize,
    indep_eligible: usize,
    indep_correct: usize,
) -> CtvAdaptiveCohortDto {
    let rate = ratio_pct(indep_correct as f64, indep_eligible as f64);
    let summary_pt = if indep_eligible == 0 {
        if dismissals > 0 {
            format!("Sem respostas independentes elegíveis ({dismissals} dispensa(s) fora do denominador).")
        } else {
            "Sem respostas independentes elegíveis no período.".to_string()
        }
    } else {
        format!(
            "{indep_correct}/{indep_eligible} verificações independentes corretas ({:.1}%){}",
            100.0 * (indep_correct as f64) / (indep_eligible as f64),
            if dismissals > 0 {
                format!(" · {dismissals} oferta(s) dispensada(s)")
            } else {
                String::new()
            }
        )
    };
    CtvAdaptiveCohortDto {
        cohort_id,
        label_pt,
        eligibility_scope_pt,
        offers_count: offers,
        dismissals_count: dismissals,
        responses_count: responses,
        independent_eligible_responses: indep_eligible,
        independent_correct_count: indep_correct,
        independent_success_rate_pct: rate,
        summary_pt,
    }
}

/// Cálculo puro do custo por verificação concluída (Seção 5 / A13):
/// divide o custo total atribuível às chamadas (incluindo rejeições e abandonos)
/// pelas verificações efetivamente respondidas (`completed_verifications_count`).
pub fn compute_cost_per_completed_verification(
    total_attributable_cost_usd: f64,
    completed_verifications_count: usize,
    cost_is_estimated: bool,
) -> (Option<f64>, Option<f64>, String) {
    if completed_verifications_count == 0 {
        return (None, None, "sem conclusões".to_string());
    }
    let raw = total_attributable_cost_usd / (completed_verifications_count as f64);
    let rounded = round4_opt(Some(raw));
    let suffix = if cost_is_estimated { " (estimado)" } else { "" };
    let display = format!("US$ {:.4}{suffix}", raw);
    (Some(raw), rounded, display)
}

fn parse_optional_bound(raw: Option<&str>) -> Result<Option<DateTime<Utc>>, AppError> {
    let Some(s) = raw.map(str::trim).filter(|s| !s.is_empty()) else {
        return Ok(None);
    };
    let dt = DateTime::parse_from_rfc3339(s)
        .map_err(|_| AppError::BadRequest("Parâmetro temporal deve estar em RFC 3339".into()))?
        .with_timezone(&Utc);
    Ok(Some(dt))
}

pub async fn compute_admin_ctv_metrics_with_window(
    state: &AppState,
    query: AdminCtvMetricsQuery,
) -> Result<AdminAiCtvMetricsResponse, AppError> {
    let ref_cost = query
        .reference_cost_usd
        .filter(|v| v.is_finite() && *v >= 0.0)
        .or_else(|| {
            std::env::var("XAI_ESTIMATED_COST_PER_CTV_USD")
                .ok()
                .and_then(|s| s.trim().parse::<f64>().ok())
                .filter(|v| v.is_finite() && *v >= 0.0)
        })
        .unwrap_or(DEFAULT_REFERENCE_COST_PER_GENERATION_USD);

    let from_dt = parse_optional_bound(query.from.as_deref())?;
    let to_dt = parse_optional_bound(query.to.as_deref())?;

    let mut audit_filter = doc! {};
    let mut attempt_filter = doc! {};
    let mut offer_filter =
        doc! { "first_displayed_at": { "$ne": bson::Bson::Null, "$exists": true } };
    let mut question_dmeta_filter =
        doc! { "diagnostic_meta": { "$exists": true, "$ne": bson::Bson::Null } };

    if from_dt.is_some() || to_dt.is_some() {
        let mut range = doc! {};
        let mut iso_range = doc! {};
        if let Some(f) = from_dt {
            range.insert("$gte", bson::DateTime::from_chrono(f));
            iso_range.insert("$gte", f.to_rfc3339());
        }
        if let Some(t) = to_dt {
            range.insert("$lte", bson::DateTime::from_chrono(t));
            iso_range.insert("$lte", t.to_rfc3339());
        }
        audit_filter.insert("recorded_at", range.clone());
        attempt_filter.insert("occurred_at", range.clone());
        offer_filter.insert("first_displayed_at", range);
        question_dmeta_filter.insert("diagnostic_meta.generated_at", iso_range);
    }

    // Varredura em streaming de TODO o período filtrado sem cortes silenciosos de 5.000 ou 200 registros!
    let mut audit_cursor = state
        .db
        .collection::<Document>("ai_generation_audit")
        .find(audit_filter)
        .await?;

    let mut earliest_event: Option<DateTime<Utc>> = from_dt;
    let mut latest_event: Option<DateTime<Utc>> = to_dt;

    let mut total_requests_evaluated = 0usize;
    let mut search_inconclusive_count = 0usize;
    let mut requests_with_student_delivery = 0usize;
    let mut reuse_without_call_deliveries = 0usize;
    let mut offline_cache_deliveries_reconciled = 0usize;
    let mut real_provider_calls = 0usize;
    let mut ai_approved_generations = 0usize;
    let mut duplicates_barred_after_call = 0usize;
    let mut persisted_ai_items_count = 0usize;
    let mut total_attributable_cost_usd = 0.0f64;
    let mut saw_estimated_cost = false;
    let mut saw_provider_reported_cost = false;

    while let Some(doc) = audit_cursor.try_next().await? {
        total_requests_evaluated += 1;
        if let Ok(rdt) = doc.get_datetime("recorded_at") {
            let cdt = rdt.to_chrono();
            earliest_event = Some(earliest_event.map_or(cdt, |p| p.min(cdt)));
            latest_event = Some(latest_event.map_or(cdt, |p| p.max(cdt)));
        }
        let outcome = doc.get_str("outcome").unwrap_or("");
        let has_explicit_fields = doc.contains_key("provider_calls");

        if has_explicit_fields {
            let calls = doc.get_i32("provider_calls").unwrap_or(0).max(0) as usize;
            let delivered = doc.get_bool("delivered_to_student").unwrap_or(false);
            let dup_barred = doc.get_bool("duplicate_barred").unwrap_or(false);
            let persisted = doc.get_bool("persisted_item").unwrap_or(false);
            let channel = doc.get_str("delivery_channel").unwrap_or("");
            let cost = doc.get_f64("estimated_cost_usd").unwrap_or(0.0);
            if calls > 0 {
                if doc.get_str("cost_source").ok() == Some("PROVIDER_REPORTED") {
                    saw_provider_reported_cost = true;
                } else {
                    saw_estimated_cost = true;
                }
            }

            real_provider_calls += calls;
            total_attributable_cost_usd += cost;
            if persisted {
                persisted_ai_items_count += 1;
            }
            if dup_barred {
                duplicates_barred_after_call += 1;
            }
            if outcome == "SEARCH_INCONCLUSIVE" {
                search_inconclusive_count += 1;
            }
            if outcome == "GENERATED_AND_APPROVED" {
                ai_approved_generations += 1;
            }
            if channel == "OFFLINE_CACHE_RECONCILED" {
                offline_cache_deliveries_reconciled += 1;
            } else if delivered {
                requests_with_student_delivery += 1;
                if calls == 0 {
                    reuse_without_call_deliveries += 1;
                }
            }
        } else {
            // Compatibilidade retroativa com registros 2.4.4 anteriores (corrigindo DUPLICATE_PREVENTED!)
            match outcome {
                "REUSED_FROM_DB" | "REUSED_CONCURRENT_GENERATION" => {
                    requests_with_student_delivery += 1;
                    reuse_without_call_deliveries += 1;
                }
                "GENERATED_AND_APPROVED" => {
                    requests_with_student_delivery += 1;
                    real_provider_calls += 1;
                    ai_approved_generations += 1;
                    persisted_ai_items_count += 1;
                    saw_estimated_cost = true;
                    total_attributable_cost_usd +=
                        doc.get_f64("estimated_cost_usd").unwrap_or(ref_cost);
                }
                "QUEUED_HUMAN_REVIEW" => {
                    real_provider_calls += 1;
                    persisted_ai_items_count += 1;
                    saw_estimated_cost = true;
                    total_attributable_cost_usd +=
                        doc.get_f64("estimated_cost_usd").unwrap_or(ref_cost);
                }
                "VALIDATION_REJECTED" => {
                    real_provider_calls += 1;
                    saw_estimated_cost = true;
                    total_attributable_cost_usd +=
                        doc.get_f64("estimated_cost_usd").unwrap_or(ref_cost);
                }
                "DUPLICATE_PREVENTED" => {
                    // Houve 1 chamada real e 1 duplicata barrada -> ZERO chamadas evitadas!
                    real_provider_calls += 1;
                    duplicates_barred_after_call += 1;
                    saw_estimated_cost = true;
                    if doc.get_str("question_id").is_ok() {
                        requests_with_student_delivery += 1;
                    }
                    total_attributable_cost_usd +=
                        doc.get_f64("estimated_cost_usd").unwrap_or(ref_cost);
                }
                "SEARCH_INCONCLUSIVE" => {
                    search_inconclusive_count += 1;
                }
                _ => {}
            }
        }
    }
    let any_estimated_cost = saw_estimated_cost || !saw_provider_reported_cost;

    // Contagem completa de verificações concluídas (respondidas) e recortes adaptativos na janela!
    let mut att_cursor = state
        .db
        .collection::<Document>("conceptual_transfer_attempts")
        .find(attempt_filter)
        .projection(doc! { "response_ack": 0 })
        .await?;

    let mut completed_verifications_count = 0usize;
    let mut diff_resp = 0usize;
    let mut diff_indep_elig = 0usize;
    let mut diff_indep_ok = 0usize;
    let mut per_resp = 0usize;
    let mut per_indep_elig = 0usize;
    let mut per_indep_ok = 0usize;
    let mut unknown_condition_count = 0usize;

    while let Some(att) = att_cursor.try_next().await? {
        completed_verifications_count += 1;
        if let Ok(odt) = att.get_datetime("occurred_at") {
            let cdt = odt.to_chrono();
            earliest_event = Some(earliest_event.map_or(cdt, |p| p.min(cdt)));
            latest_event = Some(latest_event.map_or(cdt, |p| p.max(cdt)));
        }
        let code = att.get_str("classification_code").unwrap_or("");
        if code == "UNKNOWN_CONDITION" || !att.contains_key("verification_timing") {
            unknown_condition_count += 1;
        }
        let cohort = att
            .get_str("trigger_cohort")
            .ok()
            .unwrap_or_else(|| trigger_cohort_for_reason(att.get_str("trigger_reason").ok()));
        let vmode = att
            .get_str("verification_mode")
            .unwrap_or("CONCEPTUAL_TRANSFER");
        let indep = att
            .get_bool("counts_as_independent_transfer")
            .unwrap_or_else(|_| {
                att.get_str("verification_timing").ok() == Some("PRE_FEEDBACK")
                    && !att.get_bool("was_previously_exposed").unwrap_or(false)
            })
            && vmode == "CONCEPTUAL_TRANSFER";
        let ok = att.get_bool("is_correct").unwrap_or(false);
        if cohort == "PERIODIC_SAMPLING" {
            per_resp += 1;
            if indep {
                per_indep_elig += 1;
                if ok {
                    per_indep_ok += 1;
                }
            }
        } else {
            diff_resp += 1;
            if indep {
                diff_indep_elig += 1;
                if ok {
                    diff_indep_ok += 1;
                }
            }
        }
    }

    let mut offer_cursor = state
        .db
        .collection::<Document>("ctv_offers")
        .find(offer_filter)
        .projection(doc! { "cached_response": 0 })
        .await?;
    let mut diff_offers = 0usize;
    let mut diff_dismissals = 0usize;
    let mut per_offers = 0usize;
    let mut per_dismissals = 0usize;
    while let Some(off) = offer_cursor.try_next().await? {
        let cohort = off
            .get_str("trigger_cohort")
            .ok()
            .unwrap_or_else(|| trigger_cohort_for_reason(off.get_str("trigger_reason").ok()));
        let is_dism = off.get_str("state").ok() == Some("DISMISSED");
        if cohort == "PERIODIC_SAMPLING" {
            per_offers += 1;
            if is_dism {
                per_dismissals += 1;
            }
        } else {
            diff_offers += 1;
            if is_dism {
                diff_dismissals += 1;
            }
        }
    }
    diff_offers = diff_offers.max(diff_resp + diff_dismissals);
    per_offers = per_offers.max(per_resp + per_dismissals);

    // Varredura completa das questões CTV no banco (sem limite de 200 nos totais; apenas a lista visual recente é limitada a 25!)
    let questions_coll = question_storage::collection(&state.db, "todasQuestoes");
    let mut q_cursor = questions_coll
        .find(question_dmeta_filter)
        .projection(doc! { "diagnostic_meta": 1, "question": 1 })
        .sort(doc! { "_id": -1 })
        .await?;

    let mut ai_generated_total = 0usize;
    let mut auto_approved_count = 0usize;
    let mut pending_review_count = 0usize;
    let mut suspended_count = 0usize;
    let mut total_reuse_usage_sum = 0i64;
    let mut recent_ctv_questions = Vec::new();
    const RECENT_PREVIEW_LIMIT: usize = 25;

    while let Some(q) = q_cursor.try_next().await? {
        let Ok(dmeta) = q.get_document("diagnostic_meta") else {
            continue;
        };
        let origin = dmeta.get_str("generation_origin").unwrap_or("AI_ON_DEMAND");
        let vstat = dmeta
            .get_str("validation_status")
            .unwrap_or("PENDING_HUMAN_REVIEW");
        let qstat = dmeta.get_str("quality_status").unwrap_or("MONITORING");
        let usage = dmeta
            .get_i64("usage_count")
            .or_else(|_| dmeta.get_i32("usage_count").map(i64::from))
            .unwrap_or(0);
        if origin == "AI_ON_DEMAND" {
            ai_generated_total += 1;
            total_reuse_usage_sum += usage;
        }
        match vstat {
            "AUTO_APPROVED" | "VALIDATED" | "HUMAN_CONFIRMED" => auto_approved_count += 1,
            "PENDING_HUMAN_REVIEW" => pending_review_count += 1,
            _ => {}
        }
        if qstat == "SUSPENDED" {
            suspended_count += 1;
        }
        if recent_ctv_questions.len() < RECENT_PREVIEW_LIMIT {
            recent_ctv_questions.push(json!({
                "question_id": q.get_object_id("_id").map(|id| id.to_hex()).unwrap_or_default(),
                "question_snippet": q.get_str("question").unwrap_or("").chars().take(180).collect::<String>(),
                "subject_id": dmeta.get_str("subject_id").unwrap_or(""),
                "topic_id": dmeta.get_str("topic_id").unwrap_or(""),
                "concept_ids": dmeta.get_array("concept_ids").cloned().unwrap_or_default(),
                "diagnostic_purpose": dmeta.get_str("diagnostic_purpose").unwrap_or(""),
                "generation_origin": origin,
                "validation_status": vstat,
                "quality_status": qstat,
                "usage_count": usage,
                "model_identifier": dmeta.get_str("model_identifier").ok(),
            }));
        }
    }

    let avoided_requests = reuse_without_call_deliveries;
    let reuse_rate_pct = ratio_pct(
        reuse_without_call_deliveries as f64,
        requests_with_student_delivery as f64,
    );
    let avg_reuse_per_generated = if ai_generated_total > 0 {
        round2_opt(Some(
            (total_reuse_usage_sum as f64) / (ai_generated_total as f64),
        ))
    } else {
        None
    };

    let total_cost_clean = (total_attributable_cost_usd * 1_000_000.0).round() / 1_000_000.0;
    let (cost_raw, cost_rounded, cost_display_pt) = compute_cost_per_completed_verification(
        total_cost_clean,
        completed_verifications_count,
        any_estimated_cost,
    );
    let estimated_cost_avoidance = ((avoided_requests as f64) * ref_cost * 100.0).round() / 100.0;

    let period_label = match (&earliest_event, &latest_event) {
        (Some(s), Some(e)) => format!("{} a {}", s.to_rfc3339(), e.to_rfc3339()),
        _ => "Período integral auditado (sem eventos registrados)".to_string(),
    };

    let operational_kpis = vec![
        FormalKpiDto {
            kpi_id: "QUESTION_REUSE_RATE",
            label_pt: "Atendimento por Reuso sem Chamada",
            value: reuse_rate_pct,
            unit: "%",
            numerator: reuse_without_call_deliveries as f64,
            denominator: requests_with_student_delivery as f64,
            sample_size_n: requests_with_student_delivery,
            formal_definition: "Solicitações atendidas com item elegível do banco e zero chamadas ao provedor divididas pelo total de solicitações com entrega no período.",
            eligibility_criteria: "Solicitações CTV online com entrega efetiva ao aluno (entregas offline aparecem separadas até reconciliação).",
            missing_data_rule: "Retorna nulo quando ainda não ocorreram entregas no período.",
            granularity: "Global do acervo",
            reference_period: "Período integral selecionado (sem corte de volume)",
            interpretive_limitation: "Entregas por cache offline são rastreadas separadamente até sincronização.",
        },
        FormalKpiDto {
            kpi_id: "AI_GENERATION_RATE",
            label_pt: "Taxa de Atendimento por Nova Geração IA",
            value: ratio_pct(
                ai_approved_generations as f64,
                requests_with_student_delivery as f64,
            ),
            unit: "%",
            numerator: ai_approved_generations as f64,
            denominator: requests_with_student_delivery as f64,
            sample_size_n: requests_with_student_delivery,
            formal_definition: "Percentual de solicitações com entrega que exigiram nova geração sob demanda persistida em todasQuestoes.",
            eligibility_criteria: "Solicitações CTV com entrega concluída no período.",
            missing_data_rule: "Retorna nulo quando n = 0.",
            granularity: "Global do acervo",
            reference_period: "Período integral selecionado",
            interpretive_limitation: "Em conceitos sem estoque inicial, a taxa começa mais alta e cai com o reuso.",
        },
        FormalKpiDto {
            kpi_id: "AVG_REUSE_PER_GENERATED",
            label_pt: "Média de Reutilizações por Questão Gerada",
            value: avg_reuse_per_generated,
            unit: "usos/questão",
            numerator: total_reuse_usage_sum as f64,
            denominator: ai_generated_total as f64,
            sample_size_n: ai_generated_total,
            formal_definition: "Média acumulada de verificações respondidas por questão complementar gerada por IA em todasQuestoes.",
            eligibility_criteria: "Total integral de questões com diagnostic_meta.generation_origin == AI_ON_DEMAND.",
            missing_data_rule: "Retorna nulo enquanto não houver questões geradas por IA no banco.",
            granularity: "Acervo CTV permanente completo",
            reference_period: "Estoque integral em todasQuestoes",
            interpretive_limitation: "Itens recém-criados ainda sem segundo aluno reduzem temporariamente a média.",
        },
        FormalKpiDto {
            kpi_id: "AI_COST_PER_VERIFICATION",
            label_pt: "Custo Estimado por Verificação Concluída",
            value: cost_rounded,
            unit: "USD",
            numerator: total_cost_clean,
            denominator: completed_verifications_count as f64,
            sample_size_n: completed_verifications_count,
            formal_definition: "Custo total atribuível às chamadas reais ao provedor (incluindo saídas rejeitadas, duplicatas barradas e abandonos) dividido pelas verificações efetivamente respondidas na coorte.",
            eligibility_criteria: "Verificações CTV efetivamente respondidas na janela explícita.",
            missing_data_rule: "Exibe 'sem conclusões' (nulo) quando nenhuma verificação foi respondida no período.",
            granularity: "Coorte / janela explícita",
            reference_period: "Período integral selecionado",
            interpretive_limitation: "Custo estimado a partir da referência configurada por chamada, não fatura contábil auditada.",
        },
        FormalKpiDto {
            kpi_id: "GENERATED_APPROVAL_RATE",
            label_pt: "Taxa de Aprovação Automática de Chamadas IA",
            value: ratio_pct(ai_approved_generations as f64, real_provider_calls as f64),
            unit: "%",
            numerator: ai_approved_generations as f64,
            denominator: real_provider_calls as f64,
            sample_size_n: real_provider_calls,
            formal_definition: "Proporção das chamadas reais realizadas ao provedor que produziram questão inédita aprovada e persistida.",
            eligibility_criteria: "Todas as chamadas reais ao provedor LLM no período.",
            missing_data_rule: "Retorna nulo quando nenhuma chamada foi realizada.",
            granularity: "Pipeline de geração CTV",
            reference_period: "Período integral selecionado",
            interpretive_limitation: "Aprovação automática para CTV não substitui revisão humana para simulados oficiais.",
        },
        FormalKpiDto {
            kpi_id: "DUPLICATE_PREVENTION_RATE",
            label_pt: "Duplicatas Barradas Após Chamada IA",
            value: ratio_pct(duplicates_barred_after_call as f64, real_provider_calls as f64),
            unit: "%",
            numerator: duplicates_barred_after_call as f64,
            denominator: real_provider_calls as f64,
            sample_size_n: real_provider_calls,
            formal_definition: "Proporção de chamadas reais cuja saída duplicou item existente no acervo e foi barrada antes da persistência.",
            eligibility_criteria: "Chamadas reais avaliadas pelo deduplicador JCS/Jaccard.",
            missing_data_rule: "Retorna nulo quando n = 0.",
            granularity: "Pipeline de deduplicação pós-chamada",
            reference_period: "Período integral selecionado",
            interpretive_limitation: "Uma duplicata pós-chamada consumiu 1 chamada real e nunca incrementa chamadas evitadas.",
        },
        FormalKpiDto {
            kpi_id: "AVOIDED_GENERATION_REQUESTS",
            label_pt: "Chamadas Evitadas por Reuso Prévio",
            value: Some(avoided_requests as f64),
            unit: "chamadas",
            numerator: avoided_requests as f64,
            denominator: 1.0,
            sample_size_n: requests_with_student_delivery,
            formal_definition: "Solicitações atendidas diretamente pelo acervo (ou trava de concorrência) com zero chamadas ao provedor.",
            eligibility_criteria: "Apenas atendimentos com provider_calls == 0 e entrega efetiva (exclui DUPLICATE_PREVENTED).",
            missing_data_rule: "Zero quando não há entregas por reuso sem chamada.",
            granularity: "Global operacional",
            reference_period: "Período integral selecionado",
            interpretive_limitation: "Representa chamadas não disparadas graças ao filtro Database First.",
        },
        FormalKpiDto {
            kpi_id: "ESTIMATED_COST_AVOIDANCE",
            label_pt: "Custo Evitado Estimado (Referência)",
            value: Some(estimated_cost_avoidance),
            unit: "USD",
            numerator: avoided_requests as f64,
            denominator: 1.0,
            sample_size_n: avoided_requests,
            formal_definition: "Estimativa obtida multiplicando apenas as chamadas efetivamente evitadas pelo custo unitário de referência.",
            eligibility_criteria: "Chamadas evitadas sem invocação do provedor.",
            missing_data_rule: "Zero quando nenhuma chamada foi evitada.",
            granularity: "Global operacional",
            reference_period: "Período integral selecionado",
            interpretive_limitation: "Estimativa operacional referencial separada do custo real por verificação concluída.",
        },
    ];

    let default_reuse_pct = reuse_rate_pct.unwrap_or(75.0);
    let default_simulation: ReuseEconomySimulation =
        simulate_ai_reuse_economy(10_000, default_reuse_pct, ref_cost);

    let adaptive_selection = CtvAdaptiveSelectionReportDto {
        difficulty_triggered: build_adaptive_cohort_dto(
            "DIFFICULTY_DETECTED",
            "Disparadas por dificuldade ou fragilidade detectada",
            " Tentativas com chute, baixa confiança, erro de alta confiança, reconhecimento superficial ou inconsistência",
            diff_offers,
            diff_dismissals,
            diff_resp,
            diff_indep_elig,
            diff_indep_ok,
        ),
        periodic_sampling: build_adaptive_cohort_dto(
            "PERIODIC_SAMPLING",
            "Sorteadas para acompanhamento periódico / linha de base",
            "Amostra determinística restrita a unidades elegíveis com histórico consistente ou acertos sem fragilidade",
            per_offers,
            per_dismissals,
            per_resp,
            per_indep_elig,
            per_indep_ok,
        ),
        unknown_condition_count,
    };

    let operational_breakdown = OperationalCostBreakdownDto {
        period_start: earliest_event.map(|d| d.to_rfc3339()),
        period_end: latest_event.map(|d| d.to_rfc3339()),
        full_period_aggregated: true,
        total_requests_evaluated,
        search_inconclusive_count,
        requests_with_student_delivery,
        reuse_without_call_deliveries,
        reuse_without_call_rate_pct: reuse_rate_pct,
        offline_cache_deliveries_reconciled,
        real_provider_calls,
        duplicates_barred_after_call,
        avoided_generation_requests: avoided_requests,
        persisted_ai_items_count,
        completed_verifications_count,
        total_attributable_cost_usd: total_cost_clean,
        cost_per_completed_verification_raw_usd: cost_raw,
        cost_per_completed_verification_rounded_usd: cost_rounded,
        cost_per_completed_verification_display_pt: cost_display_pt,
        cost_is_estimated: any_estimated_cost,
        cost_source_label_pt: if any_estimated_cost {
            "Custo estimado a partir de referência unitária configurada"
        } else {
            "Custo reportado pelo provedor"
        },
    };

    Ok(AdminAiCtvMetricsResponse {
        generated_at: Utc::now().to_rfc3339(),
        reference_period_label_pt: period_label,
        reference_cost_per_generation_usd: ref_cost,
        operational_kpis,
        operational_breakdown,
        adaptive_selection,
        ctv_bank_summary: json!({
            "ai_generated_total": ai_generated_total,
            "auto_approved": auto_approved_count,
            "pending_human_review": pending_review_count,
            "suspended": suspended_count,
            "recent_preview_limit": RECENT_PREVIEW_LIMIT,
            "total_aggregated_without_truncation": true,
            "catalog_version": question_contracts::catalog_ref()["catalog_version"],
        }),
        recent_ctv_questions,
        default_simulation,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a13_cost_per_completed_verification_divides_0_09_by_2_into_exact_0_045_and_handles_zero() {
        let (raw, rounded, display) = compute_cost_per_completed_verification(0.09, 2, true);
        assert!((raw.unwrap() - 0.045).abs() < 1e-12);
        assert!((rounded.unwrap() - 0.045).abs() < 1e-12);
        assert!(display.contains("0.0450"));
        assert!(display.contains("estimado"));

        let (z_raw, z_round, z_disp) = compute_cost_per_completed_verification(0.09, 0, true);
        assert!(z_raw.is_none());
        assert!(z_round.is_none());
        assert_eq!(z_disp, "sem conclusões");
    }
}
