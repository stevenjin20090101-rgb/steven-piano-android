// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.UpdateCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.NavRow
import dev.stevenjin.stevenpiano.ui.components.PageHeader
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.ShareDiagnosticsRow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.components.readingWidth
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.DisplayPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.FeelPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.FirmwarePage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.LightingPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.PedalPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.PlaybackPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.RemotePage
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.update.UpdateState
import dev.stevenjin.stevenpiano.web.WebStatus

/** The hub's column beside the open page on wide screens. */
private val HubWidth = 360.dp

/**
 * The Piano tab's hub (DESIGN.md › v1.5): the header and byline, the connection card with the
 * piano's status line under it, the UPDATE row while a newer release is known, then the groups
 * (PIANO, PLAYING, CONTROL, APP), each row a page with its one-line value or one of
 * the app's switches and actions, and the About row at the very bottom. Phones show the hub alone
 * and a page row pushes its page over it ([onOpenPage]). Wide screens set the hub in a 360 dp
 * column with the open page beside it, set off by a hairline; the open page's row is filled with
 * the elevated surface, and rows there only change which page is open (Feel at first). A phone
 * turned upright with a page open beside the hub puts that page back over it ([onReopenPage]).
 * [tab] is the tab's graph entry: the hub and its pages share one [PianoViewModel] through it, and
 * the piano saves its settings when the tab itself stops (another tab, the app in the background),
 * never when a page closes. The tab draws under the glass of the bar and the rail: it keeps clear of
 * the rail at its side, and each scrolling column ends with room for the bar ([LocalFloatingPadding]).
 */
@Composable
fun PianoScreen(tab: NavBackStackEntry, onOpenPage: (SettingsPage) -> Unit, onReopenPage: (SettingsPage) -> Unit) {
    val vm = pianoViewModel(tab)
    val frame = LocalAppFrame.current
    val hubScroll = rememberScrollState()
    LaunchedEffect(frame.twoPane) {
        if (!frame.twoPane) vm.takeOpened()?.let(onReopenPage)
    }
    if (frame.twoPane) {
        val selected by vm.selectedPage.collectAsStateWithLifecycle()
        Row(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(floatingSides()),
        ) {
            PianoHub(vm, hubScroll, selected, vm::pick, Modifier.width(HubWidth).fillMaxHeight())
            VerticalDivider(thickness = Hairline, color = LocalHairline.current)
            SettingsPageView(selected, vm, onBack = null, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        PianoHub(
            vm,
            hubScroll,
            selected = null,
            onPage = { page ->
                vm.open(page)
                onOpenPage(page)
            },
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(floatingSides()),
        )
    }
}

/** What keeps the tab clear of the rail (and the system bars at the sides); its background still reaches under them. */
@Composable
private fun floatingSides(): PaddingValues {
    val floating = LocalFloatingPadding.current
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = floating.calculateStartPadding(direction), end = floating.calculateEndPadding(direction))
}

/**
 * A page pushed over the hub on a phone, with the back glyph in its header (the back gesture works
 * too). Should the window widen (the phone turned on its side), the page moves beside the hub,
 * scrolled where it was ([onBack] takes this one off).
 */
@Composable
fun PianoPageScreen(tab: NavBackStackEntry, page: SettingsPage, onBack: () -> Unit) {
    val vm = pianoViewModel(tab)
    val frame = LocalAppFrame.current
    LaunchedEffect(frame.twoPane) {
        if (frame.twoPane) {
            vm.keepOpen(page)
            onBack()
        }
    }
    SettingsPageView(
        page,
        vm,
        onBack,
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(floatingSides()),
    )
}

/** The tab's one view model, kept by its graph entry; the piano saves its settings when that entry stops. */
@Composable
private fun pianoViewModel(tab: NavBackStackEntry): PianoViewModel {
    val graph = LocalContext.current.graph
    val vm: PianoViewModel = viewModel(tab) { PianoViewModel(graph, createSavedStateHandle()) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, lifecycleOwner = tab) { vm.leave() }
    return vm
}

@Composable
private fun PianoHub(vm: PianoViewModel, scroll: ScrollState, selected: SettingsPage?, onPage: (SettingsPage) -> Unit, modifier: Modifier) {
    val link by vm.link.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val piano by vm.piano.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val web by vm.web.collectAsStateWithLifecycle()
    val frame = LocalAppFrame.current
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(vm.canInstall()) }
    LifecycleResumeEffect(Unit) {
        canInstall = vm.canInstall()   // the person may come back from the Install unknown apps setting
        onPauseOrDispose { }
    }
    val summaries = remember(piano, settings, frame.wide, web) { GroupSummaries.from(piano, settings, frame.wide, web) }

    Column(modifier) {
        ScreenHeader("Piano", Modifier.readingWidth())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Column(Modifier.readingWidth()) {
                ConnectionCard(link, playing, vm::connect, vm::cancel, vm::disconnect, vm::connectTo, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                PianoStatusLine(piano, link is LinkState.Connected)
                UpdateRow(
                    update,
                    canInstall,
                    onUpdate = { vm.update(context) },
                    onRestart = { vm.restart(context) },
                    onAllowInstalls = { runCatching { context.startActivity(vm.installPermissionSettings()) } },
                )
                for (group in HubGroups.shown) {
                    SectionEyebrow(group.title)
                    for (row in group.rows) HubRowView(row, summaries, settings, update, vm, selected, onPage)
                }
                AboutRow(Modifier.padding(16.dp))
                Spacer(Modifier.height(LocalFloatingPadding.current.calculateBottomPadding()))
            }
        }
    }
}

@Composable
private fun HubRowView(
    row: HubRow,
    summaries: GroupSummaries,
    settings: PianoSettings,
    update: UpdateState,
    vm: PianoViewModel,
    selected: SettingsPage?,
    onPage: (SettingsPage) -> Unit,
) {
    when (row) {
        is HubRow.Page -> NavRow(row.page.title, summaries.of(row.page), onClick = { onPage(row.page) }, selected = selected?.let { it == row.page })
        HubRow.AutoConnect -> SwitchRow("Auto-connect on launch", settings.autoConnect, vm::setAutoConnect)
        HubRow.CheckForUpdates -> SwitchRow("Check for updates automatically", settings.checkForUpdates, vm::setCheckForUpdates)
        HubRow.CheckNow -> ActionRow(note = UpdateCopy.checkLine(update)) {
            ActionButton("Check now", onClick = vm::checkNow, enabled = update != UpdateState.Checking && !update.busy)
        }
        HubRow.ShareDiagnostics -> ShareDiagnosticsRow()
    }
}

/**
 * A page: its header (the back glyph on phones, [onBack]; the title alone beside the hub), then
 * its sections in a column that scrolls from anywhere across the pane, at the reading width.
 */
@Composable
private fun SettingsPageView(page: SettingsPage, vm: PianoViewModel, onBack: (() -> Unit)?, modifier: Modifier) {
    Column(modifier) {
        PageHeader(page.title, onBack, Modifier.readingWidth())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(vm.scrollOf(page)),
        ) {
            Column(Modifier.readingWidth()) {
                when (page) {
                    SettingsPage.Feel -> FeelPage(pianoReport(vm), vm)
                    SettingsPage.Lighting -> LightingPage(pianoReport(vm), vm)
                    SettingsPage.Pedal -> PedalPage(pianoReport(vm), vm)
                    SettingsPage.Firmware -> FirmwarePage(pianoReport(vm), vm)
                    SettingsPage.Playback -> PlaybackPage(appSettings(vm), vm)
                    SettingsPage.Display -> DisplayPage(appSettings(vm), vm)
                    SettingsPage.Remote -> RemotePage(appSettings(vm), webStatus(vm), vm)
                }
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.height(LocalFloatingPadding.current.calculateBottomPadding()))
            }
        }
    }
}

/** What a piano page shows of the piano, as it changes. */
@Composable
private fun pianoReport(vm: PianoViewModel): PianoReport {
    val link by vm.link.collectAsStateWithLifecycle()
    val piano by vm.piano.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    val statusReading by vm.statusReading.collectAsStateWithLifecycle()
    return PianoReport(piano, link is LinkState.Connected, statusText, statusReading)
}

/** Where the web panel listens, as it changes. */
@Composable
private fun webStatus(vm: PianoViewModel): WebStatus {
    val web by vm.web.collectAsStateWithLifecycle()
    return web
}

/** The app's preferences, as they change. */
@Composable
private fun appSettings(vm: PianoViewModel): PianoSettings {
    val settings by vm.settings.collectAsStateWithLifecycle()
    return settings
}
