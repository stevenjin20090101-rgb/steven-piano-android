// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A picked photo made into a cover: read through the picker's short-lived grant, decoded no
 * larger than it needs to be (and never past [MAX_DECODED_PIXELS], whatever its shape), turned
 * upright from its EXIF orientation, scaled so its longer side is at most [MAX_PX] and saved as a
 * JPEG. Blocking; null when the photo cannot be read, whatever the framework throws.
 */
internal object PhotoImport {
    const val MAX_PX = 1024
    private const val QUALITY = 88

    /** Four megapixels: 16 MB decoded, whatever the photo's shape. */
    const val MAX_DECODED_PIXELS = 4_000_000L

    /** The power-of-two sample that brings [width] x [height] under [maxPx] on its longer side (at most twice it) and under [MAX_DECODED_PIXELS]. Pure. */
    fun sampleFor(width: Int, height: Int, maxPx: Int): Int {
        val longer = maxOf(width, height)
        var sample = 1
        while (longer / (sample * 2) >= maxPx) sample *= 2
        while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODED_PIXELS) sample *= 2
        return sample
    }

    fun jpeg(resolver: ContentResolver, uri: Uri, maxPx: Int): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxPx) }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            decoded?.let { encode(it, rotation(resolver, uri), maxPx) }
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {   // the grant is gone (SecurityException), a provider or the decoder gave up
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun encode(bitmap: Bitmap, degrees: Int, maxPx: Int): ByteArray {
        val scale = minOf(1f, maxPx.toFloat() / maxOf(bitmap.width, bitmap.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees.toFloat())
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
    }

    private fun rotation(resolver: ContentResolver, uri: Uri): Int = try {
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    } catch (e: RuntimeException) {   // malformed EXIF: the framework's reader throws more than IOException
        0
    }
}
