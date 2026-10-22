package com.trindade.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub release model with tag and assets metadata.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

/**
 * GitHub release asset metadata representing a downloadable file (e.g. APK).
 */
@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("digest") val digest: String? = null,
)
