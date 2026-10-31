package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.ConfidenceBadge
import br.com.concursoprep.app.domain.model.ContestDiffScreenDto
import br.com.concursoprep.app.domain.model.DisciplineSyllabusGroupDto
import br.com.concursoprep.app.domain.model.SyllabusTopicDto
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.LocalExtendedColors

/**
 * Tela "Concursos & Edital Verticalizado":
 * - Exibe a cobertura real do edital (oficial ou estimado).
 * - Selos de confiança clicáveis que abrem Bottom Sheet com as fontes.
 * - Banner de transição "Previsto -> Edital Publicado" com Tela de Diff.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContestsScreen(
    selectedContestId: String,
    availableContests: List<Pair<String, String>>,
    syllabusGroups: List<DisciplineSyllabusGroupDto>,
    diffData: ContestDiffScreenDto?,
    onSelectContest: (String) -> Unit,
    onPracticeDiscipline: (String) -> Unit,
    onApplyDiffConsent: (String) -> Unit = {}
) {
    var selectedTopicForSourceSheet by remember { mutableStateOf<SyllabusTopicDto?>(null) }
    var showEditalDiffSheet by remember { mutableStateOf(false) }
    val ext = LocalExtendedColors.current

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            CognivaBrandHeader(
                ecosystemProduct = "Cogniva Planner",
                subtitle = "Edital Verticalizado · Cobertura Real"
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Seletor de abas de concursos uniforme e sem emojis estáticos
            val selectedTabIndex = availableContests
                .indexOfFirst { it.first == selectedContestId }
                .coerceAtLeast(0)

            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                availableContests.forEachIndexed { index, (code, rawLabel) ->
                    val isSelected = selectedContestId == code
                    val cleanLabel = rawLabel.replace(Regex("^[🔴🟡⚪\\s]+"), "").trim()
                    val indicatorColor = when (code) {
                        "CPNU" -> ext.error
                        "RFB" -> ext.warning
                        else -> ext.neutral
                    }

                    Tab(
                        selected = isSelected,
                        onClick = { onSelectContest(code) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(indicatorColor)
                            )
                            Text(
                                text = cleanLabel,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Banner de Transição "Previsto -> Edital Publicado"
            if (diffData != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                text = "Edital Publicado! Comparativo disponível",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${diffData.confirmedTopics.size} confirmados · ${diffData.newTopics.size} novos · ${diffData.removedTopics.size} arquivados",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                            contentDescription = "Ver Diff do Edital",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Árvore do Edital Verticalizado com chaves estáveis
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = syllabusGroups,
                    key = { group -> group.discipline }
                ) { group ->
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
                                    color = if (group.disciplineMasteryPct >= 75.0) ext.correct else MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (group.disciplineMasteryPct.toFloat() / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (group.disciplineMasteryPct >= 75.0) ext.correct else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Tópicos da Disciplina padronizados
                            group.topics.forEach { node ->
                                TopicTreeRow(
                                    depth = 1,
                                    title = node.topic.title,
                                    statusLabel = node.statusLabel,
                                    isEstimated = node.topic.estimation.isEstimated,
                                    confidenceBadge = node.topic.estimation.confidenceBadge,
                                    onClick = { selectedTopicForSourceSheet = node.topic }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            // Botão uniforme fillMaxWidth(min = 48dp)
                            OutlinedButton(
                                onClick = { onPracticeDiscipline(group.discipline) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(
                                    text = "Treinar ${group.discipline}",
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

    // Bottom Sheet 1: Transparência de Fontes do Tópico Estimado
    selectedTopicForSourceSheet?.let { topic ->
        ModalBottomSheet(
            onDismissRequest = { selectedTopicForSourceSheet = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (topic.estimation.isEstimated) "Base da Estimativa Editorial" else "Tópico Confirmado no Edital Oficial",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = topic.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
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
                        text = "Pertence ao Tronco Comum (${topic.commonTrunkId}): seu estudo neste tópico avança múltiplos concursos.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ext.correct
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { selectedTopicForSourceSheet = null },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Fechar")
                    }
                    Button(
                        onClick = {
                            val disc = topic.discipline
                            selectedTopicForSourceSheet = null
                            onPracticeDiscipline(disc)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Treinar Tópico")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Bottom Sheet 2: Tela de Diff "Previsto -> Edital Publicado"
    if (showEditalDiffSheet && diffData != null) {
        ModalBottomSheet(
            onDismissRequest = { showEditalDiffSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Comparativo de Edital · ${diffData.contestName}",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Banca Confirmada: ${diffData.confirmedBoard}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${diffData.confirmedTopics.size} tópicos que você já estudava foram confirmados!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ext.correct
                )
                if (diffData.newTopics.isNotEmpty()) {
                    Text(
                        text = "Novos tópicos incluídos no edital (${diffData.newTopics.size}):",
                        style = MaterialTheme.typography.titleMedium,
                        color = ext.warning
                    )
                    diffData.newTopics.forEach { t ->
                        Text("• ${t.discipline}: ${t.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (diffData.removedTopics.isNotEmpty()) {
                    Text(
                        text = "Tópicos removidos (${diffData.removedTopics.size} — arquivados sem perder seu histórico):",
                        style = MaterialTheme.typography.titleMedium,
                        color = ext.error
                    )
                    diffData.removedTopics.forEach { t ->
                        Text("• ${t.discipline}: ${t.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        showEditalDiffSheet = false
                        onApplyDiffConsent(diffData.contestId)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Atualizar meu cronograma com consentimento")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Componente padronizado para nós da Árvore de Tópicos do Edital.
 * Substitui a arte ASCII "└ ☐" por layout nativo com ícones vetoriais e indentação real.
 */
@Composable
fun TopicTreeRow(
    depth: Int,
    title: String,
    statusLabel: String,
    isEstimated: Boolean,
    confidenceBadge: ConfidenceBadge,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val (statusIcon, statusColor) = when (statusLabel) {
        "DOMINADO" -> Icons.Default.CheckCircle to ext.correct
        "REVISAO" -> Icons.Default.Replay to ext.warning
        "QUESTOES" -> Icons.Default.Edit to MaterialTheme.colorScheme.primary
        else -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
    }

    val (badgeLabel, badgeColor) = when (confidenceBadge) {
        ConfidenceBadge.HIGH -> "alta" to ext.correct
        ConfidenceBadge.MEDIUM -> "média" to ext.warning
        ConfidenceBadge.LOW -> "baixa" to ext.neutral
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = (depth * 14).dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = statusIcon,
            contentDescription = statusLabel,
            tint = statusColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isEstimated) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (isEstimated) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Text(
                text = if (isEstimated) "[est] $badgeLabel" else "Oficial",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
