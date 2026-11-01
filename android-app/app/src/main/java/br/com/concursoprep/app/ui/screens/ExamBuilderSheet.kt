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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExamFormBottomSheet(
    existingExams: List<StoredExam>,
    editingExam: StoredExam? = null,
    onDismiss: () -> Unit,
    onSave: (ExamDraft) -> Unit
) {
    val trunks = remember { ExamCatalog.trunks() }
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(1) }
    var trunkSlug by rememberSaveable { mutableStateOf(editingExam?.trunkSlug ?: trunks.firstOrNull()?.slug ?: "fiscal") }
    var creationMode by rememberSaveable { mutableStateOf(if(editingExam!=null)"clone"else"template") }
    var cloneFromId by rememberSaveable { mutableStateOf(editingExam?.examId ?: existingExams.firstOrNull()?.examId) }
    var organ by rememberSaveable { mutableStateOf(editingExam?.institution ?: "") }
    var role by rememberSaveable { mutableStateOf(editingExam?.role ?: "") }
    val boards = remember { listOf("FGV", "Cebraspe", "Cesgranrio", "FCC", "Vunesp", "A definir") }
    var selectedBoards by rememberSaveable { mutableStateOf(editingExam?.examBoards.orEmpty()) }
    var status by rememberSaveable { mutableStateOf(editingExam?.lifecycleStatus ?: "RUMOR_PREVISTO") }
    var dateMode by rememberSaveable { mutableStateOf(editingExam?.dateType ?: "UNDEFINED") }
    var exactDate by rememberSaveable { mutableStateOf(editingExam?.exactDateIso ?: "") }
    var windowLabel by rememberSaveable { mutableStateOf(editingExam?.estimatedWindowLabel ?: "Sem previsão") }
    var ladderPriority by rememberSaveable { mutableIntStateOf(editingExam?.ladderPriority ?: 2) }
    var wrongPenalty by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(editingExam?.wrongAnswerPenalty ?: 0.0) }
    var cutoff by rememberSaveable { mutableStateOf(editingExam?.historicalCutoffScore?.toFloat() ?: 0f) }
    val subjectSaver=Saver<List<StoredSubject>,String>(save={Json.encodeToString(it)},restore={Json.decodeFromString(it)})
    var subjects by rememberSaveable(trunkSlug, creationMode, cloneFromId,stateSaver=subjectSaver) {
        mutableStateOf(
            when (creationMode) {
                "clone" -> existingExams.firstOrNull { it.examId == cloneFromId }?.subjects?.filter { it.topics.any { topic->topic.diffStatus!="ARQUIVADO" } } ?: ExamCatalog.subjectsForTrunk(trunkSlug)
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
                text = "${if(editingExam==null) "Novo concurso" else "Editar concurso"} · passo $step de 3",
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
                                    selectedBoards = if (selected) selectedBoards-board else selectedBoards+board
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
                    Text("Desconto por resposta errada (conforme a regra da prova)")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(0.0,0.25,0.5,1.0).forEach { value ->FilterChip(selected=wrongPenalty==value,onClick={wrongPenalty=value},label={Text(value.toString())}) }
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
                        valueRange = 0f..100f
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
                                    estimatedSortDateIso = when(dateMode) {
                                        "EXACT_DATE" -> exactDate
                                        "ESTIMATED_WINDOW" -> mapOf("1º sem/2027" to "2027-06-30","2º sem/2027" to "2027-12-31","1º sem/2028" to "2028-06-30")[windowLabel] ?: editingExam?.estimatedSortDateIso.orEmpty()
                                        else -> ""
                                    },
                                    historicalCutoffScore = cutoff.toDouble(),
                                    ladderPriority = ladderPriority,
                                    subjects = subjects,
                                            wrongAnswerPenalty = wrongPenalty
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
