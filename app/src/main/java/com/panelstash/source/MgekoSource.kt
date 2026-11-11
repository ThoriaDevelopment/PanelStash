package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Mgeko, a large aggregator served as server-rendered HTML with one JSON route for paging.
 *
 * Identity: a series is the slug its URL carries, and a chapter is the reader path under
 * `/reader/en/` (`"<series>-chapter-<n>-eng-li"`), which is both its identity and its address.
 *
 * Provenance:
 *
 *   GET /browse-comics/data/?page=&sort=&include_genres=&exclude_genres=&status=
 *                                       catalogue cards, as an HTML fragment
 *   GET /search/?search=                                    search results, one page
 *   GET /manga/{slug}                                       detail, status, genres, aliases
 *   GET /manga/{slug}/all-chapters/                         every chapter in one document
 *   GET /reader/en/{chapterPath}                            reader page, every image inline
 *
 * The browse grid and the search list are two different card shapes, so the two parsers are
 * separate rather than pretending a shared one would be simpler next time a layout changes.
 */
class MgekoSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "mgeko"

    override val name = "Mgeko"

    override val description = "Large aggregator with plain readers and a broad manhwa catalogue."

    override val capabilities = SourceCapabilities(
        // The browse route takes both, with "trending" standing in for most read.
        sorts = setOf(BrowseSort.LATEST, BrowseSort.POPULAR),
        genres = true,
        titleGenres = true,
        // The route takes status and excluded genres as query parameters of its own.
        status = true,
        excludedGenres = true,
    )

    /** Politeness: pages are a few hundred KB of markup. */
    private val limiter = RateLimiter(400)

    @Volatile
    private var cachedGenres: List<SourceGenre>? = null

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        // The grid lists 24 cards a page, so offsets map to that grid rather than to the caller's
        // limit and the remainder is dropped here.
        val page = offset / BROWSE_PAGE + 1
        val skip = offset % BROWSE_PAGE
        val body = request("/browse-comics/data/") {
            parameter("page", page)
            // Top rated is not offered here; newest is the sane order if it arrives anyway.
            parameter("sort", if (sort == BrowseSort.POPULAR) "trending" else "recently_added")
            // The route reads the plural list form. `genre_included`, the name the site's own
            // detail links carry, is only understood by the browse page, which rewrites it.
            tagId?.takeIf { it.isNotBlank() }?.let { parameter("include_genres", it) }
            filter.excludedTagIds.takeIf { it.isNotEmpty() }
                ?.let { parameter("exclude_genres", it.joinToString(",")) }
            mgekoStatus(filter.status)?.let { parameter("status", it) }
        }
        val response = try {
            Network.json.decodeFromString<MgekoBrowseResponse>(body)
        } catch (error: Exception) {
            throw SourceError.Transient(error)
        }
        return parseMgekoCards(response.resultsHtml.orEmpty()).drop(skip).take(limit)
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        // The search page renders one list and pages it in the browser, so only page one exists.
        if (offset > 0) return emptyList()
        val body = request("/search/") { parameter("search", query.trim()) }
        return parseMgekoSearch(body).take(limit)
    }

    override suspend fun genres(): List<SourceGenre> {
        cachedGenres?.let { return it }
        // The search form is the one page that lists every genre the site can filter by.
        val genres = parseMgekoGenres(request("/search/"))
        if (genres.isNotEmpty()) cachedGenres = genres
        return genres
    }

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val body = request("/manga/$remoteId/") { html() }
        return parseMgekoSeries(body, remoteId)
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        // The detail page shows a window of recent chapters; the all-chapters page lists the run.
        val body = call("/manga/$manhwaRemoteId/all-chapters/") ?: request("/manga/$manhwaRemoteId/") { html() }
        return dedupeChapters(parseMgekoChapters(body))
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val body = request("/reader/en/$chapterRemoteId/") { html() }
        val images = parseMgekoPages(body)
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = MGEKO_CDN, images = images)
    }

    private suspend fun request(path: String, configure: HttpRequestBuilder.() -> Unit = { html() }): String =
        call(path, configure) ?: throw SourceError.Unavailable()

    /** Returns null only for 404, so a missing page can be told apart from a broken one. */
    private suspend fun call(path: String, configure: HttpRequestBuilder.() -> Unit = { html() }): String? {
        if (gate.isBlocked()) throw SourceError.Banned()
        limiter.acquire()
        val response = try {
            client.get("$MGEKO_BASE$path") {
                configure()
                header("Referer", "$MGEKO_BASE/")
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

            response.status == HttpStatusCode.NotFound -> null
            response.status.value >= 500 -> throw SourceError.Transient()
            !response.status.isSuccess() -> throw SourceError.Transient()
            else -> response.bodyAsText()
        }
    }

    private fun HttpRequestBuilder.html() {
        header("Accept", "text/html,application/xhtml+xml")
    }

    private companion object {
        /** Cards the browse grid lists per page. */
        const val BROWSE_PAGE = 24
    }
}

private fun mgekoStatus(status: StatusFilter): String? = when (status) {
    StatusFilter.ANY -> null
    StatusFilter.ONGOING -> "ongoing"
    StatusFilter.COMPLETED -> "completed"
    StatusFilter.HIATUS -> "hiatus"
    // The site has no cancelled status; an unknown value answers an empty catalogue, which is
    // closer to the truth than quietly showing everything.
    StatusFilter.CANCELLED -> "cancelled"
}

internal const val MGEKO_BASE = "https://www.mgeko.cc"
internal const val MGEKO_CDN = "https://imgsrv5.com"

/** The paging route answers JSON wrapping an HTML fragment rather than rows. */
@Serializable
internal data class MgekoBrowseResponse(
    @SerialName("results_html") val resultsHtml: String? = null,
    val page: Int = 0,
    @SerialName("num_pages") val numPages: Int = 0,
)

/** A browse card: the title anchor is the only place the slug and title appear together. */
private val MGEKO_CARD = Regex(
    """<h3 class="comic-card__title">\s*<a href="/manga/([^"]+)/">\s*(.*?)\s*</a>""",
    RegexOption.DOT_MATCHES_ALL,
)
private val MGEKO_CARD_COVER = Regex("""<img src="([^"]+)"""")
private val MGEKO_CARD_DESCRIPTION = Regex(
    """<p class="comic-card__description">\s*(.*?)\s*</p>""",
    RegexOption.DOT_MATCHES_ALL,
)

internal fun parseMgekoCards(html: String): List<SourceManhwa> {
    val rows = LinkedHashMap<String, SourceManhwa>()
    for (card in MGEKO_CARD.findAll(html)) {
        val slug = card.groupValues[1].trim('/')
        if (slug.isEmpty() || slug in rows) continue
        val title = unescapeHtml(card.groupValues[2]).trim()
        if (title.isEmpty()) continue
        // The cover sits before the title inside the same card, so the window looks both ways.
        val window = html.substring(maxOf(0, card.range.first - 800), minOf(html.length, card.range.first + 800))
        rows[slug] = SourceManhwa(
            remoteId = slug,
            title = title,
            // A truncated teaser is not a synopsis, so the detail page supplies the real one.
            description = null,
            coverUrl = MGEKO_CARD_COVER.find(window)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            year = null,
            status = null,
        )
    }
    return rows.values.toList()
}

/** The search list is a different card again: a lazy cover in `data-src` beside the title. */
private val MGEKO_SEARCH_ITEM = Regex("""<li class="novel-item">\s*<a href="/manga/([^"]+)/" title="([^"]*)"""")
private val MGEKO_LAZY_COVER = Regex("""data-src=['"]([^'"]+)['"]""")

internal fun parseMgekoSearch(html: String): List<SourceManhwa> {
    val rows = LinkedHashMap<String, SourceManhwa>()
    for (item in MGEKO_SEARCH_ITEM.findAll(html)) {
        val slug = item.groupValues[1].trim('/')
        if (slug.isEmpty() || slug in rows) continue
        val title = unescapeHtml(item.groupValues[2]).trim()
        if (title.isEmpty()) continue
        val window = html.substring(item.range.first, minOf(html.length, item.range.first + 1_500))
        val cover = MGEKO_LAZY_COVER.find(window)?.groupValues?.get(1)?.trim()
        rows[slug] = SourceManhwa(
            remoteId = slug,
            title = title,
            description = null,
            coverUrl = cover?.takeIf { it.isNotBlank() }?.let { absoluteMgekoUrl(it) },
            year = null,
            status = null,
        )
    }
    return rows.values.toList()
}

/** Lazily loaded covers come as a path; the grid's own cards serve an absolute URL. */
internal fun absoluteMgekoUrl(url: String): String =
    if (url.startsWith("http://") || url.startsWith("https://")) url else "$MGEKO_BASE${if (url.startsWith("/")) url else "/$url"}"

private val MGEKO_TITLE = Regex("""<h1[^>]*class="[^"]*novel-title[^"]*"[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
private val MGEKO_ALIAS = Regex("""<h2[^>]*class="[^"]*alternative-title[^"]*"[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)
private val MGEKO_OG_IMAGE = Regex("""<meta property="og:image" content="([^"]+)"""")
private val MGEKO_DESCRIPTION = Regex("""<p class="description">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
private val MGEKO_STATUS = Regex("""<strong class="[^"]*">\s*([^<]+?)\s*</strong>\s*<small>Status</small>""")
private val MGEKO_GENRE = Regex("""genre_included=\s*([^"&]+)[^"]*"[^>]*>\s*([^<]+?)\s*</a>""")

internal fun parseMgekoSeries(html: String, remoteId: String): SourceManhwa {
    val title = MGEKO_TITLE.find(html)?.groupValues?.get(1)?.let(::stripHtml)?.trim()
    // A page without a title is a challenge or a redirect, not a series.
    if (title.isNullOrBlank()) throw SourceError.Transient()
    val genres = MGEKO_GENRE.findAll(html)
        .map { SourceGenre(id = it.groupValues[1].trim(), name = unescapeHtml(it.groupValues[2]).trim()) }
        .filter { it.name.isNotEmpty() }
        .distinctBy { it.id }
        .toList()
    val alias = MGEKO_ALIAS.find(html)?.groupValues?.get(1)
        ?.let(::stripHtml)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return SourceManhwa(
        remoteId = remoteId,
        title = title,
        description = mgekoSynopsis(MGEKO_DESCRIPTION.find(html)?.groupValues?.get(1)),
        coverUrl = MGEKO_OG_IMAGE.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
        year = null,
        status = MGEKO_STATUS.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
        genres = genres,
        aliases = listOfNotNull(alias),
    )
}

/**
 * The site writes a boilerplate paragraph around every synopsis: what the work is, that chapters
 * are translated, then "The Summary is" and the text itself. Only the text is worth showing.
 */
internal fun mgekoSynopsis(html: String?): String? {
    val text = html?.let(::stripHtml)?.replace(Regex("""[ \t]+"""), " ")?.trim().orEmpty()
    if (text.isEmpty()) return null
    val marker = text.indexOf(MGEKO_SUMMARY_MARKER, ignoreCase = true)
    val summary = if (marker >= 0) {
        text.substring(marker + MGEKO_SUMMARY_MARKER.length)
    } else {
        text
    }
    return summary.trim().trimStart(':', '-').trim().takeIf { it.isNotBlank() }
}

private const val MGEKO_SUMMARY_MARKER = "The Summary is"

/** The all-chapters page carries a chapter title beside its link; older pages only the link. */
private val MGEKO_CHAPTER_LINK = Regex("""<a href="/reader/en/([^"]+)"[^>]*>""")
private val MGEKO_CHAPTER_NUMBER = Regex("""-chapter-([0-9]+(?:-[0-9]+)?)-""")

internal fun parseMgekoChapters(html: String): List<SourceChapter> {
    val chapters = LinkedHashMap<String, SourceChapter>()
    for (link in MGEKO_CHAPTER_LINK.findAll(html)) {
        val path = link.groupValues[1].trim('/')
        if (path.isEmpty() || path in chapters) continue
        // The chapter's own path is the label when the site does not number it, so a prologue
        // (`...-prologue-eng-li`) is listed instead of vanishing from the profile.
        val number = MGEKO_CHAPTER_NUMBER.find(path)?.groupValues?.get(1)?.replace('-', '.')
        val label = number?.let { ChapterLabel(it, it.toDoubleOrNull()) }
            ?: chapterLabel(path.replace('-', ' '))
            ?: continue
        chapters[path] = SourceChapter(
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
    return chapters.values.toList()
}

/**
 * Every page is an `<img class="mgr-page">`, in reading order. The class is the marker to trust:
 * the files moved from `/sv2/comic/...` to `/mg1/fastcdn/cdn_mangaraw/...` without it changing,
 * so filtering on a folder name made every chapter resolve to no pages. The site appends its own
 * credits banner to the same list, so that one image is dropped.
 */
private val MGEKO_PAGE = Regex("""<img[^>]*class="mgr-page"[^>]*src="([^"]+)"""")

internal fun parseMgekoPages(html: String): List<PageImage> =
    MGEKO_PAGE.findAll(html)
        .map { it.groupValues[1] }
        .filterNot { it.contains("credits", ignoreCase = true) }
        .distinct()
        .mapIndexed { index, url -> PageImage(index = index, url = url) }
        .toList()

/** Genre names double as filter values; the format checkboxes are not genres. */
private val MGEKO_GENRE_INPUT = Regex("""name="options\[\]"[^>]*value="([^"]+)"""")

private val MGEKO_FORMATS = setOf("manga", "manhwa", "manhua")

internal fun parseMgekoGenres(html: String): List<SourceGenre> =
    MGEKO_GENRE_INPUT.findAll(html)
        .map { unescapeHtml(it.groupValues[1]).trim() }
        .filter { it.isNotEmpty() && it.lowercase() !in MGEKO_FORMATS }
        .distinct()
        .sorted()
        .map { SourceGenre(id = it, name = it) }
        .toList()
