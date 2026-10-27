package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NyxMappingTest {

    private val json = Network.json

    /** Captured from /api/query on 2026-10-27, cut down to the fields we read. */
    private val queryPayload = """
        {
          "posts": [
            {
              "id": 899,
              "slug": "first-love-trap",
              "postTitle": "First Love Trap",
              "postContent": "<p>Life in a share house begins here.</p>",
              "featuredImage": "https://media.nyxscans.com/featured/cover.jpg",
              "seriesType": "MANHWA",
              "seriesStatus": "ONGOING",
              "isNovel": false,
              "chapters": [
                {"id": 51941, "number": 1, "title": "", "slug": "chapter-1", "isLocked": false, "isAccessible": true}
              ]
            },
            {
              "id": 866,
              "slug": "about-things-novel",
              "postTitle": "About Things That Haven't Occurred Yet [Novel]",
              "seriesType": "NOVEL",
              "seriesStatus": "COMPLETED",
              "isNovel": true,
              "chapters": []
            }
          ],
          "totalCount": 790
        }
    """.trimIndent()

    @Test
    fun `novels are dropped from the catalogue`() {
        val posts = json.decodeFromString<NyxQueryResponse>(queryPayload).posts
        val manhwa = posts.toSourceManhwa()
        assertEquals(1, manhwa.size)
        assertEquals("First Love Trap", manhwa.single().title)
    }

    @Test
    fun `remote id joins the numeric id and the slug`() {
        val dto = json.decodeFromString<NyxQueryResponse>(queryPayload).posts.first()
        val manhwa = dto.toSourceManhwa()
        assertEquals("899|first-love-trap", manhwa.remoteId)
        assertEquals("https://media.nyxscans.com/featured/cover.jpg", manhwa.coverUrl)
        assertEquals("ONGOING", manhwa.status)
        assertEquals("Life in a share house begins here.", manhwa.description)
    }

    @Test
    fun `chapter remote id is scoped to the series slug`() {
        val dto = NyxChapterDto(id = 51941, slug = "chapter-1", number = 1.0)
        val chapter = dto.toSourceChapter("first-love-trap")!!
        assertEquals("first-love-trap|chapter-1", chapter.remoteId)
        assertEquals("1", chapter.numberText)
        assertFalse(chapter.externalOnly)
        // The page count arrives with the images on first read.
        assertEquals(0, chapter.pageCount)
    }

    @Test
    fun `half chapters format with their decimal`() {
        val chapter = NyxChapterDto(slug = "chapter-10-5", number = 10.5).toSourceChapter("x")!!
        assertEquals("10.5", chapter.numberText)
    }

    @Test
    fun `paid and locked chapters are marked unreadable and dropped`() {
        val paid = NyxChapterDto(slug = "chapter-6", number = 6.0, isPermanentlyLocked = true)
            .toSourceChapter("x")!!
        val gated = NyxChapterDto(slug = "chapter-7", number = 7.0, isLocked = true, isAccessible = false)
            .toSourceChapter("x")!!
        val free = NyxChapterDto(slug = "chapter-8", number = 8.0, isLocked = false)
            .toSourceChapter("x")!!

        assertTrue(paid.externalOnly)
        assertTrue(gated.externalOnly)
        assertFalse(free.externalOnly)
        assertEquals(listOf(free), dedupeChapters(listOf(paid, gated, free)))
    }

    @Test
    fun `chapters without a number are dropped`() {
        assertNull(NyxChapterDto(slug = "extras", number = null).toSourceChapter("x"))
    }

    @Test
    fun `page images carry their dimensions`() {
        val payload = """
            {"chapter":{"slug":"chapter-1","images":[
              {"url":"https://media.nyxscans.com/a/page-0001.webp","width":800,"height":5000,"order":0},
              {"url":"https://media.nyxscans.com/a/page-0002.webp","width":800,"height":1200,"order":1}
            ]}}
        """.trimIndent()
        val images = json.decodeFromString<NyxChapterResponse>(payload).chapter!!.images
        assertEquals(2, images.size)
        assertEquals(800, images.first().width)
        assertEquals(5000, images.first().height)
    }
}
