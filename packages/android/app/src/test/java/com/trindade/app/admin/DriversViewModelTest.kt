package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.CreateAdminDriverRequest
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
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.AuthApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
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

class DriversViewModelTest {

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
    fun `initial load populates drivers and operator role`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)

        val viewModel = DriversViewModel(repository, authRepository)
        val state = viewModel.uiState.value

        assertFalse(state.loading)
        assertTrue(state.isAdmin)
        assertEquals(3, state.drivers.size)
        assertEquals(1, state.currentUserId)
    }

    @Test
    fun `worker permissions permit editing only own fletero drivers`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val workerId = 10
        val authRepository = createAuthRepository(role = RolePolicy.WORKER, userId = workerId)

        val viewModel = DriversViewModel(repository, authRepository)
        val state = viewModel.uiState.value

        val ownFletero = AdminDriverResponseDriver(
            id = 101,
            name = "Meu Fletero",
            licensePlate = null,
            driverType = AdminDriverResponseDriver.DriverType.fletero,
            isActive = 1,
            createdByUserId = workerId,
            createdAt = "2026-09-22T00:00:00Z",
        )

        val otherFletero = AdminDriverResponseDriver(
            id = 102,
            name = "Outro Fletero",
            licensePlate = null,
            driverType = AdminDriverResponseDriver.DriverType.fletero,
            isActive = 1,
            createdByUserId = 999,
            createdAt = "2026-09-22T00:00:00Z",
        )

        val casaDriver = AdminDriverResponseDriver(
            id = 103,
            name = "Motorista Casa",
            licensePlate = "ABC-1234",
            driverType = AdminDriverResponseDriver.DriverType.casa,
            isActive = 1,
            createdByUserId = workerId,
            createdAt = "2026-09-22T00:00:00Z",
        )

        assertTrue(state.canEdit(ownFletero))
        assertFalse(state.canEdit(otherFletero))
        assertFalse(state.canEdit(casaDriver))
        assertFalse(state.canToggleActive(ownFletero))
        assertFalse(state.canToggleActive(otherFletero))
        assertFalse(state.canToggleActive(casaDriver))
    }

    @Test
    fun `admin can edit any driver and toggle active status`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)

        val viewModel = DriversViewModel(repository, authRepository)
        val state = viewModel.uiState.value

        val anyDriver = AdminDriverResponseDriver(
            id = 102,
            name = "Qualquer Motorista",
            licensePlate = null,
            driverType = AdminDriverResponseDriver.DriverType.casa,
            isActive = 1,
            createdByUserId = 999,
            createdAt = "2026-09-22T00:00:00Z",
        )

        assertTrue(state.canEdit(anyDriver))
        assertTrue(state.canToggleActive(anyDriver))
    }

    @Test
    fun `openCreateForm initializes form and saveDriver creates driver successfully`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = DriversViewModel(repository, authRepository)
        viewModel.openCreateForm()

        assertNotNull(viewModel.uiState.value.form)
        assertEquals(DriversViewModel.DriverTypeChoice.FLETERO, viewModel.uiState.value.form?.driverType)

        viewModel.onNameChanged("Novo Fletero")
        viewModel.saveDriver()

        assertNull(viewModel.uiState.value.form)
        assertEquals("Motorista criado com sucesso.", viewModel.uiState.value.message)
        assertEquals(4, viewModel.uiState.value.drivers.size)
        assertEquals(1, fakeApi.createdDriverRequests.size)
        assertEquals("Novo Fletero", fakeApi.createdDriverRequests.first().name)
        assertEquals(CreateAdminDriverRequest.DriverType.fletero, fakeApi.createdDriverRequests.first().driverType)
    }

    @Test
    fun `admin can select casa type and saveDriver sends casa`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN)

        val viewModel = DriversViewModel(repository, authRepository)
        viewModel.openCreateForm()
        viewModel.onNameChanged("Motorista da Casa")
        viewModel.onDriverTypeChanged(DriversViewModel.DriverTypeChoice.CASA)
        viewModel.saveDriver()

        assertEquals(1, fakeApi.createdDriverRequests.size)
        assertEquals(CreateAdminDriverRequest.DriverType.casa, fakeApi.createdDriverRequests.first().driverType)
    }

    @Test
    fun `worker cannot change driver type to casa`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.WORKER, userId = 10)

        val viewModel = DriversViewModel(repository, authRepository)
        viewModel.openCreateForm()
        viewModel.onNameChanged("Fletero Trabalhador")
        viewModel.onDriverTypeChanged(DriversViewModel.DriverTypeChoice.CASA)

        assertEquals(DriversViewModel.DriverTypeChoice.FLETERO, viewModel.uiState.value.form?.driverType)

        viewModel.saveDriver()

        assertEquals(1, fakeApi.createdDriverRequests.size)
        assertEquals(CreateAdminDriverRequest.DriverType.fletero, fakeApi.createdDriverRequests.first().driverType)
    }

    @Test
    fun `openEditForm populates fields and saveDriver updates driver`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN)

        val viewModel = DriversViewModel(repository, authRepository)
        val existing = viewModel.uiState.value.drivers.first()

        viewModel.openEditForm(existing)
        assertEquals(existing.id, viewModel.uiState.value.form?.driverId)
        assertEquals(existing.name, viewModel.uiState.value.form?.name)

        viewModel.onNameChanged("Carlos Nome Atualizado")
        viewModel.saveDriver()

        assertNull(viewModel.uiState.value.form)
        assertEquals("Motorista atualizado com sucesso.", viewModel.uiState.value.message)
        assertEquals(1, fakeApi.updatedDriverRequests.size)
        assertEquals("Carlos Nome Atualizado", fakeApi.updatedDriverRequests.first().second.name)
    }

    @Test
    fun `toggleDriverActive calls updateDriver with flipped status`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN)

        val viewModel = DriversViewModel(repository, authRepository)
        val driver = viewModel.uiState.value.drivers.first() // isActive = 1

        viewModel.toggleDriverActive(driver)

        assertEquals(1, fakeApi.updatedDriverRequests.size)
        assertEquals(UpdateAdminDriverRequest.IsActive._0, fakeApi.updatedDriverRequests.first().second.isActive)
    }

    @Test
    fun `worker cannot toggle driver active status`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.WORKER, userId = 42)

        val viewModel = DriversViewModel(repository, authRepository)
        val driver = viewModel.uiState.value.drivers.first()

        viewModel.toggleDriverActive(driver)
        assertEquals(0, fakeApi.updatedDriverRequests.size)
    }

    @Test
    fun `closeForm cancels form and restores null form state`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = DriversViewModel(repository, authRepository)
        viewModel.openCreateForm()
        assertNotNull(viewModel.uiState.value.form)

        viewModel.closeForm()
        assertNull(viewModel.uiState.value.form)
    }

    @Test
    fun `closeForm does not cancel form during in-flight save mutation`() = runTest {
        val deferred = CompletableDeferred<Response<AdminDriverResponse>>()
        val fakeApi = object : AdminApi by FakeAdminApi() {
            override suspend fun createDriver(body: CreateAdminDriverRequest): Response<AdminDriverResponse> {
                return deferred.await()
            }
        }
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository()
        val viewModel = DriversViewModel(repository, authRepository)

        viewModel.openCreateForm()
        viewModel.onNameChanged("Novo Motorista")
        viewModel.saveDriver()

        // Saving is in flight
        assertTrue(viewModel.uiState.value.form?.saving == true)

        // Attempt to close form while saving
        viewModel.closeForm()
        assertNotNull(viewModel.uiState.value.form)
        assertTrue(viewModel.uiState.value.form?.saving == true)

        // Complete save
        deferred.complete(
            Response.success(
                AdminDriverResponse(
                    driver = AdminDriverResponseDriver(
                        id = 99,
                        name = "Novo Motorista",
                        licensePlate = null,
                        driverType = AdminDriverResponseDriver.DriverType.fletero,
                        isActive = 1,
                        createdByUserId = 42,
                        createdAt = "2026-09-22T10:00:00.000Z",
                    ),
                ),
            ),
        )

        assertNull(viewModel.uiState.value.form)
    }

    @Test
    fun `handleBack consumes form before returning false at root`() {
        val fakeApi = FakeAdminApi()
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = DriversViewModel(repository, authRepository)
        assertFalse(viewModel.handleBack())

        viewModel.openCreateForm()
        assertNotNull(viewModel.uiState.value.form)

        assertTrue(viewModel.handleBack())
        assertNull(viewModel.uiState.value.form)

        assertFalse(viewModel.handleBack())
    }

    @Test
    fun `saveDriver handles refused and unreachable responses`() {
        val fakeApi = FakeAdminApi(
            createDriverResponse = Response.error(400, "{\"error\":\"Invalid input\"}".toResponseBody("application/json".toMediaType())),
        )
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = DriversViewModel(repository, authRepository)
        viewModel.openCreateForm()
        viewModel.onNameChanged("Motorista")
        viewModel.saveDriver()

        assertTrue(viewModel.uiState.value.isError)
        assertEquals("Não foi possível criar o motorista (400).", viewModel.uiState.value.message)
    }

    @Test
    fun `toggleDriverActive handles refusal response`() {
        val fakeApi = FakeAdminApi(
            updateDriverResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody("application/json".toMediaType())),
        )
        val repository = DriversRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN)

        val viewModel = DriversViewModel(repository, authRepository)
        val driver = viewModel.uiState.value.drivers.first()

        viewModel.toggleDriverActive(driver)

        assertTrue(viewModel.uiState.value.isError)
        assertEquals("Não foi possível alterar o status do motorista.", viewModel.uiState.value.message)
    }
}
