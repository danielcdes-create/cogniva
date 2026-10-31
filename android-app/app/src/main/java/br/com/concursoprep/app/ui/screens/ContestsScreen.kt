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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.ConfidenceBadge
import br.com.concursoprep.app.domain.model.ContestDiffScreenDto
import br.com.concursoprep.app.domain.model.DisciplineSyllabusGroupDto
import br.com.concursoprep.app.domain.model.SyllabusTopicDto
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.CorrectGreen
import br.com.concursoprep.app.ui.theme.ErrorRed
import br.com.concursoprep.app.ui.theme.EstimatedNeutral
import br.com.concursoprep.app.ui.theme.ReviewAmber

/**
 * Tela "Concursos & Edital Verticalizado" (Nota Técnica Quiz Kotlin §6, §7 e §8):
 * - Exibe a cobertura real do edital (oficial ou estimado).
 * - Selos de confiança clicáveis (🟢 Alta, 🟡 Média, ⚪ Baixa) que abrem Bottom Sheet com as fontes.
 * - Banner de transição "Previsto -> Edital Publicado" com Tela de Diff (tópicos confirmados, novos e removidos).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContestsScreen(
    selectedContestId: String,
    availableContests: List<Pair<String, String>>,
    syllabusGroups: List<DisciplineSyllabusGroupDto>,
    diffData: ContestDiffScreenDto?,
    onSelectContest: (String) -> Unit,
    onPracticeDiscipline: (String) -> Unit
) {
    var selectedTopicForSourceSheet by remember { mutableStateOf<SyllabusTopicDto?>(null) }
    var showEditalDiffSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        CognivaBrandHeader(
            ecosystemProduct = "Cogniva Planner",
            subtitle = "Edital Verticalizado · Cobertura Real"
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Seletor rápido de concursos em escada (CPNU, RFB, BNDES)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            availableContests.forEach { (code, label) ->
                FilterChip(
                    selected = selectedContestId == code,
                    onClick = { onSelectContest(code) },
                    label = { Text(label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Banner de Transição "Previsto -> Edital Publicado" (§8 — O Diferencial de Marketing)
        if (diffData != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showEditalDiffSheet = true }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔴 Edital Publicado! Comparativo disponível",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "✅ ${diffData.confirmedTopics.size} confirmados · 🆕 ${diffData.newTopics.size} novos · ➖ ${diffData.removedTopics.size} arquivados",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.CompareArrows,
                        contentDescription = "Ver Diff do Edital",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Árvore do Edital Verticalizado
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(syllabusGroups) { group ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.blockName.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = group.discipline,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "${group.disciplineMasteryPct.toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (group.disciplineMasteryPct >= 75.0) CorrectGreen else MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (group.disciplineMasteryPct.toFloat() / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (group.disciplineMasteryPct >= 75.0) CorrectGreen else MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Tópicos da Disciplina
                        group.topics.forEach { node ->
                            val statusIcon = when (node.statusLabel) {
                                "DOMINADO" -> "✅"
                                "REVISAO" -> "🔁"
                                "QUESTOES" -> "✍️"
                                else -> "◻"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "└ $statusIcon ", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = node.topic.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))

                                // Selo de Confiança da Estimativa (Toque abre Bottom Sheet de Fontes)
                                val (badgeLabel, badgeColor) = when (node.topic.estimation.confidenceBadge) {
                                    ConfidenceBadge.HIGH -> "● alta" to CorrectGreen
                                    ConfidenceBadge.MEDIUM -> "● média" to ReviewAmber
                                    ConfidenceBadge.LOW -> "● baixa" to EstimatedNeutral
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = badgeColor.copy(alpha = 0.15f),
                                    modifier = Modifier.clickable {
                                        selectedTopicForSourceSheet = node.topic
                                    }
                                ) {
                                    Text(
                                        text = if (node.topic.estimation.isEstimated) "[est] $badgeLabel" else "Oficial",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onPracticeDiscipline(group.discipline) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Treinar ${group.discipline}")
                        }
                    }
                }
            }
        }
    }

    // Bottom Sheet 1: Transparência de Fontes do Tópico Estimado (§7.2)
    selectedTopicForSourceSheet?.let { topic ->
        ModalBottomSheet(onDismissRequest = { selectedTopicForSourceSheet = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (topic.estimation.isEstimated) "Base da Estimativa Editorial" else "Tópico Confirmado no Edital Oficial",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(text = topic.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Fundamentação: ${topic.estimation.rationale}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (topic.estimation.referenceExams.isNotEmpty()) {
                    Text(
                        text = "Provas de referência: ${topic.estimation.referenceExams.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (topic.commonTrunkId != null) {
                    Text(
                        text = "🔗 Pertence ao Tronco Comum (${topic.commonTrunkId}): seu estudo neste tópico avança múltiplos concursos.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CorrectGreen
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Bottom Sheet 2: Tela de Diff "Previsto -> Edital Publicado" (§8)
    if (showEditalDiffSheet && diffData != null) {
        ModalBottomSheet(onDismissRequest = { showEditalDiffSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Comparativo de Edital · ${diffData.contestName}",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "✏️ Banca Confirmada: ${diffData.confirmedBoard}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "✅ ${diffData.confirmedTopics.size} tópicos que você já estudava foram confirmados!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = CorrectGreen
                )
                if (diffData.newTopics.isNotEmpty()) {
                    Text(
                        text = "🆕 Novos tópicos incluídos no edital (${diffData.newTopics.size}):",
                        style = MaterialTheme.typography.titleMedium,
                        color = ReviewAmber
                    )
                    diffData.newTopics.forEach { t ->
                        Text("• ${t.discipline}: ${t.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (diffData.removedTopics.isNotEmpty()) {
                    Text(
                        text = "➖ Tópicos removidos (${diffData.removedTopics.size} — arquivados sem perder seu histórico):",
                        style = MaterialTheme.typography.titleMedium,
                        color = ErrorRed
                    )
                    diffData.removedTopics.forEach { t ->
                        Text("• ${t.discipline}: ${t.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showEditalDiffSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Atualizar meu cronograma com consentimento")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
