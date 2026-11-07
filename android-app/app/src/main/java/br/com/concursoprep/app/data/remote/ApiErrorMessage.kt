package br.com.concursoprep.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException

/** Consume the error body once; malformed/empty bodies leave the HTTP fallback intact. */
internal fun HttpException.backendMessage(): String? = runCatching {
    Json.parseToJsonElement(response()?.errorBody()?.string().orEmpty())
        .jsonObject["message"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()
