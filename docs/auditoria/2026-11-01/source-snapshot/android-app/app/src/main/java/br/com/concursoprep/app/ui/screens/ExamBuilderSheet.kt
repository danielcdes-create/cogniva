package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.domain.exam.ExamDraft
import br.com.concursoprep.app.domain.exam.StoredExam
import br.com.concursoprep.app.domain.exam.StoredSubject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExamFormBottomSheet(
    existingExams: List<StoredExam>,
    onDismiss: () -> Unit,
    onSave: (ExamDraft) -> Unit
) {
    val trunks = remember { ExamCatalog.trunks() }
    var step by remember { mutableIntStateOf(1) }
    var trunkSlug by remember { mutableStateOf(trunks.firstOrNull()?.slug ?: "fiscal") }
    var creationMode by remember { mutableStateOf("template") }
    var cloneFromId by remember { mutableStateOf(existingExams.firstOrNull()?.examId) }
    var organ by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    val boards = remember { listOf("FGV", "Cebraspe", "Cesgranrio", "FCC", "Vunesp", "A definir") }
    val selectedBoards = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var status by remember { mutableStateOf("RUMOR_PREVISTO") }
    var dateMode by remember { mutableStateOf("ESTIMATED_WINDOW") }
    var exactDate by remember { mutableStateOf("2027-06-01") }
    var windowLabel by remember { mutableStateOf("1º sem/2027") }
    var ladderPriority by remember { mutableIntStateOf(2) }
    var cutoff by remember { mutableStateOf(75f) }
    var subjects by remember(trunkSlug, creationMode, cloneFromId) {
        mutableStateOf(
            when (creationMode) {
                "clone" -> existingExams.firstOrNull { it.examId == cloneFromId }?.subjects ?: ExamCatalog.subjectsForTrunk(trunkSlug)
                "blank" -> emptyList()
                else -> ExamCatalog.subjectsForTrunk(trunkSlug).filter { it.relevanceTier == "CORE" }
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Novo concurso · passo $step de 3",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            when (step) {
                1 -> {
                    Text("Tronco de carreira", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        trunks.forEach { trunk ->
                            FilterChip(
                                selected = trunkSlug == trunk.slug,
                                onClick = { trunkSlug = trunk.slug },
                                label = { Text(trunk.name) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    Text("Modo de criação", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "template" to "Template do tronco",
                            "clone" to "Clonar concurso",
                            "blank" to "Criar do zero"
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = creationMode == mode,
                                onClick = { creationMode = mode },
                                label = { Text(label) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    if (creationMode == "clone") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            existingExams.forEach { exam ->
                                FilterChip(
                                    selected = cloneFromId == exam.examId,
                                    onClick = { cloneFromId = exam.examId },
                                    label = { Text(exam.shortName) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = organ,
                        onValueChange = { organ = it },
                        label = { Text("Órgão ou concurso") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = role,
                        onValueChange = { role = it },
                        label = { Text("Cargo") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text("Bancas", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        boards.forEach { board ->
                            val selected = selectedBoards.contains(board)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (selected) selectedBoards.remove(board) else selectedBoards.add(board)
                                },
                                label = { Text(board) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
                2 -> {
                    Text("Status do edital", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "RUMOR_PREVISTO" to "Pré-edital",
                            "AUTORIZADO" to "Autorizado",
                            "BANCA_DEFINIDA" to "Banca definida",
                            "EDITAL_PUBLICADO" to "Edital publicado"
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = status == value,
                                onClick = { status = value },
                                label = { Text(label) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    Text("Data da prova", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = dateMode == "EXACT_DATE",
                            onClick = { dateMode = "EXACT_DATE" },
                            label = { Text("Data exata") },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        FilterChip(
                            selected = dateMode == "ESTIMATED_WINDOW",
                            onClick = { dateMode = "ESTIMATED_WINDOW" },
                            label = { Text("Janela estimada") },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    if (dateMode == "EXACT_DATE") {
                        OutlinedTextField(
                            value = exactDate,
                            onValueChange = { exactDate = it },
                            label = { Text("Data ISO (AAAA-MM-DD)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("1º sem/2027", "2º sem/2027", "1º sem/2028", "Sem previsão").forEach { label ->
                                FilterChip(
                                    selected = windowLabel == label,
                                    onClick = {
                                        windowLabel = label
                                        dateMode = if (label == "Sem previsão") "UNDEFINED" else "ESTIMATED_WINDOW"
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                    Text("Posição na escada", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "Foco imediato", 2 to "Médio prazo", 3 to "Longo prazo").forEach { (priority, label) ->
                            FilterChip(
                                selected = ladderPriority == priority,
                                onClick = { ladderPriority = priority },
                                label = { Text(label) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    Text(
                        text = "Nota de corte histórica: ${cutoff.roundToInt()}%",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Slider(
                        value = cutoff,
                        onValueChange = { cutoff = it },
                        valueRange = 70f..85f
                    )
                }
                else -> {
                    Text("Disciplinas, pesos e questões", style = MaterialTheme.typography.titleMedium)
                    if (subjects.isEmpty()) {
                        Text(
                            text = "Nenhuma disciplina ainda. Adicione uma do catálogo canônico.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    subjects.forEach { subject ->
                        SubjectWeightRow(
                            subject = subject,
                            onChange = { updated ->
                                subjects = subjects.map { current ->
                                    if (current.localSubjectId == updated.localSubjectId) updated else current
                                }
                            }
                        )
                    }
                    val catalog = ExamCatalog.subjectsForTrunk(trunkSlug)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        catalog.filter { candidate ->
                            subjects.none { it.canonicalSubjectId == candidate.canonicalSubjectId }
                        }.forEach { candidate ->
                            OutlinedButton(
                                onClick = { subjects = subjects + candidate },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("+ ${candidate.displayName}")
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (step > 1) {
                    OutlinedButton(
                        onClick = { step -= 1 },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Voltar") }
                }
                Button(
                    onClick = {
                        if (step < 3) {
                            step += 1
                        } else {
                            val name = organ.ifBlank { trunks.first { it.slug == trunkSlug }.name }
                            onSave(
                                ExamDraft(
                                    title = "$name · ${role.ifBlank { "Cargo" }}",
                                    shortName = name,
                                    institution = name,
                                    role = role.ifBlank { "Cargo" },
                                    trunkSlug = trunkSlug,
                                    lifecycleStatus = status,
                                    examBoards = selectedBoards.toList(),
                                    dateType = dateMode,
                                    exactDateIso = if (dateMode == "EXACT_DATE") exactDate else null,
                                    estimatedWindowLabel = if (dateMode == "ESTIMATED_WINDOW") windowLabel else null,
                                    estimatedSortDateIso = if (dateMode == "EXACT_DATE") exactDate else "2027-06-30",
                                    historicalCutoffScore = cutoff.toDouble(),
                                    ladderPriority = ladderPriority,
                                    subjects = subjects
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(if (step < 3) "Continuar" else "Salvar na escada")
                }
            }
            Spacer(modifier = Modifier.heightIn(min = 8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectWeightRow(
    subject: StoredSubject,
    onChange: (StoredSubject) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(subject.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1.0, 1.5, 2.0, 3.0).forEach { weight ->
                FilterChip(
                    selected = subject.weight == weight,
                    onClick = { onChange(subject.copy(weight = weight)) },
                    label = { Text("${weight}x") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            listOf(10, 15, 20).forEach { count ->
                FilterChip(
                    selected = subject.questionCount == count,
                    onClick = { onChange(subject.copy(questionCount = count)) },
                    label = { Text("$count q") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}
