package br.com.concursoprep.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ContestStatus {
    PREVISTO,
    SOLICITADO_AUTORIZADO,
    EDITAL_PUBLICADO,
    EM_ANDAMENTO,
    ENCERRADO
}

@Serializable
enum class ConfidenceBadge {
    HIGH,   // 🟢 Recorrente nos últimos 3 editais
    MEDIUM, // 🟡 Em 2 dos últimos 3 ou similares
    LOW     // ⚪ Tendência / tema em alta
}

@Serializable
enum class TopicDiffStatus {
    CONFIRMED,
    NEW_IN_EDITAL,
    REMOVED_ARCHIVED
}

@Serializable
enum class QuestionType {
    MULTIPLE_CHOICE,
    TRUE_FALSE, // Estilo Cebraspe (Certo/Errado com penalidade)
    OPEN_ENDED
}

fun objectivePracticeQuestions(questions: List<QuestionDto>): List<QuestionDto> =
    questions.filter { it.questionType != QuestionType.OPEN_ENDED }

@Serializable
enum class QuizMode {
    STANDARD,
    SHUFFLE,
    SIMULADO,
    MOST_BADASS,
    SRS_REVIEW,
    ERROR_NOTEBOOK,
    COMMON_TRUNK,
    SINGLE
}

@Serializable
enum class ConfidenceQuadrant {
    HIGH_CONFIDENCE_CORRECT,
    LOW_CONFIDENCE_CORRECT,
    LOW_CONFIDENCE_ERROR,
    HIGH_CONFIDENCE_ERROR // Alerta crítico: Ponto cego!
}

@Serializable
enum class AppThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Escuro")
}

@Serializable
data class UserProfileDto(
    @SerialName("user_id") val userId: Long,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val role: String = "student",
    @SerialName("is_guest") val isGuest: Boolean = true,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("daily_questions_target") val dailyQuestionsTarget: Int = 50,
    @SerialName("daily_study_hours_target") val dailyStudyHoursTarget: Double = 2.0,
    @SerialName("active_contest_ids") val activeContestIds: List<String> = listOf("CPNU", "RFB"),
    @SerialName("current_daily_streak") val currentDailyStreak: Int = 0,
    @SerialName("streak_freezes_available") val streakFreezesAvailable: Int = 0,
    @SerialName("xp_total") val xpTotal: Long = 0
)

@Serializable
data class AuthSessionDto(
    val token: String,
    @SerialName("expires_in_days") val expiresInDays: Long = 30,
    val profile: UserProfileDto,
    @SerialName("historical_questions_count") val historicalQuestionsCount: Long = 0
)

@Serializable
data class UpcomingExamCard(
    @SerialName("contest_id") val contestId: String,
    val name: String,
    val status: ContestStatus,
    @SerialName("days_remaining") val daysRemaining: Long? = null,
    @SerialName("estimated_window") val estimatedWindow: String? = null,
    @SerialName("exam_board") val examBoard: String,
    @SerialName("date_type") val dateType: String? = null,
    @SerialName("exact_date_iso") val exactDateIso: String? = null
)

@Serializable
data class CommonTrunkSuggestion(
    val discipline: String,
    @SerialName("shared_contests") val sharedContests: List<String>,
    @SerialName("badge_text") val badgeText: String
)

@Serializable
data class TodayDashboardDto(
    @SerialName("user_id") val userId: Long,
    @SerialName("date_iso") val dateIso: String,
    @SerialName("daily_goal_questions") val dailyGoalQuestions: Int,
    @SerialName("questions_answered_today") val questionsAnsweredToday: Int,
    @SerialName("accuracy_today_pct") val accuracyTodayPct: Double,
    @SerialName("pending_srs_reviews_count") val pendingSrsReviewsCount: Long,
    @SerialName("error_notebook_count") val errorNotebookCount: Long,
    @SerialName("high_confidence_errors_count") val highConfidenceErrorsCount: Long,
    @SerialName("upcoming_exams") val upcomingExams: List<UpcomingExamCard>,
    @SerialName("common_trunk_suggestion") val commonTrunkSuggestion: CommonTrunkSuggestion,
    val lastSyncedAt: String? = null,
    val syncMessage: String? = null
)

@Serializable
data class EstimationMetadata(
    @SerialName("is_estimated") val isEstimated: Boolean,
    @SerialName("confidence_badge") val confidenceBadge: ConfidenceBadge,
    val rationale: String,
    @SerialName("reference_exams") val referenceExams: List<String> = emptyList()
)

@Serializable
data class SyllabusTopicDto(
    @SerialName("_id") val id: String,
    @SerialName("contest_id") val contestId: String,
    @SerialName("block_name") val blockName: String,
    val discipline: String,
    val title: String,
    val order: Int,
    @SerialName("common_trunk_id") val commonTrunkId: String? = null,
    @SerialName("canonical_topic_id") val canonicalTopicId: String? = null,
    val estimation: EstimationMetadata,
    @SerialName("diff_status") val diffStatus: TopicDiffStatus
)

@Serializable
data class SyllabusTopicNodeDto(
    val topic: SyllabusTopicDto,
    @SerialName("user_mastery_pct") val userMasteryPct: Double,
    @SerialName("questions_practiced") val questionsPracticed: Int,
    @SerialName("status_label") val statusLabel: String
)

@Serializable
data class DisciplineSyllabusGroupDto(
    @SerialName("block_name") val blockName: String,
    val discipline: String,
    @SerialName("discipline_mastery_pct") val disciplineMasteryPct: Double,
    val topics: List<SyllabusTopicNodeDto>,
    @SerialName("component_id") val componentId: String? = null
)

@Serializable
data class ContestDiffScreenDto(
    @SerialName("contest_id") val contestId: String,
    @SerialName("contest_name") val contestName: String,
    @SerialName("confirmed_board") val confirmedBoard: String,
    @SerialName("confirmed_topics") val confirmedTopics: List<SyllabusTopicDto>,
    @SerialName("new_topics") val newTopics: List<SyllabusTopicDto>,
    @SerialName("removed_topics") val removedTopics: List<SyllabusTopicDto>
)

@Serializable
data class UserQuestionBriefStats(
    val attempts: Int,
    @SerialName("accuracy_pct") val accuracyPct: Double,
    @SerialName("last_session_iso") val lastSessionIso: String? = null,
    @SerialName("is_mastered") val isMastered: Boolean,
    @SerialName("in_error_notebook") val inErrorNotebook: Boolean
)

@Serializable
data class QuestionDto(
    val id: String,
    @SerialName("question_revision") val questionRevision: String = "",
    val question: String,
    @SerialName("question_type") val questionType: QuestionType = QuestionType.MULTIPLE_CHOICE,
    val answers: List<String>,
    @SerialName("correct_answer_indices") val correctAnswerIndices: List<Int>,
    val disciplina: String,
    val source: String = "",
    @SerialName("exam_year") val examYear: Int? = null,
    val level: String = "",
    @SerialName("syllabus_topic_ids") val syllabusTopicIds: List<String> = emptyList(),
    @SerialName("common_trunk_id") val commonTrunkId: String? = null,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String? = null,
    @SerialName("canonical_topic_id") val canonicalTopicId: String? = null,
    @SerialName("is_borrowed_analogous") val isBorrowedAnalogous: Boolean = false,
    val explanation: String? = null,
    @SerialName("has_dr_sincero") val hasDrSincero: Boolean = false,
    @SerialName("has_mnemonic") val hasMnemonic: Boolean = false,
    @SerialName("has_plausibility") val hasPlausibility: Boolean = false,
    @SerialName("hints_count") val hintsCount: Int = 0,
    @SerialName("user_stats") val userStats: UserQuestionBriefStats? = null,
    @SerialName("exam_component_id") val examComponentId: String? = null,
    @SerialName("knowledge_projection_version") val knowledgeProjectionVersion: Int = 0,
    @SerialName("practice_review_due_on") val practiceReviewDueOn: String? = null,
    @SerialName("knowledge_binding_verified") val knowledgeBindingVerified:Boolean=false
)

@Serializable
data class AnswerAttemptPayload(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("question_revision") val questionRevision: String,
    @SerialName("question_id") val questionId: String,
    @SerialName("chosen_index") val chosenIndex: Int,
    @SerialName("chosen_text") val chosenText: String,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("time_spent_seconds") val timeSpentSeconds: Int,
    @SerialName("confidence_level") val confidenceLevel: Int, // 0..3
    @SerialName("session_uuid") val sessionUuid: String,
    @SerialName("quiz_mode") val quizMode: String,
    @SerialName("exam_block") val examBlock: String? = null,
    @SerialName("contest_id") val contestId: String? = null,
    @SerialName("user_note") val userNote: String? = null,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String? = null,
    @SerialName("canonical_topic_id") val canonicalTopicId: String? = null,
    @SerialName("exam_component_id") val examComponentId:String?=null
)

@Serializable
data class SubmitBatchPayload(
    @SerialName("user_id") val userId: Long,
    val attempts: List<AnswerAttemptPayload>
)

@Serializable
data class ProcessedAttemptDto(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("confidence_avg") val confidenceAvg: Int? = null,
    @SerialName("attempts_total") val attemptsTotal: Int,
    @SerialName("correct_total") val correctTotal: Int,
    @SerialName("next_review_at") val nextReviewAt: String,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String? = null,
    @SerialName("canonical_topic_id") val canonicalTopicId: String? = null,
    @SerialName("question_id") val questionId: String,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("confidence_quadrant") val confidenceQuadrant: ConfidenceQuadrant,
    @SerialName("high_confidence_error_alert") val highConfidenceErrorAlert: Boolean,
    @SerialName("streak_atual") val streakAtual: Int,
    @SerialName("streak_max") val streakMax: Int,
    @SerialName("points_total") val pointsTotal: Double,
    @SerialName("points_with_decay") val pointsWithDecay: Double,
    @SerialName("is_mastered") val isMastered: Boolean,
    @SerialName("next_review_interval_days") val nextReviewIntervalDays: Int,
    @SerialName("accuracy_accumulated_pct") val accuracyAccumulatedPct: Double,
    @SerialName("in_error_notebook") val inErrorNotebook: Boolean
)

@Serializable
data class SubmitBatchResponseDto(
    @SerialName("processed_count") val processedCount: Int,
    val results: List<ProcessedAttemptDto>,
    val rejected: List<RejectedAttemptDto> = emptyList()
)

@Serializable
data class AiAssistResponseDto(
    @SerialName("question_id") val questionId: String,
    val kind: String,
    @SerialName("content_markdown") val contentMarkdown: String,
    @SerialName("from_cache") val fromCache: Boolean
)

@Serializable
data class DisciplineMasteryBarDto(
    val discipline: String,
    @SerialName("accuracy_pct") val accuracyPct: Double,
    @SerialName("total_attempts") val totalAttempts: Int,
    @SerialName("mastered_questions") val masteredQuestions: Int,
    @SerialName("points_accumulated") val pointsAccumulated: Double
)

@Serializable
data class ConfidenceMatrixSummaryDto(
    @SerialName("high_confidence_correct") val highConfidenceCorrect: Int,
    @SerialName("low_confidence_correct") val lowConfidenceCorrect: Int,
    @SerialName("low_confidence_error") val lowConfidenceError: Int,
    @SerialName("high_confidence_error") val highConfidenceError: Int
)

@Serializable
data class DailyHeatmapCellDto(
    @SerialName("date_ymd") val dateYmd: String,
    @SerialName("questions_count") val questionsCount: Int,
    @SerialName("accuracy_pct") val accuracyPct: Double
)

@Serializable
data class ProgressAnalyticsDto(
    @SerialName("user_id") val userId: Long,
    @SerialName("global_accuracy_pct") val globalAccuracyPct: Double,
    @SerialName("total_questions_practiced") val totalQuestionsPracticed: Int,
    @SerialName("total_mastered_questions") val totalMasteredQuestions: Int,
    @SerialName("readiness_index_score") val readinessIndexScore: Double,
    @SerialName("historical_cutoff_reference") val historicalCutoffReference: Double,
    @SerialName("disciplines_sorted_by_weakness") val disciplinesSortedByWeakness: List<DisciplineMasteryBarDto>,
    @SerialName("confidence_matrix") val confidenceMatrix: ConfidenceMatrixSummaryDto,
    @SerialName("activity_heatmap") val activityHeatmap: List<DailyHeatmapCellDto>
)

/**
 * Lista oficial das 20 categorias de reporte de problemas do Cogniva (`VALID_PROBLEM_LIST`)
 */
val VALID_PROBLEM_CATEGORIES = listOf(
    "Questão com Erros de Conteúdo",
    "Questão com Formatação Ruim",
    "Questão Mal Formulada",
    "Questão com Erros de Gramática ou Ortografia",
    "Enunciado Mal Formulado",
    "Enunciado c/ Erros de Conteúdo",
    "Enunciado c/ Formatação Ruim",
    "Enunciado c/ Erros de Gramática ou Ortografia",
    "Alternativas Mal Formuladas",
    "Alternativas Corretas em Duplicidade",
    "Alternativas Muito Fáceis",
    "Alternativas c/ Erros de Conteúdo",
    "Alternativas c/ Formatação Ruim",
    "Alternativas c/ Erros de Gramática ou Ortografia",
    "Explicação c/ Formatação Ruim",
    "Explicação Incompleta",
    "Explicação Divergente c/ Alternativa Correta",
    "Explicação c/ Erros de Conteúdo",
    "Explicação c/ Erros de Gramática ou Ortografia",
    "Outro Problema com a Questão"
)

@Serializable
data class ApkInfoDto(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("sha256_checksum") val sha256Checksum: String,
    @SerialName("download_path") val downloadPath: String = "/api/v1/apk/download"
)

data class AccountAuthUiState(
    val isSubmitting: Boolean = false,
    val isAuthenticated: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

data class ApkUpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val installedVersionCode: Int = 1,
    val installedVersionName: String = "2.0.0",
    val remoteInfo: ApkInfoDto? = null,
    val hasUpdate: Boolean = false,
    val statusMessage: String = "Toque em 'Verificar Versão' para consultar o servidor COGNIVA."
)

@Serializable
data class RejectedAttemptDto(@SerialName("attempt_id") val attemptId: String, @SerialName("question_id") val questionId: String, val message: String, val retryable: Boolean)
