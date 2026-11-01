package br.com.concursoprep.app.domain.exam

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

data class StoredTopic(
    val localTopicId: String,
    val canonicalTopicId: String,
    val canonicalSubjectId: String,
    val displayTitle: String,
    val diffStatus: String
)

data class StoredSubject(
    val localSubjectId: String,
    val canonicalSubjectId: String,
    val displayName: String,
    val category: String,
    val weight: Double,
    val questionCount: Int?,
    val relevanceTier: String,
    val topics: List<StoredTopic>
)

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
    val subjects: List<StoredSubject>
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
    val questionCount: Int
)

data class SubjectOutcome(val weight: Double, val correct: Int, val drawn: Int)

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
    val topicTitle: String
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
        return live.associate { it.canonicalSubjectId to (it.weight / sum) }
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
        val pool = exams.filter { it.isActiveInLadder }.filter { exam ->
            slugs.isEmpty() || slugs.contains(exam.trunkSlug)
        }
        val presence = linkedMapOf<String, MutableList<String>>()
        val names = linkedMapOf<String, String>()
        for (exam in pool) {
            for (subject in exam.subjects.filter { !it.archivedOnly }) {
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
        return 100.0 * numerator / denominator
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
                practiced = (record?.mastery ?: 0.0) > 0.0,
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
                .filter { it.canonicalSubjectId == quota.canonicalSubjectId && !it.archived }
                .sortedBy { it.questionId }
                .take(quota.questionCount)
            picked += pool.map { it.questionId }
        }
        return picked
    }

    fun weightedNetScore(outcomes: List<SubjectOutcome>): Double {
        var numerator = 0.0
        var denominator = 0.0
        for (outcome in outcomes) {
            if (outcome.drawn == 0 || outcome.weight <= 0.0) continue
            numerator += outcome.weight * (outcome.correct.toDouble() / outcome.drawn.toDouble())
            denominator += outcome.weight
        }
        if (denominator == 0.0) return 0.0
        return 100.0 * numerator / denominator
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
        val highConfidenceError: Boolean
    )

    fun masteryFraction(accuracyPct: Double, mastered: Boolean, highConfidenceError: Boolean): Double {
        var value = (accuracyPct / 100.0).coerceIn(0.0, 1.0)
        if (mastered) value = 1.0
        if (highConfidenceError) value *= 0.5
        return value.coerceIn(0.0, 1.0)
    }

    fun masteryByCanonicalSubject(rows: List<CanonicalActivitySlice>): Map<String, Double> {
        return rows
            .filter { it.canonicalSubjectId.isNotBlank() }
            .groupBy { it.canonicalSubjectId }
            .mapValues { (_, items) ->
                items.map { masteryFraction(it.accuracyPct, it.mastered, it.highConfidenceError) }.average()
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
        val primary = exams.filter { it.isActiveInLadder }.minByOrNull { it.ladderPriority } ?: return 0.0
        val subjects = primary.subjects.map { subject ->
            val topics = subject.topics.map { topic ->
                ReadinessTopic(
                    archived = topic.diffStatus == CanonicalTopicStatus.ARQUIVADO.name,
                    practiced = (masteryByTopic[topic.canonicalTopicId] ?: 0.0) > 0.0
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
        val links = exam.subjects.flatMap { subject ->
            subject.topics.map { topic ->
                ExamTopicLink(
                    examId = exam.examId,
                    canonicalSubjectId = subject.canonicalSubjectId,
                    canonicalTopicId = topic.canonicalTopicId,
                    diffStatus = runCatching { CanonicalTopicStatus.valueOf(topic.diffStatus) }
                        .getOrDefault(CanonicalTopicStatus.PRE_EDITAL_BASE)
                )
            }
        }
        val projected = projectCrossExamCoverage(history, links)
        return exam.subjects.mapNotNull { subject ->
            val visible = subject.topics.filter { it.diffStatus != CanonicalTopicStatus.ARQUIVADO.name }
            if (visible.isEmpty()) return@mapNotNull null
            ProjectedSyllabusGroup(
                discipline = subject.displayName,
                canonicalSubjectId = subject.canonicalSubjectId,
                masteryPct = subjectMasteryPercentage(projected, exam.examId, subject.canonicalSubjectId) * 100.0,
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
        val questionIds = cachedQuestions
            .filter { it.canonicalSubjectId == card.canonicalSubjectId && !it.archived }
            .map { it.questionId }
        return CommonTrunkBattery(card, questionIds)
    }
}

object QuizSelection {
    fun weightedSimuladoIds(exam: StoredExam, questions: List<QuestionRef>): List<String> {
        val archivedTopics = exam.subjects
            .flatMap { it.topics }
            .filter { it.diffStatus == CanonicalTopicStatus.ARQUIVADO.name }
            .map { it.canonicalTopicId }
            .toSet()
        val candidates = questions.map { question ->
            SimuladoCandidate(
                questionId = question.id,
                canonicalSubjectId = question.canonicalSubjectId.orEmpty(),
                canonicalTopicId = question.canonicalTopicId.orEmpty(),
                archived = question.canonicalTopicId != null && archivedTopics.contains(question.canonicalTopicId)
            )
        }
        val quotas = exam.subjects.mapNotNull { subject ->
            val live = subject.topics.any { it.diffStatus != CanonicalTopicStatus.ARQUIVADO.name }
            val count = subject.questionCount ?: return@mapNotNull null
            if (!live || count <= 0 || subject.weight <= 0.0) null
            else SubjectQuota(subject.canonicalSubjectId, subject.weight, count)
        }
        return ExamEngine.drawWeightedSimulado(quotas, candidates)
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
        )?.questionIds.orEmpty()
    }
}

data class QuestionRef(
    val id: String,
    val canonicalSubjectId: String?,
    val canonicalTopicId: String?
)

object ExamCatalog {
    fun trunks(): List<TrunkTemplate> {
        fun subject(
            id: String,
            name: String,
            weight: Double,
            tier: String,
            topicId: String,
            topicTitle: String
        ) = TrunkSubjectTemplate(id, name, weight, tier, topicId, topicTitle)

        val finCore = subject("FIN_PUB", "Finanças Públicas", 2.0, "CORE", "FIN_PUB_01", "Orçamento público: PPA, LDO e LOA")
        val finComp = finCore.copy(relevanceTier = "COMPLEMENTARY", defaultWeight = 1.0)
        val trib = subject("DIR_TRIB", "Direito Tributário", 3.0, "CORE", "DIR_TRIB_01", "Competência tributária e crédito")
        val contab = subject("CONTAB", "Contabilidade Geral", 2.0, "CORE", "CONTAB_01", "Demonstrações contábeis")
        val adm = subject("DIR_ADM", "Direito Administrativo", 1.0, "COMPLEMENTARY", "DIR_ADM_01", "Atos administrativos e licitações")
        val port = subject("PORT", "Língua Portuguesa", 1.0, "COMPLEMENTARY", "PORT_01", "Interpretação e gramática aplicada")
        val constComp = subject("DIR_CONST", "Direito Constitucional", 1.5, "COMPLEMENTARY", "DIR_CONST_01", "Direitos fundamentais")
        val constCore = constComp.copy(relevanceTier = "CORE")
        val penal = subject("DIR_PEN", "Direito Penal", 3.0, "CORE", "DIR_PEN_01", "Teoria do crime")
        val procPen = subject("DIR_PROC_PEN", "Direito Processual Penal", 2.0, "CORE", "DIR_PROC_PEN_01", "Inquérito e ação penal")
        val afo = subject("AFO", "Administração Financeira e Orçamentária", 2.0, "CORE", "AFO_01", "Ciclo orçamentário")
        val aud = subject("AUDITORIA", "Auditoria Governamental", 2.0, "CORE", "AUD_01", "Controle interno e externo")
        val eng = subject("ENG_CIVIL", "Estruturas", 3.0, "CORE", "ENG_01", "Concreto armado")
        val mat = subject("ENG_MAT", "Resistência dos Materiais", 2.0, "CORE", "ENG_02", "Tração, compressão e flexão")
        val procCiv = subject("DIR_PROC_CIV", "Direito Processual Civil", 2.0, "CORE", "DPC_01", "Processo de conhecimento")
        val mercado = subject("MFIN", "Mercado Financeiro", 2.0, "CORE", "MFIN_01", "Sistema financeiro nacional")
        val economia = subject("ECON", "Economia", 2.0, "CORE", "ECON_01", "Política monetária e fiscal")

        return listOf(
            TrunkTemplate("fiscal", "Área Fiscal", "account_balance", listOf(trib, finCore, contab, adm, port, constComp)),
            TrunkTemplate("policial", "Carreiras Policiais", "local_police", listOf(penal, procPen, constComp, port)),
            TrunkTemplate("controle", "Controle", "fact_check", listOf(afo, aud, finCore, adm, constComp, port)),
            TrunkTemplate("engenharia", "Engenharia", "engineering", listOf(eng, mat, port)),
            TrunkTemplate("tribunais", "Tribunais", "gavel", listOf(constCore, procCiv, adm, port)),
            TrunkTemplate("bancaria", "Bancária", "savings", listOf(mercado, economia, finComp, port))
        )
    }

    fun subjectsForTrunk(trunkSlug: String): List<StoredSubject> {
        val trunk = trunks().firstOrNull { it.slug == trunkSlug } ?: return emptyList()
        return trunk.subjects.mapIndexed { index, subject ->
            StoredSubject(
                localSubjectId = "${subject.canonicalSubjectId}-$index",
                canonicalSubjectId = subject.canonicalSubjectId,
                displayName = subject.name,
                category = if (subject.relevanceTier == "CORE") "CONHECIMENTOS_ESPECIFICOS" else "CONHECIMENTOS_GERAIS",
                weight = subject.defaultWeight,
                questionCount = (subject.defaultWeight * 5.0).toInt(),
                relevanceTier = subject.relevanceTier,
                topics = listOf(
                    StoredTopic(
                        localTopicId = subject.topicId,
                        canonicalTopicId = subject.topicId,
                        canonicalSubjectId = subject.canonicalSubjectId,
                        displayTitle = subject.topicTitle,
                        diffStatus = CanonicalTopicStatus.PRE_EDITAL_BASE.name
                    )
                )
            )
        }
    }

    fun systemExams(): List<StoredExam> {
        fun exam(
            id: String,
            title: String,
            shortName: String,
            institution: String,
            role: String,
            trunk: String,
            status: String,
            boards: List<String>,
            dateType: String,
            exact: String?,
            window: String?,
            sort: String,
            cutoff: Double,
            priority: Int,
            subjectIds: List<String>
        ): StoredExam {
            val subjects = subjectsForTrunk(trunk).filter { subjectIds.contains(it.canonicalSubjectId) }
            return StoredExam(
                examId = id,
                title = title,
                shortName = shortName,
                institution = institution,
                role = role,
                trunkSlug = trunk,
                lifecycleStatus = status,
                examBoards = boards,
                dateType = dateType,
                exactDateIso = exact,
                estimatedWindowLabel = window,
                estimatedSortDateIso = sort,
                historicalCutoffScore = cutoff,
                isActiveInLadder = true,
                ladderPriority = priority,
                syncStatus = "SYNCED",
                subjects = subjects
            )
        }
        return listOf(
            exam(
                "CPNU", "CPNU - AFT", "CPNU - AFT", "MTE", "Auditor-Fiscal do Trabalho",
                "controle", "EDITAL_PUBLICADO", listOf("Cesgranrio"),
                "EXACT_DATE", "2026-12-13", null, "2026-12-13", 78.0, 1,
                listOf("FIN_PUB", "AFO", "DIR_CONST", "PORT")
            ),
            exam(
                "RFB", "Receita Federal", "Receita Federal", "RFB", "Auditor Fiscal",
                "fiscal", "AUTORIZADO", listOf("FGV", "Cebraspe"),
                "ESTIMATED_WINDOW", null, "1º sem/2027", "2027-06-30", 75.0, 2,
                listOf("DIR_TRIB", "FIN_PUB", "CONTAB", "PORT")
            ),
            exam(
                "BNDES", "BNDES", "BNDES", "BNDES", "Analista",
                "bancaria", "RUMOR_PREVISTO", listOf("Cesgranrio"),
                "ESTIMATED_WINDOW", null, "2º sem/2027", "2027-12-15", 70.0, 3,
                listOf("ECON", "MFIN", "FIN_PUB", "PORT")
            )
        )
    }
}
