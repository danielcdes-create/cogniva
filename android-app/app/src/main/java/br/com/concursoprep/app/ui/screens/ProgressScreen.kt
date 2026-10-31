package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.ProgressAnalyticsDto
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.CorrectGreen
import br.com.concursoprep.app.ui.theme.ErrorRed
import br.com.concursoprep.app.ui.theme.ReviewAmber

/**
 * Tela "Progresso & Analytics" (Nota Técnica Quiz Kotlin §12 & Nota Técnica Concurso App §10):
 * Responde em segundos: "Estou no caminho de passar?"
 * - Índice de Prontidão vs. Nota de Corte Histórica
 * - Matriz Confiança × Acerto (destacando Erros com Alta Confiança)
 * - Barras de desempenho por disciplina ordenadas da mais crítica para a mais forte
 * - Heatmap de atividade (estilo GitHub)
 */
@Composable
fun ProgressScreen(
    analytics: ProgressAnalyticsDto
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            CognivaBrandHeader(
                ecosystemProduct = "Cogniva Insights",
                subtitle = "Analytics, Matriz de Confiança & Diagnóstico"
            )
        }

        // 1. Projeção & Índice de Prontidão vs. Nota de Corte Histórica (§12)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Índice de Prontidão vs. Nota de Corte Histórica",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No ritmo atual, índice estimado ${analytics.readinessIndexScore.toInt()}/100 · Nota de corte histórica de referência: ${analytics.historicalCutoffReference.toInt()}/100",
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
                        color = if (analytics.readinessIndexScore >= analytics.historicalCutoffReference) CorrectGreen else ReviewAmber
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
                            color = CorrectGreen
                        )
                    }
                }
            }
        }

        // 2. Matriz Metacognitiva: Confiança × Acerto (Nota Técnica Concurso App §8.2)
        item {
            val cm = analytics.confidenceMatrix
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                            color = CorrectGreen,
                            modifier = Modifier.weight(1f)
                        )
                        QuadrantBox(
                            title = "Acerto Frágil",
                            subtitle = "Acerto no Chute/Dúvida",
                            count = cm.lowConfidenceCorrect,
                            color = ReviewAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuadrantBox(
                            title = "Ponto Cego Crítico",
                            subtitle = "Erro c/ Alta Confiança",
                            count = cm.highConfidenceError,
                            color = ErrorRed,
                            modifier = Modifier.weight(1f)
                        )
                        QuadrantBox(
                            title = "Lacuna Mapeada",
                            subtitle = "Erro c/ Baixa Confiança",
                            count = cm.lowConfidenceError,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. Heatmap de Consistência Diária (Estilo GitHub — §12)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CorrectGreen.copy(alpha = alpha))
                            )
                        }
                    }
                }
            }
        }

        // 4. Desempenho por Disciplina (Ordenado por Pior Desempenho — §12)
        item {
            Text(
                text = "Domínio por Disciplina (Prioridade de Ataque)",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(analytics.disciplinesSortedByWeakness) { item ->
            val barColor = when {
                item.accuracyPct < 60.0 -> ErrorRed
                item.accuracyPct < 75.0 -> ReviewAmber
                else -> CorrectGreen
            }
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${item.totalAttempts} tentativas · ${item.masteredQuestions} dominadas · ${item.pointsAccumulated} pts SRS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(12.dp)
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
