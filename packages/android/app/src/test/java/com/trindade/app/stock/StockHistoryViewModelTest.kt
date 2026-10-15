package com.trindade.app.stock

import com.trindade.app.contract.models.StockDayProductsInner
import com.trindade.app.network.StockApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The past day's boundary and what the screen derives from the state it has.
 *
 * The role rule is pinned first because it is the kind of rule that quietly becomes decoration: a screen
 * that offers a correction the server will refuse teaches the operator about permissions by saying no. The
 * boundary itself lives on the server, and this keeps the two ends saying the same thing.
 *
 * Everything here is synchronous on purpose. The paths that talk to the network are covered where they
 * belong -- in the repository's own tests against a server -- and a ViewModel test that had to start the
 * network to assert a permission would be testing the wrong end of the wire.
 */
class StockHistoryViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: StockHistoryViewModel

    private val canudo = StockDayProductsInner(
        code = "787", name = "CANUDO", category = "Terceirizados", unitsPerBox = 30, boxes = 5, units = 150
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
        viewModel = StockHistoryViewModel(StockRepository(retrofit.create(StockApi::class.java)))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `who may correct a past day is the same string the server checks`() {
        assertTrue(StockHistoryViewModel.canEdit("Administrador"))
        assertFalse(StockHistoryViewModel.canEdit("Trabalhador"))
        assertFalse(StockHistoryViewModel.canEdit(null))
    }

    @Test
    fun `a worker is told instead of being refused later`() {
        viewModel.onRoleKnown("Trabalhador")
        assertFalse(viewModel.canEdit())

        viewModel.addEntry(canudo)
        assertEquals("Seu perfil não corrige contagens.", viewModel.state.value.note)

        viewModel.onNote("")
        viewModel.resetProduct(canudo)
        assertEquals("Seu perfil não corrige contagens.", viewModel.state.value.note)
    }

    @Test
    fun `an administrator is the one the screen offers the correction to`() {
        viewModel.onRoleKnown("Administrador")
        assertTrue(viewModel.canEdit())
    }

    @Test
    fun `the destination of the history mail is the plant and never the author`() {
        val link = viewModel.mailtoLink()
        assertTrue(link.startsWith("mailto:xmitrindade@hotmail.com"))
        assertFalse(link.startsWith("mailto:wilkin.barban@gmail.com"))
    }

    @Test
    fun `a day nobody counted lists nothing and says nothing`() {
        assertEquals(emptyList<StockDayProductsInner>(), viewModel.counted())
        assertEquals("", viewModel.message())
        assertEquals("", viewModel.emailBody())
    }

    @Test
    fun `the warning keeps to the field that was left`() {
        viewModel.onFocus("787")
        viewModel.onQuantityChange("787", "0")
        assertEquals(emptyList<String>(), viewModel.visibleErrors())

        viewModel.onBlur("787")
        assertEquals(listOf("787"), viewModel.visibleErrors())
    }
}
