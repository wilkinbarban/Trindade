package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
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
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.AuthApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Unit tests for [TimeSlotsAdminViewModel] bounded integration unit 1.
 *
 * Covers:
 * - Intact lazy load test from source git object 3e4bf1a.
 * - FormState canSubmit property behavior.
 * - UiState isAdmin and canRemove boundary logic.
 * - Companion regex boundary validations and normalization.
 * - Default handleBack navigation behavior.
 *
 * 10 original source cases are explicitly deferred to subsequent integration units:
 * - Read (6):
 *   1. `loadData refreshes profile and authorizes admin with sorted slots`
 *   2. `loadData fails closed on role revocation when refreshed profile is not admin`
 *   3. `loadData fails closed when profile refresh fails`
 *   4. `loadData cancels overlapping previous load and latest result wins`
 *   5. `failed read clears stale time slots from state`
 *   6. `403 on timeSlots read fails closed and revokes admin state`
 * - Mutation (4):
 *   1. `addTimeSlot fails closed when backend answers 403`
 *   2. `removeTimeSlot fails closed when backend answers 403`
 *   3. `concurrent delete requests are ignored while delete is in flight`
 *   4. `role revocation clears active form and delete confirmation state` (depends on both)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeSlotsAdminViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestTokenStore(private var role: String? = null) : TokenStore {
        override fun accessToken(): String? = "token"
        override fun refreshToken(): String? = "refresh"
        override fun role(): String? = role
        override fun save(accessToken: String, refreshToken: String) {}
        override fun saveRole(role: String) { this.role = role }
        override fun clear() { role = null }
    }

    private class TestAuthApi(
        var profileResponse: Response<ProfileResponse>? = null,
    ) : AuthApi {
        override suspend fun profile(): Response<ProfileResponse> =
            profileResponse ?: Response.error(500, "".toResponseBody(null))
        override suspend fun me(): Response<ProfileResponse> =
            profileResponse ?: Response.error(500, "".toResponseBody(null))
        override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("unused")
        override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("unused")
        override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("unused")
        override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("unused")
        override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("unused")
        override suspend fun setupStatus(): Response<SetupStatusResponse> = error("unused")
        override suspend fun register(body: RegisterRequest): Response<RegisterResponse> = error("unused")
    }

    private class TestAdminApi(
        var slots: List<String>? = listOf("06:00", "08:00"),
        var updateResponse: Response<TimeSlotsResponse>? = null,
    ) : AdminApi {
        var timeSlotsCallCount = 0
        var updateCallCount = 0
        var timeSlotsHandler: (suspend () -> Response<TimeSlotsResponse>)? = null
        var updateHandler: (suspend (UpdateTimeSlotsRequest) -> Response<TimeSlotsResponse>)? = null

        override suspend fun timeSlots(): Response<TimeSlotsResponse> {
            timeSlotsCallCount++
            timeSlotsHandler?.let { return it() }
            val current = slots ?: return Response.error(500, "".toResponseBody(null))
            return Response.success(TimeSlotsResponse(timeSlots = current))
        }

        override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse> {
            updateCallCount++
            updateHandler?.let { return it(body) }
            updateResponse?.let { return it }
            slots = body.timeSlots
            return Response.success(TimeSlotsResponse(timeSlots = body.timeSlots))
        }

        override suspend fun categories(): Response<AdminCategoriesResponse> = error("unused")
        override suspend fun createCategory(body: CreateAdminCategoryRequest): Response<AdminCategoryResponse> = error("unused")
        override suspend fun updateCategory(id: Int, body: JsonObject): Response<AdminCategoryResponse> = error("unused")
        override suspend fun deleteCategory(id: Int): Response<Unit> = error("unused")
        override suspend fun tasks(): Response<AdminTasksResponse> = error("unused")
        override suspend fun createTask(body: CreateAdminTaskRequest): Response<AdminTaskResponse> = error("unused")
        override suspend fun updateTask(id: Int, body: JsonObject): Response<AdminTaskResponse> = error("unused")
        override suspend fun deleteTask(id: Int): Response<Unit> = error("unused")
        override suspend fun drivers(): Response<AdminDriversResponse> = error("unused")
        override suspend fun createDriver(body: CreateAdminDriverRequest): Response<AdminDriverResponse> = error("unused")
        override suspend fun updateDriver(id: Int, body: JsonObject): Response<AdminDriverResponse> = error("unused")
        override suspend fun vehicles(): Response<AdminVehiclesResponse> = error("unused")
        override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> = error("unused")
        override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> = error("unused")
        override suspend fun deleteVehicle(id: Int): Response<Unit> = error("unused")
        override suspend fun users(): Response<AdminUsersResponse> = error("unused")
        override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> = error("unused")
        override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> = error("unused")
        override suspend fun deleteUser(id: Int): Response<Unit> = error("unused")
    }

    private fun profileSuccess(role: String = RolePolicy.ADMIN) = Response.success(
        ProfileResponse(user = ProfileResponseUser(id = 1, username = "test", displayName = "Test", role = role))
    )

    private fun createAuthRepository(
        role: String? = null,
        profileResponse: Response<ProfileResponse>? = profileSuccess(role ?: RolePolicy.ADMIN),
    ): AuthRepository = AuthRepository(
        api = TestAuthApi(profileResponse = profileResponse),
        tokenStore = TestTokenStore(role = role),
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `viewModel does not eagerly load time slots during instantiation`() {
        val adminApi = TestAdminApi()
        val authRepo = createAuthRepository(role = RolePolicy.ADMIN)
        val repository = TimeSlotsAdminRepository(adminApi)

        TimeSlotsAdminViewModel(repository, authRepo)

        assertEquals("Redundant eager load should not happen during init", 0, adminApi.timeSlotsCallCount)
    }

    @Test
    fun `nested FormState canSubmit evaluates based on saving state`() {
        val idleState = TimeSlotsAdminViewModel.FormState()
        assertTrue("canSubmit should be true when not saving", idleState.canSubmit)

        val savingState = TimeSlotsAdminViewModel.FormState(saving = true)
        assertFalse("canSubmit should be false when saving", savingState.canSubmit)
    }

    @Test
    fun `UiState isAdmin and canRemove evaluate role and slot count boundaries`() {
        val adminWithMultiple = TimeSlotsAdminViewModel.UiState(
            role = RolePolicy.ADMIN,
            timeSlots = listOf("08:00", "10:00"),
        )
        assertTrue(adminWithMultiple.isAdmin)
        assertTrue("Admin with >1 slots can remove", adminWithMultiple.canRemove)

        val adminWithSingle = TimeSlotsAdminViewModel.UiState(
            role = RolePolicy.ADMIN,
            timeSlots = listOf("08:00"),
        )
        assertTrue(adminWithSingle.isAdmin)
        assertFalse("Admin with 1 slot cannot remove", adminWithSingle.canRemove)

        val adminWithEmpty = TimeSlotsAdminViewModel.UiState(
            role = RolePolicy.ADMIN,
            timeSlots = emptyList(),
        )
        assertTrue(adminWithEmpty.isAdmin)
        assertFalse("Admin with 0 slots cannot remove", adminWithEmpty.canRemove)

        val workerWithMultiple = TimeSlotsAdminViewModel.UiState(
            role = RolePolicy.WORKER,
            timeSlots = listOf("08:00", "10:00"),
        )
        assertFalse(workerWithMultiple.isAdmin)
        assertFalse("Worker cannot remove regardless of slot count", workerWithMultiple.canRemove)

        val unauthenticated = TimeSlotsAdminViewModel.UiState(
            role = null,
            timeSlots = listOf("08:00", "10:00"),
        )
        assertFalse(unauthenticated.isAdmin)
        assertFalse("Unauthenticated cannot remove", unauthenticated.canRemove)
    }

    @Test
    fun `isValidTimeSlot validates boundary values and rejects invalid formats`() {
        // Valid boundaries
        assertTrue(TimeSlotsAdminViewModel.isValidTimeSlot("00:00"))
        assertTrue(TimeSlotsAdminViewModel.isValidTimeSlot("0:00"))
        assertTrue(TimeSlotsAdminViewModel.isValidTimeSlot("08:00"))
        assertTrue(TimeSlotsAdminViewModel.isValidTimeSlot("8:00"))
        assertTrue(TimeSlotsAdminViewModel.isValidTimeSlot("23:59"))
        assertTrue(TimeSlotsAdminViewModel.isValidTimeSlot(" 08:00 "))

        // Invalid boundaries and malformed values
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("24:00"))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("25:00"))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("12:60"))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("12:61"))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot(""))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("   "))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("abc"))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("08:0"))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("08:000"))
        assertFalse(TimeSlotsAdminViewModel.isValidTimeSlot("08-00"))
    }

    @Test
    fun `normalizeTimeSlot pads single digit hour and trims whitespace`() {
        assertEquals("08:00", TimeSlotsAdminViewModel.normalizeTimeSlot("8:00"))
        assertEquals("08:00", TimeSlotsAdminViewModel.normalizeTimeSlot("08:00"))
        assertEquals("09:30", TimeSlotsAdminViewModel.normalizeTimeSlot(" 9:30 "))
        assertEquals("23:59", TimeSlotsAdminViewModel.normalizeTimeSlot("23:59"))
    }

    @Test
    fun `handleBack returns false by default when no form or deletion is active`() {
        val adminApi = TestAdminApi()
        val authRepo = createAuthRepository(role = RolePolicy.ADMIN)
        val repository = TimeSlotsAdminRepository(adminApi)
        val viewModel = TimeSlotsAdminViewModel(repository, authRepo)

        assertFalse("handleBack should return false on initial default state", viewModel.handleBack())
    }
}
