package com.panelstash.data

import com.panelstash.data.db.ManhwaEntity
import com.panelstash.source.SourceGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rules behind the Browse rails. They are pure, so the ordering the reader sees can be
 * asserted without a network: one card per source per round, and no title twice even when several
 * sites publish it.
 */
class DiscoveryTest {

    private fun manhwa(title: String, id: Long = title.hashCode().toLong()) = ManhwaEntity(
        id = id,
        sourceId = "source",
        remoteId = title,
        title = title,
    )

    /** A source's page, with no chapter count reported — the common case. */
    private fun page(sourceId: String, vararg titles: String): Pair<String, List<RankedManhwa>> =
        sourceId to titles.map { RankedManhwa(manhwa(it)) }

    @Test
    fun `the leaderboard takes one card from each source before taking a second`() {
        val pages = listOf(
            page("a", "a1", "a2", "a3"),
            page("b", "b1", "b2"),
        )

        val rows = interleaveRanked(pages, limit = 5)

        assertEquals(
            listOf("a1", "b1", "a2", "b2", "a3"),
            rows.map { it.second.title },
        )
        assertEquals(listOf("a", "b", "a", "b", "a"), rows.map { it.first })
    }

    @Test
    fun `a title published by two sources keeps its best position`() {
        val pages = listOf(
            page("a", "Solo Leveling", "a2"),
            page("b", "solo-leveling", "b2"),
        )

        val rows = interleaveRanked(pages, limit = 10)

        assertEquals(listOf("Solo Leveling", "a2", "b2"), rows.map { it.second.title })
        assertEquals("a", rows.first().first)
    }

    @Test
    fun `a title published twice takes the copy whose source reports more chapters`() {
        val pages = listOf(
            "mangadex" to listOf(
                RankedManhwa(manhwa("Solo Leveling", id = 1), chapterCount = null),
                RankedManhwa(manhwa("a2")),
            ),
            "asura" to listOf(
                RankedManhwa(manhwa("solo leveling", id = 2), chapterCount = 201),
            ),
        )

        val rows = interleaveRanked(pages, limit = 10)

        // Same slot, now pointing at the complete copy.
        assertEquals(listOf("solo leveling", "a2"), rows.map { it.second.title })
        assertEquals("asura", rows.first().first)
        assertEquals(2L, rows.first().second.id)
    }

    @Test
    fun `an unknown chapter count never displaces a known one`() {
        val pages = listOf(
            "asura" to listOf(RankedManhwa(manhwa("Solo Leveling", id = 1), chapterCount = 201)),
            "mangadex" to listOf(RankedManhwa(manhwa("Solo Leveling", id = 2), chapterCount = null)),
            "kayn" to listOf(RankedManhwa(manhwa("Solo Leveling", id = 3), chapterCount = 12)),
        )

        val rows = interleaveRanked(pages, limit = 10)

        assertEquals(1, rows.size)
        assertEquals(1L, rows.first().second.id)
    }

    @Test
    fun `an empty source does not shorten the rounds of the others`() {
        val pages = listOf(
            "empty" to emptyList<RankedManhwa>(),
            page("b", "b1", "b2"),
        )

        assertEquals(
            listOf("b1", "b2"),
            interleaveRanked(pages, limit = 10).map { it.second.title },
        )
    }

    @Test
    fun `the limit cuts the merged list, not each source`() {
        val pages = listOf(
            page("a", "a1", "a2", "a3"),
            page("b", "b1", "b2", "b3"),
        )

        assertEquals(3, interleaveRanked(pages, limit = 3).size)
        assertTrue(interleaveRanked(emptyList(), limit = 10).isEmpty())
    }

    @Test
    fun `similar drops the title itself, what was read, and a second printing`() {
        val seed = manhwa("Solo Leveling")
        val candidates = listOf(
            manhwa("Solo Leveling", id = 1),
            manhwa("solo-leveling", id = 2),
            manhwa("Eleceed", id = 3),
            manhwa("Eleceed", id = 4),
            manhwa("Nano Machine", id = 5),
            manhwa("Tower of God", id = 6),
        )
        val consumed = setOf(discoveryKey("Nano Machine"))

        val picked = pickSimilar(seed, candidates, consumed, limit = 10)

        assertEquals(listOf("Eleceed", "Tower of God"), picked.map { it.title })
        assertEquals(3L, picked.first().id)
    }

    @Test
    fun `similar respects the limit and survives an empty catalogue`() {
        val seed = manhwa("Solo Leveling")
        val candidates = (1..8).map { manhwa("Title $it", id = it.toLong()) }

        assertEquals(4, pickSimilar(seed, candidates, emptySet(), limit = 4).size)
        assertTrue(pickSimilar(seed, emptyList(), emptySet(), limit = 4).isEmpty())
    }

    @Test
    fun `the same genre from two sites counts as one taste`() {
        val reads = listOf(
            "asura" to listOf(SourceGenre("action", "Action"), SourceGenre("fantasy", "Fantasy")),
            "mangadex" to listOf(SourceGenre("391b0423-uuid", "Action")),
            "vortex" to listOf(SourceGenre("8", "Fantasy")),
        )

        val votes = tallyGenres(reads)

        assertEquals(listOf("Action", "Fantasy"), votes.map { it.name })
        assertEquals(listOf(2, 2), votes.map { it.count })
        // Ids stay with the site that named them: the chip needs an id that site understands.
        assertEquals("asura", votes.first().sourceId)
        assertEquals("action", votes.first().genre.id)
        assertEquals("fantasy", votes.last().genre.id)
    }

    @Test
    fun `genre names match across punctuation and case`() {
        assertEquals(genreKey("Sci-Fi Action"), genreKey(" scifi action "))

        val votes = tallyGenres(
            listOf(
                "a" to listOf(SourceGenre("x", "Sci-Fi Action")),
                "b" to listOf(SourceGenre("y", "scifi action")),
            ),
        )
        assertEquals(1, votes.size)
        assertEquals(2, votes.single().count)
    }

    @Test
    fun `a title votes for at most three genres so a broad series cannot outvote a habit`() {
        val many = (1..8).map { SourceGenre("g$it", "Genre $it") }

        val votes = tallyGenres(listOf("a" to many))

        // Three genres, one vote each: the other five are ignored.
        assertEquals(listOf(1, 1, 1), votes.map { it.count })
        assertEquals(listOf("g1", "g2", "g3"), votes.map { it.genre.id })
    }

    @Test
    fun `an empty reading list votes for nothing`() {
        assertTrue(tallyGenres(emptyList()).isEmpty())
        assertTrue(tallyGenres(listOf("a" to emptyList())).isEmpty())
    }

    @Test
    fun `titles are compared without case or punctuation`() {
        assertEquals("sololeveling", discoveryKey("Solo Leveling"))
        assertEquals(discoveryKey("Solo Leveling!"), discoveryKey("solo  leveling"))
    }
}
