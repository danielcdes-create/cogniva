package br.com.concursoprep.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import br.com.concursoprep.app.domain.model.SessionSnapshot
import br.com.concursoprep.app.domain.model.SessionAnswer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.R
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.VALID_PROBLEM_CATEGORIES
import br.com.concursoprep.app.ui.components.ConfidenceSelectorGrid
import br.com.concursoprep.app.ui.components.MarkdownParser
import br.com.concursoprep.app.ui.components.OptionCard
import br.com.concursoprep.app.ui.components.QuestionActionChips
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.LocalExtendedColors
import kotlinx.coroutines.delay

/**
 * Aba "Praticar": Hub de início rápido para qualquer modalidade herdada do Bot ou nova do App
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeHubScreen(
    onLaunchSession: (QuizMode, Int) -> Unit
) {
    var selectedQuantity by rememberSaveable { mutableIntStateOf(10) }
    val quantities = listOf(5, 10, 20, 40, 60)

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CognivaBrandHeader(
                ecosystemProduct = "Cogniva Questions · Exams",
                subtitle = "Banco de questões, simulados & Cogniva AI"
            )
            Text(
                text = "Quantidade de questões da bateria:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                quantities.forEach { q ->
                    FilterChip(
                        selected = selectedQuantity == q,
                        onClick = { selectedQuantity = q },
                        label = { Text("$q") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            PracticeModeCard(
                title = "Revisão Espaçada (SRS)",
                description = "Prioridade máxima: questões com curva de esquecimento ativa para retenção de longo prazo.",
                onClick = { onLaunchSession(QuizMode.SRS_REVIEW, selectedQuantity) }
            )
            PracticeModeCard(
                title = "Cogniva Questions · The Most Badass",
                description = "Questões já praticadas e ainda não dominadas, priorizadas pela menor acurácia.",
                onClick = { onLaunchSession(QuizMode.MOST_BADASS, selectedQuantity) }
            )
            PracticeModeCard(
                title = "Cogniva Exams · Simulado Ponderado",
                description = "Usa a grade selecionada em Concursos. Exige questões válidas e vínculos confirmados para os tópicos da prova.",
                onClick = { onLaunchSession(QuizMode.SIMULADO, selectedQuantity) }
            )
            PracticeModeCard(
                title = "Tronco Comum (Multi-Concurso)",
                description = "Questões de matérias compartilhadas que avançam simultaneamente seus concursos ativos.",
                onClick = { onLaunchSession(QuizMode.COMMON_TRUNK, selectedQuantity) }
            )
            PracticeModeCard(
                title = "Caderno de Erros & Pontos Cegos",
                description = "Foco cirúrgico nas questões que você errou (priorizando erros com alta confiança).",
                onClick = { onLaunchSession(QuizMode.ERROR_NOTEBOOK, selectedQuantity) }
            )
            PracticeModeCard(
                title = "Shuffle Geral / Prática Livre",
                description = "Todo o acervo válido, independente do concurso selecionado. Treino sem cronômetro, com feedback e tutores de IA.",
                onClick = { onLaunchSession(QuizMode.SHUFFLE, selectedQuantity) }
            )
        }
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
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
typealias UserAttemptState = SessionAnswer

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
    contestName: String = "Treino",
    historicalCutoffPct: Double = 0.0,
    aiTutorMarkdown: String?,
    aiTutorTitle: String?,
    onRequestAiTutor: (questionId: String, kind: String, title: String, hintIndex: Int?) -> Unit,
    onDismissAiSheet: () -> Unit,
    onSubmitAnswer: suspend (question: QuestionDto, chosenIndex: Int, confidenceLevel: Int, timeSpentSeconds: Int) -> Boolean,
    onReportProblem: (questionId: String, category: String) -> Unit,
    onExitSession: () -> Unit,
    initialSelectedOptionIndex: Int? = null,
    snapshot: SessionSnapshot? = null,
    onUpdateSession: (SessionSnapshot) -> Unit = {},
    onCompleteSession: () -> Unit = onExitSession,
    snackbarState: androidx.compose.material3.SnackbarHostState? = null
) {
    var currentIndex by rememberSaveable(snapshot?.sessionId) { mutableIntStateOf(snapshot?.currentIndex ?: initialIndex) }
    val userAnswers = remember(snapshot?.sessionId) { mutableStateMapOf<String, UserAttemptState>().apply { putAll(snapshot?.answers.orEmpty()) } }
    val actionScope=rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var isSimuladoFinished by rememberSaveable(snapshot?.sessionId) { mutableStateOf(snapshot?.finished ?: false) }
    var isSimuladoReviewMode by rememberSaveable(snapshot?.sessionId) { mutableStateOf(snapshot?.reviewing ?: false) }
    var showReportSheet by rememberSaveable { mutableStateOf(false) }
    var questionStartEpochMs by rememberSaveable(snapshot?.sessionId,currentIndex) { mutableStateOf(if(snapshot?.currentIndex==currentIndex) snapshot.questionStartedAtEpochMs else System.currentTimeMillis()) }

    val handleBackNavigation = {
        if (isSimuladoReviewMode) {
            isSimuladoReviewMode = false
        } else {
            onExitSession()
        }
    }

    BackHandler(enabled = !showReportSheet && aiTutorMarkdown == null) {
        handleBackNavigation()
    }

    LaunchedEffect(currentIndex) {
        onDismissAiSheet()
    }

    val deadline by rememberSaveable(snapshot?.sessionId) { mutableStateOf(snapshot?.deadlineEpochMs ?: (System.currentTimeMillis()+questions.size*180000L)) }
    var secondsRemaining by remember { mutableIntStateOf(((deadline-System.currentTimeMillis()).coerceAtLeast(0)/1000L).toInt()) }
    LaunchedEffect(isSimuladoMode,isSimuladoFinished,deadline) {
        if(isSimuladoMode&&!isSimuladoFinished) {
            while(true) {
                secondsRemaining=((deadline-System.currentTimeMillis()).coerceAtLeast(0)/1000L).toInt()
                if(System.currentTimeMillis()>=deadline){isSimuladoFinished=true;break}
                delay(250L)
            }
        }
    }
    LaunchedEffect(currentIndex,userAnswers.toMap(),isSimuladoFinished,isSimuladoReviewMode) {
        snapshot?.let { onUpdateSession(it.copy(currentIndex=currentIndex,answers=userAnswers.toMap(),finished=isSimuladoFinished,reviewing=isSimuladoReviewMode,questionStartedAtEpochMs=questionStartEpochMs)) }
    }
    LaunchedEffect(isSimuladoFinished) {
        if(isSimuladoMode&&isSimuladoFinished) {
            saving=true
            try {
                for((id,answer) in userAnswers.toMap()) {
                    if(!answer.submitted) {
                        val q=questions.firstOrNull { it.id==id } ?: continue
                        if(onSubmitAnswer(q,answer.chosenIndex,answer.confidenceLevel,answer.timeSpentSeconds)) userAnswers[id]=answer.copy(submitted=true)
                    }
                }
            } finally {saving=false}
        }
    }

    if (isSimuladoMode && isSimuladoFinished && !isSimuladoReviewMode) {
        val totalQuestions = questions.size
        var correctCount = 0
        var wrongCount = 0
        var blankCount = 0
        val disciplineMap = mutableMapOf<String, Pair<Int, Int>>()
        val penalty=snapshot?.exam?.wrongAnswerPenalty ?: 0.0
        val hasCebraspeQuestions = penalty>0.0

        questions.forEach { q ->
            val existing = disciplineMap.getOrDefault(q.disciplina, Pair(0, 0))
            val userAttempt = userAnswers[q.id]
            val correctIndex = q.correctAnswerIndices.singleOrNull() ?: -1

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

        fun weight(q:QuestionDto):Double = snapshot?.exam?.subjects?.firstOrNull { if(q.examComponentId!=null)it.localSubjectId==q.examComponentId else it.canonicalSubjectId==q.canonicalSubjectId || it.displayName==q.disciplina }?.weight ?: 1.0
        val outcomes=questions.groupBy { it.examComponentId ?: it.canonicalSubjectId ?: it.disciplina }.values.map { group ->
            val correct=group.count { q->userAnswers[q.id]?.chosenIndex in q.correctAnswerIndices }
            val wrong=group.count { q->userAnswers[q.id]!=null&&userAnswers[q.id]?.chosenIndex !in q.correctAnswerIndices }
            br.com.concursoprep.app.domain.exam.SubjectOutcome(weight(group.first()),correct,group.size,wrong,penalty)
        }
        val netScorePct=br.com.concursoprep.app.domain.exam.ExamEngine.weightedNetScore(outcomes)
        if(saving) { androidx.compose.material3.CircularProgressIndicator(); return }

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
            snackbarState = snackbarState,
            onReviewQuestions = {
                currentIndex = 0
                isSimuladoReviewMode = true
            },
            onFinishAndExit = { if(userAnswers.values.all { it.submitted }) onCompleteSession() else onExitSession() }
        )
        return
    }

    val question = questions.getOrNull(currentIndex) ?: return
    val currentAttempt = userAnswers[question.id]
    var selectedOptionIndex by remember(question.id, isSimuladoReviewMode) {
        mutableStateOf(currentAttempt?.chosenIndex ?: initialSelectedOptionIndex)
    }
    var selectedConfidence by remember(question.id, isSimuladoReviewMode) {
        mutableIntStateOf(currentAttempt?.confidenceLevel ?: 2)
    }
    var isAnswerSubmitted by remember(question.id, isSimuladoReviewMode) {
        mutableStateOf(currentAttempt?.submitted == true || isSimuladoReviewMode)
    }
    var hintClickCount by remember(question.id) { mutableIntStateOf(0) }

    val computeElapsedSeconds = {
        ((System.currentTimeMillis() - questionStartEpochMs) / 1000L).toInt().coerceIn(1, 3600)
    }

    val correctIndex = question.correctAnswerIndices.singleOrNull() ?: -1
    val isBlankAnswer = isSimuladoReviewMode && selectedOptionIndex == null
    val isUserCorrect = selectedOptionIndex != null && selectedOptionIndex == correctIndex
    val isHighConfidenceError = isAnswerSubmitted && !isBlankAnswer && !isUserCorrect && selectedConfidence >= 2
    val hideFeedback = isSimuladoMode && !isSimuladoReviewMode
    val ext = LocalExtendedColors.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { snackbarState?.let { androidx.compose.material3.SnackbarHost(it) } },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        )
                        .testTag("question_top_bar")
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = handleBackNavigation) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Sair da sessão"
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Questão ${currentIndex + 1} de ${questions.size} · ${question.level}",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${question.disciplina} · ${question.source}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSimuladoMode && !isSimuladoReviewMode) {
                                val minutes = secondsRemaining / 60
                                val seconds = secondsRemaining % 60
                                val timerColor = if (secondsRemaining < 300) ext.error else MaterialTheme.colorScheme.primary
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
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (hideFeedback) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (currentIndex > 0) {
                                OutlinedButton(
                                    onClick = {
                                        onDismissAiSheet()
                                        currentIndex -= 1
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 52.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_previous_question),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    onDismissAiSheet()
                                    if (currentIndex + 1 < questions.size) {
                                        currentIndex += 1
                                    } else {
                                        isSimuladoFinished = true
                                    }
                                },
                                modifier = Modifier
                                    .weight(if (currentIndex > 0) 1.15f else 1f)
                                    .heightIn(min = 52.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (currentIndex + 1 < questions.size) {
                                        stringResource(R.string.action_next_question)
                                    } else {
                                        stringResource(R.string.action_deliver_simulado)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else if (!isAnswerSubmitted) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (currentIndex > 0) {
                                OutlinedButton(
                                    onClick = {
                                        onDismissAiSheet()
                                        currentIndex -= 1
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 52.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_previous_question),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    val chosen = selectedOptionIndex ?: return@Button
                                    val elapsed = computeElapsedSeconds()
                                    actionScope.launch {
                                        saving=true
                                        try {
                                            if(onSubmitAnswer(question,chosen,selectedConfidence,elapsed)) {
                                                isAnswerSubmitted=true
                                                userAnswers[question.id]=UserAttemptState(chosen,selectedConfidence,elapsed,true)
                                            }
                                        }finally{saving=false}
                                    }
                                },
                                enabled = selectedOptionIndex != null && !saving && correctIndex>=0,
                                modifier = Modifier
                                    .testTag("confirm_answer_cta")
                                    .weight(if (currentIndex > 0) 1.15f else 1f)
                                    .heightIn(min = 52.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.action_confirm_answer),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (currentIndex > 0) {
                                OutlinedButton(
                                    onClick = {
                                        onDismissAiSheet()
                                        currentIndex -= 1
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 52.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_previous_question),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    onDismissAiSheet()
                                    if (currentIndex + 1 < questions.size) {
                                        currentIndex += 1
                                    } else if (isSimuladoReviewMode) {
                                        isSimuladoReviewMode = false
                                    } else {
                                        onCompleteSession()
                                    }
                                },
                                modifier = Modifier
                                    .weight(if (currentIndex > 0) 1.15f else 1f)
                                    .heightIn(min = 52.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = when {
                                        currentIndex + 1 < questions.size -> stringResource(R.string.action_next_question)
                                        isSimuladoReviewMode -> stringResource(R.string.action_back_to_results)
                                        else -> stringResource(R.string.action_finish_session)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hideFeedback) {
                    item(key = "simulado_banner") {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Modo Simulado Oficial: Gabarito e IAs revelados no final.",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { isSimuladoFinished = true },
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_finish_simulado),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                if (question.isBorrowedAnalogous) {
                    item(key = "borrowed_badge") {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = "[estimado] Fonte análoga: ${question.source} · Tópico de alta recorrência",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                question.userStats?.let { st ->
                    item(key = "user_stats") {
                        Text(
                            text = "Seu histórico nesta questão: ${st.attempts} tentativa(s) · ${st.accuracyPct.toInt()}% acerto",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                item(key = "question_text") {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = question.question,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                if (!isAnswerSubmitted && !hideFeedback) {
                    item(key = "pre_chips") {
                        QuestionActionChips(
                            hintCount = hintClickCount,
                            onHint = {
                                val hIdx = hintClickCount
                                hintClickCount += 1
                                onRequestAiTutor(
                                    question.id,
                                    "contextual-hint",
                                    "Dica Contextual Progressiva #${hIdx + 1}",
                                    hIdx
                                )
                            },
                            onSincereDoctor = {
                                onRequestAiTutor(question.id, "dr-sincero", "Análise do Dr. Sincero", null)
                            },
                            onSimplify = {
                                onRequestAiTutor(question.id, "child-friendly-pre", "Simplificar Pergunta", null)
                            }
                        )
                    }
                }

                if (question.questionType == QuestionType.TRUE_FALSE) {
                    item(key = "cebraspe_warning") {
                        Text(
                            text = if(isSimuladoMode) "Penalidade cadastrada nesta prova: ${snapshot?.exam?.wrongAnswerPenalty ?: 0.0} ponto(s) por erro." else "Formato Certo/Errado · treino com feedback imediato.",
                            style = MaterialTheme.typography.labelMedium,
                            color = ext.warning
                        )
                    }
                    item(key = "cebraspe_options") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            question.answers.mapIndexed { index,label->index to label }.forEach { (idx, label) ->
                                val isSelected = selectedOptionIndex == idx
                                val showColor = isAnswerSubmitted && !hideFeedback
                                Button(
                                    onClick = {
                                        if (!isAnswerSubmitted || hideFeedback) {
                                            selectedOptionIndex = idx
                                            if (!isAnswerSubmitted || hideFeedback) {
                                                val elapsed = computeElapsedSeconds()
                                                val attempt = UserAttemptState(idx, selectedConfidence, elapsed)
                                                userAnswers[question.id] = attempt
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when {
                                            showColor && idx == correctIndex -> ext.correct
                                            showColor && isSelected && idx != correctIndex -> ext.error
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        contentColor = when {
                                            showColor && idx == correctIndex -> ext.onCorrect
                                            showColor && isSelected && idx != correctIndex -> ext.onError
                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                ) {
                                    Text(text = label, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                } else {
                    items(
                        count = question.answers.size,
                        key = { idx -> "answer_$idx" }
                    ) { idx ->
                        val answerText = question.answers[idx]
                        OptionCard(
                            letter = ('A' + idx).toString(),
                            text = answerText,
                            selected = selectedOptionIndex == idx,
                            isAnswerSubmitted = isAnswerSubmitted,
                            isCorrectOption = idx == correctIndex,
                            hideFeedback = hideFeedback,
                            onClick = {
                                if (!isAnswerSubmitted || hideFeedback) {
                                    selectedOptionIndex = idx
                                    if (!isAnswerSubmitted || hideFeedback) {
                                        val elapsed = computeElapsedSeconds()
                                        val attempt = UserAttemptState(idx, selectedConfidence, elapsed)
                                        userAnswers[question.id] = attempt
                                    }
                                }
                            }
                        )
                    }
                }

                if (selectedOptionIndex != null && (!isAnswerSubmitted || hideFeedback)) {
                    item(key = "confidence_grid") {
                        ConfidenceSelectorGrid(
                            selectedConfidence = selectedConfidence,
                            onSelectConfidence = { level ->
                                selectedConfidence = level
                                if (selectedOptionIndex != null) {
                                    val elapsed = computeElapsedSeconds()
                                    val attempt = UserAttemptState(selectedOptionIndex!!, level, elapsed)
                                    userAnswers[question.id] = attempt
                                }
                            }
                        )
                    }
                }

                if (isAnswerSubmitted && !hideFeedback) {
                    item(key = "post_feedback") {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    isBlankAnswer -> ext.warningContainer
                                    isUserCorrect -> ext.correctContainer
                                    else -> ext.errorContainer
                                },
                                contentColor = when {
                                    isBlankAnswer -> ext.onWarningContainer
                                    isUserCorrect -> ext.onCorrectContainer
                                    else -> ext.onErrorContainer
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = when {
                                        isBlankAnswer -> "Questão em Branco · Veja o gabarito comentado abaixo"
                                        isUserCorrect -> "Resposta Correta! (+Pontos SRS)"
                                        else -> "Resposta Incorreta · Adicionada ao Caderno de Erros"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                if (isHighConfidenceError) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = ext.error,
                                        contentColor = ext.onError
                                    ) {
                                        Text(
                                            text = "Alerta de Ponto Cego: Você errou com alta confiança. Prioridade máxima na próxima revisão!",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }

                                val expText = question.explanation
                                    ?: "Toque em 'Análise das Alternativas' abaixo para ver o comentário pedagógico detalhado."
                                Text(
                                    text = remember(expText) { MarkdownParser.parse(expText) },
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Aprofundar com Cogniva AI:",
                                    style = MaterialTheme.typography.labelMedium
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    AssistChip(
                                        onClick = { onRequestAiTutor(question.id, "explanation", "Gabarito Comentado Completo", null) },
                                        label = { Text("Análise das Alternativas") }
                                    )
                                    AssistChip(
                                        onClick = { onRequestAiTutor(question.id, "mnemonic", "Mnemônico de Fixação", null) },
                                        label = { Text("Criar Mnemônico") }
                                    )
                                    AssistChip(
                                        onClick = { onRequestAiTutor(question.id, "extra-concepts", "Conceitos-Chave & Exemplos", null) },
                                        label = { Text("Conceitos-Chave") }
                                    )
                                    AssistChip(
                                        onClick = { onRequestAiTutor(question.id, "plausibility", "Plausibilidade dos Distratores", null) },
                                        label = { Text("Plausibilidade") }
                                    )
                                    AssistChip(
                                        onClick = { onRequestAiTutor(question.id, "child-friendly-post", "Explicação Infantil (ELI10)", null) },
                                        label = { Text("Explicação Simples") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showReportSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = null,
                        tint = ext.error
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
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onReportProblem(question.id, category)
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

    if (aiTutorMarkdown != null) {
        ModalBottomSheet(
            onDismissRequest = onDismissAiSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
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
                    text = remember(aiTutorMarkdown) { MarkdownParser.parse(aiTutorMarkdown) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onDismissAiSheet,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
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
    onFinishAndExit: () -> Unit,
    snackbarState: androidx.compose.material3.SnackbarHostState? = null
) {
    val hasCutoff=historicalCutoffPct>0
    val isApproved = hasCutoff && netScorePct >= historicalCutoffPct
    val ext = LocalExtendedColors.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { snackbarState?.let { androidx.compose.material3.SnackbarHost(it) } },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onReviewQuestions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Revisar Gabarito do Simulado", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(
                        onClick = onFinishAndExit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Concluir e Voltar ao Início", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isApproved) ext.correctContainer else ext.warningContainer,
                    contentColor = if (isApproved) ext.onCorrectContainer else ext.onWarningContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Aproveitamento Líquido",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = String.format("%.1f%%", netScorePct),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isApproved) ext.correct else ext.warning
                    )
                    Text(
                        text = if (isCebraspeRuleApplied) {
                            "Desconto por erro aplicado conforme a regra cadastrada, respeitando os pesos das disciplinas."
                        } else {
                            "Treino sem desconto por erro: $correctCount acertos de $totalQuestions questões, com os pesos cadastrados."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isApproved) ext.correct else ext.warning
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (!hasCutoff) {
                                "Sem nota de corte validada. Este resultado é uma referência de treino."
                            } else if (isApproved) {
                                "Acima da referência cadastrada: Corte histórico de referência: ${historicalCutoffPct}%"
                            } else {
                                val diff = historicalCutoffPct - netScorePct
                                "Abaixo da Nota de Corte de Referência (${historicalCutoffPct}%). Diferença de ${String.format("%.1f", diff)}%."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    title = "Acertos",
                    value = "$correctCount",
                    textColor = ext.correct,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Erros",
                    value = "$wrongCount",
                    textColor = ext.error,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Em Branco",
                    value = "$blankCount",
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Caderno de Erros & Fila SRS Atualizados",
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
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
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
                            color = if (pct >= 70.0) ext.correct else ext.warning
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
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
                color = textColor
            )
        }
    }
}
