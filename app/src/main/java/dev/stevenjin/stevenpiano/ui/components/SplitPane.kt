// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How Now playing's score and notes share a wide frame (DESIGN.md › v1.12): [Stacked], the score over
 * the notes (medium widths), or [SideBySide], the score at the start (expanded widths). Each has the
 * score's share it starts at and is reset to ([defaultShare]) and the two panes' minimums along it, in dp:
 * a stacked score keeps one system (200 dp), a stacked roll its strip and some notes (165 dp), and side
 * by side each keeps 240 dp.
 */
enum class SplitAxis(val defaultShare: Float, val firstMinDp: Float, val secondMinDp: Float) {
    Stacked(1f / 3f, 200f, 165f),
    SideBySide(0.5f, 240f, 240f),
}

/**
 * The divider's arithmetic (DESIGN.md › v1.12), pure: along an axis [length] dp long the two panes share
 * [length] less the [gap]; a share is the first pane's part of that, 0 (the first hidden) to 1 (the second
 * hidden). Between those a pane keeps its minimum ([firstMin], [secondMin]); dragged [HIDE_PAST] dp past
 * it, it hides, and dragged back as far it shows again at its minimum. A drag within [SNAP_WITHIN] dp of
 * a third, a half or two thirds ([STOPS]) rests on it. Where both minimums do not fit, a visible split
 * sits where the minimums' ratio puts it.
 */
internal class SplitRules(val length: Float, val firstMin: Float, val secondMin: Float, val gap: Float = GAP_DP) {
    /** The room the two panes share, in dp. */
    val room: Float = max(0f, length - gap)

    /** The least and the most a visible split can be: each pane at its minimum. */
    val lowest: Float
    val highest: Float

    init {
        val low = if (room > 0f) firstMin / room else 0.5f
        val high = if (room > 0f) 1f - secondMin / room else 0.5f
        if (low <= high) {
            lowest = low.coerceIn(0f, 1f)
            highest = high.coerceIn(0f, 1f)
        } else {
            val mid = firstMin / (firstMin + secondMin)
            lowest = mid
            highest = mid
        }
    }

    /** The stops a drag rests on and the Page keys move between, each kept to the panes' minimums. */
    val stops: List<Float> = STOPS.map { it.coerceIn(lowest, highest) }.distinct()

    /** The share to draw for [share]: 0 and 1 as they are (a hidden pane), anything else kept to the minimums. */
    fun shown(share: Float): Float = when {
        share <= 0f -> 0f
        share >= 1f -> 1f
        else -> share.coerceIn(lowest, highest)
    }

    /**
     * The share while a finger holds the divider where the first pane would be [firstSize] dp: hidden when
     * a pane would be [HIDE_PAST] dp under its minimum, resting on a stop within [SNAP_WITHIN] dp, else
     * as asked, kept to the minimums.
     */
    fun dragged(firstSize: Float): Float {
        if (firstSize < firstMin - HIDE_PAST) return 0f
        if (room - firstSize < secondMin - HIDE_PAST) return 1f
        val stop = stops.minByOrNull { abs(it * room - firstSize) }
        if (stop != null && abs(stop * room - firstSize) <= SNAP_WITHIN) return stop
        return if (room > 0f) (firstSize / room).coerceIn(lowest, highest) else lowest
    }

    /** [share] moved by [step] (the arrow keys' 2 %): kept to the minimums; a hidden pane shows at its minimum when moved toward it. */
    fun nudged(share: Float, step: Float): Float = when {
        share <= 0f && step <= 0f -> 0f
        share >= 1f && step >= 0f -> 1f
        else -> (shown(share) + step).coerceIn(lowest, highest)
    }

    /** What an accessibility service's adjustment to [target] gives: a visible split, kept to the minimums. */
    fun settled(target: Float): Float = target.coerceIn(lowest, highest)

    /** The next stop from [share] toward the end ([forward]) or the start; [share] itself where there is none that way. */
    fun nextStop(share: Float, forward: Boolean): Float =
        if (forward) stops.firstOrNull { it > share + EPSILON } ?: share else stops.lastOrNull { it < share - EPSILON } ?: share

    /** Whether going from [before] to [after] while dragging gives a tick: resting on a stop, or a pane hiding. */
    fun ticks(before: Float, after: Float): Boolean =
        after != before && (after <= 0f || after >= 1f || stops.any { it == after })

    companion object {
        /** The gap between the two panes, the divider's line at its middle. */
        const val GAP_DP = 8f

        /** A pane dragged this far under its minimum hides. */
        const val HIDE_PAST = 56f

        /** A drag this close to a stop rests on it. */
        const val SNAP_WITHIN = 12f

        /** The arrow keys' step. */
        const val STEP = 0.02f

        /** A third, a half, two thirds. */
        val STOPS = listOf(1f / 3f, 0.5f, 2f / 3f)

        private const val EPSILON = 0.0005f

        /** What TalkBack says of the divider's position: "sheet music 33 percent", or which pane is hidden. */
        fun stateText(share: Float): String = when {
            share <= 0f -> "sheet music hidden"
            share >= 1f -> "notes hidden"
            else -> "sheet music ${(share * 100).roundToInt()} percent"
        }
    }
}

/** What TalkBack calls the divider. */
internal const val SPLIT_DIVIDER_LABEL = "Sheet music and notes divider"

/** The divider's touch target across the gap, and its grabber. */
private val DividerTarget = 48.dp
private val GrabberLength = 36.dp
private val GrabberThickness = 4.dp

private const val FIRST = "first"
private const val SECOND = "second"
private const val DIVIDER = "divider"

/**
 * Two panes along [axis] with a divider between them (DESIGN.md › v1.12; HIG `split-views.md`): [first]
 * (the score) and [second] (the notes) share the room as [share] says (the committed share: 0 hides the
 * first, 1 the second), with an 8 dp gap holding a 1 dp hairline and a 36 × 4 dp grabber, and a 48 dp
 * touch target centred on the gap. A drag moves it ([SplitRules.dragged]: the stops, the minimums, a pane
 * hidden past its minimum), with a light tick on resting on a stop and on hiding; the panes follow the
 * finger without recomposing (the share is read in layout), and only the release calls [onShare]. A
 * hidden pane leaves the divider at its edge, to drag it back. A double tap resets to the axis's default.
 * TalkBack reads "Sheet music and notes divider, sheet music 33 percent" and adjusts it as a slider, with
 * the actions Reset, Show sheet music only and Show notes only; with a keyboard the arrows along the axis
 * move it 2 %, Page Up and Page Down between the stops, Home and End hide a pane. The panes get a modifier
 * filling their slot and whether the other pane is hidden ([first]'s keyboard strip then stays under it).
 * The divider is content: no colour, no glass.
 */
@Composable
fun SplitPane(
    axis: SplitAxis,
    share: Float,
    onShare: (Float) -> Unit,
    first: @Composable (Modifier, alone: Boolean) -> Unit,
    second: @Composable (Modifier, alone: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stacked = axis == SplitAxis.Stacked
    val view = LocalView.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val commit = rememberUpdatedState(onShare)
    val committed = rememberUpdatedState(share)
    val drag = remember { SplitDrag() }
    // Where a finger, a key or an action has put the divider, until the committed share catches up; NaN: none.
    var held by remember { mutableFloatStateOf(Float.NaN) }
    var dragging by remember { mutableStateOf(false) }
    // The share drawn. Read in layout and drawing only, so a drag moves the panes without recomposing them.
    val current = { if (held.isNaN()) committed.value else held }
    val firstShown by remember { derivedStateOf { current() > 0f } }
    val secondShown by remember { derivedStateOf { current() < 1f } }
    LaunchedEffect(share) { if (!drag.active) held = Float.NaN }
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val line = LocalHairline.current
    val grabber = if (focused || dragging) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val rules = { SplitRules(drag.lengthDp, axis.firstMinDp, axis.secondMinDp) }

    /** The divider at [next], kept: drawn at once, and written unless it is where it was. */
    fun set(next: Float) {
        if (next == committed.value) {
            held = Float.NaN
        } else {
            held = next
            commit.value(next)
        }
    }

    Layout(
        modifier = modifier,
        content = {
            if (firstShown) Box(Modifier.layoutId(FIRST)) { first(Modifier.fillMaxSize(), !secondShown) }
            if (secondShown) Box(Modifier.layoutId(SECOND)) { second(Modifier.fillMaxSize(), !firstShown) }
            Box(
                Modifier
                    .layoutId(DIVIDER)
                    .semantics {
                        contentDescription = SPLIT_DIVIDER_LABEL
                        stateDescription = SplitRules.stateText(share)
                        progressBarRangeInfo = ProgressBarRangeInfo(share.coerceIn(0f, 1f), 0f..1f)
                        setProgress { target ->
                            set(rules().settled(target))
                            true
                        }
                        customActions = listOf(
                            CustomAccessibilityAction("Reset") { set(axis.defaultShare); true },
                            CustomAccessibilityAction("Show sheet music only") { set(1f); true },
                            CustomAccessibilityAction("Show notes only") { set(0f); true },
                        )
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val r = rules()
                        val now = current()
                        val back = if (stacked) Key.DirectionUp else if (rtl) Key.DirectionRight else Key.DirectionLeft
                        val ahead = if (stacked) Key.DirectionDown else if (rtl) Key.DirectionLeft else Key.DirectionRight
                        val next = when (event.key) {
                            back -> r.nudged(now, -SplitRules.STEP)
                            ahead -> r.nudged(now, SplitRules.STEP)
                            Key.PageUp -> r.nextStop(r.shown(now), forward = false)
                            Key.PageDown -> r.nextStop(r.shown(now), forward = true)
                            Key.MoveHome -> 0f
                            Key.MoveEnd -> 1f
                            else -> return@onKeyEvent false
                        }
                        set(next)
                        true
                    }
                    .focusable(interactionSource = focus)
                    .pointerInput(axis, rtl) {
                        val start = { _: Offset ->
                            drag.active = true
                            dragging = true
                            drag.firstSize = rules().room * current()
                            held = current()
                        }
                        val end = {
                            drag.active = false
                            dragging = false
                            set(current())
                        }
                        val move = { delta: Float ->
                            drag.firstSize += delta / density
                            val r = rules()
                            val next = r.dragged(drag.firstSize)
                            if (r.ticks(held, next)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            held = next
                        }
                        if (stacked) {
                            detectVerticalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = end) { change, dy ->
                                change.consume()
                                move(dy)
                            }
                        } else {
                            detectHorizontalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = end) { change, dx ->
                                change.consume()
                                move(if (rtl) -dx else dx)
                            }
                        }
                    }
                    .pointerInput(axis) { detectTapGestures(onDoubleTap = { set(axis.defaultShare) }) }
                    .drawBehind {
                        // The line and the grabber sit at the gap's middle: the target's own middle, or by the edge for a hidden pane.
                        val at = current()
                        val along = if (stacked) size.height else size.width
                        val half = SplitRules.GAP_DP.dp.toPx() / 2
                        var middle = when {
                            at <= 0f -> half
                            at >= 1f -> along - half
                            else -> along / 2
                        }
                        if (!stacked && layoutDirection == LayoutDirection.Rtl) middle = along - middle
                        val hair = Hairline.toPx()
                        val long = GrabberLength.toPx()
                        val thick = GrabberThickness.toPx()
                        val round = CornerRadius(thick / 2)
                        if (stacked) {
                            drawRect(line, Offset(0f, middle - hair / 2), Size(size.width, hair))
                            drawRoundRect(grabber, Offset((size.width - long) / 2, middle - thick / 2), Size(long, thick), round)
                        } else {
                            drawRect(line, Offset(middle - hair / 2, 0f), Size(hair, size.height))
                            drawRoundRect(grabber, Offset(middle - thick / 2, (size.height - long) / 2), Size(thick, long), round)
                        }
                    },
            )
        },
    ) { measurables, constraints ->
        val length = if (stacked) constraints.maxHeight else constraints.maxWidth
        val cross = if (stacked) constraints.maxWidth else constraints.maxHeight
        drag.lengthDp = length / density
        val gap = SplitRules.GAP_DP.dp.roundToPx()
        val room = max(0, length - gap)
        val firstPx = (room * rules().shown(current())).roundToInt().coerceIn(0, room)
        val secondPx = room - firstPx
        fun along(size: Int) = if (stacked) Constraints.fixed(cross, size) else Constraints.fixed(size, cross)
        val firstPlaceable = measurables.firstOrNull { it.layoutId == FIRST }?.measure(along(firstPx))
        val secondPlaceable = measurables.firstOrNull { it.layoutId == SECOND }?.measure(along(secondPx))
        val target = DividerTarget.roundToPx().coerceAtMost(length)
        val dividerAt = (firstPx + gap / 2 - target / 2).coerceIn(0, max(0, length - target))
        val divider = measurables.first { it.layoutId == DIVIDER }.measure(along(target))
        layout(if (stacked) cross else length, if (stacked) length else cross) {
            firstPlaceable?.placeRelative(0, 0)
            if (stacked) {
                secondPlaceable?.placeRelative(0, firstPx + gap)
                divider.placeRelative(0, dividerAt)
            } else {
                secondPlaceable?.placeRelative(firstPx + gap, 0)
                divider.placeRelative(dividerAt, 0)
            }
        }
    }
}

/** Where a finger has the divider (the first pane's size it asks for, in dp) while a drag is on, and the axis's length in dp from the last layout. */
private class SplitDrag {
    var active = false
    var firstSize = 0f
    var lengthDp = 0f
}
