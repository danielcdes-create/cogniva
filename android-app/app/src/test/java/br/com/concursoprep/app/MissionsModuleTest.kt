package br.com.concursoprep.app

import br.com.concursoprep.app.domain.missions.LocalMissionProgressRecord
import br.com.concursoprep.app.domain.missions.LocalRecordedDecision
import br.com.concursoprep.app.domain.missions.MissionsCatalogLoader
import br.com.concursoprep.app.domain.missions.MissionsFinanceMath
import br.com.concursoprep.app.domain.missions.VisualParametersSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MissionsModuleTest {

    @Test
    fun bundledCatalogLoadsPilotMissionAndEnforcesEpistemicBoundaries() {
        val spec = MissionsCatalogLoader.loadBundled()
        assertEquals("cogniva-missions", spec.catalogId)
        assertEquals(5, spec.mediaAssets.size)
        assertEquals(1, spec.careers.size)

        val career = spec.careers.first()
        assertEquals("Banco Central — Economia e Finanças", career.displayHeader)
        assertEquals("c02bb92e-f015-4fae-8d52-92814babe02c", career.contestTemplateId)

        val disc = career.disciplines.first()
        assertEquals("MFIN", disc.canonicalSubjectId)
        assertEquals("bcb2027-ef-FIN", disc.examComponentId)

        val season = disc.seasons.first()
        assertEquals("Temporada 1: Mercado Financeiro", season.title)
        assertEquals("Renda Fixa", season.theme)

        val mission = season.missions.first()
        assertEquals("bcb-ef-t1-m01-choque-dos-juros", mission.missionId)
        assertEquals("Missão 01: O Choque dos Juros", mission.fullTitle)
        assertEquals(5, mission.scenes.size)

        val interactions = mission.scenes.mapNotNull { it.interaction }
        assertEquals(2, interactions.size)
        interactions.forEach { inter ->
            assertFalse(inter.isEditorialQuestion)
            assertFalse(inter.contributesToFormalMastery)
            assertEquals("B", inter.expectedOptionId)
        }

        val catalogView = MissionsCatalogLoader.buildCatalogView(spec, emptyMap())
        assertEquals(1, catalogView.cards.size)
        assertEquals("Não iniciada", catalogView.cards.first().narrativeProgress.statusLabelPt)
        assertTrue(catalogView.cards.first().duration.videoSeconds > 48.0)
    }

    @Test
    fun financeMathMatchesPresentValueAndDurationParityAcrossRuntimes() {
        val pv10 = MissionsFinanceMath.presentValueZeroCoupon(1000.0, 10.0, 3)
        val pv14 = MissionsFinanceMath.presentValueZeroCoupon(1000.0, 14.0, 3)
        assertTrue(abs(pv10 - 751.3148) < 0.01)
        assertTrue(abs(pv14 - 674.9715) < 0.01)
        assertTrue(pv14 < pv10)

        val cmp = MissionsFinanceMath.buildDurationComparison(
            VisualParametersSpec(
                faceValueBrl = 1000.0,
                shortMaturityYears = 1,
                longMaturityYears = 5,
                initialRatePct = 10.0,
                shockedRatePct = 12.0,
            )
        )
        assertEquals("−1,79%", MissionsFinanceMath.formatSignedPct(cmp.shortBond.changePct))
        assertEquals("−8,62%", MissionsFinanceMath.formatSignedPct(cmp.longBond.changePct))
        assertTrue(abs(cmp.longBond.changePct) > abs(cmp.shortBond.changePct) * 4.0)
    }

    @Test
    fun completedMissionSeparatesNarrativeProgressFormativeChoicesAndFormalMastery() {
        val spec = MissionsCatalogLoader.loadBundled()
        val mission = spec.careers.first().disciplines.first().seasons.first().missions.first()

        val completedRecord = LocalMissionProgressRecord(
            missionId = mission.missionId,
            status = "COMPLETED",
            currentSceneId = "cena-05-desafio-duration",
            completedSceneIds = mission.scenes.map { it.sceneId },
            decisions = mapOf(
                "decisao-01-juros-preco-prefixado" to LocalRecordedDecision(
                    interactionId = "decisao-01-juros-preco-prefixado",
                    sceneId = "cena-03-primeira-decisao",
                    firstSelectedOptionId = "A",
                    latestSelectedOptionId = "B",
                    firstAttemptCorrect = false,
                    latestCorrect = true,
                    attemptCount = 2,
                ),
                "decisao-02-desafio-duration" to LocalRecordedDecision(
                    interactionId = "decisao-02-desafio-duration",
                    sceneId = "cena-05-desafio-duration",
                    firstSelectedOptionId = "B",
                    latestSelectedOptionId = "B",
                    firstAttemptCorrect = true,
                    latestCorrect = true,
                    attemptCount = 1,
                ),
            ),
            runCount = 1,
            completedRunsCount = 1,
        )

        val detailZeroQ = MissionsCatalogLoader.buildDetailView(
            spec = spec,
            missionId = mission.missionId,
            record = completedRecord,
            availableHumanQuestions = 0,
            practicedDistinctHumanQuestions = 0,
        )
        assertNotNull(detailZeroQ)
        assertEquals("COMPLETED", detailZeroQ!!.narrativeProgress.status)
        assertEquals(100.0, detailZeroQ.narrativeProgress.progressPct, 0.01)
        assertEquals(1, detailZeroQ.interactionPerformance.firstAttemptCorrectCount)
        assertEquals(2, detailZeroQ.interactionPerformance.latestCorrectCount)
        assertTrue(detailZeroQ.workedConcepts.any { it.conceptKey == "valor_presente" && it.recommendedRevisit })
        assertEquals("NO_QUESTIONS_AVAILABLE", detailZeroQ.demonstratedMastery.masteryState)
        assertFalse(detailZeroQ.demonstratedMastery.practiceAvailable)

        val detailWithHumanBank = MissionsCatalogLoader.buildDetailView(
            spec = spec,
            missionId = mission.missionId,
            record = completedRecord,
            availableHumanQuestions = 20,
            practicedDistinctHumanQuestions = 0,
        )
        assertEquals("UNMEASURED", detailWithHumanBank!!.demonstratedMastery.masteryState)
        assertTrue(detailWithHumanBank.demonstratedMastery.practiceAvailable)
        assertTrue(detailWithHumanBank.duration.displayLabel.contains("2 decisões"))

        val detailVerified = MissionsCatalogLoader.buildDetailView(
            spec = spec,
            missionId = mission.missionId,
            record = completedRecord,
            availableHumanQuestions = 20,
            practicedDistinctHumanQuestions = 16,
            masteredHumanQuestions = 15,
        )
        assertEquals("VERIFIED_INDEPENDENT", detailVerified!!.demonstratedMastery.masteryState)

        val archivedSpec = spec.copy(careers = spec.careers.map { it.copy(status = "ARCHIVED") })
        assertEquals(null, MissionsCatalogLoader.buildDetailView(archivedSpec, mission.missionId, completedRecord, 20, 5))
    }

    @Test
    fun canAccessSceneAndShouldAutoAdvanceSceneEnforceSequentialDecisionGatesAndUserIntervention() {
        val spec = MissionsCatalogLoader.loadBundled()
        val mission = spec.careers.first().disciplines.first().seasons.first().missions.first()

        // 1. Initial NOT_STARTED state: only scene 1 is accessible; jumping to scene 2 or 5 is blocked
        val initialDetail = MissionsCatalogLoader.buildDetailView(spec, mission.missionId, null, 10, 0)!!
        val scenes = initialDetail.scenes
        assertEquals(5, scenes.size)
        assertTrue(MissionsFinanceMath.canAccessScene(scenes, scenes[0].sceneId, initialDetail.narrativeProgress))
        assertFalse(MissionsFinanceMath.canAccessScene(scenes, scenes[1].sceneId, initialDetail.narrativeProgress))
        assertFalse(MissionsFinanceMath.canAccessScene(scenes, scenes[4].sceneId, initialDetail.narrativeProgress))
        assertFalse(MissionsFinanceMath.canAccessScene(emptyList(), scenes[0].sceneId, initialDetail.narrativeProgress))

        // 2. After completing scenes 0 and 1, scene 2 (order 3) becomes accessible, while scene 3 (order 4) stays locked
        val midRecord = LocalMissionProgressRecord(
            missionId = mission.missionId,
            status = "IN_PROGRESS",
            currentSceneId = scenes[1].sceneId,
            completedSceneIds = listOf(scenes[0].sceneId, scenes[1].sceneId)
        )
        val midDetail = MissionsCatalogLoader.buildDetailView(spec, mission.missionId, midRecord, 10, 0)!!
        assertTrue(MissionsFinanceMath.canAccessScene(midDetail.scenes, scenes[2].sceneId, midDetail.narrativeProgress))
        assertFalse(MissionsFinanceMath.canAccessScene(midDetail.scenes, scenes[3].sceneId, midDetail.narrativeProgress))

        // 3. COMPLETED mission unlocks free navigation across all scenes
        val doneRecord = midRecord.copy(
            status = "COMPLETED",
            completedSceneIds = scenes.map { it.sceneId }
        )
        val doneDetail = MissionsCatalogLoader.buildDetailView(spec, mission.missionId, doneRecord, 10, 0)!!
        scenes.forEach { sc ->
            assertTrue(MissionsFinanceMath.canAccessScene(doneDetail.scenes, sc.sceneId, doneDetail.narrativeProgress))
        }

        // 4. shouldAutoAdvanceScene respects scene flags, decision gates, user intervention, video errors, and terminal scene
        val nonDecisionScene = scenes.first { !it.requiresDecisionToAdvance && it.nextSceneId != null }
            .copy(autoAdvanceOnVideoEnd = true)
        assertTrue(
            MissionsFinanceMath.shouldAutoAdvanceScene(
                scene = nonDecisionScene,
                autoAdvanceEnabled = true,
                userIntervened = false,
                videoUnavailableOrErrored = false
            )
        )
        assertFalse(
            MissionsFinanceMath.shouldAutoAdvanceScene(
                scene = nonDecisionScene,
                autoAdvanceEnabled = false,
                userIntervened = false,
                videoUnavailableOrErrored = false
            )
        )
        assertFalse(
            MissionsFinanceMath.shouldAutoAdvanceScene(
                scene = nonDecisionScene,
                autoAdvanceEnabled = true,
                userIntervened = true,
                videoUnavailableOrErrored = false
            )
        )
        assertFalse(
            MissionsFinanceMath.shouldAutoAdvanceScene(
                scene = nonDecisionScene,
                autoAdvanceEnabled = true,
                userIntervened = false,
                videoUnavailableOrErrored = true
            )
        )
        // Decision scene NEVER auto-advances even if autoAdvanceOnVideoEnd were true
        val decisionScene = scenes.first { it.requiresDecisionToAdvance }.copy(autoAdvanceOnVideoEnd = true)
        assertFalse(
            MissionsFinanceMath.shouldAutoAdvanceScene(
                scene = decisionScene,
                autoAdvanceEnabled = true,
                userIntervened = false,
                videoUnavailableOrErrored = false
            )
        )
        // Terminal scene (nextSceneId == null) NEVER auto-advances
        val terminalNoDecision = nonDecisionScene.copy(nextSceneId = null)
        assertFalse(
            MissionsFinanceMath.shouldAutoAdvanceScene(
                scene = terminalNoDecision,
                autoAdvanceEnabled = true,
                userIntervened = false,
                videoUnavailableOrErrored = false
            )
        )
    }
}
