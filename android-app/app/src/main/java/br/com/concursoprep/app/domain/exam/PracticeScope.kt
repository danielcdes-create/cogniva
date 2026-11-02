package br.com.concursoprep.app.domain.exam

import br.com.concursoprep.app.domain.model.QuizMode

/** Personal queues and general shuffle do not inherit the planner's open tab. */
object PracticeScope {
    fun contest(mode: QuizMode, requested: String?): String? = when (mode) {
        QuizMode.SHUFFLE, QuizMode.SRS_REVIEW, QuizMode.ERROR_NOTEBOOK,
        QuizMode.MOST_BADASS, QuizMode.COMMON_TRUNK -> null
        else -> requested?.takeIf { it.isNotBlank() && it != "Geral" }
    }
    fun selected(exams: List<StoredExam>, current: String): String =
        exams.firstOrNull { it.examId == current && it.lifecycleStatus !in setOf("ARQUIVADO","EXCLUIDO") }?.examId
            ?: exams.firstOrNull { it.isActiveInLadder && it.lifecycleStatus !in setOf("ARQUIVADO","EXCLUIDO") }?.examId
            ?: exams.firstOrNull { it.lifecycleStatus !in setOf("ARQUIVADO","EXCLUIDO") }?.examId.orEmpty()
}
