package com.trindade.app.network

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * El invitado es la cuenta de solo lectura: mira todo y no toca nada.
 *
 * El backend rechaza cualquiera de sus mutaciones con 403 y un mensaje fijo, y la app tiene que
 * reconocer ESE rechazo -- y solo ese -- para mostrar el aviso en vez del error genérico de siempre.
 * La lógica vive acá, en funciones puras, para poder probarla sin levantar un servidor ni un emulador.
 *
 * El mensaje es el de la directiva y su única fuente de verdad es el backend; acá vive una copia
 * porque la app no puede importar del servidor. Que las dos no se separen lo cuida GuestReadonlyTest,
 * que lee la constante del backend y la compara con esta: dos copias de una cadena que tienen que
 * coincidir es justo lo que driftea en silencio, y el día que se separen el aviso deja de aparecer.
 */
const val GUEST_READONLY_MESSAGE =
    "\u00c9 necess\u00e1rio cadastrar um usu\u00e1rio na p\u00e1gina de login para realizar a\u00e7\u00f5es"

/**
 * Reconoce el rechazo del invitado.
 *
 * Pide las dos cosas a la vez a propósito: el 403 solo no alcanza -- un permiso denegado cualquiera
 * también lo es, y tratarlo como "invitado" le mentiría al operador -- y el mensaje solo tampoco,
 * porque un error de otra capa podría repetir el texto.
 */
fun isGuestMutationRefusal(status: Int, message: String?): Boolean =
    status == HTTP_FORBIDDEN && message == GUEST_READONLY_MESSAGE

const val HTTP_FORBIDDEN = 403

/**
 * El mensaje del cuerpo de un rechazo, o null si el cuerpo no lo trae.
 *
 * Se aceptan las dos claves porque la guarda del invitado responde {message} y el resto de la API
 * responde {error}: un aviso que nunca dispara es peor que uno que dispara tarde.
 *
 * No se usa el serializador de la app a propósito: este cuerpo lo escribe un rechazo y puede llegar
 * vacío, cortado o directamente no ser JSON -- un 403 de un proxy, por ejemplo -- y una excepción acá
 * convertiría un error HTTP en una caída del cliente. La extracción es deliberadamente tolerante.
 */
fun guestRefusalMessageFrom(body: String?): String? {
    if (body.isNullOrBlank()) return null
    for (key in listOf("message", "error")) {
        val match = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body) ?: continue
        val raw = match.groupValues[1]
        // Los escapes que puede traer la cadena: el texto de la directiva lleva acentos.
        return raw.replace(Regex("""\\u([0-9a-fA-F]{4})""")) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\\"", "\"").replace("\\\\", "\\")
    }
    return null
}

/**
 * El aviso que espera a que alguien lo muestre.
 *
 * Se atienden los dos momentos por la misma razón que la web usa sessionStorage: [messages] cubre el
 * caso normal -- el rechazo llega con el armazón ya montado -- y [takePending] cubre el otro, cuando el
 * rechazo ocurrió antes de que el host existiera. Sin ese segundo camino, una mutación fallida durante
 * el arranque se perdería en silencio.
 */
object GuestReadonlyNotice {
    private val pending = AtomicReference<String?>(null)

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun mark(message: String) {
        pending.set(message)
        _messages.tryEmit(message)
    }

    fun takePending(): String? = pending.getAndSet(null)

    /** Sólo para las pruebas: deja el aviso como recién creado. */
    internal fun reset() {
        pending.set(null)
    }
}
