package br.com.concursoprep.app.domain.exam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun finiteOr(value: Double, fallback: Double): Double =
    if (value.isFinite()) value else fallback

private fun rustRound(value: Double): Double {
    if (!value.isFinite()) return 0.0
    return if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)
}

private fun round6(value: Double): Double =
    rustRound(value * 1_000_000.0) / 1_000_000.0

private fun round9(value: Double): Double =
    rustRound(value * 1_000_000_000.0) / 1_000_000_000.0

private fun round4(value: Double): Double =
    rustRound(value * 10_000.0) / 10_000.0

private fun round2(value: Double): Double =
    rustRound(value * 100.0) / 100.0

@Serializable
enum class DiagnosticStatus {
    @SerialName("INSUFFICIENT_EVIDENCE") INSUFFICIENT_EVIDENCE,
    @SerialName("OVERCONFIDENCE_ERROR") OVERCONFIDENCE_ERROR,
    @SerialName("CONCEPTUAL_ERROR") CONCEPTUAL_ERROR,
    @SerialName("RETRIEVAL_FAILURE") RETRIEVAL_FAILURE,
    @SerialName("ILLUSORY_COMPETENCE") ILLUSORY_COMPETENCE,
    @SerialName("EVIDENCE_IN_PROGRESS") EVIDENCE_IN_PROGRESS,
    @SerialName("CONSOLIDATED_NO_TRANSFER") CONSOLIDATED_NO_TRANSFER,
    @SerialName("VERIFIED_COMPETENCE") VERIFIED_COMPETENCE;

    val isErrorIndication: Boolean
        get() = this == OVERCONFIDENCE_ERROR ||
            this == CONCEPTUAL_ERROR ||
            this == RETRIEVAL_FAILURE ||
            this == ILLUSORY_COMPETENCE
}

@Serializable
enum class TransferStatus {
    @SerialName("TRANSFER_UNMEASURED") TRANSFER_UNMEASURED,
    @SerialName("TRANSFER_AVAILABLE") TRANSFER_AVAILABLE,
    @SerialName("TRANSFER_PROVISIONAL") TRANSFER_PROVISIONAL,
    @SerialName("TRANSFER_FAILED") TRANSFER_FAILED,
    @SerialName("TRANSFER_VERIFIED") TRANSFER_VERIFIED
}

data class DiagnosticInputs(
    val distinctQuestions: Int,
    val nonBlankAttempts: Int,
    val correctAttempts: Int,
    val lastIsCorrect: Boolean,
    val lastConfidence: Int,
    val hadPriorVerifiedCorrect: Boolean,
    val hasSpacedRetention: Boolean,
    val evidenceChallengePassed: Boolean? = null,
    val hasIndependentTransfer: Boolean = false
)

data class TransferInputs(
    val distinctQuestions: Int,
    val availableCrossBoardQuestions: Int,
    val transferAttempts: Int,
    val transferCorrectGrounded: Int
)

object DiagnosticEngine {
    const val MIN_QUESTIONS_FOR_MASTERY = 15

    fun resolveUnitId(conceptId: String?, topicId: String): String {
        val cleaned = conceptId?.trim()?.takeIf { it.isNotEmpty() }
        return cleaned ?: topicId.trim()
    }

    /**
     * Classifica o estado diagnóstico cruzando os três sinais (resposta, confiança declarada e
     * evidência selecionada) sob a restrição explícita de escassez (`< 15` questões humanas):
     * com menos de 15 questões distintas, identifica indícios de erro (incluindo `ILLUSORY_COMPETENCE`
     * quando acerta por evidência incorreta), mas jamais afirma domínio ou transferência sem
     * evidência independente (`>= 15` questões + transferência verificada).
     */
    fun classifyDiagnostic(input: DiagnosticInputs): DiagnosticStatus {
        if (input.nonBlankAttempts <= 0) {
            return DiagnosticStatus.INSUFFICIENT_EVIDENCE
        }
        if (!input.lastIsCorrect) {
            if (input.lastConfidence >= 3 || (input.lastConfidence >= 2 && input.evidenceChallengePassed == false)) {
                return DiagnosticStatus.OVERCONFIDENCE_ERROR
            }
            if (input.evidenceChallengePassed == false || !input.hadPriorVerifiedCorrect) {
                return DiagnosticStatus.CONCEPTUAL_ERROR
            }
            return DiagnosticStatus.RETRIEVAL_FAILURE
        }

        if (input.evidenceChallengePassed == false) {
            return DiagnosticStatus.ILLUSORY_COMPETENCE
        }

        val acc = input.correctAttempts.toDouble() / input.nonBlankAttempts.toDouble()
        if (!input.hasSpacedRetention || acc < 0.80 - 1e-9 || input.lastConfidence < 2) {
            return DiagnosticStatus.INSUFFICIENT_EVIDENCE
        }

        if (input.distinctQuestions < MIN_QUESTIONS_FOR_MASTERY) {
            return DiagnosticStatus.EVIDENCE_IN_PROGRESS
        }

        return if (input.hasIndependentTransfer) {
            DiagnosticStatus.VERIFIED_COMPETENCE
        } else {
            DiagnosticStatus.CONSOLIDATED_NO_TRANSFER
        }
    }

    /**
     * Avalia transferência entre bancas sobre conjunto separado de questões humanas inéditas.
     * Se não houver questão válida de outra banca, registra `TRANSFER_UNMEASURED`.
     */
    fun evaluateTransferStatus(input: TransferInputs): TransferStatus {
        val avail = max(0, input.availableCrossBoardQuestions)
        val att = max(0, input.transferAttempts)
        if (avail == 0 && att == 0) {
            return TransferStatus.TRANSFER_UNMEASURED
        }
        if (att == 0) {
            return TransferStatus.TRANSFER_AVAILABLE
        }
        val safeGrounded = input.transferCorrectGrounded.coerceIn(0, att)
        val ratio = safeGrounded.toDouble() / att.toDouble()
        if (safeGrounded == 0 || ratio < 0.50 - 1e-9) {
            return TransferStatus.TRANSFER_FAILED
        }
        return if (input.distinctQuestions >= MIN_QUESTIONS_FOR_MASTERY && ratio >= 0.75 - 1e-9) {
            TransferStatus.TRANSFER_VERIFIED
        } else {
            TransferStatus.TRANSFER_PROVISIONAL
        }
    }
}

@Serializable
data class RetentionUpdate(
    @SerialName("next_stability") val nextStability: Double,
    @SerialName("next_difficulty") val nextDifficulty: Double,
    @SerialName("next_interval_days") val nextIntervalDays: Int
)

object RetentionEngine {
    fun retrievability(elapsedDays: Double, stabilityDays: Double): Double {
        if (stabilityDays <= 0.0 || !stabilityDays.isFinite() || !elapsedDays.isFinite()) return 0.0
        val t = max(0.0, elapsedDays)
        return (1.0 + (19.0 / 81.0) * (t / stabilityDays)).pow(-0.5).coerceIn(0.0, 1.0)
    }

    fun updateRetention(
        priorStability: Double,
        priorDifficulty: Double,
        isCorrect: Boolean,
        confidence: Int,
        elapsedDays: Double,
        responseSeconds: Int,
        expectedSeconds: Double
    ): RetentionUpdate {
        val safePriorS = finiteOr(priorStability, 0.0)
        val safePriorD = finiteOr(priorDifficulty, 5.0)
        val safeElapsed = max(0.0, finiteOr(elapsedDays, 0.0))
        val safeExpected = max(0.0, finiteOr(expectedSeconds, 0.0))
        val d = if (safePriorS <= 0.0) 5.0 else safePriorD.coerceIn(1.0, 10.0)
        val s = max(0.0, safePriorS)

        val (sNext, dNext) = if (!isCorrect) {
            val deltaD = if (confidence >= 3) 1.5 else 1.0
            val dn = (d + deltaD).coerceIn(1.0, 10.0)
            val sn = if (s <= 0.0) 0.5 else (s * 0.25).coerceIn(0.5, 2.0)
            sn to dn
        } else if (confidence <= 1) {
            val dn = (d + 0.2).coerceIn(1.0, 10.0)
            val sn = s.coerceIn(1.0, 1.5)
            sn to dn
        } else {
            val slow = safeExpected > 0.0 && responseSeconds.toDouble() > 1.5 * safeExpected
            val g = if (confidence >= 3 && !slow) 1.0 else 0.6
            val dn = (d - 0.3 * g + 0.15 * (1.0 - g)).coerceIn(1.0, 10.0)
            val sn = if (s <= 0.0) {
                if (g >= 1.0) 4.0 else 2.0
            } else {
                val r = retrievability(safeElapsed, s)
                val mult = 1.0 + g * ((11.0 - dn) / 9.0) * (1.2 + 1.8 * (1.0 - r))
                (s * mult).coerceIn(1.0, 180.0)
            }
            sn to dn
        }

        val sRounded = round6(sNext)
        val dRounded = round6(dNext)
        val intervalDays = rustRound(sRounded).coerceIn(1.0, 180.0).toInt()
        return RetentionUpdate(
            nextStability = sRounded,
            nextDifficulty = dRounded,
            nextIntervalDays = intervalDays
        )
    }
}

data class PrioritizationInputs(
    val editalWeight: Double,
    val incidenceWeight: Double,
    val retrievability: Double,
    val verifiedCompetence: Boolean,
    val distinctQuestions: Int,
    val avgSeconds: Double,
    val unmetPrerequisitesCount: Int,
    val dependentCount: Int
)

@Serializable
data class MetacognitivePrioritySignals(
    @SerialName("high_confidence_error_rate") val highConfidenceErrorRate: Double = 0.0,
    @SerialName("high_confidence_error_count") val highConfidenceErrorCount: Int = 0,
    @SerialName("incompatible_rationale_count") val incompatibleRationaleCount: Int = 0,
    @SerialName("fragile_correct_rate") val fragileCorrectRate: Double = 0.0,
    @SerialName("failed_transfer") val failedTransfer: Boolean = false,
    @SerialName("functional_elimination_transfer_verified") val functionalEliminationTransferVerified: Boolean = false,
    @SerialName("post_explanation_fixation_pending_spacing") val postExplanationFixationPendingSpacing: Boolean = false,
    @SerialName("post_explanation_spaced_due") val postExplanationSpacedDue: Boolean = false,
    @SerialName("scarce_or_conflicting_evidence") val scarceOrConflictingEvidence: Boolean = false,
    @SerialName("recent_consecutive_exposures") val recentConsecutiveExposures: Int = 0
) {
    fun hasActiveSignals(): Boolean =
        highConfidenceErrorRate > 0.0 ||
            highConfidenceErrorCount > 0 ||
            incompatibleRationaleCount > 0 ||
            fragileCorrectRate > 0.0 ||
            failedTransfer ||
            functionalEliminationTransferVerified ||
            postExplanationFixationPendingSpacing ||
            postExplanationSpacedDue ||
            scarceOrConflictingEvidence ||
            recentConsecutiveExposures >= 3
}

data class MetacognitiveDecisionReason(
    val decisionRuleCode: String,
    val reasonPt: String,
    val recommendedMode: String
)

enum class AdaptiveSlotKind {
    RETENTION_REVIEW,
    PERSISTENT_GAP,
    NEW_HIGH_IMPACT
}

object PrioritizationEngine {
    private val studyZone: ZoneOffset = ZoneOffset.ofHours(-3)

    fun formatEntry60sHeader(at: Instant = Instant.now()): String {
        val local = at.atOffset(studyZone)
        val day = when (local.dayOfWeek) {
            DayOfWeek.MONDAY -> "SEGUNDA"
            DayOfWeek.TUESDAY -> "TERÇA"
            DayOfWeek.WEDNESDAY -> "QUARTA"
            DayOfWeek.THURSDAY -> "QUINTA"
            DayOfWeek.FRIDAY -> "SEXTA"
            DayOfWeek.SATURDAY -> "SÁBADO"
            DayOfWeek.SUNDAY -> "DOMINGO"
        }
        return "%s · %02dH".format(day, local.hour)
    }

    fun entry60sQuestionScore(
        stemCharLen: Int,
        isTrueFalse: Boolean,
        isFamiliarTopic: Boolean,
        hasPendingRetention: Boolean,
        recentlyAnswered: Boolean
    ): Double {
        var score = 0.0
        if (isFamiliarTopic && hasPendingRetention) {
            score += 50.0
        } else if (isFamiliarTopic) {
            score += 25.0
        } else if (hasPendingRetention) {
            score += 20.0
        }
        if (isTrueFalse) {
            score += 18.0
        }
        if (stemCharLen <= 260) {
            score += 22.0
        } else if (stemCharLen <= 420) {
            score += 12.0
        } else if (stemCharLen > 750) {
            score -= 20.0
        }
        if (recentlyAnswered) {
            score -= 35.0
        }
        return score
    }

    fun priorityUtility(input: PrioritizationInputs): Double {
        val r = finiteOr(input.retrievability, 0.0).coerceIn(0.0, 1.0)
        val evFactor = min(15, max(0, input.distinctQuestions)).toDouble() / 15.0
        val mEff = if (input.verifiedCompetence) {
            0.85 * r + 0.15
        } else {
            0.65 * r * evFactor
        }
        val gap = (1.0 - mEff).coerceIn(0.05, 1.0)
        val tauMin = finiteOr(input.avgSeconds, 60.0).coerceIn(20.0, 300.0) / 60.0
        val prereqMult = if (input.unmetPrerequisitesCount <= 0) {
            1.0
        } else {
            0.55.pow(min(3, input.unmetPrerequisitesCount))
        }
        val downstreamBoost = 1.0 + 0.15 * min(4, max(0, input.dependentCount)).toDouble()
        val ew = max(0.0, finiteOr(input.editalWeight, 0.05))
        val iw = max(0.0, finiteOr(input.incidenceWeight, 1.0))
        val raw = (ew * iw * gap / tauMin) * prereqMult * downstreamBoost
        return round9(raw)
    }

    fun priorityUtilityMetacognitive(
        base: PrioritizationInputs,
        signals: MetacognitivePrioritySignals
    ): Double {
        val baseU = priorityUtility(base)
        if (!signals.hasActiveSignals()) return baseU

        val recurrentBonus = when {
            signals.highConfidenceErrorCount >= 2 && signals.incompatibleRationaleCount >= 2 -> 0.25
            signals.highConfidenceErrorCount >= 2 -> 0.12
            else -> 0.0
        }
        val hceBoost = 1.0 + 0.45 * finiteOr(signals.highConfidenceErrorRate, 0.0).coerceIn(0.0, 1.0) + recurrentBonus
        val fragileBoost = 1.0 + 0.25 * finiteOr(signals.fragileCorrectRate, 0.0).coerceIn(0.0, 1.0)
        val transferBoost = if (signals.failedTransfer) 1.30 else 1.0
        val conflictBoost = if (signals.scarceOrConflictingEvidence) 1.18 else 1.0
        val spacedPostExpBoost = if (signals.postExplanationSpacedDue) 1.22 else 1.0
        val functionalAdvanceMult = if (signals.functionalEliminationTransferVerified) 0.42 else 1.0
        val postExpDeferMult = if (signals.postExplanationFixationPendingSpacing) 0.48 else 1.0
        val antiTrapping = if (signals.recentConsecutiveExposures >= 3) {
            0.55.pow(min(3, signals.recentConsecutiveExposures - 2))
        } else {
            1.0
        }
        val adjusted = baseU *
            hceBoost *
            fragileBoost *
            transferBoost *
            conflictBoost *
            spacedPostExpBoost *
            functionalAdvanceMult *
            postExpDeferMult *
            antiTrapping
        return round9(adjusted)
    }

    fun buildMetacognitiveDecisionReason(
        signals: MetacognitivePrioritySignals,
        unitTitle: String,
        fallbackReasonPt: String,
        advancedAfterFunctionalTransfer: Boolean = false
    ): MetacognitiveDecisionReason {
        if (signals.highConfidenceErrorCount >= 2 ||
            (signals.highConfidenceErrorCount >= 1 && signals.incompatibleRationaleCount >= 1)
        ) {
            val n = maxOf(signals.highConfidenceErrorCount, signals.incompatibleRationaleCount, 2)
            val msg = if (signals.incompatibleRationaleCount >= 2) {
                "Revisar $unitTitle. Em $n questões houve erro com confiança alta e fundamento incompatível."
            } else {
                "Revisar $unitTitle. Em $n questões houve erro recorrente com confiança alta."
            }
            return MetacognitiveDecisionReason("RECURRENT_HIGH_CONF_MISCONCEPTION", msg, "ADAPTIVE_LOOP")
        }
        if (signals.postExplanationSpacedDue || signals.postExplanationFixationPendingSpacing) {
            return MetacognitiveDecisionReason(
                "POST_EXPLANATION_SPACED_FOLLOWUP",
                "Você acertou após a explicação. Vamos retomar $unitTitle em outra sessão.",
                "TRANSFER_PROBE"
            )
        }
        if (signals.failedTransfer || signals.scarceOrConflictingEvidence || signals.fragileCorrectRate >= 0.40) {
            return MetacognitiveDecisionReason(
                "SCARCE_OR_CONFLICTING_EVIDENCE",
                "Falta observar como você aplica esta regra em outro exemplo: $unitTitle.",
                "TRANSFER_PROBE"
            )
        }
        if (advancedAfterFunctionalTransfer) {
            return MetacognitiveDecisionReason(
                "ADVANCE_AFTER_FUNCTIONAL_TRANSFER",
                "Você aplicou a regra em outro contexto. Sua próxima atividade avança para $unitTitle.",
                "ADAPTIVE_LOOP"
            )
        }
        return MetacognitiveDecisionReason("BASELINE_REFERENCE", fallbackReasonPt, "ENTRY_60S")
    }

    fun <T> interleaveBuckets(
        retentionDue: List<T>,
        persistentGaps: List<T>,
        newHighImpact: List<T>,
        quantity: Int,
        idOf: (T) -> String
    ): List<T> {
        if (quantity <= 0) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<T>(quantity)
        val pattern = listOf(
            AdaptiveSlotKind.RETENTION_REVIEW,
            AdaptiveSlotKind.PERSISTENT_GAP,
            AdaptiveSlotKind.RETENTION_REVIEW,
            AdaptiveSlotKind.PERSISTENT_GAP,
            AdaptiveSlotKind.NEW_HIGH_IMPACT
        )
        var step = 0

        fun nextUnseen(list: List<T>, cursorRef: IntArray): T? {
            while (cursorRef[0] < list.size) {
                val candidate = list[cursorRef[0]]
                cursorRef[0] += 1
                if (idOf(candidate) !in seen) return candidate
            }
            return null
        }

        val cRet = intArrayOf(0)
        val cGap = intArrayOf(0)
        val cNew = intArrayOf(0)

        while (out.size < quantity && (cRet[0] < retentionDue.size || cGap[0] < persistentGaps.size || cNew[0] < newHighImpact.size)) {
            val slot = pattern[step % pattern.size]
            step += 1
            val picked = when (slot) {
                AdaptiveSlotKind.RETENTION_REVIEW -> nextUnseen(retentionDue, cRet)
                AdaptiveSlotKind.PERSISTENT_GAP -> nextUnseen(persistentGaps, cGap)
                AdaptiveSlotKind.NEW_HIGH_IMPACT -> nextUnseen(newHighImpact, cNew)
            } ?: nextUnseen(retentionDue, cRet)
                ?: nextUnseen(persistentGaps, cGap)
                ?: nextUnseen(newHighImpact, cNew)

            if (picked != null) {
                seen += idOf(picked)
                out += picked
            } else {
                break
            }
        }
        return out
    }
}

@Serializable
data class CalibrationCellDto(
    @SerialName("question_type") val questionType: String,
    @SerialName("confidence_band") val confidenceBand: Int,
    val correct: Int,
    val attempts: Int,
    @SerialName("calibrated_mean") val calibratedMean: Double,
    @SerialName("credible_sd") val credibleSd: Double,
    @SerialName("sufficient_sample") val sufficientSample: Boolean
)

@Serializable
data class WalkforwardCalibrationInput(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("occurred_at_ms") val occurredAtMs: Long,
    @SerialName("question_type") val questionType: String,
    @SerialName("declared_confidence") val declaredConfidence: DeclaredConfidence,
    @SerialName("is_correct") val isCorrect: Boolean
)

@Serializable
data class WalkforwardStepTrace(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("occurred_at_ms") val occurredAtMs: Long,
    @SerialName("question_type") val questionType: String,
    @SerialName("declared_confidence") val declaredConfidence: DeclaredConfidence,
    @SerialName("predicted_probability") val predictedProbability: Double,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("squared_error") val squaredError: Double,
    @SerialName("used_cold_start_prior") val usedColdStartPrior: Boolean
)

@Serializable
data class FormatConfidenceCalibrationCellDto(
    @SerialName("question_type") val questionType: String,
    @SerialName("declared_confidence") val declaredConfidence: DeclaredConfidence,
    @SerialName("label_pt") val labelPt: String,
    val attempts: Int = 0,
    val correct: Int = 0,
    @SerialName("observed_accuracy_pct") val observedAccuracyPct: Double? = null,
    @SerialName("model_predicted_mean") val modelPredictedMean: Double = 0.5,
    @SerialName("credible_sd") val credibleSd: Double = 0.15,
    @SerialName("sufficiency_status") val sufficiencyStatus: String = "EMPTY",
    @SerialName("coverage_sentence_pt") val coverageSentencePt: String = ""
)

data class WalkforwardCalibrationReport(
    val policyVersion: String,
    val overallBrierScore: Double?,
    val overallBrierRaw: Double?,
    val trueFalseBrierScore: Double?,
    val multipleChoiceBrierScore: Double?,
    val eligibleAttemptsCount: Int,
    val coldStartPredictionsCount: Int,
    val sufficiencyStatus: String,
    val legacyBandMapping: Map<String, String>,
    val cellsByFormat: List<FormatConfidenceCalibrationCellDto>,
    val traces: List<WalkforwardStepTrace>
)

object CalibrationEngine {
    const val CALIBRATION_POLICY_VERSION = "calibration-walkforward-v2.0"

    fun normalizeCalibrationFormat(raw: String): String =
        if (raw.trim().equals("TRUE_FALSE", ignoreCase = true)) "TRUE_FALSE" else "MULTIPLE_CHOICE"

    fun legacyBandMappingTable(): Map<String, String> = mapOf(
        "0_explicit" to "GUESS",
        "1_explicit" to "GUESS",
        "2_explicit" to "MEDIUM",
        "3_explicit" to "HIGH",
        "unconfirmed_default" to "UNDECLARED_EXCLUDED"
    )

    fun levelPriorMean(questionType: String, confidence: DeclaredConfidence): Double {
        val isTf = normalizeCalibrationFormat(questionType) == "TRUE_FALSE"
        return when (confidence) {
            DeclaredConfidence.GUESS -> if (isTf) 0.50 else 0.20
            DeclaredConfidence.LOW -> if (isTf) 0.56 else 0.32
            DeclaredConfidence.MEDIUM -> if (isTf) 0.65 else 0.45
            DeclaredConfidence.HIGH -> 0.85
            DeclaredConfidence.VERY_HIGH -> 0.92
        }
    }

    fun calibratedLevelPosterior(
        questionType: String,
        confidence: DeclaredConfidence,
        correct: Int,
        attempts: Int
    ): Pair<Double, Double> {
        val mu0 = levelPriorMean(questionType, confidence)
        val n0 = 6.0
        val alpha0 = mu0 * n0
        val beta0 = (1.0 - mu0) * n0
        val safeAttempts = max(0, attempts)
        val safeCorrect = correct.coerceIn(0, safeAttempts)
        val a = alpha0 + safeCorrect.toDouble()
        val b = beta0 + (safeAttempts - safeCorrect).toDouble()
        val total = a + b
        val mean = round9(a / total)
        val sd = round9(sqrt((a * b) / ((total * total) * (total + 1.0))))
        return mean to sd
    }

    fun calibratedProbability(
        questionType: String,
        confidenceBand: Int,
        correct: Int,
        attempts: Int
    ): CalibrationCellDto {
        val fmt = normalizeCalibrationFormat(questionType)
        val mapped = when (confidenceBand.coerceIn(1, 3)) {
            1 -> DeclaredConfidence.GUESS
            2 -> DeclaredConfidence.MEDIUM
            else -> DeclaredConfidence.HIGH
        }
        val safeAttempts = max(0, attempts)
        val safeCorrect = correct.coerceIn(0, safeAttempts)
        val (mean, sd) = calibratedLevelPosterior(fmt, mapped, safeCorrect, safeAttempts)
        return CalibrationCellDto(
            questionType = fmt,
            confidenceBand = confidenceBand.coerceIn(1, 3),
            correct = safeCorrect,
            attempts = safeAttempts,
            calibratedMean = mean,
            credibleSd = sd,
            sufficientSample = safeAttempts >= 10
        )
    }

    fun computeBrierScore(predictionsAndOutcomes: List<Pair<Double, Boolean>>): Double? {
        if (predictionsAndOutcomes.isEmpty()) return null
        val sumSq = predictionsAndOutcomes.sumOf { (p, ok) ->
            val pClamped = finiteOr(p, 0.5).coerceIn(0.0, 1.0)
            val y = if (ok) 1.0 else 0.0
            val d = pClamped - y
            d * d
        }
        return round9(sumSq / predictionsAndOutcomes.size.toDouble())
    }

    fun sufficiencyStatusForSample(n: Int, minSufficient: Int = 5): String = when {
        n <= 0 -> "EMPTY"
        n < minSufficient -> "INITIAL_EVIDENCE"
        else -> "SUFFICIENT"
    }

    fun formatConfidenceCoverageSentence(
        confidence: DeclaredConfidence,
        questionType: String?,
        correct: Int,
        attempts: Int
    ): String {
        val confPhrase = when (confidence) {
            DeclaredConfidence.GUESS -> "chute assumido"
            DeclaredConfidence.LOW -> "confiança baixa"
            DeclaredConfidence.MEDIUM -> "confiança média"
            DeclaredConfidence.HIGH -> "confiança alta"
            DeclaredConfidence.VERY_HIGH -> "certeza elevada"
        }
        val fmtPhrase = when (questionType) {
            "TRUE_FALSE" -> " de Certo/Errado"
            "MULTIPLE_CHOICE" -> " de múltipla escolha"
            else -> ""
        }
        if (attempts <= 0) {
            return "Ainda não há questões$fmtPhrase respondidas em que você declarou $confPhrase."
        }
        val suffix = if (attempts < 5) " (evidência inicial)" else ""
        return "Você acertou $correct de $attempts questões$fmtPhrase em que declarou $confPhrase$suffix."
    }

    fun evaluateWalkforwardCalibration(
        events: List<WalkforwardCalibrationInput>
    ): WalkforwardCalibrationReport {
        val sorted = events.sortedWith(
            compareBy<WalkforwardCalibrationInput> { it.occurredAtMs }.thenBy { it.attemptId }
        )
        val runningCells = HashMap<Pair<String, DeclaredConfidence>, Pair<Int, Int>>()
        val allPairs = ArrayList<Pair<Double, Boolean>>(sorted.size)
        val tfPairs = ArrayList<Pair<Double, Boolean>>()
        val mcPairs = ArrayList<Pair<Double, Boolean>>()
        val traces = ArrayList<WalkforwardStepTrace>(sorted.size)
        var coldStartCount = 0

        for (ev in sorted) {
            val fmt = normalizeCalibrationFormat(ev.questionType)
            val (priorCorr, priorAtt) = runningCells[fmt to ev.declaredConfidence] ?: (0 to 0)
            val isCold = priorAtt == 0
            if (isCold) coldStartCount++
            val (pPred, _) = calibratedLevelPosterior(fmt, ev.declaredConfidence, priorCorr, priorAtt)
            val y = if (ev.isCorrect) 1.0 else 0.0
            val sqErr = round9((pPred - y) * (pPred - y))

            allPairs += (pPred to ev.isCorrect)
            if (fmt == "TRUE_FALSE") {
                tfPairs += (pPred to ev.isCorrect)
            } else {
                mcPairs += (pPred to ev.isCorrect)
            }
            traces += WalkforwardStepTrace(
                attemptId = ev.attemptId,
                occurredAtMs = ev.occurredAtMs,
                questionType = fmt,
                declaredConfidence = ev.declaredConfidence,
                predictedProbability = pPred,
                isCorrect = ev.isCorrect,
                squaredError = sqErr,
                usedColdStartPrior = isCold
            )
            runningCells[fmt to ev.declaredConfidence] =
                (priorCorr + if (ev.isCorrect) 1 else 0) to (priorAtt + 1)
        }

        val cellsByFormat = ArrayList<FormatConfidenceCalibrationCellDto>(10)
        for (fmt in listOf("TRUE_FALSE", "MULTIPLE_CHOICE")) {
            for (dc in DeclaredConfidence.entries) {
                val (corr, att) = runningCells[fmt to dc] ?: (0 to 0)
                val (postMean, sd) = calibratedLevelPosterior(fmt, dc, corr, att)
                val obsPct = if (att > 0) round2(100.0 * corr.toDouble() / att.toDouble()) else null
                cellsByFormat += FormatConfidenceCalibrationCellDto(
                    questionType = fmt,
                    declaredConfidence = dc,
                    labelPt = dc.labelPt,
                    attempts = att,
                    correct = corr,
                    observedAccuracyPct = obsPct,
                    modelPredictedMean = round4(postMean),
                    credibleSd = round4(sd),
                    sufficiencyStatus = sufficiencyStatusForSample(att, 5),
                    coverageSentencePt = formatConfidenceCoverageSentence(dc, fmt, corr, att)
                )
            }
        }

        val rawBrier = computeBrierScore(allPairs)
        return WalkforwardCalibrationReport(
            policyVersion = CALIBRATION_POLICY_VERSION,
            overallBrierScore = rawBrier?.let(::round4),
            overallBrierRaw = rawBrier,
            trueFalseBrierScore = computeBrierScore(tfPairs)?.let(::round4),
            multipleChoiceBrierScore = computeBrierScore(mcPairs)?.let(::round4),
            eligibleAttemptsCount = allPairs.size,
            coldStartPredictionsCount = coldStartCount,
            sufficiencyStatus = sufficiencyStatusForSample(allPairs.size, 5),
            legacyBandMapping = legacyBandMappingTable(),
            cellsByFormat = cellsByFormat,
            traces = traces
        )
    }
}

@Serializable
enum class StrategyPosture {
    @SerialName("MAXIMIZE_EV") MAXIMIZE_EV,
    @SerialName("PROTECT_MARGIN") PROTECT_MARGIN,
    @SerialName("CALCULATED_RISK") CALCULATED_RISK
}

@Serializable
enum class StrategyAction {
    @SerialName("ANSWER") ANSWER,
    @SerialName("OMIT") OMIT
}

@Serializable
data class StrategyDecision(
    @SerialName("expected_value") val expectedValue: Double,
    @SerialName("risk_neutral_threshold") val riskNeutralThreshold: Double,
    @SerialName("effective_threshold") val effectiveThreshold: Double,
    val posture: StrategyPosture,
    @SerialName("recommended_action") val recommendedAction: StrategyAction
)

@Serializable
data class UncertaintyDecisionInputs(
    @SerialName("p_base") val pBase: Double,
    @SerialName("uncertainty_sd") val uncertaintySd: Double,
    val penalty: Double,
    @SerialName("item_difficulty") val itemDifficulty: Double,
    @SerialName("lambda_i") val lambdaI: Double,
    @SerialName("elapsed_minutes") val elapsedMinutes: Double,
    @SerialName("current_net_score") val currentNetScore: Double,
    @SerialName("remaining_items") val remainingItems: Int,
    @SerialName("target_score") val targetScore: Double,
    @SerialName("minimum_score") val minimumScore: Double,
    @SerialName("baseline_future_p") val baselineFutureP: Double
)

@Serializable
data class UncertaintyDecisionResult(
    val p0: Double,
    @SerialName("p_at_time") val pAtTime: Double,
    @SerialName("expected_value") val expectedValue: Double,
    @SerialName("risk_neutral_threshold") val riskNeutralThreshold: Double,
    @SerialName("prob_goal_if_answer") val probGoalIfAnswer: Double,
    @SerialName("prob_goal_if_omit") val probGoalIfOmit: Double,
    val posture: StrategyPosture,
    @SerialName("recommended_action") val recommendedAction: StrategyAction
)

@Serializable
data class ExamMomentScenarioDto(
    @SerialName("scenario_id") val scenarioId: String,
    val label: String,
    @SerialName("elapsed_minutes") val elapsedMinutes: Int,
    @SerialName("current_net_score") val currentNetScore: Double,
    @SerialName("remaining_items") val remainingItems: Int,
    @SerialName("target_score") val targetScore: Double,
    @SerialName("minimum_score") val minimumScore: Double,
    @SerialName("calibrated_longitudinal") val calibratedLongitudinal: Boolean,
    @SerialName("decision_at_medium_confidence") val decisionAtMediumConfidence: UncertaintyDecisionResult
)

object StrategyEngine {
    private val SQRT_2 = sqrt(2.0)

    /**
     * Complementaridade em questão estritamente binária (`Certo`/`Errado`):
     * se uma alternativa tem probabilidade estimada de 48%, a outra possui 52%.
     */
    fun normalizeBinaryChoiceProbability(pAlternative: Double): Double {
        val p = finiteOr(pAlternative, 0.5).coerceIn(0.0, 1.0)
        return round9(max(p, 1.0 - p))
    }

    /**
     * CDF da distribuição normal padrão via aproximação racional Abramowitz & Stegun 7.1.26,
     * idêntica bit a bit entre Python, Rust e Kotlin.
     */
    fun normalCdf(z: Double): Double {
        if (!z.isFinite()) return 0.5
        if (z <= -8.0) return 0.0
        if (z >= 8.0) return 1.0
        val sign = if (z < 0.0) -1.0 else 1.0
        val x = abs(z) / SQRT_2
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val a1 = 0.254829592
        val a2 = -0.284496736
        val a3 = 1.421413741
        val a4 = -1.453152027
        val a5 = 1.061405429
        val poly = (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t
        val erfApprox = sign * (1.0 - poly * exp(-x * x))
        return 0.5 * (1.0 + erfApprox)
    }

    fun isHoldoutReserved(userId: Long, questionId: String, poolSize: Int): Boolean {
        if (poolSize < 5) return false
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("cogniva-holdout-v1:$userId:$questionId".toByteArray(Charsets.UTF_8))
        val head = ((digest[0].toLong() and 0xFFL) shl 24) or
            ((digest[1].toLong() and 0xFFL) shl 16) or
            ((digest[2].toLong() and 0xFFL) shl 8) or
            (digest[3].toLong() and 0xFFL)
        return (head % 5L) == 0L
    }

    fun simulateDecisionUnderUncertainty(input: UncertaintyDecisionInputs): UncertaintyDecisionResult {
        val pen = if (input.penalty.isFinite()) max(0.0, input.penalty) else 0.0
        val (p0, pt) = FatigueEngine.performanceAtMinute(
            pBase = input.pBase,
            itemDifficulty = input.itemDifficulty,
            lambdaI = input.lambdaI,
            elapsedMinutes = input.elapsedMinutes
        )
        val ev = round9((1.0 + pen) * pt - pen)
        val pStar = if (pen > 0.0) round9(pen / (1.0 + pen)) else 0.0
        val rem = max(1, input.remainingItems)
        val curNet = finiteOr(input.currentNetScore, 0.0)
        val goal = max(finiteOr(input.targetScore, 0.0), finiteOr(input.minimumScore, 0.0))
        val sdU = finiteOr(input.uncertaintySd, 0.0).coerceIn(0.0, 0.35)

        val pf = finiteOr(input.baselineFutureP, 0.72).coerceIn(0.10, 0.95)
        val qf = if (pen > 0.0) 0.85 else 1.0
        val muItem = qf * ((1.0 + pen) * pf - pen)
        val eX2 = qf * (pf * 1.0 + (1.0 - pf) * pen * pen)
        val varItem = max(0.04, eX2 - muItem * muItem)

        fun continuationProb(startScore: Double, nLeft: Int): Double {
            if (nLeft <= 0) {
                return if (startScore >= goal - 1e-9) 1.0 else 0.0
            }
            val nl = nLeft.toDouble()
            val meanFinal = startScore + nl * muItem
            val epistemicTerm = nl * (1.0 + pen) * sdU * 0.35
            val epistemicVar = epistemicTerm * epistemicTerm
            val sdFinal = sqrt(nl * varItem + epistemicVar)
            return normalCdf((meanFinal - goal) / sdFinal)
        }

        val probOmit = continuationProb(curNet, rem - 1)
        val pMinus = (pt - sdU).coerceIn(0.01, 0.99)
        val pPlus = (pt + sdU).coerceIn(0.01, 0.99)
        val probHit = continuationProb(curNet + 1.0, rem - 1)
        val probMiss = continuationProb(curNet - pen, rem - 1)
        val probAns = 0.5 * (pMinus * probHit + (1.0 - pMinus) * probMiss) +
            0.5 * (pPlus * probHit + (1.0 - pPlus) * probMiss)

        val (recommendedAction, posture) = when {
            pen <= 0.0 -> StrategyAction.ANSWER to StrategyPosture.MAXIMIZE_EV
            probOmit > probAns + 0.002 -> {
                val pos = if (ev > 0.0) StrategyPosture.PROTECT_MARGIN else StrategyPosture.MAXIMIZE_EV
                StrategyAction.OMIT to pos
            }
            probAns > probOmit + 0.002 -> {
                val pos = if (ev < 0.0) StrategyPosture.CALCULATED_RISK else StrategyPosture.MAXIMIZE_EV
                StrategyAction.ANSWER to pos
            }
            ev > 0.0 -> StrategyAction.ANSWER to StrategyPosture.MAXIMIZE_EV
            else -> StrategyAction.OMIT to StrategyPosture.MAXIMIZE_EV
        }

        return UncertaintyDecisionResult(
            p0 = p0,
            pAtTime = pt,
            expectedValue = ev,
            riskNeutralThreshold = pStar,
            probGoalIfAnswer = round9(probAns),
            probGoalIfOmit = round9(probOmit),
            posture = posture,
            recommendedAction = recommendedAction
        )
    }

    fun buildExamMomentScenarios(
        mediumP: Double,
        mediumSd: Double,
        penalty: Double,
        cutoffPct: Double,
        lambdaI: Double?
    ): List<ExamMomentScenarioDto> {
        val totalItems = 120.0
        val target = when {
            cutoffPct > 0.0 -> (totalItems * cutoffPct / 100.0).coerceIn(30.0, 108.0)
            penalty > 0.0 -> 66.0
            else -> 84.0
        }
        val minFloor = rustRound(target * 0.75)
        val lam = lambdaI ?: 0.0
        val calibrated = lambdaI != null

        data class Spec(val id: String, val label: String, val mins: Int, val curScore: Double, val rem: Int)
        val specs = listOf(
            Spec("OPENING_35M", "Início de prova · 35 min (ritmo base)", 35, rustRound(target * 0.18), 98),
            Spec("MIDPOINT_165M", "Meio de prova · 165 min (maximização de VE)", 165, rustRound(target * 0.58), 52),
            Spec("PROTECT_275M", "Reta final · 275 min (protegendo margem acima do corte)", 275, target + 1.5, 4),
            Spec("RECOVERY_285M", "Reta final · 285 min (1 ponto abaixo da meta)", 285, target - 1.0, 3)
        )
        return specs.map { sp ->
            ExamMomentScenarioDto(
                scenarioId = sp.id,
                label = sp.label,
                elapsedMinutes = sp.mins,
                currentNetScore = sp.curScore,
                remainingItems = sp.rem,
                targetScore = target,
                minimumScore = minFloor,
                calibratedLongitudinal = calibrated,
                decisionAtMediumConfidence = simulateDecisionUnderUncertainty(
                    UncertaintyDecisionInputs(
                        pBase = mediumP,
                        uncertaintySd = mediumSd,
                        penalty = penalty,
                        itemDifficulty = 0.50,
                        lambdaI = lam,
                        elapsedMinutes = sp.mins.toDouble(),
                        currentNetScore = sp.curScore,
                        remainingItems = sp.rem,
                        targetScore = target,
                        minimumScore = minFloor,
                        baselineFutureP = 0.72
                    )
                )
            )
        }
    }

    fun evaluateStrategy(
        pCalibrated: Double,
        penalty: Double,
        cutoffPct: Double,
        currentProjectedPct: Double,
        remainingFraction: Double
    ): StrategyDecision {
        val p = finiteOr(pCalibrated, 0.5).coerceIn(0.0, 1.0)
        val lam = if (penalty.isFinite()) max(0.0, penalty) else 0.0
        val safeCutoff = finiteOr(cutoffPct, 0.0)
        val safeProj = finiteOr(currentProjectedPct, 0.0)
        val safeRem = finiteOr(remainingFraction, 1.0)
        val ev = round9((1.0 + lam) * p - lam)

        if (lam <= 0.0) {
            return StrategyDecision(
                expectedValue = ev,
                riskNeutralThreshold = 0.0,
                effectiveThreshold = 0.0,
                posture = StrategyPosture.MAXIMIZE_EV,
                recommendedAction = StrategyAction.ANSWER
            )
        }

        val pStar = round9(lam / (1.0 + lam))
        val (eff, posture) = if (safeCutoff > 0.0 && safeRem > 0.0 && safeRem <= 0.35) {
            val margin = safeProj - safeCutoff
            when {
                margin >= 6.0 -> round9(min(0.95, pStar + 0.05)) to StrategyPosture.PROTECT_MARGIN
                margin <= -6.0 -> round9(max(0.25, pStar - 0.04)) to StrategyPosture.CALCULATED_RISK
                else -> pStar to StrategyPosture.MAXIMIZE_EV
            }
        } else {
            pStar to StrategyPosture.MAXIMIZE_EV
        }

        val action = if (p > eff + 1e-9) StrategyAction.ANSWER else StrategyAction.OMIT
        return StrategyDecision(
            expectedValue = ev,
            riskNeutralThreshold = pStar,
            effectiveThreshold = eff,
            posture = posture,
            recommendedAction = action
        )
    }
}

@Serializable
enum class FatigueStatus {
    @SerialName("INSUFFICIENT_EVIDENCE") INSUFFICIENT_EVIDENCE,
    @SerialName("FATIGUE_DETECTED") FATIGUE_DETECTED,
    @SerialName("STABLE_ENDURANCE") STABLE_ENDURANCE,
    @SerialName("WARMUP_GAIN_DETECTED") WARMUP_GAIN_DETECTED
}

@Serializable
data class FatigueCurvePointDto(
    val minute: Int,
    @SerialName("predicted_accuracy") val predictedAccuracy: Double
)

@Serializable
data class FatigueReportDto(
    val status: FatigueStatus,
    @SerialName("qualifying_sessions") val qualifyingSessions: Int,
    @SerialName("required_sessions") val requiredSessions: Int = 3,
    @SerialName("qualifying_attempts") val qualifyingAttempts: Int,
    @SerialName("required_attempts") val requiredAttempts: Int = 120,
    @SerialName("shrunk_accuracy_delta") val shrunkAccuracyDelta: Double? = null,
    @SerialName("estimated_lambda") val estimatedLambda: Double? = null,
    @SerialName("difficulty_controlled") val difficultyControlled: Boolean = true,
    @SerialName("curve_300m") val curve300m: List<FatigueCurvePointDto> = emptyList()
)

object FatigueEngine {
    fun sigmoid(z: Double): Double {
        if (!z.isFinite()) return 0.5
        return if (z >= 0.0) {
            val ez = exp(-z)
            1.0 / (1.0 + ez)
        } else {
            val ez = exp(z)
            ez / (1.0 + ez)
        }
    }

    fun logit(p: Double): Double {
        val c = finiteOr(p, 0.5).coerceIn(0.02, 0.98)
        return ln(c / (1.0 - c))
    }

    /**
     * Modelo individual de desempenho ao longo de 300 minutos:
     * `p(t) = sigma(logit(p0) - lambda_i * (t / 300))`
     */
    fun performanceAtMinute(
        pBase: Double,
        itemDifficulty: Double,
        lambdaI: Double,
        elapsedMinutes: Double
    ): Pair<Double, Double> {
        val d = finiteOr(itemDifficulty, 0.50).coerceIn(0.0, 1.0)
        val t = finiteOr(elapsedMinutes, 0.0).coerceIn(0.0, 300.0)
        val lam = if (lambdaI.isFinite()) lambdaI.coerceIn(-2.5, 2.5) else 0.0
        val z0 = logit(pBase) - 0.80 * (d - 0.50)
        val p0 = sigmoid(z0)
        val pt = sigmoid(z0 - lam * (t / 300.0))
        return round9(p0) to round9(pt)
    }

    fun buildCurve300m(pBase: Double, lambdaI: Double): List<FatigueCurvePointDto> =
        listOf(0, 60, 120, 180, 240, 300).map { minute ->
            val (_, pt) = performanceAtMinute(pBase, 0.50, lambdaI, minute.toDouble())
            FatigueCurvePointDto(minute = minute, predictedAccuracy = pt)
        }

    fun evaluateFatigueWithDifficulty(
        qualifyingSessions: Int,
        earlyCorrect: Int,
        earlyTotal: Int,
        lateCorrect: Int,
        lateTotal: Int,
        earlyAvgDifficulty: Double = 0.50,
        lateAvgDifficulty: Double = 0.50,
        earlyMeanMinutes: Double = 30.0,
        lateMeanMinutes: Double = 210.0
    ): FatigueReportDto {
        val sEarlyTotal = max(0, earlyTotal)
        val sLateTotal = max(0, lateTotal)
        val total = sEarlyTotal + sLateTotal
        if (qualifyingSessions < 3 || total < 120 || sEarlyTotal < 30 || sLateTotal < 30) {
            return FatigueReportDto(
                status = FatigueStatus.INSUFFICIENT_EVIDENCE,
                qualifyingSessions = max(0, qualifyingSessions),
                requiredSessions = 3,
                qualifyingAttempts = total,
                requiredAttempts = 120,
                shrunkAccuracyDelta = null,
                estimatedLambda = null,
                difficultyControlled = true,
                curve300m = emptyList()
            )
        }

        val dEarly = finiteOr(earlyAvgDifficulty, 0.50).coerceIn(0.0, 1.0)
        val dLate = finiteOr(lateAvgDifficulty, 0.50).coerceIn(0.0, 1.0)
        val uEarly = (finiteOr(earlyMeanMinutes, 30.0) / 300.0).coerceIn(0.0, 0.85)
        val uLate = (finiteOr(lateMeanMinutes, 210.0) / 300.0).coerceIn(uEarly + 0.15, 1.0)

        val pEarly = (earlyCorrect.coerceIn(0, sEarlyTotal).toDouble() + 0.5) / (sEarlyTotal.toDouble() + 1.0)
        val pLate = (lateCorrect.coerceIn(0, sLateTotal).toDouble() + 0.5) / (sLateTotal.toDouble() + 1.0)

        val zEarlyAdj = logit(pEarly) + 0.80 * (dEarly - 0.50)
        val zLateAdj = logit(pLate) + 0.80 * (dLate - 0.50)

        val du = uLate - uEarly
        val rawLambda = (zEarlyAdj - zLateAdj) / du

        val wEarly = sEarlyTotal.toDouble() * pEarly * (1.0 - pEarly)
        val wLate = sLateTotal.toDouble() * pLate * (1.0 - pLate)
        val varDiff = (1.0 / wEarly) + (1.0 / wLate)
        val seRawLambda = sqrt(varDiff) / du
        val infoLambda = 1.0 / (seRawLambda * seRawLambda)
        val priorPrec = 4.0
        val shrunkLambda = ((infoLambda / (infoLambda + priorPrec)) * rawLambda).coerceIn(-2.5, 2.5)

        val pEarlyNeutral = sigmoid(zEarlyAdj)
        val pLateNeutral = sigmoid(zLateAdj)
        val kappa = sLateTotal.toDouble() / (sLateTotal.toDouble() + 40.0)
        val shrunkDelta = round9(kappa * (pLateNeutral - pEarlyNeutral))
        val roundedLambda = round9(shrunkLambda)

        val status = when {
            shrunkLambda > 0.25 && (rawLambda - 1.645 * seRawLambda) > 0.0 -> FatigueStatus.FATIGUE_DETECTED
            shrunkLambda < -0.25 && (rawLambda + 1.645 * seRawLambda) < 0.0 -> FatigueStatus.WARMUP_GAIN_DETECTED
            else -> FatigueStatus.STABLE_ENDURANCE
        }

        return FatigueReportDto(
            status = status,
            qualifyingSessions = qualifyingSessions,
            requiredSessions = 3,
            qualifyingAttempts = total,
            requiredAttempts = 120,
            shrunkAccuracyDelta = shrunkDelta,
            estimatedLambda = roundedLambda,
            difficultyControlled = true,
            curve300m = buildCurve300m(pEarlyNeutral, roundedLambda)
        )
    }

    fun evaluateFatigue(
        qualifyingSessions: Int,
        earlyCorrect: Int,
        earlyTotal: Int,
        lateCorrect: Int,
        lateTotal: Int
    ): FatigueReportDto = evaluateFatigueWithDifficulty(
        qualifyingSessions = qualifyingSessions,
        earlyCorrect = earlyCorrect,
        earlyTotal = earlyTotal,
        lateCorrect = lateCorrect,
        lateTotal = lateTotal,
        earlyAvgDifficulty = 0.50,
        lateAvgDifficulty = 0.50,
        earlyMeanMinutes = 30.0,
        lateMeanMinutes = 210.0
    )
}

@Serializable
enum class DeclaredConfidence(
    val ordinalLevel: Int,
    val labelPt: String
) {
    @SerialName("GUESS") GUESS(1, "Chutei"),
    @SerialName("LOW") LOW(2, "Pouco confiante"),
    @SerialName("MEDIUM") MEDIUM(3, "Parcialmente confiante"),
    @SerialName("HIGH") HIGH(4, "Muito confiante"),
    @SerialName("VERY_HIGH") VERY_HIGH(5, "Praticamente certo");

    fun toLegacyBand(): Int = when (this) {
        GUESS -> 1
        LOW -> 1
        MEDIUM -> 2
        HIGH -> 3
        VERY_HIGH -> 3
    }

    val isHigh: Boolean
        get() = this == HIGH || this == VERY_HIGH

    val isLowOrGuess: Boolean
        get() = this == GUESS || this == LOW

    companion object {
        fun fromLegacyBand(band: Int, explicit: Boolean): DeclaredConfidence? {
            if (!explicit) return null
            return when (band) {
                0 -> GUESS
                1 -> LOW
                2 -> MEDIUM
                else -> HIGH
            }
        }
    }
}

@Serializable
enum class CognitiveStrategy(
    val labelPt: String,
    val shortLabelPt: String
) {
    @SerialName("CONCEPTUAL_KNOWLEDGE")
    CONCEPTUAL_KNOWLEDGE("Conhecia e apliquei o conceito.", "Conhecia o conceito"),
    @SerialName("LOGICAL_DEDUCTION")
    LOGICAL_DEDUCTION("Deduzi por raciocínio lógico.", "Dedução lógica"),
    @SerialName("ELIMINATION")
    ELIMINATION("Eliminei alternativas.", "Eliminação"),
    @SerialName("PATTERN_RECOGNITION")
    PATTERN_RECOGNITION("Reconheci termos familiares.", "Termos familiares"),
    @SerialName("INTUITION")
    INTUITION("Respondi por intuição.", "Intuição"),
    @SerialName("RANDOM_GUESS")
    RANDOM_GUESS("Escolhi aleatoriamente.", "Escolha aleatória"),
    @SerialName("UNSPECIFIED")
    UNSPECIFIED("Não sei identificar.", "Não sei identificar");

    val isGroundedOrFunctional: Boolean
        get() = this == CONCEPTUAL_KNOWLEDGE || this == LOGICAL_DEDUCTION || this == ELIMINATION

    val isSurfaceOrFragile: Boolean
        get() = this == PATTERN_RECOGNITION || this == INTUITION || this == RANDOM_GUESS
}

@Serializable
enum class PreliminaryDiagnosis {
    @SerialName("POSSIBLE_CONCEPTUAL_MASTERY") POSSIBLE_CONCEPTUAL_MASTERY,
    @SerialName("INSECURE_KNOWLEDGE") INSECURE_KNOWLEDGE,
    @SerialName("POSSIBLE_CASUAL_HIT") POSSIBLE_CASUAL_HIT,
    @SerialName("UNCONFIRMED_MASTERY") UNCONFIRMED_MASTERY,
    @SerialName("POSSIBLE_MASTERY_ILLUSION") POSSIBLE_MASTERY_ILLUSION,
    @SerialName("FUNCTIONAL_REASONING") FUNCTIONAL_REASONING,
    @SerialName("POSSIBLE_CONCEPTUAL_MISCONCEPTION") POSSIBLE_CONCEPTUAL_MISCONCEPTION,
    @SerialName("POSSIBLE_KNOWLEDGE_GAP") POSSIBLE_KNOWLEDGE_GAP,
    @SerialName("UNDEMONSTRATED_KNOWLEDGE") UNDEMONSTRATED_KNOWLEDGE,
    @SerialName("STRATEGIC_OMISSION") STRATEGIC_OMISSION
}

@Serializable
enum class SupportLevel(val labelPt: String) {
    @SerialName("LOW") LOW("Sustentação inicial"),
    @SerialName("MODERATE") MODERATE("Sustentação moderada"),
    @SerialName("HIGH") HIGH("Sustentação consistente")
}

@Serializable
enum class CtvTriggerPriority {
    @SerialName("NONE") NONE,
    @SerialName("PERIODIC_SAMPLE") PERIODIC_SAMPLE,
    @SerialName("OPTIONAL") OPTIONAL,
    @SerialName("RECOMMENDED") RECOMMENDED,
    @SerialName("HIGH") HIGH
}

data class MetacognitiveMatrixInputs(
    val isBlank: Boolean,
    val isCorrect: Boolean,
    val declaredConfidence: DeclaredConfidence?,
    val cognitiveStrategy: CognitiveStrategy?,
    val consistentHistory: Boolean,
    val rationaleContradicted: Boolean
)

@Serializable
data class BehavioralEvidenceDto(
    @SerialName("active_seconds") val activeSeconds: Int? = null,
    @SerialName("total_elapsed_seconds") val totalElapsedSeconds: Int = 0,
    @SerialName("answer_change_count") val answerChangeCount: Int = 0,
    @SerialName("initial_chosen_index") val initialChosenIndex: Int? = null,
    @SerialName("final_chosen_index") val finalChosenIndex: Int? = null,
    @SerialName("consulted_resources") val consultedResources: List<String> = emptyList(),
    @SerialName("focus_loss_count") val focusLossCount: Int = 0,
    @SerialName("session_interrupted") val sessionInterrupted: Boolean = false
)

@Serializable
data class MetacognitiveAssessmentDto(
    @SerialName("preliminary_diagnosis") val preliminaryDiagnosis: PreliminaryDiagnosis,
    @SerialName("inference_support") val inferenceSupport: SupportLevel,
    @SerialName("ctv_priority") val ctvPriority: CtvTriggerPriority,
    @SerialName("pedagogical_feedback_pt") val pedagogicalFeedbackPt: String = "",
    @SerialName("self_perception_summary") val selfPerceptionSummary: String,
    @SerialName("observed_evidence_summary") val observedEvidenceSummary: String,
    @SerialName("system_inference_message") val systemInferenceMessage: String,
    @SerialName("strategy_skipped") val strategySkipped: Boolean = false,
    @SerialName("policy_version") val policyVersion: String = MetacognitiveEngine.METACOGNITIVE_POLICY_VERSION
)

@Serializable
enum class HitGroundingCategory {
    @SerialName("NOT_APPLICABLE_ERROR") NOT_APPLICABLE_ERROR,
    @SerialName("VALID_CONCEPTUAL_GROUNDING") VALID_CONCEPTUAL_GROUNDING,
    @SerialName("VALID_FUNCTIONAL_STRATEGY") VALID_FUNCTIONAL_STRATEGY,
    @SerialName("DIVERGENT_RATIONALE") DIVERGENT_RATIONALE,
    @SerialName("FRAGILE_OR_GUESS") FRAGILE_OR_GUESS,
    @SerialName("RETROSPECTIVE_LEGACY_EVIDENCE") RETROSPECTIVE_LEGACY_EVIDENCE,
    @SerialName("UNKNOWN_GROUNDING") UNKNOWN_GROUNDING;

    val creditsComprehension: Boolean
        get() = this == VALID_CONCEPTUAL_GROUNDING || this == VALID_FUNCTIONAL_STRATEGY

    val isEvaluable: Boolean
        get() = this == VALID_CONCEPTUAL_GROUNDING ||
            this == VALID_FUNCTIONAL_STRATEGY ||
            this == DIVERGENT_RATIONALE ||
            this == FRAGILE_OR_GUESS
}

object MetacognitiveEngine {
    const val METACOGNITIVE_POLICY_VERSION = "metacog-v1.0"

    fun classifyHitGrounding(
        isCorrect: Boolean,
        declaredConfidence: DeclaredConfidence?,
        cognitiveStrategy: CognitiveStrategy?,
        evidenceCompatible: Boolean?
    ): HitGroundingCategory {
        if (!isCorrect) return HitGroundingCategory.NOT_APPLICABLE_ERROR
        if (evidenceCompatible == false) return HitGroundingCategory.DIVERGENT_RATIONALE
        if (declaredConfidence?.isLowOrGuess == true || cognitiveStrategy?.isSurfaceOrFragile == true) {
            return HitGroundingCategory.FRAGILE_OR_GUESS
        }
        if (cognitiveStrategy == CognitiveStrategy.LOGICAL_DEDUCTION ||
            cognitiveStrategy == CognitiveStrategy.ELIMINATION
        ) {
            return HitGroundingCategory.VALID_FUNCTIONAL_STRATEGY
        }
        if (cognitiveStrategy == CognitiveStrategy.CONCEPTUAL_KNOWLEDGE) {
            return HitGroundingCategory.VALID_CONCEPTUAL_GROUNDING
        }
        if (evidenceCompatible == true) {
            return HitGroundingCategory.RETROSPECTIVE_LEGACY_EVIDENCE
        }
        return HitGroundingCategory.UNKNOWN_GROUNDING
    }

    fun classifyPreliminaryMatrix(
        inputs: MetacognitiveMatrixInputs
    ): Triple<PreliminaryDiagnosis, SupportLevel, CtvTriggerPriority> {
        if (inputs.isBlank) {
            return Triple(
                PreliminaryDiagnosis.STRATEGIC_OMISSION,
                SupportLevel.MODERATE,
                CtvTriggerPriority.NONE
            )
        }
        val isPureOrUnconfirmedGuess = inputs.cognitiveStrategy == CognitiveStrategy.RANDOM_GUESS ||
            (inputs.declaredConfidence == DeclaredConfidence.GUESS &&
                (inputs.cognitiveStrategy == null ||
                    inputs.cognitiveStrategy == CognitiveStrategy.RANDOM_GUESS ||
                    inputs.cognitiveStrategy == CognitiveStrategy.UNSPECIFIED))
        val isPartialGuess = inputs.declaredConfidence == DeclaredConfidence.GUESS && !isPureOrUnconfirmedGuess

        if (inputs.isCorrect) {
            if (inputs.rationaleContradicted) {
                return Triple(
                    PreliminaryDiagnosis.POSSIBLE_MASTERY_ILLUSION,
                    SupportLevel.HIGH,
                    CtvTriggerPriority.HIGH
                )
            }
            if (isPureOrUnconfirmedGuess) {
                return Triple(
                    PreliminaryDiagnosis.POSSIBLE_CASUAL_HIT,
                    if (inputs.cognitiveStrategy == CognitiveStrategy.RANDOM_GUESS) SupportLevel.HIGH else SupportLevel.MODERATE,
                    CtvTriggerPriority.HIGH
                )
            }
            if (isPartialGuess) {
                return Triple(
                    PreliminaryDiagnosis.UNCONFIRMED_MASTERY,
                    SupportLevel.MODERATE,
                    CtvTriggerPriority.RECOMMENDED
                )
            }
            if (inputs.declaredConfidence == DeclaredConfidence.LOW) {
                return Triple(
                    PreliminaryDiagnosis.INSECURE_KNOWLEDGE,
                    SupportLevel.MODERATE,
                    CtvTriggerPriority.RECOMMENDED
                )
            }
            if (inputs.cognitiveStrategy == CognitiveStrategy.LOGICAL_DEDUCTION ||
                inputs.cognitiveStrategy == CognitiveStrategy.ELIMINATION
            ) {
                return Triple(
                    PreliminaryDiagnosis.FUNCTIONAL_REASONING,
                    SupportLevel.MODERATE,
                    CtvTriggerPriority.OPTIONAL
                )
            }
            if (inputs.cognitiveStrategy == CognitiveStrategy.PATTERN_RECOGNITION ||
                inputs.cognitiveStrategy == CognitiveStrategy.INTUITION
            ) {
                return Triple(
                    PreliminaryDiagnosis.UNCONFIRMED_MASTERY,
                    SupportLevel.MODERATE,
                    CtvTriggerPriority.RECOMMENDED
                )
            }
            if (inputs.declaredConfidence?.isHigh == true && inputs.consistentHistory) {
                return Triple(
                    PreliminaryDiagnosis.POSSIBLE_CONCEPTUAL_MASTERY,
                    SupportLevel.HIGH,
                    CtvTriggerPriority.PERIODIC_SAMPLE
                )
            }
            return Triple(
                PreliminaryDiagnosis.UNCONFIRMED_MASTERY,
                if (inputs.declaredConfidence != null || inputs.cognitiveStrategy != null) {
                    SupportLevel.MODERATE
                } else {
                    SupportLevel.LOW
                },
                CtvTriggerPriority.RECOMMENDED
            )
        } else {
            if (isPureOrUnconfirmedGuess) {
                return Triple(
                    PreliminaryDiagnosis.UNDEMONSTRATED_KNOWLEDGE,
                    SupportLevel.MODERATE,
                    CtvTriggerPriority.NONE
                )
            }
            if (isPartialGuess) {
                return Triple(
                    PreliminaryDiagnosis.POSSIBLE_KNOWLEDGE_GAP,
                    SupportLevel.MODERATE,
                    CtvTriggerPriority.OPTIONAL
                )
            }
            if (inputs.declaredConfidence?.isHigh == true) {
                return Triple(
                    PreliminaryDiagnosis.POSSIBLE_CONCEPTUAL_MISCONCEPTION,
                    SupportLevel.HIGH,
                    CtvTriggerPriority.HIGH
                )
            }
            return Triple(
                PreliminaryDiagnosis.POSSIBLE_KNOWLEDGE_GAP,
                SupportLevel.MODERATE,
                CtvTriggerPriority.OPTIONAL
            )
        }
    }

    fun pedagogicalFeedbackPt(
        inputs: MetacognitiveMatrixInputs,
        diagnosis: PreliminaryDiagnosis,
        hasMultiAttemptEvidence: Boolean = false
    ): String {
        if (inputs.isBlank) {
            return "Questão deixada em branco. Nenhuma inferência de domínio foi registrada."
        }
        return if (inputs.isCorrect) {
            when {
                inputs.rationaleContradicted || diagnosis == PreliminaryDiagnosis.POSSIBLE_CASUAL_HIT ->
                    "Você acertou, mas ainda precisamos verificar sua compreensão."
                inputs.declaredConfidence == DeclaredConfidence.GUESS &&
                    (inputs.cognitiveStrategy == CognitiveStrategy.ELIMINATION ||
                        inputs.cognitiveStrategy == CognitiveStrategy.LOGICAL_DEDUCTION) ->
                    "Você eliminou alternativas, mas ainda respondeu com incerteza. Sua compreensão precisa ser confirmada."
                inputs.cognitiveStrategy == CognitiveStrategy.PATTERN_RECOGNITION ||
                    inputs.cognitiveStrategy == CognitiveStrategy.INTUITION ->
                    "Você reconheceu elementos importantes. Sua compreensão ainda precisa ser confirmada."
                diagnosis == PreliminaryDiagnosis.INSECURE_KNOWLEDGE ->
                    "Você acertou, mas ainda demonstra insegurança neste conceito."
                diagnosis == PreliminaryDiagnosis.FUNCTIONAL_REASONING ->
                    if (inputs.cognitiveStrategy == CognitiveStrategy.ELIMINATION) {
                        "Você utilizou uma estratégia de eliminação eficaz."
                    } else {
                        "Você utilizou raciocínio lógico consistente nesta questão."
                    }
                diagnosis == PreliminaryDiagnosis.POSSIBLE_CONCEPTUAL_MASTERY ||
                    inputs.declaredConfidence?.isHigh == true ->
                    "Você acertou com segurança."
                inputs.declaredConfidence == null &&
                    inputs.cognitiveStrategy == null &&
                    !hasMultiAttemptEvidence ->
                    "Ainda não há informações suficientes para avaliar seu domínio."
                else ->
                    "Você acertou, mas sua compreensão ainda precisa ser confirmada."
            }
        } else {
            when (diagnosis) {
                PreliminaryDiagnosis.POSSIBLE_CONCEPTUAL_MISCONCEPTION ->
                    "Sua resposta indica um possível equívoco conceitual que merece revisão."
                PreliminaryDiagnosis.UNDEMONSTRATED_KNOWLEDGE ->
                    "Seu domínio deste conceito ainda não foi demonstrado."
                else ->
                    "Este conceito pode precisar de reforço."
            }
        }
    }

    fun inferenceMessagePt(diagnosis: PreliminaryDiagnosis, hasMultiAttemptEvidence: Boolean): String =
        when (diagnosis) {
            PreliminaryDiagnosis.POSSIBLE_CONCEPTUAL_MASTERY ->
                "Acerto consistente com alta confiança. Evidência favorável à consolidação progressiva do conceito."
            PreliminaryDiagnosis.INSECURE_KNOWLEDGE ->
                "Acerto registrado com baixa confiança declarada. Conhecimento ainda inseguro; vale validar em outro contexto."
            PreliminaryDiagnosis.POSSIBLE_CASUAL_HIT ->
                "Acerto registrado com indicação de chute. O domínio conceitual ainda não foi confirmado."
            PreliminaryDiagnosis.UNCONFIRMED_MASTERY ->
                "Acerto registrado, mas o domínio conceitual ainda não foi confirmado."
            PreliminaryDiagnosis.POSSIBLE_MASTERY_ILLUSION ->
                if (hasMultiAttemptEvidence) {
                    "Foram identificadas evidências de possível superestimação do domínio deste conceito."
                } else {
                    "Acerto registrado, porém o fundamento selecionado diverge da regra cobrada; o domínio conceitual ainda não foi confirmado."
                }
            PreliminaryDiagnosis.FUNCTIONAL_REASONING ->
                "Acerto obtido por raciocínio funcional (dedução ou eliminação). Estratégia válida; recomenda-se consolidar também a regra direta."
            PreliminaryDiagnosis.POSSIBLE_CONCEPTUAL_MISCONCEPTION ->
                if (hasMultiAttemptEvidence) {
                    "Foram identificadas evidências recorrentes de possível equívoco conceitual neste tema."
                } else {
                    "Resposta incorreta com confiança elevada: indício preliminar de possível equívoco na interpretação da regra ou exceção."
                }
            PreliminaryDiagnosis.POSSIBLE_KNOWLEDGE_GAP ->
                "Resposta incorreta com baixa confiança: indício preliminar de lacuna neste conceito, útil para revisão direcionada."
            PreliminaryDiagnosis.UNDEMONSTRATED_KNOWLEDGE ->
                "Conhecimento ainda não demonstrado nesta tentativa (resposta incorreta por tentativa aleatória)."
            PreliminaryDiagnosis.STRATEGIC_OMISSION ->
                "Questão deixada em branco como decisão estratégica sob incerteza; conhecimento do item não avaliado."
        }

    fun buildAssessment(
        inputs: MetacognitiveMatrixInputs,
        behavioral: BehavioralEvidenceDto = BehavioralEvidenceDto(),
        hasMultiAttemptEvidence: Boolean = false
    ): MetacognitiveAssessmentDto {
        val (diagnosis, support, priority) = classifyPreliminaryMatrix(inputs)
        val confText = inputs.declaredConfidence?.labelPt ?: "Não informada"
        val stratText = inputs.cognitiveStrategy?.labelPt ?: "Etapa pulada / não informada"
        val selfPerception = "Confiança declarada: $confText · Estratégia: $stratText"

        val secs = (behavioral.activeSeconds ?: behavioral.totalElapsedSeconds).coerceAtLeast(0)
        val outcomeLabel = when {
            inputs.isBlank -> "Omissão em branco"
            inputs.isCorrect -> "Resposta correta"
            else -> "Resposta incorreta"
        }
        val changesNote = if (behavioral.answerChangeCount > 0) {
            ", ${behavioral.answerChangeCount} troca(s) de alternativa"
        } else {
            ""
        }
        val resourcesNote = if (behavioral.consultedResources.isNotEmpty()) {
            ", ${behavioral.consultedResources.size} recurso(s) auxiliar(es) consultado(s)"
        } else {
            ""
        }
        val observed = "$outcomeLabel em ${secs}s ativos$changesNote$resourcesNote."
        return MetacognitiveAssessmentDto(
            preliminaryDiagnosis = diagnosis,
            inferenceSupport = support,
            ctvPriority = priority,
            pedagogicalFeedbackPt = pedagogicalFeedbackPt(inputs, diagnosis, hasMultiAttemptEvidence),
            selfPerceptionSummary = selfPerception,
            observedEvidenceSummary = observed,
            systemInferenceMessage = inferenceMessagePt(diagnosis, hasMultiAttemptEvidence),
            strategySkipped = inputs.cognitiveStrategy == null,
            policyVersion = METACOGNITIVE_POLICY_VERSION
        )
    }

    private fun normalizeTokensAndTrigrams(raw: String): Set<String> {
        val cleaned = buildString(raw.length) {
            for (ch in raw.lowercase()) {
                append(if (ch.isLetterOrDigit()) ch else ' ')
            }
        }
        val set = HashSet<String>()
        val words = cleaned.split(Regex("\\s+")).filter { it.length >= 3 }
        for (w in words) {
            set += "w:$w"
        }
        val joined = words.joinToString(" ")
        if (joined.length >= 4) {
            for (i in 0..(joined.length - 4)) {
                set += "g:${joined.substring(i, i + 4)}"
            }
        }
        return set
    }

    fun jaccardTrigramSimilarity(a: String, b: String): Double {
        val setA = normalizeTokensAndTrigrams(a)
        val setB = normalizeTokensAndTrigrams(b)
        if (setA.isEmpty() && setB.isEmpty()) return 1.0
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val inter = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        return inter / union
    }
}

@Serializable
enum class ConceptMasteryState(val labelPt: String) {
    @SerialName("NOT_EVALUATED") NOT_EVALUATED("Não avaliado"),
    @SerialName("INSUFFICIENT_EVIDENCE") INSUFFICIENT_EVIDENCE("Evidência insuficiente"),
    @SerialName("EMERGING_KNOWLEDGE") EMERGING_KNOWLEDGE("Conhecimento inicial"),
    @SerialName("UNSTABLE_KNOWLEDGE") UNSTABLE_KNOWLEDGE("Conhecimento instável"),
    @SerialName("DEVELOPING_MASTERY") DEVELOPING_MASTERY("Domínio em desenvolvimento"),
    @SerialName("DEMONSTRATED_MASTERY") DEMONSTRATED_MASTERY("Domínio demonstrado"),
    @SerialName("CONSOLIDATED_MASTERY") CONSOLIDATED_MASTERY("Domínio consolidado"),
    @SerialName("MASTERY_AT_RISK") MASTERY_AT_RISK("Domínio em risco");

    val isMasteredOrConsolidated: Boolean
        get() = this == DEMONSTRATED_MASTERY || this == CONSOLIDATED_MASTERY
}

@Serializable
data class MasteryPolicyConfig(
    @SerialName("min_attempts_for_classification") val minAttemptsForClassification: Int = 3,
    @SerialName("min_distinct_questions_for_classification") val minDistinctQuestionsForClassification: Int = 3,
    @SerialName("min_distinct_for_demonstrated") val minDistinctForDemonstrated: Int = 4,
    @SerialName("min_distinct_for_consolidated") val minDistinctForConsolidated: Int = 5,
    @SerialName("at_risk_retrievability_threshold") val atRiskRetrievabilityThreshold: Double = 0.70,
    @SerialName("high_conf_error_instability_threshold") val highConfErrorInstabilityThreshold: Double = 0.25,
    @SerialName("min_consistency_threshold") val minConsistencyThreshold: Double = 0.45
)

data class ConceptMasteryInputs(
    val attempts: Int,
    val distinctQuestions: Int,
    val accuracy: Double,
    val groundedRate: Double,
    val highConfErrorRate: Double,
    val transferAttempts: Int,
    val transferSuccessRate: Double,
    val hasSpacedRetention: Boolean,
    val retrievability: Double,
    val consistency: Double,
    val avgActiveSeconds: Double? = null,
    val referenceSeconds: Double = 60.0
)

@Serializable
data class ConceptMasteryDimensions(
    val performance: Double,
    val comprehension: Double,
    val transfer: Double? = null,
    val retention: Double? = null,
    val metacognition: Double,
    val fluency: Double? = null,
    val consistency: Double
)

@Serializable
data class ConceptMasteryEvaluation(
    val state: ConceptMasteryState,
    @SerialName("state_label_pt") val stateLabelPt: String,
    @SerialName("composite_score") val compositeScore: Double,
    val dimensions: ConceptMasteryDimensions,
    @SerialName("policy_version") val policyVersion: String = ConceptMasteryEngine.CONCEPT_MASTERY_POLICY_VERSION
)

object ConceptMasteryEngine {
    const val CONCEPT_MASTERY_POLICY_VERSION = "mastery-v1.0"

    private fun clamp01(v: Double): Double = if (v.isFinite()) v.coerceIn(0.0, 1.0) else 0.0

    fun computeFluencyScore(avgActiveSeconds: Double?, referenceSeconds: Double): Double? {
        val secs = avgActiveSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val refS = if (referenceSeconds.isFinite() && referenceSeconds > 5.0) referenceSeconds else 60.0
        if (secs < 3.0) return 0.25
        val ratio = secs / refS
        val score = if (ratio <= 1.0) {
            1.0 - 0.15 * (1.0 - ratio).coerceIn(0.0, 1.0)
        } else {
            (1.0 / (1.0 + 0.6 * (ratio - 1.0))).coerceIn(0.1, 1.0)
        }
        return clamp01(score)
    }

    fun classifyConceptMasteryWithPolicy(
        inputs: ConceptMasteryInputs,
        policy: MasteryPolicyConfig = MasteryPolicyConfig()
    ): ConceptMasteryEvaluation {
        val perf = clamp01(inputs.accuracy)
        val comp = clamp01(inputs.groundedRate)
        val hce = clamp01(inputs.highConfErrorRate)
        val metacog = clamp01(1.0 - hce)
        val cons = clamp01(inputs.consistency)
        val retr = clamp01(inputs.retrievability)
        val transferDim = if (inputs.transferAttempts > 0) clamp01(inputs.transferSuccessRate) else null
        val retentionDim = if (inputs.attempts > 0) retr else null
        val fluencyDim = if (inputs.attempts > 0) computeFluencyScore(inputs.avgActiveSeconds, inputs.referenceSeconds) else null

        val state = if (inputs.attempts <= 0 || inputs.distinctQuestions <= 0) {
            ConceptMasteryState.NOT_EVALUATED
        } else if (inputs.attempts < policy.minAttemptsForClassification ||
            inputs.distinctQuestions < policy.minDistinctQuestionsForClassification
        ) {
            ConceptMasteryState.INSUFFICIENT_EVIDENCE
        } else {
            val hadStrongMasteryProfile = inputs.distinctQuestions >= policy.minDistinctForDemonstrated &&
                perf >= 0.75 &&
                comp >= 0.70 &&
                hce <= 0.15 &&
                inputs.transferAttempts >= 1 &&
                clamp01(inputs.transferSuccessRate) >= 0.70 &&
                cons >= 0.70

            when {
                hadStrongMasteryProfile && retr < policy.atRiskRetrievabilityThreshold ->
                    ConceptMasteryState.MASTERY_AT_RISK
                hce >= policy.highConfErrorInstabilityThreshold ||
                    cons < policy.minConsistencyThreshold ||
                    (inputs.transferAttempts >= 1 && clamp01(inputs.transferSuccessRate) < 0.50 && perf >= 0.55) ->
                    ConceptMasteryState.UNSTABLE_KNOWLEDGE
                inputs.distinctQuestions >= policy.minDistinctForConsolidated &&
                    perf >= 0.80 &&
                    comp >= 0.75 &&
                    hce <= 0.10 &&
                    inputs.transferAttempts >= 1 &&
                    clamp01(inputs.transferSuccessRate) >= 0.75 &&
                    inputs.hasSpacedRetention &&
                    retr >= policy.atRiskRetrievabilityThreshold &&
                    cons >= 0.75 ->
                    ConceptMasteryState.CONSOLIDATED_MASTERY
                hadStrongMasteryProfile && retr >= policy.atRiskRetrievabilityThreshold ->
                    ConceptMasteryState.DEMONSTRATED_MASTERY
                perf >= 0.70 && comp >= 0.60 && hce < policy.highConfErrorInstabilityThreshold && cons >= 0.60 ->
                    ConceptMasteryState.DEVELOPING_MASTERY
                else -> ConceptMasteryState.EMERGING_KNOWLEDGE
            }
        }

        val compositeScore = if (inputs.attempts <= 0) {
            0.0
        } else {
            val tVal = transferDim ?: (comp * 0.7)
            val rVal = retentionDim ?: 0.5
            val fVal = fluencyDim ?: 0.7
            clamp01(
                0.22 * perf +
                    0.22 * comp +
                    0.20 * tVal +
                    0.14 * rVal +
                    0.10 * metacog +
                    0.05 * fVal +
                    0.07 * cons
            )
        }

        return ConceptMasteryEvaluation(
            state = state,
            stateLabelPt = state.labelPt,
            compositeScore = compositeScore,
            dimensions = ConceptMasteryDimensions(
                performance = perf,
                comprehension = comp,
                transfer = transferDim,
                retention = retentionDim,
                metacognition = metacog,
                fluency = fluencyDim,
                consistency = cons
            ),
            policyVersion = CONCEPT_MASTERY_POLICY_VERSION
        )
    }

    fun classifyConceptMastery(inputs: ConceptMasteryInputs): ConceptMasteryEvaluation =
        classifyConceptMasteryWithPolicy(inputs, MasteryPolicyConfig())
}

@Serializable
data class ReuseEconomySimulation(
    @SerialName("monthly_verifications") val monthlyVerifications: Long,
    @SerialName("reuse_rate_pct") val reuseRatePct: Double,
    @SerialName("avg_cost_per_generation") val avgCostPerGeneration: Double,
    @SerialName("cost_without_reuse") val costWithoutReuse: Double,
    @SerialName("cost_with_reuse") val costWithReuse: Double,
    @SerialName("monthly_savings") val monthlySavings: Double,
    @SerialName("avoided_generations_monthly") val avoidedGenerationsMonthly: Long,
    @SerialName("generated_questions_monthly") val generatedQuestionsMonthly: Long,
    @SerialName("annual_savings") val annualSavings: Double,
    @SerialName("cost_reduction_pct") val costReductionPct: Double,
    @SerialName("disclaimer_pt") val disclaimerPt: String
)

object ReuseEconomySimulator {
    private fun round2(v: Double): Double = rustRound(v * 100.0) / 100.0

    fun simulateAiReuseEconomy(
        monthlyVerifications: Long,
        reuseRatePct: Double,
        avgCostPerGeneration: Double
    ): ReuseEconomySimulation {
        val clampedVerifications = monthlyVerifications.coerceIn(0L, 10_000_000L)
        val clampedReusePct = if (reuseRatePct.isFinite()) reuseRatePct.coerceIn(0.0, 100.0) else 0.0
        val clampedCost = if (avgCostPerGeneration.isFinite()) avgCostPerGeneration.coerceIn(0.0, 100.0) else 0.0

        val avoided = rustRound(clampedVerifications.toDouble() * (clampedReusePct / 100.0))
            .toLong()
            .coerceIn(0L, clampedVerifications)
        val generated = (clampedVerifications - avoided).coerceAtLeast(0L)

        val withoutReuse = clampedVerifications.toDouble() * clampedCost
        val withReuse = generated.toDouble() * clampedCost
        val monthlySavings = max(0.0, withoutReuse - withReuse)
        val annualSavings = monthlySavings * 12.0
        val reductionPct = if (withoutReuse > 0.0) (monthlySavings / withoutReuse) * 100.0 else 0.0

        return ReuseEconomySimulation(
            monthlyVerifications = clampedVerifications,
            reuseRatePct = round2(clampedReusePct),
            avgCostPerGeneration = rustRound(clampedCost * 10000.0) / 10000.0,
            costWithoutReuse = round2(withoutReuse),
            costWithReuse = round2(withReuse),
            monthlySavings = round2(monthlySavings),
            avoidedGenerationsMonthly = avoided,
            generatedQuestionsMonthly = generated,
            annualSavings = round2(annualSavings),
            costReductionPct = round2(reductionPct),
            disclaimerPt = "Estimativa operacional hipotética baseada no custo unitário de referência informado; não constitui comprovação contábil ou financeira auditada."
        )
    }
}

@Serializable
enum class VerificationMode(val code: String) {
    @SerialName("CONCEPTUAL_TRANSFER") CONCEPTUAL_TRANSFER("CONCEPTUAL_TRANSFER"),
    @SerialName("TOPIC_PRACTICE") TOPIC_PRACTICE("TOPIC_PRACTICE"),
    @SerialName("INTENTIONAL_REVIEW") INTENTIONAL_REVIEW("INTENTIONAL_REVIEW");

    companion object {
        fun fromCode(raw: String?): VerificationMode = when (raw?.trim()?.uppercase()) {
            "TOPIC_PRACTICE" -> TOPIC_PRACTICE
            "INTENTIONAL_REVIEW", "RETENTION_CHECK" -> INTENTIONAL_REVIEW
            else -> CONCEPTUAL_TRANSFER
        }
    }
}

data class QuestionAlignmentProfile(
    val questionId: String = "",
    val subjectId: String? = null,
    val topicId: String? = null,
    val targetConceptId: String? = null,
    val allConceptIds: List<String> = emptyList(),
    val targetConceptAmbiguous: Boolean = false,
    val testedRule: String? = null,
    val decisiveExcerpt: String? = null,
    val legalProvisions: List<String> = emptyList(),
    val questionType: String = "MULTIPLE_CHOICE",
    val stem: String = "",
    val correctAnswerText: String? = null,
    val contentRevision: String = "",
    val semanticRevision: String = "",
    val anchoredSourceQuestionId: String? = null,
    val isAiUnconfirmedBinding: Boolean = false,
    val isEditorialPendingOrDubious: Boolean = false
)

sealed interface CandidateAlignmentDecision {
    data class Accepted(
        val mode: VerificationMode,
        val framingReasonCode: String,
        val framingReasonPt: String,
        val applicationBonus: Int
    ) : CandidateAlignmentDecision

    data class Rejected(
        val reasonCode: String
    ) : CandidateAlignmentDecision
}

data class CtvIndependenceInput(
    val verificationMode: VerificationMode,
    val action: String,
    val verificationTiming: String,
    val wasPreviouslyExposed: Boolean,
    val exposureHistoryKnown: Boolean = true,
    val hadDecisiveHelp: Boolean = false,
    val isCorrect: Boolean
)

data class CtvIndependenceVerdict(
    val countsAsIndependentAttempt: Boolean,
    val countsAsIndependentCorrect: Boolean,
    val independentAttemptsDelta: Int,
    val independentCorrectDelta: Int,
    val classificationCode: String,
    val summaryPt: String
)

object CtvAlignmentEngine {
    const val MAX_SOURCE_SIMILARITY_FOR_TRANSFER = 0.45
    const val MAX_COMBINED_STEM_ANSWER_SIMILARITY = 0.50

    private val STOPWORDS = setOf(
        "para", "como", "sobre", "entre", "qual", "quais", "quando", "onde", "pelo", "pela",
        "pelos", "pelas", "uma", "uns", "umas", "dos", "das", "nos", "nas", "com", "sem",
        "mais", "menos", "maior", "menor", "assinale", "opcao", "opção", "correta", "correto",
        "errado", "errada", "certo", "acerca", "relacao", "relação", "segundo", "conforme",
        "pode", "deve", "sendo", "sera", "será", "item", "questao", "questão", "alternativa",
        "aplicacao", "aplicação", "criterio", "critério", "cobrado", "oficial", "gabarito",
        "significado", "definicao", "definição", "conceito", "apresenta", "informa", "pergunta"
    )

    fun triggerCohortForReason(reason: String?): String = when (reason?.trim()) {
        "PERIODIC_MASTERY_SAMPLING", "BASELINE_SAMPLING", "PERIODIC_AUDIT" -> "PERIODIC_SAMPLING"
        else -> "DIFFICULTY_DETECTED"
    }

    fun isRuleRevealingHelp(resource: String): Boolean = when (resource.trim().lowercase()) {
        "explanation", "gabarito", "answer_key", "answer-key", "editorial_card", "editorial-card",
        "dr_sincero", "dr-sincero", "plausibility", "ai_explanation", "ai-explanation",
        "rule_hint", "rule-hint", "explicacao_infantil", "decisive_help" -> true
        else -> false
    }

    fun evaluateCtvIndependence(input: CtvIndependenceInput): CtvIndependenceVerdict {
        val actionNorm = input.action.trim().uppercase()
        if (actionNorm == "PRELOADED_ONLY") {
            return CtvIndependenceVerdict(
                countsAsIndependentAttempt = false,
                countsAsIndependentCorrect = false,
                independentAttemptsDelta = 0,
                independentCorrectDelta = 0,
                classificationCode = "PRELOADED_NOT_DISPLAYED",
                summaryPt = "Transferência de dados sem exibição do enunciado."
            )
        }
        if (actionNorm == "DISMISSED") {
            return CtvIndependenceVerdict(
                countsAsIndependentAttempt = false,
                countsAsIndependentCorrect = false,
                independentAttemptsDelta = 0,
                independentCorrectDelta = 0,
                classificationCode = "EXPOSED_AND_DISMISSED",
                summaryPt = "Questão complementar exibida e dispensada pelo estudante."
            )
        }
        val timingNorm = input.verificationTiming.trim().uppercase()
        if (!input.exposureHistoryKnown || timingNorm == "UNKNOWN_CONDITION" || timingNorm.isEmpty()) {
            return CtvIndependenceVerdict(
                countsAsIndependentAttempt = false,
                countsAsIndependentCorrect = false,
                independentAttemptsDelta = 0,
                independentCorrectDelta = 0,
                classificationCode = "UNKNOWN_CONDITION",
                summaryPt = "Registro anterior sem informação suficiente de momento ou exposição."
            )
        }
        if (input.verificationMode == VerificationMode.TOPIC_PRACTICE) {
            return CtvIndependenceVerdict(
                countsAsIndependentAttempt = false,
                countsAsIndependentCorrect = false,
                independentAttemptsDelta = 0,
                independentCorrectDelta = 0,
                classificationCode = "TOPIC_PRACTICE_RECORDED",
                summaryPt = "Prática complementar do tópico registrada sem medir transferência do conceito original."
            )
        }
        if (input.verificationMode == VerificationMode.INTENTIONAL_REVIEW || input.wasPreviouslyExposed) {
            return CtvIndependenceVerdict(
                countsAsIndependentAttempt = false,
                countsAsIndependentCorrect = false,
                independentAttemptsDelta = 0,
                independentCorrectDelta = 0,
                classificationCode = "PRIOR_EXPOSURE_REVIEW",
                summaryPt = "Item já visto anteriormente pelo estudante; contabilizado como revisão."
            )
        }
        if (timingNorm == "POST_INSTRUCTION" || input.hadDecisiveHelp) {
            return CtvIndependenceVerdict(
                countsAsIndependentAttempt = false,
                countsAsIndependentCorrect = false,
                independentAttemptsDelta = 0,
                independentCorrectDelta = 0,
                classificationCode = "POST_INSTRUCTION_FIXATION",
                summaryPt = "Resposta realizada após explicação ou ajuda que revelou a regra decisiva."
            )
        }
        if (timingNorm != "PRE_FEEDBACK") {
            return CtvIndependenceVerdict(
                countsAsIndependentAttempt = false,
                countsAsIndependentCorrect = false,
                independentAttemptsDelta = 0,
                independentCorrectDelta = 0,
                classificationCode = "DEFERRED_VERIFICATION",
                summaryPt = "Verificação realizada fora da janela pré-feedback imediata."
            )
        }
        return if (input.isCorrect) {
            CtvIndependenceVerdict(
                countsAsIndependentAttempt = true,
                countsAsIndependentCorrect = true,
                independentAttemptsDelta = 1,
                independentCorrectDelta = 1,
                classificationCode = "INDEPENDENT_CORRECT",
                summaryPt = "Aplicação independente correta antes do feedback."
            )
        } else {
            CtvIndependenceVerdict(
                countsAsIndependentAttempt = true,
                countsAsIndependentCorrect = false,
                independentAttemptsDelta = 1,
                independentCorrectDelta = 0,
                classificationCode = "INDEPENDENT_ERROR",
                summaryPt = "Falha de aplicação independente observada antes do feedback."
            )
        }
    }

    fun normalizeCompactText(raw: String): String =
        buildString(raw.length) {
            for (ch in raw.lowercase()) {
                append(if (ch.isLetterOrDigit()) ch else ' ')
            }
        }.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun domainContentTokens(raw: String): Set<String> =
        normalizeCompactText(raw)
            .split(' ')
            .filter { it.length >= 4 && it !in STOPWORDS }
            .toSet()

    private fun isDefinitionPrompt(stem: String): Boolean {
        val lower = stem.lowercase()
        return lower.contains("significado ") ||
            lower.contains("definição ") ||
            lower.contains("definicao ") ||
            lower.contains("o que mede") ||
            lower.contains("o que é ") ||
            lower.contains("o que expressa") ||
            lower.contains("o que caracteriza") ||
            lower.contains("conceito de ") ||
            lower.contains("define-se como")
    }

    private fun isApplicationScenario(stem: String): Boolean {
        val lower = stem.lowercase()
        val hasNumbers = stem.any { it.isDigit() }
        return hasNumbers ||
            lower.contains("dois títulos") ||
            lower.contains("duas carteiras") ||
            lower.contains("choque") ||
            lower.contains("caso ") ||
            lower.contains("situação") ||
            lower.contains("durante ") ||
            lower.contains("suponha") ||
            lower.contains("considere que um") ||
            lower.contains("considere dois") ||
            lower.contains("servidor ") ||
            lower.contains("contribuinte ") ||
            lower.contains("auditoria")
    }

    fun detectParaphraseOrPreservedCues(
        source: QuestionAlignmentProfile,
        candidate: QuestionAlignmentProfile
    ): Boolean {
        if (MetacognitiveEngine.jaccardTrigramSimilarity(source.stem, candidate.stem) >= MAX_SOURCE_SIMILARITY_FOR_TRANSFER) {
            return true
        }
        if (source.questionType == "MULTIPLE_CHOICE" && candidate.questionType == "MULTIPLE_CHOICE") {
            val sa = source.correctAnswerText
            val ca = candidate.correctAnswerText
            if (!sa.isNullOrBlank() && !ca.isNullOrBlank()) {
                val normSa = normalizeCompactText(sa)
                val normCa = normalizeCompactText(ca)
                if (normSa.length >= 14 && normSa == normCa) {
                    if (MetacognitiveEngine.jaccardTrigramSimilarity(source.stem, candidate.stem) >= 0.20) {
                        return true
                    }
                }
                if (MetacognitiveEngine.jaccardTrigramSimilarity("${source.stem} $sa", "${candidate.stem} $ca") >= MAX_COMBINED_STEM_ANSWER_SIMILARITY) {
                    return true
                }
            }
        }
        return false
    }

    private fun ruleOverlapRatio(
        source: QuestionAlignmentProfile,
        candidate: QuestionAlignmentProfile
    ): Double {
        if (source.legalProvisions.isNotEmpty() && candidate.legalProvisions.isNotEmpty()) {
            val shared = source.legalProvisions.any { sp ->
                candidate.legalProvisions.any { cp -> normalizeCompactText(sp) == normalizeCompactText(cp) }
            }
            if (shared) return 0.60
        }
        val srcTokens = domainContentTokens("${source.testedRule.orEmpty()} ${source.stem}")
        val candTokens = domainContentTokens("${candidate.testedRule.orEmpty()} ${candidate.stem}")
        if (srcTokens.isEmpty() || candTokens.isEmpty()) return 0.0
        val inter = srcTokens.intersect(candTokens).size.toDouble()
        val minLen = max(1, min(srcTokens.size, candTokens.size)).toDouble()
        return inter / minLen
    }

    fun evaluateCandidateAlignment(
        source: QuestionAlignmentProfile,
        candidate: QuestionAlignmentProfile,
        allowSeenReview: Boolean = false
    ): CandidateAlignmentDecision {
        if (source.questionId.isNotEmpty() && source.questionId == candidate.questionId) {
            return CandidateAlignmentDecision.Rejected("SAME_QUESTION_ID")
        }
        val exactContentDup = source.contentRevision.isNotEmpty() && source.contentRevision == candidate.contentRevision
        val exactSemanticDup = source.semanticRevision.isNotEmpty() && source.semanticRevision == candidate.semanticRevision
        if (exactContentDup || exactSemanticDup) {
            return if (allowSeenReview) {
                CandidateAlignmentDecision.Accepted(
                    mode = VerificationMode.INTENTIONAL_REVIEW,
                    framingReasonCode = "INTENTIONAL_RETENTION_REVIEW",
                    framingReasonPt = "Revisão intencional de retenção com material já conhecido pelo estudante.",
                    applicationBonus = 0
                )
            } else {
                CandidateAlignmentDecision.Rejected("DUPLICATE_CONTENT_OR_SEMANTIC_REVISION")
            }
        }
        if (detectParaphraseOrPreservedCues(source, candidate)) {
            return if (allowSeenReview) {
                CandidateAlignmentDecision.Accepted(
                    mode = VerificationMode.INTENTIONAL_REVIEW,
                    framingReasonCode = "INTENTIONAL_RETENTION_REVIEW",
                    framingReasonPt = "Revisão intencional aproveitando formulação próxima do item conhecido.",
                    applicationBonus = 0
                )
            } else {
                CandidateAlignmentDecision.Rejected("PARAPHRASE_OR_PRESERVED_ANSWER_CUE")
            }
        }
        if (candidate.isEditorialPendingOrDubious) {
            return CandidateAlignmentDecision.Rejected("CANDIDATE_EDITORIAL_PENDING_OR_DUBIOUS")
        }
        val appBonus = when {
            isDefinitionPrompt(source.stem) && isApplicationScenario(candidate.stem) -> 35
            isApplicationScenario(candidate.stem) -> 15
            else -> 0
        }
        if (source.targetConceptAmbiguous) {
            return CandidateAlignmentDecision.Accepted(
                mode = VerificationMode.TOPIC_PRACTICE,
                framingReasonCode = "TARGET_CONCEPT_AMBIGUOUS_TOPIC_PRACTICE",
                framingReasonPt = "A questão original abrange múltiplos conceitos sem alvo único inequívoco; item enquadrado como prática do tópico.",
                applicationBonus = 0
            )
        }
        val srcCid = source.targetConceptId
        if (!srcCid.isNullOrBlank()) {
            if (candidate.targetConceptAmbiguous) {
                return CandidateAlignmentDecision.Accepted(
                    mode = VerificationMode.TOPIC_PRACTICE,
                    framingReasonCode = "CANDIDATE_CONCEPT_AMBIGUOUS_TOPIC_PRACTICE",
                    framingReasonPt = "A questão candidata envolve mais de um conceito; enquadrada como prática do tópico.",
                    applicationBonus = 0
                )
            }
            val matchesConcept = candidate.targetConceptId == srcCid || srcCid in candidate.allConceptIds
            if (matchesConcept) {
                val overlap = ruleOverlapRatio(source, candidate)
                if (candidate.isAiUnconfirmedBinding &&
                    candidate.anchoredSourceQuestionId != source.questionId &&
                    overlap < 0.12
                ) {
                    return CandidateAlignmentDecision.Accepted(
                        mode = VerificationMode.TOPIC_PRACTICE,
                        framingReasonCode = "AI_UNCONFIRMED_CONCEPT_RULE_WEAK",
                        framingReasonPt = "Vínculo conceitual declarado apenas por IA sem sustentação suficiente da mesma regra decisiva; tratado como prática do tópico.",
                        applicationBonus = 0
                    )
                }
                return CandidateAlignmentDecision.Accepted(
                    mode = VerificationMode.CONCEPTUAL_TRANSFER,
                    framingReasonCode = "SAME_CONCEPT_AND_RULE_NEW_CONTEXT",
                    framingReasonPt = "Mesmo conceito-alvo e regra decisiva sustentados em situação distinta.",
                    applicationBonus = appBonus
                )
            }
            return CandidateAlignmentDecision.Accepted(
                mode = VerificationMode.TOPIC_PRACTICE,
                framingReasonCode = if (candidate.targetConceptId != null) {
                    "TOPIC_ONLY_CONCEPT_DIVERGENT"
                } else {
                    "TOPIC_ONLY_UNCONFIRMED_CONCEPT"
                },
                framingReasonPt = "O item pertence ao mesmo tópico, mas avalia conceito ou regra distinta da questão original; oferecido como prática do tópico.",
                applicationBonus = 0
            )
        }
        val overlap = ruleOverlapRatio(source, candidate)
        val isAnchored = source.questionId.isNotEmpty() && candidate.anchoredSourceQuestionId == source.questionId
        if (candidate.isAiUnconfirmedBinding && !isAnchored && overlap < 0.25) {
            return CandidateAlignmentDecision.Accepted(
                mode = VerificationMode.TOPIC_PRACTICE,
                framingReasonCode = "AI_SELF_DECLARED_WITHOUT_CONFIRMED_TARGET",
                framingReasonPt = "Declaração de IA sem conceito confirmado na questão original; mantida no nível de prática do tópico.",
                applicationBonus = 0
            )
        }
        if (isAnchored || overlap >= 0.22) {
            return CandidateAlignmentDecision.Accepted(
                mode = VerificationMode.CONCEPTUAL_TRANSFER,
                framingReasonCode = "SHARED_DECISIVE_RULE_NEW_CONTEXT",
                framingReasonPt = "Mesma regra decisiva identificada em contexto de aplicação distinto.",
                applicationBonus = appBonus
            )
        }
        return CandidateAlignmentDecision.Accepted(
            mode = VerificationMode.TOPIC_PRACTICE,
            framingReasonCode = "TOPIC_ONLY_RULE_DIVERGENT",
            framingReasonPt = "Apenas o tópico coincide e a regra decisiva diverge ou não é determinável; classificado como prática do tópico.",
            applicationBonus = 0
        )
    }
}

object OperationalCostCalculator {
    fun computeCostPerCompletedVerification(
        totalCostUsd: Double,
        completedVerificationsCount: Int,
        costIsEstimated: Boolean
    ): Triple<Double?, Double?, String> {
        val safeCost = if (totalCostUsd.isFinite()) max(0.0, totalCostUsd) else 0.0
        if (completedVerificationsCount <= 0) {
            return Triple(null, null, "sem conclusões")
        }
        val raw = safeCost / completedVerificationsCount.toDouble()
        val rounded4 = round4(raw)
        val suffix = if (costIsEstimated) " (estimado)" else ""
        return Triple(raw, rounded4, "US$ %.4f%s".format(java.util.Locale.US, rounded4, suffix))
    }
}
