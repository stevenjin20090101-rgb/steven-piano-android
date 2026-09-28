// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.QueueSnapshot
import dev.stevenjin.stevenpiano.player.RepeatMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The panel's JSON: reading it strictly, and the shapes it sends (the audit's point 5). */
class WebApiTest {
    private fun refused(status: Int, block: () -> Unit) {
        val e = assertThrows(ApiError::class.java) { block() }
        assertEquals(e.message, status, e.status)
    }

    @Test
    fun `depth counts brackets outside strings only`() {
        assertEquals(0, WebApi.depthOf("1"))
        assertEquals(1, WebApi.depthOf("""{"a":1}"""))
        assertEquals(2, WebApi.depthOf("""{"a":[1,2]}"""))
        assertEquals(1, WebApi.depthOf("""{"a":"[[[[[{{{{"}"""))
        assertEquals(1, WebApi.depthOf("""{"a":"\"[[[["}"""))
        assertEquals(5, WebApi.depthOf("""{"a":[[[[1]]]]}"""))
    }

    @Test
    fun `a body is read only with its length, under 64 KB, as JSON`() {
        fun read(text: String, length: Long? = text.length.toLong(), type: String? = "application/json", chunked: Boolean = false) =
            WebApi.readObject(ByteArrayInputStream(text.toByteArray()), length, type, chunked)
        assertEquals(1, read("""{"a":1}""").getInt("a"))
        assertEquals(1, read("""{"a":1}""", type = "application/json; charset=UTF-8").getInt("a"))
        refused(411) { read("""{"a":1}""", length = null) }
        refused(411) { read("""{"a":1}""", chunked = true) }
        refused(413) { read("""{"a":1}""", length = WebApi.MAX_BODY + 1L) }
        refused(415) { read("""{"a":1}""", type = "text/plain") }
        refused(415) { read("""{"a":1}""", type = null) }
        refused(400) { read("""{"a":1}""", length = 20) }
        refused(400) { read("""{"a":[[[[1]]]]}""") }
        refused(400) { read("""[1,2]""") }
        refused(400) { read("") }
    }

    @Test
    fun `ids are whole numbers above zero that fit a Long, and nothing else`() {
        val json = JSONObject("""{"a":7,"b":1.5,"c":"7","d":9223372036854775807,"e":9223372036854775808,"f":-1,"g":true,"h":null,"i":[1,2,3],"j":[1,"2"],"k":[1,0]}""")
        assertEquals(7L, WebApi.id(json, "a"))
        assertEquals(Long.MAX_VALUE, WebApi.id(json, "d"))
        for (name in listOf("b", "c", "e", "f", "g", "h", "missing")) refused(400) { WebApi.id(json, name) }
        assertEquals(listOf(1L, 2L, 3L), WebApi.idsOrNull(json, "i"))
        refused(400) { WebApi.idsOrNull(json, "j") }
        refused(400) { WebApi.idsOrNull(json, "k") }
        refused(413) { WebApi.idsOrNull(json, "i", max = 2) }
        assertEquals(null, WebApi.idsOrNull(json, "missing"))
    }

    @Test
    fun `strings are cut as the library cuts them, and flags are booleans`() {
        val json = JSONObject().put("q", "x".repeat(500)).put("on", true).put("off", "false")
        assertEquals(200, WebApi.string(json, "q", 200).length)
        assertTrue(WebApi.bool(json, "on"))
        refused(400) { WebApi.bool(json, "off") }
        refused(400) { WebApi.onlyKeys(json, setOf("q", "on")) }
        WebApi.onlyKeys(json, setOf("q", "on", "off"))
    }

    @Test
    fun `queue commands and settings changes are read as the app's own`() {
        assertEquals(QueueCommand.PlayNext(listOf(3, 4)), WebApi.queueCommand(JSONObject("""{"action":"playNext","ids":[3,4]}""")))
        assertEquals(QueueCommand.Move(8, 2), WebApi.queueCommand(JSONObject("""{"action":"move","uid":8,"toIndex":2}""")))
        assertEquals(QueueCommand.Clear, WebApi.queueCommand(JSONObject("""{"action":"clear"}""")))
        refused(400) { WebApi.queueCommand(JSONObject("""{"action":"add","ids":[]}""")) }
        refused(400) { WebApi.queueCommand(JSONObject("""{"action":"shred"}""")) }
        assertEquals(SettingsChange(defaultTempoPct = 90, webApproveFirst = false), WebApi.settingsChange(JSONObject("""{"defaultTempoPct":90,"webApproveFirst":false}""")))
        assertEquals(SettingsChange(webHostName = ""), WebApi.settingsChange(JSONObject("""{"webHostName":""}""")))
        refused(400) { WebApi.settingsChange(JSONObject("""{"transpose":13}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"webHostName":"-piano"}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"webHostName":"piano:8737"}""")) }
        assertEquals(RepeatMode.ALL, WebApi.repeatOf("all"))
        refused(400) { WebApi.repeatOf("ALL") }
    }

    @Test
    fun `the piano's values go out in the wire form the app's rows send`() {
        fun wire(name: String, value: Any?) = WebApi.pianoWire(PianoSettings.named(name)!!, value)
        assertEquals("1", wire("fullpower", true))
        assertEquals("0", wire("fullpower", 0))
        assertEquals("63", wire("volume", 63))
        assertEquals("63", wire("volume", 62.6))
        assertEquals("2.00", wire("velcurve", 2))
        assertEquals("110", wire("burstgap", 110))
        assertEquals("-12", wire("ledoffset", -12))
        refused(400) { wire("fullpower", "on") }
        refused(400) { wire("humanvel", 31) }
        refused(400) { wire("humanvel", 2.5) }
        refused(400) { wire("hold", 49) }
        refused(403) { wire("keyforce_black", 1) }
    }

    @Test
    fun `the state carries what the plan names, with Up next's rows and the requests waiting`() {
        val piece = WebPiece(1, "Clair de lune", "Claude Debussy", "debussy", 300_000, composerShort = "Debussy", portrait = true)
        val state = WebState(
            player = WebPlayer(
                status = PlaybackStatus.Playing,
                piece = piece,
                positionMs = 12_345,
                tempoPct = 90,
                preRollMs = 2_000,
                channel = WebChannelPlaying("calm", "Calm", 70),
                queue = QueueSnapshot(listOf(1, 2), listOf(5, 6), 0, shuffle = false, repeat = RepeatMode.ALL),
                items = listOf(WebQueueItem(5, piece), WebQueueItem(6, piece.copy(id = 2), requested = true)),
            ),
            link = WebLink("connected", "Steven Piano"),
            piano = WebPianoState("ready", mapOf("volume" to "70"), mapOf("fw" to "1.4.0")),
            import = ImportProgress(done = 3, total = 10, imported = 2, finished = false),
            web = WebAddresses("http://100.101.2.3:8737", "http://192.168.1.20:8737/request"),
            guests = GuestSettings(open = true, approveFirst = true),
        )
        val json = WebApi.state(state, pending = 2)
        val player = json.getJSONObject("player")
        assertEquals(setOf("status", "loading", "piece", "positionMs", "tempoPct", "transpose", "velocityPct", "preRollMs", "channel", "queue", "problem"), player.keys().asSequence().toSet())
        assertEquals("playing", player.getString("status"))
        assertEquals(setOf("id", "title", "composer", "composerShort", "composerKey", "durationMs", "favorite", "art"), player.getJSONObject("piece").keys().asSequence().toSet())
        assertEquals("Calm", player.getJSONObject("channel").getString("name"))
        val queue = player.getJSONObject("queue")
        assertEquals(setOf("ids", "uids", "index", "shuffle", "repeat", "items"), queue.keys().asSequence().toSet())
        assertEquals("all", queue.getString("repeat"))
        assertTrue(queue.getJSONArray("items").getJSONObject(1).getBoolean("requested"))
        assertEquals(setOf("player", "link", "piano", "import", "artwork", "requests", "web", "monochrome", "schedule"), json.keys().asSequence().toSet())
        assertTrue("no schedule ahead: null, not missing", json.getJSONObject("schedule").isNull("next"))
        val scheduled = WebApi.state(state.copy(schedule = WebScheduleState("Next: Wednesday 12:30, Calm", 7)), pending = 0).getJSONObject("schedule")
        assertEquals("Next: Wednesday 12:30, Calm", scheduled.getString("next"))
        assertEquals(7, scheduled.getInt("revision"))
        assertEquals(2, json.getJSONObject("requests").getInt("pending"))
        assertEquals("http://100.101.2.3:8737", json.getJSONObject("web").getString("address"))
        assertTrue(json.getJSONObject("import").getBoolean("running"))
        assertEquals("1.4.0", json.getJSONObject("piano").getJSONObject("facts").getString("fw"))
        assertEquals("state", WebApi.state(state, 0, type = "state").getString("type"))
        assertTrue("no piece: null, not missing", WebApi.state(WebState(), 0).getJSONObject("player").isNull("piece"))
        assertFalse(WebApi.progress(1, 2).has("player"))
    }
}
