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
 * Nyx Scans, a scanlation site with an undocumented but JSON API.
 *
 * Identity: the API addresses a series by numeric id and slug, and a chapter by slug scoped to
 * the series slug, so both are joined into one string (`"<id>|<slug>"`, `"<seriesSlug>|<chapterSlug>"`).
 * Nothing outside this module parses those values.
 *
 * Genres exist, are listed by the API and are published on each title, but every plausible query
 * parameter that would filter by one is ignored, so [capabilities] reports no genre *filtering*:
 * the profile shows a title's genres while Browse shows no chips rather than a control that
 * quietly does nothing.
 */
class NyxScansSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "nyxscans"

    override val name = "Nyx Scans"

    override val description = "Scanlation site. Small, curated, mix of free and paid chapters."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST, BrowseSort.POPULAR),
        genres = false,
        titleGenres = true,
        status = true,
        year = false,
        safeOnly = false,
        excludedGenres = false,
    )

    /** Politeness: undocumented API, so stay well under a request every 250ms. */
    private val limiter = RateLimiter(250)

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val body = request("/api/query") {
            parameter("page", pageOf(offset, limit))
            parameter("perPage", limit)
            parameter("orderBy", if (sort == BrowseSort.POPULAR) "popular" else "latest")
            statusValue(filter.status)?.let { parameter("seriesStatus", it) }
        }
        return decode(NyxQueryResponse.serializer(), body).posts.toSourceManhwa()
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        val body = request("/api/query") {
            parameter("page", pageOf(offset, limit))
            parameter("perPage", limit)
            parameter("searchTerm", query)
        }
        return decode(NyxQueryResponse.serializer(), body).posts.toSourceManhwa()
    }

    /** Listed by the API, but not usable as a filter, so offering them would be a lie. */
    override suspend fun genres(): List<SourceGenre> = emptyList()

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val body = request("/api/post") { parameter("postSlug", slugOf(remoteId)) }
        val dto = decode(NyxPostDetail.serializer(), body).post ?: throw SourceError.Unavailable()
        return dto.toSourceManhwa()
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val slug = slugOf(manhwaRemoteId)
        val body = request("/api/chapters") { parameter("postId", idOf(manhwaRemoteId)) }
        val chapters = decode(NyxChaptersResponse.serializer(), body).post?.chapters.orEmpty()
        return dedupeChapters(chapters.mapNotNull { it.toSourceChapter(slug) })
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val parts = chapterRemoteId.split(SEPARATOR)
        if (parts.size != 2) throw SourceError.Unavailable()
        // This endpoint is the odd one out: both parameters are lower case.
        val body = request("/api/chapter") {
            parameter("mangaslug", parts[0])
            parameter("chapterslug", parts[1])
        }
        val chapter = decode(NyxChapterResponse.serializer(), body).chapter
            ?: throw SourceError.Unavailable()
        if (chapter.isPermanentlyLocked || (chapter.isLocked && !chapter.isAccessible)) {
            throw SourceError.Unavailable()
        }
        val images = chapter.images
            .sortedBy { it.order }
            .filter { it.url.isNotBlank() }
            .mapIndexed { index, image ->
                PageImage(index = index, url = image.url, width = image.width, height = image.height)
            }
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = images.first().url.substringBefore("/upload/"), images = images)
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
        // A shape change on an undocumented API means "this source is unusable right now", not a
        // crash while browsing.
        throw SourceError.Transient(error)
    }

    private companion object {
        const val BASE = "https://api.nyxscans.com"
        const val SEPARATOR = "|"

        fun idOf(remoteId: String): String = remoteId.substringBefore(SEPARATOR)

        fun slugOf(remoteId: String): String = remoteId.substringAfter(SEPARATOR, remoteId)

        fun pageOf(offset: Int, limit: Int): Int = offset / limit.coerceAtLeast(1) + 1
    }
}

private fun NyxScansSource.statusValue(status: StatusFilter): String? = when (status) {
    StatusFilter.ANY -> null
    StatusFilter.ONGOING -> "ONGOING"
    StatusFilter.COMPLETED -> "COMPLETED"
    StatusFilter.HIATUS -> "HIATUS"
    StatusFilter.CANCELLED -> "CANCELLED"
}

/**
 * The catalogue mixes prose novels in with the comics; they have no pages, so they are dropped
 * here rather than appearing as a title that cannot be opened. A title the catalogue reports with
 * no chapters is dropped for the same reason: it would open to an empty profile.
 */
internal fun List<NyxPostDto>.toSourceManhwa(): List<SourceManhwa> =
    filter { !it.isNovel && it.seriesType.orEmpty().uppercase() in COMIC_TYPES }
        .filter { it.slug.isNotBlank() && it.id != 0 }
        .filter { it.chapters.isNotEmpty() }
        .map { it.toSourceManhwa() }

private val COMIC_TYPES = setOf("MANHWA", "MANHUA", "MANGA")

internal fun NyxPostDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = "$id|$slug",
    title = postTitle.trim(),
    description = postContent?.takeIf { it.isNotBlank() }?.let(::stripHtml),
    coverUrl = featuredImage?.takeIf { it.isNotBlank() },
    year = null,
    status = seriesStatus,
    genres = genres.mapNotNull { genre ->
        val name = genre.name.trim()
        if (name.isBlank() || genre.id == 0) null else SourceGenre(genre.id.toString(), name)
    },
)

internal fun NyxChapterDto.toSourceChapter(seriesSlug: String): SourceChapter? {
    val number = number ?: return null
    return SourceChapter(
        remoteId = "$seriesSlug|$slug",
        numberText = formatNumber(number),
        sortKey = number,
        title = title?.takeIf { it.isNotBlank() },
        language = "en",
        // The count arrives with the page images, on first read.
        pageCount = 0,
        // Paid or locked-for-now chapters cannot be read without an account.
        externalOnly = isPermanentlyLocked || (isLocked && !isAccessible),
    )
}
