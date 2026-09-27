package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.AdminCategoriesResponse
import com.trindade.app.contract.models.AdminCategoryResponse
import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriversResponse
import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.contract.models.AdminTasksResponse
import com.trindade.app.contract.models.AdminUserResponse
import com.trindade.app.contract.models.AdminUsersResponse
import com.trindade.app.contract.models.AdminVehicleResponse
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.contract.models.AdminVehiclesResponse
import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponse
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.RefreshResponse
import com.trindade.app.contract.models.RegisterRequest
import com.trindade.app.contract.models.RegisterResponse
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.contract.models.UpdateProfileRequest
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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VehiclesViewModelTest {
    private val fixtureVehicles = listOf(
        vehicle(1, "Van Entrega", "VAN-001", 1),
        vehicle(2, "Caminhão 3/4", "CAM-002", 0),
    )

    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun `loads vehicles and authenticated identity then applies vehicle policy`() {
        val api = VehicleApi(rows = fixtureVehicles)
        val admin = viewModel(api, role = "Administrador")
        admin.load()
        assertEquals(fixtureVehicles, admin.state.value.vehicles)
        assertEquals(42, admin.state.value.currentUserId)
        assertTrue(admin.state.value.isAdmin)
        assertTrue(admin.state.value.canEdit(fixtureVehicles[0]))
        assertTrue(admin.state.value.canToggle(fixtureVehicles[0]))
        assertTrue(admin.state.value.canDelete(fixtureVehicles[0]))

        val worker = viewModel(api, role = "Trabalhador")
        worker.load()
        assertEquals(fixtureVehicles, worker.state.value.vehicles)
        assertFalse(worker.state.value.isAdmin)
        assertFalse(worker.state.value.canEdit(fixtureVehicles[0]))
        assertFalse(worker.state.value.canToggle(fixtureVehicles[0]))
        assertFalse(worker.state.value.canDelete(fixtureVehicles[0]))

        worker.edit(fixtureVehicles[0])
        assertNull(worker.state.value.editingId)
        worker.toggle(fixtureVehicles[0])
        worker.delete(fixtureVehicles[0])
        worker.onDescriptionChange("Proibido")
        worker.onLicensePlateChange("PRO-0000")
        worker.save()
        assertEquals(0, api.creates + api.updates + api.deletes)

        val unknownApi = VehicleApi(rows = fixtureVehicles)
        val unknown = viewModel(unknownApi, role = "UnknownRole")
        unknown.load()
        assertFalse(unknown.state.value.isAdmin)
        unknown.onDescriptionChange("Tentativa")
        unknown.onLicensePlateChange("TEN-0000")
        unknown.save()
        assertEquals(0, unknownApi.creates + unknownApi.updates + unknownApi.deletes)
    }

    @Test fun `create requires nonblank description and nonblank license plate`() {
        val api = VehicleApi()
        val model = viewModel(api, role = "Administrador")
        model.load()

        model.onDescriptionChange("   ")
        model.onLicensePlateChange("ABC-1234")
        model.save()
        assertEquals(VehiclesViewModel.DESCRIPTION, model.state.value.error)
        assertEquals(0, api.creates)

        model.onDescriptionChange("Furgão")
        model.onLicensePlateChange("   ")
        model.save()
        assertEquals(VehiclesViewModel.LICENSE_PLATE, model.state.value.error)
        assertEquals(0, api.creates)

        model.onDescriptionChange("  Furgão Entrega  ")
        model.onLicensePlateChange("  ABC-1234  ")
        model.save()
        assertEquals(1, api.creates)
        assertEquals("Furgão Entrega", api.lastCreate?.description)
        assertEquals("ABC-1234", api.lastCreate?.licensePlate)
        assertNull(model.state.value.error)
    }

    @Test fun `edit loads vehicle values and PATCH may carry either field or both`() {
        val target = vehicle(5, "Furgão", "FUR-123", 1)
        val api = VehicleApi(rows = listOf(target))
        val model = viewModel(api, role = "Administrador")
        model.load()

        model.edit(target)
        assertEquals(5, model.state.value.editingId)
        assertEquals("Furgão", model.state.value.description)
        assertEquals("FUR-123", model.state.value.licensePlate)

        model.onDescriptionChange("Furgão Atualizado")
        model.save()
        assertEquals(1, api.updates)
        assertEquals(5, api.lastUpdateId)
        assertEquals("Furgão Atualizado", api.lastUpdate?.description)
        assertEquals("FUR-123", api.lastUpdate?.licensePlate)
        assertNull(model.state.value.editingId)

        model.edit(target)
        model.onDescriptionChange("Furgão Novo")
        model.onLicensePlateChange("   ")
        model.save()
        assertEquals(2, api.updates)
        assertEquals("Furgão Novo", api.lastUpdate?.description)
        assertNull(api.lastUpdate?.licensePlate)

        model.edit(target)
        model.onDescriptionChange("   ")
        model.onLicensePlateChange("NEW-999")
        model.save()
        assertEquals(3, api.updates)
        assertNull(api.lastUpdate?.description)
        assertEquals("NEW-999", api.lastUpdate?.licensePlate)

        model.edit(target)
        model.onDescriptionChange("   ")
        model.onLicensePlateChange("   ")
        model.save()
        assertEquals(3, api.updates)
        assertEquals(VehiclesViewModel.DESCRIPTION, model.state.value.error)

        model.edit(target)
        model.cancelEdit()
        assertNull(model.state.value.editingId)
        assertEquals("", model.state.value.description)
        assertEquals("", model.state.value.licensePlate)
    }

    @Test fun `toggle sends inverted is_active flag`() {
        val api = VehicleApi(rows = fixtureVehicles)
        val model = viewModel(api, role = "Administrador")
        model.load()

        val activeVeh = fixtureVehicles[0] // isActive = 1
        model.toggle(activeVeh)
        assertEquals(1, api.updates)
        assertEquals(activeVeh.id, api.lastUpdateId)
        assertEquals(UpdateAdminVehicleRequest.IsActive._0, api.lastUpdate?.isActive)

        val inactiveVeh = fixtureVehicles[1] // isActive = 0
        model.toggle(inactiveVeh)
        assertEquals(2, api.updates)
        assertEquals(inactiveVeh.id, api.lastUpdateId)
        assertEquals(UpdateAdminVehicleRequest.IsActive._1, api.lastUpdate?.isActive)
    }

    @Test fun `confirmed delete state workflow request confirm and cancel`() {
        val api = VehicleApi(rows = fixtureVehicles)
        val model = viewModel(api, role = "Administrador")
        model.load()
        val target = fixtureVehicles[0]

        model.requestDelete(target)
        assertEquals(target, model.state.value.deleteTarget)

        model.cancelDelete()
        assertNull(model.state.value.deleteTarget)
        assertEquals(0, api.deletes)

        model.requestDelete(target)
        model.confirmDelete()
        assertEquals(1, api.deletes)
        assertEquals(target.id, api.lastDeleteId)
        assertNull(model.state.value.deleteTarget)

        model.delete(fixtureVehicles[1])
        assertEquals(2, api.deletes)
        assertEquals(fixtureVehicles[1].id, api.lastDeleteId)
    }

    @Test fun `refusals and unreachable writes differ and successful writes reload`() {
        val api = VehicleApi(rows = fixtureVehicles)
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.onDescriptionChange("Van")
        model.onLicensePlateChange("ABC-1234")

        api.writeResult = VehicleWriteResult.Refused(400)
        model.save()
        assertEquals(400, model.state.value.refusedStatus)
        assertEquals(VehiclesViewModel.REFUSED, model.state.value.error)
        assertEquals(1, api.reads)

        api.writeResult = VehicleWriteResult.Unreachable
        model.save()
        assertEquals(VehiclesViewModel.UNREACHABLE, model.state.value.error)
        assertNull(model.state.value.refusedStatus)
        assertEquals(1, api.reads)

        api.writeResult = VehicleWriteResult.Saved(fixtureVehicles[0])
        model.save()
        assertEquals(2, api.reads)
        assertNull(model.state.value.error)
    }

    @Test fun `a newer load cancels an older vehicle response`() {
        val api = VehicleApi(rows = fixtureVehicles)
        api.firstRead = CompletableDeferred()
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.load()
        assertEquals(2, api.reads)
        assertEquals(fixtureVehicles, model.state.value.vehicles)
        api.firstRead!!.complete(listOf(vehicle(99, "Antigo", "OLD-999", 1)))
        assertEquals(fixtureVehicles, model.state.value.vehicles)
    }

    @Test fun `form changes clear errors and refused status`() {
        val api = VehicleApi(rows = fixtureVehicles)
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.onDescriptionChange("Van")
        model.onLicensePlateChange("ABC-1234")

        api.writeResult = VehicleWriteResult.Refused(403)
        model.save()
        assertEquals(403, model.state.value.refusedStatus)
        assertEquals(VehiclesViewModel.REFUSED, model.state.value.error)

        model.onDescriptionChange("Van Atualizada")
        assertNull(model.state.value.error)
        assertNull(model.state.value.refusedStatus)

        model.save()
        assertEquals(403, model.state.value.refusedStatus)

        model.onLicensePlateChange("NEW-1234")
        assertNull(model.state.value.error)
        assertNull(model.state.value.refusedStatus)
    }

    private fun viewModel(
        api: VehicleApi,
        role: String = "Trabalhador",
    ) = VehiclesViewModel(
        VehiclesRepository(api),
        AuthRepository(VehicleProfileApi(role), VehicleStore(), Json),
    )

    private fun vehicle(
        id: Int,
        description: String,
        licensePlate: String,
        active: Int,
    ) = AdminVehicleResponseVehicle(id, description, licensePlate, active, "now")
}

private class VehicleApi(var rows: List<AdminVehicleResponseVehicle> = emptyList()) : AdminApi {
    var reads = 0
    var creates = 0
    var updates = 0
    var deletes = 0
    var lastCreate: CreateAdminVehicleRequest? = null
    var lastUpdate: UpdateAdminVehicleRequest? = null
    var lastUpdateId: Int? = null
    var lastDeleteId: Int? = null
    var firstRead: CompletableDeferred<List<AdminVehicleResponseVehicle>>? = null
    var writeResult: VehicleWriteResult = VehicleWriteResult.Saved(
        AdminVehicleResponseVehicle(5, "Van", "VAN-001", 1, "now"),
    )

    override suspend fun vehicles(): Response<AdminVehiclesResponse> {
        reads++
        val result = if (reads == 1) firstRead?.await() else null
        return Response.success(AdminVehiclesResponse(result ?: rows))
    }

    override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> {
        creates++
        lastCreate = body
        return vehicleResponse()
    }

    override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> {
        updates++
        lastUpdateId = id
        lastUpdate = UpdateAdminVehicleRequest(
            description = body["description"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            licensePlate = body["license_plate"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            isActive = body["is_active"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let {
                if (it == "1") UpdateAdminVehicleRequest.IsActive._1 else UpdateAdminVehicleRequest.IsActive._0
            },
        )
        return vehicleResponse()
    }

    override suspend fun deleteVehicle(id: Int): Response<Unit> {
        deletes++
        lastDeleteId = id
        return when (val result = writeResult) {
            is VehicleWriteResult.Saved -> Response.success(Unit)
            is VehicleWriteResult.Refused -> Response.error(result.status, "".toResponseBody(null))
            VehicleWriteResult.Unreachable -> throw java.io.IOException("offline")
        }
    }

    private fun vehicleResponse(): Response<AdminVehicleResponse> = when (val result = writeResult) {
        is VehicleWriteResult.Saved -> Response.success(AdminVehicleResponse(result.vehicle ?: rows.firstOrNull() ?: AdminVehicleResponseVehicle(1, "A", "B", 1, "now")))
        is VehicleWriteResult.Refused -> Response.error(result.status, "".toResponseBody(null))
        VehicleWriteResult.Unreachable -> throw java.io.IOException("offline")
    }

    override suspend fun categories(): Response<AdminCategoriesResponse> = error("unused")
    override suspend fun createCategory(body: CreateAdminCategoryRequest): Response<AdminCategoryResponse> = error("unused")
    override suspend fun updateCategory(id: Int, body: JsonObject): Response<AdminCategoryResponse> = error("unused")
    override suspend fun deleteCategory(id: Int): Response<Unit> = error("unused")
    override suspend fun drivers(): Response<AdminDriversResponse> = error("unused")
    override suspend fun createDriver(body: CreateAdminDriverRequest): Response<AdminDriverResponse> = error("unused")
    override suspend fun updateDriver(id: Int, body: JsonObject): Response<AdminDriverResponse> = error("unused")
    override suspend fun tasks(): Response<AdminTasksResponse> = error("unused")
    override suspend fun createTask(body: CreateAdminTaskRequest): Response<AdminTaskResponse> = error("unused")
    override suspend fun updateTask(id: Int, body: JsonObject): Response<AdminTaskResponse> = error("unused")
    override suspend fun deleteTask(id: Int): Response<Unit> = error("unused")
    override suspend fun timeSlots(): Response<TimeSlotsResponse> = error("unused")
    override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse> = error("unused")
    override suspend fun users(): Response<AdminUsersResponse> = error("unused")
    override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> = error("unused")
    override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> = error("unused")
    override suspend fun deleteUser(id: Int): Response<Unit> = error("unused")
}

private class VehicleProfileApi(role: String) : AuthApi {
    private val data = ProfileResponse(user = ProfileResponseUser(42, "admin", "Admin", role))
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

private class VehicleStore : TokenStore {
    override fun accessToken() = "token"
    override fun refreshToken() = "refresh"
    override fun role() = null
    override fun save(accessToken: String, refreshToken: String) = Unit
    override fun saveRole(role: String) = Unit
    override fun clear() = Unit
}
