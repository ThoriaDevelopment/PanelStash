package com.panelstash.data

import com.panelstash.data.db.ManhwaEntity
import com.panelstash.source.BrowseFilter
import com.panelstash.source.BrowseSort
import com.panelstash.source.Source
import com.panelstash.source.SourceGenre
import com.panelstash.source.StatusFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One poster in a rail, with the small print that explains where it came from. */
data class DiscoveryCard(
    val manhwa: ManhwaEntity,
    val badge: String? = null,
    val caption: String? = null,
)

/** A horizontal row of titles under a heading, shown above the Browse grid. */
data class DiscoveryRail(
    val key: String,
    val eyebrow: String,
    val title: String,
    val subtitle: String? = null,
    val cards: List<DiscoveryCard>,
    /**
     * The genre this row is built from, when there is one, and the source whose id it belongs to.
     * Tapping it opens Browse filtered to that genre; the row itself can span several sources.
     */
    val genre: SourceGenre? = null,
    val genreSourceId: String? = null,
)

/**
 * Discovery surfaces: the rails above Browse (one merged leaderboard, then "because you read X"
 * rows) and the similar titles offered on a manhwa's own page.
 *
 * The leaderboard asks every source that ranks its catalogue for its own top titles and merges
 * them by position, so the result is not dominated by whichever site happens to sort first.
 *
 * The personal rails are seeded from the titles the reader touched last. Where a source publishes
 * the title's genres, the row is built from the same genre; where it does not, it falls back to
 * that source's newest titles in the same publication state, minus everything already read,
 * followed or saved. Above them sits the row the whole habit earns: the genre most of those reads
 * are filed under, shown as a catalogue filtered to it.
 *
 * All of it is cached, keyed by the seed titles, so moving around Browse does not re-query the
 * network. Finishing a chapter clears the personal caches, so a row cannot keep recommending what
 * the reader has just read.
 */
class DiscoveryRepository(private val library: LibraryRepository) {

    private data class CachedBoard(val rows: List<Pair<String, ManhwaEntity>>, val at: Long)

    private data class CachedRails(val seeds: List<Long>, val rails: List<DiscoveryRail>, val at: Long)

    private data class CachedSimilar(val cards: List<DiscoveryCard>, val at: Long)

    private var boardCache: CachedBoard? = null
    private var railsCache: CachedRails? = null
    private val similarCache = mutableMapOf<Long, CachedSimilar>()

    suspend fun rails(limit: Int = DISCOVERY_LIMIT, force: Boolean = false): List<DiscoveryRail> {
        // More seeds than the "because you read" rows use: a genre row is a claim about a habit,
        // and two titles are not a habit.
        val seeds = library.recentlyTouched(TASTE_SEED_COUNT)
        val now = System.currentTimeMillis()
        val cached = railsCache
        if (!force && cached != null && cached.seeds == seeds.map { it.id } && now - cached.at < RAILS_TTL_MS) {
            return cached.rails
        }
        if (force || boardCache?.let { now - it.at >= BOARD_TTL_MS } == true) boardCache = null

        // One rail failing must not take the others with it: a site being down is normal.
        val board = coroutineScope { async { runCatching { leaderboard(limit, now) }.getOrNull() }.await() }
        // Every title's genres are read once and shared by the genre row and the seed rows.
        val genres = GenreMemo()
        val taste = runCatching { tasteRail(seeds, limit, genres) }.getOrNull()
        val personal = runCatching { personalRails(seeds.take(SEED_COUNT), limit, genres) }
            .getOrDefault(emptyList())

        // A card is shown once on Browse, whichever row reached it first.
        val used = board?.cards.orEmpty().mapTo(mutableSetOf()) { discoveryKey(it.manhwa.title) }
        val rails = buildList {
            if (board != null) add(board)
            for (rail in listOfNotNull(taste) + personal) {
                val cards = rail.cards.filter { discoveryKey(it.manhwa.title) !in used }
                if (cards.size >= MIN_RAIL_CARDS) {
                    used += cards.map { discoveryKey(it.manhwa.title) }
                    add(rail.copy(cards = cards))
                }
            }
        }
        railsCache = CachedRails(seeds.map { it.id }, rails, now)
        return rails
    }

    /**
     * The reader finished or marked something read, so the personal surfaces are out of date:
     * a row must not keep recommending a title that is now finished. The leaderboard is public
     * and stays cached.
     */
    fun invalidateAfterReading() {
        railsCache = null
        similarCache.clear()
    }

    /** Drops the caches so the next [rails] hits the sources, used by the Browse refresh button. */
    fun invalidate() {
        boardCache = null
        railsCache = null
        similarCache.clear()
    }

    /**
     * Titles to offer beside an opened manhwa: the same genre where the source publishes genres,
     * otherwise its newest titles in the same publication state. Read, followed and saved titles
     * are dropped, and so is the title itself, so the row cannot recommend what is already open.
     *
     * Cached per title: moving between the chapters and similar tabs must not re-hit the network.
     */
    suspend fun similar(manhwaId: Long, limit: Int = SIMILAR_LIMIT, force: Boolean = false): List<DiscoveryCard> {
        val now = System.currentTimeMillis()
        if (!force) {
            similarCache[manhwaId]?.let { cached ->
                if (now - cached.at < RAILS_TTL_MS) return cached.cards
            }
        }
        val seed = library.manhwa(manhwaId) ?: return emptyList()
        val source = library.source(seed.sourceId) ?: return emptyList()

        val genre = genreOf(seed, source)
        val candidates = runCatching {
            library.browse(
                sourceId = source.id,
                sort = BrowseSort.LATEST,
                tagId = genre?.id,
                // With a genre, status would narrow an already small pool for no benefit.
                filter = BrowseFilter(status = if (genre == null) statusOf(seed, source) else StatusFilter.ANY),
                offset = 0,
                limit = limit * 2,
            )
        }.getOrDefault(emptyList())

        val consumed = library.consumedTitles().mapTo(mutableSetOf()) { discoveryKey(it) }
        val cards = pickSimilar(seed, candidates, consumed, limit)
            .map { DiscoveryCard(it, caption = source.name) }
        // Bounded: profiles are opened far more often than the cache TTL matters, and this map
        // would otherwise keep a list per title for the life of the process.
        if (similarCache.size >= SIMILAR_CACHE_LIMIT) similarCache.clear()
        similarCache[manhwaId] = CachedSimilar(cards, now)
        return cards
    }

    private suspend fun leaderboard(limit: Int, now: Long): DiscoveryRail? {
        boardCache?.let { cached ->
            if (now - cached.at < BOARD_TTL_MS) return boardRail(cached.rows.take(limit))
        }
        val ranked = library.installedSources().mapNotNull { source ->
            val sort = when {
                BrowseSort.POPULAR in source.capabilities.sorts -> BrowseSort.POPULAR
                BrowseSort.TOP_RATED in source.capabilities.sorts -> BrowseSort.TOP_RATED
                else -> null
            } ?: return@mapNotNull null
            source to sort
        }
        // A leaderboard of one site is just that site's list, which Browse already shows.
        if (ranked.size < 2) return null

        val pages = coroutineScope {
            ranked.map { (source, sort) ->
                async {
                    source.id to runCatching {
                        library.browse(
                            sourceId = source.id,
                            sort = sort,
                            tagId = null,
                            filter = BrowseFilter.None,
                            offset = 0,
                            limit = LEADERBOARD_DEPTH,
                        )
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        val rows = interleaveRanked(pages.map { (sourceId, items) -> sourceId to items.map { RankedManhwa(it) } }, limit)
        if (rows.size < MIN_RAIL_CARDS) return null
        boardCache = CachedBoard(rows, now)
        return boardRail(rows)
    }

    private fun boardRail(rows: List<Pair<String, ManhwaEntity>>): DiscoveryRail = DiscoveryRail(
        key = "leaderboard",
        eyebrow = "MOST READ RIGHT NOW",
        title = "Top ${rows.size}",
        subtitle = "Every source that ranks, merged by position.",
        cards = rows.mapIndexed { index, (sourceId, manhwa) ->
            DiscoveryCard(
                manhwa = manhwa,
                badge = "#${index + 1}",
                caption = library.source(sourceId)?.name ?: sourceId,
            )
        },
    )

    /**
     * The row a reading habit earns: the genre most of the recent reads are filed under, merged
     * from every source that can filter by it. Each site names its genres its own way and has its
     * own id for them, so the genre is matched by name and each source is asked with its own id.
     */
    private suspend fun tasteRail(
        seeds: List<ManhwaEntity>,
        limit: Int,
        genres: GenreMemo,
    ): DiscoveryRail? {
        if (seeds.size < TASTE_MIN_READS) return null
        val votes = tallyGenres(seeds.map { it.sourceId to genres.of(it) })
            .filter { it.count >= TASTE_MIN_VOTES }
        if (votes.isEmpty()) return null

        val consumed = library.consumedTitles().mapTo(mutableSetOf()) { discoveryKey(it) }
        val filterable = library.installedSources().filter { it.capabilities.genres }

        for (vote in votes) {
            // One page per source that files this genre under the same name.
            val pages = filterable.mapNotNull { source ->
                val genre = runCatching { source.genres() }.getOrNull()
                    .orEmpty()
                    .firstOrNull { genreKey(it.name) == genreKey(vote.name) }
                    ?: return@mapNotNull null
                val rows = runCatching {
                    library.browse(
                        sourceId = source.id,
                        sort = BrowseSort.LATEST,
                        tagId = genre.id,
                        filter = BrowseFilter.None,
                        offset = 0,
                        limit = limit * 2,
                    )
                }.getOrDefault(emptyList())
                    .filter { discoveryKey(it.title) !in consumed }
                if (rows.isEmpty()) null else Triple(source, genre, rows)
            }
            if (pages.isEmpty()) continue

            val ranked = interleaveRanked(
                pages.map { (source, _, rows) -> source.id to rows.map { RankedManhwa(it) } },
                limit,
            )
            if (ranked.size < MIN_RAIL_CARDS) continue

            // The chip opens Browse, which filters one source at a time: use the source of the
            // first card, so tapping it lands where the row starts.
            val chip = pages.firstOrNull { it.first.id == ranked.first().first } ?: pages.first()
            return DiscoveryRail(
                key = "taste-${genreKey(vote.name)}",
                eyebrow = "YOUR READING TASTE",
                title = "More in ${vote.name}",
                // "titles" rather than "reads": the seeds include followed and saved ones too.
                subtitle = "${vote.count} of your last ${seeds.size} titles",
                genre = chip.second,
                genreSourceId = chip.first.id,
                cards = ranked.map { (sourceId, manhwa) ->
                    DiscoveryCard(manhwa, caption = library.source(sourceId)?.name ?: sourceId)
                },
            )
        }
        return null
    }

    /**
     * One genre lookup per title, shared by the genre row and the seed rows. A source that
     * publishes genres on its titles but cannot filter by one still votes; only a source that can
     * filter can supply the row itself.
     */
    private inner class GenreMemo {
        private val seen = mutableMapOf<Long, List<SourceGenre>>()

        suspend fun of(seed: ManhwaEntity): List<SourceGenre> {
            seen[seed.id]?.let { return it }
            val source = library.source(seed.sourceId)
            val reports = source != null &&
                (source.capabilities.titleGenres || source.capabilities.genres)
            val genres = if (reports) {
                runCatching { source?.manhwa(seed.remoteId) }.getOrNull()?.genres.orEmpty()
            } else {
                emptyList()
            }
            seen[seed.id] = genres
            return genres
        }
    }

    private suspend fun personalRails(
        seeds: List<ManhwaEntity>,
        limit: Int,
        genres: GenreMemo,
    ): List<DiscoveryRail> {
        if (seeds.isEmpty()) return emptyList()
        val consumed = library.consumedTitles().mapTo(mutableSetOf()) { discoveryKey(it) }
        val rails = mutableListOf<DiscoveryRail>()
        val usedIds = mutableSetOf<Long>()
        val usedTitles = mutableSetOf<String>()

        for (seed in seeds) {
            if (rails.size >= PERSONAL_RAILS) break
            if (!usedIds.add(seed.id)) continue
            val source = library.source(seed.sourceId) ?: continue

            // Only a genre the source can filter by is worth passing: the sites that publish
            // genres on a title but ignore the filter would answer with their newest titles and
            // the row's heading would be a lie.
            val genre = genres.of(seed).firstOrNull()?.takeIf { source.capabilities.genres }
            // With a genre, status would narrow a small pool further for no benefit.
            val status = if (genre == null) statusOf(seed, source) else StatusFilter.ANY
            val candidates = runCatching {
                library.browse(
                    sourceId = source.id,
                    sort = BrowseSort.LATEST,
                    tagId = genre?.id,
                    filter = BrowseFilter(status = status),
                    offset = 0,
                    limit = limit * 2,
                )
            }.getOrDefault(emptyList())

            val picked = candidates
                .filter { candidate ->
                    candidate.id != seed.id &&
                        discoveryKey(candidate.title) !in consumed &&
                        discoveryKey(candidate.title) !in usedTitles
                }
                .take(limit)
            if (picked.size < MIN_RAIL_CARDS) continue

            rails += DiscoveryRail(
                key = "because-${seed.id}",
                eyebrow = "BECAUSE YOU READ",
                title = seed.title,
                subtitle = genre?.let { "More in ${it.name}" } ?: "More from ${source.name}",
                cards = picked.map { DiscoveryCard(it, caption = source.name) },
            )
            usedTitles += picked.map { discoveryKey(it.title) }
            usedIds += picked.map { it.id }
        }
        return rails
    }

    /** A genre only exists on the title itself, so the detail page is read once per title. */
    private suspend fun genreOf(seed: ManhwaEntity, source: Source): SourceGenre? =
        if (!source.capabilities.genres) {
            null
        } else {
            runCatching { source.manhwa(seed.remoteId) }.getOrNull()?.genres?.firstOrNull()
        }

    private fun statusOf(seed: ManhwaEntity, source: Source): StatusFilter {
        if (!source.capabilities.status) return StatusFilter.ANY
        // Sources spell it "Ongoing", "RELEASING" or "on hiatus", so the words are matched, not equal.
        val value = seed.status?.trim()?.lowercase().orEmpty()
        return when {
            value.isEmpty() -> StatusFilter.ANY
            "ongoing" in value || "releasing" in value || "current" in value -> StatusFilter.ONGOING
            "completed" in value || "finished" in value -> StatusFilter.COMPLETED
            "hiatus" in value || "hold" in value -> StatusFilter.HIATUS
            "cancel" in value || "dropped" in value -> StatusFilter.CANCELLED
            else -> StatusFilter.ANY
        }
    }
}

/** Cards offered under an opened manhwa's "Similar" tab. */
private const val SIMILAR_LIMIT = 12

/** Titles whose similar row is remembered before the map is emptied. */
private const val SIMILAR_CACHE_LIMIT = 40

/** How many cards the leaderboard shows. */
private const val DISCOVERY_LIMIT = 10

/** Candidates taken from each source before the merged top ten is chosen. */
private const val LEADERBOARD_DEPTH = 8

/** A row this short reads as a mistake, so it is dropped instead of shown. */
internal const val MIN_RAIL_CARDS = 4

private const val PERSONAL_RAILS = 2

/** Recent titles considered as seeds; extra ones are only used when an earlier seed yields a row. */
private const val SEED_COUNT = 4

/** Titles the genre row learns from: counting a habit needs more than the seed rows do. */
private const val TASTE_SEED_COUNT = 8

/** Below two reads there is no habit to describe. */
private const val TASTE_MIN_READS = 2

/** A genre needs two agreeing reads before it is a row rather than one title's filing. */
private const val TASTE_MIN_VOTES = 2

/**
 * Genres a single title votes for. A series filed under eight genres would otherwise outvote eight
 * series filed under one.
 */
private const val GENRE_VOTES_PER_TITLE = 3

private const val BOARD_TTL_MS = 10 * 60 * 1000L

/** Short enough that finishing a new title changes the rows, long enough to survive tab switching. */
private const val RAILS_TTL_MS = 10 * 60 * 1000L

/**
 * The similar row's picks: the seed itself never comes back, a title published twice appears
 * once, and anything already read, followed or saved is left out.
 */
internal fun pickSimilar(
    seed: ManhwaEntity,
    candidates: List<ManhwaEntity>,
    consumed: Set<String>,
    limit: Int,
): List<ManhwaEntity> {
    val seen = mutableSetOf(discoveryKey(seed.title))
    return candidates
        .filter { candidate ->
            val key = discoveryKey(candidate.title)
            key !in consumed && seen.add(key)
        }
        .take(limit)
}

/** A genre several recent reads were filed under, with one source's spelling of its name. */
internal data class GenreVote(
    val name: String,
    val count: Int,
    val genre: SourceGenre,
    val sourceId: String,
)

/**
 * Counts what the recent reads are filed under, most-read first. Each title votes once for each of
 * its first [GENRE_VOTES_PER_TITLE] genres, so a long genre list cannot shout louder than a long
 * reading list. Votes are counted by name, not by id, because a genre id means nothing to another
 * site and "Action" is the same taste wherever it was read.
 */
internal fun tallyGenres(reads: List<Pair<String, List<SourceGenre>>>): List<GenreVote> {
    val counts = mutableMapOf<String, Int>()
    val names = mutableMapOf<String, String>()
    val sample = mutableMapOf<String, Pair<String, SourceGenre>>()
    for ((sourceId, list) in reads) {
        for (genre in list.take(GENRE_VOTES_PER_TITLE)) {
            val key = genreKey(genre.name)
            if (key.isEmpty()) continue
            counts[key] = (counts[key] ?: 0) + 1
            names.putIfAbsent(key, genre.name.trim())
            sample.putIfAbsent(key, sourceId to genre)
        }
    }
    return counts.entries
        .sortedByDescending { it.value }
        .map { (key, count) ->
            val (sourceId, genre) = sample.getValue(key)
            GenreVote(name = names.getValue(key), count = count, genre = genre, sourceId = sourceId)
        }
}

/** "Sci-Fi Action", "Scifi-Action" and " scifi action" are one genre across three sites. */
internal fun genreKey(name: String): String = name.trim().lowercase().filter { it.isLetterOrDigit() }

/** Titles are compared without case or punctuation, so "Solo Leveling" and "solo-leveling" match. */
internal fun discoveryKey(title: String): String =
    title.lowercase().filter { it.isLetterOrDigit() }

/** A catalogue row, with the chapter count its source reported, when it reported one. */
internal data class RankedManhwa(
    val manhwa: ManhwaEntity,
    val chapterCount: Int? = null,
)

/**
 * Rounds through every source one rank at a time, so the first card from each site comes before
 * the second card from any of them. A title published by two sites keeps its best position, and
 * carries the copy whose source reported the most chapters: a licensed entry with a dozen external
 * links must not stand in for a scanlation that has hundreds, which is how "Solo Leveling" could
 * arrive with sixteen episodes while another source had two hundred.
 */
internal fun interleaveRanked(
    pages: List<Pair<String, List<RankedManhwa>>>,
    limit: Int,
): List<Pair<String, ManhwaEntity>> {
    val out = mutableListOf<Pair<String, RankedManhwa>>()
    // Title to its slot, so a richer copy replaces the one already picked without moving it.
    val positions = mutableMapOf<String, Int>()
    val depth = pages.maxOfOrNull { it.second.size } ?: 0
    for (rank in 0 until depth) {
        for ((sourceId, items) in pages) {
            val row = items.getOrNull(rank) ?: continue
            val key = discoveryKey(row.manhwa.title)
            val at = positions[key]
            if (at == null) {
                positions[key] = out.size
                out += sourceId to row
                if (out.size >= limit) return out.map { it.first to it.second.manhwa }
            } else {
                val kept = out[at].second.chapterCount
                val richer = row.chapterCount != null && (kept == null || row.chapterCount > kept)
                // An unknown count never displaces a known one, and neither displaces itself.
                if (richer) out[at] = sourceId to row
            }
        }
    }
    return out.map { it.first to it.second.manhwa }
}
