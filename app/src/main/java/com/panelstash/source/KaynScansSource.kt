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
 * Kayn Scans, a scanlation site: a JSON catalogue plus a server-rendered series page.
 *
 * Identity: a series is addressed by id in the payload and by slug in its URL, so the two are
 * joined into one string (`"<id>|<slug>"`); a chapter is addressed by its own id alone. Nothing
 * outside this module parses those values.
 *
 * Coin-gated chapters are the norm here, especially on new releases, and they cannot be read
 * without an account, so they are marked unreadable and never offered.
 */
class KaynScansSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "kaynscans"

    override val name = "Kayn Scans"

    override val description = "Scanlation site. Wide catalogue, newest chapters often coin-locked."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST, BrowseSort.POPULAR, BrowseSort.TOP_RATED),
        genres = true,
        status = true,
        year = false,
        safeOnly = false,
        excludedGenres = false,
    )

    /** Politeness: undocumented endpoints, so stay well a request apart. */
    private val limiter = RateLimiter(300)

    /** Detail and chapter list come from one page, so both calls share a single fetch. */
    @Volatile
    private var cached: Pair<String, KaynSeries>? = null
    @Volatile
    private var cachedAt = 0L

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> = catalogue(sort, tagId, filter, query = null, offset = offset, limit = limit)

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        return catalogue(BrowseSort.LATEST, null, BrowseFilter.None, query, offset, limit)
    }

    override suspend fun genres(): List<SourceGenre> =
        decode(KaynGenresResponse.serializer(), request("/api/genres")).genres
            .filter { it.name.isNotBlank() }
            // The catalogue filters by slug, so the slug is the id the UI carries around.
            .map { SourceGenre(id = it.slug.ifBlank { it.id }, name = it.name) }

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val series = series(remoteId)
        return SourceManhwa(
            remoteId = remoteId,
            title = series.title,
            description = series.description,
            coverUrl = series.coverUrl,
            // The site publishes no release year.
            year = null,
            status = series.status,
        )
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val series = series(manhwaRemoteId)
        // The page publishes the whole list in one request; the paged endpoint is the fallback
        // for a payload whose shape has moved on.
        val chapters = series.chapters.ifEmpty { pagedChapters(series.id) }
        return dedupeChapters(chapters)
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val body = request("/api/chapters/page-urls") { parameter("chapterId", chapterRemoteId) }
        val images = decode(KaynPageUrlsResponse.serializer(), body).pages.toPageImages()
        // A coin-locked chapter publishes its pages with the URLs withheld.
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = KAYN_BASE, images = images)
    }

    private suspend fun catalogue(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        query: String?,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val body = request("/api/series") {
            parameter("page", pageOf(offset, limit))
            parameter("limit", limit)
            parameter("sort", sortValue(sort))
            tagId?.let { parameter("genre", it) }
            statusValue(filter.status)?.let { parameter("status", it) }
            query?.let { parameter("q", it) }
        }
        return decode(KaynSeriesResponse.serializer(), body).data.toSourceManhwa()
    }

    /**
     * The chapter list, ten at a time and oldest first: `beforeNumber` walks backwards from the
     * newest chapter, so each call asks for the batch just below the one already read.
     */
    private suspend fun pagedChapters(seriesId: String): List<SourceChapter> {
        val chapters = mutableListOf<SourceChapter>()
        var before = Int.MAX_VALUE
        while (true) {
            val body = request("/api/chapters/next") {
                parameter("seriesId", seriesId)
                parameter("beforeNumber", before)
                parameter("limit", PAGE_SIZE)
            }
            val batch = decode(KaynChaptersResponse.serializer(), body).chapters
            if (batch.isEmpty()) return chapters
            chapters += batch.mapNotNull { it.toSourceChapter() }
            val lowest = batch.mapNotNull { it.number }.minOrNull() ?: return chapters
            if (lowest <= 1) return chapters
            before = lowest.toInt()
        }
    }

    private suspend fun series(remoteId: String): KaynSeries {
        val now = System.currentTimeMillis()
        cached?.let { (id, series) ->
            if (id == remoteId && now - cachedAt < SERIES_CACHE_MS) return series
        }
        val slug = remoteId.substringAfter(SEPARATOR, remoteId)
        val body = request("/series/comic/$slug") { html() }
        return parseKaynSeries(body, remoteId.substringBefore(SEPARATOR)).also {
            cached = remoteId to it
            cachedAt = now
        }
    }

    private suspend fun request(
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): String {
        if (gate.isBlocked()) throw SourceError.Banned()
        limiter.acquire()
        val response = try {
            client.get("$KAYN_BASE$path") {
                configure()
                header("Referer", "$KAYN_BASE/")
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

    private fun <T> decode(
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        body: String,
    ): T = try {
        Network.json.decodeFromString(serializer, body)
    } catch (error: Exception) {
        throw SourceError.Transient(error)
    }

    private fun HttpRequestBuilder.html() {
        header("Accept", "text/html,application/xhtml+xml")
    }

    private companion object {
        const val SEPARATOR = "|"

        /** Ten is all this endpoint returns, whatever the caller asks for. */
        const val PAGE_SIZE = 10

        /** Long enough to serve detail and chapters together, short enough to see new chapters. */
        const val SERIES_CACHE_MS = 60_000L

        fun pageOf(offset: Int, limit: Int): Int = offset / limit.coerceAtLeast(1) + 1
    }
}

/**
 * The chapter's pages in reading order. The site mixes its own intro and outro cards in with
 * them, and a coin-locked chapter comes back with every URL withheld, which leaves nothing here.
 */
internal fun List<KaynPageDto>.toPageImages(): List<PageImage> =
    filter { it.kind == null || it.kind == CONTENT }
        .filter { !it.isRedacted && it.imageUrl.isNotBlank() }
        .sortedBy { it.pageNumber }
        .mapIndexed { index, page ->
            PageImage(
                index = index,
                url = kaynUrl(page.imageUrl),
                width = page.width,
                height = page.height,
            )
        }

private const val CONTENT = "CONTENT"

private fun statusValue(status: StatusFilter): String? = when (status) {
    StatusFilter.ANY -> null
    StatusFilter.ONGOING -> "ONGOING"
    StatusFilter.COMPLETED -> "COMPLETED"
    StatusFilter.HIATUS -> "HIATUS"
    // DROPPED is the site's word for a series its team gave up on.
    StatusFilter.CANCELLED -> "DROPPED"
}

private fun sortValue(sort: BrowseSort): String = when (sort) {
    BrowseSort.LATEST -> "latest"
    BrowseSort.POPULAR -> "popular"
    BrowseSort.TOP_RATED -> "rating"
}

/**
 * The site calls a series nothing, a manhwa, a manga, a manhua or a web novel. Prose has no pages,
 * so it is dropped here rather than appearing as a title that cannot be opened.
 */
internal fun List<KaynSeriesDto>.toSourceManhwa(): List<SourceManhwa> =
    filter { it.type.orEmpty().uppercase() !in NOVEL_TYPES }
        .filter { it.id.isNotBlank() && it.urlSlug.isNotBlank() }
        .map { it.toSourceManhwa() }

private val NOVEL_TYPES = setOf("WEB_NOVEL", "NOVEL")

internal fun KaynSeriesDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = "$id|$urlSlug",
    title = title.trim(),
    // The synopsis lives on the series page only, so it arrives with the detail.
    description = null,
    coverUrl = coverImage?.takeIf { it.isNotBlank() }?.let(::kaynUrl),
    year = null,
    status = status,
)

internal fun KaynChapterDto.toSourceChapter(): SourceChapter? {
    val number = number ?: return null
    if (id.isBlank()) return null
    return SourceChapter(
        remoteId = id,
        numberText = formatNumber(number),
        sortKey = number,
        title = title?.takeIf { it.isNotBlank() },
        language = "en",
        pageCount = pageCount,
        // Coin-gated and early-access chapters cannot be read without an account.
        externalOnly = isLocked || !hasAccess,
    )
}
