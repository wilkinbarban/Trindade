package com.trindade.app.aero

import com.trindade.app.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The plant's WebService, called from this device.
 *
 * This is what replaces the iOS shortcut on Android. The web browser cannot reach a plain-HTTP host from a
 * secure page, so it hands the envelope to a Shortcut; a native app has no such restriction, and the only
 * thing it needs from the platform is the cleartext exemption in `network_security_config.xml`.
 *
 * Three properties of this client are deliberate and worth naming:
 *
 * - **Its own OkHttp, with no [com.trindade.app.auth.AuthInterceptor].** The credentials here are the
 *   factory's and they travel as a per-call header. Sharing the backend client would give this object the
 *   ability to attach a Trindade token to a plant request, and the separation is cheaper than trusting an
 *   exemption list.
 * - **Short timeouts.** The operator is standing next to a machine: a launch that waits thirty seconds and
 *   then fails is worse than one that fails in three and lands in the pending column, where it can be
 *   resent. The read budget is longer than the connect one because the ERP itself can be slow when the
 *   Firebird server is busy, and the whole-call bound keeps a trickle from holding the screen forever.
 * - **A `User-Agent` that names this app and its version.** The plant's log is read over SSH by a person
 *   looking at several clients at once; the header is how a request is attributed to the APK that sent it.
 */
@Singleton
class AeroErpClient @Inject constructor(
    private val json: Json,
) {

    private val api: AeroErpApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request: Request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()

        Retrofit.Builder()
            // A placeholder base: every call passes an absolute @Url, and Retrofit still requires a base
            // to be set. It is never resolved, and it names the plant so a log line does not read as if
            // this client had a third destination.
            .baseUrl("$AERO_DEFAULT_WS_URL/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AeroErpApi::class.java)
    }

    /** What a finished attempt at the plant looks like, once the transport is out of the way. */
    sealed interface Probe {
        data class Answered(val test: AeroTestResult) : Probe

        /** Nothing answered: no network, a wrong address, or a host that refuses connections. */
        data class Unreachable(val reason: ProbeFailure) : Probe
    }

    /**
     * Why no answer arrived. The screen turns each into one sentence in the operator's language; this file
     * deliberately carries no copy.
     */
    enum class ProbeFailure { TIMEOUT, NO_ROUTE, REFUSED, UNKNOWN }

    /**
     * `POST /usuarios` with the operator's credentials.
     *
     * The probe deliberately does not throw: every outcome is an answer the screen shows, because the
     * operator's next move depends on which one it is.
     */
    suspend fun testConnection(
        wsUrl: String,
        username: String,
        password: String,
    ): Probe {
        // No trailing slash, and this is load-bearing rather than cosmetic: Laravel answers `/usuarios/`
        // with a 301 to `/usuarios`, OkHttp follows that redirect, and a POST that arrives after it arrives
        // as a GET -- which the route refuses with 405. The web has always called it without the slash, so
        // the two clients were asking two different URLs and only one of them worked.
        val url = aeroEndpoint(wsUrl, "usuarios")
        return runCatching {
            api.postLogin(url, basicAuthHeader(username, password))
        }.fold(
            onSuccess = { response ->
                val body = response.body()
                if (body == null) {
                    Probe.Answered(
                        AeroTestResult.Failed(
                            if (response.code() == 401) null else "HTTP ${response.code()}",
                        ),
                    )
                } else {
                    Probe.Answered(readAeroTestResult(body))
                }
            },
            onFailure = { error -> Probe.Unreachable(error.toProbeFailure()) },
        )
    }

    /**
     * `POST /entradasitem` with one entry.
     *
     * A transport failure and a refusal are different answers: the first leaves the launch pending and
     * resendable, the second is the ERP saying no to this payload. Both come back as a value, never as a
     * throw, because the caller has a row to update either way.
     */
    suspend fun postEntry(
        wsUrl: String,
        username: String,
        password: String,
        payload: AeroEntryPayload,
    ): AeroWriteResult {
        val url = aeroEndpoint(wsUrl, "entradasitem")
        return runCatching {
            api.postEntry(url, basicAuthHeader(username, password), payload)
        }.fold(
            onSuccess = { response ->
                val body = response.body()
                if (body != null) {
                    readAeroWriteResult(body)
                } else if (response.isSuccessful) {
                    // A 2xx with no body is the ERP accepting the entry: Firebird answered with nothing to
                    // read, which is not the same as refusing it.
                    AeroWriteResult.Ok(null)
                } else {
                    AeroWriteResult.Failed("HTTP ${response.code()}")
                }
            },
            // No sentence: a transport failure is the same answer for every request, and the screen
            // already knows how to say "sem conexão com a rede da fábrica".
            onFailure = { AeroWriteResult.Failed(null) },
        )
    }

    /** `POST /movimentoitem?_method=PATCH`: the whole day, every operator. */
    suspend fun readMovements(
        wsUrl: String,
        username: String,
        password: String,
        date: String,
    ): AeroMovementsResult {
        val url = aeroMovementsUrl(wsUrl)
        return runCatching {
            api.postMovements(
                url = url,
                authorization = basicAuthHeader(username, password),
                body = buildMovimentoItemPayload(userName = null, startDate = date),
            )
        }.fold(
            onSuccess = { response ->
                val body: JsonElement? = response.body()
                if (body != null) {
                    readAeroMovements(body)
                } else {
                    AeroMovementsResult.Failed("HTTP ${response.code()}")
                }
            },
            onFailure = { AeroMovementsResult.Failed(null) },
        )
    }

    private fun Throwable.toProbeFailure(): ProbeFailure = when (this) {
        is java.net.SocketTimeoutException -> ProbeFailure.TIMEOUT
        is java.net.UnknownHostException -> ProbeFailure.NO_ROUTE
        is java.net.NoRouteToHostException -> ProbeFailure.NO_ROUTE
        is java.net.ConnectException -> ProbeFailure.REFUSED
        else -> ProbeFailure.UNKNOWN
    }

    private companion object {
        // Sized for a phone on the plant's Wi-Fi next to the Firebird server. The connect budget is the
        // one S3 asks for: a launch may not sit for longer than an operator will wait before deciding the
        // ERP is down.
        const val CONNECT_TIMEOUT_SECONDS = 3L
        const val READ_TIMEOUT_SECONDS = 10L

        // Above connect + read, so a server that keeps trickling bytes is still ended, and by the tighter,
        // more specific bound when it goes quiet.
        const val CALL_TIMEOUT_SECONDS = 15L

        /** Who this is, for the plant's log. The version comes from the build, never from a literal. */
        val USER_AGENT: String = "Trindade-Android/${BuildConfig.APP_VERSION_NAME}"
    }
}
