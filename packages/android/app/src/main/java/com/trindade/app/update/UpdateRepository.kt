package com.trindade.app.update

import com.trindade.app.contract.models.LatestReleaseResponse
import com.trindade.app.network.SystemApi
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that asks which build this installation serves.
 *
 * The answer comes from this project's own API ([SystemApi.latestRelease]) rather than from a release host
 * of somebody else's. That is not a detail of plumbing: the question "which binary is current for this
 * deployment" belongs to the backend that serves it, and asking a public host instead answered with
 * whatever was published there last -- which, for this project, was older than what the phones already ran,
 * so the dialog offered a downgrade the installer refuses.
 *
 * Every failure mode collapses to `null`, and it means exactly one thing to the caller: *could not check*.
 * A transport failure, a refusal status and a body that does not decode are the same answer here because
 * the screen has no use for the difference -- none of them is a statement about the operator's session, and
 * none of them may be read as "you are up to date".
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val api: SystemApi,
) {
    suspend fun latestRelease(): LatestReleaseResponse? = try {
        val response = api.latestRelease()
        if (response.isSuccessful) response.body() else null
    } catch (_: IOException) {
        null
    } catch (_: retrofit2.HttpException) {
        // The converter throws this instead of returning a failed response when a non-2xx body cannot be
        // decoded into the declared type, which is what a refusal whose payload is an error object does.
        null
    }
}
