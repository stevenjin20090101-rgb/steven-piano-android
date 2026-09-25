// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.MidiSink
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.midi.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The scheduler thread when a step throws, errors included (the v1.2 audit, F3): the piano is silenced and the thread lives on. */
class SchedulerTest {
    /** Records what reaches the piano; a normal send throws [failWith] when set (the stop sequence always gets through). */
    private class Sink : MidiSink {
        val sent = CopyOnWriteArrayList<String>()

        @Volatile
        var failWith: Throwable? = null

        override fun send(batch: MidiBatch, dropPending: Boolean) {
            if (!dropPending) failWith?.let { throw it }
            sent += batch.hex()
        }
    }

    private val sink = Sink()
    private val engine = PlaybackEngine(sink)
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val failed = CountDownLatch(1)
    private val scheduler = Scheduler(engine, NanoClock.System, afterStep = {}, prepareThread = {}, onError = {
        errors += it
        failed.countDown()
    })

    @Test
    fun `an Error thrown by a command stops playback, silencing the piano, and the thread goes on`() {
        scheduler.start()
        val boom = StackOverflowError("deep")
        scheduler.submitAndWait(2_000) { throw boom }
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf<Throwable>(boom), errors)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), sink.sent)
        assertEquals(PlaybackStatus.Stopped, engine.status)
        assertTrue("the scheduler still runs commands", scheduler.submitAndWait(2_000) { })
    }

    @Test
    fun `an Error while playing a piece calls fail, which stops and silences`() {
        val piece = SmfParser.parse(SmfBuilder(format = 0).track { noteOn(0, 60); noteOff(480, 60) }.build())
        sink.failWith = OutOfMemoryError("Java heap space")
        scheduler.start()
        scheduler.submit { now ->
            engine.load(piece, now)
            engine.play(now)   // the first advance sends the Note On, and the sink throws
        }
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertTrue(errors.single() is OutOfMemoryError)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), sink.sent)
        assertTrue(scheduler.submitAndWait(2_000) { })
        assertEquals(PlaybackStatus.Stopped, engine.status)
    }
}
