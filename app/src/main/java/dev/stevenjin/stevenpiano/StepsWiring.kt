// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.os.SystemClock
import dev.stevenjin.stevenpiano.audio.StepsCalibration
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.piano.LoudnessSteps
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.power.RailPhase
import dev.stevenjin.stevenpiano.quiet.QuietCopy
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.StepsCopy

/**
 * The loudness steps' part of [AppGraph] (M83, piano firmware 2.1.5): the calibration, and the piano it strikes and writes,
 * through the piano's console ([dev.stevenjin.stevenpiano.piano.PianoSettingsRepository.converse]), the player's stop
 * sequence and the app's gates. Each `calstrike` the piano confirms counts for the 24 V's power watch as that key held for
 * its 100 ms and the release lag ([LoudnessSteps.heldSpan], [dev.stevenjin.stevenpiano.power.HeldKeys.struckOutside]); the
 * rail is wanted with its own reason (`RailKeeper.STEPS`, followed in [PowerWiring.startRail]) and awaited up first
 * ([SafetyWiring.stepsRail]). The graph makes it once; everything is lazy.
 */
internal class StepsWiring(private val graph: AppGraph) {
    /** Find the loudness steps: the microphone is the tablet's own (its permission alone), as the key test's is. */
    val steps: StepsCalibration by lazy {
        StepsCalibration(graph.appScope, StepsPiano(), graph.remote.listening, before = graph.stepsRail)
    }

    private inner class StepsPiano : StepsCalibration.Piano {
        override val connected: Boolean
            get() = graph.pianoLink.kind.value == InstrumentKind.StevenPiano && graph.pianoLink.state.value is LinkState.Connected

        override val steps: Boolean get() = LoudnessSteps.supported((graph.pianoSettings.state.value as? PianoState.Ready)?.facts)

        override val writable: Boolean get() = graph.pianoSettings.lock.value.writable

        override val playing: Boolean get() = graph.player.state.value.let { it.status == PlaybackStatus.Playing || it.loading }

        override fun quiet(): String? {
            val now = graph.quiet.now()
            val until = now.until?.takeIf { now.holds } ?: return null
            val zone = java.time.ZoneId.systemDefault()
            return QuietCopy.refusal(QuietCopy.until(QuietCopy.at(until, zone), QuietCopy.at(System.currentTimeMillis(), zone)))
        }

        override val guest: Boolean
            get() = graph.pianoSettings.lock.value.guestLeft(SystemClock.elapsedRealtime()) > 0 || graph.rail.guestUntil.value != null

        override val updating: Boolean get() = graph.player.locked || graph.firmwareUpdater.state.value.busy

        override val inFront: Boolean get() = graph.remote.listening.inFront.value

        override fun busy(): String? = if (graph.remote.keyTest.running || graph.safety.state.value.running) StepsCopy.BUSY else null

        override fun plugUnsafe(): String? = graph.rail.plugUnsafe()?.let(PowerCopy::unsafe)

        override val railUp: Boolean
            get() = !graph.railPower.available.value || graph.rail.phase.value.let { it == RailPhase.Up || it == RailPhase.Kept }

        override suspend fun send(line: String): String? {
            val sent = SystemClock.elapsedRealtime()
            val reply = graph.pianoSettings.converse(line) ?: return null
            val struck = LoudnessSteps.read(line, reply) as? LoudnessSteps.Reply.Struck
            if (struck != null) {
                val (from, to) = LoudnessSteps.heldSpan(sent, SystemClock.elapsedRealtime(), struck.ms)
                graph.heldKeys.struckOutside(from, to)
            }
            return reply
        }

        override fun stopSequence() = graph.player.allKeysOff()

        override fun muteTablet(on: Boolean) = graph.tabletSound.mute(on)

        override fun reread() = graph.pianoSettings.reread()
    }
}
