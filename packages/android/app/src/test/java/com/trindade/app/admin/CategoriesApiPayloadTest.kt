package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization for [AdminApi] category operations over real Retrofit and MockWebServer.
 */
class CategoriesApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()

    @Test(timeout = 5000L)
    fun `updateCategory with active status sends numeric is_active 1 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(categoryAnswer(categoryId = CATEGORY_ID, isActive = 1)))

            runBlocking {
                wireApi(server).updateCategory(
                    id = CATEGORY_ID,
                    body = UpdateAdminCategoryRequest(isActive = UpdateAdminCategoryRequest.IsActive._1),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/categories/$CATEGORY_ID", sent.path)
            assertEquals("""{"is_active":1}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateCategory with inactive status sends numeric is_active 0 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(categoryAnswer(categoryId = CATEGORY_ID, isActive = 0)))

            runBlocking {
                wireApi(server).updateCategory(
                    id = CATEGORY_ID,
                    body = UpdateAdminCategoryRequest(isActive = UpdateAdminCategoryRequest.IsActive._0),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/categories/$CATEGORY_ID", sent.path)
            assertEquals("""{"is_active":0}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `categories lists categories from server`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"categories":[${categoryInner(1, "Higienização", 1)}]}"""))

            val response = runBlocking {
                wireApi(server).categories()
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/categories", sent.path)
            assertEquals(1, response.body()?.categories?.size)
            assertEquals("Higienização", response.body()?.categories?.first()?.namePt)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createCategory sends create payload on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody(categoryAnswer(categoryId = CATEGORY_ID, isActive = 1)))

            val response = runBlocking {
                wireApi(server).createCategory(
                    CreateAdminCategoryRequest(
                        namePt = "Nova Categoria",
                        nameEs = "Nueva Categoria",
                        categoryType = CreateAdminCategoryRequest.CategoryType.check,
                        sortOrder = 1,
                    ),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/categories", sent.path)
            assertEquals(201, response.code())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteCategory sends delete on wire and parses boolean success response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

            val response = runBlocking {
                wireApi(server).deleteCategory(CATEGORY_ID)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/categories/$CATEGORY_ID", sent.path)
            assertEquals(200, response.code())
            val successPrimitive = response.body()?.get("success") as? JsonPrimitive
            org.junit.Assert.assertNotNull(successPrimitive)
            assertEquals(false, successPrimitive?.isString)
            assertEquals(true, successPrimitive?.booleanOrNull)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteCategory parses boolean false or missing success`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false}"""))

            val response = runBlocking {
                wireApi(server).deleteCategory(CATEGORY_ID)
            }

            assertEquals(200, response.code())
            val successPrimitive = response.body()?.get("success") as? JsonPrimitive
            org.junit.Assert.assertNotNull(successPrimitive)
            assertEquals(false, successPrimitive?.booleanOrNull)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteCategory throws on malformed JSON payload`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""not-json"""))

            try {
                runBlocking {
                    wireApi(server).deleteCategory(CATEGORY_ID)
                }
                org.junit.Assert.fail("Expected exception on malformed json")
            } catch (e: Exception) {
                org.junit.Assert.assertTrue(
                    e is kotlinx.serialization.SerializationException ||
                        e.cause is kotlinx.serialization.SerializationException,
                )
            }
        } finally {
            server.shutdown()
        }
    }

    private fun wireApi(server: MockWebServer): AdminApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AdminApi::class.java)

    private fun categoryAnswer(categoryId: Int, isActive: Int): String =
        """{"category":${categoryInner(categoryId, "Higienização", isActive)}}"""

    private fun categoryInner(categoryId: Int, namePt: String, isActive: Int): String =
        """{"id":$categoryId,"parent_category_id":null,"name_pt":"$namePt","name_es":"Higienización","category_type":"check","sort_order":1,"is_active":$isActive,"created_at":"2026-09-22T10:00:00.000Z"}"""

    companion object {
        private const val CATEGORY_ID = 42
    }
}
