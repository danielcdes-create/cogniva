package br.com.concursoprep.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader

/**
 * Fluxo de Onboarding em 4 Passos:
 * 1. Vínculo com conta do Bot Telegram ou novo perfil
 * 2. Escolha de concurso(s) em escada
 * 3. Disponibilidade diária (Horas/dia e Meta de Questões) com alto contraste WCAG
 * 4. Calibração / Ativação do Edital Verticalizado
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onSkipOnboarding: () -> Unit = {},
    snackbarState: androidx.compose.material3.SnackbarHostState? = null,
    onCompleteOnboarding: (
        telegramUserId: Long?,
        telegramUsername: String?,
        displayName: String,
        selectedContests: List<String>,
        dailyQuestionsTarget: Int,
        dailyHoursTarget: Double,
        linkCode: String?
    ) -> Unit
) {
    var currentStep by rememberSaveable { mutableIntStateOf(1) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var linkCode by rememberSaveable { mutableStateOf("") }
    var telegramInput by rememberSaveable { mutableStateOf("") }
    val selectedContests = remember { mutableStateListOf("CPNU", "RFB") }
    var dailyQuestions by rememberSaveable { mutableIntStateOf(50) }
    var dailyHours by rememberSaveable { mutableDoubleStateOf(2.0) }

    BackHandler(enabled = currentStep > 1) {
        currentStep -= 1
    }

    val availableContests = listOf(
        Triple("CPNU", "CPNU · Auditor-Fiscal do Trabalho", "Modelo para configurar · confira o edital"),
        Triple("RFB", "Receita Federal · Auditor Fiscal", "Modelo para configurar · confira a autorização"),
        Triple("BNDES", "BNDES · Analista", "Modelo para configurar · confira as fontes")
    )

    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        labelColor = MaterialTheme.colorScheme.onSurface
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { snackbarState?.let { androidx.compose.material3.SnackbarHost(it) } },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    CognivaBrandHeader(
                        ecosystemProduct = "LEARN · PRACTICE · EVOLVE",
                        subtitle = "Conhecimento em evolução · Passo $currentStep de 4"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { currentStep / 4f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (currentStep > 1) {
                            OutlinedButton(
                                onClick = { currentStep -= 1 },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
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
                                        telegramInput.trim().removePrefix("@").ifBlank { null }
                                    } else null
                                    onCompleteOnboarding(
                                        parsedId,
                                        parsedUsername,
                                        displayName.trim().ifBlank { "Concurseiro" },
                                        selectedContests.toList(),
                                        dailyQuestions,
                                        dailyHours,
                                linkCode.takeIf { it.isNotBlank() }
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text(if (currentStep < 4) "Continuar →" else "Entrar no Painel Hoje")
                        }
                    }

                    if (currentStep < 4) {
                        TextButton(
                            onClick = onSkipOnboarding,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = "Pular configuração inicial e usar perfil padrão",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (currentStep) {
                1 -> {
                    Text(
                        text = "Vincular Histórico do Bot Telegram",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
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
                    OutlinedTextField(value=linkCode,onValueChange={linkCode=it},label={Text("Código de vínculo da conta histórica (opcional)")},supportingText={Text("Sem código, use um perfil local próprio. ID e @username não comprovam identidade.")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
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
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "A escada de concursos é regra: selecione 1 ou mais concursos. O app ativará automaticamente o Tronco Comum para matérias compartilhadas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    availableContests.forEach { (code, title, subtitle) ->
                        val isSelected = selectedContests.contains(code)
                        val toggleContest: () -> Unit = {
                            if (isSelected && selectedContests.size > 1) {
                                selectedContests.remove(code)
                            } else if (!isSelected) {
                                selectedContests.add(code)
                            }
                        }
                        Surface(
                            onClick = toggleContest,
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = toggleContest,
                                        label = { Text(if (isSelected) "Ativo" else "Selecionar") },
                                        colors = chipColors
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                3 -> {
                    Text(
                        text = "Disponibilidade e Meta Diária",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Defina seu ritmo sustentável para o cálculo do cronograma e revisão espaçada.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Meta de questões por dia:",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        listOf(20, 30, 50, 80, 100).forEach { q ->
                            FilterChip(
                                selected = dailyQuestions == q,
                                onClick = { dailyQuestions = q },
                                label = { Text("$q questões/dia") },
                                colors = chipColors
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Horas de estudo líquidas por dia:",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        listOf(1.0, 2.0, 3.0, 4.0, 6.0).forEach { h ->
                            FilterChip(
                                selected = dailyHours == h,
                                onClick = { dailyHours = h },
                                label = { Text("${h.toInt()}h/dia") },
                                colors = chipColors
                            )
                        }
                    }
                }

                4 -> {
                    Text(
                        text = "Edital Verticalizado Pronto!",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Resumo do seu Plano Estratégico:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text("• Concursos em Escada: ${selectedContests.joinToString(" + ")}")
                            Text("• Meta Diária: $dailyQuestions questões (${dailyHours.toInt()}h/dia)")
                            Text("• Tronco Comum: Ativado (Finanças Públicas, Constitucional, Administrativo)")
                            Text("• Modo Offline-First: Sincronizado localmente no Room")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
