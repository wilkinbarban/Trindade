package com.trindade.app.admin

import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TimeSlotsAdminRepositoryTest {

    @Test
    fun `timeSlots returns list on success and null on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)

        val slots = repo.timeSlots()
        assertEquals(FakeAdminApi.defaultTimeSlots(), slots)

        val failingRepo = TimeSlotsAdminRepository(FakeAdminApi(timeSlotsToReturn = null))
        assertNull(failingRepo.timeSlots())
    }

    @Test
    fun `updateTimeSlots returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = TimeSlotsAdminRepository(api)

        val newSlots = listOf("06:00", "08:00", "10:00")
        val result = repo.updateTimeSlots(newSlots)
        assertTrue(result is TimeSlotsWriteResult.Saved)
        assertEquals(newSlots, (result as TimeSlotsWriteResult.Saved).timeSlots)
        assertEquals(listOf(UpdateTimeSlotsRequest(timeSlots = newSlots)), api.updatedTimeSlotsRequests)

        val failingApi = FakeAdminApi(
            updateTimeSlotsResponse = Response.error(400, "{\"error\":\"Invalid input\"}".toResponseBody(null)),
        )
        val failingResult = TimeSlotsAdminRepository(failingApi).updateTimeSlots(newSlots)
        assertTrue(failingResult is TimeSlotsWriteResult.Refused)
        assertEquals(400, (failingResult as TimeSlotsWriteResult.Refused).statusCode)
    }

    @Test
    fun `updateTimeSlots returns Refused on role guard refusal 403`() = runTest {
        val forbiddenApi = FakeAdminApi(
            updateTimeSlotsResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null)),
        )
        val repo = TimeSlotsAdminRepository(forbiddenApi)
        val result = repo.updateTimeSlots(listOf("08:00"))
        assertTrue(result is TimeSlotsWriteResult.Refused)
        assertEquals(403, (result as TimeSlotsWriteResult.Refused).statusCode)
    }

    @Test
    fun `updateTimeSlots returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse> {
                throw IOException("Network unreachable")
            }
        }
        val repo = TimeSlotsAdminRepository(throwingApi)
        val result = repo.updateTimeSlots(listOf("08:00"))
        assertTrue(result is TimeSlotsWriteResult.Unreachable)
    }

    @Test
    fun `timeSlots returns null on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun timeSlots(): Response<TimeSlotsResponse> {
                throw IOException("Network unreachable")
            }
        }
        val repo = TimeSlotsAdminRepository(throwingApi)
        assertNull(repo.timeSlots())
    }
}
