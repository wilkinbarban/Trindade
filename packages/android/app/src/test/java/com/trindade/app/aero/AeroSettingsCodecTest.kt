package com.trindade.app.aero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of the integration settings, tested where it is still pure.
 *
 * What can be checked without a device is the shape: the defaults a first run gets, the round trip the
 * screen depends on, and the decision that a blob this build cannot read is "nothing configured" rather
 * than a half-applied set. The encryption itself is Keystore work and is exercised on the emulator, not
 * here; pretending otherwise would need a fake cipher that proves nothing about the real one.
 */
class AeroSettingsCodecTest {

    @Test
    fun `a first run gets the plant defaults and is not ready`() {
        val settings = AeroSettings()
        assertEquals(AERO_DEFAULT_WS_URL, settings.wsUrl)
        assertEquals("http://192.168.25.250:1453/api", settings.normalizedWsUrl)
        assertEquals("", settings.username)
        assertFalse(settings.ready)
    }

    @Test
    fun `the round trip keeps all four values`() {
        val saved = AeroSettings(
            wsUrl = "http://192.168.25.250:1453/api/",
            username = "wilkin",
            password = "s3cr3t",
            userCode = "7",
        )

        val decoded = AeroSettingsCodec.decode(AeroSettingsCodec.encode(saved))

        assertEquals(saved, decoded)
        assertTrue(decoded.ready)
        assertEquals("http://192.168.25.250:1453/api", decoded.normalizedWsUrl)
    }

    @Test
    fun `an unreadable blob is the same as no blob`() {
        assertEquals(AeroSettings(), AeroSettingsCodec.decode(null))
        assertEquals(AeroSettings(), AeroSettingsCodec.decode(""))
        assertEquals(AeroSettings(), AeroSettingsCodec.decode("{not json"))
        assertEquals(AeroSettings(), AeroSettingsCodec.decode("""{"ws_url":""}"""))
    }

    @Test
    fun `a field added by a newer build does not lose the settings an older one understands`() {
        val decoded = AeroSettingsCodec.decode(
            """{"ws_url":"http://192.168.25.250:1453/api","username":"jefferson",""" +
                """"password":"x","user_code":"","future_field":true}""",
        )
        assertEquals("jefferson", decoded.username)
        assertEquals("x", decoded.password)
    }

    @Test
    fun `the plant address resolves to its host and port, with the scheme default`() {
        assertEquals(AeroHostPort("192.168.25.250", 1453), aeroHostPort("http://192.168.25.250:1453/api"))
        assertEquals(AeroHostPort("192.168.25.250", 80), aeroHostPort("http://192.168.25.250/api"))
        assertEquals(AeroHostPort("trindade.example", 443), aeroHostPort("https://trindade.example"))
        assertNull(aeroHostPort(""))
        assertNull(aeroHostPort(null))
    }
}
