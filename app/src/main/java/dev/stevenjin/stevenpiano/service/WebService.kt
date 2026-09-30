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
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.BuildConfig
import dev.stevenjin.stevenpiano.MainActivity
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.net.HttpFetch
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.Route
import dev.stevenjin.stevenpiano.web.AndroidAssets
import dev.stevenjin.stevenpiano.web.ListenerPlan
import dev.stevenjin.stevenpiano.web.Poster
import dev.stevenjin.stevenpiano.web.WebAddress
import dev.stevenjin.stevenpiano.web.WebApi
import dev.stevenjin.stevenpiano.web.WebAssets
import dev.stevenjin.stevenpiano.web.WebServer
import dev.stevenjin.stevenpiano.web.WebSocketHub
import dev.stevenjin.stevenpiano.web.WebStatus
import dev.stevenjin.stevenpiano.web.relay.CloudAddress
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import dev.stevenjin.stevenpiano.web.relay.RelayClient
import dev.stevenjin.stevenpiano.web.relay.RelayCommands
import dev.stevenjin.stevenpiano.web.relay.RelayConfig
import dev.stevenjin.stevenpiano.web.relay.RelayStatus
import dev.stevenjin.stevenpiano.web.relay.StatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The web panel's server, running while Piano › Remote control › Web control is on (DESIGN.md ›
 * v1.5.1 — M18; BUILD_SPEC.md › The service). A foreground service of type `specialUse` (a local
 * control panel is none of Android's named types; `dataSync` would run out after six hours a day
 * from Android 15), with `connectedDevice` as the fallback should a device refuse `specialUse`;
 * not exported; `START_STICKY`. Its low-importance notification reads "Web control on" and the
 * panel's address.
 *
 * It listens where [WebAddress.choose] says and nowhere else: the tailnet address serves the whole
 * panel; the Wi-Fi address serves guests only (the request page, the poster, the public API)
 * unless Panel on Wi-Fi too is on; never the any-address. Both listeners share the process's
 * sessions, login guard, guests' requests and socket hub ([dev.stevenjin.stevenpiano.web.WebPanel]).
 * The listeners start again whenever the networks change (a callback that sees VPNs too, and a
 * look every 30 s besides), and all stop when the switch turns off, the PIN goes, or Android asks
 * ([onTimeout]). While the player plays it holds a partial wake lock (ten minutes at a time,
 * renewed at every look while the playing goes on), so a piece started from the panel with the
 * tablet's screen off keeps its time even when Android kept the playback service from starting in
 * the background. Debug builds on an emulator also listen on 127.0.0.1, so
 * `adb forward tcp:8737 tcp:8737` reaches the panel from the Mac.
 *
 * Steven Piano Cloud (v1.10 — M26): the service also runs while remote access over the internet is
 * on (with the PIN set), Web control on or not, and then holds the relay client ([RelayClient]) and
 * the panel's server for relayed requests (host "relay", never listening), sharing the hub, the
 * sessions and the guard with the listeners. The client starts once the tablet is enrolled, again
 * for each new enrolment, and is nudged when a network comes and at each look; its state goes to the
 * Remote page ([dev.stevenjin.stevenpiano.web.WebPanel.cloud]) and adds "· Cloud" to the notification
 * while connected.
 */
class WebService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var servers: List<WebServer> = emptyList()
    private var listening: List<ListenerPlan>? = null
    private var watching: Job? = null
    private var hub: WebSocketHub? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val networkChanged = MutableStateFlow(0)
    private lateinit var wakeLock: PowerManager.WakeLock
    private var relay: RelayClient? = null
    private var relayFor: Triple<String?, String?, Int>? = null
    private var relayState: Job? = null
    private var relayServer: WebServer? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground(graph.web.status.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) watching = scope.launch { watch() }
        return START_STICKY
    }

    /** Android's time for this service ran out (it has none for `specialUse`; kept for the fallback's sake): it stops, cleanly. */
    override fun onTimeout(startId: Int) = stopNow()

    override fun onTimeout(startId: Int, fgsType: Int) = stopNow()

    override fun onDestroy() {
        stopListening()
        stopRelay()
        hub?.stop()
        hub = null
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        callback = null
        if (wakeLock.isHeld) wakeLock.release()
        graph.web.report(WebStatus())
        scope.cancel()
        super.onDestroy()
    }

    /** Follows the switch, the PIN, Panel on Wi-Fi too and the networks; and the player, for the wake lock. */
    private suspend fun watch() {
        watchNetworks()
        startHub()
        scope.launch {
            graph.player.state.map { it.status == PlaybackStatus.Playing || it.loading }.distinctUntilChanged().collect(::keepAwake)
        }
        scope.launch {
            while (isActive) {
                delay(LOOK_AGAIN_MS)
                networkChanged.value++
                relay?.nudge()
                if (wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_MS)   // still playing: renewed, so a channel's hours keep it
            }
        }
        scope.launch {
            graph.web.cloud.collect { post(graph.web.status.value) }   // "· Cloud" while connected
        }
        combine(graph.settingsRepository.settings, networkChanged, graph.web.enrolments) { settings, _, enrolments -> settings to enrolments }
            .collect { (settings, enrolments) ->
                if (!(settings.webEnabled || settings.cloudEnabled) || !settings.webPinSet) {
                    stopNow()
                    return@collect
                }
                if (settings.webEnabled) {
                    listen(settings)
                } else if (servers.isNotEmpty() || graph.web.status.value.running) {
                    stopListening()   // remote access alone: no listener on the tablet's networks
                    graph.web.report(WebStatus())
                    post(WebStatus())
                }
                followCloud(settings, enrolments)
            }
    }

    /**
     * The relay client while remote access is on and the tablet is enrolled (v1.10 — M26): made
     * for this enrolment (the address, the piano's id, [enrolments]) and started; stopped otherwise.
     */
    private fun followCloud(settings: PianoSettings, enrolments: Int) {
        if (!settings.cloudEnabled || !settings.cloudEnrolled) {
            stopRelay()
            if (settings.cloudEnabled) graph.web.reportCloud(CloudStatus.NotEnrolled)
            return
        }
        val key = Triple(settings.cloudHost, settings.cloudPianoId, enrolments)
        if (relay != null && relayFor == key) return
        stopRelay()
        val client = relayClient() ?: return
        relay = client
        relayFor = key
        relayState = scope.launch { client.state.collect { graph.web.reportCloud(it) } }
        client.start()
    }

    private fun stopRelay() {
        relayState?.cancel()
        relayState = null
        relay?.stop()
        relay = null
        relayFor = null
        graph.web.reportCloud(CloudStatus.Off)
    }

    /** The relay client, with the panel's server for relayed requests (made once: never listening), the hub, the console's commands and the status. */
    private fun relayClient(): RelayClient? {
        val panel = graph.web
        val sockets = hub ?: return null
        val server = relayServer ?: WebServer(
            WebServer.Config(host = RELAY_SERVER, port = 0, tempDir = File(cacheDir, ImportLimits.WEB_DIR)),
            panel.backend,
            panel.sessions,
            panel.guard,
            panel.requests,
            AndroidAssets(this),
            sockets,
            posterPage = { url -> Poster.page(AndroidAssets(this).read(WebAssets.POSTER.name), url) },
        ).also { relayServer = it }
        return RelayClient(
            config = config@{
                val s = graph.settingsRepository.settings.first()
                val host = s.cloudHost ?: return@config null
                val pianoId = s.cloudPianoId ?: return@config null
                val secret = withContext(Dispatchers.IO) { panel.cloudSecrets.secret() } ?: return@config null
                val origin = CloudAddress.origin(host, panel.cloudOverride)
                RelayConfig(CloudAddress.socketUrl(origin), pianoId, secret, CloudAddress.scheme(origin))
            },
            server = server,
            hub = sockets,
            // The console's "Load Steven's library" (v1.10: M27's pack): the + sheet's load, answered at once.
            commands = RelayCommands(
                panel.backend,
                libraryLoad = {
                    val pack = graph.libraryPack
                    RelayCommands.libraryLoad(graph.network.online.value, { pack.state.value }) { pack.load(everything = false) }
                },
                trail = LinkLog::warn,
            ),
            status = cloudStatus(),
            secrets = { secret -> withContext(Dispatchers.IO) { panel.cloudSecrets.keep(secret) } },
            scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
            log = LinkLog::warn,
            online = { graph.network.isOnline() },
            userAgent = HttpFetch.USER_AGENT,
        )
    }

    /** The tablet's status for the relay: what the panel shows, the library's size and pack, the channels ([RelayStatus]). */
    private fun cloudStatus(): StatusSource = object : StatusSource {
        override suspend fun report(): JSONObject {
            val panel = graph.web
            val settings = graph.settings.value
            return RelayStatus.report(
                appVersion = BuildConfig.VERSION_NAME,
                appCode = BuildConfig.VERSION_CODE,
                state = panel.backend.state(),
                settings = settings,
                panelHost = panel.status.value.panelHost,
                libraryPieces = runCatching { graph.library.count().first() }.getOrNull(),
                channels = panel.backend.channels(),
                at = System.currentTimeMillis(),
            )
        }

        override val changes: Flow<Unit> = merge(
            graph.player.state.map { it.status to it.piece?.pieceId },
            graph.player.state.map { it.channel },
            graph.pianoLink.state.map { },
            graph.pianoSettings.state.map { },
            graph.settings.map { listOf(it.webGuests, it.webApproveFirst, it.webEnabled, it.libraryPackVersion) },   // a pack loaded: the console's line at once
            graph.web.status.map { it.panelHost },
        ).map { }
    }

    /**
     * Listens where the networks allow now ([WebAddress.plan]), unless it listens there already on
     * every address. A listener that could not be bound, or whose socket closed itself (Android
     * destroyed it with its address: [dev.stevenjin.stevenpiano.web.SteadyServerSocket]), is
     * started again at the next look.
     */
    private suspend fun listen(settings: PianoSettings) {
        val choice = withContext(Dispatchers.IO) { WebAddress.choose(WebAddress.list()) }
        val wanted = WebAddress.plan(choice, settings.webOnWifi, loopback = LoggingPianoLink.isWanted())
        if (wanted == listening && servers.isNotEmpty() && servers.size == wanted.size && servers.all { it.isAlive }) return
        stopListening()
        val started = withContext(Dispatchers.IO) { wanted.mapNotNull(::server) }
        servers = started
        listening = wanted
        val bound = started.map { it.hostname }.toSet()
        val status = WebStatus(
            running = true,
            tailnet = choice.tailnet?.hostAddress?.takeIf { it in bound },
            wifi = choice.wifi?.hostAddress?.takeIf { it in bound },
            panelOnWifi = settings.webOnWifi,
        )
        graph.web.report(status)
        post(status)
    }

    /** One listener as [plan] says, started, or null when its address could not be bound (it went away meanwhile). */
    private fun server(plan: ListenerPlan): WebServer? {
        val address = plan.address
        val guestOnly = plan.guestOnly
        val names = plan.names
        val panel = graph.web
        val assets = AndroidAssets(this)
        val server = WebServer(
            WebServer.Config(
                host = address,
                guestOnly = guestOnly,
                names = { names + listOfNotNull(graph.settings.value.webHostName) },
                tempDir = File(cacheDir, ImportLimits.WEB_DIR),
            ),
            panel.backend,
            panel.sessions,
            panel.guard,
            panel.requests,
            assets,
            hub ?: return null,
            posterPage = { url -> Poster.page(assets.read(WebAssets.POSTER.name), url) },
        )
        return try {
            server.startListening()
            if (BuildConfig.DEBUG) Log.d(TAG, "Listening on $address:${WebAddress.PORT}" + if (guestOnly) " (guests only)" else "")
            server
        } catch (e: IOException) {
            Log.w(TAG, "The web panel couldn't listen on an address it chose")
            null
        }
    }

    private fun stopListening() {
        val stopping = servers
        servers = emptyList()
        listening = null
        stopping.forEach { runCatching { it.stop() } }
    }

    /** The socket hub, shared by every listener this service starts. */
    private fun startHub() {
        if (hub != null) return
        val panel = graph.web
        val player = graph.player
        val created = WebSocketHub(
            stateMessage = { WebApi.state(panel.backend.state(), panel.requests.pending.value.size, type = "state").toString() },
            progressMessage = {
                if (player.state.value.status != PlaybackStatus.Playing) {
                    null
                } else {
                    WebApi.progress(player.positionMicrosNow() / 1_000, System.currentTimeMillis()).toString()
                }
            },
            sessionValid = panel.sessions::isValid,
        )
        val changes = merge(
            graph.player.state.map { },
            graph.pianoLink.state.map { },
            graph.pianoSettings.state.map { },
            graph.importProgress.map { },
            graph.artwork.progress.map { },
            graph.settings.map { },
            panel.requests.pending.map { },
            panel.requests.requested.map { },
            panel.status.map { },
            graph.schedules.entries.map { },
            // Studio (v1.7 — M23): its jobs, its models, the pieces waiting for Keep or Discard, whether it runs here.
            graph.studio.jobs.jobs.map { },
            graph.studio.models.installed.map { },
            graph.studio.review.undecided.map { },
            graph.studio.availability.support.map { },
            // The tablet's piano sound (v1.8 — M25): whether it sounds, and its SoundFont.
            graph.tabletSound.state.map { },
            // Steven Piano Cloud (v1.10 — M26): the public link in the state follows the connection.
            panel.cloud.map { },
        )
        created.start(CoroutineScope(scope.coroutineContext + Dispatchers.IO), changes)
        hub = created
    }

    /** Network changes, VPNs (Tailscale) included, nudge [networkChanged]; the loop also looks every 30 s. */
    private fun watchNetworks() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = nudge()

            override fun onLost(network: Network) = nudge()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = nudge()

            private fun nudge() {
                scope.launch {
                    networkChanged.value++
                    relay?.nudge()
                }
            }
        }
        try {
            connectivity.registerNetworkCallback(request, watcher)
            callback = watcher
        } catch (e: RuntimeException) {
            Log.w(TAG, "The web panel can't watch the networks; it looks every 30 s instead")
        }
    }

    private fun keepAwake(playing: Boolean) {
        if (playing) wakeLock.acquire(WAKE_LOCK_MS) else if (wakeLock.isHeld) wakeLock.release()
    }

    /** Off: the listeners, the relay, the sockets, the sessions, the notification, the service. */
    private fun stopNow() {
        stopListening()
        stopRelay()
        hub?.stop()
        hub = null
        graph.web.turnedOff()
        graph.web.report(WebStatus())
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * In the foreground as `specialUse`, else as `connectedDevice` (the plan's fallback, for a device
     * that refuses the first); false when Android refuses both (the Remote page says so).
     */
    private fun goForeground(status: WebStatus): Boolean {
        val notification = notificationFor(status)
        for (type in listOf(TYPE_SPECIAL_USE, TYPE_CONNECTED_DEVICE)) {
            try {
                ServiceCompat.startForeground(this, ID, notification, type)
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "The web service could not start as foreground type $type")
            }
        }
        graph.web.report(WebStatus(problem = REFUSED))
        return false
    }

    private fun post(status: WebStatus) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(ID, notificationFor(status))
    }

    /**
     * "Web control on", and where: the panel's address, the guests' when only they are served, or that
     * no network has an address yet; "· Cloud" after it while the relay is connected (v1.10 — M26).
     * With remote access alone, "Remote access on" and the relay's state.
     */
    private fun notificationFor(status: WebStatus): Notification {
        val cloud = graph.web.cloud.value
        val connected = cloud is CloudStatus.Connected
        val web = graph.settings.value.webEnabled
        val local = status.panelUrl ?: status.guestUrl?.let { "Guests: $it" } ?: "Waiting for a network"
        val text = when {
            web && connected -> "$local · Cloud"
            web -> local
            cloud is CloudStatus.Connected -> "Cloud · ${cloud.host}"
            cloud is CloudStatus.Waiting -> "Cloud · ${cloud.reason}"
            cloud is CloudStatus.Revoked || cloud is CloudStatus.Disabled || cloud is CloudStatus.NotEnrolled -> "Cloud · enrol this tablet again"
            else -> "Cloud · connecting"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piano)
            .setContentTitle(if (web) "Web control on" else "Remote access on")
            .setContentText(text)
            .setContentIntent(openPiano())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openPiano(): PendingIntent = PendingIntent.getActivity(
        this,
        ID,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, Route.Piano.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "WebService"
        const val CHANNEL_ID = "web"
        private const val ID = 5
        private const val LOOK_AGAIN_MS = 30_000L
        private const val WAKE_LOCK_TAG = "StevenPiano:web"
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val REFUSED = "Android refused to start the web service."

        /** The relayed requests' server's name for itself: it never listens anywhere. */
        private const val RELAY_SERVER = "relay"

        /**
         * `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (API 34) and `…_CONNECTED_DEVICE` (API 29):
         * constants, inlined when compiled, which ServiceCompat hands only to the Android versions
         * that know foreground types.
         */
        @SuppressLint("InlinedApi")
        private const val TYPE_SPECIAL_USE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        @SuppressLint("InlinedApi")
        private const val TYPE_CONNECTED_DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Web control")
                .setDescription("Shows while the web panel is on, with its address.")
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** From the Remote page's switch or the app coming to the foreground: a foreground service must be started from there. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, WebService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Android refused to start the web service")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebService::class.java))
        }
    }
}
