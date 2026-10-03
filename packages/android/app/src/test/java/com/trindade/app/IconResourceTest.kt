package com.trindade.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Validates adaptive launcher icon resource wiring for Variant F ("cinta dinâmica").
 *
 * Verifies that:
 * 1. The tracked foreground asset `ic_launcher_foreground_f.png` exists in `drawable-nodpi`
 *    and contains valid PNG image bytes with master square dimensions (512x512).
 * 2. The active foreground asset is the square dynamic icon ("cinta dinâmica") rather than
 *    the old rectangular multi-mask preview artwork ("cinta máscaras").
 * 3. `drawable/ic_launcher_foreground.xml` wraps `@drawable/ic_launcher_foreground_f` within
 *    Android's adaptive icon safe zone (18dp insets on a 108dp canvas).
 * 4. Both standard (`ic_launcher.xml`) and round (`ic_launcher_round.xml`) adaptive icon descriptors
 *    in `mipmap-anydpi-v26` wire `@color/ic_launcher_background` and `@drawable/ic_launcher_foreground`.
 * 5. `AndroidManifest.xml` preserves the canonical `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`
 *    references.
 */
class IconResourceTest {

    private val resDir: File = listOf(
        File("src/main/res"),
        File("packages/android/app/src/main/res"),
        File("app/src/main/res"),
    ).firstOrNull { it.isDirectory }
        ?: error("Cannot locate Android res directory from working directory: ${File(".").absolutePath}")

    private val manifestFile: File = listOf(
        File("src/main/AndroidManifest.xml"),
        File("packages/android/app/src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull { it.isFile }
        ?: error("Cannot locate AndroidManifest.xml from working directory: ${File(".").absolutePath}")

    private fun parseXml(file: File): Element {
        assertTrue("Resource file ${file.path} must exist", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        return builder.parse(file).documentElement
    }

    private fun getPngDimensions(file: File): Pair<Int, Int> {
        assertTrue("File ${file.name} must exist", file.isFile)
        val buffer = ByteArray(24)
        file.inputStream().use { it.read(buffer) }
        val width = ((buffer[16].toInt() and 0xFF) shl 24) or
            ((buffer[17].toInt() and 0xFF) shl 16) or
            ((buffer[18].toInt() and 0xFF) shl 8) or
            (buffer[19].toInt() and 0xFF)
        val height = ((buffer[20].toInt() and 0xFF) shl 24) or
            ((buffer[21].toInt() and 0xFF) shl 16) or
            ((buffer[22].toInt() and 0xFF) shl 8) or
            (buffer[23].toInt() and 0xFF)
        return Pair(width, height)
    }

    private fun assertValidPng(file: File) {
        assertTrue("${file.name} must exist in drawable-nodpi", file.isFile)
        assertTrue("${file.name} must not be empty", file.length() > 0)

        // Verify PNG magic header bytes: 0x89 'P' 'N' 'G' 0x0D 0x0A 0x1A 0x0A
        val header = ByteArray(8)
        file.inputStream().use { it.read(header) }
        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue("${file.name} must be a valid PNG file", header.contentEquals(pngMagic))
    }

    @Test
    fun `dynamic icon foreground asset exists and is valid PNG`() {
        val assetFile = File(resDir, "drawable-nodpi/ic_launcher_foreground_f.png")
        assertValidPng(assetFile)

        // Validates dynamic icon wiring rather than old artwork:
        // The dynamic icon ("F-cinta-dinamica") is square (1:1 aspect ratio, 512x512).
        // The old mistaken artwork ("F-cinta-mascaras") was a wide triple-preview image with ~3:1 aspect ratio.
        val (width, height) = getPngDimensions(assetFile)
        assertEquals("Dynamic icon width must equal height (square)", width, height)
        assertEquals("Dynamic icon master width must be 512px", 512, width)
        assertEquals("Dynamic icon master height must be 512px", 512, height)
    }

    @Test
    fun `foreground XML insets asset within safe zone`() {
        val foregroundFile = File(resDir, "drawable/ic_launcher_foreground.xml")
        val root = parseXml(foregroundFile)

        assertEquals("Root element of ic_launcher_foreground must be <inset>", "inset", root.tagName)

        val androidNs = "http://schemas.android.com/apk/res/android"
        val drawableAttr = root.getAttributeNS(androidNs, "drawable")
        assertEquals(
            "ic_launcher_foreground must point to @drawable/ic_launcher_foreground_f",
            "@drawable/ic_launcher_foreground_f",
            drawableAttr,
        )

        val insetAttr = root.getAttributeNS(androidNs, "inset")
        val insetTop = root.getAttributeNS(androidNs, "insetTop")
        val insetBottom = root.getAttributeNS(androidNs, "insetBottom")
        val insetLeft = root.getAttributeNS(androidNs, "insetLeft")
        val insetRight = root.getAttributeNS(androidNs, "insetRight")

        val has18dpInset = insetAttr == "18dp" ||
            (insetTop == "18dp" && insetBottom == "18dp" && insetLeft == "18dp" && insetRight == "18dp")
        assertTrue(
            "ic_launcher_foreground must apply 18dp inset for the adaptive icon safe zone, got inset='$insetAttr'",
            has18dpInset,
        )
    }

    @Test
    fun `brand launcher background color resource exists`() {
        val colorsFile = File(resDir, "values/colors.xml")
        val root = parseXml(colorsFile)
        val colors = root.getElementsByTagName("color")
        var found = false
        for (i in 0 until colors.length) {
            val colorEl = colors.item(i) as Element
            if (colorEl.getAttribute("name") == "ic_launcher_background") {
                found = true
                assertTrue("ic_launcher_background value must not be empty", colorEl.textContent.isNotBlank())
                break
            }
        }
        assertTrue("values/colors.xml must define ic_launcher_background", found)
    }

    @Test
    fun `standard adaptive icon wires background and foreground`() {
        val launcherFile = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml")
        val root = parseXml(launcherFile)

        assertEquals("Root element must be <adaptive-icon>", "adaptive-icon", root.tagName)

        val androidNs = "http://schemas.android.com/apk/res/android"
        val backgrounds = root.getElementsByTagName("background")
        assertEquals("Must have exactly 1 <background>", 1, backgrounds.length)
        val bgElement = backgrounds.item(0) as Element
        assertEquals(
            "Background must reference @color/ic_launcher_background",
            "@color/ic_launcher_background",
            bgElement.getAttributeNS(androidNs, "drawable"),
        )

        val foregrounds = root.getElementsByTagName("foreground")
        assertEquals("Must have exactly 1 <foreground>", 1, foregrounds.length)
        val fgElement = foregrounds.item(0) as Element
        assertEquals(
            "Foreground must reference @drawable/ic_launcher_foreground",
            "@drawable/ic_launcher_foreground",
            fgElement.getAttributeNS(androidNs, "drawable"),
        )
    }

    @Test
    fun `round adaptive icon wires background and foreground`() {
        val roundFile = File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml")
        val root = parseXml(roundFile)

        assertEquals("Root element must be <adaptive-icon>", "adaptive-icon", root.tagName)

        val androidNs = "http://schemas.android.com/apk/res/android"
        val backgrounds = root.getElementsByTagName("background")
        assertEquals("Must have exactly 1 <background>", 1, backgrounds.length)
        val bgElement = backgrounds.item(0) as Element
        assertEquals(
            "Background must reference @color/ic_launcher_background",
            "@color/ic_launcher_background",
            bgElement.getAttributeNS(androidNs, "drawable"),
        )

        val foregrounds = root.getElementsByTagName("foreground")
        assertEquals("Must have exactly 1 <foreground>", 1, foregrounds.length)
        val fgElement = foregrounds.item(0) as Element
        assertEquals(
            "Foreground must reference @drawable/ic_launcher_foreground",
            "@drawable/ic_launcher_foreground",
            fgElement.getAttributeNS(androidNs, "drawable"),
        )
    }

    @Test
    fun `manifest preserves standard and round icon references`() {
        val root = parseXml(manifestFile)
        val applications = root.getElementsByTagName("application")
        assertTrue("Manifest must declare <application>", applications.length > 0)
        val appElement = applications.item(0) as Element

        val androidNs = "http://schemas.android.com/apk/res/android"
        assertEquals(
            "Manifest must preserve android:icon='@mipmap/ic_launcher'",
            "@mipmap/ic_launcher",
            appElement.getAttributeNS(androidNs, "icon"),
        )
        assertEquals(
            "Manifest must preserve android:roundIcon='@mipmap/ic_launcher_round'",
            "@mipmap/ic_launcher_round",
            appElement.getAttributeNS(androidNs, "roundIcon"),
        )
    }
}
