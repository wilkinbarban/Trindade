package com.trindade.app.update

import android.content.Context
import java.io.File

/**
 * Inspects the installed APK binary on the device.
 *
 * Only its size, which is what the same-version rule compares: the API publishes no digest, so there is no
 * second measure to read here and no reason to hash eleven megabytes on a phone. What the size buys is the
 * patch case -- a rebuild of the same version is a different file, and the file the phone runs can be
 * compared with the one being served without asking the operator to trust a timestamp.
 */
object InstalledAppInfo {

    fun getInstalledApkFile(context: Context): File? =
        runCatching {
            val sourceDir = context.applicationInfo?.sourceDir
            sourceDir?.let { File(it) }?.takeIf { it.exists() && it.isFile }
        }.getOrNull()

    fun getInstalledApkSize(context: Context): Long =
        getInstalledApkFile(context)?.length() ?: -1L
}
