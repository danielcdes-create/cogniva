package br.com.concursoprep.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.data.remote.backendMessage
import br.com.concursoprep.app.domain.model.QuestionImportDraft
import br.com.concursoprep.app.domain.model.verifyQuestionImportReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import retrofit2.HttpException
import java.util.UUID

data class QuestionImportUiState(
    val draft: QuestionImportDraft = QuestionImportDraft(),
    val loading: Boolean = true,
    val storageReady: Boolean = false,
    val busy: Boolean = false,
    val allowed: Boolean = false,
    val aiAvailable: Boolean = false,
    val message: String? = null,
)

class QuestionImportViewModel(private val repository: ConcursoRepository) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prettyJson = Json(json) { prettyPrint = true }
    private val storage = Mutex()
    private var unreadableDraft: String? = null
    private val mutable = MutableStateFlow(QuestionImportUiState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                val saved = repository.loadQuestionImportDraft()
                unreadableDraft = saved
                val draft = saved?.takeIf { it.isNotBlank() }?.let { json.decodeFromString<QuestionImportDraft>(it) } ?: QuestionImportDraft()
                mutable.value = mutable.value.copy(draft = draft, loading = false, storageReady = true)
                unreadableDraft = null
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(loading = false, message = "Não foi possível abrir o rascunho salvo: ${e.message}")
            }
            checkAccess()
        }
    }
    fun checkAccess() {
        viewModelScope.launch {
            try {
                val caps = repository.questionImportCapabilities()
                mutable.value = mutable.value.copy(allowed = true, aiAvailable = caps["ai_available"]?.jsonPrimitive?.booleanOrNull == true, message = if (mutable.value.storageReady) null else mutable.value.message)
            } catch (e: Exception) { fail(e) }
        }
    }
    private suspend fun persist() = storage.withLock {
        repository.saveQuestionImportDraft(json.encodeToString(mutable.value.draft))
    }
    fun edit(transform: (QuestionImportDraft) -> QuestionImportDraft) {
        val s = mutable.value
        if (s.loading || !s.storageReady || s.busy || s.draft.submitted) return
        val d = transform(s.draft)
        if (d.source.toByteArray(Charsets.UTF_8).size > 512 * 1024) {
            mutable.value = s.copy(message = "O material deve ter até 512 KiB. Divida-o em lotes menores.")
            return
        }
        mutable.value = s.copy(draft = d.copy(preview = null, result = null, operationId = UUID.randomUUID().toString(), aiOperationId = UUID.randomUUID().toString()), message = null)
        viewModelScope.launch { try { persist() } catch (e: Exception) { fail(e) } }
    }
    private fun fail(e: Exception) {
        if (e is CancellationException) throw e
        val message = if (e is HttpException) when (e.code()) {
            401 -> "Entre na conta para enviar. O rascunho foi preservado."
            403 -> "A inserção exige uma conta administrativa autorizada (ADMIN_EMAILS ou --grant-admin). O rascunho foi preservado."
            else -> e.backendMessage() ?: "Servidor indisponível (HTTP ${e.code()})."
        } else e.message ?: "Não foi possível concluir a operação. O rascunho atual continua na tela."
        mutable.value = mutable.value.copy(busy = false, message = message)
    }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.loading || !mutable.value.storageReady || mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { persist(); block(); persist() } catch (e: Exception) { fail(e) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun preview() = action {
        val draft = mutable.value.draft
        val response = repository.previewQuestionImport(buildJsonObject {
            put("operation_id", draft.operationId); put("items", draft.items())
            put("assistance_id", draft.assistanceId?.let(::JsonPrimitive) ?: JsonNull)
        })
        mutable.value = mutable.value.copy(draft = draft.copy(preview = response))
    }
    fun exportDraft(): String = unreadableDraft ?: prettyJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", "cogniva-question-import-draft/1")
        put("owner_user_id", repository.ownerUserId)
        put("draft", json.encodeToJsonElement(mutable.value.draft))
    })
    fun importText(text: String) {
        val envelope = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (envelope?.get("format")?.jsonPrimitive?.contentOrNull == "cogniva-question-import-draft/1") {
            try {
                require(envelope["owner_user_id"]?.jsonPrimitive?.longOrNull == repository.ownerUserId) { "Este rascunho pertence a outra conta." }
                val draft = json.decodeFromJsonElement<QuestionImportDraft>(envelope.getValue("draft"))
                require(draft.source.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Rascunho excede o limite de material por lote." }
                if (mutable.value.loading || mutable.value.busy || mutable.value.draft.submitted) return
                mutable.value = mutable.value.copy(draft = draft, storageReady = true, message = null)
                unreadableDraft = null
                viewModelScope.launch { try { persist() } catch (e: Exception) { fail(e) } }
            } catch (e: Exception) { fail(e) }
        } else edit { it.copy(mode = "BULK", source = text, assistanceId = null) }
    }
    fun assist() = action {
        val draft = mutable.value.draft
        val response = repository.assistQuestionImport(buildJsonObject {
            put("operation_id", draft.aiOperationId); put("mode", draft.aiMode); put("text", draft.source)
        })
        val items = response.getValue("items").jsonArray
        mutable.value = mutable.value.copy(draft = draft.copy(mode = "BULK", source = prettyJson.encodeToString(JsonElement.serializer(), items), assistanceId = response.getValue("assistance_id").jsonPrimitive.content, preview = null, result = null))
    }
    fun commit() = action {
        val draft = mutable.value.draft
        val preview = requireNotNull(draft.preview)
        require(preview["ready"]?.jsonPrimitive?.booleanOrNull == true) { "Corrija todas as questões antes de inserir." }
        // Persist the exact commands and uncertain-send flag BEFORE accessing the network.
        mutable.value = mutable.value.copy(draft = draft.copy(submitted = true))
        persist()
        val response = try {
            repository.commitQuestionImport(buildJsonObject {
                put("reviewed", true)
                put("commands", JsonArray(preview.getValue("items").jsonArray.map { it.jsonObject.getValue("command") }))
            })
        } catch (e: HttpException) {
            if (e.code() in 400..499) {
                mutable.value = mutable.value.copy(draft = mutable.value.draft.copy(submitted = false))
                persist()
            }
            throw e
        }
        verifyQuestionImportReceipt(preview, response)
        mutable.value = mutable.value.copy(draft = mutable.value.draft.copy(result = response))
    }
    fun newDraft() {
        if (mutable.value.busy || mutable.value.loading || !mutable.value.storageReady) return
        val d = mutable.value.draft
        if (d.submitted && d.result == null) return
        mutable.value = mutable.value.copy(draft = QuestionImportDraft(), message = null)
        viewModelScope.launch { try { persist() } catch (e: Exception) { fail(e) } }
    }
    class Factory(private val repository: ConcursoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = QuestionImportViewModel(repository) as T
    }
}
