package com.trindade.app.admin

import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.CreateAdminDriverRequest
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
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization for [AdminApi] driver operations over real Retrofit and MockWebServer.
 *
 * Validates wire contracts for updateDriver, drivers, and createDriver.
 * Note: DTO conversion (e.g. UpdateAdminDriverRequest to JsonObject) is owned by DriversRepository
 * and is not claimed to be tested here.
 */
class DriversApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()
    private val client = OkHttpClient()

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test(timeout = 5000L)
    fun `updateDriver with active status sends numeric is_active 1 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(driverAnswer(driverId = DRIVER_ID, isActive = 1)),
            )

            val payload = JsonObject(mapOf("is_active" to JsonPrimitive(1)))
            val response = runBlocking {
                wireApi(server).updateDriver(
                    id = DRIVER_ID,
                    body = payload,
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/drivers/$DRIVER_ID", sent.path)
            assertEquals("""{"is_active":1}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(DRIVER_ID, body?.driver?.id)
            assertEquals(1, body?.driver?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateDriver with inactive status sends numeric is_active 0 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(driverAnswer(driverId = DRIVER_ID, isActive = 0)),
            )

            val payload = JsonObject(mapOf("is_active" to JsonPrimitive(0)))
            val response = runBlocking {
                wireApi(server).updateDriver(
                    id = DRIVER_ID,
                    body = payload,
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/drivers/$DRIVER_ID", sent.path)
            assertEquals("""{"is_active":0}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(DRIVER_ID, body?.driver?.id)
            assertEquals(0, body?.driver?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `drivers lists active and inactive drivers from server`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"drivers":[${driverInner(1, "André", 1)},${driverInner(2, "Beatriz", 0)}]}"""),
            )

            val response = runBlocking {
                wireApi(server).drivers()
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/drivers", sent.path)
            assertTrue(response.isSuccessful)
            val drivers = response.body()?.drivers
            assertNotNull(drivers)
            assertEquals(2, drivers?.size)
            assertEquals(1, drivers?.get(0)?.id)
            assertEquals("André", drivers?.get(0)?.name)
            assertEquals(1, drivers?.get(0)?.isActive)
            assertEquals(2, drivers?.get(1)?.id)
            assertEquals("Beatriz", drivers?.get(1)?.name)
            assertEquals(0, drivers?.get(1)?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createDriver with non-default casa sends explicit driver_type on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(driverAnswer(driverId = DRIVER_ID, isActive = 1, name = "Carlos Casa", driverType = "casa")),
            )

            val request = CreateAdminDriverRequest(
                name = "Carlos Casa",
                driverType = CreateAdminDriverRequest.DriverType.casa,
            )
            val response = runBlocking {
                wireApi(server).createDriver(request)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/drivers", sent.path)
            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("Carlos Casa", sentBody["name"]?.jsonPrimitive?.content)
            assertEquals("casa", sentBody["driver_type"]?.jsonPrimitive?.content)
            assertEquals(201, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(DRIVER_ID, body?.driver?.id)
            assertEquals(AdminDriverResponseDriver.DriverType.casa, body?.driver?.driverType)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createDriver with default fletero omits driver_type on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(driverAnswer(driverId = DRIVER_ID, isActive = 1, name = "Carlos Fletero", driverType = "fletero")),
            )

            val request = CreateAdminDriverRequest(
                name = "Carlos Fletero",
                driverType = CreateAdminDriverRequest.DriverType.fletero,
            )
            val response = runBlocking {
                wireApi(server).createDriver(request)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/drivers", sent.path)
            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("Carlos Fletero", sentBody["name"]?.jsonPrimitive?.content)
            assertFalse(sentBody.containsKey("driver_type"))
            assertEquals(201, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(DRIVER_ID, body?.driver?.id)
            assertEquals(AdminDriverResponseDriver.DriverType.fletero, body?.driver?.driverType)
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

    private fun driverAnswer(
        driverId: Int,
        isActive: Int,
        name: String = "Carlos Fletero",
        driverType: String = "fletero",
    ): String =
        """{"driver":${driverInner(driverId, name, isActive, driverType)}}"""

    private fun driverInner(
        driverId: Int,
        name: String,
        isActive: Int,
        driverType: String = "fletero",
    ): String =
        """{"id":$driverId,"name":"$name","license_plate":null,"phone":null,"driver_type":"$driverType","is_active":$isActive,"created_by_user_id":null,"created_at":"2026-01-01T00:00:00Z"}"""

    private companion object {
        const val DRIVER_ID = 55
    }
}
