// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.data.db.GenerationEntity
import dev.stevenjin.stevenpiano.studio.AudioSource
import dev.stevenjin.stevenpiano.studio.ComposeOrder
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.ModelEntry
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.studio.StudioSupport
import dev.stevenjin.stevenpiano.studio.TurnRecord
import dev.stevenjin.stevenpiano.studio.compose.ComposeRequest
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.PreviewRoll
import dev.stevenjin.stevenpiano.studio.style.PreviousTurn
import dev.stevenjin.stevenpiano.studio.style.SeedAsk
import dev.stevenjin.stevenpiano.studio.style.StyleLibrary
import dev.stevenjin.stevenpiano.studio.style.StylePrompt
import dev.stevenjin.stevenpiano.studio.style.StyleResult
import dev.stevenjin.stevenpiano.studio.style.StyleSpec
import dev.stevenjin.stevenpiano.studio.style.TempoAsk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Studio tab's state (v1.12 — M30): whether Studio runs here, its models and jobs, its turns ([turns], from
 * Studio's history and the live jobs: the stage's card and History, v1.13.1), what the stage remembers between visits
 * ([stageMemory], the one-minute rule on [clock], a monotonic clock), the preview of the piece being written, the
 * library as the idea box reads it, and what the box understands as the person types (250 ms after the last key, off
 * the main thread). Sending an idea, "Another like it", Adjust…, Listen, Keep, Discard and the models all go through here.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class StudioViewModel(private val graph: AppGraph, private val clock: () -> Long = SystemClock::elapsedRealtime) : ViewModel() {
    private val studio = graph.studio

    val support: StateFlow<StudioSupport> = studio.availability.support
    val installed: StateFlow<Set<String>> = studio.models.installed
    val jobs: StateFlow<List<StudioJob>> = studio.jobs.jobs
    val preview: StateFlow<PreviewRoll?> = studio.preview
    val locked: StateFlow<Boolean> = graph.kiosk.settingsLocked

    /** Studio's history, newest first; null until it has been read. */
    private val rows: StateFlow<List<GenerationEntity>?> = studio.generations.recent(HISTORY)
        .catch { e -> if (e is CancellationException) throw e else emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    val turns: StateFlow<List<Turn>> = combine(rows, studio.jobs.jobs, studio.review.undecided, studio.review.discardedNow) { rows, jobs, undecided, discarded ->
        StudioTurns.of(rows.orEmpty(), jobs, undecided, discarded)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    /** Nothing made yet: the history read, and empty (the stage's quiet line waits for the read, so it never flickers). */
    val nothingYet: StateFlow<Boolean> = rows.map { it?.isEmpty() == true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    private val stage = MutableStateFlow(StageMemory())

    /** What the stage remembers between visits: the results set aside, and when the tab was left ([StudioStage]). */
    val stageMemory: StateFlow<StageMemory> = stage.asStateFlow()

    /** The tab left (another tab, the app in the background, the resting screen over it): remembered with the stage's card. */
    fun stageLeft() {
        stage.update { it.left(StudioStage.card(turns.value, it), clock()) }
    }

    /** The tab shown again: a result shown when it was left a minute or more ago is set aside. */
    fun stageShown() {
        stage.update { it.returned(clock()) }
    }

    /** The stage's card for [turns]; while the tab is [inSight], the one-minute rule as of now, already (before [stageShown] records it). */
    fun stageCard(turns: List<Turn>, memory: StageMemory, inSight: Boolean): Turn? =
        StudioStage.card(turns, if (inSight) memory.returned(clock()) else memory)

    /** The library as the idea box reads it, built again half a second after the library last changed. */
    val library: StateFlow<StyleLibrary?> = graph.library.all()
        .debounce(LIBRARY_SETTLE_MS)
        .map { pieces -> StyleLibrary.of(pieces, graph.channels, graph.builtIns.lists) }
        .flowOn(Dispatchers.Default)
        .catch { e -> if (e is CancellationException) throw e }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** The composer the library holds most pieces by (the last suggestion). */
    val favourite: StateFlow<String?> = library.map { it?.let(StylePrompt::favouriteComposer) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    private val typing = MutableStateFlow("")

    /** What the box understands of what is typed, 250 ms after the last key; null while it is empty. */
    val understood: StateFlow<StyleResult?> = combine(typing.debounce(UNDERSTAND_MS), library, rows) { text, lib, _ -> text to lib }
        .mapLatest { (text, lib) -> if (text.isBlank() || lib == null) null else StylePrompt.parse(text, lib, previous()) }
        .flowOn(Dispatchers.Default)
        .catch { e -> if (e is CancellationException) throw e }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    fun checkSupport() = studio.availability.check()

    fun typed(text: String) {
        typing.value = text
    }

    /** How many ideas wait (Send stops at three). */
    fun waiting(jobs: List<StudioJob>): Int = jobs.count { it.state == JobState.Queued && it.kind != dev.stevenjin.stevenpiano.studio.JobKind.Download }

    /** The last composition's turn, which an idea without a seed of its own refines. */
    private fun previousRow(): GenerationEntity? = rows.value.orEmpty().firstOrNull { it.kind == GenerationEntity.COMPOSE && it.spec != null }

    private fun previous(): PreviousTurn? = previousRow()?.let { row ->
        StyleSpec.decode(row.spec)?.let { PreviousTurn(it, row.bpm, row.seedPieceId) }
    }

    /** Sends [text]: understood afresh (never a stale reading), then queued. False when nothing could be sent. */
    fun send(text: String, onSent: () -> Unit) {
        val lib = library.value ?: return
        viewModelScope.launch {
            val previousRow = previousRow()
            val result = withContext(Dispatchers.Default) { StylePrompt.parse(text, lib, previous()) }
            val spec = result.spec
            val avoid = if (result.refinement && previousRow != null && spec.variant > (StyleSpec.decode(previousRow.spec)?.variant ?: 0)) previousRow.seedPieceId else null
            val order = ComposeOrder(
                pieceId = null,
                request = ComposeRequest(spec.mood, spec.key, (spec.tempo as? TempoAsk.Exact)?.bpm, spec.minutes),
                style = spec,
                candidates = result.candidates,
                turn = TurnRecord(text, result.line, result.unused, if (result.refinement) previousRow?.id else null, avoid),
            )
            studio.compose(order, name = result.candidates.firstOrNull()?.let { lib.byId[it]?.title })
            onSent()
        }
    }

    /** "Another like it": [turn]'s idea again, a new random seed; its own turn, which takes the stage. */
    fun again(turn: Turn) {
        viewModelScope.launch {
            val spec = StyleSpec.decode(turn.spec)
            val lib = library.value
            val order = if (spec != null && lib != null) {
                val candidates = withContext(Dispatchers.Default) { StylePrompt.candidates(spec, lib) }
                ComposeOrder(null, ComposeRequest(spec.mood, spec.key, (spec.tempo as? TempoAsk.Exact)?.bpm, spec.minutes), spec, candidates, TurnRecord(understood = AGAIN, parentId = turn.rowId))
            } else {
                ComposeOrder(turn.seedPieceId, ComposeRequest(turn.mood ?: dev.stevenjin.stevenpiano.studio.compose.Mood.Calm, turn.musicKey, turn.bpm, turn.minutes ?: 2), turn = TurnRecord(understood = AGAIN, parentId = turn.rowId))
            }
            studio.compose(order, name = turn.seedTitle)
        }
    }

    /** The options sheet's first choices from what the box understood. */
    fun startFrom(result: StyleResult?): ComposeStart {
        val spec = result?.spec ?: return ComposeStart()
        return ComposeStart(
            pieceId = (spec.seed as? SeedAsk.Piece)?.pieceId ?: result.candidates.firstOrNull(),
            mood = spec.mood,
            key = spec.key?.let { MusicKey.all.indexOf(it) }?.takeIf { it >= 0 },
            bpm = (spec.tempo as? TempoAsk.Exact)?.bpm,
            minutes = spec.minutes,
        )
    }

    /** "Adjust…": the sheet with [turn]'s own choices. */
    fun startFrom(turn: Turn): ComposeStart = ComposeStart(
        pieceId = turn.seedPieceId,
        mood = turn.mood ?: dev.stevenjin.stevenpiano.studio.compose.Mood.Calm,
        key = turn.musicKey?.let { MusicKey.all.indexOf(it) }?.takeIf { it >= 0 },
        bpm = turn.bpm,
        minutes = turn.minutes ?: 2,
    )

    fun cancel(turn: Turn) {
        turn.job?.let { studio.cancel(it.id) }
    }

    fun keep(pieceId: Long) {
        viewModelScope.launch { studio.review.keep(pieceId) }
    }

    fun discard(pieceId: Long) {
        viewModelScope.launch { runCatching { studio.review.discard(pieceId) } }
    }

    /** Takes [turn] out of the history: a piece still waiting is discarded first; a kept piece stays in the library. */
    fun remove(turn: Turn) {
        viewModelScope.launch {
            if (turn.state == TurnState.Made) turn.pieceId?.let { runCatching { studio.review.discard(it) } }
            turn.rowId?.let { id -> runCatching { studio.generations.remove(id) } }
        }
    }

    fun download(model: ModelEntry) {
        studio.download(model)
    }

    fun removeModel(model: ModelEntry) {
        studio.remove(model)
    }

    fun cancelJob(id: Long) = studio.cancel(id)

    /** A recording the person picked: its read grant kept for the job (given back after), and queued. */
    fun transcribe(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        studio.transcribe(AudioSource.Document(uri))
    }

    companion object {
        const val HISTORY = 200
        private const val STOP_MS = 5_000L
        private const val LIBRARY_SETTLE_MS = 500L
        private const val UNDERSTAND_MS = 250L
        private const val AGAIN = "Another like it"

    }
}
