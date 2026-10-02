// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.instruments.LiveState
import dev.stevenjin.stevenpiano.record.RecordingSession
import dev.stevenjin.stevenpiano.record.RecordingState
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.rememberKioskGate
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.components.ConnectionLine
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassHeaderPane
import dev.stevenjin.stevenpiano.ui.components.KeyLayout
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keys: the piano, from the phone or tablet. A playable keyboard across the screen (two octaves
 * on a phone, about four on a small tablet, all 84 keys on a large one); where the keyboard
 * scrolls, a mini-map at the top with ‹ › octave buttons at either end. The keys are never taller
 * than [keysHeightCap] (a tall tablet upright would otherwise draw 800 dp keys that read as a
 * barcode): they sit at the bottom, just above the latching Sustain, the VELOCITY of the last key
 * for a second, and the connection line, and whatever height is left over stays empty. Every key
 * and the pedal let go when the screen stops (another tab, the app in the background) and when
 * the link drops. While a key is held the screen keeps its orientation ([HoldOrientationWhileHeld]):
 * a rotation would end every touch; it turns once the keys are let go, from the same first key.
 * The keyboard is never under glass: the whole screen stops above the tab bar, beside the rail and
 * below the header's glass ([LocalFloatingPadding]), and the pedal and the octave buttons are glass
 * pills floating just above the keys' top edge ([KeysPills], DESIGN.md › v1.9), never over them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeysScreen(onOpenPiano: () -> Unit, onListen: (Long) -> Unit = {}) {
    val graph = LocalContext.current.graph
    val frame = LocalAppFrame.current
    val vm = viewModel { KeysViewModel(graph) }
    val link by vm.link.collectAsStateWithLifecycle()
    val sustain by vm.sustain.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val tablet by vm.tabletSound.collectAsStateWithLifecycle()
    val keyboard by vm.keyboard.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val visible = frame.keysVisibleWhites
    val gate = rememberKioskGate()
    val touches = remember(vm) { KeyTouches(vm) }
    val pressed = remember { mutableIntStateOf(0) }
    val firstWhite = remember(vm, visible) { { vm.firstWhite(visible) } }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        touches.releaseAll()
        pressed.intValue++
        vm.letGo()
    }
    DisposableEffect(vm) { onDispose { vm.letGo() } }
    // Live plays only while this tab is on screen and the app is in front (v1.11 — M29).
    LifecycleStartEffect(vm) {
        vm.onScreen(true)
        onStopOrDispose { vm.onScreen(false) }
    }
    // The screen stays on while Live is on or a take runs: a timeout would stop the app mid-performance.
    val view = LocalView.current
    val taking = recording is RecordingState.Recording
    val keepOn = (live.wanted && keyboard.connected) || taking
    // The take's time on the Record control, once a second while it runs.
    var elapsed by remember { mutableStateOf(RecordingSession.clock(0L)) }
    if (taking) {
        LaunchedEffect(recording) {
            while (true) {
                val nanos = vm.elapsedNanos()
                elapsed = RecordingSession.clock(nanos)
                delay(1_000L - (nanos / 1_000_000L) % 1_000L)
            }
        }
    }
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    HoldOrientationWhileHeld(touches, pressed)
    // What a MIDI keyboard holds lights the keys too (v1.11 — M29): looked at once a frame while one is chosen.
    if (keyboard.chosen != null) {
        LaunchedEffect(vm) {
            var seen = vm.external.changes.get()
            while (true) {
                withFrameNanos { }
                val now = vm.external.changes.get()
                if (now != seen) {
                    seen = now
                    pressed.intValue++
                }
            }
        }
    }
    val held = remember(vm) { { key: Int -> vm.external.isHeld(key) } }

    // The status bar, before the header's glass takes the top: the keys' cap is measured as before.
    val outer = LocalFloatingPadding.current
    val statusBar = outer.calculateTopPadding()
    val direction = LocalLayoutDirection.current
    val sides = PaddingValues(start = outer.calculateStartPadding(direction), end = outer.calculateEndPadding(direction))
    // The header is a glass navigation bar (DESIGN.md › v1.9); nothing scrolls beneath it here. The whole pane keeps
    // clear of the rail, its header too.
    GlassHeaderPane(scroll = null, modifier = Modifier.padding(sides), header = { ScreenHeader("Keys") }) { BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(top = LocalFloatingPadding.current.calculateTopPadding(), bottom = LocalFloatingPadding.current.calculateBottomPadding()),
    ) {
        val keysHeight = keysHeightCap(maxHeight + LocalFloatingPadding.current.calculateTopPadding() - statusBar)
        Column(Modifier.fillMaxSize()) {
            // The keyboard, while one is set (v1.11 — M29): "KEYBOARD · ROLAND FP-30X", its state in words when not connected.
            InstrumentCopy.keysEyebrow(keyboard)?.let { line ->
                Eyebrow(
                    line,
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A piano runs low to high from the left in every language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                if (frame.keysScroll) {
                    KeyMiniMap(
                        touches,
                        pressed,
                        visible,
                        firstWhite,
                        onMove = { vm.moveTo(it, visible) },
                        onSettle = { vm.settle(visible) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        held = held,
                    )
                }
                // The keys at the bottom of the space between the mini-map and the row under them, and just
                // above their top edge the glass pills: the pedal, and where the keys scroll the octave buttons.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    val first = firstWhite()
                    val octaves = if (!frame.keysScroll) {
                        null
                    } else {
                        OctavePills(canGoDown = first > 0f, canGoUp = first < (KeyLayout.WHITE_KEYS - visible).toFloat()) { vm.shiftOctave(it, visible) }
                    }
                    KeysPills(
                        sustain,
                        vm::setSustain,
                        octaves,
                        live = if (keyboard.connected) LivePill(on = live.wanted, enabled = !live.looped, onToggle = vm::setLive) else null,
                        record = { RecordButton(taking, elapsed, enabled = recording != RecordingState.Saving, onToggle = vm::record) },
                    )
                    // One quiet line under the pills (v1.11 — M29): why Live is off, or the piano's 2 s rule while it plays.
                    (recordingNote(recording) ?: keysNote(live, keyboard.connected, steven = link is LinkState.Connected))?.let { note ->
                        Eyebrow(
                            note,
                            Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            uppercase = false,
                        )
                    }
                    PlayableKeyboard(
                        touches,
                        pressed,
                        visible,
                        firstWhite,
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                            .weight(1f, fill = false)
                            .heightIn(max = keysHeight)
                            .fillMaxHeight()
                            .clip(MaterialTheme.shapes.medium),
                        held = held,
                    )
                }
            }
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                VelocityReadout(vm.velocity)
                ConnectionLine(
                    connected = link is LinkState.Connected,
                    playing = playing,
                    onOpenPiano = onOpenPiano,
                    notConnected = if (tablet.active) "Not connected. The tablet plays these keys." else "Not connected. The piano won't play these keys.",
                    sent = if (keyboard.connected && !live.open) InstrumentCopy.KEYBOARD_LIVE_OFF else "Sent to piano",
                    live = !(keyboard.connected && !live.open),
                )
            }
        }
    } }
    // After Stop: keep, listen or discard (v1.11 — M29); in kiosk mode without the PIN, listen or leave it waiting.
    (recording as? RecordingState.Saved)?.let { saved ->
        RecordingSheet(
            saved.recording,
            saved.ended,
            locked = gate.locked,
            onKeep = { title -> vm.keep(saved.recording, title) },
            onDiscard = { vm.discard(saved.recording) },
            onListen = {
                vm.answered()
                onListen(saved.recording.pieceId)
            },
            onDone = vm::answered,
        )
    }
}

/** After a take: the line under the pills when nothing was played or nothing could be saved; else none. */
internal fun recordingNote(state: RecordingState): String? = when (state) {
    RecordingState.Empty -> InstrumentCopy.NOTHING_PLAYED
    RecordingState.Failed -> InstrumentCopy.NOT_SAVED
    else -> null
}

/**
 * Android ends every touch in progress when the display rotates (each window gets a cancel), which
 * would let go of every key a finger holds. So while any key is held the activity keeps its
 * orientation; once the last key is let go it may turn again, and a rotation asked for meanwhile
 * happens then. Leaving the screen gives the orientation back as it was.
 */
@Composable
private fun HoldOrientationWhileHeld(touches: KeyTouches, pressedVersion: MutableIntState) {
    val activity = LocalActivity.current ?: return
    val scope = rememberCoroutineScope()
    DisposableEffect(activity, touches) {
        val free = activity.requestedOrientation
        val job = scope.launch {
            snapshotFlow {
                pressedVersion.intValue   // bumped after every touch
                touches.anyHeld
            }.distinctUntilChanged().collect { held ->
                activity.requestedOrientation = if (held) ActivityInfo.SCREEN_ORIENTATION_LOCKED else free
            }
        }
        onDispose {
            job.cancel()
            activity.requestedOrientation = free
        }
    }
}

/**
 * The line under the pills (v1.11 — M29): why Live switched itself off (until it is on again), that the keyboard
 * is the instrument too, or, while Live plays Steven Piano ([steven]), that the piano lets a held key go after
 * 2 seconds; else none.
 */
internal fun keysNote(live: LiveState, keyboardConnected: Boolean, steven: Boolean): String? = when {
    live.tripped != null -> InstrumentCopy.tripped(live.tripped)
    keyboardConnected && live.looped -> InstrumentCopy.LOOPED
    live.open && steven -> InstrumentCopy.COILS_NOTE
    else -> null
}

/** The keys' tallest: 320 dp, or 45 % of the screen's height where that is less (a phone on its side). */
internal fun keysHeightCap(available: Dp): Dp = min(MAX_KEYS_HEIGHT, available * KEYS_HEIGHT_SHARE)

private val MAX_KEYS_HEIGHT = 320.dp
private const val KEYS_HEIGHT_SHARE = 0.45f

/**
 * "VELOCITY 84" in the eyebrow style with tabular figures, for a second after each key, so the
 * mapping can be learned. It appears and disappears as a cut; its room is always kept, so
 * nothing beside it moves.
 */
@Composable
private fun VelocityReadout(velocity: Int?) {
    Box {
        Eyebrow("Velocity 127", Modifier.alpha(0f).clearAndSetSemantics { })
        if (velocity != null) Eyebrow("Velocity $velocity")
    }
}
