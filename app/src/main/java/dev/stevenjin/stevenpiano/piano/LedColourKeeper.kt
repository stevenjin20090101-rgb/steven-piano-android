// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The strip's colour kept as the tablet chose it (M80), on a piano that says `!ledcolorproto=1` and lets this tablet write:
 * nothing at all on any other, so an older piano behaves as it always has.
 *
 * - **The tint** ([LedColour.plan]): with Album art, the loaded piece's cover colour ([CoverTint]) as `ledtint`, `off` for
 *   a grey cover or none; with Custom or Palette, `ledtint off`. It goes when the piece changes (as it loads, before its
 *   pause), at the connection, and when the choice changes, and only when the piano holds another: each value at most
 *   once a connection, so a refusal is never sent again and again. While a new cover is read the old tint stays.
 * - **The palette**: Album art and Custom need Reactive's Solid palette. `reactcolor 1` goes when the choice turns to one
 *   of them in Reactive, or when the strip turns Reactive with one of them chosen. A palette other than Solid found at
 *   the connection, or chosen elsewhere meanwhile (the web panel, the piano's own screen, a preset), is the piano's
 *   choice: the tablet follows it to Palette ([follow]), which also sends `ledtint off`.
 *
 * Event-driven only: it follows the piano's state, the choice and the loaded piece, and runs nothing on a clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedColourKeeper(
    private val piano: PianoSettingsRepository,
    /** The tablet's choice, from its settings. */
    private val source: Flow<LedColourSource>,
    /** The loaded piece's id, null with none. */
    private val piece: Flow<Long?>,
    /** Piece [id]'s cover colour ([CoverTint]), as it is read and whenever the cover changes. */
    private val coverOf: (id: Long) -> Flow<CoverTint>,
    /** Records the tablet's choice (the piano's own palette chosen elsewhere: [LedColourSource.PALETTE]). */
    private val follow: (LedColourSource) -> Unit,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val _cover = MutableStateFlow<CoverTint?>(CoverTint.None)

    /** The loaded piece's cover colour as the strip takes it; null while a new cover is read. The Lights page shows it. */
    val cover: StateFlow<CoverTint?> = _cover.asStateFlow()

    /** What this connection has seen and sent; null before the piano's first answer on it. */
    private var seen: Seen? = null

    private var started = false

    fun start() {
        if (started) return
        started = true
        val covers = piece.distinctUntilChanged().flatMapLatest { id ->
            flow {
                if (id == null) {
                    emit(CoverTint.None)
                } else {
                    emit(null)   // being read: the strip keeps what it shows
                    coverOf(id).collect { emit(it) }
                }
            }
        }.onEach { _cover.value = it }
        scope.launch {
            combine(piano.state, source.distinctUntilChanged(), covers) { state, chosen, cover -> Triple(state, chosen, cover) }
                .collect { (state, chosen, cover) -> keep(state, chosen, cover) }
        }
    }

    private fun keep(state: PianoState, chosen: LedColourSource, cover: CoverTint?) {
        val ready = state as? PianoState.Ready
        if (ready == null || !LedColour.supported(ready.facts)) {
            seen = null   // a new connection (or none) starts afresh
            return
        }
        if (!ready.lock.writable) return   // read only, or the key still being proved: nothing goes until it may
        val values = ready.values
        val mode = values[LedColour.MODE]?.trim()
        val palette = values[LedColour.PALETTE]?.trim()
        val before = seen
        var source = chosen
        var solid = false
        var followed = false
        if (mode == LedColour.REACTIVE && palette != LedColour.SOLID && source != LedColourSource.PALETTE) {
            val chosenNow = before != null && before.source != source
            val turnedReactive = before != null && before.mode != LedColour.REACTIVE
            if (chosenNow || turnedReactive) {
                solid = true
            } else {
                source = LedColourSource.PALETTE
                followed = true
            }
        }
        var tint: String? = null
        var sentTint = before?.sentTint
        if (cover != null || source != LedColourSource.ALBUM_ART) {   // a cover being read keeps the tint shown
            val wanted = LedColour.plan(source, mode, cover?.rgb).tint
            val holds = values[LedColour.TINT]?.trim()?.lowercase()
            if (wanted != holds && wanted != sentTint) tint = wanted
            if (wanted == holds || tint != null) sentTint = wanted
        }
        seen = Seen(source, mode, sentTint)   // first: what follows changes the state this reads
        if (solid) {
            log("The strip's colour needs the Solid palette: reactcolor ${LedColour.SOLID}")
            piano.set(LedColour.PALETTE, LedColour.SOLID)
        }
        if (followed) {
            log("The piano's palette is ${palette ?: "unknown"}: the strip's colour follows it (Palette)")
            follow(LedColourSource.PALETTE)
        }
        if (tint != null) {
            log("The strip's tint: $tint (${source.name.lowercase()})")
            piano.tint(tint)
        }
    }

    /** One connection's memory: the choice and the mode last seen, and the tint last sent (or found held). */
    private data class Seen(val source: LedColourSource, val mode: String?, val sentTint: String?)

    private companion object {
        const val TAG = "LedColour"
    }
}
