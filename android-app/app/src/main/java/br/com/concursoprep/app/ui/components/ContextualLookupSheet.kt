package br.com.concursoprep.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.ContextualLookupUiState
import br.com.concursoprep.app.domain.model.QuestionDto
import java.net.URLEncoder

/**
 * Toolbar contextual de seleção de texto que intercepta a seleção nativa em `SelectionContainer`
 * e expõe a ação "Consultar em Camada / Google" sem sair da tela de questão.
 */
class LayerSelectionTextToolbar(
    private val clipboardManager: ClipboardManager,
    private val onLookupSelectedText: (String) -> Unit
) : TextToolbar {
    var isVisible by mutableStateOf(false)
        private set

    private var onCopyRequested: (() -> Unit)? = null
    private var onSelectAllRequested: (() -> Unit)? = null

    override val status: TextToolbarStatus
        get() = if (isVisible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.onCopyRequested = onCopyRequested
        this.onSelectAllRequested = onSelectAllRequested
        this.isVisible = onCopyRequested != null
    }

    override fun hide() {
        isVisible = false
    }

    fun triggerLookupFromSelection() {
        val copyFn = onCopyRequested
        if (copyFn != null) {
            copyFn.invoke()
            val selected = clipboardManager.getText()?.text?.trim().orEmpty()
            isVisible = false
            if (selected.isNotEmpty()) {
                onLookupSelectedText(selected)
            }
        }
    }

    fun triggerCopyOnly() {
        onCopyRequested?.invoke()
        isVisible = false
    }
}

/**
 * Abre uma URL HTTPS em Partial Custom Tab (camada sobreposta ~74% da altura da tela)
 * mantendo a questão ativa ao fundo, com fallback transparente para navegador padrão.
 */
fun openUrlInCustomTabLayer(context: Context, url: String, toolbarColorArgb: Int) {
    if (!url.startsWith("https://")) return
    runCatching {
        val metrics = context.resources.displayMetrics
        val sheetHeightPx = (metrics.heightPixels * 0.74f).toInt().coerceAtLeast(560)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            val sessionExtras = Bundle().apply {
                putBinder("android.support.customtabs.extra.SESSION", null)
            }
            putExtras(sessionExtras)
            putExtra("androidx.browser.customtabs.extra.INITIAL_ACTIVITY_HEIGHT_PX", sheetHeightPx)
            putExtra("androidx.browser.customtabs.extra.ACTIVITY_HEIGHT_RESIZE_BEHAVIOR", 1)
            putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", toolbarColorArgb)
            putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}

fun extractSuggestedLookupTerms(question: QuestionDto): List<String> {
    val editorial = question.effectiveEditorialCard()
    val candidates = linkedSetOf<String>()
    editorial.legalProvisions.take(3).forEach { p ->
        val clean = p.trim()
        if (clean.length in 3..80) candidates += clean
    }
    question.question
        .split('.', ';', ':', ',', '?', '(', ')')
        .map { it.trim() }
        .filter { it.length in 10..65 }
        .take(4)
        .forEach { candidates += it }
    if (candidates.isEmpty()) {
        candidates += question.question.trim().take(65)
    }
    return candidates.take(5)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContextualLookupBottomSheet(
    question: QuestionDto,
    lookupState: ContextualLookupUiState,
    isAnswerSubmitted: Boolean,
    onUpdateLookupQuery: (String) -> Unit,
    onRequestAiExplanation: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
    var editableQuery by remember(lookupState.selectedText) { mutableStateOf(lookupState.selectedText) }
    val suggestedTerms = remember(question.id) { extractSuggestedLookupTerms(question) }
    val disciplina = question.disciplina.trim().ifEmpty { "Concursos Públicos" }

    val activeQuery = editableQuery.trim().ifEmpty { lookupState.selectedText.trim() }
    val resp = lookupState.response
    val googleUrl = resp?.googleSearchUrl?.takeIf { editableQuery.trim() == lookupState.selectedText.trim() }
        ?: ("https://www.google.com/search?hl=pt-BR&q=" + URLEncoder.encode("$activeQuery $disciplina", "UTF-8"))
    val planaltoUrl = resp?.planaltoSearchUrl?.takeIf { editableQuery.trim() == lookupState.selectedText.trim() }
        ?: ("https://www.google.com/search?hl=pt-BR&q=" + URLEncoder.encode("site:planalto.gov.br $activeQuery", "UTF-8"))
    val jurispUrl = resp?.jurisprudenceSearchUrl?.takeIf { editableQuery.trim() == lookupState.selectedText.trim() }
        ?: ("https://www.google.com/search?hl=pt-BR&q=" + URLEncoder.encode("(STF OR STJ) $activeQuery", "UTF-8"))

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Consulta Contextual em Camada",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Fontes normativas, síntese rápida e pesquisa Google sobreposta sem perder a questão",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = editableQuery,
                onValueChange = { editableQuery = it.take(240) },
                label = { Text("Trecho ou termo selecionado") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (editableQuery.isNotBlank()) {
                            onUpdateLookupQuery(editableQuery.trim())
                        }
                    }
                ),
                trailingIcon = {
                    if (editableQuery.trim() != lookupState.selectedText.trim() && editableQuery.isNotBlank()) {
                        AssistChip(
                            onClick = { onUpdateLookupQuery(editableQuery.trim()) },
                            label = { Text("Atualizar") }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (suggestedTerms.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    suggestedTerms.forEach { term ->
                        AssistChip(
                            onClick = {
                                editableQuery = term
                                onUpdateLookupQuery(term)
                            },
                            label = {
                                Text(
                                    text = term,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            if (lookupState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 1. Ações de Pesquisa Externa em Camada (Partial Custom Tab Sheet)
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Pesquisa Externa em Camada (Overlay)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = {
                            if (activeQuery.isNotBlank()) {
                                openUrlInCustomTabLayer(context, googleUrl, surfaceArgb)
                            }
                        },
                        enabled = activeQuery.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Abrir Pesquisa Google em Camada",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { openUrlInCustomTabLayer(context, planaltoUrl, surfaceArgb) },
                            enabled = activeQuery.isNotBlank(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.45f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "Lei · Planalto",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { openUrlInCustomTabLayer(context, jurispUrl, surfaceArgb) },
                            enabled = activeQuery.isNotBlank(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.45f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "STF / STJ",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 2. Correspondências no Catálogo Normativo & Conceitos Oficiais
            if (resp != null && (resp.normativeMatches.isNotEmpty() || resp.conceptMatches.isNotEmpty())) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Fontes Oficiais & Conceitos Relacionados",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        resp.normativeMatches.forEach { nm ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = if (!nm.locator.isNullOrBlank()) "${nm.title} · ${nm.locator}" else nm.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = nm.excerpt,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (nm.canonicalUri.startsWith("https://")) {
                                        AssistChip(
                                            onClick = { openUrlInCustomTabLayer(context, nm.canonicalUri, surfaceArgb) },
                                            label = { Text("Abrir texto legal oficial") }
                                        )
                                    }
                                }
                            }
                        }
                        if (resp.conceptMatches.isNotEmpty()) {
                            Text(
                                text = "Conceitos: " + resp.conceptMatches.joinToString(" · ") { it.label },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. Síntese Enciclopédica Aberta (quando retornada)
            resp?.encyclopedicSummary?.let { wiki ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Síntese Enciclopédica · ${wiki.title}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = wiki.extract,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (wiki.sourceUrl.startsWith("https://")) {
                            AssistChip(
                                onClick = { openUrlInCustomTabLayer(context, wiki.sourceUrl, surfaceArgb) },
                                label = { Text("Ver verbete completo em camada") }
                            )
                        }
                    }
                }
            }

            // 4. Explicação Contextual Cogniva AI (com guarda anti-spoiler pré-resposta)
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAnswerSubmitted) {
                                "Explicação Direta do Trecho (Cogniva AI)"
                            } else {
                                "Conceituar Trecho sem Revelar Gabarito (Cogniva AI)"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (lookupState.isAiLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Sintetizando definição objetiva…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else if (lookupState.aiExplanationMarkdown != null) {
                        Text(
                            text = remember(lookupState.aiExplanationMarkdown) {
                                MarkdownParser.parse(lookupState.aiExplanationMarkdown)
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        OutlinedButton(
                            onClick = {
                                if (activeQuery.isNotBlank()) {
                                    onRequestAiExplanation(activeQuery)
                                }
                            },
                            enabled = activeQuery.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Explicar \"$activeQuery\" aqui na camada")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Fechar camada e voltar à questão")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
