package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.exam.CognitiveStrategy
import br.com.concursoprep.app.domain.exam.DeclaredConfidence
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.SessionAnswer
import br.com.concursoprep.app.domain.model.SessionSnapshot
import br.com.concursoprep.app.ui.components.ConfidenceSelectorGrid
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import br.com.concursoprep.app.ui.theme.PreviewMatrix

fun questionSessionPreviewFixture(): QuestionDto = QuestionDto(
    id = "q-preview-1",
    question = "Qual é o prazo prescricional da pretensão punitiva?",
    questionType = QuestionType.MULTIPLE_CHOICE,
    answers = listOf("2 anos", "5 anos", "10 anos", "15 anos", "Imprescritível"),
    correctAnswerIndices = listOf(1),
    disciplina = "Direito Administrativo",
    source = "Cebraspe",
    examYear = 2024,
    level = "Média",
    canonicalSubjectId = "DIR_ADM",
    canonicalTopicId = "DIR_ADM_01_001"
)

@Composable
fun QuestionSessionPreviewContent() {
    ConcursoPrepTheme {
        QuestionSessionScreen(
            questions = listOf(questionSessionPreviewFixture()),
            isSimuladoMode = false,
            aiTutorMarkdown = null,
            aiTutorTitle = null,
            onRequestAiTutor = { _, _, _, _ -> },
            onDismissAiSheet = {},
            onSubmitAnswer = { _, _, _, _ -> true },
            onReportProblem = { _, _ -> },
            onExitSession = {},
            initialSelectedOptionIndex = 0
        )
    }
}

@PreviewMatrix
@Composable
fun QuestionSessionPreviewMatrix() {
    QuestionSessionPreviewContent()
}

@PreviewMatrix
@Composable
fun QuestionStrategySelectorPreviewMatrix() {
    ConcursoPrepTheme {
        Surface {
            ConfidenceSelectorGrid(
                selectedConfidence = DeclaredConfidence.MEDIUM.toLegacyBand(),
                onSelectConfidence = {},
                hasExplicitSelection = true,
                selectedDeclaredConfidence = DeclaredConfidence.MEDIUM,
                onSelectDeclaredConfidence = {},
                showStrategySelector = true,
                selectedStrategy = CognitiveStrategy.LOGICAL_DEDUCTION,
                onSelectStrategy = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@PreviewMatrix
@Composable
fun QuestionGuessDisambiguationPreviewMatrix() {
    ConcursoPrepTheme {
        Surface {
            ConfidenceSelectorGrid(
                selectedConfidence = DeclaredConfidence.GUESS.toLegacyBand(),
                onSelectConfidence = {},
                hasExplicitSelection = true,
                selectedDeclaredConfidence = DeclaredConfidence.GUESS,
                onSelectDeclaredConfidence = {},
                showStrategySelector = true,
                selectedStrategy = CognitiveStrategy.ELIMINATION,
                onSelectStrategy = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@PreviewMatrix
@Composable
fun QuestionPostAnswerFeedbackPreviewMatrix() {
    val q = questionSessionPreviewFixture().copy(
        explanation = "O prazo prescricional da ação punitiva da Administração Pública Federal é de cinco anos, nos termos do Art. 1º da Lei nº 9.873/1999."
    )
    val snapshot = SessionSnapshot(
        sessionId = "preview-post-answer",
        ownerUserId = 1L,
        questions = listOf(q),
        mode = QuizMode.STANDARD,
        exam = null,
        questionStartedAtEpochMs = 1_700_000_000_000L,
        sessionStartedAtEpochMs = 1_700_000_000_000L,
        answers = mapOf(
            q.id to SessionAnswer(
                chosenIndex = 0,
                confidenceLevel = 3,
                timeSpentSeconds = 33,
                submitted = true,
                confidenceExplicit = true,
                declaredConfidence = DeclaredConfidence.HIGH,
                cognitiveStrategy = CognitiveStrategy.CONCEPTUAL_KNOWLEDGE,
                activeSeconds = 33
            )
        )
    )
    ConcursoPrepTheme {
        QuestionSessionScreen(
            questions = listOf(q),
            isSimuladoMode = false,
            snapshot = snapshot,
            aiTutorMarkdown = null,
            aiTutorTitle = null,
            onRequestAiTutor = { _, _, _, _ -> },
            onDismissAiSheet = {},
            onSubmitAnswer = { _, _, _, _ -> true },
            onReportProblem = { _, _ -> },
            onExitSession = {}
        )
    }
}
