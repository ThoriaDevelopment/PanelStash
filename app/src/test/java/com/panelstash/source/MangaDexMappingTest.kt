package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaDexMappingTest {

    private val json = Network.json

    /**
     * Captured from the live API: Solo Leveling's title map has no English entry, so the
     * English name only exists in altTitles.
     */
    private val soloLevelingPayload = """
        {"result":"ok","response":"collection","data":[{
          "id":"32d76d19-8a05-4db0-9fc2-e0b0648fe9d0","type":"manga",
          "attributes":{
            "title":{"ko-ro":"Na Honjaman Level-Up"},
            "altTitles":[{"ko":"\uB098 \uD63C\uC790\uB9CC \uB808\uBCA8\uC5C5"},{"en":"Solo Leveling"}],
            "description":{"en":"When a portal opens..."},
            "year":2018,"status":"ongoing","contentRating":"safe"
          },
          "relationships":[
            {"id":"cover-id","type":"cover_art","attributes":{"fileName":"cover-file.png"}}
          ]
        }],"limit":1,"offset":0,"total":1}
    """.trimIndent()

    /** Captured from the live API: chapter hosted on another site, zero pages. */
    private val externalChapterPayload = """
        {"result":"ok","data":[{
          "id":"ad5931f0-863e-412d-992c-7eb28ca2806c","type":"chapter",
          "attributes":{
            "volume":"1","chapter":"0","title":"Only I Level Up","translatedLanguage":"en",
            "externalUrl":"https://www.webnovel.com/comic/15227640605485101",
            "isUnavailable":false,"pages":0
          }
        }],"limit":1,"offset":0,"total":1}
    """.trimIndent()

    @Test
    fun `english title falls back to alt titles`() {
        val dto = json.decodeFromString<MangaListResponse>(soloLevelingPayload).data.single()
        assertEquals("Solo Leveling", dto.englishTitle())
    }

    @Test
    fun `cover url is built from the cover relationship`() {
        val dto = json.decodeFromString<MangaListResponse>(soloLevelingPayload).data.single()
        val manhwa = dto.toSourceManhwa()
        assertEquals(
            "https://uploads.mangadex.org/covers/32d76d19-8a05-4db0-9fc2-e0b0648fe9d0/cover-file.png.256.jpg",
            manhwa.coverUrl,
        )
        assertEquals(2018, manhwa.year)
        assertEquals("ongoing", manhwa.status)
    }

    @Test
    fun `external chapters keep their link and are listed as bookmarks`() {
        val dto = json.decodeFromString<ChapterListResponse>(externalChapterPayload).data.single()
        val chapter = dto.toSourceChapter()!!
        assertTrue(chapter.externalOnly)
        assertEquals(
            "https://www.webnovel.com/comic/15227640605485101",
            chapter.externalUrl,
        )
        // Other sources still hide their locked chapters; MangaDex asks for these on purpose.
        assertTrue(dedupeChapters(listOf(chapter)).isEmpty())
        assertEquals(1, dedupeChapters(listOf(chapter), includeExternal = true).size)
    }

    @Test
    fun `a zero-page chapter with nowhere to go is still dropped`() {
        val payload = """
            {"result":"ok","data":[{
              "id":"empty-1","type":"chapter",
              "attributes":{"chapter":"9","translatedLanguage":"en","pages":0}
            }]}
        """.trimIndent()
        val dto = json.decodeFromString<ChapterListResponse>(payload).data.single()
        assertNull(dto.toSourceChapter())
    }

    @Test
    fun `unavailable chapters are dropped before dedupe`() {
        val payload = """
            {"result":"ok","data":[{
              "id":"unavailable-1","type":"chapter",
              "attributes":{"chapter":"5","translatedLanguage":"en","isUnavailable":true,"pages":20}
            }]}
        """.trimIndent()
        val dto = json.decodeFromString<ChapterListResponse>(payload).data.single()
        assertNull(dto.toSourceChapter())
    }

    @Test
    fun `chapters without a number are dropped`() {
        val payload = """
            {"result":"ok","data":[{
              "id":"no-number","type":"chapter",
              "attributes":{"title":"Extras","translatedLanguage":"en","pages":10}
            }]}
        """.trimIndent()
        val dto = json.decodeFromString<ChapterListResponse>(payload).data.single()
        assertNull(dto.toSourceChapter())
    }
}
