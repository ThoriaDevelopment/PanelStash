package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VortexMappingTest {

    private val json = Network.json

    /** Captured from /api/chapters on 2026-10-28 and cut down to the fields we read. */
    private val payload = """
        {"post":{"id":557,"slug":"became-a-medieval-fantasy-wizard","chapters":[
          {"slug":"chapter-46","number":46,"isPermanentlyLocked":true,"price":100},
          {"slug":"chapter-45","number":45,"isPermanentlyLocked":false,"price":0},
          {"slug":"chapter-44.5","number":44.5,"isPermanentlyLocked":false,"price":100}
        ]}}
    """.trimIndent()

    /** Captured from /api/query on 2026-10-29, cut down to the fields we read. */
    private val cataloguePayload = """
        {"posts":[
          {"id":706,"slug":"the-wizards-notes","postTitle":"The Wizard's Notes","seriesType":"MANHWA",
           "seriesStatus":"ONGOING","isNovel":false,
           "genres":[{"id":1,"name":"Action","color":""},{"id":8,"name":"Fantasy","color":""}],
           "chapters":[{"slug":"chapter-12","number":12}]},
          {"id":707,"slug":"announced-but-empty","postTitle":"Announced But Empty","seriesType":"MANHWA",
           "seriesStatus":"ONGOING","isNovel":false,"genres":[],"chapters":[]},
          {"id":708,"slug":"a-novel","postTitle":"A Novel","seriesType":"NOVEL",
           "seriesStatus":"ONGOING","isNovel":true,"chapters":[{"slug":"chapter-1","number":1}]}
        ],"totalCount":484}
    """.trimIndent()

    @Test
    fun `the catalogue drops empty series and novels and keeps each title's genres`() {
        val manhwa = json.decodeFromString<VortexQueryResponse>(cataloguePayload).posts.toSourceManhwa()

        assertEquals(listOf("The Wizard's Notes"), manhwa.map { it.title })
        assertEquals(listOf("Action", "Fantasy"), manhwa.single().genres.map { it.name })
        assertEquals(listOf("1", "8"), manhwa.single().genres.map { it.id })
    }

    @Test
    fun `chapters keep their series-scoped identity`() {
        val chapters = json.decodeFromString<VortexChaptersResponse>(payload).post!!.chapters
            .mapNotNull { it.toSourceChapter("became-a-medieval-fantasy-wizard") }

        assertEquals(listOf("46", "45", "44.5"), chapters.map { it.numberText })
        assertEquals("became-a-medieval-fantasy-wizard|chapter-45", chapters[1].remoteId)
    }

    @Test
    fun `coin-locked and early-access chapters are never offered`() {
        val chapters = json.decodeFromString<VortexChaptersResponse>(payload).post!!.chapters
            .mapNotNull { it.toSourceChapter("became-a-medieval-fantasy-wizard") }

        assertTrue(chapters[0].externalOnly)
        assertFalse(chapters[1].externalOnly)
        // Permanent false but priced: this is the early-access case that used to open blank.
        assertTrue(chapters[2].externalOnly)
        assertEquals(listOf("45"), dedupeChapters(chapters).map { it.numberText })
    }
}
