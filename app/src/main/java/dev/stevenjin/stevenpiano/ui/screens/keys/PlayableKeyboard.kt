// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.KeyLayout
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import kotlin.math.min
import kotlin.math.roundToInt

/** Octave labels scale with the system font only while they fit their C key, and never past this. */
private const val MAX_LABEL_SCALE = 1.5f
private const val LABEL_FILL = 0.9f

/**
 * The playable keyboard: [visibleWhites] white keys across the canvas, starting at white key
 * [firstWhite] (read at draw and touch time, so scrolling never restarts a touch). White keys on
 * the elevated surface with hairline gaps, black keys in the tertiary grey at 60 % height, the
 * octave letters (C1 … C7) in the eyebrow style at the bottom of each C. A key a finger holds
 * inverts to the content colour for as long as the finger is down, and so does one a MIDI keyboard
 * holds ([held], v1.11 — M29; the screen bumps [pressedVersion] as it changes); [touches] turns fingers into
 * notes, several at once for chords, and sliding plays a glissando. Where a key is touched sets
 * its loudness: the top is soft, the bottom loud. Drawing reads [pressedVersion], bumped after
 * every touch, so it redraws without recomposing. No haptics: this is a frequent interaction.
 */
@Composable
fun PlayableKeyboard(
    touches: KeyTouches,
    pressedVersion: MutableIntState,
    visibleWhites: Int,
    firstWhite: () -> Float,
    modifier: Modifier = Modifier,
    held: (Int) -> Boolean = { false },
) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val pressed = MaterialTheme.colorScheme.onSurface
    val pressedLabel = MaterialTheme.colorScheme.surface
    val blackKey = LocalTertiary.current
    val line = LocalHairline.current
    val labelStyle = MaterialTheme.typography.labelSmall.merge(Tabular)
    val measurer = rememberTextMeasurer()
    val visible by rememberUpdatedState(visibleWhites)
    val first by rememberUpdatedState(firstWhite)
    val geometry = remember { GeometryCache() }
    val start = firstWhite().roundToInt()
    val low = KeyboardGeometry.name(KeyboardGeometry.whiteKey(start))
    val high = KeyboardGeometry.name(KeyboardGeometry.whiteKey(start + visibleWhites - 1))
    Spacer(
        modifier
            .semantics {
                contentDescription = "Playable piano keyboard, $low to $high. Near the top of a key plays softly, near the bottom loudly."
            }
            .systemGestureExclusion()
            .pointerInput(touches) {
                awaitPointerEventScope {
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val keys = geometry.of(size.width.toFloat(), size.height.toFloat(), visible)
                            val from = first()
                            for (change in event.changes) {
                                val id = change.id.value
                                val at = change.position
                                when {
                                    change.changedToDownIgnoreConsumed() -> {
                                        val key = keys.keyAt(at.x, at.y, from)
                                        touches.down(id, key, keys.velocityAt(key, at.y))
                                    }
                                    change.changedToUpIgnoreConsumed() -> touches.up(id)
                                    change.pressed && change.positionChanged() -> {
                                        val key = keys.keyAt(at.x, at.y, from)
                                        touches.move(id, key, keys.velocityAt(key, at.y))
                                    }
                                }
                                change.consume()
                            }
                            pressedVersion.intValue++
                        }
                    } finally {
                        touches.releaseAll()
                        pressedVersion.intValue++
                    }
                }
            }
            .drawWithCache {
                val keys = KeyboardGeometry(size.width, size.height, visibleWhites)
                val layout = keys.layout
                val sep = Hairline.toPx()
                val labels = octaveLabels(measurer, labelStyle, this, keys.whiteWidth)
                val labelBottom = size.height - 8.dp.toPx()
                onDrawBehind {
                    pressedVersion.intValue   // a touch changed what is held
                    val offset = keys.offset(firstWhite())
                    val right = offset + size.width
                    drawRect(body)
                    translate(left = -offset) {
                        for (i in 0 until KeyMap.KEY_COUNT) {
                            if (layout.isBlack(i) || layout.right(i) < offset || layout.left(i) > right) continue
                            val down = touches.isPressed(KeyMap.LOWEST + i) || held(KeyMap.LOWEST + i)
                            if (down) drawRect(pressed, Offset(layout.left(i), 0f), Size(layout.width(i), size.height))
                            if ((KeyMap.LOWEST + i) % 12 == 0) {
                                val label = labels[(KeyMap.LOWEST + i) / 12 - 2]
                                drawLabel(label, layout.left(i) + (layout.width(i) - label.size.width) / 2, labelBottom - label.size.height, if (down) pressedLabel else blackKey)
                            }
                        }
                        for (w in 1 until KeyLayout.WHITE_KEYS) {
                            val x = w * keys.whiteWidth
                            if (x < offset || x > right) continue
                            drawRect(line, Offset(x - sep / 2, 0f), Size(sep, size.height))
                        }
                        for (i in 0 until KeyMap.KEY_COUNT) {
                            if (!layout.isBlack(i) || layout.right(i) < offset || layout.left(i) > right) continue
                            drawRect(body, Offset(layout.left(i) - sep, 0f), Size(layout.width(i) + 2 * sep, keys.blackHeight + sep))
                            val down = touches.isPressed(KeyMap.LOWEST + i) || held(KeyMap.LOWEST + i)
                            drawRect(if (down) pressed else blackKey, Offset(layout.left(i), 0f), Size(layout.width(i), keys.blackHeight))
                        }
                    }
                }
            },
    )
}

/** The last geometry built for touches, rebuilt only when the canvas or the visible range changes. */
private class GeometryCache {
    private var last: KeyboardGeometry? = null

    fun of(width: Float, height: Float, visibleWhites: Int): KeyboardGeometry {
        val cached = last
        if (cached != null && cached.width == width && cached.height == height && cached.visibleWhites == visibleWhites) return cached
        return KeyboardGeometry(width, height, visibleWhites).also { last = it }
    }
}

/**
 * "C1" … "C7", measured once per size in the eyebrow style. The system font scale applies only
 * while the widest label still fits its key (up to 1.5x); the letters stay 12 sp or more.
 */
private fun octaveLabels(measurer: TextMeasurer, style: TextStyle, density: Density, whiteWidth: Float): Array<TextLayoutResult> {
    val plain = Density(density.density, 1f)
    val widest = measurer.measure("C4", style, maxLines = 1, density = plain).size.width
    val fit = if (widest > 0) whiteWidth * LABEL_FILL / widest else MAX_LABEL_SCALE
    val scale = min(density.fontScale, fit.coerceIn(1f, MAX_LABEL_SCALE))
    val scaled = Density(density.density, scale)
    return Array(7) { measurer.measure("C${it + 1}", style, maxLines = 1, density = scaled) }
}

private fun DrawScope.drawLabel(label: TextLayoutResult, x: Float, y: Float, color: Color) {
    drawText(label, color = color, topLeft = Offset(x, y))
}
