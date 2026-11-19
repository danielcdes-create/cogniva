use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DiagnosticStatus {
    InsufficientEvidence,
    OverconfidenceError,
    ConceptualError,
    RetrievalFailure,
    VerifiedCompetence,
}

impl DiagnosticStatus {
    pub const fn as_str(&self) -> &'static str {
        match self {
            Self::InsufficientEvidence => "INSUFFICIENT_EVIDENCE",
            Self::OverconfidenceError => "OVERCONFIDENCE_ERROR",
            Self::ConceptualError => "CONCEPTUAL_ERROR",
            Self::RetrievalFailure => "RETRIEVAL_FAILURE",
            Self::VerifiedCompetence => "VERIFIED_COMPETENCE",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct DiagnosticInputs {
    pub distinct_questions: usize,
    pub non_blank_attempts: usize,
    pub correct_attempts: usize,
    pub last_is_correct: bool,
    pub last_confidence: i32,
    pub had_prior_verified_correct: bool,
    pub has_spaced_retention: bool,
    pub evidence_challenge_passed: Option<bool>,
}

/// Resolve a unidade canônica de conhecimento: usa `concept_id` quando presente,
/// fazendo fallback transparente para `topic_id` (`SUBTOPIC` folha).
pub fn resolve_unit_id(concept_id: Option<&str>, topic_id: &str) -> String {
    match concept_id.map(str::trim).filter(|s| !s.is_empty()) {
        Some(cid) => cid.to_string(),
        None => topic_id.trim().to_string(),
    }
}

/// Classifica o estado diagnóstico verificável da unidade de conhecimento.
/// Invariante central: responder a mesma questão repetidas vezes (`distinct_questions < 3`)
/// jamais afirma `VerifiedCompetence`.
pub fn classify_diagnostic(input: &DiagnosticInputs) -> DiagnosticStatus {
    if input.non_blank_attempts == 0 {
        return DiagnosticStatus::InsufficientEvidence;
    }
    if !input.last_is_correct {
        if input.last_confidence >= 3
            || (input.last_confidence >= 2 && input.evidence_challenge_passed == Some(false))
        {
            return DiagnosticStatus::OverconfidenceError;
        }
        if input.evidence_challenge_passed == Some(false) || !input.had_prior_verified_correct {
            return DiagnosticStatus::ConceptualError;
        }
        return DiagnosticStatus::RetrievalFailure;
    }

    let acc = (input.correct_attempts as f64) / (input.non_blank_attempts as f64);
    if input.distinct_questions >= 3
        && input.has_spaced_retention
        && acc >= 0.80 - 1e-9
        && input.last_confidence >= 2
    {
        DiagnosticStatus::VerifiedCompetence
    } else {
        DiagnosticStatus::InsufficientEvidence
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn repeating_single_question_never_claims_verified_competence() {
        let status = classify_diagnostic(&DiagnosticInputs {
            distinct_questions: 1,
            non_blank_attempts: 10,
            correct_attempts: 10,
            last_is_correct: true,
            last_confidence: 3,
            had_prior_verified_correct: true,
            has_spaced_retention: true,
            evidence_challenge_passed: Some(true),
        });
        assert_eq!(status, DiagnosticStatus::InsufficientEvidence);
    }

    #[test]
    fn concept_id_falls_back_cleanly_to_subtopic() {
        assert_eq!(resolve_unit_id(Some("CON_LTN"), "MFIN_02_001"), "CON_LTN");
        assert_eq!(resolve_unit_id(None, "MFIN_02_001"), "MFIN_02_001");
        assert_eq!(resolve_unit_id(Some("  "), "MFIN_02_001"), "MFIN_02_001");
    }
}
