package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.R
import br.com.concursoprep.app.domain.exam.ExamEngine
import br.com.concursoprep.app.domain.exam.PrioritizationEngine
import br.com.concursoprep.app.domain.model.ApkUpdateUiState
import br.com.concursoprep.app.domain.model.ContestStatus
import br.com.concursoprep.app.domain.model.LearningProjection
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.TodayDashboardDto
import br.com.concursoprep.app.domain.model.UpcomingExamCard
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.LocalExtendedColors
import java.time.LocalDate

/**
 * Tela Inicial — Protocolo de Entrada de 60 Segundos ("Terça · 22h"):
 * - Nenhuma meta atrasada, nenhum gráfico de produtividade e nenhuma cobrança de sequência diária.
 * - Compromisso estritamente reversível, sempre com saída visível ("Sem estudar hoje").
 */
@Composable
fun TodayScreen(
    dashboard: TodayDashboardDto,
    pausedDiscipline: String?,
    onResumeSession: () -> Unit = {},
    apkUpdateState: ApkUpdateUiState = ApkUpdateUiState(),
    onCheckApkUpdate: () -> Unit = {},
    onInstallApkUpdate: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onStartQuizMode: (QuizMode, String?) -> Unit,
    onOpenContestSyllabus: (String) -> Unit,
    onAddExam: () -> Unit = {},
    onStartSmartEntry: () -> Unit = { onStartQuizMode(QuizMode.ENTRY_60S, null) },
    onStartStrategyBattery: () -> Unit = { onStartQuizMode(QuizMode.STRATEGY_BATTERY, null) },
    onChooseConsciousRest: (Boolean) -> Unit = {},
    onScreenVisible: () -> Unit = {}
) {
    val ext = LocalExtendedColors.current
    val nextAction = dashboard.nextAction
    val scheduleHeader = nextAction?.scheduleHeader?.takeIf { it.isNotBlank() }
        ?: PrioritizationEngine.formatEntry60sHeader()
    val restedToday = dashboard.entryMetrics.restedToday

    LaunchedEffect(Unit) {
        onScreenVisible()
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "today_header") {
                CognivaBrandHeader(
                    ecosystemProduct = "Cogniva · Entrada Leve",
                    subtitle = "Compromisso reversível · Zero cobrança de sequência"
                )
            }

            // 1. Protótipo da Primeira Tela (0–8 s): TERÇA · 22H / Só uma questão. / Resolver 1 questão / Sem estudar hoje
            item(key = "entry_60s_hero") {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = scheduleHeader,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Só uma questão.",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Você pode encerrar logo depois, independentemente do resultado.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        nextAction?.entryReason?.takeIf { it.isNotBlank() }?.let { reason ->
                            Text(
                                text = "Foco sugerido: $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        if (restedToday) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Pausa consciente ativa hoje.",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Nenhuma punição, meta acumulada ou perda de progresso. Se quiser mudar de ideia a qualquer momento, o botão abaixo continua livre.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Button(
                            onClick = onStartSmartEntry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Resolver 1 questão",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = { onChooseConsciousRest(!restedToday) },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (restedToday) "Retomar disponibilidade hoje" else "Sem estudar hoje",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 2. Retomada opcional de sessão pausada (sem cobrança)
            if (pausedDiscipline != null) {
                item(key = "paused_session") {
                    CalmOptionCard(
                        title = "Continuar de onde parou: $pausedDiscipline",
                        subtitle = "Sessão salva neste aparelho (disponível offline)",
                        accentColor = MaterialTheme.colorScheme.primary,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = onResumeSession
                    )
                }
            }

            // 3. Simulador Diário de Decisão sob Incerteza (8–12 min, opcional)
            item(key = "decision_simulator_shortcut") {
                val penalty = nextAction?.examGapSummary?.wrongAnswerPenalty ?: 0.0
                val subtitle = if (penalty > 0.0) {
                    "8–12 min opcionais · Treine responder vs. omitir sob VE = 2p − 1 e meta de corte"
                } else {
                    "8–12 min opcionais · Treine calibração de confiança em diferentes momentos de prova"
                }
                CalmOptionCard(
                    title = "Simulador de Decisão sob Incerteza",
                    subtitle = subtitle,
                    accentColor = MaterialTheme.colorScheme.primary,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = onStartStrategyBattery
                )
            }

            // 4. Alavancagem opcional de Tronco Comum (quando houver múltiplos concursos ativos)
            if (dashboard.commonTrunkSuggestion.discipline.isNotBlank()) {
                item(key = "common_trunk") {
                    CalmOptionCard(
                        title = "Tronco Comum · Escada de Concursos",
                        subtitle = dashboard.commonTrunkSuggestion.badgeText,
                        accentColor = MaterialTheme.colorScheme.primary,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            onStartQuizMode(
                                QuizMode.COMMON_TRUNK,
                                dashboard.commonTrunkSuggestion.discipline
                            )
                        }
                    )
                }
            }

            // 5. Meus Concursos (referência calma de editais, sem alarmismo)
            if (dashboard.upcomingExams.isNotEmpty()) {
                item(key = "upcoming_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Concursos Ativos",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        TextButton(onClick = onAddExam, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("+ Adicionar")
                        }
                    }
                }

                items(
                    items = dashboard.upcomingExams,
                    key = { exam -> exam.contestId }
                ) { exam ->
                    val dotColor = when (exam.status) {
                        ContestStatus.EDITAL_PUBLICADO, ContestStatus.EM_ANDAMENTO -> MaterialTheme.colorScheme.primary
                        ContestStatus.SOLICITADO_AUTORIZADO -> ext.warning
                        else -> ext.neutral
                    }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenContestSyllabus(exam.contestId) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = dotColor,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = exam.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Banca: ${exam.examBoard}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = deadlineLabel(exam),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 6. Banner de Nova Versão OTA (quando disponível)
            if (apkUpdateState.hasUpdate) {
                item(key = "ota_banner") {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = ext.correct
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Nova Atualização OTA Disponível!",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = apkUpdateState.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ext.correct
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenSettings,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text("Abrir Ajustes")
                                }
                                Button(
                                    onClick = onInstallApkUpdate,
                                    enabled = apkUpdateState.hasUpdate && !apkUpdateState.isDownloading,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        text = if (apkUpdateState.isDownloading) "Baixando..."
                                        else stringResource(R.string.action_update_apk),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. Rodapé discreto de sincronização offline-first
            item(key = "sync_footer") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (dashboard.lastSyncedAt == null) {
                            "Modo offline-first ativo neste aparelho."
                        } else {
                            "Sincronizado · ${dashboard.lastSyncedAt}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    dashboard.syncMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalmOptionCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    icon: @Composable () -> Unit,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accentColor.copy(alpha = 0.14f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    icon()
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun deadlineLabel(exam: UpcomingExamCard): String {
    return ExamEngine.formatDeadlineFromCard(
        dateType = exam.dateType,
        exactDateIso = exam.exactDateIso,
        windowLabel = exam.estimatedWindow,
        fallbackDays = exam.daysRemaining,
        today = LocalDate.now(LearningProjection.studyZone)
    )
}
