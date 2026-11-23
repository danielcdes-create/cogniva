package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.CachedQuestionEntity
import br.com.concursoprep.app.data.local.ConcursoLocalDao
import br.com.concursoprep.app.data.local.DurableStateDao
import br.com.concursoprep.app.data.local.ExamDao
import br.com.concursoprep.app.data.local.ExamLocalMapping
import br.com.concursoprep.app.data.local.ExamSubjectEntity
import br.com.concursoprep.app.data.local.ExamTopicEntity
import br.com.concursoprep.app.data.local.LearningRecordEntity
import br.com.concursoprep.app.data.local.UserExamEntity
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.remote.PracticeDecision
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.data.repository.toPayload
import br.com.concursoprep.app.data.repository.toStored
import br.com.concursoprep.app.domain.exam.CachedQuestionRef
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.domain.exam.ExamDraft
import br.com.concursoprep.app.domain.exam.ExamEngine
import br.com.concursoprep.app.domain.exam.LeverageExam
import br.com.concursoprep.app.domain.exam.LeverageSubject
import br.com.concursoprep.app.domain.exam.OfflineExamQueue
import br.com.concursoprep.app.domain.exam.QuestionRef
import br.com.concursoprep.app.domain.exam.QuizSelection
import br.com.concursoprep.app.domain.exam.ReadinessSubject
import br.com.concursoprep.app.domain.exam.ReadinessTopic
import br.com.concursoprep.app.domain.exam.StoredExam
import br.com.concursoprep.app.domain.exam.StoredSubject
import br.com.concursoprep.app.domain.exam.StoredTopic
import br.com.concursoprep.app.domain.exam.SubjectOutcome
import br.com.concursoprep.app.domain.exam.TrunkFocus
import br.com.concursoprep.app.domain.model.KnowledgeEvidence
import br.com.concursoprep.app.domain.model.LearningProjection
import br.com.concursoprep.app.domain.model.LearningRecord
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.TopicDiffStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.util.UUID

class ExamEngineTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 31)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun catalogAssociationPreservesLocalIdentityAndCustomContent() {
        val catalog = ExamCatalog
        val sharedKnowledge = Json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/knowledge-catalog.json"))
                .bufferedReader()
                .use { it.readText() }
        ).jsonObject
        val expectedActiveSubjects = sharedKnowledge.getValue("subjects").jsonArray.count {
            it.jsonObject["status"]?.jsonPrimitive?.content != "DEPRECATED"
        }
        val expectedTrunks = sharedKnowledge.getValue("trunks").jsonArray.size

        val subjects = catalog.allSubjects()
        val trunks = catalog.trunks()
        assertEquals(expectedActiveSubjects, subjects.size)
        assertEquals(subjects.size, subjects.map { it.canonicalSubjectId }.distinct().size)
        assertEquals(expectedTrunks, trunks.size)
        assertTrue(trunks.any { it.slug == "fiscal" })

        val target = subjects.first()
        val first = catalog.newComponent(target)
        val second = catalog.newComponent(target).copy(stage = "2ª etapa")
        assertNotEquals(first.localSubjectId, second.localSubjectId)
        assertTrue(first.topics.isNotEmpty())
        assertEquals(5, first.questionCount)
        val remaining = br.com.concursoprep.app.ui.screens.availableCatalogSubjects(subjects, listOf(first), "", emptyList())
        assertFalse(remaining.any { it.canonicalSubjectId == first.canonicalSubjectId })

        val custom = StoredSubject(
            "component",
            "custom-${UUID.randomUUID()}",
            "Meu conteúdo",
            "OUTROS",
            2.0,
            3,
            "SPECIFIC",
            listOf(StoredTopic("local-topic", "custom-${UUID.randomUUID()}", "pending", "Texto preservado", "PRE_EDITAL_BASE")),
            "1ª etapa"
        )
        val linked = catalog.associateSubject(custom, target)
        assertEquals(custom.localSubjectId, linked.localSubjectId)
        assertEquals(custom.topics.single().localTopicId, linked.topics.single().localTopicId)
        assertEquals("Texto preservado", linked.topics.single().displayTitle)
        assertTrue(linked.topics.single().canonicalTopicId.startsWith("custom-"))
        assertEquals(target.canonicalSubjectId, linked.topics.single().canonicalSubjectId)
        assertEquals("1ª etapa", linked.stage)
        assertTrue(catalog.catalogTopics(target.canonicalSubjectId).isNotEmpty())
    }

    @Test
    fun weightedSimuladoIdsDrawsDistinctQuestionsAcrossRepeatedDisciplineQuotasAndRespectsDecisions() {
        val topic = StoredTopic("t1", "PORT_01", "PORT", "Interpretação", "OFICIAL_CONFIRMADO")
        val archivedTopic = StoredTopic("t-arch", "PORT_ARCH", "PORT", "Arquivado", "ARQUIVADO")
        val exam = StoredExam(
            examId = "CPNU",
            title = "CPNU",
            shortName = "CPNU",
            institution = "MGI",
            role = "Analista",
            trunkSlug = "controle",
            lifecycleStatus = "EDITAL_PUBLICADO",
            examBoards = listOf("Cebraspe"),
            dateType = "UNDEFINED",
            exactDateIso = null,
            estimatedWindowLabel = null,
            estimatedSortDateIso = "",
            historicalCutoffScore = 70.0,
            isActiveInLadder = true,
            ladderPriority = 1,
            revision = 2L,
            subjects = listOf(
                StoredSubject("comp-1", "PORT", "Português I", "CONHECIMENTOS_GERAIS", 1.0, 2, "CORE", listOf(topic, archivedTopic), "1ª etapa"),
                StoredSubject("comp-2", "PORT", "Português II", "CONHECIMENTOS_ESPECIFICOS", 3.0, 2, "CORE", listOf(topic), "2ª etapa"),
                StoredSubject("comp-zero", "PORT", "Zero", "OUTROS", 0.0, 5, "SPECIFIC", listOf(topic))
            )
        )
        val pool = (1..4).map { QuestionRef("q$it", "PORT", "PORT_01", "rev-1") } +
            QuestionRef("q-archived", "PORT", "PORT_ARCH", "rev-1") +
            QuestionRef("q-excluded", "PORT", "PORT_01", "rev-1")
        val decisions = listOf(
            PracticeDecision("q-excluded", "rev-1", "CPNU", 2L, "comp-1", "EXCLUDED", 1L, 1L),
            PracticeDecision("q-excluded", "rev-1", "CPNU", 2L, "comp-2", "EXCLUDED", 1L, 1L)
        )

        val ids = QuizSelection.weightedSimuladoIds(exam, pool, decisions)
        assertEquals(4, ids.size)
        assertEquals(setOf("q1", "q2", "q3", "q4"), ids.toSet())
        assertFalse(ids.contains("q-archived"))
        assertFalse(ids.contains("q-excluded"))
    }

    @Test
    fun secondExamSharesCanonicalMasteryInLearningProjectionWhileIgnoringArchivedTopics() {
        fun verifiedRow(qId: String, subjectId: String, topicId: String, acc: Double, mastered: Boolean): LearningRecord =
            LearningRecord(
                questionId = qId,
                disciplina = subjectId,
                canonicalSubjectId = subjectId,
                canonicalTopicId = topicId,
                attempts = 4,
                correct = (4 * acc / 100.0).toInt(),
                accuracyPct = acc,
                isMastered = mastered,
                inErrorNotebook = false,
                highConfidenceError = false,
                points = if (mastered) 1.0 else 0.5,
                nextReviewAt = "2027-01-01T00:00:00Z",
                confidenceAvg = 3,
                knowledgeProjectionVersion = 1,
                knowledgeEvidence = KnowledgeEvidence(
                    canonicalSubjectId = subjectId,
                    canonicalTopicId = topicId,
                    questionRevision = "rev-1",
                    attempts = 4,
                    correct = (4 * acc / 100.0).toInt(),
                    accuracyPct = acc,
                    confidenceAvg = 3,
                    mastered = mastered
                )
            )

        val rows = listOf(
            verifiedRow("q1", "FIN_PUB", "FIN_PUB_01", 75.0, mastered = false),
            verifiedRow("q2", "FIN_PUB", "FIN_PUB_02", 25.0, mastered = false),
            verifiedRow("q3", "DIR_TRIB", "DIR_TRIB_OLD", 100.0, mastered = true)
        )

        val rfb = StoredExam(
            examId = "RFB",
            title = "RFB",
            shortName = "RFB",
            institution = "RFB",
            role = "Auditor",
            trunkSlug = "fiscal",
            lifecycleStatus = "EDITAL_PUBLICADO",
            examBoards = listOf("FGV"),
            dateType = "UNDEFINED",
            exactDateIso = null,
            estimatedWindowLabel = null,
            estimatedSortDateIso = "",
            historicalCutoffScore = 75.0,
            isActiveInLadder = true,
            ladderPriority = 1,
            subjects = listOf(
                StoredSubject(
                    "s-fin", "FIN_PUB", "Finanças Públicas", "CONHECIMENTOS_ESPECIFICOS", 2.0, 10, "CORE",
                    listOf(
                        StoredTopic("t1", "FIN_PUB_01", "FIN_PUB", "Orçamento", "OFICIAL_CONFIRMADO"),
                        StoredTopic("t2", "FIN_PUB_02", "FIN_PUB", "Receita", "OFICIAL_CONFIRMADO")
                    )
                ),
                StoredSubject(
                    "s-trib", "DIR_TRIB", "Direito Tributário", "CONHECIMENTOS_ESPECIFICOS", 2.0, 10, "CORE",
                    listOf(
                        StoredTopic("t3", "DIR_TRIB_OLD", "DIR_TRIB", "Tópico Arquivado", "ARQUIVADO"),
                        StoredTopic("t4", "DIR_TRIB_NEW", "DIR_TRIB", "Tópico Novo", "OFICIAL_NOVO")
                    )
                )
            )
        )
        val sefaz = rfb.copy(
            examId = "SEFAZ",
            shortName = "SEFAZ",
            subjects = listOf(
                rfb.subjects[0].copy(
                    topics = rfb.subjects[0].topics.map { it.copy(diffStatus = "PRE_EDITAL_BASE") }
                ),
                rfb.subjects[1].copy(
                    topics = listOf(
                        StoredTopic("t3", "DIR_TRIB_OLD", "DIR_TRIB", "Tópico Ativo na SEFAZ", "OFICIAL_NOVO")
                    )
                )
            )
        )

        val rfbMastery = LearningProjection.masteryBySubject(rows, rfb)
        val sefazMastery = LearningProjection.masteryBySubject(rows, sefaz)
        assertEquals(0.50, rfbMastery.getValue("FIN_PUB"), 1e-9)
        assertEquals(rfbMastery.getValue("FIN_PUB"), sefazMastery.getValue("FIN_PUB"), 1e-12)
        // RFB archived DIR_TRIB_OLD and has unpracticed DIR_TRIB_NEW -> 0.0; SEFAZ kept DIR_TRIB_OLD active -> 1.0
        assertEquals(0.0, rfbMastery.getValue("DIR_TRIB"), 1e-9)
        assertEquals(1.0, sefazMastery.getValue("DIR_TRIB"), 1e-9)
    }

    @Test
    fun getContestSyllabusAndContestDiffReflectCanonicalMasteryStatusesAndExcludeArchivedTopics() = runBlocking {
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
            confirmedTopicsCount = 1,
            newTopicsCount = 2,
            archivedTopicsCount = 1,
            subjects = listOf(
                StoredSubject(
                    "fin", "FIN_PUB", "Finanças Públicas", "CONHECIMENTOS_ESPECIFICOS", 2.0, 10, "CORE",
                    listOf(
                        StoredTopic("t-dom", "FIN_PUB_01", "FIN_PUB", "Orçamento", "OFICIAL_CONFIRMADO"),
                        StoredTopic("t-rev", "FIN_PUB_02", "FIN_PUB", "Crédito Público", "OFICIAL_NOVO"),
                        StoredTopic("t-new", "FIN_PUB_03", "FIN_PUB", "LRF", "OFICIAL_NOVO"),
                        StoredTopic("t-arch", "FIN_PUB_ARCH", "FIN_PUB", "Saiu do Edital", "ARQUIVADO")
                    )
                ),
                StoredSubject(
                    "arch-only", "DIR_PEN", "Direito Penal", "OUTROS", 1.0, 5, "SPECIFIC",
                    listOf(StoredTopic("t-pen-arch", "DIR_PEN_01", "DIR_PEN", "Arquivado", "ARQUIVADO"))
                )
            )
        )
        val learningRows = listOf(
            LearningRecord(
                questionId = "q-dom",
                disciplina = "Finanças Públicas",
                canonicalSubjectId = "FIN_PUB",
                canonicalTopicId = "FIN_PUB_01",
                attempts = 4,
                correct = 4,
                accuracyPct = 100.0,
                isMastered = true,
                inErrorNotebook = false,
                highConfidenceError = false,
                points = 1.0,
                nextReviewAt = "2099-01-01T00:00:00Z",
                confidenceAvg = 3,
                practiceAvailable = true,
                knowledgeProjectionVersion = 1,
                knowledgeEvidence = KnowledgeEvidence("FIN_PUB", "FIN_PUB_01", "r1", 4, 4, 100.0, 3, mastered = true)
            ),
            LearningRecord(
                questionId = "q-rev",
                disciplina = "Finanças Públicas",
                canonicalSubjectId = "FIN_PUB",
                canonicalTopicId = "FIN_PUB_02",
                attempts = 2,
                correct = 1,
                accuracyPct = 50.0,
                isMastered = false,
                inErrorNotebook = true,
                highConfidenceError = false,
                points = 0.2,
                nextReviewAt = "2020-01-01T00:00:00Z",
                confidenceAvg = 3,
                reviewRequired = true,
                practiceAvailable = true,
                knowledgeProjectionVersion = 1,
                knowledgeEvidence = KnowledgeEvidence("FIN_PUB", "FIN_PUB_02", "r1", 2, 1, 50.0, 3, mastered = false)
            )
        )

        val api = Proxy.newProxyInstance(ConcursoApiService::class.java.classLoader, arrayOf(ConcursoApiService::class.java)) { _, m, _ ->
            throw IOException("Offline test: ${m.name}")
        } as ConcursoApiService
        val dao = Proxy.newProxyInstance(ConcursoLocalDao::class.java.classLoader, arrayOf(ConcursoLocalDao::class.java)) { _, m, _ ->
            when (m.name) {
                "bindLegacyOwner" -> Unit
                "getAllPendingAttempts" -> emptyList<Any>()
                "findQuestionsByIds" -> emptyList<CachedQuestionEntity>()
                else -> error("Unexpected dao.${m.name}")
            }
        } as ConcursoLocalDao
        val examEntity = ExamLocalMapping.toEntity(exam)
        val subjectEntities = ExamLocalMapping.subjectsOf(exam)
        val topicEntities = ExamLocalMapping.topicsOf(exam)
        val examDao = Proxy.newProxyInstance(ExamDao::class.java.classLoader, arrayOf(ExamDao::class.java)) { _, m, args ->
            when (m.name) {
                "listTrunks" -> emptyList<Any>()
                "upsertTrunks" -> Unit
                "countExams" -> 1
                "listExams" -> listOf(examEntity)
                "subjectsFor" -> if (args!![0] == "SEFAZ") subjectEntities else emptyList<ExamSubjectEntity>()
                "topicsFor" -> if (args!![0] == "SEFAZ") topicEntities else emptyList<ExamTopicEntity>()
                else -> error("Unexpected examDao.${m.name}")
            }
        } as ExamDao
        val durable = Proxy.newProxyInstance(DurableStateDao::class.java.classLoader, arrayOf(DurableStateDao::class.java)) { _, m, _ ->
            when (m.name) {
                "metadata" -> null
                "pruneSessionsOlderThan" -> Unit
                "learning" -> learningRows.map { LearningRecordEntity(it.questionId, 1L, json.encodeToString(it)) }
                else -> error("Unexpected durable.${m.name}")
            }
        } as DurableStateDao

        val repo = ConcursoRepository(api, dao, examDao, json, durable = durable, ownerUserId = 123L)
        val groups = repo.getContestSyllabus("SEFAZ", 123L)
        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals("Finanças Públicas", group.discipline)
        assertEquals("fin", group.componentId)
        assertEquals(listOf("Orçamento", "Crédito Público", "LRF"), group.topics.map { it.topic.title })
        assertEquals(listOf("DOMINADO", "REVISAO", "NAO_INICIADO"), group.topics.map { it.statusLabel })
        assertEquals(
            listOf(TopicDiffStatus.CONFIRMED, TopicDiffStatus.NEW_IN_EDITAL, TopicDiffStatus.NEW_IN_EDITAL),
            group.topics.map { it.topic.diffStatus }
        )
        assertEquals((100.0 + 50.0 + 0.0) / 3.0, group.disciplineMasteryPct, 1e-9)

        val diff = repo.getContestDiff("SEFAZ")
        assertNotNull(diff)
        assertEquals(listOf("Orçamento"), diff!!.confirmedTopics.map { it.title })
        assertEquals(listOf("Crédito Público", "LRF"), diff.newTopics.map { it.title })
        assertEquals(listOf("Saiu do Edital", "Arquivado"), diff.removedTopics.map { it.title })
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
        assertNotNull(battery)
        assertEquals("FIN_PUB", battery!!.card.canonicalSubjectId)
        assertEquals(listOf("q-fin"), battery.questionIds)
        assertTrue(battery.card.sentence.contains("Finanças Públicas"))
        assertTrue(battery.card.sentence.contains("ao mesmo tempo"))
    }

    @Test
    fun commonTrunkSentenceReadinessAndWeightedNetScoreMatchTheFormula() {
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

        val score = ExamEngine.weightedNetScore(
            listOf(SubjectOutcome(2.0, 1, 2), SubjectOutcome(1.0, 1, 1))
        )
        assertEquals(60.0, score, 1e-9)
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
        val masteredRecord = LearningRecord(
            questionId = "q-fin-mastered",
            disciplina = "Finanças Públicas",
            canonicalSubjectId = "FIN_PUB",
            canonicalTopicId = "FIN_PUB_01",
            attempts = 5,
            correct = 5,
            accuracyPct = 100.0,
            isMastered = true,
            inErrorNotebook = false,
            highConfidenceError = false,
            points = 1.0,
            nextReviewAt = "2027-01-01T00:00:00Z",
            knowledgeProjectionVersion = 1,
            knowledgeEvidence = KnowledgeEvidence("FIN_PUB", "FIN_PUB_01", "rev-1", 5, 5, 100.0, 3, mastered = true)
        )
        val masteryBySubject = LearningProjection.masteryBySubject(listOf(masteredRecord))
        assertEquals(1.0, masteryBySubject.getValue("FIN_PUB"), 1e-12)
        val card = ExamEngine.selectCommonTrunkCard(exams, masteryBySubject, null, 15, today)
        assertEquals("PORT", card!!.canonicalSubjectId)
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
    }

    @Test
    fun practiceFocusUsesEverySelectedTrunkDeterministically() {
        val exams = listOf(
            LeverageExam("RFB", "Receita Federal", "fiscal", "AUTORIZADO", null, true, listOf(LeverageSubject("DIR_TRIB", "Direito Tributário", 1.0, false))),
            LeverageExam("PF", "Polícia Federal", "policial", "RUMOR_PREVISTO", null, true, listOf(LeverageSubject("DIR_PEN", "Direito Penal", 1.0, false)))
        )
        val focuses = listOf(
            TrunkFocus("fiscal", setOf("DIR_TRIB")),
            TrunkFocus("policial", setOf("DIR_PEN"))
        )
        val card = ExamEngine.selectCommonTrunkCard(exams, emptyMap(), focuses[0], 8, today, listOf(focuses[1]))
        assertEquals("DIR_PEN", card!!.canonicalSubjectId)
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
                        listOf(StoredTopic("topic", focus.coreSubjectIds.first() + "_01", focus.coreSubjectIds.first(), "Reviewed topic", "PRE_EDITAL_BASE"))
                    )
                )
            )
        }
        val ids = QuizSelection.commonTrunkIds(
            stored,
            listOf(
                QuestionRef("q-pen", "DIR_PEN", "DIR_PEN_01"),
                QuestionRef("q-trib", "DIR_TRIB", "DIR_TRIB_01"),
                QuestionRef("out-of-grade", "DIR_PEN", "UNRELATED")
            ),
            setOf("fiscal", "policial"),
            emptyMap(),
            today,
            8
        )
        assertEquals(listOf("q-pen"), ids)
    }

    @Test
    fun stageGroupAndCustomComponentsSurviveLocalAndWireMappings() {
        val id = "custom-${UUID.randomUUID()}"
        val subject = StoredSubject(id, id, "Engenharia de Túneis", "CONHECIMENTOS_GERAIS", 2.75, 7, "SPECIFIC", emptyList(), "2ª etapa")
        val exam = ExamCatalog.systemExams().first().copy(subjects = listOf(subject))
        val restored = ExamLocalMapping.assemble(
            ExamLocalMapping.toEntity(exam),
            ExamLocalMapping.subjectsOf(exam),
            emptyList()
        )
        assertEquals(subject, restored.subjects.single())
        assertEquals(subject, exam.toPayload().toStored().subjects.single())
        assertTrue(ExamCatalog.allSubjects().size > ExamCatalog.subjectsForTrunk("engenharia").size)
    }

    @Test
    fun questionHeaderMetaResolvesCanonicalSubjectAndTopicBreadcrumbWithoutDifficulty() {
        val question = QuestionDto(
            id = "93439b5be942606722b541bc",
            question = "Questão sobre movimentos na estrutura a termo",
            answers = listOf("Certo", "Errado"),
            correctAnswerIndices = listOf(0),
            disciplina = "Disciplina Geral",
            source = "Cebraspe",
            examYear = 2024,
            level = "Difícil",
            canonicalSubjectId = "DIR_ADM",
            canonicalTopicId = "DIR_ADM_01_001"
        )
        val meta = ExamCatalog.resolveQuestionHeaderMeta(question)
        assertEquals("Direito Administrativo", meta.disciplineLabel)
        assertEquals(
            "Atos administrativos e licitações › Elementos e atributos dos atos administrativos",
            meta.topicBreadcrumb
        )
        assertEquals("Direito Administrativo · Cebraspe · 2024", meta.disciplineAndSourceLine)
        assertFalse(meta.disciplineAndSourceLine.contains("Difícil"))
    }
}
