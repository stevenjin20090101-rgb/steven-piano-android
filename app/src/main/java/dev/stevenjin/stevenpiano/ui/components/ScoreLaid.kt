// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.score.ScoreLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What the score panel says when a piece is too large to lay out. */
internal const val SCORE_TOO_LARGE = "This score is too large to show."

/**
 * A layout for the piece already shown (transpose, folding, the fingering or the panel changed) waits
 * this long before it starts, so a burst of changes lays out once, for the last (the v1.3 delta audit,
 * L1). A new piece's first layout starts at once.
 */
internal const val RELAYOUT_SETTLE_MS = 150L

/**
 * A score's layout and the notes it was made for ([ScorePages]). [layout] is null when the piece was
 * too large to lay out: the panel then says [SCORE_TOO_LARGE].
 */
internal class Laid(val notes: NoteList, val layout: ScoreLayout?)

/**
 * This layout when it was made for [notes], else null (the v1.3 delta audit, P2). For a frame after a
 * change of piece the state still holds the last piece's layout while the notes are already the new
 * piece's: drawn together, the overlay would read one piece's notes through the other's heads.
 */
internal fun Laid?.madeFor(notes: NoteList): Laid? = this?.takeIf { it.notes === notes }

/**
 * Lays [notes] out with [layout] on [dispatcher] (the v1.3 delta audit, H1). The engine's budgets
 * bound what a crafted file costs, but whatever still gets through (the heap running out, an index a
 * file trips) must not take the app down, and an error in a composition's effect would: an
 * [OutOfMemoryError] or a [RuntimeException] gives a [Laid] without a layout, and the panel says the
 * score is too large. Cancellation (a newer layout replacing this one) passes through, even when the
 * replaced layout then fails: [withContext] hands back a failure as it is, cancelled or not, and a
 * replaced layout's failure must not overwrite the new one's panel. [layout] gets a checkpoint to
 * call between its passes: it throws once this layout is replaced, so the work stops there (L1).
 */
internal suspend fun layOut(notes: NoteList, dispatcher: CoroutineDispatcher, layout: (checkpoint: () -> Unit) -> ScoreLayout): Laid =
    try {
        withContext(dispatcher) {
            val context = coroutineContext
            Laid(notes, layout { context.ensureActive() })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        tooLarge(notes)
    } catch (e: RuntimeException) {
        tooLarge(notes)
    }

/** [notes] too large to lay out, unless this layout has been replaced meanwhile (then it is cancelled). */
private suspend fun tooLarge(notes: NoteList): Laid {
    currentCoroutineContext().ensureActive()
    return Laid(notes, null)
}
