// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.settings.StoredPin
import dev.stevenjin.stevenpiano.web.LoginGuard
import dev.stevenjin.stevenpiano.web.PinHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * What the kiosk knows of the tablet in this run of the app: whether the start-up checks are done
 * ([checked]: nothing locks before them), whether the app is the device owner ([owner]), "Unlock for
 * now" ([unlockedForNow]), whether Android kept the lock screen when kiosk mode came on
 * ([keyguardKept]: the tablet has a screen lock), and why kiosk mode couldn't come on ([problem]).
 */
data class KioskStatus(
    val checked: Boolean = false,
    val owner: Boolean = false,
    val unlockedForNow: Boolean = false,
    val keyguardKept: Boolean = false,
    val problem: String? = null,
)

/**
 * Kiosk mode for the school tablet (DESIGN.md › v1.6 — M20), one per process in `AppGraph`: turning
 * it on and off through the [controller] (Android's side) with the switch kept in the settings; the
 * kiosk PIN (a [PinHash] like the web panel's, kept apart from the settings) and its wrong tries
 * ([KioskPinGuard], kept across restarts); "Unlock for now", which lets go of the screen until the
 * app is next opened ([appLeft], [appOpened]) or rests in display mode ([relock]); and [lockWanted],
 * which the activity follows while it is in front, locking the screen to the app
 * (`startLockTask`) or letting go. [start] runs the adb way back first (`DeviceOwnerRelease`: the
 * kiosk ends before the device owner goes), then learns whether the app is the device owner.
 */
class KioskMode(
    private val controller: KioskController,
    private val settings: SettingsRepository,
    kioskEnabled: Flow<Boolean>,
    scope: CoroutineScope,
    private val releaseIfAsked: (endKiosk: () -> Unit) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hashing: CoroutineContext = Dispatchers.Default,
    private val letGoMs: Long = LET_GO_MS,
) {
    private val _status = MutableStateFlow(KioskStatus())
    val status: StateFlow<KioskStatus> = _status.asStateFlow()

    /** The screen locked to the app: kiosk mode on, the app the device owner, the start-up checks done, not unlocked for now. */
    val lockWanted: StateFlow<Boolean> = combine(status, kioskEnabled) { s, on -> on && s.checked && s.owner && !s.unlockedForNow }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    @Volatile
    private var guard = KioskPinGuard(clock)

    /** The app went to the background while unlocked for now: opening it again locks again. Main thread. */
    private var leftWhileUnlocked = false

    /**
     * As the app starts, off the main thread: the kept wrong tries come back; the adb way back
     * (`DeviceOwnerRelease`) ends the kiosk before the device owner goes; without the device owner
     * a home alias left on goes off and kiosk mode reads off; with it, kiosk mode on and the lock task
     * list lost (never expected: Android keeps it) is put back. Only then may the screen lock.
     */
    suspend fun start() {
        var problem: String? = null
        try {
            val (count, lockedUntil) = settings.kioskStrikes()
            guard = KioskPinGuard(clock, KioskPinGuard.Strikes(count, lockedUntil))
            val stayOnBefore = settings.kioskStayOnBefore() ?: 0
            if (releaseIfAsked { controller.disable(stayOnBefore) }) {
                settings.setKioskEnabled(false)
                settings.setKioskStayOnBefore(null)
            }
            val on = settings.settings.first().kioskEnabled
            if (!controller.isDeviceOwner()) {
                controller.tidyWithoutOwner()
                if (on) settings.setKioskEnabled(false)
            } else if (on && !controller.lockTaskPermitted()) {
                if (controller.enable() is KioskResult.Refused) problem = REFUSED
            }
        } finally {
            // Whatever happened above, the kiosk goes on from what Android says now.
            val owner = controller.isDeviceOwner()
            _status.update { it.copy(checked = true, owner = owner, problem = problem) }
        }
    }

    /** Whether the app is the device owner now (the Kiosk page asks as it opens: `dpm set-device-owner` may have run meanwhile). */
    fun refresh() {
        val owner = controller.isDeviceOwner()
        _status.update { it.copy(owner = owner) }
    }

    /** Whether Android lets the app lock the screen to itself now. */
    fun lockTaskPermitted(): Boolean = controller.lockTaskPermitted()

    /** Kiosk mode on (the Kiosk page's switch): needs a PIN and the device owner; true when it came on. The activity then locks the screen. */
    suspend fun turnOn(): Boolean {
        if (settings.kioskPin() == null) return false
        val keptBefore = settings.kioskStayOnBefore()
        return when (val result = controller.enable()) {
            is KioskResult.On -> {
                if (keptBefore == null) settings.setKioskStayOnBefore(result.stayOnBefore)
                settings.setKioskEnabled(true)
                leftWhileUnlocked = false
                _status.update { it.copy(owner = true, unlockedForNow = false, keyguardKept = !result.keyguardOff, problem = null) }
                true
            }
            KioskResult.NotOwner -> {
                _status.update { it.copy(owner = false) }
                false
            }
            is KioskResult.Refused -> {
                _status.update { it.copy(problem = REFUSED) }
                false
            }
        }
    }

    /**
     * Kiosk mode off (after the right PIN): the screen lets go first and is waited for (at most
     * [letGoMs]), because Android closes a locked task whose package leaves the lock task list;
     * then everything [KioskController.enable] set is undone, "stay on while plugged in" as it was.
     */
    suspend fun turnOff() {
        _status.update { it.copy(unlockedForNow = true) }
        settings.setKioskEnabled(false)
        withTimeoutOrNull(letGoMs) { while (controller.isLocked()) delay(POLL_MS) }
        controller.disable(settings.kioskStayOnBefore() ?: 0)
        settings.setKioskStayOnBefore(null)
        leftWhileUnlocked = false
        _status.update { it.copy(unlockedForNow = false, keyguardKept = false, problem = null) }
    }

    /** How long before the next try is weighed, in milliseconds (the PIN sheet counts it down). */
    fun waitMs(): Long = guard.waitMs()

    /**
     * One try of the kiosk PIN, weighed off the main thread (PBKDF2 is slow on purpose) unless a
     * wait runs; the count is kept after each weighed try. Right, wrong with the wait now in force,
     * or refused while one runs, as the web panel's guard says it.
     */
    suspend fun check(pin: String): LoginGuard.Attempt = withContext(hashing) {
        val kept = settings.kioskPin()?.let { PinHash.restore(it.salt, it.hash) }
        val outcome = guard.attempt { kept != null && kept.matches(pin) }
        if (outcome !is LoginGuard.Attempt.Wait) guard.strikes.let { settings.setKioskStrikes(it.count, it.lockedUntil) }
        outcome
    }

    /** A new kiosk PIN, hashed off the main thread; the wrong tries counted against the old one are forgotten. */
    suspend fun setPin(pin: String) {
        val hash = withContext(hashing) { PinHash.create(pin) }
        settings.setKioskPin(StoredPin(hash.saltText, hash.hashText))
        guard.clear()
    }

    /** "Unlock for now" (after the right PIN): the screen lets go until the app is next opened, or rests in display mode. */
    fun unlockForNow() {
        leftWhileUnlocked = false
        _status.update { it.copy(unlockedForNow = true) }
    }

    /** Locked again: the Kiosk page's Lock again, display mode coming, the app opened again. Nothing while not unlocked. */
    fun relock() {
        leftWhileUnlocked = false
        if (_status.value.unlockedForNow) _status.update { it.copy(unlockedForNow = false) }
    }

    /** The activity stopped (not for a configuration change): while unlocked for now, the next [appOpened] locks again. */
    fun appLeft() {
        if (_status.value.unlockedForNow) leftWhileUnlocked = true
    }

    /** The activity started: back from elsewhere after "Unlock for now" means the unlock is over. */
    fun appOpened() {
        if (leftWhileUnlocked) relock()
    }

    companion object {
        /** How long [turnOff] waits for the activity to let go of the screen before undoing the policy anyway. */
        const val LET_GO_MS = 2_000L

        private const val POLL_MS = 50L

        /** A step of kiosk mode refused by Android (logged with its reason). */
        const val REFUSED = "Android refused kiosk mode."
    }
}
