package com.trindade.app.auth

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
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
 * `ui-test-manifest`, which is a debug-only dependency by design -- it must never reach a release APK. In
 * the shared source set this test compiled and then could not launch under `testReleaseUnitTest` or a
 * plain `./gradlew test`; in `testDebug` the variant that has the activity is the only one that runs it.
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
}
