package com.trindade.app.massas

import com.trindade.app.network.MassasApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class MassasHistoryViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: MassasHistoryViewModel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val massasApi = retrofit.create(MassasApi::class.java)
        val massasRepository = MassasRepository(massasApi)

        viewModel = MassasHistoryViewModel(massasRepository)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun toggleHelpDialog_updatesState() {
        assertFalse(viewModel.state.value.showHelpDialog)
        viewModel.toggleHelpDialog(true)
        assertTrue(viewModel.state.value.showHelpDialog)
        viewModel.toggleHelpDialog(false)
        assertFalse(viewModel.state.value.showHelpDialog)
    }
}
