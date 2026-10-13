// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.audio.KeyTest
import dev.stevenjin.stevenpiano.audio.KeyTestState
import dev.stevenjin.stevenpiano.ui.KioskGate
import dev.stevenjin.stevenpiano.ui.ListenCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.SmallFigures
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * Listen and test (v1.23 — M55; DESIGN.md › v1.23 — M55), a card of the System page: **Listening from the panel**, the
 * switch that lets the web panel hear the room through the tablet's microphone (off by default; turning it on asks Android
 * for the microphone first; behind the kiosk PIN as the page is), and **Test every key**, which strikes the piano's 84
 * keys in turn while the microphone hears which didn't sound: no live sound here, its progress ("Key 37 of 84") and then
 * the keyboard map, the keys not heard in the attention amber with the words that name them.
 */
@Composable
internal fun ListenCard(vm: PianoViewModel, gate: KioskGate) {
    val listen by vm.listen.collectAsStateWithLifecycle()
    val test by vm.keyTest.collectAsStateWithLifecycle()
    var refused by rememberSaveable { mutableStateOf(false) }
    var said by rememberSaveable { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        if (granted) vm.microphoneGranted()
    }
    SystemCard(ListenCopy.TITLE, ListenCopy.LINE) {
        Anchored(PageRows.LISTEN_FROM_PANEL.anchor) {
            SwitchRow(
                PageRows.LISTEN_FROM_PANEL.label,
                checked = listen.allowed,
                onChange = { on ->
                    gate.run {
                        when {
                            !on -> vm.setListenFromPanel(false)
                            vm.microphoneAllowed() -> vm.microphoneGranted()
                            else -> ask.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                note = ListenCopy.SWITCH_NOTE,
                locked = gate.locked,
            )
        }
        if (refused && !listen.allowed) NoteLine(ListenCopy.NOT_ALLOWED)
        Anchored(PageRows.TEST_EVERY_KEY.anchor) {
            ActionRow(note = said ?: ListenCopy.TEST_NOTE) {
                if (test.running) {
                    ActionButton(ListenCopy.STOP, onClick = {
                        vm.stopKeyTest()
                        said = null
                    })
                } else {
                    // Striking every key changes the piano: behind the kiosk PIN, as the page's other tools are.
                    ActionButton(PageRows.TEST_EVERY_KEY.label, onClick = { gate.run { said = vm.testEveryKey() } })
                }
            }
        }
        if (test.running) {
            Meter(test.done.toFloat() / test.total.coerceAtLeast(1), MaterialTheme.colorScheme.onSurface, TEST_BAR, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        KeyMap(test)
        Text(
            line(test),
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            color = if (test.results?.any { !it.heard } == true) LocalAttention.current else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the test says now: its progress while it runs, then the result, or why it stopped, or that none ran yet. */
private fun line(test: KeyTestState): String {
    val results = test.results
    return when {
        test.running && test.key != null -> ListenCopy.progress(test.done, test.total)
        test.running && test.done == 0 -> ListenCopy.STARTING
        test.running -> ListenCopy.CHECKING
        results != null -> ListenCopy.result(results)
        else -> test.stopped ?: ListenCopy.NOT_TESTED
    }
}

/** How a key of the map is drawn. */
private enum class Shade { Pending, Struck, Current, Heard, Missing }

private fun shadeOf(key: Int, test: KeyTestState, heard: Map<Int, Boolean>?): Shade = when {
    test.running -> when {
        key == test.key -> Shade.Current
        test.key == null && test.done >= test.total -> Shade.Struck
        test.key != null && key < test.key -> Shade.Struck
        else -> Shade.Pending
    }
    heard != null -> if (heard[key] == false) Shade.Missing else Shade.Heard
    else -> Shade.Pending
}

/**
 * The piano's 84 keys as seven octaves, C1 to C7, in the board octaves' style: while the test runs the keys struck so far,
 * the one struck now in the content colour, those to come faint; then every key heard as the octaves draw an answering
 * board's, every key not heard in the attention amber. TalkBack reads the line beside it, which names those keys.
 */
@Composable
private fun KeyMap(test: KeyTestState) {
    val content = MaterialTheme.colorScheme.onSurface
    val card = MaterialTheme.colorScheme.surfaceVariant
    val hairline = LocalHairline.current
    val amber = LocalAttention.current
    val tertiary = LocalTertiary.current
    val heard = remember(test.results) { test.results?.associate { it.key to it.heard } }
    val spoken = line(test)
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Keyboard map: $spoken" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(MAP_WIDTH / MAP_HEIGHT),
        ) {
            val u = size.width / MAP_WIDTH
            val line = Stroke(width = 1.dp.toPx())
            fun natural(shade: Shade): Color = when (shade) {
                Shade.Pending -> content.copy(alpha = PENDING_ALPHA)
                Shade.Struck -> content.copy(alpha = STRUCK_ALPHA)
                Shade.Current -> content
                Shade.Heard -> content.copy(alpha = HEARD_ALPHA)
                Shade.Missing -> amber
            }
            fun sharp(shade: Shade): Color = when (shade) {
                Shade.Pending -> content.copy(alpha = PENDING_ALPHA)
                Shade.Struck -> content.copy(alpha = SHARP_STRUCK_ALPHA)
                Shade.Current -> content
                Shade.Heard -> content.copy(alpha = SHARP_HEARD_ALPHA)
                Shade.Missing -> amber
            }
            for (o in 0 until OCTAVES) {
                val x = o * OCTAVE_STEP
                val first = KeyTest.KEYS.first + o * SEMITONES
                drawRoundRect(hairline, Offset((x + 2) * u, 2 * u), Size(56 * u, 44 * u), CornerRadius(6 * u), style = line)
                for ((k, step) in NATURAL_STEPS.withIndex()) {
                    val at = Offset((x + 4 + k * KEY_STEP) * u, 5 * u)
                    drawRoundRect(natural(shadeOf(first + step, test, heard)), at, Size(6.3f * u, 38 * u), CornerRadius(1.5f * u))
                }
                for ((k, step) in SHARP_STEPS) {
                    val at = Offset((x + 4 + k * KEY_STEP + 4.6f) * u, 5 * u)
                    val key = Size(4.4f * u, 23 * u)
                    // The gap around a sharp, as the octaves draw it, then the sharp itself.
                    drawRoundRect(card, Offset(at.x - 0.8f * u, at.y), Size(key.width + 1.6f * u, key.height + 0.8f * u), CornerRadius(1.6f * u))
                    drawRoundRect(sharp(shadeOf(first + step, test, heard)), at, key, CornerRadius(1.2f * u))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for (o in 0 until OCTAVES) {
                val first = KeyTest.KEYS.first + o * SEMITONES
                val missing = heard != null && (first until first + SEMITONES).any { heard[it] == false }
                Text(
                    KeyboardGeometry.name(first),
                    Modifier.weight(1f),
                    style = SmallFigures,
                    color = if (missing) amber else tertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val TEST_BAR = 3.dp
private const val OCTAVES = 7
private const val SEMITONES = 12
private const val OCTAVE_STEP = 60f
private const val KEY_STEP = 7.5f
private const val MAP_WIDTH = 420f
private const val MAP_HEIGHT = 48f

/** The naturals' places in an octave (C D E F G A B) and the sharps' (after C, D, F, G, A), as semitones above its C. */
private val NATURAL_STEPS = listOf(0, 2, 4, 5, 7, 9, 11)
private val SHARP_STEPS = listOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)

private const val PENDING_ALPHA = 0.16f
private const val STRUCK_ALPHA = 0.5f
private const val HEARD_ALPHA = 0.88f
private const val SHARP_STRUCK_ALPHA = 0.32f
private const val SHARP_HEARD_ALPHA = 0.5f
