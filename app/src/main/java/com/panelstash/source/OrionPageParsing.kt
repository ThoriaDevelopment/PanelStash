package com.panelstash.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The series page of Orion Scans is server-rendered HTML: the catalogue API has no detail
 * endpoint, and the chapter list is only published inside an Astro island's props. This file
 * turns that markup into the plain data the source module needs, and nothing outside it knows
 * the site is rendered rather than queried.
 *
 * Astro serialises a prop as `[type, value]`, where 0 is a plain value, 1 an array and 2 a map,
 * nesting at every level. [decodeAstro] unwraps that so the rest of the code sees ordinary JSON.
 */
internal data class OrionSeries(
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val status: String?,
    val year: Int?,
    val genres: List<SourceGenre> = emptyList(),
    val chapters: List<SourceChapter>,
)

internal fun parseOrionSeries(html: String, seriesSlug: String): OrionSeries {
    val detail = islandProps(html, "SeriesDescriptionIsland")?.objectOrNull("post")
    val panel = islandProps(html, "SeriesChaptersPanelIsland")
    val meta = detail ?: panel?.objectOrNull("post")

    val title = meta?.stringOrNull("postTitle")?.trim()
    // A page without the title is a redirect, a challenge or a layout change, not a series.
    if (title.isNullOrBlank()) throw SourceError.Transient()

    return OrionSeries(
        title = title,
        description = meta.stringOrNull("postContent")?.let(::stripHtml)?.takeIf { it.isNotBlank() },
        coverUrl = meta.stringOrNull("featuredImage"),
        status = meta.stringOrNull("seriesStatus"),
        year = meta.stringOrNull("releaseDate")?.let(::yearOf),
        genres = meta.genresOf(),
        chapters = panel.chaptersOf(seriesSlug),
    )
}

/** The genres the series page files a title under, as chip-shaped names. */
private fun JsonObject?.genresOf(): List<SourceGenre> =
    this?.arrayOrNull("genres").orEmpty().mapNotNull { entry ->
        val genre = entry as? JsonObject ?: return@mapNotNull null
        val name = genre.stringOrNull("name")?.trim() ?: return@mapNotNull null
        if (name.isBlank()) return@mapNotNull null
        val id = genre.stringOrNull("id") ?: name
        SourceGenre(id = id, name = name)
    }

private fun JsonObject?.chaptersOf(seriesSlug: String): List<SourceChapter> =
    this?.arrayOrNull("initialChap").orEmpty().mapNotNull { entry ->
        val chapter = entry as? JsonObject ?: return@mapNotNull null
        val number = chapter.doubleOrNull("number") ?: return@mapNotNull null
        val slug = chapter.stringOrNull("slug") ?: return@mapNotNull null
        val locked = chapter.booleanOrNull("isLocked") ?: false
        val accessible = chapter.booleanOrNull("isAccessible") ?: true
        val permanent = chapter.booleanOrNull("isPermanentlyLocked") ?: false
        SourceChapter(
            remoteId = "$seriesSlug|$slug",
            numberText = formatNumber(number),
            sortKey = number,
            title = chapter.stringOrNull("title"),
            language = "en",
            // The count arrives with the images on first read.
            pageCount = 0,
            // Coin-locked chapters have no readable pages without an account.
            externalOnly = permanent || (locked && !accessible),
        )
    }

/** The page images of one chapter, in reading order, as published in the reader markup. */
internal fun orionPageImages(html: String, seriesSlug: String): List<PageImage> {
    val prefix = "$STORAGE/public/upload/series/$seriesSlug/"
    // Excludes the quote characters an attribute can be wrapped in, so a URL stops at its end.
    val pattern = Regex(Regex.escape(prefix) + """[^"'\s<>\\&]+""")
    val urls = pattern.findAll(html)
        .map { it.value }
        .distinct()
        // The same directory also holds banners and logos; pages are the numbered files.
        .filter { PAGE_FILE in it }
        .toList()
    return urls.sortedBy { pageNumberOf(it) }
        .mapIndexed { index, url -> PageImage(index = index, url = url) }
}

/** Finds the decoded props of one named Astro island, or null when the island is absent. */
internal fun islandProps(html: String, island: String): JsonObject? {
    val marker = html.indexOf("&quot;$island&quot;")
    if (marker < 0) return null
    // The name sits in the island's `opts`, after `props`, so read back from the element itself.
    val tagStart = html.lastIndexOf("<astro-island", marker)
    if (tagStart < 0) return null
    val propsAt = html.indexOf(PROPS_ATTRIBUTE, tagStart)
    if (propsAt < 0 || propsAt > marker) return null
    val start = propsAt + PROPS_ATTRIBUTE.length
    // Astro escapes quotes inside the attribute, so the next literal quote closes the value.
    val end = html.indexOf('"', start)
    if (end < 0) return null
    return try {
        decodeAstro(Network.json.parseToJsonElement(htmlUnescape(html.substring(start, end))))
            as? JsonObject
    } catch (error: Exception) {
        // A shape change is "this page cannot be read", handled by the caller, never a crash.
        null
    }
}

internal fun decodeAstro(element: JsonElement): JsonElement = when (element) {
    is JsonArray -> {
        val tag = (element.getOrNull(0) as? JsonPrimitive)?.intOrNull
        if (element.size == 2 && tag != null) {
            when (tag) {
                0 -> decodeAstro(element[1])
                1 -> JsonArray((element[1] as? JsonArray)?.map { decodeAstro(it) }.orEmpty())
                2 -> JsonObject(astroEntries(element[1]))
                else -> element[1]
            }
        } else {
            JsonArray(element.map { decodeAstro(it) })
        }
    }

    is JsonObject -> JsonObject(element.mapValues { decodeAstro(it.value) })
    else -> element
}

private fun astroEntries(value: JsonElement): Map<String, JsonElement> =
    (value as? JsonArray).orEmpty()
        .mapNotNull { pair ->
            val entry = pair as? JsonArray ?: return@mapNotNull null
            val key = (entry.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            key to decodeAstro(entry.getOrNull(1) ?: JsonNull)
        }
        .toMap()

internal fun htmlUnescape(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

/** "2025-03-01T00:00:00.000Z" -> 2025. The site leaves the field empty on many series. */
internal fun yearOf(date: String): Int? =
    Regex("""^(\d{4})""").find(date.trim())?.groupValues?.get(1)?.toIntOrNull()

private fun pageNumberOf(url: String): Int =
    Regex("""/page-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

private const val STORAGE = "https://storage.orion-scans.com"
private const val PAGE_FILE = "/page-"
private const val PROPS_ATTRIBUTE = " props=\""
