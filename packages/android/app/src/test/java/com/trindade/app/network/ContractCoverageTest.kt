package com.trindade.app.network

import kotlinx.serialization.json.Json
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
    fun `every system interface method is documented in the contract`() {
        assertEveryMethodIsDocumented(SystemApi::class.java)
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
