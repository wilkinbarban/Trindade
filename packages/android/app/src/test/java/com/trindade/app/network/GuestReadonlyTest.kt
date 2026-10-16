package com.trindade.app.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test

/**
 * El invitado es la cuenta de solo lectura: mira todo y no toca nada. El backend rechaza cualquiera de
 * sus mutaciones con 403 y un mensaje fijo, y la app tiene que reconocer ESE rechazo -- y solo ese --
 * para mostrar el aviso en vez del error genérico de siempre.
 *
 * La última prueba lee la constante del backend y la compara con la de acá. Son dos copias de una
 * cadena que tienen que coincidir, y eso es justo lo que driftea en silencio: el día que se separen,
 * el aviso deja de aparecer y nada más lo delata.
 */
class GuestReadonlyTest {

    @Test
    fun `reconoce el rechazo del invitado y no lo confunde con otros errores`() {
        assertTrue(isGuestMutationRefusal(403, GUEST_READONLY_MESSAGE))
        assertFalse("un 401 es sesión, no invitado", isGuestMutationRefusal(401, GUEST_READONLY_MESSAGE))
        assertFalse("un 403 ajeno no es el del invitado", isGuestMutationRefusal(403, "Sem permissão"))
        assertFalse("un 200 no es un rechazo", isGuestMutationRefusal(200, GUEST_READONLY_MESSAGE))
        assertFalse("sin mensaje no hay nada que mostrar", isGuestMutationRefusal(403, null))
        assertFalse("un mensaje vacío tampoco", isGuestMutationRefusal(403, ""))
    }

    @Test
    fun `el aviso sobrevive a que todavia no haya nadie mirandolo`() {
        GuestReadonlyNotice.reset()
        GuestReadonlyNotice.mark("primeiro aviso")

        assertEquals(
            "un rechazo que llega antes de que el armazón se monte no puede perderse",
            "primeiro aviso",
            GuestReadonlyNotice.takePending(),
        )
        assertEquals("y una vez tomado no se repite", null, GuestReadonlyNotice.takePending())
    }

    @Test
    fun `la cadena de acá y la del backend son la misma`() {
        val candidates = listOf(
            "../../backend/src/guest-user.ts",
            "../../../backend/src/guest-user.ts",
            "../../../../packages/backend/src/guest-user.ts",
        )
        val source = candidates.map(::File).firstOrNull { it.isFile }?.readText()

        // Un host que sólo tiene el paquete android -- el de compilación, por ejemplo -- no trae el
        // archivo del backend, y ahí la comparación no se puede hacer. Eso se REPORTA como omitida en
        // vez de pasar en silencio: una protección que no corre no puede parecer una que sí corre. El
        // sync que copia backend/src/guest-user.ts a este árbol hace que la comparación sí se haga.
        if (source == null) {
            // No es un pase silencioso: sin el archivo del backend la comparación NO se hace, y eso
            // JUnit lo reporta como omitida. Una protección que no corre no puede parecer una que sí.
            throw AssumptionViolatedException(
                "el backend no está en este árbol (${File(".").absolutePath}): corré el sync que trae " +
                    "backend/src/guest-user.ts para que la comparación de las dos cadenas sí se haga",
            )
        }

        val match = Regex("""export const GUEST_MUTATION_MESSAGE\s*=\s*'([^']+)'""").find(source)
            ?: throw AssertionError("no encontré GUEST_MUTATION_MESSAGE en la fuente del backend")
        val backendMessage = match.groupValues[1].replace(Regex("""\\u([0-9a-fA-F]{4})""")) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }

        assertEquals(
            "el mensaje de la app y el del servidor tienen que ser la misma cadena",
            backendMessage,
            GUEST_READONLY_MESSAGE,
        )
    }

    @Test
    fun `saca el mensaje del cuerpo, con las dos claves y sin caerse con lo que no es JSON`() {
        assertEquals(
            GUEST_READONLY_MESSAGE,
            guestRefusalMessageFrom("""{"message":"É necessário cadastrar um usuário na página de login para realizar ações"}"""),
        )
        // La clave que usa el resto de la API.
        assertEquals("Sem permissão", guestRefusalMessageFrom("""{"error":"Sem permissão"}"""))
        // Lo que NO puede pasar: un cuerpo raro tiene que dar null, no una excepción.
        assertEquals(null, guestRefusalMessageFrom(null))
        assertEquals(null, guestRefusalMessageFrom(""))
        assertEquals(null, guestRefusalMessageFrom("<html>403 Forbidden</html>"))
        assertEquals(null, guestRefusalMessageFrom("{}"))
    }
}
