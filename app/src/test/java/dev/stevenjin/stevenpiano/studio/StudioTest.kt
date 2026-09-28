// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.update.FakeUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
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
 * Studio's jobs (v1.7 — M23): one at a time on their own thread, a transcription after its model's
 * download, the memory gates, cancelling, every failure a job's line, and the recording given back.
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
    private val server = FakeUpdateServer(
        JSONObject().put(
            "models",
            JSONArray().put(
                JSONObject().put("name", "tiny").put("version", 1).put("file", "tiny-v1.onnx").put("url", modelUrl)
                    .put("sizeBytes", modelBytes.size).put("sha256", tiny.sha256).put("licence", "CC0-1.0").put("attribution", "Tiny"),
            ),
        ).toString(),
    ).also { it.files[modelUrl] = modelBytes }

    private var memory = MemorySnapshot(4L shl 30, 3L shl 30, 200L shl 20, lowMemory = false)
    private var online = true
    private var downloadGate: CountDownLatch? = null
    private var runtimeLoads = true
    private val released: MutableList<AudioSource> = Collections.synchronizedList(mutableListOf())
    private val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val added = mutableListOf<String>()
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
            return WindowOutputs(onset, FloatArray(1001 * 88), frame, FloatArray(1001 * 88) { 0.5f }, FloatArray(1001), FloatArray(1001), FloatArray(1001))
        }

        override fun close() = Unit
    }

    private var silentModel = false

    private val library = object : StudioLibrary {
        override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long {
            added += title
            return 100L + added.size
        }

        override suspend fun describe(pieceId: Long, description: String) = Unit

        override suspend fun discard(pieceId: Long) = Unit
    }

    private fun studio(): Studio {
        val models = ModelStore(File(tmp.root, "models"), listOf(tiny))
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
            transcriptionModel = tiny,
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
}
