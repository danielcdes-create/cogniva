package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.ConcursoLocalDao
import br.com.concursoprep.app.data.local.DurableStateDao
import br.com.concursoprep.app.data.local.ExamDao
import br.com.concursoprep.app.data.local.LocalMetadataEntity
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.domain.model.QuestionImportDraft
import br.com.concursoprep.app.domain.model.parseQuestionImport
import br.com.concursoprep.app.domain.model.verifyQuestionImportReceipt
import br.com.concursoprep.app.ui.QuestionImportViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class QuestionImportTest {
    @Test fun existingQuestionMetadataSurvivesDraftRecoveryAndPayload() {
        val draft = QuestionImportDraft(
            originKind = "OFFICIAL_EXAM",
            sourceExamCode = " Analista / caderno 1 ",
            institution = "Órgão",
            organizer = "Banca",
            examYear = "2025",
            questionNumber = "042",
            sourceUri = "https://example.org/prova.pdf",
            sourceTitle = "Caderno oficial",
            sourceLocator = "p. 10",
            sourceDate = "2026-11-07",
            confirmOrigin = true,
            explanation = "Comentário",
            level = "Intermediária"
        )
        val restored = Json.decodeFromString<QuestionImportDraft>(Json.encodeToString(draft))
        val q = restored.items()[0].jsonObject
        assertEquals("Analista / caderno 1", q["source_exam_code"]!!.jsonPrimitive.content)
        assertEquals(2025, q["exam_year"]!!.jsonPrimitive.int)
        assertEquals("042", q["question_number"]!!.jsonPrimitive.content)
        assertEquals("Comentário", q["explanation"]!!.jsonPrimitive.content)
        assertEquals("2026-11-07T00:00:00Z", q["source_reference"]!!.jsonObject["retrieved_at"]!!.jsonPrimitive.content)
        assertTrue(q["confirm_origin"]!!.jsonPrimitive.boolean)
        assertFalse(q["confirm_classification"]!!.jsonPrimitive.boolean)
        listOf(
            draft.copy(examYear = "20e2"),
            draft.copy(examYear = "1899"),
            draft.copy(sourceDate = "2026-02-30"),
            draft.copy(sourceTitle = ""),
            draft.copy(sourceUri = "javascript:alert(1)")
        ).forEach { assertTrue(runCatching { it.items() }.isFailure) }
        assertEquals("", Json.decodeFromString<QuestionImportDraft>("""{"question":"rascunho antigo"}""").sourceExamCode)
    }

    @Test fun confirmOriginWithoutSourceReferenceIsRejectedInSingleBulkAndViewModel() {
        // 1. SINGLE mode: confirmOrigin = true with blank source_reference fields fails validation
        val noSourceDraft = QuestionImportDraft(
            mode = "SINGLE",
            originKind = "OFFICIAL_EXAM",
            question = "Enunciado oficial",
            answers = listOf("A", "B", "C", "D", "E"),
            correctIndex = 0,
            confirmOrigin = true
        )
        assertFalse(noSourceDraft.hasCompleteSourceReference)
        assertTrue(runCatching { noSourceDraft.items() }.isFailure)

        // Also fails if assistanceId is present alongside confirmOrigin = true
        val assistedWithConfirm = noSourceDraft.copy(
            sourceUri = "https://example.org/edital.pdf",
            sourceTitle = "Edital Oficial",
            sourceDate = "2026-11-10",
            assistanceId = "ai-op-1",
            confirmOrigin = true
        )
        assertTrue(assistedWithConfirm.hasCompleteSourceReference)
        assertTrue(runCatching { assistedWithConfirm.items() }.isFailure)

        // 2. BULK mode: simplified JSON item with confirm_origin = true and null/missing source_reference fails
        assertTrue(
            runCatching {
                parseQuestionImport("""[{"question_type":"TRUE_FALSE","question":"Q","answers":["Certo","Errado"],"correct_answer_indices":[0],"confirm_origin":true,"source_reference":null}]""")
            }.isFailure
        )
        assertTrue(
            runCatching {
                parseQuestionImport("""[{"question_type":"TRUE_FALSE","question":"Q","answers":["Certo","Errado"],"correct_answer_indices":[0],"confirm_origin":true}]""")
            }.isFailure
        )
    }

    @Test fun questionImportViewModelResetsSubmittedOn4xxButFreezesIdempotentCommandOn5xxAndIoFailure() = runBlocking {
        val metadata = ConcurrentHashMap<String, String>()
        var commitBehavior: (JsonObject) -> JsonObject = { throw IOException("Network dropped mid-commit") }
        val previewResponse = Json.parseToJsonElement(
            """{"ready":true,"items":[{"valid":true,"command":{"operation_id":"op-fixed-1","document":{"_id":{"${'$'}oid":"000000000000000000000001"},"question":"Q?"}}}]}"""
        ).jsonObject

        val api = Proxy.newProxyInstance(ConcursoApiService::class.java.classLoader, arrayOf(ConcursoApiService::class.java)) { _, method, args ->
            when (method.name) {
                "questionImportCapabilities" -> buildJsonObject { put("ai_available", true) }
                "previewQuestionImport" -> previewResponse
                "commitQuestionImport" -> commitBehavior(args!![0] as JsonObject)
                else -> error("Unexpected API method ${method.name}")
            }
        } as ConcursoApiService
        val dao = Proxy.newProxyInstance(ConcursoLocalDao::class.java.classLoader, arrayOf(ConcursoLocalDao::class.java)) { _, m, _ ->
            error("Unexpected dao.${m.name}")
        } as ConcursoLocalDao
        val examDao = Proxy.newProxyInstance(ExamDao::class.java.classLoader, arrayOf(ExamDao::class.java)) { _, m, _ ->
            error("Unexpected examDao.${m.name}")
        } as ExamDao
        val durable = Proxy.newProxyInstance(DurableStateDao::class.java.classLoader, arrayOf(DurableStateDao::class.java)) { _, method, args ->
            when (method.name) {
                "metadata" -> metadata[args!![0] as String]
                "putMetadata" -> {
                    val e = args!![0] as LocalMetadataEntity
                    metadata[e.key] = e.value
                    Unit
                }
                else -> error("Unexpected durable.${method.name}")
            }
        } as DurableStateDao

        val repo = ConcursoRepository(api, dao, examDao, durable = durable, ownerUserId = 123L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = QuestionImportViewModel(repo, overrideScope = scope)
            assertTrue(vm.state.value.storageReady)
            assertTrue(vm.state.value.allowed)

            // ViewModel coerces confirmOrigin = false if source_reference is incomplete during edit
            vm.edit { it.copy(question = "Questão teste", kind = "TRUE_FALSE", correctIndex = 0, confirmOrigin = true) }
            assertFalse(vm.state.value.draft.confirmOrigin)

            vm.preview()
            assertNotNull(vm.state.value.draft.preview)

            // 1. HTTP 422 resets submitted = false so the user can edit and fix validation issues
            commitBehavior = {
                throw HttpException(
                    Response.error<JsonObject>(422, """{"message":"Origem inválida"}""".toResponseBody("application/json".toMediaType()))
                )
            }
            vm.commit()
            assertFalse("4xx error must unlock draft (submitted = false)", vm.state.value.draft.submitted)
            assertEquals("Origem inválida", vm.state.value.message)

            // 2. HTTP 500 keeps submitted = true frozen in durable storage so retry reuses exact operation_id
            commitBehavior = {
                throw HttpException(
                    Response.error<JsonObject>(500, """{"message":"Erro interno"}""".toResponseBody("application/json".toMediaType()))
                )
            }
            vm.commit()
            assertTrue("5xx uncertain send must keep submitted = true", vm.state.value.draft.submitted)
            val persistedAfter500 = Json { ignoreUnknownKeys = true }
                .decodeFromString<QuestionImportDraft>(metadata.getValue("question_import_draft"))
            assertTrue(persistedAfter500.submitted)

            // While submitted && result == null, edit() and newDraft() are blocked to prevent accidental command mutation
            vm.edit { it.copy(question = "Tentativa de mutação bloqueada") }
            assertEquals("Questão teste", vm.state.value.draft.question)
            vm.newDraft()
            assertTrue(vm.state.value.draft.submitted)

            // 3. IOException on retry STILL keeps submitted = true frozen
            commitBehavior = { throw IOException("Connection reset") }
            vm.commit()
            assertTrue(vm.state.value.draft.submitted)

            // 4. Subsequent retry succeeds with matching receipt -> result stored and newDraft() unlocks
            val sentCommandIds = mutableListOf<String>()
            commitBehavior = { payload ->
                val cmd = payload.getValue("commands").jsonArray.single().jsonObject
                sentCommandIds += cmd.getValue("operation_id").jsonPrimitive.content
                Json.parseToJsonElement(
                    """{"complete":true,"items":[{"index":0,"operation_id":"op-fixed-1","id":{"${'$'}oid":"000000000000000000000001"},"saved":true,"receipt":{"status":"already_applied","revision":1}}]}"""
                ).jsonObject
            }
            vm.commit()
            assertEquals(listOf("op-fixed-1"), sentCommandIds)
            assertNotNull(vm.state.value.draft.result)

            vm.newDraft()
            assertFalse(vm.state.value.draft.submitted)
            assertEquals("", vm.state.value.draft.question)
        } finally {
            scope.cancel()
        }
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
