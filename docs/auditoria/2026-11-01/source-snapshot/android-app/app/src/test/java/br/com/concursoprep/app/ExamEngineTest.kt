package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.QuestionCacheMapping
import br.com.concursoprep.app.domain.exam.CachedQuestionRef
import br.com.concursoprep.app.domain.exam.CanonicalMasteryRecord
import br.com.concursoprep.app.domain.exam.CanonicalTopicStatus
import br.com.concursoprep.app.domain.exam.ExamDraft
import br.com.concursoprep.app.domain.exam.ExamEngine
import br.com.concursoprep.app.domain.exam.ExamEngine.CanonicalActivitySlice
import br.com.concursoprep.app.domain.exam.ExamTopicLink
import br.com.concursoprep.app.domain.exam.LeverageExam
import br.com.concursoprep.app.domain.exam.LeverageSubject
import br.com.concursoprep.app.domain.exam.OfflineExamQueue
import br.com.concursoprep.app.domain.exam.QuestionRef
import br.com.concursoprep.app.domain.exam.QuizSelection
import br.com.concursoprep.app.domain.exam.ReadinessSubject
import br.com.concursoprep.app.domain.exam.ReadinessTopic
import br.com.concursoprep.app.domain.exam.SimuladoCandidate
import br.com.concursoprep.app.domain.exam.StoredExam
import br.com.concursoprep.app.domain.exam.StoredSubject
import br.com.concursoprep.app.domain.exam.StoredTopic
import br.com.concursoprep.app.domain.exam.SubjectQuota
import br.com.concursoprep.app.domain.exam.SubjectOutcome
import br.com.concursoprep.app.domain.exam.TopicRef
import br.com.concursoprep.app.domain.exam.TrunkFocus
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class ExamEngineTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 31)

    @Test
    fun secondExamShowsMasteryAlreadyRecordedUnderTheSameCanonicalIds() {
        val history = listOf(
            CanonicalMasteryRecord("FIN_PUB", "FIN_PUB_01", 0.8),
            CanonicalMasteryRecord("FIN_PUB", "FIN_PUB_02", 0.4),
            CanonicalMasteryRecord("DIR_TRIB", "OLD_ONLY", 0.55)
        )
        val links = listOf(
            ExamTopicLink("RFB", "FIN_PUB", "FIN_PUB_01", CanonicalTopicStatus.OFICIAL_CONFIRMADO),
            ExamTopicLink("RFB", "FIN_PUB", "FIN_PUB_02", CanonicalTopicStatus.OFICIAL_CONFIRMADO),
            ExamTopicLink("SEFAZ", "FIN_PUB", "FIN_PUB_01", CanonicalTopicStatus.PRE_EDITAL_BASE),
            ExamTopicLink("SEFAZ", "FIN_PUB", "FIN_PUB_02", CanonicalTopicStatus.PRE_EDITAL_BASE),
            ExamTopicLink("RFB", "DIR_TRIB", "OLD_ONLY", CanonicalTopicStatus.ARQUIVADO),
            ExamTopicLink("SEFAZ", "DIR_TRIB", "OLD_ONLY", CanonicalTopicStatus.OFICIAL_NOVO)
        )
        val projected = ExamEngine.projectCrossExamCoverage(history, links)
        val receita = ExamEngine.subjectMasteryPercentage(projected, "RFB", "FIN_PUB")
        val sefaz = ExamEngine.subjectMasteryPercentage(projected, "SEFAZ", "FIN_PUB")
        assertEquals(receita, sefaz, 1e-12)
        assertEquals(0.6, sefaz, 1e-12)
        val archived = projected.first { it.examId == "RFB" && it.canonicalTopicId == "OLD_ONLY" }
        assertTrue(archived.hiddenFromStudyQueue)
        assertEquals(0.55, archived.mastery, 1e-12)
        val inherited = projected.first { it.examId == "SEFAZ" && it.canonicalTopicId == "OLD_ONLY" }
        assertFalse(inherited.hiddenFromStudyQueue)
        assertEquals(0.55, inherited.mastery, 1e-12)
        assertTrue(history.any { it.canonicalTopicId == "OLD_ONLY" })
    }

    @Test
    fun exactDateRendersAsDayCountAndWindowRendersAsItsLabel() {
        assertEquals(
            "42 dias",
            ExamEngine.formatDeadline("EXACT_DATE", "2026-12-12", null, today)
        )
        assertEquals(
            "1º sem/2027",
            ExamEngine.formatDeadline("ESTIMATED_WINDOW", null, "1º sem/2027", today)
        )
    }

    @Test
    fun offlineCreateKeepsClientIdPendingAndFeedsCommonTrunkFromCache() {
        val draft = ExamDraft(
            title = "SEFAZ-SP · Auditor",
            shortName = "SEFAZ-SP",
            institution = "SEFAZ-SP",
            role = "Auditor",
            trunkSlug = "fiscal",
            lifecycleStatus = "RUMOR_PREVISTO",
            examBoards = listOf("FCC"),
            dateType = "ESTIMATED_WINDOW",
            exactDateIso = null,
            estimatedWindowLabel = "1º sem/2027",
            estimatedSortDateIso = "2027-06-30",
            historicalCutoffScore = 75.0,
            ladderPriority = 2,
            subjects = listOf(
                StoredSubject(
                    localSubjectId = "fin",
                    canonicalSubjectId = "FIN_PUB",
                    displayName = "Finanças Públicas",
                    category = "CONHECIMENTOS_ESPECIFICOS",
                    weight = 2.0,
                    questionCount = 10,
                    relevanceTier = "CORE",
                    topics = listOf(
                        StoredTopic("t1", "FIN_PUB_01", "FIN_PUB", "Orçamento", "PRE_EDITAL_BASE")
                    )
                )
            )
        )
        val created = OfflineExamQueue.createPendingExam(draft)
        UUID.fromString(created.examId)
        assertEquals("PENDING_CREATE", created.syncStatus)
        val other = OfflineExamQueue.createPendingExam(draft)
        assertNotEquals(created.examId, other.examId)

        val receita = created.copy(
            examId = "RFB",
            shortName = "Receita Federal",
            syncStatus = "SYNCED",
            subjects = created.subjects
        )
        val cache = listOf(
            CachedQuestionRef("q-fin", "FIN_PUB", "FIN_PUB_01"),
            CachedQuestionRef("q-other", "DIR_PEN", "DIR_PEN_01")
        )
        val battery = OfflineExamQueue.commonTrunkFromCache(
            exams = listOf(receita, created),
            cachedQuestions = cache,
            trunkFocusSlug = null,
            masteryBySubject = emptyMap(),
            today = today,
            suggestedQuestions = 12
        )
        assertTrue(battery != null)
        assertEquals("FIN_PUB", battery!!.card.canonicalSubjectId)
        assertEquals(listOf("q-fin"), battery.questionIds)
        assertTrue(battery.card.sentence.contains("Finanças Públicas"))
        assertTrue(battery.card.sentence.contains("ao mesmo tempo"))

        val once = OfflineExamQueue.mergeByClientId(emptyMap(), created)
        val twice = OfflineExamQueue.mergeByClientId(once, created.copy(shortName = "SEFAZ-SP revisado"))
        assertEquals(1, twice.size)
        assertEquals("SEFAZ-SP revisado", twice.getValue(created.examId).shortName)
        assertEquals(created.examId, twice.getValue(created.examId).examId)
    }

    @Test
    fun commonTrunkSentenceAndReadinessAndArchivedDrawMatchTheFormula() {
        val exams = listOf(
            LeverageExam(
                "CPNU", "CPNU - AFT", "controle", "RUMOR_PREVISTO", "2026-11-30", true,
                listOf(
                    LeverageSubject("FIN_PUB", "Finanças Públicas", 3.0, false),
                    LeverageSubject("PORT", "Língua Portuguesa", 1.0, false)
                )
            ),
            LeverageExam(
                "RFB", "Receita Federal", "fiscal", "AUTORIZADO", null, true,
                listOf(
                    LeverageSubject("FIN_PUB", "Finanças Públicas", 1.0, false),
                    LeverageSubject("DIR_TRIB", "Direito Tributário", 1.0, false)
                )
            )
        )
        val card = ExamEngine.selectCommonTrunkCard(
            exams,
            mapOf("FIN_PUB" to 0.25),
            null,
            15,
            today
        )
        assertEquals("FIN_PUB", card!!.canonicalSubjectId)
        assertEquals(
            "Estudar 15 questões de Finanças Públicas avança CPNU - AFT e Receita Federal ao mesmo tempo",
            card.sentence
        )
        val focused = ExamEngine.selectCommonTrunkCard(
            exams,
            emptyMap(),
            TrunkFocus("fiscal", setOf("DIR_TRIB")),
            10,
            today
        )
        assertEquals("DIR_TRIB", focused!!.canonicalSubjectId)

        val readiness = ExamEngine.readinessIndex(
            listOf(
                ReadinessSubject(2.0, 0.8, listOf(ReadinessTopic(false, true), ReadinessTopic(false, true))),
                ReadinessSubject(
                    1.0,
                    0.5,
                    listOf(ReadinessTopic(false, true), ReadinessTopic(false, false), ReadinessTopic(true, true))
                ),
                ReadinessSubject(4.0, 1.0, listOf(ReadinessTopic(true, true)))
            )
        )
        assertEquals(74.0, readiness, 1e-9)

        val drawn = ExamEngine.drawWeightedSimulado(
            listOf(
                SubjectQuota("FIN_PUB", 2.0, 2),
                SubjectQuota("DIR_TRIB", 1.0, 1)
            ),
            listOf(
                SimuladoCandidate("q-archived", "FIN_PUB", "OLD", true),
                SimuladoCandidate("q2", "FIN_PUB", "FIN_PUB_02", false),
                SimuladoCandidate("q1", "FIN_PUB", "FIN_PUB_01", false),
                SimuladoCandidate("q4", "DIR_TRIB", "DIR_TRIB_01", false)
            )
        )
        assertEquals(listOf("q1", "q2", "q4"), drawn)
        assertFalse(drawn.contains("q-archived"))
        val score = ExamEngine.weightedNetScore(
            listOf(SubjectOutcome(2.0, 1, 2), SubjectOutcome(1.0, 1, 1))
        )
        assertEquals(200.0 / 3.0, score, 1e-9)

        val diff = ExamEngine.publishEditalDiff(
            previous = listOf(
                TopicRef("FIN_PUB_01", "FIN_PUB", "Orçamento"),
                TopicRef("OLD_ONLY", "DIR_TRIB", "Sai")
            ),
            published = listOf(
                TopicRef("FIN_PUB_01", "FIN_PUB", "Orçamento"),
                TopicRef("NEW_TOPIC", "FIN_PUB", "Novo")
            ),
            practicedCanonicalTopicIds = setOf("NEW_TOPIC")
        )
        assertEquals(1, diff.confirmedCount)
        assertEquals(1, diff.newCount)
        assertEquals(1, diff.archivedCount)
        assertTrue(diff.topics.first { it.canonicalTopicId == "NEW_TOPIC" }.coverageAlreadyFilled)
    }

    @Test
    fun fullyMasteredSubjectLosesTheCommonTrunkCard() {
        val exams = listOf(
            LeverageExam(
                "A", "CPNU - AFT", "controle", "AUTORIZADO", null, true,
                listOf(
                    LeverageSubject("FIN_PUB", "Finanças Públicas", 5.0, false),
                    LeverageSubject("PORT", "Língua Portuguesa", 1.0, false)
                )
            ),
            LeverageExam(
                "B", "Receita Federal", "fiscal", "AUTORIZADO", null, true,
                listOf(
                    LeverageSubject("FIN_PUB", "Finanças Públicas", 5.0, false),
                    LeverageSubject("PORT", "Língua Portuguesa", 1.0, false)
                )
            )
        )
        val rows = listOf(
            CanonicalActivitySlice("FIN_PUB", "FIN_PUB_01", 100.0, mastered = true, highConfidenceError = false)
        )
        val card = ExamEngine.cardFromCanonicalActivity(exams, rows, emptyList(), 15, today)
        assertEquals("PORT", card!!.canonicalSubjectId)
        assertEquals(1.0, ExamEngine.masteryByCanonicalSubject(rows).getValue("FIN_PUB"), 1e-12)
    }

    @Test
    fun readinessForStoredExamIgnoresArchivedTopics() {
        val exam = StoredExam(
            examId = "SEFAZ",
            title = "SEFAZ-SP",
            shortName = "SEFAZ-SP",
            institution = "SEFAZ-SP",
            role = "Auditor",
            trunkSlug = "fiscal",
            lifecycleStatus = "EDITAL_PUBLICADO",
            examBoards = listOf("FCC"),
            dateType = "ESTIMATED_WINDOW",
            exactDateIso = null,
            estimatedWindowLabel = "1º sem/2027",
            estimatedSortDateIso = "2027-06-30",
            historicalCutoffScore = 75.0,
            isActiveInLadder = true,
            ladderPriority = 1,
            subjects = listOf(
                StoredSubject(
                    "fin", "FIN_PUB", "Finanças Públicas", "CONHECIMENTOS_ESPECIFICOS", 2.0, 10, "CORE",
                    listOf(
                        StoredTopic("a", "FIN_PUB_01", "FIN_PUB", "Orçamento", "OFICIAL_CONFIRMADO"),
                        StoredTopic("b", "OLD", "FIN_PUB", "Saiu", "ARQUIVADO")
                    )
                )
            )
        )
        val score = ExamEngine.readinessForStoredExams(listOf(exam), mapOf("FIN_PUB_01" to 0.8, "OLD" to 0.0))
        assertEquals(86.0, score, 1e-9)
        val groups = ExamEngine.projectSyllabus(
            exam,
            listOf(br.com.concursoprep.app.domain.exam.CanonicalMasteryRecord("FIN_PUB", "FIN_PUB_01", 0.8))
        )
        assertEquals(listOf("Finanças Públicas"), groups.map { it.discipline })
        assertFalse(groups.flatMap { it.topicTitles }.contains("Saiu"))
        assertFalse(groups.any { it.discipline.contains("Segurança") })
    }

    @Test
    fun simuladoDrawAndCacheRoundTripKeepCanonicalIds() {
        val exam = StoredExam(
            examId = "SEFAZ",
            title = "SEFAZ-SP",
            shortName = "SEFAZ-SP",
            institution = "SEFAZ-SP",
            role = "Auditor",
            trunkSlug = "fiscal",
            lifecycleStatus = "EDITAL_PUBLICADO",
            examBoards = emptyList(),
            dateType = "UNDEFINED",
            exactDateIso = null,
            estimatedWindowLabel = null,
            estimatedSortDateIso = "2027-06-30",
            historicalCutoffScore = 70.0,
            isActiveInLadder = true,
            ladderPriority = 1,
            subjects = listOf(
                StoredSubject(
                    "fin", "FIN_PUB", "Finanças Públicas", "CONHECIMENTOS_ESPECIFICOS", 2.0, 1, "CORE",
                    listOf(
                        StoredTopic("a", "FIN_PUB_01", "FIN_PUB", "Orçamento", "OFICIAL_CONFIRMADO"),
                        StoredTopic("b", "OLD", "FIN_PUB", "Saiu", "ARQUIVADO")
                    )
                )
            )
        )
        val questions = listOf(
            QuestionRef("archived-q", "FIN_PUB", "OLD"),
            QuestionRef("live-q", "FIN_PUB", "FIN_PUB_01")
        )
        val ids = QuizSelection.weightedSimuladoIds(exam, questions)
        assertEquals(listOf("live-q"), ids)
        val json = Json { ignoreUnknownKeys = true }
        val dto = QuestionDto(
            id = "q-canon",
            question = "Enunciado",
            questionType = QuestionType.MULTIPLE_CHOICE,
            answers = listOf("A"),
            correctAnswerIndices = listOf(0),
            disciplina = "Finanças Públicas",
            source = "FCC",
            level = "Médio",
            canonicalSubjectId = "FIN_PUB",
            canonicalTopicId = "FIN_PUB_01"
        )
        val restored = QuestionCacheMapping.toDto(QuestionCacheMapping.toEntity(dto, json), json)
        assertEquals("FIN_PUB", restored.canonicalSubjectId)
        assertEquals("FIN_PUB_01", restored.canonicalTopicId)
        val inferred = QuestionCacheMapping.toEntity(
            dto.copy(canonicalSubjectId = null, canonicalTopicId = null, disciplina = "Finanças Públicas"),
            json
        )
        assertEquals("FIN_PUB", inferred.canonicalSubjectId)
        assertEquals("FIN_PUB_01", inferred.canonicalTopicId)
    }

    @Test
    fun practiceFocusUsesEverySelectedTrunk() {
        val exams = listOf(
            LeverageExam("RFB", "Receita Federal", "fiscal", "AUTORIZADO", null, true, listOf(LeverageSubject("DIR_TRIB", "Direito Tributário", 1.0, false))),
            LeverageExam("PF", "Polícia Federal", "policial", "RUMOR_PREVISTO", null, true, listOf(LeverageSubject("DIR_PEN", "Direito Penal", 1.0, false)))
        )
        val focuses = listOf(
            TrunkFocus("fiscal", setOf("DIR_TRIB")),
            TrunkFocus("policial", setOf("DIR_PEN"))
        )
        val card = ExamEngine.selectCommonTrunkCard(exams, emptyMap(), focuses[0], 8, today, listOf(focuses[1]))
        assertTrue(card!!.canonicalSubjectId == "DIR_TRIB" || card.canonicalSubjectId == "DIR_PEN")
        val stored = focuses.map { focus ->
            StoredExam(
                examId = focus.slug,
                title = focus.slug,
                shortName = focus.slug,
                institution = focus.slug,
                role = "Cargo",
                trunkSlug = focus.slug,
                lifecycleStatus = "RUMOR_PREVISTO",
                examBoards = emptyList(),
                dateType = "UNDEFINED",
                exactDateIso = null,
                estimatedWindowLabel = null,
                estimatedSortDateIso = "2027-01-01",
                historicalCutoffScore = 70.0,
                isActiveInLadder = true,
                ladderPriority = 2,
                subjects = listOf(
                    StoredSubject(
                        "s",
                        focus.coreSubjectIds.first(),
                        focus.coreSubjectIds.first(),
                        "CONHECIMENTOS_ESPECIFICOS",
                        1.0,
                        5,
                        "CORE",
                        emptyList()
                    )
                )
            )
        }
        val ids = QuizSelection.commonTrunkIds(
            stored,
            listOf(QuestionRef("q-pen", "DIR_PEN", "DIR_PEN_01"), QuestionRef("q-trib", "DIR_TRIB", "DIR_TRIB_01")),
            setOf("fiscal", "policial"),
            emptyMap(),
            today,
            8
        )
        assertTrue(ids.contains("q-pen") || ids.contains("q-trib"))
    }
}
