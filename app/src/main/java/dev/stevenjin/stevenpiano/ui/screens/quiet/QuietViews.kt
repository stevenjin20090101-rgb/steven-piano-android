// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.quiet

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.schedule.QuietCopy
import dev.stevenjin.stevenpiano.schedule.QuietNow
import dev.stevenjin.stevenpiano.ui.KioskGate
import dev.stevenjin.stevenpiano.ui.PlaybackStarter
import dev.stevenjin.stevenpiano.ui.components.GlassAlertDialog
import dev.stevenjin.stevenpiano.ui.components.GlassEdge
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.components.secondaryText
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Play anyway (DESIGN.md › v1.20 — M54), as the app's frame provides it: the quiet lifts until its block ends (behind
 * the kiosk PIN while the kiosk keeps the settings locked), and a piece loaded and not playing plays. Nothing by default.
 */
val LocalPlayAnyway = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * A play from a control the playback starter doesn't hold (Up next's rows): run at once, or during a quiet time kept for
 * the person's choice ([PlaybackStarter.whenAllowed]), as the frame provides it. At once by default.
 */
val LocalQuietAsk = staticCompositionLocalOf<(() -> Unit) -> Unit> { { start -> start() } }

/** The quiet times now, as they change. */
@Composable
fun rememberQuiet(): QuietNow {
    val quiet by LocalContext.current.graph.quiet.state.collectAsStateWithLifecycle()
    return quiet
}

/** "9:30": when the quiet ends, on the tablet's clock. */
fun quietUntil(quiet: QuietNow, now: ZonedDateTime = ZonedDateTime.now()): String =
    quiet.until?.let { QuietCopy.until(QuietCopy.at(it, ZoneId.systemDefault()), now) } ?: ""

/**
 * While a quiet time holds the piano (a block on, nobody chose Play anyway): a glass capsule, the moon and "Quiet until
 * 9:30", with Play anyway at its end ([LocalPlayAnyway]); nothing otherwise. On Now playing (its foot, or over "Choose a
 * piece from the library."); [translucent] over the album's colours, as the other capsules there.
 */
@Composable
fun QuietCapsule(modifier: Modifier = Modifier, translucent: Boolean = false) {
    val quiet = rememberQuiet()
    if (!quiet.holds) return
    val playAnyway = LocalPlayAnyway.current
    GlassSurface(modifier, shape = CircleShape, edge = GlassEdge.Outline, blur = false, translucent = translucent) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_moon), contentDescription = null, modifier = Modifier.size(18.dp), tint = secondaryText())
            Spacer(Modifier.width(8.dp))
            Text(QuietCopy.capsule(quietUntil(quiet)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = playAnyway) { Text(QuietCopy.PLAY_ANYWAY) }
        }
    }
}

/**
 * A play asked for during a quiet time ([PlaybackStarter.held]: the transport's Play, a row of the library, a channel):
 * "Quiet until 9:30", what the quiet is, Cancel and Play anyway, which lifts the quiet (behind the kiosk PIN through
 * [gate] while the kiosk is on) and plays what was asked.
 */
@Composable
fun QuietChoiceDialog(playback: PlaybackStarter, gate: KioskGate) {
    if (playback.held == null) return
    val quiet = rememberQuiet()
    val until = quietUntil(quiet)
    GlassAlertDialog(
        onDismissRequest = playback::dismissHeld,
        title = { Text(QuietCopy.capsule(until)) },
        text = { Text(QuietCopy.choice(until)) },
        dismissButton = { TextButton(onClick = playback::dismissHeld) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                val action = playback.takeHeld()
                gate.run { playback.playAnyway(action) }
            }) { Text(QuietCopy.PLAY_ANYWAY) }
        },
    )
}
