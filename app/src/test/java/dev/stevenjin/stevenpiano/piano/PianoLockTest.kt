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
import dev.stevenjin.stevenpiano.ble.EmulatedLock
import dev.stevenjin.stevenpiano.ble.FakeConsole
import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.LinkTrust
import dev.stevenjin.stevenpiano.ble.LockGate
import dev.stevenjin.stevenpiano.ble.Trust
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The piano's Bluetooth lock, the app's side (v1.25 — M58; firmware 2.1.0, `firmware/docs/BLE_LOCK.md` and its pinned
 * Replies), through the piano's settings and the player's gate: firmware 2.0.x unchanged; an open piano claimed and its
 * key kept; every note and setting write (the Full-power restore too) held until `auth ok`; the stop always out; read
 * only without a key or once refused; each claim refusal; the 3 s deadline; one attempt a second; trust per connection;
 * and the lock's own commands. The piano is the debug build's [EmulatedLock] (its own X25519 and HMAC), or a script.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PianoLockTest {
    private val link = FakePianoLink().apply { addressOnConnect = ADDRESS }
    private val keys = MemoryKeys()

    /** The tablet's kept keys, in memory: a test reads them, or makes keeping fail. */
    private class MemoryKeys(var keepable: Boolean = true) : LockKeys {
        val kept = HashMap<String, ByteArray>()

        override suspend fun key(address: String): ByteArray? = kept[address]?.copyOf()

        override suspend fun canKeep(): Boolean = keepable

        override suspend fun keep(address: String, key: ByteArray): Boolean {
            if (!keepable) return false
            kept[address] = key.copyOf()
            return true
        }

        override suspend fun forget(address: String) {
            kept.remove(address)
        }
    }

    private fun TestScope.connected(console: FakeConsole): PianoSettingsRepository {
        link.connectedWith(console)
        return PianoSettingsRepository(
            link,
            backgroundScope,
            log = {},
            lockKeys = keys,
            elapsed = { testScheduler.currentTime },
            crypto = StandardTestDispatcher(testScheduler),
        ).also {
            it.start()
            runCurrent()
        }
    }

    private fun TestScope.lock(mode: EmulatedLock.Mode = EmulatedLock.Mode.Open) = EmulatedLock(mode, clock = { testScheduler.currentTime })

    private fun PianoSettingsRepository.ready(): PianoState.Ready = state.value as PianoState.Ready

    /** Lines that change the piano: anything but reading, `off`, and the lock's own claim and proof. */
    private fun writes(sent: List<String>): List<String> =
        sent.filterNot { it == "dump" || it.startsWith("get ") || it == "status" || it == "off" || it.startsWith("auth ") || it.startsWith("claim ") }

    private fun batch(vararg bytes: Int): MidiBatch = MidiBatch().apply { for (i in bytes.indices step 3) add(bytes[i], bytes[i + 1], bytes[i + 2]) }

    @Test
    fun `firmware 2_0_x - no lockproto, so no claim and no proof, and everything goes at once as before`() = runTest {
        val console = FakeConsole(EmulatedConsole()::handle)
        val repo = connected(console)
        val gate = LockGate(link, repo.trust, backgroundScope)
        runCurrent()
        assertEquals(listOf("dump"), console.sent)
        assertEquals(LockPhase.None, repo.lock.value.phase)
        assertEquals(LinkTrust(0, Trust.Open), repo.trust.value)
        assertEquals(link.state.value, gate.state.value)
        repo.set("ledbright", 40)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("dump", "ledbright 40", "get ledbright"), console.sent)
        gate.send(batch(0x90, 60, 80), dropPending = false)
        assertEquals(listOf("90 3C 50"), link.messages)
        assertTrue("no key kept", keys.kept.isEmpty())
    }

    @Test
    fun `an open piano is trusted at once and claimed for this tablet, which keeps the piano's own K by its address`() = runTest {
        val piano = lock()
        val console = FakeConsole(EmulatedConsole(lock = piano)::handle)
        val repo = connected(console)
        val claim = console.sent.single { it.startsWith("claim ") }
        assertTrue(claim, Regex("claim [0-9a-f]{64}").matches(claim))
        val lock = repo.lock.value
        assertEquals(LockPhase.Trusted, lock.phase)
        assertEquals(LockWord.Claimed, lock.piano)
        assertTrue(lock.keyStored)
        val kept = keys.kept.getValue(ADDRESS)
        assertTrue("the tablet's K is the piano's", piano.knows(kept))
        assertEquals(1, piano.keyCount)
        repo.set("ledbright", 40)
        advanceTimeBy(200)
        runCurrent()
        assertEquals("trusted: a change goes at once", listOf("ledbright 40"), writes(console.sent))
    }

    @Test
    fun `on a claimed piano every note and setting write waits for auth ok, the Full-power restore with them, then goes in order`() = runTest {
        val key = LockCrypto.newKeyPair().public   // any 32 bytes do for K
        val piano = EmulatedConsole().apply { handle("fullpower 0") }   // off without a person's choice: the app restores it
        piano.lock = lock().apply { claimedBefore(key) }
        keys.kept[ADDRESS] = key
        val answers = mutableListOf<String>()
        val console = FakeConsole { line -> if (line.startsWith("auth ")) emptyList<String>().also { answers += piano.handle(line) } else piano.handle(line) }
        val repo = connected(console)
        val gate = LockGate(link, repo.trust, backgroundScope)
        runCurrent()
        assertEquals(LockPhase.Proving, repo.lock.value.phase)
        assertTrue("the proof went straight after the dump", console.sent[1].startsWith("auth "))
        assertEquals("the player sees it connecting, and starts nothing", LinkState.Connecting, gate.state.value)
        repo.set("ledbright", 40)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("no setting write before auth ok, the Full-power restore neither", emptyList<String>(), writes(console.sent))
        assertEquals("nor a read behind them", 2, console.sent.size)
        gate.send(batch(0x90, 60, 80, 0xB0, 64, 127, 0xB0, 7, 100, 0x80, 61, 0, 0x90, 62, 0, 0xB0, 64, 0, 0xB0, 123, 0), dropPending = false)
        assertEquals("no Note On before auth ok; releasing goes", listOf("80 3D 00", "90 3E 00", "B0 40 00", "B0 7B 00"), link.messages)

        answers.forEach(console::emit)
        runCurrent()
        assertEquals(LockPhase.Trusted, repo.lock.value.phase)
        assertEquals(
            "released in order after auth ok, each read back where it was",
            listOf("fullpower 1", "get fullpower", "save", "ledbright 40", "get ledbright"),
            console.sent.drop(2),
        )
        assertEquals("1", repo.ready().values["fullpower"])
        assertEquals(link.state.value, gate.state.value)
        link.clear()
        gate.send(batch(0x90, 60, 80), dropPending = false)
        assertEquals(listOf("90 3C 50"), link.messages)
    }

    @Test
    fun `the stop always goes out - off over the console and the stop sequence past the gate, proving and read only`() = runTest {
        val piano = EmulatedConsole(lock = lock(EmulatedLock.Mode.Claimed))
        val console = FakeConsole { line -> if (line.startsWith("auth ")) emptyList() else piano.handle(line) }   // the proof goes unanswered
        keys.kept[ADDRESS] = ByteArray(32) { 7 }
        val repo = connected(console)
        val gate = LockGate(link, repo.trust, backgroundScope)
        runCurrent()
        assertEquals(LockPhase.Proving, repo.lock.value.phase)
        repo.action(PianoAction.AllKeysOff)
        assertEquals("off", console.sent.last())
        gate.send(batch(0xB0, 64, 0, 0xB0, 123, 0), dropPending = true)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), link.messages)
        assertTrue(link.sent.all { it.dropPending })
        advanceTimeBy(LockHandshake.HOLD_MS)
        runCurrent()
        assertEquals(LockPhase.NotAccepted, repo.lock.value.phase)
        repo.action(PianoAction.AllKeysOff)
        assertEquals("read only too", "off", console.sent.last())
        assertTrue("and the crash handler's stop", gate.emergencySilence(200))
        assertEquals(listOf(200L), link.emergencySilences)
    }

    @Test
    fun `read only on a claimed piano the tablet keeps no key for - shown, never changed, reads still go`() = runTest {
        val console = FakeConsole(EmulatedConsole(lock = lock(EmulatedLock.Mode.Claimed))::handle)
        val repo = connected(console)
        val gate = LockGate(link, repo.trust, backgroundScope)
        runCurrent()
        assertEquals(LockPhase.LockedOut, repo.lock.value.phase)
        assertEquals("no claim and no proof", listOf("dump"), console.sent)
        assertEquals(LinkTrust(0, Trust.Refused(LinkError.LockedOut)), repo.trust.value)
        assertEquals(LinkError.LockedOut.toState(), gate.state.value)
        val ready = repo.ready()
        assertEquals("160", ready.values["ledbright"])
        assertFalse(ready.lock.writable)
        assertNull("the one-use nonce is no screen's", ready.facts["nonce"])
        assertEquals("the lock said in words (v1.27 — M64: its raw facts no screen's either)", LockWord.Claimed, ready.lock.piano)
        assertTrue(listOf("lockproto", "lock", "auth", "guest").none { it in ready.facts })
        repo.set("ledbright", 40)
        repo.preset("soft")
        repo.action(PianoAction.Save)
        repo.action(PianoAction.LedTest, 60)
        repo.openPairing()
        repo.guestPlay(60)
        repo.unlock()
        repo.leave()
        repo.action(PianoAction.Status)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("nothing written", emptyList<String>(), writes(console.sent))
        assertTrue("reads go: the status, and the setting read back", console.sent.containsAll(listOf("status", "get ledbright")))
        assertEquals("what shows comes back to what the piano holds", "160", repo.ready().values["ledbright"])
        gate.send(batch(0x90, 60, 80), dropPending = false)
        assertTrue(link.messages.isEmpty())
    }

    @Test
    fun `a key the piano refuses - read only, one attempt, never again on this connection`() = runTest {
        val console = FakeConsole(EmulatedConsole(lock = lock(EmulatedLock.Mode.Claimed))::handle)
        keys.kept[ADDRESS] = ByteArray(32) { 1 }   // another piano's, or a forgotten one
        val repo = connected(console)
        assertEquals(LockPhase.NotAccepted, repo.lock.value.phase)
        assertEquals(LinkTrust(0, Trust.Refused(LinkError.NotAccepted)), repo.trust.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, console.sent.count { it.startsWith("auth ") })
        assertTrue("the kept key is not thrown away", keys.kept.containsKey(ADDRESS))
    }

    @Test
    fun `a refused key with the pairing window open - the tablet claims the piano instead`() = runTest {
        val piano = lock(EmulatedLock.Mode.Pairing)
        val console = FakeConsole(EmulatedConsole(lock = piano)::handle)
        keys.kept[ADDRESS] = ByteArray(32) { 1 }
        val repo = connected(console)
        assertEquals(listOf("auth", "claim"), console.sent.drop(1).map { it.substringBefore(' ') })
        assertEquals(LockPhase.Trusted, repo.lock.value.phase)
        assertTrue(piano.knows(keys.kept.getValue(ADDRESS)))
        assertEquals("the other device keeps its key", 2, piano.keyCount)
    }

    @Test
    fun `each claim refusal - not open, already 4 devices, bad key twice, wait 1 s`() = runTest {
        val notOpen = connected(scripted("pairing", listOf(listOf("  claim refused — not open (open pairing on the piano's screen)"))))
        assertEquals(LockPhase.LockedOut, notOpen.lock.value.phase)

        val full = connected(scripted("pairing", listOf(listOf("  claim refused — already 4 devices (lock forget first)"))))
        assertEquals(LockPhase.LockedOut, full.lock.value.phase)
        assertEquals("  claim refused — already 4 devices (lock forget first)".trim(), full.lock.value.said)

        val claims = mutableListOf<Pair<Long, String>>()
        val bad = scripted("pairing", listOf(listOf("  claim refused — bad key"), listOf("  claim refused — bad key")), claims)
        val twice = connected(bad)
        assertEquals(LockPhase.Proving, twice.lock.value.phase)
        advanceTimeBy(LockHandshake.ATTEMPT_GAP_MS)
        runCurrent()
        assertEquals(2, claims.size)
        assertNotEquals("a fresh key pair the second time", claims[0].second, claims[1].second)
        assertTrue(claims[1].first - claims[0].first >= LockHandshake.ATTEMPT_GAP_MS)
        assertEquals(LockPhase.LockedOut, twice.lock.value.phase)

        claims.clear()
        val pianoPublic = LockCrypto.hex(LockCrypto.newKeyPair().public)
        val waiting = scripted("pairing", listOf(listOf("  claim refused — wait 1 s"), listOf("!claim=$pianoPublic", "  claimed")), claims)
        val again = connected(waiting)
        advanceTimeBy(LockHandshake.ATTEMPT_GAP_MS)
        runCurrent()
        assertEquals("the same claim again, a second later", claims[0].second, claims[1].second)
        assertEquals(LockPhase.Trusted, again.lock.value.phase)
        assertTrue(keys.kept.containsKey(ADDRESS))
    }

    @Test
    fun `an open piano whose claim is refused stays open and trusted, and keeps no key`() = runTest {
        val repo = connected(scripted("open", listOf(listOf("  claim refused — bad key"), listOf("  claim refused — bad key")), auth = "1"))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(LockPhase.Trusted, repo.lock.value.phase)
        assertFalse(keys.kept.containsKey(ADDRESS))
    }

    @Test
    fun `a tablet that can't keep a key claims nothing`() = runTest {
        keys.keepable = false
        val piano = lock()
        val console = FakeConsole(EmulatedConsole(lock = piano)::handle)
        val repo = connected(console)
        assertTrue(console.sent.none { it.startsWith("claim ") })
        assertEquals("the piano stays open, and trusts everyone", LockPhase.Trusted, repo.lock.value.phase)
        assertEquals(0, piano.keyCount)
    }

    @Test
    fun `no answer within 3 s is not accepted, auth ok even late trusts the connection`() = runTest {
        val key = ByteArray(32) { 3 }
        val piano = EmulatedConsole(lock = lock().apply { claimedBefore(key) })
        val late = mutableListOf<String>()
        val console = FakeConsole { line -> if (line.startsWith("auth ")) emptyList<String>().also { late += piano.handle(line) } else piano.handle(line) }
        keys.kept[ADDRESS] = key
        val repo = connected(console)
        advanceTimeBy(LockHandshake.HOLD_MS - 1)
        runCurrent()
        assertEquals(LockPhase.Proving, repo.lock.value.phase)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(LockPhase.NotAccepted, repo.lock.value.phase)
        late.forEach(console::emit)
        runCurrent()
        assertEquals(LockPhase.Trusted, repo.lock.value.phase)
    }

    @Test
    fun `wait 1 s - the same proof again a second later, at most one attempt a second`() = runTest {
        val key = ByteArray(32) { 5 }
        val piano = EmulatedConsole(lock = lock().apply { claimedBefore(key) })
        val times = mutableListOf<Pair<Long, String>>()
        var first = true
        val console = FakeConsole { line ->
            if (!line.startsWith("auth ")) return@FakeConsole piano.handle(line)
            times += currentTime to line
            if (first) listOf("  auth refused — wait 1 s").also { first = false } else piano.handle(line)
        }
        keys.kept[ADDRESS] = key
        val repo = connected(console)
        assertEquals(1, times.size)
        advanceTimeBy(LockHandshake.ATTEMPT_GAP_MS)
        runCurrent()
        assertEquals(2, times.size)
        assertEquals("the nonce was kept: the same proof", times[0].second, times[1].second)
        assertTrue(times[1].first - times[0].first >= LockHandshake.ATTEMPT_GAP_MS)
        assertEquals(LockPhase.Trusted, repo.lock.value.phase)
    }

    @Test
    fun `trust is the connection's - the next one proves the key again, over its own nonce`() = runTest {
        val piano = EmulatedConsole(lock = lock())
        val console = FakeConsole(piano::handle)
        val repo = connected(console)
        assertEquals(LockPhase.Trusted, repo.lock.value.phase)
        link.drop()
        runCurrent()
        assertEquals(PianoLock.NONE, repo.lock.value)
        piano.connected()
        link.connectionNumber = 1
        link.connect(null)
        runCurrent()
        val proofs = console.sent.filter { it.startsWith("auth ") }
        assertEquals(1, proofs.size)
        assertEquals(LockPhase.Trusted, repo.lock.value.phase)
        assertEquals(LinkTrust(1, Trust.Open), repo.trust.value)
        link.drop()
        runCurrent()
        piano.connected()
        link.connectionNumber = 2
        link.connect(null)
        runCurrent()
        val again = console.sent.filter { it.startsWith("auth ") }
        assertEquals(2, again.size)
        assertNotEquals("a new nonce, a new proof", again[0], again[1])
    }

    @Test
    fun `the lock's commands - another device's pairing, guest play with its time left, and unlocking forgets the key`() = runTest {
        val piano = EmulatedConsole(lock = lock())
        val console = FakeConsole(piano::handle)
        val repo = connected(console)
        assertTrue(repo.lock.value.manageable)

        repo.openPairing()
        runCurrent()
        assertTrue(console.sent.containsAll(listOf("lock pairwindow", "get !lock", "get !guest")))
        assertEquals(LockWord.Pairing, repo.lock.value.piano)

        repo.guestPlay(60)
        runCurrent()
        assertEquals("lock guest 60", console.sent.first { it.startsWith("lock guest") })
        assertEquals(3_600L, repo.lock.value.guestSeconds)
        advanceTimeBy(10 * 60_000L)
        assertEquals(3_000L, repo.lock.value.guestLeft(currentTime))
        repo.guestPlay(0)
        runCurrent()
        assertEquals(0L, repo.lock.value.guestSeconds)

        repo.unlock()
        runCurrent()
        assertTrue(console.sent.contains("lock forget"))
        assertEquals(LockWord.Open, repo.lock.value.piano)
        assertFalse(repo.lock.value.keyStored)
        assertFalse("the key goes with the lock: the next connection claims afresh", keys.kept.containsKey(ADDRESS))
        assertEquals("open trusts everyone", LockPhase.Trusted, repo.lock.value.phase)
    }

    @Test
    fun `refused - not authorised - means the piano no longer trusts this connection`() = runTest {
        val repo = connected(scripted("open", emptyList(), auth = "1", other = { line -> if (line.startsWith("ledbright ")) listOf("  refused — not authorised (send auth first)") else emptyList() }))
        assertEquals(LockPhase.Trusted, repo.lock.value.phase)
        repo.set("ledbright", 40)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(LockPhase.NotAccepted, repo.lock.value.phase)
        assertEquals("refused — not authorised (send auth first)", repo.ready().lastError)
    }

    /**
     * A piano whose dump says `!lock=`[word] (`!auth=`[auth]) and answers its claims from [claims] in turn (recording each
     * with its time in [seen]); everything else [other] answers.
     */
    private fun TestScope.scripted(
        word: String,
        claims: List<List<String>>,
        seen: MutableList<Pair<Long, String>> = mutableListOf(),
        auth: String = "0",
        other: (String) -> List<String> = { emptyList() },
    ): FakeConsole {
        val left = ArrayDeque(claims)
        return FakeConsole { line ->
            when {
                line == "dump" -> listOf("!proto=1", "!lockproto=1", "!lock=$word", "!auth=$auth", "!nonce=" + "ab".repeat(16), "!guest=0", "ledbright=160", "end")
                line.startsWith("claim ") -> {
                    seen += currentTime to line
                    left.removeFirstOrNull() ?: emptyList()
                }
                else -> other(line)
            }
        }
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
    }
}
