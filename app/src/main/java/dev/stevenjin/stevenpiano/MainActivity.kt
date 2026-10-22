// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.BadParcelableException
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.record.TakeEnd
import dev.stevenjin.stevenpiano.service.ImportService
import dev.stevenjin.stevenpiano.ui.AppFrame
import dev.stevenjin.stevenpiano.ui.PianoNavHost
import dev.stevenjin.stevenpiano.ui.Route
import dev.stevenjin.stevenpiano.ui.components.LocalHazeState
import dev.stevenjin.stevenpiano.ui.components.RailBanner
import dev.stevenjin.stevenpiano.ui.components.rememberHazeState
import dev.stevenjin.stevenpiano.ui.screens.library.ShareSheet
import dev.stevenjin.stevenpiano.ui.theme.PianoTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The one activity: edge to edge, transparent system bars, the four destinations in a frame the
 * window's width class chooses. MIDI files from the Library's pickers are imported by the import
 * service; files that arrive by "Open with" or the share sheet are imported only once the person
 * says Add in [ShareSheet] (content URIs only, at most [SharedFiles.MAX_SHARED] at a time; when the app may not read them, the
 * Library says so). The playback notification opens Now playing. Rotation and
 * resizing are handled here as configuration changes (the manifest's configChanges): the frame
 * recomputes from the new configuration and nothing is recreated, so nothing may rely on
 * recreation to refresh. After the first frame, and for as long as the activity is started, the
 * app looks for its own updates (at once, then daily; see [AppGraph.runUpdateSchedule]). Where the
 * display offers more than 60 frames a second the window asks for 60 ([capRefreshRate]). In kiosk
 * mode (Piano › Kiosk) the activity, while in front, locks the screen to the app whenever the kiosk
 * wants it and lets go when it doesn't ([followKiosk]); it is also the tablet's home screen then,
 * through the manifest's `KioskHome` alias.
 */
class MainActivity : ComponentActivity() {
    private var requestedTab by mutableStateOf<Route?>(null)

    /** Files another app sent, waiting for the person's Add or Cancel. */
    private var pendingShare by mutableStateOf<List<Uri>>(emptyList())

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        capRefreshRate()
        if (savedInstanceState == null) {
            route(intent)
        } else {
            pendingShare = BundleCompat.getParcelableArrayList(savedInstanceState, STATE_SHARED, Uri::class.java).orEmpty()
        }
        setContent {
            val size = calculateWindowSizeClass(this)
            val frame = remember(size) { AppFrame(size.widthSizeClass, size.heightSizeClass) }
            // Light, dark, or as the system says (Piano › Display › Appearance); nothing is drawn until it is known.
            val appearance by graph.appearance.collectAsStateWithLifecycle()
            val dark = appearance?.dark(isSystemInDarkTheme()) ?: return@setContent
            LaunchedEffect(dark) { systemBarsFor(dark) }
            PianoTheme(darkTheme = dark) {
                // The navigation content, the glass's source: the share sheet over it blurs it too (DESIGN.md › v1.9).
                val content = rememberHazeState()
                Box(Modifier.fillMaxSize()) {
                    PianoNavHost(frame, requestedTab, onTabShown = { requestedTab = null }, content = content) { source ->
                        ImportService.start(this@MainActivity, source, fromPicker = true)
                    }
                    // v1.26 — M60: the 24 V rail's words ("Waking the piano…", "24 V off", a failure), over every screen.
                    RailBanner(Modifier.align(Alignment.TopCenter))
                }
                if (pendingShare.isNotEmpty()) {
                    CompositionLocalProvider(LocalHazeState provides content) {
                        ShareSheet(pendingShare.size, onAdd = ::addShared, onCancel = { pendingShare = emptyList() })
                    }
                }
            }
        }
        lifecycleScope.launch {
            awaitFrame()   // nothing about updates holds up the first frame
            repeatOnLifecycle(Lifecycle.State.STARTED) { graph.runUpdateSchedule() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { graph.kiosk.lockWanted.collect { followKiosk(it) } }
        }
    }

    /**
     * Kiosk mode, while the activity is in front (lock task needs a task in the foreground): the
     * screen locked to the app when the kiosk wants it and Android permits it (the lock task list
     * holds this package: never before, or Android would ask the person to pin the screen instead),
     * let go otherwise ("Unlock for now", Turn kiosk off). Only the device owner's own lock is ever
     * let go, never a screen the person pinned themselves.
     */
    private fun followKiosk(wanted: Boolean) {
        val mode = getSystemService(ActivityManager::class.java)?.lockTaskModeState ?: return
        try {
            if (wanted && mode == ActivityManager.LOCK_TASK_MODE_NONE && graph.kiosk.lockTaskPermitted()) {
                startLockTask()
            } else if (!wanted && mode == ActivityManager.LOCK_TASK_MODE_LOCKED) {
                stopLockTask()
            }
        } catch (e: RuntimeException) {   // not in front after all, or the list changed meanwhile
            Log.w(TAG, "Kiosk: lock task not changed (${e.javaClass.simpleName})")
        }
    }

    /**
     * The status and navigation bars stay transparent over the app, with their icons light on the
     * camera body and dark on the paper, as the app appears (which may not be as the system does).
     */
    private fun systemBarsFor(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        )
    }

    /**
     * The glass re-blurs what lies under it with every frame the roll draws (DESIGN.md › v1.5 — M16).
     * Sixty frames a second are enough for the roll and the score and halve that work on a 90 or
     * 120 Hz panel, so where the display offers more the window asks for 60 (a preference the system
     * may weigh against others). Only the drawing slows: the scheduler thread times the piano on its
     * own clock, whatever the display does.
     */
    private fun capRefreshRate() {
        val display = runCatching { ContextCompat.getDisplayOrDefault(this) }.getOrNull() ?: return
        if (display.supportedModes.none { it.refreshRate > MAX_REFRESH_RATE + 1f }) return
        window.attributes = window.attributes.apply { preferredRefreshRate = MAX_REFRESH_RATE }
    }

    /** Files still waiting for Add or Cancel survive the activity being recreated (a theme change). */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (pendingShare.isNotEmpty()) outState.putParcelableArrayList(STATE_SHARED, ArrayList(pendingShare))
    }

    /**
     * In the foreground a foreground service may start: composers never looked up are fetched now, and
     * the web panel, if on, listens. Opened again after "Unlock for now", the kiosk locks again; and
     * the adb way back (`debug.stevenpiano.releaseowner`) is looked for, as on every new intent. The
     * microphone may hear for the web panel from now (v1.23 — M55), and only until the activity stops.
     */
    override fun onStart() {
        super.onStart()
        graph.fetchArtworkIfDue()
        graph.startWebIfOn(this)
        graph.kiosk.appOpened()   // back after "Unlock for now": locked again
        graph.releaseOwnerIfAsked()   // the adb way back, which `am start` reaches (DeviceOwnerRelease)
        graph.remote.listening.foreground(true)   // v1.23 — M55: the microphone may hear only while the app is in front
    }

    /**
     * In the background nothing may hold a key down: the Keys screen's keys and sustain let go, and so do a
     * MIDI keyboard's (v1.11 — M29: Live closes until the Keys tab is in front again); a take running ends and
     * is saved, waiting for Keep or Discard.
     * Not when the activity only stops to be recreated for a configuration change (a rotation
     * never gets here: the activity handles it without stopping); the Keys screen lets go of
     * whatever its own window held as that window goes. Left after "Unlock for now", the next
     * start locks the kiosk again.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            graph.player.silenceLive()
            graph.liveThru.setOnScreen(false)   // v1.11 — M29: the keyboard's keys let go too; Live waits for the Keys tab
            if (graph.recording.recording) graph.recording.stop(TakeEnd.AppLeft)   // and a take ends, saved
            graph.kiosk.appLeft()
            graph.remote.listening.foreground(false)   // v1.23 — M55: and the microphone stops (Android would hear only silence)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
        graph.releaseOwnerIfAsked()
    }

    /**
     * Shared files wait on the Library for the person's Add or Cancel ([pendingShare]); a newer
     * share replaces them. Any app can send this activity an intent, so nothing in one may crash it:
     * a malformed intent (extras that cannot be unparcelled, a provider that throws) is logged and
     * dropped.
     */
    private fun route(intent: Intent) {
        try {
            Emulator.onIntent(graph, intent)   // adb's hooks, debug builds on the emulator only
            val shared = sharedMidi(intent)
            if (shared.isNotEmpty()) {
                pendingShare = shared
                requestedTab = Route.Library
            } else {
                Route.of(stringExtra(intent, EXTRA_TAB))?.let { requestedTab = it }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Ignored an intent the app could not read")
        }
    }

    /**
     * The person said Add: the files go to the import service and the Library shows their
     * progress. A file the sender gave no access to cannot go (Android refuses the hand-over): the
     * Library says it couldn't be read instead of the app crashing.
     */
    private fun addShared() {
        val shared = pendingShare
        pendingShare = emptyList()
        try {
            when (SharedFiles.hand(shared) { ImportService.start(this, ImportSource.Uris(it), fromPicker = false) }) {
                SharedFiles.Outcome.Importing -> requestedTab = Route.Library
                SharedFiles.Outcome.Unreadable -> {
                    graph.reportUnreadableShare(shared.size)
                    requestedTab = Route.Library
                }
                SharedFiles.Outcome.None -> Unit
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't start importing shared files")
        }
    }

    companion object {
        /** A [Route] path to open at, e.g. from the playback notification. */
        const val EXTRA_TAB = "dev.stevenjin.stevenpiano.TAB"
        private const val TAG = "MainActivity"
        private const val STATE_SHARED = "dev.stevenjin.stevenpiano.state.SHARED"

        /** Frames a second the window asks for where the display offers more. */
        private const val MAX_REFRESH_RATE = 60f
    }
}

/**
 * The files an "Open with" (VIEW) or share (SEND, SEND_MULTIPLE) intent carries, as
 * [SharedFiles.accepted] allows. Reading one extra unparcels all of them (before API 33, eagerly), and
 * another app chose what they are: a class this app cannot unparcel throws BadParcelableException,
 * which here means no files.
 */
private fun sharedMidi(intent: Intent): List<Uri> = try {
    when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
        else -> emptyList()
    }.let { uris -> SharedFiles.accepted(uris) { it.scheme } }
} catch (e: BadParcelableException) {
    emptyList()
} catch (e: RuntimeException) {   // ClassCastException, IllegalStateException from a malformed bundle
    emptyList()
}

/** A string extra, or null when the extras cannot be read (see [sharedMidi]). */
private fun stringExtra(intent: Intent, name: String): String? = try {
    intent.getStringExtra(name)
} catch (e: RuntimeException) {
    null
}

/** Resumes at the next frame: called before the first one is drawn, after it. */
private suspend fun awaitFrame() = suspendCancellableCoroutine { continuation ->
    val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    Choreographer.getInstance().postFrameCallback(callback)
    continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
}
