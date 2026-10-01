// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.debugmidi

import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver
import android.util.Log
import dev.stevenjin.stevenpiano.instruments.MidiDebugHooks

/**
 * DEBUG BUILDS ONLY (app/src/debug; release builds have neither the class nor its manifest entry, since any
 * app could send to it): a MIDI device of Android's own MIDI service, "Test MIDI device", so the emulator
 * exercises the real `MidiManager` path end to end (v1.11 — M29). Its output port plays what
 * `adb shell am start -n dev.stevenjin.stevenpiano/.MainActivity --es dev.stevenjin.stevenpiano.EMULATOR_MIDI_SERVICE
 * "90 3C 64"` gives it, as a keyboard would; its input port logs every byte sent to it (`adb logcat -s
 * TestMidiDevice`), as an instrument would play them. Android binds it when the app opens the device.
 */
class TestMidiDeviceService : MidiDeviceService() {
    private val input = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            Log.d(TAG, (offset until offset + count).joinToString(" ") { "%02X".format(msg[it].toInt() and 0xFF) })
        }
    }

    override fun onCreate() {
        super.onCreate()
        MidiDebugHooks.testDeviceOutput = { bytes -> runCatching { outputPortReceivers.firstOrNull()?.send(bytes, 0, bytes.size) } }
        Log.d(TAG, "Test MIDI device up")
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf(input)

    override fun onDestroy() {
        MidiDebugHooks.testDeviceOutput = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TestMidiDevice"
    }
}
