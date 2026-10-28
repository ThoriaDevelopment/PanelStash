package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException

/**
 * MangaDex query shapes, kept apart from the request code so the API contract can be
 * asserted in unit tests without a network.
 *
 * Two limits are worth remembering because the API enforces them with a 400, not a clamp:
 * `/chapter` accepts at most 100 per page, while `/manga/{id}/feed` accepts 500. The feed is
 * therefore the chapter-list endpoint here.
 */
internal object MangaDexApi {

    const val BASE = "https://api.mangadex.org"

    /** Only Korean-origin works: this is a manhwa reader, not a manga/anime one. */
    const val MANHWA_ORIGINAL_LANGUAGE = "ko"

    /** Verified against the live API: 500 is rejected on /chapter, accepted on the feed. */
    const val FEED_PAGE_SIZE = 500

    /** Offset + limit must stay under 10,000 on this API. */
    const val MAX_OFFSET = 10_000

    fun browseOrder(sort: BrowseSort): String = when (sort) {
        BrowseSort.LATEST -> "latestUploadedChapter"
        BrowseSort.POPULAR -> "followedCount"
        BrowseSort.TOP_RATED -> "rating"
    }

    fun chapterFeedPath(manhwaRemoteId: String): String = "/manga/$manhwaRemoteId/feed"

    /** MangaDex spells publication status in lower case. */
    fun statusValue(status: StatusFilter): String? = when (status) {
        StatusFilter.ANY -> null
        StatusFilter.ONGOING -> "ongoing"
        StatusFilter.COMPLETED -> "completed"
        StatusFilter.HIATUS -> "hiatus"
        StatusFilter.CANCELLED -> "cancelled"
    }

    /** The catalogue page: manhwa with English chapters, safe or suggestive. */
    fun browseParams(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<Pair<String, Any>> =
        buildList {
            add("limit" to limit)
            add("offset" to offset)
            add("includes[]" to "cover_art")
            add("contentRating[]" to "safe")
            if (!filter.safeOnly) add("contentRating[]" to "suggestive")
            add("availableTranslatedLanguage[]" to "en")
            add("hasAvailableChapters" to "true")
            add("originalLanguage[]" to MANHWA_ORIGINAL_LANGUAGE)
            if (!tagId.isNullOrBlank()) add("includedTags[]" to tagId)
            filter.excludedTagIds.forEach { add("excludedTags[]" to it) }
            filter.year?.let { add("year" to it) }
            statusValue(filter.status)?.let { add("status[]" to it) }
            add("order[${browseOrder(sort)}]" to "desc")
        }

    /** Title search, with the same manhwa-only and readable-here filters as browse. */
    fun searchParams(query: String, offset: Int, limit: Int): List<Pair<String, Any>> =
        buildList {
            add("limit" to limit)
            add("offset" to offset)
            add("includes[]" to "cover_art")
            add("contentRating[]" to "safe")
            add("contentRating[]" to "suggestive")
            add("availableTranslatedLanguage[]" to "en")
            add("originalLanguage[]" to MANHWA_ORIGINAL_LANGUAGE)
            add("title" to query)
            add("order[followedCount]" to "desc")
        }

    /**
     * External chapters are requested rather than filtered out. MangaDex keeps licensed English
     * chapters as links to the official publisher, which is why a title can advertise English
     * translations and still return nothing here with `includeExternalUrl=0`.
     */
    fun chapterFeedParams(language: String, offset: Int): List<Pair<String, Any>> = listOf(
        "translatedLanguage[]" to language,
        "limit" to FEED_PAGE_SIZE,
        "offset" to offset,
        "order[chapter]" to "asc",
    )
}

class MangaDexSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "mangadex"
    override val name = "MangaDex"
    override val description = "Largest catalogue. Manhwa with English chapters."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST, BrowseSort.POPULAR, BrowseSort.TOP_RATED),
        genres = true,
        status = true,
        year = true,
        safeOnly = true,
        excludedGenres = true,
    )

    /** ~4.5 requests/sec, under the documented 5/sec per-IP ceiling. */
    private val apiLimiter = RateLimiter(220)

    /** /at-home/server is limited to 40/minute; 1.6s spacing keeps us under it. */
    private val atHomeLimiter = RateLimiter(1_600)

    @Volatile
    private var cachedGenres: List<SourceGenre>? = null

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val body = request("/manga") {
            MangaDexApi.browseParams(sort, tagId, filter, offset, limit).forEach { (key, value) ->
                parameter(key, value)
            }
        }
        return Network.json.decodeFromString<MangaListResponse>(body).data.map { it.toSourceManhwa() }
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        val body = request("/manga") {
            MangaDexApi.searchParams(query, offset, limit).forEach { (key, value) ->
                parameter(key, value)
            }
        }
        return Network.json.decodeFromString<MangaListResponse>(body).data.map { it.toSourceManhwa() }
    }

    override suspend fun genres(): List<SourceGenre> {
        cachedGenres?.let { return it }
        val body = request("/manga/tag")
        val genres = Network.json.decodeFromString<TagListResponse>(body).data
            .filter { it.attributes.group == "genre" }
            .mapNotNull { tag ->
                val name = tag.attributes.name["en"] ?: tag.attributes.name.values.firstOrNull()
                if (name.isNullOrBlank() || tag.id.isBlank()) null else SourceGenre(tag.id, name)
            }
            .sortedBy { it.name }
        cachedGenres = genres
        return genres
    }

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val body = request("/manga/$remoteId") {
            parameter("includes[]", "cover_art")
        }
        val dto = Network.json.decodeFromString<MangaDetailResponse>(body).data
            ?: throw SourceError.Unavailable()
        return dto.toSourceManhwa()
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val collected = mutableListOf<SourceChapter>()
        var offset = 0
        while (offset < MangaDexApi.MAX_OFFSET) {
            val body = request(MangaDexApi.chapterFeedPath(manhwaRemoteId)) {
                MangaDexApi.chapterFeedParams(language, offset).forEach { (key, value) ->
                    parameter(key, value)
                }
            }
            val page = Network.json.decodeFromString<ChapterListResponse>(body).data
            collected += page.mapNotNull { it.toSourceChapter() }
            if (page.size < MangaDexApi.FEED_PAGE_SIZE) break
            offset += MangaDexApi.FEED_PAGE_SIZE
        }
        return dedupeChapters(collected, includeExternal = true)
    }

    /**
     * An at-home node can disappear between two requests, so one node failure re-resolves the
     * base URL instead of failing the read.
     */
    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet =
        runCatching { pageSet(chapterRemoteId, quality) }
            .getOrElse { error ->
                when (error) {
                    is SourceError.Transient, is SourceError.Offline -> pageSet(chapterRemoteId, quality)
                    else -> throw error
                }
            }

    private suspend fun pageSet(chapterRemoteId: String, quality: PageQuality): PageSet {
        atHomeLimiter.acquire()
        val body = request("/at-home/server/$chapterRemoteId")
        val dto = Network.json.decodeFromString<AtHomeResponse>(body)
        val files = when (quality) {
            PageQuality.ORIGINAL -> dto.chapter.data
            PageQuality.DATA_SAVER -> dto.chapter.dataSaver
        }
        if (files.isEmpty()) throw SourceError.Unavailable()
        val images = files.mapIndexed { index, fileName ->
            PageImage(
                index = index,
                url = "${dto.baseUrl}/${quality.apiValue}/${dto.chapter.hash}/$fileName",
            )
        }
        return PageSet(baseUrl = dto.baseUrl, images = images)
    }

    private suspend fun request(path: String, configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): String {
        if (gate.isBlocked()) throw SourceError.Banned()
        apiLimiter.acquire()
        val response = try {
            client.get("${MangaDexApi.BASE}$path") { configure() }
        } catch (error: IOException) {
            throw SourceError.Offline(error)
        }

        return when {
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers["X-RateLimit-Retry-After"]?.toLongOrNull()
                throw SourceError.RateLimited(retryAfter)
            }

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

}
