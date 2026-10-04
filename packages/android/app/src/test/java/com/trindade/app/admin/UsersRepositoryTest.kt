package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
import com.trindade.app.network.AdminApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class UsersRepositoryTest {
    @Test fun `reads users and sends typed create update and delete requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"users":[$user]}"""))
            server.enqueue(json("""{"users":[]}"""))
            server.enqueue(json("""{"user":$user}""", 201))
            server.enqueue(json("""{"user":$user}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(json("""{"success":true}"""))
            val repository = repository(server)

            assertEquals(1, repository.users()?.single()?.id)
            assertEquals(emptyList<Any>(), repository.users())
            assertEquals("operador", (repository.create(CreateAdminUserRequest(
                username = "operador",
                password = "password123",
                displayName = "Operador",
                roleId = CreateAdminUserRequest.RoleId._2,
            )) as UserWriteResult.Saved).user?.username)
            assertEquals("operador", (repository.update(1, UpdateAdminUserRequest(
                displayName = "Operador Editado",
                roleId = UpdateAdminUserRequest.RoleId._1,
                isActive = UpdateAdminUserRequest.IsActive._0,
            )) as UserWriteResult.Saved).user?.username)
            assertEquals(UserWriteResult.Saved(), repository.delete(1))
            assertEquals(UserWriteResult.Saved(), repository.delete(1))

            val read1 = server.takeRequest()
            assertEquals("GET", read1.method)
            assertEquals("/api/admin/users", read1.path)

            val read2 = server.takeRequest()
            assertEquals("GET", read2.method)
            assertEquals("/api/admin/users", read2.path)

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/admin/users", create.path)
            val createBody = Json.parseToJsonElement(create.body.readUtf8()).jsonObject
            assertEquals("operador", createBody["username"]?.jsonPrimitive?.content)
            assertEquals("password123", createBody["password"]?.jsonPrimitive?.content)
            assertEquals("Operador", createBody["display_name"]?.jsonPrimitive?.content)
            val createRoleId = createBody["role_id"] as JsonPrimitive
            assertFalse(createRoleId.isString)
            assertEquals("2", createRoleId.content)

            val update = server.takeRequest()
            assertEquals("PATCH", update.method)
            assertEquals("/api/admin/users/1", update.path)
            val updateBody = Json.parseToJsonElement(update.body.readUtf8()).jsonObject
            assertEquals("Operador Editado", updateBody["display_name"]?.jsonPrimitive?.content)
            val updateRoleId = updateBody["role_id"] as JsonPrimitive
            assertFalse(updateRoleId.isString)
            assertEquals("1", updateRoleId.content)
            val updateIsActive = updateBody["is_active"] as JsonPrimitive
            assertFalse(updateIsActive.isString)
            assertEquals("0", updateIsActive.content)
            assertFalse("Unchanged password must be omitted from PATCH body", updateBody.containsKey("password"))
            assertFalse("Untouched username must be omitted without explicit null", updateBody.containsKey("username"))

            val delete1 = server.takeRequest()
            assertEquals("DELETE", delete1.method)
            assertEquals("/api/admin/users/1", delete1.path)

            val delete2 = server.takeRequest()
            assertEquals("DELETE", delete2.method)
            assertEquals("/api/admin/users/1", delete2.path)
        }
    }

    @Test fun `update sends password when provided and omits when null or empty`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"user":$user}"""))
            server.enqueue(json("""{"user":$user}"""))
            val repository = repository(server)

            val withPassword = repository.update(1, UpdateAdminUserRequest(
                password = "newPassword456",
                displayName = "Nome",
            ))
            assertTrue(withPassword is UserWriteResult.Saved)

            val req1 = server.takeRequest()
            val body1 = Json.parseToJsonElement(req1.body.readUtf8()).jsonObject
            assertEquals("newPassword456", body1["password"]?.jsonPrimitive?.content)

            val withEmptyPassword = repository.update(1, UpdateAdminUserRequest(
                password = "",
                displayName = "Nome",
            ))
            assertTrue(withEmptyPassword is UserWriteResult.Saved)

            val req2 = server.takeRequest()
            val body2 = Json.parseToJsonElement(req2.body.readUtf8()).jsonObject
            assertFalse("Empty password must be omitted from PATCH body", body2.containsKey("password"))
        }
    }

    @Test fun `numeric enum serialization and explicit null avoidance for users`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"user":$user}""", 201))
            server.enqueue(json("""{"user":$user}"""))
            val repository = repository(server)

            repository.create(CreateAdminUserRequest(
                username = "admin2",
                password = "password123",
                displayName = "Admin Two",
                roleId = CreateAdminUserRequest.RoleId._1,
            ))
            val createReq = server.takeRequest()
            val createBody = Json.parseToJsonElement(createReq.body.readUtf8()).jsonObject
            val createRoleId = createBody["role_id"] as JsonPrimitive
            assertFalse("role_id in create must be a JSON number", createRoleId.isString)
            assertEquals("1", createRoleId.content)

            repository.update(2, UpdateAdminUserRequest(
                roleId = UpdateAdminUserRequest.RoleId._2,
                isActive = UpdateAdminUserRequest.IsActive._1,
            ))
            val updateReq = server.takeRequest()
            val updateBody = Json.parseToJsonElement(updateReq.body.readUtf8()).jsonObject
            val updateRoleId = updateBody["role_id"] as JsonPrimitive
            assertFalse("role_id in update must be a JSON number", updateRoleId.isString)
            assertEquals("2", updateRoleId.content)
            val updateIsActive = updateBody["is_active"] as JsonPrimitive
            assertFalse("is_active in update must be a JSON number", updateIsActive.isString)
            assertEquals("1", updateIsActive.content)
            assertFalse("Untouched username must be omitted without explicit null", updateBody.containsKey("username"))
            assertFalse("Untouched display_name must be omitted without explicit null", updateBody.containsKey("display_name"))
            assertFalse("Untouched password must be omitted without explicit null", updateBody.containsKey("password"))
        }
    }

    @Test fun `failed reads stay null and writes preserve refusal status`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(404))
            val repository = repository(server)

            assertNull(repository.users())
            assertNull(repository.users())
            assertEquals(UserWriteResult.Refused(400), repository.create(CreateAdminUserRequest(
                username = "u", password = "p", displayName = "d", roleId = CreateAdminUserRequest.RoleId._2,
            )))
            assertEquals(UserWriteResult.Refused(403), repository.update(1, UpdateAdminUserRequest(displayName = "d")))
            assertEquals(UserWriteResult.Refused(404), repository.update(999, UpdateAdminUserRequest(displayName = "d")))
            assertEquals(UserWriteResult.Refused(403), repository.delete(1))
            assertEquals(UserWriteResult.Refused(404), repository.delete(999))
        }
    }

    @Test fun `disconnected user writes and reads are handled`() = runBlocking {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
            val repository = repository(server)

            assertNull(repository.users())
            assertEquals(UserWriteResult.Unreachable, repository.create(CreateAdminUserRequest(
                username = "u", password = "p", displayName = "d", roleId = CreateAdminUserRequest.RoleId._2,
            )))
            assertEquals(UserWriteResult.Unreachable, repository.update(1, UpdateAdminUserRequest(displayName = "d")))
            assertEquals(UserWriteResult.Unreachable, repository.delete(1))
        }
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private fun repository(server: MockWebServer) = UsersRepository(
        Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(jsonFormat.asConverterFactory("application/json".toMediaType()))
            .build().create(AdminApi::class.java),
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    private companion object {
        const val user = """{"id":1,"username":"operador","display_name":"Operador","role_id":2,"role_name":"Trabalhador","is_active":1,"created_at":"2026-01-01"}"""
    }
}
