package com.panelstash.source

/**
 * Holds the installed sources. Adding a scraper later means registering another
 * implementation here, not changing the schema, repository, or UI.
 */
class SourceRegistry(sources: List<Source>) {

    private val byId: Map<String, Source> = sources.associateBy { it.id }

    /** Everything installed, in the order the UI should offer it. */
    val all: List<Source> = sources.toList()

    val default: Source = requireNotNull(sources.firstOrNull()) { "At least one source is required" }

    fun get(sourceId: String): Source =
        byId[sourceId] ?: throw SourceError.Unavailable(IllegalStateException("Unknown source $sourceId"))

    /** Falls back to the default rather than failing when a stored id is no longer installed. */
    fun getOrNull(sourceId: String?): Source? = sourceId?.let { byId[it] }
}
