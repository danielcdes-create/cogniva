package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.domain.exam.StoredExam
import br.com.concursoprep.app.domain.model.ApkUpdateUiState
import br.com.concursoprep.app.domain.model.AccountAuthUiState
import br.com.concursoprep.app.domain.model.AppThemeMode
import br.com.concursoprep.app.domain.model.UserProfileDto
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.LocalExtendedColors

/**
 * Aba "Ajustes" (SettingsScreen):
 * Centraliza:
 * 1. Perfil de Usuário & Conta Nativa Cogniva (E-mail + Senha Argon2id / Convidado)
 * 2. Aparência do App (Modo Claro / Escuro / Sistema)
 * 3. Metas Diárias & Escada de Concursos Ativos (Questões/dia, Horas/dia, Concursos)
 * 4. Atualização do App via OTA (Verificação de Versão, SHA-256 e Download Direto de APK)
 * 5. Sincronização Offline-First (Room DB, Fila de Tentativas e Assistente de Onboarding)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    userProfile: UserProfileDto,
    themeMode: AppThemeMode,
    apkUpdateState: ApkUpdateUiState,
    cachedQuestionsCount: Int,
    pendingSyncCount: Int,
    onSelectThemeMode: (AppThemeMode) -> Unit,
    onSaveUserSettings: (
        displayName: String,
        selectedContests: List<String>,
        dailyQuestionsTarget: Int,
        dailyHoursTarget: Double
    ) -> Unit,
    onCheckApkUpdate: () -> Unit,
    onInstallApkUpdate: () -> Unit,
    onSyncNow: () -> Unit,
    onClearOfflineCache: () -> Unit,
    onReopenOnboarding: () -> Unit,
    ladderExams: List<StoredExam> = emptyList(),
    pendingExamDeletions: List<StoredExam> = emptyList(),
    focusedTrunkSlugs: Set<String> = emptySet(),
    onToggleTrunkFocus: (String) -> Unit = {},
    onEditExam: (StoredExam) -> Unit = {},
    onArchiveExam: (StoredExam) -> Unit = {},
    onRestoreExam: (StoredExam) -> Unit = {},
    onDeleteExam: (StoredExam) -> Unit = {},
    onCreateExam: () -> Unit = {},
    onUseServerExam: (StoredExam) -> Unit = {},
    onAdoptTemplate: (StoredExam) -> Unit = {},
    onOpenPending: () -> Unit = {},
    accountAuthState: AccountAuthUiState = AccountAuthUiState(),
    onAuthenticateAccount: (email: String, password: String, displayName: String?, isRegister: Boolean) -> Unit = { _, _, _, _ -> },
    onLogoutAccount: () -> Unit = {},
    onOpenQuestionImport: () -> Unit = {}
) {
    var managementOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val ext = LocalExtendedColors.current

    var displayName by remember(userProfile.displayName) {
        mutableStateOf(userProfile.displayName ?: "Estudante")
    }
    var authEmail by remember(userProfile.userId, userProfile.email) {
        mutableStateOf(userProfile.email ?: "")
    }
    var authPassword by remember(userProfile.userId) { mutableStateOf("") }
    LaunchedEffect(accountAuthState.isAuthenticated) {
        if (accountAuthState.isAuthenticated) authPassword = ""
    }
    var dailyQuestions by remember(userProfile.dailyQuestionsTarget) {
        mutableIntStateOf(userProfile.dailyQuestionsTarget)
    }
    var dailyHours by remember(userProfile.dailyStudyHoursTarget) {
        mutableDoubleStateOf(userProfile.dailyStudyHoursTarget)
    }
    val selectedContests = remember {
        mutableStateListOf<String>().apply {
            addAll(userProfile.activeContestIds)
        }
    }

    LaunchedEffect(userProfile.activeContestIds) {
        selectedContests.clear()
        selectedContests.addAll(userProfile.activeContestIds)
    }

    val ladderChips = ladderExams.filter { it.lifecycleStatus != "ARQUIVADO" }
    if(managementOpen) {
        ExamManagementScreen(
            ladderExams + pendingExamDeletions,
            pendingSyncCount,
            onBack = { managementOpen = false },
            onCreate = onCreateExam,
            onEdit = onEditExam,
            onArchive = onArchiveExam,
            onRestore = onRestoreExam,
            onDelete = onDeleteExam,
            onPending = onOpenPending,
            onUseServer = onUseServerExam,
            onAdoptTemplate = onAdoptTemplate
        )
        return
    }


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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CognivaBrandHeader(
                ecosystemProduct = "Cogniva Settings",
                subtitle = "Perfil, Aparência, Metas & Atualização OTA"
            )

            OutlinedButton(onClick={managementOpen=true},modifier=Modifier.fillMaxWidth()) { Text("Gerenciar concursos e registros") }
            if (userProfile.isAdmin) {
                OutlinedButton(onClick=onOpenQuestionImport,modifier=Modifier.fillMaxWidth()) { Text("Inserir questões · individual, lote ou IA") }
            }
            br.com.concursoprep.app.ui.components.StudyStructureHelp()
            // =========================================================================
            // 1. APARÊNCIA (Modo Claro / Escuro / Sistema)
            // =========================================================================
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (themeMode) {
                                AppThemeMode.DARK -> Icons.Default.DarkMode
                                AppThemeMode.LIGHT -> Icons.Default.LightMode
                                AppThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Aparência e Tema",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Escolha como a interface do Cogniva deve ser exibida:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            val isSelected = themeMode == mode
                            val icon = when (mode) {
                                AppThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
                                AppThemeMode.LIGHT -> Icons.Default.LightMode
                                AppThemeMode.DARK -> Icons.Default.DarkMode
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectThemeMode(mode) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(mode.label) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 2. PERFIL E CONTA NATIVA COGNIVA
            // =========================================================================
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Perfil e conta",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = if (userProfile.isGuest) "Modo convidado · ID #${userProfile.userId}" else "Conta #${userProfile.userId}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Personalize seu nome de exibição e gerencie o acesso à sua conta Cogniva.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Nome de Exibição") },
                        singleLine = true,
                        enabled = !accountAuthState.isSubmitting,
                        modifier = Modifier.fillMaxWidth()
                    )

                    AccountAuthForm(
                        email = authEmail,
                        onEmailChange = { authEmail = it },
                        password = authPassword,
                        onPasswordChange = { authPassword = it },
                        isGuest = userProfile.isGuest,
                        authenticatedEmail = userProfile.email,
                        isAdmin = userProfile.isAdmin,
                        state = accountAuthState,
                        onAuthenticate = { email, password, isRegister ->
                            onAuthenticateAccount(email, password, displayName.trim().ifBlank { null }, isRegister)
                        },
                        onLogout = onLogoutAccount,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // =========================================================================
                    // 3. METAS DE ESTUDO & ESCADA DE CONCURSOS
                    // =========================================================================
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.TrackChanges,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Metas Diárias & Escada de Concursos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Concursos ativos (o Tronco Comum cruza matérias entre os selecionados):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ladderChips.forEach { exam ->
                            val isSelected = selectedContests.contains(exam.examId)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected && selectedContests.size > 1) {
                                        selectedContests.remove(exam.examId)
                                    } else if (!isSelected) {
                                        selectedContests.add(exam.examId)
                                    }
                                },
                                label = { Text(exam.shortName) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        text = "Foco de Tronco Principal",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "O Tronco Comum na prática usa as disciplinas dos troncos marcados.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExamCatalog.trunks().forEach { trunk ->
                            FilterChip(
                                selected = focusedTrunkSlugs.contains(trunk.slug),
                                onClick = { onToggleTrunkFocus(trunk.slug) },
                                label = { Text(trunk.name) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        text = "Meta de questões por dia:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(20, 30, 50, 80, 100).forEach { q ->
                            FilterChip(
                                selected = dailyQuestions == q,
                                onClick = { dailyQuestions = q },
                                label = { Text("$q/dia") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        text = "Horas líquidas de estudo por dia:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1.0, 2.0, 3.0, 4.0, 6.0).forEach { h ->
                            FilterChip(
                                selected = dailyHours == h,
                                onClick = { dailyHours = h },
                                label = { Text("${h.toInt()}h/dia") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            onSaveUserSettings(
                                displayName.trim().ifBlank { "Estudante" },
                                selectedContests.toList(),
                                dailyQuestions,
                                dailyHours
                            )
                        },
                        enabled = !accountAuthState.isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Salvar nome e metas")
                    }
                }
            }

            // =========================================================================
            // 4. ATUALIZAÇÃO DO APP VIA OTA (Com verificação SHA-256)
            // =========================================================================
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Atualização do App (OTA)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (apkUpdateState.hasUpdate) ext.correct.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (apkUpdateState.hasUpdate) "NOVA VERSÃO" else "v${apkUpdateState.installedVersionName}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (apkUpdateState.hasUpdate) ext.correct else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Versão instalada: v${apkUpdateState.installedVersionName} (build ${apkUpdateState.installedVersionCode})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    apkUpdateState.remoteInfo?.let { remote ->
                        val sizeMb = String.format("%.1f MB", remote.sizeBytes.toDouble() / (1024.0 * 1024.0))
                        Text(
                            text = "Servidor Cogniva: v${remote.versionName} (build ${remote.versionCode}) · $sizeMb",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        SelectionContainer {
                            Text(
                                text = "SHA-256: ${remote.sha256Checksum}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = apkUpdateState.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (apkUpdateState.hasUpdate) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (apkUpdateState.hasUpdate) ext.correct else MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCheckApkUpdate,
                            enabled = !apkUpdateState.isChecking && !apkUpdateState.isDownloading,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            if (apkUpdateState.isChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Verificando...")
                            } else {
                                Text("Verificar Versão")
                            }
                        }
                        Button(
                            onClick = onInstallApkUpdate,
                            enabled = apkUpdateState.hasUpdate && !apkUpdateState.isDownloading,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                when {
                                    apkUpdateState.isDownloading -> "Baixando..."
                                    apkUpdateState.hasUpdate -> "Baixar e Atualizar"
                                    else -> "APK já atualizado"
                                }
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 5. DADOS OFFLINE-FIRST, SINCRONIZAÇÃO & DIAGNÓSTICO DO SISTEMA
            // =========================================================================
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Armazenamento Offline & Servidor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "• Endpoint da API: ${BuildConfig.API_BASE_URL}\n" +
                            "• Questões em cache local (Room DB): $cachedQuestionsCount\n" +
                            "• Respostas na fila aguardando envio: $pendingSyncCount",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pendingSyncCount > 0) ext.warning else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onSyncNow,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sincronizar")
                        }
                        OutlinedButton(
                            onClick = onClearOfflineCache,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Limpar Cache")
                        }
                    }

                    OutlinedButton(
                        onClick = onReopenOnboarding,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refazer Assistente de Boas-Vindas")
                    }
                }
            }
        }
    }
}
