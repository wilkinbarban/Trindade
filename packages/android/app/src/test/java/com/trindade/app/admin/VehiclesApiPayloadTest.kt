package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
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
 * Validates wire payload serialization for [AdminApi] vehicle operations over real Retrofit and MockWebServer.
 */
class VehiclesApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()

    @Test(timeout = 5000L)
    fun `updateVehicle with active status sends numeric is_active 1 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(vehicleAnswer(vehicleId = VEHICLE_ID, isActive = 1)))

            runBlocking {
                wireApi(server).updateVehicle(
                    id = VEHICLE_ID,
                    body = UpdateAdminVehicleRequest(isActive = UpdateAdminVehicleRequest.IsActive._1),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/vehicles/$VEHICLE_ID", sent.path)
            assertEquals("""{"is_active":1}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateVehicle with inactive status sends numeric is_active 0 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(vehicleAnswer(vehicleId = VEHICLE_ID, isActive = 0)))

            runBlocking {
                wireApi(server).updateVehicle(
                    id = VEHICLE_ID,
                    body = UpdateAdminVehicleRequest(isActive = UpdateAdminVehicleRequest.IsActive._0),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/vehicles/$VEHICLE_ID", sent.path)
            assertEquals("""{"is_active":0}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `vehicles lists vehicles from server`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"vehicles":[${vehicleInner(1, "Caminhão 01", "ABC-1234", 1)}]}"""))

            val response = runBlocking {
                wireApi(server).vehicles()
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/vehicles", sent.path)
            assertEquals(1, response.body()?.vehicles?.size)
            assertEquals("Caminhão 01", response.body()?.vehicles?.first()?.description)
            assertEquals("ABC-1234", response.body()?.vehicles?.first()?.licensePlate)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createVehicle sends create payload on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody(vehicleAnswer(vehicleId = VEHICLE_ID, isActive = 1)))

            val response = runBlocking {
                wireApi(server).createVehicle(
                    CreateAdminVehicleRequest(
                        description = "Van Nova",
                        licensePlate = "XYZ-9999",
                    ),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/vehicles", sent.path)
            assertEquals(201, response.code())
            assertEquals("""{"description":"Van Nova","license_plate":"XYZ-9999"}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteVehicle sends delete on wire and parses boolean success response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

            val response = runBlocking {
                wireApi(server).deleteVehicle(VEHICLE_ID)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/vehicles/$VEHICLE_ID", sent.path)
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
    fun `deleteVehicle parses boolean false or missing success`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false}"""))

            val response = runBlocking {
                wireApi(server).deleteVehicle(VEHICLE_ID)
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
    fun `deleteVehicle throws on malformed JSON payload`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""not-json"""))

            try {
                runBlocking {
                    wireApi(server).deleteVehicle(VEHICLE_ID)
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

    private fun vehicleAnswer(vehicleId: Int, isActive: Int): String =
        """{"vehicle":${vehicleInner(vehicleId, "Caminhão 01", "ABC-1234", isActive)}}"""

    private fun vehicleInner(vehicleId: Int, description: String, licensePlate: String, isActive: Int): String =
        """{"id":$vehicleId,"description":"$description","license_plate":"$licensePlate","is_active":$isActive,"created_at":"2026-09-22T10:00:00.000Z"}"""

    companion object {
        private const val VEHICLE_ID = 42
    }
}
