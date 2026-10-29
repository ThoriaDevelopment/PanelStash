package com.panelstash.update

import com.panelstash.source.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseTest {

    /** Trimmed to the fields the updater reads, with the shape GitHub actually returns. */
    private val capturedRelease = """
        {
          "url": "https://api.github.com/repos/ThoriaDevelopment/PanelStash/releases/427345905",
          "tag_name": "v0.2",
          "name": "PanelStash 0.2",
          "body": "Everything that changed since 0.1.",
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/ThoriaDevelopment/PanelStash/releases/tag/v0.2",
          "assets": [
            {
              "name": "checksums.txt",
              "size": 120,
              "browser_download_url": "https://github.com/ThoriaDevelopment/PanelStash/releases/download/v0.2/checksums.txt"
            },
            {
              "name": "PanelStash-v0.2.apk",
              "size": 11639853,
              "content_type": "application/vnd.android.package-archive",
              "browser_download_url": "https://github.com/ThoriaDevelopment/PanelStash/releases/download/v0.2/PanelStash-v0.2.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a release decodes with the fields the updater reads`() {
        val release = Network.json.decodeFromString<GithubRelease>(capturedRelease)

        assertEquals("v0.2", release.tagName)
        assertEquals("PanelStash 0.2", release.name)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
        assertEquals(2, release.assets.size)
    }

    @Test
    fun `the apk is picked over other assets`() {
        val release = Network.json.decodeFromString<GithubRelease>(capturedRelease)
        val asset = release.apkAsset()!!

        assertEquals("PanelStash-v0.2.apk", asset.name)
        assertEquals(11639853L, asset.size)
        assertEquals(
            "https://github.com/ThoriaDevelopment/PanelStash/releases/download/v0.2/PanelStash-v0.2.apk",
            asset.downloadUrl,
        )
    }

    @Test
    fun `an apk named after the app wins over another apk`() {
        val release = GithubRelease(
            tagName = "v0.3",
            assets = listOf(
                GithubAsset("debug.apk", "https://example.com/debug.apk", 10),
                GithubAsset("PanelStash-v0.3.apk", "https://example.com/app.apk", 20),
            ),
        )

        assertEquals("PanelStash-v0.3.apk", release.apkAsset()?.name)
    }

    @Test
    fun `a release with no apk has nothing to install`() {
        val release = GithubRelease(
            tagName = "v0.3",
            assets = listOf(GithubAsset("source.zip", "https://example.com/src.zip", 10)),
        )

        assertNull(release.apkAsset())
    }

    @Test
    fun `an apk without a download url is not usable`() {
        val release = GithubRelease(
            tagName = "v0.3",
            assets = listOf(GithubAsset("PanelStash-v0.3.apk", "", 20)),
        )

        assertNull(release.apkAsset())
    }

    @Test
    fun `the release that is already installed is not an update`() {
        assertFalse(isNewerVersion("v0.2", "0.2"))
        assertFalse(isNewerVersion("0.2", "0.2"))
        assertFalse(isNewerVersion("v0.2.0", "0.2"))
    }

    @Test
    fun `a later version counts as an update`() {
        assertTrue(isNewerVersion("v0.3", "0.2"))
        assertTrue(isNewerVersion("v1.0", "0.9.9"))
        assertTrue(isNewerVersion("0.2.1", "0.2"))
    }

    @Test
    fun `versions compare by number, not by text`() {
        // The reason for parsing: "0.10" sorts before "0.9" as a string.
        assertTrue(isNewerVersion("v0.10", "0.9"))
        assertFalse(isNewerVersion("v0.9", "0.10"))
    }

    @Test
    fun `a suffix does not make a version newer`() {
        assertFalse(isNewerVersion("v0.2-beta", "0.2"))
        assertTrue(isNewerVersion("v0.3-beta", "0.2"))
        assertEquals(listOf(0, 3), versionParts("v0.3-beta"))
    }

    @Test
    fun `text without numbers is never an update`() {
        assertFalse(isNewerVersion("nightly", "0.2"))
        assertTrue(versionParts("nightly").isEmpty())
    }
}
