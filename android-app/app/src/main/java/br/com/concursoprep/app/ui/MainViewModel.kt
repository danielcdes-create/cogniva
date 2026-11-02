package br.com.concursoprep.app.ui

import android.content.Context
import android.os.Build
import androidx.lifecycle.*
import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.ConcursoApp
import br.com.concursoprep.app.data.local.PendingAttemptEntity
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.data.sync.OfflineSyncWorker
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.util.UUID

sealed interface ApkInstallEvent {
    data object NeedsUnknownSourcesPermission: ApkInstallEvent
    data class ReadyToInstall(val apkFile:File): ApkInstallEvent
}
class MainViewModel(private val repository:ConcursoRepository,private val appContext:Context?=null):ViewModel(){
    private val prefs=appContext?.getSharedPreferences("cogniva_prefs",Context.MODE_PRIVATE)
    private val _userId=MutableStateFlow(repository.ownerUserId);val userId=_userId.asStateFlow()
    private val _userProfile=MutableStateFlow(UserProfileDto(repository.ownerUserId,
        username=prefs?.getString("username",null),displayName=prefs?.getString("display_name","Concurseiro"),
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
    private val _cachedQuestionsCount=MutableStateFlow(0);val cachedQuestionsCount=_cachedQuestionsCount.asStateFlow()
    private val _pendingSyncCount=MutableStateFlow(0);val pendingSyncCount=_pendingSyncCount.asStateFlow()
    private val _userFeedbackMessage=MutableSharedFlow<String>(extraBufferCapacity=16);val userFeedbackMessage=_userFeedbackMessage.asSharedFlow()
    private val _accountChanged=MutableSharedFlow<Long>(extraBufferCapacity=1);val accountChanged=_accountChanged.asSharedFlow()
    private val _accountLinkState=MutableStateFlow(AccountLinkUiState());val accountLinkState=_accountLinkState.asStateFlow()
    private val _isStartingQuiz=MutableStateFlow(false);val isStartingQuiz=_isStartingQuiz.asStateFlow()
    private val _apkUpdateState=MutableStateFlow(ApkUpdateUiState(installedVersionCode=BuildConfig.VERSION_CODE,installedVersionName=BuildConfig.VERSION_NAME));val apkUpdateState=_apkUpdateState.asStateFlow()
    private val _apkInstallEvents=MutableSharedFlow<ApkInstallEvent>();val apkInstallEvents=_apkInstallEvents.asSharedFlow()
    private val _pendingReview=MutableStateFlow<List<PendingReviewItem>>(emptyList());val pendingReview=_pendingReview.asStateFlow()
    private val _showPendingReview=MutableStateFlow(false);val showPendingReview=_showPendingReview.asStateFlow()
    private val _pendingExport=MutableSharedFlow<File>();val pendingExport=_pendingExport.asSharedFlow()
    fun openPendingReview(){operation{_pendingReview.value=repository.pendingReview();_showPendingReview.value=true}}
    fun dismissPendingReview(){_showPendingReview.value=false}
    fun useServerExam(exam:StoredExam){operation {val context=appContext?:return@operation;val file=repository.preserveAndUseServerExam(exam,File(context.cacheDir,"preserved"));refreshAllData();_pendingExport.emit(file);_userFeedbackMessage.emit("Versão do servidor carregada. Sua edição anterior continua preservada e disponível para exportação.")}}
    fun exportPendingReview(){operation{val context=appContext?:return@operation;_pendingExport.emit(repository.exportPending(File(context.cacheDir,"preserved")))}}
    private var refreshJob:Job?=null;private var syllabusJob:Job?=null;private var startJob:Job?=null;private var aiJob:Job?=null;private var aiGeneration=0L
    private fun operation(block:suspend CoroutineScope.()->Unit)=viewModelScope.launch {
        try{block()}catch(e:CancellationException){throw e}catch(e:Exception){_userFeedbackMessage.emit(e.message?:"Não foi possível concluir. Seus registros foram preservados.")}
    }
    init { refreshAllData();checkApkUpdate();appContext?.let { OfflineSyncWorker.enqueueNow(it) };operation { repository.observeSync().distinctUntilChanged().collect { refreshAllData() } } }
    private fun saveProfile(profile:UserProfileDto){
        check(prefs?.edit()?.putLong("user_id",profile.userId)?.putString("username",profile.username)?.putString("display_name",profile.displayName)
            ?.putInt("daily_questions_target",profile.dailyQuestionsTarget)?.putFloat("daily_study_hours_target",profile.dailyStudyHoursTarget.toFloat())
            ?.putString("active_contests",profile.activeContestIds.joinToString(","))?.putBoolean("onboarding_completed",true)?.commit()?:true)
    }
    fun completeOnboarding(telegramUserId:Long?,telegramUsername:String?,displayName:String,selectedContests:List<String>,dailyQuestionsTarget:Int,dailyHoursTarget:Double,linkCode:String?=null){
        operation {
            val session=repository.linkTelegramAccountOrLogin(telegramUserId,telegramUsername,displayName,linkCode)
            val profile=repository.saveOnboarding(session.profile.userId,selectedContests,dailyQuestionsTarget,dailyHoursTarget,displayName)
            saveProfile(profile);_isOnboardingCompleted.value=true
            if(profile.userId!=repository.ownerUserId){_accountChanged.emit(profile.userId);return@operation}
            _userProfile.value=profile;refreshAllData();appContext?.let{OfflineSyncWorker.enqueueNow(it)}
            _userFeedbackMessage.emit(if(session.token.isBlank())"Perfil salvo neste aparelho. A sincronização aguardará conexão." else "Perfil salvo. Sincronização agendada.")
        }
    }
    fun updateUserSettings(telegramUserId:Long?,telegramUsername:String?,displayName:String,selectedContests:List<String>,dailyQuestionsTarget:Int,dailyHoursTarget:Double,linkCode:String?=null){
        if(_accountLinkState.value.isSubmitting)return
        if(!linkCode.isNullOrBlank()||(telegramUserId!=null&&telegramUserId!=repository.ownerUserId)){
            completeOnboarding(telegramUserId,telegramUsername,displayName,selectedContests,dailyQuestionsTarget,dailyHoursTarget,linkCode);return
        }
        operation {val profile=repository.saveOnboarding(repository.ownerUserId,selectedContests,dailyQuestionsTarget,dailyHoursTarget,displayName);saveProfile(profile);_userProfile.value=profile;refreshAllData();appContext?.let{OfflineSyncWorker.enqueueNow(it)};_userFeedbackMessage.emit("Preferências salvas neste aparelho. Confira o estado da sincronização em Hoje.")}
    }
    fun confirmAccountLink(userId:Long,username:String?,code:String){
        if(_accountLinkState.value.isSubmitting)return
        if(userId<=0 || code.isBlank()){
            _accountLinkState.value=AccountLinkUiState(message="Informe o ID numérico da conta e o código de vínculo.",isError=true)
            return
        }
        _accountLinkState.value=AccountLinkUiState(isSubmitting=true)
        viewModelScope.launch {
            try {
                // Linking authenticates the destination account. It does not copy
                // this screen's unsaved name, goals or contests to that account.
                val session=repository.linkTelegramAccountOrLogin(userId,username,null,code.trim())
                saveProfile(session.profile);_isOnboardingCompleted.value=true
                if(session.profile.userId!=repository.ownerUserId){_accountChanged.emit(session.profile.userId);return@launch}
                _userProfile.value=session.profile;refreshAllData()
                appContext?.let{OfflineSyncWorker.enqueueNow(it)}
                _accountLinkState.value=AccountLinkUiState(isLinked=true,message="Vínculo confirmado. Sincronização do histórico agendada.")
            } catch(e:CancellationException){_accountLinkState.value=AccountLinkUiState();throw e}
            catch(e:Exception){
                val message=when(e){
                    is retrofit2.HttpException->when(e.code()){
                        401->"Código inválido ou expirado. Solicite um novo código e tente novamente."
                        409->"Este código já foi usado por outra instalação. Solicite um novo código."
                        else->"Não foi possível confirmar o vínculo. Confira os dados e tente novamente."
                    }
                    is java.io.IOException->"Sem conexão com o servidor. O vínculo não foi confirmado; tente novamente."
                    else->"Não foi possível concluir o vínculo. Seus registros foram preservados."
                }
                _accountLinkState.value=AccountLinkUiState(message=message,isError=true)
            }
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
            val profile=_userProfile.value.copy(displayName=prefs?.getString("display_name",_userProfile.value.displayName),dailyQuestionsTarget=prefs?.getInt("daily_questions_target",50)?:50,dailyStudyHoursTarget=(prefs?.getFloat("daily_study_hours_target",2f)?:2f).toDouble(),activeContestIds=(prefs?.getString("active_contests","CPNU")?:"CPNU").split(',').filter { it.isNotBlank() })
            _userProfile.value=profile
            val allStored=repository.loadStoredExams(includeDeleted=true);_pendingExamDeletions.value=allStored.filter { it.lifecycleStatus=="EXCLUIDO" && it.syncStatus!="SYNCED" };val stored=allStored.filter { it.lifecycleStatus!="EXCLUIDO" };_storedExams.value=stored
            val selected=PracticeScope.selected(stored,_selectedContestId.value)
            if(selected!=_selectedContestId.value){_selectedContestId.value=selected;prefs?.edit()?.putString("selected_contest_id",selected)?.apply()}
            _focusedTrunks.value=repository.focusedTrunkSlugs()
            val dashboard=repository.getTodayDashboard(uid,profile.dailyQuestionsTarget,profile.activeContestIds)
            _todayDashboard.value=overlayLadder(dashboard,stored,_focusedTrunks.value,repository.canonicalMasteryBySubject())
            _analytics.value=repository.getProgressAnalytics(uid)
            if(_activeSession.value==null){_pausedDiscipline.value=repository.pausedSession()?.questions?.firstOrNull()?.disciplina}
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
        val created=if(previous==null)repository.createExamOffline(draft)else{
            val next=OfflineExamQueue.createPendingExam(draft).copy(examId=previous.examId,revision=previous.revision,isActiveInLadder=draft.lifecycleStatus!="ARQUIVADO",wrongAnswerPenalty=draft.wrongAnswerPenalty,syncStatus=if(previous.syncStatus=="PENDING_CREATE")"PENDING_CREATE"else if(draft.lifecycleStatus=="EDITAL_PUBLICADO"&&previous.lifecycleStatus!="EDITAL_PUBLICADO")"PENDING_PUBLISH"else"PENDING_UPDATE")
            repository.editExamOffline(next);next
        }
        _selectedContestId.value=created.examId;_showExamBuilder.value=false;_editingExam.value=null
        val profile=_userProfile.value.copy(activeContestIds=if(created.lifecycleStatus=="ARQUIVADO")_userProfile.value.activeContestIds-created.examId else (_userProfile.value.activeContestIds+created.examId).distinct());saveProfile(profile);_userProfile.value=profile
        repository.saveOnboarding(profile.userId,profile.activeContestIds,profile.dailyQuestionsTarget,profile.dailyStudyHoursTarget,profile.displayName)
        refreshAllData();appContext?.let{OfflineSyncWorker.enqueueNow(it)};_userFeedbackMessage.emit("Concurso salvo neste aparelho. A confirmação do servidor aparecerá após sincronizar.")
    }}
    fun archiveExam(exam:StoredExam)=setExamLifecycle(exam,"ARQUIVADO")
    fun restoreExam(exam:StoredExam)=setExamLifecycle(exam,"RUMOR_PREVISTO")
    fun deleteExam(exam:StoredExam)=setExamLifecycle(exam,"EXCLUIDO")
    private fun setExamLifecycle(exam:StoredExam,status:String){operation {repository.editExamOffline(exam.copy(lifecycleStatus=status,isActiveInLadder=status!="ARQUIVADO"&&status!="EXCLUIDO"));val profile=_userProfile.value.copy(activeContestIds=if(status=="RUMOR_PREVISTO")(_userProfile.value.activeContestIds+exam.examId).distinct()else _userProfile.value.activeContestIds-exam.examId);saveProfile(profile);_userProfile.value=profile;repository.saveOnboarding(profile.userId,profile.activeContestIds,profile.dailyQuestionsTarget,profile.dailyStudyHoursTarget,profile.displayName);refreshAllData();appContext?.let{OfflineSyncWorker.enqueueNow(it)}}}
    fun applyEditalDiffUpdate(id:String){operation {val before=_storedExams.value.find{it.examId==id}?.revision;repository.syncPendingAttempts(repository.ownerUserId);refreshAllData();_userFeedbackMessage.emit(if(repository.loadStoredExams().find{it.examId==id}?.revision!=before)"Nova revisão de edital salva. Histórico preservado."else"Nenhuma revisão nova de edital foi publicada para este concurso.")}}
    fun startQuizSession(mode:QuizMode,quantity:Int,contestId:String?=null,disciplina:String?=null,componentId:String?=null){
        startJob?.cancel()
        val id=PracticeScope.contest(mode,if(mode==QuizMode.SIMULADO)contestId?:_selectedContestId.value else contestId)
        startJob=operation {
            _isStartingQuiz.value=true
            try{
                val questions=repository.fetchOrGetOfflineQuiz(repository.ownerUserId,mode,quantity,id,disciplina,componentId)
                if(questions.isEmpty()){
                    _userFeedbackMessage.emit(when(mode){
                        QuizMode.SRS_REVIEW->"Nenhuma revisão válida disponível agora. Use o Shuffle Geral para praticar o acervo."
                        QuizMode.ERROR_NOTEBOOK->"Nenhuma questão válida disponível no caderno de erros. O histórico continua preservado."
                        QuizMode.MOST_BADASS->"Nenhuma questão válida do seu histórico atende ao desafio agora. Use o Shuffle Geral."
                        QuizMode.COMMON_TRUNK->"Nenhuma questão disponível para o tronco das grades ativas. Confira os vínculos ou conecte-se para atualizar o cache."
                        else->if(disciplina!=null)"Não foi possível obter questões de $disciplina neste filtro. O Shuffle Geral acessa outras matérias disponíveis." else "Nenhuma questão disponível neste filtro. Confira a conexão e o acervo em cache."
                    });return@operation
                }
                val exam=repository.loadStoredExams().find{it.examId==id}
                val session=SessionSnapshot(UUID.randomUUID().toString(),repository.ownerUserId,questions,mode,exam,if(mode==QuizMode.SIMULADO)System.currentTimeMillis()+questions.size*180000L else null)
                repository.createSession(session);showSession(session)
            }finally{_isStartingQuiz.value=false}
        }
    }
    private fun showSession(session:SessionSnapshot){dismissAiSheet();_activeSession.value=session;_activeQuizQuestions.value=session.questions;_isSimuladoSession.value=session.mode==QuizMode.SIMULADO;_pausedDiscipline.value=session.questions.firstOrNull()?.disciplina}
    fun resumeSession(){operation {repository.pausedSession()?.let(::showSession)?:_userFeedbackMessage.emit("Não há sessão pausada neste aparelho.")}}
    fun updateSession(incoming:SessionSnapshot){
        val current=_activeSession.value?:return
        if(current.sessionId!=incoming.sessionId)return
        val answers=incoming.answers.toMutableMap();current.answers.filterValues { it.submitted }.forEach{(id,answer)->answers[id]=answer}
        val next=incoming.copy(revision=current.revision+1,answers=answers);_activeSession.value=next
        operation {repository.saveSession(next)}
    }
    suspend fun submitAnswer(question:QuestionDto,chosenIndex:Int,confidenceLevel:Int,timeSpentSeconds:Int=60):Boolean{
        val session=_activeSession.value?:return false
        return try{
            require(chosenIndex in question.answers.indices&&question.correctAnswerIndices.size==1)
            val attempt=PendingAttemptEntity(questionId=question.id,chosenIndex=chosenIndex,chosenText=question.answers[chosenIndex],isCorrect=chosenIndex in question.correctAnswerIndices,timeSpentSeconds=timeSpentSeconds.coerceIn(1,86400),confidenceLevel=confidenceLevel,sessionUuid=session.sessionId,quizMode=session.mode.name,examBlock=null,contestId=session.exam?.examId,userNote=null,canonicalSubjectId=question.canonicalSubjectId,canonicalTopicId=question.canonicalTopicId,ownerUserId=session.ownerUserId,
                attemptId=UUID.nameUUIDFromBytes("${session.sessionId}:${question.id}".toByteArray()).toString(),questionRevision=question.questionRevision,examComponentId=question.examComponentId)
            val next=session.copy(answers=session.answers+(question.id to SessionAnswer(chosenIndex,confidenceLevel,timeSpentSeconds,true)),revision=session.revision+1)
            repository.recordAttemptOfflineFirst(session.ownerUserId,attempt,next)
            if(_activeSession.value?.sessionId==session.sessionId)_activeSession.value=next
            localStats();appContext?.let{OfflineSyncWorker.enqueueNow(it)};true
        }catch(e:CancellationException){throw e}catch(e:Exception){_userFeedbackMessage.emit(e.message?:"A resposta não foi salva. Tente novamente antes de sair.");false}
    }
    fun exitSession(completed:Boolean=false){val current=_activeSession.value?:return;operation {
        repository.saveSession(current)
        if(completed)repository.completeSession(current.sessionId)
        _activeSession.value=null;_activeQuizQuestions.value=null;_isSimuladoSession.value=false;dismissAiSheet();refreshAllData()
    }}
    fun reportProblem(id:String,category:String){operation {check(repository.reportQuestionProblem(repository.ownerUserId,id,category)){"O reporte não pôde ser salvo; tente novamente."};appContext?.let{OfflineSyncWorker.enqueueNow(it)};localStats();_userFeedbackMessage.emit("Reporte salvo neste aparelho e aguardando confirmação de envio.")}}
    fun requestAiTutor(id:String,kind:String,title:String,hintIndex:Int?=null){
        aiJob?.cancel();val generation=++aiGeneration;_activeAiSheetTitle.value=title;_activeAiSheetMarkdown.value="Consultando tutor…"
        aiJob=operation {val text=repository.requestAiTutor(id,kind,hintIndex);if(aiGeneration==generation&&_activeQuizQuestions.value?.any{it.id==id}==true)_activeAiSheetMarkdown.value=text}
    }
    fun dismissAiSheet(){aiGeneration++;aiJob?.cancel();_activeAiSheetMarkdown.value=null;_activeAiSheetTitle.value=null}
    fun checkApkUpdate(){operation {
        _apkUpdateState.value=_apkUpdateState.value.copy(isChecking=true)
        try{val remote=repository.fetchRemoteApkInfo();_apkUpdateState.value=_apkUpdateState.value.copy(remoteInfo=remote,hasUpdate=remote!=null&&remote.versionCode>BuildConfig.VERSION_CODE,statusMessage=when{remote==null->"Não foi possível verificar a versão agora.";remote.versionCode>BuildConfig.VERSION_CODE->"Atualização disponível: ${remote.versionName}";else->"Versão instalada atualizada."})}finally{_apkUpdateState.value=_apkUpdateState.value.copy(isChecking=false)}
    }}
    fun downloadAndInstallApk(context:Context){operation {
        if(_apkUpdateState.value.isDownloading)return@operation
        if(Build.VERSION.SDK_INT>=26&&!context.packageManager.canRequestPackageInstalls()){_apkInstallEvents.emit(ApkInstallEvent.NeedsUnknownSourcesPermission);return@operation}
        _apkUpdateState.value=_apkUpdateState.value.copy(isDownloading=true,statusMessage="Baixando e verificando a atualização…")
        try{
            val file=repository.downloadApkUpdate(File(context.cacheDir,"updates"))?:error("O download não pôde ser verificado.")
            val flags=if(Build.VERSION.SDK_INT>=28)android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION") val installed=context.packageManager.getPackageInfo(context.packageName,flags)
            @Suppress("DEPRECATION") val incoming=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:error("Pacote inválido")
            @Suppress("DEPRECATION") val installedCerts=if(Build.VERSION.SDK_INT>=28)installed.signingInfo?.apkContentsSigners.orEmpty()else installed.signatures.orEmpty()
            @Suppress("DEPRECATION") val incomingHistory=if(Build.VERSION.SDK_INT>=28)incoming.signingInfo?.signingCertificateHistory.orEmpty()else incoming.signatures.orEmpty()
            @Suppress("DEPRECATION") val version=if(Build.VERSION.SDK_INT>=28)incoming.longVersionCode else incoming.versionCode.toLong()
            require(incoming.packageName==context.packageName&&version>BuildConfig.VERSION_CODE&&installedCerts.isNotEmpty()&&installedCerts.all{old->incomingHistory.any{it==old}}){"O pacote não corresponde à identidade e à versão esperadas do aplicativo."}
            _apkInstallEvents.emit(ApkInstallEvent.ReadyToInstall(file))
        }finally{_apkUpdateState.value=_apkUpdateState.value.copy(isDownloading=false)}
    }}
}
class MainViewModelFactory(private val repository:ConcursoRepository,private val appContext:Context?=null):ViewModelProvider.Factory{
    override fun <T:ViewModel>create(modelClass:Class<T>):T{require(modelClass.isAssignableFrom(MainViewModel::class.java));@Suppress("UNCHECKED_CAST")return MainViewModel(repository,appContext) as T}
}
