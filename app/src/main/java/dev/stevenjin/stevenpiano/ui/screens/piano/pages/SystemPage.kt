// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.diag.Attention
import dev.stevenjin.stevenpiano.diag.PianoDiag
import dev.stevenjin.stevenpiano.diag.RunningNow
import dev.stevenjin.stevenpiano.diag.SystemReading
import dev.stevenjin.stevenpiano.diag.SystemSample
import dev.stevenjin.stevenpiano.firmware.FirmwarePiano
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.ui.DisplayRule
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.KioskGate
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.LocalIdleState
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.SystemCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.DIAGNOSTICS_FAILED
import dev.stevenjin.stevenpiano.ui.components.Dial
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassAlertDialog
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.LiveDot
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.mirrored
import dev.stevenjin.stevenpiano.ui.components.rememberDiagnosticsSharer
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.GroupSummaries
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay

/**
 * What the System page and the hub's System row show of the tablet: its [reading], what [running] lists and whether the
 * player was [loading] a piece then (its row's word), read [at] (epoch ms).
 */
@Immutable
data class SystemNow(val reading: SystemReading, val running: List<RunningNow.Activity>, val at: Long, val loading: Boolean = false)

/** The day's samples ([dev.stevenjin.stevenpiano.diag.SystemHistory]), oldest first, as read [at] (epoch ms). */
@Immutable
data class SystemDay(val samples: List<SystemSample>, val at: Long)

/** The System page's column: wider than the reading width, so its cards can sit two to a row. */
fun Modifier.systemPageWidth(): Modifier = fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = SystemPageWidth)

/** How often the hub reads the tablet for its System row while it shows: as the app samples its day. */
const val SYSTEM_ROW_MS = 60_000L

/**
 * System (THIS TABLET, v1.18 — M50; DESIGN.md › v1.18 — M50): the web panel's System page (M47b) in the app's own
 * components and appearance. A line saying what needs attention first, then cards in one column, or two on a tablet on
 * its side (the app's 840 dp and up, [LocalAppFrame]) when the page has room for two cards of 360 dp: the
 * **Tablet** (battery and temperature dials, memory and storage meters, Wi-Fi, running for, the screen), the
 * **Controller** and the **Piano** (the chip's temperature and memory, the seven power boards as seven octaves, the facts;
 * "Needs newer firmware" for what the piano doesn't report, "Not connected" without it, a row at the foot to Firmware and
 * status), **Running now** (the app's twelve rows, each with its dot, detail, bar and word), **Today** (the day's battery
 * and temperature) and **Tools** (Read status, Find missing covers, Reconnect the piano after a word, All keys off, Share
 * diagnostics; the ones that change the piano through [gate]). The tablet is read every 5 s and the piano's facts asked
 * every 15 s, only while the page shows ([ReadWhileShown]); [onOpenPage] opens another page of the tab.
 */
@Composable
fun SystemPage(vm: PianoViewModel, gate: KioskGate, onOpenPage: (SettingsPage) -> Unit) {
    ReadWhileShown(SYSTEM_MS, vm::readSystem)
    ReadWhileShown(FACTS_MS, vm::refreshPianoFacts)
    ReadWhileShown(DAY_EVERY_MS, vm::readSystemDay)

    val system by vm.system.collectAsStateWithLifecycle()
    val day by vm.systemDay.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val piano by vm.piano.collectAsStateWithLifecycle()
    val kind by vm.instrumentKind.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val attention = rememberAttention(vm, system)
    val parts = remember(attention) { attention.map { it.part }.toSet() }
    val mode = SystemCopy.modeOf(kind, link, piano)
    val diag = remember(kind, link, piano) { PianoDiag.of(kind, link, piano) }
    val instrument = InstrumentCopy.instrumentValue(kind, settings.midiOutName)

    StatusLine(system, attention)
    val expanded = LocalAppFrame.current.widthClass == WindowWidthSizeClass.Expanded
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Beside the hub on the tablet the page is about 840 dp itself; on a large tablet held upright it is far narrower.
        val two = expanded && maxWidth >= TwoColumns
        val cards: List<@Composable () -> Unit> = listOf(
            { TabletCard(system?.reading, parts) },
            { ControllerCard(mode, diag, instrument, parts) },
            { PianoCard(vm, mode, diag, link, instrument, parts, onOpenPage) },
            { RunningCard(vm, system, link) },
            { TodayCard(day) },
            { ToolsCard(vm, gate) },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            if (two) {
                for (pair in cards.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(CardGap), verticalAlignment = Alignment.Top) {
                        for (card in pair) Box(Modifier.weight(1f)) { card() }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                for (card in cards) card()
            }
        }
    }
}

// ---- What the page reads, and when -----------------------------------------------------------------------------

/**
 * [read] now and every [everyMs] after while this shows: the app resumed, and the resting screen not over it (DESIGN.md
 * › v1.18 — M50: the figures are read only while someone can see them, never otherwise). [read] runs on the main thread.
 */
@Composable
internal fun ReadWhileShown(everyMs: Long, read: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resting = restingOver()
    val latest by rememberUpdatedState(read)
    LaunchedEffect(lifecycle, resting, everyMs) {
        if (resting) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                latest()
                delay(everyMs)
            }
        }
    }
}

/** Whether the resting screen is over the app now (DisplayOverlay's own rule: idle, and a piece loaded or kiosk mode on). */
@Composable
private fun restingOver(): Boolean {
    val idle = LocalIdleState.current ?: return false
    val graph = LocalContext.current.graph
    val player = graph.player.state.collectAsStateWithLifecycle()
    val settings = graph.settings.collectAsStateWithLifecycle()
    val loaded by remember(player) { derivedStateOf { player.value.piece != null } }
    val kiosk by remember(settings) { derivedStateOf { settings.value.kioskEnabled } }
    return idle.idle && DisplayRule.shows(loaded, kiosk)
}

/**
 * What needs attention now ([Attention]): the last reading and what runs ([system]), with the piano, the instrument and the
 * internet link as they are. The page colours its parts by it; the hub's row says the first.
 */
@Composable
internal fun rememberAttention(vm: PianoViewModel, system: SystemNow?): List<Attention.Item> {
    val link by vm.link.collectAsStateWithLifecycle()
    val piano by vm.piano.collectAsStateWithLifecycle()
    val kind by vm.instrumentKind.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cloud by vm.cloud.collectAsStateWithLifecycle()
    return remember(system, link, piano, kind, settings, cloud) {
        Attention.of(
            Attention.Inputs(
                reading = system?.reading,
                running = system?.running.orEmpty(),
                piano = PianoDiag.of(kind, link, piano),
                cloudOn = vm.cloudLink(settings, cloud) != null,
                cloud = cloud,
            ),
        )
    }
}

// ---- The head --------------------------------------------------------------------------------------------------

/** "Everything is running normally", or the first thing that needs attention with how many more; read out as it changes. */
@Composable
private fun StatusLine(system: SystemNow?, attention: List<Attention.Item>) {
    val first = attention.firstOrNull()
    val dot = when {
        system == null -> SystemCopy.Dot.Hollow
        first == null -> SystemCopy.Dot.On
        else -> SystemCopy.Dot.Attention
    }
    val text = when {
        system == null -> SystemCopy.READING_SYSTEM
        first == null -> SystemCopy.ALL_WELL
        else -> first.text
    }
    val more = (attention.size - 1).coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = if (more > 0) "$text, and $more more" else text
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(dot)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (more > 0) {
            Text("+$more", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- The cards -------------------------------------------------------------------------------------------------

@Composable
private fun TabletCard(reading: SystemReading?, parts: Set<Attention.Part>) {
    val storageFull = Attention.Part.Storage in parts
    SystemCard(SystemCopy.TABLET, SystemCopy.tabletLine(reading)) {
        Dials {
            Dial("Battery", SystemCopy.battery(reading, Attention.Part.Battery in parts), "0", "100")
            Dial("Temperature", SystemCopy.temperature(reading, Attention.Part.Heat in parts), "0", "60")
        }
        LevelRow("Memory", SystemCopy.memory(reading), Attention.Part.Memory in parts)
        LevelRow("Storage", SystemCopy.storage(reading, storageFull), storageFull)
        FactRow(SystemCopy.Fact(SystemCopy.networkLabel(reading), SystemCopy.network(reading)))
        FactRow(SystemCopy.Fact("Running for", reading?.uptimeMs?.let { SystemCopy.duration(it / MS_PER_S) }))
        FactRow(SystemCopy.Fact("Screen", SystemCopy.screen(reading)))
    }
}

@Composable
private fun ControllerCard(mode: SystemCopy.Mode, diag: PianoDiag?, instrument: String, parts: Set<Attention.Part>) {
    val (memory, size) = SystemCopy.heap(mode, diag, Attention.Part.Heap in parts)
    SystemCard(SystemCopy.CONTROLLER, SystemCopy.controllerLine(mode, diag, instrument)) {
        Dials {
            Dial("Chip temperature", SystemCopy.chip(mode, diag, Attention.Part.Chip in parts), "0", "80")
            Dial("Memory", memory, "0", size)
        }
        for (fact in SystemCopy.controllerFacts(mode, diag)) FactRow(fact)
    }
}

@Composable
private fun PianoCard(
    vm: PianoViewModel,
    mode: SystemCopy.Mode,
    diag: PianoDiag?,
    link: LinkState,
    instrument: String,
    parts: Set<Attention.Part>,
    onOpenPage: (SettingsPage) -> Unit,
) {
    val firmware by vm.firmware.collectAsStateWithLifecycle()
    val firmwarePiano by vm.firmwarePiano.collectAsStateWithLifecycle()
    val piano by vm.piano.collectAsStateWithLifecycle()
    val boards = diag?.boards?.takeIf { mode == SystemCopy.Mode.Ready }
    SystemCard(SystemCopy.PIANO, SystemCopy.pianoLine(mode, boards, instrument)) {
        Boards(boards)
        for (fact in SystemCopy.pianoFacts(mode, diag, link, Attention.Part.Boards in parts)) FactRow(fact)
        FootRow(
            SettingsPage.Firmware.title,
            GroupSummaries.firmware(piano, firmware, (firmwarePiano as? FirmwarePiano.Connected)?.text),
            onClick = { onOpenPage(SettingsPage.Firmware) },
        )
    }
}

@Composable
private fun RunningCard(vm: PianoViewModel, system: SystemNow?, link: LinkState) {
    val playing by vm.playing.collectAsStateWithLifecycle()
    val toPiano = playing && link is LinkState.Connected
    SystemCard(SystemCopy.RUNNING, SystemCopy.appLine(system?.reading?.app)) {
        val rows = system?.running
        if (rows == null) {
            CardNote(SystemCopy.READING_SYSTEM)
        } else {
            for (row in rows) RunningRow(row, SystemCopy.dotOf(row, toPiano), SystemCopy.stateWord(row, system.loading))
        }
    }
}

@Composable
private fun TodayCard(day: SystemDay?) {
    val samples = day?.samples.orEmpty()
    val now = day?.at ?: System.currentTimeMillis()   // the first read is a frame away
    val shown = remember(day) { samples.filter { now - it.at <= DAY_SPAN_MS } }
    SystemCard(SystemCopy.TODAY, if (day == null) SystemCopy.LAST_DAY else SystemCopy.dayLine(samples, now)) {
        Legend(piano = shown.any { it.pianoTenthsC != null })
        DayChart(shown, now)
        // The day in one sentence, for everyone and for TalkBack (the chart itself says nothing).
        CardNote(SystemCopy.daySentence(shown))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsCard(vm: PianoViewModel, gate: KioskGate) {
    val piano by vm.piano.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val kind by vm.instrumentKind.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    val statusReading by vm.statusReading.collectAsStateWithLifecycle()
    val sharer = rememberDiagnosticsSharer()
    var said by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val ready = PianoDiag.of(kind, link, piano) != null
    SystemCard(SystemCopy.TOOLS, "") {
        // Search finds Find missing covers and Reconnect the piano here: both land on the tools, which light together.
        Anchored(PageRows.FIND_COVERS.anchor) {
            Anchored(PageRows.RECONNECT.anchor) {
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        if (statusReading) SystemCopy.READING_STATUS else SystemCopy.READ_STATUS,
                        onClick = { vm.runPianoAction(PianoAction.Status, 0) },
                        enabled = ready && !statusReading,
                    )
                    ActionButton(PageRows.FIND_COVERS.label, onClick = {
                        vm.findMissingCovers()
                        said = SystemCopy.COVERS_ASKED
                    })
                    // The two that change the piano wait for the kiosk PIN as their rows elsewhere do (the page itself does too).
                    ActionButton(PageRows.RECONNECT.label, onClick = { confirming = true })
                    ActionButton(InstrumentCopy.ALL_KEYS_OFF, onClick = {
                        gate.run {
                            vm.allKeysOff()
                            said = SystemCopy.KEYS_OFF
                        }
                    })
                    ActionButton(SystemCopy.SHARE_DIAGNOSTICS, onClick = { sharer.share() }, enabled = !sharer.busy)
                }
            }
        }
        val line = if (sharer.failed) DIAGNOSTICS_FAILED else said
        if (line != null) {
            Eyebrow(
                line,
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                uppercase = false,
            )
        }
        if (statusReading) ProgressHairline(null, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        statusText?.let { report ->
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    report.ifEmpty { SystemCopy.NO_ANSWER },
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    if (confirming) {
        GlassAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(SystemCopy.RECONNECT_TITLE) },
            text = { Text(SystemCopy.RECONNECT_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    gate.run { said = if (vm.reconnectPiano()) SystemCopy.RECONNECTING else SystemCopy.RECONNECT_BUSY }
                }) { Text(SystemCopy.RECONNECT_ACTION) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

// ---- The parts -------------------------------------------------------------------------------------------------

/** A card of the page: the elevated surface with the cards' 12 dp corners, its name (a heading) with its line, then [content]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemCard(title: String, line: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(bottom = 8.dp)) {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                horizontalArrangement = Apart,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Two dials side by side, or one over the other when the text is large. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dials(content: @Composable () -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/**
 * A row of a card ([SystemCopy.Fact]): its name, and its value at the end ("—" when not known, the small "Needs newer
 * firmware" tag, the amber with its words when it needs attention, the live dot before the Bluetooth row's); a value too
 * long to sit beside the name goes under it. TalkBack reads "name, value".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactRow(fact: SystemCopy.Fact) {
    val spoken = when {
        fact.needs -> SystemCopy.NEEDS_FIRMWARE.lowercase()
        fact.value == null -> "not known"
        else -> fact.value
    }
    HairlineDivider(startInset = 16.dp)
    FlowRow(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clearAndSetSemantics { contentDescription = "${fact.label}, $spoken" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Apart,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(fact.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        when {
            fact.needs -> NeedsTag()
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (fact.live != null) {
                    LiveDot(live = fact.live, breathing = false)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    fact.value ?: NONE,
                    style = MaterialTheme.typography.bodyLarge.merge(Tabular).copy(fontWeight = if (fact.attention) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (fact.attention) LocalAttention.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** What waits for newer piano firmware: the words, small and engraved, in a hairline frame. */
@Composable
private fun NeedsTag() {
    Box(
        Modifier
            .border(Hairline, LocalHairline.current, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Eyebrow(SystemCopy.NEEDS_FIRMWARE)
    }
}

/** Memory or storage: its name and what is in use ("2.4 of 4 GB in use"), and a meter of it; amber when it needs attention. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelRow(label: String, level: SystemCopy.Level?, attention: Boolean) {
    val amber = LocalAttention.current
    HairlineDivider(startInset = 16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = level?.text ?: "not known"
                progressBarRangeInfo = ProgressBarRangeInfo(level?.share?.coerceIn(0f, 1f) ?: 0f, 0f..1f)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Apart, itemVerticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                level?.text ?: NONE,
                style = MaterialTheme.typography.bodyLarge.merge(Tabular).copy(fontWeight = if (attention) FontWeight.SemiBold else FontWeight.Normal),
                color = if (attention) amber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Meter(level?.share, if (attention) amber else MaterialTheme.colorScheme.onSurface, METER, Modifier.padding(top = 10.dp))
    }
}

/** A rounded meter of [share] (0–1; null: empty) in [color] over a faint track, [thickness] tall; it eases to each new value. */
@Composable
private fun Meter(share: Float?, color: Color, thickness: Dp, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val reduced = rememberReducedMotion()
    val eased by animateFloatAsState(share?.coerceIn(0f, 1f) ?: 0f, Motion.timed(Motion.StandardMs, reduced), label = "meter")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(thickness),
    ) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (share != null && eased > 0f) drawRoundRect(color, size = Size(size.width * eased, size.height), cornerRadius = radius)
    }
}

/**
 * The seven power boards as seven octaves of keys, C1 to C7 (the panel's drawing): an answering board's keys in the
 * content colour, a missing one hollow and amber with "Missing" under it, an unknown one faint. TalkBack reads them in a line.
 */
@Composable
private fun Boards(boards: List<String>?) {
    val content = MaterialTheme.colorScheme.onSurface
    val card = MaterialTheme.colorScheme.surfaceVariant
    val hairline = LocalHairline.current
    val amber = LocalAttention.current
    val tertiary = LocalTertiary.current
    val spoken = boards?.mapIndexed { i, board -> "C${i + 1} ${if (board == PianoDiag.OK) "OK" else if (board == PianoDiag.MISSING) "missing" else "not known"}" }
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Power boards: " + (spoken?.joinToString(", ") ?: "not known") }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(OCTAVES_WIDTH / OCTAVES_HEIGHT),
        ) {
            val u = size.width / OCTAVES_WIDTH
            val line = Stroke(width = 1.dp.toPx())
            for (o in 0 until BOARDS) {
                val state = boards?.getOrNull(o)
                val x = o * OCTAVE_STEP
                val missing = state == PianoDiag.MISSING
                val known = state == PianoDiag.OK
                drawRoundRect(if (missing) amber else hairline, Offset((x + 2) * u, 2 * u), Size(56 * u, 44 * u), CornerRadius(6 * u), style = line)
                for (k in 0 until NATURALS) {
                    val at = Offset((x + 4 + k * KEY_STEP) * u, 5 * u)
                    val key = Size(6.3f * u, 38 * u)
                    when {
                        missing -> drawRoundRect(amber, at, key, CornerRadius(1.5f * u), style = line)
                        known -> drawRoundRect(content.copy(alpha = KEY_ALPHA), at, key, CornerRadius(1.5f * u))
                        else -> drawRoundRect(content.copy(alpha = FAINT_ALPHA), at, key, CornerRadius(1.5f * u))
                    }
                }
                for (k in SHARPS) {
                    val at = Offset((x + 4 + k * KEY_STEP + 4.6f) * u, 5 * u)
                    val key = Size(4.4f * u, 23 * u)
                    drawRoundRect(card, at, key, CornerRadius(1.2f * u))
                    if (missing) drawRoundRect(amber, at, key, CornerRadius(1.2f * u), style = line)
                }
                val railAt = Offset((x + 2) * u, 54 * u)
                val rail = Size(56 * u, 6 * u)
                when {
                    missing -> drawRoundRect(amber, railAt, rail, CornerRadius(3 * u), style = Stroke(width = 1.5.dp.toPx()))
                    known -> drawRoundRect(content, railAt, rail, CornerRadius(3 * u))
                    else -> drawRoundRect(content.copy(alpha = FAINT_ALPHA), railAt, rail, CornerRadius(3 * u))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for (o in 0 until BOARDS) {
                val state = boards?.getOrNull(o)
                val missing = state == PianoDiag.MISSING
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state == PianoDiag.OK) "C${o + 1} · OK" else "C${o + 1}",
                        style = SmallFigures,
                        color = if (missing) amber else tertiary,
                        textAlign = TextAlign.Center,
                    )
                    if (missing) Text("Missing", style = SmallFigures.copy(fontWeight = FontWeight.SemiBold), color = amber, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * A row of Running now: its dot, title and state's word on a line, the detail under them, and a thin bar when it has a
 * measure; an idle or off row's title in the secondary colour. TalkBack reads it in one go.
 */
@Composable
private fun RunningRow(row: RunningNow.Activity, dot: SystemCopy.Dot, word: String) {
    val quiet = row.state == RunningNow.IDLE || row.state == RunningNow.OFF
    val indent = with(LocalDensity.current) { DotSize.toDp() } + DOT_GAP
    val progress = row.progress?.toFloat()
    HairlineDivider(startInset = 16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clearAndSetSemantics {
                contentDescription = "${row.title}, $word. ${row.detail}" + (progress?.let { ", ${(it * 100).toInt()} percent" } ?: "")
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(dot)
            Spacer(Modifier.width(DOT_GAP))
            Text(
                row.title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Eyebrow(word, uppercase = false, maxLines = 1)
        }
        if (row.detail.isNotEmpty()) {
            Text(
                row.detail,
                Modifier.padding(start = indent, top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (progress != null) Meter(progress, MaterialTheme.colorScheme.onSurface, BAR, Modifier.padding(start = indent, top = 6.dp))
    }
}

/** A row's dot: the live red (the app's [LiveDot], the one reader of the live colour), amber, filled, or a hollow ring. */
@Composable
private fun StateDot(dot: SystemCopy.Dot) {
    if (dot == SystemCopy.Dot.Live) {
        LiveDot(live = true, breathing = false)
        return
    }
    val color = when (dot) {
        SystemCopy.Dot.Attention -> LocalAttention.current
        SystemCopy.Dot.On -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Canvas(Modifier.size(with(LocalDensity.current) { DotSize.toDp() })) {
        if (dot == SystemCopy.Dot.Hollow) {
            val stroke = 1.dp.toPx()
            drawCircle(color, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        } else {
            drawCircle(color)
        }
    }
}

/** A row at a card's foot that opens another page: its name, the page's own line, and the chevron. */
@Composable
private fun FootRow(label: String, value: String, onClick: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    HairlineDivider(startInset = 16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(value, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .mirrored(rtl),
            tint = LocalTertiary.current,
        )
    }
}

/** A line of a card in the secondary colour: what is being read, or the day in a sentence. */
@Composable
private fun CardNote(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- Today -----------------------------------------------------------------------------------------------------

/** What the day's lines are: the battery solid, the tablet's temperature dashed, the piano's dotted (only once it has one). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(piano: Boolean) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Key(SystemCopy.BATTERY, Line.Solid)
        Key(SystemCopy.TABLET_HEAT, Line.Dashed)
        if (piano) Key(SystemCopy.PIANO_HEAT, Line.Dotted)
    }
}

private enum class Line { Solid, Dashed, Dotted }

@Composable
private fun Key(text: String, line: Line) {
    val content = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp, 8.dp)) {
            val y = size.height / 2
            when (line) {
                Line.Solid -> drawLine(content, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                Line.Dashed -> drawLine(secondary, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = dashes())
                Line.Dotted -> drawLine(secondary, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dots())
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = secondary)
    }
}

/**
 * The day on a canvas (the panel's chart): from the first sample, or a day ago, to now; the battery 0–100 % on the left,
 * a line over a soft area, the temperatures 10–50 °C on the right (the tablet's dashed, the piano's dotted); a line breaks
 * where a value is missing or the samples stop for three minutes; three rules and five times under it. Drawn once per
 * reading, nothing moves.
 */
@Composable
private fun DayChart(samples: List<SystemSample>, now: Long) {
    val measurer = rememberTextMeasurer()
    val content = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val hairline = LocalHairline.current
    val labels = SmallFigures.copy(color = LocalTertiary.current)
    val model = remember(samples, now) { DayModel.of(samples, now) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val left = 28.dp.toPx()
        val right = 32.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 22.dp.toPx()
        val plotW = (size.width - left - right).coerceAtLeast(1f)
        val plotH = size.height - top - bottom
        val span = (model.end - model.start).coerceAtLeast(1L).toFloat()
        fun x(t: Long): Float = left + plotW * (t - model.start) / span
        fun yBattery(v: Double): Float = top + plotH * (1f - (v.coerceIn(0.0, 100.0) / 100.0).toFloat())
        fun yHeat(c: Double): Float = top + plotH * (1f - ((c.coerceIn(HEAT_LOW, HEAT_HIGH) - HEAT_LOW) / (HEAT_HIGH - HEAT_LOW)).toFloat())

        for (v in listOf(0.0, 50.0, 100.0)) {
            val y = yBattery(v)
            drawLine(hairline, Offset(left, y), Offset(size.width - right, y), strokeWidth = 1.dp.toPx())
            label(measurer, "${v.toInt()}", labels, Offset(left - 6.dp.toPx(), y), Anchor.End)
            label(measurer, "${(HEAT_LOW + (HEAT_HIGH - HEAT_LOW) * v / 100.0).toInt()}°", labels, Offset(size.width - right + 6.dp.toPx(), y), Anchor.Start)
        }
        for (i in 0..TIME_STEPS) {
            val t = model.start + (model.end - model.start) * i / TIME_STEPS
            val text = if (i == TIME_STEPS) "Now" else SystemCopy.clock(t)
            val anchor = when (i) {
                0 -> Anchor.Start
                TIME_STEPS -> Anchor.End
                else -> Anchor.Middle
            }
            label(measurer, text, labels, Offset(x(t), size.height - bottom / 2), anchor)
        }
        val base = yBattery(0.0)
        for (run in model.battery) {
            if (run.size < 2) continue
            val area = Path().apply {
                moveTo(x(run.first().first), base)
                for ((t, v) in run) lineTo(x(t), yBattery(v))
                lineTo(x(run.last().first), base)
                close()
            }
            drawPath(area, content.copy(alpha = AREA_ALPHA))
        }
        for (run in model.tablet) drawRun(run, ::x, ::yHeat, secondary, Stroke(1.5.dp.toPx(), join = StrokeJoin.Round, pathEffect = dashes()))
        for (run in model.piano) drawRun(run, ::x, ::yHeat, secondary, Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dots()))
        for (run in model.battery) drawRun(run, ::x, ::yBattery, content, Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The day's chart, worked out once per reading: its span and its three series' runs. */
private class DayModel(
    val start: Long,
    val end: Long,
    val battery: List<List<Pair<Long, Double>>>,
    val tablet: List<List<Pair<Long, Double>>>,
    val piano: List<List<Pair<Long, Double>>>,
) {
    companion object {
        fun of(samples: List<SystemSample>, now: Long): DayModel {
            val start = samples.firstOrNull()?.at?.coerceAtLeast(now - DAY_SPAN_MS) ?: (now - DAY_SPAN_MS)
            val end = maxOf(now, start + SAMPLE_MS)
            return DayModel(
                start,
                end,
                SystemCopy.runs(samples, { it.batteryPct?.toDouble() }),
                SystemCopy.runs(samples, { it.batteryTenthsC?.div(TENTHS) }),
                SystemCopy.runs(samples, { it.pianoTenthsC?.div(TENTHS) }),
            )
        }
    }
}

private fun DrawScope.drawRun(run: List<Pair<Long, Double>>, x: (Long) -> Float, y: (Double) -> Float, color: Color, stroke: Stroke) {
    if (run.size < 2) return
    val path = Path().apply {
        moveTo(x(run.first().first), y(run.first().second))
        for (i in 1 until run.size) lineTo(x(run[i].first), y(run[i].second))
    }
    drawPath(path, color, style = stroke)
}

private enum class Anchor { Start, Middle, End }

/** A chart's label, its middle at [at]'s height, its start, middle or end at [at]'s x. */
private fun DrawScope.label(measurer: TextMeasurer, text: String, style: TextStyle, at: Offset, anchor: Anchor) {
    val laid = measurer.measure(text, style)
    val x = when (anchor) {
        Anchor.Start -> at.x
        Anchor.Middle -> at.x - laid.size.width / 2f
        Anchor.End -> at.x - laid.size.width
    }
    drawText(laid, topLeft = Offset(x, at.y - laid.size.height / 2f))
}

private fun DrawScope.dashes(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

private fun DrawScope.dots(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(0.5.dp.toPx(), 3.5.dp.toPx()))

// ---- Measures --------------------------------------------------------------------------------------------------

/** Items at the two ends of their line (one alone sits at the start), at least 16 dp apart when they share it. */
private object Apart : Arrangement.Horizontal {
    override val spacing: Dp = 16.dp

    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        with(Arrangement.SpaceBetween) { arrange(totalSize, sizes, layoutDirection, outPositions) }
    }
}

/** The small figures under the boards and on the chart: the eyebrow's size without its tracking. */
private val SmallFigures: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)

private val SystemPageWidth = 1120.dp
/** Room for two cards of 360 dp, the gap between them and the page's margins. */
private val TwoColumns = 760.dp
private val CardGap = 12.dp
private val METER = 6.dp
private val BAR = 3.dp
private val CHART_HEIGHT = 198.dp
private val DotSize = 8.sp
private val DOT_GAP = 12.dp

private const val SYSTEM_MS = 5_000L
private const val FACTS_MS = 15_000L
private const val DAY_EVERY_MS = 60_000L
private const val SAMPLE_MS = 60_000L
private const val DAY_SPAN_MS = 24 * 60 * 60 * 1000L
private const val MS_PER_S = 1_000L
private const val TENTHS = 10.0
private const val HEAT_LOW = 10.0
private const val HEAT_HIGH = 50.0
private const val TIME_STEPS = 4
private const val TRACK_ALPHA = 0.12f
private const val AREA_ALPHA = 0.09f
private const val KEY_ALPHA = 0.88f
private const val FAINT_ALPHA = 0.16f
private const val BOARDS = 7
private const val NATURALS = 7
private val SHARPS = listOf(0, 1, 3, 4, 5)
private const val OCTAVE_STEP = 60f
private const val KEY_STEP = 7.5f
private const val OCTAVES_WIDTH = 420f
private const val OCTAVES_HEIGHT = 62f
private const val NONE = "—"
