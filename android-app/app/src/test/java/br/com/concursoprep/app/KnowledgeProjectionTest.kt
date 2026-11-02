package br.com.concursoprep.app

import br.com.concursoprep.app.domain.model.*
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.data.local.QuestionCacheMapping
import br.com.concursoprep.app.data.remote.PracticeDecision
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class KnowledgeProjectionTest {
    private fun question()=QuestionDto(id="q",questionRevision="body-v1",question="Synthetic",answers=listOf("A","B","C","D","E"),correctAnswerIndices=listOf(1),disciplina="Old textual label",source="Synthetic",level="Médio",canonicalSubjectId="MAT",canonicalTopicId="MAT_01_001",knowledgeProjectionVersion=1,knowledgeBindingVerified=true)

    @Test fun legacyIdsWithoutVerifiedReviewCannotEarnOfflineKnowledge(){
        val projected=LearningProjection.project(null,question().copy(knowledgeBindingVerified=false),listOf(event("a")))
        assertEquals(1,projected.attempts);assertNull(projected.knowledgeEvidence)
        assertTrue(LearningProjection.masteryByTopic(listOf(projected)).isEmpty())
        assertTrue(LearningProjection.masteryByTopic(listOf(projected.copy(knowledgeProjectionVersion=0))).isEmpty())
    }
    private fun event(id:String,revision:String="body-v1")=LearningEvent(id,"2026-10-01T12:00:00Z",true,3,10,revision)

    @Test fun projectionReusesOneEventWithoutChangingItsIdentityOrCreditingAParent(){
        val q=question();val one=LearningProjection.project(null,q,listOf(event("a"),event("a")))
        assertEquals(1,one.attempts);assertEquals(1,one.knowledgeEvidence!!.attempts)
        assertEquals(setOf("MAT_01_001"),LearningProjection.masteryByTopic(listOf(one,one)).keys)
        val replay=LearningProjection.project(one,q,listOf(event("a")))
        assertEquals(1,replay.attempts);assertEquals(1,replay.knowledgeEvidence!!.attempts)
        val another=LearningProjection.project(replay,q,listOf(event("b")))
        assertEquals(2,another.attempts);assertEquals(2,another.knowledgeEvidence!!.attempts)
        assertEquals(listOf("a","b"),another.events.map { it.attemptId })
    }
    @Test fun editedContentAndUnknownMappingPreserveTotalsButCannotBorrowOldMastery(){
        val q=question();val old=LearningProjection.project(null,q,listOf(event("a")))
        val changed=LearningProjection.project(old,q.copy(questionRevision="body-v2"),emptyList())
        assertEquals(old.attempts,changed.attempts);assertEquals(old.events,changed.events)
        assertNull(changed.knowledgeEvidence);assertTrue(LearningProjection.masteryByTopic(listOf(changed)).isEmpty())
        val unmapped=LearningProjection.project(old,q.copy(canonicalSubjectId=null,canonicalTopicId=null),emptyList())
        assertNull(unmapped.knowledgeEvidence);assertEquals(1,unmapped.correct)
    }
    @Test fun excludedAndAnalogousAreScopedToTheComponentAndRevision(){
        val topic=StoredTopic("t","MAT_01_001","MAT","Conjuntos","PRE_EDITAL_BASE")
        val first=StoredSubject("first","MAT","Matemática","CONHECIMENTOS_GERAIS",1.0,1,"CORE",listOf(topic),"1ª etapa")
        val second=first.copy(localSubjectId="second",stage="2ª etapa",weight=3.0,topics=listOf(topic.copy(localTopicId="t2")))
        val exam=ExamCatalog.systemExams().first().copy(revision=1,subjects=listOf(first,second))
        val excluded=PracticeDecision("q1","body",exam.examId,1,"first","EXCLUDED",2,1)
        val qs=listOf(QuestionRef("q1","MAT","MAT_01_001","body"),QuestionRef("q2","MAT","MAT_01_001","body"))
        assertEquals(listOf("q2","q1"),QuizSelection.weightedSimuladoIds(exam,qs,listOf(excluded)))
        assertTrue(QuizSelection.allowed(listOf(excluded),exam,second,"q1","body"))
        assertTrue(QuizSelection.allowed(listOf(excluded),exam.copy(revision=2),first,"q1","body"))
        assertFalse(QuizSelection.allowed(listOf(excluded.copy(relation="ANALOGOUS")),exam,first,"q1","body"))
    }
    @Test fun cachedReviewDeadlineIsEnforcedLocally(){
        val q=question().copy(practiceReviewDueOn="2026-10-31")
        assertTrue(QuestionCacheMapping.withinReviewDate(q,LocalDate.of(2026,10,31)))
        assertFalse(QuestionCacheMapping.withinReviewDate(q,LocalDate.of(2026,11,1)))
    }
}
