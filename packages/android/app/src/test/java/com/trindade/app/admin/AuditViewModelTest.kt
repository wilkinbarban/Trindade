package com.trindade.app.admin

import com.trindade.app.network.AuthApi
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.AuditResponse
import com.trindade.app.contract.models.AuditResponseLogsInner
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
import com.trindade.app.contract.models.UpdateProfileRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
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
            id = 1,
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
        userId: Int = 1,
    ): AuthRepository {
        val tokenStore = FakeTokenStore(role = role)
        val authApi = FakeAuthApi(
            user = ProfileResponseUser(
                id = userId,
                username = "admin_user",
                displayName = "Admin User",
                role = role,
            ),
        )
        return AuthRepository(authApi, tokenStore, Json { ignoreUnknownKeys = true })
    }

    @Test
    fun `init loads audit logs from repository for administrator`() {
        val fakeApi = FakeAdminApi()
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.isAdmin)
        assertEquals(2, state.logs.size)
        assertEquals(2, state.total)
        assertEquals(1, state.page)
        assertEquals(1, state.totalPages)
        assertNull(state.message)
        assertFalse(state.isError)
        assertEquals(1, fakeApi.auditCalls.size)
    }

    @Test
    fun `init refuses access for worker with Portuguese error message and never calls repository`() {
        val fakeApi = FakeAdminApi()
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.WORKER)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertFalse(state.isAdmin)
        assertTrue(state.logs.isEmpty())
        assertTrue(state.isError)
        assertEquals("Acesso restrito a administradores.", state.message)
        assertEquals(0, fakeApi.auditCalls.size)
    }

    @Test
    fun `server 403 role refusal sets authorization error message`() {
        val fakeApi = FakeAdminApi(
            auditResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody("application/json".toMediaType())),
        )
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.isError)
        assertEquals("Acesso negado: permissão de administrador necessária.", state.message)
    }

    @Test
    fun `server 400 bad request sets query validation error message`() {
        val fakeApi = FakeAdminApi(
            auditResponse = Response.error(400, "{\"error\":\"Bad request\"}".toResponseBody("application/json".toMediaType())),
        )
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.isError)
        assertEquals("Parâmetros de consulta inválidos.", state.message)
    }

    @Test
    fun `server unreachable sets connection error message`() {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun audit(
                page: Int?,
                limit: Int?,
                action: String?,
                entityType: String?,
                userId: Int?,
            ): Response<AuditResponse> {
                throw java.io.IOException("Unreachable")
            }
        }
        val repo = AuditRepository(throwingApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.isError)
        assertEquals("Não foi possível conectar ao servidor.", state.message)
    }

    @Test
    fun `filter inputs can be staged and applied to query page 1`() {
        val fakeApi = FakeAdminApi()
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onActionInputChanged("create")
        vm.onEntityTypeInputChanged("category")
        vm.onUserIdInputChanged("42")

        assertEquals("create", vm.uiState.value.actionInput)
        assertEquals("category", vm.uiState.value.entityTypeInput)
        assertEquals("42", vm.uiState.value.userIdInput)
        assertEquals("", vm.uiState.value.appliedAction)

        vm.applyFilters()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("create", state.appliedAction)
        assertEquals("category", state.appliedEntityType)
        assertEquals(42, state.appliedUserId)
        assertTrue(state.hasActiveFilters)

        val lastCall = fakeApi.auditCalls.last()
        assertEquals(1, lastCall.page)
        assertEquals("create", lastCall.action)
        assertEquals("category", lastCall.entityType)
        assertEquals(42, lastCall.userId)
    }

    @Test
    fun `clearFilters resets inputs and applied filters and re-queries page 1`() {
        val fakeApi = FakeAdminApi()
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onActionInputChanged("login")
        vm.applyFilters()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.hasActiveFilters)

        vm.clearFilters()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("", state.actionInput)
        assertEquals("", state.entityTypeInput)
        assertEquals("", state.userIdInput)
        assertEquals("", state.appliedAction)
        assertEquals("", state.appliedEntityType)
        assertNull(state.appliedUserId)
        assertFalse(state.hasActiveFilters)

        val lastCall = fakeApi.auditCalls.last()
        assertEquals(1, lastCall.page)
        assertNull(lastCall.action)
        assertNull(lastCall.entityType)
        assertNull(lastCall.userId)
    }

    @Test
    fun `pagination navigates previous and next pages`() {
        val pagedResponse = AuditResponse(
            logs = listOf(
                AuditResponseLogsInner(
                    id = 1,
                    userId = 1,
                    displayName = "Admin",
                    action = "login",
                    entityType = "auth",
                    entityId = 1,
                    ipAddress = "127.0.0.1",
                    details = null,
                    createdAt = "2026-09-22T10:00:00.000Z",
                ),
            ),
            total = 50,
            page = 2,
            limit = 20,
            totalPages = 3,
        )
        val fakeApi = FakeAdminApi(auditToReturn = pagedResponse)
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.canGoPrevious)
        assertTrue(vm.uiState.value.canGoNext)

        // Previous page
        vm.goToPreviousPage()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fakeApi.auditCalls.last().page)

        // Next page
        vm.goToNextPage()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, fakeApi.auditCalls.last().page)
    }

    @Test
    fun `toggleDetails expands and collapses log entry details`() {
        val fakeApi = FakeAdminApi()
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.expandedLogId)

        vm.toggleDetails(1)
        assertEquals(1, vm.uiState.value.expandedLogId)

        vm.toggleDetails(1)
        assertNull(vm.uiState.value.expandedLogId)
    }

    @Test
    fun `handleBack collapses expanded details before delegating to screen navigation`() {
        val fakeApi = FakeAdminApi()
        val repo = AuditRepository(fakeApi)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)

        val vm = AuditViewModel(repo, auth)
        testDispatcher.scheduler.advanceUntilIdle()

        // When nothing expanded, handleBack returns false
        assertFalse(vm.handleBack())

        // When item is expanded, handleBack collapses it and returns true
        vm.toggleDetails(1)
        assertEquals(1, vm.uiState.value.expandedLogId)
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.expandedLogId)
    }
}
