// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.max

/**
 * A segmented control (v1.14 — M37: the Library's genre, All · Classical · Modern): [options] as equal segments in a
 * capsule track, the content colour at 8 % over the surface with a hairline; the [selected] one a thumb lighter than
 * the track in both appearances (the paper's elevated surface; on ink the content colour at 18 %), with a hairline,
 * or a 1.5 dp edge in the content colour with high contrast text. Solid, never glass, even on a header's glass. The
 * track is 36 dp tall inside a 48 dp target; each segment is at least 88 dp wide and as wide as the widest label
 * needs, unless [modifier] sets the whole width (a phone's own row), which the segments then share. Labels in the
 * label style, the chosen one in the content colour and medium weight, the others in the secondary text's colour
 * ([secondaryText]). The thumb slides to a new choice on the settle spring, a cut when motion is reduced; a label
 * pressed scales as a chip's does; no haptic. TalkBack hears [label] for the group, then each segment as a radio
 * button ("Classical, selected", 2 of 3).
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.surface.luminance() > 0.5f
    val track = scheme.onSurface.copy(alpha = TRACK_ALPHA).compositeOver(scheme.surface)
    val thumb = if (light) scheme.surfaceContainer else scheme.onSurface.copy(alpha = THUMB_ALPHA_INK).compositeOver(scheme.surface)
    val hairline = LocalHairline.current
    // High contrast text (which also makes the glass solid): the thumb's edge in the content colour, 1.5 dp.
    val strongEdge = LocalReducedTransparency.current
    val thumbEdge = if (strongEdge) scheme.onSurface else hairline
    val chosenText = scheme.onSurface
    val otherText = secondaryText()
    val style = MaterialTheme.typography.labelLarge
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val position = animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = Motion.sprung(Motion.settle(), rememberReducedMotion()),
        label = "segment thumb",
    )
    // Every label measured in the chosen one's weight, so the control's width never changes with the choice.
    val measurer = rememberTextMeasurer(cacheSize = options.size.coerceAtLeast(1))
    val count = options.size.coerceAtLeast(1)
    Layout(
        content = {
            options.forEachIndexed { index, option ->
                val chosen = index == selected
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.selectable(
                        selected = chosen,
                        interactionSource = press,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        modifier = Modifier
                            .padding(horizontal = LABEL_PADDING)
                            .pressScale(press),
                        style = style,
                        fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                        color = if (chosen) chosenText else otherText,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        modifier = modifier
            .semantics {
                contentDescription = label
                selectableGroup()
            }
            .drawBehind {
                val trackHeight = size.height - (TARGET - TRACK).toPx()
                val top = (size.height - trackHeight) / 2
                val edge = Hairline.toPx()
                drawRoundRect(track, Offset(0f, top), Size(size.width, trackHeight), CornerRadius(trackHeight / 2))
                drawRoundRect(
                    hairline,
                    Offset(edge / 2, top + edge / 2),
                    Size(size.width - edge, trackHeight - edge),
                    CornerRadius((trackHeight - edge) / 2),
                    style = Stroke(edge),
                )
                // The thumb, read from the spring as it draws: a choice redraws the control, nothing is laid out again.
                val segment = size.width / count
                val at = position.value
                val inset = THUMB_INSET.toPx()
                val left = (if (rtl) size.width - (at + 1) * segment else at * segment) + inset
                val width = segment - 2 * inset
                val height = trackHeight - 2 * inset
                drawRoundRect(thumb, Offset(left, top + inset), Size(width, height), CornerRadius(height / 2))
                val stroke = (if (strongEdge) STRONG_EDGE else Hairline).toPx()
                drawRoundRect(
                    thumbEdge,
                    Offset(left + stroke / 2, top + inset + stroke / 2),
                    Size(width - stroke, height - stroke),
                    CornerRadius((height - stroke) / 2),
                    style = Stroke(stroke),
                )
            },
    ) { measurables, constraints ->
        val widest = options.maxOfOrNull { measurer.measure(it, style.copy(fontWeight = FontWeight.Medium), maxLines = 1, softWrap = false).size.width } ?: 0
        val natural = max(MIN_SEGMENT.roundToPx(), widest + 2 * LABEL_PADDING.roundToPx()) * count
        val total = if (constraints.hasFixedWidth) constraints.maxWidth else natural.coerceIn(constraints.minWidth, constraints.maxWidth)
        val segment = total / count
        val labelHeight = measurables.maxOfOrNull { it.minIntrinsicHeight(segment) } ?: 0
        // 36 dp of track inside 48 dp of target; text larger than the track has room for makes both taller.
        val height = max(TARGET.roundToPx(), labelHeight + (TARGET - TRACK).roundToPx() + 2 * THUMB_INSET.roundToPx())
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeables = measurables.mapIndexed { index, measurable ->
            // The last segment takes the pixels the division leaves over.
            val width = if (index == measurables.lastIndex) total - segment * index else segment
            measurable.measure(Constraints.fixed(width, height))
        }
        layout(total, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, 0)
                x += it.width
            }
        }
    }
}

/** The track: the content colour at this opacity over the surface. */
private const val TRACK_ALPHA = 0.08f

/** On ink the thumb is the content colour at this opacity over the surface (on paper, the elevated surface). */
private const val THUMB_ALPHA_INK = 0.18f

/** The track's height, the touch target's around it, the thumb's inset inside the track. */
private val TRACK = 36.dp
private val TARGET = 48.dp
private val THUMB_INSET = 2.dp

/** The narrowest a segment is, and the room either side of its label. */
private val MIN_SEGMENT = 88.dp
private val LABEL_PADDING = 12.dp

/** The thumb's edge with high contrast text. */
private val STRONG_EDGE = 1.5.dp
