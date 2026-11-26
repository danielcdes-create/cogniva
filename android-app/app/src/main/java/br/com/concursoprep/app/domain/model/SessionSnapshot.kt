package br.com.concursoprep.app.domain.model

import br.com.concursoprep.app.domain.exam.CognitiveStrategy
import br.com.concursoprep.app.domain.exam.DeclaredConfidence
import br.com.concursoprep.app.domain.exam.StoredExam
import kotlinx.serialization.Serializable

@Serializable
data class SessionAnswer(
    val chosenIndex: Int,
    val confidenceLevel: Int,
    val timeSpentSeconds: Int,
    val submitted: Boolean = false,
    val isBlank: Boolean = false,
    val confidenceExplicit: Boolean = false,
    val selectedEvidenceId: String? = null,
    val declaredConfidence: DeclaredConfidence? = null,
    val cognitiveStrategy: CognitiveStrategy? = null,
    val strategySkipped: Boolean = false,
    val activeSeconds: Int? = null,
    val answerChangeCount: Int = 0,
    val initialChosenIndex: Int? = null,
    val consultedResources: List<String> = emptyList(),
    val focusLossCount: Int = 0,
    val ctvCompleted: Boolean = false,
    val ctvDiagnosticTitle: String? = null,
    val ctvDiagnosticMessage: String? = null
)

@Serializable
data class SessionSnapshot(
    val sessionId: String,
    val ownerUserId: Long,
    val questions: List<QuestionDto>,
    val mode: QuizMode,
    val exam: StoredExam?,
    val deadlineEpochMs: Long? = null,
    val currentIndex: Int = 0,
    val answers: Map<String, SessionAnswer> = emptyMap(),
    val finished: Boolean = false,
    val reviewing: Boolean = false,
    val questionStartedAtEpochMs: Long = System.currentTimeMillis(),
    val revision: Long = 0,
    val sessionStartedAtEpochMs: Long = System.currentTimeMillis(),
    val interrupted: Boolean = false,
    val isSmartEntry: Boolean = false,
    val sessionCtvCount: Int = 0,
    val attemptsSinceLastCtv: Int = 0
)
