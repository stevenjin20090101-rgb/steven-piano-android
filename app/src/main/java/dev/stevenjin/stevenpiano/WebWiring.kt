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
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.service.WebService
import dev.stevenjin.stevenpiano.web.WebPanel
import dev.stevenjin.stevenpiano.web.relay.CloudAddress
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import dev.stevenjin.stevenpiano.web.relay.EnrolResult
import dev.stevenjin.stevenpiano.web.relay.Enrolment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The web panel's part of [AppGraph]: the panel, its switches, and the enrolment for the internet. The graph makes it once
 * and everything else reaches it through the graph's members; what it holds is lazy or asked for.
 */
internal class WebWiring(private val graph: AppGraph, private val app: Application) {
    private val appScope = graph.appScope

    /** The web panel (Piano › Web panel): its sessions, login guard, guests' requests, and where its service listens. */
    val web: WebPanel by lazy { WebPanel(app, graph) }

    /**
     * The web panel on or off (Piano › Web panel): the switch is saved first, so the service,
     * which follows it, sees it on when it starts; off, the service stops and every session ends.
     */
    fun setWebEnabled(on: Boolean) {
        appScope.launch {
            graph.settingsRepository.setWebEnabled(on)
            if (on) {
                if (!WebService.start(app)) web.report(web.status.value.copy(problem = "Android refused to start the web service."))
            } else {
                WebService.stop(app)
                web.turnedOff()
            }
        }
    }

    /**
     * The app came to the foreground (where a foreground service may start): with Web control on (or
     * remote access over the internet, v1.10 — M26) and a PIN set, the web service starts, after a
     * restart of the tablet or of the app.
     */
    fun startWebIfOn(context: Context) {
        val appContext = context.applicationContext
        appScope.launch {
            val s = graph.settingsRepository.settings.first()
            val cloudDown = s.cloudEnabled && s.cloudEnrolled && web.cloud.value == CloudStatus.Off
            if ((s.webEnabled || s.cloudEnabled) && s.webPinSet && (!web.status.value.running || cloudDown)) WebService.start(appContext)
        }
    }

    /**
     * The web panel over the internet on or off (Piano › Web panel › OVER THE INTERNET, v1.10 — M26): saved
     * first, so the web service, which follows it, sees it; on, the service starts (it connects once
     * a PIN is set and the tablet is enrolled); off, it stops unless Web control keeps it.
     */
    fun setCloudEnabled(on: Boolean) {
        appScope.launch {
            graph.settingsRepository.setCloudEnabled(on)
            val s = graph.settingsRepository.settings.first()
            if (on) {
                if (s.webPinSet && !WebService.start(app)) web.report(web.status.value.copy(problem = "Android refused to start the web service."))
            } else if (!s.webEnabled) {
                WebService.stop(app)
            }
        }
    }

    /**
     * Enrols this tablet with the relay at [host] using a [code] from the console (v1.10 — M26): the
     * typed address is remembered; on success the piano's id and its sealed secret are kept together
     * and the relay client starts again for them. The answer says how it went, in plain words.
     */
    suspend fun enrol(host: String, code: String): EnrolResult {
        CloudAddress.host(host)?.let { graph.settingsRepository.setCloudHost(it) }
        val result = withContext(Dispatchers.IO) { Enrolment(web.cloudOverride).enrol(host, code) }
        if (result !is EnrolResult.Enrolled) return result
        val sealed = withContext(Dispatchers.IO) { web.cloudSecrets.seal(result.secret) }
            ?: return EnrolResult.Refused("The tablet couldn't keep its key. Try again.")
        graph.settingsRepository.setCloudEnrolment(result.host, result.pianoId, sealed)
        web.enrolled()
        LinkLog.warn("Cloud: enrolled with ${result.host}")
        return result
    }

    /** Forget this cloud (v1.10 — M26): remote access turns off, the enrolment and its key go; the typed address stays. */
    fun forgetCloud() {
        appScope.launch {
            graph.settingsRepository.forgetCloud()
            withContext(Dispatchers.IO) { web.cloudSecrets.forget() }
            web.reportCloud(CloudStatus.Off)
            if (!graph.settingsRepository.settings.first().webEnabled) WebService.stop(app)
            LinkLog.warn("Cloud: this tablet's enrolment was forgotten")
        }
    }
}
