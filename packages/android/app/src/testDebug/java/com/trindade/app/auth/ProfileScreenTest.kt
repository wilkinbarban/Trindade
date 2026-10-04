package com.trindade.app.auth

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun user() = ProfileResponseUser(
        id = 1,
        username = "operador",
        displayName = "Operador Silva",
        role = "Trabalhador",
    )

    @Test
    fun `navigation back and profile actions meet 48dp touch targets and invoke callbacks`() {
        var backed = 0
        var signedOut = 0

        val state = ProfileViewModel.UiState(
            loading = false,
            profile = user(),
            displayName = "Novo Nome",
            currentPassword = "oldpassword123",
            newPassword = "newpassword123",
        )

        composeRule.setContent {
            TrindadeTheme {
                ProfileScreen(
                    state = state,
                    appVersion = "0.3.0",
                    releasesUrl = "https://example.com/releases",
                    onBack = { backed++ },
                    onDisplayNameChange = {},
                    onSave = {},
                    onCurrentPasswordChange = {},
                    onNewPasswordChange = {},
                    onChangePassword = {},
                    onSignOut = { signedOut++ },
                    onBackToLogin = {},
                )
            }
        }

        val backNode = composeRule.onNodeWithText(copy(R.string.report_back))
        backNode.assertIsDisplayed()
        val backBounds = backNode.getUnclippedBoundsInRoot()
        assertTrue("Back height >= 48dp", backBounds.bottom - backBounds.top >= 48.dp)
        assertTrue("Back width >= 48dp", backBounds.right - backBounds.left >= 48.dp)
        backNode.performClick()
        assertEquals(1, backed)

        val saveNode = composeRule.onNodeWithText(copy(R.string.profile_save))
        saveNode.performScrollTo().assertIsDisplayed()
        val saveBounds = saveNode.getUnclippedBoundsInRoot()
        assertTrue("Save height >= 48dp", saveBounds.bottom - saveBounds.top >= 48.dp)

        val changePwNode = composeRule.onNode(
            hasText(copy(R.string.profile_change_password)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        )
        changePwNode.performScrollTo().assertIsDisplayed()
        val changePwBounds = changePwNode.getUnclippedBoundsInRoot()
        assertTrue("Change password height >= 48dp", changePwBounds.bottom - changePwBounds.top >= 48.dp)

        val signOutNode = composeRule.onNodeWithText(copy(R.string.profile_sign_out))
        signOutNode.performScrollTo().assertIsDisplayed()
        val signOutBounds = signOutNode.getUnclippedBoundsInRoot()
        assertTrue("Sign out height >= 48dp", signOutBounds.bottom - signOutBounds.top >= 48.dp)
        signOutNode.performClick()
        assertEquals(1, signedOut)

        val downloadNode = composeRule.onNodeWithText(copy(R.string.profile_download_update))
        downloadNode.performScrollTo().assertIsDisplayed()
        val downloadBounds = downloadNode.getUnclippedBoundsInRoot()
        assertTrue("Download height >= 48dp", downloadBounds.bottom - downloadBounds.top >= 48.dp)
    }

    @Test
    fun `signed out panel back to login action meets 48dp touch target and invokes callback`() {
        var backToLogin = 0
        val state = ProfileViewModel.UiState(
            signedOut = true,
            message = "Sessão encerrada com sucesso.",
        )

        composeRule.setContent {
            TrindadeTheme {
                ProfileScreen(
                    state = state,
                    appVersion = "0.3.0",
                    releasesUrl = "https://example.com/releases",
                    onBack = {},
                    onDisplayNameChange = {},
                    onSave = {},
                    onCurrentPasswordChange = {},
                    onNewPasswordChange = {},
                    onChangePassword = {},
                    onSignOut = {},
                    onBackToLogin = { backToLogin++ },
                )
            }
        }

        val buttonNode = composeRule.onNodeWithText(copy(R.string.profile_back_to_login))
        buttonNode.assertIsDisplayed()
        val bounds = buttonNode.getUnclippedBoundsInRoot()
        assertTrue("Back to login height >= 48dp", bounds.bottom - bounds.top >= 48.dp)
        buttonNode.performClick()
        assertEquals(1, backToLogin)
    }
}
