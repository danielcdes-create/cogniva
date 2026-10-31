package br.com.concursoprep.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import br.com.concursoprep.app.domain.model.ContestStatus
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.TodayDashboardDto
import br.com.concursoprep.app.domain.model.UpcomingExamCard
import br.com.concursoprep.app.domain.model.CommonTrunkSuggestion
import br.com.concursoprep.app.domain.model.ConfidenceMatrixSummaryDto
import br.com.concursoprep.app.domain.model.DailyHeatmapCellDto
import br.com.concursoprep.app.domain.model.DisciplineMasteryBarDto
import br.com.concursoprep.app.domain.model.ProgressAnalyticsDto
import br.com.concursoprep.app.ui.ApkInstallEvent
import br.com.concursoprep.app.ui.MainViewModel
import br.com.concursoprep.app.ui.MainViewModelFactory
import br.com.concursoprep.app.ui.screens.ContestsScreen
import br.com.concursoprep.app.ui.screens.OnboardingScreen
import br.com.concursoprep.app.ui.screens.PracticeHubScreen
import br.com.concursoprep.app.ui.screens.ProgressScreen
import br.com.concursoprep.app.ui.screens.QuestionSessionScreen
import br.com.concursoprep.app.ui.screens.TodayScreen
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(ConcursoApp.instance.repository, applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ConcursoPrepTheme {
                ConcursoPrepRootApp(viewModel)
            }
        }
    }
}

private enum class BottomTab(val label: String) {
    HOJE("Hoje"),
    CONCURSOS("Concursos"),
    PRATICAR("Praticar"),
    PROGRESSO("Progresso")
}

@Composable
fun ConcursoPrepRootApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(BottomTab.HOJE) }
    val snackbarHostState = remember { SnackbarHostState() }

    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    val activeQuestions by viewModel.activeQuizQuestions.collectAsState()
    val isSimuladoSession by viewModel.isSimuladoSession.collectAsState()
    val selectedContestId by viewModel.selectedContestId.collectAsState()
    val todayDashboard by viewModel.todayDashboard.collectAsState()
    val syllabusGroups by viewModel.syllabusGroups.collectAsState()
    val contestDiff by viewModel.contestDiff.collectAsState()
    val analytics by viewModel.analytics.collectAsState()
    val activeAiSheetMarkdown by viewModel.activeAiSheetMarkdown.collectAsState()
    val activeAiSheetTitle by viewModel.activeAiSheetTitle.collectAsState()
    val apkUpdateState by viewModel.apkUpdateState.collectAsState()

    // Ouve notificações e feedbacks do ViewModel
    LaunchedEffect(Unit) {
        viewModel.userFeedbackMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Ouve eventos de instalação OTA de APK (FileProvider + Unknown Sources)
    LaunchedEffect(Unit) {
        viewModel.apkInstallEvents.collectLatest { event ->
            when (event) {
                is ApkInstallEvent.NeedsUnknownSourcesPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
                is ApkInstallEvent.ReadyToInstall -> {
                    try {
                        val apkUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            event.apkFile
                        )
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(installIntent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Erro ao abrir instalador: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    // 1. Rota de Onboarding (para novos usuários ou vínculo inicial com o Bot Telegram)
    if (!isOnboardingCompleted) {
        OnboardingScreen(
            onCompleteOnboarding = { telegramUserId, telegramUsername, displayName, contests, dailyQ, dailyH ->
                viewModel.completeOnboarding(
                    telegramUserId = telegramUserId,
                    telegramUsername = telegramUsername,
                    displayName = displayName,
                    selectedContests = contests,
                    dailyQuestionsTarget = dailyQ,
                    dailyHoursTarget = dailyH
                )
            }
        )
        return
    }

    // 2. Rota Full-Screen Imersiva para Questões e Simulados (esconde a Bottom Bar conforme §3 da Nota Técnica)
    if (activeQuestions != null && activeQuestions!!.isNotEmpty()) {
        val contestTitle = when (selectedContestId) {
            "CPNU" -> "CPNU · Auditor-Fiscal do Trabalho (Edital Publicado)"
            "RFB" -> "Receita Federal · Auditor Fiscal (Estimado)"
            else -> "BNDES · Analista (Estimado)"
        }

        QuestionSessionScreen(
            questions = activeQuestions!!,
            initialIndex = 0,
            isSimuladoMode = isSimuladoSession,
            contestName = contestTitle,
            historicalCutoffPct = 78.5,
            aiTutorMarkdown = activeAiSheetMarkdown,
            aiTutorTitle = activeAiSheetTitle,
            onRequestAiTutor = { qId, kind, title ->
                viewModel.requestAiTutor(qId, kind, title)
            },
            onDismissAiSheet = { viewModel.dismissAiSheet() },
            onSubmitAnswer = { question, chosenIndex, confidenceLevel ->
                viewModel.submitAnswer(question, chosenIndex, confidenceLevel)
            },
            onReportProblem = { qId, category ->
                viewModel.reportProblem(qId, category)
            },
            onExitSession = {
                viewModel.exitSession()
            }
        )
        return
    }

    // 3. Aplicação Principal: 4 Abas com Scaffold e Bottom Navigation Bar
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == BottomTab.HOJE,
                    onClick = { currentTab = BottomTab.HOJE },
                    icon = { Icon(Icons.Default.Today, contentDescription = null) },
                    label = { Text(BottomTab.HOJE.label) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomTab.CONCURSOS,
                    onClick = { currentTab = BottomTab.CONCURSOS },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                    label = { Text(BottomTab.CONCURSOS.label) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomTab.PRATICAR,
                    onClick = { currentTab = BottomTab.PRATICAR },
                    icon = { Icon(Icons.Default.Quiz, contentDescription = null) },
                    label = { Text(BottomTab.PRATICAR.label) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomTab.PROGRESSO,
                    onClick = { currentTab = BottomTab.PROGRESSO },
                    icon = { Icon(Icons.Default.Assessment, contentDescription = null) },
                    label = { Text(BottomTab.PROGRESSO.label) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentTab) {
                BottomTab.HOJE -> {
                    val safeDashboard = todayDashboard ?: buildOfflineFallbackDashboard()
                    TodayScreen(
                        dashboard = safeDashboard,
                        pausedDiscipline = "Segurança e Saúde do Trabalhador (NR-15)",
                        apkUpdateState = apkUpdateState,
                        onCheckApkUpdate = { viewModel.checkApkUpdate() },
                        onInstallApkUpdate = { viewModel.downloadAndInstallApk(context) },
                        onStartQuizMode = { mode, _ ->
                            viewModel.startQuizSession(mode = mode, quantity = 10)
                        },
                        onOpenContestSyllabus = { cid ->
                            viewModel.selectContest(cid)
                            currentTab = BottomTab.CONCURSOS
                        }
                    )
                }

                BottomTab.CONCURSOS -> {
                    ContestsScreen(
                        selectedContestId = selectedContestId,
                        availableContests = listOf(
                            "CPNU" to "🔴 CPNU (AFT)",
                            "RFB" to "🟡 Receita Federal",
                            "BNDES" to "⚪ BNDES"
                        ),
                        syllabusGroups = syllabusGroups,
                        diffData = contestDiff,
                        onSelectContest = { cid ->
                            viewModel.selectContest(cid)
                        },
                        onPracticeDiscipline = { disc ->
                            viewModel.startQuizSession(
                                mode = QuizMode.STANDARD,
                                quantity = 10,
                                contestId = selectedContestId,
                                disciplina = disc
                            )
                        }
                    )
                }

                BottomTab.PRATICAR -> {
                    PracticeHubScreen(
                        onLaunchSession = { mode, quantity ->
                            viewModel.startQuizSession(
                                mode = mode,
                                quantity = quantity,
                                contestId = selectedContestId
                            )
                        }
                    )
                }

                BottomTab.PROGRESSO -> {
                    val safeAnalytics = analytics ?: buildOfflineFallbackAnalytics()
                    ProgressScreen(analytics = safeAnalytics)
                }
            }
        }
    }
}

private fun buildOfflineFallbackDashboard() = TodayDashboardDto(
    userId = 1001L,
    dateIso = "2026-10-30",
    dailyGoalQuestions = 50,
    questionsAnsweredToday = 18,
    accuracyTodayPct = 78.0,
    pendingSrsReviewsCount = 14,
    errorNotebookCount = 23,
    highConfidenceErrorsCount = 3,
    upcomingExams = listOf(
        UpcomingExamCard(
            contestId = "CPNU",
            name = "CPNU · Auditor-Fiscal do Trabalho",
            status = ContestStatus.EDITAL_PUBLICADO,
            daysRemaining = 44,
            estimatedWindow = null,
            examBoard = "Cesgranrio (Confirmada)"
        ),
        UpcomingExamCard(
            contestId = "RFB",
            name = "Receita Federal · Auditor Fiscal",
            status = ContestStatus.SOLICITADO_AUTORIZADO,
            daysRemaining = null,
            estimatedWindow = "Provável: 1º sem/2027",
            examBoard = "FGV / Cebraspe (Estimada)"
        ),
        UpcomingExamCard(
            contestId = "BNDES",
            name = "BNDES · Profissional Básico",
            status = ContestStatus.PREVISTO,
            daysRemaining = null,
            estimatedWindow = "Previsto: 2º sem/2027",
            examBoard = "Cesgranrio (Provável)"
        )
    ),
    commonTrunkSuggestion = CommonTrunkSuggestion(
        discipline = "Finanças Públicas",
        sharedContests = listOf("CPNU", "RFB"),
        badgeText = "Estudar 15 questões de Finanças Públicas avança CPNU e Receita Federal ao mesmo tempo"
    )
)

private fun buildOfflineFallbackAnalytics() = ProgressAnalyticsDto(
    userId = 1001L,
    globalAccuracyPct = 76.5,
    totalQuestionsPracticed = 428,
    totalMasteredQuestions = 189,
    readinessIndexScore = 79.2,
    historicalCutoffReference = 78.5,
    disciplinesSortedByWeakness = listOf(
        DisciplineMasteryBarDto(
            discipline = "Direito Previdenciário",
            accuracyPct = 52.0,
            totalAttempts = 50,
            masteredQuestions = 12,
            pointsAccumulated = 42.0
        ),
        DisciplineMasteryBarDto(
            discipline = "Economia do Trabalho",
            accuracyPct = 68.0,
            totalAttempts = 85,
            masteredQuestions = 38,
            pointsAccumulated = 75.0
        ),
        DisciplineMasteryBarDto(
            discipline = "Segurança e Saúde no Trabalho",
            accuracyPct = 86.0,
            totalAttempts = 220,
            masteredQuestions = 110,
            pointsAccumulated = 195.0
        )
    ),
    confidenceMatrix = ConfidenceMatrixSummaryDto(
        highConfidenceCorrect = 142,
        lowConfidenceCorrect = 88,
        lowConfidenceError = 41,
        highConfidenceError = 15 // Ponto Cego
    ),
    activityHeatmap = listOf(
        DailyHeatmapCellDto("2026-10-25", 35, 75.0),
        DailyHeatmapCellDto("2026-10-26", 45, 80.0),
        DailyHeatmapCellDto("2026-10-27", 52, 72.0),
        DailyHeatmapCellDto("2026-10-28", 20, 65.0),
        DailyHeatmapCellDto("2026-10-29", 58, 85.0),
        DailyHeatmapCellDto("2026-10-30", 18, 78.0)
    )
)
