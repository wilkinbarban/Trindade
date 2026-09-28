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
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TasksViewModelTest {
    private val fixtureCategories = listOf(
        AdminCategoryResponseCategory(3, null, "Câmara", "Cámara", AdminCategoryResponseCategory.CategoryType.temperature, 0, 1, "now"),
        AdminCategoryResponseCategory(4, null, "Limpeza", "Limpieza", AdminCategoryResponseCategory.CategoryType.check, 1, 1, "now"),
    )

    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun `repository deletion is not a public ViewModel action`() {
        assertFalse(TasksViewModel::class.java.declaredMethods.any {
            it.name == "delete" && java.lang.reflect.Modifier.isPublic(it.modifiers)
        })
    }

    @Test fun `confirmed delete state workflow enforces request cancel and no-replay confirmation`() {
        val target = task(7, 3, 99, 1)
        val second = task(8, 3, 99, 1)
        val api = CatalogApi(taskRows = listOf(target, second))
        val model = model(api, role = "Administrador")
        model.load()

        model.confirmDelete()
        assertEquals(0, api.deletes)

        model.requestDelete(target)
        assertEquals(target, model.state.value.deleteTarget)

        model.cancelDelete()
        assertNull(model.state.value.deleteTarget)

        model.confirmDelete()
        assertEquals(0, api.deletes)

        model.requestDelete(target)
        api.writeResult = TaskWriteResult.Deleted
        model.confirmDelete()
        assertEquals(1, api.deletes)
        assertNull(model.state.value.deleteTarget)

        model.confirmDelete()
        assertEquals(1, api.deletes)

        model.requestDelete(second)
        model.confirmDelete()
        assertEquals(2, api.deletes)
        assertNull(model.state.value.deleteTarget)
    }

    @Test fun `loads catalog and resolves current user through authenticated profile`() {
        val api = CatalogApi(taskRows = listOf(task(7, 3, 42, active = 1), task(8, 4, 99, active = 0)))
        val model = model(api)
        model.load()
        assertEquals(fixtureCategories, model.state.value.categories)
        assertEquals(7, model.state.value.tasks.first().id)
        assertEquals(42, model.state.value.currentUserId)
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
        val failed = model(CatalogApi().apply { refuseCategories = true })
        failed.load()
        assertEquals(TasksViewModel.UNREACHABLE, failed.state.value.error)
        assertFalse(failed.state.value.loading)
    }

    @Test fun `worker may edit own active task but cannot toggle or delete any task`() {
        val api = CatalogApi(taskRows = listOf(task(7, 3, 42, 1), task(8, 4, 42, 0), task(9, 4, 99, 1)))
        val model = model(api)
        model.load()
        assertTrue(model.state.value.canEdit(model.state.value.tasks[0]))
        assertFalse(model.state.value.canEdit(model.state.value.tasks[1]))
        assertFalse(model.state.value.canEdit(model.state.value.tasks[2]))
        assertFalse(model.state.value.canToggle(model.state.value.tasks[0]))
        assertFalse(model.state.value.canDelete(model.state.value.tasks[0]))
        model.toggle(model.state.value.tasks[0])
        model.requestDelete(model.state.value.tasks[0])
        model.confirmDelete()
        assertEquals(0, api.updates + api.deletes)
    }

    @Test fun `worker identity must be known before an unowned task can be edited`() {
        val task = task(7, 3, 42, 1).copy(createdByUserId = null)
        assertFalse(TasksViewModel.UiState(role = "Trabalhador").canEdit(task))
    }

    @Test fun `administrator toggle and delete writes reload the catalog`() {
        val api = CatalogApi(taskRows = listOf(task(7, 3, 99, 1)))
        val model = model(api, role = "Administrador")
        model.load()
        val task = model.state.value.tasks.single()
        assertTrue(model.state.value.canEdit(task))
        assertTrue(model.state.value.canToggle(task))
        assertTrue(model.state.value.canDelete(task))
        api.writeResult = TaskWriteResult.Saved(AdminTaskResponseTask(7, 3, "Task7", "Tarea7", 1, 0, 99, "now", AdminTaskResponseTask.TaskType.temperature))
        model.toggle(task)
        assertEquals(1, api.updates)
        assertEquals(2, api.taskReads)
        api.writeResult = TaskWriteResult.Deleted
        model.requestDelete(task)
        model.confirmDelete()
        assertEquals(1, api.deletes)
        assertEquals(3, api.taskReads)
    }

    @Test fun `rejects missing category names and temperature readings outside server bounds before writing`() {
        val api = CatalogApi()
        val model = model(api)
        model.load()
        model.onCategoryChange("999")
        model.onNamePtChange("Nova")
        model.save()
        assertEquals(TasksViewModel.CATEGORY, model.state.value.error)
        assertEquals(0, api.creates)

        model.onCategoryChange("3")
        model.onNamePtChange(" ")
        model.save()
        assertEquals(TasksViewModel.NAME, model.state.value.error)
        assertEquals(0, api.creates)

        model.onNamePtChange("Nova")
        model.onTemperatureReadingsChange("4")
        model.save()
        assertEquals(TasksViewModel.TEMPERATURE_READINGS, model.state.value.error)
        assertEquals(0, api.creates)
    }

    @Test fun `requires a name in at least one language and forces one reading for non-temperature categories`() {
        val api = CatalogApi()
        val model = model(api)
        model.load()
        model.onCategoryChange("4")
        model.onNameEsChange("Nueva")
        model.onTemperatureReadingsChange("3")
        model.save()
        assertEquals(1, api.creates)
        assertEquals("Nueva", api.lastCreate?.nameEs)
        assertEquals(null, api.lastCreate?.namePt)
        assertEquals(1, api.lastCreate?.temperatureReadings)
    }

    @Test fun `refusals and unreachable writes stay visible and successful writes reload`() {
        val api = CatalogApi()
        val model = model(api)
        model.load()
        model.onCategoryChange("3")
        model.onNamePtChange("Nova")
        api.writeResult = TaskWriteResult.Refused(403)
        model.save()
        assertEquals(403, model.state.value.refusedStatus)
        assertEquals(TasksViewModel.REFUSED, model.state.value.error)
        assertEquals(1, api.taskReads)

        api.writeResult = TaskWriteResult.Unreachable
        model.save()
        assertEquals(TasksViewModel.UNREACHABLE, model.state.value.error)
        assertEquals(1, api.taskReads)

        api.writeResult = TaskWriteResult.Saved(AdminTaskResponseTask(10, 3, "Task10", "Tarea10", 1, 1, 42, "now", AdminTaskResponseTask.TaskType.temperature))
        model.save()
        assertEquals(2, api.taskReads)
        assertNull(model.state.value.error)
    }

    @Test fun `a newer load cancels an older response`() {
        val api = CatalogApi()
        val model = model(api)
        api.taskRows = listOf(task(7, 3, 42, 1))
        api.firstTasksRead = CompletableDeferred()
        model.load()
        model.load()
        assertEquals(2, api.taskReads)
        assertEquals(7, model.state.value.tasks.single().id)
        api.firstTasksRead!!.complete(listOf(task(99, 3, 42, 1)))
        assertEquals(7, model.state.value.tasks.single().id)
    }

    @Test fun `failed reload clears identity, mutations are blocked during loading, and save fails closed for unknown role`() {
        val api = CatalogApi(taskRows = listOf(task(7, 3, 42, 1), task(8, 3, 99, 1)))
        val model = model(api, role = "Trabalhador")
        model.load()
        assertEquals("Trabalhador", model.state.value.role)
        assertEquals(42, model.state.value.currentUserId)

        // Failed reload clears role and currentUserId
        api.refuseCategories = true
        model.load()
        assertNull(model.state.value.role)
        assertNull(model.state.value.currentUserId)
        assertEquals(TasksViewModel.UNREACHABLE, model.state.value.error)

        // Save fails closed for cleared/missing role
        model.onCategoryChange("3")
        model.onNamePtChange("Nova")
        model.save()
        assertEquals(0, api.creates)

        // Mutations cannot proceed during loading
        val pendingApi = CatalogApi(taskRows = listOf(task(7, 3, 42, 1)))
        val pendingModel = model(pendingApi, role = "Administrador")
        pendingModel.load()
        assertEquals("Administrador", pendingModel.state.value.role)
        val deferred = CompletableDeferred<List<AdminTasksResponseTasksInner>>()
        pendingApi.deferredRead = deferred
        pendingModel.load()
        assertTrue(pendingModel.state.value.loading)
        val targetTask = task(7, 3, 42, 1)
        pendingModel.edit(targetTask)
        assertNull(pendingModel.state.value.editingId)
        pendingModel.toggle(targetTask)
        assertEquals(0, pendingApi.updates)
        pendingModel.requestDelete(targetTask)
        assertNull(pendingModel.state.value.deleteTarget)
        pendingModel.confirmDelete()
        assertEquals(0, pendingApi.deletes)
        pendingModel.onCategoryChange("3")
        pendingModel.onNamePtChange("Nova")
        pendingModel.save()
        assertEquals(0, pendingApi.creates)
        deferred.complete(listOf(targetTask))
        assertFalse(pendingModel.state.value.loading)

        // Save fails closed for unknown role and worker editing unowned target
        val unknownApi = CatalogApi()
        val unknownModel = model(unknownApi, role = "Desconhecido")
        unknownModel.load()
        unknownModel.onCategoryChange("3")
        unknownModel.onNamePtChange("Nova")
        unknownModel.save()
        assertEquals(0, unknownApi.creates)

        val workerApi = CatalogApi(taskRows = listOf(task(7, 3, 42, 1)))
        val workerModel = model(workerApi, role = "Trabalhador")
        workerModel.load()
        workerModel.edit(workerModel.state.value.tasks.single())
        assertEquals(7, workerModel.state.value.editingId)
        // The row changes owner between opening the form and submitting it.
        workerApi.taskRows = listOf(task(7, 3, 99, 1))
        workerModel.load()
        workerModel.save()
        assertEquals(0, workerApi.updates)
        workerApi.taskRows = listOf(task(7, 3, 42, 1))
        workerModel.load()
        workerModel.save()
        assertEquals(1, workerApi.updates)
    }

    @Test fun `failed reload clears deleteTarget and permission check blocks unconfirmed or unauthorized deletion`() {
        val target = task(7, 3, 99, 1)
        val api = CatalogApi(taskRows = listOf(target))
        val model = model(api, role = "Administrador")
        model.load()
        assertTrue(model.state.value.canDelete(target))

        model.requestDelete(target)
        assertEquals(target, model.state.value.deleteTarget)

        // Failed reload clears deleteTarget, role, and permissions
        api.refuseCategories = true
        model.load()
        assertEquals(TasksViewModel.UNREACHABLE, model.state.value.error)
        assertNull(model.state.value.deleteTarget)
        assertNull(model.state.value.role)
        assertFalse(model.state.value.canDelete(target))

        // Confirming when deleteTarget is null or permissions failed does nothing
        model.confirmDelete()
        assertEquals(0, api.deletes)

        // Worker role cannot request or confirm delete
        val workerApi = CatalogApi(taskRows = listOf(target))
        val workerModel = model(workerApi, role = "Trabalhador")
        workerModel.load()
        assertFalse(workerModel.state.value.canDelete(target))
        workerModel.requestDelete(target)
        assertNull(workerModel.state.value.deleteTarget)
        workerModel.confirmDelete()
        assertEquals(0, workerApi.deletes)
    }

    @Test fun `in-flight load blocks requestDelete and confirmDelete while preserving existing confirmation target`() {
        val target = task(7, 3, 99, 1)
        val other = task(8, 3, 99, 1)
        val api = CatalogApi(taskRows = listOf(target, other))
        val model = model(api, role = "Administrador")
        model.load()

        model.requestDelete(target)
        assertEquals(target, model.state.value.deleteTarget)

        val deferred = CompletableDeferred<List<AdminTasksResponseTasksInner>>()
        api.deferredRead = deferred
        model.load()
        assertTrue(model.state.value.loading)

        // requestDelete for another task is blocked during loading
        model.requestDelete(other)
        assertEquals(target, model.state.value.deleteTarget)

        // confirmDelete is blocked during loading
        model.confirmDelete()
        assertEquals(0, api.deletes)
        assertEquals(target, model.state.value.deleteTarget)

        deferred.complete(listOf(target, other))
        assertFalse(model.state.value.loading)

        // Now confirmDelete succeeds
        api.writeResult = TaskWriteResult.Deleted
        model.confirmDelete()
        assertEquals(1, api.deletes)
        assertNull(model.state.value.deleteTarget)
    }

    @Test fun `target removed from catalog between request and reload clears deleteTarget and rejects confirmation`() {
        val target = task(7, 3, 99, 1)
        val api = CatalogApi(taskRows = listOf(target))
        val model = model(api, role = "Administrador")
        model.load()

        model.requestDelete(target)
        assertEquals(target, model.state.value.deleteTarget)

        // Target task is removed before next reload
        api.taskRows = emptyList()
        model.load()
        assertNull(model.state.value.deleteTarget)

        // confirmDelete has no effect
        model.confirmDelete()
        assertEquals(0, api.deletes)
    }

    @Test fun `requestDelete selects task by id from current catalog ignoring stale caller object and confirm re-evaluates`() {
        val canonical = task(7, 3, 99, 1)
        val api = CatalogApi(taskRows = listOf(canonical))
        val model = model(api, role = "Administrador")
        model.load()

        // Stale task object with different name/readings but same ID
        val stale = canonical.copy(namePt = "Stale name", temperatureReadings = 3)
        model.requestDelete(stale)
        // ViewModel selects canonical instance from current catalog
        assertEquals(canonical, model.state.value.deleteTarget)
        assertEquals("Task7", model.state.value.deleteTarget?.namePt)

        // Non-existent ID is rejected
        val nonExistent = task(999, 3, 99, 1)
        model.requestDelete(nonExistent)
        // Keeps previous target and does not adopt nonExistent
        assertEquals(canonical, model.state.value.deleteTarget)

        // If target disappears from tasks list before confirmDelete, confirmation is rejected
        val pendingApi = CatalogApi(taskRows = listOf(canonical))
        val pendingModel = model(pendingApi, role = "Administrador")
        pendingModel.load()
        pendingModel.requestDelete(canonical)
        // Simulate task removal from state before confirmDelete
        pendingApi.taskRows = emptyList()
        pendingModel.load()
        pendingModel.confirmDelete()
        assertEquals(0, pendingApi.deletes)
        assertNull(pendingModel.state.value.deleteTarget)
    }

    private fun model(api: CatalogApi, role: String = "Trabalhador") = TasksViewModel(
        TasksRepository(api), AuthRepository(ProfileApi(role), Store(), Json),
    )

    private fun task(id: Int, category: Int, owner: Int, active: Int) = AdminTasksResponseTasksInner(
        id = id,
        categoryId = category,
        namePt = "Task$id",
        nameEs = "Tarea$id",
        temperatureReadings = 1,
        isActive = active,
        createdByUserId = owner,
        createdAt = "now",
        taskType = AdminTasksResponseTasksInner.TaskType.check,
        categoryName = "Category$category",
    )
}

private class CatalogApi(
    var taskRows: List<AdminTasksResponseTasksInner> = emptyList(),
) : AdminApi {
    private val fixtureCategories = listOf(
        AdminCategoryResponseCategory(3, null, "Câmara", "Cámara", AdminCategoryResponseCategory.CategoryType.temperature, 0, 1, "now"),
        AdminCategoryResponseCategory(4, null, "Limpeza", "Limpieza", AdminCategoryResponseCategory.CategoryType.check, 1, 1, "now"),
    )
    var taskReads = 0
    var refuseCategories = false
    var creates = 0
    var updates = 0
    var deletes = 0
    var lastCreate: CreateAdminTaskRequest? = null
    var writeResult: TaskWriteResult = TaskWriteResult.Saved(
        AdminTaskResponseTask(1, 3, "Task", "Tarea", 1, 1, 42, "now", AdminTaskResponseTask.TaskType.temperature),
    )
    var firstTasksRead: CompletableDeferred<List<AdminTasksResponseTasksInner>>? = null
    var deferredRead: CompletableDeferred<List<AdminTasksResponseTasksInner>>? = null

    override suspend fun categories(): Response<AdminCategoriesResponse> = if (refuseCategories) {
        Response.error(403, okhttp3.ResponseBody.create(null, ""))
    } else Response.success(AdminCategoriesResponse(fixtureCategories))
    override suspend fun createCategory(body: CreateAdminCategoryRequest): Response<AdminCategoryResponse> =
        error("category writes are not used by task tests")
    override suspend fun updateCategory(id: Int, body: JsonObject): Response<AdminCategoryResponse> =
        error("category writes are not used by task tests")
    override suspend fun deleteCategory(id: Int): Response<Unit> = error("category writes are not used by task tests")
    override suspend fun tasks(): Response<AdminTasksResponse> {
        taskReads++
        val result = deferredRead?.await() ?: if (taskReads == 1) firstTasksRead?.await() else null
        return Response.success(AdminTasksResponse(result ?: taskRows))
    }
    override suspend fun createTask(body: CreateAdminTaskRequest): Response<AdminTaskResponse> {
        creates++
        lastCreate = body
        return when (val result = writeResult) {
            is TaskWriteResult.Saved -> Response.success(AdminTaskResponse(result.task))
            is TaskWriteResult.Refused -> Response.error(result.status, okhttp3.ResponseBody.create(null, ""))
            else -> throw java.io.IOException("offline")
        }
    }
    override suspend fun updateTask(id: Int, body: JsonObject): Response<AdminTaskResponse> {
        updates++
        return when (val result = writeResult) {
            is TaskWriteResult.Saved -> Response.success(AdminTaskResponse(result.task))
            is TaskWriteResult.Refused -> Response.error(result.status, okhttp3.ResponseBody.create(null, ""))
            else -> throw java.io.IOException("offline")
        }
    }
    override suspend fun deleteTask(id: Int): Response<Unit> {
        deletes++
        return if (writeResult == TaskWriteResult.Deleted) Response.success(Unit)
        else if (writeResult is TaskWriteResult.Refused) Response.error((writeResult as TaskWriteResult.Refused).status, okhttp3.ResponseBody.create(null, ""))
        else throw java.io.IOException("offline")
    }
    override suspend fun drivers(): Response<AdminDriversResponse> = error("driver API is not used by task tests")
    override suspend fun createDriver(body: CreateAdminDriverRequest): Response<AdminDriverResponse> = error("driver API is not used by task tests")
    override suspend fun updateDriver(id: Int, body: JsonObject): Response<AdminDriverResponse> = error("driver API is not used by task tests")
    override suspend fun vehicles(): Response<AdminVehiclesResponse> = error("vehicle API is not used by task tests")
    override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> = error("vehicle API is not used by task tests")
    override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> = error("vehicle API is not used by task tests")
    override suspend fun deleteVehicle(id: Int): Response<Unit> = error("vehicle API is not used by task tests")
    override suspend fun timeSlots(): Response<TimeSlotsResponse> = error("time-slots API is not used by task tests")
    override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse> = error("time-slots API is not used by task tests")
    override suspend fun users(): Response<AdminUsersResponse> = error("user API is not used by task tests")
    override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> = error("user API is not used by task tests")
    override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> = error("user API is not used by task tests")
    override suspend fun deleteUser(id: Int): Response<Unit> = error("user API is not used by task tests")
}

private class ProfileApi(role: String) : AuthApi {
    private val profile = ProfileResponse(user = ProfileResponseUser(42, "ana", "Ana", role))
    override suspend fun profile() = Response.success(profile)
    override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("not expected")
    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("not expected")
    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("not expected")
    override suspend fun me(): Response<ProfileResponse> = Response.success(profile)
    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("not expected")
    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("not expected")
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error("not expected")
    override suspend fun register(body: RegisterRequest): Response<RegisterResponse> = error("not expected")
}

private class Store : TokenStore {
    override fun accessToken() = "token"
    override fun refreshToken() = "refresh"
    override fun role() = null
    override fun save(accessToken: String, refreshToken: String) = Unit
    override fun saveRole(role: String) = Unit
    override fun clear() = Unit
}
