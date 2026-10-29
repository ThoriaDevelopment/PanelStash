package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * Flame Comics, a manhwa-first scanlation site rendered by Next.js.
 *
 * Identity: a series is its numeric id, and a chapter is a token scoped to that series, so the two
 * are joined into one string (`"<seriesId>|<token>"`). Nothing outside this module parses them.
 *
 * The catalogue cannot be paginated or searched on the server: `/latest` returns every title with
 * its three newest chapters, and the site filters in the browser. A 110 KB payload is therefore
 * fetched once per session window and the pages are sliced from it, which is also what makes a
 * status filter and a title search possible without another request. Ordering by newest chapter
 * uses those release dates, so "Latest" means the same thing here as it does on the site.
 *
 * Genres come from `/browse`, the other half of the catalogue: it publishes each title's
 * categories but no release dates, so it is fetched only when the genre list is asked for or a
 * genre filter is applied, and joined to the ordered catalogue by series id.
 *
 * Novel and web-novel entries share the catalogue and have no pages, so they are dropped rather
 * than shown as unreadable.
 */
class FlameComicsSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "flamecomics"

    override val name = "Flame Comics"

    override val description = "Scanlation site. Manhwa-heavy catalogue, newest chapters free."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST, BrowseSort.POPULAR),
        genres = true,
        titleGenres = true,
        status = true,
        year = false,
        safeOnly = false,
        excludedGenres = true,
    )

    /** Politeness: a page can cost a megabyte of markup, so leave more room than the JSON APIs. */
    private val limiter = RateLimiter(400)

    /**
     * The build id namespaces the JSON routes and changes on every deploy. It is discovered from
     * the first HTML page read and then reused until a route answers 404, which means it is stale.
     */
    @Volatile
    private var buildId: String? = null

    /** Every title with its newest chapters, shared by browse and search for a few minutes. */
    @Volatile
    private var catalogue: Catalogue? = null

    /** Series id to genre names, read from /browse and only when a genre is actually in play. */
    @Volatile
    private var genreCache: GenreIndex? = null

    /** Detail and chapter list come from one page, so both calls share a single fetch. */
    @Volatile
    private var lastSeries: Pair<String, FlameSeriesPage>? = null
    @Volatile
    private var lastSeriesAt = 0L

    private data class Catalogue(val series: List<FlameSeriesDto>, val at: Long)

    private data class GenreIndex(
        val bySeries: Map<Int, Set<String>>,
        val genres: List<SourceGenre>,
        val at: Long,
    )

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val tag = tagId?.takeIf { it.isNotBlank() }
        // The genre index costs a second catalogue fetch, so it is only read when it can matter.
        val categories = if (tag != null || filter.excludedTagIds.isNotEmpty()) {
            genreIndex().bySeries
        } else {
            emptyMap()
        }
        return catalogue().flameCatalogue(sort, filter, tag, categories).drop(offset).take(limit)
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        return catalogue().flameSearch(query).drop(offset).take(limit)
    }

    /** Every category the catalogue publishes, alphabetic. Filtering happens here, not server-side. */
    override suspend fun genres(): List<SourceGenre> = genreIndex().genres

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val page = seriesPage(remoteId.toIntOrNull() ?: throw SourceError.Unavailable())
        return (page.series ?: throw SourceError.Unavailable()).toSourceManhwa()
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> {
        val seriesId = manhwaRemoteId.toIntOrNull() ?: throw SourceError.Unavailable()
        return dedupeChapters(seriesPage(seriesId).chapters.mapNotNull { it.toSourceChapter(seriesId) })
    }

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val (seriesId, token) = parseFlameChapterId(chapterRemoteId) ?: throw SourceError.Unavailable()
        val chapter = pageProps(
            dataPath = "/series/$seriesId/$token.json",
            htmlPath = "/series/$seriesId/$token",
            deserializer = FlameChapterPage.serializer(),
        ).chapter ?: throw SourceError.Unavailable()
        val images = flamePageImages(seriesId, token, chapter.images)
        // A chapter whose pages the site has pulled renders empty, which is terminal here.
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(baseUrl = FLAME_CDN, images = images)
    }

    private suspend fun catalogue(): List<FlameSeriesDto> {
        val now = System.currentTimeMillis()
        catalogue?.let { cached ->
            if (now - cached.at < CATALOGUE_TTL_MS) return cached.series
        }
        val page = pageProps(
            dataPath = "/latest.json",
            htmlPath = "/latest",
            deserializer = FlameCataloguePage.serializer(),
        )
        val series = page.allSeries.filter { it.seriesId > 0 && it.title.isNotBlank() }
        if (series.isEmpty()) throw SourceError.Transient()
        catalogue = Catalogue(series, now)
        return series
    }

    private suspend fun genreIndex(): GenreIndex {
        val now = System.currentTimeMillis()
        genreCache?.let { cached ->
            if (now - cached.at < CATALOGUE_TTL_MS) return cached
        }
        val page = pageProps(
            dataPath = "/browse.json",
            htmlPath = "/browse",
            deserializer = FlameBrowsePage.serializer(),
        )
        val rows = page.series.filter { it.seriesId > 0 && it.categories.isNotEmpty() }
        val index = GenreIndex(
            bySeries = rows.flameGenreIndex(),
            genres = rows.flameGenreNames(),
            at = now,
        )
        genreCache = index
        return index
    }

    private suspend fun seriesPage(seriesId: Int): FlameSeriesPage {
        val key = seriesId.toString()
        val now = System.currentTimeMillis()
        lastSeries?.let { (cachedKey, page) ->
            if (cachedKey == key && now - lastSeriesAt < SERIES_TTL_MS) return page
        }
        val page = pageProps(
            dataPath = "/series/$key.json",
            htmlPath = "/series/$key",
            deserializer = FlameSeriesPage.serializer(),
        )
        lastSeries = key to page
        lastSeriesAt = now
        return page
    }

    /**
     * Reads one page's props, preferring the JSON route and falling back to the markup. A JSON
     * 404 is either a retired build id or a page that does not exist; the markup answers which,
     * and refreshing the build id from it keeps the next call cheap.
     */
    private suspend fun <T> pageProps(
        dataPath: String,
        htmlPath: String,
        deserializer: DeserializationStrategy<T>,
    ): T {
        buildId?.let { build ->
            val body = call("/_next/data/$build$dataPath", JSON)
            if (body != null) return decodeProps(deserializer, body)
            buildId = null
        }

        val root = nextData(request(htmlPath, HTML)) ?: throw SourceError.Transient()
        (root["buildId"] as? JsonPrimitive)?.contentOrNull?.let { buildId = it }
        return decodeProps(deserializer, root.toString())
    }

    private suspend fun request(path: String, accept: String): String =
        call(path, accept) ?: throw SourceError.Unavailable()

    /** Returns null only for 404, so a missing page can be told apart from a broken one. */
    private suspend fun call(path: String, accept: String): String? {
        if (gate.isBlocked()) throw SourceError.Banned()
        limiter.acquire()
        val response = try {
            client.get("$FLAME_BASE$path") {
                header(HttpHeaders.Accept, accept)
                header("Referer", "$FLAME_BASE/")
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

    private fun <T> decodeProps(deserializer: DeserializationStrategy<T>, body: String): T = try {
        val root = Network.json.parseToJsonElement(body).jsonObject
        // The JSON route answers with pageProps at the top level; the markup nests them in props.
        val props = root["pageProps"]?.jsonObject
            ?: root["props"]?.jsonObject?.get("pageProps")?.jsonObject
            ?: throw SourceError.Transient()
        Network.json.decodeFromJsonElement(deserializer, props)
    } catch (error: SourceError) {
        throw error
    } catch (error: Exception) {
        // A shape change on a rendered site is "this source is not usable right now", not a crash.
        throw SourceError.Transient(error)
    }

    private companion object {
        const val CATALOGUE_TTL_MS = 10 * 60 * 1000L

        /** Long enough to serve detail and chapters together, short enough to see new chapters. */
        const val SERIES_TTL_MS = 60_000L

        const val JSON = "application/json"
        const val HTML = "text/html,application/xhtml+xml"

        /** Next.js writes its page data into this script tag, one line, JSON-escaped. */
        val NEXT_DATA = Regex(
            """<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }

    private fun nextData(html: String): JsonObject? = try {
        NEXT_DATA.find(html)?.groupValues?.get(1)
            ?.let { Network.json.parseToJsonElement(it) as? JsonObject }
    } catch (error: Exception) {
        null
    }
}

internal const val FLAME_BASE = "https://flamecomics.xyz"
internal const val FLAME_CDN = "https://cdn.flamecomics.xyz"

private const val FLAME_SEPARATOR = "|"

/** Novels share the catalogue and have no pages, so they never reach the UI. */
private val FLAME_NON_PAGES = setOf("novel", "web novel")

internal fun FlameSeriesDto.isFlameReadable(): Boolean =
    seriesId > 0 &&
        title.isNotBlank() &&
        type.orEmpty().trim().lowercase() !in FLAME_NON_PAGES

internal fun FlameSeriesDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = seriesId.toString(),
    title = title.trim(),
    description = description?.let(::stripHtml)?.takeIf { it.isNotBlank() },
    coverUrl = cover?.takeIf { it.isNotBlank() }
        ?.let { "$FLAME_CDN/uploads/images/series/$seriesId/$it" },
    year = year,
    status = status,
    // Only the detail and /browse routes carry categories; /latest rows arrive without them.
    genres = categories.map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { SourceGenre(id = it, name = it) },
)

/**
 * The catalogue is the whole site, so ordering, filtering and paging all happen here. "Latest"
 * uses the newest chapter a title published, which is what the site's own list goes by.
 *
 * [categories] is the series id to genres map from /browse. It is empty when no genre is in play,
 * and then [tagId] and [BrowseFilter.excludedTagIds] are ignored rather than excluding everything.
 */
internal fun List<FlameSeriesDto>.flameCatalogue(
    sort: BrowseSort,
    filter: BrowseFilter,
    tagId: String? = null,
    categories: Map<Int, Set<String>> = emptyMap(),
): List<SourceManhwa> = filter {
    it.isFlameReadable() &&
        flameStatusMatches(filter.status, it.status) &&
        it.flameMatchesGenres(tagId, filter.excludedTagIds, categories)
}
    .let { rows ->
        when (sort) {
            BrowseSort.POPULAR -> rows.sortedByDescending { it.likes }
            // Top rated is not offered by this source; newest is the sane order if it arrives anyway.
            else -> rows.sortedByDescending { it.lastChapterAt() }
        }
    }
    .map { it.toSourceManhwa() }

/** A genre filter needs the /browse map; without it, a tag would match nothing at all. */
internal fun FlameSeriesDto.flameMatchesGenres(
    tagId: String?,
    excluded: Set<String>,
    categories: Map<Int, Set<String>>,
): Boolean {
    if (tagId == null && excluded.isEmpty()) return true
    val tags = categories[seriesId].orEmpty()
    if (tagId != null && tagId !in tags) return false
    return tags.none { it in excluded }
}

/** Series id to its genres, dropping empty names and titles the site never categorised. */
internal fun List<FlameSeriesDto>.flameGenreIndex(): Map<Int, Set<String>> = buildMap {
    for (series in this@flameGenreIndex) {
        val tags = series.categories.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (tags.isNotEmpty()) put(series.seriesId, tags)
    }
}

/** Every distinct genre the catalogue uses, alphabetic, named as the site names it. */
internal fun List<FlameSeriesDto>.flameGenreNames(): List<SourceGenre> = flatMap { it.categories }
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .sorted()
    .map { SourceGenre(id = it, name = it) }

/** Title search, with the titles that begin with the query ahead of the ones that merely contain it. */
internal fun List<FlameSeriesDto>.flameSearch(query: String): List<SourceManhwa> {
    val wanted = query.trim().lowercase()
    if (wanted.isEmpty()) return flameCatalogue(BrowseSort.LATEST, BrowseFilter.None)
    return filter { it.isFlameReadable() && it.title.lowercase().contains(wanted) }
        .sortedWith(
            compareByDescending<FlameSeriesDto> { it.title.lowercase().startsWith(wanted) }
                .thenByDescending { it.lastChapterAt() },
        )
        .map { it.toSourceManhwa() }
}

/** The site spells its statuses in title case, and offers two the app has no filter for. */
internal fun flameStatusMatches(status: StatusFilter, value: String?): Boolean {
    if (status == StatusFilter.ANY) return true
    val site = value.orEmpty().trim().lowercase()
    return when (status) {
        StatusFilter.ONGOING -> site == "ongoing"
        StatusFilter.COMPLETED -> site == "completed"
        StatusFilter.HIATUS -> site == "hiatus"
        StatusFilter.CANCELLED -> site == "cancelled"
        StatusFilter.ANY -> true
    }
}

private fun FlameSeriesDto.lastChapterAt(): Long =
    chapters.maxOfOrNull { it.releaseDate } ?: lastEdit

internal fun FlameChapterDto.toSourceChapter(seriesId: Int): SourceChapter? {
    if (token.isBlank()) return null
    val raw = chapter?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val number = raw.toDoubleOrNull()
    return SourceChapter(
        remoteId = "$seriesId$FLAME_SEPARATOR$token",
        // "143.00" is chapter 143 to a reader, and keeps its real value for ordering.
        numberText = number?.let(::formatNumber) ?: raw,
        sortKey = number,
        title = chapterTitle?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() },
        language = "en",
        // The count arrives with the images on first read.
        pageCount = 0,
        externalOnly = false,
    )
}

internal fun parseFlameChapterId(remoteId: String): Pair<Int, String>? {
    val parts = remoteId.split(FLAME_SEPARATOR)
    if (parts.size != 2) return null
    val seriesId = parts[0].toIntOrNull() ?: return null
    if (parts[1].isBlank()) return null
    return seriesId to parts[1]
}

/**
 * Pages are files in one CDN folder, keyed by their position. The keys are strings, so they are
 * ordered numerically rather than lexically, or page 10 would follow page 1.
 */
internal fun flamePageImages(
    seriesId: Int,
    token: String,
    images: Map<String, FlameImageDto>,
): List<PageImage> = images.entries
    .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
    .mapNotNull { (_, image) ->
        val name = image.name.trim()
        if (name.isEmpty()) return@mapNotNull null
        PageImage(
            index = 0,
            url = "$FLAME_CDN/uploads/images/series/$seriesId/$token/${encodePathPart(name)}",
            width = image.width,
            height = image.height,
        )
    }
    .mapIndexed { index, page -> page.copy(index = index) }

/** Percent-encodes one path segment, leaving the unreserved characters alone. */
internal fun encodePathPart(value: String): String {
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
                append(HEX_DIGITS[code shr 4])
                append(HEX_DIGITS[code and 0x0F])
            }
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
