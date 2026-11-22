use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct RetentionUpdate {
    pub next_stability: f64,
    pub next_difficulty: f64,
    pub next_interval_days: i32,
}

/// Curva contínua de recuperabilidade de memória: `R(t, S) = (1 + (19/81)*(t/S))^-0.5`.
/// Em `t == S`, `R == 0.90` exatamente.
pub fn retrievability(elapsed_days: f64, stability_days: f64) -> f64 {
    if stability_days <= 0.0 || !stability_days.is_finite() || !elapsed_days.is_finite() {
        return 0.0;
    }
    let t = elapsed_days.max(0.0);
    (1.0 + (19.0 / 81.0) * (t / stability_days))
        .powf(-0.5)
        .clamp(0.0, 1.0)
}

fn round6(val: f64) -> f64 {
    (val * 1_000_000.0).round() / 1_000_000.0
}

/// Atualiza estabilidade `S` (dias) e dificuldade `D` (1..10) a partir da resposta observada.
/// Proteção anti-chute: acertos com `confidence <= 1` limitam a estabilidade a no máximo 1.5 dia.
pub fn update_retention(
    prior_stability: f64,
    prior_difficulty: f64,
    is_correct: bool,
    confidence: i32,
    elapsed_days: f64,
    response_seconds: i32,
    expected_seconds: f64,
) -> RetentionUpdate {
    let safe_stab = if prior_stability.is_finite() {
        prior_stability
    } else {
        0.0
    };
    let safe_diff = if prior_difficulty.is_finite() {
        prior_difficulty
    } else {
        5.0
    };
    let safe_elapsed = if elapsed_days.is_finite() {
        elapsed_days.max(0.0)
    } else {
        0.0
    };
    let safe_expected = if expected_seconds.is_finite() {
        expected_seconds
    } else {
        0.0
    };
    let d = if safe_stab <= 0.0 {
        5.0
    } else {
        safe_diff.clamp(1.0, 10.0)
    };
    let s = safe_stab.max(0.0);

    let (s_next, d_next) = if !is_correct {
        let delta_d = if confidence >= 3 { 1.5 } else { 1.0 };
        let dn = (d + delta_d).clamp(1.0, 10.0);
        let sn = if s <= 0.0 {
            0.5
        } else {
            (s * 0.25).clamp(0.5, 2.0)
        };
        (sn, dn)
    } else if confidence <= 1 {
        let dn = (d + 0.2).clamp(1.0, 10.0);
        let sn = s.clamp(1.0, 1.5);
        (sn, dn)
    } else {
        let slow = safe_expected > 0.0 && (response_seconds as f64) > 1.5 * safe_expected;
        let g = if confidence >= 3 && !slow { 1.0 } else { 0.6 };
        let dn = (d - 0.3 * g + 0.15 * (1.0 - g)).clamp(1.0, 10.0);
        let sn = if s <= 0.0 {
            if g >= 1.0 {
                4.0
            } else {
                2.0
            }
        } else {
            let r = retrievability(safe_elapsed, s);
            let mult = 1.0 + g * ((11.0 - dn) / 9.0) * (1.2 + 1.8 * (1.0 - r));
            (s * mult).clamp(1.0, 180.0)
        };
        (sn, dn)
    };

    let s_rounded = round6(s_next);
    let d_rounded = round6(d_next);
    let interval_days = s_rounded.round().clamp(1.0, 180.0) as i32;

    RetentionUpdate {
        next_stability: s_rounded,
        next_difficulty: d_rounded,
        next_interval_days: interval_days,
    }
}
