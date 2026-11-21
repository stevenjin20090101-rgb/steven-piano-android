// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

/**
 * A power board moved to another address on this one piano (M85, piano firmware 2.1.6; BLE_SETTINGS.md › `boardaddr`).
 * Steven, 2026-11-20: the new piano's C6 board has a dead A3 switch, so it answers at 0x4D, not 0x45; rather than solder,
 * "a little tick… device specific". The piano keeps the move in its own memory (never in a release; an update keeps it,
 * a factory reset keeps it too, as it keeps the lock's keys; only `default` undoes it), so it belongs to that piano alone, and the tablet only offers and shows it. Pure.
 *
 * - The fact `!boardaddrproto=1`; the dump's last row `boardaddr=40,41,42,43,44,4d,46` (each board's address in board
 *   order, lower-case hex); the fact `!strays=4d` (addresses that answer and are no board, the pedal node or All Call;
 *   empty with none), when the piano reports it.
 * - `boardaddr <board> <hex>` moves one, `boardaddr <board> default` puts it back at 0x40 + board. Answers
 *   `  boardaddr 5 = 0x4D: OK (it answers)` or `…: MISSING (nothing answers there)`; refusals hold "refused" or "usage:".
 */
object BoardAddress {
    const val PROTO = "boardaddrproto"
    const val KEY = "boardaddr"
    const val STRAYS = "strays"
    const val BOARDS = 7

    /** Board `n` lives at 0x40 + n unless moved. */
    const val BASE = 0x40

    fun supported(facts: Map<String, String>?): Boolean = facts?.get(PROTO)?.trim() == "1"

    fun default(board: Int): Int = BASE + board

    /** What the piano refuses to move a board to: the pedal node, All Call, the PCA9685's sub-addresses, I²C's reserved. */
    private val RESERVED: Set<Int> = setOf(0x47, 0x70, 0x71, 0x72, 0x74) + (0x78..0x7F)

    /**
     * Whether the piano would take [address] for a board (`boardaddr` takes 40..77, never [RESERVED]). `!strays` may list
     * an address it would refuse (a chip answering at 0x71), so only a usable one is ever offered.
     */
    fun usable(address: Int): Boolean = address in 0x40..0x77 && address !in RESERVED

    /** "C6" for board 5: the bottom C of its twelve keys, as the board view names it (MIDI 24 is C1). */
    fun name(board: Int): String = "C${board + 1}"

    /** "0x4D". */
    fun hex(address: Int): String = "0x%02X".format(address)

    /** `40,41,42,43,44,4d,46`: every board's address in board order; null unread or not seven hex numbers. */
    fun addresses(value: String?): List<Int>? {
        val parts = value?.trim()?.takeIf { it.isNotEmpty() }?.split(',')?.map { it.trim().toIntOrNull(16) } ?: return null
        if (parts.size != BOARDS || parts.any { it == null }) return null
        return parts.map { it!! }
    }

    /** The boards moved from their own address, by index, with where each lives now. */
    fun moved(addresses: List<Int>?): Map<Int, Int> =
        addresses.orEmpty().withIndex().filter { (board, address) -> address != default(board) }.associate { it.index to it.value }

    /** `!strays`: `4d` or `4d,5a`, empty with none; what isn't hex is left out. */
    fun strays(value: String?): List<Int> =
        value.orEmpty().split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull(16) }

    /** The boards `!boards` (`OK,OK,MISSING,…`) says don't answer, by index. */
    fun missing(boards: String?): Set<Int> =
        boards.orEmpty().split(',').withIndex().filter { (_, state) -> state.isNotBlank() && !state.trim().equals("ok", true) }
            .map { it.index }.toSet()

    /** What the tablet offers to tick: board [board] is the one answering at [address]. */
    data class Suggestion(val board: Int, val address: Int)

    /**
     * Whether to offer the tick, and for which board: from the boards that don't answer ([missing], by index), the
     * addresses that answer and belong to no board ([strays]), and every board's address now ([addresses]).
     * Null: offer nothing.
     *
     * Only when it can't be wrong: exactly one board missing and exactly one stray the piano would take ([usable]), no
     * board already there, and the stray at most one bit from the board's own address. A stuck switch flips one bit
     * (C6's dead A3: 0x45 to 0x4D); zero bits is the switch mended while the piano still looks elsewhere, offered back.
     * Anything else (two missing, two strays, a chip several bits away) is left to a person with the board in front of them.
     */
    fun suggest(missing: Set<Int>, strays: List<Int>, addresses: List<Int>): Suggestion? {
        val board = missing.singleOrNull()?.takeIf { it in 0 until BOARDS } ?: return null
        val stray = strays.filter(::usable).singleOrNull() ?: return null
        if (stray in addresses) return null
        if (Integer.bitCount(stray xor default(board)) > 1) return null
        return Suggestion(board, stray)
    }

    // ---- The lines and their replies -------------------------------------------------------------------------------

    /** `boardaddr 5 4d`. */
    fun move(board: Int, address: Int): String = "$KEY $board ${"%02x".format(address)}"

    /** `boardaddr 5 default`. */
    fun reset(board: Int): String = "$KEY $board default"

    private val LINE = Regex("""^boardaddr [0-6] ([0-9a-f]{2}|default)$""")

    /** One of this feature's lines (what the repository's [PianoSettingsRepository.converse] lets through). */
    fun ours(line: String): Boolean = LINE.matches(line)

    /** Whether [reply] answers [sent]: its own result line, or a refusal (the piano answers one line a command). */
    fun answers(sent: String, reply: String): Boolean {
        val text = reply.trim()
        val board = sent.split(' ').getOrNull(1) ?: return false
        return text.startsWith("$KEY $board =") || refused(text)
    }

    sealed interface Reply {
        /** Taken: the board at its new address, [answering] there or not. */
        data class Done(val answering: Boolean) : Reply

        /** Refused, in the piano's words. */
        data class Refused(val words: String) : Reply
    }

    fun read(reply: String): Reply {
        val text = reply.trim()
        return if (refused(text)) Reply.Refused(text) else Reply.Done(answering = text.contains(": OK"))
    }

    private fun refused(text: String): Boolean = text.contains("refused", true) || text.contains("usage:", true)
}
