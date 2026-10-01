// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.BuildConfig
import dev.stevenjin.stevenpiano.MainActivity
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.ImportCopy
import dev.stevenjin.stevenpiano.ui.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Imports run here: a dataSync foreground service with a progress notification (channel
 * "imports"), so a whole folder keeps going with the screen off. Access to the chosen files
 * belongs to this process; picker grants are made persistable for the length of the import
 * and handed back after. Imports queue one after another; the service ends with the last. An
 * import that brought pieces in starts the artwork service before this one stops, when the person
 * lets artwork arrive by itself. When Android's time for data sync runs out ([onTimeout]) the
 * imports stop where they are (what was saved stays) and the service ends at once. An import that
 * brought pieces in refreshes the built-in playlists before the artwork service starts.
 */
class ImportService : Service() {
    private val scope = MainScope()
    private var running = 0
    private var progressJob: Job? = null
    private val imports = mutableListOf<Job>()
    private var lastPostedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, ID, notificationFor(graph.importProgress.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val request = intent?.let(::requestOf)
        if (request == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        running++
        if (progressJob == null) progressJob = scope.launch { graph.importProgress.collect(::post) }
        imports += scope.launch {
            try {
                val result = try {
                    graph.importer.import(this@ImportService, request.source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {   // the importer counts every file's own failure; this is anything else
                    stopped(e)
                    null
                } catch (e: OutOfMemoryError) {
                    stopped(e)
                    null
                }
                if (result != null && result.piecesChanged) {
                    // The built-in playlists take in what arrived (while the foreground holds the process).
                    graph.refreshBuiltIns()
                    // Started now, while this service still holds the foreground: Android 12+ refuses
                    // a foreground service started from the background.
                    if (graph.settingsRepository.settings.first().fetchArtworkAutomatically) ArtworkService.start(this@ImportService, force = false)
                }
            } finally {
                if (request.persisted) request.uris.forEach { uri -> runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                running--
                stopIfIdle()
            }
        }
        imports.removeAll { it.isCompleted }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) = stopForTimeout()

    /** Android 15's six hours a day for data sync ran out: the imports stop, and so does the service, now. */
    override fun onTimeout(startId: Int, fgsType: Int) = stopForTimeout()

    private fun stopForTimeout() {
        imports.forEach { it.cancel() }   // each releases its grants as it ends
        imports.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** An import that failed as a whole: logged (in debug builds with its message, which may name a file), never a crash. */
    private fun stopped(e: Throwable) {
        if (BuildConfig.DEBUG) Log.w(TAG, "An import stopped", e) else Log.w(TAG, "An import stopped with an error")
    }

    private fun stopIfIdle() {
        if (running > 0) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** At most a few updates a second: Android drops notifications that change faster. */
    private fun post(progress: ImportProgress) {
        val now = SystemClock.elapsedRealtime()
        if (running == 0 || now - lastPostedAt < UPDATE_MS) return
        lastPostedAt = now
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(ID, notificationFor(progress))
    }

    private fun notificationFor(progress: ImportProgress): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piano)
            .setContentTitle("Importing MIDI files")
            .setContentText(ImportCopy.running(progress))
            .setProgress(progress.total, progress.done, progress.total == 0)
            .setContentIntent(openLibrary())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun openLibrary(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, Route.Library.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private class Request(val source: ImportSource, val uris: List<Uri>, val persisted: Boolean)

    private fun requestOf(intent: Intent): Request? {
        val clip = intent.clipData ?: return null
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isEmpty()) return null
        val source = when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_FOLDER -> ImportSource.Tree(uris.first())
            KIND_ZIP -> ImportSource.Zip(uris.first())
            else -> ImportSource.Uris(uris)
        }
        return Request(source, uris, intent.getBooleanExtra(EXTRA_PERSISTED, false))
    }

    companion object {
        private const val TAG = "ImportService"
        const val CHANNEL_ID = "imports"
        private const val ID = 2
        private const val UPDATE_MS = 400L
        private const val EXTRA_KIND = "dev.stevenjin.stevenpiano.extra.KIND"
        private const val EXTRA_PERSISTED = "dev.stevenjin.stevenpiano.extra.PERSISTED"
        private const val KIND_FILES = "files"
        private const val KIND_FOLDER = "folder"
        private const val KIND_ZIP = "zip"

        fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Imports")
                .setDescription("Progress while MIDI files come into the library.")
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /**
         * Starts importing [source]. [fromPicker]: the files came from the system picker, whose
         * grants can be held for the whole import even if the screen that chose them closes.
         */
        fun start(context: Context, source: ImportSource, fromPicker: Boolean) {
            val (kind, uris) = when (source) {
                is ImportSource.Uris -> KIND_FILES to source.uris
                is ImportSource.Tree -> KIND_FOLDER to listOf(source.treeUri)
                is ImportSource.Zip -> KIND_ZIP to listOf(source.uri)
                // v1.10 — M27: a zip the app saved itself (Steven's library) is imported by the service that saved it,
                // LibraryService, which already holds the foreground (Android may refuse a second one from the background).
                is ImportSource.LocalZip -> throw IllegalArgumentException("A zip the app saved is imported by LibraryService")
            }
            if (uris.isEmpty()) return
            if (fromPicker) {
                uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            }
            val intent = Intent(context, ImportService::class.java)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_PERSISTED, fromPicker)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
