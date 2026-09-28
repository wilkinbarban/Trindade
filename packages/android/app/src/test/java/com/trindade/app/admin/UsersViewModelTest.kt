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
import com.trindade.app.contract.models.AdminUserResponseUser
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsersViewModelTest {
    private val adminUser = user(42, "admin", "Administrador Principal", 1, "Administrador", 1)
    private val workerUser = user(101, "carlos", "Carlos Trabalhador", 2, "Trabalhador", 1)
    private val inactiveUser = user(102, "maria", "Maria Inativa", 2, "Trabalhador", 0)
    private val fixtureUsers = listOf(adminUser, workerUser, inactiveUser)

    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun `loads users and authenticated identity for exact Administrador`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val admin = viewModel(api, role = "Administrador", userId = 42)
        admin.load()

        assertEquals(fixtureUsers, admin.state.value.users)
        assertEquals(42, admin.state.value.currentUserId)
        assertEquals("Administrador", admin.state.value.role)
        assertTrue(admin.state.value.isAdmin)
        assertFalse(admin.state.value.loading)
        assertNull(admin.state.value.error)
        assertEquals(1, api.reads)

        // Admin can edit anyone (including self)
        assertTrue(admin.state.value.canEdit(adminUser))
        assertTrue(admin.state.value.canEdit(workerUser))

        // Admin can toggle and delete others, but NOT self
        assertTrue(admin.state.value.canToggle(workerUser))
        assertTrue(admin.state.value.canDelete(workerUser))
        assertFalse(admin.state.value.canToggle(adminUser))
        assertFalse(admin.state.value.canDelete(adminUser))
    }

    @Test fun `fails closed for worker, unknown or null role without issuing a read or write`() {
        // Worker session
        val workerApi = UserAdminApi(rows = fixtureUsers)
        val worker = viewModel(workerApi, role = "Trabalhador", userId = 101)
        worker.load()

        assertEquals(0, workerApi.reads)
        assertEquals(emptyList<AdminUserResponseUser>(), worker.state.value.users)
        assertFalse(worker.state.value.isAdmin)
        assertFalse(worker.state.value.canEdit(workerUser))
        assertFalse(worker.state.value.canToggle(workerUser))
        assertFalse(worker.state.value.canDelete(workerUser))

        worker.edit(workerUser)
        assertNull(worker.state.value.editingId)
        worker.toggle(workerUser)
        worker.requestDelete(workerUser)
        assertNull(worker.state.value.deleteTarget)
        worker.confirmDelete()
        worker.onUsernameChange("hacker")
        worker.onDisplayNameChange("Hacker")
        worker.onPasswordChange("senha1234")
        worker.save()
        assertEquals(0, workerApi.creates + workerApi.updates + workerApi.deletes)

        // Unknown role session
        val unknownApi = UserAdminApi(rows = fixtureUsers)
        val unknown = viewModel(unknownApi, role = "Gerente", userId = 999)
        unknown.load()

        assertEquals(0, unknownApi.reads)
        assertEquals(emptyList<AdminUserResponseUser>(), unknown.state.value.users)
        assertFalse(unknown.state.value.isAdmin)
        unknown.onUsernameChange("tentativa")
        unknown.save()
        assertEquals(0, unknownApi.creates + unknownApi.updates + unknownApi.deletes)

        // Unauthenticated / null profile
        val nullProfileApi = UserAdminApi(rows = fixtureUsers)
        val nullSession = viewModel(nullProfileApi, role = null, userId = null)
        nullSession.load()

        assertEquals(0, nullProfileApi.reads)
        assertEquals(emptyList<AdminUserResponseUser>(), nullSession.state.value.users)
        assertFalse(nullSession.state.value.isAdmin)
        assertEquals(UsersViewModel.UNREACHABLE, nullSession.state.value.error)
        nullSession.save()
        assertEquals(0, nullProfileApi.creates + nullProfileApi.updates + nullProfileApi.deletes)
    }

    @Test fun `null profile clears authorization and blocks save toggle and delete`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val profileApi = UserProfileApi(role = "Administrador", userId = 42)
        val model = UsersViewModel(
            UsersRepository(api),
            AuthRepository(profileApi, UserStore(), Json),
        )

        // 1. Initial load as Admin succeeds
        model.load()
        assertEquals("Administrador", model.state.value.role)
        assertEquals(42, model.state.value.currentUserId)
        assertTrue(model.state.value.isAdmin)
        assertEquals(1, api.reads)

        // 2. Second load where profile returns null (e.g. session lost / unauthenticated)
        profileApi.role = null
        profileApi.userId = null
        model.load()

        // State must lose authorization entirely
        assertNull(model.state.value.role)
        assertNull(model.state.value.currentUserId)
        assertFalse(model.state.value.isAdmin)
        assertEquals(UsersViewModel.UNREACHABLE, model.state.value.error)
        assertEquals(emptyList<AdminUserResponseUser>(), model.state.value.users)

        // 3. Mutation attempts issue NO repository calls
        model.onUsernameChange("hacker")
        model.onDisplayNameChange("Hacker")
        model.onPasswordChange("senha1234")
        model.save()
        assertEquals(0, api.creates)
        assertEquals(0, api.updates)

        model.toggle(workerUser)
        assertEquals(0, api.updates)

        model.requestDelete(workerUser)
        assertNull(model.state.value.deleteTarget)
        model.confirmDelete()
        assertEquals(0, api.deletes)
    }

    @Test fun `in-flight load guards toggle and confirmDelete until reload completes`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val profileApi = UserProfileApi(role = "Administrador", userId = 42)
        val model = UsersViewModel(
            UsersRepository(api),
            AuthRepository(profileApi, UserStore(), Json),
        )

        // 1. Initial load as Admin completes
        model.load()
        assertEquals(1, api.reads)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.isAdmin)

        // 2. Begin a second load that suspends in-flight
        val gate = CompletableDeferred<Unit>()
        profileApi.profileGate = gate
        model.load()
        assertTrue(model.state.value.loading)
        assertEquals("Administrador", model.state.value.role)

        // 3. Mutating calls during the in-flight window must be rejected
        model.toggle(workerUser)
        assertEquals(0, api.updates)

        model.requestDelete(workerUser)
        assertNull(model.state.value.deleteTarget)
        model.confirmDelete()
        assertEquals(0, api.deletes)

        model.save()
        assertEquals(0, api.creates)

        // 4. Complete the in-flight load with valid admin profile
        gate.complete(Unit)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.isAdmin)
        assertEquals(2, api.reads)

        // 5. Mutating calls succeed now that reload finished
        model.toggle(workerUser)
        assertEquals(1, api.updates)
        assertEquals(101, api.lastUpdateId)

        model.requestDelete(workerUser)
        assertEquals(workerUser, model.state.value.deleteTarget)
        model.confirmDelete()
        assertEquals(1, api.deletes)
        assertEquals(101, api.lastDeleteId)
    }

    @Test fun `self-protection rules prevent self-toggle, self-delete, and self-username-role-status changes`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val admin = viewModel(api, role = "Administrador", userId = 42)
        admin.load()

        // 1. Self toggle is refused locally without network call
        assertFalse(admin.state.value.canToggle(adminUser))
        admin.toggle(adminUser)
        assertEquals(0, api.updates)

        // 2. Self delete is refused locally without network call
        assertFalse(admin.state.value.canDelete(adminUser))
        admin.requestDelete(adminUser)
        assertNull(admin.state.value.deleteTarget)
        admin.confirmDelete()
        assertEquals(0, api.deletes)

        // 3. Edit self: username editing is disabled, role and status change controls are disabled
        admin.edit(adminUser)
        assertEquals(42, admin.state.value.editingId)
        assertTrue(admin.state.value.isSelfEditing)
        assertFalse(admin.state.value.canEditUsername)
        assertFalse(admin.state.value.canChangeRole)
        assertFalse(admin.state.value.canChangeStatus)

        // Attempting to change username, role or status on self is ignored
        admin.onUsernameChange("novo_admin")
        assertEquals("admin", admin.state.value.username)
        admin.onRoleIdChange(2)
        assertEquals(1, admin.state.value.roleId)
        admin.onIsActiveChange(0)
        assertEquals(1, admin.state.value.isActive)
    }

    @Test fun `create validation enforces required username, display name, password and 4-char minimum`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        // Missing username
        model.onUsernameChange("   ")
        model.onDisplayNameChange("Novo Usuário")
        model.onPasswordChange("senha1234")
        model.save()
        assertEquals(UsersViewModel.USERNAME, model.state.value.error)
        assertEquals(0, api.creates)

        // Missing display name
        model.onUsernameChange("novousuario")
        model.onDisplayNameChange("   ")
        model.save()
        assertEquals(UsersViewModel.DISPLAY_NAME, model.state.value.error)
        assertEquals(0, api.creates)

        // Missing password on create
        model.onDisplayNameChange("Novo Usuário")
        model.onPasswordChange("")
        model.save()
        assertEquals(UsersViewModel.PASSWORD, model.state.value.error)
        assertEquals(0, api.creates)

        // Password shorter than 4 characters
        model.onPasswordChange("123")
        model.save()
        assertEquals(UsersViewModel.PASSWORD_TOO_SHORT, model.state.value.error)
        assertEquals(0, api.creates)

        // Valid create (4 characters passes backend minimum)
        model.onPasswordChange("1234")
        model.onRoleIdChange(2)
        model.save()
        assertEquals(1, api.creates)
        assertNotNull(api.lastCreateBody)
        assertEquals("novousuario", api.lastCreateBody!!["username"]?.jsonPrimitive?.content)
        assertEquals("Novo Usuário", api.lastCreateBody!!["display_name"]?.jsonPrimitive?.content)
        assertEquals("1234", api.lastCreateBody!!["password"]?.jsonPrimitive?.content)
        assertEquals("2", api.lastCreateBody!!["role_id"]?.jsonPrimitive?.content)
    }

    @Test fun `edit self updates display name and password while omitting username, role and status`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        model.edit(adminUser)
        assertTrue(model.state.value.isSelfEditing)

        // Update display name and password
        model.onDisplayNameChange("Admin Atualizado")
        model.onPasswordChange("novaSenhaSegura1")
        model.save()

        assertEquals(1, api.updates)
        assertEquals(42, api.lastUpdateId)
        assertNotNull(api.lastUpdateBody)
        assertEquals("Admin Atualizado", api.lastUpdateBody!!["display_name"]?.jsonPrimitive?.content)
        assertEquals("novaSenhaSegura1", api.lastUpdateBody!!["password"]?.jsonPrimitive?.content)
        // Self-protection: username, role_id, and is_active must not be sent
        val usernameVal = api.lastUpdateBody!!["username"]
        assertTrue(usernameVal == null || usernameVal is JsonNull)
        val roleIdVal = api.lastUpdateBody!!["role_id"]
        assertTrue(roleIdVal == null || roleIdVal is JsonNull)
        val isActiveVal = api.lastUpdateBody!!["is_active"]
        assertTrue(isActiveVal == null || isActiveVal is JsonNull)
    }

    @Test fun `edit other user sends updated fields and omits password when empty`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        model.edit(workerUser)
        assertFalse(model.state.value.isSelfEditing)
        assertTrue(model.state.value.canEditUsername)
        assertTrue(model.state.value.canChangeRole)
        assertTrue(model.state.value.canChangeStatus)

        model.onUsernameChange("carlos_novo")
        model.onDisplayNameChange("Carlos Silva")
        model.onRoleIdChange(1)
        model.onIsActiveChange(0)
        // Password left empty
        assertEquals("", model.password.value)
        model.save()

        assertEquals(1, api.updates)
        assertEquals(101, api.lastUpdateId)
        assertNotNull(api.lastUpdateBody)
        assertEquals("carlos_novo", api.lastUpdateBody!!["username"]?.jsonPrimitive?.content)
        assertEquals("Carlos Silva", api.lastUpdateBody!!["display_name"]?.jsonPrimitive?.content)
        assertEquals("1", api.lastUpdateBody!!["role_id"]?.jsonPrimitive?.content)
        assertEquals("0", api.lastUpdateBody!!["is_active"]?.jsonPrimitive?.content)
        assertFalse("Unchanged password must be omitted from update payload", api.lastUpdateBody!!.containsKey("password"))
    }

    @Test fun `password is kept out of public UiState and state textual representation`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        // 1. Assert UiState has no field named 'password'
        val declaredFieldNames = UsersViewModel.UiState::class.java.declaredFields.map { it.name }
        assertFalse(
            "UiState must not contain a password field",
            declaredFieldNames.contains("password"),
        )

        // 2. Type secret password and assert it is exposed via narrow accessor but absent from UiState toString
        val secret = "SecretP@ssword987!"
        model.onPasswordChange(secret)
        assertEquals(secret, model.password.value)

        val stateDump = model.state.value.toString()
        assertFalse(
            "State dump must not contain the plaintext password",
            stateDump.contains(secret),
        )

        // 3. Clear on cancel
        model.cancelEdit()
        assertEquals("", model.password.value)
        assertFalse(model.state.value.toString().contains(secret))
    }

    @Test fun `password is cleared from state after successful write and on cancel`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        // 1. Password cleared after successful create
        model.onUsernameChange("novousuario")
        model.onDisplayNameChange("Novo")
        model.onPasswordChange("segredo123")
        assertEquals("segredo123", model.password.value)

        model.save()
        assertEquals("", model.password.value)
        assertNull(model.state.value.editingId)

        // 2. Password cleared on cancelEdit
        model.edit(workerUser)
        model.onPasswordChange("novaSenha456")
        assertEquals("novaSenha456", model.password.value)

        model.cancelEdit()
        assertEquals("", model.password.value)
        assertNull(model.state.value.editingId)
    }

    @Test fun `advisory warning surfaces on weak password but does not block saving when minimum length met`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        model.onUsernameChange("testuser")
        model.onDisplayNameChange("Test User")

        // 1. Empty password: no warning
        model.onPasswordChange("")
        assertFalse(model.state.value.hasPasswordWarning)
        assertNull(model.state.value.passwordWarning)

        // 2. Short password (< 4 chars): warning active AND save is blocked
        model.onPasswordChange("abc")
        assertTrue(model.state.value.hasPasswordWarning)
        assertEquals(UsersViewModel.PASSWORD_WARNING, model.state.value.passwordWarning)
        model.save()
        assertEquals(UsersViewModel.PASSWORD_TOO_SHORT, model.state.value.error)
        assertEquals(0, api.creates)

        // 3. 4-character password: meets backend minimum, warning remains active, but save is NOT blocked!
        model.onPasswordChange("pass")
        assertTrue(model.state.value.hasPasswordWarning)
        model.save()
        assertNull(model.state.value.error)
        assertEquals(1, api.creates)

        // 4. 8-character password without uppercase or digit: warning active, save succeeds
        model.onPasswordChange("password")
        assertTrue(model.state.value.hasPasswordWarning)

        // 5. Strong password (>= 8 chars, uppercase, digit): warning is cleared!
        model.onPasswordChange("Password123")
        assertFalse(model.state.value.hasPasswordWarning)
        assertNull(model.state.value.passwordWarning)
    }

    @Test fun `delete confirmation sequence prevents direct delete bypass`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        // 1. Assert that delete is not exposed as a public method on ViewModel
        val publicMethodNames = UsersViewModel::class.java.methods.map { it.name }
        assertFalse(
            "UsersViewModel must not expose a public delete method; delete must go through request/confirm sequence",
            publicMethodNames.contains("delete"),
        )

        // 2. Request delete: sets target, issues 0 deletes
        model.requestDelete(workerUser)
        assertEquals(workerUser, model.state.value.deleteTarget)
        assertEquals(0, api.deletes)

        // 3. Cancel delete: clears target, issues 0 deletes
        model.cancelDelete()
        assertNull(model.state.value.deleteTarget)
        assertEquals(0, api.deletes)

        // 4. Confirm delete without active target: issues 0 deletes
        model.confirmDelete()
        assertEquals(0, api.deletes)

        // 5. Request then confirm: issues exactly 1 delete
        model.requestDelete(workerUser)
        assertEquals(workerUser, model.state.value.deleteTarget)
        model.confirmDelete()
        assertNull(model.state.value.deleteTarget)
        assertEquals(1, api.deletes)
        assertEquals(101, api.lastDeleteId)
    }

    @Test fun `refusals and unreachable writes differ and successful writes reload`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()
        assertEquals(1, api.reads)

        model.onUsernameChange("novo")
        model.onDisplayNameChange("Nome")
        model.onPasswordChange("pass1234")

        // Refusal 403
        api.writeResult = UserWriteResult.Refused(403)
        model.save()
        assertFalse(model.state.value.saving)
        assertEquals(UsersViewModel.REFUSED, model.state.value.error)
        assertEquals(403, model.state.value.refusedStatus)

        // Unreachable transport
        api.writeResult = UserWriteResult.Unreachable
        model.onDisplayNameChange("Nome 2")
        model.save()
        assertFalse(model.state.value.saving)
        assertEquals(UsersViewModel.UNREACHABLE, model.state.value.error)
        assertNull(model.state.value.refusedStatus)

        // Successful write reloads catalog
        api.writeResult = UserWriteResult.Saved(
            user(200, "novo", "Nome 2", 2, "Trabalhador", 1)
        )
        model.onDisplayNameChange("Nome 3")
        model.save()
        assertFalse(model.state.value.saving)
        assertNull(model.state.value.error)
        assertNull(model.state.value.refusedStatus)
        assertEquals(2, api.reads)
    }

    @Test fun `toggle sends inverted is_active flag`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        // Active user (isActive = 1) -> toggle sends 0
        model.toggle(workerUser)
        assertEquals(1, api.updates)
        assertEquals(101, api.lastUpdateId)
        assertEquals("0", api.lastUpdateBody!!["is_active"]?.jsonPrimitive?.content)

        // Inactive user (isActive = 0) -> toggle sends 1
        model.toggle(inactiveUser)
        assertEquals(2, api.updates)
        assertEquals(102, api.lastUpdateId)
        assertEquals("1", api.lastUpdateBody!!["is_active"]?.jsonPrimitive?.content)
    }

    @Test fun `newer load cancels an in-flight older user read`() {
        val api = UserAdminApi(rows = fixtureUsers)
        api.firstRead = CompletableDeferred()
        val model = viewModel(api, role = "Administrador", userId = 42)

        model.load()
        model.load()
        assertEquals(2, api.reads)
        assertEquals(fixtureUsers, model.state.value.users)

        // Resolving first read does not overwrite state
        api.firstRead!!.complete(listOf(user(999, "stale", "Stale", 2, "Trabalhador", 1)))
        assertEquals(fixtureUsers, model.state.value.users)
    }

    @Test fun `form changes clear errors and refused status`() {
        val api = UserAdminApi(rows = fixtureUsers)
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        model.onUsernameChange("user")
        model.onDisplayNameChange("Name")
        model.onPasswordChange("pass")

        api.writeResult = UserWriteResult.Refused(400)
        model.save()
        assertEquals(400, model.state.value.refusedStatus)
        assertEquals(UsersViewModel.REFUSED, model.state.value.error)

        model.onDisplayNameChange("Name Updated")
        assertNull(model.state.value.error)
        assertNull(model.state.value.refusedStatus)
    }

    @Test fun `unreachable read sets unreachable error and empty list`() {
        val api = UserAdminApi(rows = fixtureUsers)
        api.failRead = true
        val model = viewModel(api, role = "Administrador", userId = 42)
        model.load()

        assertFalse(model.state.value.loading)
        assertEquals(UsersViewModel.UNREACHABLE, model.state.value.error)
        assertEquals(emptyList<AdminUserResponseUser>(), model.state.value.users)
    }

    private fun viewModel(
        api: UserAdminApi,
        role: String? = "Trabalhador",
        userId: Int? = 42,
    ) = UsersViewModel(
        UsersRepository(api),
        AuthRepository(UserProfileApi(role, userId), UserStore(), Json),
    )

    private fun user(
        id: Int,
        username: String,
        displayName: String,
        roleId: Int,
        roleName: String,
        active: Int,
    ) = AdminUserResponseUser(id, username, displayName, roleId, roleName, active, "now")
}

private class UserAdminApi(var rows: List<AdminUserResponseUser> = emptyList()) : AdminApi {
    var reads = 0
    var creates = 0
    var updates = 0
    var deletes = 0
    var lastCreateBody: JsonObject? = null
    var lastUpdateBody: JsonObject? = null
    var lastUpdateId: Int? = null
    var lastDeleteId: Int? = null
    var firstRead: CompletableDeferred<List<AdminUserResponseUser>>? = null
    var failRead = false
    var writeResult: UserWriteResult = UserWriteResult.Saved(
        AdminUserResponseUser(10, "newuser", "New User", 2, "Trabalhador", 1, "now")
    )

    override suspend fun users(): Response<AdminUsersResponse> {
        reads++
        if (failRead) throw java.io.IOException("offline")
        val result = if (reads == 1 && firstRead != null) firstRead!!.await() else rows
        return Response.success(AdminUsersResponse(result))
    }

    override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> {
        creates++
        lastCreateBody = body
        return userResponse()
    }

    override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> {
        updates++
        lastUpdateId = id
        lastUpdateBody = body
        return userResponse()
    }

    override suspend fun deleteUser(id: Int): Response<Unit> {
        deletes++
        lastDeleteId = id
        return when (val result = writeResult) {
            is UserWriteResult.Saved -> Response.success(Unit)
            is UserWriteResult.Refused -> Response.error(result.status, "".toResponseBody(null))
            UserWriteResult.Unreachable -> throw java.io.IOException("offline")
        }
    }

    private fun userResponse(): Response<AdminUserResponse> = when (val result = writeResult) {
        is UserWriteResult.Saved -> Response.success(
            AdminUserResponse(result.user ?: rows.firstOrNull() ?: AdminUserResponseUser(1, "A", "B", 2, "Trabalhador", 1, "now"))
        )
        is UserWriteResult.Refused -> Response.error(result.status, "".toResponseBody(null))
        UserWriteResult.Unreachable -> throw java.io.IOException("offline")
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
    override suspend fun vehicles(): Response<AdminVehiclesResponse> = error("unused")
    override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> = error("unused")
    override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> = error("unused")
    override suspend fun deleteVehicle(id: Int): Response<Unit> = error("unused")
}

private class UserProfileApi(
    var role: String?,
    var userId: Int?,
    var fail: Boolean = false,
) : AuthApi {
    var profileGate: CompletableDeferred<Unit>? = null

    override suspend fun profile(): Response<ProfileResponse> {
        profileGate?.await()
        if (fail) throw java.io.IOException("offline")
        val r = role ?: return Response.error(401, "".toResponseBody(null))
        val id = userId ?: return Response.error(401, "".toResponseBody(null))
        return Response.success(ProfileResponse(user = ProfileResponseUser(id, "admin", "Admin", r)))
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

private class UserStore : TokenStore {
    override fun accessToken() = "token"
    override fun refreshToken() = "refresh"
    override fun role() = null
    override fun save(accessToken: String, refreshToken: String) = Unit
    override fun saveRole(role: String) = Unit
    override fun clear() = Unit
}
