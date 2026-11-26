package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.QuestionCacheMapping
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SharedDomainContractTest {
    private val fixture = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/domain-contracts.json"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    ).jsonObject

    private fun question(): QuestionDto {
        val q = fixture.getValue("question").jsonObject
        return QuestionDto(
            id = "fixture",
            question = q.getValue("question").jsonPrimitive.content,
            answers = q.getValue("answers").jsonArray.map { it.jsonPrimitive.content },
            correctAnswerIndices = listOf(q.getValue("correct_index").jsonPrimitive.int),
            disciplina = "Fixture",
            source = "Fixture",
            level = "Fixture"
        )
    }

    @Test
    fun bothRuntimesUseTheSameUtf8RevisionAndMasteryPolicy() {
        assertEquals(5, fixture.getValue("contract_version").jsonPrimitive.int)
        assertEquals(
            DiagnosticEngine.MIN_QUESTIONS_FOR_MASTERY,
            fixture.getValue("min_questions_for_mastery").jsonPrimitive.int
        )
        assertEquals(
            fixture.getValue("question").jsonObject.getValue("revision").jsonPrimitive.content,
            QuestionCacheMapping.withRevision(question()).questionRevision
        )
        fixture.getValue("mastery").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            assertEquals(
                v.getValue("expected").jsonPrimitive.double,
                ExamEngine.masteryFraction(
                    v.getValue("accuracy").jsonPrimitive.double,
                    v.getValue("mastered").jsonPrimitive.boolean,
                    v.getValue("high_confidence_error").jsonPrimitive.boolean,
                    v["confidence"]?.jsonPrimitive?.intOrNull
                ),
                1e-9
            )
        }
    }

    @Test
    fun weightedPointsAndCalendarDaysMatchTheSharedContract() {
        fixture.getValue("scores").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val outcomes = v.getValue("outcomes").jsonArray.map { item ->
                val o = item.jsonObject
                SubjectOutcome(
                    o.getValue("weight").jsonPrimitive.double,
                    o.getValue("correct").jsonPrimitive.int,
                    o.getValue("drawn").jsonPrimitive.int,
                    o.getValue("wrong").jsonPrimitive.int,
                    o.getValue("penalty").jsonPrimitive.double
                )
            }
            assertEquals(v.getValue("expected").jsonPrimitive.double, ExamEngine.weightedNetScore(outcomes), 1e-9)
        }
        fixture.getValue("study_days").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            assertEquals(
                v.getValue("day").jsonPrimitive.content,
                Instant.parse(v.getValue("instant").jsonPrimitive.content)
                    .atOffset(LearningProjection.studyZone)
                    .toLocalDate()
                    .toString()
            )
        }
    }

    @Test
    fun occurrenceOrderedSrsAndConfidenceMatchTheSharedContract() {
        fixture.getValue("srs").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val events = v.getValue("statuses").jsonArray.mapIndexed { index, status ->
                LearningEvent(
                    "e$index",
                    Instant.parse("2026-10-01T12:00:00Z").plusSeconds(index * 86400L).toString(),
                    status.jsonPrimitive.boolean,
                    v.getValue("confidence").jsonPrimitive.int,
                    42
                )
            }
            val result = LearningProjection.project(null, question(), events.reversed())
            assertEquals(v.getValue("points").jsonPrimitive.double, result.points, 1e-9)
            assertEquals(v.getValue("mastered").jsonPrimitive.boolean, result.isMastered)
            assertEquals(
                v.getValue("interval").jsonPrimitive.long,
                (Instant.parse(result.nextReviewAt).epochSecond - Instant.parse(events.last().occurredAt).epochSecond) / 86400
            )
        }
    }

    @Test
    fun allDecisionEnginesScarcityTransferAnd300mCurveMatchSharedContractV5() {
        assertEquals(0.52, StrategyEngine.normalizeBinaryChoiceProbability(0.48), 1e-9)
        assertEquals(0.52, StrategyEngine.normalizeBinaryChoiceProbability(0.52), 1e-9)

        fixture.getValue("entry_60s").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            assertEquals(
                v.getValue("expected_header").jsonPrimitive.content,
                PrioritizationEngine.formatEntry60sHeader(Instant.parse(v.getValue("instant").jsonPrimitive.content))
            )
        }

        fixture.getValue("holdout").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            assertEquals(
                v.getValue("reserved").jsonPrimitive.boolean,
                StrategyEngine.isHoldoutReserved(
                    userId = v.getValue("user_id").jsonPrimitive.long,
                    questionId = v.getValue("question_id").jsonPrimitive.content,
                    poolSize = v.getValue("pool_size").jsonPrimitive.int
                )
            )
        }

        fixture.getValue("diagnostics").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val actual = DiagnosticEngine.classifyDiagnostic(
                DiagnosticInputs(
                    distinctQuestions = v.getValue("distinct_questions").jsonPrimitive.int,
                    nonBlankAttempts = v.getValue("non_blank_attempts").jsonPrimitive.int,
                    correctAttempts = v.getValue("correct_attempts").jsonPrimitive.int,
                    lastIsCorrect = v.getValue("last_is_correct").jsonPrimitive.boolean,
                    lastConfidence = v.getValue("last_confidence").jsonPrimitive.int,
                    hadPriorVerifiedCorrect = v.getValue("had_prior_verified_correct").jsonPrimitive.boolean,
                    hasSpacedRetention = v.getValue("has_spaced_retention").jsonPrimitive.boolean,
                    evidenceChallengePassed = v["evidence_challenge_passed"]?.jsonPrimitive?.booleanOrNull,
                    hasIndependentTransfer = v["has_independent_transfer"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            )
            assertEquals(v.getValue("expected").jsonPrimitive.content, actual.name)
        }

        fixture.getValue("transfer").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val actual = DiagnosticEngine.evaluateTransferStatus(
                TransferInputs(
                    distinctQuestions = v.getValue("distinct_questions").jsonPrimitive.int,
                    availableCrossBoardQuestions = v.getValue("available_cross_board_questions").jsonPrimitive.int,
                    transferAttempts = v.getValue("transfer_attempts").jsonPrimitive.int,
                    transferCorrectGrounded = v.getValue("transfer_correct_grounded").jsonPrimitive.int
                )
            )
            assertEquals(v.getValue("expected").jsonPrimitive.content, actual.name)
        }

        fixture.getValue("retention").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val priorS = v.getValue("prior_stability").jsonPrimitive.double
            val elapsed = v.getValue("elapsed_days").jsonPrimitive.double
            assertEquals(
                v.getValue("retrievability_at_elapsed").jsonPrimitive.double,
                RetentionEngine.retrievability(elapsed, priorS),
                1e-6
            )
            val updated = RetentionEngine.updateRetention(
                priorStability = priorS,
                priorDifficulty = v.getValue("prior_difficulty").jsonPrimitive.double,
                isCorrect = v.getValue("is_correct").jsonPrimitive.boolean,
                confidence = v.getValue("confidence").jsonPrimitive.int,
                elapsedDays = elapsed,
                responseSeconds = v.getValue("response_seconds").jsonPrimitive.int,
                expectedSeconds = v.getValue("expected_seconds").jsonPrimitive.double
            )
            assertEquals(v.getValue("next_stability").jsonPrimitive.double, updated.nextStability, 1e-6)
            assertEquals(v.getValue("next_difficulty").jsonPrimitive.double, updated.nextDifficulty, 1e-6)
            assertEquals(v.getValue("next_interval_days").jsonPrimitive.int, updated.nextIntervalDays)
        }

        fixture.getValue("prioritization").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val actual = PrioritizationEngine.priorityUtility(
                PrioritizationInputs(
                    editalWeight = v.getValue("edital_weight").jsonPrimitive.double,
                    incidenceWeight = v.getValue("incidence_weight").jsonPrimitive.double,
                    retrievability = v.getValue("retrievability").jsonPrimitive.double,
                    verifiedCompetence = v.getValue("verified_competence").jsonPrimitive.boolean,
                    distinctQuestions = v.getValue("distinct_questions").jsonPrimitive.int,
                    avgSeconds = v.getValue("avg_seconds").jsonPrimitive.double,
                    unmetPrerequisitesCount = v.getValue("unmet_prerequisites_count").jsonPrimitive.int,
                    dependentCount = v.getValue("dependent_count").jsonPrimitive.int
                )
            )
            assertEquals(v.getValue("expected_utility").jsonPrimitive.double, actual, 1e-9)
        }

        fixture.getValue("calibration").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val cell = CalibrationEngine.calibratedProbability(
                questionType = v.getValue("question_type").jsonPrimitive.content,
                confidenceBand = v.getValue("confidence_band").jsonPrimitive.int,
                correct = v.getValue("correct").jsonPrimitive.int,
                attempts = v.getValue("attempts").jsonPrimitive.int
            )
            assertEquals(v.getValue("expected_mean").jsonPrimitive.double, cell.calibratedMean, 1e-9)
            assertEquals(v.getValue("expected_sd").jsonPrimitive.double, cell.credibleSd, 1e-9)
        }

        fixture.getValue("performance_300m").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val (p0, pt) = FatigueEngine.performanceAtMinute(
                pBase = v.getValue("p_base").jsonPrimitive.double,
                itemDifficulty = v.getValue("item_difficulty").jsonPrimitive.double,
                lambdaI = v.getValue("lambda_i").jsonPrimitive.double,
                elapsedMinutes = v.getValue("elapsed_minutes").jsonPrimitive.double
            )
            assertEquals(v.getValue("expected_p0").jsonPrimitive.double, p0, 1e-9)
            assertEquals(v.getValue("expected_pt").jsonPrimitive.double, pt, 1e-9)
        }

        fixture.getValue("decision_uncertainty").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val dec = StrategyEngine.simulateDecisionUnderUncertainty(
                UncertaintyDecisionInputs(
                    pBase = v.getValue("p_base").jsonPrimitive.double,
                    uncertaintySd = v.getValue("uncertainty_sd").jsonPrimitive.double,
                    penalty = v.getValue("penalty").jsonPrimitive.double,
                    itemDifficulty = v.getValue("item_difficulty").jsonPrimitive.double,
                    lambdaI = v.getValue("lambda_i").jsonPrimitive.double,
                    elapsedMinutes = v.getValue("elapsed_minutes").jsonPrimitive.double,
                    currentNetScore = v.getValue("current_net_score").jsonPrimitive.double,
                    remainingItems = v.getValue("remaining_items").jsonPrimitive.int,
                    targetScore = v.getValue("target_score").jsonPrimitive.double,
                    minimumScore = v.getValue("minimum_score").jsonPrimitive.double,
                    baselineFutureP = v.getValue("baseline_future_p").jsonPrimitive.double
                )
            )
            assertEquals(v.getValue("expected_p0").jsonPrimitive.double, dec.p0, 1e-9)
            assertEquals(v.getValue("expected_pt").jsonPrimitive.double, dec.pAtTime, 1e-9)
            assertEquals(v.getValue("expected_ev").jsonPrimitive.double, dec.expectedValue, 1e-9)
            assertEquals(v.getValue("expected_p_star").jsonPrimitive.double, dec.riskNeutralThreshold, 1e-9)
            assertEquals(v.getValue("expected_prob_goal_if_answer").jsonPrimitive.double, dec.probGoalIfAnswer, 1e-9)
            assertEquals(v.getValue("expected_prob_goal_if_omit").jsonPrimitive.double, dec.probGoalIfOmit, 1e-9)
            assertEquals(v.getValue("expected_posture").jsonPrimitive.content, dec.posture.name)
            assertEquals(v.getValue("expected_action").jsonPrimitive.content, dec.recommendedAction.name)
        }

        fixture.getValue("strategy").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val dec = StrategyEngine.evaluateStrategy(
                pCalibrated = v.getValue("p_calibrated").jsonPrimitive.double,
                penalty = v.getValue("penalty").jsonPrimitive.double,
                cutoffPct = v.getValue("cutoff_pct").jsonPrimitive.double,
                currentProjectedPct = v.getValue("current_projected_pct").jsonPrimitive.double,
                remainingFraction = v.getValue("remaining_fraction").jsonPrimitive.double
            )
            assertEquals(v.getValue("expected_ev").jsonPrimitive.double, dec.expectedValue, 1e-9)
            assertEquals(v.getValue("expected_p_star").jsonPrimitive.double, dec.riskNeutralThreshold, 1e-9)
            assertEquals(v.getValue("expected_effective_threshold").jsonPrimitive.double, dec.effectiveThreshold, 1e-9)
            assertEquals(v.getValue("expected_posture").jsonPrimitive.content, dec.posture.name)
            assertEquals(v.getValue("expected_action").jsonPrimitive.content, dec.recommendedAction.name)
        }

        fixture.getValue("fatigue").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val report = FatigueEngine.evaluateFatigueWithDifficulty(
                qualifyingSessions = v.getValue("qualifying_sessions").jsonPrimitive.int,
                earlyCorrect = v.getValue("early_correct").jsonPrimitive.int,
                earlyTotal = v.getValue("early_total").jsonPrimitive.int,
                lateCorrect = v.getValue("late_correct").jsonPrimitive.int,
                lateTotal = v.getValue("late_total").jsonPrimitive.int,
                earlyAvgDifficulty = v.getValue("early_avg_difficulty").jsonPrimitive.double,
                lateAvgDifficulty = v.getValue("late_avg_difficulty").jsonPrimitive.double,
                earlyMeanMinutes = v.getValue("early_mean_minutes").jsonPrimitive.double,
                lateMeanMinutes = v.getValue("late_mean_minutes").jsonPrimitive.double
            )
            assertEquals(v.getValue("expected_status").jsonPrimitive.content, report.status.name)
            val expectedLambda = v["expected_lambda"]?.jsonPrimitive?.doubleOrNull
            if (expectedLambda == null) {
                assertNull(report.estimatedLambda)
            } else {
                assertEquals(expectedLambda, report.estimatedLambda!!, 1e-9)
            }
            val expectedDelta = v["expected_shrunk_delta"]?.jsonPrimitive?.doubleOrNull
            if (expectedDelta == null) {
                assertNull(report.shrunkAccuracyDelta)
            } else {
                assertEquals(expectedDelta, report.shrunkAccuracyDelta!!, 1e-9)
            }
        }
    }

    @Test
    fun metacognitiveScalesMatrixConceptMasteryAndReuseSimulatorMatchSharedContract() {
        val scales = fixture.getValue("metacognitive_scales").jsonObject
        val confArr = scales.getValue("declared_confidence").jsonArray
        assertEquals(DeclaredConfidence.entries.size, confArr.size)
        confArr.forEachIndexed { idx, raw ->
            val v = raw.jsonObject
            val item = DeclaredConfidence.entries[idx]
            assertEquals(v.getValue("code").jsonPrimitive.content, item.name)
            assertEquals(v.getValue("ordinal").jsonPrimitive.int, item.ordinalLevel)
            assertEquals(v.getValue("legacy_band").jsonPrimitive.int, item.toLegacyBand())
            assertEquals(v.getValue("label_pt").jsonPrimitive.content, item.labelPt)
        }

        val stratArr = scales.getValue("cognitive_strategies").jsonArray
        assertEquals(CognitiveStrategy.entries.size, stratArr.size)
        stratArr.forEachIndexed { idx, raw ->
            val v = raw.jsonObject
            val item = CognitiveStrategy.entries[idx]
            assertEquals(v.getValue("code").jsonPrimitive.content, item.name)
            assertEquals(v.getValue("label_pt").jsonPrimitive.content, item.labelPt)
        }

        fixture.getValue("metacognitive_matrix").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val declConf = v["declared_confidence"]?.jsonPrimitive?.contentOrNull?.let(DeclaredConfidence::valueOf)
            val cogStrat = v["cognitive_strategy"]?.jsonPrimitive?.contentOrNull?.let(CognitiveStrategy::valueOf)
            val inputs = MetacognitiveMatrixInputs(
                isBlank = v.getValue("is_blank").jsonPrimitive.boolean,
                isCorrect = v.getValue("is_correct").jsonPrimitive.boolean,
                declaredConfidence = declConf,
                cognitiveStrategy = cogStrat,
                consistentHistory = v.getValue("consistent_history").jsonPrimitive.boolean,
                rationaleContradicted = v.getValue("rationale_contradicted").jsonPrimitive.boolean
            )
            val (diag, sup, pri) = MetacognitiveEngine.classifyPreliminaryMatrix(inputs)
            assertEquals(v.getValue("expected_diagnosis").jsonPrimitive.content, diag.name)
            assertEquals(v.getValue("expected_support").jsonPrimitive.content, sup.name)
            assertEquals(v.getValue("expected_ctv_priority").jsonPrimitive.content, pri.name)
            val assessment = MetacognitiveEngine.buildAssessment(inputs)
            assertFalse(assessment.systemInferenceMessage.contains("Competência ilusória detectada"))
        }

        fixture.getValue("concept_mastery_states").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val eval = ConceptMasteryEngine.classifyConceptMastery(
                ConceptMasteryInputs(
                    attempts = v.getValue("attempts").jsonPrimitive.int,
                    distinctQuestions = v.getValue("distinct_questions").jsonPrimitive.int,
                    accuracy = v.getValue("accuracy").jsonPrimitive.double,
                    groundedRate = v.getValue("grounded_rate").jsonPrimitive.double,
                    highConfErrorRate = v.getValue("high_conf_error_rate").jsonPrimitive.double,
                    transferAttempts = v.getValue("transfer_attempts").jsonPrimitive.int,
                    transferSuccessRate = v.getValue("transfer_success_rate").jsonPrimitive.double,
                    hasSpacedRetention = v.getValue("has_spaced_retention").jsonPrimitive.boolean,
                    retrievability = v.getValue("retrievability").jsonPrimitive.double,
                    consistency = v.getValue("consistency").jsonPrimitive.double
                )
            )
            assertEquals(v.getValue("expected_state").jsonPrimitive.content, eval.state.name)
        }

        fixture.getValue("ai_reuse_simulator").jsonArray.forEach { raw ->
            val v = raw.jsonObject
            val sim = ReuseEconomySimulator.simulateAiReuseEconomy(
                monthlyVerifications = v.getValue("monthly_verifications").jsonPrimitive.long,
                reuseRatePct = v.getValue("reuse_rate_pct").jsonPrimitive.double,
                avgCostPerGeneration = v.getValue("avg_cost_per_generation").jsonPrimitive.double
            )
            assertEquals(v.getValue("expected_cost_without_reuse").jsonPrimitive.double, sim.costWithoutReuse, 1e-6)
            assertEquals(v.getValue("expected_cost_with_reuse").jsonPrimitive.double, sim.costWithReuse, 1e-6)
            assertEquals(v.getValue("expected_monthly_savings").jsonPrimitive.double, sim.monthlySavings, 1e-6)
            assertEquals(v.getValue("expected_avoided_generations").jsonPrimitive.long, sim.avoidedGenerationsMonthly)
            assertEquals(v.getValue("expected_annual_savings").jsonPrimitive.double, sim.annualSavings, 1e-6)
            assertEquals(v.getValue("expected_cost_reduction_pct").jsonPrimitive.double, sim.costReductionPct, 1e-6)
            assertTrue(sim.disclaimerPt.contains("hipotética"))
        }
    }

    @Test
    fun ctvAlignmentIndependenceGroundingWalkforwardPriorityAndCostMatchSharedContract() {
        val alignSec = fixture.getValue("ctv_alignment_vectors").jsonObject
        val srcV = alignSec.getValue("source").jsonObject
        val srcTid = srcV["target_concept_id"]?.jsonPrimitive?.contentOrNull
        val srcProf = QuestionAlignmentProfile(
            questionId = srcV.getValue("question_id").jsonPrimitive.content,
            subjectId = srcV["subject_id"]?.jsonPrimitive?.contentOrNull,
            topicId = srcV["topic_id"]?.jsonPrimitive?.contentOrNull,
            targetConceptId = srcTid,
            allConceptIds = listOfNotNull(srcTid),
            testedRule = srcV["tested_rule"]?.jsonPrimitive?.contentOrNull,
            questionType = srcV.getValue("question_type").jsonPrimitive.content,
            stem = srcV.getValue("stem").jsonPrimitive.content,
            correctAnswerText = srcV["correct_answer_text"]?.jsonPrimitive?.contentOrNull,
            contentRevision = srcV.getValue("content_revision").jsonPrimitive.content
        )
        alignSec.getValue("candidates").jsonArray.forEach { raw ->
            val cv = raw.jsonObject
            val cTid = cv["target_concept_id"]?.jsonPrimitive?.contentOrNull
            val candProf = QuestionAlignmentProfile(
                questionId = cv.getValue("id").jsonPrimitive.content,
                subjectId = srcProf.subjectId,
                topicId = srcProf.topicId,
                targetConceptId = cTid,
                allConceptIds = listOfNotNull(cTid),
                testedRule = cv["tested_rule"]?.jsonPrimitive?.contentOrNull,
                questionType = cv.getValue("question_type").jsonPrimitive.content,
                stem = cv.getValue("stem").jsonPrimitive.content,
                correctAnswerText = cv["correct_answer_text"]?.jsonPrimitive?.contentOrNull,
                contentRevision = cv.getValue("content_revision").jsonPrimitive.content
            )
            val dec = CtvAlignmentEngine.evaluateCandidateAlignment(srcProf, candProf, allowSeenReview = false)
            if (cv.getValue("expected_accepted").jsonPrimitive.boolean) {
                assertTrue("Expected Accepted for ${candProf.questionId}, got $dec", dec is CandidateAlignmentDecision.Accepted)
                val acc = dec as CandidateAlignmentDecision.Accepted
                assertEquals(cv.getValue("expected_mode").jsonPrimitive.content, acc.mode.code)
                assertEquals(cv.getValue("expected_reason_code").jsonPrimitive.content, acc.framingReasonCode)
                assertEquals(cv.getValue("expected_application_bonus").jsonPrimitive.int, acc.applicationBonus)
            } else {
                assertTrue("Expected Rejected for ${candProf.questionId}, got $dec", dec is CandidateAlignmentDecision.Rejected)
                val rej = dec as CandidateAlignmentDecision.Rejected
                assertEquals(cv.getValue("expected_reason_code").jsonPrimitive.content, rej.reasonCode)
            }
        }

        fixture.getValue("ctv_independence_vectors").jsonArray.forEach { raw ->
            val iv = raw.jsonObject
            val verdict = CtvAlignmentEngine.evaluateCtvIndependence(
                CtvIndependenceInput(
                    verificationMode = VerificationMode.fromCode(iv.getValue("verification_mode").jsonPrimitive.content),
                    action = iv.getValue("action").jsonPrimitive.content,
                    verificationTiming = iv.getValue("verification_timing").jsonPrimitive.content,
                    wasPreviouslyExposed = iv.getValue("was_previously_exposed").jsonPrimitive.boolean,
                    exposureHistoryKnown = iv.getValue("exposure_history_known").jsonPrimitive.boolean,
                    hadDecisiveHelp = iv.getValue("had_decisive_help").jsonPrimitive.boolean,
                    isCorrect = iv.getValue("is_correct").jsonPrimitive.boolean
                )
            )
            assertEquals(iv.getValue("expected_attempts_delta").jsonPrimitive.int, verdict.independentAttemptsDelta)
            assertEquals(iv.getValue("expected_correct_delta").jsonPrimitive.int, verdict.independentCorrectDelta)
            assertEquals(iv.getValue("expected_code").jsonPrimitive.content, verdict.classificationCode)
        }

        fixture.getValue("hit_grounding_vectors").jsonArray.forEach { raw ->
            val gv = raw.jsonObject
            val dc = gv["declared_confidence"]?.jsonPrimitive?.contentOrNull?.let(DeclaredConfidence::valueOf)
            val cs = gv["cognitive_strategy"]?.jsonPrimitive?.contentOrNull?.let(CognitiveStrategy::valueOf)
            val evCompat = gv["evidence_compatible"]?.jsonPrimitive?.booleanOrNull
            val cat = MetacognitiveEngine.classifyHitGrounding(
                isCorrect = gv.getValue("is_correct").jsonPrimitive.boolean,
                declaredConfidence = dc,
                cognitiveStrategy = cs,
                evidenceCompatible = evCompat
            )
            assertEquals(gv.getValue("expected_category").jsonPrimitive.content, cat.name)
            assertEquals(gv.getValue("expected_credits_comprehension").jsonPrimitive.boolean, cat.creditsComprehension)
            assertEquals(gv.getValue("expected_is_evaluable").jsonPrimitive.boolean, cat.isEvaluable)
        }

        val wfSec = fixture.getValue("walkforward_brier_vectors").jsonObject
        assertEquals(wfSec.getValue("policy_version").jsonPrimitive.content, CalibrationEngine.CALIBRATION_POLICY_VERSION)
        val refPairs = wfSec.getValue("reference_pairs").jsonArray.map { el ->
            val obj = el.jsonObject
            obj.getValue("p").jsonPrimitive.double to obj.getValue("y").jsonPrimitive.boolean
        }
        val refBrier = checkNotNull(CalibrationEngine.computeBrierScore(refPairs))
        assertEquals(wfSec.getValue("expected_reference_brier").jsonPrimitive.double, refBrier, 1e-12)

        val wfSeq = wfSec.getValue("sequence").jsonArray
        val wfEvents = wfSeq.map { el ->
            val ev = el.jsonObject
            WalkforwardCalibrationInput(
                attemptId = ev.getValue("attempt_id").jsonPrimitive.content,
                occurredAtMs = ev.getValue("occurred_at_ms").jsonPrimitive.long,
                questionType = ev.getValue("question_type").jsonPrimitive.content,
                declaredConfidence = DeclaredConfidence.valueOf(ev.getValue("declared_confidence").jsonPrimitive.content),
                isCorrect = ev.getValue("is_correct").jsonPrimitive.boolean
            )
        }
        val wfRep = CalibrationEngine.evaluateWalkforwardCalibration(wfEvents)
        wfSeq.forEachIndexed { idx, el ->
            val ev = el.jsonObject
            assertEquals(ev.getValue("expected_p").jsonPrimitive.double, wfRep.traces[idx].predictedProbability, 1e-8)
            assertEquals(ev.getValue("expected_cold_start").jsonPrimitive.boolean, wfRep.traces[idx].usedColdStartPrior)
        }

        val mpSec = fixture.getValue("metacognitive_priority_vectors").jsonObject
        val mpBaseObj = mpSec.getValue("base").jsonObject
        val mpBase = PrioritizationInputs(
            editalWeight = mpBaseObj.getValue("edital_weight").jsonPrimitive.double,
            incidenceWeight = mpBaseObj.getValue("incidence_weight").jsonPrimitive.double,
            retrievability = mpBaseObj.getValue("retrievability").jsonPrimitive.double,
            verifiedCompetence = mpBaseObj.getValue("verified_competence").jsonPrimitive.boolean,
            distinctQuestions = mpBaseObj.getValue("distinct_questions").jsonPrimitive.int,
            avgSeconds = mpBaseObj.getValue("avg_seconds").jsonPrimitive.double,
            unmetPrerequisitesCount = mpBaseObj.getValue("unmet_prerequisites_count").jsonPrimitive.int,
            dependentCount = mpBaseObj.getValue("dependent_count").jsonPrimitive.int
        )
        val baseU = PrioritizationEngine.priorityUtilityMetacognitive(mpBase, MetacognitivePrioritySignals())
        assertEquals(mpSec.getValue("expected_baseline_utility").jsonPrimitive.double, baseU, 1e-8)

        val recHceObj = mpSec.getValue("recurrent_hce").jsonObject
        val hceSig = MetacognitivePrioritySignals(
            highConfidenceErrorRate = recHceObj.getValue("high_confidence_error_rate").jsonPrimitive.double,
            highConfidenceErrorCount = recHceObj.getValue("high_confidence_error_count").jsonPrimitive.int,
            incompatibleRationaleCount = recHceObj.getValue("incompatible_rationale_count").jsonPrimitive.int
        )
        val hceU = PrioritizationEngine.priorityUtilityMetacognitive(mpBase, hceSig)
        assertEquals(recHceObj.getValue("expected_utility").jsonPrimitive.double, hceU, 1e-8)
        val hceReason = PrioritizationEngine.buildMetacognitiveDecisionReason(
            signals = hceSig,
            unitTitle = "Duration modificada",
            fallbackReasonPt = "Assunto familiar",
            advancedAfterFunctionalTransfer = false
        )
        assertEquals(recHceObj.getValue("expected_rule_code").jsonPrimitive.content, hceReason.decisionRuleCode)

        val funcObj = mpSec.getValue("functional_verified").jsonObject
        val funcSig = MetacognitivePrioritySignals(
            functionalEliminationTransferVerified = funcObj.getValue("functional_elimination_transfer_verified").jsonPrimitive.boolean
        )
        val funcU = PrioritizationEngine.priorityUtilityMetacognitive(mpBase, funcSig)
        assertEquals(funcObj.getValue("expected_utility").jsonPrimitive.double, funcU, 1e-8)

        fixture.getValue("operational_cost_vectors").jsonArray.forEach { raw ->
            val cv = raw.jsonObject
            val (rawVal, roundedVal, displayStr) = OperationalCostCalculator.computeCostPerCompletedVerification(
                totalCostUsd = cv.getValue("total_cost_usd").jsonPrimitive.double,
                completedVerificationsCount = cv.getValue("completed_verifications_count").jsonPrimitive.int,
                costIsEstimated = cv.getValue("cost_is_estimated").jsonPrimitive.boolean
            )
            assertEquals(cv.getValue("expected_display").jsonPrimitive.content, displayStr)
            val expRaw = cv["expected_raw"]?.jsonPrimitive?.doubleOrNull
            if (expRaw == null) {
                assertNull(rawVal)
                assertNull(roundedVal)
            } else {
                assertEquals(expRaw, rawVal!!, 1e-12)
                assertEquals(cv.getValue("expected_rounded").jsonPrimitive.double, roundedVal!!, 1e-12)
            }
        }
    }
}
