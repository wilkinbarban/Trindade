package com.trindade.app.auth

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegisterScreenTest {
    @get:Rule val rule = createComposeRule()
    private fun text(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    @Test fun `form shows worker notice and four fields with disabled empty submit`() {
        rule.setContent { TrindadeTheme { Screen(RegisterViewModel.UiState()) } }
        listOf(R.string.register_worker_role_notice, R.string.register_username,
            R.string.register_display_name, R.string.register_password,
            R.string.register_confirm_password).forEach { rule.onNodeWithText(text(it)).assertIsDisplayed() }
        rule.onNodeWithText(text(R.string.register_submit)).assertIsNotEnabled()
    }

    @Test fun `ready form submits once and pending state prevents another submission`() {
        val state = mutableStateOf(RegisterViewModel.UiState(
            username = "worker", displayName = "Worker", password = "password123", confirmPassword = "password123",
        ))
        var submitted = 0
        rule.setContent { TrindadeTheme { Screen(state.value, onSubmit = { submitted++ }) } }
        rule.onNodeWithText(text(R.string.register_submit)).assertIsEnabled().performClick()
        assertEquals(1, submitted)
        rule.runOnUiThread { state.value = state.value.copy(submitting = true) }
        rule.onNodeWithText(text(R.string.register_submit)).assertDoesNotExist()
        assertEquals(1, submitted)
    }

    @Test fun `success replaces form and back action returns to login`() {
        var backs = 0
        val confirmation = "Solicitação recebida se aprovada."
        rule.setContent { TrindadeTheme { Screen(
            RegisterViewModel.UiState(successMessage = confirmation), onBack = { backs++ },
        ) } }
        rule.onNodeWithText(confirmation).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.register_username)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.register_back_to_login)).performClick()
        assertEquals(1, backs)
    }

    @Test fun `validation and server refusals render the message the layers above produced`() {
        val state = mutableStateOf(RegisterViewModel.UiState(
            message = RegisterMessage.Validation(RegisterViewModel.PASSWORD_MISMATCH)))
        rule.setContent { TrindadeTheme { Screen(state.value) } }
        rule.onNodeWithText(RegisterViewModel.PASSWORD_MISMATCH).assertIsDisplayed()
        val refusal = "O nome de usuário já está em uso."
        rule.runOnUiThread { state.value = state.value.copy(message = RegisterMessage.FromServer(refusal)) }
        rule.onNodeWithText(refusal).assertIsDisplayed()
        rule.onNodeWithText(RegisterViewModel.PASSWORD_MISMATCH).assertDoesNotExist()
    }

    @Test fun `rate limit shows the seconds the server sent, or its own words when it sent none`() {
        val serverWords = "Muitas solicitações deste endereço."
        val state = mutableStateOf(RegisterViewModel.UiState(
            message = RegisterMessage.RateLimited(serverWords, retryAfterSeconds = 45)))
        rule.setContent { TrindadeTheme { Screen(state.value) } }
        val wait = text(R.string.register_rate_limit_wait, 45)
        rule.onNodeWithText(wait).assertIsDisplayed()
        rule.onNodeWithText(serverWords).assertDoesNotExist()
        rule.runOnUiThread { state.value = state.value.copy(message = RegisterMessage.RateLimited(serverWords)) }
        rule.onNodeWithText(serverWords).assertIsDisplayed()
        rule.onNodeWithText(wait).assertDoesNotExist()
    }

    @Test fun `each transport cause renders its own sentence`() {
        val state = mutableStateOf(RegisterViewModel.UiState(message = RegisterMessage.Timeout))
        rule.setContent { TrindadeTheme { Screen(state.value) } }
        listOf(
            RegisterMessage.Timeout to R.string.login_timeout,
            RegisterMessage.Tls to R.string.login_tls,
            RegisterMessage.UnreadableBody to R.string.login_unreadable_body,
            RegisterMessage.Unreachable to R.string.login_unreachable,
            RegisterMessage.Unknown to R.string.register_unknown,
        ).forEach { (cause, sentence) ->
            rule.runOnUiThread { state.value = state.value.copy(message = cause) }
            rule.onNodeWithText(text(sentence)).assertIsDisplayed()
        }
    }

    @Test fun `a 320dp viewport scrolls to the submit action instead of squeezing it`() {
        rule.setContent { TrindadeTheme {
            Box(Modifier.size(width = 400.dp, height = 320.dp)) { Screen(RegisterViewModel.UiState(
                username = "worker", displayName = "Worker",
                password = "password123", confirmPassword = "password123")) }
        } }
        val submit = rule.onNodeWithText(text(R.string.register_submit))
        submit.assertIsNotDisplayed()
        submit.assertHeightIsAtLeast(40.dp)
        submit.performScrollTo().assertIsDisplayed()
    }

    @Test fun `password fields draw the mask and stay declared as obscured`() {
        val secret = "senha-secreta-123"
        rule.setContent { TrindadeTheme { Screen(RegisterViewModel.UiState(
            password = secret, confirmPassword = secret)) } }
        // A password field's displayed value is the visual transformation's output, and the platform
        // declares it obscured far enough down that accessibility knows to hide it too.
        rule.onAllNodesWithText("\u2022".repeat(secret.length)).assertCountEquals(2)
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)).assertCountEquals(2)
    }

    @Test fun `pending submission disables the back action`() {
        var backs = 0
        rule.setContent { TrindadeTheme { Screen(RegisterViewModel.UiState(
            username = "worker", displayName = "Worker",
            password = "password123", confirmPassword = "password123", submitting = true),
            onBack = { backs++ }) } }
        rule.onNodeWithText(text(R.string.register_back_to_login)).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertEquals(0, backs)
    }

    @androidx.compose.runtime.Composable
    private fun Screen(state: RegisterViewModel.UiState, onSubmit: () -> Unit = {}, onBack: () -> Unit = {}) {
        RegisterScreen(state, {}, {}, {}, {}, onSubmit, onBack)
    }
}
