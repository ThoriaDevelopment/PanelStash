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
import java.time.Instant

/**
 * Asura Scans, a scanlation site with a JSON catalogue and a JSON reader.
 *
 * Identity: a series is its slug, and a chapter is its own slug scoped to the series slug, so the
 * two are joined into one string (`"<seriesSlug>|<chapterSlug>"`). Nothing outside this module
 * parses those values.
 *
 * The catalogue supports three orderings and a title search but no status or genre filter, so
 * [capabilities] promises only what the API actually applies. Premium and early-access chapters
 * are marked unreadable rather than failing when opened.
 */
class AsuraScansSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "asurascans"

    override val name = "Asura Scans"

    override val description = "Large scanlation catalogue. Fresh chapters, some premium."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST, BrowseSort.POPULAR, BrowseSort.TOP_RATED),
        genres = false,
        status = false,
        year = false,
        safeOnly = false,
        excludedGenres = false,
    )

    /** Politeness: undocumented endpoint, so stay well under a request every 300ms. */
    private val limiter = RateLimiter(300)

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val body = request("/api/series") {
            parameter("page", pageOf(offset, limit))
            parameter("limit", limit)
            parameter("sort", sortValue(sort))
        }
        return decode(AsuraSeriesResponse.serializer(), body).data.toSourceManhwa()
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        val body = request("/api/search") {
            parameter("q", query)
            parameter("page", pageOf(offset, limit))
            parameter("limit", limit)
        }
        return decode(AsuraSeriesResponse.serializer(), body).data.toSourceManhwa()
    }

    /** Listed on the site, but the catalogue API cannot filter by one. */
    override suspend fun genres(): List<SourceGenre> = emptyList()

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val body = request("/api/series/$remoteId")
        val dto = decode(AsuraSeriesDetailResponse.serializer(), body).series
            ?: throw SourceError.Unavailable()
        return dto.toSourceManhwa()
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val slug = manhwaRemoteId
        val body = request("/api/series/$slug/chapters")
        val chapters = decode(AsuraChaptersResponse.serializer(), body).data
        return dedupeChapters(chapters.mapNotNull { it.toSourceChapter(slug) })
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val parts = chapterRemoteId.split(SEPARATOR)
        if (parts.size != 2) throw SourceError.Unavailable()
        val body = request("/api/series/${parts[0]}/chapters/${parts[1]}")
        val data = decode(AsuraChapterPageResponse.serializer(), body).data
            ?: throw SourceError.Unavailable()
        // A premium or early-access chapter answers with an access gate and no page list.
        if (data.isLocked || !data.accessGate.isNullOrBlank()) throw SourceError.Unavailable()
        val images = data.chapter?.pages.orEmpty()
            .filter { it.url.isNotBlank() }
            .mapIndexed { index, page ->
                PageImage(index = index, url = page.url, width = page.width, height = page.height)
            }
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = CDN, images = images)
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

    private fun <T> decode(
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        body: String,
    ): T = try {
        Network.json.decodeFromString(serializer, body)
    } catch (error: Exception) {
        throw SourceError.Transient(error)
    }

    private companion object {
        const val BASE = "https://api.asurascans.com"
        const val CDN = "https://cdn.asurascans.com"
        const val SEPARATOR = "|"

        fun pageOf(offset: Int, limit: Int): Int = offset / limit.coerceAtLeast(1) + 1
    }
}

private fun sortValue(sort: BrowseSort): String = when (sort) {
    BrowseSort.LATEST -> "latest"
    BrowseSort.POPULAR -> "popular"
    BrowseSort.TOP_RATED -> "rating"
}

/** Prose has no pages, so it never reaches the UI; the site labels it separately. */
internal fun List<AsuraSeriesDto>.toSourceManhwa(): List<SourceManhwa> =
    filter { it.type.orEmpty().lowercase() !in NOVEL_TYPES }
        .filter { it.slug.isNotBlank() && it.title.isNotBlank() }
        .map { it.toSourceManhwa() }

private val NOVEL_TYPES = setOf("novel", "web_novel", "webnovel")

internal fun AsuraSeriesDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = slug,
    title = title.trim(),
    description = description?.let(::stripHtml)?.takeIf { it.isNotBlank() },
    coverUrl = cover?.takeIf { it.isNotBlank() },
    year = releaseYear,
    status = status,
)

internal fun AsuraChapterDto.toSourceChapter(seriesSlug: String): SourceChapter? {
    val number = number ?: return null
    if (slug.isBlank()) return null
    return SourceChapter(
        remoteId = "$seriesSlug|$slug",
        numberText = formatNumber(number),
        sortKey = number,
        title = null,
        language = "en",
        pageCount = pageCount,
        externalOnly = isPremium || isEarlyAccess(earlyAccessUntil),
    )
}

/** Early access ends at a timestamp; before then the pages are not published to guests. */
private fun isEarlyAccess(until: String?): Boolean {
    if (until.isNullOrBlank()) return false
    return runCatching { Instant.parse(until).isAfter(Instant.now()) }.getOrDefault(false)
}
