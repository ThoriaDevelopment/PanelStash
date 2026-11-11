package com.panelstash.source

import kotlinx.serialization.Serializable

/**
 * Response shapes of the Orion Scans API, verified against the live endpoints on 2026-11-10:
 *
 *   GET /api/query?page=&perPage=[&searchTerm=][&seriesStatus=]
 *   GET /api/post?postSlug=
 *   GET /api/chapters?postId=
 *   GET /api/chapter?mangaslug=&chapterslug=
 *
 * The site's series and reader pages are client-rendered, so these routes are the whole source;
 * nothing here depends on page markup. Chapter numbers arrive as JSON numbers, so they decode as
 * Double and are formatted back.
 */
@Serializable
data class OrionQueryResponse(
    val posts: List<OrionPostDto> = emptyList(),
    val totalCount: Int = 0,
)

@Serializable
data class OrionPostDto(
    val id: Int = 0,
    val slug: String = "",
    val postTitle: String = "",
    val featuredImage: String? = null,
    /** MANGA, MANHWA or NOVEL. */
    val seriesType: String? = null,
    val seriesStatus: String? = null,
    val genres: List<OrionGenreDto> = emptyList(),
    /** The newest few only. An empty list means the series has nothing to open. */
    val chapters: List<OrionChapterRef> = emptyList(),
)

@Serializable
data class OrionChapterRef(
    val slug: String = "",
    val number: Double? = null,
)

@Serializable
data class OrionGenreDto(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class OrionPostDetailResponse(
    val post: OrionPostDetailDto? = null,
    val totalChapterCount: Int = 0,
)

@Serializable
data class OrionPostDetailDto(
    val id: Int = 0,
    val slug: String = "",
    val postTitle: String = "",
    val postContent: String? = null,
    val featuredImage: String? = null,
    val seriesType: String? = null,
    val seriesStatus: String? = null,
    /** "2022" on most series, a full timestamp on some; only the year is read. */
    val releaseDate: String? = null,
    /** Alternate names as one line-separated string. */
    val alternativeTitles: String? = null,
    val genres: List<OrionGenreDto> = emptyList(),
)

@Serializable
data class OrionChaptersResponse(
    val post: OrionChaptersPostDto? = null,
    val totalChapterCount: Int = 0,
)

@Serializable
data class OrionChaptersPostDto(
    val chapters: List<OrionChapterDto> = emptyList(),
)

@Serializable
data class OrionChapterDto(
    val slug: String = "",
    val number: Double? = null,
    val title: String? = null,
    val isLocked: Boolean = false,
    val isAccessible: Boolean = true,
    val isPermanentlyLocked: Boolean = false,
)

@Serializable
data class OrionChapterPageResponse(
    val chapter: OrionChapterPageDto? = null,
)

@Serializable
data class OrionChapterPageDto(
    val isLocked: Boolean = false,
    val isAccessible: Boolean = true,
    val isPermanentlyLocked: Boolean = false,
    val images: List<OrionImageDto> = emptyList(),
)

@Serializable
data class OrionImageDto(
    val url: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val order: Int = 0,
)

/** "2025-03-01T00:00:00.000Z" and "2022" both mean their first four digits. */
internal fun yearOf(date: String): Int? =
    Regex("""^(\d{4})""").find(date.trim())?.groupValues?.get(1)?.toIntOrNull()
