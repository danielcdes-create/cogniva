package br.com.concursoprep.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalFireDepartment
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(width = if (selected || showResult) 2.dp else 1.dp, color = borderColor),
        modifier = modifier.fillMaxWidth()
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
    modifier: Modifier = Modifier
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
    }
}

/**
 * Seletor de Nível de Confiança em Grade 2x2 simétrica com 100% da largura.
 */
@Composable
fun ConfidenceSelectorGrid(
    selectedConfidence: Int,
    onSelectConfidence: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
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
            Text(
                text = "Nível de confiança nesta resposta:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            val options = listOf(
                0 to "Nenhuma Ideia",
                1 to "Chutei",
                2 to "Mais ou Menos",
                3 to "Certeza Absoluta"
            )

            // Linha 1: Opções 0 e 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConfidenceButton(
                    label = options[0].second,
                    selected = selectedConfidence == options[0].first,
                    onClick = { onSelectConfidence(options[0].first) },
                    modifier = Modifier.weight(1f)
                )
                ConfidenceButton(
                    label = options[1].second,
                    selected = selectedConfidence == options[1].first,
                    onClick = { onSelectConfidence(options[1].first) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Linha 2: Opções 2 e 3
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConfidenceButton(
                    label = options[2].second,
                    selected = selectedConfidence == options[2].first,
                    onClick = { onSelectConfidence(options[2].first) },
                    modifier = Modifier.weight(1f)
                )
                ConfidenceButton(
                    label = options[3].second,
                    selected = selectedConfidence == options[3].first,
                    onClick = { onSelectConfidence(options[3].first) },
                    modifier = Modifier.weight(1f)
                )
            }
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
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = cs.primary,
                contentColor = cs.onPrimary
            ),
            modifier = modifier.heightIn(min = 48.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = cs.onSurface
            ),
            border = BorderStroke(1.dp, cs.outlineVariant),
            modifier = modifier.heightIn(min = 48.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
