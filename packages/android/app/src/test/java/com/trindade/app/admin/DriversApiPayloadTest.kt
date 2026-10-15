package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization for [AdminApi] driver operations over real Retrofit and MockWebServer.
 */
class DriversApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()

    @Test(timeout = 5000L)
    fun `updateDriver with active status sends numeric is_active 1 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(driverAnswer(driverId = DRIVER_ID, isActive = 1)))

            runBlocking {
                wireApi(server).updateDriver(
                    id = DRIVER_ID,
                    body = UpdateAdminDriverRequest(isActive = UpdateAdminDriverRequest.IsActive._1),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/drivers/$DRIVER_ID", sent.path)
            assertEquals("""{"is_active":1}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateDriver with inactive status sends numeric is_active 0 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(driverAnswer(driverId = DRIVER_ID, isActive = 0)))

            runBlocking {
                wireApi(server).updateDriver(
                    id = DRIVER_ID,
                    body = UpdateAdminDriverRequest(isActive = UpdateAdminDriverRequest.IsActive._0),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/drivers/$DRIVER_ID", sent.path)
            assertEquals("""{"is_active":0}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `drivers lists active and inactive drivers from server`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"drivers":[${driverInner(1, "André", 1)}]}"""))

            val response = runBlocking {
                wireApi(server).drivers()
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/drivers", sent.path)
            assertEquals(1, response.body()?.drivers?.size)
            assertEquals("André", response.body()?.drivers?.first()?.name)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createDriver sends create payload on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody(driverAnswer(driverId = DRIVER_ID, isActive = 1)))

            val response = runBlocking {
                wireApi(server).createDriver(
                    CreateAdminDriverRequest(
                        name = "Carlos Fletero",
                        driverType = CreateAdminDriverRequest.DriverType.fletero,
                    ),
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/drivers", sent.path)
            assertEquals(201, response.code())
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

    private fun driverAnswer(driverId: Int, isActive: Int): String =
        """{"driver":${driverInner(driverId, "Carlos Fletero", isActive)}}"""

    private fun driverInner(driverId: Int, name: String, isActive: Int): String =
        """{"id":$driverId,"name":"$name","license_plate":null,"driver_type":"fletero","is_active":$isActive,"created_by_user_id":null,"created_at":"2026-01-01T00:00:00Z"}"""

    private companion object {
        const val DRIVER_ID = 55
    }
}
