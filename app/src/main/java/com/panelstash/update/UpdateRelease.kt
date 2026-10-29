package com.panelstash.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The one endpoint the updater reads: the newest published release, drafts excluded. */
const val RELEASE_API =
    "https://api.github.com/repos/ThoriaDevelopment/PanelStash/releases/latest"

/**
 * Only the fields the updater needs. Everything else GitHub sends is ignored, so a field being
 * added or renamed upstream cannot break the check.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
)

/**
 * The release's installable file. A release can carry extra assets, so the APK is chosen by
 * extension, and a file named after the app wins over any other APK attached to it.
 */
fun GithubRelease.apkAsset(): GithubAsset? =
    assets
        .filter { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() }
        .sortedByDescending { it.name.contains("panelstash", ignoreCase = true) }
        .firstOrNull()

/**
 * "v0.2", "0.2.1" and "v1.0-beta" all reduce to their numeric parts, so versions compare by
 * number rather than by string: 0.10 is newer than 0.9, which a plain text comparison gets wrong.
 */
fun versionParts(text: String): List<Int> =
    text.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .split('.', ' ')
        .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }

/** True only when [candidate] is a later version than [current], never when they are equal. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val candidateParts = versionParts(candidate)
    if (candidateParts.isEmpty()) return false
    val currentParts = versionParts(current)
    for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (candidatePart != currentPart) return candidatePart > currentPart
    }
    return false
}
