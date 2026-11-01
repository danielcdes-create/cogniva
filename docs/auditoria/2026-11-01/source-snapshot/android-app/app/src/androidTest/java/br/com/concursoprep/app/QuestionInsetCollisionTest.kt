package br.com.concursoprep.app

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.concursoprep.app.ui.QuestionInsetProbeActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fails when the question top bar intersects the status/cutout inset or the
 * "Confirmar Resposta" CTA intersects the navigation inset.
 */
@RunWith(AndroidJUnit4::class)
class QuestionInsetCollisionTest {
    @get:Rule
    val rule = createAndroidComposeRule<QuestionInsetProbeActivity>()

    @Test
    fun topBarClearsCutoutAndCtaClearsNavigation() {
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        rule.runOnUiThread {
            ViewCompat.requestApplyInsets(decor)
        }
        rule.waitForIdle()
        val measured = rule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(decor)
                ?: error("system insets unavailable on the question window")
            Triple(
                insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
                ).top,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                decor.height,
            )
        }
        val topObstacle = measured.first
        val navBottom = measured.second
        val windowHeight = measured.third.toFloat()
        assertTrue(
            "status/cutout inset was 0; the emulator did not expose a top inset",
            topObstacle > 0
        )
        assertTrue(
            "navigation inset was 0; the emulator did not expose a navigation inset",
            navBottom > 0
        )

        val topBar = rule.onNodeWithTag("question_top_bar").fetchSemanticsNode().boundsInWindow
        val cta = rule.onNodeWithTag("confirm_answer_cta").fetchSemanticsNode().boundsInWindow

        assertTrue(
            "top bar collides with status/cutout inset: top=${topBar.top} obstacle=$topObstacle",
            topBar.top >= topObstacle
        )
        assertTrue(
            "Confirmar Resposta CTA collides with navigation inset: bottom=${cta.bottom} limit=${windowHeight - navBottom}",
            cta.bottom <= windowHeight - navBottom
        )
        Log.i(
            "INSET_CHECK",
            "success topBar=${topBar.top} obstacle=$topObstacle ctaBottom=${cta.bottom} nav=$navBottom window=$windowHeight"
        )
    }
}
