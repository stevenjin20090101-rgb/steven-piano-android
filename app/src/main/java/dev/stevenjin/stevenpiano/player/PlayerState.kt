// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.midi.KeySignature
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature

enum class PlaybackStatus { Stopped, Playing, Paused }

/** The ranges Now playing and the Piano tab offer. */
object PlaybackLimits {
    val TempoPct = 25..200
    val Transpose = -12..12
    val VelocityPct = 50..150
}

/**
 * The piece in the player: what Now playing shows. [notes] feed the note canvas; the score also
 * reads the file's [tempoMap], [barStartsMicros] and signatures.
 */
data class NowPlaying(
    val pieceId: Long,
    val title: String,
    val composer: String,
    val durationMicros: Long,
    val notes: NoteList,
    val tempoMap: TempoMap = TempoMap.constant(DEFAULT_PPQ),
    val barStartsMicros: LongArray = longArrayOf(0L),
    val keySignatures: List<KeySignature> = emptyList(),
    val timeSignatures: List<TimeSignature> = listOf(TimeSignature.Common),
) {
    private companion object {
        const val DEFAULT_PPQ = 480
    }
}

/**
 * The queue as the UI, the playback service and the media session see it: piece [ids] in playing
 * order with their entries' [uids], the [index] playing (-1 before anything has), and the two
 * modes. See [Queue].
 */
data class QueueSnapshot(
    val ids: List<Long> = emptyList(),
    val uids: List<Long> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
) {
    val currentUid: Long? get() = uids.getOrNull(index)

    /** The pieces after the current one, in playing order. */
    val upNextIds: List<Long> get() = ids.drop(index + 1)

    val upNextUids: List<Long> get() = uids.drop(index + 1)

    /** Next has somewhere to go (Repeat all wraps to the top). */
    val hasNext: Boolean get() = index + 1 < ids.size || (repeat == RepeatMode.ALL && ids.isNotEmpty())

    /**
     * Something plays once the current piece ends: a next piece, a wrap with Repeat all, or the
     * same piece with Repeat one. The playback service stays in the foreground through that pause.
     */
    val advancesAtEnd: Boolean get() = index in ids.indices && (repeat != RepeatMode.OFF || index + 1 < ids.size)

    /**
     * The entries the system media controls are given: at most [max] (a Binder limit), starting a
     * few before the current one.
     */
    fun window(max: Int = MEDIA_WINDOW): IntRange {
        if (ids.isEmpty()) return IntRange.EMPTY
        val first = (index - WINDOW_BEFORE).coerceIn(0, (ids.size - max).coerceAtLeast(0))
        return first until minOf(ids.size, first + max)
    }

    companion object {
        const val MEDIA_WINDOW = 50
        const val WINDOW_BEFORE = 5
    }
}

/** Everything about playback except the moving position, which [Player.positionMicrosNow] reads per frame. */
data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.Stopped,
    val piece: NowPlaying? = null,
    val loading: Boolean = false,
    val tempoPct: Int = 100,
    val transpose: Int = 0,
    val velocityPct: Int = 100,
    val fold: Boolean = true,
    val skipDrums: Boolean = true,
    val queue: QueueSnapshot = QueueSnapshot(),
    /** Why the last piece could not be played, in plain English; null when all is well. */
    val problem: String? = null,
) {
    val queueIndex: Int get() = queue.index

    val queueSize: Int get() = queue.ids.size
}
