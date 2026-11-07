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
