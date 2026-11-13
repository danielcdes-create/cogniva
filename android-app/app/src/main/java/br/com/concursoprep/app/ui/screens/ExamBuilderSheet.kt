package br.com.concursoprep.app.ui.screens

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForInspector
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.domain.exam.ExamDraft
import br.com.concursoprep.app.domain.exam.StoredExam
import br.com.concursoprep.app.domain.exam.StoredSubject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.roundToInt

/** Catalog subjects that can still be added: those already in the grade leave the list. */
internal fun availableCatalogSubjects(all: List<StoredSubject>, added: List<StoredSubject>, search: String, suggestedIds: List<String>): List<StoredSubject> {
    val used = added.map { it.canonicalSubjectId }.toSet()
    val remaining = all.filter { it.canonicalSubjectId !in used }
    if (search.isNotBlank()) return remaining.filter { ExamCatalog.matchesSubject(it, search.trim()) }
    val order = suggestedIds.withIndex().associate { it.value to it.index }
    return remaining.sortedWith(compareBy<StoredSubject> { order[it.canonicalSubjectId] ?: Int.MAX_VALUE }.thenBy { it.displayName })
}

/**
 * Full-screen exam wizard. A Dialog (instead of a ModalBottomSheet) keeps the sheet's
 * drag-to-dismiss gesture from competing with vertical scrolling of the long form.
 */
@Composable
fun ExamFormBottomSheet(
    existingExams: List<StoredExam>,
    editingExam: StoredExam? = null,
    onDismiss: () -> Unit,
    onSave: (ExamDraft) -> Unit,
    onDelete: ((StoredExam) -> Unit)? = null
) {
    FullScreenModalDialog(onDismissRequest = onDismiss) {
        ExamFormContent(existingExams, editingExam, onDismiss, onSave, onDelete)
    }
}

/**
 * Edge-to-edge modal dialog window using Material3's non-floating window theme so the
 * WindowManager measures and lays out the full screen in a single pass on API 31-35
 * (avoiding Compose Dialog's floating WRAP_CONTENT -> setLayout two-pass race) while
 * preserving parent density/fontScale overrides and dialog semantics.
 */
@Composable
private fun FullScreenModalDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val darkTheme = isSystemInDarkTheme()
    val composition = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentDensity by rememberUpdatedState(density)
    val currentLayoutDirection by rememberUpdatedState(layoutDirection)
    val dialogId = rememberSaveable { UUID.randomUUID() }
    val dialog = remember(view) {
        FullScreenDialogWrapper(
            onDismissRequest = onDismissRequest,
            composeView = view,
            layoutDirection = layoutDirection,
            dialogId = dialogId,
            darkTheme = darkTheme
        ).apply {
            setContent(composition) {
                CompositionLocalProvider(
                    LocalDensity provides currentDensity,
                    LocalLayoutDirection provides currentLayoutDirection
                ) {
                    Box(Modifier.fillMaxSize().semantics { dialog() }) {
                        currentContent()
                    }
                }
            }
        }
    }
    DisposableEffect(dialog) {
        dialog.show()
        onDispose {
            dialog.dismiss()
            dialog.disposeComposition()
        }
    }
    SideEffect {
        dialog.updateParameters(
            onDismissRequest = onDismissRequest,
            layoutDirection = layoutDirection,
            darkTheme = darkTheme
        )
    }
}

private class FullScreenDialogLayout(
    context: Context,
    override val window: Window
) : AbstractComposeView(context), DialogWindowProvider {
    private var content: @Composable () -> Unit by mutableStateOf({})

    override var shouldCreateCompositionOnAttachedToWindow: Boolean = false
        private set

    fun setContent(parent: CompositionContext, content: @Composable () -> Unit) {
        setParentCompositionContext(parent)
        this.content = content
        shouldCreateCompositionOnAttachedToWindow = true
        createComposition()
    }

    @Composable
    override fun Content() {
        content()
    }
}

private class FullScreenDialogWrapper(
    private var onDismissRequest: () -> Unit,
    composeView: View,
    layoutDirection: LayoutDirection,
    dialogId: UUID,
    darkTheme: Boolean
) : ComponentDialog(
    ContextThemeWrapper(
        composeView.context,
        androidx.compose.material3.R.style.EdgeToEdgeFloatingDialogWindowTheme
    )
), ViewRootForInspector {
    private val dialogLayout: FullScreenDialogLayout

    override val subCompositionView: AbstractComposeView
        get() = dialogLayout

    init {
        val window = checkNotNull(window) { "Dialog has no window" }
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setCanceledOnTouchOutside(false)
        dialogLayout = FullScreenDialogLayout(context, window).apply {
            setTag(androidx.compose.ui.R.id.compose_view_saveable_id_tag, "Dialog:$dialogId")
            clipChildren = false
        }
        setContentView(dialogLayout)
        dialogLayout.setViewTreeLifecycleOwner(composeView.findViewTreeLifecycleOwner())
        dialogLayout.setViewTreeViewModelStoreOwner(composeView.findViewTreeViewModelStoreOwner())
        dialogLayout.setViewTreeSavedStateRegistryOwner(composeView.findViewTreeSavedStateRegistryOwner())
        updateParameters(onDismissRequest, layoutDirection, darkTheme)
        onBackPressedDispatcher.addCallback(this) {
            this@FullScreenDialogWrapper.onDismissRequest()
        }
    }

    fun setContent(parentComposition: CompositionContext, children: @Composable () -> Unit) {
        dialogLayout.setContent(parentComposition, children)
    }

    fun updateParameters(
        onDismissRequest: () -> Unit,
        layoutDirection: LayoutDirection,
        darkTheme: Boolean
    ) {
        this.onDismissRequest = onDismissRequest
        dialogLayout.layoutDirection = when (layoutDirection) {
            LayoutDirection.Ltr -> View.LAYOUT_DIRECTION_LTR
            LayoutDirection.Rtl -> View.LAYOUT_DIRECTION_RTL
        }
        window?.let { win ->
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            win.setSoftInputMode(
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                }
            )
            WindowCompat.getInsetsController(win, win.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    fun disposeComposition() {
        dialogLayout.disposeComposition()
    }

    override fun cancel() {
        // Controlled via onDismissRequest, matching Compose DialogWrapper.
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExamFormContent(
    existingExams: List<StoredExam>,
    editingExam: StoredExam? = null,
    onDismiss: () -> Unit,
    onSave: (ExamDraft) -> Unit,
    onDelete: ((StoredExam) -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets.safeDrawing
) {
    val formKey = editingExam?.examId ?: "new"
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val trunks = remember { ExamCatalog.trunks() }
    var step by rememberSaveable(formKey) { mutableIntStateOf(1) }
    var trunkSlug by rememberSaveable(formKey) { mutableStateOf(editingExam?.trunkSlug ?: trunks.firstOrNull()?.slug ?: "fiscal") }
    var creationMode by rememberSaveable(formKey) { mutableStateOf(if (editingExam != null) "clone" else "template") }
    var cloneFromId by rememberSaveable(formKey) { mutableStateOf(editingExam?.examId ?: existingExams.firstOrNull()?.examId) }
    var organ by rememberSaveable(formKey) { mutableStateOf(editingExam?.institution ?: "") }
    var role by rememberSaveable(formKey) { mutableStateOf(editingExam?.role ?: "") }
    val boards = remember { listOf("FGV", "Cebraspe", "Cesgranrio", "FCC", "Vunesp", "A definir") }
    var customBoard by rememberSaveable(formKey) { mutableStateOf("") }
    var selectedBoards by rememberSaveable(formKey) { mutableStateOf(editingExam?.examBoards.orEmpty()) }
    var status by rememberSaveable(formKey) { mutableStateOf(editingExam?.lifecycleStatus ?: "RUMOR_PREVISTO") }
    var dateMode by rememberSaveable(formKey) { mutableStateOf(editingExam?.dateType ?: "UNDEFINED") }
    var exactDate by rememberSaveable(formKey) { mutableStateOf(editingExam?.exactDateIso ?: "") }
    var windowLabel by rememberSaveable(formKey) { mutableStateOf(editingExam?.estimatedWindowLabel ?: "Sem previsão") }
    var customSortDate by rememberSaveable(formKey) { mutableStateOf(editingExam?.estimatedSortDateIso ?: "") }
    var isActiveInLadder by rememberSaveable(formKey) { mutableStateOf(editingExam?.isActiveInLadder ?: true) }
    var ladderPriority by rememberSaveable(formKey) { mutableIntStateOf(editingExam?.ladderPriority ?: 2) }
    var wrongPenalty by rememberSaveable(formKey) { mutableStateOf(editingExam?.wrongAnswerPenalty ?: 0.0) }
    var cutoff by rememberSaveable(formKey) { mutableStateOf(editingExam?.historicalCutoffScore?.toFloat() ?: 0f) }
    val subjectSaver = Saver<List<StoredSubject>, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })
    var subjects by rememberSaveable(formKey, trunkSlug, creationMode, cloneFromId, stateSaver = subjectSaver) {
        mutableStateOf(
            when (creationMode) {
                "clone" -> (if (editingExam != null && cloneFromId == editingExam.examId) editingExam.subjects else existingExams.firstOrNull { it.examId == cloneFromId }?.subjects) ?: ExamCatalog.subjectsForTrunk(trunkSlug)
                "blank" -> emptyList()
                else -> ExamCatalog.subjectsForTrunk(trunkSlug).filter { it.relevanceTier == "CORE" }
            }
        )
    }
    var confirmDeleteExam by rememberSaveable(formKey) { mutableStateOf(false) }
    var invalidSubjects by remember { mutableStateOf(setOf<String>()) }
    var customName by rememberSaveable { mutableStateOf("") }
    var subjectSearch by rememberSaveable { mutableStateOf("") }
    var showAllCatalog by rememberSaveable { mutableStateOf(false) }
    val stepTitles = listOf("Identificação", "Prova e escada", "Disciplinas e tópicos")
    val canSave = invalidSubjects.none { id -> subjects.any { it.localSubjectId == id } }

    BackHandler(enabled = step > 1) {
        focusManager.clearFocus()
        keyboard?.hide()
        step -= 1
    }
    // Measure the action bar before the scrolling form so its space and the
    // navigation/IME insets cannot be used by the form beneath it.
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = windowInsets,
        topBar = {
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(
                    windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("exam_editor_header").padding(horizontal = 4.dp, vertical = 4.dp)) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Fechar sem salvar") }
                    Column(Modifier.weight(1f)) {
                        Text(if (editingExam == null) "Novo concurso" else "Editar concurso", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Passo $step de 3 · ${stepTitles[step - 1]}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (editingExam != null && onDelete != null) {
                        IconButton(onClick = { confirmDeleteExam = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Excluir concurso", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    stepTitles.forEachIndexed { idx, label ->
                        val targetStep = idx + 1
                        FilterChip(
                            selected = step == targetStep,
                            onClick = { focusManager.clearFocus(); keyboard?.hide(); step = targetStep },
                            label = { Text("${targetStep}. $label") }
                        )
                    }
                }
                LinearProgressIndicator(progress = { step / 3f }, modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier.fillMaxWidth().windowInsetsPadding(
                        windowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    ).testTag("exam_editor_actions")
                ) {
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (step > 1) {
                            OutlinedButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); step -= 1 }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Voltar") }
                        }
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                keyboard?.hide()
                                if (step < 3) {
                                    step += 1
                                } else {
                                    val name = organ.ifBlank { trunks.first { it.slug == trunkSlug }.name }
                                    val presetSortDates = mapOf("1º sem/2027" to "2027-06-30", "2º sem/2027" to "2027-12-31", "1º sem/2028" to "2028-06-30")
                                    onSave(
                                        ExamDraft(
                                            title = "$name · ${role.ifBlank { "Cargo" }}",
                                            shortName = name,
                                            institution = name,
                                            role = role.ifBlank { "Cargo" },
                                            trunkSlug = trunkSlug,
                                            lifecycleStatus = status,
                                            examBoards = selectedBoards.filter { it != "A definir" },
                                            dateType = dateMode,
                                            exactDateIso = if (dateMode == "EXACT_DATE") exactDate else null,
                                            estimatedWindowLabel = if (dateMode == "ESTIMATED_WINDOW") windowLabel else null,
                                            estimatedSortDateIso = when (dateMode) {
                                                "EXACT_DATE" -> exactDate
                                                "ESTIMATED_WINDOW" -> customSortDate.trim().ifBlank { presetSortDates[windowLabel] ?: editingExam?.estimatedSortDateIso ?: "2027-12-31" }
                                                else -> ""
                                            },
                                            historicalCutoffScore = cutoff.toDouble(),
                                            ladderPriority = ladderPriority,
                                            subjects = subjects,
                                            wrongAnswerPenalty = wrongPenalty,
                                            isActiveInLadder = isActiveInLadder && status != "ARQUIVADO"
                                        )
                                    )
                                }
                            },
                            enabled = step < 3 || canSave,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("exam_editor_primary_action")
                        ) {
                            Text(if (step < 3) "Continuar" else "Salvar na escada")
                        }
                    }
                }
            }
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .testTag("exam_editor_form")
                .verticalScroll(key(step) { rememberScrollState() })
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (step) {
                1 -> {
                    Text("Tronco de carreira", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        trunks.forEach { trunk -> FilterChip(selected = trunkSlug == trunk.slug, onClick = { trunkSlug = trunk.slug }, label = { Text(trunk.name) }, modifier = Modifier.heightIn(min = 48.dp)) }
                    }
                    if (editingExam == null) {
                        Text("Modo de criação", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("template" to "Template do tronco", "clone" to "Clonar concurso", "blank" to "Criar do zero").forEach { (mode, label) ->
                                FilterChip(selected = creationMode == mode, onClick = { creationMode = mode }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                        if (creationMode == "clone") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                existingExams.forEach { exam -> FilterChip(selected = cloneFromId == exam.examId, onClick = { cloneFromId = exam.examId }, label = { Text(exam.shortName) }, modifier = Modifier.heightIn(min = 48.dp)) }
                            }
                        }
                    }
                    OutlinedTextField(value = organ, onValueChange = { organ = it }, label = { Text("Órgão ou concurso") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = role, onValueChange = { role = it }, label = { Text("Cargo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Bancas", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (boards + selectedBoards).distinct().forEach { board ->
                            val selected = selectedBoards.contains(board)
                            FilterChip(selected = selected, onClick = { selectedBoards = if (selected) selectedBoards - board else selectedBoards + board }, label = { Text(board) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(customBoard, { customBoard = it.take(100) }, label = { Text("Outra banca") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedButton(enabled = customBoard.isNotBlank(), onClick = { selectedBoards = (selectedBoards + customBoard.trim()).distinct(); customBoard = "" }) { Text("Adicionar banca") }
                    }
                }
                2 -> {
                    val presetSortDates = mapOf("1º sem/2027" to "2027-06-30", "2º sem/2027" to "2027-12-31", "1º sem/2028" to "2028-06-30")
                    Text("Status do edital", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("RUMOR_PREVISTO" to "Pré-edital", "AUTORIZADO" to "Autorizado", "BANCA_DEFINIDA" to "Banca definida", "EDITAL_PUBLICADO" to "Edital publicado", "PROVA_REALIZADA" to "Prova realizada").forEach { (value, label) ->
                            FilterChip(selected = status == value, onClick = { status = value }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Text("Data da prova", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = dateMode == "EXACT_DATE", onClick = { dateMode = "EXACT_DATE" }, label = { Text("Data exata") }, modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(selected = dateMode != "EXACT_DATE", onClick = { dateMode = if (windowLabel.isBlank() || windowLabel == "Sem previsão") "UNDEFINED" else "ESTIMATED_WINDOW" }, label = { Text("Janela estimada") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    if (dateMode == "EXACT_DATE") {
                        OutlinedTextField(value = exactDate, onValueChange = { exactDate = it }, label = { Text("Data ISO (AAAA-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("1º sem/2027", "2º sem/2027", "1º sem/2028", "Sem previsão").forEach { label ->
                                FilterChip(
                                    selected = windowLabel == label,
                                    onClick = {
                                        windowLabel = label
                                        dateMode = if (label == "Sem previsão") "UNDEFINED" else "ESTIMATED_WINDOW"
                                        customSortDate = presetSortDates[label].orEmpty()
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                        OutlinedTextField(
                            value = if (windowLabel == "Sem previsão") "" else windowLabel,
                            onValueChange = { value ->
                                val trimmed = value.take(60)
                                windowLabel = trimmed.ifBlank { "Sem previsão" }
                                dateMode = if (trimmed.isBlank()) "UNDEFINED" else "ESTIMATED_WINDOW"
                            },
                            label = { Text("Rótulo da janela estimada (ex.: 2º trim/2027)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (dateMode == "ESTIMATED_WINDOW") {
                            OutlinedTextField(
                                value = customSortDate,
                                onValueChange = { customSortDate = it.take(10) },
                                label = { Text("Data de referência ISO (AAAA-MM-DD)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                    Text("Desconto por resposta errada (conforme a regra da prova)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0.0, 0.25, 0.5, 1.0).forEach { value -> FilterChip(selected = wrongPenalty == value, onClick = { wrongPenalty = value }, label = { Text(value.toString()) }) }
                    }
                    Text("Posição na escada", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1 to "Foco imediato", 2 to "Médio prazo", 3 to "Longo prazo").forEach { (priority, label) ->
                            FilterChip(selected = ladderPriority == priority, onClick = { ladderPriority = priority }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    FilterChip(
                        selected = isActiveInLadder,
                        onClick = { isActiveInLadder = !isActiveInLadder },
                        label = { Text(if (isActiveInLadder) "Ativo na escada de concursos" else "Pausado na escada") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    Text("Nota de corte histórica: ${cutoff.roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = cutoff, onValueChange = { cutoff = it }, valueRange = 0f..100f)
                }
                else -> {
                    Text("Disciplinas do edital", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${subjects.size} disciplina(s) · ${subjects.sumOf { it.questionCount ?: 0 }} questões · ${subjects.sumOf { s -> s.topics.count { it.diffStatus != "ARQUIVADO" } }} tópicos ativos",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (subjects.isEmpty()) Text("Nenhuma disciplina ainda. Adicione abaixo pelo catálogo ou crie uma personalizada.")
                    subjects.forEach { subject ->
                        key(subject.localSubjectId) {
                            SubjectEditor(
                                subject,
                                onChange = { updated -> subjects = subjects.map { if (it.localSubjectId == updated.localSubjectId) updated else it } },
                                onRemove = { subjects = subjects.filterNot { it.localSubjectId == subject.localSubjectId }; invalidSubjects = invalidSubjects - subject.localSubjectId },
                                onAddStage = {
                                    val index = subjects.indexOfFirst { it.localSubjectId == subject.localSubjectId }
                                    val copy = ExamCatalog.newComponent(subject).copy(stage = "")
                                    subjects = subjects.toMutableList().apply { add(index + 1, copy) }
                                },
                                onValidity = { valid -> invalidSubjects = if (valid) invalidSubjects - subject.localSubjectId else invalidSubjects + subject.localSubjectId }
                            )
                        }
                    }

                    HorizontalDivider()
                    Text("Adicionar disciplina", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(subjectSearch, { subjectSearch = it }, label = { Text("Buscar em todo o catálogo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    val catalog = remember { ExamCatalog.allSubjects() }
                    val suggested = remember(trunkSlug) { ExamCatalog.subjectsForTrunk(trunkSlug).map { it.canonicalSubjectId } }
                    val candidates = availableCatalogSubjects(catalog, subjects, subjectSearch, suggested)
                    val limit = if (showAllCatalog || subjectSearch.isNotBlank()) candidates.size else MAX_VISIBLE_CHOICES
                    when {
                        candidates.isEmpty() && subjectSearch.isNotBlank() -> Text("Nenhuma disciplina disponível com esse nome. Crie uma personalizada abaixo.")
                        candidates.isEmpty() -> Text("Todas as disciplinas do catálogo já estão nesta grade.")
                        subjectSearch.isBlank() -> Text("Sugestões do tronco primeiro. Disciplinas já adicionadas saem desta lista; para cobrá-la em outra etapa, use \"Adicionar outra etapa\" no cartão.", style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        candidates.take(limit).forEach { candidate ->
                            AssistChip(
                                onClick = { subjects = subjects + ExamCatalog.newComponent(candidate); subjectSearch = "" },
                                label = { Text("+ ${candidate.displayName}") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    if (subjectSearch.isBlank() && candidates.size > MAX_VISIBLE_CHOICES) {
                        TextButton(onClick = { showAllCatalog = !showAllCatalog }) {
                            Text(if (showAllCatalog) "Mostrar menos" else "Ver todas as ${candidates.size} disciplinas")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(customName, { customName = it.take(150) }, label = { Text("Nome de outra disciplina") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    OutlinedButton(enabled = customName.isNotBlank(), onClick = {
                        focusManager.clearFocus(); keyboard?.hide()
                        val id = "custom-${UUID.randomUUID()}"
                        subjects = subjects + StoredSubject(UUID.randomUUID().toString(), id, customName.trim(), "CONHECIMENTOS_ESPECIFICOS", 1.0, 5, "SPECIFIC", emptyList())
                        customName = ""
                    }) { Text("Adicionar disciplina personalizada") }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if (confirmDeleteExam && editingExam != null && onDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteExam = false },
            title = { Text("Excluir ${editingExam.shortName}?") },
            text = { Text("O concurso e sua grade sairão da sua lista. Seu histórico de respostas será mantido.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteExam = false; onDelete(editingExam) }) {
                    Text("Excluir concurso", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteExam = false }) { Text("Cancelar") }
            }
        )
    }
}
