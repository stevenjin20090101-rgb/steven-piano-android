// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Whether a newer release exists, and the updater's [state] from there on (the download and the
 * installer [publish] theirs here). A check fetches the manifest from [server], reads it against
 * [source], and compares its versionCode with [currentVersionCode] (`BuildConfig.VERSION_CODE`):
 * higher is [UpdateState.Available] (unless the release needs a newer Android than [sdkInt]),
 * equal or lower is [UpdateState.UpToDate]. A check never replaces a download or an install
 * ([UpdateState.busy]); one that fails keeps the release already on offer, with the failure's line.
 *
 * Automatic checks ([runSchedule]) run while the app is in the foreground, the switch is on and
 * [online] says there is a network: at once if none has run in this process, then every
 * [INTERVAL_MS]. Offline or switched off, nothing is asked; the check runs when both come back, if
 * it is due. Check now ([checkNow]) ignores the switch. Checks run one at a time.
 */
class UpdateChecker(
    private val currentVersionCode: Int,
    private val sdkInt: Int,
    private val source: UpdateSource,
    private val server: UpdateServer,
    private val online: StateFlow<Boolean>,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val mutex = Mutex()

    /** When the server was last asked (automatic or not), by [now]; null before the first time in this process. */
    @Volatile
    var lastCheckedAt: Long? = null
        private set

    /** Asks the server now, whatever the switch says: the Piano tab's Check now. */
    suspend fun checkNow() = check(manual = true)

    /**
     * Automatic checks, for as long as the caller runs this (the activity, while started): whenever
     * [enabled] (the switch) is on and the device is online, a check when one is due, then one
     * every 24 hours. Turning the switch off or losing the network stops the wait; it starts again,
     * from the last check, when both are back.
     */
    suspend fun runSchedule(enabled: Flow<Boolean>) {
        combine(enabled, online) { on, connected -> on && connected }
            .distinctUntilChanged()
            .collectLatest { ready ->
                if (!ready) return@collectLatest
                while (true) {
                    delay(untilDue())
                    check(manual = false)
                }
            }
    }

    /** How long until an automatic check is due: none yet in this process, or the clock went back, is now. */
    fun untilDue(): Long {
        val last = lastCheckedAt ?: return 0L
        val at = now()
        if (at < last) return 0L
        return (last + INTERVAL_MS - at).coerceAtLeast(0L)
    }

    /** An update has just been installed and this is it: nothing to ask for another day (Check now still asks). */
    fun markChecked() {
        lastCheckedAt = now()
    }

    /** The download, the installer and the Piano tab move the state on from a release on offer. */
    fun publish(next: UpdateState) {
        _state.value = next
    }

    private suspend fun check(manual: Boolean) = mutex.withLock {
        val before = _state.value
        if (before.busy) {
            lastCheckedAt = now()   // counted, so the schedule waits a day rather than asking again at once
            return@withLock
        }
        if (manual && !online.value) {
            settle(UpdateState.Failed(UpdateFailures.OFFLINE, before.manifest))   // nothing asked: not counted
            return@withLock
        }
        lastCheckedAt = now()
        if (before.manifest == null) _state.compareAndSet(before, UpdateState.Checking)
        val result = try {
            outcome(UpdateManifest.parse(server.manifest(), source))
        } catch (e: CancellationException) {
            _state.compareAndSet(UpdateState.Checking, before)
            throw e
        } catch (e: InvalidManifest) {
            log("Update check: ${e.message}")
            UpdateState.Failed(UpdateFailures.UNREADABLE, before.manifest)
        } catch (e: IOException) {
            log("Update check failed: ${e.javaClass.simpleName}")
            UpdateState.Failed(UpdateFailures.UNREACHABLE, before.manifest)
        } catch (e: RuntimeException) {
            log("Update check failed: ${e.javaClass.simpleName}")
            UpdateState.Failed(UpdateFailures.UNREADABLE, before.manifest)
        }
        settle(result)
    }

    private fun outcome(manifest: UpdateManifest): UpdateState = when {
        manifest.versionCode <= currentVersionCode -> UpdateState.UpToDate
        manifest.minSdk > sdkInt -> UpdateState.Failed(UpdateFailures.needsNewerAndroid(manifest.versionName))
        else -> UpdateState.Available(manifest)
    }

    /** [next], unless a download or an install began while the check was out. */
    private fun settle(next: UpdateState) = _state.update { current -> if (current.busy) current else next }

    companion object {
        /** One automatic check a day while the app is in the foreground. */
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
