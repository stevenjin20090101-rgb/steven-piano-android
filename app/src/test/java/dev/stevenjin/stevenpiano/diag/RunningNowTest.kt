// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.art.ArtworkProgress
import dev.stevenjin.stevenpiano.firmware.FirmwareManifest
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** What the System page lists as running (v1.18 — M46): twelve rows in their order, each said as the app says it. */
class RunningNowTest {
    private val now = Instant.parse("2026-10-03T13:32:00Z").toEpochMilli()

    private fun rows(inputs: RunningNow.Inputs = RunningNow.Inputs()): Map<String, RunningNow.Activity> =
        RunningNow.of(inputs.copy(now = now, zone = ZoneOffset.UTC, locale = Locale.ROOT)).associateBy { it.key }

    @Test
    fun `with everything idle the twelve rows come in their order, none running, each a plain fragment`() {
        val list = RunningNow.of(RunningNow.Inputs(now = now, zone = ZoneOffset.UTC, locale = Locale.ROOT))
        assertEquals(RunningNow.KEYS, list.map { it.key })
        assertTrue(list.joinToString { "${it.key}=${it.state}" }, list.none { it.state == RunningNow.RUNNING || it.state == RunningNow.PROBLEM })
        assertTrue(list.all { it.progress == null && it.title.isNotBlank() && it.detail.isNotBlank() && !it.detail.endsWith(".") })
        val row = list.associateBy { it.key }
        assertEquals("Stopped · nothing loaded", row.getValue("player").detail)
        assertEquals(RunningNow.OFF to "Not connected", row.getValue("link").let { it.state to it.detail })
        assertEquals(RunningNow.OFF, row.getValue("web").state)
        assertEquals(RunningNow.OFF, row.getValue("relay").state)
        assertEquals(RunningNow.IDLE to "Nothing to fetch", row.getValue("covers").let { it.state to it.detail })
        assertEquals("Nothing importing", row.getValue("import").detail)
        assertEquals("Nothing scheduled", row.getValue("schedule").detail)
        assertEquals(RunningNow.IDLE to "The piano sound isn't on this tablet yet", row.getValue("sound").let { it.state to it.detail })
    }

    @Test
    fun `a channel playing shows the piece, its composer, the channel and how far it is`() {
        val playing = RunningNow.Player(PlaybackStatus.Playing, title = "Clair de lune", composer = "Claude Debussy", channel = "Calm", positionMs = 75_000, durationMs = 300_000)
        val player = rows(RunningNow.Inputs(player = playing)).getValue("player")
        assertEquals(RunningNow.RUNNING, player.state)
        assertEquals("Playing · Clair de lune · Claude Debussy · Calm channel", player.detail)
        assertEquals(0.25, player.progress!!, 1e-9)
        val paused = rows(RunningNow.Inputs(player = playing.copy(status = PlaybackStatus.Paused, channel = null))).getValue("player")
        assertEquals(RunningNow.WAITING to "Paused · Clair de lune · Claude Debussy", paused.state to paused.detail)
        val problem = rows(RunningNow.Inputs(player = playing.copy(problem = "This piece is too large to play."))).getValue("player")
        assertEquals(RunningNow.PROBLEM to "This piece is too large to play", problem.state to problem.detail)
        val link = rows(RunningNow.Inputs(link = RunningNow.Link(state = LinkState.Connected("Steven Piano", 247), live = true))).getValue("link")
        assertEquals(RunningNow.RUNNING to "Connected to Steven Piano · MTU 247 · Live", link.state to link.detail)
    }

    @Test
    fun `Apple's hour-long stop shows the covers waiting until a time, after a run under way`() {
        val stopped = rows(RunningNow.Inputs(covers = RunningNow.Covers(blockedUntil = now + 3_600_000))).getValue("covers")
        assertEquals(RunningNow.WAITING, stopped.state)
        assertEquals("Apple asked to wait · covers again at 14:32", stopped.detail)
        val run = ArtworkProgress(done = 11, total = 61, current = "Claude Debussy", idle = false)
        val running = rows(RunningNow.Inputs(covers = RunningNow.Covers(run, blockedUntil = now + 60_000))).getValue("covers")
        assertEquals(RunningNow.RUNNING to "Fetching artwork 12 of 61 · Claude Debussy", running.state to running.detail)
        assertEquals(0.18, running.progress!!, 1e-9)
        assertEquals("a stop that has ended is no wait", RunningNow.IDLE, rows(RunningNow.Inputs(covers = RunningNow.Covers(blockedUntil = now - 1))).getValue("covers").state)
    }

    @Test
    fun `a firmware update shows what it is sending and how far`() {
        val manifest = FirmwareManifest("2.1.0", "a1b2c3d", "https://example.invalid/fw.bin", 1_000_000, "0".repeat(64), "A".repeat(88), 27, "", usbOnly = false)
        val sending = rows(RunningNow.Inputs(firmware = FirmwareState.Sending(manifest, bytes = 380_000, total = 1_000_000, ratePerSec = 0))).getValue("firmware")
        assertEquals(RunningNow.RUNNING, sending.state)
        assertEquals("Sending · 38%", sending.detail)
        assertEquals(0.38, sending.progress!!, 1e-9)
        val restarting = rows(RunningNow.Inputs(firmware = FirmwareState.Restarting(manifest))).getValue("firmware")
        assertEquals(RunningNow.RUNNING to "Restarting the piano…", restarting.state to restarting.detail)
        assertEquals(null, restarting.progress)
        val failed = rows(RunningNow.Inputs(firmware = FirmwareState.Failed("The piano didn't take the update.", retryable = true))).getValue("firmware")
        assertEquals(RunningNow.PROBLEM to "The piano didn't take the update", failed.state to failed.detail)
        assertFalse(rows().getValue("firmware").state == RunningNow.RUNNING)
    }
}
