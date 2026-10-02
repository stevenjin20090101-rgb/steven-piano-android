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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.firmware.FirmwarePiano
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.MidiNames
import dev.stevenjin.stevenpiano.instruments.MidiTransport
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.KioskGate
import dev.stevenjin.stevenpiano.ui.KioskGateSheet
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.KioskLockCopy
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.LockGlyph
import dev.stevenjin.stevenpiano.ui.LockedFirmware
import dev.stevenjin.stevenpiano.ui.LockedPage
import dev.stevenjin.stevenpiano.ui.rememberKioskGate
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.UpdateCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassHeaderPane
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.NavRow
import dev.stevenjin.stevenpiano.ui.components.PageHeader
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.ShareDiagnosticsRow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.components.readingWidth
import dev.stevenjin.stevenpiano.ui.components.scrollEdges
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.DisplayPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.FeelPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.FirmwarePage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.FirmwareReport
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.InstrumentPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.KeyboardPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.KioskPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.LockedFirmwareUpdate
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.LightingPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.PedalPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.PlaybackPage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.RemotePage
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.SchedulePage
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
 * The hub and each page scroll beneath their own glass headers (DESIGN.md › v1.9).
 * In kiosk mode the settings are locked (DESIGN.md › v1.6.1 — M20): the rows show a padlock, and a
 * page, a switch of the APP group, Check now and Disconnect wait for the kiosk PIN ([KioskGate]).
 */
@Composable
fun PianoScreen(tab: NavBackStackEntry, onOpenPage: (SettingsPage) -> Unit, onReopenPage: (SettingsPage) -> Unit) {
    val vm = pianoViewModel(tab)
    val frame = LocalAppFrame.current
    val hubScroll = rememberScrollState()
    val gate = rememberKioskGate()
    LaunchedEffect(frame.twoPane) {
        if (!frame.twoPane) vm.takeOpened()?.let(onReopenPage)
    }
    if (frame.twoPane) {
        val chosen by vm.selectedPage.collectAsStateWithLifecycle()
        val kind by vm.instrumentKind.collectAsStateWithLifecycle()
        // A page of Steven Piano's own while a MIDI piano plays (v1.11 — M29): Playback stands in for it.
        val selected = if (kind == InstrumentKind.MidiPiano && chosen.piano != null) SettingsPage.Playback else chosen
        Row(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(floatingSides()),
        ) {
            PianoHub(vm, hubScroll, selected, { page -> gate.openPage(vm, page) { vm.pick(page) } }, Modifier.width(HubWidth).fillMaxHeight(), gate)
            VerticalDivider(thickness = Hairline, color = LocalHairline.current)
            SettingsPageView(selected, vm, onBack = null, Modifier.weight(1f).fillMaxHeight(), gate)
        }
    } else {
        PianoHub(
            vm,
            hubScroll,
            selected = null,
            onPage = { page ->
                gate.openPage(vm, page) {
                    vm.open(page)
                    onOpenPage(page)
                }
            },
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(floatingSides()),
            gate,
        )
    }
    KioskGateSheet(gate)
}

/** A page row's tap: through the kiosk gate, but for Firmware and status while an update runs ([LockedFirmware]). */
private fun KioskGate.openPage(vm: PianoViewModel, page: SettingsPage, open: () -> Unit) {
    if (LockedFirmware.opensUnlocked(page, vm.firmware.value)) open() else run(open)
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
    val gate = rememberKioskGate()
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
        gate,
    )
    KioskGateSheet(gate)
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
private fun PianoHub(vm: PianoViewModel, scroll: ScrollState, selected: SettingsPage?, onPage: (SettingsPage) -> Unit, modifier: Modifier, gate: KioskGate) {
    val link by vm.link.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val piano by vm.piano.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val web by vm.web.collectAsStateWithLifecycle()
    val firmware by vm.firmware.collectAsStateWithLifecycle()
    val firmwarePiano by vm.firmwarePiano.collectAsStateWithLifecycle()
    val nextSchedule by vm.nextSchedule.collectAsStateWithLifecycle()
    val keyboard by vm.keyboard.collectAsStateWithLifecycle()
    val kind by vm.instrumentKind.collectAsStateWithLifecycle()
    val midi = kind == InstrumentKind.MidiPiano
    val instrument = InstrumentCopy.instrumentValue(kind, settings.midiOutName)
    val frame = LocalAppFrame.current
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(vm.canInstall()) }
    LifecycleResumeEffect(Unit) {
        canInstall = vm.canInstall()   // the person may come back from the Install unknown apps setting
        onPauseOrDispose { }
    }
    val summaries = remember(piano, settings, web, firmware, firmwarePiano, nextSchedule, keyboard, instrument) {
        GroupSummaries.from(
            piano,
            settings,
            web,
            firmware,
            (firmwarePiano as? FirmwarePiano.Connected)?.text,
            nextSchedule?.occurrence?.at,
            keyboard,
            instrument,
        )
    }

    // The hub scrolls beneath its header's glass (DESIGN.md › v1.9); beside a page, each has its own.
    GlassHeaderPane(scroll = scroll, modifier = modifier, header = { ScreenHeader("Piano", Modifier.readingWidth()) }) {
        val floating = LocalFloatingPadding.current
        Column(
            Modifier
                .fillMaxSize()
                .scrollEdges(scroll)
                .verticalScroll(scroll),
        ) {
            Spacer(Modifier.height(floating.calculateTopPadding()))
            Column(Modifier.readingWidth()) {
                ConnectionCard(
                    link,
                    playing,
                    onConnect = vm::connect,
                    onCancel = vm::cancel,
                    onDisconnect = { gate.run(vm::disconnect) },   // Connect stays free; Disconnect asks in kiosk mode
                    onConnectTo = vm::connectTo,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    name = instrument,
                    status = InstrumentCopy.linkWords(kind, link),
                    bluetooth = !midi || MidiNames.transportOf(settings.midiOutId) == MidiTransport.BLUETOOTH,
                )
                // The piano's status line is Steven Piano's: hidden while a MIDI piano plays (v1.11 — M29).
                if (!midi) PianoStatusLine(piano, link is LinkState.Connected)
                UpdateRow(
                    update,
                    canInstall,
                    onUpdate = { vm.update(context) },
                    onRestart = { vm.restart(context) },
                    onAllowInstalls = { runCatching { context.startActivity(vm.installPermissionSettings()) } },
                )
                for (group in HubGroups.shown(midi)) {
                    SectionEyebrow(group.title)
                    for (row in group.rows) HubRowView(row, summaries, settings, update, firmware, vm, selected, onPage, gate)
                }
                AboutRow(Modifier.padding(16.dp))
                Spacer(Modifier.height(floating.calculateBottomPadding()))
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
    firmware: FirmwareState,
    vm: PianoViewModel,
    selected: SettingsPage?,
    onPage: (SettingsPage) -> Unit,
    gate: KioskGate,
) {
    val locked = gate.locked
    when (row) {
        is HubRow.Page -> NavRow(
            row.page.title,
            summaries.of(row.page),
            onClick = { onPage(row.page) },
            selected = selected?.let { it == row.page },
            // Firmware and status during an update opens without the PIN: its chevron, not the padlock.
            locked = locked && !LockedFirmware.opensUnlocked(row.page, firmware),
        )
        HubRow.AutoConnect -> SwitchRow("Auto-connect on launch", settings.autoConnect, { on -> gate.run { vm.setAutoConnect(on) } }, locked = locked)
        HubRow.CheckForUpdates -> SwitchRow("Check for updates automatically", settings.checkForUpdates, { on -> gate.run { vm.setCheckForUpdates(on) } }, locked = locked)
        HubRow.CheckNow -> ActionRow(note = UpdateCopy.checkLine(update)) {
            ActionButton(
                "Check now",
                onClick = { gate.run(vm::checkNow) },
                enabled = update != UpdateState.Checking && !update.busy,
                description = if (locked) "Check now, ${KioskLockCopy.LOCKED.lowercase()}" else null,
            )
            if (locked) Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) { LockGlyph(description = null) }
        }
        HubRow.ShareDiagnostics -> ShareDiagnosticsRow()
    }
}

/**
 * A page: its header (the back glyph on phones, [onBack]; the title alone beside the hub), a glass
 * navigation bar of its own (DESIGN.md › v1.9), then its sections in a column that scrolls from
 * anywhere across the pane, at the reading width, beneath the header.
 */
@Composable
private fun SettingsPageView(page: SettingsPage, vm: PianoViewModel, onBack: (() -> Unit)?, modifier: Modifier, gate: KioskGate) {
    val scroll = vm.scrollOf(page)
    GlassHeaderPane(scroll = scroll, modifier = modifier, header = { PageHeader(page.title, onBack, Modifier.readingWidth()) }) {
        val floating = LocalFloatingPadding.current
        Column(
            Modifier
                .fillMaxSize()
                .scrollEdges(scroll)
                .verticalScroll(scroll),
        ) {
            Spacer(Modifier.height(floating.calculateTopPadding()))
            Column(Modifier.readingWidth()) {
                // Settings locked in kiosk: the page's controls wait behind the PIN (the page beside the hub,
                // or one left open when the five minutes ran out or the tablet rested). Firmware and status
                // keeps a firmware update in view, its Cancel behind the PIN (LockedFirmware).
                if (gate.locked) {
                    val firmware = if (page == SettingsPage.Firmware) firmwareReport(vm) else null
                    val update = firmware != null && LockedFirmware.shows(firmware.state)
                    if (update) LockedFirmwareUpdate(firmware, onCancel = { gate.run(vm::cancelFirmware) })
                    LockedPage(gate, rule = !update)
                } else when (page) {
                    SettingsPage.Instrument -> InstrumentPage(vm)
                    SettingsPage.Keyboard -> KeyboardPage(vm)
                    SettingsPage.Feel -> FeelPage(pianoReport(vm), vm)
                    SettingsPage.Lighting -> LightingPage(pianoReport(vm), vm)
                    SettingsPage.Pedal -> PedalPage(pianoReport(vm), vm)
                    SettingsPage.Firmware -> FirmwarePage(pianoReport(vm), vm, firmwareReport(vm), vm)
                    SettingsPage.Playback -> PlaybackPage(appSettings(vm), vm)
                    SettingsPage.Display -> DisplayPage(appSettings(vm), vm)
                    SettingsPage.Schedule -> SchedulePage()
                    SettingsPage.Remote -> RemotePage(appSettings(vm), webStatus(vm), vm)
                    SettingsPage.Kiosk -> KioskPage(appSettings(vm))
                }
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.height(floating.calculateBottomPadding()))
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

/** The piano's firmware and its update, as they change. */
@Composable
private fun firmwareReport(vm: PianoViewModel): FirmwareReport {
    val piano by vm.firmwarePiano.collectAsStateWithLifecycle()
    val state by vm.firmware.collectAsStateWithLifecycle()
    return FirmwareReport(piano, state)
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
