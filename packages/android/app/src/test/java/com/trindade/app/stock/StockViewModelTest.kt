package com.trindade.app.stock

import com.trindade.app.contract.models.StockDayProductsInner
import com.trindade.app.network.StockApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The counter's state, tested without a screen.
 *
 * The network paths are covered where they belong, in the repository's own tests against a server; what is
 * pinned here is the part a screen depends on: that the two sections come from the catalogue the API sent,
 * that the warning keeps to the field that was left, that a field being typed in is never accused, and that
 * the message and the email are the text the plant reads -- with the plant's mailbox as the destination and
 * not the author's.
 */
class StockViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: StockViewModel

    private val canudo = StockDayProductsInner(
        code = "787", name = "CANUDO", category = "Terceirizados", unitsPerBox = 30, boxes = 5, units = 150
    )
    private val massa = StockDayProductsInner(
        code = "1047", name = "MASSA ROLO 2KG", category = "Massas", unitsPerBox = 6, boxes = 2, units = 12
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        viewModel = StockViewModel(StockRepository(retrofit.create(StockApi::class.java)))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the day is read as Sao Paulo reads it`() {
        // The device may be anywhere; the count belongs to the plant's calendar.
        val today = StockViewModel.saopauloToday()
        assertTrue("formato YYYY-MM-DD", Regex("""\d{4}-\d{2}-\d{2}""").matches(today))
    }

    @Test
    fun `typing is never accused and leaving the field is`() {
        viewModel.onFocus("787")
        viewModel.onQuantityChange("787", "0")
        assertEquals(emptyList<String>(), viewModel.visibleErrors())

        viewModel.onBlur("787")
        assertEquals(listOf("787"), viewModel.visibleErrors())

        viewModel.onFocus("787")
        viewModel.onQuantityChange("787", "5")
        assertEquals(emptyList<String>(), viewModel.visibleErrors())
    }

    @Test
    fun `the message and the email carry what was counted and the plant's mailbox`() {
        val day = com.trindade.app.contract.models.StockDay(
            date = "2026-10-15", products = listOf(canudo, massa), totalBoxes = 7, totalUnits = 162
        )
        val mensaje = stockMessage(day)
        assertTrue(mensaje.contains("CANUDO (787) — 150 un"))
        assertTrue(mensaje.contains("MASSA ROLO 2KG (1047) — 12 un"))
        assertTrue(mensaje.contains("Total: 7 caixas · 162 unidades"))

        val cuerpo = stockEmailBody(day)
        assertTrue(cuerpo.contains("Categoria | Código | Produto | Unidades").not())
        assertTrue("las columnas van medidas", cuerpo.contains("Categoria") && cuerpo.contains("Unidades"))
        assertTrue("el total cae bajo las unidades", Regex("""Total\s*\|.*\|\s+162""").containsMatchIn(cuerpo))
        assertTrue("sin la columna de bultos", !cuerpo.contains("Caixas"))
    }

    @Test
    fun `the destination is the plant and never the author`() {
        val link = viewModel.mailtoLink()
        assertTrue(link.startsWith("mailto:xmitrindade@hotmail.com"))
        assertTrue("el asunto viaja codificado", link.contains("subject="))
        assertTrue("y el correo del autor no es destino", !link.startsWith("mailto:wilkin.barban@gmail.com"))
    }

    @Test
    fun `a link needs a number and a count to carry`() {
        assertNull("sin número no hay conversación", viewModel.whatsappLink())

        viewModel.onNumberChange("(11) 99999-8888")
        assertTrue(viewModel.whatsappLink() != null)
    }

    @Test
    fun `only administrator can edit stock counts`() {
        assertTrue(StockViewModel.canEdit("Administrador"))
        assertTrue(!StockViewModel.canEdit("Trabalhador"))
        assertTrue(!StockViewModel.canEdit(null))

        viewModel.onRoleKnown("Trabalhador")
        assertEquals(false, viewModel.canEdit())

        viewModel.onQuantityChange("787", "5")
        viewModel.addEntry(canudo)
        assertEquals("Apenas administradores podem atualizar o estoque.", viewModel.state.value.note)

        viewModel.resetProduct(canudo)
        assertEquals("Apenas administradores podem atualizar o estoque.", viewModel.state.value.note)

        viewModel.onRoleKnown("Administrador")
        assertEquals(true, viewModel.canEdit())
    }
}
