package com.panelstash.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import com.panelstash.PanelStashApp
import com.panelstash.data.Preferences
import com.panelstash.data.ReaderPage
import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.ContinueRow
import com.panelstash.data.db.DownloadState
import com.panelstash.data.db.FollowRow
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.data.mergeChapters
import com.panelstash.data.normaliseTitle
import com.panelstash.source.BrowseFilter
import com.panelstash.source.BrowseSort
import com.panelstash.source.PageQuality
import com.panelstash.source.Source
import com.panelstash.source.SourceCapabilities
import com.panelstash.source.SourceError
import com.panelstash.source.SourceGenre
import com.panelstash.update.UpdateState
import com.panelstash.ui.warmRequest
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val BROWSE_PAGE = 30
private const val SEARCH_PAGE = 30

/**
 * Pages whose encoded bytes are warmed ahead of the reader. The two pages just ahead are left to
 * the reader itself: warming them would put four more transfers in front of the page the reader is
 * about to look at, which on a slow link makes the visible page arrive later, not sooner.
 */
private const val PREFETCH_AHEAD = 6

/** The first page ahead that is warmed rather than left to the reader. */
private const val PREFETCH_NEAR_GAP = 2

/** Pages kept warm behind the reader, so scrolling back up is a cache hit too. */
private const val PREFETCH_BEHIND = 2

/** Pages decoded whole ahead of the reader. Only pages that fit in one bitmap can be. */
private const val PREFETCH_DECODED_AHEAD = 4

/** Typing is searched after this pause, so a name is one request instead of one per letter. */
private const val SEARCH_DEBOUNCE_MS = 350L

/** One letter matches nearly everything, so a search waits for the second. */
private const val MIN_QUERY = 2

private const val MAX_RECENT_SEARCHES = 6

/** A chapter scroll writes its resume point at most this often. */
private const val PROGRESS_WRITE_DELAY_MS = 600L

/** A stored chapter list older than this is refreshed when its profile is opened. */
private const val CHAPTER_REFRESH_MS = 6 * 60 * 60 * 1000L

/** The other sources carrying an opened title are looked for again after this long. */
private const val LINK_REFRESH_MS = 10 * 60 * 1000L

/** How long one source may take to open a linked copy of a title. */
private const val LINK_OPEN_TIMEOUT_MS = 15_000L

/**
 * Taller pages are drawn as bands, so only their bytes are warmed. Decoding one whole would be a
 * multi-megabyte bitmap that the reader never asks for.
 */
private const val MAX_DECODE_AHEAD_HEIGHT = 8_192

/** How many pages of the next chapter are warmed when the reader nears the end. */
private const val NEXT_CHAPTER_WARM_PAGES = 3

/** Roughly the shape of one slice of a webtoon page, used when the source does not say. */
const val FALLBACK_PAGE_ASPECT = 0.64f

/** One title's automatic source switch: which sources have already been looked at for it. */
private class MatchChain(val title: String) {
    val tried = mutableSetOf<String>()
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PanelStashApp).container
    private val library = container.library
    private val settings = container.settings

    /**
     * The other sources' copies of a title, keyed by the profile that was opened. A profile's
     * chapter list is the union of these, so a chapter only some source carries is still there.
     */
    private val linked = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())
    private val linkJobs = mutableMapOf<Long, Job>()
    private val linkedAt = mutableMapOf<Long, Long>()

    private val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Browse))
    val screen: StateFlow<Screen> = stack
        .map { it.last() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Screen.Browse)

    val canGoBack: StateFlow<Boolean> = stack
        .map { it.size > 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Everything the user can change, observed by the theme and by settings. */
    val preferences: StateFlow<Preferences> = settings.state

    val continueReading: StateFlow<List<ContinueRow>> = library.observeContinueReading()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val followed: StateFlow<List<FollowRow>> = library.observeFollowed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val readLater: StateFlow<List<ManhwaEntity>> = library.observeReadLater()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalDownloadBytes: StateFlow<Long> = container.downloads.observeTotalBytes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Where the in-app updater is, observed by Settings. */
    val update: StateFlow<UpdateState> = container.updater.state

    val appVersion: String = container.updater.currentVersion

    val downloadGroups: StateFlow<List<DownloadGroup>> = container.downloads.observeAll()
        .map { rows ->
            rows.groupBy { it.manhwaId }.map { (manhwaId, chapters) ->
                DownloadGroup(
                    manhwaId = manhwaId,
                    manhwaTitle = chapters.first().manhwaTitle,
                    coverUrl = chapters.first().coverUrl,
                    chapters = chapters.map {
                        DownloadChapter(
                            chapterId = it.chapterId,
                            number = it.chapterNumber,
                            title = it.chapterTitle,
                            state = it.state,
                            byteSize = it.byteSize,
                        )
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse

    private val _discovery = MutableStateFlow(DiscoveryState())
    val discovery: StateFlow<DiscoveryState> = _discovery

    private val _similar = MutableStateFlow(SimilarState())
    val similar: StateFlow<SimilarState> = _similar

    /** Genres published with a title, loaded once per profile and shown as chips. */
    private val _profileGenres = MutableStateFlow<Map<Long, List<SourceGenre>>>(emptyMap())
    val profileGenres: StateFlow<Map<Long, List<SourceGenre>>> = _profileGenres

    private val _librarySection = MutableStateFlow(LibrarySection.FOLLOWING)
    val librarySection: StateFlow<LibrarySection> = _librarySection

    private val _chapterLoad = MutableStateFlow<ChapterLoad?>(null)
    val chapterLoad: StateFlow<ChapterLoad?> = _chapterLoad

    private val _pendingUndo = MutableStateFlow<PendingUndo?>(null)
    val pendingUndo: StateFlow<PendingUndo?> = _pendingUndo

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    private val _recentSearches = MutableStateFlow(settings.current.recentSearches)
    val recentSearches: StateFlow<List<String>> = _recentSearches

    private val _reader = MutableStateFlow<ReaderState?>(null)
    val reader: StateFlow<ReaderState?> = _reader

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Set when a download starts without notification permission, so the prompt has context. */
    private val _notificationPrompt = MutableStateFlow(false)
    val notificationPrompt: StateFlow<Boolean> = _notificationPrompt

    private var searchJob: Job? = null
    private var progressJob: Job? = null
    private var searchDebounceJob: Job? = null
    /** Per-source offsets while the search is showing every installed source. */
    private var searchOffsets: Map<String, Int> = emptyMap()
    private var readerJob: Job? = null
    private var advanceJob: Job? = null
    private var browseJob: Job? = null
    private var discoveryJob: Job? = null
    private var similarJob: Job? = null
    private var matchJob: Job? = null
    private var genresRequestedFor: String? = null
    private var prepareNextJob: Job? = null
    /** The chapter whose successor has already been warmed. */
    private var preparedNextFor = -1L
    /** The page list the two prefetch sets below belong to. A new chapter resets them. */
    private var prefetchPages: List<ReaderPage>? = null
    private val prefetchedBytes = HashSet<Int>()
    private val prefetchedPictures = HashSet<Int>()
    /** Per-source offsets while Browse is showing every installed source. */
    private var allBrowseOffsets: Map<String, Int> = emptyMap()
    private val chaptersInFlight = mutableSetOf<Long>()

    /**
     * The width the reader is drawing at, reported by the reader itself. Image requests are sized
     * against it so a prefetch decodes the same bitmap the reader will ask for and the two share
     * the memory cache. Falls back to the display until the reader has said.
     */
    @Volatile
    private var viewportWidthPx = 0

    /** Which sources a title has already been looked for, so an automatic switch cannot loop. */
    private var matchChain: MatchChain? = null

    init {
        // Browse starts on All Sources unless the reader has pinned a startup source, and the
        // matching capabilities come along: without them the first screen would offer no sort
        // selector and an empty filter sheet until the user happened to switch source.
        val startId = settings.current.defaultSourceId
        val source = library.installedSources().firstOrNull { it.id == startId }
        _browse.value = _browse.value.copy(
            sourceId = source?.id ?: ALL_SOURCES,
            sourceName = source?.name ?: "All sources",
            capabilities = source?.capabilities ?: allSourceCapabilities(),
            sources = library.installedSources(),
            sort = source?.capabilities?.sorts?.minByOrNull { it.ordinal } ?: BrowseSort.LATEST,
            filter = settings.current.browseFilter,
        )
    }

    /** What Browse can offer when every source is selected: only what all of them understand. */
    private fun allSourceCapabilities() = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST),
        // Status is the one filter every installed source understands.
        status = true,
    )

    fun clearMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    /** The display name of a source id, for headers and metadata lines. */
    fun sourceName(sourceId: String): String =
        if (sourceId == ALL_SOURCES) "All sources" else library.source(sourceId)?.name ?: sourceId

    // --- navigation -------------------------------------------------------

    fun selectTab(screen: Screen) {
        stack.value = listOf(screen)
    }

    fun push(screen: Screen) {
        stack.value = stack.value + screen
    }

    /** Puts [screen] where the current one is, for a move the user did not ask to come back from. */
    private fun replaceTop(screen: Screen) {
        val current = stack.value
        stack.value = if (current.isEmpty()) listOf(screen) else current.dropLast(1) + screen
        if (current.lastOrNull() is Screen.Reader && screen !is Screen.Reader) _reader.value = null
    }

    fun back(): Boolean {
        val current = stack.value
        if (current.size <= 1) return false
        stack.value = current.dropLast(1)
        if (current.last() is Screen.Reader) _reader.value = null
        return true
    }

    fun selectLibrarySection(section: LibrarySection) {
        _librarySection.value = section
    }

    // --- preferences ------------------------------------------------------

    fun updatePreferences(block: (Preferences) -> Preferences) = settings.update(block)

    fun setDownloadQuality(quality: PageQuality) = settings.update { it.copy(downloadQuality = quality) }

    fun downloadQuality(): PageQuality = settings.current.downloadQuality

    fun installedSources(): List<Source> = library.installedSources()

    fun allSources(): List<Source> = library.allSources()

    /**
     * Turns a source on or off. One source always stays on, so Browse is never left without a
     * catalogue; switching the default off moves the default to whatever is still enabled.
     */
    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        val off = settings.current.disabledSourceIds
        val next = if (enabled) off - sourceId else off + sourceId
        if (library.allSources().none { it.id !in next }) return
        settings.update { it.copy(disabledSourceIds = next) }

        val remaining = library.installedSources()
        _browse.value = _browse.value.copy(sources = remaining)
        if (!enabled) {
            remaining.firstOrNull()?.let { first ->
                if (settings.current.defaultSourceId == sourceId) {
                    settings.update { it.copy(defaultSourceId = first.id) }
                }
            }
            if (_browse.value.sourceId == sourceId) {
                setBrowseSource(remaining.firstOrNull()?.id ?: ALL_SOURCES)
            }
        }
        container.discovery.invalidate()
        refreshDiscovery(force = true)
    }

    // --- updates ----------------------------------------------------------

    /** [force] is the button; a quiet check is throttled inside the updater. */
    fun checkForUpdate(force: Boolean = true) = container.updater.check(force)

    fun downloadUpdate() = container.updater.download()

    /** Opens the system installer, or says why it could not be opened. */
    fun installUpdate() {
        container.updater.install()?.let { _message.value = it }
    }

    fun clearImageCache() {
        viewModelScope.launch(Dispatchers.IO) { container.clearImageCache() }
        _message.value = "Image cache cleared"
    }

    /** Bytes held by the disposable image cache, for the settings screen. */
    suspend fun imageCacheBytes(): Long {
        val directory = getApplication<Application>().cacheDir.resolve("images")
        return directory.walkBottomUp()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    // --- browse -----------------------------------------------------------

    /** Called when the Browse tab becomes visible. Only does work when it has nothing to show. */
    fun primeBrowse() {
        loadGenres()
        if (_browse.value.manhwa.isEmpty() && !_browse.value.loading) refreshBrowse()
        refreshDiscovery()
    }

    /**
     * The rails above the grid. Cheap after the first call: the repository caches them until the
     * reader picks up something new, and a failure simply leaves the grid on its own.
     */
    fun refreshDiscovery(force: Boolean = false) {
        if (discoveryJob?.isActive == true) return
        if (force) container.discovery.invalidate()
        _discovery.value = _discovery.value.copy(loading = true)
        discoveryJob = viewModelScope.launch {
            runCatching { container.discovery.rails(force = force) }
                .onSuccess { rails -> _discovery.value = DiscoveryState(loading = false, rails = rails) }
                .onFailure { _discovery.value = _discovery.value.copy(loading = false) }
        }
    }

    /**
     * The titles shown under "Similar" on a profile. Loaded when that tab is first opened, so a
     * profile the reader only uses to pick a chapter costs no extra request.
     */
    fun loadSimilar(manhwaId: Long, force: Boolean = false) {
        val current = _similar.value
        if (!force && current.manhwaId == manhwaId && (current.loading || current.attempted)) return
        similarJob?.cancel()
        _similar.value = SimilarState(manhwaId = manhwaId, loading = true)
        similarJob = viewModelScope.launch {
            runCatching { container.discovery.similar(manhwaId, force = force) }
                .onSuccess { cards ->
                    _similar.value = SimilarState(
                        manhwaId = manhwaId,
                        loading = false,
                        attempted = true,
                        cards = cards,
                    )
                }
                .onFailure {
                    _similar.value = SimilarState(manhwaId = manhwaId, loading = false, attempted = true)
                }
        }
    }

    /** The genres a title was filed under, for the chips on its profile. */
    fun loadGenresFor(manhwaId: Long) {
        if (_profileGenres.value.containsKey(manhwaId)) return
        viewModelScope.launch {
            val manhwa = library.manhwa(manhwaId) ?: return@launch
            val source = library.source(manhwa.sourceId) ?: return@launch
            // Reading a title's genres asks for the detail page, so it is skipped only on the
            // sources known never to publish them.
            val reports = source.capabilities.titleGenres || source.capabilities.genres
            val genres = if (reports) {
                runCatching { source.manhwa(manhwa.remoteId) }.getOrNull()?.genres.orEmpty()
            } else {
                emptyList()
            }
            _profileGenres.value = _profileGenres.value + (manhwaId to genres)
        }
    }

    /** Opens Browse filtered to a genre the profile was filed under. */
    fun openGenre(sourceId: String, genre: SourceGenre) {
        if (_browse.value.sourceId != sourceId) setBrowseSource(sourceId)
        setBrowseGenre(genre)
        selectTab(Screen.Browse)
    }

    private fun loadGenres() {
        val sourceId = _browse.value.sourceId
        if (!_browse.value.capabilities.genres) return
        if (genresRequestedFor == sourceId) return
        genresRequestedFor = sourceId
        viewModelScope.launch {
            runCatching { library.genres(sourceId) }
                .onSuccess { genres ->
                    if (_browse.value.sourceId == sourceId) {
                        _browse.value = _browse.value.copy(genres = genres)
                    }
                }
                .onFailure { genresRequestedFor = null }
        }
    }

    fun setBrowseSource(sourceId: String) {
        if (_browse.value.sourceId == sourceId) return
        val source = library.source(sourceId)
        if (sourceId != ALL_SOURCES && source == null) return
        genresRequestedFor = null
        allBrowseOffsets = emptyMap()
        _browse.value = _browse.value.copy(
            sourceId = sourceId,
            sourceName = source?.name ?: "All sources",
            capabilities = source?.capabilities ?: allSourceCapabilities(),
            sources = library.installedSources(),
            // The capabilities are a set, whose order is not the order the UI shows: pick the
            // same sort the screen would start on, not whatever the set happened to yield.
            sort = source?.capabilities?.sorts?.minByOrNull { it.ordinal } ?: BrowseSort.LATEST,
            tagId = null,
            tagName = null,
            genres = emptyList(),
            manhwa = emptyList(),
            endReached = false,
            error = null,
        )
        primeBrowse()
    }

    fun setBrowseSort(sort: BrowseSort) {
        if (_browse.value.sort == sort) return
        _browse.value = _browse.value.copy(sort = sort, manhwa = emptyList(), endReached = false, error = null)
        refreshBrowse()
    }

    fun setBrowseGenre(genre: SourceGenre?) {
        if (_browse.value.tagId == genre?.id) return
        _browse.value = _browse.value.copy(
            tagId = genre?.id,
            tagName = genre?.name,
            manhwa = emptyList(),
            endReached = false,
            error = null,
        )
        refreshBrowse()
    }

    fun setBrowseFilter(filter: BrowseFilter) {
        settings.update { it.copy(browseFilter = filter) }
        _browse.value = _browse.value.copy(filter = filter, manhwa = emptyList(), endReached = false, error = null)
        refreshBrowse()
    }

    fun refreshBrowse() {
        val requestedSort = _browse.value.sort
        val requestedTag = _browse.value.tagId
        val requestedSource = _browse.value.sourceId
        val requestedFilter = _browse.value.filter
        browseJob?.cancel()
        allBrowseOffsets = emptyMap()
        _browse.value = _browse.value.copy(loading = true, error = null)
        browseJob = viewModelScope.launch {
            if (requestedSource == ALL_SOURCES) {
                runCatching {
                    library.browseAll(
                        sort = requestedSort,
                        tagId = null,
                        filter = BrowseFilter(status = requestedFilter.status),
                        offsets = emptyMap(),
                        limit = BROWSE_PAGE,
                    )
                }
                    .onSuccess { page ->
                        if (isCurrent(requestedSource, requestedSort, requestedTag, requestedFilter)) {
                            allBrowseOffsets = page.nextOffsets
                            _browse.value = _browse.value.copy(
                                manhwa = page.items,
                                loading = false,
                                endReached = page.exhausted,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        if (isCurrent(requestedSource, requestedSort, requestedTag, requestedFilter)) {
                            _browse.value = _browse.value.copy(loading = false, error = friendly(error))
                        }
                    }
            } else {
                runCatching {
                    library.browse(
                        sourceId = requestedSource,
                        sort = requestedSort,
                        tagId = requestedTag,
                        filter = requestedFilter,
                        offset = 0,
                        limit = BROWSE_PAGE,
                    )
                }
                    .onSuccess { items ->
                        if (isCurrent(requestedSource, requestedSort, requestedTag, requestedFilter)) {
                            _browse.value = _browse.value.copy(
                                manhwa = items,
                                loading = false,
                                endReached = items.size < BROWSE_PAGE,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        if (isCurrent(requestedSource, requestedSort, requestedTag, requestedFilter)) {
                            _browse.value = _browse.value.copy(loading = false, error = friendly(error))
                        }
                    }
            }
        }
    }

    fun loadMoreBrowse() {
        val state = _browse.value
        if (state.loading || state.loadingMore || state.endReached) return
        val requestedSort = state.sort
        val requestedTag = state.tagId
        val requestedSource = state.sourceId
        val requestedFilter = state.filter
        val offset = state.manhwa.size
        _browse.value = state.copy(loadingMore = true)
        viewModelScope.launch {
            if (requestedSource == ALL_SOURCES) {
                runCatching {
                    library.browseAll(
                        sort = requestedSort,
                        tagId = null,
                        filter = BrowseFilter(status = requestedFilter.status),
                        offsets = allBrowseOffsets,
                        limit = BROWSE_PAGE,
                    )
                }
                    .onSuccess { page ->
                        if (isCurrent(requestedSource, requestedSort, requestedTag, requestedFilter)) {
                            allBrowseOffsets = page.nextOffsets
                            val current = _browse.value
                            _browse.value = current.copy(
                                manhwa = current.manhwa + page.items,
                                loadingMore = false,
                                endReached = page.exhausted,
                            )
                        }
                    }
                    .onFailure { error ->
                        if (isCurrent(requestedSource, requestedSort, requestedTag, requestedFilter)) {
                            _browse.value = _browse.value.copy(loadingMore = false, error = friendly(error))
                        }
                    }
                return@launch
            }

            runCatching {
                library.browse(requestedSource, requestedSort, requestedTag, requestedFilter, offset, BROWSE_PAGE)
            }
                .onSuccess { items ->
                    if (isCurrent(requestedSource, requestedSort, requestedTag, requestedFilter)) {
                        val current = _browse.value
                        _browse.value = if (current.manhwa.size == offset) {
                            current.copy(
                                manhwa = current.manhwa + items,
                                loadingMore = false,
                                endReached = items.size < BROWSE_PAGE,
                            )
                        } else {
                            current.copy(loadingMore = false)
                        }
                    }
                }
                .onFailure { error ->
                    if (isCurrent(requestedSource, requestedSort, requestedTag, requestedFilter)) {
                        _browse.value = _browse.value.copy(loadingMore = false, error = friendly(error))
                    }
                }
        }
    }

    private fun isCurrent(
        sourceId: String,
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
    ): Boolean {
        val state = _browse.value
        return state.sourceId == sourceId && state.sort == sort && state.tagId == tagId && state.filter == filter
    }

    // --- library ----------------------------------------------------------

    fun openProfile(manhwaId: Long) {
        push(Screen.Profile(manhwaId))
        ensureChapters(manhwaId)
    }

    /**
     * From a chapter to the profile of the series it belongs to. The reader is left behind rather
     * than buried under the profile: the profile is where reading stopped, and going back from it
     * lands where the reader was opened from.
     */
    /**
     * Pins a source for this title, or clears the pin when [sourceId] is null. The pin is stored
     * under every name the title goes by, because each copy of it is opened separately and has to
     * arrive at the same answer.
     */
    fun setPreferredSource(manhwaId: Long, sourceId: String?) {
        viewModelScope.launch {
            val manhwa = library.manhwa(manhwaId) ?: return@launch
            val names = buildList {
                add(manhwa.title)
                for (id in groupOf(manhwaId)) library.manhwa(id)?.title?.let(::add)
            }.map(::normaliseTitle).filter { it.isNotBlank() }.distinct()
            settings.update { prefs ->
                val next = prefs.preferredSources.toMutableMap()
                if (sourceId == null) {
                    names.forEach(next::remove)
                } else {
                    names.forEach { next[it] = sourceId }
                }
                prefs.copy(preferredSources = next)
            }
            _message.value = if (sourceId == null) {
                "Using all sources"
            } else {
                "${library.source(sourceId)?.name ?: sourceId} first"
            }
        }
    }

    fun openSeriesFromReader(manhwaId: Long) {
        val below = stack.value.dropLast(1).lastOrNull()
        if (below is Screen.Profile && below.manhwaId == manhwaId) {
            back()
        } else {
            replaceTop(Screen.Profile(manhwaId))
        }
        ensureChapters(manhwaId)
    }

    /**
     * Loads the chapter list the first time a manhwa is opened, and records why it failed so the
     * profile can show it instead of an empty list. A list that is already stored but old is
     * refreshed quietly, so a followed title eventually shows its new chapters.
     */
    fun ensureChapters(manhwaId: Long) {
        linkSources(manhwaId)
        if (!chaptersInFlight.add(manhwaId)) return
        viewModelScope.launch {
            try {
                val stored = library.chaptersFor(manhwaId)
                val readable = stored.any { it.externalUrl == null }
                val fetchedAt = library.manhwa(manhwaId)?.lastFetchedAt ?: 0
                val stale = System.currentTimeMillis() - fetchedAt > CHAPTER_REFRESH_MS
                if (readable && !stale) return@launch
                if (readable) {
                    // Something is readable already, so a failed refresh stays silent.
                    runCatching { library.refreshChapters(manhwaId) }
                    return@launch
                }
                _chapterLoad.value = ChapterLoad(manhwaId, loading = true)
                runCatching { library.refreshChapters(manhwaId) }
                    .onSuccess {
                        _chapterLoad.value = ChapterLoad(manhwaId, loading = false)
                        openOnAnotherSource(manhwaId)
                    }
                    .onFailure {
                        _chapterLoad.value = ChapterLoad(manhwaId, loading = false, error = friendly(it))
                    }
            } finally {
                chaptersInFlight.remove(manhwaId)
            }
        }
    }

    fun refreshChapters(manhwaId: Long) {
        linkSources(manhwaId, force = true)
        if (!chaptersInFlight.add(manhwaId)) return
        _chapterLoad.value = ChapterLoad(manhwaId, loading = true)
        viewModelScope.launch {
            try {
                runCatching { library.refreshChapters(manhwaId) }
                    .onSuccess {
                        _chapterLoad.value = ChapterLoad(manhwaId, loading = false)
                        openOnAnotherSource(manhwaId)
                    }
                    .onFailure {
                        _chapterLoad.value = ChapterLoad(manhwaId, loading = false, error = friendly(it))
                    }
            } finally {
                chaptersInFlight.remove(manhwaId)
            }
        }
    }

    /**
     * A title can be perfectly present on a source and still have nothing to read: MangaDex keeps
     * licensed series and drops their chapters, and a scanlation site can coin-lock everything it
     * publishes. So when a title turns out to be unreadable, the same title is opened on another
     * installed source straight away, rather than asking the reader to go and look for it.
     *
     * The sources already tried for this title are remembered, so two sources that both lack
     * chapters cannot pass the reader between each other, and the profile that showed the
     * unreadable copy is replaced rather than buried under the readable one.
     */
    private fun openOnAnotherSource(manhwaId: Long) {
        matchJob?.cancel()
        matchJob = viewModelScope.launch {
            try {
                val manhwa = container.db.manhwaDao().byId(manhwaId) ?: return@launch
                // Linking runs alongside the chapter sync. Waiting for it means a title MangaDex
                // only keeps an empty copy of is not switched away from when another source
                // turns out to carry readable chapters of it.
                linkJobs[manhwaId]?.join()
                if (library.hasReadableChapter(groupOf(manhwaId))) return@launch
                // The search runs over every other source, which takes a moment; the profile says
                // so rather than claiming, in the meantime, that nothing anywhere has the title.
                _chapterLoad.value = ChapterLoad(manhwaId, matching = true)
                val chain = matchChain?.takeIf { it.title == manhwa.title } ?: MatchChain(manhwa.title)
                matchChain = chain
                chain.tried += manhwa.sourceId
                val match = runCatching { library.findOnOtherSource(manhwa.title, chain.tried) }
                    .getOrNull()
                    ?: return@launch
                val (source, target) = match
                chain.tried += source.id
                val localId = runCatching { library.openManhwa(source.id, target.remoteId) }.getOrNull()
                    ?: return@launch
                _message.value = "Opened on ${source.name}"
                replaceTop(Screen.Profile(localId))
                ensureChapters(localId)
            } finally {
                val load = _chapterLoad.value
                if (load?.manhwaId == manhwaId && load.matching) {
                    _chapterLoad.value = load.copy(matching = false)
                }
            }
        }
    }

    /** The title's copies: the one the reader opened, then whichever sources it was linked to. */
    private fun groupOf(manhwaId: Long): List<Long> =
        listOf(manhwaId) + linked.value[manhwaId].orEmpty()

    /** The source pinned for a title, looked up by the name this copy goes by. */
    private fun preferredSource(title: String?, prefs: Preferences = settings.current): String? =
        title?.let { prefs.preferredSources[normaliseTitle(it)] }

    /**
     * Chapter precedence: the pinned source first when there is one, then the app's own order.
     * A chapter the pinned source does not carry still comes from whichever source has it.
     */
    private fun rankFor(preferred: String?): (String) -> Int =
        if (preferred == null) library::sourceRank else { id -> if (id == preferred) -1 else library.sourceRank(id) }

    private suspend fun rankForTitle(manhwaId: Long): (String) -> Int =
        rankFor(preferredSource(library.manhwa(manhwaId)?.title))

    /**
     * A profile is one title, not one source. Every copy of the title it was linked to is read
     * at once and [mergeChapters] keeps one entry per chapter number, so a series whose first
     * chapters only exist on one source and whose later ones only exist on another reads whole.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun profile(manhwaId: Long): Flow<ProfileState?> = combine(
        library.observeManhwa(manhwaId),
        linked.map { it[manhwaId].orEmpty() },
        preferences,
    ) { manhwa, others, prefs -> Triple(manhwa, others, prefs) }
        .flatMapLatest { (manhwa, others, prefs) ->
            if (manhwa == null) {
                flowOf(null)
            } else {
                val group = listOf(manhwaId) + others
                val preferred = preferredSource(manhwa.title, prefs)
                combine(
                    library.observeChaptersFor(group),
                    container.downloads.observeForManhwas(group),
                    container.db.resumeDao().observePoint(manhwaId),
                ) { chapters, downloads, resume ->
                    ProfileState(
                        manhwa = manhwa,
                        chapters = mergeChapters(chapters, rankFor(preferred)),
                        downloads = downloads.associateBy { it.chapterId },
                        resumeChapterId = resume?.chapterId,
                        resumePage = resume?.pageIndex ?: 0,
                        preferredSourceId = preferred,
                    )
                }
            }
        }

    /**
     * Finds the same title on the other installed sources and keeps the copies that exist. Each
     * is stored like any opened title, so its chapters can be read and downloaded, but the
     * profile shows one merged list rather than making the reader pick a source up front.
     *
     * The result is cached for a while: reopening a title must not cost one search per source
     * every time. A refresh forces it, which is how a new source, or a new chapter on a sibling,
     * gets picked up.
     */
    private fun linkSources(manhwaId: Long, force: Boolean = false) {
        if (linkJobs[manhwaId]?.isActive == true) return
        if (!force && System.currentTimeMillis() - (linkedAt[manhwaId] ?: 0) < LINK_REFRESH_MS) return
        linkJobs[manhwaId] = viewModelScope.launch {
            try {
                val manhwa = container.db.manhwaDao().byId(manhwaId) ?: return@launch
                // The names this source publishes for the title, alternates included: two sites
                // that call the same series different things are still one series.
                val anchor = runCatching {
                    library.source(manhwa.sourceId)?.manhwa(manhwa.remoteId)
                }.getOrNull()
                val names = listOf(manhwa.title) + anchor?.aliases.orEmpty()
                val matches = runCatching {
                    library.findEverywhere(names, excluding = setOf(manhwa.sourceId))
                }.getOrNull().orEmpty()
                // Every copy is a detail page plus a chapter list. Opening them one after another
                // made the merged list wait for one source at a time, so they are opened at once
                // and each is published as soon as it lands: the profile fills in its chapters
                // instead of staying empty until the slowest site answers.
                val ids = Collections.synchronizedList(mutableListOf<Long>())
                coroutineScope {
                    matches.forEach { (source, target) ->
                        launch {
                            val id = withTimeoutOrNull(LINK_OPEN_TIMEOUT_MS) {
                                runCatching { library.openManhwa(source.id, target.remoteId) }
                                    .getOrNull()
                            } ?: return@launch
                            if (id == manhwaId || id in ids) return@launch
                            ids += id
                            linked.value = linked.value + (manhwaId to ids.toList())
                        }
                    }
                }
                linkedAt[manhwaId] = System.currentTimeMillis()
                linked.value = linked.value + (manhwaId to ids.toList())
            } finally {
                linkJobs.remove(manhwaId)
            }
        }
    }

    fun followedState(manhwaId: Long): Flow<Boolean> = library.observeFollowedState(manhwaId)

    fun readLaterState(manhwaId: Long): Flow<Boolean> = library.observeReadLaterState(manhwaId)

    fun toggleFollow(manhwaId: Long, followed: Boolean) {
        viewModelScope.launch {
            library.setFollowed(manhwaId, followed)
            _message.value = if (followed) "Following" else "Unfollowed"
        }
    }

    fun toggleReadLater(manhwaId: Long, marked: Boolean) {
        viewModelScope.launch {
            library.setReadLater(manhwaId, marked)
            _message.value = if (marked) "Saved for later" else "Removed from later"
        }
    }

    // --- search -----------------------------------------------------------

    /**
     * Typing searches on its own: the list follows the field after a short pause, so the search
     * key is an option rather than a requirement. Blank goes back to the ranking list.
     */
    fun setQuery(query: String) {
        _search.value = _search.value.copy(query = query)
        searchDebounceJob?.cancel()
        if (query.isBlank()) {
            if (_search.value.results.isEmpty() && !_search.value.loading) runSearch("")
            return
        }
        if (query.trim().length < MIN_QUERY) return
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(query.trim())
        }
    }

    fun setSearchSource(sourceId: String) {
        if (_search.value.sourceId == sourceId) return
        _search.value = _search.value.copy(sourceId = sourceId, results = emptyList(), message = null)
        runSearch(_search.value.query)
    }

    fun clearSearch() {
        searchDebounceJob?.cancel()
        _search.value = _search.value.copy(query = "")
        runSearch("")
    }

    fun runSearch(query: String) {
        val requestedSource = _search.value.sourceId
        val requestedQuery = query.trim()
        searchDebounceJob?.cancel()
        searchJob?.cancel()
        searchOffsets = emptyMap()
        _search.value = _search.value.copy(
            query = query,
            loading = true,
            loadingMore = false,
            endReached = false,
            message = null,
        )
        searchJob = viewModelScope.launch {
            runCatching {
                if (requestedSource == ALL_SOURCES) {
                    library.searchAll(requestedQuery, emptyMap(), SEARCH_PAGE)
                } else {
                    library.search(requestedSource, requestedQuery, offset = 0, limit = SEARCH_PAGE)
                }
            }
                .onSuccess { page ->
                    if (_search.value.sourceId == requestedSource) {
                        searchOffsets = page.nextOffsets
                        _search.value = _search.value.copy(
                            results = page.items,
                            loading = false,
                            endReached = page.exhausted,
                        )
                        if (requestedQuery.isNotBlank()) rememberSearch(requestedQuery)
                    }
                }
                .onFailure { error ->
                    if (_search.value.sourceId == requestedSource) {
                        _search.value = _search.value.copy(loading = false, message = friendly(error))
                    }
                }
        }
    }

    /** The next page of a search, which continues every source rather than replaying the first. */
    fun loadMoreSearch() {
        val state = _search.value
        if (state.loading || state.loadingMore || state.endReached) return
        val requestedSource = state.sourceId
        val query = state.query.trim()
        _search.value = state.copy(loadingMore = true)
        searchJob = viewModelScope.launch {
            runCatching {
                if (requestedSource == ALL_SOURCES) {
                    library.searchAll(query, searchOffsets, SEARCH_PAGE)
                } else {
                    val offset = searchOffsets[requestedSource] ?: 0
                    library.search(requestedSource, query, offset, SEARCH_PAGE)
                }
            }
                .onSuccess { page ->
                    val current = _search.value
                    if (current.sourceId != requestedSource) return@onSuccess
                    searchOffsets = page.nextOffsets
                    _search.value = current.copy(
                        results = current.results + page.items,
                        loadingMore = false,
                        endReached = page.exhausted,
                    )
                }
                .onFailure { error ->
                    _search.value = _search.value.copy(loadingMore = false, message = friendly(error))
                }
        }
    }

    private fun rememberSearch(query: String) {
        val next = (listOf(query) + _recentSearches.value.filterNot { it.equals(query, true) })
            .take(MAX_RECENT_SEARCHES)
        _recentSearches.value = next
        settings.update { it.copy(recentSearches = next) }
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
        settings.update { it.copy(recentSearches = emptyList()) }
    }

    // --- reader -----------------------------------------------------------

    /**
     * The chapter list of whatever the reader has open, for switching without leaving it. It is
     * the merged list when the title also exists on another source, so the next chapter of a
     * series that stops publishing here is still openable from inside the reader.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun chapters(manhwaId: Long): Flow<List<ChapterEntity>> = combine(
        linked.map { it[manhwaId].orEmpty() },
        preferences,
    ) { others, prefs -> others to prefs }
        .flatMapLatest { (others, prefs) ->
            val preferred = preferredSource(library.manhwa(manhwaId)?.title, prefs)
            library.observeChaptersFor(listOf(manhwaId) + others)
                .map { mergeChapters(it, rankFor(preferred)) }
        }

    /** Replaces the open chapter with another one from the same title. */
    fun switchChapter(chapterId: Long, ownerManhwaId: Long) {
        if (_reader.value?.chapter?.id == chapterId) return
        stack.value = stack.value.dropLast(1)
        openChapter(chapterId, 0, ownerManhwaId)
    }

    /**
     * Opens a chapter. [ownerManhwaId] is the copy of the title the reader navigated from: a
     * chapter merged in from another source still saves its progress there, so the Library keeps
     * one "Continue reading" entry per title instead of one per source.
     */
    fun openChapter(chapterId: Long, pageIndex: Int = 0, ownerManhwaId: Long? = null) {
        push(Screen.Reader(chapterId))
        readerJob?.cancel()
        readerJob = viewModelScope.launch {
            _reader.value = null
            resetPrefetch()
            val chapter = library.chapter(chapterId)
            val manhwa = chapter?.let { container.db.manhwaDao().byId(it.manhwaId) }
            if (chapter == null || manhwa == null) {
                _message.value = "Unavailable"
                back()
                return@launch
            }
            val owner = ownerManhwaId ?: manhwa.id
            runCatching { resolvePages(chapter, manhwa, owner) }
                .onSuccess { open ->
                    // The chapter that will actually be read: normally the one that was clicked,
                    // or a sibling source's copy of the same number when that one has no pages.
                    val pages = open.pages
                    val saved = library.resume(owner)
                    val start = when {
                        pageIndex > 0 -> pageIndex
                        saved?.chapterId == open.chapter.id -> saved.pageIndex
                        else -> 0
                    }
                    // Sources that report page counts only when a chapter opens store them here,
                    // so resume, progress and clamping stay accurate.
                    if (pages.size != open.chapter.pageCount) {
                        library.updatePageCount(open.chapter.id, pages.size)
                    }
                    val fresh = library.chapter(open.chapter.id) ?: open.chapter
                    val resolved = start.coerceIn(0, maxOf(0, pages.size - 1))
                    _reader.value = ReaderState(
                        manhwa = open.manhwa,
                        ownerManhwaId = owner,
                        chapter = fresh,
                        pages = pages,
                        startPage = resolved,
                        nextChapterId = library
                            .nextChapterIn(groupOf(owner), fresh.id, rankForTitle(owner))?.id,
                    )
                    prefetch(pages, resolved)
                }
                .onFailure { error ->
                    _message.value = friendly(error)
                    back()
                }
        }
    }

    /** A chapter's pages together with the copy of the chapter they came from. */
    private data class OpenPages(
        val chapter: ChapterEntity,
        val manhwa: ManhwaEntity,
        val pages: List<ReaderPage>,
    )

    /**
     * Resolves a chapter's pages, falling back to another source's copy of the same chapter when
     * the copy that was listed cannot produce them.
     *
     * The profile merges every source's chapters and keeps one copy of each number, so a chapter
     * whose images were removed, moved or locked on the winning source reads as "not available"
     * while the same chapter is perfectly readable on a sibling source. The reader never sees the
     * difference: the sibling's pages are opened, and progress is still saved against the title
     * the reader came from, so "continue reading" leads straight back to a working copy.
     */
    private suspend fun resolvePages(
        chapter: ChapterEntity,
        manhwa: ManhwaEntity,
        owner: Long,
    ): OpenPages {
        val first = runCatching { container.reader.pages(chapter, manhwa) }
        first.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return OpenPages(chapter, manhwa, it) }
        val error = first.exceptionOrNull()
        // A dropped connection or a source that did not answer is worth one more try before the
        // chapter is treated as missing.
        if (error is SourceError.Transient || error is SourceError.Offline) {
            delay(200)
            runCatching { container.reader.pages(chapter, manhwa) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return OpenPages(chapter, manhwa, it) }
        }
        val alternatives = runCatching {
            library.alternativeCopies(
                manhwaIds = groupOf(owner),
                numberText = chapter.numberText,
                excludingChapterId = chapter.id,
                rank = rankForTitle(owner),
            )
        }.getOrNull().orEmpty()
        for ((copy, copyManhwa) in alternatives) {
            val pages = runCatching { container.reader.pages(copy, copyManhwa) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            return OpenPages(copy, copyManhwa, pages)
        }
        throw error ?: SourceError.Unavailable()
    }

    /** Called by the reader, which is the only part of the app that knows its window's width. */
    fun setViewportWidth(widthPx: Int) {
        if (widthPx > 0) viewportWidthPx = widthPx
    }

    /**
     * Warms the pages around the reader so scrolling never waits on the network.
     *
     * Two levels, because a page's cost depends on its shape. Every page in the window has its
     * **encoded bytes** fetched into the disk cache: that is the network cost, and for a 15,000 px
     * webtoon strip it is the only cost, since its bands then decode locally from those bytes. A
     * page short enough to be drawn in one bitmap is also **decoded**, so it paints straight from
     * the memory cache. Pages behind the reader are warmed too, so scrolling back up is free.
     *
     * Called again as the reader moves: the two sets make a repeat call free, and a jump forward
     * still warms everything beyond the jump. The previous high-water mark could not do that — a
     * jump made the pages it skipped permanently cold, which is exactly where a black screen
     * appeared.
     */
    fun prefetch(pages: List<ReaderPage>, from: Int) {
        if (pages.isEmpty()) return
        if (pages !== prefetchPages) {
            prefetchPages = pages
            prefetchedBytes.clear()
            prefetchedPictures.clear()
        }
        val width = if (viewportWidthPx > 0) viewportWidthPx else container.screenWidthPx()
        val context = getApplication<Application>()
        val firstByte = (from - PREFETCH_BEHIND).coerceAtLeast(0)
        val lastByte = (from + PREFETCH_AHEAD).coerceAtMost(pages.lastIndex)
        // Pages behind are usually already in the sets, so a warm loop over them costs nothing.
        val firstPicture = from + PREFETCH_NEAR_GAP
        val lastPicture = (from + PREFETCH_DECODED_AHEAD).coerceAtMost(pages.lastIndex)
        // Behind the reader, then beyond the gap: never the page under the reader's eyes.
        val byteWindow = sequence {
            for (index in firstByte until from) yield(index)
            for (index in (from + PREFETCH_NEAR_GAP)..lastByte) yield(index)
        }
        viewModelScope.launch {
            for (index in byteWindow) {
                if (!prefetchedBytes.add(index)) continue
                val page = pages[index] as? ReaderPage.Remote ?: continue
                container.imageLoader.enqueue(warmRequest(context, page.url))
            }
            for (index in firstPicture..lastPicture) {
                if (!prefetchedPictures.add(index)) continue
                val page = pages[index] as? ReaderPage.Remote ?: continue
                val pageWidth = if (page.width > 0) minOf(width, page.width) else width
                val height = if (page.width > 0 && page.height > 0) {
                    (pageWidth.toLong() * page.height / page.width).toInt()
                } else {
                    (pageWidth / (page.aspectOrNull() ?: FALLBACK_PAGE_ASPECT)).toInt()
                }
                // A strip this tall is drawn as bands; decoding it whole would be a bitmap the
                // reader never asks for. Its bytes are already warming above.
                if (height > MAX_DECODE_AHEAD_HEIGHT) continue
                container.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(page.url)
                        .size(pageWidth, height)
                        .precision(Precision.INEXACT)
                        .allowHardware(true)
                        .crossfade(false)
                        .build(),
                )
            }
        }
    }

    /**
     * Resolves the next chapter and warms its opening pages while the reader is still a few pages
     * from the end, so stepping over the end card opens a full reader instead of an empty one.
     *
     * Only the copy that is about to open is resolved: if it turns out to be unreadable, the
     * fallback in [openChapter] finds another one anyway.
     */
    fun prepareNext(chapterId: Long) {
        if (preparedNextFor == chapterId) return
        if (prepareNextJob?.isActive == true) return
        val state = _reader.value?.takeIf { it.chapter.id == chapterId } ?: return
        preparedNextFor = chapterId
        prepareNextJob = viewModelScope.launch {
            val owner = state.ownerManhwaId
            val next = library.nextChapterIn(groupOf(owner), chapterId, rankForTitle(owner))
                ?: return@launch
            val manhwa = container.db.manhwaDao().byId(next.manhwaId) ?: return@launch
            val pages = runCatching { container.reader.pages(next, manhwa) }.getOrNull()
                ?: return@launch
            val context = getApplication<Application>()
            for (page in pages.take(NEXT_CHAPTER_WARM_PAGES)) {
                val remote = page as? ReaderPage.Remote ?: continue
                container.imageLoader.enqueue(warmRequest(context, remote.url))
            }
        }
    }

    /** The prefetch sets only describe one chapter's pages. */
    private fun resetPrefetch() {
        prefetchPages = null
        prefetchedBytes.clear()
        prefetchedPictures.clear()
        preparedNextFor = -1L
    }

    /**
     * Scrolling reports every page it passes. The write is coalesced, so a fast scroll costs one
     * row update instead of one per page, and the last position always lands because the write
     * happens in the view-model's scope, which outlives the reader.
     */
    fun saveProgress(manhwaId: Long, chapterId: Long, pageIndex: Int) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(PROGRESS_WRITE_DELAY_MS)
            library.saveResume(manhwaId, chapterId, pageIndex)
        }
    }

    fun markFinished(chapterId: Long) {
        container.discovery.invalidateAfterReading()
        viewModelScope.launch { library.markRead(chapterId, read = true) }
    }

    /** Manual read/unread from the chapter list, without opening the chapter. */
    fun setChapterRead(chapterId: Long, read: Boolean) {
        container.discovery.invalidateAfterReading()
        viewModelScope.launch { library.markRead(chapterId, read) }
    }

    /**
     * Called when the end-of-chapter circle completes. Resume moves to the next chapter.
     *
     * A trigger for a chapter that is no longer open is ignored, and a second one while the first
     * is still resolving is dropped: each would resolve "the next chapter" against whatever is
     * open by then, which skips a chapter without the reader ever seeing it.
     */
    fun advance(chapterId: Long) {
        if (advanceJob?.isActive == true) return
        val state = _reader.value?.takeIf { it.chapter.id == chapterId } ?: return
        advanceJob = viewModelScope.launch {
            library.markRead(chapterId, read = true)
            // The next chapter is looked up in the merged list, so a series that continues on
            // another source keeps going instead of reporting the end of the series.
            val owner = state.ownerManhwaId
            val next = library.nextChapterIn(groupOf(owner), chapterId, rankForTitle(owner))
            if (next == null) {
                _message.value = "You are all caught up"
                stack.value = stack.value.dropLast(1)
                _reader.value = null
            } else {
                library.saveResume(owner, next.id, 0)
                stack.value = stack.value.dropLast(1)
                openChapter(next.id, 0, owner)
            }
        }
    }

    // --- downloads --------------------------------------------------------

    fun download(chapters: List<ChapterEntity>, manhwa: ManhwaEntity, metered: Boolean) {
        val quality = settings.current.downloadQuality
        askForNotificationsOnce()
        viewModelScope.launch {
            for (chapter in chapters) {
                container.downloads.markQueued(manhwa.id, chapter.id, quality, metered)
            }
            container.scheduler.enqueue(manhwa, chapters, quality, metered)
            _message.value = if (chapters.size == 1) {
                "Downloading ${chapterHeading(chapters.first().numberText)}"
            } else {
                "Downloading ${chapters.size} chapters"
            }
        }
    }

    /** The permission is asked for when it first matters: the reader started a download. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) _notificationPrompt.value = true
    }

    fun clearNotificationPrompt() {
        _notificationPrompt.value = false
    }

    /** Removes every downloaded chapter of a title, files included, and cancels any still queued. */
    fun deleteAllDownloads(manhwaId: Long) {
        viewModelScope.launch {
            for (chapterId in container.downloads.chapterIdsFor(manhwaId)) {
                container.scheduler.cancel(chapterId)
                container.reader.invalidate(chapterId)
            }
            container.downloads.deleteAllForManhwa(manhwaId)
            _message.value = "Downloads deleted"
        }
    }

    fun deleteDownload(chapterId: Long, number: String? = null) {
        viewModelScope.launch {
            container.scheduler.cancel(chapterId)
            container.reader.invalidate(chapterId)
            val undoable = container.downloads.delete(chapterId, keepForUndo = true)
            _pendingUndo.value = if (undoable) {
                PendingUndo(
                    chapterId = chapterId,
                    label = number?.let { "${chapterHeading(it)} deleted" } ?: "Download deleted",
                )
            } else {
                null
            }
        }
    }

    fun undoDelete() {
        val pending = _pendingUndo.value ?: return
        _pendingUndo.value = null
        viewModelScope.launch {
            container.reader.invalidate(pending.chapterId)
            _message.value = if (container.downloads.restore(pending.chapterId)) {
                "Restored"
            } else {
                "Could not restore"
            }
        }
    }

    /** The undo window closed: the moved-aside copy is now really gone. */
    fun clearUndo() {
        if (_pendingUndo.value == null) return
        _pendingUndo.value = null
        container.downloads.discardPending()
    }

    private fun friendly(error: Throwable): String = when (error) {
        is SourceError.RateLimited -> "Source asked us to slow down"
        is SourceError.Banned -> "Source paused requests for now"
        is SourceError.Transient -> "Source did not answer"
        is SourceError.Unavailable -> "Not available on this source"
        is SourceError.Offline -> "No connection"
        else -> "Something went wrong"
    }
}
