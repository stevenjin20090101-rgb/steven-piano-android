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
import dev.stevenjin.stevenpiano.score.ChordTrack

enum class PlaybackStatus { Stopped, Playing, Paused }

/** The ranges Now playing and the Piano tab offer. */
object PlaybackLimits {
    val TempoPct = 25..200
    val Transpose = -12..12
    val VelocityPct = 50..150

    /** The pause before each piece, in milliseconds (Piano › Playback, "Pause before each piece"). */
    val PreRollMs = 0..5_000

    /** The quietest note's velocity (Piano › Playback, "Quietest note", v1.16 — M44). */
    val VelocityFloor = 1..60

    /** The re-strike time set by hand, in milliseconds, in tens ([Performance.AUTO], 0, is Auto). */
    val RestrikeMs = 60..250
    const val RESTRIKE_STEP_MS = 10

    /** [ms] as the re-strike time takes it: Auto at 0 or below, else in tens within [RestrikeMs]. */
    fun restrikeMs(ms: Int): Int =
        if (ms <= Performance.AUTO) Performance.AUTO else ((ms + RESTRIKE_STEP_MS / 2) / RESTRIKE_STEP_MS * RESTRIKE_STEP_MS).coerceIn(RestrikeMs)
}

/**
 * The piece in the player: what Now playing shows. [composerKey] (the library's, "" when the composer
 * is unknown) finds the composer's portrait for the mini player and the now-playing panel. [notes]
 * feed the note canvas; the score also
 * reads the file's [tempoMap], [barStartsMicros] and signatures. [hands] (`score.Hands`, one per
 * note; empty until known) are worked out once per piece, off the main thread, before it is shown;
 * the suggested [fingers] (`score.Fingering`) with them, on the keys the piano plays at
 * [fingersTranspose] and [fingersFold], and again whenever those change; and the [chords]
 * (`score.Chords`), in the file's own pitches (they are spelled transposed as they are shown).
 */
data class NowPlaying(
    val pieceId: Long,
    val title: String,
    val composer: String,
    val durationMicros: Long,
    val notes: NoteList,
    val composerKey: String = "",
    val tempoMap: TempoMap = TempoMap.constant(DEFAULT_PPQ),
    val barStartsMicros: LongArray = longArrayOf(0L),
    val keySignatures: List<KeySignature> = emptyList(),
    val timeSignatures: List<TimeSignature> = listOf(TimeSignature.Common),
    val hands: ByteArray = ByteArray(0),
    val fingers: ByteArray = ByteArray(0),
    val fingersTranspose: Int = 0,
    val fingersFold: Boolean = true,
    val chords: ChordTrack = ChordTrack.Empty,
) {
    /** The hands, when they have been worked out for these notes: one per note. */
    val handsOrNull: ByteArray? get() = hands.takeIf { it.size == notes.size && it.isNotEmpty() }

    /** The fingering for the keys played at [transpose] and [fold], or null while it is being worked out again. */
    fun fingersFor(transpose: Int, fold: Boolean): ByteArray? =
        fingers.takeIf { it.size == notes.size && it.isNotEmpty() && fingersTranspose == transpose && fingersFold == fold }

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
    /**
     * The channel playing ("calm"; DESIGN.md › v1.5 — M17), or null. Set by [Player.playAll] with a
     * channel, kept by Next, Previous, Play next and Add to queue, and gone when anything else takes
     * over the queue or playback stops (see [Player]).
     */
    val channel: String? = null,
) {
    val queueIndex: Int get() = queue.index

    val queueSize: Int get() = queue.ids.size
}
