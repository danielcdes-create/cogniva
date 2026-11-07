package br.com.concursoprep.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable
data class QuestionImportDraft(
    val mode: String = "SINGLE",
    val kind: String = "MULTIPLE_CHOICE",
    val originKind: String = "LEGACY_IMPORT",
    val question: String = "",
    val answers: List<String> = List(5) { "" },
    val correctIndex: Int = -1,
    val reference: String = "",
    val criteria: String = "",
    val subjectId: String = "",
    val topicId: String = "",
    val confirmClassification: Boolean = false,
    val source: String = "",
    val assistanceId: String? = null,
    val operationId: String = UUID.randomUUID().toString(),
    val aiOperationId: String = UUID.randomUUID().toString(),
    val aiMode: String = "STRUCTURE",
    val preview: JsonObject? = null,
    val result: JsonObject? = null,
    val submitted: Boolean = false,
) {
    fun items(): JsonArray {
        if (mode == "BULK") return parseQuestionImport(source)
        val options = when (kind) {
            "TRUE_FALSE" -> listOf("Certo", "Errado")
            "OPEN_ENDED" -> emptyList()
            else -> answers
        }
        val grading = if (kind == "OPEN_ENDED") buildJsonObject {
            put("strategy", "RUBRIC"); put("max_score", 100); put("pass_score", 60)
            put("expected_responses", JsonArray(listOf(JsonPrimitive(reference))))
            put("rubric", buildJsonObject {
                put("revision", 1)
                val parsed = Json.parseToJsonElement(criteria)
                require(parsed is JsonArray && parsed.isNotEmpty()) { "Informe os critérios da rubrica como array JSON." }
                put("criteria", parsed)
            })
            put("engine", JsonNull); put("confirmation_policy", "HUMAN_REVIEW")
            put("policy_version", "open-rubric-human-review/1")
        } else JsonNull
        return JsonArray(listOf(buildJsonObject {
            put("question_type", kind); put("question", question)
            put("origin_kind", originKind)
            put("answers", JsonArray(options.map(::JsonPrimitive)))
            put("correct_answer_indices", JsonArray(if (kind == "OPEN_ENDED" || correctIndex < 0) emptyList() else listOf(JsonPrimitive(correctIndex))))
            put("grading", grading)
            put("subject_id", subjectId); put("topic_id", topicId)
            put("confirm_classification", confirmClassification)
        }))
    }
}

fun parseQuestionImport(source: String): JsonArray {
    require(source.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "O lote deve ter até 512 KiB." }
    require(source.isNotBlank()) { "Cole ou importe as questões em JSON ou JSONL." }
    val value = runCatching { Json.parseToJsonElement(source) }.getOrElse {
        JsonArray(source.lineSequence().filter { it.isNotBlank() }.mapIndexed { index, line ->
            try { Json.parseToJsonElement(line) } catch (_: Exception) { error("JSON inválido na linha ${index + 1}.") }
        }.toList())
    }
    val items = when (value) {
        is JsonArray -> value
        is JsonObject -> JsonArray(listOf(value))
        else -> error("Informe objeto JSON, array JSON ou JSONL.")
    }
    require(items.size in 1..50) { "Cada lote deve conter de 1 a 50 questões." }
    require(items.all { it is JsonObject }) { "Cada questão deve ser um objeto JSON." }
    return items
}

fun verifyQuestionImportReceipt(preview: JsonObject, response: JsonObject) {
    val expected = preview.getValue("items").jsonArray
    val received = response.getValue("items").jsonArray
    require(received.size == expected.size) { "Recibo incompleto; os comandos foram preservados para conferência." }
    received.forEachIndexed { index, value ->
        val item = value.jsonObject
        val command = expected[index].jsonObject.getValue("command").jsonObject
        require(item["index"]?.jsonPrimitive?.intOrNull == index && item["id"] == command.getValue("document").jsonObject["_id"] && item["operation_id"] == command["operation_id"]) { "Recibo não corresponde ao lote enviado; confira usando os mesmos comandos." }
        if (item["saved"]?.jsonPrimitive?.booleanOrNull == true) {
            val receipt = item.getValue("receipt").jsonObject
            require(receipt["status"]?.jsonPrimitive?.content in listOf("applied", "already_applied") && receipt["revision"]?.jsonPrimitive?.intOrNull == 1) { "Confirmação de gravação inválida." }
        }
    }
    require(response["complete"]?.jsonPrimitive?.booleanOrNull == received.all { it.jsonObject["saved"]?.jsonPrimitive?.booleanOrNull == true }) { "Totais do recibo divergem dos itens." }
}
