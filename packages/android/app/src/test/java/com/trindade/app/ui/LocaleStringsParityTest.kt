package com.trindade.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class LocaleStringsParityTest {

    private fun findResFile(subpath: String): File {
        val candidates = listOf(
            File("src/main/res", subpath),
            File("app/src/main/res", subpath),
            File("packages/android/app/src/main/res", subpath),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find resource file for $subpath in candidates: $candidates")
    }

    private fun extractStrings(file: File): Map<String, String> {
        val text = file.readText()
        val pattern = Pattern.compile("""<string\s+name="([^"]+)">([\s\S]*?)</string>""")
        val matcher = pattern.matcher(text)
        val result = mutableMapOf<String, String>()
        while (matcher.find()) {
            val key = matcher.group(1)
            val value = matcher.group(2)
            result[key] = value
        }
        return result
    }

    private fun extractSpecifiers(value: String): List<String> {
        val pattern = Pattern.compile("""%\d+\$[sdf]""")
        val matcher = pattern.matcher(value)
        val result = mutableListOf<String>()
        while (matcher.find()) {
            result.add(matcher.group())
        }
        return result.sorted()
    }

    @Test
    fun `values-es strings xml has exact key parity with default values strings xml`() {
        val ptStrings = extractStrings(findResFile("values/strings.xml"))
        val esStrings = extractStrings(findResFile("values-es/strings.xml"))

        assertTrue("PT strings must not be empty", ptStrings.isNotEmpty())
        assertTrue("ES strings must not be empty", esStrings.isNotEmpty())

        val missingInEs = ptStrings.keys - esStrings.keys
        val extraInEs = esStrings.keys - ptStrings.keys

        assertTrue("Keys missing in values-es: $missingInEs", missingInEs.isEmpty())
        assertTrue("Extra keys in values-es not in default: $extraInEs", extraInEs.isEmpty())
        assertEquals(ptStrings.size, esStrings.size)
    }

    @Test
    fun `format specifiers match between default and spanish strings`() {
        val ptStrings = extractStrings(findResFile("values/strings.xml"))
        val esStrings = extractStrings(findResFile("values-es/strings.xml"))

        val mismatches = mutableListOf<String>()
        for ((key, ptVal) in ptStrings) {
            val esVal = esStrings[key] ?: continue
            val ptSpecs = extractSpecifiers(ptVal)
            val esSpecs = extractSpecifiers(esVal)
            if (ptSpecs != esSpecs) {
                mismatches.add("$key: PT=$ptSpecs vs ES=$esSpecs")
            }
        }

        assertTrue("Format specifier mismatches found:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }
}
