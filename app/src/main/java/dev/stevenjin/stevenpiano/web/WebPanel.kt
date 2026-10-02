// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import android.content.Context
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.StoredPin
import dev.stevenjin.stevenpiano.update.CloudOverride
import dev.stevenjin.stevenpiano.web.relay.CloudAddress
import dev.stevenjin.stevenpiano.web.relay.CloudSecrets
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import dev.stevenjin.stevenpiano.web.relay.KeystoreSealer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the web service listens, as the Remote page, its hub row and the notification show it:
 * [running] while it is up; the tablet's [tailnet] and [wifi] addresses it listens on (digits only);
 * [panelOnWifi] when the Wi-Fi one serves the whole panel. [problem] says why it is not running
 * when it should be ("Android refused to start the web service.").
 */
data class WebStatus(
    val running: Boolean = false,
    val tailnet: String? = null,
    val wifi: String? = null,
    val panelOnWifi: Boolean = false,
    val problem: String? = null,
) {
    /** Where the panel is reached: the tailnet address, else the Wi-Fi one if it serves the panel too. */
    val panelHost: String? get() = tailnet ?: wifi?.takeIf { panelOnWifi }

    val panelUrl: String? get() = panelHost?.let { "http://$it:${WebAddress.PORT}" }

    /** Where guests reach the request page: the Wi-Fi address (guests have no Tailscale), else the tailnet one. */
    val guestUrl: String? get() = (wifi ?: tailnet)?.let { "http://$it:${WebAddress.PORT}/request" }
}

/**
 * The web panel's state that outlives any one listener, one per process in [AppGraph]: the
 * sessions and the login guard (shared by the tailnet and Wi-Fi listeners, so a PIN guessed on one
 * is counted on both), the guests' requests (the panel's Requests page and the tablet's Library
 * banner act on the same list), the [backend] the listeners serve, and the [status] the web
 * service reports. Changing the PIN here ends every session. Steven Piano Cloud (v1.10 — M26)
 * shares all of it but the login guard: its [cloud] status as the web service reports it, the relay's
 * secret sealed in the settings ([cloudSecrets]), the enrolments made here ([enrolments]), the public
 * link ([cloudLink]); the relay's requests are weighed by [relayGuard] (audit delta 3).
 */
class WebPanel(private val app: Context, private val graph: AppGraph) {
    val sessions = Sessions()
    val guard = LoginGuard()

    /** The PIN tries that come through the relay, from the internet: a gate of their own, far stricter (audit delta 3). */
    val relayGuard = LoginGuard.forRelay()
    val requests = GuestRequests()

    private val _status = MutableStateFlow(WebStatus())
    val status: StateFlow<WebStatus> = _status.asStateFlow()

    private val _cloud = MutableStateFlow<CloudStatus>(CloudStatus.Off)

    /** Where the tablet's connection to Steven Piano Cloud stands (Off while the web service or remote access is off). */
    val cloud: StateFlow<CloudStatus> = _cloud.asStateFlow()

    private val _enrolments = MutableStateFlow(0)

    /** How many enrolments this process has made: the relay client starts again for each, even for the same piano. */
    val enrolments: StateFlow<Int> = _enrolments.asStateFlow()

    /** The relay's bearer secret, sealed by the Keystore in the settings. */
    val cloudSecrets: CloudSecrets by lazy {
        CloudSecrets(KeystoreSealer(), read = { graph.settingsRepository.cloudSecret() }, write = { graph.settingsRepository.setCloudSecret(it) })
    }

    /** The debug build's local relay on the emulator, read once ([CloudOverride]); null everywhere else. */
    val cloudOverride: String? by lazy { CloudOverride.origin() }

    val backend: AppWebBackend by lazy {
        AppWebBackend(
            app,
            graph,
            addresses = { status.value.let { WebAddresses(it.panelUrl, it.guestUrl, cloudLink()) } },
            guests = { graph.settings.value.let { GuestSettings(open = it.webGuests, approveFirst = it.webApproveFirst) } },
            requested = { requests.requested.value },
            applyWebSettings = ::applyWebSettings,
        )
    }

    /** From [AppGraph.start]: the guests' entries are forgotten as they leave the queue. */
    fun start() {
        graph.appScope.launch {
            graph.player.state.map { it.queue.uids }.distinctUntilChanged().collect { requests.retain(it) }
        }
    }

    /** The web service says where it listens now, or that it stopped. */
    fun report(status: WebStatus) {
        _status.value = status
    }

    /** The web service says where the connection to the relay stands. */
    fun reportCloud(status: CloudStatus) {
        _cloud.value = status
    }

    /** An enrolment was made: the relay client starts again for it. */
    fun enrolled() {
        _enrolments.value++
    }

    /**
     * The panel's public link through the relay, `https://<relay>/p/<id>/` (`http://localhost:8787/…`
     * with the debug build's local relay), while remote access over the internet is on and the tablet
     * is enrolled: the host the relay's hello names once connected, the typed one before.
     */
    fun cloudLink(settings: PianoSettings = graph.settings.value, status: CloudStatus = cloud.value): String? {
        if (!settings.cloudEnabled || !settings.cloudEnrolled) return null
        val typed = settings.cloudHost ?: return null
        val host = (status as? CloudStatus.Connected)?.host ?: typed
        return "${CloudAddress.scheme(CloudAddress.origin(typed, cloudOverride))}://$host/p/${settings.cloudPianoId}/"
    }

    /** A new six-digit PIN, hashed off the main thread; every session ends (open sockets close at their next ping). */
    suspend fun setPin(pin: String) {
        val hash = withContext(Dispatchers.Default) { PinHash.create(pin) }
        graph.settingsRepository.setWebPin(StoredPin(hash.saltText, hash.hashText))
        sessions.closeAll()
    }

    /** The panel is turned off: every session ends with it, and the views' layouts are forgotten (v1.13 — M32). */
    fun turnedOff() {
        sessions.closeAll()
        backend.clearViews()
    }

    /** Approve on the tablet (the Library's banner): the piece joins Up next, as from the panel. */
    fun approve(id: Long) {
        graph.appScope.launch {
            val request = requests.take(id) ?: return@launch
            requests.noteQueued(backend.queueRequested(request.pieceId))
        }
    }

    /** Dismiss on the tablet: the request goes, nothing plays. */
    fun dismiss(id: Long) {
        requests.dismiss(id)
    }

    private suspend fun applyWebSettings(change: SettingsChange) {
        val settings = graph.settingsRepository
        change.webGuests?.let { settings.setWebGuests(it) }
        change.webApproveFirst?.let { settings.setWebApproveFirst(it) }
        change.webHostName?.let { settings.setWebHostName(it.ifEmpty { null }) }
    }
}
