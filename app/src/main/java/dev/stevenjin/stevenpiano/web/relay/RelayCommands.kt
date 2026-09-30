// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.library.LibraryFailures
import dev.stevenjin.stevenpiano.library.PackState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.web.ChannelStart
import dev.stevenjin.stevenpiano.web.SettingsChange
import dev.stevenjin.stevenpiano.web.Transport
import dev.stevenjin.stevenpiano.web.WebBackend

/** How a console command went: done or not, and a line the console shows. */
data class CommandResult(val ok: Boolean, val message: String)

/** Runs a console command by its name and arguments ([RelayCommands]; the tests' fakes). */
fun interface CommandHandler {
    suspend fun run(name: String, args: Map<String, Any?>): CommandResult
}

/**
 * The console's commands, as they reach the tablet on its own authenticated connection
 * (BUILD_SPEC.md › v1.10 — M26): an allow-list, [NAMES], each with exactly its arguments, checked
 * here again as the relay checks them (anything else is refused with a line saying why), and acted
 * on through the web panel's [backend] as the panel's own routes act: [TRANSPORT] (`action`, one of
 * the transport's buttons), [PLAY] (`pieceId`), [PLAY_CHANNEL] (`key`), [STOP_CHANNEL], [GUESTS]
 * (`open`, `approveFirst`: Guests can request and Approve requests first), [LIBRARY_LOAD] (Steven's
 * library, through [libraryLoad]: the web service passes the library pack's load, [Companion.libraryLoad];
 * without one, "not available"), and [STATUS] (a line of what plays; the relay client then reports its status at once). Every
 * command, and how it went, is a line of the link's [trail] (`LinkLog`): its name and its outcome,
 * never a title.
 */
class RelayCommands(
    private val backend: WebBackend,
    private val libraryLoad: (suspend () -> CommandResult)? = null,
    private val trail: (String) -> Unit = {},
) : CommandHandler {
    override suspend fun run(name: String, args: Map<String, Any?>): CommandResult {
        val result = try {
            act(name, args)
        } catch (e: Refused) {
            CommandResult(false, e.message)
        }
        trail("Cloud: the console sent ${if (name in NAMES) name else "an unknown command"}: ${if (result.ok) "done" else "not done"}")
        return result
    }

    private suspend fun act(name: String, args: Map<String, Any?>): CommandResult = when (name) {
        TRANSPORT -> {
            only(name, args, "action")
            val action = Transport.of(args["action"] as? String) ?: refuse("action must be one of ${Transport.entries.joinToString(", ") { it.key }}.")
            backend.transport(action)
            CommandResult(true, TRANSPORT_DONE.getValue(action))
        }
        PLAY -> {
            only(name, args, "pieceId")
            val id = RelayProtocol.whole(args["pieceId"])?.takeIf { it > 0 } ?: refuse("pieceId must be a whole number above 0.")
            if (backend.play(id, null)) CommandResult(true, "Playing.") else CommandResult(false, "That piece isn't in the tablet's library.")
        }
        PLAY_CHANNEL -> {
            only(name, args, "key")
            val key = (args["key"] as? String)?.takeIf(CHANNEL_KEY::matches) ?: refuse("key must name a channel.")
            when (backend.playChannel(key)) {
                ChannelStart.STARTED -> CommandResult(true, "Playing the channel.")
                ChannelStart.TOO_SMALL -> CommandResult(false, "That channel needs three pieces at least.")
                ChannelStart.UNKNOWN -> CommandResult(false, "The tablet has no such channel.")
            }
        }
        STOP_CHANNEL -> {
            only(name, args)
            backend.stopChannel()
            CommandResult(true, "The channel stopped.")
        }
        GUESTS -> {
            only(name, args, "open", "approveFirst")
            val open = flag(args, "open")
            val approveFirst = flag(args, "approveFirst")
            if (open == null && approveFirst == null) refuse("guests needs open or approveFirst.")
            backend.applySettings(SettingsChange(webGuests = open, webApproveFirst = approveFirst))
            val now = backend.guestSettings()
            CommandResult(true, if (!now.open) "Guests can't request." else if (now.approveFirst) "Guests can request; each waits for approval." else "Guests can request.")
        }
        LIBRARY_LOAD -> {
            only(name, args)
            libraryLoad?.invoke() ?: CommandResult(false, "Loading Steven's library isn't available on this tablet yet.")
        }
        STATUS -> {
            only(name, args)
            CommandResult(true, playing())
        }
        else -> CommandResult(false, "That isn't a command the tablet takes.")
    }

    /** "Playing · Clair de lune — Claude Debussy", "Paused · …", or "Idle". */
    private suspend fun playing(): String {
        val player = backend.state().player
        val piece = player.piece ?: return "Idle"
        val what = listOf(piece.title, piece.composer).filter { it.isNotBlank() }.joinToString(" — ")
        return when (player.status) {
            PlaybackStatus.Playing -> "Playing · $what"
            PlaybackStatus.Paused -> "Paused · $what"
            else -> "Idle"
        }
    }

    private fun only(name: String, args: Map<String, Any?>, vararg keys: String) {
        val extra = args.keys.firstOrNull { it !in keys } ?: return
        refuse("$name doesn't take \"${extra.take(40)}\".")
    }

    private fun flag(args: Map<String, Any?>, key: String): Boolean? {
        if (!args.containsKey(key) || args[key] == null) return null
        return args[key] as? Boolean ?: refuse("$key must be true or false.")
    }

    private fun refuse(message: String): Nothing = throw Refused(message)

    private class Refused(override val message: String) : Exception(message)

    companion object {
        const val TRANSPORT = "transport"
        const val PLAY = "play"
        const val PLAY_CHANNEL = "playChannel"
        const val STOP_CHANNEL = "stopChannel"
        const val GUESTS = "guests"
        const val LIBRARY_LOAD = "library.load"
        const val STATUS = "status"

        /** `library.load`'s answers: a load started, or one under way already. */
        const val LIBRARY_STARTED = "Loading Steven's library."
        const val LIBRARY_BUSY = "Steven's library is loading already."

        /**
         * The console's `library.load` on this tablet (v1.10: M26's command, M27's pack): the pack's [load]
         * as the + sheet's Update calls it (`everything = false`: a newer pack's new pieces, pieces a teacher
         * deleted staying deleted; on a tablet with none, the whole pack, without the licence sheet: the
         * owner's command), answered at once. A load under way ([state] busy): [LIBRARY_BUSY], nothing new
         * started; the tablet offline ([online] false): the library's own [LibraryFailures.OFFLINE], nothing
         * started (and no failure left on the tablet's screen); a load that could not start: its line. What
         * the load does then follows in the pack's state, and the version it brings in the status report.
         */
        fun libraryLoad(online: Boolean, state: () -> PackState, load: () -> Boolean): CommandResult = when {
            state().busy -> CommandResult(false, LIBRARY_BUSY)
            !online -> CommandResult(false, LibraryFailures.OFFLINE)
            load() -> CommandResult(true, LIBRARY_STARTED)
            else -> CommandResult(false, (state() as? PackState.Failed)?.line ?: LIBRARY_BUSY)
        }

        /** The commands the console may send, and no others. */
        val NAMES: Set<String> = setOf(TRANSPORT, PLAY, PLAY_CHANNEL, STOP_CHANNEL, GUESTS, LIBRARY_LOAD, STATUS)

        /** A channel's key, as the panel's route takes it. */
        private val CHANNEL_KEY = Regex("[a-z0-9_-]{1,40}")

        private val TRANSPORT_DONE = mapOf(
            Transport.TOGGLE to "Play or pause.",
            Transport.PAUSE to "Paused.",
            Transport.RESUME to "Playing.",
            Transport.NEXT to "Next piece.",
            Transport.PREVIOUS to "Previous piece.",
            Transport.STOP to "Stopped.",
        )
    }
}
