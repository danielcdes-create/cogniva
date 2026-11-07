package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.domain.exam.StoredSubject
import br.com.concursoprep.app.domain.exam.StoredTopic
import java.util.UUID

/** Maximum chips shown at once so the list never becomes a wall of buttons. */
internal const val MAX_VISIBLE_CHOICES = 12

internal fun categoryLabel(value: String) = when (value) {
    "CONHECIMENTOS_GERAIS" -> "Conhecimentos gerais"
    "CONHECIMENTOS_ESPECIFICOS" -> "Conhecimentos específicos"
    "DISCURSIVA_ESTUDO_DE_CASO" -> "Discursiva / estudo de caso"
    else -> "Outros"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubjectEditor(
    subject: StoredSubject,
    onChange: (StoredSubject) -> Unit,
    onRemove: () -> Unit,
    onAddStage: () -> Unit,
    onValidity: (Boolean) -> Unit
) {
    var expanded by rememberSaveable(subject.localSubjectId) { mutableStateOf(false) }
    var confirmRemove by rememberSaveable(subject.localSubjectId) { mutableStateOf(false) }
    var topicName by rememberSaveable(subject.localSubjectId) { mutableStateOf("") }
    var weight by rememberSaveable(subject.localSubjectId) { mutableStateOf(subject.weight.toString()) }
    var count by rememberSaveable(subject.localSubjectId) { mutableStateOf(subject.questionCount?.toString().orEmpty()) }
    var chooseSubject by rememberSaveable(subject.localSubjectId) { mutableStateOf(false) }
    var subjectQuery by rememberSaveable(subject.localSubjectId) { mutableStateOf("") }
    var topicQuery by rememberSaveable(subject.localSubjectId) { mutableStateOf("") }
    var linkingTopic by rememberSaveable(subject.localSubjectId) { mutableStateOf<String?>(null) }
    val validWeight = weight.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in 0.01..100.0 } == true
    val validCount = count.isBlank() || count.toIntOrNull()?.let { it in 0..500 } == true
    val activeTopics = subject.topics.count { it.diffStatus != "ARQUIVADO" }
    val isCustom = subject.canonicalSubjectId.startsWith("custom-")
    LaunchedEffect(validWeight, validCount, subject.displayName, subject.topics) {
        onValidity(validWeight && validCount && subject.displayName.isNotBlank() && subject.topics.all { it.displayTitle.isNotBlank() })
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(subject.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                listOf(
                    subject.stage.ifBlank { "Etapa não definida" },
                    categoryLabel(subject.category),
                    "${subject.questionCount ?: 0} questões",
                    "$activeTopics tópicos ativos"
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
            if (activeTopics == 0) {
                Text(
                    "Sem tópicos ativos: a disciplina não aparecerá no edital nem na prática.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Recolher" else "Editar disciplina e tópicos")
                }
                TextButton(onClick = { confirmRemove = true }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            }
            if (expanded) {
                HorizontalDivider()
                OutlinedTextField(subject.displayName, { onChange(subject.copy(displayName = it.take(150))) }, label = { Text("Nome da disciplina") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(subject.stage, { onChange(subject.copy(stage = it.take(120))) }, label = { Text("Etapa (ex.: 1ª etapa · Prova objetiva)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Grupo de conhecimentos", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CONHECIMENTOS_GERAIS", "CONHECIMENTOS_ESPECIFICOS", "DISCURSIVA_ESTUDO_DE_CASO", "OUTROS").forEach { group ->
                        FilterChip(selected = subject.category == group, onClick = { onChange(subject.copy(category = group)) }, label = { Text(categoryLabel(group)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(weight, { weight = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { n -> n.isFinite() && n in 0.01..100.0 }?.let { n -> onChange(subject.copy(weight = n)) } }, isError = !validWeight, label = { Text("Peso") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(count, { count = it; if (it.isBlank()) onChange(subject.copy(questionCount = null)) else it.toIntOrNull()?.takeIf { n -> n in 0..500 }?.let { n -> onChange(subject.copy(questionCount = n)) } }, isError = !validCount, label = { Text("Questões") }, modifier = Modifier.weight(1f), singleLine = true)
                }

                Text("Tópicos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                subject.topics.forEach { topic ->
                    key(topic.localTopicId) {
                        val archived = topic.diffStatus == "ARQUIVADO"
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                topic.displayTitle,
                                { value -> onChange(subject.copy(topics = subject.topics.map { if (it.localTopicId == topic.localTopicId) it.copy(displayTitle = value.take(500)) else it })) },
                                label = { Text(if (archived) "Tópico arquivado" else "Tópico") },
                                supportingText = { Text(ExamCatalog.topicLabel(topic)) },
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onChange(subject.copy(topics = subject.topics.map { if (it.localTopicId == topic.localTopicId) it.copy(diffStatus = if (archived) "PRE_EDITAL_BASE" else "ARQUIVADO") else it })) }) {
                                Icon(if (archived) Icons.Default.Unarchive else Icons.Default.Archive, contentDescription = if (archived) "Restaurar tópico" else "Arquivar tópico")
                            }
                            IconButton(onClick = { onChange(subject.copy(topics = subject.topics.filterNot { it.localTopicId == topic.localTopicId })) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Excluir tópico")
                            }
                        }
                        if (!isCustom && topic.canonicalTopicId.startsWith("custom-")) {
                            TextButton(onClick = { linkingTopic = topic.localTopicId; topicQuery = "" }) { Text("Associar tópico ao catálogo") }
                        }
                    }
                }

                if (!isCustom) {
                    val candidates = ExamCatalog.catalogTopics(subject.canonicalSubjectId).filter { target ->
                        subject.topics.none { it.canonicalTopicId == target.canonicalTopicId && it.localTopicId != linkingTopic } &&
                            ExamCatalog.topicLabel(target).contains(topicQuery, true)
                    }
                    Text(if (linkingTopic != null) "Escolha o assunto do catálogo para associar" else "Adicionar assunto ou subassunto do catálogo", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(topicQuery, { topicQuery = it }, label = { Text("Buscar assunto ou subassunto") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (candidates.isEmpty()) Text("Todos os assuntos do catálogo já estão na grade.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        candidates.take(MAX_VISIBLE_CHOICES).forEach { target ->
                            AssistChip(
                                onClick = {
                                    val local = linkingTopic
                                    onChange(subject.copy(topics = if (local == null) subject.topics + target.copy(localTopicId = UUID.randomUUID().toString()) else subject.topics.map { if (it.localTopicId == local) it.copy(canonicalTopicId = target.canonicalTopicId) else it }))
                                    linkingTopic = null; topicQuery = ""
                                },
                                label = { Text(target.displayTitle) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                            )
                        }
                    }
                    if (candidates.size > MAX_VISIBLE_CHOICES) Text("Mais ${candidates.size - MAX_VISIBLE_CHOICES} assuntos: refine a busca.", style = MaterialTheme.typography.bodySmall)
                    if (linkingTopic != null) TextButton(onClick = { linkingTopic = null }) { Text("Cancelar associação") }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(topicName, { topicName = it.take(500) }, label = { Text("Novo tópico") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedButton(enabled = topicName.isNotBlank(), onClick = {
                        val id = "custom-${UUID.randomUUID()}"
                        onChange(subject.copy(topics = subject.topics + StoredTopic(id, id, subject.canonicalSubjectId, topicName.trim(), "PRE_EDITAL_BASE")))
                        topicName = ""
                    }) { Text("Adicionar tópico") }
                }

                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onAddStage) { Text("Adicionar outra etapa") }
                    if (subject.topics.isNotEmpty()) {
                        val archive = activeTopics > 0
                        TextButton(onClick = { onChange(subject.copy(topics = subject.topics.map { it.copy(diffStatus = if (archive) "ARQUIVADO" else "PRE_EDITAL_BASE") })) }) {
                            Text(if (archive) "Arquivar disciplina" else "Restaurar disciplina")
                        }
                    }
                    TextButton(onClick = { chooseSubject = !chooseSubject }) { Text(if (isCustom) "Associar ao catálogo" else "Trocar vínculo do catálogo") }
                }
                if (chooseSubject) {
                    Text("O nome e os tópicos locais serão preservados. Tópicos sem vínculo ficarão aguardando associação.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(subjectQuery, { subjectQuery = it }, label = { Text("Buscar disciplina ou nome alternativo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    val targets = ExamCatalog.allSubjects().filter { it.canonicalSubjectId != subject.canonicalSubjectId && ExamCatalog.matchesSubject(it, subjectQuery) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targets.take(MAX_VISIBLE_CHOICES).forEach { target ->
                            AssistChip(onClick = { onChange(ExamCatalog.associateSubject(subject, target)); chooseSubject = false; subjectQuery = "" }, label = { Text(target.displayName) })
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Excluir ${subject.displayName}?") },
            text = { Text("A disciplina e seus tópicos saem desta grade ao salvar. Seu histórico de respostas é mantido.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Excluir disciplina") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar") } }
        )
    }
}
