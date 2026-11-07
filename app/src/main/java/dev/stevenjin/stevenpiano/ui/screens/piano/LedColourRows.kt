// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.piano.LedColour
import dev.stevenjin.stevenpiano.piano.LedColourSource
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.ui.LedSwatch
import dev.stevenjin.stevenpiano.ui.LightsCopy
import dev.stevenjin.stevenpiano.ui.SettingNotes
import dev.stevenjin.stevenpiano.ui.components.ChoiceRow
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.HairlineSlider
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.Motion
import kotlin.math.roundToInt

// The strip's colour on Piano › Lights and screen (M80), on a piano that says `!ledcolorproto=1`. The page's chrome stays
// monochrome; the swatches, the cover's colour and the bands under the sliders are the strip's own colours, which are
// content (DESIGN.md: one meaning per UI colour does not reach the piano's LEDs). No glass: these are rows of a page.

/**
 * A row that comes and goes with the colour's choice ([LedColour.dependsOnColour]): it grows in and out as the
 * disclosures do, a cut under reduced motion ([reduced]). Every other row just draws.
 */
@Composable
internal fun ColourReveal(visible: Boolean, conditional: Boolean, reduced: Boolean, content: @Composable () -> Unit) {
    if (!conditional) {
        content()
        return
    }
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) EnterTransition.None else expandVertically(tween(Motion.StandardMs, easing = Motion.Standard)) + fadeIn(tween(Motion.StandardMs)),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(Motion.StandardMs, easing = Motion.Standard)) + fadeOut(tween(Motion.QuickMs)),
        label = "strip colour row",
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * Colour: Album art, Custom or Palette (Palette in Reactive only), the tablet's own choice; with Album art, a line under
 * it saying what the loaded piece's cover gives the strip, its colour beside it.
 */
@Composable
internal fun ColourSourceRow(ready: PianoState.Ready, chosen: LedColourSource, actions: PianoSettingsActions) {
    val mode = ready.values[LedColour.MODE]
    val sources = LedColour.sources(mode)
    val shown = LedColour.shown(chosen, mode)
    ChoiceRow(
        LightsCopy.COLOUR,
        sources.map(LightsCopy::source),
        sources.indexOf(shown).takeIf { it >= 0 },
        { actions.setLedColourSource(sources[it]) },
        enabled = ready.lock.writable,
        note = LightsCopy.sourceNote(shown),
    )
    if (shown == LedColourSource.ALBUM_ART) CoverNowRow()
}

/** With Album art: the loaded piece's cover colour, and in words where the strip's colour comes from just now. */
@Composable
private fun CoverNowRow() {
    val graph = LocalContext.current.graph
    val cover by graph.ledColours.cover.collectAsStateWithLifecycle()
    val player by graph.player.state.collectAsStateWithLifecycle()
    val title = player.piece?.title
    val line = LightsCopy.now(title, cover)
    val rgb = cover?.rgb
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = if (rgb == null) line else "$line, ${LightsCopy.name(rgb)}" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(rgb, 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HairlineDivider(startInset = 16.dp)
}

/**
 * The custom colour (`ledcolor`): its swatch at the row's end, twelve named swatches, then any colour by hue and
 * saturation (at full value: the strip's brightness is its own control). Each change is written as the page writes
 * any setting, and the piano keeps it with the page's save. With Album art it is the colour a grey cover, or none, shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomColourRow(ready: PianoState.Ready, chosen: LedColourSource, actions: PianoSettingsActions) {
    val rgb = LedColour.rgb(ready.values[LedColour.COLOR])
    val enabled = rgb != null && ready.lock.writable
    val hsv = rgb?.let(LedColour::hsv)
    // The hue the sliders stand at: the colour's own, kept through a grey one (white has no hue to move from).
    var hue by rememberSaveable { mutableFloatStateOf(hsv?.hue ?: 0f) }
    LaunchedEffect(rgb) { if (hsv != null && hsv.saturation > GREY) hue = hsv.hue }
    val saturation = hsv?.saturation ?: 1f
    val pick: (Int) -> Unit = { actions.setPianoValue(LedColour.COLOR, LedColour.hex(it)) }
    val fallback = LedColour.shown(chosen, ready.values[LedColour.MODE]) == LedColourSource.ALBUM_ART
    val label = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = LightsCopy.CUSTOM_COLOUR + (rgb?.let { ", " + LightsCopy.name(it) } ?: "")
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            ) {
                Text(LightsCopy.CUSTOM_COLOUR, style = MaterialTheme.typography.bodyLarge, color = label)
                if (fallback) Eyebrow(SettingNotes.CUSTOM_COLOUR_FALLBACK, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
            }
            Swatch(rgb, 32.dp)
        }
        FlowRow(Modifier.fillMaxWidth().semantics { selectableGroup() }) {
            for (swatch in LightsCopy.SWATCHES) SwatchButton(swatch, selected = rgb == swatch.rgb, enabled = enabled) { pick(swatch.rgb) }
        }
        Eyebrow(LightsCopy.HUE, Modifier.padding(top = 8.dp))
        HairlineSlider(
            value = hsv?.let { hue },
            range = 0f..HUE_MAX,
            step = 1f,
            onChange = { h ->
                hue = h
                pick(LedColour.rgb(h, if (saturation < GREY) 1f else saturation))   // from white, a hue means its full colour
            },
            contentDescription = "${LightsCopy.CUSTOM_COLOUR} ${LightsCopy.HUE.lowercase()}",
            stateDescription = rgb?.let(LightsCopy::name) ?: "not known",
            modifier = Modifier.fillMaxWidth().band(Brush.horizontalGradient(HUE_STOPS)),
            enabled = enabled,
        )
        Eyebrow(LightsCopy.SATURATION)
        HairlineSlider(
            value = hsv?.saturation?.times(PERCENT),
            range = 0f..PERCENT,
            step = 1f,
            onChange = { s -> pick(LedColour.rgb(hue, s / PERCENT)) },
            contentDescription = "${LightsCopy.CUSTOM_COLOUR} ${LightsCopy.SATURATION.lowercase()}",
            stateDescription = hsv?.let { "${(it.saturation * PERCENT).roundToInt()} percent" } ?: "not known",
            modifier = Modifier.fillMaxWidth().band(Brush.horizontalGradient(listOf(Color.White, colourOf(LedColour.rgb(hue, 1f))))),
            enabled = enabled,
        )
    }
    HairlineDivider(startInset = 16.dp)
}

/** Background glow (`ledambient`, Reactive) or Colour change (`ledfade`): four named levels as chips. */
@Composable
internal fun LevelRow(label: String, levels: List<LedColour.Level>, name: String, note: String, ready: PianoState.Ready, actions: PianoSettingsActions) {
    val wire = ready.values[name]
    ChoiceRow(
        label,
        levels.map { it.label },
        LedColour.levelOf(levels, wire),
        { actions.setPianoValue(name, levels[it].value.toString()) },
        enabled = wire != null && ready.lock.writable,
        note = note,
        spoken = levels.map { it.spoken },
    )
}

/** One preset swatch in a 48 dp target: the colour in a 32 dp disc with a hairline edge, ringed in ink when it is the colour. */
@Composable
private fun SwatchButton(swatch: LedSwatch, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(48.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = swatch.name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(42.dp)
                    .border(2.dp, ink, CircleShape),
            )
        }
        Swatch(swatch.rgb, 32.dp, Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA))
    }
}

/** A colour of the strip's in a disc with a hairline edge, or the edge alone while the colour is not known or there is none. */
@Composable
private fun Swatch(rgb: Int?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (rgb != null) Modifier.background(colourOf(rgb)) else Modifier)
            .border(Hairline, LocalHairline.current, CircleShape)
            .clearAndSetSemantics { },
    )
}

/** A band of the colours a slider moves through, 4 dp tall, under its track and thumb and as wide as the thumb travels. */
private fun Modifier.band(brush: Brush): Modifier = drawBehind {
    val inset = THUMB_RADIUS.toPx()
    val top = size.height / 2 + BAND_GAP.toPx()
    val tall = BAND_HEIGHT.toPx()
    drawRoundRect(brush, Offset(inset, top), Size(size.width - 2 * inset, tall), CornerRadius(tall / 2))
}

private fun colourOf(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/** Under this saturation a colour reads as white or grey: it has no hue to keep. */
private const val GREY = 0.02f
private const val HUE_MAX = 359f
private const val PERCENT = 100f
private const val DISABLED_ALPHA = 0.38f
private val THUMB_RADIUS = 6.dp
private val BAND_GAP = 9.dp
private val BAND_HEIGHT = 4.dp
private val HUE_STOPS = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { colourOf(LedColour.rgb(it, 1f)) }
