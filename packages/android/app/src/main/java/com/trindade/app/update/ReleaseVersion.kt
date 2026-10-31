package com.trindade.app.update

/**
 * The comparison between the version the API reports and the version this build carries.
 *
 * Pure and in a file of its own, because it is the one part of the update check that can be wrong with
 * no phone, no network and no server: `0.10.0` is newer than `0.9.0` while as text it sorts before it,
 * so the rule is worth being able to exercise on its own rather than through a screen.
 *
 * **The strictness is deliberate rather than defensive.** A release of this project always carries a
 * `vX.Y.Z` tag and the build's own versionName is that same tag without the `v` -- the release workflow
 * derives the version from the tag and refuses a tag that is not of that shape. So a string that is not
 * exactly `major.minor.patch`, optionally prefixed by `v`, did not come out of this project's release
 * process: it is somebody else's tag, or a mistake. There is nothing sensible to do with one -- reading
 * `0.3` as `0.3.0`, or `v0.3.0-rc1` as `0.3.0`, invents the parts that are missing and then acts on the
 * invention by telling an operator to install something. [Comparison.Undetermined] is the answer that says
 * so instead.
 *
 * One consequence is accepted with that rule rather than hidden: the release guard in
 * `build.gradle.kts` permits a versionName of any digits-and-dots shape, so a build carrying `1.2.3.4`
 * compares as [Comparison.Undetermined] rather than as newer or older. That is the same refusal to guess,
 * one level up: nothing is invented here, and the day the project wants a four-component versionName
 * refused outright, that guard is where the rule belongs -- not in a comparison that runs on a phone.
 */
object ReleaseVersion {

    /**
     * What comparing the newest release with this build produces.
     *
     * Four answers rather than a boolean, because two of them fit neither truth value: "a newer version
     * exists" and "this build is current" are both claims about the two versions, and a string that is
     * not a version supports neither. [Older] and [SameVersion] are separate for the same kind of reason,
     * one level down: both mean "there is nothing to install", but they are different facts about the two
     * versions, and the phone acts differently on each -- only the same version invites the detector to
     * compare the release's APK against the installed one. Folding them into one answer is what turned an
     * older release whose asset differed in size into a mandatory update of a newer build.
     */
    sealed interface Comparison {

        /**
         * A newer release exists, named [version].
         *
         * The version is written without the tag's leading `v`, the way this app writes its own version
         * everywhere else: the sentence this feeds is drawn directly under the version line, and the
         * operator is comparing the two.
         */
        data class Newer(val version: String) : Comparison

        /** The newest release carries exactly this build's version. */
        data object SameVersion : Comparison

        /**
         * The newest release is older than this build.
         *
         * Reachable whenever the phone runs an APK published by hand or the API serves an older build, and it
         * is the answer that must never lead anywhere: there is no newer release to install, and the
         * older one is not a patch of this build either.
         */
        data object Older : Comparison

        /** One of the two strings is not `major.minor.patch`, so this client cannot say. */
        data object Undetermined : Comparison
    }

    /**
     * Whether [latestTag] names a release newer than [currentVersion].
     *
     * Compared **numerically, component by component**, and that is the whole reason this is not a
     * string comparison: `0.10.0` is the tenth minor release and `0.9.0` the ninth, while as text
     * `"0.10.0"` sorts before `"0.9.0"` because `'1' < '9'`. A client that got this backwards would
     * offer the operator an older APK as an update, which is the one outcome worse than saying nothing.
     *
     * [Comparison.Older] is kept apart from [Comparison.SameVersion] rather than merged into one
     * "current" answer: a build ahead of what the API serves -- an APK installed by hand, or a deployment
     * lagging its own tag -- has nothing to install either way, but it is not the same version, and
     * saying it is invites a comparison of the two APKs that can only end in a wrong answer.
     *
     * Both sides go through the same shape rule, so an unreadable version on either one is
     * [Comparison.Undetermined] rather than a silent "no update available".
     */
    fun compare(latestTag: String, currentVersion: String): Comparison {
        val latest = parse(latestTag) ?: return Comparison.Undetermined
        val current = parse(currentVersion) ?: return Comparison.Undetermined

        for (component in 0 until COMPONENTS) {
            val difference = latest[component].compareTo(current[component])
            if (difference != 0) {
                return if (difference > 0) Comparison.Newer(normalize(latestTag)) else Comparison.Older
            }
        }

        return Comparison.SameVersion
    }

    /**
     * The three numbers in a version string, or null when it is not exactly `major.minor.patch`.
     *
     * `matchEntire` rather than `find`: the pattern has to cover the whole string, so `1.2.3.4`,
     * `v0.3.0-rc1` and `release-3` are refused whole instead of a three-number run inside them being
     * picked out and acted on.
     *
     * `toIntOrNull` rather than `toInt`, because a component wider than an `Int` is a string that is
     * not a version either, and this function has no failure channel to throw out of: an exception
     * here would leave the screen with no update line at all.
     */
    private fun parse(version: String): List<Int>? {
        val components = VERSION_PATTERN.matchEntire(version)?.groupValues?.drop(1) ?: return null
        return components.map { it.toIntOrNull() ?: return null }
    }

    /** The tag read as the version it names: `v0.10.0` becomes `0.10.0`. */
    private fun normalize(tag: String): String = tag.removePrefix(TAG_PREFIX)

    private const val COMPONENTS = 3
    private const val TAG_PREFIX = "v"

    /** `v` optional, then three dot-separated runs of digits, and nothing else. */
    private val VERSION_PATTERN = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)")
}
