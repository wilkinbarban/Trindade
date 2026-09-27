package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.AdminCategoriesResponse
import com.trindade.app.contract.models.AdminCategoryResponse
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriversResponse
import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.contract.models.AdminTasksResponse
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
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.AuthApi
import java.util.Locale
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
class CategoriesViewModelTest {
    private val fixtureCategories = listOf(
        category(1, "Higiene", "Higiene", AdminCategoryResponseCategory.CategoryType.check, 0, 1),
        category(2, "Temperaturas", "Temperaturas", AdminCategoryResponseCategory.CategoryType.temperature, 1, 1),
    )

    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun `loads categories and authenticated identity then applies category policy`() {
        val api = CategoryApi(rows = fixtureCategories)
        val admin = viewModel(api, role = "Administrador")
        admin.load()
        assertEquals(fixtureCategories, admin.state.value.categories)
        assertEquals(42, admin.state.value.currentUserId)
        assertTrue(admin.state.value.isAdmin)
        assertTrue(admin.state.value.canEdit(fixtureCategories[0]))
        assertTrue(admin.state.value.canToggle(fixtureCategories[0]))
        assertTrue(admin.state.value.canDelete(fixtureCategories[0]))

        val worker = viewModel(api, role = "Trabalhador")
        worker.load()
        assertEquals(fixtureCategories, worker.state.value.categories)
        assertFalse(worker.state.value.isAdmin)
        assertFalse(worker.state.value.canEdit(fixtureCategories[0]))
        assertFalse(worker.state.value.canToggle(fixtureCategories[0]))
        assertFalse(worker.state.value.canDelete(fixtureCategories[0]))

        worker.edit(fixtureCategories[0])
        assertNull(worker.state.value.editingId)
        worker.toggle(fixtureCategories[0])
        worker.delete(fixtureCategories[0])
        worker.onNamePtChange("Novo")
        worker.save()
        assertEquals(0, api.creates + api.updates + api.deletes)

        val unknownApi = CategoryApi(rows = fixtureCategories)
        val unknown = viewModel(unknownApi, role = "UnknownRole")
        unknown.load()
        assertFalse(unknown.state.value.isAdmin)
        unknown.onNamePtChange("Tentativa")
        unknown.save()
        assertEquals(0, unknownApi.creates)
    }

    @Test fun `administrator creates category with current-language name in Portuguese or Spanish`() {
        val api = CategoryApi()
        val ptModel = viewModel(api, role = "Administrador", locale = Locale.forLanguageTag("pt-BR"))
        ptModel.load()
        ptModel.onNamePtChange("Câmara Fria")
        ptModel.onCategoryTypeChange("temperature")
        ptModel.onSortOrderChange("2")
        ptModel.save()
        assertEquals(1, api.creates)
        assertEquals("Câmara Fria", api.lastCreate?.namePt)
        assertNull(api.lastCreate?.nameEs)
        assertNull(api.lastCreate?.parentCategoryId)
        assertEquals(CreateAdminCategoryRequest.CategoryType.temperature, api.lastCreate?.categoryType)
        assertEquals(2, api.lastCreate?.sortOrder)

        val esModel = viewModel(api, role = "Administrador", locale = Locale.forLanguageTag("es-ES"))
        esModel.load()
        esModel.onNameEsChange("Cámara Fría")
        esModel.onCategoryTypeChange("check")
        esModel.onSortOrderChange("0")
        esModel.save()
        assertEquals(2, api.creates)
        assertNull(api.lastCreate?.namePt)
        assertEquals("Cámara Fría", api.lastCreate?.nameEs)
        assertNull(api.lastCreate?.parentCategoryId)
        assertEquals(CreateAdminCategoryRequest.CategoryType.check, api.lastCreate?.categoryType)
        assertEquals(0, api.lastCreate?.sortOrder)
    }

    @Test fun `validates at least one nonblank name allowed category type and nonnegative sort order`() {
        val api = CategoryApi()
        val model = viewModel(api, role = "Administrador")
        model.load()

        model.onNamePtChange("   ")
        model.onNameEsChange("")
        model.save()
        assertEquals(CategoriesViewModel.NAME, model.state.value.error)
        assertEquals(0, api.creates)

        model.onNamePtChange("Nova Categoria")
        model.onCategoryTypeChange("invalid_type")
        model.save()
        assertEquals(CategoriesViewModel.CATEGORY_TYPE, model.state.value.error)
        assertEquals(0, api.creates)

        model.onCategoryTypeChange("check")
        model.onSortOrderChange("-1")
        model.save()
        assertEquals(CategoriesViewModel.SORT_ORDER, model.state.value.error)
        assertEquals(0, api.creates)

        model.onSortOrderChange("abc")
        model.save()
        assertEquals(CategoriesViewModel.SORT_ORDER, model.state.value.error)
        assertEquals(0, api.creates)
    }

    @Test fun `administrator edits category sending both names`() {
        val api = CategoryApi(rows = fixtureCategories)
        val model = viewModel(api, role = "Administrador")
        model.load()
        val target = fixtureCategories[0]

        model.edit(target)
        assertEquals(target.id, model.state.value.editingId)
        assertEquals("Higiene", model.state.value.namePt)
        assertEquals("Higiene", model.state.value.nameEs)
        assertEquals("check", model.state.value.categoryType)
        assertEquals("0", model.state.value.sortOrder)

        model.onNamePtChange("Higiene Geral")
        model.onNameEsChange("Higiene General")
        model.onCategoryTypeChange("check_normal")
        model.onSortOrderChange("5")
        model.save()

        assertEquals(1, api.updates)
        assertEquals(target.id, api.lastUpdateId)
        assertEquals("Higiene Geral", api.lastUpdate?.namePt)
        assertEquals("Higiene General", api.lastUpdate?.nameEs)
        assertEquals(UpdateAdminCategoryRequest.CategoryType.check_normal, api.lastUpdate?.categoryType)
        assertEquals(5, api.lastUpdate?.sortOrder)
        assertNull(model.state.value.editingId)

        model.edit(target)
        model.cancelEdit()
        assertNull(model.state.value.editingId)
        assertEquals("", model.state.value.namePt)
        assertEquals("", model.state.value.nameEs)
    }

    @Test fun `toggle sends inverted is_active flag`() {
        val api = CategoryApi(rows = fixtureCategories)
        val model = viewModel(api, role = "Administrador")
        model.load()

        val activeCat = fixtureCategories[0] // isActive = 1
        model.toggle(activeCat)
        assertEquals(1, api.updates)
        assertEquals(activeCat.id, api.lastUpdateId)
        assertEquals(UpdateAdminCategoryRequest.IsActive._0, api.lastUpdate?.isActive)

        val inactiveCat = category(3, "Inativa", "Inactiva", AdminCategoryResponseCategory.CategoryType.check, 2, 0)
        model.toggle(inactiveCat)
        assertEquals(2, api.updates)
        assertEquals(inactiveCat.id, api.lastUpdateId)
        assertEquals(UpdateAdminCategoryRequest.IsActive._1, api.lastUpdate?.isActive)
    }

    @Test fun `confirmed delete state workflow for later screen`() {
        val api = CategoryApi(rows = fixtureCategories)
        val model = viewModel(api, role = "Administrador")
        model.load()
        val target = fixtureCategories[0]

        model.requestDelete(target)
        assertEquals(target, model.state.value.deleteTarget)

        model.cancelDelete()
        assertNull(model.state.value.deleteTarget)
        assertEquals(0, api.deletes)

        model.requestDelete(target)
        model.confirmDelete()
        assertEquals(1, api.deletes)
        assertEquals(target.id, api.lastDeleteId)
        assertNull(model.state.value.deleteTarget)

        model.delete(fixtureCategories[1])
        assertEquals(2, api.deletes)
        assertEquals(fixtureCategories[1].id, api.lastDeleteId)
    }

    @Test fun `refusals and unreachable writes differ and successful writes reload`() {
        val api = CategoryApi(rows = fixtureCategories)
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.onNamePtChange("Nova")

        api.writeResult = CategoryWriteResult.Refused(400)
        model.save()
        assertEquals(400, model.state.value.refusedStatus)
        assertEquals(CategoriesViewModel.REFUSED, model.state.value.error)
        assertEquals(1, api.reads)

        api.writeResult = CategoryWriteResult.Unreachable
        model.save()
        assertEquals(CategoriesViewModel.UNREACHABLE, model.state.value.error)
        assertNull(model.state.value.refusedStatus)
        assertEquals(1, api.reads)

        api.writeResult = CategoryWriteResult.Saved(fixtureCategories[0])
        model.save()
        assertEquals(2, api.reads)
        assertNull(model.state.value.error)
    }

    @Test fun `a newer load cancels an older category response`() {
        val api = CategoryApi(rows = fixtureCategories)
        api.firstRead = CompletableDeferred()
        val model = viewModel(api, role = "Administrador")
        model.load()
        model.load()
        assertEquals(2, api.reads)
        assertEquals(fixtureCategories, model.state.value.categories)
        api.firstRead!!.complete(listOf(category(99, "Antiga", "Antigua", AdminCategoryResponseCategory.CategoryType.check, 0, 1)))
        assertEquals(fixtureCategories, model.state.value.categories)
    }

    private fun viewModel(
        api: CategoryApi,
        role: String = "Trabalhador",
        locale: Locale = Locale.forLanguageTag("pt-BR"),
    ) = CategoriesViewModel(
        CategoriesRepository(api),
        AuthRepository(CategoryProfileApi(role), CategoryStore(), Json),
        localeProvider = { locale },
    )

    private fun category(
        id: Int,
        namePt: String,
        nameEs: String,
        type: AdminCategoryResponseCategory.CategoryType,
        sortOrder: Int,
        active: Int,
    ) = AdminCategoryResponseCategory(id, null, namePt, nameEs, type, sortOrder, active, "now")
}

private class CategoryApi(var rows: List<AdminCategoryResponseCategory> = emptyList()) : AdminApi {
    var reads = 0
    var creates = 0
    var updates = 0
    var deletes = 0
    var lastCreate: CreateAdminCategoryRequest? = null
    var lastUpdate: UpdateAdminCategoryRequest? = null
    var lastUpdateId: Int? = null
    var lastDeleteId: Int? = null
    var firstRead: CompletableDeferred<List<AdminCategoryResponseCategory>>? = null
    var writeResult: CategoryWriteResult = CategoryWriteResult.Saved(
        AdminCategoryResponseCategory(5, null, "Laticínios", "Lácteos", AdminCategoryResponseCategory.CategoryType.check, 0, 1, "now"),
    )

    override suspend fun categories(): Response<AdminCategoriesResponse> {
        reads++
        val result = if (reads == 1) firstRead?.await() else null
        return Response.success(AdminCategoriesResponse(result ?: rows))
    }

    override suspend fun createCategory(body: CreateAdminCategoryRequest): Response<AdminCategoryResponse> {
        creates++
        lastCreate = body
        return categoryResponse()
    }

    override suspend fun updateCategory(id: Int, body: JsonObject): Response<AdminCategoryResponse> {
        updates++
        lastUpdateId = id
        lastUpdate = UpdateAdminCategoryRequest(
            parentCategoryId = body["parent_category_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            namePt = body["name_pt"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            nameEs = body["name_es"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            categoryType = body["category_type"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let(UpdateAdminCategoryRequest.CategoryType::valueOf),
            sortOrder = body["sort_order"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull(),
            isActive = body["is_active"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let {
                if (it == "1") UpdateAdminCategoryRequest.IsActive._1 else UpdateAdminCategoryRequest.IsActive._0
            },
        )
        return categoryResponse()
    }

    override suspend fun deleteCategory(id: Int): Response<Unit> {
        deletes++
        lastDeleteId = id
        return when (val result = writeResult) {
            is CategoryWriteResult.Saved -> Response.success(Unit)
            is CategoryWriteResult.Refused -> Response.error(result.status, "".toResponseBody(null))
            CategoryWriteResult.Unreachable -> throw java.io.IOException("offline")
        }
    }

    private fun categoryResponse(): Response<AdminCategoryResponse> = when (val result = writeResult) {
        is CategoryWriteResult.Saved -> Response.success(AdminCategoryResponse(result.category ?: rows.firstOrNull() ?: AdminCategoryResponseCategory(1, null, "A", "B", AdminCategoryResponseCategory.CategoryType.check, 0, 1, "now")))
        is CategoryWriteResult.Refused -> Response.error(result.status, "".toResponseBody(null))
        CategoryWriteResult.Unreachable -> throw java.io.IOException("offline")
    }

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
    override suspend fun timeSlots(): Response<TimeSlotsResponse> = error("unused")
    override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse> = error("unused")
}

private class CategoryProfileApi(role: String) : AuthApi {
    private val data = ProfileResponse(user = ProfileResponseUser(42, "admin", "Admin", role))
    override suspend fun profile() = Response.success(data)
    override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("unused")
    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("unused")
    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("unused")
    override suspend fun me(): Response<ProfileResponse> = Response.success(data)
    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("unused")
    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("unused")
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error("unused")
    override suspend fun register(body: RegisterRequest): Response<RegisterResponse> = error("unused")
}

private class CategoryStore : TokenStore {
    override fun accessToken() = "token"
    override fun refreshToken() = "refresh"
    override fun role() = null
    override fun save(accessToken: String, refreshToken: String) = Unit
    override fun saveRole(role: String) = Unit
    override fun clear() = Unit
}
