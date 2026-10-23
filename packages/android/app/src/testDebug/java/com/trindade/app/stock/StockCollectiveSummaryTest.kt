package com.trindade.app.stock

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.StockDayProductsInner
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A resumo coletivo do dia, desenhado em vez de lido.
 *
 * A seção é uma função do que o servidor somou, então o que se prova aqui é o que a tela faz com essa
 * lista: chama-se pelo título que o operador conhece, desenha um cartão por produto contado com o nome,
 * o un/cx e as duas etiquetas, e diz que não há nada quando o dia está vazio -- em vez de um cartão em
 * branco que parece uma falha de carregamento.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StockCollectiveSummaryTest {
    @get:Rule val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)

    private val canudo = StockDayProductsInner(
        code = "787", name = "CANUDO", category = "Terceirizados", unitsPerBox = 30, boxes = 5, units = 150
    )
    private val massa = StockDayProductsInner(
        code = "1047", name = "MASSA ROLO 2KG", category = "Massas", unitsPerBox = 6, boxes = 2, units = 12
    )

    @Test
    fun `draws one amber card per counted product with its un-cx and its two badges`() {
        composeRule.setContent { TrindadeTheme { StockCollectiveSummary(products = listOf(canudo, massa)) } }

        composeRule.onNodeWithText(copy(R.string.stock_collective_summary)).assertIsDisplayed()
        composeRule.onNodeWithText("CANUDO").assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_units_per_box_short, 30)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_summary_boxes, 5)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_summary_units, 150)).assertIsDisplayed()
        composeRule.onNodeWithText("MASSA ROLO 2KG").assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_summary_boxes, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_summary_units, 12)).assertIsDisplayed()
    }

    @Test
    fun `says nothing was counted instead of drawing an empty card`() {
        composeRule.setContent { TrindadeTheme { StockCollectiveSummary(products = emptyList()) } }

        composeRule.onNodeWithText(copy(R.string.stock_collective_summary)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_no_products_counted)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "es")
    fun `speaks spanish when the phone does`() {
        composeRule.setContent { TrindadeTheme { StockCollectiveSummary(products = listOf(canudo)) } }

        composeRule.onNodeWithText("Resumen Colectivo del Día").assertIsDisplayed()
        composeRule.onNodeWithText("30 un/cj").assertIsDisplayed()
        composeRule.onNodeWithText("5 cj").assertIsDisplayed()
    }
}
