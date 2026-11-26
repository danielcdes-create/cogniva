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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import br.com.concursoprep.app.domain.model.SessionSnapshot
import br.com.concursoprep.app.domain.model.SessionAnswer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import br.com.concursoprep.app.R
import br.com.concursoprep.app.domain.exam.BehavioralEvidenceDto
import br.com.concursoprep.app.domain.exam.CalibrationCellDto
import br.com.concursoprep.app.domain.exam.CalibrationEngine
import br.com.concursoprep.app.domain.exam.CognitiveStrategy
import br.com.concursoprep.app.domain.exam.DeclaredConfidence
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.domain.exam.ExamMomentScenarioDto
import br.com.concursoprep.app.domain.exam.MetacognitiveEngine
import br.com.concursoprep.app.domain.exam.MetacognitiveMatrixInputs
import br.com.concursoprep.app.domain.exam.StrategyAction
import br.com.concursoprep.app.domain.exam.StrategyEngine
import br.com.concursoprep.app.domain.exam.StrategyPosture
import br.com.concursoprep.app.domain.exam.UncertaintyDecisionInputs
import br.com.concursoprep.app.domain.model.ContextualLookupUiState
import br.com.concursoprep.app.domain.model.CtvEvaluateResponseDto
import br.com.concursoprep.app.domain.model.CtvSubmitResponseDto
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuestionType
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.VALID_PROBLEM_CATEGORIES
import br.com.concursoprep.app.domain.model.isPostAnswerTextRedundant
import br.com.concursoprep.app.domain.model.normalizeEvidenceOptionId
import br.com.concursoprep.app.ui.components.ConfidenceSelectorGrid
import br.com.concursoprep.app.ui.components.ContextualLookupBottomSheet
import br.com.concursoprep.app.ui.components.LayerSelectionTextToolbar
import br.com.concursoprep.app.ui.components.MarkdownParser
import br.com.concursoprep.app.ui.components.OptionCard
import br.com.concursoprep.app.ui.components.QuestionActionChips
import br.com.concursoprep.app.ui.components.extractSuggestedLookupTerms
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.LocalExtendedColors
import kotlinx.coroutines.delay

/**
 * Aba "Praticar": Hub de início rápido para qualquer modalidade herdada do Bot ou nova do App
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeHubScreen(
    onLaunchSession: (QuizMode, Int) -> Unit,
    onOpenMissions: (() -> Unit)? = null
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

            if (onOpenMissions != null) {
                PracticeModeCard(
                    title = "Cogniva Missões · Episódios Audiovisuais Interativos",
                    description = "Vivencie situações reais de carreira (piloto: Banco Central · O Choque dos Juros) com modelos nativos e vínculo ao edital.",
                    onClick = onOpenMissions
                )
            }

            PracticeModeCard(
                title = "Protocolo de Entrada de 60s (1 Questão)",
                description = "Compromisso reversível: uma questão humana curta de assunto familiar com saída livre imediata.",
                onClick = { onLaunchSession(QuizMode.ENTRY_60S, 1) }
            )
            PracticeModeCard(
                title = "Simulador de Decisão sob Incerteza (8–12 min)",
                description = "Questões reais em cenários de prova (35m, 165m, 275m, 285m) maximizando P(atingir meta) sob VE = 2p − 1.",
                onClick = { onLaunchSession(QuizMode.STRATEGY_BATTERY, selectedQuantity.coerceAtMost(12)) }
            )
            PracticeModeCard(
                title = "Avaliação de Transferência Cross-Banca",
                description = "Conjunto separado de questões humanas inéditas de outras bancas para validar transferência real de conceito.",
                onClick = { onLaunchSession(QuizMode.TRANSFER_PROBE, selectedQuantity) }
            )
            PracticeModeCard(
                title = "Treinamento Adaptativo (Rota 40/40/20)",
                description = "Intercala revisões próximas do esquecimento, lacunas diagnósticas e tópicos novos de alto impacto no edital.",
                onClick = { onLaunchSession(QuizMode.ADAPTIVE_LOOP, selectedQuantity) }
            )
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
    onSubmitSimuladoBatch: (suspend (Map<String, UserAttemptState>) -> Boolean)? = null,
    snackbarState: androidx.compose.material3.SnackbarHostState? = null,
    requireExplicitConfidence: Boolean = false,
    allowBlankAnswer: Boolean = false,
    isSmartEntrySession: Boolean = false,
    onContinueAdaptiveRoute: () -> Unit = {},
    onResolveAnotherEntry: () -> Unit = onContinueAdaptiveRoute,
    isStrategySimulator: Boolean = false,
    examScenarios: List<ExamMomentScenarioDto> = emptyList(),
    calibrationCells: List<CalibrationCellDto> = emptyList(),
    estimatedLambda: Double? = null,
    contextualLookupState: ContextualLookupUiState? = null,
    onOpenContextualLookup: ((QuestionDto, String) -> Unit)? = null,
    onRequestContextualLookupAi: (questionId: String, selectedText: String, isAnswerSubmitted: Boolean) -> Unit = { _, _, _ -> },
    onDismissContextualLookup: () -> Unit = {},
    onSubmitExtendedAnswer: (suspend (question: QuestionDto, chosenIndex: Int, confidenceLevel: Int, timeSpentSeconds: Int, isBlank: Boolean, confidenceExplicit: Boolean) -> Boolean)? = null,
    onSubmitMetacognitiveAnswer: (suspend (
        question: QuestionDto,
        chosenIndex: Int,
        confidenceLevel: Int,
        timeSpentSeconds: Int,
        isBlank: Boolean,
        confidenceExplicit: Boolean,
        declaredConfidence: DeclaredConfidence?,
        cognitiveStrategy: CognitiveStrategy?,
        strategySkipped: Boolean,
        activeSeconds: Int,
        answerChangeCount: Int,
        initialChosenIndex: Int?,
        consultedResources: List<String>,
        focusLossCount: Int
    ) -> Boolean)? = null,
    onEvaluateCtv: (suspend (
        question: QuestionDto,
        isCorrect: Boolean,
        isBlank: Boolean,
        declaredConfidence: DeclaredConfidence?,
        cognitiveStrategy: CognitiveStrategy?,
        confidenceLevel: Int
    ) -> CtvEvaluateResponseDto)? = null,
    onSubmitCtvAttempt: (suspend (
        sourceQuestion: QuestionDto,
        complementaryQuestion: QuestionDto,
        chosenIndex: Int,
        declaredConfidence: DeclaredConfidence?,
        cognitiveStrategy: CognitiveStrategy?,
        activeSeconds: Int,
        verificationTiming: String,
        wasPreviouslyExposed: Boolean,
        parentWasCorrect: Boolean
    ) -> CtvSubmitResponseDto)? = null,
    onRecordCtvDisplayed: (evaluation: CtvEvaluateResponseDto, sourceQuestionId: String) -> Unit = { _, _ -> },
    onDismissCtvOffer: (evaluation: CtvEvaluateResponseDto, sourceQuestionId: String) -> Unit = { _, _ -> },
    onSubmitCtvFullAttempt: (suspend (
        sourceQuestion: QuestionDto,
        evaluation: CtvEvaluateResponseDto,
        chosenIndex: Int,
        declaredConfidence: DeclaredConfidence?,
        cognitiveStrategy: CognitiveStrategy?,
        activeSeconds: Int,
        displayedAt: String?,
        consultedResources: List<String>,
        focusLossCount: Int,
        parentWasCorrect: Boolean
    ) -> CtvSubmitResponseDto)? = null
) {
    var currentIndex by rememberSaveable(snapshot?.sessionId) { mutableIntStateOf(snapshot?.currentIndex ?: initialIndex) }
    val userAnswers = remember(snapshot?.sessionId) { mutableStateMapOf<String, UserAttemptState>().apply { putAll(snapshot?.answers.orEmpty()) } }
    val actionScope=rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var isSimuladoFinished by rememberSaveable(snapshot?.sessionId) { mutableStateOf(snapshot?.finished ?: false) }
    var isSimuladoReviewMode by rememberSaveable(snapshot?.sessionId) { mutableStateOf(snapshot?.reviewing ?: false) }
    var showReportSheet by rememberSaveable { mutableStateOf(false) }
    var questionStartEpochMs by rememberSaveable(snapshot?.sessionId,currentIndex) { mutableStateOf(if(snapshot?.currentIndex==currentIndex) snapshot.questionStartedAtEpochMs else System.currentTimeMillis()) }

    val persistSessionNow = { idx: Int, finishedFlag: Boolean, reviewFlag: Boolean ->
        snapshot?.let {
            onUpdateSession(
                it.copy(
                    currentIndex = idx,
                    answers = userAnswers.toMap(),
                    finished = finishedFlag,
                    reviewing = reviewFlag,
                    questionStartedAtEpochMs = questionStartEpochMs
                )
            )
        }
    }

    val handleBackNavigation = {
        if (isSimuladoReviewMode) {
            isSimuladoReviewMode = false
        } else {
            onExitSession()
        }
    }

    BackHandler(enabled = !showReportSheet && aiTutorMarkdown == null && contextualLookupState == null) {
        handleBackNavigation()
    }

    val listState = rememberLazyListState()
    var lastScrolledIndex by remember { mutableIntStateOf(currentIndex) }
    LaunchedEffect(currentIndex) {
        onDismissAiSheet()
        onDismissContextualLookup()
        if (currentIndex != lastScrolledIndex) {
            lastScrolledIndex = currentIndex
            listState.scrollToItem(0)
        }
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
        persistSessionNow(currentIndex, isSimuladoFinished, isSimuladoReviewMode)
    }
    LaunchedEffect(isSimuladoFinished) {
        if (isSimuladoMode && isSimuladoFinished) {
            val pendingSnapshot = userAnswers.toMap()
            if (pendingSnapshot.values.any { !it.submitted }) {
                saving = true
                try {
                    if (onSubmitSimuladoBatch != null) {
                        if (onSubmitSimuladoBatch(pendingSnapshot)) {
                            pendingSnapshot.forEach { (id, answer) ->
                                userAnswers[id] = answer.copy(submitted = true)
                            }
                        }
                    } else {
                        for ((id, answer) in pendingSnapshot) {
                            if (!answer.submitted) {
                                val q = questions.firstOrNull { it.id == id } ?: continue
                                val ok = if (onSubmitExtendedAnswer != null) {
                                    onSubmitExtendedAnswer(q, answer.chosenIndex, answer.confidenceLevel, answer.timeSpentSeconds, answer.isBlank, answer.confidenceExplicit)
                                } else if (!answer.isBlank) {
                                    onSubmitAnswer(q, answer.chosenIndex, answer.confidenceLevel, answer.timeSpentSeconds)
                                } else {
                                    true
                                }
                                if (ok) {
                                    userAnswers[id] = answer.copy(submitted = true)
                                }
                            }
                        }
                    }
                } finally {
                    saving = false
                }
            }
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

            if (userAttempt == null || userAttempt.isBlank) {
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
            val correct=group.count { q -> val ua=userAnswers[q.id]; ua!=null && !ua.isBlank && ua.chosenIndex in q.correctAnswerIndices }
            val wrong=group.count { q -> val ua=userAnswers[q.id]; ua!=null && !ua.isBlank && ua.chosenIndex !in q.correctAnswerIndices }
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
        mutableStateOf(
            if (currentAttempt?.isBlank == true) null
            else (currentAttempt?.chosenIndex?.takeIf { it >= 0 } ?: initialSelectedOptionIndex)
        )
    }
    var isMarkedBlank by remember(question.id, isSimuladoReviewMode) {
        mutableStateOf(currentAttempt?.isBlank == true)
    }
    var selectedConfidence by remember(question.id, isSimuladoReviewMode) {
        mutableIntStateOf(currentAttempt?.confidenceLevel ?: 2)
    }
    var selectedDeclaredConfidence by remember(question.id, isSimuladoReviewMode) {
        mutableStateOf(
            currentAttempt?.declaredConfidence
                ?: if (currentAttempt?.submitted == true || currentAttempt?.confidenceExplicit == true) {
                    DeclaredConfidence.fromLegacyBand(currentAttempt.confidenceLevel, true)
                } else {
                    null
                }
        )
    }
    var selectedStrategy by remember(question.id, isSimuladoReviewMode) {
        mutableStateOf(currentAttempt?.cognitiveStrategy)
    }
    var strategySkipped by remember(question.id, isSimuladoReviewMode) {
        mutableStateOf(currentAttempt?.strategySkipped == true)
    }
    var confidenceExplicitlySelected by remember(question.id, isSimuladoReviewMode) {
        mutableStateOf(currentAttempt?.confidenceExplicit == true || currentAttempt?.submitted == true)
    }
    val historicalSelectedEvidenceId = currentAttempt?.selectedEvidenceId
    var isAnswerSubmitted by remember(question.id, isSimuladoReviewMode) {
        mutableStateOf(currentAttempt?.submitted == true || isSimuladoReviewMode)
    }
    var hintClickCount by remember(question.id) { mutableIntStateOf(0) }
    var initialChosenIndex by remember(question.id) {
        mutableStateOf(currentAttempt?.initialChosenIndex ?: initialSelectedOptionIndex)
    }
    var answerChangeCount by remember(question.id) {
        mutableIntStateOf(currentAttempt?.answerChangeCount ?: 0)
    }
    var consultedResources by remember(question.id) {
        mutableStateOf(currentAttempt?.consultedResources?.toSet() ?: emptySet())
    }
    var focusLossCount by remember(question.id) {
        mutableIntStateOf(currentAttempt?.focusLossCount ?: 0)
    }
    var activeForegroundSeconds by remember(question.id) {
        mutableIntStateOf(currentAttempt?.activeSeconds ?: 1)
    }
    var isAppInForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, question.id, isAnswerSubmitted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (isAppInForeground && !isAnswerSubmitted) {
                        focusLossCount += 1
                    }
                    isAppInForeground = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    isAppInForeground = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(question.id, isAnswerSubmitted, isAppInForeground) {
        while (!isAnswerSubmitted && isAppInForeground) {
            delay(1000L)
            activeForegroundSeconds = (activeForegroundSeconds + 1).coerceAtMost(86400)
        }
    }

    // Estado da Camada 3: Verificação Adaptativa de Domínio Conceitual (CTV) antes de revelar o gabarito original
    var activeCtvEvaluation by remember(question.id) { mutableStateOf<CtvEvaluateResponseDto?>(null) }
    var ctvChosenIndex by remember(question.id) { mutableStateOf<Int?>(null) }
    var ctvDeclaredConfidence by remember(question.id) { mutableStateOf<DeclaredConfidence?>(null) }
    var ctvActiveSeconds by remember(question.id) { mutableIntStateOf(1) }
    var ctvDisplayedAtIso by remember(question.id) { mutableStateOf<String?>(null) }
    var ctvFocusLossCount by remember(question.id) { mutableIntStateOf(0) }
    var ctvSubmitting by remember(question.id) { mutableStateOf(false) }
    var ctvResult by remember(question.id) {
        mutableStateOf(
            if (currentAttempt?.ctvCompleted == true && currentAttempt.ctvDiagnosticTitle != null) {
                CtvSubmitResponseDto(
                    ctvAttemptId = "",
                    parentAttemptId = "",
                    complementaryQuestionId = "",
                    isCorrect = true,
                    correctIndex = 0,
                    consolidatedDiagnosticTitlePt = currentAttempt.ctvDiagnosticTitle,
                    consolidatedDiagnosticMessagePt = currentAttempt.ctvDiagnosticMessage.orEmpty()
                )
            } else {
                null
            }
        )
    }
    val ctvPendingStage = activeCtvEvaluation?.triggered == true &&
        activeCtvEvaluation?.complementaryQuestion != null &&
        ctvResult == null
    DisposableEffect(lifecycleOwner, ctvPendingStage) {
        val ctvObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && ctvPendingStage) {
                ctvFocusLossCount += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(ctvObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(ctvObserver) }
    }
    LaunchedEffect(ctvPendingStage, activeCtvEvaluation?.offerId, activeCtvEvaluation?.complementaryQuestion?.id) {
        val eval = activeCtvEvaluation
        if (ctvPendingStage && eval != null) {
            if (ctvDisplayedAtIso == null) {
                ctvDisplayedAtIso = java.time.Instant.now().toString()
            }
            onRecordCtvDisplayed(eval, question.id)
        }
    }
    LaunchedEffect(ctvPendingStage, isAppInForeground) {
        while (ctvPendingStage && isAppInForeground) {
            delay(1000L)
            ctvActiveSeconds = (ctvActiveSeconds + 1).coerceAtMost(3600)
        }
    }

    val computeElapsedSeconds = {
        ((System.currentTimeMillis() - questionStartEpochMs) / 1000L).toInt().coerceIn(1, 3600)
    }

    val recordOptionChoice = { idx: Int ->
        if (initialChosenIndex == null) {
            initialChosenIndex = idx
        } else if (selectedOptionIndex != null && selectedOptionIndex != idx) {
            answerChangeCount += 1
        }
        selectedOptionIndex = idx
        isMarkedBlank = false
    }

    val correctIndex = question.correctAnswerIndices.singleOrNull() ?: -1
    val isBlankAnswer = isMarkedBlank || (isSimuladoReviewMode && selectedOptionIndex == null)
    val isUserCorrect = !isBlankAnswer && selectedOptionIndex != null && selectedOptionIndex == correctIndex
    val hideFeedback = isSimuladoMode && !isSimuladoReviewMode
    val hideOriginalGabarito = hideFeedback || ctvPendingStage
    val ext = LocalExtendedColors.current
    val headerMeta = remember(
        question.id,
        question.disciplina,
        question.source,
        question.examYear,
        question.canonicalSubjectId,
        question.canonicalTopicId,
        question.examComponentId,
        question.isBorrowedAnalogous,
        snapshot?.exam?.examId,
        snapshot?.exam?.revision
    ) {
        ExamCatalog.resolveQuestionHeaderMeta(question, snapshot?.exam)
    }
    val editorial = remember(question.id, question.questionRevision) { question.effectiveEditorialCard() }
    val legacyChallenge = remember(question.id, question.questionRevision, historicalSelectedEvidenceId) {
        if (historicalSelectedEvidenceId != null) question.effectiveEvidenceChallenge() else null
    }

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
                                text = "Questão ${currentIndex + 1} de ${questions.size}",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = headerMeta.disciplineAndSourceLine,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
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
                                        text = String.format(Locale.forLanguageTag("pt-BR"), "%02d:%02d", minutes, seconds),
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

                    headerMeta.topicBreadcrumb?.let { topicPath ->
                        Text(
                            text = topicPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        )
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
                                        val nextIdx = currentIndex - 1
                                        currentIndex = nextIdx
                                        persistSessionNow(nextIdx, isSimuladoFinished, isSimuladoReviewMode)
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
                                        val nextIdx = currentIndex + 1
                                        currentIndex = nextIdx
                                        persistSessionNow(nextIdx, isSimuladoFinished, isSimuladoReviewMode)
                                    } else {
                                        isSimuladoFinished = true
                                        persistSessionNow(currentIndex, true, isSimuladoReviewMode)
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
                                    val actSecs = activeForegroundSeconds.coerceIn(1, elapsed.coerceAtLeast(1))
                                    val explicitFlag = !requireExplicitConfidence || confidenceExplicitlySelected
                                    val resolvedDecl = selectedDeclaredConfidence
                                        ?: if (explicitFlag) DeclaredConfidence.fromLegacyBand(selectedConfidence, true) else null
                                    val skipFlag = strategySkipped || selectedStrategy == null
                                    actionScope.launch {
                                        saving=true
                                        try {
                                            val ok = when {
                                                onSubmitMetacognitiveAnswer != null -> onSubmitMetacognitiveAnswer(
                                                    question,
                                                    chosen,
                                                    selectedConfidence,
                                                    elapsed,
                                                    false,
                                                    explicitFlag,
                                                    resolvedDecl,
                                                    selectedStrategy,
                                                    skipFlag,
                                                    actSecs,
                                                    answerChangeCount,
                                                    initialChosenIndex,
                                                    consultedResources.toList(),
                                                    focusLossCount
                                                )
                                                onSubmitExtendedAnswer != null -> onSubmitExtendedAnswer(
                                                    question,
                                                    chosen,
                                                    selectedConfidence,
                                                    elapsed,
                                                    false,
                                                    explicitFlag
                                                )
                                                else -> onSubmitAnswer(question, chosen, selectedConfidence, elapsed)
                                            }
                                            if (ok) {
                                                userAnswers[question.id]=UserAttemptState(
                                                    chosenIndex = chosen,
                                                    confidenceLevel = selectedConfidence,
                                                    timeSpentSeconds = elapsed,
                                                    submitted = true,
                                                    isBlank = false,
                                                    confidenceExplicit = explicitFlag,
                                                    declaredConfidence = resolvedDecl,
                                                    cognitiveStrategy = selectedStrategy,
                                                    strategySkipped = skipFlag,
                                                    activeSeconds = actSecs,
                                                    answerChangeCount = answerChangeCount,
                                                    initialChosenIndex = initialChosenIndex,
                                                    consultedResources = consultedResources.toList(),
                                                    focusLossCount = focusLossCount
                                                )
                                                if (onEvaluateCtv != null && !isSimuladoMode && !isSmartEntrySession) {
                                                    val eval = runCatching {
                                                        onEvaluateCtv(
                                                            question,
                                                            chosen == correctIndex,
                                                            false,
                                                            resolvedDecl,
                                                            selectedStrategy,
                                                            selectedConfidence
                                                        )
                                                    }.getOrNull()
                                                    if (eval?.triggered == true && eval.complementaryQuestion != null) {
                                                        activeCtvEvaluation = eval
                                                        ctvChosenIndex = null
                                                        ctvDeclaredConfidence = null
                                                        ctvActiveSeconds = 1
                                                    }
                                                }
                                                isAnswerSubmitted=true
                                            }
                                        }finally{saving=false}
                                    }
                                },
                                enabled = selectedOptionIndex != null && (!requireExplicitConfidence || confidenceExplicitlySelected) && !saving && correctIndex>=0,
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
                    } else if (ctvPendingStage) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    activeCtvEvaluation?.let { onDismissCtvOffer(it, question.id) }
                                    activeCtvEvaluation = null
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Ver gabarito agora",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val compQ = activeCtvEvaluation?.complementaryQuestion
                            Button(
                                onClick = {
                                    val cq = compQ ?: return@Button
                                    val ctvEvalSnapshot = activeCtvEvaluation ?: return@Button
                                    val cIdx = ctvChosenIndex ?: return@Button
                                    actionScope.launch {
                                        ctvSubmitting = true
                                        try {
                                            val res = if (onSubmitCtvFullAttempt != null) {
                                                onSubmitCtvFullAttempt(
                                                    question,
                                                    ctvEvalSnapshot,
                                                    cIdx,
                                                    ctvDeclaredConfidence,
                                                    null,
                                                    ctvActiveSeconds,
                                                    ctvDisplayedAtIso,
                                                    consultedResources.toList(),
                                                    ctvFocusLossCount,
                                                    isUserCorrect
                                                )
                                            } else {
                                                onSubmitCtvAttempt?.invoke(
                                                    question,
                                                    cq,
                                                    cIdx,
                                                    ctvDeclaredConfidence,
                                                    null,
                                                    ctvActiveSeconds,
                                                    ctvEvalSnapshot.verificationTiming,
                                                    ctvEvalSnapshot.wasPreviouslyExposed,
                                                    isUserCorrect
                                                )
                                            }
                                            if (res != null) {
                                                ctvResult = res
                                                userAnswers[question.id]?.let { prev ->
                                                    userAnswers[question.id] = prev.copy(
                                                        ctvCompleted = true,
                                                        ctvDiagnosticTitle = res.consolidatedDiagnosticTitlePt,
                                                        ctvDiagnosticMessage = res.consolidatedDiagnosticMessagePt
                                                    )
                                                }
                                            } else {
                                                activeCtvEvaluation = null
                                            }
                                        } finally {
                                            ctvSubmitting = false
                                        }
                                    }
                                },
                                enabled = ctvChosenIndex != null && !ctvSubmitting,
                                modifier = Modifier
                                    .weight(1.25f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Confirmar verificação",
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
                state = listState,
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

                if (isStrategySimulator && !hideFeedback) {
                    item(key = "strategy_simulator_scenario") {
                        val activePenalty = snapshot?.exam?.wrongAnswerPenalty ?: 1.0
                        val activeCutoff = snapshot?.exam?.historicalCutoffScore ?: 65.0
                        val fallbackScenarios = remember(activePenalty, activeCutoff, estimatedLambda) {
                            StrategyEngine.buildExamMomentScenarios(
                                mediumP = 0.62,
                                mediumSd = 0.08,
                                penalty = activePenalty,
                                cutoffPct = activeCutoff,
                                lambdaI = estimatedLambda
                            )
                        }
                        val activeList = examScenarios.ifEmpty { fallbackScenarios }
                        val scenario = activeList[currentIndex % activeList.size]
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Simulador de Decisão · ${scenario.label}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Estado simulado: ${scenario.currentNetScore} pts · Faltam ${scenario.remainingItems} itens · Meta ${scenario.targetScore.toInt()} pts (mínimo ${scenario.minimumScore.toInt()} pts)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = if (scenario.calibratedLongitudinal) {
                                        "Curva 300 min calibrada longitudinalmente (λᵢ com controle de dificuldade)."
                                    } else {
                                        "Cenário matemático de decisão (calibração fisiológica real de fadiga exige ≥ 3 simulados longos)."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                )
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
                    val clipboardManager = LocalClipboardManager.current
                    val layerToolbar = remember(question.id, onOpenContextualLookup) {
                        if (onOpenContextualLookup != null) {
                            LayerSelectionTextToolbar(clipboardManager) { selected ->
                                onOpenContextualLookup(question, selected)
                            }
                        } else {
                            null
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (layerToolbar != null) {
                                CompositionLocalProvider(LocalTextToolbar provides layerToolbar) {
                                    SelectionContainer {
                                        Text(
                                            text = question.question,
                                            style = MaterialTheme.typography.titleLarge,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                                if (layerToolbar.isVisible) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = { layerToolbar.triggerLookupFromSelection() },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 40.dp),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text(
                                                    text = "Consultar trecho em Camada / Google",
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = { layerToolbar.triggerCopyOnly() },
                                                modifier = Modifier.heightIn(min = 40.dp),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Copiar")
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = question.question,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }

                if (!isAnswerSubmitted && !hideFeedback) {
                    item(key = "pre_chips") {
                        QuestionActionChips(
                            hintCount = hintClickCount,
                            onHint = {
                                val hIdx = hintClickCount
                                hintClickCount += 1
                                consultedResources = consultedResources + "contextual-hint"
                                onRequestAiTutor(
                                    question.id,
                                    "contextual-hint",
                                    "Dica Contextual Progressiva #${hIdx + 1}",
                                    hIdx
                                )
                            },
                            onSincereDoctor = {
                                consultedResources = consultedResources + "dr-sincero"
                                onRequestAiTutor(question.id, "dr-sincero", "Análise do Dr. Sincero", null)
                            },
                            onSimplify = {
                                consultedResources = consultedResources + "child-friendly-pre"
                                onRequestAiTutor(question.id, "child-friendly-pre", "Simplificar Pergunta", null)
                            },
                            onLookupLayer = if (onOpenContextualLookup != null) {
                                {
                                    consultedResources = consultedResources + "contextual-lookup"
                                    val seed = extractSuggestedLookupTerms(question).firstOrNull().orEmpty()
                                    onOpenContextualLookup(question, seed)
                                }
                            } else {
                                null
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            question.answers.mapIndexed { index,label->index to label }.forEach { (idx, label) ->
                                val isSelected = selectedOptionIndex == idx
                                val showColor = isAnswerSubmitted && !hideOriginalGabarito
                                Button(
                                    onClick = {
                                        if (!isAnswerSubmitted || hideFeedback) {
                                            recordOptionChoice(idx)
                                            val elapsed = computeElapsedSeconds()
                                            val attempt = UserAttemptState(
                                                chosenIndex = idx,
                                                confidenceLevel = selectedConfidence,
                                                timeSpentSeconds = elapsed,
                                                isBlank = false,
                                                confidenceExplicit = confidenceExplicitlySelected,
                                                declaredConfidence = selectedDeclaredConfidence,
                                                cognitiveStrategy = selectedStrategy,
                                                strategySkipped = strategySkipped,
                                                activeSeconds = activeForegroundSeconds,
                                                answerChangeCount = answerChangeCount,
                                                initialChosenIndex = initialChosenIndex,
                                                consultedResources = consultedResources.toList(),
                                                focusLossCount = focusLossCount
                                            )
                                            userAnswers[question.id] = attempt
                                            persistSessionNow(currentIndex, isSimuladoFinished, isSimuladoReviewMode)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 56.dp)
                                        .semantics {
                                            role = Role.RadioButton
                                            selected = isSelected
                                        },
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
                            hideFeedback = hideOriginalGabarito,
                            onClick = {
                                if (!isAnswerSubmitted || hideFeedback) {
                                    recordOptionChoice(idx)
                                    val elapsed = computeElapsedSeconds()
                                    val attempt = UserAttemptState(
                                        chosenIndex = idx,
                                        confidenceLevel = selectedConfidence,
                                        timeSpentSeconds = elapsed,
                                        isBlank = false,
                                        confidenceExplicit = confidenceExplicitlySelected,
                                        declaredConfidence = selectedDeclaredConfidence,
                                        cognitiveStrategy = selectedStrategy,
                                        strategySkipped = strategySkipped,
                                        activeSeconds = activeForegroundSeconds,
                                        answerChangeCount = answerChangeCount,
                                        initialChosenIndex = initialChosenIndex,
                                        consultedResources = consultedResources.toList(),
                                        focusLossCount = focusLossCount
                                    )
                                    userAnswers[question.id] = attempt
                                    persistSessionNow(currentIndex, isSimuladoFinished, isSimuladoReviewMode)
                                }
                            }
                        )
                    }
                }

                if (allowBlankAnswer && (!isAnswerSubmitted || hideFeedback)) {
                    item(key = "strategic_omit_button") {
                        OutlinedButton(
                            onClick = {
                                val elapsed = computeElapsedSeconds()
                                if (hideFeedback) {
                                    selectedOptionIndex = null
                                    isMarkedBlank = true
                                    userAnswers[question.id] = UserAttemptState(
                                        chosenIndex = -1,
                                        confidenceLevel = 0,
                                        timeSpentSeconds = elapsed,
                                        isBlank = true,
                                        confidenceExplicit = true
                                    )
                                    persistSessionNow(currentIndex, isSimuladoFinished, isSimuladoReviewMode)
                                } else {
                                    actionScope.launch {
                                        saving = true
                                        try {
                                            val ok = onSubmitExtendedAnswer?.invoke(question, -1, 0, elapsed, true, true) ?: true
                                            if (ok) {
                                                selectedOptionIndex = null
                                                isMarkedBlank = true
                                                isAnswerSubmitted = true
                                                userAnswers[question.id] = UserAttemptState(
                                                    chosenIndex = -1,
                                                    confidenceLevel = 0,
                                                    timeSpentSeconds = elapsed,
                                                    submitted = true,
                                                    isBlank = true,
                                                    confidenceExplicit = true
                                                )
                                            }
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }
                            },
                            border = BorderStroke(
                                width = if (isMarkedBlank) 2.dp else 1.dp,
                                color = if (isMarkedBlank) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (isMarkedBlank) {
                                    "Questão marcada para deixar em branco (0 pt)"
                                } else {
                                    "Deixar em Branco (Omitir · 0 pt)"
                                },
                                fontWeight = if (isMarkedBlank) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                if (selectedOptionIndex != null && (!isAnswerSubmitted || hideFeedback)) {
                    item(key = "confidence_grid") {
                        ConfidenceSelectorGrid(
                            selectedConfidence = selectedConfidence,
                            hasExplicitSelection = !requireExplicitConfidence || confidenceExplicitlySelected,
                            selectedDeclaredConfidence = selectedDeclaredConfidence,
                            onSelectDeclaredConfidence = { decl ->
                                selectedDeclaredConfidence = decl
                                selectedConfidence = decl.toLegacyBand()
                                confidenceExplicitlySelected = true
                                if (selectedOptionIndex != null) {
                                    val elapsed = computeElapsedSeconds()
                                    userAnswers[question.id] = UserAttemptState(
                                        chosenIndex = selectedOptionIndex!!,
                                        confidenceLevel = decl.toLegacyBand(),
                                        timeSpentSeconds = elapsed,
                                        isBlank = false,
                                        confidenceExplicit = true,
                                        declaredConfidence = decl,
                                        cognitiveStrategy = selectedStrategy,
                                        strategySkipped = strategySkipped,
                                        activeSeconds = activeForegroundSeconds,
                                        answerChangeCount = answerChangeCount,
                                        initialChosenIndex = initialChosenIndex,
                                        consultedResources = consultedResources.toList(),
                                        focusLossCount = focusLossCount
                                    )
                                    persistSessionNow(currentIndex, isSimuladoFinished, isSimuladoReviewMode)
                                }
                            },
                            showStrategySelector = !hideFeedback,
                            selectedStrategy = selectedStrategy,
                            strategySkipped = strategySkipped,
                            onSelectStrategy = { strat ->
                                selectedStrategy = strat
                                strategySkipped = false
                                // Consolida os dois sinais sem exigir segunda interação redundante quando o aluno
                                // declara escolha totalmente aleatória antes de escolher nível explícito de confiança.
                                if (strat == CognitiveStrategy.RANDOM_GUESS && !confidenceExplicitlySelected) {
                                    selectedDeclaredConfidence = DeclaredConfidence.GUESS
                                    selectedConfidence = DeclaredConfidence.GUESS.toLegacyBand()
                                    confidenceExplicitlySelected = true
                                }
                                if (selectedOptionIndex != null) {
                                    val elapsed = computeElapsedSeconds()
                                    userAnswers[question.id] = UserAttemptState(
                                        chosenIndex = selectedOptionIndex!!,
                                        confidenceLevel = selectedConfidence,
                                        timeSpentSeconds = elapsed,
                                        isBlank = false,
                                        confidenceExplicit = confidenceExplicitlySelected,
                                        declaredConfidence = selectedDeclaredConfidence,
                                        cognitiveStrategy = strat,
                                        strategySkipped = false,
                                        activeSeconds = activeForegroundSeconds,
                                        answerChangeCount = answerChangeCount,
                                        initialChosenIndex = initialChosenIndex,
                                        consultedResources = consultedResources.toList(),
                                        focusLossCount = focusLossCount
                                    )
                                    persistSessionNow(currentIndex, isSimuladoFinished, isSimuladoReviewMode)
                                }
                            },
                            onSkipStrategy = {
                                selectedStrategy = null
                                strategySkipped = true
                                if (selectedOptionIndex != null) {
                                    val elapsed = computeElapsedSeconds()
                                    userAnswers[question.id] = UserAttemptState(
                                        chosenIndex = selectedOptionIndex!!,
                                        confidenceLevel = selectedConfidence,
                                        timeSpentSeconds = elapsed,
                                        isBlank = false,
                                        confidenceExplicit = confidenceExplicitlySelected,
                                        declaredConfidence = selectedDeclaredConfidence,
                                        cognitiveStrategy = null,
                                        strategySkipped = true,
                                        activeSeconds = activeForegroundSeconds,
                                        answerChangeCount = answerChangeCount,
                                        initialChosenIndex = initialChosenIndex,
                                        consultedResources = consultedResources.toList(),
                                        focusLossCount = focusLossCount
                                    )
                                    persistSessionNow(currentIndex, isSimuladoFinished, isSimuladoReviewMode)
                                }
                            },
                            onSelectConfidence = { level ->
                                selectedConfidence = level
                                confidenceExplicitlySelected = true
                            }
                        )
                    }
                }

                // Camada 3: Verificação Adaptativa de Domínio Conceitual (CTV) antes da revelação do gabarito original
                if (ctvPendingStage) {
                    val ctvEval = activeCtvEvaluation!!
                    val compQ = ctvEval.complementaryQuestion!!
                    item(key = "ctv_verification_step") {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ctv_verification_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Verificação Complementar de Conceito",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = ctvEval.studentFramingPt
                                        ?: "Vamos verificar este conceito em outro contexto antes de abrir o gabarito comentado.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = compQ.question,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectableGroup(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    compQ.answers.forEachIndexed { idx, ansText ->
                                        OptionCard(
                                            letter = if (compQ.questionType == QuestionType.TRUE_FALSE) {
                                                if (idx == 0) "C" else "E"
                                            } else {
                                                ('A' + idx).toString()
                                            },
                                            text = ansText,
                                            selected = ctvChosenIndex == idx,
                                            isAnswerSubmitted = false,
                                            isCorrectOption = false,
                                            hideFeedback = true,
                                            onClick = { ctvChosenIndex = idx }
                                        )
                                    }
                                }
                                if (ctvChosenIndex != null) {
                                    ConfidenceSelectorGrid(
                                        selectedConfidence = ctvDeclaredConfidence?.toLegacyBand() ?: 2,
                                        hasExplicitSelection = ctvDeclaredConfidence != null,
                                        selectedDeclaredConfidence = ctvDeclaredConfidence,
                                        onSelectDeclaredConfidence = { ctvDeclaredConfidence = it },
                                        showStrategySelector = false,
                                        onSelectConfidence = {}
                                    )
                                }
                            }
                        }
                    }
                }

                if (isAnswerSubmitted && !hideOriginalGabarito) {
                    item(key = "post_feedback") {
                        val correctOptionLabel = question.formatPostAnswerCorrectLabel(correctIndex)
                        val formattedExplanation = remember(question.id, question.explanation) {
                            question.formatPostAnswerExplanation()
                        }
                        val showTestedRule = formattedExplanation == null || !isPostAnswerTextRedundant(
                            editorial.testedRule,
                            question.explanation,
                            formattedExplanation.body
                        )
                        val distinctException = editorial.exceptionClause?.takeIf { exc ->
                            exc.isNotBlank() && !isPostAnswerTextRedundant(
                                exc,
                                question.explanation,
                                formattedExplanation?.body,
                                if (question.editorialCard == null) question.question else null
                            )
                        }
                        val actionableError = if (!isUserCorrect && !isBlankAnswer) {
                            question.resolveActionableConceptualError(editorial, selectedOptionIndex)
                        } else {
                            null
                        }
                        val resolvedDeclConf = selectedDeclaredConfidence
                            ?: DeclaredConfidence.fromLegacyBand(selectedConfidence, true)
                        val confidenceBadgeText = resolvedDeclConf?.labelPt ?: "Não informada"
                        val hasCustomChForMeta = question.hasCustomEvidenceChallenge()
                        val normSelectedEvForMeta = normalizeEvidenceOptionId(historicalSelectedEvidenceId, hasCustomChForMeta)
                        val rationaleContradicted = isUserCorrect &&
                            legacyChallenge != null &&
                            normSelectedEvForMeta != null &&
                            normSelectedEvForMeta != legacyChallenge.correctEvidenceId
                        var showTechnicalDetails by rememberSaveable(question.id) { mutableStateOf(false) }
                        val metacognitiveAssessment = remember(
                            isBlankAnswer,
                            isUserCorrect,
                            resolvedDeclConf,
                            selectedStrategy,
                            rationaleContradicted,
                            activeForegroundSeconds,
                            answerChangeCount,
                            consultedResources,
                            question.userStats
                        ) {
                            MetacognitiveEngine.buildAssessment(
                                inputs = MetacognitiveMatrixInputs(
                                    isBlank = isBlankAnswer,
                                    isCorrect = isUserCorrect,
                                    declaredConfidence = resolvedDeclConf,
                                    cognitiveStrategy = selectedStrategy,
                                    consistentHistory = (question.userStats?.attempts ?: 0) >= 2 &&
                                        (question.userStats?.accuracyPct ?: 0.0) >= 75.0,
                                    rationaleContradicted = rationaleContradicted
                                ),
                                behavioral = BehavioralEvidenceDto(
                                    activeSeconds = currentAttempt?.activeSeconds ?: activeForegroundSeconds,
                                    totalElapsedSeconds = currentAttempt?.timeSpentSeconds ?: activeForegroundSeconds,
                                    answerChangeCount = currentAttempt?.answerChangeCount ?: answerChangeCount,
                                    initialChosenIndex = currentAttempt?.initialChosenIndex ?: initialChosenIndex,
                                    finalChosenIndex = selectedOptionIndex,
                                    consultedResources = currentAttempt?.consultedResources ?: consultedResources.toList(),
                                    focusLossCount = currentAttempt?.focusLossCount ?: focusLossCount
                                ),
                                hasMultiAttemptEvidence = (question.userStats?.attempts ?: 0) >= 2
                            )
                        }
                        val userChosenSummary = when {
                            isBlankAnswer -> null
                            question.questionType == QuestionType.TRUE_FALSE ->
                                selectedOptionIndex?.let { question.answers.getOrNull(it) }
                            else -> selectedOptionIndex?.let { "Alternativa ${'A' + it}" }
                        }
                        val recordedSeconds = currentAttempt?.activeSeconds ?: currentAttempt?.timeSpentSeconds

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
                                        isBlankAnswer -> "Em Branco (0 pt) · Gabarito: $correctOptionLabel"
                                        isUserCorrect -> "Resposta Correta · Gabarito: $correctOptionLabel"
                                        else -> "Resposta Incorreta · Gabarito: $correctOptionLabel"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                if (userChosenSummary != null) {
                                    val timeSuffix = recordedSeconds?.takeIf { it > 0 }?.let { " · ${it}s ativos" }.orEmpty()
                                    Text(
                                        text = "Sua marcação: $userChosenSummary · Confiança: $confidenceBadgeText$timeSuffix",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                // Camada Primária (Feedback pedagógico conciso) + Camada Secundária expansível (Detalhes técnicos)
                                if (!isBlankAnswer) {
                                    val primaryPedagogicalFeedback = ctvResult?.let { ctvRes ->
                                        val isConceptTransfer = ctvRes.verificationMode.isBlank() ||
                                            ctvRes.verificationMode == "CONCEPTUAL_TRANSFER"
                                        when {
                                            ctvRes.isCorrect && isConceptTransfer ->
                                                "Você conseguiu aplicar o conceito em outro contexto."
                                            !ctvRes.isCorrect && isConceptTransfer ->
                                                "Precisamos reforçar a compreensão deste conceito."
                                            ctvRes.isCorrect ->
                                                "Boa prática complementar neste tópico. A aplicação independente do conceito segue em acompanhamento."
                                            else ->
                                                "Precisamos reforçar a compreensão deste tópico."
                                        }
                                    } ?: metacognitiveAssessment.pedagogicalFeedbackPt.ifBlank {
                                        metacognitiveAssessment.systemInferenceMessage
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = primaryPedagogicalFeedback,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            ctvResult?.let { ctvRes ->
                                                Text(
                                                    text = "Verificação complementar: ${ctvRes.consolidatedDiagnosticTitlePt}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (ctvRes.isCorrect) ext.correct else ext.warning,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Text(
                                                text = if (showTechnicalDetails) {
                                                    "Ocultar detalhes da avaliação"
                                                } else {
                                                    "Ver detalhes da avaliação"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .testTag("metacognitive_details_toggle")
                                                    .clickable { showTechnicalDetails = !showTechnicalDetails }
                                                    .padding(vertical = 2.dp)
                                            )
                                            if (showTechnicalDetails) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                Text(
                                                    text = "Sustentação diagnóstica: ${metacognitiveAssessment.inferenceSupport.labelPt}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = "Estratégia cognitiva: ${selectedStrategy?.labelPt ?: "Etapa pulada / não informada"}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                val effChanges = currentAttempt?.answerChangeCount ?: answerChangeCount
                                                val effResources = currentAttempt?.consultedResources ?: consultedResources.toList()
                                                if (effChanges > 0 || effResources.isNotEmpty()) {
                                                    val detailsParts = buildList {
                                                        if (effChanges > 0) add("$effChanges troca(s) de alternativa")
                                                        if (effResources.isNotEmpty()) add("${effResources.size} recurso(s) consultado(s)")
                                                    }
                                                    Text(
                                                        text = "Interações na resolução: ${detailsParts.joinToString(" · ")}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                question.userStats?.takeIf { it.attempts > 0 }?.let { stats ->
                                                    Text(
                                                        text = "Histórico prévio neste item: ${stats.attempts} tentativa(s) (${stats.accuracyPct.toInt()}% de acerto)",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(
                                                    text = metacognitiveAssessment.systemInferenceMessage,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                ctvResult?.let { ctvRes ->
                                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                    Text(
                                                        text = ctvRes.consolidatedDiagnosticMessagePt,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    ctvRes.explanation?.takeIf { it.isNotBlank() }?.let { compExp ->
                                                        Text(
                                                            text = "Gabarito da verificação: $compExp",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Fundamentação consolidada em superfície única (sem repetir explicação como "Regra cobrada" ou enunciado como "Ressalva")
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (editorial.legalProvisions.isNotEmpty()) {
                                            Text(
                                                text = "Fundamento: " + editorial.legalProvisions.joinToString(" · "),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (showTestedRule) {
                                            Text(
                                                text = if (formattedExplanation != null) {
                                                    "Regra cobrada: ${editorial.testedRule}"
                                                } else {
                                                    editorial.testedRule
                                                },
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        if (showTestedRule && formattedExplanation != null) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                        formattedExplanation?.let { expParts ->
                                            Text(
                                                text = remember(expParts.body) { MarkdownParser.parse(expParts.body) },
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            expParts.referenceFooter?.let { refLine ->
                                                Text(
                                                    text = refLine,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        distinctException?.let { exc ->
                                            Text(
                                                text = "Ressalva / Exceção: $exc",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = ext.warning
                                            )
                                        }
                                        actionableError?.let { err ->
                                            Text(
                                                text = "Indicação objetiva do erro: ${err.label} — ${err.description}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = ext.error
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Aprofundar ou consultar trecho:",
                                    style = MaterialTheme.typography.labelMedium
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    if (onOpenContextualLookup != null) {
                                        AssistChip(
                                            onClick = {
                                                val seed = editorial.legalProvisions.firstOrNull()
                                                    ?: extractSuggestedLookupTerms(question).firstOrNull().orEmpty()
                                                onOpenContextualLookup(question, seed)
                                            },
                                            label = { Text("Consultar / Google em Camada") }
                                        )
                                    }
                                    if (formattedExplanation == null) {
                                        AssistChip(
                                            onClick = { onRequestAiTutor(question.id, "explanation", "Gabarito Comentado Completo", null) },
                                            label = { Text("Gerar Comentário IA") }
                                        )
                                    }
                                    if (question.questionType == QuestionType.MULTIPLE_CHOICE) {
                                        AssistChip(
                                            onClick = { onRequestAiTutor(question.id, "plausibility", "Análise dos Distratores", null) },
                                            label = { Text("Análise dos Distratores") }
                                        )
                                    }
                                    AssistChip(
                                        onClick = { onRequestAiTutor(question.id, "extra-concepts", "Conceitos-Chave & Exemplos", null) },
                                        label = { Text("Conceitos-Chave") }
                                    )
                                    AssistChip(
                                        onClick = { onRequestAiTutor(question.id, "child-friendly-post", "Explicação Simples", null) },
                                        label = { Text("Explicação Simples") }
                                    )
                                    AssistChip(
                                        onClick = { onRequestAiTutor(question.id, "mnemonic", "Mnemônico de Fixação", null) },
                                        label = { Text("Criar Mnemônico") }
                                    )
                                }
                            }
                        }
                    }

                    // Veredito do Simulador de Decisão sob Incerteza (quando em bateria de estratégia)
                    if (isStrategySimulator) {
                        item(key = "strategy_simulator_verdict") {
                            val activePenalty = snapshot?.exam?.wrongAnswerPenalty ?: 1.0
                            val activeCutoff = snapshot?.exam?.historicalCutoffScore ?: 65.0
                            val qtKey = if (question.questionType == QuestionType.TRUE_FALSE) "TRUE_FALSE" else "MULTIPLE_CHOICE"
                            val band = if (isBlankAnswer) 1 else selectedConfidence.coerceIn(1, 3)
                            val cell = calibrationCells.firstOrNull { it.questionType == qtKey && it.confidenceBand == band }
                                ?: CalibrationEngine.calibratedProbability(qtKey, band, 0, 0)
                            val rawP = if (question.questionType == QuestionType.TRUE_FALSE) {
                                StrategyEngine.normalizeBinaryChoiceProbability(cell.calibratedMean)
                            } else {
                                cell.calibratedMean
                            }
                            val fallbackScenarios = remember(activePenalty, activeCutoff, estimatedLambda) {
                                StrategyEngine.buildExamMomentScenarios(
                                    mediumP = 0.62,
                                    mediumSd = 0.08,
                                    penalty = activePenalty,
                                    cutoffPct = activeCutoff,
                                    lambdaI = estimatedLambda
                                )
                            }
                            val activeList = examScenarios.ifEmpty { fallbackScenarios }
                            val scenario = activeList[currentIndex % activeList.size]
                            val sim = StrategyEngine.simulateDecisionUnderUncertainty(
                                UncertaintyDecisionInputs(
                                    pBase = rawP,
                                    uncertaintySd = cell.credibleSd,
                                    penalty = activePenalty,
                                    itemDifficulty = 0.50,
                                    lambdaI = estimatedLambda ?: 0.0,
                                    elapsedMinutes = scenario.elapsedMinutes.toDouble(),
                                    currentNetScore = scenario.currentNetScore,
                                    remainingItems = scenario.remainingItems,
                                    targetScore = scenario.targetScore,
                                    minimumScore = scenario.minimumScore,
                                    baselineFutureP = 0.72
                                )
                            )
                            val ptLocale = Locale.forLanguageTag("pt-BR")
                            val evStr = String.format(ptLocale, "%+.2f pt", sim.expectedValue)
                            val pAnsPct = String.format(ptLocale, "%.1f%%", sim.probGoalIfAnswer * 100.0)
                            val pOmitPct = String.format(ptLocale, "%.1f%%", sim.probGoalIfOmit * 100.0)
                            val postureLabel = when (sim.posture) {
                                StrategyPosture.PROTECT_MARGIN -> "Proteger margem acima da meta"
                                StrategyPosture.CALCULATED_RISK -> "Risco calculado p/ buscar a meta"
                                StrategyPosture.MAXIMIZE_EV -> "Maximizar valor esperado"
                            }
                            val recLabel = if (sim.recommendedAction == StrategyAction.OMIT) "Omitir (Deixar em Branco)" else "Responder"

                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Análise de Decisão sob Incerteza (${scenario.elapsedMinutes} min)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "p(t) estimada = ${(sim.pAtTime * 100.0).toInt()}% · Valor Esperado VE = $evStr",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "P(atingir meta | Responder) = $pAnsPct vs. P(atingir meta | Omitir) = $pOmitPct",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Decisão ótima p/ meta: $recLabel ($postureLabel)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Protocolo 50–60 s: Encerrar ou Resolver outra (zero punição ou pressão)
                    if (isSmartEntrySession && questions.size == 1) {
                        item(key = "smart_entry_continuation") {
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Compromisso concluído.",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Você pode encerrar agora sem qualquer penalidade ou resolver mais uma questão curta.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = onCompleteSession,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.45f)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text(
                                                text = "Encerrar",
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Button(
                                            onClick = onResolveAnotherEntry,
                                            modifier = Modifier
                                                .weight(1.2f)
                                                .heightIn(min = 48.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text(
                                                text = "Resolver outra",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = onContinueAdaptiveRoute,
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text(
                                            text = "Seguir bateria adaptativa curta (10 questões) →",
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
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

    if (contextualLookupState != null && contextualLookupState.questionId == question.id) {
        ContextualLookupBottomSheet(
            question = question,
            lookupState = contextualLookupState,
            isAnswerSubmitted = isAnswerSubmitted && !hideFeedback,
            onUpdateLookupQuery = { newQuery ->
                onOpenContextualLookup?.invoke(question, newQuery)
            },
            onRequestAiExplanation = { queryText ->
                onRequestContextualLookupAi(question.id, queryText, isAnswerSubmitted && !hideFeedback)
            },
            onDismiss = onDismissContextualLookup
        )
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
                        text = String.format(Locale.forLanguageTag("pt-BR"), "%.1f%%", netScorePct),
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
                                "Abaixo da Nota de Corte de Referência (${historicalCutoffPct}%). Diferença de ${String.format(Locale.forLanguageTag("pt-BR"), "%.1f", diff)}%."
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
