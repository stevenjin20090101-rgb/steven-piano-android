// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.screens.library.LibraryScreen
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.NowPlayingScreen
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoScreen
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.first
import kotlin.math.min

/**
 * The app's frame: three tabs in a bottom navigation bar (Library, Now playing, Piano) with a
 * 240 ms fade-through between them, or a cut when motion is reduced. [requestedTab] switches
 * tabs from outside (a shared file, the notification); [onImport] brings files into the library.
 */
@Composable
fun PianoNavHost(requestedTab: Route?, onTabShown: () -> Unit, onImport: (ImportSource) -> Unit) {
    val nav = rememberNavController()
    val reduced = rememberReducedMotion()
    val playback = rememberPlaybackStarter()
    val entry by nav.currentBackStackEntryAsState()
    val current = Route.of(entry?.destination?.route) ?: Route.Library
    val show: (Route) -> Unit = remember(nav) { { route -> nav.showTab(route) } }

    LaunchedEffect(requestedTab) {
        val tab = requestedTab ?: return@LaunchedEffect
        nav.currentBackStackEntryFlow.first()   // the graph is in place
        show(tab)
        onTabShown()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { TabBar(current, show) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Route.Library.path,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = { if (reduced) EnterTransition.None else FadeThrough.enter },
            exitTransition = { if (reduced) ExitTransition.None else FadeThrough.exit },
            popEnterTransition = { if (reduced) EnterTransition.None else FadeThrough.enter },
            popExitTransition = { if (reduced) ExitTransition.None else FadeThrough.exit },
        ) {
            composable(Route.Library.path) {
                LibraryScreen(
                    onPlay = { id, queue ->
                        playback.play(id, queue)
                        show(Route.NowPlaying)
                    },
                    onImport = onImport,
                )
            }
            composable(Route.NowPlaying.path) {
                NowPlayingScreen(onPlayPause = playback::togglePlayPause, onOpenPiano = { show(Route.Piano) })
            }
            composable(Route.Piano.path) { PianoScreen() }
        }
    }
}

/**
 * Short labels; the selected tab sits on a surfaceElevated pill, never a tint. Labels scale with
 * the system font up to 1.5x, so "Now playing" stays on one line and the three icons stay level.
 */
@Composable
private fun TabBar(current: Route, onSelect: (Route) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSurface,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val density = LocalDensity.current
    Column {
        HairlineDivider()
        CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, MAX_LABEL_SCALE))) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Route.entries.forEach { route ->
                    NavigationBarItem(
                        selected = route == current,
                        onClick = { onSelect(route) },
                        icon = { Icon(painterResource(route.icon), contentDescription = null) },
                        label = { Text(route.label, maxLines = 1) },
                        colors = colors,
                    )
                }
            }
        }
    }
}

private const val MAX_LABEL_SCALE = 1.5f

private fun NavController.showTab(route: Route) = navigate(route.path) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Material fade-through in 240 ms: the old tab fades out, then the new one fades and scales in. */
private object FadeThrough {
    private const val OUT_MS = Motion.StandardMs * 35 / 100
    private const val IN_MS = Motion.StandardMs - OUT_MS
    private const val SCALE_FROM = 0.92f

    val enter = fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(IN_MS, delayMillis = OUT_MS, easing = LinearOutSlowInEasing), initialScale = SCALE_FROM)
    val exit = fadeOut(tween(OUT_MS, easing = FastOutLinearInEasing))
}
