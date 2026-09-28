// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

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
}
