package com.panelstash.source

import kotlinx.serialization.Serializable

/**
 * Response shapes of the Orion Scans catalogue API, verified against the live endpoint on
 * 2026-10-27:
 *
 *   GET /api/query?page=&perPage=[&searchTerm=][&seriesStatus=]
 *
 * The series page and the reader are server-rendered HTML rather than JSON, so their shapes are
 * read by [OrionHtml] instead of these classes.
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
