// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.record

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.Locale

/** Recording as the Keys tab shows it (v1.11 — M29). */
sealed interface RecordingState {
    /** No take. */
    data object Idle : RecordingState

    /** A take runs (since [startedAtNanos], `System.nanoTime`'s clock, for the elapsed time). */
    data class Recording(val startedAtNanos: Long) : RecordingState

    /** The take is being saved. */
    data object Saving : RecordingState

    /** Saved and waiting for Keep or Discard: the sheet asks. */
    data class Saved(val recording: SavedRecording, val ended: TakeEnd) : RecordingState

    /** The take held no note: nothing was saved ("Nothing was played." for a moment). */
    data object Empty : RecordingState

    /** The library would not take it (its file stays for the next start). */
    data object Failed : RecordingState
}

/**
 * The take running, from the Record control to the sheet (v1.11 — M29): [start] begins one on the [recorder];
 * once a second it looks whether the take should end by itself ([Recorder.due]: an hour, 200,000 events, five
 * minutes of silence); [stop] (the control, the app leaving the foreground) ends it and saves it off the main
 * thread through [pieces], then says what came of it ([state]); [answered] puts the sheet away. Main thread.
 */
class RecordingSession(
    private val recorder: Recorder,
    private val pieces: RecordingPieces,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var watch: Job? = null
    private var startedAt: ZonedDateTime? = null

    /** Whether a take runs. */
    val recording: Boolean get() = recorder.recording

    /** The Record control: a take begins (a sheet still asking about the last one goes away; that one stays undecided). */
    fun start() {
        if (!recorder.start()) return
        startedAt = now()
        _state.value = RecordingState.Recording(nanoTime())
        log("Recording: a take started")
        watch?.cancel()
        watch = scope.launch {
            while (true) {
                delay(WATCH_MS)
                val due = recorder.due() ?: continue
                stop(due)
                break
            }
        }
    }

    /** The take ends ([ended] says why) and is saved; a take with no note is none. */
    fun stop(ended: TakeEnd = TakeEnd.Stopped) {
        watch?.cancel()
        watch = null
        val at = startedAt ?: now()
        val take = recorder.stop(ended) ?: run {
            if (_state.value is RecordingState.Recording) empty()
            return
        }
        _state.value = RecordingState.Saving
        scope.launch {
            val saved = try {
                withContext(io) { pieces.save(take, at) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Recording: the take couldn't be saved (${e.javaClass.simpleName})")
                null
            }
            _state.value = if (saved != null) RecordingState.Saved(saved, ended) else RecordingState.Failed
        }
    }

    /** The sheet was answered (Keep, Discard, Listen, Done) or put away: back to no take. */
    fun answered() {
        if (_state.value !is RecordingState.Recording && _state.value != RecordingState.Saving) _state.value = RecordingState.Idle
    }

    /** At start: takes a crash left behind go into the library. */
    suspend fun recoverPending() = withContext(io) {
        try {
            pieces.recoverPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Recording: takes left by a crash couldn't be read (${e.javaClass.simpleName})")
        }
    }

    /** "Nothing was played." for a moment, then nothing. */
    private fun empty() {
        _state.value = RecordingState.Empty
        log("Recording: the take held no note; nothing saved")
        scope.launch {
            delay(EMPTY_SHOWN_MS)
            if (_state.value == RecordingState.Empty) _state.value = RecordingState.Idle
        }
    }

    companion object {
        private const val WATCH_MS = 1_000L

        /** How long "Nothing was played." shows. */
        const val EMPTY_SHOWN_MS = 3_000L

        /** "0:42", "12:05", "1:00:00": a take's length in whole seconds, tabular. */
        fun clock(nanos: Long): String {
            val seconds = (nanos.coerceAtLeast(0L) / 1_000_000_000L)
            val h = seconds / 3600
            val m = seconds / 60 % 60
            val s = seconds % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
