package com.trindade.app.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Binds the client's password rule to the published contract and states the rule once in behaviour.
 *
 * The contract is read from the test classpath rather than copied here, because a copy would be a
 * second statement of the rule that can drift exactly the way the client drifted from the server:
 * the screens enabled an eight-character password for as long as the API refused anything under
 * fifteen, and the cost of that drift was an operator typing a password twice into a form that told
 * them it was fine.
 */
class PasswordPolicyContractTest {

    private val schemas by lazy {
        Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader.getResource("openapi.json")) {
                "openapi.json is not on the test classpath; the test source set should include it"
            }.readText(),
        ).jsonObject.getValue("components").jsonObject.getValue("schemas").jsonObject
    }

    /** The documented length bounds of one password property, as the artifact states them. */
    private fun documentedBounds(schema: String, property: String): Pair<Int, Int> {
        val field = schemas.getValue(schema).jsonObject
            .getValue("properties").jsonObject
            .getValue(property).jsonObject
        return field.getValue("minLength").jsonPrimitive.int to field.getValue("maxLength").jsonPrimitive.int
    }

    @Test
    fun `every password the contract documents carries the bounds the client applies`() {
        val documented = listOf(
            "RegisterRequest" to "password",
            "ChangePasswordRequest" to "newPassword",
            "CreateAdminUserRequest" to "password",
            "UpdateAdminUserRequest" to "password",
        )
        for ((schema, property) in documented) {
            val (minimum, maximum) = documentedBounds(schema, property)
            assertEquals("$schema.$property minimum", PasswordPolicy.MIN_GRAPHEMES, minimum)
            assertEquals("$schema.$property maximum", PasswordPolicy.MAX_BYTES, maximum)
        }
    }

    @Test
    fun `the rule counts visible characters, not code points`() {
        val decomposedFifteen = "e\u0301".repeat(PasswordPolicy.MIN_GRAPHEMES)

        assertEquals(PasswordPolicy.MIN_GRAPHEMES, PasswordPolicy.graphemes(decomposedFifteen))
        assertEquals(2 * PasswordPolicy.MIN_GRAPHEMES, decomposedFifteen.length)
        assertNull(PasswordPolicy.violationOf(decomposedFifteen))

        assertNull(PasswordPolicy.violationOf("p".repeat(PasswordPolicy.MIN_GRAPHEMES)))
        assertEquals(
            PasswordPolicy.Violation.TOO_FEW_GRAPHEMES,
            PasswordPolicy.violationOf("p".repeat(PasswordPolicy.MIN_GRAPHEMES - 1)),
        )
    }

    @Test
    fun `the rule measures the original bytes, not the characters`() {
        val atCeiling = "€".repeat(PasswordPolicy.MAX_BYTES / 3)

        assertEquals(PasswordPolicy.MAX_BYTES, PasswordPolicy.utf8Bytes(atCeiling))
        assertNull(PasswordPolicy.violationOf(atCeiling))
        assertEquals(
            PasswordPolicy.Violation.TOO_MANY_BYTES,
            PasswordPolicy.violationOf("€".repeat(PasswordPolicy.MAX_BYTES / 3 + 1)),
        )
    }

    @Test
    fun `the shorter rule is reported first when a password breaks both`() {
        // Fourteen visible characters of seven bytes each: short of the minimum and past the byte ceiling
        // at once. The operator is told to lengthen, and not to shorten a password they were just told to
        // lengthen.
        val both = "a\u0301\u0301\u0301".repeat(PasswordPolicy.MIN_GRAPHEMES - 1)

        assertEquals(PasswordPolicy.MIN_GRAPHEMES - 1, PasswordPolicy.graphemes(both))
        assertTrue(PasswordPolicy.utf8Bytes(both) > PasswordPolicy.MAX_BYTES)
        assertEquals(PasswordPolicy.Violation.TOO_FEW_GRAPHEMES, PasswordPolicy.violationOf(both))
    }
}
