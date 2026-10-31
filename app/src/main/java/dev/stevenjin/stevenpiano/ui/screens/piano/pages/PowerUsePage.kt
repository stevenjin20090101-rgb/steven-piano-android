// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.power.EnergyMeter
import dev.stevenjin.stevenpiano.power.Reading
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.SystemCopy
import dev.stevenjin.stevenpiano.ui.components.FactRow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import java.time.ZoneId

/**
 * Piano › Power use (v1.36 — M72): what the piano's 24 V supply draws, as the SwitchBot plug's adverts say it. **Now**: the
 * watts from the latest advert ("—" with none for 10 s, or no plug), and the last 10 minutes as a line, redrawn as readings
 * come (no animation). **Peak**: today's and since pairing, each with its time. **Energy**: today, this month and since
 * pairing, in kWh. The cards are the app's paper and ink; nothing here is ever amber, nothing loops.
 */
@Composable
fun PowerUsePage() {
    val graph = LocalContext.current.graph
    val settings by graph.plugSettings.collectAsStateWithLifecycle()
    val seen by graph.plugAdverts.seen.collectAsStateWithLifecycle()
    val use by graph.powerUse.state.collectAsStateWithLifecycle()
    SectionRule()
    if (settings?.pairing == null) NoteLine(PowerCopy.USE_NO_PLUG)   // and Now reads "—"
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SystemCard(PowerCopy.USE_NOW, "") {
            val watts = PowerCopy.watts(seen?.watts)
            Text(
                watts,
                Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clearAndSetSemantics { contentDescription = "${PowerCopy.USE_NOW}, $watts" },
                style = MaterialTheme.typography.displaySmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurface,
            )
            CardNote(PowerCopy.USE_LINE)
            WattsLine(use.line, now)
        }
        SystemCard(PowerCopy.USE_PEAK, "") {
            Fact(PowerCopy.USE_TODAY, PowerCopy.peak(use.todayPeak, now, zone))
            Fact(PowerCopy.USE_ALL_TIME, PowerCopy.peak(use.peak, now, zone))
        }
        SystemCard(PowerCopy.USE_ENERGY, "") {
            Fact(PowerCopy.USE_TODAY, PowerCopy.kwh(use.todayWh))
            Fact(PowerCopy.USE_MONTH, PowerCopy.kwh(use.monthWh))
            Fact(PowerCopy.USE_ALL_TIME, PowerCopy.kwh(use.totalWh))
        }
    }
    NoteLine(PowerCopy.USE_NOTE)
}

/** A card's fact, a hairline above it. */
@Composable
private fun Fact(label: String, value: String) {
    HairlineDivider(startInset = 16.dp)
    FactRow(SystemCopy.Fact(label, value))
}

/**
 * The last 10 minutes of watts, a plain line in ink over a hairline floor, its top the highest reading (at least 10 W). A
 * gap of over 10 s between readings is a break in the line. Drawn as it stands: a new reading redraws it, nothing moves.
 */
@Composable
private fun WattsLine(line: List<Reading>, now: Long) {
    val ink = MaterialTheme.colorScheme.onSurface
    val hairline = LocalHairline.current
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(LINE_HEIGHT)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics { },
    ) {
        val top = maxOf(MIN_TOP_WATTS, line.maxOfOrNull { it.watts } ?: 0.0)
        val start = now - EnergyMeter.LINE_MS
        fun x(t: Long): Float = size.width * ((t - start).toFloat() / EnergyMeter.LINE_MS).coerceIn(0f, 1f)
        fun y(w: Double): Float = size.height * (1f - (w / top).toFloat().coerceIn(0f, 1f))
        drawLine(hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        val path = Path()
        var last: Reading? = null
        for (reading in line) {
            val joined = last?.let { reading.at - it.at <= EnergyMeter.GAP_MS } == true
            if (joined) path.lineTo(x(reading.at), y(reading.watts)) else path.moveTo(x(reading.at), y(reading.watts))
            last = reading
        }
        drawPath(path, ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private val LINE_HEIGHT = 120.dp
private const val MIN_TOP_WATTS = 10.0
