// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import ai.onnxruntime.OrtEnvironment
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Whether Studio runs on this device, and if not, why (DESIGN.md › v1.7 — M23). */
enum class StudioSupport {
    /** Not asked yet, or being asked: Studio's entries stay hidden meanwhile. */
    Checking,

    /** The runtime loads and the device has the memory: Studio is offered. */
    Available,

    /** ONNX Runtime's native library does not load here (not arm64, say): "Studio isn't available on this device". */
    NoRuntime,

    /** Less than [MemoryGate.OFFER_TOTAL_BYTES] of memory: "This tablet doesn't have enough memory for Studio". */
    TooLittleMemory,
}

/** What Android says of the device's memory ([ActivityManager.MemoryInfo]), as numbers. */
data class MemorySnapshot(val totalMem: Long, val availMem: Long, val threshold: Long, val lowMemory: Boolean)

/**
 * Studio's memory gates, from the spike's measurements (docs/STUDIO_SPIKE.md › Memory gates). Pure.
 *
 * - **Offering Studio at all**: `totalMem` ≥ [OFFER_TOTAL_BYTES] (2.5 GiB). The whole process peaks at
 *   0.68–0.74 GiB while transcribing, so a device sold with 3 GB passes (`totalMem` leaves out what the
 *   kernel and firmware keep).
 * - **Starting a transcription**: not `lowMemory`, and `availMem − threshold` ≥ [TRANSCRIPTION_FREE_BYTES]
 *   (900 MiB: the worst job measured, 576 MiB above the app's own resident set, and half as much again).
 *   Checked again before every window ([Transcriber]), so a transcription stops rather than pushes the
 *   player out of memory when something else takes it meanwhile.
 * - **Starting a composition** (v1.7 — M24): not `lowMemory`, and `availMem − threshold` ≥
 *   [COMPOSING_FREE_BYTES] (700 MiB: the worst measured, 442 MiB above the app's own resident set, and
 *   half as much again). While it runs, [canContinue] every 100 tokens and before each slide of its
 *   window ([dev.stevenjin.stevenpiano.studio.compose.Sampler]).
 */
object MemoryGate {
    /** 2.5 GiB. */
    const val OFFER_TOTAL_BYTES = 2_684_354_560L

    /** 900 MiB. */
    const val TRANSCRIPTION_FREE_BYTES = 943_718_400L

    /** 700 MiB. */
    const val COMPOSING_FREE_BYTES = 734_003_200L

    /**
     * While a transcription runs, the most of its free room it may have used before it stops: a
     * window costs little once the session is up, so less than [RUNNING_FREE_BYTES] free above the
     * threshold (or `lowMemory`) means something else is taking the memory.
     */
    const val RUNNING_FREE_BYTES = 128L * 1024 * 1024

    /** Whether Studio is offered on a device with [totalMem] bytes. */
    fun offered(totalMem: Long): Boolean = totalMem >= OFFER_TOTAL_BYTES

    /** Whether a transcription may start now. */
    fun canStart(memory: MemorySnapshot): Boolean =
        !memory.lowMemory && memory.availMem - memory.threshold >= TRANSCRIPTION_FREE_BYTES

    /** Whether a composition may start now. */
    fun canStartComposing(memory: MemorySnapshot): Boolean =
        !memory.lowMemory && memory.availMem - memory.threshold >= COMPOSING_FREE_BYTES

    /** Whether a job under way may go on: a transcription to its next window, a composition past its next hundred tokens. */
    fun canContinue(memory: MemorySnapshot): Boolean =
        !memory.lowMemory && memory.availMem - memory.threshold >= RUNNING_FREE_BYTES

    /**
     * Studio's support from the device's [totalMem] and whether the runtime [loads] (asked only when the
     * memory would do). [override] is the emulator's `debug.stevenpiano.studio` (debug builds only):
     * "noruntime" or "lowmem" play those devices.
     */
    fun support(totalMem: Long, loads: () -> Boolean, override: String? = null): StudioSupport = when {
        override == OVERRIDE_NO_RUNTIME -> StudioSupport.NoRuntime
        override == OVERRIDE_LOW_MEMORY || !offered(totalMem) -> StudioSupport.TooLittleMemory
        !loads() -> StudioSupport.NoRuntime
        else -> StudioSupport.Available
    }

    const val OVERRIDE_NO_RUNTIME = "noruntime"
    const val OVERRIDE_LOW_MEMORY = "lowmem"

    /** The emulator's stand-in for a busy tablet: every job finds too little memory free. */
    const val OVERRIDE_BUSY = "busy"
}

/**
 * Whether Studio runs here ([support]), asked once per process the first time something needs to know
 * ([check]: the Piano tab's hub, the Library's `+` sheet, the Studio page, the web panel): the device's
 * memory, then whether ONNX Runtime's native library loads (off the main thread; loading maps a
 * 28 MB library, so it is never done at start). [memory] reads Android's figures each time it is
 * called, for the per-job gates.
 */
class StudioAvailability(
    private val memoryReader: () -> MemorySnapshot,
    private val loadRuntime: () -> Boolean,
    private val override: () -> String?,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = {},
) {
    private val state = MutableStateFlow(StudioSupport.Checking)
    val support: StateFlow<StudioSupport> = state.asStateFlow()
    private val asked = AtomicBoolean(false)

    /** Asks once; later calls do nothing. */
    fun check() {
        if (!asked.compareAndSet(false, true)) return
        scope.launch(worker) {
            state.value = MemoryGate.support(memoryReader().totalMem, loadRuntime, override())
            log("Studio: ${state.value}")
        }
    }

    /** Android's memory figures now. */
    fun memory(): MemorySnapshot = memoryReader().let { now ->
        // The emulator's stand-in for a busy tablet (debug builds only): nothing free.
        if (override() == MemoryGate.OVERRIDE_BUSY) now.copy(availMem = now.threshold) else now
    }

    companion object {
        private const val TAG = "Studio"

        /** The emulator's override (debug builds on an emulator only): `adb shell setprop debug.stevenpiano.studio lowmem`. */
        const val PROPERTY = "debug.stevenpiano.studio"

        fun of(context: Context, scope: CoroutineScope): StudioAvailability {
            val activities = context.getSystemService(ActivityManager::class.java)
            return StudioAvailability(
                memoryReader = {
                    val info = ActivityManager.MemoryInfo().also { activities.getMemoryInfo(it) }
                    MemorySnapshot(info.totalMem, info.availMem, info.threshold, info.lowMemory)
                },
                loadRuntime = ::runtimeLoads,
                override = ::overrideProperty,
                scope = scope,
                log = { Log.i(TAG, it) },
            )
        }

        /**
         * Whether ONNX Runtime's native library loads: the environment is made once per process (the
         * library's own rule) and kept. Anything thrown, an [UnsatisfiedLinkError] above all, is a no.
         */
        private fun runtimeLoads(): Boolean = try {
            OrtEnvironment.getEnvironment()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "ONNX Runtime doesn't load here: ${e.javaClass.simpleName}")
            false
        }

        @Volatile
        private var cachedOverride: String? = null

        @Volatile
        private var overrideRead = false

        /** `debug.stevenpiano.studio`, read once per process, and only in debug builds on an emulator. */
        private fun overrideProperty(): String? {
            if (overrideRead) return cachedOverride
            val value = if (!LoggingPianoLink.isWanted()) {
                null
            } else {
                runCatching {
                    ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }
                }.getOrNull()?.ifEmpty { null }
            }
            cachedOverride = value
            overrideRead = true
            return value
        }
    }
}
