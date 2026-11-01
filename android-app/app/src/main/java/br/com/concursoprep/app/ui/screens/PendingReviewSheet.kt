package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.PendingReviewItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingReviewSheet(items:List<PendingReviewItem>,onDismiss:()->Unit,onExport:()->Unit){
    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Registros preservados",style=MaterialTheme.typography.titleLarge)
            Text("Os itens abaixo não foram descartados. Registros da versão anterior exigem conferir o recebimento no histórico antes de qualquer reenvio.")
            items.forEach { item ->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(item.title,style=MaterialTheme.typography.titleSmall)
                        Text(item.detail,style=MaterialTheme.typography.bodyMedium)
                        Text(item.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    }
                }
            }
            OutlinedButton(onClick=onExport,enabled=items.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("Exportar registros preservados")}
            Button(onClick=onDismiss,modifier=Modifier.fillMaxWidth()){Text("Manter os registros e voltar")}
        }
    }
}
