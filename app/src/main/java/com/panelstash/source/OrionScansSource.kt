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
 * Orion Scans, a scanlation site with a JSON catalogue and a JSON reader.
 *
 * Identity: a series is its slug, and a chapter is its own slug scoped to the series slug, so the
 * two are joined into one string (`"<seriesSlug>|<chapterSlug>"`). Nothing outside this module
 * parses those values.
 *
 * The series and reader pages used to be read as HTML. They are now client-rendered and no longer
 * carry the island props that parser looked for, while the same API the catalogue uses answers
 * detail, the chapter list and the page images. Reading those routes means a page redesign cannot
 * leave every chapter listed but unopenable.
 *
 * The catalogue API can search and filter by status, but ignores genre parameters, so
 * [capabilities] reports no genre *filtering* and Browse shows no chips rather than a control that
 * quietly does nothing. Each title still reports its genres, so a profile can show them. Ordering
 * cannot be verified either: the API accepts `orderBy` and returns a list that barely changes, so
 * only newest-first is offered.
 */
class OrionScansSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "orionscans"

    override val name = "Orion Scans"

    override val description = "Scanlation site. Small catalogue, newest chapters free."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST),
        genres = false,
        titleGenres = true,
        status = true,
        year = false,
        safeOnly = false,
        excludedGenres = false,
    )

    /** Politeness: undocumented endpoints, so stay well under a request every 300ms. */
    private val limiter = RateLimiter(300)

    /** Detail supplies the id the chapter list is queried by, so both calls share one fetch. */
    @Volatile
    private var cached: Pair<String, OrionPostDetailDto>? = null
    @Volatile
    private var cachedAt = 0L

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
            statusValue(filter.status)?.let { parameter("seriesStatus", it) }
        }
        return decode(OrionQueryResponse.serializer(), body).posts.toSourceManhwa()
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        val body = request("/api/query") {
            parameter("page", pageOf(offset, limit))
            parameter("perPage", limit)
            parameter("searchTerm", query)
        }
        return decode(OrionQueryResponse.serializer(), body).posts.toSourceManhwa()
    }

    /** Listed on the series pages, but not usable as a filter, so offering them would be a lie. */
    override suspend fun genres(): List<SourceGenre> = emptyList()

    override suspend fun manhwa(remoteId: String): SourceManhwa = series(remoteId).toSourceManhwa()

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val seriesId = series(manhwaRemoteId).id
        val body = request("/api/chapters") { parameter("postId", seriesId) }
        val chapters = decode(OrionChaptersResponse.serializer(), body).post?.chapters.orEmpty()
        return dedupeChapters(chapters.mapNotNull { it.toSourceChapter(manhwaRemoteId) })
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val parts = chapterRemoteId.split(SEPARATOR)
        if (parts.size != 2) throw SourceError.Unavailable()
        val body = request("/api/chapter") {
            parameter("mangaslug", parts[0])
            parameter("chapterslug", parts[1])
        }
        val chapter = decode(OrionChapterPageResponse.serializer(), body).chapter
            ?: throw SourceError.Unavailable()
        if (chapter.isPermanentlyLocked || (chapter.isLocked && !chapter.isAccessible)) {
            throw SourceError.Unavailable()
        }
        val images = chapter.toPageImages()
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = images.first().url.substringBefore("/upload/"), images = images)
    }

    private suspend fun series(seriesSlug: String): OrionPostDetailDto {
        val now = System.currentTimeMillis()
        cached?.let { (slug, post) ->
            if (slug == seriesSlug && now - cachedAt < SERIES_CACHE_MS) return post
        }
        val body = request("/api/post") { parameter("postSlug", seriesSlug) }
        val post = decode(OrionPostDetailResponse.serializer(), body).post
            ?: throw SourceError.Unavailable()
        cached = seriesSlug to post
        cachedAt = now
        return post
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
                // A plain browser-shaped request is what the site's own front end sends; some
                // edge rules reject anything that looks like automation.
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
        const val BASE = "https://orion-scans.com"
        const val SEPARATOR = "|"

        /** Long enough to serve detail and chapters together, short enough to see new chapters. */
        const val SERIES_CACHE_MS = 60_000L

        fun pageOf(offset: Int, limit: Int): Int = offset / limit.coerceAtLeast(1) + 1
    }
}

private fun OrionScansSource.statusValue(status: StatusFilter): String? = when (status) {
    StatusFilter.ANY -> null
    StatusFilter.ONGOING -> "ONGOING"
    StatusFilter.COMPLETED -> "COMPLETED"
    StatusFilter.HIATUS -> "HIATUS"
    StatusFilter.CANCELLED -> "CANCELLED"
}

/**
 * Prose novels share the catalogue but have no pages, so they never reach the UI. Neither does a
 * series the catalogue reports with no chapters: it would open to an empty profile.
 */
internal fun List<OrionPostDto>.toSourceManhwa(): List<SourceManhwa> =
    filter { !it.seriesType.equals("NOVEL", ignoreCase = true) }
        .filter { it.slug.isNotBlank() }
        .filter { it.chapters.isNotEmpty() }
        .map { it.toSourceManhwa() }

internal fun OrionPostDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = slug,
    title = postTitle.trim(),
    // The synopsis lives on the series route only, so it arrives with the detail.
    description = null,
    coverUrl = featuredImage?.takeIf { it.isNotBlank() },
    year = null,
    status = seriesStatus,
    genres = genres.toSourceGenres(),
)

internal fun OrionPostDetailDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = slug,
    title = postTitle.trim(),
    description = postContent?.let(::stripHtml)?.takeIf { it.isNotBlank() },
    coverUrl = featuredImage?.takeIf { it.isNotBlank() },
    year = yearOf(releaseDate.orEmpty()),
    status = seriesStatus,
    genres = genres.toSourceGenres(),
    aliases = aliasList(alternativeTitles),
)

private fun List<OrionGenreDto>.toSourceGenres(): List<SourceGenre> = mapNotNull { genre ->
    val name = genre.name.trim()
    if (name.isBlank() || genre.id == 0) null else SourceGenre(genre.id.toString(), name)
}

/** The reader publishes image order as a number, not as the array's own order. */
internal fun OrionChapterPageDto.toPageImages(): List<PageImage> = images
    .sortedBy { it.order }
    .filter { it.url.isNotBlank() }
    .mapIndexed { index, image ->
        PageImage(
            index = index,
            url = image.url,
            width = image.width ?: 0,
            height = image.height ?: 0,
        )
    }

/** Joins a chapter to its series in the one remote id the source stores. */
internal const val ORION_SEPARATOR = "|"

/**
 * A chapter is addressable only together with its series, and a coin-locked chapter without an
 * account has no readable pages, so it is marked external and dropped from the opened profile.
 */
internal fun OrionChapterDto.toSourceChapter(seriesSlug: String): SourceChapter? {
    if (slug.isBlank()) return null
    // Modules are numbered; a chapter with no number keeps its own name when it has one.
    val label = number?.let { ChapterLabel(formatNumber(it), it) }
        ?: chapterLabel(title.orEmpty())
        ?: chapterLabel(slug.replace('-', ' '))
        ?: return null
    return SourceChapter(
        remoteId = "$seriesSlug$ORION_SEPARATOR$slug",
        numberText = label.numberText,
        sortKey = label.sortKey,
        title = title?.takeIf { it.isNotBlank() },
        language = "en",
        // The count arrives with the images on first read.
        pageCount = 0,
        externalOnly = isPermanentlyLocked || (isLocked && !isAccessible),
    )
}
