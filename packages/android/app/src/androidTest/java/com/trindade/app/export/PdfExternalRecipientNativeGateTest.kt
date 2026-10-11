package com.trindade.app.export

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfExternalRecipientNativeGateTest {

    @Test
    fun externalRecipientReadsSharedPdf() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val targetContext = instr.targetContext
        val testContext = instr.context
        val pm = testContext.packageManager

        @Suppress("DEPRECATION")
        val testPackageUid = pm.getApplicationInfo(testContext.packageName, 0).uid
        val providerUid = Process.myUid()
        // Shell metadata lookup isolates package UID without conferring grants or permissions
        val protectedUid = getProtectedPackageUidFromShell(instr)
        val runnerPid = Process.myPid()

        assertEquals(10194, testPackageUid); assertEquals(10193, providerUid); assertEquals(10192, protectedUid)

        var generatedFile: File? = null
        var streamUri: Uri? = null

        try {
            val exportsDir = File(targetContext.cacheDir, AndroidPdfGenerator.EXPORTS_DIR)
            val file = AndroidPdfGenerator(targetContext, exportsDir).generate((1..59).joinToString("\n") { "line $it short" }, "fixture_${UUID.randomUUID()}.pdf")
            generatedFile = file

            val fileLength = file.length()
            assertTrue("Generated file must be at least 4096 bytes", fileLength >= 4096L)
            val prefixLen = minOf(fileLength, 4096L).toInt()
            val expectedPrefix = ByteArray(prefixLen)
            DataInputStream(file.inputStream()).use { it.readFully(expectedPrefix) }
            assertEquals("%PDF-", String(expectedPrefix.copyOf(5), Charsets.US_ASCII))

            val chooser = checkNotNull(AndroidShareManager(targetContext).createPdfShareIntent(file, "Share")) { "Chooser intent must not be null" }
            @Suppress("DEPRECATION")
            val sendIntent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION")
            val uri = sendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            streamUri = uri
            assertEquals("${targetContext.packageName}.fileprovider", uri.authority)
            assertEquals(1, sendIntent.clipData?.itemCount)
            assertEquals(uri, sendIntent.clipData?.getItemAt(0)?.uri)

            val latch = CountDownLatch(1)
            var callingUid = -1; var callingPid = -1; var reportedUid = -1; var reportedPid = -1
            var reportedNonce: String? = null; var reportedResult: String? = null; var reportedExceptionClass: String? = null
            var reportedCount = -1; var reportedPrefix: ByteArray? = null

            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == PdfGrantRecipientActivity.TRANSACTION_REPORT_PDF) {
                        callingUid = Binder.getCallingUid(); callingPid = Binder.getCallingPid()
                        reportedNonce = data.readString()
                        reportedUid = data.readInt(); reportedPid = data.readInt()
                        reportedResult = data.readString(); reportedExceptionClass = data.readString()?.ifEmpty { null }
                        reportedCount = data.readInt(); reportedPrefix = data.createByteArray()
                        reply?.writeInt(0); latch.countDown(); return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            val nonce = "gate_${UUID.randomUUID()}"
            val bundle = Bundle().apply {
                putBinder(PdfGrantRecipientActivity.EXTRA_CALLBACK, callback)
                putString(PdfGrantRecipientActivity.EXTRA_NONCE, nonce)
                putInt(PdfGrantRecipientActivity.EXTRA_MODE, PdfGrantRecipientActivity.MODE_PDF)
            }
            sendIntent.flags = sendIntent.flags or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            sendIntent.component = ComponentName(testContext, PdfGrantRecipientActivity::class.java)
            sendIntent.putExtras(bundle)

            val grantFlag = (sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
            assertEquals(true, grantFlag)

            targetContext.startActivity(sendIntent)

            assertTrue("Timed out waiting for recipient activity callback", latch.await(10, TimeUnit.SECONDS))
            assertEquals(nonce, reportedNonce)
            assertEquals(testPackageUid, callingUid); assertEquals(testPackageUid, reportedUid)
            assertTrue("Calling PID must differ from runner", callingPid > 0 && callingPid != runnerPid)
            assertEquals(callingPid, reportedPid)

            assertEquals(PdfGrantRecipientActivity.RESULT_READ_OK, reportedResult)
            assertNull(reportedExceptionClass)

            val statusBundle = Bundle().apply {
                putString("stream", "PDF_GREEN_EVIDENCE nonce=$nonce authority=${uri.authority} grantFlag=$grantFlag testPackageUid=$testPackageUid providerUid=$providerUid protectedUid=$protectedUid callingUid=$callingUid reportedUid=$reportedUid runnerPid=$runnerPid callingPid=$callingPid reportedPid=$reportedPid result=$reportedResult exceptionClass=$reportedExceptionClass count=$reportedCount\n")
            }
            instr.sendStatus(0, statusBundle)

            assertEquals(PdfGrantRecipientActivity.RESULT_READ_OK, reportedResult)
            assertEquals(prefixLen, reportedCount)
            assertArrayEquals(expectedPrefix, reportedPrefix)
        } finally {
            var cleanupSuccess = true
            try { streamUri?.let { targetContext.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } } catch (_: Exception) { cleanupSuccess = false }
            try { generatedFile?.let { if (it.exists() && !it.delete()) cleanupSuccess = false } } catch (_: Exception) { cleanupSuccess = false }
            val cleanupBundle = Bundle().apply { putString("stream", "PDF_CLEANUP_EVIDENCE success=$cleanupSuccess\n") }
            instr.sendStatus(0, cleanupBundle)
            if (!cleanupSuccess) error("Cleanup failed: fixture delete or revoke failed (SETUP failure)")
        }
    }
}
