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
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.QueueSnapshot
import dev.stevenjin.stevenpiano.player.RepeatMode
import java.io.File

/**
 * Everything the web panel reads and does, and nothing else (DESIGN.md › v1.5.1 — M18): the
 * player's state and commands, the library's lists and art, the channels, the piano's settings,
 * the app's playback preferences, the guests' catalogue and queue, the PIN's hash, and imports.
 * The server ([WebServer]) sees the app only through this; the app's own ([AppWebBackend]) runs
 * every player command on the main thread, where `Player` lives, and the tests' fake records what
 * it is asked. Every call is made from one of the server's request threads.
 */
interface WebBackend {
    /** What `/api/state` and the socket's state messages carry, but for the guests' requests (the server adds them). */
    suspend fun state(): WebState

    /** Pieces in the library: those whose title or composer contains [query] when it is not blank, else the [category]; [limit] from [offset]. */
    suspend fun library(query: String?, category: LibraryCategory, offset: Int, limit: Int): WebPage

    suspend fun playlists(): List<WebPlaylist>

    /** Playlist [id] and its pieces in its order; null when there is none. */
    suspend fun playlist(id: Long): WebPlaylistDetail?

    suspend fun composers(): List<WebComposer>

    /** Composer [key] and their pieces; null when the library has none by them. */
    suspend fun composer(key: String): WebComposerDetail?

    /** Composer [key]'s portrait at [size]; null when there is none. */
    suspend fun composerArt(key: String, size: WebArtSize): WebImage?

    /** Piece [id]'s art: its composer's portrait, else its own roll card (a mask the page tints); null when neither can be had. */
    suspend fun pieceArt(id: Long): WebImage?

    /** Plays piece [pieceId], then [queue] around it (just the piece when null). False when the library has no such piece. */
    suspend fun play(pieceId: Long, queue: List<Long>?): Boolean

    /** Plays [ids] from the top (in random order when [shuffle]); the ones the library does not hold are left out. False when none is left. */
    suspend fun playAll(ids: List<Long>, shuffle: Boolean): Boolean

    /** Plays playlist [id] from the top, or shuffled. False when it does not exist or is empty. */
    suspend fun playPlaylist(id: Long, shuffle: Boolean): Boolean

    suspend fun transport(action: Transport)

    /** Seeks to [ms] from the piece's start (held within the piece). */
    suspend fun seek(ms: Long)

    /** The live tempo, already checked to be in range. */
    suspend fun setTempo(pct: Int)

    suspend fun setShuffle(on: Boolean)

    suspend fun setRepeat(mode: RepeatMode)

    /** Changes Up next. False when the queue has no such entry (a stale page). */
    suspend fun queue(command: QueueCommand): Boolean

    suspend fun channels(): List<WebChannel>

    suspend fun playChannel(key: String): ChannelStart

    /** Stops the channel playing (and the player with it); nothing when none plays. */
    suspend fun stopChannel()

    /** Channel [key]'s volume, 0-100 %, saved and heard at once if it plays. False for an unknown channel. */
    suspend fun setChannelVolume(key: String, pct: Int): Boolean

    /** The piano's settings as it last reported them, and its status report. */
    suspend fun piano(): WebPiano

    /** Setting [name] to [wire] (its wire form), both already checked against the table and its range. */
    suspend fun setPiano(name: String, wire: String)

    /** A feel preset, already checked to be one of the table's. */
    suspend fun pianoPreset(command: String)

    /** All keys off, Save now or Read status. */
    suspend fun pianoAction(action: PianoAction)

    /** The app's preferences the panel may change, each checked or clamped as the app's own setters do. */
    suspend fun applySettings(change: SettingsChange)

    /** Where uploads wait while they are read (`cacheDir/web`), and nothing else. */
    val uploadDir: File

    /** Starts importing one MIDI file sent by the panel; returns at once (the progress is in [state]). */
    suspend fun importMidi(name: String, bytes: ByteArray)

    /** Starts importing a zip the panel sent, saved as [file] (deleted once read); returns at once. */
    suspend fun importZip(name: String, file: File)

    /** What guests may ask for: the built-in lists' pieces (Popular, Recognisable, Epic on piano), each piece once. */
    suspend fun catalogue(): List<CatalogueList>

    /** Whether guests may ask, and whether their requests wait for approval. */
    suspend fun guestSettings(): GuestSettings

    /** Adds piece [pieceId] at the end of Up next for a guest; returns the queue entries added (their uids). */
    suspend fun queueRequested(pieceId: Long): List<Long>

    /** The panel's PIN as it is kept (salted and hashed); null while none is set. */
    suspend fun pinHash(): PinHash?
}

/** The Library's lists besides search: all pieces by title, the favourites, the last hundred played or added. */
enum class LibraryCategory(val key: String) {
    ALL("all"),
    FAVORITES("favorites"),
    RECENT("recent"),
    ;

    companion object {
        fun of(key: String?): LibraryCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A piece as the panel shows it: [composer] in full (Now playing's eyebrow), [composerShort] as rows
 * show it ("Chopin · 4:31"). [portrait]: its composer has a portrait (the panel shows it), else its
 * art is its own roll card, which the panel tints as the app does.
 */
data class WebPiece(
    val id: Long,
    val title: String,
    val composer: String,
    val composerKey: String,
    val durationMs: Long,
    val composerShort: String = composer,
    val favorite: Boolean = false,
    val portrait: Boolean = false,
)

/** One page of a list: [total] pieces in all, these from [offset]. */
data class WebPage(val total: Int, val offset: Int, val pieces: List<WebPiece>)

data class WebPlaylist(val id: Long, val name: String, val pieceCount: Int, val durationMs: Long, val builtIn: Boolean)

data class WebPlaylistDetail(val playlist: WebPlaylist, val pieces: List<WebPiece>)

data class WebComposer(val key: String, val name: String, val pieceCount: Int, val portrait: Boolean)

data class WebComposerDetail(val composer: WebComposer, val pieces: List<WebPiece>)

/** An image and its type, as the art routes send it. */
class WebImage(val bytes: ByteArray, val contentType: String)

/** A portrait for a 40 px row (at most 128 px) or a tile (at most 512 px). */
enum class WebArtSize(val key: String) {
    ROW("row"),
    TILE("tile"),
    ;

    companion object {
        fun of(key: String?): WebArtSize? = entries.firstOrNull { it.key == key }
    }
}

/** The transport's buttons. */
enum class Transport(val key: String) {
    TOGGLE("toggle"),
    PAUSE("pause"),
    RESUME("resume"),
    NEXT("next"),
    PREVIOUS("previous"),
    STOP("stop"),
    ;

    companion object {
        fun of(key: String?): Transport? = entries.firstOrNull { it.key == key }
    }
}

/** A change to Up next, as the app's own Up next sheet and row menus make them. */
sealed interface QueueCommand {
    data class PlayNext(val ids: List<Long>) : QueueCommand

    data class Add(val ids: List<Long>) : QueueCommand

    data class Remove(val uid: Long) : QueueCommand

    /** Entry [uid] to place [toIndex] among the pieces up next. */
    data class Move(val uid: Long, val toIndex: Int) : QueueCommand

    data object Clear : QueueCommand

    /** Plays entry [uid] now. */
    data class Skip(val uid: Long) : QueueCommand
}

/** How a channel's start went: it plays, its pool is under three pieces ("Add more pieces"), or there is no such channel. */
enum class ChannelStart { STARTED, TOO_SMALL, UNKNOWN }

/** A composer on a channel's card: the key finds the portrait, the name the monogram. */
data class WebCardComposer(val key: String, val name: String, val portrait: Boolean)

/** A channel's card: its pool's size, whether it can play (three pieces or more), whether it plays, its volume. */
data class WebChannel(
    val key: String,
    val name: String,
    val size: Int,
    val playable: Boolean,
    val playing: Boolean,
    val volume: Int,
    val composers: List<WebCardComposer>,
)

/** The channel playing, its name and volume. */
data class WebChannelPlaying(val key: String, val name: String, val volume: Int)

/** An entry of the queue with its piece; [requested] when a guest asked for it. */
data class WebQueueItem(val uid: Long, val piece: WebPiece, val requested: Boolean = false)

/**
 * The player as the panel shows it: [positionMs] as sampled for this message; [items] the current
 * piece and up to [WebLimits.QUEUE_ITEMS] pieces after it, with their titles.
 */
data class WebPlayer(
    val status: PlaybackStatus = PlaybackStatus.Stopped,
    val loading: Boolean = false,
    val piece: WebPiece? = null,
    val positionMs: Long = 0,
    val tempoPct: Int = 100,
    val transpose: Int = 0,
    val velocityPct: Int = 100,
    val preRollMs: Int = 0,
    val channel: WebChannelPlaying? = null,
    val queue: QueueSnapshot = QueueSnapshot(),
    val items: List<WebQueueItem> = emptyList(),
    val problem: String? = null,
)

/** The piano link: its state's name ("connected", "scanning"…) and the piano's name while connected. */
data class WebLink(val state: String, val name: String? = null)

/** What the piano reported: "unknown", "unsupported" or "ready", its values and facts, and its last refusal. */
data class WebPianoState(
    val state: String,
    val values: Map<String, String> = emptyMap(),
    val facts: Map<String, String> = emptyMap(),
    val lastError: String? = null,
    val errorAbout: String? = null,
)

/** `/api/piano`: the state, and the Read status report (null before one; "" when the piano didn't answer). */
data class WebPiano(val state: WebPianoState, val statusText: String?, val statusReading: Boolean)

/** Artwork fetched in the background: [done] of [total] while [running]. */
data class WebArtwork(val running: Boolean, val done: Int, val total: Int)

/** Where the panel and the request page are reached: `http://100.101.2.3:8737`, and the guests' `…/request`. */
data class WebAddresses(val panel: String?, val guest: String?)

/** Guests: whether they may ask, and whether a request waits for approval. */
data class GuestSettings(val open: Boolean, val approveFirst: Boolean)

/** One of the built-in lists guests choose from, with the pieces it holds now. */
data class CatalogueList(val key: String, val name: String, val pieces: List<WebPiece>)

/**
 * Everything `/api/state` carries but the requests waiting. [monochrome]: Artwork in black and
 * white is on, so the panel draws portraits without colour, as the app does.
 */
data class WebState(
    val player: WebPlayer = WebPlayer(),
    val link: WebLink = WebLink("disconnected"),
    val piano: WebPianoState = WebPianoState("unknown"),
    val import: ImportProgress = ImportProgress.Idle,
    val artwork: WebArtwork = WebArtwork(running = false, done = 0, total = 0),
    val web: WebAddresses = WebAddresses(null, null),
    val guests: GuestSettings = GuestSettings(open = false, approveFirst = true),
    val monochrome: Boolean = false,
)

/**
 * The preferences the panel may change (`PUT /api/settings`); null leaves one as it is. The
 * playback preferences are clamped as the app's own setters clamp them; [webHostName] "" forgets
 * the name. The PIN, the Web control switch and Panel on Wi-Fi too are the tablet's alone.
 */
data class SettingsChange(
    val preRollMs: Int? = null,
    val defaultTempoPct: Int? = null,
    val transpose: Int? = null,
    val velocityPct: Int? = null,
    val foldOutOfRange: Boolean? = null,
    val skipDrumChannel: Boolean? = null,
    val webGuests: Boolean? = null,
    val webApproveFirst: Boolean? = null,
    val webHostName: String? = null,
) {
    val isEmpty: Boolean get() = this == SettingsChange()
}

/** How much the panel is given or may ask for at once. */
object WebLimits {
    /** Up next's rows in a state message: the current piece and this many after it. */
    const val QUEUE_ITEMS = 100

    /** Pieces in one page of a list. */
    const val PAGE_MAX = 200

    /** Piece ids in one request (a list played, a queue given, pieces added). */
    const val IDS_MAX = 5_000
}
