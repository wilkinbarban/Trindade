package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
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

class VehiclesRepositoryTest {
    @Test fun `reads vehicles and sends typed create update and delete requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"vehicles":[$vehicle]}"""))
            server.enqueue(json("""{"vehicles":[]}"""))
            server.enqueue(json("""{"vehicle":$vehicle}""", 201))
            server.enqueue(json("""{"vehicle":$vehicle}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(json("""{"success":true}"""))
            val repository = repository(server)

            assertEquals(1, repository.vehicles()?.single()?.id)
            assertEquals(emptyList<Any>(), repository.vehicles())
            assertEquals("Casa BDG", (repository.create(CreateAdminVehicleRequest(
                description = "Casa BDG",
                licensePlate = "BDG",
            )) as VehicleWriteResult.Saved).vehicle?.description)
            assertEquals("Casa BDG", (repository.update(1, UpdateAdminVehicleRequest(
                isActive = UpdateAdminVehicleRequest.IsActive._0,
            )) as VehicleWriteResult.Saved).vehicle?.description)
            assertEquals(VehicleWriteResult.Saved(), repository.delete(1))
            assertEquals(VehicleWriteResult.Saved(), repository.delete(1))

            val read1 = server.takeRequest()
            assertEquals("GET", read1.method)
            assertEquals("/api/admin/vehicles", read1.path)
            val read2 = server.takeRequest()
            assertEquals("GET", read2.method)
            assertEquals("/api/admin/vehicles", read2.path)
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/admin/vehicles", create.path)
            assertEquals("Casa BDG", Json.parseToJsonElement(create.body.readUtf8()).jsonObject["description"]?.toString()?.trim('"'))
            val update = server.takeRequest()
            assertEquals("PATCH", update.method)
            assertEquals("/api/admin/vehicles/1", update.path)
            assertEquals("0", Json.parseToJsonElement(update.body.readUtf8()).jsonObject["is_active"]?.toString())
            val delete1 = server.takeRequest()
            assertEquals("DELETE", delete1.method)
            assertEquals("/api/admin/vehicles/1", delete1.path)
            val delete2 = server.takeRequest()
            assertEquals("DELETE", delete2.method)
            assertEquals("/api/admin/vehicles/1", delete2.path)
        }
    }

    @Test fun `failed reads stay null and writes preserve refusal status`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setResponseCode(404))
            val repository = repository(server)

            assertNull(repository.vehicles())
            assertEquals(VehicleWriteResult.Refused(400), repository.create(CreateAdminVehicleRequest(description = "x", licensePlate = "y")))
            assertEquals(VehicleWriteResult.Refused(404), repository.update(1, UpdateAdminVehicleRequest(description = "x")))
            assertEquals(VehicleWriteResult.Refused(400), repository.delete(1))
            assertEquals(VehicleWriteResult.Refused(404), repository.delete(999))
        }
    }

    @Test fun `disconnected vehicle writes are unreachable`() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
            val repository = repository(server)
            assertEquals(VehicleWriteResult.Unreachable, repository.create(CreateAdminVehicleRequest(description = "x", licensePlate = "y")))
            assertEquals(VehicleWriteResult.Unreachable, repository.update(1, UpdateAdminVehicleRequest(description = "x")))
            assertEquals(VehicleWriteResult.Unreachable, repository.delete(1))
        }
    }

    private fun repository(server: MockWebServer) = VehiclesRepository(
        Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(AdminApi::class.java),
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    private companion object {
        const val vehicle = """{"id":1,"description":"Casa BDG","license_plate":"BDG","is_active":1,"created_at":"2026-01-01"}"""
    }
}
