package com.panelstash.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Page data of Flame Comics, verified against the live site on 2026-10-28.
 *
 * The site is a Next.js app, so every route publishes its data twice: once as `__NEXT_DATA__`
 * inside the HTML, and once as JSON at `/_next/data/{buildId}{route}.json`. The JSON is what the
 * source asks for — 3 KB for a chapter against 73 KB of markup — and the HTML is the fallback when
 * a deploy has retired the build id cached earlier in the session.
 *
 *   GET /latest                   -> allSeries: every title with its three newest chapters
 *   GET /browse                   -> series: every title with its categories
 *   GET /series/{id}              -> series + chapters (the full list)
 *   GET /series/{id}/{token}      -> chapter + images
 *
 * The two catalogue routes complement each other: /latest is ordered by chapter release and
 * /browse carries the genres, so browse reads the first for ordering and the second when a genre
 * filter is applied or the genre list is offered.
 *
 * Chapter numbers arrive as padded strings ("143.00"), chapter ids are numeric, and a chapter is
 * addressed in a URL by a token scoped to its series.
 */
@Serializable
data class FlameCataloguePage(
    val allSeries: List<FlameSeriesDto> = emptyList(),
)

/** The two catalogue routes name the same list differently; /browse calls it `series`. */
@Serializable
data class FlameBrowsePage(
    val series: List<FlameSeriesDto> = emptyList(),
    val initialFilters: FlameFiltersDto? = null,
)

@Serializable
data class FlameFiltersDto(
    val status: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val year: List<String> = emptyList(),
)

@Serializable
data class FlameSeriesPage(
    val series: FlameSeriesDto? = null,
    val chapters: List<FlameChapterDto> = emptyList(),
)

@Serializable
data class FlameChapterPage(
    val chapter: FlameChapterDto? = null,
)

@Serializable
data class FlameSeriesDto(
    @SerialName("series_id") val seriesId: Int = 0,
    val title: String = "",
    val description: String? = null,
    /** Thumbnail file name inside the series' CDN folder, e.g. `thumbnail.webp`. */
    val cover: String? = null,
    /** Manhwa, Manhua, Manga, Comic, Novel, Web Novel. */
    val type: String? = null,
    val status: String? = null,
    val year: Int? = null,
    /** Genre names as the site spells them, e.g. "School Life". Empty on the /latest route. */
    val categories: List<String> = emptyList(),
    val likes: Int = 0,
    @SerialName("last_edit") val lastEdit: Long = 0,
    val chapters: List<FlameChapterDto> = emptyList(),
)

@Serializable
data class FlameChapterDto(
    @SerialName("chapter_id") val chapterId: Int = 0,
    @SerialName("series_id") val seriesId: Int = 0,
    /** The number as the site stores it: "143.00", "10.50", occasionally not a number at all. */
    val chapter: String? = null,
    @SerialName("chapter_title") val chapterTitle: String? = null,
    val title: String? = null,
    /** URL segment for the reader, unique per series. */
    val token: String = "",
    @SerialName("release_date") val releaseDate: Long = 0,
    val hidden: Int = 0,
    val draft: Int = 0,
    /** Page images keyed by their position, present only on the reader page. */
    val images: Map<String, FlameImageDto> = emptyMap(),
)

@Serializable
data class FlameImageDto(
    val name: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
)
