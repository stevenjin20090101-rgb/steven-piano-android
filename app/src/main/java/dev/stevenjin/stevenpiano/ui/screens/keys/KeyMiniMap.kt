// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.KeyLayout
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import kotlin.math.roundToInt

private val MiniMapHeight = 28.dp
private const val BLACK_KEY_HEIGHT = 0.62f

/**
 * The whole 84-key keyboard in miniature, with the part the playable keyboard shows framed by a
 * rectangle in the content colour. Dragging moves the frame ([onMove], in white keys, as the
 * finger goes; [onSettle] when it lifts); touching outside the frame brings it there first. Keys
 * a finger holds invert here too, and those a MIDI keyboard holds ([held], v1.11 — M29). The ‹ › octave
 * buttons beside it are the way in for TalkBack.
 */
@Composable
fun KeyMiniMap(
    touches: KeyTouches,
    pressedVersion: IntState,
    visibleWhites: Int,
    firstWhite: () -> Float,
    onMove: (firstWhite: Float) -> Unit,
    onSettle: () -> Unit,
    modifier: Modifier = Modifier,
    held: (Int) -> Boolean = { false },
) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val blackKey = LocalTertiary.current
    val line = LocalHairline.current
    val visible by rememberUpdatedState(visibleWhites)
    val first by rememberUpdatedState(firstWhite)
    val move by rememberUpdatedState(onMove)
    val settle by rememberUpdatedState(onSettle)
    val start = firstWhite().roundToInt()
    val range = "${KeyboardGeometry.name(KeyboardGeometry.whiteKey(start))} to ${KeyboardGeometry.name(KeyboardGeometry.whiteKey(start + visibleWhites - 1))}"
    Spacer(
        modifier
            .height(MiniMapHeight)
            .semantics {
                contentDescription = "Keyboard position"
                stateDescription = range
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val white = size.width / KeyLayout.WHITE_KEYS.toFloat()
                    val frameLeft = first() * white
                    val frameWidth = visible * white
                    val grab = if (down.position.x in frameLeft..frameLeft + frameWidth) down.position.x - frameLeft else frameWidth / 2
                    down.consume()
                    move((down.position.x - grab) / white)
                    drag(down.id) { change ->
                        change.consume()
                        move((change.position.x - grab) / white)
                    }
                    settle()
                }
            }
            .drawWithCache {
                val keys = KeyLayout(size.width)
                val sep = Hairline.toPx()
                val blackHeight = size.height * BLACK_KEY_HEIGHT
                val frameStroke = 2.dp.toPx()
                val stroke = Stroke(frameStroke)
                onDrawBehind {
                    pressedVersion.intValue   // a touch changed what is held
                    drawRect(body)
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (!keys.isBlack(i) && (touches.isPressed(KeyMap.LOWEST + i) || held(KeyMap.LOWEST + i))) {
                            drawRect(ink, Offset(keys.left(i), 0f), Size(keys.width(i), size.height))
                        }
                    }
                    for (w in 1 until KeyLayout.WHITE_KEYS) {
                        drawRect(line, Offset(w * keys.whiteWidth - sep / 2, 0f), Size(sep, size.height))
                    }
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (!keys.isBlack(i)) continue
                        drawRect(body, Offset(keys.left(i) - sep, 0f), Size(keys.width(i) + 2 * sep, blackHeight + sep))
                        val down = touches.isPressed(KeyMap.LOWEST + i) || held(KeyMap.LOWEST + i)
                        drawRect(if (down) ink else blackKey, Offset(keys.left(i), 0f), Size(keys.width(i), blackHeight))
                    }
                    val left = firstWhite() * keys.whiteWidth
                    drawRect(
                        ink,
                        topLeft = Offset(left + frameStroke / 2, frameStroke / 2),
                        size = Size(visible * keys.whiteWidth - frameStroke, size.height - frameStroke),
                        style = stroke,
                    )
                }
            },
    )
}
