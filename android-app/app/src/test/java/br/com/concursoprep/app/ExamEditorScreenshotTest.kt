package br.com.concursoprep.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.exam.ExamCatalog
import br.com.concursoprep.app.ui.screens.ExamFormContent
import br.com.concursoprep.app.ui.screens.ExamManagementScreen
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ExamEditorScreenshotTest(private val fontScale: Float) {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL.copy(screenWidth = 1080, screenHeight = 2400, fontScale = fontScale),
        theme = "android:Theme.Material.Light.NoActionBar",
        appCompatEnabled = false
    )

    private val exam = ExamCatalog.systemExams().first().copy(
        shortName = "BCB 2027 - EF", institution = "Banco Central do Brasil (BCB)",
        role = "Analista – Área: Economia e Finanças", trunkSlug = "bancaria", examBoards = listOf("Cebraspe")
    )

    @Test fun editorReservesSpaceForActionsAndSystemNavigation() {
        paparazzi.snapshot {
            ConcursoPrepTheme(darkTheme = true) {
                ExamFormContent(
                    listOf(exam), exam, onDismiss = {}, onSave = {}, onDelete = {},
                    windowInsets = WindowInsets(top = 24.dp, bottom = 48.dp)
                )
            }
        }
    }

    @Test fun managementHasNoInlineExplanationCard() {
        paparazzi.snapshot {
            ConcursoPrepTheme(darkTheme = true) {
                ExamManagementScreen(
                    listOf(exam), 0, onBack = {}, onCreate = {}, onEdit = {}, onArchive = {},
                    onRestore = {}, onDelete = {}, onPending = {}, onUseServer = {}
                )
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "fontScale={0}")
        fun fonts() = listOf(arrayOf(1f), arrayOf(2f))
    }
}
