package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.data.remote.*
import br.com.concursoprep.app.data.repository.ConcursoRepository
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

/** Protocol invariants; fakes model network outcomes, not Room's SQL/transaction implementation. */
class RepositoryContractTest {
    private val json=Json { ignoreUnknownKeys=true }
    private inline fun <reified T> fake(crossinline call:(String,Array<out Any?>)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)){_,method,args->call(method.name,args?:emptyArray())} as T
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
        val exams=fake<ExamDao>{method,_->when(method){
            "countExams"->1
            "listExams","listPendingExams","listTrunks"->emptyList<UserExamEntity>()
            else->error("Unexpected exam operation $method")
        }}
        val durable=fake<DurableStateDao>{method,args->when(method){
            "learning"->learning.values.toList()
            "putLearning"->{(args[0] as List<*>).filterIsInstance<LearningRecordEntity>().forEach{learning[it.questionId]=it};Unit}
            "metadata"->metadata[args[0]]
            "putMetadata"->{val m=args[0] as LocalMetadataEntity;metadata[m.key]=m.value;Unit}
            "reports"->reports.values.filter{it.ownerUserId==args[0]}
            "enqueueReport"->{val r=args[0] as PendingReportEntity;reports.putIfAbsent(r.eventId,r);Unit}
            "acknowledgeReport"->{reports.remove(args[0]);Unit}
            "observeMetadata"->flowOf(null)
            else->error("Unexpected durable operation $method")
        }}
        fun repository(submit:(SubmitBatchPayload)->SubmitBatchResponseDto={throw IOException("Synthetic outage")},sendReport:()->Unit={}):ConcursoRepository {
            val api=fake<ConcursoApiService>{method,args->when(method){
                "submitAttemptsBatch"->submit(args[0] as SubmitBatchPayload)
                "listExams"->emptyList<ExamBlueprintPayload>()
                "catalog"->Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/canonical-catalog.json")).bufferedReader().readText()).jsonObject
                "learningState"->LearningPage(emptyList())
                "reportQuestionProblem"->{sendReport();Unit}
                "generateQuizQuestions"->throw IOException("Synthetic outage")
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
}
