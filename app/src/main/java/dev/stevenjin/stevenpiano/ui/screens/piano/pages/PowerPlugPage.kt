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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.power.FoundPlug
import dev.stevenjin.stevenpiano.power.PairFlow
import dev.stevenjin.stevenpiano.power.PairState
import dev.stevenjin.stevenpiano.power.PairStep
import dev.stevenjin.stevenpiano.power.PlugSettings
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.GlassAlertDialog
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NavRow
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.ReadingRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import kotlinx.coroutines.delay

/**
 * Piano › Power plug (v1.26 — M60; the SwitchBot Plug Mini since v1.36 — M72), behind the kiosk PIN as every Piano page.
 * With no plug: what it is for, what to do first (the plug in the wall, nothing in the SwitchBot app, an outlet timer
 * behind it) and **Pair the SwitchBot plug**, which opens the Pair screen's steps. Paired: the plug as its adverts say it
 * (the 24 V on or off, the supply's draw), the switch, a link to **Power use** ([onOpenPage]) and **Forget the plug**.
 */
@Composable
fun PowerPlugPage(onOpenPage: (SettingsPage) -> Unit) {
    val graph = LocalContext.current.graph
    val settings by graph.plugSettings.collectAsStateWithLifecycle()
    val pair by graph.pairFlow.state.collectAsStateWithLifecycle()
    var opened by rememberSaveable { mutableStateOf(false) }
    val current = settings
    if (current == null) {
        NoteLine(PowerCopy.READING)
        return
    }
    val pairing = pair.step != PairStep.Done && (opened || pair.step != PairStep.Find || pair.searching || pair.found.isNotEmpty())
    when {
        pairing -> PairSteps(pair, graph.pairFlow, onStartOver = {
            graph.pairFlow.restart()
            opened = current.pairing == null
            if (opened) graph.pairFlow.search()
        })
        current.pairing != null -> PairedPlug(current, clicked = pair.step == PairStep.Done && pair.clicked, onOpenPage)
        else -> Unpaired(current, onPair = {
            graph.pairFlow.restart()
            opened = true
            graph.pairFlow.search()
        })
    }
}

/** No plug yet: what it is for, what to do first, and Pair the SwitchBot plug (v1.36 — M72: the Shelly's pairing cleared, said). */
@Composable
private fun Unpaired(settings: PlugSettings, onPair: () -> Unit) {
    SectionRule()
    if (settings.shellyCleared) Problem(PowerCopy.SHELLY_CLEARED)
    NoteLine(PowerCopy.EXPLANATION)
    FirstWords()
    HairlineDivider(startInset = 16.dp)
    Anchored(PageRows.PAIR_PLUG.anchor) {
        ActionRow(note = PowerCopy.PAIR_NOTE) { ActionButton(PowerCopy.PAIR, onClick = onPair) }
    }
}

/** Before Find (v1.36 — M72): the plug in the wall and the supply in it, nothing in the SwitchBot app, the outlet timer. */
@Composable
private fun FirstWords() {
    for (line in listOf(PowerCopy.PLUG_IN, PowerCopy.NOT_IN_APP, PowerCopy.OUTLET_TIMER)) NoteLine("· $line")
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
            NoteLine(PowerCopy.stepNote(step, pair))
            if (step == PairStep.Find) FindStep(pair, flow)
            if (step == PairStep.Check && pair.problem != null) {
                pair.plug?.let { plug -> ActionRow { ActionButton("Try again", onClick = { flow.choose(plug) }, enabled = !pair.working) } }
                ActionRow { ActionButton(PowerCopy.SEARCH, onClick = flow::search, enabled = !pair.working) }
            }
            if (pair.working) ProgressHairline(null, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            pair.problem?.let { Problem(it) }
        }
        HairlineDivider(startInset = 16.dp)
    }
    ActionRow { ActionButton(PowerCopy.START_OVER, onClick = onStartOver, enabled = !pair.working || pair.searching) }
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

/** Step 1: the plugs found, the strongest first (a tap chooses one and stops the search), and Search again. */
@Composable
private fun FindStep(pair: PairState, flow: PairFlow) {
    FirstWords()
    for (plug in pair.found) FoundRow(plug, enabled = !pair.working || pair.searching) { flow.choose(plug) }
    ActionRow(note = if (pair.searching) PowerCopy.SEARCHING else null) {
        ActionButton(PowerCopy.SEARCH, onClick = flow::search, enabled = !pair.searching && !pair.working)
    }
}

/** A plug the search found: "SwitchBot Plug Mini (US) …47CA" and its signal. */
@Composable
private fun FoundRow(plug: FoundPlug, enabled: Boolean, onChoose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onChoose)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            PowerCopy.found(plug),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            plug.rssi?.let { "$it dBm" }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HairlineDivider(startInset = 16.dp)
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

/** The plug as its adverts say it, its switch, Power use, Forget the plug; [clicked] just after pairing, said once. */
@Composable
private fun PairedPlug(settings: PlugSettings, clicked: Boolean, onOpenPage: (SettingsPage) -> Unit) {
    val graph = LocalContext.current.graph
    val seen by graph.plugAdverts.seen.collectAsStateWithLifecycle()
    val welded by graph.rail.welded.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf(false) }

    if (settings.on) ManualSwitch()   // v1.38 — M75: near the top
    SectionEyebrow(PowerCopy.STATE)
    if (clicked) NoteLine(PowerCopy.CLICKED)
    ReadingRow(PowerCopy.OUTPUT, PowerCopy.output(seen))
    ReadingRow(PowerCopy.DRAW, PowerCopy.watts(seen?.watts))
    if (welded) Problem(PowerCopy.WELDED)
    NoteLine(PowerCopy.OUTLET_TIMER)

    SectionEyebrow(PowerCopy.TITLE)
    Anchored(PageRows.PLUG_SWITCH.anchor) {
        SwitchRow(
            PowerCopy.SWITCH,
            settings.on,
            onChange = graph::setPlugOn,
            note = if (settings.on) PowerCopy.SWITCH_ON_NOTE else PowerCopy.SWITCH_OFF_NOTE,
        )
    }
    NavRow(
        PowerCopy.USE_TITLE,
        PowerCopy.USE_OPEN,
        onClick = { onOpenPage(SettingsPage.PowerUse) },
        glyph = painterResource(SettingsPage.PowerUse.glyph),
    )
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

/**
 * v1.38 — M75: **Turn the piano on** (the rail wanted as any feature wants it: ARM, the piano's link, the CHECK), which
 * becomes **Turn the piano off** while on, with how long it stays on ("On · turns off in 28 min": 30 minutes after the
 * last note). Behind the kiosk PIN, as the whole page is.
 */
@Composable
private fun ManualSwitch() {
    val graph = LocalContext.current.graph
    val until by graph.rail.manualUntil.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(until) {
        while (until != null) {
            now = SystemClock.elapsedRealtime()
            delay(MANUAL_TICK_MS)
        }
    }
    val left = until?.let { it - now }?.takeIf { it > 0 }
    ActionRow(note = left?.let(PowerCopy::manualOn) ?: PowerCopy.MANUAL_OFF_NOTE) {
        ActionButton(if (left != null) PowerCopy.TURN_OFF else PowerCopy.TURN_ON, onClick = { graph.setPianoOn(left == null) })
    }
    HairlineDivider(startInset = 16.dp)
}

/** How often the manual switch's minutes left are looked at again. */
private const val MANUAL_TICK_MS = 15_000L

/** The Pair screen's numbered steps, in order (v1.36 — M72). */
private val STEPS = listOf(PairStep.Find, PairStep.Check, PairStep.Done)
