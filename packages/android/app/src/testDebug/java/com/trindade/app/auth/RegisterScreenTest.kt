package com.trindade.app.auth

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI layout and interaction tests for [RegisterScreen].
 *
 * Verifies form element rendering, role notice presentation ("Trabalhador"),
 * error presentation, confirmation display, and scrollability on constrained viewports.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegisterScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Test
    fun `renders form fields, worker role notice, submit button and back button`() {
        composeRule.setContent {
            TrindadeTheme {
                RegisterScreen(
                    state = RegisterViewModel.UiState(),
                    onUsernameChange = {},
                    onDisplayNameChange = {},
                    onPasswordChange = {},
                    onConfirmPasswordChange = {},
                    onSubmit = {},
                    onBackToLogin = {},
                )
            }
        }

        composeRule.onNodeWithText(copy(R.string.register_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_worker_role_notice)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_username)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_display_name)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_password)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_confirm_password)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_submit)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).assertIsDisplayed()
    }

    @Test
    fun `renders neutral success confirmation and back action`() {
        val neutralMessage =
            "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."
        var backClicked = false

        composeRule.setContent {
            TrindadeTheme {
                RegisterScreen(
                    state = RegisterViewModel.UiState(successMessage = neutralMessage),
                    onUsernameChange = {},
                    onDisplayNameChange = {},
                    onPasswordChange = {},
                    onConfirmPasswordChange = {},
                    onSubmit = {},
                    onBackToLogin = { backClicked = true },
                )
            }
        }

        composeRule.onNodeWithText(copy(R.string.register_success_title)).assertIsDisplayed()
        composeRule.onNodeWithText(neutralMessage).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).performClick()
        assertTrue(backClicked)
    }

    @Test
    fun `renders validation and server error messages`() {
        composeRule.setContent {
            TrindadeTheme {
                RegisterScreen(
                    state = RegisterViewModel.UiState(message = RegisterMessage.Validation("As senhas não coincidem.")),
                    onUsernameChange = {},
                    onDisplayNameChange = {},
                    onPasswordChange = {},
                    onConfirmPasswordChange = {},
                    onSubmit = {},
                    onBackToLogin = {},
                )
            }
        }

        composeRule.onNodeWithText("As senhas não coincidem.").assertIsDisplayed()
    }

    @Test
    fun `renders rate limit message with retry-after seconds`() {
        composeRule.setContent {
            TrindadeTheme {
                RegisterScreen(
                    state = RegisterViewModel.UiState(
                        message = RegisterMessage.RateLimited("Limite excedido", retryAfterSeconds = 45),
                    ),
                    onUsernameChange = {},
                    onDisplayNameChange = {},
                    onPasswordChange = {},
                    onConfirmPasswordChange = {},
                    onSubmit = {},
                    onBackToLogin = {},
                )
            }
        }

        composeRule.onNodeWithText(copy(R.string.register_rate_limit_wait, 45)).assertIsDisplayed()
    }

    @Test
    fun `short viewport scrolls to submit button`() {
        composeRule.setContent {
            TrindadeTheme {
                Box(Modifier.size(width = 400.dp, height = 320.dp)) {
                    RegisterScreen(
                        state = RegisterViewModel.UiState(),
                        onUsernameChange = {},
                        onDisplayNameChange = {},
                        onPasswordChange = {},
                        onConfirmPasswordChange = {},
                        onSubmit = {},
                        onBackToLogin = {},
                    )
                }
            }
        }

        val submit = composeRule.onNodeWithText(copy(R.string.register_submit))
        submit.assertIsNotDisplayed()
        submit.assertHeightIsAtLeast(40.dp)
        submit.performScrollTo().assertIsDisplayed()
    }
}
