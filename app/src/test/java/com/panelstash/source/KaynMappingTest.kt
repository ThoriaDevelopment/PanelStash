package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KaynMappingTest {

    private val json = Network.json

    /**
     * Captured from a live series page on 2026-10-27 and cut down to the fields we read. Next.js
     * pushes the payload in JavaScript string literals, and the site encodes it twice: one copy of
     * the series sits in the stream as plain JSON, while the copy that carries the chapter list is
     * wrapped in a second layer of escaping.
     */
    private val seriesHtml = """
        <html><body>
        <script>self.__next_f.push([1,"1:\"${'$'}Sreact.fragment\"\n3:I[41165,[\"90401\",\"chunk.js\"],\"IndexPageSuspense\"]\n"])</script>
        <script>self.__next_f.push([1,"a:[\"${'$'}\",\"${'$'}L7d\",null,{\"series\":{\"id\":\"kayn-s-31\",\"title\":\"The Supreme Demon Swordmaster\",\"slug\":\"the-supreme-demon-swordmaster\",\"urlSlug\":\"the-supreme-demon-swordmaster\",\"description\":\"He said \\\"it is over\\\" and left.\",\"coverImage\":\"/uploads/api/a6558a991173b4ba.png\",\"status\":\"ONGOING\",\"type\":\"MANHWA\"}}\n"])</script>
        <script>self.__next_f.push([1,"b:{\\\"series\\\":{\\\"id\\\":\\\"kayn-s-31\\\",\\\"title\\\":\\\"The Supreme Demon Swordmaster\\\",\\\"nsfw\\\":false},\\\"chapters\\\":[{\\\"id\\\":\\\"kayn-c-1135\\\",\\\"number\\\":1,\\\"title\\\":null,\\\"isLocked\\\":false,\\\"coinPrice\\\":0,\\\"hasAccess\\\":true},{\\\"id\\\":\\\"kayn-c-1136\\\",\\\"number\\\":2.5,\\\"title\\\":\\\"Extra\\\",\\\"isLocked\\\":true,\\\"coinPrice\\\":75,\\\"hasAccess\\\":false}]}\n"])</script>
        </body></html>
    """.trimIndent()

    private val cataloguePayload = """
        {
          "data": [
            {
              "id": "kayn-s-31",
              "slug": "the-supreme-demon-swordmaster",
              "urlSlug": "the-supreme-demon-swordmaster",
              "title": "The Supreme Demon Swordmaster",
              "coverImage": "/uploads/api/a6558a991173b4ba.png",
              "status": "ONGOING",
              "type": "MANHWA"
            },
            {
              "id": "kayn-s-337",
              "slug": "the-world-mistook-my-toy",
              "urlSlug": "the-world-mistook-my-toy",
              "title": "The World Mistook My Toy",
              "type": "WEB_NOVEL"
            }
          ],
          "meta": {"total": 436, "hasMore": true}
        }
    """.trimIndent()

    private val chaptersPayload = """
        {
          "chapters": [
            {"id": "kayn-c-1135", "number": 1, "title": null, "isLocked": false, "coinPrice": 0, "hasAccess": true, "pageCount": 12},
            {"id": "kayn-c-1136", "number": 2.5, "title": "Extra", "isLocked": true, "coinPrice": 75, "hasAccess": false, "pageCount": 0}
          ]
        }
    """.trimIndent()

    private val pageUrlsPayload = """
        {
          "pages": [
            {"pageNumber": 2, "kind": "CONTENT", "isRedacted": false, "imageUrl": "/uploads/series/x/0001/p2.webp", "width": 800, "height": 1200},
            {"pageNumber": 1, "kind": "CONTENT", "isRedacted": false, "imageUrl": "/uploads/series/x/0001/p1.webp", "width": 800, "height": 1200},
            {"pageNumber": 3, "kind": "BRAND_OUTRO", "isRedacted": false, "imageUrl": "/uploads/api/brand.webp", "width": 800, "height": 400},
            {"pageNumber": 4, "kind": "CONTENT", "isRedacted": true, "imageUrl": ""}
          ]
        }
    """.trimIndent()

    @Test
    fun `series payload supplies title, synopsis, cover and status`() {
        val series = parseKaynSeries(seriesHtml, "kayn-s-31")

        assertEquals("kayn-s-31", series.id)
        assertEquals("The Supreme Demon Swordmaster", series.title)
        assertEquals("He said \"it is over\" and left.", series.description)
        assertEquals("https://kaynscans.com/uploads/api/a6558a991173b4ba.png", series.coverUrl)
        assertEquals("ONGOING", series.status)
    }

    @Test
    fun `payload chapter list marks coin-gated chapters unreadable`() {
        val chapters = parseKaynSeries(seriesHtml, "kayn-s-31").chapters

        assertEquals(listOf("1", "2.5"), chapters.map { it.numberText })
        assertEquals("kayn-c-1135", chapters.first().remoteId)
        assertFalse(chapters.first().externalOnly)
        assertTrue(chapters.last().externalOnly)
        assertEquals("Extra", chapters.last().title)
        assertNull(chapters.first().title)
        // The page count arrives with the page images, on first read.
        assertEquals(0, chapters.first().pageCount)
    }

    @Test
    fun `dedupe leaves only what can actually be read`() {
        val chapters = parseKaynSeries(seriesHtml, "kayn-s-31").chapters

        assertEquals(listOf("1"), dedupeChapters(chapters).map { it.numberText })
    }

    @Test
    fun `a page without the payload is transient rather than empty`() {
        try {
            parseKaynSeries("<html><body>Just a moment</body></html>", "kayn-s-31")
            fail("Expected a transient error")
        } catch (expected: SourceError.Transient) {
            // The site answered, but not with a series page: retry rather than trust it.
        }
    }

    @Test
    fun `catalogue rows key a series by id and slug and drop prose`() {
        val rows = json.decodeFromString<KaynSeriesResponse>(cataloguePayload).data.toSourceManhwa()

        assertEquals(1, rows.size)
        assertEquals("kayn-s-31|the-supreme-demon-swordmaster", rows.single().remoteId)
        assertEquals("The Supreme Demon Swordmaster", rows.single().title)
        assertEquals("ONGOING", rows.single().status)
        assertEquals("https://kaynscans.com/uploads/api/a6558a991173b4ba.png", rows.single().coverUrl)
        // The synopsis is only on the series page.
        assertNull(rows.single().description)
        assertNull(rows.single().year)
    }

    @Test
    fun `paged chapter list maps numbers, page counts and lock state`() {
        val chapters = json.decodeFromString<KaynChaptersResponse>(chaptersPayload)
            .chapters.mapNotNull { it.toSourceChapter() }

        assertEquals(listOf("1", "2.5"), chapters.map { it.numberText })
        assertEquals(listOf(1.0, 2.5), chapters.map { it.sortKey })
        assertEquals(12, chapters.first().pageCount)
        assertFalse(chapters.first().externalOnly)
        assertTrue(chapters.last().externalOnly)
    }

    @Test
    fun `reader pages skip the site's own cards and the withheld ones`() {
        val images = json.decodeFromString<KaynPageUrlsResponse>(pageUrlsPayload).pages.toPageImages()

        assertEquals(
            listOf(
                "https://kaynscans.com/uploads/series/x/0001/p1.webp",
                "https://kaynscans.com/uploads/series/x/0001/p2.webp",
            ),
            images.map { it.url },
        )
        assertEquals(listOf(0, 1), images.map { it.index })
        assertEquals(1200, images.first().height)
    }

    @Test
    fun `escapes are undone a layer at a time`() {
        assertEquals("a \"b\" c", unescape("a \\\"b\\\" c"))
        assertEquals("line\nbreak", unescape("line\\nbreak"))
        assertEquals("a&b", unescape("a\\u0026b"))
        // A lone backslash is left alone rather than swallowing the text after it.
        assertEquals("end\\", unescape("end\\"))
    }
}
