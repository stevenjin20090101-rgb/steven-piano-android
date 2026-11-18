// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.sort

import dev.stevenjin.stevenpiano.data.db.StatsCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The one-time backfill of the pieces' measures (M82: the pieces imported before them; new ones are measured as they are
 * imported). One piece at a time, oldest first: its file read and parsed and its notes measured ([measure]), then kept
 * ([keep]), and [GAP_MS] before the next, so a tablet that also serves the panel hardly notices. Never while a piece
 * plays or loads ([busy]: it waits for the player to rest before each piece). A piece that can't be read is kept as
 * [PieceStats.None] (it can't be played either), so it is not asked again. Resumable: what was kept is never measured
 * again, and a run cut short (the app closing) goes on from there next time. Call it off the main thread.
 */
class StatsBackfill(
    private val pending: suspend (limit: Int) -> List<StatsCandidate>,
    private val measure: suspend (StatsCandidate) -> PieceStats,
    private val keep: suspend (id: Long, stats: PieceStats) -> Unit,
    private val busy: Flow<Boolean>,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    /** Measures every piece still to be measured. Returns how many. */
    suspend fun run(): Int {
        var done = 0
        while (true) {
            val batch = pending(BATCH)
            if (batch.isEmpty()) break
            for (piece in batch) {
                busy.first { !it }
                val stats = try {
                    measure(piece)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Piece ${piece.id} couldn't be measured: ${e.message}")
                    PieceStats.None
                } catch (e: OutOfMemoryError) {
                    log("Piece ${piece.id} is too large to measure")
                    PieceStats.None
                }
                keep(piece.id, stats)
                done++
                pause(GAP_MS)
            }
        }
        if (done > 0) log("Measured $done pieces for the Library's sorts")
        return done
    }

    companion object {
        /** Between pieces. */
        const val GAP_MS = 40L

        private const val BATCH = 25
    }
}
