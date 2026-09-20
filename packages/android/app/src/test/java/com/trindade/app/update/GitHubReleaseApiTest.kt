package com.trindade.app.update

import com.trindade.app.BuildConfig
import com.trindade.app.di.NetworkModule
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        // on the five fields above -- both are `CouldNotCheck` in the app's own words, and both keep the
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
         * under the remote name it really carries, plus five fields this app deliberately does not declare.
         *
         * That asymmetry is the fixture's whole job. `NetworkModule.provideJson()` ignores unknown keys, so
         * the body must be able to exercise it -- a payload carrying only `tag_name` would decode identically
         * for a client set up either way, and would leave the one setting that keeps a future GitHub field
         * from turning into "could not check" untested.
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
    }
}
