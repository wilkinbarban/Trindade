package com.trindade.app

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.auth.LoginScreen
import com.trindade.app.auth.LoginViewModel
import com.trindade.app.auth.RegisterScreen
import com.trindade.app.auth.RegisterViewModel
import com.trindade.app.auth.RolePolicy
import com.trindade.app.ui.components.NavigationActionButton
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
 * properly dispatches tab selection and profile open events, confirms [AppRoot] hosts
 * application destinations with safe drawing insets, and verifies [NavigationActionButton]
 * provides minimum touch target size >= 48dp.
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

        // All 5 worker tabs are displayed; the scrollable row brings each one into view on narrow viewports
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
            RolePolicy.EntryPoint.AUDIT to MainActivity.Tab.TASKS,
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
            RolePolicy.EntryPoint.AUDIT to MainActivity.Tab.TASKS,
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
    fun `navigation action button has minimum touch target of at least 48dp`() {
        composeRule.setContent {
            TrindadeTheme {
                NavigationActionButton(onClick = {}) {
                    Text(copy(R.string.report_back))
                }
            }
        }
        val node = composeRule.onNodeWithText(copy(R.string.report_back))
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Width must be >= 48dp", bounds.right - bounds.left >= 48.dp)
        assertTrue("Height must be >= 48dp", bounds.bottom - bounds.top >= 48.dp)
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

    @Test
    fun `theme provides brand red primary and brand gold secondary with high contrast`() {
        var primary: Color? = null
        var onPrimary: Color? = null
        var secondary: Color? = null
        var onSecondary: Color? = null
        var surface: Color? = null
        var onSurface: Color? = null

        composeRule.setContent {
            TrindadeTheme {
                primary = MaterialTheme.colorScheme.primary
                onPrimary = MaterialTheme.colorScheme.onPrimary
                secondary = MaterialTheme.colorScheme.secondary
                onSecondary = MaterialTheme.colorScheme.onSecondary
                surface = MaterialTheme.colorScheme.surface
                onSurface = MaterialTheme.colorScheme.onSurface
            }
        }

        val expectedPrimary = Color(0xFFB42025)
        val expectedSecondary = Color(0xFF855400)
        assertEquals("Theme primary must match brand red #B42025", expectedPrimary, primary)
        assertEquals("Theme onPrimary must be white", Color(0xFFFFFFFF), onPrimary)
        assertEquals("Theme secondary must match brand gold #855400", expectedSecondary, secondary)

        val contrastOnPrimary = contrastRatio(onPrimary!!, primary!!)
        assertTrue(
            "Contrast between onPrimary and primary ($contrastOnPrimary:1) must meet WCAG AA >= 4.5:1",
            contrastOnPrimary >= 4.5,
        )

        val contrastOnSecondary = contrastRatio(onSecondary!!, secondary!!)
        assertTrue(
            "Contrast between onSecondary and secondary ($contrastOnSecondary:1) must meet WCAG AA >= 4.5:1",
            contrastOnSecondary >= 4.5,
        )

        val contrastOnSurface = contrastRatio(onSurface!!, surface!!)
        assertTrue(
            "Contrast between onSurface and surface ($contrastOnSurface:1) must meet WCAG AA >= 4.5:1",
            contrastOnSurface >= 4.5,
        )
    }

    @Test
    fun `app shell header profile button has minimum touch target of at least 48dp`() {
        render(role = RolePolicy.WORKER)

        val profileNode = composeRule.onNodeWithText(copy(R.string.profile_title))
        profileNode.assertIsDisplayed()
        val bounds = profileNode.getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        assertTrue("Profile button width ($width) must be >= 48dp", width >= 48.dp)
        assertTrue("Profile button height ($height) must be >= 48dp", height >= 48.dp)
    }

    @Test
    fun `app shell tab items meet 48dp minimum touch target height`() {
        render(role = RolePolicy.WORKER)

        for (tab in listOf(MainActivity.Tab.DASHBOARD, MainActivity.Tab.REPORTS, MainActivity.Tab.LOADING)) {
            val tabNode = composeRule.onNodeWithText(copy(tab.label))
            tabNode.assertIsDisplayed()
            val bounds = tabNode.getUnclippedBoundsInRoot()
            val height = bounds.bottom - bounds.top
            assertTrue("Tab ${copy(tab.label)} height ($height) must be >= 48dp", height >= 48.dp)
        }
    }

    private fun relativeLuminance(color: Color): Double {
        fun channelLuminance(channel: Float): Double {
            return if (channel <= 0.04045f) {
                channel / 12.92
            } else {
                Math.pow((channel + 0.055) / 1.055, 2.4)
            }
        }
        return 0.2126 * channelLuminance(color.red) +
            0.7152 * channelLuminance(color.green) +
            0.0722 * channelLuminance(color.blue)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `branded header renders menu button alongside brand title and profile action with touch target of at least 48dp`() {
        render(role = RolePolicy.WORKER)

        val menuNode = composeRule.onNodeWithText(copy(R.string.nav_menu))
        menuNode.assertIsDisplayed()
        val menuBounds = menuNode.getUnclippedBoundsInRoot()
        assertTrue("Menu button width (${menuBounds.right - menuBounds.left}) must be >= 48dp", menuBounds.right - menuBounds.left >= 48.dp)
        assertTrue("Menu button height (${menuBounds.bottom - menuBounds.top}) must be >= 48dp", menuBounds.bottom - menuBounds.top >= 48.dp)

        val profileNode = composeRule.onNodeWithText(copy(R.string.profile_title))
        profileNode.assertIsDisplayed()
        val profileBounds = profileNode.getUnclippedBoundsInRoot()
        assertTrue("Profile button width (${profileBounds.right - profileBounds.left}) must be >= 48dp", profileBounds.right - profileBounds.left >= 48.dp)
        assertTrue("Profile button height (${profileBounds.bottom - profileBounds.top}) must be >= 48dp", profileBounds.bottom - profileBounds.top >= 48.dp)

        composeRule.onNodeWithText(copy(R.string.nav_brand_title)).assertIsDisplayed()
    }

    @Test
    fun `header at 320dp width with 2x font scale keeps menu brand title and profile bounded within screen`() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                TrindadeTheme {
                    Box(Modifier.width(320.dp)) {
                        MainAppShell(
                            role = RolePolicy.ADMIN,
                            currentTab = MainActivity.Tab.DASHBOARD,
                            onSelectTab = {},
                            onOpenProfile = {},
                        ) {
                            Text("Body")
                        }
                    }
                }
            }
        }

        val menuNode = composeRule.onNodeWithText(copy(R.string.nav_menu))
        menuNode.assertIsDisplayed()
        val menuBounds = menuNode.getUnclippedBoundsInRoot()
        assertTrue("Menu left (${menuBounds.left}) >= 0dp", menuBounds.left >= 0.dp)
        assertTrue("Menu right (${menuBounds.right}) <= 320dp", menuBounds.right <= 320.dp)
        assertTrue("Menu width >= 48dp at 2x font", menuBounds.right - menuBounds.left >= 48.dp)
        assertTrue("Menu height >= 48dp at 2x font", menuBounds.bottom - menuBounds.top >= 48.dp)

        val profileNode = composeRule.onNodeWithText(copy(R.string.profile_title))
        profileNode.assertIsDisplayed()
        val profileBounds = profileNode.getUnclippedBoundsInRoot()
        assertTrue("Profile left (${profileBounds.left}) >= 0dp", profileBounds.left >= 0.dp)
        assertTrue("Profile right (${profileBounds.right}) <= 320dp", profileBounds.right <= 320.dp)
        assertTrue("Profile width >= 48dp at 2x font", profileBounds.right - profileBounds.left >= 48.dp)
        assertTrue("Profile height >= 48dp at 2x font", profileBounds.bottom - profileBounds.top >= 48.dp)

        val brandNode = composeRule.onNodeWithText(copy(R.string.nav_brand_title))
        brandNode.assertIsDisplayed()
        val brandBounds = brandNode.getUnclippedBoundsInRoot()
        assertTrue("Brand title left (${brandBounds.left}) >= 0dp", brandBounds.left >= 0.dp)
        assertTrue("Brand title right (${brandBounds.right}) <= 320dp", brandBounds.right <= 320.dp)

        val overlaps = menuBounds.left < profileBounds.right &&
            menuBounds.right > profileBounds.left &&
            menuBounds.top < profileBounds.bottom &&
            menuBounds.bottom > profileBounds.top
        assertFalse("Menu and Profile buttons must not overlap in header", overlaps)
    }

    @Test
    fun `tapping menu button opens modal navigation drawer showing role filtered destinations`() {
        render(role = RolePolicy.WORKER)

        composeRule.onNodeWithTag("navigation_drawer_sheet").assertDoesNotExist()

        composeRule.onNodeWithText(copy(R.string.nav_menu)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("navigation_drawer_sheet").assertIsDisplayed()

        composeRule.onNode(hasText(copy(R.string.nav_dashboard)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))).assertIsDisplayed()
        composeRule.onNode(hasText(copy(R.string.nav_reports)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))).assertIsDisplayed()
        composeRule.onNode(hasText(copy(R.string.nav_loading)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))).assertIsDisplayed()
        composeRule.onNode(hasText(copy(R.string.tasks_title)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))).assertIsDisplayed()
        composeRule.onNode(hasText(copy(R.string.drivers_title)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))).assertIsDisplayed()

        composeRule.onNode(hasText(copy(R.string.admin_panel_title)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))).assertDoesNotExist()
    }

    @Test
    fun `administrator sees admin destination in drawer without horizontal hunting`() {
        render(role = RolePolicy.ADMIN)

        composeRule.onNodeWithText(copy(R.string.nav_menu)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("navigation_drawer_sheet").assertIsDisplayed()

        val adminDrawerItem = composeRule.onNode(
            hasText(copy(R.string.admin_panel_title)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))
        )
        adminDrawerItem.assertIsDisplayed()
        val bounds = adminDrawerItem.getUnclippedBoundsInRoot()
        assertTrue("Admin item left (${bounds.left}) >= 0dp", bounds.left >= 0.dp)
        assertTrue("Admin item right (${bounds.right}) <= 320dp", bounds.right <= 320.dp)
    }

    @Test
    fun `selecting destination in drawer invokes onSelectTab exactly once and closes drawer`() {
        var selectedDestination: MainActivity.Tab? = null
        var callCount = 0
        render(
            role = RolePolicy.ADMIN,
            currentTab = MainActivity.Tab.DASHBOARD,
            onSelectTab = {
                selectedDestination = it
                callCount++
            },
        )

        composeRule.onNodeWithText(copy(R.string.nav_menu)).performClick()
        composeRule.waitForIdle()

        val adminItem = composeRule.onNode(
            hasText(copy(R.string.admin_panel_title)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))
        )
        adminItem.performClick()
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()

        assertEquals("onSelectTab must be invoked exactly once", 1, callCount)
        assertEquals(MainActivity.Tab.ADMIN, selectedDestination)
        composeRule.onNodeWithTag("navigation_drawer_sheet").assertDoesNotExist()
    }

    @Test
    fun `system back closes drawer when open before navigating out of current body`() {
        var backDispatcher: OnBackPressedDispatcher? = null
        var bodyNavigatedBack = false

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            TrindadeTheme {
                MainAppShell(
                    role = RolePolicy.ADMIN,
                    currentTab = MainActivity.Tab.LOADING,
                    onSelectTab = {},
                    onOpenProfile = {},
                ) {
                    BackHandler {
                        bodyNavigatedBack = true
                    }
                    Text("Loading Body")
                }
            }
        }

        composeRule.onNodeWithText(copy(R.string.nav_menu)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("navigation_drawer_sheet").assertIsDisplayed()

        composeRule.runOnIdle {
            backDispatcher?.onBackPressed()
        }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("navigation_drawer_sheet").assertDoesNotExist()
        assertFalse("Body must not navigate back while drawer was open", bodyNavigatedBack)

        composeRule.runOnIdle {
            backDispatcher?.onBackPressed()
        }
        composeRule.waitForIdle()

        assertTrue("Body back handler must be invoked after drawer is already closed", bodyNavigatedBack)
    }

    @Test
    fun `drawer is vertically scrollable on short viewport with 2x font scale to reach administrator item`() {
        var selectedDestination: MainActivity.Tab? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                TrindadeTheme {
                    Box(Modifier.width(320.dp).height(320.dp)) {
                        MainAppShell(
                            role = RolePolicy.ADMIN,
                            currentTab = MainActivity.Tab.DASHBOARD,
                            onSelectTab = { selectedDestination = it },
                            onOpenProfile = {},
                        ) {
                            Text("Body")
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText(copy(R.string.nav_menu)).performClick()
        composeRule.waitForIdle()

        val adminItem = composeRule.onNode(
            hasText(copy(R.string.admin_panel_title)).and(hasAnyAncestor(hasTestTag("navigation_drawer_sheet")))
        )
        adminItem.performScrollTo()
        adminItem.assertIsDisplayed()
        adminItem.performClick()
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()

        assertEquals(MainActivity.Tab.ADMIN, selectedDestination)
        composeRule.onNodeWithTag("navigation_drawer_sheet").assertDoesNotExist()
    }
}
