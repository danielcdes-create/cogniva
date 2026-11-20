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
        val r = input.retrievability.coerceIn(0.0, 1.0)
        val evFactor = min(15, max(0, input.distinctQuestions)).toDouble() / 15.0
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
        val p = pAlternative.coerceIn(0.0, 1.0)
        return round9(max(p, 1.0 - p))
    }

    /**
     * CDF da distribuição normal padrão via aproximação racional Abramowitz & Stegun 7.1.26,
     * idêntica bit a bit entre Python, Rust e Kotlin.
     */
    fun normalCdf(z: Double): Double {
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
        val goal = max(input.targetScore, input.minimumScore)
        val sdU = input.uncertaintySd.coerceIn(0.0, 0.35)

        val pf = input.baselineFutureP.coerceIn(0.10, 0.95)
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

        val probOmit = continuationProb(input.currentNetScore, rem - 1)
        val pMinus = (pt - sdU).coerceIn(0.01, 0.99)
        val pPlus = (pt + sdU).coerceIn(0.01, 0.99)
        val probHit = continuationProb(input.currentNetScore + 1.0, rem - 1)
        val probMiss = continuationProb(input.currentNetScore - pen, rem - 1)
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
    fun sigmoid(z: Double): Double =
        if (z >= 0.0) {
            val ez = exp(-z)
            1.0 / (1.0 + ez)
        } else {
            val ez = exp(z)
            ez / (1.0 + ez)
        }

    fun logit(p: Double): Double {
        val c = p.coerceIn(0.02, 0.98)
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
        val d = itemDifficulty.coerceIn(0.0, 1.0)
        val t = elapsedMinutes.coerceIn(0.0, 300.0)
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

        val dEarly = earlyAvgDifficulty.coerceIn(0.0, 1.0)
        val dLate = lateAvgDifficulty.coerceIn(0.0, 1.0)
        val uEarly = (earlyMeanMinutes / 300.0).coerceIn(0.0, 1.0)
        val uLate = (lateMeanMinutes / 300.0).coerceIn(uEarly + 0.15, 1.0)

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
