package br.com.concursoprep.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import br.com.concursoprep.app.domain.model.AppThemeMode
import br.com.concursoprep.app.domain.model.ContestStatus
import br.com.concursoprep.app.domain.exam.ExamEngine
import br.com.concursoprep.app.ui.screens.ExamFormBottomSheet
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
import br.com.concursoprep.app.ui.screens.MissionPlayerScreen
import br.com.concursoprep.app.ui.screens.MissionsCatalogScreen
import br.com.concursoprep.app.ui.screens.OnboardingScreen
import br.com.concursoprep.app.ui.screens.PracticeHubScreen
import br.com.concursoprep.app.ui.screens.ProgressScreen
import br.com.concursoprep.app.ui.screens.QuestionSessionScreen
import br.com.concursoprep.app.ui.screens.SettingsScreen
import br.com.concursoprep.app.ui.screens.TodayScreen
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(ConcursoApp.instance.repository, applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val isDark = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            ConcursoPrepTheme(darkTheme = isDark) {
                ConcursoPrepRootApp(viewModel)
            }
        }
    }
}

private enum class BottomTab(val label: String) {
    HOJE("Hoje"),
    MISSOES("Missões"),
    CONCURSOS("Concursos"),
    PRATICAR("Praticar"),
    PROGRESSO("Progresso"),
    AJUSTES("Ajustes")
}

@Composable
fun ConcursoPrepRootApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf(BottomTab.HOJE) }
    var questionImportOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    val activeQuestions by viewModel.activeQuizQuestions.collectAsState()
    val isSimuladoSession by viewModel.isSimuladoSession.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val editingExam by viewModel.editingExam.collectAsState()
    val pendingReview by viewModel.pendingReview.collectAsState()
    val showPendingReview by viewModel.showPendingReview.collectAsState()
    val selectedContestId by viewModel.selectedContestId.collectAsState()
    val todayDashboard by viewModel.todayDashboard.collectAsState()
    val syllabusGroups by viewModel.syllabusGroups.collectAsState()
    val contestDiff by viewModel.contestDiff.collectAsState()
    val analytics by viewModel.analytics.collectAsState()
    val activeAiSheetMarkdown by viewModel.activeAiSheetMarkdown.collectAsState()
    val activeAiSheetTitle by viewModel.activeAiSheetTitle.collectAsState()
    val contextualLookupState by viewModel.contextualLookupState.collectAsState()
    val apkUpdateState by viewModel.apkUpdateState.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val accountAuthState by viewModel.accountAuthState.collectAsState()
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val cachedQuestionsCount by viewModel.cachedQuestionsCount.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsState()
    val pausedDiscipline by viewModel.pausedDiscipline.collectAsState()
    val storedExams by viewModel.storedExams.collectAsState()
    val pendingExamDeletions by viewModel.pendingExamDeletions.collectAsState()
    val focusedTrunks by viewModel.focusedTrunks.collectAsState()
    val syllabusViewMode by viewModel.syllabusViewMode.collectAsState()
    val showExamBuilder by viewModel.showExamBuilder.collectAsState()
    val missionsCatalog by viewModel.missionsCatalog.collectAsState()
    val activeMissionDetail by viewModel.activeMissionDetail.collectAsState()
    val isMissionBusy by viewModel.isMissionBusy.collectAsState()
    var trunkSlug by remember { mutableStateOf("fiscal") }

    // Ouve notificações e feedbacks do ViewModel
    LaunchedEffect(Unit) {
        viewModel.userFeedbackMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Ouve eventos de instalação OTA de APK (PackageInstaller.Session + Unknown Sources)
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
                is ApkInstallEvent.ConfirmSessionInstall -> {
                    try {
                        val confirmIntent = Intent(event.confirmationIntent).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Erro ao abrir confirmação do instalador: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
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

    LaunchedEffect(Unit) {
        viewModel.accountChanged.collectLatest { id ->
            ConcursoApp.instance.selectAccount(id)
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            (context as? android.app.Activity)?.finish()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.accountDeletedSwitch.collectLatest { switch ->
            ConcursoApp.instance.wipeLocalAccountDatabaseAndOpenGuest(switch.deletedUserId, switch.nextGuestUserId)
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            (context as? android.app.Activity)?.finish()
        }
    }

    LaunchedEffect(Unit){viewModel.pendingExport.collectLatest { file ->
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
        val intent=Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent,"Exportar registros preservados"))
    }}
    // 1. Rota de Onboarding (boas-vindas nativas Cogniva com modo convidado imediato ou conta por e-mail)
    if (!isOnboardingCompleted) {
        OnboardingScreen(
            onSkipOnboarding = { viewModel.skipOnboarding() },
            snackbarState = snackbarHostState,
            availableExams = storedExams.ifEmpty { br.com.concursoprep.app.domain.exam.ExamCatalog.systemExams() },
            onCompleteOnboarding = { displayName, contests, dailyQ, dailyH, email, password, authMode ->
                viewModel.completeOnboarding(
                    displayName = displayName,
                    selectedContests = contests,
                    dailyQuestionsTarget = dailyQ,
                    dailyHoursTarget = dailyH,
                    email = email,
                    password = password,
                    authMode = authMode
                )
            }
        )
        return
    }

    // 2. Rota Full-Screen Imersiva para Questões e Simulados (esconde a Bottom Bar conforme §3 da Nota Técnica)
    if (activeQuestions != null && activeQuestions!!.isNotEmpty()) {
        val contestTitle = activeSession?.exam?.title ?: "Treino"

        QuestionSessionScreen(
            questions = activeQuestions!!,
            initialIndex = activeSession?.currentIndex ?: 0,
            isSimuladoMode = isSimuladoSession,
            contestName = contestTitle,
            historicalCutoffPct = activeSession?.exam?.historicalCutoffScore ?: 0.0,
            snapshot = activeSession,
            onUpdateSession = { viewModel.updateSession(it) },
            onCompleteSession = { viewModel.exitSession(completed=true) },
            onSubmitSimuladoBatch = { answers -> viewModel.submitSimuladoBatch(answers) },
            snackbarState = snackbarHostState,
            aiTutorMarkdown = activeAiSheetMarkdown,
            aiTutorTitle = activeAiSheetTitle,
            onRequestAiTutor = { qId, kind, title, hintIdx ->
                viewModel.requestAiTutor(qId, kind, title, hintIdx)
            },
            onDismissAiSheet = { viewModel.dismissAiSheet() },
            onSubmitAnswer = { question, chosenIndex, confidenceLevel, timeSpentSeconds ->
                viewModel.submitAnswer(question, chosenIndex, confidenceLevel, timeSpentSeconds)
            },
            onReportProblem = { qId, category ->
                viewModel.reportProblem(qId, category)
            },
            onExitSession = {
                viewModel.exitSession()
            },
            requireExplicitConfidence = true,
            allowBlankAnswer = activeSession?.mode in listOf(QuizMode.SIMULADO, QuizMode.STRATEGY_BATTERY, QuizMode.ENTRY_60S) || activeSession?.isSmartEntry == true,
            isSmartEntrySession = activeSession?.isSmartEntry == true || activeSession?.mode == QuizMode.ENTRY_60S,
            onContinueAdaptiveRoute = { viewModel.continueAdaptiveRouteFromEntry() },
            onResolveAnotherEntry = { viewModel.resolveAnotherSmartEntryQuestion() },
            isStrategySimulator = activeSession?.mode == QuizMode.STRATEGY_BATTERY,
            examScenarios = todayDashboard?.nextAction?.examScenarios ?: analytics?.examScenarios.orEmpty(),
            calibrationCells = todayDashboard?.nextAction?.calibrationCells ?: analytics?.calibrationCells.orEmpty(),
            estimatedLambda = todayDashboard?.nextAction?.fatigueReport?.estimatedLambda ?: analytics?.fatigueReport?.estimatedLambda,
            contextualLookupState = contextualLookupState,
            onOpenContextualLookup = { q, selected -> viewModel.openContextualLookup(q, selected) },
            onRequestContextualLookupAi = { qId, selected, submitted -> viewModel.requestContextualLookupAi(qId, selected, submitted) },
            onDismissContextualLookup = { viewModel.dismissContextualLookup() },
            onSelectEvidence = { qId, evId -> viewModel.selectEvidenceForQuestion(qId, evId) },
            onSubmitExtendedAnswer = { question, chosenIndex, confidenceLevel, timeSpentSeconds, isBlank, confidenceExplicit ->
                viewModel.submitAnswer(
                    question = question,
                    chosenIndex = chosenIndex,
                    confidenceLevel = confidenceLevel,
                    timeSpentSeconds = timeSpentSeconds,
                    isBlank = isBlank,
                    confidenceExplicit = confidenceExplicit
                )
            }
        )
        return
    }

    // 2b. Rota Full-Screen Imersiva para o Player de Missões Interativas
    if (activeMissionDetail != null) {
        val currentMission = activeMissionDetail!!
        MissionPlayerScreen(
            detail = currentMission,
            isBusy = isMissionBusy,
            onExitMission = { sid, posMs -> viewModel.exitActiveMission(sid, posMs) },
            onAdvanceScene = { doneId, nextId, viaTranscript ->
                viewModel.advanceMissionScene(currentMission.missionId, doneId, nextId, viaTranscript)
            },
            onJumpToScene = { targetId ->
                viewModel.jumpToMissionScene(currentMission.missionId, targetId)
            },
            onSubmitDecision = { sid, iid, optId, spentMs, viaTranscript ->
                viewModel.submitMissionDecision(currentMission.missionId, sid, iid, optId, spentMs, viaTranscript)
            },
            onRepeatMission = { viewModel.repeatMission(currentMission.missionId) },
            onStartRelatedPractice = {
                viewModel.startMissionRelatedPractice(currentMission.missionId, currentMission.contestTemplateId)
            },
            onResolveVideoFile = { assetId, sha256 ->
                viewModel.resolveMissionVideoFile(context.cacheDir, assetId, sha256)
            },
            onPreloadNextVideo = { assetId, sha256 ->
                viewModel.preloadMissionVideoInBackground(context.cacheDir, assetId, sha256)
            },
        )
        return
    }

    // 3. Aplicação Principal com Scaffold e Bottom Navigation Bar
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == BottomTab.HOJE,
                    onClick = { currentTab = BottomTab.HOJE },
                    icon = { Icon(Icons.Default.Today, contentDescription = null) },
                    label = { Text(BottomTab.HOJE.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomTab.MISSOES,
                    onClick = { currentTab = BottomTab.MISSOES },
                    icon = { Icon(Icons.Default.OndemandVideo, contentDescription = null) },
                    label = { Text(BottomTab.MISSOES.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomTab.CONCURSOS,
                    onClick = { currentTab = BottomTab.CONCURSOS },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    label = { Text(BottomTab.CONCURSOS.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomTab.PRATICAR,
                    onClick = { currentTab = BottomTab.PRATICAR },
                    icon = { Icon(Icons.Default.Quiz, contentDescription = null) },
                    label = { Text(BottomTab.PRATICAR.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomTab.PROGRESSO,
                    onClick = { currentTab = BottomTab.PROGRESSO },
                    icon = { Icon(Icons.Default.Assessment, contentDescription = null) },
                    label = { Text(BottomTab.PROGRESSO.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = currentTab == BottomTab.AJUSTES,
                    onClick = { currentTab = BottomTab.AJUSTES },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(BottomTab.AJUSTES.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
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
                        pausedDiscipline = pausedDiscipline,
                        onResumeSession = { viewModel.resumeSession() },
                        apkUpdateState = apkUpdateState,
                        onCheckApkUpdate = { viewModel.checkApkUpdate() },
                        onInstallApkUpdate = { viewModel.downloadAndInstallApk(context) },
                        onOpenSettings = { currentTab = BottomTab.AJUSTES },
                        onStartQuizMode = { mode, disc ->
                            viewModel.startQuizSession(mode = mode, quantity = 10, disciplina = disc)
                        },
                        onOpenContestSyllabus = { cid ->
                            viewModel.selectContest(cid)
                            currentTab = BottomTab.CONCURSOS
                        },
                        onAddExam = { viewModel.openExamBuilder() },
                        onStartSmartEntry = { viewModel.startSmartEntryQuestion() },
                        onStartStrategyBattery = {
                            viewModel.startQuizSession(
                                mode = QuizMode.STRATEGY_BATTERY,
                                quantity = 10,
                                contestId = selectedContestId
                            )
                        },
                        onChooseConsciousRest = { rested -> viewModel.chooseConsciousRestToday(rested) },
                        onScreenVisible = { viewModel.recordEntryImpression() },
                        onOpenMissions = { currentTab = BottomTab.MISSOES }
                    )
                }

                BottomTab.MISSOES -> {
                    MissionsCatalogScreen(
                        catalog = missionsCatalog,
                        isBusy = isMissionBusy,
                        onOpenMission = { missionId, resume ->
                            viewModel.openMission(missionId, resume)
                        },
                        onRefresh = { viewModel.refreshMissionsCatalog() }
                    )
                }

                BottomTab.CONCURSOS -> {
                    ContestsScreen(
                        selectedContestId = selectedContestId,
                        availableContests = storedExams.filter { it.lifecycleStatus != "ARQUIVADO" }.map { exam -> exam.examId to exam.shortName },
                        syllabusGroups = syllabusGroups,
                        diffData = contestDiff,
                        onSelectContest = { cid ->
                            viewModel.selectContest(cid)
                        },
                        syllabusViewMode = syllabusViewMode,
                        onSyllabusViewMode = { viewModel.setSyllabusViewMode(it) },
                        onAddContest = { viewModel.openExamBuilder() },
                        trunkRows = ExamEngine.trunkRows(trunkSlug, storedExams),
                        trunkSlug = trunkSlug,
                        onSelectTrunk = { trunkSlug = it },
                        onEditContest = { cid -> storedExams.find { it.examId == cid }?.let { viewModel.editExam(it) } },
                        onDeleteContest = { cid -> storedExams.find { it.examId == cid }?.let { viewModel.deleteExam(it) } },
                        upstreamUpdateAvailable = storedExams.find { it.examId == selectedContestId }?.upstreamUpdateAvailable == true,
                        onAdoptTemplate = { cid -> viewModel.adoptExamTemplate(cid) },
                        onPracticeDiscipline = { disc, component, topicId ->
                            viewModel.startQuizSession(
                                mode = QuizMode.STANDARD,
                                quantity = 10,
                                contestId = selectedContestId,
                                disciplina = disc,
                                componentId = component,
                                syllabusTopicId = topicId
                            )
                        },
                        onApplyDiffConsent = { cid ->
                            viewModel.applyEditalDiffUpdate(cid)
                        }
                    )
                }

                BottomTab.PRATICAR -> {
                    PracticeHubScreen(
                        onLaunchSession = { mode, quantity ->
                            if (mode == QuizMode.ENTRY_60S) {
                                viewModel.startSmartEntryQuestion()
                            } else {
                                viewModel.startQuizSession(
                                    mode = mode,
                                    quantity = quantity,
                                    contestId = if (mode in listOf(QuizMode.SIMULADO, QuizMode.STRATEGY_BATTERY, QuizMode.TRANSFER_PROBE, QuizMode.ADAPTIVE_LOOP)) selectedContestId else null
                                )
                            }
                        },
                        onOpenMissions = { currentTab = BottomTab.MISSOES }
                    )
                }

                BottomTab.PROGRESSO -> {
                    val safeAnalytics = analytics ?: buildOfflineFallbackAnalytics(storedExams)
                    ProgressScreen(
                        analytics = safeAnalytics,
                        onPracticeQuizMode = { mode, disc ->
                            viewModel.startQuizSession(
                                mode = mode,
                                quantity = 10,
                                contestId = if (mode in listOf(QuizMode.STRATEGY_BATTERY, QuizMode.TRANSFER_PROBE)) selectedContestId else null,
                                disciplina = disc
                            )
                        }
                    )
                }

                BottomTab.AJUSTES -> {
                    if (questionImportOpen) {
                        val questionImportViewModel: br.com.concursoprep.app.ui.QuestionImportViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            key = "question-import-${ConcursoApp.instance.repository.ownerUserId}",
                            factory = br.com.concursoprep.app.ui.QuestionImportViewModel.Factory(ConcursoApp.instance.repository)
                        )
                        br.com.concursoprep.app.ui.screens.QuestionImportScreen(questionImportViewModel) { questionImportOpen = false }
                    } else {
                    SettingsScreen(
                        onOpenQuestionImport = { questionImportOpen = true },
                        userProfile = userProfile,
                        accountAuthState = accountAuthState,
                        onAuthenticateAccount = { email, password, dispName, isRegister ->
                            viewModel.authenticateAccount(email, password, dispName, isRegister)
                        },
                        onLogoutAccount = { viewModel.logoutAccount() },
                        isDeletingAccount = isDeletingAccount,
                        onDeleteAccount = { confirm -> viewModel.deleteAccount(confirm) },
                        themeMode = themeMode,
                        apkUpdateState = apkUpdateState,
                        cachedQuestionsCount = cachedQuestionsCount,
                        pendingSyncCount = pendingSyncCount,
                        onSelectThemeMode = { mode ->
                            viewModel.setThemeMode(mode)
                        },
                        onSaveUserSettings = { dispName, contests, dailyQ, dailyH ->
                            viewModel.updateUserSettings(
                                displayName = dispName,
                                selectedContests = contests,
                                dailyQuestionsTarget = dailyQ,
                                dailyHoursTarget = dailyH
                            )
                        },
                        onCheckApkUpdate = { viewModel.checkApkUpdate() },
                        onInstallApkUpdate = { viewModel.downloadAndInstallApk(context) },
                        onSyncNow = { viewModel.syncNow() },
                        onClearOfflineCache = { viewModel.clearOfflineQuestionCache() },
                        ladderExams = storedExams,
                        pendingExamDeletions = pendingExamDeletions,
                        focusedTrunkSlugs = focusedTrunks,
                        onToggleTrunkFocus = { viewModel.toggleTrunkFocus(it) },
                        onEditExam = { viewModel.editExam(it) },
                        onArchiveExam = { viewModel.archiveExam(it) },
                        onRestoreExam = { viewModel.restoreExam(it) },
                        onDeleteExam = { viewModel.deleteExam(it) },
                        onCreateExam = { viewModel.openExamBuilder() },
                        onUseServerExam = { viewModel.useServerExam(it) },
                        onAdoptTemplate = { viewModel.adoptExamTemplate(it.examId) },
                        onOpenPending = { viewModel.openPendingReview() },
                        onReopenOnboarding = {
                            currentTab = BottomTab.HOJE
                            viewModel.reopenOnboardingWizard()
                        }
                    )
                    }
                }
            }
        }
    }

    if (showPendingReview) {
        br.com.concursoprep.app.ui.screens.PendingReviewSheet(
            items = pendingReview,
            onDismiss = { viewModel.dismissPendingReview() },
            onExport = { viewModel.exportPendingReview() },
            onDismissItem = { viewModel.dismissPendingReviewItem(it) },
            onClearQuarantined = { viewModel.clearQuarantinedReviewItems() }
        )
    }
    if (showExamBuilder) {
        ExamFormBottomSheet(
            existingExams = storedExams,
            editingExam = editingExam,
            onDismiss = { viewModel.dismissExamBuilder() },
            onSave = { draft -> viewModel.saveExamDraft(draft) },
            onDelete = { exam -> viewModel.deleteExam(exam) }
        )
    }
}

private fun buildOfflineFallbackDashboard() = TodayDashboardDto(
    userId=0,dateIso=java.time.LocalDate.now().toString(),dailyGoalQuestions=0,questionsAnsweredToday=0,accuracyTodayPct=0.0,
    pendingSrsReviewsCount=0,errorNotebookCount=0,highConfidenceErrorsCount=0,upcomingExams=emptyList(),commonTrunkSuggestion=CommonTrunkSuggestion("",emptyList(),""),syncMessage="Carregando dados deste aparelho…")
private fun buildOfflineFallbackAnalytics(exams:List<br.com.concursoprep.app.domain.exam.StoredExam>) = ProgressAnalyticsDto(
    userId=0,globalAccuracyPct=0.0,totalQuestionsPracticed=0,totalMasteredQuestions=0,readinessIndexScore=0.0,historicalCutoffReference=0.0,
    disciplinesSortedByWeakness=emptyList(),confidenceMatrix=ConfidenceMatrixSummaryDto(0,0,0,0),activityHeatmap=emptyList())
