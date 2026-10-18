package com.trindade.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.O])
class InstallPermissionManagerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `createManageUnknownAppSourcesIntent targets current package settings`() {
        val manager = InstallPermissionManager(context)
        val intent = manager.createManageUnknownAppSourcesIntent()

        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `canRequestPackageInstalls returns boolean status`() {
        val manager = InstallPermissionManager(context)
        // In Robolectric test environment, canRequestPackageInstalls() returns default mock value
        assertNotNull(manager.canRequestPackageInstalls())
    }
}
