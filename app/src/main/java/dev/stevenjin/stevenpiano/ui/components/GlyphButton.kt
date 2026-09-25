// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import kotlinx.coroutines.delay

/** Held down, a repeating button waits this long, then repeats this often. */
private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 60L

/**
 * An icon-only control: at least a 48 dp target, a monochrome glyph, and always a
 * [contentDescription]. Disabled glyphs use the disabled-glyph token. With [repeatWhileHeld]
 * (a stepper's − and + over a wide range), holding it down repeats [onClick] until it is let go
 * or disabled; the release after a repeat is not one more click.
 */
@Composable
fun GlyphButton(
    @DrawableRes glyph: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyphSize: Dp = 24.dp,
    repeatWhileHeld: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val click by rememberUpdatedState(onClick)
    val repeat = remember { RepeatState() }
    if (repeatWhileHeld) {
        val pressed by interaction.collectIsPressedAsState()
        LaunchedEffect(pressed, enabled) {
            if (!pressed) return@LaunchedEffect
            repeat.repeated = false
            if (!enabled) return@LaunchedEffect
            delay(REPEAT_AFTER_MS)
            repeat.repeated = true
            while (true) {
                click()
                delay(REPEAT_EVERY_MS)
            }
        }
    }
    IconButton(
        onClick = {
            if (repeat.repeated) repeat.repeated = false else onClick()
        },
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = LocalDisabledGlyph.current,
        ),
        interactionSource = interaction,
    ) {
        Icon(painterResource(glyph), contentDescription = contentDescription, modifier = Modifier.size(glyphSize))
    }
}

/** Whether the press now ending has already repeated, so its release is not a click too. */
private class RepeatState {
    var repeated = false
}
