// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads a release's file to `<dir>/<versionName>.apk` ([dir] is `cacheDir/updates`, which the
 * app's FileProvider shares with Android's installer and nothing else). The bytes go to a
 * `.part` file while their SHA-256 is computed on the way; the file is renamed to its name only
 * once its size and hash match the manifest's. Anything else (a size past the manifest's or
 * [cap], a hash that differs) deletes it and fails with [UpdateFailures.MISMATCH]: nothing is
 * ever done with a file that did not match. Older downloads in [dir] are removed first.
 */
class UpdateDownloader(
    private val dir: File,
    private val server: UpdateServer,
    private val cap: Long = UpdateManifest.MAX_APK_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * The verified file for [manifest]; [progress] hears (bytes so far, total). Throws
     * [UpdateFailure] with the line to show, or CancellationException (the part file is removed).
     */
    suspend fun download(manifest: UpdateManifest, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(io) {
        val limit = minOf(cap, manifest.sizeBytes)
        if (!dir.isDirectory && !dir.mkdirs()) throw UpdateFailure(UpdateFailures.NO_ROOM)
        val target = File(dir, "${manifest.versionName}.apk")
        val part = File(dir, "${manifest.versionName}.apk.part")
        dir.listFiles()?.forEach { if (it != target) it.delete() }
        target.delete()
        val free = dir.usableSpace
        if (free in 1 until limit + FREE_MARGIN) throw UpdateFailure(UpdateFailures.NO_ROOM)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            FileOutputStream(part).use { out ->
                server.download(manifest.apkUrl, limit) { buffer, n ->
                    count += n
                    if (count > limit) throw UpdateFailure(UpdateFailures.MISMATCH)
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    progress(count, manifest.sizeBytes)
                }
                out.fd.sync()
            }
            // The hash is compared before anything else is done with the file.
            if (count != manifest.sizeBytes || hex(digest.digest()) != manifest.sha256) throw UpdateFailure(UpdateFailures.MISMATCH)
            if (!part.renameTo(target)) throw UpdateFailure(UpdateFailures.NO_ROOM)
            target
        } catch (e: UpdateFailure) {
            part.delete()
            throw e
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            throw UpdateFailure(if (count > 0) UpdateFailures.STOPPED else UpdateFailures.UNREACHABLE, e)
        }
    }

    /** Whether [file] still hashes to [manifest]'s SHA-256 (checked again just before installing). */
    suspend fun verified(file: File, manifest: UpdateManifest): Boolean = withContext(io) {
        if (!file.isFile || file.length() != manifest.sizeBytes) return@withContext false
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            return@withContext false
        }
        hex(digest.digest()) == manifest.sha256
    }

    /** Removes every download (a new process starts clean; the installer has its own copy by then). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Room left over after the file, so an update never fills the device. */
        const val FREE_MARGIN = 16L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
