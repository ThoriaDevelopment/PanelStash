package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payloads captured from api.luacomic.org on 2026-11-10 and cut to the fields the app reads.
 */
class LuaComicMappingTest {

    private val json = Network.json

    private val catalogue = """
        {
          "meta": {"total": 694, "per_page": 5, "current_page": 1, "last_page": 139},
          "data": [
            {
              "id": 77,
              "title": "I Need to Raise My Sister Properly",
              "description": "Transported into a novel as the villain's half-sister, she vows to change their tragic fate.",
              "alternative_names": "언니는 여동생을 바르게 키워야 합니다",
              "series_type": "Comic",
              "series_slug": "i-need-to-raise-my-sister-properly",
              "thumbnail": "https://media.luacomic.org/file/V4IKlhs/oysh4jdz78cza3mqd08i6pih.webp",
              "status": "Ongoing",
              "release_year": 2023,
              "meta": {"metadata": {}, "chapters_count": "171"}
            },
            {
              "id": 78,
              "title": "",
              "series_slug": "",
              "series_type": "Comic"
            }
          ]
        }
    """.trimIndent()

    /** Captured from /series/the-scarred-young-masters-maid. */
    private val detail = """
        {
          "id": 799,
          "title": "The Scarred Young Master’s Maid",
          "series_slug": "the-scarred-young-masters-maid",
          "thumbnail": "https://media.luacomic.org/file/V4IKlhs/o8cwhs67oyej4tk2fdfx9p4u.webp",
          "description": "A fearless war hero known for his bravery has one unexpected weakness: women!",
          "series_type": "Comic",
          "tags": [],
          "rating": 5,
          "status": "Ongoing",
          "alternative_names": "도련님은 하녀에게 입을 맞추고 싶어서",
          "release_year": 2025
        }
    """.trimIndent()

    private val chapters = """
        {
          "meta": {"total": 15, "per_page": 30, "current_page": 1, "last_page": 1},
          "data": [
            {"chapter_name": "Chapter 15", "chapter_title": null, "chapter_slug": "chapter-15", "price": 10},
            {"chapter_name": "Chapter 10", "chapter_title": "The Escape", "chapter_slug": "chapter-10", "price": 0},
            {"chapter_name": null, "chapter_title": null, "chapter_slug": "chapter-9", "price": 0}
          ]
        }
    """.trimIndent()

    /** The reader page lists its pages as preloaded images; the cover and icon are not pages. */
    private val reader = """
        <link rel="preload" as="image" href="https://media.luacomic.org/file/V4IKlhs/uploads/series/the-scarred-young-masters-maid/e6bceed6-7d38-40bf-b001-2360b4d9358f/001.webp.jpg"/>
        <link rel="preload" as="image" href="https://media.luacomic.org/file/V4IKlhs/uploads/series/the-scarred-young-masters-maid/e6bceed6-7d38-40bf-b001-2360b4d9358f/002.webp.jpg"/>
        <link rel="icon" href="/icon.png"/>
        <link rel="preload" as="image" href="https://media.luacomic.org/file/V4IKlhs/o8cwhs67oyej4tk2fdfx9p4u.webp"/>
        <link rel="preload" as="image" href="https://media.luacomic.org/file/V4IKlhs/uploads/series/the-scarred-young-masters-maid/e6bceed6-7d38-40bf-b001-2360b4d9358f/003.webp.jpg"/>
        <img class="lazy" src="" data-src="https://media.luacomic.org/file/V4IKlhs/uploads/series/the-scarred-young-masters-maid/e6bceed6-7d38-40bf-b001-2360b4d9358f/004.webp.jpg"/>
        <img class="lazy" src="" data-src="https://media.luacomic.org/file/V4IKlhs/uploads/series/the-scarred-young-masters-maid/e6bceed6-7d38-40bf-b001-2360b4d9358f/005.webp.jpg"/>
    """.trimIndent()

    @Test
    fun `catalogue rows carry the chapter tally and the published aliases`() {
        val rows = json.decodeFromString<LuaQueryResponse>(catalogue).data.mapNotNull { it.toSourceManhwa() }

        assertEquals(1, rows.size)
        val manhwa = rows.single()
        assertEquals("i-need-to-raise-my-sister-properly", manhwa.remoteId)
        assertEquals(171, manhwa.chapterCount)
        assertEquals(2023, manhwa.year)
        assertEquals("Ongoing", manhwa.status)
        assertEquals(listOf("언니는 여동생을 바르게 키워야 합니다"), manhwa.aliases)
    }

    @Test
    fun `detail maps without widening the remote id`() {
        val manhwa = json.decodeFromString<LuaSeriesDetailDto>(detail).toSourceManhwa()

        assertEquals("the-scarred-young-masters-maid", manhwa.remoteId)
        assertEquals("The Scarred Young Master’s Maid", manhwa.title)
        assertEquals(2025, manhwa.year)
        assertEquals(listOf("도련님은 하녀에게 입을 맞추고 싶어서"), manhwa.aliases)
    }

    @Test
    fun `a priced chapter is listed as unreadable`() {
        val parsed = json.decodeFromString<LuaChapterQueryResponse>(chapters).data
            .mapNotNull { it.toSourceChapter("the-scarred-young-masters-maid") }

        assertEquals(3, parsed.size)
        assertTrue(parsed.first().externalOnly)
        assertFalse(parsed[1].externalOnly)
        assertEquals("the-scarred-young-masters-maid|chapter-10", parsed[1].remoteId)
        // The title is optional; a chapter with only its number still maps.
        assertEquals("9", parsed.last().numberText)
        assertEquals("The Escape", parsed[1].title)
    }

    @Test
    fun `a chapter named instead of numbered keeps its name`() {
        val payload = """
            {"data": [
              {"chapter_name": "Prologue", "chapter_slug": "prologue", "price": 0},
              {"chapter_name": "Chapter 1", "chapter_slug": "chapter-1", "price": 0}
            ]}
        """.trimIndent()
        val parsed = json.decodeFromString<LuaChapterQueryResponse>(payload).data
            .mapNotNull { it.toSourceChapter("the-scarred-young-masters-maid") }

        assertEquals(listOf("Prologue", "1"), dedupeChapters(parsed).map { it.numberText })
    }

    @Test
    fun `chapter ids round-trip through the reader address`() {
        assertEquals(
            "the-scarred-young-masters-maid" to "chapter-10",
            parseLuaChapterId("the-scarred-young-masters-maid|chapter-10"),
        )
        assertNull(parseLuaChapterId("chapter-10"))
        assertNull(parseLuaChapterId("series|"))
    }

    @Test
    fun `pages come from the whole chapter, not just its preloads, cover excluded`() {
        val pages = parseLuaPages(reader, "the-scarred-young-masters-maid")

        assertEquals(5, pages.size)
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.index })
        assertTrue(pages.last().url.endsWith("/005.webp.jpg"))
        assertTrue(pages.none { it.url.contains("o8cwhs67oyej4tk2fdfx9p4u") })
    }

    @Test
    fun `a search term is percent-encoded for the query route`() {
        assertEquals("solo%20leveling%3A%20ragnarok", encodeQuery("solo leveling: ragnarok"))
    }
}
