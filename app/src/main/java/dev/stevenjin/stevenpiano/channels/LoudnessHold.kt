// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.channels

import dev.stevenjin.stevenpiano.player.PlaybackLimits

/**
 * How loud the piano plays while something with a volume of its own plays (a channel, a schedule;
 * DESIGN.md › v1.5 — M17 and v1.5.2 — M19), and what comes back after. [hold] sets the loudness
 * for its owner: the piano's own volume when the piano offers one ([PianoVolume.hold], never saved
 * on the piano), else the app's velocity (50 + volume / 2 %). The first hold remembers what was
 * there; a later hold, by the same owner or another, takes over and keeps that memory, so what
 * comes back is always what was there before the first. [release] by the owner holding it puts
 * that back (the piano's volume with its Full power; the velocity only if the person has not
 * changed it meanwhile); a release by an owner another has taken over from changes nothing. One
 * per process, shared by the channels and the schedules; call on the main thread.
 */
class LoudnessHold(private val piano: PianoVolume, private val deck: ChannelDeck) {
    private var restore: Restore? = null
    private var owner: Any? = null

    /** Whoever holds the loudness now; null when nothing does. */
    val holder: Any? get() = owner

    /** [owner] sets the loudness to [pct] (0-100). */
    fun hold(owner: Any, pct: Int) {
        this.owner = owner
        val volume = pct.coerceIn(0, MAX_VOLUME)
        val pianoNow = piano.current()
        if (pianoNow != null) {
            if (restore == null) restore = Restore.Piano(pianoNow)
            piano.hold(volume)
        } else {
            val velocity = velocityFor(volume)
            if (restore == null) restore = Restore.Velocity(deck.state.value.velocityPct, velocity)
            (restore as? Restore.Velocity)?.applied = velocity
            deck.setVelocity(velocity)
        }
    }

    /** [owner]'s hold ends: what was there before the first hold comes back, unless another owner holds it now. */
    fun release(owner: Any) {
        if (this.owner !== owner) return
        when (val put = restore) {
            is Restore.Piano -> piano.release(put.loudness)
            is Restore.Velocity -> if (deck.state.value.velocityPct == put.applied) deck.setVelocity(put.pct)
            null -> Unit
        }
        restore = null
        this.owner = null
    }

    /** What a hold replaced, to put back when it ends. */
    private sealed interface Restore {
        class Piano(val loudness: PianoLoudness) : Restore

        class Velocity(val pct: Int, var applied: Int) : Restore
    }

    companion object {
        const val MAX_VOLUME = 100

        /** The app's velocity for a volume, where the piano has no volume of its own: 50 % to 100 %. */
        fun velocityFor(volume: Int): Int = (50 + volume.coerceIn(0, MAX_VOLUME) / 2).coerceIn(PlaybackLimits.VelocityPct)
    }
}
