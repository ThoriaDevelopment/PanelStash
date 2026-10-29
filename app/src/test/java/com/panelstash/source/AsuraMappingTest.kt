package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsuraMappingTest {

    private val json = Network.json

    /** Captured from /api/series on 2026-10-28 and cut down to the fields we read. */
    private val cataloguePayload = """
        {
          "data": [
            {
              "id": 6103,
              "slug": "blade-of-the-discarded",
              "title": "Blade of the Discarded",
              "description": "<p>In a murim where war has become a thing of the past.</p><p>Now they rise.</p>",
              "cover": "https://cdn.asurascans.com/asura-images/covers/blade-of-the-discarded.590181.webp",
              "status": "ongoing",
              "type": "manhwa",
              "release_year": 2026,
              "chapter_count": 6,
              "genres": [{"id": 1, "name": "Action", "slug": "action"}]
            },
            {
              "id": 12,
              "slug": "a-web-novel",
              "title": "A Web Novel",
              "type": "web_novel",
              "chapter_count": 3
            },
            {
              "id": 13,
              "slug": "announced-but-empty",
              "title": "Announced But Empty",
              "type": "manhwa",
              "chapter_count": 0
            }
          ],
          "meta": {"total": 2, "page": 1, "limit": 30, "total_pages": 1}
        }
    """.trimIndent()

    private val detailPayload = """
        {"recommended_series":[],"series":{
          "id":6103,"slug":"blade-of-the-discarded","title":"Blade of the Discarded",
          "description":"<p>A murim story.</p>",
          "cover":"https://cdn.asurascans.com/asura-images/covers/blade-of-the-discarded.590181.webp",
          "status":"ongoing","type":"manhwa","chapter_count":6
        }}
    """.trimIndent()

    private val chaptersPayload = """
        {"data":[
          {"id":262555,"series_id":6103,"number":6,"slug":"chapter-6","page_count":40,"is_premium":false},
          {"id":262554,"series_id":6103,"number":5.5,"slug":"chapter-5-5","page_count":20,"is_premium":true}
        ]}
    """.trimIndent()

    private val pagePayload = """
        {"data":{"access_gate":"","is_locked":false,"chapter":{"pages":[
          {"url":"https://cdn.asurascans.com/a/1.webp","width":900,"height":16000},
          {"url":"https://cdn.asurascans.com/a/2.webp","width":1532,"height":1024}
        ]}}}
    """.trimIndent()

    @Test
    fun `catalogue drops prose and maps the rest`() {
        val rows = json.decodeFromString<AsuraSeriesResponse>(cataloguePayload).data.toSourceManhwa()

        assertEquals(1, rows.size)
        val manhwa = rows.single()
        assertEquals("blade-of-the-discarded", manhwa.remoteId)
        assertEquals("Blade of the Discarded", manhwa.title)
        assertEquals("In a murim where war has become a thing of the past.\nNow they rise.", manhwa.description)
        assertEquals("https://cdn.asurascans.com/asura-images/covers/blade-of-the-discarded.590181.webp", manhwa.coverUrl)
        assertEquals(2026, manhwa.year)
        assertEquals("ongoing", manhwa.status)
        // The slug is what the catalogue filter takes, so that is the id the chips carry.
        assertEquals(listOf("action" to "Action"), manhwa.genres.map { it.id to it.name })
        assertEquals(6, manhwa.chapterCount)
    }

    @Test
    fun `series detail maps without a second id scheme`() {
        val series = json.decodeFromString<AsuraSeriesDetailResponse>(detailPayload).series!!
        val manhwa = series.toSourceManhwa()

        assertEquals("blade-of-the-discarded", manhwa.remoteId)
        assertEquals("A murim story.", manhwa.description)
    }

    @Test
    fun `chapters are scoped to the series and premium rows are unreadable`() {
        val chapters = json.decodeFromString<AsuraChaptersResponse>(chaptersPayload).data
            .mapNotNull { it.toSourceChapter("blade-of-the-discarded") }

        assertEquals(listOf("6", "5.5"), chapters.map { it.numberText })
        assertEquals("blade-of-the-discarded|chapter-6", chapters.first().remoteId)
        assertEquals(40, chapters.first().pageCount)
        assertFalse(chapters.first().externalOnly)
        assertTrue(chapters.last().externalOnly)
        assertEquals(listOf("6"), dedupeChapters(chapters).map { it.numberText })
    }

    @Test
    fun `pages keep their dimensions for the reader`() {
        val data = json.decodeFromString<AsuraChapterPageResponse>(pagePayload).data!!
        assertTrue(data.accessGate.isNullOrBlank())
        val pages = data.chapter!!.pages
        assertEquals(2, pages.size)
        assertEquals(16000, pages.first().height)
        assertEquals(1532, pages.last().width)
    }

    @Test
    fun `a gated chapter carries no pages`() {
        val payload = """
            {"data":{"access_gate":"premium","is_locked":true,"chapter":{"pages":[]}}}
        """.trimIndent()
        val data = json.decodeFromString<AsuraChapterPageResponse>(payload).data!!
        assertFalse(data.accessGate.isNullOrBlank())
        assertTrue(data.chapter!!.pages.isEmpty())
    }
}
