package com.panelstash.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response shapes of the Asura Scans API, verified against the live endpoints on 2026-10-28:
 *
 *   GET /api/series?page=&limit=&sort=latest|popular|rating
 *   GET /api/search?q=&page=&limit=
 *   GET /api/series/{slug}
 *   GET /api/series/{slug}/chapters
 *   GET /api/series/{slug}/chapters/{chapterSlug}
 *
 * A series is addressed by slug everywhere, so the slug alone is its remote id. A chapter is
 * scoped to its series, so the two are joined into `"<seriesSlug>|<chapterSlug>"`.
 */
@Serializable
data class AsuraSeriesResponse(
    val data: List<AsuraSeriesDto> = emptyList(),
    val meta: AsuraMetaDto? = null,
)

@Serializable
data class AsuraMetaDto(
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
)

@Serializable
data class AsuraSeriesDto(
    val id: Int = 0,
    val slug: String = "",
    val title: String = "",
    val description: String? = null,
    val cover: String? = null,
    val status: String? = null,
    val type: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("chapter_count") val chapterCount: Int = 0,
)

@Serializable
data class AsuraSeriesDetailResponse(
    /** The detail endpoint answers with the series at the top level, not under `data`. */
    val series: AsuraSeriesDto? = null,
)

@Serializable
data class AsuraChaptersResponse(
    val data: List<AsuraChapterDto> = emptyList(),
)

@Serializable
data class AsuraChapterDto(
    val id: Int = 0,
    @SerialName("series_id") val seriesId: Int = 0,
    val number: Double? = null,
    val slug: String = "",
    @SerialName("page_count") val pageCount: Int = 0,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("early_access_until") val earlyAccessUntil: String? = null,
)

@Serializable
data class AsuraChapterPageResponse(
    val data: AsuraChapterPageData? = null,
)

@Serializable
data class AsuraChapterPageData(
    @SerialName("access_gate") val accessGate: String? = null,
    @SerialName("is_locked") val isLocked: Boolean = false,
    val chapter: AsuraChapterDetail? = null,
)

@Serializable
data class AsuraChapterDetail(
    val pages: List<AsuraPageDto> = emptyList(),
)

@Serializable
data class AsuraPageDto(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
)
