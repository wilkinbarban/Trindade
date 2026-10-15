package com.trindade.app.admin

import com.trindade.app.contract.models.AdminUserResponse
import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
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

class UsersRepositoryTest {

    @Test
    fun `users returns list on success and null on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)

        val users = repo.users()
        assertEquals(3, users?.size)
        assertEquals("admin", users?.first()?.username)
        assertEquals(1, users?.first()?.roleId)

        val failingRepo = UsersRepository(FakeAdminApi(usersToReturn = null))
        assertNull(failingRepo.users())
    }

    @Test
    fun `createUser returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)

        val request = CreateAdminUserRequest(
            username = "novo_usuario",
            displayName = "Novo Usuário",
            password = "testpassword",
            roleId = CreateAdminUserRequest.RoleId._2,
        )
        val result = repo.createUser(request)
        assertTrue(result is UserWriteResult.Saved)
        assertEquals("novo_usuario", (result as UserWriteResult.Saved).user.username)
        assertEquals(listOf(request), api.createdUserRequests)

        val failingApi = FakeAdminApi(
            createUserResponse = Response.error(400, "{\"error\":\"Username taken\"}".toResponseBody(null)),
        )
        val failingResult = UsersRepository(failingApi).createUser(request)
        assertTrue(failingResult is UserWriteResult.Refused)
        assertEquals(400, (failingResult as UserWriteResult.Refused).statusCode)
    }

    @Test
    fun `updateUser returns Saved on success and Refused on failure`() = runTest {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)

        val request = UpdateAdminUserRequest(
            displayName = "Nome Atualizado",
            roleId = UpdateAdminUserRequest.RoleId._1,
            isActive = UpdateAdminUserRequest.IsActive._0,
        )
        val result = repo.updateUser(42, request)
        assertTrue(result is UserWriteResult.Saved)
        assertEquals("Nome Atualizado", (result as UserWriteResult.Saved).user.displayName)
        assertEquals(1, api.updatedUserRequests.size)
        assertEquals(42 to request, api.updatedUserRequests.first())

        val failingApi = FakeAdminApi(
            updateUserResponse = Response.error(403, "{\"error\":\"Forbidden\"}".toResponseBody(null)),
        )
        val failingResult = UsersRepository(failingApi).updateUser(42, request)
        assertTrue(failingResult is UserWriteResult.Refused)
        assertEquals(403, (failingResult as UserWriteResult.Refused).statusCode)
    }

    @Test
    fun `deleteUser returns Changed on success and Refused on constraint error`() = runTest {
        val api = FakeAdminApi()
        val repo = UsersRepository(api)

        val result = repo.deleteUser(42)
        assertTrue(result is UserLifecycleResult.Changed)
        assertEquals(listOf(42), api.deletedUserIds)

        val failingApi = FakeAdminApi(
            deleteUserResponse = Response.error(400, "{\"error\":\"Has related records\"}".toResponseBody(null)),
        )
        val failingResult = UsersRepository(failingApi).deleteUser(42)
        assertTrue(failingResult is UserLifecycleResult.Refused)
        assertEquals(400, (failingResult as UserLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteUser returns Refused when response body success is false`() = runTest {
        val api = FakeAdminApi(
            deleteUserResponse = Response.success(JsonObject(mapOf("success" to JsonPrimitive(false)))),
        )
        val result = UsersRepository(api).deleteUser(42)
        assertTrue(result is UserLifecycleResult.Refused)
        assertEquals(200, (result as UserLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteUser returns Refused when response body success is string rather than boolean`() = runTest {
        val api = FakeAdminApi(
            deleteUserResponse = Response.success(JsonObject(mapOf("success" to JsonPrimitive("true")))),
        )
        val result = UsersRepository(api).deleteUser(42)
        assertTrue(result is UserLifecycleResult.Refused)
        assertEquals(200, (result as UserLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `deleteUser returns Refused when response body success is missing`() = runTest {
        val api = FakeAdminApi(
            deleteUserResponse = Response.success(JsonObject(emptyMap())),
        )
        val result = UsersRepository(api).deleteUser(42)
        assertTrue(result is UserLifecycleResult.Refused)
        assertEquals(200, (result as UserLifecycleResult.Refused).statusCode)
    }

    @Test
    fun `createUser returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> {
                throw IOException("Network unreachable")
            }
        }
        val result = UsersRepository(throwingApi).createUser(
            CreateAdminUserRequest(
                username = "u",
                displayName = "d",
                password = "p",
                roleId = CreateAdminUserRequest.RoleId._2,
            ),
        )
        assertTrue(result is UserWriteResult.Unreachable)
    }

    @Test
    fun `updateUser returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> {
                throw IOException("Network unreachable")
            }
        }
        val result = UsersRepository(throwingApi).updateUser(1, UpdateAdminUserRequest(displayName = "d"))
        assertTrue(result is UserWriteResult.Unreachable)
    }

    @Test
    fun `deleteUser returns Unreachable on network failure`() = runTest {
        val throwingApi = object : com.trindade.app.network.AdminApi by FakeAdminApi() {
            override suspend fun deleteUser(id: Int): Response<JsonObject> {
                throw IOException("Network unreachable")
            }
        }
        val result = UsersRepository(throwingApi).deleteUser(1)
        assertTrue(result is UserLifecycleResult.Unreachable)
    }
}
