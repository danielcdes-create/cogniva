package br.com.concursoprep.app

import androidx.compose.ui.graphics.Color
import br.com.concursoprep.app.ui.theme.DarkColorScheme
import br.com.concursoprep.app.ui.theme.LightColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemeContrastTest {

    private fun compositeColor(foreground: Color, background: Color): Color {
        val fa = foreground.alpha
        val ba = background.alpha
        val outAlpha = fa + ba * (1f - fa)
        if (outAlpha == 0f) return Color.Transparent
        val outR = (foreground.red * fa + background.red * ba * (1f - fa)) / outAlpha
        val outG = (foreground.green * fa + background.green * ba * (1f - fa)) / outAlpha
        val outB = (foreground.blue * fa + background.blue * ba * (1f - fa)) / outAlpha
        return Color(outR, outG, outB, outAlpha)
    }

    private fun linearize(channel: Float): Double {
        return if (channel <= 0.04045f) {
            (channel / 12.92).toDouble()
        } else {
            ((channel + 0.055) / 1.055).toDouble().pow(2.4)
        }
    }

    private fun relativeLuminance(color: Color): Double {
        val r = linearize(color.red)
        val g = linearize(color.green)
        val b = linearize(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun calculateContrast(c1: Color, c2: Color): Double {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Compõe cores translúcidas sobre a superfície base opaca antes de medir o contraste WCAG.
     */
    private fun effectiveContrast(
        foreground: Color,
        container: Color,
        baseSurface: Color
    ): Double {
        val opaqueBg = if (container.alpha < 1f) {
            compositeColor(container, baseSurface)
        } else {
            container
        }
        val opaqueFg = if (foreground.alpha < 1f) {
            compositeColor(foreground, opaqueBg)
        } else {
            foreground
        }
        return calculateContrast(opaqueFg, opaqueBg)
    }

    @Test
    fun colorSchemes_meetWcagContrastRequirements() {
        listOf("Light" to LightColorScheme, "Dark" to DarkColorScheme).forEach { (name, cs) ->
            val textPairs = listOf(
                "onBackground/background" to Triple(cs.onBackground, cs.background, cs.background),
                "onSurface/surface" to Triple(cs.onSurface, cs.surface, cs.surface),
                "onSurface/surfaceContainer" to Triple(cs.onSurface, cs.surfaceContainer, cs.surface),
                "onSurfaceVariant/surfaceVariant" to Triple(cs.onSurfaceVariant, cs.surfaceVariant, cs.surface),
                "onPrimary/primary" to Triple(cs.onPrimary, cs.primary, cs.surface),
                "onPrimaryContainer/primaryContainer" to Triple(cs.onPrimaryContainer, cs.primaryContainer, cs.surface),
                "onSecondaryContainer/secondaryContainer" to Triple(cs.onSecondaryContainer, cs.secondaryContainer, cs.surface),
            )
            textPairs.forEach { (label, colors) ->
                val ratio = effectiveContrast(colors.first, colors.second, colors.third)
                assertTrue("$name [$label] contraste insuficiente: $ratio (< 4.5)", ratio >= 4.5)
            }

            // Verifica legibilidade mínima do estado desabilitado sobre surfaceContainer
            val disabledBg = cs.onSurface.copy(alpha = 0.12f)
            val disabledFg = cs.onSurface.copy(alpha = 0.38f)
            val disabledRatio = effectiveContrast(disabledFg, disabledBg, cs.surfaceContainer)
            assertTrue("$name [disabledButton] contraste muito baixo: $disabledRatio (< 2.5)", disabledRatio >= 2.5)
        }
    }
}
