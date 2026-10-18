package com.trindade.app.update

enum class UpdateType {
    NEW_RELEASE,
    SAME_RELEASE_PATCH,
    NONE,
}

data class UpdateAvailability(
    val type: UpdateType,
    val release: GitHubRelease,
    val apkAsset: GitHubAsset?,
) {
    val isUpdateAvailable: Boolean get() = type != UpdateType.NONE
}

object UpdateDetector {

    /**
     * Checks if a GitHub release represents an update compared to the installed build.
     *
     * Detects:
     * 1. New release if tagName > currentVersionName (e.g. v0.6.0 > 0.5.0)
     * 2. Same-release patch if tagName == currentVersionName and remote APK asset updated_at
     *    is strictly newer than currentBuildTimeMillis.
     */
    fun checkUpdate(
        release: GitHubRelease,
        currentVersionName: String,
        currentBuildTimeMillis: Long,
    ): UpdateAvailability {
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
        val comparison = ReleaseVersion.compare(release.tagName, currentVersionName)

        if (comparison is ReleaseVersion.Comparison.Newer) {
            return UpdateAvailability(UpdateType.NEW_RELEASE, release, apkAsset)
        }

        if (comparison is ReleaseVersion.Comparison.UpToDate && apkAsset != null) {
            val assetMillis = runCatching {
                java.time.Instant.parse(apkAsset.updatedAt).toEpochMilli()
            }.getOrDefault(0L)

            if (assetMillis > currentBuildTimeMillis) {
                return UpdateAvailability(UpdateType.SAME_RELEASE_PATCH, release, apkAsset)
            }
        }

        return UpdateAvailability(UpdateType.NONE, release, apkAsset)
    }
}
