// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.channels.ChannelPlayer
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.schedule.QuietGate
import dev.stevenjin.stevenpiano.service.PlaybackService

/**
 * Playback started by a tap in the app: a library row, a playlist's Play or Shuffle, a channel's
 * card, a row menu's Play next or Add to queue, or the transport. Anything that may start
 * the piano also starts the playback service, which must be started from the foreground; the
 * first time, the notification permission is asked for (API 33+), in context. During a quiet time
 * (v1.20 — M54, [quiet]) a play waits as [held] for the person's choice: Play anyway ([playAnyway])
 * lifts the quiet and plays it, Cancel ([dismissHeld]) forgets it. Play next and Add to queue never
 * wait: what they add waits in Up next, loaded, for Play.
 */
class PlaybackStarter(
    private val context: Context,
    private val player: Player,
    private val channels: ChannelPlayer,
    private val quiet: QuietGate,
    private val askForNotifications: () -> Unit,
) {
    /** A piece started from a library row or tile (v1.14 — motion): the now-playing art grows in when it shows it. */
    val artEntrance = ArtEntrance()

    /** A play asked for while a quiet time holds the piano, waiting for the person's choice; null when none waits. */
    var held: (() -> Unit)? by mutableStateOf(null)
        private set

    fun play(pieceId: Long, queue: List<Long>) = unlessQuiet {
        player.play(pieceId, queue)
        started()
    }

    /** A playlist's (or a composer's) Play, in order, or Shuffle. */
    fun playAll(pieceIds: List<Long>, shuffle: Boolean) {
        if (pieceIds.isEmpty()) return
        unlessQuiet {
            player.playAll(pieceIds, shuffle)
            started()
        }
    }

    /**
     * Channel [key], endlessly, from a fresh shuffle of its pool (a card's tap). False when its pool is too small to play;
     * true too while it waits for the quiet time's choice.
     */
    fun playChannel(key: String): Boolean {
        if (quiet.holds()) {
            held = { if (channels.play(key)) started() }
            return true
        }
        if (!channels.play(key)) return false
        started()
        return true
    }

    /** Right after the current piece; with nothing queued yet it plays now. */
    fun playNext(pieceIds: List<Long>) {
        if (player.playNext(pieceIds)) started()
    }

    /** At the end of the queue; with nothing queued yet it plays now. */
    fun addToQueue(pieceIds: List<Long>) {
        if (player.addToQueue(pieceIds)) started()
    }

    fun togglePlayPause() {
        if (player.state.value.status == PlaybackStatus.Playing) {
            player.pause()
        } else {
            unlessQuiet {
                player.resume()
                started()
            }
        }
    }

    fun previous() = unlessQuiet {
        player.previous()
        started()
    }

    fun next() = unlessQuiet {
        player.next()
        started()
    }

    /** The play that waited for the quiet time's choice, taken (none waits after it). */
    fun takeHeld(): (() -> Unit)? = held.also { held = null }

    /** Cancel: the play that waited is forgotten; nothing plays. */
    fun dismissHeld() {
        held = null
    }

    /**
     * Play anyway (v1.20 — M54): the quiet lifts until its block ends ([QuietGate.override]), then [action] runs (the play
     * that waited for the choice); with none, a piece loaded and not playing plays.
     */
    fun playAnyway(action: (() -> Unit)? = null) {
        quiet.override()
        if (action != null) {
            action()
            return
        }
        val state = player.state.value
        if (state.piece != null && state.status != PlaybackStatus.Playing) {
            player.resume()
            started()
        }
    }

    /** [start] now; while a quiet time holds the piano it waits as [held] for the person's choice instead. */
    fun whenAllowed(start: () -> Unit) {
        if (quiet.holds()) held = start else start()
    }

    private fun unlessQuiet(start: () -> Unit) = whenAllowed(start)

    private fun started() {
        askForNotifications()
        PlaybackService.start(context)
    }

    companion object {
        /** Asked once per process at most; Android itself stops asking after two refusals. */
        private var askedForNotifications = false

        fun shouldAskForNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedForNotifications &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        fun markAsked() {
            askedForNotifications = true
        }
    }
}

/**
 * The now-playing art's entrance (DESIGN.md › v1.14 — motion): [ask]ed for when a piece starts from a library row or
 * a tile, [take]n by the art the first time it shows a new piece, if that is within [WINDOW_MS] of the ask (the piece
 * loading). A piece that follows by itself, Next, or a piece started anywhere else just appears.
 */
class ArtEntrance(private val now: () -> Long = SystemClock::uptimeMillis) {
    private var askedAt: Long? = null

    fun ask() {
        askedAt = now()
    }

    /** Whether the art showing a new piece grows in; the ask is spent either way. */
    fun take(): Boolean {
        val asked = askedAt ?: return false
        askedAt = null
        return now() - asked <= WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 3_000L
    }
}

@Composable
fun rememberPlaybackStarter(): PlaybackStarter {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }   // playback goes on either way
    return remember(context) {
        PlaybackStarter(context.applicationContext, context.graph.player, context.graph.channelPlayer, context.graph.quiet) {
            if (PlaybackStarter.shouldAskForNotifications(context)) {
                PlaybackStarter.markAsked()
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
