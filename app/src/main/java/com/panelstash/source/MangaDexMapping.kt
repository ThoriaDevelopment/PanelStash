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
        genres = sourceGenres(),
        // Every spelling MangaDex lists, in every language: this is what lets the same series be
        // matched to a source that titles it differently.
        aliases = attributes.altTitles
            .flatMap { it.values }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct(),
    )
}

/**
 * The title's tags, with the real genres ahead of themes and formats. The first entry is what a
 * "more like this" row is built from, so "Romance" has to outrank "Full Colour".
 */
internal fun MangaDto.sourceGenres(): List<SourceGenre> =
    attributes.tags
        .sortedBy { if (it.attributes.group == "genre") 0 else 1 }
        .mapNotNull { tag ->
            val name = tag.attributes.name["en"] ?: tag.attributes.name.values.firstOrNull()
            if (tag.id.isBlank() || name.isNullOrBlank()) null else SourceGenre(tag.id, name)
        }

/**
 * Returns null only for chapters that cannot be represented at all: no number and no name,
 * unavailable, or zero pages with nowhere to send the reader. A chapter hosted on another site
 * keeps its [SourceChapter.externalUrl], so licensed English chapters can be listed as bookmarks
 * instead of disappearing from the profile.
 */
internal fun ChapterDto.toSourceChapter(): SourceChapter? {
    if (attributes.isUnavailable) return null
    val externalUrl = attributes.externalUrl
    if (externalUrl == null && attributes.pages <= 0) return null
    // MangaDex usually numbers a prologue "0", but not always: the entry can arrive with no
    // number at all and the name in its title, and it is still a chapter of the series.
    val number = attributes.chapter?.trim()?.takeIf { it.isNotEmpty() }
    val label = number?.let { chapterLabel(it) ?: ChapterLabel(it, chapterSortKey(it)) }
        ?: chapterLabel(attributes.title.orEmpty())
        ?: return null
    return SourceChapter(
        remoteId = id,
        numberText = label.numberText,
        sortKey = label.sortKey,
        title = attributes.title,
        language = attributes.translatedLanguage,
        pageCount = attributes.pages,
        externalOnly = externalUrl != null || attributes.pages <= 0,
        externalUrl = externalUrl,
    )
}
