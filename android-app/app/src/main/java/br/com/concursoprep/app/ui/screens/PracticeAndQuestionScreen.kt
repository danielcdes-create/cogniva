package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.VALID_PROBLEM_CATEGORIES
import br.com.concursoprep.app.ui.theme.CorrectGreen
import br.com.concursoprep.app.ui.theme.ErrorRed
import br.com.concursoprep.app.ui.theme.ReviewAmber
import kotlinx.coroutines.delay

/**
 * Aba "Praticar": Hub de início rápido para qualquer modalidade herdada do Bot ou nova do App
 */
@Composable
fun PracticeHubScreen(
    onLaunchSession: (QuizMode, Int) -> Unit
) {
    var selectedQuantity by remember { mutableIntStateOf(10) }
    val quantities = listOf(5, 10, 20, 40, 60)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Praticar · Baterias & Simulados",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Quantidade de questões da bateria:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quantities.forEach { q ->
                FilterChip(
                    selected = selectedQuantity == q,
                    onClick = { selectedQuantity = q },
                    label = { Text("$q") }
                )
            }
        }

        PracticeModeCard(
            title = "🔥 The Most Badass (Desafio Adaptativo)",
            description = "Seleciona as questões com maior índice de erro, tempo gasto e reincidência no seu histórico.",
            onClick = { onLaunchSession(QuizMode.MOST_BADASS, selectedQuantity) }
        )
        PracticeModeCard(
            title = "⏱️ Simulado Oficial Ponderado",
            description = "Full-screen com cronômetro, distribuição exata de pesos do edital e cálculo de nota líquida.",
            onClick = { onLaunchSession(QuizMode.SIMULADO, selectedQuantity) }
        )
        PracticeModeCard(
            title = "🔗 Tronco Comum (Multi-Concurso)",
            description = "Questões de matérias compartilhadas que avançam simultaneamente seus concursos ativos.",
            onClick = { onLaunchSession(QuizMode.COMMON_TRUNK, selectedQuantity) }
        )
        PracticeModeCard(
            title = "📕 Caderno de Erros & Pontos Cegos",
            description = "Foco cirúrgico nas questões que você errou (priorizando erros com alta confiança).",
            onClick = { onLaunchSession(QuizMode.ERROR_NOTEBOOK, selectedQuantity) }
        )
        PracticeModeCard(
            title = "🎲 Shuffle Geral / Prática Livre",
            description = "Treino sem timer com feedback imediato e tutores de IA sob demanda.",
            onClick = { onLaunchSession(QuizMode.SHUFFLE, selectedQuantity) }
        )
    }
}

@Composable
private fun PracticeModeCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Resposta registrada pelo usuário durante a sessão
 */
data class UserAttemptState(
    val chosenIndex: Int,
    val confidenceLevel: Int,
    val timeSpentSeconds: Int
)

/**
 * Tela de Questão Full-Screen com Suporte Completo ao Modo Simulado,
 * Tutores IA em Bottom Sheet e Modal de Reportar Problema Oficial.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuestionSessionScreen(
    questions: List<QuestionDto>,
    initialIndex: Int = 0,
    isSimuladoMode: Boolean,
    contestName: String = "CPNU · Auditor-Fiscal do Trabalho",
    historicalCutoffPct: Double = 78.5,
    aiTutorMarkdown: String?,
    aiTutorTitle: String?,
    onRequestAiTutor: (questionId: String, kind: String, title: String) -> Unit,
    onDismissAiSheet: () -> Unit,
    onSubmitAnswer: (question: QuestionDto, chosenIndex: Int, confidenceLevel: Int) -> Unit,
    onReportProblem: (questionId: String, category: String) -> Unit,
    onExitSession: () -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val userAnswers = remember { mutableStateMapOf<String, UserAttemptState>() }
    var isSimuladoFinished by remember { mutableStateOf(false) }
    var isSimuladoReviewMode by remember { mutableStateOf(false) }
    var showReportSheet by remember { mutableStateOf(false) }
    var reportFeedbackMessage by remember { mutableStateOf<String?>(null) }

    // Cronômetro Regressivo do Simulado (3 minutos por questão, ex: 10 questões = 30 min)
    var secondsRemaining by remember {
        mutableIntStateOf(if (isSimuladoMode) questions.size * 180 else 0)
    }

    LaunchedEffect(isSimuladoMode, isSimuladoFinished) {
        if (isSimuladoMode && !isSimuladoFinished) {
            while (secondsRemaining > 0) {
                delay(1000L)
                secondsRemaining -= 1
            }
            if (secondsRemaining <= 0) {
                isSimuladoFinished = true
            }
        }
    }

    // Se o simulado foi finalizado e o concurseiro não está em modo revisão, exibe a tela de resultado
    if (isSimuladoMode && isSimuladoFinished && !isSimuladoReviewMode) {
        val totalQuestions = questions.size
        var correctCount = 0
        var wrongCount = 0
        var blankCount = 0
        val disciplineMap = mutableMapOf<String, Pair<Int, Int>>() // disciplina -> (acertos, total)
        val hasCebraspeQuestions = questions.any { it.questionType == QuestionType.TRUE_FALSE }

        questions.forEach { q ->
            val existing = disciplineMap.getOrDefault(q.disciplina, Pair(0, 0))
            val userAttempt = userAnswers[q.id]
            val correctIndex = q.correctAnswerIndices.firstOrNull() ?: 0

            if (userAttempt == null) {
                blankCount += 1
                disciplineMap[q.disciplina] = Pair(existing.first, existing.second + 1)
            } else if (userAttempt.chosenIndex == correctIndex) {
                correctCount += 1
                disciplineMap[q.disciplina] = Pair(existing.first + 1, existing.second + 1)
            } else {
                wrongCount += 1
                disciplineMap[q.disciplina] = Pair(existing.first, existing.second + 1)
            }
        }

        val netScorePct: Double = if (hasCebraspeQuestions) {
            val netScore = (correctCount - wrongCount).coerceAtLeast(0)
            (netScore.toDouble() / totalQuestions.coerceAtLeast(1).toDouble()) * 100.0
        } else {
            (correctCount.toDouble() / totalQuestions.coerceAtLeast(1).toDouble()) * 100.0
        }

        SimuladoResultScreen(
            contestName = contestName,
            totalQuestions = totalQuestions,
            answeredQuestions = correctCount + wrongCount,
            correctCount = correctCount,
            wrongCount = wrongCount,
            blankCount = blankCount,
            netScorePct = netScorePct,
            historicalCutoffPct = historicalCutoffPct,
            disciplineBreakdown = disciplineMap,
            isCebraspeRuleApplied = hasCebraspeQuestions,
            onReviewQuestions = {
                currentIndex = 0
                isSimuladoReviewMode = true
            },
            onFinishAndExit = onExitSession
        )
        return
    }

    val question = questions.getOrNull(currentIndex) ?: return
    val currentAttempt = userAnswers[question.id]
    var selectedOptionIndex by remember(question.id) { mutableStateOf(currentAttempt?.chosenIndex) }
    var selectedConfidence by remember(question.id) { mutableIntStateOf(currentAttempt?.confidenceLevel ?: 2) }
    var isAnswerSubmitted by remember(question.id) {
        mutableStateOf(currentAttempt != null || isSimuladoReviewMode)
    }

    val confidenceOptions = listOf(
        0 to "😔 Nenhuma Ideia",
        1 to "🤔 Chute",
        2 to "😅 Mais ou Menos",
        3 to "😎 Dominei!"
    )

    val correctIndex = question.correctAnswerIndices.firstOrNull() ?: 0
    val isUserCorrect = selectedOptionIndex == correctIndex
    val isHighConfidenceError = isAnswerSubmitted && !isUserCorrect && selectedConfidence >= 2

    // Modo em que gabarito e IA ficam ocultos durante o teste
    val hideFeedback = isSimuladoMode && !isSimuladoReviewMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // 1. Top Bar da Sessão
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onExitSession) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Sair da sessão")
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Questão ${currentIndex + 1} de ${questions.size} · ${question.level}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${question.disciplina} · ${question.source}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSimuladoMode && !isSimuladoReviewMode) {
                    val minutes = secondsRemaining / 60
                    val seconds = secondsRemaining % 60
                    val timerColor = if (secondsRemaining < 300) ErrorRed else MaterialTheme.colorScheme.primary
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = timerColor)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = timerColor
                        )
                    }
                }
                IconButton(onClick = { showReportSheet = true }) {
                    Icon(Icons.Default.Flag, contentDescription = "Reportar problema na questão")
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (currentIndex + 1).toFloat() / questions.size.coerceAtLeast(1).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )

        if (hideFeedback) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⏱️ Modo Simulado Oficial: Gabarito e IAs revelados no final.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    OutlinedButton(
                        onClick = { isSimuladoFinished = true },
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Finalizar Prova", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Conteúdo Rolável: Enunciado, Metadados e Alternativas
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (question.isBorrowedAnalogous) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "[estimado] Fonte análoga: ${question.source} · Tópico de alta recorrência",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            question.userStats?.let { st ->
                Text(
                    text = "Seu histórico nesta questão: ${st.attempts} tentativa(s) · ${st.accuracyPct.toInt()}% acerto",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Enunciado Principal (22sp)
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Tutores de IA Pré-Resposta (ocultos em modo simulado ativo)
            if (!isAnswerSubmitted && !hideFeedback) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = { onRequestAiTutor(question.id, "contextual-hint", "💡 Dica Contextual Progressiva") },
                        label = { Text("💡 Pedir Dica") }
                    )
                    AssistChip(
                        onClick = { onRequestAiTutor(question.id, "dr-sincero", "🔥 Análise do Dr. Sincero") },
                        label = { Text("🔥 Dr. Sincero") }
                    )
                    AssistChip(
                        onClick = { onRequestAiTutor(question.id, "child-friendly-pre", "🧒 Simplificar Pergunta") },
                        label = { Text("🧒 Simplificar Pergunta") }
                    )
                }
            }

            // Alternativas: Cebraspe vs Múltipla Escolha
            if (question.questionType == QuestionType.TRUE_FALSE) {
                Text(
                    text = "⚠️ Padrão Cebraspe: 1 resposta errada anula 1 resposta certa.",
                    style = MaterialTheme.typography.labelMedium,
                    color = ReviewAmber
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(0 to "CERTO", 1 to "ERRADO").forEach { (idx, label) ->
                        val isSelected = selectedOptionIndex == idx
                        val showColor = isAnswerSubmitted && !hideFeedback
                        Button(
                            onClick = {
                                selectedOptionIndex = idx
                                if (hideFeedback) {
                                    val attempt = UserAttemptState(idx, selectedConfidence, 60)
                                    userAnswers[question.id] = attempt
                                    onSubmitAnswer(question, idx, selectedConfidence)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(68.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = when {
                                    showColor && idx == correctIndex -> CorrectGreen
                                    showColor && isSelected && idx != correctIndex -> ErrorRed
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Text(text = label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            } else {
                question.answers.forEachIndexed { idx, answerText ->
                    val isSelected = selectedOptionIndex == idx
                    val showColor = isAnswerSubmitted && !hideFeedback

                    val borderColor = when {
                        showColor && idx == correctIndex -> CorrectGreen
                        showColor && isSelected && idx != correctIndex -> ErrorRed
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val bgColor = when {
                        showColor && idx == correctIndex -> CorrectGreen.copy(alpha = 0.14f)
                        showColor && isSelected && idx != correctIndex -> ErrorRed.copy(alpha = 0.14f)
                        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else -> MaterialTheme.colorScheme.surface
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(2.dp, borderColor),
                        colors = CardDefaults.cardColors(containerColor = bgColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable {
                                if (!isAnswerSubmitted || hideFeedback) {
                                    selectedOptionIndex = idx
                                    if (hideFeedback) {
                                        val attempt = UserAttemptState(idx, selectedConfidence, 60)
                                        userAnswers[question.id] = attempt
                                        onSubmitAnswer(question, idx, selectedConfidence)
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val optionLetter = ('A' + idx)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = borderColor.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "$optionLetter",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Metacognição (Confiança)
            if (selectedOptionIndex != null && (!isAnswerSubmitted || hideFeedback)) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Nível de confiança nesta resposta:",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            confidenceOptions.forEach { (level, label) ->
                                FilterChip(
                                    selected = selectedConfidence == level,
                                    onClick = {
                                        selectedConfidence = level
                                        if (hideFeedback && selectedOptionIndex != null) {
                                            val attempt = UserAttemptState(selectedOptionIndex!!, level, 60)
                                            userAnswers[question.id] = attempt
                                            onSubmitAnswer(question, selectedOptionIndex!!, level)
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // Feedback Pós-Resposta (apenas quando revelado)
            if (isAnswerSubmitted && !hideFeedback) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUserCorrect) CorrectGreen.copy(alpha = 0.12f)
                        else ErrorRed.copy(alpha = 0.12f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isUserCorrect) "✅ Resposta Correta! (+Pontos SRS)"
                            else "❌ Resposta Incorreta · Adicionada ao Caderno de Erros",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isUserCorrect) CorrectGreen else ErrorRed
                        )

                        if (isHighConfidenceError) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ErrorRed.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "🚨 Alerta de Ponto Cego: Você errou com alta confiança. Prioridade máxima na próxima revisão!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ErrorRed,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        Text(
                            text = question.explanation
                                ?: "Toque em 'Análise das Alternativas' abaixo para ver o comentário pedagógico detalhado.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Aprofundar com Tutores de IA (Cache Instantâneo):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AssistChip(
                                onClick = { onRequestAiTutor(question.id, "explanation", "📚 Gabarito Comentado Completo") },
                                label = { Text("📚 Análise das Alternativas") }
                            )
                            AssistChip(
                                onClick = { onRequestAiTutor(question.id, "mnemonic", "🧠 Mnemônico de Fixação") },
                                label = { Text("🧠 Criar Mnemônico") }
                            )
                            AssistChip(
                                onClick = { onRequestAiTutor(question.id, "extra-concepts", "🎯 Conceitos-Chave & Exemplos") },
                                label = { Text("🎯 Conceitos-Chave") }
                            )
                            AssistChip(
                                onClick = { onRequestAiTutor(question.id, "plausibility", "⚖️ Plausibilidade dos Distratores") },
                                label = { Text("⚖️ Plausibilidade") }
                            )
                            AssistChip(
                                onClick = { onRequestAiTutor(question.id, "child-friendly-post", "🧒 Explicação Infantil (ELI10)") },
                                label = { Text("🧒 Explicação Simples") }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Controles Inferiores de Navegação
        if (hideFeedback) {
            // Em modo simulado, o concurseiro pode ir e voltar entre questões
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { if (currentIndex > 0) currentIndex -= 1 },
                    enabled = currentIndex > 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Text("← Anterior")
                }

                Button(
                    onClick = {
                        if (currentIndex + 1 < questions.size) {
                            currentIndex += 1
                        } else {
                            isSimuladoFinished = true
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Text(if (currentIndex + 1 < questions.size) "Próxima →" else "Entregar Simulado")
                }
            }
        } else {
            // Em treino padrão ou revisão
            if (!isAnswerSubmitted) {
                Button(
                    onClick = {
                        val chosen = selectedOptionIndex ?: return@Button
                        isAnswerSubmitted = true
                        val attempt = UserAttemptState(chosen, selectedConfidence, 60)
                        userAnswers[question.id] = attempt
                        onSubmitAnswer(question, chosen, selectedConfidence)
                    },
                    enabled = selectedOptionIndex != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Confirmar Resposta", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isSimuladoReviewMode && currentIndex > 0) {
                        OutlinedButton(
                            onClick = { currentIndex -= 1 },
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Text("← Anterior")
                        }
                    }
                    Button(
                        onClick = {
                            if (currentIndex + 1 < questions.size) {
                                currentIndex += 1
                            } else {
                                onExitSession()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (currentIndex + 1 < questions.size) "Próxima Questão →" else "Finalizar Bateria",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }

    // Modal de Reporte de Problemas (20 categorias oficiais herdadas do AFTBot.py)
    if (showReportSheet) {
        ModalBottomSheet(onDismissRequest = { showReportSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = null,
                        tint = ErrorRed
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reportar Inconsistência na Questão",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Text(
                    text = "Selecione a categoria do problema para envio imediato à fila pedagógica:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                VALID_PROBLEM_CATEGORIES.forEach { category ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onReportProblem(question.id, category)
                                reportFeedbackMessage = "Problema registrado! Agradecemos o reporte."
                                showReportSheet = false
                            }
                    ) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Bottom Sheet de Tutor de IA
    if (aiTutorMarkdown != null) {
        ModalBottomSheet(onDismissRequest = onDismissAiSheet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = aiTutorTitle ?: "Tutor IA",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
                Text(
                    text = aiTutorMarkdown,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onDismissAiSheet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Fechar e voltar para a questão")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Tela de Resultado do Simulado com Nota Líquida, Comparação com Nota de Corte e Desempenho por Matéria
 */
@Composable
fun SimuladoResultScreen(
    contestName: String,
    totalQuestions: Int,
    answeredQuestions: Int,
    correctCount: Int,
    wrongCount: Int,
    blankCount: Int,
    netScorePct: Double,
    historicalCutoffPct: Double,
    disciplineBreakdown: Map<String, Pair<Int, Int>>,
    isCebraspeRuleApplied: Boolean,
    onReviewQuestions: () -> Unit,
    onFinishAndExit: () -> Unit
) {
    val isApproved = netScorePct >= historicalCutoffPct

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Resultado do Simulado Oficial",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = contestName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // Card Principal de Nota Líquida & Corte
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isApproved) CorrectGreen.copy(alpha = 0.15f)
                else ReviewAmber.copy(alpha = 0.15f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Aproveitamento Líquido",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = String.format("%.1f%%", netScorePct),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isApproved) CorrectGreen else ReviewAmber
                )
                Text(
                    text = if (isCebraspeRuleApplied) {
                        "⚠️ Regra Cebraspe aplicada: cada erro anulou um acerto ($correctCount certos - $wrongCount errados)."
                    } else {
                        "Padrão Oficial: $correctCount acertos de $totalQuestions questões."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isApproved) CorrectGreen else ReviewAmber
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isApproved) {
                            "🎯 Na Zona de Aprovação! Corte histórico de referência: ${historicalCutoffPct}%"
                        } else {
                            val diff = historicalCutoffPct - netScorePct
                            "⚠️ Abaixo da Nota de Corte de Referência (${historicalCutoffPct}%). Diferença de ${String.format("%.1f", diff)}%."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Grade Resumo de Métricas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                title = "Acertos",
                value = "$correctCount",
                color = CorrectGreen,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Erros",
                value = "$wrongCount",
                color = ErrorRed,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Em Branco",
                value = "$blankCount",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }

        // Alerta de Automação no Caderno de Erros
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "📕 Caderno de Erros & Fila SRS Atualizados",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Todas as $wrongCount questões erradas foram salvas localmente no Room e integradas ao algoritmo SRS para reapresentação em 1, 7 ou 30 dias.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Desempenho por Disciplina
        Text(
            text = "Desempenho por Disciplina no Simulado:",
            style = MaterialTheme.typography.titleMedium
        )
        disciplineBreakdown.forEach { (discipline, stats) ->
            val correct = stats.first
            val total = stats.second
            val pct = if (total > 0) (correct.toDouble() / total.toDouble()) * 100.0 else 0.0

            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = discipline, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "$correct / $total (${pct.toInt()}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (pct / 100.0).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (pct >= 70.0) CorrectGreen else ReviewAmber
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Ações de Conclusão
        Button(
            onClick = onReviewQuestions,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Revisar Gabarito do Simulado", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedButton(
            onClick = onFinishAndExit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Concluir e Voltar ao Início", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
