package br.com.concursoprep.app.ui.screens

import androidx.compose.runtime.Composable
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import br.com.concursoprep.app.ui.theme.PreviewMatrix

fun questionSessionPreviewFixture(): QuestionDto = QuestionDto(
    id = "q-preview-1",
    question = "Qual é o prazo prescricional da pretensão punitiva?",
    questionType = QuestionType.MULTIPLE_CHOICE,
    answers = listOf("2 anos", "5 anos", "10 anos", "Imprescritível"),
    correctAnswerIndices = listOf(1),
    disciplina = "Direito Administrativo",
    source = "Cebraspe",
    examYear = 2024,
    level = "Média"
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
