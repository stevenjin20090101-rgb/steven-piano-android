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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M81: what the strip between pieces sends the piano, and when. */
@OptIn(ExperimentalCoroutinesApi::class)
class LedRestTest {
    @Test
    fun `1 only while nothing plays, with Solid colour, in Reactive, outside guest play`() {
        val solid = LedRestChoice.SOLID
        assertEquals("1", LedRest.wanted(true, LedColour.REACTIVE, solid, playing = false, guest = false))
        assertEquals("a piece, the Keys tab, a key test", "0", LedRest.wanted(true, LedColour.REACTIVE, solid, playing = true, guest = false))
        assertEquals("0", LedRest.wanted(true, LedColour.REACTIVE, LedRestChoice.NOTES, playing = false, guest = false))
        assertEquals("Static", "0", LedRest.wanted(true, LedColour.STATIC, solid, playing = false, guest = false))
        assertEquals("Rainbow", "0", LedRest.wanted(true, "2", solid, playing = false, guest = false))
        assertEquals("guest play", "0", LedRest.wanted(true, LedColour.REACTIVE, solid, playing = false, guest = true))
        assertNull("a piano without !ledrestproto=1: nothing", LedRest.wanted(false, LedColour.REACTIVE, solid, playing = false, guest = false))
    }

    private val link = FakePianoLink()
    private val piano = EmulatedConsole()
    private val console = FakeConsole(piano::handle)
    private val choice = MutableStateFlow(LedRestChoice.SOLID)
    private val playing = MutableStateFlow(false)
    private val guest = MutableStateFlow(false)

    private fun TestScope.keeping(): PianoSettingsRepository {
        link.connectedWith(console)
        val repo = PianoSettingsRepository(link, backgroundScope, log = {})
        repo.start()
        LedRestKeeper(repo.state, choice, playing, guest, repo::rest, backgroundScope, log = {}).start()
        settle()
        return repo
    }

    private fun TestScope.settle() {
        advanceTimeBy(DEBOUNCE_AND_A_BIT)
        runCurrent()
    }

    private fun writes(): List<String> = console.sent.filterNot { it.startsWith("get ") || it == "dump" }.also { console.clearSent() }

    /** The link drops (the piano lets `ledrest` go back to 0 with it) and comes back. */
    private fun TestScope.reconnect() {
        link.drop()
        piano.linkDropped()
        runCurrent()
        link.connectedWith(console)
        settle()
    }

    @Test
    fun `each change goes once, at the connection too, and a refusal is not sent again`() = runTest {
        keeping()
        assertEquals("at rest as the piano answers: the colour solid", listOf("ledrest 1"), writes())
        playing.value = true
        settle()
        assertEquals("a piece starts", listOf("ledrest 0"), writes())
        playing.value = false
        settle()
        assertEquals("it ends", listOf("ledrest 1"), writes())
        piano.handle("ledmode 1")
        reconnect()
        assertEquals("Static, and the piano holds 0 from the drop: nothing", emptyList<String>(), writes())
        piano.handle("ledmode 3")
        reconnect()
        assertEquals("every new connection needs it", listOf("ledrest 1"), writes())
        choice.value = LedRestChoice.NOTES
        settle()
        assertEquals(listOf("ledrest 0"), writes())
        // A piano that refuses it: tried once per change, never again and again.
        console.reply = { line -> if (line.startsWith("ledrest ")) listOf("  usage: ledrest 0|1") else piano.handle(line) }
        choice.value = LedRestChoice.SOLID
        settle()
        settle()
        assertEquals(listOf("ledrest 1"), writes())
        playing.value = true
        settle()
        playing.value = false
        settle()
        assertEquals("the piano held 0 meanwhile, so only the next 1, once", listOf("ledrest 1"), writes())
    }

    @Test
    fun `guest play holds it at 0, over the connection that ends it too`() = runTest {
        keeping()
        assertEquals(listOf("ledrest 1"), writes())
        guest.value = true   // `lock guest 60`: the iPad's notes are to show
        settle()
        assertEquals(listOf("ledrest 0"), writes())
        link.drop()   // the tablet lets go of the piano
        runCurrent()
        guest.value = false   // guest play ends: the tablet connects again for `lock guest 0`
        link.connectedWith(console)
        settle()
        assertEquals("nothing sends 1 over guest play's own connection", emptyList<String>(), writes())
        reconnect()
        assertEquals("the next connection is the tablet's again", listOf("ledrest 1"), writes())
        // Without the power plug the tablet keeps its connection through guest play: 0 while it lasts, 1 after.
        guest.value = true
        settle()
        guest.value = false
        settle()
        assertEquals(listOf("ledrest 0", "ledrest 1"), writes())
        reconnect()
        assertEquals("and the next connection is the tablet's own", listOf("ledrest 1"), writes())
    }

    @Test
    fun `a piano without the strip at rest is sent nothing`() = runTest {
        piano.ledRest = false
        keeping()
        playing.value = true
        settle()
        assertEquals(emptyList<String>(), writes())
    }

    private companion object {
        const val DEBOUNCE_AND_A_BIT = 151L
    }
}
