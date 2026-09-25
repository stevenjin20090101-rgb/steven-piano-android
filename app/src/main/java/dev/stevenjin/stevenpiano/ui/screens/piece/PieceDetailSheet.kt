// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piece

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.data.art.ArtKey
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.ArtworkCopy
import dev.stevenjin.stevenpiano.ui.components.ArtworkImage
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.RollCardImage
import dev.stevenjin.stevenpiano.ui.components.WikipediaLink
import dev.stevenjin.stevenpiano.ui.components.rememberArtworkRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The piece sheet ("About this piece", or a tap on the title on Now playing): a bottom sheet with
 * its drag handle; the art (the composer's portrait, else the piece's own roll card), the title,
 * the composer as an eyebrow, then the piece's Wikipedia extract when it has a page, otherwise the
 * composer's, a "From Wikipedia" link to where the text came from and the attribution line.
 * Opening it asks for the piece's notes (and the composer's, if never looked up) ahead of any
 * background fetch, when artwork may arrive by itself (the Piano tab's "Fetch artwork
 * automatically"); with that off, nothing is asked until the person taps "Fetch notes". Nothing
 * found: "No notes found for this piece."; offline with nothing kept: "Notes need an internet
 * connection."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PieceDetailSheet(pieceId: Long, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val piece by produceState<PieceEntity?>(null, pieceId) {
        value = try {
            graph.library.piece(pieceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {   // the library can't be read: the sheet stays empty rather than crash
            null
        }
    }
    val settings by graph.settings.collectAsStateWithLifecycle()
    var asked by remember(pieceId) { mutableStateOf(false) }
    val fetching = settings.fetchArtworkAutomatically || asked
    LaunchedEffect(piece, fetching) {
        val p = piece ?: return@LaunchedEffect
        if (!fetching) return@LaunchedEffect
        // Both go to the front of the queue; the piece's own notes, asked last, come first.
        if (p.composerKey.isNotEmpty()) graph.artwork.request(ArtKey.Composer(p.composerKey, p.composer), priority = true)
        graph.artwork.request(ArtKey.Piece(p.id, p.title, p.composer), priority = true)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        piece?.let { PieceNotes(it, sheetState, fetching, onFetch = { asked = true }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PieceNotes(piece: PieceEntity, sheetState: SheetState, fetching: Boolean, onFetch: () -> Unit) {
    val online by LocalContext.current.graph.artwork.online.collectAsStateWithLifecycle()
    val own = rememberArtworkRow(ArtworkEntity.forPiece(piece.id))
    val composer = rememberArtworkRow(ArtworkEntity.forComposer(piece.composerKey))
    // A fetch that never records anything (Wikimedia asked to wait) must not leave the sheet waiting.
    var patient by remember(piece.id) { mutableStateOf(true) }
    LaunchedEffect(piece.id, fetching) {
        if (!fetching) return@LaunchedEffect
        patient = true
        delay(WAIT_MS)
        patient = false
    }
    val notes = PieceNotesChoice.of(own, composer, online, waiting = patient, fetching = fetching)
    // The notes arrive after the sheet has opened at the height it had then: once it is up, it
    // settles again at its new height, so the text never runs off the bottom.
    LaunchedEffect(notes) {
        snapshotFlow { sheetState.isVisible }.first { it }
        sheetState.expand()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtworkImage(ArtworkEntity.forComposer(piece.composerKey), ArtSize.Full, Modifier.size(ART)) { RollCardImage(piece.id, it) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    piece.title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (piece.composer.isNotBlank()) Eyebrow(piece.composer, Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        when (notes) {
            is PieceNotesChoice.Text -> NotesText(notes)
            is PieceNotesChoice.Ask -> {
                notes.composer?.let { NotesText(it) }
                // Shifted by the button's own padding so its label lines up with the text.
                TextButton(onClick = onFetch, modifier = Modifier.padding(top = 4.dp).offset(x = (-12).dp)) { Text("Fetch notes") }
                Eyebrow(ArtworkCopy.TRANSPARENCY, Modifier.padding(top = 4.dp), uppercase = false)
            }
            PieceNotesChoice.Waiting -> ProgressHairline(null, Modifier.padding(vertical = 12.dp))
            PieceNotesChoice.None -> Message("No notes found for this piece.")
            PieceNotesChoice.Offline -> Message("Notes need an internet connection.")
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Wikipedia's text, where it came from, and its attribution. */
@Composable
private fun NotesText(notes: PieceNotesChoice.Text) {
    Text(notes.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    // Shifted by the button's own padding so its label lines up with the text.
    WikipediaLink(notes.sourceUrl, Modifier.padding(top = 4.dp).offset(x = (-12).dp))
    Eyebrow(ArtworkCopy.ATTRIBUTION, Modifier.padding(top = 4.dp), uppercase = false)
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What the sheet says, from the piece's and the composer's artwork rows and whether the device is online. */
sealed interface PieceNotesChoice {
    /** The piece's own extract when it has a page, else the composer's, with where it came from. */
    data class Text(val text: String, val sourceUrl: String?) : PieceNotesChoice

    /** Online, and the piece has not been looked up yet: the fetch is under way. */
    data object Waiting : PieceNotesChoice

    /**
     * Artwork is not fetched by itself, and the piece was never looked up: a "Fetch notes" button,
     * under the composer's text when that is kept already.
     */
    data class Ask(val composer: Text?) : PieceNotesChoice

    data object None : PieceNotesChoice

    data object Offline : PieceNotesChoice

    companion object {
        /**
         * [waiting]: the sheet still expects the piece's own fetch to finish. [fetching]: a fetch was
         * asked for, by the setting or by the person; without it nothing goes out until they ask.
         */
        fun of(piece: ArtworkEntity?, composer: ArtworkEntity?, online: Boolean, waiting: Boolean, fetching: Boolean = true): PieceNotesChoice {
            piece.textOrNull()?.let { return it }
            if (piece == null && !fetching && online) return Ask(composer.textOrNull())
            if (piece == null && online && waiting) return Waiting
            composer.textOrNull()?.let { return it }
            return if (piece == null && !online) Offline else None
        }

        private fun ArtworkEntity?.textOrNull(): Text? {
            val row = this?.takeIf { it.status == ArtworkStatus.OK } ?: return null
            val text = row.description?.takeIf { it.isNotBlank() } ?: return null
            return Text(text, row.sourceUrl)
        }
    }
}

private val ART = 112.dp

/** How long the sheet waits for the piece's own notes before settling for the composer's. */
private const val WAIT_MS = 12_000L
