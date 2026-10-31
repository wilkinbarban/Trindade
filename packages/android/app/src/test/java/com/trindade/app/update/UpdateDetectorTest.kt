package com.trindade.app.update

import com.trindade.app.contract.models.LatestReleaseResponse
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether the answer from `/api/app/latest-release` is an update.
 *
 * The payload carries no digest, so the digest rule the GitHub release allowed is gone rather than
 * pretended: the size the API reports and the moment it was published are the two measures this build has,
 * and an answer that carries neither is a version comparison and nothing more.
 */
class UpdateDetectorTest {

    private fun release(
        version: String,
        apkSize: Long? = null,
        publishedAt: String? = null,
        apkUrl: String = "https://trindademasas.duckdns.org/api/app/download-apk",
    ) = LatestReleaseResponse(
        version = version,
        apkUrl = apkUrl,
        apkName = "trindade.apk",
        apkSize = apkSize?.let(::BigDecimal),
        publishedAt = publishedAt,
        webUrl = "https://trindademasas.duckdns.org",
    )

    @Test
    fun `a newer version is an update`() {
        val availability = UpdateDetector.checkUpdate(
            release = release("v0.7.0"),
            currentVersionName = "0.6.3",
            currentBuildTimeMillis = 0L,
        )

        assertEquals(UpdateType.NEW_RELEASE, availability.type)
        assertTrue(availability.isUpdateAvailable)
    }

    @Test
    fun `the tag is read as the version it names`() {
        // The endpoint answers a tag (`v0.7.0`) and the build carries a version (`0.7.0`); the comparison
        // has to cross that on its own instead of the caller trimming a prefix the API might stop sending.
        val availability = UpdateDetector.checkUpdate(
            release = release("v0.7.0"),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = 0L,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun `an older version offers nothing`() {
        // GitHub's newest release was 0.5.3 while the plant ran 0.7.0. An older build is not an update: it
        // is a downgrade the installer refuses, which is the dialog with no way forward.
        val availability = UpdateDetector.checkUpdate(
            release = release("v0.5.3", apkSize = 10_528_616L, publishedAt = "2026-08-01T00:00:00Z"),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = Instant.parse("2026-10-31T00:00:00Z").toEpochMilli(),
            installedSizeBytes = 11_149_040L,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun `a version this build cannot read is not a guess`() {
        val availability = UpdateDetector.checkUpdate(
            release = release("v0.3.0-rc1"),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = 0L,
        )

        assertEquals(UpdateType.UNDETERMINED, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun `the same version with a different size is a patch`() {
        val availability = UpdateDetector.checkUpdate(
            release = release("v0.7.0", apkSize = 11_149_040L),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = 0L,
            installedSizeBytes = 10_900_292L,
        )

        assertEquals(UpdateType.SAME_RELEASE_PATCH, availability.type)
        assertTrue(availability.isUpdateAvailable)
    }

    @Test
    fun `the same version with the same size is up to date`() {
        val availability = UpdateDetector.checkUpdate(
            release = release("v0.7.0", apkSize = 11_149_040L),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = 0L,
            installedSizeBytes = 11_149_040L,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }

    @Test
    fun `without a size the publish moment decides`() {
        val buildTime = Instant.parse("2026-10-31T00:00:00Z").toEpochMilli()

        val newer = UpdateDetector.checkUpdate(
            release = release("v0.7.0", publishedAt = "2026-10-31T06:24:08.000Z"),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = buildTime,
        )
        val same = UpdateDetector.checkUpdate(
            release = release("v0.7.0", publishedAt = "2026-10-30T06:24:08.000Z"),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = buildTime,
        )

        assertEquals(UpdateType.SAME_RELEASE_PATCH, newer.type)
        assertEquals(UpdateType.NONE, same.type)
    }

    @Test
    fun `a size of zero is not a size`() {
        // The API answers the size it read from disk, and a file it could not measure is not a file of zero
        // bytes: comparing against zero would report every build as a patch forever.
        val availability = UpdateDetector.checkUpdate(
            release = release("v0.7.0", apkSize = 0L, publishedAt = "2026-10-30T00:00:00Z"),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = Instant.parse("2026-10-31T00:00:00Z").toEpochMilli(),
            installedSizeBytes = 11_149_040L,
        )

        assertEquals(UpdateType.NONE, availability.type)
    }

    @Test
    fun `an answer with no size and no moment is the version comparison alone`() {
        val availability = UpdateDetector.checkUpdate(
            release = release("v0.7.0"),
            currentVersionName = "0.7.0",
            currentBuildTimeMillis = 0L,
            installedSizeBytes = 11_149_040L,
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertNull(availability.release.publishedAt)
    }
}
