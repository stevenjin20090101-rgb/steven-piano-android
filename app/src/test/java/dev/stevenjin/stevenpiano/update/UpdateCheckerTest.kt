// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Whether a newer release exists, and when the app asks (v1.4): at start, then daily, online and switched on. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerTest {
    private val server = FakeUpdateServer()
    private val online = MutableStateFlow(true)
    private val enabled = MutableStateFlow(true)
    private val hour = 60L * 60 * 1000

    private fun TestScope.checker(current: Int = 7, sdk: Int = 34) =
        UpdateChecker(current, sdk, UpdateSource.production, server, online, now = { testScheduler.currentTime })

    @Test
    fun `a newer versionCode is on offer, the same or an older one is up to date`() = runTest {
        val newer = checker(current = 7)
        newer.checkNow()
        assertEquals(UpdateState.Available(Manifests.manifest()), newer.state.value)

        val same = checker(current = 8)
        same.checkNow()
        assertEquals(UpdateState.UpToDate, same.state.value)

        val older = checker(current = 9)
        older.checkNow()
        assertEquals(UpdateState.UpToDate, older.state.value)
    }

    @Test
    fun `a release for a newer Android is not offered`() = runTest {
        server.manifestText = Manifests.json { put("minSdk", 35) }
        val checker = checker(sdk = 34)
        checker.checkNow()
        assertEquals(UpdateState.Failed(UpdateFailures.needsNewerAndroid("1.4")), checker.state.value)
    }

    @Test
    fun `failures say so in one line, and keep a release already on offer`() = runTest {
        val checker = checker()
        server.failing = true
        checker.checkNow()
        assertEquals(UpdateState.Failed(UpdateFailures.UNREACHABLE), checker.state.value)

        server.failing = false
        server.manifestText = "{\"versionCode\": \"eight\"}"
        checker.checkNow()
        assertEquals(UpdateState.Failed(UpdateFailures.UNREADABLE), checker.state.value)

        server.manifestText = Manifests.json()
        checker.checkNow()
        val offered = (checker.state.value as UpdateState.Available).manifest
        server.failing = true
        checker.checkNow()
        assertEquals(UpdateState.Failed(UpdateFailures.UNREACHABLE, offered), checker.state.value)
    }

    @Test
    fun `offline, automatic checks wait for the network, and Check now says why`() = runTest {
        online.value = false
        val checker = checker()
        backgroundScope.launch { checker.runSchedule(enabled) }
        advanceTimeBy(3 * hour)
        runCurrent()
        assertEquals(0, server.manifestCalls)
        assertEquals(UpdateState.Idle, checker.state.value)

        checker.checkNow()
        assertEquals(0, server.manifestCalls)
        assertEquals(UpdateState.Failed(UpdateFailures.OFFLINE), checker.state.value)

        online.value = true
        runCurrent()
        assertEquals(1, server.manifestCalls)
        assertTrue(checker.state.value is UpdateState.Available)
    }

    @Test
    fun `a check at once, then one every 24 hours on the clock`() = runTest {
        val checker = checker()
        backgroundScope.launch { checker.runSchedule(enabled) }
        runCurrent()
        assertEquals(1, server.manifestCalls)
        assertEquals(0L, checker.lastCheckedAt)

        advanceTimeBy(23 * hour)
        runCurrent()
        assertEquals(1, server.manifestCalls)
        advanceTimeBy(hour)
        runCurrent()
        assertEquals(2, server.manifestCalls)
        advanceTimeBy(24 * hour)
        runCurrent()
        assertEquals(3, server.manifestCalls)
        assertEquals(24 * hour, checker.untilDue())
    }

    @Test
    fun `losing the network part-way keeps the day's rhythm from the last check`() = runTest {
        val checker = checker()
        backgroundScope.launch { checker.runSchedule(enabled) }
        runCurrent()
        advanceTimeBy(10 * hour)
        online.value = false
        runCurrent()
        advanceTimeBy(20 * hour)   // 30 h since the check, offline all along
        runCurrent()
        assertEquals(1, server.manifestCalls)
        online.value = true
        runCurrent()
        assertEquals(2, server.manifestCalls)
    }

    @Test
    fun `switched off, nothing is asked by itself, but Check now still asks`() = runTest {
        enabled.value = false
        val checker = checker()
        backgroundScope.launch { checker.runSchedule(enabled) }
        advanceTimeBy(48 * hour)
        runCurrent()
        assertEquals(0, server.manifestCalls)

        checker.checkNow()
        assertEquals(1, server.manifestCalls)
        assertTrue(checker.state.value is UpdateState.Available)

        advanceTimeBy(24 * hour)
        enabled.value = true
        runCurrent()
        assertEquals(2, server.manifestCalls)
    }

    @Test
    fun `a check never replaces a download or an install under way`() = runTest {
        val checker = checker()
        checker.checkNow()
        val manifest = checker.state.value.manifest!!
        for (busy in listOf(
            UpdateState.Downloading(manifest, 10, 100),
            UpdateState.ReadyToInstall(manifest, File("1.4.apk")),
            UpdateState.Installing(manifest),
            UpdateState.Installed("1.4"),
        )) {
            checker.publish(busy)
            checker.checkNow()
            assertEquals(busy, checker.state.value)
        }
    }

    @Test
    fun `after an update Android restarted, the next automatic check waits a day, and Check now still asks`() = runTest {
        val checker = checker(current = 8)
        checker.publish(UpdateState.Installed("1.4", restartNeeded = false))
        checker.markChecked()
        backgroundScope.launch { checker.runSchedule(enabled) }
        advanceTimeBy(23 * hour)
        runCurrent()
        assertEquals(0, server.manifestCalls)
        assertEquals(UpdateState.Installed("1.4", restartNeeded = false), checker.state.value)
        checker.checkNow()
        assertEquals(UpdateState.UpToDate, checker.state.value)
    }
}
