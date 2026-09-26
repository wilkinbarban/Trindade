package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.UpdateAdminTaskRequest
import com.trindade.app.network.AdminApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class TasksRepositoryTest {
    @Test fun `reads categories and tasks and sends task mutations on the admin routes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"categories":[]}""").addHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setBody("""{"tasks":[]}""").addHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"task":{"id":7,"category_id":2,"name_pt":"Nova","name_es":"Nueva","temperature_readings":1,"is_active":1,"created_by_user_id":null,"created_at":"2026-01-01","task_type":"check"}}""").addHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("""{"success":true}""").addHeader("Content-Type", "application/json"))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                .build().create(AdminApi::class.java)
            val repository = TasksRepository(api)
            assertEquals(emptyList<Any>(), repository.categories())
            assertEquals(emptyList<Any>(), repository.tasks())
            assertEquals("Nova", (repository.create(CreateAdminTaskRequest(categoryId = 2, namePt = "Nova")) as TaskWriteResult.Saved).task.namePt)
            assertEquals(TaskWriteResult.Refused(403), repository.update(7, UpdateAdminTaskRequest(isActive = UpdateAdminTaskRequest.IsActive._0)))
            assertEquals(TaskWriteResult.Deleted, repository.delete(7))
            assertEquals("/api/admin/categories", server.takeRequest().path)
            assertEquals("/api/admin/tasks", server.takeRequest().path)
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/admin/tasks", create.path)
            assertTrue(create.body.readUtf8().contains("\"category_id\":2"))
            val update = server.takeRequest()
            assertEquals("PATCH", update.method)
            assertEquals("/api/admin/tasks/7", update.path)
            assertTrue(update.body.readUtf8().contains("\"is_active\":0"))
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/admin/tasks/7", delete.path)
        }
    }

    @Test fun `refused reads are not mistaken for empty catalogs and write refusals retain status`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(400))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                .build().create(AdminApi::class.java)
            val repository = TasksRepository(api)
            assertNull(repository.categories())
            assertNull(repository.tasks())
            assertEquals(TaskWriteResult.Refused(400), repository.create(CreateAdminTaskRequest(categoryId = 2, nameEs = "Nueva")))
        }
    }
}
