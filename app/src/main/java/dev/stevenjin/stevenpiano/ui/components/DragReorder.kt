// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Near the top or bottom of the list a dragged row scrolls it, up to this far per frame. */
private val AUTO_SCROLL_EDGE = 64.dp
private val AUTO_SCROLL_MAX_STEP = 12.dp
private val LIFT_ELEVATION = 6.dp
private const val SETTLE_MS = 150

/** One item of a lazy list along its axis, in `LazyListState.layoutInfo`'s coordinates. */
data class ItemSpan(val index: Int, val key: Any, val offset: Int, val size: Int)

/**
 * Where the dragged item belongs once it has been moved by [translation]: the index of the
 * farthest item among [items] whose middle it has passed in the direction it moves (its bottom
 * below that middle going down, its top above it going up) and that [canMoveTo] lets it take the
 * place of; null when it stays where it is. Every value is a `layoutInfo` offset, so nothing is
 * compared with a screen position. Passing middles rather than edges keeps rows of different
 * heights from swapping back and forth. Pure: unit-tested.
 */
fun targetIndex(dragged: ItemSpan, translation: Float, items: List<ItemSpan>, canMoveTo: (key: Any) -> Boolean): Int? {
    if (translation == 0f) return null
    val top = dragged.offset + translation
    val bottom = top + dragged.size
    var target: Int? = null
    for (item in items) {
        if (item.index == dragged.index || !canMoveTo(item.key)) continue
        val middle = item.offset + item.size / 2f
        if (translation > 0f && item.index > dragged.index && middle < bottom) {
            if (target == null || item.index > target) target = item.index
        } else if (translation < 0f && item.index < dragged.index && middle > top) {
            if (target == null || item.index < target) target = item.index
        }
    }
    return target
}

/** [order] with [from] moved to the place of [to]; null when either is not in it. Pure: unit-tested. */
fun <K> moved(order: List<K>, from: K, to: K): List<K>? {
    val at = order.indexOf(from)
    val place = order.indexOf(to)
    if (at < 0 || place < 0) return null
    return order.toMutableList().apply { add(place, removeAt(at)) }
}

/** [items] in the order of [keys] (as a drag left them); items [keys] does not name keep their order after them. Pure: unit-tested. */
fun <T, K> reorderedBy(items: List<T>, keys: List<K>, keyOf: (T) -> K): List<T> {
    val byKey = items.associateBy(keyOf)
    val ordered = keys.mapNotNull { byKey[it] }
    if (ordered.size == items.size) return ordered
    val named = keys.toHashSet()
    return ordered + items.filter { keyOf(it) !in named }
}

/**
 * A lazy list whose rows are reordered by dragging their handles ([Modifier.dragHandle]). The
 * dragged row follows the finger above the others ([Modifier.reorderable]) and takes each place it
 * passes through [onMove] at once, so the list the caller shows is always the order on screen;
 * [onDrop] then commits it. Near either end of the list the drag scrolls it. Rows are found by
 * their stable keys, never by index.
 */
@Stable
class DragReorderState internal constructor(private val listState: LazyListState, private val scope: CoroutineScope) {
    internal var canMoveTo: (key: Any) -> Boolean = { false }
    internal var onMove: (from: Any, to: Any) -> Boolean = { _, _ -> false }
    internal var onDrop: (key: Any) -> Unit = {}
    internal var reducedMotion = false
    internal var edgePx = 0f
    internal var maxStepPx = 0f
    internal var liftColor = Color.Unspecified
    internal var liftOutline = Color.Unspecified

    /** The key of the row being dragged (or settling into its place), null otherwise. */
    var draggingKey: Any? by mutableStateOf(null)
        private set

    /** How far the dragged row is drawn from its place in the list. */
    var translation: Float by mutableFloatStateOf(0f)
        private set

    private var awaitingIndex: Int? = null
    private var scrollJob: Job? = null
    private var settleJob: Job? = null

    fun isDragging(key: Any): Boolean = draggingKey == key

    internal fun start(key: Any) {
        settleJob?.cancel()
        draggingKey = key
        translation = 0f
        awaitingIndex = null
        scrollJob?.cancel()
        scrollJob = scope.launch { autoScroll() }
    }

    internal fun dragBy(delta: Float) {
        if (draggingKey == null) return
        translation += delta
        swapIfPassed()
    }

    internal fun end() {
        val key = draggingKey ?: return
        scrollJob?.cancel()
        onDrop(key)
        settleJob = scope.launch {
            if (!reducedMotion) animate(translation, 0f, animationSpec = tween(SETTLE_MS)) { value, _ -> translation = value }
            draggingKey = null
            translation = 0f
        }
    }

    private fun swapIfPassed() {
        val key = draggingKey ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == key } ?: return
        awaitingIndex?.let { expected ->
            if (dragged.index != expected) return   // the last move has not been laid out yet
            awaitingIndex = null
        }
        val span = ItemSpan(dragged.index, dragged.key, dragged.offset, dragged.size)
        val target = targetIndex(span, translation, visible.map { ItemSpan(it.index, it.key, it.offset, it.size) }, canMoveTo) ?: return
        val to = visible.first { it.index == target }
        // Moving the first visible row would make the list follow it; keep the scroll where it is.
        val first = listState.firstVisibleItemIndex
        if (dragged.index == first || target == first) listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        if (!onMove(key, to.key)) return
        // Its new place: its bottom where the target's bottom was going down, its top where the
        // target's top was going up. The row stays under the finger.
        val newOffset = if (target > dragged.index) to.offset + to.size - dragged.size else to.offset
        translation += dragged.offset - newOffset
        awaitingIndex = target
    }

    private suspend fun autoScroll() {
        while (true) {
            withFrameNanos { }
            val key = draggingKey ?: return
            val info = listState.layoutInfo
            val dragged = info.visibleItemsInfo.firstOrNull { it.key == key } ?: continue
            val top = dragged.offset + translation
            val bottom = top + dragged.size
            val lowEdge = info.viewportEndOffset - edgePx
            val highEdge = info.viewportStartOffset + edgePx
            val step = when {
                bottom > lowEdge -> maxStepPx * ((bottom - lowEdge) / edgePx).coerceIn(0f, 1f)
                top < highEdge -> -maxStepPx * ((highEdge - top) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (step != 0f) {
                // The row moves with the list; the finger does not.
                translation += listState.scrollBy(step)
                swapIfPassed()
            }
        }
    }
}

/**
 * A [DragReorderState] for [listState]. [canMoveTo] says which rows a dragged one may pass (the
 * reorderable ones, not headers); [onMove] moves row [from] to the place of row [to] in the
 * caller's list and says whether it could; [onDrop] commits the order when the finger lifts.
 */
@Composable
fun rememberDragReorderState(
    listState: LazyListState,
    canMoveTo: (key: Any) -> Boolean,
    onMove: (from: Any, to: Any) -> Boolean,
    onDrop: (key: Any) -> Unit,
): DragReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember(listState, scope) { DragReorderState(listState, scope) }
    state.canMoveTo = canMoveTo
    state.onMove = onMove
    state.onDrop = onDrop
    state.reducedMotion = rememberReducedMotion()
    state.edgePx = with(density) { AUTO_SCROLL_EDGE.toPx() }
    state.maxStepPx = with(density) { AUTO_SCROLL_MAX_STEP.toPx() }
    state.liftColor = MaterialTheme.colorScheme.surfaceVariant
    state.liftOutline = LocalHairline.current
    return state
}

/**
 * On a row's handle: dragging it moves row [key]. The handle takes every change of the drag for
 * itself, so neither the list nor a bottom sheet around it moves along.
 */
fun Modifier.dragHandle(state: DragReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectVerticalDragGestures(
        onDragStart = { state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
        onVerticalDrag = { change, amount ->
            change.consume()
            state.dragBy(amount)
        },
    )
}

/**
 * On a reorderable row in [item]'s lazy list: the dragged row lifts onto the elevated surface
 * with a hairline edge and follows the finger above the others; every other row slides out of
 * its way ([LazyItemScope.animateItem]; a cut when motion is reduced).
 */
fun Modifier.reorderable(state: DragReorderState, key: Any, item: LazyItemScope): Modifier =
    if (state.isDragging(key)) {
        zIndex(1f)
            .graphicsLayer { translationY = state.translation }
            .shadow(LIFT_ELEVATION, clip = false)
            .background(state.liftColor)
            .border(Hairline, state.liftOutline)
    } else {
        with(item) {
            this@reorderable.animateItem(fadeInSpec = null, placementSpec = if (state.reducedMotion) null else spring(), fadeOutSpec = null)
        }
    }

/**
 * A row's trailing 48 dp drag handle, "Reorder" to screen readers, whose Move up and Move down
 * actions ([onMoveUp], [onMoveDown]; null where the row cannot go further) do what a drag does.
 */
@Composable
fun DragHandle(state: DragReorderState, key: Any, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .dragHandle(state, key)
            .semantics {
                contentDescription = "Reorder"
                customActions = listOfNotNull(
                    onMoveUp?.let { up -> CustomAccessibilityAction("Move up") { up(); true } },
                    onMoveDown?.let { down -> CustomAccessibilityAction("Move down") { down(); true } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
