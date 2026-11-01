package br.com.concursoprep.app.data.repository

import androidx.room.withTransaction
import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.data.AuthStore
import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.data.remote.*
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

class ConcursoRepository(
    private val api: ConcursoApiService,
    private val dao: ConcursoLocalDao,
    private val examDao: ExamDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val durable: DurableStateDao? = null,
    private val database: AppDatabase? = null,
    val ownerUserId: Long = 0,
    private val auth: AuthStore? = null
) {
    fun observeSync(): kotlinx.coroutines.flow.Flow<String?> = durable?.let { state ->
        kotlinx.coroutines.flow.combine(state.observeMetadata("last_sync"),state.observeMetadata("sync_error"),dao.observePendingAttemptCount(),examDao.observeAllExams()) { stamp,error,count,exams -> "$stamp|$error|$count|${exams.hashCode()}" }
    } ?: kotlinx.coroutines.flow.flowOf(null)
    private val syncMutex = Mutex()
    private val mutationMutex = Mutex()
    private suspend fun <T> transaction(block: suspend () -> T): T = if (database!=null) database.withTransaction { block() } else block()
    private fun owner(userId: Long) { require(ownerUserId == 0L || ownerUserId == userId) { "As pendências pertencem a outra conta e foram preservadas." } }
    private suspend fun metadata(key: String): String? = durable?.metadata(key)
    private suspend fun metadata(key: String, value: String) { durable?.putMetadata(LocalMetadataEntity(key,value)) }

    suspend fun linkTelegramAccountOrLogin(telegramUserId: Long?, telegramUsername: String?, displayName: String?, linkCode: String? = null): AuthSessionDto {
        val identity = auth ?: error("Credencial da instalação indisponível")
        if (!linkCode.isNullOrBlank()) {
            api.nativeSession(NativeSessionPayload(identity.installationId, identity.installationSecret))
            return api.linkTelegramOrLogin(TelegramLinkPayload(telegramUserId,telegramUsername,displayName,linkCode,identity.installationId,identity.installationSecret)).also(identity::accept)
        }
        require(telegramUserId == null && telegramUsername.isNullOrBlank()) { "Use o código de vínculo para acessar uma conta histórica." }
        return try {
            api.nativeSession(NativeSessionPayload(identity.installationId, identity.installationSecret,displayName)).also(identity::accept)
        } catch (e: CancellationException) { throw e } catch (e: java.io.IOException) {
            // This is a unique, unlinked local identity, not a fabricated server session.
            AuthSessionDto("",UserProfileDto(identity.localUserId,displayName=displayName,activeContestIds=listOf("CPNU"),currentDailyStreak=0,streakFreezesAvailable=0),0,false)
        }
    }

    suspend fun saveOnboarding(userId: Long, contestIds: List<String>, dailyQuestionsTarget: Int, dailyHours: Double, displayName: String?): UserProfileDto {
        require(dailyQuestionsTarget in 1..1000 && dailyHours.isFinite() && dailyHours in 0.0..24.0)
        val payload=OnboardingPayload(userId,contestIds,dailyQuestionsTarget,dailyHours,displayName)
        auth?.setPendingProfile(userId,json.encodeToString(payload))
        if (userId == ownerUserId) {
            metadata("pending_profile",json.encodeToString(payload))
            examDao.listExams().forEach { examDao.setActive(it.examId,it.examId in contestIds) }
        }
        return try {
            api.saveOnboardingPreferences(payload).also { auth?.clearPendingProfile(userId,json.encodeToString(payload));if (userId == ownerUserId) metadata("pending_profile", "") }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            UserProfileDto(userId,displayName=displayName,dailyQuestionsTarget=dailyQuestionsTarget,dailyStudyHoursTarget=dailyHours,activeContestIds=contestIds,currentDailyStreak=0,streakFreezesAvailable=0)
        }
    }

    suspend fun ensureExamSeed() {
        mutationMutex.withLock {
            metadata("catalog")?.takeIf { it.isNotBlank() }?.let { ExamCatalog.installCatalog(Json.parseToJsonElement(it).jsonObject) }
            dao.bindLegacyOwner(ownerUserId)
            val trunkIds=examDao.listTrunks().map { it.slug }.toSet()
            val missingTrunks=ExamCatalog.trunks().filter { it.slug !in trunkIds }
            if(missingTrunks.isNotEmpty())examDao.upsertTrunks(missingTrunks.map { CareerTrunkEntity(it.slug,it.name,it.iconKey,false) })
            if (examDao.countExams() == 0) {
                val active=auth?.activeContestIds(ownerUserId)?:setOf("CPNU")
                ExamCatalog.systemExams().forEach { saveExamAggregate(it.copy(isActiveInLadder=it.examId in active)) }
            }
        }
    }
    suspend fun loadStoredExams(): List<StoredExam> {
        ensureExamSeed()
        return examDao.listExams().map { ExamLocalMapping.assemble(it,examDao.subjectsFor(it.examId),examDao.topicsFor(it.examId)) }
    }
    private suspend fun saveExamAggregate(exam: StoredExam) = transaction {
        examDao.upsertExam(ExamLocalMapping.toEntity(exam))
        examDao.clearTopics(exam.examId); examDao.clearSubjects(exam.examId)
        examDao.upsertSubjects(ExamLocalMapping.subjectsOf(exam));examDao.upsertTopics(ExamLocalMapping.topicsOf(exam))
    }
    suspend fun saveExamLocal(exam: StoredExam) = mutationMutex.withLock { saveExamAggregate(exam) }
    suspend fun createExamOffline(draft: ExamDraft): StoredExam = OfflineExamQueue.createPendingExam(draft).also { saveExamLocal(it) }
    suspend fun editExamOffline(exam: StoredExam) {
        mutationMutex.withLock {
            val previous=examDao.listExams().find { it.examId==exam.examId }?.let { ExamLocalMapping.assemble(it,examDao.subjectsFor(it.examId),examDao.topicsFor(it.examId)) }
            val subjects=exam.subjects.toMutableList()
            previous?.subjects?.forEach { old ->
                val index=subjects.indexOfFirst { it.canonicalSubjectId==old.canonicalSubjectId }
                if(index>=0){
                    val current=subjects[index]
                    subjects[index]=current.copy(topics=current.topics+old.topics.filter { t->current.topics.none { it.canonicalTopicId==t.canonicalTopicId } }.map { it.copy(diffStatus="ARQUIVADO") })
                }else subjects+=old.copy(topics=old.topics.map { it.copy(diffStatus="ARQUIVADO") })
            }
            saveExamAggregate(exam.copy(subjects=subjects,syncStatus=if(exam.revision==0L && exam.syncStatus=="PENDING_CREATE") "PENDING_CREATE" else "PENDING_UPDATE",syncError=null))
        }
    }
    suspend fun setTrunkFocus(slug: String, focused: Boolean) { ensureExamSeed();examDao.setTrunkFocus(slug,focused) }
    suspend fun focusedTrunkSlugs(): Set<String> { ensureExamSeed();return examDao.listTrunks().filter { it.isFocusedTrunk }.map { it.slug }.toSet() }

    private suspend fun baseLearning(): List<LearningRecord> = durable?.learning().orEmpty().map { json.decodeFromString<LearningRecord>(it.payloadJson) }
    private fun PendingAttemptEntity.event() = LearningEvent(attemptId,Instant.ofEpochMilli(createdAtEpochMs).toString(),isCorrect,confidenceLevel,timeSpentSeconds)
    suspend fun projectedLearning(): List<LearningRecord> {
        val base=baseLearning().associateBy { it.questionId }.toMutableMap()
        val pending=dao.getAllPendingAttempts().filter { it.ownerUserId==ownerUserId && it.syncError==null }
        pending.groupBy { it.questionId }.forEach { (id,events) ->
            val question=dao.findQuestionById(id)?.let { QuestionCacheMapping.toDto(it,json) }
            if (question!=null) base[id]=LearningProjection.project(base[id],question,events.map { it.event() })
        }
        return base.values.toList()
    }
    suspend fun canonicalMasteryBySubject(): Map<String,Double> = LearningProjection.masteryBySubject(projectedLearning())
    suspend fun getContestSyllabus(contestId: String,userId: Long): List<DisciplineSyllabusGroupDto> {
        owner(userId)
        val exam=loadStoredExams().firstOrNull { it.examId==contestId } ?: return emptyList()
        val rows=projectedLearning();val mastery=LearningProjection.masteryByTopic(rows)
        return exam.subjects.mapNotNull { subject ->
            val visible=subject.topics.filter { it.diffStatus!="ARQUIVADO" };if(visible.isEmpty()) return@mapNotNull null
            val nodes=visible.mapIndexed { index,topic ->
                val related=rows.filter { it.canonicalTopicId==topic.canonicalTopicId }
                val value=(mastery[topic.canonicalTopicId]?:0.0)*100
                SyllabusTopicNodeDto(SyllabusTopicDto(topic.localTopicId,exam.examId,subject.category,subject.displayName,topic.displayTitle,index,
                    estimation=EstimationMetadata(topic.diffStatus=="PRE_EDITAL_BASE",ConfidenceBadge.LOW,"Modelo/manual. Valide o conteúdo no edital e registre a fonte.",emptyList()),
                    diffStatus=when(topic.diffStatus){"OFICIAL_NOVO"->TopicDiffStatus.NEW_IN_EDITAL;"ARQUIVADO"->TopicDiffStatus.REMOVED_ARCHIVED;else->TopicDiffStatus.CONFIRMED}),value,related.sumOf { it.attempts },
                    if(related.isEmpty()) "NAO_INICIADO" else if(related.all { it.isMastered }) "DOMINADO" else if(related.any { Instant.parse(it.nextReviewAt)<=Instant.now() }) "REVISAO" else "QUESTOES")
            }
            DisciplineSyllabusGroupDto(subject.category,subject.displayName,nodes.map { it.userMasteryPct }.average(),nodes)
        }
    }
    suspend fun getContestDiff(contestId: String): ContestDiffScreenDto? {
        val exam=loadStoredExams().firstOrNull { it.examId==contestId } ?: return null
        if(exam.newTopicsCount+exam.archivedTopicsCount+exam.confirmedTopicsCount==0) return null
        fun nodes(status:String)=exam.subjects.flatMap { subject ->subject.topics.filter { it.diffStatus==status }.mapIndexed { index,topic ->
            SyllabusTopicDto(topic.localTopicId,exam.examId,subject.category,subject.displayName,topic.displayTitle,index,estimation=EstimationMetadata(false,ConfidenceBadge.LOW,"Publicação cadastrada pelo usuário; confira a fonte do edital."),diffStatus=when(status){"ARQUIVADO"->TopicDiffStatus.REMOVED_ARCHIVED;"OFICIAL_NOVO"->TopicDiffStatus.NEW_IN_EDITAL;else->TopicDiffStatus.CONFIRMED})
        } }
        return ContestDiffScreenDto(exam.examId,exam.shortName,exam.examBoards.joinToString(),nodes("OFICIAL_CONFIRMADO"),nodes("OFICIAL_NOVO"),nodes("ARQUIVADO"))
    }
    suspend fun getTodayDashboard(userId: Long,dailyGoalQuestions: Int=50,activeContestIds: List<String> = listOf("CPNU")): TodayDashboardDto {
        owner(userId);val rows=projectedLearning();val today=LocalDate.now(LearningProjection.studyZone)
        val events=rows.flatMap { it.events }.filter { Instant.parse(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate()==today }
        return TodayDashboardDto(userId,today.toString(),dailyGoalQuestions,events.size,if(events.isEmpty())0.0 else events.count { it.isCorrect }*100.0/events.size,
            rows.count { Instant.parse(it.nextReviewAt)<=Instant.now() }.toLong(),rows.count { it.inErrorNotebook }.toLong(),rows.count { it.highConfidenceError }.toLong(),emptyList(),CommonTrunkSuggestion("",emptyList(),""),
            lastSyncedAt=metadata("last_sync"),syncMessage=metadata("sync_error"))
    }
    suspend fun getProgressAnalytics(userId: Long): ProgressAnalyticsDto {
        owner(userId);val rows=projectedLearning();val attempts=rows.sumOf { it.attempts };val correct=rows.sumOf { it.correct }
        val cutoff=loadStoredExams().filter { it.isActiveInLadder }.minByOrNull { it.ladderPriority }?.historicalCutoffScore?:0.0
        val since=LocalDate.now(LearningProjection.studyZone).minusDays(89)
        val events=rows.flatMap { it.events }.filter { Instant.parse(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate()>=since }
        val days=events.groupBy { Instant.parse(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate() }
        return ProgressAnalyticsDto(userId,if(attempts==0)0.0 else correct*100.0/attempts,attempts,rows.count { it.isMastered },
            ExamEngine.readinessForStoredExams(loadStoredExams(),LearningProjection.masteryByTopic(rows)),cutoff,
            rows.groupBy { it.disciplina }.map { (name,items) ->val count=items.sumOf { it.attempts };DisciplineMasteryBarDto(name,if(count==0)0.0 else items.sumOf { it.correct }*100.0/count,count,items.count { it.isMastered },items.sumOf { it.points }) }.sortedBy { it.accuracyPct },
            ConfidenceMatrixSummaryDto(events.count { it.isCorrect&&it.confidenceLevel>=2 },events.count { it.isCorrect&&it.confidenceLevel<2 },events.count { !it.isCorrect&&it.confidenceLevel<2 },events.count { !it.isCorrect&&it.confidenceLevel>=2 }),
            (0L..89L).map { offset ->val date=since.plusDays(offset);val daily=days[date].orEmpty();DailyHeatmapCellDto(date.toString(),daily.size,if(daily.isEmpty())0.0 else daily.count { it.isCorrect }*100.0/daily.size) })
    }

    private suspend fun decorateQuestions(questions:List<QuestionDto>):List<QuestionDto>{
        val records=projectedLearning().associateBy { it.questionId }
        return questions.map { q->records[q.id]?.let { row->q.copy(userStats=UserQuestionBriefStats(row.attempts,row.accuracyPct,row.events.maxByOrNull { Instant.parse(it.occurredAt) }?.occurredAt,row.isMastered,row.inErrorNotebook)) }?:q }
    }
    suspend fun fetchOrGetOfflineQuiz(userId: Long,mode: QuizMode,quantity: Int,contestId: String?=null,disciplina: String?=null): List<QuestionDto> {
        owner(userId);val count=quantity.coerceIn(1,120)
        try {
            auth?.ensureSession(api,ownerUserId)
            val remote=api.generateQuizQuestions(QuizGeneratePayload(userId,mode,count,trunkFocusSlugs=focusedTrunkSlugs().toList(),contestId=contestId,disciplina=disciplina)).map(QuestionCacheMapping::withRevision)
            if(remote.isNotEmpty()) dao.insertQuestions(remote.map { QuestionCacheMapping.toEntity(it,json) })
            return decorateQuestions(remote)
        } catch(e: CancellationException){throw e} catch(e: retrofit2.HttpException){
            if(e.code() in listOf(400,409,422)) throw IllegalStateException("Não há questões suficientes ou válidas para esta grade. Confira disciplinas, tópicos e quantidades.")
        } catch(_: Exception) { }
        val rows=projectedLearning().associateBy { it.questionId }
        val all=dao.getCachedQuestions(null,10000).map { QuestionCacheMapping.toDto(it,json) }.filter { it.correctAnswerIndices.size==1&&it.correctAnswerIndices[0] in it.answers.indices }
        val exam=loadStoredExams().firstOrNull { it.examId==contestId }
        require(mode==QuizMode.COMMON_TRUNK||exam?.lifecycleStatus!="ARQUIVADO"){"Concurso arquivado; revise sua seleção de estudo."}
        val topics=exam?.subjects?.flatMap { it.topics }?.filter { it.diffStatus!="ARQUIVADO" }?.map { it.canonicalTopicId }?.toSet()
        val commonTopics=if(mode==QuizMode.COMMON_TRUNK)loadStoredExams().filter { it.isActiveInLadder&&it.lifecycleStatus!="ARQUIVADO" }.flatMap { it.subjects }.flatMap { it.topics }.filter { it.diffStatus!="ARQUIVADO" }.map { it.canonicalTopicId }.toSet()else emptySet()
        val boardFilter=exam?.examBoards.orEmpty().filter { it!="A definir"&&it.isNotBlank() }.flatMap { if(it=="Cebraspe")listOf("Cebraspe","CEBRASPE","CESPE")else listOf(it) }.toSet()
        val pool=all.filter { mode!=QuizMode.SIMULADO||boardFilter.isEmpty()||it.source in boardFilter }.filter { q -> (disciplina==null||q.disciplina==disciplina) && if(mode==QuizMode.COMMON_TRUNK)q.canonicalTopicId in commonTopics else (exam==null||q.canonicalTopicId in topics.orEmpty()||(q.canonicalTopicId==null&&exam.subjects.any { it.displayName==q.disciplina })) }
        val selected=when(mode){
            QuizMode.SRS_REVIEW->pool.filter { rows[it.id]?.let { r->Instant.parse(r.nextReviewAt)<=Instant.now() }==true }.sortedBy { rows[it.id]?.nextReviewAt }.take(count)
            QuizMode.ERROR_NOTEBOOK->pool.filter { rows[it.id]?.inErrorNotebook==true }.take(count)
            QuizMode.MOST_BADASS->pool.filter { rows[it.id]?.let { r->!r.isMastered&&r.attempts>0 }==true }.sortedBy { rows[it.id]?.accuracyPct }.take(count)
            QuizMode.SIMULADO->{if(exam==null) emptyList() else {
                val ids=QuizSelection.weightedSimuladoIds(exam,pool.map { QuestionRef(it.id,it.canonicalSubjectId,it.canonicalTopicId) })
                val expected=exam.subjects.filter { it.topics.any { t->t.diffStatus!="ARQUIVADO" } }.sumOf { it.questionCount?:0 }
                require(expected>0&&ids.size==expected){"Cache insuficiente para o simulado: ${ids.size}/$expected questões. Conecte-se ou ajuste a grade."}
                ids.mapNotNull { id->pool.find { it.id==id } }
            }}
            QuizMode.COMMON_TRUNK->{val ids=QuizSelection.commonTrunkIds(loadStoredExams(),pool.map { QuestionRef(it.id,it.canonicalSubjectId,it.canonicalTopicId) },focusedTrunkSlugs(),canonicalMasteryBySubject(),LocalDate.now(LearningProjection.studyZone),count);ids.mapNotNull { id->pool.find { it.id==id } }}
            QuizMode.SINGLE->emptyList()
            else->pool.shuffled().take(count)
        }
        return decorateQuestions(selected)
    }

    suspend fun recordAttemptOfflineFirst(userId: Long,attempt: PendingAttemptEntity, session: SessionSnapshot? = null) {
        owner(userId);require(attempt.ownerUserId==userId);require(attempt.questionRevision.isNotEmpty()){ "A versão desta questão precisa ser atualizada antes de responder." }
        mutationMutex.withLock {
            transaction {
                val previous=dao.findPendingAttemptInSession(attempt.sessionUuid,attempt.questionId)
                if(previous!=null){require(previous.attemptId==attempt.attemptId&&previous.chosenIndex==attempt.chosenIndex&&previous.confidenceLevel==attempt.confidenceLevel){"Resposta já registrada nesta sessão."};return@transaction}
                val confirmed=baseLearning().any { row->row.events.any { it.attemptId==attempt.attemptId } }
                if(!confirmed) dao.enqueuePendingAttempt(attempt)
                if(session!=null) durable?.saveSession(session.sessionId,ownerUserId,json.encodeToString(session),session.revision,System.currentTimeMillis())
            }
        }
    }
    suspend fun syncPendingAttempts(userId: Long): Int = syncMutex.withLock {
        owner(userId)
        try {
            auth?.ensureSession(api,userId)
            dao.bindLegacyOwner(userId)
            val examSyncCount=syncPendingExams()
            (auth?.pendingProfile(userId) ?: metadata("pending_profile"))?.takeIf { it.isNotBlank() }?.let { val profile=json.decodeFromString<OnboardingPayload>(it);require(profile.userId==userId);api.saveOnboardingPreferences(profile);auth?.clearPendingProfile(userId,it);metadata("pending_profile","") }
            var synced=examSyncCount
            var transient=examDao.listPendingExams().any { it.syncStatus in setOf("PENDING_CREATE","PENDING_UPDATE","PENDING_PUBLISH")&&it.syncError==null }
            var batchCount=0
            while (batchCount++ < 20) {
            val pending=dao.getAllPendingAttempts().filter { it.ownerUserId==userId&&it.syncError==null }.take(120)
            if(pending.isEmpty()) break
            if(pending.isNotEmpty()){
                val payload=SubmitBatchPayload(userId,true,pending.map { it.payload() })
                val response=api.submitAttemptsBatch(payload)
                val byId=pending.associateBy { it.attemptId }
                require(response.results.map { it.attemptId }.distinct().size==response.results.size && response.results.all { byId[it.attemptId]?.questionId==it.questionId }){"Recibos inválidos; fila preservada."}
                mutationMutex.withLock { transaction {
                    for(ack in response.results){
                        val local=byId.getValue(ack.attemptId)
                        val question=dao.findQuestionById(local.questionId)?.let { QuestionCacheMapping.toDto(it,json) }
                        if(question!=null&&durable!=null){
                            val base=baseLearning().firstOrNull { it.questionId==local.questionId }
                            val projected=if(base!=null&&base.attempts>=ack.attemptsTotal) base else LearningProjection.project(base,question,listOf(local.event().copy(isCorrect=ack.isCorrect))).copy(isMastered=ack.isMastered,inErrorNotebook=ack.inErrorNotebook,accuracyPct=ack.accuracyAccumulatedPct,points=ack.pointsTotal,nextReviewAt=ack.nextReviewAt,attempts=ack.attemptsTotal,correct=ack.correctTotal,confidenceAvg=ack.confidenceAvg,canonicalSubjectId=ack.canonicalSubjectId?:base?.canonicalSubjectId?:question.canonicalSubjectId,canonicalTopicId=ack.canonicalTopicId?:base?.canonicalTopicId?:question.canonicalTopicId)
                            durable.putLearning(listOf(LearningRecordEntity(local.questionId,projected.revision,json.encodeToString(projected))))
                        }
                        dao.updateLearningFlags(local.questionId,ack.inErrorNotebook,ack.isMastered)
                        dao.acknowledgeAttempt(ack.attemptId,userId)
                        synced++
                    }
                    response.rejected.forEach { rejection->
                        if(byId[rejection.attemptId]?.questionId==rejection.questionId){
                            if(rejection.retryable) transient=true else dao.markAttemptError(rejection.attemptId,userId,rejection.message)
                        }
                    }
                } }
                val accounted=(response.results.map { it.attemptId }+response.rejected.map { it.attemptId }).toSet()
                if(pending.any { it.attemptId !in accounted }) transient=true
            }
            if(transient) break
            }
            if(dao.getAllPendingAttempts().any { it.ownerUserId==userId&&it.syncError==null }) transient=true
            durable?.reports(userId)?.forEach { report ->
                if(metadata("report_error:${report.eventId}").isNullOrBlank()) {
                    try {
                        api.reportQuestionProblem(report.questionId,ReportProblemPayload(userId,report.category))
                        durable.acknowledgeReport(report.eventId,userId)
                        synced++
                    } catch(e:retrofit2.HttpException) {
                        if(e.code() in listOf(400,404,409,422)) metadata("report_error:${report.eventId}","Reporte exige revisão (HTTP ${e.code()}); conteúdo preservado.")
                        else throw e
                    }
                }
            }
            auth?.saveProfile(api.profile())
            pullExams()
            if(durable!=null){var cursor:String?=null;do {
                val page=api.learningState(cursor)
                mutationMutex.withLock { transaction {
                    val current=baseLearning().associateBy { it.questionId }
                    val accepted=page.records.filter { incoming->(current[incoming.questionId]?.attempts?:0)<=incoming.attempts }
                    durable.putLearning(accepted.map { LearningRecordEntity(it.questionId,it.revision,json.encodeToString(it)) })
                    accepted.forEach { dao.updateLearningFlags(it.questionId,it.inErrorNotebook,it.isMastered) }
                } }
                cursor=page.nextCursor
            }while(cursor!=null)}
            metadata("last_sync",Instant.now().toString());metadata("sync_error",if(transient)"Há tentativas aguardando confirmação do servidor." else "")
            if(transient) error("Há tentativas aguardando confirmação do servidor.")
            synced
        } catch(e: CancellationException){throw e} catch(e: Exception){if(e is retrofit2.HttpException&&e.code()==401)auth?.invalidate(userId);metadata("sync_error","Sincronização pendente. Seus registros continuam neste aparelho.");throw e}
    }
    private fun PendingAttemptEntity.payload()=AnswerAttemptPayload(attemptId,Instant.ofEpochMilli(createdAtEpochMs).toString(),questionRevision,questionId,chosenIndex,chosenText,isCorrect,timeSpentSeconds,confidenceLevel,sessionUuid,quizMode,examBlock,contestId,userNote,canonicalSubjectId,canonicalTopicId)

    private suspend fun syncPendingExams(): Int {
        var count=0
        for(entity in examDao.listPendingExams()){
            if(entity.syncStatus !in setOf("PENDING_CREATE","PENDING_UPDATE","PENDING_PUBLISH")) continue
            if(entity.syncError?.let { it.startsWith("Conflito de revisão")||it.startsWith("Revisão necessária") } == true) continue
            val snapshot=ExamLocalMapping.assemble(entity,examDao.subjectsFor(entity.examId),examDao.topicsFor(entity.examId))
            try {
                val result=when(snapshot.syncStatus){
                    "PENDING_CREATE"->api.createExam(snapshot.toPayload())
                    "PENDING_PUBLISH"->api.publishEdital(snapshot.examId,PublishEditalPayload(snapshot.revision,snapshot.subjects.flatMap { it.topics }.filter { it.diffStatus!="ARQUIVADO" }.map { PublishedTopicPayload(it.canonicalTopicId,it.canonicalSubjectId,it.displayTitle) }))
                    else->api.updateExam(snapshot.examId,snapshot.toPayload())
                }
                mutationMutex.withLock {
                    val current=examDao.listExams().find { it.examId==snapshot.examId }?.let { ExamLocalMapping.assemble(it,examDao.subjectsFor(it.examId),examDao.topicsFor(it.examId)) }
                    if(current==snapshot){saveExamAggregate(result.toStored());count++}
                    else if(current!=null) saveExamAggregate(current.copy(revision=result.revision,syncStatus="PENDING_UPDATE"))
                }
            } catch(e: CancellationException){throw e} catch(e: Exception){
                val permanent=e is retrofit2.HttpException&&e.code() in listOf(400,404,409,422)
                val reason=if(e is retrofit2.HttpException&&e.code()==409) "Conflito de revisão: preserve sua edição antes de carregar a versão do servidor." else if(permanent) "Revisão necessária: o servidor recusou a grade. Confira seus campos e salve uma nova edição." else "Envio pendente; edição preservada."
                examDao.setSyncError(snapshot.examId,reason)
                if(!permanent)throw e
            }
        }
        return count
    }
    suspend fun pullExams() {
        val incoming=api.listExams()
        mutationMutex.withLock {
            val local=examDao.listExams().associateBy { it.examId }
            incoming.forEach { remote ->
                val current=local[remote.id]
                if(current==null||current.syncStatus=="SYNCED") saveExamAggregate(remote.toStored())
                else if(current.revision!=remote.revision&&current.syncError==null) examDao.setSyncError(remote.id,"Conflito de revisão: há uma versão diferente no servidor. Preserve sua edição antes de carregá-la.")
            }
        }
        val catalog=api.catalog();ExamCatalog.installCatalog(catalog);metadata("catalog",catalog.toString())
    }

    suspend fun reportQuestionProblem(userId: Long,questionId: String,category: String): Boolean {
        owner(userId);require(category in VALID_PROBLEM_CATEGORIES)
        val queue=durable?:return false
        val id=UUID.nameUUIDFromBytes("$userId:$questionId:$category".toByteArray()).toString()
        queue.enqueueReport(PendingReportEntity(id,userId,questionId,category))
        return true
    }
    suspend fun requestAiTutor(questionId: String,kind: String,hintIndex: Int?=null): String {
        return try {auth?.ensureSession(api,ownerUserId);api.requestAiTutor(questionId,kind,hintIndex).contentMarkdown}
        catch(e:CancellationException){throw e}catch(_:Exception){
            if(kind in listOf("contextual-hint","child-friendly-pre")) "Tutor indisponível. A dica não foi gerada; sua resposta continua sem interferência."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário já disponível (tutor indisponível):\n\n$it" }?:"Tutor indisponível. Tente novamente quando houver conexão."
        }
    }
    suspend fun pendingReview():List<PendingReviewItem> {
        val attempts=dao.getAllPendingAttempts().filter{it.ownerUserId==ownerUserId}.map { item->
            PendingReviewItem(item.attemptId,dao.findQuestionById(item.questionId)?.question?:"Questão ${item.questionId}","Resposta: ${item.chosenText} · ${Instant.ofEpochMilli(item.createdAtEpochMs)}",item.syncError?:"Aguardando confirmação do servidor")
        }
        val exams=loadStoredExams().filter { it.syncStatus!="SYNCED" }.map { PendingReviewItem(it.examId,it.title,"Concurso · revisão ${it.revision}",it.syncError?:"Edição aguardando confirmação") }
        val backups=durable?.preservedExamEdits().orEmpty().map { item->val exam=json.decodeFromString<StoredExam>(item.value);PendingReviewItem(item.key,exam.title,"Edição preservada antes de usar a versão do servidor","Disponível na exportação; não será reenviada automaticamente") }
        return attempts + exams + backups + durable?.reports(ownerUserId).orEmpty().map { item->PendingReviewItem(item.eventId,"Reporte de questão",item.category,metadata("report_error:${item.eventId}")?:"Aguardando confirmação do servidor") }
    }
    suspend fun preserveAndUseServerExam(expected:StoredExam,directory:File):File {
        auth?.ensureSession(api,ownerUserId)
        val remote=api.listExams().find { it.id==expected.examId }?:error("A versão do servidor não está disponível; sua edição foi preservada.")
        mutationMutex.withLock { transaction {
            val entity=examDao.listExams().find { it.examId==expected.examId }?:error("O concurso mudou; reabra a revisão.")
            val current=ExamLocalMapping.assemble(entity,examDao.subjectsFor(entity.examId),examDao.topicsFor(entity.examId))
            require(current==expected){"A edição mudou enquanto a versão era consultada. Reabra a revisão."}
            checkNotNull(durable).putMetadata(LocalMetadataEntity("exam_backup:${expected.examId}:${UUID.randomUUID()}",json.encodeToString(current)))
            saveExamAggregate(remote.toStored())
        } }
        return exportPending(directory)
    }
    suspend fun exportPending(directory:File):File=withContext(Dispatchers.IO){
        directory.mkdirs()
        val attempts=dao.getAllPendingAttempts().filter{it.ownerUserId==ownerUserId}
        val exams=loadStoredExams().filter { it.syncStatus!="SYNCED" }
        val document=buildJsonObject {
            put("schema_version",2);put("owner_user_id",ownerUserId)
            put("exams",json.encodeToJsonElement(exams))
            put("preserved_exam_edits",buildJsonArray { durable?.preservedExamEdits().orEmpty().forEach { add(Json.parseToJsonElement(it.value)) } })
            put("attempts",buildJsonArray{attempts.forEach{item->add(buildJsonObject{put("payload",json.encodeToJsonElement(item.payload()));put("review_reason",item.syncError);put("local_created_at_ms",item.createdAtEpochMs)})}})
            put("reports",buildJsonArray{durable?.reports(ownerUserId).orEmpty().forEach{item->add(buildJsonObject{put("event_id",item.eventId);put("question_id",item.questionId);put("category",item.category)})}})
        }
        File(directory,"cogniva-preservados-${System.currentTimeMillis()}.json").also{it.writeText(document.toString(),Charsets.UTF_8)}
    }
    suspend fun getLocalStorageStats(): Pair<Int,Int> = dao.countCachedQuestions() to (dao.getAllPendingAttempts().count { it.ownerUserId==ownerUserId }+(durable?.reports(ownerUserId)?.size?:0)+examDao.listPendingExams().size)
    suspend fun clearLocalQuestionCache() {
        require(dao.getAllPendingAttempts().isEmpty() && durable?.pausedSession(ownerUserId)==null){"Conclua a sessão e revise as pendências antes de limpar as questões."}
        dao.clearCachedQuestions()
    }
    suspend fun createSession(session: SessionSnapshot){require(session.ownerUserId==ownerUserId);durable?.createSession(QuizSessionEntity(session.sessionId,ownerUserId,json.encodeToString(session),session.revision))}
    suspend fun saveSession(session: SessionSnapshot){require(session.ownerUserId==ownerUserId);durable?.saveSession(session.sessionId,ownerUserId,json.encodeToString(session),session.revision,System.currentTimeMillis())}
    suspend fun pausedSession(): SessionSnapshot? = durable?.pausedSession(ownerUserId)?.let { json.decodeFromString<SessionSnapshot>(it.payloadJson) }
    suspend fun completeSession(id:String){durable?.completeSession(id,ownerUserId)}

    suspend fun fetchRemoteApkInfo(): ApkInfoDto?=try{api.getApkInfo()}catch(e:CancellationException){throw e}catch(_:Exception){null}
    suspend fun downloadApkUpdate(targetDir:File): File?=withContext(Dispatchers.IO){
        val info=fetchRemoteApkInfo()?:return@withContext null
        require(BuildConfig.API_BASE_URL.startsWith("https://")){"Atualização exige conexão HTTPS."}
        val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).build()
        targetDir.mkdirs();val partial=File(targetDir,"${UUID.randomUUID()}.part")
        try{
            client.newCall(Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/')+"/apk/download").build()).execute().use { response ->
                if(!response.isSuccessful) return@withContext null
                val body=response.body?:return@withContext null
                body.byteStream().use { input->partial.outputStream().use { input.copyTo(it) } }
            }
            val hash=partial.inputStream().use { input->val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().joinToString(""){"%02x".format(it)} }
            if(partial.length()!=info.sizeBytes||!hash.equals(info.sha256Checksum,true))return@withContext null
            val destination=File(targetDir,"cogniva-${info.versionCode}.apk")
            java.nio.file.Files.move(partial.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            destination
        }finally{partial.delete()}
    }
}

fun StoredExam.toPayload():ExamBlueprintPayload=ExamBlueprintPayload(id=examId,revision=revision,wrongAnswerPenalty=wrongAnswerPenalty,title=title,shortName=shortName,institution=institution,role=role,trunkSlug=trunkSlug,status=lifecycleStatus,examBoards=examBoards,
    targetDate=when(dateType){"EXACT_DATE"->buildJsonObject{put("type","EXACT_DATE");put("iso_date",exactDateIso.orEmpty())};"ESTIMATED_WINDOW"->buildJsonObject{put("type","ESTIMATED_WINDOW");put("label",estimatedWindowLabel.orEmpty());put("estimated_sort_date",estimatedSortDateIso)};else->buildJsonObject{put("type","UNDEFINED")}},
    historicalCutoffScore=historicalCutoffScore,isActiveInLadder=isActiveInLadder,ladderPriority=ladderPriority,syncStatus=syncStatus,subjects=subjects.map { s->ExamSubjectPayload(s.localSubjectId,s.canonicalSubjectId,s.displayName,s.category,s.weight,s.questionCount,s.relevanceTier,s.topics.map { ExamTopicPayload(it.localTopicId,it.canonicalTopicId,it.canonicalSubjectId,it.displayTitle,it.diffStatus) }) })
fun ExamBlueprintPayload.toStored():StoredExam=StoredExam(examId=id,title=title,shortName=shortName,institution=institution,role=role,trunkSlug=trunkSlug,lifecycleStatus=status,examBoards=examBoards,dateType=targetDate["type"]?.jsonPrimitive?.content?:"UNDEFINED",exactDateIso=targetDate["iso_date"]?.jsonPrimitive?.contentOrNull,estimatedWindowLabel=targetDate["label"]?.jsonPrimitive?.contentOrNull,estimatedSortDateIso=targetDate["estimated_sort_date"]?.jsonPrimitive?.contentOrNull?:targetDate["iso_date"]?.jsonPrimitive?.contentOrNull.orEmpty(),historicalCutoffScore=historicalCutoffScore,isActiveInLadder=isActiveInLadder,ladderPriority=ladderPriority,syncStatus="SYNCED",revision=revision,wrongAnswerPenalty=wrongAnswerPenalty,
    confirmedTopicsCount=subjects.sumOf { it.topics.count { t->t.diffStatus=="OFICIAL_CONFIRMADO" } },newTopicsCount=subjects.sumOf { it.topics.count { t->t.diffStatus=="OFICIAL_NOVO" } },archivedTopicsCount=subjects.sumOf { it.topics.count { t->t.diffStatus=="ARQUIVADO" } },
    subjects=subjects.map { s->StoredSubject(s.localSubjectId,s.canonicalSubjectId,s.displayName,s.category,s.weight,s.questionCount,s.relevanceTier,s.topics.map { StoredTopic(it.localTopicId,it.canonicalTopicId,it.canonicalSubjectId,it.displayTitle,it.diffStatus) }) })
