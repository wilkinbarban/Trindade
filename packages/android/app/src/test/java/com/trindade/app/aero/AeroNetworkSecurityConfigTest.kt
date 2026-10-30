package com.trindade.app.aero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cleartext exemption, read as a fact about the shipped files rather than trusted as an intention.
 *
 * A release APK has to reach the plant's WebService over plain HTTP, and the cost of that is an exemption
 * whose blast radius is a security decision. Reviewing it by eye is how it drifts: one `<base-config>`
 * added during a debugging session re-opens every destination the app talks to, and nothing in the build
 * complains. These assertions are the complaint.
 */
class AeroNetworkSecurityConfigTest {

    private fun resource(subpath: String): File {
        val candidates = listOf(
            File("src/main/res", subpath),
            File("app/src/main/res", subpath),
            File("packages/android/app/src/main/res", subpath),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find resource $subpath in candidates: $candidates")
    }

    private fun mainConfig(): String = resource("xml/network_security_config.xml").readText()

    private fun debugConfig(): File {
        val candidates = listOf(
            File("src/debug/res/xml/network_security_config.xml"),
            File("app/src/debug/res/xml/network_security_config.xml"),
            File("packages/android/app/src/debug/res/xml/network_security_config.xml"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find the debug network security config in candidates: $candidates")
    }

    private fun manifest(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("packages/android/app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("Could not find AndroidManifest.xml in candidates: $candidates")
    }

    private fun domainsIn(xml: String): List<String> =
        Regex("<domain[^>]*>([^<]+)</domain>").findAll(xml).map { it.groupValues[1].trim() }.toList()

    @Test
    fun `the release exemption is exactly the plant host`() {
        assertEquals(listOf("192.168.25.250"), domainsIn(mainConfig()))
    }

    @Test
    fun `the exemption does not open every destination`() {
        val config = mainConfig().replace(Regex("<!--[\\s\\S]*?-->"), "")
        assertFalse(
            "A base-config in the release file re-opens every host the app talks to",
            config.contains("<base-config"),
        )
        assertFalse(
            "A subdomain wildcard widens the exemption beyond the address that was reviewed",
            config.contains("includeSubdomains=\"true\""),
        )
    }

    @Test
    fun `the manifest points at the config and asks for the network state`() {
        val manifest = manifest()
        assertTrue(
            "android:networkSecurityConfig must be declared, or the file above is never applied",
            manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
        )
        assertTrue(
            "The reachability probe needs ACCESS_NETWORK_STATE to answer without asking for location",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE"),
        )
    }

    @Test
    fun `the debug overlay keeps the plant host it would otherwise lose`() {
        val debug = debugConfig()
        assertTrue("The debug overlay must exist where the release file is overridden", debug.exists())
        val domains = domainsIn(debug.readText())
        assertTrue(
            "A debug build that cannot reach the plant makes the native integration untestable there: $domains",
            domains.contains("192.168.25.250"),
        )
    }
}
