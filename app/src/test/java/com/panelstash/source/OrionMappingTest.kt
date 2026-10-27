package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrionMappingTest {

    private val json = Network.json

    /**
     * Captured from a live series page on 2026-10-27 and cut down to the fields we read. Astro
     * escapes quotes as `&quot;` inside the attribute and wraps every prop as `[type, value]`.
     */
    private val seriesHtml = """
        <html><body>
        <astro-island uid="d1" component-url="/_vcomics/a.js" component-export="default" renderer-url="/_vcomics/b.js" props="{&quot;post&quot;:[0,{&quot;postTitle&quot;:[0,&quot;#How to Walk on a Broken Earth &quot;],&quot;postContent&quot;:[0,&quot;&lt;p&gt;A shattered world.&lt;/p&gt;&lt;br&gt;&lt;br&gt;A traveler walks.&quot;],&quot;featuredImage&quot;:[0,&quot;https://storage.orion-scans.com/public/upload/series/featured/x/cover.png&quot;],&quot;releaseDate&quot;:[0,&quot;2025-03-01T00:00:00.000Z&quot;],&quot;seriesStatus&quot;:[0,&quot;ONGOING&quot;],&quot;genres&quot;:[1,[[0,{&quot;id&quot;:[0,2],&quot;name&quot;:[0,&quot;Fantasy&quot;]}]]]}]}" ssr client="load" opts="{&quot;name&quot;:&quot;SeriesDescriptionIsland&quot;,&quot;value&quot;:true}"></astro-island>
        <astro-island uid="d2" component-url="/_vcomics/c.js" component-export="default" renderer-url="/_vcomics/b.js" props="{&quot;post&quot;:[0,{&quot;postTitle&quot;:[0,&quot;#How to Walk on a Broken Earth &quot;]}],&quot;initialChap&quot;:[1,[[0,{&quot;number&quot;:[0,5],&quot;slug&quot;:[0,&quot;chapter-5&quot;],&quot;title&quot;:[0,&quot;A locked extra&quot;],&quot;isLocked&quot;:[0,true],&quot;isAccessible&quot;:[0,false],&quot;isPermanentlyLocked&quot;:[0,true]}],[0,{&quot;number&quot;:[0,1],&quot;slug&quot;:[0,&quot;chapter-1&quot;],&quot;title&quot;:[0,null],&quot;isLocked&quot;:[0,false],&quot;isAccessible&quot;:[0,true],&quot;isPermanentlyLocked&quot;:[0,false]}]]],&quot;totalChapterCount&quot;:[0,2]}" ssr client="load" opts="{&quot;name&quot;:&quot;SeriesChaptersPanelIsland&quot;,&quot;value&quot;:true}"></astro-island>
        </body></html>
    """.trimIndent()

    private val readerHtml = """
        <link slot="head-preload" rel="preload" as="image" href="https://storage.orion-scans.com/public/upload/series/demo/abc/page-0002_0001_17-600139.jpg">
        <img class="h-full w-full" src="https://storage.orion-scans.com/public/upload/series/featured/abc/cover.png">
        <img src="https://storage.orion-scans.com/public/upload/series/demo/abc/page-0001_00_17-882712.jpg">
        <img src="https://storage.orion-scans.com/public/upload/series/demo/abc/page-0001_00_17-882712.jpg">
        <img src="https://storage.orion-scans.com/public/upload/series/demo/abc/page-0010_09_17-793762.png">
        <div>page-0001_00_17-882712.jpg</div>
    """.trimIndent()

    private val queryPayload = """
        {
          "posts": [
            {
              "id": 331,
              "slug": "how-to-walk-on-a-broken-earth",
              "postTitle": "#How to Walk on a Broken Earth ",
              "featuredImage": "https://storage.orion-scans.com/public/upload/series/featured/x/cover.png",
              "seriesType": "MANGA",
              "seriesStatus": "ONGOING",
              "genres": [{"id": 2, "name": "Fantasy"}]
            },
            {
              "id": 12,
              "slug": "some-web-novel",
              "postTitle": "Some Web Novel",
              "seriesType": "NOVEL",
              "seriesStatus": "COMPLETED"
            }
          ],
          "totalCount": 325
        }
    """.trimIndent()

    @Test
    fun `series page props carry title, synopsis, cover and year`() {
        val series = parseOrionSeries(seriesHtml, "how-to-walk-on-a-broken-earth")

        assertEquals("#How to Walk on a Broken Earth", series.title)
        assertEquals("A shattered world.\n\nA traveler walks.", series.description)
        assertEquals(
            "https://storage.orion-scans.com/public/upload/series/featured/x/cover.png",
            series.coverUrl,
        )
        assertEquals("ONGOING", series.status)
        assertEquals(2025, series.year)
    }

    @Test
    fun `chapter list is scoped to the series and marks coin-locked chapters unreadable`() {
        val chapters = parseOrionSeries(seriesHtml, "how-to-walk-on-a-broken-earth").chapters

        assertEquals(listOf("5", "1"), chapters.map { it.numberText })
        assertEquals("how-to-walk-on-a-broken-earth|chapter-1", chapters.last().remoteId)
        assertTrue(chapters.first().externalOnly)
        assertFalse(chapters.last().externalOnly)
        assertEquals("A locked extra", chapters.first().title)
        assertNull(chapters.last().title)
        // A chapter only reports its page count once it has been opened.
        assertEquals(0, chapters.last().pageCount)
    }

    @Test
    fun `dedupe drops the unreadable chapters`() {
        val chapters = parseOrionSeries(seriesHtml, "how-to-walk-on-a-broken-earth").chapters

        assertEquals(listOf("1"), dedupeChapters(chapters).map { it.numberText })
    }

    @Test
    fun `reader page images are ordered by page number and de-duplicated`() {
        val images = orionPageImages(readerHtml, "demo")

        assertEquals(
            listOf(
                "https://storage.orion-scans.com/public/upload/series/demo/abc/page-0001_00_17-882712.jpg",
                "https://storage.orion-scans.com/public/upload/series/demo/abc/page-0002_0001_17-600139.jpg",
                "https://storage.orion-scans.com/public/upload/series/demo/abc/page-0010_09_17-793762.png",
            ),
            images.map { it.url },
        )
        assertEquals(listOf(0, 1, 2), images.map { it.index })
        assertEquals(
            "https://storage.orion-scans.com",
            images.first().url.substringBefore("/public/upload/"),
        )
    }

    @Test
    fun `a reader page with no page images yields nothing`() {
        assertTrue(orionPageImages("<div>This chapter is locked.</div>", "demo").isEmpty())
    }

    @Test
    fun `a page without the islands is transient rather than empty`() {
        try {
            parseOrionSeries("<html><body>Just a moment</body></html>", "demo")
            fail("Expected a transient error")
        } catch (expected: SourceError.Transient) {
            // The site answered, but not with a series page: retry rather than trust it.
        }
    }

    @Test
    fun `catalogue payload keys series by slug and drops novels`() {
        val posts = json.decodeFromString<OrionQueryResponse>(queryPayload).posts
        val manhwa = posts.toSourceManhwa()

        assertEquals(1, manhwa.size)
        assertEquals("how-to-walk-on-a-broken-earth", manhwa.single().remoteId)
        assertEquals("#How to Walk on a Broken Earth", manhwa.single().title)
        assertEquals("ONGOING", manhwa.single().status)
        // The synopsis is only on the series page.
        assertNull(manhwa.single().description)
    }

    @Test
    fun `release dates without a year are ignored`() {
        assertNull(yearOf(""))
        assertNull(yearOf("-"))
        assertEquals(2024, yearOf("2024-12-31T23:59:59.000Z"))
    }

    @Test
    fun `attributes are unescaped once`() {
        assertEquals("a \"b\" & <c>", htmlUnescape("a &quot;b&quot; &amp; &lt;c&gt;"))
    }
}
