package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.data.remote.*
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.data.repository.toPayload
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Protocol invariants; fakes model network outcomes, not Room's SQL/transaction implementation. */
class RepositoryContractTest {
    private val json=Json { ignoreUnknownKeys=true }
    private inline fun <reified T> fake(crossinline call:(String,Array<out Any?>)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)){_,method,args->
            try { call(method.name,args?:emptyArray()) }
            catch(error:Throwable) {
                // Model a suspend failure, rather than Java Proxy wrapping a
                // checked IOException in UndeclaredThrowableException.
                val continuation=args?.lastOrNull() as? kotlin.coroutines.Continuation<Any?>
                if(continuation==null)throw error
                continuation.resumeWith(Result.failure(error))
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }
        } as T
    private fun question(id:String="000000000000000000000001")=QuestionCacheMapping.withRevision(QuestionDto(id=id,question="Q",answers=listOf("A","B"),correctAnswerIndices=listOf(1),disciplina="Finanças Públicas",source="Fixture",level="Médio"))
    private fun attempt(q:QuestionDto=question(),owner:Long=123,id:String=UUID.randomUUID().toString())=PendingAttemptEntity(
        localId=1,questionId=q.id,chosenIndex=1,chosenText="B",isCorrect=true,timeSpentSeconds=10,confidenceLevel=3,sessionUuid=id,quizMode="STANDARD",examBlock=null,contestId=null,userNote=null,createdAtEpochMs=Instant.parse("2026-10-31T22:00:00Z").toEpochMilli(),ownerUserId=owner,attemptId=id,questionRevision=q.questionRevision)
    private fun ack(a:PendingAttemptEntity)=ProcessedAttemptDto(attemptId=a.attemptId,attemptsTotal=1,correctTotal=1,nextReviewAt="2026-11-07T22:00:00Z",confidenceAvg=3,questionId=a.questionId,isCorrect=true,confidenceQuadrant=ConfidenceQuadrant.HIGH_CONFIDENCE_CORRECT,highConfidenceErrorAlert=false,streakAtual=1,streakMax=1,pointsTotal=.2,pointsWithDecay=.2,isMastered=false,nextReviewIntervalDays=7,accuracyAccumulatedPct=100.0,inErrorNotebook=false)

    private inner class Store {
        val pending=ConcurrentHashMap<String,PendingAttemptEntity>()
        val questions=ConcurrentHashMap<String,CachedQuestionEntity>()
        val learning=ConcurrentHashMap<String,LearningRecordEntity>()
        val reports=ConcurrentHashMap<String,PendingReportEntity>()
        val metadata=ConcurrentHashMap<String,String>()
        val examRows=ConcurrentHashMap<String,UserExamEntity>()
        val subjectRows=ConcurrentHashMap<String,List<ExamSubjectEntity>>()
        val topicRows=ConcurrentHashMap<String,List<ExamTopicEntity>>()
        var remoteExams:List<ExamBlueprintPayload> = emptyList()
        fun putExam(exam:StoredExam){examRows[exam.examId]=ExamLocalMapping.toEntity(exam);subjectRows[exam.examId]=ExamLocalMapping.subjectsOf(exam);topicRows[exam.examId]=ExamLocalMapping.topicsOf(exam)}
        val dao=fake<ConcursoLocalDao>{method,args->when(method){
            "getAllPendingAttempts"->pending.values.sortedBy{it.createdAtEpochMs}
            "getCachedQuestions"->questions.values.toList()
            "findQuestionById"->questions[args[0]]
            "countPendingAttempts"->pending.size
            "countCachedQuestions"->questions.size
            "bindLegacyOwner"->{pending.replaceAll{_,a->if(a.ownerUserId==0L)a.copy(ownerUserId=args[0] as Long)else a};Unit}
            "findPendingAttemptInSession"->pending.values.find{it.sessionUuid==args[0]&&it.questionId==args[1]}
            "enqueuePendingAttempt"->{val a=args[0] as PendingAttemptEntity;pending[a.attemptId]=a;Unit}
            "acknowledgeAttempt"->{val a=pending[args[0]];if(a?.ownerUserId==args[1])pending.remove(args[0]);Unit}
            "markAttemptError"->{val a=pending[args[0]];if(a!=null&&a.ownerUserId==args[1])pending[a.attemptId]=a.copy(syncError=args[2] as String?);Unit}
            "insertQuestions"->{(args[0] as List<*>).filterIsInstance<CachedQuestionEntity>().forEach{questions[it.id]=it};Unit}
            "updateLearningFlags"->Unit
            else->error("Unexpected DAO operation $method")
        }}
        val exams=fake<ExamDao>{method,args->when(method){
            "countExams"->1
            "listExams"->examRows.values.toList()
            "listPendingExams"->examRows.values.filter { it.syncStatus!="SYNCED" }
            "listTrunks"->emptyList<CareerTrunkEntity>()
            "upsertTrunks"->Unit
            "subjectsFor"->subjectRows[args[0]].orEmpty()
            "topicsFor"->topicRows[args[0]].orEmpty()
            "upsertExam"->{val e=args[0] as UserExamEntity;examRows[e.examId]=e;Unit}
            "clearSubjects"->{subjectRows.remove(args[0]);Unit}
            "clearTopics"->{topicRows.remove(args[0]);Unit}
            "upsertSubjects"->{(args[0] as List<*>).filterIsInstance<ExamSubjectEntity>().groupBy { it.examId }.forEach { (id,rows)->subjectRows[id]=rows };Unit}
            "upsertTopics"->{(args[0] as List<*>).filterIsInstance<ExamTopicEntity>().groupBy { it.examId }.forEach { (id,rows)->topicRows[id]=rows };Unit}
            else->error("Unexpected exam operation $method")
        }}
        val durable=fake<DurableStateDao>{method,args->when(method){
            "learning"->learning.values.toList()
            "putLearning"->{(args[0] as List<*>).filterIsInstance<LearningRecordEntity>().forEach{learning[it.questionId]=it};Unit}
            "metadata"->metadata[args[0]]
            "preservedExamEdits"->metadata.filterKeys { it.startsWith("exam_backup:") }.map { LocalMetadataEntity(it.key,it.value) }
            "putMetadata"->{val m=args[0] as LocalMetadataEntity;metadata[m.key]=m.value;Unit}
            "reports"->reports.values.filter{it.ownerUserId==args[0]}
            "enqueueReport"->{val r=args[0] as PendingReportEntity;reports.putIfAbsent(r.eventId,r);Unit}
            "acknowledgeReport"->{reports.remove(args[0]);Unit}
            "observeMetadata"->flowOf(null)
            else->error("Unexpected durable operation $method")
        }}
        fun repository(submit:(SubmitBatchPayload)->SubmitBatchResponseDto={throw IOException("Synthetic outage")},sendReport:()->Unit={},updateExam:(ExamBlueprintPayload)->ExamBlueprintPayload={throw IOException("Synthetic outage")},generate:(QuizGeneratePayload)->List<QuestionDto> = {throw IOException("Synthetic outage")},aiTutor:(String,String,Int?)->AiAssistResponseDto={_,_,_->throw IOException("Synthetic outage")}):ConcursoRepository {
            val api=fake<ConcursoApiService>{method,args->when(method){
                "submitAttemptsBatch"->submit(args[0] as SubmitBatchPayload)
                "listExams"->remoteExams
                "updateExam"->updateExam(args[1] as ExamBlueprintPayload)
                "catalog"->Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/canonical-catalog.json")).bufferedReader().readText()).jsonObject
                "knowledgeCatalog"->Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/knowledge-catalog.json")).bufferedReader().readText()).jsonObject
                "cacheState"->CacheStateResponse(1,(args[0] as CacheStatePayload).questionIds.map { CacheStateItem(it,questions[it]?.let { q->QuestionCacheMapping.toDto(q,json) }) })
                "learningState"->LearningPage(emptyList())
                "reportQuestionProblem"->{sendReport();Unit}
                "generateQuizQuestions"->generate(args[0] as QuizGeneratePayload)
                "requestAiTutor"->aiTutor(args[0] as String,args[1] as String,args[2] as Int?)
                else->error("Unexpected API operation $method")
            }}
            return ConcursoRepository(api,dao,exams,json,durable=durable,ownerUserId=123)
        }
        fun add(a:PendingAttemptEntity){pending[a.attemptId]=a;questions[a.questionId]=QuestionCacheMapping.toEntity(question(a.questionId),json)}
    }

    @Test fun zeroAckDoesNotDeleteAndIsReportedAsPending()=runBlocking {
        val store=Store();val a=attempt();store.add(a)
        val failure=runCatching{store.repository(submit={SubmitBatchResponseDto(0,emptyList())}).syncPendingAttempts(123)}.exceptionOrNull()
        assertNotNull(failure);assertEquals(setOf(a.attemptId),store.pending.keys)
    }
    @Test fun partialAckRemovesOnlyTheConfirmedIdentity()=runBlocking {
        val store=Store();val a=attempt();val b=attempt(question("000000000000000000000002"));store.add(a);store.add(b)
        runCatching{store.repository(submit={SubmitBatchResponseDto(1,listOf(ack(a)),listOf(RejectedAttemptDto(b.attemptId,b.questionId,"Retry",true)))}).syncPendingAttempts(123)}
        assertFalse(store.pending.containsKey(a.attemptId));assertTrue(store.pending.containsKey(b.attemptId));assertTrue(store.learning.containsKey(a.questionId))
    }
    @Test fun lossOfResponseKeepsTheImmutableEventAndOriginalOccurrenceTime()=runBlocking {
        val store=Store();val a=attempt();store.add(a)
        val seen=mutableListOf<AnswerAttemptPayload>()
        val repo=store.repository(submit={seen+=it.attempts;throw IOException("ACK lost")})
        repeat(2){assertTrue(runCatching{repo.syncPendingAttempts(123)}.isFailure)}
        assertEquals(2,seen.size);assertEquals(seen[0],seen[1]);assertEquals("2026-10-31T22:00:00Z",seen[0].occurredAt);assertEquals(a,store.pending[a.attemptId])
    }
    @Test fun simultaneousSyncsSendAnEventOnlyOnce()=runBlocking {
        val store=Store();val a=attempt();store.add(a);val calls=AtomicInteger()
        val repo=store.repository(submit={calls.incrementAndGet();Thread.sleep(60);SubmitBatchResponseDto(1,listOf(ack(a)))})
        listOf(async(Dispatchers.Default){repo.syncPendingAttempts(123)},async(Dispatchers.Default){repo.syncPendingAttempts(123)}).awaitAll()
        assertEquals(1,calls.get());assertTrue(store.pending.isEmpty())
    }
    @Test fun accountChangesCannotRetagOrTransmitAnotherOwnersQueue()=runBlocking {
        val store=Store();val a=attempt(owner=456);store.add(a);val calls=AtomicInteger()
        val repo=store.repository(submit={calls.incrementAndGet();SubmitBatchResponseDto(0,emptyList())})
        assertTrue(runCatching{repo.syncPendingAttempts(456)}.isFailure)
        repo.syncPendingAttempts(123)
        assertEquals(0,calls.get());assertEquals(456L,store.pending[a.attemptId]?.ownerUserId)
    }
    @Test fun emptyOfflineHistoryHasNoInventedAchievementsOrTasks()=runBlocking {
        val repo=Store().repository()
        val today=repo.getTodayDashboard(123);val progress=repo.getProgressAnalytics(123)
        assertEquals(0,today.questionsAnsweredToday);assertEquals(0L,today.pendingSrsReviewsCount)
        assertEquals(0,progress.totalQuestionsPracticed);assertEquals(0,progress.totalMasteredQuestions);assertEquals(0.0,progress.readinessIndexScore,0.0)
    }
    @Test fun emptyErrorNotebookAndSrsNeverBecomeOrdinaryPractice()=runBlocking {
        val store=Store();val q=question();store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        val repo=store.repository()
        assertTrue(repo.fetchOrGetOfflineQuiz(123,QuizMode.ERROR_NOTEBOOK,10).isEmpty())
        assertTrue(repo.fetchOrGetOfflineQuiz(123,QuizMode.SRS_REVIEW,10).isEmpty())
        assertEquals(1,repo.fetchOrGetOfflineQuiz(123,QuizMode.STANDARD,10).size)
    }
    @Test fun generalAndPersonalModesDoNotInheritAnArchivedPlannerSelection()=runBlocking {
        val store=Store();val q=question();val archived=ExamCatalog.systemExams().first().copy(examId="archived",lifecycleStatus="ARQUIVADO",isActiveInLadder=false)
        store.putExam(archived);store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        for(mode in listOf(QuizMode.SHUFFLE,QuizMode.SRS_REVIEW,QuizMode.ERROR_NOTEBOOK,QuizMode.MOST_BADASS,QuizMode.COMMON_TRUNK)) {
            val repo=store.repository(generate={ request ->assertNull(request.contestId);listOf(q) })
            assertEquals(q.id,repo.fetchOrGetOfflineQuiz(123,mode,10,"archived").single().id)
        }
        assertEquals(q.id,store.repository().fetchOrGetOfflineQuiz(123,QuizMode.SHUFFLE,10,"archived").single().id)
        assertTrue(runCatching { store.repository().fetchOrGetOfflineQuiz(123,QuizMode.STANDARD,10,"archived") }.isFailure)
        assertTrue(runCatching { store.repository().fetchOrGetOfflineQuiz(123,QuizMode.SIMULADO,10,"archived") }.isFailure)
        assertEquals("active",PracticeScope.selected(listOf(archived,archived.copy(examId="active",lifecycleStatus="RUMOR_PREVISTO",isActiveInLadder=true)),"archived"))
        assertEquals("",PracticeScope.selected(listOf(archived),"archived"))
    }
    @Test fun reviewFlagAndAvailabilityAgreeBetweenDashboardAndOfflineSelection()=runBlocking {
        val store=Store();val q=question();store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        val row=LearningRecord(q.id,q.disciplina,attempts=1,correct=0,accuracyPct=0.0,isMastered=false,inErrorNotebook=true,highConfidenceError=true,points=0.0,nextReviewAt="2099-01-01T00:00:00Z",reviewRequired=true,practiceAvailable=true)
        fun put(value:LearningRecord){store.learning[q.id]=LearningRecordEntity(q.id,0,json.encodeToString(value))}
        put(row);val repo=store.repository()
        assertEquals(1L,repo.getTodayDashboard(123).pendingSrsReviewsCount)
        assertEquals(q.id,repo.fetchOrGetOfflineQuiz(123,QuizMode.SRS_REVIEW,10).single().id)
        put(row.copy(practiceAvailable=false))
        val dashboard=repo.getTodayDashboard(123)
        assertEquals(0L,dashboard.pendingSrsReviewsCount);assertEquals(0L,dashboard.errorNotebookCount)
        assertTrue(runCatching { repo.fetchOrGetOfflineQuiz(123,QuizMode.SRS_REVIEW,10) }.isFailure)
        assertTrue(runCatching { repo.fetchOrGetOfflineQuiz(123,QuizMode.ERROR_NOTEBOOK,10) }.isFailure)
        assertTrue(runCatching { repo.fetchOrGetOfflineQuiz(123,QuizMode.SHUFFLE,10) }.isFailure)
        assertEquals(1,repo.getProgressAnalytics(123).totalQuestionsPracticed)
    }
    @Test fun serverRejectionIsNotRewrittenAsAnInsufficientGradeOrSilentlyReplacedByCache()=runBlocking {
        val store=Store();val q=question();store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        val body="""{"message":"Requisição inválida: Concurso arquivado; selecione outro concurso"}"""
        val repo=store.repository(generate={throw retrofit2.HttpException(retrofit2.Response.error<List<QuestionDto>>(400,body.toResponseBody("application/json".toMediaType())))})
        val error=runCatching { repo.fetchOrGetOfflineQuiz(123,QuizMode.STANDARD,10,"CPNU") }.exceptionOrNull()
        assertEquals("Concurso arquivado; selecione outro concurso",error?.message)
    }
    @Test fun cacheRoundtripPreservesMeaningWithoutInventingCanonicalLinks(){
        val original=question().copy(syllabusTopicIds=listOf("topic"),commonTrunkId="common",isBorrowedAnalogous=true,hasDrSincero=true,hasMnemonic=true,hasPlausibility=true,hintsCount=3,userStats=UserQuestionBriefStats(4,75.0,"2026-10-31T22:00:00Z",true,true))
        assertEquals(original,QuestionCacheMapping.toDto(QuestionCacheMapping.toEntity(original,json),json))
        assertNull(QuestionCacheMapping.inferCanonical("Matemática Financeira"));assertNull(QuestionCacheMapping.inferCanonical("Mercado Financeiro"))
    }
    @Test fun eventChangesAfterEnqueueAreRejectedLocally()=runBlocking {
        val store=Store();val a=attempt();val repo=store.repository()
        repo.recordAttemptOfflineFirst(123,a)
        assertTrue(runCatching{repo.recordAttemptOfflineFirst(123,a.copy(confidenceLevel=1))}.isFailure)
        assertEquals(3,store.pending[a.attemptId]?.confidenceLevel)
    }
    @Test fun reportIsDurableBeforeSuccessAndSurvivesOutage()=runBlocking {
        val store=Store();val repo=store.repository(sendReport={throw IOException("offline")})
        assertTrue(repo.reportQuestionProblem(123,question().id,VALID_PROBLEM_CATEGORIES.first()))
        assertEquals(1,store.reports.size)
        assertTrue(runCatching{repo.syncPendingAttempts(123)}.isFailure)
        assertEquals(1,store.reports.size)
        assertTrue(repo.reportQuestionProblem(123,question().id,VALID_PROBLEM_CATEGORIES.first()));assertEquals(1,store.reports.size)
    }
    @Test fun aPermanentRejectionPreservesAnInspectableRecordWithoutAutomaticReplay()=runBlocking {
        val store=Store();val a=attempt();store.add(a)
        val repo=store.repository(submit={SubmitBatchResponseDto(0,emptyList(),listOf(RejectedAttemptDto(a.attemptId,a.questionId,"Question changed",false)))})
        repo.syncPendingAttempts(123)
        assertEquals("Question changed",store.pending[a.attemptId]?.syncError)
        assertEquals("Question changed",repo.pendingReview().single().status)
    }
    @Test fun queuesLargerThanOneBatchAreDrainedWithoutChangingEventIdentity()=runBlocking {
        val store=Store();repeat(245){store.add(attempt(question("%024x".format(it+1))))}
        val seen=mutableListOf<String>();val sizes=mutableListOf<Int>()
        val repo=store.repository(submit={ batch ->sizes+=batch.attempts.size;seen+=batch.attempts.map { it.attemptId };SubmitBatchResponseDto(batch.attempts.size,batch.attempts.map { ack(store.pending.getValue(it.attemptId)) }) })
        assertEquals(245,repo.syncPendingAttempts(123));assertEquals(listOf(120,120,5),sizes)
        assertEquals(245,seen.distinct().size);assertTrue(store.pending.isEmpty())
    }
    @Test fun anIncompletePullCannotEraseConfirmedPointsWhenAnotherAnswerIsPending(){
        val q=question();val event=LearningEvent("one","2026-10-31T22:00:00Z",true,3,10)
        val base=LearningRecord(q.id,q.disciplina,attempts=10,correct=8,accuracyPct=80.0,isMastered=true,inErrorNotebook=false,highConfidenceError=false,points=2.4,nextReviewAt="2026-11-30T22:00:00Z",events=listOf(event))
        val projected=LearningProjection.project(base,q,listOf(event.copy(attemptId="two",occurredAt="2026-11-01T22:00:00Z")))
        assertEquals(2.4,projected.points,0.0);assertEquals(11,projected.attempts);assertTrue(projected.isMastered)
    }
    @Test fun resolvingAnExamConflictPreservesAndExportsTheExactLocalEditBeforeUsingRemote()=runBlocking {
        val store=Store()
        val local=ExamCatalog.systemExams().first().copy(title="My conflicting edit",syncStatus="PENDING_UPDATE",revision=2,syncError="Conflito de revisão")
        val remote=local.copy(title="Other device revision",revision=3,syncStatus="SYNCED",syncError=null)
        store.putExam(local);store.remoteExams=listOf(remote.toPayload())
        val directory=java.nio.file.Files.createTempDirectory("cogniva-conflict-test").toFile()
        try {
            val repo=store.repository();val exported=repo.preserveAndUseServerExam(local,directory)
            assertEquals(remote.title,repo.loadStoredExams().single().title)
            val document=Json.parseToJsonElement(exported.readText()).jsonObject
            val backup=document.getValue("preserved_exam_edits").jsonArray.single().jsonObject
            assertEquals(local.title,backup.getValue("title").jsonPrimitive.content)
            assertEquals(local.revision,backup.getValue("revision").jsonPrimitive.long)
            assertTrue(repo.pendingReview().any { it.title==local.title })
        }finally{directory.listFiles()?.forEach { it.delete() };directory.delete()}
    }
    @Test fun anExamAckCannotReplaceAnEditMadeWhileTheRequestWasInFlight()=runBlocking {
        val store=Store();val sent=ExamCatalog.systemExams().first().copy(title="Sent revision",revision=2,syncStatus="PENDING_UPDATE")
        store.putExam(sent)
        val repo=store.repository(updateExam={ payload ->store.putExam(sent.copy(title="Edited during send"));payload.copy(revision=3,syncStatus="SYNCED") })
        assertTrue(runCatching { repo.syncPendingAttempts(123) }.isFailure)
        val actual=repo.loadStoredExams().single()
        assertEquals("Edited during send",actual.title);assertEquals(3L,actual.revision);assertEquals("PENDING_UPDATE",actual.syncStatus)
    }
    @Test fun offlineCommonTrunkUsesAllActiveGradesInsteadOfOnlyTheOpenedContest()=runBlocking {
        val store=Store();val template=ExamCatalog.systemExams().find { it.examId=="CPNU" }!!
        val openedSubject=template.subjects.first { it.canonicalSubjectId!="FIN_PUB" }
        val finance=template.subjects.find { it.canonicalSubjectId=="FIN_PUB" }!!
        store.putExam(template.copy(examId="opened",subjects=listOf(openedSubject.copy(weight=1.0)),isActiveInLadder=true))
        store.putExam(template.copy(examId="other",subjects=listOf(finance.copy(weight=100.0)),isActiveInLadder=true))
        store.putExam(template.copy(examId="third",subjects=listOf(finance.copy(weight=100.0)),isActiveInLadder=true))
        val q=question().copy(canonicalSubjectId="FIN_PUB",canonicalTopicId="FIN_PUB_01")
        store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        val selected=store.repository().fetchOrGetOfflineQuiz(123,QuizMode.COMMON_TRUNK,1,contestId="opened")
        assertEquals(listOf(q.id),selected.map { it.id })
    }
    @Test fun anExplicitLegacyLabelCanSelectFreePracticeWithoutInventingATopic()=runBlocking {
        val store=Store();val template=ExamCatalog.systemExams().find { it.examId=="CPNU" }!!
        store.putExam(template.copy(subjects=template.subjects.filter { it.canonicalSubjectId=="SOC_TRAB" }))
        val q=question().copy(disciplina="Sociologia e Psicologia Aplicadas")
        store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        val selected=store.repository().fetchOrGetOfflineQuiz(123,QuizMode.STANDARD,1,contestId="CPNU").single()
        assertEquals(q.id,selected.id);assertNull(selected.canonicalTopicId);assertNull(selected.canonicalSubjectId)
    }
    @Test fun legacyInferredColumnsRemainPreservedButDoNotBecomeVerifiedLinks(){
        val legacy=QuestionCacheMapping.toEntity(question(),json).copy(payloadJson=null,canonicalSubjectId="FIN_PUB",canonicalTopicId="FIN_PUB_01")
        val restored=QuestionCacheMapping.toDto(legacy,json)
        assertNull(restored.canonicalSubjectId);assertNull(restored.canonicalTopicId)
        assertEquals("FIN_PUB_01",legacy.canonicalTopicId)
    }
    @Test fun anOfflinePreAnswerTutorNeverFallsBackToAnAnswerRevealingExplanation()=runBlocking {
        val store=Store();val q=question().copy(explanation="ANSWER_REFERENCE_DO_NOT_REVEAL")
        store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        val repo=store.repository()
        for(kind in listOf("contextual-hint","child-friendly-pre"))assertFalse(repo.requestAiTutor(q.id,kind).contains("ANSWER_REFERENCE_DO_NOT_REVEAL"))
        assertTrue(repo.requestAiTutor(q.id,"explanation").contains("ANSWER_REFERENCE_DO_NOT_REVEAL"))
        val backendReason="Erro ao consultar provedor de IA: xAI indisponível (HTTP 403): limite de créditos atingido"
        val body="""{"error":true,"message":"$backendReason"}"""
        val httpRepo=store.repository(aiTutor={_,_,_->throw retrofit2.HttpException(retrofit2.Response.error<AiAssistResponseDto>(502,body.toResponseBody("application/json".toMediaType())))})
        val hintError=httpRepo.requestAiTutor(q.id,"contextual-hint")
        assertTrue(hintError.contains(backendReason))
        assertFalse(hintError.contains("ANSWER_REFERENCE_DO_NOT_REVEAL"))
        val explanationError=httpRepo.requestAiTutor(q.id,"explanation")
        assertTrue(explanationError.contains(backendReason))
        assertTrue(explanationError.contains("ANSWER_REFERENCE_DO_NOT_REVEAL"))
    }
    @Test fun componentScopedPracticeSelectsLegacyQuestionsForTheComponentSubject()=runBlocking {
        val store=Store();val template=ExamCatalog.systemExams().find { it.examId=="CPNU" }!!
        val component=template.subjects.first { it.canonicalSubjectId=="SOC_TRAB" }
        store.putExam(template.copy(subjects=listOf(component)))
        val q=question().copy(disciplina="Sociologia e Psicologia Aplicadas",canonicalSubjectId=null,canonicalTopicId=null)
        store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        val selected=store.repository().fetchOrGetOfflineQuiz(123,QuizMode.STANDARD,1,contestId="CPNU",componentId=component.localSubjectId).single()
        assertEquals(q.id,selected.id);assertNull(selected.canonicalTopicId);assertNull(selected.canonicalSubjectId)
        assertEquals(component.localSubjectId,selected.examComponentId)
    }
    @Test fun subjectMasteryAccountsForUnpracticedActiveTopicsInActiveExamAndProjectsReviewRequired(){
        val q=QuestionCacheMapping.withRevision(question().copy(canonicalSubjectId="FIN_PUB",canonicalTopicId="FIN_PUB_01",knowledgeProjectionVersion=1,knowledgeBindingVerified=true))
        val pastEvent=LearningEvent("ev-1","2020-01-01T12:00:00Z",true,3,15,q.questionRevision)
        val projected=LearningProjection.project(null,q,listOf(pastEvent))
        assertTrue(projected.reviewRequired)
        val futureEvent=LearningEvent("ev-2","2099-01-01T12:00:00Z",true,3,15,q.questionRevision)
        val futureProjected=LearningProjection.project(null,q,listOf(futureEvent))
        assertFalse(futureProjected.reviewRequired)
        val exam=ExamCatalog.systemExams().find { it.examId=="CPNU" }!!.let { base ->
            val fin=base.subjects.first { it.canonicalSubjectId=="FIN_PUB" }.copy(
                topics=listOf(
                    StoredTopic("t1","FIN_PUB_01","FIN_PUB","Tópico 1","OFICIAL_CONFIRMADO"),
                    StoredTopic("t2","FIN_PUB_02","FIN_PUB","Tópico 2","OFICIAL_NOVO"),
                    StoredTopic("t3","FIN_PUB_03","FIN_PUB","Tópico Arquivado","ARQUIVADO")
                )
            )
            base.copy(subjects=listOf(fin))
        }
        val withoutExam=LearningProjection.masteryBySubject(listOf(projected)).getValue("FIN_PUB")
        val withExam=LearningProjection.masteryBySubject(listOf(projected),exam).getValue("FIN_PUB")
        assertEquals(withoutExam / 2.0,withExam,1e-9)
    }
    @Test fun deletingPendingCreateExamSettlesLocallyAndDeletingSyncedExamResolvesRevisionConflict()=runBlocking {
        val store=Store()
        val repo=store.repository(updateExam={ payload ->
            if(payload.revision==1L) throw retrofit2.HttpException(retrofit2.Response.error<ExamBlueprintPayload>(409,"{}".toResponseBody("application/json".toMediaType())))
            payload.copy(revision=payload.revision+1,syncStatus="SYNCED")
        })
        val draft=ExamDraft("Teste","Teste","Órgão","Cargo","fiscal","RUMOR_PREVISTO",emptyList(),"UNDEFINED",null,null,"",70.0,1,ExamCatalog.subjectsForTrunk("fiscal"))
        val localCreated=repo.createExamOffline(draft)
        assertEquals(1,repo.loadStoredExams().count { it.examId==localCreated.examId })
        repo.editExamOffline(localCreated.copy(lifecycleStatus="EXCLUIDO",isActiveInLadder=false))
        assertEquals(0,repo.loadStoredExams().count { it.examId==localCreated.examId })
        assertEquals("SYNCED",repo.loadStoredExams(includeDeleted=true).single { it.examId==localCreated.examId }.syncStatus)

        val remoteBase=ExamCatalog.systemExams().first().copy(examId="RFB",revision=1,syncStatus="SYNCED")
        store.putExam(remoteBase)
        store.remoteExams=listOf(remoteBase.copy(revision=3).toPayload())
        repo.editExamOffline(remoteBase.copy(lifecycleStatus="EXCLUIDO",isActiveInLadder=false))
        repo.syncPendingAttempts(123)
        val deleted=repo.loadStoredExams(includeDeleted=true).single { it.examId=="RFB" }
        assertEquals("EXCLUIDO",deleted.lifecycleStatus)
        assertEquals("SYNCED",deleted.syncStatus)
        assertEquals(4L,deleted.revision)
    }
}
