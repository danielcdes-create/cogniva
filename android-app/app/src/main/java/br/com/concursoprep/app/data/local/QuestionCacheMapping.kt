package br.com.concursoprep.app.data.local

import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import kotlinx.serialization.json.Json

object QuestionCacheMapping {
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
            canonicalSubjectId = dto.canonicalSubjectId ?: inferCanonical(dto.disciplina)?.first,
            canonicalTopicId = dto.canonicalTopicId ?: inferCanonical(dto.disciplina)?.second
        )
    }

    fun inferCanonical(disciplina: String): Pair<String, String>? {
        val name = disciplina.lowercase()
        return when {
            name.contains("finan") -> "FIN_PUB" to "FIN_PUB_01"
            name.contains("tribut") -> "DIR_TRIB" to "DIR_TRIB_01"
            name.contains("constituc") -> "DIR_CONST" to "DIR_CONST_01"
            name.contains("administrativo") -> "DIR_ADM" to "DIR_ADM_01"
            name.contains("portugu") -> "PORT" to "PORT_01"
            name.contains("penal") && !name.contains("process") -> "DIR_PEN" to "DIR_PEN_01"
            name.contains("contabil") -> "CONTAB" to "CONTAB_01"
            else -> null
        }
    }

    fun toDto(entity: CachedQuestionEntity, json: Json): QuestionDto {
        return QuestionDto(
            id = entity.id,
            question = entity.question,
            questionType = runCatching { QuestionType.valueOf(entity.questionType) }
                .getOrDefault(QuestionType.MULTIPLE_CHOICE),
            answers = runCatching { json.decodeFromString<List<String>>(entity.answersJson) }
                .getOrDefault(emptyList()),
            correctAnswerIndices = runCatching { json.decodeFromString<List<Int>>(entity.correctIndicesJson) }
                .getOrDefault(listOf(0)),
            disciplina = entity.disciplina,
            source = entity.source,
            examYear = entity.examYear,
            level = entity.level,
            explanation = entity.explanation,
            canonicalSubjectId = entity.canonicalSubjectId,
            canonicalTopicId = entity.canonicalTopicId
        )
    }
}
