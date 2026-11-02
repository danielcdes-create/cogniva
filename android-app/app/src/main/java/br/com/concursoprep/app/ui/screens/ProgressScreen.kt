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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.DailyHeatmapCellDto
import br.com.concursoprep.app.domain.model.ProgressAnalyticsDto
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.LocalExtendedColors

/**
 * Tela "Progresso & Analytics":
 * Responde em segundos: "Estou no caminho de passar?"
 * - Índice de Prontidão vs. Nota de Corte Histórica
 * - Matriz Confiança × Acerto (destacando Erros com Alta Confiança)
 * - Barras de desempenho por disciplina ordenadas da mais crítica para a mais forte
 * - Heatmap de atividade
 */
@Composable
fun ProgressScreen(
    analytics: ProgressAnalyticsDto,
    onPracticeQuizMode: (QuizMode, String?) -> Unit = { _, _ -> }
) {
    val ext = LocalExtendedColors.current
    var selectedHeatmapCell by remember { mutableStateOf<DailyHeatmapCellDto?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                CognivaBrandHeader(
                    ecosystemProduct = "Cogniva Insights",
                    subtitle = "Analytics, Matriz de Confiança & Diagnóstico"
                )
            }

            // 1. Projeção & Índice de Prontidão vs. Nota de Corte Histórica
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Índice de Prontidão vs. Nota de Corte Histórica",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if(analytics.historicalCutoffReference>0) "Índice de prontidão ${analytics.readinessIndexScore.toInt()}/100 · Corte de referência cadastrado: ${analytics.historicalCutoffReference.toInt()}/100" else "Índice de prontidão ${analytics.readinessIndexScore.toInt()}/100 · Sem corte de referência validado",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (analytics.readinessIndexScore.toFloat() / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (analytics.historicalCutoffReference>0 && analytics.readinessIndexScore >= analytics.historicalCutoffReference) ext.correct else ext.warning
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Questões praticadas: ${analytics.totalQuestionsPracticed}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "Dominadas (SRS): ${analytics.totalMasteredQuestions}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ext.correct
                            )
                        }
                    }
                }
            }

            // 2. Matriz Metacognitiva: Confiança × Acerto
            item {
                val cm = analytics.confidenceMatrix
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Matriz Metacognitiva (Confiança × Resultado)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuadrantBox(
                                title = "Domínio Real",
                                subtitle = "Acerto c/ Alta Confiança",
                                count = cm.highConfidenceCorrect,
                                color = ext.correct,
                                modifier = Modifier.weight(1f),
                                onClick = { onPracticeQuizMode(QuizMode.SRS_REVIEW, null) }
                            )
                            QuadrantBox(
                                title = "Acerto Frágil",
                                subtitle = "Acerto no Chute/Dúvida",
                                count = cm.lowConfidenceCorrect,
                                color = ext.warning,
                                modifier = Modifier.weight(1f),
                                onClick = { onPracticeQuizMode(QuizMode.SRS_REVIEW, null) }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuadrantBox(
                                title = "Ponto Cego Crítico",
                                subtitle = "Erro c/ Alta Confiança (Toque p/ Treinar)",
                                count = cm.highConfidenceError,
                                color = ext.error,
                                modifier = Modifier.weight(1f),
                                onClick = { onPracticeQuizMode(QuizMode.ERROR_NOTEBOOK, null) }
                            )
                            QuadrantBox(
                                title = "Lacuna Mapeada",
                                subtitle = "Erro c/ Baixa Confiança",
                                count = cm.lowConfidenceError,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                onClick = { onPracticeQuizMode(QuizMode.ERROR_NOTEBOOK, null) }
                            )
                        }
                    }
                }
            }

            // 3. Heatmap de Consistência Diária
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Consistência de Estudo (Últimos dias ativos)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            analytics.activityHeatmap.take(14).forEach { cell ->
                                val alpha = (cell.questionsCount.toFloat() / 40f).coerceIn(0.2f, 1f)
                                val isSelected = selectedHeatmapCell?.dateYmd == cell.dateYmd
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else ext.correct.copy(alpha = alpha),
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clickable { selectedHeatmapCell = cell }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                        selectedHeatmapCell?.let { cell ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${cell.dateYmd}: ${cell.questionsCount} questões respondidas (${cell.accuracyPct.toInt()}% de acerto)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 4. Desempenho por Disciplina (Ordenado por Pior Desempenho)
            item {
                Text(
                    text = "Histórico de acertos por disciplina",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            items(
                items = analytics.disciplinesSortedByWeakness,
                key = { it.discipline }
            ) { item ->
                val barColor = when {
                    item.accuracyPct < 60.0 -> ext.error
                    item.accuracyPct < 75.0 -> ext.warning
                    else -> ext.correct
                }
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPracticeQuizMode(QuizMode.STANDARD, item.discipline) }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.discipline,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${item.accuracyPct.toInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = barColor
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (item.accuracyPct.toFloat() / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = barColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${item.totalAttempts} tentativas · ${item.masteredQuestions} dominadas",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Treinar →",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuadrantBox(
    title: String,
    subtitle: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.headlineMedium,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
