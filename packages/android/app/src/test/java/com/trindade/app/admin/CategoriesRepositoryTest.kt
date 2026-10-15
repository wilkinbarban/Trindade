package com.trindade.app.admin

import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class CategoriesRepositoryTest {

    @Test
    fun `categories returns list on success and null on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)

        val categories = repo.categories()
        assertEquals(2, categories?.size)
        assertEquals("Higienização", categories?.first()?.namePt)

        val failingRepo = CategoriesRepository(FakeAdminApi(categoriesToReturn = null))
        assertNull(failingRepo.categories())
    }

    @Test
    fun `createCategory returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)

        val request = CreateAdminCategoryRequest(
            namePt = "Nova Categoria",
            nameEs = "Nueva Categoría",
            categoryType = CreateAdminCategoryRequest.CategoryType.check,
            sortOrder = 10,
        )
        val result = repo.createCategory(request)
        assertTrue(result is CategoryWriteResult.Saved)
        assertEquals("Nova Categoria", (result as CategoryWriteResult.Saved).category.namePt)
        assertEquals(listOf(request), api.createdCategoryRequests)

        val failingApi = FakeAdminApi(
            createCategoryResponse = Response.error(400, "{\"error\":\"Invalid input\"}".toResponseBody(null)),
        )
        val failingResult = CategoriesRepository(failingApi).createCategory(request)
        assertTrue(failingResult is CategoryWriteResult.Refused)
        assertEquals(400, (failingResult as CategoryWriteResult.Refused).statusCode)
    }

    @Test
    fun `updateCategory returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)

        val request = UpdateAdminCategoryRequest(
            namePt = "Nome Atualizado",
            categoryType = UpdateAdminCategoryRequest.CategoryType.temperature,
            isActive = UpdateAdminCategoryRequest.IsActive._0,
        )
        val result = repo.updateCategory(1, request)
        assertTrue(result is CategoryWriteResult.Saved)
        assertEquals("Nome Atualizado", (result as CategoryWriteResult.Saved).category.namePt)
        assertEquals(1, api.updatedCategoryRequests.size)
        assertEquals(1 to request, api.updatedCategoryRequests.first())

        val failingApi = FakeAdminApi(
            updateCategoryResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null)),
        )
        val failingResult = CategoriesRepository(failingApi).updateCategory(1, request)
        assertTrue(failingResult is CategoryWriteResult.Refused)
        assertEquals(403, (failingResult as CategoryWriteResult.Refused).statusCode)
    }

    @Test
    fun `deleteCategory returns Changed on success and Refused on constraint error`() = runTest {
        val api = FakeAdminApi()
        val repo = CategoriesRepository(api)

        val result = repo.deleteCategory(1)
        assertTrue(result is CategoryLifecycleResult.Changed)
        assertEquals(listOf(1), api.deletedCategoryIds)

        val failingApi = FakeAdminApi(
            deleteCategoryResponse = Response.error(400, "{\"error\":\"FK constraint\"}".toResponseBody(null)),
        )
        val failingResult = CategoriesRepository(failingApi).deleteCategory(1)
        assertTrue(failingResult is CategoryLifecycleResult.Refused)
        assertEquals(400, (failingResult as CategoryLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteCategory returns Refused when response body success is false`() = runTest {
        val api = FakeAdminApi(
            deleteCategoryResponse = Response.success(JsonObject(mapOf("success" to JsonPrimitive(false)))),
        )
        val result = CategoriesRepository(api).deleteCategory(1)
        assertTrue(result is CategoryLifecycleResult.Refused)
        assertEquals(200, (result as CategoryLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteCategory returns Refused when response body success is string rather than boolean`() = runTest {
        val api = FakeAdminApi(
            deleteCategoryResponse = Response.success(JsonObject(mapOf("success" to JsonPrimitive("true")))),
        )
        val result = CategoriesRepository(api).deleteCategory(1)
        assertTrue(result is CategoryLifecycleResult.Refused)
        assertEquals(200, (result as CategoryLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteCategory returns Refused when response body success is missing`() = runTest {
        val api = FakeAdminApi(
            deleteCategoryResponse = Response.success(JsonObject(emptyMap())),
        )
        val result = CategoriesRepository(api).deleteCategory(1)
        assertTrue(result is CategoryLifecycleResult.Refused)
        assertEquals(200, (result as CategoryLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `createCategory returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun createCategory(body: CreateAdminCategoryRequest): Response<com.trindade.app.contract.models.AdminCategoryResponse> {
                throw IOException("Network unreachable")
            }
        }
        val repo = CategoriesRepository(throwingApi)
        val result = repo.createCategory(CreateAdminCategoryRequest(namePt = "Categoria"))
        assertTrue(result is CategoryWriteResult.Unreachable)
    }

    @Test
    fun `updateCategory returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun updateCategory(
                id: Int,
                body: kotlinx.serialization.json.JsonObject,
            ): Response<com.trindade.app.contract.models.AdminCategoryResponse> {
                throw IOException("Network unreachable")
            }
        }
        val repo = CategoriesRepository(throwingApi)
        val result = repo.updateCategory(1, UpdateAdminCategoryRequest(namePt = "Categoria"))
        assertTrue(result is CategoryWriteResult.Unreachable)
    }

    @Test
    fun `deleteCategory returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun deleteCategory(id: Int): Response<JsonObject> {
                throw IOException("Network unreachable")
            }
        }
        val repo = CategoriesRepository(throwingApi)
        val result = repo.deleteCategory(1)
        assertTrue(result is CategoryLifecycleResult.Unreachable)
    }
}
