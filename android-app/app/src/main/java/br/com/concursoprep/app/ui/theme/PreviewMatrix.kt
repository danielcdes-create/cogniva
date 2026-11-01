package br.com.concursoprep.app.ui.theme

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Android Studio previews include the dimensions below. The JVM Paparazzi
 * gate uses stock PIXEL geometry for every variant and varies font/night mode.
 * Real S23 Ultra geometry and cutout are verified by the API 35 emulator job.
 */
@Preview(name = "Light Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 411, heightDp = 891)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 411, heightDp = 891)
@Preview(name = "Font Scale 1.3", fontScale = 1.3f, showBackground = true, widthDp = 411, heightDp = 891)
@Preview(name = "Font Scale 2.0", fontScale = 2.0f, showBackground = true, widthDp = 411, heightDp = 1600)
@Preview(name = "Phone Small (360dp)", widthDp = 360, heightDp = 640, showBackground = true)
@Preview(name = "S23 Ultra Default (411dp)", widthDp = 411, heightDp = 891, showBackground = true)
@Preview(name = "Foldable/Tablet (700dp)", widthDp = 700, heightDp = 900, showBackground = true)
annotation class PreviewMatrix
