package br.com.concursoprep.app.data.local

import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object QuestionCacheMapping {
    private const val OBJECTIVE_GRADING_JCS =
        "{\"confirmation_policy\":\"AUTOMATIC_EXACT\",\"engine\":null,\"expected_responses\":[],\"max_score\":100,\"pass_score\":60,\"policy_version\":\"objective-exact/1\",\"rubric\":null,\"strategy\":\"EXACT_OPTION\"}"

    fun withinReviewDate(dto: QuestionDto, today: java.time.LocalDate): Boolean =
        dto.practiceReviewDueOn?.let {
            runCatching { !java.time.LocalDate.parse(it).isBefore(today) }.getOrDefault(false)
        } ?: true

    private fun jcsString(value: String): String = buildString(value.length + 8) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (ch.code < 0x20) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    /**
     * Calcula o `content_revision` único do Cogniva 2.0 via RFC 8785 (JCS) + SHA-256,
     * em paridade exata com `question_contracts::hash` (Rust), `question_contracts.py` e `web-admin/domain.js`.
     */
    fun withRevision(dto: QuestionDto): QuestionDto {
        if (dto.questionRevision.isNotEmpty()) return dto
        val qType = dto.questionType.name
        val indices = if (dto.questionType == QuestionType.OPEN_ENDED) emptyList() else dto.correctAnswerIndices
        val answersJson = dto.answers.joinToString(",", prefix = "[", postfix = "]", transform = ::jcsString)
        val indicesJson = indices.joinToString(",", prefix = "[", postfix = "]")
        val gradingJson = if (dto.questionType == QuestionType.OPEN_ENDED) "null" else OBJECTIVE_GRADING_JCS
        val canonical = buildString {
            append("{\"answers\":")
            append(answersJson)
            append(",\"correct_answer_indices\":")
            append(indicesJson)
            append(",\"grading\":")
            append(gradingJson)
            append(",\"question\":")
            append(jcsString(dto.question))
            append(",\"question_type\":")
            append(jcsString(qType))
            append('}')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return dto.copy(questionRevision = digest.joinToString("") { "%02x".format(it) })
    }

    fun toEntity(dto: QuestionDto, json: Json): CachedQuestionEntity {
        val normalized = withRevision(dto)
        return CachedQuestionEntity(
            id = normalized.id,
            question = normalized.question,
            questionType = normalized.questionType.name,
            answersJson = json.encodeToString(normalized.answers),
            correctIndicesJson = json.encodeToString(normalized.correctAnswerIndices),
            disciplina = normalized.disciplina,
            source = normalized.source,
            examYear = normalized.examYear,
            level = normalized.level,
            explanation = normalized.explanation,
            inErrorNotebook = normalized.userStats?.inErrorNotebook ?: false,
            isMastered = normalized.userStats?.isMastered ?: false,
            userNote = null,
            canonicalSubjectId = normalized.canonicalSubjectId,
            canonicalTopicId = normalized.canonicalTopicId,
            payloadJson = json.encodeToString(normalized.copy(examComponentId = null))
        )
    }

    fun inferCanonical(disciplina: String): Pair<String, String>? {
        // A subject label is not evidence of a question's canonical topic.
        return null
    }

    fun toDto(entity: CachedQuestionEntity, json: Json): QuestionDto {
        entity.payloadJson?.let { payload ->
            val restored = withRevision(json.decodeFromString<QuestionDto>(payload))
            return restored.copy(
                userStats = restored.userStats?.copy(
                    isMastered = entity.isMastered,
                    inErrorNotebook = entity.inErrorNotebook
                )
            )
        }
        return withRevision(
            QuestionDto(
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
                // Rows without the complete server DTO predate the verified
                // mapping contract. Keep their original columns for review, but
                // do not project inferred labels as authoritative topic links.
                canonicalSubjectId = null,
                canonicalTopicId = null
            )
        )
    }
}
