// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import dev.stevenjin.stevenpiano.ble.EmulatedConsole
import dev.stevenjin.stevenpiano.ble.FakeConsole
import dev.stevenjin.stevenpiano.ble.FakePianoLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One fact asked and awaited (v1.26 — M60: the smart plug's CHECK, `get boards`; M61's safety check asks the same). */
@OptIn(ExperimentalCoroutinesApi::class)
class PianoAskTest {
    private val link = FakePianoLink()

    private fun TestScope.connected(console: FakeConsole?): PianoSettingsRepository {
        link.connectedWith(console)
        return PianoSettingsRepository(link, backgroundScope, log = {}).also {
            it.start()
            runCurrent()
        }
    }

    @Test
    fun `ask sends get with the fact's name and returns the value of its line`() = runTest {
        val piano = EmulatedConsole()
        val console = FakeConsole(piano::handle)
        val repo = connected(console)
        console.clearSent()
        assertEquals("OK,OK,OK,OK,OK,OK,OK", repo.ask("boards"))
        // The kept ask (M61's) names the fact with its "!", as the app always does; the firmware and the emulated console
        // answer `get boards` and `get !boards` alike (BLE_SETTINGS.md › 4).
        assertEquals(listOf("get !boards"), console.sent)
        assertEquals("named with its mark too", "OK,OK,OK,OK,OK,OK,OK", repo.ask("!boards"))
        piano.setFact("boards", "OK,MISSING,OK,OK,OK,OK,OK")
        assertEquals("OK,MISSING,OK,OK,OK,OK,OK", repo.ask("boards"))
        assertEquals("the answer reaches the settings' facts too", "OK,MISSING,OK,OK,OK,OK,OK", (repo.state.value as PianoState.Ready).facts["boards"])
    }

    @Test
    fun `no answer within the time - null`() = runTest {
        val mute = FakeConsole { if (it == "dump") EmulatedConsole().handle("dump") else emptyList() }
        val repo = connected(mute)
        val answer = async { repo.ask("boards", 2_000) }
        advanceTimeBy(1_999)
        assertTrue(answer.isActive)
        advanceTimeBy(2)
        assertNull(answer.await())
    }

    @Test
    fun `no console, or a name that isn't a fact's - null, and nothing sent`() = runTest {
        val repo = connected(null)
        assertNull(repo.ask("boards"))
        val console = FakeConsole(EmulatedConsole()::handle)
        val other = connected(console)
        console.clearSent()
        assertNull(other.ask("boards; off"))
        assertNull(other.ask(""))
        assertTrue(console.sent.isEmpty())
    }
}
