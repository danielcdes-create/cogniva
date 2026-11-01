package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.QuestionCacheMapping
import br.com.concursoprep.app.domain.exam.ExamEngine
import br.com.concursoprep.app.domain.exam.SubjectOutcome
import br.com.concursoprep.app.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SharedDomainContractTest {
    private val fixture=Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/domain-contracts.json")).bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    private fun question():QuestionDto { val q=fixture.getValue("question").jsonObject
        return QuestionDto(id="fixture",question=q.getValue("question").jsonPrimitive.content,answers=q.getValue("answers").jsonArray.map { it.jsonPrimitive.content },correctAnswerIndices=listOf(q.getValue("correct_index").jsonPrimitive.int),disciplina="Fixture",source="Fixture",level="Fixture")
    }
    @Test fun bothRuntimesUseTheSameUtf8RevisionAndMasteryPolicy(){
        assertEquals(fixture.getValue("question").jsonObject.getValue("revision").jsonPrimitive.content,QuestionCacheMapping.withRevision(question()).questionRevision)
        fixture.getValue("mastery").jsonArray.forEach { raw ->val v=raw.jsonObject
            assertEquals(v.getValue("expected").jsonPrimitive.double,ExamEngine.masteryFraction(v.getValue("accuracy").jsonPrimitive.double,v.getValue("mastered").jsonPrimitive.boolean,v.getValue("high_confidence_error").jsonPrimitive.boolean,v["confidence"]?.jsonPrimitive?.intOrNull),1e-9)
        }
    }
    @Test fun weightedPointsAndCalendarDaysMatchTheSharedContract(){
        fixture.getValue("scores").jsonArray.forEach { raw ->val v=raw.jsonObject
            val outcomes=v.getValue("outcomes").jsonArray.map { item->val o=item.jsonObject;SubjectOutcome(o.getValue("weight").jsonPrimitive.double,o.getValue("correct").jsonPrimitive.int,o.getValue("drawn").jsonPrimitive.int,o.getValue("wrong").jsonPrimitive.int,o.getValue("penalty").jsonPrimitive.double) }
            assertEquals(v.getValue("expected").jsonPrimitive.double,ExamEngine.weightedNetScore(outcomes),1e-9)
        }
        fixture.getValue("study_days").jsonArray.forEach { raw->val v=raw.jsonObject
            assertEquals(v.getValue("day").jsonPrimitive.content,Instant.parse(v.getValue("instant").jsonPrimitive.content).atOffset(LearningProjection.studyZone).toLocalDate().toString())
        }
    }
    @Test fun occurrenceOrderedSrsAndConfidenceMatchTheSharedContract(){
        fixture.getValue("srs").jsonArray.forEach { raw->val v=raw.jsonObject
            val events=v.getValue("statuses").jsonArray.mapIndexed { index,status->LearningEvent("e$index",Instant.parse("2026-10-01T12:00:00Z").plusSeconds(index*86400L).toString(),status.jsonPrimitive.boolean,v.getValue("confidence").jsonPrimitive.int,42) }
            val result=LearningProjection.project(null,question(),events.reversed())
            assertEquals(v.getValue("points").jsonPrimitive.double,result.points,1e-9)
            assertEquals(v.getValue("mastered").jsonPrimitive.boolean,result.isMastered)
            assertEquals(v.getValue("interval").jsonPrimitive.long,(Instant.parse(result.nextReviewAt).epochSecond-Instant.parse(events.last().occurredAt).epochSecond)/86400)
        }
    }
}
