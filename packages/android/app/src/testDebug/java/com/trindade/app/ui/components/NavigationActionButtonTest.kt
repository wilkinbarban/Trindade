package com.trindade.app.ui.components

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationActionButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

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
}
