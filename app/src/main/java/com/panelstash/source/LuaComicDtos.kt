package com.panelstash.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * The payloads served by api.luacomic.org. Every route answers a Laravel-shaped page envelope:
 * `data` for the rows and `meta` for the paging the catalogue pages through.
 */
@Serializable
internal data class LuaQueryResponse(
    val data: List<LuaSeriesDto> = emptyList(),
    val meta: LuaMeta = LuaMeta(),
)

@Serializable
internal data class LuaMeta(
    val total: Int = 0,
    @SerialName("per_page") val perPage: Int = 0,
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
)

@Serializable
internal data class LuaSeriesDto(
    val id: Long = 0,
    val title: String = "",
    val description: String? = null,
    @SerialName("alternative_names") val alternativeNames: String? = null,
    @SerialName("series_type") val seriesType: String? = null,
    @SerialName("series_slug") val slug: String = "",
    val thumbnail: String? = null,
    val status: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    val meta: LuaSeriesCounts? = null,
)

/** Only the chapter tally is read; the site also nests unrelated metadata here. */
@Serializable
internal data class LuaSeriesCounts(
    @SerialName("chapters_count") val chaptersCount: JsonPrimitive? = null,
)

@Serializable
internal data class LuaSeriesDetailDto(
    val id: Long = 0,
    val title: String = "",
    @SerialName("series_slug") val slug: String = "",
    val thumbnail: String? = null,
    val description: String? = null,
    val status: String? = null,
    @SerialName("series_type") val seriesType: String? = null,
    @SerialName("alternative_names") val alternativeNames: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
)

@Serializable
internal data class LuaChapterQueryResponse(
    val data: List<LuaChapterDto> = emptyList(),
    val meta: LuaMeta = LuaMeta(),
)

@Serializable
internal data class LuaChapterDto(
    @SerialName("chapter_name") val name: String? = null,
    @SerialName("chapter_title") val title: String? = null,
    @SerialName("chapter_slug") val slug: String = "",
    /** Zero is readable, anything else is a priced chapter the reader cannot open. */
    val price: Double = 0.0,
)
