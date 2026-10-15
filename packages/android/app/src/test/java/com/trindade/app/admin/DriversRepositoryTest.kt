package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DriversRepositoryTest {

    @Test
    fun `drivers returns list on success and null on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = DriversRepository(api)

        val drivers = repo.drivers()
        assertEquals(3, drivers?.size)
        assertEquals("Carlos Fletero", drivers?.first()?.name)

        val failingRepo = DriversRepository(FakeAdminApi(driversToReturn = null))
        assertNull(failingRepo.drivers())
    }

    @Test
    fun `createDriver returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = DriversRepository(api)

        val request = CreateAdminDriverRequest(
            name = "Novo Fletero",
            driverType = CreateAdminDriverRequest.DriverType.fletero,
        )
        val result = repo.createDriver(request)
        assertTrue(result is DriverWriteResult.Saved)
        assertEquals("Novo Fletero", (result as DriverWriteResult.Saved).driver.name)
        assertEquals(listOf(request), api.createdDriverRequests)

        val failingApi = FakeAdminApi(
            createDriverResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null)),
        )
        val failingResult = DriversRepository(failingApi).createDriver(request)
        assertTrue(failingResult is DriverWriteResult.Refused)
        assertEquals(403, (failingResult as DriverWriteResult.Refused).statusCode)
    }

    @Test
    fun `updateDriver returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = DriversRepository(api)

        val request = UpdateAdminDriverRequest(
            name = "Nome Atualizado",
            isActive = UpdateAdminDriverRequest.IsActive._0,
        )
        val result = repo.updateDriver(1, request)
        assertTrue(result is DriverWriteResult.Saved)
        assertEquals("Nome Atualizado", (result as DriverWriteResult.Saved).driver.name)
        assertEquals(1, api.updatedDriverRequests.size)
        assertEquals(1 to request, api.updatedDriverRequests.first())

        val failingApi = FakeAdminApi(
            updateDriverResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null)),
        )
        val failingResult = DriversRepository(failingApi).updateDriver(1, request)
        assertTrue(failingResult is DriverWriteResult.Refused)
        assertEquals(403, (failingResult as DriverWriteResult.Refused).statusCode)
    }

    @Test
    fun `createDriver returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun createDriver(body: CreateAdminDriverRequest): Response<com.trindade.app.contract.models.AdminDriverResponse> {
                throw java.io.IOException("Network unreachable")
            }
        }
        val repo = DriversRepository(throwingApi)
        val result = repo.createDriver(CreateAdminDriverRequest(name = "Motorista"))
        assertTrue(result is DriverWriteResult.Unreachable)
    }

    @Test
    fun `updateDriver returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun updateDriver(
                id: Int,
                body: kotlinx.serialization.json.JsonObject,
            ): Response<com.trindade.app.contract.models.AdminDriverResponse> {
                throw java.io.IOException("Network unreachable")
            }
        }
        val repo = DriversRepository(throwingApi)
        val result = repo.updateDriver(1, UpdateAdminDriverRequest(name = "Motorista"))
        assertTrue(result is DriverWriteResult.Unreachable)
    }
}
