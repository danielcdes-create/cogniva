use crate::models::activity::{ConfidenceQuadrant, PastSessionEntry};
use chrono::{DateTime, Utc};
use std::collections::HashSet;

pub const DIAS_POR_MES: f64 = 30.44;

/// Resultado consolidado do motor de Streak e Pontos (portado de `calculate_streak_and_points` em AFTBot.py)
#[derive(Debug, Clone, PartialEq)]
pub struct StreakAndPointsResult {
    pub streak_atual: i32,
    pub streak_max: i32,
    pub points_total: f64,
    pub last_session_points: f64,
}

/// Portabilidade exata de `calculate_streak_and_points` do AFTBot.py:
/// - Acertos progressivos: [0.2, 0.3, 0.5, 0.1]
/// - Penalização por erros consecutivos: [-0.2, -0.3, -0.5, -0.1]
/// - Pontuação acumulada nunca fica abaixo de 0.0
pub fn calculate_streak_and_points(statuses: &[bool]) -> StreakAndPointsResult {
    let points_correct = [0.2, 0.3, 0.5, 0.1];
    let points_incorrect = [-0.2, -0.3, -0.5, -0.1];

    let mut streak_atual: i32 = 0;
    let mut streak_max: i32 = 0;
    let mut points: f64 = 0.0;
    let mut streak: usize = 0;
    let mut erro_consecutivo: usize = 0;
    let mut last_session_points: f64 = 0.0;

    for (idx, &is_correct) in statuses.iter().enumerate() {
        if is_correct {
            streak += 1;
            erro_consecutivo = 0;
            let delta = points_correct[(streak - 1).min(3)];
            points += delta;
            if idx == statuses.len() - 1 {
                last_session_points = delta;
            }
        } else {
            erro_consecutivo += 1;
            let delta = points_incorrect[(erro_consecutivo - 1).min(3)];
            points = (points + delta).max(0.0);
            if idx == statuses.len() - 1 {
                last_session_points = delta;
            }
            streak = 0;
        }

        streak_max = streak_max.max(streak as i32);
        streak_atual = streak as i32;
    }

    StreakAndPointsResult {
        streak_atual,
        streak_max,
        points_total: (points * 1000.0).round() / 1000.0,
        last_session_points,
    }
}

/// Verifica se a questão está dominada (`points >= 1.0`)
pub fn is_mastered(points: f64) -> bool {
    points >= 1.0 - 1e-6
}

/// Calcula o decaimento diário de 1% (`points * 0.99^dias_passados`), portado de `calculate_decay`
pub fn calculate_decay(
    points: f64,
    ultima_sessao: DateTime<Utc>,
    current_date: DateTime<Utc>,
) -> f64 {
    let dias_passados = (current_date.date_naive() - ultima_sessao.date_naive())
        .num_days()
        .max(0) as i32;
    let taxa_decay_diaria = 0.01_f64;
    let pontos_restantes = points * (1.0 - taxa_decay_diaria).powi(dias_passados);
    pontos_restantes.max(0.0)
}

/// Calcula o Score de Repetição Espaçada (portado de `calculate_spaced_repetition_score` em AFTBot.py)
pub fn calculate_spaced_repetition_score(
    streak_max: i32,
    tempo_medio_resposta: f64,
    tempo_gasto_segundos: i32,
    nivel_confianca: i32,
) -> f64 {
    let streak_factor = ((streak_max as f64) / 5.0).min(1.0).max(0.0);

    let time_factor = if tempo_medio_resposta > 0.0 {
        (2.0 - (tempo_gasto_segundos as f64 / tempo_medio_resposta)).clamp(0.0, 1.0)
    } else {
        0.5
    };

    let confidence_factor = (nivel_confianca.clamp(0, 3) as f64) / 3.0;

    let score = (streak_factor * 0.4) + (time_factor * 0.2) + (confidence_factor * 0.4);
    score.clamp(0.0, 1.0)
}

/// Calcula o intervalo de Repetição Espaçada em dias (portado de `calculate_spaced_repetition_interval`)
/// Suporta também o modo SRS simplificado de degraus fixos (1d / 7d / 30d) quando `use_simplified_steps` = true
pub fn calculate_spaced_repetition_interval(
    streak_max: i32,
    tempo_medio_resposta: f64,
    tempo_gasto_segundos: i32,
    nivel_confianca: i32,
    is_correct: bool,
    use_simplified_steps: bool,
) -> (f64, i32) {
    let score = calculate_spaced_repetition_score(
        streak_max,
        tempo_medio_resposta,
        tempo_gasto_segundos,
        nivel_confianca,
    );

    if use_simplified_steps {
        // Nota Técnica Quiz Kotlin §11.1: Intervalos 24h (1d) / 7d / 30d
        let interval = if !is_correct || nivel_confianca <= 1 {
            1
        } else if streak_max <= 2 {
            7
        } else {
            30
        };
        return (score, interval);
    }

    // Legacy policy v1: continuous, monotone and explicitly sensitive to failure.
    // New attempts use simplified policy v2; persisted historical intervals are untouched.
    if !is_correct || nivel_confianca <= 1 {
        return (score, 3);
    }
    let interval = (3.0 + 87.0 * score.powi(2)).round().clamp(3.0, 90.0) as i32;
    (score, interval)
}

/// Classifica a tentativa na Matriz Confiança × Acerto (Nota Técnica Concurso App §8.2)
pub fn classify_confidence_quadrant(is_correct: bool, nivel_confianca: i32) -> ConfidenceQuadrant {
    let high_conf = nivel_confianca >= 2;
    match (is_correct, high_conf) {
        (true, true) => ConfidenceQuadrant::HighConfidenceCorrect,
        (true, false) => ConfidenceQuadrant::LowConfidenceCorrect,
        (false, false) => ConfidenceQuadrant::LowConfidenceError,
        (false, true) => ConfidenceQuadrant::HighConfidenceError,
    }
}

/// Calcula a variação de alternativas escolhidas (`calcular_variacao_alternativa` em AFTBot.py)
pub fn calculate_alternative_variation(past_sessions: &[PastSessionEntry]) -> f64 {
    if past_sessions.len() <= 1 {
        return 0.0;
    }
    let unique: HashSet<&str> = past_sessions
        .iter()
        .map(|s| s.alternativa_escolhida.as_str())
        .collect();
    ((unique.len().saturating_sub(1)) as f64 / ((past_sessions.len() - 1) as f64)) * 100.0
}

/// Calcula a frequência de estudo mensal (`calcular_frequencia_estudo` em AFTBot.py)
pub fn calculate_monthly_study_frequency(
    past_sessions: &[PastSessionEntry],
    current_date: DateTime<Utc>,
) -> f64 {
    let Some(first) = past_sessions.first() else {
        return 1.0;
    };
    let days_since_first = (current_date.date_naive() - first.data.date_naive())
        .num_days()
        .max(1) as f64;
    let months = (days_since_first / DIAS_POR_MES).max(1.0 / DIAS_POR_MES);
    ((past_sessions.len() as f64) / months).min(30.0)
}

/// Calcula a média do nível de confiança das últimas `n` sessões (`util_calculate_confidence`)
pub fn calculate_recent_confidence_avg(
    past_sessions: &[PastSessionEntry],
    n: usize,
) -> Option<i32> {
    let slice = if past_sessions.len() > n {
        &past_sessions[past_sessions.len() - n..]
    } else {
        past_sessions
    };
    if slice.is_empty() {
        return None;
    }
    let sum: i32 = slice.iter().map(|s| s.nivel_confianca).sum();
    Some(((sum as f64) / (slice.len() as f64)).round() as i32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_streak_and_points_progression_and_mastery() {
        // 3 acertos consecutivos = 0.2 + 0.3 + 0.5 = 1.0 -> Dominada!
        let res = calculate_streak_and_points(&[true, true, true]);
        assert_eq!(res.streak_atual, 3);
        assert_eq!(res.streak_max, 3);
        assert!((res.points_total - 1.0).abs() < 1e-6);
        assert!(is_mastered(res.points_total));

        // 1 erro em seguida desconta 0.2 -> 0.8 (deixa de ser dominada)
        let res_after_err = calculate_streak_and_points(&[true, true, true, false]);
        assert_eq!(res_after_err.streak_atual, 0);
        assert_eq!(res_after_err.streak_max, 3);
        assert!((res_after_err.points_total - 0.8).abs() < 1e-6);
        assert!(!is_mastered(res_after_err.points_total));
    }

    #[test]
    fn new_attempt_intervals_are_only_one_seven_or_thirty_days() {
        for streak in 0..=6 {
            for confidence in 0..=3 {
                for correct in [true, false] {
                    let (_, interval) = calculate_spaced_repetition_interval(
                        streak, 40.0, 20, confidence, correct, true,
                    );
                    assert!(
                        interval == 1 || interval == 7 || interval == 30,
                        "interval {interval}"
                    );
                }
            }
        }
    }

    #[test]
    fn test_high_confidence_error_quadrant() {
        assert_eq!(
            classify_confidence_quadrant(false, 3),
            ConfidenceQuadrant::HighConfidenceError
        );
        assert_eq!(
            classify_confidence_quadrant(true, 3),
            ConfidenceQuadrant::HighConfidenceCorrect
        );
    }
}

#[cfg(test)]
mod policy_contract_tests {
    use super::*;
    #[test]
    fn failed_or_guessed_attempts_have_short_intervals_and_both_policies_are_monotone() {
        for steps in [true, false] {
            for streak in 0..=5 {
                let (_, bad) =
                    calculate_spaced_repetition_interval(streak, 60.0, 30, 3, false, steps);
                assert_eq!(bad, if steps { 1 } else { 3 });
                let mut previous = 0;
                for confidence in 0..=3 {
                    let (_, days) = calculate_spaced_repetition_interval(
                        streak, 60.0, 30, confidence, true, steps,
                    );
                    assert!(days >= previous);
                    previous = days;
                }
            }
        }
    }
}
