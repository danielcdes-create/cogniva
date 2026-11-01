package br.com.concursoprep.app.ui.screens

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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.R
import br.com.concursoprep.app.domain.exam.ExamEngine
import br.com.concursoprep.app.domain.model.ApkUpdateUiState
import br.com.concursoprep.app.domain.model.UpcomingExamCard
import java.time.LocalDate
import br.com.concursoprep.app.domain.model.ContestStatus
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.TodayDashboardDto
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.LocalExtendedColors

/**
 * Tela "Hoje":
 * Responde em segundos: "O que eu estudo/respondo hoje?"
 * Regra de ouro: Revisão espaçada (SRS) e Caderno de Erros sempre acima de conteúdo novo.
 */
@Composable
fun TodayScreen(
    dashboard: TodayDashboardDto,
    pausedDiscipline: String?,
    apkUpdateState: ApkUpdateUiState = ApkUpdateUiState(),
    onCheckApkUpdate: () -> Unit = {},
    onInstallApkUpdate: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onStartQuizMode: (QuizMode, String?) -> Unit,
    onOpenContestSyllabus: (String) -> Unit,
    onAddExam: () -> Unit = {}
) {
    val progressFraction = (dashboard.questionsAnsweredToday.toFloat() /
        dashboard.dailyGoalQuestions.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val ext = LocalExtendedColors.current

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
            // 1. Cabeçalho Sóbrio COGNIVA + Pulso do Dia
            item(key = "today_header") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CognivaBrandHeader(
                        ecosystemProduct = "Cogniva Planner · Learn",
                        subtitle = "Conhecimento em evolução · Pulso diário"
                    )
                    val primaryExam = dashboard.upcomingExams.firstOrNull()
                    if (primaryExam != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val badgeColor = when (primaryExam.status) {
                                ContestStatus.EDITAL_PUBLICADO, ContestStatus.EM_ANDAMENTO -> ext.error
                                ContestStatus.SOLICITADO_AUTORIZADO -> ext.warning
                                else -> ext.neutral
                            }
                            Surface(
                                shape = CircleShape,
                                color = badgeColor,
                                modifier = Modifier.size(10.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${deadlineLabel(primaryExam)} · ${primaryExam.name}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }

            // 2. Card da Meta Diária (Sem confetti, foco em consistência)
            item(key = "daily_goal") {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Meta diária: ${dashboard.dailyGoalQuestions} questões",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${dashboard.questionsAnsweredToday}/${dashboard.dailyGoalQuestions} (${dashboard.accuracyTodayPct.toInt()}% acerto)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (progressFraction >= 1f) ext.correct else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }

            // 3. Prioridade Nº 1: Revisão Espaçada (SRS) — Sempre no topo!
            item(key = "srs_action") {
                PriorityActionCard(
                    title = "Revisão espaçada (${dashboard.pendingSrsReviewsCount} pendentes)",
                    subtitle = "Prioridade máxima para retenção de longo prazo antes de avançar matéria nova",
                    accentColor = ext.warning,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Alarm,
                            contentDescription = null,
                            tint = ext.warning
                        )
                    },
                    onClick = { onStartQuizMode(QuizMode.SRS_REVIEW, null) }
                )
            }

            // 4. Prioridade Nº 2: Caderno de Erros + Alerta de Erro com Alta Confiança
            item(key = "error_notebook") {
                val subtitle = if (dashboard.highConfidenceErrorsCount > 0) {
                    "Inclui ${dashboard.highConfidenceErrorsCount} ponto(s) cego(s) (erro com alta confiança)"
                } else {
                    "Refazer apenas questões erradas anteriormente"
                }
                PriorityActionCard(
                    title = "Caderno de Erros (${dashboard.errorNotebookCount} questões)",
                    subtitle = subtitle,
                    accentColor = ext.error,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = ext.error
                        )
                    },
                    onClick = { onStartQuizMode(QuizMode.ERROR_NOTEBOOK, null) }
                )
            }

            // 5. Retomada de Sessão Pausada (Estudo no transporte)
            if (pausedDiscipline != null) {
                item(key = "paused_session") {
                    PriorityActionCard(
                        title = "Continuar sessão: $pausedDiscipline",
                        subtitle = "Retomar de onde você parou (disponível offline)",
                        accentColor = MaterialTheme.colorScheme.primary,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = { onStartQuizMode(QuizMode.STANDARD, pausedDiscipline) }
                    )
                }
            }

            // 6. Alavancagem Multi-Concurso: Tronco Comum
            item(key = "common_trunk") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onStartQuizMode(
                                QuizMode.COMMON_TRUNK,
                                dashboard.commonTrunkSuggestion.discipline
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Tronco Comum · Escada de Concursos",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = dashboard.commonTrunkSuggestion.badgeText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // 7. Próximas Provas
            item(key = "upcoming_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Meus Concursos em Escada",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    TextButton(onClick = onAddExam, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("+ Adicionar Concurso na Escada")
                    }
                }
            }

            items(
                items = dashboard.upcomingExams,
                key = { exam -> exam.contestId }
            ) { exam ->
                val badgeColor = when (exam.status) {
                    ContestStatus.EDITAL_PUBLICADO, ContestStatus.EM_ANDAMENTO -> ext.error
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
                                color = badgeColor,
                                modifier = Modifier.size(10.dp)
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = deadlineLabel(exam),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (exam.dateType == "EXACT_DATE" || exam.daysRemaining != null) ext.error else ext.neutral
                        )
                    }
                }
            }

            // 8. Banner de Nova Versão OTA
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
        }
    }
}

@Composable
private fun PriorityActionCard(
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
                color = accentColor.copy(alpha = 0.16f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    icon()
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
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
        today = LocalDate.now()
    )
}
