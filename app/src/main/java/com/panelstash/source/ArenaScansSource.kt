package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Collections

/**
 * Arena Scans, a WordPress site on a Madara-derived theme, served entirely as HTML.
 *
 * Identity: a series is the slug the catalogue files it under, and a chapter keeps the path its
 * reader page lives at (`"eleceed-chapter-425"`), because the site spells that path two ways and
 * only the link itself knows which one a chapter uses.
 *
 * Provenance:
 *
 *   GET /page/{n}/                     newest catalogue, forty posters a page
 *   GET /genres/{slug}/page/{n}/       one genre, ten a page
 *   GET /manga/?status=&genre[0]=&page= the filtered archive, twenty a page, and the one
 *                                       listing that takes a status
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
        // Only the archive takes one, and it is barely used: the site files almost every title as
        // ongoing, so "completed" is a handful of series rather than a working filter.
        status = true,
    )

    /** Politeness: a detail page is a few hundred KB of markup. */
    private val limiter = RateLimiter(350)

    @Volatile
    private var cachedGenres: List<SourceGenre>? = null

    @Volatile
    private var cachedTermIds: Map<String, Int>? = null

    /** Detail and chapter list are the same page, so opening a title reads it once. */
    @Volatile
    private var cachedDetail: Triple<String, String, Long>? = null

    /** Promo artwork already recognised, so a repeated banner is not probed again. */
    private val knownPromoUrls: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val tag = tagId?.takeIf { it.isNotBlank() }
        // The latest grid and the genre pages are fixed listings that ignore the query, so a
        // status sends the request to the one page that answers it.
        arenaStatus(filter.status)?.let { return browseArchive(it, tag, offset, limit) }
        // The site's own page size, not the caller's: the latest grid carries 40 rows a page and
        // a genre 10, so offsets are mapped to that grid and the remainder is dropped here.
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

    /**
     * The filtered archive, twenty rows a page. It is also the only listing that can say "one
     * genre, ongoing": the genre pages drop the status and the archive drops a genre slug.
     */
    private suspend fun browseArchive(
        status: String,
        tag: String?,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val page = offset / FILTERED_PAGE + 1
        val skip = offset % FILTERED_PAGE
        val termId = tag?.let { arenaTermId(it) }
        val body = request("/manga/") {
            html()
            parameter("status", status)
            parameter("page", page)
            termId?.let { parameter("genre[0]", it) }
        }
        return parseArenaCatalogue(body).drop(skip).take(limit)
    }

    /**
     * The archive takes a genre as a term id while the rest of the site, and so this source, names
     * genres by slug. Its filter form pairs every id with a name, so a slug becomes a name and the
     * name an id. Read once: the taxonomy is fixed and the form only lives on one page.
     */
    private suspend fun arenaTermId(slug: String): Int? {
        val name = genres().firstOrNull { it.id == slug }?.name ?: return null
        val terms = cachedTermIds ?: run {
            val parsed = parseArenaGenreTerms(request("/manga/"))
            if (parsed.isNotEmpty()) cachedTermIds = parsed
            parsed
        }
        return terms[name]
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
        val body = detailPage(remoteId)
        return parseArenaSeries(body, remoteId)
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val body = detailPage(manhwaRemoteId)
        return dedupeChapters(parseArenaChapters(body))
    }

    /**
     * One detail page serves both the title and its chapter list. Linking a profile reads the
     * same page twice otherwise, and an Arena page is a few hundred KB of markup.
     */
    private suspend fun detailPage(remoteId: String): String {
        val now = System.currentTimeMillis()
        cachedDetail?.let { (id, body, at) ->
            if (id == remoteId && now - at < DETAIL_CACHE_MS) return body
        }
        val body = request("/manga/$remoteId/") { html() }
        cachedDetail = Triple(remoteId, body, now)
        return body
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val body = request("/$chapterRemoteId/") { html() }
        var images = parseArenaPages(body)
        if (images.isEmpty()) throw SourceError.Unavailable()
        // Some Arena chapters end with a short recommendation banner served from the chapter's
        // own folder, so the markup cannot tell it from a page. A page of a webtoon is taller
        // than it is wide and the banner is not, so only the last image's header is read.
        if (images.size > 1) {
            val last = images.last()
            if (last.url in knownPromoUrls || isWidePromo(last.url)) {
                knownPromoUrls += last.url
                images = images.dropLast(1)
            }
        }
        return PageSet(images = images)
    }

    /**
     * Reads only the last image's header. A slow or unhelpful CDN must not hold the chapter: an
     * unanswered probe leaves the image in place, which is no worse than before.
     */
    private suspend fun isWidePromo(url: String): Boolean = runCatching {
        withTimeoutOrNull(PROMO_PROBE_MS) {
            val bytes = client.get(url) {
                header("Range", "bytes=0-63")
                header("Referer", "$ARENA_BASE/")
            }.bodyAsBytes()
            imageIsWide(bytes)
        }
    }.getOrNull() ?: false

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

        /** Rows the filtered archive lists. */
        const val FILTERED_PAGE = 20

        /** Long enough to serve detail and chapters together, short enough to see new chapters. */
        const val DETAIL_CACHE_MS = 60_000L

        /** A header probe answers in well under this; a slow CDN must not delay the chapter. */
        const val PROMO_PROBE_MS = 700L
    }
}

private fun arenaStatus(status: StatusFilter): String? = when (status) {
    StatusFilter.ANY -> null
    StatusFilter.ONGOING -> "ongoing"
    StatusFilter.COMPLETED -> "completed"
    StatusFilter.HIATUS -> "hiatus"
    // No value of its own; an unknown one answers an empty catalogue, which is the truth here.
    StatusFilter.CANCELLED -> "cancelled"
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
 * the number is read from the label rather than reconstructed. The label is not assumed to be
 * numbered: the rows the site files as a prologue or an extra carry their own text there.
 */
private val ARENA_CHAPTER = Regex(
    // The first chapter's row is the only one that carries a second attribute
    // (`class="first-chapter"`), and requiring the tag to end after `data-num` used to drop it.
    """<li data-num="[^"]*"[^>]*>\s*<div class="chbox">\s*<div class="eph-num">\s*<a href="https://arenascan\.com/([^"]+)">\s*<span class="chapternum">\s*([^<]{0,80})""",
    RegexOption.DOT_MATCHES_ALL,
)

internal fun parseArenaChapters(html: String): List<SourceChapter> {
    val chapters = mutableListOf<SourceChapter>()
    for (row in ARENA_CHAPTER.findAll(html)) {
        val path = row.groupValues[1].trim('/')
        if (path.isEmpty()) continue
        // The label is read as written, so a prologue or a side story is listed beside the run.
        val label = chapterLabel(row.groupValues[2]) ?: chapterLabel(path.replace('-', ' ')) ?: continue
        chapters += SourceChapter(
            remoteId = path,
            numberText = label.numberText,
            sortKey = label.sortKey,
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

/**
 * Artwork the reader page appends after the chapter: related-series cards, the site's own
 * banners and support pages. Their filenames say what they are, and they are not story pages.
 */
private val ARENA_PAGE_PROMO = Regex(
    """(?i)(recommend|related|banner|promo|sponsor|advert|discord|patreon|merch|credit)""",
)

/** `arena-bucket/<series id>`: every page of a chapter is filed under its series. */
private fun arenaSeriesFolder(url: String): String? {
    val parts = url.substringAfter("cdn.arenascan.com/", "")
        .split('/')
        .filter { it.isNotBlank() }
    return if (parts.size >= 3 && parts[0] == "arena-bucket") "${parts[0]}/${parts[1]}" else null
}

internal fun parseArenaPages(html: String): List<PageImage> {
    val urls = ARENA_PAGE.findAll(html)
        .map { it.groupValues[1] }
        .distinct()
        .filterNot { ARENA_PAGE_PROMO.containsMatchIn(it) }
        .toList()
    if (urls.isEmpty()) return emptyList()
    // The reader page also carries the site's related-series cards. They share the CDN host,
    // so the upload folder is what separates a page from a poster: the chapter's pages are all
    // under one series folder, and artwork from another series is appended chrome, not story.
    val folder = urls.mapNotNull(::arenaSeriesFolder)
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
    val pages = if (folder == null) urls else urls.filter { arenaSeriesFolder(it) == folder }
    return pages.ifEmpty { urls }.mapIndexed { index, url -> PageImage(index = index, url = url) }
}

/**
 * True when an image is much wider than it is tall, which a webtoon page never is. Only the
 * WebP forms Arena's CDN serves are read; anything else is left alone rather than guessed at.
 */
internal fun imageIsWide(bytes: ByteArray): Boolean {
    val size = webpSize(bytes) ?: return false
    val (width, height) = size
    return height > 0 && width > height * 3 / 2
}

private fun webpSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 30) return null
    fun text(from: Int, to: Int): String = bytes.copyOfRange(from, to).decodeToString()
    if (text(0, 4) != "RIFF" || text(8, 12) != "WEBP") return null
    return when (text(12, 16)) {
        // Extended format: 24-bit canvas dimensions, stored minus one.
        "VP8X" -> {
            val width = 1 + (bytes[24].u8() or (bytes[25].u8() shl 8) or (bytes[26].u8() shl 16))
            val height = 1 + (bytes[27].u8() or (bytes[28].u8() shl 8) or (bytes[29].u8() shl 16))
            width to height
        }
        // Lossy format: three-byte start code, then two 14-bit dimensions.
        "VP8 " -> {
            if (bytes[23].u8() != 0x9d || bytes[24].u8() != 0x01 || bytes[25].u8() != 0x2a) {
                return null
            }
            val width = bytes[26].u8() or ((bytes[27].u8() and 0x3f) shl 8)
            val height = bytes[28].u8() or ((bytes[29].u8() and 0x3f) shl 8)
            width to height
        }
        else -> null
    }
}

private fun Byte.u8(): Int = toInt() and 0xFF

/**
 * Genre names as the archive's filter form spells them, each with the id its genre page answers
 * to. A few names are filed twice, by an import and by the theme's own defaults; WordPress gives
 * the plain slug to the term created first, so the lowest id is the one `/genres/{slug}/` lists.
 */
private val ARENA_TERM = Regex("""id="genre-(\d+)"[^>]*>\s*<label for="genre-\d+">([^<]+)</label>""")

internal fun parseArenaGenreTerms(html: String): Map<String, Int> {
    val terms = LinkedHashMap<String, Int>()
    for (row in ARENA_TERM.findAll(html)) {
        val id = row.groupValues[1].toIntOrNull() ?: continue
        val name = unescapeHtml(row.groupValues[2]).trim()
        if (name.isEmpty()) continue
        terms[name] = minOf(terms[name] ?: id, id)
    }
    return terms
}

internal fun parseArenaGenres(html: String): List<SourceGenre> =
    ARENA_GENRE.findAll(html)
        .map { SourceGenre(id = it.groupValues[1], name = unescapeHtml(it.groupValues[2]).trim()) }
        .filter { it.name.isNotEmpty() }
        .distinctBy { it.id }
        .sortedBy { it.name }
        .toList()
