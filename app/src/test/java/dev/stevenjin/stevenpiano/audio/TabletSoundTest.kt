// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.MidiSink
import dev.stevenjin.stevenpiano.player.PlaybackEngine
import dev.stevenjin.stevenpiano.studio.ModelEntry
import dev.stevenjin.stevenpiano.studio.ModelInstaller
import dev.stevenjin.stevenpiano.studio.ModelKind
import dev.stevenjin.stevenpiano.studio.ModelStore
import dev.stevenjin.stevenpiano.update.FakeUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The tablet's sound as the app holds it (v1.8 — M25): the modes, the sink that passes the player's
 * messages only while the tablet sounds (and silences it the moment it stops), the SoundFont's download
 * through the verified path with its failures in words, cancel and remove, and the tee that puts the
 * tablet beside the link without ever going before it. The fixture piano plays the SoundFont's part.
 */
class TabletSoundTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val bytes = Sf2Fixture.piano()
    private val fixture = ModelEntry(
        name = "fixture-piano", version = 1, file = "fixture-piano-v1.sf2",
        url = "https://github.com/${UpdateSource.REPOSITORY}/releases/download/models/fixture-piano-v1.sf2",
        sizeBytes = bytes.size.toLong(), sha256 = VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        licence = "CC0-1.0", attribution = "fixture", title = "Fixture piano", licenceLabel = "CC0", use = "", kind = ModelKind.Sound,
    )
    private val dir get() = File(tmp.root, "models")
    private val server = FakeUpdateServer(list()).apply { chunk = 16 * 1024 }

    /** [server], but a pause of [pauseMs] before each chunk while it is set: a download slow enough to cancel. */
    private var pauseMs: Long? = null
    private val slow = object : UpdateServer {
        override suspend fun manifest(): String = server.manifest()

        override suspend fun download(url: String, cap: Long, sink: (ByteArray, Int) -> Unit) {
            val bytes = server.files[url] ?: throw java.io.IOException("HTTP 404")
            server.downloads += url
            var at = 0
            while (at < bytes.size) {
                pauseMs?.let { delay(it) }
                val end = minOf(bytes.size, at + server.chunk)
                sink(bytes.copyOfRange(at, end), end - at)
                at = end
            }
        }
    }
    private var online = true
    private val voice = PianoVoice(48_000)

    private fun list(): String = JSONObject().put("models", JSONArray()).put(
        "sounds",
        JSONArray().put(
            JSONObject().put("name", fixture.name).put("version", 1).put("file", fixture.file).put("url", fixture.url)
                .put("sizeBytes", fixture.sizeBytes).put("sha256", fixture.sha256).put("licence", "CC0-1.0").put("attribution", "fixture"),
        ),
    ).toString()

    private fun sound(): TabletSound {
        val store = ModelStore(dir, listOf(fixture), alsoKept = emptyList())
        val installer = ModelInstaller(UpdateSource.models, slow, store, VerifiedDownloader(slow, Dispatchers.Unconfined) { 10L shl 30 })
        return TabletSound(scope, store, installer, { online }, voice, fixture, io = Dispatchers.IO)
    }

    private fun installed(): TabletSound {
        dir.mkdirs()
        File(dir, fixture.file).writeBytes(bytes)
        return sound().also { it.start() }
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!check()) {
            if (System.nanoTime() > until) throw AssertionError("never: $what")
            Thread.sleep(5)
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }

    private fun noteOn(key: Int) = MidiBatch().apply { add(0x90, key, 100) }

    @After
    fun tearDown() {
        scope.cancel()
        main.close()
    }

    @Test
    fun `the mode says when the tablet sounds`() {
        assertFalse(TabletSoundMode.OFF.sounds(connected = false))
        assertFalse(TabletSoundMode.OFF.sounds(connected = true))
        assertTrue(TabletSoundMode.WHEN_NOT_CONNECTED.sounds(connected = false))
        assertFalse("never doubling the real piano", TabletSoundMode.WHEN_NOT_CONNECTED.sounds(connected = true))
        assertTrue(TabletSoundMode.ALWAYS.sounds(connected = true))
        val waiting = TabletSoundState(TabletSoundMode.WHEN_NOT_CONNECTED, connected = false, installed = false)
        assertTrue(waiting.wanted)
        assertFalse(waiting.active)
        assertTrue("the download is offered", waiting.needsDownload)
        assertTrue(waiting.copy(installed = true).active)
        assertFalse(waiting.copy(installed = true, connected = true).active)
        assertFalse(waiting.copy(mode = TabletSoundMode.OFF).needsDownload)
        assertEquals(TabletSoundMode.WHEN_NOT_CONNECTED, TabletSoundState().mode)
        assertEquals(60, TabletSoundState().volume)
    }

    @Test
    fun `the sink plays while the tablet sounds, and goes silent the moment the piano connects`() = runBlocking {
        val sound = installed()
        onMain { sound.follow(TabletSoundMode.WHEN_NOT_CONNECTED, 100, connected = false) }
        eventually("loaded and active") { sound.active }
        sound.sink.send(noteOn(60), false)
        assertTrue(voice.pending())
        val out = FloatArray(4_800)
        assertTrue(voice.render(out, out.size))
        assertTrue(out.any { it != 0f })

        onMain { sound.follow(TabletSoundMode.WHEN_NOT_CONNECTED, 100, connected = true) }
        assertFalse(sound.active)
        // Within one note: what sounded fades out over the fade, and nothing more is queued.
        voice.render(out, out.size)
        assertEquals(0f, out.copyOfRange(out.size / 2, out.size).maxOf { kotlin.math.abs(it) })
        sound.sink.send(noteOn(64), false)
        assertFalse(voice.pending())
        assertFalse(voice.render(out, out.size))

        onMain { sound.follow(TabletSoundMode.ALWAYS, 100, connected = true) }
        assertTrue("Always plays beside the piano", sound.active)
        assertTrue(sound.keepOpen.not())
        sound.playing(true)
        assertTrue(sound.keepOpen)
    }

    @Test
    fun `off lets go of the SoundFont, and turning it on again reads it back`() = runBlocking {
        val sound = installed()
        onMain { sound.follow(TabletSoundMode.ALWAYS, 60, connected = false) }
        eventually("loaded") { voice.loaded }
        assertEquals(60, voice.volume)
        onMain { sound.follow(TabletSoundMode.OFF, 60, connected = false) }
        assertFalse(voice.loaded)
        assertFalse(sound.active)
        voice.drain()   // the silence queued as it stopped
        sound.sink.send(noteOn(60), false)
        assertFalse(voice.pending())
        onMain { sound.follow(TabletSoundMode.WHEN_NOT_CONNECTED, 35, connected = false) }
        eventually("read again") { sound.active }
        assertEquals(35, voice.volume)
    }

    @Test
    fun `the download goes through the verified path, then the tablet sounds`() = runBlocking {
        server.files[fixture.url] = bytes
        val sound = sound()
        sound.start()
        onMain { sound.follow(TabletSoundMode.WHEN_NOT_CONNECTED, 60, connected = false) }
        eventually("read the folder") { !sound.state.value.installed && sound.state.value.needsDownload }
        val seen = mutableListOf<SoundDownload?>()
        val watch = scope.launchCollect(sound) { seen += it.download }
        onMain { sound.download() }
        eventually("installed") { sound.state.value.installed }
        eventually("active") { sound.active }
        assertNull(sound.state.value.download)
        assertEquals(bytes.size.toLong(), File(dir, fixture.file).length())
        assertTrue("its progress was shown", seen.any { it is SoundDownload.Running })
        watch.cancel()
        assertEquals(listOf(fixture.url), server.downloads)
    }

    @Test
    fun `a download that fails says why, in the piano sound's words`() = runBlocking {
        val sound = sound()
        sound.start()
        online = false
        onMain { sound.download() }
        eventually("failed") { sound.state.value.download is SoundDownload.Failed }
        assertEquals("Downloading the piano sound needs an internet connection.", (sound.state.value.download as SoundDownload.Failed).line)
        online = true
        server.files[fixture.url] = bytes.copyOf().also { it[5_000] = (it[5_000] + 1).toByte() }
        onMain { sound.download() }
        eventually("failed again") { (sound.state.value.download as? SoundDownload.Failed)?.line?.contains("didn't match") == true }
        assertEquals("The download didn't match the piano sound; try again.", (sound.state.value.download as SoundDownload.Failed).line)
        assertFalse(sound.state.value.installed)
        assertFalse(File(dir, fixture.file).exists())
        assertEquals("the models' own line otherwise", "Couldn't reach the download server.", TabletSound.failureLine("Couldn't reach the download server."))
    }

    @Test
    fun `cancel stops the download and remove deletes the SoundFont`() = runBlocking {
        server.files[fixture.url] = bytes
        pauseMs = 50
        val sound = sound()
        sound.start()
        onMain { sound.download() }
        eventually("under way") { sound.state.value.downloading }
        onMain { sound.cancelDownload() }
        eventually("stopped") { sound.state.value.download == null }
        pauseMs = null
        assertFalse(sound.state.value.installed)

        onMain { sound.download() }
        eventually("installed") { sound.state.value.installed }
        onMain { sound.follow(TabletSoundMode.ALWAYS, 60, connected = true) }
        eventually("active") { sound.active }
        onMain { sound.remove() }
        assertFalse(sound.active)
        assertFalse(voice.loaded)
        eventually("deleted") { !File(dir, fixture.file).exists() }
        assertFalse(sound.state.value.installed)
        assertTrue(sound.state.value.needsDownload)
    }

    @Test
    fun `the tee sends to the link first, then the tablet, the same messages at the same moment`() {
        val order = mutableListOf<String>()
        val link = FakePianoLink { 0L }
        val first = MidiSink { batch, drop -> order += "link ${batch.size}"; link.send(batch, drop) }
        val tablet = mutableListOf<String>()
        val second = MidiSink { batch, _ -> order += "tablet ${batch.size}"; for (i in 0 until batch.size) tablet += "%02X %02X %02X".format(batch.status(i), batch.data1(i), batch.data2(i)) }
        val engine = PlaybackEngine(TeeSink(first, second))
        engine.liveNoteOn(60, 100, 0L)
        engine.liveSustain(true)
        engine.liveNoteOff(60)
        engine.stop(0L)
        assertEquals(link.messages, tablet)
        assertEquals(listOf("90 3C 64", "B0 40 7F", "80 3C 00", "B0 40 00", "B0 7B 00"), tablet)
        assertEquals(listOf("link 1", "tablet 1", "link 1", "tablet 1", "link 1", "tablet 1", "link 2", "tablet 2"), order)
    }

    /** Collects [sound]'s state in the scope, handing each to [each]. */
    private fun CoroutineScope.launchCollect(sound: TabletSound, each: (TabletSoundState) -> Unit) =
        launch(Dispatchers.Unconfined) { sound.state.collect { each(it) } }
}
