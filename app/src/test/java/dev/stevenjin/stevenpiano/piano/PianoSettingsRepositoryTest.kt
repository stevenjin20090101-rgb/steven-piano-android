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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The piano's settings over its console: the read, writes and read-backs, refusals, presets, saving. */
@OptIn(ExperimentalCoroutinesApi::class)
class PianoSettingsRepositoryTest {
    private val link = FakePianoLink()
    private val piano = EmulatedConsole()
    private val console = FakeConsole(piano::handle)

    /** A person's Full power choice (v1.22): off when true. */
    private var chosenOff = false
    private val choices = mutableListOf<Boolean>()

    private fun TestScope.connected(with: FakeConsole? = console): PianoSettingsRepository {
        link.connectedWith(with)
        return PianoSettingsRepository(link, backgroundScope, log = {}, fullPowerChosenOff = { chosenOff }, chooseFullPower = { choices += it; chosenOff = !it }).also {
            it.start()
            runCurrent()
        }
    }

    private fun PianoSettingsRepository.ready(): PianoState.Ready = state.value as PianoState.Ready

    /** Lets every 150 ms debounce run out and the replies come in. */
    private fun TestScope.settle() {
        advanceTimeBy(DEBOUNCE_AND_A_BIT)
        runCurrent()
    }

    @Test
    fun `on connect it reads the dump - values, and facts without their mark - until end`() = runTest {
        val repo = connected()
        assertEquals(listOf("dump"), console.sent)
        val ready = repo.ready()
        assertEquals("160", ready.values["ledbright"])
        assertEquals("1.60", ready.values["velcurve"])
        assertEquals("73", ready.values["ledcount"])
        assertEquals("1", ready.facts["proto"])
        assertEquals("OK,OK,OK,OK,OK,OK,OK", ready.facts["boards"])
        assertEquals("123", ready.facts["uptime"])
        assertFalse("!uptime" in ready.values || "uptime" in ready.values)
        assertNull(ready.lastError)
    }

    @Test
    fun `stray lines around the dump are ignored, and nothing shows before end`() = runTest {
        val lines = mutableListOf("[settings] loaded from NVS", "!proto=1", "!fw=1a2b3c4", "ledbright=40", "  chatter from another command", "velcurve=1.25")
        val slow = FakeConsole { if (it == "dump") lines.toList() else emptyList() }
        val repo = connected(slow)
        assertEquals("no end yet", PianoState.Unknown, repo.state.value)
        slow.emit("end")
        runCurrent()
        assertEquals(mapOf("ledbright" to "40", "velcurve" to "1.25"), repo.ready().values)
        assertEquals(mapOf("proto" to "1", "fw" to "1a2b3c4"), repo.ready().facts)
    }

    @Test
    fun `a fact is read again on request, and nothing else is sent for a name that isn't one`() = runTest {
        piano.setFact("ota", "pending")
        val repo = connected()
        assertEquals("pending", repo.ready().facts["ota"])
        console.clearSent()
        piano.setFact("ota", "confirmed")
        repo.readFact("ota")
        runCurrent()
        assertEquals(listOf("get !ota"), console.sent)
        assertEquals("confirmed", repo.ready().facts["ota"])
        repo.readFact("ota; save")
        repo.readFact("")
        repo.readFact("!ota")
        runCurrent()
        assertEquals(listOf("get !ota"), console.sent)
    }

    @Test
    fun `a piano without the console is Unsupported at once`() = runTest {
        val repo = connected(with = null)
        assertEquals(PianoState.Unsupported, repo.state.value)
        assertFalse("no console: no dump to ask for again (v1.27 — M64)", repo.dumpAgain())
    }

    @Test
    fun `no end within 2 s is Unsupported`() = runTest {
        val silent = FakeConsole { emptyList() }
        val repo = connected(silent)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(PianoState.Unknown, repo.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(PianoState.Unsupported, repo.state.value)
    }

    @Test
    fun `a dump unanswered on this connection is asked for once more, and a console that answers then is read (v1_27 M64)`() = runTest {
        var answers = false
        val late = FakeConsole { line -> if (answers) piano.handle(line) else emptyList() }
        val repo = connected(late)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(PianoState.Unsupported, repo.state.value)
        answers = true
        assertTrue(repo.dumpAgain())
        runCurrent()
        assertEquals(listOf("dump", "dump"), late.sent)
        assertEquals("OK,OK,OK,OK,OK,OK,OK", repo.ready().facts["boards"])
        assertFalse("answered: nothing more to ask", repo.dumpAgain())
    }

    @Test
    fun `a dump that is not protocol 1 is Unsupported`() = runTest {
        val newer = FakeConsole { if (it == "dump") listOf("!proto=2", "ledbright=40", "end") else emptyList() }
        val repo = connected(newer)
        assertEquals(PianoState.Unsupported, repo.state.value)
    }

    @Test
    fun `firmware that does not know dump is Unsupported without waiting`() = runTest {
        val older = FakeConsole { listOf("  unknown command — type 'help'") }
        val repo = connected(older)
        assertEquals(PianoState.Unsupported, repo.state.value)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a change shows at once and goes 150 ms after the last change, then is read back`() = runTest {
        val repo = connected()
        console.clearSent()
        repo.set("ledbright", 40)
        advanceTimeBy(100)
        repo.set("ledbright", 41)
        advanceTimeBy(100)
        repo.set("ledbright", 42)
        assertEquals("shown at once", "42", repo.ready().values["ledbright"])
        advanceTimeBy(149)
        runCurrent()
        assertTrue("nothing sent while the value is still changing", console.sent.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("ledbright 42", "get ledbright"), console.sent)
        assertEquals("42", piano.valueOf("ledbright"))
        assertEquals("42", repo.ready().values["ledbright"])
    }

    @Test
    fun `each setting waits out its own 150 ms`() = runTest {
        val repo = connected()
        console.clearSent()
        repo.set("ledbright", 40)
        advanceTimeBy(100)
        repo.set("gap", 50)
        advanceTimeBy(51)
        runCurrent()
        assertEquals(listOf("ledbright 40", "get ledbright"), console.sent)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("ledbright 40", "get ledbright", "gap 50", "get gap"), console.sent)
    }

    @Test
    fun `switches go as 0 or 1 and decimals with two places`() = runTest {
        val repo = connected()
        console.clearSent()
        repo.set("velbright", true)
        repo.set("velcurve", 1.7f)
        settle()
        assertEquals(listOf("velbright 1", "get velbright", "velcurve 1.70", "get velcurve"), console.sent)
        assertEquals("1.70", repo.ready().values["velcurve"])
    }

    @Test
    fun `what the piano holds is what shows`() = runTest {
        val repo = connected()
        repo.set("hold", 20)
        settle()
        assertEquals("the piano clamps hold to 50..4000", "50", repo.ready().values["hold"])
    }

    @Test
    fun `volume is read back with full power, which the piano turns off below 100`() = runTest {
        chosenOff = true   // Full power switched off by a person (v1.22): the piano's volume may move
        val repo = connected()
        console.clearSent()
        repo.set("volume", 80)
        settle()
        assertEquals(listOf("volume 80", "get volume", "get fullpower"), console.sent)
        assertEquals("0", repo.ready().values["fullpower"])
    }

    @Test
    fun `an answer for an older value does not replace a newer one being chosen`() = runTest {
        val held = mutableListOf<String>()
        val slowGets = FakeConsole { line -> if (line.startsWith("get ")) emptyList<String>().also { held += piano.handle(line) } else piano.handle(line) }
        val repo = connected(slowGets)
        repo.set("ledbright", 40)
        settle()
        repo.set("ledbright", 50)
        held.forEach(slowGets::emit)
        runCurrent()
        assertEquals("50", repo.ready().values["ledbright"])
    }

    @Test
    fun `a refusal becomes the last error, the value springs back, and the next accepted change clears it`() = runTest {
        val repo = connected()
        repo.set("ledbright", 300)
        settle()
        val refused = repo.ready()
        assertEquals("ledbright out of range (0..255)", refused.lastError)
        assertEquals("ledbright", refused.errorAbout)
        assertEquals("the piano kept its value", "160", refused.values["ledbright"])
        repo.set("ledbright", 120)
        settle()
        assertNull(repo.ready().lastError)
        assertNull(repo.ready().errorAbout)
    }

    @Test
    fun `usage, unknown and refused replies are errors too, placed by what they name or what was last sent`() = runTest {
        val strict = FakeConsole { line ->
            when {
                line.startsWith("ledtest") -> listOf("  usage: ledtest <midi 0..127>")
                line == "off" -> listOf("  refused over Bluetooth — use the USB console")
                else -> piano.handle(line)
            }
        }
        val repo = connected(strict)
        repo.action(PianoAction.LedTest, 60)
        runCurrent()
        assertEquals("usage: ledtest <midi 0..127>", repo.ready().lastError)
        assertEquals("ledtest", repo.ready().errorAbout)
        repo.action(PianoAction.AllKeysOff)
        runCurrent()
        assertEquals("refused over Bluetooth — use the USB console", repo.ready().lastError)
        assertEquals("off", repo.ready().errorAbout)
        strict.emit("  unknown setting")
        runCurrent()
        assertEquals("unknown setting", repo.ready().lastError)
        repo.dismissError()
        assertNull(repo.ready().lastError)
    }

    @Test
    fun `a preset is sent, then everything is read again`() = runTest {
        chosenOff = true   // Full power switched off by a person (v1.22): the piano's volume may move
        val repo = connected()
        console.clearSent()
        repo.set("gap", 100)
        repo.preset("cinematic")
        runCurrent()
        assertEquals("the waiting change goes first", listOf("gap 100", "get gap", "cinematic", "dump"), console.sent)
        val ready = repo.ready()
        assertEquals("45", ready.values["minstrike"])
        assertEquals("the preset has the last word", "22", ready.values["gap"])
        assertEquals("0", ready.values["fullpower"])
    }

    @Test
    fun `leaving after an accepted change saves on the piano, once`() = runTest {
        val repo = connected()
        repo.set("ledbright", 40)
        settle()
        console.clearSent()
        repo.leave()
        assertEquals(listOf("save"), console.sent)
        repo.leave()
        assertEquals(listOf("save"), console.sent)
    }

    @Test
    fun `leaving with nothing changed, or only a refused change, saves nothing`() = runTest {
        val repo = connected()
        repo.leave()
        repo.set("ledbright", 300)
        settle()
        console.clearSent()
        repo.leave()
        assertTrue(console.sent.isEmpty())
    }

    @Test
    fun `leaving while a change waits out its 150 ms sends it now, then saves`() = runTest {
        val repo = connected()
        console.clearSent()
        repo.set("ledbright", 40)
        runCurrent()
        repo.leave()
        assertEquals(listOf("ledbright 40", "get ledbright", "save"), console.sent)
    }

    @Test
    fun `leaving while a change is unanswered still saves`() = runTest {
        val noGets = FakeConsole { line -> if (line.startsWith("get ")) emptyList() else piano.handle(line) }
        val repo = connected(noGets)
        repo.set("ledbright", 40)
        settle()
        noGets.clearSent()
        repo.leave()
        assertEquals(listOf("save"), noGets.sent)
    }

    @Test
    fun `save now sends waiting changes first`() = runTest {
        val repo = connected()
        console.clearSent()
        repo.set("gap", 40)
        repo.action(PianoAction.Save)
        assertEquals(listOf("gap 40", "get gap", "save"), console.sent)
        settle()
        repo.leave()
        assertEquals("already saved", listOf("gap 40", "get gap", "save"), console.sent)
    }

    @Test
    fun `status lines are collected until 300 ms of silence, and the live facts are read again`() = runTest {
        val repo = connected()
        console.clearSent()
        repo.action(PianoAction.Status)
        runCurrent()
        assertTrue(repo.statusReading.value)
        assertEquals(listOf("status", "get !boards", "get !i2cfails", "get !pedalboard", "get !uptime"), console.sent)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(repo.statusReading.value)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(repo.statusReading.value)
        val text = repo.statusText.value!!
        assertTrue(text, text.startsWith("PWM settings: min=1500"))
        assertTrue(text, text.contains("\n  [0] (MIDI 24-35): OK"))
        assertFalse("get answers are not status text", text.contains("!uptime="))
    }

    @Test
    fun `a status that never comes ends empty after 2 s`() = runTest {
        val mute = FakeConsole { line -> if (line == "status" || line.startsWith("get !")) emptyList() else piano.handle(line) }
        val repo = connected(mute)
        repo.action(PianoAction.Status)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repo.statusReading.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(repo.statusReading.value)
        assertEquals("", repo.statusText.value)
    }

    @Test
    fun `actions send their commands, test keys kept to the piano's 84`() = runTest {
        val repo = connected()
        console.clearSent()
        repo.action(PianoAction.LedTest, 200)
        repo.action(PianoAction.TestMin, 10)
        repo.action(PianoAction.TestMax, 60)
        repo.action(PianoAction.AllKeysOff)
        assertEquals(listOf("ledtest 107", "testmin 24", "testmax 60", "off"), console.sent)
    }

    @Test
    fun `nothing outside the table, nothing read-only, and nothing but a number is ever sent`() = runTest {
        val repo = connected()
        console.clearSent()
        repo.set("fire", "0")
        repo.set("keyforce_white", 1.5f)
        repo.set("ledbright", "40\nfire 0 0 4095")
        repo.set("ledbright", "forty")
        repo.preset("reset")
        settle()
        assertTrue(console.sent.toString(), console.sent.isEmpty())
    }

    @Test
    fun `a drop forgets everything, and the next connection reads the piano again`() = runTest {
        val repo = connected()
        repo.action(PianoAction.Status)
        advanceTimeBy(400)
        runCurrent()
        link.drop()
        runCurrent()
        assertEquals(PianoState.Unknown, repo.state.value)
        assertNull(repo.statusText.value)
        piano.handle("ledbright 12")   // changed at the piano meanwhile
        console.clearSent()
        link.connect(null)
        runCurrent()
        assertEquals(listOf("dump"), console.sent)
        assertEquals("12", repo.ready().values["ledbright"])
    }

    @Test
    fun `nothing is sent before the piano has answered the read`() = runTest {
        val silent = FakeConsole { emptyList() }
        val repo = connected(silent)
        repo.set("ledbright", 40)
        repo.action(PianoAction.AllKeysOff)
        repo.leave()
        settle()
        assertEquals(listOf("dump"), silent.sent)
    }

    @Test
    fun `a held value is sent and read back, but never saved`() = runTest {
        chosenOff = true   // Full power switched off by a person (v1.22): the piano's volume may move
        val repo = connected()
        console.clearSent()
        repo.holdTemporarily("volume", 60)
        settle()
        assertEquals(listOf("volume 60", "get volume", "get fullpower"), console.sent)
        assertEquals("60", repo.ready().values["volume"])
        repo.leave()
        assertEquals("no save for a held value", listOf("volume 60", "get volume", "get fullpower"), console.sent)
    }

    @Test
    fun `a save due while a value is held waits for its release, and the old value goes back first`() = runTest {
        chosenOff = true   // Full power switched off by a person (v1.22): the piano's volume may move
        val repo = connected()
        repo.set("ledbright", 40)
        settle()
        repo.holdTemporarily("volume", 60)
        settle()
        console.clearSent()
        repo.leave()
        assertTrue("the save waits: ${console.sent}", console.sent.none { it == "save" })
        repo.releaseTemporary("volume", 85)
        assertEquals(listOf("volume 85", "get volume", "get fullpower", "save"), console.sent)
    }

    @Test
    fun `a volume the person chooses while one is held stands, and is saved`() = runTest {
        chosenOff = true   // Full power switched off by a person (v1.22): the piano's volume may move
        val repo = connected()
        repo.holdTemporarily("volume", 60)
        settle()
        repo.set("volume", 90)
        settle()
        console.clearSent()
        repo.releaseTemporary("volume", 85)
        settle()
        assertTrue("nothing put back: ${console.sent}", console.sent.isEmpty())
        repo.leave()
        assertEquals(listOf("save"), console.sent)
    }

    @Test
    fun `after the link dropped and came back, a release still puts the volume back, unsaved`() = runTest {
        chosenOff = true   // Full power switched off by a person (v1.22): the piano's volume may move
        val repo = connected()
        repo.holdTemporarily("volume", 60)
        settle()
        link.drop()
        runCurrent()
        link.connect(null)
        runCurrent()
        console.clearSent()
        repo.releaseTemporary("volume", 85)
        settle()
        assertEquals(listOf("volume 85", "get volume", "get fullpower"), console.sent)
        repo.leave()
        assertEquals("no save", listOf("volume 85", "get volume", "get fullpower"), console.sent)
    }

    private companion object {
        const val DEBOUNCE_AND_A_BIT = 151L
    }

    // ---- Full power stays on until a person switches it off (v1.22, fire safety) -----------------------------------

    @Test
    fun `a piano found with Full power off has it turned back on and saved`() = runTest {
        piano.handle("fullpower 0")
        val repo = connected()
        settle()
        assertTrue(console.sent.containsAll(listOf("fullpower 1", "save")))
        assertEquals("1", repo.ready().values["fullpower"])
    }

    @Test
    fun `a person's choice of off is kept`() = runTest {
        piano.handle("fullpower 0")
        chosenOff = true
        val repo = connected()
        settle()
        assertFalse("fullpower 1" in console.sent)
        assertEquals("0", repo.ready().values["fullpower"])
    }

    @Test
    fun `switching it off is recorded as the person's choice, and stays off`() = runTest {
        val repo = connected()
        repo.set("fullpower", false)
        settle()
        assertEquals(listOf(false), choices)
        assertTrue("fullpower 0" in console.sent)
        assertFalse("fullpower 1" in console.sent)
        assertEquals("0", repo.ready().values["fullpower"])
    }

    @Test
    fun `a volume under 100 is not sent while Full power is on`() = runTest {
        val repo = connected()
        repo.set("volume", 55)
        repo.holdTemporarily("volume", 70)
        settle()
        assertFalse(console.sent.any { it.startsWith("volume ") })
        assertEquals("1", repo.ready().values["fullpower"])
    }

    @Test
    fun `a preset that turns Full power off keeps it on, and saved`() = runTest {
        val repo = connected()
        repo.preset("soft")
        settle()
        val after = console.sent.dropWhile { it != "soft" }
        assertEquals(listOf("soft", "volume 100", "fullpower 1", "save"), after.take(4))
        assertEquals("1", repo.ready().values["fullpower"])
    }
}
