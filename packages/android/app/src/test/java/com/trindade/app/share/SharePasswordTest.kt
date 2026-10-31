package com.trindade.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The temporary password a new worker gets, and the login it is built from.
 *
 * The web screen derives both the same way; this file pins the same examples so the two clients cannot answer
 * with different credentials for the same name.
 */
class SharePasswordTest {

    @Test
    fun `pads the username with the running digit sequence up to fifteen characters`() {
        assertEquals("pedro1234567890", quickWorkerPassword("pedro"))
        assertEquals("ana123456789012", quickWorkerPassword("ana"))
        assertEquals("maria.silva1234", quickWorkerPassword("maria.silva"))
    }

    @Test
    fun `keeps a username that already reaches the floor, cut to it`() {
        assertEquals("usuarioquincecd", quickWorkerPassword("usuarioquincecd"))
        assertEquals("usuarioquincecd", quickWorkerPassword("usuarioquincecdx"))
    }

    @Test
    fun `never answers with fewer than fifteen characters`() {
        for (username in listOf("a", "ab", "joao.da.silva", "administrador")) {
            assertTrue(username, quickWorkerPassword(username).length >= 15)
        }
    }

    @Test
    fun `derives the login from the name, without accents, capitals or dots`() {
        assertEquals("joaodasilva", deriveUsername("João da Silva"))
        assertEquals("mariajose", deriveUsername("María José"))
        assertEquals("pedro", deriveUsername("  Pedro  "))
        assertEquals("anapaula", deriveUsername("Ana Paula!"))
        assertEquals("joaosilva2", deriveUsername("joao.silva_2"))
        // The ERP writes the operator on the seat, and that name is how the account gets created.
        assertEquals("joaoh", deriveUsername("JOAO H"))
        assertEquals("jefferson", deriveUsername("JEFFERSON"))
    }

    @Test
    fun `builds the password from the derived login`() {
        assertEquals("pedro1234567890", quickWorkerPassword(deriveUsername("Pedro")))
        assertEquals("joaoh1234567890", quickWorkerPassword(deriveUsername("JOAO H")))
    }
}
