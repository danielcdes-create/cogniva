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
        val sessions=ConcurrentHashMap<String,QuizSessionEntity>()
        val metadata=ConcurrentHashMap<String,String>()
        val examRows=ConcurrentHashMap<String,UserExamEntity>()
        val subjectRows=ConcurrentHashMap<String,List<ExamSubjectEntity>>()
        val topicRows=ConcurrentHashMap<String,List<ExamTopicEntity>>()
        val learningChunkSizes=java.util.Collections.synchronizedList(mutableListOf<Int>())
        val questionChunkSizes=java.util.Collections.synchronizedList(mutableListOf<Int>())
        var remoteExams:List<ExamBlueprintPayload> = emptyList()
        fun putExam(exam:StoredExam){examRows[exam.examId]=ExamLocalMapping.toEntity(exam);subjectRows[exam.examId]=ExamLocalMapping.subjectsOf(exam);topicRows[exam.examId]=ExamLocalMapping.topicsOf(exam)}
        val dao=fake<ConcursoLocalDao>{method,args->when(method){
            "getAllPendingAttempts"->pending.values.sortedBy{it.createdAtEpochMs}
            "getCachedQuestions"->questions.values.toList()
            "findQuestionById"->questions[args[0]]
            "findQuestionsByIds"->{
                val ids=(args[0] as List<*>).filterIsInstance<String>()
                questionChunkSizes.add(ids.size)
                ids.mapNotNull { questions[it] }
            }
            "countPendingAttempts"->pending.size
            "countCachedQuestions"->questions.size
            "bindLegacyOwner"->{pending.replaceAll{_,a->if(a.ownerUserId==0L)a.copy(ownerUserId=args[0] as Long)else a};Unit}
            "findPendingAttemptInSession"->pending.values.find{it.sessionUuid==args[0]&&it.questionId==args[1]}
            "enqueuePendingAttempt"->{val a=args[0] as PendingAttemptEntity;pending[a.attemptId]=a;Unit}
            "acknowledgeAttempt"->{val a=pending[args[0]];if(a?.ownerUserId==args[1])pending.remove(args[0]);Unit}
            "dismissQuarantinedAttempt"->{
                val id=args[0] as String;val owner=args[1] as Long
                val a=pending[id]
                if(a!=null && a.ownerUserId==owner && a.syncError!=null){pending.remove(id);1}else 0
            }
            "clearQuarantinedAttempts"->{
                val owner=args[0] as Long
                val toRemove=pending.values.filter{it.ownerUserId==owner && it.syncError!=null}.map{it.attemptId}
                toRemove.forEach{pending.remove(it)}
                toRemove.size
            }
            "markAttemptError"->{val a=pending[args[0]];if(a!=null&&a.ownerUserId==args[1])pending[a.attemptId]=a.copy(syncError=args[2] as String?);Unit}
            "insertQuestions"->{(args[0] as List<*>).filterIsInstance<CachedQuestionEntity>().forEach{questions[it.id]=it};Unit}
            "clearCachedQuestions"->{questions.clear();Unit}
            "updateLearningFlags"->Unit
            else->error("Unexpected DAO operation $method")
        }}
        val exams=fake<ExamDao>{method,args->when(method){
            "countExams"->examRows.size.coerceAtLeast(1)
            "listExams"->examRows.values.toList()
            "listPendingExams"->examRows.values.filter { it.syncStatus!="SYNCED" }
            "listTrunks"->emptyList<CareerTrunkEntity>()
            "upsertTrunks"->Unit
            "setActive"->{val id=args[0] as String;val act=args[1] as Boolean;examRows[id]?.let{examRows[id]=it.copy(isActiveInLadder=act)};Unit}
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
            "learningForQuestions"->{
                val ids=(args[0] as List<*>).filterIsInstance<String>()
                learningChunkSizes.add(ids.size)
                ids.mapNotNull { learning[it] }
            }
            "putLearning"->{(args[0] as List<*>).filterIsInstance<LearningRecordEntity>().forEach{learning[it.questionId]=it};Unit}
            "metadata"->metadata[args[0]]
            "deleteMetadata"->{metadata.remove(args[0] as String);Unit}
            "preservedExamEdits"->metadata.filterKeys { it.startsWith("exam_backup:") }.map { LocalMetadataEntity(it.key,it.value) }
            "putMetadata"->{val m=args[0] as LocalMetadataEntity;metadata[m.key]=m.value;Unit}
            "reports"->reports.values.filter{it.ownerUserId==args[0]}
            "enqueueReport"->{val r=args[0] as PendingReportEntity;reports.putIfAbsent(r.eventId,r);Unit}
            "acknowledgeReport"->{reports.remove(args[0]);Unit}
            "createSession"->{val s=args[0] as QuizSessionEntity;sessions[s.sessionId]=s;Unit}
            "deactivateOtherSessions"->{
                val owner=args[0] as Long;val keep=args[1] as String
                sessions.replaceAll{_,s->if(s.ownerUserId==owner && s.active && s.sessionId!=keep)s.copy(active=false)else s};Unit
            }
            "saveSession"->{
                val id=args[0] as String;val owner=args[1] as Long;val payload=args[2] as String;val rev=args[3] as Long;val at=args[4] as Long
                val cur=sessions[id]
                if(cur!=null && cur.ownerUserId==owner && cur.active && cur.revision<rev){
                    sessions[id]=cur.copy(payloadJson=payload,revision=rev,updatedAt=at)
                }
                Unit
            }
            "completeSession"->{
                val id=args[0] as String;val owner=args[1] as Long
                sessions[id]?.takeIf{it.ownerUserId==owner}?.let{sessions[id]=it.copy(active=false)};Unit
            }
            "pausedSession"->{
                val owner=args[0] as Long
                sessions.values.filter{it.active && it.ownerUserId==owner}.maxByOrNull{it.updatedAt}
            }
            "pruneSessionsOlderThan"->{
                val cutoff=args[0] as Long
                sessions.entries.removeIf{it.value.updatedAt<cutoff};Unit
            }
            "observeMetadata"->flowOf(null)
            else->error("Unexpected durable operation $method")
        }}
        fun repository(
            submit:(SubmitBatchPayload)->SubmitBatchResponseDto={throw IOException("Synthetic outage")},
            sendReport:()->Unit={},
            updateExam:(ExamBlueprintPayload)->ExamBlueprintPayload={throw IOException("Synthetic outage")},
            generate:(QuizGeneratePayload)->List<QuestionDto> = {throw IOException("Synthetic outage")},
            aiTutor:(String,String,Int?)->AiAssistResponseDto={_,_,_->throw IOException("Synthetic outage")},
            saveOnboarding:(OnboardingPayload)->UserProfileDto={UserProfileDto(it.userId,displayName=it.displayName,dailyQuestionsTarget=it.dailyQuestionsTarget,dailyStudyHoursTarget=it.dailyStudyHoursTarget,activeContestIds=it.activeContestIds)},
            deleteAccount:(DeleteAccountRequest)->retrofit2.Response<Unit> = {retrofit2.Response.success(Unit)}
        ):ConcursoRepository {
            val api=fake<ConcursoApiService>{method,args->when(method){
                "submitAttemptsBatch"->submit(args[0] as SubmitBatchPayload)
                "listExams"->remoteExams
                "updateExam"->updateExam(args[1] as ExamBlueprintPayload)
                "catalog"->Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/exam-templates.json")).bufferedReader().readText()).jsonObject
                "knowledgeCatalog"->Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/knowledge-catalog.json")).bufferedReader().readText()).jsonObject
                "cacheState"->CacheStateResponse(1,(args[0] as CacheStatePayload).questionIds.map { CacheStateItem(it,questions[it]?.let { q->QuestionCacheMapping.toDto(q,json) }) })
                "learningState"->LearningPage(emptyList())
                "reportQuestionProblem"->{sendReport();Unit}
                "generateQuizQuestions"->generate(args[0] as QuizGeneratePayload)
                "requestAiTutor"->aiTutor(args[0] as String,args[1] as String,args[2] as Int?)
                "saveOnboardingPreferences"->saveOnboarding(args[0] as OnboardingPayload)
                "profile"->UserProfileDto(123,displayName="Estudante",isGuest=true)
                "getApkInfo"->{throw IOException("Synthetic offline APK check")}
                "deleteAccount"->deleteAccount(args[0] as DeleteAccountRequest)
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
    @Test fun tutorDistinguishesProviderNetworkAndProcessingFailuresWithoutRevealingAnswers()=runBlocking {
        val store=Store(); val q=question().copy(explanation="ANSWER_REFERENCE_DO_NOT_REVEAL")
        store.questions[q.id]=QuestionCacheMapping.toEntity(q,json)
        for(status in listOf(403,429,502,503)) {
            val reason="Motivo do servidor $status"
            val repo=store.repository(aiTutor={_,_,_->throw retrofit2.HttpException(retrofit2.Response.error<AiAssistResponseDto>(status,"""{"message":"$reason"}""".toResponseBody("application/json".toMediaType())))})
            for(kind in listOf("contextual-hint","child-friendly-pre")) {
                val message=repo.requestAiTutor(q.id,kind);assertTrue(message.contains(reason));assertFalse(message.contains(q.explanation!!));assertFalse(message.contains("quando houver conexão"))
            }
        }
        val noCached=question().copy(id="uncached")
        assertTrue(store.repository(aiTutor={_,_,_->throw IOException()}).requestAiTutor(noCached.id,"explanation").contains("conexão"))
        val processing=store.repository(aiTutor={_,_,_->throw IllegalStateException()})
        assertTrue(processing.requestAiTutor(noCached.id,"explanation").contains("processar"));assertFalse(processing.requestAiTutor(noCached.id,"explanation").contains("conexão"))
        for(kind in listOf("contextual-hint","child-friendly-pre"))assertFalse(processing.requestAiTutor(q.id,kind).contains(q.explanation!!))
        for(body in listOf("", "<html>Proxy error</html>", "{}", """{"message":"   "}""", """{"message":null}""")) {
            val repo=store.repository(aiTutor={_,_,_->throw retrofit2.HttpException(retrofit2.Response.error<AiAssistResponseDto>(502,body.toResponseBody("application/json".toMediaType())))})
            assertTrue(repo.requestAiTutor(noCached.id,"explanation").contains("HTTP 502"))
        }
        val cancelled=store.repository(aiTutor={_,_,_->throw CancellationException("cancelled")})
        try { cancelled.requestAiTutor(q.id,"contextual-hint");fail("Cancellation must propagate") } catch(_:CancellationException) { }
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
    @Test fun deletingAnAlreadyAbsentRemoteExamKeepsLocalGradeAndSettlesTheQueue()=runBlocking {
        val store=Store();val original=ExamCatalog.systemExams().first().copy(examId="missing-remote",revision=2,syncStatus="SYNCED")
        store.putExam(original);store.remoteExams=emptyList()
        val repo=store.repository(updateExam={throw retrofit2.HttpException(retrofit2.Response.error<ExamBlueprintPayload>(404,"{}".toResponseBody("application/json".toMediaType())))})
        repo.editExamOffline(original.copy(lifecycleStatus="EXCLUIDO",isActiveInLadder=false))
        assertTrue(repo.loadStoredExams().isEmpty())
        repo.syncPendingAttempts(123);repo.pullExams()
        val tombstone=repo.loadStoredExams(includeDeleted=true).single()
        assertEquals("EXCLUIDO",tombstone.lifecycleStatus);assertEquals("SYNCED",tombstone.syncStatus)
        assertEquals(original.subjects,tombstone.subjects);assertFalse(tombstone.isActiveInLadder)
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
    @Test fun topicScopedPracticeFiltersOfflineCacheToTheRequestedCanonicalTopic()=runBlocking {
        val store=Store()
        val template=ExamCatalog.systemExams().find { it.examId=="CPNU" }!!
        val component=template.subjects.first { it.canonicalSubjectId=="FIN_PUB" }
        store.putExam(template.copy(subjects=listOf(component)))
        val qTopic1=question("000000000000000000000011").copy(canonicalSubjectId="FIN_PUB",canonicalTopicId="FIN_PUB_01",syllabusTopicIds=listOf("FIN_PUB_01"))
        val qTopic2=question("000000000000000000000012").copy(canonicalSubjectId="FIN_PUB",canonicalTopicId="FIN_PUB_02",syllabusTopicIds=listOf("FIN_PUB_02"))
        store.questions[qTopic1.id]=QuestionCacheMapping.toEntity(qTopic1,json)
        store.questions[qTopic2.id]=QuestionCacheMapping.toEntity(qTopic2,json)
        val selected=store.repository().fetchOrGetOfflineQuiz(
            userId=123,
            mode=QuizMode.STANDARD,
            quantity=10,
            contestId="CPNU",
            componentId=component.localSubjectId,
            syllabusTopicId="FIN_PUB_01"
        )
        assertEquals(listOf(qTopic1.id),selected.map { it.id })
    }

    @Test fun profileSyncFailureNeverAbortsPendingAttemptSyncAndTerminal4xxClearsDirtyFlag() = runBlocking {
        val store = Store()
        val a = attempt()
        store.add(a)
        val dirtyPayload = OnboardingPayload(123, listOf("CPNU"), 45, 2.5, "Estudante")
        store.metadata["pending_profile"] = json.encodeToString(dirtyPayload)

        // 1. Transient HTTP 500 on profile sync: attempts MUST still sync, and pending_profile stays queued for retry
        val repo500 = store.repository(
            submit = { SubmitBatchResponseDto(1, listOf(ack(a))) },
            saveOnboarding = {
                throw retrofit2.HttpException(
                    retrofit2.Response.error<UserProfileDto>(500, "{}".toResponseBody("application/json".toMediaType()))
                )
            }
        )
        assertTrue(runCatching { repo500.syncPendingAttempts(123) }.isFailure)
        assertTrue("Pending attempt must sync even when profile sync returns 500", store.pending.isEmpty())
        assertTrue("Dirty profile flag must remain set for retry on 500", store.metadata["pending_profile"]?.isNotBlank() == true)

        // 2. Terminal HTTP 422 on profile sync: attempts sync AND dirty profile flag is cleared
        val b = attempt(question("000000000000000000000002"))
        store.add(b)
        val repo422 = store.repository(
            submit = { SubmitBatchResponseDto(1, listOf(ack(b))) },
            saveOnboarding = {
                throw retrofit2.HttpException(
                    retrofit2.Response.error<UserProfileDto>(422, """{"message":"invalid"}""".toResponseBody("application/json".toMediaType()))
                )
            }
        )
        val synced = repo422.syncPendingAttempts(123)
        assertEquals(1, synced)
        assertTrue(store.pending.isEmpty())
        assertEquals("", store.metadata["pending_profile"].orEmpty())
    }

    @Test fun quarantinedAttemptsCanBeDismissedAndDoNotBlockClearLocalQuestionCache() = runBlocking {
        val store = Store()
        val unsynced = attempt(question("000000000000000000000021"))
        store.add(unsynced)
        val repo = store.repository()

        // Unsynced attempt (syncError == null) blocks clearing question cache
        assertTrue(runCatching { repo.clearLocalQuestionCache() }.isFailure)
        assertEquals(1, store.questions.size)

        // Mark attempt as permanently rejected/quarantined (syncError != null)
        store.pending[unsynced.attemptId] = unsynced.copy(syncError = "Questão substituída no servidor")

        // Quarantined item no longer permanently blocks clearing local question cache!
        repo.clearLocalQuestionCache()
        assertTrue(store.questions.isEmpty())
        assertEquals(1, store.pending.size)

        // PendingReviewItem exposes canDismiss = true for quarantined attempt and user can dismiss it
        val reviewItems = repo.pendingReview()
        val reviewTarget = reviewItems.single { it.id == unsynced.attemptId }
        assertTrue(reviewTarget.canDismiss)
        assertTrue(repo.dismissQuarantinedAttempt(unsynced.attemptId))
        assertTrue(store.pending.isEmpty())
    }

    @Test fun learningMapForQuestionsChunksRequestsAtMost400Parameters() = runBlocking {
        val store = Store()
        val repo = store.repository()
        val ids = (1..950).map { idx -> "q-%04d".format(idx) }
        ids.forEach { qid ->
            val rec = LearningRecord(
                questionId = qid,
                disciplina = "Direito Constitucional",
                attempts = 2,
                correct = 1,
                accuracyPct = 50.0,
                isMastered = false,
                inErrorNotebook = true,
                highConfidenceError = false,
                points = 0.2,
                nextReviewAt = "2026-12-01T00:00:00Z"
            )
            store.learning[qid] = LearningRecordEntity(qid, 1L, json.encodeToString(rec))
        }
        store.learningChunkSizes.clear()
        val loaded = repo.learningMapForQuestions(ids)
        assertEquals(950, loaded.size)
        assertEquals(listOf(400, 400, 150), store.learningChunkSizes.toList())
        assertTrue(store.learningChunkSizes.all { it <= ConcursoRepository.SQLITE_BIND_CHUNK_SIZE })
    }

    @Test fun pruneAbandonedSessionsRemovesSessionsOlderThan30Days() = runBlocking {
        val store = Store()
        val repo = store.repository()
        val nowMs = Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()
        val staleMs = nowMs - (31L * 24L * 60L * 60L * 1000L)
        val recentMs = nowMs - (5L * 24L * 60L * 60L * 1000L)
        store.sessions["stale-1"] = QuizSessionEntity("stale-1", 123L, "{}", 1L, active = false, updatedAt = staleMs)
        store.sessions["stale-2"] = QuizSessionEntity("stale-2", 123L, "{}", 1L, active = true, updatedAt = staleMs)
        store.sessions["recent-1"] = QuizSessionEntity("recent-1", 123L, "{}", 1L, active = true, updatedAt = recentMs)

        repo.pruneAbandonedSessions(nowMs)
        assertEquals(setOf("recent-1"), store.sessions.keys)
    }

    @Test fun simuladoBatchPersistsAtomicallyAndRestoresProgressAcrossProcessDeath() = runBlocking {
        val store = Store()
        val q1 = question("000000000000000000000031")
        val q2 = question("000000000000000000000032")
        val q3 = question("000000000000000000000033")
        listOf(q1, q2, q3).forEach { store.questions[it.id] = QuestionCacheMapping.toEntity(it, json) }
        val repo = store.repository()

        val initialSession = SessionSnapshot(
            sessionId = "simulado-1",
            ownerUserId = 123L,
            questions = listOf(q1, q2, q3),
            mode = QuizMode.SIMULADO,
            exam = null,
            currentIndex = 1,
            answers = mapOf(
                q1.id to SessionAnswer(chosenIndex = 1, confidenceLevel = 3, timeSpentSeconds = 25, submitted = false),
                q2.id to SessionAnswer(chosenIndex = 0, confidenceLevel = 2, timeSpentSeconds = 30, submitted = false)
            ),
            revision = 2L
        )
        repo.createSession(initialSession)

        // Simulate SavedStateHandle surviving Android process death at index 2
        val savedHandle = androidx.lifecycle.SavedStateHandle(
            mapOf(
                "active_session_id" to "simulado-1",
                "active_session_index" to 2
            )
        )
        val testVmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val restoredVm = br.com.concursoprep.app.ui.MainViewModel(
                repo,
                appContext = null,
                savedStateHandle = savedHandle,
                overrideScope = testVmScope
            )
            withTimeout(5_000) {
                while (restoredVm.activeSession.value == null) {
                    delay(20)
                }
            }
            val restoredSession = checkNotNull(restoredVm.activeSession.value)
            assertEquals("simulado-1", restoredSession.sessionId)
            assertEquals(2, restoredSession.currentIndex)
            assertEquals(2, restoredSession.answers.size)
            assertTrue(restoredVm.isSimuladoSession.value)

            // Submit final Simulado batch atomically
            val finalAnswers = restoredSession.answers + (
                q3.id to SessionAnswer(chosenIndex = 1, confidenceLevel = 1, timeSpentSeconds = 18, submitted = false)
            )
            assertTrue(restoredVm.submitSimuladoBatch(finalAnswers))
            assertEquals(3, store.pending.size)
            val persistedAfterFinish = checkNotNull(repo.pausedSession())
            assertTrue(persistedAfterFinish.finished)
            assertTrue(persistedAfterFinish.answers.values.all { it.submitted })
        } finally {
            testVmScope.cancel()
        }
    }

    @Test fun lgpdDeleteAccountSendsConfirmExcluirAndWipesLocalQueueOnlyOnSuccess() = runBlocking {
        val store = Store()
        val a = attempt()
        store.add(a)
        store.metadata["pending_profile"] = """{"user_id":123}"""
        val capturedConfirm = mutableListOf<String>()

        // 1. Wrong confirmation word is rejected locally before calling server
        val repoRejectWord = store.repository(deleteAccount = {
            capturedConfirm += it.confirm
            retrofit2.Response.success(Unit)
        })
        assertTrue(runCatching { repoRejectWord.deleteAccount("CANCELAR") }.isFailure)
        assertTrue(capturedConfirm.isEmpty())
        assertEquals(1, store.pending.size)

        // 2. Server HTTP 500 preserves local pending queue
        val repo500 = store.repository(deleteAccount = {
            capturedConfirm += it.confirm
            retrofit2.Response.error(500, """{"message":"erro"}""".toResponseBody("application/json".toMediaType()))
        })
        assertTrue(runCatching { repo500.deleteAccount("EXCLUIR") }.isFailure)
        assertEquals(listOf("EXCLUIR"), capturedConfirm)
        assertEquals(1, store.pending.size)

        // 3. Server HTTP 200 wipes local pending queue and sync metadata
        val repoOk = store.repository(deleteAccount = {
            capturedConfirm += it.confirm
            retrofit2.Response.success(Unit)
        })
        repoOk.deleteAccount("EXCLUIR")
        assertEquals(listOf("EXCLUIR", "EXCLUIR"), capturedConfirm)
        assertTrue(store.pending.isEmpty())
        assertNull(store.metadata["pending_profile"])
    }
}
