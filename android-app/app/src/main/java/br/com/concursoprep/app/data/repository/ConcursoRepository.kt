package br.com.concursoprep.app.data.repository

import androidx.room.withTransaction
import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.data.AuthStore
import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.data.remote.*
import br.com.concursoprep.app.data.update.AppUpdateManager
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
    companion object {
        const val SQLITE_BIND_CHUNK_SIZE = 400
        const val SESSION_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L // 30 dias
        const val LGPD_CONFIRM_WORD = "EXCLUIR"
    }

    suspend fun questionImportCapabilities(): JsonObject { auth?.ensureSession(api, ownerUserId); return api.questionImportCapabilities() }
    suspend fun previewQuestionImport(payload: JsonObject): JsonObject { auth?.ensureSession(api, ownerUserId); return api.previewQuestionImport(payload) }
    suspend fun commitQuestionImport(payload: JsonObject): JsonObject { auth?.ensureSession(api, ownerUserId); return api.commitQuestionImport(payload) }
    suspend fun assistQuestionImport(payload: JsonObject): JsonObject { auth?.ensureSession(api, ownerUserId); return api.assistQuestionImport(payload) }
    suspend fun loadQuestionImportDraft(): String? = metadata("question_import_draft")
    suspend fun saveQuestionImportDraft(value: String) {
        check(durable != null) { "Armazenamento local indisponível; o envio foi bloqueado." }
        metadata("question_import_draft", value)
    }
    fun observeSync(): kotlinx.coroutines.flow.Flow<String?> = durable?.let { state ->
        kotlinx.coroutines.flow.combine(state.observeMetadata("last_sync"), state.observeMetadata("sync_error"), dao.observePendingAttemptCount(), examDao.observeAllExams()) { stamp, error, count, exams -> "$stamp|$error|$count|${exams.hashCode()}" }
    } ?: kotlinx.coroutines.flow.flowOf(null)
    private val syncMutex = Mutex()
    private val mutationMutex = Mutex()
    suspend fun awaitIdleForClose() {
        syncMutex.withLock {
            mutationMutex.withLock { }
        }
    }
    private suspend fun <T> transaction(block: suspend () -> T): T = if (database != null) database.withTransaction { block() } else block()
    private fun owner(userId: Long) { require(ownerUserId == 0L || ownerUserId == userId) { "As pendências pertencem a outra conta e foram preservadas." } }
    private suspend fun metadata(key: String): String? = durable?.metadata(key)
    private suspend fun metadata(key: String, value: String) { durable?.putMetadata(LocalMetadataEntity(key, value)) }
    private fun safeInstant(iso: String): Instant = runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)

    suspend fun startGuestOrOfflineSession(displayName: String?): AuthSessionDto {
        val identity = auth ?: error("Credencial da instalação indisponível")
        return try {
            api.guestSession(NativeSessionPayload(identity.installationId, identity.installationSecret, displayName)).also(identity::accept)
        } catch (e: CancellationException) { throw e } catch (_: java.io.IOException) {
            AuthSessionDto(
                token = "",
                expiresInDays = 0,
                profile = UserProfileDto(
                    userId = identity.localUserId,
                    displayName = displayName,
                    isGuest = true,
                    activeContestIds = listOf("CPNU"),
                    currentDailyStreak = 0,
                    streakFreezesAvailable = 0
                ),
                historicalQuestionsCount = 0
            )
        }
    }

    suspend fun loginWithEmail(email: String, password: String): AuthSessionDto {
        val identity = auth ?: error("Credencial da instalação indisponível")
        val normalized = email.trim()
        require(normalized.contains("@") && password.isNotEmpty()) { "Informe seu e-mail e sua senha." }
        return api.login(AuthLoginPayload(email = normalized, password = password)).also(identity::accept)
    }

    suspend fun registerAccount(email: String, password: String, displayName: String?): AuthSessionDto {
        val identity = auth ?: error("Credencial da instalação indisponível")
        val normalized = email.trim()
        require(normalized.contains("@") && password.length >= 10) { "Informe um e-mail válido e senha com no mínimo 10 caracteres." }
        if (!identity.isRegisteredAccount(ownerUserId)) {
            runCatching { identity.ensureSession(api, ownerUserId) }
        }
        return api.register(
            AuthRegisterPayload(
                email = normalized,
                password = password,
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
            )
        ).also(identity::accept)
    }

    suspend fun logoutAccount(): Long {
        val identity = auth ?: error("Credencial da instalação indisponível")
        runCatching { api.logout() }
        return identity.clearSessionForLogout(ownerUserId)
    }

    /**
     * Exclui permanentemente a conta e todos os dados pessoais no servidor (`DELETE /api/v1/auth/account`
     * com `{"confirm": "EXCLUIR"}`), preservando o acervo público compartilhado (`todasQuestoes`),
     * e limpa credenciais/fila locais conforme o Art. 18 da LGPD (Lei 13.709/2018).
     */
    suspend fun deleteAccount(
        confirm: String = LGPD_CONFIRM_WORD,
        postSwitchMessage: String? = null
    ): Long {
        require(confirm == LGPD_CONFIRM_WORD) {
            "Digite EXCLUIR exatamente para confirmar a exclusão definitiva da conta."
        }
        if (auth != null) {
            val token = auth.token(ownerUserId)
            if (token.isNullOrBlank()) {
                if (auth.isRegisteredAccount(ownerUserId)) {
                    error("Sua sessão expirou. Entre novamente com seu e-mail e senha para confirmar a exclusão da conta.")
                } else if (ownerUserId > 0L) {
                    auth.ensureSession(api, ownerUserId)
                }
            }
        }
        val response = api.deleteAccount(DeleteAccountRequest(confirm = confirm))
        if (!response.isSuccessful) {
            throw retrofit2.HttpException(response)
        }
        mutationMutex.withLock {
            transaction {
                dao.getAllPendingAttempts()
                    .filter { it.ownerUserId == ownerUserId }
                    .forEach { dao.acknowledgeAttempt(it.attemptId, ownerUserId) }
                durable?.reports(ownerUserId).orEmpty()
                    .forEach { durable?.acknowledgeReport(it.eventId, ownerUserId) }
                durable?.pausedSession(ownerUserId)
                    ?.let { durable?.completeSession(it.sessionId, ownerUserId) }
                durable?.deleteMetadata("pending_profile")
                durable?.deleteMetadata("sync_error")
                durable?.deleteMetadata("last_sync")
            }
        }
        return auth?.wipeAccountAndResetToGuest(ownerUserId, postSwitchMessage) ?: 0L
    }

    suspend fun adoptExamTemplate(examId: String): StoredExam {
        auth?.ensureSession(api, ownerUserId)
        val updated = api.adoptExamTemplate(examId).toStored()
        saveExamLocal(updated)
        return updated
    }

    suspend fun saveOnboarding(userId: Long, contestIds: List<String>, dailyQuestionsTarget: Int, dailyHours: Double, displayName: String?): UserProfileDto {
        require(dailyQuestionsTarget in 1..1000 && dailyHours.isFinite() && dailyHours in 0.0..24.0)
        val payload = OnboardingPayload(userId, contestIds, dailyQuestionsTarget, dailyHours, displayName)
        val serialized = json.encodeToString(payload)
        auth?.setPendingProfile(userId, serialized)
        if (userId == ownerUserId) {
            metadata("pending_profile", serialized)
            examDao.listExams().forEach { examDao.setActive(it.examId, it.examId in contestIds) }
        }
        return try {
            api.saveOnboardingPreferences(payload).also {
                auth?.clearPendingProfile(userId, serialized)
                if (userId == ownerUserId) metadata("pending_profile", "")
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            UserProfileDto(userId, displayName = displayName, dailyQuestionsTarget = dailyQuestionsTarget, dailyStudyHoursTarget = dailyHours, activeContestIds = contestIds, currentDailyStreak = 0, streakFreezesAvailable = 0)
        }
    }

    suspend fun pruneAbandonedSessions(nowEpochMs: Long = System.currentTimeMillis()) {
        durable?.pruneSessionsOlderThan(nowEpochMs - SESSION_RETENTION_MS)
    }

    suspend fun ensureExamSeed() {
        mutationMutex.withLock {
            metadata("catalog")?.takeIf { it.isNotBlank() }?.let {
                runCatching { ExamCatalog.installCatalog(Json.parseToJsonElement(it).jsonObject) }
            }
            metadata("knowledge_catalog")?.takeIf { it.isNotBlank() }?.let {
                runCatching { ExamCatalog.installKnowledge(Json.parseToJsonElement(it).jsonObject) }
            }
            dao.bindLegacyOwner(ownerUserId)
            durable?.pruneSessionsOlderThan(System.currentTimeMillis() - SESSION_RETENTION_MS)
            val trunkIds = examDao.listTrunks().map { it.slug }.toSet()
            val missingTrunks = ExamCatalog.trunks().filter { it.slug !in trunkIds }
            if (missingTrunks.isNotEmpty()) examDao.upsertTrunks(missingTrunks.map { CareerTrunkEntity(it.slug, it.name, it.iconKey, false) })
            if (examDao.countExams() == 0) {
                val active = auth?.activeContestIds(ownerUserId) ?: setOf("CPNU")
                ExamCatalog.systemExams().forEach { saveExamAggregate(it.copy(isActiveInLadder = it.examId in active)) }
            }
        }
    }
    suspend fun loadStoredExams(includeDeleted: Boolean = false): List<StoredExam> {
        ensureExamSeed()
        return examDao.listExams().map { ExamLocalMapping.assemble(it, examDao.subjectsFor(it.examId), examDao.topicsFor(it.examId)) }.filter { includeDeleted || it.lifecycleStatus != "EXCLUIDO" }
    }
    private suspend fun saveExamAggregate(exam: StoredExam) = transaction {
        examDao.upsertExam(ExamLocalMapping.toEntity(exam))
        examDao.clearTopics(exam.examId); examDao.clearSubjects(exam.examId)
        examDao.upsertSubjects(ExamLocalMapping.subjectsOf(exam)); examDao.upsertTopics(ExamLocalMapping.topicsOf(exam))
    }
    suspend fun saveExamLocal(exam: StoredExam) = mutationMutex.withLock { saveExamAggregate(exam) }
    suspend fun createExamOffline(draft: ExamDraft): StoredExam = OfflineExamQueue.createPendingExam(draft).also { saveExamLocal(it) }
    suspend fun editExamOffline(exam: StoredExam) {
        mutationMutex.withLock {
            val previous = examDao.listExams().find { it.examId == exam.examId }?.let { ExamLocalMapping.assemble(it, examDao.subjectsFor(it.examId), examDao.topicsFor(it.examId)) }
            val nextSyncStatus = when {
                exam.lifecycleStatus == "EXCLUIDO" && previous != null && previous.revision == 0L && previous.syncStatus == "PENDING_CREATE" -> "SYNCED"
                exam.revision == 0L && exam.syncStatus == "PENDING_CREATE" -> "PENDING_CREATE"
                else -> "PENDING_UPDATE"
            }
            saveExamAggregate(exam.copy(syncStatus = nextSyncStatus, syncError = null, isCustomized = true))
        }
    }
    suspend fun setTrunkFocus(slug: String, focused: Boolean) { ensureExamSeed(); examDao.setTrunkFocus(slug, focused) }
    suspend fun focusedTrunkSlugs(): Set<String> { ensureExamSeed(); return examDao.listTrunks().filter { it.isFocusedTrunk }.map { it.slug }.toSet() }

    private suspend fun baseLearning(): List<LearningRecord> =
        durable?.learning().orEmpty().mapNotNull { entity ->
            runCatching { json.decodeFromString<LearningRecord>(entity.payloadJson) }.getOrNull()
        }

    /**
     * Consulta em lote (`<= 400` IDs por query SQLite) registros de aprendizagem sem N+1.
     */
    suspend fun learningMapForQuestions(questionIds: Collection<String>): Map<String, LearningRecord> {
        val state = durable ?: return emptyMap()
        val distinctIds = questionIds.filter { it.isNotBlank() }.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        val result = HashMap<String, LearningRecord>(distinctIds.size)
        for (chunk in distinctIds.chunked(SQLITE_BIND_CHUNK_SIZE)) {
            state.learningForQuestions(chunk).forEach { entity ->
                runCatching { json.decodeFromString<LearningRecord>(entity.payloadJson) }
                    .getOrNull()
                    ?.let { result[it.questionId] = it }
            }
        }
        return result
    }

    private suspend fun cachedQuestionsMap(questionIds: Collection<String>): Map<String, QuestionDto> {
        val distinctIds = questionIds.filter { it.isNotBlank() }.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        val result = HashMap<String, QuestionDto>(distinctIds.size)
        for (chunk in distinctIds.chunked(SQLITE_BIND_CHUNK_SIZE)) {
            dao.findQuestionsByIds(chunk).forEach { entity ->
                result[entity.id] = QuestionCacheMapping.toDto(entity, json)
            }
        }
        return result
    }

    private suspend fun projectedLearningForQuestions(questionIds: Collection<String>): Map<String, LearningRecord> {
        val targetSet = questionIds.filter { it.isNotBlank() }.toSet()
        if (targetSet.isEmpty()) return emptyMap()
        val base = learningMapForQuestions(targetSet).toMutableMap()
        val pendingByQuestion = dao.getAllPendingAttempts()
            .filter { it.ownerUserId == ownerUserId && it.syncError == null && it.questionId in targetSet }
            .groupBy { it.questionId }
        if (pendingByQuestion.isNotEmpty()) {
            val cachedById = cachedQuestionsMap(pendingByQuestion.keys)
            pendingByQuestion.forEach { (id, events) ->
                val question = cachedById[id]
                if (question != null) {
                    base[id] = LearningProjection.project(base[id], question, events.map { it.event() })
                }
            }
        }
        return base
    }

    private fun PendingAttemptEntity.event() = LearningEvent(attemptId, Instant.ofEpochMilli(createdAtEpochMs).toString(), isCorrect, confidenceLevel, timeSpentSeconds, questionRevision)

    suspend fun projectedLearning(): List<LearningRecord> {
        val base = baseLearning().associateBy { it.questionId }.toMutableMap()
        val pendingByQuestion = dao.getAllPendingAttempts()
            .filter { it.ownerUserId == ownerUserId && it.syncError == null }
            .groupBy { it.questionId }
        if (pendingByQuestion.isNotEmpty()) {
            val cachedById = cachedQuestionsMap(pendingByQuestion.keys)
            pendingByQuestion.forEach { (id, events) ->
                val question = cachedById[id]
                if (question != null) base[id] = LearningProjection.project(base[id], question, events.map { it.event() })
            }
        }
        return base.values.toList()
    }

    suspend fun canonicalMasteryBySubject(): Map<String, Double> = LearningProjection.masteryBySubject(projectedLearning(), loadStoredExams().filter { it.isActiveInLadder && it.lifecycleStatus !in setOf("ARQUIVADO", "EXCLUIDO") }.minByOrNull { it.ladderPriority })

    suspend fun getContestSyllabus(contestId: String, userId: Long): List<DisciplineSyllabusGroupDto> {
        owner(userId)
        val exam = loadStoredExams().firstOrNull { it.examId == contestId } ?: return emptyList()
        val rows = LearningProjection.knowledgeRows(projectedLearning()); val mastery = LearningProjection.masteryByTopic(rows)
        return exam.subjects.mapNotNull { subject ->
            val visible = subject.topics.filter { it.diffStatus != "ARQUIVADO" }; if (visible.isEmpty()) return@mapNotNull null
            val nodes = visible.mapIndexed { index, topic ->
                val related = rows.filter { it.canonicalTopicId == topic.canonicalTopicId }
                val value = (mastery[topic.canonicalTopicId] ?: 0.0) * 100
                SyllabusTopicNodeDto(
                    SyllabusTopicDto(
                        topic.localTopicId, exam.examId, subject.groupLabel(), subject.displayName, topic.displayTitle, index,
                        canonicalTopicId = topic.canonicalTopicId,
                        estimation = EstimationMetadata(topic.diffStatus == "PRE_EDITAL_BASE", ConfidenceBadge.LOW, "Modelo/manual. Valide o conteúdo no edital e registre a fonte.", emptyList()),
                        diffStatus = when (topic.diffStatus) { "OFICIAL_NOVO" -> TopicDiffStatus.NEW_IN_EDITAL; "ARQUIVADO" -> TopicDiffStatus.REMOVED_ARCHIVED; else -> TopicDiffStatus.CONFIRMED }
                    ),
                    value,
                    related.sumOf { it.attempts },
                    if (related.isEmpty()) "NAO_INICIADO" else if (related.all { it.isMastered }) "DOMINADO" else if (related.any { it.practiceAvailable != false && (it.reviewRequired || safeInstant(it.nextReviewAt) <= Instant.now()) }) "REVISAO" else "QUESTOES"
                )
            }
            DisciplineSyllabusGroupDto(subject.groupLabel(), subject.displayName, nodes.map { it.userMasteryPct }.average(), nodes, subject.localSubjectId)
        }
    }

    suspend fun getContestDiff(contestId: String): ContestDiffScreenDto? {
        val exam = loadStoredExams().firstOrNull { it.examId == contestId } ?: return null
        if (exam.newTopicsCount + exam.archivedTopicsCount + exam.confirmedTopicsCount == 0) return null
        fun nodes(status: String) = exam.subjects.flatMap { subject ->
            subject.topics.filter { it.diffStatus == status }.mapIndexed { index, topic ->
                SyllabusTopicDto(topic.localTopicId, exam.examId, subject.groupLabel(), subject.displayName, topic.displayTitle, index, canonicalTopicId = topic.canonicalTopicId, estimation = EstimationMetadata(false, ConfidenceBadge.LOW, "Publicação cadastrada pelo usuário; confira a fonte do edital."), diffStatus = when (status) { "ARQUIVADO" -> TopicDiffStatus.REMOVED_ARCHIVED; "OFICIAL_NOVO" -> TopicDiffStatus.NEW_IN_EDITAL; else -> TopicDiffStatus.CONFIRMED })
            }
        }
        return ContestDiffScreenDto(exam.examId, exam.shortName, exam.examBoards.joinToString(), nodes("OFICIAL_CONFIRMADO"), nodes("OFICIAL_NOVO"), nodes("ARQUIVADO"))
    }

    suspend fun getTodayDashboard(userId: Long, dailyGoalQuestions: Int = 50, activeContestIds: List<String> = listOf("CPNU")): TodayDashboardDto {
        owner(userId); val rows = projectedLearning(); val today = LocalDate.now(LearningProjection.studyZone)
        val events = rows.flatMap { it.events }.filter { safeInstant(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate() == today }
        val actionable = rows.filter { it.practiceAvailable != false }
        return TodayDashboardDto(
            userId, today.toString(), dailyGoalQuestions, events.size,
            if (events.isEmpty()) 0.0 else events.count { it.isCorrect } * 100.0 / events.size,
            actionable.count { it.reviewRequired || safeInstant(it.nextReviewAt) <= Instant.now() }.toLong(),
            actionable.count { it.inErrorNotebook }.toLong(),
            actionable.count { it.inErrorNotebook && it.highConfidenceError }.toLong(),
            emptyList(), CommonTrunkSuggestion("", emptyList(), ""),
            lastSyncedAt = metadata("last_sync"), syncMessage = metadata("sync_error")
        )
    }

    suspend fun getProgressAnalytics(userId: Long): ProgressAnalyticsDto {
        owner(userId); val rows = projectedLearning(); val attempts = rows.sumOf { it.attempts }; val correct = rows.sumOf { it.correct }
        val cutoff = loadStoredExams().filter { it.isActiveInLadder }.minByOrNull { it.ladderPriority }?.historicalCutoffScore ?: 0.0
        val since = LocalDate.now(LearningProjection.studyZone).minusDays(89)
        val events = rows.flatMap { it.events }.filter { safeInstant(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate() >= since }
        val days = events.groupBy { safeInstant(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate() }
        return ProgressAnalyticsDto(
            userId, if (attempts == 0) 0.0 else correct * 100.0 / attempts, rows.count { it.attempts > 0 }, rows.count { it.isMastered },
            ExamEngine.readinessForStoredExams(loadStoredExams(), LearningProjection.masteryByTopic(rows)), cutoff,
            rows.groupBy { it.disciplina }.map { (name, items) -> val count = items.sumOf { it.attempts }; DisciplineMasteryBarDto(name, if (count == 0) 0.0 else items.sumOf { it.correct } * 100.0 / count, count, items.count { it.isMastered }, items.sumOf { it.points }) }.sortedBy { it.accuracyPct },
            ConfidenceMatrixSummaryDto(events.count { it.isCorrect && it.confidenceLevel >= 2 }, events.count { it.isCorrect && it.confidenceLevel < 2 }, events.count { !it.isCorrect && it.confidenceLevel < 2 }, events.count { !it.isCorrect && it.confidenceLevel >= 2 }),
            (0L..89L).map { offset -> val date = since.plusDays(offset); val daily = days[date].orEmpty(); DailyHeatmapCellDto(date.toString(), daily.size, if (daily.isEmpty()) 0.0 else daily.count { it.isCorrect } * 100.0 / daily.size) }
        )
    }

    private suspend fun decorateQuestions(questions: List<QuestionDto>): List<QuestionDto> {
        if (questions.isEmpty()) return emptyList()
        val records = projectedLearningForQuestions(questions.map { it.id })
        return questions.map { q ->
            records[q.id]?.let { row ->
                q.copy(
                    userStats = UserQuestionBriefStats(
                        row.attempts,
                        row.accuracyPct,
                        row.events.maxByOrNull { safeInstant(it.occurredAt) }?.occurredAt,
                        row.isMastered,
                        row.inErrorNotebook
                    )
                )
            } ?: q
        }
    }

    suspend fun fetchOrGetOfflineQuiz(userId: Long, mode: QuizMode, quantity: Int, contestId: String? = null, disciplina: String? = null, componentId: String? = null, syllabusTopicId: String? = null): List<QuestionDto> {
        owner(userId); val count = quantity.coerceIn(1, 120)
        val scope = PracticeScope.contest(mode, contestId)
        try {
            auth?.ensureSession(api, ownerUserId)
            val remote = objectivePracticeQuestions(api.generateQuizQuestions(QuizGeneratePayload(userId, mode, count, trunkFocusSlugs = focusedTrunkSlugs().toList(), contestId = scope, disciplina = disciplina, syllabusTopicId = syllabusTopicId, componentId = componentId)).map(QuestionCacheMapping::withRevision))
            require(componentId == null || remote.all { it.examComponentId == componentId }) { "O servidor precisa confirmar o componente selecionado. Atualize e tente novamente." }
            if (remote.isNotEmpty()) dao.insertQuestions(remote.map { QuestionCacheMapping.toEntity(it, json) })
            return decorateQuestions(remote)
        } catch (e: CancellationException) { throw e } catch (e: retrofit2.HttpException) {
            if (e.code() in listOf(400, 401, 403, 404, 409, 422)) {
                val detail = runCatching { Json.parseToJsonElement(e.response()?.errorBody()?.string().orEmpty()).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                throw IllegalStateException(detail?.removePrefix("Requisição inválida: ")?.take(600) ?: "Não foi possível abrir as questões (${e.code()}). Confira a conta e o filtro de estudo.")
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IllegalStateException("Não foi possível interpretar as questões enviadas pelo servidor. Atualize o aplicativo e tente novamente.", e)
        } catch (_: java.io.IOException) { }

        val blocked = metadata("cache_unavailable_ids")?.let { runCatching { json.decodeFromString<List<String>>(it).toSet() }.getOrDefault(emptySet()) }.orEmpty()
        val cachedDtos = dao.getCachedQuestions(null, 10000).map { QuestionCacheMapping.toDto(it, json) }
        val rows = projectedLearningForQuestions(cachedDtos.map { it.id })
        val all = cachedDtos.filter {
            it.id !in blocked &&
                QuestionCacheMapping.withinReviewDate(it, LocalDate.now(LearningProjection.studyZone)) &&
                it.correctAnswerIndices.size == 1 &&
                it.correctAnswerIndices[0] in it.answers.indices &&
                rows[it.id]?.practiceAvailable != false
        }
        val savedExam = loadStoredExams().firstOrNull { it.examId == scope }
        require(componentId == null || (mode == QuizMode.STANDARD && savedExam?.subjects?.any { it.localSubjectId == componentId } == true)) { "Componente não pertence ao concurso selecionado." }
        val exam = if (componentId == null) savedExam else savedExam?.copy(subjects = savedExam.subjects.filter { it.localSubjectId == componentId })
        val decisions = metadata("practice_decisions")?.let { runCatching { json.decodeFromString<List<PracticeDecision>>(it) }.getOrDefault(emptyList()) }.orEmpty()
        require(scope == null || exam != null) { "Concurso não disponível neste aparelho. Sincronize a escada e selecione novamente." }
        require(mode != QuizMode.SIMULADO || exam != null) { "Selecione um concurso não arquivado para o simulado." }
        require(mode == QuizMode.COMMON_TRUNK || exam?.lifecycleStatus != "ARQUIVADO") { "Concurso arquivado; revise sua seleção de estudo." }
        require(all.isNotEmpty()) { "Não foi possível recuperar questões do servidor e ainda não há questões válidas em cache. Verifique a conexão e tente novamente." }
        val topics = exam?.subjects?.flatMap { it.topics }?.filter { it.diffStatus != "ARQUIVADO" }?.map { it.canonicalTopicId }?.toSet()
        val commonTopics = if (mode == QuizMode.COMMON_TRUNK) loadStoredExams().filter { it.isActiveInLadder && it.lifecycleStatus != "ARQUIVADO" }.flatMap { it.subjects }.flatMap { it.topics }.filter { it.diffStatus != "ARQUIVADO" }.map { it.canonicalTopicId }.toSet() else emptySet()
        val boardFilter = exam?.examBoards.orEmpty().filter { it != "A definir" && it.isNotBlank() }.flatMap { if (it == "Cebraspe") listOf("Cebraspe", "CEBRASPE", "CESPE") else listOf(it) }.toSet()
        val pool = all.filter { mode != QuizMode.SIMULADO || boardFilter.isEmpty() || it.source in boardFilter }.filter { q ->
            (syllabusTopicId == null || q.canonicalTopicId == syllabusTopicId || syllabusTopicId in q.syllabusTopicIds) &&
                (disciplina == null || if (q.canonicalSubjectId != null) q.canonicalSubjectId == ExamCatalog.subjectIdForLabel(disciplina) else q.disciplina in ExamCatalog.equivalentLabels(disciplina)) &&
                if (mode == QuizMode.COMMON_TRUNK) q.canonicalTopicId in commonTopics else (exam == null || q.canonicalTopicId in topics.orEmpty() || (q.canonicalTopicId == null && exam.subjects.any { it.topics.any { t -> t.diffStatus != "ARQUIVADO" } && (it.displayName == q.disciplina || q.disciplina in ExamCatalog.legacyLabels(it.canonicalSubjectId)) }))
        }.filter { q -> exam == null || mode == QuizMode.SIMULADO || exam.subjects.any { s -> (q.canonicalTopicId == null || s.topics.any { it.diffStatus != "ARQUIVADO" && it.canonicalTopicId == q.canonicalTopicId }) && QuizSelection.allowed(decisions, exam, s, q.id, q.questionRevision) } }
        val selected = when (mode) {
            QuizMode.SRS_REVIEW -> pool.filter { rows[it.id]?.let { r -> r.practiceAvailable != false && (r.reviewRequired || safeInstant(r.nextReviewAt) <= Instant.now()) } == true }.sortedBy { rows[it.id]?.nextReviewAt }.take(count)
            QuizMode.ERROR_NOTEBOOK -> pool.filter { rows[it.id]?.let { r -> r.practiceAvailable != false && r.inErrorNotebook } == true }.take(count)
            QuizMode.MOST_BADASS -> pool.filter { rows[it.id]?.let { r -> r.practiceAvailable != false && !r.isMastered && r.attempts > 0 } == true }.sortedBy { rows[it.id]?.accuracyPct }.take(count)
            QuizMode.SIMULADO -> {
                if (exam == null) emptyList() else {
                    val ids = QuizSelection.weightedSimuladoIds(exam, pool.map { QuestionRef(it.id, it.canonicalSubjectId, it.canonicalTopicId, it.questionRevision) }, decisions)
                    val expected = exam.subjects.filter { it.topics.any { t -> t.diffStatus != "ARQUIVADO" } }.sumOf { it.questionCount ?: 0 }
                    require(expected > 0 && ids.size == expected) { "Cache insuficiente para o simulado: ${ids.size}/$expected questões. Conecte-se ou ajuste a grade." }
                    var offset = 0
                    exam.subjects.filter { it.topics.any { t -> t.diffStatus != "ARQUIVADO" } && (it.questionCount ?: 0) > 0 }.flatMap { subject ->
                        ids.drop(offset).take(subject.questionCount ?: 0).mapNotNull { id -> pool.find { it.id == id }?.copy(examComponentId = subject.localSubjectId) }.also { offset += subject.questionCount ?: 0 }
                    }
                }
            }
            QuizMode.COMMON_TRUNK -> { val exams = loadStoredExams(); val eligible = pool.filter { q -> exams.any { e -> e.isActiveInLadder && e.lifecycleStatus !in setOf("ARQUIVADO", "EXCLUIDO") && e.subjects.any { s -> s.topics.any { it.diffStatus != "ARQUIVADO" && it.canonicalTopicId == q.canonicalTopicId } && QuizSelection.allowed(decisions, e, s, q.id, q.questionRevision) } } }; val ids = QuizSelection.commonTrunkIds(exams, eligible.map { QuestionRef(it.id, it.canonicalSubjectId, it.canonicalTopicId) }, focusedTrunkSlugs(), canonicalMasteryBySubject(), LocalDate.now(LearningProjection.studyZone), count); ids.mapNotNull { id -> eligible.find { it.id == id } } }
            QuizMode.SINGLE -> emptyList()
            else -> pool.shuffled().take(count)
        }
        if (selected.isEmpty() && mode in listOf(QuizMode.SRS_REVIEW, QuizMode.ERROR_NOTEBOOK, QuizMode.MOST_BADASS)) {
            val allHistorical = projectedLearning()
            val expected = allHistorical.any { r -> r.practiceAvailable != false && (disciplina == null || r.disciplina in ExamCatalog.equivalentLabels(disciplina)) && when (mode) { QuizMode.SRS_REVIEW -> r.reviewRequired || safeInstant(r.nextReviewAt) <= Instant.now(); QuizMode.ERROR_NOTEBOOK -> r.inErrorNotebook; else -> !r.isMastered && r.attempts > 0 } }
            require(!expected) { "Seu histórico contém registros para este modo, mas as questões não estão disponíveis no cache. Conecte-se para recuperá-las." }
        }
        return objectivePracticeQuestions(decorateQuestions(if (componentId == null) selected else selected.map { it.copy(examComponentId = componentId) }))
    }

    suspend fun <T> withMutationLock(block: suspend () -> T): T = mutationMutex.withLock { block() }

    internal suspend fun recordAttemptUnlocked(userId: Long, attempt: PendingAttemptEntity, session: SessionSnapshot? = null) {
        owner(userId); require(attempt.ownerUserId == userId); require(attempt.questionRevision.isNotEmpty()) { "A versão desta questão precisa ser atualizada antes de responder." }
        transaction {
            val previous = dao.findPendingAttemptInSession(attempt.sessionUuid, attempt.questionId)
            if (previous != null) { require(previous.attemptId == attempt.attemptId && previous.chosenIndex == attempt.chosenIndex && previous.confidenceLevel == attempt.confidenceLevel) { "Resposta já registrada nesta sessão." }; return@transaction }
            val confirmed = learningMapForQuestions(listOf(attempt.questionId))[attempt.questionId]?.events?.any { it.attemptId == attempt.attemptId } == true
            if (!confirmed) dao.enqueuePendingAttempt(attempt)
            if (session != null) durable?.saveSession(session.sessionId, ownerUserId, json.encodeToString(session), session.revision, System.currentTimeMillis())
        }
    }

    suspend fun recordAttemptOfflineFirst(userId: Long, attempt: PendingAttemptEntity, session: SessionSnapshot? = null) {
        owner(userId); require(attempt.ownerUserId == userId); require(attempt.questionRevision.isNotEmpty()) { "A versão desta questão precisa ser atualizada antes de responder." }
        mutationMutex.withLock {
            recordAttemptUnlocked(userId, attempt, session)
        }
    }

    /**
     * Grava todo o lote final de respostas de um Simulado e atualiza a sessão de forma atômica
     * em uma única transação Room (`@Transaction`), impedindo perda parcial por navegação.
     */
    internal suspend fun recordSimuladoBatchUnlocked(
        userId: Long,
        attempts: List<PendingAttemptEntity>,
        session: SessionSnapshot
    ) {
        owner(userId)
        require(session.ownerUserId == userId)
        attempts.forEach { attempt ->
            require(attempt.ownerUserId == userId)
            require(attempt.questionRevision.isNotEmpty()) { "A versão desta questão precisa ser atualizada antes de responder." }
        }
        transaction {
            val existingLearning = learningMapForQuestions(attempts.map { it.questionId })
            for (attempt in attempts) {
                val previous = dao.findPendingAttemptInSession(attempt.sessionUuid, attempt.questionId)
                if (previous != null) {
                    require(previous.attemptId == attempt.attemptId && previous.chosenIndex == attempt.chosenIndex && previous.confidenceLevel == attempt.confidenceLevel) {
                        "Resposta já registrada nesta sessão."
                    }
                    continue
                }
                val confirmed = existingLearning[attempt.questionId]?.events?.any { it.attemptId == attempt.attemptId } == true
                if (!confirmed) {
                    dao.enqueuePendingAttempt(attempt)
                }
            }
            durable?.saveSession(session.sessionId, ownerUserId, json.encodeToString(session), session.revision, System.currentTimeMillis())
        }
    }

    suspend fun recordSimuladoBatchOfflineFirst(
        userId: Long,
        attempts: List<PendingAttemptEntity>,
        session: SessionSnapshot
    ) {
        mutationMutex.withLock {
            recordSimuladoBatchUnlocked(userId, attempts, session)
        }
    }

    /**
     * Sincroniza preferências pendentes de perfil com isolamento de falhas para que um erro de perfil
     * jamais aborte o envio das tentativas de estudo (`submitAttemptsBatch`).
     * Limpa a flag suja em respostas terminais `400`/`404`/`422` e agenda retry apenas em erros transitórios.
     */
    private suspend fun syncPendingProfile(userId: Long): Boolean {
        val raw = (auth?.pendingProfile(userId) ?: metadata("pending_profile"))?.takeIf { it.isNotBlank() }
            ?: return false
        val profile = runCatching { json.decodeFromString<OnboardingPayload>(raw) }.getOrNull()
        if (profile == null) {
            auth?.clearPendingProfile(userId, raw)
            metadata("pending_profile", "")
            return false
        }
        if (profile.userId != userId) {
            if (auth?.pendingProfile(userId) == null) {
                metadata("pending_profile", "")
            }
            return false
        }
        return try {
            api.saveOnboardingPreferences(profile)
            auth?.clearPendingProfile(userId, raw)
            metadata("pending_profile", "")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: retrofit2.HttpException) {
            when {
                e.code() == 401 -> throw e
                e.code() in listOf(400, 404, 422) -> {
                    auth?.clearPendingProfile(userId, raw)
                    metadata("pending_profile", "")
                    false
                }
                else -> true
            }
        } catch (_: Exception) {
            true
        }
    }

    suspend fun syncPendingAttempts(userId: Long): Int = syncMutex.withLock {
        owner(userId)
        try {
            auth?.ensureSession(api, userId)
            dao.bindLegacyOwner(userId)
            val examSyncCount = syncPendingExams()
            val profileTransient = syncPendingProfile(userId)
            var synced = examSyncCount
            var transient = profileTransient || examDao.listPendingExams().any { it.syncStatus in setOf("PENDING_CREATE", "PENDING_UPDATE", "PENDING_PUBLISH") && it.syncError == null }
            var batchCount = 0
            while (batchCount++ < 20) {
                val pending = dao.getAllPendingAttempts().filter { it.ownerUserId == userId && it.syncError == null }.take(120)
                if (pending.isEmpty()) break
                val payload = SubmitBatchPayload(userId, pending.map { it.payload() })
                val response = api.submitAttemptsBatch(payload)
                val byId = pending.associateBy { it.attemptId }
                require(response.results.map { it.attemptId }.distinct().size == response.results.size && response.results.all { byId[it.attemptId]?.questionId == it.questionId }) { "Recibos inválidos; fila preservada." }
                val ackedQuestionIds = response.results.map { byId.getValue(it.attemptId).questionId }
                mutationMutex.withLock {
                    transaction {
                        val questionsById = cachedQuestionsMap(ackedQuestionIds)
                        val learningById = learningMapForQuestions(ackedQuestionIds).toMutableMap()
                        val updatedLearning = mutableListOf<LearningRecordEntity>()
                        for (ack in response.results) {
                            val local = byId.getValue(ack.attemptId)
                            val question = questionsById[local.questionId]
                            if (question != null && durable != null) {
                                val base = learningById[local.questionId]
                                val projected = if (base != null && base.attempts >= ack.attemptsTotal) {
                                    base
                                } else {
                                    LearningProjection.project(base, question, listOf(local.event().copy(isCorrect = ack.isCorrect))).copy(
                                        isMastered = ack.isMastered,
                                        inErrorNotebook = ack.inErrorNotebook,
                                        accuracyPct = ack.accuracyAccumulatedPct,
                                        points = ack.pointsTotal,
                                        nextReviewAt = ack.nextReviewAt,
                                        attempts = ack.attemptsTotal,
                                        correct = ack.correctTotal,
                                        confidenceAvg = ack.confidenceAvg,
                                        canonicalSubjectId = ack.canonicalSubjectId ?: base?.canonicalSubjectId ?: question.canonicalSubjectId,
                                        canonicalTopicId = ack.canonicalTopicId ?: base?.canonicalTopicId ?: question.canonicalTopicId
                                    )
                                }
                                learningById[local.questionId] = projected
                                updatedLearning += LearningRecordEntity(local.questionId, projected.revision, json.encodeToString(projected))
                            }
                            dao.updateLearningFlags(local.questionId, ack.inErrorNotebook, ack.isMastered)
                            dao.acknowledgeAttempt(ack.attemptId, userId)
                            synced++
                        }
                        if (updatedLearning.isNotEmpty() && durable != null) {
                            durable.putLearning(updatedLearning)
                        }
                        response.rejected.forEach { rejection ->
                            if (byId[rejection.attemptId]?.questionId == rejection.questionId) {
                                if (rejection.retryable) transient = true else dao.markAttemptError(rejection.attemptId, userId, rejection.message)
                            }
                        }
                    }
                }
                val accounted = (response.results.map { it.attemptId } + response.rejected.map { it.attemptId }).toSet()
                if (pending.any { it.attemptId !in accounted }) transient = true
                if (transient) break
            }
            if (dao.getAllPendingAttempts().any { it.ownerUserId == userId && it.syncError == null }) transient = true
            durable?.reports(userId)?.forEach { report ->
                if (metadata("report_error:${report.eventId}").isNullOrBlank()) {
                    try {
                        api.reportQuestionProblem(report.questionId, ReportProblemPayload(userId, report.category))
                        durable.acknowledgeReport(report.eventId, userId)
                        synced++
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() in listOf(400, 404, 409, 422)) metadata("report_error:${report.eventId}", "Reporte exige revisão (HTTP ${e.code()}); conteúdo preservado.")
                        else throw e
                    }
                }
            }
            runCatching { api.profile() }.onSuccess { auth?.saveProfile(it) }
            pullExams()
            if (durable != null) {
                var cursor: String? = null
                do {
                    val page = api.learningState(cursor)
                    mutationMutex.withLock {
                        transaction {
                            val current = learningMapForQuestions(page.records.map { it.questionId })
                            val accepted = page.records.filter { incoming -> (current[incoming.questionId]?.attempts ?: 0) <= incoming.attempts }
                            if (accepted.isNotEmpty()) {
                                durable.putLearning(accepted.map { LearningRecordEntity(it.questionId, it.revision, json.encodeToString(it)) })
                                accepted.forEach { dao.updateLearningFlags(it.questionId, it.inErrorNotebook, it.isMastered) }
                            }
                        }
                    }
                    cursor = page.nextCursor
                } while (cursor != null)
            }
            refreshQuestionCache()
            metadata("last_sync", Instant.now().toString())
            metadata("sync_error", if (transient) "Há tentativas aguardando confirmação do servidor." else "")
            if (transient) error("Há tentativas aguardando confirmação do servidor.")
            synced
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is retrofit2.HttpException && e.code() == 401) auth?.invalidate(userId)
            metadata("sync_error", "Sincronização pendente. Seus registros continuam neste aparelho.")
            throw e
        }
    }

    private fun PendingAttemptEntity.payload() = AnswerAttemptPayload(attemptId, Instant.ofEpochMilli(createdAtEpochMs).toString(), questionRevision, questionId, chosenIndex, chosenText, isCorrect, timeSpentSeconds, confidenceLevel, sessionUuid, quizMode, examBlock, contestId, userNote, canonicalSubjectId, canonicalTopicId, examComponentId)

    private suspend fun refreshQuestionCache() {
        val blocked = metadata("cache_unavailable_ids")?.let { runCatching { json.decodeFromString<List<String>>(it).toMutableSet() }.getOrDefault(mutableSetOf()) } ?: mutableSetOf()
        for (ids in dao.getCachedQuestions(null, 10000).map { it.id }.chunked(200)) {
            val response = api.cacheState(CacheStatePayload(ids))
            require(response.projectionVersion == 1 && response.questions.map { it.questionId }.toSet() == ids.toSet()) { "Resposta de cache incompleta; estado anterior preservado." }
            mutationMutex.withLock {
                transaction {
                    response.questions.forEach { row ->
                        if (row.question == null) blocked += row.questionId else {
                            require(row.question.id == row.questionId)
                            blocked -= row.questionId
                            dao.insertQuestions(listOf(QuestionCacheMapping.toEntity(row.question, json)))
                        }
                    }
                    metadata("cache_unavailable_ids", json.encodeToString(blocked.toList()))
                    val prior = metadata("practice_decisions")?.let { runCatching { json.decodeFromString<List<PracticeDecision>>(it) }.getOrDefault(emptyList()) }.orEmpty()
                    metadata("practice_decisions", json.encodeToString(prior.filter { it.questionId !in ids } + response.decisions))
                }
            }
        }
    }

    private suspend fun syncPendingExams(): Int {
        var count = 0
        for (entity in examDao.listPendingExams()) {
            if (entity.syncStatus !in setOf("PENDING_CREATE", "PENDING_UPDATE", "PENDING_PUBLISH")) continue
            if (entity.syncError?.let { it.startsWith("Conflito de revisão") || it.startsWith("Revisão necessária") } == true) continue
            val snapshot = ExamLocalMapping.assemble(entity, examDao.subjectsFor(entity.examId), examDao.topicsFor(entity.examId))
            try {
                val result = when (snapshot.syncStatus) {
                    "PENDING_CREATE" -> api.createExam(snapshot.toPayload())
                    "PENDING_PUBLISH" -> api.publishEdital(snapshot.examId, PublishEditalPayload(snapshot.revision, snapshot.subjects.flatMap { it.topics }.filter { it.diffStatus != "ARQUIVADO" }.map { PublishedTopicPayload(it.canonicalTopicId, it.canonicalSubjectId, it.displayTitle) }))
                    else -> try {
                        api.updateExam(snapshot.examId, snapshot.toPayload())
                    } catch (conflict: retrofit2.HttpException) {
                        if (snapshot.lifecycleStatus == "EXCLUIDO" && conflict.code() == 409) {
                            val remoteRev = api.listExams().find { it.id == snapshot.examId }?.revision
                            if (remoteRev != null) api.updateExam(snapshot.examId, snapshot.copy(revision = remoteRev).toPayload())
                            else throw conflict
                        } else if (snapshot.lifecycleStatus == "EXCLUIDO" && conflict.code() == 404) {
                            snapshot.copy(syncStatus = "SYNCED", syncError = null).toPayload()
                        } else {
                            throw conflict
                        }
                    }
                }
                mutationMutex.withLock {
                    val current = examDao.listExams().find { it.examId == snapshot.examId }?.let { ExamLocalMapping.assemble(it, examDao.subjectsFor(it.examId), examDao.topicsFor(it.examId)) }
                    if (current == snapshot) { saveExamAggregate(result.toStored()); count++ }
                    else if (current != null) saveExamAggregate(current.copy(revision = result.revision, syncStatus = "PENDING_UPDATE"))
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val permanent = e is retrofit2.HttpException && e.code() in listOf(400, 404, 409, 422)
                val reason = if (e is retrofit2.HttpException && e.code() == 409) "Conflito de revisão: preserve sua edição antes de carregar a versão do servidor." else if (permanent) "Revisão necessária: o servidor recusou a grade. Confira seus campos e salve uma nova edição." else "Envio pendente; edição preservada."
                examDao.setSyncError(snapshot.examId, reason)
                if (!permanent) throw e
            }
        }
        return count
    }

    suspend fun pullExams() {
        val incoming = api.listExams()
        mutationMutex.withLock {
            val local = examDao.listExams().associateBy { it.examId }
            incoming.forEach { remote ->
                val current = local[remote.id]
                if (current == null || (current.syncStatus == "SYNCED" && remote.revision >= current.revision)) saveExamAggregate(remote.toStored())
                else if (current.lifecycleStatus != "EXCLUIDO" && current.revision != remote.revision && current.syncError == null) examDao.setSyncError(remote.id, "Conflito de revisão: há uma versão diferente no servidor. Preserve sua edição antes de carregá-la.")
            }
        }
        val catalog = api.catalog(); ExamCatalog.installCatalog(catalog); metadata("catalog", catalog.toString())
        val knowledge = api.knowledgeCatalog(); ExamCatalog.installKnowledge(knowledge); metadata("knowledge_catalog", knowledge.toString())
    }

    suspend fun reportQuestionProblem(userId: Long, questionId: String, category: String): Boolean {
        owner(userId); require(category in VALID_PROBLEM_CATEGORIES)
        val queue = durable ?: return false
        val id = UUID.nameUUIDFromBytes("$userId:$questionId:$category".toByteArray()).toString()
        queue.enqueueReport(PendingReportEntity(id, userId, questionId, category))
        return true
    }

    suspend fun requestAiTutor(questionId: String, kind: String, hintIndex: Int? = null): String {
        return try { auth?.ensureSession(api, ownerUserId); api.requestAiTutor(questionId, kind, hintIndex).contentMarkdown }
        catch (e: CancellationException) { throw e } catch (e: retrofit2.HttpException) {
            val detail = e.backendMessage()
            val reason = detail ?: "Serviço de IA indisponível no momento (HTTP ${e.code()})."
            if (kind in listOf("contextual-hint", "child-friendly-pre")) "Tutor indisponível ($reason).\n\nA dica não foi gerada; sua resposta continua sem interferência."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário já disponível ($reason):\n\n$it" } ?: "Tutor indisponível: $reason"
        } catch (_: java.io.IOException) {
            if (kind in listOf("contextual-hint", "child-friendly-pre")) "Tutor indisponível. A dica não foi gerada; sua resposta continua sem interferência."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário já disponível (tutor indisponível):\n\n$it" } ?: "Tutor indisponível. Tente novamente quando houver conexão."
        } catch (_: Exception) {
            if (kind in listOf("contextual-hint", "child-friendly-pre")) "Tutor indisponível. Não foi possível processar a dica; sua resposta continua sem interferência."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário já disponível (tutor indisponível):\n\n$it" } ?: "Tutor indisponível. Não foi possível processar a resposta do serviço. Tente novamente."
        }
    }

    suspend fun pendingReview(): List<PendingReviewItem> {
        val rawAttempts = dao.getAllPendingAttempts().filter { it.ownerUserId == ownerUserId }
        val questionsById = cachedQuestionsMap(rawAttempts.map { it.questionId })
        val attempts = rawAttempts.map { item ->
            PendingReviewItem(
                id = item.attemptId,
                title = questionsById[item.questionId]?.question ?: "Questão ${item.questionId}",
                detail = "Resposta: ${item.chosenText} · ${Instant.ofEpochMilli(item.createdAtEpochMs)}",
                status = item.syncError ?: "Aguardando confirmação do servidor",
                canDismiss = item.syncError != null
            )
        }
        val exams = loadStoredExams(includeDeleted = true)
            .filter { it.syncStatus != "SYNCED" }
            .map { PendingReviewItem(it.examId, it.title, "Concurso · revisão ${it.revision}", it.syncError ?: "Edição aguardando confirmação", canDismiss = false) }
        val backups = durable?.preservedExamEdits().orEmpty().mapNotNull { item ->
            runCatching { json.decodeFromString<StoredExam>(item.value) }.getOrNull()?.let { exam ->
                PendingReviewItem(item.key, exam.title, "Edição preservada antes de usar a versão do servidor", "Disponível na exportação; não será reenviada automaticamente", canDismiss = true)
            }
        }
        val reports = durable?.reports(ownerUserId).orEmpty().map { item ->
            val err = metadata("report_error:${item.eventId}")
            PendingReviewItem(
                id = item.eventId,
                title = "Reporte de questão",
                detail = item.category,
                status = err ?: "Aguardando confirmação do servidor",
                canDismiss = !err.isNullOrBlank()
            )
        }
        return attempts + exams + backups + reports
    }

    suspend fun dismissQuarantinedAttempt(attemptId: String): Boolean = mutationMutex.withLock {
        dao.dismissQuarantinedAttempt(attemptId, ownerUserId) > 0
    }

    suspend fun dismissQuarantinedItem(itemId: String) = mutationMutex.withLock {
        transaction {
            if (itemId.startsWith("exam_backup:")) {
                durable?.deleteMetadata(itemId)
                return@transaction
            }
            val removedAttempt = dao.dismissQuarantinedAttempt(itemId, ownerUserId)
            if (removedAttempt == 0 && !metadata("report_error:$itemId").isNullOrBlank()) {
                durable?.acknowledgeReport(itemId, ownerUserId)
                durable?.deleteMetadata("report_error:$itemId")
            }
        }
    }

    suspend fun clearQuarantinedAttempts(): Int = mutationMutex.withLock {
        transaction {
            var removed = dao.clearQuarantinedAttempts(ownerUserId)
            val state = durable
            if (state != null) {
                state.reports(ownerUserId).forEach { report ->
                    if (!metadata("report_error:${report.eventId}").isNullOrBlank()) {
                        state.acknowledgeReport(report.eventId, ownerUserId)
                        state.deleteMetadata("report_error:${report.eventId}")
                        removed++
                    }
                }
            }
            removed
        }
    }

    suspend fun preserveAndUseServerExam(expected: StoredExam, directory: File): File {
        auth?.ensureSession(api, ownerUserId)
        val remote = api.listExams().find { it.id == expected.examId } ?: error("A versão do servidor não está disponível; sua edição foi preservada.")
        mutationMutex.withLock {
            transaction {
                val entity = examDao.listExams().find { it.examId == expected.examId } ?: error("O concurso mudou; reabra a revisão.")
                val current = ExamLocalMapping.assemble(entity, examDao.subjectsFor(entity.examId), examDao.topicsFor(entity.examId))
                require(current == expected) { "A edição mudou enquanto a versão era consultada. Reabra a revisão." }
                checkNotNull(durable).putMetadata(LocalMetadataEntity("exam_backup:${expected.examId}:${UUID.randomUUID()}", json.encodeToString(current)))
                saveExamAggregate(remote.toStored())
            }
        }
        return exportPending(directory)
    }

    suspend fun exportPending(directory: File): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val attempts = dao.getAllPendingAttempts().filter { it.ownerUserId == ownerUserId }
        val exams = loadStoredExams(includeDeleted = true).filter { it.syncStatus != "SYNCED" }
        val document = buildJsonObject {
            put("schema_version", 2); put("owner_user_id", ownerUserId)
            put("exams", json.encodeToJsonElement(exams))
            put("preserved_exam_edits", buildJsonArray { durable?.preservedExamEdits().orEmpty().forEach { runCatching { Json.parseToJsonElement(it.value) }.getOrNull()?.let(::add) } })
            put("attempts", buildJsonArray { attempts.forEach { item -> add(buildJsonObject { put("payload", json.encodeToJsonElement(item.payload())); put("review_reason", item.syncError); put("local_created_at_ms", item.createdAtEpochMs) }) } })
            put("reports", buildJsonArray { durable?.reports(ownerUserId).orEmpty().forEach { item -> add(buildJsonObject { put("event_id", item.eventId); put("question_id", item.questionId); put("category", item.category) }) } })
        }
        File(directory, "cogniva-preservados-${System.currentTimeMillis()}.json").also { it.writeText(document.toString(), Charsets.UTF_8) }
    }

    suspend fun getLocalStorageStats(): Pair<Int, Int> =
        dao.countCachedQuestions() to (dao.getAllPendingAttempts().count { it.ownerUserId == ownerUserId } + (durable?.reports(ownerUserId)?.size ?: 0) + examDao.listPendingExams().size)

    suspend fun clearLocalQuestionCache() {
        val hasActiveSession = durable?.pausedSession(ownerUserId) != null
        val hasUnsyncedPending = dao.getAllPendingAttempts().any {
            it.ownerUserId == ownerUserId && it.syncError == null
        }
        require(!hasActiveSession && !hasUnsyncedPending) {
            "Conclua a sessão ativa e sincronize as respostas pendentes antes de limpar as questões."
        }
        dao.clearCachedQuestions()
    }

    suspend fun createSession(session: SessionSnapshot) {
        require(session.ownerUserId == ownerUserId)
        transaction {
            durable?.deactivateOtherSessions(ownerUserId, session.sessionId)
            durable?.createSession(QuizSessionEntity(session.sessionId, ownerUserId, json.encodeToString(session), session.revision))
        }
    }

    suspend fun saveSession(session: SessionSnapshot) {
        require(session.ownerUserId == ownerUserId)
        durable?.saveSession(session.sessionId, ownerUserId, json.encodeToString(session), session.revision, System.currentTimeMillis())
    }

    suspend fun pausedSession(): SessionSnapshot? =
        durable?.pausedSession(ownerUserId)?.let { entity ->
            runCatching { json.decodeFromString<SessionSnapshot>(entity.payloadJson) }.getOrNull()
        }

    suspend fun completeSession(id: String) {
        durable?.completeSession(id, ownerUserId)
    }

    suspend fun fetchRemoteApkInfo(): ApkInfoDto? = try { api.getApkInfo() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    suspend fun downloadApkUpdate(targetDir: File, expectedInfo: ApkInfoDto? = null): File? = withContext(Dispatchers.IO) {
        val info = expectedInfo ?: fetchRemoteApkInfo() ?: return@withContext null
        val validated = AppUpdateManager.validateMetadata(info, BuildConfig.VERSION_CODE)
        val downloadUrl = AppUpdateManager.resolveDownloadUrl(BuildConfig.API_BASE_URL, validated.downloadPath)
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        targetDir.mkdirs()
        val destination = File(targetDir, "cogniva-${validated.versionCode}.apk")
        try {
            client.newCall(Request.Builder().url(downloadUrl).header("Accept-Encoding", "identity").build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                body.byteStream().use { input ->
                    AppUpdateManager.downloadVerifyAndStreamToSink(
                        source = input,
                        validated = validated,
                        updatesDir = targetDir,
                        sinkOpener = { FileOutputStream(destination) }
                    )
                }
            }
            destination
        } catch (e: java.io.IOException) {
            runCatching { destination.delete() }
            throw java.io.IOException("Falha de conexão durante o download da atualização. Tente novamente.", e)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}

fun StoredExam.toPayload(): ExamBlueprintPayload = ExamBlueprintPayload(
    id = examId, revision = revision, wrongAnswerPenalty = wrongAnswerPenalty, title = title, shortName = shortName, institution = institution, role = role, trunkSlug = trunkSlug, status = lifecycleStatus, examBoards = examBoards,
    targetDate = when (dateType) { "EXACT_DATE" -> buildJsonObject { put("type", "EXACT_DATE"); put("iso_date", exactDateIso.orEmpty()) }; "ESTIMATED_WINDOW" -> buildJsonObject { put("type", "ESTIMATED_WINDOW"); put("label", estimatedWindowLabel.orEmpty()); put("estimated_sort_date", estimatedSortDateIso) }; else -> buildJsonObject { put("type", "UNDEFINED") } },
    historicalCutoffScore = historicalCutoffScore, isActiveInLadder = isActiveInLadder, ladderPriority = ladderPriority, syncStatus = syncStatus,
    templateSourceId = templateSourceId, templateRevisionSynced = templateRevisionSynced, isCustomized = isCustomized, upstreamUpdateAvailable = upstreamUpdateAvailable,
    subjects = subjects.map { s -> ExamSubjectPayload(s.localSubjectId, s.canonicalSubjectId, s.displayName, s.category, s.weight, s.questionCount, s.relevanceTier, s.topics.map { ExamTopicPayload(it.localTopicId, it.canonicalTopicId, it.canonicalSubjectId, it.displayTitle, it.diffStatus) }, s.stage) }
)

fun ExamBlueprintPayload.toStored(): StoredExam = StoredExam(
    examId = id, title = title, shortName = shortName, institution = institution, role = role, trunkSlug = trunkSlug, lifecycleStatus = status, examBoards = examBoards, dateType = targetDate["type"]?.jsonPrimitive?.content ?: "UNDEFINED", exactDateIso = targetDate["iso_date"]?.jsonPrimitive?.contentOrNull, estimatedWindowLabel = targetDate["label"]?.jsonPrimitive?.contentOrNull, estimatedSortDateIso = targetDate["estimated_sort_date"]?.jsonPrimitive?.contentOrNull ?: targetDate["iso_date"]?.jsonPrimitive?.contentOrNull.orEmpty(), historicalCutoffScore = historicalCutoffScore, isActiveInLadder = isActiveInLadder, ladderPriority = ladderPriority, syncStatus = "SYNCED", revision = revision, wrongAnswerPenalty = wrongAnswerPenalty,
    templateSourceId = templateSourceId, templateRevisionSynced = templateRevisionSynced, isCustomized = isCustomized, upstreamUpdateAvailable = upstreamUpdateAvailable,
    confirmedTopicsCount = subjects.sumOf { it.topics.count { t -> t.diffStatus == "OFICIAL_CONFIRMADO" } }, newTopicsCount = subjects.sumOf { it.topics.count { t -> t.diffStatus == "OFICIAL_NOVO" } }, archivedTopicsCount = subjects.sumOf { it.topics.count { t -> t.diffStatus == "ARQUIVADO" } },
    subjects = subjects.map { s -> StoredSubject(s.localSubjectId, s.canonicalSubjectId, s.displayName, s.category, s.weight, s.questionCount, s.relevanceTier, s.topics.map { StoredTopic(it.localTopicId, it.canonicalTopicId, it.canonicalSubjectId, it.displayTitle, it.diffStatus) }, s.stage) }
)

private fun StoredSubject.groupLabel(): String = listOf(stage.takeIf { it.isNotBlank() }, when (category) {
    "CONHECIMENTOS_GERAIS" -> "Conhecimentos gerais"
    "CONHECIMENTOS_ESPECIFICOS" -> "Conhecimentos específicos"
    "DISCURSIVA_ESTUDO_DE_CASO" -> "Discursiva / estudo de caso"
    else -> "Outros"
}).filterNotNull().joinToString(" · ")
