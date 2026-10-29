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
 * Weeb Central, the site that replaced MangaSee: a large scanlation catalogue behind plain HTML.
 *
 * Identity: a series is a pair of ids, the ULID the site files it under and the readable slug the
 * URL ends with, joined into one string (`"<ulid>/<slug>"`). A chapter is its own ULID.
 *
 * Every page is server-rendered, so there is no catalogue API to promise a filter. Provenance:
 *
 *   GET /search/data?text=&limit=&offset=      catalogue rows, in the site's own order
 *   GET /series/{ulid}/{slug}                  detail page
 *   GET /series/{ulid}/full-chapter-list       every chapter, as an htmx fragment
 *   GET /chapters/{ulid}/images?reading_style=long_strip   page images, as an htmx fragment
 *
 * The full chapter list is the difference from Orion and Kayn: it arrives in one document however
 * long the series is, so a 200 chapter run is a single request.
 */
class WeebCentralSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "weebcentral"

    override val name = "Weeb Central"

    override val description = "Big catalogue, complete chapter lists, nothing coin-locked."

    override val capabilities = SourceCapabilities(
        // The catalogue endpoint lists rows in the site's own order and takes no sort parameter.
        sorts = setOf(BrowseSort.LATEST),
        genres = false,
        status = false,
        year = false,
    )

    /** Politeness: a search page is a few hundred KB of markup, so leave room between calls. */
    private val limiter = RateLimiter(400)

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> = catalogue("", offset, limit)

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> =
        catalogue(query, offset, limit)

    private suspend fun catalogue(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        val body = request("/search/data") {
            parameter("text", query)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        return parseWeebCatalogue(body).take(limit)
    }

    /** The series page files a title under tags, but the catalogue cannot be filtered by them. */
    override suspend fun genres(): List<SourceGenre> = emptyList()

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val body = request("/series/$remoteId") { html() }
        return parseWeebSeries(body, remoteId)
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val seriesId = manhwaRemoteId.substringBefore("/")
        val body = request("/series/$seriesId/full-chapter-list") { html() }
        return dedupeChapters(parseWeebChapters(body, seriesId))
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val body = request("/chapters/$chapterRemoteId/images") {
            html()
            // The endpoint is an htmx fragment and answers with a redirect without the header.
            header("HX-Request", "true")
            parameter("reading_style", "long_strip")
        }
        val images = parseWeebPages(body)
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = images.first().url.substringBefore("/manga/"), images = images)
    }

    private suspend fun request(
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
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

    internal companion object {
        const val BASE = "https://weebcentral.com"
    }
}

/** A series link on a catalogue row, which is the only identity the site gives a search result. */
private val SERIES_LINK = Regex("""weebcentral\.com/series/([A-Z0-9]{26})/([^"]+)""")

/** The cover is the only place a row states its title, as `alt="<title> cover"`. */
private val ROW_COVER = Regex("""<img\s+src="([^"]+)"[^>]*alt="([^"]*)"""")

/** Chapter numbers sit in a span after the link, e.g. `<span class="">Chapter 12.5</span>`. */
private val CHAPTER_LINK = Regex("""<a href="/chapters/([A-Z0-9]{26})"""")

private val CHAPTER_NUMBER = Regex("""Chapter\s+([0-9][0-9.]*)""")

private val PAGE_IMAGE = Regex("""<img[^>]+src="(https?://[^"]+\.(?:png|jpe?g|webp))"""")

/**
 * The catalogue fragment repeats each series twice, once for the cover and once for the title, so
 * rows are keyed by the ULID and written once.
 */
internal fun parseWeebCatalogue(html: String): List<SourceManhwa> {
    val rows = LinkedHashMap<String, SourceManhwa>()
    for (link in SERIES_LINK.findAll(html)) {
        val id = link.groupValues[1]
        if (id in rows) continue
        val slug = link.groupValues[2]
        val window = html.substring(link.range.first, minOf(html.length, link.range.first + 2_000))
        val cover = ROW_COVER.find(window)
        val title = cover?.groupValues?.get(2)
            ?.removeSuffix(" cover")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ').trim()
        if (title.isBlank()) continue
        rows[id] = SourceManhwa(
            remoteId = "$id/$slug",
            title = title,
            // Synopsis, status and year are only on the series page.
            description = null,
            coverUrl = cover?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            year = null,
            status = null,
        )
    }
    return rows.values.toList()
}

private val TITLE = Regex("""<h1[^>]*>\s*([^<]+?)\s*</h1>""")
private val OG_IMAGE = Regex("""property="og:image"\s+content="([^"]+)"""")
private val DESCRIPTION = Regex("""<strong>Description</strong>\s*<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
private val STATUS_LINK = Regex("""included_status=([A-Za-z]+)""")
private val RELEASED = Regex("""<strong>Released:\s*</strong>\s*<span>(\d{4})</span>""")
private val TAG_LINK = Regex("""included_tag=([^"&]+)""")

internal fun parseWeebSeries(html: String, remoteId: String): SourceManhwa {
    val title = TITLE.find(html)?.groupValues?.get(1)?.trim()
    // A page without a title is a challenge or a redirect, not a series.
    if (title.isNullOrBlank()) throw SourceError.Transient()
    val description = DESCRIPTION.find(html)?.groupValues?.get(1)
        ?.let(::stripHtml)
        ?.takeIf { it.isNotBlank() }
    val genres = TAG_LINK.findAll(html)
        .map { it.groupValues[1].replace("+", " ").trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .map { SourceGenre(id = it, name = it) }
        .toList()
    return SourceManhwa(
        remoteId = remoteId,
        title = title,
        description = description,
        coverUrl = OG_IMAGE.find(html)?.groupValues?.get(1),
        year = RELEASED.find(html)?.groupValues?.get(1)?.toIntOrNull(),
        status = STATUS_LINK.find(html)?.groupValues?.get(1),
        genres = genres,
    )
}

internal fun parseWeebChapters(html: String, seriesId: String): List<SourceChapter> {
    val chapters = mutableListOf<SourceChapter>()
    for (link in CHAPTER_LINK.findAll(html)) {
        val id = link.groupValues[1]
        // The number is in the anchor's own text, a few tags after the link opens.
        val window = html.substring(link.range.first, minOf(html.length, link.range.first + 700))
        val number = CHAPTER_NUMBER.find(window)?.groupValues?.get(1) ?: continue
        chapters += SourceChapter(
            remoteId = id,
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

internal fun parseWeebPages(html: String): List<PageImage> =
    PAGE_IMAGE.findAll(html)
        .map { it.groupValues[1] }
        .distinct()
        .mapIndexed { index, url -> PageImage(index = index, url = url) }
        .toList()
