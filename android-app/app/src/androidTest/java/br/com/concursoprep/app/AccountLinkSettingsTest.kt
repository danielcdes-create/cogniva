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

    @Test fun authenticatesWithoutSavingPreferencesAndSupportsLogout() = verifyForm(1f)
    @Test fun largeFontKeepsAllFieldsAndAuthenticationReachable() = verifyForm(2f)

    private fun verifyForm(fontScale: Float) {
        var profile by mutableStateOf(
            UserProfileDto(
                userId = 7084155621,
                displayName = "Daniel Caires",
                role = "student",
                isGuest = true
            )
        )
        var state by mutableStateOf(AccountAuthUiState())
        val authAttempts = mutableListOf<Triple<String, String, Boolean>>()
        var logoutCalls = 0
        var preferencesSaved = 0

        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ConcursoPrepTheme {
                    SettingsScreen(
                        userProfile = profile,
                        themeMode = AppThemeMode.LIGHT,
                        apkUpdateState = ApkUpdateUiState(),
                        cachedQuestionsCount = 0,
                        pendingSyncCount = 0,
                        onSelectThemeMode = {},
                        onSaveUserSettings = { _, _, _, _ -> preferencesSaved++ },
                        onCheckApkUpdate = {},
                        onInstallApkUpdate = {},
                        onSyncNow = {},
                        onClearOfflineCache = {},
                        onReopenOnboarding = {},
                        accountAuthState = state,
                        onAuthenticateAccount = { email, password, _, isRegister ->
                            authAttempts += Triple(email, password, isRegister)
                            state = AccountAuthUiState(isSubmitting = true)
                        },
                        onLogoutAccount = {
                            logoutCalls++
                            profile = profile.copy(email = null, isGuest = true)
                            state = AccountAuthUiState(message = "Sessão encerrada neste aparelho.")
                        }
                    )
                }
            }
        }

        rule.onNodeWithTag("account_auth_submit").performScrollTo().assertIsNotEnabled()
        val rootWidth = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        listOf("account_auth_email", "account_auth_password").forEach { tag ->
            val node = rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag collapsed at fontScale=$fontScale", bounds.width > rootWidth * .7f)
            assertTrue("$tag lost its touch area", bounds.height > 40f)
        }

        rule.onNodeWithTag("account_auth_mode_register").performScrollTo().performClick()
        rule.onNodeWithTag("account_auth_email").performScrollTo().performTextInput("estudante@cogniva.app")
        rule.onNodeWithTag("account_auth_password").performScrollTo().performTextInput("curta")
        // Register mode requires at least 10 characters for password
        rule.onNodeWithTag("account_auth_submit").performScrollTo().assertIsNotEnabled()

        rule.onNodeWithTag("account_auth_password").performScrollTo().performTextClearance()
        rule.onNodeWithTag("account_auth_password").performScrollTo().performTextInput("SenhaForte#2026")
        rule.onNodeWithTag("account_auth_submit").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag("account_auth_submit").assertIsNotEnabled()
        rule.onNodeWithTag("account_auth_email").assertIsNotEnabled()

        rule.runOnIdle {
            assertEquals(listOf(Triple("estudante@cogniva.app", "SenhaForte#2026", true)), authAttempts)
            assertEquals(0, preferencesSaved)
            state = AccountAuthUiState(message = "Falha sintética. Tente novamente.", isError = true)
        }

        rule.onNodeWithTag("account_auth_status").performScrollTo().assertTextEquals("Falha sintética. Tente novamente.")
        // A failed submission preserves typed credentials for retry
        rule.onNodeWithTag("account_auth_submit").performScrollTo().assertIsEnabled().performClick()

        rule.runOnIdle {
            assertEquals(2, authAttempts.size)
            assertEquals(authAttempts.first(), authAttempts.last())
            assertEquals(0, preferencesSaved)
            profile = profile.copy(email = "estudante@cogniva.app", isGuest = false)
            state = AccountAuthUiState(isAuthenticated = true, message = "Conta criada e vinculada com sucesso.")
        }

        rule.onNodeWithTag("account_auth_status").performScrollTo().assertTextEquals("Conta criada e vinculada com sucesso.")
        rule.onNodeWithTag("account_auth_logout").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, logoutCalls)
        }
    }
}
