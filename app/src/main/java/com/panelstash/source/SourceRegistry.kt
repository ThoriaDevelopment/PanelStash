package com.panelstash.source

/**
 * Holds the installed sources. Adding a scraper later means registering another
 * implementation here, not changing the schema, repository, or UI.
 */
class SourceRegistry(
    sources: List<Source>,
    /** Sources the reader switched off. They stay resolvable, but are never offered again. */
    private val disabled: () -> Set<String> = { emptySet() },
) {

    private val byId: Map<String, Source> = sources.associateBy { it.id }

    /** Every source the app knows about, including the ones the reader switched off. */
    val everything: List<Source> = sources.toList()

    /**
     * Everything that is switched on, in the order the UI should offer it. Falls back to every
     * source when nothing is left on, so a mis-tap can never leave Browse with no catalogue.
     */
    val all: List<Source>
        get() {
            val off = disabled()
            return everything.filterNot { it.id in off }.ifEmpty { everything }
        }

    val default: Source = requireNotNull(sources.firstOrNull()) { "At least one source is required" }

    fun get(sourceId: String): Source =
        byId[sourceId] ?: throw SourceError.Unavailable(IllegalStateException("Unknown source $sourceId"))

    /** Falls back to the default rather than failing when a stored id is no longer installed. */
    fun getOrNull(sourceId: String?): Source? = sourceId?.let { byId[it] }
}
