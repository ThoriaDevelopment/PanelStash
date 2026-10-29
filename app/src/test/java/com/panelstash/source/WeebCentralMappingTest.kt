package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Weeb Central is read from server-rendered markup, so these fixtures are cut from the live pages
 * captured on 2026-10-29: a catalogue row, the detail panel, one chapter row and the reader
 * fragment.
 */
class WeebCentralMappingTest {

    private val cataloguePayload = """
        <article class="bg-base-300 flex gap-4 p-4">
          <section class="w-full lg:w-[25%] xl:w-[20%]">
            <a href="https://weebcentral.com/series/01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling">
              <article><picture>
                <source srcset="https://temp.compsci88.com/cover/normal/01J76XYCPSY3C4BNPBRY8JMCBE.webp" width="400" height="600" type="image/webp">
                <img src="https://temp.compsci88.com/cover/fallback/01J76XYCPSY3C4BNPBRY8JMCBE.jpg" alt="Solo Leveling cover" width="400" height="600">
              </picture></article>
            </a>
          </section>
          <section class="w-full">
            <a href="https://weebcentral.com/series/01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling">
              <div class="text-lg font-semibold flex items-center gap-1">Solo Leveling</div>
            </a>
          </section>
        </article>
        <article class="bg-base-300 flex gap-4 p-4">
          <section><a href="https://weebcentral.com/series/01J76XY7E2VCSR0ZCC21KGXS1K/Eleceed"><article><picture>
            <img src="https://temp.compsci88.com/cover/fallback/01J76XY7E2VCSR0ZCC21KGXS1K.jpg" alt="Eleceed cover" width="400" height="600">
          </picture></article></a></section>
        </article>
    """.trimIndent()

    private val seriesPayload = """
        <html><head>
          <meta property="og:image" content="https://temp.compsci88.com/cover/fallback/01J76XYCPSY3C4BNPBRY8JMCBE.jpg">
        </head><body>
        <h1 class="md:hidden text-2xl font-bold text-center">Solo Leveling</h1>
        <ul class="flex flex-col gap-4">
          <li><strong>Released: </strong><span>2018</span></li>
          <li><strong>Tags(s): </strong>
            <span><a href="https://weebcentral.com/search?included_tag=Action" class="link link-info link-hover">Action</a>,</span>
            <span><a href="https://weebcentral.com/search?included_tag=Adventure" class="link link-info link-hover">Adventure</a>,</span>
          </li>
          <li><strong>Type: </strong><a href="https://weebcentral.com/search?included_type=Manhwa" class="link">Manhwa</a></li>
          <li><strong>Status: </strong><a href="https://weebcentral.com/search?included_status=Complete" class="link">Complete</a></li>
          <li><strong>Description</strong>
            <p class="whitespace-pre-wrap break-words">E-class hunter Jinwoo Sung is the weakest of them all.<br><br>Then a hidden dungeon opens.</p>
          </li>
        </ul>
        </body></html>
    """.trimIndent()

    private val chapterListPayload = """
        <a href="/chapters/01J76XZ666GREP4DQDKEP1YDZG" class="hover:bg-base-300 flex-1 flex items-center p-2">
          <span class="me-2"><img src="/static/images/chapter-badge-official.svg" alt="" width="16" height="16"></span>
          <span class="grow flex items-center gap-2"><span class="">Chapter 200</span></span>
        </a>
        <a href="/chapters/01J76XZ6614Z72ZP8CDK0PV0V3" class="hover:bg-base-300 flex-1 flex items-center p-2">
          <span class="grow flex items-center gap-2"><span class="">Chapter 199.5</span></span>
        </a>
    """.trimIndent()

    private val readerPayload = """
        <img src="https://hot.planeptune.us/manga/Solo-Leveling/0200-001.png" alt="Page 1" width="720" height="4000">
        <img src="https://hot.planeptune.us/manga/Solo-Leveling/0200-002.png" alt="Page 2" width="720" height="4000">
        <img src="https://weebcentral.com/static/images/spinner.svg" alt="">
    """.trimIndent()

    @Test
    fun `catalogue rows are keyed by ulid and take their title from the cover`() {
        val rows = parseWeebCatalogue(cataloguePayload)

        assertEquals(listOf("Solo Leveling", "Eleceed"), rows.map { it.title })
        assertEquals("01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling", rows.first().remoteId)
        assertEquals(
            "https://temp.compsci88.com/cover/fallback/01J76XYCPSY3C4BNPBRY8JMCBE.jpg",
            rows.first().coverUrl,
        )
        // A row carries no synopsis, status or year: those are on the series page only.
        assertNull(rows.first().description)
        assertNull(rows.first().year)
        assertNull(rows.first().status)
    }

    @Test
    fun `the series page carries status, year, genres and a plain synopsis`() {
        val manhwa = parseWeebSeries(seriesPayload, "01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling")

        assertEquals("Solo Leveling", manhwa.title)
        assertEquals("Complete", manhwa.status)
        assertEquals(2018, manhwa.year)
        assertEquals(listOf("Action", "Adventure"), manhwa.genres.map { it.name })
        assertEquals("E-class hunter Jinwoo Sung is the weakest of them all.\n\nThen a hidden dungeon opens.", manhwa.description)
        assertEquals(
            "https://temp.compsci88.com/cover/fallback/01J76XYCPSY3C4BNPBRY8JMCBE.jpg",
            manhwa.coverUrl,
        )
    }

    @Test
    fun `a page without a title is a challenge, not a series`() {
        try {
            parseWeebSeries("<html><body>Just a moment</body></html>", "x/y")
            fail("Expected a transient error")
        } catch (expected: SourceError.Transient) {
            // Retry rather than show an empty profile.
        }
    }

    @Test
    fun `the full chapter list keeps decimal numbers and chapter identity`() {
        val chapters = parseWeebChapters(chapterListPayload, "01J76XYCPSY3C4BNPBRY8JMCBE")

        assertEquals(listOf("200", "199.5"), chapters.map { it.numberText })
        assertEquals(199.5, chapters.last().sortKey!!, 0.0001)
        assertEquals("01J76XZ666GREP4DQDKEP1YDZG", chapters.first().remoteId)
    }

    @Test
    fun `reader images keep their order and drop the site's own icons`() {
        val images = parseWeebPages(readerPayload)

        assertEquals(2, images.size)
        assertEquals("https://hot.planeptune.us/manga/Solo-Leveling/0200-001.png", images.first().url)
        assertEquals(1, images.last().index)
        assertTrue(parseWeebPages("<html></html>").isEmpty())
    }
}
