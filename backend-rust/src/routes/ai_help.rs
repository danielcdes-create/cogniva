use crate::{
    error::AppError,
    services::ai_tutor::{
        get_or_generate_ai_assist_with_context, shared_http_client, AiAssistKind, AiAssistResponse,
    },
    AppState,
};
use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    Extension, Json,
};
use bson::{doc, oid::ObjectId, Document};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use unicode_normalization::UnicodeNormalization;

#[derive(Debug, Default, Deserialize)]
pub struct AiHelpQuery {
    pub hint_index: Option<usize>,
    pub user_note: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub struct AiHelpBody {
    pub hint_index: Option<usize>,
    pub user_note: Option<String>,
    pub student_answer: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ContextualLookupRequest {
    pub selected_text: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct NormativeLookupItemDto {
    pub source_id: String,
    pub title: String,
    pub locator: Option<String>,
    pub excerpt: String,
    pub canonical_uri: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ConceptLookupItemDto {
    pub concept_id: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct EncyclopedicSummaryDto {
    pub title: String,
    pub extract: String,
    pub source_url: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ContextualLookupResponse {
    pub question_id: String,
    pub query: String,
    pub disciplina: String,
    pub normative_matches: Vec<NormativeLookupItemDto>,
    pub concept_matches: Vec<ConceptLookupItemDto>,
    pub encyclopedic_summary: Option<EncyclopedicSummaryDto>,
    pub google_search_url: String,
    pub planalto_search_url: String,
    pub jurisprudence_search_url: String,
}

fn fold_search_key(input: &str) -> String {
    input
        .nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .collect::<String>()
        .to_lowercase()
}

fn percent_encode_query(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len() * 2);
    for b in raw.as_bytes() {
        match *b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' => out.push(*b as char),
            b' ' => out.push('+'),
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

fn percent_encode_path_segment(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed == "." {
        return "%2E".to_string();
    }
    if trimmed == ".." {
        return "%2E%2E".to_string();
    }
    let mut out = String::with_capacity(trimmed.len() * 2);
    for b in trimmed.as_bytes() {
        match *b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' => out.push(*b as char),
            b' ' => out.push('_'),
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

fn validate_lookup_selected_text(raw: &str) -> Result<&str, AppError> {
    let trimmed = raw.trim();
    let char_count = trimmed.chars().count();
    if !(1..=240).contains(&char_count) {
        return Err(AppError::BadRequest(
            "Selecione um trecho entre 1 e 240 caracteres para consultar.".into(),
        ));
    }
    Ok(trimmed)
}

pub(crate) async fn fetch_wikimedia_summary(
    api_base_url: &str,
    trimmed_query: &str,
) -> Option<EncyclopedicSummaryDto> {
    let wiki_seg = percent_encode_path_segment(trimmed_query);
    let wiki_url = format!("{}/{wiki_seg}", api_base_url.trim_end_matches('/'));
    tokio::time::timeout(std::time::Duration::from_millis(2200), async {
        let mut resp = shared_http_client()
            .get(&wiki_url)
            .header("User-Agent", "Cogniva/2.4 (Educacao Concursos)")
            .send()
            .await
            .ok()?;
        if !resp.status().is_success() {
            return None;
        }
        let mut body_buf = Vec::with_capacity(4096);
        while let Ok(Some(chunk)) = resp.chunk().await {
            if body_buf.len() + chunk.len() > 64 * 1024 {
                return None;
            }
            body_buf.extend_from_slice(&chunk);
        }
        let val: serde_json::Value = serde_json::from_slice(&body_buf).ok()?;
        let title = val["title"].as_str().unwrap_or("").trim().to_string();
        let extract = val["extract"].as_str().unwrap_or("").trim().to_string();
        let page_url = val["content_urls"]["desktop"]["page"]
            .as_str()
            .filter(|u| u.starts_with("https://"))
            .unwrap_or("https://pt.wikipedia.org")
            .to_string();
        if !title.is_empty()
            && !extract.is_empty()
            && val["type"].as_str() != Some("disambiguation")
        {
            Some(EncyclopedicSummaryDto {
                title,
                extract,
                source_url: page_url,
            })
        } else {
            None
        }
    })
    .await
    .unwrap_or(None)
}
/// Exemplos de `:kind`: `explanation`, `contextual-hint`, `dr-sincero`, `child-friendly-pre`,
/// `child-friendly-post`, `extra-concepts`, `mnemonic`, `plausibility`, `term-lookup`, `term-lookup-post`
pub async fn handle_question_ai_assist(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<crate::security::SessionUser>,
    Path((question_id, kind)): Path<(String, AiAssistKind)>,
    Query(query): Query<AiHelpQuery>,
    body: Bytes,
) -> Result<Json<AiAssistResponse>, AppError> {
    let parsed_body = if body.iter().all(|b| b.is_ascii_whitespace()) {
        AiHelpBody::default()
    } else {
        serde_json::from_slice::<AiHelpBody>(&body)
            .map_err(|_| AppError::BadRequest("Corpo JSON inválido na solicitação de IA.".into()))?
    };

    let hint_index = parsed_body.hint_index.or(query.hint_index);
    let user_note = parsed_body.user_note.or(query.user_note);

    let res = get_or_generate_ai_assist_with_context(
        &state.db,
        &state.config,
        &question_id,
        kind,
        hint_index,
        Some(user.user_id),
        user.is_guest,
        user_note.as_deref(),
        parsed_body.student_answer.as_deref(),
    )
    .await?;

    Ok(Json(res))
}

/// Endpoint `POST /api/v1/questions/:id/lookup` (Custo zero de cota IA):
/// Retorna instantaneamente correspondências nas fontes normativas e conceitos do catálogo,
/// síntese enciclopédica aberta (quando disponível) e URLs seguras de busca em camada (Google/Planalto/Jurisprudência).
pub async fn handle_question_contextual_lookup(
    State(state): State<Arc<AppState>>,
    Extension(user): Extension<crate::security::SessionUser>,
    Path(question_id): Path<String>,
    Json(req): Json<ContextualLookupRequest>,
) -> Result<Json<ContextualLookupResponse>, AppError> {
    let trimmed = validate_lookup_selected_text(&req.selected_text)?;
    crate::services::rate_limit::check_auth_rate_limit(
        &state.db,
        "ctx_lookup",
        &user.user_id.to_string(),
        45,
        60,
    )
    .await?;
    let oid = ObjectId::parse_str(&question_id)?;
    let raw_q = state
        .db
        .collection::<Document>("todasQuestoes")
        .find_one(doc! { "_id": oid })
        .await?
        .ok_or_else(|| AppError::NotFound("Questão não encontrada.".into()))?;
    let questao = crate::services::question_compatibility::decode(raw_q)?;
    let disciplina = if questao.disciplina.trim().is_empty() {
        "Concursos Públicos".to_string()
    } else {
        questao.disciplina.trim().to_string()
    };

    let folded_query = fold_search_key(trimmed);
    let terms: Vec<String> = folded_query
        .split_whitespace()
        .filter(|t| t.len() >= 3)
        .take(6)
        .map(str::to_string)
        .collect();

    let cat = crate::services::question_contracts::catalog_ref();
    let norm_sources = crate::services::catalog_runtime::effective_normative_sources(&cat);
    let concepts = crate::services::catalog_runtime::effective_concepts(&cat);

    let mut normative_matches = Vec::new();
    for ns in &norm_sources {
        if ns["status"].as_str() != Some("ACTIVE") {
            continue;
        }
        let title = ns["title"].as_str().unwrap_or("");
        let locator = ns["locator"].as_str();
        let excerpt = ns["excerpt"].as_str().unwrap_or("");
        let uri = ns["canonical_uri"].as_str().unwrap_or("");
        let hay = fold_search_key(&format!("{title} {} {excerpt}", locator.unwrap_or("")));
        let hit = hay.contains(&folded_query)
            || (!terms.is_empty()
                && terms.iter().filter(|t| hay.contains(t.as_str())).count() >= terms.len().min(2));
        if hit {
            normative_matches.push(NormativeLookupItemDto {
                source_id: ns["source_id"].as_str().unwrap_or("").to_string(),
                title: title.to_string(),
                locator: locator.map(str::to_string),
                excerpt: excerpt.to_string(),
                canonical_uri: uri.to_string(),
            });
            if normative_matches.len() >= 4 {
                break;
            }
        }
    }

    let mut concept_matches = Vec::new();
    for c in &concepts {
        if c["status"].as_str() != Some("ACTIVE") {
            continue;
        }
        let label = c["label"].as_str().unwrap_or("");
        let hay = fold_search_key(label);
        if hay.contains(&folded_query)
            || (!terms.is_empty()
                && terms.iter().filter(|t| hay.contains(t.as_str())).count() >= terms.len().min(2))
        {
            concept_matches.push(ConceptLookupItemDto {
                concept_id: c["concept_id"].as_str().unwrap_or("").to_string(),
                label: label.to_string(),
            });
            if concept_matches.len() >= 4 {
                break;
            }
        }
    }

    // Síntese enciclopédica rápida via Wikimedia PT REST API (timeout total de 2.2s e teto de 64 KB no corpo)
    let encyclopedic_summary =
        fetch_wikimedia_summary("https://pt.wikipedia.org/api/rest_v1/page/summary", trimmed).await;

    let contextual_q = format!("{trimmed} {disciplina}");
    let google_search_url = format!(
        "https://www.google.com/search?hl=pt-BR&q={}",
        percent_encode_query(&contextual_q)
    );
    let planalto_search_url = format!(
        "https://www.google.com/search?hl=pt-BR&q={}",
        percent_encode_query(&format!("site:planalto.gov.br {trimmed}"))
    );
    let jurisprudence_search_url = format!(
        "https://www.google.com/search?hl=pt-BR&q={}",
        percent_encode_query(&format!("(STF OR STJ) {trimmed}"))
    );

    Ok(Json(ContextualLookupResponse {
        question_id,
        query: trimmed.to_string(),
        disciplina,
        normative_matches,
        concept_matches,
        encyclopedic_summary,
        google_search_url,
        planalto_search_url,
        jurisprudence_search_url,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{routing::get, Router};
    use serde_json::json;

    #[test]
    fn path_segment_encoding_search_folding_and_query_bounds_prevent_traversal_and_unicode_drift() {
        assert_eq!(percent_encode_path_segment("."), "%2E");
        assert_eq!(percent_encode_path_segment(" .. "), "%2E%2E");
        assert_eq!(percent_encode_path_segment("a/b"), "a%2Fb");
        assert_eq!(
            percent_encode_path_segment("Princípio da Legalidade"),
            "Princ%C3%ADpio_da_Legalidade"
        );
        assert_eq!(
            fold_search_key("Administração Pública — Ação Direta"),
            "administracao publica — acao direta"
        );

        assert!(validate_lookup_selected_text("   ").is_err());
        assert_eq!(validate_lookup_selected_text(" Selic ").unwrap(), "Selic");
        let exact_240 = "á".repeat(240);
        assert_eq!(
            validate_lookup_selected_text(&exact_240)
                .unwrap()
                .chars()
                .count(),
            240
        );
        let too_long_241 = "á".repeat(241);
        assert!(validate_lookup_selected_text(&too_long_241).is_err());
    }

    #[tokio::test]
    async fn wikimedia_summary_filters_disambiguation_caps_at_64kib_and_blocks_non_https_page_url()
    {
        let mock = Router::new().route(
            "/summary/:slug",
            get(|Path(slug): Path<String>| async move {
                match slug.as_str() {
                    "Ambiguo" => axum::Json(json!({
                        "type": "disambiguation",
                        "title": "Ambiguo",
                        "extract": "Pode referir-se a múltiplos conceitos.",
                        "content_urls": { "desktop": { "page": "https://pt.wikipedia.org/wiki/Ambiguo" } }
                    }))
                    .into_response(),
                    "Gigante" => {
                        let huge = "X".repeat(66 * 1024);
                        axum::Json(json!({
                            "type": "standard",
                            "title": "Gigante",
                            "extract": huge,
                            "content_urls": { "desktop": { "page": "https://pt.wikipedia.org/wiki/Gigante" } }
                        }))
                        .into_response()
                    }
                    "HttpInseguro" => axum::Json(json!({
                        "type": "standard",
                        "title": "Taxa Selic",
                        "extract": "Taxa básica de juros da economia brasileira.",
                        "content_urls": { "desktop": { "page": "http://evil.example/selic" } }
                    }))
                    .into_response(),
                    _ => axum::Json(json!({
                        "type": "standard",
                        "title": "Banco Central do Brasil",
                        "extract": "Autarquia federal integrante do Sistema Financeiro Nacional.",
                        "content_urls": { "desktop": { "page": "https://pt.wikipedia.org/wiki/Banco_Central_do_Brasil" } }
                    }))
                    .into_response(),
                }
            }),
        );
        use axum::response::IntoResponse;

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let srv = tokio::spawn(async move { axum::serve(listener, mock).await.unwrap() });
        let base = format!("http://{addr}/summary");

        assert!(fetch_wikimedia_summary(&base, "Ambiguo").await.is_none());
        assert!(fetch_wikimedia_summary(&base, "Gigante").await.is_none());

        let sanitized = fetch_wikimedia_summary(&base, "HttpInseguro")
            .await
            .expect("valid summary with fallback https url");
        assert_eq!(sanitized.title, "Taxa Selic");
        assert_eq!(sanitized.source_url, "https://pt.wikipedia.org");

        let valid = fetch_wikimedia_summary(&base, "Banco Central")
            .await
            .expect("valid summary");
        assert_eq!(
            valid.source_url,
            "https://pt.wikipedia.org/wiki/Banco_Central_do_Brasil"
        );
        srv.abort();
    }
}
