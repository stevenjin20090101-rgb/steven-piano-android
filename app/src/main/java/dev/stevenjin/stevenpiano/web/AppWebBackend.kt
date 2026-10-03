// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import dev.stevenjin.stevenpiano.studio.AudioSource
import dev.stevenjin.stevenpiano.studio.ComposeOrder
import dev.stevenjin.stevenpiano.studio.JobKind
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.SeedChoice
import dev.stevenjin.stevenpiano.studio.StudioFailures
import dev.stevenjin.stevenpiano.studio.StudioSupport
import dev.stevenjin.stevenpiano.ui.StudioCopy
import java.util.Locale
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.BuildConfig
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.LibraryScope
import dev.stevenjin.stevenpiano.data.PlaylistOrder
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.data.imports.ImportBatch
import dev.stevenjin.stevenpiano.data.imports.ImportItem
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.data.imports.OpenedSource
import dev.stevenjin.stevenpiano.data.imports.ZipSource
import dev.stevenjin.stevenpiano.record.RecordingState
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.schedule.SaveResult
import dev.stevenjin.stevenpiano.schedule.ScheduleDraft
import dev.stevenjin.stevenpiano.service.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The web panel's view of the app ([AppGraph]). Reads come from the app's flows and the library;
 * every command that touches the player, the channels or the piano's settings runs on the main
 * thread, their one owner, and a command that may start the piano also starts the playback service
 * (Android may refuse that from the background, where the web service is: playback goes on, and
 * the web service keeps the device awake meanwhile). Imports run in the app's scope, one at a time
 * through the importer's own lock. What the panel's server knows of itself comes in from the
 * web panel ([addresses], [guests], [requested], [applyWebSettings]).
 */
class AppWebBackend(
    private val app: Context,
    private val graph: AppGraph,
    private val addresses: () -> WebAddresses,
    private val guests: () -> GuestSettings,
    private val requested: () -> Set<Long>,
    private val applyWebSettings: suspend (SettingsChange) -> Unit,
    override val uploadDir: File = File(app.cacheDir, UPLOAD_DIR),
) : WebBackend {
    /** Encoded portraits and roll cards, by what they show and when it was fetched: a list's art is encoded once. */
    private val encoded = object : LruCache<String, WebImage>(ART_CACHE_BYTES) {
        override fun sizeOf(key: String, value: WebImage): Int = value.bytes.size
    }

    /** The panel's notes and score of the piece playing (v1.13 — M32): one layout thread, bounded, cleared with the panel. */
    private val views = NowViews(source = ::nowSource)

    /**
     * The guests' Modern list (v1.14 — M37): every Modern piece by title, at most [WebLimits.GUEST_MODERN], title and
     * artist only. Anyone with the request page's address may ask for it, so it is worked out off the main thread half
     * a second after the library last changed (as the channels are) and kept: a request costs no more than writing it out.
     */
    @OptIn(FlowPreview::class)
    private val guestModern: StateFlow<List<WebPiece>> = graph.library.all(LibraryScope.Modern)
        .debounce(SETTLE_MS)
        .map { pieces -> pieces.asSequence().take(WebLimits.GUEST_MODERN).map { it.toGuest() }.toList() }
        .flowOn(Dispatchers.Default)
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "The guests' Modern list couldn't be read")
        }
        .stateIn(graph.appScope, SharingStarted.Eagerly, emptyList())

    /** What the views are drawn from now: the piece playing as Now playing shows it, at the transpose and folding played. */
    private fun nowSource(): NowSource? {
        val player = graph.player.state.value
        val piece = player.piece ?: return null
        val settings = graph.settings.value
        return NowSource(
            notes = piece.notes,
            tempo = piece.tempoMap,
            bars = piece.barStartsMicros,
            keySignatures = piece.keySignatures,
            timeSignatures = piece.timeSignatures,
            durationMicros = piece.durationMicros,
            transpose = player.transpose,
            fold = player.fold,
            hands = piece.handsOrNull,
            fingers = if (settings.fingering) piece.fingersFor(player.transpose, player.fold) else null,
            chords = if (settings.chordNames) piece.chords.takeIf { it.size > 0 } else null,
        )
    }

    /** Forgets the views' layouts (the panel turned off). */
    fun clearViews() = views.clear()

    override suspend fun nowNotes(rev: Int?): NowAnswer = withContext(Dispatchers.Default) { views.notes(rev) }

    override suspend fun nowScore(rev: Int?, width: Int, height: Int): NowAnswer = views.score(rev, width, height)

    override suspend fun nowScorePage(layoutId: Int, page: Int): NowAnswer = withContext(Dispatchers.Default) { views.page(layoutId, page) }

    override suspend fun state(): WebState {
        val player = graph.player.state.value
        val settings = graph.settings.value
        val portraits = portraits()
        val covers = covers()
        val piece = player.piece
        val shown = piece?.let {
            WebPiece(
                it.pieceId, it.title, it.composer, it.composerKey, it.durationMicros / MICROS_PER_MS, portrait = it.composerKey in portraits,
                cover = it.pieceId in covers, artVersion = covers[it.pieceId] ?: portraits[it.composerKey] ?: 0,
            )
        }
        val queue = player.queue
        val from = queue.index.coerceAtLeast(0)
        val itemUids = queue.uids.drop(from).take(WebLimits.QUEUE_ITEMS + 1)
        val itemIds = queue.ids.drop(from).take(WebLimits.QUEUE_ITEMS + 1)
        val known = library { graph.library.pieces(itemIds) }.orEmpty()
        val asked = requested()
        val items = itemUids.zip(itemIds).mapNotNull { (uid, id) ->
            known[id]?.let { WebQueueItem(uid, it.toWeb(portraits, covers), requested = uid in asked) }
        }
        val channel = player.channel?.let { key ->
            WebChannelPlaying(key, channelName(key) ?: key, settings.channelVolume(key))
        }
        val artwork = graph.artwork.progress.value
        val source = nowSource()
        val views = source?.let {
            WebViews(
                rev = this.views.revision() ?: 0,
                notes = it.notes.size,
                hands = it.hands != null,
                fingers = it.fingers != null,
                chords = it.chords != null,
                score = it.notes.size <= NowViews.MAX_SCORE_NOTES && it.bars.size <= NowViews.MAX_SCORE_BARS,
            )
        }
        // The position and the moment it was taken, on the tablet's monotonic clock, together (v1.13 — M32).
        val nanos = System.nanoTime()
        return WebState(
            player = WebPlayer(
                status = player.status,
                loading = player.loading,
                piece = shown,
                // Below zero during the pause before a piece: the page shows 0:00 and "Starting" until it ends.
                positionMs = if (piece == null) 0L else graph.player.positionMicrosAt(nanos) / MICROS_PER_MS,
                at = nanos / NANOS_PER_MS,
                tempoPct = player.tempoPct,
                transpose = player.transpose,
                velocityPct = player.velocityPct,
                preRollMs = settings.preRollMs,
                channel = channel,
                tablet = tablet(),
                queue = queue,
                items = items,
                problem = player.problem,
                fold = player.fold,
                views = views,
            ),
            link = linkOf(graph.pianoLink.state.value),
            piano = pianoOf(graph.pianoSettings.state.value),
            import = graph.importProgress.value,
            artwork = WebArtwork(running = !artwork.idle, done = artwork.done, total = artwork.total),
            web = addresses(),
            guests = guests(),
            monochrome = settings.artworkMonochrome,
            albumBackdrop = settings.albumBackdrop,
            schedule = WebScheduleState(graph.schedules.nextNow()?.line, graph.schedules.revision),
            studio = studio(),
            instruments = WebInstruments.of(
                graph.pianoLink.kind.value,
                settings.midiOutName,
                linkOf(graph.pianoLink.state.value).state,
                graph.keyboard.state.value,
                live = graph.liveThru.state.value.open,
                recording = graph.recording.state.value is RecordingState.Recording,
            ),
            display = WebDisplay(
                noteDisplay = WebApi.noteDisplayName(settings.noteDisplay),
                rollStyle = WebApi.noteDisplayName(settings.noteDisplay.rollStyle),
                fingering = settings.fingering,
                chordNames = settings.chordNames,
                handColours = settings.handColours,
            ),
        )
    }

    override suspend fun library(query: String?, category: LibraryCategory, offset: Int, limit: Int, scope: LibraryScope): WebPage {
        val pieces = library {
            when {
                !query.isNullOrBlank() -> graph.library.search(query, scope).first()
                category == LibraryCategory.FAVORITES -> graph.library.favorites(scope).first()
                category == LibraryCategory.RECENT -> graph.library.recent(scope).first()
                else -> graph.library.all(scope).first()
            }
        }.orEmpty()
        val portraits = portraits()
        val covers = covers()
        val from = offset.coerceIn(0, pieces.size)
        val page = pieces.subList(from, (from + limit).coerceAtMost(pieces.size)).map { it.toWeb(portraits, covers) }
        return WebPage(pieces.size, from, page)
    }

    /**
     * Every playlist, in the order the app's Playlists listing has chosen (v1.10.1 — M28, D6: newest first, or by name);
     * under a genre's [scope], those the repository shows under it (v1.14 — M37).
     */
    override suspend fun playlists(scope: LibraryScope): List<WebPlaylist> {
        val all = library { graph.library.playlists(scope).first() }.orEmpty()
        return PlaylistOrder.listing(all, graph.settings.value.playlistSort, graph.builtIns.keys)
            .map { WebPlaylist(it.id, it.name, it.pieceCount, it.durationMs, it.builtIn) }
    }

    override suspend fun playlist(id: Long): WebPlaylistDetail? {
        val playlist = playlists().firstOrNull { it.id == id } ?: return null
        val portraits = portraits()
        val covers = covers()
        val pieces = library { graph.library.inPlaylist(id).first() }.orEmpty().map { it.toWeb(portraits, covers) }
        return WebPlaylistDetail(playlist, pieces)
    }

    override suspend fun composers(scope: LibraryScope): List<WebComposer> {
        val portraits = portraits()
        return library { graph.library.composers(scope).first() }.orEmpty()
            .map { WebComposer(it.composerKey, it.name, it.pieceCount, it.composerKey in portraits, portraits[it.composerKey] ?: 0) }
    }

    override suspend fun composer(key: String, scope: LibraryScope): WebComposerDetail? {
        val pieces = library { graph.library.byComposer(key, scope).first() }.orEmpty()
        if (pieces.isEmpty()) return null
        val portraits = portraits()
        val covers = covers()
        val name = pieces.minOf { it.composer }
        return WebComposerDetail(WebComposer(key, name, pieces.size, key in portraits, portraits[key] ?: 0), pieces.map { it.toWeb(portraits, covers) })
    }

    override suspend fun composerArt(key: String, size: WebArtSize): WebImage? = portrait(key, artSize(size))

    /** The decode size a web size asks for: a row's, a tile's, or (v1.18 — M47) Now playing's full one. */
    private fun artSize(size: WebArtSize): ArtSize = when (size) {
        WebArtSize.ROW -> ArtSize.Row
        WebArtSize.TILE -> ArtSize.Tile
        WebArtSize.FULL -> ArtSize.Full
    }

    override suspend fun pieceArt(id: Long, kind: WebArtKind?, size: WebArtSize): WebImage? {
        val piece = library { graph.library.piece(id) } ?: return null
        val art = artSize(size)
        // One kind each (v1.17 — M45): a cover asked for is the piece's own or none, so a roll card never lands in an <img>.
        return when (kind) {
            WebArtKind.COVER -> image(ArtworkEntity.forPiece(id), art)
            WebArtKind.ROLL -> rollCard(id)
            // An older page: its own cover first (v1.12 — M30), else its composer's portrait, else its roll card.
            null -> image(ArtworkEntity.forPiece(id), art) ?: portrait(piece.composerKey, art) ?: rollCard(id)
        }
    }

    /** Piece [id]'s roll card as a PNG mask, encoded once. */
    private suspend fun rollCard(id: Long): WebImage? {
        val cacheKey = "roll:$id"
        encoded.get(cacheKey)?.let { return it }
        val card = graph.artwork.rollCard(id) ?: return null
        // The card is an alpha map; the page tints it as the app does (a CSS mask in the theme's grey).
        return withContext(Dispatchers.Default) {
            val argb = card.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
            WebImage(compress(argb, Bitmap.CompressFormat.PNG), PNG).also { encoded.put(cacheKey, it) }
        }
    }

    override suspend fun play(pieceId: Long, queue: List<Long>?): Boolean {
        library { graph.library.piece(pieceId) } ?: return false
        // A queue sent with it keeps only the pieces the library holds, as Play all does.
        val kept = queue?.let { ids ->
            val held = library { graph.library.summaries(ids) }.orEmpty()
            ids.filter { it in held }
        }
        onMain {
            graph.player.play(pieceId, kept ?: listOf(pieceId))
            startPlayback()
        }
        return true
    }

    override suspend fun playAll(ids: List<Long>, shuffle: Boolean): Boolean {
        val held = library { graph.library.summaries(ids) }.orEmpty()
        val kept = ids.filter { it in held }
        if (kept.isEmpty()) return false
        onMain {
            graph.player.playAll(kept, shuffle)
            startPlayback()
        }
        return true
    }

    override suspend fun playPlaylist(id: Long, shuffle: Boolean): Boolean {
        if (playlists().none { it.id == id }) return false
        val ids = library { graph.library.inPlaylist(id).first() }.orEmpty().map { it.id }
        if (ids.isEmpty()) return false
        onMain {
            graph.player.playAll(ids, shuffle)
            startPlayback()
        }
        return true
    }

    override suspend fun transport(action: Transport) = onMain {
        val player = graph.player
        when (action) {
            Transport.TOGGLE -> if (player.state.value.status == PlaybackStatus.Playing) player.pause() else resume()
            Transport.PAUSE -> player.pause()
            Transport.RESUME -> resume()
            Transport.NEXT -> {
                player.next()
                startPlayback()
            }
            Transport.PREVIOUS -> {
                player.previous()
                startPlayback()
            }
            Transport.STOP -> player.stop()
        }
    }

    override suspend fun seek(ms: Long) = onMain {
        val duration = graph.player.state.value.piece?.durationMicros ?: return@onMain
        graph.player.seek((ms * MICROS_PER_MS).coerceIn(0L, duration))
    }

    override suspend fun setTempo(pct: Int) = onMain { graph.player.setTempo(pct) }

    override suspend fun setShuffle(on: Boolean) = onMain { graph.player.setShuffle(on) }

    override suspend fun setRepeat(mode: RepeatMode) = onMain { graph.player.setRepeat(mode) }

    override suspend fun queue(command: QueueCommand): Boolean {
        val ids = when (command) {
            is QueueCommand.PlayNext -> command.ids
            is QueueCommand.Add -> command.ids
            else -> emptyList()
        }
        val held = if (ids.isEmpty()) emptySet() else library { graph.library.summaries(ids) }.orEmpty().keys
        return onMain {
            val player = graph.player
            val uids = player.state.value.queue.uids
            when (command) {
                is QueueCommand.PlayNext -> ids.filter { it in held }.takeIf { it.isNotEmpty() }?.let {
                    if (player.playNext(it)) startPlayback()
                    true
                } ?: false
                is QueueCommand.Add -> ids.filter { it in held }.takeIf { it.isNotEmpty() }?.let {
                    if (player.addToQueue(it)) startPlayback()
                    true
                } ?: false
                is QueueCommand.Remove -> (command.uid in uids).also { if (it) player.removeFromQueue(command.uid) }
                is QueueCommand.Move -> (command.uid in uids).also { if (it) player.moveInQueue(command.uid, command.toIndex) }
                QueueCommand.Clear -> true.also { player.clearUpNext() }
                is QueueCommand.Skip -> (command.uid in uids).also {
                    if (it) {
                        player.skipToQueueEntry(command.uid)
                        startPlayback()
                    }
                }
            }
        }
    }

    override suspend fun channels(): List<WebChannel> {
        val summaries = graph.channelPools.summaries.value ?: return emptyList()
        val settings = graph.settings.value
        val portraits = portraits()
        val playing = onMain { graph.channelPlayer.playing }
        return summaries.map { card ->
            WebChannel(
                key = card.key,
                name = card.name,
                size = card.size,
                playable = card.playable,
                playing = card.key == playing,
                volume = settings.channelVolume(card.key),
                composers = card.composers.map { WebCardComposer(it.key, it.name, it.key in portraits, portraits[it.key] ?: 0) },
                genre = Genres.name(card.genre),
            )
        }
    }

    override suspend fun playChannel(key: String): ChannelStart {
        val card = graph.channelPools.summary(key) ?: return ChannelStart.UNKNOWN
        if (!card.playable) return ChannelStart.TOO_SMALL
        return onMain {
            if (graph.channelPlayer.play(key)) {
                startPlayback()
                ChannelStart.STARTED
            } else {
                ChannelStart.TOO_SMALL
            }
        }
    }

    override suspend fun stopChannel() = onMain {
        if (graph.channelPlayer.playing != null) graph.channelPlayer.stop()
    }

    override suspend fun setChannelVolume(key: String, pct: Int): Boolean {
        if (graph.channelPools.summary(key) == null) return false
        val volume = pct.coerceIn(0, MAX_VOLUME)
        graph.settingsRepository.setChannelVolume(key, volume)
        onMain { graph.channelPlayer.volumeChanged(key, volume) }
        return true
    }

    override suspend fun piano(): WebPiano = WebPiano(
        pianoOf(graph.pianoSettings.state.value),
        graph.pianoSettings.statusText.value,
        graph.pianoSettings.statusReading.value,
    )

    override suspend fun setPiano(name: String, wire: String) = onMain { graph.pianoSettings.set(name, wire) }

    override suspend fun pianoPreset(command: String) = onMain { graph.pianoSettings.preset(command) }

    override suspend fun pianoAction(action: PianoAction) = onMain { graph.pianoSettings.action(action) }

    /** The tablet's piano sound as the panel shows it (v1.8 — M25). */
    private fun tablet(): WebTablet = graph.tabletSound.state.value.let { t ->
        WebTablet(
            mode = when (t.mode) {
                TabletSoundMode.OFF -> "off"
                TabletSoundMode.WHEN_NOT_CONNECTED -> "whenNotConnected"
                TabletSoundMode.ALWAYS -> "always"
            },
            volume = t.volume,
            active = t.active,
            installed = t.installed,
        )
    }

    override suspend fun applySettings(change: SettingsChange) {
        val settings = graph.settingsRepository
        change.tabletVolume?.let { settings.setTabletVolume(it) }
        change.preRollMs?.let { settings.setPreRoll(it) }
        change.defaultTempoPct?.let { settings.setDefaultTempo(it) }
        change.transpose?.let { settings.setTranspose(it) }
        change.velocityPct?.let { settings.setVelocity(it) }
        change.foldOutOfRange?.let { settings.setFoldOutOfRange(it) }
        change.skipDrumChannel?.let { settings.setSkipDrumChannel(it) }
        change.dynamicRange?.let { settings.setDynamicRange(it) }
        change.velocityFloor?.let { settings.setVelocityFloor(it) }
        change.expression?.let { settings.setExpression(it) }
        change.restrikeMs?.let { settings.setRestrike(it) }
        // The View control's four (v1.13 — M32), as the tablet's own View menu sets them.
        change.noteDisplay?.let { settings.setNoteDisplay(it) }
        change.fingering?.let { settings.setFingering(it) }
        change.chordNames?.let { settings.setChordNames(it) }
        change.handColours?.let { settings.setHandColours(it) }
        // Album colours behind the player (v1.18 — M47b), as Piano › Display and Now playing's View menu set it.
        change.albumBackdrop?.let { settings.setAlbumBackdrop(it) }
        applyWebSettings(change)
    }

    /** A loose MIDI file: its piece goes into the standing playlist Uploads (DESIGN.md › v1.10.1, D2). */
    override suspend fun importMidi(name: String, bytes: ByteArray) {
        importInBackground(OpenedSource(listOf(ImportItem(name, name) { ByteArrayInputStream(bytes) }), batch = ImportBatch.Uploads))
    }

    override suspend fun importZip(name: String, file: File) {
        val zip = try {
            withContext(Dispatchers.IO) { ZipSource(file, deleteWhenClosed = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {   // not a zip, or one past the entry cap: it fails as an import that could not open
            file.delete()
            if (BuildConfig.DEBUG) Log.w(TAG, "An uploaded zip couldn't be opened", e) else Log.w(TAG, "An uploaded zip couldn't be opened")
            graph.reportFailedImport()
            return
        }
        // The zip's pieces go into its own playlist, named after its root folder or the zip (v1.10.1, D2).
        val source = try {
            OpenedSource(zip.items(), zip.readIndex(), zip.indexBase, zip::close, batch = ImportBatch.zip(name))
        } catch (e: CancellationException) {
            zip.close()
            throw e
        } catch (e: Exception) {
            zip.close()
            graph.reportFailedImport()
            return
        }
        importInBackground(source)
    }

    /** The built-in lists, Classical, each piece once; then Modern as last worked out ([guestModern]). Guests see titles and composers only, so no art is looked up. */
    override suspend fun catalogue(): List<CatalogueList> {
        val seen = HashSet<Long>()
        val builtIn = graph.builtIns.lists.mapNotNull { list ->
            val id = library { graph.library.builtInId(list.key) } ?: return@mapNotNull null
            val pieces = library { graph.library.inPlaylist(id).first() }.orEmpty()
                .filter { seen.add(it.id) }
                .map { it.toGuest() }
            CatalogueList(list.key, list.name, pieces, CLASSICAL).takeIf { pieces.isNotEmpty() }
        }
        val modern = guestModern.value.takeIf { it.isNotEmpty() }?.let { CatalogueList(MODERN_LIST, MODERN_LIST_NAME, it, MODERN) }
        return builtIn + listOfNotNull(modern)
    }

    /** On offer: on the Modern list as guests were last shown it, or in one of the built-in lists ([catalogue]'s two sources). */
    override suspend fun offered(pieceId: Long): WebPiece? {
        guestModern.value.firstOrNull { it.id == pieceId }?.let { return it }
        val piece = library { graph.library.piece(pieceId) } ?: return null
        val inLists = library { graph.library.playlistIdsOf(pieceId).first() }.orEmpty()
        if (inLists.isEmpty()) return null
        val builtIn = graph.builtIns.lists.mapNotNull { library { graph.library.builtInId(it.key) } }
        return if (inLists.any { it in builtIn }) piece.toGuest() else null
    }

    override suspend fun guestSettings(): GuestSettings = guests()

    override suspend fun queueRequested(pieceId: Long): List<Long> = onMain {
        val player = graph.player
        val before = player.state.value.queue.uids.toHashSet()
        if (player.addToQueue(listOf(pieceId))) startPlayback()
        player.state.value.queue.uids.filter { it !in before }
    }

    override suspend fun pinHash(): PinHash? = graph.settingsRepository.webPin()?.let { PinHash.restore(it.salt, it.hash) }

    override suspend fun schedules(): WebSchedules {
        val schedules = graph.schedules
        return WebSchedules(
            schedules = schedules.rowsNow().map { WebSchedule(it.entry, it.name, it.whenLine, it.whatLine) },
            next = schedules.nextNow()?.line,
            last = schedules.lastNow(),
            exactAlarms = schedules.exactAllowed.value,
        )
    }

    override suspend fun scheduleTarget(kind: ScheduleKind, target: String): String? = graph.schedules.nameOf(kind, target)

    override suspend fun saveSchedule(draft: ScheduleDraft): SaveResult = graph.schedules.save(draft)

    override suspend fun deleteSchedule(id: Long): Boolean {
        if (graph.schedules.repository.get(id) == null) return false
        graph.schedules.delete(id)
        return true
    }

    override suspend fun studio(): WebStudio {
        val studio = graph.studio
        studio.availability.check()
        val support = studio.availability.support.value
        val installed = studio.models.installed.value
        val jobs = studio.jobs.jobs.value
        val undecided = studio.review.undecided.value
        val discarded = studio.review.discardedNow.value
        return WebStudio(
            // While the first check runs Studio reads as available; a job still meets the check before it starts.
            available = support == StudioSupport.Available || support == StudioSupport.Checking,
            reason = StudioCopy.unsupported(support)?.let { "$it." },
            models = ModelCatalogue.all.map { model ->
                val download = jobs.lastOrNull { it.kind == JobKind.Download && it.model == model.name && !it.state.finished }
                val here = model.name in installed
                WebModel(
                    name = model.name,
                    title = model.title,
                    sizeBytes = model.sizeBytes,
                    licence = model.licenceLabel,
                    installed = here,
                    line = download?.let { StudioCopy.jobLine(it) } ?: if (here) "Installed · ${StudioCopy.modelLine(model)}" else StudioCopy.modelLine(model),
                    progress = download?.takeIf { it.state == JobState.Running }?.progress,
                )
            },
            jobs = jobs.asReversed().map { job ->
                WebStudioJob(
                    id = job.id,
                    kind = job.kind.name.lowercase(Locale.ROOT),
                    name = StudioCopy.jobName(job),
                    state = job.state.name.lowercase(Locale.ROOT),
                    line = StudioCopy.jobLine(job, undecided, discarded),
                    progress = job.progress.takeIf { job.state == JobState.Running },
                    title = job.title,
                    step = job.step.name.lowercase(Locale.ROOT),
                    steps = job.steps.map { it.name.lowercase(Locale.ROOT) },
                    tokens = job.tokens,
                    musicMs = job.musicMs,
                    targetMs = job.targetMs,
                    etaMs = job.etaMs,
                    notes = job.notes,
                    turn = job.turnId,
                )
            },
        )
    }

    override suspend fun transcribeUpload(name: String, file: File): StudioUpload {
        val studio = graph.studio
        studio.availability.check()
        val support = studio.availability.support.first { it != StudioSupport.Checking }
        if (support != StudioSupport.Available) {
            file.delete()
            return StudioUpload.Refused(StudioCopy.unsupported(support)?.let { "$it." } ?: StudioFailures.UNAVAILABLE)
        }
        val job = withContext(Dispatchers.Main) { studio.transcribe(AudioSource.Local(file), name) }
        return StudioUpload.Queued(job.id)
    }

    override suspend fun composeSeed(pieceId: Long?): SeedChoice? = graph.studio.seedChoice(pieceId)

    override suspend fun compose(order: ComposeOrder): StudioCompose {
        val studio = graph.studio
        studio.availability.check()
        val support = studio.availability.support.first { it != StudioSupport.Checking }
        if (support != StudioSupport.Available) {
            return StudioCompose.Refused(StudioCopy.unsupported(support)?.let { "$it." } ?: StudioFailures.UNAVAILABLE)
        }
        val chosen = order.pieceId
        val piece = (if (chosen != null) graph.library.piece(chosen) else graph.library.seedPiece()) ?: return StudioCompose.NoSuchPiece
        val job = withContext(Dispatchers.Main) { studio.compose(order.copy(pieceId = piece.id), piece.title) }
        return StudioCompose.Queued(job.id)
    }

    override suspend fun cancelStudioJob(id: Long): Boolean = withContext(Dispatchers.Main) {
        val job = graph.studio.jobs.get(id)
        if (job == null || job.state.finished) {
            false
        } else {
            graph.studio.cancel(id)
            true
        }
    }

    /** In the app's scope, so an import outlives the request that sent it; the built-in lists and artwork follow, as after the app's own imports. */
    private fun importInBackground(source: OpenedSource) {
        graph.appScope.launch {
            val result = try {
                graph.importer.importOpened(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "An upload's import stopped", e) else Log.w(TAG, "An upload's import stopped")
                null
            }
            if (result != null && result.piecesChanged) {
                graph.refreshBuiltIns()
                if (graph.settingsRepository.settings.first().fetchArtworkAutomatically) graph.artwork.requestDue(force = false)
            }
        }
    }

    private fun resume() {
        graph.player.resume()
        startPlayback()
    }

    /**
     * The playback service holds the media notification and the wake lock; Android lets an app start
     * it from the background only in some cases (the device owner, no battery optimisation), so a
     * refusal is logged and playback goes on in the app's process.
     */
    private fun startPlayback() {
        try {
            PlaybackService.start(app)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Playback from the web panel runs without its notification: Android refused the service from the background")
        } catch (e: SecurityException) {
            Log.w(TAG, "Playback from the web panel runs without its notification")
        }
    }

    private suspend fun portrait(composerKey: String, size: ArtSize): WebImage? {
        if (composerKey.isEmpty()) return null
        return image(ArtworkEntity.forComposer(composerKey), size)
    }

    /** The picture of artwork row [key] at [size], encoded once per version; null when it has none. */
    private suspend fun image(key: String, size: ArtSize): WebImage? {
        val row = withTimeoutOrNull(READ_TIMEOUT_MS) { graph.artwork.artwork(key).first() } ?: return null
        if (row.imagePath == null) return null
        val cacheKey = "${row.key}|${row.fetchedAt}|$size"
        encoded.get(cacheKey)?.let { return it }
        val bitmap = graph.artwork.bitmap(row, size) ?: return null
        return withContext(Dispatchers.Default) {
            val image = if (bitmap.hasAlpha()) {
                WebImage(compress(bitmap, Bitmap.CompressFormat.PNG), PNG)
            } else {
                WebImage(compress(bitmap, Bitmap.CompressFormat.JPEG), JPEG)
            }
            image.also { encoded.put(cacheKey, it) }
        }
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(format, JPEG_QUALITY, out)
            out.toByteArray()
        }

    /**
     * The composers with portraits, with when each was kept (v1.17 — M45: the art's version), or none after a moment (the
     * table unreadable): every piece then shows its roll card.
     */
    private suspend fun portraits(): Map<String, Long> = withTimeoutOrNull(READ_TIMEOUT_MS) { graph.artwork.portraitComposers() }.orEmpty()

    /** The pieces with a cover of their own (Studio's), with when it was drawn (v1.12 — M30). */
    private suspend fun covers(): Map<Long, Long> = withTimeoutOrNull(READ_TIMEOUT_MS) { graph.artwork.pieceCovers() }.orEmpty()

    private fun channelName(key: String): String? =
        graph.channelPools.summary(key)?.name ?: graph.channels.firstOrNull { it.key == key }?.name

    private fun PieceEntity.toWeb(portraits: Map<String, Long>, covers: Map<Long, Long>): WebPiece =
        WebPiece(
            id, title, composer, composerKey, durationMs, composerShort = composerShort, favorite = favorite, portrait = composerKey in portraits,
            cover = id in covers, artVersion = covers[id] ?: portraits[composerKey] ?: 0, genre = Genres.name(genre),
        )

    /** A piece as guests are offered it (v1.14 — M37): what its row says, nothing looked up. */
    private fun PieceEntity.toGuest(): WebPiece =
        WebPiece(id, title, composer, composerKey, durationMs, composerShort = composerShort, genre = Genres.name(genre))

    /** A library read; null when it fails (the library unreadable), which the panel shows as an empty list or a 404. */
    private suspend fun <T> library(read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "The web panel couldn't read the library")
        null
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    companion object {
        private const val TAG = "WebPanel"

        /** `cacheDir/web`: uploads while they are read, and the server's temporary files (swept at start, `ImportLimits.sweepStale`). */
        const val UPLOAD_DIR = ImportLimits.WEB_DIR

        private const val MICROS_PER_MS = 1_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_VOLUME = 100
        private const val READ_TIMEOUT_MS = 2_000L
        private const val JPEG_QUALITY = 85
        private const val ART_CACHE_BYTES = 8 * 1024 * 1024
        private const val PNG = "image/png"
        private const val JPEG = "image/jpeg"

        /** How long the library must be quiet before the guests' Modern list is worked out again, as the channels wait. */
        private const val SETTLE_MS = 500L

        /** The guests' lists' genres, and the Modern list's key and name (v1.14 — M37). */
        private val CLASSICAL = Genres.name(Genres.CLASSICAL)
        private val MODERN = Genres.name(Genres.MODERN)
        private const val MODERN_LIST = "modern"
        private const val MODERN_LIST_NAME = "Modern"

        /** A link state's name on the wire. */
        fun linkOf(state: LinkState): WebLink = when (state) {
            is LinkState.Connected -> WebLink("connected", state.name)
            LinkState.Disconnected -> WebLink("disconnected")
            LinkState.Scanning -> WebLink("scanning")
            LinkState.Connecting -> WebLink("connecting")
            is LinkState.Reconnecting -> WebLink("reconnecting")
            is LinkState.Error -> WebLink("error")
        }

        /** What the piano reported, as the panel reads it. */
        fun pianoOf(state: PianoState): WebPianoState = when (state) {
            PianoState.Unknown -> WebPianoState("unknown")
            PianoState.Unsupported -> WebPianoState("unsupported")
            is PianoState.Ready -> WebPianoState("ready", state.values, state.facts, state.lastError, state.errorAbout)
        }

    }

    // ---- The System page (v1.18 — M46) -------------------------------------------------------

    /** The piano's live facts at most once in 10 s, whichever panel, listener or the tablet's own System page asks (firmware/docs/BLE_DIAG.md). */
    private val refreshFloor = graph.factsFloor

    override suspend fun system(): WebSystem {
        val reading = withContext(Dispatchers.IO) { graph.systemProbe.read() }
        val link = graph.pianoLink.state.value
        val piano = graph.pianoSettings.state.value
        val now = System.currentTimeMillis()
        // What runs, gathered as the tablet's own System page gathers it (AppGraph.runningInputs, v1.18 — M50).
        val inputs = graph.runningInputs(now)
        val counts = library { graph.library.coverCounts() }
        return WebSystem(
            at = now,
            reading = reading,
            piano = WebSystemPiano(
                link = linkOf(link),
                mtu = (link as? LinkState.Connected)?.mtu?.takeIf { it > 0 },
                state = pianoOf(piano).state,
                facts = (piano as? PianoState.Ready)?.facts,
                factsAt = graph.pianoSettings.factsAt.value,
            ),
            running = dev.stevenjin.stevenpiano.diag.RunningNow.of(inputs),
            web = WebSystemWeb(
                inputs.web.sessions, inputs.web.sockets, inputs.web.guests,
                WebRelay(dev.stevenjin.stevenpiano.diag.RunningNow.relayWord(inputs.relay.status), inputs.relay.answered, inputs.relay.refused),
            ),
            covers = coversOf(counts, inputs.covers.blockedUntil),
        )
    }

    /** The covers' counts by their lookup's status (all null when the library couldn't be read), and Apple's stop. */
    private fun coversOf(counts: List<dev.stevenjin.stevenpiano.data.db.CoverCount>?, blockedUntil: Long?): WebCovers {
        fun count(status: String?): Int? = counts?.filter { it.status == status }?.sumOf { it.count }
        return WebCovers(
            found = count(dev.stevenjin.stevenpiano.data.db.ArtworkStatus.OK.name),
            missing = count(dev.stevenjin.stevenpiano.data.db.ArtworkStatus.NOT_FOUND.name),
            failed = count(dev.stevenjin.stevenpiano.data.db.ArtworkStatus.FAILED.name),
            waiting = count(null),
            blockedUntil = blockedUntil,
        )
    }

    override suspend fun systemHistory(): List<dev.stevenjin.stevenpiano.diag.SystemSample> = graph.systemHistory.snapshot()

    /** At most once in 10 s ([refreshFloor]), on the main thread where the piano's settings live; nothing during a firmware update. */
    override suspend fun refreshPiano(): Boolean = refreshFloor.take() && onMain { graph.pianoSettings.refreshFacts() }

    override suspend fun systemTool(tool: SystemTool): SystemToolResult = when (tool) {
        SystemTool.COVERS -> {
            // Queued in the app's scope: finding what to look again for reads every cover's row, which may take a moment.
            graph.appScope.launch { graph.artwork.lookAgainForCovers() }
            SystemToolResult.DONE
        }
        SystemTool.RECONNECT -> if (onMain { graph.reconnectPiano() }) SystemToolResult.DONE else SystemToolResult.BUSY
    }

    override suspend fun diagnostics(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            graph.diagnostics.exportBytes()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "The diagnostics couldn't be gathered for the web panel")
            null
        }
    }

    // ---- The Settings page (v1.18 — M47b) ---------------------------------------------------------------

    /**
     * The Playback page's settings as the app holds them, and what the piano says of them: Full power as the line under
     * Velocity reads it (`PlaybackCopy.fullPower`: the piano ready and its `fullpower` not 0), its repeat period as Re-strike
     * time's Auto takes it (`PlaybackCopy.pianoRepeatMs`); both null without a ready piano.
     */
    override suspend fun settings(): WebSettings {
        val piano = graph.pianoSettings.state.value
        return WebSettings(
            settings = graph.settings.value,
            fullPower = (piano as? PianoState.Ready)?.values?.get(PIANO_FULL_POWER)?.trim()?.let { it != "0" },
            repeatMs = dev.stevenjin.stevenpiano.ui.PlaybackCopy.pianoRepeatMs(piano),
        )
    }
}

/** The piano's Full power setting, by its firmware name (v1.18 — M47b: the Settings page's line under Velocity). */
private const val PIANO_FULL_POWER = "fullpower"
