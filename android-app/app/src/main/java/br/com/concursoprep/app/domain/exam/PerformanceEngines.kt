package br.com.concursoprep.app.domain.exam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun rustRound(value: Double): Double =
    if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)

private fun round6(value: Double): Double =
    rustRound(value * 1_000_000.0) / 1_000_000.0

private fun round9(value: Double): Double =
    rustRound(value * 1_000_000_000.0) / 1_000_000_000.0

@Serializable
enum class DiagnosticStatus {
    @SerialName("INSUFFICIENT_EVIDENCE") INSUFFICIENT_EVIDENCE,
    @SerialName("OVERCONFIDENCE_ERROR") OVERCONFIDENCE_ERROR,
    @SerialName("CONCEPTUAL_ERROR") CONCEPTUAL_ERROR,
    @SerialName("RETRIEVAL_FAILURE") RETRIEVAL_FAILURE,
    @SerialName("VERIFIED_COMPETENCE") VERIFIED_COMPETENCE
}

data class DiagnosticInputs(
    val distinctQuestions: Int,
    val nonBlankAttempts: Int,
    val correctAttempts: Int,
    val lastIsCorrect: Boolean,
    val lastConfidence: Int,
    val hadPriorVerifiedCorrect: Boolean,
    val hasSpacedRetention: Boolean,
    val evidenceChallengePassed: Boolean? = null
)

object DiagnosticEngine {
    fun resolveUnitId(conceptId: String?, topicId: String): String {
        val cleaned = conceptId?.trim()?.takeIf { it.isNotEmpty() }
        return cleaned ?: topicId.trim()
    }

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
        val acc = input.correctAttempts.toDouble() / input.nonBlankAttempts.toDouble()
        return if (
            input.distinctQuestions >= 3 &&
            input.hasSpacedRetention &&
            acc >= 0.80 - 1e-9 &&
            input.lastConfidence >= 2
        ) {
            DiagnosticStatus.VERIFIED_COMPETENCE
        } else {
            DiagnosticStatus.INSUFFICIENT_EVIDENCE
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
        if (stabilityDays <= 0.0 || !stabilityDays.isFinite()) return 0.0
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
        val d = if (priorStability <= 0.0) 5.0 else priorDifficulty.coerceIn(1.0, 10.0)
        val s = max(0.0, priorStability)

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
            val slow = expectedSeconds > 0.0 && responseSeconds.toDouble() > 1.5 * expectedSeconds
            val g = if (confidence >= 3 && !slow) 1.0 else 0.6
            val dn = (d - 0.3 * g + 0.15 * (1.0 - g)).coerceIn(1.0, 10.0)
            val sn = if (s <= 0.0) {
                if (g >= 1.0) 4.0 else 2.0
            } else {
                val r = retrievability(elapsedDays, s)
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

enum class AdaptiveSlotKind {
    RETENTION_REVIEW,
    PERSISTENT_GAP,
    NEW_HIGH_IMPACT
}

object PrioritizationEngine {
    fun priorityUtility(input: PrioritizationInputs): Double {
        val r = input.retrievability.coerceIn(0.0, 1.0)
        val evFactor = min(3, max(0, input.distinctQuestions)).toDouble() / 3.0
        val mEff = if (input.verifiedCompetence) {
            0.85 * r + 0.15
        } else {
            0.65 * r * evFactor
        }
        val gap = (1.0 - mEff).coerceIn(0.05, 1.0)
        val tauMin = input.avgSeconds.coerceIn(20.0, 300.0) / 60.0
        val prereqMult = if (input.unmetPrerequisitesCount <= 0) {
            1.0
        } else {
            0.55.pow(min(3, input.unmetPrerequisitesCount))
        }
        val downstreamBoost = 1.0 + 0.15 * min(4, max(0, input.dependentCount)).toDouble()
        val raw = (input.editalWeight * input.incidenceWeight * gap / tauMin) * prereqMult * downstreamBoost
        return round9(raw)
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
        var idxRet = 0
        var idxGap = 0
        var idxNew = 0
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
        // suppress unused local vars warning cleanly
        idxRet = cRet[0]; idxGap = cGap[0]; idxNew = cNew[0]
        if (idxRet < 0 || idxGap < 0 || idxNew < 0) return emptyList()
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

object CalibrationEngine {
    fun calibratedProbability(
        questionType: String,
        confidenceBand: Int,
        correct: Int,
        attempts: Int
    ): CalibrationCellDto {
        val isTf = questionType.equals("TRUE_FALSE", ignoreCase = true)
        val mu0 = when {
            confidenceBand <= 1 -> if (isTf) 0.50 else 0.20
            confidenceBand == 2 -> if (isTf) 0.65 else 0.45
            else -> 0.85
        }
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
        return CalibrationCellDto(
            questionType = if (isTf) "TRUE_FALSE" else "MULTIPLE_CHOICE",
            confidenceBand = confidenceBand.coerceIn(1, 3),
            correct = safeCorrect,
            attempts = safeAttempts,
            calibratedMean = mean,
            credibleSd = sd,
            sufficientSample = safeAttempts >= 10
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

object StrategyEngine {
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

    fun evaluateStrategy(
        pCalibrated: Double,
        penalty: Double,
        cutoffPct: Double,
        currentProjectedPct: Double,
        remainingFraction: Double
    ): StrategyDecision {
        val p = pCalibrated.coerceIn(0.0, 1.0)
        val lam = if (penalty.isFinite()) max(0.0, penalty) else 0.0
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
        val (eff, posture) = if (cutoffPct > 0.0 && remainingFraction > 0.0 && remainingFraction <= 0.35) {
            val margin = currentProjectedPct - cutoffPct
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
    @SerialName("STABLE_ENDURANCE") STABLE_ENDURANCE
}

@Serializable
data class FatigueReportDto(
    val status: FatigueStatus,
    @SerialName("qualifying_sessions") val qualifyingSessions: Int,
    @SerialName("required_sessions") val requiredSessions: Int = 3,
    @SerialName("qualifying_attempts") val qualifyingAttempts: Int,
    @SerialName("required_attempts") val requiredAttempts: Int = 120,
    @SerialName("shrunk_accuracy_delta") val shrunkAccuracyDelta: Double? = null
)

object FatigueEngine {
    fun evaluateFatigue(
        qualifyingSessions: Int,
        earlyCorrect: Int,
        earlyTotal: Int,
        lateCorrect: Int,
        lateTotal: Int
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
                shrunkAccuracyDelta = null
            )
        }
        val pEarly = earlyCorrect.coerceIn(0, sEarlyTotal).toDouble() / sEarlyTotal.toDouble()
        val pLate = lateCorrect.coerceIn(0, sLateTotal).toDouble() / sLateTotal.toDouble()
        val delta = pLate - pEarly
        val se = sqrt((pEarly * (1.0 - pEarly)) / sEarlyTotal.toDouble() + (pLate * (1.0 - pLate)) / sLateTotal.toDouble())
        val kappa = sLateTotal.toDouble() / (sLateTotal.toDouble() + 40.0)
        val shrunk = round9(kappa * delta)
        val status = if (delta < -0.05 && (delta + 1.645 * se) < 0.0) {
            FatigueStatus.FATIGUE_DETECTED
        } else {
            FatigueStatus.STABLE_ENDURANCE
        }
        return FatigueReportDto(
            status = status,
            qualifyingSessions = qualifyingSessions,
            requiredSessions = 3,
            qualifyingAttempts = total,
            requiredAttempts = 120,
            shrunkAccuracyDelta = shrunk
        )
    }
}
