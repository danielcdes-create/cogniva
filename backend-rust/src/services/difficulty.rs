use crate::models::question::{IncorrectAnswerAnalysis, QuestionDocument};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DifficultyMetrics {
    pub plausibility_index: f64,
    pub question_length: usize,
    pub avg_alternative_length: f64,
    pub linguistic_complexity: f64,
    pub key_concepts: usize,
    pub has_numerical_data: u8,
    pub abstraction_level: usize,
    pub alternative_similarity: f64,
    pub negation_count: usize,
    pub difficulty_index: f64,
    pub level_label: String,
}

/// Portabilidade de `analyze_plausibility_B` em AFTBot.py
pub fn analyze_plausibility_b(incorrect_alternatives: &[IncorrectAnswerAnalysis]) -> f64 {
    if incorrect_alternatives.is_empty() {
        return 0.0;
    }

    let mut total_plausibility = 0.0;
    for alt in incorrect_alternatives {
        let level_str = alt
            .plausibility
            .first()
            .map(|p| p.plausibilidade.as_str())
            .unwrap_or("Improvável");

        let val = match level_str {
            "Plausível" => 1.0,
            "Parcialmente plausível" => 0.5,
            _ => 0.0,
        };
        total_plausibility += val;
    }

    let index = (total_plausibility / (incorrect_alternatives.len() as f64)) * 100.0;
    (index * 100.0).round() / 100.0
}

/// Similaridade textual entre duas strings (substituto nativo ultrarrápido de `SequenceMatcher.ratio()`)
fn string_similarity_ratio(a: &str, b: &str) -> f64 {
    if a == b {
        return 1.0;
    }
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    let a_words: HashSet<&str> = a.split_whitespace().collect();
    let b_words: HashSet<&str> = b.split_whitespace().collect();
    if a_words.is_empty() || b_words.is_empty() {
        return 0.0;
    }
    let intersection = a_words.intersection(&b_words).count();
    (2.0 * intersection as f64) / ((a_words.len() + b_words.len()) as f64)
}

/// Portabilidade de `analyze_question_difficulty` e `classify_difficulty` do AFTBot.py
pub fn analyze_question_difficulty(questao: &QuestionDocument) -> DifficultyMetrics {
    let q_text = &questao.question;
    let q_lower = q_text.to_lowercase();

    let mut plausibility_index = 0.5_f64;
    let mut avg_alternative_length = if !questao.answers.is_empty() {
        questao.answers.iter().map(|a| a.len()).sum::<usize>() as f64
            / (questao.answers.len() as f64)
    } else {
        0.0
    };

    if let Some(pa) = &questao.plausibility_analysis {
        if !pa.incorrect_answers_analysis.is_empty() {
            plausibility_index = analyze_plausibility_b(&pa.incorrect_answers_analysis) / 100.0;
            avg_alternative_length = pa
                .incorrect_answers_analysis
                .iter()
                .map(|a| a.alternative.len())
                .sum::<usize>() as f64
                / (pa.incorrect_answers_analysis.len() as f64);
        }
    }

    // Conceitos-chave capitalizados
    let re_concepts = Regex::new(r"\b[A-ZÁÉÍÓÚÂÊÔÃÕÇ][a-záéíóúâêôãõç]+(?:\s+[A-ZÁÉÍÓÚÂÊÔÃÕÇ][a-záéíóúâêôãõç]+)*\b").unwrap();
    let key_concepts = re_concepts.find_iter(q_text).count();

    // Presença de dados numéricos
    let has_numerical_data = if q_text.chars().any(|c| c.is_ascii_digit()) {
        1
    } else {
        0
    };

    // Nível de abstração
    let abstract_words = ["conceito", "teoria", "princípio", "abstrato", "filosófico", "jurisprudência", "doutrina"];
    let abstraction_level = abstract_words
        .iter()
        .filter(|w| q_lower.contains(**w))
        .count();

    // Similaridade entre alternativas
    let mut similarity_scores = Vec::new();
    for i in 0..questao.answers.len() {
        for j in (i + 1)..questao.answers.len() {
            similarity_scores.push(string_similarity_ratio(
                &questao.answers[i],
                &questao.answers[j],
            ));
        }
    }
    let alternative_similarity = if !similarity_scores.is_empty() {
        similarity_scores.iter().sum::<f64>() / (similarity_scores.len() as f64)
    } else {
        0.5
    };

    // Contagem de negações ('não', 'exceto', 'incorreto', 'menos')
    let negation_count = q_lower.matches("não").count()
        + q_lower.matches("exceto").count()
        + q_lower.matches("incorret").count();

    let linguistic_complexity = ((q_text.split_whitespace().count() as f64) / 80.0).clamp(0.1, 1.0);

    // Pesos calibrados para escala normalizada [0.0, 1.0]
    let norm_q_len = (q_text.len() as f64 / 600.0).min(1.0);
    let norm_alt_len = (avg_alternative_length / 180.0).min(1.0);
    let norm_concepts = (key_concepts as f64 / 10.0).min(1.0);
    let norm_abstraction = (abstraction_level as f64 / 3.0).min(1.0);
    let norm_negation = (negation_count as f64 / 2.0).min(1.0);

    let difficulty_index = (plausibility_index * 0.60)
        + (norm_q_len * 0.025)
        + (norm_alt_len * 0.025)
        + (linguistic_complexity * 0.05)
        + (norm_concepts * 0.05)
        + ((has_numerical_data as f64) * 0.05)
        + (norm_abstraction * 0.05)
        + (alternative_similarity * 0.10)
        + (norm_negation * 0.05);

    let rounded_index = (difficulty_index * 100.0).round() / 100.0;
    let level_label = classify_difficulty(rounded_index).to_string();

    DifficultyMetrics {
        plausibility_index,
        question_length: q_text.len(),
        avg_alternative_length,
        linguistic_complexity,
        key_concepts,
        has_numerical_data,
        abstraction_level,
        alternative_similarity,
        negation_count,
        difficulty_index: rounded_index,
        level_label,
    }
}

pub fn classify_difficulty(difficulty_index: f64) -> &'static str {
    if difficulty_index < 0.15 {
        "Muito Fácil"
    } else if difficulty_index < 0.35 {
        "Fácil"
    } else if difficulty_index < 0.55 {
        "Médio"
    } else if difficulty_index < 0.75 {
        "Difícil"
    } else {
        "Muito Difícil"
    }
}
