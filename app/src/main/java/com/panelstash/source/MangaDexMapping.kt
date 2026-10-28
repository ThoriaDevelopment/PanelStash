package com.panelstash.source

/**
 * English title resolution. MangaDex frequently ships a title map without an "en" entry
 * (for example {ko-ro: "Na Honjaman Level-Up"}) while the English name lives in altTitles.
 */
internal fun MangaDto.englishTitle(): String =
    attributes.title["en"]
        ?: attributes.altTitles.firstNotNullOfOrNull { it["en"] }
        ?: attributes.title.values.firstOrNull()
        ?: "Untitled"

internal fun MangaDto.toSourceManhwa(): SourceManhwa {
    val coverFile = relationships.firstOrNull { it.type == "cover_art" }?.attributes?.fileName
    return SourceManhwa(
        remoteId = id,
        title = englishTitle(),
        description = attributes.description["en"],
        coverUrl = coverFile?.let { "https://uploads.mangadex.org/covers/$id/$it.256.jpg" },
        year = attributes.year,
        status = attributes.status,
    )
}

/**
 * Returns null only for chapters that cannot be represented at all: no number, unavailable, or
 * zero pages with nowhere to send the reader. A chapter hosted on another site keeps its
 * [SourceChapter.externalUrl], so licensed English chapters can be listed as bookmarks instead
 * of disappearing from the profile.
 */
internal fun ChapterDto.toSourceChapter(): SourceChapter? {
    val number = attributes.chapter?.trim() ?: return null
    if (attributes.isUnavailable) return null
    val externalUrl = attributes.externalUrl
    if (externalUrl == null && attributes.pages <= 0) return null
    return SourceChapter(
        remoteId = id,
        numberText = number,
        sortKey = chapterSortKey(number),
        title = attributes.title,
        language = attributes.translatedLanguage,
        pageCount = attributes.pages,
        externalOnly = externalUrl != null || attributes.pages <= 0,
        externalUrl = externalUrl,
    )
}
