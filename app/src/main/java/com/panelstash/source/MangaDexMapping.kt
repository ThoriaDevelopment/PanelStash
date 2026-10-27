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
 * Returns null for chapters that cannot be read here: no number, unavailable, external-only
 * (hosted on another site), or reporting zero pages.
 */
internal fun ChapterDto.toSourceChapter(): SourceChapter? {
    val number = attributes.chapter?.trim() ?: return null
    if (attributes.isUnavailable) return null
    return SourceChapter(
        remoteId = id,
        numberText = number,
        sortKey = chapterSortKey(number),
        title = attributes.title,
        language = attributes.translatedLanguage,
        pageCount = attributes.pages,
        externalOnly = attributes.externalUrl != null || attributes.pages <= 0,
    )
}
