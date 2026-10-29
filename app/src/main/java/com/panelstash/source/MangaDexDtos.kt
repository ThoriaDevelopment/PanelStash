package com.panelstash.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaListResponse(
    val result: String = "",
    val data: List<MangaDto> = emptyList(),
)

@Serializable
data class MangaDetailResponse(
    val result: String = "",
    val data: MangaDto? = null,
)

@Serializable
data class MangaDto(
    val id: String = "",
    val attributes: MangaAttributes = MangaAttributes(),
    val relationships: List<RelationshipDto> = emptyList(),
)

@Serializable
data class MangaAttributes(
    val title: Map<String, String> = emptyMap(),
    /** Often holds the only English title, e.g. title = {ko-ro: ...} with altTitles = [{en: ...}]. */
    val altTitles: List<Map<String, String>> = emptyList(),
    val description: Map<String, String> = emptyMap(),
    val year: Int? = null,
    val status: String? = null,
    @SerialName("contentRating") val contentRating: String? = null,
    /** Embedded in every manga object; no `includes[]` is needed to receive them. */
    val tags: List<TagDto> = emptyList(),
)

@Serializable
data class RelationshipDto(
    val id: String = "",
    val type: String = "",
    val attributes: CoverAttributes? = null,
)

@Serializable
data class CoverAttributes(
    @SerialName("fileName") val fileName: String? = null,
)

@Serializable
data class TagListResponse(
    val result: String = "",
    val data: List<TagDto> = emptyList(),
)

@Serializable
data class TagDto(
    val id: String = "",
    val attributes: TagAttributes = TagAttributes(),
)

@Serializable
data class TagAttributes(
    val name: Map<String, String> = emptyMap(),
    /** genre | theme | format | content */
    val group: String = "",
)

@Serializable
data class ChapterListResponse(
    val result: String = "",
    val data: List<ChapterDto> = emptyList(),
)

@Serializable
data class ChapterDto(
    val id: String = "",
    val attributes: ChapterAttributes = ChapterAttributes(),
)

@Serializable
data class ChapterAttributes(
    val volume: String? = null,
    val chapter: String? = null,
    val title: String? = null,
    @SerialName("translatedLanguage") val translatedLanguage: String = "en",
    val pages: Int = 0,
    @SerialName("externalUrl") val externalUrl: String? = null,
    @SerialName("isUnavailable") val isUnavailable: Boolean = false,
)

@Serializable
data class AtHomeResponse(
    val result: String = "",
    @SerialName("baseUrl") val baseUrl: String = "",
    val chapter: AtHomeChapter = AtHomeChapter(),
)

@Serializable
data class AtHomeChapter(
    val hash: String = "",
    val data: List<String> = emptyList(),
    @SerialName("dataSaver") val dataSaver: List<String> = emptyList(),
)
