package com.trindade.app.update

import android.content.Context
import com.trindade.app.di.NetworkModule
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int,
    ) : DownloadState {
        val bytesDownloadedMb: Float get() = bytesDownloaded / (1024f * 1024f)
        val totalBytesMb: Float get() = totalBytes / (1024f * 1024f)
        val progressFraction: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }
    data class Completed(val file: File) : DownloadState
    data class Error(val message: String) : DownloadState
}

@Singleton
class UpdateDownloader @Inject constructor(
    @NetworkModule.GitHubClient private val client: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    /**
     * Downloads an APK from [url] into context.cacheDir/updates/[targetFileName],
     * emitting [DownloadState.Downloading], [DownloadState.Completed] or [DownloadState.Error].
     */
    fun downloadApk(
        url: String,
        targetFileName: String = "trindade-update.apk",
    ): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0L, 0L, 0))

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, targetFileName)
        val tempFile = File(updatesDir, "$targetFileName.tmp")

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadState.Error("HTTP ${response.code}: Falha ao baixar atualização"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadState.Error("Resposta sem corpo"))
                return@flow
            }

            val totalBytes = body.contentLength()
            var bytesDownloaded = 0L

            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var lastPercent = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read
                        val percent = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
                        if (percent != lastPercent || bytesDownloaded == totalBytes) {
                            lastPercent = percent
                            emit(DownloadState.Downloading(bytesDownloaded, totalBytes, percent))
                        }
                    }
                }
            }

            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }

            emit(DownloadState.Completed(targetFile))
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            emit(DownloadState.Error(e.message ?: "Erro ao baixar atualização"))
        }
    }.flowOn(Dispatchers.IO)
}
