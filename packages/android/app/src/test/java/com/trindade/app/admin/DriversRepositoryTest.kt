package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
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

class DriversRepositoryTest {
    @Test fun `reads drivers and sends create and update on only the backend routes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"drivers":[$driver]}"""))
            server.enqueue(json("""{"drivers":[]}"""))
            server.enqueue(json("""{"driver":$driver}""", 201))
            server.enqueue(json("""{"driver":$driver}"""))
            val repository = repository(server)

            assertEquals(1, repository.drivers()?.single()?.id)
            assertEquals(emptyList<Any>(), repository.drivers())
            assertEquals("Ana", (repository.create(CreateAdminDriverRequest(
                name = "Ana", driverType = CreateAdminDriverRequest.DriverType.fletero,
            )) as DriverWriteResult.Saved).driver.name)
            assertEquals("Ana", (repository.update(1, UpdateAdminDriverRequest(
                isActive = UpdateAdminDriverRequest.IsActive._0,
            )) as DriverWriteResult.Saved).driver.name)

            val read = server.takeRequest()
            assertEquals("GET", read.method)
            assertEquals("/api/admin/drivers", read.path)
            assertEquals("GET", server.takeRequest().method)
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/admin/drivers", create.path)
            assertEquals("Ana", Json.parseToJsonElement(create.body.readUtf8()).jsonObject["name"]?.toString()?.trim('"'))
            val update = server.takeRequest()
            assertEquals("PATCH", update.method)
            assertEquals("/api/admin/drivers/1", update.path)
            assertEquals("0", Json.parseToJsonElement(update.body.readUtf8()).jsonObject["is_active"]?.toString())
        }
    }

    @Test fun `a refused read remains null and writes retain refusal status`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(403))
            val repository = repository(server)

            assertNull(repository.drivers())
            assertEquals(
                DriverWriteResult.Refused(403),
                repository.create(CreateAdminDriverRequest(name = "Ana")),
            )
        }
    }

    @Test fun `a disconnected write is unreachable`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertEquals(
                DriverWriteResult.Unreachable,
                repository(server).create(CreateAdminDriverRequest(name = "Ana")),
            )
        }
    }

    private fun repository(server: MockWebServer) = DriversRepository(
        Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(AdminApi::class.java),
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    private companion object {
        const val driver = """{"id":1,"name":"Ana","license_plate":null,"driver_type":"fletero","is_active":1,"created_by_user_id":8,"created_at":"2026-01-01"}"""
    }
}
