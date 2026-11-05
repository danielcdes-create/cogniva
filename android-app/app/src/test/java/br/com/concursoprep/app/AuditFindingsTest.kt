package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.AccountDatabaseOpener
import br.com.concursoprep.app.data.local.AccountDatabaseSession
import br.com.concursoprep.app.domain.exam.ExamEngine
import br.com.concursoprep.app.domain.exam.StoredExam
import br.com.concursoprep.app.domain.exam.StoredSubject
import br.com.concursoprep.app.domain.exam.StoredTopic
import br.com.concursoprep.app.domain.model.AccountLinkMessages
import br.com.concursoprep.app.domain.model.LearningEvent
import br.com.concursoprep.app.domain.model.LearningProjection
import br.com.concursoprep.app.domain.model.LearningRecord
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import br.com.concursoprep.app.domain.model.objectivePracticeQuestions
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class AuditFindingsTest {
    private class Opened(val name: String) {
        var closed = false
    }

    private fun question() = QuestionDto(
        id = "q",
        questionRevision = "body-v1",
        question = "Synthetic",
        answers = listOf("A", "B", "C", "D", "E"),
        correctAnswerIndices = listOf(1),
        disciplina = "Matemática",
        source = "Synthetic",
        level = "Médio",
        canonicalSubjectId = "MAT",
        canonicalTopicId = "MAT_01_001",
        knowledgeProjectionVersion = 1,
        knowledgeBindingVerified = true,
    )

    private fun event(id: String, correct: Boolean, confidence: Int, at: String) =
        LearningEvent(id, at, correct, confidence, 10, "body-v1")

    private fun exam(id: String, status: String, topics: List<StoredTopic>) = StoredExam(
        examId = id,
        title = id,
        shortName = id,
        institution = "I",
        role = "R",
        trunkSlug = "fiscal",
        lifecycleStatus = status,
        examBoards = emptyList(),
        dateType = "UNDEFINED",
        exactDateIso = null,
        estimatedWindowLabel = null,
        estimatedSortDateIso = "",
        historicalCutoffScore = 0.0,
        isActiveInLadder = true,
        ladderPriority = 1,
        subjects = listOf(
            StoredSubject("s", "FIN", "Finanças", "CONHECIMENTOS_GERAIS", 1.0, 1, "CORE", topics),
        ),
    )

    @Test
    fun emptyTopicsStayLiveAndExcludedExamsLeaveTheTrunkCard() {
        val live = ExamEngine.toLeverage(exam("a", "RUMOR_PREVISTO", emptyList()))
        assertFalse(live.subjects.single().archivedOnly)
        val archived = ExamEngine.toLeverage(
            exam("b", "RUMOR_PREVISTO", listOf(StoredTopic("t", "FIN_01", "FIN", "Orçamento", "ARQUIVADO"))),
        )
        assertTrue(archived.subjects.single().archivedOnly)
        val today = LocalDate.of(2026, 11, 4)
        val card = ExamEngine.selectCommonTrunkCard(
            listOf(
                ExamEngine.toLeverage(exam("a", "RUMOR_PREVISTO", emptyList())),
                ExamEngine.toLeverage(exam("b", "AUTORIZADO", emptyList())),
                ExamEngine.toLeverage(exam("gone", "EXCLUIDO", emptyList())),
                ExamEngine.toLeverage(exam("old", "ARQUIVADO", emptyList())),
            ),
            emptyMap(),
            null,
            8,
            today,
        )
        assertEquals(listOf("a", "b"), card!!.contestNames.sorted())
    }

    @Test
    fun notebookFollowsTheServerRuleAndPartialHistoryDoesNotInventAStreak() {
        val q = question()
        val kept = LearningProjection.project(
            null,
            q,
            listOf(
                event("err", false, 1, "2026-11-01T12:00:00Z"),
                event("ok", true, 0, "2026-11-02T12:00:00Z"),
            ),
        )
        assertTrue(kept.inErrorNotebook)
        val graduated = LearningProjection.project(
            null,
            q,
            listOf(
                event("err", false, 1, "2026-11-01T12:00:00Z"),
                event("ok1", true, 0, "2026-11-02T12:00:00Z"),
                event("ok2", true, 0, "2026-11-03T12:00:00Z"),
            ),
        )
        assertFalse(graduated.inErrorNotebook)
        val confident = LearningProjection.project(null, q, listOf(event("ok", true, 2, "2026-11-04T12:00:00Z")))
        assertFalse(confident.inErrorNotebook)
        val twoWrongsThenConfident = LearningProjection.project(
            null,
            q,
            listOf(
                event("w1", false, 1, "2026-11-01T12:00:00Z"),
                event("w2", false, 1, "2026-11-02T12:00:00Z"),
                event("c1", true, 2, "2026-11-03T12:00:00Z"),
            ),
        )
        assertFalse(twoWrongsThenConfident.inErrorNotebook)
        assertEquals(1, twoWrongsThenConfident.correct)
        assertEquals(3, twoWrongsThenConfident.attempts)
        val intervalDays = Duration.between(
            Instant.parse("2026-11-04T12:00:00Z"),
            Instant.parse(confident.nextReviewAt),
        ).toDays()
        assertTrue(intervalDays == 1L || intervalDays == 7L || intervalDays == 30L)
        val partialBase = LearningRecord(
            questionId = "q",
            disciplina = "Matemática",
            canonicalSubjectId = "MAT",
            canonicalTopicId = "MAT_01_001",
            attempts = 4,
            correct = 1,
            accuracyPct = 25.0,
            isMastered = false,
            inErrorNotebook = true,
            highConfidenceError = false,
            points = 0.2,
            nextReviewAt = "2026-11-05T12:00:00Z",
            events = listOf(event("old", false, 1, "2026-10-01T12:00:00Z")),
        )
        val partial = LearningProjection.project(
            partialBase,
            q,
            listOf(event("late", true, 3, "2026-11-04T15:00:00Z")),
        )
        assertTrue(partial.inErrorNotebook)
        assertEquals(0.2, partial.points, 0.0)
        assertEquals(5, partial.attempts)
    }

    @Test
    fun openEndedDecodesAndStaysOutOfObjectivePractice() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val raw = """[{"id":"open","question":"discursiva","question_type":"OPEN_ENDED","answers":[],"correct_answer_indices":[],"disciplina":"x","source":"s","level":"s"}]"""
        val decoded = json.decodeFromString<List<QuestionDto>>(raw)
        assertEquals(QuestionType.OPEN_ENDED, decoded.single().questionType)
        assertTrue(objectivePracticeQuestions(decoded).isEmpty())
        assertEquals(1, objectivePracticeQuestions(decoded + question()).size)
    }

    @Test
    fun reusedLinkCodeIsNotShownAsExpired() {
        assertEquals(
            AccountLinkMessages.REUSED,
            AccountLinkMessages.fromHttp(401, "Código de vínculo já utilizado em outra sessão"),
        )
        assertEquals(AccountLinkMessages.EXPIRED, AccountLinkMessages.fromHttp(401, "Sessão inválida ou expirada"))
        assertNotEquals(
            AccountLinkMessages.fromHttp(401, "Código de vínculo já utilizado em outra sessão"),
            AccountLinkMessages.fromHttp(401, "Sessão inválida ou expirada"),
        )
    }

    @Test
    fun switchingAccountsClosesThePreviousDatabaseAndAnOwnerlessLegacyFileIsNotUser1001() {
        val opened = mutableListOf<Opened>()
        val session = AccountDatabaseSession(
            openNamed = { name -> Opened(name).also { opened += it } },
            close = { it.closed = true },
        )
        val first = session.open(AccountDatabaseOpener.fileName(0L, 7L))
        val second = session.open(AccountDatabaseOpener.fileName(0L, 9L))
        assertTrue(first.closed)
        assertFalse(second.closed)
        assertEquals("cogniva-7.db", first.name)
        assertEquals("cogniva-9.db", second.name)
        assertNull(AccountDatabaseOpener.legacyOwnerToStore(databaseExists = true, alreadyStored = false, storedUserId = null))
        assertEquals(42L, AccountDatabaseOpener.legacyOwnerToStore(true, false, 42L))
        assertNull(AccountDatabaseOpener.legacyOwnerToStore(true, true, 1001L))
        assertNotEquals("concurso_prep.db", AccountDatabaseOpener.fileName(0L, 1001L))
        assertEquals("concurso_prep.db", AccountDatabaseOpener.fileName(42L, 42L))
    }
}
