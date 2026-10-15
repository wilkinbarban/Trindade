package com.trindade.app.admin

import com.trindade.app.contract.models.AdminVehicleResponse
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class VehiclesRepositoryTest {

    @Test
    fun `vehicles returns list on success and null on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)

        val vehicles = repo.vehicles()
        assertEquals(3, vehicles?.size)
        assertEquals("Caminhão 01", vehicles?.first()?.description)

        val failingRepo = VehiclesRepository(FakeAdminApi(vehiclesToReturn = null))
        assertNull(failingRepo.vehicles())
    }

    @Test
    fun `createVehicle returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)

        val request = CreateAdminVehicleRequest(
            description = "Van Nova",
            licensePlate = "XYZ-1234",
        )
        val result = repo.createVehicle(request)
        assertTrue(result is VehicleWriteResult.Saved)
        assertEquals("Van Nova", (result as VehicleWriteResult.Saved).vehicle.description)
        assertEquals(listOf(request), api.createdVehicleRequests)

        val failingApi = FakeAdminApi(
            createVehicleResponse = Response.error(400, "{\"error\":\"Invalid input\"}".toResponseBody(null)),
        )
        val failingResult = VehiclesRepository(failingApi).createVehicle(request)
        assertTrue(failingResult is VehicleWriteResult.Refused)
        assertEquals(400, (failingResult as VehicleWriteResult.Refused).statusCode)
    }

    @Test
    fun `updateVehicle returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)

        val request = UpdateAdminVehicleRequest(
            description = "Caminhão Atualizado",
            licensePlate = "DEF-5678",
            isActive = UpdateAdminVehicleRequest.IsActive._0,
        )
        val result = repo.updateVehicle(1, request)
        assertTrue(result is VehicleWriteResult.Saved)
        assertEquals("Caminhão Atualizado", (result as VehicleWriteResult.Saved).vehicle.description)
        assertEquals(1, api.updatedVehicleRequests.size)
        assertEquals(1 to request, api.updatedVehicleRequests.first())

        val failingApi = FakeAdminApi(
            updateVehicleResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null)),
        )
        val failingResult = VehiclesRepository(failingApi).updateVehicle(1, request)
        assertTrue(failingResult is VehicleWriteResult.Refused)
        assertEquals(403, (failingResult as VehicleWriteResult.Refused).statusCode)
    }

    @Test
    fun `deleteVehicle returns Changed on success and Refused on constraint error`() = runTest {
        val api = FakeAdminApi()
        val repo = VehiclesRepository(api)

        val result = repo.deleteVehicle(1)
        assertTrue(result is VehicleLifecycleResult.Changed)
        assertEquals(listOf(1), api.deletedVehicleIds)

        val failingApi = FakeAdminApi(
            deleteVehicleResponse = Response.error(400, "{\"error\":\"FK constraint\"}".toResponseBody(null)),
        )
        val failingResult = VehiclesRepository(failingApi).deleteVehicle(1)
        assertTrue(failingResult is VehicleLifecycleResult.Refused)
        assertEquals(400, (failingResult as VehicleLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteVehicle returns Refused when response body success is false`() = runTest {
        val api = FakeAdminApi(
            deleteVehicleResponse = Response.success(JsonObject(mapOf("success" to JsonPrimitive(false)))),
        )
        val result = VehiclesRepository(api).deleteVehicle(1)
        assertTrue(result is VehicleLifecycleResult.Refused)
        assertEquals(200, (result as VehicleLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteVehicle returns Refused when response body success is string rather than boolean`() = runTest {
        val api = FakeAdminApi(
            deleteVehicleResponse = Response.success(JsonObject(mapOf("success" to JsonPrimitive("true")))),
        )
        val result = VehiclesRepository(api).deleteVehicle(1)
        assertTrue(result is VehicleLifecycleResult.Refused)
        assertEquals(200, (result as VehicleLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteVehicle returns Refused when response body success is missing`() = runTest {
        val api = FakeAdminApi(
            deleteVehicleResponse = Response.success(JsonObject(emptyMap())),
        )
        val result = VehiclesRepository(api).deleteVehicle(1)
        assertTrue(result is VehicleLifecycleResult.Refused)
        assertEquals(200, (result as VehicleLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `createVehicle returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> {
                throw IOException("Network unreachable")
            }
        }
        val repo = VehiclesRepository(throwingApi)
        val result = repo.createVehicle(CreateAdminVehicleRequest(description = "Van", licensePlate = "V-1"))
        assertTrue(result is VehicleWriteResult.Unreachable)
    }

    @Test
    fun `updateVehicle returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> {
                throw IOException("Network unreachable")
            }
        }
        val repo = VehiclesRepository(throwingApi)
        val result = repo.updateVehicle(1, UpdateAdminVehicleRequest(description = "Van"))
        assertTrue(result is VehicleWriteResult.Unreachable)
    }

    @Test
    fun `deleteVehicle returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun deleteVehicle(id: Int): Response<JsonObject> {
                throw IOException("Network unreachable")
            }
        }
        val repo = VehiclesRepository(throwingApi)
        val result = repo.deleteVehicle(1)
        assertTrue(result is VehicleLifecycleResult.Unreachable)
    }
}
