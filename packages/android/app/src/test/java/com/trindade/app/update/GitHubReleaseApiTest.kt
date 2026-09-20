package com.trindade.app.update

import com.trindade.app.BuildConfig
import com.trindade.app.di.NetworkModule
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The update check's wire half, which is the one part of it no other test reaches.
 *
 * The rest of the update check is exercised through `ProfileViewModelTest`'s stub of the interface, and a
 * stub cannot see either half of what this file is about. It cannot see the DTO's `@SerialName`, so a body
 * whose remote name is `tag_name` would decode to whatever the annotation said and no test would notice
 * until an operator did. And it cannot see the `@GET` path, which is the second copy of the repository slug
 * this project keeps on purpose -- a wrong owner or a wrong endpoint is invisible to every other test in
 * this suite.
 *
 * A real socket rather than a stub, for the reason `AuthInterceptorTest` gives: a hand-rolled fake would
 * test the decoding against my own idea of what Retrofit and kotlinx-serialization do with a body, and the
 * point here is what they do with GitHub's.
 */
class GitHubReleaseApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the wire payload decodes to the tag this app reads, extra fields and all`() {
        // The fixture's own claim, checked here rather than left to the reader: if these fields ever left it,
        // the test would still pass and would have stopped being about the thing it exists for.
        assertTrue(
            "the fixture must carry fields this client does not declare, or the decode below measures nothing",
            listOf("name", "body", "assets", "published_at", "prerelease")
                .all { latestReleaseBody.contains("\"$it\"") },
        )
        server.enqueue(jsonResponse(latestReleaseBody))

        val response = runBlocking { api().latestRelease() }

        assertTrue("a 200 carrying a release must read as successful", response.isSuccessful)
        // The one field the DTO declares, under the name GitHub really sends it. Two failures land here and
        // both are the point: a `@SerialName` that stopped naming `tag_name` throws inside the decode (the
        // field is required, with no default, by design), and a parser that did not ignore unknown keys throws
        // on the undeclared fields above -- both are `CouldNotCheck` in the app's own words, and both keep the
        // operator from ever being told about an update.
        val release = requireNotNull(response.body()) { "the payload did not decode" }
        assertEquals("v0.10.0", release.tagName)
    }

    @Test
    fun `the request goes to the repository the APK is published under`() {
        server.enqueue(jsonResponse(latestReleaseBody))

        runBlocking { api().latestRelease() }

        val path = server.takeRequest().path.orEmpty()
        // GitHub's newest-release endpoint, asserted through what really went on the wire. The repository is
        // left as the variable it is: spelling the slug out here would be a third handwritten copy of the
        // very fact this test checks, which is how the two that exist drift apart in the first place.
        val segments = path.trim('/').split('/')
        assertEquals("the path is GitHub's newest-release endpoint, not '$path'", 5, segments.size)
        assertEquals("repos", segments[0])
        assertEquals("releases", segments[3])
        assertEquals("latest", segments[4])

        // And the duplication this project accepts knowingly. The slug in the path above is the same project
        // fact the `releasesUrl` default in `build.gradle.kts` names -- one is a Retrofit annotation, so a
        // compile-time constant, and the other a Gradle property, so no mechanism can be one value for both.
        // This assertion is what holds them together: "if this project moves, both move together" is now
        // something a test fails about rather than something a comment asks the next editor to remember.
        val slug = "${segments[1]}/${segments[2]}"
        assertTrue(
            "the repository this client asks about ('$slug') must be the one the APK is published under " +
                "(${BuildConfig.RELEASES_URL})",
            BuildConfig.RELEASES_URL.contains(slug),
        )
    }

    /**
     * The timeouts this client carries, asserted as values because a value is what a revert moves.
     *
     * The client under test is the app's own -- `provideGitHubOkHttpClient`, the same call the Retrofit
     * below is built on -- and the three numbers are written here rather than read from the module that
     * states them, on purpose. Reading them back would assert that the constant equals itself, which no
     * change can fail: an edit that replaced this client's pair with the backend's would leave the test
     * green while the meaning of both clients moved. So these are copies, and the copying is the point of
     * the test rather than an oversight of it -- each one is the only thing in this suite that notices a
     * silent revert.
     *
     * The three are one decision and are not interchangeable: a per-read bound is not a bound on the call,
     * so the third one exists for what the first two cannot do, and `NetworkModule` states why the values
     * are what they are. The trickle test below is that same claim measured with a clock instead of read
     * off a configuration.
     */
    @Test
    fun `the GitHub client carries this check's own timeouts, not the backend's`() {
        val client = NetworkModule.provideGitHubOkHttpClient()

        assertEquals(
            "the connect timeout must stay this check's own value: falling back to the backend's is the " +
                "revert this assertion exists to catch, and the harm is not the number changing but one " +
                "number carrying two meanings again, so that a later edit to either client moves the other",
            5_000,
            client.connectTimeoutMillis,
        )
        assertEquals(
            "the read timeout must stay this check's own value: the backend's is sized for a request the " +
                "operator issued and is waiting on, and a check nobody asked for may not inherit a wait of " +
                "that size",
            10_000,
            client.readTimeoutMillis,
        )
        assertEquals(
            "the whole-call bound has to be here at all, because the two above are not one: a read timeout " +
                "bounds a single idle socket read, and a response that keeps arriving inside every window " +
                "never trips it, which leaves the check unresolved for as long as the server likes",
            15_000,
            client.callTimeoutMillis,
        )
    }

    /**
     * The bound the screen's silence is measured against, measured rather than stated.
     *
     * A read timeout bounds one idle socket read and not the call, so a server that answers and then
     * trickles bytes inside every window holds the call open for as long as it chooses: no single read is
     * ever idle long enough to trip it, the `Checking` state stays on the screen showing nothing, and the
     * socket and its coroutine stay held. The client's own whole-call timeout is what makes that silence a
     * bound instead of a hope, and this is the only test that says so with a clock rather than a comment --
     * the assertion above reads the value, and a value is not the behaviour it is there for.
     *
     * It costs about as long as the bound it measures, and that is the price of measuring a duration: the
     * only way to observe a bound is to wait it out. A faster version would have to shorten the bound, which
     * would change what is being tested, or assert the configuration instead, which the test above already
     * does.
     *
     * **Bounded from the outside too, and that bound is not decoration.** A client whose whole-call timeout
     * was removed has nothing that ends this call, and without [withTimeout] the suite would sit here on a
     * socket the server is still trickling into, for minutes past anything a result could be read from.
     * The outside bound turns that regression into an assertion failure instead -- which is the difference
     * between this test reporting the revert and this test being the reason nobody can see the report.
     */
    @Test
    fun `a response that keeps trickling is still ended by the whole-call bound`() {
        // One byte per period, on a body whose trickle takes far longer than the whole-call bound is long:
        // there is no window in which this response goes quiet long enough for a read timeout to fire,
        // whatever that timeout is set to, so only a bound on the call itself can end it.
        server.enqueue(
            jsonResponse(latestReleaseBody)
                .throttleBody(1, TRICKLE_PERIOD_MILLIS, TimeUnit.MILLISECONDS),
        )
        val client = NetworkModule.provideGitHubOkHttpClient()
        val callBoundMillis = client.callTimeoutMillis

        val startedAtNanos = System.nanoTime()
        assertThrows(IOException::class.java) {
            runBlocking {
                // The client's own bound, plus one read timeout's worth, plus the harness's slack: derived
                // rather than written down so that it stays past whatever this client is configured with,
                // including the client that has no whole-call bound at all. A call this harness kills is a
                // call the client never bounded, and that is the whole of what the regression case shows.
                withTimeout(callBoundMillis + client.readTimeoutMillis + HARNESS_GRACE_MILLIS) {
                    api().latestRelease()
                }
            }
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

        assertTrue(
            "the call ended after $elapsedMillis ms, short of the client's whole-call bound of " +
                "$callBoundMillis ms: the bound that ended it has to be this one, because a per-read bound " +
                "would have had to find the response quiet to fire, and this response is never quiet -- " +
                "it is still trickling when the call is cut",
            elapsedMillis >= callBoundMillis,
        )
    }

    /**
     * A real Retrofit over a real socket, configured the way the app configures GitHub's client.
     *
     * Both pieces are the app's own rather than copies: `provideGitHubOkHttpClient` is the destination's
     * tokenless client with its own timeouts, and `provideJson` is the parser whose `ignoreUnknownKeys` the
     * assertion about undeclared fields is really about.
     */
    private fun api(): GitHubReleaseApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(NetworkModule.provideGitHubOkHttpClient())
            .addConverterFactory(NetworkModule.provideJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubReleaseApi::class.java)

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        /**
         * A latest-release payload shaped the way GitHub sends one: the single field this app declares,
         * under the remote name it really carries, plus a set of fields this app deliberately does not
         * declare.
         *
         * That asymmetry is the fixture's whole job. `NetworkModule.provideJson()` ignores unknown keys, so
         * the body must be able to exercise it -- a payload carrying only `tag_name` would decode identically
         * for a client set up either way, and would leave the one setting that keeps a future GitHub field
         * from turning into "could not check" untested.
         *
         * How many undeclared fields it carries is deliberately not written down here or anywhere else in
         * prose. The fixture has to be *wider* than the DTO; how much wider is free to change, and a count in
         * a sentence is what goes stale when it does. Nothing in the reasoning above depends on the number,
         * and each undeclared field the decode actually leans on is asserted by name in the test that reads
         * this fixture -- which is where a claim about the fixture belongs.
         */
        val latestReleaseBody = """
            {
              "url": "https://api.github.com/repos/wilkinbarban/Trindade/releases/12345678",
              "html_url": "https://github.com/wilkinbarban/Trindade/releases/tag/v0.10.0",
              "id": 12345678,
              "tag_name": "v0.10.0",
              "target_commitish": "main",
              "name": "Trindade 0.10.0",
              "draft": false,
              "prerelease": false,
              "created_at": "2026-01-01T00:00:00Z",
              "published_at": "2026-01-02T03:04:05Z",
              "body": "O que mudou nesta versao.",
              "assets": [
                {
                  "name": "trindade-0.10.0.apk",
                  "size": 7340032,
                  "download_count": 3
                }
              ]
            }
        """.trimIndent()

        /**
         * How fast the trickling response is allowed to drip: one byte each period.
         *
         * The period is what keeps the whole-call-bound test from being a test of the read timeout. It has
         * to sit far below any read timeout either client could carry: a drip that slow would be ended by the
         * per-read bound instead, so the test would pass while measuring the wrong one.
         */
        const val TRICKLE_PERIOD_MILLIS = 500L

        /**
         * The slack the outside bound in that test carries on top of the client's own two bounds.
         *
         * A client with no whole-call timeout has to be killed by something, and this is what kills it. It
         * is derived from the client's bounds rather than written as a total, so the harness's patience stays
         * past whatever the client is configured to do: a call killed after the client's per-read bound
         * would already have fired is the evidence that no single read in this response is ever idle that
         * long. Both runs end the same way -- the call aborted -- and only the assertion after it tells them
         * apart.
         */
        const val HARNESS_GRACE_MILLIS = 10_000L
    }
}
