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
import kotlinx.serialization.json.JsonNull
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
        assertEquals(DriversViewModel.NAME.resId, model.state.value.error?.resId)
        assertEquals(0, api.creates)

        model.onNameChange(" Ana ")
        model.onDriverTypeChange("casa")
        model.save()
        assertEquals("Ana", api.lastCreate?.name)
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
        model.save()
        assertEquals("Updated", api.lastUpdate?.name)
        model.toggle(driver(8, 42, "casa", active = 1))
        assertEquals(UpdateAdminDriverRequest.IsActive._0, api.lastUpdate?.isActive)
    }

    @Test fun `a saved driver never carries a plate, because the driver form does not own that field`() {
        val api = DriverApi(rows = listOf(driver(8, 42, licensePlate = "ABC1234")))
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.edit(model.state.value.drivers[0])
        model.onNameChange("Updated")
        model.save()
        assertEquals("Updated", api.lastUpdate?.name)

        // The plate belongs to vehicles; a key left out is the endpoint's "leave the stored plate alone".
        val body = api.lastUpdateBody
        assertNotNull(body)
        assertFalse("The driver form must not send a plate", body!!.containsKey("license_plate"))
    }

    @Test fun `saving a driver clears the phone from form state so the next driver does not inherit it`() {
        val api = DriverApi()
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.onNameChange("Driver With Phone")
        model.onPhoneChange("+55 (11) 98888-7777")
        api.writeResult = DriverWriteResult.Saved(driver(9, 42, phone = "+55 (11) 98888-7777"))
        model.save()

        assertEquals("", model.state.value.phone)
        assertEquals("", model.state.value.name)
        assertNull(model.state.value.editingId)
    }

    @Test fun `editing a driver carries their number into the form and typing keeps only number characters`() {
        val api = DriverApi(rows = listOf(driver(8, 42, phone = "+55 (11) 98888-7777")))
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.edit(model.state.value.drivers[0])
        assertEquals("+55 (11) 98888-7777", model.state.value.phone)

        model.onPhoneChange("+55 (11) 98888-7777 ramal")
        assertEquals("+55 (11) 98888-7777 ", model.state.value.phone)

        // Closing the form does not leave the last driver's number behind for the next one.
        model.cancelEdit()
        assertEquals("", model.state.value.phone)
    }

    @Test fun `a blank number on a new driver is omitted, which is what optional means here`() {
        val api = DriverApi()
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.onNameChange("New Driver")
        model.onPhoneChange("   ")
        model.save()
        assertEquals("New Driver", api.lastCreate?.name)
        assertNull("A number nobody has yet must be absent, not an empty string", api.lastCreate?.phone)
    }

    @Test fun `a number typed on a new driver is saved cleaned`() {
        val api = DriverApi()
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.onNameChange("New Driver")
        model.onPhoneChange("  +55 (11) 98888-7777  ")
        model.save()
        assertEquals("+55 (11) 98888-7777", api.lastCreate?.phone)
    }

    @Test fun `emptying the number on an edit reaches the wire as an explicit null, not as a missing key`() {
        val api = DriverApi(rows = listOf(driver(8, 42, phone = "+55 (11) 98888-7777")))
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.edit(model.state.value.drivers[0])
        model.onPhoneChange("")
        model.save()

        val body = api.lastUpdateBody
        assertNotNull(body)
        assertTrue(
            "An emptied field asks for removal: the endpoint reads a missing key as leave it alone",
            body!!.containsKey("phone"),
        )
        assertTrue("The removal travels as JSON null", body["phone"] is JsonNull)
    }

    @Test fun `refused and unreachable writes differ and successful writes reload`() {
        val api = DriverApi()
        val model = viewModel(api)
        model.load()
        model.onNameChange("Ana")
        api.writeResult = DriverWriteResult.Refused(403)
        model.save()
        assertEquals(403, model.state.value.refusedStatus)
        assertEquals(DriversViewModel.REFUSED.resId, model.state.value.error?.resId)
        assertEquals(1, api.reads)
        api.writeResult = DriverWriteResult.Unreachable
        model.save()
        assertEquals(DriversViewModel.UNREACHABLE.resId, model.state.value.error?.resId)
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

    @Test fun `failed profile or catalog reload clears role identity and fail-closes permissions`() {
        val row = driver(1, 42, "casa")
        val api = DriverApi(rows = listOf(row))
        val profileApi = DriverProfileApi("Administrador")
        val model = DriversViewModel(DriversRepository(api), AuthRepository(profileApi, DriverStore(), Json))

        model.load()
        assertEquals("Administrador", model.state.value.role)
        assertEquals(42, model.state.value.currentUserId)
        assertTrue(model.state.value.isAdmin)
        assertTrue(model.state.value.canEdit(row))
        assertTrue(model.state.value.canToggle(row))

        profileApi.failProfile = true
        model.load()
        assertEquals(DriversViewModel.UNREACHABLE.resId, model.state.value.error?.resId)
        assertNull(model.state.value.role)
        assertNull(model.state.value.currentUserId)
        assertFalse(model.state.value.isAdmin)
        assertFalse(model.state.value.canEdit(row))
        assertFalse(model.state.value.canToggle(row))

        profileApi.failProfile = false
        model.load()
        assertEquals("Administrador", model.state.value.role)
        assertTrue(model.state.value.isAdmin)

        api.failDrivers = true
        model.load()
        assertEquals(DriversViewModel.UNREACHABLE.resId, model.state.value.error?.resId)
        assertNull(model.state.value.role)
        assertNull(model.state.value.currentUserId)
        assertFalse(model.state.value.isAdmin)
        assertFalse(model.state.value.canEdit(row))
        assertFalse(model.state.value.canToggle(row))
    }

    @Test fun `in-flight read blocks edit and toggle mutations`() {
        val row = driver(1, 42, "casa")
        val api = DriverApi(rows = listOf(row))
        val profileApi = DriverProfileApi("Administrador")
        val model = DriversViewModel(DriversRepository(api), AuthRepository(profileApi, DriverStore(), Json))

        model.load()
        assertEquals("Administrador", model.state.value.role)

        val deferred = CompletableDeferred<List<AdminDriverResponseDriver>>()
        api.deferredRead = deferred
        model.load()
        assertTrue(model.state.value.loading)

        model.edit(row)
        assertNull(model.state.value.editingId)

        model.toggle(row)
        assertEquals(0, api.updates)

        model.onNameChange("Nova")
        model.save()
        assertEquals(0, api.creates)
        assertEquals(0, api.updates)

        deferred.complete(listOf(row))
        assertFalse(model.state.value.loading)
    }

    private fun viewModel(api: DriverApi, role: String = "Trabalhador") = DriversViewModel(
        DriversRepository(api), AuthRepository(DriverProfileApi(role), DriverStore(), Json),
    )

    private fun driver(
        id: Int,
        owner: Int,
        type: String = "fletero",
        active: Int = 1,
        phone: String? = null,
        licensePlate: String? = null,
    ) = AdminDriverResponseDriver(id, "Driver$id", licensePlate, phone,
        if (type == "casa") AdminDriverResponseDriver.DriverType.casa else AdminDriverResponseDriver.DriverType.fletero,
        active, owner, "now")
}

private class DriverApi(var rows: List<AdminDriverResponseDriver> = emptyList()) : AdminApi {
    var reads = 0
    var creates = 0
    var updates = 0
    var lastCreate: CreateAdminDriverRequest? = null
    var lastUpdate: UpdateAdminDriverRequest? = null

    /** The body as it went out, so a test can tell an explicit null apart from a key nobody sent. */
    var lastUpdateBody: JsonObject? = null
    var firstRead: CompletableDeferred<List<AdminDriverResponseDriver>>? = null
    var deferredRead: CompletableDeferred<List<AdminDriverResponseDriver>>? = null
    var failDrivers = false
    var writeResult: DriverWriteResult = DriverWriteResult.Saved(
        AdminDriverResponseDriver(5, "Ana", null, null, AdminDriverResponseDriver.DriverType.fletero, 1, 42, "now"),
    )
    override suspend fun drivers(): Response<AdminDriversResponse> {
        reads++
        if (failDrivers) throw java.io.IOException("failed to read drivers")
        val deferred = deferredRead
        val result = if (deferred != null) deferred.await() else if (reads == 1) firstRead?.await() else null
        return Response.success(AdminDriversResponse(result ?: rows))
    }
    override suspend fun createDriver(body: CreateAdminDriverRequest): Response<AdminDriverResponse> {
        creates++; lastCreate = body
        return response()
    }
    override suspend fun updateDriver(id: Int, body: JsonObject): Response<AdminDriverResponse> {
        updates++
        lastUpdateBody = body
        lastUpdate = UpdateAdminDriverRequest(
            name = body["name"]?.jsonPrimitive?.content,
            phone = body["phone"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
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
    override suspend fun timeSlots(): Response<TimeSlotsResponse> = error("time-slots API is not used by driver tests")
    override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse> = error("time-slots API is not used by driver tests")
    override suspend fun users(): Response<AdminUsersResponse> = error("user API is not used by driver tests")
    override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> = error("user API is not used by driver tests")
    override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> = error("user API is not used by driver tests")
    override suspend fun deleteUser(id: Int): Response<Unit> = error("user API is not used by driver tests")
}

private class DriverProfileApi(role: String) : AuthApi {
    private val data = ProfileResponse(user = ProfileResponseUser(42, "ana", "Ana", role))
    var failProfile = false
    override suspend fun profile(): Response<ProfileResponse> =
        if (failProfile) Response.error(500, okhttp3.ResponseBody.create(null, "")) else Response.success(data)
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
