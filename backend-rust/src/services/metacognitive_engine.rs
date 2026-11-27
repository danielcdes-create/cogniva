//! Motor Metacognitivo e Matriz de Diagnóstico Preliminar (Evolução Cogniva).
//!
//! Separa explicitamente:
//! 1. Autopercepção declarada pelo aluno (`DeclaredConfidence` + `CognitiveStrategy`)
//! 2. Evidência observada (`is_correct`, `BehavioralEvidence`, histórico de exposição)
//! 3. Inferência preliminar do sistema (`PreliminaryDiagnosis`) e seu grau de sustentação (`SupportLevel`)
//! 4. Prioridade sugerida para Verificação Adaptativa de Domínio Conceitual (`CtvTriggerPriority`).
//!
//! Nenhuma tentativa isolada determina estado cognitivo definitivo, e nenhuma probabilidade
//! numérica arbitrária é atribuída sem calibração empírica (`calibration_engine`).

use serde::{Deserialize, Serialize};

pub const METACOGNITIVE_POLICY_VERSION: &str = "metacog-v1.0";

/// Escala ordinal de cinco níveis de confiança declarada pré-gabarito.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DeclaredConfidence {
    Guess,
    Low,
    Medium,
    High,
    VeryHigh,
}

impl DeclaredConfidence {
    pub const ALL: [Self; 5] = [
        Self::Guess,
        Self::Low,
        Self::Medium,
        Self::High,
        Self::VeryHigh,
    ];

    pub const fn code(self) -> &'static str {
        match self {
            Self::Guess => "GUESS",
            Self::Low => "LOW",
            Self::Medium => "MEDIUM",
            Self::High => "HIGH",
            Self::VeryHigh => "VERY_HIGH",
        }
    }

    pub const fn ordinal(self) -> u8 {
        match self {
            Self::Guess => 1,
            Self::Low => 2,
            Self::Medium => 3,
            Self::High => 4,
            Self::VeryHigh => 5,
        }
    }

    pub const fn label_pt(self) -> &'static str {
        match self {
            Self::Guess => "Chutei",
            Self::Low => "Pouco confiante",
            Self::Medium => "Parcialmente confiante",
            Self::High => "Muito confiante",
            Self::VeryHigh => "Praticamente certo",
        }
    }

    /// Mapeamento determinístico para o campo legado `confidence_level` (`0..=3`),
    /// preservando compatibilidade retroativa com SRS, retenção e contratos históricos.
    pub const fn to_legacy_band(self) -> i32 {
        match self {
            Self::Guess => 1,
            Self::Low => 1,
            Self::Medium => 2,
            Self::High => 3,
            Self::VeryHigh => 3,
        }
    }

    /// Reconstrói um nível ordinal aproximado a partir de registros históricos legados (`0..=3`)
    /// quando `declared_confidence` ainda não estava presente. Tentativas sem confirmação explícita
    /// de confiança (`explicit == false`, ex.: valor padrão 2) permanecem `None` para nunca contaminar
    /// calibração nem diagnóstico metacognitivo.
    pub const fn from_legacy_band(band: i32, explicit: bool) -> Option<Self> {
        if !explicit {
            return None;
        }
        match band {
            0 => Some(Self::Guess),
            1 => Some(Self::Low),
            2 => Some(Self::Medium),
            _ => Some(Self::High),
        }
    }

    pub const fn is_high(self) -> bool {
        matches!(self, Self::High | Self::VeryHigh)
    }

    pub const fn is_low_or_guess(self) -> bool {
        matches!(self, Self::Guess | Self::Low)
    }
}

/// Estratégia cognitiva autodeclarada antes da revelação do gabarito ("Como você chegou à sua resposta?").
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CognitiveStrategy {
    ConceptualKnowledge,
    LogicalDeduction,
    Elimination,
    PatternRecognition,
    Intuition,
    RandomGuess,
    Unspecified,
}

impl CognitiveStrategy {
    pub const ALL: [Self; 7] = [
        Self::ConceptualKnowledge,
        Self::LogicalDeduction,
        Self::Elimination,
        Self::PatternRecognition,
        Self::Intuition,
        Self::RandomGuess,
        Self::Unspecified,
    ];

    pub const fn code(self) -> &'static str {
        match self {
            Self::ConceptualKnowledge => "CONCEPTUAL_KNOWLEDGE",
            Self::LogicalDeduction => "LOGICAL_DEDUCTION",
            Self::Elimination => "ELIMINATION",
            Self::PatternRecognition => "PATTERN_RECOGNITION",
            Self::Intuition => "INTUITION",
            Self::RandomGuess => "RANDOM_GUESS",
            Self::Unspecified => "UNSPECIFIED",
        }
    }

    pub const fn label_pt(self) -> &'static str {
        match self {
            Self::ConceptualKnowledge => "Conhecia e apliquei o conceito.",
            Self::LogicalDeduction => "Deduzi por raciocínio lógico.",
            Self::Elimination => "Eliminei alternativas.",
            Self::PatternRecognition => "Reconheci termos familiares.",
            Self::Intuition => "Respondi por intuição.",
            Self::RandomGuess => "Escolhi aleatoriamente.",
            Self::Unspecified => "Não sei identificar.",
        }
    }

    /// Dedução lógica e eliminação são estratégias cognitivas funcionais válidas (não inferiores).
    pub const fn is_grounded_or_functional(self) -> bool {
        matches!(
            self,
            Self::ConceptualKnowledge | Self::LogicalDeduction | Self::Elimination
        )
    }

    pub const fn is_surface_or_fragile(self) -> bool {
        matches!(
            self,
            Self::PatternRecognition | Self::Intuition | Self::RandomGuess
        )
    }
}

/// Telemetria comportamental complementar registrada durante a tentativa original.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct BehavioralEvidence {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_seconds: Option<i32>,
    #[serde(default)]
    pub total_elapsed_seconds: i32,
    #[serde(default)]
    pub answer_change_count: u16,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub initial_chosen_index: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub final_chosen_index: Option<usize>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub consulted_resources: Vec<String>,
    #[serde(default)]
    pub focus_loss_count: u16,
    #[serde(default)]
    pub session_interrupted: bool,
    #[serde(default)]
    pub prior_question_exposures: u32,
    #[serde(default)]
    pub prior_concept_exposures: u32,
    #[serde(default)]
    pub prior_gabarito_exposed: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PreliminaryDiagnosis {
    PossibleConceptualMastery,
    InsecureKnowledge,
    PossibleCasualHit,
    UnconfirmedMastery,
    PossibleMasteryIllusion,
    FunctionalReasoning,
    PossibleConceptualMisconception,
    PossibleKnowledgeGap,
    UndemonstratedKnowledge,
    StrategicOmission,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SupportLevel {
    Low,
    Moderate,
    High,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CtvTriggerPriority {
    None,
    PeriodicSample,
    Optional,
    Recommended,
    High,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetacognitiveMatrixInputs {
    pub is_blank: bool,
    pub is_correct: bool,
    pub declared_confidence: Option<DeclaredConfidence>,
    pub cognitive_strategy: Option<CognitiveStrategy>,
    pub consistent_history: bool,
    pub rationale_contradicted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MetacognitiveAssessment {
    pub preliminary_diagnosis: PreliminaryDiagnosis,
    pub inference_support: SupportLevel,
    pub ctv_priority: CtvTriggerPriority,
    #[serde(default)]
    pub pedagogical_feedback_pt: String,
    pub self_perception_summary: String,
    pub observed_evidence_summary: String,
    pub system_inference_message: String,
    pub strategy_skipped: bool,
    pub policy_version: String,
}

pub fn classify_preliminary_matrix(
    inputs: &MetacognitiveMatrixInputs,
) -> (PreliminaryDiagnosis, SupportLevel, CtvTriggerPriority) {
    if inputs.is_blank {
        return (
            PreliminaryDiagnosis::StrategicOmission,
            SupportLevel::Moderate,
            CtvTriggerPriority::None,
        );
    }

    let is_pure_or_unconfirmed_guess = inputs.cognitive_strategy
        == Some(CognitiveStrategy::RandomGuess)
        || (inputs.declared_confidence == Some(DeclaredConfidence::Guess)
            && matches!(
                inputs.cognitive_strategy,
                None | Some(CognitiveStrategy::RandomGuess | CognitiveStrategy::Unspecified)
            ));
    let is_partial_guess = inputs.declared_confidence == Some(DeclaredConfidence::Guess)
        && !is_pure_or_unconfirmed_guess;

    if inputs.is_correct {
        if inputs.rationale_contradicted {
            return (
                PreliminaryDiagnosis::PossibleMasteryIllusion,
                SupportLevel::High,
                CtvTriggerPriority::High,
            );
        }
        if is_pure_or_unconfirmed_guess {
            return (
                PreliminaryDiagnosis::PossibleCasualHit,
                if inputs.cognitive_strategy == Some(CognitiveStrategy::RandomGuess) {
                    SupportLevel::High
                } else {
                    SupportLevel::Moderate
                },
                CtvTriggerPriority::High,
            );
        }
        if is_partial_guess {
            return (
                PreliminaryDiagnosis::UnconfirmedMastery,
                SupportLevel::Moderate,
                CtvTriggerPriority::Recommended,
            );
        }
        if inputs.declared_confidence == Some(DeclaredConfidence::Low) {
            return (
                PreliminaryDiagnosis::InsecureKnowledge,
                SupportLevel::Moderate,
                CtvTriggerPriority::Recommended,
            );
        }
        if matches!(
            inputs.cognitive_strategy,
            Some(CognitiveStrategy::LogicalDeduction | CognitiveStrategy::Elimination)
        ) {
            return (
                PreliminaryDiagnosis::FunctionalReasoning,
                SupportLevel::Moderate,
                CtvTriggerPriority::Optional,
            );
        }
        if matches!(
            inputs.cognitive_strategy,
            Some(CognitiveStrategy::PatternRecognition | CognitiveStrategy::Intuition)
        ) {
            return (
                PreliminaryDiagnosis::UnconfirmedMastery,
                SupportLevel::Moderate,
                CtvTriggerPriority::Recommended,
            );
        }
        if inputs
            .declared_confidence
            .is_some_and(DeclaredConfidence::is_high)
            && inputs.consistent_history
        {
            return (
                PreliminaryDiagnosis::PossibleConceptualMastery,
                SupportLevel::High,
                CtvTriggerPriority::PeriodicSample,
            );
        }
        (
            PreliminaryDiagnosis::UnconfirmedMastery,
            if inputs.declared_confidence.is_some() || inputs.cognitive_strategy.is_some() {
                SupportLevel::Moderate
            } else {
                SupportLevel::Low
            },
            CtvTriggerPriority::Recommended,
        )
    } else {
        if is_pure_or_unconfirmed_guess {
            return (
                PreliminaryDiagnosis::UndemonstratedKnowledge,
                SupportLevel::Moderate,
                CtvTriggerPriority::None,
            );
        }
        if is_partial_guess {
            return (
                PreliminaryDiagnosis::PossibleKnowledgeGap,
                SupportLevel::Moderate,
                CtvTriggerPriority::Optional,
            );
        }
        if inputs
            .declared_confidence
            .is_some_and(DeclaredConfidence::is_high)
        {
            return (
                PreliminaryDiagnosis::PossibleConceptualMisconception,
                SupportLevel::High,
                CtvTriggerPriority::High,
            );
        }
        (
            PreliminaryDiagnosis::PossibleKnowledgeGap,
            SupportLevel::Moderate,
            CtvTriggerPriority::Optional,
        )
    }
}

/// Gera feedback pedagógico breve, claro e não-julgamental para a camada primária do aluno (Seção 7.3).
pub fn pedagogical_feedback_pt(
    inputs: &MetacognitiveMatrixInputs,
    diagnosis: PreliminaryDiagnosis,
    has_multi_attempt_evidence: bool,
) -> &'static str {
    if inputs.is_blank {
        return "Questão deixada em branco. Nenhuma inferência de domínio foi registrada.";
    }
    if inputs.is_correct {
        if inputs.rationale_contradicted || diagnosis == PreliminaryDiagnosis::PossibleCasualHit {
            return "Você acertou, mas ainda precisamos verificar sua compreensão.";
        }
        if inputs.declared_confidence == Some(DeclaredConfidence::Guess)
            && matches!(
                inputs.cognitive_strategy,
                Some(CognitiveStrategy::Elimination | CognitiveStrategy::LogicalDeduction)
            )
        {
            return "Você eliminou alternativas, mas ainda respondeu com incerteza. Sua compreensão precisa ser confirmada.";
        }
        if matches!(
            inputs.cognitive_strategy,
            Some(CognitiveStrategy::PatternRecognition | CognitiveStrategy::Intuition)
        ) {
            return "Você reconheceu elementos importantes. Sua compreensão ainda precisa ser confirmada.";
        }
        if diagnosis == PreliminaryDiagnosis::InsecureKnowledge {
            return "Você acertou, mas ainda demonstra insegurança neste conceito.";
        }
        if diagnosis == PreliminaryDiagnosis::FunctionalReasoning {
            return if inputs.cognitive_strategy == Some(CognitiveStrategy::Elimination) {
                "Você utilizou uma estratégia de eliminação eficaz."
            } else {
                "Você utilizou raciocínio lógico consistente nesta questão."
            };
        }
        if diagnosis == PreliminaryDiagnosis::PossibleConceptualMastery
            || inputs
                .declared_confidence
                .is_some_and(DeclaredConfidence::is_high)
        {
            return "Você acertou com segurança.";
        }
        if inputs.declared_confidence.is_none()
            && inputs.cognitive_strategy.is_none()
            && !has_multi_attempt_evidence
        {
            return "Ainda não há informações suficientes para avaliar seu domínio.";
        }
        "Você acertou, mas sua compreensão ainda precisa ser confirmada."
    } else {
        match diagnosis {
            PreliminaryDiagnosis::PossibleConceptualMisconception => {
                "Sua resposta indica um possível equívoco conceitual que merece revisão."
            }
            PreliminaryDiagnosis::UndemonstratedKnowledge => {
                "Seu domínio deste conceito ainda não foi demonstrado."
            }
            _ => "Este conceito pode precisar de reforço.",
        }
    }
}

/// Gera mensagens proporcionais à evidência observada, evitando rótulos categóricos definitivos.
pub fn inference_message_pt(
    diagnosis: PreliminaryDiagnosis,
    has_multi_attempt_evidence: bool,
) -> &'static str {
    match diagnosis {
        PreliminaryDiagnosis::PossibleConceptualMastery => {
            "Acerto consistente com alta confiança. Evidência favorável à consolidação progressiva do conceito."
        }
        PreliminaryDiagnosis::InsecureKnowledge => {
            "Acerto registrado com baixa confiança declarada. Conhecimento ainda inseguro; vale validar em outro contexto."
        }
        PreliminaryDiagnosis::PossibleCasualHit => {
            "Acerto registrado com indicação de chute. O domínio conceitual ainda não foi confirmado."
        }
        PreliminaryDiagnosis::UnconfirmedMastery => {
            "Acerto registrado, mas o domínio conceitual ainda não foi confirmado."
        }
        PreliminaryDiagnosis::PossibleMasteryIllusion => {
            if has_multi_attempt_evidence {
                "Foram identificadas evidências de possível superestimação do domínio deste conceito."
            } else {
                "Acerto registrado, porém o fundamento selecionado diverge da regra cobrada; o domínio conceitual ainda não foi confirmado."
            }
        }
        PreliminaryDiagnosis::FunctionalReasoning => {
            "Acerto obtido por raciocínio funcional (dedução ou eliminação). Estratégia válida; recomenda-se consolidar também a regra direta."
        }
        PreliminaryDiagnosis::PossibleConceptualMisconception => {
            if has_multi_attempt_evidence {
                "Foram identificadas evidências recorrentes de possível equívoco conceitual neste tema."
            } else {
                "Resposta incorreta com confiança elevada: indício preliminar de possível equívoco na interpretação da regra ou exceção."
            }
        }
        PreliminaryDiagnosis::PossibleKnowledgeGap => {
            "Resposta incorreta com baixa confiança: indício preliminar de lacuna neste conceito, útil para revisão direcionada."
        }
        PreliminaryDiagnosis::UndemonstratedKnowledge => {
            "Conhecimento ainda não demonstrado nesta tentativa (resposta incorreta por tentativa aleatória)."
        }
        PreliminaryDiagnosis::StrategicOmission => {
            "Questão deixada em branco como decisão estratégica sob incerteza; conhecimento do item não avaliado."
        }
    }
}

pub fn build_assessment(
    inputs: &MetacognitiveMatrixInputs,
    behavioral: &BehavioralEvidence,
    has_multi_attempt_evidence: bool,
) -> MetacognitiveAssessment {
    let (preliminary_diagnosis, inference_support, ctv_priority) =
        classify_preliminary_matrix(inputs);
    let conf_text = inputs
        .declared_confidence
        .map(DeclaredConfidence::label_pt)
        .unwrap_or("Não informada");
    let strat_text = inputs
        .cognitive_strategy
        .map(CognitiveStrategy::label_pt)
        .unwrap_or("Etapa pulada / não informada");
    let self_perception_summary =
        format!("Confiança declarada: {conf_text} · Estratégia: {strat_text}");

    let secs = behavioral
        .active_seconds
        .unwrap_or(behavioral.total_elapsed_seconds)
        .max(0);
    let outcome_label = if inputs.is_blank {
        "Omissão em branco"
    } else if inputs.is_correct {
        "Resposta correta"
    } else {
        "Resposta incorreta"
    };
    let changes_note = if behavioral.answer_change_count > 0 {
        format!(
            ", {} troca(s) de alternativa",
            behavioral.answer_change_count
        )
    } else {
        String::new()
    };
    let resources_note = if !behavioral.consulted_resources.is_empty() {
        format!(
            ", {} recurso(s) auxiliar(es) consultado(s)",
            behavioral.consulted_resources.len()
        )
    } else {
        String::new()
    };
    let observed_evidence_summary =
        format!("{outcome_label} em {secs}s ativos{changes_note}{resources_note}.");

    MetacognitiveAssessment {
        preliminary_diagnosis,
        inference_support,
        ctv_priority,
        pedagogical_feedback_pt: pedagogical_feedback_pt(
            inputs,
            preliminary_diagnosis,
            has_multi_attempt_evidence,
        )
        .to_string(),
        self_perception_summary,
        observed_evidence_summary,
        system_inference_message: inference_message_pt(
            preliminary_diagnosis,
            has_multi_attempt_evidence,
        )
        .to_string(),
        strategy_skipped: inputs.cognitive_strategy.is_none(),
        policy_version: METACOGNITIVE_POLICY_VERSION.to_string(),
    }
}

/// Classificação explícita da força do fundamento em um acerto (Seção 3.1 / A06 e Seção 4.3).
/// Evita contar acertos sem declaração como fundamentados e separa estritamente evidências
/// retrospectivas legadas pós-gabarito (`RetrospectiveLegacyEvidence`) de declarações pré-gabarito.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HitGroundingCategory {
    NotApplicableError,
    ValidConceptualGrounding,
    ValidFunctionalStrategy,
    DivergentRationale,
    FragileOrGuess,
    RetrospectiveLegacyEvidence,
    UnknownGrounding,
}

impl HitGroundingCategory {
    pub const fn code(self) -> &'static str {
        match self {
            Self::NotApplicableError => "NOT_APPLICABLE_ERROR",
            Self::ValidConceptualGrounding => "VALID_CONCEPTUAL_GROUNDING",
            Self::ValidFunctionalStrategy => "VALID_FUNCTIONAL_STRATEGY",
            Self::DivergentRationale => "DIVERGENT_RATIONALE",
            Self::FragileOrGuess => "FRAGILE_OR_GUESS",
            Self::RetrospectiveLegacyEvidence => "RETROSPECTIVE_LEGACY_EVIDENCE",
            Self::UnknownGrounding => "UNKNOWN_GROUNDING",
        }
    }

    /// Só concede crédito positivo na dimensão de compreensão quando houver evidência positiva pré-exposição
    /// (conhecimento conceitual, dedução lógica ou eliminação válidas sem divergência).
    /// Evidência legada pós-gabarito (`RetrospectiveLegacyEvidence`) nunca é equiparada automaticamente.
    pub const fn credits_comprehension(self) -> bool {
        matches!(
            self,
            Self::ValidConceptualGrounding | Self::ValidFunctionalStrategy
        )
    }

    pub const fn is_evaluable(self) -> bool {
        matches!(
            self,
            Self::ValidConceptualGrounding
                | Self::ValidFunctionalStrategy
                | Self::DivergentRationale
                | Self::FragileOrGuess
        )
    }
}

pub fn classify_hit_grounding(
    is_correct: bool,
    declared_confidence: Option<DeclaredConfidence>,
    cognitive_strategy: Option<CognitiveStrategy>,
    evidence_compatible: Option<bool>,
) -> HitGroundingCategory {
    if !is_correct {
        return HitGroundingCategory::NotApplicableError;
    }
    if evidence_compatible == Some(false) {
        return HitGroundingCategory::DivergentRationale;
    }
    if declared_confidence.is_some_and(DeclaredConfidence::is_low_or_guess)
        || cognitive_strategy.is_some_and(CognitiveStrategy::is_surface_or_fragile)
    {
        return HitGroundingCategory::FragileOrGuess;
    }
    if matches!(
        cognitive_strategy,
        Some(CognitiveStrategy::LogicalDeduction | CognitiveStrategy::Elimination)
    ) {
        return HitGroundingCategory::ValidFunctionalStrategy;
    }
    if cognitive_strategy == Some(CognitiveStrategy::ConceptualKnowledge) {
        return HitGroundingCategory::ValidConceptualGrounding;
    }
    if evidence_compatible == Some(true) {
        return HitGroundingCategory::RetrospectiveLegacyEvidence;
    }
    HitGroundingCategory::UnknownGrounding
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a06_unobserved_hit_stays_unknown_grounding_while_deduction_with_compatible_evidence_credits_once(
    ) {
        // Caso 1: acerto sem estratégia nem justificativa observável -> desempenho bruto, fundamento desconhecido!
        let g_unobserved = classify_hit_grounding(true, Some(DeclaredConfidence::High), None, None);
        assert_eq!(g_unobserved, HitGroundingCategory::UnknownGrounding);
        assert!(!g_unobserved.credits_comprehension());
        assert!(!g_unobserved.is_evaluable());

        // Caso 2: acerto por dedução lógica com justificativa compatível -> estratégia funcional válida + crédito de compreensão
        let g_deduction = classify_hit_grounding(
            true,
            Some(DeclaredConfidence::Medium),
            Some(CognitiveStrategy::LogicalDeduction),
            Some(true),
        );
        assert_eq!(g_deduction, HitGroundingCategory::ValidFunctionalStrategy);
        assert!(g_deduction.credits_comprehension());
        assert!(g_deduction.is_evaluable());

        // Caso 3: acerto por dedução, porém justificativa incompatível com o gabarito -> preserva divergência!
        let g_contradicted = classify_hit_grounding(
            true,
            Some(DeclaredConfidence::High),
            Some(CognitiveStrategy::LogicalDeduction),
            Some(false),
        );
        assert_eq!(g_contradicted, HitGroundingCategory::DivergentRationale);
        assert!(!g_contradicted.credits_comprehension());
    }

    #[test]
    fn metacognitive_matrix_never_emits_categorical_illusory_label_on_single_attempt() {
        let inputs = MetacognitiveMatrixInputs {
            is_blank: false,
            is_correct: true,
            declared_confidence: Some(DeclaredConfidence::High),
            cognitive_strategy: Some(CognitiveStrategy::ConceptualKnowledge),
            consistent_history: false,
            rationale_contradicted: true,
        };
        let assessment = build_assessment(&inputs, &BehavioralEvidence::default(), false);
        assert_eq!(
            assessment.preliminary_diagnosis,
            PreliminaryDiagnosis::PossibleMasteryIllusion
        );
        assert!(!assessment
            .system_inference_message
            .contains("Competência ilusória detectada"));
    }

    #[test]
    fn logical_deduction_and_elimination_are_classified_as_functional_reasoning() {
        for strategy in [
            CognitiveStrategy::LogicalDeduction,
            CognitiveStrategy::Elimination,
        ] {
            let (diag, _, _) = classify_preliminary_matrix(&MetacognitiveMatrixInputs {
                is_blank: false,
                is_correct: true,
                declared_confidence: Some(DeclaredConfidence::Medium),
                cognitive_strategy: Some(strategy),
                consistent_history: false,
                rationale_contradicted: false,
            });
            assert_eq!(diag, PreliminaryDiagnosis::FunctionalReasoning);
        }
    }

    #[test]
    fn guess_after_elimination_is_differentiated_from_pure_random_guess_and_unconfirmed_guess() {
        let (pure_diag, pure_sup, pure_prio) =
            classify_preliminary_matrix(&MetacognitiveMatrixInputs {
                is_blank: false,
                is_correct: true,
                declared_confidence: Some(DeclaredConfidence::Guess),
                cognitive_strategy: Some(CognitiveStrategy::RandomGuess),
                consistent_history: false,
                rationale_contradicted: false,
            });
        assert_eq!(pure_diag, PreliminaryDiagnosis::PossibleCasualHit);
        assert_eq!(pure_sup, SupportLevel::High);
        assert_eq!(pure_prio, CtvTriggerPriority::High);

        let unconfirmed_inputs = MetacognitiveMatrixInputs {
            is_blank: false,
            is_correct: true,
            declared_confidence: Some(DeclaredConfidence::Guess),
            cognitive_strategy: None,
            consistent_history: false,
            rationale_contradicted: false,
        };
        let unconfirmed_assessment =
            build_assessment(&unconfirmed_inputs, &BehavioralEvidence::default(), false);
        assert_eq!(
            unconfirmed_assessment.preliminary_diagnosis,
            PreliminaryDiagnosis::PossibleCasualHit
        );
        assert_eq!(
            unconfirmed_assessment.inference_support,
            SupportLevel::Moderate
        );
        assert!(unconfirmed_assessment.strategy_skipped);

        let elim_guess_inputs = MetacognitiveMatrixInputs {
            is_blank: false,
            is_correct: true,
            declared_confidence: Some(DeclaredConfidence::Guess),
            cognitive_strategy: Some(CognitiveStrategy::Elimination),
            consistent_history: false,
            rationale_contradicted: false,
        };
        let elim_assessment =
            build_assessment(&elim_guess_inputs, &BehavioralEvidence::default(), false);
        assert_eq!(
            elim_assessment.preliminary_diagnosis,
            PreliminaryDiagnosis::UnconfirmedMastery
        );
        assert_eq!(
            elim_assessment.ctv_priority,
            CtvTriggerPriority::Recommended
        );
        assert!(elim_assessment
            .pedagogical_feedback_pt
            .contains("eliminou alternativas"));
    }

    #[test]
    fn retrospective_post_exposure_evidence_never_auto_credits_pre_exposure_comprehension() {
        let g_retrospective =
            classify_hit_grounding(true, Some(DeclaredConfidence::High), None, Some(true));
        assert_eq!(
            g_retrospective,
            HitGroundingCategory::RetrospectiveLegacyEvidence
        );
        assert!(!g_retrospective.credits_comprehension());
        assert!(!g_retrospective.is_evaluable());
    }

    #[test]
    fn from_legacy_band_never_fabricates_confidence_when_not_explicit() {
        assert_eq!(DeclaredConfidence::from_legacy_band(1, false), None);
        assert_eq!(DeclaredConfidence::from_legacy_band(2, false), None);
        assert_eq!(DeclaredConfidence::from_legacy_band(3, false), None);
        assert_eq!(
            DeclaredConfidence::from_legacy_band(1, true),
            Some(DeclaredConfidence::Low)
        );
        assert_eq!(
            DeclaredConfidence::from_legacy_band(2, true),
            Some(DeclaredConfidence::Medium)
        );
        assert_eq!(
            DeclaredConfidence::from_legacy_band(3, true),
            Some(DeclaredConfidence::High)
        );
    }
}
