package com.panelstash.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.panelstash.ui.screens.BrowseScreen
import com.panelstash.ui.screens.LibraryScreen
import com.panelstash.ui.screens.ProfileScreen
import com.panelstash.ui.screens.ReaderScreen
import com.panelstash.ui.screens.SearchScreen
import com.panelstash.ui.screens.SettingsScreen
import com.panelstash.ui.theme.AppCanvas
import com.panelstash.ui.theme.Palette
import com.panelstash.ui.theme.PanelStashTheme
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class Tab(val screen: Screen, val label: String, val glyph: Glyph)

private val tabs = listOf(
    Tab(Screen.Browse, "Browse", Glyph.Compass),
    Tab(Screen.Library, "Library", Glyph.Books),
)

/** How long a deleted download can still be taken back. */
private const val UNDO_WINDOW_MS = 5_000L

/**
 * One saved-state key per screen, so a list keeps its scroll position when a profile is opened
 * over it or a tab is switched. Two profiles are two entries, so their lists never share state.
 */
private fun screenKey(screen: Screen): String = when (screen) {
    is Screen.Browse -> "browse"
    is Screen.Library -> "library"
    is Screen.Search -> "search"
    is Screen.Settings -> "settings"
    is Screen.Profile -> "profile-${screen.manhwaId}"
    is Screen.Reader -> "reader-${screen.chapterId}"
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val preferences by vm.preferences.collectAsState()

    PanelStashTheme(preferences) {
        AppContent(vm)
    }
}

@Composable
private fun AppContent(vm: AppViewModel) {
    val view = LocalView.current
    // The system bar icons are the platform's, not Compose's, so they need telling when the
    // user switches between a dark and a light canvas while the app is running.
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            // True means a light background, so the platform draws dark icons.
            isAppearanceLightStatusBars = Palette.IsLight
            isAppearanceLightNavigationBars = Palette.IsLight
        }
    }

    val screen by vm.screen.collectAsState()
    val context = LocalContext.current
    val savedState = rememberSaveableStateHolder()
    val message by vm.message.collectAsState()
    val undo by vm.pendingUndo.collectAsState()
    val canGoBack by vm.canGoBack.collectAsState()
    val wantsNotifications by vm.notificationPrompt.collectAsState()

    // Asked the first time a download starts, where the request explains itself, rather than on
    // launch where the only notification the app posts is a silent progress bar.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.clearNotificationPrompt() }
    LaunchedEffect(wantsNotifications) {
        if (!wantsNotifications) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            vm.clearNotificationPrompt()
            return@LaunchedEffect
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.clearNotificationPrompt()
        } else {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = canGoBack) { vm.back() }

    LaunchedEffect(message) {
        if (message != null) {
            delay(2_200)
            vm.clearMessage()
        }
    }

    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_WINDOW_MS)
            vm.clearUndo()
        }
    }

    val onReader = screen is Screen.Reader
    val onTab = screen is Screen.Browse || screen is Screen.Library

    AppCanvas {
        val current = screen
        savedState.SaveableStateProvider(screenKey(current)) {
            when (current) {
                is Screen.Browse -> BrowseScreen(vm)
                is Screen.Library -> LibraryScreen(vm)
                is Screen.Search -> SearchScreen(vm)
                is Screen.Profile -> ProfileScreen(vm, current.manhwaId)
                is Screen.Reader -> ReaderScreen(vm)
                is Screen.Settings -> SettingsScreen(vm)
            }
        }

        if (message != null && undo == null && !onReader) {
            Toast(
                text = message!!,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Below the status bar, or the toast covers the clock and the camera cutout.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 12.dp),
            )
        }

        AnimatedVisibility(
            visible = undo != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = if (onTab) 92.dp else 18.dp),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        ) {
            Row(
                modifier = Modifier
                    // The undo bar floats over a chapter list, and a clear fill is the only
                    // thing that keeps its label from reading as part of the row behind it.
                    .glass(
                        RoundedCornerShape(50),
                        strong = true,
                        elevation = 22.dp,
                        base = Palette.Canvas.copy(alpha = 0.92f),
                    )
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = undo?.label.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.Ink,
                )
                PillButton(
                    text = "Undo",
                    glyph = Glyph.Undo,
                    tone = PillTone.Quiet,
                    onClick = { vm.undoDelete() },
                )
            }
        }

        if (onTab) {
            FloatingNavBar(
                active = screen,
                onSelect = { vm.selectTab(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 14.dp),
            )
        }
    }
}

/**
 * A floating capsule rather than an edge-to-edge bar: artwork stays visible behind it, and the
 * selected pill slides, so switching tabs reads as a physical movement.
 */
@Composable
private fun FloatingNavBar(
    active: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = tabs.indexOfFirst { it.screen == active }.coerceAtLeast(0)
    val shape = RoundedCornerShape(26.dp)
    val itemWidth = 124.dp
    val itemHeight = 48.dp
    val thumbOffset by animateDpAsState(
        targetValue = itemWidth * selectedIndex,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "tab-thumb",
    )

    Box(
        modifier = modifier
            // Opaque enough to read over artwork: the labels used to sit on top of whatever
            // poster happened to be scrolling underneath them.
            .glass(shape, strong = true, elevation = 26.dp, base = Palette.Canvas.copy(alpha = 0.88f))
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .width(itemWidth)
                .height(itemHeight)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Palette.accentFill(), Palette.accentAltFill(0.12f)),
                    ),
                )
                .border(1.dp, Palette.accentEdge(0.35f), RoundedCornerShape(20.dp)),
        )
        Row {
            tabs.forEach { tab ->
                val selected = tab.screen == active
                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(itemHeight)
                        .clip(RoundedCornerShape(20.dp))
                        .pressable({ onSelect(tab.screen) }, scaleDown = 0.95f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlyphIcon(
                            glyph = tab.glyph,
                            size = 19.dp,
                            tint = if (selected) Palette.Accent else Palette.InkSecondary,
                            stroke = 1.9.dp,
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Palette.Ink else Palette.InkSecondary,
                        )
                    }
                }
            }
        }
    }
}
