package com.trindade.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule the whole-app Material 3 pass is built on, checked rather than trusted: a colour literal lives in
 * the palette and nowhere else.
 *
 * `ui/theme/Theme.kt` is where `Color(0x…)` is allowed, because that is where the scheme's values are
 * declared. Every other source file has to take its colour from `MaterialTheme.colorScheme`, `HelpAmber` or
 * one of the other named constants -- and the reason is not tidiness: a screen that owns its own green cannot
 * be re-toned with the palette, and a component that hardcodes a light grey becomes unreadable the day a dark
 * scheme exists.
 *
 * This is a source scan and not a Compose test on purpose. What it guards is a property of the code, not of a
 * rendered frame, and a frame would have to be captured for every screen to say the same thing -- including
 * the ones nobody thought to look at.
 */
class M3TokenHygieneTest {

    private val paletteFile = "ui/theme/Theme.kt"

    @Test
    fun `no source file outside the palette declares a colour literal`() {
        val sourceRoot = findSourceRoot()
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.replace('\\', '/').endsWith(paletteFile) }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> HEX_COLOUR.containsMatchIn(line) }
                    .map { (index, line) ->
                        val relative = file.relativeTo(sourceRoot).path.replace('\\', '/')
                        "$relative:${index + 1}: ${line.trim()}"
                    }
            }
            .toList()

        assertTrue(
            "Colour literals outside $paletteFile: take the value from MaterialTheme.colorScheme, or name " +
                "it in the palette if it is a brand colour. Found:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Walks up from the working directory until it finds the module's `src/main/java`.
     *
     * Gradle runs a unit test from the module directory, but that is a fact about how the build is invoked
     * and not something to depend on silently: the walk makes the test say what it could not find instead of
     * passing because a `File` did not exist.
     */
    private fun findSourceRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            val candidate = File(directory, "src/main/java")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        error("Could not find src/main/java from ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** `Color(0xFF…)`, `Color(0x14000000)` and the four-digit forms. */
        val HEX_COLOUR = Regex("""Color\(0x[0-9A-Fa-f]+""")
    }
}
