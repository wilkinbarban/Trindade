package com.trindade.app.admin

import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization for [AdminApi] category operations over real Retrofit and MockWebServer.
 *
 * Covers updateCategory (PATCH with numeric is_active), categories (GET list),
 * createCategory (POST with non-default and default field handling under encodeDefaults=false),
 * and deleteCategory (DELETE with 200, 204, and 403 responses without JSON body claims).
 */
class CategoriesApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()
    private val client = OkHttpClient()

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test(timeout = 5000L)
    fun `updateCategory with active status sends numeric is_active 1 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(categoryAnswer(categoryId = CATEGORY_ID, isActive = 1)),
            )

            val payload = JsonObject(mapOf("is_active" to JsonPrimitive(1)))
            val response = runBlocking {
                wireApi(server).updateCategory(
                    id = CATEGORY_ID,
                    body = payload,
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/categories/$CATEGORY_ID", sent.path)
            assertEquals("""{"is_active":1}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(CATEGORY_ID, body?.category?.id)
            assertEquals(1, body?.category?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateCategory with inactive status sends numeric is_active 0 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(categoryAnswer(categoryId = CATEGORY_ID, isActive = 0)),
            )

            val payload = JsonObject(mapOf("is_active" to JsonPrimitive(0)))
            val response = runBlocking {
                wireApi(server).updateCategory(
                    id = CATEGORY_ID,
                    body = payload,
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/categories/$CATEGORY_ID", sent.path)
            assertEquals("""{"is_active":0}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(CATEGORY_ID, body?.category?.id)
            assertEquals(0, body?.category?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `categories lists categories from server`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"categories":[${categoryInner(categoryId = 1, namePt = "Higienização", isActive = 1)}]}"""),
            )

            val response = runBlocking {
                wireApi(server).categories()
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/categories", sent.path)
            assertTrue(response.isSuccessful)
            val categories = response.body()?.categories
            assertNotNull(categories)
            assertEquals(1, categories?.size)
            assertEquals(1, categories?.first()?.id)
            assertEquals("Higienização", categories?.first()?.namePt)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createCategory with non-default category type and sort order sends explicit fields on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        categoryAnswer(
                            categoryId = CATEGORY_ID,
                            isActive = 1,
                            namePt = "Temperatura Congelados",
                            nameEs = "Temperatura Congelados",
                            categoryType = "temperature",
                            sortOrder = 2,
                        ),
                    ),
            )

            val request = CreateAdminCategoryRequest(
                namePt = "Temperatura Congelados",
                nameEs = "Temperatura Congelados",
                categoryType = CreateAdminCategoryRequest.CategoryType.temperature,
                sortOrder = 2,
            )
            val response = runBlocking {
                wireApi(server).createCategory(request)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/categories", sent.path)
            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("Temperatura Congelados", sentBody["name_pt"]?.jsonPrimitive?.content)
            assertEquals("Temperatura Congelados", sentBody["name_es"]?.jsonPrimitive?.content)
            assertEquals("temperature", sentBody["category_type"]?.jsonPrimitive?.content)
            assertEquals("2", sentBody["sort_order"]?.jsonPrimitive?.content)
            assertEquals(201, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(CATEGORY_ID, body?.category?.id)
            assertEquals(AdminCategoryResponseCategory.CategoryType.temperature, body?.category?.categoryType)
            assertEquals(2, body?.category?.sortOrder)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createCategory with default category type and sort order omits default fields on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        categoryAnswer(
                            categoryId = CATEGORY_ID,
                            isActive = 1,
                            namePt = "Higienização",
                            nameEs = "Higienización",
                            categoryType = "check",
                            sortOrder = 0,
                        ),
                    ),
            )

            val request = CreateAdminCategoryRequest(
                namePt = "Higienização",
                nameEs = "Higienización",
                categoryType = CreateAdminCategoryRequest.CategoryType.check,
                sortOrder = 0,
            )
            val response = runBlocking {
                wireApi(server).createCategory(request)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/categories", sent.path)
            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("Higienização", sentBody["name_pt"]?.jsonPrimitive?.content)
            assertEquals("Higienización", sentBody["name_es"]?.jsonPrimitive?.content)
            assertFalse(sentBody.containsKey("category_type"))
            assertFalse(sentBody.containsKey("sort_order"))
            assertEquals(201, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(CATEGORY_ID, body?.category?.id)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteCategory sends delete on wire and handles 200 success response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200))

            val response = runBlocking {
                wireApi(server).deleteCategory(CATEGORY_ID)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/categories/$CATEGORY_ID", sent.path)
            assertEquals(200, response.code())
            assertTrue(response.isSuccessful)
            assertEquals(Unit, response.body())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteCategory sends delete on wire and handles 204 no content response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(204))

            val response = runBlocking {
                wireApi(server).deleteCategory(CATEGORY_ID)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/categories/$CATEGORY_ID", sent.path)
            assertEquals(204, response.code())
            assertTrue(response.isSuccessful)
            assertNull(response.body())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteCategory sends delete on wire and handles 403 refusal response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))

            val response = runBlocking {
                wireApi(server).deleteCategory(CATEGORY_ID)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/categories/$CATEGORY_ID", sent.path)
            assertEquals(403, response.code())
            assertFalse(response.isSuccessful)
            assertNull(response.body())
        } finally {
            server.shutdown()
        }
    }

    private fun wireApi(server: MockWebServer): AdminApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AdminApi::class.java)

    private fun categoryAnswer(
        categoryId: Int,
        isActive: Int,
        namePt: String = "Higienização",
        nameEs: String = "Higienización",
        categoryType: String = "check",
        sortOrder: Int = 1,
    ): String =
        """{"category":${categoryInner(categoryId, namePt, nameEs, categoryType, sortOrder, isActive)}}"""

    private fun categoryInner(
        categoryId: Int,
        namePt: String = "Higienização",
        nameEs: String = "Higienización",
        categoryType: String = "check",
        sortOrder: Int = 1,
        isActive: Int = 1,
    ): String =
        """{"id":$categoryId,"parent_category_id":null,"name_pt":"$namePt","name_es":"$nameEs","category_type":"$categoryType","sort_order":$sortOrder,"is_active":$isActive,"created_at":"2026-09-22T10:00:00.000Z"}"""

    private companion object {
        const val CATEGORY_ID = 42
    }
}
