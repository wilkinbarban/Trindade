package com.trindade.app.auth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The interceptor's policy, exercised against a real OkHttp client and a real set of sockets.
 *
 * A hand-rolled fake `Interceptor.Chain` would test the logic against my own idea of what OkHttp
 * does. MockWebServer answers on a socket, so a retry here is a retry in the sense the app will
 * perform one, and the single-flight test can genuinely have two calls in flight at once.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private val refreshes = AtomicInteger()
    private val store = FakeTokenStore(access = EXPIRED, refresh = "refresh-token")

    private class FakeTokenStore(var access: String? = null, var refresh: String? = null) : TokenStore {
        override fun accessToken(): String? = access
        override fun refreshToken(): String? = refresh
        override fun save(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }
        override fun clear() {
            access = null
            refresh = null
        }
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * The fake rotation. Counts its calls because the count is the assertion that matters: refreshing
     * twice is what the server reads as a leaked token and answers by revoking the session family.
     */
    private fun client(refreshSucceeds: Boolean = true): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(store) {
                    refreshes.incrementAndGet()
                    if (refreshSucceeds) {
                        store.save(FRESH, "rotated-refresh-token")
                    } else {
                        store.clear()
                    }
                    refreshSucceeds
                },
            )
            .build()

    /**
     * Answers 200 only for a request carrying the rotated token, so a test that passes proves the
     * retry was made with it rather than merely made.
     */
    private fun freshTokenDispatcher(unauthorizedDelayMs: Long = 0): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Authorization") == "Bearer $FRESH") {
                    MockResponse().setResponseCode(200).setBody("{}")
                } else {
                    MockResponse()
                        .setResponseCode(HTTP_UNAUTHORIZED)
                        .setBody("""{"error":"expired"}""")
                        .setBodyDelay(unauthorizedDelayMs, TimeUnit.MILLISECONDS)
                }
        }

    private fun alwaysUnauthorized(): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(HTTP_UNAUTHORIZED).setBody("""{"error":"expired"}""")
        }

    private fun call(path: String = "/api/loading/schedules", client: OkHttpClient) =
        client.newCall(
            Request.Builder().url(server.url(path)).build(),
        ).execute()

    @Test
    fun `a 401 is refreshed once and the retry carries the new token`() {
        server.dispatcher = freshTokenDispatcher()

        call(client = client()).use { response ->
            assertEquals(200, response.code)
        }

        assertEquals(1, refreshes.get())
        assertEquals(FRESH, store.accessToken())
        // The first request carried the expired token, the retry the rotated one.
        assertEquals("/api/loading/schedules", server.takeRequest().path)
        assertEquals("Bearer $FRESH", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `two concurrent 401s cause exactly one refresh`() {
        // The delay is what puts both callers inside the window at once. Without it the requests can
        // serialise, the second one never sees a 401, and the test passes without having tested the
        // guarantee it names.
        server.dispatcher = freshTokenDispatcher(unauthorizedDelayMs = 150)

        val shared = client()
        val bothInFlight = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        repeat(2) {
            executor.submit {
                bothInFlight.await()
                call(client = shared).use { response -> assertEquals(200, response.code) }
                finished.countDown()
            }
        }
        bothInFlight.countDown()

        assertTrue("both calls should finish", finished.await(20, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("the pair must be rotated once, not once per caller", 1, refreshes.get())
    }

    @Test
    fun `the refresh call itself is never refreshed`() {
        server.dispatcher = alwaysUnauthorized()

        call(path = "/api/auth/refresh", client = client()).use { response ->
            assertEquals(HTTP_UNAUTHORIZED, response.code)
        }

        assertEquals("a 401 on refresh itself is the answer, not a reason to refresh", 0, refreshes.get())
    }

    @Test
    fun `a request with no session is passed through untouched`() {
        server.dispatcher = alwaysUnauthorized()
        store.clear()

        call(client = client()).use { response ->
            assertEquals(HTTP_UNAUTHORIZED, response.code)
        }

        assertEquals(0, refreshes.get())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a failed refresh clears the tokens and does not retry`() {
        server.dispatcher = alwaysUnauthorized()

        call(client = client(refreshSucceeds = false)).use { response ->
            assertEquals(HTTP_UNAUTHORIZED, response.code)
        }

        assertEquals(1, refreshes.get())
        assertNull("a token that cannot be refreshed must not be offered again", store.accessToken())
        assertEquals("the request was not sent a second time", 1, server.requestCount)
    }

    @Test
    fun `a second 401 after a successful refresh is returned rather than refreshed again`() {
        server.dispatcher = alwaysUnauthorized()

        call(client = client()).use { response ->
            assertEquals(HTTP_UNAUTHORIZED, response.code)
        }

        assertEquals("retrying once is the policy; looping is not", 1, refreshes.get())
    }

    private companion object {
        const val EXPIRED = "expired-token"
        const val FRESH = "fresh-token"
        const val HTTP_UNAUTHORIZED = 401
    }
}
