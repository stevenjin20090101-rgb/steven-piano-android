// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.power.FoundPlug
import dev.stevenjin.stevenpiano.power.PairFlow
import dev.stevenjin.stevenpiano.power.PairState
import dev.stevenjin.stevenpiano.power.PairStep
import dev.stevenjin.stevenpiano.power.PlugInfo
import dev.stevenjin.stevenpiano.power.PlugRead
import dev.stevenjin.stevenpiano.power.PlugSettings
import dev.stevenjin.stevenpiano.power.Provision
import dev.stevenjin.stevenpiano.power.RailKeeper
import dev.stevenjin.stevenpiano.power.SetUpTask
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.GlassAlertDialog
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.ReadingRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Piano › Power plug (v1.26 — M60; DESIGN.md › v1.26 — M60), behind the kiosk PIN as every Piano page. With no plug: what
 * it is for and **Pair the power plug**, which opens the Pair screen's steps (the setup order of SMART_PLUG.md, followed on
 * the day). Paired: the plug as it stands (the 24 V on or off with its timer, the supply's draw, its setup and firmware),
 * the switch, **Admit a replacement tablet** and **Forget the plug**. The plug is read every 2 s while the page shows.
 */
@Composable
fun PowerPlugPage() {
    val graph = LocalContext.current.graph
    val settings by graph.plugSettings.collectAsStateWithLifecycle()
    val pair by graph.pairFlow.state.collectAsStateWithLifecycle()
    var opened by rememberSaveable { mutableStateOf(false) }
    val current = settings
    if (current == null) {
        NoteLine(PowerCopy.READING)
        return
    }
    val pairing = pair.step != PairStep.Done && (pair.step != PairStep.ShellyApp || opened)
    when {
        pairing -> PairSteps(pair, graph.pairFlow, onStartOver = {
            graph.pairFlow.restart()
            opened = current.pairing == null
        })
        current.pairing != null -> PairedPlug(current)
        else -> Unpaired(onPair = {
            graph.pairFlow.restart()
            opened = true
        })
    }
}

/** No plug yet: what it is for, and Pair the power plug. */
@Composable
private fun Unpaired(onPair: () -> Unit) {
    SectionRule()
    NoteLine(PowerCopy.EXPLANATION)
    HairlineDivider(startInset = 16.dp)
    Anchored(PageRows.PAIR_PLUG.anchor) {
        ActionRow(note = PowerCopy.PAIR_NOTE) { ActionButton(PowerCopy.PAIR, onClick = onPair) }
    }
}

// ---- The Pair screen -----------------------------------------------------------------------------------------------

/** The steps, numbered, the one under way open with its words and its action; those done ticked. */
@Composable
private fun PairSteps(pair: PairState, flow: PairFlow, onStartOver: () -> Unit) {
    SectionEyebrow(PowerCopy.PAIR)
    val at = STEPS.indexOf(pair.step).coerceAtLeast(0)
    STEPS.forEachIndexed { i, step ->
        StepHeader(i + 1, PowerCopy.stepTitle(step), done = i < at, current = i == at)
        if (i == at) {
            NoteLine(PowerCopy.stepNote(step))
            when (step) {
                PairStep.ShellyApp -> ActionRow { ActionButton(PowerCopy.SHELLY_DONE, onClick = flow::shellyAppDone) }
                PairStep.Find -> FindStep(pair, flow)
                PairStep.SetUp -> SetUpStep(pair, flow)
                PairStep.Complete -> ReadingRow(PowerCopy.SETUP, pair.info?.provision?.let(PowerCopy::provision) ?: PowerCopy.READING)
                PairStep.Network -> ActionRow { ActionButton(PowerCopy.CLOSE_NETWORK, onClick = flow::closeNetwork, enabled = !pair.working) }
                PairStep.Verify -> VerifyStep(pair, flow)
                PairStep.Done -> Unit
            }
            if (pair.working) ProgressHairline(null, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            pair.problem?.let { Problem(it) }
        }
        HairlineDivider(startInset = 16.dp)
    }
    ActionRow { ActionButton(PowerCopy.START_OVER, onClick = onStartOver, enabled = !pair.working) }
}

/** A step's number in a ring (filled while it is the one under way, a tick once done) and its title. */
@Composable
private fun StepHeader(number: Int, title: String, done: Boolean, current: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = "Step $number, $title" + when {
                    done -> ", done"
                    current -> ", now"
                    else -> ""
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .border(1.dp, if (current || done) ink else LocalTertiary.current, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) "✓" else "$number",
                style = MaterialTheme.typography.labelLarge,
                color = if (current || done) ink else quiet,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
            color = if (current || done) ink else quiet,
        )
    }
}

/** Step 2: the plugs found (a tap chooses one), then what the chosen one said, and the way on from there. */
@Composable
private fun FindStep(pair: PairState, flow: PairFlow) {
    for (plug in pair.found) FoundRow(plug, chosen = plug.address == pair.plug?.address, enabled = !pair.working) { flow.choose(plug) }
    ActionRow(note = if (pair.searching) PowerCopy.SEARCHING else null) {
        ActionButton(PowerCopy.SEARCH, onClick = flow::search, enabled = !pair.searching && !pair.working)
    }
    val info = pair.info ?: return
    PlugFacts(info)
    when {
        pair.fallback -> Fallback(pair, flow)
        !info.pairs || info.provision == Provision.Locked -> Unit   // the problem line says what to do
        info.provision == Provision.Pending -> {
            NoteLine(PowerCopy.NEW_PLUG)
            ActionRow { ActionButton(PowerCopy.OVER_BLUETOOTH, onClick = flow::bondWhilePending, enabled = !pair.working) }
        }
        info.provision == Provision.Confirmed -> ActionRow { ActionButton(PowerCopy.SET_UP, onClick = flow::setUp, enabled = !pair.working) }
        else -> ActionRow { ActionButton(PowerCopy.SEARCH, onClick = flow::refresh, enabled = !pair.working) }
    }
}

/** A plug the scan found: its name and signal; the chosen one marked. */
@Composable
private fun FoundRow(plug: FoundPlug, chosen: Boolean, enabled: Boolean, onChoose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onChoose)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            plug.name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            listOfNotNull(plug.rssi?.let { "$it dBm" }, if (chosen) "Chosen" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HairlineDivider(startInset = 16.dp)
}

/** What the plug said of itself: its model, firmware and setup state. */
@Composable
private fun PlugFacts(info: PlugInfo) {
    ReadingRow("Model", info.model ?: "—")
    ReadingRow(PowerCopy.FIRMWARE, info.ver ?: "—")
    ReadingRow(PowerCopy.SETUP, PowerCopy.provision(info.provision))
}

/** The plug finished its setup without this tablet: a factory reset, or a pairing window another tablet opened. */
@Composable
private fun Fallback(pair: PairState, flow: PairFlow) {
    NoteLine(PowerCopy.FALLBACK)
    ActionRow { ActionButton(PowerCopy.FIND_AGAIN, onClick = flow::search, enabled = !pair.working && !pair.searching) }
    ActionRow(note = PowerCopy.JOIN_NOTE) { ActionButton(PowerCopy.JOIN, onClick = flow::join, enabled = !pair.working) }
}

/** Step 3: each task ticked as it lands; set up from here (the fallback), the hotspot's name and password after the bond. */
@Composable
private fun SetUpStep(pair: PairState, flow: PairFlow) {
    val tasks = if (pair.overBluetooth && SetUpTask.Settings !in pair.tasks) {
        listOf(SetUpTask.Window, SetUpTask.Bond, SetUpTask.Listed)
    } else {
        SetUpTask.entries
    }
    for (task in tasks) Tick(PowerCopy.task(task), task in pair.tasks)
    if (pair.overBluetooth && SetUpTask.Listed in pair.tasks && SetUpTask.Settings !in pair.tasks) {
        HotspotForm(enabled = !pair.working, onSend = flow::provision)
    } else if (pair.problem != null && !pair.working) {
        ActionRow { ActionButton("Try again", onClick = if (pair.overBluetooth) flow::bondWhilePending else flow::setUp) }
    }
}

/** One task: a tick once done. */
@Composable
private fun Tick(label: String, done: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) { contentDescription = label + if (done) ", done" else ", waiting" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (done) "✓" else "·",
            Modifier.width(24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The fallback's network for the plug: the phone hotspot's name and password, sent over Bluetooth. */
@Composable
private fun HotspotForm(enabled: Boolean, onSend: (String, String) -> Unit) {
    var ssid by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(PowerCopy.HOTSPOT, ssid, { ssid = it }, enabled)
        Field(PowerCopy.PASSWORD, pass, { pass = it }, enabled, password = true)
    }
    ActionRow { ActionButton(PowerCopy.SEND_NETWORK, onClick = { onSend(ssid, pass) }, enabled = enabled && ssid.isNotBlank()) }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
            unfocusedBorderColor = LocalTertiary.current,
            disabledBorderColor = LocalHairline.current,
            focusedLabelColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Step 6: the 24 V on for 5 s, the boards answering as it goes, then Finish (or without the check). */
@Composable
private fun VerifyStep(pair: PairState, flow: PairFlow) {
    pair.verify?.let { verify ->
        ReadingRow(
            "Power boards",
            PowerCopy.answering(verify.answered, verify.total),
            note = when {
                verify.finished && verify.passed -> PowerCopy.VERIFY_PASSED
                verify.on -> "24 V on"
                else -> "24 V off"
            },
        )
    }
    val passed = pair.verify?.passed == true
    ActionRow {
        ActionButton(if (pair.verify == null) PowerCopy.VERIFY else PowerCopy.VERIFY_AGAIN, onClick = flow::verify, enabled = !pair.working)
        ActionButton(PowerCopy.FINISH, onClick = flow::finish, enabled = passed && !pair.working)
    }
    if (!passed) ActionRow { ActionButton(PowerCopy.SKIP_CHECK, onClick = flow::finish, enabled = !pair.working) }
}

/** What went wrong, in the attention amber, read out as it comes. */
@Composable
private fun Problem(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyLarge,
        color = LocalAttention.current,
    )
}

// ---- Paired -----------------------------------------------------------------------------------------------------------

/** The plug as it stands, its switch, Admit a replacement tablet, Forget the plug. */
@Composable
private fun PairedPlug(settings: PlugSettings) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val status by graph.plug.status.collectAsStateWithLifecycle()
    val reachable by graph.plug.reachable.collectAsStateWithLifecycle()
    val armedAt by graph.rail.armedAt.collectAsStateWithLifecycle()
    val welded by graph.rail.welded.collectAsStateWithLifecycle()
    var heard by remember { mutableStateOf<PlugRead?>(null) }
    var said by rememberSaveable { mutableStateOf<String?>(null) }
    var asking by rememberSaveable { mutableStateOf(false) }
    PollWhileShown(STATUS_EVERY_MS) { graph.plug.switchStatus() }
    LaunchedEffect(settings.pairing?.address) { heard = graph.plug.read() }
    val left = secondsLeft(armedAt)
    val info = (heard as? PlugRead.Read)?.info
    // v1.26 — M62: a plug this tablet has no bond with that connects and stays silent has locked (SMART_PLUG.md).
    val locked = heard == PlugRead.Silent && settings.pairing?.address?.let(graph.plug.bluetooth::bonded) == false

    SectionEyebrow(PowerCopy.STATE)
    val answering = reachable != false
    ReadingRow(PowerCopy.OUTPUT, if (answering) PowerCopy.output(status, left) else PowerCopy.NOT_ANSWERING)
    ReadingRow(PowerCopy.DRAW, if (answering) PowerCopy.watts(status) else "—")
    ReadingRow(
        PowerCopy.SETUP,
        when {
            info != null -> PowerCopy.provision(info.provision)
            locked -> PowerCopy.provision(Provision.Locked)
            heard == null -> PowerCopy.READING
            else -> PowerCopy.NOT_ANSWERING
        },
    )
    ReadingRow(PowerCopy.FIRMWARE, info?.ver ?: settings.pairing?.firmware ?: "—")
    if (locked) Problem(PairFlow.LOCKED)
    if (welded) Problem(PowerCopy.WELDED)

    SectionEyebrow(PowerCopy.TITLE)
    Anchored(PageRows.PLUG_SWITCH.anchor) {
        SwitchRow(
            PowerCopy.SWITCH,
            settings.on,
            onChange = graph::setPlugOn,
            note = if (settings.on) PowerCopy.SWITCH_ON_NOTE else PowerCopy.SWITCH_OFF_NOTE,
        )
    }
    Anchored(PageRows.ADMIT_TABLET.anchor) {
        ActionRow(note = said ?: PowerCopy.ADMIT_NOTE) {
            ActionButton(PowerCopy.ADMIT, onClick = {
                scope.launch { said = if (graph.admitTablet()) PowerCopy.ADMIT_OPEN else PowerCopy.ADMIT_FAILED }
            })
        }
    }
    Anchored(PageRows.FORGET_PLUG.anchor) {
        ActionRow(note = PowerCopy.FORGET_NOTE) { ActionButton(PowerCopy.FORGET, onClick = { asking = true }) }
    }
    if (asking) {
        GlassAlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(PowerCopy.FORGET_TITLE) },
            text = { Text(PowerCopy.FORGET_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    graph.forgetPlug()
                }) { Text(PowerCopy.FORGET_ACTION) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/** The seconds the plug's timer has left from the last ARM ([armedAt], elapsed ms), counted down while shown; null with none. */
@Composable
internal fun secondsLeft(armedAt: Long?): Long? {
    val left by produceState<Long?>(null, armedAt) {
        while (true) {
            value = armedAt?.let { RailKeeper.TOGGLE_AFTER_S - (SystemClock.elapsedRealtime() - it) / MS_PER_S }?.takeIf { it > 0 }
            if (value == null) break
            delay(MS_PER_S)
        }
    }
    return left
}

/** [read] now and every [everyMs] after while the app is in front, one after another (each may wait on the plug). */
@Composable
internal fun PollWhileShown(everyMs: Long, read: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(read)
    LaunchedEffect(lifecycle, everyMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                latest()
                delay(everyMs)
            }
        }
    }
}

/** The Pair screen's numbered steps, in order (Done is the end, not a step). */
private val STEPS = listOf(PairStep.ShellyApp, PairStep.Find, PairStep.SetUp, PairStep.Complete, PairStep.Network, PairStep.Verify)

private const val STATUS_EVERY_MS = 2_000L
private const val MS_PER_S = 1_000L
