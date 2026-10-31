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
    fun `derives the login from the name, without accents or capitals`() {
        assertEquals("joao.da.silva", deriveUsername("João da Silva"))
        assertEquals("maria.jose", deriveUsername("María José"))
        assertEquals("pedro", deriveUsername("  Pedro  "))
        assertEquals("ana.paula", deriveUsername("Ana Paula!"))
        assertEquals("joao.silva_2", deriveUsername("joao.silva_2"))
    }

    @Test
    fun `builds the password from the derived login`() {
        assertEquals("pedro1234567890", quickWorkerPassword(deriveUsername("Pedro")))
    }
}
