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
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
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
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.ui.components.AuraDot
import dev.stevenjin.stevenpiano.ui.components.FloatingPlayLayer
import dev.stevenjin.stevenpiano.ui.components.FloatingPlaySlot
import dev.stevenjin.stevenpiano.ui.components.GlassEdge
import dev.stevenjin.stevenpiano.ui.components.GlassHidden
import dev.stevenjin.stevenpiano.ui.components.GlassShown
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.HazeState
import dev.stevenjin.stevenpiano.ui.components.LinkDot
import dev.stevenjin.stevenpiano.ui.components.LiveDot
import dev.stevenjin.stevenpiano.ui.components.LocalArtworkMonochrome
import dev.stevenjin.stevenpiano.ui.components.LocalFloatingPlaySlot
import dev.stevenjin.stevenpiano.ui.components.LocalHazeState
import dev.stevenjin.stevenpiano.ui.components.LocalOnGlass
import dev.stevenjin.stevenpiano.ui.components.LocalReducedTransparency
import dev.stevenjin.stevenpiano.ui.components.MicrophoneBanner
import dev.stevenjin.stevenpiano.ui.components.MiniPlayer
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.hazeSource
import dev.stevenjin.stevenpiano.ui.components.miniPlayerShown
import dev.stevenjin.stevenpiano.ui.components.rememberBackdrop
import dev.stevenjin.stevenpiano.ui.components.rememberHazeState
import dev.stevenjin.stevenpiano.ui.screens.display.DisplayScreen
import dev.stevenjin.stevenpiano.ui.screens.display.RestingMotion
import dev.stevenjin.stevenpiano.ui.screens.keys.KeysScreen
import dev.stevenjin.stevenpiano.ui.screens.library.LibraryScreen
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.NowPlayingScreen
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoPageScreen
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoScreen
import dev.stevenjin.stevenpiano.ui.screens.quiet.LocalPlayAnyway
import dev.stevenjin.stevenpiano.ui.screens.quiet.LocalQuietAsk
import dev.stevenjin.stevenpiano.ui.screens.quiet.QuietChoiceDialog
import dev.stevenjin.stevenpiano.ui.screens.studio.StudioScreen
import dev.stevenjin.stevenpiano.ui.theme.GlassTokens
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalHandColours
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.SelectedAlpha
import dev.stevenjin.stevenpiano.ui.theme.rememberGlassAccessibility
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The app's frame: five destinations (Library, Now playing, Keys, Studio, Piano; Studio since v1.12 — M30) in a bottom navigation
 * bar on compact widths, or a navigation rail on the left on medium and expanded ones ([frame]),
 * with a fade-through between them (out 120 ms, in 200 ms), or a cut when motion is reduced. The bar and the rail
 * are glass (DESIGN.md › v1.5 — M16, v1.9): the content fills the window and draws beneath them, the
 * status bar included (each pane's glass header reaches up under it), recorded as the glass's source
 * ([hazeSource], which [content] is: the sheets, menus and dialogs blur it too), and each screen keeps
 * clear of them through [LocalFloatingPadding] (lists scroll under the bar, fixed layouts stop above
 * it). The rail is a sibling of the content, laid over its start edge, never inside its own source.
 * High contrast text stands for Reduce Transparency and Increase Contrast ([rememberGlassAccessibility]):
 * solid surfaces, and every hairline stronger ([LocalHairline]). The Piano tab is a
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
fun PianoNavHost(
    frame: AppFrame,
    requestedTab: Route?,
    onTabShown: () -> Unit,
    content: HazeState = rememberHazeState(),
    onImport: (ImportSource) -> Unit,
) {
    val nav = rememberNavController()
    val reduced = rememberReducedMotion()
    val playback = rememberPlaybackStarter()
    val entry by nav.currentBackStackEntryAsState()
    val current = Route.of(entry?.destination?.route) ?: Route.Library
    val open: (Route) -> Unit = remember(nav) { { route -> nav.openTab(route) } }
    val select: (Route) -> Unit = remember(nav) { { route -> nav.selectTab(route) } }
    // Studio's Listen (v1.7 — M23): the piece it made plays, and Now playing shows it (and asks Keep or Discard after a first listen).
    val listen: (Long) -> Unit = remember(playback, open) { { pieceId -> playback.play(pieceId, listOf(pieceId)); open(Route.NowPlaying) } }
    val settings by LocalContext.current.graph.settings.collectAsStateWithLifecycle()
    val glass = rememberGlassAccessibility()
    // Increase contrast (DESIGN.md › v1.9): every hairline in the content colour at 0.4 instead of the hairline token.
    val hairline = if (glass.increasedContrast) MaterialTheme.colorScheme.onSurface.copy(alpha = GlassTokens.ContrastHairlineAlpha) else LocalHairline.current
    val floatingPlay = remember { FloatingPlaySlot() }
    // Display mode (DESIGN.md › v1.5 — M17): every touch anywhere keeps it away, a minute without one brings it.
    // In kiosk mode it is always on (v1.6.1 — M20), and the byline's hold opens the kiosk's PIN sheet.
    val kiosk = settings.kioskEnabled
    val idle = rememberIdle(enabled = DisplayRule.watched(settings.displayModeAfterMinute, kiosk), timeoutMs = DisplayModeTimeout.ms)
    val onTouch = remember(idle) { { idle.touch() } }
    // A MIDI keyboard played counts as a touch (v1.11 — M29): display mode stays away while someone plays it.
    val keyboard = LocalContext.current.graph.keyboard
    val keyboardState by keyboard.state.collectAsStateWithLifecycle()
    if (keyboardState.connected) {
        LaunchedEffect(idle, keyboard) {
            var seen = keyboard.changes.get()
            while (true) {
                delay(KEYBOARD_TOUCH_MS)
                val now = keyboard.changes.get()
                if (now != seen) {
                    seen = now
                    idle.touch()
                }
            }
        }
    }
    var kioskSheet by rememberSaveable { mutableStateOf(false) }
    val openKioskSheet = remember { { kioskSheet = true } }
    // Play anyway (v1.20 — M54): behind the kiosk PIN while the kiosk keeps the settings locked, from any screen.
    val quietGate = rememberKioskGate()
    val playAnyway: () -> Unit = remember(quietGate, playback) { { quietGate.run { playback.playAnyway() } } }
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
        LocalReducedTransparency provides glass.reducedTransparency,
        LocalHairline provides hairline,
        LocalHazeState provides content,
        LocalFloatingPlaySlot provides floatingPlay,
        LocalBylineHold provides if (kiosk) openKioskSheet else null,
        LocalIdleState provides idle,
        LocalPlayAnyway provides playAnyway,
        LocalQuietAsk provides playback::whenAllowed,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .watchTouches(onTouch),
        ) {
            RailFrame(
                rail = if (frame.rail) ({ TabRail(current, select, settings.albumBackdrop) }) else null,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) { railWidth ->
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    // Every inset reaches the padding below; the content itself keeps only the top one.
                    // A phone on its side may have its navigation buttons or its camera cutout at either end.
                    contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
                    bottomBar = { if (!frame.rail) BottomBar(current, select, playback, settings.albumBackdrop, onOpenNowPlaying = { open(Route.NowPlaying) }) },
                ) { padding ->
                    val direction = LocalLayoutDirection.current
                    val top = padding.calculateTopPadding()
                    // The top is the status bar's until a pane's glass header takes it (GlassHeaderPane): the
                    // headers reach up under the status bar, and each pane's content starts below its header.
                    val floating = PaddingValues(
                        start = max(padding.calculateStartPadding(direction), railWidth),
                        top = top,
                        end = padding.calculateEndPadding(direction),
                        bottom = padding.calculateBottomPadding(),
                    )
                    CompositionLocalProvider(LocalFloatingPadding provides floating) {
                        Box(Modifier.fillMaxSize()) {
                            NavHost(
                                navController = nav,
                                startDestination = Route.Library.path,
                                modifier = Modifier
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
                                    LibraryScreen(playback, onPlaying = { open(Route.NowPlaying) }, onOpenPiano = { open(Route.Piano) }, onImport = onImport, onOpenStudio = { open(Route.Studio) })
                                }
                                composable(Route.NowPlaying.path) {
                                    NowPlayingScreen(playback, onOpenPiano = { open(Route.Piano) })
                                }
                                composable(Route.Keys.path) { KeysScreen(onOpenPiano = { open(Route.Piano) }, onListen = listen) }
                                composable(Route.Studio.path) { StudioScreen(onListen = listen) }
                                navigation(startDestination = PianoRoutes.HUB, route = Route.Piano.path) {
                                    composable(PianoRoutes.HUB) { hub ->
                                        val tab = remember(hub) { nav.getBackStackEntry(Route.Piano.path) }
                                        PianoScreen(
                                            tab,
                                            onOpenPage = { page -> if (nav.isTop(hub)) nav.navigate(PianoRoutes.page(page)) },
                                            onReopenPage = { page -> if (nav.isTop(hub)) nav.navigate(PianoRoutes.page(page, cut = true)) },
                                            onOpenTab = open,
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
                                        PianoPageScreen(
                                            tab,
                                            shown,
                                            onBack = { if (nav.isTop(page)) nav.popBackStack() },
                                            onOpenPage = { other -> if (nav.isTop(page)) nav.navigate(PianoRoutes.page(other)) },
                                        )
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
            // While the microphone is on (v1.23 — M55): on every screen, the resting screen too, with Stop.
            MicrophoneBanner(Modifier.align(Alignment.TopCenter))
        }
        if (kioskSheet && kiosk) KioskExitSheet(onDismiss = { kioskSheet = false })
        // A play asked for during a quiet time (v1.20 — M54): its choice, and the kiosk PIN Play anyway may ask for.
        QuietChoiceDialog(playback, quietGate)
        KioskGateSheet(quietGate)
    }
}

/**
 * Display mode over the whole window while the app is idle and a piece is loaded, or in kiosk mode
 * with nothing loaded too (its resting state); not a route, so the tabs beneath keep their state. It
 * comes over the app in a slow cross-fade and goes on a touch in a quicker one (DESIGN.md › v1.7.1,
 * [RestingMotion]; cuts under reduced motion): from the moment it starts to go, the app beneath is
 * live, its touches, Back and TalkBack. It reads only whether a piece is loaded here, apart from the
 * frame, so a piece changing never recomposes the frame. In kiosk mode, coming to rest ends an
 * "Unlock for now": a tablet left alone locks itself again.
 */
@Composable
private fun DisplayOverlay(idle: IdleState, kiosk: Boolean, onLeave: () -> Unit) {
    val graph = LocalContext.current.graph
    val player = graph.player.state.collectAsStateWithLifecycle()
    val loaded by remember(player) { derivedStateOf { player.value.piece != null } }
    val reduced = rememberReducedMotion()
    AnimatedVisibility(
        visible = idle.idle && DisplayRule.shows(loaded, kiosk),
        enter = RestingMotion.enter(reduced),
        exit = RestingMotion.leave(reduced),
        label = "resting screen",
    ) {
        val resting = transition.targetState == EnterExitState.Visible
        if (kiosk) LaunchedEffect(Unit) { graph.kiosk.relock() }
        DisplayScreen(onLeave, resting)
    }
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
 * grows in from the bar and gives way into it over 200 ms (a cut when motion is reduced); the
 * floating padding follows it. Over Now playing's album colours (v1.15 — M41) it is the surface at
 * the bar's fill, so they show through it without a blur on every frame.
 */
@Composable
private fun BottomBar(current: Route, onSelect: (Route) -> Unit, playback: PlaybackStarter, albumBackdrop: Boolean, onOpenNowPlaying: () -> Unit) {
    val state by LocalContext.current.graph.player.state.collectAsStateWithLifecycle()
    val frame = LocalAppFrame.current
    val reduced = rememberReducedMotion()
    // Lists pass beneath the bar (the Library, the Piano tab), and the frost thickens along its top edge
    // where they do (the scroll-edge effect); Now playing and Keys stop above it.
    val lists = current == Route.Library || current == Route.Piano
    val playing = state.piece?.takeIf { current == Route.NowPlaying }
    val backdrop = rememberBackdrop(playing?.pieceId, playing?.composerKey, albumBackdrop) != null
    GlassSurface(Modifier.fillMaxWidth(), blur = lists, band = if (lists) GlassShown else GlassHidden, translucent = backdrop) {
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

/** The mini player coming and going: its height and its opacity together, 200 ms, standard easing. */
private object MiniPlayerMotion {
    val enter = expandVertically(tween(Motion.StandardMs, easing = Motion.Standard)) + fadeIn(tween(Motion.StandardMs, easing = Motion.Standard))
    val exit = shrinkVertically(tween(Motion.StandardMs, easing = Motion.Standard)) + fadeOut(tween(Motion.StandardMs, easing = Motion.Standard))
}

/**
 * Compact widths: the five destinations along the bottom, short labels, the selected one on a
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
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (cap < 1f) cap else min(density.fontScale, cap))) {
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                Route.entries.forEach { route ->
                    NavigationBarItem(
                        selected = route == current,
                        onClick = { onSelect(route) },
                        icon = { TabIcon(route) },
                        label = { Text(route.label, maxLines = 1) },
                        colors = colors,
                    )
                }
            }
        }
    }
}

/**
 * Medium and expanded widths (v1.25 — M59, the web panel's rail as thin as the one before it): a floating card of regular
 * glass, [RailWidth] wide and 8 dp in from the start, the top (under the status bar) and the bottom, in 20 dp corners,
 * its hairline and specular line all the way round ([GlassEdge.Outline]). At its top the app's mark with the piano
 * link's dot on its corner and "Steven Piano" under it ([RailIdentity]); then the five places, each its glyph over its
 * label ([RailItem]). Every screen keeps clear of the card and its two gaps (the floating padding's start, 96 dp), so it
 * has the glass's look without blurring (see [GlassSurface]). While Now playing shows the album's colours
 * ([albumBackdrop]) they reach under the card and round it (v1.18 — M49), and the card is the surface at the bar's fill
 * over them (v1.15 — M41), as the header is. Labels scale with the system font up to 1.5x, as far as "Now playing" still
 * fits its 74 dp on one line, and shrink rather than wrap; a window too short for the card lets it scroll.
 */
@Composable
private fun TabRail(current: Route, onSelect: (Route) -> Unit, albumBackdrop: Boolean) {
    val graph = LocalContext.current.graph
    val state by graph.player.state.collectAsStateWithLifecycle()
    val link by graph.pianoLink.state.collectAsStateWithLifecycle()
    val playing = state.piece?.takeIf { current == Route.NowPlaying }
    val backdrop = rememberBackdrop(playing?.pieceId, playing?.composerKey, albumBackdrop) != null
    // The piano link, as the "Sent to piano" capsule reads it: red only while a piece's notes go to the piano.
    val dot = LinkDot.of(link is LinkState.Connected, sending = state.status == PlaybackStatus.Playing)
    val label = railLabel()
    val density = LocalDensity.current
    val cap = labelScaleThatFits(RailWidth - RailSide * 2, label)
    CompositionLocalProvider(LocalDensity provides Density(density.density, if (cap < 1f) cap else min(density.fontScale, cap))) {
        Box(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                .padding(RailInset),
        ) {
            GlassSurface(
                Modifier
                    .width(RailWidth)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(RailCorner),
                edge = GlassEdge.Outline,
                blur = false,
                translucent = backdrop,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = RailSide, end = RailSide, top = 16.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RailIdentity(dot)
                    Route.entries.forEach { route -> RailItem(route, route == current, label) { onSelect(route) } }
                }
            }
        }
    }
}

/**
 * The top of the rail's card: the app's mark (the launcher icon's tile and bars, 28 dp) with the piano link's dot on its
 * top corner, 8 dp and ringed 2 dp in the surface colour ([LinkDot]: a ring while not connected, the content colour
 * while connected and silent, the live red while notes go to the piano), and under it "Steven Piano" on two centred
 * lines, as Steven asked rather than a word for the dot. The dot's state is the mark's description for TalkBack.
 */
@Composable
private fun RailIdentity(dot: LinkDot) {
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.semantics { contentDescription = railDotWord(dot) }) {
            Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(MarkSize))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = MarkDotRing + 5.dp, y = -(MarkDotRing + 3.dp))
                    .background(surface, CircleShape)
                    .padding(MarkDotRing),
            ) {
                LiveDot(dot, size = MarkDotSize)
            }
        }
        Text(
            "Steven\nPiano",
            modifier = Modifier
                .padding(top = 6.dp)
                .clearAndSetSemantics { contentDescription = "Steven Piano" },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
            color = ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** What TalkBack hears of the rail's dot. */
private fun railDotWord(dot: LinkDot): String = when (dot) {
    LinkDot.NotConnected -> "Not connected"
    LinkDot.Connected -> "Connected"
    LinkDot.Sending -> "Sending notes to the piano"
}

/**
 * A place on the rail: its glyph (24 dp) over its label ([label]: 12 sp medium, one line, centred), 4 dp apart, at
 * least 64 dp tall in 14 dp corners. The chosen place sits on the content colour at [SelectedAlpha] with its glyph in
 * the content colour; the others' glyphs are the secondary grey, and every label is the content colour (text on a
 * bar's glass is). The fill eases in and out over 200 ms, a cut when motion is reduced; a press ripples.
 */
@Composable
private fun RailItem(route: Route, selected: Boolean, label: TextStyle, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val reduced = rememberReducedMotion()
    val fill by animateColorAsState(ink.copy(alpha = if (selected) SelectedAlpha else 0f), Motion.timed(Motion.StandardMs, reduced), label = "rail place")
    val glyph by animateColorAsState(if (selected) ink else MaterialTheme.colorScheme.onSurfaceVariant, Motion.timed(Motion.StandardMs, reduced), label = "rail glyph")
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RailItemHeight)
            .clip(RoundedCornerShape(RailItemCorner))
            .background(fill)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TabIcon(route, tint = glyph)
        Text(
            route.label,
            modifier = Modifier.padding(top = 4.dp),
            style = label,
            color = ink,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

/** The rail's labels: the eyebrow's 12 sp medium without its tracking, as the panel's rail sets them. */
@Composable
private fun railLabel(): TextStyle = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)

/** The bar's colours: the chosen tab in the content colour on its pill, the others secondary (the rail's are [RailItem]'s). */
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

/**
 * A destination's glyph (the web panel's stroke glyph, v1.25 — M59) in [tint]; Studio's carries a 6 dp dot of the aura
 * while a job runs (v1.12 — M30), never the live red, and TalkBack hears it in the tab's name ("Studio, composing").
 */
@Composable
private fun TabIcon(route: Route, tint: Color = LocalContentColor.current) {
    if (route != Route.Studio) {
        Icon(painterResource(route.icon), contentDescription = null, tint = tint)
        return
    }
    val jobs by LocalContext.current.graph.studio.jobs.jobs.collectAsStateWithLifecycle()
    val busy = jobs.any { it.state == JobState.Running || it.state == JobState.Queued }
    Box(if (busy) Modifier.semantics { stateDescription = "working" } else Modifier) {
        Icon(painterResource(route.icon), contentDescription = null, tint = tint)
        if (busy) AuraDot(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp))
    }
}

/**
 * The font scale, between [MIN_LABEL_SCALE] and [MAX_LABEL_SCALE], at which the widest destination label in [style]
 * fills [itemWidth]: five labels on a compact bar may scale down to 0.85 (v1.12 — M30), and so may the rail's in its
 * thin card (v1.25 — M59); none is ever shortened.
 */
@Composable
private fun labelScaleThatFits(itemWidth: Dp, style: TextStyle = MaterialTheme.typography.labelMedium): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    return remember(itemWidth, style, density) {
        val unscaled = Density(density, 1f)
        val widest = Route.entries.maxOf { measurer.measure(it.label, style, maxLines = 1, density = unscaled).size.width }
        val room = with(unscaled) { itemWidth.toPx() } * LABEL_FILL
        if (widest <= 0) MAX_LABEL_SCALE else (room / widest).coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE)
    }
}

private const val MAX_LABEL_SCALE = 1.5f
private const val MIN_LABEL_SCALE = 0.85f

/** How often the nav host looks whether a MIDI keyboard was played (v1.11 — M29), as a touch for display mode. */
private const val KEYBOARD_TOUCH_MS = 500L

/** A label may use this share of its item's width, so it never touches its neighbour. */
private const val LABEL_FILL = 0.92f

/** Material's gap between bar items. */
private val BAR_ITEM_GAP = 8.dp

/** The rail's card (v1.25 — M59): its width, its gap to the window's edges and the content, its corners, its sides' room. */
private val RailWidth = 80.dp
private val RailInset = 8.dp
private val RailCorner = 20.dp
private val RailSide = 3.dp

/** A place on the rail: at least this tall, in these corners. */
private val RailItemHeight = 64.dp
private val RailItemCorner = 14.dp

/** The mark at the rail's top, and the dot on its corner with the surface's ring round it. */
private val MarkSize = 28.dp
private val MarkDotSize = 8.dp
private val MarkDotRing = 2.dp

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
 * a quarter of its width; back reverses it. 200 ms, standard easing; a cut when motion is reduced.
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

/**
 * The fade-through between tabs (DESIGN.md › v1.14 — motion): the old tab fades out in 120 ms, accelerating, then
 * the new one fades and grows in from 0.92 in 200 ms, decelerating.
 */
private object FadeThrough {
    private const val OUT_MS = Motion.QuickMs
    private const val IN_MS = Motion.StandardMs

    val enter = fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = Motion.Enter)) +
        scaleIn(tween(IN_MS, delayMillis = OUT_MS, easing = Motion.Enter), initialScale = Motion.GrowFrom)
    val exit = fadeOut(tween(OUT_MS, easing = Motion.Leave))
}
