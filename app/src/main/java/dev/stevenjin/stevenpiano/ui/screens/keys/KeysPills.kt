// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/** Where the keyboard scrolls, the ‹ › octave buttons at the pills' two ends. */
class OctavePills(val canGoDown: Boolean, val canGoUp: Boolean, val onShift: (Int) -> Unit)

/**
 * The Live pill (v1.11 — M29): shown while a MIDI keyboard is connected; [on] is the remembered switch;
 * [enabled] false while the keyboard is the instrument too (Live would play every key twice).
 */
class LivePill(val on: Boolean, val enabled: Boolean, val onToggle: (Boolean) -> Unit)

/**
 * The glass pills floating just above the keyboard's top edge (DESIGN.md › v1.9, v1.11), in this order:
 * Live (while a MIDI keyboard is connected, [live]), the ‹ octave down (where the keyboard scrolls,
 * [octaves]), the latching [SustainButton], the › octave up, and the Record control ([record]), 48 dp each.
 * The first three sit at the row's start, the last two at its end; when they don't fit (large text) the
 * row scrolls sideways, one line always. They never cover the keys (whose tops are the soft end of every
 * key): the keyboard is never under glass.
 */
@Composable
fun KeysPills(
    sustain: Boolean,
    onSustain: (Boolean) -> Unit,
    octaves: OctavePills?,
    modifier: Modifier = Modifier,
    live: LivePill? = null,
    record: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                Modifier
                    .widthIn(min = width)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (live != null) LiveButton(live)
                    if (octaves != null) OctavePill(R.drawable.ic_chevron_left, "Octave down", octaves.canGoDown) { octaves.onShift(-1) }
                    SustainButton(sustain, onSustain)
                }
                Row(
                    Modifier.padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (octaves != null) OctavePill(R.drawable.ic_chevron_right, "Octave up", octaves.canGoUp) { octaves.onShift(1) }
                    record?.invoke()
                }
            }
        }
    }
}

/**
 * Live (v1.11 — M29): a latching glass pill like Sustain, "Live" and "Live on" (its ring brightens to the
 * content colour when on), TalkBack "Live, on". Never red: the connection line's live dot says the keys are
 * sent to the piano. Disabled (the hairline ring, the disabled glyph colour) while the keyboard is the
 * instrument too.
 */
@Composable
private fun LiveButton(pill: LivePill) {
    val ink = MaterialTheme.colorScheme.onSurface
    GlassSurface(
        Modifier,
        shape = CircleShape,
        blur = false,
        outline = when {
            !pill.enabled -> LocalHairline.current
            pill.on -> ink
            else -> LocalTertiary.current
        },
    ) {
        Box(
            Modifier
                .heightIn(min = PillHeight)
                .toggleable(value = pill.on && pill.enabled, enabled = pill.enabled, role = Role.Switch, onValueChange = pill.onToggle)
                .semantics { contentDescription = InstrumentCopy.LIVE }
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (pill.on && pill.enabled) InstrumentCopy.LIVE_ON else InstrumentCopy.LIVE,
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.labelLarge,
                color = if (pill.enabled) ink else LocalDisabledGlyph.current,
            )
        }
    }
}

/**
 * Record (v1.11 — M29): a glass pill in the content colour, never red (red means sent to the piano): a filled
 * circle and "Record"; while a take runs, a filled square and its [elapsed] time in tabular digits ("0:42").
 * TalkBack reads "Record" with its state ("Recording" or "Off"), never the ticking time: the sheet after Stop
 * says how long the take was.
 */
@Composable
fun RecordButton(recording: Boolean, elapsed: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val ink = if (enabled) MaterialTheme.colorScheme.onSurface else LocalDisabledGlyph.current
    GlassSurface(
        Modifier,
        shape = CircleShape,
        blur = false,
        outline = when {
            !enabled -> LocalHairline.current
            recording -> ink
            else -> LocalTertiary.current
        },
    ) {
        Row(
            Modifier
                .heightIn(min = PillHeight)
                .toggleable(value = recording, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
                .semantics {
                    contentDescription = InstrumentCopy.RECORD
                    stateDescription = if (recording) InstrumentCopy.RECORDING_ON else InstrumentCopy.RECORDING_OFF
                }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(RecordGlyph)
                    .background(ink, if (recording) RoundedCornerShape(2.dp) else CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (recording) elapsed else InstrumentCopy.RECORD,
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.labelLarge.merge(Tabular),
                color = ink,
            )
        }
    }
}

/** The Record control's glyph: a 12 dp circle, or a square while a take runs. */
private val RecordGlyph = 12.dp

/**
 * An octave button: a 48 dp glass circle, its glyph in the content colour and its ring in the action
 * outline's grey; disabled, the disabled glyph and the hairline. Glass without a blur, as the pedal.
 */
@Composable
private fun OctavePill(@DrawableRes glyph: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    GlassSurface(
        Modifier.size(PillHeight),
        shape = CircleShape,
        blur = false,
        outline = if (enabled) LocalTertiary.current else LocalHairline.current,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(glyph),
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else LocalDisabledGlyph.current,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
