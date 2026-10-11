package com.trindade.app.export

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfRecipientIdentityNativeGateTest {

    @Test
    fun verifyIsolatedRecipientIdentityGate() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val testContext = instr.context
        val pm = testContext.packageManager

        @Suppress("DEPRECATION")
        val testPackageUid = pm.getApplicationInfo(testContext.packageName, 0).uid
        val providerProcessUid = Process.myUid()
        val protectedPackageUid = getProtectedPackageUidFromShell(instr)
        val runnerPid = Process.myPid()

        assertTrue(testPackageUid > 0 && protectedPackageUid > 0)
        assertTrue("Provider process UID must differ from test package UID", providerProcessUid != testPackageUid)
        assertTrue("Provider process UID must differ from protected package UID", providerProcessUid != protectedPackageUid)
        assertTrue("Test package UID must differ from protected package UID", testPackageUid != protectedPackageUid)

        val latch = CountDownLatch(1)
        var callingUid = -1
        var callingPid = -1
        var reportedUid = -1
        var reportedPid = -1
        var receivedNonce: String? = null

        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == PdfGrantRecipientActivity.TRANSACTION_REPORT_IDENTITY) {
                    callingUid = Binder.getCallingUid()
                    callingPid = Binder.getCallingPid()
                    receivedNonce = data.readString()
                    reportedUid = data.readInt()
                    reportedPid = data.readInt()
                    reply?.writeInt(0)
                    latch.countDown()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }

        val nonce = "gate_${UUID.randomUUID()}"
        val bundle = Bundle().apply {
            putBinder(PdfGrantRecipientActivity.EXTRA_CALLBACK, callback)
            putString(PdfGrantRecipientActivity.EXTRA_NONCE, nonce)
        }
        val intent = Intent().apply {
            component = ComponentName(testContext, PdfGrantRecipientActivity::class.java)
            putExtras(bundle)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        testContext.startActivity(intent)

        assertTrue("Timed out waiting for recipient activity callback", latch.await(10, TimeUnit.SECONDS))
        assertEquals(nonce, receivedNonce)
        assertEquals(testPackageUid, reportedUid)
        assertEquals(testPackageUid, callingUid)
        assertEquals(callingUid, reportedUid)
        assertTrue("Reported PID must differ from runner PID", reportedPid != runnerPid)
        assertTrue("Calling PID must differ from runner PID", callingPid != runnerPid)
        assertEquals(callingPid, reportedPid)

        val statusBundle = Bundle().apply {
            putString("stream", "IDENTITY_EVIDENCE nonce=$nonce testPackageUid=$testPackageUid providerUid=$providerProcessUid protectedUid=$protectedPackageUid callingUid=$callingUid reportedUid=$reportedUid runnerPid=$runnerPid callingPid=$callingPid reportedPid=$reportedPid\n")
        }
        instr.sendStatus(0, statusBundle)
    }
}

/**
 * Reads shell metadata ONLY from package manager listing to determine
 * the protected package UID. This helper inspects shell metadata ONLY
 * and does NOT confer package identity, security grants, or PDF privileges.
 */
internal fun getProtectedPackageUidFromShell(instr: Instrumentation): Int {
    // Query shell metadata ONLY; this does NOT confer package identity, grant authority, or PDF privilege.
    val pfd = instr.uiAutomation.executeShellCommand("pm list packages -U com.trindade.app")
    val rawOutput = try {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4097)
            var totalRead = 0
            while (totalRead < buffer.size) {
                val count = reader.read(buffer, totalRead, buffer.size - totalRead)
                if (count <= 0) break
                totalRead += count
            }
            check(totalRead <= 4096) { "Shell output exceeded bounded limit of 4096 characters" }
            String(buffer, 0, totalRead)
        }
    } finally {
        try {
            pfd.close()
        } catch (_: Throwable) {
        }
    }

    val lineRegex = Regex("""package:([A-Za-z0-9_.]+) uid:([0-9]+)""")
    var matchedUid: Int? = null

    for (line in rawOutput.lines()) {
        if (line.isEmpty()) {
            continue
        }
        val match = lineRegex.matchEntire(line)
        checkNotNull(match) { "Malformed shell output line: '$line'" }

        val packageName = match.groupValues[1]
        val uidString = match.groupValues[2]

        val parsedUid = uidString.toIntOrNull()
        checkNotNull(parsedUid) { "Malformed UID integer '$uidString'" }

        if (packageName != "com.trindade.app") {
            continue
        }
        check(matchedUid == null) {
            "Duplicate package entry found for com.trindade.app"
        }

        matchedUid = parsedUid
    }

    val finalUid = checkNotNull(matchedUid) {
        "Package com.trindade.app absent in shell output"
    }
    assertEquals(10192, finalUid)
    return finalUid
}
