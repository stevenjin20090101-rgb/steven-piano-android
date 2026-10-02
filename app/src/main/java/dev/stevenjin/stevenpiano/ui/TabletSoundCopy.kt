// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.audio.SoundDownload
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.audio.TabletSoundState
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.ModelEntry
import java.util.Locale

/**
 * What the app says about the tablet's piano sound (DESIGN.md › v1.8 — M25): the Tablet sound page (PLAYING,
 * its own page since v1.13), the speaker on Now playing and in the panel, its popover, and Now playing's note
 * while the sound waits for its download. One line each, in words, sentence case; nothing red. Its volume is
 * "Tablet volume" wherever it shows (v1.13: one name per thing).
 */
object TabletSoundCopy {
    const val EYEBROW = "Tablet sound"
    const val CHOICE = "Piano sound on the tablet"
    const val VOLUME = "Tablet volume"

    /** The chips, in [TabletSoundMode]'s order. */
    val MODES = listOf("Off", "When the piano isn't connected", "Always")

    fun mode(mode: TabletSoundMode): String = MODES[mode.ordinal]

    /** Under the chips: what the choice does. Always warns that the two can drift apart. */
    fun modeNote(mode: TabletSoundMode): String = when (mode) {
        TabletSoundMode.OFF -> "The tablet stays silent. Pieces play on the piano alone."
        TabletSoundMode.WHEN_NOT_CONNECTED -> "The tablet plays pieces and the Keys tab itself while the piano isn't connected."
        TabletSoundMode.ALWAYS -> "The tablet plays along with the piano. It may sound slightly early or late compared with the piano."
    }

    /** The SoundFont row's title. */
    fun title(model: ModelEntry = ModelCatalogue.pianoSound): String = model.title

    /** "57 MB · CC0 · FreePats". */
    fun facts(model: ModelEntry = ModelCatalogue.pianoSound): String = "${StudioCopy.size(model.sizeBytes)} · ${model.licenceLabel} · FreePats"

    /**
     * The SoundFont row's line: its size and licence with what it is; "Downloading · 12 of 57 MB";
     * "Installed · 57 MB · CC0"; or why the last download failed.
     */
    fun line(state: TabletSoundState, model: ModelEntry = ModelCatalogue.pianoSound, locale: Locale = Locale.getDefault()): String =
        when (val d = state.download) {
            is SoundDownload.Running -> "Downloading · ${StudioCopy.megabytes(d.bytes, d.total, locale)}"
            is SoundDownload.Failed -> d.line
            null -> if (state.installed) "Installed · ${StudioCopy.size(model.sizeBytes)} · ${model.licenceLabel}" else "${facts(model)} · ${model.use}"
        }

    /** The download's progress, 0–1, while it runs; null otherwise. */
    fun progress(state: TabletSoundState): Float? =
        (state.download as? SoundDownload.Running)?.let { if (it.total > 0) (it.bytes.toDouble() / it.total).toFloat().coerceIn(0f, 1f) else 0f }

    /**
     * The popover's line and Now playing's speaker's word for the sound now: sounding, waiting for the
     * piano to go (or for its download), or off.
     */
    fun status(state: TabletSoundState): String = when {
        state.mode == TabletSoundMode.OFF -> "Off. Piano › Tablet sound turns it on."
        state.needsDownload -> "The piano sound isn't on this tablet yet."
        state.active && state.mode == TabletSoundMode.ALWAYS -> "Playing on this tablet with the piano."
        state.active -> "Playing on this tablet while the piano isn't connected."
        else -> "Silent while the piano is connected."
    }

    /** Now playing's one-line note while the sound waits for its download (and while it downloads, or failed). */
    fun nowPlayingNote(state: TabletSoundState, model: ModelEntry = ModelCatalogue.pianoSound, locale: Locale = Locale.getDefault()): String? {
        if (!state.needsDownload) return null
        return when (val d = state.download) {
            is SoundDownload.Running -> "Downloading the piano sound · ${StudioCopy.megabytes(d.bytes, d.total, locale)}"
            is SoundDownload.Failed -> d.line
            null -> "Hear it on this tablet: the piano sound is a ${StudioCopy.size(model.sizeBytes)} download."
        }
    }

    /** The speaker's description for TalkBack: it opens the volume. */
    fun glyphDescription(state: TabletSoundState): String =
        if (state.active) "Tablet sound, volume ${Format.percent(state.volume)}" else "Tablet sound, off here, volume ${Format.percent(state.volume)}"

    /** The button's description when the download is offered. */
    fun downloadDescription(model: ModelEntry = ModelCatalogue.pianoSound): String = "Download the piano sound, ${StudioCopy.size(model.sizeBytes)}"

    /** About's credit. */
    const val CREDIT = "Piano sound: Upright Piano KW, FreePats (CC0)"
}
