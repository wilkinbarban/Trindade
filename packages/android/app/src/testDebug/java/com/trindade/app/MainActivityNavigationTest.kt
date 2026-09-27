package com.trindade.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.auth.LoginScreen
import com.trindade.app.auth.LoginViewModel
import com.trindade.app.auth.RegisterScreen
import com.trindade.app.auth.RegisterViewModel
import com.trindade.app.auth.RolePolicy
import com.trindade.app.admin.CategoriesScreen
import com.trindade.app.admin.CategoriesViewModel
import com.trindade.app.admin.DriversScreen
import com.trindade.app.admin.DriversViewModel
import com.trindade.app.admin.TasksScreen
import com.trindade.app.admin.TasksViewModel
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The signed-out navigation `MainActivity` holds between login and registration, rendered.
 *
 * The activity keeps one flag for the registration form and draws it ahead of the login branch while
 * there is no session; these tests pin the surface that flag selects and the return trip it owns. They
 * render the two screens the activity composes -- [LoginScreen] and [RegisterScreen], the same pair
 * `LoginRoute` and `RegisterRoute` attach a view model to -- rather than the Hilt-built routes, so the
 * navigation between them is reachable from a JVM test without an activity or a dependency container.
 * That is the boundary this file covers: the screens and the callbacks that connect them. The one-line
 * branch in the activity that reads the flag is the same decision this host mirrors.
 *
 * Two things are deliberately not the screen's own business and are pinned here instead: the login
 * form does not navigate itself (its link reports one entry and the caller decides), and a registration
 * request is not a session -- the confirmation offers the way back to login and nothing that could sign
 * the operator in. The latter is the whole reason a successful request is not an approval: the server
 * still has to admit the account, so the only destination after success is the login form.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** The server's own neutral words, repeated verbatim rather than typed into the screen. */
    private val neutralMessage =
        "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."

    private fun copy(id: Int): String = context.getString(id)

    /**
     * The signed-out surface as the activity draws it: the registration form while [registerOpen] is
     * true, the login form otherwise, with the login form's own callback the only thing that opens it.
     *
     * [registerOpen] is a reader rather than a value so the host recomposes when the flag it mirrors
     * changes, exactly as the activity's own remembered state would.
     */
    private fun render(
        registerOpen: () -> Boolean,
        registerState: RegisterViewModel.UiState = RegisterViewModel.UiState(),
        onRegisterOpen: () -> Unit,
        onBackToLogin: () -> Unit,
        onSignedIn: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                if (registerOpen()) {
                    RegisterScreen(
                        state = registerState,
                        onUsernameChange = {},
                        onDisplayNameChange = {},
                        onPasswordChange = {},
                        onConfirmPasswordChange = {},
                        onSubmit = {},
                        onBackToLogin = onBackToLogin,
                    )
                } else {
                    LoginScreen(
                        state = LoginViewModel.UiState(),
                        onUsernameChange = {},
                        onPasswordChange = {},
                        onSubmit = onSignedIn,
                        onRegisterClick = onRegisterOpen,
                    )
                }
            }
        }
    }

    /**
     * The link leaves the login form for the registration form, and the registration form's back action
     * returns to the login form.
     *
     * Both screens are found by the labels the app ships rather than by strings typed here, so a
     * selector cannot drift from the copy. The link may sit below the fold on a short viewport -- the
     * login form is the one an operator meets in landscape -- so it is scrolled to before the press,
     * which is what makes this an assertion about reachability and not just presence.
     */
    @Test
    fun `login link opens registration and back returns to login without a session`() {
        var registerOpen by mutableStateOf(false)
        var signedIn by mutableStateOf(false)
        render(
            registerOpen = { registerOpen },
            onRegisterOpen = { registerOpen = true },
            onBackToLogin = { registerOpen = false },
            onSignedIn = { signedIn = true },
        )

        composeRule.onNodeWithText(copy(R.string.login_submit)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.login_register_link)).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.login_register_link)).performClick()

        composeRule.onNodeWithText(copy(R.string.register_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_worker_role_notice)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).performClick()

        composeRule.onNodeWithText(copy(R.string.login_submit)).assertIsDisplayed()
        assertFalse(signedIn)
    }

    /** The task tab's existing role and branch mirror remains covered beside the drivers destination. */
    @Test
    fun `task tab is reachable for administrator and worker but not unknown roles`() {
        var role by mutableStateOf(RolePolicy.ADMIN)
        var selected by mutableStateOf(false)
        composeRule.setContent {
            TrindadeTheme {
                if (selected) {
                    TasksScreen(
                        state = TasksViewModel.UiState(loading = false, role = role),
                        onBack = { selected = false }, onRefresh = {}, onCategoryChange = {},
                        onNamePtChange = {}, onNameEsChange = {}, onReadingsChange = {},
                        onSave = {}, onCancel = {}, onEdit = {}, onToggle = {}, onDelete = {},
                    )
                } else {
                    Column {
                        RolePolicy.visibleDestinations(role, listOf(
                            RolePolicy.EntryPoint.CATALOG_TASKS to "Tarefas",
                        )).filter { role == RolePolicy.ADMIN || role == RolePolicy.WORKER }
                            .forEach { label ->
                            androidx.compose.material3.TextButton(onClick = { selected = true }) {
                                androidx.compose.material3.Text(label)
                            }
                        }
                    }
                }
            }
        }
        listOf(RolePolicy.ADMIN, RolePolicy.WORKER).forEach { allowedRole ->
            role = allowedRole
            composeRule.onNodeWithText("Tarefas").performClick()
            composeRule.onNodeWithText("Nova tarefa").assertIsDisplayed()
            composeRule.onNodeWithText("Voltar").performClick()
            composeRule.onNodeWithText("Tarefas").assertIsDisplayed()
        }
        role = "unknown"
        composeRule.onNodeWithText("Tarefas").assertDoesNotExist()
    }

    /**
     * Mirrors MainActivity's drivers destination branch and role filter. This renders the screen and
     * exercises its callbacks, but does not launch or instrument MainActivity or its Hilt graph.
     */
    @Test
    fun `drivers tab is reachable for administrator and worker but not unknown roles`() {
        var role by mutableStateOf(RolePolicy.ADMIN)
        var selected by mutableStateOf(false)
        var refreshes = 0
        composeRule.setContent {
            TrindadeTheme {
                if (selected) {
                    DriversScreen(
                        state = DriversViewModel.UiState(loading = false, role = role),
                        onBack = { selected = false }, onRefresh = { refreshes++ },
                        onNameChange = {}, onLicensePlateChange = {}, onDriverTypeChange = {},
                        onSave = {}, onCancel = {}, onEdit = {}, onToggle = {},
                    )
                } else {
                    Column {
                        RolePolicy.visibleDestinations(role, listOf(
                            RolePolicy.EntryPoint.CATALOG_DRIVERS to copy(R.string.nav_drivers),
                        )).filter { role == RolePolicy.ADMIN || role == RolePolicy.WORKER }
                            .forEach { label ->
                            androidx.compose.material3.TextButton(onClick = { selected = true }) {
                                androidx.compose.material3.Text(label)
                            }
                        }
                    }
                }
            }
        }
        listOf(RolePolicy.ADMIN, RolePolicy.WORKER).forEach { allowedRole ->
            role = allowedRole
            composeRule.onNodeWithText(copy(R.string.nav_drivers)).performClick()
            composeRule.onNodeWithText("Motoristas").assertIsDisplayed()
            composeRule.onNodeWithText("Atualizar").performClick()
            composeRule.onNodeWithText("Voltar").performClick()
            composeRule.onNodeWithText(copy(R.string.nav_drivers)).assertIsDisplayed()
        }
        assert(refreshes == 2)
        role = "unknown"
        composeRule.onNodeWithText(copy(R.string.nav_drivers)).assertDoesNotExist()
    }

    /**
     * Mirrors MainActivity's categories destination branch and role filter. This renders the screen and
     * exercises its callbacks, but does not launch or instrument MainActivity or its Hilt graph.
     */
    @Test
    fun `categories tab is reachable for administrator but not worker, unknown or null roles`() {
        var role by mutableStateOf<String?>(RolePolicy.ADMIN)
        var selected by mutableStateOf(false)
        var refreshes = 0
        composeRule.setContent {
            TrindadeTheme {
                if (selected) {
                    CategoriesScreen(
                        state = CategoriesViewModel.UiState(loading = false, role = role),
                        onBack = { selected = false },
                        onRefresh = { refreshes++ },
                        onNameChange = {},
                        onNamePtChange = {},
                        onNameEsChange = {},
                        onCategoryTypeChange = {},
                        onSortOrderChange = {},
                        onSave = {},
                        onCancel = {},
                        onEdit = {},
                        onToggle = {},
                        onRequestDelete = {},
                        onConfirmDelete = {},
                        onDismissDelete = {},
                    )
                } else {
                    Column {
                        RolePolicy.visibleDestinations(
                            role,
                            listOf(
                                RolePolicy.EntryPoint.CATEGORIES to copy(R.string.nav_categories),
                            ),
                        ).filter { role == RolePolicy.ADMIN }
                            .forEach { label ->
                                androidx.compose.material3.TextButton(onClick = { selected = true }) {
                                    androidx.compose.material3.Text(label)
                                }
                            }
                    }
                }
            }
        }
        role = RolePolicy.ADMIN
        composeRule.onNodeWithText(copy(R.string.nav_categories)).performClick()
        composeRule.onNodeWithText("Categorias").assertIsDisplayed()
        composeRule.onNodeWithText("Atualizar").performClick()
        composeRule.onNodeWithText("Voltar").performClick()
        composeRule.onNodeWithText(copy(R.string.nav_categories)).assertIsDisplayed()
        assert(refreshes == 1)

        listOf(RolePolicy.WORKER, "unknown", null).forEach { disallowedRole ->
            role = disallowedRole
            composeRule.onNodeWithText(copy(R.string.nav_categories)).assertDoesNotExist()
        }
    }

    /**
     * A registration request that succeeded still ends on the login form, and it never signs anybody in.
     *
     * The confirmation is the whole of what a completed request earns: the neutral sentence, and one
     * action that returns to login. There is no signed-in surface behind it and no callback that could
     * open one, which is why the session flag stays false here -- approval is the administrator's, not
     * the client's.
     */
    @Test
    fun `successful registration returns to login and never signs in`() {
        var registerOpen by mutableStateOf(true)
        var signedIn by mutableStateOf(false)
        render(
            registerOpen = { registerOpen },
            registerState = RegisterViewModel.UiState(successMessage = neutralMessage),
            onRegisterOpen = { registerOpen = true },
            onBackToLogin = { registerOpen = false },
            onSignedIn = { signedIn = true },
        )

        composeRule.onNodeWithText(copy(R.string.register_success_title)).assertIsDisplayed()
        composeRule.onNodeWithText(neutralMessage).assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).performScrollTo().performClick()

        composeRule.onNodeWithText(copy(R.string.login_submit)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.login_register_link)).performScrollTo().assertIsDisplayed()
        assertFalse(signedIn)
    }
}
