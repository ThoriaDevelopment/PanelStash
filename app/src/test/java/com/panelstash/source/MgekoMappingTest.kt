package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are cut from mgeko.cc on 2026-11-10, trimmed to the markup the parsers read.
 */
class MgekoMappingTest {

    private val json = Network.json

    /** The browse route wraps its cards as an HTML fragment inside JSON. */
    private val browseJson = """
        {"results_html":"<article class=\"comic-card\">x</article>","page":1,"num_pages":296}
    """.trimIndent()

    private val cards = """
        <article class="comic-card">
          <div class="comic-card__cover">
            <a href="/manga/xroy-exiled-duke-conquers-the-dungeon/">
              <img src="https://imgsrv5.com/avatar/288x412/media/manga_covers/7b243d9f6f2ec8dd174e42117b7077b271162728.jpg" alt="Exiled Duke Conquers the Dungeon!">
            </a>
          </div>
          <div class="comic-card__content">
            <h3 class="comic-card__title">
              <a href="/manga/xroy-exiled-duke-conquers-the-dungeon/">
                Exiled Duke Conquers the Dungeon!
              </a>
            </h3>
            <p class="comic-card__description">
              Betrayed by his fiancée and cast into the Labyrinth of Death…
            </p>
          </div>
        </article>
        <article class="comic-card">
          <div class="comic-card__content">
            <h3 class="comic-card__title">
              <a href="/manga/xroy-exiled-duke-conquers-the-dungeon/">
                Exiled Duke Conquers the Dungeon!
              </a>
            </h3>
          </div>
        </article>
    """.trimIndent()

    private val search = """
        <ul class="novel-list">
          <li class="novel-item">
            <a href="/manga/7lke-solo-dungeon-i-m-going-to-enjoy-my-weekends-to-the-fullest/" title="Solo Dungeon: I&#x27;m Going to Enjoy My Weekends to the Fullest">
              <div class="cover-wrap"><figure class="novel-cover">
                <img class="lazy" src="/static/img/loading.gif" data-src='/media/manga_covers/2NvWEL.jpg' alt="Solo Dungeon" />
              </figure></div>
              <h4 class="novel-title text2row"> Solo Dungeon: I&#x27;m Going to Enjoy My Weekends to the Fullest </h4>
            </a>
          </li>
        </ul>
    """.trimIndent()

    /** Captured from /manga/647i-agent-reborn/. */
    private val detail = """
        <meta property="og:image" content="https://www.mgeko.cc/media/manga_covers/ec2368e0ffa5d22232e5651c2f305182ab17e7a1.jpg">
        <div class="novel-info">
          <h1 itemprop="name" class="novel-title text2row"> Agent Reborn </h1>
          <h2 class="alternative-title text1row"> 潜入再起 </h2>
          <div class="stats">
            <span> <strong class="ongoing">Ongoing</strong> <small>Status</small> </span>
          </div>
          <div class="categories">
            <strong>Categories</strong>
            <ul>
              <li><a href="/browse-comics/?genre_included=Action&minchaps=0" title="Action Genre" class="property-item"> Action </a></li>
              <li><a href="/browse-comics/?genre_included= Thriller&minchaps=0" title=" Thriller Genre" class="property-item"> Thriller </a></li>
            </ul>
          </div>
        </div>
        <p class="description"> Agent Reborn is a Manga/Manhwa/Manhua in english language, Action series, english chapters have been translated and you can read them here. The Summary is <br><br> In a near-future Japan devastated by a mysterious new drug called "G-SAP," Public Security Investigator Jin Kurosaki is the only uncorrupted cop. </p>
    """.trimIndent()

    private val allChapters = """
        <ul class="chapter-list" start="1">
          <li data-chapterno="1" data-orderno="617676">
            <a href="/reader/en/9m32-bandit-king-chapter-40-eng-li/" title="Chapter 40">
              <strong class="chapter-title"> 40-eng-li </strong>
            </a>
          </li>
          <li data-chapterno="1" data-orderno="617675">
            <a href="/reader/en/9m32-bandit-king-chapter-39-5-eng-li/">
              <strong class="chapter-title"> 39-5-eng-li </strong>
            </a>
          </li>
          <li data-chapterno="1">
            <a href="/manga/9m32-bandit-king/">Display Info</a>
          </li>
        </ul>
    """.trimIndent()

    private val reader = """
        <div class="mgr-reader">
          <img class="mgr-page" src="https://imgsrv5.com/sv2/comic/647i-agent-reborn/chapter-14/0.jpg" loading="eager" alt="Agent Reborn chapter 14-eng-li, page 1">
          <img class="mgr-page" src="https://imgsrv5.com/sv2/comic/647i-agent-reborn/chapter-14/1.jpg" loading="eager" alt="Agent Reborn chapter 14-eng-li, page 2">
          <img class="mgr-page" width="968" height="540" loading="lazy" alt="Mgeko credits" src="https://imgsrv5.com/credits-mgeko.png">
          <img class="mgr-page" src="https://imgsrv5.com/sv2/comic/647i-agent-reborn/chapter-14/2.jpg" loading="eager" alt="Agent Reborn chapter 14-eng-li, page 3">
        </div>
    """.trimIndent()

    /** The reader layout live on 2026-11-10, after the image files moved to a new CDN path. */
    private val readerFastCdn = """
        <div class="mgr-reader">
          <img class="mgr-page" src="https://imgsrv5.com/mg1/fastcdn/cdn_mangaraw/starting-as-the-god-of-wealth-taming-billions-of-divine-pets/chapter-1/0.jpg" loading="eager" alt="Starting As The God Of Wealth Chapter 1 Page 1">
          <img class="mgr-page" src="https://imgsrv5.com/mg1/fastcdn/cdn_mangaraw/starting-as-the-god-of-wealth-taming-billions-of-divine-pets/chapter-1/1.jpg" loading="eager" alt="Starting As The God Of Wealth Chapter 1 Page 2">
          <img class="mgr-page" width="968" height="540" loading="lazy" alt="Mgeko credits" src="https://imgsrv5.com/credits-mgeko.png">
          <img class="mgr-page" src="https://imgsrv5.com/mg1/fastcdn/cdn_mangaraw/starting-as-the-god-of-wealth-taming-billions-of-divine-pets/chapter-1/2.jpg" loading="eager" alt="Starting As The God Of Wealth Chapter 1 Page 3">
        </div>
    """.trimIndent()

    private val searchForm = """
        <select name="status"><option value="Ongoing">Ongoing</option></select>
        <input name="options[]" type="checkbox" value="Action">
        <input name="options[]" type="checkbox" value="Manhwa">
        <input name="options[]" type="checkbox" value="Manga">
        <input name="options[]" type="checkbox" value="Thriller">
    """.trimIndent()

    @Test
    fun `browse cards map once and keep the row cover`() {
        val page = json.decodeFromString<MgekoBrowseResponse>(browseJson)
        assertEquals(296, page.numPages)
        val rows = parseMgekoCards(cards)

        assertEquals(1, rows.size)
        val manhwa = rows.single()
        assertEquals("xroy-exiled-duke-conquers-the-dungeon", manhwa.remoteId)
        assertEquals("Exiled Duke Conquers the Dungeon!", manhwa.title)
        assertEquals(
            "https://imgsrv5.com/avatar/288x412/media/manga_covers/7b243d9f6f2ec8dd174e42117b7077b271162728.jpg",
            manhwa.coverUrl,
        )
        // A truncated teaser is not a synopsis; the detail page supplies it.
        assertEquals(null, manhwa.description)
    }

    @Test
    fun `search cards decode entities and resolve lazy covers against the site`() {
        val rows = parseMgekoSearch(search)

        assertEquals(1, rows.size)
        assertEquals("Solo Dungeon: I'm Going to Enjoy My Weekends to the Fullest", rows.single().title)
        assertEquals("https://www.mgeko.cc/media/manga_covers/2NvWEL.jpg", rows.single().coverUrl)
    }

    @Test
    fun `detail maps the alias, status, genres and only the synopsis`() {
        val manhwa = parseMgekoSeries(detail, "647i-agent-reborn")

        assertEquals("Agent Reborn", manhwa.title)
        assertEquals(listOf("潜入再起"), manhwa.aliases)
        assertEquals("Ongoing", manhwa.status)
        assertEquals(listOf("Action", "Thriller"), manhwa.genres.map { it.name })
        val description = manhwa.description!!
        assertTrue(description.startsWith("In a near-future Japan"))
        assertFalse(description.contains("The Summary is"))
    }

    @Test
    fun `chapters map both whole and decimal numbers and ignore other links`() {
        val parsed = dedupeChapters(parseMgekoChapters(allChapters))

        assertEquals(listOf("39.5", "40"), parsed.map { it.numberText })
        assertEquals("9m32-bandit-king-chapter-40-eng-li", parsed.last().remoteId)
    }

    @Test
    fun `an unnumbered chapter path is kept instead of skipped`() {
        val payload = """
            <ul class="chapter-list">
              <li><a href="/reader/en/9m32-bandit-king-prologue-eng-li/">Prologue</a></li>
              <li><a href="/reader/en/9m32-bandit-king-chapter-1-eng-li/">Chapter 1</a></li>
            </ul>
        """.trimIndent()

        val parsed = dedupeChapters(parseMgekoChapters(payload))

        assertEquals(listOf("Prologue", "1"), parsed.map { it.numberText })
        assertEquals("9m32-bandit-king-prologue-eng-li", parsed.first().remoteId)
    }

    @Test
    fun `pages keep the chapter folder and drop the credits banner`() {
        val pages = parseMgekoPages(reader)

        assertEquals(3, pages.size)
        assertTrue(pages.all { it.url.startsWith("https://imgsrv5.com/sv2/comic/") })
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
    }

    @Test
    fun `pages do not depend on the CDN folder name`() {
        // The folder moved from /sv2/comic/ to /mg1/fastcdn/cdn_mangaraw/; a chapter is listed
        // either way, so the parser must not filter on a path that the site can rename.
        val pages = parseMgekoPages(readerFastCdn)

        assertEquals(3, pages.size)
        assertTrue(pages.all { it.url.contains("/mg1/fastcdn/cdn_mangaraw/") })
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
    }

    @Test
    fun `genre chips exclude the format checkboxes`() {
        val genres = parseMgekoGenres(searchForm)

        assertEquals(listOf("Action", "Thriller"), genres.map { it.name })
    }
}
