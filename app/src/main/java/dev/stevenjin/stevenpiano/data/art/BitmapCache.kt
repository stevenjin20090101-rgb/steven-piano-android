// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * The sizes art is decoded at, by the length its shorter side is brought down to: [Row] for the
 * 40 dp portraits beside piece rows, [Tile] for tiles, covers and headers, [Full] for the piece
 * sheet.
 */
enum class ArtSize(val px: Int) {
    Row(128),
    Tile(512),
    Full(1024),
}

/**
 * Decoded art in memory, least recently used out first, bounded by bytes (an eighth of the heap,
 * at most 48 MB). Every decode is two passes: the bounds first, then the pixels with a
 * power-of-two `inSampleSize` that brings the shorter side to at most the size asked for. Running
 * out of memory gives no picture (the fallback art shows), never a crash. Blocking: call it off
 * the main thread.
 */
class BitmapCache(maxBytes: Int = defaultBytes()) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** [file] decoded for [size]; [version] (when the file was written) keeps a replaced file from showing stale. */
    fun load(file: File, size: ArtSize, version: Long): Bitmap? {
        val key = keyOf(file, size, version)
        cache.get(key)?.let { return it }
        return decode(file, size.px)?.also { cache.put(key, it) }
    }

    /** [load], only if it is in memory already. Cheap: safe on the main thread. */
    fun peek(file: File, size: ArtSize, version: Long): Bitmap? = cache.get(keyOf(file, size, version))

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    private fun keyOf(file: File, size: ArtSize, version: Long) = "${file.path}@$version#${size.name}"

    companion object {
        private const val MAX_BYTES = 48 * 1024 * 1024

        fun defaultBytes(): Int = minOf(Runtime.getRuntime().maxMemory() / 8, MAX_BYTES.toLong()).toInt()

        /** Four megapixels: the most any picture is decoded to, whatever its shape (16 MB as ARGB). */
        const val MAX_DECODED_PIXELS = 4_000_000L

        /**
         * The smallest power of two that brings the shorter side of [width] × [height] to at most
         * [target], and the whole picture to at most [MAX_DECODED_PIXELS] (a panorama's shorter
         * side alone would let it decode huge). Pure.
         */
        fun sampleSize(width: Int, height: Int, target: Int): Int {
            val shorter = minOf(width, height)
            if (shorter <= 0 || target <= 0) return 1
            var sample = 1
            while (shorter / sample > target) sample *= 2
            while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODED_PIXELS) sample *= 2
            return sample
        }

        /** [file] decoded with its shorter side at most [target] px; null when it is not an image or memory runs out. */
        fun decode(file: File, target: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target) }
            return try {
                BitmapFactory.decodeFile(file.path, options)
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        /** Whether [bytes] are an image Android can decode (a bounds pass only). */
        fun isImage(bytes: ByteArray): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            return bounds.outWidth > 0 && bounds.outHeight > 0
        }
    }
}
