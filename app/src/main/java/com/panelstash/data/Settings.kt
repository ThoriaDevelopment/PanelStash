package com.panelstash.data

import android.content.Context
import androidx.core.content.edit
import com.panelstash.source.BrowseFilter
import com.panelstash.source.PageQuality
import com.panelstash.source.StatusFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Accent identities. The colours themselves belong to the theme, not to storage. */
enum class AccentTheme(val label: String) {
    /** The brand's own pink-to-purple, which is what a new install starts on. */
    BRAND("Brand"),
    INDIGO("Indigo"),
    VIOLET("Violet"),
    EMERALD("Emerald"),
    ROSE("Rose"),
    AMBER("Amber"),
    ;

    companion object {
        fun fromName(value: String?): AccentTheme =
            entries.firstOrNull { it.name == value } ?: BRAND
    }
}

enum class CanvasStyle(val label: String) {
    /** Near-black (or warm off-white in light mode) with two faint colour glows. */
    GLASS("Glass"),

    /** True black, or pure white in light mode, for people who want the glows gone. */
    OLED("Pure black"),
    ;

    /** The label follows the mode: "pure black" is not a thing in a light theme. */
    fun labelFor(mode: ThemeMode): String = when (this) {
        GLASS -> label
        OLED -> if (mode == ThemeMode.LIGHT) "Pure white" else label
    }

    companion object {
        fun fromName(value: String?): CanvasStyle = entries.firstOrNull { it.name == value } ?: GLASS
    }
}

/** Which side of the canvas the app draws on. Light still offers both glass and pure. */
enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    ;

    companion object {
        fun fromName(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: DARK
    }
}

/**
 * How long the animated jump a tap makes takes. 300ms is the default: quick enough to feel like a
 * flick rather than a transition, slow enough to read as movement instead of a cut.
 */
enum class TapScroll(val label: String, val durationMs: Int) {
    NORMAL("300 ms", 300),
    SLOW("500 ms", 500),
    RELAXED("700 ms", 700),
    ;

    companion object {
        fun fromName(value: String?): TapScroll = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

enum class PageFit(val label: String) {
    /** Webtoon default: the page fills the width and scrolls. */
    WIDTH("Fit width"),

    /** Fits a whole page on screen; better for tall single-panel pages. */
    HEIGHT("Fit page"),
    ;

    companion object {
        fun fromName(value: String?): PageFit = entries.firstOrNull { it.name == value } ?: WIDTH
    }
}

/**
 * Everything the user can change, read as one immutable snapshot. Screens observe [Settings.state]
 * so a change anywhere redraws immediately, and every write goes straight to disk.
 */
data class Preferences(
    val accent: AccentTheme = AccentTheme.BRAND,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val canvas: CanvasStyle = CanvasStyle.GLASS,
    /** Browse grid density. */
    val gridColumns: Int = 2,
    val pageFit: PageFit = PageFit.WIDTH,
    /** How long a tap takes to scroll forward a screenful. */
    val tapScroll: TapScroll = TapScroll.NORMAL,
    val keepAwake: Boolean = false,
    val streamingQuality: PageQuality = PageQuality.DATA_SAVER,
    val downloadQuality: PageQuality = PageQuality.ORIGINAL,
    /** When false, downloads are allowed to start on mobile data without asking. */
    val unmeteredOnly: Boolean = true,
    val defaultSourceId: String = "mangadex",
    /** Chapter lists read newest-first when true. */
    val reverseChapters: Boolean = false,
    val browseFilter: BrowseFilter = BrowseFilter.None,
)

/**
 * Streaming defaults to data-saver for speed; downloads default to original because the
 * downloaded copy is the archive. Writes are applied to memory first and persisted after, so the
 * UI never waits on disk.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("panelstash.settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())

    val state: StateFlow<Preferences> = _state

    val current: Preferences get() = _state.value

    var downloadQuality: PageQuality
        get() = current.downloadQuality
        set(value) = update { it.copy(downloadQuality = value) }

    var streamingQuality: PageQuality
        get() = current.streamingQuality
        set(value) = update { it.copy(streamingQuality = value) }

    fun update(block: (Preferences) -> Preferences) {
        val next = block(_state.value)
        if (next == _state.value) return
        _state.value = next
        persist(next)
    }

    private fun read(): Preferences = Preferences(
        accent = AccentTheme.fromName(prefs.getString(KEY_ACCENT, null)),
        themeMode = ThemeMode.fromName(prefs.getString(KEY_THEME_MODE, null)),
        canvas = CanvasStyle.fromName(prefs.getString(KEY_CANVAS, null)),
        gridColumns = prefs.getInt(KEY_GRID_COLUMNS, 2).coerceIn(2, 4),
        pageFit = PageFit.fromName(prefs.getString(KEY_PAGE_FIT, null)),
        tapScroll = TapScroll.fromName(prefs.getString(KEY_TAP_SCROLL, null)),
        keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, false),
        streamingQuality = quality(KEY_STREAMING_QUALITY, PageQuality.DATA_SAVER),
        downloadQuality = quality(KEY_DOWNLOAD_QUALITY, PageQuality.ORIGINAL),
        unmeteredOnly = prefs.getBoolean(KEY_UNMETERED_ONLY, true),
        defaultSourceId = prefs.getString(KEY_DEFAULT_SOURCE, "mangadex") ?: "mangadex",
        reverseChapters = prefs.getBoolean(KEY_REVERSE_CHAPTERS, false),
        browseFilter = readFilter(),
    )

    private fun quality(key: String, fallback: PageQuality): PageQuality =
        prefs.getString(key, null)
            ?.let { runCatching { PageQuality.valueOf(it) }.getOrNull() }
            ?: fallback

    private fun readFilter(): BrowseFilter = BrowseFilter(
        status = prefs.getString(KEY_FILTER_STATUS, null)
            ?.let { name -> StatusFilter.entries.firstOrNull { it.name == name } }
            ?: StatusFilter.ANY,
        year = prefs.getInt(KEY_FILTER_YEAR, 0).takeIf { it > 0 },
        safeOnly = prefs.getBoolean(KEY_FILTER_SAFE, false),
        excludedTagIds = prefs.getStringSet(KEY_FILTER_EXCLUDED, emptySet()).orEmpty(),
    )

    private fun persist(value: Preferences) {
        prefs.edit {
            putString(KEY_ACCENT, value.accent.name)
            putString(KEY_THEME_MODE, value.themeMode.name)
            putString(KEY_CANVAS, value.canvas.name)
            putInt(KEY_GRID_COLUMNS, value.gridColumns)
            putString(KEY_PAGE_FIT, value.pageFit.name)
            putString(KEY_TAP_SCROLL, value.tapScroll.name)
            putBoolean(KEY_KEEP_AWAKE, value.keepAwake)
            putString(KEY_STREAMING_QUALITY, value.streamingQuality.name)
            putString(KEY_DOWNLOAD_QUALITY, value.downloadQuality.name)
            putBoolean(KEY_UNMETERED_ONLY, value.unmeteredOnly)
            putString(KEY_DEFAULT_SOURCE, value.defaultSourceId)
            putBoolean(KEY_REVERSE_CHAPTERS, value.reverseChapters)
            putString(KEY_FILTER_STATUS, value.browseFilter.status.name)
            putInt(KEY_FILTER_YEAR, value.browseFilter.year ?: 0)
            putBoolean(KEY_FILTER_SAFE, value.browseFilter.safeOnly)
            putStringSet(KEY_FILTER_EXCLUDED, value.browseFilter.excludedTagIds)
        }
    }

    private companion object {
        const val KEY_DOWNLOAD_QUALITY = "download_quality"
        const val KEY_STREAMING_QUALITY = "streaming_quality"
        const val KEY_ACCENT = "accent"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_CANVAS = "canvas"
        const val KEY_GRID_COLUMNS = "grid_columns"
        const val KEY_PAGE_FIT = "page_fit"
        const val KEY_TAP_SCROLL = "tap_scroll"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_UNMETERED_ONLY = "unmetered_only"
        const val KEY_DEFAULT_SOURCE = "default_source"
        const val KEY_REVERSE_CHAPTERS = "reverse_chapters"
        const val KEY_FILTER_STATUS = "filter_status"
        const val KEY_FILTER_YEAR = "filter_year"
        const val KEY_FILTER_SAFE = "filter_safe"
        const val KEY_FILTER_EXCLUDED = "filter_excluded"
    }
}
