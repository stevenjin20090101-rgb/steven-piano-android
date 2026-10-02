// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.web.GuestSettings
import dev.stevenjin.stevenpiano.web.WebChannel
import dev.stevenjin.stevenpiano.web.WebChannelPlaying
import dev.stevenjin.stevenpiano.web.WebInstrument
import dev.stevenjin.stevenpiano.web.WebInstruments
import dev.stevenjin.stevenpiano.web.WebKeyboard
import dev.stevenjin.stevenpiano.web.WebLink
import dev.stevenjin.stevenpiano.web.WebPianoState
import dev.stevenjin.stevenpiano.web.WebPiece
import dev.stevenjin.stevenpiano.web.WebPlayer
import dev.stevenjin.stevenpiano.web.WebState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tablet's status for the relay: the protocol's fields, as the relay's `sanitizeStatus` reads them, and nothing that names the device. */
class RelayStatusTest {
    @Test
    fun `the status carries the versions, the link, the player, the guests, the panel, the library and the channels`() {
        val state = WebState(
            player = WebPlayer(
                status = PlaybackStatus.Playing,
                piece = WebPiece(1, "Clair de lune", "Claude Debussy", "debussy", 300_000),
                positionMs = 61_000,
                channel = WebChannelPlaying("calm", "Calm", 70),
            ),
            link = WebLink("connected", "Steven Piano"),
            piano = WebPianoState("ready", mapOf("volume" to "70"), mapOf("fw" to " 2.0.0 ", "mac" to "C8:2E:18:00:11:22")),
            guests = GuestSettings(open = true, approveFirst = false),
        )
        val channels = listOf(WebChannel("calm", "Calm", 30, playable = true, playing = true, volume = 70, composers = emptyList()))
        val status = RelayStatus.report("1.10", 18, state, webEnabled = true, libraryPieces = 1_727, pack = null, channels = channels, at = 5)
        assertEquals(setOf("app", "firmware", "link", "instruments", "player", "guests", "panel", "library", "channels", "at"), status.keys().asSequence().toSet())
        assertEquals("1.10", status.getJSONObject("app").getString("version"))
        assertEquals(18, status.getJSONObject("app").getInt("code"))
        assertEquals("2.0.0", status.getString("firmware"))
        assertEquals("connected", status.getString("link"))
        val player = status.getJSONObject("player")
        assertEquals("playing", player.getString("status"))
        assertEquals("Clair de lune", player.getString("title"))
        assertEquals("Claude Debussy", player.getString("composer"))
        assertEquals(61_000L, player.getLong("positionMs"))
        assertEquals(300_000L, player.getLong("durationMs"))
        assertEquals("calm", player.getJSONObject("channel").getString("key"))
        assertTrue(status.getJSONObject("guests").getBoolean("open"))
        assertFalse(status.getJSONObject("guests").getBoolean("approveFirst"))
        assertTrue(status.getJSONObject("panel").getBoolean("web"))
        assertEquals("never the tablet's address on its own networks (audit delta 3)", setOf("web"), status.getJSONObject("panel").keys().asSequence().toSet())
        assertEquals(1_727, status.getJSONObject("library").getInt("pieces"))
        assertTrue("no pack until M27 brings one", status.getJSONObject("library").isNull("pack"))
        assertEquals("Calm", status.getJSONArray("channels").getJSONObject(0).getString("name"))
        val text = status.toString()
        for (identifier in listOf("C8:2E:18", "Steven Piano", "mac")) assertFalse("$identifier never goes to the relay", identifier in text)
        assertTrue(RelayProtocol.utf8Length(RelayMessage.Status(status).encode()) < RelayProtocol.MAX_TEXT)
    }

    @Test
    fun `what plays and what is played from goes without a name (v1_11 M29)`() {
        val state = WebState(
            link = WebLink("connected", "Roland FP-30X"),
            instruments = WebInstruments(
                instrument = WebInstrument("midi", "Roland FP-30X", "connected"),
                keyboard = WebKeyboard("Kim's KeyStep", "bluetooth", "connected"),
                live = true,
                recording = true,
            ),
        )
        val status = RelayStatus.report("1.11", 19, state, webEnabled = false, libraryPieces = 3, pack = 0, channels = emptyList(), at = 5)
        val instruments = status.getJSONObject("instruments")
        assertEquals(setOf("instrument", "keyboard", "live", "recording"), instruments.keys().asSequence().toSet())
        assertEquals(setOf("kind", "state"), instruments.getJSONObject("instrument").keys().asSequence().toSet())
        assertEquals("midi", instruments.getJSONObject("instrument").getString("kind"))
        assertEquals(setOf("transport", "state"), instruments.getJSONObject("keyboard").keys().asSequence().toSet())
        assertEquals("bluetooth", instruments.getJSONObject("keyboard").getString("transport"))
        assertTrue(instruments.getBoolean("live") && instruments.getBoolean("recording"))
        for (name in listOf("Roland", "FP-30X", "Kim")) assertFalse("$name never goes to the relay", name in status.toString())
        val none = RelayStatus.report("1.11", 19, WebState(), webEnabled = false, libraryPieces = 3, pack = 0, channels = emptyList(), at = 5).getJSONObject("instruments")
        assertEquals("steven", none.getJSONObject("instrument").getString("kind"))
        assertTrue("no keyboard: null, not missing", none.isNull("keyboard"))
        assertFalse(none.getBoolean("live") || none.getBoolean("recording"))
    }

    @Test
    fun `the web service's report reads the panel's switch and the library pack loaded from the settings`() {
        val state = WebState(piano = WebPianoState("unknown"))
        fun sent(settings: PianoSettings) = RelayStatus.report("1.10", 18, state, settings, libraryPieces = 1_726, channels = emptyList(), at = 5)
        val loaded = sent(PianoSettings(webEnabled = true, libraryPackVersion = 1))
        assertEquals("Steven's library, version 1 (v1.10 — M27)", 1, loaded.getJSONObject("library").getInt("pack"))
        assertTrue(loaded.getJSONObject("panel").getBoolean("web"))
        val fresh = sent(PianoSettings())
        assertEquals("none loaded yet: 0, which the console leaves out", 0, fresh.getJSONObject("library").getInt("pack"))
        assertFalse(fresh.getJSONObject("panel").getBoolean("web"))
        assertEquals(1_726, fresh.getJSONObject("library").getInt("pieces"))
    }

    @Test
    fun `an idle tablet reports no piece, and long texts and many channels are cut`() {
        val many = (1..40).map { WebChannel("c$it", "C".repeat(300), 3, playable = true, playing = false, volume = 70, composers = emptyList()) }
        val idle = RelayStatus.report("1.10", 18, WebState(piano = WebPianoState("unknown")), webEnabled = false, libraryPieces = null, pack = 2, channels = many, at = 5)
        val player = idle.getJSONObject("player")
        assertEquals("stopped", player.getString("status"))
        assertTrue(player.isNull("title") && player.isNull("composer") && player.isNull("channel"))
        assertEquals(0L, player.getLong("positionMs"))
        assertTrue(idle.isNull("firmware"))
        assertEquals("disconnected", idle.getString("link"))
        assertFalse(idle.getJSONObject("panel").has("host"))
        assertEquals(2, idle.getJSONObject("library").getInt("pack"))
        assertEquals(RelayStatus.MAX_CHANNELS, idle.getJSONArray("channels").length())
        assertEquals(RelayStatus.MAX_TEXT, idle.getJSONArray("channels").getJSONObject(0).getString("name").length)
    }
}
