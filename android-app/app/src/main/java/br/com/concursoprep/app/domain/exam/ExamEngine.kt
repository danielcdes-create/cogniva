package br.com.concursoprep.app.domain.exam

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

enum class SyllabusViewMode { CONTEST, TRUNK }

enum class CanonicalTopicStatus {
    PRE_EDITAL_BASE,
    OFICIAL_CONFIRMADO,
    OFICIAL_NOVO,
    ARQUIVADO
}

data class TopicRef(
    val canonicalTopicId: String,
    val canonicalSubjectId: String,
    val displayTitle: String
)

data class ClassifiedTopic(
    val canonicalTopicId: String,
    val canonicalSubjectId: String,
    val displayTitle: String,
    val diffStatus: CanonicalTopicStatus,
    val coverageAlreadyFilled: Boolean
)

data class EditalPublishResult(
    val topics: List<ClassifiedTopic>,
    val confirmedCount: Int,
    val newCount: Int,
    val archivedCount: Int
)

data class CanonicalMasteryRecord(
    val canonicalSubjectId: String,
    val canonicalTopicId: String,
    val mastery: Double
)

data class ExamTopicLink(
    val examId: String,
    val canonicalSubjectId: String,
    val canonicalTopicId: String,
    val diffStatus: CanonicalTopicStatus
)

data class ProjectedTopicCoverage(
    val examId: String,
    val canonicalSubjectId: String,
    val canonicalTopicId: String,
    val mastery: Double,
    val practiced: Boolean,
    val hiddenFromStudyQueue: Boolean
)

@kotlinx.serialization.Serializable
data class StoredTopic(
    val localTopicId: String,
    val canonicalTopicId: String,
    val canonicalSubjectId: String,
    val displayTitle: String,
    val diffStatus: String
)

@kotlinx.serialization.Serializable
data class StoredSubject(
    val localSubjectId: String,
    val canonicalSubjectId: String,
    val displayName: String,
    val category: String,
    val weight: Double,
    val questionCount: Int?,
    val relevanceTier: String,
    val topics: List<StoredTopic>,
    val stage: String = ""
)

@kotlinx.serialization.Serializable
data class StoredExam(
    val examId: String,
    val title: String,
    val shortName: String,
    val institution: String,
    val role: String,
    val trunkSlug: String,
    val lifecycleStatus: String,
    val examBoards: List<String>,
    val dateType: String,
    val exactDateIso: String?,
    val estimatedWindowLabel: String?,
    val estimatedSortDateIso: String,
    val historicalCutoffScore: Double,
    val isActiveInLadder: Boolean,
    val ladderPriority: Int,
    val confirmedTopicsCount: Int = 0,
    val newTopicsCount: Int = 0,
    val archivedTopicsCount: Int = 0,
    val syncStatus: String = "SYNCED",
    val revision: Long = 0,
    val wrongAnswerPenalty: Double = 0.0,
    val syncError: String? = null,
    val subjects: List<StoredSubject> = emptyList()
)

data class ExamDraft(
    val title: String,
    val shortName: String,
    val institution: String,
    val role: String,
    val trunkSlug: String,
    val lifecycleStatus: String,
    val examBoards: List<String>,
    val dateType: String,
    val exactDateIso: String?,
    val estimatedWindowLabel: String?,
    val estimatedSortDateIso: String,
    val historicalCutoffScore: Double,
    val ladderPriority: Int,
    val subjects: List<StoredSubject>,
    val wrongAnswerPenalty: Double = 0.0
)

data class LeverageSubject(
    val canonicalSubjectId: String,
    val name: String,
    val weight: Double,
    val archivedOnly: Boolean
)

data class LeverageExam(
    val examId: String,
    val shortName: String,
    val trunkSlug: String,
    val status: String,
    val exactDateIso: String?,
    val isActiveInLadder: Boolean,
    val subjects: List<LeverageSubject>
)

data class TrunkFocus(
    val slug: String,
    val coreSubjectIds: Set<String>
)

data class CommonTrunkCard(
    val canonicalSubjectId: String,
    val subjectName: String,
    val suggestedQuestions: Int,
    val contestNames: List<String>,
    val sentence: String,
    val score: Double
)

data class ReadinessTopic(val archived: Boolean, val practiced: Boolean)

data class ReadinessSubject(
    val weight: Double,
    val realMastery: Double,
    val topics: List<ReadinessTopic>
)

data class SimuladoCandidate(
    val questionId: String,
    val canonicalSubjectId: String,
    val canonicalTopicId: String,
    val archived: Boolean
)

data class SubjectQuota(
    val canonicalSubjectId: String,
    val weight: Double,
    val questionCount: Int,
    val allowedTopicIds: Set<String> = emptySet()
)

data class SubjectOutcome(val weight: Double, val correct: Int, val drawn: Int, val wrong: Int=0, val penalty: Double=0.0)

data class CachedQuestionRef(
    val questionId: String,
    val canonicalSubjectId: String,
    val canonicalTopicId: String,
    val archived: Boolean = false
)

data class CommonTrunkBattery(
    val card: CommonTrunkCard,
    val questionIds: List<String>
)

data class TrunkSubjectTemplate(
    val canonicalSubjectId: String,
    val name: String,
    val defaultWeight: Double,
    val relevanceTier: String,
    val topicId: String,
    val topicTitle: String,
    val topics: List<StoredTopic> = emptyList()
)

data class TrunkTemplate(
    val slug: String,
    val name: String,
    val iconKey: String,
    val subjects: List<TrunkSubjectTemplate>
)

data class TrunkSubjectRow(
    val canonicalSubjectId: String,
    val name: String,
    val tier: String,
    val contestBadges: List<String>
)

object ExamEngine {
    fun parseIsoDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val slice = if (text.length >= 10) text.substring(0, 10) else text
        return try {
            LocalDate.parse(slice)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun daysUntil(isoDate: String?, today: LocalDate): Long? {
        val date = parseIsoDate(isoDate) ?: return null
        return ChronoUnit.DAYS.between(today, date)
    }

    fun formatDeadline(dateType: String, exactDateIso: String?, windowLabel: String?, today: LocalDate): String {
        return when (dateType) {
            "EXACT_DATE" -> {
                val days = daysUntil(exactDateIso, today) ?: return "Sem previsão"
                "${days.coerceAtLeast(0)} dias"
            }
            "ESTIMATED_WINDOW" -> windowLabel?.takeIf { it.isNotBlank() } ?: "Sem previsão"
            else -> "Sem previsão"
        }
    }

    fun formatDeadlineFromCard(
        dateType: String?,
        exactDateIso: String?,
        windowLabel: String?,
        fallbackDays: Long?,
        today: LocalDate
    ): String {
        if (!dateType.isNullOrBlank()) {
            return formatDeadline(dateType, exactDateIso, windowLabel, today)
        }
        if (!exactDateIso.isNullOrBlank()) {
            return formatDeadline("EXACT_DATE", exactDateIso, windowLabel, today)
        }
        if (fallbackDays != null) return "$fallbackDays dias"
        if (!windowLabel.isNullOrBlank()) return windowLabel
        return "Sem previsão"
    }

    fun urgencyFactor(status: String, exactDateIso: String?, today: LocalDate): Double {
        val days = daysUntil(exactDateIso, today)
        if (days != null && days in 0..60) return 2.0
        if (status == "EDITAL_PUBLICADO") return 1.5
        return 1.0
    }

    private fun normalizedWeights(exam: LeverageExam): Map<String, Double> {
        val live = exam.subjects.filter { !it.archivedOnly && it.weight > 0.0 }
        val sum = live.sumOf { it.weight }
        if (sum <= 0.0) return emptyMap()
        return live.groupBy { it.canonicalSubjectId }.mapValues { (_, components) -> components.sumOf { it.weight } / sum }
    }

    fun leverageScore(
        exams: List<LeverageExam>,
        canonicalSubjectId: String,
        mastery: Double,
        today: LocalDate
    ): Double {
        val clamped = mastery.coerceIn(0.0, 1.0)
        var weightedUrgency = 0.0
        for (exam in exams) {
            val weight = normalizedWeights(exam)[canonicalSubjectId] ?: continue
            weightedUrgency += weight * urgencyFactor(exam.status, exam.exactDateIso, today)
        }
        return weightedUrgency * (1.0 - clamped)
    }

    fun contestList(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} e ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " e " + names.last()
    }

    fun commonTrunkSentence(suggestedQuestions: Int, subjectName: String, contests: List<String>): String {
        return "Estudar $suggestedQuestions questões de $subjectName avança ${contestList(contests)} ao mesmo tempo"
    }

    fun selectCommonTrunkCard(
        exams: List<LeverageExam>,
        masteryBySubject: Map<String, Double>,
        trunkFocus: TrunkFocus?,
        suggestedQuestions: Int,
        today: LocalDate,
        additionalFocus: List<TrunkFocus> = emptyList()
    ): CommonTrunkCard? {
        val focuses = listOfNotNull(trunkFocus) + additionalFocus
        val slugs = focuses.map { it.slug }.toSet()
        val cores = focuses.flatMap { it.coreSubjectIds }.toSet()
        val pool = exams.filter { it.isActiveInLadder && it.status !in setOf("ARQUIVADO", "EXCLUIDO") }.filter { exam ->
            slugs.isEmpty() || slugs.contains(exam.trunkSlug)
        }
        val presence = linkedMapOf<String, MutableList<String>>()
        val names = linkedMapOf<String, String>()
        for (exam in pool) {
            for (subject in exam.subjects.filter { !it.archivedOnly }.distinctBy { it.canonicalSubjectId }) {
                presence.getOrPut(subject.canonicalSubjectId) { mutableListOf() }.add(exam.shortName)
                names.putIfAbsent(subject.canonicalSubjectId, subject.name)
            }
        }
        var best: CommonTrunkCard? = null
        for ((subjectId, contests) in presence) {
            val core = cores.contains(subjectId)
            if (contests.size < 2 && !core) continue
            val mastery = masteryBySubject[subjectId] ?: 0.0
            val score = leverageScore(pool, subjectId, mastery, today)
            val subjectName = names[subjectId].orEmpty()
            val card = CommonTrunkCard(
                canonicalSubjectId = subjectId,
                subjectName = subjectName,
                suggestedQuestions = suggestedQuestions,
                contestNames = contests.toList(),
                sentence = commonTrunkSentence(suggestedQuestions, subjectName, contests),
                score = score
            )
            val current = best
            val replace = current == null ||
                card.score > current.score + 1e-12 ||
                (abs(card.score - current.score) <= 1e-12 && card.canonicalSubjectId < current.canonicalSubjectId)
            if (replace) best = card
        }
        return best
    }

    fun readinessIndex(subjects: List<ReadinessSubject>): Double {
        var numerator = 0.0
        var denominator = 0.0
        for (subject in subjects) {
            val live = subject.topics.filter { !it.archived }
            if (live.isEmpty() || subject.weight <= 0.0) continue
            val coverage = live.count { it.practiced }.toDouble() / live.size.toDouble()
            val mastery = subject.realMastery.coerceIn(0.0, 1.0)
            numerator += subject.weight * (0.7 * mastery + 0.3 * coverage)
            denominator += subject.weight
        }
        if (denominator == 0.0) return 0.0
        return (100.0 * numerator / denominator).coerceIn(0.0,100.0)
    }

    fun publishEditalDiff(
        previous: List<TopicRef>,
        published: List<TopicRef>,
        practicedCanonicalTopicIds: Set<String>
    ): EditalPublishResult {
        val previousIds = previous.map { it.canonicalTopicId }.toSet()
        val publishedIds = published.map { it.canonicalTopicId }.toSet()
        val topics = mutableListOf<ClassifiedTopic>()
        for (topic in published) {
            val status = if (previousIds.contains(topic.canonicalTopicId)) {
                CanonicalTopicStatus.OFICIAL_CONFIRMADO
            } else {
                CanonicalTopicStatus.OFICIAL_NOVO
            }
            topics += ClassifiedTopic(
                canonicalTopicId = topic.canonicalTopicId,
                canonicalSubjectId = topic.canonicalSubjectId,
                displayTitle = topic.displayTitle,
                diffStatus = status,
                coverageAlreadyFilled = practicedCanonicalTopicIds.contains(topic.canonicalTopicId)
            )
        }
        for (topic in previous) {
            if (!publishedIds.contains(topic.canonicalTopicId)) {
                topics += ClassifiedTopic(
                    canonicalTopicId = topic.canonicalTopicId,
                    canonicalSubjectId = topic.canonicalSubjectId,
                    displayTitle = topic.displayTitle,
                    diffStatus = CanonicalTopicStatus.ARQUIVADO,
                    coverageAlreadyFilled = practicedCanonicalTopicIds.contains(topic.canonicalTopicId)
                )
            }
        }
        return EditalPublishResult(
            topics = topics,
            confirmedCount = topics.count { it.diffStatus == CanonicalTopicStatus.OFICIAL_CONFIRMADO },
            newCount = topics.count { it.diffStatus == CanonicalTopicStatus.OFICIAL_NOVO },
            archivedCount = topics.count { it.diffStatus == CanonicalTopicStatus.ARQUIVADO }
        )
    }

    fun projectCrossExamCoverage(
        history: List<CanonicalMasteryRecord>,
        links: List<ExamTopicLink>
    ): List<ProjectedTopicCoverage> {
        val byTopic = history.associateBy { it.canonicalTopicId }
        return links.map { link ->
            val record = byTopic[link.canonicalTopicId]
            ProjectedTopicCoverage(
                examId = link.examId,
                canonicalSubjectId = link.canonicalSubjectId,
                canonicalTopicId = link.canonicalTopicId,
                mastery = record?.mastery ?: 0.0,
                practiced = record != null,
                hiddenFromStudyQueue = link.diffStatus == CanonicalTopicStatus.ARQUIVADO
            )
        }
    }

    fun subjectMasteryPercentage(
        projected: List<ProjectedTopicCoverage>,
        examId: String,
        canonicalSubjectId: String
    ): Double {
        val visible = projected.filter {
            it.examId == examId && it.canonicalSubjectId == canonicalSubjectId && !it.hiddenFromStudyQueue
        }
        if (visible.isEmpty()) return 0.0
        return visible.sumOf { it.mastery } / visible.size.toDouble()
    }

    fun drawWeightedSimulado(quotas: List<SubjectQuota>, questions: List<SimuladoCandidate>): List<String> {
        val picked = mutableListOf<String>()
        for (quota in quotas) {
            val pool = questions
                .filter { it.questionId !in picked && it.canonicalSubjectId == quota.canonicalSubjectId && !it.archived && it.canonicalTopicId in quota.allowedTopicIds }
                .distinctBy { it.questionId }
                .shuffled()
                .take(quota.questionCount)
            picked += pool.map { it.questionId }
        }
        return picked
    }

    fun weightedNetScore(outcomes: List<SubjectOutcome>): Double {
        var numerator = 0.0
        var denominator = 0.0
        for (outcome in outcomes) {
            if (outcome.drawn <= 0 || outcome.weight <= 0.0 || !outcome.weight.isFinite() || !outcome.penalty.isFinite()) continue
            val correct=outcome.correct.coerceIn(0,outcome.drawn)
            val wrong=outcome.wrong.coerceIn(0,outcome.drawn-correct)
            numerator += outcome.weight * (correct-outcome.penalty.coerceIn(0.0,1.0)*wrong)
            denominator += outcome.weight*outcome.drawn
        }
        if (denominator == 0.0) return 0.0
        return (100.0 * numerator / denominator).coerceIn(0.0,100.0)
    }

    fun toLeverage(exam: StoredExam): LeverageExam {
        return LeverageExam(
            examId = exam.examId,
            shortName = exam.shortName,
            trunkSlug = exam.trunkSlug,
            status = exam.lifecycleStatus,
            exactDateIso = if (exam.dateType == "EXACT_DATE") exam.exactDateIso else null,
            isActiveInLadder = exam.isActiveInLadder,
            subjects = exam.subjects.map { subject ->
                val archivedOnly = subject.topics.isNotEmpty() &&
                    subject.topics.all { it.diffStatus == CanonicalTopicStatus.ARQUIVADO.name }
                LeverageSubject(
                    canonicalSubjectId = subject.canonicalSubjectId,
                    name = subject.displayName,
                    weight = subject.weight,
                    archivedOnly = archivedOnly
                )
            }
        )
    }

    data class CanonicalActivitySlice(
        val canonicalSubjectId: String,
        val canonicalTopicId: String,
        val accuracyPct: Double,
        val mastered: Boolean,
        val highConfidenceError: Boolean,
        val confidenceAvg: Int? = null
    )

    fun masteryFraction(accuracyPct: Double, mastered: Boolean, highConfidenceError: Boolean,confidenceAvg:Int?=null): Double {
        var value = (accuracyPct / 100.0).coerceIn(0.0, 1.0)
        if (mastered) value = 1.0 else if(confidenceAvg!=null) value*=confidenceAvg.coerceIn(0,3)/3.0
        if (highConfidenceError) value *= 0.5
        return value.coerceIn(0.0, 1.0)
    }

    fun masteryByCanonicalSubject(rows: List<CanonicalActivitySlice>): Map<String, Double> {
        return rows
            .filter { it.canonicalSubjectId.isNotBlank() }
            .groupBy { it.canonicalSubjectId }
            .mapValues { (_, items) ->
                items.map { masteryFraction(it.accuracyPct, it.mastered, it.highConfidenceError,it.confidenceAvg) }.average()
            }
    }

    fun cardFromCanonicalActivity(
        exams: List<LeverageExam>,
        rows: List<CanonicalActivitySlice>,
        focuses: List<TrunkFocus>,
        suggestedQuestions: Int,
        today: LocalDate
    ): CommonTrunkCard? {
        val mastery = masteryByCanonicalSubject(rows)
        return selectCommonTrunkCard(
            exams,
            mastery,
            focuses.firstOrNull(),
            suggestedQuestions,
            today,
            focuses.drop(1)
        )
    }

    fun readinessForStoredExams(exams: List<StoredExam>, masteryByTopic: Map<String, Double>): Double {
        val primary = exams.filter { it.isActiveInLadder&&it.lifecycleStatus !in setOf("ARQUIVADO","EXCLUIDO") }.minByOrNull { it.ladderPriority } ?: return 0.0
        val subjects = primary.subjects.map { subject ->
            val topics = subject.topics.map { topic ->
                ReadinessTopic(
                    archived = topic.diffStatus == CanonicalTopicStatus.ARQUIVADO.name,
                    practiced = masteryByTopic.containsKey(topic.canonicalTopicId)
                )
            }
            val live = subject.topics.filter { it.diffStatus != CanonicalTopicStatus.ARQUIVADO.name }
            val realMastery = if (live.isEmpty()) {
                0.0
            } else {
                live.map { masteryByTopic[it.canonicalTopicId] ?: 0.0 }.average()
            }
            ReadinessSubject(subject.weight, realMastery, topics)
        }
        return readinessIndex(subjects)
    }

    data class ProjectedSyllabusGroup(
        val discipline: String,
        val canonicalSubjectId: String,
        val masteryPct: Double,
        val topicTitles: List<String>
    )

    fun projectSyllabus(exam: StoredExam, history: List<CanonicalMasteryRecord>): List<ProjectedSyllabusGroup> {
        return exam.subjects.mapNotNull { subject ->
            val visible = subject.topics.filter { it.diffStatus != CanonicalTopicStatus.ARQUIVADO.name }
            if (visible.isEmpty()) return@mapNotNull null
            ProjectedSyllabusGroup(
                discipline = subject.displayName,
                canonicalSubjectId = subject.canonicalSubjectId,
                masteryPct = history.filter { it.canonicalTopicId in visible.map { t -> t.canonicalTopicId } }.distinctBy { it.canonicalTopicId }.sumOf { it.mastery } / visible.map { it.canonicalTopicId }.distinct().size * 100.0,
                topicTitles = visible.map { it.displayTitle }
            )
        }
    }

    fun trunkRows(trunkSlug: String, exams: List<StoredExam>): List<TrunkSubjectRow> {
        val trunk = ExamCatalog.trunks().firstOrNull { it.slug == trunkSlug } ?: return emptyList()
        val active = exams.filter { it.isActiveInLadder }
        return trunk.subjects
            .filter { it.relevanceTier == "CORE" || it.relevanceTier == "COMPLEMENTARY" }
            .map { subject ->
                val badges = active
                    .filter { exam -> exam.subjects.any { it.canonicalSubjectId == subject.canonicalSubjectId } }
                    .map { it.shortName }
                TrunkSubjectRow(
                    canonicalSubjectId = subject.canonicalSubjectId,
                    name = subject.name,
                    tier = subject.relevanceTier,
                    contestBadges = badges
                )
            }
    }

    fun coreSubjectIds(trunkSlug: String): Set<String> {
        return ExamCatalog.trunks()
            .firstOrNull { it.slug == trunkSlug }
            ?.subjects
            .orEmpty()
            .filter { it.relevanceTier == "CORE" }
            .map { it.canonicalSubjectId }
            .toSet()
    }
}

object OfflineExamQueue {
    fun createPendingExam(
        draft: ExamDraft,
        newId: () -> String = { UUID.randomUUID().toString() }
    ): StoredExam {
        require(draft.title.isNotBlank()&&draft.title.length<=300&&draft.ladderPriority in 1..100) { "Revise o nome e a prioridade do concurso." }
        require(draft.historicalCutoffScore.isFinite()&&draft.historicalCutoffScore in 0.0..100.0&&draft.wrongAnswerPenalty.isFinite()&&draft.wrongAnswerPenalty in 0.0..1.0) { "Corte ou desconto inválido." }
        if(draft.dateType=="EXACT_DATE") require(runCatching { LocalDate.parse(draft.exactDateIso) }.isSuccess) { "Informe uma data válida no formato AAAA-MM-DD." }
        if(draft.dateType=="ESTIMATED_WINDOW") require(!draft.estimatedWindowLabel.isNullOrBlank()&&runCatching { LocalDate.parse(draft.estimatedSortDateIso) }.isSuccess) { "Revise a janela estimada." }
        require(draft.subjects.size<=100&&draft.subjects.sumOf { it.questionCount?:0 }<=500&&draft.subjects.all { it.weight.isFinite()&&it.weight in 0.01..100.0&&(it.questionCount?:0) in 0..500&&it.displayName.isNotBlank()&&it.topics.all { t->t.displayTitle.isNotBlank() } }) { "Revise a grade: disciplinas, tópicos, pesos e até 500 questões." }
        return StoredExam(
            examId = newId(),
            title = draft.title,
            shortName = draft.shortName.ifBlank { draft.title },
            institution = draft.institution,
            role = draft.role,
            trunkSlug = draft.trunkSlug,
            lifecycleStatus = draft.lifecycleStatus,
            examBoards = draft.examBoards,
            dateType = draft.dateType,
            exactDateIso = draft.exactDateIso,
            estimatedWindowLabel = draft.estimatedWindowLabel,
            estimatedSortDateIso = draft.estimatedSortDateIso,
            historicalCutoffScore = draft.historicalCutoffScore,
            isActiveInLadder = true,
            ladderPriority = draft.ladderPriority,
            syncStatus = "PENDING_CREATE",
            wrongAnswerPenalty = draft.wrongAnswerPenalty,
            subjects = draft.subjects
        )
    }

    fun markUpdate(existing: StoredExam): StoredExam {
        val status = if (existing.syncStatus == "PENDING_CREATE") "PENDING_CREATE" else "PENDING_UPDATE"
        return existing.copy(syncStatus = status)
    }

    fun mergeByClientId(store: Map<String, StoredExam>, incoming: StoredExam): Map<String, StoredExam> {
        val next = store.toMutableMap()
        next[incoming.examId] = incoming
        return next
    }

    fun commonTrunkFromCache(
        exams: List<StoredExam>,
        cachedQuestions: List<CachedQuestionRef>,
        trunkFocusSlug: String? = null,
        masteryBySubject: Map<String, Double>,
        today: LocalDate,
        suggestedQuestions: Int,
        focusedTrunkSlugs: Set<String> = emptySet()
    ): CommonTrunkBattery? {
        val slugs = focusedTrunkSlugs + listOfNotNull(trunkFocusSlug)
        val focuses = slugs.map { slug -> TrunkFocus(slug, ExamEngine.coreSubjectIds(slug)) }
        val card = ExamEngine.selectCommonTrunkCard(
            exams.map(ExamEngine::toLeverage),
            masteryBySubject,
            focuses.firstOrNull(),
            suggestedQuestions,
            today,
            focuses.drop(1)
        ) ?: return null
        val liveTopics=exams.filter { it.isActiveInLadder&&it.lifecycleStatus !in setOf("ARQUIVADO","EXCLUIDO") }.flatMap { it.subjects }.flatMap { it.topics }.filter { it.diffStatus!="ARQUIVADO" }.map { it.canonicalTopicId }.toSet()
        val questionIds = cachedQuestions
            .filter { it.canonicalSubjectId == card.canonicalSubjectId && !it.archived && it.canonicalTopicId in liveTopics }
            .distinctBy { it.questionId }
            .map { it.questionId }
        return CommonTrunkBattery(card, questionIds)
    }
}

object QuizSelection {
    fun allowed(decisions:List<br.com.concursoprep.app.data.remote.PracticeDecision>,exam:StoredExam,component:StoredSubject,id:String,revision:String):Boolean = decisions.filter { it.examId==exam.examId && it.examRevision==exam.revision && it.componentId==component.localSubjectId && it.questionId==id && it.questionRevision==revision && it.catalogVersion==ExamCatalog.knowledgeVersion() }.maxWithOrNull(compareBy<br.com.concursoprep.app.data.remote.PracticeDecision> { it.revision }.thenBy { if(it.relation=="EXCLUDED")2 else if(it.relation=="ANALOGOUS")1 else 0 })?.relation?.let { it=="DIRECT" }?:true
    fun weightedSimuladoIds(exam: StoredExam, questions: List<QuestionRef>,decisions:List<br.com.concursoprep.app.data.remote.PracticeDecision> = emptyList()): List<String> {
        val picked=mutableListOf<String>()
        for(subject in exam.subjects){
            val topics=subject.topics.filter { it.diffStatus!="ARQUIVADO" }.map { it.canonicalTopicId }.toSet()
            val count=subject.questionCount?:0
            if(topics.isEmpty() || count<=0 || subject.weight<=0)continue
            picked+=questions.filter { it.id !in picked && it.canonicalSubjectId==subject.canonicalSubjectId && it.canonicalTopicId in topics && allowed(decisions,exam,subject,it.id,it.revision) }.distinctBy { it.id }.shuffled().take(count).map { it.id }
        }
        return picked
    }

    fun commonTrunkIds(
        exams: List<StoredExam>,
        questions: List<QuestionRef>,
        focusedTrunkSlugs: Set<String>,
        masteryBySubject: Map<String, Double>,
        today: LocalDate,
        quantity: Int
    ): List<String> {
        val refs = questions.map { question ->
            CachedQuestionRef(
                questionId = question.id,
                canonicalSubjectId = question.canonicalSubjectId.orEmpty(),
                canonicalTopicId = question.canonicalTopicId.orEmpty()
            )
        }
        return OfflineExamQueue.commonTrunkFromCache(
            exams = exams,
            cachedQuestions = refs,
            masteryBySubject = masteryBySubject,
            today = today,
            suggestedQuestions = quantity,
            focusedTrunkSlugs = focusedTrunkSlugs
        )?.questionIds.orEmpty().take(quantity)
    }
}

data class QuestionRef(
    val id: String,
    val canonicalSubjectId: String?,
    val canonicalTopicId: String?,
    val revision:String=""
)

object ExamCatalog {
    fun knowledgeVersion():Long=knowledge.getValue("catalog_version").jsonPrimitive.long
    fun subjectIdForLabel(label:String):String?=allSubjects().firstOrNull { legacyLabels(it.canonicalSubjectId).any { name -> name.equals(label,true) } }?.canonicalSubjectId
    @Volatile private var installedKnowledge: JsonObject? = null
    private val bundledKnowledge: JsonObject by lazy {
        checkNotNull(ExamCatalog::class.java.getResourceAsStream("/knowledge-catalog.json")).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    private val knowledge: JsonObject get() = installedKnowledge ?: bundledKnowledge
    fun installKnowledge(value: JsonObject) {
        require(value["catalog_id"] == bundledKnowledge["catalog_id"] && value["subjects"] is JsonArray && value["topics"] is JsonArray)
        installedKnowledge = value
    }
    fun matchesSubject(subject: StoredSubject, search: String): Boolean = knowledge.array("subjects").map { it.jsonObject }.find { it.text("subject_id") == subject.canonicalSubjectId }?.let { it.text("label").contains(search,true) || it.array("aliases").any { alias -> alias.jsonPrimitive.content.contains(search,true) } } ?: subject.displayName.contains(search,true)
    fun defaultTopicsForSubject(subjectId: String): List<StoredTopic> {
        val rawTopics = knowledge.array("topics").map { it.jsonObject }.filter { it.text("subject_id") == subjectId && it.text("status") == "ACTIVE" }
        val roots = rawTopics.filter { it["parent_topic_id"]?.jsonPrimitive?.contentOrNull == null }
        val selected = roots.ifEmpty { rawTopics }
        return selected.map { t ->
            val topicId = t.text("topic_id")
            StoredTopic(UUID.randomUUID().toString(), topicId, subjectId, t.text("label"), "PRE_EDITAL_BASE")
        }
    }
    fun newComponent(subject: StoredSubject): StoredSubject {
        val baseTopics = if (subject.topics.isNotEmpty()) {
            subject.topics.map { it.copy(localTopicId = UUID.randomUUID().toString()) }
        } else {
            defaultTopicsForSubject(subject.canonicalSubjectId)
        }
        return subject.copy(
            localSubjectId = UUID.randomUUID().toString(),
            questionCount = subject.questionCount?.takeIf { it > 0 } ?: 5,
            topics = baseTopics
        )
    }
    fun associateSubject(subject: StoredSubject, target: StoredSubject): StoredSubject = subject.copy(canonicalSubjectId=target.canonicalSubjectId,topics=subject.topics.map { old ->
        old.copy(canonicalSubjectId=target.canonicalSubjectId,canonicalTopicId=if(catalogTopics(target.canonicalSubjectId).any { it.canonicalTopicId==old.canonicalTopicId } || old.canonicalTopicId.startsWith("custom-"))old.canonicalTopicId else "custom-${UUID.randomUUID()}")
    })
    fun catalogTopics(subjectId: String): List<StoredTopic> = knowledge.array("topics").map { it.jsonObject }.filter { it.text("subject_id")==subjectId && it.text("status")=="ACTIVE" }.map { t ->
        StoredTopic(t.text("topic_id"),t.text("topic_id"),subjectId,t.text("label"),"PRE_EDITAL_BASE")
    }
    fun topicLabel(topic: StoredTopic): String {
        val t=knowledge.array("topics").map { it.jsonObject }.find { it.text("topic_id")==topic.canonicalTopicId } ?: return "Aguardando associação"
        val parent=t["parent_topic_id"]?.jsonPrimitive?.contentOrNull?.let { id -> knowledge.array("topics").map { it.jsonObject }.find { it.text("topic_id")==id }?.text("label") }
        return if(parent==null) "Assunto · ${t.text("label")}" else "Subassunto · $parent › ${t.text("label")}"
    }
    fun equivalentLabels(label:String):Set<String> = knowledge.array("subjects").map { it.jsonObject }.find { it.text("label")==label||it.array("aliases").any { alias->alias.jsonPrimitive.content==label } }?.let { legacyLabels(it.text("subject_id")) }?:setOf(label)
    fun legacyLabels(subjectId:String):Set<String> = knowledge.array("subjects").map { it.jsonObject }.find { it.text("subject_id")==subjectId }?.let { setOf(it.text("label"))+it.array("aliases").map { alias->alias.jsonPrimitive.content } }?:emptySet()
    @Volatile private var installed: JsonObject? = null
    private val bundled: JsonObject by lazy {
        val stream=checkNotNull(ExamCatalog::class.java.getResourceAsStream("/canonical-catalog.json")) { "Catálogo canônico ausente" }
        stream.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    private val catalog: JsonObject get() = installed ?: bundled
    fun installCatalog(value: JsonObject) {
        require((value["trunks"] as? JsonArray)?.isNotEmpty()==true && value["exams"] is JsonArray && value["subjects"] is JsonArray)
        installed=value
    }
    private fun JsonObject.text(key:String):String = getValue(key).jsonPrimitive.content
    private fun JsonObject.array(key:String):JsonArray = getValue(key).jsonArray
    private fun topic(value:JsonObject,subjectId:String)=StoredTopic(value.text("topic_id"),value.text("topic_id"),subjectId,value.text("title"),"PRE_EDITAL_BASE")
    fun trunks(): List<TrunkTemplate> = catalog.array("trunks").map { element ->
        val t=element.jsonObject
        val subjects=(t.array("core_subjects")+t.array("complementary_subjects")).map { raw ->
            val item=raw.jsonObject;val id=item.text("canonical_subject_id");val topics=item.array("topics").map { topic(it.jsonObject,id) };val first=topics.first()
            TrunkSubjectTemplate(id,item.text("name"),item.getValue("default_weight").jsonPrimitive.double,item.text("relevance_tier"),first.canonicalTopicId,first.displayTitle,topics)
        }
        TrunkTemplate(t.text("slug"),t.text("name"),t.text("icon_key"),subjects)
    } + knowledge.array("trunks").map { it.jsonObject }.filter { t -> catalog.array("trunks").none { it.jsonObject.text("slug")==t.text("trunk_id") } }.map { TrunkTemplate(it.text("trunk_id"),it.text("label"),"work",emptyList()) }
    fun subjectsForTrunk(trunkSlug:String):List<StoredSubject> = trunks().find { it.slug==trunkSlug }?.subjects.orEmpty().mapIndexed { index,s ->
        StoredSubject("${s.canonicalSubjectId}-$index",s.canonicalSubjectId,s.name,if(s.relevanceTier=="CORE")"CONHECIMENTOS_ESPECIFICOS"else"CONHECIMENTOS_GERAIS",s.defaultWeight,kotlin.math.round(s.defaultWeight*5).toInt(),s.relevanceTier,s.topics)
    }
    fun allSubjects():List<StoredSubject> = knowledge.array("subjects").map { it.jsonObject }.filter { it.text("status")=="ACTIVE" }.map { s ->
        val id=s.text("subject_id")
        StoredSubject(id,id,s.text("label"),"CONHECIMENTOS_ESPECIFICOS",1.0,0,"SPECIFIC",emptyList())
    }
    fun systemExams():List<StoredExam> = catalog.array("exams").map { element ->
        val e=element.jsonObject;val date=e.getValue("target_date").jsonObject
        StoredExam(examId=e.text("_id"),title=e.text("title"),shortName=e.text("short_name"),institution=e.text("institution"),role=e.text("role"),trunkSlug=e.text("trunk_slug"),lifecycleStatus=e.text("status"),examBoards=e.array("exam_boards").map { it.jsonPrimitive.content },
            dateType=date.text("type"),exactDateIso=date["iso_date"]?.jsonPrimitive?.contentOrNull,estimatedWindowLabel=date["label"]?.jsonPrimitive?.contentOrNull,estimatedSortDateIso=date["estimated_sort_date"]?.jsonPrimitive?.contentOrNull.orEmpty(),historicalCutoffScore=e.getValue("historical_cutoff_score").jsonPrimitive.double,
            isActiveInLadder=e.getValue("is_active_in_ladder").jsonPrimitive.boolean,ladderPriority=e.getValue("ladder_priority").jsonPrimitive.int,revision=e["revision"]?.jsonPrimitive?.long?:0,wrongAnswerPenalty=e["wrong_answer_penalty"]?.jsonPrimitive?.double?:0.0,
            subjects=e.array("subjects").map { raw ->val x=raw.jsonObject
                StoredSubject(x.text("local_subject_id"),x.text("canonical_subject_id"),x.text("display_name"),x.text("category"),x.getValue("weight").jsonPrimitive.double,x["question_count"]?.jsonPrimitive?.intOrNull,x.text("relevance_tier"),x.array("topics").map { rawTopic ->val t=rawTopic.jsonObject;StoredTopic(t.text("local_topic_id"),t.text("canonical_topic_id"),t.text("canonical_subject_id"),t.text("display_title"),t.text("diff_status")) })
            })
    }
}
