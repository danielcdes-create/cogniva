package br.com.concursoprep.app

import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.data.remote.PracticeDecision
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CatalogAdministrationTest {
    private fun base()=checkNotNull(javaClass.getResourceAsStream("/knowledge-catalog.json")).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun next(base:JsonObject,deprecated:Boolean=false):JsonObject=JsonObject(base+mapOf(
        "catalog_version" to JsonPrimitive(2),
        "trunks" to JsonArray(base.getValue("trunks").jsonArray.map { value ->val row=value.jsonObject;if(row["trunk_id"]?.jsonPrimitive?.content=="fiscal")JsonObject(row+("label" to JsonPrimitive("Fiscal revisado")))else row }),
        "subjects" to JsonArray(base.getValue("subjects").jsonArray.map { value ->val row=value.jsonObject;if(row["subject_id"]?.jsonPrimitive?.content=="MAT")JsonObject(row+mapOf("label" to JsonPrimitive("Matemática revisada"),"status" to JsonPrimitive(if(deprecated)"DEPRECATED"else"ACTIVE"),"trunk_links" to buildJsonArray { add(buildJsonObject {put("trunk_id","fiscal");put("role","FOUNDATIONAL");put("basis","EDITORIAL_SUGGESTION");put("note","Reviewed fixture")}) }))else row })
    ))
    @Test fun activatedLabelsAndTrunkRelationshipsReachTheClientWithoutRebuildingTemplates(){
        val base=base()
        try{
            ExamCatalog.installKnowledge(next(base))
            val fiscal=ExamCatalog.trunks().first { it.slug=="fiscal" }
            assertEquals("Fiscal revisado",fiscal.name)
            val math=fiscal.subjects.first { it.canonicalSubjectId=="MAT" }
            assertEquals("Matemática revisada",math.name)
            assertEquals("CORE",math.relevanceTier)
            assertTrue(math.topics.isNotEmpty())
        }finally{ExamCatalog.installKnowledge(base)}
    }
    @Test fun catalogRenameKeepsOldExclusionsAndDeprecationDoesNotOfferNewTopics(){
        val base=base()
        try{
            ExamCatalog.installKnowledge(next(base))
            val subject=StoredSubject("first","MAT","Old label","CONHECIMENTOS_GERAIS",1.0,1,"CORE",emptyList(),"1ª etapa")
            val exam=ExamCatalog.systemExams().first().copy(revision=1,subjects=listOf(subject))
            val decision=PracticeDecision("q","body",exam.examId,1,"first","EXCLUDED",1,1)
            assertFalse(QuizSelection.allowed(listOf(decision),exam,subject,"q","body"))
            assertTrue(QuizSelection.allowed(listOf(decision.copy(catalogVersion=3)),exam,subject,"q","body"))
            ExamCatalog.installKnowledge(next(base,true))
            assertFalse(ExamCatalog.allSubjects().any { it.canonicalSubjectId=="MAT" })
            assertTrue(ExamCatalog.catalogTopics("MAT").isEmpty())
            assertTrue(ExamCatalog.defaultTopicsForSubject("MAT").isEmpty())
        }finally{ExamCatalog.installKnowledge(base)}
    }
}
