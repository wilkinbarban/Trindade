package com.trindade.app.update

enum class UpdateType {
    NEW_RELEASE,
    SAME_RELEASE_PATCH,
    NONE,
    UNDETERMINED,
}

data class UpdateAvailability(
    val type: UpdateType,
    val release: GitHubRelease,
    val apkAsset: GitHubAsset?,
) {
    val isUpdateAvailable: Boolean get() = type == UpdateType.NEW_RELEASE || type == UpdateType.SAME_RELEASE_PATCH
}

object UpdateDetector {

    /**
     * Checks if a GitHub release represents an update compared to the installed build.
     *
     * Detects:
     * 1. New release if tagName > currentVersionName (e.g. v0.6.0 > 0.5.0).
     * 2. If tagName == currentVersionName and an APK asset is present:
     *    - If both remote digest and installed SHA-256 are available: returns NONE if matching,
     *      or SAME_RELEASE_PATCH if different.
     *    - If size is available: returns NONE if sizes match, or SAME_RELEASE_PATCH if different.
     *    - Fallback: compares remote asset updated_at strictly against currentBuildTimeMillis.
     * 3. Nothing at all if tagName < currentVersionName: an older release is not a patch of this build.
     * 4. Undetermined if version tag cannot be parsed.
     *
     * The second rule applies **only to the same version**. An older release whose APK is a different
     * size, digest or timestamp is older by every measure this check has, and comparing its asset with
     * the installed build answers the wrong question: the difference is guaranteed and means nothing.
     * Reading it as a patch put a mandatory update dialog in front of an operator already running a newer
     * build, with no way forward.
     */
    fun checkUpdate(
        release: GitHubRelease,
        currentVersionName: String,
        currentBuildTimeMillis: Long,
        installedSha256: String? = null,
        installedSizeBytes: Long = -1L,
    ): UpdateAvailability {
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }

        when (ReleaseVersion.compare(release.tagName, currentVersionName)) {
            is ReleaseVersion.Comparison.Undetermined ->
                return UpdateAvailability(UpdateType.UNDETERMINED, release, apkAsset)

            is ReleaseVersion.Comparison.Newer ->
                return UpdateAvailability(UpdateType.NEW_RELEASE, release, apkAsset)

            // Nothing to install, and nothing to compare: this build is ahead of the newest release.
            is ReleaseVersion.Comparison.Older ->
                return UpdateAvailability(UpdateType.NONE, release, apkAsset)

            is ReleaseVersion.Comparison.SameVersion -> Unit
        }

        if (apkAsset != null) {
            val remoteDigest = apkAsset.digest?.removePrefix("sha256:")?.trim()
            val localDigest = installedSha256?.removePrefix("sha256:")?.trim()

            if (!remoteDigest.isNullOrBlank() && !localDigest.isNullOrBlank()) {
                return if (remoteDigest.equals(localDigest, ignoreCase = true)) {
                    UpdateAvailability(UpdateType.NONE, release, apkAsset)
                } else {
                    UpdateAvailability(UpdateType.SAME_RELEASE_PATCH, release, apkAsset)
                }
            }

            if (installedSizeBytes > 0L && apkAsset.size > 0L) {
                return if (apkAsset.size == installedSizeBytes) {
                    UpdateAvailability(UpdateType.NONE, release, apkAsset)
                } else {
                    UpdateAvailability(UpdateType.SAME_RELEASE_PATCH, release, apkAsset)
                }
            }

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
