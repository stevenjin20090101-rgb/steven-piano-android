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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
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
import dev.stevenjin.stevenpiano.ui.components.FloatingPlayLayer
import dev.stevenjin.stevenpiano.ui.components.FloatingPlaySlot
import dev.stevenjin.stevenpiano.ui.components.GlassEdge
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.LocalArtworkMonochrome
import dev.stevenjin.stevenpiano.ui.components.LocalFloatingPlaySlot
import dev.stevenjin.stevenpiano.ui.components.LocalHazeState
import dev.stevenjin.stevenpiano.ui.components.LocalOnGlass
import dev.stevenjin.stevenpiano.ui.components.LocalReducedTransparency
import dev.stevenjin.stevenpiano.ui.components.MiniPlayer
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.hazeSource
import dev.stevenjin.stevenpiano.ui.components.miniPlayerShown
import dev.stevenjin.stevenpiano.ui.components.rememberHazeState
import dev.stevenjin.stevenpiano.ui.screens.display.DisplayScreen
import dev.stevenjin.stevenpiano.ui.screens.keys.KeysScreen
import dev.stevenjin.stevenpiano.ui.screens.library.LibraryScreen
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.NowPlayingScreen
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoPageScreen
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoScreen
import dev.stevenjin.stevenpiano.ui.theme.LocalHandColours
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedTransparency
import kotlinx.coroutines.flow.first
import kotlin.math.min

/**
 * The app's frame: four destinations (Library, Now playing, Keys, Piano) in a bottom navigation
 * bar on compact widths, or a navigation rail on the left on medium and expanded ones ([frame]),
 * with a 240 ms fade-through between them, or a cut when motion is reduced. The bar and the rail
 * are glass (DESIGN.md › v1.5 — M16): the content keeps only the top inset and draws beneath them,
 * recorded as the glass's source ([hazeSource]), and each screen keeps clear of them through
 * [LocalFloatingPadding] (lists scroll under the bar, fixed layouts stop above it). The rail is a
 * sibling of the content, laid over its start edge, never inside its own source. The Piano tab is a
 * graph of its own: its hub, and on phones its pages (`piano/{page}`), pushed over the hub with the
 * bar still there and popped by back; a tab keeps its place when another is chosen, and choosing
 * Piano again goes back to its hub. Artwork everywhere follows the Display page's black-and-white
 * switch ([LocalArtworkMonochrome]), and the waterfall its Hand colours switch ([LocalHandColours]).
 * [requestedTab] switches destination from outside (a shared file, a notification; Piano opens on
 * its hub); [onImport] brings files into the library. Every touch anywhere is watched ([watchTouches]):
 * with Display mode after a minute on, a minute without one while a piece is loaded brings display
 * mode over the whole window ([DisplayScreen], DESIGN.md › v1.5 — M17), and the next touch leaves it.
 * In kiosk mode (DESIGN.md › v1.6.1 — M20) display mode is always on and is the resting state, with a
 * piece or without; the byline on every tab is the hidden way out ([LocalBylineHold]), and its PIN
 * sheet opens over whatever tab is showing.
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
    val reducedTransparency = rememberReducedTransparency()
    val content = rememberHazeState()
    val floatingPlay = remember { FloatingPlaySlot() }
    // Display mode (DESIGN.md › v1.5 — M17): every touch anywhere keeps it away, a minute without one brings it.
    // In kiosk mode it is always on (v1.6.1 — M20), and the byline's hold opens the kiosk's PIN sheet.
    val kiosk = settings.kioskEnabled
    val idle = rememberIdle(enabled = DisplayRule.watched(settings.displayModeAfterMinute, kiosk), timeoutMs = DisplayModeTimeout.ms)
    val onTouch = remember(idle) { { idle.touch() } }
    var kioskSheet by rememberSaveable { mutableStateOf(false) }
    val openKioskSheet = remember { { kioskSheet = true } }
    LaunchedEffect(kiosk) { if (!kiosk) kioskSheet = false }   // kiosk mode ended some other way: no sheet left to come back

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
        LocalReducedTransparency provides reducedTransparency,
        LocalHazeState provides content,
        LocalFloatingPlaySlot provides floatingPlay,
        LocalBylineHold provides if (kiosk) openKioskSheet else null,
        LocalIdleState provides idle,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .watchTouches(onTouch),
        ) {
            RailFrame(
                rail = if (frame.rail) ({ TabRail(current, select) }) else null,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) { railWidth ->
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    // Every inset reaches the padding below; the content itself keeps only the top one.
                    // A phone on its side may have its navigation buttons or its camera cutout at either end.
                    contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
                    bottomBar = { if (!frame.rail) BottomBar(current, select, playback, onOpenNowPlaying = { open(Route.NowPlaying) }) },
                ) { padding ->
                    val direction = LocalLayoutDirection.current
                    val top = padding.calculateTopPadding()
                    val floating = PaddingValues(
                        start = max(padding.calculateStartPadding(direction), railWidth),
                        end = padding.calculateEndPadding(direction),
                        bottom = padding.calculateBottomPadding(),
                    )
                    CompositionLocalProvider(LocalFloatingPadding provides floating) {
                        Box(Modifier.fillMaxSize()) {
                            NavHost(
                                navController = nav,
                                startDestination = Route.Library.path,
                                modifier = Modifier
                                    .padding(top = top)
                                    .consumeWindowInsets(PaddingValues(top = top))
                                    .hazeSource(content),
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
                                    LibraryScreen(playback, onPlaying = { open(Route.NowPlaying) }, onOpenPiano = { open(Route.Piano) }, onImport = onImport)
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
                            // Over the content and beside its source, never inside it: a playlist's floating Play.
                            FloatingPlayLayer(floatingPlay, shown = current == Route.Library)
                        }
                    }
                }
            }
            // Composed last, a sibling after the frame: above the glass bar, the mini player and the rail.
            DisplayOverlay(idle, kiosk, onLeave = onTouch)
        }
        if (kioskSheet && kiosk) KioskExitSheet(onDismiss = { kioskSheet = false })
    }
}

/**
 * Display mode over the whole window while the app is idle and a piece is loaded, or in kiosk mode
 * with nothing loaded too (its resting state); not a route, so the tabs beneath keep their state. It
 * reads the player here, apart from the frame, so a piece changing never recomposes the frame. In
 * kiosk mode, coming to rest ends an "Unlock for now": a tablet left alone locks itself again.
 */
@Composable
private fun DisplayOverlay(idle: IdleState, kiosk: Boolean, onLeave: () -> Unit) {
    if (!idle.idle) return
    val graph = LocalContext.current.graph
    val state by graph.player.state.collectAsStateWithLifecycle()
    if (!DisplayRule.shows(state.piece != null, kiosk)) return
    if (kiosk) LaunchedEffect(Unit) { graph.kiosk.relock() }
    DisplayScreen(onLeave)
}

/**
 * Wide frames: the content fills the window and [rail] floats over its start edge, placed after it
 * so it draws on top, a sibling and never a descendant of the content's glass source; [content]
 * learns the rail's measured width (0 without one) before it is laid out, so nothing jumps.
 */
@Composable
private fun RailFrame(rail: (@Composable () -> Unit)?, modifier: Modifier, content: @Composable (railWidth: Dp) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val railPlaceables = if (rail == null) {
            emptyList()
        } else {
            val tall = Constraints(maxWidth = constraints.maxWidth, minHeight = constraints.maxHeight, maxHeight = constraints.maxHeight)
            subcompose(FrameSlot.Rail, rail).map { it.measure(tall) }
        }
        val railWidth = railPlaceables.maxOfOrNull { it.width } ?: 0
        val contentPlaceables = subcompose(FrameSlot.Content) { content(railWidth.toDp()) }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.placeRelative(0, 0) }
            railPlaceables.forEach { it.placeRelative(0, 0) }
        }
    }
}

private enum class FrameSlot { Rail, Content }

/**
 * Compact widths: the glass along the bottom, holding the tab bar and above it, on phones, the mini
 * player whenever a piece is loaded or loading (not on Now playing itself, which is the full
 * player), a hairline between them, or while a piece loads the moving hairline. The mini player
 * grows in from the bar and gives way into it over 240 ms (a cut when motion is reduced); the
 * floating padding follows it.
 */
@Composable
private fun BottomBar(current: Route, onSelect: (Route) -> Unit, playback: PlaybackStarter, onOpenNowPlaying: () -> Unit) {
    val state by LocalContext.current.graph.player.state.collectAsStateWithLifecycle()
    val frame = LocalAppFrame.current
    val reduced = rememberReducedMotion()
    // Lists pass beneath the bar (the Library, the Piano tab); Now playing and Keys stop above it.
    GlassSurface(Modifier.fillMaxWidth(), blur = current == Route.Library || current == Route.Piano) {
        Column {
            AnimatedVisibility(
                visible = !frame.twoPane && state.miniPlayerShown && current != Route.NowPlaying,
                enter = if (reduced) EnterTransition.None else MiniPlayerMotion.enter,
                exit = if (reduced) ExitTransition.None else MiniPlayerMotion.exit,
            ) {
                Column {
                    MiniPlayer(state, onOpen = onOpenNowPlaying, onPlayPause = playback::togglePlayPause, onNext = playback::next)
                    if (state.loading) ProgressHairline(null) else HairlineDivider()
                }
            }
            TabBar(current, onSelect)
        }
    }
}

/** The mini player coming and going: its height and its opacity together, 240 ms, standard easing. */
private object MiniPlayerMotion {
    val enter = expandVertically(tween(Motion.StandardMs, easing = Motion.Standard)) + fadeIn(tween(Motion.StandardMs, easing = Motion.Standard))
    val exit = shrinkVertically(tween(Motion.StandardMs, easing = Motion.Standard)) + fadeOut(tween(Motion.StandardMs, easing = Motion.Standard))
}

/**
 * Compact widths: the four destinations along the bottom, short labels, the selected one on a
 * surfaceElevated pill, never a tint. The bar is transparent: the glass under it ([BottomBar]) is
 * its container. Labels scale with the system font only as far as the widest one ("Now playing")
 * still fits its quarter of the bar on one line, and never past 1.5x, so the four icons stay level.
 */
@Composable
private fun TabBar(current: Route, onSelect: (Route) -> Unit) {
    val tones = tabTones()
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = tones.selected,
        selectedTextColor = tones.selected,
        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
        unselectedIconColor = tones.unselectedIcon,
        unselectedTextColor = tones.unselectedText,
    )
    val density = LocalDensity.current
    BoxWithConstraints {
        val itemWidth = (maxWidth - BAR_ITEM_GAP * (Route.entries.size - 1)) / Route.entries.size
        val cap = labelScaleThatFits(itemWidth)
        CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, cap))) {
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
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

/**
 * Medium and expanded widths: the same four glyphs and labels in a rail on the left, labels
 * always shown, on glass whose end edge (a hairline and the specular line) sets it off from the
 * content beside it. Every screen keeps clear of the rail, so it has the glass's look without
 * blurring (see [GlassSurface]). Labels scale up to 1.5x.
 */
@Composable
private fun TabRail(current: Route, onSelect: (Route) -> Unit) {
    // Every screen keeps clear of the rail (the floating padding's start), so nothing passes beneath it.
    GlassSurface(Modifier.fillMaxHeight(), edge = GlassEdge.End, blur = false) {
        val tones = tabTones()
        val colors = NavigationRailItemDefaults.colors(
            selectedIconColor = tones.selected,
            selectedTextColor = tones.selected,
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            unselectedIconColor = tones.unselectedIcon,
            unselectedTextColor = tones.unselectedText,
        )
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, MAX_LABEL_SCALE))) {
            NavigationRail(
                containerColor = Color.Transparent,
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
    }
}

/** The bar's and the rail's colours: the chosen tab in the content colour on its pill, the others secondary. */
private class TabTones(val selected: Color, val unselectedIcon: Color, val unselectedText: Color)

/**
 * On glass the labels are all the content colour (text on glass is primary: GlassTokensTest), and
 * the chosen tab is told by its pill and its brighter glyph; on the solid surface, today's
 * secondary labels.
 */
@Composable
private fun tabTones(): TabTones {
    val primary = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    return TabTones(primary, secondary, if (LocalOnGlass.current) primary else secondary)
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
