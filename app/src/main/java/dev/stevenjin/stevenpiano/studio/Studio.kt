// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/** Reads a recording for the model; Android's [AudioDecoder] in the app, a fake in tests. */
interface RecordingReader {
    fun decode(source: AudioSource, cancelled: () -> Boolean): DecodedAudio

    fun displayName(source: AudioSource): String?
}

/**
 * Studio (v1.7 — M23): its models, its jobs, and what a finished transcription leaves (the review of
 * Keep or Discard). Jobs run **one at a time**, in the order they were asked for, on [worker] (the app
 * gives it a thread of its own at `THREAD_PRIORITY_BACKGROUND`, so the player's scheduler, at urgent
 * audio priority, keeps its timing; ORT's own threads, made from it, take its priority). A download
 * brings a model on ([ModelInstaller]); a transcription reads the recording ([RecordingReader]), runs
 * the model ([Transcriber], behind [MemoryGate]: 900 MiB free above the threshold to start, and the
 * memory checked before every window) and adds the piece ([StudioPieces]), which then waits for Keep or
 * Discard ([StudioReview]). A transcription asked for while its model isn't here queues the model's
 * download first. [onBusy] is called whenever a job is queued (the app starts its foreground service
 * then); [awake] holds the device awake around each job. Every outcome is a job's state: nothing a job
 * does can throw out of here.
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
) {
    val jobs = StudioJobs()

    private sealed interface Pending {
        val id: Long
    }

    private class Download(override val id: Long, val model: ModelEntry) : Pending

    private class Transcribe(override val id: Long, val source: AudioSource, val name: String?) : Pending

    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val running = ConcurrentHashMap<Long, Job>()
    private val cancelledWhileQueued = ConcurrentHashMap.newKeySet<Long>()

    /** Tidies the models' folder (on the job thread, before any job), reads back the pieces waiting for Keep or Discard, and starts taking jobs. */
    fun start() {
        scope.launch(worker) { runCatching { models.sweep() } }
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
        val job = jobs.add(JobKind.Transcribe, shown)
        queue.trySend(Transcribe(job.id, source, shown))
        onBusy()
        return job
    }

    /** Stops job [id]: the one running between buffers or windows, one waiting before it starts. */
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
        if (downloading(model) || (model == transcriptionModel && jobs.jobs.value.any { it.kind == JobKind.Transcribe && !it.state.finished })) return false
        scope.launch(worker) { models.remove(model) }
        return true
    }

    /** Whether [model]'s download waits or runs. */
    fun downloading(model: ModelEntry): Boolean =
        jobs.jobs.value.any { it.kind == JobKind.Download && it.model == model.name && !it.state.finished }

    private suspend fun take(pending: Pending) {
        if (cancelledWhileQueued.remove(pending.id)) {
            (pending as? Transcribe)?.let { runCatching { release(it.source) } }
            return
        }
        jobs.update(pending.id) { it.copy(state = JobState.Running) }
        awake(true)
        val job = scope.launch(worker) {
            when (pending) {
                is Download -> runDownload(pending)
                is Transcribe -> runTranscription(pending)
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
        outcome(pending.id, cleanup = { runCatching { release(pending.source) } }) { transcribe(pending) }
    }

    private suspend fun transcribe(pending: Transcribe) {
        val id = pending.id
        availability.check()
        when (availability.support.first { it != StudioSupport.Checking }) {
            StudioSupport.NoRuntime -> throw StudioFailure(StudioFailures.UNAVAILABLE)
            StudioSupport.TooLittleMemory -> throw StudioFailure(StudioFailures.TOO_LITTLE_MEMORY)
            else -> Unit
        }
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
        val piece = pieces.add(result, pending.name, clock())
        review.made(piece.id)
        done = { it.copy(state = JobState.Done, step = JobStep.Waiting, progress = 1f, pieceId = piece.id, title = piece.title) }
        val figures = "Studio: transcribed %.1f s of audio in %.1f s (read %.1f s, %d windows, model %.1f s), %d notes, %d pedal; peak VmHWM %d kB"
            .format(
                java.util.Locale.ROOT,
                audio.seconds, (transcribedAt - startedAt) / 1e9, (decodedAt - startedAt) / 1e9, windows,
                (transcribedAt - decodedAt) / 1e9, result.notes.size, result.pedals.size, peakKb(),
            )
        log(figures)
        trail(figures)
    }

    /** How the transcription running now ended, when it did: published after its cleanup. */
    private var done: ((StudioJob) -> StudioJob)? = null

    /** Runs [block], whose every outcome becomes job [id]'s state, once [cleanup] has run. */
    private suspend fun outcome(id: Long, cleanup: () -> Unit = {}, block: suspend () -> Unit) {
        done = null
        val end: (StudioJob) -> StudioJob = try {
            block()
            done ?: { it.copy(state = JobState.Done, step = JobStep.Waiting, progress = 1f) }
        } catch (e: CancellationException) {
            { it.copy(state = JobState.Cancelled, step = JobStep.Waiting, progress = null) }
        } catch (e: StudioFailure) {
            failed(e.message ?: StudioFailures.FAILED)
        } catch (e: AudioFailure) {
            failed(e.message ?: AudioFailure.UNREADABLE)
        } catch (e: OutOfMemoryError) {
            failed(StudioFailures.RAN_OUT)
        } catch (e: Exception) {   // the runtime's own errors (OrtException), the library's: this job fails, Studio goes on
            log("Studio: job $id failed: ${e.javaClass.simpleName}: ${e.message}")
            failed(if (jobs.get(id)?.kind == JobKind.Download) StudioFailures.UNREACHABLE else StudioFailures.FAILED)
        }
        cleanup()
        jobs.update(id, end)
    }

    private fun failed(line: String): (StudioJob) -> StudioJob = { it.copy(state = JobState.Failed, step = JobStep.Waiting, progress = null, error = line) }
}
