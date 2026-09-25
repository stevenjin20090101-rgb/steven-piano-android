// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.components.ConnectionLine
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.KeyLayout
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader

/**
 * Keys: the piano, from the phone or tablet. A playable keyboard across the screen (two octaves
 * on a phone, about four on a small tablet, all 84 keys on a large one); where the keyboard
 * scrolls, a mini-map at the top with ‹ › octave buttons at either end. The keys are never taller
 * than [keysHeightCap] (a tall tablet upright would otherwise draw 800 dp keys that read as a
 * barcode): they sit at the bottom, just above the latching Sustain, the VELOCITY of the last key
 * for a second, and the connection line, and whatever height is left over stays empty. Every key
 * and the pedal let go when the screen stops (another tab, the app in the background) and when
 * the link drops.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeysScreen(onOpenPiano: () -> Unit) {
    val graph = LocalContext.current.graph
    val frame = LocalAppFrame.current
    val vm = viewModel { KeysViewModel(graph) }
    val link by vm.link.collectAsStateWithLifecycle()
    val sustain by vm.sustain.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val visible = frame.keysVisibleWhites
    val touches = remember(vm) { KeyTouches(vm) }
    val pressed = remember { mutableIntStateOf(0) }
    val firstWhite = remember(vm, visible) { { vm.firstWhite(visible) } }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        touches.releaseAll()
        pressed.intValue++
        vm.letGo()
    }
    DisposableEffect(vm) { onDispose { vm.letGo() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val keysHeight = keysHeightCap(maxHeight)
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Keys")
            // A piano runs low to high from the left in every language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                if (frame.keysScroll) {
                    val first = firstWhite()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphButton(R.drawable.ic_chevron_left, "Octave down", enabled = first > 0f) { vm.shiftOctave(-1, visible) }
                        KeyMiniMap(
                            touches,
                            pressed,
                            visible,
                            firstWhite,
                            onMove = { vm.moveTo(it, visible) },
                            onSettle = { vm.settle(visible) },
                            modifier = Modifier.weight(1f),
                        )
                        GlyphButton(R.drawable.ic_chevron_right, "Octave up", enabled = first < (KeyLayout.WHITE_KEYS - visible).toFloat()) {
                            vm.shiftOctave(1, visible)
                        }
                    }
                }
                // The keys at the bottom of the space between the mini-map and the row under them.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    PlayableKeyboard(
                        touches,
                        pressed,
                        visible,
                        firstWhite,
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                            .heightIn(max = keysHeight)
                            .fillMaxHeight()
                            .clip(MaterialTheme.shapes.medium),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SustainButton(sustain, vm::setSustain)
                    Spacer(Modifier.width(16.dp))
                    VelocityReadout(vm.velocity)
                }
                ConnectionLine(
                    connected = link is LinkState.Connected,
                    playing = playing,
                    onOpenPiano = onOpenPiano,
                    notConnected = "Not connected. The piano won't play these keys.",
                )
            }
        }
    }
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
