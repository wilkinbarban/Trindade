package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
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
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AuditApi
import com.trindade.app.network.AuthApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `admin role loads audit logs and updates state`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load()

        assertFalse(viewModel.state.value.loading)
        assertTrue(viewModel.state.value.isAdmin)
        assertEquals(AuditViewModel.ADMIN, viewModel.state.value.role)
        assertEquals(1, viewModel.state.value.page)
        assertEquals(2, viewModel.state.value.totalPages)
        assertEquals(40, viewModel.state.value.total)
        assertEquals(1, viewModel.state.value.logs.size)
        assertEquals(101, viewModel.state.value.logs.first().id)
        assertNull(viewModel.state.value.error)
        assertEquals(1, auditApi.reads)
        assertEquals(1, auditApi.lastPage)
        assertEquals(20, auditApi.lastLimit)
    }

    @Test
    fun `worker role fails closed without making audit call`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = "Trabalhador")
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load()

        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.isAdmin)
        assertNull(viewModel.state.value.role)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertEquals(1, viewModel.state.value.page)
        assertEquals(1, viewModel.state.value.totalPages)
        assertEquals(0, viewModel.state.value.total)
        assertFalse(viewModel.state.value.canGoPrevious)
        assertFalse(viewModel.state.value.canGoNext)
        assertNull(viewModel.state.value.error)
        assertEquals(0, auditApi.reads)
    }

    @Test
    fun `unauthenticated or profile failure fails closed and sets unreachable error`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = null)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load()

        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.isAdmin)
        assertNull(viewModel.state.value.role)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertEquals(1, viewModel.state.value.page)
        assertEquals(1, viewModel.state.value.totalPages)
        assertEquals(0, viewModel.state.value.total)
        assertFalse(viewModel.state.value.canGoPrevious)
        assertFalse(viewModel.state.value.canGoNext)
        assertEquals(AuditViewModel.UNREACHABLE, viewModel.state.value.error)
        assertEquals(0, auditApi.reads)
    }

    @Test
    fun `unknown role fails closed without making audit call`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = "Desconhecido")
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load()

        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.isAdmin)
        assertNull(viewModel.state.value.role)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertFalse(viewModel.state.value.canGoPrevious)
        assertFalse(viewModel.state.value.canGoNext)
        assertEquals(0, auditApi.reads)
    }

    @Test
    fun `staging filter inputs does not fetch per keystroke and separates staged from applied`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load()
        assertEquals(1, auditApi.reads)

        viewModel.onActionChange("login")
        viewModel.onEntityTypeChange("auth")
        viewModel.onUserIdChange("42")

        assertEquals("login", viewModel.state.value.actionInput)
        assertEquals("auth", viewModel.state.value.entityTypeInput)
        assertEquals("42", viewModel.state.value.userIdInput)
        assertNull(viewModel.state.value.appliedAction)
        assertNull(viewModel.state.value.appliedEntityType)
        assertNull(viewModel.state.value.appliedUserId)
        assertEquals(1, auditApi.reads)
    }

    @Test
    fun `applying valid filters resets page to 1 and sends filters in audit request`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load(page = 2)
        assertEquals(2, viewModel.state.value.page)

        viewModel.onActionChange("delete")
        viewModel.onEntityTypeChange("task")
        viewModel.onUserIdChange("7")
        viewModel.applyFilters()

        assertFalse(viewModel.state.value.loading)
        assertEquals(1, viewModel.state.value.page)
        assertEquals("delete", viewModel.state.value.appliedAction)
        assertEquals("task", viewModel.state.value.appliedEntityType)
        assertEquals(7, viewModel.state.value.appliedUserId)
        assertEquals(2, auditApi.reads)
        assertEquals(1, auditApi.lastPage)
        assertEquals("delete", auditApi.lastAction)
        assertEquals("task", auditApi.lastEntityType)
        assertEquals(7, auditApi.lastUserId)
    }

    @Test
    fun `applying invalid userId displays error and issues no request`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load()
        assertEquals(1, auditApi.reads)

        // Negative userId
        viewModel.onUserIdChange("-5")
        viewModel.applyFilters()
        assertEquals(AuditViewModel.INVALID_USER_ID, viewModel.state.value.error)
        assertNull(viewModel.state.value.appliedUserId)
        assertEquals(1, auditApi.reads)

        // Zero userId
        viewModel.onUserIdChange("0")
        viewModel.applyFilters()
        assertEquals(AuditViewModel.INVALID_USER_ID, viewModel.state.value.error)
        assertEquals(1, auditApi.reads)

        // Non-numeric userId
        viewModel.onUserIdChange("abc")
        viewModel.applyFilters()
        assertEquals(AuditViewModel.INVALID_USER_ID, viewModel.state.value.error)
        assertEquals(1, auditApi.reads)

        // Valid empty userId clears filter and proceeds
        viewModel.onUserIdChange("")
        viewModel.applyFilters()
        assertNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.appliedUserId)
        assertEquals(2, auditApi.reads)
    }

    @Test
    fun `clearing filters resets inputs and applied filters and reloads page 1`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.onActionChange("create")
        viewModel.onEntityTypeChange("user")
        viewModel.onUserIdChange("15")
        viewModel.applyFilters()
        assertEquals(1, auditApi.reads)
        assertEquals("create", auditApi.lastAction)

        viewModel.clearFilters()

        assertEquals("", viewModel.state.value.actionInput)
        assertEquals("", viewModel.state.value.entityTypeInput)
        assertEquals("", viewModel.state.value.userIdInput)
        assertNull(viewModel.state.value.appliedAction)
        assertNull(viewModel.state.value.appliedEntityType)
        assertNull(viewModel.state.value.appliedUserId)
        assertEquals(1, viewModel.state.value.page)
        assertEquals(2, auditApi.reads)
        assertNull(auditApi.lastAction)
        assertNull(auditApi.lastEntityType)
        assertNull(auditApi.lastUserId)
    }

    @Test
    fun `pagination moves forward and backward bounded by server totalPages`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load()
        assertEquals(1, viewModel.state.value.page)
        assertFalse(viewModel.state.value.canGoPrevious)
        assertTrue(viewModel.state.value.canGoNext)

        // previous on page 1 does nothing
        viewModel.previousPage()
        assertEquals(1, auditApi.reads)

        // next page
        viewModel.nextPage()
        assertEquals(2, auditApi.reads)
        assertEquals(2, viewModel.state.value.page)
        assertTrue(viewModel.state.value.canGoPrevious)
        assertFalse(viewModel.state.value.canGoNext)

        // next on last page does nothing
        viewModel.nextPage()
        assertEquals(2, auditApi.reads)

        // previous moves back
        viewModel.previousPage()
        assertEquals(3, auditApi.reads)
        assertEquals(1, viewModel.state.value.page)
    }

    @Test
    fun `read failure clears stale logs and resets paging metadata`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load(page = 2)
        assertEquals(2, viewModel.state.value.page)
        assertEquals(2, viewModel.state.value.totalPages)
        assertEquals(40, viewModel.state.value.total)
        assertEquals(1, viewModel.state.value.logs.size)
        assertTrue(viewModel.state.value.canGoPrevious)

        auditApi.failAudit = true
        viewModel.load(page = 2)

        assertFalse(viewModel.state.value.loading)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertEquals(1, viewModel.state.value.page)
        assertEquals(1, viewModel.state.value.totalPages)
        assertEquals(0, viewModel.state.value.total)
        assertFalse(viewModel.state.value.canGoPrevious)
        assertFalse(viewModel.state.value.canGoNext)
        assertEquals(AuditViewModel.UNREACHABLE, viewModel.state.value.error)
        assertTrue(viewModel.state.value.isAdmin)

        // Block navigation after failure
        viewModel.nextPage()
        viewModel.previousPage()
        assertEquals(2, auditApi.reads)
    }

    @Test
    fun `navigation is blocked while loading or when not authorized`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        // First load page 2
        viewModel.load(page = 2)
        assertEquals(2, viewModel.state.value.page)
        assertTrue(viewModel.state.value.canGoPrevious)

        // Now start reload on slow network
        val deferred = CompletableDeferred<Response<AuditResponse>>()
        auditApi.deferredResponse = deferred
        viewModel.load(page = 2)

        assertTrue(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.isAdmin)
        assertFalse(viewModel.state.value.canGoPrevious)
        assertFalse(viewModel.state.value.canGoNext)

        // Attempting navigation while loading is a no-op
        viewModel.nextPage()
        viewModel.previousPage()
        // No new reads dispatched while loading
        assertEquals(2, auditApi.reads)

        // Complete the in-flight load
        auditApi.deferredResponse = null
        deferred.complete(
            Response.success(
                AuditResponse(
                    logs = listOf(sampleLog(id = 102, page = 2)),
                    total = 40,
                    page = 2,
                    limit = 20,
                    totalPages = 2,
                ),
            ),
        )
        assertFalse(viewModel.state.value.loading)
        assertTrue(viewModel.state.value.isAdmin)
        assertTrue(viewModel.state.value.canGoPrevious)

        // Worker role cannot navigate even if state held pages
        val workerAuth = FakeAuthApi(role = "Trabalhador")
        val workerModel = createViewModel(auditApi, workerAuth)
        workerModel.load()
        assertFalse(workerModel.state.value.isAdmin)
        assertFalse(workerModel.state.value.canGoPrevious)
        assertFalse(workerModel.state.value.canGoNext)
        workerModel.nextPage()
        workerModel.previousPage()
        assertEquals(2, auditApi.reads)
    }

    @Test
    fun `profile failure on reload clears previously loaded role and logs`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load()
        assertTrue(viewModel.state.value.isAdmin)
        assertEquals(1, viewModel.state.value.logs.size)

        authApi.failProfile = true
        viewModel.load()

        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.isAdmin)
        assertNull(viewModel.state.value.role)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertEquals(AuditViewModel.UNREACHABLE, viewModel.state.value.error)
    }

    @Test
    fun `newer load cancels older in-flight request and ignores stale completion`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        val deferredFirst = CompletableDeferred<Response<AuditResponse>>()
        auditApi.deferredResponse = deferredFirst

        viewModel.load(page = 1)
        assertTrue(viewModel.state.value.loading)

        // Start newer load for page 2 before first finishes
        auditApi.deferredResponse = null
        viewModel.load(page = 2)

        assertFalse(viewModel.state.value.loading)
        assertEquals(2, viewModel.state.value.page)
        assertEquals(102, viewModel.state.value.logs.first().id)

        // Complete the first deferred; state must stay on page 2
        deferredFirst.complete(
            Response.success(
                AuditResponse(
                    logs = listOf(sampleLog(id = 999, page = 1)),
                    total = 10,
                    page = 1,
                    limit = 20,
                    totalPages = 1,
                ),
            ),
        )

        assertEquals(2, viewModel.state.value.page)
        assertEquals(102, viewModel.state.value.logs.first().id)
    }

    @Test
    fun `arriving response does not clobber newly staged inputs`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        val deferred = CompletableDeferred<Response<AuditResponse>>()
        auditApi.deferredResponse = deferred

        viewModel.load()
        assertTrue(viewModel.state.value.loading)

        // User enters staged inputs while load is in flight
        viewModel.onActionChange("export")
        viewModel.onEntityTypeChange("report")
        viewModel.onUserIdChange("99")

        // Response arrives
        auditApi.deferredResponse = null
        deferred.complete(
            Response.success(
                AuditResponse(
                    logs = listOf(sampleLog(id = 200, page = 1)),
                    total = 1,
                    page = 1,
                    limit = 20,
                    totalPages = 1,
                ),
            ),
        )

        assertFalse(viewModel.state.value.loading)
        assertEquals(1, viewModel.state.value.logs.size)
        // Staged inputs must be preserved, not clobbered
        assertEquals("export", viewModel.state.value.actionInput)
        assertEquals("report", viewModel.state.value.entityTypeInput)
        assertEquals("99", viewModel.state.value.userIdInput)
    }

    @Test
    fun `load atomically clears prior sensitive logs role and paging at load start preventing leak on session revalidation`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        // 1. Initial admin load populates sensitive logs and paging metadata
        viewModel.load(page = 2)
        assertEquals(1, viewModel.state.value.logs.size)
        assertEquals(2, viewModel.state.value.page)
        assertEquals(2, viewModel.state.value.totalPages)
        assertEquals(40, viewModel.state.value.total)
        assertEquals(AuditViewModel.ADMIN, viewModel.state.value.role)
        assertTrue(viewModel.state.value.isAdmin)

        // 2. Suspend next profile call to inspect intermediate state during revalidation
        val deferredProfile = CompletableDeferred<Response<ProfileResponse>>()
        authApi.deferredProfile = deferredProfile

        viewModel.load(page = 1)

        // Assert immediately after load() that prior operator data is completely cleared
        assertTrue(viewModel.state.value.loading)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertEquals(1, viewModel.state.value.page)
        assertEquals(1, viewModel.state.value.totalPages)
        assertEquals(0, viewModel.state.value.total)
        assertNull(viewModel.state.value.role)
        assertFalse(viewModel.state.value.isAdmin)
        assertFalse(viewModel.state.value.canGoPrevious)
        assertFalse(viewModel.state.value.canGoNext)

        // 3. Complete profile revalidation with worker role (session switch / demotion)
        authApi.deferredProfile = null
        deferredProfile.complete(
            Response.success(
                ProfileResponse(
                    user = ProfileResponseUser(99, "worker", "Worker", "Trabalhador"),
                ),
            ),
        )

        // Assert fail-closed state: worker is denied, no audit call was dispatched, no data leaked
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.role)
        assertFalse(viewModel.state.value.isAdmin)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertEquals(1, viewModel.state.value.page)
        assertEquals(1, viewModel.state.value.totalPages)
        assertEquals(0, viewModel.state.value.total)
        assertEquals(1, auditApi.reads)
    }

    @Test
    fun `load with non-positive page fails closed and issues no network request`() {
        val auditApi = FakeAuditApi()
        val authApi = FakeAuthApi(role = AuditViewModel.ADMIN)
        val viewModel = createViewModel(auditApi, authApi)

        viewModel.load(page = 0)

        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.role)
        assertFalse(viewModel.state.value.isAdmin)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertEquals(1, viewModel.state.value.page)
        assertEquals(1, viewModel.state.value.totalPages)
        assertEquals(0, viewModel.state.value.total)
        assertNull(viewModel.state.value.error)
        assertEquals(0, auditApi.reads)
        assertEquals(0, authApi.profileReads)

        viewModel.load(page = -5)

        assertFalse(viewModel.state.value.loading)
        assertEquals(0, auditApi.reads)
        assertEquals(0, authApi.profileReads)
    }

    private fun createViewModel(
        auditApi: FakeAuditApi,
        authApi: FakeAuthApi,
    ): AuditViewModel {
        val auditRepo = AuditRepository(auditApi)
        val authRepo = AuthRepository(authApi, FakeTokenStore(), Json { ignoreUnknownKeys = true })
        return AuditViewModel(auditRepo, authRepo)
    }
}

private class FakeAuditApi : AuditApi {
    var reads = 0
    var lastPage: Int? = null
    var lastLimit: Int? = null
    var lastAction: String? = null
    var lastEntityType: String? = null
    var lastUserId: Int? = null
    var deferredResponse: CompletableDeferred<Response<AuditResponse>>? = null
    var failAudit = false

    var responseBuilder: (page: Int, action: String?, entityType: String?, userId: Int?) -> Response<AuditResponse> =
        { p, a, e, u ->
            Response.success(
                AuditResponse(
                    logs = listOf(
                        sampleLog(id = 100 + p, page = p, action = a ?: "login", entityType = e ?: "auth", userId = u ?: 1),
                    ),
                    total = 40,
                    page = p,
                    limit = 20,
                    totalPages = 2,
                ),
            )
        }

    override suspend fun audit(
        page: Int?,
        limit: Int?,
        action: String?,
        entityType: String?,
        userId: Int?,
    ): Response<AuditResponse> {
        reads++
        lastPage = page
        lastLimit = limit
        lastAction = action
        lastEntityType = entityType
        lastUserId = userId

        val deferred = deferredResponse
        if (deferred != null) return deferred.await()
        if (failAudit) return Response.error(500, "".toResponseBody(null))
        return responseBuilder(page ?: 1, action, entityType, userId)
    }
}

private class FakeAuthApi(var role: String? = AuditViewModel.ADMIN) : AuthApi {
    var profileReads = 0
    var failProfile = false
    var deferredProfile: CompletableDeferred<Response<ProfileResponse>>? = null

    override suspend fun profile(): Response<ProfileResponse> {
        profileReads++
        val deferred = deferredProfile
        if (deferred != null) return deferred.await()
        if (failProfile) return Response.error(500, "".toResponseBody(null))
        val currentRole = role ?: return Response.error(404, "".toResponseBody(null))
        return Response.success(ProfileResponse(user = ProfileResponseUser(42, "admin", "Admin", currentRole)))
    }

    override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("unused")
    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("unused")
    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("unused")
    override suspend fun me(): Response<ProfileResponse> = profile()
    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("unused")
    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("unused")
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error("unused")
    override suspend fun register(body: RegisterRequest): Response<RegisterResponse> = error("unused")
}

private class FakeTokenStore : TokenStore {
    override fun accessToken() = "token"
    override fun refreshToken() = "refresh"
    override fun role() = null
    override fun save(accessToken: String, refreshToken: String) = Unit
    override fun saveRole(role: String) = Unit
    override fun clear() = Unit
}

private fun sampleLog(
    id: Int = 1,
    page: Int = 1,
    action: String = "login",
    entityType: String = "auth",
    userId: Int = 42,
) = AuditResponseLogsInner(
    id = id,
    userId = userId,
    displayName = "Admin User",
    action = action,
    entityType = entityType,
    entityId = 10,
    ipAddress = "127.0.0.1",
    details = """{"page":$page}""",
    createdAt = "2026-09-28T12:00:00Z",
)
