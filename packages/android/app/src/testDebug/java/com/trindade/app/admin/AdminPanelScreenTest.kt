package com.trindade.app.admin

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponse
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.RefreshResponse
import com.trindade.app.contract.models.RegisterRequest
import com.trindade.app.contract.models.RegisterResponse
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AuthApi
import com.trindade.app.ui.theme.TrindadeTheme
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminPanelScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private class SimpleFakeTokenStore(private var role: String?) : TokenStore {
        override fun accessToken(): String? = "token"
        override fun refreshToken(): String? = "refresh"
        override fun role(): String? = role
        override fun save(accessToken: String, refreshToken: String) {}
        override fun saveRole(role: String) { this.role = role }
        override fun clear() { role = null }
    }

    private class SimpleFakeAuthApi : AuthApi {
        override suspend fun login(body: LoginRequest) = Response.error<LoginResponse>(500, "".toResponseBody())
        override suspend fun refresh(body: RefreshRequest) = Response.error<RefreshResponse>(500, "".toResponseBody())
        override suspend fun logout(body: LogoutRequest) = Response.success(SuccessResponse(SuccessResponse.Success.`true`))
        override suspend fun me() = Response.error<ProfileResponse>(500, "".toResponseBody())
        override suspend fun profile() = Response.error<ProfileResponse>(500, "".toResponseBody())
        override suspend fun updateProfile(body: UpdateProfileRequest) = Response.error<ProfileResponse>(500, "".toResponseBody())
        override suspend fun changePassword(body: ChangePasswordRequest) = Response.success(SuccessResponse(SuccessResponse.Success.`true`))
        override suspend fun setupStatus() = Response.error<SetupStatusResponse>(500, "".toResponseBody())
        override suspend fun register(body: RegisterRequest) = Response.error<RegisterResponse>(500, "".toResponseBody())
    }

    private fun createAuthRepository(role: String): AuthRepository {
        return AuthRepository(SimpleFakeAuthApi(), SimpleFakeTokenStore(role), Json { ignoreUnknownKeys = true })
    }

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun render(
        onBack: () -> Unit = {},
        onOpenSection: (AdminSection) -> Unit = {},
        isAdmin: Boolean = true,
    ) {
        composeRule.setContent {
            TrindadeTheme {
                AdminPanelScreen(
                    onBack = onBack,
                    onOpenSection = onOpenSection,
                    isAdmin = isAdmin,
                )
            }
        }
    }

    @Test
    fun `admin panel renders header title and description`() {
        render()

        composeRule.onNodeWithText(copy(R.string.admin_panel_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_panel_description)).assertIsDisplayed()
    }

    @Test
    fun `admin panel renders categories section and opens on click`() {
        var openedSection: AdminSection? = null
        render(onOpenSection = { openedSection = it })

        composeRule.onNodeWithText(copy(R.string.admin_section_categories)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_categories_desc)).assertIsDisplayed()

        composeRule.onAllNodesWithText(copy(R.string.admin_access_section))[0].performClick()
        assertEquals(AdminSection.CATEGORIES, openedSection)
    }

    @Test
    fun `admin panel renders vehicles section and opens on click`() {
        var openedSection: AdminSection? = null
        render(onOpenSection = { openedSection = it })

        composeRule.onNodeWithText(copy(R.string.admin_section_vehicles)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_vehicles_desc)).performScrollTo().assertIsDisplayed()

        composeRule.onAllNodesWithText(copy(R.string.admin_access_section))[1].performScrollTo().performClick()
        assertEquals(AdminSection.VEHICLES, openedSection)
    }

    @Test
    fun `admin panel renders time slots section and opens on click`() {
        var openedSection: AdminSection? = null
        render(onOpenSection = { openedSection = it })

        composeRule.onNodeWithText(copy(R.string.admin_section_time_slots)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_time_slots_desc)).performScrollTo().assertIsDisplayed()

        composeRule.onAllNodesWithText(copy(R.string.admin_access_section))[2].performScrollTo().performClick()
        assertEquals(AdminSection.TIME_SLOTS, openedSection)
    }

    @Test
    fun `admin panel renders users section and opens on click`() {
        var openedSection: AdminSection? = null
        render(onOpenSection = { openedSection = it })

        composeRule.onNodeWithText(copy(R.string.admin_section_users)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_users_desc)).performScrollTo().assertIsDisplayed()

        composeRule.onAllNodesWithText(copy(R.string.admin_access_section))[3].performScrollTo().performClick()
        assertEquals(AdminSection.USERS, openedSection)
    }

    @Test
    fun `admin panel renders audit section and opens on click for administrator`() {
        var openedSection: AdminSection? = null
        render(isAdmin = true, onOpenSection = { openedSection = it })

        composeRule.onNodeWithText(copy(R.string.admin_section_audit)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_audit_desc)).performScrollTo().assertIsDisplayed()

        composeRule.onAllNodesWithText(copy(R.string.admin_access_section))[4].performScrollTo().performClick()
        assertEquals(AdminSection.AUDIT, openedSection)
    }

    @Test
    fun `admin panel does not expose any admin cards to worker`() {
        render(isAdmin = false)

        composeRule.onAllNodesWithText(copy(R.string.admin_section_categories)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_section_vehicles)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_section_time_slots)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_section_users)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_section_audit)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_access_section)).assertCountEquals(0)
    }

    @Test
    fun `admin panel route with worker session role from authRepository hides all admin cards`() {
        val authRepository = createAuthRepository(role = RolePolicy.WORKER)
        composeRule.setContent {
            TrindadeTheme {
                AdminPanelRoute(
                    sessionKey = "test-session",
                    onBack = {},
                    authRepository = authRepository,
                )
            }
        }

        composeRule.onAllNodesWithText(copy(R.string.admin_section_categories)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_section_vehicles)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_section_time_slots)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_section_users)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_section_audit)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.admin_access_section)).assertCountEquals(0)
    }

    @Test
    fun `admin panel route with admin session role from authRepository shows admin cards`() {
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN)
        composeRule.setContent {
            TrindadeTheme {
                AdminPanelRoute(
                    sessionKey = "test-session",
                    onBack = {},
                    authRepository = authRepository,
                )
            }
        }

        composeRule.onNodeWithText(copy(R.string.admin_section_categories)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_vehicles)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_time_slots)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_users)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.admin_section_audit)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `admin panel has no remaining planned sections with coming soon badges`() {
        render()

        composeRule.onAllNodesWithText(copy(R.string.admin_badge_coming_soon)).assertCountEquals(0)
    }

    @Test(expected = IllegalStateException::class)
    fun `admin panel route propagates entrypoint exception when hilt entrypoint is missing`() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context {
                throw IllegalStateException("Hilt entrypoint lookup failed")
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides failingContext) {
                TrindadeTheme {
                    AdminPanelRoute(
                        sessionKey = "test-session",
                        onBack = {},
                    )
                }
            }
        }
    }

    @Test
    fun `back button invokes onBack callback`() {
        var backInvoked = false
        render(onBack = { backInvoked = true })

        composeRule.onNodeWithText(copy(R.string.report_back)).performClick()
        assertEquals(true, backInvoked)
    }

    @Test
    fun `admin panel route preserves selected section across state restoration`() {
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN)
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            TrindadeTheme {
                AdminPanelRoute(
                    sessionKey = "test-session",
                    onBack = {},
                    authRepository = authRepository,
                    sectionContent = { section, onBack ->
                        Text("Active section: ${section.name}")
                    },
                )
            }
        }

        // Open Time Slots section
        composeRule.onAllNodesWithText(copy(R.string.admin_access_section))[2].performScrollTo().performClick()
        composeRule.onNodeWithText("Active section: TIME_SLOTS").assertIsDisplayed()

        // Emulate state restoration (e.g. rotation / process recreation)
        restorationTester.emulateSavedInstanceStateRestore()

        // Selected section should still be displayed
        composeRule.onNodeWithText("Active section: TIME_SLOTS").assertIsDisplayed()
    }

    @Test
    fun `admin panel section action buttons meet 48dp minimum touch target bounds`() {
        render()

        val accessNodes = composeRule.onAllNodesWithText(copy(R.string.admin_access_section))
        accessNodes.assertCountEquals(5)

        for (i in 0 until 5) {
            val node = accessNodes[i]
            node.performScrollTo().assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("Access section button $i height >= 48dp", bounds.bottom - bounds.top >= 48.dp)
            assertTrue("Access section button $i width >= 48dp", bounds.right - bounds.left >= 48.dp)
        }
    }
}
