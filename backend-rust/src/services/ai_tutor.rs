use crate::{
    config::AppConfig,
    error::AppError,
    models::question::{PlausibilityAnalysis, QuestionDocument},
};
use bson::{doc, oid::ObjectId};
use mongodb::Database;
use serde::{Deserialize, Serialize};
use serde_json::json;

/// Tipos de Assistência Pedagógica por IA (100% compatíveis com os campos cacheados pelo AFTBot.py)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum AiAssistKind {
    Explanation,      // Gemini 1.5 Flash -> campo `explanation`
    ContextualHint,   // OpenAI gpt-4o-mini -> array `contextualHint`
    DrSincero,        // OpenAI gpt-4o-mini -> campo `drSincero`
    ChildFriendlyPre, // OpenAI gpt-4o-mini -> campo `ajudaInfantil`
    ChildFriendlyPost,// OpenAI gpt-4o-mini -> campo `explicacaoInfantil`
    ExtraConcepts,    // OpenAI gpt-4o-mini -> campo `explanationExtra`
    Mnemonic,         // OpenAI gpt-4o-mini -> campo `mnemonico`
    Plausibility,     // OpenAI gpt-4o-mini -> objeto `plausibilityAnalysis`
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiAssistResponse {
    pub question_id: String,
    pub kind: AiAssistKind,
    pub content_markdown: String,
    pub from_cache: bool,
    pub plausibility_structured: Option<PlausibilityAnalysis>,
}

const GEMINI_SYSTEM_INSTRUCTION: &str = r#"Você é um especialista em concursos públicos, com vasta experiência em elaborar materiais didáticos. Sua tarefa é analisar questões de múltipla escolha, fornecendo explicações claras e concisas para cada alternativa em formato Markdown.
Primeiro faça um pequeno overview do cerne do texto da questão.
Explique cada alternativa individualmente:
- Comece com "✅:" se a alternativa for a correta.
- Comece com "❌:" se a alternativa for incorreta.
Seja conciso, objetivo e didático. Não utilize marcadores de letras ou números (A, B, C, 1, 2, 3) pois as alternativas são embaralhadas."#;

/// Obtém o conteúdo pedagógico de IA do cache no MongoDB (`todasQuestoes`) ou gera via LLM e persiste
pub async fn get_or_generate_ai_assist(
    db: &Database,
    config: &AppConfig,
    question_id: &str,
    kind: AiAssistKind,
    hint_index: Option<usize>,
) -> Result<AiAssistResponse, AppError> {
    let oid = ObjectId::parse_str(question_id)?;
    let coll = db.collection::<QuestionDocument>("todasQuestoes");

    let questao = coll
        .find_one(doc! { "_id": oid })
        .await?
        .ok_or_else(|| AppError::NotFound(format!("Questão {} não encontrada", question_id)))?;

    let correct_answer = questao
        .correct_answers
        .first()
        .cloned()
        .or_else(|| {
            questao
                .correct_answer_indices
                .first()
                .and_then(|&idx| questao.answers.get(idx).cloned())
        })
        .unwrap_or_else(|| "Gabarito oficial".to_string());

    // 1. Verificar se já existe no cache do documento MongoDB
    match &kind {
        AiAssistKind::Explanation => {
            if let Some(exp) = &questao.explanation {
                if !exp.trim().is_empty() {
                    return Ok(AiAssistResponse {
                        question_id: question_id.to_string(),
                        kind,
                        content_markdown: exp.clone(),
                        from_cache: true,
                        plausibility_structured: None,
                    });
                }
            }
        }
        AiAssistKind::ContextualHint => {
            let idx = hint_index.unwrap_or(0);
            if idx < questao.contextual_hint.len() {
                return Ok(AiAssistResponse {
                    question_id: question_id.to_string(),
                    kind,
                    content_markdown: questao.contextual_hint[idx].clone(),
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
                        content_markdown: txt.clone(),
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
                        content_markdown: txt.clone(),
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
                        content_markdown: txt.clone(),
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
                        content_markdown: txt.clone(),
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
                        content_markdown: txt.clone(),
                        from_cache: true,
                        plausibility_structured: None,
                    });
                }
            }
        }
        AiAssistKind::Plausibility => {
            if let Some(pa) = &questao.plausibility_analysis {
                let formatted = format_plausibility_markdown(pa);
                return Ok(AiAssistResponse {
                    question_id: question_id.to_string(),
                    kind,
                    content_markdown: formatted,
                    from_cache: true,
                    plausibility_structured: Some(pa.clone()),
                });
            }
        }
    }

    // 2. Se não está em cache, gera via Gemini ou OpenAI e grava em `todasQuestoes`
    let generated_text = match &kind {
        AiAssistKind::Explanation => {
            generate_gemini_explanation(config, &questao, &correct_answer).await?
        }
        AiAssistKind::ContextualHint => {
            let prev = questao.contextual_hint.join("; ");
            let prompt = format!(
                "Enunciado: {}\nAlternativas: {:?}\nResposta Correta: {}\nDicas Anteriores: {}\nForneça UMA ÚNICA frase curta (máx 30 palavras) como dica contextual progressiva sem entregar o gabarito.",
                questao.question, questao.answers, correct_answer, prev
            );
            call_openai_chat(
                config,
                "Você é um especialista em concursos públicos. Dê uma dica progressiva sem entregar a resposta.",
                &prompt,
            )
            .await?
        }
        AiAssistKind::DrSincero => {
            let prompt = format!(
                "Questão: {}\nAlternativas: {:?}\nResposta Correta: {}\nApresente uma análise honesta, ácida, sarcástica e direta sobre as pegadinhas da banca e por que candidatos erram esta questão (máx 250 palavras).",
                questao.question, questao.answers, correct_answer
            );
            call_openai_chat(
                config,
                "Você é o Dr. Sincero, um mentor sarcástico, direto e cirúrgico em concursos públicos.",
                &prompt,
            )
            .await?
        }
        AiAssistKind::ChildFriendlyPre => {
            let prompt = format!(
                "Questão: {}\nAlternativas: {:?}\nReformule a pergunta e as alternativas de forma que uma criança de 10 anos consiga entender, mantendo o sentido técnico.",
                questao.question, questao.answers
            );
            call_openai_chat(
                config,
                "Você traduz questões complexas de concursos para uma linguagem simples e clara.",
                &prompt,
            )
            .await?
        }
        AiAssistKind::ChildFriendlyPost => {
            let prompt = format!(
                "Questão: {}\nResposta Correta: {}\nExplique os conceitos desta questão como se estivesse falando com uma criança de 10 anos, usando analogias do dia a dia.",
                questao.question, correct_answer
            );
            call_openai_chat(
                config,
                "Você explica conceitos difíceis de concursos com analogias simples e didáticas.",
                &prompt,
            )
            .await?
        }
        AiAssistKind::ExtraConcepts => {
            let prompt = format!(
                "Questão: {}\nResposta Correta: {}\nExplique os Conceitos-Chave da questão de forma didática e forneça Exemplos Práticos e Reais da Administração Pública/Fiscalização.",
                questao.question, correct_answer
            );
            call_openai_chat(
                config,
                "Você é um professor especialista em concursos públicos de alto nível.",
                &prompt,
            )
            .await?
        }
        AiAssistKind::Mnemonic => {
            let prompt = format!(
                "Questão: {}\nConceito central: {}\nCrie um mnemônico curto, criativo e memorável para fixar os requisitos ou conceitos-chave cobrados nesta questão.",
                questao.question, correct_answer
            );
            call_openai_chat(
                config,
                "Você é especialista em técnicas de memorização e mnemônicos para concurseiros.",
                &prompt,
            )
            .await?
        }
        AiAssistKind::Plausibility => {
            let prompt = format!(
                "Questão: {}\nResposta Correta: {}\nAlternativas: {:?}\nAnalise a plausibilidade de cada alternativa incorreta (Improvável, Parcialmente plausível, Plausível), prestando atenção especial a termos como NÃO, EXCETO, MENOS.",
                questao.question, correct_answer, questao.answers
            );
            call_openai_chat(
                config,
                "Você é um examinador de banca de concurso público analisando distratores.",
                &prompt,
            )
            .await?
        }
    };

    // 3. Persistir no MongoDB para nunca gastar tokens duas vezes na mesma questão
    let update_doc = match &kind {
        AiAssistKind::Explanation => doc! { "$set": { "explanation": &generated_text } },
        AiAssistKind::ContextualHint => doc! { "$push": { "contextualHint": &generated_text } },
        AiAssistKind::DrSincero => doc! { "$set": { "drSincero": &generated_text } },
        AiAssistKind::ChildFriendlyPre => doc! { "$set": { "ajudaInfantil": &generated_text } },
        AiAssistKind::ChildFriendlyPost => doc! { "$set": { "explicacaoInfantil": &generated_text } },
        AiAssistKind::ExtraConcepts => doc! { "$set": { "explanationExtra": &generated_text } },
        AiAssistKind::Mnemonic => doc! { "$set": { "mnemonico": &generated_text } },
        AiAssistKind::Plausibility => doc! { "$set": { "explanationExtra": &generated_text } },
    };

    let _ = coll.update_one(doc! { "_id": oid }, update_doc).await;

    Ok(AiAssistResponse {
        question_id: question_id.to_string(),
        kind,
        content_markdown: generated_text,
        from_cache: false,
        plausibility_structured: None,
    })
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

async fn call_openai_chat(
    config: &AppConfig,
    system_prompt: &str,
    user_prompt: &str,
) -> Result<String, AppError> {
    let Some(api_key) = &config.openai_api_key else {
        return Err(AppError::ExternalAiError(
            "OPENAI_API_KEY não configurada no ambiente".to_string(),
        ));
    };

    let client = reqwest::Client::new();
    let res = client
        .post("https://api.openai.com/v1/chat/completions")
        .bearer_auth(api_key)
        .json(&json!({
            "model": config.openai_model,
            "temperature": 0.25,
            "messages": [
                { "role": "system", "content": system_prompt },
                { "role": "user", "content": user_prompt }
            ]
        }))
        .send()
        .await
        .map_err(|e| AppError::ExternalAiError(e.to_string()))?;

    let body: serde_json::Value = res
        .json()
        .await
        .map_err(|e| AppError::ExternalAiError(e.to_string()))?;

    body["choices"][0]["message"]["content"]
        .as_str()
        .map(|s| s.to_string())
        .ok_or_else(|| AppError::ExternalAiError("Resposta inválida da OpenAI".to_string()))
}

async fn generate_gemini_explanation(
    config: &AppConfig,
    questao: &QuestionDocument,
    correct_answer: &str,
) -> Result<String, AppError> {
    let Some(api_key) = &config.gemini_api_key else {
        // Fallback para OpenAI caso Gemini não esteja configurado
        return call_openai_chat(
            config,
            GEMINI_SYSTEM_INSTRUCTION,
            &json!({
                "question": questao.question,
                "choices": questao.answers,
                "correct_answer": correct_answer
            })
            .to_string(),
        )
        .await;
    };

    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent?key={}",
        config.gemini_model, api_key
    );

    let input_payload = json!({
        "question": questao.question,
        "choices": questao.answers,
        "correct_answer": correct_answer
    });

    let client = reqwest::Client::new();
    let res = client
        .post(&url)
        .json(&json!({
            "system_instruction": {
                "parts": [{ "text": GEMINI_SYSTEM_INSTRUCTION }]
            },
            "contents": [{
                "parts": [{ "text": input_payload.to_string() }]
            }],
            "generationConfig": {
                "temperature": 0.2,
                "topP": 0.7,
                "topK": 10,
                "maxOutputTokens": 2048
            }
        }))
        .send()
        .await
        .map_err(|e| AppError::ExternalAiError(e.to_string()))?;

    let body: serde_json::Value = res
        .json()
        .await
        .map_err(|e| AppError::ExternalAiError(e.to_string()))?;

    body["candidates"][0]["content"]["parts"][0]["text"]
        .as_str()
        .map(|s| s.to_string())
        .ok_or_else(|| AppError::ExternalAiError("Resposta inválida do Gemini".to_string()))
}
