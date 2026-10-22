// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.diag.PianoDiag
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.ui.SystemCopy
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/**
 * A fact the app reads ([SystemCopy.Fact]), on the System page's cards and the piano's Firmware and status page (one
 * row for both since v1.24 — M57): its name, and its value at the end ("—" when not known, the small "Needs newer
 * firmware" tag, the amber with its words when it needs attention, the link's dot before the Bluetooth row's); a value too
 * long to sit beside the name goes under it. TalkBack reads "name, value". The page draws the hairlines between rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FactRow(fact: SystemCopy.Fact) {
    val spoken = when {
        fact.needs -> SystemCopy.NEEDS_FIRMWARE.lowercase()
        fact.value == null -> "not known"
        else -> fact.value
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clearAndSetSemantics { contentDescription = "${fact.label}, $spoken" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Apart,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(fact.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        when {
            fact.needs -> NeedsTag()
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (fact.connected != null) {
                    // The Bluetooth row's link: filled while connected, a ring when not; it never knows of notes, so never red.
                    LiveDot(LinkDot.of(connected = fact.connected, sending = false))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    fact.value ?: NONE,
                    style = MaterialTheme.typography.bodyLarge.merge(Tabular).copy(fontWeight = if (fact.attention) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (fact.attention) LocalAttention.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** What waits for newer piano firmware: the words, small and engraved, in a hairline frame. */
@Composable
private fun NeedsTag() {
    Box(
        Modifier
            .border(Hairline, LocalHairline.current, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Eyebrow(SystemCopy.NEEDS_FIRMWARE)
    }
}

/**
 * Whether the 24 V power plug switches the rail and it isn't up now (v1.26 — M62): off, coming up or going off. A power
 * board that doesn't answer then is unpowered, not missing ([PowerBoards]' `off`, `Attention.Inputs.railOff`, the boards
 * row's "· 24 V off"). False with no plug paired or its switch off: the boards as before the plug.
 */
@Composable
fun rememberRailOff(): Boolean {
    val graph = LocalContext.current.graph
    val plug by graph.plugSettings.collectAsStateWithLifecycle()
    val rail by graph.rail.phase.collectAsStateWithLifecycle()
    return plug?.switches == true && rail != RailPhase.Up && rail != RailPhase.Kept
}

/**
 * The seven power boards as seven octaves of keys, C1 to C7 (the panel's drawing; the System page and the piano's
 * Firmware and status page draw it, one drawing since v1.24 — M57): an answering board's keys in the content colour, a
 * missing one hollow and amber with "Missing" under it, an unknown one faint, [title] above them when given. [boards] as
 * `PianoDiag.boards` reads them. While the power plug keeps the 24 V off ([off], v1.26 — M62, [rememberRailOff]) a board
 * that doesn't answer is unpowered, not missing: hollow in ink, never amber, nothing under it but its name. TalkBack reads
 * them in a line.
 */
@Composable
fun PowerBoards(boards: List<String>?, title: String? = null, off: Boolean = false) {
    val content = MaterialTheme.colorScheme.onSurface
    val card = MaterialTheme.colorScheme.surfaceVariant
    val hairline = LocalHairline.current
    val amber = LocalAttention.current
    val tertiary = LocalTertiary.current
    val spoken = boards?.mapIndexed { i, board ->
        "C${i + 1} " + when {
            board == PianoDiag.OK -> "OK"
            board == PianoDiag.MISSING -> if (off) "off" else "missing"
            else -> "not known"
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = (if (off) "Power boards, the 24 V off: " else "Power boards: ") + (spoken?.joinToString(", ") ?: "not known")
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (title != null) {
            Text(title, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(OCTAVES_WIDTH / OCTAVES_HEIGHT),
        ) {
            val u = size.width / OCTAVES_WIDTH
            val line = Stroke(width = 1.dp.toPx())
            for (o in 0 until BOARDS) {
                val state = boards?.getOrNull(o)
                val x = o * OCTAVE_STEP
                val absent = state == PianoDiag.MISSING
                val missing = absent && !off
                val unpowered = absent && off
                val known = state == PianoDiag.OK
                drawRoundRect(if (missing) amber else hairline, Offset((x + 2) * u, 2 * u), Size(56 * u, 44 * u), CornerRadius(6 * u), style = line)
                for (k in 0 until NATURALS) {
                    val at = Offset((x + 4 + k * KEY_STEP) * u, 5 * u)
                    val key = Size(6.3f * u, 38 * u)
                    when {
                        missing -> drawRoundRect(amber, at, key, CornerRadius(1.5f * u), style = line)
                        unpowered -> drawRoundRect(tertiary, at, key, CornerRadius(1.5f * u), style = line)
                        known -> drawRoundRect(content.copy(alpha = KEY_ALPHA), at, key, CornerRadius(1.5f * u))
                        else -> drawRoundRect(content.copy(alpha = FAINT_ALPHA), at, key, CornerRadius(1.5f * u))
                    }
                }
                for (k in SHARPS) {
                    val at = Offset((x + 4 + k * KEY_STEP + 4.6f) * u, 5 * u)
                    val key = Size(4.4f * u, 23 * u)
                    drawRoundRect(card, at, key, CornerRadius(1.2f * u))
                    if (absent) drawRoundRect(if (missing) amber else tertiary, at, key, CornerRadius(1.2f * u), style = line)
                }
                val railAt = Offset((x + 2) * u, 54 * u)
                val rail = Size(56 * u, 6 * u)
                when {
                    absent -> drawRoundRect(if (missing) amber else tertiary, railAt, rail, CornerRadius(3 * u), style = Stroke(width = 1.5.dp.toPx()))
                    known -> drawRoundRect(content, railAt, rail, CornerRadius(3 * u))
                    else -> drawRoundRect(content.copy(alpha = FAINT_ALPHA), railAt, rail, CornerRadius(3 * u))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for (o in 0 until BOARDS) {
                val state = boards?.getOrNull(o)
                val missing = state == PianoDiag.MISSING && !off
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state == PianoDiag.OK) "C${o + 1} · OK" else "C${o + 1}",
                        style = SmallFigures,
                        color = if (missing) amber else tertiary,
                        textAlign = TextAlign.Center,
                    )
                    if (missing) Text("Missing", style = SmallFigures.copy(fontWeight = FontWeight.SemiBold), color = amber, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** The small figures under the boards and on the System page's chart: the eyebrow's size without its tracking. */
internal val SmallFigures: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)

private const val NONE = "—"
private const val KEY_ALPHA = 0.88f
private const val FAINT_ALPHA = 0.16f
private const val BOARDS = 7
private const val NATURALS = 7
private val SHARPS = listOf(0, 1, 3, 4, 5)
private const val OCTAVE_STEP = 60f
private const val KEY_STEP = 7.5f
private const val OCTAVES_WIDTH = 420f
private const val OCTAVES_HEIGHT = 62f
