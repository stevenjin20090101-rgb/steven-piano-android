// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import dev.stevenjin.stevenpiano.ble.EmulatedConsole
import dev.stevenjin.stevenpiano.ble.FakeConsole
import dev.stevenjin.stevenpiano.ble.FakeOtaChannel
import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.OtaPiano
import dev.stevenjin.stevenpiano.piano.PianoSettingsRepository
import dev.stevenjin.stevenpiano.update.FakeUpdateServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The firmware updater against the fake update channel (BLE_OTA.md › 5–11): checks, the transfer
 * with its windows and ACKs, the ≥ 500 ms rule, Cancel, failures that are never retried by
 * themselves, the restart and what the piano reports after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareUpdaterTest {
    private val manifest = FirmwareManifest.parse(OtaExample.manifestJson())

    /** The player as the updater sees it: locked or not, and when the stop sequence was written. */
    private class FakePlayer(private val now: () -> Long) : FirmwarePlayer {
        var locked = false
        val locks = mutableListOf<String>()
        var unlocks = 0
        var stoppedAt: Long? = null

        override fun lock(reason: String) {
            locked = true
            locks += reason
        }

        override fun unlock() {
            locked = false
            unlocks++
        }

        override suspend fun stopForUpdate(timeoutMs: Long): Boolean {
            stoppedAt = now()
            return true
        }
    }

    /** A piano on firmware 2.0.0 with its console and update service, the example release on the server, and the updater. */
    private inner class Rig(
        val test: TestScope,
        manifestJson: String = OtaExample.manifestJson(),
        key: ByteArray = FirmwareKeys.rfc8032Test,
        answerDelayMs: Long? = null,
    ) {
        val link = FakePianoLink()
        val emulated = EmulatedConsole(EmulatedConsole.DEFAULT_FACTS + mapOf("fw" to OtaExample.RUNNING, "ota" to "none"))
        val ota = FakeOtaChannel(
            link,
            now = { test.testScheduler.currentTime },
            answerScope = answerDelayMs?.let { test.backgroundScope },
            answerDelayMs = answerDelayMs ?: 0L,
        )
        val server = FakeUpdateServer(manifestJson).apply {
            files[OtaExample.BIN_URL] = OtaExample.image()
            chunk = 64 * 1024
        }
        val player = FakePlayer { test.testScheduler.currentTime }
        val online = MutableStateFlow(true)
        var power = PowerState(80, charging = false)
        val logged = mutableListOf<String>()
        val settings: PianoSettingsRepository
        val updater: FirmwareUpdater
        val states = mutableListOf<FirmwareState>()
        var lockedAtBegin: Boolean? = null

        init {
            link.firmwareVersionOnConnect = OtaExample.RUNNING
            link.otaOnConnect = ota
            link.consoleOnConnect = FakeConsole(emulated::handle)
            link.connect(null)
            settings = PianoSettingsRepository(link, test.backgroundScope, log = {}).also { it.start() }
            updater = FirmwareUpdater(
                link = link,
                pianoState = settings.state,
                readFact = settings::readFact,
                player = player,
                server = server,
                publicKey = key,
                appVersionCode = 13,
                platformEd25519 = false,
                online = online,
                power = { power },
                scope = test.backgroundScope,
                now = { test.testScheduler.currentTime },
                log = { logged += it },
                compute = StandardTestDispatcher(test.testScheduler),
            )
            updater.start()
            ota.onBegin = { lockedAtBegin = player.locked }
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { updater.state.collect { states += it } }
            test.runCurrent()
        }

        /** After OK the piano drops the link, and is back [backAfterMs] later running [version] with `!ota` [ota]. */
        fun restartsInto(version: String, ota: String, backAfterMs: Long = 4_000) {
            this.ota.onRestart = { inMs ->
                test.backgroundScope.launch {
                    delay(inMs.toLong())
                    link.drop()
                    delay(backAfterMs)
                    link.firmwareVersionOnConnect = version
                    emulated.setFact("fw", version)
                    emulated.setFact("ota", ota)
                    link.connect(null)
                }
            }
        }

        fun check() {
            updater.checkNow()
            test.runCurrent()
        }
    }

    @Test
    fun `a check offers a newer release signed with the key the app holds`() = runTest {
        val rig = Rig(this)
        rig.check()
        assertEquals(FirmwareState.Available(manifest), rig.updater.state.value)
        assertTrue(rig.updater.state.value.offered)
        assertTrue(rig.logged.contains("Firmware check: 2.1.0 is available (the piano runs 2.0.0+a1b2c3d)"))
    }

    @Test
    fun `a release not signed with the app's key is never offered`() = runTest {
        val rig = Rig(this, key = FirmwareKeys.author)
        rig.check()
        assertEquals(FirmwareState.Failed(FirmwareFailures.UNSIGNED, retryable = false, check = true), rig.updater.state.value)
        assertFalse(rig.updater.state.value.offered)
        rig.updater.update()
        runCurrent()
        assertTrue(rig.server.downloads.isEmpty())
    }

    @Test
    fun `the same or an older release is up to date`() = runTest {
        val same = Rig(this)
        same.link.firmwareVersionOnConnect = "2.1.0+0000000"
        same.link.connect(null)
        runCurrent()
        same.check()
        assertEquals(FirmwareState.UpToDate("2.1.0"), same.updater.state.value)
        assertFalse(same.updater.state.value.offered)
    }

    @Test
    fun `the happy path - download, check, stop, 500 ms of quiet, windows and ACKs, OK, restart, confirmed`() = runTest {
        val rig = Rig(this)
        rig.restartsInto("2.1.0+a1b2c3d", ota = "pending")
        rig.check()
        rig.updater.update()
        advanceTimeBy(1_000)
        runCurrent()

        // Sent: BEGIN, the image in 3,965 frames of 250 bytes at most, END.
        assertEquals(1, rig.ota.sessions)
        assertEquals(listOf("BEGIN", "END"), rig.ota.controlNames)
        assertEquals(3_965, rig.ota.data.size)
        assertArrayEquals(OtaExample.image(), rig.ota.image())
        assertTrue(rig.ota.data.all { it.size <= 252 })
        assertEquals("the player was locked as BEGIN went", true, rig.lockedAtBegin)
        assertTrue("BEGIN at least 500 ms after the stop sequence", rig.ota.beginAt!! - rig.player.stoppedAt!! >= 500)
        assertEquals(listOf(FirmwareUpdater.PLAYER_LOCKED), rig.player.locks)
        assertEquals(FirmwareState.Restarting(manifest), rig.updater.state.value)
        assertEquals(listOf(60_000L), rig.link.restartsExpected)
        assertTrue("still locked while the piano restarts", rig.player.locked)

        // The piano drops at 1.5 s and is back 4 s later, pending its self-test.
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(FirmwareState.Done("2.1.0", confirming = true), rig.updater.state.value)
        assertFalse("unlocked once it is done", rig.player.locked)
        assertEquals(1, rig.player.unlocks)
        assertTrue(rig.logged.contains("Firmware update: the piano runs 2.1.0+a1b2c3d, !ota pending"))

        // Its self-test passes; the re-read 30 s later finds it confirmed.
        rig.emulated.setFact("ota", "confirmed")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(FirmwareState.Done("2.1.0"), rig.updater.state.value)
        assertTrue(rig.logged.contains("Firmware update: 2.1.0 confirmed (!ota confirmed)"))

        val kinds = rig.states.map { it::class.simpleName }.distinct()
        assertEquals(
            listOf("Idle", "Checking", "Available", "Downloading", "Verifying", "Sending", "PianoVerifying", "Restarting", "Done"),
            kinds,
        )
        val lastSending = rig.states.filterIsInstance<FirmwareState.Sending>().last()
        assertEquals(OtaExample.SIZE.toLong(), lastSending.bytes)
        assertEquals(1, rig.server.downloads.size)
    }

    @Test
    fun `ERR 1 fails with Retry, is never retried by itself, and Retry reuses the download`() = runTest {
        val rig = Rig(this)
        rig.ota.script = OtaPiano.Script(errorAtBegin = 1)
        rig.check()
        rig.updater.update()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, retryable = true, manifest = manifest), rig.updater.state.value)
        assertFalse(rig.player.locked)
        assertTrue(rig.logged.contains("Firmware update: ERR 1 (not quiet) before READY; the piano kept its firmware"))
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals("no retry by itself", 1, rig.ota.sessions)

        rig.ota.script = OtaPiano.Script()
        rig.restartsInto("2.1.0+a1b2c3d", ota = "confirmed")
        rig.updater.update()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(FirmwareState.Done("2.1.0"), rig.updater.state.value)
        assertEquals("the verified image was kept for Retry", 1, rig.server.downloads.size)
    }

    @Test
    fun `a disconnect mid-transfer fails once, with no retry loop`() = runTest {
        val rig = Rig(this)
        rig.ota.script = OtaPiano.Script(dropAfterAcks = 5)
        rig.check()
        rig.updater.update()
        advanceTimeBy(1_000)
        runCurrent()
        val failed = rig.updater.state.value as FirmwareState.Failed
        assertEquals(FirmwareFailures.DIDNT_FINISH, failed.message)
        assertTrue(failed.retryable)
        assertEquals(LinkState.Reconnecting(1), rig.link.state.value)
        assertEquals("nothing after the drop", 5 * 16, rig.ota.data.size)
        assertFalse(rig.player.locked)
        assertTrue(rig.link.restartsExpected.isEmpty())
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(1, rig.ota.sessions)
        assertEquals(failed, rig.updater.state.value)
    }

    @Test
    fun `Cancel sends ABORT once, and the release goes back on offer`() = runTest {
        val rig = Rig(this, answerDelayMs = 50)
        rig.check()
        rig.updater.update()
        advanceTimeBy(600 + 50 * 4 + 10)   // the quiet, READY and a few windows
        runCurrent()
        assertTrue(rig.updater.state.value is FirmwareState.Sending)
        assertTrue(rig.updater.state.value.cancellable)
        rig.updater.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("BEGIN", "ABORT"), rig.ota.controlNames)
        assertEquals(FirmwareState.Available(manifest), rig.updater.state.value)
        assertFalse(rig.player.locked)
        assertTrue(rig.ota.data.size < 3_965)
        assertTrue(rig.logged.contains("Firmware update: cancelled while it was sent; the piano kept its firmware"))
    }

    @Test
    fun `the old version back after the restart is a rollback`() = runTest {
        val rig = Rig(this)
        rig.restartsInto(OtaExample.RUNNING, ota = "none")
        rig.check()
        rig.updater.update()
        advanceTimeBy(10_000)
        runCurrent()
        val failed = rig.updater.state.value as FirmwareState.Failed
        assertEquals("The piano restarted but reports 2.0.0 — it rolled back.", failed.message)
        assertTrue(failed.rolledBack)
        assertTrue(failed.retryable)
        assertTrue("the release is still on offer", failed.offered)
        assertFalse(rig.player.locked)
    }

    @Test
    fun `a new image that resets before confirming is caught as it comes back old`() = runTest {
        val rig = Rig(this)
        rig.restartsInto("2.1.0+a1b2c3d", ota = "pending")
        rig.check()
        rig.updater.update()
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(FirmwareState.Done("2.1.0", confirming = true), rig.updater.state.value)
        // A power cut 10 s into the new image: the bootloader goes back to the old slot.
        rig.link.drop()
        advanceTimeBy(5_000)
        rig.link.firmwareVersionOnConnect = OtaExample.RUNNING
        rig.link.connect(null)
        advanceTimeBy(30_000)
        runCurrent()
        val failed = rig.updater.state.value as FirmwareState.Failed
        assertTrue(failed.rolledBack)
        assertEquals("The piano restarted but reports 2.0.0 — it rolled back.", failed.message)
    }

    @Test
    fun `a USB-only release and one that needs a newer app are shown, never sent`() = runTest {
        val usb = Rig(this, OtaExample.manifestJson { put("usbOnly", true) })
        usb.check()
        assertTrue(usb.updater.state.value is FirmwareState.UsbOnly)
        assertTrue(usb.updater.state.value.offered)
        usb.updater.update()
        advanceTimeBy(5_000)
        assertEquals(0, usb.ota.sessions)
        assertTrue(usb.server.downloads.isEmpty())
        assertTrue(usb.player.locks.isEmpty())

        val newer = Rig(this, OtaExample.manifestJson { put("minAppVersionCode", 14) })
        newer.check()
        assertTrue(newer.updater.state.value is FirmwareState.NeedsNewerApp)
        newer.updater.update()
        advanceTimeBy(5_000)
        assertEquals(0, newer.ota.sessions)
        assertTrue(newer.server.downloads.isEmpty())
    }

    @Test
    fun `a download that doesn't match the release is refused before anything reaches the piano`() = runTest {
        val rig = Rig(this)
        rig.server.files[OtaExample.BIN_URL] = OtaExample.image().also { it[123_456] = (it[123_456].toInt() xor 1).toByte() }
        rig.check()
        rig.updater.update()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(FirmwareState.Failed(FirmwareFailures.MISMATCH, retryable = true, manifest = manifest), rig.updater.state.value)
        assertEquals(0, rig.ota.sessions)
        assertTrue(rig.player.locks.isEmpty())

        rig.server.files[OtaExample.BIN_URL] = OtaExample.image().copyOf(OtaExample.SIZE + 1)
        rig.updater.update()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(FirmwareFailures.MISMATCH, (rig.updater.state.value as FirmwareState.Failed).message)
        assertEquals(0, rig.ota.sessions)
    }

    @Test
    fun `nothing is downloaded without a connected piano that can take it, or with a flat tablet`() = runTest {
        val rig = Rig(this)
        rig.check()
        rig.power = PowerState(15, charging = false)
        rig.updater.update()
        runCurrent()
        assertEquals(FirmwareState.Failed(FirmwareFailures.LOW_BATTERY, retryable = true, manifest = manifest), rig.updater.state.value)
        assertTrue(rig.server.downloads.isEmpty())

        rig.power = PowerState(15, charging = true)
        rig.link.disconnect()
        runCurrent()
        rig.updater.update()
        runCurrent()
        assertEquals(FirmwareState.Failed(FirmwareFailures.NOT_CONNECTED, retryable = true, manifest = manifest), rig.updater.state.value)
        assertTrue(rig.server.downloads.isEmpty())

        rig.check()
        assertEquals("a check says why it can't ask", FirmwareFailures.NOT_CONNECTED, (rig.updater.state.value as FirmwareState.Failed).message)

        rig.link.otaOnConnect = null
        rig.link.firmwareVersionOnConnect = null
        rig.link.connect(null)
        runCurrent()
        rig.check()
        assertEquals(FirmwareFailures.NOT_UPDATABLE, (rig.updater.state.value as FirmwareState.Failed).message)
        assertTrue((rig.updater.piano.value as dev.stevenjin.stevenpiano.firmware.FirmwarePiano.Connected).tooOld)
    }

    @Test
    fun `a refused signature is not retryable and says the piano refused it`() = runTest {
        val rig = Rig(this)
        rig.ota.script = OtaPiano.Script(publicKey = FirmwareKeys.author)   // the piano holds the author's key
        rig.check()
        rig.updater.update()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, retryable = false, manifest = manifest, hint = FirmwareFailures.REFUSED_HINT),
            rig.updater.state.value,
        )
        assertFalse(rig.updater.state.value.offered)
        rig.updater.update()
        runCurrent()
        assertEquals("no Retry for it", 1, rig.ota.sessions)
    }

    @Test
    fun `a hash the piano finds wrong after END is the one-line failure, with Retry`() = runTest {
        val rig = Rig(this)
        rig.ota.script = OtaPiano.Script(hashMismatch = true)
        rig.check()
        rig.updater.update()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, retryable = true, manifest = manifest), rig.updater.state.value)
        assertTrue(rig.logged.contains("Firmware update: ERR 5 (hash mismatch) while the piano checked the image; the piano kept its firmware"))
    }

    @Test
    fun `a link lost after END is judged by what the piano runs once it is back`() = runTest {
        val rig = Rig(this)
        rig.ota.script = OtaPiano.Script(dropAfterEnd = true)
        rig.restartsInto("2.1.0+a1b2c3d", ota = "confirmed")
        rig.check()
        rig.updater.update()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(FirmwareState.Restarting(manifest), rig.updater.state.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(FirmwareState.Done("2.1.0"), rig.updater.state.value)
    }

    @Test
    fun `a piano that doesn't come back within the minute says so`() = runTest {
        val rig = Rig(this)
        rig.ota.onRestart = { ms ->
            backgroundScope.launch {
                delay(ms.toLong())
                rig.link.drop()
            }
        }
        rig.check()
        rig.updater.update()
        advanceTimeBy(1_000 + 60_000)
        runCurrent()
        assertEquals(FirmwareState.Failed(FirmwareFailures.DIDNT_COME_BACK, retryable = false, manifest = manifest), rig.updater.state.value)
        assertFalse(rig.player.locked)
        // It turns up later, on the new firmware: that is still reported.
        rig.link.firmwareVersionOnConnect = "2.1.0+a1b2c3d"
        rig.emulated.setFact("ota", "confirmed")
        rig.link.connect(null)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(FirmwareState.Done("2.1.0"), rig.updater.state.value)
    }

    @Test
    fun `checks run daily while a piano that can be updated is connected and the switch is on`() = runTest {
        val rig = Rig(this)
        val enabled = MutableStateFlow(true)
        backgroundScope.launch { rig.updater.runSchedule(enabled) }
        runCurrent()
        assertEquals("at once", 1, rig.server.manifestCalls)
        advanceTimeBy(FirmwareUpdater.INTERVAL_MS - 1)
        runCurrent()
        assertEquals(1, rig.server.manifestCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("a day later", 2, rig.server.manifestCalls)

        enabled.value = false
        advanceTimeBy(3 * FirmwareUpdater.INTERVAL_MS)
        runCurrent()
        assertEquals("switched off", 2, rig.server.manifestCalls)
        rig.link.disconnect()
        enabled.value = true
        advanceTimeBy(3 * FirmwareUpdater.INTERVAL_MS)
        runCurrent()
        assertEquals("not connected", 2, rig.server.manifestCalls)
        rig.link.connect(null)
        runCurrent()
        assertEquals("connected again, and due", 3, rig.server.manifestCalls)
    }

    @Test
    fun `opening the Firmware page checks, at most every ten minutes`() = runTest {
        val rig = Rig(this)
        rig.updater.checkOnOpen()
        runCurrent()
        assertEquals(1, rig.server.manifestCalls)
        advanceTimeBy(9 * 60_000)
        rig.updater.checkOnOpen()
        runCurrent()
        assertEquals(1, rig.server.manifestCalls)
        advanceTimeBy(60_000)
        rig.updater.checkOnOpen()
        runCurrent()
        assertEquals(2, rig.server.manifestCalls)
    }

    @Test
    fun `offline, a check says so without asking, and a release on offer stays`() = runTest {
        val rig = Rig(this)
        rig.check()
        rig.online.value = false
        rig.check()
        assertEquals(
            FirmwareState.Failed(FirmwareFailures.OFFLINE, retryable = true, manifest = manifest, check = true),
            rig.updater.state.value,
        )
        assertTrue("still on offer", rig.updater.state.value.offered)
        assertEquals(1, rig.server.manifestCalls)
    }
}
