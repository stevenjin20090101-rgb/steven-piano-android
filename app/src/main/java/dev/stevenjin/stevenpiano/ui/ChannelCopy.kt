// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.channels.ChannelSummary
import dev.stevenjin.stevenpiano.graph

/** What the channels say (DESIGN.md › v1.5 — M17). Pure, but for [rememberChannelName]. */
object ChannelCopy {
    /** The word after a channel's name wherever it names what is playing. */
    const val CHANNEL = "Channel"

    /** A card whose pool is too small to play says this instead of its count. */
    const val ADD_MORE = "Add more pieces"

    /** A card's eyebrow while its channel plays, beside the live dot. */
    const val PLAYING = "Playing"

    /** Schedule, in a card's menu, until schedules come (M19). */
    const val SCHEDULE_LATER = "Coming in the next update"

    /**
     * The composer line on Now playing and in the panel: the composer, and while a channel plays
     * its name and "Channel" ("Claude Debussy · Calm · Channel", set in capitals by the eyebrow).
     * An unknown composer leaves "Calm · Channel"; no channel, the composer alone.
     */
    fun eyebrow(composer: String, channel: String?): String =
        listOfNotNull(composer.trim().ifEmpty { null }, channel, channel?.let { CHANNEL }).joinToString(" · ")

    /** A card's eyebrow: "12 pieces", "Add more pieces" when the pool cannot play, or "Playing" while it does. */
    fun cardMeta(summary: ChannelSummary, playing: Boolean): String = when {
        playing -> PLAYING
        !summary.playable -> ADD_MORE
        else -> Format.count(summary.size, "piece", "pieces")
    }
}

/** The name of channel [key] ("Calm"), from the channels as last worked out; null for none, or before they are. */
@Composable
fun rememberChannelName(key: String?): String? {
    if (key == null) return null
    val channels by LocalContext.current.graph.channelPools.summaries.collectAsStateWithLifecycle()
    return channels?.firstOrNull { it.key == key }?.name
}
