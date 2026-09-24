// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

/** The list a piece was started from; Next, Previous and auto-advance move within it. */
data class Playlist(val pieceIds: List<Long>, val index: Int) {
    val current: Long? get() = pieceIds.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < pieceIds.size
    val hasPrevious: Boolean get() = index > 0

    fun next(): Playlist = if (hasNext) copy(index = index + 1) else this
    fun previous(): Playlist = if (hasPrevious) copy(index = index - 1) else this

    /** Previous restarts the piece when more than 3 s in, or when there is nothing before it. */
    fun previousRestarts(positionMicros: Long): Boolean = positionMicros > RESTART_AFTER_MICROS || !hasPrevious

    companion object {
        const val RESTART_AFTER_MICROS = 3_000_000L
        val Empty = Playlist(emptyList(), -1)

        /** [queue] positioned at [pieceId]; just that piece when the queue does not hold it. */
        fun startingAt(pieceId: Long, queue: List<Long>): Playlist {
            val index = queue.indexOf(pieceId)
            return if (index >= 0) Playlist(queue, index) else Playlist(listOf(pieceId), 0)
        }
    }
}
