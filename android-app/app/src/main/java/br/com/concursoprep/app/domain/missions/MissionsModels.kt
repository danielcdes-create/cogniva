package br.com.concursoprep.app.domain.missions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

// --- Bundled Declarative Schema Models (`shared/missions-catalog.json`) ---

@Serializable
data class CaptionCueSpec(
    @SerialName("cue_id") val cueId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val speaker: String? = null,
    val text: String,
)

@Serializable
data class MediaAssetSpec(
    @SerialName("asset_id") val assetId: String,
    val kind: String = "VIDEO_MP4",
    @SerialName("relative_file_name") val relativeFileName: String,
    @SerialName("mime_type") val mimeType: String = "video/mp4",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val sha256: String = "",
    @SerialName("duration_seconds") val durationSeconds: Double,
    val width: Int = 1280,
    val height: Int = 720,
    @SerialName("aspect_ratio") val aspectRatio: String = "16:9",
    @SerialName("has_embedded_voice") val hasEmbeddedVoice: Boolean = true,
    val captions: List<CaptionCueSpec> = emptyList(),
)

@Serializable
data class CharacterSpec(
    @SerialName("character_id") val characterId: String,
    val name: String,
    @SerialName("role_title") val roleTitle: String,
    @SerialName("institution_context") val institutionContext: String = "",
)

@Serializable
data class WorkedConceptSpec(
    @SerialName("concept_key") val conceptKey: String,
    val label: String,
    val summary: String,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String,
    @SerialName("canonical_topic_ids") val canonicalTopicIds: List<String> = emptyList(),
    @SerialName("related_scene_ids") val relatedSceneIds: List<String> = emptyList(),
)

@Serializable
data class CurriculumBindingSpec(
    @SerialName("contest_name") val contestName: String,
    @SerialName("contest_template_id") val contestTemplateId: String,
    @SerialName("role_name") val roleName: String,
    @SerialName("exam_component_id") val examComponentId: String,
    @SerialName("discipline_label") val disciplineLabel: String,
    @SerialName("theme_label") val themeLabel: String,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String,
    @SerialName("primary_topic_ids") val primaryTopicIds: List<String> = emptyList(),
    @SerialName("fallback_topic_ids") val fallbackTopicIds: List<String> = emptyList(),
    @SerialName("worked_concepts") val workedConcepts: List<WorkedConceptSpec> = emptyList(),
)

@Serializable
data class DecisionOptionSpec(
    @SerialName("option_id") val optionId: String,
    val label: String,
    val feedback: String = "",
)

@Serializable
data class DidacticInteractionSpec(
    @SerialName("interaction_id") val interactionId: String,
    val kind: String = "SINGLE_CHOICE_DECISION",
    @SerialName("is_editorial_question") val isEditorialQuestion: Boolean = false,
    @SerialName("contributes_to_formal_mastery") val contributesToFormalMastery: Boolean = false,
    @SerialName("context_lead") val contextLead: String? = null,
    val prompt: String,
    @SerialName("ceteris_paribus_note") val ceterisParibusNote: String,
    val options: List<DecisionOptionSpec>,
    @SerialName("expected_option_id") val expectedOptionId: String = "",
    @SerialName("conceptual_explanation") val conceptualExplanation: String = "",
    @SerialName("revisit_concept_keys") val revisitConceptKeys: List<String> = emptyList(),
)

@Serializable
data class VisualParametersSpec(
    @SerialName("face_value_brl") val faceValueBrl: Double = 1000.0,
    @SerialName("maturity_years") val maturityYears: Int = 3,
    @SerialName("short_maturity_years") val shortMaturityYears: Int = 1,
    @SerialName("long_maturity_years") val longMaturityYears: Int = 5,
    @SerialName("initial_rate_pct") val initialRatePct: Double = 10.0,
    @SerialName("shocked_rate_pct") val shockedRatePct: Double = 14.0,
    @SerialName("min_rate_pct") val minRatePct: Double = 4.0,
    @SerialName("max_rate_pct") val maxRatePct: Double = 20.0,
)

@Serializable
data class NativeVisualSpec(
    @SerialName("visual_id") val visualId: String,
    @SerialName("visual_type") val visualType: String,
    val title: String,
    val subtitle: String,
    @SerialName("formula_label") val formulaLabel: String,
    val highlights: List<String> = emptyList(),
    val parameters: VisualParametersSpec = VisualParametersSpec(),
)

@Serializable
data class SceneSpec(
    @SerialName("scene_id") val sceneId: String,
    val order: Int,
    val title: String,
    @SerialName("pedagogical_goal") val pedagogicalGoal: String = "",
    @SerialName("asset_id") val assetId: String,
    @SerialName("character_id") val characterId: String,
    @SerialName("narrative_context") val narrativeContext: String,
    @SerialName("problem_statement") val problemStatement: String? = null,
    @SerialName("transcript_pt_br") val transcriptPtBr: String,
    @SerialName("estimated_interaction_seconds") val estimatedInteractionSeconds: Int = 15,
    @SerialName("auto_advance_on_video_end") val autoAdvanceOnVideoEnd: Boolean = false,
    @SerialName("requires_decision_to_advance") val requiresDecisionToAdvance: Boolean = false,
    @SerialName("next_scene_id") val nextSceneId: String? = null,
    val interaction: DidacticInteractionSpec? = null,
    @SerialName("native_visual") val nativeVisual: NativeVisualSpec? = null,
)

@Serializable
data class MissionConclusionSpec(
    val headline: String,
    @SerialName("narrative_closure") val narrativeClosure: String,
    @SerialName("epistemological_note") val epistemologicalNote: String,
    @SerialName("next_step_guidance") val nextStepGuidance: String,
)

@Serializable
data class MissionSpec(
    @SerialName("mission_id") val missionId: String,
    @SerialName("mission_number") val missionNumber: Int,
    val version: Int = 1,
    @SerialName("publication_state") val publicationState: String = "PUBLISHED",
    @SerialName("display_code") val displayCode: String,
    val title: String,
    @SerialName("full_title") val fullTitle: String,
    val summary: String,
    val description: String,
    val curriculum: CurriculumBindingSpec,
    val scenes: List<SceneSpec>,
    val conclusion: MissionConclusionSpec,
)

@Serializable
data class SeasonSpec(
    @SerialName("season_id") val seasonId: String,
    @SerialName("season_number") val seasonNumber: Int,
    val title: String,
    val theme: String,
    val summary: String,
    val missions: List<MissionSpec>,
)

@Serializable
data class DisciplineSpec(
    @SerialName("discipline_id") val disciplineId: String,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String,
    @SerialName("exam_component_id") val examComponentId: String,
    @SerialName("display_name") val displayName: String,
    val seasons: List<SeasonSpec>,
)

@Serializable
data class CareerSpec(
    @SerialName("career_id") val careerId: String,
    @SerialName("trunk_id") val trunkId: String,
    @SerialName("contest_template_id") val contestTemplateId: String,
    @SerialName("display_header") val displayHeader: String,
    val institution: String,
    val role: String,
    val status: String = "ACTIVE",
    val disciplines: List<DisciplineSpec>,
)

@Serializable
data class MissionsCatalogSpec(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("catalog_id") val catalogId: String = "cogniva-missions",
    @SerialName("catalog_version") val catalogVersion: Int = 1,
    @SerialName("media_assets") val mediaAssets: List<MediaAssetSpec>,
    val characters: List<CharacterSpec>,
    val careers: List<CareerSpec>,
)

// --- Local Durable Per-Account Mission Progress & View Projections ---

@Serializable
data class LocalRecordedDecision(
    val interactionId: String,
    val sceneId: String,
    val firstSelectedOptionId: String,
    val latestSelectedOptionId: String,
    val firstAttemptCorrect: Boolean,
    val latestCorrect: Boolean,
    val attemptCount: Int,
)

@Serializable
data class LocalMissionProgressRecord(
    val missionId: String,
    val status: String = "NOT_STARTED", // NOT_STARTED | IN_PROGRESS | COMPLETED
    val currentSceneId: String = "",
    val completedSceneIds: List<String> = emptyList(),
    val scenePositionsMs: Map<String, Long> = emptyMap(),
    val decisions: Map<String, LocalRecordedDecision> = emptyMap(),
    val runCount: Int = 0,
    val completedRunsCount: Int = 0,
)

@Serializable
data class LocalMissionsStateEnvelope(
    val ownerUserId: Long = 0L,
    val missions: Map<String, LocalMissionProgressRecord> = emptyMap(),
)

data class MissionDurationView(
    val videoSeconds: Double,
    val interactionSeconds: Int,
    val estimatedMinutes: Int,
    val displayLabel: String,
)

data class NarrativeProgressView(
    val status: String,
    val statusLabelPt: String,
    val currentSceneId: String,
    val currentSceneOrder: Int,
    val completedSceneIds: List<String>,
    val completedScenesCount: Int,
    val totalScenesCount: Int,
    val progressPct: Double,
    val scenePositionsMs: Map<String, Long>,
    val completedRunsCount: Int,
)

data class InteractionPerformanceView(
    val answeredDecisionsCount: Int,
    val totalDecisionsCount: Int,
    val firstAttemptCorrectCount: Int,
    val latestCorrectCount: Int,
    val conceptsForReview: List<String>,
)

data class DemonstratedMasteryView(
    val masteryState: String,
    val masteryLabelPt: String,
    val availableHumanQuestionsCount: Int,
    val distinctHumanQuestionsPracticed: Int,
    val minQuestionsForMastery: Int = 15,
    val practiceAvailable: Boolean,
)

data class WorkedConceptView(
    val conceptKey: String,
    val label: String,
    val summary: String,
    val canonicalSubjectId: String,
    val activeTopicIds: List<String>,
    val relatedSceneIds: List<String>,
    val recommendedRevisit: Boolean,
)

data class AnsweredDecisionView(
    val interactionId: String,
    val sceneId: String,
    val selectedOptionId: String,
    val expectedOptionId: String,
    val isCorrect: Boolean,
    val firstAttemptCorrect: Boolean,
    val attemptCount: Int,
    val optionFeedback: String,
    val conceptualExplanation: String,
)

data class SceneInteractionView(
    val interactionId: String,
    val contextLead: String?,
    val prompt: String,
    val ceterisParibusNote: String,
    val options: List<DecisionOptionSpec>,
    val answered: AnsweredDecisionView?,
)

data class MissionSceneView(
    val sceneId: String,
    val order: Int,
    val title: String,
    val character: CharacterSpec,
    val narrativeContext: String,
    val problemStatement: String?,
    val transcriptPtBr: String,
    val autoAdvanceOnVideoEnd: Boolean,
    val requiresDecisionToAdvance: Boolean,
    val nextSceneId: String?,
    val nextAssetId: String?,
    val nextAssetSha256: String?,
    val isCompleted: Boolean,
    val asset: MediaAssetSpec,
    val availableOnServer: Boolean,
    val interaction: SceneInteractionView?,
    val nativeVisual: NativeVisualSpec?,
)

data class MissionCardView(
    val careerHeader: String,
    val institution: String,
    val role: String,
    val disciplineName: String,
    val seasonTitle: String,
    val seasonTheme: String,
    val seasonSummary: String,
    val missionId: String,
    val displayCode: String,
    val title: String,
    val fullTitle: String,
    val description: String,
    val scenesCount: Int,
    val duration: MissionDurationView,
    val workedConceptLabels: List<String>,
    val narrativeProgress: NarrativeProgressView,
    val interactionPerformance: InteractionPerformanceView,
)

data class MissionsCatalogView(
    val catalogVersion: Int,
    val cards: List<MissionCardView>,
)

data class MissionDetailView(
    val careerHeader: String,
    val institution: String,
    val role: String,
    val contestTemplateId: String,
    val disciplineName: String,
    val canonicalSubjectId: String,
    val seasonTitle: String,
    val seasonTheme: String,
    val missionId: String,
    val displayCode: String,
    val title: String,
    val fullTitle: String,
    val description: String,
    val duration: MissionDurationView,
    val scenes: List<MissionSceneView>,
    val workedConcepts: List<WorkedConceptView>,
    val conclusion: MissionConclusionSpec,
    val narrativeProgress: NarrativeProgressView,
    val interactionPerformance: InteractionPerformanceView,
    val demonstratedMastery: DemonstratedMasteryView,
)

// --- Bundled Resource Loader, View Projector & Pure Financial Math ---

object MissionsCatalogLoader {
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Volatile
    private var cachedSpec: MissionsCatalogSpec? = null

    fun loadBundled(): MissionsCatalogSpec {
        cachedSpec?.let { return it }
        val stream = MissionsCatalogLoader::class.java.classLoader
            ?.getResourceAsStream("missions-catalog.json")
            ?: error("Contrato compartilhado missions-catalog.json ausente nos recursos do app.")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return jsonParser.decodeFromString<MissionsCatalogSpec>(text).also {
            cachedSpec = it
        }
    }

    fun calculateDuration(mission: MissionSpec, assets: Map<String, MediaAssetSpec>): MissionDurationView {
        val videoSec = mission.scenes.sumOf { assets[it.assetId]?.durationSeconds ?: 0.0 }
        val interSec = mission.scenes.sumOf { it.estimatedInteractionSeconds }
        val estMin = ceil((videoSec + interSec) / 60.0).toInt().coerceAtLeast(1)
        val roundedVideo = videoSec.roundToInt()
        return MissionDurationView(
            videoSeconds = videoSec,
            interactionSeconds = interSec,
            estimatedMinutes = estMin,
            displayLabel = "~$estMin min (${roundedVideo}s de vídeo + decisões)",
        )
    }

    fun statusLabelPt(status: String): String = when (status) {
        "COMPLETED" -> "Concluída"
        "IN_PROGRESS" -> "Em andamento"
        else -> "Não iniciada"
    }

    fun buildCatalogView(
        spec: MissionsCatalogSpec,
        localState: Map<String, LocalMissionProgressRecord>,
    ): MissionsCatalogView {
        val assets = spec.mediaAssets.associateBy { it.assetId }
        val cards = mutableListOf<MissionCardView>()
        for (career in spec.careers) {
            if (career.status == "ARCHIVED") continue
            for (disc in career.disciplines) {
                for (season in disc.seasons) {
                    for (mission in season.missions) {
                        if (mission.publicationState != "PUBLISHED") continue
                        val rec = localState[mission.missionId]
                        val prog = summarizeProgress(mission, rec)
                        val perf = summarizePerformance(mission, rec)
                        cards += MissionCardView(
                            careerHeader = career.displayHeader,
                            institution = career.institution,
                            role = career.role,
                            disciplineName = disc.displayName,
                            seasonTitle = season.title,
                            seasonTheme = season.theme,
                            seasonSummary = season.summary,
                            missionId = mission.missionId,
                            displayCode = mission.displayCode,
                            title = mission.title,
                            fullTitle = mission.fullTitle,
                            description = mission.description,
                            scenesCount = mission.scenes.size,
                            duration = calculateDuration(mission, assets),
                            workedConceptLabels = mission.curriculum.workedConcepts.map { it.label },
                            narrativeProgress = prog,
                            interactionPerformance = perf,
                        )
                    }
                }
            }
        }
        return MissionsCatalogView(catalogVersion = spec.catalogVersion, cards = cards)
    }

    fun buildDetailView(
        spec: MissionsCatalogSpec,
        missionId: String,
        record: LocalMissionProgressRecord?,
        availableHumanQuestions: Int,
        practicedDistinctHumanQuestions: Int,
    ): MissionDetailView? {
        val assets = spec.mediaAssets.associateBy { it.assetId }
        val chars = spec.characters.associateBy { it.characterId }

        for (career in spec.careers) {
            for (disc in career.disciplines) {
                for (season in disc.seasons) {
                    val mission = season.missions.find { it.missionId == missionId } ?: continue
                    val prog = summarizeProgress(mission, record)
                    val perf = summarizePerformance(mission, record)
                    val reviewSet = perf.conceptsForReview.toSet()
                    val concepts = mission.curriculum.workedConcepts.map { c ->
                        WorkedConceptView(
                            conceptKey = c.conceptKey,
                            label = c.label,
                            summary = c.summary,
                            canonicalSubjectId = c.canonicalSubjectId,
                            activeTopicIds = c.canonicalTopicIds,
                            relatedSceneIds = c.relatedSceneIds,
                            recommendedRevisit = reviewSet.contains(c.conceptKey),
                        )
                    }
                    val completedSet = prog.completedSceneIds.toSet()
                    val scenesById = mission.scenes.associateBy { it.sceneId }
                    val scenes = mission.scenes.mapNotNull { sc ->
                        val asset = assets[sc.assetId] ?: return@mapNotNull null
                        val ch = chars[sc.characterId] ?: CharacterSpec(sc.characterId, "Analista", "")
                        val nextScene = sc.nextSceneId?.let { scenesById[it] }
                        val nextAsset = nextScene?.let { assets[it.assetId] }
                        val interView = sc.interaction?.let { inter ->
                            val saved = record?.decisions?.get(inter.interactionId)
                            val answered = saved?.let { d ->
                                val optFb = inter.options.find { it.optionId == d.latestSelectedOptionId }?.feedback.orEmpty()
                                AnsweredDecisionView(
                                    interactionId = inter.interactionId,
                                    sceneId = sc.sceneId,
                                    selectedOptionId = d.latestSelectedOptionId,
                                    expectedOptionId = inter.expectedOptionId,
                                    isCorrect = d.latestCorrect,
                                    firstAttemptCorrect = d.firstAttemptCorrect,
                                    attemptCount = d.attemptCount,
                                    optionFeedback = optFb,
                                    conceptualExplanation = inter.conceptualExplanation,
                                )
                            }
                            SceneInteractionView(
                                interactionId = inter.interactionId,
                                contextLead = inter.contextLead,
                                prompt = inter.prompt,
                                ceterisParibusNote = inter.ceterisParibusNote,
                                options = inter.options,
                                answered = answered,
                            )
                        }
                        MissionSceneView(
                            sceneId = sc.sceneId,
                            order = sc.order,
                            title = sc.title,
                            character = ch,
                            narrativeContext = sc.narrativeContext,
                            problemStatement = sc.problemStatement,
                            transcriptPtBr = sc.transcriptPtBr,
                            autoAdvanceOnVideoEnd = sc.autoAdvanceOnVideoEnd,
                            requiresDecisionToAdvance = sc.requiresDecisionToAdvance,
                            nextSceneId = sc.nextSceneId,
                            nextAssetId = nextAsset?.assetId,
                            nextAssetSha256 = nextAsset?.sha256,
                            isCompleted = completedSet.contains(sc.sceneId) || prog.status == "COMPLETED",
                            asset = asset,
                            availableOnServer = true,
                            interaction = interView,
                            nativeVisual = sc.nativeVisual,
                        )
                    }
                    val masteryState = when {
                        availableHumanQuestions == 0 -> "NO_QUESTIONS_AVAILABLE"
                        practicedDistinctHumanQuestions == 0 -> "UNMEASURED"
                        else -> "EVIDENCE_IN_PROGRESS"
                    }
                    val masteryLabel = when (masteryState) {
                        "NO_QUESTIONS_AVAILABLE" ->
                            "Nenhuma questão humana validada está em cache/acervo neste ambiente para os subtópicos vinculados."
                        "UNMEASURED" ->
                            "Ainda não aferido em questões humanas reais ($availableHumanQuestions disponíveis). Concluir a missão não concede domínio automático."
                        else ->
                            "Evidência independente em construção ($practicedDistinctHumanQuestions/15 questões humanas distintas)."
                    }
                    return MissionDetailView(
                        careerHeader = career.displayHeader,
                        institution = career.institution,
                        role = career.role,
                        contestTemplateId = career.contestTemplateId,
                        disciplineName = disc.displayName,
                        canonicalSubjectId = disc.canonicalSubjectId,
                        seasonTitle = season.title,
                        seasonTheme = season.theme,
                        missionId = mission.missionId,
                        displayCode = mission.displayCode,
                        title = mission.title,
                        fullTitle = mission.fullTitle,
                        description = mission.description,
                        duration = calculateDuration(mission, assets),
                        scenes = scenes,
                        workedConcepts = concepts,
                        conclusion = mission.conclusion,
                        narrativeProgress = prog,
                        interactionPerformance = perf,
                        demonstratedMastery = DemonstratedMasteryView(
                            masteryState = masteryState,
                            masteryLabelPt = masteryLabel,
                            availableHumanQuestionsCount = availableHumanQuestions,
                            distinctHumanQuestionsPracticed = practicedDistinctHumanQuestions,
                            minQuestionsForMastery = 15,
                            practiceAvailable = availableHumanQuestions > 0,
                        ),
                    )
                }
            }
        }
        return null
    }

    fun summarizeProgress(mission: MissionSpec, record: LocalMissionProgressRecord?): NarrativeProgressView {
        val firstScene = mission.scenes.firstOrNull()?.sceneId.orEmpty()
        val total = mission.scenes.size
        if (record == null) {
            return NarrativeProgressView(
                status = "NOT_STARTED",
                statusLabelPt = statusLabelPt("NOT_STARTED"),
                currentSceneId = firstScene,
                currentSceneOrder = 1,
                completedSceneIds = emptyList(),
                completedScenesCount = 0,
                totalScenesCount = total,
                progressPct = 0.0,
                scenePositionsMs = emptyMap(),
                completedRunsCount = 0,
            )
        }
        val validIds = mission.scenes.map { it.sceneId }.toSet()
        val done = record.completedSceneIds.filter { validIds.contains(it) }.distinct()
        val curOrder = mission.scenes.find { it.sceneId == record.currentSceneId }?.order ?: 1
        val curId = mission.scenes.find { it.order == curOrder }?.sceneId ?: firstScene
        val pct = if (record.status == "COMPLETED") {
            100.0
        } else if (total > 0) {
            ((done.size.toDouble() / total.toDouble()) * 1000.0).roundToInt() / 10.0
        } else {
            0.0
        }
        return NarrativeProgressView(
            status = record.status,
            statusLabelPt = statusLabelPt(record.status),
            currentSceneId = curId,
            currentSceneOrder = curOrder,
            completedSceneIds = done,
            completedScenesCount = done.size,
            totalScenesCount = total,
            progressPct = pct,
            scenePositionsMs = record.scenePositionsMs,
            completedRunsCount = record.completedRunsCount,
        )
    }

    fun summarizePerformance(mission: MissionSpec, record: LocalMissionProgressRecord?): InteractionPerformanceView {
        val interactions = mission.scenes.mapNotNull { it.interaction }
        if (record == null) {
            return InteractionPerformanceView(
                answeredDecisionsCount = 0,
                totalDecisionsCount = interactions.size,
                firstAttemptCorrectCount = 0,
                latestCorrectCount = 0,
                conceptsForReview = emptyList(),
            )
        }
        var answered = 0
        var firstRight = 0
        var latestRight = 0
        val review = mutableListOf<String>()
        for (inter in interactions) {
            val d = record.decisions[inter.interactionId] ?: continue
            answered += 1
            if (d.firstAttemptCorrect) {
                firstRight += 1
            } else {
                for (ck in inter.revisitConceptKeys) {
                    if (!review.contains(ck)) review += ck
                }
            }
            if (d.latestCorrect) latestRight += 1
        }
        return InteractionPerformanceView(
            answeredDecisionsCount = answered,
            totalDecisionsCount = interactions.size,
            firstAttemptCorrectCount = firstRight,
            latestCorrectCount = latestRight,
            conceptsForReview = review,
        )
    }
}

object MissionsFinanceMath {
    data class CurvePoint(val ratePct: Double, val priceBrl: Double)

    data class BondShockSummary(
        val years: Int,
        val macaulayDuration: Int,
        val modifiedDuration: Double,
        val initialPrice: Double,
        val shockedPrice: Double,
        val changePct: Double,
    )

    data class DurationComparison(
        val faceValue: Double,
        val initialRatePct: Double,
        val shockedRatePct: Double,
        val shortBond: BondShockSummary,
        val longBond: BondShockSummary,
    )

    fun presentValueZeroCoupon(faceValueBrl: Double, annualRatePct: Double, maturityYears: Int): Double {
        val r = annualRatePct / 100.0
        if (r <= -1.0 || maturityYears < 0) return faceValueBrl
        return faceValueBrl / (1.0 + r).pow(maturityYears.toDouble())
    }

    fun priceChangePct(
        faceValueBrl: Double,
        initialRatePct: Double,
        shockedRatePct: Double,
        maturityYears: Int,
    ): Double {
        val p0 = presentValueZeroCoupon(faceValueBrl, initialRatePct, maturityYears)
        val p1 = presentValueZeroCoupon(faceValueBrl, shockedRatePct, maturityYears)
        if (p0 <= 0.0) return 0.0
        return ((p1 - p0) / p0) * 100.0
    }

    fun modifiedDurationZeroCoupon(maturityYears: Int, annualRatePct: Double): Double {
        val r = annualRatePct / 100.0
        if (r <= -1.0) return maturityYears.toDouble()
        return maturityYears.toDouble() / (1.0 + r)
    }

    fun buildPresentValueCurve(params: VisualParametersSpec, steps: Int = 16): List<CurvePoint> {
        val minR = params.minRatePct
        val maxR = params.maxRatePct
        return (0..steps).map { i ->
            val rate = minR + ((maxR - minR) * i) / steps.toDouble()
            CurvePoint(
                ratePct = rate,
                priceBrl = presentValueZeroCoupon(params.faceValueBrl, rate, params.maturityYears),
            )
        }
    }

    fun buildDurationComparison(params: VisualParametersSpec): DurationComparison {
        val vf = params.faceValueBrl
        val r0 = params.initialRatePct
        val r1 = params.shockedRatePct
        val shortY = params.shortMaturityYears
        val longY = params.longMaturityYears
        return DurationComparison(
            faceValue = vf,
            initialRatePct = r0,
            shockedRatePct = r1,
            shortBond = BondShockSummary(
                years = shortY,
                macaulayDuration = shortY,
                modifiedDuration = modifiedDurationZeroCoupon(shortY, r0),
                initialPrice = presentValueZeroCoupon(vf, r0, shortY),
                shockedPrice = presentValueZeroCoupon(vf, r1, shortY),
                changePct = priceChangePct(vf, r0, r1, shortY),
            ),
            longBond = BondShockSummary(
                years = longY,
                macaulayDuration = longY,
                modifiedDuration = modifiedDurationZeroCoupon(longY, r0),
                initialPrice = presentValueZeroCoupon(vf, r0, longY),
                shockedPrice = presentValueZeroCoupon(vf, r1, longY),
                changePct = priceChangePct(vf, r0, r1, longY),
            ),
        )
    }

    fun formatBrl(value: Double): String {
        val formatted = String.format(Locale.forLanguageTag("pt-BR"), "%,.2f", value)
        return "R$ $formatted"
    }

    fun formatSignedPct(value: Double): String {
        val sign = when {
            value > 0.0001 -> "+"
            value < -0.0001 -> "−"
            else -> ""
        }
        val num = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", abs(value))
        return "$sign$num%"
    }

    fun formatClockSeconds(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val mins = total / 60
        val secs = total % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun canAccessScene(
        scenes: List<MissionSceneView>,
        targetSceneId: String,
        progress: NarrativeProgressView,
    ): Boolean {
        if (scenes.isEmpty()) return false
        if (progress.status == "COMPLETED") return true
        val done = progress.completedSceneIds.toSet()
        if (targetSceneId == progress.currentSceneId || done.contains(targetSceneId)) return true
        val targetIdx = scenes.indexOfFirst { it.sceneId == targetSceneId }
        if (targetIdx <= 0) return targetIdx == 0
        for (i in 0 until targetIdx) {
            if (!done.contains(scenes[i].sceneId)) return false
        }
        return true
    }

    fun shouldAutoAdvanceScene(
        scene: MissionSceneView,
        autoAdvanceEnabled: Boolean,
        userIntervened: Boolean,
        videoUnavailableOrErrored: Boolean,
    ): Boolean {
        if (!scene.autoAdvanceOnVideoEnd || scene.requiresDecisionToAdvance) return false
        if (!autoAdvanceEnabled || userIntervened || videoUnavailableOrErrored) return false
        return scene.nextSceneId != null
    }
}
