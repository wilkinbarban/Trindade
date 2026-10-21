package com.trindade.app.massas

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.TokenStore
import com.trindade.app.network.AuthApi
import com.trindade.app.network.MassaIndividualLaunch
import com.trindade.app.network.MassasApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class MassasLaunchViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: MassasLaunchViewModel

    private class FakeTokenStore : TokenStore {
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
        override fun saveRole(role: String) {
            this.role = role
        }
        override fun clear() {
            access = null
            refresh = null
            role = null
        }
    }

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
        val authApi = retrofit.create(AuthApi::class.java)
        val tokenStore = FakeTokenStore()
        val authRepository = AuthRepository(authApi, tokenStore, json)
        val massasRepository = MassasRepository(massasApi)

        viewModel = MassasLaunchViewModel(massasRepository, authRepository)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun saopauloToday_matchesDateFormat() {
        val today = MassasLaunchViewModel.saopauloToday()
        assertTrue("formato YYYY-MM-DD", Regex("""\d{4}-\d{2}-\d{2}""").matches(today))
    }

    @Test
    fun onQuantityChange_handlesInputsCorrectly() {
        viewModel.onQuantityChange("1045", "5")
        assertEquals("5", viewModel.state.value.quantities["1045"])

        // Non-digits are stripped
        viewModel.onQuantityChange("1045", "5a3")
        assertEquals("53", viewModel.state.value.quantities["1045"])

        // Zero or empty removes entry
        viewModel.onQuantityChange("1045", "0")
        assertNull(viewModel.state.value.quantities["1045"])

        viewModel.onQuantityChange("1045", "10")
        viewModel.onQuantityChange("1045", "")
        assertNull(viewModel.state.value.quantities["1045"])
    }

    @Test
    fun editLifecycle_updatesState() {
        val launch = MassaIndividualLaunch(
            id = 42,
            launchDate = "2025-05-18",
            productCode = "1045",
            productName = "MASSA CASEIRA",
            boxes = 4,
            units = 40,
            userId = 1,
            username = "wilkin",
            displayName = "Wilkin",
            createdAt = "2025-05-18T10:00:00Z",
            updatedAt = "2025-05-18T10:00:00Z",
        )

        viewModel.startEdit(launch)
        assertEquals(42, viewModel.state.value.editingLaunchId)
        assertEquals("4", viewModel.state.value.editingBoxes)

        viewModel.onEditingBoxesChange("8")
        assertEquals("8", viewModel.state.value.editingBoxes)

        viewModel.cancelEdit()
        assertNull(viewModel.state.value.editingLaunchId)
        assertEquals("", viewModel.state.value.editingBoxes)
    }

    @Test
    fun deletePromptLifecycle_updatesState() {
        viewModel.promptDelete(99)
        assertEquals(99, viewModel.state.value.deleteConfirmLaunchId)

        viewModel.dismissDelete()
        assertNull(viewModel.state.value.deleteConfirmLaunchId)
    }

    @Test
    fun toggleHelpDialog_updatesState() {
        assertFalse(viewModel.state.value.showHelpDialog)
        viewModel.toggleHelpDialog(true)
        assertTrue(viewModel.state.value.showHelpDialog)
        viewModel.toggleHelpDialog(false)
        assertFalse(viewModel.state.value.showHelpDialog)
    }

    @Test
    fun getWhatsAppLink_returnsNullWhenNoDigits() {
        viewModel.onPhoneChange("")
        assertNull(viewModel.getWhatsAppLink())
        viewModel.onPhoneChange("abc")
        assertNull(viewModel.getWhatsAppLink())
    }
}
