package com.trindade.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [AndroidShareManager] platform sharing seam.
 *
 * Verifies that text and PDF exports build safe ACTION_SEND chooser intents,
 * use FileProvider URIs with read permissions, handle missing/empty files safely,
 * and never hardcode WhatsApp or any specific package name.
 */
@RunWith(AndroidJUnit4::class)
class ShareManagerTest {

    private lateinit var context: Context
    private lateinit var shareManager: AndroidShareManager
    private lateinit var exportsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shareManager = AndroidShareManager(context)
        exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        clearFileProviderCache()
    }

    @After
    fun tearDown() {
        clearFileProviderCache()
    }

    private fun clearFileProviderCache() {
        try {
            val sCacheField = FileProvider::class.java.getDeclaredField("sCache")
            sCacheField.isAccessible = true
            (sCacheField.get(null) as? MutableMap<*, *>)?.clear()
        } catch (_: Throwable) {
            // Ignore if reflection is unavailable
        }
    }

    @Test
    fun `createTextShareIntent builds ACTION_SEND intent without package hardcode`() {
        val sampleText = "CRONOGRAMA DE CARREGAMENTO\n15/01/2025\n04:00 - João"
        val chooser = shareManager.createTextShareIntent(sampleText, "Compartilhar")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(
            "Chooser must have FLAG_ACTIVITY_NEW_TASK for ApplicationContext",
            (chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )

        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Chooser must wrap a target intent", target)
        assertEquals(Intent.ACTION_SEND, target!!.action)
        assertEquals("text/plain", target.type)
        assertEquals(sampleText, target.getStringExtra(Intent.EXTRA_TEXT))

        assertNull("ShareManager must not hardcode WhatsApp or any package", target.`package`)
    }

    @Test
    fun `createPdfShareIntent builds ACTION_SEND intent with FileProvider URI and read permissions`() {
        val pdfFile = File(exportsDir, "relatorio-42.pdf").apply {
            writeBytes("%PDF-1.4 sample content".toByteArray())
        }

        val chooser = shareManager.createPdfShareIntent(pdfFile, "Compartilhar PDF")
        assertNotNull("Valid PDF file must produce a non-null chooser intent", chooser)

        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        assertTrue(
            "Chooser must have FLAG_GRANT_READ_URI_PERMISSION",
            (chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
        assertTrue(
            "Chooser must have FLAG_ACTIVITY_NEW_TASK",
            (chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )

        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(target)
        assertEquals(Intent.ACTION_SEND, target!!.action)
        assertEquals("application/pdf", target.type)

        @Suppress("DEPRECATION")
        val streamUri = target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("Target intent must carry content URI in EXTRA_STREAM", streamUri)
        assertTrue("URI scheme must be content://", streamUri!!.toString().startsWith("content://"))
        assertTrue(
            "URI authority must match application fileprovider",
            streamUri.authority?.endsWith(".fileprovider") == true,
        )

        assertTrue(
            "Target intent must have FLAG_GRANT_READ_URI_PERMISSION",
            (target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
        assertNotNull("ClipData must be populated for reliable URI permission propagation", target.clipData)
        assertEquals(streamUri, target.clipData?.getItemAt(0)?.uri)

        assertNull("ShareManager must not hardcode WhatsApp or any package", target.`package`)
    }

    @Test
    fun `createPdfShareIntent safely returns null for non-existent file`() {
        val nonExistentFile = File(exportsDir, "does_not_exist.pdf")
        val chooser = shareManager.createPdfShareIntent(nonExistentFile)
        assertNull("Non-existent file must not produce a share intent", chooser)
    }

    @Test
    fun `createPdfShareIntent safely returns null for zero-byte file`() {
        val emptyFile = File(exportsDir, "empty.pdf").apply {
            writeBytes(ByteArray(0))
        }
        val chooser = shareManager.createPdfShareIntent(emptyFile)
        assertNull("Empty 0-byte file must not produce a share intent", chooser)
    }

    @Test
    fun `shareText launches system chooser without crashing`() {
        val launched = shareManager.shareText("Export text to share", "Compartilhar")
        assertTrue("shareText must successfully launch chooser on ApplicationContext", launched)
    }

    @Test
    fun `sharePdf launches system chooser for valid PDF file`() {
        val pdfFile = File(exportsDir, "cronograma-2025-01-15.pdf").apply {
            writeBytes("%PDF-1.4 test".toByteArray())
        }
        val launched = shareManager.sharePdf(pdfFile, "Compartilhar Cronograma")
        assertTrue("sharePdf must successfully launch chooser for existing file", launched)
    }

    @Test
    fun `sharePdf returns false and does not throw for non-existent file`() {
        val nonExistent = File(exportsDir, "missing.pdf")
        val launched = shareManager.sharePdf(nonExistent)
        assertFalse("sharePdf must return false when file is missing", launched)
    }
}
