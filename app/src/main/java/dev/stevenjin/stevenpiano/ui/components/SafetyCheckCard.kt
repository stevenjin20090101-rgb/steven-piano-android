// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.audio.KeyResult
import dev.stevenjin.stevenpiano.audio.KeyTest
import dev.stevenjin.stevenpiano.audio.KeyTestState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.safety.Deferral
import dev.stevenjin.stevenpiano.safety.Mark
import dev.stevenjin.stevenpiano.safety.SafetyLock
import dev.stevenjin.stevenpiano.safety.SafetyOutcome
import dev.stevenjin.stevenpiano.safety.SafetyState
import dev.stevenjin.stevenpiano.safety.SafetyStep
import dev.stevenjin.stevenpiano.safety.StepLine
import dev.stevenjin.stevenpiano.safety.Why
import dev.stevenjin.stevenpiano.ui.KioskGate
import dev.stevenjin.stevenpiano.ui.KioskGateSheet
import dev.stevenjin.stevenpiano.ui.SafetyCopy
import dev.stevenjin.stevenpiano.ui.rememberKioskGate
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.KeyMap
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.LocalLive
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * The safety check's card (v1.26 — M61; DESIGN.md › v1.26 — M61), composed over every screen, the resting screen too, as
 * the microphone's banner is: a calm full-screen glass card while a check runs, a moment after it passed or stopped, and
 * for as long as Play is locked.
 *
 * - **Running**: "Safety check", why, and the next piece's title; the steps ticking as they finish ("Keys released · No
 *   coil on · Boards answering (5 of 7) · Testing each key (37 of 84) · Restarting the piano · Power off and on"), those
 *   to come faint, a step left out in the secondary grey with why; and the slim progress line. The screen stays on.
 * - **Ended**: "Safety check passed" (it closes by itself) or "Safety check stopped" with why and "It runs again before
 *   the next piece."; OK closes it at once.
 * - **Locked**: "Safety check failed", the keys that didn't sound on the keyboard map in the attention amber, "Ask
 *   whoever looks after the piano to check these keys", **Run the safety check** (the kiosk PIN while the kiosk keeps
 *   the settings locked; only a check that passes unlocks Play) and **Not now** (the same PIN), which leaves a capsule
 *   at the top of every screen until Show, or until Play is refused again. A coil still on is the one thing in red.
 *
 * It holds every touch and Back while it shows; it fades in and out (a cut under reduced motion), its marks cross-fade,
 * nothing loops; every control is 48 dp or more.
 */
@Composable
fun BoxScope.SafetyCheckCard() {
    val graph = LocalContext.current.graph
    val runner = graph.safety
    val state by runner.state.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    val gate = rememberKioskGate()
    var said by remember { mutableStateOf<String?>(null) }
    KeepScreenOn(state.running)
    LaunchedEffect(state.running, state.lock) { said = null }
    AnimatedVisibility(
        visible = state.card,
        modifier = Modifier.matchParentSize(),
        enter = Motion.enter(fadeIn(tween(Motion.EmphasisedMs, easing = Motion.Enter)), reduced),
        exit = Motion.exit(fadeOut(tween(Motion.StandardMs, easing = Motion.Leave)), reduced),
        label = "safety check",
    ) {
        BackHandler {}   // what lies beneath waits
        GlassSurface(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = SafetyCopy.TITLE },
            fill = GlassFill.Sheet,
        ) {
            // Centred in the window, and scrolling where it is taller (large text on a phone on its side).
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val window = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = window)
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(Modifier.widthIn(max = CARD_WIDTH).fillMaxWidth()) {
                            val lock = state.lock
                            if (lock != null && !state.running) {
                                Locked(
                                    lock = lock,
                                    said = said,
                                    gate = gate,
                                    onRun = { why -> said = why?.let(SafetyCopy::deferral) },
                                    onAside = runner::putAside,
                                )
                            } else {
                                Checking(state, onDismiss = runner::dismiss)
                            }
                        }
                    }
                }
            }
        }
    }
    AnimatedVisibility(
        visible = state.lock != null && state.aside && !state.running,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = Motion.enter(fadeIn(tween(Motion.StandardMs, easing = Motion.Enter)) + slideInVertically(tween(Motion.StandardMs, easing = Motion.Enter)) { -it / 2 }, reduced),
        exit = Motion.exit(fadeOut(tween(Motion.QuickMs, easing = Motion.Leave)) + slideOutVertically(tween(Motion.QuickMs, easing = Motion.Leave)) { -it / 2 }, reduced),
        label = "play locked",
    ) {
        LockedCapsule(onShow = runner::showCard)
    }
    KioskGateSheet(gate)
}

/** A check running, or its moment after it passed or stopped. */
@Composable
private fun Checking(state: SafetyState, onDismiss: () -> Unit) {
    val ended = state.ended
    val title = when (ended) {
        is SafetyOutcome.Passed -> SafetyCopy.PASSED
        is SafetyOutcome.Unfinished -> SafetyCopy.STOPPED
        else -> SafetyCopy.TITLE
    }
    val line = when (ended) {
        is SafetyOutcome.Unfinished -> listOfNotNull(unfinishedWhy(ended), SafetyCopy.AGAIN).joinToString(" ")
        else -> SafetyCopy.LINE
    }
    Text(
        title,
        Modifier.semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        },
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(line, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.next?.let { next ->
        Text(
            SafetyCopy.next(next),
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalTertiary.current,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.size(24.dp))
    val steps = state.check?.steps() ?: listOf(StepLine(SafetyStep.RELEASE, Mark.RUNNING))
    steps.forEach { StepRow(it) }
    Spacer(Modifier.size(20.dp))
    ProgressHairline(state.check?.progress ?: 0f, Modifier.fillMaxWidth())
    if (ended != null) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TOUCH)) { Text(SafetyCopy.OK) }
        }
    }
}

/** Why a check stopped, in words. */
private fun unfinishedWhy(ended: SafetyOutcome.Unfinished): String? = ended.said ?: when (ended.why) {
    Why.NO_ANSWER -> "The piano didn't answer."
    Why.NO_DUMP -> SafetyCopy.RECONNECT
    Why.NO_PIANO -> SafetyCopy.NOT_CONNECTED
    Why.RAIL_DOWN -> SafetyCopy.RAIL_DOWN.replaceFirstChar { it.uppercase() } + "."
    else -> null
}

/** One step: its mark and its words, in the content colour, faint while to come, amber when failed (red for a coil on). */
@Composable
private fun StepRow(line: StepLine) {
    val reduced = rememberReducedMotion()
    val failed = if (line.why == Why.COIL_ON) LocalLive.current else LocalAttention.current
    val color = when (line.mark) {
        Mark.DONE, Mark.RUNNING -> MaterialTheme.colorScheme.onSurface
        Mark.WAITING -> LocalTertiary.current
        Mark.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        Mark.FAILED -> failed
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = STEP_HEIGHT)
            .semantics(mergeDescendants = true) { stateDescription = markWord(line.mark) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(line.mark, Modifier.size(MARK), animationSpec = Motion.timed(Motion.StandardMs, reduced), label = "step mark") { mark ->
            Box(Modifier.size(MARK), contentAlignment = Alignment.Center) { MarkGlyph(mark, color) }
        }
        Spacer(Modifier.width(12.dp))
        Text(SafetyCopy.line(line), style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** A tick when done, a cross when failed, a dot while running, a ring to come, a dash when left out. */
@Composable
private fun MarkGlyph(mark: Mark, color: Color) {
    when (mark) {
        Mark.DONE -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = color, modifier = Modifier.size(MARK))
        Mark.FAILED -> Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = color, modifier = Modifier.size(MARK))
        Mark.RUNNING -> Box(Modifier.size(DOT).background(color, CircleShape))
        Mark.WAITING -> Box(Modifier.size(DOT).border(1.5.dp, color, CircleShape))
        Mark.SKIPPED -> Box(Modifier.size(width = 10.dp, height = 2.dp).background(color, CircleShape))
    }
}

private fun markWord(mark: Mark): String = when (mark) {
    Mark.WAITING -> "to come"
    Mark.RUNNING -> "now"
    Mark.DONE -> "done"
    Mark.SKIPPED -> "left out"
    Mark.FAILED -> "failed"
}

/** Play locked: what failed, who to ask, and Run the safety check or Not now, both behind the kiosk PIN while it locks the settings. */
@Composable
private fun Locked(lock: SafetyLock, said: String?, gate: KioskGate, onRun: (Deferral?) -> Unit, onAside: () -> Unit) {
    val runner = LocalContext.current.graph.safety
    Text(
        SafetyCopy.FAILED,
        Modifier.semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        },
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.size(12.dp))
    when (lock) {
        SafetyLock.CoilOn -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(LinkDot.Sending, breathing = false, size = 10.dp)
                Spacer(Modifier.width(10.dp))
                Text(SafetyCopy.COIL_ON.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, color = LocalLive.current)
            }
            Text(SafetyCopy.ASK_COIL, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is SafetyLock.KeysSilent -> {
            Text(SafetyCopy.silent(lock.keys).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, color = LocalAttention.current)
            if (lock.keys.isNotEmpty()) {
                Box(Modifier.padding(vertical = 8.dp)) {
                    KeyMap(KeyTestState(results = KeyTest.KEYS.map { KeyResult(it, it !in lock.keys) }))
                }
            }
            Text(SafetyCopy.ASK, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { gate.run { onRun(runner.runNow()) } },
            modifier = Modifier.heightIn(min = TOUCH),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
        ) { Text(SafetyCopy.RUN) }
        TextButton(onClick = { gate.run(onAside) }, modifier = Modifier.heightIn(min = TOUCH)) { Text(SafetyCopy.NOT_NOW) }
    }
    if (said != null) {
        Text(
            said,
            Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The locked card put aside: a glass capsule at the top, "Play is locked · safety check failed", and Show. */
@Composable
private fun LockedCapsule(onShow: () -> Unit) {
    GlassSurface(
        Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 560.dp),
        shape = CircleShape,
        edge = GlassEdge.Outline,
        blur = false,
    ) {
        Row(
            Modifier
                .heightIn(min = TOUCH)
                .padding(start = 16.dp, end = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(DOT).background(LocalAttention.current, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                SafetyCopy.PLAY_LOCKED,
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onShow, modifier = Modifier.heightIn(min = TOUCH)) { Text(SafetyCopy.SHOW) }
        }
    }
}

/**
 * The screen stays on while a check runs (a screen that slept would end it with the microphone): the window's own flag,
 * so the views' keep-screen-on that other screens set (the Keys tab, the resting screen) is never touched.
 */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on, context) {
        val window = if (on) context.activity()?.window else null
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private val CARD_WIDTH = 520.dp
private val TOUCH = 48.dp
private val STEP_HEIGHT = 40.dp
private val MARK = 20.dp
private val DOT = 8.dp
