package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.data.remote.*
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.data.repository.toPayload
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
        val trunkRows=ConcurrentHashMap<String,CareerTrunkEntity>()
        val masteryRows=ConcurrentHashMap<String,CanonicalMasteryEntity>()
        val metadataFlows=ConcurrentHashMap<String,kotlinx.coroutines.flow.MutableStateFlow<String?>>()
        val pendingCountFlow=kotlinx.coroutines.flow.MutableStateFlow(0)
        val allExamsFlow=kotlinx.coroutines.flow.MutableStateFlow<List<UserExamEntity>>(emptyList())
        val learningChunkSizes=java.util.Collections.synchronizedList(mutableListOf<Int>())
        val questionChunkSizes=java.util.Collections.synchronizedList(mutableListOf<Int>())
        var remoteExams:List<ExamBlueprintPayload> = emptyList()
        private fun refreshFlows(){
            pendingCountFlow.value=pending.size
            allExamsFlow.value=examRows.values.sortedWith(compareBy<UserExamEntity>{it.ladderPriority}.thenBy{it.shortName})
        }
        fun putExam(exam:StoredExam){examRows[exam.examId]=ExamLocalMapping.toEntity(exam);subjectRows[exam.examId]=ExamLocalMapping.subjectsOf(exam);topicRows[exam.examId]=ExamLocalMapping.topicsOf(exam);refreshFlows()}
        val dao=fake<ConcursoLocalDao>{method,args->when(method){
            "getAllPendingAttempts"->pending.values.sortedBy{it.createdAtEpochMs}
            "getCachedQuestions"->{
                val disc=args[0] as? String
                val limit=(args[1] as? Int ?: Int.MAX_VALUE).coerceAtLeast(0)
                questions.values.filter { disc==null || it.disciplina==disc }.take(limit)
            }
            "findQuestionById"->questions[args[0]]
            "findQuestionsByIds"->{
                val ids=(args[0] as List<*>).filterIsInstance<String>()
                questionChunkSizes.add(ids.size)
                ids.mapNotNull { questions[it] }
            }
            "countPendingAttempts"->pending.size
            "observePendingAttemptCount"->pendingCountFlow
            "countCachedQuestions"->questions.size
            "bindLegacyOwner"->{pending.replaceAll{_,a->if(a.ownerUserId==0L)a.copy(ownerUserId=args[0] as Long)else a};Unit}
            "findPendingAttemptInSession"->pending.values.find{it.sessionUuid==args[0]&&it.questionId==args[1]}
            "enqueuePendingAttempt"->{val a=args[0] as PendingAttemptEntity;pending[a.attemptId]=a;refreshFlows();Unit}
            "acknowledgeAttempt"->{val a=pending[args[0]];if(a?.ownerUserId==args[1]){pending.remove(args[0]);refreshFlows()};Unit}
            "dismissQuarantinedAttempt"->{
                val id=args[0] as String;val owner=args[1] as Long
                val a=pending[id]
                if(a!=null && a.ownerUserId==owner && a.syncError!=null){pending.remove(id);refreshFlows();1}else 0
            }
            "clearQuarantinedAttempts"->{
                val owner=args[0] as Long
                val toRemove=pending.values.filter{it.ownerUserId==owner && it.syncError!=null}.map{it.attemptId}
                toRemove.forEach{pending.remove(it)}
                refreshFlows()
                toRemove.size
            }
            "markAttemptError"->{val a=pending[args[0]];if(a!=null&&a.ownerUserId==args[1])pending[a.attemptId]=a.copy(syncError=args[2] as String?);Unit}
            "insertQuestions"->{(args[0] as List<*>).filterIsInstance<CachedQuestionEntity>().forEach{questions[it.id]=it};Unit}
            "clearCachedQuestions"->{questions.clear();Unit}
            "updateLearningFlags"->{
                val id=args[0] as String;val err=args[1] as Boolean;val mast=args[2] as Boolean
                questions[id]?.let{questions[id]=it.copy(inErrorNotebook=err,isMastered=mast)}
                Unit
            }
            "updateAttemptEvidence"->{
                val sessId=args[0] as String;val qId=args[1] as String;val evId=args[2] as String;val owner=args[3] as Long
                pending.values.find { it.sessionUuid==sessId && it.questionId==qId && it.ownerUserId==owner }?.let {
                    pending[it.attemptId]=it.copy(selectedEvidenceId=evId)
                }
                Unit
            }
            else->error("Unexpected DAO operation $method")
        }}
        val exams=fake<ExamDao>{method,args->when(method){
            "observeAllExams"->allExamsFlow
            "countExams"->examRows.size
            "listExams"->examRows.values.sortedWith(compareBy<UserExamEntity>{it.ladderPriority}.thenBy{it.shortName})
            "listPendingExams"->examRows.values.filter { it.syncStatus!="SYNCED" }
            "listTrunks"->trunkRows.values.toList()
            "upsertTrunks"->{(args[0] as List<*>).filterIsInstance<CareerTrunkEntity>().forEach{trunkRows[it.slug]=it};Unit}
            "setTrunkFocus"->{val slug=args[0] as String;val foc=args[1] as Boolean;trunkRows[slug]?.let{trunkRows[slug]=it.copy(isFocusedTrunk=foc)};Unit}
            "setActive"->{val id=args[0] as String;val act=args[1] as Boolean;examRows[id]?.let{examRows[id]=it.copy(isActiveInLadder=act);refreshFlows()};Unit}
            "setSyncError"->{val id=args[0] as String;val err=args[1] as? String;examRows[id]?.let{examRows[id]=it.copy(syncError=err);refreshFlows()};Unit}
            "markSynced"->{val id=args[0] as String;examRows[id]?.let{examRows[id]=it.copy(syncStatus="SYNCED");refreshFlows()};Unit}
            "subjectsFor"->subjectRows[args[0]].orEmpty()
            "topicsFor"->topicRows[args[0]].orEmpty()
            "upsertExam"->{val e=args[0] as UserExamEntity;examRows[e.examId]=e;refreshFlows();Unit}
            "clearSubjects"->{subjectRows.remove(args[0]);Unit}
            "clearTopics"->{topicRows.remove(args[0]);Unit}
            "clearAllTopics"->{topicRows.clear();Unit}
            "clearAllSubjects"->{subjectRows.clear();Unit}
            "clearAllExams"->{examRows.clear();refreshFlows();Unit}
            "clearAllMastery"->{masteryRows.clear();Unit}
            "clearAllTrunks"->{trunkRows.clear();Unit}
            "upsertMastery"->{val m=args[0] as CanonicalMasteryEntity;masteryRows[m.canonicalTopicId]=m;Unit}
            "listMastery"->masteryRows.values.toList()
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
            "deleteMetadata"->{
                val k=args[0] as String
                metadata.remove(k)
                metadataFlows[k]?.value=null
                Unit
            }
            "clearAllMetadata"->{
                metadata.clear()
                metadataFlows.values.forEach{it.value=null}
                Unit
            }
            "clearAllLearning"->{learning.clear();Unit}
            "preservedExamEdits"->metadata.filterKeys { it.startsWith("exam_backup:") }.map { LocalMetadataEntity(it.key,it.value) }
            "putMetadata"->{
                val m=args[0] as LocalMetadataEntity
                metadata[m.key]=m.value
                metadataFlows[m.key]?.value=m.value
                Unit
            }
            "reports"->reports.values.filter{it.ownerUserId==args[0]}
            "enqueueReport"->{val r=args[0] as PendingReportEntity;reports.putIfAbsent(r.eventId,r);Unit}
            "acknowledgeReport"->{
                val id=args[0] as String;val owner=args[1] as Long
                if(reports[id]?.ownerUserId==owner)reports.remove(id)
                Unit
            }
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
            "deleteSessionsForOwner"->{
                val owner=args[0] as Long
                sessions.entries.removeIf{it.value.ownerUserId==owner};Unit
            }
            "pausedSession"->{
                val owner=args[0] as Long
                sessions.values.filter{it.active && it.ownerUserId==owner}.maxByOrNull{it.updatedAt}
            }
            "pruneSessionsOlderThan"->{
                val cutoff=args[0] as Long
                sessions.entries.removeIf{it.value.updatedAt<cutoff};Unit
            }
            "observeMetadata"->{
                val k=args[0] as String
                metadataFlows.computeIfAbsent(k){kotlinx.coroutines.flow.MutableStateFlow(metadata[k])}
            }
            else->error("Unexpected durable operation $method")
        }}
        fun repository(
            ownerUserId:Long=123L,
            auth:br.com.concursoprep.app.data.AuthStore?=null,
            submit:(SubmitBatchPayload)->SubmitBatchResponseDto={throw IOException("Synthetic outage")},
            attachEvidence:(AttachAttemptEvidencePayload)->AttachAttemptEvidenceResponse={AttachAttemptEvidenceResponse("applied",it.attemptId,it.selectedEvidenceId)},
            sendReport:()->Unit={},
            sendReportPayload:(String,ReportProblemPayload)->Unit={_,_->sendReport()},
            updateExam:(ExamBlueprintPayload)->ExamBlueprintPayload={throw IOException("Synthetic outage")},
            generate:(QuizGeneratePayload)->List<QuestionDto> = {throw IOException("Synthetic outage")},
            aiTutor:(String,String,Int?)->AiAssistResponseDto={_,_,_->throw IOException("Synthetic outage")},
            aiTutorWithContext:(String,String,AiAssistRequestPayload)->AiAssistResponseDto={_,_,_->throw IOException("Synthetic offline contextual AI")},
            lookupContext:(String,ContextualLookupRequestDto)->ContextualLookupResponseDto={_,_->throw IOException("Synthetic offline lookup")},
            saveOnboarding:(OnboardingPayload)->UserProfileDto={UserProfileDto(it.userId,displayName=it.displayName,dailyQuestionsTarget=it.dailyQuestionsTarget,dailyStudyHoursTarget=it.dailyStudyHoursTarget,activeContestIds=it.activeContestIds)},
            guestSession:(NativeSessionPayload)->AuthSessionDto={throw IOException("Synthetic offline guest session")},
            deleteAccount:(DeleteAccountRequest)->retrofit2.Response<Unit> = {retrofit2.Response.success(Unit)},
            listMissions:()->JsonObject={throw IOException("Synthetic offline missions")},
            getMissionDetail:(String,Boolean)->JsonObject={_,_->throw IOException("Synthetic offline mission detail")},
            updateMissionProgress:(String,JsonObject)->JsonObject={_,_->throw IOException("Synthetic offline mission progress")},
            submitMissionDecision:(String,JsonObject)->JsonObject={_,_->throw IOException("Synthetic offline mission decision")},
            repeatMission:(String)->JsonObject={_->throw IOException("Synthetic offline mission repeat")},
            getMissionPracticeQuestions:(String,JsonObject)->JsonObject={_,_->throw IOException("Synthetic offline mission practice")},
            importCapabilities:()->JsonObject={buildJsonObject{put("ai_available",true)}},
            previewImport:(JsonObject)->JsonObject={throw IOException("Synthetic offline preview")},
            commitImport:(JsonObject)->JsonObject={throw IOException("Synthetic offline commit")},
            assistImport:(JsonObject)->JsonObject={throw IOException("Synthetic offline assist")}
        ):ConcursoRepository {
            val api=fake<ConcursoApiService>{method,args->when(method){
                "submitAttemptsBatch"->submit(args[0] as SubmitBatchPayload)
                "attachAttemptEvidence"->attachEvidence(args[0] as AttachAttemptEvidencePayload)
                "listExams"->remoteExams
                "updateExam"->updateExam(args[1] as ExamBlueprintPayload)
                "catalog"->Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/exam-templates.json")).bufferedReader().readText()).jsonObject
                "knowledgeCatalog"->Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/knowledge-catalog.json")).bufferedReader().readText()).jsonObject
                "cacheState"->CacheStateResponse(1,(args[0] as CacheStatePayload).questionIds.map { CacheStateItem(it,questions[it]?.let { q->QuestionCacheMapping.toDto(q,json) }) })
                "learningState"->LearningPage(emptyList())
                "reportQuestionProblem"->{sendReportPayload(args[0] as String,args[1] as ReportProblemPayload);Unit}
                "generateQuizQuestions"->generate(args[0] as QuizGeneratePayload)
                "requestAiTutor"->aiTutor(args[0] as String,args[1] as String,args[2] as Int?)
                "requestAiTutorWithContext"->aiTutorWithContext(args[0] as String,args[1] as String,args[2] as AiAssistRequestPayload)
                "lookupQuestionContext"->lookupContext(args[0] as String,args[1] as ContextualLookupRequestDto)
                "saveOnboardingPreferences"->saveOnboarding(args[0] as OnboardingPayload)
                "guestSession"->guestSession(args[0] as NativeSessionPayload)
                "profile"->UserProfileDto(if(ownerUserId>0L)ownerUserId else 0L,displayName="Estudante",isGuest=true)
                "getApkInfo"->{throw IOException("Synthetic offline APK check")}
                "getNextAction"->{throw IOException("Synthetic offline next-action")}
                "deleteAccount"->deleteAccount(args[0] as DeleteAccountRequest)
                "listMissions"->listMissions()
                "getMissionDetail"->getMissionDetail(args[0] as String,args[1] as Boolean)
                "updateMissionProgress"->updateMissionProgress(args[0] as String,args[1] as JsonObject)
                "submitMissionDecision"->submitMissionDecision(args[0] as String,args[1] as JsonObject)
                "repeatMission"->repeatMission(args[0] as String)
                "getMissionPracticeQuestions"->getMissionPracticeQuestions(args[0] as String,args[1] as JsonObject)
                "questionImportCapabilities"->importCapabilities()
                "previewQuestionImport"->previewImport(args[0] as JsonObject)
                "commitQuestionImport"->commitImport(args[0] as JsonObject)
                "assistQuestionImport"->assistImport(args[0] as JsonObject)
                else->error("Unexpected API operation $method")
            }}
            return ConcursoRepository(api,dao,exams,json,durable=durable,ownerUserId=ownerUserId,auth=auth)
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
        for(kind in listOf("contextual-hint","child-friendly-pre","dr-sincero"))assertFalse(repo.requestAiTutor(q.id,kind).contains("ANSWER_REFERENCE_DO_NOT_REVEAL"))
        for(kind in listOf("explanation","extra-concepts","child-friendly-post"))assertTrue(repo.requestAiTutor(q.id,kind).contains("ANSWER_REFERENCE_DO_NOT_REVEAL"))
        val backendReason="Erro ao consultar provedor de IA: xAI indisponível (HTTP 403): limite de créditos atingido"
        val body="""{"error":true,"message":"$backendReason"}"""
        val httpRepo=store.repository(aiTutor={_,_,_->throw retrofit2.HttpException(retrofit2.Response.error<AiAssistResponseDto>(502,body.toResponseBody("application/json".toMediaType())))})
        for(kind in listOf("contextual-hint","child-friendly-pre","dr-sincero")) {
            val preError=httpRepo.requestAiTutor(q.id,kind)
            assertTrue(preError.contains(backendReason))
            assertFalse(preError.contains("ANSWER_REFERENCE_DO_NOT_REVEAL"))
        }
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
            for(kind in listOf("contextual-hint","child-friendly-pre","dr-sincero")) {
                val message=repo.requestAiTutor(q.id,kind);assertTrue(message.contains(reason));assertFalse(message.contains(q.explanation!!));assertFalse(message.contains("quando houver conexão"))
            }
        }
        val noCached=question().copy(id="uncached")
        assertTrue(store.repository(aiTutor={_,_,_->throw IOException()}).requestAiTutor(noCached.id,"explanation").contains("conexão"))
        val processing=store.repository(aiTutor={_,_,_->throw IllegalStateException()})
        assertTrue(processing.requestAiTutor(noCached.id,"explanation").contains("processar"));assertFalse(processing.requestAiTutor(noCached.id,"explanation").contains("conexão"))
        for(kind in listOf("contextual-hint","child-friendly-pre","dr-sincero"))assertFalse(processing.requestAiTutor(q.id,kind).contains(q.explanation!!))
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

    @Test fun holdoutPartitionCrossBoardSimuladoFallbackAndBlankOmitWorkOffline() = runBlocking {
        val store = Store()
        val template = ExamCatalog.systemExams().find { it.examId == "CPNU" }!!
        val finComponent = template.subjects.first { it.canonicalSubjectId == "FIN_PUB" }.copy(
            questionCount = 2,
            topics = listOf(StoredTopic("t1", "FIN_PUB_01", "FIN_PUB", "Orçamento Público", "OFICIAL_CONFIRMADO"))
        )
        store.putExam(template.copy(examBoards = listOf("Cebraspe"), subjects = listOf(finComponent), isActiveInLadder = true))

        // Populate 10 questions on FIN_PUB_01 from FGV (different board than Cebraspe!) so Cross-Board Fallback is exercised
        val generatedIds = (1..10).map { idx ->
            val q = question("%024x".format(100 + idx)).copy(
                source = "FGV",
                canonicalSubjectId = "FIN_PUB",
                canonicalTopicId = "FIN_PUB_01",
                syllabusTopicIds = listOf("FIN_PUB_01")
            )
            store.questions[q.id] = QuestionCacheMapping.toEntity(q, json)
            q.id
        }
        val repo = store.repository()

        // 1. Cross-Board Fallback fills Simulado quota instead of throwing insufficient cache error
        val simQuestions = repo.fetchOrGetOfflineQuiz(123, QuizMode.SIMULADO, 2, contestId = "CPNU")
        assertEquals(2, simQuestions.size)
        assertTrue("Borrowed cross-board questions should be marked analogous", simQuestions.all { it.isBorrowedAnalogous })

        // 2. ADAPTIVE_LOOP excludes Holdout 20% questions when pool >= 5
        val holdoutIds = generatedIds.filter { StrategyEngine.isHoldoutReserved(123, it, generatedIds.size) }.toSet()
        val adaptiveQuestions = repo.fetchOrGetOfflineQuiz(123, QuizMode.ADAPTIVE_LOOP, 4, contestId = "CPNU")
        assertEquals(4, adaptiveQuestions.size)
        assertTrue(adaptiveQuestions.none { it.id in holdoutIds })

        // 3. Strategic omit (isBlank = true) does not increment non-blank attempts in LearningProjection
        val qBlank = QuestionCacheMapping.toDto(store.questions.getValue(generatedIds.first()), json)
        val blankAttempt = PendingAttemptEntity(
            localId = 99,
            questionId = qBlank.id,
            chosenIndex = -1,
            chosenText = "",
            isCorrect = false,
            timeSpentSeconds = 12,
            confidenceLevel = 0,
            sessionUuid = "sess-omit",
            quizMode = "STRATEGY_BATTERY",
            examBlock = null,
            contestId = "CPNU",
            userNote = null,
            ownerUserId = 123L,
            attemptId = UUID.randomUUID().toString(),
            questionRevision = qBlank.questionRevision,
            isBlank = true,
            confidenceExplicit = true,
            positionInSession = 1,
            elapsedSessionSeconds = 12
        )
        repo.recordAttemptOfflineFirst(123L, blankAttempt)
        assertEquals(0, repo.getProgressAnalytics(123L).totalQuestionsPracticed)
        assertEquals(FatigueStatus.INSUFFICIENT_EVIDENCE, repo.getProgressAnalytics(123L).fatigueReport.status)
    }

    @Test fun postAnswerEvidenceSelectionSurvivesPriorBatchSyncAndFlushesPatch() = runBlocking {
        val store = Store()
        val q = question("000000000000000000000088").copy(
            editorialCard = EditorialCardDto(
                legalProvisions = listOf("Lei nº 9.784/1999 — Art. 53"),
                testedRule = "A Administração deve anular atos eivados de vício de legalidade.",
                decisiveStemExcerpt = "eivados de vício de legalidade",
                plausibleConceptualErrors = listOf(
                    ConceptualErrorItemDto("ERR_REVOG", "Confusão entre revogação e anulação", "Revogação recai apenas sobre ato válido por mérito.")
                )
            )
        )
        store.questions[q.id] = QuestionCacheMapping.toEntity(q, json)
        val sessionUuid = "sess-evidence-race"
        val detId = ConcursoRepository.deterministicAttemptId(sessionUuid, q.id)
        val pendingAttempt = PendingAttemptEntity(
            localId = 1,
            questionId = q.id,
            chosenIndex = 1,
            chosenText = "B",
            isCorrect = true,
            timeSpentSeconds = 18,
            confidenceLevel = 3,
            sessionUuid = sessionUuid,
            quizMode = "ENTRY_60S",
            examBlock = null,
            contestId = "CPNU",
            userNote = null,
            ownerUserId = 123L,
            attemptId = detId,
            questionRevision = q.questionRevision,
            confidenceExplicit = true
        )
        store.pending[detId] = pendingAttempt

        val attachedPayloads = mutableListOf<AttachAttemptEvidencePayload>()
        val repo = store.repository(
            submit = { batch -> SubmitBatchResponseDto(batch.attempts.size, batch.attempts.map { ack(pendingAttempt) }) },
            attachEvidence = { p ->
                attachedPayloads += p
                AttachAttemptEvidenceResponse("applied", p.attemptId, p.selectedEvidenceId)
            }
        )

        // 1. Background sync finishes BEFORE student taps optional post-answer evidence card
        repo.syncPendingAttempts(123L)
        assertTrue("Pending attempt was already drained", store.pending.isEmpty())

        // 2. Student taps wrong distractor evidence on a hit -> local LearningRecord updates immediately (ILLUSORY_COMPETENCE) + patch queued
        repo.recordEvidenceSelection(123L, sessionUuid, q.id, "ev_err_1")
        val analyticsBeforePatchSync = repo.getProgressAnalytics(123L)
        assertEquals(1, analyticsBeforePatchSync.threeSignalSummary.illusoryCompetenceHits)

        // 3. Next sync flushes `POST /api/v1/quiz/attempt-evidence` idempotently and clears queue
        val syncedPatches = repo.syncPendingAttempts(123L)
        assertEquals(1, syncedPatches)
        assertEquals(1, attachedPayloads.size)
        assertEquals(detId, attachedPayloads.single().attemptId)
        assertEquals("ev_err_1", attachedPayloads.single().selectedEvidenceId)
        assertEquals("", store.metadata["pending_evidence_patches"].orEmpty())
    }

    @Test fun entry60sProtocolConsciousRestAndOfflineContextualLookupWorkCleanly() = runBlocking {
        val store = Store()
        val q = question("000000000000000000000099").copy(
            question = "A Administração deve anular seus próprios atos eivados de vício de legalidade (Art. 53 da Lei 9.784/1999).",
            questionType = QuestionType.TRUE_FALSE,
            answers = listOf("Certo", "Errado"),
            correctAnswerIndices = listOf(0),
            disciplina = "Direito Administrativo",
            source = "Cebraspe",
            canonicalSubjectId = "DIR_ADM",
            canonicalTopicId = "DIR_ADM_01_001"
        )
        store.questions[q.id] = QuestionCacheMapping.toEntity(q, json)
        val repo = store.repository()

        // 1. Conscious rest ("Sem estudar hoje") is reversible and recorded without guilt
        repo.recordEntryScreenImpression(123L)
        val restMetrics = repo.setConsciousRestToday(123L, rested = true)
        assertTrue(restMetrics.restedToday)
        assertEquals(1, restMetrics.consciousRestDaysCount)

        // 2. Voluntary start clears restedToday automatically
        repo.recordEntryVoluntaryStart(123L)
        repo.recordEntryFirstQuestionCompleted(123L)
        val dash = repo.getTodayDashboard(123L)
        assertFalse(dash.entryMetrics.restedToday)
        assertEquals(1, dash.entryMetrics.voluntaryStartsCount)
        assertEquals(1, dash.entryMetrics.firstQuestionCompletedCount)

        // 3. Offline ENTRY_60S returns the short familiar question
        val entryQuiz = repo.fetchOrGetOfflineQuiz(123L, QuizMode.ENTRY_60S, 1)
        assertEquals(listOf(q.id), entryQuiz.map { it.id })

        // 4. Offline contextual lookup matches bundled normative source Art. 53 Lei 9.784/1999 + builds Google layer URL
        val lookup = repo.lookupQuestionContext(q, "Art. 53")
        assertTrue(lookup.googleSearchUrl.startsWith("https://www.google.com/search?hl=pt-BR&q="))
        assertTrue(lookup.normativeMatches.any { it.sourceId == "LEI_9784_ART_53" })
    }

    @Test fun negativeOwnerUserIdSendsApiUserIdZeroWhilePreservingNegativeLocalOwner() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val aesKey = TestAuthFixtures.newAes256Key()
        val auth = br.com.concursoprep.app.data.AuthStore(prefs, keyProvider = { aesKey })
        val negOwner = auth.localUserId
        assertTrue(negOwner < 0L)
        auth.select(negOwner)

        val store = Store()
        val q = question("0000000000000000000000a1")
        val a = attempt(q = q, owner = negOwner)
        store.add(a)

        var submitUserId: Long? = null
        var quizUserId: Long? = null
        var onboardingUserId: Long? = null
        var reportUserId: Long? = null
        val jwt = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L + 3600L, sub = 5501L)

        val repo = store.repository(
            ownerUserId = negOwner,
            auth = auth,
            guestSession = {
                AuthSessionDto(
                    token = jwt,
                    profile = UserProfileDto(userId = 5501L, displayName = "Convidado", isGuest = true)
                )
            },
            submit = { batch ->
                submitUserId = batch.userId
                SubmitBatchResponseDto(1, listOf(ack(a)))
            },
            generate = { req ->
                quizUserId = req.userId
                listOf(q)
            },
            saveOnboarding = { payload ->
                onboardingUserId = payload.userId
                UserProfileDto(userId = 0L, displayName = payload.displayName, activeContestIds = payload.activeContestIds)
            },
            sendReportPayload = { _, payload ->
                reportUserId = payload.userId
            }
        )

        // Room stores negative owner locally before network sync
        assertEquals(negOwner, store.pending.getValue(a.attemptId).ownerUserId)
        val savedOnboarding = repo.saveOnboarding(negOwner, listOf("CPNU"), 30, 2.0, "Offline Guest")
        assertEquals(0L, onboardingUserId)
        assertEquals(negOwner, savedOnboarding.userId)

        val generated = repo.fetchOrGetOfflineQuiz(negOwner, QuizMode.SHUFFLE, 5)
        assertEquals(0L, quizUserId)
        assertEquals(1, generated.size)

        assertTrue(repo.reportQuestionProblem(negOwner, q.id, VALID_PROBLEM_CATEGORIES.first()))
        assertEquals(negOwner, store.reports.values.single().ownerUserId)

        val session = SessionSnapshot("sess-neg", negOwner, listOf(q), QuizMode.STANDARD, null)
        repo.createSession(session)
        assertEquals(negOwner, store.sessions.getValue("sess-neg").ownerUserId)

        repo.syncPendingAttempts(negOwner)
        assertEquals(0L, submitUserId)
        assertEquals(0L, reportUserId)
        assertEquals(jwt, auth.token(negOwner))
    }

    @Test fun lgpdDeleteAccountWipesAllPersonalTablesExamsSessionsAndMetadataCompletely() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val aesKey = TestAuthFixtures.newAes256Key()
        val auth = br.com.concursoprep.app.data.AuthStore(prefs, keyProvider = { aesKey })
        val owner = 123L
        auth.select(owner)
        auth.accept(
            AuthSessionDto(
                token = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L + 3600L, sub = owner),
                profile = UserProfileDto(userId = owner, email = "lgpd@cogniva.app", isGuest = false)
            )
        )

        val store = Store()
        val q = question("0000000000000000000000b1")
        store.add(attempt(q = q, owner = owner))
        store.learning[q.id] = LearningRecordEntity(
            q.id,
            1L,
            json.encodeToString(
                LearningRecord(q.id, q.disciplina, attempts = 1, correct = 1, accuracyPct = 100.0, isMastered = false, inErrorNotebook = false, highConfidenceError = false, points = 0.2, nextReviewAt = "2027-01-01T00:00:00Z")
            )
        )
        store.metadata["pending_evidence_patches"] = """[{"ownerUserId":123,"attemptId":"a1","questionId":"${q.id}","selectedEvidenceId":"ev_decisive"}]"""
        store.metadata["missions_state_v1"] = """{"ownerUserId":123,"missions":{"bcb-ef-t1-m01-choque-dos-juros":{"missionId":"bcb-ef-t1-m01-choque-dos-juros","dirty":true}}}"""
        store.metadata["pending_profile"] = """{"user_id":123}"""
        store.reports["rep-1"] = PendingReportEntity("rep-1", owner, q.id, VALID_PROBLEM_CATEGORIES.first())
        store.sessions["sess-active"] = QuizSessionEntity("sess-active", owner, "{}", 1L, active = true)
        store.sessions["sess-done"] = QuizSessionEntity("sess-done", owner, "{}", 2L, active = false)
        store.putExam(ExamCatalog.systemExams().first())
        store.masteryRows["FIN_PUB_01"] = CanonicalMasteryEntity("FIN_PUB_01", "FIN_PUB", 0.9, true)
        store.trunkRows["fiscal"] = CareerTrunkEntity("fiscal", "Fiscal", "icon", true)

        val repo = store.repository(ownerUserId = owner, auth = auth)
        val nextGuestId = repo.deleteAccount("EXCLUIR", "Exclusão concluída.")

        assertTrue(nextGuestId < 0L)
        assertTrue(store.pending.isEmpty())
        assertTrue(store.learning.isEmpty())
        assertTrue(store.metadata.isEmpty())
        assertTrue(store.questions.isEmpty())
        assertTrue(store.reports.isEmpty())
        assertTrue(store.sessions.isEmpty())
        assertTrue(store.examRows.isEmpty())
        assertTrue(store.subjectRows.isEmpty())
        assertTrue(store.topicRows.isEmpty())
        assertTrue(store.masteryRows.isEmpty())
        assertTrue(store.trunkRows.isEmpty())
        assertEquals(0, store.exams.countExams())
        assertNull(auth.token(owner))
    }

    @Test fun strategicOmissionIsCountedExactlyOnceOfflineAndAfterSync() = runBlocking {
        val store = Store()
        val exam = ExamCatalog.systemExams().first().copy(
            examId = "CPNU",
            wrongAnswerPenalty = 1.0,
            isActiveInLadder = true
        )
        store.putExam(exam)
        val qCached = question("0000000000000000000000c1").copy(
            questionType = QuestionType.TRUE_FALSE,
            answers = listOf("Certo", "Errado"),
            correctAnswerIndices = listOf(0)
        )
        store.questions[qCached.id] = QuestionCacheMapping.toEntity(qCached, json)
        val repo = store.repository()

        val blank1 = PendingAttemptEntity(
            localId = 1,
            questionId = qCached.id,
            chosenIndex = -1,
            chosenText = "",
            isCorrect = false,
            timeSpentSeconds = 9,
            confidenceLevel = 0,
            sessionUuid = "sess-blank-1",
            quizMode = "STRATEGY_BATTERY",
            examBlock = null,
            contestId = "CPNU",
            userNote = null,
            ownerUserId = 123L,
            attemptId = "blank-att-1",
            questionRevision = qCached.questionRevision,
            isBlank = true,
            confidenceExplicit = true
        )
        repo.recordAttemptOfflineFirst(123L, blank1)

        // Regression guard: must be 1, NEVER 2!
        assertEquals(1, repo.getTodayDashboard(123L).nextAction!!.threeSignalSummary.strategicOmissions)
        assertEquals(1, repo.getProgressAnalytics(123L).threeSignalSummary.strategicOmissions)

        // Uncached question blank attempt also counts once
        val blankUncached = blank1.copy(
            localId = 2,
            questionId = "0000000000000000000000c2",
            sessionUuid = "sess-blank-2",
            attemptId = "blank-att-2"
        )
        store.pending[blankUncached.attemptId] = blankUncached
        assertEquals(2, repo.getProgressAnalytics(123L).threeSignalSummary.strategicOmissions)
    }

    @Test fun pendingEvidencePatchesHandlePendingParentRetry4xxDiscardAndMultiAccountIsolation() = runBlocking {
        val store = Store()
        val q = question("0000000000000000000000d1")
        store.questions[q.id] = QuestionCacheMapping.toEntity(q, json)

        // Multi-account DAO isolation on updateAttemptEvidence when sessionUuid + questionId collide
        val att123 = attempt(q, owner = 123L, id = "att-123").copy(sessionUuid = "shared-sess")
        val att999 = attempt(q, owner = 999L, id = "att-999").copy(sessionUuid = "shared-sess")
        store.pending[att123.attemptId] = att123
        store.pending[att999.attemptId] = att999

        val baseRepo = store.repository(ownerUserId = 123L)
        baseRepo.recordEvidenceSelection(123L, "shared-sess", q.id, "ev_decisive")
        assertEquals("ev_decisive", store.pending.getValue("att-123").selectedEvidenceId)
        assertNull(store.pending.getValue("att-999").selectedEvidenceId)

        // Drain attempts, seed patch for 123L AND foreign patch for 999L
        store.pending.clear()
        store.metadata["pending_evidence_patches"] = """[
            {"ownerUserId":123,"attemptId":"att-123","questionId":"${q.id}","selectedEvidenceId":"ev_decisive"},
            {"ownerUserId":999,"attemptId":"att-999","questionId":"${q.id}","selectedEvidenceId":"ev_err_1"}
        ]"""

        // getLocalStorageStats counts ONLY owner 123L's patch (1), ignoring 999L
        assertEquals(1, baseRepo.getLocalStorageStats().second)

        val seenAttempts = mutableListOf<String>()

        // 1. Server returns "pending_parent_attempt" even though pending_attempts was just drained -> MUST retain patch!
        val repoPendingParent = store.repository(
            ownerUserId = 123L,
            attachEvidence = { p ->
                seenAttempts += p.attemptId
                AttachAttemptEvidenceResponse("pending_parent_attempt", p.attemptId, p.selectedEvidenceId)
            }
        )
        assertTrue(runCatching { repoPendingParent.syncPendingAttempts(123L) }.isFailure)
        assertEquals(listOf("att-123"), seenAttempts)
        assertEquals(1, repoPendingParent.getLocalStorageStats().second)

        // 2. Transient 500 and IOException retain patch for retry
        val repo500 = store.repository(
            ownerUserId = 123L,
            attachEvidence = {
                throw retrofit2.HttpException(retrofit2.Response.error<AttachAttemptEvidenceResponse>(500, "{}".toResponseBody("application/json".toMediaType())))
            }
        )
        assertTrue(runCatching { repo500.syncPendingAttempts(123L) }.isFailure)
        assertEquals(1, repo500.getLocalStorageStats().second)

        val repoIo = store.repository(
            ownerUserId = 123L,
            attachEvidence = { throw IOException("timeout") }
        )
        assertTrue(runCatching { repoIo.syncPendingAttempts(123L) }.isFailure)
        assertEquals(1, repoIo.getLocalStorageStats().second)

        // 3. Terminal 404 discards 123L's patch cleanly while preserving 999L's patch untouched
        val repo404 = store.repository(
            ownerUserId = 123L,
            attachEvidence = {
                throw retrofit2.HttpException(retrofit2.Response.error<AttachAttemptEvidenceResponse>(404, "{}".toResponseBody("application/json".toMediaType())))
            }
        )
        repo404.syncPendingAttempts(123L)
        assertEquals(0, repo404.getLocalStorageStats().second)
        assertTrue(store.metadata["pending_evidence_patches"].orEmpty().contains("\"ownerUserId\":999"))
        assertFalse(store.metadata["pending_evidence_patches"].orEmpty().contains("\"ownerUserId\":123"))
    }

    @Test fun termLookupAndPreAnswerTutorNeverLeakExplanationWhilePostAnswerTermLookupFallsBack() = runBlocking {
        val store = Store()
        val secretExplanation = "GABARITO_OFICIAL_SECRETO_ART_37"
        val q = question("0000000000000000000000e1").copy(explanation = secretExplanation)
        store.questions[q.id] = QuestionCacheMapping.toEntity(q, json)

        // Blank term validation without network call
        assertEquals(
            "Selecione um termo ou trecho do enunciado para consultar.",
            store.repository().requestAiTermLookup(q.id, "   ", isAnswerSubmitted = false)
        )

        // 1. Offline IOException: pre-answer "term-lookup" NEVER leaks explanation; post-answer "term-lookup-post" DOES fall back
        val offlineRepo = store.repository()
        assertFalse(offlineRepo.requestAiTutor(q.id, "term-lookup").contains(secretExplanation))
        assertFalse(offlineRepo.requestAiTermLookup(q.id, "Art. 37", isAnswerSubmitted = false).contains(secretExplanation))
        assertTrue(offlineRepo.requestAiTermLookup(q.id, "Art. 37", isAnswerSubmitted = true).contains(secretExplanation))

        // 2. HTTP 502 error: pre-answer NEVER leaks explanation; post-answer falls back with backend message
        val http502Repo = store.repository(
            aiTutor = { _, _, _ ->
                throw retrofit2.HttpException(retrofit2.Response.error<AiAssistResponseDto>(502, """{"message":"IA fora do ar"}""".toResponseBody("application/json".toMediaType())))
            },
            aiTutorWithContext = { _, _, _ ->
                throw retrofit2.HttpException(retrofit2.Response.error<AiAssistResponseDto>(502, """{"message":"IA fora do ar"}""".toResponseBody("application/json".toMediaType())))
            }
        )
        val preTutor502 = http502Repo.requestAiTutor(q.id, "term-lookup")
        assertTrue(preTutor502.contains("IA fora do ar"))
        assertFalse(preTutor502.contains(secretExplanation))

        val preLookup502 = http502Repo.requestAiTermLookup(q.id, "Art. 37", isAnswerSubmitted = false)
        assertTrue(preLookup502.contains("IA fora do ar"))
        assertFalse(preLookup502.contains(secretExplanation))

        val postLookup502 = http502Repo.requestAiTermLookup(q.id, "Art. 37", isAnswerSubmitted = true)
        assertTrue(postLookup502.contains("IA fora do ar"))
        assertTrue(postLookup502.contains(secretExplanation))

        // 3. Online success sends expected kind ("term-lookup" vs "term-lookup-post")
        val seenKinds = mutableListOf<String>()
        val okRepo = store.repository(
            aiTutorWithContext = { _, kind, payload ->
                seenKinds += "$kind:${payload.userNote}"
                AiAssistResponseDto(q.id, kind, "Conceito explicado", fromCache = false)
            }
        )
        assertEquals("Conceito explicado", okRepo.requestAiTermLookup(q.id, "  improbidade  ", isAnswerSubmitted = false))
        assertEquals("Conceito explicado", okRepo.requestAiTermLookup(q.id, "improbidade", isAnswerSubmitted = true))
        assertEquals(listOf("term-lookup:improbidade", "term-lookup-post:improbidade"), seenKinds)
    }

    @Test fun reactiveFlowsCachedQuestionFiltersAndLearningFlagUpdatesMatchDaoContract() = runBlocking {
        val store = Store()
        val qFin = question("0000000000000000000000f1").copy(disciplina = "Finanças Públicas")
        val qAdm = question("0000000000000000000000f2").copy(disciplina = "Direito Administrativo")
        store.questions[qFin.id] = QuestionCacheMapping.toEntity(qFin, json)
        store.questions[qAdm.id] = QuestionCacheMapping.toEntity(qAdm, json)

        assertEquals(1, store.dao.getCachedQuestions("Direito Administrativo", 10).size)
        assertEquals(1, store.dao.getCachedQuestions(null, 1).size)

        val a = attempt(qFin, owner = 123L).copy(isCorrect = false)
        store.pending[a.attemptId] = a
        val repo = store.repository(
            submit = {
                SubmitBatchResponseDto(
                    1,
                    listOf(ack(a).copy(isCorrect = false, inErrorNotebook = true, isMastered = false, correctTotal = 0, accuracyAccumulatedPct = 0.0))
                )
            }
        )
        val observedBefore = repo.observeSync().first()
        assertNotNull(observedBefore)

        repo.syncPendingAttempts(123L)
        assertTrue(store.questions.getValue(qFin.id).inErrorNotebook)
        assertFalse(store.questions.getValue(qFin.id).isMastered)
        val observedAfter = repo.observeSync().first()
        assertNotEquals(observedBefore, observedAfter)
    }

    @Test fun missionsOfflineFirstFlushesOrderedTwoStepDecisionsPositionsAndVerifiesVideoSha256() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val aesKey = TestAuthFixtures.newAes256Key()
        val auth = br.com.concursoprep.app.data.AuthStore(prefs, keyProvider = { aesKey })
        auth.select(123L)
        auth.accept(
            AuthSessionDto(
                token = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L + 3600L, sub = 123L),
                profile = UserProfileDto(userId = 123L, isGuest = true)
            )
        )

        val store = Store()
        val missionId = "bcb-ef-t1-m01-choque-dos-juros"

        // 1. Offline mutations keep dirty = true and count in getLocalStorageStats()
        val offlineRepo = store.repository(ownerUserId = 123L, auth = auth)
        offlineRepo.updateMissionProgressOfflineFirst(
            missionId = missionId,
            currentSceneId = "cena-02-o-problema",
            completedSceneId = "cena-01-o-alerta",
            playbackPositionMs = 8500L
        )
        offlineRepo.updateMissionProgressOfflineFirst(
            missionId = missionId,
            currentSceneId = "cena-03-primeira-decisao",
            completedSceneId = "cena-02-o-problema"
        )
        // Student answers Scene 3 wrong ("A") then right ("B") while offline!
        offlineRepo.submitMissionDecisionOfflineFirst(
            missionId = missionId,
            sceneId = "cena-03-primeira-decisao",
            interactionId = "decisao-01-juros-preco-prefixado",
            selectedOptionId = "A",
            timeSpentMs = 4200L
        )
        offlineRepo.submitMissionDecisionOfflineFirst(
            missionId = missionId,
            sceneId = "cena-03-primeira-decisao",
            interactionId = "decisao-01-juros-preco-prefixado",
            selectedOptionId = "B",
            timeSpentMs = 2100L
        )
        offlineRepo.updateMissionProgressOfflineFirst(
            missionId = missionId,
            currentSceneId = "cena-05-desafio-duration",
            completedSceneId = "cena-04-explicacao",
            playbackPositionMs = 14200L
        )
        offlineRepo.submitMissionDecisionOfflineFirst(
            missionId = missionId,
            sceneId = "cena-05-desafio-duration",
            interactionId = "decisao-02-desafio-duration",
            selectedOptionId = "B",
            timeSpentMs = 3500L
        )

        // Force reverse map insertion order (Scene 5 before Scene 3) in stored JSON to prove flushDirtyMissionRecord sorts by scene order!
        val rawEnv = json.decodeFromString<br.com.concursoprep.app.domain.missions.LocalMissionsStateEnvelope>(
            store.metadata.getValue("missions_state_v1")
        )
        val rec = rawEnv.missions.getValue(missionId)
        assertTrue(rec.dirty)
        val reversedDecisions = linkedMapOf(
            "decisao-02-desafio-duration" to rec.decisions.getValue("decisao-02-desafio-duration"),
            "decisao-01-juros-preco-prefixado" to rec.decisions.getValue("decisao-01-juros-preco-prefixado")
        )
        store.metadata["missions_state_v1"] = json.encodeToString(
            rawEnv.copy(missions = mapOf(missionId to rec.copy(decisions = reversedDecisions)))
        )
        assertEquals(1, offlineRepo.getLocalStorageStats().second)

        // 2. Online flush enforces server state machine strictly (never 409 DECISION_REQUIRED_BEFORE_ADVANCE)
        val callLog = mutableListOf<String>()
        var scene3AnsweredOnServer = false
        var scene3FirstWasWrong = false
        val onlineRepo = store.repository(
            ownerUserId = 123L,
            auth = auth,
            submitMissionDecision = { mid, body ->
                assertEquals(missionId, mid)
                val sid = body.getValue("scene_id").jsonPrimitive.content
                val opt = body.getValue("selected_option_id").jsonPrimitive.content
                if (sid == "cena-05-desafio-duration") {
                    assertTrue("Scene 3 MUST be submitted before Scene 5!", scene3AnsweredOnServer)
                }
                if (sid == "cena-03-primeira-decisao") {
                    if (!scene3AnsweredOnServer) {
                        scene3FirstWasWrong = (opt == "A")
                        scene3AnsweredOnServer = true
                    }
                }
                callLog += "DECISION:$sid:$opt"
                buildJsonObject {}
            },
            updateMissionProgress = { mid, body ->
                assertEquals(missionId, mid)
                val done = body["completed_scene_id"]?.jsonPrimitive?.contentOrNull
                val cur = body["current_scene_id"]?.jsonPrimitive?.contentOrNull
                val pos = body["playback_position_ms"]?.jsonPrimitive?.longOrNull
                val comp = body["mark_mission_completed"]?.jsonPrimitive?.booleanOrNull == true
                callLog += "PROGRESS:done=$done,cur=$cur,pos=$pos,completed=$comp"
                buildJsonObject {}
            },
            listMissions = { buildJsonObject { put("careers", buildJsonArray {}) } }
        )

        onlineRepo.loadMissionsCatalog()
        assertTrue("First wrong attempt 'A' must be sent before 'B' to preserve firstAttemptCorrect=false", scene3FirstWasWrong)

        val idxScene3First = callLog.indexOf("DECISION:cena-03-primeira-decisao:A")
        val idxScene3Second = callLog.indexOf("DECISION:cena-03-primeira-decisao:B")
        val idxScene4Done = callLog.indexOfFirst { it.startsWith("PROGRESS:done=cena-04-explicacao") }
        val idxScene5Dec = callLog.indexOf("DECISION:cena-05-desafio-duration:B")
        val idxScene2Pos = callLog.indexOfFirst { it.contains("cur=cena-02-o-problema,pos=8500") }
        val idxFinal = callLog.lastIndex

        assertTrue(idxScene3First >= 0 && idxScene3First < idxScene3Second)
        assertTrue("Prior scenes completedSceneIds must precede terminal decision", idxScene4Done in (idxScene3Second + 1) until idxScene5Dec)
        assertTrue("Intermediate scene position must precede final current_scene_id", idxScene2Pos in 0 until idxFinal)
        assertEquals(
            "PROGRESS:done=null,cur=cena-05-desafio-duration,pos=14200,completed=true",
            callLog[idxFinal]
        )
        assertEquals(0, onlineRepo.getLocalStorageStats().second)

        // 3. Video cache (.sha256 sidecar hit, mismatch eviction, truncated rejection, path traversal confinement, live download)
        val tempRoot = java.nio.file.Files.createTempDirectory("cogniva-mission-video").toFile()
        try {
            // Pure traversal characters become empty -> null
            assertNull(onlineRepo.ensureMissionVideoCached(tempRoot, "../..", "a".repeat(64)))

            val validBytes = ByteArray(2048) { idx -> (idx and 0xFF).toByte() }
            val validSha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(validBytes)
                .joinToString("") { "%02x".format(it) }

            val missionsDir = java.io.File(tempRoot, "missions").apply { mkdirs() }
            val cachedMp4 = java.io.File(missionsDir, "cena01.mp4").apply { writeBytes(validBytes) }
            val cachedSha = java.io.File(missionsDir, "cena01.sha256").apply { writeText(validSha) }

            // Cache hit returns existing file with zero network calls (even on repo without auth!)
            val noAuthRepo = store.repository()
            val hit = noAuthRepo.ensureMissionVideoCached(tempRoot, "cena01", validSha)
            assertEquals(cachedMp4.canonicalPath, hit?.canonicalPath)

            // Mismatched expected SHA-256 deletes stale .mp4 and .sha256 and refuses to serve it
            val miss = noAuthRepo.ensureMissionVideoCached(tempRoot, "cena01", "f".repeat(64))
            assertNull(miss)
            assertFalse(cachedMp4.exists())
            assertFalse(cachedSha.exists())

            // Truncated file (<= 1024 bytes) is never served as cache hit even if .sha256 matches
            val tinyBytes = ByteArray(512) { 0x42 }
            val tinySha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(tinyBytes)
                .joinToString("") { "%02x".format(it) }
            java.io.File(missionsDir, "tiny.mp4").writeBytes(tinyBytes)
            java.io.File(missionsDir, "tiny.sha256").writeText(tinySha)
            assertNull(noAuthRepo.ensureMissionVideoCached(tempRoot, "tiny", tinySha))

            // Live HTTP loopback ServerSocket verifies stream SHA-256 and confines traversal assetIds inside missionsDir
            val serverSocket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
            val serverThread = Thread {
                repeat(2) {
                    runCatching {
                        serverSocket.accept().use { sock ->
                            val reader = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val headers = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${validBytes.size}\r\nConnection: close\r\n\r\n"
                            val out = sock.getOutputStream()
                            out.write(headers.toByteArray(Charsets.US_ASCII))
                            out.write(validBytes)
                            out.flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                val localBase = "http://127.0.0.1:${serverSocket.localPort}/api/v1"
                // Wrong expected hash rejects downloaded stream and leaves no file behind
                assertNull(onlineRepo.ensureMissionVideoCached(tempRoot, "live_asset", "0".repeat(64), apiBaseUrl = localBase))
                assertFalse(java.io.File(missionsDir, "live_asset.mp4").exists())

                // Traversal attempt "../../asset_ok" is sanitized to "asset_ok" strictly inside missionsDir
                val downloaded = onlineRepo.ensureMissionVideoCached(tempRoot, "../../asset_ok", validSha, apiBaseUrl = localBase)
                assertNotNull(downloaded)
                val parentDir = checkNotNull(downloaded!!.parentFile)
                assertEquals(missionsDir.canonicalPath, parentDir.canonicalPath)
                assertEquals("asset_ok.mp4", downloaded.name)
                assertEquals(validSha, java.io.File(missionsDir, "asset_ok.sha256").readText().trim())
            } finally {
                runCatching { serverSocket.close() }
                serverThread.join(2_000)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
