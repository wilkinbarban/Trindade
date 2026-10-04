package com.trindade.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class AdminActionButtonsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `admin primary button meets 48dp bounds and invokes onClick`() {
        var clicked = 0
        composeRule.setContent {
            TrindadeTheme {
                AdminPrimaryButton(
                    onClick = { clicked++ },
                    text = "Criar",
                )
            }
        }

        val node = composeRule.onNodeWithText("Criar")
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Height must be >= 48dp", bounds.bottom - bounds.top >= 48.dp)
        assertTrue("Width must be >= 48dp", bounds.right - bounds.left >= 48.dp)

        node.performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `admin secondary button meets 48dp bounds and invokes onClick`() {
        var clicked = 0
        composeRule.setContent {
            TrindadeTheme {
                AdminSecondaryButton(
                    onClick = { clicked++ },
                    text = "Cancelar",
                )
            }
        }

        val node = composeRule.onNodeWithText("Cancelar")
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Height must be >= 48dp", bounds.bottom - bounds.top >= 48.dp)
        assertTrue("Width must be >= 48dp", bounds.right - bounds.left >= 48.dp)

        node.performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `admin destructive button meets 48dp bounds and invokes onClick`() {
        var clicked = 0
        composeRule.setContent {
            TrindadeTheme {
                AdminDestructiveButton(
                    onClick = { clicked++ },
                    text = "Excluir",
                )
            }
        }

        val node = composeRule.onNodeWithText("Excluir")
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Height must be >= 48dp", bounds.bottom - bounds.top >= 48.dp)
        assertTrue("Width must be >= 48dp", bounds.right - bounds.left >= 48.dp)

        node.performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `admin destructive confirm button meets 48dp bounds and invokes onClick`() {
        var clicked = 0
        composeRule.setContent {
            TrindadeTheme {
                AdminDestructiveConfirmButton(
                    onClick = { clicked++ },
                    text = "Confirmar",
                )
            }
        }

        val node = composeRule.onNodeWithText("Confirmar")
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Height must be >= 48dp", bounds.bottom - bounds.top >= 48.dp)
        assertTrue("Width must be >= 48dp", bounds.right - bounds.left >= 48.dp)

        node.performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `admin choice chip meets 48dp bounds and toggles selection`() {
        var clicked = 0
        composeRule.setContent {
            TrindadeTheme {
                AdminChoiceChip(
                    selected = true,
                    onClick = { clicked++ },
                    label = "Temperatura",
                )
            }
        }

        val node = composeRule.onNodeWithText("Temperatura")
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Height must be >= 48dp", bounds.bottom - bounds.top >= 48.dp)
        assertTrue("Width must be >= 48dp", bounds.right - bounds.left >= 48.dp)

        node.performClick()
        assertEquals(1, clicked)
    }
}
