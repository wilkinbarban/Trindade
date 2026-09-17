package com.trindade.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * Asserts that configuration actually reached the build.
 *
 * This replaces a placeholder that asserted arithmetic. That test proved the unit-test source set
 * runs, which was its one job, but it would have passed identically for a build whose base URL was
 * blank -- so it could not fail for the reason this project most needs it to. The value checked here
 * is the one D2 cannot work without and the one most likely to be wrong in a way that only shows up
 * at runtime, inside Retrofit, with an error that does not name the cause.
 *
 * The Gradle side refuses the same malformed values at configuration time. These assertions are not
 * a duplicate of that check: they cover the value as the app actually receives it, so a future
 * refactor of the build script that stops passing the property through is caught here.
 */
class ApiConfigurationTest {
    @Test
    fun `base url is an absolute http url ending in a slash`() {
        val baseUrl = BuildConfig.API_BASE_URL

        assertTrue("the base url must not be blank", baseUrl.isNotBlank())

        val uri = URI(baseUrl)
        assertTrue(
            "the base url must carry both a scheme and a host, but was '$baseUrl'",
            uri.scheme != null && uri.host != null,
        )
        assertTrue(
            "the base url must be http or https, but used '${uri.scheme}'",
            uri.scheme == "http" || uri.scheme == "https",
        )
        assertTrue(
            "Retrofit resolves every endpoint relative to the base url, so it must end in '/': '$baseUrl'",
            baseUrl.endsWith("/"),
        )
    }

    @Test
    fun `base url is absolute`() {
        // A URL missing its leading slash resolves as a relative reference, which Retrofit accepts at
        // construction and then fails on at the first call.
        val uri = URI(BuildConfig.API_BASE_URL)

        assertTrue(
            "the base url must be absolute, but was '${BuildConfig.API_BASE_URL}'",
            uri.isAbsolute,
        )
    }
}
