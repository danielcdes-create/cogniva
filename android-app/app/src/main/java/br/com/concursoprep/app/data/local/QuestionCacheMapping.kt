package br.com.concursoprep.app.data.local

import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

object QuestionCacheMapping {
    fun withRevision(dto: QuestionDto): QuestionDto {
        if(dto.questionRevision.isNotEmpty()) return dto
        val hash=java.security.MessageDigest.getInstance("SHA-256")
        fun number(value:Long){hash.update(java.nio.ByteBuffer.allocate(8).putLong(value).array())}
        fun text(value:String){val bytes=value.toByteArray(Charsets.UTF_8);number(bytes.size.toLong());hash.update(bytes)}
        text(dto.question);number(dto.answers.size.toLong());dto.answers.forEach(::text)
        number(dto.correctAnswerIndices.size.toLong());dto.correctAnswerIndices.forEach{number(it.toLong())}
        return dto.copy(questionRevision=hash.digest().joinToString(""){"%02x".format(it)})
    }
    fun toEntity(dto: QuestionDto, json: Json): CachedQuestionEntity {
        return CachedQuestionEntity(
            id = dto.id,
            question = dto.question,
            questionType = dto.questionType.name,
            answersJson = json.encodeToString(dto.answers),
            correctIndicesJson = json.encodeToString(dto.correctAnswerIndices),
            disciplina = dto.disciplina,
            source = dto.source,
            examYear = dto.examYear,
            level = dto.level,
            explanation = dto.explanation,
            inErrorNotebook = dto.userStats?.inErrorNotebook ?: false,
            isMastered = dto.userStats?.isMastered ?: false,
            userNote = null,
            canonicalSubjectId = dto.canonicalSubjectId,
            canonicalTopicId = dto.canonicalTopicId,
            payloadJson = json.encodeToString(dto)
        )
    }

    fun inferCanonical(disciplina: String): Pair<String, String>? {
        // A subject label is not evidence of a question's canonical topic.
        return null
    }

    fun toDto(entity: CachedQuestionEntity, json: Json): QuestionDto {
        entity.payloadJson?.let { payload ->
            val restored=withRevision(json.decodeFromString<QuestionDto>(payload))
            return restored.copy(userStats=restored.userStats?.copy(isMastered=entity.isMastered,inErrorNotebook=entity.inErrorNotebook))
        }
        return withRevision(QuestionDto(
            id = entity.id,
            question = entity.question,
            questionType = runCatching { QuestionType.valueOf(entity.questionType) }
                .getOrDefault(QuestionType.MULTIPLE_CHOICE),
            answers = runCatching { json.decodeFromString<List<String>>(entity.answersJson) }
                .getOrDefault(emptyList()),
            correctAnswerIndices = runCatching { json.decodeFromString<List<Int>>(entity.correctIndicesJson) }
                .getOrDefault(emptyList()),
            disciplina = entity.disciplina,
            source = entity.source,
            examYear = entity.examYear,
            level = entity.level,
            explanation = entity.explanation,
            canonicalSubjectId = entity.canonicalSubjectId,
            canonicalTopicId = entity.canonicalTopicId
        ))
    }
}
