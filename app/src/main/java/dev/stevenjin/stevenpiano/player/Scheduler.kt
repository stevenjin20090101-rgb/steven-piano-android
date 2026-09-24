// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import android.os.Process
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Monotonic nanoseconds, without boxing; tests drive their own. */
fun interface NanoClock {
    fun nanoTime(): Long

    companion object {
        val System = NanoClock { java.lang.System.nanoTime() }
    }
}

/**
 * The one thread that drives the [PlaybackEngine] ("steven-piano-scheduler", urgent-audio
 * priority). It sleeps in a timed poll until the next event is due or a command arrives,
 * so wake-ups are sub-millisecond and no coroutine delay slack reaches the piano.
 * Commands run here, given the time they run at; [afterStep] runs after every step.
 */
class Scheduler(
    private val engine: PlaybackEngine,
    private val clock: NanoClock,
    private val afterStep: () -> Unit,
    private val prepareThread: () -> Unit = UrgentAudio,
    private val onError: (Throwable) -> Unit = { Log.e("Scheduler", "Playback step failed; piano silenced", it) },
) {
    private val commands = LinkedBlockingQueue<(Long) -> Unit>()
    private val thread = Thread(::loop, "steven-piano-scheduler").apply { isDaemon = true }

    fun start() = thread.start()

    fun shutdown() = thread.interrupt()

    /** Runs [command] on the scheduler thread with the time it runs at. */
    fun submit(command: (nowNanos: Long) -> Unit) = commands.put(command)

    /** Like [submit], but waits up to [timeoutMs] for the command to have run; true when it did. */
    fun submitAndWait(timeoutMs: Long, command: (nowNanos: Long) -> Unit): Boolean {
        val done = CountDownLatch(1)
        submit { now ->
            try {
                command(now)
            } finally {
                done.countDown()
            }
        }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun loop() {
        prepareThread()
        try {
            while (true) {
                val wake = advance()
                val command = when (wake) {
                    Long.MAX_VALUE -> commands.take()
                    else -> (wake - clock.nanoTime()).let { wait -> if (wait <= 0) commands.poll() else commands.poll(wait, TimeUnit.NANOSECONDS) }
                }
                if (command != null) run(command)
            }
        } catch (e: InterruptedException) {
            // shut down
        }
    }

    private fun advance(): Long = try {
        engine.advance(clock.nanoTime())
    } catch (e: RuntimeException) {
        fail(e)
        Long.MAX_VALUE
    } finally {
        afterStep()
    }

    private fun run(command: (Long) -> Unit) = try {
        command(clock.nanoTime())
    } catch (e: RuntimeException) {
        fail(e)
    } finally {
        afterStep()
    }

    /** A failed step stops playback, which silences the piano. */
    private fun fail(e: RuntimeException) {
        onError(e)
        runCatching { engine.stop(clock.nanoTime()) }
    }

    companion object {
        /** Raises the calling thread to urgent-audio priority. */
        val UrgentAudio: () -> Unit = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
    }
}
