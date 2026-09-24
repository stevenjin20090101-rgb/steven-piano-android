// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

val KeyboardStripHeight = 44.dp
private const val BLACK_KEY_HEIGHT = 0.62f

/**
 * The piano's 84 keys under the roll, monochrome: white keys on the elevated surface split by
 * hairlines, black keys in the tertiary grey. A key the piano is playing inverts to the content
 * colour. It redraws each frame [frameNanos] changes, reading the player's key bitsets
 * ([activeLow]: bit `key - 24`, keys 24-87; [activeHigh]: bit `key - 88`) without allocating.
 */
@Composable
fun KeyboardStrip(
    frameNanos: LongState,
    activeLow: () -> Long,
    activeHigh: () -> Long,
    modifier: Modifier = Modifier,
) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val pressed = MaterialTheme.colorScheme.onSurface
    val blackKey = LocalTertiary.current
    val line = LocalHairline.current
    Spacer(
        modifier
            .fillMaxWidth()
            .height(KeyboardStripHeight)
            .drawWithCache {
                val keys = KeyLayout(size.width)
                val sep = Hairline.toPx()
                val blackHeight = size.height * BLACK_KEY_HEIGHT
                onDrawBehind {
                    frameNanos.longValue   // a new frame: the keys may have changed
                    val low = activeLow()
                    val high = activeHigh()
                    drawRect(body)
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (!keys.isBlack(i) && isActive(i, low, high)) {
                            drawRect(pressed, Offset(keys.left(i), 0f), Size(keys.width(i), size.height))
                        }
                    }
                    for (w in 1 until KeyLayout.WHITE_KEYS) {
                        drawRect(line, Offset(w * keys.whiteWidth - sep / 2, 0f), Size(sep, size.height))
                    }
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (!keys.isBlack(i)) continue
                        drawRect(body, Offset(keys.left(i) - sep, 0f), Size(keys.width(i) + 2 * sep, blackHeight + sep))
                        drawRect(if (isActive(i, low, high)) pressed else blackKey, Offset(keys.left(i), 0f), Size(keys.width(i), blackHeight))
                    }
                }
            },
    )
}

private fun isActive(i: Int, low: Long, high: Long): Boolean =
    if (i < 64) (low ushr i) and 1L == 1L else (high ushr (i - 64)) and 1L == 1L
