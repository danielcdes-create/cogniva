package br.com.concursoprep.app.data.remote

import br.com.concursoprep.app.domain.model.AiAssistResponseDto
import br.com.concursoprep.app.domain.model.ApkInfoDto
import br.com.concursoprep.app.domain.model.AuthSessionDto
import br.com.concursoprep.app.domain.model.ContestDiffScreenDto
import br.com.concursoprep.app.domain.model.DisciplineSyllabusGroupDto
import br.com.concursoprep.app.domain.model.ProgressAnalyticsDto
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.SubmitBatchPayload
import br.com.concursoprep.app.domain.model.SubmitBatchResponseDto
import br.com.concursoprep.app.domain.model.TodayDashboardDto
import br.com.concursoprep.app.domain.model.UserProfileDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class TelegramLinkPayload(
    @SerialName("user_id") val userId: Long? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class OnboardingPayload(
    @SerialName("user_id") val userId: Long,
    @SerialName("active_contest_ids") val activeContestIds: List<String>,
    @SerialName("daily_questions_target") val dailyQuestionsTarget: Int,
    @SerialName("daily_study_hours_target") val dailyStudyHoursTarget: Double,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class QuizGeneratePayload(
    @SerialName("user_id") val userId: Long,
    val mode: QuizMode,
    val quantity: Int,
    @SerialName("contest_id") val contestId: String? = null,
    @SerialName("block_name") val blockName: String? = null,
    val disciplina: String? = null,
    val source: String? = null,
    @SerialName("syllabus_topic_id") val syllabusTopicId: String? = null
)

@Serializable
data class ReportProblemPayload(
    @SerialName("user_id") val userId: Long,
    @SerialName("problem_category") val problemCategory: String
)

@Serializable
data class ExamTopicPayload(
    @SerialName("local_topic_id") val localTopicId: String,
    @SerialName("canonical_topic_id") val canonicalTopicId: String,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String,
    @SerialName("display_title") val displayTitle: String,
    @SerialName("diff_status") val diffStatus: String
)

@Serializable
data class ExamSubjectPayload(
    @SerialName("local_subject_id") val localSubjectId: String,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String,
    @SerialName("display_name") val displayName: String,
    val category: String,
    val weight: Double,
    @SerialName("question_count") val questionCount: Int? = null,
    @SerialName("relevance_tier") val relevanceTier: String,
    val topics: List<ExamTopicPayload>
)

@Serializable
data class ExamBlueprintPayload(
    @SerialName("_id") val id: String,
    val title: String,
    @SerialName("short_name") val shortName: String,
    val institution: String,
    val role: String,
    @SerialName("trunk_slug") val trunkSlug: String,
    val status: String,
    @SerialName("exam_boards") val examBoards: List<String>,
    @SerialName("target_date") val targetDate: JsonObject,
    @SerialName("historical_cutoff_score") val historicalCutoffScore: Double,
    @SerialName("is_active_in_ladder") val isActiveInLadder: Boolean,
    @SerialName("ladder_priority") val ladderPriority: Int,
    val subjects: List<ExamSubjectPayload>,
    @SerialName("sync_status") val syncStatus: String
)

interface ConcursoApiService {

    @POST("auth/telegram-link")
    suspend fun linkTelegramOrLogin(
        @Body payload: TelegramLinkPayload
    ): AuthSessionDto

    @PUT("auth/onboarding")
    suspend fun saveOnboardingPreferences(
        @Body payload: OnboardingPayload
    ): UserProfileDto

    @GET("home/today")
    suspend fun getTodayDashboard(
        @Query("user_id") userId: Long
    ): TodayDashboardDto

    @GET("contests/{contestId}/syllabus")
    suspend fun getContestSyllabus(
        @Path("contestId") contestId: String,
        @Query("user_id") userId: Long
    ): List<DisciplineSyllabusGroupDto>

    @GET("contests/{contestId}/diff")
    suspend fun getContestEditalDiff(
        @Path("contestId") contestId: String
    ): ContestDiffScreenDto

    @POST("quiz/generate")
    suspend fun generateQuizQuestions(
        @Body payload: QuizGeneratePayload
    ): List<QuestionDto>

    @POST("quiz/submit-batch")
    suspend fun submitAttemptsBatch(
        @Body payload: SubmitBatchPayload
    ): SubmitBatchResponseDto

    @POST("questions/{id}/ai/{kind}")
    suspend fun requestAiTutor(
        @Path("id") questionId: String,
        @Path("kind") kind: String,
        @Query("hint_index") hintIndex: Int? = null
    ): AiAssistResponseDto

    @POST("questions/{id}/report")
    suspend fun reportQuestionProblem(
        @Path("id") questionId: String,
        @Body payload: ReportProblemPayload
    )

    @GET("analytics/progress")
    suspend fun getProgressAnalytics(
        @Query("user_id") userId: Long
    ): ProgressAnalyticsDto

    @GET("apk/info")
    suspend fun getApkInfo(): ApkInfoDto

    @POST("exams")
    suspend fun createExam(@Body payload: ExamBlueprintPayload): ExamBlueprintPayload

    @PUT("exams/{examId}")
    suspend fun updateExam(
        @Path("examId") examId: String,
        @Body payload: ExamBlueprintPayload
    ): ExamBlueprintPayload
}
