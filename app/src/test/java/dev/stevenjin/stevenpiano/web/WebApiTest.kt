// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import dev.stevenjin.stevenpiano.studio.ComposeOrder
import dev.stevenjin.stevenpiano.studio.SeedChoice
import dev.stevenjin.stevenpiano.studio.compose.ComposeRequest
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.SeedFacts
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.ImportedPlaylist
import dev.stevenjin.stevenpiano.diag.BatteryReading
import dev.stevenjin.stevenpiano.diag.RunningNow
import dev.stevenjin.stevenpiano.diag.SystemReading
import dev.stevenjin.stevenpiano.diag.ThermalReading
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.MidiNames
import dev.stevenjin.stevenpiano.instruments.MidiTransport
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.player.DynamicRange
import dev.stevenjin.stevenpiano.player.ExpressionLevel
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.QueueSnapshot
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.settings.NoteDisplay
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
        assertEquals(SettingsChange(tabletVolume = 35), WebApi.settingsChange(JSONObject("""{"tabletVolume":35}""")))
        refused(400) { WebApi.settingsChange(JSONObject("""{"tabletVolume":101}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"tabletSound":"always"}""")) }   // the mode is the tablet's alone
        refused(400) { WebApi.settingsChange(JSONObject("""{"webHostName":"-piano"}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"webHostName":"piano:8737"}""")) }
        // The View control's four (v1.13 — M32): the roll's two styles only, never the phone's Score.
        assertEquals(
            SettingsChange(noteDisplay = NoteDisplay.FALLING, fingering = false, chordNames = true, handColours = true),
            WebApi.settingsChange(JSONObject("""{"noteDisplay":"falling","fingering":false,"chordNames":true,"handColours":true}""")),
        )
        assertEquals(SettingsChange(noteDisplay = NoteDisplay.PAPER_ROLL), WebApi.settingsChange(JSONObject("""{"noteDisplay":"paperRoll"}""")))
        refused(400) { WebApi.settingsChange(JSONObject("""{"noteDisplay":"score"}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"noteDisplay":"PAPER_ROLL"}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"handColours":1}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"notesSplitStacked":0.4}""")) }   // the split is the tablet's alone
        // How a piece is played (v1.16 — M44): the Playback page's four, with its choices.
        assertEquals(
            SettingsChange(dynamicRange = DynamicRange.WIDE, velocityFloor = 30, expression = ExpressionLevel.OFF, restrikeMs = 0),
            WebApi.settingsChange(JSONObject("""{"dynamicRange":"wide","velocityFloor":30,"expression":"off","restrikeMs":0}""")),
        )
        assertEquals(SettingsChange(restrikeMs = 250), WebApi.settingsChange(JSONObject("""{"restrikeMs":250}""")))
        refused(400) { WebApi.settingsChange(JSONObject("""{"dynamicRange":"WIDE"}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"expression":"humane"}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"velocityFloor":61}""")) }
        refused(400) { WebApi.settingsChange(JSONObject("""{"restrikeMs":50}""")) }   // between Auto and 60 there is none
        refused(400) { WebApi.settingsChange(JSONObject("""{"restrikeMs":65}""")) }   // in tens
        // Album colours behind the player (v1.18 — M47b: the Settings page's Panel).
        assertEquals(SettingsChange(albumBackdrop = false), WebApi.settingsChange(JSONObject("""{"albumBackdrop":false}""")))
        refused(400) { WebApi.settingsChange(JSONObject("""{"albumBackdrop":"off"}""")) }
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
                at = 98_765_432,
                fold = false,
                views = WebViews(rev = 41, notes = 3_216, hands = true, fingers = false, chords = true, score = true),
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
            display = WebDisplay(noteDisplay = "score", rollStyle = "paperRoll", fingering = true, chordNames = false, handColours = true),
        )
        val json = WebApi.state(state, pending = 2)
        val player = json.getJSONObject("player")
        assertEquals(setOf("status", "loading", "piece", "positionMs", "at", "tempoPct", "transpose", "velocityPct", "preRollMs", "channel", "tablet", "queue", "problem", "fold", "views"), player.keys().asSequence().toSet())
        // The views (v1.13 — M32): when the position was taken, folding, and what the views of the piece show.
        assertEquals(98_765_432L, player.getLong("at"))
        assertFalse(player.getBoolean("fold"))
        val views = player.getJSONObject("views")
        assertEquals(setOf("rev", "notes", "hands", "fingers", "chords", "score"), views.keys().asSequence().toSet())
        assertEquals(41, views.getInt("rev"))
        assertEquals(3_216, views.getInt("notes"))
        assertTrue(views.getBoolean("hands") && !views.getBoolean("fingers") && views.getBoolean("chords") && views.getBoolean("score"))
        assertTrue("nothing loaded: no views", WebApi.state(WebState(), 0).getJSONObject("player").isNull("views"))
        val display = json.getJSONObject("display")
        assertEquals(setOf("noteDisplay", "rollStyle", "fingering", "chordNames", "handColours"), display.keys().asSequence().toSet())
        assertEquals("score", display.getString("noteDisplay"))
        assertEquals("paperRoll", display.getString("rollStyle"))
        assertTrue(display.getBoolean("handColours"))
        // The tablet's piano sound (v1.8 — M25): its mode, volume, whether it sounds, whether its SoundFont is there.
        val tablet = player.getJSONObject("tablet")
        assertEquals(setOf("mode", "volume", "active", "installed"), tablet.keys().asSequence().toSet())
        assertEquals("whenNotConnected", tablet.getString("mode"))
        assertEquals(60, tablet.getInt("volume"))
        assertEquals("playing", player.getString("status"))
        assertEquals(setOf("id", "title", "composer", "composerShort", "composerKey", "durationMs", "favorite", "art", "artVersion"), player.getJSONObject("piece").keys().asSequence().toSet())
        assertEquals("Calm", player.getJSONObject("channel").getString("name"))
        val queue = player.getJSONObject("queue")
        assertEquals(setOf("ids", "uids", "index", "shuffle", "repeat", "items"), queue.keys().asSequence().toSet())
        assertEquals("all", queue.getString("repeat"))
        assertTrue(queue.getJSONArray("items").getJSONObject(1).getBoolean("requested"))
        assertEquals(setOf("player", "link", "instruments", "piano", "import", "artwork", "requests", "web", "monochrome", "albumBackdrop", "schedule", "studio", "display"), json.keys().asSequence().toSet())
        assertTrue("Album colours behind the player, on until turned off (v1.15 — M41)", json.getBoolean("albumBackdrop"))
        // Steven Piano Cloud (v1.10 — M26): the public link beside the tablet's own addresses, null while remote access is off.
        assertEquals(setOf("address", "guestAddress", "guests", "cloud"), json.getJSONObject("web").keys().asSequence().toSet())
        assertTrue(json.getJSONObject("web").isNull("cloud"))
        val clouded = WebApi.state(state.copy(web = WebAddresses(null, null, "https://relay.example.dev/p/abcdefgh2345/")), pending = 0)
        assertEquals("https://relay.example.dev/p/abcdefgh2345/", clouded.getJSONObject("web").getString("cloud"))
        // Studio (v1.7 — M23): whether it runs here, its models and its jobs, the progress null when not running.
        val studio = WebApi.studio(
            WebStudio(
                available = true,
                models = listOf(WebModel("transcription", "Transcription", 124_511_036, "CC BY 4.0", installed = false, line = "Downloading · 42 of 125 MB", progress = 0.34f)),
                jobs = listOf(WebStudioJob(3, "transcribe", "take.m4a", "done", "Kept as take", progress = null, title = "take")),
            ),
        )
        assertEquals(setOf("available", "reason", "models", "jobs"), studio.keys().asSequence().toSet())
        assertTrue(studio.isNull("reason"))
        val model = studio.getJSONArray("models").getJSONObject(0)
        assertEquals(setOf("name", "title", "sizeBytes", "licence", "installed", "line", "progress"), model.keys().asSequence().toSet())
        assertEquals(0.34, model.getDouble("progress"), 1e-6)
        val job = studio.getJSONArray("jobs").getJSONObject(0)
        assertEquals(
            setOf("id", "kind", "name", "state", "line", "progress", "title", "step", "steps", "tokens", "musicMs", "targetMs", "etaMs", "notes", "turn"),
            job.keys().asSequence().toSet(),
        )
        assertTrue(job.isNull("progress"))
        assertEquals("Kept as take", job.getString("line"))
        assertTrue("no schedule ahead: null, not missing", json.getJSONObject("schedule").isNull("next"))
        val scheduled = WebApi.state(state.copy(schedule = WebScheduleState("Next: Wednesday 12:30, Calm", 7)), pending = 0).getJSONObject("schedule")
        assertEquals("Next: Wednesday 12:30, Calm", scheduled.getString("next"))
        assertEquals(7, scheduled.getInt("revision"))
        assertEquals(2, json.getJSONObject("requests").getInt("pending"))
        assertEquals("http://100.101.2.3:8737", json.getJSONObject("web").getString("address"))
        assertTrue(json.getJSONObject("import").getBoolean("running"))
        // v1.10.1 — M28 (D7): the playlist the last finished import filled, null while one runs or when it filled none.
        assertEquals(setOf("running", "done", "total", "imported", "duplicates", "failed", "current", "playlist"), json.getJSONObject("import").keys().asSequence().toSet())
        assertTrue(json.getJSONObject("import").isNull("playlist"))
        val finished = WebApi.import(ImportProgress(done = 266, total = 266, imported = 265, duplicates = 1, playlist = ImportedPlaylist(14, "MIDI")))
        assertEquals(14, finished.getJSONObject("playlist").getLong("id"))
        assertEquals("MIDI", finished.getJSONObject("playlist").getString("name"))
        assertTrue("a run under way shows none yet", WebApi.import(ImportProgress(done = 3, total = 9, finished = false, playlist = ImportedPlaylist(14, "MIDI"))).isNull("playlist"))
        assertEquals("1.4.0", json.getJSONObject("piano").getJSONObject("facts").getString("fw"))
        assertEquals("state", WebApi.state(state, 0, type = "state").getString("type"))
        assertTrue("no piece: null, not missing", WebApi.state(WebState(), 0).getJSONObject("player").isNull("piece"))
        assertFalse(WebApi.progress(1, 2).has("player"))
    }

    @Test
    fun `a piece and a channel carry their genre only when they have one (v1_14 M37)`() {
        val modern = WebPiece(4, "Shape of You", "Ed Sheeran", "ed sheeran", 233_000, composerShort = "Ed Sheeran", genre = "modern")
        val json = WebApi.piece(modern)
        assertEquals(setOf("id", "title", "composer", "composerShort", "composerKey", "durationMs", "favorite", "art", "artVersion", "genre"), json.keys().asSequence().toSet())
        assertEquals("modern", json.getString("genre"))
        // Every kind carries its version (v1.17 — M45): a roll card's is 1, a portrait's or a cover's when it was kept.
        assertEquals("roll", json.getString("art"))
        assertEquals(1L, json.getLong("artVersion"))
        assertEquals(1_780_000_000_000L, WebApi.piece(modern.copy(portrait = true, artVersion = 1_780_000_000_000L)).getLong("artVersion"))
        assertEquals(42L, WebApi.composer(WebComposer("debussy", "Debussy", 3, portrait = true, artVersion = 42)).getLong("artVersion"))
        assertFalse("made on the tablet: no genre at all, not null", WebApi.piece(modern.copy(genre = null)).has("genre"))
        val channels = WebApi.channels(
            listOf(
                WebChannel("classical", "Classical", 1_726, playable = true, playing = false, volume = 70, composers = emptyList(), genre = "classical"),
                WebChannel("everything", "Everything", 1_992, playable = true, playing = false, volume = 70, composers = emptyList()),
            ),
        ).getJSONArray("channels")
        assertEquals("classical", channels.getJSONObject(0).getString("genre"))
        assertFalse("Everything is both: no genre", channels.getJSONObject(1).has("genre"))
    }

    @Test
    fun `a composition's choices are read strictly, and nothing but choices is taken`() {
        val full = WebApi.composeOrder(JSONObject("""{"pieceId":12,"mood":"wild","key":{"tonic":9,"minor":true},"bpm":132,"minutes":1}"""))
        assertEquals(ComposeOrder(12L, ComposeRequest(Mood.Wild, MusicKey(9, true), 132, 1)), full)
        val plain = WebApi.composeOrder(JSONObject("""{"mood":"calm","key":null,"bpm":null,"minutes":2}"""))
        assertEquals("no piece, key or tempo: the defaults", ComposeOrder(null, ComposeRequest(Mood.Calm, null, null, 2)), plain)
        for (mood in listOf("calm", "bright", "wild", "melancholy")) WebApi.composeOrder(JSONObject().put("mood", mood).put("minutes", 3))
        val refusedBodies = listOf(
            """{"mood":"calm","minutes":2,"prompt":"play like Chopin"}""", // no text for the model, ever
            """{"mood":"Calm ","minutes":2}""",
            """{"mood":"happy","minutes":2}""",
            """{"mood":"calm"}""",
            """{"mood":"calm","minutes":0}""",
            """{"mood":"calm","minutes":6}""",
            """{"mood":"calm","minutes":2,"bpm":39}""",
            """{"mood":"calm","minutes":2,"bpm":201}""",
            """{"mood":"calm","minutes":2,"bpm":120.5}""",
            """{"mood":"calm","minutes":2,"key":{"tonic":12,"minor":false}}""",
            """{"mood":"calm","minutes":2,"key":{"tonic":0}}""",
            """{"mood":"calm","minutes":2,"key":{"tonic":0,"minor":false,"name":"C"}}""",
            """{"mood":"calm","minutes":2,"key":"C major"}""",
            """{"mood":"calm","minutes":2,"pieceId":0}""",
            """{"mood":"calm","minutes":2,"pieceId":"12"}""",
            """{"mood":7,"minutes":2}""",
        )
        for (body in refusedBodies) refused(400) { WebApi.composeOrder(JSONObject(body)) }

        val seed = WebApi.seed(SeedChoice(12, "Clair de lune", null, SeedFacts(MusicKey(1, false), 66, 66.3)))
        assertEquals(12L, seed.getLong("pieceId"))
        assertEquals("Clair de lune", seed.getString("title"))
        assertTrue(seed.isNull("composer"))
        assertEquals(1, seed.getJSONObject("key").getInt("tonic"))
        assertFalse(seed.getJSONObject("key").getBoolean("minor"))
        assertEquals("D♭ major", seed.getJSONObject("key").getString("label"))
        assertEquals(66, seed.getInt("bpm"))
    }

    @Test
    fun `what plays and what is played from, read-only (v1_11 M29)`() {
        val keyboard = KeyboardState(KeyboardState.Chosen(MidiNames.bluetoothKey("11:22:33:44:55:66"), "KeyStep", MidiTransport.BLUETOOTH), KeyboardState.Phase.NeedsPairing)
        val state = WebInstruments.of(InstrumentKind.MidiPiano, "FP-30X", "connected", keyboard, live = false, recording = true)
        val json = WebApi.instruments(state)
        assertEquals(setOf("instrument", "keyboard", "live", "recording"), json.keys().asSequence().toSet())
        assertEquals(setOf("kind", "name", "state"), json.getJSONObject("instrument").keys().asSequence().toSet())
        assertEquals("midi", json.getJSONObject("instrument").getString("kind"))
        assertEquals("FP-30X", json.getJSONObject("instrument").getString("name"))
        assertEquals("connected", json.getJSONObject("instrument").getString("state"))
        assertEquals(setOf("name", "transport", "state"), json.getJSONObject("keyboard").keys().asSequence().toSet())
        assertEquals("KeyStep", json.getJSONObject("keyboard").getString("name"))
        assertEquals("bluetooth", json.getJSONObject("keyboard").getString("transport"))
        assertEquals("pairing", json.getJSONObject("keyboard").getString("state"))
        assertFalse(json.getBoolean("live"))
        assertTrue(json.getBoolean("recording"))
        val steven = WebApi.instruments(WebInstruments.of(InstrumentKind.StevenPiano, "FP-30X", "disconnected", KeyboardState(), live = false, recording = false))
        assertEquals("steven", steven.getJSONObject("instrument").getString("kind"))
        assertEquals("Steven Piano", steven.getJSONObject("instrument").getString("name"))
        assertTrue("no keyboard: null, not missing", steven.isNull("keyboard"))
        assertEquals(
            listOf("connected", "connecting", "disconnected", "disconnected", "pairing", "unavailable"),
            listOf(KeyboardState.Phase.Connected, KeyboardState.Phase.Connecting, KeyboardState.Phase.NotConnected, KeyboardState.Phase.None, KeyboardState.Phase.NeedsPairing, KeyboardState.Phase.Unavailable)
                .map(WebInstruments::keyboardState),
        )
        assertEquals("in the state beside the link", "midi", WebApi.state(WebState(instruments = state), 0).getJSONObject("instruments").getJSONObject("instrument").getString("kind"))
    }

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    @Test
    fun `the System page's object has every key, null where nothing is known, and the piano's diag only from its facts (v1_18 M46)`() {
        val bare = WebApi.system(WebSystem(at = 5))
        assertEquals(setOf("at", "app", "tablet", "piano", "running", "web", "covers"), keys(bare))
        val app = bare.getJSONObject("app")
        assertEquals(setOf("version", "build", "pid", "threads", "uptimeMs", "heapUsed", "heapMax", "nativeHeap", "cpuPct", "deviceOwner", "kiosk"), keys(app))
        val tablet = bare.getJSONObject("tablet")
        assertEquals(setOf("model", "android", "uptimeMs", "screenOn", "battery", "thermal", "memory", "storage", "cpu", "network"), keys(tablet))
        val parts = mapOf(
            "battery" to setOf("percent", "charging", "plug", "tempC", "voltageMv", "health"),
            "thermal" to setOf("status", "headroom", "cpuC", "skinC"),
            "memory" to setOf("total", "available", "low"),
            "storage" to setOf("total", "free"),
            "cpu" to setOf("cores", "loadPct"),
            "network" to setOf("online", "transport", "signalDbm", "downKbps"),
        )
        val unknowns = mutableListOf(app)
        for ((name, expected) in parts) unknowns += tablet.getJSONObject(name).also { assertEquals(name, expected, keys(it)) }
        val piano = bare.getJSONObject("piano")
        assertEquals(setOf("link", "state", "facts", "factsAt", "diag"), keys(piano))
        assertEquals(setOf("state", "name", "mtu"), keys(piano.getJSONObject("link")))
        val web = bare.getJSONObject("web")
        assertEquals(setOf("sessions", "sockets", "guests", "relay"), keys(web))
        assertEquals(setOf("state", "answered", "refused"), keys(web.getJSONObject("relay")))
        val covers = bare.getJSONObject("covers")
        assertEquals(setOf("found", "missing", "failed", "waiting", "blockedUntil"), keys(covers))
        unknowns += listOf(covers, piano.getJSONObject("diag"))
        for (part in unknowns) for (key in keys(part)) assertTrue("$key: unknown is null, never absent", part.isNull(key))
        for (key in listOf("model", "android", "uptimeMs", "screenOn")) assertTrue(tablet.isNull(key))
        assertTrue(piano.isNull("facts") && piano.isNull("factsAt") && piano.getJSONObject("link").isNull("mtu"))
        assertEquals("no facts: every diag name there, each null", PianoSettings.diagFacts.toSet(), keys(piano.getJSONObject("diag")))
        assertEquals("off", web.getJSONObject("relay").getString("state"))

        // What a 2.0.0 piano lists, a fact from later firmware, one that doesn't read, and one the app doesn't know.
        val facts = mapOf(
            "proto" to "1", "fw" to "2.0.0+a1b2c3d", "ota" to "confirmed", "boards" to "OK,OK,MISSING,OK,OK,OK,OK", "i2cfails" to "3",
            "pedalboard" to "online", "uptime" to "7322", "repeatms" to "70", "temp" to "41.5", "heap" to "182344", "heapmin" to "lots",
            "loopms" to "1.25", "rssi" to "-61", "reset" to "brownout", "mystery" to "42",
        )
        val full = WebApi.system(
            WebSystem(
                at = 7,
                reading = SystemReading(
                    model = "Google Pixel Tablet",
                    battery = BatteryReading(percent = 81, charging = true, plug = "usb", tempC = 31.2, voltageMv = 4_210, health = "good"),
                    thermal = ThermalReading(status = 2, headroom = 0.45),
                ),
                piano = WebSystemPiano(WebLink("connected", "Steven Piano"), mtu = 247, state = "ready", facts = facts, factsAt = 1_790_000_000_000L),
                running = RunningNow.of(RunningNow.Inputs()),
                web = WebSystemWeb(sessions = 2, sockets = 1, guests = true, relay = WebRelay("connected", 12, 0)),
                covers = WebCovers(found = 10, missing = 3, failed = 1, waiting = 40),
            ),
        )
        val shown = full.getJSONObject("tablet")
        assertEquals(31.2, shown.getJSONObject("battery").getDouble("tempC"), 1e-9)
        assertEquals("usb", shown.getJSONObject("battery").getString("plug"))
        assertEquals("the thermal status by name", "moderate", shown.getJSONObject("thermal").getString("status"))
        assertTrue(shown.getJSONObject("thermal").isNull("cpuC"))
        val fullPiano = full.getJSONObject("piano")
        assertEquals(247, fullPiano.getJSONObject("link").getInt("mtu"))
        assertEquals("every fact as it stands, known or not", "42", fullPiano.getJSONObject("facts").getString("mystery"))
        val diag = fullPiano.getJSONObject("diag")
        assertEquals(PianoSettings.diagFacts.toSet(), keys(diag))
        assertEquals(41.5, diag.getDouble("temp"), 1e-9)
        assertEquals(1.25, diag.getDouble("loopms"), 1e-9)
        assertEquals(182_344L, diag.getLong("heap"))
        assertEquals(-61, diag.getInt("rssi"))
        assertEquals(7_322L, diag.getLong("uptime"))
        assertEquals("brownout", diag.getString("reset"))
        assertEquals("2.0.0+a1b2c3d", diag.getString("fw"))
        assertEquals("online", diag.getString("pedalboard"))
        val boards = diag.getJSONArray("boards")
        assertEquals(listOf("ok", "ok", "missing", "ok", "ok", "ok", "ok"), (0 until boards.length()).map { boards.getString(it) })
        assertTrue("a count that isn't one reads as none", diag.isNull("heapmin"))
        assertTrue("2.0.0 has none of these", listOf("heapblock", "tasks", "stack", "looprate", "active", "trips", "crashes").all { diag.isNull(it) })
        assertFalse("a fact outside the list stays in facts only", diag.has("mystery") || diag.has("proto"))
        val running = full.getJSONArray("running")
        assertEquals(RunningNow.KEYS, (0 until running.length()).map { running.getJSONObject(it).getString("key") })
        assertEquals(setOf("key", "title", "state", "detail", "progress"), keys(running.getJSONObject(0)))
        assertTrue("nothing playing: no progress", running.getJSONObject(0).isNull("progress"))
        assertEquals(12, full.getJSONObject("web").getJSONObject("relay").getInt("answered"))
        assertEquals(40, full.getJSONObject("covers").getInt("waiting"))
        assertTrue(full.getJSONObject("covers").isNull("blockedUntil"))
    }

    @Test
    fun `the Settings page's read has every key, the PUT's own words, its limits, and the piano's two null without a piano (v1_18 M47b)`() {
        val bare = WebApi.settings(WebSettings())
        assertEquals(setOf("values", "limits", "piano"), keys(bare))
        val values = bare.getJSONObject("values")
        assertEquals(
            setOf("defaultTempoPct", "transpose", "velocityPct", "dynamicRange", "velocityFloor", "expression", "restrikeMs", "preRollMs", "foldOutOfRange", "skipDrumChannel", "albumBackdrop"),
            keys(values),
        )
        assertEquals("natural", values.getString("dynamicRange"))
        assertEquals("light", values.getString("expression"))
        assertEquals("Auto at first", 0, values.getInt("restrikeMs"))
        assertTrue(values.getBoolean("albumBackdrop"))
        // What the page reads it may send back as it is.
        for (key in keys(values)) WebApi.settingsChange(JSONObject().put(key, values.get(key)))
        val limits = bare.getJSONObject("limits")
        assertEquals(setOf("defaultTempoPct", "transpose", "velocityPct", "velocityFloor", "restrikeMs", "preRollMs"), keys(limits))
        for (key in keys(limits)) assertEquals(key, setOf("min", "max", "step"), keys(limits.getJSONObject(key)))
        fun limit(key: String) = limits.getJSONObject(key).let { Triple(it.getInt("min"), it.getInt("max"), it.getInt("step")) }
        assertEquals(Triple(PlaybackLimits.TempoPct.first, PlaybackLimits.TempoPct.last, 5), limit("defaultTempoPct"))
        assertEquals(Triple(-12, 12, 1), limit("transpose"))
        assertEquals(Triple(1, 60, 5), limit("velocityFloor"))
        assertEquals("the times set by hand; 0 is Auto", Triple(60, 250, PlaybackLimits.RESTRIKE_STEP_MS), limit("restrikeMs"))
        assertEquals(Triple(0, 5_000, 500), limit("preRollMs"))
        val piano = bare.getJSONObject("piano")
        assertEquals(setOf("fullPower", "repeatMs"), keys(piano))
        assertTrue("no piano: both unknown, never absent", piano.isNull("fullPower") && piano.isNull("repeatMs"))

        val set = WebApi.settings(
            WebSettings(
                dev.stevenjin.stevenpiano.settings.PianoSettings(
                    dynamicRange = DynamicRange.WIDE, expression = ExpressionLevel.FULL, restrikeMs = 150, albumBackdrop = false, transpose = -3,
                ),
                fullPower = false,
                repeatMs = 110,
            ),
        )
        val shown = set.getJSONObject("values")
        assertEquals("wide", shown.getString("dynamicRange"))
        assertEquals("full", shown.getString("expression"))
        assertEquals(150, shown.getInt("restrikeMs"))
        assertEquals(-3, shown.getInt("transpose"))
        assertFalse(shown.getBoolean("albumBackdrop"))
        assertFalse(set.getJSONObject("piano").getBoolean("fullPower"))
        assertEquals(110, set.getJSONObject("piano").getInt("repeatMs"))
    }
}
