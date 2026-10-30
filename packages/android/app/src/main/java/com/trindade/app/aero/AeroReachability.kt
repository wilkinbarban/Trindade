package com.trindade.app.aero

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether this phone can actually reach the plant, asked before the operator is told the ERP failed.
 *
 * The failure this exists for is the ordinary one: an operator on mobile data, or on the plant's guest
 * Wi-Fi, taps "enviar ao ERP" and gets an error from a request that never had a chance. The ERP is on the
 * factory LAN and reachable only from inside it, so the useful answer is not the exception the socket threw
 * but the sentence "sem conexão com a rede da fábrica".
 *
 * Two questions, cheapest first:
 *
 * 1. Is there a Wi-Fi network at all? This is `ACCESS_NETWORK_STATE` and nothing else -- the SSID is not
 *    read, because since API 27 reading it requires the location permission, and asking a factory
 *    operator for their location to test a socket is a worse trade than the precision it would add.
 * 2. Does the plant host accept a TCP connection, within the short budget S3 asks for? A connect that
 *    completes is the only evidence that matters: it says the address is right, the route exists and
 *    something is listening.
 *
 * This never replaces the request's own failure handling. It runs before the request to produce a sentence,
 * and a probe that says "reachable" does not promise the ERP will accept the payload.
 */
@Singleton
class AeroReachability @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The two answers, as data, so the screen owns the words. */
    data class Diagnosis(
        val wifiConnected: Boolean,
        /** Null when the address is unusable, which is a configuration problem and not a network one. */
        val hostReachable: Boolean?,
    ) {
        /** Reachable only when both halves hold; an unusable address is never reachable. */
        val plantReachable: Boolean get() = wifiConnected && hostReachable == true
    }

    fun wifiConnected(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * A TCP connect to the plant host with a short budget.
     *
     * `isReachable` is not used: it needs the ICMP privilege on some platforms, and the ERP is a TCP
     * service, so a connection to its port is the honest test.
     */
    suspend fun hostReachable(wsUrl: String?, timeoutMillis: Int = DEFAULT_TIMEOUT_MS): Boolean? {
        val hostPort = aeroHostPort(wsUrl) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(hostPort.host, hostPort.port), timeoutMillis)
                    true
                }
            }.getOrDefault(false)
        }
    }

    suspend fun diagnose(wsUrl: String?, timeoutMillis: Int = DEFAULT_TIMEOUT_MS): Diagnosis {
        val wifi = wifiConnected()
        if (!wifi) return Diagnosis(wifiConnected = false, hostReachable = null)
        return Diagnosis(wifiConnected = true, hostReachable = hostReachable(wsUrl, timeoutMillis))
    }

    companion object {
        /**
         * 2.5 seconds. Long enough for a healthy LAN handshake and short enough that an operator standing
         * at the machine has not yet decided the app is broken.
         */
        const val DEFAULT_TIMEOUT_MS = 2500
    }
}
