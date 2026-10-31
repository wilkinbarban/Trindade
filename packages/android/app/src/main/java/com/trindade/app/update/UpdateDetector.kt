package com.trindade.app.update

import com.trindade.app.contract.models.LatestReleaseResponse
import java.time.Instant

enum class UpdateType {
    NEW_RELEASE,
    SAME_RELEASE_PATCH,
    NONE,
    UNDETERMINED,
}

data class UpdateAvailability(
    val type: UpdateType,
    val release: LatestReleaseResponse,
) {
    val isUpdateAvailable: Boolean get() = type == UpdateType.NEW_RELEASE || type == UpdateType.SAME_RELEASE_PATCH
}

object UpdateDetector {

    /**
     * Reads the answer from `/api/app/latest-release` as a decision about the installed build.
     *
     * The rules, in the order they decide:
     * 1. A newer `version` than the installed one is an update (`v0.7.0` against `0.6.3`).
     * 2. An older one is nothing: an older build is not a patch of this one, and offering it produced the
     *    mandatory dialog whose only action led to a downgrade the installer refuses.
     * 3. A version this client cannot read is undetermined: `v0.3.0-rc1` is not something to compare, and a
     *    guess is worse than saying so.
     * 4. The same version, with both sizes known, is a patch when they differ and up to date when they match.
     *    The size is the whole binary, so it moves whenever the build does.
     * 5. The same version without a comparable size falls back to the moment the APK was published, strictly
     *    after this build's own time. There is no digest rule: the API publishes none, and a rule over a field
     *    that does not exist is a claim this check cannot keep.
     *
     * A size of zero, or none at all, is *not known* rather than *empty*: the route reports what it measured
     * on disk, and reading a missing measurement as a zero-byte file would call every build a patch forever.
     */
    fun checkUpdate(
        release: LatestReleaseResponse,
        currentVersionName: String,
        currentBuildTimeMillis: Long,
        installedSizeBytes: Long = -1L,
    ): UpdateAvailability {
        when (ReleaseVersion.compare(release.version, currentVersionName)) {
            is ReleaseVersion.Comparison.Undetermined ->
                return UpdateAvailability(UpdateType.UNDETERMINED, release)

            is ReleaseVersion.Comparison.Newer ->
                return UpdateAvailability(UpdateType.NEW_RELEASE, release)

            is ReleaseVersion.Comparison.Older ->
                return UpdateAvailability(UpdateType.NONE, release)

            is ReleaseVersion.Comparison.SameVersion -> Unit
        }

        val remoteSize = release.apkSize?.toLong() ?: 0L
        if (installedSizeBytes > 0L && remoteSize > 0L) {
            return if (remoteSize == installedSizeBytes) {
                UpdateAvailability(UpdateType.NONE, release)
            } else {
                UpdateAvailability(UpdateType.SAME_RELEASE_PATCH, release)
            }
        }

        val publishedMillis = release.publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        if (publishedMillis != null && publishedMillis > currentBuildTimeMillis) {
            return UpdateAvailability(UpdateType.SAME_RELEASE_PATCH, release)
        }

        return UpdateAvailability(UpdateType.NONE, release)
    }
}
