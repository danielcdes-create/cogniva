package br.com.concursoprep.app.domain.model

import br.com.concursoprep.app.domain.exam.CalibrationEngine
import br.com.concursoprep.app.domain.exam.CognitiveStrategy
import br.com.concursoprep.app.domain.exam.ConceptMasteryEvaluation
import br.com.concursoprep.app.domain.exam.DeclaredConfidence
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.domain.exam.ExamMomentScenarioDto
import br.com.concursoprep.app.domain.exam.FormatConfidenceCalibrationCellDto
import br.com.concursoprep.app.domain.exam.MetacognitiveAssessmentDto
import br.com.concursoprep.app.domain.exam.PrioritizationEngine
import java.text.Normalizer
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
    SINGLE,
    ADAPTIVE_LOOP,
    STRATEGY_BATTERY,
    ENTRY_60S,
    TRANSFER_PROBE
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
data class ExamGapSummaryDto(
    @SerialName("exam_id") val examId: String? = null,
    @SerialName("exam_name") val examName: String? = null,
    @SerialName("days_until_exam") val daysUntilExam: Long? = null,
    @SerialName("verified_units") val verifiedUnits: Int = 0,
    @SerialName("consolidating_units") val consolidatingUnits: Int = 0,
    @SerialName("insufficient_evidence_units") val insufficientEvidenceUnits: Int = 0,
    @SerialName("total_units") val totalUnits: Int = 0,
    @SerialName("wrong_answer_penalty") val wrongAnswerPenalty: Double = 0.0,
    @SerialName("historical_cutoff_score") val historicalCutoffScore: Double = 0.0,
    @SerialName("min_questions_for_mastery") val minQuestionsForMastery: Int = 15,
    @SerialName("evidence_in_progress_units") val evidenceInProgressUnits: Int = 0,
    @SerialName("active_error_units") val activeErrorUnits: Int = 0,
    @SerialName("transfer_verified_units") val transferVerifiedUnits: Int = 0,
    @SerialName("transfer_available_units") val transferAvailableUnits: Int = 0,
    @SerialName("transfer_unmeasured_units") val transferUnmeasuredUnits: Int = 0
)

@Serializable
data class ThreeSignalSummaryDto(
    @SerialName("grounded_hits") val groundedHits: Int = 0,
    @SerialName("illusory_competence_hits") val illusoryCompetenceHits: Int = 0,
    @SerialName("hesitant_hits") val hesitantHits: Int = 0,
    @SerialName("unknown_grounding_hits") val unknownGroundingHits: Int = 0,
    @SerialName("overconfidence_errors") val overconfidenceErrors: Int = 0,
    @SerialName("conceptual_errors") val conceptualErrors: Int = 0,
    @SerialName("retrieval_failures") val retrievalFailures: Int = 0,
    @SerialName("strategic_omissions") val strategicOmissions: Int = 0,
    @SerialName("omission_net_points_saved") val omissionNetPointsSaved: Double = 0.0
)

@Serializable
data class EntryProtocolMetricsDto(
    @SerialName("impressions_count") val impressionsCount: Int = 0,
    @SerialName("voluntary_starts_count") val voluntaryStartsCount: Int = 0,
    @SerialName("first_question_completed_count") val firstQuestionCompletedCount: Int = 0,
    @SerialName("conscious_rest_days_count") val consciousRestDaysCount: Int = 0,
    @SerialName("spontaneous_return_days_count") val spontaneousReturnDaysCount: Int = 0,
    @SerialName("active_study_days_count") val activeStudyDaysCount: Int = 0,
    @SerialName("rested_today") val restedToday: Boolean = false
)

@Serializable
data class StrategyBandGuidanceDto(
    @SerialName("confidence_band") val confidenceBand: Int,
    @SerialName("calibrated_mean") val calibratedMean: Double,
    val decision: br.com.concursoprep.app.domain.exam.StrategyDecision
)

@Serializable
data class NextActionResponseDto(
    @SerialName("schedule_header") val scheduleHeader: String = PrioritizationEngine.formatEntry60sHeader(),
    @SerialName("entry_question") val entryQuestion: QuestionDto? = null,
    @SerialName("entry_reason") val entryReason: String = "Assunto familiar com retenção pendente.",
    @SerialName("priority_topic_id") val priorityTopicId: String? = null,
    @SerialName("recommendation_id") val recommendationId: String? = null,
    @SerialName("decision_rule_code") val decisionRuleCode: String = "BASELINE_REFERENCE",
    @SerialName("recommended_quiz_mode") val recommendedQuizMode: String = "ENTRY_60S",
    @SerialName("exam_gap_summary") val examGapSummary: ExamGapSummaryDto = ExamGapSummaryDto(),
    @SerialName("three_signal_summary") val threeSignalSummary: ThreeSignalSummaryDto = ThreeSignalSummaryDto(),
    @SerialName("calibration_cells") val calibrationCells: List<br.com.concursoprep.app.domain.exam.CalibrationCellDto> = emptyList(),
    @SerialName("strategy_guidance") val strategyGuidance: List<StrategyBandGuidanceDto> = emptyList(),
    @SerialName("exam_scenarios") val examScenarios: List<ExamMomentScenarioDto> = emptyList(),
    @SerialName("fatigue_report") val fatigueReport: br.com.concursoprep.app.domain.exam.FatigueReportDto = br.com.concursoprep.app.domain.exam.FatigueEngine.evaluateFatigue(0, 0, 0, 0, 0)
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
    val syncMessage: String? = null,
    val nextAction: NextActionResponseDto? = null,
    val entryMetrics: EntryProtocolMetricsDto = EntryProtocolMetricsDto()
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
data class EvidenceOptionDto(
    @SerialName("evidence_id") val evidenceId: String,
    val text: String,
    @SerialName("source_id") val sourceId: String? = null
)

@Serializable
data class EvidenceChallengeDto(
    val prompt: String,
    val options: List<EvidenceOptionDto>,
    @SerialName("correct_evidence_id") val correctEvidenceId: String
)

@Serializable
data class ConceptualErrorItemDto(
    @SerialName("error_id") val errorId: String,
    val label: String,
    val description: String
)

@Serializable
data class EditorialCardDto(
    @SerialName("legal_provisions") val legalProvisions: List<String> = emptyList(),
    @SerialName("tested_rule") val testedRule: String,
    @SerialName("exception_clause") val exceptionClause: String? = null,
    @SerialName("decisive_stem_excerpt") val decisiveStemExcerpt: String,
    @SerialName("official_source_excerpt") val officialSourceExcerpt: String? = null,
    @SerialName("plausible_conceptual_errors") val plausibleConceptualErrors: List<ConceptualErrorItemDto> = emptyList()
)

@Serializable
data class NormativeLookupItemDto(
    @SerialName("source_id") val sourceId: String,
    val title: String,
    val locator: String? = null,
    val excerpt: String,
    @SerialName("canonical_uri") val canonicalUri: String
)

@Serializable
data class ConceptLookupItemDto(
    @SerialName("concept_id") val conceptId: String,
    val label: String
)

@Serializable
data class EncyclopedicSummaryDto(
    val title: String,
    val extract: String,
    @SerialName("source_url") val sourceUrl: String
)

@Serializable
data class ContextualLookupRequestDto(
    @SerialName("selected_text") val selectedText: String
)

@Serializable
data class ContextualLookupResponseDto(
    @SerialName("question_id") val questionId: String,
    val query: String,
    val disciplina: String,
    @SerialName("normative_matches") val normativeMatches: List<NormativeLookupItemDto> = emptyList(),
    @SerialName("concept_matches") val conceptMatches: List<ConceptLookupItemDto> = emptyList(),
    @SerialName("encyclopedic_summary") val encyclopedicSummary: EncyclopedicSummaryDto? = null,
    @SerialName("google_search_url") val googleSearchUrl: String,
    @SerialName("planalto_search_url") val planaltoSearchUrl: String,
    @SerialName("jurisprudence_search_url") val jurisprudenceSearchUrl: String
)

data class ContextualLookupUiState(
    val questionId: String,
    val selectedText: String,
    val isLoading: Boolean = false,
    val response: ContextualLookupResponseDto? = null,
    val aiExplanationMarkdown: String? = null,
    val isAiLoading: Boolean = false
)

private val LEGAL_PROVISION_REGEX = Regex(
    "(?i)\\b(?:art(?:igo)?\\.?\\s*\\d+[º°]?(?:\\s*,\\s*(?:§\\s*\\d+[º°]?|parágrafo\\s+único|inciso\\s+[IVXLCDM]+|[a-z]\\)))?|lei(?:\\s+complementar|\\s+n[º°]?)?\\s*[\\d\\.]+(?:/\\d{2,4})?|cf(?:/88)?|súmula(?:\\s+vinculante)?\\s*(?:n[º°]?\\s*)?\\d+|decreto(?:\\s+n[º°]?)?\\s*[\\d\\.]+(?:/\\d{2,4})?|resolu[çc][ãa]o\\s+(?:cmn|bcb|cvm)?\\s*n?[º°]?\\s*[\\d\\.]+)"
)
private val GENUINE_EXCEPTION_REGEX = Regex(
    "(?i)\\b(salvo|exceto|excetuad[oa]s?|ressalvad[oa]s?|à\\s+exce[çc][ãa]o|sem\\s+preju[íi]zo)\\b"
)
private val STEM_DELIMITER_REGEX = Regex(
    "(?i)\\b(salvo|exceto|ressalvad[oa]s?|vedad[oa]s?|independentemente|apenas|exclusivamente|somente|necessariamente|isoladamente|sempre|nunca|jamais|sem\\s+preju[íi]zo|não\\s+[ée]|implica|exige|prescinde)\\b"
)
private val LEADING_VERDICT_REGEX = Regex(
    "(?i)^\\s*(?:[✅❌✔]\\s*:?\\s*)?(?:(?:gabarito(?:\\s+oficial)?|item|assertiva|afirmativa|resposta)\\s*:\\s*)?(?:(?:certo|errado|corret[oa]|incorret[oa]|verdadeir[oa]|fals[oa])\\s*[.!—–:-]+\\s*|(?:a\\s+)?(?:alternativa|op[çc][ãa]o)\\s+correta\\s+[ée]\\s+(?:a\\s+)?(?:primeira|segunda|terceira|quarta|quinta|letra\\s+[a-e]|[a-e]|[1-5][ªº]?)\\s*[.!—–:-]+\\s*)"
)
private val LEADING_TF_VERDICT_ONLY_REGEX = Regex(
    "(?i)^\\s*(?:gabarito(?:\\s+oficial)?\\s*:\\s*)?(?:certo|errado)\\s*[.!—–:-]+\\s+"
)
private val TRAILING_REFERENCES_REGEX = Regex(
    "(?is)\\s*(Refer[êe]ncias?\\s*:\\s*.+)$"
)
private val COMPARISON_NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")

private fun truncateChars(raw: String, maxChars: Int): String {
    val t = raw.trim()
    if (t.length <= maxChars) return t
    val limit = (maxChars - 1).coerceAtLeast(1)
    val prefix = t.take(limit)
    val minWordCut = (limit * 60) / 100
    val lastWs = prefix.indexOfLast { it.isWhitespace() }
    val cleanPrefix = if (lastWs >= minWordCut) prefix.substring(0, lastWs) else prefix
    val trimmedEnd = cleanPrefix.trimEnd().trimEnd(',', ';', ':', '-', '–', '—').trimEnd()
    return "$trimmedEnd…"
}

internal fun stripVerdictAndReferences(raw: String): String {
    val withoutIcons = raw.replace("✅:", "").replace("❌:", "").trim()
    val strippedVerdict = LEADING_VERDICT_REGEX.replace(withoutIcons, "").trim()
    val withoutRefs = TRAILING_REFERENCES_REGEX.replace(strippedVerdict, "").trim()
    return when {
        withoutRefs.length >= 16 -> withoutRefs
        strippedVerdict.length >= 16 -> strippedVerdict
        else -> withoutIcons
    }
}

private fun extractGenuineExceptionSentence(raw: String): String? {
    val cleaned = stripVerdictAndReferences(raw)
    return cleaned.split('.', ';', '\n')
        .map { it.trim() }
        .firstOrNull { it.length >= 18 && GENUINE_EXCEPTION_REGEX.containsMatchIn(it) }
        ?.let { truncateChars(it, 240) }
}

private fun pickDecisiveStemFragment(stem: String): String {
    val sentences = stem.split('.', ';', '?', '\n')
        .map { it.trim() }
        .filter { it.length >= 16 }
    val chosen = sentences.firstOrNull { STEM_DELIMITER_REGEX.containsMatchIn(it) }
        ?: sentences.lastOrNull()
        ?: stem.trim()
    if (chosen.length <= 135) return chosen

    val match = STEM_DELIMITER_REGEX.find(chosen)
    if (match != null && match.range.first > 32) {
        val targetStart = (match.range.first - 20).coerceAtLeast(0)
        val wsIdx = chosen.indexOf(' ', targetStart)
        val startIdx = if (wsIdx in targetStart until match.range.first) wsIdx + 1 else targetStart
        val windowed = chosen.substring(startIdx)
            .trimStart { it.isWhitespace() || it in charArrayOf(',', ';', ':', '-', '–', '—') }
        if (windowed.length >= 24) {
            return "…" + truncateChars(windowed, 130)
        }
    }
    return truncateChars(chosen, 135)
}

private fun deterministicSwapEvidenceOrder(questionId: String): Boolean {
    var sum = 0
    for (b in questionId.encodeToByteArray()) {
        sum = (sum + (b.toInt() and 0xFF)) and 0xFF
    }
    return (sum and 1) == 1
}

fun normalizeEvidenceOptionId(rawId: String?, hasCuratedChallenge: Boolean = false): String? {
    val trimmed = rawId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (hasCuratedChallenge) return trimmed
    return when {
        trimmed.equals("ev_decisive", ignoreCase = true) -> "EV_DECISIVE"
        trimmed.equals("ev_premissa_divergente", ignoreCase = true) ||
            trimmed.equals("ev_err_1", ignoreCase = true) ||
            trimmed.equals("ev_err_2", ignoreCase = true) -> "EV_PREMISSA_DIVERGENTE"
        else -> trimmed
    }
}

private fun foldPostAnswerTextForComparison(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
    val stripped = stripVerdictAndReferences(nfc)
        .removePrefix("…")
        .removeSuffix("…")
        .removeSuffix("...")
        .lowercase()
    return COMPARISON_NON_ALNUM_REGEX.replace(stripped, " ").trim()
}

/**
 * Verifica deterministicamente se um campo resumido (`testedRule` ou `exceptionClause`) apenas repete
 * conteúdo já presente na explicação principal ou no próprio enunciado, evitando blocos duplicados.
 */
fun isPostAnswerTextRedundant(candidate: String?, vararg primaryTexts: String?): Boolean {
    val candNorm = foldPostAnswerTextForComparison(candidate)
    if (candNorm.isEmpty()) return true
    for (primary in primaryTexts) {
        val primNorm = foldPostAnswerTextForComparison(primary)
        if (primNorm.isEmpty()) continue
        if (primNorm.contains(candNorm)) return true
        if (primNorm.length >= 24 && candNorm.contains(primNorm)) return true
        if (candNorm.length >= 24) {
            val prefixLen = minOf(candNorm.length, 56)
            if (primNorm.startsWith(candNorm.take(prefixLen))) return true
        }
    }
    return false
}

data class FormattedExplanationParts(
    val body: String,
    val referenceFooter: String? = null
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
    @SerialName("knowledge_binding_verified") val knowledgeBindingVerified: Boolean = false,
    @SerialName("concept_ids") val conceptIds: List<String> = emptyList(),
    @SerialName("evidence_challenge") val evidenceChallenge: EvidenceChallengeDto? = null,
    @SerialName("editorial_card") val editorialCard: EditorialCardDto? = null
) {
    fun hasCustomEvidenceChallenge(): Boolean {
        val ch = evidenceChallenge ?: return false
        if (ch.options.size < 2) return false
        val isSynthesizedIds = ch.correctEvidenceId.equals("EV_DECISIVE", ignoreCase = true) &&
            ch.options.all {
                it.evidenceId.equals("EV_DECISIVE", ignoreCase = true) ||
                    it.evidenceId.equals("EV_PREMISSA_DIVERGENTE", ignoreCase = true) ||
                    it.evidenceId.startsWith("ev_err_", ignoreCase = true)
            }
        return !isSynthesizedIds
    }

    fun formatPostAnswerCorrectLabel(correctIndex: Int): String {
        val rawAnswer = answers.getOrNull(correctIndex)?.trim().orEmpty()
        if (questionType == QuestionType.TRUE_FALSE) {
            return rawAnswer.ifEmpty { "Gabarito oficial" }
        }
        if (correctIndex in answers.indices) {
            val letter = ('A' + correctIndex)
            return if (rawAnswer.isNotEmpty() && rawAnswer.length <= 36) {
                "Alternativa $letter ($rawAnswer)"
            } else {
                "Alternativa $letter"
            }
        }
        return rawAnswer.ifEmpty { "Gabarito oficial" }
    }

    fun formatPostAnswerExplanation(): FormattedExplanationParts? {
        val raw = explanation?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanedVerdict = if (questionType == QuestionType.TRUE_FALSE) {
            val stripped = LEADING_TF_VERDICT_ONLY_REGEX.replace(raw, "").trim()
            if (stripped.length >= 16) stripped else raw
        } else {
            raw
        }
        val refMatch = TRAILING_REFERENCES_REGEX.find(cleanedVerdict)
        if (refMatch != null) {
            val mainBody = cleanedVerdict.substring(0, refMatch.range.first).trim()
            val refText = refMatch.groupValues[1].trim()
            if (mainBody.length >= 20 && refText.isNotEmpty()) {
                return FormattedExplanationParts(body = mainBody, referenceFooter = refText)
            }
        }
        return FormattedExplanationParts(body = cleanedVerdict, referenceFooter = null)
    }

    fun resolveActionableConceptualError(
        card: EditorialCardDto,
        chosenIndex: Int?
    ): ConceptualErrorItemDto? {
        if (card.plausibleConceptualErrors.isEmpty()) return null
        if (questionType == QuestionType.MULTIPLE_CHOICE) {
            val nonTrivial = card.plausibleConceptualErrors.filterNot { it.errorId.startsWith("ERR_ALT_") }
            if (nonTrivial.isEmpty()) return null
            val chosenText = chosenIndex?.let { answers.getOrNull(it)?.trim() }.orEmpty()
            if (chosenText.isNotEmpty()) {
                nonTrivial.firstOrNull {
                    chosenText.contains(it.label, ignoreCase = true) ||
                        it.label.contains(chosenText.take(32), ignoreCase = true)
                }?.let { return it }
            }
            return nonTrivial.first()
        }
        // Em Certo/Errado: exibe erro conceitual estruturado se for curado especificamente
        // ou se a questão não possuir comentário textual principal.
        val genericTfIds = setOf("ERR_REGRA_EXCECAO", "ERR_ESCOPO_ASSERTIVA")
        val curatedSpecific = card.plausibleConceptualErrors.firstOrNull { it.errorId !in genericTfIds }
        if (curatedSpecific != null) return curatedSpecific
        return if (explanation.isNullOrBlank()) card.plausibleConceptualErrors.firstOrNull() else null
    }

    /**
     * Retorna a Ficha Editorial Rastreável curada ou sintetizada deterministicamente
     * a partir das fontes oficiais do catálogo e do gabarito/enunciado (zero alucinação).
     */
    fun effectiveEditorialCard(): EditorialCardDto {
        if (editorialCard != null && editorialCard.testedRule.isNotBlank()) {
            val isLegacyVerdictPrefixed = !explanation.isNullOrBlank() &&
                LEADING_TF_VERDICT_ONLY_REGEX.containsMatchIn(editorialCard.testedRule)
            val isLegacyFalseStemException = editorialCard.exceptionClause != null &&
                !GENUINE_EXCEPTION_REGEX.containsMatchIn(editorialCard.exceptionClause) &&
                isPostAnswerTextRedundant(editorialCard.exceptionClause, question)
            return if (isLegacyVerdictPrefixed || isLegacyFalseStemException) {
                editorialCard.copy(
                    testedRule = if (isLegacyVerdictPrefixed) stripVerdictAndReferences(editorialCard.testedRule) else editorialCard.testedRule,
                    exceptionClause = if (isLegacyFalseStemException) null else editorialCard.exceptionClause
                )
            } else {
                editorialCard
            }
        }
        val linkedSources = runCatching {
            ExamCatalog.normativeSourcesForQuestion(canonicalTopicId, conceptIds)
        }.getOrDefault(emptyList())
        val combined = "$question\n${explanation.orEmpty()}"
        val provisions = linkedSources.map { ns ->
            if (!ns.locator.isNullOrBlank()) "${ns.title} (${ns.locator})" else ns.title
        }.toMutableList()
        LEGAL_PROVISION_REGEX.findAll(combined).forEach { m ->
            val found = m.value.trim()
            if (found.length >= 3 && provisions.none { it.equals(found, ignoreCase = true) } && provisions.size < 5) {
                provisions += truncateChars(found, 120)
            }
        }
        val cleanedExp = explanation?.takeIf { it.isNotBlank() }?.let { stripVerdictAndReferences(it) }
        val firstExp = cleanedExp?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.length >= 20 }
            ?: cleanedExp?.takeIf { it.isNotBlank() }
        val testedRule = when {
            linkedSources.any { it.excerpt.isNotBlank() } ->
                truncateChars(linkedSources.first { it.excerpt.isNotBlank() }.excerpt, 320)
            !firstExp.isNullOrBlank() -> truncateChars(firstExp, 320)
            else -> {
                val cIdx = correctAnswerIndices.firstOrNull() ?: 0
                val cText = answers.getOrNull(cIdx) ?: "Gabarito oficial"
                val disc = disciplina.trim().ifEmpty { "disciplina do edital" }
                truncateChars("Aplicação direta do critério cobrado em $disc (gabarito oficial: $cText).", 320)
            }
        }
        val isTrueStatement = questionType == QuestionType.TRUE_FALSE &&
            correctAnswerIndices.firstOrNull()?.let { answers.getOrNull(it) }?.trim().equals("Certo", ignoreCase = true)
        val exceptionClause = linkedSources.firstNotNullOfOrNull { extractGenuineExceptionSentence(it.excerpt) }
            ?: explanation?.takeIf { it.isNotBlank() }?.let { extractGenuineExceptionSentence(it) }
            ?: if (explanation.isNullOrBlank() && isTrueStatement) extractGenuineExceptionSentence(question) else null
        val decisiveStem = pickDecisiveStemFragment(question)

        val conceptualErrors = if (questionType == QuestionType.TRUE_FALSE) {
            if (exceptionClause != null) {
                listOf(
                    ConceptualErrorItemDto(
                        errorId = "ERR_REGRA_EXCECAO",
                        label = "Inversão entre regra geral e cláusula restritiva/exceção",
                        description = "Desconsiderar a ressalva ou exceção expressa aplicável ao caso concreto."
                    )
                )
            } else {
                listOf(
                    ConceptualErrorItemDto(
                        errorId = "ERR_ESCOPO_ASSERTIVA",
                        label = "Generalização ou restrição indevida da assertiva",
                        description = "Aceitar extrapolação além do alcance do dispositivo ou conceito cobrado pela banca."
                    )
                )
            }
        } else {
            val cIdx = correctAnswerIndices.firstOrNull() ?: 0
            answers.mapIndexedNotNull { idx, ans ->
                if (idx == cIdx) null
                else ConceptualErrorItemDto(
                    errorId = "ERR_ALT_${idx + 1}",
                    label = truncateChars("Premissa incorreta na alternativa ${'A' + idx}", 90),
                    description = truncateChars(ans, 200)
                )
            }.take(2)
        }

        return EditorialCardDto(
            legalProvisions = provisions,
            testedRule = testedRule,
            exceptionClause = exceptionClause,
            decisiveStemExcerpt = decisiveStem,
            officialSourceExcerpt = linkedSources.firstOrNull()?.excerpt?.let { truncateChars(it, 320) },
            plausibleConceptualErrors = conceptualErrors
        )
    }

    /**
     * Retorna o seletor opcional pós-resposta apontando exclusivamente para fragmentos concisos
     * do enunciado e da fonte oficial (sem repetir a explicação e sem formular nova questão).
     */
    fun effectiveEvidenceChallenge(): EvidenceChallengeDto? {
        if (questionType == QuestionType.OPEN_ENDED) return null
        if (evidenceChallenge != null && evidenceChallenge.options.size >= 2) {
            val isLegacyRepetitive = !hasCustomEvidenceChallenge() && evidenceChallenge.options.any {
                it.text.startsWith("Trecho decisivo do enunciado e fundamento:") ||
                    it.text.startsWith("Trecho decisivo:") ||
                    it.text.startsWith("Premissa divergente:")
            }
            if (!isLegacyRepetitive) {
                return evidenceChallenge
            }
        }
        val card = effectiveEditorialCard()
        val linkedSources = runCatching {
            ExamCatalog.normativeSourcesForQuestion(canonicalTopicId, conceptIds)
        }.getOrDefault(emptyList())
        val primarySourceId = linkedSources.firstOrNull()?.sourceId

        val decisiveText = when {
            card.legalProvisions.isNotEmpty() -> truncateChars(
                "Critério no trecho \"${card.decisiveStemExcerpt}\" confrontado com ${card.legalProvisions.first()}",
                195
            )
            linkedSources.any { it.title.isNotBlank() } -> truncateChars(
                "Critério no trecho \"${card.decisiveStemExcerpt}\" à luz de ${linkedSources.first { it.title.isNotBlank() }.title}",
                195
            )
            else -> truncateChars(
                "Alcance exato do trecho \"${card.decisiveStemExcerpt}\" diante do conceito central cobrado",
                195
            )
        }

        val divergentText = when {
            questionType == QuestionType.MULTIPLE_CHOICE -> {
                val specificAlt = card.plausibleConceptualErrors
                    .firstOrNull { !it.errorId.startsWith("ERR_ALT_") && it.label.isNotBlank() }
                if (specificAlt != null) {
                    truncateChars("Associação com a hipótese alternativa \"${specificAlt.label}\"", 195)
                } else {
                    "Associação geral com termos familiares das alternativas, sem isolar o requisito específico do comando."
                }
            }
            card.exceptionClause != null ->
                "Aplicação direta da regra geral do tema, sem ponderar a ressalva ou condição específica do item."
            else ->
                "Impressão geral pelos termos técnicos do tema, sem isolar a condição específica afirmada na assertiva."
        }

        val decisiveOpt = EvidenceOptionDto(
            evidenceId = "EV_DECISIVE",
            text = decisiveText,
            sourceId = primarySourceId
        )
        val divergentOpt = EvidenceOptionDto(
            evidenceId = "EV_PREMISSA_DIVERGENTE",
            text = divergentText,
            sourceId = null
        )
        val orderedOptions = if (deterministicSwapEvidenceOrder(id)) {
            listOf(divergentOpt, decisiveOpt)
        } else {
            listOf(decisiveOpt, divergentOpt)
        }

        return EvidenceChallengeDto(
            prompt = "Opcional — qual ponto guiou sua decisão nesta questão?",
            options = orderedOptions,
            correctEvidenceId = "EV_DECISIVE"
        )
    }
}

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
    @SerialName("exam_component_id") val examComponentId: String? = null,
    @SerialName("is_blank") val isBlank: Boolean = false,
    @SerialName("confidence_explicit") val confidenceExplicit: Boolean = false,
    @SerialName("selected_evidence_id") val selectedEvidenceId: String? = null,
    @SerialName("position_in_session") val positionInSession: Int? = null,
    @SerialName("elapsed_session_seconds") val elapsedSessionSeconds: Int? = null,
    @SerialName("session_interrupted") val sessionInterrupted: Boolean = false,
    @SerialName("declared_confidence") val declaredConfidence: DeclaredConfidence? = null,
    @SerialName("cognitive_strategy") val cognitiveStrategy: CognitiveStrategy? = null,
    @SerialName("strategy_skipped") val strategySkipped: Boolean = false,
    @SerialName("active_seconds") val activeSeconds: Int? = null,
    @SerialName("answer_change_count") val answerChangeCount: Int = 0,
    @SerialName("initial_chosen_index") val initialChosenIndex: Int? = null,
    @SerialName("consulted_resources") val consultedResources: List<String> = emptyList(),
    @SerialName("focus_loss_count") val focusLossCount: Int = 0
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
    @SerialName("in_error_notebook") val inErrorNotebook: Boolean,
    @SerialName("metacognitive_assessment") val metacognitiveAssessment: MetacognitiveAssessmentDto? = null
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
    @SerialName("points_accumulated") val pointsAccumulated: Double,
    @SerialName("distinct_questions") val distinctQuestions: Int = 0
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
    @SerialName("activity_heatmap") val activityHeatmap: List<DailyHeatmapCellDto>,
    val calibrationCells: List<br.com.concursoprep.app.domain.exam.CalibrationCellDto> = emptyList(),
    val strategyGuidance: List<StrategyBandGuidanceDto> = emptyList(),
    val fatigueReport: br.com.concursoprep.app.domain.exam.FatigueReportDto = br.com.concursoprep.app.domain.exam.FatigueEngine.evaluateFatigue(0, 0, 0, 0, 0),
    val wrongAnswerPenalty: Double = 0.0,
    val examGapSummary: ExamGapSummaryDto = ExamGapSummaryDto(),
    val threeSignalSummary: ThreeSignalSummaryDto = ThreeSignalSummaryDto(),
    val examScenarios: List<ExamMomentScenarioDto> = emptyList(),
    val entryMetrics: EntryProtocolMetricsDto = EntryProtocolMetricsDto(),
    val studentLearningAnalytics: StudentLearningAnalyticsDto? = null
)

@Serializable
data class CtvEvaluateRequestDto(
    @SerialName("parent_attempt_id") val parentAttemptId: String,
    @SerialName("source_question_id") val sourceQuestionId: String,
    @SerialName("session_uuid") val sessionUuid: String,
    @SerialName("quiz_mode") val quizMode: String,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("is_blank") val isBlank: Boolean = false,
    @SerialName("declared_confidence") val declaredConfidence: DeclaredConfidence? = null,
    @SerialName("cognitive_strategy") val cognitiveStrategy: CognitiveStrategy? = null,
    @SerialName("confidence_level") val confidenceLevel: Int = 2,
    @SerialName("session_ctv_count") val sessionCtvCount: Int = 0,
    @SerialName("attempts_since_last_ctv") val attemptsSinceLastCtv: Int = 0,
    @SerialName("force_post_instruction") val forcePostInstruction: Boolean = false
)

@Serializable
data class CtvEvaluateResponseDto(
    val triggered: Boolean,
    val status: String,
    @SerialName("offer_id") val offerId: String? = null,
    @SerialName("trigger_reason") val triggerReason: String? = null,
    @SerialName("trigger_cohort") val triggerCohort: String = "DIFFICULTY_DETECTED",
    @SerialName("diagnostic_purpose") val diagnosticPurpose: String? = null,
    @SerialName("verification_mode") val verificationMode: String = "CONCEPTUAL_TRANSFER",
    @SerialName("framing_reason_code") val framingReasonCode: String? = null,
    @SerialName("framing_reason_pt") val framingReasonPt: String? = null,
    @SerialName("target_concept_id") val targetConceptId: String? = null,
    @SerialName("target_tested_rule") val targetTestedRule: String? = null,
    @SerialName("delivery_source") val deliverySource: String? = null,
    @SerialName("verification_timing") val verificationTiming: String = "PRE_FEEDBACK",
    @SerialName("was_previously_exposed") val wasPreviouslyExposed: Boolean = false,
    @SerialName("complementary_question") val complementaryQuestion: QuestionDto? = null,
    @SerialName("student_framing_pt") val studentFramingPt: String? = null,
    @SerialName("policy_version") val policyVersion: String = "ctv-policy-v1.0"
)

@Serializable
data class CtvSubmitRequestDto(
    @SerialName("ctv_attempt_id") val ctvAttemptId: String,
    @SerialName("offer_id") val offerId: String? = null,
    @SerialName("parent_attempt_id") val parentAttemptId: String,
    @SerialName("source_question_id") val sourceQuestionId: String,
    @SerialName("complementary_question_id") val complementaryQuestionId: String,
    @SerialName("session_uuid") val sessionUuid: String,
    @SerialName("verification_timing") val verificationTiming: String = "PRE_FEEDBACK",
    @SerialName("verification_mode") val verificationMode: String? = null,
    @SerialName("chosen_index") val chosenIndex: Int,
    @SerialName("chosen_text") val chosenText: String = "",
    @SerialName("declared_confidence") val declaredConfidence: DeclaredConfidence? = null,
    @SerialName("cognitive_strategy") val cognitiveStrategy: CognitiveStrategy? = null,
    @SerialName("active_seconds") val activeSeconds: Int = 15,
    @SerialName("was_previously_exposed") val wasPreviouslyExposed: Boolean = false,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("displayed_at") val displayedAt: String? = null,
    @SerialName("consulted_resources") val consultedResources: List<String> = emptyList(),
    @SerialName("focus_loss_count") val focusLossCount: Int = 0,
    @SerialName("had_decisive_help") val hadDecisiveHelp: Boolean = false,
    @SerialName("trigger_reason") val triggerReason: String? = null,
    @SerialName("delivery_source") val deliverySource: String? = null
)

@Serializable
data class CtvSubmitResponseDto(
    @SerialName("ctv_attempt_id") val ctvAttemptId: String,
    @SerialName("offer_id") val offerId: String = "",
    @SerialName("parent_attempt_id") val parentAttemptId: String,
    @SerialName("complementary_question_id") val complementaryQuestionId: String,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("correct_index") val correctIndex: Int,
    val explanation: String? = null,
    @SerialName("verification_timing") val verificationTiming: String = "PRE_FEEDBACK",
    @SerialName("verification_mode") val verificationMode: String = "CONCEPTUAL_TRANSFER",
    @SerialName("counts_as_independent_transfer") val countsAsIndependentTransfer: Boolean = false,
    @SerialName("classification_code") val classificationCode: String = "",
    @SerialName("target_concept_id") val targetConceptId: String? = null,
    @SerialName("target_tested_rule") val targetTestedRule: String? = null,
    @SerialName("consolidated_diagnostic_title_pt") val consolidatedDiagnosticTitlePt: String,
    @SerialName("consolidated_diagnostic_message_pt") val consolidatedDiagnosticMessagePt: String
)

@Serializable
data class CtvLifecycleEventRequestDto(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("offer_id") val offerId: String,
    @SerialName("parent_attempt_id") val parentAttemptId: String,
    @SerialName("source_question_id") val sourceQuestionId: String,
    @SerialName("complementary_question_id") val complementaryQuestionId: String,
    @SerialName("question_revision") val questionRevision: String? = null,
    @SerialName("event_kind") val eventKind: String,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("trigger_reason") val triggerReason: String? = null,
    @SerialName("verification_mode") val verificationMode: String? = null,
    @SerialName("delivery_source") val deliverySource: String? = null,
    @SerialName("target_concept_id") val targetConceptId: String? = null,
    @SerialName("target_tested_rule") val targetTestedRule: String? = null,
    @SerialName("framing_reason_code") val framingReasonCode: String? = null
)

@Serializable
data class CtvLifecycleEventResponseDto(
    @SerialName("offer_id") val offerId: String,
    val state: String,
    @SerialName("first_displayed_at") val firstDisplayedAt: String? = null,
    @SerialName("dismissed_at") val dismissedAt: String? = null,
    @SerialName("reconciled_attempts_count") val reconciledAttemptsCount: Int = 0
)

@Serializable
data class CtvReconciledOfferDto(
    @SerialName("offer_id") val offerId: String,
    @SerialName("parent_attempt_id") val parentAttemptId: String = "",
    @SerialName("source_question_id") val sourceQuestionId: String = "",
    @SerialName("complementary_question_id") val complementaryQuestionId: String,
    @SerialName("content_revision") val contentRevision: String = "",
    @SerialName("semantic_revision") val semanticRevision: String = "",
    val state: String = "SUPPLIED",
    @SerialName("verification_mode") val verificationMode: String = "CONCEPTUAL_TRANSFER",
    @SerialName("trigger_reason") val triggerReason: String? = null,
    @SerialName("trigger_cohort") val triggerCohort: String = "DIFFICULTY_DETECTED",
    @SerialName("first_displayed_at") val firstDisplayedAt: String? = null,
    @SerialName("dismissed_at") val dismissedAt: String? = null
)

@Serializable
data class CtvReconciledAttemptDto(
    @SerialName("ctv_attempt_id") val ctvAttemptId: String,
    @SerialName("offer_id") val offerId: String = "",
    @SerialName("parent_attempt_id") val parentAttemptId: String = "",
    @SerialName("source_question_id") val sourceQuestionId: String = "",
    @SerialName("complementary_question_id") val complementaryQuestionId: String,
    @SerialName("content_revision") val contentRevision: String = "",
    @SerialName("verification_mode") val verificationMode: String = "CONCEPTUAL_TRANSFER",
    @SerialName("verification_timing") val verificationTiming: String = "PRE_FEEDBACK",
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("was_previously_exposed") val wasPreviouslyExposed: Boolean = false,
    @SerialName("counts_as_independent_transfer") val countsAsIndependentTransfer: Boolean = false,
    @SerialName("classification_code") val classificationCode: String = "",
    @SerialName("trigger_reason") val triggerReason: String? = null,
    @SerialName("trigger_cohort") val triggerCohort: String = "DIFFICULTY_DETECTED",
    @SerialName("target_concept_id") val targetConceptId: String? = null,
    @SerialName("target_tested_rule") val targetTestedRule: String? = null,
    @SerialName("occurred_at") val occurredAt: String = ""
)

@Serializable
data class CtvUserStateResponseDto(
    val offers: List<CtvReconciledOfferDto> = emptyList(),
    val attempts: List<CtvReconciledAttemptDto> = emptyList()
)

@Serializable
data class RecommendationFeedbackRequestDto(
    @SerialName("recommendation_id") val recommendationId: String,
    val outcome: String,
    @SerialName("priority_topic_id") val priorityTopicId: String? = null,
    @SerialName("decision_rule_code") val decisionRuleCode: String? = null,
    @SerialName("additional_seconds") val additionalSeconds: Int = 0,
    @SerialName("occurred_at") val occurredAt: String? = null
)

@Serializable
data class RecommendationFeedbackResponseDto(
    val status: String,
    @SerialName("recommendation_id") val recommendationId: String
)

@Serializable
data class FormalKpiDto(
    @SerialName("kpi_id") val kpiId: String,
    @SerialName("label_pt") val labelPt: String,
    val value: Double? = null,
    val unit: String = "%",
    val numerator: Double = 0.0,
    val denominator: Double = 0.0,
    @SerialName("sample_size_n") val sampleSizeN: Int = 0,
    @SerialName("formal_definition") val formalDefinition: String = "",
    @SerialName("eligibility_criteria") val eligibilityCriteria: String = "",
    @SerialName("missing_data_rule") val missingDataRule: String = "",
    val granularity: String = "",
    @SerialName("reference_period") val referencePeriod: String = "",
    @SerialName("interpretive_limitation") val interpretiveLimitation: String = ""
)

@Serializable
data class ConfidenceLevelCalibrationDto(
    @SerialName("declared_confidence") val declaredConfidence: DeclaredConfidence,
    @SerialName("label_pt") val labelPt: String,
    val attempts: Int = 0,
    val correct: Int = 0,
    @SerialName("observed_accuracy_pct") val observedAccuracyPct: Double? = null,
    @SerialName("calibrated_posterior_mean") val calibratedPosteriorMean: Double = 0.5,
    @SerialName("credible_sd") val credibleSd: Double = 0.15,
    @SerialName("sufficiency_status") val sufficiencyStatus: String = "EMPTY",
    @SerialName("coverage_sentence_pt") val coverageSentencePt: String = ""
)

@Serializable
data class GroundingEvidenceSummaryDto(
    @SerialName("raw_correct_hits") val rawCorrectHits: Int = 0,
    @SerialName("evaluable_grounding_hits") val evaluableGroundingHits: Int = 0,
    @SerialName("unknown_grounding_hits") val unknownGroundingHits: Int = 0,
    @SerialName("valid_conceptual_hits") val validConceptualHits: Int = 0,
    @SerialName("valid_functional_strategy_hits") val validFunctionalStrategyHits: Int = 0,
    @SerialName("divergent_rationale_hits") val divergentRationaleHits: Int = 0,
    @SerialName("fragile_or_guess_hits") val fragileOrGuessHits: Int = 0,
    @SerialName("coverage_sentence_pt") val coverageSentencePt: String = ""
)

@Serializable
data class CtvAdaptiveCohortDto(
    @SerialName("cohort_id") val cohortId: String = "",
    @SerialName("label_pt") val labelPt: String = "",
    @SerialName("eligibility_scope_pt") val eligibilityScopePt: String = "",
    @SerialName("offers_count") val offersCount: Int = 0,
    @SerialName("dismissals_count") val dismissalsCount: Int = 0,
    @SerialName("responses_count") val responsesCount: Int = 0,
    @SerialName("independent_eligible_responses") val independentEligibleResponses: Int = 0,
    @SerialName("independent_correct_count") val independentCorrectCount: Int = 0,
    @SerialName("independent_success_rate_pct") val independentSuccessRatePct: Double? = null,
    @SerialName("summary_pt") val summaryPt: String = ""
)

@Serializable
data class CtvAdaptiveSelectionReportDto(
    @SerialName("difficulty_triggered") val difficultyTriggered: CtvAdaptiveCohortDto = CtvAdaptiveCohortDto(
        cohortId = "DIFFICULTY_DETECTED",
        labelPt = "Disparo adaptativo por dificuldade ou fragilidade"
    ),
    @SerialName("periodic_sampling") val periodicSampling: CtvAdaptiveCohortDto = CtvAdaptiveCohortDto(
        cohortId = "PERIODIC_SAMPLING",
        labelPt = "Amostragem periódica de acompanhamento"
    ),
    @SerialName("unknown_condition_count") val unknownConditionCount: Int = 0
)

@Serializable
data class ConceptMasterySummaryDto(
    @SerialName("not_evaluated") val notEvaluated: Int = 0,
    @SerialName("insufficient_evidence") val insufficientEvidence: Int = 0,
    @SerialName("emerging_knowledge") val emergingKnowledge: Int = 0,
    @SerialName("unstable_knowledge") val unstableKnowledge: Int = 0,
    @SerialName("developing_mastery") val developingMastery: Int = 0,
    @SerialName("demonstrated_mastery") val demonstratedMastery: Int = 0,
    @SerialName("consolidated_mastery") val consolidatedMastery: Int = 0,
    @SerialName("mastery_at_risk") val masteryAtRisk: Int = 0
)

@Serializable
data class UnitConceptMasteryDto(
    @SerialName("unit_id") val unitId: String,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String,
    @SerialName("canonical_topic_id") val canonicalTopicId: String,
    @SerialName("concept_id") val conceptId: String? = null,
    val attempts: Int = 0,
    @SerialName("distinct_questions") val distinctQuestions: Int = 0,
    @SerialName("transfer_attempts") val transferAttempts: Int = 0,
    val evaluation: ConceptMasteryEvaluation
)

@Serializable
data class StudentLearningAnalyticsDto(
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("calibration_policy_version") val calibrationPolicyVersion: String = CalibrationEngine.CALIBRATION_POLICY_VERSION,
    @SerialName("learning_kpis") val learningKpis: List<FormalKpiDto> = emptyList(),
    @SerialName("empirical_brier_score") val empiricalBrierScore: Double? = null,
    @SerialName("true_false_brier_score") val trueFalseBrierScore: Double? = null,
    @SerialName("multiple_choice_brier_score") val multipleChoiceBrierScore: Double? = null,
    @SerialName("cold_start_predictions_count") val coldStartPredictionsCount: Int = 0,
    @SerialName("confidence_calibration") val confidenceCalibration: List<ConfidenceLevelCalibrationDto> = emptyList(),
    @SerialName("confidence_calibration_by_format") val confidenceCalibrationByFormat: List<FormatConfidenceCalibrationCellDto> = emptyList(),
    @SerialName("grounding_summary") val groundingSummary: GroundingEvidenceSummaryDto = GroundingEvidenceSummaryDto(),
    @SerialName("ctv_adaptive_selection") val ctvAdaptiveSelection: CtvAdaptiveSelectionReportDto = CtvAdaptiveSelectionReportDto(),
    @SerialName("concept_mastery_summary") val conceptMasterySummary: ConceptMasterySummaryDto = ConceptMasterySummaryDto(),
    @SerialName("unit_mastery") val unitMastery: List<UnitConceptMasteryDto> = emptyList()
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
data class RejectedAttemptDto(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("question_id") val questionId: String,
    val message: String,
    val retryable: Boolean
)
