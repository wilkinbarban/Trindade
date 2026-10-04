package com.trindade.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumberStepperTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `number stepper buttons meet 48dp minimum touch target bounds`() {
        composeRule.setContent {
            TrindadeTheme {
                NumberStepper(
                    value = 2,
                    onValueChange = {},
                    minValue = 1,
                    maxValue = 5,
                )
            }
        }

        val decNode = composeRule.onNodeWithText("-")
        decNode.assertIsDisplayed()
        val decBounds = decNode.getUnclippedBoundsInRoot()
        assertTrue(
            "Decrement button height must be >= 48dp but was ${decBounds.bottom - decBounds.top}",
            decBounds.bottom - decBounds.top >= 48.dp,
        )
        assertTrue(
            "Decrement button width must be >= 48dp but was ${decBounds.right - decBounds.left}",
            decBounds.right - decBounds.left >= 48.dp,
        )

        val incNode = composeRule.onNodeWithText("+")
        incNode.assertIsDisplayed()
        val incBounds = incNode.getUnclippedBoundsInRoot()
        assertTrue(
            "Increment button height must be >= 48dp but was ${incBounds.bottom - incBounds.top}",
            incBounds.bottom - incBounds.top >= 48.dp,
        )
        assertTrue(
            "Increment button width must be >= 48dp but was ${incBounds.right - incBounds.left}",
            incBounds.right - incBounds.left >= 48.dp,
        )
    }

    @Test
    fun `tapping plus increments and tapping minus decrements`() {
        var currentValue = 2
        composeRule.setContent {
            var value by mutableIntStateOf(currentValue)
            TrindadeTheme {
                NumberStepper(
                    value = value,
                    onValueChange = {
                        value = it
                        currentValue = it
                    },
                    minValue = 1,
                    maxValue = 5,
                )
            }
        }

        composeRule.onNodeWithText("+").performClick()
        assertEquals(3, currentValue)
        composeRule.onNodeWithText("3").assertIsDisplayed()

        composeRule.onNodeWithText("-").performClick()
        assertEquals(2, currentValue)
        composeRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun `decrement disabled at minimum value and increment disabled at maximum value`() {
        composeRule.setContent {
            TrindadeTheme {
                NumberStepper(
                    value = 1,
                    onValueChange = {},
                    minValue = 1,
                    maxValue = 3,
                )
            }
        }

        composeRule.onNodeWithText("-").assertIsNotEnabled()
        composeRule.onNodeWithText("+").assertIsEnabled()
    }

    @Test
    fun `increment disabled at maximum value`() {
        composeRule.setContent {
            TrindadeTheme {
                NumberStepper(
                    value = 3,
                    onValueChange = {},
                    minValue = 1,
                    maxValue = 3,
                )
            }
        }

        composeRule.onNodeWithText("+").assertIsNotEnabled()
        composeRule.onNodeWithText("-").assertIsEnabled()
    }

    @Test
    fun `stepper completely disabled when enabled is false`() {
        composeRule.setContent {
            TrindadeTheme {
                NumberStepper(
                    value = 2,
                    onValueChange = {},
                    minValue = 1,
                    maxValue = 3,
                    enabled = false,
                )
            }
        }

        composeRule.onNodeWithText("-").assertIsNotEnabled()
        composeRule.onNodeWithText("+").assertIsNotEnabled()
    }
}
