package br.com.concursoprep.app.domain.model

import br.com.concursoprep.app.domain.exam.StoredExam
import kotlinx.serialization.Serializable

@Serializable
data class SessionAnswer(val chosenIndex: Int, val confidenceLevel: Int, val timeSpentSeconds: Int, val submitted: Boolean = false)
@Serializable
data class SessionSnapshot(
    val sessionId: String,
    val ownerUserId: Long,
    val questions: List<QuestionDto>,
    val mode: QuizMode,
    val exam: StoredExam?,
    val deadlineEpochMs: Long? = null,
    val currentIndex: Int = 0,
    val answers: Map<String,SessionAnswer> = emptyMap(),
    val finished: Boolean = false,
    val reviewing: Boolean = false,
    val questionStartedAtEpochMs: Long = System.currentTimeMillis(),
    val revision: Long = 0
)
