package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are cut from arenascan.com on 2026-11-10, trimmed to the markup the parsers read.
 */
class ArenaMappingTest {

    private val catalogue = """
        <div class="listupd">
          <div class="bsx"> <a href="https://arenascan.com/manga/eleceed/" title="Eleceed"> <div class="limit"> <div class="ply"></div> <span class="type Manhwa"></span> <img src="https://arenascan.com/wp-content/uploads/2025/08/f071b507-766a-4559-a43e-121c05948d74.jpg" class="ts-post-image wp-post-image attachment-medium size-medium" loading="lazy" title="Eleceed" alt="Eleceed" width="690" height="846"/> </div> <div class="bigor"> <div class="tt"> Eleceed </div> <div class="adds"> <div class="epxs">Chapter 425</div> </div> </div> </a> </div>
          <div class="bsx"> <a href="https://arenascan.com/manga/return-of-the-first-patriarch-the-strongest-reincarnates-into-his-descendant-1000-years-later/" title="Return of the First Patriarch: The Strongest Reincarnates into His Descendant 1000 Years Later"> <div class="limit"> <img src="https://arenascan.com/wp-content/uploads/2025/09/7acf9797-29c8-462e-b7fc-5df890ca4607.webp" class="ts-post-image"/> </div> </a> </div>
          <div class="bsx"> <a href="https://arenascan.com/manga/eleceed/" title="Eleceed"> <div class="limit"> <img src="https://arenascan.com/wp-content/uploads/2025/08/f071b507-766a-4559-a43e-121c05948d74.jpg" class="ts-post-image"/> </div> </a> </div>
        </div>
    """.trimIndent()

    /** Captured from /manga/eleceed/. */
    private val detail = """
        <meta property="og:image" content="https://arenascan.com/wp-content/uploads/2025/08/f071b507-766a-4559-a43e-121c05948d74.jpg" />
        <div class="info-desc bixbox">
          <div id="titledesktop"><div id="titlemove">
            <h1 class="entry-title" itemprop="name">Eleceed</h1>
            <span class="alternative">일렉시드</span>
          </div></div>
          <div class="wd-full"><span class="mgen"><a href="https://arenascan.com/genres/action/" rel="tag">Action</a> <a href="https://arenascan.com/genres/adventure/" rel="tag">Adventure</a> <a href="https://arenascan.com/genres/fantasy/" rel="tag">Fantasy</a> </span></div>
          <h2>Synopsis Eleceed</h2>
          <div class="entry-content entry-content-single" itemprop="description"><p>Read manhwa Eleceed / 일렉시드<br /> Kaiden hides inside the body of a street cat.</p></div>
          <div class="tsinfo bixbox">
            <div class="imptdt"> Status <i>Ongoing</i> </div>
            <div class="imptdt"> Type <a href="https://arenascan.com//?order=title&amp;type=Manhwa">Manhwa</a> </div>
            <div class="imptdt"> Released <i>2023</i> </div>
          </div>
        </div>
    """.trimIndent()

    /** Both chapter URL shapes the site uses, as captured on the Eleceed page. */
    private val chapters = """
        <ul>
          <li data-num="425"> <div class="chbox"> <div class="eph-num"> <a href="https://arenascan.com/eleceed-chapter-425/"> <span class="chapternum">Chapter 425</span> <span class="chapterdate">November 4, 2026</span> </a> </div> </div> </li>
          <li data-num="385"> <div class="chbox"> <div class="eph-num"> <a href="https://arenascan.com/eleceed-385/"> <span class="chapternum">Chapter 385</span> <span class="chapterdate">January 15, 2026</span> </a> </div> </div> </li>
          <li data-num="424"> <div class="chbox"> <div class="eph-num"> <a href="https://arenascan.com/eleceed-chapter-424/"> <span class="chapternum">Chapter 424</span> </a> </div> </div> </li>
        </ul>
    """.trimIndent()

    private val reader = """
        <div class="reader-area">
          <img src="https://arenascan.com/wp-content/uploads/2023/02/arena-logo-1.png">
          <img decoding="async" src="https://cdn.arenascan.com/arena-bucket/320761/425/b650368ec26e.webp" />
          <img decoding="async" src="https://cdn.arenascan.com/arena-bucket/320761/425/6649cd08eb78.webp" />
          <img decoding="async" src="https://cdn.arenascan.com/arena-bucket/320761/425/b650368ec26e.webp" />
          <img decoding="async" src="https://cdn.arenascan.com/arena-bucket/320761/425/38734b90fe05.webp" />
        </div>
    """.trimIndent()

    @Test
    fun `catalogue keeps one row per slug and the title the card states`() {
        val rows = parseArenaCatalogue(catalogue)

        assertEquals(listOf("eleceed", "return-of-the-first-patriarch-the-strongest-reincarnates-into-his-descendant-1000-years-later"), rows.map { it.remoteId })
        assertEquals("Eleceed", rows.first().title)
        assertEquals(
            "https://arenascan.com/wp-content/uploads/2025/08/f071b507-766a-4559-a43e-121c05948d74.jpg",
            rows.first().coverUrl,
        )
        // Detail-only fields stay unknown rather than guessed from the card.
        assertEquals(null, rows.first().description)
    }

    @Test
    fun `only the latest grid is the catalogue, not the slider or the genre tabs`() {
        val page = """
            <div class="listupd popularslider">
              <div class="bsx"> <a href="https://arenascan.com/manga/slider-title/" title="Slider Title"> <div class="limit"></div> </a> </div>
            </div>
            <div class="bixbox"><div class="releases"><h2>Latest Update</h2></div>
              <div class="listupd">
                <div class="bsx"> <a href="https://arenascan.com/manga/latest-title/" title="Latest Title"> <div class="limit"></div> </a> </div>
              </div>
            </div>
            <div class="listupd">
              <div class="bsx"> <a href="https://arenascan.com/manga/tab-title/" title="Tab Title"> <div class="limit"></div> </a> </div>
            </div>
        """.trimIndent()

        val rows = parseArenaCatalogue(arenaLatestSection(page))

        assertEquals(listOf("latest-title"), rows.map { it.remoteId })
    }

    @Test
    fun `detail maps the title, alias, year, status and genres`() {
        val manhwa = parseArenaSeries(detail, "eleceed")

        assertEquals("Eleceed", manhwa.title)
        assertEquals("일렉시드", manhwa.aliases.single())
        assertEquals(2023, manhwa.year)
        assertEquals("Ongoing", manhwa.status)
        assertEquals(listOf("action", "adventure", "fantasy"), manhwa.genres.map { it.id })
        assertEquals(listOf("Action", "Adventure", "Fantasy"), manhwa.genres.map { it.name })
        assertTrue(manhwa.description!!.startsWith("Read manhwa Eleceed"))
        assertEquals(
            "https://arenascan.com/wp-content/uploads/2025/08/f071b507-766a-4559-a43e-121c05948d74.jpg",
            manhwa.coverUrl,
        )
    }

    @Test
    fun `chapters keep the path the site gave them, whatever shape it takes`() {
        val parsed = dedupeChapters(parseArenaChapters(chapters))

        assertEquals(listOf("385", "424", "425"), parsed.map { it.numberText })
        assertEquals("eleceed-385", parsed.first().remoteId)
        assertEquals("eleceed-chapter-425", parsed.last().remoteId)
    }

    @Test
    fun `the first chapter is not lost to its extra row attribute`() {
        // The site marks chapter 1 with `class="first-chapter"`, the only row that carries a
        // second attribute; it used to make that row unmatchable.
        val page = """
            <ul>
              <li data-num="2"> <div class="chbox"> <div class="eph-num"> <a href="https://arenascan.com/eleceed-2/"> <span class="chapternum">Chapter 2</span> </a> </div> </div> </li>
              <li data-num="1" class="first-chapter"> <div class="chbox"> <div class="eph-num"> <a href="https://arenascan.com/eleceed-1/"> <span class="chapternum">Chapter 1</span> </a> </div> </div> </li>
            </ul>
        """.trimIndent()

        val parsed = dedupeChapters(parseArenaChapters(page))

        assertEquals(listOf("1", "2"), parsed.map { it.numberText })
        assertEquals("eleceed-1", parsed.first().remoteId)
    }

    @Test
    fun `a prologue beside the numbered chapters is kept and reads first`() {
        val page = """
            <ul>
              <li data-num="1"> <div class="chbox"> <div class="eph-num"> <a href="https://arenascan.com/eleceed-chapter-1/"> <span class="chapternum">Chapter 1</span> </a> </div> </div> </li>
              <li data-num="0"> <div class="chbox"> <div class="eph-num"> <a href="https://arenascan.com/eleceed-prologue/"> <span class="chapternum">Prologue</span> </a> </div> </div> </li>
            </ul>
        """.trimIndent()

        val parsed = dedupeChapters(parseArenaChapters(page))

        assertEquals(listOf("Prologue", "1"), parsed.map { it.numberText })
        assertEquals("eleceed-prologue", parsed.first().remoteId)
        assertEquals(0.0, parsed.first().sortKey!!, 0.0001)
    }

    @Test
    fun `pages come from the chapter CDN only and keep reading order`() {
        val pages = parseArenaPages(reader)

        assertEquals(3, pages.size)
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertEquals("https://cdn.arenascan.com/arena-bucket/320761/425/38734b90fe05.webp", pages.last().url)
    }

    /** The archive's genre filter, cut from /manga/ on 2026-11-10. */
    private val filterForm = """
        <form action="https://arenascan.com/manga/" class="filters " method="GET">
            <div class="filter dropdown">
                <input class="genre-item " type="checkbox" id="genre-74" name="genre[]" value="74">
                <label for="genre-74">Action</label>
                <input class="genre-item " type="checkbox" id="genre-31" name="genre[]" value="31">
                <label for="genre-31">Action</label>
                <input class="genre-item " type="checkbox" id="genre-5269" name="genre[]" value="5269">
                <label for="genre-5269">Zombies</label>
            </div>
        </form>
    """.trimIndent()

    @Test
    fun `a genre maps to the term its own page lists, not to the duplicate name`() {
        val terms = parseArenaGenreTerms(filterForm)

        assertEquals(2, terms.size)
        // Two terms are filed as Action. The plain slug belongs to the older one, which is the
        // term /genres/action/ lists, and that is the lower id.
        assertEquals(31, terms["Action"])
        assertEquals(5269, terms["Zombies"])
    }

    @Test
    fun `a detail page with no title is treated as unavailable`() {
        val error = runCatching { parseArenaSeries("<html><body>Just a moment...</body></html>", "x") }.exceptionOrNull()
        assertTrue(error is SourceError.Transient)
    }
}
