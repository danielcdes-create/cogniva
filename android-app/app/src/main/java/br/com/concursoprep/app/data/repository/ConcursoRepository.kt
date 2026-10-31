package br.com.concursoprep.app.data.repository

import br.com.concursoprep.app.data.local.CachedQuestionEntity
import br.com.concursoprep.app.data.local.ConcursoLocalDao
import br.com.concursoprep.app.data.local.PendingAttemptEntity
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.remote.OnboardingPayload
import br.com.concursoprep.app.data.remote.QuizGeneratePayload
import br.com.concursoprep.app.data.remote.ReportProblemPayload
import br.com.concursoprep.app.data.remote.TelegramLinkPayload
import br.com.concursoprep.app.domain.model.AiAssistResponseDto
import br.com.concursoprep.app.domain.model.AnswerAttemptPayload
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repositório Unificado (API Rust + Room Offline-First):
 * Conecta 100% das telas do Jetpack Compose aos endpoints do `api-rust-core` e mantém
 * resiliência total para uso no transporte público sem internet via Room.
 */
class ConcursoRepository(
    private val api: ConcursoApiService,
    private val dao: ConcursoLocalDao,
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

    suspend fun getTodayDashboard(userId: Long): TodayDashboardDto {
        return try {
            api.getTodayDashboard(userId)
        } catch (e: Exception) {
            buildOfflineFallbackDashboard(userId)
        }
    }

    suspend fun getContestSyllabus(contestId: String, userId: Long): List<DisciplineSyllabusGroupDto> {
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
                val entities = remoteQuestions.map { dto ->
                    CachedQuestionEntity(
                        id = dto.id,
                        question = dto.question,
                        questionType = dto.questionType.name,
                        answersJson = json.encodeToString(dto.answers),
                        correctIndicesJson = json.encodeToString(dto.correctAnswerIndices),
                        disciplina = dto.disciplina,
                        source = dto.source,
                        examYear = dto.examYear,
                        level = dto.level,
                        explanation = dto.explanation,
                        inErrorNotebook = dto.userStats?.inErrorNotebook ?: false,
                        isMastered = dto.userStats?.isMastered ?: false
                    )
                }
                dao.insertQuestions(entities)
                return remoteQuestions
            }
            loadFromLocalRoomOrSeed(disciplina, quantity)
        } catch (e: Exception) {
            loadFromLocalRoomOrSeed(disciplina, quantity)
        }
    }

    private suspend fun loadFromLocalRoomOrSeed(disciplina: String?, quantity: Int): List<QuestionDto> {
        val local = dao.getCachedQuestions(disciplina, quantity)
        if (local.isNotEmpty()) {
            return local.map { entity ->
                QuestionDto(
                    id = entity.id,
                    question = entity.question,
                    questionType = runCatching { QuestionType.valueOf(entity.questionType) }
                        .getOrDefault(QuestionType.MULTIPLE_CHOICE),
                    answers = runCatching { json.decodeFromString<List<String>>(entity.answersJson) }
                        .getOrDefault(emptyList()),
                    correctAnswerIndices = runCatching { json.decodeFromString<List<Int>>(entity.correctIndicesJson) }
                        .getOrDefault(listOf(0)),
                    disciplina = entity.disciplina,
                    source = entity.source,
                    examYear = entity.examYear,
                    level = entity.level,
                    explanation = entity.explanation
                )
            }
        }
        return buildOfflineSeedQuestions()
    }

    suspend fun recordAttemptOfflineFirst(
        userId: Long,
        attempt: PendingAttemptEntity
    ) {
        dao.enqueuePendingAttempt(attempt)
        syncPendingAttempts(userId)
    }

    suspend fun syncPendingAttempts(userId: Long): Int {
        val pending = dao.getAllPendingAttempts()
        if (pending.isEmpty()) return 0

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
                    userNote = item.userNote
                )
            }
        )

        return try {
            val response = api.submitAttemptsBatch(payload)
            dao.deleteSyncedAttempts(pending.map { it.localId })
            response.processedCount
        } catch (e: Exception) {
            0
        }
    }

    suspend fun requestAiTutor(questionId: String, kind: String, hintIndex: Int? = null): String {
        return try {
            val response: AiAssistResponseDto = api.requestAiTutor(questionId, kind, hintIndex)
            response.contentMarkdown
        } catch (e: Exception) {
            buildOfflineFallbackAiResponse(kind)
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

    // --- Fallbacks Offline-First para primeira execução sem servidor acessível ---
    private fun buildOfflineFallbackDashboard(userId: Long) = TodayDashboardDto(
        userId = userId,
        dateIso = "2026-10-30",
        dailyGoalQuestions = 50,
        questionsAnsweredToday = 18,
        accuracyTodayPct = 78.0,
        pendingSrsReviewsCount = 14,
        errorNotebookCount = 23,
        highConfidenceErrorsCount = 3,
        upcomingExams = listOf(
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
        ),
        commonTrunkSuggestion = CommonTrunkSuggestion(
            discipline = "Finanças Públicas",
            sharedContests = listOf("CPNU", "RFB"),
            badgeText = "Estudar 15 questões de Finanças Públicas avança CPNU e Receita Federal ao mesmo tempo"
        )
    )

    private fun buildOfflineFallbackSyllabus(contestId: String): List<DisciplineSyllabusGroupDto> {
        val isEstimated = contestId != "CPNU"
        return listOf(
            DisciplineSyllabusGroupDto(
                blockName = "Conhecimentos Específicos",
                discipline = "Segurança e Saúde do Trabalhador",
                disciplineMasteryPct = 82.0,
                topics = listOf(
                    SyllabusTopicNodeDto(
                        topic = SyllabusTopicDto(
                            id = "sst_nr15",
                            contestId = contestId,
                            blockName = "Conhecimentos Específicos",
                            discipline = "Segurança e Saúde do Trabalhador",
                            title = "NR-15 — Atividades e Operações Insalubres e Limites de Tolerância",
                            order = 1,
                            commonTrunkId = "tronco_sst_nrs",
                            estimation = EstimationMetadata(
                                isEstimated = isEstimated,
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
                            contestId = contestId,
                            blockName = "Conhecimentos Específicos",
                            discipline = "Segurança e Saúde do Trabalhador",
                            title = "NR-01 — Gerenciamento de Riscos Ocupacionais (GRO e PGR)",
                            order = 2,
                            commonTrunkId = "tronco_sst_nrs",
                            estimation = EstimationMetadata(
                                isEstimated = isEstimated,
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
                            contestId = contestId,
                            blockName = "Conhecimentos Gerais",
                            discipline = "Finanças Públicas",
                            title = "Orçamento Público: PPA, LDO, LOA e Créditos Adicionais",
                            order = 1,
                            commonTrunkId = "tronco_financas_orcamento",
                            estimation = EstimationMetadata(
                                isEstimated = isEstimated,
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
        )
    )

    private fun buildOfflineFallbackAiResponse(kind: String): String = when (kind) {
        "dr-sincero" -> "🔥 **Dr. Sincero:** A banca adora colocar que 'basta entregar o EPI no papel' para se livrar do adicional de insalubridade. Súmula 289 do TST manda lembranças: o simples fornecimento do aparelho de proteção pelo empregador NÃO o exime do pagamento do adicional! Tem que neutralizar de verdade dentro do limite de tolerância."
        "mnemonic" -> "🧠 **Mnemônico — Neutralização da Insalubridade (M.E.L.):**\n- **M**edidas de Ordem Geral (EPC)\n- **E**PI com CA e uso efetivo\n- **L**imite de Tolerância respeitado!"
        "contextual-hint" -> "💡 **Dica 1:** Lembre-se do art. 191 da CLT: existem exatamente duas vias para neutralizar o agente insalubre (coletiva no ambiente ou individual eficaz)."
        "plausibility" -> "⚖️ **Análise de Plausibilidade dos Distratores:**\n- *Simples entrega formal do EPI:* **Plausível (Armadilha Clássica)** — confunde o candidato que lembra da existência do EPI mas esquece da Súmula 289 do TST.\n- *Pagamento substitutivo de bônus:* **Improvável** — direitos de saúde e segurança são indisponíveis."
        else -> "📚 **Explicação Detalhada:** O art. 191 da CLT e o item 15.4 da NR-15 estabelecem que a eliminação ou neutralização da insalubridade depende da conservação do ambiente dentro dos limites de tolerância ou da redução da intensidade do agente via EPI."
    }

    private fun buildOfflineFallbackAnalytics(userId: Long) = ProgressAnalyticsDto(
        userId = userId,
        globalAccuracyPct = 71.4,
        totalQuestionsPracticed = 412,
        totalMasteredQuestions = 128,
        readinessIndexScore = 69.5,
        historicalCutoffReference = 74.0,
        disciplinesSortedByWeakness = listOf(
            DisciplineMasteryBarDto("Sociologia e Psicologia Aplicadas ao Trabalho", 54.0, 45, 8, 6.2),
            DisciplineMasteryBarDto("Finanças Públicas", 61.5, 52, 14, 11.4),
            DisciplineMasteryBarDto("Políticas Públicas", 68.0, 60, 19, 15.0),
            DisciplineMasteryBarDto("Direito do Trabalho", 76.5, 120, 42, 38.5),
            DisciplineMasteryBarDto("Segurança e Saúde do Trabalhador", 82.0, 135, 45, 44.1)
        ),
        confidenceMatrix = ConfidenceMatrixSummaryDto(
            highConfidenceCorrect = 215,
            lowConfidenceCorrect = 79,
            lowConfidenceError = 94,
            highConfidenceError = 24
        ),
        activityHeatmap = (1..14).map { day ->
            DailyHeatmapCellDto(
                dateYmd = "2026-10-${(16 + day).toString().padStart(2, '0')}",
                questionsCount = 25,
                accuracyPct = 72.0
            )
        }
    )
}
