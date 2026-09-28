// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfException
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.studio.compose.Amt
import dev.stevenjin.stevenpiano.studio.compose.ComposeFailures
import dev.stevenjin.stevenpiano.studio.compose.ComposeRequest
import dev.stevenjin.stevenpiano.studio.compose.ComposerFixtures
import dev.stevenjin.stevenpiano.studio.compose.ComposerModel
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.SeedPiece
import dev.stevenjin.stevenpiano.update.FakeUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Studio's jobs (v1.7 — M23, M24): one at a time on their own thread, a transcription or a composition
 * after its model's download, the memory gates, cancelling, every failure a job's line, the recording
 * given back, and a composition that holds only the new music, at its own tempo.
 */
class StudioTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val work = Executors.newSingleThreadExecutor { Thread(it, "studio-test-worker") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    @After
    fun tearDown() {
        scope.cancel()
        main.close()
        work.close()
    }

    private val modelBytes = Random(1).nextBytes(50_000)
    private val modelUrl = "https://github.com/${UpdateSource.REPOSITORY}/releases/download/models/tiny-v1.onnx"
    private val tiny = ModelEntry(
        "tiny", 1, "tiny-v1.onnx", modelUrl, modelBytes.size.toLong(),
        VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(modelBytes)), "CC0-1.0", "tiny", "Tiny", "CC0", "",
    )
    private val composerBytes = Random(2).nextBytes(40_000)
    private val composerUrl = "https://github.com/${UpdateSource.REPOSITORY}/releases/download/models/tunes-v1.onnx"
    private val tunes = ModelEntry(
        "tunes", 1, "tunes-v1.onnx", composerUrl, composerBytes.size.toLong(),
        VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(composerBytes)), "CC0-1.0", "tunes", "Composing", "CC0", "",
    )
    private val server = FakeUpdateServer(
        JSONObject().put(
            "models",
            JSONArray().put(
                JSONObject().put("name", "tiny").put("version", 1).put("file", "tiny-v1.onnx").put("url", modelUrl)
                    .put("sizeBytes", modelBytes.size).put("sha256", tiny.sha256).put("licence", "CC0-1.0").put("attribution", "Tiny"),
            ).put(
                JSONObject().put("name", "tunes").put("version", 1).put("file", "tunes-v1.onnx").put("url", composerUrl)
                    .put("sizeBytes", composerBytes.size).put("sha256", tunes.sha256).put("licence", "CC0-1.0").put("attribution", "Tunes"),
            ),
        ).toString(),
    ).also {
        it.files[modelUrl] = modelBytes
        it.files[composerUrl] = composerBytes
    }

    private var memory = MemorySnapshot(4L shl 30, 3L shl 30, 200L shl 20, lowMemory = false)
    private var online = true
    private var downloadGate: CountDownLatch? = null
    private var runtimeLoads = true
    private val released: MutableList<AudioSource> = Collections.synchronizedList(mutableListOf())
    private val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val added = mutableListOf<String>()
    private val trailed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var windowGate: CountDownLatch? = null

    /** A recording of [seconds]; "bad" ones can't be read. */
    private val reader = object : RecordingReader {
        override fun decode(source: AudioSource, cancelled: () -> Boolean): DecodedAudio {
            threads += Thread.currentThread().name
            val file = (source as AudioSource.Local).file
            if (file.name.startsWith("bad")) throw AudioFailure(AudioFailure.UNREADABLE)
            val seconds = file.name.substringBefore('-').toIntOrNull() ?: 10
            return DecodedAudio(FloatArray(seconds * 16_000), seconds * 16_000, 16_000, 1)
        }

        override fun displayName(source: AudioSource): String = (source as AudioSource.Local).file.name
    }

    /** One note per window at its middle, or none for a silent model. */
    private inner class Model(private val notes: Boolean) : WindowModel {
        private val onset = FloatArray(1001 * 88)
        private val frame = FloatArray(1001 * 88)

        init {
            if (notes) {
                onset[500 * 88 + 39] = 0.9f
                for (f in 501 until 540) frame[f * 88 + 39] = 0.8f
            }
        }

        override fun run(window: FloatArray): WindowOutputs {
            threads += Thread.currentThread().name
            windowGate?.await(5, TimeUnit.SECONDS)
            modelError?.let { throw it }
            return WindowOutputs(onset, FloatArray(1001 * 88), frame, FloatArray(1001 * 88) { 0.5f }, FloatArray(1001), FloatArray(1001), FloatArray(1001))
        }

        override fun close() = Unit
    }

    private var silentModel = false

    /** What the transcription model throws from its next window, when set (an Error the runtime might raise). */
    private var modelError: Throwable? = null

    /** When set, the library's add has put the piece in (as an import does) and waits here before it answers. */
    private var addGate: CompletableDeferred<Unit>? = null
    private val addStarted = CompletableDeferred<Unit>()

    /** What the library was given: each piece's bytes, composer and file name, and each piece's line. */
    private val addedFiles = mutableListOf<Triple<String, String, ByteArray>>()
    private val described = mutableMapOf<Long, String>()

    private val library = object : StudioLibrary {
        override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long {
            added += title
            addedFiles += Triple(fileName, composer, bytes)
            val id = 100L + added.size
            addStarted.complete(Unit)
            addGate?.await()
            return id
        }

        override suspend fun describe(pieceId: Long, description: String) {
            described[pieceId] = description
        }

        override suspend fun discard(pieceId: Long) = Unit

        override suspend fun exists(pieceId: Long) = true
    }

    /**
     * A composing model that writes key 90 (or 91, a little less likely: the guard allows no more than
     * four of one note in a row) every 25 ticks, 20 long; [composerGate] holds each token; [composerThreads]
     * hears where it ran.
     */
    private inner class Composer : ComposerModel {
        private var read = 0
        private var lastTime = 0

        override fun prefill(tokens: IntArray): FloatArray {
            read = 0
            lastTime = 0
            tokens.forEach(::take)
            composerThreads += Thread.currentThread().name
            return next()
        }

        override fun step(token: Int): FloatArray {
            take(token)
            composerGate?.await(5, TimeUnit.SECONDS)
            return next()
        }

        private fun take(token: Int) {
            if (read > 0 && (read - 1) % 3 == 0) lastTime = token
            read++
        }

        private fun next(): FloatArray = FloatArray(Amt.VOCAB_SIZE) { Float.NEGATIVE_INFINITY }.also { l ->
            when ((read - 1) % 3) {
                0 -> l[lastTime + 25] = 0f
                1 -> l[Amt.DUR_OFFSET + 20] = 0f
                else -> {
                    l[Amt.NOTE_OFFSET + 90] = 1f
                    l[Amt.NOTE_OFFSET + 91] = 0f
                }
            }
        }

        override fun reset() {
            read = 0
        }

        override fun close() = Unit
    }

    private var composerGate: CountDownLatch? = null
    private val composerThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The library's seeds: piece 5 is the Bach fixture, 6 has no notes; [defaultSeed] when none is chosen. */
    private var defaultSeed: Long? = 5L
    private val seeds = object : SeedSource {
        override suspend fun seed(pieceId: Long): SeedPiece? = when (pieceId) {
            5L -> SeedPiece("Prelude in C major", "Johann Sebastian Bach", ComposerFixtures.bach)
            6L -> SeedPiece("Silence", null, SmfParser.parse(SmfBuilder(format = 0).track { tempo(0, 500_000) }.build()))
            7L -> throw SmfException("This file isn't MIDI.")   // a piece whose file no longer parses
            else -> null
        }

        override suspend fun defaultPieceId(): Long? = defaultSeed
    }

    private fun studio(): Studio {
        val models = ModelStore(File(tmp.root, "models"), listOf(tiny, tunes))
        val availability = StudioAvailability({ memory }, { runtimeLoads }, { null }, scope, work)
        val reviewPlayer = object : ReviewPlayer {
            override val state = MutableStateFlow(PlayerState())

            override fun positionMicrosNow(): Long = 0
        }
        val store = object : ReviewStore {
            override val undecided = MutableStateFlow<Set<Long>>(emptySet())

            override suspend fun save(ids: Set<Long>) {
                undecided.value = ids
            }
        }
        return Studio(
            scope = scope,
            availability = availability,
            models = models,
            installer = ModelInstaller(UpdateSource.models, server, models, VerifiedDownloader(server, work) { 10L shl 30 }),
            reader = reader,
            pieces = StudioPieces(library, Locale.US),
            review = StudioReview(store, reviewPlayer, library, scope),
            worker = work,
            online = {
                downloadGate?.await(5, TimeUnit.SECONDS)
                online
            },
            release = { released += it },
            openModel = { Model(notes = !silentModel) },
            clock = { ZonedDateTime.of(2026, 9, 28, 12, 0, 0, 0, ZoneId.of("UTC")) },
            trail = { trailed += it },
            transcriptionModel = tiny,
            seeds = seeds,
            openComposer = { Composer() },
            composerModel = tunes,
            random = { 24L },
        ).also { it.start() }
    }

    private fun recording(name: String) = AudioSource.Local(File(tmp.root, name))

    private suspend fun Studio.settled(): List<StudioJob> = withTimeout(10_000) { jobs.jobs.first { list -> list.isNotEmpty() && list.all { it.state.finished } } }

    @Test
    fun `a transcription asked for without its model downloads it first, then runs on the job thread and adds the piece`() = runBlocking {
        val studio = studio()
        val job = studio.transcribe(recording("30-take.wav"))
        val done = studio.settled()
        assertEquals(listOf(JobKind.Download, JobKind.Transcribe), done.map { it.kind })
        assertEquals(listOf(JobState.Done, JobState.Done), done.map { it.state })
        assertEquals("Tiny model", done[0].name)
        assertEquals(setOf("tiny"), studio.models.installed.value)
        val made = done.single { it.id == job.id }
        assertEquals("30-take", made.title)
        assertEquals(101L, made.pieceId)
        assertEquals(1f, made.progress)
        assertEquals(listOf("30-take"), added)
        assertEquals("the piece waits for Keep or Discard", setOf(101L), studio.review.undecided.value)
        assertTrue("read and transcribed on the job thread: ${threads.toSet()}", threads.isNotEmpty() && threads.all { it.startsWith("studio-test-worker") })
        assertEquals(listOf<AudioSource>(recording("30-take.wav")), released)
        val figures = trailed.single()
        assertTrue("the figures on the trail, no name: $figures", figures.startsWith("Studio: transcribed 30.0 s of audio in ") && "take" !in figures)
    }

    @Test
    fun `jobs run one at a time, in order`() = runBlocking {
        val studio = studio()
        windowGate = CountDownLatch(1)
        val first = studio.transcribe(recording("10-a.wav"))
        val second = studio.transcribe(recording("10-b.wav"))
        withTimeout(5_000) { studio.jobs.jobs.first { list -> list.any { it.id == first.id && it.step == JobStep.Transcribing } } }
        assertEquals(JobState.Queued, studio.jobs.get(second.id)!!.state)
        windowGate!!.countDown()
        studio.settled()
        assertEquals(listOf("10-a", "10-b"), added)
    }

    @Test
    fun `the memory gate, the recording and a silent result refuse in words, and the recording is given back`() = runBlocking {
        val studio = studio()
        studio.download(tiny)
        studio.settled()
        memory = memory.copy(availMem = memory.threshold + (899L shl 20))
        val busy = studio.transcribe(recording("10-busy.wav"))
        assertEquals(StudioFailures.BUSY, studio.settled().single { it.id == busy.id }.error)
        memory = memory.copy(availMem = memory.threshold + (900L shl 20))
        val bad = studio.transcribe(recording("bad.wav"))
        assertEquals(AudioFailure.UNREADABLE, studio.settled().single { it.id == bad.id }.error)
        silentModel = true
        val silent = studio.transcribe(recording("10-silence.wav"))
        assertEquals(StudioFailures.NO_NOTES, studio.settled().single { it.id == silent.id }.error)
        assertEquals(3, released.size)
        assertTrue(added.isEmpty())
    }

    @Test
    fun `a device the runtime can't load on refuses`() = runBlocking {
        runtimeLoads = false
        val studio = studio()
        val job = studio.transcribe(recording("10-x.wav"))
        assertEquals(StudioFailures.UNAVAILABLE, studio.settled().single { it.id == job.id }.error)
    }

    @Test
    fun `memory running short between windows stops the transcription`() = runBlocking {
        val studio = studio()
        studio.download(tiny)
        studio.settled()
        windowGate = CountDownLatch(1)
        val running = studio.transcribe(recording("60-long.wav"))
        withTimeout(5_000) { studio.jobs.jobs.first { list -> list.any { it.id == running.id && it.step == JobStep.Transcribing } } }
        memory = memory.copy(lowMemory = true)
        windowGate!!.countDown()
        assertEquals(StudioFailures.RAN_OUT, studio.settled().single { it.id == running.id }.error)
    }

    @Test
    fun `cancel stops a job waiting before it starts and one running between windows`() = runBlocking {
        val studio = studio()
        studio.download(tiny)
        studio.settled()
        windowGate = CountDownLatch(1)
        val running = studio.transcribe(recording("60-a.wav"))
        val waiting = studio.transcribe(recording("10-b.wav"))
        withTimeout(5_000) { studio.jobs.jobs.first { list -> list.any { it.id == running.id && it.step == JobStep.Transcribing } } }
        studio.cancel(waiting.id)
        assertEquals(JobState.Cancelled, studio.jobs.get(waiting.id)!!.state)
        studio.cancel(running.id)
        windowGate!!.countDown()
        val done = studio.settled()
        assertEquals(JobState.Cancelled, done.single { it.id == running.id }.state)
        assertTrue(added.isEmpty())
        withTimeout(2_000) { while (released.size < 2) delay(10) }
        assertEquals("both recordings given back", 2, released.size)
    }

    @Test
    fun `a download needs the network, is asked for once, and a model in use or on its way can't be removed`() = runBlocking {
        online = false
        val studio = studio()
        val offline = studio.download(tiny)!!
        assertEquals(StudioFailures.OFFLINE, studio.settled().single { it.id == offline.id }.error)
        online = true
        downloadGate = CountDownLatch(1)
        val first = studio.download(tiny)!!
        assertEquals("already on its way", null, studio.download(tiny))
        assertFalse(studio.remove(tiny))
        downloadGate!!.countDown()
        studio.settled()
        assertEquals(JobState.Done, studio.jobs.get(first.id)!!.state)
        assertEquals("installed: nothing to download", null, studio.download(tiny))
        assertTrue(studio.remove(tiny))
        assertEquals(emptySet<String>(), withTimeout(2_000) { studio.models.installed.first { it.isEmpty() } })
    }

    private fun order(pieceId: Long? = 5L, minutes: Int = 1, mood: Mood = Mood.Calm, key: MusicKey? = null, bpm: Int? = null) =
        ComposeOrder(pieceId, ComposeRequest(mood, key, bpm, minutes))

    @Test
    fun `a composition asked for without its model downloads it first, then writes only new music at its tempo, and waits for Keep or Discard`() = runBlocking {
        val studio = studio()
        val job = studio.compose(order(key = MusicKey(2, false), bpm = 96), name = "Prelude in C major")
        assertEquals("In the manner of Prelude in C major", dev.stevenjin.stevenpiano.ui.StudioCopy.jobTitle(studio.jobs.get(job.id)!!))
        val done = studio.settled()
        assertEquals(listOf(JobKind.Download, JobKind.Compose), done.map { it.kind })
        assertEquals(listOf(JobState.Done, JobState.Done), done.map { it.state })
        assertEquals(setOf("tunes"), studio.models.installed.value)
        val made = done.single { it.id == job.id }
        val at = ZonedDateTime.of(2026, 9, 28, 12, 0, 0, 0, ZoneId.of("UTC"))
        val title = StudioPieces(library, Locale.US).compositionTitle(at)
        assertTrue(title, title.startsWith("Composition · Sep 28, 2026 12:00"))
        assertEquals(title, made.title)
        assertEquals(101L, made.pieceId)
        assertEquals(1f, made.progress)
        assertEquals("the seed's title names the job", "Prelude in C major", made.name)
        assertEquals(listOf(title), added)
        val (fileName, composer, bytes) = addedFiles.single()
        assertEquals("$title.mid", fileName)
        assertEquals("Made in Studio", composer)
        assertEquals("Made in Studio · in the manner of Prelude in C major (Johann Sebastian Bach)", described[101L])
        assertEquals(setOf(101L), studio.review.undecided.value)

        // The file: at the chosen tempo (96 bpm), only what the model wrote (keys 90 and 91; the seed's keys are 60-84, moved up 2 for D major), from its start.
        val piece = SmfParser.parse(bytes)
        assertEquals(625_000, piece.tempoMap.tempoAt(0))
        assertEquals(title, piece.sequenceName)
        assertTrue("only the new music: ${(0 until piece.noteCount).map { piece.notes.note(it) }.toSet()}", (0 until piece.noteCount).all { piece.notes.note(it) in 90..91 })
        assertTrue(piece.noteCount > 150)
        assertTrue("it starts on its first beat", piece.notes.startMicros[0] < 625_000)
        assertTrue("about a minute", piece.durationMicros in 50_000_000L..61_000_000L)
        assertTrue("composed on the job thread: ${composerThreads.toSet()}", composerThreads.isNotEmpty() && composerThreads.all { it.startsWith("studio-test-worker") })
        val figures = trailed.single()
        assertTrue("the figures on the trail, no title: $figures", figures.startsWith("Studio: composed ") && "Prelude" !in figures && "seed 24" in figures && "96 bpm" in figures)
    }

    @Test
    fun `a composition's gate and seeds refuse in words`() = runBlocking {
        val studio = studio()
        studio.download(tunes)
        studio.settled()
        memory = memory.copy(availMem = memory.threshold + (699L shl 20))
        val busy = studio.compose(order())
        assertEquals(StudioFailures.BUSY, studio.settled().single { it.id == busy.id }.error)
        memory = memory.copy(availMem = memory.threshold + (700L shl 20))
        val gone = studio.compose(order(pieceId = 99L))
        assertEquals(ComposeFailures.SEED_GONE, studio.settled().single { it.id == gone.id }.error)
        val silent = studio.compose(order(pieceId = 6L))
        assertEquals(ComposeFailures.NO_SEED, studio.settled().single { it.id == silent.id }.error)
        defaultSeed = null
        val empty = studio.compose(order(pieceId = null))
        assertEquals(ComposeFailures.EMPTY_LIBRARY, studio.settled().single { it.id == empty.id }.error)
        assertTrue(added.isEmpty())
        defaultSeed = 5L
        val fallback = studio.compose(order(pieceId = null))
        val ended = studio.settled().single { it.id == fallback.id }
        assertEquals("no piece chosen: the default seed", JobState.Done, ended.state)
        assertEquals("Prelude in C major", ended.name)
    }

    @Test
    fun `cancel stops a composition between tokens, and memory running short stops another`() = runBlocking {
        val studio = studio()
        studio.download(tunes)
        studio.settled()
        composerGate = CountDownLatch(1)
        val running = studio.compose(order())
        withTimeout(5_000) { studio.jobs.jobs.first { list -> list.any { it.id == running.id && it.step == JobStep.Composing } } }
        assertFalse("the model in use can't be removed", studio.remove(tunes))
        studio.cancel(running.id)
        composerGate!!.countDown()
        assertEquals(JobState.Cancelled, studio.settled().single { it.id == running.id }.state)
        assertTrue(added.isEmpty())

        composerGate = CountDownLatch(1)
        val short = studio.compose(order())
        withTimeout(5_000) { studio.jobs.jobs.first { list -> list.any { it.id == short.id && it.step == JobStep.Composing } } }
        memory = memory.copy(lowMemory = true)
        composerGate!!.countDown()
        assertEquals(ComposeFailures.RAN_OUT, studio.settled().single { it.id == short.id }.error)
        assertTrue(added.isEmpty())
    }

    @Test
    fun `composing reports how far along it is, and the hub and the page say so`() = runBlocking {
        val studio = studio()
        studio.download(tunes)
        studio.settled()
        composerGate = CountDownLatch(1)
        val job = studio.compose(order(minutes = 1))
        withTimeout(5_000) { studio.jobs.jobs.first { list -> list.any { it.id == job.id && it.step == JobStep.Composing } } }
        assertEquals("Composing 0%", dev.stevenjin.stevenpiano.ui.StudioCopy.hub(1, studio.jobs.jobs.value))
        composerGate!!.countDown()
        val seen = mutableListOf<Float>()
        withTimeout(10_000) {
            studio.jobs.jobs.first { list ->
                list.single { it.id == job.id }.let { j -> if (j.step == JobStep.Composing) j.progress?.let(seen::add); j.state.finished }
            }
        }
        assertTrue("progress only grows: $seen", seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(JobState.Done, studio.jobs.get(job.id)!!.state)
    }

    @Test
    fun `the seed choice is the piece with its key and tempo, the default one when none is chosen`() = runBlocking {
        val studio = studio()
        val bach = studio.seedChoice(null)!!
        assertEquals(5L, bach.pieceId)
        assertEquals("Prelude in C major", bach.title)
        assertEquals("Johann Sebastian Bach", bach.composer)
        assertEquals(MusicKey.C, bach.facts.key)
        assertEquals(120, bach.facts.bpm)
        assertEquals(null, studio.seedChoice(99L))
        defaultSeed = null
        assertEquals(null, studio.seedChoice(null))
    }

    @Test
    fun `a seed that can't be read is no choice, and the sheet's call never throws (audit delta 2)`() = runBlocking {
        val studio = studio()
        // Before the audit the parser's refusal came out of seedChoice, into the compose sheet's produceState.
        assertEquals(null, studio.seedChoice(7L))
        defaultSeed = 7L
        assertEquals(null, studio.seedChoice(null))
        studio.download(tunes)
        studio.settled()
        val job = studio.compose(order(pieceId = 7L))
        assertEquals("a job fails in words, Studio goes on", JobState.Failed, studio.settled().single { it.id == job.id }.state)
        assertTrue(added.isEmpty())
    }

    @Test
    fun `a cancel while the piece is saved changes nothing - it is kept and waits for Keep or Discard (audit delta 2)`() = runBlocking {
        val studio = studio()
        studio.download(tiny)
        studio.settled()
        addGate = CompletableDeferred()
        val job = studio.transcribe(recording("10-late.wav"))
        withTimeout(5_000) { addStarted.await() }
        assertEquals(JobStep.Saving, studio.jobs.get(job.id)!!.step)
        studio.cancel(job.id)   // the notification's Cancel, as the import puts the piece in
        addGate!!.complete(Unit)
        val ended = studio.settled().single { it.id == job.id }
        // Before: "Cancelled", while the piece stayed in the library with no Keep or Discard ever asked.
        assertEquals(JobState.Done, ended.state)
        assertEquals(101L, ended.pieceId)
        assertEquals(listOf("10-late"), added)
        assertEquals(setOf(101L), studio.review.undecided.value)
        assertEquals(listOf<AudioSource>(recording("10-late.wav")), released)
    }

    @Test
    fun `an Error from the runtime fails the job in words, and Studio goes on (audit delta 2)`() = runBlocking {
        val studio = studio()
        studio.download(tiny)
        studio.settled()
        modelError = StackOverflowError()
        val broken = studio.transcribe(recording("10-broken.wav"))
        // Before: the Error left the job "Running" for good (on the tablet it crashed the app, notification and all).
        val ended = studio.settled().single { it.id == broken.id }
        assertEquals(JobState.Failed, ended.state)
        assertEquals(StudioFailures.FAILED, ended.error)
        assertEquals(listOf<AudioSource>(recording("10-broken.wav")), released)
        modelError = null
        val next = studio.transcribe(recording("10-next.wav"))
        assertEquals(JobState.Done, studio.settled().single { it.id == next.id }.state)
        assertFalse(studio.jobs.busy)
    }
}
