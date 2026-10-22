package com.trindade.app.update

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Inspects the local installed APK binary on the device.
 *
 * Provides size and SHA-256 calculation for the running application's base.apk,
 * allowing byte-level comparison against remote release asset digests to avoid
 * spurious update loops when release timestamps or re-upload times differ.
 */
object InstalledAppInfo {

    @Volatile
    private var cachedSha256: String? = null

    fun getInstalledApkFile(context: Context): File? =
        runCatching {
            val sourceDir = context.applicationInfo?.sourceDir
            sourceDir?.let { File(it) }?.takeIf { it.exists() && it.isFile }
        }.getOrNull()

    fun getInstalledApkSize(context: Context): Long =
        getInstalledApkFile(context)?.length() ?: -1L

    fun getInstalledApkSha256(context: Context): String? {
        cachedSha256?.let { return it }

        val file = getInstalledApkFile(context) ?: return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            cachedSha256 = hex
            hex
        }.getOrNull()
    }

    /**
     * Visible for testing to reset in-memory cached digest.
     */
    internal fun clearCache() {
        cachedSha256 = null
    }
}
