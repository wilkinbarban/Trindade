package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.*
import com.trindade.app.network.AdminApi
import com.trindade.app.network.AuthApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class DriversViewModelTest {
    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun `loads drivers and authenticated identity then applies driver edit and toggle policy`() {
        val api = DriverApi(rows = listOf(driver(1, 42), driver(2, 99, "casa")))
        val model = viewModel(api)
        model.load()
        val own = model.state.value.drivers[0]
        val other = model.state.value.drivers[1]
        val ownCasa = driver(3, 42, "casa")
        assertEquals(42, model.state.value.currentUserId)
        assertTrue(model.state.value.canEdit(own))
        assertFalse(model.state.value.canEdit(other))
        assertFalse(model.state.value.canEdit(ownCasa))
        assertFalse(model.state.value.canToggle(own))
        model.edit(other)
        model.toggle(own)
        assertNull(model.state.value.editingId)
        assertEquals(0, api.updates)

        val adminApi = DriverApi(rows = listOf(own, other))
        val admin = viewModel(adminApi, role = "Administrador")
        admin.load()
        assertTrue(admin.state.value.canEdit(admin.state.value.drivers[0]))
        assertTrue(admin.state.value.canEdit(admin.state.value.drivers[1]))
        assertTrue(admin.state.value.canToggle(admin.state.value.drivers[0]))
        admin.toggle(admin.state.value.drivers[0])
        assertEquals(UpdateAdminDriverRequest.IsActive._0, adminApi.lastUpdate?.isActive)
    }

    @Test fun `worker creates only fletero and name must be nonblank`() {
        val api = DriverApi()
        val model = viewModel(api)
        model.load()
        model.onNameChange("   ")
        model.save()
        assertEquals(DriversViewModel.NAME, model.state.value.error)
        assertEquals(0, api.creates)

        model.onNameChange(" Ana ")
        model.onLicensePlateChange(" ")
        model.onDriverTypeChange("casa")
        model.save()
        assertEquals("Ana", api.lastCreate?.name)
        assertNull(api.lastCreate?.licensePlate)
        assertEquals(CreateAdminDriverRequest.DriverType.fletero, api.lastCreate?.driverType)
    }

    @Test fun `unknown role cannot write and invalid administrator type is rejected`() {
        val unknownApi = DriverApi()
        val unknown = viewModel(unknownApi, role = "Unknown")
        unknown.load()
        unknown.onNameChange("Ana")
        unknown.save()
        assertEquals(0, unknownApi.creates)

        val adminApi = DriverApi()
        val admin = viewModel(adminApi, role = "Administrador")
        admin.load()
        admin.onNameChange("Casa")
        admin.onDriverTypeChange("invalid")
        admin.save()
        assertNotNull(admin.state.value.error)
        assertEquals(0, adminApi.creates)
    }

    @Test fun `administrator may create casa edit fields and toggle active`() {
        val api = DriverApi()
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.onNameChange("Casa")
        model.onDriverTypeChange("casa")
        model.save()
        assertEquals(CreateAdminDriverRequest.DriverType.casa, api.lastCreate?.driverType)

        model.edit(driver(8, 42, "casa"))
        model.onNameChange("Updated")
        model.onLicensePlateChange("ABC")
        model.save()
        assertEquals("Updated", api.lastUpdate?.name)
        assertEquals("ABC", api.lastUpdate?.licensePlate)
        model.toggle(driver(8, 42, "casa", active = 1))
        assertEquals(UpdateAdminDriverRequest.IsActive._0, api.lastUpdate?.isActive)
    }

    @Test fun `refused and unreachable writes differ and successful writes reload`() {
        val api = DriverApi()
        val model = viewModel(api)
        model.load()
        model.onNameChange("Ana")
        api.writeResult = DriverWriteResult.Refused(403)
        model.save()
        assertEquals(403, model.state.value.refusedStatus)
        assertEquals(DriversViewModel.REFUSED, model.state.value.error)
        assertEquals(1, api.reads)
        api.writeResult = DriverWriteResult.Unreachable
        model.save()
        assertEquals(DriversViewModel.UNREACHABLE, model.state.value.error)
        assertEquals(1, api.reads)
        api.writeResult = DriverWriteResult.Saved(driver(5, 42))
        model.save()
        assertEquals(2, api.reads)
        assertNull(model.state.value.error)
    }

    @Test fun `a newer load cancels an older driver response and cancel clears the form`() {
        val api = DriverApi(rows = listOf(driver(7, 42)))
        api.firstRead = CompletableDeferred()
        val model = viewModel(api)
        model.load()
        model.load()
        assertEquals(2, api.reads)
        assertEquals(7, model.state.value.drivers.single().id)
        api.firstRead!!.complete(listOf(driver(99, 42)))
        assertEquals(7, model.state.value.drivers.single().id)
        model.edit(model.state.value.drivers.single())
        model.cancelEdit()
        assertNull(model.state.value.editingId)
        assertEquals("", model.state.value.name)
    }

    private fun viewModel(api: DriverApi, role: String = "Trabalhador") = DriversViewModel(
        DriversRepository(api), AuthRepository(DriverProfileApi(role), DriverStore(), Json),
    )

    private fun driver(id: Int, owner: Int, type: String = "fletero", active: Int = 1) =
        AdminDriverResponseDriver(id, "Driver$id", null,
            if (type == "casa") AdminDriverResponseDriver.DriverType.casa else AdminDriverResponseDriver.DriverType.fletero,
            active, owner, "now")
}

private class DriverApi(var rows: List<AdminDriverResponseDriver> = emptyList()) : AdminApi {
    var reads = 0
    var creates = 0
    var updates = 0
    var lastCreate: CreateAdminDriverRequest? = null
    var lastUpdate: UpdateAdminDriverRequest? = null
    var firstRead: CompletableDeferred<List<AdminDriverResponseDriver>>? = null
    var writeResult: DriverWriteResult = DriverWriteResult.Saved(
        AdminDriverResponseDriver(5, "Ana", null, AdminDriverResponseDriver.DriverType.fletero, 1, 42, "now"),
    )
    override suspend fun drivers(): Response<AdminDriversResponse> {
        reads++
        val result = if (reads == 1) firstRead?.await() else null
        return Response.success(AdminDriversResponse(result ?: rows))
    }
    override suspend fun createDriver(body: CreateAdminDriverRequest): Response<AdminDriverResponse> {
        creates++; lastCreate = body
        return response()
    }
    override suspend fun updateDriver(id: Int, body: JsonObject): Response<AdminDriverResponse> {
        updates++
        lastUpdate = UpdateAdminDriverRequest(
            name = body["name"]?.jsonPrimitive?.content,
            licensePlate = body["license_plate"]?.jsonPrimitive?.content,
            driverType = body["driver_type"]?.jsonPrimitive?.content?.let(UpdateAdminDriverRequest.DriverType::valueOf),
            isActive = body["is_active"]?.jsonPrimitive?.content?.let {
                if (it == "1") UpdateAdminDriverRequest.IsActive._1 else UpdateAdminDriverRequest.IsActive._0
            },
        )
        return response()
    }
    private fun response(): Response<AdminDriverResponse> = when (val result = writeResult) {
        is DriverWriteResult.Saved -> Response.success(AdminDriverResponse(result.driver))
        is DriverWriteResult.Refused -> Response.error(result.status, okhttp3.ResponseBody.create(null, ""))
        DriverWriteResult.Unreachable -> throw java.io.IOException("offline")
    }
    override suspend fun categories(): Response<AdminCategoriesResponse> = error("unused")
    override suspend fun createCategory(body: CreateAdminCategoryRequest): Response<AdminCategoryResponse> =
        error("category API is not used by driver tests")
    override suspend fun updateCategory(id: Int, body: JsonObject): Response<AdminCategoryResponse> =
        error("category API is not used by driver tests")
    override suspend fun deleteCategory(id: Int): Response<Unit> = error("category API is not used by driver tests")
    override suspend fun tasks(): Response<AdminTasksResponse> = error("unused")
    override suspend fun createTask(body: CreateAdminTaskRequest): Response<AdminTaskResponse> = error("unused")
    override suspend fun updateTask(id: Int, body: JsonObject): Response<AdminTaskResponse> = error("unused")
    override suspend fun deleteTask(id: Int): Response<Unit> = error("drivers have no delete")
    override suspend fun vehicles(): Response<AdminVehiclesResponse> = error("vehicle API is not used by driver tests")
    override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> = error("vehicle API is not used by driver tests")
    override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> = error("vehicle API is not used by driver tests")
    override suspend fun deleteVehicle(id: Int): Response<Unit> = error("vehicle API is not used by driver tests")
}

private class DriverProfileApi(role: String) : AuthApi {
    private val data = ProfileResponse(user = ProfileResponseUser(42, "ana", "Ana", role))
    override suspend fun profile() = Response.success(data)
    override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("unused")
    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("unused")
    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("unused")
    override suspend fun me(): Response<ProfileResponse> = Response.success(data)
    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("unused")
    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("unused")
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error("unused")
    override suspend fun register(body: RegisterRequest): Response<RegisterResponse> = error("unused")
}

private class DriverStore : TokenStore {
    override fun accessToken() = "token"
    override fun refreshToken() = "refresh"
    override fun role() = null
    override fun save(accessToken: String, refreshToken: String) = Unit
    override fun saveRole(role: String) = Unit
    override fun clear() = Unit
}
