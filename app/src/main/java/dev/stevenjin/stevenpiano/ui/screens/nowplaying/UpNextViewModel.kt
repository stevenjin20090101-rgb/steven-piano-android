// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.db.PieceSummary
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.QueueSnapshot
import dev.stevenjin.stevenpiano.ui.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** One entry of the Up next sheet: its queue [uid], and the piece's title over "Surname · m:ss". */
data class QueueRow(val uid: Long, val pieceId: Long, val title: String, val meta: String)

/** The piece playing, then the ones coming, in playing order. */
data class UpNextState(val current: QueueRow? = null, val upNext: List<QueueRow> = emptyList())

/**
 * The Up next sheet: the player's queue with each piece named from the library, and the sheet's
 * edits, which go straight to the player (they never touch a playlist).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpNextViewModel(private val player: Player, private val library: LibraryRepository) : ViewModel() {
    val state: StateFlow<UpNextState> = player.state
        .map { it.queue }
        .distinctUntilChanged()
        .mapLatest(::rows)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UpNextState())

    fun remove(uid: Long) = player.removeFromQueue(uid)

    /** Entry [uid] to place [toIndex] among the pieces up next. */
    fun move(uid: Long, toIndex: Int) = player.moveInQueue(uid, toIndex)

    fun clear() = player.clearUpNext()

    fun skipTo(uid: Long) = player.skipToQueueEntry(uid)

    private suspend fun rows(queue: QueueSnapshot): UpNextState {
        val found = library.summaries(queue.ids)
        fun row(i: Int) = row(queue.uids[i], queue.ids[i], found[queue.ids[i]])
        val current = if (queue.index in queue.ids.indices) row(queue.index) else null
        return UpNextState(current, (queue.index + 1 until queue.ids.size).map(::row))
    }

    private fun row(uid: Long, pieceId: Long, piece: PieceSummary?): QueueRow = if (piece == null) {
        QueueRow(uid, pieceId, "No longer in the library", "")
    } else {
        QueueRow(uid, pieceId, piece.title, listOf(piece.composerShort, Format.clockMillis(piece.durationMs)).filter { it.isNotBlank() }.joinToString(" · "))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
