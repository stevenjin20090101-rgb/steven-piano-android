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
import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.schedule.SaveResult
import dev.stevenjin.stevenpiano.schedule.ScheduleCopy
import dev.stevenjin.stevenpiano.schedule.ScheduleDraft
import dev.stevenjin.stevenpiano.schedule.ScheduleRules
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
        WebPiece(1, "Clair de lune", "Claude Debussy", "debussy", 300_000, composerShort = "Debussy", portrait = true),
        WebPiece(2, "Nocturne in E-flat", "Frédéric Chopin", "chopin", 271_000, composerShort = "Chopin"),
        WebPiece(3, "Für Elise", "Ludwig van Beethoven", "beethoven", 180_000, composerShort = "Beethoven", favorite = true),
    )
    val playlistsHeld = mutableListOf(WebPlaylist(10, "Evening", 2, 571_000, builtIn = false))
    val playlistPieces = mutableMapOf(10L to listOf(1L, 2L))
    val channelsHeld = mutableListOf(
        WebChannel("calm", "Calm", 30, playable = true, playing = false, volume = 70, composers = listOf(WebCardComposer("debussy", "Debussy", true))),
        WebChannel("tiny", "Tiny", 2, playable = false, playing = false, volume = 70, composers = emptyList()),
    )
    var piano = WebPiano(WebPianoState("ready", mapOf("volume" to "70", "leds" to "1")), statusText = null, statusReading = false)
    var guests = GuestSettings(open = true, approveFirst = false)
    var catalogueHeld = listOf(CatalogueList("popular", "Popular", pieces.take(2)))
    var pin: PinHash? = null
    val imported = Collections.synchronizedList(mutableListOf<String>())
    private var nextUid = 100L

    private fun record(line: String) {
        calls += line
    }

    override suspend fun state(): WebState = state

    override suspend fun library(query: String?, category: LibraryCategory, offset: Int, limit: Int): WebPage {
        val all = when {
            !query.isNullOrBlank() -> pieces.filter { query.lowercase() in it.title.lowercase() || query.lowercase() in it.composer.lowercase() }
            category == LibraryCategory.FAVORITES -> pieces.filter { it.favorite }
            else -> pieces
        }
        val from = offset.coerceIn(0, all.size)
        return WebPage(all.size, from, all.drop(from).take(limit))
    }

    override suspend fun playlists(): List<WebPlaylist> = playlistsHeld

    override suspend fun playlist(id: Long): WebPlaylistDetail? {
        val playlist = playlistsHeld.firstOrNull { it.id == id } ?: return null
        return WebPlaylistDetail(playlist, playlistPieces[id].orEmpty().mapNotNull { pid -> pieces.firstOrNull { it.id == pid } })
    }

    override suspend fun composers(): List<WebComposer> =
        pieces.groupBy { it.composerKey }.map { (key, list) -> WebComposer(key, list.first().composer, list.size, list.first().portrait) }

    override suspend fun composer(key: String): WebComposerDetail? {
        val list = pieces.filter { it.composerKey == key }
        if (list.isEmpty()) return null
        return WebComposerDetail(WebComposer(key, list.first().composer, list.size, list.first().portrait), list)
    }

    override suspend fun composerArt(key: String, size: WebArtSize): WebImage? =
        if (key == "debussy") WebImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2), "image/jpeg") else null

    override suspend fun pieceArt(id: Long): WebImage? =
        if (pieces.any { it.id == id }) WebImage(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), "image/png") else null

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

    override suspend fun catalogue(): List<CatalogueList> = catalogueHeld

    override suspend fun guestSettings(): GuestSettings = guests

    override suspend fun queueRequested(pieceId: Long): List<Long> {
        record("requested $pieceId")
        return listOf(nextUid++)
    }

    override suspend fun pinHash(): PinHash? = pin

    /** The schedules, by id; [exactAlarms] and [lastOutcome] as the app would report them. */
    val schedulesHeld = sortedMapOf<Long, ScheduleEntity>()
    var exactAlarms = true
    var lastOutcome: String? = null
    private var nextScheduleId = 1L

    override suspend fun schedules(): WebSchedules = WebSchedules(
        schedulesHeld.values.sortedWith(compareBy({ it.startMinute }, { it.id })).map { e ->
            val name = scheduleTarget(e.kind, e.target) ?: "Gone"
            WebSchedule(e, name, ScheduleCopy.whenLine(e.days, e.startMinute), ScheduleCopy.whatLine(e.kind, name, e.endMinute, e.volumePct))
        },
        next = if (schedulesHeld.values.any { it.enabled }) "Next: Wednesday 12:30, Calm" else null,
        last = lastOutcome,
        exactAlarms = exactAlarms,
    )

    override suspend fun scheduleTarget(kind: ScheduleKind, target: String): String? = when (kind) {
        ScheduleKind.CHANNEL -> channelsHeld.firstOrNull { it.key == target }?.name
        ScheduleKind.PLAYLIST -> playlistsHeld.firstOrNull { it.id.toString() == target }?.name
        ScheduleKind.PIECE -> pieces.firstOrNull { it.id.toString() == target }?.title
    }

    override suspend fun saveSchedule(draft: ScheduleDraft): SaveResult {
        record("schedule save ${draft.id} ${draft.days} ${draft.startMinute} ${draft.kind} ${draft.target} ${draft.endMinute} ${draft.volumePct} ${draft.enabled}")
        if (draft.isNew) {
            if (schedulesHeld.size >= ScheduleRules.MAX_SCHEDULES) return SaveResult.TooMany
            val entity = draft.toEntity(createdAt = 0).copy(id = nextScheduleId++)
            schedulesHeld[entity.id] = entity
            return SaveResult.Saved(entity)
        }
        if (draft.id !in schedulesHeld) return SaveResult.Gone
        return SaveResult.Saved(draft.toEntity(createdAt = 0).also { schedulesHeld[it.id] = it })
    }

    override suspend fun deleteSchedule(id: Long): Boolean {
        record("schedule delete $id")
        return schedulesHeld.remove(id) != null
    }

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
}
