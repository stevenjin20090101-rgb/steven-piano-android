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
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.SeedFacts
import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.LibraryScope
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.schedule.QuietNow
import dev.stevenjin.stevenpiano.schedule.QuietSection
import java.io.File
import java.util.Collections

/**
 * The web panel's backend in the tests: a small library held in memory, and every command it is
 * given written to [calls] ("play 3 queue=[3, 4]", "transport next"…), so a test can see exactly
 * what a request reached, and that a refused one reached nothing.
 */
class FakeWebBackend(override val uploadDir: File) : WebBackend {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    var state = WebState()
    val pieces = mutableListOf(
        WebPiece(1, "Clair de lune", "Claude Debussy", "debussy", 300_000, composerShort = "Debussy", portrait = true, genre = "classical"),
        WebPiece(2, "Nocturne in E-flat", "Frédéric Chopin", "chopin", 271_000, composerShort = "Chopin", genre = "classical"),
        WebPiece(3, "Für Elise", "Ludwig van Beethoven", "beethoven", 180_000, composerShort = "Beethoven", favorite = true, genre = "classical"),
    )
    val playlistsHeld = mutableListOf(WebPlaylist(10, "Evening", 2, 571_000, builtIn = false))
    val playlistPieces = mutableMapOf(10L to listOf(1L, 2L))
    val channelsHeld = mutableListOf(
        WebChannel("calm", "Calm", 30, playable = true, playing = false, volume = 70, composers = listOf(WebCardComposer("debussy", "Debussy", true))),
        WebChannel("tiny", "Tiny", 2, playable = false, playing = false, volume = 70, composers = emptyList()),
    )
    var piano = WebPiano(WebPianoState("ready", mapOf("volume" to "70", "leds" to "1")), statusText = null, statusReading = false)
    var guests = GuestSettings(open = true, approveFirst = false)
    /** The built-in lists guests see; the Modern list is every Modern piece in [pieces], as the app's (v1.14 — M37). */
    var catalogueHeld = listOf(CatalogueList("popular", "Popular", pieces.take(2), "classical"))
    var pin: PinHash? = null
    val imported = Collections.synchronizedList(mutableListOf<String>())
    private var nextUid = 100L

    private fun record(line: String) {
        calls += line
    }

    override suspend fun state(): WebState = state

    /** [pieces] within [scope]: every one under All, else those of its genre. */
    private fun scoped(scope: LibraryScope): List<WebPiece> = scope.genre?.let { g -> pieces.filter { it.genre == Genres.name(g) } } ?: pieces

    override suspend fun library(query: String?, category: LibraryCategory, offset: Int, limit: Int, scope: LibraryScope): WebPage {
        val held = scoped(scope)
        val all = when {
            !query.isNullOrBlank() -> held.filter { query.lowercase() in it.title.lowercase() || query.lowercase() in it.composer.lowercase() }
            category == LibraryCategory.FAVORITES -> held.filter { it.favorite }
            else -> held
        }
        val from = offset.coerceIn(0, all.size)
        return WebPage(all.size, from, all.drop(from).take(limit))
    }

    override suspend fun playlists(scope: LibraryScope): List<WebPlaylist> = playlistsHeld

    override suspend fun playlist(id: Long): WebPlaylistDetail? {
        val playlist = playlistsHeld.firstOrNull { it.id == id } ?: return null
        return WebPlaylistDetail(playlist, playlistPieces[id].orEmpty().mapNotNull { pid -> pieces.firstOrNull { it.id == pid } })
    }

    override suspend fun composers(scope: LibraryScope): List<WebComposer> =
        scoped(scope).groupBy { it.composerKey }.map { (key, list) -> WebComposer(key, list.first().composer, list.size, list.first().portrait) }

    override suspend fun composer(key: String, scope: LibraryScope): WebComposerDetail? {
        val list = scoped(scope).filter { it.composerKey == key }
        if (list.isEmpty()) return null
        return WebComposerDetail(WebComposer(key, list.first().composer, list.size, list.first().portrait), list)
    }

    override suspend fun composerArt(key: String, size: WebArtSize): WebImage? =
        if (key == "debussy") WebImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2), "image/jpeg") else null

    /** A piece's own cover (a JPEG) when it has one, its roll card (a PNG) always; no [kind]: the cover, else the card. */
    override suspend fun pieceArt(id: Long, kind: WebArtKind?, size: WebArtSize): WebImage? {
        val piece = pieces.firstOrNull { it.id == id } ?: return null
        val cover = if (piece.cover) WebImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 3, 4), "image/jpeg") else null
        val roll = WebImage(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), "image/png")
        return when (kind) {
            WebArtKind.COVER -> cover
            WebArtKind.ROLL -> roll
            null -> cover ?: roll
        }
    }

    override suspend fun play(pieceId: Long, queue: List<Long>?): Boolean {
        if (pieces.none { it.id == pieceId }) return false
        record("play $pieceId queue=$queue")
        return true
    }

    override suspend fun playAll(ids: List<Long>, shuffle: Boolean): Boolean {
        val kept = ids.filter { id -> pieces.any { it.id == id } }
        if (kept.isEmpty()) return false
        record("play-all $kept shuffle=$shuffle")
        return true
    }

    override suspend fun playPlaylist(id: Long, shuffle: Boolean): Boolean {
        if (playlistPieces[id].isNullOrEmpty()) return false
        record("play-playlist $id shuffle=$shuffle")
        return true
    }

    override suspend fun transport(action: Transport) = record("transport ${action.key}")

    override suspend fun seek(ms: Long) = record("seek $ms")

    override suspend fun setTempo(pct: Int) = record("tempo $pct")

    override suspend fun setShuffle(on: Boolean) = record("shuffle $on")

    override suspend fun setRepeat(mode: RepeatMode) = record("repeat ${mode.name.lowercase()}")

    override suspend fun queue(command: QueueCommand): Boolean {
        record("queue $command")
        return when (command) {
            is QueueCommand.Remove -> command.uid in state.player.queue.uids
            is QueueCommand.Move -> command.uid in state.player.queue.uids
            is QueueCommand.Skip -> command.uid in state.player.queue.uids
            else -> true
        }
    }

    override suspend fun channels(): List<WebChannel> = channelsHeld

    override suspend fun playChannel(key: String): ChannelStart {
        val channel = channelsHeld.firstOrNull { it.key == key } ?: return ChannelStart.UNKNOWN
        if (!channel.playable) return ChannelStart.TOO_SMALL
        record("channel play $key")
        return ChannelStart.STARTED
    }

    override suspend fun stopChannel() = record("channel stop")

    override suspend fun setChannelVolume(key: String, pct: Int): Boolean {
        if (channelsHeld.none { it.key == key }) return false
        record("channel volume $key $pct")
        return true
    }

    override suspend fun piano(): WebPiano = piano

    override suspend fun setPiano(name: String, wire: String) = record("piano set $name $wire")

    override suspend fun pianoPreset(command: String) = record("piano preset $command")

    override suspend fun pianoAction(action: PianoAction) = record("piano action ${action.command}")

    override suspend fun applySettings(change: SettingsChange) = record("settings $change")

    override suspend fun importMidi(name: String, bytes: ByteArray) {
        imported += "midi $name ${bytes.size}"
        record("import midi $name ${bytes.size}")
    }

    override suspend fun importZip(name: String, file: File) {
        imported += "zip $name ${file.length()}"
        record("import zip $name ${file.length()}")
        file.delete()
    }

    override suspend fun catalogue(): List<CatalogueList> {
        val modern = pieces.filter { it.genre == "modern" }
        return if (modern.isEmpty()) catalogueHeld else catalogueHeld + CatalogueList("modern", "Modern", modern, "modern")
    }

    override suspend fun offered(pieceId: Long): WebPiece? = catalogue().asSequence().flatMap { it.pieces }.firstOrNull { it.id == pieceId }

    override suspend fun guestSettings(): GuestSettings = guests

    override suspend fun queueRequested(pieceId: Long): List<Long> {
        record("requested $pieceId")
        return listOf(nextUid++)
    }

    override suspend fun pinHash(): PinHash? = pin

    /** Studio (v1.7 — M23): whether it runs here; the recordings it was sent (name, size, their bytes); the jobs that may be cancelled. */
    var studioHeld = WebStudio(available = true)
    val recordings: MutableList<Triple<String, Long, ByteArray>> = Collections.synchronizedList(mutableListOf())
    val studioJobs = mutableSetOf(7L)
    private var nextJob = 40L

    override suspend fun studio(): WebStudio = studioHeld

    override suspend fun transcribeUpload(name: String, file: File): StudioUpload {
        recordings += Triple(name, file.length(), file.readBytes())
        record("studio transcribe $name ${file.length()}")
        file.delete()
        return StudioUpload.Queued(nextJob++)
    }

    /** Composing (v1.7 — M24): the pieces a seed may be (id to title), the default one, and the orders taken. */
    val seedPieces = mutableMapOf(12L to "Clair de lune", 13L to "Für Elise")
    var defaultSeed: Long? = 12L
    val composed: MutableList<ComposeOrder> = Collections.synchronizedList(mutableListOf())
    var composeRefusal: String? = null

    override suspend fun composeSeed(pieceId: Long?): SeedChoice? {
        val id = pieceId ?: defaultSeed ?: return null
        val title = seedPieces[id] ?: return null
        return SeedChoice(id, title, if (id == 12L) "Claude Debussy" else "Ludwig van Beethoven", SeedFacts(MusicKey(if (id == 12L) 1 else 9, id == 13L), 66, 66.2))
    }

    override suspend fun compose(order: ComposeOrder): StudioCompose {
        record("studio compose ${order.pieceId} ${order.request.mood} ${order.request.key} ${order.request.bpm} ${order.request.minutes}")
        composeRefusal?.let { return StudioCompose.Refused(it) }
        val id = order.pieceId ?: defaultSeed
        if (id == null || id !in seedPieces) return StudioCompose.NoSuchPiece
        composed += order
        return StudioCompose.Queued(nextJob++)
    }

    override suspend fun cancelStudioJob(id: Long): Boolean {
        record("studio cancel $id")
        return studioJobs.remove(id)
    }

    /** The views (v1.13 — M32): what each of their routes answers, and what they were asked (never a change: not in [calls]). */
    var notesAnswer: NowAnswer = NowAnswer.Ready("SPNT-notes".toByteArray())
    var scoreAnswer: NowAnswer = NowAnswer.Ready("SPSI-index".toByteArray())
    var pageAnswer: NowAnswer = NowAnswer.Ready("SPSP-page".toByteArray())
    val viewsAsked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun nowNotes(rev: Int?): NowAnswer {
        viewsAsked += "notes rev=$rev"
        return notesAnswer
    }

    override suspend fun nowScore(rev: Int?, width: Int, height: Int): NowAnswer {
        viewsAsked += "score rev=$rev ${width}x$height"
        return scoreAnswer
    }

    override suspend fun nowScorePage(layoutId: Int, page: Int): NowAnswer {
        viewsAsked += "page $layoutId/$page"
        return pageAnswer
    }

    /**
     * The System page (v1.18 — M46): what its reads answer (fixed, so a listener's answer and the relay's match), the
     * refresh's floor on [clock] (the app's own [RefreshFloor]), whether a firmware update runs, and the zip. By default
     * the clock moves 10 s on at each look, so the floor never trips: a test that sets [clock] sees it.
     */
    var systemHeld = WebSystem(at = 1_790_000_000_000L, piano = WebSystemPiano(facts = mapOf("proto" to "1", "fw" to "2.0.0+a1b2c3d")))
    var historyHeld = listOf(dev.stevenjin.stevenpiano.diag.SystemSample(1_790_000_000_000L, batteryPct = 80, batteryTenthsC = 312))
    private var looks = 0L
    var clock: () -> Long = { ++looks * RefreshFloor.REFRESH_FLOOR_MS }
    private val refreshFloor = RefreshFloor { clock() }
    var firmwareUpdating = false
    var zip: ByteArray? = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6) + ByteArray(18)

    override suspend fun system(): WebSystem = systemHeld

    override suspend fun systemHistory(): List<dev.stevenjin.stevenpiano.diag.SystemSample> = historyHeld

    override suspend fun refreshPiano(): Boolean = refreshFloor.take().also { if (it) record("system refresh") }

    override suspend fun systemTool(tool: SystemTool): SystemToolResult {
        if (tool == SystemTool.RECONNECT && firmwareUpdating) return SystemToolResult.BUSY
        record("system tool ${tool.key}")
        return SystemToolResult.DONE
    }

    override suspend fun diagnostics(): ByteArray? = zip

    /** The Settings page's Playback (v1.18 — M47b): the app's settings at their defaults, a piano with Full power on and a 110 ms repeat period. */
    var settingsHeld = WebSettings(fullPower = true, repeatMs = 110)

    override suspend fun settings(): WebSettings = settingsHeld

    /** The cover picker (v1.18 — M48): what a search answers (one JPEG cover by default), and what a choice or a removal does. */
    var coverSearchAnswer: dev.stevenjin.stevenpiano.data.art.CoverSearch = dev.stevenjin.stevenpiano.data.art.CoverSearch.Found(
        "s1",
        listOf(dev.stevenjin.stevenpiano.data.art.CoverPick(0, "Interstellar", "Hans Zimmer", byteArrayOf(-1, -40, -1, -32), "image/jpeg")),
    )
    var coverChangeAnswer: dev.stevenjin.stevenpiano.data.art.CoverChange = dev.stevenjin.stevenpiano.data.art.CoverChange.Done

    override suspend fun coverSearch(text: String): dev.stevenjin.stevenpiano.data.art.CoverSearch = coverSearchAnswer.also { record("cover search $text") }

    override suspend fun coverChoose(pieceId: Long, searchId: String, index: Int): dev.stevenjin.stevenpiano.data.art.CoverChange =
        coverChangeAnswer.also { record("cover choose $pieceId $searchId $index") }

    override suspend fun coverRemove(pieceId: Long): dev.stevenjin.stevenpiano.data.art.CoverChange = coverChangeAnswer.also { record("cover remove $pieceId") }

    /** Quiet times (v1.20 — M54): the quiet now (none by default), and the sections kept. */
    var quietHeld = QuietNow()
    val sectionsHeld: MutableList<QuietSection> = Collections.synchronizedList(mutableListOf())

    override suspend fun quietNow(): QuietNow = quietHeld

    override suspend fun quiet(): WebQuiet = WebQuiet(sectionsHeld.toList(), quietHeld)

    override suspend fun saveQuiet(sections: List<QuietSection>) {
        record("quiet save ${sections.size}")
        sectionsHeld.clear()
        sectionsHeld += sections
    }

    override suspend fun overrideQuiet(): Boolean {
        if (!quietHeld.now) return false
        record("quiet override")
        quietHeld = quietHeld.copy(overridden = true)
        return true
    }
}
