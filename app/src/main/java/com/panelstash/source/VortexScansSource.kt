package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException

/**
 * Vortex Scans, a scanlation site, behind the same contract as MangaDex.
 *
 * Identity: this API addresses a series by post id *and* slug, and a chapter by its own slug
 * scoped to the manga slug. The [Source] contract carries a single remote id per row, so the two
 * are joined into one string (`"<id>|<slug>"`, `"<mangaSlug>|<chapterSlug>"`). The encoding stays
 * inside this module; nothing outside it parses those values.
 *
 * Genre filtering is not exposed by the API, so [capabilities] says so and the UI offers no genre
 * chips for this source rather than showing a control that quietly does nothing.
 */
class VortexScansSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "vortexscans"
    override val name = "Vortex Scans"
    override val description = "Scanlation site. Fresh chapters, smaller catalogue."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST),
        genres = false,
        status = true,
        year = false,
        safeOnly = false,
        excludedGenres = false,
    )

    /** Politeness: the public API is undocumented, so stay well under a request every 300ms. */
    private val limiter = RateLimiter(300)

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val body = request("/api/query") {
            parameter("page", offset / limit.coerceAtLeast(1) + 1)
            parameter("perPage", limit)
            parameter("orderBy", "latest")
            statusValue(filter.status)?.let { parameter("seriesStatus", it) }
        }
        return decode(VortexQueryResponse.serializer(), body).posts.toSourceManhwa()
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        val body = request("/api/query") {
            parameter("page", offset / limit.coerceAtLeast(1) + 1)
            parameter("perPage", limit)
            parameter("searchTerm", query)
        }
        return decode(VortexQueryResponse.serializer(), body).posts.toSourceManhwa()
    }

    /** The API exposes a genre list but cannot filter by it, so browse shows no genre chips. */
    override suspend fun genres(): List<SourceGenre> = emptyList()

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val slug = slugOf(remoteId)
        val body = request("/api/post") { parameter("postSlug", slug) }
        val dto = decode(VortexPostDetail.serializer(), body).post ?: throw SourceError.Unavailable()
        return dto.toSourceManhwa()
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val slug = slugOf(manhwaRemoteId)
        val body = request("/api/chapters") { parameter("postId", idOf(manhwaRemoteId)) }
        val chapters = decode(VortexChaptersResponse.serializer(), body).post?.chapters.orEmpty()
        return dedupeChapters(
            chapters.mapNotNull { chapter -> chapter.toSourceChapter(slug) },
        )
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val parts = chapterRemoteId.split(SEPARATOR)
        if (parts.size != 2) throw SourceError.Unavailable()
        val body = request("/api/chapter") {
            parameter("mangaslug", parts[0])
            parameter("chapterSlug", parts[1])
        }
        val chapter = decode(VortexChapterPageResponse.serializer(), body).chapter
            ?: throw SourceError.Unavailable()
        if (chapter.isLocked || !chapter.isAccessible) throw SourceError.Unavailable()
        val images = chapter.images
            .sortedBy { it.order }
            .filter { it.url.isNotBlank() }
            .mapIndexed { index, image ->
                PageImage(index = index, url = image.url, width = image.width, height = image.height)
            }
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = images.first().url.substringBefore("/upload/"), images = images)
    }

    private suspend fun request(path: String, configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): String {
        if (gate.isBlocked()) throw SourceError.Banned()
        limiter.acquire()
        val response = try {
            client.get("${BASE}$path") {
                configure()
                // A plain client is what the site's own front end uses; some edge rules reject
                // requests that look like automation.
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
        // A shape change on an undocumented API is "this source is not usable right now", not a
        // crash on the browse screen.
        throw SourceError.Transient(error)
    }

    private companion object {
        const val BASE = "https://vortexscans.org"
        const val SEPARATOR = "|"

        fun idOf(remoteId: String): String = remoteId.substringBefore(SEPARATOR)
        fun slugOf(remoteId: String): String = remoteId.substringAfter(SEPARATOR, remoteId)
    }
}

private fun VortexScansSource.statusValue(status: StatusFilter): String? = when (status) {
    StatusFilter.ANY -> null
    StatusFilter.ONGOING -> "ONGOING"
    StatusFilter.COMPLETED -> "COMPLETED"
    StatusFilter.HIATUS -> "HIATUS"
    StatusFilter.CANCELLED -> "CANCELLED"
}

private fun List<VortexPostDto>.toSourceManhwa(): List<SourceManhwa> =
    filter { !it.isNovel && it.seriesType.equals("MANHWA", ignoreCase = true) }
        .filter { it.slug.isNotBlank() && it.id != 0 }
        .map { it.toSourceManhwa() }

private fun VortexPostDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = "$id|$slug",
    title = postTitle.trim(),
    description = postContent?.let(::stripHtml),
    coverUrl = featuredImage?.takeIf { it.isNotBlank() },
    year = null,
    status = seriesStatus,
)

private fun VortexChapterDto.toSourceChapter(mangaSlug: String): SourceChapter? {
    val number = number ?: return null
    val text = formatNumber(number)
    return SourceChapter(
        remoteId = "$mangaSlug|$slug",
        numberText = text,
        sortKey = number,
        title = title?.takeIf { it.isNotBlank() },
        language = "en",
        // The API reports page counts only when a chapter is opened, so the count is stored
        // after the first read.
        pageCount = 0,
        // Coin-locked chapters have no readable pages here.
        externalOnly = isPermanentlyLocked,
    )
}

/** 15.0 -> "15", 10.5 -> "10.5". */
internal fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

internal fun stripHtml(html: String): String =
    html.replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("</p>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&#39;|&apos;"), "'")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
