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
}
