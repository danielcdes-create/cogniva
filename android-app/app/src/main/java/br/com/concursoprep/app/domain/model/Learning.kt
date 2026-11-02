package br.com.concursoprep.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import br.com.concursoprep.app.domain.exam.ExamEngine
import java.time.Instant
import java.time.ZoneOffset

data class PendingReviewItem(val id:String,val title:String,val detail:String,val status:String)

@Serializable
data class LearningEvent(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("confidence_level") val confidenceLevel: Int,
    @SerialName("time_spent_seconds") val timeSpentSeconds: Int,
    @SerialName("question_revision") val questionRevision:String?=null
)
@Serializable data class KnowledgeEvidence(
    @SerialName("canonical_subject_id") val canonicalSubjectId:String,
    @SerialName("canonical_topic_id") val canonicalTopicId:String,
    @SerialName("question_revision") val questionRevision:String,
    val attempts:Int,val correct:Int,
    @SerialName("accuracy_pct") val accuracyPct:Double,
    @SerialName("confidence_avg") val confidenceAvg:Int?=null,
    @SerialName("recent_confidences") val recentConfidences:List<Int> = emptyList(),
    @SerialName("high_confidence_error") val highConfidenceError:Boolean=false,
    @SerialName("review_due_on") val reviewDueOn:String?=null,
    val mastered:Boolean=false
)
@Serializable
data class LearningRecord(
    @SerialName("question_id") val questionId: String,
    val disciplina: String,
    @SerialName("canonical_subject_id") val canonicalSubjectId: String? = null,
    @SerialName("canonical_topic_id") val canonicalTopicId: String? = null,
    val attempts: Int, val correct: Int,
    @SerialName("accuracy_pct") val accuracyPct: Double,
    @SerialName("is_mastered") val isMastered: Boolean,
    @SerialName("in_error_notebook") val inErrorNotebook: Boolean,
    @SerialName("high_confidence_error") val highConfidenceError: Boolean,
    val points: Double,
    @SerialName("next_review_at") val nextReviewAt: String,
    val revision: Long = 0,
    val events: List<LearningEvent> = emptyList(),
    @SerialName("confidence_avg") val confidenceAvg: Int? = null,
    @SerialName("review_required") val reviewRequired: Boolean = false,
    @SerialName("practice_available") val practiceAvailable: Boolean? = null,
    @SerialName("knowledge_projection_version") val knowledgeProjectionVersion:Int=0,
    @SerialName("knowledge_evidence") val knowledgeEvidence:KnowledgeEvidence?=null
)
@Serializable
data class LearningPage(val records: List<LearningRecord>, @SerialName("next_cursor") val nextCursor: String? = null)

/** Contract v2. Practice evidence is presence of a record, including a score of zero. */
object LearningProjection {
    val studyZone: ZoneOffset = ZoneOffset.ofHours(-3)
    fun knowledgeRows(rows:List<LearningRecord>):List<LearningRecord> = rows.distinctBy { it.questionId }.mapNotNull { r ->
        if(r.knowledgeProjectionVersion==0) null else r.knowledgeEvidence?.takeIf { e -> e.reviewDueOn?.let { runCatching { !java.time.LocalDate.parse(it).isBefore(java.time.LocalDate.now(studyZone)) }.getOrDefault(false) }?:true }?.let { e ->
            r.copy(canonicalSubjectId=e.canonicalSubjectId,canonicalTopicId=e.canonicalTopicId,attempts=e.attempts,correct=e.correct,accuracyPct=e.accuracyPct,confidenceAvg=e.confidenceAvg,isMastered=e.mastered,highConfidenceError=e.highConfidenceError)
        }
    }
    fun masteryByTopic(rows: List<LearningRecord>): Map<String, Double> = knowledgeRows(rows)
        .filter { !it.canonicalTopicId.isNullOrBlank() }
        .groupBy { it.canonicalTopicId!! }
        .mapValues { (_, records) -> records.map { ExamEngine.masteryFraction(it.accuracyPct, it.isMastered, it.highConfidenceError,it.confidenceAvg) }.average() }
    fun masteryBySubject(rows: List<LearningRecord>): Map<String, Double> = knowledgeRows(rows)
        .filter { !it.canonicalSubjectId.isNullOrBlank() }
        .groupBy { it.canonicalSubjectId!! }
        .mapValues { (_, records) -> records.map { ExamEngine.masteryFraction(it.accuracyPct, it.isMastered, it.highConfidenceError,it.confidenceAvg) }.average() }
    fun project(base: LearningRecord?, question: QuestionDto, pending: List<LearningEvent>): LearningRecord {
        val history=projectHistory(base,question,pending)
        if(question.knowledgeProjectionVersion==0)return history
        val subject=question.canonicalSubjectId;val topic=question.canonicalTopicId
        if(!question.knowledgeBindingVerified || subject==null || topic==null || br.com.concursoprep.app.domain.exam.ExamCatalog.catalogTopics(subject).none { it.canonicalTopicId==topic } || !br.com.concursoprep.app.data.local.QuestionCacheMapping.withinReviewDate(question,java.time.LocalDate.now(studyZone)))return history.copy(knowledgeProjectionVersion=1,knowledgeEvidence=null)
        val previous=base?.knowledgeEvidence?.takeIf { it.questionRevision==question.questionRevision }
        val confirmed=base?.events.orEmpty().map { it.attemptId }.toSet()
        val fresh=pending.filter { it.attemptId !in confirmed && it.questionRevision==question.questionRevision }.distinctBy { it.attemptId }.sortedWith(compareBy<LearningEvent> { Instant.parse(it.occurredAt) }.thenBy { it.attemptId })
        val attempts=(previous?.attempts?:0)+fresh.size
        if(attempts==0)return history.copy(knowledgeProjectionVersion=1,knowledgeEvidence=null)
        val correct=(previous?.correct?:0)+fresh.count { it.isCorrect }
        val recent=(previous?.recentConfidences.orEmpty()+fresh.map { it.confidenceLevel.coerceIn(0,3) }).takeLast(3)
        val high=fresh.lastOrNull()?.let { !it.isCorrect&&it.confidenceLevel>=2 }?:previous?.highConfidenceError?:false
        return history.copy(knowledgeProjectionVersion=1,knowledgeEvidence=KnowledgeEvidence(subject,topic,question.questionRevision,attempts,correct,100.0*correct/attempts,if(recent.isEmpty())null else kotlin.math.floor(recent.average()+0.5).toInt(),recent,high,question.practiceReviewDueOn,history.attempts==attempts&&history.isMastered))
    }
    private fun projectHistory(base: LearningRecord?, question: QuestionDto, pending: List<LearningEvent>): LearningRecord {
        val confirmed = base?.events.orEmpty().map { it.attemptId }.toSet()
        val fresh = pending.filter { it.attemptId !in confirmed }.distinctBy { it.attemptId }
        if (fresh.isEmpty() && base != null) return base
        val events = (base?.events.orEmpty() + fresh).sortedWith(compareBy<LearningEvent> { Instant.parse(it.occurredAt) }.thenBy { it.attemptId })
        val attempts = (base?.attempts ?: 0) + fresh.size
        val correct = (base?.correct ?: 0) + fresh.count { it.isCorrect }
        if(base!=null&&base.events.size<base.attempts){
            // An ACK can precede a full pull. Unknown older events must never
            // be replaced with an invented streak starting at zero.
            return base.copy(attempts=attempts,correct=correct,accuracyPct=correct*100.0/attempts,
                isMastered=base.isMastered&&fresh.none { !it.isCorrect },
                inErrorNotebook=base.inErrorNotebook||fresh.any { !it.isCorrect },events=events,reviewRequired=false,practiceAvailable=true)
        }
        var points = 0.0; var streak = 0; var errors = 0
        events.forEach { event ->
            if (event.isCorrect) { streak++; errors = 0; points += listOf(.2,.3,.5,.1)[(streak-1).coerceAtMost(3)] }
            else { streak = 0; errors++; points = (points + listOf(-.2,-.3,-.5,-.1)[(errors-1).coerceAtMost(3)]).coerceAtLeast(0.0) }
        }
        points = kotlin.math.round(points * 1000.0) / 1000.0
        val last = events.lastOrNull()
        val recent=events.takeLast(3)
        val confidence=if(recent.isEmpty())null else kotlin.math.floor(recent.map { it.confidenceLevel }.average()+0.5).toInt()
        val mastered = points >= 1.0 - 1e-6 && (confidence?:0)>=2
        val interval = if (last == null || !last.isCorrect || last.confidenceLevel <= 1) 1 else if (streak <= 2) 7 else 30
        return LearningRecord(question.id, question.disciplina, question.canonicalSubjectId, question.canonicalTopicId,
            attempts, correct, if (attempts > 0) correct * 100.0 / attempts else 0.0, mastered,
            if (last?.isCorrect == false) true else if (mastered) false else base?.inErrorNotebook == true || fresh.any { !it.isCorrect },
            last?.let { !it.isCorrect && it.confidenceLevel >= 2 } ?: false, points,
            last?.let { Instant.parse(it.occurredAt).plusSeconds(interval * 86400L).toString() } ?: Instant.EPOCH.toString(),
            base?.revision ?: 0, events,confidence,reviewRequired=false,practiceAvailable=true)
    }
}
