package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.contract.models.AdminTaskResponseTask
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.CreateAdminTaskRequest
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
import com.trindade.app.contract.models.UpdateAdminTaskRequest
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.AuthApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
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

class TasksViewModelTest {

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
            id = 42,
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
        userId: Int = 42,
    ): AuthRepository {
        val tokenStore = FakeTokenStore(role = role)
        val authApi = FakeAuthApi(
            user = ProfileResponseUser(
                id = userId,
                username = "test_user",
                displayName = "Test User",
                role = role,
            ),
        )
        return AuthRepository(authApi, tokenStore, Json { ignoreUnknownKeys = true })
    }

    @Test
    fun `initial load populates categories, tasks, and operator role`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)

        val viewModel = TasksViewModel(repository, authRepository)
        val state = viewModel.uiState.value

        assertFalse(state.loading)
        assertTrue(state.isAdmin)
        assertEquals(2, state.categories.size)
        assertEquals(2, state.tasks.size)
        assertEquals(1, state.currentUserId)
    }

    @Test
    fun `worker permissions permit editing and deleting only own tasks`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val workerId = 10
        val authRepository = createAuthRepository(role = RolePolicy.WORKER, userId = workerId)

        val viewModel = TasksViewModel(repository, authRepository)
        val state = viewModel.uiState.value

        val ownTask = AdminTasksResponseTasksInner(
            id = 101,
            categoryId = 1,
            namePt = "Minha tarefa",
            nameEs = "Mi tarea",
            temperatureReadings = 1,
            isActive = 1,
            createdByUserId = workerId,
            createdAt = "2026-09-22T00:00:00Z",
            taskType = AdminTasksResponseTasksInner.TaskType.check,
            categoryName = "Check Cat",
        )

        val otherTask = AdminTasksResponseTasksInner(
            id = 102,
            categoryId = 1,
            namePt = "Outra tarefa",
            nameEs = "Otra tarea",
            temperatureReadings = 1,
            isActive = 1,
            createdByUserId = 999,
            createdAt = "2026-09-22T00:00:00Z",
            taskType = AdminTasksResponseTasksInner.TaskType.check,
            categoryName = "Check Cat",
        )

        assertTrue(state.canEdit(ownTask))
        assertTrue(state.canDelete(ownTask))
        assertFalse(state.canEdit(otherTask))
        assertFalse(state.canDelete(otherTask))
        assertFalse(state.canToggleActive(ownTask))
        assertFalse(state.canToggleActive(otherTask))
    }

    @Test
    fun `admin can edit, delete, and toggle any task`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN, userId = 1)

        val viewModel = TasksViewModel(repository, authRepository)
        val state = viewModel.uiState.value

        val anyTask = AdminTasksResponseTasksInner(
            id = 102,
            categoryId = 1,
            namePt = "Tarefa",
            nameEs = "Tarea",
            temperatureReadings = 1,
            isActive = 1,
            createdByUserId = 999,
            createdAt = "2026-09-22T00:00:00Z",
            taskType = AdminTasksResponseTasksInner.TaskType.check,
            categoryName = "Check Cat",
        )

        assertTrue(state.canEdit(anyTask))
        assertTrue(state.canDelete(anyTask))
        assertTrue(state.canToggleActive(anyTask))
    }

    @Test
    fun `openCreateForm initializes form and saveTask creates task successfully`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        viewModel.openCreateForm()

        assertNotNull(viewModel.uiState.value.form)
        assertEquals(1, viewModel.uiState.value.form?.categoryId)

        viewModel.onNamePtChanged("Nova Tarefa")
        viewModel.onNameEsChanged("Nueva Tarea")
        viewModel.saveTask()

        assertNull(viewModel.uiState.value.form)
        assertEquals("Tarefa criada com sucesso.", viewModel.uiState.value.message)
        assertEquals(3, viewModel.uiState.value.tasks.size)
        assertEquals(1, fakeApi.createdRequests.size)
        assertEquals("Nova Tarefa", fakeApi.createdRequests.first().namePt)
    }

    @Test
    fun `openEditForm populates fields and saveTask updates task`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        val existing = viewModel.uiState.value.tasks.first()

        viewModel.openEditForm(existing)
        assertEquals(existing.id, viewModel.uiState.value.form?.taskId)
        assertEquals(existing.namePt, viewModel.uiState.value.form?.namePt)

        viewModel.onNamePtChanged("Nome Atualizado")
        viewModel.saveTask()

        assertNull(viewModel.uiState.value.form)
        assertEquals("Tarefa atualizada com sucesso.", viewModel.uiState.value.message)
        assertEquals(1, fakeApi.updatedRequests.size)
        assertEquals("Nome Atualizado", fakeApi.updatedRequests.first().second.namePt)
    }

    @Test
    fun `toggleTaskActive calls updateTask with flipped status`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN)

        val viewModel = TasksViewModel(repository, authRepository)
        val task = viewModel.uiState.value.tasks.first() // isActive = 1

        viewModel.toggleTaskActive(task)

        assertEquals(1, fakeApi.updatedRequests.size)
        assertEquals(UpdateAdminTaskRequest.IsActive._0, fakeApi.updatedRequests.first().second.isActive)
    }

    @Test
    fun `delete confirmation flow handles success and 400 constraint error`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository(role = RolePolicy.ADMIN)

        val viewModel = TasksViewModel(repository, authRepository)
        val task = viewModel.uiState.value.tasks.first()

        viewModel.requestDelete(task)
        assertEquals(task, viewModel.uiState.value.deleteConfirmTask)

        // Cancel
        viewModel.cancelDelete()
        assertNull(viewModel.uiState.value.deleteConfirmTask)

        // Request again and confirm delete
        viewModel.requestDelete(task)
        viewModel.confirmDelete()

        assertNull(viewModel.uiState.value.deleteConfirmTask)
        assertEquals("Tarefa excluída com sucesso.", viewModel.uiState.value.message)
        assertEquals(1, viewModel.uiState.value.tasks.size)

        // Now simulate 400 error on delete
        fakeApi.deleteResponse = Response.error(400, "In use".toResponseBody("application/json".toMediaType()))
        val remainingTask = viewModel.uiState.value.tasks.first()
        viewModel.requestDelete(remainingTask)
        viewModel.confirmDelete()

        assertTrue(viewModel.uiState.value.isError)
        assertEquals(
            "Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo.",
            viewModel.uiState.value.message,
        )
    }

    @Test
    fun `FakeAdminApi mutates tasks list on create and delete so subsequent fetch reflects mutations`() = kotlinx.coroutines.test.runTest {
        val fakeApi = FakeAdminApi()
        val initialTasks = fakeApi.tasks().body()!!.tasks
        assertEquals(2, initialTasks.size)

        fakeApi.createTask(
            CreateAdminTaskRequest(
                categoryId = 1,
                namePt = "Nova Tarefa",
                nameEs = "Nueva Tarea",
                temperatureReadings = 1,
            ),
        )
        val afterCreate = fakeApi.tasks().body()!!.tasks
        assertEquals(3, afterCreate.size)
        assertTrue(afterCreate.any { it.namePt == "Nova Tarefa" })

        fakeApi.deleteTask(afterCreate.first().id)
        val afterDelete = fakeApi.tasks().body()!!.tasks
        assertEquals(2, afterDelete.size)
    }

    @Test
    fun `non-temperature task create serializes a valid number instead of null for temperature_readings`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        viewModel.openCreateForm() // Category 1 is 'check' (non-temperature)
        viewModel.onNamePtChanged("Tarefa Limpeza")
        viewModel.saveTask()

        val sentRequest = fakeApi.createdRequests.firstOrNull()
        assertNotNull(sentRequest)
        assertNotNull("temperatureReadings must be a valid number, not null", sentRequest?.temperatureReadings)
        assertEquals(1, sentRequest?.temperatureReadings)

        val serialized = Json.encodeToString(CreateAdminTaskRequest.serializer(), sentRequest!!)
        assertFalse("JSON must not serialize null temperature_readings", serialized.contains("\"temperature_readings\":null"))
    }

    @Test
    fun `closeForm cancels create form and restores null form state`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        viewModel.openCreateForm()
        assertNotNull(viewModel.uiState.value.form)

        viewModel.closeForm()
        assertNull(viewModel.uiState.value.form)
    }

    @Test
    fun `closeForm cancels edit form and restores null form state`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        val task = viewModel.uiState.value.tasks.first()
        viewModel.openEditForm(task)
        assertNotNull(viewModel.uiState.value.form)

        viewModel.closeForm()
        assertNull(viewModel.uiState.value.form)
    }

    @Test
    fun `cancelDelete dismisses active delete confirmation dialog state`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        val task = viewModel.uiState.value.tasks.first()
        viewModel.requestDelete(task)
        assertEquals(task, viewModel.uiState.value.deleteConfirmTask)

        viewModel.cancelDelete()
        assertNull(viewModel.uiState.value.deleteConfirmTask)
    }

    @Test
    fun `handleBack or cancellation dispatch preserves state invariants across form and delete flows`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        val task = viewModel.uiState.value.tasks.first()

        // Form flow
        viewModel.openEditForm(task)
        assertNotNull(viewModel.uiState.value.form)
        val handleBack = viewModel.javaClass.methods.firstOrNull { it.name == "handleBack" }
        if (handleBack != null) {
            handleBack.invoke(viewModel)
        } else {
            viewModel.closeForm()
        }
        assertNull(viewModel.uiState.value.form)

        // Delete flow
        viewModel.requestDelete(task)
        assertNotNull(viewModel.uiState.value.deleteConfirmTask)
        if (handleBack != null) {
            handleBack.invoke(viewModel)
        } else {
            viewModel.cancelDelete()
        }
        assertNull(viewModel.uiState.value.deleteConfirmTask)
    }

    @Test
    fun `handleBack returns false at root when neither dialog nor form is open`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        assertNull(viewModel.uiState.value.deleteConfirmTask)
        assertNull(viewModel.uiState.value.form)

        assertFalse(viewModel.handleBack())
    }

    @Test
    fun `handleBack consumes delete dialog before form when both are present`() {
        val fakeApi = FakeAdminApi()
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()

        val viewModel = TasksViewModel(repository, authRepository)
        val task = viewModel.uiState.value.tasks.first()

        viewModel.openCreateForm()
        viewModel.requestDelete(task)
        assertNotNull(viewModel.uiState.value.deleteConfirmTask)
        assertNotNull(viewModel.uiState.value.form)

        // First back consumes delete dialog
        assertTrue(viewModel.handleBack())
        assertNull(viewModel.uiState.value.deleteConfirmTask)
        assertNotNull(viewModel.uiState.value.form)

        // Second back consumes form
        assertTrue(viewModel.handleBack())
        assertNull(viewModel.uiState.value.form)

        // Third back at root returns false
        assertFalse(viewModel.handleBack())
    }

    @Test
    fun `closeForm does not cancel form during in-flight save mutation`() = kotlinx.coroutines.test.runTest {
        val deferred = CompletableDeferred<Response<AdminTaskResponse>>()
        val fakeApi = object : AdminApi by FakeAdminApi() {
            override suspend fun createTask(body: CreateAdminTaskRequest): Response<AdminTaskResponse> {
                return deferred.await()
            }
        }
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()
        val viewModel = TasksViewModel(repository, authRepository)

        viewModel.openCreateForm()
        viewModel.onNamePtChanged("Nova Tarefa")
        viewModel.saveTask()

        // Saving is in flight
        assertTrue(viewModel.uiState.value.form?.saving == true)

        // Attempt to close form while saving
        viewModel.closeForm()
        assertNotNull(viewModel.uiState.value.form)
        assertTrue(viewModel.uiState.value.form?.saving == true)

        // Complete the save
        deferred.complete(
            Response.success(
                AdminTaskResponse(
                    task = AdminTaskResponseTask(
                        id = 99,
                        categoryId = 1,
                        namePt = "Nova Tarefa",
                        nameEs = "",
                        temperatureReadings = 1,
                        isActive = 1,
                        createdByUserId = 1,
                        createdAt = "2026-09-22T10:00:00.000Z",
                        taskType = AdminTaskResponseTask.TaskType.check,
                    ),
                ),
            ),
        )

        assertNull(viewModel.uiState.value.form)
    }

    @Test
    fun `cancelDelete does not dismiss dialog during in-flight delete mutation`() = kotlinx.coroutines.test.runTest {
        val deferred = CompletableDeferred<Response<SuccessResponse>>()
        val fakeApi = object : AdminApi by FakeAdminApi() {
            override suspend fun deleteTask(id: Int): Response<SuccessResponse> {
                return deferred.await()
            }
        }
        val repository = TasksRepository(fakeApi)
        val authRepository = createAuthRepository()
        val viewModel = TasksViewModel(repository, authRepository)
        val task = viewModel.uiState.value.tasks.first()

        viewModel.requestDelete(task)
        viewModel.confirmDelete()

        // Deleting is in flight
        assertTrue(viewModel.uiState.value.deleting)

        // Attempt to cancel delete while deleting
        viewModel.cancelDelete()
        assertNotNull(viewModel.uiState.value.deleteConfirmTask)
        assertTrue(viewModel.uiState.value.deleting)

        // Complete the delete
        deferred.complete(Response.success(SuccessResponse(success = SuccessResponse.Success.`true`)))

        assertNull(viewModel.uiState.value.deleteConfirmTask)
        assertFalse(viewModel.uiState.value.deleting)
    }
}


