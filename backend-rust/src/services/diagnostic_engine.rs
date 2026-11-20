use serde::{Deserialize, Serialize};

pub const MIN_QUESTIONS_FOR_MASTERY: usize = 15;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DiagnosticStatus {
    InsufficientEvidence,
    OverconfidenceError,
    ConceptualError,
    RetrievalFailure,
    IllusoryCompetence,
    EvidenceInProgress,
    ConsolidatedNoTransfer,
    VerifiedCompetence,
}

impl DiagnosticStatus {
    pub const fn as_str(&self) -> &'static str {
        match self {
            Self::InsufficientEvidence => "INSUFFICIENT_EVIDENCE",
            Self::OverconfidenceError => "OVERCONFIDENCE_ERROR",
            Self::ConceptualError => "CONCEPTUAL_ERROR",
            Self::RetrievalFailure => "RETRIEVAL_FAILURE",
            Self::IllusoryCompetence => "ILLUSORY_COMPETENCE",
            Self::EvidenceInProgress => "EVIDENCE_IN_PROGRESS",
            Self::ConsolidatedNoTransfer => "CONSOLIDATED_NO_TRANSFER",
            Self::VerifiedCompetence => "VERIFIED_COMPETENCE",
        }
    }

    pub const fn is_error_indication(&self) -> bool {
        matches!(
            self,
            Self::OverconfidenceError
                | Self::ConceptualError
                | Self::RetrievalFailure
                | Self::IllusoryCompetence
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TransferStatus {
    TransferUnmeasured,
    TransferAvailable,
    TransferProvisional,
    TransferFailed,
    TransferVerified,
}

impl TransferStatus {
    pub const fn as_str(&self) -> &'static str {
        match self {
            Self::TransferUnmeasured => "TRANSFER_UNMEASURED",
            Self::TransferAvailable => "TRANSFER_AVAILABLE",
            Self::TransferProvisional => "TRANSFER_PROVISIONAL",
            Self::TransferFailed => "TRANSFER_FAILED",
            Self::TransferVerified => "TRANSFER_VERIFIED",
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
    pub has_independent_transfer: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TransferInputs {
    pub distinct_questions: usize,
    pub available_cross_board_questions: usize,
    pub transfer_attempts: usize,
    pub transfer_correct_grounded: usize,
}

/// Resolve a unidade canônica de conhecimento: usa `concept_id` quando presente,
/// fazendo fallback transparente para `topic_id` (`SUBTOPIC` folha).
pub fn resolve_unit_id(concept_id: Option<&str>, topic_id: &str) -> String {
    match concept_id.map(str::trim).filter(|s| !s.is_empty()) {
        Some(cid) => cid.to_string(),
        None => topic_id.trim().to_string(),
    }
}

/// Classifica o estado diagnóstico cruzando os três sinais (resposta, confiança declarada e
/// evidência selecionada) sob a restrição epistemológica de escassez (`< 15` questões humanas):
/// - Com menos de 15 questões distintas, o motor identifica indícios de erro (incluindo
///   `IllusoryCompetence` quando acerta a questão por premissa/evidência incorreta), mas jamais
///   afirma domínio (`VerifiedCompetence`) ou transferência.
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

    if input.evidence_challenge_passed == Some(false) {
        return DiagnosticStatus::IllusoryCompetence;
    }

    let acc = (input.correct_attempts as f64) / (input.non_blank_attempts as f64);
    if !input.has_spaced_retention || acc < 0.80 - 1e-9 || input.last_confidence < 2 {
        return DiagnosticStatus::InsufficientEvidence;
    }

    if input.distinct_questions < MIN_QUESTIONS_FOR_MASTERY {
        return DiagnosticStatus::EvidenceInProgress;
    }

    if input.has_independent_transfer {
        DiagnosticStatus::VerifiedCompetence
    } else {
        DiagnosticStatus::ConsolidatedNoTransfer
    }
}

/// Avalia transferência entre bancas sobre conjunto separado de questões humanas inéditas.
/// Quando não há questão válida de outra banca, registra explicitamente `TransferUnmeasured`.
pub fn evaluate_transfer_status(input: &TransferInputs) -> TransferStatus {
    if input.available_cross_board_questions == 0 && input.transfer_attempts == 0 {
        return TransferStatus::TransferUnmeasured;
    }
    if input.transfer_attempts == 0 {
        return TransferStatus::TransferAvailable;
    }
    let safe_grounded = input.transfer_correct_grounded.min(input.transfer_attempts);
    let ratio = (safe_grounded as f64) / (input.transfer_attempts as f64);
    if safe_grounded == 0 || ratio < 0.50 - 1e-9 {
        return TransferStatus::TransferFailed;
    }
    if input.distinct_questions >= MIN_QUESTIONS_FOR_MASTERY && ratio >= 0.75 - 1e-9 {
        TransferStatus::TransferVerified
    } else {
        TransferStatus::TransferProvisional
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scarcity_below_15_questions_identifies_errors_and_blocks_mastery_claim() {
        let status = classify_diagnostic(&DiagnosticInputs {
            distinct_questions: 14,
            non_blank_attempts: 15,
            correct_attempts: 14,
            last_is_correct: true,
            last_confidence: 3,
            had_prior_verified_correct: true,
            has_spaced_retention: true,
            evidence_challenge_passed: Some(true),
            has_independent_transfer: true,
        });
        assert_eq!(status, DiagnosticStatus::EvidenceInProgress);

        let illusory = classify_diagnostic(&DiagnosticInputs {
            distinct_questions: 6,
            non_blank_attempts: 6,
            correct_attempts: 6,
            last_is_correct: true,
            last_confidence: 3,
            had_prior_verified_correct: true,
            has_spaced_retention: true,
            evidence_challenge_passed: Some(false),
            has_independent_transfer: false,
        });
        assert_eq!(illusory, DiagnosticStatus::IllusoryCompetence);
    }

    #[test]
    fn missing_cross_board_question_records_transfer_unmeasured() {
        assert_eq!(
            evaluate_transfer_status(&TransferInputs {
                distinct_questions: 20,
                available_cross_board_questions: 0,
                transfer_attempts: 0,
                transfer_correct_grounded: 0,
            }),
            TransferStatus::TransferUnmeasured
        );
    }

    #[test]
    fn concept_id_falls_back_cleanly_to_subtopic() {
        assert_eq!(resolve_unit_id(Some("CON_LTN"), "MFIN_02_001"), "CON_LTN");
        assert_eq!(resolve_unit_id(None, "MFIN_02_001"), "MFIN_02_001");
        assert_eq!(resolve_unit_id(Some("  "), "MFIN_02_001"), "MFIN_02_001");
    }
}
