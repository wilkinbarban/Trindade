package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponse
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.RefreshResponse
import com.trindade.app.contract.models.RegisterRequest
import com.trindade.app.contract.models.RegisterResponse
import com.trindade.app.contract.models.SetupRequest
import com.trindade.app.contract.models.SetupResponse
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AuthApi
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TimeSlotsAdminViewModelTest {

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class FakeTokenStore(
        private var access: String? = "mock-token",
        private var refresh: String? = "mock-refresh",
        private var role: String? = RolePolicy.ADMIN,
    ) : TokenStore {
        override fun accessToken(): String? = access
        override fun refreshToken(): String? = refresh
        override fun role(): String? = role
        override fun save(accessToken: String, refreshToken: String) {
            this.access = accessToken
            this.refresh = refreshToken
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

    private class FakeAuthApi(
        private val user: ProfileResponseUser = ProfileResponseUser(
            id = 42,
            username = "admin_user",
            displayName = "Admin User",
            role = RolePolicy.ADMIN,
        ),
    ) : AuthApi {
        override suspend fun login(body: LoginRequest): Response<LoginResponse> =
            Response.error(500, "".toResponseBody())
        override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> =
            Response.error(500, "".toResponseBody())
        override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> =
            Response.success(SuccessResponse(success = SuccessResponse.Success.`true`))
        override suspend fun me(): Response<ProfileResponse> =
            Response.success(ProfileResponse(user = user))
        override suspend fun profile(): Response<ProfileResponse> =
            Response.success(ProfileResponse(user = user))
        override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> =
            Response.success(ProfileResponse(user = user))
        override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> =
            Response.success(SuccessResponse(success = SuccessResponse.Success.`true`))
        override suspend fun setupStatus(): Response<SetupStatusResponse> =
            Response.error(500, "".toResponseBody())
        override suspend fun setup(body: SetupRequest): Response<SetupResponse> =
            Response.error(500, "".toResponseBody())
        override suspend fun register(body: RegisterRequest): Response<RegisterResponse> =
            error("unused")
    }

    private fun createAuthRepository(
        role: String = RolePolicy.ADMIN,
        userId: Int = 42,
    ): AuthRepository {
        val tokenStore = FakeTokenStore(role = role)
        val authApi = FakeAuthApi(
            user = ProfileResponseUser(
                id = userId,
                username = "test_user",
                displayName = "Test User",
                role = role,
            ),
        )
        return AuthRepository(authApi, tokenStore, Json { ignoreUnknownKeys = true })
    }

    @Test
    fun `init loads time slots from repository for administrator`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.isAdmin)
        assertFalse(state.isError)
        assertEquals(FakeAdminApi.defaultTimeSlots().sorted(), state.timeSlots)
    }

    @Test
    fun `init restricts access and sets error for worker role`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.WORKER)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertFalse(state.isAdmin)
        assertTrue(state.isError)
        assertEquals("Acesso restrito a administradores.", state.message)
        assertTrue(state.timeSlots.isEmpty())
    }

    @Test
    fun `openAddForm and closeForm manage form state`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        assertNull(vm.uiState.value.form)

        vm.openAddForm()
        assertNotNull(vm.uiState.value.form)
        assertEquals("", vm.uiState.value.form?.slotInput)

        vm.closeForm()
        assertNull(vm.uiState.value.form)
    }

    @Test
    fun `onSlotInputChanged updates input and clears validation error`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        vm.openAddForm()
        vm.addTimeSlot() // Triggers empty validation error
        assertNotNull(vm.uiState.value.form?.validationError)

        vm.onSlotInputChanged("08:00")
        assertEquals("08:00", vm.uiState.value.form?.slotInput)
        assertNull(vm.uiState.value.form?.validationError)
    }

    @Test
    fun `addTimeSlot validates empty input`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        vm.openAddForm()
        vm.onSlotInputChanged("   ")
        vm.addTimeSlot()

        assertEquals("Horário é obrigatório.", vm.uiState.value.form?.validationError)
        assertTrue(api.updatedTimeSlotsRequests.isEmpty())
    }

    @Test
    fun `addTimeSlot validates invalid format`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        vm.openAddForm()
        vm.onSlotInputChanged("25:00")
        vm.addTimeSlot()

        assertEquals("Formato inválido. Use HH:MM (ex: 08:00).", vm.uiState.value.form?.validationError)
        assertTrue(api.updatedTimeSlotsRequests.isEmpty())

        vm.onSlotInputChanged("12:60")
        vm.addTimeSlot()
        assertEquals("Formato inválido. Use HH:MM (ex: 08:00).", vm.uiState.value.form?.validationError)

        vm.onSlotInputChanged("invalid")
        vm.addTimeSlot()
        assertEquals("Formato inválido. Use HH:MM (ex: 08:00).", vm.uiState.value.form?.validationError)
    }

    @Test
    fun `addTimeSlot normalizes single-digit hour and sorts ascending`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        vm.openAddForm()
        vm.onSlotInputChanged("8:00")
        vm.addTimeSlot()

        val state = vm.uiState.value
        assertNull(state.form)
        assertFalse(state.isError)
        assertEquals("Horário adicionado com sucesso.", state.message)
        assertTrue(state.timeSlots.contains("08:00"))
        assertEquals(state.timeSlots, state.timeSlots.sorted())
        assertEquals(listOf("08:00"), api.updatedTimeSlotsRequests.last().timeSlots.filter { it == "08:00" })
    }

    @Test
    fun `addTimeSlot prevents duplicate slots`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        vm.openAddForm()
        vm.onSlotInputChanged("04:00") // 04:00 already in default slots
        vm.addTimeSlot()

        assertEquals("Horário já cadastrado.", vm.uiState.value.form?.validationError)
        assertTrue(api.updatedTimeSlotsRequests.isEmpty())
    }

    @Test
    fun `addTimeSlot handles repository refusal`() {
        val failingApi = FakeAdminApi(
            updateTimeSlotsResponse = Response.error(400, "{\"error\":\"Refused\"}".toResponseBody(null)),
        )
        val repo = TimeSlotsAdminRepository(failingApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        vm.openAddForm()
        vm.onSlotInputChanged("08:00")
        vm.addTimeSlot()

        val state = vm.uiState.value
        assertNotNull(state.form)
        assertFalse(state.form!!.saving)
        assertTrue(state.isError)
        assertEquals("Não foi possível salvar os horários (400).", state.message)
    }

    @Test
    fun `addTimeSlot handles unreachable network`() {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<com.trindade.app.contract.models.TimeSlotsResponse> {
                throw IOException("Network offline")
            }
        }
        val repo = TimeSlotsAdminRepository(throwingApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        vm.openAddForm()
        vm.onSlotInputChanged("08:00")
        vm.addTimeSlot()

        val state = vm.uiState.value
        assertNotNull(state.form)
        assertFalse(state.form!!.saving)
        assertTrue(state.isError)
        assertEquals("Sem conexão com o servidor. Tente de novo.", state.message)
    }

    @Test
    fun `removeTimeSlot prevents removing last slot`() {
        val singleSlotApi = FakeAdminApi(
            timeSlotsToReturn = listOf("08:00"),
        )
        val repo = TimeSlotsAdminRepository(singleSlotApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        assertEquals(1, vm.uiState.value.timeSlots.size)
        assertFalse(vm.uiState.value.canRemove)

        vm.removeTimeSlot("08:00")

        val state = vm.uiState.value
        assertTrue(state.isError)
        assertEquals("É necessário manter pelo menos um horário.", state.message)
        assertEquals(listOf("08:00"), state.timeSlots)
        assertTrue(singleSlotApi.updatedTimeSlotsRequests.isEmpty())
    }

    @Test
    fun `removeTimeSlot atomically replaces set on server`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        assertTrue(vm.uiState.value.timeSlots.contains("04:00"))

        vm.removeTimeSlot("04:00")

        val state = vm.uiState.value
        assertFalse(state.isError)
        assertEquals("Horário removido com sucesso.", state.message)
        assertFalse(state.timeSlots.contains("04:00"))
        assertEquals(FakeAdminApi.defaultTimeSlots().size - 1, state.timeSlots.size)
        assertEquals(1, api.updatedTimeSlotsRequests.size)
        assertFalse(api.updatedTimeSlotsRequests.first().timeSlots.contains("04:00"))
    }

    @Test
    fun `requestDelete, cancelDelete, and confirmDelete manage dialog flow`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        assertNull(vm.uiState.value.deleteConfirmSlot)

        vm.requestDelete("04:00")
        assertEquals("04:00", vm.uiState.value.deleteConfirmSlot)

        vm.cancelDelete()
        assertNull(vm.uiState.value.deleteConfirmSlot)

        vm.requestDelete("04:00")
        vm.confirmDelete()
        assertNull(vm.uiState.value.deleteConfirmSlot)
        assertFalse(vm.uiState.value.timeSlots.contains("04:00"))
    }

    @Test
    fun `handleBack dismisses confirmation dialog or form`() {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = TimeSlotsAdminViewModel(repo, auth)

        // Neither form nor dialog open
        assertFalse(vm.handleBack())

        // Form open
        vm.openAddForm()
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.form)

        // Dialog open
        vm.requestDelete("04:00")
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.deleteConfirmSlot)
    }
}
