package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Mangapill is plain HTML too. These fixtures are cut from live pages captured on 2026-10-29:
 * two catalogue cards, the detail panel, a chapter row, the genre form and the reader page.
 */
class MangaPillMappingTest {

    private val cataloguePayload = """
        <div class="grid grid-cols-2">
          <div class="w-full">
            <a href="/manga/6372/solo-glitch-player" class="mb-2">
              <div class="mt-3 font-black leading-tight line-clamp-2">Solo Glitch Player</div>
              <div class="line-clamp-2 text-xs text-secondary mt-1">Solo Bug Player</div>
            </a>
            <div class="flex flex-wrap gap-1 mt-1">
              <div class="text-xs leading-5 bg-purple-500 text-black rounded px-1">manga</div>
              <div class="text-xs leading-5 bg-orange-500 text-black rounded px-1">2020</div>
              <div class="text-xs leading-5 bg-green-500 text-black rounded px-1">publishing</div>
            </div>
            <div><a href="/manga/6372/solo-glitch-player" class="relative block">
              <figure class="w-full h-52 overflow-hidden bg-card rounded-md">
                <img data-src="https://cdn.readdetectiveconan.com/file/mangapill/i/6372.jpeg" alt="Solo Glitch Player" class="lazy">
              </figure>
            </a></div>
          </div>
          <div class="w-full">
            <a href="/manga/8136/solo-leveling-novel" class="mb-2">
              <div class="mt-3 font-black leading-tight line-clamp-2">Solo Leveling Novel</div>
            </a>
            <div class="flex flex-wrap gap-1 mt-1">
              <div class="text-xs leading-5 bg-purple-500 text-black rounded px-1">novel</div>
            </div>
          </div>
          <div class="w-full">
            <a href="/manga/9176/futari-solo-camp" class="mb-2">
              <div class="mt-3 font-black leading-tight line-clamp-2">Futari Solo Camp &quot;Camp&quot;</div>
            </a>
            <div class="flex flex-wrap gap-1 mt-1">
              <div class="text-xs leading-5 bg-purple-500 text-black rounded px-1">manga</div>
            </div>
          </div>
        </div>
    """.trimIndent()

    private val detailPayload = """
        <div class="flex flex-col sm:flex-row my-3">
          <div class="text-transparent flex-shrink-0 w-60 h-80 relative rounded bg-card mr-3 mb-3 md:mb-0">
            <img data-src="https://cdn.readdetectiveconan.com/file/mangapill/i/6372.jpeg" alt="Solo Glitch Player" loading="lazy">
          </div>
          <div class="flex flex-col">
            <div class="mb-3"><h1 class="font-bold text-lg md:text-2xl">Solo Glitch Player</h1>
              <div class="text-sm text-secondary">Solo Bug Player</div>
            </div>
            <div class="mb-3"><p class="text-sm text--secondary">Sudden death after all-nighters and ridiculous amounts of work playing a game.</p></div>
          </div>
        </div>
        <div class="bg-card rounded p-3 mb-3">
          <div><label class="text-secondary">Type</label><div>manga</div></div>
          <div><label class="text-secondary">Status</label><div>publishing</div></div>
          <div><label class="text-secondary">Year</label><div>2000</div></div>
        </div>
        <div class="mb-3">
          <label class="text-secondary">Genres</label>
          <a class="text-sm mr-1 text-brand" href="/search?genre=Action">Action</a>
          <a class="text-sm mr-1 text-brand" href="/search?genre=Fantasy">Fantasy</a>
        </div>
        <div id="chapters" class="p-3">
          <a class="border border-border p-1 hover:bg-brand hover:text-white" href="/chapters/6372-10186000/solo-glitch-player-chapter-186" title="Solo Bug Player Chapter 186">Chapter 186</a>
          <a class="border border-border p-1" href="/chapters/6372-10185000/solo-glitch-player-chapter-185" title="Solo Bug Player Chapter 185">Chapter 185</a>
        </div>
    """.trimIndent()

    private val genreFormPayload = """
        <form method="get">
          <label class="text-sm"><input type="checkbox" name="genre" value="Action" /> Action</label>
          <label class="text-sm"><input type="checkbox" name="genre" value="Fantasy" /> Fantasy</label>
          <label class="text-sm"><input type="checkbox" name="genre" value="Action" /> Action</label>
        </form>
    """.trimIndent()

    private val readerPayload = """
        <section class="flex flex-col items-center">
          <img class="js-page" data-src="https://cdn.readdetectiveconan.com/file/mangap/2026/33/6372/10186000/019febad/1.jpeg" alt="Page 1" loading="lazy" width="940" height="1300">
          <img class="js-page" data-src="https://cdn.readdetectiveconan.com/file/mangap/2026/33/6372/10186000/019febad/2.jpeg" alt="Page 2" loading="lazy" width="940" height="1300">
        </section>
    """.trimIndent()

    @Test
    fun `catalogue cards keep their identity and badges, and drop prose`() {
        val rows = parseMangaPillCatalogue(cataloguePayload)

        assertEquals(listOf("Solo Glitch Player", "Futari Solo Camp \"Camp\""), rows.map { it.title })
        assertEquals("6372/solo-glitch-player", rows.first().remoteId)
        assertEquals(2020, rows.first().year)
        assertEquals("publishing", rows.first().status)
        assertEquals(
            "https://cdn.readdetectiveconan.com/file/mangapill/i/6372.jpeg",
            rows.first().coverUrl,
        )
        // The novel has no pages, so it is not a catalogue row the reader could open.
        assertTrue(rows.none { it.title.contains("Novel") })
    }

    @Test
    fun `the detail page carries status, year, genres and the synopsis`() {
        val manhwa = parseMangaPillSeries(detailPayload, "6372/solo-glitch-player")

        assertEquals("Solo Glitch Player", manhwa.title)
        assertEquals("publishing", manhwa.status)
        assertEquals(2000, manhwa.year)
        assertEquals(listOf("Action", "Fantasy"), manhwa.genres.map { it.name })
        assertEquals("Action", manhwa.genres.first().id)
        assertEquals(
            "Sudden death after all-nighters and ridiculous amounts of work playing a game.",
            manhwa.description,
        )
    }

    @Test
    fun `a page without a title is a challenge, not a series`() {
        try {
            parseMangaPillSeries("<html><body>Checking your browser</body></html>", "1/x")
            fail("Expected a transient error")
        } catch (expected: SourceError.Transient) {
            // Retry rather than trust an empty page.
        }
    }

    @Test
    fun `chapter paths stay whole so the reader url can be rebuilt`() {
        val chapters = parseMangaPillChapters(detailPayload)

        assertEquals(listOf("186", "185"), chapters.map { it.numberText })
        assertEquals("6372-10186000/solo-glitch-player-chapter-186", chapters.first().remoteId)
        assertEquals(186.0, chapters.first().sortKey!!, 0.0001)
    }

    @Test
    fun `genres come from the filter form without duplicates`() {
        val genres = parseMangaPillGenres(genreFormPayload)

        assertEquals(listOf("Action", "Fantasy"), genres.map { it.name })
        // The catalogue filters by name, so the name is also the id.
        assertEquals("Fantasy", genres.last().id)
    }

    @Test
    fun `reader images are lazy urls in page order`() {
        val images = parseMangaPillPages(readerPayload)

        assertEquals(2, images.size)
        assertTrue(images.first().url.endsWith("/019febad/1.jpeg"))
        assertEquals(1, images.last().index)
        assertTrue(parseMangaPillPages("<html></html>").isEmpty())
    }

    @Test
    fun `entities in a title are unescaped once`() {
        assertEquals("A & B \"C\"", unescapeHtml("A &amp; B &quot;C&quot;"))
        assertNull(parseMangaPillGenres("").firstOrNull())
    }
}
