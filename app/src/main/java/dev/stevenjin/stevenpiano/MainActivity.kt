// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.BadParcelableException
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.service.ImportService
import dev.stevenjin.stevenpiano.ui.AppFrame
import dev.stevenjin.stevenpiano.ui.PianoNavHost
import dev.stevenjin.stevenpiano.ui.Route
import dev.stevenjin.stevenpiano.ui.screens.library.ShareSheet
import dev.stevenjin.stevenpiano.ui.theme.PianoTheme

/**
 * The one activity: edge to edge, transparent system bars, the four destinations in a frame the
 * window's width class chooses. MIDI files from the Library's pickers are imported by the import
 * service; files that arrive by "Open with" or the share sheet are imported only once the person
 * says Add in [ShareSheet] (content URIs only, at most [SharedFiles.MAX_SHARED] at a time; when the app may not read them, the
 * Library says so). The playback notification opens Now playing. Rotation and
 * resizing are handled here as configuration changes (the manifest's configChanges): the frame
 * recomputes from the new configuration and nothing is recreated, so nothing may rely on
 * recreation to refresh.
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
        if (savedInstanceState == null) {
            route(intent)
        } else {
            pendingShare = BundleCompat.getParcelableArrayList(savedInstanceState, STATE_SHARED, Uri::class.java).orEmpty()
        }
        setContent {
            val size = calculateWindowSizeClass(this)
            val frame = remember(size) { AppFrame(size.widthSizeClass, size.heightSizeClass) }
            PianoTheme {
                PianoNavHost(frame, requestedTab, onTabShown = { requestedTab = null }) { source ->
                    ImportService.start(this, source, fromPicker = true)
                }
                if (pendingShare.isNotEmpty()) ShareSheet(pendingShare.size, onAdd = ::addShared, onCancel = { pendingShare = emptyList() })
            }
        }
    }

    /** Files still waiting for Add or Cancel survive the activity being recreated (a theme change). */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (pendingShare.isNotEmpty()) outState.putParcelableArrayList(STATE_SHARED, ArrayList(pendingShare))
    }

    /** In the foreground a foreground service may start: composers never looked up are fetched now. */
    override fun onStart() {
        super.onStart()
        graph.fetchArtworkIfDue()
    }

    /**
     * In the background nothing may hold a key down: the Keys screen's keys and sustain let go.
     * Not when the activity only stops to be recreated for a configuration change (a rotation
     * never gets here: the activity handles it without stopping); the Keys screen lets go of
     * whatever its own window held as that window goes.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) graph.player.silenceLive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /**
     * Shared files wait on the Library for the person's Add or Cancel ([pendingShare]); a newer
     * share replaces them. Any app can send this activity an intent, so nothing in one may crash it:
     * a malformed intent (extras that cannot be unparcelled, a provider that throws) is logged and
     * dropped.
     */
    private fun route(intent: Intent) {
        try {
            emulatorSet(intent)
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

    /**
     * The emulator only (a debug build with no piano, see [LoggingPianoLink]): `adb shell am start -n
     * dev.stevenjin.stevenpiano/.MainActivity --es dev.stevenjin.stevenpiano.EMULATOR_SET "ledbright 300"`
     * sets a value past the controls' ranges, so a refusal can be seen. Inert on a phone or tablet.
     */
    private fun emulatorSet(intent: Intent) {
        if (!LoggingPianoLink.isWanted()) return
        val line = stringExtra(intent, EXTRA_EMULATOR_SET)?.trim() ?: return
        graph.pianoSettings.set(line.substringBefore(' '), line.substringAfter(' ', ""))
    }

    companion object {
        /** A [Route] path to open at, e.g. from the playback notification. */
        const val EXTRA_TAB = "dev.stevenjin.stevenpiano.TAB"
        private const val EXTRA_EMULATOR_SET = "dev.stevenjin.stevenpiano.EMULATOR_SET"
        private const val TAG = "MainActivity"
        private const val STATE_SHARED = "dev.stevenjin.stevenpiano.state.SHARED"
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
