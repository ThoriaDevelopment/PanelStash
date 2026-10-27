package com.panelstash.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The series page of Kayn Scans has no JSON endpoint: Next.js streams the series into the page as
 * a flight payload, so this file reads that payload and hands the source plain data. Nothing
 * outside this module knows the page is rendered rather than queried.
 *
 * The payload is pushed into the page as JavaScript string literals, and the site encodes parts
 * of it twice: some objects sit directly in the stream, others are wrapped in one more layer of
 * string escaping. Both copies of the series exist, so every lookup tries the stream as it is and
 * the stream unescaped once, and keeps whichever parses as a JSON object. That is why a shape
 * change degrades to [SourceError.Transient] instead of a wrong answer.
 */
internal data class KaynSeries(
    /** Needed for the chapter-list fallback, which addresses the series by id. */
    val id: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val status: String?,
    /** The full chapter list as published on the page, or empty when the payload omitted it. */
    val chapters: List<SourceChapter>,
)

internal fun parseKaynSeries(html: String, seriesId: String): KaynSeries {
    val stream = kaynFlightStream(html)
    // The page addresses a series by slug; the payload is keyed by id, so the caller passes
    // whichever it has and the lookup accepts both.
    val series = seriesObject(stream, seriesId)
        ?: throw SourceError.Transient()
    val id = series.stringOrNull("id") ?: throw SourceError.Transient()
    val title = series.stringOrNull("title")?.trim().orEmpty()
    // A page without a title is a challenge, a redirect or a layout change, not a series.
    if (title.isBlank()) throw SourceError.Transient()
    return KaynSeries(
        id = id,
        title = title,
        description = series.stringOrNull("description")?.trim()?.takeIf { it.isNotBlank() },
        coverUrl = series.stringOrNull("coverImage")?.let(::kaynUrl),
        status = series.stringOrNull("status"),
        chapters = chaptersIn(stream, id),
    )
}

/** The series object, from whichever copy of the payload parses. */
private fun seriesObject(stream: String, seriesId: String): JsonObject? {
    for (text in listOf(stream, unescape(stream))) {
        val at = text.indexOf(SERIES_MARKER.format(seriesId))
        if (at < 0) continue
        val candidate = balanced(text, at, '{', '}')
        val parsed = candidate?.let(::jsonObjectOrNull)
        if (parsed != null && parsed.contains("title")) return parsed
    }
    return null
}

/**
 * The full chapter list, which the payload publishes next to the series object rather than inside
 * it. A list is only accepted when the series is named shortly before it, so a list belonging to
 * another component can never be mistaken for this series'.
 */
private fun chaptersIn(stream: String, seriesId: String): List<SourceChapter> {
    for (text in listOf(unescape(stream), stream)) {
        var at = text.indexOf(CHAPTERS)
        while (at >= 0) {
            val owner = text.substring(maxOf(0, at - OWNER_WINDOW), at)
            val array = if (owner.contains("\"$seriesId\"")) {
                balanced(text, at + CHAPTERS.length - 1, '[', ']')
            } else {
                null
            }
            val chapters = array?.let(::jsonArrayOrNull)?.mapNotNull(::chapterOf)
            if (!chapters.isNullOrEmpty()) return chapters
            at = text.indexOf(CHAPTERS, at + CHAPTERS.length)
        }
    }
    return emptyList()
}

private fun chapterOf(element: JsonElement): SourceChapter? {
    val chapter = element as? JsonObject ?: return null
    val id = chapter.stringOrNull("id") ?: return null
    val number = chapter.numberOrNull("number") ?: return null
    val locked = chapter.flagOrNull("isLocked") ?: false
    val accessible = chapter.flagOrNull("hasAccess") ?: true
    return SourceChapter(
        remoteId = id,
        numberText = formatNumber(number),
        sortKey = number,
        title = chapter.stringOrNull("title"),
        language = "en",
        // The page count arrives with the page images, on first read.
        pageCount = 0,
        // Coin-gated and early-access chapters cannot be read without an account.
        externalOnly = locked || !accessible,
    )
}

/** Joins every `self.__next_f.push` literal in the page into the flight stream behind it. */
internal fun kaynFlightStream(html: String): String {
    val out = StringBuilder()
    var at = html.indexOf(PUSH)
    while (at >= 0) {
        val start = at + PUSH.length
        val (value, end) = readStringLiteral(html, start)
        out.append(value)
        at = html.indexOf(PUSH, end + 1)
    }
    return out.toString()
}

/** Reads a JavaScript string literal that starts just past its opening quote. */
private fun readStringLiteral(text: String, start: Int): Pair<String, Int> {
    val out = StringBuilder()
    var i = start
    while (i < text.length) {
        val char = text[i]
        if (char == BACKSLASH) {
            val (value, next) = escapeAt(text, i)
            out.append(value)
            i = next
            continue
        }
        if (char == '"') return out.toString() to i
        out.append(char)
        i++
    }
    return out.toString() to text.length
}

/** Unescapes one layer of JavaScript string escaping over a whole text. */
internal fun unescape(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\') {
            val (value, next) = escapeAt(text, i)
            out.append(value)
            i = next
            continue
        }
        out.append(text[i])
        i++
    }
    return out.toString()
}

/** The character one escape sequence stands for, and the index just past it. */
private fun escapeAt(text: String, at: Int): Pair<Char, Int> {
    val next = text.getOrNull(at + 1) ?: return '\\' to at + 1
    if (next == 'u' && at + 6 <= text.length) {
        val code = text.substring(at + 2, at + 6).toIntOrNull(16)
        if (code != null) return code.toChar() to at + 6
    }
    return (ESCAPES[next] ?: next) to at + 2
}

/** The balanced group that starts at [start], or null when it never closes. */
private fun balanced(text: String, start: Int, open: Char, close: Char): String? {
    if (start !in text.indices || text[start] != open) return null
    var depth = 0
    var inString = false
    var escaped = false
    var i = start
    while (i < text.length) {
        val char = text[i]
        when {
            inString -> when {
                escaped -> escaped = false
                char == BACKSLASH -> escaped = true
                char == '"' -> inString = false
            }

            char == '"' -> inString = true
            char == open -> depth++
            char == close -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        i++
    }
    return null
}

private fun jsonObjectOrNull(text: String): JsonObject? =
    runCatching { Network.json.parseToJsonElement(text) as? JsonObject }.getOrNull()

private fun jsonArrayOrNull(text: String): JsonArray? =
    runCatching { Network.json.parseToJsonElement(text) as? JsonArray }.getOrNull()

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.numberOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.flagOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

/** Site-relative image paths are turned into something the image loader can fetch. */
internal fun kaynUrl(path: String): String =
    if (path.startsWith("http")) path else KAYN_BASE + path

private const val BACKSLASH = '\\'

private val ESCAPES = mapOf(
    'n' to '\n',
    't' to '\t',
    'r' to '\r',
    'b' to '\b',
    'f' to '\u000C',
    '"' to '"',
    '\\' to '\\',
    '/' to '/',
)

private const val PUSH = "self.__next_f.push([1,\""
private const val CHAPTERS = "\"chapters\":["
private const val SERIES_MARKER = "{\"id\":\"%s\""

/** How far back a chapter list has to name the series to be accepted as its own. */
private const val OWNER_WINDOW = 8_000

internal const val KAYN_BASE = "https://kaynscans.com"
