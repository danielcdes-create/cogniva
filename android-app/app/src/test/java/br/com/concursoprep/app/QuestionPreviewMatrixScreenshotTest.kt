package br.com.concursoprep.app

import android.content.res.Configuration
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import br.com.concursoprep.app.ui.theme.PreviewMatrix
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/**
 * Renders every shipped `@PreviewMatrix` variant of the question UI, including
 * "Font Scale 2.0", and compares the bitmap to the committed Paparazzi baseline.
 */
@RunWith(Parameterized::class)
class QuestionPreviewMatrixScreenshotTest(
    private val preview: ComposablePreview<AndroidPreviewInfo>,
) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = deviceConfigFor(preview),
        theme = "android:Theme.Material.Light.NoActionBar",
        appCompatEnabled = false,
    )

    @Test
    fun matchesApprovedBaseline() {
        check(preview.previewInfo.name.isNotBlank()) {
            "PreviewMatrix variant is missing a name: ${preview.methodName}"
        }
        paparazzi.snapshot(name = screenshotName(preview)) {
            preview()
        }
    }

    companion object {
        private const val FONT_SCALE_2 = "Font Scale 2.0"

        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun previews(): List<Array<Any>> {
            val found = AndroidComposablePreviewScanner()
                .scanPackageTrees("br.com.concursoprep.app.ui")
                .includeIfAnnotatedWithAnyOf(PreviewMatrix::class.java)
                .getPreviews()
            check(found.any { it.previewInfo.name == FONT_SCALE_2 && it.previewInfo.fontScale == 2.0f }) {
                "PreviewMatrix did not yield a \"$FONT_SCALE_2\" variant. Found: " +
                    found.joinToString { "${it.previewInfo.name}@${it.previewInfo.fontScale}" }
            }
            return found.map { arrayOf(it as Any) }
        }

        fun screenshotName(preview: ComposablePreview<AndroidPreviewInfo>): String {
            return preview.previewInfo.name
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
        }

        fun deviceConfigFor(preview: ComposablePreview<AndroidPreviewInfo>): DeviceConfig {
            val info = preview.previewInfo
            val night = (info.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val densityScale = DeviceConfig.PIXEL.density.dpiValue / 160f
            val widthPx = if (info.widthDp > 0) (info.widthDp * densityScale).toInt() else DeviceConfig.PIXEL.screenWidth
            val heightPx = if (info.heightDp > 0) (info.heightDp * densityScale).toInt() else DeviceConfig.PIXEL.screenHeight
            return DeviceConfig.PIXEL.copy(
                screenWidth = widthPx,
                screenHeight = heightPx,
                fontScale = info.fontScale,
                nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
            )
        }
    }
}
