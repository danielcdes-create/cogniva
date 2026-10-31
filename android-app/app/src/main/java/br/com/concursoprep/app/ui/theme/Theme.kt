package br.com.concursoprep.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =========================================================================
// Identidade Visual Oficial COGNIVA:
// Azul-Marinho Profundo (Navy), Azul Elétrico (Electric Blue) e Branco Puro
// Slogan: "CONHECIMENTO EM EVOLUÇÃO · LEARN · PRACTICE · EVOLVE"
// =========================================================================
val CognivaDeepNavy = Color(0xFF071120)
val CognivaNavySurface = Color(0xFF0E1D34)
val CognivaNavyVariant = Color(0xFF172B4D)
val CognivaElectricBlue = Color(0xFF0052CC) // Azul Elétrico com alto contraste WCAG AA
val CognivaElectricBright = Color(0xFF38BDF8)
val CognivaPureWhite = Color(0xFFFFFFFF)

// Cores Semânticas de Desempenho (acessíveis em modo claro e escuro)
val InstitutionalBlue = CognivaElectricBlue
val PetrolTeal = Color(0xFF0284C7)
val CorrectGreen = Color(0xFF16A34A)
val ErrorRed = Color(0xFFDC2626)
val ReviewAmber = Color(0xFFD97706)
val EstimatedNeutral = Color(0xFF64748B)

@Immutable
data class ExtendedColors(
    val correct: Color,
    val onCorrect: Color,
    val correctContainer: Color,
    val onCorrectContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val neutral: Color,
    val onNeutral: Color
)

val DarkExtendedColors = ExtendedColors(
    correct = Color(0xFF4ADE80),
    onCorrect = Color(0xFF052E16),
    correctContainer = Color(0xFF14532D),
    onCorrectContainer = Color(0xFFDCFCE7),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    warning = Color(0xFFFBBF24),
    onWarning = Color(0xFF451A03),
    warningContainer = Color(0xFF78350F),
    onWarningContainer = Color(0xFFFEF3C7),
    neutral = Color(0xFF94A3B8),
    onNeutral = Color(0xFF0F172A)
)

val LightExtendedColors = ExtendedColors(
    correct = Color(0xFF16A34A),
    onCorrect = Color(0xFFFFFFFF),
    correctContainer = Color(0xFFDCFCE7),
    onCorrectContainer = Color(0xFF14532D),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    warning = Color(0xFFD97706),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
    neutral = Color(0xFF64748B),
    onNeutral = Color(0xFFFFFFFF)
)

val LocalExtendedColors = staticCompositionLocalOf {
    LightExtendedColors
}

val DarkColorScheme = darkColorScheme(
    primary = CognivaElectricBright,
    onPrimary = Color(0xFF041833),
    primaryContainer = Color(0xFF003D99),
    onPrimaryContainer = Color(0xFFE0EDFF),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF041833),
    secondaryContainer = Color(0xFF0A3459),
    onSecondaryContainer = Color(0xFFBAE6FD),
    background = CognivaDeepNavy,
    onBackground = Color(0xFFF8FAFC),
    surface = CognivaNavySurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = CognivaNavyVariant,
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceContainerLowest = Color(0xFF050D1A),
    surfaceContainerLow = Color(0xFF0B172A),
    surfaceContainer = CognivaNavySurface,
    surfaceContainerHigh = Color(0xFF132440),
    surfaceContainerHighest = Color(0xFF192F52),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

val LightColorScheme = lightColorScheme(
    primary = CognivaElectricBlue,
    onPrimary = CognivaPureWhite,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF002966),
    secondary = CognivaNavySurface,
    onSecondary = CognivaPureWhite,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF000000),
    surface = CognivaPureWhite,
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF1E293B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF8FAFC),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFCBD5E1),
    error = ErrorRed,
    onError = CognivaPureWhite,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val DarkColorPalette = DarkColorScheme
val LightColorPalette = LightColorScheme

/**
 * Símbolo Geométrico Neural COGNIVA (Cérebro + Conexões Neurais + Vetor de Evolução)
 */
@Composable
fun CognivaNeuralSymbol(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CognivaDeepNavy),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val w = size.width
            val h = size.height

            val n1 = Offset(w * 0.20f, h * 0.65f)
            val n2 = Offset(w * 0.35f, h * 0.30f)
            val n3 = Offset(w * 0.55f, h * 0.55f)
            val n4 = Offset(w * 0.82f, h * 0.22f)
            val n5 = Offset(w * 0.75f, h * 0.72f)

            drawLine(CognivaElectricBlue, n1, n2, strokeWidth = 4.5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBlue, n2, n3, strokeWidth = 4.5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBlue, n1, n3, strokeWidth = 3.5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBlue, n3, n5, strokeWidth = 3.5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBright, n2, n4, strokeWidth = 5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBright, n3, n4, strokeWidth = 5.5f, cap = StrokeCap.Round)

            drawCircle(CognivaPureWhite, radius = 3.8f, center = n1)
            drawCircle(CognivaElectricBright, radius = 4.2f, center = n2)
            drawCircle(CognivaPureWhite, radius = 4.2f, center = n3)
            drawCircle(CognivaPureWhite, radius = 5.0f, center = n4)
            drawCircle(CognivaElectricBright, radius = 3.5f, center = n5)
        }
    }
}

/**
 * Cabeçalho de Identidade do Ecossistema COGNIVA
 */
@Composable
fun CognivaBrandHeader(
    ecosystemProduct: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            CognivaNeuralSymbol()
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "COGNIVA",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            text = ecosystemProduct,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ConcursoPrepTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
