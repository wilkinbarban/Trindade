package com.trindade.app.stock

import com.trindade.app.contract.models.*
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
 * The past day as a reading: what the screen derives from the state it has.
 *
 * The correction used to live here and does not anymore -- one calculator per shelf, and this one reads --
 * so what is left to pin is the day the screen loads, the way it is chosen, and the fact that both ways of
 * sending it out name the plant and never the author.
 *
 * Everything here is synchronous on purpose. The paths that talk to the network are covered where they
 * belong, in the repository's own tests against a server.
 */
class StockHistoryViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var retrofit: Retrofit
    private lateinit var viewModel: StockHistoryViewModel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        retrofit = Retrofit.Builder()
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
    fun `the destination of the history mail is the plant and never the author`() {
        val link = viewModel.mailtoLink()
        assertTrue(link.startsWith("mailto:xmitrindade@hotmail.com"))
        assertFalse(link.startsWith("mailto:wilkin.barban@gmail.com"))
    }

    @Test
    fun `a day nobody counted lists nothing and says nothing`() {
        assertEquals("", viewModel.message())
        assertEquals("", viewModel.emailBody())
    }

    @Test
    fun `onDateChange updates date without querying when date is partial or invalid ISO`() {
        viewModel.onDateChange("2")
        assertEquals("2", viewModel.state.value.date)
        assertEquals(false, viewModel.state.value.loading)

        viewModel.onDateChange("2026-1")
        assertEquals("2026-1", viewModel.state.value.date)
        assertEquals(false, viewModel.state.value.loading)
    }

    @Test
    fun `onDateChange accepts valid ISO date and updates state`() {
        viewModel.onDateChange("2026-04-18")
        assertEquals("2026-04-18", viewModel.state.value.date)
    }
}
