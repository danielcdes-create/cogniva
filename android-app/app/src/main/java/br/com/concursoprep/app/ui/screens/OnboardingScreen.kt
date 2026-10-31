package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Fluxo de Onboarding em 4 Passos (§4 da Nota Técnica Quiz Kotlin):
 * 1. Vínculo com conta do Bot Telegram (para importar histórico do banco CNU) ou novo perfil
 * 2. Escolha de concurso(s) em escada (Editais Abertos + Autorizados + Previstos)
 * 3. Disponibilidade diária (Horas/dia e Meta de Questões)
 * 4. Calibração / Ativação do Edital Verticalizado ("Aha moment")
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onCompleteOnboarding: (
        telegramUserId: Long?,
        telegramUsername: String?,
        displayName: String,
        selectedContests: List<String>,
        dailyQuestionsTarget: Int,
        dailyHoursTarget: Double
    ) -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var displayName by remember { mutableStateOf("") }
    var telegramInput by remember { mutableStateOf("") }
    val selectedContests = remember { mutableStateListOf("CPNU", "RFB") }
    var dailyQuestions by remember { mutableIntStateOf(50) }
    var dailyHours by remember { mutableStateOf(2.0) }

    val availableContests = listOf(
        Triple("CPNU", "🔴 CPNU · Auditor-Fiscal do Trabalho", "Edital Aberto · Cesgranrio"),
        Triple("RFB", "🟡 Receita Federal · Auditor Fiscal", "Solicitado/Autorizado · FGV/Cebraspe"),
        Triple("BNDES", "⚪ BNDES · Analista", "Previsto · Cesgranrio")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Passo $currentStep de 4 · Configuração Tática",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        LinearProgressIndicator(
            progress = { currentStep / 4f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )

        when (currentStep) {
            1 -> {
                Text(
                    text = "Vincular Histórico do Bot Telegram",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Se você já respondeu questões no nosso QuizBot do Telegram, informe seu ID numérico ou @username para importar imediatamente todo o seu histórico, SRS e estatísticas do banco CNU.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Seu nome de exibição") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = telegramInput,
                    onValueChange = { telegramInput = it },
                    label = { Text("ID do Telegram ou @username (Opcional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            2 -> {
                Text(
                    text = "Qual(is) concurso(s) você mira?",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "A escada de concursos é regra: selecione 1 ou mais concursos. O app ativará automaticamente o Tronco Comum para matérias compartilhadas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                availableContests.forEach { (code, title, subtitle) ->
                    val isSelected = selectedContests.contains(code)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = title, style = MaterialTheme.typography.titleMedium)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected && selectedContests.size > 1) {
                                            selectedContests.remove(code)
                                        } else if (!isSelected) {
                                            selectedContests.add(code)
                                        }
                                    },
                                    label = { Text(if (isSelected) "Ativo" else "Selecionar") }
                                )
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            3 -> {
                Text(
                    text = "Disponibilidade e Meta Diária",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Defina seu ritmo sustentável para o cálculo do cronograma e revisão espaçada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Meta de questões por dia:", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20, 30, 50, 80, 100).forEach { q ->
                        FilterChip(
                            selected = dailyQuestions == q,
                            onClick = { dailyQuestions = q },
                            label = { Text("$q questões/dia") }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Horas de estudo líquidas por dia:", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0, 2.0, 3.0, 4.0, 6.0).forEach { h ->
                        FilterChip(
                            selected = dailyHours == h,
                            onClick = { dailyHours = h },
                            label = { Text("${h.toInt()}h/dia") }
                        )
                    }
                }
            }

            4 -> {
                Text(
                    text = "Edital Verticalizado Pronto!",
                    style = MaterialTheme.typography.headlineMedium
                )
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Resumo do seu Plano Estratégico:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text("• Concursos em Escada: ${selectedContests.joinToString(" + ")}")
                        Text("• Meta Diária: $dailyQuestions questões (${dailyHours.toInt()}h/dia)")
                        Text("• Tronco Comum: Ativado (Finanças Públicas, Constitucional, Administrativo)")
                        Text("• Modo Offline-First: Sincronizado localmente no Room")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (currentStep > 1) {
                OutlinedButton(
                    onClick = { currentStep -= 1 },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Voltar")
                }
            }
            Button(
                onClick = {
                    if (currentStep < 4) {
                        currentStep += 1
                    } else {
                        val parsedId = telegramInput.trim().toLongOrNull()
                        val parsedUsername = if (parsedId == null && telegramInput.isNotBlank()) {
                            telegramInput.trim()
                        } else null
                        onCompleteOnboarding(
                            parsedId,
                            parsedUsername,
                            displayName.ifBlank { "Concurseiro" },
                            selectedContests.toList(),
                            dailyQuestions,
                            dailyHours
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (currentStep < 4) "Continuar →" else "Entrar no Painel Hoje")
            }
        }
    }
}
