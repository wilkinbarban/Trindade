package com.trindade.app.auth

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

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

    @androidx.compose.runtime.Composable
    private fun Screen(state: RegisterViewModel.UiState, onSubmit: () -> Unit = {}, onBack: () -> Unit = {}) {
        RegisterScreen(state, {}, {}, {}, {}, onSubmit, onBack)
    }
}
