package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.AdminUserResponseUser
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
import com.trindade.app.contract.models.UpdateAdminUserRequest
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

class UsersViewModelTest {

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
            id = 1,
            username = "admin",
            displayName = "Administrador Principal",
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
    fun `init loads users from repository for administrator and resolves currentUserId`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.isAdmin)
        assertEquals(1, state.currentUserId)
        assertEquals(3, state.users.size)
        assertNull(state.message)
    }

    @Test
    fun `init rejects non-administrators with permission error`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.WORKER, userId = 42)
        val vm = UsersViewModel(repo, auth)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertFalse(state.isAdmin)
        assertTrue(state.users.isEmpty())
        assertTrue(state.isError)
        assertEquals("Acesso restrito a administradores.", state.message)
    }

    @Test
    fun `self-protection rules prevent self role change, self deactivation, and self delete`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val state = vm.uiState.value
        val selfUser = checkNotNull(state.users.find { it.id == 1 })
        val otherUser = checkNotNull(state.users.find { it.id == 42 })

        // Self user checks
        assertTrue(state.isSelf(selfUser))
        assertTrue(state.canEdit(selfUser))
        assertFalse("Self user must not be toggleable", state.canToggleActive(selfUser))
        assertFalse("Self user must not be deletable", state.canDelete(selfUser))

        // Other user checks
        assertFalse(state.isSelf(otherUser))
        assertTrue(state.canEdit(otherUser))
        assertTrue(state.canToggleActive(otherUser))
        assertTrue(state.canDelete(otherUser))
    }

    @Test
    fun `openEditForm for self-user restricts role and status editing`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val selfUser = checkNotNull(vm.uiState.value.users.find { it.id == 1 })
        vm.openEditForm(selfUser)

        val form = vm.uiState.value.form
        assertNotNull(form)
        assertTrue(form!!.isEditing)
        assertTrue(form.isSelf)
        assertEquals(1, form.roleId)

        // Attempting to change restricted fields should be ignored for self
        vm.onRoleIdChanged(2)
        assertEquals(1, vm.uiState.value.form?.roleId)

        vm.onIsActiveChanged(0)
        assertEquals(1, vm.uiState.value.form?.isActive)

        vm.onUsernameChanged("new_username")
        assertEquals("admin", vm.uiState.value.form?.username)

        // Display name can be edited
        vm.onDisplayNameChanged("Admin Modificado")
        assertEquals("Admin Modificado", vm.uiState.value.form?.displayName)
        assertTrue(vm.uiState.value.form!!.canSubmit)
    }

    @Test
    fun `saveUser for self-user sends only displayName and password without role or status`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val selfUser = checkNotNull(vm.uiState.value.users.find { it.id == 1 })
        vm.openEditForm(selfUser)
        vm.onDisplayNameChanged("Admin Atualizado")
        vm.onPasswordChanged("newpassword123")
        vm.saveUser()

        assertEquals(1, api.updatedUserRequests.size)
        val (updatedId, request) = api.updatedUserRequests.first()
        assertEquals(1, updatedId)
        assertEquals("Admin Atualizado", request.displayName)
        assertEquals("newpassword123", request.password)
        assertNull("Self update must omit role_id", request.roleId)
        assertNull("Self update must omit is_active", request.isActive)
        assertNull(vm.uiState.value.form)
        assertEquals("Usuário atualizado com sucesso.", vm.uiState.value.message)
    }

    @Test
    fun `saveUser for other-user sends updated role and active status`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val otherUser = checkNotNull(vm.uiState.value.users.find { it.id == 42 })
        vm.openEditForm(otherUser)

        assertFalse(vm.uiState.value.form!!.isSelf)
        vm.onDisplayNameChanged("Operador Promovido")
        vm.onRoleIdChanged(1)
        vm.onIsActiveChanged(1)
        vm.saveUser()

        assertEquals(1, api.updatedUserRequests.size)
        val (updatedId, request) = api.updatedUserRequests.first()
        assertEquals(42, updatedId)
        assertEquals("Operador Promovido", request.displayName)
        assertEquals(UpdateAdminUserRequest.RoleId._1, request.roleId)
        assertEquals(UpdateAdminUserRequest.IsActive._1, request.isActive)
        assertNull(vm.uiState.value.form)
    }

    @Test
    fun `openCreateForm validates required fields and password length`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        vm.openCreateForm()
        val form = vm.uiState.value.form
        assertNotNull(form)
        assertFalse(form!!.isEditing)
        assertEquals(2, form.roleId)
        assertFalse("Cannot submit empty form", form.canSubmit)

        vm.onUsernameChanged("operador_novo")
        assertFalse("Password and display name still required", vm.uiState.value.form!!.canSubmit)

        vm.onDisplayNameChanged("Operador Novo")
        assertFalse("Password still required", vm.uiState.value.form!!.canSubmit)

        vm.onPasswordChanged("123") // 3 chars < 4
        assertFalse("Password must be >= 4 chars", vm.uiState.value.form!!.canSubmit)

        vm.onPasswordChanged("1234") // 4 chars
        assertTrue(vm.uiState.value.form!!.canSubmit)

        vm.saveUser()
        assertEquals(1, api.createdUserRequests.size)
        val created = api.createdUserRequests.first()
        assertEquals("operador_novo", created.username)
        assertEquals("Operador Novo", created.displayName)
        assertEquals("1234", created.password)
        assertEquals(2, created.roleId.value)
        assertNull(vm.uiState.value.form)
        assertEquals("Usuário criado com sucesso.", vm.uiState.value.message)
    }

    @Test
    fun `toggleUserActive flips active status for non-self user`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val activeUser = checkNotNull(vm.uiState.value.users.find { it.id == 42 && it.isActive == 1 })
        vm.toggleUserActive(activeUser)

        assertEquals(1, api.updatedUserRequests.size)
        val (id, request) = api.updatedUserRequests.first()
        assertEquals(42, id)
        assertEquals(UpdateAdminUserRequest.IsActive._0, request.isActive)
    }

    @Test
    fun `delete confirmation flow and refusal handling`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val targetUser = checkNotNull(vm.uiState.value.users.find { it.id == 42 })

        // Request delete
        vm.requestDelete(targetUser)
        assertEquals(targetUser, vm.uiState.value.deleteConfirmUser)

        // Cancel delete
        vm.cancelDelete()
        assertNull(vm.uiState.value.deleteConfirmUser)

        // Request delete and confirm with constraint error 400
        api.deleteUserResponse = Response.error(400, "{\"error\":\"FK constraint\"}".toResponseBody(null))
        vm.requestDelete(targetUser)
        vm.confirmDelete()

        assertNull(vm.uiState.value.deleteConfirmUser)
        assertTrue(vm.uiState.value.isError)
        assertEquals(
            "Não é possível excluir o usuário pois ele possui registros relacionados.",
            vm.uiState.value.message,
        )
    }

    @Test
    fun `handleBack dismisses dialog then form then returns false`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        // Nothing open
        assertFalse(vm.handleBack())

        // Form open
        vm.openCreateForm()
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.form)

        // Dialog open
        val targetUser = checkNotNull(vm.uiState.value.users.find { it.id == 42 })
        vm.requestDelete(targetUser)
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.deleteConfirmUser)
    }

    @Test
    fun `update user 403 for non-self user surfaces generic permission denial`() {
        val api = FakeAdminApi()
        api.updateUserResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null))
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val targetUser = checkNotNull(vm.uiState.value.users.find { it.id == 42 })
        vm.openEditForm(targetUser)
        vm.onDisplayNameChanged("Outro Usuário")
        vm.saveUser()

        assertTrue(vm.uiState.value.isError)
        assertEquals("Acesso negado: permissão insuficiente.", vm.uiState.value.message)
    }

    @Test
    fun `update user 403 for self user surfaces self-profile restriction message`() {
        val api = FakeAdminApi()
        api.updateUserResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null))
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)
        val vm = UsersViewModel(repo, auth)

        val selfUser = checkNotNull(vm.uiState.value.users.find { it.id == 1 })
        vm.openEditForm(selfUser)
        vm.onDisplayNameChanged("Admin Modificado")
        vm.saveUser()

        assertTrue(vm.uiState.value.isError)
        assertEquals("Não é possível alterar seu próprio perfil de acesso. Atualize apenas seus dados e senha.", vm.uiState.value.message)
    }

    @Test
    fun `worker cannot save user and makes no write call`() {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)
        val auth = createAuthRepository(role = RolePolicy.WORKER, userId = 42)
        val vm = UsersViewModel(repo, auth)

        vm.openCreateForm()
        assertNull(vm.uiState.value.form)

        vm.saveUser()
        assertTrue(api.createdUserRequests.isEmpty())
        assertTrue(api.updatedUserRequests.isEmpty())
    }
}
