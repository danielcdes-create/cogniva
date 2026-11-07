package br.com.concursoprep.app

import br.com.concursoprep.app.domain.model.QuestionImportDraft
import br.com.concursoprep.app.domain.model.parseQuestionImport
import br.com.concursoprep.app.domain.model.verifyQuestionImportReceipt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuestionImportTest {
    @Test fun existingQuestionMetadataSurvivesDraftRecoveryAndPayload() {
        val draft = QuestionImportDraft(originKind = "OFFICIAL_EXAM", sourceExamCode = " Analista / caderno 1 ", institution = "Órgão", organizer = "Banca", examYear = "2025", questionNumber = "042", sourceUri = "https://example.org/prova.pdf", sourceTitle = "Caderno oficial", sourceLocator = "p. 10", sourceDate = "2026-11-07", confirmOrigin = true, explanation = "Comentário", level = "Intermediária")
        val restored = Json.decodeFromString<QuestionImportDraft>(Json.encodeToString(draft))
        val q = restored.items()[0].jsonObject
        assertEquals("Analista / caderno 1", q["source_exam_code"]!!.jsonPrimitive.content)
        assertEquals(2025, q["exam_year"]!!.jsonPrimitive.int)
        assertEquals("042", q["question_number"]!!.jsonPrimitive.content)
        assertEquals("Comentário", q["explanation"]!!.jsonPrimitive.content)
        assertEquals("2026-11-07T00:00:00Z", q["source_reference"]!!.jsonObject["retrieved_at"]!!.jsonPrimitive.content)
        assertTrue(q["confirm_origin"]!!.jsonPrimitive.boolean)
        assertFalse(q["confirm_classification"]!!.jsonPrimitive.boolean)
        listOf(draft.copy(examYear = "20e2"), draft.copy(examYear = "1899"), draft.copy(sourceDate = "2026-02-30"), draft.copy(sourceTitle = ""), draft.copy(sourceUri = "javascript:alert(1)")).forEach { assertTrue(runCatching { it.items() }.isFailure) }
        assertEquals("", Json.decodeFromString<QuestionImportDraft>("""{"question":"rascunho antigo"}""").sourceExamCode)
    }
    @Test fun supportsJsonJsonlAndSingleWithoutDeduplicating() {
        assertEquals(1, parseQuestionImport("{\"question\":\"A\"}").size)
        assertEquals(2, parseQuestionImport("{\"question\":\"A\"}\n{\"question\":\"A\"}").size)
        assertEquals(2, parseQuestionImport("[{\"question\":\"A\"},{\"question\":\"A\"}]").size)
    }
    @Test fun rejectsInvalidAndOversizedBatches() {
        listOf("", "[]", "[1]", "null", "{broken}", "[" + List(51) { "{}" }.joinToString() + "]", "x".repeat(524289)).forEach { source ->
            assertTrue(source.take(30), runCatching { parseQuestionImport(source) }.isFailure)
        }
    }
    @Test fun missingCorrectOptionRemainsMissingAndTfIsCanonical() {
        val missing = QuestionImportDraft(question = "Q").items()[0].jsonObject
        assertEquals(0, missing["correct_answer_indices"]!!.jsonArray.size)
        val tf = QuestionImportDraft(kind = "TRUE_FALSE", correctIndex = 1).items()[0].jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("Certo"), JsonPrimitive("Errado"))), tf["answers"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), tf["correct_answer_indices"])
    }
    @Test fun interruptedSendRoundTripRetainsFrozenCommandsAndAssistance() {
        val command = buildJsonObject { put("operation_id", "immutable-command"); put("document", buildJsonObject { put("question", "Q") }) }
        val draft = QuestionImportDraft(mode = "BULK", source = "[]", assistanceId = "ai-receipt", submitted = true, preview = buildJsonObject {
            put("ready", true); put("items", JsonArray(listOf(buildJsonObject { put("command", command) })))
        })
        assertEquals(draft, Json.decodeFromString<QuestionImportDraft>(Json.encodeToString(draft)))
    }
    @Test fun openQuestionRequiresExplicitRubricAndHasNoFakeAlternative() {
        assertTrue(runCatching { QuestionImportDraft(kind = "OPEN_ENDED").items() }.isFailure)
        val open = QuestionImportDraft(kind = "OPEN_ENDED", reference = "Resposta", criteria = """[{"criterion_id":"c1","description":"Explicação correta","max_points":100,"essential":true}]""").items()[0].jsonObject
        assertEquals(0, open["answers"]!!.jsonArray.size)
        assertEquals(0, open["correct_answer_indices"]!!.jsonArray.size)
        assertEquals("HUMAN_REVIEW", open["grading"]!!.jsonObject["confirmation_policy"]!!.jsonPrimitive.content)
    }
    @Test fun acknowledgementMustMatchEveryFrozenIdentity() {
        val preview = Json.parseToJsonElement("""{"items":[{"command":{"operation_id":"op","document":{"_id":{"${'$'}oid":"000000000000000000000001"}}}}]}""").jsonObject
        val valid = Json.parseToJsonElement("""{"complete":true,"items":[{"index":0,"operation_id":"op","id":{"${'$'}oid":"000000000000000000000001"},"saved":true,"receipt":{"status":"already_applied","revision":1}}]}""").jsonObject
        verifyQuestionImportReceipt(preview, valid)
        val wrongOperation = JsonObject(valid + ("items" to JsonArray(listOf(JsonObject(valid["items"]!!.jsonArray[0].jsonObject + ("operation_id" to JsonPrimitive("different")))))))
        assertTrue(runCatching { verifyQuestionImportReceipt(preview, wrongOperation) }.isFailure)
        assertTrue(runCatching { verifyQuestionImportReceipt(preview, JsonObject(valid + ("items" to JsonArray(emptyList())))) }.isFailure)
        assertTrue(runCatching { verifyQuestionImportReceipt(preview, JsonObject(valid + ("complete" to JsonPrimitive(false)))) }.isFailure)
    }
}
