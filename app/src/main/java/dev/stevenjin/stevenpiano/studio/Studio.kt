// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.db.GenerationEntity
import dev.stevenjin.stevenpiano.data.art.CoverInput
import dev.stevenjin.stevenpiano.studio.compose.Amt
import dev.stevenjin.stevenpiano.studio.compose.ComposeFailures
import dev.stevenjin.stevenpiano.studio.compose.ComposeRequest
import dev.stevenjin.stevenpiano.studio.compose.ComposerModel
import dev.stevenjin.stevenpiano.studio.compose.OrtComposerModel
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.Postprocess
import dev.stevenjin.stevenpiano.studio.compose.PreviewRoll
import dev.stevenjin.stevenpiano.studio.compose.PromptBuilder
import dev.stevenjin.stevenpiano.studio.compose.Sampler
import dev.stevenjin.stevenpiano.studio.compose.SeedFacts
import dev.stevenjin.stevenpiano.studio.compose.SeedPiece
import dev.stevenjin.stevenpiano.studio.style.SeedAsk
import dev.stevenjin.stevenpiano.studio.style.SeedPicker
import dev.stevenjin.stevenpiano.studio.style.StylePrompt
import dev.stevenjin.stevenpiano.studio.style.StyleSpec
import dev.stevenjin.stevenpiano.studio.style.TempoAsk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Reads a recording for the model; Android's [AudioDecoder] in the app, a fake in tests. */
interface RecordingReader {
    fun decode(source: AudioSource, cancelled: () -> Boolean): DecodedAudio

    fun displayName(source: AudioSource): String?
}

/** Where a composition's seed comes from (v1.7 — M24): the library in the app, a fake in tests. Only the library: nothing else reaches the model. */
interface SeedSource {
    /** Piece [pieceId] as a seed (its title, composer and parsed notes); null when it is gone or can't be read. */
    suspend fun seed(pieceId: Long): SeedPiece?

    /** The piece a composition starts from when none is chosen: the one played last that Studio didn't make, else the first by title; null for an empty library. */
    suspend fun defaultPieceId(): Long?
}

/**
 * A composition asked for (v1.7 — M24): its seed, piece [pieceId] of the library ([SeedSource.defaultPieceId] when
 * null), and the sheet's [request]. A typed idea (v1.12 — M30) gives its asked [style] instead, resolved on the job's
 * thread against its seed [candidates] ([SeedPicker]); [turn] is what the history keeps of how it was asked.
 */
data class ComposeOrder(
    val pieceId: Long?,
    val request: ComposeRequest,
    val style: StyleSpec? = null,
    val candidates: List<Long> = emptyList(),
    val turn: TurnRecord = TurnRecord(),
)

/**
 * What a turn of Studio's history keeps of how it was asked (v1.12 — M30): the idea as typed ([prompt], cut to
 * [StylePrompt.MAX_CHARS] code points; it is kept only in the history, never in a title, a log line, the
 * diagnostics or a file name), the line that says what was understood ([understood]), the words not used
 * ([unused]), the turn it refines ([parentId]), and a seed piece to pass over ([avoid]: "different").
 */
data class TurnRecord(
    val prompt: String? = null,
    val understood: String = "",
    val unused: List<String> = emptyList(),
    val parentId: Long? = null,
    val avoid: Long? = null,
)

/** A seed as the compose sheet shows it: the piece ([pieceId], [title], [composer]) and what it brings, its key and tempo ([facts]). */
data class SeedChoice(val pieceId: Long, val title: String, val composer: String?, val facts: SeedFacts)

/**
 * Studio (v1.7 — M23, M24): its models, its jobs, and what a finished transcription or composition
 * leaves (the review of Keep or Discard). Jobs run **one at a time**, in the order they were asked for,
 * on [worker] (the app gives it a thread of its own at `THREAD_PRIORITY_BACKGROUND`, so the player's
 * scheduler, at urgent audio priority, keeps its timing; ORT's own threads, made from it, take its
 * priority). A download brings a model on ([ModelInstaller]); a transcription reads the recording
 * ([RecordingReader]), runs the model ([Transcriber], behind [MemoryGate]: 900 MiB free above the
 * threshold to start, and the memory checked before every window) and adds the piece ([StudioPieces]);
 * a composition (v1.7 — M24) reads its seed from the library ([SeedSource]), builds its prompt
 * ([PromptBuilder]), samples the composing model ([Sampler], behind 700 MiB free, the memory checked
 * every 100 tokens; [random] gives each job its own seed) and adds what it wrote ([Postprocess], only the
 * new music, never the seed). Either piece then waits for Keep or Discard ([StudioReview]). A job asked
 * for while its model isn't here queues the model's download first. [onBusy] is called whenever a job
 * is queued (the app starts its foreground service then); [awake] holds the device awake around each
 * job. Every outcome is a job's state: nothing a job does can throw out of here.
 */
class Studio(
    private val scope: CoroutineScope,
    val availability: StudioAvailability,
    val models: ModelStore,
    private val installer: ModelInstaller,
    private val reader: RecordingReader,
    private val pieces: StudioPieces,
    val review: StudioReview,
    private val worker: CoroutineDispatcher,
    private val online: () -> Boolean,
    private val onBusy: () -> Unit = {},
    private val awake: (Boolean) -> Unit = {},
    private val release: (AudioSource) -> Unit = {},
    private val openModel: (File) -> WindowModel = { OrtWindowModel(it) },
    private val clock: () -> ZonedDateTime = ZonedDateTime::now,
    private val elapsed: () -> Long = System::nanoTime,
    private val peakKb: () -> Long = { -1L },
    private val log: (String) -> Unit = {},
    /** Where each transcription's figures also go (the link's trail, so Share diagnostics carries them): no name in them. */
    private val trail: (String) -> Unit = {},
    /** The transcription model (the catalogue's; tests give a small one). */
    private val transcriptionModel: ModelEntry = ModelCatalogue.transcription,
    /** Where compositions' seeds come from (v1.7 — M24). */
    private val seeds: SeedSource = NoSeeds,
    private val openComposer: (File) -> ComposerModel = { OrtComposerModel(it) },
    /** The composing model (the catalogue's; tests give a small one). */
    private val composerModel: ModelEntry = ModelCatalogue.composer,
    /** Each composition's own seed for its random choices (logged with its figures). */
    private val random: () -> Long = System::nanoTime,
    /** Studio's history (v1.12 — M30): a turn per job. */
    val generations: Generations = NoGenerations,
    /** This tablet's pace, for the first seconds' "time left". */
    private val calibration: StudioCalibration = NoCalibration,
    /** Kiosk mode: at most [KIOSK_UNDECIDED] of Studio's pieces wait for the PIN; past that, the oldest is discarded. */
    private val kiosk: () -> Boolean = { false },
) {
    val jobs = StudioJobs()

    private val previewState = MutableStateFlow<PreviewRoll?>(null)

    /**
     * The notes the composition running now has written so far (v1.12 — M30), for its card's preview roll; null
     * when none runs. Never part of [jobs], so the notification and the web panel's state never carry it.
     */
    val preview: StateFlow<PreviewRoll?> = previewState.asStateFlow()

    private sealed interface Pending {
        val id: Long
    }

    private class Download(override val id: Long, val model: ModelEntry) : Pending

    private class Transcribe(override val id: Long, val source: AudioSource, val name: String?, val turn: Deferred<Long?>) : Pending

    private class Compose(override val id: Long, val order: ComposeOrder, val turn: Deferred<Long?>) : Pending

    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val running = ConcurrentHashMap<Long, Job>()
    private val cancelledWhileQueued = ConcurrentHashMap.newKeySet<Long>()

    /** Tidies the models' folder (on the job thread, before any job), reads back the pieces waiting for Keep or Discard, and starts taking jobs. */
    fun start() {
        scope.launch(worker) { runCatching { models.sweep() } }
        scope.launch(worker) {
            history { generations.interruptPending() }
            history { generations.trim() }
            drawMissingCovers()
        }
        review.start()
        scope.launch {
            for (pending in queue) take(pending)
        }
    }

    /** Downloads [model], unless it is here or on its way already; the job, or null then. */
    fun download(model: ModelEntry): StudioJob? {
        if (models.isInstalled(model) || downloading(model)) return null
        val job = jobs.add(JobKind.Download, "${model.title} model", model = model.name)
        queue.trySend(Download(job.id, model))
        onBusy()
        return job
    }

    /** Transcribes [source] (called [name], else its own display name), after the model's download when it isn't here. */
    fun transcribe(source: AudioSource, name: String? = null): StudioJob {
        val model = transcriptionModel
        if (!models.isInstalled(model)) download(model)
        val shown = name ?: runCatching { reader.displayName(source) }.getOrNull() ?: "A recording"
        val job = jobs.add(JobKind.Transcribe, shown, steps = listOf(JobStep.Reading, JobStep.Transcribing, JobStep.Saving))
        val turn = beginTurn(job, GenerationEntity(createdAt = now(), kind = GenerationEntity.TRANSCRIBE, understood = StudioPieces.transcribeLine(shown), outcome = GenerationEntity.PENDING))
        queue.trySend(Transcribe(job.id, source, shown, turn))
        onBusy()
        return job
    }

    /**
     * Composes a piece (v1.7 — M24) from [order]; [name] is its seed's title, shown while it waits (the
     * job names it once it has read the seed). After the composing model's download when it isn't here.
     */
    fun compose(order: ComposeOrder, name: String? = null): StudioJob {
        val model = composerModel
        if (!models.isInstalled(model)) download(model)
        val job = jobs.add(JobKind.Compose, name?.takeIf { it.isNotBlank() } ?: "a piece", steps = COMPOSE_STEPS)
        val style = order.style
        val asked = order.turn
        val row = GenerationEntity(
            createdAt = now(),
            kind = GenerationEntity.COMPOSE,
            parentId = asked.parentId,
            prompt = asked.prompt?.let { TextLimits.clip(it, StylePrompt.MAX_CHARS) }?.takeIf { it.isNotBlank() },
            understood = TextLimits.clip(asked.understood, LINE_CHARS),
            unused = asked.unused.take(StylePrompt.MAX_UNUSED).joinToString("\n") { TextLimits.clip(it, StylePrompt.MAX_CHARS) },
            spec = style?.encode() ?: StyleSpec(order.request.mood, order.request.key, null, order.request.bpm?.let { TempoAsk.Exact(it) }, order.request.minutes, order.pieceId?.let { SeedAsk.Piece(it) } ?: SeedAsk.Default).encode(),
            mood = (style?.mood ?: order.request.mood).name,
            minutes = style?.minutes ?: order.request.minutes,
            outcome = GenerationEntity.PENDING,
        )
        val turn = beginTurn(job, row)
        queue.trySend(Compose(job.id, order, turn))
        onBusy()
        return job
    }

    /** The turn of [job] in the history, written now; its id once written (null when the history can't be written: the job runs all the same). */
    private fun beginTurn(job: StudioJob, row: GenerationEntity): Deferred<Long?> = scope.async(worker) {
        history { generations.begin(row) }?.also { id -> jobs.update(job.id) { it.copy(turnId = id) } }
    }

    /** How many jobs wait (not yet running): Studio's Send and "Another like it" stop at [MAX_WAITING]. */
    val waiting: Int get() = jobs.jobs.value.count { it.state == JobState.Queued && it.kind != JobKind.Download }

    /**
     * The seed piece [pieceId] (the default one when null) as the compose sheet shows it, with its key
     * and tempo; null when there is none, or it can't be read. All of it off the main thread (the piece
     * is parsed there too), and it never throws but for cancellation: the compose sheet and the panel
     * call it with nothing around it (audit delta 2).
     */
    suspend fun seedChoice(pieceId: Long?): SeedChoice? = withContext(Dispatchers.Default) {
        try {
            val id = pieceId ?: seeds.defaultPieceId() ?: return@withContext null
            val seed = seeds.seed(id) ?: return@withContext null
            SeedChoice(id, seed.title, seed.composer, PromptBuilder.facts(seed.midi))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Stops job [id]: the one running between buffers, windows or tokens, one waiting before it starts. */
    fun cancel(id: Long) {
        val job = jobs.get(id) ?: return
        when (job.state) {
            JobState.Queued -> {
                cancelledWhileQueued += id
                jobs.update(id) { it.copy(state = JobState.Cancelled, step = JobStep.Waiting) }
            }
            JobState.Running -> running[id]?.cancel()
            else -> Unit
        }
    }

    /** Removes [model] from the tablet (never while a job uses or brings it). False when it can't go now. */
    fun remove(model: ModelEntry): Boolean {
        val user = when (model) {
            transcriptionModel -> JobKind.Transcribe
            composerModel -> JobKind.Compose
            else -> null
        }
        if (downloading(model) || jobs.jobs.value.any { it.kind == user && !it.state.finished }) return false
        scope.launch(worker) { models.remove(model) }
        return true
    }

    /** Whether [model]'s download waits or runs. */
    fun downloading(model: ModelEntry): Boolean =
        jobs.jobs.value.any { it.kind == JobKind.Download && it.model == model.name && !it.state.finished }

    private suspend fun take(pending: Pending) {
        if (cancelledWhileQueued.remove(pending.id)) {
            (pending as? Transcribe)?.let { runCatching { release(it.source) } }
            val turn = (pending as? Compose)?.turn ?: (pending as? Transcribe)?.turn
            turn?.let { scope.launch(worker) { it.await()?.let { id -> history { generations.update(id) { row -> row.copy(outcome = GenerationEntity.CANCELLED) } } } } }
            return
        }
        jobs.update(pending.id) { it.copy(state = JobState.Running) }
        awake(true)
        val job = scope.launch(worker) {
            when (pending) {
                is Download -> runDownload(pending)
                is Transcribe -> runTranscription(pending)
                is Compose -> outcome(pending.id, pending.turn) { runComposition(pending) }
            }
        }
        running[pending.id] = job
        job.join()
        running.remove(pending.id)
        awake(jobs.busy)
    }

    private suspend fun runDownload(pending: Download) {
        outcome(pending.id) {
            if (!online()) throw StudioFailure(StudioFailures.OFFLINE)
            jobs.update(pending.id) { it.copy(step = JobStep.Downloading, progress = 0f, total = pending.model.sizeBytes) }
            installer.install(pending.model) { bytes, total ->
                jobs.update(pending.id) { it.copy(bytes = bytes, total = total, progress = (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)) }
            }
            log("Studio: ${pending.model.file} downloaded and verified")
        }
    }

    private suspend fun runTranscription(pending: Transcribe) {
        // The recording is given back before the job reads as ended: a web upload's file is gone by then.
        outcome(pending.id, pending.turn, cleanup = { runCatching { release(pending.source) } }) { transcribe(pending) }
    }

    /** Whether Studio runs here at all, asked once per process: a job refuses in words when it doesn't. */
    private suspend fun requireSupport() {
        availability.check()
        when (availability.support.first { it != StudioSupport.Checking }) {
            StudioSupport.NoRuntime -> throw StudioFailure(StudioFailures.UNAVAILABLE)
            StudioSupport.TooLittleMemory -> throw StudioFailure(StudioFailures.TOO_LITTLE_MEMORY)
            else -> Unit
        }
    }

    private suspend fun transcribe(pending: Transcribe) {
        val id = pending.id
        requireSupport()
        val model = transcriptionModel
        val installed = models.isInstalled(model)
        val file = models.open(model) ?: throw StudioFailure(if (installed) StudioFailures.MODEL_DAMAGED else StudioFailures.NO_MODEL)
        if (!MemoryGate.canStart(availability.memory())) throw StudioFailure(StudioFailures.BUSY)
        val context = currentCoroutineContext()
        val cancelled = { !context.isActive }
        val startedAt = elapsed()
        jobs.update(id) { it.copy(step = JobStep.Reading, progress = null) }
        val audio = reader.decode(pending.source, cancelled)
        val decodedAt = elapsed()
        jobs.update(id) { it.copy(step = JobStep.Transcribing, progress = 0f) }
        var windows = 0
        val result = openModel(file).use { session ->
            Transcriber(memoryHolds = { MemoryGate.canContinue(availability.memory()) }, cancelled = cancelled)
                .transcribe(audio, session) { done, of ->
                    windows = of
                    jobs.update(id) { it.copy(progress = done.toFloat() / of) }
                }
        }
        val transcribedAt = elapsed()
        jobs.update(id) { it.copy(step = JobStep.Saving, progress = null) }
        val turnId = pending.turn.await()
        save(turnId, { piece -> { row -> row.copy(pieceId = piece.id, title = piece.title, wallMs = (elapsed() - startedAt) / 1_000_000, coverKind = piece.cover) } }) {
            pieces.add(result, pending.name, clock(), random())
        }
        val figures = "Studio: transcribed %.1f s of audio in %.1f s (read %.1f s, %d windows, model %.1f s), %d notes, %d pedal; peak VmHWM %d kB"
            .format(
                java.util.Locale.ROOT,
                audio.seconds, (transcribedAt - startedAt) / 1e9, (decodedAt - startedAt) / 1e9, windows,
                (transcribedAt - decodedAt) / 1e9, result.notes.size, result.pedals.size, peakKb(),
            )
        log(figures)
        trail(figures)
    }

    /**
     * A composition (v1.7 — M24): the model checked and opened, the memory gate (700 MiB), the seed read
     * from the library and made a prompt, the model sampled a token at a time (progress, the cancel before
     * each token, the memory every hundred), then only what it wrote, post-processed, becomes the piece.
     * v1.12 (M30): a typed idea's seed is picked here from its candidates ([SeedPicker]); every whole event
     * the model writes joins the preview, and the job's figures are published at most twice a second
     * ([ProgressMeter]); the piece is titled from what was understood ([StylePrompt.title]), drawn its cover,
     * and its turn in the history says how it went.
     */
    private suspend fun runComposition(pending: Compose) {
        val id = pending.id
        val turnId = pending.turn.await()
        jobs.update(id) { it.copy(step = JobStep.Reading, progress = null) }
        requireSupport()
        val model = composerModel
        val installed = models.isInstalled(model)
        val file = models.open(model) ?: throw StudioFailure(if (installed) StudioFailures.COMPOSER_DAMAGED else StudioFailures.NO_COMPOSER)
        if (!MemoryGate.canStartComposing(availability.memory())) throw StudioFailure(StudioFailures.BUSY)
        val chosen = chooseSeed(pending.order)
        val seed = seeds.seed(chosen.pieceId) ?: throw StudioFailure(ComposeFailures.SEED_GONE)
        val prompt = PromptBuilder.build(seed, chosen.request)
        val title = StylePrompt.title(prompt.mood, chosen.ask, seed.title)
        val minutes = chosen.request.minutes.coerceIn(PromptBuilder.MIN_MINUTES, PromptBuilder.MAX_MINUTES)
        val targetTicks = minutes * 60 * Amt.TICKS_PER_SECOND
        jobs.update(id) {
            it.copy(name = seed.title, step = JobStep.Composing, progress = 0f, budget = prompt.budget, targetMs = minutes * 60_000L, tokens = 0, musicMs = 0, notes = 0)
        }
        val seedValue = random()
        turnId?.let { turn ->
            history {
                generations.update(turn) { row ->
                    row.copy(
                        mood = prompt.mood.name, keyTonic = prompt.key.tonic, keyMinor = prompt.key.minor, bpm = prompt.bpm, minutes = minutes,
                        seedPieceId = chosen.pieceId, seedTitle = TextLimits.clip(seed.title, TextLimits.TITLE),
                        seedComposer = seed.composer?.let { TextLimits.clip(it, TextLimits.COMPOSER) }, seedSource = seedSource(chosen.ask),
                        randomSeed = seedValue, composerModel = model.name, composerVersion = model.version,
                    )
                }
            }
        }
        val context = currentCoroutineContext()
        val chance = Random(seedValue)
        val startedAt = elapsed()
        val startedMs = startedAt / 1_000_000
        val meter = ProgressMeter(prompt.budget, targetTicks, startedMs, calibration.msPerToken())
        val roll = PreviewRoll.Builder(prompt.currentTime)
        previewState.value = roll.snapshot()
        val generation = openComposer(file).use { session ->
            Sampler(
                session,
                chance,
                cancelled = { !context.isActive },
                memoryHolds = { MemoryGate.canContinue(availability.memory()) },
            ).generate(prompt, onEvent = { event, made, fraction ->
                roll.add(event)
                val now = elapsed() / 1_000_000
                if (meter.due(now)) {
                    val shot = roll.snapshot()
                    previewState.value = shot
                    val ticks = shot.lengthTicks
                    jobs.update(id) {
                        it.copy(
                            progress = fraction, tokens = made, musicMs = ticks * 10L, notes = shot.size,
                            etaMs = meter.etaMs(now, made, ticks, fraction),
                        )
                    }
                }
            })
        }
        val composedAt = elapsed()
        previewState.value = roll.snapshot()
        jobs.update(id) { it.copy(step = JobStep.Shaping, progress = null, tokens = generation.tokens.size, etaMs = null) }
        val composition = Postprocess.compose(generation.events, prompt.bpm, prompt.mood, chance)
        jobs.update(id) { it.copy(step = JobStep.Saving, musicMs = composition.durationMicros / 1_000, notes = composition.notes.size) }
        val stop = generation.stop.name.lowercase(java.util.Locale.ROOT)
        val seconds = (composedAt - startedAt) / 1e9
        val cover = CoverInput.of(composition, prompt.key, prompt.mood, prompt.bpm, seedValue)
        save(turnId, { piece ->
            { row ->
                row.copy(
                    pieceId = piece.id, title = piece.title, tokens = generation.tokens.size, slides = generation.slides, stop = stop,
                    musicMs = composition.durationMicros / 1_000, wallMs = (elapsed() - startedAt) / 1_000_000,
                    coverKind = piece.cover, coverSeed = seedValue,
                )
            }
        }, stop = stop) {
            pieces.addComposition(composition, prompt.mannerOf, clock(), title, cover)
        }
        if (generation.tokens.isNotEmpty()) runCatching { calibration.record(seconds * 1000 / generation.tokens.size) }
        // The figures go to Logcat and the trail: no idea, no title, no name in them.
        val figures = "Studio: composed %.1f s of music in %.1f s (%d tokens, %.1f ms a token, %d slides, stop %s), %d notes at %d bpm, %s, %d min; seed %d; peak VmHWM %d kB"
            .format(
                java.util.Locale.ROOT,
                composition.durationMicros / 1e6, seconds, generation.tokens.size,
                if (generation.tokens.isEmpty()) 0.0 else seconds * 1000 / generation.tokens.size,
                generation.slides, stop, composition.notes.size, prompt.bpm,
                prompt.mood.name.lowercase(java.util.Locale.ROOT), minutes, seedValue, peakKb(),
            )
        log(figures)
        trail(figures)
    }

    /** The seed a composition grows from, what is asked of it, and how it was found. */
    private class Chosen(val pieceId: Long, val request: ComposeRequest, val ask: SeedAsk)

    /**
     * A typed idea's seed: the [SeedPicker]'s among its candidates (reading their key and tempo), else the default
     * piece; the options sheet's and the web panel's: their piece as chosen, or the default.
     */
    private suspend fun chooseSeed(order: ComposeOrder): Chosen {
        val style = order.style
        if (style != null) {
            val facts: suspend (Long) -> SeedFacts? = { id -> seeds.seed(id)?.let { runCatching { PromptBuilder.facts(it.midi) }.getOrNull() } }
            SeedPicker.pick(style, order.candidates, facts, order.turn.avoid)?.let { return Chosen(it.pieceId, it.request, style.seed) }
            val fallback = seeds.defaultPieceId() ?: throw StudioFailure(ComposeFailures.EMPTY_LIBRARY)
            val known = facts(fallback) ?: throw StudioFailure(ComposeFailures.SEED_GONE)
            return Chosen(fallback, SeedPicker.request(style, known), SeedAsk.Default)
        }
        val pieceId = order.pieceId ?: seeds.defaultPieceId() ?: throw StudioFailure(ComposeFailures.EMPTY_LIBRARY)
        return Chosen(pieceId, order.request, if (order.pieceId != null) SeedAsk.Piece(pieceId) else SeedAsk.Default)
    }

    private fun seedSource(ask: SeedAsk): String = when (ask) {
        is SeedAsk.Title -> "title"
        is SeedAsk.Pool -> ask.source.substringBefore(':').substringBefore('+').let { if ('+' in ask.source) "composer+form" else it }
        is SeedAsk.Piece -> "chosen"
        SeedAsk.Default -> "default"
    }

    /** How the job running now ended, when it did: published after its cleanup. */
    private var done: ((StudioJob) -> StudioJob)? = null

    /**
     * The piece [add] makes goes into the library and waits for Keep or Discard, its turn says so ([record]), and the
     * job is done: all of it once it has begun, whatever cancel comes meanwhile (audit delta 2: a cancel during the
     * import left the piece in the library, its job "Cancelled" and no Keep or Discard asked). In kiosk mode at most
     * [KIOSK_UNDECIDED] of Studio's pieces wait: the oldest past that is discarded.
     */
    private suspend fun save(
        turnId: Long?,
        record: (StudioPiece) -> (GenerationEntity) -> GenerationEntity,
        stop: String? = null,
        add: suspend () -> StudioPiece,
    ) = withContext(NonCancellable) {
        val piece = add()
        turnId?.let { turn -> history { generations.update(turn) { row -> record(piece)(row).copy(outcome = GenerationEntity.MADE) } } }
        review.made(piece.id)
        pieces.shelve()
        done = { it.copy(state = JobState.Done, step = JobStep.Waiting, progress = 1f, pieceId = piece.id, title = piece.title, stop = stop, etaMs = null) }
        if (kiosk()) {
            val waiting = history { generations.undecided() }.orEmpty()
            for (old in waiting.dropLast(KIOSK_UNDECIDED)) {
                log("Studio: more than $KIOSK_UNDECIDED pieces wait for the PIN in kiosk mode: the oldest discarded")
                runCatching { review.discard(old) }
            }
        }
        history { generations.trim() }
    }

    /** Runs [block], whose every outcome becomes job [id]'s state (and its turn's, [turn]), once [cleanup] has run. */
    private suspend fun outcome(id: Long, turn: Deferred<Long?>? = null, cleanup: () -> Unit = {}, block: suspend () -> Unit) {
        done = null
        var line: String? = null
        var cancelled = false
        val end: (StudioJob) -> StudioJob = try {
            block()
            done ?: { it.copy(state = JobState.Done, step = JobStep.Waiting, progress = 1f) }
        } catch (e: CancellationException) {
            // A cancel that came once the piece was saved changes nothing: it is in the library, waiting for Keep or Discard.
            cancelled = done == null
            done ?: { it.copy(state = JobState.Cancelled, step = JobStep.Waiting, progress = null, etaMs = null) }
        } catch (e: StudioFailure) {
            line = e.message ?: StudioFailures.FAILED
            failed(line)
        } catch (e: AudioFailure) {
            line = e.message ?: AudioFailure.UNREADABLE
            failed(line)
        } catch (e: OutOfMemoryError) {
            line = if (jobs.get(id)?.kind == JobKind.Compose) ComposeFailures.RAN_OUT else StudioFailures.RAN_OUT
            failed(line)
        } catch (e: Throwable) {
            // The runtime's own errors (OrtException), the library's, and any Error but running out of memory (audit
            // delta 2: one used to escape into the app's scope, crash the app and leave its notification): this job
            // fails, Studio goes on.
            log("Studio: job $id failed: ${e.javaClass.simpleName}")
            line = when (jobs.get(id)?.kind) {
                JobKind.Download -> StudioFailures.UNREACHABLE
                JobKind.Compose -> ComposeFailures.FAILED
                else -> StudioFailures.FAILED
            }
            failed(line)
        }
        cleanup()
        previewState.value = null
        if (turn != null && (cancelled || line != null)) {
            withContext(NonCancellable) {
                turn.await()?.let { turnId ->
                    history {
                        generations.update(turnId) { row ->
                            if (row.outcome != GenerationEntity.PENDING) row else row.copy(outcome = if (cancelled) GenerationEntity.CANCELLED else GenerationEntity.FAILED, error = line)
                        }
                    }
                }
            }
        }
        jobs.update(id, end)
    }

    /** A history's read or write: null when the database can't be reached (the job goes on; the history only tells). */
    private suspend fun <T> history(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        log("Studio: the history couldn't be written: ${e.javaClass.simpleName}")
        null
    }

    /** At start: a cover for each piece made before covers were drawn (apps 1.7 to 1.11), from its own notes; one at a time. */
    private suspend fun drawMissingCovers() {
        val missing = history { generations.withoutCover() }.orEmpty()
        for (row in missing) {
            val pieceId = row.pieceId ?: continue
            val notes = try {
                seeds.seed(pieceId)?.midi?.notes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            val seedValue = row.randomSeed ?: (pieceId * 7_919L)
            val drawn = notes != null && runCatching {
                val mood = row.mood?.let { name -> Mood.entries.firstOrNull { it.name == name } }
                val key = if (row.keyTonic != null && row.keyMinor != null) MusicKey(row.keyTonic, row.keyMinor) else null
                pieces.cover(pieceId, CoverInput.of(notes, key, mood, row.bpm, seedValue))
            }.getOrDefault(false)
            history { generations.setCover(row.id, if (drawn) GenerationEntity.DRAWN else NO_COVER, seedValue) }
        }
    }

    private fun now(): Long = clock().toInstant().toEpochMilli()

    private fun failed(line: String): (StudioJob) -> StudioJob = { it.copy(state = JobState.Failed, step = JobStep.Waiting, progress = null, error = line, etaMs = null) }

    companion object {
        /** A composition's row of steps. */
        val COMPOSE_STEPS = listOf(JobStep.Reading, JobStep.Composing, JobStep.Shaping, JobStep.Saving)

        /** At most this many typed ideas wait at once (v1.12 — M30: Send and "Another like it" stop there). */
        const val MAX_WAITING = 3

        /** In kiosk mode at most this many of Studio's pieces wait for the PIN. */
        const val KIOSK_UNDECIDED = 30

        /** The understood line is kept to this. */
        private const val LINE_CHARS = 400

        /** A turn whose piece couldn't be read for a cover: not tried again. */
        private const val NO_COVER = "none"
    }
}

/** No seeds: a Studio made without a library (the transcription tests) composes nothing. */
private object NoSeeds : SeedSource {
    override suspend fun seed(pieceId: Long): SeedPiece? = null

    override suspend fun defaultPieceId(): Long? = null
}
