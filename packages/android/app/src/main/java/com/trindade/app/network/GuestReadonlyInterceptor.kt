package com.trindade.app.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Convierte el rechazo del invitado en un aviso que el operador ve.
 *
 * Antes de esto, un intento de mutación del invitado producía el error genérico de la pantalla donde
 * estuviera, y no había forma de enterarse de que la sesión es de solo lectura por diseño.
 *
 * Se registra después de [com.trindade.app.auth.AuthInterceptor] a propósito: aquel puede renovar el
 * token y reintentar una vez, y este tiene que mirar la respuesta FINAL -- la del reintento incluido --
 * y no la primera, o un rechazo que llegara en el segundo intento pasaría inadvertido.
 */
object GuestReadonlyInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code != HTTP_FORBIDDEN) return response

        // peekBody y no body: leer el cuerpo en un interceptor lo CONSUME, y el que viene aguas abajo
        // -- Retrofit, o el manejo de errores de la pantalla -- se quedaría sin él y reportaría el
        // rechazo como un cuerpo vacío. Se mira sin gastarlo.
        val message = guestRefusalMessageFrom(response.peekBody(MAX_PEEK_BYTES).string())
        if (isGuestMutationRefusal(response.code, message)) {
            message?.let(GuestReadonlyNotice::mark)
        }
        return response
    }

    /** Suficiente para el cuerpo de un rechazo, y acotado para no leer una respuesta enorme. */
    private const val MAX_PEEK_BYTES = 64L * 1024
}
