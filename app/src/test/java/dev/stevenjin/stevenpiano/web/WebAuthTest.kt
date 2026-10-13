// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.web.Sessions.Transport.RELAY
import dev.stevenjin.stevenpiano.web.Sessions.Transport.TAILNET
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.concurrent.thread

/** The panel's PIN, sessions and login guard (the audit's point 3); the sessions' file (v1.15 — M42); a safer sign-in (v1.24 — M56). */
class WebAuthTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a PIN is six digits and nothing else`() {
        assertTrue(PinHash.isValid("123456"))
        assertTrue(PinHash.isValid("000000"))
        for (bad in listOf("", "12345", "1234567", "12345a", " 23456", "１２３４５６", "12 456")) assertFalse(bad, PinHash.isValid(bad))
        assertThrows(IllegalArgumentException::class.java) { PinHash.create("12345") }
    }

    @Test
    fun `the hash is PBKDF2-HMAC-SHA256 with 100,000 rounds over a random 16-byte salt`() {
        val pin = PinHash.create("482913")
        val salt = Base64.getDecoder().decode(pin.saltText)
        val hash = Base64.getDecoder().decode(pin.hashText)
        assertEquals(16, salt.size)
        assertEquals(32, hash.size)
        assertEquals(100_000, PinHash.ITERATIONS)
        // Worked out independently with the JDK's own PBKDF2.
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("482913".toCharArray(), salt, 100_000, 256)).encoded
        assertTrue(expected.contentEquals(hash))
        assertTrue(pin.matches("482913"))
        assertFalse(pin.matches("482914"))
        assertFalse(pin.matches("48291"))
        assertFalse("nothing prints the hash", pin.toString().contains(pin.hashText))
    }

    @Test
    fun `every PIN gets its own salt, so the same digits never hash the same`() {
        val a = PinHash.create("111111")
        val b = PinHash.create("111111")
        assertNotEquals(a.saltText, b.saltText)
        assertNotEquals(a.hashText, b.hashText)
        assertTrue(a.matches("111111") && b.matches("111111"))
    }

    @Test
    fun `a kept hash comes back only whole`() {
        val pin = PinHash.create("246810", SecureRandom())
        val restored = PinHash.restore(pin.saltText, pin.hashText)
        assertTrue(restored!!.matches("246810"))
        assertNull(PinHash.restore(null, pin.hashText))
        assertNull(PinHash.restore(pin.saltText, null))
        assertNull(PinHash.restore("not base64!", pin.hashText))
        assertNull("a short salt", PinHash.restore(Base64.getEncoder().encodeToString(ByteArray(8)), pin.hashText))
        assertNull("a short hash", PinHash.restore(pin.saltText, Base64.getEncoder().encodeToString(ByteArray(16))))
    }

    @Test
    fun `comparisons run over every byte whatever the first difference`() {
        assertTrue(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(ConstantTime.equals(byteArrayOf(9, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2), byteArrayOf(1, 2, 0)))
        assertTrue(ConstantTime.equals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `sessions are random 32-byte tokens, at most thirty-two, the least recently used going first`() {
        var now = 1_000_000L
        val sessions = Sessions(clock = { now })
        val first = sessions.open(TAILNET)
        assertTrue(Sessions.TOKEN.matches(first))
        assertTrue(sessions.isValid(first, TAILNET))
        assertFalse(sessions.isValid(null, TAILNET))
        assertFalse(sessions.isValid(first.dropLast(1) + if (first.last() == 'A') "B" else "A", TAILNET))
        assertFalse("not a token at all", sessions.isValid("../../etc/passwd", TAILNET))

        val more = (1..31).map { sessions.open(TAILNET) }
        assertEquals(32, Sessions.MAX_SESSIONS)
        assertEquals(32, sessions.count())
        now += 1_000
        sessions.isValid(more[0], TAILNET)   // used: the least recently used is now `first`
        val next = sessions.open(TAILNET)
        assertEquals(32, sessions.count())
        assertFalse("the least recently used went", sessions.isValid(first, TAILNET))
        assertTrue(sessions.isValid(more[0], TAILNET))
        assertTrue(sessions.isValid(next, TAILNET))
    }

    /** M56: a fixed lifetime of 90 days from the sign-in, then a known device until the cookie's year is out. */
    @Test
    fun `a session ends 90 days after its sign-in despite daily use, and its cookie marks a known device for a year (M56)`() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals(90 * day, Sessions.VALID_MS)
        assertEquals(365 * day, Sessions.KNOWN_MS)
        assertEquals("the cookie lasts the known device's year", Sessions.KNOWN_MS / 1_000, WebCookies.SESSION_MAX_AGE_S)
        var now = 1_700_000_000_000L
        val sessions = Sessions(clock = { now })
        val token = sessions.open(RELAY)
        repeat(89) {
            now += day
            assertTrue("day ${it + 1}: used every day", sessions.isValid(token, RELAY))
        }
        now += day - 1
        assertTrue("a moment before the 90 days are up", sessions.isValid(token, RELAY))
        now += 1
        assertFalse("90 days after the sign-in, however often it was used", sessions.isValid(token, RELAY))
        assertFalse("for Listening too", sessions.isValid(token))
        assertEquals("no longer counted as signed in", 0, sessions.count())
        assertTrue("its browser is a known device", sessions.knownDevice(token, RELAY))
        assertFalse("on its own transport alone", sessions.knownDevice(token, TAILNET))
        now += 365 * day - 90 * day - 1
        assertTrue("until a year after the sign-in", sessions.knownDevice(token, RELAY))
        now += 1
        assertFalse("then forgotten, as the browser forgets the cookie", sessions.knownDevice(token, RELAY))
        assertFalse(sessions.knownDevice(null, RELAY))
        assertFalse(sessions.knownDevice("A".repeat(43), RELAY))
        // A stamp a day ahead of the tablet's clock (it was set back) holds; further ahead, the row is forgotten.
        val ahead = sessions.open(TAILNET)
        now -= day
        assertTrue(sessions.isValid(ahead, TAILNET))
        now -= 1
        assertFalse(sessions.isValid(ahead, TAILNET))
        assertFalse(sessions.knownDevice(ahead, TAILNET))
    }

    @Test
    fun `a session opened on one transport is never good on the other, and a row from before 1_24 binds to its first (M56)`() {
        val now = 1_700_000_000_000L
        val sessions = Sessions(clock = { now })
        val tailnet = sessions.open(TAILNET)
        val relay = sessions.open(RELAY)
        assertTrue(sessions.isValid(tailnet, TAILNET))
        assertFalse("a Tailscale session over the relay", sessions.isValid(tailnet, RELAY))
        assertTrue(sessions.isValid(relay, RELAY))
        assertFalse("a relay session on the tablet's own address", sessions.isValid(relay, TAILNET))
        assertTrue("Listening asks of any transport", sessions.isValid(tailnet) && sessions.isValid(relay))
        assertFalse("nor is it a known device there", sessions.knownDevice(tailnet, RELAY))

        // An unbound row (kept before 1.24): bound by its first use, then good there alone.
        val store = FakeSessionStore()
        val old = "B".repeat(43)
        val older = "C".repeat(43)
        store.save(mapOf(Sessions.digest(old) to SessionRecord(null, now, now), Sessions.digest(older) to SessionRecord(null, now, now)))
        val upgraded = Sessions(store, clock = { now })
        assertTrue("Listening's look binds nothing", upgraded.isValid(old))
        assertNull(store.saved.getValue(Sessions.digest(old)).transport)
        assertTrue("its first use, over the relay", upgraded.isValid(old, RELAY))
        assertEquals("saved bound at once", RELAY, store.saved.getValue(Sessions.digest(old)).transport)
        assertFalse(upgraded.isValid(old, TAILNET))
        assertTrue("a known device binds it too", upgraded.knownDevice(older, TAILNET))
        assertEquals(TAILNET, store.saved.getValue(Sessions.digest(older)).transport)
        assertFalse(upgraded.isValid(older, RELAY))
        assertTrue(upgraded.isValid(older, TAILNET))
    }

    @Test
    fun `logging out ends one session, and closing all ends every one`() {
        val sessions = Sessions()
        val a = sessions.open(TAILNET)
        val b = sessions.open(TAILNET)
        sessions.close(a)
        assertFalse(sessions.isValid(a, TAILNET))
        assertFalse("logged out is no known device either", sessions.knownDevice(a, TAILNET))
        assertTrue(sessions.isValid(b, TAILNET))
        sessions.closeAll()
        assertFalse(sessions.isValid(b, TAILNET))
        assertNotEquals("tokens never repeat", sessions.open(TAILNET), sessions.open(TAILNET))
    }

    /** M56: the PIN again before the microphone or an update; signing in counts; three wrong in a row end the session. */
    @Test
    fun `confirming it's you holds five minutes, a sign-in counts as one, and the third wrong PIN in a row ends the session (M56)`() {
        var now = 1_700_000_000_000L
        val sessions = Sessions(clock = { now })
        val token = sessions.open(TAILNET)
        assertTrue("a fresh sign-in counts", sessions.confirmed(token))
        now += Sessions.CONFIRM_MS - 1
        assertTrue(sessions.confirmed(token))
        now += 1
        assertFalse("five minutes on", sessions.confirmed(token))
        sessions.confirm(token)
        assertTrue(sessions.confirmed(token))
        now += Sessions.CONFIRM_MS
        assertFalse(sessions.confirmed(token))
        assertFalse(sessions.confirmed(null))
        assertFalse(sessions.confirmed("A".repeat(43)))

        assertFalse(sessions.wrongConfirm(token))
        assertFalse(sessions.wrongConfirm(token))
        sessions.confirm(token)   // a right PIN: the count starts again
        assertFalse(sessions.wrongConfirm(token))
        assertFalse(sessions.wrongConfirm(token))
        assertTrue(sessions.isValid(token, TAILNET))
        assertTrue("the third wrong PIN in a row", sessions.wrongConfirm(token))
        assertFalse("the session has ended", sessions.isValid(token, TAILNET))
        assertFalse("and its browser is no known device", sessions.knownDevice(token, TAILNET))
        assertTrue("an ended session says so", sessions.wrongConfirm(token))
        assertFalse(sessions.confirmed(token))
    }

    @Test
    fun `a session outlives a restart of the app with its transport and sign-in, and one ninety days old does not act (M42, M56)`() {
        val start = 1_700_000_000_000L
        var now = start
        val file = File(tmp.root, "web/sessions.json")
        val before = Sessions(FileSessionStore(file), clock = { now })
        val kept = before.open(TAILNET)
        now += 1_000
        val relayed = before.open(RELAY)
        val text = file.readText()
        assertTrue("the file holds the tokens' digests…", sha256(kept) in text && sha256(relayed) in text)
        assertFalse("…and never a token", kept in text || relayed in text)
        val json = JSONObject(text)
        assertEquals(setOf("v", "sessions"), json.keys().asSequence().toSet())
        assertEquals(FileSessionStore.VERSION, json.getInt("v"))
        val row = json.getJSONObject("sessions").getJSONObject(sha256(relayed))
        assertEquals(setOf("t", "in", "used"), row.keys().asSequence().toSet())
        assertEquals("relay", row.getString("t"))
        assertEquals(start + 1_000, row.getLong("in"))

        now += 30_000
        assertTrue(before.isValid(kept, TAILNET))
        assertEquals("a use within the minute is not written", start, FileSessionStore(file).load(now).sessions.getValue(sha256(kept)).used)
        now += 31_000
        assertTrue(before.isValid(kept, TAILNET))
        assertEquals("past the minute it is", now, FileSessionStore(file).load(now).sessions.getValue(sha256(kept)).used)

        val restarted = Sessions(FileSessionStore(file), clock = { now })   // the app starts again
        assertEquals(2, restarted.count())
        assertTrue("still signed in", restarted.isValid(kept, TAILNET))
        assertFalse("still bound", restarted.isValid(relayed, TAILNET))
        assertTrue(restarted.isValid(relayed, RELAY))

        now = start + Sessions.VALID_MS
        val later = Sessions(FileSessionStore(file), clock = { now })
        assertFalse("ninety days from its sign-in", later.isValid(kept, TAILNET))
        assertTrue("a known device still", later.knownDevice(kept, TAILNET))
        assertEquals(1, later.count())
        now = start + Sessions.KNOWN_MS + 1_000
        assertFalse("a year on, forgotten", Sessions(FileSessionStore(file), clock = { now }).knownDevice(relayed, RELAY))
    }

    @Test
    fun `the thirty-third session sends the least recently used away, from the file too (M42)`() {
        var now = 1_700_000_000_000L
        val file = File(tmp.root, "web/sessions.json")
        val sessions = Sessions(FileSessionStore(file), clock = { now })
        val tokens = (1..32).map {
            now += 1_000
            sessions.open(TAILNET)
        }
        now += 1_000
        assertTrue(sessions.isValid(tokens[0], TAILNET))   // used: tokens[1] is now the least recently used
        val next = sessions.open(TAILNET)
        val saved = FileSessionStore(file).load(now).sessions
        assertEquals(32, saved.size)
        assertFalse("the least recently used went from the file", sha256(tokens[1]) in saved)
        assertTrue(sha256(tokens[0]) in saved && sha256(next) in saved)

        val restarted = Sessions(FileSessionStore(file), clock = { now })
        assertEquals(32, restarted.count())
        assertFalse(restarted.isValid(tokens[1], TAILNET))
        assertTrue(restarted.isValid(tokens[0], TAILNET))
        assertTrue(restarted.isValid(next, TAILNET))
        now += 1_000
        restarted.open(TAILNET)
        assertFalse("after a restart too, the least recently used goes first", restarted.isValid(tokens[2], TAILNET))
        assertTrue(restarted.isValid(tokens[3], TAILNET))
    }

    @Test
    fun `logging out takes a session out of the file, and closing all empties it (M42)`() {
        val file = File(tmp.root, "web/sessions.json")
        val sessions = Sessions(FileSessionStore(file))
        val a = sessions.open(TAILNET)
        val b = sessions.open(RELAY)
        sessions.close(a)
        assertEquals(setOf(sha256(b)), FileSessionStore(file).load(0).sessions.keys)
        sessions.closeAll()   // a new PIN, or the web panel turned off
        val empty = JSONObject(file.readText())
        assertEquals(2, empty.getInt("v"))
        assertEquals("an empty table", 0, empty.getJSONObject("sessions").length())
        val restarted = Sessions(FileSessionStore(file))
        assertFalse("nobody is signed in at the next start", restarted.isValid(a, TAILNET))
        assertFalse(restarted.isValid(b, RELAY))
        assertEquals(0, restarted.count())
    }

    @Test
    fun `a missing or corrupt file reads as no sessions (M42, M56)`() {
        val file = File(tmp.root, "web/sessions.json")
        val now = 1_700_000_100_000L
        assertEquals("missing", SessionStore.Loaded.NONE, FileSessionStore(file).load(now))
        val digest = "ab".repeat(32)
        file.parentFile!!.mkdirs()
        val row = """{"t":"tailnet","in":1700000000000,"used":1700000000000}"""
        val corrupt = listOf(
            "", "not json", "[]", "{",
            // Before 1.24: digests to whole numbers, nothing else.
            "{\"$digest\":\"1700000000000\"}", "{\"$digest\":1.5}", "{\"$digest\":null}", "{\"${digest.uppercase()}\":1}", "{\"$digest\":1,\"token\":2}",
            // Since 1.24: exactly v 2 and the sessions, each row exactly its three (t absent while unbound).
            """{"v":3,"sessions":{"$digest":$row}}""", """{"v":"2","sessions":{"$digest":$row}}""", """{"v":2}""", """{"v":2,"sessions":[]}""",
            """{"v":2,"sessions":{},"more":1}""", """{"v":2,"sessions":{"${digest.uppercase()}":$row}}""", """{"v":2,"sessions":{"$digest":1700000000000}}""",
            """{"v":2,"sessions":{"$digest":{"t":"wifi","in":1,"used":1}}}""", """{"v":2,"sessions":{"$digest":{"t":null,"in":1,"used":1}}}""",
            """{"v":2,"sessions":{"$digest":{"in":1.5,"used":1}}}""", """{"v":2,"sessions":{"$digest":{"in":1}}}""",
            """{"v":2,"sessions":{"$digest":{"in":1,"used":1,"token":"x"}}}""",
        )
        for (text in corrupt) {
            file.writeText(text)
            assertEquals(text, SessionStore.Loaded.NONE, FileSessionStore(file).load(now))
        }
        file.writeText("""{"v":2,"sessions":{"$digest":$row}}""" + " ".repeat(FileSessionStore.MAX_BYTES.toInt()))
        assertEquals("larger than any table", SessionStore.Loaded.NONE, FileSessionStore(file).load(now))
        file.writeText("""{"v":2,"sessions":{"$digest":$row,"${"cd".repeat(32)}":{"in":5,"used":6}}}""")
        assertEquals(
            SessionStore.Loaded(mapOf(digest to SessionRecord(Sessions.Transport.TAILNET, 1_700_000_000_000L, 1_700_000_000_000L), "cd".repeat(32) to SessionRecord(null, 5, 6))),
            FileSessionStore(file).load(now),
        )
        file.writeText("{\"$digest\":1700000000000}")
        assertEquals("the table before 1.24", SessionStore.Loaded(mapOf(digest to SessionRecord(null, now, 1_700_000_000_000L)), old = true), FileSessionStore(file).load(now))

        file.writeText("not json")
        val sessions = Sessions(FileSessionStore(file))
        assertEquals(0, sessions.count())
        val token = sessions.open(TAILNET)
        assertTrue("a corrupt file is written over whole", Sessions(FileSessionStore(file)).isValid(token, TAILNET))
    }

    /** M56's upgrade: the sessions signed in before 1.24 stay signed in, unbound, and are written in the new form at once. */
    @Test
    fun `a table kept before 1_24 is read as unbound sessions signed in now, written again at once, each bound by its first use (M56)`() {
        val upgradeAt = 1_800_000_000_000L
        var now = upgradeAt
        val file = File(tmp.root, "web/sessions.json")
        file.parentFile!!.mkdirs()
        val usedLately = "D".repeat(43)
        val usedLastYear = "E".repeat(43)
        val longGone = "F".repeat(43)
        file.writeText(JSONObject(mapOf(sha256(usedLately) to upgradeAt - 60_000, sha256(usedLastYear) to upgradeAt - 300L * 86_400_000, sha256(longGone) to upgradeAt - 400L * 86_400_000)).toString())
        val sessions = Sessions(FileSessionStore(file), clock = { now })
        val written = JSONObject(file.readText())
        assertEquals("the new form at once", 2, written.getInt("v"))
        val rows = written.getJSONObject("sessions")
        assertEquals("a session a year unused stays gone, as before", setOf(sha256(usedLately), sha256(usedLastYear)), rows.keys().asSequence().toSet())
        for (digest in rows.keys()) {
            val row = rows.getJSONObject(digest)
            assertFalse("unbound", row.has("t"))
            assertEquals("signed in at the upgrade", upgradeAt, row.getLong("in"))
        }
        assertEquals("both still signed in", 2, sessions.count())
        assertTrue("first used over the relay", sessions.isValid(usedLately, RELAY))
        assertFalse("then good there alone", sessions.isValid(usedLately, TAILNET))
        assertTrue(sessions.isValid(usedLastYear, TAILNET))
        assertFalse(sessions.isValid(usedLastYear, RELAY))
        now = upgradeAt + Sessions.VALID_MS
        assertFalse("ninety days from the upgrade", Sessions(FileSessionStore(file), clock = { now }).isValid(usedLately, RELAY))
    }

    /** M56's upgrade: Panel on Wi-Fi too retired, its cookies (which may have crossed the Wi-Fi in the clear) dropped if it was on. */
    @Test
    fun `retiring Panel on Wi-Fi too drops the unbound sessions if it was on, keeps them if not, removes its key, and changes nothing twice (M56)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val wifiKey = booleanPreferencesKey("webOnWifi")
        val now = 1_800_000_000_000L
        for (wasOn in listOf(true, false, null)) {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "wifi-$wasOn.preferences_pb") }
            val settings = SettingsRepository(store)
            if (wasOn != null) store.edit { it[wifiKey] = wasOn }
            val sessionStore = FakeSessionStore()
            val old = "G".repeat(43)
            sessionStore.save(mapOf(Sessions.digest(old) to SessionRecord(null, now, now)))
            val sessions = Sessions(sessionStore, clock = { now })
            val opened = sessions.open(TAILNET)   // bound: its cookie never crossed the Wi-Fi in the clear
            assertEquals(wasOn, settings.wifiPanelBefore())

            WebPanel.retireWifiPanel(sessions, settings)
            val kept = sessions.isValid(old, RELAY)
            assertEquals("$wasOn: the unbound session", wasOn != true, kept)
            assertTrue("$wasOn: a bound one stays", sessions.isValid(opened, TAILNET))
            assertNull("$wasOn: the key is gone", store.data.first()[wifiKey])
            assertNull(settings.wifiPanelBefore())

            val before = sessionStore.saved
            WebPanel.retireWifiPanel(sessions, settings)
            assertEquals("$wasOn: a second run changes nothing", before, sessionStore.saved)
            assertEquals(kept, sessions.isValid(old, RELAY))
        }
        // A stop between the steps: the sessions dropped, the key still there. The next run drops again, then forgets the key.
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "wifi-again.preferences_pb") }
        val settings = SettingsRepository(store)
        store.edit { it[wifiKey] = true }
        val sessions = Sessions(clock = { now })
        sessions.dropUnbound()
        WebPanel.retireWifiPanel(sessions, settings)
        assertNull(store.data.first()[wifiKey])
        scope.cancel()
    }

    @Test
    fun `five wrong PINs lock the address for 30 s, then each doubles to ten minutes`() {
        var now = 0L
        val guard = LoginGuard(clock = { now })
        repeat(4) { assertEquals(0L, guard.failed("10.0.0.2")) }
        assertEquals(0L, guard.waitMs("10.0.0.2"))
        assertEquals(30_000L, guard.failed("10.0.0.2"))
        assertEquals(30_000L, guard.waitMs("10.0.0.2"))
        now += 10_000
        assertEquals(20_000L, guard.waitMs("10.0.0.2"))
        val waits = mutableListOf<Long>()
        repeat(6) {
            now += guard.waitMs("10.0.0.2")
            waits += guard.failed("10.0.0.2")
        }
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), waits)
    }

    @Test
    fun `one address hammering wrong PINs no longer locks the gate for everyone (audit W1)`() {
        val now = 0L
        val guard = LoginGuard(clock = { now })
        // Five wrong PINs lock the one address that sent them, for thirty seconds...
        repeat(5) { guard.failed("10.0.0.1") }
        assertEquals(30_000L, guard.waitMs("10.0.0.1"))
        // ...but a different address is still free: the old shared lock barred everyone here.
        assertEquals("a fresh address is not locked by another's failures", 0L, guard.waitMs("10.9.9.9"))
        // A whole burst from one address still cannot reach the (much higher) global threshold: once
        // that address is locked its further tries are refused uncounted, so nobody else is affected.
        repeat(50) { if (guard.waitMs("10.0.0.1") == 0L) guard.failed("10.0.0.1") }
        assertEquals("one address cannot lock everyone", 0L, guard.waitMs("10.9.9.9"))
    }

    @Test
    fun `the global gate still bounds a distributed brute force, gently and capped at a minute, and only a quiet hour clears it (M56)`() {
        var now = 0L
        val guard = LoginGuard(clock = { now })
        // One under the global threshold, spread over addresses that each stay below their own
        // five-in-a-row lock (four each), and a fresh caller is still free.
        repeat(LoginGuard.GLOBAL_THRESHOLD - 1) { guard.failed("10.1.${it / 4}.${it % 4}") }
        assertEquals("under the global threshold nobody else is locked", 0L, guard.waitMs("10.9.9.9"))
        // The threshold'th wrong PIN trips the global gate — for five seconds, not the thirty the old shared lock gave after five.
        guard.failed("10.1.9.9")
        assertEquals(LoginGuard.GLOBAL_FIRST_LOCK_MS, guard.waitMs("10.9.9.9"))
        // However long the flood runs (rotating addresses dodge the per-address lock), the global wait doubles but never past a minute.
        val waits = mutableListOf<Long>()
        var fresh = 0
        repeat(8) {
            now += guard.waitMs("10.2.2.2")
            waits += guard.failed("10.3.${fresh / 200}.${fresh % 200}").also { fresh++ }
        }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L), waits)
        assertTrue("the global wait is capped at a minute, unlike the per-address ten", waits.all { it <= LoginGuard.GLOBAL_MAX_LOCK_MS })
        // M56: a right PIN clears its own key alone; the flood's count stands, and the next wrong PIN shuts the gate again.
        now += guard.waitMs("10.9.9.9")
        assertEquals(LoginGuard.Attempt.Right, guard.attempt("10.9.9.9") { true })
        assertEquals("the next wrong one shuts it for a minute again", LoginGuard.GLOBAL_MAX_LOCK_MS, guard.failed("10.4.0.1"))
        // An hour without a wrong try (longer than any wait) forgets everyone's count: the next wrong PIN is free.
        assertEquals(60 * 60_000L, LoginGuard.GLOBAL_QUIET_MS)
        now += LoginGuard.GLOBAL_QUIET_MS - 1
        assertEquals("a moment short of the hour", LoginGuard.GLOBAL_MAX_LOCK_MS, guard.failed("10.4.0.2"))
        now += LoginGuard.GLOBAL_QUIET_MS
        assertEquals("a quiet hour on, the count is forgotten", 0L, guard.failed("10.4.0.3"))
        repeat(LoginGuard.GLOBAL_THRESHOLD - 2) { guard.failed("10.5.${it / 4}.${it % 4}") }
        assertEquals("the threshold counts from the start again", 0L, guard.waitMs("10.9.9.9"))
        assertEquals(LoginGuard.GLOBAL_FIRST_LOCK_MS, guard.failed("10.6.0.1"))
    }

    /**
     * Audit delta 3: tries spread over rotating addresses, one as soon as the wait allows, for a day. The
     * listeners' gate (W1's, chosen behind the tailnet) weighs ~1,440 of them: half the million PINs in about
     * a year. The relay's, which the whole internet reaches, ~40 the first day and 24 a day from then on: half
     * the PINs in some 57 years.
     */
    @Test
    fun `the relay's gate lets a distributed brute force some 24 tries a day, the listeners' some 1,440 (audit delta 3)`() {
        fun triesInADay(guard: LoginGuard, clock: LongArray): Int {
            var tries = 0
            var fresh = 0
            val day = 24 * 60 * 60 * 1000L
            while (clock[0] < day) {
                val wait = guard.waitMs("10.200.0.1")
                if (wait > 0) {
                    clock[0] += wait
                    continue
                }
                guard.failed("10.${fresh / 65_000}.${fresh / 250 % 260}.${fresh % 250}")   // a new address each time: no per-address lock
                fresh++
                tries++
                clock[0] += 1_000   // a second a try, far faster than the gate lets any through
            }
            return tries
        }
        val lanClock = LongArray(1)
        val lan = triesInADay(LoginGuard(clock = { lanClock[0] }, maxKeys = 1_000_000), lanClock)
        val relayClock = LongArray(1)
        val relay = triesInADay(LoginGuard.forRelay(clock = { relayClock[0] }), relayClock)
        assertTrue("the listeners' gate: about 1,440 a day ($lan)", lan in 1_400..1_500)
        assertTrue("the relay's gate: its first tries, then 24 a day at its cap ($relay)", relay in 30..45)
        // Its schedule: ten wrong in a row, then a minute, doubling to an hour.
        var now = 0L
        val guard = LoginGuard.forRelay(clock = { now })
        repeat(LoginGuard.RELAY_GLOBAL_THRESHOLD - 1) { guard.failed("10.1.0.$it") }
        assertEquals(0L, guard.waitMs("10.9.9.9"))
        assertEquals(60_000L, guard.failed("10.1.1.1"))
        val waits = mutableListOf<Long>()
        repeat(8) {
            now += guard.waitMs("10.9.9.9")
            waits += guard.failed("10.2.0.$it")
        }
        assertEquals(listOf(120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L, 3_600_000L), waits)
        // The per-address gate is the listeners' own (five, then 30 s).
        val one = LoginGuard.forRelay(clock = { 0L })
        repeat(LoginGuard.THRESHOLD) { one.failed("10.3.3.3") }
        assertEquals(30_000L, one.waitMs("10.3.3.3"))
        assertEquals("under the global ten, nobody else waits", 0L, one.waitMs("10.4.4.4"))
        // M56: a right PIN no longer frees everyone; a day without a wrong try does (an hour is not enough here).
        now += guard.waitMs("10.9.9.9")
        guard.succeeded("10.9.9.9")
        assertEquals("the count stands", 3_600_000L, guard.failed("10.5.0.1"))
        assertEquals(24 * 60 * 60_000L, LoginGuard.RELAY_GLOBAL_QUIET_MS)
        now += LoginGuard.GLOBAL_QUIET_MS
        assertEquals("an hour on, the relay's count stands", 3_600_000L, guard.failed("10.5.0.2"))
        now += LoginGuard.RELAY_GLOBAL_QUIET_MS
        assertEquals("a quiet day on, it is forgotten", 0L, guard.failed("10.5.0.3"))
        assertEquals(0L, guard.waitMs("10.9.9.9"))
    }

    /** M56: a known device's sign-in and a session's confirm are weighed on their own key alone. */
    @Test
    fun `a try outside the shared gate neither waits for it nor counts toward it (M56)`() {
        val guard = LoginGuard.forRelay(clock = { 0L })
        repeat(LoginGuard.RELAY_GLOBAL_THRESHOLD) { guard.failed("198.51.100.$it") }
        assertTrue("the gate for everyone is shut", guard.waitMs("203.0.113.1") > 0)
        assertEquals("not for a key of its own", 0L, guard.waitMs("device:a", shared = false))
        assertEquals(LoginGuard.Attempt.Right, guard.attempt("device:a", shared = false) { true })
        assertTrue("shared, the same try waits", guard.attempt("203.0.113.1") { true } is LoginGuard.Attempt.Wait)
        // Wrong tries on its own key count there alone: its own lock at the fifth, the shared count untouched.
        val open = LoginGuard.forRelay(clock = { 0L })
        repeat(LoginGuard.THRESHOLD - 1) { assertEquals(LoginGuard.Attempt.Wrong(0), open.attempt("confirm:b", shared = false) { false }) }
        assertEquals(LoginGuard.Attempt.Wrong(LoginGuard.FIRST_LOCK_MS), open.attempt("confirm:b", shared = false) { false })
        repeat(LoginGuard.RELAY_GLOBAL_THRESHOLD - 1) { open.failed("198.51.100.$it") }
        assertEquals("nine shared wrong tries, and nobody waits: the five never counted", 0L, open.waitMs("203.0.113.1"))
    }

    @Test
    fun `tries sent at once are weighed one at a time, and only until the lock (audit W2)`() {
        val guard = LoginGuard(clock = { 0L })
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val weighed = AtomicInteger()
        val outcomes: MutableList<LoginGuard.Attempt> = Collections.synchronizedList(mutableListOf())
        val go = CountDownLatch(1)
        val tries = (1..12).map {
            thread {
                go.await()
                outcomes += guard.attempt("10.0.0.7") {
                    most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    weighed.incrementAndGet()
                    Thread.sleep(20)   // a derivation takes a while: the others arrive meanwhile
                    inside.decrementAndGet()
                    false
                }
            }
        }
        go.countDown()
        tries.forEach { it.join() }
        assertEquals("never two derivations at once", 1, most.get())
        assertEquals("exactly the five before the lock are weighed", LoginGuard.THRESHOLD, weighed.get())
        assertEquals(LoginGuard.THRESHOLD, outcomes.count { it is LoginGuard.Attempt.Wrong })
        assertEquals("the rest are refused uncounted", 12 - LoginGuard.THRESHOLD, outcomes.count { it is LoginGuard.Attempt.Wait })
        assertEquals("a locked key is refused before it waits on anyone's derivation", LoginGuard.Attempt.Wait(30_000L), guard.attempt("10.0.0.7") { error("not weighed") })
        assertEquals("another key is weighed meanwhile", LoginGuard.Attempt.Right, guard.attempt("10.0.0.8") { true })
    }

    @Test
    fun `the guard remembers a bounded number of addresses`() {
        val guard = LoginGuard(clock = { 0L }, maxKeys = 3, threshold = 100)
        (1..10).forEach { guard.failed("10.0.0.$it") }
        assertEquals(3, guard.keyCount())
        assertEquals(0L, guard.waitMs("10.0.0.1"))
    }

    private fun sha256(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
}
