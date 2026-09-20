package com.trindade.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET

/**
 * The one call this app makes to GitHub: which release is the newest.
 *
 * Hand-written, and for the opposite reason the contract types are generated: GitHub's payload is not
 * this project's schema, so there is nothing to generate it from and nothing that keeps it in step.
 * Only the field this app reads is declared below, and the parser is set to ignore the rest (the shared
 * `Json` sets `ignoreUnknownKeys`), so a field GitHub adds later cannot break a check that never wanted
 * it.
 *
 * `repos/wilkinbarban/Trindade/releases/latest` answers the newest release that is **neither a
 * prerelease nor a draft**, which is that endpoint's own filter. That is why nothing here compares
 * prerelease markers: the check cannot offer a prerelease because GitHub never sends one to this call,
 * and a filter of this client's own would be a second rule to keep in step with the server's.
 *
 * The repository named in the path is the same project fact the `releasesUrl` default in
 * `build.gradle.kts` names, written in the two places that need it -- where the APK is published and
 * where the app asks which release is newest. The two copies cannot be one value: this one is a Retrofit
 * annotation, so it has to be a compile-time constant, and the other is a Gradle property resolved at
 * configuration time, and any mechanism between them would be more machinery than the fact it holds.
 *
 * What holds them together is therefore a test rather than a habit: `GitHubReleaseApiTest` records the path
 * this interface really produced, extracts the repository slug from it, and asserts that the same slug
 * appears in `BuildConfig.RELEASES_URL`. If this project moves, that test fails until both copies move
 * together.
 *
 * The path is relative to the configured base URL, which ends in a slash, so it must not begin with one.
 * Which client that base URL belongs to is the part that matters: see `NetworkModule`, where this
 * interface is bound to a Retrofit built on a client that carries no token.
 */
interface GitHubReleaseApi {

    /**
     * The newest published release.
     *
     * Returns [Response] rather than the body, like every other interface in this app: a 404 (what this
     * endpoint answers while the repository has no releases at all) and a 403 (the unauthenticated rate
     * limit) are ordinary answers to a check the operator never asked for, and a bare return type would
     * turn them into an exception thrown to say "could not check".
     */
    @GET("repos/wilkinbarban/Trindade/releases/latest")
    suspend fun latestRelease(): Response<GitHubRelease>
}

/**
 * GitHub's release payload, cut down to the one field this app reads.
 *
 * `tag_name` and nothing else. `name`, `body`, `assets` and `published_at` are all in the response and
 * none of them is used, so none of them is declared: a field with no reader is a field somebody has to
 * keep correct for nothing, and this response is the one place where mirroring the remote schema would
 * be someone else's work to maintain.
 *
 * Non-null, and deliberately without a default: a payload that arrives without this field is a payload
 * this client cannot read, and letting the decode fail turns that into the same "could not check" an
 * unreachable GitHub produces. The failure is caught where the call is made, never here.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
)
