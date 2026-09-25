// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Roll cards kept on disk, so a card is drawn from its piece's file once, not every time a tile
 * scrolls into view after the memory cache let it go: `<dir>/v1-<pieceId>.a8.gz`, the card's
 * [RollCard.SIZE]² alpha bytes gzipped (a few kilobytes: most of a card is paper). The directory
 * is the app's cache, which Android may clear; a card is then simply drawn again. Pieces never
 * change their notes and ids are never reused, so a card never goes stale. Written atomically;
 * anything unreadable reads as no card. Blocking I/O.
 */
class RollCardFiles(private val dir: File) {
    /** The card for [pieceId], or null when there is none (or it cannot be read). */
    fun read(pieceId: Long): ByteArray? {
        val file = fileFor(pieceId)
        if (!file.isFile) return null
        return try {
            val bytes = file.inputStream().use { raw ->
                GZIPInputStream(raw).use { input ->
                    val out = ByteArrayOutputStream(CARD_BYTES)
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > CARD_BYTES) return null   // not a card of ours
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
            bytes.takeIf { it.size == CARD_BYTES }
        } catch (e: IOException) {
            null
        }
    }

    /** Keeps [alpha] as [pieceId]'s card. A failure (a full disk) only means drawing it again next time. */
    fun write(pieceId: Long, alpha: ByteArray) {
        if (alpha.size != CARD_BYTES) return
        try {
            dir.mkdirs()
            val target = fileFor(pieceId)
            val partial = File(dir, "${target.name}.part")
            partial.outputStream().use { raw -> GZIPOutputStream(raw).use { it.write(alpha) } }
            if (!partial.renameTo(target)) partial.delete()
        } catch (e: IOException) {
            // The card is still shown; it is drawn again when next needed.
        }
    }

    /** The piece is gone: so is its card. */
    fun delete(pieceId: Long) {
        fileFor(pieceId).delete()
    }

    private fun fileFor(pieceId: Long) = File(dir, "$VERSION-$pieceId.a8.gz")

    companion object {
        /** Bumped when [RollCard] draws differently, so old cards are not shown. */
        private const val VERSION = "v1"
        const val CARD_BYTES = RollCard.SIZE * RollCard.SIZE
    }
}
