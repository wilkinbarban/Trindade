package com.trindade.app.share

import com.trindade.app.admin.UsersRepository
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.AdminCategoriesResponse
import com.trindade.app.contract.models.AdminCategoryResponse
import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriversResponse
import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.contract.models.AdminTasksResponse
import com.trindade.app.contract.models.AdminUserResponse
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.contract.models.AdminUsersResponse
import com.trindade.app.contract.models.AdminVehicleResponse
import com.trindade.app.contract.models.AdminVehiclesResponse
import com.trindade.app.contract.models.AuthUser
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.create
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ShareAppViewModelTest {

    private val user1 = AdminUserResponseUser(1, "ana.admin", "Ana Admin", 1, "Administrador", 1, "now")
    private val user2 = AdminUserResponseUser(2, "joao.silva", "João Silva", 2, "Trabalhador", 1, "now")
    private val fixtureUsers = listOf(user1, user2)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `administrator sees admin privileges, loads users, and includes credentials`() {
        val adminApi = FakeAdminApi(fixtureUsers)
        val authApi = FakeAuthApi(role = "Administrador", userId = 1)
        val authRepo = AuthRepository(authApi, FakeTokenStore("Administrador"), Json)
        val usersRepo = UsersRepository(adminApi)

        val vm = ShareAppViewModel(authRepo, usersRepo)

        assertTrue(vm.state.value.isAdmin)
        assertEquals(2, vm.state.value.users.size)

        // Picking an account that already exists is the other mode; creating a worker is what the screen
        // offers first, so the test has to choose this one.
        vm.onCredentialModeSelect(ShareCredentialMode.EXISTING)
        vm.onUserSelect(2)
        assertEquals(2, vm.state.value.selectedUserId)
        assertEquals("João Silva", vm.state.value.displayName)
        assertEquals("joao.silva", vm.state.value.username)
        assertEquals("123456789", vm.state.value.password)

        // Password change
        vm.onPasswordChange("NovaSenha99")
        assertEquals("NovaSenha99", vm.state.value.password)

        val message = vm.message()
        assertTrue(message.contains("Olá, *João Silva*!"))
        assertTrue(message.contains("Usuário: *joao.silva*"))
        assertTrue(message.contains("Senha: *NovaSenha99*"))

        // Disabling credentials hides them from message
        vm.onIncludeCredentialsToggle(false)
        assertFalse(vm.state.value.includeCredentials)
        assertFalse(vm.message().contains("🔐 *Suas Credenciais:*"))
    }

    @Test
    fun `worker does not get admin privileges, does not load users, and excludes credentials`() {
        val adminApi = FakeAdminApi(fixtureUsers)
        val authApi = FakeAuthApi(role = "Trabalhador", userId = 2)
        val authRepo = AuthRepository(authApi, FakeTokenStore("Trabalhador"), Json)
        val usersRepo = UsersRepository(adminApi)

        val vm = ShareAppViewModel(authRepo, usersRepo)

        assertFalse(vm.state.value.isAdmin)
        assertTrue(vm.state.value.users.isEmpty())
        assertFalse(vm.message().contains("🔐 *Suas Credenciais:*"))
    }

    @Test
    fun `platform selection updates message content accordingly`() {
        val adminApi = FakeAdminApi(fixtureUsers)
        val authApi = FakeAuthApi(role = "Trabalhador", userId = 2)
        val authRepo = AuthRepository(authApi, FakeTokenStore("Trabalhador"), Json)
        val usersRepo = UsersRepository(adminApi)

        val vm = ShareAppViewModel(authRepo, usersRepo)

        // Default: Android
        assertEquals(SharePlatform.ANDROID, vm.state.value.platform)
        assertTrue(vm.message().contains("📱 *Aplicativo Android:*"))
        assertFalse(vm.message().contains("🌐 *Acesso Web"))

        // iOS
        vm.onPlatformSelect(SharePlatform.IOS)
        assertEquals(SharePlatform.IOS, vm.state.value.platform)
        assertFalse(vm.message().contains("📱 *Aplicativo Android:*"))
        assertTrue(vm.message().contains("🌐 *Acesso Web"))

        // Both
        vm.onPlatformSelect(SharePlatform.BOTH)
        assertEquals(SharePlatform.BOTH, vm.state.value.platform)
        assertTrue(vm.message().contains("📱 *Aplicativo Android:*"))
        assertTrue(vm.message().contains("🌐 *Acesso Web"))
    }

    @Test
    fun `phone changes apply mask and whatsapp link uses normalized international number`() {
        val adminApi = FakeAdminApi(fixtureUsers)
        val authApi = FakeAuthApi(role = "Trabalhador", userId = 2)
        val authRepo = AuthRepository(authApi, FakeTokenStore("Trabalhador"), Json)
        val usersRepo = UsersRepository(adminApi)

        val vm = ShareAppViewModel(authRepo, usersRepo)
        assertNull(vm.whatsappLink())

        vm.onPhoneChange("11988887777")
        assertEquals("11988887777", vm.state.value.phone)

        val link = vm.whatsappLink()
        assertNotNull(link)
        assertTrue(link!!.startsWith("https://api.whatsapp.com/send?phone=5511988887777&text="))
    }
    @Test
    fun `a new worker is explicitly created with the derived login, generated password, worker role, and success feedback`() {
        val adminApi = FakeAdminApi(fixtureUsers)
        val authRepo = AuthRepository(FakeAuthApi(role = "Administrador", userId = 1), FakeTokenStore("Administrador"), Json)
        val vm = ShareAppViewModel(authRepo, UsersRepository(adminApi))

        // Creation is what the screen offers first.
        assertEquals(ShareCredentialMode.CREATE, vm.state.value.credentialMode)

        vm.onQuickNameChange("Pedro")
        assertEquals("pedro", vm.quickLogin())
        assertEquals("pedro1234567890", vm.quickPassword())

        // The message already carries the credentials that will exist once the account is created.
        val preview = vm.message()
        assertTrue(preview.contains("Olá, *Pedro*!"))
        assertTrue(preview.contains("Usuário: *pedro*"))
        assertTrue(preview.contains("Senha: *pedro1234567890*"))

        // Explicit creation via button (S9, S11)
        vm.createWorker()

        assertEquals(1, adminApi.created.size)
        val body = adminApi.created.first()
        assertEquals("pedro", body["username"]?.toString()?.trim('"'))
        assertEquals("Pedro", body["display_name"]?.toString()?.trim('"'))
        assertEquals("pedro1234567890", body["password"]?.toString()?.trim('"'))
        // The role travels as a JSON number, which is what the backend schema refuses to read otherwise.
        assertEquals("2", body["role_id"]?.toString())
        assertEquals("pedro", vm.state.value.createdUsername)
        assertTrue(vm.state.value.createSuccess)
        assertTrue(vm.isWorkerRegistered())
    }

    @Test
    fun `a worker the company already has counts as registered and is not created again`() {
        // The login the derivation produces for "Joao Silva" is joaosilva, and this plant already has it.
        val known = AdminUserResponseUser(3, "joaosilva", "Joao Silva", 2, "Trabalhador", 1, "now")
        val adminApi = FakeAdminApi(fixtureUsers + known)
        val authRepo = AuthRepository(FakeAuthApi(role = "Administrador", userId = 1), FakeTokenStore("Administrador"), Json)
        val vm = ShareAppViewModel(authRepo, UsersRepository(adminApi))

        // Joao Silva already works here: the login exists, so the credentials in the message work too.
        vm.onQuickNameChange("Joao Silva")

        assertTrue(vm.isWorkerRegistered())
        assertTrue(kotlinx.coroutines.runBlocking { vm.ensureWorkerCreated() })
        assertEquals(0, adminApi.created.size)

        vm.createWorker()

        // Asking to register a login that exists answers with the green confirmation instead of a 409.
        assertTrue(vm.state.value.createSuccess)
        assertEquals("joaosilva", vm.state.value.createdUsername)
        assertEquals(0, adminApi.created.size)
    }

    @Test
    fun `sharing or copying without prior explicit creation is refused and asks for registration`() {
        val adminApi = FakeAdminApi(fixtureUsers)
        val authRepo = AuthRepository(FakeAuthApi(role = "Administrador", userId = 1), FakeTokenStore("Administrador"), Json)
        val vm = ShareAppViewModel(authRepo, UsersRepository(adminApi))

        vm.onQuickNameChange("Pedro")
        // Did not call vm.createWorker() yet!
        assertFalse(vm.isWorkerRegistered())

        val canProceed = kotlinx.coroutines.runBlocking { vm.ensureWorkerCreated() }

        assertFalse(canProceed)
        assertEquals(ShareCreateError.CREATION_REQUIRED, vm.state.value.createError)
        // No user was created on the server behind the admin's back (S12)
        assertEquals(0, adminApi.created.size)

        // Now register explicitly
        vm.createWorker()
        assertEquals(1, adminApi.created.size)
        assertTrue(vm.isWorkerRegistered())

        // Now sharing/copying succeeds without re-creating
        val proceedAfter = kotlinx.coroutines.runBlocking { vm.ensureWorkerCreated() }
        assertTrue(proceedAfter)
        assertEquals(1, adminApi.created.size)
    }

    @Test
    fun `a taken login keeps the account out and says why`() {
        val adminApi = FakeAdminApi(fixtureUsers, createStatus = 409)
        val authRepo = AuthRepository(FakeAuthApi(role = "Administrador", userId = 1), FakeTokenStore("Administrador"), Json)
        val vm = ShareAppViewModel(authRepo, UsersRepository(adminApi))

        vm.onQuickNameChange("Ana Admin")
        vm.createWorker()

        assertEquals(ShareCreateError.DUPLICATED, vm.state.value.createError)
        assertFalse(vm.state.value.createSuccess)
        assertNull(vm.state.value.createdUsername)
    }

    @Test
    fun `a name without letters asks for a login instead of creating an empty one`() {
        val adminApi = FakeAdminApi(fixtureUsers)
        val authRepo = AuthRepository(FakeAuthApi(role = "Administrador", userId = 1), FakeTokenStore("Administrador"), Json)
        val vm = ShareAppViewModel(authRepo, UsersRepository(adminApi))

        vm.createWorker()

        assertEquals(ShareCreateError.NAME_REQUIRED, vm.state.value.createError)
        assertFalse(vm.state.value.createSuccess)
        assertTrue(adminApi.created.isEmpty())
    }
}

private class FakeAdminApi(
    private val rows: List<AdminUserResponseUser>,
    /** What `POST /api/admin/users` gets, so the test can read the role and password that were sent. */
    val created: MutableList<JsonObject> = mutableListOf(),
    private val createStatus: Int = 201,
) : AdminApi {
    override suspend fun users(): Response<AdminUsersResponse> = Response.success(AdminUsersResponse(rows))
    override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> {
        if (createStatus == 409) {
            return Response.error(
                409,
                okhttp3.ResponseBody.Companion.create(
                    "application/json".toMediaTypeOrNull(),
                    "{\"error\":\"USERNAME_TAKEN\"}",
                ),
            )
        }
        created.add(body)
        return Response.success(AdminUserResponse(rows.first()))
    }
    override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> = error("unused")
    override suspend fun deleteUser(id: Int): Response<Unit> = error("unused")
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
    override suspend fun vehicles(): Response<AdminVehiclesResponse> = error("unused")
    override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> = error("unused")
    override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> = error("unused")
    override suspend fun deleteVehicle(id: Int): Response<Unit> = error("unused")
}

private class FakeAuthApi(val role: String, val userId: Int) : AuthApi {
    override suspend fun profile(): Response<ProfileResponse> = Response.success(
        ProfileResponse(user = ProfileResponseUser(userId, "username", "Display", role)),
    )
    override suspend fun me(): Response<ProfileResponse> = profile()
    override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("unused")
    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("unused")
    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("unused")
    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("unused")
    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("unused")
    override suspend fun register(body: RegisterRequest): Response<RegisterResponse> = error("unused")
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error("unused")
}

private class FakeTokenStore(private val roleName: String) : TokenStore {
    override fun accessToken(): String = "token"
    override fun refreshToken(): String = "refresh"
    override fun role(): String = roleName
    override fun save(accessToken: String, refreshToken: String) {}
    override fun saveRole(role: String) {}
    override fun clear() {}
}
