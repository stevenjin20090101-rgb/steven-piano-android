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
import android.os.Bundle
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
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.service.ImportService
import dev.stevenjin.stevenpiano.ui.AppFrame
import dev.stevenjin.stevenpiano.ui.PianoNavHost
import dev.stevenjin.stevenpiano.ui.Route
import dev.stevenjin.stevenpiano.ui.theme.PianoTheme

/**
 * The one activity: edge to edge, transparent system bars, the four destinations in a frame the
 * window's width class chooses. MIDI files that arrive by "Open with" or the share sheet, or
 * come from the Library's pickers, are imported by the import service; the playback
 * notification opens Now playing.
 */
class MainActivity : ComponentActivity() {
    private var requestedTab by mutableStateOf<Route?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (savedInstanceState == null) route(intent)
        setContent {
            val size = calculateWindowSizeClass(this)
            val frame = remember(size) { AppFrame(size.widthSizeClass, size.heightSizeClass) }
            PianoTheme {
                PianoNavHost(frame, requestedTab, onTabShown = { requestedTab = null }) { source ->
                    ImportService.start(this, source, fromPicker = true)
                }
            }
        }
    }

    /** In the background nothing may hold a key down: the Keys screen's keys and sustain let go. */
    override fun onStop() {
        super.onStop()
        graph.player.silenceLive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent) {
        val shared = sharedMidi(intent)
        if (shared.isNotEmpty()) {
            ImportService.start(this, ImportSource.Uris(shared), fromPicker = false)
            requestedTab = Route.Library
        } else {
            Route.of(intent.getStringExtra(EXTRA_TAB))?.let { requestedTab = it }
        }
    }

    companion object {
        /** A [Route] path to open at, e.g. from the playback notification. */
        const val EXTRA_TAB = "dev.stevenjin.stevenpiano.TAB"
    }
}

/** The files an "Open with" (VIEW) or share (SEND, SEND_MULTIPLE) intent carries. */
private fun sharedMidi(intent: Intent): List<Uri> = when (intent.action) {
    Intent.ACTION_VIEW -> listOfNotNull(intent.data)
    Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else -> emptyList()
}
