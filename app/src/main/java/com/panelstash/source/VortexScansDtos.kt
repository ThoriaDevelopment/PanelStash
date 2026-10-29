package com.panelstash.source

import kotlinx.serialization.Serializable

/**
 * Response shapes of the Vortex Scans API, verified against the live endpoints on 2026-10-27:
 *
 *   GET /api/query?page=&perPage=&orderBy=latest[&searchTerm=][&seriesStatus=]
 *   GET /api/post?postSlug=
 *   GET /api/chapters?postId=
 *   GET /api/chapter?mangaslug=&chapterslug=
 *
 * Chapter numbers arrive as JSON numbers, so they decode as Double and are formatted back.
 */
@Serializable
data class VortexQueryResponse(
    val posts: List<VortexPostDto> = emptyList(),
    val totalCount: Int = 0,
)

@Serializable
data class VortexPostDto(
    val id: Int = 0,
    val slug: String = "",
    val postTitle: String = "",
    val postContent: String? = null,
    val featuredImage: String? = null,
    /** MANHWA, MANHUA, MANGA, NOVEL. */
    val seriesType: String? = null,
    val seriesStatus: String? = null,
    val isNovel: Boolean = false,
    val alternativeTitles: String? = null,
    /** The genres the title was filed under; empty on the titles the site never categorised. */
    val genres: List<VortexGenreDto> = emptyList(),
    val chapters: List<VortexChapterDto> = emptyList(),
)

@Serializable
data class VortexGenreDto(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class VortexPostDetail(
    val firstChapter: VortexChapterRef? = null,
    val lastChapter: VortexChapterRef? = null,
    val totalChapterCount: Int = 0,
    val post: VortexPostDto? = null,
)

@Serializable
data class VortexChapterRef(
    val number: Double? = null,
    val slug: String = "",
)

@Serializable
data class VortexChaptersResponse(
    val post: VortexPostDto? = null,
    val totalChapterCount: Int = 0,
)

@Serializable
data class VortexChapterDto(
    val id: Int = 0,
    val slug: String = "",
    val number: Double? = null,
    val title: String? = null,
    val isPermanentlyLocked: Boolean = false,
    val price: Int = 0,
)

@Serializable
data class VortexChapterPageResponse(
    val chapter: VortexChapterDetail? = null,
)

@Serializable
data class VortexChapterDetail(
    val slug: String = "",
    val images: List<VortexImageDto> = emptyList(),
    val isLocked: Boolean = false,
    val isAccessible: Boolean = true,
)

@Serializable
data class VortexImageDto(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val order: Int = 0,
)
