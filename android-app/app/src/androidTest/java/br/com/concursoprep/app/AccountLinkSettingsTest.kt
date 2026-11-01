package br.com.concursoprep.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.concursoprep.app.domain.model.*
import br.com.concursoprep.app.ui.screens.SettingsScreen
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountLinkSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmsBesideTheCodeWithoutSavingPreferences() = verifyForm(1f)
    @Test fun largeFontKeepsAllFieldsAndConfirmationReachable() = verifyForm(2f)

    private fun verifyForm(fontScale: Float) {
        var state by mutableStateOf(AccountLinkUiState())
        val confirmations = mutableListOf<Triple<Long, String?, String>>()
        var preferencesSaved = 0
        val syntheticCode = "header." + "x".repeat(240) + ".signature"
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ConcursoPrepTheme {
                    SettingsScreen(
                        userProfile = UserProfileDto(7084155621, displayName = "Daniel Caires"),
                        themeMode = AppThemeMode.LIGHT,
                        apkUpdateState = ApkUpdateUiState(),
                        cachedQuestionsCount = 0,
                        pendingSyncCount = 0,
                        onSelectThemeMode = {},
                        onSaveUserSettings = { _, _, _, _, _, _, _ -> preferencesSaved++ },
                        onCheckApkUpdate = {}, onInstallApkUpdate = {}, onSyncNow = {},
                        onClearOfflineCache = {}, onReopenOnboarding = {},
                        accountLinkState = state,
                        onConfirmAccountLink = { id, username, code ->
                            confirmations += Triple(id, username, code)
                            state = AccountLinkUiState(isSubmitting = true)
                        }
                    )
                }
            }
        }
        rule.onNodeWithTag("account_link_confirm").performScrollTo().assertIsNotEnabled()
        // The broken Row left ID/username at almost zero width. Each real field
        // must retain usable width and height after being scrolled into view.
        val rootWidth = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        listOf("account_link_id", "account_link_username", "account_link_code").forEach { tag ->
            val node = rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag collapsed at fontScale=$fontScale", bounds.width > rootWidth * .7f)
            assertTrue("$tag lost its touch area", bounds.height > 40f)
        }
        rule.onNodeWithTag("account_link_username").performScrollTo().performTextInput("@user_7084155621")
        rule.onNodeWithTag("account_link_code").performScrollTo().performTextInput(syntheticCode)
        rule.onNodeWithTag("account_link_confirm").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag("account_link_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("account_link_code").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(listOf(Triple(7084155621L, "user_7084155621", syntheticCode)), confirmations)
            assertEquals(0, preferencesSaved)
            state = AccountLinkUiState(message = "Falha sintética. Tente novamente.", isError = true)
        }
        rule.onNodeWithTag("account_link_status").performScrollTo().assertTextEquals("Falha sintética. Tente novamente.")
        // A failed submission preserves the pasted code for a retry.
        rule.onNodeWithTag("account_link_confirm").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(2, confirmations.size)
            assertEquals(confirmations.first(), confirmations.last())
            assertEquals(0, preferencesSaved)
            state = AccountLinkUiState(isLinked = true, message = "Vínculo confirmado.")
        }
        rule.onNodeWithTag("account_link_status").performScrollTo().assertTextEquals("Vínculo confirmado.")
        rule.onNodeWithTag("account_link_confirm").performScrollTo().assertIsNotEnabled()
    }
}
