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
 * Mangapill, a large scanlation catalogue served as plain HTML.
 *
 * Identity: a series is the numeric id and the slug the URL carries, joined into one string
 * (`"<id>/<slug>"`). A chapter keeps the path the site gives it (`"<seriesId>-<chapterId>/<slug>"`),
 * which is what the reader URL needs and nothing outside this module parses.
 *
 * The catalogue is a filter form on a search page, so the endpoints the app uses are:
 *
 *   GET /mangas/new?page=                 newest additions, the only ordering on offer
 *   GET /search?q=&page=                  title search
 *   GET /search?genre=&page=              one genre, by name
 *   GET /manga/{id}/{slug}                detail page and full chapter list
 *   GET /chapters/{seriesId}-{chapterId}/{slug}   reader page
 *
 * Genres are the site's own names, so the chip list is read from the search form once and cached.
 */
class MangaPillSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "mangapill"

    override val name = "Mangapill"

    override val description = "Large catalogue. Clean pages, plain HTML, no coin locks."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST),
        genres = true,
        titleGenres = true,
    )

    /** Politeness: rendered pages, so stay well under a request every 300ms. */
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
        val page = pageOf(offset, limit)
        val body = if (tagId.isNullOrBlank()) {
            request("/mangas/new") { parameter("page", page) }
        } else {
            request("/search") {
                parameter("genre", tagId)
                parameter("page", page)
            }
        }
        return parseMangaPillCatalogue(body).take(limit)
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        val body = request("/search") {
            parameter("q", query)
            parameter("page", pageOf(offset, limit))
        }
        return parseMangaPillCatalogue(body).take(limit)
    }

    override suspend fun genres(): List<SourceGenre> {
        cachedGenres?.let { return it }
        // The filter form lives on a search page; an empty query still renders it.
        val body = request("/search") { parameter("q", "a") }
        val genres = parseMangaPillGenres(body)
        if (genres.isNotEmpty()) cachedGenres = genres
        return genres
    }

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val body = request("/manga/$remoteId") { html() }
        return parseMangaPillSeries(body, remoteId)
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val body = request("/manga/$manhwaRemoteId") { html() }
        return dedupeChapters(parseMangaPillChapters(body))
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val body = request("/chapters/$chapterRemoteId") { html() }
        val images = parseMangaPillPages(body)
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = images.first().url.substringBefore("/file/"), images = images)
    }

    private suspend fun request(
        path: String,
        configure: HttpRequestBuilder.() -> Unit = { html() },
    ): String {
        if (gate.isBlocked()) throw SourceError.Banned()
        limiter.acquire()
        val response = try {
            client.get("$BASE$path") {
                configure()
                header("Referer", "$BASE/")
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

    private fun pageOf(offset: Int, limit: Int): Int = offset / limit.coerceAtLeast(1) + 1

    internal companion object {
        const val BASE = "https://mangapill.com"
    }
}

/**
 * A catalogue card: the title anchor, then the cover and the type/year/status badges that follow
 * it in the same card. The cover sits in a second anchor, so it is found in a window rather than
 * inside this match.
 */
private val CARD = Regex(
    """<a href="/manga/(\d+)/([^"]+)"[^>]*>\s*<div class="[^"]*font-black[^"]*">([^<]+)</div>""",
)

private val COVER = Regex("""data-src="([^"]+)"""")
private val TYPE_BADGE = Regex("""bg-purple-500[^"]*">([^<]+)<""")
private val YEAR_BADGE = Regex("""bg-orange-500[^"]*">([^<]+)<""")
private val STATUS_BADGE = Regex("""bg-green-500[^"]*">([^<]+)<""")

/** Prose shares the catalogue and cannot be read here, so it never becomes a catalogue row. */
private const val NOVEL = "novel"

internal fun parseMangaPillCatalogue(html: String): List<SourceManhwa> {
    val rows = LinkedHashMap<String, SourceManhwa>()
    for (card in CARD.findAll(html)) {
        val id = card.groupValues[1]
        if (id in rows) continue
        val slug = card.groupValues[2]
        val title = card.groupValues[3].let(::unescapeHtml).trim()
        if (title.isBlank()) continue
        val window = html.substring(card.range.first, minOf(html.length, card.range.first + 3_000))
        val type = TYPE_BADGE.find(window)?.groupValues?.get(1)?.trim()
        if (type?.lowercase() == NOVEL) continue
        rows[id] = SourceManhwa(
            remoteId = "$id/$slug",
            title = title,
            description = null,
            coverUrl = COVER.find(window)?.groupValues?.get(1),
            year = YEAR_BADGE.find(window)?.groupValues?.get(1)?.trim()?.toIntOrNull(),
            status = STATUS_BADGE.find(window)?.groupValues?.get(1)?.trim(),
        )
    }
    return rows.values.toList()
}

internal fun parseMangaPillGenres(html: String): List<SourceGenre> =
    GENRE_INPUT.findAll(html)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .map { SourceGenre(id = it, name = it) }
        .toList()

private val GENRE_INPUT = Regex("""name="genre"\s+value="([^"]+)"""")

private val TITLE = Regex("""<h1[^>]*>\s*([^<]+?)\s*</h1>""")
private val DESCRIPTION = Regex("""<p class="text-sm text--secondary">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
private val META_ROW = Regex("""<label class="text-secondary">(\w+)</label>\s*<div>([^<]+)</div>""")
private val GENRE_LINK = Regex("""href="/search\?genre=([^"]+)">([^<]+)</a>""")

internal fun parseMangaPillSeries(html: String, remoteId: String): SourceManhwa {
    val title = TITLE.find(html)?.groupValues?.get(1)?.let(::unescapeHtml)?.trim()
    if (title.isNullOrBlank()) throw SourceError.Transient()
    val meta = META_ROW.findAll(html).associate { row ->
        row.groupValues[1] to row.groupValues[2].trim()
    }
    val description = DESCRIPTION.find(html)?.groupValues?.get(1)
        ?.let(::stripHtml)
        ?.takeIf { it.isNotBlank() }
    val genres = GENRE_LINK.findAll(html)
        .map { match -> SourceGenre(id = match.groupValues[1], name = unescapeHtml(match.groupValues[2]).trim()) }
        .distinctBy { it.id }
        .toList()
    return SourceManhwa(
        remoteId = remoteId,
        title = title,
        description = description,
        coverUrl = COVER.find(html)?.groupValues?.get(1),
        year = meta["Year"]?.toIntOrNull(),
        status = meta["Status"],
        genres = genres,
    )
}

private val CHAPTER_LINK = Regex("""<a[^>]+href="/chapters/([^"]+)"[^>]*>\s*(Chapter[^<]*)</a>""")
private val CHAPTER_NUMBER = Regex("""Chapter\s+([0-9][0-9.]*)""")

internal fun parseMangaPillChapters(html: String): List<SourceChapter> {
    val chapters = mutableListOf<SourceChapter>()
    for (link in CHAPTER_LINK.findAll(html)) {
        val path = link.groupValues[1]
        val number = CHAPTER_NUMBER.find(link.groupValues[2])?.groupValues?.get(1) ?: continue
        chapters += SourceChapter(
            remoteId = path,
            numberText = number,
            sortKey = chapterSortKey(number),
            title = null,
            language = "en",
            pageCount = 0,
            externalOnly = false,
        )
    }
    if (chapters.isEmpty()) throw SourceError.Transient()
    return chapters
}

private val PAGE_IMAGE =
    Regex("""class="js-page"[^>]*data-src="([^"]+)"[^>]*?(?:width="(\d+)")?[^>]*?(?:height="(\d+)")?""")

internal fun parseMangaPillPages(html: String): List<PageImage> =
    PAGE_IMAGE.findAll(html)
        .map { it.groupValues[1] }
        .distinct()
        .mapIndexed { index, url -> PageImage(index = index, url = url) }
        .toList()

/** The site writes entities in titles and chapter names ("&quot;", "&#039;"). */
internal fun unescapeHtml(text: String): String = text
    .replace("&quot;", "\"")
    .replace("&#039;", "'")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
