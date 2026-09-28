// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import dev.stevenjin.stevenpiano.ble.OtaPiano
import dev.stevenjin.stevenpiano.update.UpdateServer
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * The emulator's firmware update (debug builds on an emulator only, as the other emulator hooks):
 * `adb shell setprop debug.stevenpiano.fakeota <scenario>`, then start the app. The emulated piano
 * ([LoggingPianoLink]) reports firmware 2.0.0 with the update service, the updater reads BLE_OTA.md
 * › 10's example release from [FakeFirmwareServer] instead of GitHub (§ 5's worked example image,
 * signed with RFC 8032's test key, which is then the key it trusts), and the piano's side answers
 * through [OtaPiano] as [script] says. Inert on a phone or tablet and in every release build, where
 * the updater trusts the author's key alone.
 */
enum class FakeOta(val key: String) {
    /** Everything goes through; the piano comes back on 2.1.0, pending its self-test, confirmed 25 s later. */
    Happy("happy"),

    /** ERR 1 at BEGIN: the piano wasn't quiet. */
    NotQuiet("err1"),

    /** The link drops after 40 windows; the piano is back 3 s later on its old firmware. */
    Disconnect("disconnect"),

    /** OK, but the piano comes back on 2.0.0: the new image rolled back. */
    Rollback("rollback"),

    /** ERR 5 after END: the image changed on the way. */
    HashMismatch("hash"),

    /** ERR 6 at BEGIN: the piano holds another key. */
    Signature("signature"),

    /** The release changes the partition table: USB only. */
    UsbOnly("usbonly"),

    /** The release asks for a newer app than this one. */
    NewerApp("newerapp"),

    /** The piano runs 2.1.0 already. */
    UpToDate("uptodate"),

    /** OK, and the piano never comes back. */
    NoComeBack("nocomeback"),

    /** Firmware older than 2.0.0: no version and no update service. */
    Old("old"),
    ;

    /** What the emulated piano reports before the update; null for [Old]. */
    val running: String?
        get() = when (this) {
            Old -> null
            UpToDate -> "2.1.0+a1b2c3d"
            else -> OtaExample.RUNNING
        }

    /** How the emulated piano's side answers. */
    val script: OtaPiano.Script
        get() = when (this) {
            NotQuiet -> OtaPiano.Script(errorAtBegin = 1)
            Disconnect -> OtaPiano.Script(publicKey = FirmwareKeys.rfc8032Test, dropAfterAcks = 40)
            HashMismatch -> OtaPiano.Script(publicKey = FirmwareKeys.rfc8032Test, hashMismatch = true)
            Signature -> OtaPiano.Script(publicKey = FirmwareKeys.author)
            else -> OtaPiano.Script(publicKey = FirmwareKeys.rfc8032Test)
        }

    /** After OK: the version the piano comes back with and its `!ota`; null when it never comes back. */
    val afterRestart: Pair<String, String>?
        get() = when (this) {
            Rollback -> OtaExample.RUNNING to "none"
            NoComeBack -> null
            else -> "2.1.0+a1b2c3d" to "pending"
        }

    /** The release the fake server offers: § 10's example, as the scenario changes it. */
    fun manifestJson(): String = OtaExample.manifestJson {
        when (this@FakeOta) {
            UsbOnly -> put("usbOnly", true)
            NewerApp -> put("minAppVersionCode", NEWER_APP)
            else -> Unit
        }
    }

    companion object {
        const val PROPERTY = "debug.stevenpiano.fakeota"
        private const val NEWER_APP = 99

        fun named(value: String?): FakeOta? = entries.firstOrNull { it.key == value?.trim() }

        /** The scenario [PROPERTY] names, in a debug build on an emulator; null everywhere else. */
        fun fromProperty(): FakeOta? {
            if (!LoggingPianoLink.isWanted()) return null
            val value = runCatching {
                ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            return named(value)
        }
    }
}

/**
 * The fake update server of [FakeOta] (debug builds on an emulator only): the scenario's manifest
 * after a moment, and § 5's image at [BYTES_PER_STEP] every [STEP_MS], so Downloading can be seen.
 * [scenario] is asked each time, so a new `setprop` needs only a new check.
 */
class FakeFirmwareServer(private val scenario: () -> FakeOta?) : UpdateServer {
    override suspend fun manifest(): String {
        delay(MANIFEST_MS)
        return (scenario() ?: throw IOException("No fake firmware scenario")).manifestJson()
    }

    override suspend fun download(url: String, cap: Long, sink: (ByteArray, Int) -> Unit) {
        if (url != OtaExample.BIN_URL) throw IOException("HTTP 404")
        val image = OtaExample.image()
        var at = 0
        while (at < image.size) {
            delay(STEP_MS)
            val end = minOf(image.size, at + BYTES_PER_STEP)
            val buffer = image.copyOfRange(at, end)
            sink(buffer, buffer.size)
            at = end
        }
    }

    private companion object {
        const val MANIFEST_MS = 400L
        const val STEP_MS = 100L
        const val BYTES_PER_STEP = 16 * 1024
    }
}
