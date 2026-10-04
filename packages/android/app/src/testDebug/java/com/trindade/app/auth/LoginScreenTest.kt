package com.trindade.app.auth

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LoginResponseUser
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
import retrofit2.Response
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The login screen, rendered rather than read.
 *
 * This is the first test in the project that draws a screen and measures the result, and it exists
 * because of a real regression: the branding block gave the form a fixed height, a short viewport --
 * landscape above all -- measured the submit button against the space that was left, and the button
 * could be squeezed to nothing with no way for the operator to reach it. Every test in this project
 * passed while that was true, because nothing could render the screen.
 *
 * Four things about the lane are deliberate:
 *
 *  * `GraphicsMode.NATIVE` is what makes the rendering real -- text is measured by the framework's own
 *    layout instead of by a shadow that returns a constant -- so a screen measured here is measured
 *    the way a device measures it. This project has already been bitten by a test that could not fail;
 *    a lane that renders without measuring text would be a second one.
 *  * The screen is rendered inside a `Box` of a fixed size instead of filling the test window, so a
 *    viewport size is a number this test states rather than a property of whichever device Robolectric
 *    happens to emulate. `LoginScreen` fills whatever it is given, so the box is the viewport.
 *  * The submit button is found by the label the app ships (`R.string.login_submit`) rather than by a
 *    string typed here, so the selector cannot drift from the copy, and it resolves to the button's own
 *    node -- the button merges its descendants, which is what gives that node the button's bounds
 *    rather than the text's.
 *  * The window's own size is not decoration. A fixed-size `Box` is measured *under* the window's
 *    constraints, so `@Config(qualifiers = "w400dp-h1000dp")` is what makes the boxes below mean what they
 *    say: the centring test declares a 700dp box and grows it to 900dp, and both numbers only measure the
 *    viewport while the window stays taller than the larger one. Trimming the qualifier because the KDoc
 *    says the box replaces the test window measures a 0dp delta, which this test would then report as
 *    content pinned to the top.
 *
 * The class lives in `src/testDebug` rather than `src/test`, because the host activity comes from
 * `ui-test-manifest`, which is a debug-only dependency by design -- it must never reach a release APK. The
 * module builds exactly one unit-test variant today (`testDebugUnitTest`, and the aggregate `test` covers
 * only it), so the shared set would not have broken anything yet: the move is what makes the source set
 * state the scope its own dependency has, instead of inheriting a test that could not launch the day a
 * second unit-test variant exists.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginScreenTest {

    // The `v2` factory, because the unqualified one is deprecated in this Compose version and this file is
    // the one the lane's next tests get copied from. The difference between the two is the dispatcher
    // composition is queued on: the old one runs it immediately, the new one uses a `StandardTestDispatcher`
    // and queues it, so a read that follows a state change waits for idle first. Nothing below had to be
    // rewritten for that -- the test API synchronizes before every assertion and action -- and the one place
    // this file changes the viewport itself says `waitForIdle` out loud, which is exactly where a queued
    // recomposition would otherwise be measured as the previous layout.
    @get:Rule
    val composeRule = createComposeRule()

    private val submitLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.login_submit)

    private val titleLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.login_title)

    private val registerLinkLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.login_register_link)

    private val showPasswordLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.login_password_show)

    private val hidePasswordLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.login_password_hide)

    @Test
    fun `password visibility toggle is disabled while submission is in flight and suppresses toggle clicks`() {
        val state = mutableStateOf(LoginViewModel.UiState(password = "segredo123", submitting = true))
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = state.value,
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                )
            }
        }

        val toggle = composeRule.onNodeWithText(showPasswordLabel)
        toggle.assertIsDisplayed()
        toggle.assertIsNotEnabled()

        toggle.performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(showPasswordLabel).assertIsDisplayed()
        composeRule.onNodeWithText(hidePasswordLabel).assertDoesNotExist()

        state.value = state.value.copy(submitting = false)
        composeRule.waitForIdle()
        toggle.assertIsEnabled()
    }
    @Test
    fun `password field has accessible visibility toggle that toggles transformation and button label`() {
        val secret = "segredo123"
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = LoginViewModel.UiState(password = secret),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                )
            }
        }

        // Semantic assertion: the password field node has SemanticsProperties.Password set
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)).assertCountEquals(1)

        // 1. Initial hidden state: mask bullets are displayed in EditableText, unmasked secret is not exposed outside password node, toggle label is "Mostrar"
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("\u2022".repeat(secret.length)))).assertCountEquals(1)
        composeRule.onAllNodesWithText("\u2022".repeat(secret.length)).assertCountEquals(1)
        composeRule.onNode(hasText(secret) and !SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)).assertDoesNotExist()

        val toggle = composeRule.onNodeWithText(showPasswordLabel)
        toggle.assertIsDisplayed()
        toggle.assertHeightIsAtLeast(48.dp)

        // 2. Click to toggle to visible state: unmasked secret is in EditableText, mask bullets do not exist, toggle label is "Ocultar"
        toggle.performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(hidePasswordLabel).assertIsDisplayed()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(secret))).assertCountEquals(1)
        composeRule.onAllNodesWithText("\u2022".repeat(secret.length)).assertCountEquals(0)

        // 3. Click to toggle back to hidden state: mask bullets are in EditableText again, unmasked secret is no longer in EditableText, toggle label is "Mostrar"
        composeRule.onNodeWithText(hidePasswordLabel).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(showPasswordLabel).assertIsDisplayed()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("\u2022".repeat(secret.length)))).assertCountEquals(1)
        composeRule.onAllNodesWithText("\u2022".repeat(secret.length)).assertCountEquals(1)
        composeRule.onNode(hasText(secret) and !SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)).assertDoesNotExist()
    }

    @Test
    fun `submit button dispatches onSubmit callback once per click when form is valid`() {
        var submitClicks = 0
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = LoginViewModel.UiState(username = "operador", password = "segredo"),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = { submitClicks++ },
                )
            }
        }

        val submit = composeRule.onNodeWithText(submitLabel)
        submit.assertIsEnabled()

        submit.performClick()
        composeRule.waitForIdle()
        assertEquals(1, submitClicks)

        submit.performClick()
        composeRule.waitForIdle()
        assertEquals(2, submitClicks)
    }

    @Test
    fun `disabled submit button suppresses clicks repeatedly when form cannot submit`() {
        var submitClicks = 0
        val state = mutableStateOf(LoginViewModel.UiState(username = "", password = ""))
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = state.value,
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = { submitClicks++ },
                )
            }
        }

        val submit = composeRule.onNodeWithText(submitLabel)
        submit.assertIsNotEnabled()

        submit.performClick()
        submit.performClick()
        composeRule.waitForIdle()
        assertEquals("clicks on empty form submit button must be suppressed", 0, submitClicks)

        // Also test while submitting is in flight
        state.value = state.value.copy(username = "operador", password = "segredo", submitting = true)
        composeRule.waitForIdle()
        submit.assertIsNotEnabled()

        submit.performClick()
        submit.performClick()
        composeRule.waitForIdle()
        assertEquals("clicks during in-flight submission must be suppressed", 0, submitClicks)
    }

    @Test
    fun `login route handles sign-in success event by invoking callback exactly once and consuming event`() {
        var signedInCalled = 0
        val authRepo = AuthRepository(RouteFakeAuthApi(), RouteFakeTokenStore(), Json { ignoreUnknownKeys = true })
        val viewModel = LoginViewModel(authRepo)

        composeRule.setContent {
            TrindadeTheme {
                LoginRoute(
                    onSignedIn = { signedInCalled++ },
                    viewModel = viewModel,
                )
            }
        }

        assertEquals(0, signedInCalled)
        assertEquals(false, viewModel.state.value.signedIn)

        viewModel.onUsernameChange("operador")
        viewModel.onPasswordChange("segredo")
        viewModel.submit()

        composeRule.waitForIdle()

        assertEquals("onSignedIn callback must be invoked exactly once", 1, signedInCalled)
        assertEquals("signedIn event must be consumed", false, viewModel.state.value.signedIn)
    }

    @Test
    fun `login route handles navigation to register callback`() {
        var registerClicked = 0
        val authRepo = AuthRepository(RouteFakeAuthApi(), RouteFakeTokenStore(), Json { ignoreUnknownKeys = true })
        val viewModel = LoginViewModel(authRepo)

        composeRule.setContent {
            TrindadeTheme {
                LoginRoute(
                    onSignedIn = {},
                    onNavigateToRegister = { registerClicked++ },
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithText(registerLinkLabel).performClick()
        composeRule.waitForIdle()

        assertEquals(1, registerClicked)
    }

    @Test
    fun `submit button preserves submit label and provides at least 48dp touch target during submission`() {
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = LoginViewModel.UiState(username = "operador", password = "segredo", submitting = true),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                )
            }
        }

        val submit = composeRule.onNodeWithText(submitLabel)
        submit.assertIsDisplayed()
        submit.assertHeightIsAtLeast(48.dp)
        submit.assertIsNotEnabled()
    }

    @Test
    fun `registration action has at least 48dp touch target height`() {
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = LoginViewModel.UiState(),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                )
            }
        }

        val link = composeRule.onNodeWithText(registerLinkLabel)
        link.assertIsDisplayed()
        link.assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `login error message is marked with assertive live region semantics`() {
        val unknownError = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.login_unknown)
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = LoginViewModel.UiState(message = LoginMessage.Unknown),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                )
            }
        }

        val errorNode = composeRule.onNodeWithText(unknownError)
        errorNode.assertIsDisplayed()
        errorNode.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    /**
     * The registration entry, drawn on the form and reported to the caller.
     *
     * The link is found by the resource the app ships rather than by a string typed here, for the same
     * reason the submit button is: a selector that cannot drift from the copy. One press has to produce
     * exactly one report -- the callback is the only thing this screen owns about the errand, and the
     * screen does not navigate itself -- so a second dispatch per press would be a defect this pins.
     */
    @Test
    fun `the registration link is shown and reports exactly one click per press`() {
        var registerClicks = 0
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = LoginViewModel.UiState(),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                    onRegisterClick = { registerClicks++ },
                )
            }
        }

        composeRule.onNodeWithText(registerLinkLabel).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, registerClicks)
    }

    /**
     * The link is available to a form the operator has not filled, and unavailable while a sign-in is in
     * flight.
     *
     * The first half is deliberate rather than incidental: registering is not conditional on having typed a
     * credential, so the link must not borrow the submit button's `canSubmit`. The second half is the
     * suppression this slice exists for: leaving then would abandon a request already being waited on, so
     * the press must do nothing at all and recovery must be automatic once the request settles.
     *
     * The assertion pairs the disabled declaration with the observed callback count, because a disabled
     * `TextButton` that still dispatched would be exactly the failure an enabled-state assertion alone
     * cannot catch.
     */
    @Test
    fun `the registration link stays available on an empty form and stops while a sign-in is pending`() {
        var registerClicks = 0
        val state = mutableStateOf(LoginViewModel.UiState())
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = state.value,
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                    onRegisterClick = { registerClicks++ },
                )
            }
        }

        // Blank fields: the submit action is disabled, the registration link is not.
        composeRule.onNodeWithText(registerLinkLabel).assertIsEnabled()

        composeRule.runOnUiThread {
            state.value = state.value.copy(username = "operador", password = "segredo", submitting = true)
        }
        val pendingLink = composeRule.onNodeWithText(registerLinkLabel)
        pendingLink.assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals("a disabled registration link dispatched its click while a sign-in was pending", 0, registerClicks)

        composeRule.runOnUiThread { state.value = state.value.copy(submitting = false) }
        composeRule.onNodeWithText(registerLinkLabel).assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(1, registerClicks)
    }

    /**
     * The registration link on the same viewport the squeeze regression was measured on.
     *
     * The link sits below the button, so it is the first thing to leave a short viewport and the last to
     * come back. The precondition is asserted, not assumed: if the content ever fits here,
     * "reachable" stops meaning anything. Reachability is then the scroll, which is the shared
     * `verticalScroll` doing the same job for this child as for the button above it.
     */
    @Test
    fun `a short viewport scrolls to the registration link instead of losing it`() {
        composeRule.setContent {
            Box(Modifier.size(width = 400.dp, height = 320.dp)) { TestLoginScreen() }
        }

        val link = composeRule.onNodeWithText(registerLinkLabel)

        link.assertIsNotDisplayed()
        link.performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `known server credential refusal is shown in Portuguese`() {
        val translated = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.login_invalid_credentials)
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = LoginViewModel.UiState(message = LoginMessage.FromServer("Invalid credentials")),
                    onUsernameChange = {}, onPasswordChange = {}, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText(translated).assertIsDisplayed()
        composeRule.onNodeWithText("Invalid credentials").assertDoesNotExist()
    }

    @Test
    fun `unknown server refusal remains unchanged`() {
        val refusal = "Account pending approval"
        composeRule.setContent {
            TrindadeTheme {
                LoginScreen(
                    state = LoginViewModel.UiState(message = LoginMessage.FromServer(refusal)),
                    onUsernameChange = {}, onPasswordChange = {}, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText(refusal).assertIsDisplayed()
    }

    /**
     * The regression itself: the screen must scroll to the button on a viewport too short to hold the
     * branding, the fields and the button.
     *
     * The first assertion is a precondition rather than a claim about the fix, and it is what keeps
     * this test from going quietly vacuous: if the content ever fits in this viewport, "the button can
     * be scrolled to" stops meaning anything. The second is the squeeze -- a Material button is 40dp
     * tall (`ButtonDefaults.MinHeight`) and 0 when it is measured against no space at all -- and it is the
     * one the recorded mutation trips **first**, with `Actual height is 0.0.dp, expected at least
     * 40.0.dp`. The third is reachability, and the same mutation does not fail it so much as make it
     * impossible: with no scrollable ancestor there is nothing to scroll, so the run never reaches it.
     */
    @Test
    fun `a short viewport scrolls to the submit button instead of squeezing it`() {
        composeRule.setContent {
            Box(Modifier.size(width = 400.dp, height = 320.dp)) { TestLoginScreen() }
        }

        val submit = composeRule.onNodeWithText(submitLabel)

        submit.assertIsNotDisplayed()
        submit.assertHeightIsAtLeast(40.dp)
        submit.performScrollTo().assertIsDisplayed()
    }

    /**
     * The other half of the fix, and the half a scroll container trades away by default: the fix keeps
     * the centring the web login has (`min-h-screen flex items-center justify-center`).
     *
     * The claim is measured as a difference instead of a position, because that is what "centred"
     * means and it needs no knowledge of how tall the content is: content that is centred moves by half
     * of what the viewport grows by, and content pinned to the top does not move at all. The two
     * hypotheses are 100dp apart here, so the test states a number rather than a direction. Growing the
     * box is how the same screen is measured twice: `setContent` may only be called once per test.
     *
     * The precondition both measurements rest on is asserted rather than assumed, and it is asserted as
     * "the whole screen is on screen at once" rather than as a height: a height assertion cannot make this
     * claim, because the scroll container this very fix installs is what keeps the submit button at its 40dp
     * when the content outgrows the box -- so the height stays 40dp in both regimes and the guard passed
     * while the regime it named had already broken. If the content ever grows past the box, the button
     * leaves the viewport and `assertTheBoxHoldsTheWholeScreen` fails here, naming the precondition, instead
     * of surfacing later as a movement of less than half and being read as a centring regression.
     */
    @Test
    fun `the content still centres when the viewport holds it`() {
        var viewportHeight by mutableStateOf(700.dp)
        composeRule.setContent {
            Box(Modifier.size(width = 400.dp, height = viewportHeight)) { TestLoginScreen() }
        }

        assertTheBoxHoldsTheWholeScreen()
        // The bounds come back in dp, not in pixels, and the annotated type says so: `getBoundsInRoot()`
        // answers in `Dp` -- its `bottom` is a `Dp` whose `.value` is the number of dp -- while the
        // neighbouring `SemanticsNode.boundsInRoot` is the API that answers in pixels. Naming the unit was
        // worth a line here because this test is the one the lane's next geometry tests are copied from.
        val bottomInShortBox: Dp = composeRule.onNodeWithText(submitLabel).getBoundsInRoot().bottom

        val grownBy = 200.dp
        composeRule.runOnUiThread { viewportHeight += grownBy }
        composeRule.waitForIdle()

        assertTheBoxHoldsTheWholeScreen()
        val bottomInTallBox: Dp = composeRule.onNodeWithText(submitLabel).getBoundsInRoot().bottom

        val moved = bottomInTallBox.value - bottomInShortBox.value
        val expected = (grownBy / 2).value
        // The basis of this allowance is measured rather than chosen: with the tolerance at zero this
        // assertion still passes, so the movement here is exactly the 100dp that separates the two
        // hypotheses -- this lane runs at mdpi, where a dp is a pixel and the layout lands on whole dp. The
        // 8dp is the room a different density or fractional text metrics could need for sub-dp rounding, and
        // it stays an order of magnitude below the 100dp gap, so it can never admit the behaviour this
        // assertion exists to reject.
        val tolerance = 8f

        assertTrue(
            "the submit button moved ${moved}dp when the viewport grew by ${grownBy.value}dp. Centred " +
                "content moves by half of that (${expected}dp) and content pinned to the top does not " +
                "move at all, so this measures neither.",
            moved >= expected - tolerance && moved <= expected + tolerance,
        )
    }

    /**
     * The precondition the two measurements above rest on: the box holds the whole screen, which is what
     * makes "centred" a thing this test can measure at all.
     *
     * The heading is the top of the content and the submit button is its bottom, so both being displayed
     * at the same time is exactly "it fits". The height of the button is not, for the reason above: the
     * scroll container keeps it at 40dp whether or not the content fits, so a height assertion is true in
     * both regimes and cannot fail when this one breaks.
     */
    private fun assertTheBoxHoldsTheWholeScreen() {
        composeRule.onNodeWithText(titleLabel).assertIsDisplayed()
        composeRule.onNodeWithText(submitLabel).assertIsDisplayed()
    }

    /**
     * The screen as the operator meets it after typing: both fields filled, so the submit button is
     * enabled and is the button the layout has to keep reachable -- asserting on a disabled button
     * would be measuring a control nobody is trying to press.
     */
    @Composable
    private fun TestLoginScreen() {
        TrindadeTheme {
            LoginScreen(
                state = LoginViewModel.UiState(username = "operador", password = "segredo"),
                onUsernameChange = {},
                onPasswordChange = {},
                onSubmit = {},
            )
        }
    }

    private class RouteFakeTokenStore : TokenStore {
        private var access: String? = null
        private var refresh: String? = null
        private var role: String? = null

        override fun accessToken(): String? = access
        override fun refreshToken(): String? = refresh
        override fun role(): String? = role
        override fun save(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }
        override fun saveRole(role: String) { this.role = role }
        override fun clear() {
            access = null
            refresh = null
            role = null
        }
    }

    private class RouteFakeAuthApi : AuthApi {
        override suspend fun login(body: LoginRequest): Response<LoginResponse> = Response.success(
            LoginResponse(
                token = "jwt_access_token",
                refreshToken = "jwt_refresh_token",
                expiresIn = 900,
                user = LoginResponseUser(id = 1, username = "operador", role = "Trabalhador"),
            )
        )
        override suspend fun refresh(body: RefreshRequest) = Response.error<RefreshResponse>(500, "".toResponseBody())
        override suspend fun logout(body: LogoutRequest) = Response.success(SuccessResponse(SuccessResponse.Success.`true`))
        override suspend fun me() = Response.error<ProfileResponse>(500, "".toResponseBody())
        override suspend fun profile() = Response.error<ProfileResponse>(500, "".toResponseBody())
        override suspend fun updateProfile(body: UpdateProfileRequest) = Response.error<ProfileResponse>(500, "".toResponseBody())
        override suspend fun changePassword(body: ChangePasswordRequest) = Response.success(SuccessResponse(SuccessResponse.Success.`true`))
        override suspend fun setupStatus() = Response.error<SetupStatusResponse>(500, "".toResponseBody())
        override suspend fun register(body: RegisterRequest) = Response.error<RegisterResponse>(500, "".toResponseBody())
    }
}
