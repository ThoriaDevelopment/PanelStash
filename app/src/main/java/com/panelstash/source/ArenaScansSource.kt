package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException

/**
 * Arena Scans, a WordPress site on a Madara-derived theme, served entirely as HTML.
 *
 * Identity: a series is the slug the catalogue files it under, and a chapter keeps the path its
 * reader page lives at (`"eleceed-chapter-425"`), because the site spells that path two ways and
 * only the link itself knows which one a chapter uses.
 *
 * Provenance:
 *
 *   GET /page/{n}/                     newest catalogue, ten posters a page
 *   GET /genres/{slug}/page/{n}/       one genre, the only filter the site honours
 *   GET /?s={query}                    search (one page; the theme answers paging with 404)
 *   GET /manga/{slug}/                 detail, genres and the whole chapter list
 *   GET /{chapterPath}/                reader page, pages as plain image tags
 *
 * The chapter list arrives whole on the detail page, so a 400 chapter run is one request.
 */
class ArenaScansSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "arenascan"

    override val name = "Arena Scans"

    override val description = "Big catalogue. Complete chapter lists and full HTML reader."

    override val capabilities = SourceCapabilities(
        // The homepage is a fixed latest list and takes no sort parameter.
        sorts = setOf(BrowseSort.LATEST),
        genres = true,
        titleGenres = true,
    )

    /** Politeness: a detail page is a few hundred KB of markup. */
    private val limiter = RateLimiter(350)

    @Volatile
    private var cachedGenres: List<SourceGenre>? = null

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val tag = tagId?.takeIf { it.isNotBlank() }
        // The site's own page size, not the caller's: the archive carries 40 rows a page and a
        // genre 10, so offsets are mapped to that grid and the remainder is dropped here.
        val pageSize = if (tag == null) ARCHIVE_PAGE else GENRE_PAGE
        val page = offset / pageSize + 1
        val skip = offset % pageSize
        val path = when {
            tag == null && page == 1 -> "/"
            tag == null -> "/page/$page/"
            page == 1 -> "/genres/$tag/"
            else -> "/genres/$tag/page/$page/"
        }
        val body = request(path)
        val rows = parseArenaCatalogue(if (tag == null) arenaLatestSection(body) else body)
        return rows.drop(skip).take(limit)
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        // Search results are one page on this theme: /page/2/?s= answers 404.
        if (offset > 0) return emptyList()
        val body = request("/") { parameter("s", query.trim()) }
        return parseArenaCatalogue(body).take(limit)
    }

    override suspend fun genres(): List<SourceGenre> {
        cachedGenres?.let { return it }
        // The homepage lists every genre in its own menu; the detail page repeats them per title.
        val genres = parseArenaGenres(request("/"))
        if (genres.isNotEmpty()) cachedGenres = genres
        return genres
    }

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val body = request("/manga/$remoteId/") { html() }
        return parseArenaSeries(body, remoteId)
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val body = request("/manga/$manhwaRemoteId/") { html() }
        return dedupeChapters(parseArenaChapters(body))
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val body = request("/$chapterRemoteId/") { html() }
        val images = parseArenaPages(body)
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = ARENA_CDN, images = images)
    }

    private suspend fun request(
        path: String,
        configure: HttpRequestBuilder.() -> Unit = { html() },
    ): String {
        if (gate.isBlocked()) throw SourceError.Banned()
        limiter.acquire()
        val response = try {
            client.get("$ARENA_BASE$path") {
                configure()
                header("Referer", "$ARENA_BASE/")
            }
        } catch (error: IOException) {
            throw SourceError.Offline(error)
        }

        return when {
            response.status == HttpStatusCode.TooManyRequests -> throw SourceError.RateLimited(null)
            response.status == HttpStatusCode.Forbidden -> {
                gate.block()
                throw SourceError.Banned()
            }

            response.status == HttpStatusCode.NotFound -> throw SourceError.Unavailable()
            response.status.value >= 500 -> throw SourceError.Transient()
            !response.status.isSuccess() -> throw SourceError.Transient()
            else -> response.bodyAsText()
        }
    }

    private fun HttpRequestBuilder.html() {
        header("Accept", "text/html,application/xhtml+xml")
    }

    private companion object {
        /** Rows in the "Latest Update" grid on an archive page. */
        const val ARCHIVE_PAGE = 40

        /** Rows a genre page lists. */
        const val GENRE_PAGE = 10
    }
}

internal const val ARENA_BASE = "https://arenascan.com"
internal const val ARENA_CDN = "https://cdn.arenascan.com"

/** A poster card: the title anchor carries the slug and title, the cover sits in the same card. */
private val ARENA_CARD = Regex(
    """<div class="bsx">\s*<a href="https://arenascan\.com/manga/([^"]+)/" title="([^"]*)"""",
)

private val ARENA_COVER = Regex("""<img src="([^"]+)"""")

/**
 * The front page carries three card blocks: a "Popular Today" slider, the latest grid, and a tab
 * per genre. Only the latest grid is the catalogue, so the other two are cut away before parsing.
 */
internal fun arenaLatestSection(html: String): String {
    val heading = html.indexOf("Latest Update")
    if (heading < 0) return html
    val start = html.indexOf("<div class=\"listupd\"", heading)
    if (start < 0) return html
    val next = html.indexOf("<div class=\"listupd\"", start + 1)
    return if (next > start) html.substring(start, next) else html.substring(start)
}

internal fun parseArenaCatalogue(html: String): List<SourceManhwa> {
    val rows = LinkedHashMap<String, SourceManhwa>()
    for (card in ARENA_CARD.findAll(html)) {
        val slug = card.groupValues[1].trim('/')
        if (slug.isEmpty() || slug in rows) continue
        val title = unescapeHtml(card.groupValues[2]).trim()
        if (title.isEmpty()) continue
        val window = html.substring(card.range.first, minOf(html.length, card.range.first + 2_000))
        rows[slug] = SourceManhwa(
            remoteId = slug,
            title = title,
            // Synopsis, status and year are only on the detail page.
            description = null,
            coverUrl = ARENA_COVER.find(window)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            year = null,
            status = null,
        )
    }
    return rows.values.toList()
}

private val ARENA_TITLE = Regex("""<h1[^>]*class="[^"]*entry-title[^"]*"[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
private val ARENA_ALS = Regex("""<span class="alternative">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
private val ARENA_OG_IMAGE = Regex("""property="og:image"\s+content="([^"]+)"""")
private val ARENA_GENRE_BLOCK = Regex("""<span class="mgen">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
private val ARENA_GENRE = Regex("""href="https://arenascan\.com/genres/([^"/]+)/"[^>]*>([^<]+)</a>""")
private val ARENA_DESCRIPTION = Regex(
    """<div class="entry-content entry-content-single"[^>]*>(.*?)</div>""",
    RegexOption.DOT_MATCHES_ALL,
)
private val ARENA_STATUS = Regex("""Status\s*<i>([^<]*)</i>""")
private val ARENA_YEAR = Regex("""Released\s*<i>(\d{4})</i>""")

internal fun parseArenaSeries(html: String, remoteId: String): SourceManhwa {
    val title = ARENA_TITLE.find(html)?.groupValues?.get(1)?.let(::stripHtml)?.trim()
    // A page without a title is a challenge or a redirect, not a series.
    if (title.isNullOrBlank()) throw SourceError.Transient()
    val genres = ARENA_GENRE_BLOCK.find(html)?.groupValues?.get(1)
        ?.let { block ->
            ARENA_GENRE.findAll(block)
                .map { SourceGenre(id = it.groupValues[1], name = unescapeHtml(it.groupValues[2]).trim()) }
                .distinctBy { it.id }
                .toList()
        }
        .orEmpty()
    val aliases = ARENA_ALS.find(html)?.groupValues?.get(1)
        ?.let(::stripHtml)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::listOf)
        .orEmpty()
    return SourceManhwa(
        remoteId = remoteId,
        title = title,
        description = ARENA_DESCRIPTION.find(html)?.groupValues?.get(1)
            ?.let(::stripHtml)
            ?.takeIf { it.isNotBlank() },
        coverUrl = ARENA_OG_IMAGE.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
        year = ARENA_YEAR.find(html)?.groupValues?.get(1)?.toIntOrNull(),
        status = ARENA_STATUS.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
        genres = genres,
        aliases = aliases,
    )
}

/**
 * One row: a numbered list item around the chapter link and its label. The site writes newer
 * chapters as `{slug}-chapter-{n}` and older ones as `{slug}-{n}`, so the link is kept whole and
 * the number is read from the label rather than reconstructed.
 */
private val ARENA_CHAPTER = Regex(
    """<li data-num="[^"]*">\s*<div class="chbox">\s*<div class="eph-num">\s*<a href="https://arenascan\.com/([^"]+)">\s*<span class="chapternum">\s*Chapter\s*([0-9][0-9.]*)""",
    RegexOption.DOT_MATCHES_ALL,
)

internal fun parseArenaChapters(html: String): List<SourceChapter> {
    val chapters = mutableListOf<SourceChapter>()
    for (row in ARENA_CHAPTER.findAll(html)) {
        val path = row.groupValues[1].trim('/')
        val number = row.groupValues[2]
        if (path.isEmpty()) continue
        chapters += SourceChapter(
            remoteId = path,
            numberText = number,
            sortKey = chapterSortKey(number),
            title = null,
            language = "en",
            // The count arrives with the images on first read.
            pageCount = 0,
            externalOnly = false,
        )
    }
    if (chapters.isEmpty()) throw SourceError.Transient()
    return chapters
}

/** Pages live on one CDN folder; the logo and other chrome are served from the site itself. */
private val ARENA_PAGE = Regex("""<img[^>]+src="(https://cdn\.arenascan\.com/[^"]+)"""")

internal fun parseArenaPages(html: String): List<PageImage> =
    ARENA_PAGE.findAll(html)
        .map { it.groupValues[1] }
        .distinct()
        .mapIndexed { index, url -> PageImage(index = index, url = url) }
        .toList()

internal fun parseArenaGenres(html: String): List<SourceGenre> =
    ARENA_GENRE.findAll(html)
        .map { SourceGenre(id = it.groupValues[1], name = unescapeHtml(it.groupValues[2]).trim()) }
        .filter { it.name.isNotEmpty() }
        .distinctBy { it.id }
        .sortedBy { it.name }
        .toList()
