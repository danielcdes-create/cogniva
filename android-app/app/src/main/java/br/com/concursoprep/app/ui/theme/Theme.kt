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
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
val CognivaElectricBlue = Color(0xFF0066FF)
val CognivaElectricBright = Color(0xFF38BDF8)
val CognivaPureWhite = Color(0xFFFFFFFF)

// Cores Semânticas de Desempenho
val InstitutionalBlue = CognivaElectricBlue
val PetrolTeal = Color(0xFF0284C7)
val CorrectGreen = Color(0xFF16A34A)
val ErrorRed = Color(0xFFDC2626)
val ReviewAmber = Color(0xFFD97706)
val EstimatedNeutral = Color(0xFF64748B)

private val DarkColorPalette = darkColorScheme(
    primary = CognivaElectricBright,
    onPrimary = CognivaDeepNavy,
    primaryContainer = Color(0xFF003D99),
    onPrimaryContainer = CognivaPureWhite,
    secondary = CognivaElectricBlue,
    onSecondary = CognivaPureWhite,
    background = CognivaDeepNavy,
    surface = CognivaNavySurface,
    surfaceVariant = CognivaNavyVariant,
    onBackground = CognivaPureWhite,
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = ErrorRed
)

private val LightColorPalette = lightColorScheme(
    primary = CognivaElectricBlue,
    onPrimary = CognivaPureWhite,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = CognivaDeepNavy,
    secondary = CognivaDeepNavy,
    onSecondary = CognivaPureWhite,
    background = Color(0xFFF4F7FB),
    surface = CognivaPureWhite,
    surfaceVariant = Color(0xFFE6EEF8),
    onBackground = CognivaDeepNavy,
    onSurface = Color(0xFF0E1D34),
    onSurfaceVariant = Color(0xFF475569),
    error = ErrorRed
)

// Tipografia densa e legível — Enunciado de questão nunca abaixo de 22sp
val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

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
            val n4 = Offset(w * 0.82f, h * 0.22f) // Ápice de evolução
            val n5 = Offset(w * 0.75f, h * 0.72f)

            // Conexões sinápticas em Azul Elétrico
            drawLine(CognivaElectricBlue, n1, n2, strokeWidth = 4.5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBlue, n2, n3, strokeWidth = 4.5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBlue, n1, n3, strokeWidth = 3.5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBlue, n3, n5, strokeWidth = 3.5f, cap = StrokeCap.Round)
            // Vetor ascendente de evolução em Azul Luminoso + Branco
            drawLine(CognivaElectricBright, n2, n4, strokeWidth = 5f, cap = StrokeCap.Round)
            drawLine(CognivaElectricBright, n3, n4, strokeWidth = 5.5f, cap = StrokeCap.Round)

            // Nós Neurais (Branco Puro e Azul Elétrico)
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
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CognivaNeuralSymbol()
            Spacer(modifier = Modifier.width(12.dp))
            Column {
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
                        color = CognivaElectricBlue.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = ecosystemProduct,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
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
    val colorScheme = if (darkTheme) DarkColorPalette else LightColorPalette
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
