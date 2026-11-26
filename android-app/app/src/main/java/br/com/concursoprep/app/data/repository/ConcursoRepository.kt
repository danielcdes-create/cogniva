package br.com.concursoprep.app.data.repository

import androidx.room.withTransaction
import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.data.AuthStore
import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.data.remote.*
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.missions.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
private data class PendingEvidencePatch(
    val ownerUserId: Long,
    val attemptId: String,
    val questionId: String,
    val selectedEvidenceId: String
)

@Serializable
private data class PendingCtvSubmission(
    val ownerUserId: Long,
    val request: CtvSubmitRequestDto
)

@Serializable
private data class LocalCtvOfferEntry(
    val offerId: String,
    val parentAttemptId: String,
    val sourceQuestionId: String,
    val complementaryQuestionId: String,
    val contentRevision: String = "",
    val canonicalTopicId: String? = null,
    val targetConceptId: String? = null,
    val targetTestedRule: String? = null,
    val framingReasonCode: String? = null,
    val verificationMode: String = "CONCEPTUAL_TRANSFER",
    val triggerReason: String? = null,
    val triggerCohort: String = "DIFFICULTY_DETECTED",
    val deliverySource: String? = null,
    val state: String = "SUPPLIED",
    val suppliedAt: String = "",
    val firstDisplayedAt: String? = null,
    val dismissedAt: String? = null
)

@Serializable
private data class LocalCtvAttemptEntry(
    val ctvAttemptId: String,
    val offerId: String = "",
    val parentAttemptId: String,
    val sourceQuestionId: String,
    val complementaryQuestionId: String,
    val contentRevision: String = "",
    val canonicalTopicId: String? = null,
    val targetConceptId: String? = null,
    val targetTestedRule: String? = null,
    val verificationMode: String = "CONCEPTUAL_TRANSFER",
    val verificationTiming: String = "PRE_FEEDBACK",
    val isCorrect: Boolean,
    val wasPreviouslyExposed: Boolean = false,
    val hadDecisiveHelp: Boolean = false,
    val countsAsIndependentAttempt: Boolean = false,
    val countsAsIndependentCorrect: Boolean = false,
    val classificationCode: String = "",
    val triggerReason: String? = null,
    val triggerCohort: String = "DIFFICULTY_DETECTED",
    val occurredAt: String = "",
    val provisional: Boolean = true
)

@Serializable
private data class LocalCtvLedger(
    val ownerUserId: Long = 0L,
    val offers: Map<String, LocalCtvOfferEntry> = emptyMap(),
    val attempts: Map<String, LocalCtvAttemptEntry> = emptyMap(),
    val pendingEvents: List<CtvLifecycleEventRequestDto> = emptyList(),
    val pendingRecommendationFeedback: List<RecommendationFeedbackRequestDto> = emptyList()
)

@Serializable
private data class LocalEntryTelemetry(
    val impressionsCount: Int = 0,
    val lastImpressionDateIso: String? = null,
    val voluntaryStartsCount: Int = 0,
    val firstQuestionCompletedCount: Int = 0,
    val consciousRestDates: Set<String> = emptySet()
)

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

        fun deterministicAttemptId(sessionUuid: String, questionId: String): String =
            UUID.nameUUIDFromBytes("$sessionUuid:$questionId".toByteArray(Charsets.UTF_8)).toString()

        fun deterministicOfferId(userId: Long, parentAttemptId: String, complementaryQuestionId: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("ctv-offer-v1:$userId:$parentAttemptId:$complementaryQuestionId".toByteArray(Charsets.UTF_8))
            val hex = digest.take(16).joinToString("") { "%02x".format(it) }
            return "ctvo_$hex"
        }
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
    private val videoCacheMutex = Mutex()
    private fun apiUserId(userId: Long): Long = if (userId > 0L) userId else 0L
    suspend fun awaitIdleForClose() {
        syncMutex.withLock {
            mutationMutex.withLock { }
        }
        videoCacheMutex.withLock { }
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
                durable?.deleteSessionsForOwner(ownerUserId)
                durable?.clearAllLearning()
                durable?.clearAllMetadata()
                dao.clearCachedQuestions()
                examDao.clearAllTopics()
                examDao.clearAllSubjects()
                examDao.clearAllExams()
                examDao.clearAllMastery()
                examDao.clearAllTrunks()
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
            auth?.let { if (userId == ownerUserId) runCatching { it.ensureSession(api, ownerUserId) } }
            val remote = api.saveOnboardingPreferences(payload.copy(userId = apiUserId(userId))).also {
                auth?.clearPendingProfile(userId, serialized)
                if (userId == ownerUserId) metadata("pending_profile", "")
            }
            if (remote.userId == 0L && userId != 0L) remote.copy(userId = userId) else remote
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
        return transaction {
            examDao.listExams()
                .map { ExamLocalMapping.assemble(it, examDao.subjectsFor(it.examId), examDao.topicsFor(it.examId)) }
                .filter { includeDeleted || it.lifecycleStatus != "EXCLUIDO" }
        }
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

    private fun parseDeclaredConfidence(raw: String?): DeclaredConfidence? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { DeclaredConfidence.valueOf(it) }.getOrNull() }

    private fun parseCognitiveStrategy(raw: String?): CognitiveStrategy? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { CognitiveStrategy.valueOf(it) }.getOrNull() }

    private fun parseConsultedResources(csv: String?): List<String> =
        csv?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun PendingAttemptEntity.event() = LearningEvent(
        attemptId = attemptId,
        occurredAt = Instant.ofEpochMilli(createdAtEpochMs).toString(),
        isCorrect = isCorrect,
        confidenceLevel = confidenceLevel,
        timeSpentSeconds = timeSpentSeconds,
        questionRevision = questionRevision,
        isBlank = isBlank,
        selectedEvidenceId = selectedEvidenceId,
        quizMode = quizMode,
        sessionUuid = sessionUuid,
        elapsedSessionSeconds = elapsedSessionSeconds,
        sessionInterrupted = sessionInterrupted,
        declaredConfidence = parseDeclaredConfidence(declaredConfidence),
        cognitiveStrategy = parseCognitiveStrategy(cognitiveStrategy),
        strategySkipped = strategySkipped,
        activeSeconds = activeSeconds,
        answerChangeCount = answerChangeCount,
        initialChosenIndex = initialChosenIndex,
        consultedResources = parseConsultedResources(consultedResourcesCsv),
        focusLossCount = focusLossCount
    )

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

    private suspend fun loadEntryTelemetry(userId: Long): LocalEntryTelemetry =
        metadata("entry_60s_telemetry:$userId")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<LocalEntryTelemetry>(it) }.getOrNull() }
            ?: LocalEntryTelemetry()

    private suspend fun saveEntryTelemetry(userId: Long, state: LocalEntryTelemetry) {
        metadata("entry_60s_telemetry:$userId", json.encodeToString(state))
    }

    suspend fun recordEntryScreenImpression(userId: Long) {
        owner(userId)
        mutationMutex.withLock {
            val today = LocalDate.now(LearningProjection.studyZone).toString()
            val cur = loadEntryTelemetry(userId)
            if (cur.lastImpressionDateIso != today) {
                saveEntryTelemetry(
                    userId,
                    cur.copy(
                        impressionsCount = cur.impressionsCount + 1,
                        lastImpressionDateIso = today
                    )
                )
            }
        }
    }

    suspend fun recordEntryVoluntaryStart(userId: Long) {
        owner(userId)
        mutationMutex.withLock {
            val today = LocalDate.now(LearningProjection.studyZone).toString()
            val cur = loadEntryTelemetry(userId)
            val baseImpressions = maxOf(cur.impressionsCount, cur.voluntaryStartsCount + 1)
            saveEntryTelemetry(
                userId,
                cur.copy(
                    impressionsCount = baseImpressions,
                    voluntaryStartsCount = cur.voluntaryStartsCount + 1,
                    consciousRestDates = cur.consciousRestDates - today
                )
            )
        }
    }

    suspend fun recordEntryFirstQuestionCompleted(userId: Long) {
        owner(userId)
        mutationMutex.withLock {
            val cur = loadEntryTelemetry(userId)
            saveEntryTelemetry(
                userId,
                cur.copy(
                    firstQuestionCompletedCount = cur.firstQuestionCompletedCount + 1,
                    voluntaryStartsCount = maxOf(cur.voluntaryStartsCount, cur.firstQuestionCompletedCount + 1),
                    impressionsCount = maxOf(cur.impressionsCount, cur.firstQuestionCompletedCount + 1)
                )
            )
        }
    }

    suspend fun setConsciousRestToday(userId: Long, rested: Boolean): EntryProtocolMetricsDto {
        owner(userId)
        mutationMutex.withLock {
            val today = LocalDate.now(LearningProjection.studyZone).toString()
            val cur = loadEntryTelemetry(userId)
            val updatedDates = if (rested) cur.consciousRestDates + today else cur.consciousRestDates - today
            saveEntryTelemetry(
                userId,
                cur.copy(
                    impressionsCount = maxOf(cur.impressionsCount, 1),
                    consciousRestDates = updatedDates
                )
            )
        }
        return buildEntryProtocolMetrics(userId, projectedLearning())
    }

    private suspend fun buildEntryProtocolMetrics(userId: Long, rows: List<LearningRecord>): EntryProtocolMetricsDto {
        val today = LocalDate.now(LearningProjection.studyZone).toString()
        val local = loadEntryTelemetry(userId)
        val studyDates = rows.asSequence()
            .flatMap { it.events.asSequence() }
            .filter { !it.isBlank }
            .map { safeInstant(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate() }
            .distinct()
            .sorted()
            .toList()
        val activeDays = studyDates.size
        val returnDays = (activeDays - 1).coerceAtLeast(0)
        return EntryProtocolMetricsDto(
            impressionsCount = local.impressionsCount,
            voluntaryStartsCount = local.voluntaryStartsCount,
            firstQuestionCompletedCount = local.firstQuestionCompletedCount,
            consciousRestDaysCount = local.consciousRestDates.size,
            spontaneousReturnDaysCount = returnDays,
            activeStudyDaysCount = activeDays,
            restedToday = today in local.consciousRestDates
        )
    }

    private fun normalizeBoardKey(raw: String): String {
        val norm = raw.trim().lowercase()
        return when {
            norm.isEmpty() || norm == "a definir" || norm == "origem não informada" -> ""
            norm.contains("cebraspe") || norm.contains("cespe") -> "cebraspe"
            norm.contains("fgv") || norm.contains("getulio") || norm.contains("getúlio") -> "fgv"
            norm.contains("fcc") || norm.contains("carlos chagas") -> "fcc"
            norm.contains("cesgranrio") -> "cesgranrio"
            norm.contains("vunesp") -> "vunesp"
            else -> norm
        }
    }

    private suspend fun loadCtvLedger(userId: Long): LocalCtvLedger {
        val raw = metadata("ctv_ledger_v2:$userId")?.takeIf { it.isNotBlank() }
            ?: return LocalCtvLedger(ownerUserId = userId)
        val parsed = runCatching { json.decodeFromString<LocalCtvLedger>(raw) }.getOrNull()
        return if (parsed != null && (parsed.ownerUserId == userId || parsed.ownerUserId == 0L)) {
            parsed.copy(ownerUserId = userId)
        } else {
            LocalCtvLedger(ownerUserId = userId)
        }
    }

    private suspend fun saveCtvLedger(userId: Long, ledger: LocalCtvLedger) {
        metadata("ctv_ledger_v2:$userId", json.encodeToString(ledger.copy(ownerUserId = userId)))
    }

    private fun resolveAlignmentProfile(q: QuestionDto): QuestionAlignmentProfile {
        val card = q.effectiveEditorialCard()
        val distinctConcepts = q.conceptIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val ownRuleContext = q.editorialCard?.testedRule?.takeIf { it.isNotBlank() } ?: q.explanation
        val inferred = ExamCatalog.inferSingleConceptForTopic(q.canonicalTopicId, q.question, ownRuleContext)
        val (targetConceptId, ambiguous) = when (distinctConcepts.size) {
            0 -> inferred to false
            1 -> distinctConcepts.first() to false
            else -> {
                if (inferred != null && inferred in distinctConcepts) inferred to false else null to true
            }
        }
        val allConcepts = (distinctConcepts + listOfNotNull(targetConceptId)).distinct()
        val correctText = q.correctAnswerIndices.firstOrNull()?.let { q.answers.getOrNull(it) }
        val rev = QuestionCacheMapping.withRevision(q).questionRevision
        val isAiUnconfirmed = !q.knowledgeBindingVerified && q.source.contains("IA", ignoreCase = true)
        return QuestionAlignmentProfile(
            questionId = q.id,
            subjectId = q.canonicalSubjectId,
            topicId = q.canonicalTopicId,
            targetConceptId = targetConceptId,
            allConceptIds = allConcepts,
            targetConceptAmbiguous = ambiguous,
            testedRule = card.testedRule,
            decisiveExcerpt = card.decisiveStemExcerpt,
            legalProvisions = card.legalProvisions,
            questionType = q.questionType.name,
            stem = q.question,
            correctAnswerText = correctText,
            contentRevision = rev,
            semanticRevision = "",
            anchoredSourceQuestionId = null,
            isAiUnconfirmedBinding = isAiUnconfirmed,
            isEditorialPendingOrDubious = false
        )
    }

    private fun hasPriorExposureExcludingOffer(
        questionId: String,
        questionRevision: String,
        excludeOfferId: String?,
        excludeAttemptId: String?,
        learningById: Map<String, LearningRecord>,
        ledger: LocalCtvLedger,
        evaluatedAtMs: Long? = null
    ): Boolean {
        val rec = learningById[questionId]
        if (rec != null) {
            if (evaluatedAtMs == null) {
                if (rec.attempts > 0) return true
            } else if (rec.events.any { !it.isBlank && safeInstant(it.occurredAt).toEpochMilli() < evaluatedAtMs }) {
                return true
            }
        }
        if (questionRevision.isNotBlank()) {
            val sameRevInRegular = learningById.values.any { r ->
                r.events.any { ev ->
                    !ev.isBlank &&
                        ev.questionRevision == questionRevision &&
                        (evaluatedAtMs == null || safeInstant(ev.occurredAt).toEpochMilli() < evaluatedAtMs)
                }
            }
            if (sameRevInRegular) return true
        }
        for (off in ledger.offers.values) {
            if (excludeOfferId != null && off.offerId == excludeOfferId) continue
            val dispIso = off.firstDisplayedAt ?: off.dismissedAt ?: continue
            if (evaluatedAtMs != null && safeInstant(dispIso).toEpochMilli() > evaluatedAtMs) continue
            if (off.complementaryQuestionId == questionId ||
                (questionRevision.isNotBlank() && off.contentRevision == questionRevision)
            ) {
                return true
            }
        }
        for (att in ledger.attempts.values) {
            if (excludeAttemptId != null && att.ctvAttemptId == excludeAttemptId) continue
            if (excludeOfferId != null && att.offerId.isNotBlank() && att.offerId == excludeOfferId) continue
            if (evaluatedAtMs != null && att.occurredAt.isNotBlank() && safeInstant(att.occurredAt).toEpochMilli() > evaluatedAtMs) continue
            if (att.complementaryQuestionId == questionId ||
                (questionRevision.isNotBlank() && att.contentRevision == questionRevision)
            ) {
                return true
            }
        }
        return false
    }

    private fun computeMetacognitiveSignalsByTopic(
        rows: List<LearningRecord>,
        cachedById: Map<String, QuestionDto>,
        ledger: LocalCtvLedger,
        nowMs: Long
    ): Pair<Map<String, MetacognitivePrioritySignals>, Boolean> {
        data class TopicAcc(
            var attempts: Int = 0,
            var corrects: Int = 0,
            var hceCount: Int = 0,
            var incompatibleCount: Int = 0,
            var fragileCorrects: Int = 0,
            var functionalCorrects: Int = 0,
            var indepTransferOk: Boolean = false,
            var indepTransferFail: Boolean = false,
            var latestPostExpOkMs: Long? = null,
            var latestRegularAfterPostExpMs: Long? = null
        )

        val accByTopic = HashMap<String, TopicAcc>()
        val chronoSeq = ArrayList<Pair<Long, String>>()

        for (row in rows) {
            val tid = row.canonicalTopicId?.takeIf { it.isNotBlank() } ?: continue
            val acc = accByTopic.getOrPut(tid) { TopicAcc() }
            val q = cachedById[row.questionId]
            val customCh = q?.hasCustomEvidenceChallenge() == true
            val correctEvId = q?.effectiveEvidenceChallenge()?.correctEvidenceId
            for (ev in row.events) {
                if (ev.isBlank) continue
                val ts = safeInstant(ev.occurredAt).toEpochMilli()
                chronoSeq += (ts to tid)
                acc.attempts++
                val dc = ev.declaredConfidence ?: DeclaredConfidence.fromLegacyBand(ev.confidenceLevel, ev.confidenceLevel > 0)
                val normSel = normalizeEvidenceOptionId(ev.selectedEvidenceId, customCh)
                val evCompat = if (correctEvId != null && normSel != null) normSel == correctEvId else null
                if (evCompat == false) acc.incompatibleCount++
                if (ev.isCorrect) {
                    acc.corrects++
                    val g = MetacognitiveEngine.classifyHitGrounding(true, dc, ev.cognitiveStrategy, evCompat)
                    when (g) {
                        HitGroundingCategory.FRAGILE_OR_GUESS, HitGroundingCategory.DIVERGENT_RATIONALE -> acc.fragileCorrects++
                        HitGroundingCategory.VALID_FUNCTIONAL_STRATEGY -> acc.functionalCorrects++
                        else -> Unit
                    }
                } else if (dc?.isHigh == true || ev.confidenceLevel >= 3) {
                    acc.hceCount++
                }
            }
        }

        for (ctv in ledger.attempts.values) {
            val tid = ctv.canonicalTopicId
                ?: cachedById[ctv.complementaryQuestionId]?.canonicalTopicId
                ?: cachedById[ctv.sourceQuestionId]?.canonicalTopicId
                ?: continue
            if (tid.isBlank()) continue
            val acc = accByTopic.getOrPut(tid) { TopicAcc() }
            if (ctv.countsAsIndependentAttempt && ctv.verificationMode == "CONCEPTUAL_TRANSFER") {
                if (ctv.isCorrect) acc.indepTransferOk = true else acc.indepTransferFail = true
            } else if (ctv.isCorrect && (ctv.classificationCode == "POST_INSTRUCTION_FIXATION" || ctv.verificationTiming == "POST_INSTRUCTION")) {
                val ms = safeInstant(ctv.occurredAt).toEpochMilli()
                acc.latestPostExpOkMs = maxOf(acc.latestPostExpOkMs ?: ms, ms)
            }
        }

        for ((tid, acc) in accByTopic) {
            val postMs = acc.latestPostExpOkMs ?: continue
            val after = chronoSeq.filter { it.second == tid && it.first > postMs }.maxOfOrNull { it.first }
            acc.latestRegularAfterPostExpMs = after
        }

        chronoSeq.sortBy { it.first }
        val consecutiveByTopic = HashMap<String, Int>()
        val lastTid = chronoSeq.lastOrNull()?.second
        if (lastTid != null) {
            consecutiveByTopic[lastTid] = chronoSeq.asReversed().takeWhile { it.second == lastTid }.size
        }

        val anyFunctionalTransferCleared = accByTopic.values.any {
            it.functionalCorrects > 0 && it.indepTransferOk && it.hceCount == 0
        }

        val out = HashMap<String, MetacognitivePrioritySignals>()
        for ((tid, m) in accByTopic) {
            val hceRate = if (m.attempts > 0) m.hceCount.toDouble() / m.attempts.toDouble() else 0.0
            val fragileRate = if (m.corrects > 0) m.fragileCorrects.toDouble() / m.corrects.toDouble() else 0.0
            val functionalVerified = m.functionalCorrects > 0 && m.indepTransferOk && m.hceCount == 0
            val (pendingSpacing, spacedDue) = m.latestPostExpOkMs?.let { postMs ->
                if (m.latestRegularAfterPostExpMs != null) {
                    false to false
                } else {
                    val elapsedH = (nowMs - postMs).coerceAtLeast(0L).toDouble() / 3_600_000.0
                    if (elapsedH < 18.0) true to false else false to true
                }
            } ?: (false to false)
            val scarceOrConflict = (m.attempts in 1..2 && (m.fragileCorrects > 0 || m.incompatibleCount > 0)) ||
                (m.corrects > 0 && m.hceCount > 0) ||
                m.indepTransferFail
            out[tid] = MetacognitivePrioritySignals(
                highConfidenceErrorRate = hceRate,
                highConfidenceErrorCount = m.hceCount,
                incompatibleRationaleCount = m.incompatibleCount,
                fragileCorrectRate = fragileRate,
                failedTransfer = m.indepTransferFail,
                functionalEliminationTransferVerified = functionalVerified,
                postExplanationFixationPendingSpacing = pendingSpacing,
                postExplanationSpacedDue = spacedDue,
                scarceOrConflictingEvidence = scarceOrConflict,
                recentConsecutiveExposures = consecutiveByTopic[tid] ?: 0
            )
        }
        return out to anyFunctionalTransferCleared
    }

    private suspend fun buildLocalDecisionTelemetry(
        userId: Long,
        rows: List<LearningRecord>,
        exams: List<StoredExam>,
        preferredContestId: String? = null
    ): NextActionResponseDto {
        val activeExam = preferredContestId?.let { cid ->
            exams.firstOrNull { it.examId == cid && it.lifecycleStatus !in setOf("ARQUIVADO", "EXCLUIDO") }
        } ?: exams.filter { it.isActiveInLadder && it.lifecycleStatus !in setOf("ARQUIVADO", "EXCLUIDO") }
            .minByOrNull { it.ladderPriority }

        val today = LocalDate.now(LearningProjection.studyZone)
        val nowInst = Instant.now()
        val nowMs = nowInst.toEpochMilli()
        val scheduleHeader = PrioritizationEngine.formatEntry60sHeader(nowInst)
        val byTopic = rows.filter { !it.canonicalTopicId.isNullOrBlank() }.groupBy { it.canonicalTopicId!! }

        val allCached = dao.getCachedQuestions(null, 10000).map { QuestionCacheMapping.toDto(it, json) }
        val cachedById = allCached.associateBy { it.id }
        val cachedByTopic = allCached.filter { !it.canonicalTopicId.isNullOrBlank() }.groupBy { it.canonicalTopicId!! }
        val ledger = loadCtvLedger(userId)
        val (signalsByTopic, anyFunctionalCleared) = computeMetacognitiveSignalsByTopic(rows, cachedById, ledger, nowMs)
        val targetBoardKeys = activeExam?.examBoards.orEmpty()
            .filter { it.isNotBlank() && it != "A definir" }
            .map { normalizeBoardKey(it) }
            .toSet()

        var verifiedUnits = 0
        var consolidatingUnits = 0
        var insufficientUnits = 0
        var evidenceInProgressUnits = 0
        var activeErrorUnits = 0
        var transferVerifiedUnits = 0
        var transferAvailableUnits = 0
        var transferUnmeasuredUnits = 0
        var bestTopicId: String? = null
        var bestReason = "Assunto familiar com retenção pendente."
        var bestRuleCode = "BASELINE_REFERENCE"
        var bestMode = "ENTRY_60S"
        var bestUtility = -1.0

        if (activeExam != null) {
            val totalWeight = activeExam.subjects.filter { it.weight > 0.0 }.sumOf { it.weight }.coerceAtLeast(1.0)
            for (subj in activeExam.subjects) {
                val normW = (subj.weight / totalWeight).coerceAtLeast(0.05)
                for (top in subj.topics.filter { it.diffStatus != "ARQUIVADO" }) {
                    val unitRows = byTopic[top.canonicalTopicId].orEmpty()
                    val nonBlankEvents = unitRows.flatMap { it.events }.filter { !it.isBlank }
                    val lastRowAndEv = unitRows.flatMap { r -> r.events.filter { !it.isBlank }.map { r.questionId to it } }
                        .maxByOrNull { safeInstant(it.second.occurredAt) }
                    val lastEv = lastRowAndEv?.second
                    val lastQuestion = lastRowAndEv?.first?.let { cachedById[it] }
                    val evChallengePassed = if (lastEv?.selectedEvidenceId != null && lastQuestion != null) {
                        val ch = lastQuestion.effectiveEvidenceChallenge()
                        val normSel = normalizeEvidenceOptionId(lastEv.selectedEvidenceId, lastQuestion.hasCustomEvidenceChallenge())
                        if (ch != null && normSel != null) normSel == ch.correctEvidenceId else null
                    } else {
                        null
                    }
                    val nonBlankAttempts = unitRows.sumOf { it.attempts }
                    val correctAttempts = unitRows.sumOf { it.correct }
                    val distinctQuestions = unitRows.count { it.attempts > 0 }
                    val hadPriorCorrect = correctAttempts >= 2 || (correctAttempts == 1 && lastEv?.isCorrect != true)
                    val hasSpaced = unitRows.any { r ->
                        val sorted = r.events.filter { !it.isBlank && it.isCorrect }.map { safeInstant(it.occurredAt).toEpochMilli() }.sorted()
                        sorted.size >= 2 && (sorted.last() - sorted.first()) >= 86_400_000L
                    } || unitRows.any { it.isMastered }

                    val topicQuestions = cachedByTopic[top.canonicalTopicId].orEmpty()
                    val attemptedSet = unitRows.filter { it.attempts > 0 }.map { it.questionId }.toSet()
                    val availableCrossBoard = topicQuestions.count { q ->
                        val bk = normalizeBoardKey(q.source)
                        q.id !in attemptedSet && bk.isNotEmpty() && bk != "origem não informada" &&
                            (targetBoardKeys.isEmpty() || bk !in targetBoardKeys)
                    }
                    var transferAttempts = 0
                    var transferGrounded = 0
                    for (r in unitRows) {
                        val q = cachedById[r.questionId] ?: continue
                        val bk = normalizeBoardKey(q.source)
                        val isCross = bk.isNotEmpty() && bk != "origem não informada" &&
                            (targetBoardKeys.isEmpty() || bk !in targetBoardKeys)
                        if (!isCross) continue
                        val ch = q.effectiveEvidenceChallenge()
                        val customCh = q.hasCustomEvidenceChallenge()
                        for (ev in r.events) {
                            if (ev.isBlank) continue
                            if (ev.quizMode.equals("TRANSFER_PROBE", ignoreCase = true) || targetBoardKeys.isNotEmpty()) {
                                transferAttempts++
                                val normSel = normalizeEvidenceOptionId(ev.selectedEvidenceId, customCh)
                                val badEvidence = ch != null && normSel != null && normSel != ch.correctEvidenceId
                                if (ev.isCorrect && ev.confidenceLevel >= 2 && !badEvidence) {
                                    transferGrounded++
                                }
                            }
                        }
                    }
                    val ctvIndepTopic = ledger.attempts.values.filter {
                        it.canonicalTopicId == top.canonicalTopicId &&
                            it.countsAsIndependentAttempt &&
                            it.verificationMode == "CONCEPTUAL_TRANSFER"
                    }
                    transferAttempts += ctvIndepTopic.size
                    transferGrounded += ctvIndepTopic.count { it.countsAsIndependentCorrect }

                    val transferStatus = DiagnosticEngine.evaluateTransferStatus(
                        TransferInputs(
                            distinctQuestions = distinctQuestions,
                            availableCrossBoardQuestions = availableCrossBoard,
                            transferAttempts = transferAttempts,
                            transferCorrectGrounded = transferGrounded
                        )
                    )
                    when (transferStatus) {
                        TransferStatus.TRANSFER_VERIFIED -> transferVerifiedUnits++
                        TransferStatus.TRANSFER_AVAILABLE, TransferStatus.TRANSFER_PROVISIONAL -> transferAvailableUnits++
                        TransferStatus.TRANSFER_UNMEASURED, TransferStatus.TRANSFER_FAILED -> transferUnmeasuredUnits++
                    }

                    val diag = DiagnosticEngine.classifyDiagnostic(
                        DiagnosticInputs(
                            distinctQuestions = distinctQuestions,
                            nonBlankAttempts = nonBlankAttempts,
                            correctAttempts = correctAttempts,
                            lastIsCorrect = lastEv?.isCorrect ?: false,
                            lastConfidence = lastEv?.confidenceLevel ?: 0,
                            hadPriorVerifiedCorrect = hadPriorCorrect,
                            hasSpacedRetention = hasSpaced,
                            evidenceChallengePassed = evChallengePassed,
                            hasIndependentTransfer = transferStatus == TransferStatus.TRANSFER_VERIFIED ||
                                ctvIndepTopic.any { it.countsAsIndependentCorrect }
                        )
                    )
                    when {
                        diag == DiagnosticStatus.VERIFIED_COMPETENCE -> verifiedUnits++
                        diag == DiagnosticStatus.EVIDENCE_IN_PROGRESS || diag == DiagnosticStatus.CONSOLIDATED_NO_TRANSFER -> {
                            consolidatingUnits++
                            if (diag == DiagnosticStatus.EVIDENCE_IN_PROGRESS) evidenceInProgressUnits++
                        }
                        diag.isErrorIndication -> {
                            consolidatingUnits++
                            activeErrorUnits++
                        }
                        diag == DiagnosticStatus.INSUFFICIENT_EVIDENCE && distinctQuestions == 0 -> insufficientUnits++
                        else -> consolidatingUnits++
                    }
                    val lastMs = lastEv?.let { safeInstant(it.occurredAt).toEpochMilli() } ?: 0L
                    val elapsedDays = if (lastMs > 0L) ((nowMs - lastMs).coerceAtLeast(0L).toDouble() / 86_400_000.0) else 0.0
                    val approxStability = when {
                        unitRows.any { it.isMastered } -> 14.0
                        correctAttempts > 0 -> 4.0
                        else -> 0.0
                    }
                    val retr = RetentionEngine.retrievability(elapsedDays, approxStability)
                    val avgSec = if (nonBlankEvents.isNotEmpty()) nonBlankEvents.map { it.timeSpentSeconds }.average() else 60.0
                    val entryBoost = if (distinctQuestions >= 1 && retr < 0.90) {
                        1.65
                    } else if (distinctQuestions >= 1) {
                        1.20
                    } else {
                        1.0
                    }
                    val signals = signalsByTopic[top.canonicalTopicId] ?: MetacognitivePrioritySignals()
                    val u = PrioritizationEngine.priorityUtilityMetacognitive(
                        PrioritizationInputs(
                            editalWeight = normW,
                            incidenceWeight = 1.0,
                            retrievability = retr,
                            verifiedCompetence = diag == DiagnosticStatus.VERIFIED_COMPETENCE,
                            distinctQuestions = distinctQuestions,
                            avgSeconds = avgSec,
                            unmetPrerequisitesCount = 0,
                            dependentCount = 0
                        ),
                        signals
                    ) * entryBoost
                    if (u > bestUtility) {
                        bestUtility = u
                        bestTopicId = top.canonicalTopicId
                        val fallbackReason = when (diag) {
                            DiagnosticStatus.OVERCONFIDENCE_ERROR -> "Indício de erro c/ alta confiança em ${top.displayTitle}"
                            DiagnosticStatus.CONCEPTUAL_ERROR -> "Regra/exceção pendente em ${top.displayTitle}"
                            DiagnosticStatus.RETRIEVAL_FAILURE -> "Assunto familiar c/ retenção pendente: ${top.displayTitle}"
                            DiagnosticStatus.ILLUSORY_COMPETENCE -> "Revisar fundamento decisivo em ${top.displayTitle}"
                            DiagnosticStatus.EVIDENCE_IN_PROGRESS -> "${top.displayTitle} (${distinctQuestions}/${DiagnosticEngine.MIN_QUESTIONS_FOR_MASTERY} questões p/ validar domínio)"
                            DiagnosticStatus.CONSOLIDATED_NO_TRANSFER -> "${top.displayTitle} (testar transferência em outra banca)"
                            DiagnosticStatus.VERIFIED_COMPETENCE -> "Manutenção leve em ${top.displayTitle}"
                            DiagnosticStatus.INSUFFICIENT_EVIDENCE -> "Assunto prioritário: ${top.displayTitle}"
                        }
                        val advAfterFunctional = anyFunctionalCleared && !signals.functionalEliminationTransferVerified && distinctQuestions == 0
                        val dec = PrioritizationEngine.buildMetacognitiveDecisionReason(
                            signals = signals,
                            unitTitle = top.displayTitle,
                            fallbackReasonPt = fallbackReason,
                            advancedAfterFunctionalTransfer = advAfterFunctional
                        )
                        bestReason = dec.reasonPt
                        bestRuleCode = dec.decisionRuleCode
                        bestMode = dec.recommendedMode
                    }
                }
            }
        }

        val penalty = activeExam?.wrongAnswerPenalty ?: 0.0
        val cutoff = activeExam?.historicalCutoffScore ?: 0.0

        var groundedHits = 0
        var illusoryHits = 0
        var hesitantHits = 0
        var unknownGroundingHits = 0
        var overconfErrors = 0
        var conceptualErrors = 0
        var retrievalFailures = 0
        var strategicOmissions = 0

        val cellCounts = HashMap<Pair<String, Int>, Pair<Int, Int>>()
        val countedAttemptIds = HashSet<String>()
        for (row in rows) {
            val q = cachedById[row.questionId]
            val qt = if (q?.questionType == QuestionType.TRUE_FALSE) "TRUE_FALSE" else "MULTIPLE_CHOICE"
            val customCh = q?.hasCustomEvidenceChallenge() == true
            val correctEvId = q?.effectiveEvidenceChallenge()?.correctEvidenceId
            var seenCorrectBefore = false
            for (ev in row.events.sortedBy { safeInstant(it.occurredAt) }) {
                countedAttemptIds += ev.attemptId
                if (ev.isBlank) {
                    strategicOmissions++
                    continue
                }
                val band = ev.confidenceLevel.coerceIn(1, 3)
                val key = qt to band
                val cur = cellCounts[key] ?: (0 to 0)
                cellCounts[key] = (cur.first + if (ev.isCorrect) 1 else 0) to (cur.second + 1)

                val normSel = normalizeEvidenceOptionId(ev.selectedEvidenceId, customCh)
                val evCompat = if (correctEvId != null && normSel != null) normSel == correctEvId else null
                val evWrong = evCompat == false
                val dc = ev.declaredConfidence ?: DeclaredConfidence.fromLegacyBand(ev.confidenceLevel, ev.confidenceLevel > 0)
                if (ev.isCorrect) {
                    when (MetacognitiveEngine.classifyHitGrounding(true, dc, ev.cognitiveStrategy, evCompat)) {
                        HitGroundingCategory.DIVERGENT_RATIONALE -> illusoryHits++
                        HitGroundingCategory.FRAGILE_OR_GUESS -> hesitantHits++
                        HitGroundingCategory.VALID_CONCEPTUAL_GROUNDING,
                        HitGroundingCategory.VALID_FUNCTIONAL_STRATEGY -> {
                            groundedHits++
                            seenCorrectBefore = true
                        }
                        HitGroundingCategory.UNKNOWN_GROUNDING,
                        HitGroundingCategory.NOT_APPLICABLE_ERROR -> unknownGroundingHits++
                    }
                } else {
                    if (ev.confidenceLevel >= 3 || dc?.isHigh == true || (ev.confidenceLevel >= 2 && evWrong)) {
                        overconfErrors++
                    } else if (evWrong || !seenCorrectBefore) {
                        conceptualErrors++
                    } else {
                        retrievalFailures++
                    }
                }
            }
        }
        val pendingUnsyncedBlanks = dao.getAllPendingAttempts().count {
            it.ownerUserId == userId && it.syncError == null && it.isBlank && it.attemptId !in countedAttemptIds
        }
        strategicOmissions += pendingUnsyncedBlanks

        val calibrationCells = buildList {
            for (qt in listOf("TRUE_FALSE", "MULTIPLE_CHOICE")) {
                for (band in listOf(1, 2, 3)) {
                    val (corr, att) = cellCounts[qt to band] ?: (0 to 0)
                    add(CalibrationEngine.calibratedProbability(qt, band, corr, att))
                }
            }
        }

        val primaryQt = if (penalty > 0.0) "TRUE_FALSE" else "MULTIPLE_CHOICE"
        val lowBandCell = calibrationCells.first { it.questionType == primaryQt && it.confidenceBand == 1 }
        val lowEv = (1.0 + penalty) * lowBandCell.calibratedMean - penalty
        val omissionSaved = if (penalty > 0.0 && lowEv < 0.0) {
            kotlin.math.round(strategicOmissions.toDouble() * (-lowEv) * 100.0) / 100.0
        } else {
            0.0
        }

        val strategyGuidance = listOf(1, 2, 3).map { band ->
            val cell = calibrationCells.first { it.questionType == primaryQt && it.confidenceBand == band }
            StrategyBandGuidanceDto(
                confidenceBand = band,
                calibratedMean = cell.calibratedMean,
                decision = StrategyEngine.evaluateStrategy(cell.calibratedMean, penalty, cutoff, 0.0, 1.0)
            )
        }

        // Consolida sessões SIMULADO de learning_records + pending_attempts sem duplicar attemptId
        data class TimedObs(val attemptId: String, val sessionUuid: String, val elapsedSec: Int, val isCorrect: Boolean, val interrupted: Boolean, val difficulty01: Double)
        val timedByAttempt = LinkedHashMap<String, TimedObs>()
        for (row in rows) {
            val itemDiff = ((100.0 - row.accuracyPct).coerceIn(10.0, 90.0)) / 100.0
            for (ev in row.events) {
                if (ev.isBlank || !ev.quizMode.equals("SIMULADO", ignoreCase = true)) continue
                val el = ev.elapsedSessionSeconds ?: continue
                val sid = ev.sessionUuid ?: continue
                timedByAttempt[ev.attemptId] = TimedObs(ev.attemptId, sid, el, ev.isCorrect, ev.sessionInterrupted, itemDiff)
            }
        }
        for (p in dao.getAllPendingAttempts()) {
            if (p.ownerUserId != userId || p.isBlank || !p.quizMode.equals("SIMULADO", ignoreCase = true)) continue
            val el = p.elapsedSessionSeconds ?: continue
            timedByAttempt.putIfAbsent(p.attemptId, TimedObs(p.attemptId, p.sessionUuid, el, p.isCorrect, p.sessionInterrupted, 0.50))
        }
        var qualSessions = 0
        var earlyCorr = 0
        var earlyTot = 0
        var lateCorr = 0
        var lateTot = 0
        var earlyDiffSum = 0.0
        var lateDiffSum = 0.0
        var earlyMinSum = 0.0
        var lateMinSum = 0.0
        for ((_, list) in timedByAttempt.values.groupBy { it.sessionUuid }) {
            val interrupted = list.any { it.interrupted }
            val maxEl = list.maxOfOrNull { it.elapsedSec } ?: 0
            if (interrupted || (maxEl < 5400 && list.size < 40)) continue
            qualSessions++
            for (item in list) {
                val mins = (item.elapsedSec.coerceAtLeast(0).toDouble() / 60.0).coerceAtMost(300.0)
                if (item.elapsedSec <= 2700) {
                    earlyTot++
                    earlyDiffSum += item.difficulty01
                    earlyMinSum += mins
                    if (item.isCorrect) earlyCorr++
                } else {
                    lateTot++
                    lateDiffSum += item.difficulty01
                    lateMinSum += mins
                    if (item.isCorrect) lateCorr++
                }
            }
        }
        val localFatigue = FatigueEngine.evaluateFatigueWithDifficulty(
            qualifyingSessions = qualSessions,
            earlyCorrect = earlyCorr,
            earlyTotal = earlyTot,
            lateCorrect = lateCorr,
            lateTotal = lateTot,
            earlyAvgDifficulty = if (earlyTot > 0) earlyDiffSum / earlyTot else 0.50,
            lateAvgDifficulty = if (lateTot > 0) lateDiffSum / lateTot else 0.50,
            earlyMeanMinutes = if (earlyTot > 0) earlyMinSum / earlyTot else 30.0,
            lateMeanMinutes = if (lateTot > 0) lateMinSum / lateTot else 180.0
        )
        val cachedRemote = metadata("cached_next_action:${preferredContestId ?: "GLOBAL"}")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<NextActionResponseDto>(it) }.getOrNull() }
        val fatigueReport = if (localFatigue.status == FatigueStatus.INSUFFICIENT_EVIDENCE && cachedRemote != null && cachedRemote.fatigueReport.qualifyingAttempts > localFatigue.qualifyingAttempts) {
            cachedRemote.fatigueReport
        } else {
            localFatigue
        }

        val mediumCell = calibrationCells.first { it.questionType == primaryQt && it.confidenceBand == 2 }
        val examScenarios = StrategyEngine.buildExamMomentScenarios(
            mediumP = mediumCell.calibratedMean,
            mediumSd = mediumCell.credibleSd,
            penalty = penalty,
            cutoffPct = cutoff,
            lambdaI = fatigueReport.estimatedLambda
        )
        val daysUntilExam = if (activeExam?.dateType == "EXACT_DATE") ExamEngine.daysUntil(activeExam.exactDateIso, today) else null

        // Seleciona questão curta familiar p/ entrada imediata de 60s mesmo offline
        val familiarTopics = rows.filter { it.attempts > 0 && !it.canonicalTopicId.isNullOrBlank() }.map { it.canonicalTopicId!! }.toSet()
        val pendingRetTopics = rows.filter {
            !it.canonicalTopicId.isNullOrBlank() && (it.reviewRequired || safeInstant(it.nextReviewAt) <= nowInst)
        }.map { it.canonicalTopicId!! }.toSet()
        val recentIds = rows.filter { r ->
            r.events.any { (nowMs - safeInstant(it.occurredAt).toEpochMilli()) in 0..(18L * 3600_000L) }
        }.map { it.questionId }.toSet()
        val validEntryPool = objectivePracticeQuestions(allCached)
            .filter {
                it.correctAnswerIndices.size == 1 &&
                    it.correctAnswerIndices[0] in it.answers.indices &&
                    QuestionCacheMapping.withinReviewDate(it, today)
            }
        val priorityPool = if (bestTopicId != null) {
            validEntryPool.filter { it.canonicalTopicId == bestTopicId }.ifEmpty { validEntryPool }
        } else {
            validEntryPool
        }
        val localEntryCandidate = priorityPool.maxByOrNull { q ->
            val bonus = if (bestTopicId != null && q.canonicalTopicId == bestTopicId) 120.0 else 0.0
            bonus + PrioritizationEngine.entry60sQuestionScore(
                stemCharLen = q.question.length,
                isTrueFalse = q.questionType == QuestionType.TRUE_FALSE,
                isFamiliarTopic = q.canonicalTopicId in familiarTopics,
                hasPendingRetention = q.canonicalTopicId in pendingRetTopics,
                recentlyAnswered = q.id in recentIds
            )
        }
        val recId = UUID.nameUUIDFromBytes(
            "rec:$userId:$today:${bestTopicId.orEmpty()}:$bestRuleCode".toByteArray(Charsets.UTF_8)
        ).toString()

        return NextActionResponseDto(
            scheduleHeader = scheduleHeader,
            entryQuestion = localEntryCandidate,
            entryReason = bestReason,
            priorityTopicId = bestTopicId,
            recommendationId = recId,
            decisionRuleCode = bestRuleCode,
            recommendedQuizMode = bestMode,
            examGapSummary = ExamGapSummaryDto(
                examId = activeExam?.examId,
                examName = activeExam?.shortName,
                daysUntilExam = daysUntilExam,
                verifiedUnits = verifiedUnits,
                consolidatingUnits = consolidatingUnits,
                insufficientEvidenceUnits = insufficientUnits,
                totalUnits = verifiedUnits + consolidatingUnits + insufficientUnits,
                wrongAnswerPenalty = penalty,
                historicalCutoffScore = cutoff,
                minQuestionsForMastery = DiagnosticEngine.MIN_QUESTIONS_FOR_MASTERY,
                evidenceInProgressUnits = evidenceInProgressUnits,
                activeErrorUnits = activeErrorUnits,
                transferVerifiedUnits = transferVerifiedUnits,
                transferAvailableUnits = transferAvailableUnits,
                transferUnmeasuredUnits = transferUnmeasuredUnits
            ),
            threeSignalSummary = ThreeSignalSummaryDto(
                groundedHits = groundedHits,
                illusoryCompetenceHits = illusoryHits,
                hesitantHits = hesitantHits,
                unknownGroundingHits = unknownGroundingHits,
                overconfidenceErrors = overconfErrors,
                conceptualErrors = conceptualErrors,
                retrievalFailures = retrievalFailures,
                strategicOmissions = strategicOmissions,
                omissionNetPointsSaved = omissionSaved
            ),
            calibrationCells = calibrationCells,
            strategyGuidance = strategyGuidance,
            examScenarios = examScenarios,
            fatigueReport = fatigueReport
        )
    }

    suspend fun fetchNextAction(userId: Long, contestId: String? = null): NextActionResponseDto {
        owner(userId)
        val scope = contestId?.takeIf { it.isNotBlank() && it != "Geral" }
        try {
            auth?.ensureSession(api, ownerUserId)
            val remote = api.getNextAction(scope)
            metadata("cached_next_action:${scope ?: "GLOBAL"}", json.encodeToString(remote))
            val normalizedEntry = remote.entryQuestion?.let(QuestionCacheMapping::withRevision)
            if (normalizedEntry != null && normalizedEntry.questionType != QuestionType.OPEN_ENDED) {
                dao.insertQuestions(listOf(QuestionCacheMapping.toEntity(normalizedEntry, json)))
                val decorated = decorateQuestions(listOf(normalizedEntry)).firstOrNull()
                return remote.copy(entryQuestion = decorated)
            }
            return remote
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return buildLocalDecisionTelemetry(userId, projectedLearning(), loadStoredExams(), scope)
        }
    }

    suspend fun getTodayDashboard(userId: Long, dailyGoalQuestions: Int = 50, activeContestIds: List<String> = listOf("CPNU")): TodayDashboardDto {
        owner(userId); val rows = projectedLearning(); val today = LocalDate.now(LearningProjection.studyZone)
        val stored = loadStoredExams()
        val events = rows.flatMap { it.events }.filter { !it.isBlank && safeInstant(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate() == today }
        val actionable = rows.filter { it.practiceAvailable != false }
        val localNextAction = buildLocalDecisionTelemetry(userId, rows, stored, activeContestIds.firstOrNull())
        val entryMetrics = buildEntryProtocolMetrics(userId, rows)
        return TodayDashboardDto(
            userId, today.toString(), dailyGoalQuestions, events.size,
            if (events.isEmpty()) 0.0 else events.count { it.isCorrect } * 100.0 / events.size,
            actionable.count { it.reviewRequired || safeInstant(it.nextReviewAt) <= Instant.now() }.toLong(),
            actionable.count { it.inErrorNotebook }.toLong(),
            actionable.count { it.inErrorNotebook && it.highConfidenceError }.toLong(),
            emptyList(), CommonTrunkSuggestion("", emptyList(), ""),
            lastSyncedAt = metadata("last_sync"), syncMessage = metadata("sync_error"),
            nextAction = localNextAction,
            entryMetrics = entryMetrics
        )
    }

    private fun buildCohortSummaryDto(
        cohortId: String,
        labelPt: String,
        eligibilityScopePt: String,
        offers: Int,
        dismissals: Int,
        responses: Int,
        indepEligible: Int,
        indepCorrect: Int
    ): CtvAdaptiveCohortDto {
        val rate = if (indepEligible > 0) {
            kotlin.math.round(indepCorrect * 10000.0 / indepEligible) / 100.0
        } else {
            null
        }
        val summary = when {
            indepEligible == 0 && dismissals > 0 ->
                "Sem respostas independentes elegíveis ($dismissals dispensa(s) fora do denominador)."
            indepEligible == 0 ->
                "Sem respostas independentes elegíveis no período."
            else -> {
                val base = "%d/%d verificações independentes corretas (%.1f%%)".format(
                    java.util.Locale.US,
                    indepCorrect,
                    indepEligible,
                    100.0 * indepCorrect.toDouble() / indepEligible.toDouble()
                )
                if (dismissals > 0) "$base · $dismissals oferta(s) dispensada(s)" else base
            }
        }
        return CtvAdaptiveCohortDto(
            cohortId = cohortId,
            labelPt = labelPt,
            eligibilityScopePt = eligibilityScopePt,
            offersCount = offers,
            dismissalsCount = dismissals,
            responsesCount = responses,
            independentEligibleResponses = indepEligible,
            independentCorrectCount = indepCorrect,
            independentSuccessRatePct = rate,
            summaryPt = summary
        )
    }

    private suspend fun buildLocalStudentLearningAnalytics(
        userId: Long,
        rows: List<LearningRecord>,
        exams: List<StoredExam>
    ): StudentLearningAnalyticsDto {
        val nowMs = System.currentTimeMillis()
        val cachedById = cachedQuestionsMap(rows.map { it.questionId })
        val ledger = loadCtvLedger(userId)

        var rawCorrectHits = 0
        var evaluableGroundingHits = 0
        var unknownGroundingHits = 0
        var validConceptualHits = 0
        var validFunctionalHits = 0
        var divergentRationaleHits = 0
        var fragileHits = 0
        var totalAttempts = 0
        var highConfTotal = 0
        var highConfErrors = 0
        val wfInputs = ArrayList<WalkforwardCalibrationInput>()

        for (row in rows) {
            val q = cachedById[row.questionId]
            val qt = if (q?.questionType == QuestionType.TRUE_FALSE) "TRUE_FALSE" else "MULTIPLE_CHOICE"
            val customCh = q?.hasCustomEvidenceChallenge() == true
            val correctEvId = q?.effectiveEvidenceChallenge()?.correctEvidenceId
            for (ev in row.events) {
                if (ev.isBlank) continue
                totalAttempts++
                val dc = ev.declaredConfidence ?: DeclaredConfidence.fromLegacyBand(ev.confidenceLevel, ev.confidenceLevel > 0)
                if (dc?.isHigh == true) {
                    highConfTotal++
                    if (!ev.isCorrect) highConfErrors++
                }
                val normSel = normalizeEvidenceOptionId(ev.selectedEvidenceId, customCh)
                val evCompat = if (correctEvId != null && normSel != null) normSel == correctEvId else null
                if (ev.isCorrect) {
                    rawCorrectHits++
                    when (MetacognitiveEngine.classifyHitGrounding(true, dc, ev.cognitiveStrategy, evCompat)) {
                        HitGroundingCategory.VALID_CONCEPTUAL_GROUNDING -> {
                            evaluableGroundingHits++; validConceptualHits++
                        }
                        HitGroundingCategory.VALID_FUNCTIONAL_STRATEGY -> {
                            evaluableGroundingHits++; validFunctionalHits++
                        }
                        HitGroundingCategory.DIVERGENT_RATIONALE -> {
                            evaluableGroundingHits++; divergentRationaleHits++
                        }
                        HitGroundingCategory.FRAGILE_OR_GUESS -> {
                            evaluableGroundingHits++; fragileHits++
                        }
                        HitGroundingCategory.UNKNOWN_GROUNDING,
                        HitGroundingCategory.NOT_APPLICABLE_ERROR -> {
                            unknownGroundingHits++
                        }
                    }
                }
                if (dc != null) {
                    wfInputs += WalkforwardCalibrationInput(
                        attemptId = ev.attemptId,
                        occurredAtMs = safeInstant(ev.occurredAt).toEpochMilli(),
                        questionType = qt,
                        declaredConfidence = dc,
                        isCorrect = ev.isCorrect
                    )
                }
            }
        }

        val wfReport = CalibrationEngine.evaluateWalkforwardCalibration(wfInputs)
        val dominantQt = if (wfInputs.count { it.questionType == "TRUE_FALSE" } > wfInputs.size / 2) {
            "TRUE_FALSE"
        } else {
            "MULTIPLE_CHOICE"
        }
        val calList = DeclaredConfidence.entries.map { level ->
            val matching = wfInputs.filter { it.declaredConfidence == level }
            val att = matching.size
            val corr = matching.count { it.isCorrect }
            val (postMean, sd) = CalibrationEngine.calibratedLevelPosterior(dominantQt, level, corr, att)
            ConfidenceLevelCalibrationDto(
                declaredConfidence = level,
                labelPt = level.labelPt,
                attempts = att,
                correct = corr,
                observedAccuracyPct = if (att > 0) kotlin.math.round(corr * 10000.0 / att) / 100.0 else null,
                calibratedPosteriorMean = kotlin.math.round(postMean * 10000.0) / 10000.0,
                credibleSd = kotlin.math.round(sd * 10000.0) / 10000.0,
                sufficiencyStatus = CalibrationEngine.sufficiencyStatusForSample(att, 5),
                coverageSentencePt = CalibrationEngine.formatConfidenceCoverageSentence(level, null, corr, att)
            )
        }

        // Estatísticas CTV locais/reconciliadas (Seções 2 e 3.5 — A03, A04, A08)
        var diffOffers = 0
        var diffDismissals = 0
        var perOffers = 0
        var perDismissals = 0
        val seenOfferIds = HashSet<String>()
        for (off in ledger.offers.values) {
            if (off.firstDisplayedAt == null && off.dismissedAt == null && off.state == "SUPPLIED") continue
            seenOfferIds += off.offerId
            val isPeriodic = off.triggerCohort == "PERIODIC_SAMPLING"
            if (isPeriodic) {
                perOffers++
                if (off.state == "DISMISSED" || (off.dismissedAt != null && ledger.attempts.values.none { it.offerId == off.offerId })) {
                    perDismissals++
                }
            } else {
                diffOffers++
                if (off.state == "DISMISSED" || (off.dismissedAt != null && ledger.attempts.values.none { it.offerId == off.offerId })) {
                    diffDismissals++
                }
            }
        }
        var diffResp = 0
        var diffIndep = 0
        var diffIndepOk = 0
        var perResp = 0
        var perIndep = 0
        var perIndepOk = 0
        var unknownCondCount = 0
        for (att in ledger.attempts.values) {
            if (att.classificationCode == "UNKNOWN_CONDITION" || att.verificationTiming == "UNKNOWN_CONDITION") {
                unknownCondCount++
            }
            val isPeriodic = att.triggerCohort == "PERIODIC_SAMPLING" ||
                CtvAlignmentEngine.triggerCohortForReason(att.triggerReason) == "PERIODIC_SAMPLING"
            if (att.offerId.isBlank() || att.offerId !in seenOfferIds) {
                if (isPeriodic) perOffers++ else diffOffers++
            }
            if (isPeriodic) {
                perResp++
                if (att.countsAsIndependentAttempt) {
                    perIndep++
                    if (att.countsAsIndependentCorrect) perIndepOk++
                }
            } else {
                diffResp++
                if (att.countsAsIndependentAttempt) {
                    diffIndep++
                    if (att.countsAsIndependentCorrect) diffIndepOk++
                }
            }
        }
        val totalIndepAtt = diffIndep + perIndep
        val totalIndepOk = diffIndepOk + perIndepOk
        val adaptiveReport = CtvAdaptiveSelectionReportDto(
            difficultyTriggered = buildCohortSummaryDto(
                cohortId = "DIFFICULTY_DETECTED",
                labelPt = "Disparo adaptativo por dificuldade ou fragilidade",
                eligibilityScopePt = "Tentativas com sinal de chute, baixa confiança, equívoco com confiança alta ou transferência pendente.",
                offers = diffOffers,
                dismissals = diffDismissals,
                responses = diffResp,
                indepEligible = diffIndep,
                indepCorrect = diffIndepOk
            ),
            periodicSampling = buildCohortSummaryDto(
                cohortId = "PERIODIC_SAMPLING",
                labelPt = "Amostragem periódica de acompanhamento",
                eligibilityScopePt = "Amostra determinística restrita a tentativas elegíveis em conceitos com histórico consistente.",
                offers = perOffers,
                dismissals = perDismissals,
                responses = perResp,
                indepEligible = perIndep,
                indepCorrect = perIndepOk
            ),
            unknownConditionCount = unknownCondCount
        )

        val activeExam = exams.filter { it.isActiveInLadder && it.lifecycleStatus !in setOf("ARQUIVADO", "EXCLUIDO") }
            .minByOrNull { it.ladderPriority }
        val byTopic = rows.filter { !it.canonicalTopicId.isNullOrBlank() }.groupBy { it.canonicalTopicId!! }
        val targetTopics = activeExam?.subjects.orEmpty()
            .flatMap { s -> s.topics.filter { it.diffStatus != "ARQUIVADO" }.map { s.canonicalSubjectId to it.canonicalTopicId } }
            .distinctBy { it.second }
            .ifEmpty {
                byTopic.keys.map { tid ->
                    (byTopic[tid]?.firstOrNull()?.canonicalSubjectId ?: "") to tid
                }
            }

        var notEval = 0
        var insuff = 0
        var emerging = 0
        var unstable = 0
        var developing = 0
        var demonstrated = 0
        var consolidated = 0
        var atRisk = 0
        val unitMasteries = mutableListOf<UnitConceptMasteryDto>()

        for ((subjId, topicId) in targetTopics) {
            val unitRows = byTopic[topicId].orEmpty()
            val uEventsWithQ = unitRows.flatMap { r -> r.events.filter { !it.isBlank }.map { r.questionId to it } }
            val uEvents = uEventsWithQ.map { it.second }
            val att = uEvents.size
            val distinctQ = unitRows.count { it.attempts > 0 }
            val corr = uEvents.count { it.isCorrect }
            val acc = if (att > 0) corr.toDouble() / att.toDouble() else 0.0
            val grounded = uEventsWithQ.count { (qid, ev) ->
                val q = cachedById[qid]
                val customCh = q?.hasCustomEvidenceChallenge() == true
                val correctEvId = q?.effectiveEvidenceChallenge()?.correctEvidenceId
                val normSel = normalizeEvidenceOptionId(ev.selectedEvidenceId, customCh)
                val evCompat = if (correctEvId != null && normSel != null) normSel == correctEvId else null
                val dc = ev.declaredConfidence ?: DeclaredConfidence.fromLegacyBand(ev.confidenceLevel, ev.confidenceLevel > 0)
                MetacognitiveEngine.classifyHitGrounding(ev.isCorrect, dc, ev.cognitiveStrategy, evCompat).creditsComprehension
            }
            val groundedRate = if (att > 0) grounded.toDouble() / att.toDouble() else 0.0
            val hce = uEvents.count { ev ->
                val dc = ev.declaredConfidence ?: DeclaredConfidence.fromLegacyBand(ev.confidenceLevel, ev.confidenceLevel > 0)
                !ev.isCorrect && dc?.isHigh == true
            }
            val hceRate = if (att > 0) hce.toDouble() / att.toDouble() else 0.0
            val transferProbeEvs = uEvents.filter { it.quizMode.equals("TRANSFER_PROBE", ignoreCase = true) }
            val topicCtvIndep = ledger.attempts.values.filter {
                it.canonicalTopicId == topicId && it.countsAsIndependentAttempt && it.verificationMode == "CONCEPTUAL_TRANSFER"
            }
            val tAtt = transferProbeEvs.size + topicCtvIndep.size
            val tOk = transferProbeEvs.count { it.isCorrect } + topicCtvIndep.count { it.countsAsIndependentCorrect }
            val tSucc = if (tAtt > 0) tOk.toDouble() / tAtt.toDouble() else 0.0
            val hasSpaced = unitRows.any { r ->
                val ts = r.events.filter { !it.isBlank && it.isCorrect }.map { safeInstant(it.occurredAt).toEpochMilli() }.sorted()
                ts.size >= 2 && (ts.last() - ts.first()) >= 86_400_000L
            } || unitRows.any { it.isMastered }
            val lastMs = uEvents.maxOfOrNull { safeInstant(it.occurredAt).toEpochMilli() } ?: 0L
            val elapsedDays = if (lastMs > 0L) ((nowMs - lastMs).coerceAtLeast(0L).toDouble() / 86_400_000.0) else 0.0
            val stab = when {
                unitRows.any { it.isMastered } -> 14.0
                corr > 0 -> 4.0
                else -> 0.0
            }
            val retr = if (att > 0) RetentionEngine.retrievability(elapsedDays, stab) else 0.0
            val perQAcc = unitRows.filter { it.attempts > 0 }.map { it.accuracyPct / 100.0 }
            val cons = if (perQAcc.size >= 2) {
                val mean = perQAcc.average()
                val sd = kotlin.math.sqrt(perQAcc.sumOf { (it - mean) * (it - mean) } / perQAcc.size)
                (1.0 - sd * 1.5).coerceIn(0.0, 1.0)
            } else if (att > 0) {
                acc
            } else {
                0.0
            }
            val avgAct = uEvents.map { (it.activeSeconds ?: it.timeSpentSeconds).toDouble() }.takeIf { it.isNotEmpty() }?.average()
            val eval = ConceptMasteryEngine.classifyConceptMastery(
                ConceptMasteryInputs(
                    attempts = att,
                    distinctQuestions = distinctQ,
                    accuracy = acc,
                    groundedRate = groundedRate,
                    highConfErrorRate = hceRate,
                    transferAttempts = tAtt,
                    transferSuccessRate = tSucc,
                    hasSpacedRetention = hasSpaced,
                    retrievability = retr,
                    consistency = cons,
                    avgActiveSeconds = avgAct,
                    referenceSeconds = 60.0
                )
            )
            when (eval.state) {
                ConceptMasteryState.NOT_EVALUATED -> notEval++
                ConceptMasteryState.INSUFFICIENT_EVIDENCE -> insuff++
                ConceptMasteryState.EMERGING_KNOWLEDGE -> emerging++
                ConceptMasteryState.UNSTABLE_KNOWLEDGE -> unstable++
                ConceptMasteryState.DEVELOPING_MASTERY -> developing++
                ConceptMasteryState.DEMONSTRATED_MASTERY -> demonstrated++
                ConceptMasteryState.CONSOLIDATED_MASTERY -> consolidated++
                ConceptMasteryState.MASTERY_AT_RISK -> atRisk++
            }
            unitMasteries += UnitConceptMasteryDto(
                unitId = topicId,
                canonicalSubjectId = subjId,
                canonicalTopicId = topicId,
                attempts = att,
                distinctQuestions = distinctQ,
                transferAttempts = tAtt,
                evaluation = eval
            )
        }

        val evaluatedUnits = unitMasteries.count { it.evaluation.state != ConceptMasteryState.NOT_EVALUATED }
        val confirmedUnits = demonstrated + consolidated
        fun pct(n: Int, d: Int): Double? = if (d > 0) kotlin.math.round(n * 10000.0 / d) / 100.0 else null

        val groundingSentence = if (rawCorrectHits == 0) {
            "Ainda não há acertos objetivos para avaliar a força do fundamento."
        } else {
            "Dos $rawCorrectHits acertos brutos, $evaluableGroundingHits têm evidência avaliável de fundamento e $unknownGroundingHits permanecem com fundamento não declarado."
        }

        val localKpis = listOf(
            FormalKpiDto(
                kpiId = "CONFIRMED_CONCEPTUAL_MASTERY_RATE",
                labelPt = "Taxa de Domínio Conceitual Confirmado",
                value = pct(confirmedUnits, evaluatedUnits),
                unit = "%",
                numerator = confirmedUnits.toDouble(),
                denominator = evaluatedUnits.toDouble(),
                sampleSizeN = evaluatedUnits,
                formalDefinition = "Proporção de unidades avaliadas nos estados DEMONSTRATED_MASTERY ou CONSOLIDATED_MASTERY.",
                interpretiveLimitation = "Exige evidência mínima em múltiplas questões, fundamento observável e transferência independente."
            ),
            FormalKpiDto(
                kpiId = "INDEPENDENT_TRANSFER_SUCCESS_RATE",
                labelPt = "Taxa de Transferência Independente",
                value = pct(totalIndepOk, totalIndepAtt),
                unit = "%",
                numerator = totalIndepOk.toDouble(),
                denominator = totalIndepAtt.toDouble(),
                sampleSizeN = totalIndepAtt,
                formalDefinition = "Proporção de verificações CTV inéditas, alinhadas ao conceito-alvo e respondidas antes do feedback em que o aluno acertou.",
                interpretiveLimitation = "Respostas erradas inéditas pré-feedback permanecem no denominador; dispensas e itens já vistos ficam fora."
            ),
            FormalKpiDto(
                kpiId = "FRAGILE_HIT_RATE",
                labelPt = "Taxa de Acerto Frágil",
                value = pct(fragileHits, evaluableGroundingHits),
                unit = "%",
                numerator = fragileHits.toDouble(),
                denominator = evaluableGroundingHits.toDouble(),
                sampleSizeN = evaluableGroundingHits,
                formalDefinition = "Proporção de acertos com evidência avaliável obtidos com baixa confiança, chute ou reconhecimento superficial.",
                missingDataRule = "Acertos sem estratégia/evidência ($unknownGroundingHits) são preservados como fundamento desconhecido.",
                interpretiveLimitation = "Indica oportunidade de consolidação, não ausência absoluta de conhecimento."
            ),
            FormalKpiDto(
                kpiId = "OVERCONFIDENCE_ERROR_RATE",
                labelPt = "Taxa de Superconfiança",
                value = pct(highConfErrors, highConfTotal),
                unit = "%",
                numerator = highConfErrors.toDouble(),
                denominator = highConfTotal.toDouble(),
                sampleSizeN = highConfTotal,
                formalDefinition = "Proporção de erros dentre as respostas registradas com confiança alta ou praticamente certa.",
                interpretiveLimitation = "Sensível a pequenas amostras iniciais (n < 10)."
            )
        )

        return StudentLearningAnalyticsDto(
            generatedAt = Instant.now().toString(),
            calibrationPolicyVersion = wfReport.policyVersion,
            learningKpis = localKpis,
            empiricalBrierScore = wfReport.overallBrierScore,
            trueFalseBrierScore = wfReport.trueFalseBrierScore,
            multipleChoiceBrierScore = wfReport.multipleChoiceBrierScore,
            coldStartPredictionsCount = wfReport.coldStartPredictionsCount,
            confidenceCalibration = calList,
            confidenceCalibrationByFormat = wfReport.cellsByFormat,
            groundingSummary = GroundingEvidenceSummaryDto(
                rawCorrectHits = rawCorrectHits,
                evaluableGroundingHits = evaluableGroundingHits,
                unknownGroundingHits = unknownGroundingHits,
                validConceptualHits = validConceptualHits,
                validFunctionalStrategyHits = validFunctionalHits,
                divergentRationaleHits = divergentRationaleHits,
                fragileOrGuessHits = fragileHits,
                coverageSentencePt = groundingSentence
            ),
            ctvAdaptiveSelection = adaptiveReport,
            conceptMasterySummary = ConceptMasterySummaryDto(
                notEvaluated = notEval,
                insufficientEvidence = insuff,
                emergingKnowledge = emerging,
                unstableKnowledge = unstable,
                developingMastery = developing,
                demonstratedMastery = demonstrated,
                consolidatedMastery = consolidated,
                masteryAtRisk = atRisk
            ),
            unitMastery = unitMasteries
        )
    }

    private suspend fun hasUnsyncedLocalLearningOrCtv(userId: Long): Boolean {
        val hasPendingRegular = dao.getAllPendingAttempts().any { it.ownerUserId == userId && it.syncError == null }
        if (hasPendingRegular) return true
        val hasPendingCtvSub = metadata("pending_ctv_submissions")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<PendingCtvSubmission>>(it) }.getOrDefault(emptyList()) }
            .orEmpty()
            .any { it.ownerUserId == userId }
        if (hasPendingCtvSub) return true
        val ledger = loadCtvLedger(userId)
        return ledger.pendingEvents.isNotEmpty() || ledger.attempts.values.any { it.provisional }
    }

    private suspend fun fetchOrBuildStudentLearningAnalytics(
        userId: Long,
        rows: List<LearningRecord>,
        exams: List<StoredExam>
    ): StudentLearningAnalyticsDto {
        val cacheKey = "cached_learning_analytics:$userId"
        return try {
            if (hasUnsyncedLocalLearningOrCtv(userId)) {
                return buildLocalStudentLearningAnalytics(userId, rows, exams)
            }
            auth?.ensureSession(api, ownerUserId)
            val remote = api.getLearningAnalytics()
            metadata(cacheKey, json.encodeToString(remote))
            remote
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (hasUnsyncedLocalLearningOrCtv(userId)) {
                buildLocalStudentLearningAnalytics(userId, rows, exams)
            } else {
                metadata(cacheKey)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { json.decodeFromString<StudentLearningAnalyticsDto>(it) }.getOrNull() }
                    ?: buildLocalStudentLearningAnalytics(userId, rows, exams)
            }
        }
    }

    suspend fun getProgressAnalytics(userId: Long): ProgressAnalyticsDto {
        owner(userId); val rows = projectedLearning(); val attempts = rows.sumOf { it.attempts }; val correct = rows.sumOf { it.correct }
        val stored = loadStoredExams()
        val primaryExam = stored.filter { it.isActiveInLadder && it.lifecycleStatus !in setOf("ARQUIVADO", "EXCLUIDO") }.minByOrNull { it.ladderPriority }
        val cutoff = primaryExam?.historicalCutoffScore ?: 0.0
        val penalty = primaryExam?.wrongAnswerPenalty ?: 0.0
        val telemetry = buildLocalDecisionTelemetry(userId, rows, stored, primaryExam?.examId)
        val entryMetrics = buildEntryProtocolMetrics(userId, rows)
        val studentLearning = fetchOrBuildStudentLearningAnalytics(userId, rows, stored)
        val since = LocalDate.now(LearningProjection.studyZone).minusDays(89)
        val events = rows.flatMap { it.events }.filter { !it.isBlank && safeInstant(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate() >= since }
        val days = events.groupBy { safeInstant(it.occurredAt).atOffset(LearningProjection.studyZone).toLocalDate() }
        return ProgressAnalyticsDto(
            userId, if (attempts == 0) 0.0 else correct * 100.0 / attempts, rows.count { it.attempts > 0 }, rows.count { it.isMastered },
            ExamEngine.readinessForStoredExams(stored, LearningProjection.masteryByTopic(rows)), cutoff,
            rows.groupBy { it.disciplina }.map { (name, items) ->
                val count = items.sumOf { it.attempts }
                val distinctQ = items.count { it.attempts > 0 }
                DisciplineMasteryBarDto(
                    discipline = name,
                    accuracyPct = if (count == 0) 0.0 else items.sumOf { it.correct } * 100.0 / count,
                    totalAttempts = count,
                    masteredQuestions = items.count { it.isMastered },
                    pointsAccumulated = items.sumOf { it.points },
                    distinctQuestions = distinctQ
                )
            }.sortedBy { it.accuracyPct },
            ConfidenceMatrixSummaryDto(events.count { it.isCorrect && it.confidenceLevel >= 2 }, events.count { it.isCorrect && it.confidenceLevel < 2 }, events.count { !it.isCorrect && it.confidenceLevel < 2 }, events.count { !it.isCorrect && it.confidenceLevel >= 2 }),
            (0L..89L).map { offset -> val date = since.plusDays(offset); val daily = days[date].orEmpty(); DailyHeatmapCellDto(date.toString(), daily.size, if (daily.isEmpty()) 0.0 else daily.count { it.isCorrect } * 100.0 / daily.size) },
            calibrationCells = telemetry.calibrationCells,
            strategyGuidance = telemetry.strategyGuidance,
            fatigueReport = telemetry.fatigueReport,
            wrongAnswerPenalty = penalty,
            examGapSummary = telemetry.examGapSummary,
            threeSignalSummary = telemetry.threeSignalSummary,
            examScenarios = telemetry.examScenarios,
            entryMetrics = entryMetrics,
            studentLearningAnalytics = studentLearning
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
            val remote = objectivePracticeQuestions(api.generateQuizQuestions(QuizGeneratePayload(apiUserId(userId), mode, count, trunkFocusSlugs = focusedTrunkSlugs().toList(), contestId = scope, disciplina = disciplina, syllabusTopicId = syllabusTopicId, componentId = componentId)).map(QuestionCacheMapping::withRevision))
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
        val baseFiltered = all.filter { q ->
            (syllabusTopicId == null || q.canonicalTopicId == syllabusTopicId || syllabusTopicId in q.syllabusTopicIds) &&
                (disciplina == null || if (q.canonicalSubjectId != null) q.canonicalSubjectId == ExamCatalog.subjectIdForLabel(disciplina) else q.disciplina in ExamCatalog.equivalentLabels(disciplina)) &&
                if (mode == QuizMode.COMMON_TRUNK) q.canonicalTopicId in commonTopics else (exam == null || q.canonicalTopicId in topics.orEmpty() || (q.canonicalTopicId == null && exam.subjects.any { it.topics.any { t -> t.diffStatus != "ARQUIVADO" } && (it.displayName == q.disciplina || q.disciplina in ExamCatalog.legacyLabels(it.canonicalSubjectId)) }))
        }.filter { q -> exam == null || mode == QuizMode.SIMULADO || exam.subjects.any { s -> (q.canonicalTopicId == null || s.topics.any { it.diffStatus != "ARQUIVADO" && it.canonicalTopicId == q.canonicalTopicId }) && QuizSelection.allowed(decisions, exam, s, q.id, q.questionRevision) } }
        val pool = baseFiltered.filter { mode != QuizMode.SIMULADO || boardFilter.isEmpty() || it.source in boardFilter }
        val selected = when (mode) {
            QuizMode.SRS_REVIEW -> pool.filter { rows[it.id]?.let { r -> r.practiceAvailable != false && (r.reviewRequired || safeInstant(r.nextReviewAt) <= Instant.now()) } == true }.sortedBy { rows[it.id]?.nextReviewAt }.take(count)
            QuizMode.ERROR_NOTEBOOK -> pool.filter { rows[it.id]?.let { r -> r.practiceAvailable != false && r.inErrorNotebook } == true }.take(count)
            QuizMode.MOST_BADASS -> pool.filter { rows[it.id]?.let { r -> r.practiceAvailable != false && !r.isMastered && r.attempts > 0 } == true }.sortedBy { rows[it.id]?.accuracyPct }.take(count)
            QuizMode.ADAPTIVE_LOOP -> {
                val nonHoldout = pool.filterNot { StrategyEngine.isHoldoutReserved(userId, it.id, pool.size) }
                val candidatePool = if (nonHoldout.size >= count) nonHoldout else pool
                val nowInst = Instant.now()
                val nowMs = nowInst.toEpochMilli()
                val ledger = loadCtvLedger(userId)
                val cachedMap = candidatePool.associateBy { it.id }
                val (signalsByTopic, _) = computeMetacognitiveSignalsByTopic(rows.values.toList(), cachedMap, ledger, nowMs)
                val totalExamWeight = exam?.subjects?.filter { it.weight > 0.0 }?.sumOf { it.weight }?.coerceAtLeast(1.0) ?: 1.0
                val topicWeightMap = HashMap<String, Double>()
                exam?.subjects?.forEach { s ->
                    val nw = (s.weight / totalExamWeight).coerceAtLeast(0.05)
                    s.topics.filter { it.diffStatus != "ARQUIVADO" }.forEach { t ->
                        topicWeightMap[t.canonicalTopicId] = maxOf(topicWeightMap[t.canonicalTopicId] ?: 0.05, nw)
                    }
                }
                val rowsByTopic = rows.values.filter { !it.canonicalTopicId.isNullOrBlank() }.groupBy { it.canonicalTopicId!! }
                fun topicPriority(tid: String?): Double {
                    if (tid.isNullOrBlank()) return 0.05
                    val uRows = rowsByTopic[tid].orEmpty()
                    val dq = uRows.count { it.attempts > 0 }
                    val corr = uRows.sumOf { it.correct }
                    val lastMs = uRows.flatMap { it.events }.filter { !it.isBlank }.maxOfOrNull { safeInstant(it.occurredAt).toEpochMilli() } ?: 0L
                    val elDays = if (lastMs > 0L) ((nowMs - lastMs).coerceAtLeast(0L).toDouble() / 86_400_000.0) else 0.0
                    val stab = if (uRows.any { it.isMastered }) 14.0 else if (corr > 0) 4.0 else 0.0
                    val retr = if (dq > 0) RetentionEngine.retrievability(elDays, stab) else 0.0
                    val sig = signalsByTopic[tid] ?: MetacognitivePrioritySignals()
                    return PrioritizationEngine.priorityUtilityMetacognitive(
                        PrioritizationInputs(
                            editalWeight = topicWeightMap[tid] ?: 0.10,
                            incidenceWeight = 1.0,
                            retrievability = retr,
                            verifiedCompetence = dq >= DiagnosticEngine.MIN_QUESTIONS_FOR_MASTERY && uRows.all { it.isMastered },
                            distinctQuestions = dq,
                            avgSeconds = 60.0,
                            unmetPrerequisitesCount = 0,
                            dependentCount = 0
                        ),
                        sig
                    )
                }
                val due = candidatePool.filter { rows[it.id]?.let { r -> r.reviewRequired || safeInstant(r.nextReviewAt) <= nowInst } == true }
                    .sortedWith(compareByDescending<QuestionDto> { topicPriority(it.canonicalTopicId) }.thenBy { rows[it.id]?.nextReviewAt })
                val gaps = candidatePool.filter { rows[it.id]?.let { r -> r.inErrorNotebook || (!r.isMastered && r.attempts > 0) } == true }
                    .sortedWith(compareByDescending<QuestionDto> { topicPriority(it.canonicalTopicId) }.thenBy { rows[it.id]?.accuracyPct ?: 0.0 })
                val unseenByPriority = candidatePool.filter { rows[it.id] == null || rows[it.id]?.attempts == 0 }
                    .sortedByDescending { topicPriority(it.canonicalTopicId) }
                val fresh = unseenByPriority + candidatePool.sortedByDescending { topicPriority(it.canonicalTopicId) }
                PrioritizationEngine.interleaveBuckets(due, gaps, fresh, count) { it.id }
            }
            QuizMode.STRATEGY_BATTERY -> {
                val boardPrioritized = baseFiltered.sortedWith(
                    compareByDescending<QuestionDto> { StrategyEngine.isHoldoutReserved(userId, it.id, baseFiltered.size) }
                        .thenByDescending { boardFilter.isEmpty() || it.source in boardFilter }
                )
                boardPrioritized.take(count).map { q ->
                    if (boardFilter.isNotEmpty() && q.source !in boardFilter) q.copy(isBorrowedAnalogous = true) else q
                }
            }
            QuizMode.ENTRY_60S -> {
                val nonHoldout = pool.filterNot { StrategyEngine.isHoldoutReserved(userId, it.id, pool.size) }
                val candidatePool = if (nonHoldout.size >= count) nonHoldout else pool
                val nowInst = Instant.now()
                val nowMs = nowInst.toEpochMilli()
                val familiarTopics = rows.values.filter { it.attempts > 0 && !it.canonicalTopicId.isNullOrBlank() }.map { it.canonicalTopicId!! }.toSet()
                val pendingRetTopics = rows.values.filter {
                    !it.canonicalTopicId.isNullOrBlank() && (it.reviewRequired || safeInstant(it.nextReviewAt) <= nowInst)
                }.map { it.canonicalTopicId!! }.toSet()
                val recentIds = rows.values.filter { r ->
                    r.events.any { (nowMs - safeInstant(it.occurredAt).toEpochMilli()) in 0..(18L * 3600_000L) }
                }.map { it.questionId }.toSet()
                candidatePool.sortedByDescending { q ->
                    PrioritizationEngine.entry60sQuestionScore(
                        stemCharLen = q.question.length,
                        isTrueFalse = q.questionType == QuestionType.TRUE_FALSE,
                        isFamiliarTopic = q.canonicalTopicId in familiarTopics,
                        hasPendingRetention = q.canonicalTopicId in pendingRetTopics,
                        recentlyAnswered = q.id in recentIds
                    )
                }.take(count)
            }
            QuizMode.TRANSFER_PROBE -> {
                val targetKeys = boardFilter.map { normalizeBoardKey(it) }.toSet()
                baseFiltered.sortedWith(
                    compareByDescending<QuestionDto> { rows[it.id] == null || rows[it.id]?.attempts == 0 }
                        .thenByDescending { q ->
                            val bk = normalizeBoardKey(q.source)
                            bk.isNotEmpty() && bk != "origem não informada" && (targetKeys.isEmpty() || bk !in targetKeys)
                        }
                        .thenByDescending { StrategyEngine.isHoldoutReserved(userId, it.id, baseFiltered.size) }
                ).take(count).map { q ->
                    val bk = normalizeBoardKey(q.source)
                    if (targetKeys.isNotEmpty() && bk !in targetKeys) q.copy(isBorrowedAnalogous = true) else q
                }
            }
            QuizMode.SIMULADO -> {
                if (exam == null) emptyList() else {
                    val expected = exam.subjects.filter { it.topics.any { t -> t.diffStatus != "ARQUIVADO" } }.sumOf { it.questionCount ?: 0 }
                    val strictOrdered = pool.sortedByDescending { StrategyEngine.isHoldoutReserved(userId, it.id, pool.size) }
                    var activeSimPool = strictOrdered
                    var ids = QuizSelection.weightedSimuladoIds(exam, activeSimPool.map { QuestionRef(it.id, it.canonicalSubjectId, it.canonicalTopicId, it.questionRevision) }, decisions)
                    if (expected > 0 && ids.size < expected && boardFilter.isNotEmpty()) {
                        // Cross-Board Fallback automático quando a banca-alvo não preenche todas as cotas offline
                        activeSimPool = baseFiltered.sortedWith(
                            compareByDescending<QuestionDto> { it.source in boardFilter }
                                .thenByDescending { StrategyEngine.isHoldoutReserved(userId, it.id, baseFiltered.size) }
                        ).map { q -> if (q.source !in boardFilter) q.copy(isBorrowedAnalogous = true) else q }
                        ids = QuizSelection.weightedSimuladoIds(exam, activeSimPool.map { QuestionRef(it.id, it.canonicalSubjectId, it.canonicalTopicId, it.questionRevision) }, decisions)
                    }
                    require(expected > 0 && ids.size == expected) { "Cache insuficiente para o simulado: ${ids.size}/$expected questões. Conecte-se ou ajuste a grade." }
                    var offset = 0
                    exam.subjects.filter { it.topics.any { t -> t.diffStatus != "ARQUIVADO" } && (it.questionCount ?: 0) > 0 }.flatMap { subject ->
                        ids.drop(offset).take(subject.questionCount ?: 0).mapNotNull { id -> activeSimPool.find { it.id == id }?.copy(examComponentId = subject.localSubjectId) }.also { offset += subject.questionCount ?: 0 }
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

    suspend fun evaluateAndSupplyCtv(
        userId: Long,
        request: CtvEvaluateRequestDto,
        sourceQuestion: QuestionDto
    ): CtvEvaluateResponseDto {
        owner(userId)
        if (request.isBlank || (request.quizMode.equals("SIMULADO", ignoreCase = true) && !request.forcePostInstruction)) {
            return CtvEvaluateResponseDto(triggered = false, status = "not_triggered")
        }
        try {
            auth?.ensureSession(api, ownerUserId)
            val remote = api.evaluateCtv(request)
            val normalizedComp = remote.complementaryQuestion?.let(QuestionCacheMapping::withRevision)
            val resolvedOfferId = remote.offerId?.takeIf { it.isNotBlank() }
                ?: normalizedComp?.let { deterministicOfferId(userId, request.parentAttemptId, it.id) }
            if (remote.triggered && normalizedComp != null && resolvedOfferId != null) {
                mutationMutex.withLock {
                    val ledger = loadCtvLedger(userId)
                    val prevOffer = ledger.offers[resolvedOfferId]
                    val entry = LocalCtvOfferEntry(
                        offerId = resolvedOfferId,
                        parentAttemptId = request.parentAttemptId,
                        sourceQuestionId = request.sourceQuestionId,
                        complementaryQuestionId = normalizedComp.id,
                        contentRevision = normalizedComp.questionRevision,
                        canonicalTopicId = normalizedComp.canonicalTopicId ?: sourceQuestion.canonicalTopicId,
                        targetConceptId = remote.targetConceptId,
                        targetTestedRule = remote.targetTestedRule,
                        framingReasonCode = remote.framingReasonCode,
                        verificationMode = remote.verificationMode,
                        triggerReason = remote.triggerReason,
                        triggerCohort = remote.triggerCohort.ifBlank {
                            CtvAlignmentEngine.triggerCohortForReason(remote.triggerReason)
                        },
                        deliverySource = remote.deliverySource,
                        state = prevOffer?.state ?: "SUPPLIED",
                        suppliedAt = prevOffer?.suppliedAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString(),
                        firstDisplayedAt = prevOffer?.firstDisplayedAt,
                        dismissedAt = prevOffer?.dismissedAt
                    )
                    saveCtvLedger(userId, ledger.copy(offers = ledger.offers + (resolvedOfferId to entry)))
                }
            }
            if (normalizedComp != null && normalizedComp.questionType != QuestionType.OPEN_ENDED) {
                dao.insertQuestions(listOf(QuestionCacheMapping.toEntity(normalizedComp, json)))
                val decorated = decorateQuestions(listOf(normalizedComp)).firstOrNull() ?: normalizedComp
                return remote.copy(offerId = resolvedOfferId ?: remote.offerId, complementaryQuestion = decorated)
            }
            return remote.copy(offerId = resolvedOfferId ?: remote.offerId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fallback offline Database First: consulta o cache local com alinhamento conceitual e sem registrar exibição prematura
            if (request.sessionCtvCount >= 3) {
                return CtvEvaluateResponseDto(triggered = false, status = "cooldown_suppressed")
            }
            val dc = request.declaredConfidence
                ?: DeclaredConfidence.fromLegacyBand(request.confidenceLevel, true)
            val highPriority = (request.isCorrect && (dc == DeclaredConfidence.GUESS || request.cognitiveStrategy == CognitiveStrategy.RANDOM_GUESS)) ||
                (!request.isCorrect && dc?.isHigh == true)
            val mediumPriority = request.isCorrect && (
                dc == DeclaredConfidence.LOW ||
                    request.cognitiveStrategy == CognitiveStrategy.PATTERN_RECOGNITION ||
                    request.cognitiveStrategy == CognitiveStrategy.INTUITION
                )
            val periodicBucket = run {
                val dig = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("periodic:${request.parentAttemptId}:${request.sourceQuestionId}".toByteArray(Charsets.UTF_8))
                (dig[0].toInt() and 0xFF) % 100
            }
            val periodicTriggered = request.isCorrect && dc?.isHigh == true && periodicBucket < 15
            if (!highPriority &&
                !(mediumPriority && (request.sessionCtvCount == 0 || request.attemptsSinceLastCtv >= 2)) &&
                !(periodicTriggered && (request.sessionCtvCount == 0 || request.attemptsSinceLastCtv >= 2))
            ) {
                return CtvEvaluateResponseDto(triggered = false, status = "not_triggered")
            }
            val topicId = sourceQuestion.canonicalTopicId?.takeIf { it.isNotBlank() }
                ?: return CtvEvaluateResponseDto(triggered = false, status = "offline_unavailable")

            val sourceProfile = resolveAlignmentProfile(sourceQuestion)
            val cachedDtos = dao.getCachedQuestions(null, 5000).map { QuestionCacheMapping.withRevision(QuestionCacheMapping.toDto(it, json)) }
            val sameTopic = objectivePracticeQuestions(cachedDtos).filter { cand ->
                cand.id != sourceQuestion.id &&
                    cand.canonicalTopicId == topicId &&
                    cand.correctAnswerIndices.size == 1 &&
                    cand.correctAnswerIndices[0] in cand.answers.indices &&
                    QuestionCacheMapping.withinReviewDate(cand, LocalDate.now(LearningProjection.studyZone))
            }
            if (sameTopic.isEmpty()) {
                return CtvEvaluateResponseDto(triggered = false, status = "offline_unavailable")
            }
            val learningById = projectedLearningForQuestions(sameTopic.map { it.id } + sourceQuestion.id)
            val ledger = loadCtvLedger(userId)

            // Idempotência da mesma tentativa-mãe caso já tenha oferta fornecida localmente
            val existingSameParent = ledger.offers.values.firstOrNull { it.parentAttemptId == request.parentAttemptId }
            if (existingSameParent != null) {
                val existingQ = sameTopic.firstOrNull { it.id == existingSameParent.complementaryQuestionId }
                if (existingQ != null) {
                    val priorExp = hasPriorExposureExcludingOffer(
                        questionId = existingQ.id,
                        questionRevision = existingQ.questionRevision,
                        excludeOfferId = existingSameParent.offerId,
                        excludeAttemptId = null,
                        learningById = learningById,
                        ledger = ledger
                    )
                    return CtvEvaluateResponseDto(
                        triggered = true,
                        status = "supplied",
                        offerId = existingSameParent.offerId,
                        triggerReason = existingSameParent.triggerReason,
                        triggerCohort = existingSameParent.triggerCohort,
                        diagnosticPurpose = if (priorExp) "LONGITUDINAL_RETENTION" else existingSameParent.verificationMode,
                        verificationMode = existingSameParent.verificationMode,
                        framingReasonCode = existingSameParent.framingReasonCode,
                        targetConceptId = existingSameParent.targetConceptId,
                        targetTestedRule = existingSameParent.targetTestedRule,
                        deliverySource = "REUSED_LOCAL_CACHE",
                        verificationTiming = if (request.forcePostInstruction) "POST_INSTRUCTION" else "PRE_FEEDBACK",
                        wasPreviouslyExposed = priorExp,
                        complementaryQuestion = existingQ,
                        studentFramingPt = if (existingSameParent.verificationMode == "TOPIC_PRACTICE") {
                            "Prática complementar do tópico relacionada ao tema estudado."
                        } else if (priorExp) {
                            "Verificação rápida de retenção em outra questão deste tópico antes do gabarito."
                        } else {
                            "Vamos verificar este conceito em outro contexto antes de abrir o gabarito comentado."
                        }
                    )
                }
            }

            data class ScoredLocalCandidate(
                val question: QuestionDto,
                val mode: VerificationMode,
                val framingReasonCode: String,
                val framingReasonPt: String,
                val wasExposed: Boolean,
                val score: Int
            )

            val evaluated = sameTopic.mapNotNull { cand ->
                val candProfile = resolveAlignmentProfile(cand)
                val tentativeOfferId = deterministicOfferId(userId, request.parentAttemptId, cand.id)
                val wasExposed = hasPriorExposureExcludingOffer(
                    questionId = cand.id,
                    questionRevision = cand.questionRevision,
                    excludeOfferId = tentativeOfferId,
                    excludeAttemptId = null,
                    learningById = learningById,
                    ledger = ledger
                )
                when (val dec = CtvAlignmentEngine.evaluateCandidateAlignment(sourceProfile, candProfile, allowSeenReview = false)) {
                    is CandidateAlignmentDecision.Rejected -> null
                    is CandidateAlignmentDecision.Accepted -> {
                        val baseModeScore = when {
                            dec.mode == VerificationMode.CONCEPTUAL_TRANSFER && !wasExposed -> 300
                            dec.mode == VerificationMode.TOPIC_PRACTICE && !wasExposed -> 140
                            dec.mode == VerificationMode.CONCEPTUAL_TRANSFER && wasExposed -> 70
                            else -> 30
                        }
                        val crossBoardBonus = if (cand.source.isNotBlank() && cand.source != sourceQuestion.source) 15 else 0
                        ScoredLocalCandidate(
                            question = cand,
                            mode = if (wasExposed && dec.mode == VerificationMode.CONCEPTUAL_TRANSFER) {
                                VerificationMode.INTENTIONAL_REVIEW
                            } else {
                                dec.mode
                            },
                            framingReasonCode = if (wasExposed && dec.mode == VerificationMode.CONCEPTUAL_TRANSFER) {
                                "PRIOR_EXPOSURE_REVIEW"
                            } else {
                                dec.framingReasonCode
                            },
                            framingReasonPt = dec.framingReasonPt,
                            wasExposed = wasExposed,
                            score = baseModeScore + dec.applicationBonus + crossBoardBonus
                        )
                    }
                }
            }.sortedWith(compareByDescending<ScoredLocalCandidate> { it.score }.thenBy { it.question.id })

            val picked = evaluated.firstOrNull()
                ?: return CtvEvaluateResponseDto(triggered = false, status = "offline_unavailable")

            val triggerReason = when {
                periodicTriggered && !highPriority && !mediumPriority -> "PERIODIC_MASTERY_SAMPLING"
                highPriority && !request.isCorrect -> "HIGH_CONFIDENCE_MISCONCEPTION"
                highPriority -> "GUESS_HIT"
                else -> "LOW_CONFIDENCE_HIT"
            }
            val triggerCohort = CtvAlignmentEngine.triggerCohortForReason(triggerReason)
            val offerId = deterministicOfferId(userId, request.parentAttemptId, picked.question.id)
            // Importante (A04): `evaluateAndSupplyCtv` registra apenas `SUPPLIED` (`firstDisplayedAt = null`).
            // Transferência/pré-carregamento sem exibição na tela NÃO conta como exposição!
            mutationMutex.withLock {
                val curLedger = loadCtvLedger(userId)
                val prevOffer = curLedger.offers[offerId]
                val entry = LocalCtvOfferEntry(
                    offerId = offerId,
                    parentAttemptId = request.parentAttemptId,
                    sourceQuestionId = request.sourceQuestionId,
                    complementaryQuestionId = picked.question.id,
                    contentRevision = picked.question.questionRevision,
                    canonicalTopicId = picked.question.canonicalTopicId ?: topicId,
                    targetConceptId = sourceProfile.targetConceptId,
                    targetTestedRule = sourceProfile.testedRule,
                    framingReasonCode = picked.framingReasonCode,
                    verificationMode = picked.mode.code,
                    triggerReason = triggerReason,
                    triggerCohort = triggerCohort,
                    deliverySource = "REUSED_LOCAL_CACHE",
                    state = prevOffer?.state ?: "SUPPLIED",
                    suppliedAt = prevOffer?.suppliedAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString(),
                    firstDisplayedAt = prevOffer?.firstDisplayedAt,
                    dismissedAt = prevOffer?.dismissedAt
                )
                saveCtvLedger(userId, curLedger.copy(offers = curLedger.offers + (offerId to entry)))
            }

            val studentFraming = when (picked.mode) {
                VerificationMode.TOPIC_PRACTICE ->
                    "Prática complementar do tópico antes do gabarito comentado (conceito original permanece sem transferência medida)."
                VerificationMode.INTENTIONAL_REVIEW ->
                    "Verificação rápida de retenção em questão já conhecida deste tópico antes do gabarito."
                VerificationMode.CONCEPTUAL_TRANSFER ->
                    "Vamos verificar este conceito em outro contexto antes de abrir o gabarito comentado."
            }
            return CtvEvaluateResponseDto(
                triggered = true,
                status = "supplied",
                offerId = offerId,
                triggerReason = triggerReason,
                triggerCohort = triggerCohort,
                diagnosticPurpose = when {
                    picked.mode == VerificationMode.TOPIC_PRACTICE -> "TOPIC_PRACTICE"
                    picked.wasExposed -> "LONGITUDINAL_RETENTION"
                    else -> "CONCEPTUAL_TRANSFER"
                },
                verificationMode = picked.mode.code,
                framingReasonCode = picked.framingReasonCode,
                framingReasonPt = picked.framingReasonPt,
                targetConceptId = sourceProfile.targetConceptId,
                targetTestedRule = sourceProfile.testedRule,
                deliverySource = "REUSED_LOCAL_CACHE",
                verificationTiming = if (request.forcePostInstruction) "POST_INSTRUCTION" else "PRE_FEEDBACK",
                wasPreviouslyExposed = picked.wasExposed,
                complementaryQuestion = picked.question,
                studentFramingPt = studentFraming
            )
        }
    }

    suspend fun recordCtvDisplayed(
        userId: Long,
        evaluation: CtvEvaluateResponseDto,
        sourceQuestionId: String,
        parentAttemptId: String,
        occurredAtIso: String = Instant.now().toString()
    ): String {
        owner(userId)
        val compQ = evaluation.complementaryQuestion ?: return occurredAtIso
        val normalizedComp = QuestionCacheMapping.withRevision(compQ)
        val offerId = evaluation.offerId?.takeIf { it.isNotBlank() }
            ?: deterministicOfferId(userId, parentAttemptId, normalizedComp.id)
        var effectiveFirstDisplayed = occurredAtIso
        var eventToSend: CtvLifecycleEventRequestDto? = null

        mutationMutex.withLock {
            val ledger = loadCtvLedger(userId)
            val prev = ledger.offers[offerId]
            val firstDisp = prev?.firstDisplayedAt ?: occurredAtIso
            effectiveFirstDisplayed = firstDisp
            val nextState = when (prev?.state) {
                "ANSWERED", "DISMISSED" -> prev.state
                else -> "DISPLAYED"
            }
            val updatedOffer = LocalCtvOfferEntry(
                offerId = offerId,
                parentAttemptId = parentAttemptId,
                sourceQuestionId = sourceQuestionId,
                complementaryQuestionId = normalizedComp.id,
                contentRevision = normalizedComp.questionRevision,
                canonicalTopicId = normalizedComp.canonicalTopicId ?: prev?.canonicalTopicId,
                targetConceptId = evaluation.targetConceptId ?: prev?.targetConceptId,
                targetTestedRule = evaluation.targetTestedRule ?: prev?.targetTestedRule,
                framingReasonCode = evaluation.framingReasonCode ?: prev?.framingReasonCode,
                verificationMode = evaluation.verificationMode.ifBlank { prev?.verificationMode ?: "CONCEPTUAL_TRANSFER" },
                triggerReason = evaluation.triggerReason ?: prev?.triggerReason,
                triggerCohort = evaluation.triggerCohort.ifBlank {
                    CtvAlignmentEngine.triggerCohortForReason(evaluation.triggerReason ?: prev?.triggerReason)
                },
                deliverySource = evaluation.deliverySource ?: prev?.deliverySource,
                state = nextState,
                suppliedAt = prev?.suppliedAt?.takeIf { it.isNotBlank() } ?: firstDisp,
                firstDisplayedAt = firstDisp,
                dismissedAt = prev?.dismissedAt
            )
            val eventId = UUID.nameUUIDFromBytes("ctv-ev:$offerId:DISPLAYED".toByteArray(Charsets.UTF_8)).toString()
            val req = CtvLifecycleEventRequestDto(
                eventId = eventId,
                offerId = offerId,
                parentAttemptId = parentAttemptId,
                sourceQuestionId = sourceQuestionId,
                complementaryQuestionId = normalizedComp.id,
                questionRevision = normalizedComp.questionRevision,
                eventKind = "DISPLAYED",
                occurredAt = firstDisp,
                triggerReason = updatedOffer.triggerReason,
                verificationMode = updatedOffer.verificationMode,
                deliverySource = updatedOffer.deliverySource,
                targetConceptId = updatedOffer.targetConceptId,
                targetTestedRule = updatedOffer.targetTestedRule,
                framingReasonCode = updatedOffer.framingReasonCode
            )
            eventToSend = req
            val alreadyQueued = ledger.pendingEvents.any { it.offerId == offerId && it.eventKind == "DISPLAYED" }
            val nextPending = if (alreadyQueued || prev?.firstDisplayedAt != null) {
                ledger.pendingEvents
            } else {
                ledger.pendingEvents + req
            }
            saveCtvLedger(userId, ledger.copy(offers = ledger.offers + (offerId to updatedOffer), pendingEvents = nextPending))
        }

        eventToSend?.let { req ->
            val sent = runCatching {
                auth?.ensureSession(api, ownerUserId)
                api.recordCtvEvent(req)
                true
            }.getOrDefault(false)
            if (sent) {
                mutationMutex.withLock {
                    val cur = loadCtvLedger(userId)
                    saveCtvLedger(
                        userId,
                        cur.copy(pendingEvents = cur.pendingEvents.filterNot { it.offerId == offerId && it.eventKind == "DISPLAYED" })
                    )
                }
            }
        }
        return effectiveFirstDisplayed
    }

    suspend fun recordCtvDismissed(
        userId: Long,
        evaluation: CtvEvaluateResponseDto,
        sourceQuestionId: String,
        parentAttemptId: String,
        occurredAtIso: String = Instant.now().toString()
    ) {
        owner(userId)
        val compQ = evaluation.complementaryQuestion ?: return
        val normalizedComp = QuestionCacheMapping.withRevision(compQ)
        val offerId = evaluation.offerId?.takeIf { it.isNotBlank() }
            ?: deterministicOfferId(userId, parentAttemptId, normalizedComp.id)
        var eventToSend: CtvLifecycleEventRequestDto? = null

        mutationMutex.withLock {
            val ledger = loadCtvLedger(userId)
            val prev = ledger.offers[offerId]
            val firstDisp = prev?.firstDisplayedAt ?: occurredAtIso
            val dismissedAt = prev?.dismissedAt ?: occurredAtIso
            val nextState = if (prev?.state == "ANSWERED") "ANSWERED" else "DISMISSED"
            val updatedOffer = LocalCtvOfferEntry(
                offerId = offerId,
                parentAttemptId = parentAttemptId,
                sourceQuestionId = sourceQuestionId,
                complementaryQuestionId = normalizedComp.id,
                contentRevision = normalizedComp.questionRevision,
                canonicalTopicId = normalizedComp.canonicalTopicId ?: prev?.canonicalTopicId,
                targetConceptId = evaluation.targetConceptId ?: prev?.targetConceptId,
                targetTestedRule = evaluation.targetTestedRule ?: prev?.targetTestedRule,
                framingReasonCode = evaluation.framingReasonCode ?: prev?.framingReasonCode,
                verificationMode = evaluation.verificationMode.ifBlank { prev?.verificationMode ?: "CONCEPTUAL_TRANSFER" },
                triggerReason = evaluation.triggerReason ?: prev?.triggerReason,
                triggerCohort = evaluation.triggerCohort.ifBlank {
                    CtvAlignmentEngine.triggerCohortForReason(evaluation.triggerReason ?: prev?.triggerReason)
                },
                deliverySource = evaluation.deliverySource ?: prev?.deliverySource,
                state = nextState,
                suppliedAt = prev?.suppliedAt?.takeIf { it.isNotBlank() } ?: firstDisp,
                firstDisplayedAt = firstDisp,
                dismissedAt = dismissedAt
            )
            val eventId = UUID.nameUUIDFromBytes("ctv-ev:$offerId:DISMISSED".toByteArray(Charsets.UTF_8)).toString()
            val req = CtvLifecycleEventRequestDto(
                eventId = eventId,
                offerId = offerId,
                parentAttemptId = parentAttemptId,
                sourceQuestionId = sourceQuestionId,
                complementaryQuestionId = normalizedComp.id,
                questionRevision = normalizedComp.questionRevision,
                eventKind = "DISMISSED",
                occurredAt = dismissedAt,
                triggerReason = updatedOffer.triggerReason,
                verificationMode = updatedOffer.verificationMode,
                deliverySource = updatedOffer.deliverySource,
                targetConceptId = updatedOffer.targetConceptId,
                targetTestedRule = updatedOffer.targetTestedRule,
                framingReasonCode = updatedOffer.framingReasonCode
            )
            eventToSend = req
            val nextPending = ledger.pendingEvents.filterNot { it.offerId == offerId && it.eventKind == "DISMISSED" } + req
            saveCtvLedger(userId, ledger.copy(offers = ledger.offers + (offerId to updatedOffer), pendingEvents = nextPending))
        }

        eventToSend?.let { req ->
            val sent = runCatching {
                auth?.ensureSession(api, ownerUserId)
                api.recordCtvEvent(req)
                true
            }.getOrDefault(false)
            if (sent) {
                mutationMutex.withLock {
                    val cur = loadCtvLedger(userId)
                    saveCtvLedger(
                        userId,
                        cur.copy(pendingEvents = cur.pendingEvents.filterNot { it.offerId == offerId && it.eventKind == "DISMISSED" })
                    )
                }
            }
        }
    }

    suspend fun recordRecommendationFeedback(
        userId: Long,
        request: RecommendationFeedbackRequestDto
    ) {
        owner(userId)
        val stamped = if (request.occurredAt.isNullOrBlank()) {
            request.copy(occurredAt = Instant.now().toString())
        } else {
            request
        }
        val sent = runCatching {
            auth?.ensureSession(api, ownerUserId)
            api.recordRecommendationFeedback(stamped)
            true
        }.getOrDefault(false)
        if (!sent) {
            mutationMutex.withLock {
                val ledger = loadCtvLedger(userId)
                val deduped = ledger.pendingRecommendationFeedback.filterNot {
                    it.recommendationId == stamped.recommendationId && it.outcome == stamped.outcome
                } + stamped
                saveCtvLedger(userId, ledger.copy(pendingRecommendationFeedback = deduped))
            }
        }
    }

    suspend fun submitCtvAttemptOfflineResilient(
        userId: Long,
        request: CtvSubmitRequestDto,
        complementaryQuestion: QuestionDto,
        parentWasCorrect: Boolean
    ): CtvSubmitResponseDto {
        owner(userId)
        val normalizedComp = QuestionCacheMapping.withRevision(complementaryQuestion)
        val resolvedOfferId = request.offerId?.takeIf { it.isNotBlank() }
            ?: deterministicOfferId(userId, request.parentAttemptId, normalizedComp.id)
        val occurredIso = request.occurredAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val occurredMs = safeInstant(occurredIso).toEpochMilli()
        val hadDecisiveHelp = request.hadDecisiveHelp ||
            request.consultedResources.any(CtvAlignmentEngine::isRuleRevealingHelp)

        val learningById = projectedLearningForQuestions(listOf(normalizedComp.id, request.sourceQuestionId))
        val preLedger = loadCtvLedger(userId)
        val existingOffer = preLedger.offers[resolvedOfferId]
        val localPriorExposed = request.wasPreviouslyExposed || hasPriorExposureExcludingOffer(
            questionId = normalizedComp.id,
            questionRevision = normalizedComp.questionRevision,
            excludeOfferId = resolvedOfferId,
            excludeAttemptId = request.ctvAttemptId,
            learningById = learningById,
            ledger = preLedger,
            evaluatedAtMs = if (occurredMs > 0L) occurredMs else null
        )
        val resolvedMode = VerificationMode.fromCode(
            request.verificationMode ?: existingOffer?.verificationMode ?: "CONCEPTUAL_TRANSFER"
        )
        val resolvedTriggerReason = request.triggerReason ?: existingOffer?.triggerReason
        val resolvedCohort = existingOffer?.triggerCohort
            ?: CtvAlignmentEngine.triggerCohortForReason(resolvedTriggerReason)
        val displayedIso = request.displayedAt ?: existingOffer?.firstDisplayedAt ?: occurredIso
        val enrichedRequest = request.copy(
            offerId = resolvedOfferId,
            occurredAt = occurredIso,
            displayedAt = displayedIso,
            wasPreviouslyExposed = localPriorExposed,
            hadDecisiveHelp = hadDecisiveHelp,
            triggerReason = resolvedTriggerReason,
            deliverySource = request.deliverySource ?: existingOffer?.deliverySource,
            verificationMode = resolvedMode.code
        )

        try {
            auth?.ensureSession(api, ownerUserId)
            val remote = api.submitCtvAttempt(enrichedRequest)
            val verdict = CtvAlignmentEngine.evaluateCtvIndependence(
                CtvIndependenceInput(
                    verificationMode = VerificationMode.fromCode(remote.verificationMode),
                    action = "ANSWERED",
                    verificationTiming = remote.verificationTiming,
                    wasPreviouslyExposed = localPriorExposed && !remote.countsAsIndependentTransfer,
                    exposureHistoryKnown = remote.verificationTiming.uppercase() != "UNKNOWN_CONDITION",
                    hadDecisiveHelp = hadDecisiveHelp,
                    isCorrect = remote.isCorrect
                )
            )
            mutationMutex.withLock {
                val ledger = loadCtvLedger(userId)
                val prevOff = ledger.offers[resolvedOfferId]
                val nextOff = LocalCtvOfferEntry(
                    offerId = resolvedOfferId,
                    parentAttemptId = enrichedRequest.parentAttemptId,
                    sourceQuestionId = enrichedRequest.sourceQuestionId,
                    complementaryQuestionId = normalizedComp.id,
                    contentRevision = normalizedComp.questionRevision,
                    canonicalTopicId = normalizedComp.canonicalTopicId ?: prevOff?.canonicalTopicId,
                    targetConceptId = remote.targetConceptId ?: prevOff?.targetConceptId,
                    targetTestedRule = remote.targetTestedRule ?: prevOff?.targetTestedRule,
                    framingReasonCode = prevOff?.framingReasonCode,
                    verificationMode = remote.verificationMode,
                    triggerReason = resolvedTriggerReason,
                    triggerCohort = resolvedCohort,
                    deliverySource = enrichedRequest.deliverySource,
                    state = "ANSWERED",
                    suppliedAt = prevOff?.suppliedAt?.takeIf { it.isNotBlank() } ?: displayedIso,
                    firstDisplayedAt = prevOff?.firstDisplayedAt ?: displayedIso,
                    dismissedAt = prevOff?.dismissedAt
                )
                val attEntry = LocalCtvAttemptEntry(
                    ctvAttemptId = remote.ctvAttemptId,
                    offerId = remote.offerId.ifBlank { resolvedOfferId },
                    parentAttemptId = remote.parentAttemptId,
                    sourceQuestionId = enrichedRequest.sourceQuestionId,
                    complementaryQuestionId = remote.complementaryQuestionId,
                    contentRevision = normalizedComp.questionRevision,
                    canonicalTopicId = normalizedComp.canonicalTopicId ?: prevOff?.canonicalTopicId,
                    targetConceptId = remote.targetConceptId ?: prevOff?.targetConceptId,
                    targetTestedRule = remote.targetTestedRule ?: prevOff?.targetTestedRule,
                    verificationMode = remote.verificationMode,
                    verificationTiming = remote.verificationTiming,
                    isCorrect = remote.isCorrect,
                    wasPreviouslyExposed = !remote.countsAsIndependentTransfer && localPriorExposed,
                    hadDecisiveHelp = hadDecisiveHelp,
                    countsAsIndependentAttempt = remote.countsAsIndependentTransfer,
                    countsAsIndependentCorrect = remote.countsAsIndependentTransfer && remote.isCorrect,
                    classificationCode = remote.classificationCode.ifBlank { verdict.classificationCode },
                    triggerReason = resolvedTriggerReason,
                    triggerCohort = resolvedCohort,
                    occurredAt = occurredIso,
                    provisional = false
                )
                saveCtvLedger(
                    userId,
                    ledger.copy(
                        offers = ledger.offers + (resolvedOfferId to nextOff),
                        attempts = ledger.attempts + (remote.ctvAttemptId to attEntry),
                        pendingEvents = ledger.pendingEvents.filterNot { it.offerId == resolvedOfferId && it.eventKind == "DISPLAYED" }
                    )
                )
                metadata("cached_learning_analytics:$userId", "")
            }
            return remote
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val correctIdx = normalizedComp.correctAnswerIndices.firstOrNull() ?: 0
            val isCorrect = enrichedRequest.chosenIndex == correctIdx
            // Semântica idêntica ao servidor (Seção 2 / A03): `isCorrect` entra EXCLUSIVAMENTE no numerador!
            val verdict = CtvAlignmentEngine.evaluateCtvIndependence(
                CtvIndependenceInput(
                    verificationMode = resolvedMode,
                    action = "ANSWERED",
                    verificationTiming = enrichedRequest.verificationTiming,
                    wasPreviouslyExposed = localPriorExposed,
                    exposureHistoryKnown = enrichedRequest.verificationTiming.uppercase() != "UNKNOWN_CONDITION",
                    hadDecisiveHelp = hadDecisiveHelp,
                    isCorrect = isCorrect
                )
            )
            val independentAttempt = verdict.countsAsIndependentAttempt
            mutationMutex.withLock {
                val prior = metadata("pending_ctv_submissions")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { json.decodeFromString<List<PendingCtvSubmission>>(it) }.getOrDefault(emptyList()) }
                    .orEmpty()
                val next = prior.filterNot {
                    it.ownerUserId == userId && it.request.ctvAttemptId == enrichedRequest.ctvAttemptId
                } + PendingCtvSubmission(userId, enrichedRequest)
                metadata("pending_ctv_submissions", json.encodeToString(next))

                val ledger = loadCtvLedger(userId)
                val prevOff = ledger.offers[resolvedOfferId]
                val nextOff = LocalCtvOfferEntry(
                    offerId = resolvedOfferId,
                    parentAttemptId = enrichedRequest.parentAttemptId,
                    sourceQuestionId = enrichedRequest.sourceQuestionId,
                    complementaryQuestionId = normalizedComp.id,
                    contentRevision = normalizedComp.questionRevision,
                    canonicalTopicId = normalizedComp.canonicalTopicId ?: prevOff?.canonicalTopicId,
                    targetConceptId = prevOff?.targetConceptId,
                    targetTestedRule = prevOff?.targetTestedRule,
                    framingReasonCode = prevOff?.framingReasonCode,
                    verificationMode = resolvedMode.code,
                    triggerReason = resolvedTriggerReason,
                    triggerCohort = resolvedCohort,
                    deliverySource = enrichedRequest.deliverySource,
                    state = "ANSWERED",
                    suppliedAt = prevOff?.suppliedAt?.takeIf { it.isNotBlank() } ?: displayedIso,
                    firstDisplayedAt = prevOff?.firstDisplayedAt ?: displayedIso,
                    dismissedAt = prevOff?.dismissedAt
                )
                val attEntry = LocalCtvAttemptEntry(
                    ctvAttemptId = enrichedRequest.ctvAttemptId,
                    offerId = resolvedOfferId,
                    parentAttemptId = enrichedRequest.parentAttemptId,
                    sourceQuestionId = enrichedRequest.sourceQuestionId,
                    complementaryQuestionId = normalizedComp.id,
                    contentRevision = normalizedComp.questionRevision,
                    canonicalTopicId = normalizedComp.canonicalTopicId ?: prevOff?.canonicalTopicId,
                    targetConceptId = prevOff?.targetConceptId,
                    targetTestedRule = prevOff?.targetTestedRule,
                    verificationMode = resolvedMode.code,
                    verificationTiming = enrichedRequest.verificationTiming,
                    isCorrect = isCorrect,
                    wasPreviouslyExposed = localPriorExposed,
                    hadDecisiveHelp = hadDecisiveHelp,
                    countsAsIndependentAttempt = verdict.countsAsIndependentAttempt,
                    countsAsIndependentCorrect = verdict.countsAsIndependentCorrect,
                    classificationCode = verdict.classificationCode,
                    triggerReason = resolvedTriggerReason,
                    triggerCohort = resolvedCohort,
                    occurredAt = occurredIso,
                    provisional = true
                )
                saveCtvLedger(
                    userId,
                    ledger.copy(
                        offers = ledger.offers + (resolvedOfferId to nextOff),
                        attempts = ledger.attempts + (enrichedRequest.ctvAttemptId to attEntry)
                    )
                )
                metadata("cached_learning_analytics:$userId", "")
            }
            val (title, msg) = when {
                resolvedMode == VerificationMode.TOPIC_PRACTICE ->
                    "Prática complementar do tópico registrada" to "Atividade registrada no nível do tópico. O conceito da questão original permanece com transferência não mensurada."
                verdict.classificationCode == "POST_INSTRUCTION_FIXATION" ->
                    "Fixação pós-instrução registrada" to "Resposta registrada após consulta de explicação/regra; uma questão inédita retomará este conceito em outra sessão."
                parentWasCorrect && isCorrect && independentAttempt ->
                    "Transferência conceitual demonstrada" to "Você acertou a questão original e aplicou o mesmo conceito em contexto inédito antes do gabarito."
                parentWasCorrect && isCorrect ->
                    "Compreensão reforçada no conceito" to "Ambas as questões foram respondidas corretamente. Uma verificação inédita futura confirmará a transferência."
                parentWasCorrect && !isCorrect ->
                    "Domínio conceitual ainda não confirmado" to "A questão original foi respondida corretamente, mas houve divergência no novo contexto. Vale revisar a regra e suas exceções abaixo."
                !parentWasCorrect && isCorrect ->
                    "Recuperação conceitual observada" to "Apesar do erro na questão original, você aplicou o conceito corretamente na verificação complementar."
                else ->
                    "Lacuna conceitual identificada para revisão" to "As duas questões indicam oportunidade de revisão direcionada da regra central abaixo."
            }
            return CtvSubmitResponseDto(
                ctvAttemptId = enrichedRequest.ctvAttemptId,
                offerId = resolvedOfferId,
                parentAttemptId = enrichedRequest.parentAttemptId,
                complementaryQuestionId = enrichedRequest.complementaryQuestionId,
                isCorrect = isCorrect,
                correctIndex = correctIdx,
                explanation = normalizedComp.explanation,
                verificationTiming = enrichedRequest.verificationTiming,
                verificationMode = resolvedMode.code,
                countsAsIndependentTransfer = independentAttempt,
                classificationCode = verdict.classificationCode,
                targetConceptId = existingOffer?.targetConceptId,
                targetTestedRule = existingOffer?.targetTestedRule,
                consolidatedDiagnosticTitlePt = title,
                consolidatedDiagnosticMessagePt = msg
            )
        }
    }

    suspend fun recordEvidenceSelection(userId: Long, sessionUuid: String, questionId: String, evidenceId: String) {
        owner(userId)
        val cleanEvId = evidenceId.trim()
        if (cleanEvId.isEmpty()) return
        mutationMutex.withLock {
            transaction {
                val pendingRow = dao.findPendingAttemptInSession(sessionUuid, questionId)
                    ?.takeIf { it.ownerUserId == userId }
                dao.updateAttemptEvidence(sessionUuid, questionId, cleanEvId, userId)
                val resolvedAttemptId = pendingRow?.attemptId ?: deterministicAttemptId(sessionUuid, questionId)

                // Atualiza imediatamente o registro local já confirmado (caso o lote tenha sincronizado antes do toque)
                val state = durable
                if (state != null) {
                    val existingMap = learningMapForQuestions(listOf(questionId))
                    val curRecord = existingMap[questionId]
                    if (curRecord != null && curRecord.events.any { it.attemptId == resolvedAttemptId }) {
                        val updatedRecord = curRecord.copy(
                            events = curRecord.events.map { ev ->
                                if (ev.attemptId == resolvedAttemptId) ev.copy(selectedEvidenceId = cleanEvId) else ev
                            }
                        )
                        state.putLearning(listOf(LearningRecordEntity(questionId, updatedRecord.revision, json.encodeToString(updatedRecord))))
                    }
                    // Enfileira patch idempotente para sincronização com `POST /api/v1/quiz/attempt-evidence`
                    val priorPatches = metadata("pending_evidence_patches")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { runCatching { json.decodeFromString<List<PendingEvidencePatch>>(it) }.getOrDefault(emptyList()) }
                        .orEmpty()
                    val nextPatches = priorPatches.filterNot {
                        it.ownerUserId == userId && it.attemptId == resolvedAttemptId
                    } + PendingEvidencePatch(userId, resolvedAttemptId, questionId, cleanEvId)
                    metadata("pending_evidence_patches", json.encodeToString(nextPatches))
                }
            }
        }
    }

    internal suspend fun recordAttemptUnlocked(userId: Long, attempt: PendingAttemptEntity, session: SessionSnapshot? = null) {
        owner(userId); require(attempt.ownerUserId == userId); require(attempt.questionRevision.isNotEmpty()) { "A versão desta questão precisa ser atualizada antes de responder." }
        transaction {
            val previous = dao.findPendingAttemptInSession(attempt.sessionUuid, attempt.questionId)
            if (previous != null) {
                require(
                    previous.attemptId == attempt.attemptId &&
                        previous.isBlank == attempt.isBlank &&
                        previous.chosenIndex == attempt.chosenIndex &&
                        previous.confidenceLevel == attempt.confidenceLevel
                ) { "Resposta já registrada nesta sessão." }
                return@transaction
            }
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
                    require(
                        previous.attemptId == attempt.attemptId &&
                            previous.isBlank == attempt.isBlank &&
                            previous.chosenIndex == attempt.chosenIndex &&
                            previous.confidenceLevel == attempt.confidenceLevel
                    ) {
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
            api.saveOnboardingPreferences(profile.copy(userId = apiUserId(userId)))
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
                val payload = SubmitBatchPayload(apiUserId(userId), pending.map { it.payload() })
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
                            val snapshotAttempt = byId.getValue(ack.attemptId)
                            val latestEvidenceId = dao.findPendingAttemptInSession(snapshotAttempt.sessionUuid, snapshotAttempt.questionId)
                                ?.selectedEvidenceId ?: snapshotAttempt.selectedEvidenceId
                            val local = if (latestEvidenceId != snapshotAttempt.selectedEvidenceId) {
                                snapshotAttempt.copy(selectedEvidenceId = latestEvidenceId)
                            } else {
                                snapshotAttempt
                            }
                            val question = questionsById[local.questionId]
                            if (question != null && durable != null && (!local.isBlank || learningById[local.questionId] != null || ack.attemptsTotal > 0)) {
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

            // Sincroniza patches tardios de seleção de evidência (`POST /api/v1/quiz/attempt-evidence`)
            val rawPatches = metadata("pending_evidence_patches")
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString<List<PendingEvidencePatch>>(it) }.getOrDefault(emptyList()) }
                .orEmpty()
            if (rawPatches.isNotEmpty()) {
                val quarantinedIds = dao.getAllPendingAttempts()
                    .filter { it.ownerUserId == userId && it.syncError != null }
                    .map { it.attemptId }
                    .toSet()
                val remainingPatches = mutableListOf<PendingEvidencePatch>()
                for ((idx, patch) in rawPatches.withIndex()) {
                    if (patch.ownerUserId != userId) {
                        remainingPatches += patch
                        continue
                    }
                    try {
                        val resp = api.attachAttemptEvidence(
                            AttachAttemptEvidencePayload(
                                questionId = patch.questionId,
                                attemptId = patch.attemptId,
                                selectedEvidenceId = patch.selectedEvidenceId
                            )
                        )
                        if (resp.status == "applied" || resp.status == "already_applied") {
                            synced++
                        } else if (patch.attemptId !in quarantinedIds) {
                            // Retém com segurança em "pending_parent_attempt" até confirmação pelo servidor
                            remainingPatches += patch
                            transient = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401) {
                            val tail = rawPatches.subList(idx, rawPatches.size)
                            val preserved = remainingPatches + tail
                            mutationMutex.withLock {
                                metadata("pending_evidence_patches", json.encodeToString(preserved))
                            }
                            throw e
                        }
                        if (e.code() !in listOf(400, 404, 422)) {
                            remainingPatches += patch
                            transient = true
                        }
                    } catch (_: Exception) {
                        remainingPatches += patch
                        transient = true
                    }
                }
                mutationMutex.withLock {
                    metadata("pending_evidence_patches", if (remainingPatches.isEmpty()) "" else json.encodeToString(remainingPatches))
                }
            }

            // Sincroniza eventos de ciclo de vida CTV (`DISPLAYED` / `DISMISSED`), submissões CTV e reconcilia estado
            val initialLedger = loadCtvLedger(userId)
            var hadAnyCtvSyncActivity = initialLedger.offers.isNotEmpty() || initialLedger.attempts.isNotEmpty()
            if (initialLedger.pendingEvents.isNotEmpty()) {
                val orderedEvents = initialLedger.pendingEvents.sortedWith(
                    compareBy<CtvLifecycleEventRequestDto> { safeInstant(it.occurredAt.orEmpty()).toEpochMilli() }
                        .thenBy { if (it.eventKind == "DISPLAYED") 0 else 1 }
                )
                val remainingEvents = mutableListOf<CtvLifecycleEventRequestDto>()
                for ((idx, evReq) in orderedEvents.withIndex()) {
                    try {
                        api.recordCtvEvent(evReq)
                        synced++
                        hadAnyCtvSyncActivity = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401) {
                            val preserved = remainingEvents + orderedEvents.subList(idx, orderedEvents.size)
                            mutationMutex.withLock {
                                val cur = loadCtvLedger(userId)
                                saveCtvLedger(userId, cur.copy(pendingEvents = preserved))
                            }
                            throw e
                        }
                        if (e.code() !in listOf(400, 404, 422)) {
                            remainingEvents += evReq
                            transient = true
                        }
                    } catch (_: Exception) {
                        remainingEvents += evReq
                        transient = true
                    }
                }
                mutationMutex.withLock {
                    val cur = loadCtvLedger(userId)
                    saveCtvLedger(userId, cur.copy(pendingEvents = remainingEvents))
                }
            }

            // Sincroniza submissões CTV registradas offline (`POST /api/v1/quiz/ctv/submit`)
            val rawCtv = metadata("pending_ctv_submissions")
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString<List<PendingCtvSubmission>>(it) }.getOrDefault(emptyList()) }
                .orEmpty()
            if (rawCtv.isNotEmpty()) {
                val orderedCtv = rawCtv.sortedBy { safeInstant(it.request.occurredAt.orEmpty()).toEpochMilli() }
                val remainingCtv = mutableListOf<PendingCtvSubmission>()
                for ((idx, item) in orderedCtv.withIndex()) {
                    if (item.ownerUserId != userId) {
                        remainingCtv += item
                        continue
                    }
                    try {
                        val serverResp = api.submitCtvAttempt(item.request)
                        synced++
                        hadAnyCtvSyncActivity = true
                        mutationMutex.withLock {
                            val cur = loadCtvLedger(userId)
                            val prevAtt = cur.attempts[serverResp.ctvAttemptId]
                            if (prevAtt != null) {
                                val updatedAtt = prevAtt.copy(
                                    verificationMode = serverResp.verificationMode.ifBlank { prevAtt.verificationMode },
                                    verificationTiming = serverResp.verificationTiming.ifBlank { prevAtt.verificationTiming },
                                    isCorrect = serverResp.isCorrect,
                                    countsAsIndependentAttempt = serverResp.countsAsIndependentTransfer,
                                    countsAsIndependentCorrect = serverResp.countsAsIndependentTransfer && serverResp.isCorrect,
                                    classificationCode = serverResp.classificationCode.ifBlank { prevAtt.classificationCode },
                                    targetConceptId = serverResp.targetConceptId ?: prevAtt.targetConceptId,
                                    targetTestedRule = serverResp.targetTestedRule ?: prevAtt.targetTestedRule,
                                    provisional = false
                                )
                                saveCtvLedger(userId, cur.copy(attempts = cur.attempts + (serverResp.ctvAttemptId to updatedAtt)))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401) {
                            val preserved = remainingCtv + orderedCtv.subList(idx, orderedCtv.size)
                            mutationMutex.withLock {
                                metadata("pending_ctv_submissions", json.encodeToString(preserved))
                            }
                            throw e
                        }
                        if (e.code() !in listOf(400, 404, 422)) {
                            remainingCtv += item
                            transient = true
                        }
                    } catch (_: Exception) {
                        remainingCtv += item
                        transient = true
                    }
                }
                mutationMutex.withLock {
                    metadata("pending_ctv_submissions", if (remainingCtv.isEmpty()) "" else json.encodeToString(remainingCtv))
                }
            }

            // Sincroniza feedback de recomendação registrado offline (`POST /api/v1/study/recommendation-feedback`)
            val curForRec = loadCtvLedger(userId)
            if (curForRec.pendingRecommendationFeedback.isNotEmpty()) {
                val remainingFeedback = mutableListOf<RecommendationFeedbackRequestDto>()
                for (fb in curForRec.pendingRecommendationFeedback) {
                    val ok = runCatching {
                        api.recordRecommendationFeedback(fb)
                        true
                    }.getOrElse { err ->
                        if (err is CancellationException) throw err
                        if (err is retrofit2.HttpException && err.code() in listOf(400, 404, 422)) true else false
                    }
                    if (ok) synced++ else remainingFeedback += fb
                }
                mutationMutex.withLock {
                    val cur = loadCtvLedger(userId)
                    saveCtvLedger(userId, cur.copy(pendingRecommendationFeedback = remainingFeedback))
                }
            }

            // Reconcilia resultados CTV provisórios com o estado consolidado do servidor (A03, A05)
            if (hadAnyCtvSyncActivity) {
                runCatching { api.getCtvState() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                    ?.let { remoteState ->
                        mutationMutex.withLock {
                            val cur = loadCtvLedger(userId)
                            val mergedOffers = cur.offers.toMutableMap()
                            for (ro in remoteState.offers) {
                                val prev = mergedOffers[ro.offerId]
                                mergedOffers[ro.offerId] = LocalCtvOfferEntry(
                                    offerId = ro.offerId,
                                    parentAttemptId = ro.parentAttemptId.ifBlank { prev?.parentAttemptId.orEmpty() },
                                    sourceQuestionId = ro.sourceQuestionId.ifBlank { prev?.sourceQuestionId.orEmpty() },
                                    complementaryQuestionId = ro.complementaryQuestionId,
                                    contentRevision = ro.contentRevision.ifBlank { prev?.contentRevision.orEmpty() },
                                    canonicalTopicId = prev?.canonicalTopicId,
                                    targetConceptId = prev?.targetConceptId,
                                    targetTestedRule = prev?.targetTestedRule,
                                    framingReasonCode = prev?.framingReasonCode,
                                    verificationMode = ro.verificationMode.ifBlank { prev?.verificationMode ?: "CONCEPTUAL_TRANSFER" },
                                    triggerReason = ro.triggerReason ?: prev?.triggerReason,
                                    triggerCohort = ro.triggerCohort.ifBlank { prev?.triggerCohort ?: "DIFFICULTY_DETECTED" },
                                    deliverySource = prev?.deliverySource,
                                    state = ro.state.ifBlank { prev?.state ?: "SUPPLIED" },
                                    suppliedAt = prev?.suppliedAt.orEmpty(),
                                    firstDisplayedAt = ro.firstDisplayedAt ?: prev?.firstDisplayedAt,
                                    dismissedAt = ro.dismissedAt ?: prev?.dismissedAt
                                )
                            }
                            val mergedAttempts = cur.attempts.toMutableMap()
                            for (ra in remoteState.attempts) {
                                val prev = mergedAttempts[ra.ctvAttemptId]
                                mergedAttempts[ra.ctvAttemptId] = LocalCtvAttemptEntry(
                                    ctvAttemptId = ra.ctvAttemptId,
                                    offerId = ra.offerId.ifBlank { prev?.offerId.orEmpty() },
                                    parentAttemptId = ra.parentAttemptId.ifBlank { prev?.parentAttemptId.orEmpty() },
                                    sourceQuestionId = ra.sourceQuestionId.ifBlank { prev?.sourceQuestionId.orEmpty() },
                                    complementaryQuestionId = ra.complementaryQuestionId,
                                    contentRevision = ra.contentRevision.ifBlank { prev?.contentRevision.orEmpty() },
                                    canonicalTopicId = prev?.canonicalTopicId,
                                    targetConceptId = ra.targetConceptId ?: prev?.targetConceptId,
                                    targetTestedRule = ra.targetTestedRule ?: prev?.targetTestedRule,
                                    verificationMode = ra.verificationMode.ifBlank { prev?.verificationMode ?: "CONCEPTUAL_TRANSFER" },
                                    verificationTiming = ra.verificationTiming,
                                    isCorrect = ra.isCorrect,
                                    wasPreviouslyExposed = ra.wasPreviouslyExposed,
                                    hadDecisiveHelp = prev?.hadDecisiveHelp ?: false,
                                    countsAsIndependentAttempt = ra.countsAsIndependentTransfer,
                                    countsAsIndependentCorrect = ra.countsAsIndependentTransfer && ra.isCorrect,
                                    classificationCode = ra.classificationCode,
                                    triggerReason = ra.triggerReason ?: prev?.triggerReason,
                                    triggerCohort = ra.triggerCohort.ifBlank { prev?.triggerCohort ?: "DIFFICULTY_DETECTED" },
                                    occurredAt = ra.occurredAt.ifBlank { prev?.occurredAt.orEmpty() },
                                    provisional = false
                                )
                            }
                            saveCtvLedger(userId, cur.copy(offers = mergedOffers, attempts = mergedAttempts))
                            metadata("cached_learning_analytics:$userId", "")
                        }
                    }
            }

            durable?.reports(userId)?.forEach { report ->
                if (metadata("report_error:${report.eventId}").isNullOrBlank()) {
                    try {
                        api.reportQuestionProblem(report.questionId, ReportProblemPayload(apiUserId(userId), report.category))
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
                            val accepted = page.records
                                .filter { incoming -> (current[incoming.questionId]?.attempts ?: 0) <= incoming.attempts }
                                .map { incoming ->
                                    val localRec = current[incoming.questionId]
                                    if (localRec == null) {
                                        incoming
                                    } else {
                                        val localEvById = localRec.events.associateBy { it.attemptId }
                                        incoming.copy(
                                            events = incoming.events.map { incEv ->
                                                val locEv = localEvById[incEv.attemptId]
                                                if (incEv.selectedEvidenceId == null && locEv?.selectedEvidenceId != null) {
                                                    incEv.copy(selectedEvidenceId = locEv.selectedEvidenceId)
                                                } else {
                                                    incEv
                                                }
                                            }
                                        )
                                    }
                                }
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

    private fun PendingAttemptEntity.payload() = AnswerAttemptPayload(
        attemptId = attemptId,
        occurredAt = Instant.ofEpochMilli(createdAtEpochMs).toString(),
        questionRevision = questionRevision,
        questionId = questionId,
        chosenIndex = if (isBlank) 0 else chosenIndex,
        chosenText = if (isBlank) "" else chosenText,
        isCorrect = if (isBlank) false else isCorrect,
        timeSpentSeconds = timeSpentSeconds,
        confidenceLevel = confidenceLevel,
        sessionUuid = sessionUuid,
        quizMode = quizMode,
        examBlock = examBlock,
        contestId = contestId,
        userNote = userNote,
        canonicalSubjectId = canonicalSubjectId,
        canonicalTopicId = canonicalTopicId,
        examComponentId = examComponentId,
        isBlank = isBlank,
        confidenceExplicit = confidenceExplicit,
        selectedEvidenceId = selectedEvidenceId,
        positionInSession = positionInSession,
        elapsedSessionSeconds = elapsedSessionSeconds,
        sessionInterrupted = sessionInterrupted,
        declaredConfidence = parseDeclaredConfidence(declaredConfidence),
        cognitiveStrategy = parseCognitiveStrategy(cognitiveStrategy),
        strategySkipped = strategySkipped,
        activeSeconds = activeSeconds,
        answerChangeCount = answerChangeCount,
        initialChosenIndex = initialChosenIndex,
        consultedResources = parseConsultedResources(consultedResourcesCsv),
        focusLossCount = focusLossCount
    )

    private suspend fun refreshQuestionCache() {
        val blocked = metadata("cache_unavailable_ids")?.let { runCatching { json.decodeFromString<List<String>>(it).toMutableSet() }.getOrDefault(mutableSetOf()) } ?: mutableSetOf()
        for (ids in dao.getCachedQuestions(null, 10000).map { it.id }.chunked(200)) {
            val response = api.cacheState(CacheStatePayload(ids))
            require(response.projectionVersion == 1 && response.questions.map { it.questionId }.toSet() == ids.toSet()) { "Resposta de cache incompleta; estado anterior preservado." }
            mutationMutex.withLock {
                transaction {
                    val existingById = dao.findQuestionsByIds(ids).associateBy { it.id }
                    response.questions.forEach { row ->
                        if (row.question == null) blocked += row.questionId else {
                            require(row.question.id == row.questionId)
                            blocked -= row.questionId
                            val mapped = QuestionCacheMapping.toEntity(row.question, json)
                            val prev = existingById[row.questionId]
                            val preserved = if (prev != null && row.question.userStats == null) {
                                mapped.copy(
                                    inErrorNotebook = prev.inErrorNotebook,
                                    isMastered = prev.isMastered,
                                    userNote = prev.userNote
                                )
                            } else {
                                mapped
                            }
                            dao.insertQuestions(listOf(preserved))
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
                    "PENDING_PUBLISH" -> api.publishEdital(snapshot.examId, PublishEditalPayload(snapshot.revision, snapshot.subjects.flatMap { s -> s.topics.filter { it.diffStatus != "ARQUIVADO" }.map { PublishedTopicPayload(it.canonicalTopicId, it.canonicalSubjectId, it.displayTitle, localSubjectId = s.localSubjectId) } }))
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
        val isPreAnswer = kind in setOf("contextual-hint", "child-friendly-pre", "dr-sincero", "term-lookup")
        return try { auth?.ensureSession(api, ownerUserId); api.requestAiTutor(questionId, kind, hintIndex).contentMarkdown }
        catch (e: CancellationException) { throw e } catch (e: retrofit2.HttpException) {
            val detail = e.backendMessage()
            val reason = detail ?: "Serviço de IA indisponível no momento (HTTP ${e.code()})."
            if (isPreAnswer) "Tutor indisponível ($reason).\n\nA ajuda pré-resposta não foi gerada; sua resposta continua sem interferência."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário já disponível ($reason):\n\n$it" } ?: "Tutor indisponível: $reason"
        } catch (_: java.io.IOException) {
            if (isPreAnswer) "Tutor indisponível. A ajuda pré-resposta não foi gerada; sua resposta continua sem interferência."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário já disponível (tutor indisponível):\n\n$it" } ?: "Tutor indisponível. Tente novamente quando houver conexão."
        } catch (_: Exception) {
            if (isPreAnswer) "Tutor indisponível. Não foi possível processar a ajuda pré-resposta; sua resposta continua sem interferência."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário já disponível (tutor indisponível):\n\n$it" } ?: "Tutor indisponível. Não foi possível processar a resposta do serviço. Tente novamente."
        }
    }

    suspend fun requestAiTermLookup(questionId: String, selectedText: String, isAnswerSubmitted: Boolean): String {
        val trimmed = selectedText.trim().take(240)
        if (trimmed.isEmpty()) return "Selecione um termo ou trecho do enunciado para consultar."
        val kind = if (isAnswerSubmitted) "term-lookup-post" else "term-lookup"
        val isPreAnswer = !isAnswerSubmitted
        return try {
            auth?.ensureSession(api, ownerUserId)
            api.requestAiTutorWithContext(
                questionId = questionId,
                kind = kind,
                payload = AiAssistRequestPayload(userNote = trimmed)
            ).contentMarkdown
        } catch (e: CancellationException) {
            throw e
        } catch (e: retrofit2.HttpException) {
            val reason = e.backendMessage() ?: "Serviço de IA indisponível (HTTP ${e.code()})."
            if (isPreAnswer) "Consulta IA indisponível ($reason). Use os cartões normativos abaixo ou a Pesquisa Google em Camada."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário local disponível ($reason):\n\n$it" }
                ?: "Consulta IA indisponível ($reason)."
        } catch (_: Exception) {
            if (isPreAnswer) "Sem conexão para consulta generativa agora. Use as fontes normativas locais abaixo ou a Pesquisa Google em Camada."
            else dao.findQuestionById(questionId)?.explanation?.let { "Comentário local disponível (offline):\n\n$it" }
                ?: "Sem conexão para consulta generativa agora."
        }
    }

    suspend fun lookupQuestionContext(question: QuestionDto, selectedText: String): ContextualLookupResponseDto {
        val trimmed = selectedText.trim().take(240)
        val disciplina = question.disciplina.trim().ifEmpty { "Concursos Públicos" }
        val (localNorm, localConcepts) = ExamCatalog.lookupCatalogMatches(trimmed)
        val contextualQuery = "$trimmed $disciplina"
        val googleUrl = "https://www.google.com/search?hl=pt-BR&q=" + URLEncoder.encode(contextualQuery, "UTF-8")
        val planaltoUrl = "https://www.google.com/search?hl=pt-BR&q=" + URLEncoder.encode("site:planalto.gov.br $trimmed", "UTF-8")
        val jurispUrl = "https://www.google.com/search?hl=pt-BR&q=" + URLEncoder.encode("(STF OR STJ) $trimmed", "UTF-8")
        val fallback = ContextualLookupResponseDto(
            questionId = question.id,
            query = trimmed,
            disciplina = disciplina,
            normativeMatches = localNorm,
            conceptMatches = localConcepts,
            encyclopedicSummary = null,
            googleSearchUrl = googleUrl,
            planaltoSearchUrl = planaltoUrl,
            jurisprudenceSearchUrl = jurispUrl
        )
        if (trimmed.isEmpty()) return fallback
        return try {
            auth?.ensureSession(api, ownerUserId)
            val remote = api.lookupQuestionContext(question.id, ContextualLookupRequestDto(trimmed))
            remote.copy(
                normativeMatches = remote.normativeMatches.ifEmpty { localNorm },
                conceptMatches = remote.conceptMatches.ifEmpty { localConcepts }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
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

    suspend fun getLocalStorageStats(): Pair<Int, Int> {
        val pendingAttemptsCount = dao.getAllPendingAttempts().count { it.ownerUserId == ownerUserId }
        val pendingReportsCount = durable?.reports(ownerUserId)?.size ?: 0
        val pendingExamsCount = examDao.listPendingExams().size
        val pendingPatchesCount = metadata("pending_evidence_patches")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<PendingEvidencePatch>>(it) }.getOrDefault(emptyList()) }
            .orEmpty()
            .count { it.ownerUserId == ownerUserId }
        val pendingCtvCount = metadata("pending_ctv_submissions")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<PendingCtvSubmission>>(it) }.getOrDefault(emptyList()) }
            .orEmpty()
            .count { it.ownerUserId == ownerUserId }
        val ledger = loadCtvLedger(ownerUserId)
        val pendingCtvEventsCount = ledger.pendingEvents.size + ledger.pendingRecommendationFeedback.size
        val dirtyMissionsCount = loadLocalMissionsEnvelope().missions.values.count { it.dirty }
        val totalPending = pendingAttemptsCount + pendingReportsCount + pendingExamsCount + pendingPatchesCount + pendingCtvCount + pendingCtvEventsCount + dirtyMissionsCount
        return dao.countCachedQuestions() to totalPending
    }

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

    // --- Módulo Missões (Sincronização Online + Persistência Local por Conta + Cache de Vídeo) ---

    private suspend fun loadLocalMissionsEnvelope(): LocalMissionsStateEnvelope {
        val raw = metadata("missions_state_v1")?.takeIf { it.isNotBlank() } ?: return LocalMissionsStateEnvelope(ownerUserId)
        val parsed = runCatching { json.decodeFromString<LocalMissionsStateEnvelope>(raw) }.getOrNull()
        return if (parsed != null && parsed.ownerUserId == ownerUserId) parsed else LocalMissionsStateEnvelope(ownerUserId)
    }

    private suspend fun saveLocalMissionsEnvelope(env: LocalMissionsStateEnvelope) {
        metadata("missions_state_v1", json.encodeToString(env.copy(ownerUserId = ownerUserId)))
    }

    private fun parseRemoteNarrativeProgress(obj: JsonObject?, fallback: NarrativeProgressView): NarrativeProgressView {
        if (obj == null) return fallback
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: fallback.status
        val completedIds = obj["completed_scene_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: fallback.completedSceneIds
        val posMap = obj["scene_positions_ms"]?.jsonObject?.entries?.mapNotNull { (k, v) ->
            v.jsonPrimitive.longOrNull?.let { k to it }
        }?.toMap() ?: fallback.scenePositionsMs
        return NarrativeProgressView(
            status = status,
            statusLabelPt = obj["status_label_pt"]?.jsonPrimitive?.contentOrNull ?: MissionsCatalogLoader.statusLabelPt(status),
            currentSceneId = obj["current_scene_id"]?.jsonPrimitive?.contentOrNull ?: fallback.currentSceneId,
            currentSceneOrder = obj["current_scene_order"]?.jsonPrimitive?.intOrNull ?: fallback.currentSceneOrder,
            completedSceneIds = completedIds,
            completedScenesCount = obj["completed_scenes_count"]?.jsonPrimitive?.intOrNull ?: completedIds.size,
            totalScenesCount = obj["total_scenes_count"]?.jsonPrimitive?.intOrNull ?: fallback.totalScenesCount,
            progressPct = obj["progress_pct"]?.jsonPrimitive?.doubleOrNull ?: fallback.progressPct,
            scenePositionsMs = posMap,
            completedRunsCount = obj["completed_runs_count"]?.jsonPrimitive?.intOrNull ?: fallback.completedRunsCount,
        )
    }

    private fun parseRemoteInteractionPerformance(obj: JsonObject?, fallback: InteractionPerformanceView): InteractionPerformanceView {
        if (obj == null) return fallback
        return InteractionPerformanceView(
            answeredDecisionsCount = obj["answered_decisions_count"]?.jsonPrimitive?.intOrNull ?: fallback.answeredDecisionsCount,
            totalDecisionsCount = obj["total_decisions_count"]?.jsonPrimitive?.intOrNull ?: fallback.totalDecisionsCount,
            firstAttemptCorrectCount = obj["first_attempt_correct_count"]?.jsonPrimitive?.intOrNull ?: fallback.firstAttemptCorrectCount,
            latestCorrectCount = obj["latest_correct_count"]?.jsonPrimitive?.intOrNull ?: fallback.latestCorrectCount,
            conceptsForReview = obj["concepts_for_review"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: fallback.conceptsForReview,
        )
    }

    private data class LocalMissionQuestionCounts(
        val available: Int,
        val practiced: Int,
        val mastered: Int,
    )

    private suspend fun localMissionHumanQuestionStats(curriculum: CurriculumBindingSpec): LocalMissionQuestionCounts {
        val cachedDtos = runCatching { dao.getCachedQuestions(null, 10000).map { QuestionCacheMapping.toDto(it, json) } }.getOrDefault(emptyList())
        val targetTopics = (curriculum.primaryTopicIds + curriculum.fallbackTopicIds).toSet()
        val eligible = cachedDtos.filter { q ->
            q.correctAnswerIndices.size == 1 &&
                q.correctAnswerIndices[0] in q.answers.indices &&
                ((q.canonicalTopicId != null && q.canonicalTopicId in targetTopics) || q.canonicalSubjectId == curriculum.canonicalSubjectId)
        }
        val learning = runCatching { projectedLearningForQuestions(eligible.map { it.id }) }.getOrDefault(emptyMap())
        val practicedCount = eligible.count { (learning[it.id]?.attempts ?: 0) > 0 }
        val masteredCount = eligible.count { learning[it.id]?.isMastered == true }
        return LocalMissionQuestionCounts(eligible.size, practicedCount, masteredCount)
    }

    private suspend fun submitRecordedDecisionSteps(missionId: String, dec: LocalRecordedDecision) {
        if (dec.firstSelectedOptionId.isNotBlank() && dec.firstSelectedOptionId != dec.latestSelectedOptionId) {
            api.submitMissionDecision(
                missionId,
                buildJsonObject {
                    put("scene_id", dec.sceneId)
                    put("interaction_id", dec.interactionId)
                    put("selected_option_id", dec.firstSelectedOptionId)
                    put("time_spent_ms", 1000L)
                    put("used_transcript_fallback", false)
                }
            )
        }
        api.submitMissionDecision(
            missionId,
            buildJsonObject {
                put("scene_id", dec.sceneId)
                put("interaction_id", dec.interactionId)
                put("selected_option_id", dec.latestSelectedOptionId)
                put("time_spent_ms", 1000L)
                put("used_transcript_fallback", false)
            }
        )
    }

    private suspend fun flushDirtyMissionRecord(missionId: String, rec: LocalMissionProgressRecord): Boolean {
        if (!rec.dirty) return true
        return runCatching {
            auth?.ensureSession(api, ownerUserId)
            val spec = runCatching { MissionsCatalogLoader.loadBundled() }.getOrNull()
            val missionSpec = spec?.careers?.asSequence()
                ?.flatMap { it.disciplines.asSequence() }
                ?.flatMap { it.seasons.asSequence() }
                ?.flatMap { it.missions.asSequence() }
                ?.firstOrNull { it.missionId == missionId }
            val orderedScenes = missionSpec?.scenes?.sortedBy { it.order }.orEmpty()
            val sceneOrderById = orderedScenes.associate { it.sceneId to it.order }
            val terminalSceneIds = orderedScenes.filter { it.nextSceneId == null }.map { it.sceneId }.toSet()
            val curScene = rec.currentSceneId.takeIf { it.isNotBlank() }
            val completedSet = rec.completedSceneIds.toSet()

            val orderedDecisions = rec.decisions.values.sortedWith(
                compareBy<LocalRecordedDecision> { sceneOrderById[it.sceneId] ?: Int.MAX_VALUE }
                    .thenBy { it.interactionId }
            )
            val decisionsByScene = orderedDecisions.groupBy { it.sceneId }
            val handledDecisionIds = HashSet<String>()
            val syncedCompletedIds = HashSet<String>()
            val syncedPositionScenes = HashSet<String>()

            // 1. Cenas anteriores na ordem declarativa (decisões 1ª/última tentativa, conclusão e posição)
            for (sc in orderedScenes.filter { it.sceneId !in terminalSceneIds }) {
                for (dec in decisionsByScene[sc.sceneId].orEmpty()) {
                    submitRecordedDecisionSteps(missionId, dec)
                    handledDecisionIds += dec.interactionId
                }
                val isCompleted = sc.sceneId in completedSet
                val nonFinalPos = if (sc.sceneId != curScene) rec.scenePositionsMs[sc.sceneId] else null
                if (isCompleted) {
                    api.updateMissionProgress(
                        missionId,
                        buildJsonObject {
                            put("completed_scene_id", sc.sceneId)
                            if (nonFinalPos != null) {
                                put("current_scene_id", sc.sceneId)
                                put("playback_position_ms", nonFinalPos)
                            }
                        }
                    )
                    syncedCompletedIds += sc.sceneId
                    if (nonFinalPos != null) syncedPositionScenes += sc.sceneId
                } else if (nonFinalPos != null) {
                    val priorRequiredAnswered = orderedScenes
                        .filter { it.order < sc.order && it.requiresDecisionToAdvance }
                        .all { p -> p.interaction?.interactionId?.let { rec.decisions.containsKey(it) } == true }
                    if (priorRequiredAnswered || rec.status == "COMPLETED") {
                        api.updateMissionProgress(
                            missionId,
                            buildJsonObject {
                                put("current_scene_id", sc.sceneId)
                                put("playback_position_ms", nonFinalPos)
                            }
                        )
                        syncedPositionScenes += sc.sceneId
                    }
                }
            }

            for (doneId in rec.completedSceneIds) {
                if (doneId !in syncedCompletedIds && doneId !in terminalSceneIds) {
                    api.updateMissionProgress(
                        missionId,
                        buildJsonObject { put("completed_scene_id", doneId) }
                    )
                    syncedCompletedIds += doneId
                }
            }

            // 2. Decisões restantes (incluindo cena terminal) já ordenadas por cena
            for (dec in orderedDecisions) {
                if (dec.interactionId !in handledDecisionIds) {
                    submitRecordedDecisionSteps(missionId, dec)
                    handledDecisionIds += dec.interactionId
                }
            }

            for (doneId in rec.completedSceneIds) {
                if (doneId !in syncedCompletedIds && decisionsByScene[doneId].isNullOrEmpty()) {
                    api.updateMissionProgress(
                        missionId,
                        buildJsonObject { put("completed_scene_id", doneId) }
                    )
                    syncedCompletedIds += doneId
                }
            }

            // 3. Posições restantes de cenas != curScene antes de definir current_scene_id final
            val remainingPositions = rec.scenePositionsMs.entries
                .filter { (sid, _) -> sid != curScene && sid !in syncedPositionScenes }
                .sortedBy { (sid, _) -> sceneOrderById[sid] ?: Int.MAX_VALUE }
            for ((sid, pos) in remainingPositions) {
                api.updateMissionProgress(
                    missionId,
                    buildJsonObject {
                        put("current_scene_id", sid)
                        put("playback_position_ms", pos)
                    }
                )
            }

            // 4. Define current_scene_id final, posição da cena atual e eventual conclusão
            api.updateMissionProgress(
                missionId,
                buildJsonObject {
                    curScene?.let { put("current_scene_id", it) }
                    curScene?.let { sid -> rec.scenePositionsMs[sid]?.let { pos -> put("playback_position_ms", pos) } }
                    if (rec.status == "COMPLETED") put("mark_mission_completed", true)
                }
            )
            mutationMutex.withLock {
                val latestEnv = loadLocalMissionsEnvelope()
                val cur = latestEnv.missions[missionId]
                if (cur == rec) {
                    saveLocalMissionsEnvelope(latestEnv.copy(missions = latestEnv.missions + (missionId to cur.copy(dirty = false))))
                }
            }
            true
        }.getOrDefault(false)
    }

    suspend fun loadMissionsCatalog(): MissionsCatalogView {
        val spec = MissionsCatalogLoader.loadBundled()
        var env = loadLocalMissionsEnvelope()
        val localView = MissionsCatalogLoader.buildCatalogView(spec, env.missions)

        return try {
            auth?.let { runCatching { it.ensureSession(api, ownerUserId) } }
            for ((mid, rec) in env.missions) {
                if (rec.dirty) flushDirtyMissionRecord(mid, rec)
            }
            env = loadLocalMissionsEnvelope()
            val refreshedLocalView = MissionsCatalogLoader.buildCatalogView(spec, env.missions)
            val remote = api.listMissions()
            val remoteByMission = mutableMapOf<String, Pair<JsonObject?, JsonObject?>>()
            remote["careers"]?.jsonArray?.forEach { cEl ->
                cEl.jsonObject["disciplines"]?.jsonArray?.forEach { dEl ->
                    dEl.jsonObject["seasons"]?.jsonArray?.forEach { sEl ->
                        sEl.jsonObject["missions"]?.jsonArray?.forEach { mEl ->
                            val mObj = mEl.jsonObject
                            val mid = mObj["mission_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            remoteByMission[mid] = mObj["narrative_progress"]?.jsonObject to mObj["interaction_performance"]?.jsonObject
                        }
                    }
                }
            }
            val mergedCards = refreshedLocalView.cards.map { card ->
                val localRec = env.missions[card.missionId]
                val pair = remoteByMission[card.missionId]
                if (pair == null || localRec?.dirty == true) card else card.copy(
                    narrativeProgress = parseRemoteNarrativeProgress(pair.first, card.narrativeProgress),
                    interactionPerformance = parseRemoteInteractionPerformance(pair.second, card.interactionPerformance),
                )
            }
            // Mirror server progress locally for offline resilience without clobbering unsynced dirty edits
            mutationMutex.withLock {
                val currentEnv = loadLocalMissionsEnvelope()
                val updatedMap = currentEnv.missions.toMutableMap()
                for (card in mergedCards) {
                    val prev = updatedMap[card.missionId] ?: LocalMissionProgressRecord(missionId = card.missionId)
                    if (prev.dirty) continue
                    updatedMap[card.missionId] = prev.copy(
                        status = card.narrativeProgress.status,
                        currentSceneId = card.narrativeProgress.currentSceneId,
                        completedSceneIds = card.narrativeProgress.completedSceneIds,
                        scenePositionsMs = card.narrativeProgress.scenePositionsMs,
                        completedRunsCount = card.narrativeProgress.completedRunsCount,
                    )
                }
                env = currentEnv.copy(missions = updatedMap)
                saveLocalMissionsEnvelope(env)
            }
            refreshedLocalView.copy(cards = mergedCards)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            localView
        }
    }

    suspend fun openMissionDetail(missionId: String, resume: Boolean = true): MissionDetailView {
        val spec = MissionsCatalogLoader.loadBundled()
        var env = loadLocalMissionsEnvelope()
        val missionSpec = spec.careers.asSequence()
            .flatMap { it.disciplines.asSequence() }
            .flatMap { it.seasons.asSequence() }
            .flatMap { it.missions.asSequence() }
            .firstOrNull { it.missionId == missionId }
            ?: error("Missão não encontrada no catálogo declarativo.")

        val counts = localMissionHumanQuestionStats(missionSpec.curriculum)
        val fallbackDetail = MissionsCatalogLoader.buildDetailView(
            spec = spec,
            missionId = missionId,
            record = env.missions[missionId],
            availableHumanQuestions = counts.available,
            practicedDistinctHumanQuestions = counts.practiced,
            masteredHumanQuestions = counts.mastered,
        ) ?: error("Detalhe da missão indisponível.")

        return try {
            auth?.let { runCatching { it.ensureSession(api, ownerUserId) } }
            env.missions[missionId]?.let { rec ->
                if (rec.dirty) {
                    flushDirtyMissionRecord(missionId, rec)
                    env = loadLocalMissionsEnvelope()
                }
            }
            if (env.missions[missionId]?.dirty == true) {
                return fallbackDetail
            }
            val remote = api.getMissionDetail(missionId, resume)
            val prog = parseRemoteNarrativeProgress(remote["narrative_progress"]?.jsonObject, fallbackDetail.narrativeProgress)
            val perf = parseRemoteInteractionPerformance(remote["interaction_performance"]?.jsonObject, fallbackDetail.interactionPerformance)
            val masteryObj = remote["demonstrated_mastery"]?.jsonObject
            val mastery = if (masteryObj != null) {
                DemonstratedMasteryView(
                    masteryState = masteryObj["mastery_state"]?.jsonPrimitive?.contentOrNull ?: fallbackDetail.demonstratedMastery.masteryState,
                    masteryLabelPt = masteryObj["mastery_label_pt"]?.jsonPrimitive?.contentOrNull ?: fallbackDetail.demonstratedMastery.masteryLabelPt,
                    availableHumanQuestionsCount = masteryObj["available_human_questions_count"]?.jsonPrimitive?.intOrNull ?: counts.available,
                    distinctHumanQuestionsPracticed = masteryObj["distinct_human_questions_practiced"]?.jsonPrimitive?.intOrNull ?: counts.practiced,
                    minQuestionsForMastery = masteryObj["min_questions_for_mastery"]?.jsonPrimitive?.intOrNull ?: 15,
                    practiceAvailable = masteryObj["practice_available"]?.jsonPrimitive?.booleanOrNull ?: (counts.available > 0),
                )
            } else {
                fallbackDetail.demonstratedMastery
            }

            // Merge server scene media availability and answered decision states
            val remoteScenesById = remote["mission"]?.jsonObject?.get("scenes")?.jsonArray
                ?.associateBy { it.jsonObject["scene_id"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                .orEmpty()

            val mergedScenes = fallbackDetail.scenes.map { sc ->
                val rSc = remoteScenesById[sc.sceneId]?.jsonObject ?: return@map sc
                val mediaAvail = rSc["media"]?.jsonObject?.get("available_on_server")?.jsonPrimitive?.booleanOrNull ?: true
                val isDone = rSc["is_completed"]?.jsonPrimitive?.booleanOrNull ?: sc.isCompleted
                val rAnswered = rSc["interaction"]?.jsonObject?.get("answered")?.jsonObject
                val updatedInter = if (sc.interaction != null && rAnswered != null) {
                    sc.interaction.copy(
                        answered = AnsweredDecisionView(
                            interactionId = rAnswered["interaction_id"]?.jsonPrimitive?.contentOrNull ?: sc.interaction.interactionId,
                            sceneId = sc.sceneId,
                            selectedOptionId = rAnswered["selected_option_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            expectedOptionId = rAnswered["expected_option_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            isCorrect = rAnswered["is_correct"]?.jsonPrimitive?.booleanOrNull ?: false,
                            firstAttemptCorrect = rAnswered["first_attempt_correct"]?.jsonPrimitive?.booleanOrNull ?: false,
                            attemptCount = rAnswered["attempt_count"]?.jsonPrimitive?.intOrNull ?: 1,
                            optionFeedback = rAnswered["option_feedback"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            conceptualExplanation = rAnswered["conceptual_explanation"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                    )
                } else {
                    sc.interaction
                }
                sc.copy(isCompleted = isDone, availableOnServer = mediaAvail, interaction = updatedInter)
            }

            val reviewSet = perf.conceptsForReview.toSet()
            fallbackDetail.copy(
                scenes = mergedScenes,
                workedConcepts = fallbackDetail.workedConcepts.map { it.copy(recommendedRevisit = reviewSet.contains(it.conceptKey)) },
                narrativeProgress = prog,
                interactionPerformance = perf,
                demonstratedMastery = mastery,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallbackDetail
        }
    }

    suspend fun updateMissionProgressOfflineFirst(
        missionId: String,
        currentSceneId: String? = null,
        completedSceneId: String? = null,
        playbackPositionMs: Long? = null,
        markMissionCompleted: Boolean = false,
        usedTranscriptFallback: Boolean = false,
    ): MissionDetailView {
        val spec = MissionsCatalogLoader.loadBundled()
        var savedRecord: LocalMissionProgressRecord? = null
        var wasAlreadyDirty = false
        mutationMutex.withLock {
            val env = loadLocalMissionsEnvelope()
            val prev = env.missions[missionId] ?: LocalMissionProgressRecord(missionId = missionId)
            wasAlreadyDirty = prev.dirty
            val nextDone = if (completedSceneId != null && !prev.completedSceneIds.contains(completedSceneId)) {
                prev.completedSceneIds + completedSceneId
            } else {
                prev.completedSceneIds
            }
            val nextStatus = when {
                markMissionCompleted -> "COMPLETED"
                prev.status == "NOT_STARTED" -> "IN_PROGRESS"
                else -> prev.status
            }
            val nextScene = currentSceneId ?: prev.currentSceneId.ifBlank {
                spec.careers.asSequence()
                    .flatMap { it.disciplines.asSequence() }
                    .flatMap { it.seasons.asSequence() }
                    .flatMap { it.missions.asSequence() }
                    .firstOrNull { it.missionId == missionId }
                    ?.scenes?.firstOrNull()?.sceneId.orEmpty()
            }
            val nextPos = if (playbackPositionMs != null && nextScene.isNotBlank()) {
                prev.scenePositionsMs + (nextScene to playbackPositionMs)
            } else {
                prev.scenePositionsMs
            }
            val updated = prev.copy(
                status = nextStatus,
                currentSceneId = nextScene,
                completedSceneIds = nextDone,
                scenePositionsMs = nextPos,
                completedRunsCount = if (markMissionCompleted && prev.status != "COMPLETED") prev.completedRunsCount + 1 else prev.completedRunsCount,
                dirty = true,
            )
            savedRecord = updated
            saveLocalMissionsEnvelope(env.copy(missions = env.missions + (missionId to updated)))
        }

        if (wasAlreadyDirty && savedRecord != null) {
            flushDirtyMissionRecord(missionId, savedRecord!!)
            return openMissionDetail(missionId, resume = false)
        }

        val pushed = runCatching {
            auth?.ensureSession(api, ownerUserId)
            val payload = buildJsonObject {
                currentSceneId?.let { put("current_scene_id", it) }
                completedSceneId?.let { put("completed_scene_id", it) }
                playbackPositionMs?.let { put("playback_position_ms", it) }
                if (markMissionCompleted) put("mark_mission_completed", true)
                if (usedTranscriptFallback) put("used_transcript_fallback", true)
            }
            api.updateMissionProgress(missionId, payload)
            true
        }.getOrDefault(false)
        if (pushed && savedRecord != null) {
            mutationMutex.withLock {
                val env = loadLocalMissionsEnvelope()
                if (env.missions[missionId] == savedRecord) {
                    saveLocalMissionsEnvelope(env.copy(missions = env.missions + (missionId to savedRecord!!.copy(dirty = false))))
                }
            }
        }
        return openMissionDetail(missionId, resume = false)
    }

    suspend fun submitMissionDecisionOfflineFirst(
        missionId: String,
        sceneId: String,
        interactionId: String,
        selectedOptionId: String,
        timeSpentMs: Long,
        usedTranscriptFallback: Boolean = false,
    ): MissionDetailView {
        val spec = MissionsCatalogLoader.loadBundled()
        val missionSpec = spec.careers.asSequence()
            .flatMap { it.disciplines.asSequence() }
            .flatMap { it.seasons.asSequence() }
            .flatMap { it.missions.asSequence() }
            .firstOrNull { it.missionId == missionId }
            ?: error("Missão não encontrada.")
        val sceneSpec = missionSpec.scenes.find { it.sceneId == sceneId } ?: error("Cena não encontrada.")
        val interSpec = sceneSpec.interaction ?: error("Cena sem decisão interativa.")
        val isCorrect = interSpec.expectedOptionId == selectedOptionId

        var savedRecord: LocalMissionProgressRecord? = null
        var wasAlreadyDirty = false
        mutationMutex.withLock {
            val env = loadLocalMissionsEnvelope()
            val prev = env.missions[missionId] ?: LocalMissionProgressRecord(missionId = missionId)
            wasAlreadyDirty = prev.dirty
            val prevDec = prev.decisions[interactionId]
            val recorded = if (prevDec == null) {
                LocalRecordedDecision(
                    interactionId = interactionId,
                    sceneId = sceneId,
                    firstSelectedOptionId = selectedOptionId,
                    latestSelectedOptionId = selectedOptionId,
                    firstAttemptCorrect = isCorrect,
                    latestCorrect = isCorrect,
                    attemptCount = 1,
                )
            } else {
                prevDec.copy(
                    latestSelectedOptionId = selectedOptionId,
                    latestCorrect = isCorrect,
                    attemptCount = prevDec.attemptCount + 1,
                )
            }
            val nextDone = if (prev.completedSceneIds.contains(sceneId)) prev.completedSceneIds else prev.completedSceneIds + sceneId
            val isTerminal = sceneSpec.nextSceneId == null
            val nextStatus = if (isTerminal) "COMPLETED" else if (prev.status == "NOT_STARTED") "IN_PROGRESS" else prev.status
            val updated = prev.copy(
                status = nextStatus,
                currentSceneId = sceneId,
                completedSceneIds = if (isTerminal) missionSpec.scenes.map { it.sceneId } else nextDone,
                decisions = prev.decisions + (interactionId to recorded),
                completedRunsCount = if (isTerminal && prev.status != "COMPLETED") prev.completedRunsCount + 1 else prev.completedRunsCount,
                dirty = true,
            )
            savedRecord = updated
            saveLocalMissionsEnvelope(env.copy(missions = env.missions + (missionId to updated)))
        }

        if (wasAlreadyDirty && savedRecord != null) {
            flushDirtyMissionRecord(missionId, savedRecord!!)
            return openMissionDetail(missionId, resume = false)
        }

        val pushed = runCatching {
            auth?.ensureSession(api, ownerUserId)
            api.submitMissionDecision(
                missionId,
                buildJsonObject {
                    put("scene_id", sceneId)
                    put("interaction_id", interactionId)
                    put("selected_option_id", selectedOptionId)
                    put("time_spent_ms", timeSpentMs)
                    put("used_transcript_fallback", usedTranscriptFallback)
                }
            )
            true
        }.getOrDefault(false)
        if (pushed && savedRecord != null) {
            mutationMutex.withLock {
                val env = loadLocalMissionsEnvelope()
                if (env.missions[missionId] == savedRecord) {
                    saveLocalMissionsEnvelope(env.copy(missions = env.missions + (missionId to savedRecord!!.copy(dirty = false))))
                }
            }
        }
        return openMissionDetail(missionId, resume = false)
    }

    suspend fun repeatMissionOfflineFirst(missionId: String): MissionDetailView {
        val spec = MissionsCatalogLoader.loadBundled()
        val firstScene = spec.careers.asSequence()
            .flatMap { it.disciplines.asSequence() }
            .flatMap { it.seasons.asSequence() }
            .flatMap { it.missions.asSequence() }
            .firstOrNull { it.missionId == missionId }
            ?.scenes?.firstOrNull()?.sceneId.orEmpty()

        var savedRecord: LocalMissionProgressRecord? = null
        mutationMutex.withLock {
            val env = loadLocalMissionsEnvelope()
            val prev = env.missions[missionId] ?: LocalMissionProgressRecord(missionId = missionId)
            val reset = prev.copy(
                status = "IN_PROGRESS",
                currentSceneId = firstScene,
                completedSceneIds = emptyList(),
                scenePositionsMs = emptyMap(),
                decisions = emptyMap(),
                runCount = prev.runCount + 1,
                dirty = true,
            )
            savedRecord = reset
            saveLocalMissionsEnvelope(env.copy(missions = env.missions + (missionId to reset)))
        }
        val pushed = runCatching {
            auth?.ensureSession(api, ownerUserId)
            api.repeatMission(missionId)
            true
        }.getOrDefault(false)
        if (pushed && savedRecord != null) {
            mutationMutex.withLock {
                val env = loadLocalMissionsEnvelope()
                if (env.missions[missionId] == savedRecord) {
                    saveLocalMissionsEnvelope(env.copy(missions = env.missions + (missionId to savedRecord!!.copy(dirty = false))))
                }
            }
        }
        return openMissionDetail(missionId, resume = false)
    }

    suspend fun fetchMissionRelatedHumanQuestions(missionId: String, quantity: Int = 5): List<QuestionDto> {
        val spec = MissionsCatalogLoader.loadBundled()
        val missionSpec = spec.careers.asSequence()
            .flatMap { it.disciplines.asSequence() }
            .flatMap { it.seasons.asSequence() }
            .flatMap { it.missions.asSequence() }
            .firstOrNull { it.missionId == missionId }
            ?: return emptyList()

        try {
            auth?.ensureSession(api, ownerUserId)
            val resp = api.getMissionPracticeQuestions(missionId, buildJsonObject { put("quantity", quantity) })
            val arr = resp["questions"]?.jsonArray
            if (arr != null && arr.isNotEmpty()) {
                val remoteDtos = arr.map { json.decodeFromJsonElement<QuestionDto>(it) }
                    .map(QuestionCacheMapping::withRevision)
                val valid = objectivePracticeQuestions(remoteDtos)
                if (valid.isNotEmpty()) {
                    dao.insertQuestions(valid.map { QuestionCacheMapping.toEntity(it, json) })
                    return decorateQuestions(valid)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall back to offline Room cache below
        }

        val targetTopics = (missionSpec.curriculum.primaryTopicIds + missionSpec.curriculum.fallbackTopicIds).toSet()
        val cached = runCatching {
            dao.getCachedQuestions(null, 10000).map { QuestionCacheMapping.toDto(it, json) }
        }.getOrDefault(emptyList())
        val matched = objectivePracticeQuestions(
            cached.filter { q ->
                (q.canonicalTopicId != null && q.canonicalTopicId in targetTopics) ||
                    q.canonicalSubjectId == missionSpec.curriculum.canonicalSubjectId
            }
        ).take(quantity)
        return decorateQuestions(matched)
    }

    suspend fun ensureMissionVideoCached(
        cacheRootDir: File,
        assetId: String,
        expectedSha256: String? = null,
        apiBaseUrl: String = BuildConfig.API_BASE_URL,
    ): File? = withContext(Dispatchers.IO) {
        val cleanAssetId = assetId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        if (cleanAssetId.isEmpty()) return@withContext null
        videoCacheMutex.withLock {
            val missionsDir = File(cacheRootDir, "missions").apply { mkdirs() }
            val target = File(missionsDir, "$cleanAssetId.mp4")
            val shaSidecar = File(missionsDir, "$cleanAssetId.sha256")
            if (target.isFile && target.length() > 1024L) {
                val expectedNorm = expectedSha256?.trim()?.lowercase()
                if (expectedNorm.isNullOrBlank()) {
                    return@withLock target
                }
                val cachedSha = runCatching { shaSidecar.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.lowercase() }.getOrNull()
                if (cachedSha == expectedNorm) {
                    return@withLock target
                }
                runCatching { target.delete() }
                runCatching { shaSidecar.delete() }
            }

            val token = auth?.let { store ->
                store.token(ownerUserId) ?: runCatching {
                    store.ensureSession(api, ownerUserId)
                    store.token(ownerUserId)
                }.getOrNull()
            } ?: return@withLock null

            val baseUrl = apiBaseUrl.trimEnd('/')
            val url = "$baseUrl/missions/media/$cleanAssetId"
            val tmpFile = File(missionsDir, "$cleanAssetId-${UUID.randomUUID()}.part")
            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept-Encoding", "identity")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withLock null
                    val body = resp.body ?: return@withLock null
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                digest.update(buf, 0, read)
                                out.write(buf, 0, read)
                            }
                        }
                    }
                    val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!expectedSha256.isNullOrBlank() && !actualHex.equals(expectedSha256.trim(), ignoreCase = true)) {
                        tmpFile.delete()
                        return@withLock null
                    }
                    if (target.exists()) target.delete()
                    if (!tmpFile.renameTo(target)) {
                        tmpFile.copyTo(target, overwrite = true)
                        tmpFile.delete()
                    }
                    runCatching { shaSidecar.writeText(actualHex, Charsets.UTF_8) }
                    target
                }
            } catch (e: CancellationException) {
                runCatching { tmpFile.delete() }
                throw e
            } catch (_: Exception) {
                runCatching { tmpFile.delete() }
                null
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
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
