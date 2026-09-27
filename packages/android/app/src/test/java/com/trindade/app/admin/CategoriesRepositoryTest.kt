package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
import com.trindade.app.network.AdminApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class CategoriesRepositoryTest {
    @Test fun `reads categories and sends typed create update and delete requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"categories":[$category]}"""))
            server.enqueue(json("""{"categories":[]}"""))
            server.enqueue(json("""{"category":$category}""", 201))
            server.enqueue(json("""{"category":$category}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val repository = repository(server)

            assertEquals(1, repository.categories()?.single()?.id)
            assertEquals(emptyList<Any>(), repository.categories())
            assertEquals("Alimentos", (repository.create(CreateAdminCategoryRequest(
                namePt = "Alimentos", categoryType = CreateAdminCategoryRequest.CategoryType.check,
            )) as CategoryWriteResult.Saved).category?.namePt)
            assertEquals("Alimentos", (repository.update(1, UpdateAdminCategoryRequest(
                isActive = UpdateAdminCategoryRequest.IsActive._0,
            )) as CategoryWriteResult.Saved).category?.namePt)
            assertEquals(CategoryWriteResult.Saved(), repository.delete(1))

            assertEquals("GET", server.takeRequest().method)
            assertEquals("/api/admin/categories", server.takeRequest().path)
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/admin/categories", create.path)
            assertEquals("Alimentos", Json.parseToJsonElement(create.body.readUtf8()).jsonObject["name_pt"]?.toString()?.trim('"'))
            val update = server.takeRequest()
            assertEquals("PATCH", update.method)
            assertEquals("/api/admin/categories/1", update.path)
            assertEquals("0", Json.parseToJsonElement(update.body.readUtf8()).jsonObject["is_active"]?.toString())
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/admin/categories/1", delete.path)
        }
    }

    @Test fun `failed reads stay null and writes preserve refusal status`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(403))
            val repository = repository(server)

            assertNull(repository.categories())
            assertEquals(CategoryWriteResult.Refused(403), repository.create(CreateAdminCategoryRequest(namePt = "x")))
            assertEquals(CategoryWriteResult.Refused(403), repository.update(1, UpdateAdminCategoryRequest(namePt = "x")))
            assertEquals(CategoryWriteResult.Refused(403), repository.delete(1))
        }
    }

    @Test fun `disconnected category writes are unreachable`() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
            val repository = repository(server)
            assertEquals(CategoryWriteResult.Unreachable, repository.create(CreateAdminCategoryRequest(namePt = "x")))
            assertEquals(CategoryWriteResult.Unreachable, repository.update(1, UpdateAdminCategoryRequest(namePt = "x")))
            assertEquals(CategoryWriteResult.Unreachable, repository.delete(1))
        }
    }

    private fun repository(server: MockWebServer) = CategoriesRepository(
        Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(AdminApi::class.java),
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    private companion object {
        const val category = """{"id":1,"parent_category_id":null,"name_pt":"Alimentos","name_es":"Alimentos","category_type":"check","sort_order":0,"is_active":1,"created_at":"2026-01-01"}"""
    }
}
