use crate::{
    config::AppConfig,
    error::AppError,
    models::question::{PlausibilityAnalysis, QuestionDocument},
};
use bson::{doc, oid::ObjectId};
use mongodb::Database;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;
use std::sync::{Arc, OnceLock};
use tokio::sync::Mutex;

/// Tipos de Assistência Pedagógica por IA (100% compatíveis com os campos cacheados pelo AFTBot.py)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum AiAssistKind {
    Explanation,       // campo `explanation`
    ContextualHint,    // array `contextualHint`
    DrSincero,         // campo `drSincero`
    ChildFriendlyPre,  // campo `ajudaInfantil`
    ChildFriendlyPost, // campo `explicacaoInfantil`
    ExtraConcepts,     // campo `explanationExtra`
    Mnemonic,          // campo `mnemonico`
    Plausibility,      // objeto `plausibilityAnalysis`
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiAssistResponse {
    pub question_id: String,
    pub kind: AiAssistKind,
    pub content_markdown: String,
    pub from_cache: bool,
    pub plausibility_structured: Option<PlausibilityAnalysis>,
}

const EXPLANATION_SYSTEM_INSTRUCTION: &str = r#"Você é um especialista em concursos públicos, com vasta experiência em elaborar materiais didáticos. Sua tarefa é analisar questões de múltipla escolha, fornecendo explicações claras e concisas para cada alternativa em formato Markdown.
Primeiro faça um pequeno overview do cerne do texto da questão.
Explique cada alternativa individualmente:
- Comece com "✅:" se a alternativa for a correta.
- Comece com "❌:" se a alternativa for incorreta.
Seja conciso, objetivo e didático. Não utilize marcadores de letras ou números (A, B, C, 1, 2, 3) pois as alternativas são embaralhadas."#;

static LOCKS: OnceLock<Mutex<HashMap<String, Arc<Mutex<()>>>>> = OnceLock::new();

pub async fn acquire_generation_lock(
    db_name: &str,
    question_id: &str,
    kind: &AiAssistKind,
) -> Arc<Mutex<()>> {
    let key = format!("{db_name}:{question_id}:{kind:?}");
    let mut locks = LOCKS
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .await;
    locks.retain(|_, arc| Arc::strong_count(arc) > 1);
    locks
        .entry(key)
        .or_insert_with(|| Arc::new(Mutex::new(())))
        .clone()
}

/// Obtém o conteúdo pedagógico de IA do cache no MongoDB (`todasQuestoes`) ou gera via LLM e persiste
pub async fn get_or_generate_ai_assist(
    db: &Database,
    config: &AppConfig,
    question_id: &str,
    kind: AiAssistKind,
    hint_index: Option<usize>,
) -> Result<AiAssistResponse, AppError> {
    get_or_generate_ai_assist_budget(db, config, question_id, kind, hint_index, None).await
}

pub async fn get_or_generate_ai_assist_budget(
    db: &Database,
    config: &AppConfig,
    question_id: &str,
    kind: AiAssistKind,
    hint_index: Option<usize>,
    budget_user: Option<i64>,
) -> Result<AiAssistResponse, AppError> {
    let lock = acquire_generation_lock(db.name(), question_id, &kind).await;
    let _guard = lock.lock().await;
    let oid = ObjectId::parse_str(question_id)?;
    let coll = db.collection_with_options::<QuestionDocument>(
        "todasQuestoes",
        mongodb::options::CollectionOptions::builder()
            .write_concern(
                mongodb::options::WriteConcern::builder()
                    .w(mongodb::options::Acknowledgment::Majority)
                    .journal(true)
                    .build(),
            )
            .build(),
    );

    let original = db
        .collection::<bson::Document>("todasQuestoes")
        .find_one(doc! { "_id": oid })
        .await?
        .ok_or_else(|| AppError::NotFound(format!("Questão {} não encontrada", question_id)))?;

    let questao = crate::services::question_compatibility::decode(original.clone())?;

    // 1. Verificar se já existe no cache do documento MongoDB
    match &kind {
        AiAssistKind::Explanation => {
            if let Some(exp) = &questao.explanation {
                if !exp.trim().is_empty() {
                    return Ok(AiAssistResponse {
                        question_id: question_id.to_string(),
                        kind,
                        content_markdown: sanitize_ai_content(exp),
                        from_cache: true,
                        plausibility_structured: None,
                    });
                }
            }
        }
        AiAssistKind::ContextualHint => {
            let idx = hint_index.unwrap_or(0);
            if idx > 2 {
                return Err(AppError::BadRequest(
                    "Há até três dicas progressivas por questão".into(),
                ));
            }
            if idx < questao.contextual_hint.len() && !questao.contextual_hint[idx].trim().is_empty()
            {
                return Ok(AiAssistResponse {
                    question_id: question_id.to_string(),
                    kind,
                    content_markdown: sanitize_ai_content(&questao.contextual_hint[idx]),
                    from_cache: true,
                    plausibility_structured: None,
                });
            }
        }
        AiAssistKind::DrSincero => {
            if let Some(txt) = &questao.dr_sincero {
                if !txt.trim().is_empty() {
                    return Ok(AiAssistResponse {
                        question_id: question_id.to_string(),
                        kind,
                        content_markdown: sanitize_ai_content(txt),
                        from_cache: true,
                        plausibility_structured: None,
                    });
                }
            }
        }
        AiAssistKind::ChildFriendlyPre => {
            if let Some(txt) = &questao.ajuda_infantil {
                if !txt.trim().is_empty() {
                    return Ok(AiAssistResponse {
                        question_id: question_id.to_string(),
                        kind,
                        content_markdown: sanitize_ai_content(txt),
                        from_cache: true,
                        plausibility_structured: None,
                    });
                }
            }
        }
        AiAssistKind::ChildFriendlyPost => {
            if let Some(txt) = &questao.explicacao_infantil {
                if !txt.trim().is_empty() {
                    return Ok(AiAssistResponse {
                        question_id: question_id.to_string(),
                        kind,
                        content_markdown: sanitize_ai_content(txt),
                        from_cache: true,
                        plausibility_structured: None,
                    });
                }
            }
        }
        AiAssistKind::ExtraConcepts => {
            if let Some(txt) = &questao.explanation_extra {
                if !txt.trim().is_empty() {
                    return Ok(AiAssistResponse {
                        question_id: question_id.to_string(),
                        kind,
                        content_markdown: sanitize_ai_content(txt),
                        from_cache: true,
                        plausibility_structured: None,
                    });
                }
            }
        }
        AiAssistKind::Mnemonic => {
            if let Some(txt) = &questao.mnemonico {
                if !txt.trim().is_empty() {
                    return Ok(AiAssistResponse {
                        question_id: question_id.to_string(),
                        kind,
                        content_markdown: sanitize_ai_content(txt),
                        from_cache: true,
                        plausibility_structured: None,
                    });
                }
            }
        }
        AiAssistKind::Plausibility => {
            if let Some(markdown) = &questao.plausibility_markdown {
                return Ok(AiAssistResponse {
                    question_id: question_id.into(),
                    kind,
                    content_markdown: sanitize_ai_content(markdown),
                    from_cache: true,
                    plausibility_structured: None,
                });
            }
            if let Some(pa) = questao.plausibility_analysis.as_ref().filter(|pa| {
                !pa.correct_answer_explanation.trim().is_empty()
                    || !pa.incorrect_answers_analysis.is_empty()
                    || !pa.conclusion.trim().is_empty()
            }) {
                let formatted = format_plausibility_markdown(pa);
                return Ok(AiAssistResponse {
                    question_id: question_id.to_string(),
                    kind,
                    content_markdown: sanitize_ai_content(&formatted),
                    from_cache: true,
                    plausibility_structured: Some(pa.clone()),
                });
            }
        }
    }

    let correct_index = questao
        .answer_indices()?
        .into_iter()
        .next()
        .ok_or_else(|| AppError::BadRequest("Questão sem gabarito válido".into()))?;
    let correct_answer = questao.answers[correct_index].clone();

    // 2. Conteúdo ausente: reserva a cota ANTES de chamar a API paga da xAI e devolve em caso de falha.
    if let Some(user) = budget_user {
        crate::services::rate_limit::reserve_tutor(db, user).await?;
    }

    let generated_result = match &kind {
        AiAssistKind::Explanation => {
            call_xai_text(
                config,
                EXPLANATION_SYSTEM_INSTRUCTION,
                &json!({"question":questao.question,"choices":questao.answers,"correct_answer":correct_answer}).to_string(),
            )
            .await
        }
        AiAssistKind::ContextualHint => {
            let prev = questao.contextual_hint.join("; ");
            let prompt = format!(
                "Enunciado: {}\nAlternativas: {:?}\nResposta Correta: {}\nDicas Anteriores: {}\nForneça UMA ÚNICA frase curta (máx 30 palavras) como dica contextual progressiva sem entregar o gabarito.",
                questao.question, questao.answers, correct_answer, prev
            );
            call_xai_text(
                config,
                "Você é um especialista em concursos públicos. Dê uma dica progressiva sem entregar a resposta.",
                &prompt,
            )
            .await
        }
        AiAssistKind::DrSincero => {
            let prompt = format!(
                "Questão: {}\nAlternativas: {:?}\nResposta Correta: {}\nApresente uma análise honesta, ácida, sarcástica e direta sobre as pegadinhas da banca e por que candidatos erram esta questão (máx 250 palavras).",
                questao.question, questao.answers, correct_answer
            );
            call_xai_text(
                config,
                "Você é o Dr. Sincero, um mentor sarcástico, direto e cirúrgico em concursos públicos.",
                &prompt,
            )
            .await
        }
        AiAssistKind::ChildFriendlyPre => {
            let prompt = format!(
                "Questão: {}\nAlternativas: {:?}\nReformule a pergunta e as alternativas de forma que uma criança de 10 anos consiga entender, mantendo o sentido técnico.",
                questao.question, questao.answers
            );
            call_xai_text(
                config,
                "Você traduz questões complexas de concursos para uma linguagem simples e clara.",
                &prompt,
            )
            .await
        }
        AiAssistKind::ChildFriendlyPost => {
            let prompt = format!(
                "Questão: {}\nResposta Correta: {}\nExplique os conceitos desta questão como se estivesse falando com uma criança de 10 anos, usando analogias do dia a dia.",
                questao.question, correct_answer
            );
            call_xai_text(
                config,
                "Você explica conceitos difíceis de concursos com analogias simples e didáticas.",
                &prompt,
            )
            .await
        }
        AiAssistKind::ExtraConcepts => {
            let prompt = format!(
                "Questão: {}\nResposta Correta: {}\nExplique os Conceitos-Chave da questão de forma didática e forneça Exemplos Práticos e Reais da Administração Pública/Fiscalização.",
                questao.question, correct_answer
            );
            call_xai_text(
                config,
                "Você é um professor especialista em concursos públicos de alto nível.",
                &prompt,
            )
            .await
        }
        AiAssistKind::Mnemonic => {
            let prompt = format!(
                "Questão: {}\nConceito central: {}\nCrie um mnemônico curto, criativo e memorável para fixar os requisitos ou conceitos-chave cobrados nesta questão.",
                questao.question, correct_answer
            );
            call_xai_text(
                config,
                "Você é especialista em técnicas de memorização e mnemônicos para concurseiros.",
                &prompt,
            )
            .await
        }
        AiAssistKind::Plausibility => {
            let prompt = format!(
                "Questão: {}\nResposta Correta: {}\nAlternativas: {:?}\nAnalise a plausibilidade de cada alternativa incorreta (Improvável, Parcialmente plausível, Plausível), prestando atenção especial a termos como NÃO, EXCETO, MENOS.",
                questao.question, correct_answer, questao.answers
            );
            call_xai_text(
                config,
                "Você é um examinador de banca de concurso público analisando distratores.",
                &prompt,
            )
            .await
        }
    };

    let generated_text = match generated_result {
        Ok(text) if crate::services::rate_limit::charges_tutor_budget(false, Some(&text)) => text,
        Ok(_) => {
            if let Some(user) = budget_user {
                crate::services::rate_limit::release_tutor(db, user).await;
            }
            return Err(AppError::ExternalAiError(
                "xAI devolveu conteúdo vazio".into(),
            ));
        }
        Err(err) => {
            if let Some(user) = budget_user {
                crate::services::rate_limit::release_tutor(db, user).await;
            }
            return Err(err);
        }
    };

    // 3. Persistir no MongoDB para nunca gastar tokens duas vezes na mesma questão
    let update_doc = match &kind {
        AiAssistKind::Explanation => doc! { "$set": { "explanation": &generated_text } },
        AiAssistKind::ContextualHint => {
            let index = hint_index.unwrap_or(0);
            let mut hints = questao.contextual_hint.clone();
            hints.resize(hints.len().max(index + 1), String::new());
            hints[index] = generated_text.clone();
            doc! {"$set":{"contextualHint":hints}}
        }
        AiAssistKind::DrSincero => doc! { "$set": { "drSincero": &generated_text } },
        AiAssistKind::ChildFriendlyPre => doc! { "$set": { "ajudaInfantil": &generated_text } },
        AiAssistKind::ChildFriendlyPost => {
            doc! { "$set": { "explicacaoInfantil": &generated_text } }
        }
        AiAssistKind::ExtraConcepts => doc! { "$set": { "explanationExtra": &generated_text } },
        AiAssistKind::Mnemonic => doc! { "$set": { "mnemonico": &generated_text } },
        AiAssistKind::Plausibility => doc! { "$set": { "plausibilityMarkdown": &generated_text } },
    };

    let mut guard = doc! {"_id":oid};
    for field in [
        "question",
        "answers",
        "correct_answers",
        "correct_answer_indices",
        "content_revision",
        "grading",
        "status",
        "validity",
        "legal_validity",
    ] {
        guard.insert(
            field,
            original
                .get(field)
                .cloned()
                .unwrap_or_else(|| bson::Bson::Document(doc! {"$exists":false})),
        );
    }
    let result = match coll.update_one(guard, update_doc).await {
        Ok(res) => res,
        Err(err) => {
            if let Some(user) = budget_user {
                crate::services::rate_limit::release_tutor(db, user).await;
            }
            return Err(err.into());
        }
    };
    if result.matched_count != 1 {
        if let Some(user) = budget_user {
            crate::services::rate_limit::release_tutor(db, user).await;
        }
        return Err(AppError::Conflict(
            "Questão alterada ou removida durante a geração; conteúdo não confirmado".into(),
        ));
    }

    let final_content = sanitize_ai_content(&generated_text);

    Ok(AiAssistResponse {
        question_id: question_id.to_string(),
        kind,
        content_markdown: final_content,
        from_cache: false,
        plausibility_structured: None,
    })
}

pub fn sanitize_ai_content(raw: &str) -> String {
    let collapsed = regex::Regex::new(r"\n{3,}")
        .unwrap()
        .replace_all(raw.trim(), "\n\n");
    let cleaned = regex::Regex::new(r"(?i)^\s*(?:🔥\s*)?(?:\*\*Dr\.\s*Sincero:?\*\*:?\s*)")
        .unwrap()
        .replace(&collapsed, "");
    cleaned.trim().to_string()
}

fn format_plausibility_markdown(pa: &PlausibilityAnalysis) -> String {
    let mut out = format!(
        "**Explicação da Resposta Correta:**\n{}\n\n**Análise dos Distratores (Alternativas Incorretas):**\n\n",
        pa.correct_answer_explanation
    );
    for item in &pa.incorrect_answers_analysis {
        let nivel = item
            .plausibility
            .first()
            .map(|p| p.plausibilidade.as_str())
            .unwrap_or("N/A");
        out.push_str(&format!(
            "- **Alternativa:** {}\n  - **Grau de Plausibilidade:** `{}`\n  - **Análise:** {}\n\n",
            item.alternative, nivel, item.explanation
        ));
    }
    if !pa.conclusion.is_empty() {
        out.push_str(&format!("**Conclusão:**\n{}", pa.conclusion));
    }
    out
}

async fn call_xai_text(
    config: &AppConfig,
    system_prompt: &str,
    user_prompt: &str,
) -> Result<String, AppError> {
    let key = config
        .xai_api_key
        .as_ref()
        .ok_or_else(|| AppError::ExternalAiError("xAI não configurada".into()))?;
    let base = config.xai_base_url.trim_end_matches('/');
    if !base.starts_with("https://") && !(cfg!(test) && base.starts_with("http://127.0.0.1:")) {
        return Err(AppError::ExternalAiError(
            "Endpoint xAI deve usar HTTPS".into(),
        ));
    }
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(40))
        .connect_timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|_| AppError::Internal("Cliente xAI indisponível".into()))?;
    let response = client
        .post(format!("{base}/responses"))
        .bearer_auth(key)
        .json(&json!({
            "model":config.xai_model,"store":false,"max_output_tokens":4096,
            "reasoning":{"effort":"low"},
            "input":[{"role":"system","content":system_prompt},{"role":"user","content":user_prompt}]
        }))
        .send()
        .await
        .map_err(|_| AppError::ExternalAiError("Não foi possível receber a resposta da xAI".into()))?;
    if !response.status().is_success() {
        return Err(AppError::ExternalAiError(format!(
            "xAI indisponível (HTTP {})",
            response.status().as_u16()
        )));
    }
    let body: serde_json::Value = response
        .json()
        .await
        .map_err(|_| AppError::ExternalAiError("Resposta xAI inválida".into()))?;
    if body["status"] != "completed" {
        return Err(AppError::ExternalAiError(
            "A xAI não concluiu a geração; tente novamente".into(),
        ));
    }
    let text = body["output"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|item| item["type"] == "message")
        .flat_map(|item| item["content"].as_array().into_iter().flatten())
        .filter(|part| part["type"] == "output_text")
        .filter_map(|part| part["text"].as_str())
        .collect::<Vec<_>>()
        .join("\n");
    let text = sanitize_ai_content(&text);
    if text.is_empty() {
        return Err(AppError::ExternalAiError(
            "xAI devolveu conteúdo vazio".into(),
        ));
    }
    Ok(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sanitize_ai_content_removes_redundant_dr_sincero_prefix() {
        let raw = "🔥 **Dr. Sincero:** Esta questão é uma pegadinha evidente da banca.";
        let cleaned = sanitize_ai_content(raw);
        assert_eq!(cleaned, "Esta questão é uma pegadinha evidente da banca.");

        let raw_no_emoji = "**Dr. Sincero:** Análise ácida sem emoji.";
        assert_eq!(
            sanitize_ai_content(raw_no_emoji),
            "Análise ácida sem emoji."
        );
    }

    #[test]
    fn test_sanitize_ai_content_collapses_newlines() {
        let raw = "Linha 1\n\n\n\nLinha 2";
        assert_eq!(sanitize_ai_content(raw), "Linha 1\n\nLinha 2");
    }

    #[tokio::test]
    async fn generation_locks_prune_idle_entries_by_strong_count() {
        let lock_a = acquire_generation_lock("test_db", "q1", &AiAssistKind::Mnemonic).await;
        assert!(Arc::strong_count(&lock_a) >= 2);
        drop(lock_a);

        // Acquiring another lock triggers retain(|_, arc| Arc::strong_count(arc) > 1), pruning q1
        let lock_b = acquire_generation_lock("test_db", "q2", &AiAssistKind::Explanation).await;
        let map = LOCKS.get().unwrap().lock().await;
        assert!(!map.contains_key("test_db:q1:Mnemonic"));
        assert!(map.contains_key("test_db:q2:Explanation"));
        drop(map);
        drop(lock_b);
    }
}
