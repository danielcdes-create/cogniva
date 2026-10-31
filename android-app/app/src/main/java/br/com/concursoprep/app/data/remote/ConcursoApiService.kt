package br.com.concursoprep.app.data.remote

import br.com.concursoprep.app.domain.model.AiAssistResponseDto
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
}
