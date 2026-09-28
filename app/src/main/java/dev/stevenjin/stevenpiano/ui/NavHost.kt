// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.LocalArtworkMonochrome
import dev.stevenjin.stevenpiano.ui.screens.keys.KeysScreen
import dev.stevenjin.stevenpiano.ui.screens.library.LibraryScreen
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.NowPlayingScreen
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoPageScreen
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoScreen
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalHandColours
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.first
import kotlin.math.min

/**
 * The app's frame: four destinations (Library, Now playing, Keys, Piano) in a bottom navigation
 * bar on compact widths, or a navigation rail on the left on medium and expanded ones ([frame]),
 * with a 240 ms fade-through between them, or a cut when motion is reduced. The Piano tab is a
 * graph of its own: its hub, and on phones its pages (`piano/{page}`), pushed over the hub with the
 * bar still there and popped by back; a tab keeps its place when another is chosen, and choosing
 * Piano again goes back to its hub. Artwork everywhere follows the Display page's black-and-white
 * switch ([LocalArtworkMonochrome]), and the waterfall its Hand colours switch ([LocalHandColours]).
 * [requestedTab] switches destination from outside (a shared file, a notification; Piano opens on
 * its hub); [onImport] brings files into the library.
 */
@Composable
fun PianoNavHost(frame: AppFrame, requestedTab: Route?, onTabShown: () -> Unit, onImport: (ImportSource) -> Unit) {
    val nav = rememberNavController()
    val reduced = rememberReducedMotion()
    val playback = rememberPlaybackStarter()
    val entry by nav.currentBackStackEntryAsState()
    val current = Route.of(entry?.destination?.route) ?: Route.Library
    val open: (Route) -> Unit = remember(nav) { { route -> nav.openTab(route) } }
    val select: (Route) -> Unit = remember(nav) { { route -> nav.selectTab(route) } }
    val settings by LocalContext.current.graph.settings.collectAsStateWithLifecycle()

    LaunchedEffect(requestedTab) {
        val tab = requestedTab ?: return@LaunchedEffect
        nav.currentBackStackEntryFlow.first()   // the graph is in place
        open(tab)
        onTabShown()
    }

    CompositionLocalProvider(
        LocalAppFrame provides frame,
        LocalArtworkMonochrome provides settings.artworkMonochrome,
        LocalHandColours provides settings.handColours,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (frame.rail) TabRail(current, select)
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                // The rail pads for the start edge; the content keeps the others. A phone on its
                // side may have its navigation buttons or its camera cutout at either end.
                contentWindowInsets = if (frame.rail) {
                    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                } else {
                    ScaffoldDefaults.contentWindowInsets
                },
                bottomBar = { if (!frame.rail) TabBar(current, select) },
            ) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = Route.Library.path,
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding),
                    enterTransition = {
                        when {
                            reduced -> EnterTransition.None
                            !withinPianoTab() -> FadeThrough.enter
                            pushesPage(frame) -> PagePush.enter(this)
                            else -> EnterTransition.None
                        }
                    },
                    exitTransition = {
                        when {
                            reduced -> ExitTransition.None
                            !withinPianoTab() -> FadeThrough.exit
                            pushesPage(frame) -> PagePush.exitUnder(this)
                            else -> ExitTransition.None
                        }
                    },
                    popEnterTransition = {
                        when {
                            reduced -> EnterTransition.None
                            !withinPianoTab() -> FadeThrough.enter
                            popsPage(frame) -> PagePush.popEnterUnder(this)
                            else -> EnterTransition.None
                        }
                    },
                    popExitTransition = {
                        when {
                            reduced -> ExitTransition.None
                            !withinPianoTab() -> FadeThrough.exit
                            popsPage(frame) -> PagePush.popExit(this)
                            else -> ExitTransition.None
                        }
                    },
                ) {
                    composable(Route.Library.path) {
                        LibraryScreen(playback, onPlaying = { open(Route.NowPlaying) }, onImport = onImport)
                    }
                    composable(Route.NowPlaying.path) {
                        NowPlayingScreen(playback, onOpenPiano = { open(Route.Piano) })
                    }
                    composable(Route.Keys.path) { KeysScreen(onOpenPiano = { open(Route.Piano) }) }
                    navigation(startDestination = PianoRoutes.HUB, route = Route.Piano.path) {
                        composable(PianoRoutes.HUB) { hub ->
                            val tab = remember(hub) { nav.getBackStackEntry(Route.Piano.path) }
                            PianoScreen(
                                tab,
                                onOpenPage = { page -> if (nav.isTop(hub)) nav.navigate(PianoRoutes.page(page)) },
                                onReopenPage = { page -> if (nav.isTop(hub)) nav.navigate(PianoRoutes.page(page, cut = true)) },
                            )
                        }
                        composable(
                            PianoRoutes.PAGE,
                            arguments = listOf(
                                navArgument(PianoRoutes.PAGE_KEY) { type = PianoRoutes.PageType },
                                navArgument(PianoRoutes.CUT) {
                                    type = NavType.BoolType
                                    defaultValue = false
                                },
                            ),
                        ) { page ->
                            val tab = remember(page) { nav.getBackStackEntry(Route.Piano.path) }
                            val shown = page.arguments?.let { PianoRoutes.PageType[it, PianoRoutes.PAGE_KEY] } ?: SettingsPage.Feel
                            PianoPageScreen(tab, shown, onBack = { if (nav.isTop(page)) nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact widths: the four destinations along the bottom, short labels, the selected one on a
 * surfaceElevated pill, never a tint. Labels scale with the system font only as far as the widest
 * one ("Now playing") still fits its quarter of the bar on one line, and never past 1.5x, so the
 * four icons stay level.
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
        BoxWithConstraints {
            val itemWidth = (maxWidth - BAR_ITEM_GAP * (Route.entries.size - 1)) / Route.entries.size
            val cap = labelScaleThatFits(itemWidth)
            CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, cap))) {
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
}

/**
 * Medium and expanded widths: the same four glyphs and labels in a rail on the left, labels
 * always shown, set off from the content by a hairline. Labels scale up to 1.5x.
 */
@Composable
private fun TabRail(current: Route, onSelect: (Route) -> Unit) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSurface,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val density = LocalDensity.current
    Row {
        CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, MAX_LABEL_SCALE))) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            ) {
                Route.entries.forEach { route ->
                    NavigationRailItem(
                        selected = route == current,
                        onClick = { onSelect(route) },
                        icon = { Icon(painterResource(route.icon), contentDescription = null) },
                        label = { Text(route.label, maxLines = 1, textAlign = TextAlign.Center) },
                        alwaysShowLabel = true,
                        colors = colors,
                    )
                }
            }
        }
        VerticalDivider(thickness = Hairline, color = LocalHairline.current)
    }
}

/** The font scale, between 1x and [MAX_LABEL_SCALE], at which the widest destination label fills [itemWidth]. */
@Composable
private fun labelScaleThatFits(itemWidth: Dp): Float {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current.density
    return remember(itemWidth, style, density) {
        val unscaled = Density(density, 1f)
        val widest = Route.entries.maxOf { measurer.measure(it.label, style, maxLines = 1, density = unscaled).size.width }
        val room = with(unscaled) { itemWidth.toPx() } * LABEL_FILL
        if (widest <= 0) MAX_LABEL_SCALE else (room / widest).coerceIn(1f, MAX_LABEL_SCALE)
    }
}

private const val MAX_LABEL_SCALE = 1.5f

/** A label may use this share of its item's width, so it never touches its neighbour. */
private const val LABEL_FILL = 0.92f

/** Material's gap between bar items. */
private val BAR_ITEM_GAP = 8.dp

private fun NavController.showTab(route: Route) = navigate(route.path) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** From outside the bar (a notification, "Not connected", a piece starting to play): the tab, and for Piano its hub. */
private fun NavController.openTab(route: Route) {
    if (route != Route.Piano) return showTab(route)
    if (Route.of(currentDestination?.route) != Route.Piano) showTab(route)
    popBackStack(PianoRoutes.HUB, inclusive = false)
}

/** The bar or the rail: a tab comes back as it was left; Piano chosen while showing goes back to its hub. */
private fun NavController.selectTab(route: Route) {
    if (route == Route.Piano && Route.of(currentDestination?.route) == Route.Piano) {
        popBackStack(PianoRoutes.HUB, inclusive = false)
    } else {
        showTab(route)
    }
}

/** Whether [entry] is still the top of the stack: a second tap while a page comes or goes does nothing. */
private fun NavController.isTop(entry: NavBackStackEntry): Boolean = currentBackStackEntry?.id == entry.id

/** Both ends of the transition are the Piano tab's (its hub or a page). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.withinPianoTab(): Boolean =
    Route.of(initialState.destination.route) == Route.Piano && Route.of(targetState.destination.route) == Route.Piano

/** A phone opening a page over the hub (not one put back as the window narrowed, which just appears). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushesPage(frame: AppFrame): Boolean =
    !frame.twoPane && PianoRoutes.isPage(targetState.destination.route) && targetState.arguments?.getBoolean(PianoRoutes.CUT) != true

/** A phone closing a page back to the hub. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popsPage(frame: AppFrame): Boolean =
    !frame.twoPane && PianoRoutes.isPage(initialState.destination.route)

/**
 * Phones, inside the Piano tab: a page slides in from the end edge over the hub, which gives way by
 * a quarter of its width; back reverses it. 240 ms, standard easing; a cut when motion is reduced.
 */
private object PagePush {
    private const val GIVE_WAY = 4
    private val spec = tween<IntOffset>(Motion.StandardMs, easing = Motion.Standard)

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        scope.slideIntoContainer(SlideDirection.Start, spec)

    fun exitUnder(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        scope.slideOutOfContainer(SlideDirection.Start, spec) { it / GIVE_WAY }

    fun popEnterUnder(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        scope.slideIntoContainer(SlideDirection.End, spec) { it / GIVE_WAY }

    fun popExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        scope.slideOutOfContainer(SlideDirection.End, spec)
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
