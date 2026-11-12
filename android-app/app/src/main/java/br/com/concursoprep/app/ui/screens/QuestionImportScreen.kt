package br.com.concursoprep.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.ui.QuestionImportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val importPrettyJson = Json { prettyPrint = true }
private val bulkExample = """[
  {
    "question_type": "MULTIPLE_CHOICE",
    "question": "Exemplo: quanto é 2 + 2?",
    "answers": ["1", "2", "3", "4", "5"],
    "correct_answer_indices": [3],
    "grading": null
  }
]"""

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionImportScreen(viewModel: QuestionImportViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val draft = state.draft
    val editable = !state.loading && state.storageReady && !state.busy && !draft.submitted
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileError by remember { mutableStateOf<String?>(null) }
    var reviewed by remember(draft.preview) { mutableStateOf(false) }
    var showJson by remember { mutableStateOf(false) }
    var chooseSubject by remember { mutableStateOf(false) }
    var chooseTopic by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { stream ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= 2 * 1024 * 1024) { "Arquivo excede 2 MiB. Lotes de questões devem ter até 512 KiB." }
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toString("UTF-8").removePrefix("\uFEFF")
                    }
                }
                viewModel.importText(text)
                fileError = null
            } catch (e: Exception) { fileError = e.message ?: "Não foi possível ler o arquivo." }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = viewModel.exportDraft()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.bufferedWriter(Charsets.UTF_8).use { it.write(text) } }
            } catch (e: Exception) { fileError = e.message ?: "Falha ao exportar." }
        }
    }
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = onBack) { Text("Voltar aos ajustes") }
                Text("Inserir questões", style = MaterialTheme.typography.headlineSmall)
                Text("Individual ou em lote · revisão antes do envio", style = MaterialTheme.typography.bodyMedium)
                Text("Rascunho salvo neste aparelho, separado por conta. IA e envio exigem conexão e acesso administrativo.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.mode == "SINGLE", onClick = { viewModel.edit { it.copy(mode = "SINGLE") } }, enabled = editable && draft.assistanceId == null, label = { Text("Individual") })
                    FilterChip(selected = draft.mode == "BULK", onClick = { viewModel.edit { it.copy(mode = "BULK") } }, enabled = editable, label = { Text("Lote / IA") })
                    OutlinedButton(onClick = { exportFile.launch("cogniva-rascunho-questoes.json") }, enabled = !state.loading) { Text("Exportar rascunho") }
                }
            }
            if (state.loading || state.busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            (state.message ?: fileError)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (!state.allowed) item {
                Text("Você pode preparar o rascunho offline. Para validar e inserir, conecte uma conta com acesso administrativo.")
                OutlinedButton(onClick = viewModel::checkAccess, enabled = !state.busy) { Text("Verificar acesso") }
            }
            if (draft.mode == "SINGLE") {
                item {
                    Text("Origem do conteúdo")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.originKind == "LEGACY_IMPORT", onClick = { viewModel.edit { it.copy(originKind = "LEGACY_IMPORT", confirmOrigin = false) } }, enabled = editable, label = { Text("Transcrição / importação") })
                        FilterChip(selected = draft.originKind == "OFFICIAL_EXAM", onClick = { viewModel.edit { it.copy(originKind = "OFFICIAL_EXAM", confirmOrigin = false) } }, enabled = editable, label = { Text("Prova oficial") })
                        FilterChip(selected = draft.originKind == "AUTHOR_CREATED", onClick = { viewModel.edit { it.copy(originKind = "AUTHOR_CREATED", confirmOrigin = false) } }, enabled = editable, label = { Text("Autoria própria") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("MULTIPLE_CHOICE" to "Múltipla escolha", "TRUE_FALSE" to "Certo / Errado", "OPEN_ENDED" to "Discursiva").forEach { (kind, label) ->
                            FilterChip(selected = draft.kind == kind, onClick = { viewModel.edit { it.copy(kind = kind, correctIndex = -1) } }, enabled = editable, label = { Text(label) })
                        }
                    }
                    OutlinedTextField(value = draft.question, onValueChange = { value -> viewModel.edit { it.copy(question = value) } }, label = { Text("Enunciado") }, minLines = 4, enabled = editable, modifier = Modifier.fillMaxWidth())
                }
                if (draft.kind != "OPEN_ENDED") {
                    val options = if (draft.kind == "TRUE_FALSE") listOf("Certo", "Errado") else draft.answers
                    itemsIndexed(options) { index, answer ->
                        Column {
                            if (draft.kind == "MULTIPLE_CHOICE") OutlinedTextField(value = answer, onValueChange = { value -> viewModel.edit { it.copy(answers = it.answers.toMutableList().apply { set(index, value) }) } }, label = { Text("Alternativa ${'A' + index}") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                            FilterChip(selected = draft.correctIndex == index, onClick = { viewModel.edit { it.copy(correctIndex = index) } }, enabled = editable, label = { Text(if (draft.kind == "TRUE_FALSE") "$answer · gabarito" else "${'A' + index} é o gabarito") })
                        }
                    }
                } else item {
                    OutlinedTextField(value = draft.reference, onValueChange = { value -> viewModel.edit { it.copy(reference = value) } }, label = { Text("Resposta de referência") }, minLines = 3, enabled = editable, modifier = Modifier.fillMaxWidth())
                    Text("Rubrica: os pontos dos critérios devem somar 100. Critérios essenciais com zero impedem acerto confirmado.")
                    OutlinedTextField(value = draft.criteria, onValueChange = { value -> viewModel.edit { it.copy(criteria = value) } }, label = { Text("Critérios da rubrica (array JSON)") }, placeholder = { Text("[{\"criterion_id\":\"c1\",\"description\":\"...\",\"max_points\":100,\"essential\":true}]") }, minLines = 4, enabled = editable, modifier = Modifier.fillMaxWidth())
                    Text("Correção manual com revisão humana; cadastro não ativa correção automática.", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Text("Prova de origem e referência", style = MaterialTheme.typography.titleMedium)
                    Text("A origem registra de onde veio a questão. Na origem oficial, informe prova, instituição, banca, ano e fonte. Para editar uma questão já salva no Cogniva, use o Banco de questões no painel web.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = draft.sourceExamCode, onValueChange = { value -> viewModel.edit { it.copy(sourceExamCode = value, confirmOrigin = false) } }, label = { Text("Identificação da prova / cargo / caderno") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.institution, onValueChange = { value -> viewModel.edit { it.copy(institution = value, confirmOrigin = false) } }, label = { Text("Instituição / órgão") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.organizer, onValueChange = { value -> viewModel.edit { it.copy(organizer = value, confirmOrigin = false) } }, label = { Text("Banca organizadora") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.examYear, onValueChange = { value -> viewModel.edit { it.copy(examYear = value, confirmOrigin = false) } }, label = { Text("Ano da prova") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.questionNumber, onValueChange = { value -> viewModel.edit { it.copy(questionNumber = value, confirmOrigin = false) } }, label = { Text("Número da questão") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.level, onValueChange = { value -> viewModel.edit { it.copy(level = value) } }, label = { Text("Nível / dificuldade (opcional)") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.sourceUri, onValueChange = { value -> viewModel.edit { it.copy(sourceUri = value, confirmOrigin = false) } }, label = { Text("Endereço da fonte (URL)") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.sourceTitle, onValueChange = { value -> viewModel.edit { it.copy(sourceTitle = value, confirmOrigin = false) } }, label = { Text("Título do documento / fonte") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.sourceLocator, onValueChange = { value -> viewModel.edit { it.copy(sourceLocator = value, confirmOrigin = false) } }, label = { Text("Página / localização no documento") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draft.sourceDate, onValueChange = { value -> viewModel.edit { it.copy(sourceDate = value, confirmOrigin = false) } }, label = { Text("Data de consulta da fonte (AAAA-MM-DD)") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    Row {
                        Checkbox(checked = draft.confirmOrigin, onCheckedChange = { value -> viewModel.edit { it.copy(confirmOrigin = value) } }, enabled = editable)
                        Text("Consultei a fonte e conferi a origem informada.", Modifier.padding(top = 12.dp))
                    }
                    Text("Sem conferência, a origem transcrita fica pendente. Origem conferida não significa validade atual do conteúdo.", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Text("Classificação por conteúdo", style = MaterialTheme.typography.titleMedium)
                    Text("Disciplina → Assunto → Subassunto. Escolha o conteúdo mais específico cobrado; este vínculo é compartilhado entre concursos.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { chooseSubject = true }, enabled = editable) { Text(ExamCatalog.allSubjects().find { it.canonicalSubjectId == draft.subjectId }?.displayName ?: "Selecionar disciplina (opcional)") }
                    OutlinedButton(onClick = { chooseTopic = true }, enabled = editable && draft.subjectId.isNotBlank()) { Text(ExamCatalog.catalogTopics(draft.subjectId).find { it.canonicalTopicId == draft.topicId }?.let { ExamCatalog.topicLabel(it) } ?: "Selecionar tópico") }
                    Row {
                        Checkbox(checked = draft.confirmClassification, onCheckedChange = { value -> viewModel.edit { it.copy(confirmClassification = value) } }, enabled = editable && draft.topicId.isNotBlank())
                        Text("Conferi e confirmo este vínculo principal direto", modifier = Modifier.padding(top = 12.dp))
                    }
                }
                item {
                    OutlinedTextField(value = draft.explanation, onValueChange = { value -> viewModel.edit { it.copy(explanation = value) } }, label = { Text("Explicação / justificativa do gabarito (opcional)") }, minLines = 3, enabled = editable, modifier = Modifier.fillMaxWidth())
                }
            } else {
                item {
                    Text("Cole JSON/JSONL ou use IA para preparar o texto.")
                    OutlinedTextField(value = draft.source, onValueChange = { value -> viewModel.edit { it.copy(source = value) } }, label = { Text("Questões ou material para a IA") }, minLines = 8, maxLines = 18, enabled = editable, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = editable) { Text("Abrir JSON / JSONL / TXT") }
                        OutlinedButton(onClick = { viewModel.edit { it.copy(source = bulkExample, assistanceId = null) } }, enabled = editable && draft.source.isBlank()) { Text("Carregar exemplo") }
                    }
                    Text("Até 50 questões / 512 KiB. JSON usa índices de gabarito a partir de zero. Documentos v2 completos também são aceitos.", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Assistência de IA · Grok 4.7", style = MaterialTheme.typography.titleMedium)
                            Text("Envia o material acima à xAI somente ao tocar no botão. Até 10 rascunhos por solicitação; usa a cota diária de IA da conta.")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = draft.aiMode == "STRUCTURE", onClick = { viewModel.edit { it.copy(aiMode = "STRUCTURE") } }, enabled = editable && draft.assistanceId == null, label = { Text("Estruturar texto") })
                                FilterChip(selected = draft.aiMode == "GENERATE", onClick = { viewModel.edit { it.copy(aiMode = "GENERATE") } }, enabled = editable && draft.assistanceId == null, label = { Text("Gerar autorais") })
                            }
                            Button(onClick = viewModel::assist, enabled = editable && state.allowed && state.aiAvailable && draft.source.isNotBlank() && draft.assistanceId == null) { Text("Preparar com IA") }
                            if (draft.assistanceId != null) Text("Rascunho preparado com IA. Edite o JSON acima e confira os gabaritos antes de validar; a proveniência será preservada.")
                            if (!state.aiAvailable) Text("IA indisponível nesta conexão. A inserção manual continua disponível.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text("Novas questões entram com validade desconhecida. Origem e classificação têm conferências separadas. Para validade revisada ou múltiplos vínculos, importe o contrato v2.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = viewModel::preview, enabled = editable && state.allowed, modifier = Modifier.fillMaxWidth()) { Text("Validar e revisar questões") }
            }
            draft.preview?.let { preview ->
                item { Text("Revisão do lote", style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(preview["items"]!!.jsonArray) { index, entry ->
                    val item = entry.jsonObject
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Questão ${index + 1}", style = MaterialTheme.typography.titleMedium)
                            if (item["valid"]?.jsonPrimitive?.booleanOrNull != true) Text(item["message"]?.jsonPrimitive?.content.orEmpty(), color = MaterialTheme.colorScheme.error)
                            else {
                                val q = item["command"]!!.jsonObject["document"]!!.jsonObject
                                Text(q["question"]!!.jsonPrimitive.content)
                                q["answers"]!!.jsonArray.forEachIndexed { option, answer -> Text("${'A' + option}) ${answer.jsonPrimitive.content}") }
                                Text("Gabarito: ${q["correct_answers"]!!.jsonArray.joinToString { it.jsonPrimitive.content }.ifBlank { "Discursiva — rubrica" }}")
                                if (q["question_type"]?.jsonPrimitive?.content == "OPEN_ENDED") Text("Correção: ${q["grading"]}")
                                Text("Classificação: ${q["classification"]!!.jsonObject["state"]!!.jsonPrimitive.content} · Validade: ${q["validity"]!!.jsonObject["status"]!!.jsonPrimitive.content}", style = MaterialTheme.typography.bodySmall)
                                q["classification"]!!.jsonObject["links"]!!.jsonArray.forEach { link ->
                                    val l = link.jsonObject
                                    Text("${l["subject_id"]!!.jsonPrimitive.content} / ${l["topic_id"]!!.jsonPrimitive.content} · ${l["role"]!!.jsonPrimitive.content} · ${l["coverage"]!!.jsonPrimitive.content}", style = MaterialTheme.typography.bodySmall)
                                }
                                val validity = q["validity"]!!.jsonObject
                                Text("Contexto: ${validity["scope"]} · data histórica: ${validity["context_date"]} · próxima revisão: ${validity["review_due_on"]}", style = MaterialTheme.typography.bodySmall)
                                q["origins"]!!.jsonArray.forEach { origin ->
                                    val o = origin.jsonObject
                                    Text("Origem: ${o["kind"]} · ${o["verification"]} · prova: ${o["source_exam_code"]} · instituição: ${o["institution"]} · banca: ${o["organizer"]} · ano: ${o["exam_year"]} · questão: ${o["question_number"]}", style = MaterialTheme.typography.bodySmall)
                                    if (o["source_reference"] is JsonObject) Text("Fonte: ${o["source_reference"]}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(onClick = { showJson = !showJson }) { Text(if (showJson) "Ocultar contrato completo" else "Ver contrato completo") }
                    if (showJson) Text(importPrettyJson.encodeToString(JsonElement.serializer(), preview), style = MaterialTheme.typography.bodySmall)
                    Row {
                        Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = !state.busy)
                        Text("Revisei enunciados, alternativas, gabaritos e metadados do lote.", Modifier.padding(top = 12.dp))
                    }
                    val complete = draft.result?.get("complete")?.jsonPrimitive?.booleanOrNull == true
                    Button(onClick = viewModel::commit, enabled = !state.busy && state.allowed && reviewed && preview["ready"]?.jsonPrimitive?.booleanOrNull == true && !complete, modifier = Modifier.fillMaxWidth()) {
                        Text(if (draft.submitted) "Conferir / reenviar os mesmos comandos" else "Confirmar inserção de ${preview["items"]!!.jsonArray.size} questão(ões)")
                    }
                    if (draft.submitted && !complete) Text("Envio iniciado. O conteúdo permanece bloqueado até a confirmação. A retentativa usa os mesmos identificadores e não duplica itens já confirmados.")
                }
            }
            draft.result?.let { result -> item {
                val results = result["items"]!!.jsonArray
                val count = results.count { it.jsonObject["saved"]?.jsonPrimitive?.booleanOrNull == true }
                Text("$count de ${results.size} questão(ões) confirmadas no servidor.", style = MaterialTheme.typography.titleMedium)
                results.forEachIndexed { index, entry ->
                    val item = entry.jsonObject
                    Text("${index + 1}: " + if (item["saved"]?.jsonPrimitive?.booleanOrNull == true) "Confirmada · ${item["id"]}" else item["message"]?.jsonPrimitive?.content.orEmpty())
                }
            } }
            item {
                OutlinedButton(onClick = { confirmNew = true }, enabled = !state.busy && !state.loading && state.storageReady && (!draft.submitted || draft.result?.get("complete")?.jsonPrimitive?.booleanOrNull == true)) { Text("Iniciar novo cadastro") }
            }
        }
    }
    if (chooseSubject) ImportCatalogDialog("Disciplina", ExamCatalog.allSubjects().map { it.canonicalSubjectId to it.displayName }, { chooseSubject = false }) { id ->
        viewModel.edit { it.copy(subjectId = id, topicId = "", confirmClassification = false) }; chooseSubject = false
    }
    if (chooseTopic) ImportCatalogDialog("Tópico", ExamCatalog.catalogTopics(draft.subjectId).map { it.canonicalTopicId to ExamCatalog.topicLabel(it) }, { chooseTopic = false }) { id ->
        viewModel.edit { it.copy(topicId = id, confirmClassification = false) }; chooseTopic = false
    }
    if (confirmNew) AlertDialog(onDismissRequest = { confirmNew = false }, title = { Text("Iniciar novo cadastro?") }, text = { Text("O rascunho local atual será substituído. Você pode exportá-lo antes de continuar.") }, confirmButton = { TextButton(onClick = { viewModel.newDraft(); confirmNew = false }) { Text("Novo cadastro") } }, dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Continuar no rascunho") } })
}

@Composable
private fun ImportCatalogDialog(title: String, options: List<Pair<String, String>>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Buscar") })
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                val filtered = options.filter { it.first.contains(search, true) || it.second.contains(search, true) }
                itemsIndexed(filtered) { _, (id, label) -> TextButton(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) { Text("$label · $id") } }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } })
}
