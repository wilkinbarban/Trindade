package com.trindade.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: InstallPermissionManager,
) {
    sealed interface InstallResult {
        data class Launched(val intent: Intent) : InstallResult
        data class PermissionRequired(val manageIntent: Intent) : InstallResult
        data class Error(val message: String) : InstallResult
    }

    /**
     * Builds the Intent to install an APK file using FileProvider.
     */
    fun createInstallIntent(apkFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Triggers the package installation if permission is granted, or returns [InstallResult.PermissionRequired].
     */
    fun installApk(apkFile: File): InstallResult {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return InstallResult.Error("Arquivo APK inválido ou não encontrado")
        }

        if (!permissionManager.canRequestPackageInstalls()) {
            val manageIntent = permissionManager.createManageUnknownAppSourcesIntent().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(manageIntent) }
            return InstallResult.PermissionRequired(manageIntent)
        }

        return try {
            val intent = createInstallIntent(apkFile)
            context.startActivity(intent)
            InstallResult.Launched(intent)
        } catch (e: Exception) {
            InstallResult.Error(e.message ?: "Erro ao disparar instalador")
        }
    }
}
