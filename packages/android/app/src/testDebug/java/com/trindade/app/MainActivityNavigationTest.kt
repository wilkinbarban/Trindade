package com.trindade.app

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.auth.LoginScreen
import com.trindade.app.auth.LoginViewModel
import com.trindade.app.auth.RegisterScreen
import com.trindade.app.auth.RegisterViewModel
import com.trindade.app.auth.RolePolicy
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Navigation shell tests for [MainAppShell] and [AppRoot].
 *
 * Verifies that the Hilt-free top-level navigation shell renders the branded header with
 * profile action, displays the active Material3 tab indicator across visible destinations
 * (Dashboard, Reports, Loading, Tasks, Drivers, and Admin), respects [RolePolicy] filtering,
 * properly dispatches tab selection and profile open events, and confirms [AppRoot] hosts
 * application destinations with safe drawing insets.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun render(
        role: String? = RolePolicy.WORKER,
        currentTab: MainActivity.Tab = MainActivity.Tab.DASHBOARD,
        onSelectTab: (MainActivity.Tab) -> Unit = {},
        onOpenProfile: () -> Unit = {},
        destinations: List<Pair<RolePolicy.EntryPoint, MainActivity.Tab>> = MainActivity.DEFAULT_DESTINATIONS,
        content: @Composable () -> Unit = { Text("Destination Content") },
    ) {
        composeRule.setContent {
            TrindadeTheme {
                MainAppShell(
                    role = role,
                    currentTab = currentTab,
                    onSelectTab = onSelectTab,
                    onOpenProfile = onOpenProfile,
                    destinations = destinations,
                    content = content,
                )
            }
        }
    }

    @Test
    fun `branded header renders brand title and profile action`() {
        var profileOpened = false
        render(onOpenProfile = { profileOpened = true })

        composeRule.onNodeWithText(copy(R.string.nav_brand_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.profile_title)).assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.profile_title)).performClick()
        assertTrue(profileOpened)
    }

    @Test
    fun `all five default tabs are displayed and only dashboard is selected by default`() {
        render(currentTab = MainActivity.Tab.DASHBOARD)

        // All 5 tabs are displayed; the scrollable row brings each one into view on narrow viewports
        composeRule.onNodeWithText(copy(R.string.nav_dashboard)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.nav_reports)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.nav_loading)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_title)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.drivers_title)).performScrollTo().assertIsDisplayed()

        // Dashboard is selected; exactly one tab is selected
        composeRule.onNodeWithText(copy(R.string.nav_dashboard)).assertIsSelected()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)
    }

    @Test
    fun `reports tab is marked selected when active`() {
        render(currentTab = MainActivity.Tab.REPORTS)

        composeRule.onNodeWithText(copy(R.string.nav_reports)).assertIsSelected()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)
    }

    @Test
    fun `loading tab is marked selected when active`() {
        render(currentTab = MainActivity.Tab.LOADING)

        composeRule.onNodeWithText(copy(R.string.nav_loading)).assertIsSelected()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)
    }

    @Test
    fun `tasks tab is marked selected when active`() {
        render(currentTab = MainActivity.Tab.TASKS)

        composeRule.onNodeWithText(copy(R.string.tasks_title)).assertIsSelected()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)
    }

    @Test
    fun `drivers tab is marked selected when active`() {
        render(currentTab = MainActivity.Tab.DRIVERS)

        composeRule.onNodeWithText(copy(R.string.drivers_title)).assertIsSelected()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)
    }

    @Test
    fun `clicking each tab invokes onSelectTab with corresponding destination`() {
        var selectedDestination: MainActivity.Tab? = null
        render(
            currentTab = MainActivity.Tab.DASHBOARD,
            onSelectTab = { selectedDestination = it },
        )

        composeRule.onNodeWithText(copy(R.string.nav_reports)).performScrollTo().performClick()
        assertEquals(MainActivity.Tab.REPORTS, selectedDestination)

        composeRule.onNodeWithText(copy(R.string.nav_loading)).performScrollTo().performClick()
        assertEquals(MainActivity.Tab.LOADING, selectedDestination)

        composeRule.onNodeWithText(copy(R.string.tasks_title)).performScrollTo().performClick()
        assertEquals(MainActivity.Tab.TASKS, selectedDestination)

        composeRule.onNodeWithText(copy(R.string.drivers_title)).performScrollTo().performClick()
        assertEquals(MainActivity.Tab.DRIVERS, selectedDestination)

        composeRule.onNodeWithText(copy(R.string.nav_dashboard)).performScrollTo().performClick()
        assertEquals(MainActivity.Tab.DASHBOARD, selectedDestination)
    }

    @Test
    fun `role policy filtering hides unauthorized destinations for worker`() {
        val testDestinations = listOf(
            RolePolicy.EntryPoint.DASHBOARD to MainActivity.Tab.DASHBOARD,
            RolePolicy.EntryPoint.AUDIT to MainActivity.Tab.TASKS, // Audit is admin-only in RolePolicy
        )

        render(
            role = RolePolicy.WORKER,
            destinations = testDestinations,
        )

        composeRule.onNodeWithText(copy(R.string.nav_dashboard)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_title)).assertDoesNotExist()
    }

    @Test
    fun `role policy filtering displays authorized destinations for administrator`() {
        val testDestinations = listOf(
            RolePolicy.EntryPoint.DASHBOARD to MainActivity.Tab.DASHBOARD,
            RolePolicy.EntryPoint.AUDIT to MainActivity.Tab.TASKS, // Audit is admin-only in RolePolicy
        )

        render(
            role = RolePolicy.ADMIN,
            destinations = testDestinations,
        )

        composeRule.onNodeWithText(copy(R.string.nav_dashboard)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_title)).assertIsDisplayed()
    }

    @Test
    fun `drivers tab is visible for worker role in default destinations`() {
        render(role = RolePolicy.WORKER)
        composeRule.onNodeWithText(copy(R.string.drivers_title)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `drivers tab is visible for administrator role in default destinations`() {
        render(role = RolePolicy.ADMIN)
        composeRule.onNodeWithText(copy(R.string.drivers_title)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `admin tab is visible for administrator role in default destinations`() {
        render(role = RolePolicy.ADMIN)
        composeRule.onNodeWithText(copy(R.string.admin_panel_title)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `admin tab is hidden for worker role in default destinations`() {
        render(role = RolePolicy.WORKER)
        composeRule.onNodeWithText(copy(R.string.admin_panel_title)).assertDoesNotExist()
    }

    @Test
    fun `clicking admin tab invokes onSelectTab with admin destination`() {
        var selectedDestination: MainActivity.Tab? = null
        render(
            role = RolePolicy.ADMIN,
            currentTab = MainActivity.Tab.DASHBOARD,
            onSelectTab = { selectedDestination = it },
        )

        val adminTab = composeRule.onNodeWithText(copy(R.string.admin_panel_title))
        adminTab.performScrollTo()
        adminTab.assertIsDisplayed()
        adminTab.performClick()
        assertEquals(MainActivity.Tab.ADMIN, selectedDestination)
    }

    @Test
    fun `worker tabs remain readable and admin is absent at Pixel 8 width`() {
        renderAtPhoneWidth(RolePolicy.WORKER)
        composeRule.onNodeWithText(copy(R.string.admin_panel_title)).assertDoesNotExist()
        assertReadableTabs(MainActivity.Tab.entries.filter { it != MainActivity.Tab.ADMIN })
    }

    @Test
    fun `administrator can reach the last tab at Pixel 8 width`() {
        var selected by mutableStateOf(MainActivity.Tab.DASHBOARD)
        renderAtPhoneWidth(RolePolicy.ADMIN, { selected }) { selected = it }
        assertReadableTabs(MainActivity.Tab.entries)
        val admin = composeRule.onNodeWithText(copy(R.string.admin_panel_title))
        admin.performScrollTo()
        admin.assertIsDisplayed()
        admin.performClick()
        assertEquals(MainActivity.Tab.ADMIN, selected)
        admin.assertIsSelected()
        admin.assertIsDisplayed()
    }

    private fun renderAtPhoneWidth(
        role: String,
        currentTab: () -> MainActivity.Tab = { MainActivity.Tab.DASHBOARD },
        onSelectTab: (MainActivity.Tab) -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                Box(Modifier.width(411.dp)) {
                    MainAppShell(role, currentTab(), onSelectTab, {}) { Text("Body") }
                }
            }
        }
    }

    private fun assertReadableTabs(tabs: List<MainActivity.Tab>) {
        tabs.forEach { tab ->
            val label = composeRule.onNodeWithText(copy(tab.label))
            label.performScrollTo()
            label.assertIsDisplayed()
            val bounds = label.getUnclippedBoundsInRoot()
            assertTrue("${copy(tab.label)} must have room for its text", bounds.right - bounds.left >= 80.dp)
            assertTrue(label.fetchSemanticsNode().config.contains(SemanticsProperties.Text))
        }
    }

    @Test
    fun `shell renders provided body content`() {
        render {
            Text("Inner Screen Content")
        }

        composeRule.onNodeWithText("Inner Screen Content").assertIsDisplayed()
    }

    @Test
    fun `app root renders child content`() {
        composeRule.setContent {
            TrindadeTheme {
                AppRoot {
                    Text("App Root Content")
                }
            }
        }

        composeRule.onNodeWithText("App Root Content").assertIsDisplayed()
    }

    @Test
    fun `app root hosts main app shell with branded header and destination content`() {
        composeRule.setContent {
            TrindadeTheme {
                AppRoot {
                    MainAppShell(
                        role = RolePolicy.WORKER,
                        currentTab = MainActivity.Tab.DASHBOARD,
                        onSelectTab = {},
                        onOpenProfile = {},
                    ) {
                        Text("Dashboard Body Content")
                    }
                }
            }
        }

        composeRule.onNodeWithText(copy(R.string.nav_brand_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.profile_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Dashboard Body Content").assertIsDisplayed()
    }

    @Test
    fun `navigating from login to register and back preserves unauthenticated state without session`() {
        var registerOpen by mutableStateOf(false)
        var signedIn by mutableStateOf(false)

        composeRule.setContent {
            TrindadeTheme {
                AppRoot {
                    if (registerOpen) {
                        RegisterScreen(
                            state = RegisterViewModel.UiState(),
                            onUsernameChange = {},
                            onDisplayNameChange = {},
                            onPasswordChange = {},
                            onConfirmPasswordChange = {},
                            onSubmit = {},
                            onBackToLogin = { registerOpen = false },
                        )
                    } else {
                        LoginScreen(
                            state = LoginViewModel.UiState(),
                            onUsernameChange = {},
                            onPasswordChange = {},
                            onSubmit = { signedIn = true },
                            onRegisterClick = { registerOpen = true },
                        )
                    }
                }
            }
        }

        // On login screen initially: submit button and register link are visible
        composeRule.onNodeWithText(copy(R.string.login_submit)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.login_register_link)).performScrollTo().assertIsDisplayed()

        // Tap "Solicitar cadastro"
        composeRule.onNodeWithText(copy(R.string.login_register_link)).performClick()

        // Now on register screen: role notice and form are displayed
        composeRule.onNodeWithText(copy(R.string.register_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_worker_role_notice)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).performScrollTo().assertIsDisplayed()

        // Tap "Voltar para o login"
        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).performClick()

        // Back to login screen: still signed out, preserving no session
        composeRule.onNodeWithText(copy(R.string.login_submit)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.login_register_link)).performScrollTo().assertIsDisplayed()
        assertFalse(signedIn)
    }

    @Test
    fun `successful registration on register screen allows navigating back to login preserving unauthenticated state`() {
        var registerOpen by mutableStateOf(true)
        var signedIn by mutableStateOf(false)
        val neutralMessage =
            "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."

        composeRule.setContent {
            TrindadeTheme {
                AppRoot {
                    if (registerOpen) {
                        RegisterScreen(
                            state = RegisterViewModel.UiState(successMessage = neutralMessage),
                            onUsernameChange = {},
                            onDisplayNameChange = {},
                            onPasswordChange = {},
                            onConfirmPasswordChange = {},
                            onSubmit = {},
                            onBackToLogin = { registerOpen = false },
                        )
                    } else {
                        LoginScreen(
                            state = LoginViewModel.UiState(),
                            onUsernameChange = {},
                            onPasswordChange = {},
                            onSubmit = { signedIn = true },
                            onRegisterClick = { registerOpen = true },
                        )
                    }
                }
            }
        }

        // Confirmation shown verbatim
        composeRule.onNodeWithText(copy(R.string.register_success_title)).assertIsDisplayed()
        composeRule.onNodeWithText(neutralMessage).assertIsDisplayed()

        // Tap back to login
        composeRule.onNodeWithText(copy(R.string.register_back_to_login)).performScrollTo().performClick()

        // Returns to login screen without session
        composeRule.onNodeWithText(copy(R.string.login_submit)).assertIsDisplayed()
        assertFalse(signedIn)
    }
}
