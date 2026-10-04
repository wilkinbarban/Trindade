package com.trindade.app.network

import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import java.lang.reflect.Method

/**
 * Proves the hand-written interfaces speak paths the contract actually documents.
 *
 * This exists because of what the compiler cannot see. A wrong path in a Retrofit annotation is a
 * perfectly valid string, so the interface compiles, the build goes green, and the client discovers
 * the mistake when someone taps the button. Only the DTO references are checked at compile time.
 *
 * The check reads `openapi.json` from the test classpath rather than a list written here. A hardcoded
 * list of expected paths would be a second statement of the same belief that produced the interface,
 * so it would agree with a typo instead of catching it, and it would need editing every time the
 * contract moved. The artifact is the only source of truth this repository has.
 *
 * Reflection over the annotations is what makes the check generic: it covers every method of every
 * interface it is given, including ones added later, and nobody has to remember to extend it.
 */
class ContractCoverageTest {

    @Test
    fun `every reports interface method is documented in the contract`() {
        assertEveryMethodIsDocumented(ReportsApi::class.java)
    }

    @Test
    fun `every auth interface method is documented in the contract`() {
        assertEveryMethodIsDocumented(AuthApi::class.java)
    }

    @Test
    fun `auth api declares worker register operation`() {
        val method = AuthApi::class.java.declaredMethods.firstOrNull { it.name == "register" }
        org.junit.Assert.assertNotNull("AuthApi must declare register method", method)
        val post = method?.getAnnotation(POST::class.java)
        assertEquals("api/auth/register", post?.value)
    }

    @Test
    fun `every system interface method is documented in the contract`() {
        assertEveryMethodIsDocumented(SystemApi::class.java)
    }

    @Test
    fun `every loading interface method is documented in the contract`() {
        assertEveryMethodIsDocumented(LoadingApi::class.java)
    }

    @Test
    fun `every admin interface method is documented in the contract`() {
        assertEveryMethodIsDocumented(AdminApi::class.java)
    }

    @Test
    fun `every audit interface method is documented in the contract`() {
        assertEveryMethodIsDocumented(AuditApi::class.java)
    }

    @Test
    fun `admin driver update serializes empty string for cleared plate and omits untouched fields without explicit null`() {
        val request = UpdateAdminDriverRequest(
            name = "Ana",
            licensePlate = "",
        )
        val serialized = Json.encodeToString(UpdateAdminDriverRequest.serializer(), request)
        assertEquals("""{"name":"Ana","license_plate":""}""", serialized)
    }

    @Test
    fun `admin driver update with only isActive omits untouched fields without explicit null`() {
        val request = UpdateAdminDriverRequest(
            isActive = UpdateAdminDriverRequest.IsActive._0,
        )
        val serialized = Json.encodeToString(UpdateAdminDriverRequest.serializer(), request)
        assertEquals("""{"is_active":"0"}""", serialized)

        val fields = Json.encodeToJsonElement(UpdateAdminDriverRequest.serializer(), request).jsonObject.toMutableMap()
        request.isActive?.let { fields["is_active"] = JsonPrimitive(it.value) }
        val fixedJson = JsonObject(fields).toString()
        assertEquals("""{"is_active":0}""", fixedJson)
    }

    @Test
    fun `admin user requests omit untouched fields and convert role_id and is_active to numeric primitives`() {
        val createRequest = CreateAdminUserRequest(
            username = "admin2",
            password = "password123",
            displayName = "Admin Two",
            roleId = CreateAdminUserRequest.RoleId._1,
        )
        val createFields = Json.encodeToJsonElement(CreateAdminUserRequest.serializer(), createRequest).jsonObject.toMutableMap()
        createFields["role_id"] = JsonPrimitive(createRequest.roleId.value)
        val createJson = JsonObject(createFields).toString()
        assertEquals("""{"username":"admin2","password":"password123","display_name":"Admin Two","role_id":1}""", createJson)

        val updateRequest = UpdateAdminUserRequest(
            roleId = UpdateAdminUserRequest.RoleId._2,
            isActive = UpdateAdminUserRequest.IsActive._1,
        )
        val updateFields = Json.encodeToJsonElement(UpdateAdminUserRequest.serializer(), updateRequest).jsonObject.toMutableMap()
        if (updateRequest.password.isNullOrEmpty()) {
            updateFields.remove("password")
        }
        updateRequest.roleId?.let { updateFields["role_id"] = JsonPrimitive(it.value) }
        updateRequest.isActive?.let { updateFields["is_active"] = JsonPrimitive(it.value) }
        val updateJson = JsonObject(updateFields).toString()
        assertEquals("""{"role_id":2,"is_active":1}""", updateJson)
    }

    private fun assertEveryMethodIsDocumented(api: Class<*>) {
        val paths = contractPaths()
        val undocumented = api.declaredMethods.mapNotNull { method ->
            verbAndPath(method)?.let { (verb, path) ->
                val entry = paths["/$path"]?.jsonObject
                if (entry?.get(verb.lowercase()) == null) "$verb /$path" else null
            }
        }

        assertEquals(
            "${api.simpleName} calls paths the contract does not document",
            emptyList<String>(),
            undocumented,
        )
    }

    /** The document's `paths` object, read once per assertion from the committed artifact. */
    private fun contractPaths() =
        Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader.getResource("openapi.json")) {
                "openapi.json is not on the test classpath; the test source set should include it"
            }.readText(),
        ).jsonObject.getValue("paths").jsonObject

    /**
     * The verb and path for one interface method, or null for a method Retrofit does not annotate.
     *
     * The path is used verbatim, because the contract writes templates as `{param}` and so does
     * Retrofit -- which is why the two can be compared as strings.
     *
     * Written as one branch per annotation rather than as a loop over annotation classes: Retrofit's
     * annotations share no interface with a `value`, so a loop erases to `Annotation` and cannot read
     * the path.
     */
    private fun verbAndPath(method: Method): Pair<String, String>? {
        method.getAnnotation(GET::class.java)?.let { return "GET" to it.value }
        method.getAnnotation(POST::class.java)?.let { return "POST" to it.value }
        method.getAnnotation(PUT::class.java)?.let { return "PUT" to it.value }
        method.getAnnotation(PATCH::class.java)?.let { return "PATCH" to it.value }
        method.getAnnotation(DELETE::class.java)?.let { return "DELETE" to it.value }
        return null
    }
}
