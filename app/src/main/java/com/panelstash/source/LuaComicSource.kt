package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Lua Comic, a manhwa catalogue behind a documented-by-inspection JSON API.
 *
 * Identity: a series is its slug, because that is what both the site and the API address it by,
 * and a chapter is the series slug joined to the chapter slug (`"<series>|<chapter>"`). The
 * numeric series id is only needed to ask for the chapter list, so it is read from the detail
 * route and cached rather than stored in the remote id.
 *
 * Provenance:
 *
 *   GET api.luacomic.org/query?page=&perPage=             catalogue, newest first
 *   GET api.luacomic.org/query?query_string=&page=        title search
 *   GET api.luacomic.org/series/{slug}                    detail, aliases, year, status
 *   GET api.luacomic.org/chapter/query?page=&series_id=   chapters, with the price that locks them
 *   GET luacomic.org/series/{slug}/{chapterSlug}          reader page, pages as preloaded images
 *
 * Chapters carrying a price are coin-locked on the site; they are listed as external so they
 * never open into an empty reader. The catalogue takes no sort parameter and publishes no
 * genres, so this source offers neither.
 */
class LuaComicSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "luacomic"

    override val name = "Lua Comic"

    override val description = "Curated manhwa catalogue, with the newest chapters coin-locked."

    override val capabilities = SourceCapabilities(
        // /query ignores the ordering parameter it accepts, so only its own newest-first list.
        sorts = setOf(BrowseSort.LATEST),
    )

    /** Politeness: the API is small JSON, but the reader page is a rendered document. */
    private val limiter = RateLimiter(300)

    /** Detail and chapter list both need the series id, so one fetch serves both. */
    @Volatile
    private var lastSeries: Pair<String, LuaSeriesDetailDto>? = null
    @Volatile
    private var lastSeriesAt = 0L

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val response = api(
            "/query?page=${pageOf(offset, limit)}&perPage=$limit",
            LuaQueryResponse.serializer(),
        )
        return response.data.mapNotNull { it.toSourceManhwa() }.take(limit)
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        val response = api(
            "/query?query_string=${encodeQuery(query.trim())}&page=${pageOf(offset, limit)}&perPage=$limit",
            LuaQueryResponse.serializer(),
        )
        return response.data.mapNotNull { it.toSourceManhwa() }.take(limit)
    }

    /** The API has a tag route, but it answers empty and no title carries one. */
    override suspend fun genres(): List<SourceGenre> = emptyList()

    override suspend fun manhwa(remoteId: String): SourceManhwa = seriesDetail(remoteId).toSourceManhwa()

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val seriesId = seriesDetail(manhwaRemoteId).id
        val rows = mutableListOf<LuaChapterDto>()
        var page = 1
        while (true) {
            val response = api(
                "/chapter/query?page=$page&perPage=$CHAPTER_PAGE&series_id=$seriesId",
                LuaChapterQueryResponse.serializer(),
            )
            if (response.data.isEmpty()) break
            rows += response.data
            if (page >= response.meta.lastPage) break
            page++
        }
        return dedupeChapters(rows.mapNotNull { it.toSourceChapter(manhwaRemoteId) })
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val (slug, chapterSlug) = parseLuaChapterId(chapterRemoteId) ?: throw SourceError.Unavailable()
        val body = call("$LUA_SITE/series/$slug/$chapterSlug", json = false)
            ?: throw SourceError.Unavailable()
        val images = parseLuaPages(body, slug)
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = LUA_CDN, images = images)
    }

    private suspend fun seriesDetail(slug: String): LuaSeriesDetailDto {
        val now = System.currentTimeMillis()
        lastSeries?.let { (cachedSlug, series) ->
            if (cachedSlug == slug && now - lastSeriesAt < SERIES_TTL_MS) return series
        }
        val series = api("/series/$slug", LuaSeriesDetailDto.serializer())
        lastSeries = slug to series
        lastSeriesAt = now
        return series
    }

    private suspend fun <T> api(path: String, deserializer: DeserializationStrategy<T>): T {
        val body = call("$LUA_API$path") ?: throw SourceError.Unavailable()
        return try {
            Network.json.decodeFromString(deserializer, body)
        } catch (error: Exception) {
            // A shape change is "this source is not usable right now", never a crash.
            throw SourceError.Transient(error)
        }
    }

    /** Returns null only for 404, so a retired chapter can be told apart from a broken call. */
    private suspend fun call(url: String, json: Boolean = true): String? {
        if (gate.isBlocked()) throw SourceError.Banned()
        limiter.acquire()
        val response = try {
            client.get(url) {
                header("Accept", if (json) "application/json" else "text/html,application/xhtml+xml")
                header("Referer", "$LUA_SITE/")
                header("Origin", LUA_SITE)
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

    private fun pageOf(offset: Int, limit: Int): Int = offset / limit.coerceAtLeast(1) + 1

    private companion object {
        /** Long enough to serve detail and chapters together, short enough to see new chapters. */
        const val SERIES_TTL_MS = 60_000L

        /** The API pages chapters; 100 is accepted and halves the round trips of a long series. */
        const val CHAPTER_PAGE = 100
    }
}

internal const val LUA_API = "https://api.luacomic.org"
internal const val LUA_SITE = "https://luacomic.org"
internal const val LUA_CDN = "https://media.luacomic.org"

private const val LUA_SEPARATOR = "|"

private val LUA_CHAPTER_NUMBER = Regex("""Chapter\s+([0-9][0-9.]*)""")

/** A priced chapter is coin-locked, so listing it as readable would open an empty reader. */
internal fun LuaChapterDto.toSourceChapter(seriesSlug: String): SourceChapter? {
    if (slug.isBlank()) return null
    val number = name?.let { LUA_CHAPTER_NUMBER.find(it)?.groupValues?.get(1) }
        ?: slug.removePrefix("chapter-").takeIf { it.matches(Regex("""[0-9][0-9.]*""")) }
    // A chapter the site names instead of numbering (a prologue, a side story) keeps its name.
    val label = number?.let { ChapterLabel(it, it.toDoubleOrNull()) }
        ?: chapterLabel(name.orEmpty())
        ?: chapterLabel(slug.replace('-', ' '))
        ?: return null
    return SourceChapter(
        remoteId = "$seriesSlug$LUA_SEPARATOR$slug",
        numberText = label.numberText,
        sortKey = label.sortKey,
        title = title?.trim()?.takeIf { it.isNotEmpty() },
        language = "en",
        // The count arrives with the images on first read.
        pageCount = 0,
        externalOnly = price > 0.0,
    )
}

internal fun parseLuaChapterId(remoteId: String): Pair<String, String>? {
    val parts = remoteId.split(LUA_SEPARATOR)
    if (parts.size != 2) return null
    val series = parts[0].trim()
    val chapter = parts[1].trim()
    if (series.isEmpty() || chapter.isEmpty()) return null
    return series to chapter
}

internal fun LuaSeriesDto.toSourceManhwa(): SourceManhwa? {
    if (slug.isBlank() || title.isBlank()) return null
    return SourceManhwa(
        remoteId = slug,
        title = title.trim(),
        description = description?.let(::stripHtml)?.takeIf { it.isNotBlank() },
        coverUrl = thumbnail?.takeIf { it.isNotBlank() },
        year = releaseYear,
        status = status?.trim()?.takeIf { it.isNotEmpty() },
        // The catalogue carries the chapter tally, which decides the complete copy of a title.
        chapterCount = meta?.chaptersCount?.contentOrNull?.toIntOrNull(),
        aliases = luaAliases(alternativeNames),
    )
}

internal fun LuaSeriesDetailDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = slug,
    title = title.trim(),
    description = description?.let(::stripHtml)?.takeIf { it.isNotBlank() },
    coverUrl = thumbnail?.takeIf { it.isNotBlank() },
    year = releaseYear,
    status = status?.trim()?.takeIf { it.isNotEmpty() },
    aliases = luaAliases(alternativeNames),
)

/** Titles that appear on three sitess under different names are matched by these lines. */
internal fun luaAliases(raw: String?): List<String> = aliasList(raw)

/**
 * Each chapter lives in one upload folder, and a page file is numbered inside it. Neither the
 * preload tags nor the lazy images list the whole chapter — the reader only marks the first few
 * eager — so pages are collected from every mention of the folder and ordered by their number.
 */
private fun luaPagePattern(seriesSlug: String) = Regex(
    """https://media\.luacomic\.org/file/[^"\s]+/uploads/series/${Regex.escape(seriesSlug)}/[^"\s]+/(\d+)\.(?:webp|jpg|jpeg|png)(?:\.jpg)?""",
)

internal fun parseLuaPages(html: String, seriesSlug: String): List<PageImage> {
    val pages = LinkedHashMap<Int, String>()
    for (page in luaPagePattern(seriesSlug).findAll(html)) {
        val number = page.groupValues[1].toIntOrNull() ?: continue
        pages.putIfAbsent(number, page.value)
    }
    return pages.entries
        .sortedBy { it.key }
        .mapIndexed { index, entry -> PageImage(index = index, url = entry.value) }
}

/** Percent-encodes a query value, leaving the unreserved characters alone. */
internal fun encodeQuery(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return buildString(bytes.size) {
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            val unreserved = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '.' || char == '_' || char == '~'
            if (unreserved) {
                append(char)
            } else {
                append('%')
                append(HEX[code shr 4])
                append(HEX[code and 0x0F])
            }
        }
    }
}

private const val HEX = "0123456789ABCDEF"
