package br.com.concursoprep.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import br.com.concursoprep.app.ui.screens.QuestionSessionPreviewContent

/**
 * Debug-only host used by the headless emulator inset check.
 * Shows the same question session the previews render.
 */
class QuestionInsetProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            QuestionSessionPreviewContent()
        }
    }
}
