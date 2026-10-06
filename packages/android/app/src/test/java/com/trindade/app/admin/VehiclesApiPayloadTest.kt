package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminVehicleRequest
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
 * Validates wire payload serialization for [AdminApi] vehicle operations over real Retrofit and MockWebServer.
 *
 * Covers updateVehicle (PATCH with numeric is_active), vehicles (GET list),
 * createVehicle (POST with required fields), and deleteVehicle (DELETE with 200, 204, and 403 responses without JSON body claims).
 */
class VehiclesApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()
    private val client = OkHttpClient()

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test(timeout = 5000L)
    fun `updateVehicle with active status sends numeric is_active 1 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(vehicleAnswer(vehicleId = VEHICLE_ID, isActive = 1)),
            )

            val payload = JsonObject(mapOf("is_active" to JsonPrimitive(1)))
            val response = runBlocking {
                wireApi(server).updateVehicle(
                    id = VEHICLE_ID,
                    body = payload,
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/vehicles/$VEHICLE_ID", sent.path)
            assertEquals("""{"is_active":1}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(VEHICLE_ID, body?.vehicle?.id)
            assertEquals(1, body?.vehicle?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateVehicle with inactive status sends numeric is_active 0 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(vehicleAnswer(vehicleId = VEHICLE_ID, isActive = 0)),
            )

            val payload = JsonObject(mapOf("is_active" to JsonPrimitive(0)))
            val response = runBlocking {
                wireApi(server).updateVehicle(
                    id = VEHICLE_ID,
                    body = payload,
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/vehicles/$VEHICLE_ID", sent.path)
            assertEquals("""{"is_active":0}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(VEHICLE_ID, body?.vehicle?.id)
            assertEquals(0, body?.vehicle?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `vehicles lists vehicles from server`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"vehicles":[${vehicleInner(vehicleId = 1, description = "Caminhão 01", licensePlate = "ABC-1234", isActive = 1)}]}"""),
            )

            val response = runBlocking {
                wireApi(server).vehicles()
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/vehicles", sent.path)
            assertTrue(response.isSuccessful)
            val vehicles = response.body()?.vehicles
            assertNotNull(vehicles)
            assertEquals(1, vehicles?.size)
            val first = vehicles?.first()
            assertEquals(1, first?.id)
            assertEquals("Caminhão 01", first?.description)
            assertEquals("ABC-1234", first?.licensePlate)
            assertEquals(1, first?.isActive)
            assertEquals("2026-09-22T10:00:00.000Z", first?.createdAt)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createVehicle sends create payload on wire and decodes created vehicle`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        vehicleAnswer(
                            vehicleId = VEHICLE_ID,
                            isActive = 1,
                            description = "Van Nova",
                            licensePlate = "XYZ-9999",
                        ),
                    ),
            )

            val request = CreateAdminVehicleRequest(
                description = "Van Nova",
                licensePlate = "XYZ-9999",
            )
            val response = runBlocking {
                wireApi(server).createVehicle(request)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/vehicles", sent.path)
            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("Van Nova", sentBody["description"]?.jsonPrimitive?.content)
            assertEquals("XYZ-9999", sentBody["license_plate"]?.jsonPrimitive?.content)
            assertEquals(201, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(VEHICLE_ID, body?.vehicle?.id)
            assertEquals("Van Nova", body?.vehicle?.description)
            assertEquals("XYZ-9999", body?.vehicle?.licensePlate)
            assertEquals(1, body?.vehicle?.isActive)
            assertEquals("2026-09-22T10:00:00.000Z", body?.vehicle?.createdAt)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteVehicle sends delete on wire and handles 200 response with JSON ignored as Unit`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success":true}"""),
            )

            val response = runBlocking {
                wireApi(server).deleteVehicle(VEHICLE_ID)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/vehicles/$VEHICLE_ID", sent.path)
            assertEquals(200, response.code())
            assertTrue(response.isSuccessful)
            assertEquals(Unit, response.body())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteVehicle sends delete on wire and handles 204 no content response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(204))

            val response = runBlocking {
                wireApi(server).deleteVehicle(VEHICLE_ID)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/vehicles/$VEHICLE_ID", sent.path)
            assertEquals(204, response.code())
            assertTrue(response.isSuccessful)
            assertNull(response.body())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteVehicle sends delete on wire and handles 403 refusal response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))

            val response = runBlocking {
                wireApi(server).deleteVehicle(VEHICLE_ID)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/vehicles/$VEHICLE_ID", sent.path)
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

    private fun vehicleAnswer(
        vehicleId: Int,
        isActive: Int,
        description: String = "Caminhão 01",
        licensePlate: String = "ABC-1234",
    ): String =
        """{"vehicle":${vehicleInner(vehicleId, description, licensePlate, isActive)}}"""

    private fun vehicleInner(
        vehicleId: Int,
        description: String = "Caminhão 01",
        licensePlate: String = "ABC-1234",
        isActive: Int = 1,
    ): String =
        """{"id":$vehicleId,"description":"$description","license_plate":"$licensePlate","is_active":$isActive,"created_at":"2026-09-22T10:00:00.000Z"}"""

    private companion object {
        const val VEHICLE_ID = 42
    }
}
