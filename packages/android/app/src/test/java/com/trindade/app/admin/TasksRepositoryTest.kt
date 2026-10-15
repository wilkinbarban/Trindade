package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.UpdateAdminTaskRequest
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TasksRepositoryTest {

    @Test
    fun `categories returns list on success and null on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = TasksRepository(api)

        val categories = repo.categories()
        assertEquals(2, categories?.size)
        assertEquals("Higienização", categories?.first()?.namePt)

        val failingRepo = TasksRepository(FakeAdminApi(categoriesToReturn = null))
        assertNull(failingRepo.categories())
    }

    @Test
    fun `tasks returns list on success and null on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = TasksRepository(api)

        val tasks = repo.tasks()
        assertEquals(2, tasks?.size)
        assertEquals(10, tasks?.first()?.id)

        val failingRepo = TasksRepository(FakeAdminApi(tasksToReturn = null))
        assertNull(failingRepo.tasks())
    }

    @Test
    fun `createTask returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = TasksRepository(api)

        val request = CreateAdminTaskRequest(
            categoryId = 1,
            namePt = "Nova Tarefa",
            nameEs = "Nueva Tarea",
            temperatureReadings = 1,
        )
        val result = repo.createTask(request)
        assertTrue(result is TaskWriteResult.Saved)
        assertEquals("Nova Tarefa", (result as TaskWriteResult.Saved).task.namePt)
        assertEquals(listOf(request), api.createdRequests)

        val failingApi = FakeAdminApi(
            createResponse = Response.error(400, "{\"error\":\"Invalid input\"}".toResponseBody(null)),
        )
        val failingResult = TasksRepository(failingApi).createTask(request)
        assertTrue(failingResult is TaskWriteResult.Refused)
        assertEquals(400, (failingResult as TaskWriteResult.Refused).statusCode)
    }

    @Test
    fun `updateTask returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = TasksRepository(api)

        val request = UpdateAdminTaskRequest(
            namePt = "Nome Atualizado",
            isActive = UpdateAdminTaskRequest.IsActive._0,
        )
        val result = repo.updateTask(10, request)
        assertTrue(result is TaskWriteResult.Saved)
        assertEquals("Nome Atualizado", (result as TaskWriteResult.Saved).task.namePt)
        assertEquals(1, api.updatedRequests.size)
        assertEquals(10 to request, api.updatedRequests.first())

        val failingApi = FakeAdminApi(
            updateResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null)),
        )
        val failingResult = TasksRepository(failingApi).updateTask(10, request)
        assertTrue(failingResult is TaskWriteResult.Refused)
        assertEquals(403, (failingResult as TaskWriteResult.Refused).statusCode)
    }

    @Test
    fun `deleteTask returns Changed on success and Refused on constraint error`() = runTest {
        val api = FakeAdminApi()
        val repo = TasksRepository(api)

        val result = repo.deleteTask(10)
        assertTrue(result is TaskLifecycleResult.Changed)
        assertEquals(listOf(10), api.deletedIds)

        val failingApi = FakeAdminApi(
            deleteResponse = Response.error(400, "{\"error\":\"FK constraint\"}".toResponseBody(null)),
        )
        val failingResult = TasksRepository(failingApi).deleteTask(10)
        assertTrue(failingResult is TaskLifecycleResult.Refused)
        assertEquals(400, (failingResult as TaskLifecycleResult.Refused).statusCode)
    }
}
