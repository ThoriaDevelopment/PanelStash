package com.panelstash.data

import com.panelstash.data.db.ChapterEntity
import com.panelstash.source.chapterLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A profile is one title, not one source: chapters only one source carries are all kept, and a
 * chapter several sources publish is taken from the best placed of them.
 */
class ChapterMergeTest {

    /** The order the app asks for: MangaDex, then Asura Scans, then the rest by registration. */
    private val rank: (String) -> Int = { id ->
        when (id) {
            "mangadex" -> 0
            "asurascans" -> 1
            "vortex" -> 2
            "nyx" -> 3
            else -> 9
        }
    }

    private fun chapter(
        source: String,
        number: String,
        pages: Int = 10,
        external: String? = null,
    ) = ChapterEntity(
        manhwaId = 1,
        sourceId = source,
        remoteId = "$source-$number",
        numberText = number,
        // Named chapters are placed the way a source's own label places them.
        sortKey = chapterLabel(number)?.sortKey,
        pageCount = pages,
        externalUrl = external,
    )

    @Test
    fun `chapters only one source carries are all kept`() {
        // The report: A has 3-13, B has 1-3 and C has 13-59. Any single source loses some.
        val merged = mergeChapters(
            buildList {
                for (number in 3..13) add(chapter("a", "$number"))
                for (number in 1..3) add(chapter("b", "$number"))
                for (number in 13..59) add(chapter("c", "$number"))
            },
            rank,
        )
        assertEquals((1..59).map { "$it" }, merged.map { it.numberText })
    }

    @Test
    fun `a chapter several sources publish comes from the higher ranked source`() {
        val merged = mergeChapters(
            listOf(
                chapter("nyx", "7"),
                chapter("asurascans", "7"),
                chapter("mangadex", "7"),
            ),
            rank,
        )
        assertEquals(1, merged.size)
        assertEquals("mangadex", merged.single().sourceId)
    }

    @Test
    fun `asura comes before the rest`() {
        val merged = mergeChapters(
            listOf(chapter("vortex", "7"), chapter("asurascans", "7")),
            rank,
        )
        assertEquals("asurascans", merged.single().sourceId)
    }

    @Test
    fun `a readable chapter beats an external-only one`() {
        // MangaDex ranks first, but a licensed entry is a link away from the app, not a chapter.
        val merged = mergeChapters(
            listOf(
                chapter("mangadex", "12", external = "https://example.com/chapter-12"),
                chapter("vortex", "12"),
            ),
            rank,
        )
        assertEquals("vortex", merged.single().sourceId)
    }

    @Test
    fun `one source publishing a number twice keeps the longer upload`() {
        val merged = mergeChapters(
            listOf(chapter("asurascans", "4", pages = 12), chapter("asurascans", "4", pages = 40)),
            rank,
        )
        assertEquals(40, merged.single().pageCount)
    }

    @Test
    fun `unnumbered chapters sort after the numbered ones`() {
        val merged = mergeChapters(
            listOf(chapter("mangadex", "2"), chapter("mangadex", "Note"), chapter("mangadex", "10")),
            rank,
        )
        assertEquals(listOf("2", "10", "Note"), merged.map { it.numberText })
    }

    @Test
    fun `a prologue reads first and a side story last`() {
        val merged = mergeChapters(
            listOf(
                chapter("mangadex", "1"),
                chapter("mangadex", "Side Story"),
                chapter("mangadex", "Prologue"),
                chapter("mangadex", "2"),
            ),
            rank,
        )

        assertEquals(listOf("Prologue", "1", "2", "Side Story"), merged.map { it.numberText })
    }

    @Test
    fun `a pinned source wins the chapters it carries`() {
        // Kayn would rank last; the reader pinned it for this title, so it comes first.
        val pinned: (String) -> Int = { id -> if (id == "kaynscans") -1 else rank(id) }
        val merged = mergeChapters(
            listOf(chapter("mangadex", "7"), chapter("kaynscans", "7")),
            pinned,
        )
        assertEquals("kaynscans", merged.single().sourceId)
    }

    @Test
    fun `chapters the pinned source lacks still come from the others`() {
        val pinned: (String) -> Int = { id -> if (id == "kaynscans") -1 else rank(id) }
        val merged = mergeChapters(
            listOf(chapter("mangadex", "7"), chapter("kaynscans", "8")),
            pinned,
        )
        assertEquals(listOf("mangadex", "kaynscans"), merged.map { it.sourceId })
    }
}
