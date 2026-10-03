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
import dev.stevenjin.stevenpiano.data.LibraryScope
import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.player.DynamicRange
import dev.stevenjin.stevenpiano.player.ExpressionLevel
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.QueueSnapshot
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.schedule.SaveResult
import dev.stevenjin.stevenpiano.schedule.ScheduleDraft
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import java.io.File
import java.util.Locale

/**
 * Everything the web panel reads and does, and nothing else (DESIGN.md › v1.5.1 — M18): the
 * player's state and commands, the library's lists and art, the channels, the piano's settings,
 * the app's playback preferences, the guests' catalogue and queue, the PIN's hash, imports,
 * (v1.6.2 — M19) the schedules, and (v1.7 — M23) Studio.
 * The server ([WebServer]) sees the app only through this; the app's own ([AppWebBackend]) runs
 * every player command on the main thread, where `Player` lives, and the tests' fake records what
 * it is asked. Every call is made from one of the server's request threads.
 */
interface WebBackend {
    /** What `/api/state` and the socket's state messages carry, but for the guests' requests (the server adds them). */
    suspend fun state(): WebState

    /**
     * Pieces in the library: those whose title or composer contains [query] when it is not blank, else the [category];
     * [limit] from [offset]; within the genre's [scope] (v1.14 — M37: the panel's switch; All is every piece).
     */
    suspend fun library(query: String?, category: LibraryCategory, offset: Int, limit: Int, scope: LibraryScope = LibraryScope.All): WebPage

    /** The playlists in the app's order; under a genre's [scope], those that show under it (most of their pieces of it, a tie both, Recordings and Made in Studio always). */
    suspend fun playlists(scope: LibraryScope = LibraryScope.All): List<WebPlaylist>

    /** Playlist [id] and its pieces in its order; null when there is none. */
    suspend fun playlist(id: Long): WebPlaylistDetail?

    /** The composers or artists; under a genre's [scope], those with a piece of it, counted within it. */
    suspend fun composers(scope: LibraryScope = LibraryScope.All): List<WebComposer>

    /** Composer [key] and their pieces (within [scope]); null when the library has none by them there. */
    suspend fun composer(key: String, scope: LibraryScope = LibraryScope.All): WebComposerDetail?

    /** Composer [key]'s portrait at [size]; null when there is none. */
    suspend fun composerArt(key: String, size: WebArtSize): WebImage?

    /** Piece [id]'s art: its own cover (a Studio piece's), else its composer's portrait, else its own roll card (a mask the page tints); null when none can be had. */
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

    /**
     * What guests may ask for: the built-in lists' pieces (Popular, Recognisable, Epic on piano), each piece once, as
     * Classical lists; and (v1.14 — M37) the list Modern, every Modern piece by title up to [WebLimits.GUEST_MODERN],
     * kept until the library changes. Titles and composers only: no art is looked up for it.
     */
    suspend fun catalogue(): List<CatalogueList>

    /** Piece [pieceId] as guests are offered it (in a built-in list, or the Modern list); null when it is not on offer. */
    suspend fun offered(pieceId: Long): WebPiece?

    /** Whether guests may ask, and whether their requests wait for approval. */
    suspend fun guestSettings(): GuestSettings

    /** Adds piece [pieceId] at the end of Up next for a guest; returns the queue entries added (their uids). */
    suspend fun queueRequested(pieceId: Long): List<Long>

    /** The panel's PIN as it is kept (salted and hashed); null while none is set. */
    suspend fun pinHash(): PinHash?

    /** Piano › Schedule as the panel shows it: every schedule by start time, the next start, the last one's outcome, whether exact alarms are allowed. */
    suspend fun schedules(): WebSchedules

    /** What a schedule's target is called (a channel's name, a playlist's, a piece's title); null when there is no such one. */
    suspend fun scheduleTarget(kind: ScheduleKind, target: String): String?

    /** Saves a schedule the panel made or changed, already checked (no [ScheduleDraft.problem], its target found). */
    suspend fun saveSchedule(draft: ScheduleDraft): SaveResult

    /** Deletes schedule [id]; false when there is none. */
    suspend fun deleteSchedule(id: Long): Boolean

    /** Studio (v1.7 — M23): whether it runs here, its models and its jobs (the state carries them too). */
    suspend fun studio(): WebStudio

    /**
     * Transcribes the recording the panel sent, saved as [file] in [uploadDir] (deleted once read, or at
     * once when Studio can't take it); [name] is its file name. Returns at once: the job is in [state].
     */
    suspend fun transcribeUpload(name: String, file: File): StudioUpload

    /** Stops Studio's job [id]; false when there is none left to stop. */
    suspend fun cancelStudioJob(id: Long): Boolean

    /**
     * Composing (v1.7 — M24): the seed piece [pieceId] (the default one, the piece played last, when null)
     * with its key and tempo, for the panel's form; null when there is no such piece.
     */
    suspend fun composeSeed(pieceId: Long?): SeedChoice?

    /** Composes a piece from [order] (its seed a piece of the library): queued, refused when Studio can't run here, or no such piece. */
    suspend fun compose(order: ComposeOrder): StudioCompose

    /**
     * The panel's views (v1.13 — M32, [NowViews]): the notes of the piece playing as revision [rev] shows them (the
     * current one when null), in [dev.stevenjin.stevenpiano.score.ScoreDisplayList]'s notes format.
     */
    suspend fun nowNotes(rev: Int?): NowAnswer

    /** The score's index for a browser panel [width] × [height] CSS px, of revision [rev]: laid out within the bounds [NowViews] keeps. */
    suspend fun nowScore(rev: Int?, width: Int, height: Int): NowAnswer

    /** Page [page] of the score's layout [layoutId], once laid out; never starts a layout. */
    suspend fun nowScorePage(layoutId: Int, page: Int): NowAnswer
}

/** A model on the panel's Studio page: its size and licence, whether it is installed, its line ("Installed · 125 MB · CC BY 4.0", or its download's), its download's progress. */
data class WebModel(val name: String, val title: String, val sizeBytes: Long, val licence: String, val installed: Boolean, val line: String, val progress: Float? = null)

/**
 * One of Studio's jobs as the tablet's page lists it: "download", "transcribe" or "compose", "queued"… "cancelled", its
 * line, its progress, the piece it made; since v1.12 (M30) its [step] and [steps], the tokens written, the music
 * written of what was asked ([musicMs], [targetMs]), the time left ([etaMs]), the notes written and its [turn] in
 * Studio's history. The notes themselves never travel in the state.
 */
data class WebStudioJob(
    val id: Long,
    val kind: String,
    val name: String,
    val state: String,
    val line: String,
    val progress: Float?,
    val title: String?,
    val step: String = "waiting",
    val steps: List<String> = emptyList(),
    val tokens: Int = 0,
    val musicMs: Long = 0,
    val targetMs: Long = 0,
    val etaMs: Long? = null,
    val notes: Int = 0,
    val turn: Long? = null,
)

/** Studio on the panel: [available] (else [reason]), the models, the jobs newest first. */
data class WebStudio(val available: Boolean = false, val reason: String? = null, val models: List<WebModel> = emptyList(), val jobs: List<WebStudioJob> = emptyList())

/** How a composition the panel asked for went: queued as job [jobId], refused for [reason] (Studio can't run here), or its piece isn't in the library. */
sealed interface StudioCompose {
    data class Queued(val jobId: Long) : StudioCompose

    data class Refused(val reason: String) : StudioCompose

    data object NoSuchPiece : StudioCompose
}

/** How a recording the panel sent went: queued as job [jobId], or refused for [reason] (Studio can't run here). */
sealed interface StudioUpload {
    data class Queued(val jobId: Long) : StudioUpload

    data class Refused(val reason: String) : StudioUpload
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
    /** Its own cover (v1.12 — M30: a Studio piece's), drawn at [artVersion]: the panel asks for it afresh when that changes. */
    val cover: Boolean = false,
    val artVersion: Long = 0,
    /** "classical" or "modern" (v1.14 — M37, `Genres.name`); null for a piece made on the tablet, or one not read from the library. */
    val genre: String? = null,
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

/**
 * A channel's card: its pool's size, whether it can play (three pieces or more), whether it plays, its volume, and the
 * genre it is listed under ([genre], v1.14 — M37: "classical", "modern", or null for Everything, which is both).
 */
data class WebChannel(
    val key: String,
    val name: String,
    val size: Int,
    val playable: Boolean,
    val playing: Boolean,
    val volume: Int,
    val composers: List<WebCardComposer>,
    val genre: String? = null,
)

/** The channel playing, its name and volume. */
data class WebChannelPlaying(val key: String, val name: String, val volume: Int)

/**
 * The tablet's piano sound (v1.8 — M25): its [mode] ("off", "whenNotConnected", "always"), its [volume]
 * (the panel may change it), whether it sounds now ([active]) and whether the SoundFont is on the tablet.
 */
data class WebTablet(val mode: String = "whenNotConnected", val volume: Int = 60, val active: Boolean = false, val installed: Boolean = false)

/** An entry of the queue with its piece; [requested] when a guest asked for it. */
data class WebQueueItem(val uid: Long, val piece: WebPiece, val requested: Boolean = false)

/**
 * The player as the panel shows it: [positionMs] as sampled for this message (below zero during the
 * pause before a piece, as the app's own clock runs); [items] the current piece and up to
 * [WebLimits.QUEUE_ITEMS] pieces after it, with their titles.
 */
data class WebPlayer(
    val status: PlaybackStatus = PlaybackStatus.Stopped,
    val loading: Boolean = false,
    val piece: WebPiece? = null,
    val positionMs: Long = 0,
    /** When [positionMs] was sampled, on the tablet's monotonic clock in ms (v1.13 — M32): the panel's clock runs from it. */
    val at: Long = 0,
    val tempoPct: Int = 100,
    val transpose: Int = 0,
    val velocityPct: Int = 100,
    val preRollMs: Int = 0,
    val channel: WebChannelPlaying? = null,
    val tablet: WebTablet = WebTablet(),
    val queue: QueueSnapshot = QueueSnapshot(),
    val items: List<WebQueueItem> = emptyList(),
    val problem: String? = null,
    /** Folding out-of-range notes (v1.13 — M32: the views' keys follow it). */
    val fold: Boolean = true,
    /** What the panel's views show of the piece playing (v1.13 — M32); null with nothing loaded. */
    val views: WebViews? = null,
)

/**
 * The panel's views of the piece playing (v1.13 — M32): their revision [rev] (it moves on whenever what they show
 * changes, and the routes refuse an old one), how many [notes], whether the [hands], the [fingers] and the [chords]
 * come with them, and whether a [score] can be laid out (within [NowViews]' caps).
 */
data class WebViews(val rev: Int, val notes: Int, val hands: Boolean, val fingers: Boolean, val chords: Boolean, val score: Boolean)

/**
 * The tablet's display settings the panel's View control shows and changes (v1.13 — M32): [noteDisplay]
 * ("paperRoll", "falling", or "score", a phone-sized tablet's choice), the roll's style ("paperRoll" or "falling"),
 * Fingering, Chord names and Hand colours.
 */
data class WebDisplay(
    val noteDisplay: String = "paperRoll",
    val rollStyle: String = "paperRoll",
    val fingering: Boolean = true,
    val chordNames: Boolean = true,
    val handColours: Boolean = false,
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

/**
 * Where the panel and the request page are reached: `http://100.101.2.3:8737`, and the guests'
 * `…/request`; and (v1.10 — M26) the panel's public link through Steven Piano Cloud,
 * `https://<relay>/p/<id>/`, while remote access over the internet is on.
 */
data class WebAddresses(val panel: String?, val guest: String?, val cloud: String? = null)

/** Guests: whether they may ask, and whether a request waits for approval. */
data class GuestSettings(val open: Boolean, val approveFirst: Boolean)

/** One of the lists guests choose from, with the pieces it holds now, and its [genre] (v1.14 — M37: "classical" or "modern"). */
data class CatalogueList(val key: String, val name: String, val pieces: List<WebPiece>, val genre: String? = null)

/**
 * Everything `/api/state` carries but the requests waiting. [monochrome]: Artwork in black and
 * white is on, so the panel draws portraits without colour, as the app does. [albumBackdrop]: Album
 * colours behind the player is on (v1.15 — M41), so the panel's Now playing has them too. [schedule]: the next
 * start's line for Now playing with nothing loaded, and a revision that changes whenever the
 * schedules do, so an open Schedule page reads them again.
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
    val albumBackdrop: Boolean = true,
    val schedule: WebScheduleState = WebScheduleState(),
    val studio: WebStudio = WebStudio(),
    /** What plays and what is played from (v1.11 — M29), read-only. */
    val instruments: WebInstruments = WebInstruments(),
    /** The display settings the panel's View control mirrors (v1.13 — M32). */
    val display: WebDisplay = WebDisplay(),
)

/**
 * What plays and what is played from (v1.11 — M29), read-only on the panel: the [instrument], the [keyboard]
 * (null with none chosen), whether the keyboard plays the instrument now ([live]) and whether a take runs
 * ([recording]). Live, recording and the choice of a device are the tablet's alone: nothing the panel or
 * the cloud sends changes them.
 */
data class WebInstruments(
    val instrument: WebInstrument = WebInstrument(),
    val keyboard: WebKeyboard? = null,
    val live: Boolean = false,
    val recording: Boolean = false,
) {
    companion object {
        /**
         * As the panel reads it: the instrument [kind] (named [midiName] when a MIDI piano), its [link] state's name
         * ([WebLink.state]), the [keyboard], and whether Live is open and a take runs.
         */
        fun of(kind: InstrumentKind, midiName: String?, link: String, keyboard: KeyboardState, live: Boolean, recording: Boolean): WebInstruments =
            WebInstruments(
                instrument = WebInstrument(
                    kind = if (kind == InstrumentKind.MidiPiano) "midi" else "steven",
                    name = InstrumentCopy.instrumentValue(kind, midiName),
                    state = link,
                ),
                keyboard = keyboard.chosen?.let { WebKeyboard(it.name, it.transport.name.lowercase(Locale.ROOT), keyboardState(keyboard.phase)) },
                live = live,
                recording = recording,
            )

        /** A keyboard's state on the wire. */
        fun keyboardState(phase: KeyboardState.Phase): String = when (phase) {
            KeyboardState.Phase.Connected -> "connected"
            KeyboardState.Phase.Connecting -> "connecting"
            KeyboardState.Phase.NeedsPairing -> "pairing"
            KeyboardState.Phase.Unavailable -> "unavailable"
            KeyboardState.Phase.None, KeyboardState.Phase.NotConnected -> "disconnected"
        }
    }
}

/** The instrument: [kind] "steven" (Steven Piano) or "midi" (another MIDI piano), its [name], and its link's [state] as [WebLink] names it. */
data class WebInstrument(val kind: String = "steven", val name: String = "Steven Piano", val state: String = "disconnected")

/** The keyboard chosen: its [name], "usb", "bluetooth" or "virtual", and "connected", "connecting", "disconnected", "pairing" or "unavailable". */
data class WebKeyboard(val name: String, val transport: String, val state: String)

/** The schedules in the state: the next start ("Next: Wednesday 12:30, Calm", null with none ahead) and a revision of the list. */
data class WebScheduleState(val next: String? = null, val revision: Int = 0)

/** A schedule as the panel lists it: its row, what its target is called, and the two lines the tablet shows ("Weekdays 12:30", "Calm channel · until 13:15 · 70%"). */
data class WebSchedule(val entry: ScheduleEntity, val name: String, val whenLine: String, val whatLine: String)

/** Piano › Schedule for the panel: the rows, the next start's line, the last one's outcome, and whether Android allows exact alarms. */
data class WebSchedules(val schedules: List<WebSchedule>, val next: String?, val last: String?, val exactAlarms: Boolean)

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
    /** How a piece is played (v1.16 — M44): Dynamic range, Quietest note, Expression, Re-strike time (0: Auto). */
    val dynamicRange: DynamicRange? = null,
    val velocityFloor: Int? = null,
    val expression: ExpressionLevel? = null,
    val restrikeMs: Int? = null,
    val webGuests: Boolean? = null,
    val webApproveFirst: Boolean? = null,
    val webHostName: String? = null,
    /** The tablet's piano sound's volume, 0–100 % (v1.8 — M25); its mode is the tablet's alone. */
    val tabletVolume: Int? = null,
    /** The View control's four (v1.13 — M32): the roll's style (paper roll or falling notes only), Fingering, Chord names, Hand colours. */
    val noteDisplay: NoteDisplay? = null,
    val fingering: Boolean? = null,
    val chordNames: Boolean? = null,
    val handColours: Boolean? = null,
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

    /** The guests' Modern list (v1.14 — M37): at most this many pieces, the first by title. */
    const val GUEST_MODERN = 2_000

    /** A views panel's width or height as a request may give it, in CSS px (v1.13 — M32; [NowViews] then holds it to its own bounds). */
    val VIEW_SIZE = 1..8_192
}
