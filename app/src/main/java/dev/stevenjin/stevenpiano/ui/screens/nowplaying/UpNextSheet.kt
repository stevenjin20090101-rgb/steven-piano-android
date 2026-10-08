// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.components.DragHandle
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassSheet
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.moved
import dev.stevenjin.stevenpiano.ui.components.rememberDragReorderState
import dev.stevenjin.stevenpiano.ui.components.reorderable
import dev.stevenjin.stevenpiano.ui.components.reorderedBy
import dev.stevenjin.stevenpiano.ui.screens.quiet.LocalQuietAsk

/**
 * Up next: a bottom sheet with its drag handle and swipe-away. The piece playing first, then the
 * pieces coming, each with a remove glyph and a drag handle; tapping one plays it; Clear empties
 * what is coming. The order here is the queue's alone: no playlist changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val vm = viewModel { UpNextViewModel(graph.player, graph.library) }
    val ask = LocalQuietAsk.current
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // The order on screen while a drag is under way and until the queue has caught up with it.
    var dragged by remember { mutableStateOf<List<Long>?>(null) }
    val rows = dragged?.let { uids -> reorderedBy(state.upNext, uids) { it.uid } } ?: state.upNext
    val drag = rememberDragReorderState(
        listState,
        canMoveTo = { it is Long },
        onMove = { from, to ->
            val uids = moved(dragged ?: state.upNext.map { it.uid }, from as Long, to as Long)
            if (uids != null) dragged = uids
            uids != null
        },
        onDrop = { key -> dragged?.let { uids -> vm.move(key as Long, uids.indexOf(key)) } },
    )
    LaunchedEffect(state.upNext, drag.draggingKey) {
        if (drag.draggingKey == null && dragged == state.upNext.map { it.uid }) dragged = null
    }

    GlassSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Up next",
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (rows.isNotEmpty()) TextButton(onClick = vm::clear) { Text("Clear") }
        }
        LazyColumn(state = listState) {
            state.current?.let { current ->
                item(key = "now-label") {
                    Eyebrow("Now playing", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { heading() })
                }
                item(key = "now") { QueueRowView(current) }
                item(key = "now-rule") { HairlineDivider() }
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing up next.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(rows, key = { _, row -> row.uid }) { index, row ->
                QueueRowView(
                    row,
                    Modifier.reorderable(drag, row.uid, this),
                    onClick = { ask { vm.skipTo(row.uid) } },   // during a quiet time it asks first (v1.20 — M54)
                ) {
                    GlyphButton(R.drawable.ic_close, "Remove from queue") { vm.remove(row.uid) }
                    DragHandle(
                        drag,
                        row.uid,
                        onMoveUp = if (index > 0) ({ vm.move(row.uid, index - 1) }) else null,
                        onMoveDown = if (index < rows.lastIndex) ({ vm.move(row.uid, index + 1) }) else null,
                    )
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Title over "Surname · m:ss", 56 dp or more; tap plays it when [onClick] is given; [trailing] holds the row's glyphs. */
@Composable
private fun QueueRowView(row: QueueRow, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable(onClickLabel = "Play", onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.meta.isNotEmpty()) Eyebrow(row.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false, maxLines = 1)
        }
        trailing()
    }
}
