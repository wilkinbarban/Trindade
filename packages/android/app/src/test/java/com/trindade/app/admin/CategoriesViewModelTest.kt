package com.trindade.app.admin

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.AdminCategoryResponseCategory
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

class CategoriesViewModelTest {

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
    fun `init loads categories from repository for administrator`() {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)
        val auth = createAuthRepository(role = RolePolicy.ADMIN)
        val vm = CategoriesViewModel(repo, auth)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(2, state.categories.size)
        assertEquals("Higienização", state.categories.first().namePt)
        assertTrue(state.isAdmin)
    }

    @Test
    fun `openCreateForm initializes form for administrator`() {
        val vm = CategoriesViewModel(CategoriesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        vm.openCreateForm()

        val form = vm.uiState.value.form
        assertNotNull(form)
        assertFalse(form!!.isEditing)
        assertEquals("", form.namePt)
        assertEquals(AdminCategoryResponseCategory.CategoryType.check, form.categoryType)
    }

    @Test
    fun `openCreateForm is ignored for worker`() {
        val vm = CategoriesViewModel(CategoriesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.WORKER))
        vm.openCreateForm()

        assertNull(vm.uiState.value.form)
    }

    @Test
    fun `openEditForm seeds existing category values`() {
        val vm = CategoriesViewModel(CategoriesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        val category = FakeAdminApi.defaultCategories().first()
        vm.openEditForm(category)

        val form = vm.uiState.value.form
        assertNotNull(form)
        assertTrue(form!!.isEditing)
        assertEquals(category.id, form.categoryId)
        assertEquals("Higienização", form.namePt)
        assertEquals("Higienización", form.nameEs)
        assertEquals(AdminCategoryResponseCategory.CategoryType.check, form.categoryType)
        assertEquals("1", form.sortOrder)
    }

    @Test
    fun `closeForm resets form state`() {
        val vm = CategoriesViewModel(CategoriesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        vm.openCreateForm()
        assertNotNull(vm.uiState.value.form)

        vm.closeForm()
        assertNull(vm.uiState.value.form)
    }

    @Test
    fun `saveCategory creates category on valid submit`() {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)
        val vm = CategoriesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))

        vm.openCreateForm()
        vm.onNamePtChanged("Nova Categoria")
        vm.onNameEsChanged("Nueva Categoría")
        vm.onCategoryTypeChanged(AdminCategoryResponseCategory.CategoryType.temperature)
        vm.onSortOrderChanged("5")

        vm.saveCategory()

        assertNull(vm.uiState.value.form)
        assertEquals("Categoria criada com sucesso.", vm.uiState.value.message)
        assertFalse(vm.uiState.value.isError)
        assertEquals(1, api.createdCategoryRequests.size)
        assertEquals("Nova Categoria", api.createdCategoryRequests.first().namePt)
    }

    @Test
    fun `saveCategory updates category when editing`() {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)
        val vm = CategoriesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))
        val category = FakeAdminApi.defaultCategories().first()

        vm.openEditForm(category)
        vm.onNamePtChanged("Nome Alterado")
        vm.saveCategory()

        assertNull(vm.uiState.value.form)
        assertEquals("Categoria atualizada com sucesso.", vm.uiState.value.message)
        assertEquals(1, api.updatedCategoryRequests.size)
        assertEquals("Nome Alterado", api.updatedCategoryRequests.first().second.namePt)
    }

    @Test
    fun `toggleCategoryActive flips active status`() {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)
        val vm = CategoriesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))
        val category = FakeAdminApi.defaultCategories().first() // isActive = 1

        vm.toggleCategoryActive(category)

        assertEquals(1, api.updatedCategoryRequests.size)
        assertEquals(
            com.trindade.app.contract.models.UpdateAdminCategoryRequest.IsActive._0,
            api.updatedCategoryRequests.first().second.isActive,
        )
    }

    @Test
    fun `requestDelete and cancelDelete manage confirmation state`() {
        val vm = CategoriesViewModel(CategoriesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        val category = FakeAdminApi.defaultCategories().first()

        vm.requestDelete(category)
        assertEquals(category, vm.uiState.value.deleteConfirmCategory)

        vm.cancelDelete()
        assertNull(vm.uiState.value.deleteConfirmCategory)
    }

    @Test
    fun `confirmDelete deletes category and displays success message`() {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)
        val vm = CategoriesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))
        val category = FakeAdminApi.defaultCategories().first()

        vm.requestDelete(category)
        vm.confirmDelete()

        assertNull(vm.uiState.value.deleteConfirmCategory)
        assertEquals("Categoria excluída com sucesso.", vm.uiState.value.message)
        assertFalse(vm.uiState.value.isError)
        assertEquals(listOf(category.id), api.deletedCategoryIds)
    }

    @Test
    fun `confirmDelete surfaces 400 foreign key constraint refusal message`() {
        val api = FakeAdminApi(
            deleteCategoryResponse = Response.error(400, "{\"error\":\"FK constraint\"}".toResponseBody(null)),
        )
        val repo = CategoriesRepository(api)
        val vm = CategoriesViewModel(repo, createAuthRepository(role = RolePolicy.ADMIN))
        val category = FakeAdminApi.defaultCategories().first()

        vm.requestDelete(category)
        vm.confirmDelete()

        assertNull(vm.uiState.value.deleteConfirmCategory)
        assertTrue(vm.uiState.value.isError)
        assertEquals(
            "Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo.",
            vm.uiState.value.message,
        )
    }

    @Test
    fun `handleBack dismisses delete dialog first then form`() {
        val vm = CategoriesViewModel(CategoriesRepository(FakeAdminApi()), createAuthRepository(role = RolePolicy.ADMIN))
        val category = FakeAdminApi.defaultCategories().first()

        vm.openCreateForm()
        vm.requestDelete(category)

        // First back dismisses delete dialog
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.deleteConfirmCategory)
        assertNotNull(vm.uiState.value.form)

        // Second back dismisses form
        assertTrue(vm.handleBack())
        assertNull(vm.uiState.value.form)

        // Third back returns false (navigates screen)
        assertFalse(vm.handleBack())
    }

    @Test
    fun `saveCategory rechecks admin and refuses write for worker session without network call`() {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)
        val auth = createAuthRepository(role = RolePolicy.WORKER)
        val vm = CategoriesViewModel(repo, auth)

        // Attempt to open form as worker (should fail)
        vm.openCreateForm()
        assertNull(vm.uiState.value.form)

        // Even if saveCategory is called, it rechecks admin, sets error message, and does no network call
        vm.saveCategory()

        assertTrue(api.createdCategoryRequests.isEmpty())
        assertTrue(api.updatedCategoryRequests.isEmpty())
        assertEquals(0, api.deletedCategoryIds.size)
    }

    @Test
    fun `saveCategory aborts if session role changes to worker before network call`() {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)
        val tokenStore = FakeTokenStore(role = RolePolicy.ADMIN)
        val authApi = FakeAuthApi()
        val auth = AuthRepository(authApi, tokenStore, Json { ignoreUnknownKeys = true })
        val vm = CategoriesViewModel(repo, auth)

        vm.openCreateForm()
        vm.onNamePtChanged("Nova Categoria")

        // Role changes to worker right before save
        tokenStore.saveRole(RolePolicy.WORKER)

        vm.saveCategory()

        assertTrue(api.createdCategoryRequests.isEmpty())
        assertTrue(api.updatedCategoryRequests.isEmpty())
        assertTrue(vm.uiState.value.isError)
        assertEquals("Acesso restrito a administradores.", vm.uiState.value.message)
    }

    @Test
    fun `toggleCategoryActive and confirmDelete refuse write for worker without network call`() {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)
        val auth = createAuthRepository(role = RolePolicy.WORKER)
        val vm = CategoriesViewModel(repo, auth)
        val category = FakeAdminApi.defaultCategories().first()

        vm.toggleCategoryActive(category)
        assertTrue(api.updatedCategoryRequests.isEmpty())

        vm.requestDelete(category)
        assertNull(vm.uiState.value.deleteConfirmCategory)

        vm.confirmDelete()
        assertTrue(api.deletedCategoryIds.isEmpty())
    }
}
