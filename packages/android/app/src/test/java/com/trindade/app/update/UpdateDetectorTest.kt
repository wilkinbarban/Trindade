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
    ): GitHubRelease {
        val assets = if (assetUpdatedAt != null) {
            listOf(
                GitHubAsset(
                    name = assetName,
                    browserDownloadUrl = "https://github.com/trindade/releases/download/$tagName/$assetName",
                    updatedAt = assetUpdatedAt,
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
}
