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
import android.content.Context
import android.os.StrictMode
import dev.stevenjin.stevenpiano.service.ArtworkService
import dev.stevenjin.stevenpiano.service.ImportService
import dev.stevenjin.stevenpiano.service.PlaybackNotification

/**
 * Builds the [AppGraph] once per process, and the notification channels. First of all it puts
 * [CrashSilencer] in front of Android's crash handler, so a crash anywhere sends the piano the
 * stop sequence before the process ends.
 */
class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashSilencer.install { if (::graph.isInitialized) graph.pianoLinkIfMade() else null }
        if (BuildConfig.DEBUG) watchStrictly()
        PlaybackNotification.createChannel(this)
        ImportService.createChannel(this)
        ArtworkService.createChannel(this)
        graph = AppGraph(this).also { it.start() }
    }
}

/** Debug builds log disk and network work on the main thread, leaked closeables and the like (StrictMode), without stopping. */
private fun watchStrictly() {
    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
    StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
}

/** The process's [AppGraph], from any Context. */
val Context.graph: AppGraph get() = (applicationContext as App).graph
