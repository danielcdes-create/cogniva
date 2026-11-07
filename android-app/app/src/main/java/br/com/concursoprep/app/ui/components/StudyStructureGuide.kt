package br.com.concursoprep.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StudyStructureGuide() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text("Como troncos, concursos e questões se conectam?")
            }
        }
    }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text("Estrutura dos estudos") },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("Entendi") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tronco → Concurso → Grade do edital → Componentes", style = MaterialTheme.typography.titleSmall)
                Text("O tronco agrupa carreiras. O concurso identifica a oportunidade e o cargo. A grade organiza o conteúdo previsto ou publicado no edital. Cada componente é uma disciplina em uma etapa, com peso, quantidade e assuntos próprios.", style = MaterialTheme.typography.bodySmall)
                Text("Disciplina → Assunto → Subassunto", style = MaterialTheme.typography.titleSmall)
                Text("O catálogo de conteúdo é compartilhado entre troncos e concursos. Assuntos pertencem a uma disciplina; subassuntos detalham um assunto. A mesma disciplina pode aparecer em vários componentes da grade.", style = MaterialTheme.typography.bodySmall)
                Text("Questões: conteúdo e origem", style = MaterialTheme.typography.titleSmall)
                Text("A questão é classificada pela disciplina e pelo assunto ou subassunto cobrado. Prova, banca e ano registram sua origem. Seu uso em outro concurso depende da correspondência com a grade e das regras de compatibilidade. Um vínculo de tronco sugere disciplinas; a grade define o conteúdo cobrado.", style = MaterialTheme.typography.bodySmall)
            }
        }
    )
}
