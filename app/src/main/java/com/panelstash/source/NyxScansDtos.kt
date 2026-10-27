package com.panelstash.source

import kotlinx.serialization.Serializable

/**
 * Response shapes of the Nyx Scans API, verified against the live endpoints on 2026-10-27:
 *
 *   GET /api/query?page=&perPage=&orderBy=latest|popular[&searchTerm=][&seriesStatus=]
 *   GET /api/post?postSlug=
 *   GET /api/chapters?postId=
 *   GET /api/chapter?mangaslug=&chapterslug=
 *   GET /api/genres
 *
 * Chapters arrive with the page images in one payload, dimensions included, so a chapter needs a
 * single request. Note the reader endpoint spells its parameters in lower case (`chapterslug`),
 * unlike every other endpoint on the same API.
 */
@Serializable
data class NyxQueryResponse(
    val posts: List<NyxPostDto> = emptyList(),
    val totalCount: Int = 0,
)

@Serializable
data class NyxPostDto(
    val id: Int = 0,
    val slug: String = "",
    val postTitle: String = "",
    val postContent: String? = null,
    val featuredImage: String? = null,
    /** MANHWA, MANHUA, MANGA, NOVEL. */
    val seriesType: String? = null,
    val seriesStatus: String? = null,
    val isNovel: Boolean = false,
    val chapters: List<NyxChapterDto> = emptyList(),
)

@Serializable
data class NyxPostDetail(
    val firstChapter: NyxChapterRef? = null,
    val lastChapter: NyxChapterRef? = null,
    val totalChapterCount: Int = 0,
    val post: NyxPostDto? = null,
)

@Serializable
data class NyxChapterRef(
    val number: Double? = null,
    val slug: String = "",
)

@Serializable
data class NyxChaptersResponse(
    val post: NyxPostDto? = null,
    val totalChapterCount: Int = 0,
)

@Serializable
data class NyxChapterDto(
    val id: Int = 0,
    val slug: String = "",
    val number: Double? = null,
    val title: String? = null,
    /** Never free, e.g. a paid chapter that has not been unlocked. */
    val isPermanentlyLocked: Boolean = false,
    /** Locked for now: paid, or timed for supporters. */
    val isLocked: Boolean = false,
    val isAccessible: Boolean = true,
    val price: Int = 0,
)

@Serializable
data class NyxChapterResponse(
    val chapter: NyxChapterDetail? = null,
)

@Serializable
data class NyxChapterDetail(
    val slug: String = "",
    val images: List<NyxImageDto> = emptyList(),
    val isLocked: Boolean = false,
    val isAccessible: Boolean = true,
    val isPermanentlyLocked: Boolean = false,
)

@Serializable
data class NyxImageDto(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val order: Int = 0,
)

@Serializable
data class NyxGenreDto(
    val id: Int = 0,
    val name: String = "",
)
