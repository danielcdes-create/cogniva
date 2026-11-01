package br.com.concursoprep.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.AccountLinkUiState
import br.com.concursoprep.app.ui.screens.AccountLinkForm
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AccountLinkScreenshotTest(private val fontScale: Float) {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL.copy(fontScale = fontScale),
        theme = "android:Theme.Material.Light.NoActionBar",
        appCompatEnabled = false
    )

    @Test fun fieldsAndConfirmationStayTogether() {
        paparazzi.snapshot {
            ConcursoPrepTheme {
                Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AccountLinkForm(
                            "7084155621", {}, "user_7084155621", {},
                            "header." + "x".repeat(240) + ".signature", {},
                            AccountLinkUiState(), { _, _, _ -> }, Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}")
        fun fontScales() = listOf(arrayOf(1f), arrayOf(2f))
    }
}
