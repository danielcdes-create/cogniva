package br.com.concursoprep.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.exam.StoredExam

@Composable
fun ExamManagementScreen(
    exams: List<StoredExam>, pendingCount: Int, onBack: ()->Unit,
    onCreate: ()->Unit, onEdit: (StoredExam)->Unit, onArchive: (StoredExam)->Unit,
    onRestore: (StoredExam)->Unit, onDelete: (StoredExam)->Unit,
    onPending: ()->Unit, onUseServer: (StoredExam)->Unit
) {
    var archived by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack=onBack)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick=onBack) { Text("Voltar aos ajustes") }
            Text("Gerenciar concursos e registros",style=MaterialTheme.typography.headlineSmall)
            br.com.concursoprep.app.ui.components.StudyStructureGuide()
            Button(onClick=onCreate,modifier=Modifier.fillMaxWidth()) { Text("Criar concurso") }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!archived,onClick={archived=false},label={Text("Concursos")})
                FilterChip(selected=archived,onClick={archived=true},label={Text("Arquivados")})
            }
            val visible=exams.filter { (it.lifecycleStatus=="ARQUIVADO")==archived && it.lifecycleStatus!="EXCLUIDO" }
            if(visible.isEmpty()) Text(if(archived)"Nenhum concurso arquivado."else"Nenhum concurso cadastrado.")
            visible.forEach { exam ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(exam.shortName,style=MaterialTheme.typography.titleMedium)
                        Text(exam.role)
                        OutlinedButton(onClick={onEdit(exam)},modifier=Modifier.fillMaxWidth()) { Text("Editar concurso e componentes") }
                        OutlinedButton(onClick={if(archived)onRestore(exam)else onArchive(exam)},modifier=Modifier.fillMaxWidth()) { Text(if(archived)"Restaurar concurso"else"Arquivar concurso") }
                        OutlinedButton(onClick={deletingId=exam.examId},modifier=Modifier.fillMaxWidth()) { Text("Excluir concurso") }
                        exam.syncError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        if(exam.syncError?.startsWith("Conflito de revisão")==true) OutlinedButton(onClick={onUseServer(exam)},modifier=Modifier.fillMaxWidth()) { Text("Preservar edição e usar versão do servidor") }
                    }
                }
            }
            exams.filter { it.lifecycleStatus=="EXCLUIDO" && it.syncStatus!="SYNCED" }.forEach { exam ->
                Text("Exclusão aguardando confirmação: ${exam.shortName}")
                exam.syncError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                if(exam.syncError?.startsWith("Conflito de revisão")==true) OutlinedButton(onClick={onUseServer(exam)},modifier=Modifier.fillMaxWidth()) { Text("Preservar pedido e carregar versão do servidor") }
            }
            OutlinedButton(onClick=onPending,modifier=Modifier.fillMaxWidth()) { Text(if(pendingCount>0)"Revisar $pendingCount registro(s) pendente(s)"else"Ver registros preservados") }
        }
    }
    exams.find { it.examId==deletingId }?.let { exam ->
        AlertDialog(onDismissRequest={deletingId=null},title={Text("Excluir ${exam.shortName}?")},text={Text("O concurso e sua grade sairão da sua lista. Seu histórico de respostas será mantido. Para voltar a usá-lo depois, prefira arquivar.")},confirmButton={TextButton(onClick={onDelete(exam);deletingId=null}) { Text("Excluir concurso") }},dismissButton={TextButton(onClick={deletingId=null}) { Text("Cancelar") }})
    }
}
