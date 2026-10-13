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
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.stevenjin.stevenpiano.ble.EmulatedPianoLink
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.firmware.FakeFirmwareServer
import dev.stevenjin.stevenpiano.firmware.FakeOta
import dev.stevenjin.stevenpiano.firmware.FirmwareKeys
import dev.stevenjin.stevenpiano.firmware.FirmwareStandIn
import dev.stevenjin.stevenpiano.instruments.CombinedMidiPorts
import dev.stevenjin.stevenpiano.instruments.EmulatedMidiPorts
import dev.stevenjin.stevenpiano.instruments.MidiDebugHooks
import dev.stevenjin.stevenpiano.instruments.MidiPorts
import dev.stevenjin.stevenpiano.library.LibraryOverride
import dev.stevenjin.stevenpiano.update.ModelsOverride
import dev.stevenjin.stevenpiano.update.UpdateOverride
import dev.stevenjin.stevenpiano.update.UpdateSource

/**
 * The emulator's stand-ins, debug builds only (v1.24 — M57): the one seam main code asks, with the same API in
 * `src/release` answering "none" everywhere, so a release build carries none of them. On Android's emulator (goldfish or
 * ranchu hardware, or a generic build) the piano is [EmulatedPianoLink], the MIDI devices gain an emulated keyboard and
 * MIDI piano ([EmulatedMidiPorts]), the firmware update can be a [FakeOta] scenario, the app's updates, Studio's models
 * and the library pack can come from a server on the Mac (`debug.stevenpiano.updateurl`, `…modelsurl`, `…libraryurl`),
 * and adb's intents set a value, crash the app or play MIDI ([onIntent]). On a phone or tablet a debug build is inert,
 * as release is.
 */
object Emulator {
    /** A debug build on Android's emulator, which has no piano to reach. */
    val on: Boolean by lazy {
        Build.HARDWARE in setOf("goldfish", "ranchu") || Build.FINGERPRINT?.startsWith("generic") == true
    }

    /** The emulated keyboard and MIDI piano, once [midiPorts] made them: adb's MIDI intents play through them. */
    @Volatile
    private var midi: EmulatedMidiPorts? = null

    /** The piano's link on the emulator ([EmulatedPianoLink]), or null for the real one. */
    fun pianoLink(): PianoLink? = if (on) EmulatedPianoLink() else null

    /** Android's MIDI ports [android], with the emulator's keyboard and MIDI piano beside them (callbacks on [handler]'s thread). */
    fun midiPorts(android: MidiPorts, handler: Handler): MidiPorts {
        if (!on) return android
        val emulated = EmulatedMidiPorts(handler).also { midi = it }
        return CombinedMidiPorts(android, emulated)
    }

    /** The firmware update `debug.stevenpiano.fakeota` names: its fake server and RFC 8032's test key; null without one. */
    fun firmware(): FirmwareStandIn? {
        FakeOta.fromProperty() ?: return null
        return FirmwareStandIn(FakeFirmwareServer(FakeOta::fromProperty), FirmwareKeys.rfc8032Test)
    }

    /** The app's updates from the server `debug.stevenpiano.updateurl` names, or null for GitHub. */
    fun updateSource(): UpdateSource? = UpdateOverride.source()

    /** Studio's models and the piano sound from the server `debug.stevenpiano.modelsurl` names, or null for GitHub. */
    fun modelsSource(): UpdateSource? = ModelsOverride.source()

    /** The library pack's manifest from the server `debug.stevenpiano.libraryurl` names, or null for GitHub. */
    fun librarySource(): UpdateSource? = LibraryOverride.source()

    /**
     * adb's intents to the activity, on the emulator only:
     * - `--es dev.stevenjin.stevenpiano.EMULATOR_SET "ledbright 300"` sets a value past the controls' ranges, so a refusal
     *   can be seen;
     * - `--ez …EMULATOR_CRASH true` crashes the app on the main thread, outside the activity's guard, so the crash handler,
     *   the crash report and the next launch's banner can be seen;
     * - `--es …EMULATOR_MIDI "90 3C 64 80 3C 00"` plays those bytes (malformed ones too, as they are) from the emulated
     *   keyboard; `--ez …EMULATOR_MIDI_PLUG false` unplugs it and `true` plugs it in again; `--es …EMULATOR_MIDI_SERVICE
     *   "…"` plays bytes from the debug build's test device through Android's own MIDI service (v1.11 — M29).
     */
    fun onIntent(graph: AppGraph, intent: Intent) {
        if (!on) return
        stringExtra(intent, EXTRA_SET)?.trim()?.let { line -> graph.pianoSettings.set(line.substringBefore(' '), line.substringAfter(' ', "")) }
        if (booleanExtra(intent, EXTRA_CRASH)) {
            Handler(Looper.getMainLooper()).post { throw IllegalStateException("A crash asked for on the emulator (debug build)") }
        }
        stringExtra(intent, EXTRA_MIDI)?.let { midi?.feed(EmulatedMidiPorts.hex(it)) }
        stringExtra(intent, EXTRA_MIDI_SERVICE)?.let { text -> MidiDebugHooks.testDeviceOutput?.invoke(EmulatedMidiPorts.hex(text)) }
        if (intent.hasExtra(EXTRA_MIDI_PLUG)) midi?.plug(booleanExtra(intent, EXTRA_MIDI_PLUG))
    }

    private fun stringExtra(intent: Intent, name: String): String? = runCatching { intent.getStringExtra(name) }.getOrNull()

    private fun booleanExtra(intent: Intent, name: String): Boolean = runCatching { intent.getBooleanExtra(name, false) }.getOrDefault(false)

    private const val EXTRA_SET = "dev.stevenjin.stevenpiano.EMULATOR_SET"
    private const val EXTRA_CRASH = "dev.stevenjin.stevenpiano.EMULATOR_CRASH"
    private const val EXTRA_MIDI = "dev.stevenjin.stevenpiano.EMULATOR_MIDI"
    private const val EXTRA_MIDI_SERVICE = "dev.stevenjin.stevenpiano.EMULATOR_MIDI_SERVICE"
    private const val EXTRA_MIDI_PLUG = "dev.stevenjin.stevenpiano.EMULATOR_MIDI_PLUG"
}
