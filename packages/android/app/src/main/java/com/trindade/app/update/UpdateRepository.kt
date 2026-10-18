package com.trindade.app.update

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository @Inject constructor(
    private val api: GitHubReleaseApi,
) {
    suspend fun latestRelease(): GitHubRelease? =
        try {
            val response = api.latestRelease()
            if (response.isSuccessful) response.body() else null
        } catch (_: Throwable) {
            null
        }
}
