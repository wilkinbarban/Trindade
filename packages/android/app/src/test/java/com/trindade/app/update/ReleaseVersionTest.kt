package com.trindade.app.update

import com.trindade.app.update.ReleaseVersion.Comparison
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The version comparison, which is the one part of the update check that can be wrong with no phone, no
 * network and no server.
 *
 * The tests are named for the behaviour rather than for the input, because every mistake worth catching
 * here is a mistake of judgement rather than of parsing: comparing text instead of numbers, cutting a
 * version that is not this project's into a shape that is, and reading "the newest release is older than
 * this build" as something to install.
 */
class ReleaseVersionTest {

    @Test
    fun `a higher minor number is newer even though it reads shorter as a string`() {
        // The case the numeric comparison exists for: "0.10.0" sorts *before* "0.9.0" as text, because
        // '1' < '9', so a client that compared strings would call the tenth minor release older than the
        // ninth and offer the operator an older APK.
        assertEquals(
            Comparison.Newer("0.10.0"),
            ReleaseVersion.compare(latestTag = "v0.10.0", currentVersion = "0.9.0"),
        )
    }

    @Test
    fun `a build ahead of the newest release is up to date, and no downgrade is ever suggested`() {
        // The same pair the other way round. Nothing here may come back as "a newer version exists",
        // because the version it would name is older than the one on the phone: an operator running an
        // APK published by hand, or a release GitHub has not caught up with yet.
        assertEquals(
            Comparison.UpToDate,
            ReleaseVersion.compare(latestTag = "v0.9.0", currentVersion = "0.10.0"),
        )
        assertEquals(
            Comparison.UpToDate,
            ReleaseVersion.compare(latestTag = "v0.9.9", currentVersion = "1.0.0"),
        )
    }

    @Test
    fun `the release this build already carries is up to date`() {
        assertEquals(Comparison.UpToDate, ReleaseVersion.compare(latestTag = "v0.3.0", currentVersion = "0.3.0"))
        // Equality is per component and not per string, so the two spellings of one version are equal
        // too -- and neither of them is a newer release.
        assertEquals(Comparison.UpToDate, ReleaseVersion.compare(latestTag = "0.3.0", currentVersion = "0.3.0"))
    }

    @Test
    fun `each component is compared on its own, from the left`() {
        // Not how many numbers differ and not their sum: the leftmost difference decides, which is what
        // makes a major release newer than any minor one.
        assertEquals(Comparison.Newer("0.3.10"), ReleaseVersion.compare("v0.3.10", "0.3.9"))
        assertEquals(Comparison.Newer("0.4.0"), ReleaseVersion.compare("v0.4.0", "0.3.99"))
        assertEquals(Comparison.Newer("1.0.0"), ReleaseVersion.compare("v1.0.0", "0.9.9"))
        assertEquals(Comparison.UpToDate, ReleaseVersion.compare("v0.9.9", "1.0.0"))
    }

    @Test
    fun `the tag's v is the workflow's convention and not part of the version`() {
        // The workflow cuts `vX.Y.Z` tags while the build carries `X.Y.Z` from the same tag, so the
        // comparison has to read both spellings as one version.
        assertEquals(Comparison.Newer("0.4.0"), ReleaseVersion.compare(latestTag = "v0.4.0", currentVersion = "0.3.0"))
        assertEquals(Comparison.Newer("0.4.0"), ReleaseVersion.compare(latestTag = "0.4.0", currentVersion = "0.3.0"))
        assertEquals(Comparison.UpToDate, ReleaseVersion.compare(latestTag = "v0.3.0", currentVersion = "0.3.0"))
        assertEquals(Comparison.UpToDate, ReleaseVersion.compare(latestTag = "0.3.0", currentVersion = "0.3.0"))
    }

    @Test
    fun `the version a newer release is reported with carries no v`() {
        // What this feeds is a sentence drawn directly under the version line, so it has to read the way
        // that line does: `0.3.10`, not `v0.3.10`.
        assertEquals(Comparison.Newer("0.3.10"), ReleaseVersion.compare(latestTag = "v0.3.10", currentVersion = "0.3.9"))
    }

    @Test
    fun `a tag that is not three numbers is not a version this client acts on`() {
        // Each of these could be made to look plausible by filling in what it is missing or cutting off
        // what it carries -- `0.3` as `0.3.0`, `release-3` as `3.0.0`, `v0.3.0-rc1` as `0.3.0` -- and
        // every one of those guesses would end in a sentence telling an operator to install something.
        // None of them is this project's release workflow's output, so none of them is answered.
        for (tag in listOf("0.3", "release-3", "v0.3.0-rc1", "1.2.3.4", "v", "", "v0.3.0 ")) {
            assertEquals("tag '$tag'", Comparison.Undetermined, ReleaseVersion.compare(tag, "0.3.0"))
        }
    }

    @Test
    fun `a build version that is not three numbers leaves the question open rather than closing it`() {
        // The build's own side is held to the same rule, and this is the case where "up to date" would be
        // a claim nothing supports: a version this client cannot read is not evidence that it is current.
        // The three-component shape is what the release workflow writes; `build.gradle.kts` permits more,
        // which is why this is reachable rather than hypothetical.
        for (version in listOf("0.1", "1.2.3.4", "0.1.0-debug", "")) {
            assertEquals(
                "version '$version'",
                Comparison.Undetermined,
                ReleaseVersion.compare(latestTag = "v9.9.9", currentVersion = version),
            )
        }
    }

    @Test
    fun `a component too wide for a number is refused instead of thrown`() {
        // This function has no failure channel: an exception here would leave the screen with no update
        // line at all rather than with the sentence that says the check could not be made.
        assertEquals(
            Comparison.Undetermined,
            ReleaseVersion.compare(latestTag = "v99999999999999999999.0.0", currentVersion = "0.1.0"),
        )
    }
}
