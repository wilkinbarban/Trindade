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
import kotlinx.serialization.json.JsonObject
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
class TimeSlotsViewModelTest {
    private val fixtureSlots = listOf("04:00", "04:30", "05:00")

    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun `loads time slots and authenticated identity then applies admin mutation policy`() {
        val api = TimeSlotApi(slots = fixtureSlots)
        val admin = viewModel(api, role = "Administrador")
        admin.load()
        assertEquals(fixtureSlots, admin.state.value.timeSlots)
        assertEquals(42, admin.state.value.currentUserId)
        assertTrue(admin.state.value.isAdmin)
        assertTrue(admin.state.value.canAdd())
        assertTrue(admin.state.value.canRemove("04:00"))
        assertFalse(admin.state.value.loading)
        assertNull(admin.state.value.error)

        val worker = viewModel(api, role = "Trabalhador")
        worker.load()
        assertEquals(fixtureSlots, worker.state.value.timeSlots)
        assertFalse(worker.state.value.isAdmin)
        assertFalse(worker.state.value.canAdd())
        assertFalse(worker.state.value.canRemove("04:00"))

        worker.onInputChange("06:00")
        worker.addTimeSlot()
        worker.removeTimeSlot("04:00")
        assertEquals(0, api.updates)

        val unknownApi = TimeSlotApi(slots = fixtureSlots)
        val unknown = viewModel(unknownApi, role = "Operador")
        unknown.load()
        assertFalse(unknown.state.value.isAdmin)
        unknown.onInputChange("06:00")
        unknown.addTimeSlot()
        unknown.removeTimeSlot("04:00")
        assertEquals(0, unknownApi.updates)

        val unauthenticated = viewModel(api, role = "Administrador")
        // role is null before load
        assertNull(unauthenticated.state.value.role)
        assertFalse(unauthenticated.state.value.isAdmin)
        unauthenticated.onInputChange("06:00")
        unauthenticated.addTimeSlot()
        unauthenticated.removeTimeSlot("04:00")
        assertEquals(0, api.updates)
    }

    @Test fun `add trims input appends and sorts the whole list immediately calling update`() {
        val api = TimeSlotApi(slots = listOf("08:00", "12:00"))
        val model = viewModel(api, role = "Administrador")
        model.load()

        model.onInputChange("   10:00   ")
        model.addTimeSlot()
        assertEquals(1, api.updates)
        assertEquals(listOf("08:00", "10:00", "12:00"), api.lastUpdate?.timeSlots)
        assertEquals(listOf("08:00", "10:00", "12:00"), model.state.value.timeSlots)
        assertEquals("", model.state.value.input)
        assertFalse(model.state.value.saving)
    }

    @Test fun `add does not invent validation and allows arbitrary strings and duplicates`() {
        val api = TimeSlotApi(slots = listOf("08:00", "10:00"))
        val model = viewModel(api, role = "Administrador")
        model.load()

        model.onInputChange("08:00")
        model.addTimeSlot()
        assertEquals(1, api.updates)
        assertEquals(listOf("08:00", "08:00", "10:00"), api.lastUpdate?.timeSlots)

        model.addTimeSlot("extra-slot")
        assertEquals(2, api.updates)
        assertEquals(listOf("08:00", "08:00", "10:00", "extra-slot").sorted(), api.lastUpdate?.timeSlots)
    }

    @Test fun `add with blank input does not issue any update`() {
        val api = TimeSlotApi(slots = listOf("08:00"))
        val model = viewModel(api, role = "Administrador")
        model.load()

        model.onInputChange("   ")
        model.addTimeSlot()
        assertEquals(0, api.updates)

        model.addTimeSlot("")
        assertEquals(0, api.updates)
    }

    @Test fun `remove filters chosen value out and immediately calls update`() {
        val api = TimeSlotApi(slots = listOf("04:00", "06:00", "08:00"))
        val model = viewModel(api, role = "Administrador")
        model.load()

        model.removeTimeSlot("06:00")
        assertEquals(1, api.updates)
        assertEquals(listOf("04:00", "08:00"), api.lastUpdate?.timeSlots)
        assertEquals(listOf("04:00", "08:00"), model.state.value.timeSlots)
        assertFalse(model.state.value.saving)
    }

    @Test fun `refuses to remove the last remaining slot locally without issuing a write`() {
        val api = TimeSlotApi(slots = listOf("08:00"))
        val model = viewModel(api, role = "Administrador")
        model.load()

        model.removeTimeSlot("08:00")
        assertEquals(0, api.updates)
        assertEquals(TimeSlotsViewModel.AT_LEAST_ONE_SLOT, model.state.value.error)
        assertNull(model.state.value.refusedStatus)

        val api2 = TimeSlotApi(slots = listOf("08:00", "08:00"))
        val model2 = viewModel(api2, role = "Administrador")
        model2.load()

        model2.removeTimeSlot("08:00")
        assertEquals(0, api2.updates)
        assertEquals(TimeSlotsViewModel.AT_LEAST_ONE_SLOT, model2.state.value.error)
        assertNull(model2.state.value.refusedStatus)
    }

    @Test fun `server refusal preserves HTTP status code distinct from unreachable transport`() {
        val api = TimeSlotApi(slots = listOf("08:00", "09:00"))
        val model = viewModel(api, role = "Administrador")
        model.load()

        api.writeResult = TimeSlotWriteResult.Refused(400)
        model.removeTimeSlot("08:00")
        assertEquals(TimeSlotsViewModel.REFUSED, model.state.value.error)
        assertEquals(400, model.state.value.refusedStatus)
        assertFalse(model.state.value.saving)

        api.writeResult = TimeSlotWriteResult.Refused(403)
        model.removeTimeSlot("08:00")
        assertEquals(TimeSlotsViewModel.REFUSED, model.state.value.error)
        assertEquals(403, model.state.value.refusedStatus)

        api.writeResult = TimeSlotWriteResult.Unreachable
        model.removeTimeSlot("08:00")
        assertEquals(TimeSlotsViewModel.UNREACHABLE, model.state.value.error)
        assertNull(model.state.value.refusedStatus)
    }

    @Test fun `failed load marks unreachable and resets catalog`() {
        val api = TimeSlotApi(slots = null)
        val model = viewModel(api, role = "Administrador")
        model.load()

        assertFalse(model.state.value.loading)
        assertEquals(TimeSlotsViewModel.UNREACHABLE, model.state.value.error)
        assertEquals(emptyList<String>(), model.state.value.timeSlots)

        val okApi = TimeSlotApi(slots = fixtureSlots)
        val profileFailModel = viewModel(okApi, role = "Administrador", profileSucceeds = false)
        profileFailModel.load()
        assertFalse(profileFailModel.state.value.loading)
        assertEquals(TimeSlotsViewModel.UNREACHABLE, profileFailModel.state.value.error)
        assertEquals(emptyList<String>(), profileFailModel.state.value.timeSlots)
    }

    @Test fun `input change clears error and refused status`() {
        val api = TimeSlotApi(slots = listOf("08:00", "09:00"))
        val model = viewModel(api, role = "Administrador")
        model.load()

        api.writeResult = TimeSlotWriteResult.Refused(400)
        model.removeTimeSlot("08:00")
        assertEquals(400, model.state.value.refusedStatus)
        assertEquals(TimeSlotsViewModel.REFUSED, model.state.value.error)

        model.onInputChange("07:00")
        assertNull(model.state.value.error)
        assertNull(model.state.value.refusedStatus)
    }

    @Test fun `cancelling superseded reads prevents older responses replacing the newest catalog`() {
        val api = TimeSlotApi(slots = fixtureSlots)
        api.firstRead = CompletableDeferred()
        val model = viewModel(api, role = "Administrador")

        model.load()
        assertEquals(1, api.reads)
        assertTrue(model.state.value.loading)

        model.load()
        assertEquals(2, api.reads)
        assertEquals(fixtureSlots, model.state.value.timeSlots)

        api.firstRead!!.complete(listOf("99:00"))
        assertEquals(fixtureSlots, model.state.value.timeSlots)
    }

    private fun viewModel(
        api: TimeSlotApi,
        role: String = "Trabalhador",
        profileSucceeds: Boolean = true,
    ) = TimeSlotsViewModel(
        TimeSlotsRepository(api),
        AuthRepository(TimeSlotProfileApi(role, profileSucceeds), TimeSlotStore(), Json),
    )
}

private class TimeSlotApi(
    var slots: List<String>? = emptyList(),
) : AdminApi {
    var reads = 0
    var updates = 0
    var lastUpdate: UpdateTimeSlotsRequest? = null
    var firstRead: CompletableDeferred<List<String>>? = null
    var writeResult: TimeSlotWriteResult = TimeSlotWriteResult.Saved()

    override suspend fun timeSlots(): Response<TimeSlotsResponse> {
        reads++
        val result = if (reads == 1 && firstRead != null) firstRead!!.await() else slots
        return if (result != null) {
            Response.success(TimeSlotsResponse(timeSlots = result))
        } else {
            Response.error(500, "".toResponseBody(null))
        }
    }

    override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse> {
        updates++
        lastUpdate = body
        return when (val result = writeResult) {
            is TimeSlotWriteResult.Saved -> {
                slots = body.timeSlots
                Response.success(TimeSlotsResponse(timeSlots = result.timeSlots ?: body.timeSlots))
            }
            is TimeSlotWriteResult.Refused -> Response.error(result.status, "".toResponseBody(null))
            TimeSlotWriteResult.Unreachable -> throw java.io.IOException("offline")
        }
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
    override suspend fun vehicles(): Response<AdminVehiclesResponse> = error("unused")
    override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> = error("unused")
    override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> = error("unused")
    override suspend fun deleteVehicle(id: Int): Response<Unit> = error("unused")
    override suspend fun users(): Response<AdminUsersResponse> = error("unused")
    override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> = error("unused")
    override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> = error("unused")
    override suspend fun deleteUser(id: Int): Response<Unit> = error("unused")
}

private class TimeSlotProfileApi(
    private val role: String,
    private val succeed: Boolean = true,
) : AuthApi {
    private val data = ProfileResponse(user = ProfileResponseUser(42, "admin", "Admin", role))
    override suspend fun profile() = if (succeed) Response.success(data) else Response.error(500, "".toResponseBody(null))
    override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("unused")
    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("unused")
    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("unused")
    override suspend fun me(): Response<ProfileResponse> = if (succeed) Response.success(data) else Response.error(500, "".toResponseBody(null))
    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("unused")
    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("unused")
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error("unused")
    override suspend fun register(body: RegisterRequest): Response<RegisterResponse> = error("unused")
}

private class TimeSlotStore : TokenStore {
    override fun accessToken() = "token"
    override fun refreshToken() = "refresh"
    override fun role() = null
    override fun save(accessToken: String, refreshToken: String) = Unit
    override fun saveRole(role: String) = Unit
    override fun clear() = Unit
}
