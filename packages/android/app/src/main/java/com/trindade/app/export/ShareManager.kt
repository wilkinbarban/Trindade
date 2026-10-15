package com.trindade.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam for sharing export content via the platform's ACTION_SEND chooser.
 *
 * Uses generic Android ACTION_SEND intents with [Intent.createChooser] rather than targeting
 * WhatsApp or any specific vendor package directly.
 *
 * Grants read URI permissions safely through FileProvider for temporary cache exports.
 */
interface ShareManager {
    /**
     * Launches the platform ACTION_SEND chooser with the given [text].
     *
     * @param text The verbatim export text to share.
     * @param title Optional chooser title shown to the operator.
     * @return true if the chooser was launched successfully, false otherwise.
     */
    fun shareText(text: String, title: String? = null): Boolean

    /**
     * Launches the platform ACTION_SEND chooser with the given PDF [file].
     *
     * Creates a FileProvider content URI scoped to the application's cache exports directory,
     * granting temporary read URI permissions to the receiving application.
     *
     * @param file The generated PDF file in the cache exports directory.
     * @param title Optional chooser title shown to the operator.
     * @return true if the chooser was launched successfully, false otherwise.
     */
    fun sharePdf(file: File, title: String? = null): Boolean
}

/**
 * Platform implementation of [ShareManager] backed by [FileProvider] and Android [Intent.ACTION_SEND].
 */
@Singleton
class AndroidShareManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : ShareManager {

    /**
     * Builds the chooser [Intent] for sharing plain text.
     *
     * Exposed as internal for direct JVM/Robolectric verification of intent action, MIME type,
     * extra text, flags, and absence of hardcoded package names.
     */
    internal fun createTextShareIntent(text: String, title: String? = null): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Builds the chooser [Intent] for sharing a PDF file via [FileProvider].
     *
     * Grants [Intent.FLAG_GRANT_READ_URI_PERMISSION] and attaches the URI to [Intent.setClipData]
     * so that receiving applications on all API levels receive explicit read access to the
     * cache-scoped export file.
     *
     * Returns null safely if the file does not exist, is empty, or if FileProvider URI creation fails.
     */
    internal fun createPdfShareIntent(file: File, title: String? = null): Intent? {
        if (!file.exists() || file.length() == 0L) {
            return null
        }
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (_: Throwable) {
            return null
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("PDF", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun shareText(text: String, title: String?): Boolean {
        return try {
            val chooser = createTextShareIntent(text, title)
            context.startActivity(chooser)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun sharePdf(file: File, title: String?): Boolean {
        return try {
            val chooser = createPdfShareIntent(file, title) ?: return false
            context.startActivity(chooser)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * No-op implementation of [ShareManager] used for test defaults or fallback.
 */
object NoOpShareManager : ShareManager {
    override fun shareText(text: String, title: String?): Boolean = true
    override fun sharePdf(file: File, title: String?): Boolean = true
}

/**
 * No-op implementation of [PdfGenerator] used for test defaults or fallback.
 */
object NoOpPdfGenerator : PdfGenerator {
    override suspend fun generate(text: String, fileName: String): File = File("/tmp/$fileName")
}
