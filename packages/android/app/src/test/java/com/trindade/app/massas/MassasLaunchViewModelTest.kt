package com.trindade.app.massas

import androidx.lifecycle.viewModelScope
import com.trindade.app.R
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.TokenStore
import com.trindade.app.network.AuthApi
import com.trindade.app.network.MassaIndividualLaunch
import com.trindade.app.network.MassasApi
import com.trindade.app.network.MassasLaunchDayView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
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
    private lateinit var massasApi: MassasApi
    private lateinit var authRepository: AuthRepository
    private lateinit var massasRepository: MassasRepository

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        massasApi = retrofit.create(MassasApi::class.java)
        val authApi = retrofit.create(AuthApi::class.java)
        val tokenStore = FakeTokenStore()
        authRepository = AuthRepository(authApi, tokenStore, json)
        massasRepository = MassasRepository(massasApi)

        viewModel = MassasLaunchViewModel(massasRepository, authRepository)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        // The view model polls on its own scope from the moment it is constructed, and nothing clears it
        // here. A poll that is still in flight when the test ends resumes on `Dispatchers.Main` while the
        // next test's tearDown resets it, and kotlinx-coroutines-test answers that overlap with
        // `IllegalStateException: Dispatchers.Main is used concurrently with setting it` -- the failure
        // this class produced in CI while passing on a slower machine. Cancelling the scope first stops the
        // polling before the server it talks to goes away, so both the shutdown and the reset run alone.
        viewModel.viewModelScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
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

    @Test
    fun clearQuantities_clearsAllInputs() {
        viewModel.onQuantityChange("1045", "4")
        viewModel.onQuantityChange("1046", "2")
        assertEquals(2, viewModel.state.value.quantities.size)

        viewModel.clearQuantities()
        assertTrue(viewModel.state.value.quantities.isEmpty())
    }

    private class FakeMassasRepository(api: MassasApi) : MassasRepository(api) {
        var createLaunchResult: MassasWriteResult = MassasWriteResult.Saved(
            MassasLaunchDayView(date = "2026-10-21", totalUnits = 30, totalBoxes = 3)
        )
        var lastCreatedItems: List<com.trindade.app.network.CreateMassasLaunchItem>? = null

        override suspend fun createLaunch(
            date: String,
            items: List<com.trindade.app.network.CreateMassasLaunchItem>,
        ): MassasWriteResult {
            lastCreatedItems = items
            return createLaunchResult
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submitLaunch_onSuccess_clearsQuantitiesAndSetsNotice() = runTest {
        val fakeRepo = FakeMassasRepository(massasApi)
        val testVm = MassasLaunchViewModel(fakeRepo, authRepository)

        testVm.onQuantityChange("1045", "3")
        assertEquals(1, testVm.state.value.quantities.size)

        testVm.submitLaunch()
        testScheduler.runCurrent()

        assertTrue(testVm.state.value.quantities.isEmpty())
        assertFalse(testVm.state.value.isSubmitting)
        assertEquals(R.string.massas_success, testVm.state.value.noticeMessage?.resId)
        assertEquals(30, testVm.state.value.dayView?.totalUnits)
        assertEquals(1, fakeRepo.lastCreatedItems?.size)
        assertEquals("1045", fakeRepo.lastCreatedItems?.first()?.productCode)
        assertEquals(3, fakeRepo.lastCreatedItems?.first()?.boxes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmLaunch_onSuccess_setsResultDialogAndClearsQuantities() = runTest {
        val fakeRepo = FakeMassasRepository(massasApi)
        val testVm = MassasLaunchViewModel(fakeRepo, authRepository)

        // Setup dayView with products
        testVm.onQuantityChange("1045", "3")
        // Trigger confirmation
        testVm.requestLaunchConfirmation()
        assertTrue(testVm.state.value.showConfirmLaunchDialog)
        assertEquals(1, testVm.state.value.confirmItems.size)
        assertEquals("1045", testVm.state.value.confirmItems[0].productCode)
        assertEquals(3, testVm.state.value.confirmItems[0].boxes)

        // Dismiss test
        testVm.dismissConfirmLaunch()
        assertFalse(testVm.state.value.showConfirmLaunchDialog)
        assertTrue(testVm.state.value.confirmItems.isEmpty())

        // Re-request and confirm
        testVm.requestLaunchConfirmation()
        testVm.confirmLaunch()
        testScheduler.runCurrent()

        assertFalse(testVm.state.value.showConfirmLaunchDialog)
        assertTrue(testVm.state.value.quantities.isEmpty())
        assertTrue(testVm.state.value.resultDialogIsSuccess)
        assertEquals(R.string.massas_success, testVm.state.value.resultDialogMessage?.resId)

        testVm.dismissResultDialog()
        assertNull(testVm.state.value.resultDialogMessage)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmLaunch_onError_preservesQuantitiesAndSetsErrorDialog() = runTest {
        val fakeRepo = FakeMassasRepository(massasApi)
        fakeRepo.createLaunchResult = MassasWriteResult.Refused(400)
        val testVm = MassasLaunchViewModel(fakeRepo, authRepository)

        testVm.onQuantityChange("1045", "5")
        testVm.requestLaunchConfirmation()
        testVm.confirmLaunch()
        testScheduler.runCurrent()

        // Quantities MUST NOT be cleared on failure, so operator can retry or correct
        assertEquals("5", testVm.state.value.quantities["1045"])
        assertFalse(testVm.state.value.resultDialogIsSuccess)
        assertEquals(R.string.massas_error_create_refused, testVm.state.value.resultDialogMessage?.resId)
        assertEquals(listOf(400), testVm.state.value.resultDialogMessage?.args)
    }

    @Test
    fun selectOperator_updatesSelectedOperatorId() {
        assertNull(viewModel.state.value.selectedOperatorId)
        viewModel.selectOperator(15)
        assertEquals(15, viewModel.state.value.selectedOperatorId)
        viewModel.selectOperator(null)
        assertNull(viewModel.state.value.selectedOperatorId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun massasRepository_createLaunch_parses201AndRefreshesDay() = runTest {
        val testServer = MockWebServer()
        testServer.start()
        try {
            val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
            val retrofit = Retrofit.Builder()
                .baseUrl(testServer.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            val api = retrofit.create(MassasApi::class.java)
            val repo = MassasRepository(api)

            testServer.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setBody("""{"created":[{"id":10,"launch_date":"2026-10-21","product_code":"1045","boxes":3,"units":30,"user_id":1}]}""")
            )
            testServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"date":"2026-10-21","products":[],"launches":[],"totalBoxes":3,"totalUnits":30}""")
            )

            val result = repo.createLaunch("2026-10-21", listOf(com.trindade.app.network.CreateMassasLaunchItem("1045", 3)))
            assertTrue(result is MassasWriteResult.Saved)
            val saved = result as MassasWriteResult.Saved
            assertEquals(30, saved.view.totalUnits)

            val postRequest = testServer.takeRequest()
            assertEquals("/api/massas-launch", postRequest.path)
            assertEquals("POST", postRequest.method)
            assertTrue(postRequest.body.readUtf8().contains(""""boxes":3"""))

            val getRequest = testServer.takeRequest()
            assertEquals("/api/massas-launch?date=2026-10-21", getRequest.path)
            assertEquals("GET", getRequest.method)
        } finally {
            testServer.shutdown()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadDay_withPastDate_setsReadOnlyAndForbidsModifications() = runTest {
        val fakeRepo = FakeMassasRepository(massasApi)
        val testVm = MassasLaunchViewModel(fakeRepo, authRepository)
        testScheduler.runCurrent()

        testVm.loadDay("2020-01-01")
        testScheduler.runCurrent()

        assertTrue(testVm.state.value.isReadOnly)
        assertEquals("2020-01-01", testVm.state.value.date)

        // Quantity change is ignored when isReadOnly
        testVm.onQuantityChange("1045", "5")
        assertTrue(testVm.state.value.quantities.isEmpty())

        // Launch confirmation cannot be requested when isReadOnly
        testVm.requestLaunchConfirmation()
        assertFalse(testVm.state.value.showConfirmLaunchDialog)

        // Past launch cannot be edited
        val pastLaunch = MassaIndividualLaunch(
            id = 1,
            launchDate = "2020-01-01",
            productCode = "1045",
            productName = "MASSA CASEIRA",
            boxes = 5,
            units = 50,
            userId = 1,
            username = "admin",
            displayName = "Admin",
            createdAt = "2020-01-01T10:00:00Z",
            updatedAt = "2020-01-01T10:00:00Z",
        )
        assertFalse(testVm.canEdit(pastLaunch))
        testVm.startEdit(pastLaunch)
        assertNull(testVm.state.value.editingLaunchId)

        // Delete prompt is ignored when isReadOnly
        testVm.promptDelete(1)
        assertNull(testVm.state.value.deleteConfirmLaunchId)

        // Return to today clears isReadOnly
        testVm.loadDay(MassasLaunchViewModel.saopauloToday())
        testScheduler.runCurrent()
        assertFalse(testVm.state.value.isReadOnly)
    }
}
