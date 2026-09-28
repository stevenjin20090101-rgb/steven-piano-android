// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pieces shown at once, searched or recent. */
private const val PIECES_SHOWN = 30

/**
 * A search over the library's titles and composers, in a sheet (the schedule editor's Pieces, v1.6.2 —
 * M19; the compose sheet's In the manner of, v1.7 — M24); before one, the piece chosen ([chosenId]) and
 * the ones played or added last. A row chosen calls [onChoose] with its id.
 */
@Composable
fun PieceSearch(chosenId: Long?, onChoose: (Long) -> Unit) {
    val library = LocalContext.current.graph.library
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search titles and composers") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            { GlyphButton(R.drawable.ic_close, "Clear search") { query = "" } }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
            unfocusedBorderColor = LocalTertiary.current,
            cursorColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
    val chosen by produceState<PieceEntity?>(null, chosenId) {
        value = chosenId?.let { id -> withContext(Dispatchers.IO) { library.piece(id) } }
    }
    val pieces by produceState<List<PieceEntity>?>(null, query) {
        val flow = if (query.isBlank()) library.recent() else library.search(query)
        flow.collect { value = it.take(PIECES_SHOWN) }
    }
    val shown = pieces ?: return
    Column(Modifier.semantics { selectableGroup() }) {
        chosen?.takeIf { piece -> shown.none { it.id == piece.id } }?.let { piece ->
            SheetChoiceRow(piece.title, pieceMeta(piece), chosen = true) { onChoose(piece.id) }
        }
        if (query.isBlank() && shown.isNotEmpty()) Eyebrow("Played or added last", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        for (piece in shown) {
            SheetChoiceRow(piece.title, pieceMeta(piece), chosen = piece.id == chosenId) { onChoose(piece.id) }
        }
        if (shown.isEmpty()) SheetNote(if (query.isBlank()) "No pieces yet." else "Nothing matches that search.")
    }
}

/** "Claude Debussy · 5:12": a piece's composer (or "Unknown composer") and length. */
fun pieceMeta(piece: PieceEntity): String =
    listOf(piece.composerShort.ifBlank { "Unknown composer" }, Format.clockMillis(piece.durationMs)).joinToString(" · ")

/** One thing that can be chosen in a sheet: its name over what it is, a check when it is the one chosen. */
@Composable
fun SheetChoiceRow(title: String, meta: String?, chosen: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(chosen, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) Eyebrow(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false, maxLines = 1)
        }
        if (chosen) {
            Spacer(Modifier.width(16.dp))
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
    HairlineDivider(startInset = 16.dp)
}

/** A sheet's line where a list has nothing yet (in Body, the secondary colour). */
@Composable
fun SheetNote(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
