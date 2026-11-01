package br.com.concursoprep.app.data.repository

import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.data.local.CachedQuestionEntity
import br.com.concursoprep.app.data.local.CanonicalMasteryEntity
import br.com.concursoprep.app.data.local.CareerTrunkEntity
import br.com.concursoprep.app.data.local.ConcursoLocalDao
import br.com.concursoprep.app.data.local.ExamDao
import br.com.concursoprep.app.data.local.ExamLocalMapping
import br.com.concursoprep.app.data.local.PendingAttemptEntity
import br.com.concursoprep.app.data.local.QuestionCacheMapping
import br.com.concursoprep.app.data.remote.ExamBlueprintPayload
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.remote.OnboardingPayload
import br.com.concursoprep.app.data.remote.QuizGeneratePayload
import br.com.concursoprep.app.data.remote.ReportProblemPayload
import br.com.concursoprep.app.data.remote.TelegramLinkPayload
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.domain.exam.ExamEngine
import br.com.concursoprep.app.domain.exam.OfflineExamQueue
import br.com.concursoprep.app.domain.exam.QuestionRef
import br.com.concursoprep.app.domain.exam.QuizSelection
import br.com.concursoprep.app.domain.exam.StoredExam
import br.com.concursoprep.app.domain.model.AiAssistResponseDto
import br.com.concursoprep.app.domain.model.AnswerAttemptPayload
import br.com.concursoprep.app.domain.model.ApkInfoDto
import br.com.concursoprep.app.domain.model.AuthSessionDto
import br.com.concursoprep.app.domain.model.CommonTrunkSuggestion
import br.com.concursoprep.app.domain.model.ConfidenceBadge
import br.com.concursoprep.app.domain.model.ConfidenceMatrixSummaryDto
import br.com.concursoprep.app.domain.model.ContestDiffScreenDto
import br.com.concursoprep.app.domain.model.ContestStatus
import br.com.concursoprep.app.domain.model.DailyHeatmapCellDto
import br.com.concursoprep.app.domain.model.DisciplineMasteryBarDto
import br.com.concursoprep.app.domain.model.DisciplineSyllabusGroupDto
import br.com.concursoprep.app.domain.model.EstimationMetadata
import br.com.concursoprep.app.domain.model.ProgressAnalyticsDto
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.SubmitBatchPayload
import br.com.concursoprep.app.domain.model.SyllabusTopicDto
import br.com.concursoprep.app.domain.model.SyllabusTopicNodeDto
import br.com.concursoprep.app.domain.model.TodayDashboardDto
import br.com.concursoprep.app.domain.model.TopicDiffStatus
import br.com.concursoprep.app.domain.model.UpcomingExamCard
import br.com.concursoprep.app.domain.model.UserProfileDto
import br.com.concursoprep.app.domain.model.UserQuestionBriefStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Repositório Unificado (API Rust + Room Offline-First):
 * Conecta 100% das telas do Jetpack Compose aos endpoints do `api-rust-core` e mantém
 * resiliência total para uso no transporte público sem internet via Room.
 */
class ConcursoRepository(
    private val api: ConcursoApiService,
    private val dao: ConcursoLocalDao,
    private val examDao: ExamDao,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun linkTelegramAccountOrLogin(
        telegramUserId: Long?,
        telegramUsername: String?,
        displayName: String?
    ): AuthSessionDto {
        return try {
            api.linkTelegramOrLogin(
                TelegramLinkPayload(
                    userId = telegramUserId,
                    username = telegramUsername,
                    displayName = displayName
                )
            )
        } catch (e: Exception) {
            val uid = telegramUserId ?: 1001L
            AuthSessionDto(
                token = "offline_local_jwt_token",
                profile = UserProfileDto(
                    userId = uid,
                    username = telegramUsername,
                    displayName = displayName ?: "Concurseiro"
                ),
                historicalQuestionsCount = 0L,
                isExistingTelegramUser = telegramUserId != null
            )
        }
    }

    suspend fun saveOnboarding(
        userId: Long,
        contestIds: List<String>,
        dailyQuestionsTarget: Int,
        dailyHours: Double,
        displayName: String?
    ): UserProfileDto {
        return try {
            api.saveOnboardingPreferences(
                OnboardingPayload(
                    userId = userId,
                    activeContestIds = contestIds,
                    dailyQuestionsTarget = dailyQuestionsTarget,
                    dailyStudyHoursTarget = dailyHours,
                    displayName = displayName
                )
            )
        } catch (e: Exception) {
            UserProfileDto(
                userId = userId,
                displayName = displayName,
                dailyQuestionsTarget = dailyQuestionsTarget,
                dailyStudyHoursTarget = dailyHours,
                activeContestIds = contestIds
            )
        }
    }

    suspend fun getTodayDashboard(
        userId: Long,
        dailyGoalQuestions: Int = 50,
        activeContestIds: List<String> = listOf("CPNU", "RFB")
    ): TodayDashboardDto {
        return try {
            api.getTodayDashboard(userId)
        } catch (e: Exception) {
            buildOfflineFallbackDashboard(userId, dailyGoalQuestions, activeContestIds)
        }
    }

    suspend fun getContestSyllabus(contestId: String, userId: Long): List<DisciplineSyllabusGroupDto> {
        val stored = runCatching { loadStoredExams() }.getOrDefault(emptyList())
        val exam = stored.firstOrNull { it.examId == contestId }
        if (exam != null) {
            val history = runCatching { examDao.listMastery() }.getOrDefault(emptyList()).map { row ->
                br.com.concursoprep.app.domain.exam.CanonicalMasteryRecord(
                    canonicalSubjectId = row.canonicalSubjectId,
                    canonicalTopicId = row.canonicalTopicId,
                    mastery = row.mastery
                )
            }
            return ExamEngine.projectSyllabus(exam, history).map { group ->
                DisciplineSyllabusGroupDto(
                    blockName = exam.trunkSlug,
                    discipline = group.discipline,
                    disciplineMasteryPct = group.masteryPct,
                    topics = group.topicTitles.mapIndexed { index, title ->
                        SyllabusTopicNodeDto(
                            topic = SyllabusTopicDto(
                                id = "${group.canonicalSubjectId}-$index",
                                contestId = contestId,
                                blockName = exam.trunkSlug,
                                discipline = group.discipline,
                                title = title,
                                order = index,
                                estimation = EstimationMetadata(
                                    isEstimated = exam.lifecycleStatus != "EDITAL_PUBLICADO",
                                    confidenceBadge = ConfidenceBadge.HIGH,
                                    rationale = "Projeção do edital salvo e do domínio canônico.",
                                    referenceExams = emptyList()
                                ),
                                diffStatus = TopicDiffStatus.CONFIRMED
                            ),
                            userMasteryPct = group.masteryPct,
                            questionsPracticed = if (group.masteryPct > 0.0) 1 else 0,
                            statusLabel = if (group.masteryPct > 0.0) "QUESTOES" else "NAO_INICIADO"
                        )
                    }
                )
            }
        }
        return try {
            api.getContestSyllabus(contestId, userId)
        } catch (e: Exception) {
            buildOfflineFallbackSyllabus(contestId)
        }
    }

    suspend fun getContestDiff(contestId: String): ContestDiffScreenDto? {
        return try {
            api.getContestEditalDiff(contestId)
        } catch (e: Exception) {
            if (contestId == "CPNU") buildOfflineFallbackDiff() else null
        }
    }

    suspend fun getProgressAnalytics(userId: Long): ProgressAnalyticsDto {
        return try {
            api.getProgressAnalytics(userId)
        } catch (e: Exception) {
            buildOfflineFallbackAnalytics(userId)
        }
    }

    suspend fun fetchOrGetOfflineQuiz(
        userId: Long,
        mode: QuizMode,
        quantity: Int,
        contestId: String? = null,
        disciplina: String? = null
    ): List<QuestionDto> {
        return try {
            val remoteQuestions = api.generateQuizQuestions(
                QuizGeneratePayload(
                    userId = userId,
                    mode = mode,
                    quantity = quantity,
                    contestId = contestId,
                    disciplina = disciplina
                )
            )
            if (remoteQuestions.isNotEmpty()) {
                dao.insertQuestions(remoteQuestions.map { dto -> QuestionCacheMapping.toEntity(dto, json) })
            }
            val pool = if (remoteQuestions.isNotEmpty()) {
                remoteQuestions
            } else {
                loadFromLocalRoomOrSeed(null, quantity.coerceAtLeast(40), QuizMode.STANDARD)
            }
            return selectModeQuestions(mode, quantity, contestId, disciplina, pool, remoteQuestions)
        } catch (e: Exception) {
            val pool = loadFromLocalRoomOrSeed(null, quantity.coerceAtLeast(40), QuizMode.STANDARD)
            selectModeQuestions(mode, quantity, contestId, disciplina, pool, emptyList())
        }
    }

    private suspend fun selectModeQuestions(
        mode: QuizMode,
        quantity: Int,
        contestId: String?,
        disciplina: String?,
        pool: List<QuestionDto>,
        remoteQuestions: List<QuestionDto>
    ): List<QuestionDto> {
        return when (mode) {
            QuizMode.SIMULADO -> {
                val exam = loadStoredExams().firstOrNull { it.examId == contestId }
                if (exam == null) pool.take(quantity) else {
                    val ids = QuizSelection.weightedSimuladoIds(exam, pool.map { it.toRef() })
                    val byId = pool.associateBy { it.id }
                    ids.mapNotNull { byId[it] }
                }
            }
            QuizMode.COMMON_TRUNK -> {
                val ids = QuizSelection.commonTrunkIds(
                    exams = loadStoredExams(),
                    questions = pool.map { it.toRef() },
                    focusedTrunkSlugs = focusedTrunkSlugs(),
                    masteryBySubject = canonicalMasteryBySubject(),
                    today = LocalDate.now(),
                    quantity = quantity
                )
                val byId = pool.associateBy { it.id }
                ids.mapNotNull { byId[it] }
            }
            else -> if (remoteQuestions.isNotEmpty()) remoteQuestions else loadFromLocalRoomOrSeed(disciplina, quantity, mode)
        }
    }

    suspend fun canonicalMasteryBySubject(): Map<String, Double> {
        val rows = runCatching { examDao.listMastery() }.getOrDefault(emptyList())
        return rows.groupBy { it.canonicalSubjectId }.mapValues { (_, items) -> items.map { it.mastery }.average() }
    }

    private suspend fun loadFromLocalRoomOrSeed(
        disciplina: String?,
        quantity: Int,
        mode: QuizMode = QuizMode.STANDARD
    ): List<QuestionDto> {
        val local = if (mode == QuizMode.ERROR_NOTEBOOK) {
            val errList = dao.getErrorNotebookQuestions(quantity)
            errList.ifEmpty { dao.getCachedQuestions(disciplina, quantity) }
        } else {
            dao.getCachedQuestions(disciplina, quantity)
        }
        if (local.isNotEmpty()) {
            return local.map { entity -> QuestionCacheMapping.toDto(entity, json) }
        }
        val seed = buildOfflineSeedQuestions()
        return if (!disciplina.isNullOrBlank()) {
            val filtered = seed.filter { it.disciplina.contains(disciplina, ignoreCase = true) || disciplina.contains(it.disciplina, ignoreCase = true) }
            filtered.ifEmpty { seed }.shuffled().take(quantity)
        } else {
            seed.shuffled().take(quantity)
        }
    }

    suspend fun recordAttemptOfflineFirst(
        userId: Long,
        attempt: PendingAttemptEntity
    ) {
        val existingInSession = dao.findPendingAttemptInSession(attempt.sessionUuid, attempt.questionId)
        val entityToSave = if (existingInSession != null) {
            attempt.copy(localId = existingInSession.localId)
        } else {
            attempt
        }
        dao.enqueuePendingAttempt(entityToSave)
        if (!attempt.isCorrect) {
            dao.updateQuestionErrorNotebookFlag(attempt.questionId, true)
        }
        val mastery = if (attempt.isCorrect) {
            attempt.confidenceLevel.coerceIn(0, 3) / 3.0
        } else if (attempt.confidenceLevel >= 3) {
            0.0
        } else {
            0.25
        }
        recordCanonicalMastery(attempt.canonicalSubjectId, attempt.canonicalTopicId, mastery)
        syncPendingAttempts(userId)
    }

    suspend fun syncPendingAttempts(userId: Long): Int {
        val pending = dao.getAllPendingAttempts()
        val attemptsSynced = if (pending.isEmpty()) 0 else syncAttemptBatch(userId, pending)
        return attemptsSynced + syncPendingExams()
    }

    private suspend fun syncAttemptBatch(userId: Long, pending: List<PendingAttemptEntity>): Int {
        val pendingForBatch = pending

        val payload = SubmitBatchPayload(
            userId = userId,
            useSimplifiedSrs = false,
            attempts = pending.map { item ->
                AnswerAttemptPayload(
                    questionId = item.questionId,
                    chosenIndex = item.chosenIndex,
                    chosenText = item.chosenText,
                    isCorrect = item.isCorrect,
                    timeSpentSeconds = item.timeSpentSeconds,
                    confidenceLevel = item.confidenceLevel,
                    sessionUuid = item.sessionUuid,
                    quizMode = item.quizMode,
                    examBlock = item.examBlock,
                    contestId = item.contestId,
                    userNote = item.userNote,
                    canonicalSubjectId = item.canonicalSubjectId,
                    canonicalTopicId = item.canonicalTopicId
                )
            }
        )

        return try {
            val response = api.submitAttemptsBatch(payload)
            dao.deleteSyncedAttempts(pendingForBatch.map { it.localId })
            response.processedCount
        } catch (e: Exception) {
            0
        }
    }

    suspend fun ensureExamSeed() {
        if (examDao.countExams() > 0) return
        examDao.upsertTrunks(
            ExamCatalog.trunks().map { trunk ->
                CareerTrunkEntity(trunk.slug, trunk.name, trunk.iconKey, false)
            }
        )
        ExamCatalog.systemExams().forEach { saveExamLocal(it) }
    }

    suspend fun loadStoredExams(): List<StoredExam> {
        ensureExamSeed()
        return examDao.listExams().map { entity ->
            ExamLocalMapping.assemble(entity, examDao.subjectsFor(entity.examId), examDao.topicsFor(entity.examId))
        }
    }

    suspend fun saveExamLocal(exam: StoredExam) {
        examDao.upsertExam(ExamLocalMapping.toEntity(exam))
        examDao.upsertSubjects(ExamLocalMapping.subjectsOf(exam))
        examDao.upsertTopics(ExamLocalMapping.topicsOf(exam))
    }

    suspend fun createExamOffline(draft: br.com.concursoprep.app.domain.exam.ExamDraft): StoredExam {
        val created = OfflineExamQueue.createPendingExam(draft)
        saveExamLocal(created)
        return created
    }

    suspend fun setTrunkFocus(slug: String, focused: Boolean) {
        ensureExamSeed()
        examDao.setTrunkFocus(slug, focused)
    }

    suspend fun focusedTrunkSlugs(): Set<String> {
        ensureExamSeed()
        return examDao.listTrunks().filter { it.isFocusedTrunk }.map { it.slug }.toSet()
    }

    suspend fun recordCanonicalMastery(subjectId: String?, topicId: String?, mastery: Double) {
        if (subjectId.isNullOrBlank() || topicId.isNullOrBlank()) return
        examDao.upsertMastery(
            CanonicalMasteryEntity(topicId, subjectId, mastery, mastery > 0.0)
        )
    }

    private suspend fun syncPendingExams(): Int {
        val pending = examDao.listPendingExams()
        var synced = 0
        for (entity in pending) {
            val exam = ExamLocalMapping.assemble(
                entity,
                examDao.subjectsFor(entity.examId),
                examDao.topicsFor(entity.examId)
            )
            try {
                val payload = exam.toPayload()
                if (exam.syncStatus == "PENDING_CREATE") api.createExam(payload) else api.updateExam(exam.examId, payload)
                examDao.markSynced(exam.examId)
                synced += 1
            } catch (_: Exception) {
                // O id do cliente permanece até a rede voltar.
            }
        }
        return synced
    }

    private fun StoredExam.toPayload(): ExamBlueprintPayload {
        val date = when (dateType) {
            "EXACT_DATE" -> buildJsonObject {
                put("type", "EXACT_DATE")
                put("iso_date", exactDateIso ?: "")
            }
            "ESTIMATED_WINDOW" -> buildJsonObject {
                put("type", "ESTIMATED_WINDOW")
                put("label", estimatedWindowLabel ?: "")
                put("estimated_sort_date", estimatedSortDateIso)
            }
            else -> buildJsonObject { put("type", "UNDEFINED") }
        }
        return ExamBlueprintPayload(
            id = examId,
            title = title,
            shortName = shortName,
            institution = institution,
            role = role,
            trunkSlug = trunkSlug,
            status = lifecycleStatus,
            examBoards = examBoards,
            targetDate = date,
            historicalCutoffScore = historicalCutoffScore,
            isActiveInLadder = isActiveInLadder,
            ladderPriority = ladderPriority,
            syncStatus = syncStatus,
            subjects = subjects.map { subject ->
                br.com.concursoprep.app.data.remote.ExamSubjectPayload(
                    localSubjectId = subject.localSubjectId,
                    canonicalSubjectId = subject.canonicalSubjectId,
                    displayName = subject.displayName,
                    category = subject.category,
                    weight = subject.weight,
                    questionCount = subject.questionCount,
                    relevanceTier = subject.relevanceTier,
                    topics = subject.topics.map { topic ->
                        br.com.concursoprep.app.data.remote.ExamTopicPayload(
                            localTopicId = topic.localTopicId,
                            canonicalTopicId = topic.canonicalTopicId,
                            canonicalSubjectId = topic.canonicalSubjectId,
                            displayTitle = topic.displayTitle,
                            diffStatus = topic.diffStatus
                        )
                    }
                )
            }
        )
    }

    suspend fun requestAiTutor(questionId: String, kind: String, hintIndex: Int? = null): String {
        return try {
            val response: AiAssistResponseDto = api.requestAiTutor(questionId, kind, hintIndex)
            response.contentMarkdown
        } catch (e: Exception) {
            buildOfflineFallbackAiResponse(questionId, kind)
        }
    }

    suspend fun reportQuestionProblem(userId: Long, questionId: String, category: String): Boolean {
        return try {
            api.reportQuestionProblem(
                questionId = questionId,
                payload = ReportProblemPayload(userId = userId, problemCategory = category)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getLocalStorageStats(): Pair<Int, Int> {
        return try {
            val cached = dao.countCachedQuestions()
            val pending = dao.countPendingAttempts()
            Pair(cached, pending)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }

    suspend fun clearLocalQuestionCache() {
        try {
            dao.clearCachedQuestions()
        } catch (_: Exception) {
        }
    }

    // --- Sistemática de Atualização OTA de APK (estilo SpamShield) ---
    suspend fun fetchRemoteApkInfo(): ApkInfoDto? {
        return try {
            api.getApkInfo()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadApkUpdate(targetDir: File): File? = withContext(Dispatchers.IO) {
        try {
            val info = fetchRemoteApkInfo() ?: return@withContext null
            val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
            val downloadUrl = "$baseUrl/apk/download"

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, "cogniva-update.apk")

            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Verifica integridade SHA-256 do APK baixado
            val digest = MessageDigest.getInstance("SHA-256")
            outFile.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val calculatedSha = digest.digest().joinToString("") { "%02x".format(it) }

            if (!calculatedSha.equals(info.sha256Checksum, ignoreCase = true)) {
                outFile.delete()
                return@withContext null
            }

            outFile
        } catch (e: Exception) {
            null
        }
    }

    // --- Fallbacks Offline-First para primeira execução sem servidor acessível ---
    private suspend fun buildOfflineFallbackDashboard(
        userId: Long,
        dailyGoalQuestions: Int = 50,
        activeContestIds: List<String> = listOf("CPNU", "RFB")
    ): TodayDashboardDto {
        val pendingAttempts = runCatching { dao.getAllPendingAttempts() }.getOrDefault(emptyList())
        val startOfDayMs = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val todayLocal = pendingAttempts.filter { it.createdAtEpochMs >= startOfDayMs }
        val localCountToday = todayLocal.size
        val localCorrectToday = todayLocal.count { it.isCorrect }

        val baseAnswered = 18 + localCountToday
        val baseCorrect = 14 + localCorrectToday
        val accPct = if (baseAnswered > 0) (baseCorrect.toDouble() / baseAnswered.toDouble()) * 100.0 else 78.0
        val localErrors = todayLocal.count { !it.isCorrect }
        val localHcErrors = todayLocal.count { !it.isCorrect && it.confidenceLevel >= 2 }

        val allExams = listOf(
            UpcomingExamCard(
                contestId = "CPNU",
                name = "CPNU · Auditor-Fiscal do Trabalho",
                status = ContestStatus.EDITAL_PUBLICADO,
                daysRemaining = 44,
                estimatedWindow = null,
                examBoard = "Cesgranrio (Confirmada)"
            ),
            UpcomingExamCard(
                contestId = "RFB",
                name = "Receita Federal · Auditor Fiscal",
                status = ContestStatus.SOLICITADO_AUTORIZADO,
                daysRemaining = null,
                estimatedWindow = "Provável: 1º sem/2027",
                examBoard = "FGV / Cebraspe (Estimada)"
            ),
            UpcomingExamCard(
                contestId = "BNDES",
                name = "BNDES · Profissional Básico",
                status = ContestStatus.PREVISTO,
                daysRemaining = null,
                estimatedWindow = "Previsto: 2º sem/2027",
                examBoard = "Cesgranrio (Provável)"
            )
        )
        val filteredExams = allExams.filter { activeContestIds.contains(it.contestId) }.ifEmpty { allExams }

        return TodayDashboardDto(
            userId = userId,
            dateIso = java.time.LocalDate.now().toString(),
            dailyGoalQuestions = dailyGoalQuestions,
            questionsAnsweredToday = baseAnswered,
            accuracyTodayPct = accPct,
            pendingSrsReviewsCount = 14L + localErrors,
            errorNotebookCount = 23L + localErrors,
            highConfidenceErrorsCount = 3L + localHcErrors,
            upcomingExams = filteredExams,
            commonTrunkSuggestion = CommonTrunkSuggestion(
                discipline = "Finanças Públicas",
                sharedContests = activeContestIds.ifEmpty { listOf("CPNU", "RFB") },
                badgeText = "Estudar 15 questões de Finanças Públicas avança ${
                    activeContestIds.ifEmpty { listOf("CPNU", "RFB") }.joinToString(" e ")
                } ao mesmo tempo"
            )
        )
    }

    private fun buildOfflineFallbackSyllabus(contestId: String): List<DisciplineSyllabusGroupDto> {
        return when (contestId) {
            "RFB" -> listOf(
                DisciplineSyllabusGroupDto(
                    blockName = "Conhecimentos Específicos",
                    discipline = "Direito Tributário",
                    disciplineMasteryPct = 64.0,
                    topics = listOf(
                        SyllabusTopicNodeDto(
                            topic = SyllabusTopicDto(
                                id = "rfb_trib_01",
                                contestId = "RFB",
                                blockName = "Conhecimentos Específicos",
                                discipline = "Direito Tributário",
                                title = "Reforma Tributária (EC 132/2023 e IBS/CBS) e Sistema Tributário Nacional",
                                order = 1,
                                commonTrunkId = "tronco_tributario_reforma",
                                estimation = EstimationMetadata(
                                    isEstimated = true,
                                    confidenceBadge = ConfidenceBadge.HIGH,
                                    rationale = "Tema central pós-EC 132/2023 e presente em 100% das provas fiscais recentes.",
                                    referenceExams = listOf("RFB 2023", "ISS-RJ 2025")
                                ),
                                diffStatus = TopicDiffStatus.CONFIRMED
                            ),
                            userMasteryPct = 64.0,
                            questionsPracticed = 22,
                            statusLabel = "QUESTOES"
                        )
                    )
                ),
                DisciplineSyllabusGroupDto(
                    blockName = "Conhecimentos Específicos",
                    discipline = "Finanças Públicas",
                    disciplineMasteryPct = 61.0,
                    topics = listOf(
                        SyllabusTopicNodeDto(
                            topic = SyllabusTopicDto(
                                id = "rfb_fin_01",
                                contestId = "RFB",
                                blockName = "Conhecimentos Específicos",
                                discipline = "Finanças Públicas",
                                title = "Orçamento Público: PPA, LDO, LOA e Créditos Adicionais",
                                order = 2,
                                commonTrunkId = "tronco_financas_orcamento",
                                estimation = EstimationMetadata(
                                    isEstimated = true,
                                    confidenceBadge = ConfidenceBadge.HIGH,
                                    rationale = "Tronco comum entre CPNU, Receita Federal e Controladorias.",
                                    referenceExams = listOf("RFB 2023", "CPNU 2024")
                                ),
                                diffStatus = TopicDiffStatus.CONFIRMED
                            ),
                            userMasteryPct = 61.0,
                            questionsPracticed = 19,
                            statusLabel = "QUESTOES"
                        )
                    )
                )
            )

            "BNDES" -> listOf(
                DisciplineSyllabusGroupDto(
                    blockName = "Conhecimentos Transversais",
                    discipline = "Papel do BNDES no desenvolvimento brasileiro",
                    disciplineMasteryPct = 70.0,
                    topics = listOf(
                        SyllabusTopicNodeDto(
                            topic = SyllabusTopicDto(
                                id = "bndes_trans_01",
                                contestId = "BNDES",
                                blockName = "Conhecimentos Transversais",
                                discipline = "Papel do BNDES no desenvolvimento brasileiro",
                                title = "Políticas de Financiamento de Longo Prazo, Transição Ecológica e Neoindustrialização",
                                order = 1,
                                commonTrunkId = "tronco_politicas_publicas",
                                estimation = EstimationMetadata(
                                    isEstimated = true,
                                    confidenceBadge = ConfidenceBadge.HIGH,
                                    rationale = "Eixo estruturante exigido pela banca Cesgranrio no último edital BNDES.",
                                    referenceExams = listOf("BNDES 2024")
                                ),
                                diffStatus = TopicDiffStatus.CONFIRMED
                            ),
                            userMasteryPct = 70.0,
                            questionsPracticed = 15,
                            statusLabel = "QUESTOES"
                        )
                    )
                ),
                DisciplineSyllabusGroupDto(
                    blockName = "Conhecimentos Específicos",
                    discipline = "Finanças Públicas",
                    disciplineMasteryPct = 61.0,
                    topics = listOf(
                        SyllabusTopicNodeDto(
                            topic = SyllabusTopicDto(
                                id = "bndes_fin_01",
                                contestId = "BNDES",
                                blockName = "Conhecimentos Específicos",
                                discipline = "Finanças Públicas",
                                title = "Orçamento Público, Responsabilidade Fiscal e Crédito Público",
                                order = 2,
                                commonTrunkId = "tronco_financas_orcamento",
                                estimation = EstimationMetadata(
                                    isEstimated = true,
                                    confidenceBadge = ConfidenceBadge.MEDIUM,
                                    rationale = "Tronco comum compartilhado com CPNU e carreiras econômicas.",
                                    referenceExams = listOf("BNDES 2024", "CPNU 2024")
                                ),
                                diffStatus = TopicDiffStatus.CONFIRMED
                            ),
                            userMasteryPct = 61.0,
                            questionsPracticed = 19,
                            statusLabel = "QUESTOES"
                        )
                    )
                )
            )

            "CPNU" -> listOf(
                DisciplineSyllabusGroupDto(
                    blockName = "Conhecimentos Específicos",
                    discipline = "Segurança e Saúde do Trabalhador",
                    disciplineMasteryPct = 82.0,
                    topics = listOf(
                        SyllabusTopicNodeDto(
                            topic = SyllabusTopicDto(
                                id = "sst_nr15",
                                contestId = "CPNU",
                                blockName = "Conhecimentos Específicos",
                                discipline = "Segurança e Saúde do Trabalhador",
                                title = "NR-15 — Atividades e Operações Insalubres e Limites de Tolerância",
                                order = 1,
                                commonTrunkId = "tronco_sst_nrs",
                                estimation = EstimationMetadata(
                                    isEstimated = false,
                                    confidenceBadge = ConfidenceBadge.HIGH,
                                    rationale = "Cobrado em 100% das últimas 3 provas de AFT e Segurança do Trabalho.",
                                    referenceExams = listOf("CNU 2024", "AFT 2013")
                                ),
                                diffStatus = TopicDiffStatus.CONFIRMED
                            ),
                            userMasteryPct = 85.0,
                            questionsPracticed = 34,
                            statusLabel = "DOMINADO"
                        ),
                        SyllabusTopicNodeDto(
                            topic = SyllabusTopicDto(
                                id = "sst_nr01",
                                contestId = "CPNU",
                                blockName = "Conhecimentos Específicos",
                                discipline = "Segurança e Saúde do Trabalhador",
                                title = "NR-01 — Gerenciamento de Riscos Ocupacionais (GRO e PGR)",
                                order = 2,
                                commonTrunkId = "tronco_sst_nrs",
                                estimation = EstimationMetadata(
                                    isEstimated = false,
                                    confidenceBadge = ConfidenceBadge.HIGH,
                                    rationale = "Núcleo estruturante da fiscalização moderna do trabalho.",
                                    referenceExams = listOf("CNU 2024")
                                ),
                                diffStatus = TopicDiffStatus.CONFIRMED
                            ),
                            userMasteryPct = 52.0,
                            questionsPracticed = 12,
                            statusLabel = "REVISAO"
                        )
                    )
                ),
                DisciplineSyllabusGroupDto(
                    blockName = "Conhecimentos Gerais",
                    discipline = "Finanças Públicas",
                    disciplineMasteryPct = 61.0,
                    topics = listOf(
                        SyllabusTopicNodeDto(
                            topic = SyllabusTopicDto(
                                id = "fin_01",
                                contestId = "CPNU",
                                blockName = "Conhecimentos Gerais",
                                discipline = "Finanças Públicas",
                                title = "Orçamento Público: PPA, LDO, LOA e Créditos Adicionais",
                                order = 1,
                                commonTrunkId = "tronco_financas_orcamento",
                                estimation = EstimationMetadata(
                                    isEstimated = false,
                                    confidenceBadge = ConfidenceBadge.HIGH,
                                    rationale = "Tronco comum entre CPNU, Receita Federal e Controladorias.",
                                    referenceExams = listOf("CPNU 2024", "RFB 2023")
                                ),
                                diffStatus = TopicDiffStatus.CONFIRMED
                            ),
                            userMasteryPct = 61.0,
                            questionsPracticed = 19,
                            statusLabel = "QUESTOES"
                        )
                    )
                )
            )
            else -> emptyList()
        }
    }

    private fun buildOfflineFallbackDiff() = ContestDiffScreenDto(
        contestId = "CPNU",
        contestName = "CPNU · Auditor-Fiscal do Trabalho",
        confirmedBoard = "Cesgranrio",
        confirmedTopics = listOf(
            SyllabusTopicDto(
                id = "sst_nr15",
                contestId = "CPNU",
                blockName = "Específicos",
                discipline = "Segurança e Saúde do Trabalhador",
                title = "NR-15 e Limites de Tolerância",
                order = 1,
                estimation = EstimationMetadata(false, ConfidenceBadge.HIGH, "Confirmado"),
                diffStatus = TopicDiffStatus.CONFIRMED
            )
        ),
        newTopics = listOf(
            SyllabusTopicDto(
                id = "dt_plat",
                contestId = "CPNU",
                blockName = "Específicos",
                discipline = "Direito do Trabalho",
                title = "Trabalho em Plataformas Digitais e Subordinação Algorítmica",
                order = 2,
                estimation = EstimationMetadata(false, ConfidenceBadge.MEDIUM, "Novo no edital"),
                diffStatus = TopicDiffStatus.NEW_IN_EDITAL
            )
        ),
        removedTopics = listOf(
            SyllabusTopicDto(
                id = "antigo_01",
                contestId = "CPNU",
                blockName = "Básicos",
                discipline = "Informática",
                title = "Atalhos de Teclado Legacy Windows",
                order = 99,
                estimation = EstimationMetadata(true, ConfidenceBadge.LOW, "Removido"),
                diffStatus = TopicDiffStatus.REMOVED_ARCHIVED
            )
        )
    )

    private fun buildOfflineSeedQuestions(): List<QuestionDto> = listOf(
        QuestionDto(
            id = "66afca336caf9b5d37185cdd",
            question = "De acordo com a NR-15 (Atividades e Operações Insalubres), a eliminação ou neutralização da insalubridade determinará a cessação do pagamento do adicional respectivo. Essa neutralização ocorre com:",
            questionType = QuestionType.MULTIPLE_CHOICE,
            answers = listOf(
                "A adoção de medidas de ordem geral que conservem o ambiente de trabalho dentro dos limites de tolerância ou a utilização de EPI que diminua a intensidade do agente agressivo a limites de tolerância.",
                "O pagamento substitutivo de bônus de produtividade acordado individualmente entre empregado e empregador.",
                "A simples entrega formal do Equipamento de Proteção Individual (EPI), independentemente de Certificado de Aprovação (CA) ou fiscalização de uso.",
                "A redução proporcional da jornada diária em 15 minutos sem alteração do ambiente físico.",
                "A declaração unilateral da empresa em laudo interno sem avaliação quantitativa do agente químico."
            ),
            correctAnswerIndices = listOf(0),
            disciplina = "Segurança e Saúde do Trabalhador",
            source = "Cesgranrio",
            examYear = 2024,
            level = "Difícil",
            explanation = "✅ Correta: Conforme o item 15.4.1 da NR-15 e o art. 191 da CLT, a eliminação ou neutralização da insalubridade ocorre com medidas de proteção coletiva que mantenham o ambiente dentro dos limites de tolerância OU com o uso efetivo de EPI com CA válido que reduza a intensidade do agente aos limites de tolerância.",
            hasDrSincero = true,
            hasMnemonic = true,
            hasPlausibility = true,
            hintsCount = 2,
            userStats = UserQuestionBriefStats(3, 66.7, "2026-10-25", false, true)
        ),
        QuestionDto(
            id = "66afca336caf9b5d37185cde",
            question = "Julgue o item a seguir (Padrão Cebraspe): No regime constitucional dos créditos adicionais, os créditos extraordinários somente podem ser abertos para atender a despesas imprevisíveis e urgentes, como as decorrentes de guerra, comoção interna ou calamidade pública.",
            questionType = QuestionType.TRUE_FALSE,
            answers = listOf("CERTO", "ERRADO"),
            correctAnswerIndices = listOf(0),
            disciplina = "Finanças Públicas",
            canonicalSubjectId = "FIN_PUB",
            canonicalTopicId = "FIN_PUB_01",
            source = "CESPE",
            examYear = 2024,
            level = "Médio",
            isBorrowedAnalogous = true,
            explanation = "✅ CERTO: Literalidade do art. 167, § 3º da Constituição Federal de 1988.",
            hasDrSincero = true,
            hasMnemonic = true,
            hasPlausibility = true,
            hintsCount = 1,
            userStats = null
        ),
        QuestionDto(
            id = "66afca336caf9b5d37185cdf",
            question = "No âmbito da Reforma Tributária (EC nº 132/2023), a Contribuição sobre Bens e Serviços (CBS) e o Imposto sobre Bens e Serviços (IBS) compartilham regras harmônicas de incidência. Assinale a alternativa correta sobre o princípio informador da cobrança no destino:",
            questionType = QuestionType.MULTIPLE_CHOICE,
            answers = listOf(
                "O imposto e a contribuição incidem de forma não cumulativa, cabendo a arrecadação do IBS ao ente federativo de destino da operação ou prestação.",
                "A alíquota de referência do IBS é fixada unilateralmente pela União por decreto presidencial sem participação do Comitê Gestor.",
                "A CBS substitui o Imposto sobre Produtos Industrializados (IPI), extinguindo imediatamente os incentivos da Zona Franca de Manaus.",
                "O IBS possui caráter cumulativo nas cadeias industriais de exportação.",
                "O imposto seletivo incide sobre exportações de bens minerais e produtos primários em geral sem limite de alíquota."
            ),
            correctAnswerIndices = listOf(0),
            disciplina = "Direito Tributário",
            source = "FGV",
            examYear = 2025,
            level = "Difícil",
            isBorrowedAnalogous = true,
            explanation = "✅ Correta: A EC 132/2023 consagrou a não cumulatividade plena e a tributação no destino para o IVA Dual (CBS federal e IBS subnacional).",
            hasDrSincero = true,
            hasMnemonic = true,
            hasPlausibility = true,
            hintsCount = 1,
            userStats = null
        ),
        QuestionDto(
            id = "66afca336caf9b5d37185ce0",
            question = "Acerca da relação de emprego prevista nos artigos 2º e 3º da Consolidação das Leis do Trabalho (CLT), constitui requisito essencial configurador do vínculo empregatício:",
            questionType = QuestionType.MULTIPLE_CHOICE,
            answers = listOf(
                "A prestação de serviços de natureza não eventual a empregador, sob a dependência deste e mediante salário, com pessoalidade.",
                "A exclusividade absoluta do trabalhador perante um único tomador de serviços.",
                "A realização das atividades obrigatoriamente nas dependências físicas da sede da empresa.",
                "O pagamento de remuneração exclusivamente em periodicidade mensal fixa.",
                "O registro formal prévio em carteira de trabalho como condição de existência fática do direito."
            ),
            correctAnswerIndices = listOf(0),
            disciplina = "Direito do Trabalho",
            source = "Cesgranrio",
            examYear = 2024,
            level = "Fácil",
            explanation = "✅ Correta: Os elementos fático-jurídicos da relação de emprego (pessoalidade, pessoa física, não eventualidade, onerosidade e subordinação) independem de exclusividade ou do local de trabalho (art. 3º e art. 6º da CLT).",
            hasDrSincero = true,
            hasMnemonic = true,
            hasPlausibility = true,
            hintsCount = 1,
            userStats = null
        )
    )

    private suspend fun buildOfflineFallbackAiResponse(questionId: String, kind: String): String {
        val seedQ = buildOfflineSeedQuestions().firstOrNull { it.id == questionId }
        val explanation = seedQ?.explanation ?: runCatching {
            dao.findQuestionById(questionId)?.explanation
        }.getOrNull()

        if (!explanation.isNullOrBlank()) {
            return when (kind) {
                "explanation" -> explanation
                "child-friendly-pre" -> "🧒 **Simplificando a Pergunta:**\n${seedQ?.question ?: "Analise cuidadosamente o cerne da questão e a regra geral aplicável."}"
                "child-friendly-post" -> "🧒 **Explicação Didática:**\n$explanation"
                "dr-sincero" -> "🔥 **Dr. Sincero:** Sem enrolação — atente-se ao cerne da questão e às pegadinhas da banca:\n\n$explanation"
                "contextual-hint" -> "💡 **Dica Pedagógica:** Lembre-se da fundamentação do gabarito oficial para descartar as alternativas incorretas."
                "extra-concepts" -> "🎯 **Conceitos-Chave:**\n$explanation"
                "mnemonic" -> "🧠 **Mnemônico & Fixação:** Fixe os elementos centrais do gabarito oficial:\n\n$explanation"
                "plausibility" -> "⚖️ **Análise dos Distratores:**\n$explanation"
                else -> explanation
            }
        }

        return when (kind) {
            "dr-sincero" -> "🔥 **Dr. Sincero:** Fique atento aos termos absolutos da banca (como 'sempre', 'nunca', 'apenas') que costumam invalidar alternativas."
            "mnemonic" -> "🧠 **Dica de Fixação:** Revise os requisitos essenciais da disciplina e anote a fundamentação do gabarito."
            "contextual-hint" -> "💡 **Dica Progressiva:** Elimine primeiro as alternativas que contrariam expressamente a legislação ou o bom senso constitucional."
            "child-friendly-pre" -> "🧒 **Dica:** Identifique qual é a regra principal que a questão está cobrando antes de ler as opções."
            "child-friendly-post" -> "🧒 **Explicação:** A alternativa correta reflete a literalidade e a interpretação consolidada da banca examinadora."
            else -> "📚 **Explicação:** Não foi possível carregar o comentário detalhado da IA no momento. Conecte-se à internet para consultar o tutor pedagógico completo."
        }
    }

    private suspend fun buildOfflineFallbackAnalytics(userId: Long): ProgressAnalyticsDto {
        val pendingAttempts = runCatching { dao.getAllPendingAttempts() }.getOrDefault(emptyList())
        val localTotal = pendingAttempts.size
        val localCorrect = pendingAttempts.count { it.isCorrect }
        val localHcCorrect = pendingAttempts.count { it.isCorrect && it.confidenceLevel >= 2 }
        val localLcCorrect = pendingAttempts.count { it.isCorrect && it.confidenceLevel < 2 }
        val localLcError = pendingAttempts.count { !it.isCorrect && it.confidenceLevel < 2 }
        val localHcError = pendingAttempts.count { !it.isCorrect && it.confidenceLevel >= 2 }

        val totalPracticed = 412 + localTotal
        val totalCorrect = 294 + localCorrect
        val globalAcc = ((totalCorrect.toDouble() / totalPracticed.coerceAtLeast(1).toDouble()) * 1000.0).toInt() / 10.0

        return ProgressAnalyticsDto(
            userId = userId,
            globalAccuracyPct = globalAcc,
            totalQuestionsPracticed = totalPracticed,
            totalMasteredQuestions = 128 + (localHcCorrect / 3),
            readinessIndexScore = ExamEngine.readinessForStoredExams(
                runCatching { loadStoredExams() }.getOrDefault(emptyList()),
                runCatching { examDao.listMastery() }.getOrDefault(emptyList())
                    .associate { it.canonicalTopicId to it.mastery }
            ),
            historicalCutoffReference = 74.0,
            disciplinesSortedByWeakness = listOf(
                DisciplineMasteryBarDto("Sociologia e Psicologia Aplicadas ao Trabalho", 54.0, 45, 8, 6.2),
                DisciplineMasteryBarDto("Finanças Públicas", 61.5, 52, 14, 11.4),
                DisciplineMasteryBarDto("Políticas Públicas", 68.0, 60, 19, 15.0),
                DisciplineMasteryBarDto("Direito do Trabalho", 76.5, 120, 42, 38.5),
                DisciplineMasteryBarDto("Segurança e Saúde do Trabalhador", 82.0, 135, 45, 44.1)
            ),
            confidenceMatrix = ConfidenceMatrixSummaryDto(
                highConfidenceCorrect = 215 + localHcCorrect,
                lowConfidenceCorrect = 79 + localLcCorrect,
                lowConfidenceError = 94 + localLcError,
                highConfidenceError = 24 + localHcError
            ),
            activityHeatmap = (1..14).map { day ->
                DailyHeatmapCellDto(
                    dateYmd = "2026-10-${(16 + day).toString().padStart(2, '0')}",
                    questionsCount = if (day == 14) 25 + localTotal else 25,
                    accuracyPct = if (day == 14) globalAcc else 72.0
                )
            }
        )
    }
}

private fun QuestionDto.toRef(): QuestionRef {
    val inferred = QuestionCacheMapping.inferCanonical(disciplina)
    return QuestionRef(
        id = id,
        canonicalSubjectId = canonicalSubjectId ?: inferred?.first,
        canonicalTopicId = canonicalTopicId ?: inferred?.second
    )
}
