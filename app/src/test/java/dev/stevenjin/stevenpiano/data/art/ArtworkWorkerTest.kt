// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import dev.stevenjin.stevenpiano.net.FakeWikipedia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The artwork worker against a fake Wikipedia, on virtual time: every millisecond here is the test scheduler's. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkWorkerTest {
    private val dao = FakeArtworkDao()
    private var online = true
    private var wall = 1_780_000_000_000L
    private val logs = mutableListOf<String>()

    private val debussy = ArtKey.Composer("debussy", "Claude Debussy")
    private val chopin = ArtKey.Composer("chopin", "Frédéric Chopin")
    private val bach = ArtKey.Composer("bach", "Johann Sebastian Bach")
    private val clair = ArtKey.Piece(7, "Clair de lune", "Claude Debussy")

    private fun TestScope.wiki(latencyMs: Long = 100): FakeWikipedia =
        FakeWikipedia(now = { testScheduler.currentTime }, latencyMs = latencyMs).apply {
            page("Claude Debussy", "Claude Debussy was a French composer.", image = "https://upload.wikimedia.org/d.jpg")
            page("Frédéric Chopin", "Frédéric Chopin was a Polish composer.", image = "https://upload.wikimedia.org/c.jpg")
            page("Johann Sebastian Bach", "Johann Sebastian Bach was a German composer.", image = "https://upload.wikimedia.org/b.jpg")
            searches["Clair de lune Claude Debussy"] = listOf("Suite bergamasque")
            page("Suite bergamasque", "The Suite bergamasque is a piano suite by Claude Debussy.")
        }

    private fun TestScope.worker(wiki: FakeWikipedia): ArtworkWorker = ArtworkWorker(
        store = dao,
        fetcher = ArtworkFetcher(PacedWikiApi(wiki, RequestPacer(now = { testScheduler.currentTime }))),
        images = ImageStore { key, _ -> "art/$key.jpg" },
        online = { online },
        scope = this,   // not backgroundScope: advanceUntilIdle leaves background work alone
        clock = { wall },
        log = { logs += it },
    )

    @Test
    fun `requests go one at a time, their starts at least 250 ms apart`() = runTest {
        val wiki = wiki(latencyMs = 100)
        val worker = worker(wiki)
        assertEquals(3, worker.requestAll(listOf(debussy, chopin, bach), force = false))
        advanceUntilIdle()

        val calls = wiki.calls
        assertEquals(6, calls.size)   // a summary and a portrait each
        for (i in 1 until calls.size) {
            assertTrue("call $i overlaps the one before", calls[i].startedAt >= calls[i - 1].endedAt)
            assertTrue("call $i came too soon", calls[i].startedAt - calls[i - 1].startedAt >= RequestPacer.MIN_GAP_MS)
        }
        assertEquals(listOf(0L, 250L, 500L, 750L, 1_000L, 1_250L), calls.map { it.startedAt })   // the pacer, not the latency, sets the beat
        assertEquals(setOf("composer:debussy", "composer:chopin", "composer:bach"), dao.rows.keys)
        assertTrue(dao.rows.values.all { it.status == ArtworkStatus.OK && it.fetchedAt == wall })
        assertEquals("art/composer:debussy.jpg", dao.rows.getValue("composer:debussy").imagePath)
        assertEquals("Claude Debussy was a French composer.", dao.rows.getValue("composer:debussy").description)
        assertEquals(ArtworkProgress.Idle, worker.progress.value)
    }

    @Test
    fun `quick answers are still spaced 250 ms`() = runTest {
        val wiki = wiki(latencyMs = 0)
        val worker = worker(wiki)
        worker.requestAll(listOf(debussy, chopin), force = false)
        advanceUntilIdle()
        assertEquals(listOf(0L, 250L, 500L, 750L), wiki.calls.map { it.startedAt })
    }

    @Test
    fun `offline, keys are skipped and nothing is recorded`() = runTest {
        val wiki = wiki()
        val worker = worker(wiki)
        online = false
        assertEquals(2, worker.requestAll(listOf(debussy, chopin), force = false))
        advanceUntilIdle()
        assertTrue(wiki.calls.isEmpty())
        assertTrue(dao.rows.isEmpty())
        assertEquals(ArtworkProgress.Idle, worker.progress.value)

        online = true
        assertEquals(2, worker.requestAll(listOf(debussy, chopin), force = false))   // still due: nothing was recorded
        advanceUntilIdle()
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `a failure seen while going offline is not recorded`() = runTest {
        val wiki = wiki()
        wiki.failing = true
        wiki.onCall = { online = false }
        val worker = worker(wiki)
        worker.requestAll(listOf(debussy), force = false)
        advanceUntilIdle()
        assertEquals(1, wiki.calls.size)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a request the person waits on goes to the front, after the fetch under way`() = runTest {
        val wiki = wiki()
        val worker = worker(wiki)
        worker.requestAll(listOf(debussy, chopin, bach), force = false)
        runCurrent()   // Debussy's summary is under way
        worker.request(clair, priority = true, force = false)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "summary Claude Debussy", "download https://upload.wikimedia.org/d.jpg",
                "search Clair de lune Claude Debussy", "summary Suite bergamasque",
                "summary Frédéric Chopin", "download https://upload.wikimedia.org/c.jpg",
                "summary Johann Sebastian Bach", "download https://upload.wikimedia.org/b.jpg",
            ),
            wiki.kinds,
        )
        val notes = dao.rows.getValue("piece:7")
        assertEquals(ArtworkStatus.OK, notes.status)
        assertNull(notes.imagePath)
        assertEquals("https://en.wikipedia.org/wiki/Suite_bergamasque", notes.sourceUrl)
    }

    @Test
    fun `a failure is recorded, then left alone for a day`() = runTest {
        val wiki = wiki()
        wiki.failing = true
        val worker = worker(wiki)
        worker.requestAll(listOf(debussy), force = false)
        advanceUntilIdle()
        assertEquals(ArtworkStatus.FAILED, dao.rows.getValue("composer:debussy").status)
        assertEquals(wall, dao.rows.getValue("composer:debussy").fetchedAt)

        wiki.failing = false
        wall += ArtworkPolicy.RETRY_FAILED_AFTER_MS - 1
        assertEquals(0, worker.requestAll(listOf(debussy), force = false))
        worker.request(debussy, priority = true, force = false)   // not due for a sheet either
        advanceUntilIdle()
        assertEquals(1, wiki.calls.size)

        wall += 1
        assertEquals(1, worker.requestAll(listOf(debussy), force = false))
        advanceUntilIdle()
        assertEquals(ArtworkStatus.OK, dao.rows.getValue("composer:debussy").status)
    }

    @Test
    fun `not found stays not found until forced`() = runTest {
        val wiki = wiki()
        val worker = worker(wiki)
        val nobody = ArtKey.Composer("vallier", "Hortense Vallier")
        worker.requestAll(listOf(nobody), force = false)
        advanceUntilIdle()
        assertEquals(ArtworkStatus.NOT_FOUND, dao.rows.getValue("composer:vallier").status)
        assertEquals(0, worker.requestAll(listOf(nobody), force = false))
        assertEquals(1, worker.requestAll(listOf(nobody), force = true))
        advanceUntilIdle()
        assertEquals(2, wiki.calls.size)
    }

    @Test
    fun `asked to slow down, the worker records nothing, waits, and goes on`() = runTest {
        val wiki = wiki(latencyMs = 0)
        wiki.busy += listOf(5_000L, 5_000L)
        val worker = worker(wiki)
        worker.requestAll(listOf(debussy, chopin), force = false)
        advanceUntilIdle()
        assertFalse("composer:debussy" in dao.rows)
        assertEquals(ArtworkStatus.OK, dao.rows.getValue("composer:chopin").status)
        val chopinStart = wiki.calls.first { it.arg == "Frédéric Chopin" }.startedAt
        val lastBusy = wiki.calls.filter { it.arg == "Claude Debussy" }.maxOf { it.startedAt }
        assertTrue(chopinStart - lastBusy >= 5_000L)
    }

    @Test
    fun `asked to wait more than a minute, the background run stops and the rest waits for the next`() = runTest {
        val wiki = wiki(latencyMs = 0)
        wiki.busy += 3_600_000L
        val worker = worker(wiki)
        worker.requestAll(listOf(debussy, chopin, bach), force = false)
        runCurrent()
        worker.request(clair, priority = true, force = false)   // the person is waiting on this one: it still goes
        advanceUntilIdle()
        assertEquals(setOf("piece:7"), dao.rows.keys)
        assertEquals(listOf("Claude Debussy", "Clair de lune Claude Debussy", "Suite bergamasque"), wiki.calls.map { it.arg })
        assertTrue(testScheduler.currentTime < 60_000)
        assertEquals(ArtworkProgress.Idle, worker.progress.value)
        assertEquals(3, worker.requestAll(listOf(debussy, chopin, bach), force = false))   // nothing was recorded for them
    }

    @Test
    fun `progress counts the background run, not a sheet's own request`() = runTest {
        val wiki = wiki()
        val worker = worker(wiki)
        worker.requestAll(listOf(debussy, chopin, bach), force = false)
        assertEquals(ArtworkProgress(done = 0, total = 3, current = null, idle = false), worker.progress.value)
        runCurrent()
        assertEquals(ArtworkProgress(done = 0, total = 3, current = "Claude Debussy", idle = false), worker.progress.value)
        worker.request(clair, priority = true, force = false)
        runCurrent()
        assertEquals(3, worker.progress.value.total)
        advanceTimeBy(600)   // Debussy's two requests are done; the piece is under way
        runCurrent()
        assertEquals(ArtworkProgress(done = 1, total = 3, current = "Clair de lune", idle = false), worker.progress.value)
        advanceUntilIdle()
        assertEquals(ArtworkProgress.Idle, worker.progress.value)

        worker.request(clair.copy(id = 8), priority = true, force = false)
        runCurrent()
        assertEquals(0, worker.progress.value.total)
        assertFalse(worker.progress.value.idle)
        advanceUntilIdle()
        assertTrue(worker.progress.value.idle)
    }

    @Test
    fun `a key queued again is fetched once, moved to the front when the person waits on it`() = runTest {
        val wiki = wiki()
        val worker = worker(wiki)
        worker.requestAll(listOf(chopin, bach, debussy), force = false)
        runCurrent()   // Chopin is under way
        worker.request(debussy, priority = true, force = false)
        worker.request(debussy, priority = false, force = false)
        worker.request(chopin, priority = true, force = false)   // under way already
        advanceUntilIdle()
        assertEquals(
            listOf("Frédéric Chopin", "Claude Debussy", "Johann Sebastian Bach"),
            wiki.calls.filter { it.kind == "summary" }.map { it.arg },
        )
        assertEquals(6, wiki.calls.size)
    }

    @Test
    fun `an automatic run asks about at most 200 composers it does not know, and every known one`() = runTest {
        val worker = worker(wiki())
        val unknown = (0 until 250).map { ArtKey.Composer("someone$it", "Someone $it") }
        assertEquals(203, worker.requestAll(unknown + listOf(debussy, chopin, bach), force = false))
        assertEquals(203, worker.progress.value.total)
        assertEquals(3, worker.requestAll(listOf(debussy, chopin, bach, ArtKey.Composer("someone250", "Someone 250")), force = false))   // known ones only: the cap is spent
        advanceUntilIdle()
        assertEquals(ArtworkProgress.Idle, worker.progress.value)
        // A new run may ask about the next 200; the person asking for every composer is never capped.
        assertEquals(50, worker.requestAll(unknown, force = false))   // the 50 not asked about yet
        advanceUntilIdle()
        assertEquals(250, worker.requestAll(unknown, force = true))
    }

    @Test
    fun `cancelling the background run lets the fetch under way finish and records nothing else`() = runTest {
        val wiki = wiki()
        val worker = worker(wiki)
        worker.requestAll(listOf(debussy, chopin, bach), force = false)
        runCurrent()
        worker.cancelBackground()
        advanceUntilIdle()
        assertEquals(setOf("composer:debussy"), dao.rows.keys)
        assertEquals(ArtworkProgress.Idle, worker.progress.value)
    }
}
