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
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AuthApi
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

class VehiclesViewModelTest {

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
    fun `init loads vehicles from repository for administrator`() {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = VehiclesViewModel(repo, auth)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(3, state.vehicles.size)
        assertEquals("Caminhão 01", state.vehicles.first().description)
        assertTrue(state.isAdmin)
    }

    @Test
    fun `init refuses load and sets message for worker`() {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)
        val auth = createAuthRepository(role = RolePolicy.WORKER)
        val vm = VehiclesViewModel(repo, auth)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.vehicles.isEmpty())
        assertFalse(state.isAdmin)
        assertTrue(state.isError)
        assertEquals("Acesso restrito a administradores.", state.message)
    }

    @Test
    fun `openCreateForm initializes form for administrator`() {
        val vm = VehiclesViewModel(VehiclesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        vm.openCreateForm()

        val form = vm.uiState.value.form
        assertNotNull(form)
        assertFalse(form!!.isEditing)
        assertEquals("", form.description)
        assertEquals("", form.licensePlate)
    }

    @Test
    fun `openCreateForm is ignored for worker`() {
        val vm = VehiclesViewModel(VehiclesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.WORKER))
        vm.openCreateForm()

        assertNull(vm.uiState.value.form)
    }

    @Test
    fun `openEditForm seeds existing vehicle values`() {
        val vm = VehiclesViewModel(VehiclesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        val vehicle = FakeAdminApi.defaultVehicles().first()
        vm.openEditForm(vehicle)

        val form = vm.uiState.value.form
        assertNotNull(form)
        assertTrue(form!!.isEditing)
        assertEquals(vehicle.id, form.vehicleId)
        assertEquals("Caminhão 01", form.description)
        assertEquals("ABC-1234", form.licensePlate)
    }

    @Test
    fun `openEditForm is ignored for worker`() {
        val vm = VehiclesViewModel(VehiclesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.WORKER))
        val vehicle = FakeAdminApi.defaultVehicles().first()
        vm.openEditForm(vehicle)

        assertNull(vm.uiState.value.form)
    }

    @Test
    fun `closeForm resets form state`() {
        val vm = VehiclesViewModel(VehiclesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        vm.openCreateForm()
        assertNotNull(vm.uiState.value.form)

        vm.closeForm()
        assertNull(vm.uiState.value.form)
    }

    @Test
    fun `saveVehicle creates vehicle on valid submit`() {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)
        val vm = VehiclesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))

        vm.openCreateForm()
        vm.onDescriptionChanged("Van Nova")
        vm.onLicensePlateChanged("XYZ-1234")

        vm.saveVehicle()

        assertNull(vm.uiState.value.form)
        assertEquals("Veículo criado com sucesso.", vm.uiState.value.message)
        assertFalse(vm.uiState.value.isError)
        assertEquals(1, api.createdVehicleRequests.size)
        assertEquals("Van Nova", api.createdVehicleRequests.first().description)
        assertEquals("XYZ-1234", api.createdVehicleRequests.first().licensePlate)
    }

    @Test
    fun `saveVehicle updates vehicle when editing`() {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)
        val vm = VehiclesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))
        val vehicle = FakeAdminApi.defaultVehicles().first()

        vm.openEditForm(vehicle)
        vm.onDescriptionChanged("Caminhão Atualizado")
        vm.onLicensePlateChanged("ABC-9999")
        vm.saveVehicle()

        assertNull(vm.uiState.value.form)
        assertEquals("Veículo atualizado com sucesso.", vm.uiState.value.message)
        assertEquals(1, api.updatedVehicleRequests.size)
        assertEquals("Caminhão Atualizado", api.updatedVehicleRequests.first().second.description)
        assertEquals("ABC-9999", api.updatedVehicleRequests.first().second.licensePlate)
    }

    @Test
    fun `saveVehicle is ignored for worker`() {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)
        val vm = VehiclesViewModel(repo, createAuthRepository(role = RolePolicy.WORKER))

        vm.saveVehicle()
        assertEquals(0, api.createdVehicleRequests.size)
        assertEquals(0, api.updatedVehicleRequests.size)
    }

    @Test
    fun `toggleVehicleActive flips active status`() {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)
        val vm = VehiclesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))
        val vehicle = FakeAdminApi.defaultVehicles().first() // isActive = 1

        vm.toggleVehicleActive(vehicle)

        assertEquals(1, api.updatedVehicleRequests.size)
        assertEquals(
            UpdateAdminVehicleRequest.IsActive._0,
            api.updatedVehicleRequests.first().second.isActive,
        )
    }

    @Test
    fun `toggleVehicleActive is ignored for worker`() {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)
        val vm = VehiclesViewModel(repo, createAuthRepository(role = RolePolicy.WORKER))
        val vehicle = FakeAdminApi.defaultVehicles().first()

        vm.toggleVehicleActive(vehicle)
        assertEquals(0, api.updatedVehicleRequests.size)
    }

    @Test
    fun `requestDelete and cancelDelete manage confirmation state`() {
        val vm = VehiclesViewModel(VehiclesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        val vehicle = FakeAdminApi.defaultVehicles().first()

        vm.requestDelete(vehicle)
        assertEquals(vehicle, vm.uiState.value.deleteConfirmVehicle)

        vm.cancelDelete()
        assertNull(vm.uiState.value.deleteConfirmVehicle)
    }

    @Test
    fun `requestDelete is ignored for worker`() {
        val vm = VehiclesViewModel(VehiclesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.WORKER))
        val vehicle = FakeAdminApi.defaultVehicles().first()

        vm.requestDelete(vehicle)
        assertNull(vm.uiState.value.deleteConfirmVehicle)
    }

    @Test
    fun `confirmDelete deletes vehicle and displays success message`() {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)
        val vm = VehiclesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))
        val vehicle = FakeAdminApi.defaultVehicles().first()

        vm.requestDelete(vehicle)
        vm.confirmDelete()

        assertNull(vm.uiState.value.deleteConfirmVehicle)
        assertEquals("Veículo excluído com sucesso.", vm.uiState.value.message)
        assertFalse(vm.uiState.value.isError)
        assertEquals(listOf(vehicle.id), api.deletedVehicleIds)
    }

    @Test
    fun `confirmDelete surfaces 400 foreign key constraint refusal message`() {
        val api = FakeAdminApi(
            deleteVehicleResponse = Response.error(400, "{\"error\":\"FK constraint\"}".toResponseBody(null)),
        )
        val repo = VehiclesRepository(api)
        val vm = VehiclesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))
        val vehicle = FakeAdminApi.defaultVehicles().first()

        vm.requestDelete(vehicle)
        vm.confirmDelete()

        assertNull(vm.uiState.value.deleteConfirmVehicle)
        assertTrue(vm.uiState.value.isError)
        assertEquals(
            "Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo.",
            vm.uiState.value.message,
        )
    }

    @Test
    fun `handleBack dismisses delete dialog first then form`() {
        val vm = VehiclesViewModel(VehiclesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        val vehicle = FakeAdminApi.defaultVehicles().first()

        vm.openCreateForm()
        vm.requestDelete(vehicle)

        // First back dismisses delete dialog
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.deleteConfirmVehicle)
        assertNotNull(vm.uiState.value.form)

        // Second back dismisses form
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.form)

        // Third back returns false (navigates screen)
        assertFalse(vm.handleBack())
    }

    @Test
    fun `clearMessage clears error or status message`() {
        val api = FakeAdminApi(vehiclesToReturn = null)
        val repo = VehiclesRepository(api)
        val vm = VehiclesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))

        assertNotNull(vm.uiState.value.message)
        vm.clearMessage()
        assertNull(vm.uiState.value.message)
    }
}
