package br.com.concursoprep.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.data.remote.*
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.model.*
import br.com.concursoprep.app.ui.MainViewModel
import br.com.concursoprep.app.ui.screens.QuestionSessionScreen
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual fetch -> cache -> durable session -> question UI -> queued answer. */
@RunWith(AndroidJUnit4::class)
class QuestionPracticeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun shuffleOpensALegacyQuestionAndSavesAnAnswerDespiteArchivedPlannerContext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "practice_fixture_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val store = ViewModelStore()
        var repo: ConcursoRepository? = null
        try {
            val archived = ExamCatalog.systemExams().first { it.examId == "CPNU" }
                .copy(lifecycleStatus = "ARQUIVADO", isActiveInLadder = false)
            db.examDao().upsertExam(ExamLocalMapping.toEntity(archived))
            db.examDao().upsertSubjects(ExamLocalMapping.subjectsOf(archived))
            db.examDao().upsertTopics(ExamLocalMapping.topicsOf(archived))
            val q = QuestionCacheMapping.withRevision(QuestionDto(
                id = "000000000000000000000001", question = "Questão sintética recuperada para praticar",
                answers = listOf("Alternativa A", "Alternativa B"), correctAnswerIndices = listOf(1),
                disciplina = "Direito do Trabalho", source = "Fixture", level = "Médio"
            ))
            var sent: QuizGeneratePayload? = null
            val api = Proxy.newProxyInstance(ConcursoApiService::class.java.classLoader, arrayOf(ConcursoApiService::class.java)) { _, method, args ->
                when (method.name) {
                    "generateQuizQuestions" -> { sent = args!![0] as QuizGeneratePayload; listOf(q) }
                    else -> throw IOException("Synthetic offline API: ${method.name}")
                }
            } as ConcursoApiService
            val repository = ConcursoRepository(api, db.concursoDao(), db.examDao(), durable = db.durableDao(), database = db, ownerUserId = 123)
            repo = repository
            lateinit var model: MainViewModel
            rule.runOnUiThread {
                model = MainViewModel(repository)
                store.put("practice", model)
                model.startQuizSession(QuizMode.SHUFFLE, 10, "CPNU")
            }
            rule.waitUntil(30000) { model.activeSession.value != null }
            val session = model.activeSession.value!!
            assertNull(sent!!.contestId)
            assertNull(session.exam)
            assertEquals(listOf(q.id), session.questions.map { it.id })
            assertNull(session.questions.single().canonicalTopicId)
            assertEquals(q.id, repository.pausedSession()!!.questions.single().id)
            rule.setContent {
                ConcursoPrepTheme {
                    QuestionSessionScreen(
                        questions = session.questions, isSimuladoMode = false, snapshot = session,
                        aiTutorMarkdown = null, aiTutorTitle = null,
                        onRequestAiTutor = { _, _, _, _ -> }, onDismissAiSheet = {},
                        onSubmitAnswer = { question, choice, confidence, seconds -> model.submitAnswer(question, choice, confidence, seconds) },
                        onReportProblem = { _, _ -> }, onExitSession = {}
                    )
                }
            }
            rule.onNodeWithText(q.question).assertIsDisplayed()
            assertTrue(model.submitAnswer(session.questions.single(), 1, 3, 10))
            val pending = db.concursoDao().getAllPendingAttempts().single()
            assertEquals(123L, pending.ownerUserId)
            assertEquals(q.id, pending.questionId)
            assertTrue(pending.questionRevision.isNotBlank())
            assertTrue(repository.pausedSession()!!.answers.getValue(q.id).submitted)
        } finally {
            rule.runOnUiThread { store.clear() }
            repo?.awaitIdleForClose()
            db.close()
            check(name.startsWith("practice_fixture_")); context.deleteDatabase(name)
        }
    }
}
