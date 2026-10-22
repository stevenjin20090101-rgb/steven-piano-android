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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One fact asked and awaited, and the piano's restart (v1.26 — M61, the safety check; firmware 2.1.1's `restart`), over a
 * stand-in console: what goes out, what comes back, and that a refusal is the check's alone, never a setting's error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PianoAskRestartTest {
    private val link = FakePianoLink()
    private val piano = EmulatedConsole(EmulatedConsole.DEFAULT_FACTS + mapOf("active" to "0", "restartproto" to "1"))

    /** What the piano answers `restart` with; empty: nothing. */
    private var restartReply = listOf("  restarting")

    /** Lines the piano doesn't answer (yet). */
    private val unanswered = mutableSetOf<String>()
    private val console = FakeConsole { line ->
        when {
            line in unanswered -> emptyList()
            line == "restart" -> restartReply
            else -> piano.handle(line)
        }
    }

    private fun TestScope.connected(): PianoSettingsRepository {
        link.connectedWith(console)
        return PianoSettingsRepository(link, backgroundScope, log = {}).also {
            it.start()
            runCurrent()
        }
    }

    @Test
    fun `ask sends get with the fact's mark and returns the value of the first answer`() = runTest {
        val repo = connected()
        console.clearSent()
        assertEquals("OK,OK,OK,OK,OK,OK,OK", repo.ask("boards"))
        assertEquals("0", repo.ask("!active"))
        assertEquals(listOf("get !boards", "get !active"), console.sent)
        piano.setFact("active", "2")
        assertEquals("the answer as it is now", "2", repo.ask("active"))
        assertEquals("2", (repo.state.value as PianoState.Ready).facts["active"])
    }

    @Test
    fun `an ask with no answer gives up after its time, and two asks never overlap`() = runTest {
        val repo = connected()
        console.clearSent()
        unanswered += "get !boards"
        val first = async { repo.ask("boards") }
        val second = async { repo.ask("active") }
        runCurrent()
        assertEquals("one at a time", listOf("get !boards"), console.sent)
        advanceTimeBy(2_001)
        runCurrent()
        assertNull(first.await())
        assertEquals("0", second.await())
        assertEquals(listOf("get !boards", "get !active"), console.sent)
        assertNull("not a fact's name: nothing sent", repo.ask("no such fact!"))
        assertEquals(2, console.sent.size)
    }

    @Test
    fun `restart is sent and its reply returned, and a refusal is the check's, never a setting's error`() = runTest {
        val repo = connected()
        console.clearSent()
        assertEquals("restarting", repo.restart())
        assertEquals(listOf("restart"), console.sent)
        for (refusal in listOf("  refused — updating firmware", "  refused — this firmware has not confirmed itself yet", "  refused — not authorised (send auth first)")) {
            restartReply = listOf(refusal)
            assertEquals(refusal.trim(), repo.restart())
            assertNull("no setting's error from it", (repo.state.value as PianoState.Ready).lastError)
        }
        restartReply = emptyList()
        val silent = async { repo.restart() }
        advanceTimeBy(2_001)
        runCurrent()
        assertNull(silent.await())
    }

    @Test
    fun `restart never goes right after a setting's write, which it would lose`() = runTest {
        val repo = connected()
        repo.set("ledbright", 40)   // waits out its 150 ms
        console.clearSent()
        assertNull(repo.restart())
        assertFalse("not sent", "restart" in console.sent)
        advanceTimeBy(500)
        runCurrent()
        assertTrue("the write went", console.sent.contains("ledbright 40"))
        assertEquals("written and read back: now it may go", "restarting", repo.restart())
    }

    @Test
    fun `with no piano there is nothing to ask`() = runTest {
        link.disconnect()
        val repo = PianoSettingsRepository(link, backgroundScope, log = {}).also { it.start() }
        runCurrent()
        assertNull(repo.ask("boards"))
        assertNull(repo.restart())
    }
}
