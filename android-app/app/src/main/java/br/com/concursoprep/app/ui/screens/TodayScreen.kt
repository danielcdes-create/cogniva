package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.ContestStatus
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.domain.model.TodayDashboardDto
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.CorrectGreen
import br.com.concursoprep.app.ui.theme.ErrorRed
import br.com.concursoprep.app.ui.theme.EstimatedNeutral
import br.com.concursoprep.app.ui.theme.ReviewAmber

/**
 * Tela "Hoje" (Nota Técnica Quiz Kotlin §5 & §13):
 * Responde em segundos: "O que eu estudo/respondo hoje?"
 * Regra de ouro: Revisão espaçada (SRS) e Caderno de Erros sempre acima de conteúdo novo.
 */
@Composable
fun TodayScreen(
    dashboard: TodayDashboardDto,
    pausedDiscipline: String?,
    onStartQuizMode: (QuizMode, String?) -> Unit,
    onOpenContestSyllabus: (String) -> Unit
) {
    val progressFraction = (dashboard.questionsAnsweredToday.toFloat() /
        dashboard.dailyGoalQuestions.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Cabeçalho Sóbrio COGNIVA + Pulso do Dia
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CognivaBrandHeader(
                    ecosystemProduct = "Cogniva Planner · Learn",
                    subtitle = "Conhecimento em evolução · Pulso diário"
                )
                val primaryExam = dashboard.upcomingExams.firstOrNull()
                if (primaryExam != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val badgeColor = when (primaryExam.status) {
                            ContestStatus.EDITAL_PUBLICADO, ContestStatus.EM_ANDAMENTO -> ErrorRed
                            ContestStatus.SOLICITADO_AUTORIZADO -> ReviewAmber
                            else -> EstimatedNeutral
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (primaryExam.daysRemaining != null) {
                                "Faltam ${primaryExam.daysRemaining} dias · ${primaryExam.name}"
                            } else {
                                "${primaryExam.name} · Previsto (${primaryExam.estimatedWindow ?: "Sem edital"})"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }

        // 2. Card da Meta Diária (Sem confetti, foco em consistência)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                        color = if (progressFraction >= 1f) CorrectGreen else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 3. Prioridade Nº 1: Revisão Espaçada (SRS) — Sempre no topo!
        item {
            PriorityActionCard(
                title = "Revisão espaçada (${dashboard.pendingSrsReviewsCount} pendentes)",
                subtitle = "Prioridade máxima para retenção de longo prazo antes de avançar matéria nova",
                accentColor = ReviewAmber,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = null,
                        tint = ReviewAmber
                    )
                },
                onClick = { onStartQuizMode(QuizMode.SRS_REVIEW, null) }
            )
        }

        // 4. Prioridade Nº 2: Caderno de Erros + Alerta de Erro com Alta Confiança
        item {
            val subtitle = if (dashboard.highConfidenceErrorsCount > 0) {
                "Inclui ${dashboard.highConfidenceErrorsCount} ponto(s) cego(s) (erro com alta confiança)"
            } else {
                "Refazer apenas questões erradas anteriormente"
            }
            PriorityActionCard(
                title = "Caderno de Erros (${dashboard.errorNotebookCount} questões)",
                subtitle = subtitle,
                accentColor = ErrorRed,
                icon = {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = ErrorRed
                    )
                },
                onClick = { onStartQuizMode(QuizMode.ERROR_NOTEBOOK, null) }
            )
        }

        // 5. Retomada de Sessão Pausada (Estudo no transporte)
        if (pausedDiscipline != null) {
            item {
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

        // 6. Alavancagem Multi-Concurso: Tronco Comum (§13)
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
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
                    Column(modifier = Modifier.weight(1f)) {
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
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // 7. Próximas Provas (Ordenadas por urgência real: Oficial > Autorizado > Previsto)
        item {
            Text(
                text = "Meus Concursos em Escada",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        items(dashboard.upcomingExams) { exam ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        val statusEmoji = when (exam.status) {
                            ContestStatus.EDITAL_PUBLICADO, ContestStatus.EM_ANDAMENTO -> "🔴"
                            ContestStatus.SOLICITADO_AUTORIZADO -> "🟡"
                            else -> "⚪"
                        }
                        Text(text = statusEmoji, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
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
                        text = if (exam.daysRemaining != null) {
                            "${exam.daysRemaining} dias"
                        } else {
                            exam.estimatedWindow ?: "Previsto"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (exam.daysRemaining != null) ErrorRed else EstimatedNeutral
                    )
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
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
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
