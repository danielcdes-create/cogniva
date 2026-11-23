package br.com.concursoprep.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.ConcursoApp
import br.com.concursoprep.app.data.local.PendingAttemptEntity
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.data.sync.OfflineSyncWorker
import br.com.concursoprep.app.data.update.AppUpdateManager
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.missions.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.util.UUID

sealed interface ApkInstallEvent {
    data object NeedsUnknownSourcesPermission : ApkInstallEvent
    data class ConfirmSessionInstall(val confirmationIntent: Intent) : ApkInstallEvent
}

data class AccountDeletionSwitch(
    val deletedUserId: Long,
    val nextGuestUserId: Long
)

class MainViewModel(
    private val repository: ConcursoRepository,
    private val appContext: Context? = null,
    private val savedStateHandle: SavedStateHandle? = null,
    overrideScope: CoroutineScope? = null
) : ViewModel() {

    private val vmScope: CoroutineScope = overrideScope ?: viewModelScope

    companion object {
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val KEY_ACTIVE_SESSION_INDEX = "active_session_index"
    }

    private val prefs=appContext?.getSharedPreferences("cogniva_prefs",Context.MODE_PRIVATE)
    private val _userId=MutableStateFlow(repository.ownerUserId);val userId=_userId.asStateFlow()
    private val _userProfile=MutableStateFlow(UserProfileDto(repository.ownerUserId,
        email=prefs?.getString("email",null),
        displayName=prefs?.getString("display_name","Estudante"),
        role=prefs?.getString("role","student")?:"student",
        isGuest=prefs?.getBoolean("is_guest",true)?:true,
        isAdmin=prefs?.getBoolean("is_admin",false)?:false,
        dailyQuestionsTarget=prefs?.getInt("daily_questions_target",50)?:50,
        dailyStudyHoursTarget=(prefs?.getFloat("daily_study_hours_target",2f)?:2f).toDouble(),
        activeContestIds=(prefs?.getString("active_contests","CPNU")?:"CPNU").split(',').filter { it.isNotBlank() },currentDailyStreak=0,streakFreezesAvailable=0));val userProfile=_userProfile.asStateFlow()
    private val _themeMode=MutableStateFlow(runCatching{AppThemeMode.valueOf(prefs?.getString("theme_mode","SYSTEM")?:"SYSTEM")}.getOrDefault(AppThemeMode.SYSTEM));val themeMode=_themeMode.asStateFlow()
    private val _isOnboardingCompleted=MutableStateFlow(prefs?.getBoolean("onboarding_completed",false)?:false);val isOnboardingCompleted=_isOnboardingCompleted.asStateFlow()
    private val _selectedContestId=MutableStateFlow(prefs?.getString("selected_contest_id","CPNU")?:"CPNU");val selectedContestId=_selectedContestId.asStateFlow()
    private val _storedExams=MutableStateFlow<List<StoredExam>>(emptyList());val storedExams=_storedExams.asStateFlow()
    private val _focusedTrunks=MutableStateFlow<Set<String>>(emptySet());val focusedTrunks=_focusedTrunks.asStateFlow()
    private val _syllabusViewMode=MutableStateFlow(SyllabusViewMode.CONTEST);val syllabusViewMode=_syllabusViewMode.asStateFlow()
    private val _showExamBuilder=MutableStateFlow(false);val showExamBuilder=_showExamBuilder.asStateFlow()
    private val _editingExam=MutableStateFlow<StoredExam?>(null);val editingExam=_editingExam.asStateFlow()
    private val _pausedDiscipline=MutableStateFlow<String?>(null);val pausedDiscipline=_pausedDiscipline.asStateFlow()
    private val _todayDashboard=MutableStateFlow<TodayDashboardDto?>(null);val todayDashboard=_todayDashboard.asStateFlow()
    private val _syllabusGroups=MutableStateFlow<List<DisciplineSyllabusGroupDto>>(emptyList());val syllabusGroups=_syllabusGroups.asStateFlow()
    private val _contestDiff=MutableStateFlow<ContestDiffScreenDto?>(null);val contestDiff=_contestDiff.asStateFlow()
    private val _analytics=MutableStateFlow<ProgressAnalyticsDto?>(null);val analytics=_analytics.asStateFlow()
    private val _activeQuizQuestions=MutableStateFlow<List<QuestionDto>?>(null);val activeQuizQuestions=_activeQuizQuestions.asStateFlow()
    private val _isSimuladoSession=MutableStateFlow(false);val isSimuladoSession=_isSimuladoSession.asStateFlow()
    private val _activeSession=MutableStateFlow<SessionSnapshot?>(null);val activeSession=_activeSession.asStateFlow()
    private val _activeAiSheetMarkdown=MutableStateFlow<String?>(null);val activeAiSheetMarkdown=_activeAiSheetMarkdown.asStateFlow()
    private val _activeAiSheetTitle=MutableStateFlow<String?>(null);val activeAiSheetTitle=_activeAiSheetTitle.asStateFlow()
    private val _contextualLookupState=MutableStateFlow<ContextualLookupUiState?>(null);val contextualLookupState=_contextualLookupState.asStateFlow()
    private val _cachedQuestionsCount=MutableStateFlow(0);val cachedQuestionsCount=_cachedQuestionsCount.asStateFlow()
    private val _pendingSyncCount=MutableStateFlow(0);val pendingSyncCount=_pendingSyncCount.asStateFlow()
    private val _userFeedbackMessage=MutableSharedFlow<String>(extraBufferCapacity=16);val userFeedbackMessage=_userFeedbackMessage.asSharedFlow()
    private val _accountChanged=MutableSharedFlow<Long>(extraBufferCapacity=1);val accountChanged=_accountChanged.asSharedFlow()
    private val _accountDeletedSwitch=MutableSharedFlow<AccountDeletionSwitch>(extraBufferCapacity=1);val accountDeletedSwitch=_accountDeletedSwitch.asSharedFlow()
    private val _isDeletingAccount=MutableStateFlow(false);val isDeletingAccount=_isDeletingAccount.asStateFlow()
    private val _accountAuthState=MutableStateFlow(AccountAuthUiState(isAuthenticated=!(prefs?.getBoolean("is_guest",true)?:true)));val accountAuthState=_accountAuthState.asStateFlow()
    private val _isStartingQuiz=MutableStateFlow(false);val isStartingQuiz=_isStartingQuiz.asStateFlow()
    private val _apkUpdateState=MutableStateFlow(ApkUpdateUiState(installedVersionCode=BuildConfig.VERSION_CODE,installedVersionName=BuildConfig.VERSION_NAME));val apkUpdateState=_apkUpdateState.asStateFlow()
    private val _apkInstallEvents=MutableSharedFlow<ApkInstallEvent>(extraBufferCapacity=4);val apkInstallEvents=_apkInstallEvents.asSharedFlow()
    private val _pendingReview=MutableStateFlow<List<PendingReviewItem>>(emptyList());val pendingReview=_pendingReview.asStateFlow()
    private val _showPendingReview=MutableStateFlow(false);val showPendingReview=_showPendingReview.asStateFlow()
    private val _pendingExport=MutableSharedFlow<File>();val pendingExport=_pendingExport.asSharedFlow()
    private val _missionsCatalog=MutableStateFlow<MissionsCatalogView?>(null);val missionsCatalog=_missionsCatalog.asStateFlow()
    private val _activeMissionDetail=MutableStateFlow<MissionDetailView?>(null);val activeMissionDetail=_activeMissionDetail.asStateFlow()
    private val _isMissionBusy=MutableStateFlow(false);val isMissionBusy=_isMissionBusy.asStateFlow()
    fun openPendingReview(){operation{_pendingReview.value=repository.pendingReview();_showPendingReview.value=true}}
    fun dismissPendingReview(){_showPendingReview.value=false}
    fun dismissPendingReviewItem(item: PendingReviewItem) {
        operation {
            repository.dismissQuarantinedItem(item.id)
            _pendingReview.value = repository.pendingReview()
            localStats()
            refreshAllData()
            _userFeedbackMessage.emit("Registro rejeitado descartado da fila local.")
        }
    }
    fun clearQuarantinedReviewItems() {
        operation {
            val removed = repository.clearQuarantinedAttempts()
            _pendingReview.value = repository.pendingReview()
            localStats()
            refreshAllData()
            _userFeedbackMessage.emit("$removed registro(s) rejeitado(s) descartado(s).")
        }
    }
    fun useServerExam(exam:StoredExam){operation {val context=appContext?:return@operation;val file=repository.preserveAndUseServerExam(exam,File(context.cacheDir,"preserved"));refreshAllData();_pendingExport.emit(file);_userFeedbackMessage.emit("Versão do servidor carregada. Sua edição anterior continua preservada e disponível para exportação.")}}
    fun exportPendingReview(){operation{val context=appContext?:return@operation;_pendingExport.emit(repository.exportPending(File(context.cacheDir,"preserved")))}}
    private var refreshJob:Job?=null;private var syllabusJob:Job?=null;private var startJob:Job?=null;private var aiJob:Job?=null;private var lookupJob:Job?=null;private var lookupAiJob:Job?=null;private var aiGeneration=0L
    private fun operation(block:suspend CoroutineScope.()->Unit)=vmScope.launch {
        try{block()}catch(e:CancellationException){throw e}catch(e:Exception){_userFeedbackMessage.emit(e.message?:"Não foi possível concluir. Seus registros foram preservados.")}
    }
    init {
        prefs?.getString("pending_feedback_message", null)?.takeIf { it.isNotBlank() }?.let { pendingMsg ->
            prefs.edit().remove("pending_feedback_message").apply()
            operation { _userFeedbackMessage.emit(pendingMsg) }
        }
        vmScope.launch {
            AppUpdateManager.statusEvents.collect { event ->
                when (event) {
                    is AppUpdateManager.InstallSessionEvent.PendingUserConfirmation -> {
                        _apkUpdateState.value = _apkUpdateState.value.copy(
                            statusMessage = "Confirme a instalação da atualização no sistema."
                        )
                        _apkInstallEvents.emit(ApkInstallEvent.ConfirmSessionInstall(event.confirmationIntent))
                    }
                    is AppUpdateManager.InstallSessionEvent.Succeeded -> {
                        _apkUpdateState.value = _apkUpdateState.value.copy(
                            hasUpdate = false,
                            statusMessage = "Atualização concluída com sucesso."
                        )
                        _userFeedbackMessage.emit("Atualização instalada com sucesso.")
                    }
                    is AppUpdateManager.InstallSessionEvent.Failed -> {
                        _apkUpdateState.value = _apkUpdateState.value.copy(statusMessage = event.message)
                        _userFeedbackMessage.emit(event.message)
                    }
                }
            }
        }
        refreshAllData()
        checkApkUpdate()
        appContext?.let { OfflineSyncWorker.enqueueNow(it) }
        operation { repository.observeSync().distinctUntilChanged().collect { refreshAllData() } }
    }
    private fun saveProfile(profile:UserProfileDto){
        check(prefs?.edit()?.putLong("user_id",profile.userId)
            ?.putString("email",profile.email)
            ?.putString("display_name",profile.displayName)
            ?.putString("role",profile.role)
            ?.putBoolean("is_guest",profile.isGuest)
            ?.putBoolean("is_admin",profile.isAdmin)
            ?.putInt("daily_questions_target",profile.dailyQuestionsTarget)
            ?.putFloat("daily_study_hours_target",profile.dailyStudyHoursTarget.toFloat())
            ?.putString("active_contests",profile.activeContestIds.joinToString(","))
            ?.putBoolean("onboarding_completed",true)?.commit()?:true)
    }
    fun completeOnboarding(displayName:String,selectedContests:List<String>,dailyQuestionsTarget:Int,dailyHoursTarget:Double,email:String?=null,password:String?=null,authMode:String="guest"){
        operation {
            val session=when(authMode){
                "login"->repository.loginWithEmail(email.orEmpty(),password.orEmpty())
                "register"->repository.registerAccount(email.orEmpty(),password.orEmpty(),displayName)
                else->repository.startGuestOrOfflineSession(displayName)
            }
            val targetRepo = if (session.profile.userId != repository.ownerUserId && appContext != null) {
                runCatching {
                    ConcursoApp.instance.selectAccount(session.profile.userId)
                    ConcursoApp.instance.repository
                }.getOrDefault(repository)
            } else {
                repository
            }
            val savedOnboarding=targetRepo.saveOnboarding(session.profile.userId,selectedContests,dailyQuestionsTarget,dailyHoursTarget,displayName)
            val profile=savedOnboarding.copy(email=session.profile.email?:savedOnboarding.email,role=session.profile.role,isGuest=session.profile.isGuest,isAdmin=session.profile.isAdmin)
            saveProfile(profile);_isOnboardingCompleted.value=true
            _accountAuthState.value=AccountAuthUiState(isAuthenticated=!profile.isGuest)
            if(profile.userId!=repository.ownerUserId){
                appContext?.let{OfflineSyncWorker.enqueueNow(it)}
                _accountChanged.emit(profile.userId)
                return@operation
            }
            _userProfile.value=profile;refreshAllData();appContext?.let{OfflineSyncWorker.enqueueNow(it)}
            _userFeedbackMessage.emit(if(session.token.isBlank())"Perfil salvo neste aparelho. A sincronização aguardará conexão." else "Perfil salvo. Sincronização agendada.")
        }
    }
    fun updateUserSettings(displayName:String,selectedContests:List<String>,dailyQuestionsTarget:Int,dailyHoursTarget:Double){
        if(_accountAuthState.value.isSubmitting)return
        operation {
            val saved=repository.saveOnboarding(repository.ownerUserId,selectedContests,dailyQuestionsTarget,dailyHoursTarget,displayName)
            val profile=saved.copy(email=_userProfile.value.email?:saved.email,role=_userProfile.value.role,isGuest=_userProfile.value.isGuest,isAdmin=_userProfile.value.isAdmin)
            saveProfile(profile);_userProfile.value=profile;refreshAllData();appContext?.let{OfflineSyncWorker.enqueueNow(it)}
            _userFeedbackMessage.emit("Preferências salvas neste aparelho. Confira o estado da sincronização em Hoje.")
        }
    }
    fun authenticateAccount(email:String,password:String,displayName:String?=null,isRegister:Boolean=false){
        if(_accountAuthState.value.isSubmitting)return
        val normalizedEmail=email.trim()
        if(!normalizedEmail.contains("@") || (isRegister && password.length<10) || (!isRegister && password.isBlank())){
            _accountAuthState.value=AccountAuthUiState(
                isAuthenticated=!_userProfile.value.isGuest,
                message=if(isRegister)"Informe um e-mail válido e senha com no mínimo 10 caracteres." else "Informe seu e-mail e sua senha.",
                isError=true
            )
            return
        }
        _accountAuthState.value=AccountAuthUiState(isSubmitting=true,isAuthenticated=!_userProfile.value.isGuest)
        vmScope.launch {
            try {
                val session=if(isRegister) repository.registerAccount(normalizedEmail,password,displayName?:_userProfile.value.displayName) else repository.loginWithEmail(normalizedEmail,password)
                saveProfile(session.profile);_isOnboardingCompleted.value=true
                if(session.profile.userId!=repository.ownerUserId){_accountChanged.emit(session.profile.userId);return@launch}
                _userProfile.value=session.profile;refreshAllData()
                appContext?.let{OfflineSyncWorker.enqueueNow(it)}
                _accountAuthState.value=AccountAuthUiState(
                    isAuthenticated=true,
                    message=if(isRegister)"Conta criada e vinculada ao seu progresso atual." else "Sessão autenticada. Sincronização agendada."
                )
            } catch(e:CancellationException){_accountAuthState.value=AccountAuthUiState(isAuthenticated=!_userProfile.value.isGuest);throw e}
            catch(e:Exception){
                val message=when(e){
                    is retrofit2.HttpException->AccountLinkMessages.fromHttp(e.code(), runCatching { e.response()?.errorBody()?.string().orEmpty() }.getOrDefault(""))
                    is java.io.IOException->AccountLinkMessages.OFFLINE
                    else->e.message?:"Não foi possível concluir a autenticação. Seus registros foram preservados."
                }
                _accountAuthState.value=AccountAuthUiState(isAuthenticated=!_userProfile.value.isGuest,message=message,isError=true)
            }
        }
    }
    fun logoutAccount(){
        if(_accountAuthState.value.isSubmitting || _isDeletingAccount.value)return
        operation {
            savedStateHandle?.remove<String>(KEY_ACTIVE_SESSION_ID)
            savedStateHandle?.remove<Int>(KEY_ACTIVE_SESSION_INDEX)
            val nextUserId=repository.logoutAccount()
            _accountAuthState.value=AccountAuthUiState(isAuthenticated=false,message="Sessão encerrada neste aparelho.")
            if(nextUserId!=repository.ownerUserId){_accountChanged.emit(nextUserId);return@operation}
            _userProfile.value=_userProfile.value.copy(email=null,role="student",isGuest=true,isAdmin=false)
            refreshAllData()
        }
    }
    fun deleteAccount(confirmText: String = ConcursoRepository.LGPD_CONFIRM_WORD) {
        if (_isDeletingAccount.value || _accountAuthState.value.isSubmitting) return
        val normalizedConfirm = confirmText.trim()
        if (normalizedConfirm != ConcursoRepository.LGPD_CONFIRM_WORD) {
            vmScope.launch {
                _userFeedbackMessage.emit("Digite EXCLUIR exatamente em letras maiúsculas para confirmar a exclusão.")
            }
            return
        }
        _isDeletingAccount.value = true
        vmScope.launch {
            try {
                val confirmationMsg = "Conta e dados pessoais excluídos conforme a LGPD (Lei 13.709/2018). O acervo compartilhado de questões foi mantido."
                val deletedUserId = repository.ownerUserId
                val nextGuestId = repository.deleteAccount(
                    confirm = normalizedConfirm,
                    postSwitchMessage = confirmationMsg
                )
                savedStateHandle?.remove<String>(KEY_ACTIVE_SESSION_ID)
                savedStateHandle?.remove<Int>(KEY_ACTIVE_SESSION_INDEX)
                _activeSession.value = null
                _activeQuizQuestions.value = null
                _isSimuladoSession.value = false
                _accountAuthState.value = AccountAuthUiState(
                    isAuthenticated = false,
                    message = confirmationMsg
                )
                if (appContext != null && nextGuestId != 0L) {
                    _accountDeletedSwitch.emit(AccountDeletionSwitch(deletedUserId, nextGuestId))
                } else {
                    _userProfile.value = UserProfileDto(
                        userId = if (nextGuestId != 0L) nextGuestId else deletedUserId,
                        email = null,
                        displayName = "Estudante",
                        role = "student",
                        isGuest = true,
                        isAdmin = false
                    )
                    refreshAllData()
                    _userFeedbackMessage.emit(confirmationMsg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = when (e) {
                    is retrofit2.HttpException -> "Não foi possível excluir a conta no servidor (HTTP ${e.code()}). Seus dados locais foram preservados."
                    is java.io.IOException -> "Sem conexão com o servidor para confirmar a exclusão definitiva. Tente novamente conectado."
                    else -> e.message ?: "Não foi possível concluir a exclusão da conta."
                }
                _userFeedbackMessage.emit(msg)
            } finally {
                _isDeletingAccount.value = false
            }
        }
    }
    fun adoptExamTemplate(examId:String){
        operation {
            val updated=repository.adoptExamTemplate(examId)
            refreshAllData()
            _userFeedbackMessage.emit("Template oficial adotado para \"${updated.shortName}\".")
        }
    }
    fun skipOnboarding(){_isOnboardingCompleted.value=true;prefs?.edit()?.putBoolean("onboarding_completed",true)?.apply();refreshAllData()}
    fun reopenOnboardingWizard(){_isOnboardingCompleted.value=false;prefs?.edit()?.putBoolean("onboarding_completed",false)?.apply()}
    fun setThemeMode(mode:AppThemeMode){_themeMode.value=mode;prefs?.edit()?.putString("theme_mode",mode.name)?.apply()}
    private suspend fun localStats(){val stats=repository.getLocalStorageStats();_cachedQuestionsCount.value=stats.first;_pendingSyncCount.value=stats.second}
    fun syncNow(){operation {try{val count=repository.syncPendingAttempts(repository.ownerUserId);_userFeedbackMessage.emit("$count registro(s) confirmado(s). Confira as pendências restantes.")}finally{refreshAllData()}}}
    fun clearOfflineQuestionCache(){operation {repository.clearLocalQuestionCache();localStats();_userFeedbackMessage.emit("Questões em cache removidas. Histórico e pendências preservados.")}}
    private val _pendingExamDeletions=MutableStateFlow<List<StoredExam>>(emptyList())
    val pendingExamDeletions:StateFlow<List<StoredExam>> = _pendingExamDeletions
    fun refreshAllData(){
        refreshJob?.cancel();refreshJob=operation {
            val uid=repository.ownerUserId
            val isGuest=prefs?.getBoolean("is_guest",_userProfile.value.isGuest)?:_userProfile.value.isGuest
            val profile=_userProfile.value.copy(
                email=prefs?.getString("email",_userProfile.value.email),
                displayName=prefs?.getString("display_name",_userProfile.value.displayName),
                role=prefs?.getString("role",_userProfile.value.role)?:_userProfile.value.role,
                isGuest=isGuest,
                isAdmin=prefs?.getBoolean("is_admin",_userProfile.value.isAdmin)?:_userProfile.value.isAdmin,
                dailyQuestionsTarget=prefs?.getInt("daily_questions_target",50)?:50,
                dailyStudyHoursTarget=(prefs?.getFloat("daily_study_hours_target",2f)?:2f).toDouble(),
                activeContestIds=(prefs?.getString("active_contests","CPNU")?:"CPNU").split(',').filter { it.isNotBlank() }
            )
            _userProfile.value=profile
            if(!_accountAuthState.value.isSubmitting && _accountAuthState.value.isAuthenticated != !isGuest){
                _accountAuthState.value=_accountAuthState.value.copy(isAuthenticated=!isGuest)
            }
            val allStored=repository.loadStoredExams(includeDeleted=true);_pendingExamDeletions.value=allStored.filter { it.lifecycleStatus=="EXCLUIDO" && it.syncStatus!="SYNCED" };val stored=allStored.filter { it.lifecycleStatus!="EXCLUIDO" };_storedExams.value=stored
            runCatching { _missionsCatalog.value = repository.loadMissionsCatalog() }.onFailure { if (it is CancellationException) throw it }
            val selected=PracticeScope.selected(stored,_selectedContestId.value)
            if(selected!=_selectedContestId.value){_selectedContestId.value=selected;prefs?.edit()?.putString("selected_contest_id",selected)?.apply()}
            _focusedTrunks.value=repository.focusedTrunkSlugs()
            val dashboard=repository.getTodayDashboard(uid,profile.dailyQuestionsTarget,profile.activeContestIds)
            _todayDashboard.value=overlayLadder(dashboard,stored,_focusedTrunks.value,repository.canonicalMasteryBySubject())
            _analytics.value=repository.getProgressAnalytics(uid)
            val nextAction=repository.fetchNextAction(uid,selected)
            _todayDashboard.value=_todayDashboard.value?.copy(nextAction=nextAction)
            _analytics.value=_analytics.value?.copy(
                calibrationCells=nextAction.calibrationCells,
                strategyGuidance=nextAction.strategyGuidance,
                fatigueReport=nextAction.fatigueReport,
                examGapSummary=nextAction.examGapSummary,
                threeSignalSummary=nextAction.threeSignalSummary,
                examScenarios=nextAction.examScenarios
            )
            if(_activeSession.value==null){
                val paused=repository.pausedSession()
                val savedActiveId=savedStateHandle?.get<String>(KEY_ACTIVE_SESSION_ID)
                if(paused!=null && savedActiveId!=null && paused.sessionId==savedActiveId){
                    val maxIdx=(paused.questions.size-1).coerceAtLeast(0)
                    val restoredIdx=(savedStateHandle.get<Int>(KEY_ACTIVE_SESSION_INDEX) ?: paused.currentIndex).coerceIn(0,maxIdx)
                    showSession(paused.copy(currentIndex=restoredIdx))
                } else {
                    _pausedDiscipline.value=paused?.questions?.firstOrNull()?.disciplina
                }
            }
            localStats();loadSyllabus(_selectedContestId.value)
        }
    }
    private fun overlayLadder(dashboard:TodayDashboardDto,exams:List<StoredExam>,focus:Set<String>,mastery:Map<String,Double>):TodayDashboardDto{
        val active=exams.filter { it.isActiveInLadder }.sortedBy { it.ladderPriority }
        val today=LocalDate.now(LearningProjection.studyZone)
        val cards=active.map { exam->UpcomingExamCard(exam.examId,exam.shortName,
            when(exam.lifecycleStatus){"EDITAL_PUBLICADO"->ContestStatus.EDITAL_PUBLICADO;"AUTORIZADO","BANCA_DEFINIDA"->ContestStatus.SOLICITADO_AUTORIZADO;"PROVA_REALIZADA"->ContestStatus.EM_ANDAMENTO;"ARQUIVADO"->ContestStatus.ENCERRADO;else->ContestStatus.PREVISTO},
            if(exam.dateType=="EXACT_DATE")ExamEngine.daysUntil(exam.exactDateIso,today)else null,exam.estimatedWindowLabel,exam.examBoards.joinToString(" / ").ifBlank{"A definir"},exam.dateType,exam.exactDateIso) }
        val battery=OfflineExamQueue.commonTrunkFromCache(exams=active,cachedQuestions=emptyList(),masteryBySubject=mastery,today=today,suggestedQuestions=15,focusedTrunkSlugs=focus)
        return dashboard.copy(upcomingExams=cards,commonTrunkSuggestion=battery?.let{CommonTrunkSuggestion(it.card.subjectName,it.card.contestNames,it.card.sentence)}?:CommonTrunkSuggestion("",emptyList(),""))
    }
    private fun loadSyllabus(id:String){syllabusJob?.cancel();syllabusJob=operation {val groups=repository.getContestSyllabus(id,repository.ownerUserId);val diff=repository.getContestDiff(id);if(_selectedContestId.value==id){_syllabusGroups.value=groups;_contestDiff.value=diff}}}
    fun selectContest(id:String){_selectedContestId.value=id;prefs?.edit()?.putString("selected_contest_id",id)?.apply();loadSyllabus(id)}
    fun setSyllabusViewMode(mode:SyllabusViewMode){_syllabusViewMode.value=mode}
    fun toggleTrunkFocus(slug:String){operation {repository.setTrunkFocus(slug,slug !in _focusedTrunks.value);refreshAllData()}}
    fun openExamBuilder(){_editingExam.value=null;_showExamBuilder.value=true}
    fun editExam(exam:StoredExam){_editingExam.value=exam;_showExamBuilder.value=true}
    fun dismissExamBuilder(){_showExamBuilder.value=false;_editingExam.value=null}
    fun saveExamDraft(draft:ExamDraft){operation {
        val previous=_editingExam.value
        val activeInLadder=draft.isActiveInLadder && draft.lifecycleStatus!="ARQUIVADO"
        val created=if(previous==null)repository.createExamOffline(draft.copy(isActiveInLadder=activeInLadder))else{
            val next=OfflineExamQueue.createPendingExam(draft).copy(
                examId=previous.examId,
                revision=previous.revision,
                isActiveInLadder=activeInLadder,
                wrongAnswerPenalty=draft.wrongAnswerPenalty,
                templateSourceId=previous.templateSourceId,
                templateRevisionSynced=previous.templateRevisionSynced,
                isCustomized=true,
                upstreamUpdateAvailable=previous.upstreamUpdateAvailable,
                syncStatus=if(previous.syncStatus=="PENDING_CREATE")"PENDING_CREATE"else"PENDING_UPDATE"
            )
            repository.editExamOffline(next);next
        }
        _selectedContestId.value=created.examId;_showExamBuilder.value=false;_editingExam.value=null
        val profile=_userProfile.value.copy(activeContestIds=if(!created.isActiveInLadder || created.lifecycleStatus=="ARQUIVADO")_userProfile.value.activeContestIds-created.examId else (_userProfile.value.activeContestIds+created.examId).distinct());saveProfile(profile);_userProfile.value=profile
        repository.saveOnboarding(profile.userId,profile.activeContestIds,profile.dailyQuestionsTarget,profile.dailyStudyHoursTarget,profile.displayName)
        refreshAllData();appContext?.let{OfflineSyncWorker.enqueueNow(it)};_userFeedbackMessage.emit("Concurso salvo neste aparelho. A confirmação do servidor aparecerá após sincronizar.")
    }}
    fun archiveExam(exam:StoredExam)=setExamLifecycle(exam,"ARQUIVADO","Concurso \"${exam.shortName}\" arquivado.")
    fun restoreExam(exam:StoredExam)=setExamLifecycle(exam,"RUMOR_PREVISTO","Concurso \"${exam.shortName}\" restaurado.")
    fun deleteExam(exam:StoredExam)=setExamLifecycle(exam,"EXCLUIDO","Concurso \"${exam.shortName}\" excluído da sua lista.")
    private fun setExamLifecycle(exam:StoredExam,status:String,feedbackMessage:String?=null){operation {
        if(_editingExam.value?.examId==exam.examId){_showExamBuilder.value=false;_editingExam.value=null}
        repository.editExamOffline(exam.copy(lifecycleStatus=status,isActiveInLadder=status!="ARQUIVADO"&&status!="EXCLUIDO"))
        val profile=_userProfile.value.copy(activeContestIds=if(status=="RUMOR_PREVISTO")(_userProfile.value.activeContestIds+exam.examId).distinct()else _userProfile.value.activeContestIds-exam.examId)
        saveProfile(profile);_userProfile.value=profile
        repository.saveOnboarding(profile.userId,profile.activeContestIds,profile.dailyQuestionsTarget,profile.dailyStudyHoursTarget,profile.displayName)
        refreshAllData();appContext?.let{OfflineSyncWorker.enqueueNow(it)}
        feedbackMessage?.let { _userFeedbackMessage.emit(it) }
    }}
    fun applyEditalDiffUpdate(id:String){operation {val before=_storedExams.value.find{it.examId==id}?.revision;repository.syncPendingAttempts(repository.ownerUserId);refreshAllData();_userFeedbackMessage.emit(if(repository.loadStoredExams().find{it.examId==id}?.revision!=before)"Nova revisão de edital salva. Histórico preservado."else"Nenhuma revisão nova de edital foi publicada para este concurso.")}}
    fun recordEntryImpression() {
        operation {
            repository.recordEntryScreenImpression(repository.ownerUserId)
        }
    }

    fun chooseConsciousRestToday(rested: Boolean = true) {
        operation {
            val metrics = repository.setConsciousRestToday(repository.ownerUserId, rested)
            _todayDashboard.value = _todayDashboard.value?.copy(entryMetrics = metrics)
            _analytics.value = _analytics.value?.copy(entryMetrics = metrics)
            if (rested) {
                _userFeedbackMessage.emit("Sem estudar hoje registrado. Nenhuma punição ou cobrança — o acesso continua livre se mudar de ideia.")
            }
        }
    }

    fun startSmartEntryQuestion(excludeQuestionIds: Set<String> = emptySet()){
        startJob?.cancel()
        val cid=_selectedContestId.value.takeIf { it.isNotBlank() }
        val nextAct=_todayDashboard.value?.nextAction
        startJob=operation {
            _isStartingQuiz.value=true
            try {
                if (excludeQuestionIds.isEmpty()) {
                    repository.recordEntryVoluntaryStart(repository.ownerUserId)
                }
                val preloaded=nextAct?.entryQuestion
                    ?.takeIf { it.id !in excludeQuestionIds }
                    ?.let { listOf(it) }
                    .orEmpty()
                val questions=preloaded.ifEmpty {
                    val fetched=runCatching {
                        repository.fetchOrGetOfflineQuiz(
                            repository.ownerUserId,
                            QuizMode.ENTRY_60S,
                            4,
                            cid,
                            syllabusTopicId=nextAct?.priorityTopicId
                        )
                    }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList()).filter { it.id !in excludeQuestionIds }
                    fetched.ifEmpty {
                        repository.fetchOrGetOfflineQuiz(repository.ownerUserId,QuizMode.ENTRY_60S,4,null)
                            .filter { it.id !in excludeQuestionIds }
                            .ifEmpty {
                                repository.fetchOrGetOfflineQuiz(repository.ownerUserId,QuizMode.ADAPTIVE_LOOP,1,null)
                            }
                    }
                }
                if(questions.isEmpty()){
                    _userFeedbackMessage.emit("Nenhuma questão disponível agora. Conecte-se para sincronizar o acervo.")
                    return@operation
                }
                val exam=repository.loadStoredExams().find { it.examId==cid }
                val session=SessionSnapshot(
                    sessionId=UUID.randomUUID().toString(),
                    ownerUserId=repository.ownerUserId,
                    questions=questions.take(1),
                    mode=QuizMode.ENTRY_60S,
                    exam=exam,
                    deadlineEpochMs=null,
                    isSmartEntry=true
                )
                repository.createSession(session)
                showSession(session)
            } finally {
                _isStartingQuiz.value=false
            }
        }
    }

    fun resolveAnotherSmartEntryQuestion(){
        operation {
            val current=_activeSession.value
            val prevIds=current?.questions?.map { it.id }?.toSet().orEmpty()
            if(current!=null){
                withContext(NonCancellable){
                    repository.withMutationLock {
                        repository.saveSession(current)
                        repository.completeSession(current.sessionId)
                    }
                }
            }
            startSmartEntryQuestion(excludeQuestionIds=prevIds)
        }
    }

    fun continueAdaptiveRouteFromEntry(){
        operation {
            val current=_activeSession.value
            if(current!=null){
                withContext(NonCancellable){
                    repository.withMutationLock {
                        repository.saveSession(current)
                        repository.completeSession(current.sessionId)
                    }
                }
            }
            startQuizSession(
                mode=QuizMode.ADAPTIVE_LOOP,
                quantity=10,
                contestId=_selectedContestId.value.takeIf { it.isNotBlank() }
            )
        }
    }

    fun startQuizSession(mode:QuizMode,quantity:Int,contestId:String?=null,disciplina:String?=null,componentId:String?=null,syllabusTopicId:String?=null){
        startJob?.cancel()
        val id=PracticeScope.contest(mode,if(mode in listOf(QuizMode.SIMULADO,QuizMode.ADAPTIVE_LOOP,QuizMode.STRATEGY_BATTERY,QuizMode.ENTRY_60S,QuizMode.TRANSFER_PROBE))contestId?:_selectedContestId.value else contestId)
        startJob=operation {
            _isStartingQuiz.value=true
            try{
                val questions=repository.fetchOrGetOfflineQuiz(repository.ownerUserId,mode,quantity,id,disciplina,componentId,syllabusTopicId)
                if(questions.isEmpty()){
                    _userFeedbackMessage.emit(when(mode){
                        QuizMode.SRS_REVIEW->"Nenhuma revisão válida disponível agora. Use o Shuffle Geral para praticar o acervo."
                        QuizMode.ERROR_NOTEBOOK->"Nenhuma questão válida disponível no caderno de erros. O histórico continua preservado."
                        QuizMode.MOST_BADASS->"Nenhuma questão válida do seu histórico atende ao desafio agora. Use o Shuffle Geral."
                        QuizMode.COMMON_TRUNK->"Nenhuma questão disponível para o tronco das grades ativas. Confira os vínculos ou conecte-se para atualizar o cache."
                        QuizMode.TRANSFER_PROBE->"Transferência não mensurada: ainda não há questões humanas inéditas de outra banca neste filtro."
                        else->if(disciplina!=null)"Não foi possível obter questões de $disciplina neste filtro. O Shuffle Geral acessa outras matérias disponíveis." else "Nenhuma questão disponível neste filtro. Confira a conexão e o acervo em cache."
                    });return@operation
                }
                val exam=repository.loadStoredExams().find{it.examId==id}
                val deadlineMs=when(mode){
                    QuizMode.SIMULADO->System.currentTimeMillis()+questions.size*180000L
                    else->null
                }
                val session=SessionSnapshot(UUID.randomUUID().toString(),repository.ownerUserId,questions,mode,exam,deadlineMs)
                repository.createSession(session);showSession(session)
            }finally{_isStartingQuiz.value=false}
        }
    }
    private fun showSession(session:SessionSnapshot){
        dismissAiSheet()
        dismissContextualLookup()
        savedStateHandle?.set(KEY_ACTIVE_SESSION_ID,session.sessionId)
        savedStateHandle?.set(KEY_ACTIVE_SESSION_INDEX,session.currentIndex)
        _activeSession.value=session
        _activeQuizQuestions.value=session.questions
        _isSimuladoSession.value=session.mode == QuizMode.SIMULADO
        _pausedDiscipline.value=session.questions.firstOrNull()?.disciplina
    }
    fun resumeSession(){operation {repository.pausedSession()?.let(::showSession)?:_userFeedbackMessage.emit("Não há sessão pausada neste aparelho.")}}
    fun updateSession(incoming:SessionSnapshot){
        if(_activeSession.value?.sessionId==incoming.sessionId){
            savedStateHandle?.set(KEY_ACTIVE_SESSION_ID,incoming.sessionId)
            savedStateHandle?.set(KEY_ACTIVE_SESSION_INDEX,incoming.currentIndex)
        }
        operation {
            withContext(NonCancellable) {
                repository.withMutationLock {
                    val current=_activeSession.value?:return@withMutationLock
                    if(current.sessionId!=incoming.sessionId)return@withMutationLock
                    val answers=incoming.answers.toMutableMap();current.answers.filterValues { it.submitted }.forEach{(id,answer)->answers[id]=answer}
                    val next=incoming.copy(revision=current.revision+1,answers=answers);_activeSession.value=next
                    repository.saveSession(next)
                }
            }
        }
    }
    fun selectEvidenceForQuestion(questionId: String, evidenceId: String) {
        operation {
            val session = _activeSession.value ?: return@operation
            repository.recordEvidenceSelection(session.ownerUserId, session.sessionId, questionId, evidenceId)
            val prev = session.answers[questionId]
            if (prev != null) {
                val next = session.copy(
                    answers = session.answers + (questionId to prev.copy(selectedEvidenceId = evidenceId)),
                    revision = session.revision + 1
                )
                _activeSession.value = next
                repository.saveSession(next)
            }
            appContext?.let { OfflineSyncWorker.enqueueNow(it) }
        }
    }

    suspend fun submitAnswer(
        question: QuestionDto,
        chosenIndex: Int,
        confidenceLevel: Int,
        timeSpentSeconds: Int = 60,
        isBlank: Boolean = false,
        confidenceExplicit: Boolean = false,
        selectedEvidenceId: String? = null
    ): Boolean {
        return try {
            require(question.correctAnswerIndices.size == 1 && (isBlank || chosenIndex in question.answers.indices))
            var completedFirstEntryQuestion = false
            val saved = repository.withMutationLock {
                val session = _activeSession.value ?: return@withMutationLock false
                val isFirstSubmittedInSession = session.answers.none { it.value.submitted }
                val pos = session.answers.count { it.value.submitted } + 1
                val sessionElapsed = ((System.currentTimeMillis() - session.sessionStartedAtEpochMs) / 1000L).toInt().coerceAtLeast(1)
                val safeIndex = if (isBlank) -1 else chosenIndex
                val safeText = if (isBlank) "" else question.answers[chosenIndex]
                val safeCorrect = !isBlank && chosenIndex in question.correctAnswerIndices
                val attempt = PendingAttemptEntity(
                    questionId = question.id,
                    chosenIndex = safeIndex,
                    chosenText = safeText,
                    isCorrect = safeCorrect,
                    timeSpentSeconds = timeSpentSeconds.coerceIn(1, 86400),
                    confidenceLevel = confidenceLevel,
                    sessionUuid = session.sessionId,
                    quizMode = session.mode.name,
                    examBlock = null,
                    contestId = session.exam?.examId,
                    userNote = null,
                    canonicalSubjectId = question.canonicalSubjectId,
                    canonicalTopicId = question.canonicalTopicId,
                    ownerUserId = session.ownerUserId,
                    attemptId = ConcursoRepository.deterministicAttemptId(session.sessionId, question.id),
                    questionRevision = question.questionRevision,
                    examComponentId = question.examComponentId,
                    isBlank = isBlank,
                    confidenceExplicit = confidenceExplicit,
                    selectedEvidenceId = selectedEvidenceId,
                    positionInSession = pos,
                    elapsedSessionSeconds = sessionElapsed,
                    sessionInterrupted = session.interrupted
                )
                val next = session.copy(
                    answers = session.answers + (
                        question.id to SessionAnswer(
                            chosenIndex = safeIndex,
                            confidenceLevel = confidenceLevel,
                            timeSpentSeconds = timeSpentSeconds,
                            submitted = true,
                            isBlank = isBlank,
                            confidenceExplicit = confidenceExplicit,
                            selectedEvidenceId = selectedEvidenceId
                        )
                    ),
                    revision = session.revision + 1
                )
                repository.recordAttemptUnlocked(session.ownerUserId, attempt, next)
                if (_activeSession.value?.sessionId == session.sessionId) _activeSession.value = next
                if (session.isSmartEntry && isFirstSubmittedInSession) {
                    completedFirstEntryQuestion = true
                }
                true
            }
            if (!saved) return false
            if (completedFirstEntryQuestion) {
                repository.recordEntryFirstQuestionCompleted(repository.ownerUserId)
            }
            localStats(); appContext?.let { OfflineSyncWorker.enqueueNow(it) }; true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _userFeedbackMessage.emit(e.message ?: "A resposta não foi salva. Tente novamente antes de sair.")
            false
        }
    }

    /**
     * Registra todo o lote final de respostas de um Simulado em uma única transação Room (`@Transaction`)
     * sobre `viewModelScope` + `NonCancellable`, impedindo perda parcial caso a tela navegue/feche imediatamente.
     */
    suspend fun submitSimuladoBatch(answersSnapshot: Map<String, SessionAnswer>): Boolean {
        val deferred = vmScope.async {
            withContext(NonCancellable) {
                try {
                    val saved = repository.withMutationLock {
                        val session = _activeSession.value ?: return@withMutationLock false
                        val questionsById = session.questions.associateBy { it.id }
                        val mergedAnswers = session.answers.toMutableMap()
                        val attemptsToRecord = mutableListOf<PendingAttemptEntity>()
                        val sessionElapsed = ((System.currentTimeMillis() - session.sessionStartedAtEpochMs) / 1000L).toInt().coerceAtLeast(1)
                        var orderIdx = 0
                        for ((qId, ans) in answersSnapshot) {
                            val question = questionsById[qId] ?: continue
                            if (question.correctAnswerIndices.size != 1) continue
                            if (!ans.isBlank && ans.chosenIndex !in question.answers.indices) continue
                            orderIdx += 1
                            val safeTime = ans.timeSpentSeconds.coerceIn(1, 86400)
                            if (mergedAnswers[qId]?.submitted != true) {
                                attemptsToRecord += PendingAttemptEntity(
                                    questionId = question.id,
                                    chosenIndex = if (ans.isBlank) -1 else ans.chosenIndex,
                                    chosenText = if (ans.isBlank) "" else question.answers[ans.chosenIndex],
                                    isCorrect = !ans.isBlank && ans.chosenIndex in question.correctAnswerIndices,
                                    timeSpentSeconds = safeTime,
                                    confidenceLevel = ans.confidenceLevel,
                                    sessionUuid = session.sessionId,
                                    quizMode = session.mode.name,
                                    examBlock = null,
                                    contestId = session.exam?.examId,
                                    userNote = null,
                                    canonicalSubjectId = question.canonicalSubjectId,
                                    canonicalTopicId = question.canonicalTopicId,
                                    ownerUserId = session.ownerUserId,
                                    attemptId = ConcursoRepository.deterministicAttemptId(session.sessionId, question.id),
                                    questionRevision = question.questionRevision,
                                    examComponentId = question.examComponentId,
                                    isBlank = ans.isBlank,
                                    confidenceExplicit = ans.confidenceExplicit,
                                    selectedEvidenceId = ans.selectedEvidenceId,
                                    positionInSession = session.questions.indexOfFirst { it.id == qId }.takeIf { it >= 0 }?.plus(1) ?: orderIdx,
                                    elapsedSessionSeconds = sessionElapsed,
                                    sessionInterrupted = session.interrupted
                                )
                            }
                            mergedAnswers[qId] = ans.copy(timeSpentSeconds = safeTime, submitted = true)
                        }
                        val nextSession = session.copy(
                            answers = mergedAnswers,
                            finished = true,
                            revision = session.revision + 1
                        )
                        repository.recordSimuladoBatchUnlocked(session.ownerUserId, attemptsToRecord, nextSession)
                        if (_activeSession.value?.sessionId == session.sessionId) {
                            _activeSession.value = nextSession
                        }
                        true
                    }
                    if (saved) {
                        localStats()
                        appContext?.let { OfflineSyncWorker.enqueueNow(it) }
                    }
                    saved
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _userFeedbackMessage.emit(
                        e.message ?: "Não foi possível salvar todas as respostas do simulado. Tente novamente."
                    )
                    false
                }
            }
        }
        return deferred.await()
    }

    fun exitSession(completed:Boolean=false){operation {
        savedStateHandle?.remove<String>(KEY_ACTIVE_SESSION_ID)
        savedStateHandle?.remove<Int>(KEY_ACTIVE_SESSION_INDEX)
        val closed=withContext(NonCancellable){
            repository.withMutationLock {
                val current=_activeSession.value?:return@withMutationLock false
                val savedSession=if(!completed && !current.finished && current.mode==QuizMode.SIMULADO){
                    current.copy(interrupted=true,revision=current.revision+1)
                }else{
                    current
                }
                repository.saveSession(savedSession)
                if(completed)repository.completeSession(savedSession.sessionId)
                _activeSession.value=null;_activeQuizQuestions.value=null;_isSimuladoSession.value=false
                true
            }
        }
        if(!closed)return@operation
        dismissAiSheet();dismissContextualLookup();refreshAllData()
    }}
    fun reportProblem(id:String,category:String){operation {check(repository.reportQuestionProblem(repository.ownerUserId,id,category)){"O reporte não pôde ser salvo; tente novamente."};appContext?.let{OfflineSyncWorker.enqueueNow(it)};localStats();_userFeedbackMessage.emit("Reporte salvo neste aparelho e aguardando confirmação de envio.")}}
    fun requestAiTutor(id:String,kind:String,title:String,hintIndex:Int?=null){
        aiJob?.cancel();val generation=++aiGeneration;_activeAiSheetTitle.value=title;_activeAiSheetMarkdown.value="Consultando tutor…"
        aiJob=operation {val text=repository.requestAiTutor(id,kind,hintIndex);if(aiGeneration==generation&&_activeQuizQuestions.value?.any{it.id==id}==true)_activeAiSheetMarkdown.value=text}
    }
    fun dismissAiSheet(){aiGeneration++;aiJob?.cancel();_activeAiSheetMarkdown.value=null;_activeAiSheetTitle.value=null}

    fun openContextualLookup(question: QuestionDto, rawSelectedText: String) {
        val cleaned = rawSelectedText.trim().replace(Regex("\\s+"), " ").take(240)
        if (cleaned.isEmpty()) return
        lookupJob?.cancel()
        lookupAiJob?.cancel()
        _contextualLookupState.value = ContextualLookupUiState(
            questionId = question.id,
            selectedText = cleaned,
            isLoading = true
        )
        lookupJob = operation {
            val result = repository.lookupQuestionContext(question, cleaned)
            val cur = _contextualLookupState.value
            if (cur != null && cur.questionId == question.id && cur.selectedText == cleaned) {
                _contextualLookupState.value = cur.copy(isLoading = false, response = result)
            }
        }
    }

    fun requestContextualLookupAi(questionId: String, selectedText: String, isAnswerSubmitted: Boolean) {
        val cleaned = selectedText.trim().replace(Regex("\\s+"), " ").take(240)
        if (cleaned.isEmpty()) return
        lookupAiJob?.cancel()
        _contextualLookupState.value = _contextualLookupState.value?.copy(
            selectedText = cleaned,
            isAiLoading = true,
            aiExplanationMarkdown = null
        )
        lookupAiJob = operation {
            val md = repository.requestAiTermLookup(questionId, cleaned, isAnswerSubmitted)
            val cur = _contextualLookupState.value
            if (cur != null && cur.questionId == questionId && cur.selectedText == cleaned) {
                _contextualLookupState.value = cur.copy(isAiLoading = false, aiExplanationMarkdown = md)
            }
        }
    }

    fun dismissContextualLookup() {
        lookupJob?.cancel()
        lookupAiJob?.cancel()
        _contextualLookupState.value = null
    }
    fun checkApkUpdate(){operation {
        _apkUpdateState.value=_apkUpdateState.value.copy(isChecking=true)
        try{val remote=repository.fetchRemoteApkInfo();_apkUpdateState.value=_apkUpdateState.value.copy(remoteInfo=remote,hasUpdate=remote!=null&&remote.versionCode>BuildConfig.VERSION_CODE,statusMessage=when{remote==null->"Não foi possível verificar a versão agora.";remote.versionCode>BuildConfig.VERSION_CODE->"Atualização disponível: ${remote.versionName}";else->"Versão instalada atualizada."})}finally{_apkUpdateState.value=_apkUpdateState.value.copy(isChecking=false)}
    }}
    fun downloadAndInstallApk(context:Context){operation {
        if(_apkUpdateState.value.isDownloading)return@operation
        if(Build.VERSION.SDK_INT>=26&&!context.packageManager.canRequestPackageInstalls()){_apkInstallEvents.emit(ApkInstallEvent.NeedsUnknownSourcesPermission);return@operation}
        _apkUpdateState.value=_apkUpdateState.value.copy(isDownloading=true,statusMessage="Baixando e verificando a atualização (SHA-256)…")
        try{
            val info=_apkUpdateState.value.remoteInfo ?: repository.fetchRemoteApkInfo()
                ?: error("Metadados da atualização indisponíveis. Toque em 'Verificar Versão' e tente novamente.")
            AppUpdateManager.validateMetadata(info, BuildConfig.VERSION_CODE)
            _apkUpdateState.value=_apkUpdateState.value.copy(remoteInfo=info)
            AppUpdateManager.downloadVerifyAndStageInstall(
                context = context,
                info = info,
                apiBaseUrl = BuildConfig.API_BASE_URL
            )
            _apkUpdateState.value=_apkUpdateState.value.copy(statusMessage="Pacote verificado e enviado ao instalador do sistema…")
        }catch(e:CancellationException){throw e}catch(e:Exception){
            val msg=e.message?.takeIf{it.isNotBlank()}?:"Não foi possível baixar ou validar a atualização. Tente novamente."
            _apkUpdateState.value=_apkUpdateState.value.copy(statusMessage=msg)
            throw IllegalStateException(msg,e)
        }finally{_apkUpdateState.value=_apkUpdateState.value.copy(isDownloading=false)}
    }}

    // --- Ações do Módulo Missões ---

    fun refreshMissionsCatalog() {
        operation {
            _missionsCatalog.value = repository.loadMissionsCatalog()
        }
    }

    fun openMission(missionId: String, resume: Boolean = true) {
        operation {
            _isMissionBusy.value = true
            try {
                var detail = repository.openMissionDetail(missionId, resume = resume)
                if (detail.narrativeProgress.status == "NOT_STARTED" && detail.scenes.isNotEmpty()) {
                    detail = repository.updateMissionProgressOfflineFirst(
                        missionId = missionId,
                        currentSceneId = detail.scenes.first().sceneId,
                    )
                }
                _activeMissionDetail.value = detail
                _missionsCatalog.value = repository.loadMissionsCatalog()
            } finally {
                _isMissionBusy.value = false
            }
        }
    }

    fun exitActiveMission(currentSceneId: String? = null, playbackPositionMs: Long? = null) {
        val current = _activeMissionDetail.value
        _activeMissionDetail.value = null
        if (current != null && currentSceneId != null) {
            operation {
                repository.updateMissionProgressOfflineFirst(
                    missionId = current.missionId,
                    currentSceneId = currentSceneId,
                    playbackPositionMs = playbackPositionMs,
                )
                _missionsCatalog.value = repository.loadMissionsCatalog()
            }
        }
    }

    fun saveMissionPlaybackPosition(missionId: String, currentSceneId: String, playbackPositionMs: Long) {
        if (currentSceneId.isBlank()) return
        operation {
            repository.updateMissionProgressOfflineFirst(
                missionId = missionId,
                currentSceneId = currentSceneId,
                playbackPositionMs = playbackPositionMs,
            )
        }
    }

    fun advanceMissionScene(
        missionId: String,
        completedSceneId: String,
        nextSceneId: String?,
        viaTranscript: Boolean = false,
    ) {
        operation {
            _isMissionBusy.value = true
            try {
                val updated = repository.updateMissionProgressOfflineFirst(
                    missionId = missionId,
                    currentSceneId = nextSceneId ?: completedSceneId,
                    completedSceneId = completedSceneId,
                    markMissionCompleted = nextSceneId == null,
                    usedTranscriptFallback = viaTranscript,
                )
                _activeMissionDetail.value = updated
                _missionsCatalog.value = repository.loadMissionsCatalog()
            } finally {
                _isMissionBusy.value = false
            }
        }
    }

    fun jumpToMissionScene(missionId: String, targetSceneId: String) {
        operation {
            val updated = repository.updateMissionProgressOfflineFirst(
                missionId = missionId,
                currentSceneId = targetSceneId,
            )
            _activeMissionDetail.value = updated
        }
    }

    fun submitMissionDecision(
        missionId: String,
        sceneId: String,
        interactionId: String,
        selectedOptionId: String,
        timeSpentMs: Long,
        viaTranscript: Boolean = false,
    ) {
        operation {
            _isMissionBusy.value = true
            try {
                val updated = repository.submitMissionDecisionOfflineFirst(
                    missionId = missionId,
                    sceneId = sceneId,
                    interactionId = interactionId,
                    selectedOptionId = selectedOptionId,
                    timeSpentMs = timeSpentMs,
                    usedTranscriptFallback = viaTranscript,
                )
                _activeMissionDetail.value = updated
                _missionsCatalog.value = repository.loadMissionsCatalog()
            } finally {
                _isMissionBusy.value = false
            }
        }
    }

    fun repeatMission(missionId: String) {
        operation {
            _isMissionBusy.value = true
            try {
                val updated = repository.repeatMissionOfflineFirst(missionId)
                _activeMissionDetail.value = updated
                _missionsCatalog.value = repository.loadMissionsCatalog()
            } finally {
                _isMissionBusy.value = false
            }
        }
    }

    suspend fun resolveMissionVideoFile(
        cacheDir: File,
        assetId: String,
        expectedSha256: String?,
    ): File? = repository.ensureMissionVideoCached(cacheDir, assetId, expectedSha256)

    fun preloadMissionVideoInBackground(cacheDir: File, assetId: String?, expectedSha256: String?) {
        if (assetId.isNullOrBlank()) return
        vmScope.launch(Dispatchers.IO) {
            runCatching { repository.ensureMissionVideoCached(cacheDir, assetId, expectedSha256) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    fun startMissionRelatedPractice(missionId: String, contestTemplateId: String) {
        operation {
            _isMissionBusy.value = true
            try {
                val questions = repository.fetchMissionRelatedHumanQuestions(missionId, quantity = 5)
                if (questions.isEmpty()) {
                    _userFeedbackMessage.emit(
                        "Nenhuma questão humana validada disponível neste ambiente para os subtópicos desta missão."
                    )
                    return@operation
                }
                val exam = repository.loadStoredExams().find { it.examId == contestTemplateId || it.templateSourceId == contestTemplateId }
                val session = SessionSnapshot(
                    sessionId = UUID.randomUUID().toString(),
                    ownerUserId = repository.ownerUserId,
                    questions = questions,
                    mode = QuizMode.STANDARD,
                    exam = exam,
                    deadlineEpochMs = null,
                    isSmartEntry = false,
                )
                repository.createSession(session)
                _activeMissionDetail.value = null
                showSession(session)
            } finally {
                _isMissionBusy.value = false
            }
        }
    }
}
class MainViewModelFactory(
    private val repository: ConcursoRepository,
    private val appContext: Context? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(repository, appContext, null) as T
    }

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java))
        val handle = runCatching { extras.createSavedStateHandle() }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(repository, appContext, handle) as T
    }
}
