// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.web.FakeWebBackend
import dev.stevenjin.stevenpiano.web.WebPiece
import dev.stevenjin.stevenpiano.web.WebPlayer
import dev.stevenjin.stevenpiano.web.WebState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The console's commands: the allow-list with exactly its arguments, acted on through the panel's backend, each a line of the trail. */
class RelayCommandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val trail = mutableListOf<String>()

    private fun commands(backend: FakeWebBackend, libraryLoad: (suspend () -> CommandResult)? = null) =
        RelayCommands(backend, libraryLoad, trail = { trail += it })

    @Test
    fun `each command reaches the app as the panel's own route would`() = runBlocking {
        val backend = FakeWebBackend(tmp.newFolder())
        val commands = commands(backend)
        assertEquals(CommandResult(true, "Next piece."), commands.run("transport", mapOf("action" to "next")))
        assertEquals(CommandResult(true, "Playing."), commands.run("play", mapOf("pieceId" to 3)))
        assertEquals(CommandResult(false, "That piece isn't in the tablet's library."), commands.run("play", mapOf("pieceId" to 99L)))
        assertEquals(CommandResult(true, "Playing the channel."), commands.run("playChannel", mapOf("key" to "calm")))
        assertEquals(CommandResult(false, "That channel needs three pieces at least."), commands.run("playChannel", mapOf("key" to "tiny")))
        assertEquals(CommandResult(false, "The tablet has no such channel."), commands.run("playChannel", mapOf("key" to "none")))
        assertEquals(CommandResult(true, "The channel stopped."), commands.run("stopChannel", emptyMap()))
        assertTrue(commands.run("guests", mapOf("open" to true, "approveFirst" to false)).ok)
        assertEquals(
            listOf("transport next", "play 3 queue=null", "channel play calm", "channel stop"),
            backend.calls.filterNot { it.startsWith("settings") },
        )
        val settings = backend.calls.single { it.startsWith("settings") }
        assertTrue(settings, "webGuests=true" in settings && "webApproveFirst=false" in settings && "preRollMs=null" in settings)
    }

    @Test
    fun `status says what plays, and loading the library waits for the library pack`() = runBlocking {
        val backend = FakeWebBackend(tmp.newFolder())
        val commands = commands(backend)
        assertEquals(CommandResult(true, "Idle"), commands.run("status", emptyMap()))
        backend.state = WebState(player = WebPlayer(status = PlaybackStatus.Playing, piece = WebPiece(1, "Clair de lune", "Claude Debussy", "debussy", 300_000)))
        assertEquals(CommandResult(true, "Playing · Clair de lune — Claude Debussy"), commands.run("status", emptyMap()))
        backend.state = WebState(player = WebPlayer(status = PlaybackStatus.Paused, piece = WebPiece(1, "Clair de lune", "", "debussy", 300_000)))
        assertEquals(CommandResult(true, "Paused · Clair de lune"), commands.run("status", emptyMap()))
        assertEquals(CommandResult(false, "Loading Steven's library isn't available on this tablet yet."), commands.run("library.load", emptyMap()))
        val withPack = commands(backend) { CommandResult(true, "Loading Steven's library.") }
        assertEquals(CommandResult(true, "Loading Steven's library."), withPack.run("library.load", emptyMap()))
    }

    @Test
    fun `anything off the list, or with other arguments, is refused and reaches nothing`() = runBlocking {
        val backend = FakeWebBackend(tmp.newFolder())
        val commands = commands(backend)
        val refused = listOf(
            "reboot" to emptyMap<String, Any?>(),
            "transport" to mapOf("action" to "eject"),
            "transport" to mapOf("action" to "next", "then" to "stop"),
            "transport" to emptyMap(),
            "play" to mapOf("pieceId" to "3"),
            "play" to mapOf("pieceId" to 0),
            "play" to mapOf("pieceId" to 1.5),
            "playChannel" to mapOf("key" to "Calm Channel"),
            "playChannel" to mapOf("key" to "../x"),
            "stopChannel" to mapOf("now" to true),
            "guests" to emptyMap(),
            "guests" to mapOf("open" to "yes"),
            "guests" to mapOf("open" to true, "pin" to "482913"),
            "library.load" to mapOf("from" to "https://evil.example"),
            "status" to mapOf("verbose" to true),
        )
        for ((name, args) in refused) {
            val result = commands.run(name, args)
            assertFalse("$name $args", result.ok)
            assertTrue("$name $args: ${result.message}", result.message.isNotBlank())
        }
        assertEquals("transport doesn't take \"then\".", commands.run("transport", mapOf("action" to "next", "then" to "stop")).message)
        assertEquals("nothing reached the app", emptyList<String>(), backend.calls.toList())
    }

    @Test
    fun `every command is a line of the trail, its name and outcome only`() = runBlocking {
        val backend = FakeWebBackend(tmp.newFolder())
        backend.state = WebState(player = WebPlayer(status = PlaybackStatus.Playing, piece = WebPiece(1, "Clair de lune", "Claude Debussy", "debussy", 300_000)))
        val commands = commands(backend)
        commands.run("status", emptyMap())
        commands.run("transport", mapOf("action" to "eject"))
        commands.run("rm -rf /", emptyMap())
        assertEquals(
            listOf("Cloud: the console sent status: done", "Cloud: the console sent transport: not done", "Cloud: the console sent an unknown command: not done"),
            trail.toList(),
        )
        assertTrue("no title in the trail", trail.none { "Clair" in it })
    }
}
