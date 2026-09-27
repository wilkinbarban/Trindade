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
        model.delete(model.state.value.tasks[0])
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
        model.delete(task)
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
        val result = if (taskReads == 1) firstTasksRead?.await() else null
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
