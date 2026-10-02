// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.record

import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.midi.SmfWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A take into the library (v1.11 — M29): its file, its names, the playlist, the review, the kiosk's cap, a crash's leftovers. */
class RecordingPiecesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The library and the review, as a recording sees them; it refuses a file it already holds, as the importer does. */
    private class FakeStore : RecordingStore {
        val files = mutableMapOf<Long, ByteArray>()
        val titles = mutableMapOf<Long, String>()
        val composers = mutableMapOf<Long, String>()
        val fileNames = mutableMapOf<Long, String>()
        val descriptions = mutableMapOf<Long, String>()
        val recordingsList = ArrayDeque<Long>()
        val waiting = LinkedHashSet<Long>()
        val discarded = mutableListOf<Long>()
        val shas = HashSet<String>()
        var refuse = false
        private var next = 1L

        override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long? {
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != "MThd") return null   // no MIDI file: the importer's parse refuses it
            if (refuse || !shas.add(sha)) return null
            val id = next++
            files[id] = bytes
            titles[id] = title
            composers[id] = composer
            fileNames[id] = fileName
            return id
        }

        override suspend fun describe(pieceId: Long, description: String) {
            descriptions[pieceId] = description
        }

        override suspend fun addToRecordings(pieceId: Long) {
            recordingsList.remove(pieceId)
            recordingsList.addFirst(pieceId)
        }

        override suspend fun recordings(): List<Long> = recordingsList.toList()

        override suspend fun markUndecided(pieceId: Long) {
            waiting += pieceId
        }

        override suspend fun undecided(): Set<Long> = waiting.toSet()

        override suspend fun discard(pieceId: Long) {
            discarded += pieceId
            waiting -= pieceId
            recordingsList.remove(pieceId)
        }
    }

    private val store = FakeStore()
    private var kiosk = false
    private val zone = ZoneId.of("America/New_York")
    private val at = ZonedDateTime.of(2026, 10, 1, 14, 5, 33, 0, zone)
    private val pieces by lazy { RecordingPieces(store, tmp.root.resolve("recordings"), kiosk = { kiosk }, zone = { zone }, locale = Locale.US) }

    /** "Recording · Oct 1, 2026 2:05 PM", the time as the JDK writes it (its space before PM varies by version). */
    private val title = "Recording · Oct 1, 2026 " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.US).format(at)

    private val take = Take(
        notes = listOf(SmfWriter.Note(0, 400_000, 60, 90), SmfWriter.Note(250_000, 900_000, 21, 40)),
        controls = listOf(SmfWriter.Control(100_000, 64, 70), SmfWriter.Control(900_000, 64, 0)),
        durationMicros = 900_000,
        ended = TakeEnd.Stopped,
    )

    @Test
    fun `a take becomes a piece by Recorded live, described, first in Recordings and waiting, its file as played`() = runBlocking {
        val saved = pieces.save(take, at)!!
        assertEquals(SavedRecording(1, title, 900_000, 2), saved)
        assertEquals("Recorded live", store.composers[1])
        assertEquals("Recording 2026-10-01 14-05-33.mid", store.fileNames[1])
        assertEquals("Recorded live · Oct 1, 2026", store.descriptions[1])
        assertTrue(RecordingPieces.isRecording(store.descriptions[1]))
        assertEquals(listOf(1L), store.recordings())
        assertEquals(setOf(1L), store.waiting)
        val piece = SmfParser.parse(store.files.getValue(1))
        assertEquals(title, piece.sequenceName)
        assertEquals(listOf("Recorded live, 2026-10-01 14:05:33"), piece.texts)
        assertEquals(listOf(21, 60), (0 until piece.noteCount).map { piece.notes.note(it) }.sorted())
        assertEquals("every pedal value kept", listOf(70, 0), piece.events.filter { it.command == 0xB0 && it.data1 == 64 }.map { it.data2 })
        assertEquals("the file kept until the library had it", emptyList<String>(), tmp.root.resolve("recordings").list().orEmpty().toList())
    }

    @Test
    fun `newest first in Recordings, and two takes in one second are still two files`() = runBlocking {
        val first = pieces.save(take, at)!!
        val second = pieces.save(take, at)!!   // the same notes, the same second: the library would refuse the same bytes
        assertTrue(first.pieceId != second.pieceId)
        assertEquals(listOf(second.pieceId, first.pieceId), store.recordings())
        assertEquals(listOf("Recorded live, 2026-10-01 14:05:33 (2)"), SmfParser.parse(store.files.getValue(second.pieceId)).texts)
    }

    @Test
    fun `in kiosk mode at most 30 recordings wait for the PIN, and the oldest waiting goes`() = runBlocking {
        kiosk = true
        repeat(31) { i -> pieces.save(take, at.plusSeconds(i.toLong()))!! }
        assertEquals(listOf(1L), store.discarded)
        assertEquals(30, store.waiting.size)
        assertFalse(1L in store.recordings())
        kiosk = false
        pieces.save(take, at.plusSeconds(99))
        assertEquals("outside kiosk mode, no cap", 31, store.waiting.size)
    }

    @Test
    fun `a take the library would not take keeps its file, and the next start saves it`() = runBlocking {
        store.refuse = true
        assertNull(pieces.save(take, at))
        val left = tmp.root.resolve("recordings").listFiles().orEmpty().map { it.name }
        assertEquals(listOf("pending-" + at.toInstant().toEpochMilli() + ".mid"), left)
        store.refuse = false
        assertEquals(1, pieces.recoverPending())
        assertEquals(title, store.titles[1])
        assertEquals(listOf(1L), store.recordings())
        assertEquals(setOf(1L), store.waiting)
        assertEquals(emptyList<String>(), tmp.root.resolve("recordings").list().orEmpty().toList())
        assertEquals("nothing left to save", 0, pieces.recoverPending())
    }

    @Test
    fun `a file in the folder that is no take is cleared away`() = runBlocking {
        val dir = tmp.root.resolve("recordings").apply { mkdirs() }
        dir.resolve("pending-notatime.mid").writeBytes(byteArrayOf(1, 2, 3))
        dir.resolve("pending-1759341933000.mid").writeBytes(byteArrayOf(1, 2, 3))   // a time, but not a MIDI file
        assertEquals(0, pieces.recoverPending())
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test
    fun `only a recording's own sheet line says it is one`() {
        assertTrue(RecordingPieces.isRecording("Recorded live · Oct 1, 2026"))
        assertFalse(RecordingPieces.isRecording("Made in Studio · Oct 1, 2026"))
        assertFalse(RecordingPieces.isRecording(null))
        assertFalse(RecordingPieces.isRecording("Recorded live in Vienna"))
    }
}

/** A take from the Record control to the sheet (v1.11 — M29), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val clock: () -> Long = { dispatcher.scheduler.currentTime * 1_000_000L }
    private val recorder = Recorder(nanoTime = clock, idleNanos = 5_000_000_000L)
    private val store = object : RecordingStore {
        var added = 0

        override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long = (++added).toLong()

        override suspend fun describe(pieceId: Long, description: String) = Unit

        override suspend fun addToRecordings(pieceId: Long) = Unit

        override suspend fun recordings(): List<Long> = emptyList()

        override suspend fun markUndecided(pieceId: Long) = Unit

        override suspend fun undecided(): Set<Long> = emptySet()

        override suspend fun discard(pieceId: Long) = Unit
    }
    private val session by lazy {
        RecordingSession(
            recorder,
            RecordingPieces(store, tmp.root, kiosk = { false }, locale = Locale.US),
            scope,
            io = dispatcher,
            now = { ZonedDateTime.of(2026, 10, 1, 14, 5, 33, 0, ZoneId.of("UTC")) },
            nanoTime = clock,
        )
    }

    @Test
    fun `Record, a few keys, then Stop, and it is saved and asking`() {
        session.start()
        assertTrue(session.state.value is RecordingState.Recording)
        assertTrue(session.recording)
        scope.advanceTimeBy(500)
        recorder.screenKey(true, 60, 80)
        scope.advanceTimeBy(700)
        recorder.screenKey(false, 60, 0)
        session.stop()
        assertEquals(RecordingState.Saving, session.state.value)
        scope.runCurrent()
        val saved = session.state.value as RecordingState.Saved
        assertEquals(700_000L, saved.recording.durationMicros)
        assertEquals(1, saved.recording.notes)
        assertEquals(TakeEnd.Stopped, saved.ended)
        session.answered()
        assertEquals(RecordingState.Idle, session.state.value)
    }

    @Test
    fun `a take with no note says so for a moment and saves nothing`() {
        session.start()
        scope.advanceTimeBy(800)
        session.stop()
        assertEquals(RecordingState.Empty, session.state.value)
        scope.advanceTimeBy(RecordingSession.EMPTY_SHOWN_MS + 1)
        assertEquals(RecordingState.Idle, session.state.value)
        assertEquals(0, store.added)
    }

    @Test
    fun `five minutes of silence end the take by itself, and it is saved`() {
        session.start()
        recorder.screenKey(true, 60, 80)
        recorder.screenKey(false, 60, 0)
        scope.advanceTimeBy(4_500)
        assertTrue(session.state.value is RecordingState.Recording)
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        val saved = session.state.value as? RecordingState.Saved
        assertNotNull(saved)
        assertEquals(TakeEnd.Silence, saved!!.ended)
        assertFalse(session.recording)
    }

    @Test
    fun `the take's time reads as a clock`() {
        assertEquals("0:00", RecordingSession.clock(0))
        assertEquals("0:42", RecordingSession.clock(42_900_000_000L))
        assertEquals("12:05", RecordingSession.clock(725_000_000_000L))
        assertEquals("1:00:00", RecordingSession.clock(3_600_000_000_000L))
    }
}
