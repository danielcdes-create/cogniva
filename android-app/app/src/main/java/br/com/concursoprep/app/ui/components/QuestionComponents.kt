package br.com.concursoprep.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.concursoprep.app.domain.exam.CognitiveStrategy
import br.com.concursoprep.app.domain.exam.DeclaredConfidence
import br.com.concursoprep.app.ui.theme.LocalExtendedColors

/**
 * Badge de letra de alternativa (A, B, C, D, E) com largura estritamente fixa (36dp).
 * Garante que o texto de todas as alternativas comece exatamente na mesma coordenada horizontal.
 */
@Composable
fun LetterBadge(
    letter: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    isCorrect: Boolean? = null
) {
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    val density = LocalDensity.current

    val badgeSize = with(density) {
        36.dp.coerceAtLeast(24.sp.toDp())
    }

    val (bgColor, textColor) = when {
        isCorrect == true -> ext.correct to ext.onCorrect
        isCorrect == false -> ext.error to ext.onError
        selected -> cs.primary to cs.onPrimary
        else -> cs.surfaceVariant to cs.onSurfaceVariant
    }

    Surface(
        shape = CircleShape,
        color = bgColor,
        contentColor = textColor,
        modifier = modifier.size(badgeSize)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Card padronizado de Alternativa (Múltipla Escolha) com suporte a WCAG AA e estados semânticos.
 */
@Composable
fun OptionCard(
    letter: String,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isAnswerSubmitted: Boolean = false,
    isCorrectOption: Boolean = false,
    hideFeedback: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    val showResult = isAnswerSubmitted && !hideFeedback

    val containerColor = when {
        showResult && isCorrectOption -> ext.correctContainer
        showResult && selected && !isCorrectOption -> ext.errorContainer
        selected -> cs.primaryContainer
        else -> cs.surfaceContainerLow
    }

    val contentColor = when {
        showResult && isCorrectOption -> ext.onCorrectContainer
        showResult && selected && !isCorrectOption -> ext.onErrorContainer
        selected -> cs.onPrimaryContainer
        else -> cs.onSurface
    }

    val borderColor = when {
        showResult && isCorrectOption -> ext.correct
        showResult && selected && !isCorrectOption -> ext.error
        selected -> cs.primary
        else -> cs.outlineVariant
    }

    val badgeState = when {
        showResult && isCorrectOption -> true
        showResult && selected && !isCorrectOption -> false
        else -> null
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(width = if (selected || showResult) 2.dp else 1.dp, color = borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            LetterBadge(letter = letter, selected = selected, isCorrect = badgeState)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp)
            )
        }
    }
}

/**
 * Linha de Chips de Ação da Questão ("Pedir Dica", "Dr. Sincero", "Simplificar Pergunta").
 * Elimina o vão vertical exagerado descontando o padding implícito do Material 3 (spacedBy = 0.dp).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionActionChips(
    hintCount: Int,
    onHint: () -> Unit,
    onSincereDoctor: () -> Unit,
    onSimplify: () -> Unit,
    modifier: Modifier = Modifier,
    onLookupLayer: (() -> Unit)? = null
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        AssistChip(
            onClick = onHint,
            label = {
                Text(
                    text = if (hintCount == 0) "Pedir Dica" else "Outra Dica (${hintCount + 1})",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        AssistChip(
            onClick = onSincereDoctor,
            label = {
                Text(
                    text = "Dr. Sincero",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.LocalFireDepartment,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        AssistChip(
            onClick = onSimplify,
            label = {
                Text(
                    text = "Simplificar Pergunta",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        if (onLookupLayer != null) {
            AssistChip(
                onClick = onLookupLayer,
                label = {
                    Text(
                        text = "Consultar / Google",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

/**
 * Seletor Metacognitivo Pré-Gabarito:
 * 1) Escala ordinal de 5 níveis de Confiança Declarada (`DeclaredConfidence`)
 * 2) Estratégia Cognitiva neutra e opcional ("Como você chegou à sua resposta?") sem pré-seleção.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfidenceSelectorGrid(
    selectedConfidence: Int,
    onSelectConfidence: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hasExplicitSelection: Boolean = true,
    selectedDeclaredConfidence: DeclaredConfidence? = null,
    onSelectDeclaredConfidence: ((DeclaredConfidence) -> Unit)? = null,
    showStrategySelector: Boolean = false,
    selectedStrategy: CognitiveStrategy? = null,
    strategySkipped: Boolean = false,
    onSelectStrategy: ((CognitiveStrategy?) -> Unit)? = null,
    onSkipStrategy: (() -> Unit)? = null
) {
    val activeDeclared = selectedDeclaredConfidence
        ?: if (hasExplicitSelection) DeclaredConfidence.fromLegacyBand(selectedConfidence, true) else null

    fun selectLevel(level: DeclaredConfidence) {
        onSelectDeclaredConfidence?.invoke(level)
        onSelectConfidence(level.toLegacyBand())
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (hasExplicitSelection) {
                        "Grau de confiança nesta resposta:"
                    } else {
                        "Selecione sua confiança antes de confirmar:"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val stackFullWidth = LocalDensity.current.fontScale >= 1.25f
                if (stackFullWidth) {
                    DeclaredConfidence.entries.forEach { level ->
                        ConfidenceButton(
                            label = level.labelPt,
                            selected = hasExplicitSelection && activeDeclared == level,
                            onClick = { selectLevel(level) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    // Linha 1: Chutei | Pouco confiante
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConfidenceButton(
                            label = DeclaredConfidence.GUESS.labelPt,
                            selected = hasExplicitSelection && activeDeclared == DeclaredConfidence.GUESS,
                            onClick = { selectLevel(DeclaredConfidence.GUESS) },
                            modifier = Modifier.weight(1f)
                        )
                        ConfidenceButton(
                            label = DeclaredConfidence.LOW.labelPt,
                            selected = hasExplicitSelection && activeDeclared == DeclaredConfidence.LOW,
                            onClick = { selectLevel(DeclaredConfidence.LOW) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Linha 2: Parcialmente confiante | Muito confiante
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConfidenceButton(
                            label = DeclaredConfidence.MEDIUM.labelPt,
                            selected = hasExplicitSelection && activeDeclared == DeclaredConfidence.MEDIUM,
                            onClick = { selectLevel(DeclaredConfidence.MEDIUM) },
                            modifier = Modifier.weight(1f)
                        )
                        ConfidenceButton(
                            label = DeclaredConfidence.HIGH.labelPt,
                            selected = hasExplicitSelection && activeDeclared == DeclaredConfidence.HIGH,
                            onClick = { selectLevel(DeclaredConfidence.HIGH) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Linha 3: Praticamente certo
                    ConfidenceButton(
                        label = DeclaredConfidence.VERY_HIGH.labelPt,
                        selected = hasExplicitSelection && activeDeclared == DeclaredConfidence.VERY_HIGH,
                        onClick = { selectLevel(DeclaredConfidence.VERY_HIGH) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (showStrategySelector && onSelectStrategy != null) {
                val cs = MaterialTheme.colorScheme
                val isGuessSelected = hasExplicitSelection && activeDeclared == DeclaredConfidence.GUESS
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGuessSelected) {
                            "Natureza do chute (opcional):"
                        } else {
                            "Como você chegou à sua resposta?"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            onSelectStrategy(null)
                            onSkipStrategy?.invoke()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (strategySkipped && selectedStrategy == null) cs.secondaryContainer else cs.surface,
                            contentColor = if (strategySkipped && selectedStrategy == null) cs.onSecondaryContainer else cs.onSurfaceVariant
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (strategySkipped && selectedStrategy == null) cs.primary else cs.outlineVariant
                        ),
                        modifier = Modifier.heightIn(min = 38.dp)
                    ) {
                        Text(
                            text = if (strategySkipped && selectedStrategy == null) "Etapa pulada" else "Pular",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!strategySkipped || selectedStrategy != null) {
                    val displayedOptions: List<Pair<CognitiveStrategy, String>> = if (isGuessSelected) {
                        val baseGuessOptions = mutableListOf(
                            CognitiveStrategy.RANDOM_GUESS to "Chute totalmente aleatório.",
                            CognitiveStrategy.ELIMINATION to "Chute após eliminar alternativas.",
                            CognitiveStrategy.INTUITION to "Chute por intuição ou reconhecimento parcial."
                        )
                        // Preserva seleção explícita prévia (nunca oculta nem sobrescreve silenciosamente)
                        if (selectedStrategy != null && baseGuessOptions.none { it.first == selectedStrategy }) {
                            baseGuessOptions += (selectedStrategy to selectedStrategy.labelPt)
                        }
                        baseGuessOptions
                    } else {
                        CognitiveStrategy.entries.map { it to it.labelPt }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        displayedOptions.forEach { (strat, displayLabel) ->
                            val isSel = selectedStrategy == strat
                            StrategyRadioOptionRow(
                                label = displayLabel,
                                selected = isSel,
                                onClick = {
                                    val nextStrat = if (isSel) null else strat
                                    if (nextStrat == CognitiveStrategy.RANDOM_GUESS && !hasExplicitSelection) {
                                        selectLevel(DeclaredConfidence.GUESS)
                                    }
                                    onSelectStrategy(nextStrat)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyRadioOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) cs.secondaryContainer else cs.surface,
        contentColor = if (selected) cs.onSecondaryContainer else cs.onSurface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) cs.primary else cs.outlineVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(
                        width = 2.dp,
                        color = if (selected) cs.primary else cs.outline,
                        shape = RoundedCornerShape(9.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = cs.primary,
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ConfidenceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val a11yModifier = modifier
        .heightIn(min = 48.dp)
        .semantics {
            role = Role.RadioButton
            this.selected = selected
        }
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = cs.primary,
                contentColor = cs.onPrimary
            ),
            modifier = a11yModifier
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = cs.onSurface
            ),
            border = BorderStroke(1.dp, cs.outlineVariant),
            modifier = a11yModifier
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
