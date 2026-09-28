// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.OtaBegin
import dev.stevenjin.stevenpiano.ble.OtaChannel
import dev.stevenjin.stevenpiano.ble.OtaEvent
import dev.stevenjin.stevenpiano.ble.OtaFrames
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.update.UpdateFailure
import dev.stevenjin.stevenpiano.update.UpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Updates the piano's firmware over the Bluetooth link (plan M21; `firmware/docs/BLE_OTA.md`, the
 * app's obligations in § 11).
 *
 * **Checks** fetch the signed release manifest ([server], the firmware's allow-list), refuse one
 * whose signature does not check out against [publicKey], and compare its version with the one the
 * piano reports ([PianoLink.firmwareVersion]): newer is [FirmwareState.Available] (or
 * [FirmwareState.UsbOnly], or [FirmwareState.NeedsNewerApp] when its `minAppVersionCode` is above
 * [appVersionCode]), the same or older [FirmwareState.UpToDate]. Automatically once a day while a
 * piano that can be updated is connected and the switch is on ([runSchedule]), when the Firmware
 * page opens ([checkOnOpen]), and on the page's button ([checkNow]).
 *
 * **An update** ([update]) needs the link connected to a piano that has the update service and the
 * tablet at 20 % or charging (checked before the download and again before sending). It downloads
 * the image (4 MB at most) and checks its size, its SHA-256 and the signature over the digest of
 * those very bytes (on [compute]); then it locks the player ("Updating the piano"), stops it,
 * waits until the stop sequence has been written and [QUIET_MS] more have passed (the piano refuses
 * a BEGIN within 500 ms of a note, ERR 1), and sends: BEGIN, READY, then the image a window of
 * frames at a time (READY's window, frames of READY's chunk at most), each window answered by an
 * ACK before the next, then END, VERIFYING and OK. [cancel] before END sends ABORT. After OK the
 * link expects the piano's restart ([PianoLink.expectRestart]) and the updater waits (a minute at
 * most) for it to come back, then reads its version and the settings dump's `!ota`: the new version
 * is [FirmwareState.Done] ("confirming…" while `pending`, read again every 30 s), the old one a
 * rollback. A session lost after END (the point of no return) is judged the same way, by what the
 * piano runs once it is back. Any failure is [FirmwareState.Failed] and is never retried by itself:
 * Retry is the person's. Every outcome goes to [log] (the link's log). The player is unlocked
 * whatever happens. Call from [scope]'s thread (the main thread).
 */
class FirmwareUpdater(
    private val link: PianoLink,
    /** The piano's settings, for `!ota` (`PianoSettingsRepository.state`). */
    private val pianoState: StateFlow<PianoState>,
    /** Asks the piano for one of its dump's facts again (`get !name`). */
    private val readFact: (String) -> Unit,
    private val player: FirmwarePlayer,
    private val server: UpdateServer,
    private val publicKey: ByteArray,
    private val appVersionCode: Int,
    /** Android 13 and newer: Ed25519 from the platform's provider first ([Ed25519]). */
    private val platformEd25519: Boolean,
    private val online: StateFlow<Boolean>,
    private val power: () -> PowerState,
    private val scope: CoroutineScope,
    /** A monotonic clock in milliseconds (elapsed real time on Android; the test scheduler's in tests). */
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    /** Where the image is hashed and its signature checked: off the main thread. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<FirmwareState>(FirmwareState.Idle)
    val state: StateFlow<FirmwareState> = _state.asStateFlow()

    /** The piano as the updater sees it: its version and whether it can be updated, from the link. */
    val piano: StateFlow<FirmwarePiano> = combine(link.state, link.firmwareVersion) { state, version -> pianoOf(state, version) }
        .stateIn(scope, SharingStarted.Eagerly, pianoOf(link.state.value, link.firmwareVersion.value))

    /** When the manifest was last fetched, by [now]; null before the first time in this process. */
    @Volatile
    var lastCheckedAt: Long? = null
        private set

    private val checks = Mutex()
    private var job: Job? = null
    private var confirmJob: Job? = null
    private var started = false

    @Volatile
    private var cancelRequested = false

    /** The update service the transfer under way talks to, and whether it has sent ABORT (once is enough). */
    private var sending: OtaChannel? = null
    private var abortSent = false

    /** The last manifest a check read, to weigh again against a piano that connects with another version. */
    private var lastManifest: FirmwareManifest? = null

    /** The last image downloaded and verified, kept for Retry. */
    private var verified: Pair<FirmwareManifest, ByteArray>? = null

    /** Follows the link from now on: a release on offer is weighed again whenever a piano connects with its version. */
    fun start() {
        if (started) return
        started = true
        scope.launch { piano.collect(::reweigh) }
    }

    /** The Firmware page's Check for piano updates: asks now, whatever the switch says. */
    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    /** The Firmware page opened: a check, unless one ran in the last [OPEN_CHECK_EVERY_MS]. */
    fun checkOnOpen() {
        val piano = piano.value
        if (piano !is FirmwarePiano.Connected || !piano.updatable || _state.value.busy) return
        val last = lastCheckedAt
        if (last != null && now() - last < OPEN_CHECK_EVERY_MS) return
        scope.launch { check(manual = false) }
    }

    /**
     * Automatic checks for as long as the caller runs this (the activity, while started): while
     * [enabled] (Check for updates automatically) is on, the device is online and a piano that can
     * be updated is connected, a check when one is due (none yet in this process, or a day after
     * the last), then one a day.
     */
    suspend fun runSchedule(enabled: Flow<Boolean>) {
        combine(enabled, online, piano) { on, connected, piano -> on && connected && piano is FirmwarePiano.Connected && piano.updatable }
            .distinctUntilChanged()
            .collectLatest { ready ->
                if (!ready) return@collectLatest
                while (true) {
                    delay(untilDue())
                    val asked = lastCheckedAt
                    check(manual = false)
                    // Nothing was asked (the piano went a moment ago, or a transfer runs): look again in a while, never at once.
                    if (lastCheckedAt == asked) delay(SCHEDULE_RETRY_MS)
                }
            }
    }

    /** How long until an automatic check is due. */
    fun untilDue(): Long {
        val last = lastCheckedAt ?: return 0L
        return (last + INTERVAL_MS - now()).coerceIn(0L, INTERVAL_MS)
    }

    /** One check: the manifest, its signature, then the piano's version against it. [manual]: say why when it can't ask. */
    suspend fun check(manual: Boolean) = checks.withLock {
        val before = _state.value
        if (before.busy) return@withLock
        val piano = piano.value
        if (piano !is FirmwarePiano.Connected || !piano.updatable) {
            val why = if (piano is FirmwarePiano.Connected) FirmwareFailures.NOT_UPDATABLE else FirmwareFailures.NOT_CONNECTED
            if (manual) settle(FirmwareState.Failed(why, retryable = false, manifest = before.manifest, check = true))
            return@withLock
        }
        if (!online.value) {
            if (manual) settle(FirmwareState.Failed(FirmwareFailures.OFFLINE, retryable = true, manifest = before.manifest, check = true))
            return@withLock
        }
        lastCheckedAt = now()
        if (before.manifest == null) _state.compareAndSet(before, FirmwareState.Checking)
        val result = try {
            val manifest = FirmwareManifest.parse(server.manifest())
            if (!manifest.verify(publicKey, platformEd25519)) {
                log("Firmware check: the release manifest for ${manifest.version} is not signed with the author's key; not offered")
                FirmwareState.Failed(FirmwareFailures.UNSIGNED, retryable = false, check = true)
            } else {
                lastManifest = manifest
                weigh(manifest, piano.version).also { log("Firmware check: ${describe(it, piano)}") }
            }
        } catch (e: CancellationException) {
            _state.compareAndSet(FirmwareState.Checking, before)
            throw e
        } catch (e: InvalidFirmwareManifest) {
            log("Firmware check: ${e.message}")
            FirmwareState.Failed(FirmwareFailures.UNREADABLE, retryable = false, manifest = before.manifest, check = true)
        } catch (e: IOException) {
            log("Firmware check failed: ${e.javaClass.simpleName}")
            FirmwareState.Failed(FirmwareFailures.UNREACHABLE, retryable = true, manifest = before.manifest, check = true)
        } catch (e: RuntimeException) {
            log("Firmware check failed: ${e.javaClass.simpleName}")
            FirmwareState.Failed(FirmwareFailures.UNREADABLE, retryable = false, manifest = before.manifest, check = true)
        }
        settle(result)
    }

    /**
     * The Update (or Retry) button: the release on offer goes to the piano. Nothing while a transfer
     * runs, for a release this app can't send, or after a failure Retry would not mend.
     */
    fun update() {
        val current = _state.value
        val manifest = current.manifest ?: return
        val allowed = current is FirmwareState.Available || (current is FirmwareState.Failed && (current.retryable || current.check))
        if (!allowed || manifest.usbOnly || manifest.needsNewerApp(appVersionCode)) return
        confirmJob?.cancel()
        cancelRequested = false
        _state.value = FirmwareState.Downloading(manifest, 0, manifest.sizeBytes)
        job = scope.launch { transfer(manifest) }
    }

    /**
     * Cancel: a download stops and the release stays on offer; a transfer to the piano ends with
     * ABORT (the piano discards what it received and keeps its firmware). Nothing after END.
     */
    fun cancel() {
        when (_state.value) {
            is FirmwareState.Downloading, is FirmwareState.Verifying -> job?.cancel()
            is FirmwareState.Sending -> {
                cancelRequested = true
                sending?.let(::abortOnce)
            }
            else -> Unit
        }
    }

    // ---- The transfer ----------------------------------------------------------------------

    private suspend fun transfer(manifest: FirmwareManifest) {
        var locked = false
        try {
            precondition()?.let { why -> return notSent(manifest, why) }
            val image = verified?.takeIf { it.first == manifest }?.second ?: (download(manifest) ?: return)
            _state.value = FirmwareState.Verifying(manifest)
            if (!withContext(compute) { matches(manifest, image) }) {
                verified = null
                log("Firmware update: the download of ${manifest.version} does not match its release")
                return fail(FirmwareState.Failed(FirmwareFailures.MISMATCH, retryable = true, manifest = manifest))
            }
            verified = manifest to image
            if (cancelRequested) return cancelled(manifest, "before it was sent")
            precondition()?.let { why -> return notSent(manifest, why) }
            val ota = link.ota ?: return notSent(manifest, FirmwareFailures.NOT_UPDATABLE)
            val before = (piano.value as? FirmwarePiano.Connected)?.version
            _state.value = FirmwareState.Sending(manifest, 0, image.size.toLong(), 0)
            player.lock(PLAYER_LOCKED)
            locked = true
            if (!player.stopForUpdate(STOP_TIMEOUT_MS)) log("Firmware update: the stop sequence was not confirmed written in $STOP_TIMEOUT_MS ms")
            delay(QUIET_MS)   // BLE_OTA.md › 7, 11: at least 500 ms between the stop sequence and BEGIN
            if (cancelRequested) return cancelled(manifest, "before it was sent")
            when (val outcome = send(manifest, image, ota)) {
                is Outcome.Sent -> comeBack(manifest, before, uncertain = null)
                is Outcome.Uncertain -> comeBack(manifest, before, uncertain = outcome.detail)
                Outcome.Cancelled -> cancelled(manifest, "while it was sent")
                is Outcome.Refused -> {
                    log("Firmware update: ${outcome.detail}; the piano kept its firmware")
                    fail(FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, outcome.retryable, manifest, hint = outcome.hint))
                }
            }
        } catch (e: CancellationException) {
            if (_state.value.busy) _state.value = FirmwareState.Available(manifest)
            throw e
        } finally {
            if (locked) player.unlock()
        }
    }

    private fun notSent(manifest: FirmwareManifest, why: String) {
        log("Firmware update: ${manifest.version} not sent: $why")
        fail(FirmwareState.Failed(why, retryable = why != FirmwareFailures.NOT_UPDATABLE, manifest = manifest))
    }

    private fun cancelled(manifest: FirmwareManifest, stage: String) {
        log("Firmware update: cancelled $stage; the piano kept its firmware")
        _state.value = FirmwareState.Available(manifest)
    }

    /** The image, its SHA-256 computed on the way, or null (with the failure published). */
    private suspend fun download(manifest: FirmwareManifest): ByteArray? {
        val limit = minOf(manifest.sizeBytes, UpdateSource.MAX_FIRMWARE_BYTES)
        val out = ByteArrayOutputStream(limit.toInt())
        var count = 0L
        var shown = 0L
        try {
            server.download(manifest.binUrl, limit) { buffer, n ->
                count += n
                if (count > limit) throw UpdateFailure(FirmwareFailures.MISMATCH)
                out.write(buffer, 0, n)
                if (count == limit || count - shown >= PROGRESS_BYTES) {
                    shown = count
                    _state.value = FirmwareState.Downloading(manifest, count, manifest.sizeBytes)
                }
            }
        } catch (e: UpdateFailure) {
            log("Firmware update: the download of ${manifest.version} failed: ${e.message}")
            fail(FirmwareState.Failed(e.message ?: FirmwareFailures.MISMATCH, retryable = true, manifest = manifest))
            return null
        } catch (e: IOException) {
            log("Firmware update: the download of ${manifest.version} failed: ${e.javaClass.simpleName}")
            fail(FirmwareState.Failed(if (count > 0) FirmwareFailures.STOPPED else FirmwareFailures.UNREACHABLE, retryable = true, manifest = manifest))
            return null
        }
        return out.toByteArray()
    }

    /** The image is the release's: its size and SHA-256, and the signature over the digest of these very bytes. */
    private fun matches(manifest: FirmwareManifest, image: ByteArray): Boolean {
        if (image.size.toLong() != manifest.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(image)
        if (!digest.contentEquals(manifest.digest())) return false
        return Ed25519.verify(publicKey, digest, manifest.signature(), platformEd25519)
    }

    /** Why the image can't go now, or null: connected to a piano that can take it, and the tablet charged enough. */
    private fun precondition(): String? {
        if (link.state.value !is LinkState.Connected) return FirmwareFailures.NOT_CONNECTED
        if (link.ota == null) return FirmwareFailures.NOT_UPDATABLE
        val power = power()
        val percent = power.percent
        if (percent != null && percent < MIN_BATTERY_PERCENT && !power.charging) return FirmwareFailures.LOW_BATTERY
        return null
    }

    private sealed interface Outcome {
        data class Sent(val restartInMs: Int) : Outcome

        /** The session ended after END without the piano's word: it may be restarting on the new image, or not. */
        data class Uncertain(val detail: String) : Outcome

        data object Cancelled : Outcome

        data class Refused(val detail: String, val retryable: Boolean, val hint: String? = null) : Outcome
    }

    /** BEGIN to OK: the image a window at a time, each answered by an ACK before the next. */
    private suspend fun send(manifest: FirmwareManifest, image: ByteArray, ota: OtaChannel): Outcome = coroutineScope {
        val total = image.size.toLong()
        val header = OtaBegin(total, manifest.digest(), manifest.signature(), manifest.version, ota.window)
        abortSent = false
        sending = ota
        val answers = ota.begin(header).buffer(Channel.UNLIMITED).produceIn(this)
        try {
            log("Firmware update: sending ${manifest.version}, $total bytes")
            val ready = when (val first = next(answers, READY_TIMEOUT_MS)) {
                is OtaEvent.Ready -> first
                else -> return@coroutineScope refusal(first, ota, answers, "before READY")
            }
            // READY's window is the piano's (it may lower ours); a frame never carries more than either side allows.
            val chunk = minOf(ready.maxChunk, ota.maxChunk)
            val window = ready.window
            if (chunk <= 0 || window !in 1..OtaFrames.MAX_WINDOW) {
                abortOnce(ota)
                return@coroutineScope Outcome.Refused("READY offered frames of $chunk bytes, $window a window", retryable = true)
            }
            val started = now()
            var sent = 0
            var seq = 0
            while (sent < image.size) {
                if (cancelRequested) return@coroutineScope stop(ota, answers)
                var frames = 0
                while (frames < window && sent < image.size) {
                    val length = minOf(chunk, image.size - sent)
                    if (!ota.write(seq, image.copyOfRange(sent, sent + length))) {
                        val said = next(answers, 0L)
                        if (said != null) return@coroutineScope refusal(said, ota, answers, "at frame $seq")
                        abortOnce(ota)
                        return@coroutineScope Outcome.Refused("the link would not take frame $seq", retryable = true)
                    }
                    sent += length
                    seq++
                    frames++
                }
                when (val answer = next(answers, ACK_TIMEOUT_MS)) {
                    is OtaEvent.Ack -> if (answer.bytesReceived != sent.toLong()) {
                        abortOnce(ota)
                        return@coroutineScope Outcome.Refused("the piano acknowledged ${answer.bytesReceived} bytes of $sent", retryable = true)
                    }
                    else -> return@coroutineScope refusal(answer, ota, answers, "waiting for an ACK at $sent bytes")
                }
                val elapsed = now() - started
                _state.value = FirmwareState.Sending(manifest, sent.toLong(), total, if (elapsed > 0) sent * 1_000L / elapsed else 0L)
            }
            if (cancelRequested) return@coroutineScope stop(ota, answers)
            ota.end()   // the point of no return: from here the piano verifies and restarts, with or without the app
            _state.value = FirmwareState.PianoVerifying(manifest)
            var answer = next(answers, VERIFY_TIMEOUT_MS)
            if (answer == OtaEvent.Verifying) answer = next(answers, VERIFY_TIMEOUT_MS)
            when (answer) {
                is OtaEvent.Ok -> Outcome.Sent(answer.restartInMs)
                is OtaEvent.Error -> refusal(answer, ota, answers, "while the piano checked the image")
                is OtaEvent.Lost -> Outcome.Uncertain("the session ended after END: ${answer.reason}")
                null -> Outcome.Uncertain("no answer within ${VERIFY_TIMEOUT_MS / 1000} s after END")
                else -> Outcome.Uncertain("an answer out of turn after END: $answer")
            }
        } finally {
            sending = null
            answers.cancel()   // before END, leaving the session sends ABORT (the link's rule)
        }
    }

    private fun abortOnce(ota: OtaChannel) {
        if (abortSent) return
        abortSent = true
        ota.abort()
    }

    /** The person's Cancel: ABORT, then the piano's ABORTED (or its silence, [ABORT_TIMEOUT_MS]). */
    private suspend fun stop(ota: OtaChannel, answers: ReceiveChannel<OtaEvent>): Outcome {
        abortOnce(ota)
        while (true) {
            val answer = next(answers, ABORT_TIMEOUT_MS) ?: return Outcome.Cancelled
            if (answer.ends) return Outcome.Cancelled
        }
    }

    /** Why the session did not go on, from the piano's [answer] (null: it said nothing in time). */
    private suspend fun refusal(answer: OtaEvent?, ota: OtaChannel, answers: ReceiveChannel<OtaEvent>, where: String): Outcome = when (answer) {
        is OtaEvent.Error -> {
            val code = answer.code
            val hint = when (code) {
                SAFE_MODE -> FirmwareFailures.SAFE_MODE_HINT
                BAD_SIGNATURE, IMAGE_INVALID -> FirmwareFailures.REFUSED_HINT
                else -> null
            }
            Outcome.Refused("ERR $code (${OtaFrames.errorName(code)}) $where", retryable = code !in NOT_RETRYABLE, hint = hint)
        }
        is OtaEvent.Lost -> Outcome.Refused("the session ended $where: ${answer.reason}", retryable = true)
        OtaEvent.Aborted -> if (cancelRequested) Outcome.Cancelled else Outcome.Refused("the piano aborted $where", retryable = true)
        null -> {
            if (cancelRequested) {
                stop(ota, answers)
            } else {
                abortOnce(ota)
                Outcome.Refused("the piano stopped answering $where", retryable = true)
            }
        }
        else -> {
            abortOnce(ota)
            Outcome.Refused("an answer out of turn $where: $answer", retryable = true)
        }
    }

    /** The piano's next answer, null when none came within [timeoutMs]; the end of the session is [OtaEvent.Lost]. */
    private suspend fun next(answers: ReceiveChannel<OtaEvent>, timeoutMs: Long): OtaEvent? {
        val result = if (timeoutMs <= 0) answers.tryReceive() else withTimeoutOrNull(timeoutMs) { answers.receiveCatching() } ?: return null
        if (result.isClosed) return OtaEvent.Lost("the session ended")
        return result.getOrNull()
    }

    // ---- After OK --------------------------------------------------------------------------

    /**
     * The piano restarts: the link expects the drop; within [RESTART_WINDOW_MS] the piano must be
     * back (a new connection: a drop seen, or another epoch). Its version decides: the release's is
     * Done (confirming while `!ota` says pending), anything else a rollback. [uncertain]: the
     * session ended after END without OK (why), so the piano may not have restarted at all; then the
     * old version back means the update did not take, not that it rolled back.
     */
    private suspend fun comeBack(manifest: FirmwareManifest, old: FirmwareVersion?, uncertain: String?) {
        _state.value = FirmwareState.Restarting(manifest)
        val staleSettings = pianoState.value
        link.expectRestart(RESTART_WINDOW_MS)
        log(if (uncertain == null) "Firmware update: the piano verified ${manifest.version} and restarts" else "Firmware update: $uncertain; waiting for the piano")
        val epochBefore = (link.state.value as? LinkState.Connected)?.epoch
        var dropped = false
        val back = withTimeoutOrNull(RESTART_WINDOW_MS) {
            link.state.first { state ->
                if (state !is LinkState.Connected) {
                    dropped = true
                    false
                } else {
                    dropped || state.epoch != epochBefore
                }
            }
        }
        if (back == null) {
            log("Firmware update: the piano did not come back within ${RESTART_WINDOW_MS / 1000} s")
            fail(FirmwareState.Failed(FirmwareFailures.DIDNT_COME_BACK, retryable = false, manifest = manifest))
            watchReturn(manifest, old, uncertain != null)
            return
        }
        report(manifest, old, uncertain != null, staleSettings)
    }

    /** The piano is back: what it runs, and whether it has confirmed it. */
    private suspend fun report(manifest: FirmwareManifest, old: FirmwareVersion?, uncertain: Boolean, staleSettings: PianoState?) {
        val expected = manifest.parsedVersion
        val text = link.firmwareVersion.value
        val reported = FirmwareVersion.parse(text)
        if (reported == null || !reported.sameRelease(expected)) {
            val shown = reported?.release ?: old?.release ?: "no version"
            if (uncertain) {
                log("Firmware update: the piano is back running ${text ?: "no version"}: the update did not take")
                fail(FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, retryable = true, manifest = manifest))
            } else {
                log("Firmware update: the piano came back running ${text ?: "no version"}: it rolled back")
                fail(FirmwareState.Failed(FirmwareFailures.rolledBack(shown), retryable = true, manifest = manifest, rolledBack = true))
            }
            return
        }
        verified = null
        val ota = otaFact(staleSettings)
        log("Firmware update: the piano runs $text" + (ota?.let { ", !ota $it" } ?: ""))
        val confirming = ota == PENDING
        _state.value = FirmwareState.Done(expected.release, confirming)
        if (confirming) confirm(manifest)
    }

    /**
     * `!ota` from the settings dump of the new connection ([FACT_TIMEOUT_MS] at most; never the
     * [stale] report of the connection before the restart); null when the piano has no console or
     * no such fact.
     */
    private suspend fun otaFact(stale: PianoState?): String? {
        val settled = withTimeoutOrNull(FACT_TIMEOUT_MS) {
            pianoState.first { it !== stale && (it is PianoState.Ready || it == PianoState.Unsupported) }
        }
        return (settled as? PianoState.Ready)?.facts?.get(OTA_FACT)
    }

    /**
     * While the new image is `pending`: `!ota` read again every [CONFIRM_REREAD_MS] for
     * [CONFIRM_WINDOW_MS]; `confirmed` makes it Done, the old version back (a reset before the
     * self-test passed) a rollback.
     */
    private fun confirm(manifest: FirmwareManifest) {
        val expected = manifest.parsedVersion
        confirmJob?.cancel()
        confirmJob = scope.launch {
            val until = now() + CONFIRM_WINDOW_MS
            while (now() < until) {
                delay(CONFIRM_REREAD_MS)
                if (_state.value != FirmwareState.Done(expected.release, confirming = true)) return@launch
                if (link.state.value !is LinkState.Connected) continue
                val running = FirmwareVersion.parse(link.firmwareVersion.value)
                if (running == null || !running.sameRelease(expected)) {
                    val shown = running?.release ?: "no version"
                    log("Firmware update: the piano reports $shown after restarting again: ${manifest.version} rolled back")
                    _state.compareAndSet(
                        FirmwareState.Done(expected.release, confirming = true),
                        FirmwareState.Failed(FirmwareFailures.rolledBack(shown), retryable = true, manifest = manifest, rolledBack = true),
                    )
                    return@launch
                }
                readFact(OTA_FACT)
                val settled = withTimeoutOrNull(FACT_TIMEOUT_MS) {
                    pianoState.map { (it as? PianoState.Ready)?.facts?.get(OTA_FACT) }.first { it != null && it != PENDING }
                }
                if (settled != null) {
                    log("Firmware update: ${manifest.version} confirmed (!ota $settled)")
                    _state.compareAndSet(FirmwareState.Done(expected.release, confirming = true), FirmwareState.Done(expected.release))
                    return@launch
                }
            }
            log("Firmware update: ${manifest.version} still not confirmed after ${CONFIRM_WINDOW_MS / 60_000} minutes")
        }
    }

    /** The piano did not come back in time: its next connection (within [WATCH_RETURN_MS]) still says how the update went. */
    private fun watchReturn(manifest: FirmwareManifest, old: FirmwareVersion?, uncertain: Boolean) {
        confirmJob?.cancel()
        confirmJob = scope.launch {
            withTimeoutOrNull(WATCH_RETURN_MS) {
                link.state.first { it is LinkState.Connected }
                val current = _state.value
                if (current is FirmwareState.Failed && current.message == FirmwareFailures.DIDNT_COME_BACK && current.manifest == manifest) {
                    report(manifest, old, uncertain, staleSettings = null)
                }
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private fun fail(failed: FirmwareState.Failed) {
        _state.value = failed
    }

    /** [next], unless a transfer began while the check was out. */
    private fun settle(next: FirmwareState) = _state.update { current -> if (current.busy) current else next }

    /** A release against the piano's version: newer is on offer (as far as this app can send it), the same or older up to date. */
    private fun weigh(manifest: FirmwareManifest, running: FirmwareVersion?): FirmwareState {
        if (running != null && manifest.parsedVersion <= running) return FirmwareState.UpToDate(running.release)
        return when {
            manifest.usbOnly -> FirmwareState.UsbOnly(manifest)
            manifest.needsNewerApp(appVersionCode) -> FirmwareState.NeedsNewerApp(manifest)
            else -> FirmwareState.Available(manifest)
        }
    }

    /** A piano connected with a version: what was on offer (or up to date) is weighed again against it. */
    private fun reweigh(piano: FirmwarePiano) {
        val manifest = lastManifest ?: return
        if (piano !is FirmwarePiano.Connected || piano.version == null) return
        _state.update { current ->
            when (current) {
                is FirmwareState.Available, is FirmwareState.UsbOnly, is FirmwareState.NeedsNewerApp, is FirmwareState.UpToDate -> weigh(manifest, piano.version)
                else -> current
            }
        }
    }

    private fun describe(state: FirmwareState, piano: FirmwarePiano.Connected): String {
        val running = piano.text ?: "no version"
        return when (state) {
            is FirmwareState.UpToDate -> "up to date (the piano runs $running)"
            is FirmwareState.UsbOnly -> "${state.manifest.version} needs a USB flash (the piano runs $running)"
            is FirmwareState.NeedsNewerApp -> "${state.manifest.version} needs app versionCode ${state.manifest.minAppVersionCode} (the piano runs $running)"
            else -> "${state.manifest?.version} is available (the piano runs $running)"
        }
    }

    private fun pianoOf(state: LinkState, version: String?): FirmwarePiano =
        if (state !is LinkState.Connected) {
            FirmwarePiano.NotConnected
        } else {
            FirmwarePiano.Connected(version, FirmwareVersion.parse(version), updatable = link.ota != null)
        }

    companion object {
        /** What Now playing's banner says while the player is locked. */
        const val PLAYER_LOCKED = "Updating the piano"

        /** Between the stop sequence having been written and BEGIN: the piano asks for 500 ms of quiet (ERR 1); 100 ms spare for the air. */
        const val QUIET_MS = 600L
        const val STOP_TIMEOUT_MS = 1_000L
        const val READY_TIMEOUT_MS = 10_000L

        /** Longer than the piano's own 10 s, so its ERR 9 arrives first when the silence is its. */
        const val ACK_TIMEOUT_MS = 15_000L
        const val VERIFY_TIMEOUT_MS = 30_000L
        const val ABORT_TIMEOUT_MS = 3_000L

        /** After OK, the piano is back within this, or it is reported as not come back. */
        const val RESTART_WINDOW_MS = 60_000L
        const val FACT_TIMEOUT_MS = 5_000L
        const val CONFIRM_REREAD_MS = 30_000L
        const val CONFIRM_WINDOW_MS = 5 * 60_000L
        const val WATCH_RETURN_MS = 10 * 60_000L

        /** One automatic check a day. */
        const val INTERVAL_MS = 24L * 60 * 60 * 1000

        /** The Firmware page asks again on opening once this long has passed since the last check. */
        const val OPEN_CHECK_EVERY_MS = 10 * 60_000L

        /** A due automatic check that could not ask is tried again after this. */
        const val SCHEDULE_RETRY_MS = 60_000L
        const val MIN_BATTERY_PERCENT = 20
        private const val PROGRESS_BYTES = 32 * 1024L
        private const val OTA_FACT = "ota"
        private const val PENDING = "pending"
        private const val SAFE_MODE = 10
        private const val BAD_SIGNATURE = 6
        private const val IMAGE_INVALID = 8

        /** ERRs Retry would not mend: too large, a bad signature, an invalid image. */
        private val NOT_RETRYABLE = setOf(2, BAD_SIGNATURE, IMAGE_INVALID)
    }
}
