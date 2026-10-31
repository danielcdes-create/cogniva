package br.com.concursoprep.app.ui

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.data.local.PendingAttemptEntity
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.domain.model.ApkUpdateUiState
import br.com.concursoprep.app.domain.model.AppThemeMode
import br.com.concursoprep.app.domain.model.ContestDiffScreenDto
import br.com.concursoprep.app.domain.model.DisciplineSyllabusGroupDto
import br.com.concursoprep.app.domain.model.ProgressAnalyticsDto
import br.com.concursoprep.app.domain.model.QuestionDto
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.TodayDashboardDto
import br.com.concursoprep.app.domain.model.UserProfileDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed interface ApkInstallEvent {
    data object NeedsUnknownSourcesPermission : ApkInstallEvent
    data class ReadyToInstall(val apkFile: File) : ApkInstallEvent
}

class MainViewModel(
    private val repository: ConcursoRepository,
    private val appContext: Context? = null
) : ViewModel() {

    private val prefs = appContext?.getSharedPreferences("cogniva_prefs", Context.MODE_PRIVATE)

    private val _userId = MutableStateFlow(prefs?.getLong("user_id", 1001L) ?: 1001L)
    val userId: StateFlow<Long> = _userId.asStateFlow()

    private val _userProfile = MutableStateFlow(
        UserProfileDto(
            userId = _userId.value,
            username = prefs?.getString("username", null),
            displayName = prefs?.getString("display_name", "Concurseiro") ?: "Concurseiro",
            dailyQuestionsTarget = prefs?.getInt("daily_questions_target", 50) ?: 50,
            dailyStudyHoursTarget = (prefs?.getFloat("daily_study_hours_target", 2.0f) ?: 2.0f).toDouble(),
            activeContestIds = (prefs?.getString("active_contests", "CPNU,RFB") ?: "CPNU,RFB")
                .split(",")
                .filter { it.isNotBlank() }
                .ifEmpty { listOf("CPNU", "RFB") }
        )
    )
    val userProfile: StateFlow<UserProfileDto> = _userProfile.asStateFlow()

    private val _themeMode = MutableStateFlow(
        runCatching {
            AppThemeMode.valueOf(prefs?.getString("theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name)
        }.getOrDefault(AppThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _cachedQuestionsCount = MutableStateFlow(0)
    val cachedQuestionsCount: StateFlow<Int> = _cachedQuestionsCount.asStateFlow()

    private val _pendingSyncCount = MutableStateFlow(0)
    val pendingSyncCount: StateFlow<Int> = _pendingSyncCount.asStateFlow()

    private val _isOnboardingCompleted = MutableStateFlow(prefs?.getBoolean("onboarding_completed", false) ?: false)
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()

    private val _selectedContestId = MutableStateFlow(prefs?.getString("selected_contest_id", "CPNU") ?: "CPNU")
    val selectedContestId: StateFlow<String> = _selectedContestId.asStateFlow()

    private val _pausedDiscipline = MutableStateFlow(
        prefs?.getString("paused_discipline", "Segurança e Saúde do Trabalhador (NR-15)")
            ?: "Segurança e Saúde do Trabalhador (NR-15)"
    )
    val pausedDiscipline: StateFlow<String?> = _pausedDiscipline.asStateFlow()

    private val _currentQuizMode = MutableStateFlow(QuizMode.STANDARD)
    val currentQuizMode: StateFlow<QuizMode> = _currentQuizMode.asStateFlow()

    private val _todayDashboard = MutableStateFlow<TodayDashboardDto?>(null)
    val todayDashboard: StateFlow<TodayDashboardDto?> = _todayDashboard.asStateFlow()

    private val _syllabusGroups = MutableStateFlow<List<DisciplineSyllabusGroupDto>>(emptyList())
    val syllabusGroups: StateFlow<List<DisciplineSyllabusGroupDto>> = _syllabusGroups.asStateFlow()

    private val _contestDiff = MutableStateFlow<ContestDiffScreenDto?>(null)
    val contestDiff: StateFlow<ContestDiffScreenDto?> = _contestDiff.asStateFlow()

    private val _analytics = MutableStateFlow<ProgressAnalyticsDto?>(null)
    val analytics: StateFlow<ProgressAnalyticsDto?> = _analytics.asStateFlow()

    private val _activeQuizQuestions = MutableStateFlow<List<QuestionDto>?>(null)
    val activeQuizQuestions: StateFlow<List<QuestionDto>?> = _activeQuizQuestions.asStateFlow()

    private val _isSimuladoSession = MutableStateFlow(false)
    val isSimuladoSession: StateFlow<Boolean> = _isSimuladoSession.asStateFlow()

    private val _activeAiSheetMarkdown = MutableStateFlow<String?>(null)
    val activeAiSheetMarkdown: StateFlow<String?> = _activeAiSheetMarkdown.asStateFlow()

    private val _activeAiSheetTitle = MutableStateFlow<String?>(null)
    val activeAiSheetTitle: StateFlow<String?> = _activeAiSheetTitle.asStateFlow()

    private val _userFeedbackMessage = MutableSharedFlow<String>()
    val userFeedbackMessage: SharedFlow<String> = _userFeedbackMessage.asSharedFlow()

    private val _apkUpdateState = MutableStateFlow(
        ApkUpdateUiState(
            installedVersionCode = BuildConfig.VERSION_CODE,
            installedVersionName = BuildConfig.VERSION_NAME
        )
    )
    val apkUpdateState: StateFlow<ApkUpdateUiState> = _apkUpdateState.asStateFlow()

    private val _apkInstallEvents = MutableSharedFlow<ApkInstallEvent>()
    val apkInstallEvents: SharedFlow<ApkInstallEvent> = _apkInstallEvents.asSharedFlow()

    private val _currentSessionUuid = MutableStateFlow(UUID.randomUUID().toString())

    init {
        // Carrega dados iniciais com resiliência offline-first e verifica automaticamente nova versão OTA
        refreshAllData()
        checkApkUpdate()
    }

    fun completeOnboarding(
        telegramUserId: Long?,
        telegramUsername: String?,
        displayName: String,
        selectedContests: List<String>,
        dailyQuestionsTarget: Int,
        dailyHoursTarget: Double
    ) {
        viewModelScope.launch {
            val auth = repository.linkTelegramAccountOrLogin(telegramUserId, telegramUsername, displayName)
            _userId.value = auth.profile.userId

            val profile = repository.saveOnboarding(
                userId = auth.profile.userId,
                contestIds = selectedContests,
                dailyQuestionsTarget = dailyQuestionsTarget,
                dailyHours = dailyHoursTarget,
                displayName = displayName
            )
            val mergedProfile = profile.copy(
                username = auth.profile.username ?: telegramUsername,
                xpTotal = auth.profile.xpTotal,
                currentDailyStreak = auth.profile.currentDailyStreak
            )
            _userProfile.value = mergedProfile
            _isOnboardingCompleted.value = true
            saveProfileToPrefs(mergedProfile)
            prefs?.edit()
                ?.putBoolean("onboarding_completed", true)
                ?.apply()
            refreshAllData()
            _userFeedbackMessage.emit("Perfil configurado com sucesso!")
        }
    }

    private fun saveProfileToPrefs(profile: UserProfileDto) {
        prefs?.edit()
            ?.putLong("user_id", profile.userId)
            ?.putString("username", profile.username)
            ?.putString("display_name", profile.displayName)
            ?.putInt("daily_questions_target", profile.dailyQuestionsTarget)
            ?.putFloat("daily_study_hours_target", profile.dailyStudyHoursTarget.toFloat())
            ?.putString("active_contests", profile.activeContestIds.joinToString(","))
            ?.apply()
    }

    fun updateUserSettings(
        telegramUserId: Long?,
        telegramUsername: String?,
        displayName: String,
        selectedContests: List<String>,
        dailyQuestionsTarget: Int,
        dailyHoursTarget: Double
    ) {
        viewModelScope.launch {
            val auth = repository.linkTelegramAccountOrLogin(
                telegramUserId ?: _userId.value,
                telegramUsername,
                displayName
            )
            _userId.value = auth.profile.userId

            val updatedProfile = repository.saveOnboarding(
                userId = auth.profile.userId,
                contestIds = selectedContests,
                dailyQuestionsTarget = dailyQuestionsTarget,
                dailyHours = dailyHoursTarget,
                displayName = displayName
            ).copy(
                username = auth.profile.username ?: telegramUsername,
                xpTotal = auth.profile.xpTotal,
                currentDailyStreak = auth.profile.currentDailyStreak
            )

            _userProfile.value = updatedProfile
            saveProfileToPrefs(updatedProfile)
            refreshAllData()
            _userFeedbackMessage.emit("Cadastro e preferências de estudo salvos com sucesso!")
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs?.edit()?.putString("theme_mode", mode.name)?.apply()
    }

    fun syncNow() {
        viewModelScope.launch {
            val synced = repository.syncPendingAttempts(_userId.value)
            refreshLocalStats()
            refreshAllData()
            if (synced > 0) {
                _userFeedbackMessage.emit("Sincronizadas $synced resposta(s) pendente(s) com o servidor!")
            } else {
                _userFeedbackMessage.emit("Sincronização concluída. Dados atualizados.")
            }
        }
    }

    fun clearOfflineQuestionCache() {
        viewModelScope.launch {
            repository.clearLocalQuestionCache()
            refreshLocalStats()
            _userFeedbackMessage.emit("Cache local de questões limpo com sucesso.")
        }
    }

    fun reopenOnboardingWizard() {
        _isOnboardingCompleted.value = false
        prefs?.edit()?.putBoolean("onboarding_completed", false)?.apply()
    }

    private suspend fun refreshLocalStats() {
        val (cached, pending) = repository.getLocalStorageStats()
        _cachedQuestionsCount.value = cached
        _pendingSyncCount.value = pending
    }

    fun skipOnboarding() {
        _isOnboardingCompleted.value = true
        prefs?.edit()?.putBoolean("onboarding_completed", true)?.apply()
        refreshAllData()
    }

    fun refreshAllData() {
        viewModelScope.launch {
            val uid = _userId.value
            val profile = _userProfile.value
            _todayDashboard.value = repository.getTodayDashboard(
                userId = uid,
                dailyGoalQuestions = profile.dailyQuestionsTarget,
                activeContestIds = profile.activeContestIds
            )
            _syllabusGroups.value = repository.getContestSyllabus(_selectedContestId.value, uid)
            _contestDiff.value = repository.getContestDiff(_selectedContestId.value)
            _analytics.value = repository.getProgressAnalytics(uid)
            refreshLocalStats()
        }
    }

    fun selectContest(contestId: String) {
        _selectedContestId.value = contestId
        prefs?.edit()?.putString("selected_contest_id", contestId)?.apply()
        viewModelScope.launch {
            val uid = _userId.value
            _syllabusGroups.value = repository.getContestSyllabus(contestId, uid)
            _contestDiff.value = repository.getContestDiff(contestId)
        }
    }

    fun applyEditalDiffUpdate(contestId: String) {
        viewModelScope.launch {
            val uid = _userId.value
            _syllabusGroups.value = repository.getContestSyllabus(contestId, uid)
            _userFeedbackMessage.emit("Cronograma verticalizado atualizado com os novos tópicos do edital!")
        }
    }

    fun startQuizSession(
        mode: QuizMode,
        quantity: Int,
        contestId: String? = null,
        disciplina: String? = null
    ) {
        viewModelScope.launch {
            _currentSessionUuid.value = UUID.randomUUID().toString()
            _currentQuizMode.value = mode
            _isSimuladoSession.value = (mode == QuizMode.SIMULADO)
            if (!disciplina.isNullOrBlank()) {
                _pausedDiscipline.value = disciplina
                prefs?.edit()?.putString("paused_discipline", disciplina)?.apply()
            }
            val questions = repository.fetchOrGetOfflineQuiz(
                userId = _userId.value,
                mode = mode,
                quantity = quantity,
                contestId = contestId ?: _selectedContestId.value,
                disciplina = disciplina
            )
            if (questions.isEmpty()) {
                _userFeedbackMessage.emit("Nenhuma questão encontrada para este filtro no momento.")
            } else {
                _activeQuizQuestions.value = questions
            }
        }
    }

    fun submitAnswer(
        question: QuestionDto,
        chosenIndex: Int,
        confidenceLevel: Int,
        timeSpentSeconds: Int = 60
    ) {
        viewModelScope.launch {
            val correctIndex = question.correctAnswerIndices.firstOrNull() ?: 0
            val isCorrect = chosenIndex == correctIndex
            val chosenText = question.answers.getOrElse(chosenIndex) { "" }

            _pausedDiscipline.value = question.disciplina
            prefs?.edit()?.putString("paused_discipline", question.disciplina)?.apply()

            val attempt = PendingAttemptEntity(
                questionId = question.id,
                chosenIndex = chosenIndex,
                chosenText = chosenText,
                isCorrect = isCorrect,
                timeSpentSeconds = timeSpentSeconds.coerceAtLeast(1),
                confidenceLevel = confidenceLevel,
                sessionUuid = _currentSessionUuid.value,
                quizMode = _currentQuizMode.value.name,
                examBlock = null,
                contestId = _selectedContestId.value,
                userNote = null
            )

            // Registra offline no Room e dispara sincronização em background
            repository.recordAttemptOfflineFirst(_userId.value, attempt)
            refreshLocalStats()
        }
    }

    fun reportProblem(questionId: String, category: String) {
        viewModelScope.launch {
            val success = repository.reportQuestionProblem(_userId.value, questionId, category)
            if (success) {
                _userFeedbackMessage.emit("Problema reportado com sucesso à equipe pedagógica!")
            } else {
                _userFeedbackMessage.emit("Reporte registrado localmente. Sincronizará quando online.")
            }
        }
    }

    private var activeAiJob: Job? = null
    private var activeAiQuestionId: String? = null

    fun requestAiTutor(questionId: String, kind: String, title: String, hintIndex: Int? = null) {
        activeAiJob?.cancel()
        activeAiQuestionId = questionId
        _activeAiSheetTitle.value = title
        _activeAiSheetMarkdown.value = "⏳ Consultando tutor pedagógico Cogniva AI..."

        activeAiJob = viewModelScope.launch {
            val content = repository.requestAiTutor(questionId, kind, hintIndex)
            if (activeAiQuestionId == questionId) {
                _activeAiSheetMarkdown.value = content
            }
        }
    }

    fun dismissAiSheet() {
        activeAiJob?.cancel()
        activeAiJob = null
        activeAiQuestionId = null
        _activeAiSheetMarkdown.value = null
        _activeAiSheetTitle.value = null
    }

    fun exitSession() {
        _activeQuizQuestions.value = null
        _isSimuladoSession.value = false
        _activeAiSheetMarkdown.value = null
        _activeAiSheetTitle.value = null
        refreshAllData()
    }

    fun checkApkUpdate() {
        viewModelScope.launch {
            _apkUpdateState.value = _apkUpdateState.value.copy(
                isChecking = true,
                statusMessage = "Consultando servidor Cogniva..."
            )
            val remote = repository.fetchRemoteApkInfo()
            if (remote == null) {
                _apkUpdateState.value = _apkUpdateState.value.copy(
                    isChecking = false,
                    statusMessage = "Servidor sem novo APK publicado ou offline no momento."
                )
                return@launch
            }

            val currentCode = BuildConfig.VERSION_CODE
            val hasNewer = remote.versionCode > currentCode
            val msg = if (hasNewer) {
                "Nova versão disponível: v${remote.versionName} (build ${remote.versionCode})"
            } else {
                "App atualizado (servidor: v${remote.versionName} · SHA-256 ${remote.sha256Checksum.take(8)}…)"
            }

            _apkUpdateState.value = _apkUpdateState.value.copy(
                isChecking = false,
                remoteInfo = remote,
                hasUpdate = hasNewer,
                statusMessage = msg
            )
        }
    }

    fun downloadAndInstallApk(context: Context) {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    _apkUpdateState.value = _apkUpdateState.value.copy(
                        statusMessage = "Permita 'Instalar apps desconhecidos' para o Cogniva e toque em Atualizar novamente."
                    )
                    _apkInstallEvents.emit(ApkInstallEvent.NeedsUnknownSourcesPermission)
                    return@launch
                }
            }

            _apkUpdateState.value = _apkUpdateState.value.copy(
                isDownloading = true,
                statusMessage = "Baixando APK e verificando assinatura SHA-256..."
            )

            val updatesDir = File(context.cacheDir, "updates")
            val apkFile = repository.downloadApkUpdate(updatesDir)
            if (apkFile == null) {
                _apkUpdateState.value = _apkUpdateState.value.copy(
                    isDownloading = false,
                    statusMessage = "Falha no download ou checksum SHA-256 inválido."
                )
                _userFeedbackMessage.emit("Falha ao baixar ou validar o APK no servidor.")
                return@launch
            }

            _apkUpdateState.value = _apkUpdateState.value.copy(
                isDownloading = false,
                statusMessage = "APK verificado com sucesso! Abrindo instalador do Android..."
            )
            _apkInstallEvents.emit(ApkInstallEvent.ReadyToInstall(apkFile))
        }
    }
}

class MainViewModelFactory(
    private val repository: ConcursoRepository,
    private val appContext: Context? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
