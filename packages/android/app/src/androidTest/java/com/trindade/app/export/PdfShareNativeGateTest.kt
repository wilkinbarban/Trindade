package com.trindade.app.export

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfShareNativeGateTest {

    @Test
    fun boundedPdfShareCharacterization() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val targetContext = instr.targetContext
        assertEquals("com.trindade.app.nativegate", targetContext.packageName)
        assertEquals("com.trindade.app.nativegate.test", instr.context.packageName)

        val uniqueName = "fixture_${UUID.randomUUID()}.pdf"
        val exportsDir = File(targetContext.cacheDir, AndroidPdfGenerator.EXPORTS_DIR)
        assertFalse(File(exportsDir, uniqueName).exists())

        val generator = AndroidPdfGenerator(targetContext, exportsDir)
        val textLines = (1..59).joinToString("\n") { "line $it short" }
        var generatedFile: File? = null

        try {
            generatedFile = generator.generate(textLines, uniqueName)
            assertTrue(generatedFile.length() > 0L)

            ParcelFileDescriptor.open(generatedFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    assertEquals(2, renderer.pageCount)
                }
            }

            val shareManager = AndroidShareManager(targetContext)
            val chooser = shareManager.createPdfShareIntent(generatedFile, "Share")
            assertNotNull(chooser)

            val validChooser = chooser!!
            assertEquals(Intent.ACTION_CHOOSER, validChooser.action)
            assertTrue((validChooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
            assertTrue((validChooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

            @Suppress("DEPRECATION")
            val intent = validChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/pdf", intent.type)
            assertNull(intent.`package`)
            assertNull(intent.component)
            assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals("${targetContext.packageName}.fileprovider", uri.authority)
            assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)

            targetContext.contentResolver.openInputStream(uri).use { stream ->
                assertArrayEquals(generatedFile.readBytes(), stream?.readBytes())
            }
        } finally {
            generatedFile?.let {
                if (it.exists()) assertTrue(it.delete())
            }
        }
    }
}
