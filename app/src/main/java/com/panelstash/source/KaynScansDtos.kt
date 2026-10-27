package com.panelstash.source

import kotlinx.serialization.Serializable

/**
 * Response shapes of the Kayn Scans API, verified against the live endpoints on 2026-10-27:
 *
 *   GET /api/series?page=&limit=&sort=latest|popular|rating|views[&q=][&genre=][&status=]
 *   GET /api/genres
 *   GET /api/chapters/next?seriesId=&beforeNumber=&limit=   (ten chapters per call, oldest first)
 *   GET /api/chapters/page-urls?chapterId=
 *
 * The series page is Next.js server output rather than JSON, so its detail and chapter list are
 * read by [parseKaynSeries]; the chapter list from there is the fast path and `/api/chapters/next`
 * is the fallback when a payload changes shape.
 */
@Serializable
data class KaynSeriesResponse(
    val data: List<KaynSeriesDto> = emptyList(),
    val meta: KaynMetaDto? = null,
)

@Serializable
data class KaynMetaDto(
    val total: Int = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class KaynSeriesDto(
    val id: String = "",
    val slug: String = "",
    val urlSlug: String = "",
    val title: String = "",
    /** A site-relative path such as `/uploads/api/<hash>.png`. */
    val coverImage: String? = null,
    /** MANHWA, MANGA, MANHUA or WEB_NOVEL. */
    val type: String? = null,
    val status: String? = null,
)

@Serializable
data class KaynGenresResponse(
    val genres: List<KaynGenreDto> = emptyList(),
)

@Serializable
data class KaynGenreDto(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
)

@Serializable
data class KaynChaptersResponse(
    val chapters: List<KaynChapterDto> = emptyList(),
)

@Serializable
data class KaynChapterDto(
    val id: String = "",
    val number: Double? = null,
    val title: String? = null,
    /** Coin-gated: unreadable without an account. */
    val isLocked: Boolean = false,
    val coinPrice: Int = 0,
    /** False on a locked or early-access chapter, which is the guest's view of both. */
    val hasAccess: Boolean = true,
    val pageCount: Int = 0,
)

@Serializable
data class KaynPageUrlsResponse(
    val pages: List<KaynPageDto> = emptyList(),
)

@Serializable
data class KaynPageDto(
    val pageNumber: Int = 0,
    /** CONTENT is a page of the chapter; BRAND_INTRO and BRAND_OUTRO are the site's own cards. */
    val kind: String? = null,
    /** True when the chapter is not readable and the URL has been withheld. */
    val isRedacted: Boolean = false,
    val imageUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
)
