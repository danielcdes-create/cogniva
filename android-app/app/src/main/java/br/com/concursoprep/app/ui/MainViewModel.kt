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
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed interface ApkInstallEvent {
    data object NeedsUnknownSourcesPermission : ApkInstallEvent
    data class ReadyToInstall(val apkFile: File) : ApkInstallEvent
}

class MainViewModel(
    private val repository: ConcursoRepository
) : ViewModel() {

    private val _userId = MutableStateFlow(1001L)
    val userId: StateFlow<Long> = _userId.asStateFlow()

    private val _userProfile = MutableStateFlow<UserProfileDto?>(null)
    val userProfile: StateFlow<UserProfileDto?> = _userProfile.asStateFlow()

    private val _isOnboardingCompleted = MutableStateFlow(false)
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()

    private val _selectedContestId = MutableStateFlow("CPNU")
    val selectedContestId: StateFlow<String> = _selectedContestId.asStateFlow()

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
            _userProfile.value = profile
            _isOnboardingCompleted.value = true
            refreshAllData()
            _userFeedbackMessage.emit("Perfil configurado com sucesso!")
        }
    }

    fun skipOnboarding() {
        _isOnboardingCompleted.value = true
        refreshAllData()
    }

    fun refreshAllData() {
        viewModelScope.launch {
            val uid = _userId.value
            _todayDashboard.value = repository.getTodayDashboard(uid)
            _syllabusGroups.value = repository.getContestSyllabus(_selectedContestId.value, uid)
            _contestDiff.value = repository.getContestDiff(_selectedContestId.value)
            _analytics.value = repository.getProgressAnalytics(uid)
        }
    }

    fun selectContest(contestId: String) {
        _selectedContestId.value = contestId
        viewModelScope.launch {
            val uid = _userId.value
            _syllabusGroups.value = repository.getContestSyllabus(contestId, uid)
            _contestDiff.value = repository.getContestDiff(contestId)
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
            _isSimuladoSession.value = (mode == QuizMode.SIMULADO)
            val questions = repository.fetchOrGetOfflineQuiz(
                userId = _userId.value,
                mode = mode,
                quantity = quantity,
                contestId = contestId ?: _selectedContestId.value,
                disciplina = disciplina
            )
            _activeQuizQuestions.value = questions
        }
    }

    fun submitAnswer(
        question: QuestionDto,
        chosenIndex: Int,
        confidenceLevel: Int
    ) {
        viewModelScope.launch {
            val correctIndex = question.correctAnswerIndices.firstOrNull() ?: 0
            val isCorrect = chosenIndex == correctIndex
            val chosenText = question.answers.getOrElse(chosenIndex) { "" }

            val attempt = PendingAttemptEntity(
                questionId = question.id,
                chosenIndex = chosenIndex,
                chosenText = chosenText,
                isCorrect = isCorrect,
                timeSpentSeconds = 60,
                confidenceLevel = confidenceLevel,
                sessionUuid = _currentSessionUuid.value,
                quizMode = if (_isSimuladoSession.value) "SIMULADO" else "STANDARD",
                examBlock = null,
                contestId = _selectedContestId.value,
                userNote = null
            )

            // Registra offline no Room e dispara sincronização em background
            repository.recordAttemptOfflineFirst(_userId.value, attempt)
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

    fun requestAiTutor(questionId: String, kind: String, title: String) {
        viewModelScope.launch {
            _activeAiSheetTitle.value = title
            val content = repository.requestAiTutor(questionId, kind)
            _activeAiSheetMarkdown.value = content
        }
    }

    fun dismissAiSheet() {
        _activeAiSheetMarkdown.value = null
        _activeAiSheetTitle.value = null
    }

    fun exitSession() {
        _activeQuizQuestions.value = null
        _isSimuladoSession.value = false
        _activeAiSheetMarkdown.value = null
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
    private val repository: ConcursoRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
