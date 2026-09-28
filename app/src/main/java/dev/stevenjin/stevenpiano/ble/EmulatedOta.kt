// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import android.os.Handler
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The emulated piano's update service ([LoggingPianoLink], debug builds on an emulator with a fake
 * update scenario): an [OtaPiano] answers each frame, and its answers arrive on [handler]'s thread
 * after the pauses a real piano takes (READY [READY_MS] after BEGIN, each window's ACK [ACK_MS] after
 * its last frame: about 13 KB a second, 75 s for the worked example; VERIFYING, then OK
 * [OK_MS] later). [onDrop] runs where the scenario drops the link; [onRestart] after OK. Call from
 * [handler]'s thread (the main thread, where the updater runs).
 */
class EmulatedOta(
    private val handler: Handler,
    private val script: OtaPiano.Script,
    private val onDrop: () -> Unit,
    private val onRestart: (inMs: Int) -> Unit,
) : OtaChannel {
    override val maxChunk: Int = OtaFrames.maxChunk(MTU)
    override val window: Int = OtaFrames.WINDOW

    private var piano = OtaPiano(MTU, script)
    private var out: SendChannel<OtaEvent>? = null
    private var ended = false

    override fun begin(header: OtaBegin): Flow<OtaEvent> = callbackFlow {
        val session = channel
        piano = OtaPiano(MTU, script)
        out = session
        ended = false
        answer(piano.control(OtaFrames.begin(header)))
        awaitClose {
            handler.post {
                if (out === session) {
                    if (!ended && !session.isClosedForSend) piano.control(OtaFrames.abort())
                    out = null
                }
            }
        }
    }

    override fun write(seq: Int, payload: ByteArray): Boolean {
        val session = out ?: return false
        if (session.isClosedForSend || ended || payload.isEmpty() || payload.size > maxChunk) return false
        answer(piano.data(OtaFrames.data(seq, payload)))
        return true
    }

    override fun end() {
        val session = out ?: return
        if (session.isClosedForSend || ended) return
        ended = true
        answer(piano.control(OtaFrames.end()))
    }

    override fun abort() {
        val session = out ?: return
        if (session.isClosedForSend || ended) return
        answer(piano.control(OtaFrames.abort()))
    }

    /** Each answer after its pause, in order. */
    private fun answer(outs: List<OtaPiano.Out>) {
        val session = out ?: return
        var at = 0L
        for (o in outs) {
            at += pauseBefore(o)
            handler.postDelayed({ deliver(session, o) }, at)
        }
    }

    private fun deliver(session: SendChannel<OtaEvent>, o: OtaPiano.Out) {
        when (o) {
            is OtaPiano.Out.Notify -> {
                if (session.isClosedForSend) return
                val event = OtaFrames.parse(o.bytes) ?: return
                session.trySend(event)
                if (event.ends) session.close()
            }
            OtaPiano.Out.Drop -> {
                session.trySend(OtaEvent.Lost("the connection to the piano ended"))
                session.close()
                onDrop()
            }
            is OtaPiano.Out.Restart -> onRestart(o.inMs)
        }
    }

    private fun pauseBefore(o: OtaPiano.Out): Long = when (o) {
        is OtaPiano.Out.Notify -> when (o.bytes[0].toInt() and 0xFF) {
            OtaFrames.READY -> READY_MS
            OtaFrames.ACK -> ACK_MS
            OtaFrames.VERIFYING -> VERIFYING_MS
            OtaFrames.OK -> OK_MS
            else -> ANSWER_MS
        }
        OtaPiano.Out.Drop -> 0L
        is OtaPiano.Out.Restart -> 0L
    }

    private companion object {
        const val MTU = 255
        const val READY_MS = 1_200L   // the stop, the verified OFF, Update.begin
        const val ACK_MS = 300L
        const val VERIFYING_MS = 300L
        const val OK_MS = 2_500L   // the digest, the signature, Update.end
        const val ANSWER_MS = 200L
    }
}
