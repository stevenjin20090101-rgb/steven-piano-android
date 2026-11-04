// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.util.Log
import dev.stevenjin.stevenpiano.AppGraph.Companion.MODELS_DIR
import dev.stevenjin.stevenpiano.AppGraph.Companion.debugLog
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.record.Recorder
import dev.stevenjin.stevenpiano.record.RecordingPieces
import dev.stevenjin.stevenpiano.record.RecordingSession
import dev.stevenjin.stevenpiano.record.RecordingStore
import dev.stevenjin.stevenpiano.service.StudioService
import dev.stevenjin.stevenpiano.studio.AppStudioLibrary
import dev.stevenjin.stevenpiano.studio.AudioDecoder
import dev.stevenjin.stevenpiano.studio.AudioSource
import dev.stevenjin.stevenpiano.studio.Generations
import dev.stevenjin.stevenpiano.studio.LibrarySeeds
import dev.stevenjin.stevenpiano.studio.ModelInstaller
import dev.stevenjin.stevenpiano.studio.ModelStore
import dev.stevenjin.stevenpiano.studio.ReviewPlayer
import dev.stevenjin.stevenpiano.studio.RoomGenerations
import dev.stevenjin.stevenpiano.studio.RoomReview
import dev.stevenjin.stevenpiano.studio.StoredCalibration
import dev.stevenjin.stevenpiano.studio.StoredWaiting
import dev.stevenjin.stevenpiano.studio.Studio
import dev.stevenjin.stevenpiano.studio.StudioAvailability
import dev.stevenjin.stevenpiano.studio.StudioPieces
import dev.stevenjin.stevenpiano.studio.StudioReview
import dev.stevenjin.stevenpiano.update.HttpUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * Studio's part of [AppGraph]: its history, its jobs on their own thread, what a transcription or a take leaves for Keep or
 * Discard, and the recorder. The graph makes it once and everything else reaches it through the graph's members; what it
 * holds is lazy or asked for.
 */
internal class StudioWiring(private val graph: AppGraph, private val app: Application) {
    private val appScope = graph.appScope

    /** Studio's history (v1.12 — M30): the turns of its conversation, and the one record of its pieces' Keep or Discard. */
    val studioHistory: Generations by lazy { RoomGenerations(graph.database.generations()) }

    /**
     * Studio (its own tab since v1.12 — M30; a Piano page from v1.7 — M23): the models (downloaded on demand from the release tagged
     * `models`, pinned by hash; on the emulator in debug builds, a server on the Mac: [Emulator.modelsSource]), the
     * jobs (one at a time on [studioThread], a partial wake lock around each, the foreground service
     * following them), and what a transcription leaves: a piece that waits for Keep or Discard.
     */
    val studio: Studio by lazy {
        val source = Emulator.modelsSource() ?: UpdateSource.models
        val server = HttpUpdateServer(source, log = debugLog(STUDIO_TAG), fileAccept = HttpUpdateServer.BINARY_ACCEPT)
        val models = ModelStore(File(app.filesDir, MODELS_DIR))
        val library = studioLibrary
        Studio(
            scope = appScope,
            availability = StudioAvailability.of(app, appScope),
            models = models,
            installer = ModelInstaller(source, server, models, VerifiedDownloader(server)),
            reader = AudioDecoder(app.contentResolver),
            pieces = StudioPieces(library),
            review = studioReview,
            worker = studioThread,
            online = graph.network::isOnline,
            onBusy = { StudioService.start(app) },
            awake = ::studioAwake,
            release = ::releaseRecording,
            peakKb = ::peakResidentKb,
            log = { Log.i(STUDIO_TAG, it) },
            trail = LinkLog.shared::add,
            seeds = LibrarySeeds(graph.library),
            generations = studioHistory,
            calibration = StoredCalibration(app),
            kiosk = { graph.settings.value.kioskEnabled },
        )
    }

    /**
     * Where Studio's pieces and the tablet's recordings go into the library (v1.7 — M23; hoisted for recordings in
     * v1.11 — M29): through the importer, described on their sheet, discarded as the Library's Delete does.
     */
    val studioLibrary: AppStudioLibrary by lazy { AppStudioLibrary(graph) }

    /** Keep or Discard after a first listen, for Studio's pieces and the tablet's recordings (hoisted in v1.11 — M29). */
    val studioReview: StudioReview by lazy {
        StudioReview(
            RoomReview(studioHistory, StoredWaiting(app)),
            object : ReviewPlayer {
                override val state = graph.player.state

                override fun positionMicrosNow(): Long = graph.player.positionMicrosNow()
            },
            studioLibrary,
            appScope,
        )
    }

    /**
     * The recorder (v1.11 — M29): what the Keys screen and the MIDI keyboard play, captured before the router; it hears
     * the keyboard whether Live is on or not.
     */
    val recorder: Recorder by lazy { Recorder().also { graph.keyboard.listen(it) } }

    /** A take from the Record control to the sheet; saved into Recordings with a cover of its own, waiting for Keep or Discard. */
    val recording: RecordingSession by lazy {
        val store = object : RecordingStore {
            override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long? = studioLibrary.add(fileName, bytes, title, composer)

            override suspend fun describe(pieceId: Long, description: String) = studioLibrary.describe(pieceId, description)

            override suspend fun addToRecordings(pieceId: Long) = graph.library.addRecording(pieceId)

            override suspend fun recordings(): List<Long> = graph.library.recordings()

            override suspend fun markUndecided(pieceId: Long) = studioReview.made(pieceId)

            override suspend fun undecided(): Set<Long> = studioReview.undecided.value

            override suspend fun discard(pieceId: Long) = studioReview.discard(pieceId)

            override suspend fun setCover(pieceId: Long, png: ByteArray): Boolean = studioLibrary.setCover(pieceId, png)
        }
        RecordingSession(
            recorder,
            RecordingPieces(store, File(app.filesDir, RECORDINGS_DIR), kiosk = { graph.settings.value.kioskEnabled }, log = LinkLog::warn),
            appScope,
            log = LinkLog::warn,
        )
    }

    /** Studio's jobs' thread: one, at background priority, so the player's scheduler (urgent audio) keeps its timing. */
    private val studioThread by lazy {
        Executors.newSingleThreadExecutor { work ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                work.run()
            }, "steven-piano-studio").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    private val studioWakeLock by lazy {
        app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StevenPiano:studio").apply { setReferenceCounted(false) }
    }

    /** Studio's jobs keep the device awake while one runs (30 minutes at most a job: the longest recording takes about 7). */
    private fun studioAwake(on: Boolean) {
        if (on) studioWakeLock.acquire(STUDIO_WAKE_MS) else if (studioWakeLock.isHeld) studioWakeLock.release()
    }

    /** A transcription is over: a web upload's file goes, a picked document's read grant is given back. */
    private fun releaseRecording(source: AudioSource) {
        when (source) {
            is AudioSource.Local -> source.file.delete()
            is AudioSource.Document -> runCatching {
                app.contentResolver.releasePersistableUriPermission(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** The process's peak resident set (VmHWM), in kB; -1 when it can't be read. For Studio's log line. */
    private fun peakResidentKb(): Long = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()
    }.getOrNull() ?: -1L

    private companion object {
        const val STUDIO_TAG = "Studio"
        const val RECORDINGS_DIR = "recordings"
        const val STUDIO_WAKE_MS = 30 * 60_000L
    }
}
