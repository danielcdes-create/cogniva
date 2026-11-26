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
    val templateSourceId: String? = null,
    val templateRevisionSynced: Long? = null,
    val isCustomized: Boolean = false,
    val upstreamUpdateAvailable: Boolean = false,
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
    val wrongAnswerPenalty: Double = 0.0,
    val isActiveInLadder: Boolean = true
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

    fun masteryFraction(accuracyPct: Double, mastered: Boolean, highConfidenceError: Boolean,confidenceAvg:Int?=null): Double {
        var value = (accuracyPct / 100.0).coerceIn(0.0, 1.0)
        if (mastered) value = 1.0 else if(confidenceAvg!=null) value*=confidenceAvg.coerceIn(0,3)/3.0
        if (highConfidenceError) value *= 0.5
        return value.coerceIn(0.0, 1.0)
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
            isActiveInLadder = draft.isActiveInLadder && draft.lifecycleStatus != "ARQUIVADO",
            ladderPriority = draft.ladderPriority,
            syncStatus = "PENDING_CREATE",
            wrongAnswerPenalty = draft.wrongAnswerPenalty,
            isCustomized = true,
            subjects = draft.subjects
        )
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
    fun allowed(decisions:List<br.com.concursoprep.app.data.remote.PracticeDecision>,exam:StoredExam,component:StoredSubject,id:String,revision:String):Boolean = decisions.filter { it.examId==exam.examId && it.examRevision==exam.revision && it.componentId==component.localSubjectId && it.questionId==id && it.questionRevision==revision && it.catalogVersion in 1..ExamCatalog.knowledgeVersion() }.maxWithOrNull(compareBy<br.com.concursoprep.app.data.remote.PracticeDecision> { it.revision }.thenBy { if(it.relation=="EXCLUDED")2 else if(it.relation=="ANALOGOUS")1 else 0 })?.relation?.let { it=="DIRECT" }?:true
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

data class QuestionHeaderMeta(
    val disciplineLabel: String,
    val topicBreadcrumb: String?,
    val sourceBadge: String,
    val disciplineAndSourceLine: String
)

object ExamCatalog {
    fun knowledgeVersion():Long=knowledge.getValue("catalog_version").jsonPrimitive.long
    fun subjectLabel(subjectId: String): String? =
        knowledge.array("subjects")
            .map { it.jsonObject }
            .find { it.text("subject_id") == subjectId }
            ?.text("label")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun topicBreadcrumb(topicId: String): String? {
        val topics = knowledge.array("topics").map { it.jsonObject }
        val topic = topics.find { it.text("topic_id") == topicId } ?: return null
        val childLabel = topic.text("label").trim().takeIf { it.isNotEmpty() } ?: return null
        val parentLabel = topic["parent_topic_id"]?.jsonPrimitive?.contentOrNull?.let { parentId ->
            topics.find { it.text("topic_id") == parentId }?.text("label")?.trim()?.takeIf { it.isNotEmpty() }
        }
        return if (parentLabel != null && !parentLabel.equals(childLabel, ignoreCase = true)) {
            "$parentLabel › $childLabel"
        } else {
            childLabel
        }
    }

    fun resolveQuestionHeaderMeta(
        question: br.com.concursoprep.app.domain.model.QuestionDto,
        exam: StoredExam? = null
    ): QuestionHeaderMeta {
        val rawDisc = question.disciplina.trim()
        val isGenericDisc = rawDisc.isEmpty() ||
            rawDisc.equals("Disciplina Geral", ignoreCase = true) ||
            rawDisc.equals("Geral", ignoreCase = true)

        val examComponentName = exam?.subjects?.firstOrNull { subj ->
            (question.examComponentId != null && subj.localSubjectId == question.examComponentId) ||
                (question.canonicalSubjectId != null && subj.canonicalSubjectId == question.canonicalSubjectId)
        }?.displayName?.trim()?.takeIf { it.isNotEmpty() }

        val catalogSubjectName = question.canonicalSubjectId?.let { subjectLabel(it) }

        val disciplineLabel = examComponentName
            ?: catalogSubjectName
            ?: rawDisc.takeIf { !isGenericDisc }
            ?: rawDisc.ifEmpty { "Disciplina Geral" }

        val topicBreadcrumb = question.canonicalTopicId?.let { tid ->
            topicBreadcrumb(tid) ?: exam?.subjects
                ?.asSequence()
                ?.flatMap { it.topics.asSequence() }
                ?.firstOrNull { it.canonicalTopicId == tid }
                ?.displayTitle
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        val cleanSource = question.source.trim()
        val year = question.examYear
        val sourceBadge = when {
            cleanSource.isEmpty() -> year?.toString().orEmpty()
            year != null && !cleanSource.contains(year.toString()) -> "$cleanSource · $year"
            else -> cleanSource
        }

        val parts = buildList {
            add(disciplineLabel)
            if (question.isBorrowedAnalogous) add("Correlato")
            if (sourceBadge.isNotEmpty()) add(sourceBadge)
        }
        return QuestionHeaderMeta(
            disciplineLabel = disciplineLabel,
            topicBreadcrumb = topicBreadcrumb,
            sourceBadge = sourceBadge,
            disciplineAndSourceLine = parts.joinToString(" · ")
        )
    }

    fun subjectIdForLabel(label:String):String?=allSubjects().firstOrNull { legacyLabels(it.canonicalSubjectId).any { name -> name.equals(label,true) } }?.canonicalSubjectId
    @Volatile private var installedKnowledge: JsonObject? = null
    private val bundledKnowledge: JsonObject by lazy {
        checkNotNull(ExamCatalog::class.java.getResourceAsStream("/knowledge-catalog.json")).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    private val bundledConceptSeed: JsonObject by lazy {
        ExamCatalog::class.java.getResourceAsStream("/knowledge-concepts-seed.json")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { Json.parseToJsonElement(it.readText()).jsonObject }
            ?: buildJsonObject {}
    }
    private val knowledge: JsonObject get() = installedKnowledge ?: bundledKnowledge

    private fun foldSearchKey(raw: String): String =
        java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

    private fun effectiveNormativeSources(): List<JsonObject> {
        val fromCat = (knowledge["normative_sources"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        if (fromCat.isNotEmpty()) return fromCat
        return (bundledConceptSeed["normative_sources"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
    }

    private fun effectiveConcepts(): List<JsonObject> {
        val fromCat = (knowledge["concepts"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        if (fromCat.isNotEmpty()) return fromCat
        return (bundledConceptSeed["concepts"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
    }

    fun inferSingleConceptForTopic(
        topicId: String?,
        stem: String,
        testedRule: String? = null
    ): String? {
        val cleanTopic = topicId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val haystack = CtvAlignmentEngine.normalizeCompactText("$stem ${testedRule.orEmpty()}")
        if (haystack.isEmpty()) return null
        val matched = LinkedHashSet<String>()
        for (c in effectiveConcepts()) {
            if (c["status"]?.jsonPrimitive?.contentOrNull != "ACTIVE") continue
            if (c["topic_id"]?.jsonPrimitive?.contentOrNull != cleanTopic) continue
            val cid = c["concept_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val phrases = buildList {
                c["label"]?.jsonPrimitive?.contentOrNull?.let(::add)
                (c["aliases"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.forEach(::add)
            }
            val hit = phrases.any { p ->
                val normP = CtvAlignmentEngine.normalizeCompactText(p)
                normP.length >= 6 && haystack.contains(normP)
            }
            if (hit) matched += cid
        }
        return matched.singleOrNull()
    }

    fun normativeSourcesForQuestion(
        canonicalTopicId: String?,
        conceptIds: List<String>
    ): List<br.com.concursoprep.app.domain.model.NormativeLookupItemDto> {
        val concepts = effectiveConcepts()
        val wantedSourceIds = linkedSetOf<String>()
        for (c in concepts) {
            if (c["status"]?.jsonPrimitive?.contentOrNull != "ACTIVE") continue
            val cid = c["concept_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val cTopic = c["topic_id"]?.jsonPrimitive?.contentOrNull
            if (cid in conceptIds || (conceptIds.isEmpty() && !canonicalTopicId.isNullOrBlank() && cTopic == canonicalTopicId)) {
                (c["source_ids"] as? JsonArray)?.forEach { s ->
                    s.jsonPrimitive.contentOrNull?.let { wantedSourceIds += it }
                }
            }
        }
        if (wantedSourceIds.isEmpty()) return emptyList()
        return effectiveNormativeSources()
            .filter { it["status"]?.jsonPrimitive?.contentOrNull == "ACTIVE" && it["source_id"]?.jsonPrimitive?.contentOrNull in wantedSourceIds }
            .map { ns ->
                br.com.concursoprep.app.domain.model.NormativeLookupItemDto(
                    sourceId = ns["source_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    title = ns["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    locator = ns["locator"]?.jsonPrimitive?.contentOrNull,
                    excerpt = ns["excerpt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    canonicalUri = ns["canonical_uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }
    }

    fun lookupCatalogMatches(
        selectedText: String
    ): Pair<List<br.com.concursoprep.app.domain.model.NormativeLookupItemDto>, List<br.com.concursoprep.app.domain.model.ConceptLookupItemDto>> {
        val trimmed = selectedText.trim()
        if (trimmed.isEmpty()) return emptyList<br.com.concursoprep.app.domain.model.NormativeLookupItemDto>() to emptyList()
        val folded = foldSearchKey(trimmed)
        val terms = folded.split(Regex("\\s+")).filter { it.length >= 3 }.take(6)

        val normMatches = mutableListOf<br.com.concursoprep.app.domain.model.NormativeLookupItemDto>()
        for (ns in effectiveNormativeSources()) {
            if (ns["status"]?.jsonPrimitive?.contentOrNull != "ACTIVE") continue
            val title = ns["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val locator = ns["locator"]?.jsonPrimitive?.contentOrNull
            val excerpt = ns["excerpt"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val uri = ns["canonical_uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val hay = foldSearchKey("$title ${locator.orEmpty()} $excerpt")
            val hit = hay.contains(folded) || (terms.isNotEmpty() && terms.count { hay.contains(it) } >= minOf(2, terms.size))
            if (hit) {
                normMatches += br.com.concursoprep.app.domain.model.NormativeLookupItemDto(
                    sourceId = ns["source_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    title = title,
                    locator = locator,
                    excerpt = excerpt,
                    canonicalUri = uri
                )
                if (normMatches.size >= 4) break
            }
        }

        val conceptMatches = mutableListOf<br.com.concursoprep.app.domain.model.ConceptLookupItemDto>()
        for (c in effectiveConcepts()) {
            if (c["status"]?.jsonPrimitive?.contentOrNull != "ACTIVE") continue
            val label = c["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val hay = foldSearchKey(label)
            val hit = hay.contains(folded) || (terms.isNotEmpty() && terms.count { hay.contains(it) } >= minOf(2, terms.size))
            if (hit) {
                conceptMatches += br.com.concursoprep.app.domain.model.ConceptLookupItemDto(
                    conceptId = c["concept_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    label = label
                )
                if (conceptMatches.size >= 4) break
            }
        }
        return normMatches to conceptMatches
    }
    fun installKnowledge(value: JsonObject) {
        require(value["catalog_id"] == bundledKnowledge["catalog_id"] && value["subjects"] is JsonArray && value["topics"] is JsonArray)
        installedKnowledge = value
    }
    fun matchesSubject(subject: StoredSubject, search: String): Boolean = knowledge.array("subjects").map { it.jsonObject }.find { it.text("subject_id") == subject.canonicalSubjectId }?.let { it.text("label").contains(search,true) || it.array("aliases").any { alias -> alias.jsonPrimitive.content.contains(search,true) } } ?: subject.displayName.contains(search,true)
    fun defaultTopicsForSubject(subjectId: String): List<StoredTopic> {
        if(knowledge.array("subjects").none { it.jsonObject.text("subject_id")==subjectId && it.jsonObject.text("status")=="ACTIVE" })return emptyList()
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
    fun catalogTopics(subjectId: String): List<StoredTopic> = if(knowledge.array("subjects").none { it.jsonObject.text("subject_id")==subjectId && it.jsonObject.text("status")=="ACTIVE" })emptyList() else knowledge.array("topics").map { it.jsonObject }.filter { it.text("subject_id")==subjectId && it.text("status")=="ACTIVE" }.map { t ->
        StoredTopic(t.text("topic_id"),t.text("topic_id"),subjectId,t.text("label"),"PRE_EDITAL_BASE")
    }
    fun topicLabel(topic: StoredTopic): String {
        val t=knowledge.array("topics").map { it.jsonObject }.find { it.text("topic_id")==topic.canonicalTopicId } ?: return "Aguardando associação"
        val parent=t["parent_topic_id"]?.jsonPrimitive?.contentOrNull?.let { id -> knowledge.array("topics").map { it.jsonObject }.find { it.text("topic_id")==id }?.text("label") }
        return if(parent==null) "Assunto · ${t.text("label")}" else "Subassunto · $parent › ${t.text("label")}"
    }
    fun equivalentLabels(label:String):Set<String> = knowledge.array("subjects").map { it.jsonObject }.find { it.text("label")==label||it.array("aliases").any { alias->alias.jsonPrimitive.content==label } }?.let { legacyLabels(it.text("subject_id")) }?:setOf(label)
    fun legacyLabels(subjectId:String):Set<String> = knowledge.array("subjects").map { it.jsonObject }.find { it.text("subject_id")==subjectId }?.let { setOf(it.text("label"))+it.array("aliases").map { alias->alias.jsonPrimitive.content } }?:emptySet()
    @Volatile private var installedExams: JsonArray? = null
    private val bundledTemplates: JsonObject by lazy {
        val stream = checkNotNull(ExamCatalog::class.java.getResourceAsStream("/exam-templates.json")) { "Templates globais de concurso ausentes" }
        stream.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    fun installCatalog(value: JsonObject) {
        val exams = (value["exams"] as? JsonArray) ?: (value["templates"] as? JsonArray)
        require(exams != null) { "Payload de catálogo inválido: 'exams' ou 'templates' ausente" }
        installedExams = exams
    }
    private fun JsonObject.text(key:String):String = getValue(key).jsonPrimitive.content
    private fun JsonObject.array(key:String):JsonArray = getValue(key).jsonArray
    fun trunks(): List<TrunkTemplate> {
        return knowledge.array("trunks").map { it.jsonObject }.filter { it.text("status") == "ACTIVE" }.map { trunk ->
            val slug = trunk.text("trunk_id")
            val iconKey = trunk["icon_key"]?.jsonPrimitive?.contentOrNull ?: "work"
            val subjects = knowledge.array("subjects")
                .map { it.jsonObject }
                .filter { s -> s.text("status") == "ACTIVE" && s.array("trunk_links").any { it.jsonObject.text("trunk_id") == slug } }
                .mapNotNull { s ->
                    val topics = catalogTopics(s.text("subject_id"))
                    val first = topics.firstOrNull() ?: return@mapNotNull null
                    val link = s.array("trunk_links").first { it.jsonObject.text("trunk_id") == slug }.jsonObject
                    val weight = link["default_weight"]?.jsonPrimitive?.doubleOrNull
                        ?: if (link.text("role") == "FOUNDATIONAL") 2.0 else 1.0
                    TrunkSubjectTemplate(
                        s.text("subject_id"),
                        s.text("label"),
                        weight,
                        if (link.text("role") == "FOUNDATIONAL") "CORE" else "COMPLEMENTARY",
                        first.canonicalTopicId,
                        first.displayTitle,
                        topics
                    )
                }
            TrunkTemplate(slug, trunk.text("label"), iconKey, subjects)
        }
    }
    fun subjectsForTrunk(trunkSlug:String):List<StoredSubject> = trunks().find { it.slug==trunkSlug }?.subjects.orEmpty().mapIndexed { index,s ->
        StoredSubject("${s.canonicalSubjectId}-$index",s.canonicalSubjectId,s.name,if(s.relevanceTier=="CORE")"CONHECIMENTOS_ESPECIFICOS"else"CONHECIMENTOS_GERAIS",s.defaultWeight,kotlin.math.round(s.defaultWeight*5).toInt(),s.relevanceTier,s.topics)
    }
    fun allSubjects():List<StoredSubject> = knowledge.array("subjects").map { it.jsonObject }.filter { it.text("status")=="ACTIVE" }.map { s ->
        val id=s.text("subject_id")
        StoredSubject(id,id,s.text("label"),"CONHECIMENTOS_ESPECIFICOS",1.0,0,"SPECIFIC",emptyList())
    }
    fun systemExams():List<StoredExam> = (installedExams ?: bundledTemplates.array("templates")).map { element ->
        val e=element.jsonObject;val date=e.getValue("target_date").jsonObject;val examId=e.text("_id");val rev=e["revision"]?.jsonPrimitive?.long?:1L
        StoredExam(
            examId=examId,
            title=e.text("title"),
            shortName=e.text("short_name"),
            institution=e.text("institution"),
            role=e.text("role"),
            trunkSlug=e.text("trunk_slug"),
            lifecycleStatus=e.text("status"),
            examBoards=e.array("exam_boards").map { it.jsonPrimitive.content },
            dateType=date.text("type"),
            exactDateIso=date["iso_date"]?.jsonPrimitive?.contentOrNull,
            estimatedWindowLabel=date["label"]?.jsonPrimitive?.contentOrNull,
            estimatedSortDateIso=date["estimated_sort_date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            historicalCutoffScore=e.getValue("historical_cutoff_score").jsonPrimitive.double,
            isActiveInLadder=e.getValue("is_active_in_ladder").jsonPrimitive.boolean,
            ladderPriority=e.getValue("ladder_priority").jsonPrimitive.int,
            revision=rev,
            wrongAnswerPenalty=e["wrong_answer_penalty"]?.jsonPrimitive?.double?:0.0,
            templateSourceId=e["template_source_id"]?.jsonPrimitive?.contentOrNull ?: examId,
            templateRevisionSynced=e["template_revision_synced"]?.jsonPrimitive?.longOrNull ?: rev,
            isCustomized=e["is_customized"]?.jsonPrimitive?.booleanOrNull ?: false,
            upstreamUpdateAvailable=e["upstream_update_available"]?.jsonPrimitive?.booleanOrNull ?: false,
            subjects=e.array("subjects").map { raw ->val x=raw.jsonObject
                StoredSubject(
                    x.text("local_subject_id"),
                    x.text("canonical_subject_id"),
                    x.text("display_name"),
                    x.text("category"),
                    x.getValue("weight").jsonPrimitive.double,
                    x["question_count"]?.jsonPrimitive?.intOrNull,
                    x.text("relevance_tier"),
                    x.array("topics").map { rawTopic ->val t=rawTopic.jsonObject;StoredTopic(t.text("local_topic_id"),t.text("canonical_topic_id"),t.text("canonical_subject_id"),t.text("display_title"),t.text("diff_status")) },
                    x["stage"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }
        )
    }
}
