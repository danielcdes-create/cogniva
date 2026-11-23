package br.com.concursoprep.app

import br.com.concursoprep.app.domain.exam.FatigueEngine
import br.com.concursoprep.app.domain.exam.PrioritizationEngine
import br.com.concursoprep.app.domain.exam.StrategyAction
import br.com.concursoprep.app.domain.exam.StrategyEngine
import br.com.concursoprep.app.domain.exam.StrategyPosture
import br.com.concursoprep.app.domain.model.EditorialCardDto
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceEnginesAndModelsTest {

    @Test
    fun interleaveBucketsFollows404020PatternDeduplicatesAndDrainsFallbacks() {
        assertTrue(
            PrioritizationEngine.interleaveBuckets(
                listOf("r1"),
                listOf("g1"),
                listOf("n1"),
                quantity = 0
            ) { it }.isEmpty()
        )

        val retention = listOf("r1", "r2", "shared", "r3")
        val gaps = listOf("g1", "shared", "g2")
        val fresh = listOf("n1", "n2")

        // Exact 5-slot cycle: RETENTION, GAP, RETENTION, GAP, NEW -> r1, g1, r2, shared (from gaps!), n1
        val five = PrioritizationEngine.interleaveBuckets(retention, gaps, fresh, quantity = 5) { it }
        assertEquals(listOf("r1", "g1", "r2", "shared", "n1"), five)

        // Requesting more than total unique items returns all 8 unique items without duplicates,
        // skipping "shared" on retention's 3rd cursor and falling back to fresh when retention/gaps exhaust
        val all = PrioritizationEngine.interleaveBuckets(retention, gaps, fresh, quantity = 20) { it }
        assertEquals(8, all.size)
        assertEquals(all.size, all.distinct().size)
        assertEquals(listOf("r1", "g1", "r2", "shared", "n1", "r3", "g2", "n2"), all)
    }

    @Test
    fun buildExamMomentScenariosGeneratesCanonicalMomentsAndReflectsCalibrationAndPenalty() {
        val uncalibrated = StrategyEngine.buildExamMomentScenarios(
            mediumP = 0.56,
            mediumSd = 0.08,
            penalty = 1.0,
            cutoffPct = 70.0,
            lambdaI = null
        )
        assertEquals(
            listOf("OPENING_35M", "MIDPOINT_165M", "PROTECT_275M", "RECOVERY_285M"),
            uncalibrated.map { it.scenarioId }
        )
        assertEquals(listOf(35, 165, 275, 285), uncalibrated.map { it.elapsedMinutes })
        assertTrue(uncalibrated.all { !it.calibratedLongitudinal })
        // 120 * 70% = 84.0 target, minimumScore = round(84 * 0.75) = 63.0
        assertEquals(84.0, uncalibrated.first().targetScore, 1e-9)
        assertEquals(63.0, uncalibrated.first().minimumScore, 1e-9)

        // PROTECT_275M (+1.5 above target, 4 items left): even with slightly positive EV (p = 0.51 -> EV = +0.02),
        // omitting protects the lead against -1.0 downside variance!
        val protectScenarios = StrategyEngine.buildExamMomentScenarios(
            mediumP = 0.51,
            mediumSd = 0.02,
            penalty = 1.0,
            cutoffPct = 65.0,
            lambdaI = 0.0
        )
        assertTrue(protectScenarios.all { it.calibratedLongitudinal })
        val protect = protectScenarios.first { it.scenarioId == "PROTECT_275M" }
        assertTrue(protect.decisionAtMediumConfidence.expectedValue > 0.0)
        assertEquals(StrategyAction.OMIT, protect.decisionAtMediumConfidence.recommendedAction)
        assertEquals(StrategyPosture.PROTECT_MARGIN, protect.decisionAtMediumConfidence.posture)

        // RECOVERY_285M (-1.0 below target, 3 items left): even with slightly negative EV (p = 0.49 -> EV = -0.02),
        // answering maximizes P(reaching goal) via calculated risk!
        val recoveryScenarios = StrategyEngine.buildExamMomentScenarios(
            mediumP = 0.49,
            mediumSd = 0.02,
            penalty = 1.0,
            cutoffPct = 65.0,
            lambdaI = 0.0
        )
        val recovery = recoveryScenarios.first { it.scenarioId == "RECOVERY_285M" }
        assertTrue(recovery.decisionAtMediumConfidence.expectedValue < 0.0)
        assertEquals(StrategyAction.ANSWER, recovery.decisionAtMediumConfidence.recommendedAction)
        assertEquals(StrategyPosture.CALCULATED_RISK, recovery.decisionAtMediumConfidence.posture)

        // Default targets when cutoffPct <= 0.0
        val defaultPenaltyTarget = StrategyEngine.buildExamMomentScenarios(0.6, 0.05, penalty = 1.0, cutoffPct = 0.0, lambdaI = 0.0)
        val defaultMcTarget = StrategyEngine.buildExamMomentScenarios(0.6, 0.05, penalty = 0.0, cutoffPct = 0.0, lambdaI = 0.0)
        assertEquals(66.0, defaultPenaltyTarget.first().targetScore, 1e-9)
        assertEquals(84.0, defaultMcTarget.first().targetScore, 1e-9)
    }

    @Test
    fun buildCurve300mProducesSixOrderedPointsMatchingFatigueDirection() {
        val decay = FatigueEngine.buildCurve300m(pBase = 0.75, lambdaI = 0.80)
        assertEquals(listOf(0, 60, 120, 180, 240, 300), decay.map { it.minute })
        for (i in 0 until decay.lastIndex) {
            assertTrue("Accuracy must strictly decrease when lambdaI > 0", decay[i].predictedAccuracy > decay[i + 1].predictedAccuracy)
        }

        val flat = FatigueEngine.buildCurve300m(pBase = 0.70, lambdaI = 0.0)
        assertTrue(flat.all { kotlin.math.abs(it.predictedAccuracy - flat.first().predictedAccuracy) < 1e-9 })

        val warmup = FatigueEngine.buildCurve300m(pBase = 0.65, lambdaI = -0.60)
        for (i in 0 until warmup.lastIndex) {
            assertTrue("Accuracy must strictly increase when lambdaI < 0", warmup[i].predictedAccuracy < warmup[i + 1].predictedAccuracy)
        }
    }

    @Test
    fun effectiveEditorialCardHonorsCuratedCardAndSynthesizesDeterministicProvisionsAndExceptions() {
        val curated = EditorialCardDto(
            legalProvisions = listOf("CF/88, Art. 37, XXI"),
            testedRule = "Regra curada editorialmente.",
            exceptionClause = "Ressalvados os casos especificados na legislação.",
            decisiveStemExcerpt = "ressalvados os casos"
        )
        val qWithCurated = QuestionDto(
            id = "q-curated",
            question = "Texto qualquer",
            answers = listOf("Certo", "Errado"),
            correctAnswerIndices = listOf(0),
            disciplina = "Direito Administrativo",
            editorialCard = curated
        )
        assertEquals(curated, qWithCurated.effectiveEditorialCard())

        // Synthesized TRUE_FALSE card with normative link, legal regex, and exception marker
        val qSynthesizedTf = QuestionDto(
            id = "q-synth-tf",
            question = "Conforme o Art. 53 da Lei 9.784/1999 e a Súmula 473, a Administração deve anular seus atos ilegais; salvo comprovada má-fé, decai em cinco anos o direito de anular.",
            questionType = QuestionType.TRUE_FALSE,
            answers = listOf("Certo", "Errado"),
            correctAnswerIndices = listOf(0),
            disciplina = "Direito Administrativo",
            canonicalSubjectId = "DIR_ADM",
            canonicalTopicId = "DIR_ADM_01_001",
            explanation = "A autotutela impõe anulação de atos ilegais, respeitada a decadência quinquenal salvo comprovada má-fé."
        )
        val tfCard = qSynthesizedTf.effectiveEditorialCard()
        assertTrue(tfCard.legalProvisions.isNotEmpty())
        assertNotNull(tfCard.exceptionClause)
        assertTrue(tfCard.exceptionClause!!.contains("salvo", ignoreCase = true))
        assertEquals(listOf("ERR_REGRA_EXCECAO"), tfCard.plausibleConceptualErrors.map { it.errorId })

        val tfChallenge = qSynthesizedTf.effectiveEvidenceChallenge()
        assertNotNull(tfChallenge)
        assertEquals("ev_decisive", tfChallenge!!.correctEvidenceId)
        assertTrue(tfChallenge.options.size >= 2)

        // Synthesized MULTIPLE_CHOICE card builds distractor error items excluding the correct index
        val qSynthesizedMc = QuestionDto(
            id = "q-synth-mc",
            question = "Nos termos da CF/88, qual é a regra aplicável apenas aos cargos efetivos?",
            questionType = QuestionType.MULTIPLE_CHOICE,
            answers = listOf("Livre nomeação", "Estabilidade após 3 anos de efetivo exercício", "Demissão ad nutum"),
            correctAnswerIndices = listOf(1),
            disciplina = "Direito Constitucional"
        )
        val mcCard = qSynthesizedMc.effectiveEditorialCard()
        assertTrue(mcCard.legalProvisions.any { it.contains("CF/88", ignoreCase = true) })
        assertEquals(listOf("ERR_ALT_1", "ERR_ALT_3"), mcCard.plausibleConceptualErrors.map { it.errorId })
    }
}
