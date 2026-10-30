package com.trindade.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UpdateDetectorTest {

    private fun createRelease(
        tagName: String,
        assetUpdatedAt: String? = null,
        assetName: String = "trindade.apk",
        assetDigest: String? = null,
        assetSize: Long = 0L,
    ): GitHubRelease {
        val assets = if (assetUpdatedAt != null || assetDigest != null || assetSize > 0L) {
            listOf(
                GitHubAsset(
                    name = assetName,
                    browserDownloadUrl = "https://github.com/trindade/releases/download/$tagName/$assetName",
                    updatedAt = assetUpdatedAt ?: "2026-05-18T12:00:00Z",
                    size = assetSize,
                    digest = assetDigest,
                )
            )
        } else {
            emptyList()
        }

        return GitHubRelease(
            tagName = tagName,
            name = "Release $tagName",
            publishedAt = "2026-05-18T10:00:00Z",
            assets = assets,
        )
    }

    @Test
    fun checkUpdate_withHigherVersion_returnsNewRelease() {
        val release = createRelease(tagName = "v0.6.0")
        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = 1000L,
        )

        assertEquals(UpdateType.NEW_RELEASE, availability.type)
        assertTrue(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withSameVersionAndNewerAsset_returnsSameReleasePatch() {
        val currentBuildTime = Instant.parse("2026-05-18T10:00:00Z").toEpochMilli()
        val assetTime = "2026-05-18T12:00:00Z" // 2 hours newer
        val release = createRelease(tagName = "v0.5.1", assetUpdatedAt = assetTime)

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = currentBuildTime,
        )

        assertEquals(UpdateType.SAME_RELEASE_PATCH, availability.type)
        assertTrue(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withSameVersionAndOlderOrEqualAsset_returnsNone() {
        val currentBuildTime = Instant.parse("2026-05-18T15:00:00Z").toEpochMilli()
        val assetTime = "2026-05-18T12:00:00Z" // older than build
        val release = createRelease(tagName = "v0.5.1", assetUpdatedAt = assetTime)

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = currentBuildTime,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withOlderRemoteVersion_returnsNone() {
        val release = createRelease(tagName = "v0.4.0")
        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = 1000L,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withInvalidTag_returnsUndetermined() {
        val release = createRelease(tagName = "beta-invalid")
        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = 1000L,
        )

        assertEquals(UpdateType.UNDETERMINED, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withNonApkAsset_doesNotTriggerSameReleasePatch() {
        val currentBuildTime = Instant.parse("2026-05-18T10:00:00Z").toEpochMilli()
        val assetTime = "2026-05-18T12:00:00Z"
        val release = createRelease(
            tagName = "v0.5.1",
            assetUpdatedAt = assetTime,
            assetName = "trindade-notes.txt",
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = currentBuildTime,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withSameVersionAndMatchingDigest_returnsNone() {
        val currentBuildTime = Instant.parse("2026-05-18T10:00:00Z").toEpochMilli()
        val assetTime = "2026-05-18T12:00:00Z" // Newer timestamp on GitHub
        val sha = "1cd5d57485e85d9bc21c2869c4f336ce37fe5af4c652b09a5ec7f860a2f49b43"
        val release = createRelease(
            tagName = "v0.5.1",
            assetUpdatedAt = assetTime,
            assetDigest = "sha256:$sha",
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = currentBuildTime,
            installedSha256 = sha,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withSameVersionAndDifferentDigest_returnsSameReleasePatch() {
        val currentBuildTime = Instant.parse("2026-05-18T10:00:00Z").toEpochMilli()
        val assetTime = "2026-05-18T12:00:00Z"
        val remoteSha = "aaaa5d57485e85d9bc21c2869c4f336ce37fe5af4c652b09a5ec7f860a2f49b43"
        val localSha = "bbbb5d57485e85d9bc21c2869c4f336ce37fe5af4c652b09a5ec7f860a2f49b43"
        val release = createRelease(
            tagName = "v0.5.1",
            assetUpdatedAt = assetTime,
            assetDigest = "sha256:$remoteSha",
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = currentBuildTime,
            installedSha256 = localSha,
        )

        assertEquals(UpdateType.SAME_RELEASE_PATCH, availability.type)
        assertTrue(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withSameVersionAndMatchingSize_returnsNone() {
        val currentBuildTime = Instant.parse("2026-05-18T10:00:00Z").toEpochMilli()
        val assetTime = "2026-05-18T12:00:00Z"
        val size = 10774704L
        val release = createRelease(
            tagName = "v0.5.1",
            assetUpdatedAt = assetTime,
            assetSize = size,
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = currentBuildTime,
            installedSizeBytes = size,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withSameVersionAndDifferentSize_returnsSameReleasePatch() {
        val currentBuildTime = Instant.parse("2026-05-18T10:00:00Z").toEpochMilli()
        val assetTime = "2026-05-18T12:00:00Z"
        val release = createRelease(
            tagName = "v0.5.1",
            assetUpdatedAt = assetTime,
            assetSize = 10774704L,
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.1",
            currentBuildTimeMillis = currentBuildTime,
            installedSizeBytes = 9999999L,
        )

        assertEquals(UpdateType.SAME_RELEASE_PATCH, availability.type)
        assertTrue(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withOlderReleaseThanTheInstalledBuild_returnsNoneWhateverTheAssetLooksLike() {
        // The loop Wilkin hit on the phone: GitHub still says v0.5.3 while the installed build is 0.6.0,
        // and the older release ships a 10.8 MB APK against this build's 11.1 MB. Comparing the asset of
        // an older release with the installed build says "different" for the obvious reason, and reading
        // that difference as a patch promoted the old release into a mandatory update: the dialog came up,
        // the app locked itself into the profile route, and the only thing the operator could do was
        // close it and open it again into the same dialog.
        //
        // An older release is not this build's version to patch. It is older, full stop.
        val currentBuildTime = Instant.parse("2026-05-18T10:00:00Z").toEpochMilli()
        val release = createRelease(
            tagName = "v0.5.3",
            assetUpdatedAt = "2026-06-01T12:00:00Z",
            assetDigest = "sha256:1111111111111111111111111111111111111111111111111111111111111111",
            assetSize = 10774704L,
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.6.0",
            currentBuildTimeMillis = currentBuildTime,
            installedSha256 = "sha256:2222222222222222222222222222222222222222222222222222222222222222",
            installedSizeBytes = 11115300L,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun checkUpdate_withOlderReleaseAndNoSizeToCompare_returnsNone() {
        // The same pair with nothing to compare but the timestamp, which is newer than the build on
        // purpose: an older release must not reach that fallback at all, because a re-uploaded old asset
        // is newer by the clock and older by every measure that matters.
        val release = createRelease(tagName = "v0.5.3", assetUpdatedAt = "2026-06-01T12:00:00Z")

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.6.0",
            currentBuildTimeMillis = Instant.parse("2026-05-18T10:00:00Z").toEpochMilli(),
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }
}
