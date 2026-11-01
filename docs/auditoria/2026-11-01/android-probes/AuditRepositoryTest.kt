package br.com.concursoprep.app.audit

import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Estes asserts documentam defeitos; nao sao criterios de aceite para a correcao.
class AuditRepositoryTest {
    private inline fun <reified T> fake(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T

    private fun attempt() = PendingAttemptEntity(
        localId = 1L, questionId = "000000000000000000000001", chosenIndex = 0,
        chosenText = "A", isCorrect = true, timeSpentSeconds = 10, confidenceLevel = 3,
        sessionUuid = "audit-session", quizMode = "STANDARD", examBlock = null,
        contestId = "CPNU", userNote = null
    )

    private fun repository(
        pending: MutableList<PendingAttemptEntity> = CopyOnWriteArrayList(),
        cached: List<CachedQuestionEntity> = emptyList(),
        onApi: (String) -> Any? = { throw IllegalStateException("synthetic offline") }
    ): ConcursoRepository {
        val dao = fake<ConcursoLocalDao> { name, args -> when (name) {
            "getAllPendingAttempts" -> pending.toList()
            "deleteSyncedAttempts" -> { val ids = args[0] as List<*>; pending.removeAll { ids.contains(it.localId) }; Unit }
            "getCachedQuestions" -> cached
            "getErrorNotebookQuestions" -> emptyList<CachedQuestionEntity>()
            "countPendingAttempts" -> pending.size
            "countCachedQuestions" -> cached.size
            else -> throw IllegalStateException("unexpected DAO $name")
        } }
        val exams = fake<ExamDao> { name, _ -> when(name) {
            "countExams" -> 1
            "listExams" -> emptyList<UserExamEntity>()
            "listPendingExams" -> emptyList<UserExamEntity>()
            "listMastery" -> emptyList<CanonicalMasteryEntity>()
            else -> throw IllegalStateException("unexpected ExamDAO $name")
        } }
        val api = fake<ConcursoApiService> { name, _ -> onApi(name) }
        return ConcursoRepository(api, dao, exams, Json { ignoreUnknownKeys = true })
    }

    @Test fun zeroProcessedAcknowledgementStillDeletesPendingAttempt() = runBlocking {
        val pending = CopyOnWriteArrayList(listOf(attempt()))
        val repo = repository(pending, onApi = { SubmitBatchResponseDto(0, emptyList()) })
        assertEquals(0, repo.syncPendingAttempts(123L))
        assertTrue(pending.isEmpty())
    }

    @Test fun networkFailureIsConvertedIntoSuccessfulReturnValue() = runBlocking {
        val pending = CopyOnWriteArrayList(listOf(attempt()))
        assertEquals(0, repository(pending).syncPendingAttempts(123L))
        assertEquals(1, pending.size)
    }

    @Test fun simultaneousSyncsSendTheSameAttemptTwice() = runBlocking {
        val pending = CopyOnWriteArrayList(listOf(attempt()))
        val barrier = CyclicBarrier(2)
        val sent = AtomicInteger()
        val repo = repository(pending, onApi = {
            sent.incrementAndGet()
            barrier.await(10, TimeUnit.SECONDS)
            SubmitBatchResponseDto(1, emptyList())
        })
        val jobs = List(2) { async(Dispatchers.Default) { repo.syncPendingAttempts(123L) } }
        jobs.awaitAll()
        assertEquals(2, sent.get())
        assertTrue(pending.isEmpty())
    }

    @Test fun emptyOfflineHistoryDisplaysInventedAchievements() = runBlocking {
        val repo = repository()
        val analytics = repo.getProgressAnalytics(123L)
        val today = repo.getTodayDashboard(123L)
        assertEquals(412, analytics.totalQuestionsPracticed)
        assertEquals(128, analytics.totalMasteredQuestions)
        assertEquals(18, today.questionsAnsweredToday)
        assertEquals(14L, today.pendingSrsReviewsCount)
    }

    @Test fun cacheRoundtripDropsStatsAndSyllabus() {
        val q = QuestionDto(id = "q", question = "synthetic", answers = listOf("A", "B"),
            correctAnswerIndices = listOf(1), disciplina = "Finanças Públicas", source = "audit",
            level = "Médio", syllabusTopicIds = listOf("topic-1"),
            userStats = UserQuestionBriefStats(10, 80.0, null, true, true))
        val cached = QuestionCacheMapping.toDto(QuestionCacheMapping.toEntity(q, Json), Json)
        assertNull(cached.userStats)
        assertTrue(cached.syllabusTopicIds.isEmpty())
    }

    @Test fun disciplineSubstringInventsACanonicalTopic() {
        assertEquals("FIN_PUB" to "FIN_PUB_01", QuestionCacheMapping.inferCanonical("Matemática Financeira"))
        assertEquals("FIN_PUB" to "FIN_PUB_01", QuestionCacheMapping.inferCanonical("Mercado Financeiro"))
    }

    @Test fun offlineErrorNotebookReturnsOrdinaryQuestionsWhenEmpty() = runBlocking {
        val q = QuestionDto(id = "q", question = "not an error", answers = listOf("A", "B"),
            correctAnswerIndices = listOf(1), disciplina = "Audit", source = "audit", level = "Médio")
        val rows = listOf(QuestionCacheMapping.toEntity(q, Json))
        val selected = repository(cached = rows).fetchOrGetOfflineQuiz(123L, QuizMode.ERROR_NOTEBOOK, 10)
        assertEquals(listOf("q"), selected.map { it.id })
        assertFalse(rows[0].inErrorNotebook)
    }
}
